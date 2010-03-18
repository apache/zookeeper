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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Random;
import java.util.Set;

import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerEntry;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.AsyncCallback.ReadCallback;
import org.apache.bookkeeper.client.BookKeeper.DigestType;
import org.apache.bookkeeper.proto.BookieServer;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.junit.Before;
import org.junit.Test;

/**
 * This test tests read and write, synchronous and asynchronous, strings and
 * integers for a BookKeeper client. The test deployment uses a ZooKeeper server
 * and three BookKeepers.
 * 
 */

public class BookieFailureTest extends BaseTestCase implements AddCallback, ReadCallback {

    // Depending on the taste, select the amount of logging
    // by decommenting one of the two lines below
    // static Logger LOG = Logger.getRootLogger();
    static Logger LOG = Logger.getLogger(BookieFailureTest.class);

    byte[] ledgerPassword = "aaa".getBytes();
    LedgerHandle lh, lh2;
    long ledgerId;
    Enumeration<LedgerEntry> ls;

    // test related variables
    int numEntriesToWrite = 200;
    int maxInt = 2147483647;
    Random rng; // Random Number Generator
    ArrayList<byte[]> entries; // generated entries
    ArrayList<Integer> entriesSize;
    DigestType digestType;
    
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

    public BookieFailureTest(DigestType digestType) {
        super(4);
        this.digestType = digestType;        
    }
    
    /**
     * Tests writes and reads when a bookie fails.
     * 
     * @throws {@link IOException}
     */
    @Test
    public void testAsyncBK1() throws IOException {
        LOG.info("#### BK1 ####");
        auxTestReadWriteAsyncSingleClient(bs.get(0));
    }
    
    @Test
    public void testAsyncBK2() throws IOException {
        LOG.info("#### BK2 ####");
        auxTestReadWriteAsyncSingleClient(bs.get(1));
    }

    @Test
    public void testAsyncBK3() throws IOException {
        LOG.info("#### BK3 ####");
        auxTestReadWriteAsyncSingleClient(bs.get(2));
    }

    @Test
    public void testAsyncBK4() throws IOException {
        LOG.info("#### BK4 ####");
        auxTestReadWriteAsyncSingleClient(bs.get(3));
    }
    
    @Test
    public void testBookieRecovery() throws Exception{
        bkc = new BookKeeper("127.0.0.1");
        
        //Shutdown all but 1 bookie
        bs.get(0).shutdown();
        bs.get(1).shutdown();
        bs.get(2).shutdown();
        
        byte[] passwd = "blah".getBytes();
        LedgerHandle lh = bkc.createLedger(1, 1,digestType, passwd);
        
        int numEntries = 100;
        for (int i=0; i< numEntries; i++){
            byte[] data = (""+i).getBytes();
            lh.addEntry(data);
        }
        
        bs.get(3).shutdown();
        BookieServer server = new BookieServer(initialPort + 3, HOSTPORT, tmpDirs.get(3), new File[] { tmpDirs.get(3)});
        server.start();
        bs.set(3, server);

        assertEquals(numEntries - 1 , lh.getLastAddConfirmed());
        Enumeration<LedgerEntry> entries = lh.readEntries(0, lh.getLastAddConfirmed());
        
        int numScanned = 0;
        while (entries.hasMoreElements()){
            assertEquals((""+numScanned), new String(entries.nextElement().getEntry()));
            numScanned++;
        }
        assertEquals(numEntries, numScanned);
        
        
    }

    void auxTestReadWriteAsyncSingleClient(BookieServer bs) throws IOException {
        try {
            // Create a BookKeeper client and a ledger
            lh = bkc.createLedger(3, 2, digestType, ledgerPassword);

            ledgerId = lh.getId();
            LOG.info("Ledger ID: " + lh.getId());
            for (int i = 0; i < numEntriesToWrite; i++) {
                ByteBuffer entry = ByteBuffer.allocate(4);
                entry.putInt(rng.nextInt(maxInt));
                entry.position(0);

                entries.add(entry.array());
                entriesSize.add(entry.array().length);
                lh.asyncAddEntry(entry.array(), this, sync);
                
            }
            
            LOG.info("Wrote " + numEntriesToWrite + " and now going to fail bookie.");
            // Bookie fail
            bs.shutdown();

            // wait for all entries to be acknowledged
            synchronized (sync) {
                while (sync.counter < numEntriesToWrite) {
                    LOG.debug("Entries counter = " + sync.counter);
                    sync.wait();
                }
            }

            LOG.debug("*** WRITE COMPLETE ***");
            // close ledger
            lh.close();

            // *** WRITING PART COMPLETE // READ PART BEGINS ***

            // open ledger
            bkc.halt();
            bkc = new BookKeeper("127.0.0.1");
            lh = bkc.openLedger(ledgerId, digestType, ledgerPassword);
            LOG.debug("Number of entries written: " + (lh.getLastAddConfirmed() + 1));
            assertTrue("Verifying number of entries written", lh.getLastAddConfirmed() == (numEntriesToWrite - 1));

            // read entries

            lh.asyncReadEntries(0, numEntriesToWrite - 1, this, sync);

            synchronized (sync) {
                while (sync.value == false) {
                    sync.wait(10000);
                    assertTrue("Haven't received entries", sync.value);
                }
            }

            LOG.debug("*** READ COMPLETE ***");

            // at this point, LedgerSequence ls is filled with the returned
            // values
            int i = 0;
            while (ls.hasMoreElements()) {
                ByteBuffer origbb = ByteBuffer.wrap(entries.get(i));
                Integer origEntry = origbb.getInt();
                byte[] entry = ls.nextElement().getEntry();
                ByteBuffer result = ByteBuffer.wrap(entry);

                Integer retrEntry = result.getInt();
                LOG.debug("Retrieved entry: " + i);
                assertTrue("Checking entry " + i + " for equality", origEntry.equals(retrEntry));
                assertTrue("Checking entry " + i + " for size", entry.length == entriesSize.get(i).intValue());
                i++;
            }

            assertTrue("Checking number of read entries", i == numEntriesToWrite);

            LOG.info("Verified that entries are ok, and now closing ledger");
            lh.close();
        } catch (KeeperException e) {
            LOG.error("Caught KeeperException", e);
            fail(e.toString());
        } catch (BKException e) {
            LOG.error("Caught BKException", e);
            fail(e.toString());
        } catch (InterruptedException e) {
            LOG.error("Caught InterruptedException", e);
            fail(e.toString());
        }

    }

    public void addComplete(int rc, LedgerHandle lh, long entryId, Object ctx) {
        if (rc != 0)
            fail("Failed to write entry: " + entryId);
        SyncObj x = (SyncObj) ctx;
        synchronized (x) {
            x.counter++;
            x.notify();
        }
    }

    public void readComplete(int rc, LedgerHandle lh, Enumeration<LedgerEntry> seq, Object ctx) {
        if (rc != 0)
            fail("Failed to write entry");
        ls = seq;
        synchronized (sync) {
            sync.value = true;
            sync.notify();
        }

    }

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        rng = new Random(System.currentTimeMillis()); // Initialize the Random
                                                      // Number Generator
        entries = new ArrayList<byte[]>(); // initialize the entries list
        entriesSize = new ArrayList<Integer>();
        sync = new SyncObj(); // initialize the synchronization data structure

        zkc.close();
    }


    /* Clean up a directory recursively */
    @Override
    protected boolean cleanUpDir(File dir) {
        if (dir.isDirectory()) {
            LOG.info("Cleaning up " + dir.getName());
            String[] children = dir.list();
            for (String string : children) {
                boolean success = cleanUpDir(new File(dir, string));
                if (!success)
                    return false;
            }
        }
        // The directory is now empty so delete it
        return dir.delete();
    }

    /* User for testing purposes, void */
    class emptyWatcher implements Watcher {
        public void process(WatchedEvent event) {
        }
    }

}