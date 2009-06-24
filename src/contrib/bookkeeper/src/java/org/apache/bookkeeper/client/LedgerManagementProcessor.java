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

package org.apache.bookkeeper.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.bookkeeper.client.AsyncCallback.CloseCallback;
import org.apache.bookkeeper.client.AsyncCallback.CreateCallback;
import org.apache.bookkeeper.client.AsyncCallback.OpenCallback;
import org.apache.bookkeeper.client.BKException.Code;
import org.apache.bookkeeper.client.LedgerHandle.QMode;
import org.apache.bookkeeper.client.QuorumEngine.Operation.StopOp;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;

import org.apache.log4j.Logger;

public class LedgerManagementProcessor 
extends Thread 
implements StatCallback, StringCallback, ChildrenCallback, DataCallback {
    
   Logger LOG = Logger.getLogger(LedgerManagementProcessor.class);
    
    static final int MAXATTEMPTS = 3;
    
    /**
     *  Types of ledger operations: CREATE, OPEN, CLOSE
     */
    static enum OpType {CREATE, OPEN, CLOSE};
    
    /**
     * Operation descriptor for asynchronous execution. 
     *
     */
    static class LedgerOp {
        private OpType op;
        private int action;
        private int rc = 0;
        private Object ctx;
        private LedgerHandle lh;

        /**
         * Constructor sets the operation type.
         * 
         * @param op    operation type
         */
        LedgerOp(OpType op, Object ctx){
            this.op = op;
            this.ctx = ctx;
            this.action = 0;
        }        
        
        /**
         * Returns the operation type.
         * 
         * @return OpType
         */
        OpType getType(){
            return op;
        }

        /**
         * Set value of action
         * 
         * @return int  return action identifier
         */
        int setAction(int action){
            return this.action = action;
        }
        
        /**
         * Return value of action
         * 
         * @return  int  return action identifier
         */
        int getAction(){
            return action;
        }
        
        /**
         * Set the return code
         * 
         * @param rc return code
         */
        void setRC(int rc){
            this.rc = rc;
        }
        
        /**
         * Return return code
         * 
         * @return int return code
         */
        int getRC(){
            return rc;
        }
        
        /**
         * Return control object
         * 
         * @return Object   control object
         */
        Object getCtx(){
            return ctx;
        }
        
        /**
         * Set ledger handle
         * 
         * @param lh ledger handle
         */
        
        void setLh(LedgerHandle lh){
            this.lh = lh;
        }
        
        /**
         * Return ledger handle
         * 
         * @return LedgerHandle ledger handle
         */
        LedgerHandle getLh(){
            return this.lh;
        }
    }

    /**
     * Create ledger descriptor for asynchronous execution.
     */
    static class CreateLedgerOp extends LedgerOp {
        private long lId;
        private int ensSize;
        private int qSize; 
        private QMode mode;  
        private byte passwd[];
        
        private CreateCallback cb;
        
        private List<String> available;
        private String path;
        
        AtomicInteger zkOpCounter;
        
        /**
         * Constructor of request to create a new ledger.
         * 
         * @param ensSize   ensemble size
         * @param qSize     quorum size
         * @param mode      quorum mode (VERIFIABLE or GENERIC)
         * @param passwd    password
         * @param cb        create callback implementation
         * @param ctx       control object
         */
        CreateLedgerOp(int ensSize,
                int qSize, 
                QMode mode,  
                byte passwd[],
                CreateCallback cb,
                Object ctx)
                throws BKException{
            super(OpType.CREATE, ctx);
            this.ensSize = ensSize;
            this.qSize = qSize;
            this.mode = mode;
            this.passwd = passwd;
            this.cb = cb;
            
            /*
             * There are 5 fixed ZK operations, and a variable
             * number to set the bookies of the new ledger. We
             * initialize it with 5 and increment as we add bookies
             * in action 2. 
             */
            this.zkOpCounter = new AtomicInteger(5);
            
            // Check that quorum size follows the minimum
            long t;
            switch(mode){
            case VERIFIABLE:
                t = java.lang.Math.round(java.lang.Math.floor((ensSize - 1)/2));
                if(t == 0){
                    throw BKException.create(Code.QuorumException);
                }
                break;
            case GENERIC:
                t = java.lang.Math.round(java.lang.Math.floor((ensSize - 1)/3));
                if(t == 0){
                    throw BKException.create(Code.QuorumException);
                }
                break;
            case FREEFORM:
                break;
            }
        }
        
        /**
         * Constructor for cloning. This is necessary because there
         * are create actions that issue multiple ZK operations, and 
         * when we queue back the result of the operation we need the
         * operation object to reflect the result of the operation.
         * 
         * @param op
         */
        CreateLedgerOp(CreateLedgerOp op){
            super(OpType.CREATE, op.getCtx());
            setRC(op.getRC());
            setAction(op.getAction());
            
            this.setLh(op.getLh());
            this.lId = op.getLid();
            this.ensSize = op.getEnsembleSize();
            this.qSize = op.getQuorumSize();
            this.mode = op.getMode();
            this.passwd = op.getPasswd();
            this.cb = op.getCb();
            this.available = op.getAvailable();
            this.path = op.getPath(); 
            this.zkOpCounter = op.zkOpCounter;
        }
        
        /**
         * Set ledger identifier (sequence number
         * of ZooKeeper)
         * 
         * @param lId
         */
        void setLid(long lId){
            this.lId = lId;
        }
        
        /**
         * Return ledger identifier
         * 
         * @return long ledger identifier
         */
        long getLid(){
            return lId;
        }
        
        /**
         * Return ensemble size
         * 
         * @return int ensemble size
         */
        int getEnsembleSize(){
            return ensSize;
        }
        
        /**
         * Return quorum size
         * 
         * @return int quorum size
         */
        int getQuorumSize(){
           return qSize; 
        }
        
        /**
         * Return quorum mode
         * 
         * @return  QMode   quorum mode
         */
        QMode getMode(){
            return mode;   
        }
        
        /**
         * Return password
         * 
         * @return byte[] passwd
         */
        byte[] getPasswd(){
            return passwd;
        }
        
        /**
         * Return callback implementation
         * 
         * @return CreateCallback   callback implementation
         */
        CreateCallback getCb(){
            return cb;
        }
        
        
        
        /**
         * Set the list of available bookies for processing
         * 
         * @param available lost of available bookies
         */
        void addAvailable(List<String> available){
            this.available = available;
        }
        
        /**
         * Return list of bookies available
         * 
         * @return List<String> list of bookies available
         */
        List<String> getAvailable(){
            return available;
        }
        
        /**
         * Set path as returned in the callback
         * 
         * @param path  created path
         */
        void setPath(String path){
            this.path = path;
        }

        /**
         * Return path
         * 
         * @return String   path
         */
        String getPath(){
            return path;
        }
    }
    
    /**
     * Open ledger descriptor for asynchronous execution.
     */
    static class OpenLedgerOp extends LedgerOp {
        private long lId; 
        private byte passwd[];
        private OpenCallback cb;
        
        private int qSize;
        private long last;
        private QMode qMode;
        private List<String> children;
        
        private String dataString;
        private String item;
        private AtomicInteger counter;

        /**
         * Constructor of request to open a ledger.
         * 
         * @param lId   ledger identifier
         * @param passwd    password to access ledger
         */
        OpenLedgerOp(long lId, 
                byte passwd[],
                OpenCallback cb,
                Object ctx){
            super(OpType.OPEN, ctx);
            this.lId = lId;
            this.passwd = passwd;
        }
        
        /**
         * Return ledger identifier
         * 
         * @return long
         */
        long getLid(){
            return lId;
        }
        
        /**
         * Return password
         * @return byte[]
         */
        byte[] getPasswd(){
            return passwd;
        }

        /**
         * Return callback object
         * 
         * @return OpenCallback 
         */
        OpenCallback getCb(){
            return this.cb;
        }
             
        /**
         * Set quorum size as extracted from ZK
         * 
         * @param data  znode data
         */
        void setQSize(byte[] data){
            ByteBuffer bb = ByteBuffer.wrap(data);
            this.qSize = bb.getInt();
        }
        
        /**
         * Return quorum size
         * 
         * @return  int quorum size
         */
        int getQSize(){
            return qSize;
        }
        
        /**
         * Set last value as read from close znode
         * 
         * @param last
         */
        void setLast(long last){
            this.last = last;
        }
        
        /**
         * Return last value
         * 
         * @return long last value
         */
        long getLast(){
            return last;
        }
        
        /**
         * Set ledger mode 
         *    
         * @param mode  GENERIC or VERIFIABLE
         */
        void setQMode(QMode mode){
            this.qMode = mode;
        }
        
        /**
         * Return ledger mode
         * 
         * @return QMode   ledger mode
         */
        QMode getQMode(){
            return qMode;
        }
        
        /**
         * Set list of bookie identifiers
         * 
         * @param list  list of bbokie identifiers
         */
        void addChildren(List<String> list){
            this.children = list;
        }
        
        /**
         * Return list of bookie identifiers
         * 
         * @return List<String> list of bookie identifiers
         */
        List<String> getChildren(){
            return children;
        }
        
        /**
         * Returns the size of the children list. Used in processOpen.
         * 
         * @return int
         */
        int getListSize(){
            return children.size();
        }
        
        /**
         * Sets the value of item. This is used in processOpen to
         * keep the item value of the list of ensemble changes.
         * 
         * @param item
         */
        void setItem(String item){
            this.item = item;
        }
        
        /**
         * Returns the value of item
         * 
         * @return String
         */
        
        String getItem(){
            return item;
        }
        
        /**
         * Sets the value of dataString
         * 
         * @param data  value to set
         */
        void setStringData(String data){
            this.dataString = data;
        }
        
        /**
         * Returns the value of dataString
         * 
         * @return String
         */
        String getStringData(){
            return dataString;
        }
    }
    
    /**
     * Close ledger descriptor for asynchronous execution.
     */
    static class CloseLedgerOp extends LedgerOp {
        private long lid;
        private ByteBuffer last;
        private String closePath;
        private CloseCallback cb;
        private Stat stat;
        
        /**
         * Constructor of request to close a ledger
         * 
         * @param lh    ledger handle
         */
        CloseLedgerOp(LedgerHandle lh, 
                CloseCallback cb,
                Object ctx){
            super(OpType.CLOSE, ctx);
       
            this.setLh(lh);
            this.lid = lh.getId();
            this.last = ByteBuffer.allocate(8);
            this.last.putLong(lh.getAddConfirmed());
            this.cb = cb;
        }
        
        /**
         * Return a ByteBuffer containing the last entry written
         * 
         * @return ByteBuffer identifier of last entry
         */
        ByteBuffer getLast(){
            return last;
        }
        
        /**
         * Return ledger identifier
         * 
         * @return long
         */
        long getLid(){
            return this.lid;
        }
        
        /**
         * Set close path
         * 
         * @param path  close path
         */
        void setClosePath(String path){
            this.closePath = path;
        }
        
        /**
         * Return close path string.
         * 
         * @return String   close path
         */
        String getClosePath(){
            return this.closePath;
        }
        
        
        /**
         * Return callback object.
         * 
         * @return CloseCallback 
         */
        CloseCallback getCb(){
            return this.cb;
        }
     
    
        /**
         * Set value of stat
         * 
         * @param stat stat object returned by ZK callback
         */
        void setStat (Stat stat){
            this.stat = stat;
        }
        
        /**
         * Return value of stat
         * 
         * @return Stat
         */
        
        Stat getStat (){
            return stat;
        }
    }
    
    /*
     * BookKeeper parent.
     */
    BookKeeper bk;
    /*
     * Queue of outstanding operations
     */
    ArrayBlockingQueue<LedgerOp> outstandingRequests = 
        new ArrayBlockingQueue<LedgerOp>(200);
    
    
    /**
     * Add ledger operation to queue of pending
     * 
     * @param op    ledger operation
     */
    void addOp(LedgerOp op)
    throws InterruptedException{
        LOG.info("Queuing new op");
        outstandingRequests.put(op);
    }
    
    /**
     * Constructor takes BookKeeper object 
     * 
     * @param bk BookKeeper object
     */
    
    LedgerManagementProcessor(BookKeeper bk){
        this.bk = bk;
    }
    
    /**
     * Run method
     */
    public void run(){
        while(true){
            try{
                LedgerOp op = outstandingRequests.take();
            
                switch(op.getType()){
                case CREATE:
                    processCreate((CreateLedgerOp) op);
                    break;            
                case OPEN:
                    processOpen((OpenLedgerOp) op);
                    break;
                case CLOSE:
                    processClose((CloseLedgerOp) op);
                    break;
                }
            } catch(InterruptedException e){
                LOG.warn("Interrupted while waiting in the queue of incoming requests");   
            }
        }
    }
    
    /**
     * Processes a create ledger operation.
     * 
     * @param cop   create ledger operation to process
     * @throws InterruptedException
     */
    
    private void processCreate(CreateLedgerOp cop)
    throws InterruptedException {
        if(cop.getRC() != BKDefs.EOK)
            cop.getCb().createComplete(cop.getRC(), null, cop.getCtx());

        switch(cop.getAction()){
        case 0:
            LOG.info("Action 0 of create");
            /*
             * Create ledger node on ZK.
             * We get the id from the sequence number on the node.
             */
            bk.getZooKeeper().create(BKDefs.prefix, 
                new byte[0], 
                Ids.OPEN_ACL_UNSAFE, 
                CreateMode.PERSISTENT_SEQUENTIAL,
                this,
                cop);
        break;
        case 1:
            LOG.info("Action 1 of create");
            /* 
             * Extract ledger id.
             */
            String parts[] = cop.getPath().split("/");
            String subparts[] = parts[2].split("L");
            long lId = Long.parseLong(subparts[1]);
            cop.setLid(lId);
        
            LedgerHandle lh = new LedgerHandle(bk, lId, 0, cop.getQuorumSize(), cop.getMode(), cop.getPasswd());
            cop.setLh(lh);
            /* 
             * Get children from "/ledgers/available" on zk
             */

            bk.getZooKeeper().getChildren("/ledgers/available", false, this, cop);
            /* 
             * Select ensSize servers to form the ensemble
             */
            bk.getZooKeeper().create(BKDefs.prefix + bk.getZKStringId(lId) + BKDefs.ensemble, new byte[0], 
                    Ids.OPEN_ACL_UNSAFE, 
                    CreateMode.PERSISTENT,
                    this,
                    cop);
            /* 
             * Add quorum size to ZK metadata
             */
            ByteBuffer bb = ByteBuffer.allocate(4);
            bb.putInt(cop.getQuorumSize());
            
            bk.getZooKeeper().create(BKDefs.prefix + bk.getZKStringId(lId) + cop.getQuorumSize(), 
                    bb.array(), 
                    Ids.OPEN_ACL_UNSAFE, 
                    CreateMode.PERSISTENT,
                    this,
                    cop);
            /* 
             * Quorum mode
             */
            bb = ByteBuffer.allocate(4);
            bb.putInt(cop.getMode().ordinal());
            
            bk.getZooKeeper().create(BKDefs.prefix + bk.getZKStringId(lId) + cop.getMode(), 
                    bb.array(), 
                    Ids.OPEN_ACL_UNSAFE, 
                    CreateMode.PERSISTENT,
                    this,
                    cop);
            break;
        case 2:
            LOG.info("Action 2 of create");
            /*
             * Adding bookies to ledger handle
             */
            Random r = new Random();
            List<String> children = cop.getAvailable();
            for(int i = 0; i < cop.getEnsembleSize(); i++){
                int index = 0;
                if(children.size() > 1) 
                    index = r.nextInt(children.size() - 1);
                else if(children.size() == 1)
                    index = 0;
                else {
                    LOG.error("Not enough bookies available");    
                    cop.setRC(BKDefs.EIB);
                }
            
                try{
                    String bookie = children.remove(index);
                    LOG.info("Bookie: " + bookie);
                    InetSocketAddress tAddr = bk.parseAddr(bookie);
                    int bindex = cop.getLh().addBookieForWriting(tAddr); 
                    ByteBuffer bindexBuf = ByteBuffer.allocate(4);
                    bindexBuf.putInt(bindex);
                
                    String pBookie = "/" + bookie;
                    cop.zkOpCounter.getAndIncrement();
                    bk.getZooKeeper().create(BKDefs.prefix + bk.getZKStringId(cop.getLid()) + BKDefs.ensemble + pBookie, 
                            bindexBuf.array(), 
                            Ids.OPEN_ACL_UNSAFE, 
                            CreateMode.PERSISTENT, 
                            this,
                            cop);
                } catch (IOException e) {
                    LOG.error(e);
                    i--;
                } 
            }
            break;
        case 3:
            LOG.info("Action 3 of create");
            LOG.debug("Created new ledger");
            cop.getCb().createComplete(cop.getRC(), cop.getLh(), cop.getCtx());   
            break;
        case 4:
            break;
        }
    }
        
    /**
     *  Processes open ledger operation.
     * 
     * @param oop   open ledger operation to process.
     * @throws InterruptedException
     */
    private void processOpen(OpenLedgerOp oop) 
    throws InterruptedException {    
        /*
         * Case for open operation
         */
        if(oop.getRC() != BKDefs.EOK)
            oop.getCb().openComplete(oop.getRC(), null, oop.getCtx());
        
        String path;
        LedgerHandle lh;
        
        switch(oop.getAction()){
        case 0:                    
            /*
             * Check if ledger exists
             */
            bk.getZooKeeper().exists(BKDefs.prefix + bk.getZKStringId(oop.getLid()), 
                    false,
                    this,
                    oop);
            break;
        case 1:                    
            /*
             * Get quorum size.
             */
            bk.getZooKeeper().getData(BKDefs.prefix + bk.getZKStringId(oop.getLid()) + BKDefs.quorumSize, 
                    false,
                    this,
                    oop);
            break;    
        case 2:         
            /*
             * Get last entry written from ZK 
             */
                
            long last = 0;
            LOG.debug("Close path: " + BKDefs.prefix + bk.getZKStringId(oop.getLid()) + BKDefs.close);
            bk.getZooKeeper().exists(BKDefs.prefix + bk.getZKStringId(oop.getLid()) + BKDefs.close, 
                    false,
                    this,
                    oop);
            break;
        case 3:
            try{
                bk.recoverLedger(oop.getLid(), oop.getPasswd());
            } catch(Exception e){
                LOG.error("Cannot recover ledger", e);
                oop.getCb().openComplete(BKDefs.ERL, null, oop.getCtx());
            }
            /*
             * In the case of recovery, it falls through to the
             * next case intentionally.
             */
        case 4:   
            bk.getZooKeeper().getData(BKDefs.prefix + bk.getZKStringId(oop.getLid()) + BKDefs.close, 
                    false, 
                    this,
                    oop);
            break;
        case 5:                
            /*
             * Quorum mode 
             */
            bk.getZooKeeper().getData(BKDefs.prefix + bk.getZKStringId(oop.getLid()) + BKDefs.quorumMode, 
                    false, 
                    this,
                    oop);
        case 6:         
            /*
             *  Create ledger handle
             */
            lh = new LedgerHandle(bk, oop.getLid(), oop.getLast(), oop.getQSize(), oop.getQMode(), oop.getPasswd());
                
            /*
             * Get children of "/ledgers/id/ensemble" 
             */
              
            bk.getZooKeeper().getChildren(BKDefs.prefix + bk.getZKStringId(oop.getLid()) + BKDefs.ensemble, 
                    false,
                    this,
                    oop);
            break;

        case 7:
            List<String> list = oop.getChildren();
            LOG.info("Length of list of bookies: " + list.size());
            try{
                for(int i = 0 ; i < list.size() ; i++){
                    for(String s : list){
                        byte[] bindex = bk.getZooKeeper().getData(BKDefs.prefix + bk.getZKStringId(oop.getLid()) + BKDefs.ensemble + "/" + s, 
                                false, new Stat());
                        ByteBuffer bindexBuf = ByteBuffer.wrap(bindex);
                        if(bindexBuf.getInt() == i){                      
                            oop.getLh().addBookieForReading(bk.parseAddr(s));
                        }
                    }
                }

                /*
                 * Check if there has been any change to the ensemble of bookies
                 * due to failures.
                 */
                bk.getZooKeeper().exists(BKDefs.prefix + 
                        bk.getZKStringId(oop.getLid()) +  
                        BKDefs.quorumEvolution, 
                                false,
                                this,
                                oop);
                        
            } catch(KeeperException e){
                LOG.error("Exception while adding bookies", e);
                oop.setRC(BKDefs.EZK);
                oop.getCb().openComplete(oop.getRC(), oop.getLh(), oop.getCtx());
            } catch(IOException e){
                LOG.error("Exception while trying to connect to bookie");
                oop.setRC(BKDefs.EIO);
                oop.getCb().openComplete(oop.getRC(), oop.getLh(), oop.getCtx());
            } 
            
             break;
        
        case 8:
            path = BKDefs.prefix + 
            bk.getZKStringId(oop.getLid()) +  
            BKDefs.quorumEvolution;
                
            bk.getZooKeeper().getChildren(path, 
                    false,
                    this,
                    oop);
        case 9: 
            oop.getCb().openComplete(oop.getRC(), oop.getLh(), oop.getCtx());
            break;
        case 10:        
            path = BKDefs.prefix + 
            bk.getZKStringId(oop.getLid()) +  
            BKDefs.quorumEvolution;
            
            for(String s : oop.getChildren()){
                oop.setItem(s);
                bk.getZooKeeper().getData(path + "/" + s, 
                        false, 
                        this,
                        oop);
            }
            
            break;
        case 11:
            lh = oop.getLh();
            
            String parts[] = oop.getStringData().split(" ");

            ArrayList<BookieHandle> newBookieSet = new ArrayList<BookieHandle>();
            for(int i = 0 ; i < parts.length ; i++){
                LOG.info("Address: " + parts[i]);
                InetSocketAddress faultyBookie =  
                    bk.parseAddr(parts[i].substring(1));                           
        
                newBookieSet.add(lh.getBookieHandleDup(faultyBookie));
            }
            lh.setNewBookieConfig(Long.parseLong(oop.getItem()), newBookieSet);
        
            if(oop.counter.incrementAndGet() == oop.getListSize()){
                lh.prepareEntryChange();
                oop.getCb().openComplete(oop.getRC(), oop.getLh(), oop.getCtx());
            }
            
            break;
        }
    }    
    
    
   /**
    * Processes close ledger operation.
    * 
    * @param clop   close ledger operation to process.
    * @throws InterruptedException
    */
    
    private void processClose(CloseLedgerOp clop)
    throws InterruptedException {
        if(clop.getRC() != BKDefs.EOK)
            clop.getCb().closeComplete(clop.getRC(), clop.getLh(), clop.getCtx());
        
        switch(clop.getAction()){
        case 0:
            LOG.info("Last saved on ZK is: " + clop.getLh().getLast()); 
            clop.setClosePath(BKDefs.prefix + bk.getZKStringId(getId()) + BKDefs.close);
            bk.getZooKeeper().exists(clop.getClosePath(), null, this, clop);
            break;             
        case 1:
            if(clop.getStat() == null){
                bk.getZooKeeper().create(clop.getClosePath(), 
                        clop.getLast().array(), 
                        Ids.OPEN_ACL_UNSAFE, 
                        CreateMode.PERSISTENT, 
                        this,
                        clop);
            } else {
                bk.getZooKeeper().setData(clop.getClosePath(), 
                        clop.getLast().array(), -1, this, clop);
            }
            break;
        case 2:   
            LedgerHandle lh = clop.getLh(); 
            try{
                lh.closeUp();
                StopOp sOp = new StopOp();
                lh.getQuorumEngine().sendOp(sOp);

            } catch(Exception e) {
                LOG.warn("Exception while stopping quorum engine: " + lh.getId());
            }
            clop.getCb().closeComplete(BKDefs.EOK, clop.getLh(), clop.getCtx());
        
            break;
        }    
    }
    
    /**
     * Implements org.apache.zookeeper.AsyncCallback.StatCallback 
     */
    public void processResult(int rc, String path, Object ctx, Stat stat){
        LedgerOp op = (LedgerOp) ctx;
       
        if(rc != BKDefs.EOK){
            op.setRC(rc);
            while(true){
                try{
                    this.addOp(op);
                    return;
                } catch(InterruptedException e) {
                    LOG.warn("Interrupted while trying to add operation to queue", e);
                }
            }
        }
        
        switch(op.getType()){
        case CREATE:
            break;
        case OPEN:
            switch(op.getAction()){
            case 0:
                if(stat == null)
                    op.setRC(BKDefs.ENL);
                break;
            case 2:
                /*
                 * If there is no "close" znode, then we have
                 * to recover this ledger
                 */
                if(stat == null)
                    op.setAction(3);
                else
                    op.setAction(4);
                break;
            case 8:
                if(stat == null)
                    op.setAction(9);
                else
                    op.setAction(10);
                break;
            }
        case CLOSE:
            CloseLedgerOp clop = (CloseLedgerOp) op;
            clop.setStat(stat);
            clop.setAction(1);
            break;
        }
    
        /*
         * Queues operation for processing
         */
        int counter = 0;
        boolean leave = false;
        while(!leave){
            try{
                this.addOp(op);
                leave = true;
            } catch(InterruptedException e) {
                if(counter++ > MAXATTEMPTS){
                    LOG.error("Exceed maximum number of attempts");
                    leave = true;
                } else
                    LOG.warn("Interrupted while trying to add operation to queue", e);
            }
        }
    
    }   
    
    /**
     * Implements org.apache.zookeeper.AsyncCallback.StringCallback 
     */
    public void processResult(int rc, String path, Object ctx, String name){
        LedgerOp op = (LedgerOp) ctx;
        
        if(rc != BKDefs.EOK){
            op.setRC(rc);
        } else switch(op.getType()){
               case CREATE:
                   CreateLedgerOp cop = (CreateLedgerOp) op;

                   int counter = cop.zkOpCounter.decrementAndGet(); 
                   if(op.getAction() == 0){
                       cop.setAction(1);
                       cop.setPath(name);
                       op.setRC(rc);               
                   } else {
                       if(counter == 0){
                           cop.setAction(3);
                       } else {
                           /*
                            * Could queue a no-op, but for optimization 
                            * purposes, let's return here
                            */
                           return;
                       }

                   }
                   op = cop;
                   break;
               case OPEN:
                   break;
               case CLOSE:
                   CloseLedgerOp clop = (CloseLedgerOp) op;
                   clop.setAction(1);
                   break;
        }
        
        /*
         * Queues operation for processing 
         */
        int counter = 0;
        boolean leave = false;
        while(!leave){
            try{
                this.addOp(op);
                leave = true;
            } catch(InterruptedException e) {
                if(counter++ > MAXATTEMPTS){
                    LOG.error("Exceed maximum number of attempts");
                    leave = true;
                } else
                    LOG.warn("Interrupted while trying to add operation to queue", e);
            }
        }
        LOG.info("Leaving loop");
    }
    
    /**
     * Implement org.apache.zookeeper.AsyncCallback.ChildrenCallback
     */
    public void processResult(int rc, String path, Object ctx, List<String> children){
       LedgerOp op = (LedgerOp) ctx;
       
       LOG.info("Processing children callback");
       if(rc != BKDefs.EOK){
           op.setRC(rc);
       } else switch(op.getType()){
              case CREATE:
                  CreateLedgerOp cop = (CreateLedgerOp) op;
                  cop.addAvailable(children);
                  int counter = cop.zkOpCounter.decrementAndGet();
                  LOG.info("ZK Op counter value: " + counter);
                  cop.setAction(2);
                  
                  op = cop;
                  break;
              case OPEN:
                  OpenLedgerOp oop = (OpenLedgerOp) op;
                  oop.addChildren(children);
                  break;
       }
       
       int counter = 0;
       boolean leave = false;
       while(!leave){
           try{
               this.addOp(op);
               leave = true;
           } catch(InterruptedException e) {
               if(counter++ > MAXATTEMPTS){
                   LOG.error("Exceed maximum number of attempts");
                   leave = true;
               } else
                   LOG.warn("Interrupted while trying to add operation to queue", e);
           }
       }
    }
    
    /**
     * Implement org.apache.zookeeper.AsyncCallback.DataCallback
     */
    public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat){
        LedgerOp op = (LedgerOp) ctx;
        ByteBuffer bb;
        
        if(rc != BKDefs.EOK){
            op.setRC(rc);
        } else switch(op.getType()){
               case OPEN:
                   OpenLedgerOp oop = (OpenLedgerOp) op;
                   switch(oop.getAction()){
                   case 1: 
                       oop.setQSize(data);
                       break;
                   case 4:
                       bb = ByteBuffer.wrap(data);
                       oop.setLast(bb.getLong());
                       break;
                   case 5:
                       bb = ByteBuffer.wrap(data);
                       
                       switch(bb.getInt()){
                       case 1:
                           oop.setQMode(QMode.GENERIC);
                           LOG.info("Generic ledger");
                           break;
                       case 2:
                           oop.setQMode(QMode.FREEFORM);
                           break;
                       default:
                           oop.setQMode(QMode.VERIFIABLE);
                       LOG.info("Verifiable ledger");
                       }
                       break;
                   case 10:
                       String addr = new String(data);
                       oop.setStringData(addr);
                       oop.setAction(11);
                       break;
                   }
                   break;
               default:
                   LOG.warn("Wrong type");
                   break;  
        }
        
        int counter = 0;
        boolean leave = false;
        while(!leave){
            try{
                this.addOp(op);
                leave = true;
            } catch(InterruptedException e) {
                if(counter++ > MAXATTEMPTS){
                    LOG.error("Exceed maximum number of attempts");
                    leave = true;
                } else
                    LOG.warn("Interrupted while trying to add operation to queue", e);
            }
        }
    }
}
