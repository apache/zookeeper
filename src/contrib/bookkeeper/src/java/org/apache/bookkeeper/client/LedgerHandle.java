package org.apache.bookkeeper.client;
/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */


import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.TreeMap;

import org.apache.bookkeeper.client.BKDefs;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookieHandle;
import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.client.AsyncCallback.CloseCallback;
import org.apache.bookkeeper.client.AsyncCallback.ReadCallback;
import org.apache.bookkeeper.client.BKException.Code;
import org.apache.bookkeeper.client.LedgerManagementProcessor.CloseLedgerOp;
import org.apache.bookkeeper.client.QuorumEngine.Operation;
import org.apache.bookkeeper.client.QuorumEngine.Operation.AddOp;
import org.apache.bookkeeper.client.QuorumEngine.Operation.ReadOp;
import org.apache.bookkeeper.client.QuorumEngine.Operation.StopOp;
import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;

/**
 * Ledger handle on the client side. Contains ledger metadata
 * used to access it. This api exposes the read and write 
 * to a ledger and also exposes a streaming api for the ledger.
 */
public class LedgerHandle implements ReadCallback, AddCallback {
    /**
     * the call stack looks like --
     * ledgerhandle->write->bookeeper->quorumengine->bookiehandle
     * ->bookieclient
     */
   static Logger LOG = Logger.getLogger(LedgerHandle.class);
    
    public enum QMode {VERIFIABLE, GENERIC, FREEFORM};
    
    
    private long ledger;
    private volatile long last;
    private volatile long lastAddConfirmed = 0;
    private HashMap<Integer, Long> lastRecvCorrectly;
    private volatile ArrayList<BookieHandle> bookies;
    private ArrayList<InetSocketAddress> bookieAddrList;
    private TreeMap<Long, ArrayList<BookieHandle> > bookieConfigMap;
    private long[] entryChange;
    private BookKeeper bk;
    private QuorumEngine qe;
    private int qSize;
    private QMode qMode = QMode.VERIFIABLE;
    private int lMode;

    private int threshold;
    private String digestAlg = "SHA1";
    
    private byte[] macKey;
    private byte[] ledgerKey;
    private byte[] passwd;
    
    /**
     * @param bk the bookkeeper handle
     * @param ledger the id for this ledger
     * @param last the last id written 
     * @param passwd the passwd to encode
     * the entries
     * @throws InterruptedException
     */
    LedgerHandle(BookKeeper bk, 
            long ledger, 
            long last,
            byte[] passwd) throws InterruptedException {
        this.bk = bk;
        this.ledger = ledger;
        this.last = last;
        this.bookies = new ArrayList<BookieHandle>();
        this.lastRecvCorrectly = new HashMap<Integer, Long>();
        this.passwd = passwd;
        genLedgerKey(passwd);
        genMacKey(passwd);
        this.qSize = (bookies.size() + 1)/2;
        this.qe = new QuorumEngine(this);
    }
    
    /**
     * @param bk the bookkeeper handle
     * @param ledger the id for this ledger
     * @param last the last entree written
     * @param qSize the queuing size 
     * for this ledger
     * @param mode the quueuing mode
     * for this ledger
     * @param passwd the passwd to encode
     * @throws InterruptedException
     */
    LedgerHandle(BookKeeper bk, 
            long ledger, 
            long last,
            int qSize, 
            QMode mode,
            byte[] passwd) throws InterruptedException {
        this.bk = bk;
        this.ledger = ledger;
        this.last = last;
        this.bookies = new ArrayList<BookieHandle>();
        this.lastRecvCorrectly = new HashMap<Integer, Long>();


        this.qSize = qSize;
        this.qMode = mode;
        this.passwd = passwd;
        genLedgerKey(passwd);
        genMacKey(passwd);
        this.qe = new QuorumEngine(this);
    }
        
    /**
     * 
     * @param bk the bookkeeper handle
     * @param ledger the id for this ledger
     * @param last the last entree written
     * @param qSize the queuing size 
     * for this ledger
     * @param passwd the passwd to encode
     * @throws InterruptedException
     */
    LedgerHandle(BookKeeper bk, 
            long ledger, 
            long last,
            int qSize,
            byte[] passwd) throws InterruptedException {
        this.bk = bk;
        this.ledger = ledger;
        this.last = last;
        this.bookies = new ArrayList<BookieHandle>();
        this.lastRecvCorrectly = new HashMap<Integer, Long>();


        this.qSize = qSize;
        this.passwd = passwd;
        genLedgerKey(passwd);
        genMacKey(passwd);
        this.qe = new QuorumEngine(this);
    }
    
    private void setBookies(ArrayList<InetSocketAddress> bookies)
    throws InterruptedException {
    	try{
    		for(InetSocketAddress a : bookies){
    			LOG.debug("Opening bookieHandle: " + a);
            
    			//BookieHandle bh = new BookieHandle(this, a);
    			this.bookies.add(bk.getBookieHandle(this, a));
    		}
    	} catch(ConnectException e){
    		LOG.error(e);
    		InetSocketAddress addr = bk.getNewBookie(bookies);
    		if(addr != null){
    		    bookies.add(addr);
    		}
    	} catch(IOException e) {
    		LOG.error(e);
    	}
    }
    
    /**
     * set the quorum engine
     * @param qe the quorum engine
     */
    void setQuorumEngine(QuorumEngine qe) {
        this.qe = qe;
    }
    
    /** get the quorum engine
     * @return return the quorum engine
     */
    QuorumEngine getQuorumEngine() {
        return this.qe;
    }
    
    /**
     * Create bookie handle and add it to the list
     * 
     * @param addr	socket address
     */
    int addBookieForWriting(InetSocketAddress addr)
    throws IOException {
        LOG.debug("Bookie address: " + addr);
        lMode = BKDefs.WRITE;
        //BookieHandle bh = new BookieHandle(this, addr);
        this.bookies.add(bk.getBookieHandle(this, addr));
        if(bookies.size() > qSize) setThreshold();
        return (this.bookies.size() - 1);
    }
    
    /**
     * Create bookie handle and add it to the list
     * 
     * @param addr  socket address
     */
    int addBookieForReading(InetSocketAddress addr)
    throws IOException {
        LOG.debug("Bookie address: " + addr);
        lMode = BKDefs.READ;
        //BookieHandle bh = new BookieHandle(this, addr);
        try{
            this.bookies.add(bk.getBookieHandle(this, addr));
        } catch (IOException e){
            LOG.info("Inserting a decoy bookie handle");
            this.bookies.add(new BookieHandle(addr, false));
        }
        if(bookies.size() > qSize) setThreshold();
        return (this.bookies.size() - 1);
    }

    
    private void setThreshold() {
        switch(qMode){
        case GENERIC:
            threshold = bookies.size() - qSize/2;
            break;
        case VERIFIABLE:
            threshold = bookies.size() - qSize + 1;
            break;
        default:
            threshold = bookies.size();
        }
        
    }
    
    public int getThreshold() {
        return threshold;
    }
    
    
    /**
     * Writes to BookKeeper changes to the ensemble.
     *         
     * @param addr  Address of faulty bookie
     * @param entry Last entry written before change of ensemble.
     */
    
    void changeEnsemble(long entry){
        String path = BKDefs.prefix + 
        bk.getZKStringId(getId()) +  
        BKDefs.quorumEvolution + "/" + 
        String.format("%010d", entry);
        
        LOG.info("Report failure: " + String.format("%010d", entry));
        try{
            if(bk.getZooKeeper().exists(BKDefs.prefix + 
                    bk.getZKStringId(getId()) +  
                    BKDefs.quorumEvolution, false) == null)
                bk.getZooKeeper().create(BKDefs.prefix + bk.getZKStringId(getId()) + 
                        BKDefs.quorumEvolution, new byte[0], Ids.OPEN_ACL_UNSAFE, 
                        CreateMode.PERSISTENT);
        
            boolean first = true;
            String addresses = "";
            for(BookieHandle bh : bookies){
                if(first){ 
                    addresses = bh.addr.toString();
                    first = false;
                }
                else 
                    addresses = addresses + " " + bh.addr.toString();
            }
            
            bk.getZooKeeper() .create(path, addresses.getBytes(),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch(Exception e){
            LOG.error("Could not write to ZooKeeper: " + path + ", " + e);
        }
    }
    
    /**
     * Replace bookie in the case of a failure 
     */
    void replaceBookie(int index) 
    throws BKException {
        InetSocketAddress addr = null;
        try{
            addr = bk.getNewBookie(bookieAddrList);
        } catch(InterruptedException e){
            LOG.error(e);
        }
        
        if(addr == null){
            throw BKException.create(Code.NoBookieAvailableException);
        } else {           
            try{
                //BookieHandle bh = new BookieHandle(this, addr);
                
                /*
                 * TODO: Read from current bookies, and write to this one
                 */
                
                /*
                 * If successful in writing to new bookie, add it to the set
                 */
                this.bookies.set(index, bk.getBookieHandle(this, addr));
            } catch(ConnectException e){
                bk.blackListBookie(addr);
                LOG.error(e);
            } catch(IOException e) {
                bk.blackListBookie(addr);
                LOG.error(e);
            }
        }
    }
    
    /**
     * This method is used when BK cannot find a bookie
     * to replace the current faulty one. In such cases,
     * we simply remove the bookie.
     * 
     * 
     * @param BookieHandle
     */
    synchronized void removeBookie(BookieHandle bh){
       if(lMode == BKDefs.WRITE){
           LOG.info("Removing bookie: " + bh.addr);
           int index = bookies.indexOf(bh);
           if(index >= 0){
               Long tmpLastRecv = lastRecvCorrectly.get(index);
               bookies.remove(index);
        
               if(tmpLastRecv == null)
                   changeEnsemble(0);
               else
                   changeEnsemble(tmpLastRecv);
           }
       }
    }
    
    
    /**
     * Returns the ledger identifier
     * @return long
     */
    public long getId(){
        return ledger;
    }
    
    /**
     * Returns the last entry identifier submitted
     * @return long
     */
    public long getLast(){
        return last;   
    }
    
    /**
     * Returns the last entry identifier submitted and increments it.
     * @return long
     */
    long incLast(){
        return last++;
    }
    
    /**
     * Sets the last entry identifier submitted.
     * 
     * @param   last    last entry
     * @return  long    returns the value just set
     */
    long setLast(long last){
        this.last = last;
        return this.last;
    }
    
    /**
     * Sets the value of the last add confirmed. This is used
     * when adding new entries, since we use this value as a hint
     * to recover from failures of the client.
     */
    void setAddConfirmed(long entryId){
        if(entryId > lastAddConfirmed)
            lastAddConfirmed = entryId;
    }
    
    long getAddConfirmed(){
        return lastAddConfirmed;
    }
    
    void setLastRecvCorrectly(int sId, long entry){
        //LOG.info("Setting last received correctly: " + entry);
        lastRecvCorrectly.put(sId, entry);
    }
    
    /**
     * Returns the list of bookies
     * @return ArrayList<BookieHandle>
     */
    ArrayList<BookieHandle> getBookies(){
        return bookies;
    }
    
    /**
     * For reads, there might be multiple operations.
     * 
     * @param entry
     * @return ArrayList<BookieHandle>  returns list of bookies
     */
    ArrayList<BookieHandle> getBookies(long entry){
        return getConfig(entry);
    }
    
    /**
     * Returns the bookie handle corresponding to the addresses in the input.
     * 
     * @param addr
     * @return
     */
    BookieHandle getBookieHandleDup(InetSocketAddress addr){
        for(BookieHandle bh : bookies){
            if(bh.addr.equals(addr))
                return bh;
        }
        
        return null;
    }
    
    /**
     * Sets a new bookie configuration corresponding to a failure during
     * writes to the ledger. We have one configuration for every failure.
     * 
     * @param entry
     * @param list
     */
    
    void setNewBookieConfig(long entry, ArrayList<BookieHandle> list){
        if(bookieConfigMap == null)
            bookieConfigMap = new TreeMap<Long, ArrayList<BookieHandle> >();
        
        /*
         * If initial config is not in the list, we include it.
         */
        if(!bookieConfigMap.containsKey(new Long(0))){
            bookieConfigMap.put(new Long(0), bookies);
        }
        
        LOG.info("Adding new entry: " + entry + ", " + bookies.size() + ", " + list.size());
        bookieConfigMap.put(entry, list);
    }
    
    /**
     * Once we read all changes to the bookie configuration, we
     * have to call this method to generate an array that we use
     * to determine the bookie configuration for an entry.
     * 
     * Note that this array is a performance optimization and 
     * it is not necessary for correctness. We could just use 
     * bookieConfigMap but it would be slower.
     */
    
    void prepareEntryChange(){
        entryChange = new long[bookieConfigMap.size()];
    
        int counter = 0;
        for(Long l : bookieConfigMap.keySet()){
            entryChange[counter++] = l;
        }
    }
    
    /**
     * Return the quorum size. By default, the size of a quorum is (n+1)/2, 
     * where n is the size of the set of bookies.
     * @return int
     */
    int getQuorumSize(){
        return qSize;   
    }
    
    
    /**
     *  Returns the config corresponding to the entry
     *  
     * @param entry
     * @return
     */
    private ArrayList<BookieHandle> getConfig(long entry){
        if(bookieConfigMap == null)
            return bookies;
        
        int index = Arrays.binarySearch(entryChange, entry);
        
        /*
         * If not on the map, binarySearch returns a negative value
         */
        int before = index;
        index = index >= 0? index : ((-1) - index);

        if(index == 0){
            if((entry % 10) == 0){
                LOG.info("Index: " + index + ", " + before + ", " + entry + ", " + bookieConfigMap.get(entryChange[index]).size());
            }
            return bookieConfigMap.get(entryChange[index]); 
        } else{
            //LOG.warn("IndexDiff " + entry);
            return bookieConfigMap.get(entryChange[index - 1]);
        }
    }
    
    /**
     * Returns the quorum mode for this ledger: Verifiable or Generic
     */
    QMode getQMode(){
        return qMode;   
    }
    
    /**
     * Sets message digest algorithm.
     */
    
    void setDigestAlg(String alg){
        this.digestAlg = alg;
    }
    
    /**
     * Get message digest algorithm.
     */
    
    String getDigestAlg(){
        return digestAlg;
    }
    
    /**
     * Generates and stores Ledger key.
     * 
     * @param passwd
     */
    
    private void genLedgerKey(byte[] passwd){
        try{
            MessageDigest digest = MessageDigest.getInstance("SHA");
            String pad = "ledger";
            
            byte[] toProcess = new byte[passwd.length + pad.length()];
            System.arraycopy(pad.getBytes(), 0, toProcess, 0, pad.length());
            System.arraycopy(passwd, 0, toProcess, pad.length(), passwd.length);
        
            digest.update(toProcess);
            this.ledgerKey = digest.digest();
        } catch(NoSuchAlgorithmException e){
            this.passwd = passwd;
            LOG.error("Storing password as plain text because secure hash implementation does not exist");
        }
    }
    
    /**
     * Generates and stores Mac key.
     * 
     * @param passwd
     */
    
    private void genMacKey(byte[] passwd){
        try{
            MessageDigest digest = MessageDigest.getInstance("SHA");
            String pad = "mac";
            
            byte[] toProcess = new byte[passwd.length + pad.length()];
            System.arraycopy(pad.getBytes(), 0, toProcess, 0, pad.length());
            System.arraycopy(passwd, 0, toProcess, pad.length(), passwd.length);
        
            digest.update(toProcess);
            this.macKey = digest.digest();
        } catch(NoSuchAlgorithmException e){
            this.passwd = passwd;
            LOG.error("Storing password as plain text because secure hash implementation does not exist");
        }
    }
    
    /**
     * Returns password in plain text
     */
    byte[] getPasswd(){
    	return passwd;
    }
    
    
    /**
     * Returns MAC key
     * 
     * @return byte[]
     */
    byte[] getMacKey(){
       return macKey; 
    }
   
    /**
     * Returns Ledger key
     * 
     * @return byte[]
     */
    byte[] getLedgerKey(){
       return ledgerKey; 
    }
    
    void closeUp(){
        ledger = -1;
        last = -1;
        bk.haltBookieHandles(this, bookies);
    }
    
    /**
     * Close ledger.
     * 
     */
    public void close() 
    throws KeeperException, InterruptedException, BKException {
        //Set data on zookeeper
        ByteBuffer last = ByteBuffer.allocate(8);
        last.putLong(lastAddConfirmed);
        LOG.info("Last saved on ZK is: " + lastAddConfirmed);
        String closePath = BKDefs.prefix + bk.getZKStringId(getId()) + BKDefs.close; 
        if(bk.getZooKeeper().exists(closePath, false) == null){
           bk.getZooKeeper().create(closePath, 
                   last.array(), 
                   Ids.OPEN_ACL_UNSAFE, 
                   CreateMode.PERSISTENT); 
        } 
        
        closeUp();
        StopOp sOp = new StopOp();
        qe.sendOp(sOp);
        LOG.info("##### CB worker queue size: " + qe.cbWorker.pendingOps.size());
    }
    
    /**
     * Asynchronous close
     *
     * @param cb    callback implementation
     * @param ctx   control object
     * @throws InterruptedException
     */
    public void asyncClose(CloseCallback cb, Object ctx)
    throws InterruptedException {
        CloseLedgerOp op = new CloseLedgerOp(this, cb, ctx);
        LedgerManagementProcessor lmp = bk.getMngProcessor();
        lmp.addOp(op);  
    }
       
    /**
     * Read a sequence of entries asynchronously.
     * 
     * @param firstEntry    id of first entry of sequence
     * @param lastEntry     id of last entry of sequence
     * @param cb    object implementing read callback interface
     * @param ctx   control object 
     */
    public void asyncReadEntries(long firstEntry, 
            long lastEntry, ReadCallback cb, Object ctx)
    throws BKException, InterruptedException {
        // Little sanity check
        if((firstEntry > getLast()) || (firstEntry > lastEntry)) 
            throw BKException.create(Code.ReadException);
        
        Operation r = new ReadOp(this, firstEntry, lastEntry, cb, ctx);
        qe.sendOp(r); 
        //qeMap.get(lh.getId()).put(r);
    }
    
    
    /**
     * Read a sequence of entries synchronously.
     * 
     * @param firstEntry    id of first entry of sequence
     * @param lastEntry     id of last entry of sequence
     *
     */
    public LedgerSequence readEntries(long firstEntry, long lastEntry) 
    throws InterruptedException, BKException {
        // Little sanity check
        if((firstEntry > getLast()) || (firstEntry > lastEntry))
            throw BKException.create(Code.ReadException);
        
        RetCounter counter = new RetCounter();
        counter.inc();
     
        Operation r = new ReadOp(this, firstEntry, lastEntry, this, counter);
        qe.sendOp(r);
        
        LOG.debug("Going to wait for read entries: " + counter.i);
        counter.block(0);
        LOG.debug("Done with waiting: " + counter.i + ", " + firstEntry);
        
        if(counter.getSequence() == null){
            LOG.error("Failed to read entries: " + firstEntry + ", " + lastEntry);
            throw BKException.create(Code.ReadException);
        }
        return counter.getSequence();
    }
   
    /**
     * Add entry asynchronously to an open ledger.
     * 
     * @param data  array of bytes to be written
     * @param cb    object implementing callbackinterface
     * @param ctx   some control object
     */
    public void asyncAddEntry(byte[] data, AddCallback cb, Object ctx)
    throws InterruptedException, BKException {
        AddOp r = new AddOp(this, data, cb, ctx);
        qe.sendOp(r);
    }
    
    
    /**
     * Add entry synchronously to an open ledger.
     * 
     * @param   data byte[]
     */
    
    public long addEntry(byte[] data)
    throws InterruptedException, BKException{
        LOG.debug("Adding entry " + data);
        RetCounter counter = new RetCounter();
        counter.inc();
        
        Operation r = new AddOp(this, data, this, counter);
        qe.sendOp(r);   
        //qeMap.get(lh.getId()).put(r);
        counter.block(0);
        return counter.getrc();
    }
    
    
    /**
     * Implementation of callback interface for synchronous read method.
     * 
     * @param rc    return code
     * @param leder ledger identifier
     * @param seq   sequence of entries
     * @param ctx   control object
     */
    public void readComplete(int rc, 
            LedgerHandle lh,
            LedgerSequence seq,  
            Object ctx){        
        
        RetCounter counter = (RetCounter) ctx;
        counter.setSequence(seq);
        LOG.debug("Read complete: " + seq.size() + ", " + counter.i);
        counter.dec();
    }
    
    /**
     * Implementation of callback interface for synchronous read method.
     * 
     * @param rc    return code
     * @param leder ledger identifier
     * @param entry entry identifier
     * @param ctx   control object
     */
    public void addComplete(int rc, 
            LedgerHandle lh,
            long entry, 
            Object ctx){          
        RetCounter counter = (RetCounter) ctx;
        
        counter.setrc(rc);
        counter.dec();
    }
    
    
    
    /**
     * Implements objects to help with the synchronization of asynchronous calls
     * 
     */
    
    private static class RetCounter {
        int i;
        int rc;
        int total;
        LedgerSequence seq = null;
        
        synchronized void inc() {
            i++;
            total++;
        }
        synchronized void dec() {
            i--;
            notifyAll();
        }
        synchronized void block(int limit) throws InterruptedException {
            while(i > limit) {
                int prev = i;
                wait(15000);
                if(i == prev){
                    break;
                }
            }
        }
        synchronized int total() {
            return total;
        }
        
        void setrc(int rc){
            this.rc = rc;
        }
        
        int getrc(){
            return rc;
        }
        
        void setSequence(LedgerSequence seq){
            this.seq = seq;
        }
        
        LedgerSequence getSequence(){
            return seq;
        }
    }
}
