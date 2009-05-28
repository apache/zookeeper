package org.apache.bookkeeper.test;
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


import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;

import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.AsyncCallback.CloseCallback;
import org.apache.bookkeeper.client.AsyncCallback.CreateCallback;
import org.apache.bookkeeper.client.AsyncCallback.OpenCallback;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.LedgerSequence;
import org.apache.bookkeeper.client.AsyncCallback.ReadCallback;
import org.apache.bookkeeper.client.LedgerHandle.QMode;
import org.apache.bookkeeper.proto.BookieServer;
import org.apache.bookkeeper.streaming.LedgerInputStream;
import org.apache.bookkeeper.streaming.LedgerOutputStream;
import org.apache.bookkeeper.util.ClientBase;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ZooKeeperServer;

import org.apache.zookeeper.ZooDefs.Ids;
import org.junit.Test;

//import BookieReadWriteTest.SyncObj;
//import BookieReadWriteTest.emptyWatcher;

/**
 * This test tests read and write, synchronous and 
 * asynchronous, strings and integers for a BookKeeper client. 
 * The test deployment uses a ZooKeeper server 
 * and three BookKeepers. 
 * 
 */

public class AsyncLedgerOpsTest 
    extends junit.framework.TestCase 
    implements AddCallback, 
    ReadCallback, 
    CreateCallback,
    CloseCallback,
    OpenCallback{
    static Logger LOG = Logger.getLogger(BookieClientTest.class);

    static ConsoleAppender ca = new ConsoleAppender(new PatternLayout());

    // ZooKeeper related variables
    private static final String HOSTPORT = "127.0.0.1:2181";
    static Integer ZooKeeperDefaultPort = 2181;
    ZooKeeperServer zks;
    ZooKeeper zkc; //zookeeper client
    NIOServerCnxn.Factory serverFactory;
    File ZkTmpDir;
    
    //BookKeeper 
    File tmpDirB1, tmpDirB2, tmpDirB3;
    BookieServer bs1, bs2, bs3;
    Integer initialPort = 5000;
    BookKeeper bkc; // bookkeeper client
    byte[] ledgerPassword = "aaa".getBytes();
    LedgerHandle lh, lh2;
    long ledgerId;
    LedgerSequence ls;
    
    //test related variables 
    int numEntriesToWrite = 20;
    int maxInt = 2147483647;
    Random rng; // Random Number Generator 
    ArrayList<byte[]> entries; // generated entries
    ArrayList<Integer> entriesSize;
    
    // Synchronization
    SyncObj sync;
    Set<Object> syncObjs;
    
    class SyncObj {
        int counter;
        boolean value;      
        public SyncObj() {
            counter = 0;
            value = false;
        }       
    }
    
    class ControlObj{
        LedgerHandle lh;
        
        void setLh(LedgerHandle lh){
            this.lh = lh;
        }
        
        LedgerHandle getLh(){
            return lh;
        }
    }
    
    @Test
    public void testAsyncCreateClose() throws IOException{
        try {
            // Create a BookKeeper client and a ledger
            bkc = new BookKeeper("127.0.0.1");
           
            ControlObj ctx = new ControlObj();
            
            synchronized(ctx){
                bkc.asyncCreateLedger(3, 2, 
                    QMode.VERIFIABLE, 
                    ledgerPassword,
                    this,
                    ctx);
                ctx.wait();
            }
            
            
            //bkc.initMessageDigest("SHA1");
            LedgerHandle lh = ctx.getLh();
            ledgerId = lh.getId();
            LOG.info("Ledger ID: " + lh.getId());
            for(int i = 0; i < numEntriesToWrite; i++){
                ByteBuffer entry = ByteBuffer.allocate(4);
                entry.putInt(rng.nextInt(maxInt));
                entry.position(0);
                
                entries.add(entry.array());
                entriesSize.add(entry.array().length);
                lh.asyncAddEntry(entry.array(), this, sync);
            }
            
            // wait for all entries to be acknowledged
            synchronized (sync) {
                if (sync.counter < numEntriesToWrite){
                    LOG.debug("Entries counter = " + sync.counter);
                    sync.wait();
                }
            }
            
            LOG.debug("*** WRITE COMPLETE ***");
            // close ledger 
            synchronized(ctx){
                lh.asyncClose(this, ctx);
                ctx.wait();
            }
            
            //*** WRITING PART COMPLETE // READ PART BEGINS ***
            
            // open ledger
            synchronized(ctx){
                bkc.asyncOpenLedger(ledgerId, ledgerPassword, this, ctx);
                ctx.wait();
            }
            lh = ctx.getLh();
            
            LOG.debug("Number of entries written: " + lh.getLast());
            assertTrue("Verifying number of entries written", lh.getLast() == numEntriesToWrite);       
            
            //read entries
            lh.asyncReadEntries(0, numEntriesToWrite - 1, this, (Object) sync);
            
            synchronized (sync) {
                while(sync.value == false){
                    sync.wait();
                }               
            }
            
            assertTrue("Checking number of read entries", ls.size() == numEntriesToWrite);
            
            LOG.debug("*** READ COMPLETE ***");
            
            // at this point, LedgerSequence ls is filled with the returned values
            int i = 0;
            while(ls.hasMoreElements()){
                ByteBuffer origbb = ByteBuffer.wrap(entries.get(i));
                Integer origEntry = origbb.getInt();
                byte[] entry = ls.nextElement().getEntry();
                ByteBuffer result = ByteBuffer.wrap(entry);
                LOG.debug("Length of result: " + result.capacity());
                LOG.debug("Original entry: " + origEntry);

                Integer retrEntry = result.getInt();
                LOG.debug("Retrieved entry: " + retrEntry);
                assertTrue("Checking entry " + i + " for equality", origEntry.equals(retrEntry));
                assertTrue("Checking entry " + i + " for size", entry.length == entriesSize.get(i).intValue());
                i++;
            }
            lh.close();
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (BKException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } //catch (NoSuchAlgorithmException e) {
        //  e.printStackTrace();
        //}
        
    }
    
    
    public void addComplete(int rc, LedgerHandle lh, long entryId, Object ctx) {
        SyncObj x = (SyncObj) ctx;
        synchronized (x) {
            x.counter++;
            x.notify();
        }
    }

    public void readComplete(int rc, LedgerHandle lh, LedgerSequence seq,
            Object ctx) {
        ls = seq;               
        synchronized (sync) {
            sync.value = true;
            sync.notify();
        }
        
    }
    
    public void createComplete(int rc, LedgerHandle lh, Object ctx){
        synchronized(ctx){
            ControlObj cobj = (ControlObj) ctx;
            cobj.setLh(lh);
            cobj.notify();
        }   
    }
    
    public void openComplete(int rc, LedgerHandle lh, Object ctx){
        synchronized(ctx){
            ControlObj cobj = (ControlObj) ctx;
            cobj.setLh(lh);
            cobj.notify();
        }   
    }
    
    public void closeComplete(int rc, LedgerHandle lh, Object ctx){
        synchronized(ctx){
            ControlObj cobj = (ControlObj) ctx;
            cobj.notify();
        }
    }
     
    protected void setUp() throws IOException {
        LOG.addAppender(ca);
        LOG.setLevel((Level) Level.DEBUG);
        
        // create a ZooKeeper server(dataDir, dataLogDir, port)
        LOG.debug("Running ZK server");
        //ServerStats.registerAsConcrete();
        ClientBase.setupTestEnv();
        ZkTmpDir = File.createTempFile("zookeeper", "test");
        ZkTmpDir.delete();
        ZkTmpDir.mkdir();
            
        try {
            zks = new ZooKeeperServer(ZkTmpDir, ZkTmpDir, ZooKeeperDefaultPort);
            serverFactory =  new NIOServerCnxn.Factory(ZooKeeperDefaultPort);
            serverFactory.startup(zks);
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        boolean b = ClientBase.waitForServerUp(HOSTPORT, ClientBase.CONNECTION_TIMEOUT);
        
        LOG.debug("Server up: " + b);
        
        // create a zookeeper client
        LOG.debug("Instantiate ZK Client");
        zkc = new ZooKeeper("127.0.0.1", ZooKeeperDefaultPort, new emptyWatcher());
        
        //initialize the zk client with values
        try {
            zkc.create("/ledgers", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zkc.create("/ledgers/available", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zkc.create("/ledgers/available/127.0.0.1:" + Integer.toString(initialPort), new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zkc.create("/ledgers/available/127.0.0.1:" + Integer.toString(initialPort + 1), new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zkc.create("/ledgers/available/127.0.0.1:" + Integer.toString(initialPort + 2), new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        
        // Create Bookie Servers (B1, B2, B3)
        tmpDirB1 = File.createTempFile("bookie1", "test");
        tmpDirB1.delete();
        tmpDirB1.mkdir();
         
        bs1 = new BookieServer(initialPort, tmpDirB1, new File[]{tmpDirB1});
        bs1.start();
        
        tmpDirB2 = File.createTempFile("bookie2", "test");
        tmpDirB2.delete();
        tmpDirB2.mkdir();
            
        bs2 = new BookieServer(initialPort + 1, tmpDirB2, new File[]{tmpDirB2});
        bs2.start();

        tmpDirB3 = File.createTempFile("bookie3", "test");
        tmpDirB3.delete();
        tmpDirB3.mkdir();
        
        bs3 = new BookieServer(initialPort + 2, tmpDirB3, new File[]{tmpDirB3});
        bs3.start();
        
        rng = new Random(System.currentTimeMillis());   // Initialize the Random Number Generator 
        entries = new ArrayList<byte[]>(); // initialize the  entries list
        entriesSize = new ArrayList<Integer>(); 
        sync = new SyncObj(); // initialize the synchronization data structure
    }
    
    protected void tearDown(){
        LOG.info("TearDown");

        //shutdown bookie servers 
        try {
            bs1.shutdown();
            bs2.shutdown();
            bs3.shutdown();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        cleanUpDir(tmpDirB1);
        cleanUpDir(tmpDirB2);
        cleanUpDir(tmpDirB3);
        
        //shutdown ZK server
        serverFactory.shutdown();
        assertTrue("waiting for server down",
                ClientBase.waitForServerDown(HOSTPORT,
                                             ClientBase.CONNECTION_TIMEOUT));
        //ServerStats.unregister();
        cleanUpDir(ZkTmpDir);
        
    }

    /*  Clean up a directory recursively */
    protected boolean cleanUpDir(File dir){
        if (dir.isDirectory()) {
            LOG.info("Cleaning up " + dir.getName());
            String[] children = dir.list();
            for (String string : children) {
                boolean success = cleanUpDir(new File(dir, string));
                if (!success) return false;
            }
        }
        // The directory is now empty so delete it
        return dir.delete();        
    }

    /*  User for testing purposes, void */
    class emptyWatcher implements Watcher{
        public void process(WatchedEvent event) {}
    }
}