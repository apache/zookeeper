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
import java.io.ByteArrayOutputStream;
import java.security.NoSuchAlgorithmException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Random;
import java.net.InetSocketAddress;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.LedgerSequence;
import org.apache.bookkeeper.client.BKException.Code;
import org.apache.bookkeeper.client.LedgerHandle.QMode;
import org.apache.bookkeeper.client.QuorumEngine.Operation;
import org.apache.bookkeeper.client.QuorumEngine.Operation.AddOp;
import org.apache.bookkeeper.client.QuorumEngine.Operation.ReadOp;
import org.apache.bookkeeper.client.QuorumEngine.Operation.StopOp;
import org.apache.log4j.Logger;

import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.server.ZooKeeperServer;


/**
 * BookKeeper client. We assume there is one single writer 
 * to a ledger at any time. 
 * 
 * There are three possible operations: start a new ledger, 
 * write to a ledger, and read from a ledger.
 * 
 *
 */

public class BookKeeper 
implements ReadCallback, AddCallback, Watcher {
    
    Logger LOG = Logger.getLogger(BookKeeper.class);

    static public final String prefix = "/ledgers/L";
    static public final String ensemble = "/ensemble"; 
    static public final String quorumSize = "/quorum";
    static public final String close = "/close";
    static public final String quorumMode = "/mode";
    
    ZooKeeper zk = null;
    QuorumEngine engine = null;
    MessageDigest md = null;
    //HashMap<Long, ArrayBlockingQueue<Operation> > qeMap;
    HashMap<Long, QuorumEngine> engines;
    HashSet<InetSocketAddress> bookieBlackList;
    
    LedgerSequence responseRead;
    Long responseLong;
    
    public BookKeeper(String servers) 
    throws KeeperException, IOException{
    	LOG.debug("Creating BookKeeper for servers " + servers);
        //Create ZooKeeper object
        this.zk = new ZooKeeper(servers, 10000, this);
        
        //Create hashmap for quorum engines
        //this.qeMap = new HashMap<Long, ArrayBlockingQueue<Operation> >();
        this.engines = new HashMap<Long, QuorumEngine >();
        //List to enable clients to blacklist bookies
        this.bookieBlackList = new HashSet<InetSocketAddress>();
    }
    
    /**
     * Watcher method. 
     */
    synchronized public void process(WatchedEvent event) {
        LOG.info("Process: " + event.getType() + " " + event.getPath());
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
    
    /**
     * Formats ledger ID according to ZooKeeper rules
     * 
     * @param id	znode id
     */
    private String getZKStringId(long id){
        return String.format("%010d", id);        
    }
    
    
    /**
     * Creates a new ledger. To create a ledger, we need to specify the ensemble
     * size, the quorum size, the operation mode, and a password. The ensemble size
     * and the quorum size depend upon the operation mode. The operation mode can be
     * GENERIC, VERIFIABLE, or FREEFORM (debugging). The password is used not only
     * to authenticate access to a ledger, but also to verify entries in verifiable
     * ledgers.
     * 
     * @param ensSize   ensemble size
     * @param qSize     quorum size
     * @param mode      quorum mode: VERIFIABLE (default), GENERIC, or FREEFORM
     * @param passwd    password
     */
    public LedgerHandle createLedger(int ensSize, int qSize, QMode mode,  byte passwd[])
    throws KeeperException, InterruptedException, 
    IOException, BKException {
        // Check that quorum size follows the minimum
        long t;
        switch(mode){
        case VERIFIABLE:
            t = java.lang.Math.round(java.lang.Math.floor((ensSize - 1)/2));
            if(t == 0){
                LOG.error("Tolerates 0 bookie failures"); 
                throw BKException.create(Code.QuorumException);
            }
            break;
        case GENERIC:
            t = java.lang.Math.round(java.lang.Math.floor((ensSize - 1)/3));
            if(t == 0){
                LOG.error("Tolerates 0 bookie failures"); 
                throw BKException.create(Code.QuorumException);
            }
            break;
        case FREEFORM:
            break;
        }
        
        /*
         * Create ledger node on ZK.
         * We get the id from the sequence number on the node.
         */
        
        String path = zk.create(prefix, new byte[0], 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
        
        /* 
         * Extract ledger id.
         */
        String parts[] = path.split("/");
        String subparts[] = parts[2].split("L");
        System.out.println("SubPath: " + subparts[0]);
        long lId = Long.parseLong(subparts[1]);
               
        /* 
         * Get children from "/ledgers/available" on zk
         */
        List<String> list = 
            zk.getChildren("/ledgers/available", false);
        ArrayList<InetSocketAddress> lBookies = new ArrayList<InetSocketAddress>();
        
        /* 
         * Select ensSize servers to form the ensemble
         */
        System.out.println("create: " + (prefix + getZKStringId(lId) + ensemble));
        path = zk.create(prefix + getZKStringId(lId) + ensemble, new byte[0], 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        
        /* 
         * Add quorum size to ZK metadata
         */
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.putInt(qSize);
        zk.create(prefix + getZKStringId(lId) + quorumSize, bb.array(), 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        
        /* 
         * Quorum mode
         */
        bb = ByteBuffer.allocate(4);
        bb.putInt(mode.ordinal());
        zk.create(prefix + getZKStringId(lId) + quorumMode, bb.array(), 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        
        /* 
         * Create QuorumEngine
         */
        LedgerHandle lh = new LedgerHandle(this, lId, 0, qSize, mode, passwd);
        //ArrayBlockingQueue<Operation> queue = new ArrayBlockingQueue<Operation>(200);
        engines.put(lh.getId(), new QuorumEngine(lh)); //queue));
        //qeMap.put(lId, queue);
        
        /*
         * Adding bookies to ledger handle
         */
        Random r = new Random();
        
        for(int i = 0; i < ensSize; i++){
        	int index = 0;
        	if(list.size() > 1) 
        		index = r.nextInt(list.size() - 1);
        	else if(list.size() == 1)
        	    index = 0;
        	else {
        	    LOG.error("Not enough bookies available");
        	    engines.remove(lh.getId());
        	    
        	    return null;
        	}
            
        	try{
        	    String bookie = list.remove(index);
        	    LOG.info("Bookie: " + bookie);
        	    InetSocketAddress tAddr = parseAddr(bookie);
        	    lh.addBookie(tAddr);         	
        	    String pBookie = "/" + bookie;
        	    zk.create(prefix + getZKStringId(lId) + ensemble + pBookie, new byte[0], 
        	            Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        	} catch (IOException e) {
        	    LOG.error(e);
        	    i--;
        	} 
        }
      
        LOG.debug("Created new ledger");
        // Return ledger handler
        return lh; 
    }

    /**
     * Creates a new ledger. Default of 3 servers, and quorum of 2 servers,
     * verifiable ledger.
     * 
     * @param passwd	password
     */
    public LedgerHandle createLedger(byte passwd[])
    throws KeeperException, BKException, 
    InterruptedException, IOException {
        return createLedger(3, 2, QMode.VERIFIABLE, passwd);
    }


    
    /**
     * Open existing ledger for reading. Default for quorum size is 2.
     * 
     * @param long  the long corresponding to the ledger id
     * @param byte[]    byte array corresponding to the password to access a ledger
     * @param int   the quorum size, it has to be at least ceil(n+1/2)
     */
    public LedgerHandle openLedger(long lId, byte passwd[])
    throws KeeperException, InterruptedException, IOException, BKException {
        
        Stat stat = null;
        
        /*
         * Check if ledger exists
         */
        if(zk.exists(prefix + getZKStringId(lId), false) == null){
            LOG.error("Ledger " + getZKStringId(lId) + " doesn't exist.");
            return null;
        }
        
        /*
         * Get quorum size.
         */
        ByteBuffer bb = ByteBuffer.wrap(zk.getData(prefix + getZKStringId(lId) + quorumSize, false, stat));
        int qSize = bb.getInt();
         
        /*
         * Get last entry written from ZK 
         */
        
        long last = 0;
        LOG.debug("Close path: " + prefix + getZKStringId(lId) + close);
        if(zk.exists(prefix + getZKStringId(lId) + close, false) == null){
            recoverLedger(lId, passwd);
        }
            
        stat = null;
        byte[] data = zk.getData(prefix + getZKStringId(lId) + close, false, stat);
        ByteBuffer buf = ByteBuffer.wrap(data);
        last = buf.getLong();
        //zk.delete(prefix + getZKStringId(lId) + close, -1);
        
        /*
         * Quorum mode 
         */
        data = zk.getData(prefix + getZKStringId(lId) + quorumMode, false, stat);
        buf = ByteBuffer.wrap(data);
        //int ordinal = buf.getInt();
        
        QMode qMode;
        switch(buf.getInt()){
        case 1:
            qMode = QMode.GENERIC;
            LOG.info("Generic ledger");
            break;
        case 2:
            qMode = QMode.FREEFORM;
            break;
        default:
            qMode = QMode.VERIFIABLE;
            LOG.info("Verifiable ledger");
        }
        
        /*
         *  Create QuorumEngine
         */
        LedgerHandle lh = new LedgerHandle(this, lId, last, qSize, qMode, passwd);
        engines.put(lh.getId(), new QuorumEngine(lh));// queue));
        
        /*
         * Get children of "/ledgers/id/ensemble" 
         */
        
        List<String> list = 
            zk.getChildren(prefix + getZKStringId(lId) + ensemble, false);
                
        for(String s : list){
            try{
                lh.addBookie(parseAddr(s));
            } catch (IOException e){
                LOG.error(e);
            }
        }
      
        // Return ledger handler
        return lh;
    }    
    
    /**
     * Parses address into IP and port.
     * 
     *  @param addr	String
     */
    
    private InetSocketAddress parseAddr(String s){
        String parts[] = s.split(":");
        if (parts.length != 2) {
            System.out.println(s
                    + " does not have the form host:port");
        }
        InetSocketAddress addr = new InetSocketAddress(parts[0],
                Integer.parseInt(parts[1]));
        return addr;
    }
    
    public void initMessageDigest(String alg)
    throws NoSuchAlgorithmException {
        md = MessageDigest.getInstance(alg);
    }
    
    /**
     * Add entry synchronously to an open ledger.
     * 
     * @param	lh	LedgerHandle
     * @param	data byte[]
     */
    
    public long addEntry(LedgerHandle lh, byte[] data)
    throws InterruptedException{
        LOG.debug("Adding entry " + data);
        RetCounter counter = new RetCounter();
        counter.inc();
        
        if(lh != null){ 
        	Operation r = new AddOp(lh, data, this, counter);
        	engines.get(lh.getId()).sendOp(r);
        	//qeMap.get(lh.getId()).put(r);
        
        	counter.block(0);
        
        	return counter.getrc();
        } else return -1;
    }
    
    /**
     * Add entry asynchronously to an open ledger.
     * 
     * @param lh	ledger handle returned with create
     * @param data	array of bytes to be written
     * @param cb	object implementing callbackinterface
     * @param ctx	some control object
     */
    public void asyncAddEntry(LedgerHandle lh, byte[] data, AddCallback cb, Object ctx)
    throws InterruptedException {
        LOG.debug("Adding entry asynchronously: " + data);
        //lh.incLast();
        if(lh != null){
            AddOp r = new AddOp(lh, data, cb, ctx);
            engines.get(lh.getId()).sendOp(r);
        }
        //qeMap.get(lh.getId()).put(r);
    }
    
    /**
     * Add entry asynchronously to an open ledger.
     */
    //public  void asyncAddEntryVerifiable(LedgerHandle lh, byte[] data, AddCallback cb, Object ctx)
    //throws InterruptedException, IOException, BKException, NoSuchAlgorithmException {
    //    if(md == null)
    //        throw BKException.create(Code.DigestNotInitializedException);
    //        
    //    LOG.info("Data size: " + data.length);
    //    AddOp r = new AddOp(lh, data, cb, ctx);
    //    //r.addDigest();
    //    LOG.info("Data length: " + r.data.length);
    //    engines.get(lh.getId()).sendOp(r);
    //    //qeMap.get(lh.getId()).put(r);
    //}
    
    
    /**
     * Read a sequence of entries synchronously.
     * 
     * @param lh	ledger handle returned with create
     * @param firstEntry	id of first entry of sequence
     * @param lastEntry		id of last entry of sequence
     *
     */
    public LedgerSequence readEntries(LedgerHandle lh, long firstEntry, long lastEntry) 
    throws InterruptedException, BKException {
        // Little sanity check
        if((firstEntry > lh.getLast()) || (firstEntry > lastEntry))
            throw BKException.create(Code.ReadException);
        
        RetCounter counter = new RetCounter();
        counter.inc();
        
        Operation r = new ReadOp(lh, firstEntry, lastEntry, this, counter);
        engines.get(lh.getId()).sendOp(r);
        //qeMap.get(lh.getId()).put(r);
        LOG.debug("Going to wait for read entries: " + counter.i);
        counter.block(0);
        LOG.debug("Done with waiting: " + counter.i + ", " + firstEntry);
        
        if(counter.getSequence() == null) throw BKException.create(Code.ReadException);
        return counter.getSequence();
    }
    
    /**
     * Read a sequence of entries asynchronously.
     * 
     * @param lh	ledger handle
     * @param firstEntry	id of first entry of sequence
     * @param lastEntry		id of last entry of sequence
     * @param cb	object implementing read callback interface
     * @param ctx	control object 
     */
    public void asyncReadEntries(LedgerHandle lh, long firstEntry, long lastEntry, ReadCallback cb, Object ctx)
    throws BKException, InterruptedException {
        // Little sanity check
        if((firstEntry > lh.getLast()) || (firstEntry > lastEntry)) 
            throw BKException.create(Code.ReadException);
        
        Operation r = new ReadOp(lh, firstEntry, lastEntry, cb, ctx);
        engines.get(lh.getId()).sendOp(r); 
        //qeMap.get(lh.getId()).put(r);
    }
    
    /**
     * Close ledger.
     * 
     * @param lh	handle of ledger to close
     */
    public void closeLedger(LedgerHandle lh) 
    throws KeeperException, InterruptedException {
        //Set data on zookeeper
        ByteBuffer last = ByteBuffer.allocate(8);
        last.putLong(lh.getLast());
        LOG.info("Last saved on ZK is: " + lh.getLast());
        String closePath = prefix + getZKStringId(lh.getId()) + close; 
        if(zk.exists(closePath, false) == null){
           zk.create(closePath, 
                   last.array(), 
                   Ids.OPEN_ACL_UNSAFE, 
                   CreateMode.PERSISTENT); 
        } else {
            zk.setData(closePath, 
                last.array(), -1);
        }
        lh.close();
        for(QuorumEngine qe : engines.values()){
        	StopOp sOp = new StopOp();
        	qe.sendOp(sOp);
        }
    }
    
    /**
     * Check if close node exists. 
     * 
     * @param ledgerId	id of the ledger to check
     */
    public boolean hasClosed(long ledgerId)
    throws KeeperException, InterruptedException{
        String closePath = prefix + getZKStringId(ledgerId) + close;
        if(zk.exists(closePath, false) == null) return false;
        else return true;
    }
    
    /**
     * Recover a ledger that was not closed properly.
     * 
     * @param lId	ledger identifier
     * @param passwd	password
     */
    
    private boolean recoverLedger(long lId, byte passwd[])
    throws KeeperException, InterruptedException, IOException, BKException {
        
        Stat stat = null;
       
        LOG.info("Recovering ledger");
        
        /*
         * Get quorum size.
         */
        ByteBuffer bb = ByteBuffer.wrap(zk.getData(prefix + getZKStringId(lId) + quorumSize, false, stat));
        int qSize = bb.getInt();
                
        
        /*
         * Get children of "/ledgers/id/ensemble" 
         */
        
        List<String> list = 
            zk.getChildren(prefix + getZKStringId(lId) + ensemble, false);
        
        ArrayList<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>();
        for(String s : list){
            addresses.add(parseAddr(s));
        }
        
        /*
         * Quorum mode 
         */
        byte[] data = zk.getData(prefix + getZKStringId(lId) + quorumMode, false, stat);
        ByteBuffer buf = ByteBuffer.wrap(data);
        //int ordinal = buf.getInt();
            
        QMode qMode = QMode.VERIFIABLE;
        switch(buf.getInt()){
        case 0:
            qMode = QMode.VERIFIABLE;
            break;
        case 1:
            qMode = QMode.GENERIC;
            break;
        case 2:
            qMode = QMode.FREEFORM;
            break;
        }
        
        /*
         * Create ledger recovery monitor object
         */
        
        LedgerRecoveryMonitor lrm = new LedgerRecoveryMonitor(this, lId, qSize, addresses, qMode);
        
        return lrm.recover(passwd);
    }
    
    /**
     * Get new bookies
     * 
     * @param addrList	list of bookies to replace
     */
    public InetSocketAddress getNewBookie(ArrayList<InetSocketAddress> addrList)
    throws InterruptedException {
        try{
            // Get children from "/ledgers/available" on zk
            List<String> list = 
                zk.getChildren("/ledgers/available", false);
            ArrayList<InetSocketAddress> lBookies = new ArrayList<InetSocketAddress>();
    
            for(String addr : list){
                InetSocketAddress nAddr = parseAddr(addr); 
                if(!addrList.contains(nAddr) &&
                        !bookieBlackList.contains(nAddr))
                    return nAddr;
            }
        } catch (KeeperException e){
            LOG.error("Problem accessing ZooKeeper: " + e);
        }
        
        return null;
    }
    
    /**
     * Blacklists bookies.
     * 
     * @param addr 	address of bookie
     */
    void blackListBookie(InetSocketAddress addr){
        bookieBlackList.add(addr);
    }
    
    /**
     * Implementation of callback interface for synchronous read method.
     * 
     * @param rc	return code
     * @param leder	ledger identifier
     * @param seq	sequence of entries
     * @param ctx	control object
     */
    public void readComplete(int rc, 
            long ledger, 
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
     * @param rc	return code
     * @param leder	ledger identifier
     * @param entry	entry identifier
     * @param ctx	control object
     */
    public void addComplete(int rc, 
            long ledger, 
            long entry, 
            Object ctx){          
        RetCounter counter = (RetCounter) ctx;
        
        counter.setrc(rc);
        counter.dec();
    }
}
