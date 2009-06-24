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


import static org.apache.bookkeeper.util.ClientBase.CONNECTION_TIMEOUT;

import java.lang.InterruptedException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import junit.framework.TestCase;

import org.junit.*;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.proto.BookieServer;
import org.apache.bookkeeper.util.ClientBase;
import org.apache.log4j.Logger;

import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZooKeeper;


/**
 * This unit test tests closing ledgers sequentially. 
 * It creates 4 ledgers, then write 1000 entries to each 
 * ledger and close it.
 * 
 */

public class CloseTest 
extends TestCase 
implements Watcher {
    static Logger LOG = Logger.getLogger(LedgerRecoveryTest.class);
    
    BookieServer bs1, bs2, bs3;
    File tmpDir1, tmpDir2, tmpDir3, tmpDirZK;
    private static final String HOSTPORT = "127.0.0.1:33299";
    private NIOServerCnxn.Factory serverFactory;
    
    private static String BOOKIEADDR1 = "127.0.0.1:33300";
    private static String BOOKIEADDR2 = "127.0.0.1:33301";
    private static String BOOKIEADDR3 = "127.0.0.1:33302";
    
    private static void recursiveDelete(File dir) {
        File children[] = dir.listFiles();
        if (children != null) {
            for(File child: children) {
                recursiveDelete(child);
            }
        }
        dir.delete();
    }
    
    protected void setUp() throws Exception {
        /*
         * Creates 3 BookieServers
         */
        
        
        tmpDir1 = File.createTempFile("bookie1", "test");
        tmpDir1.delete();
        tmpDir1.mkdir();
        
        final int PORT1 = Integer.parseInt(BOOKIEADDR1.split(":")[1]);
        bs1 = new BookieServer(PORT1, tmpDir1, new File[] { tmpDir1 });
        bs1.start();
        
        tmpDir2 = File.createTempFile("bookie2", "test");
        tmpDir2.delete();
        tmpDir2.mkdir();
        
        final int PORT2 = Integer.parseInt(BOOKIEADDR2.split(":")[1]);
        bs2 = new BookieServer(PORT2, tmpDir2, new File[] { tmpDir2 });
        bs2.start();
        
        tmpDir3 = File.createTempFile("bookie3", "test");
        tmpDir3.delete();
        tmpDir3.mkdir();
        
        final int PORT3 = Integer.parseInt(BOOKIEADDR3.split(":")[1]);
        bs3 = new BookieServer(PORT3, tmpDir3, new File[] { tmpDir3 });
        bs3.start();
        
        /*
         * Instantiates a ZooKeeper server. This is a blind copy
         * of setUp from SessionTest.java.
         */
        LOG.info("STARTING " + getName());

        //ServerStats.registerAsConcrete();

        tmpDirZK = ClientBase.createTmpDir();

        ClientBase.setupTestEnv();
        ZooKeeperServer zs = new ZooKeeperServer(tmpDirZK, tmpDirZK, 3000);
        
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        serverFactory = new NIOServerCnxn.Factory(PORT);
        serverFactory.startup(zs);

        assertTrue("waiting for server up",
                   ClientBase.waitForServerUp(HOSTPORT,
                                              CONNECTION_TIMEOUT));
        
        /*
         * Creating necessary znodes
         */
        try{
            ZooKeeper zk = new ZooKeeper(HOSTPORT, 3000, this);
            zk.create("/ledgers", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create("/ledgers/available", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create("/ledgers/available/" + BOOKIEADDR1, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT );
            zk.create("/ledgers/available/" + BOOKIEADDR2, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT );
            zk.create("/ledgers/available/" + BOOKIEADDR3, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT );
            zk.close();
        } catch (KeeperException ke) {
            LOG.error(ke);
            fail("Couldn't execute ZooKeeper start procedure");
        }
        
    }
    
    /**
     * Watcher method. 
     */
    synchronized public void process(WatchedEvent event) {
        LOG.info("Process: " + event.getType() + " " + event.getPath());
    }
    
    protected void tearDown() throws Exception {
        LOG.info("### Tear down ###");
        bs1.shutdown();
        recursiveDelete(tmpDir1);
        
        bs2.shutdown();
        recursiveDelete(tmpDir2);
        
        bs3.shutdown();
        recursiveDelete(tmpDir3);
        
        serverFactory.shutdown();
        assertTrue("waiting for server down",
                   ClientBase.waitForServerDown(HOSTPORT,
                                                CONNECTION_TIMEOUT));

        //ServerStats.unregister();
        recursiveDelete(tmpDirZK);
        LOG.info("FINISHED " + getName());
    }

    @Test
    public void testClose(){
        /*
         * Instantiate BookKeeper object.
         */
        BookKeeper bk = null;
        try{
            bk = new BookKeeper(HOSTPORT);
        } catch (KeeperException ke){
            LOG.error("Error instantiating BookKeeper", ke);
            fail("ZooKeeper error");
        } catch (IOException ioe){
            LOG.error(ioe);
            fail("Failure due to IOException");
        }
        
        /*
         * Create 4 ledgers.
         */
        LedgerHandle lh1 = null;
        LedgerHandle lh2 = null;
        LedgerHandle lh3 = null;
        LedgerHandle lh4 = null;
        
        try{
            lh1 = bk.createLedger("".getBytes());
            lh2 = bk.createLedger("".getBytes());
            lh3 = bk.createLedger("".getBytes());
            lh4 = bk.createLedger("".getBytes());
        } catch (KeeperException ke){
            LOG.error("Error creating a ledger", ke);
            fail("ZooKeeper error");            
        } catch (BKException bke){
            LOG.error("BookKeeper error");
            fail("BookKeeper error");
        } catch (InterruptedException ie) {
            LOG.error(ie);
            fail("Failure due to interrupted exception");
        } catch (IOException ioe) {
            LOG.error(ioe);
            fail("Failure due to IO exception");
        }
        
        /*
         * Write a 1000 entries to lh1.
         */
        try{
            String tmp = "BookKeeper is cool!";
            for(int i = 0; i < 1000; i++){
                lh1.addEntry(tmp.getBytes());
            }
        } catch(InterruptedException e){
            LOG.error("Interrupted when adding entry", e);
            fail("Couldn't finish adding entries");
        } catch(BKException e){
            LOG.error("BookKeeper exception", e);
            fail("BookKeeper exception when adding entries");
        }
        
        try{
            lh1.close();
        } catch(Exception e) {
            LOG.error(e);
            fail("Exception while closing ledger 1");
        }
        /*
         * Write a 1000 entries to lh2.
         */
        try{
            String tmp = "BookKeeper is cool!";
            for(int i = 0; i < 1000; i++){
                lh2.addEntry(tmp.getBytes());
            }
        } catch(InterruptedException e){
            LOG.error("Interrupted when adding entry", e);
            fail("Couldn't finish adding entries");
        } catch(BKException e){
            LOG.error("BookKeeper exception", e);
            fail("CBookKeeper exception while adding entries");
        }
        
        try{
            lh2.close();
        } catch(Exception e){
            LOG.error(e);
            fail("Exception while closing ledger 2");
        }
        
        /*
         * Write a 1000 entries to lh3 and lh4.
         */
        try{
            String tmp = "BookKeeper is cool!";
            for(int i = 0; i < 1000; i++){
                lh3.addEntry(tmp.getBytes());
                lh4.addEntry(tmp.getBytes());
            }
        } catch(InterruptedException e){
            LOG.error("Interrupted when adding entry", e);
            fail("Couldn't finish adding entries");
        } catch(BKException e){
            LOG.error("BookKeeper exception", e);
            fail("BookKeeper exception when adding entries");
        }
        
        try{
            lh3.close();
            lh4.close();
        } catch(Exception e){
            LOG.error(e);
            fail("Exception while closing ledger 4");
        }
    }      
}
    
    