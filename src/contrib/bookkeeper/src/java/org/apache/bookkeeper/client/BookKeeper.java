package org. apache.bookkeeper.client;
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
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Random;
import java.net.InetSocketAddress;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookieHandle;
import org.apache.bookkeeper.client.LedgerSequence;
import org.apache.bookkeeper.client.AsyncCallback.CreateCallback;
import org.apache.bookkeeper.client.AsyncCallback.OpenCallback;
import org.apache.bookkeeper.client.BKException.Code;
import org.apache.bookkeeper.client.LedgerHandle.QMode;
import org.apache.bookkeeper.client.LedgerManagementProcessor.CreateLedgerOp;
import org.apache.bookkeeper.client.LedgerManagementProcessor.OpenLedgerOp;
import org.apache.log4j.Logger;

import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.WatchedEvent;


/**
 * BookKeeper client. We assume there is one single writer 
 * to a ledger at any time. 
 * 
 * There are three possible operations: start a new ledger, 
 * write to a ledger, and read from a ledger.
 * 
 * For the ZooKeeper layout, please refer to BKDefs.java.
 * 
 */

public class BookKeeper 
implements Watcher {
 
    Logger LOG = Logger.getLogger(BookKeeper.class);
    
    ZooKeeper zk = null;
    
    /*
     * The ledgerMngProcessor is a thread that processes
     * asynchronously requests that handle ledgers, such
     * as create, open, and close.
     */
    private static LedgerManagementProcessor ledgerMngProcessor;
    
    /*
     * Blacklist of bookies
     */
    HashSet<InetSocketAddress> bookieBlackList;
    
    LedgerSequence responseRead;
    Long responseLong;
    
    public BookKeeper(String servers) 
    throws KeeperException, IOException{
    	LOG.debug("Creating BookKeeper for servers " + servers);
        //Create ZooKeeper object
        this.zk = new ZooKeeper(servers, 10000, this);
        
        //List to enable clients to blacklist bookies
        this.bookieBlackList = new HashSet<InetSocketAddress>();
    }
    
    /**
     * Watcher method. 
     */
    synchronized public void process(WatchedEvent event) {
        LOG.debug("Process: " + event.getType() + " " + event.getPath());
    }
    
    /**
     * Formats ledger ID according to ZooKeeper rules
     * 
     * @param id	znode id
     */
    String getZKStringId(long id){
        return String.format("%010d", id);        
    }
    
    /**
     * return the zookeeper instance
     * @return return the zookeeper instance
     */
    ZooKeeper getZooKeeper() {
        return zk;
    }
    
    LedgerManagementProcessor getMngProcessor(){
        if (ledgerMngProcessor == null){
            ledgerMngProcessor = new LedgerManagementProcessor(this);
            ledgerMngProcessor.start();
        }
        return ledgerMngProcessor;
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
        LedgerHandle lh = null;
        
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
        String path = zk.create(BKDefs.prefix, new byte[0], 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
        /* 
         * Extract ledger id.
         */
        String parts[] = path.split("/");
        String subparts[] = parts[2].split("L");
        try{
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
            path = zk.create(BKDefs.prefix + getZKStringId(lId) + BKDefs.ensemble, new byte[0], 
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
         
            /* 
             * Add quorum size to ZK metadata
             */
            ByteBuffer bb = ByteBuffer.allocate(4);
            bb.putInt(qSize);
            zk.create(BKDefs.prefix + getZKStringId(lId) + BKDefs.quorumSize, bb.array(), 
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            /* 
             * Quorum mode
             */
            bb = ByteBuffer.allocate(4);
            bb.putInt(mode.ordinal());
            zk.create(BKDefs.prefix + getZKStringId(lId) + BKDefs.quorumMode, bb.array(), 
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            /* 
             * Create QuorumEngine
             */
            lh = new LedgerHandle(this, lId, 0, qSize, mode, passwd);
            
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
        	    
                    return null;
                }
            
                try{
                    String bookie = list.remove(index);
                    LOG.info("Bookie: " + bookie);
                    InetSocketAddress tAddr = parseAddr(bookie);
                    int bindex = lh.addBookieForWriting(tAddr); 
                    ByteBuffer bindexBuf = ByteBuffer.allocate(4);
                    bindexBuf.putInt(bindex);
        	    
                    String pBookie = "/" + bookie;
                    zk.create(BKDefs.prefix + getZKStringId(lId) + BKDefs.ensemble + pBookie, bindexBuf.array(), 
                            Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                } catch (IOException e) {
                    LOG.error(e);
                    i--;
                } 
            }
            LOG.debug("Created new ledger");
        } catch (NumberFormatException e) {
            LOG.error("Error when parsing the ledger identifier", e);
        }
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
     * Asychronous call to create ledger
     * 
     * @param ensSize
     * @param qSize
     * @param mode
     * @param passwd
     * @param cb
     * @param ctx
     * @throws KeeperException
     * @throws InterruptedException
     * @throws IOException
     * @throws BKException
     */
    public void asyncCreateLedger(int ensSize, 
            int qSize, 
            QMode mode,  
            byte passwd[],
            CreateCallback cb,
            Object ctx
            )
    throws KeeperException, InterruptedException, 
    IOException, BKException {
        CreateLedgerOp op = new CreateLedgerOp(ensSize, 
                qSize, 
                mode, 
                passwd, 
                cb, 
                ctx);
        LedgerManagementProcessor lmp = getMngProcessor();
        lmp.addOp(op);
        
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
        if(zk.exists(BKDefs.prefix + getZKStringId(lId), false) == null){
            LOG.error("Ledger " + getZKStringId(lId) + " doesn't exist.");
            throw BKException.create(Code.NoSuchLedgerExistsException);
        }
        
        /*
         * Get quorum size.
         */
        ByteBuffer bb = ByteBuffer.wrap(zk.getData(BKDefs.prefix + getZKStringId(lId) + BKDefs.quorumSize, false, stat));
        int qSize = bb.getInt();
         
        /*
         * Get last entry written from ZK 
         */
        
        long last = 0;
        LOG.debug("Close path: " + BKDefs.prefix + getZKStringId(lId) + BKDefs.close);
        if(zk.exists(BKDefs.prefix + getZKStringId(lId) + BKDefs.close, false) == null){
            recoverLedger(lId, passwd);
        }
            
        stat = null;
        byte[] data = zk.getData(BKDefs.prefix + getZKStringId(lId) + BKDefs.close, false, stat);
        ByteBuffer buf = ByteBuffer.wrap(data);
        last = buf.getLong();
        //zk.delete(BKDefs.prefix + getZKStringId(lId) + BKDefs.close, -1);
        
        /*
         * Quorum mode 
         */
        data = zk.getData(BKDefs.prefix + getZKStringId(lId) + BKDefs.quorumMode, false, stat);
        buf = ByteBuffer.wrap(data);
        
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
        
        /*
         * Get children of "/ledgers/id/ensemble" 
         */
        
        List<String> list = 
            zk.getChildren(BKDefs.prefix + getZKStringId(lId) + BKDefs.ensemble, false);
        
        LOG.debug("Length of list of bookies: " + list.size());
        for(int i = 0 ; i < list.size() ; i++){
            for(String s : list){
                LOG.debug("Extracting bookie: " + s);
                byte[] bindex = zk.getData(BKDefs.prefix + getZKStringId(lId) + BKDefs.ensemble + "/" + s, false, stat);
                ByteBuffer bindexBuf = ByteBuffer.wrap(bindex);
                if(bindexBuf.getInt() == i){                      
                    try{
                        lh.addBookieForReading(parseAddr(s));
                    } catch (IOException e){
                        LOG.error(e);
                    }
                }
            }
        }
        
        /*
         * Read changes to quorum over time. To determine if there has been changes during
         * writes to the ledger, check if there is a znode called quorumEvolution.
         */
        if(zk.exists(BKDefs.prefix + 
                getZKStringId(lh.getId()) +  
                BKDefs.quorumEvolution, false) != null){
                    String path = BKDefs.prefix + 
                    getZKStringId(lh.getId()) +  
                    BKDefs.quorumEvolution;
                    
                    List<String> faultList = zk.getChildren(path, false);
                    try{
                        for(String s : faultList){
                            LOG.debug("Faulty list child: " + s);
                            long entry = Long.parseLong(s);
                            String addresses = new String(zk.getData(path + "/" + s, false, stat));
                            String parts[] = addresses.split(" ");

                            ArrayList<BookieHandle> newBookieSet = new ArrayList<BookieHandle>();
                            for(int i = 0 ; i < parts.length ; i++){
                                LOG.debug("Address: " + parts[i]);
                                InetSocketAddress faultyBookie =  
                                    parseAddr(parts[i].substring(1));                           
                        
                                newBookieSet.add(lh.getBookieHandleDup(faultyBookie));
                            }
                            lh.setNewBookieConfig(entry, newBookieSet);
                            LOG.debug("NewBookieSet size: " + newBookieSet.size());
                        }

                        lh.prepareEntryChange();
                    } catch (NumberFormatException e) {
                        LOG.error("Error when parsing the ledger identifier", e);
                    }
                }
      
        /*
         *  Return ledger handler
         */
        return lh;
    }    
    
    public void asyncOpenLedger(long lId, byte passwd[], OpenCallback cb, Object ctx)
    throws InterruptedException{
        OpenLedgerOp op = new OpenLedgerOp(lId, 
                passwd,  
                cb, 
                ctx);
        LedgerManagementProcessor lmp = getMngProcessor();
        lmp.addOp(op);
    }
    
    /**
     * Parses address into IP and port.
     * 
     *  @param addr	String
     */
    
    InetSocketAddress parseAddr(String s){
        String parts[] = s.split(":");
        if (parts.length != 2) {
            System.out.println(s
                    + " does not have the form host:port");
        }
        InetSocketAddress addr = new InetSocketAddress(parts[0],
                Integer.parseInt(parts[1]));
        return addr;
    }
    
 
    /**
     * Check if close node exists. 
     * 
     * @param ledgerId	id of the ledger to check
     */
    public boolean hasClosed(long ledgerId)
    throws KeeperException, InterruptedException{
        String closePath = BKDefs.prefix + getZKStringId(ledgerId) + BKDefs.close;
        if(zk.exists(closePath, false) == null) return false;
        else return true;
    }
    
    /**
     * Recover a ledger that was not closed properly.
     * 
     * @param lId	ledger identifier
     * @param passwd	password
     */
    
    boolean recoverLedger(long lId, byte passwd[])
    throws KeeperException, InterruptedException, IOException, BKException {
        
        Stat stat = null;
       
        LOG.info("Recovering ledger");
        
        /*
         * Get quorum size.
         */
        ByteBuffer bb = ByteBuffer.wrap(zk.getData(BKDefs.prefix + getZKStringId(lId) + BKDefs.quorumSize, false, stat));
        int qSize = bb.getInt();
                
        
        /*
         * Get children of "/ledgers/id/ensemble" 
         */
        
        List<String> list = 
            zk.getChildren(BKDefs.prefix + getZKStringId(lId) + BKDefs.ensemble, false);
        
        ArrayList<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>();
        for(String s : list){
            addresses.add(parseAddr(s));
        }
        
        /*
         * Quorum mode 
         */
        byte[] data = zk.getData(BKDefs.prefix + getZKStringId(lId) + BKDefs.quorumMode, false, stat);
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
    InetSocketAddress getNewBookie(ArrayList<InetSocketAddress> addrList)
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
    
    HashMap<InetSocketAddress, BookieHandle> bhMap = 
    	new HashMap<InetSocketAddress, BookieHandle>();
    
    /**
     *  Keeps a list of available BookieHandle objects and returns
     *  the corresponding object given an address.
     *  
     *  @param	a	InetSocketAddress
     */
    
    synchronized BookieHandle getBookieHandle(LedgerHandle lh, InetSocketAddress a)
    throws ConnectException, IOException {
    	if(!bhMap.containsKey(a)){
    	    BookieHandle bh = new BookieHandle(a, true); 
    		bhMap.put(a, bh);
    		bh.start();
    	}
    	bhMap.get(a).incRefCount(lh);
    	
    	return bhMap.get(a);
    }
    
    /**
     * When there are no more references to a BookieHandle,
     * remove it from the list. 
     */
    
    synchronized void haltBookieHandles(LedgerHandle lh, ArrayList<BookieHandle> bookies){
        while(bookies.size() > 0){
            BookieHandle bh = bookies.remove(0);
            if(bh.halt(lh) <= 0)
                bhMap.remove(bh.addr);
        }
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
     * Halts all bookie handles
     * 
     */
    public void halt() throws InterruptedException{
        
        for(BookieHandle bh: bhMap.values()){
            bh.shutdown();
        }
        zk.close();
    }
}
