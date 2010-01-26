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
import org.apache.bookkeeper.streaming.LedgerInputStream;
import org.apache.bookkeeper.streaming.LedgerOutputStream;
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

public class BookieReadWriteTest extends BaseTestCase implements AddCallback, ReadCallback {

    // Depending on the taste, select the amount of logging
    // by decommenting one of the two lines below
    // static Logger LOG = Logger.getRootLogger();
    static Logger LOG = Logger.getLogger(BookieReadWriteTest.class);

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
    
    public BookieReadWriteTest(DigestType digestType){
        super(3);
        this.digestType = digestType;
    }
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

    @Test
    public void testOpenException() throws KeeperException, IOException, InterruptedException {
        try {
            lh = bkc.openLedger(0, digestType, ledgerPassword);
            fail("Haven't thrown exception");
        } catch (BKException e) {
            LOG.warn("Successfully thrown and caught exception:", e);
        }
    }

    /**
     * test the streaming api for reading and writing
     * 
     * @throws {@link IOException}, {@link KeeperException}
     */
    @Test
    public void testStreamingClients() throws IOException, KeeperException, BKException, InterruptedException {
        bkc = new BookKeeper("127.0.0.1");
        lh = bkc.createLedger(digestType, ledgerPassword);
        // write a string so that we cna
        // create a buffer of a single bytes
        // and check for corner cases
        String toWrite = "we need to check for this string to match " + "and for the record mahadev is the best";
        LedgerOutputStream lout = new LedgerOutputStream(lh, 1);
        byte[] b = toWrite.getBytes();
        lout.write(b);
        lout.close();
        long lId = lh.getId();
        lh.close();
        // check for sanity
        lh = bkc.openLedger(lId, digestType, ledgerPassword);
        LedgerInputStream lin = new LedgerInputStream(lh, 1);
        byte[] bread = new byte[b.length];
        int read = 0;
        while (read < b.length) {
            read = read + lin.read(bread, read, b.length);
        }

        String newString = new String(bread);
        assertTrue("these two should same", toWrite.equals(newString));
        lin.close();
        lh.close();
        // create another ledger to write one byte at a time
        lh = bkc.createLedger(digestType, ledgerPassword);
        lout = new LedgerOutputStream(lh);
        for (int i = 0; i < b.length; i++) {
            lout.write(b[i]);
        }
        lout.close();
        lId = lh.getId();
        lh.close();
        lh = bkc.openLedger(lId, digestType, ledgerPassword);
        lin = new LedgerInputStream(lh);
        bread = new byte[b.length];
        read = 0;
        while (read < b.length) {
            read = read + lin.read(bread, read, b.length);
        }
        newString = new String(bread);
        assertTrue("these two should be same ", toWrite.equals(newString));
        lin.close();
        lh.close();
    }

    @Test
    public void testReadWriteAsyncSingleClient() throws IOException {
        try {
            // Create a BookKeeper client and a ledger
            bkc = new BookKeeper("127.0.0.1");
            lh = bkc.createLedger(digestType, ledgerPassword);
            // bkc.initMessageDigest("SHA1");
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
            lh = bkc.openLedger(ledgerId, digestType, ledgerPassword);
            LOG.debug("Number of entries written: " + (lh.getLastAddConfirmed() + 1));
            assertTrue("Verifying number of entries written", lh.getLastAddConfirmed() == (numEntriesToWrite - 1));

            // read entries
            lh.asyncReadEntries(0, numEntriesToWrite - 1, this, (Object) sync);

            synchronized (sync) {
                while (sync.value == false) {
                    sync.wait();
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
                LOG.debug("Length of result: " + result.capacity());
                LOG.debug("Original entry: " + origEntry);

                Integer retrEntry = result.getInt();
                LOG.debug("Retrieved entry: " + retrEntry);
                assertTrue("Checking entry " + i + " for equality", origEntry.equals(retrEntry));
                assertTrue("Checking entry " + i + " for size", entry.length == entriesSize.get(i).intValue());
                i++;
            }
            assertTrue("Checking number of read entries", i == numEntriesToWrite);

            lh.close();
        } catch (KeeperException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to ZooKeeper exception");
        } catch (BKException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to BookKeeper exception");
        } catch (InterruptedException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to interruption");
        }
    }

    @Test
    public void testSyncReadAsyncWriteStringsSingleClient() throws IOException {
        LOG.info("TEST READ WRITE STRINGS MIXED SINGLE CLIENT");
        String charset = "utf-8";
        LOG.debug("Default charset: " + Charset.defaultCharset());
        try {
            // Create a BookKeeper client and a ledger
            bkc = new BookKeeper("127.0.0.1");
            lh = bkc.createLedger(digestType, ledgerPassword);
            // bkc.initMessageDigest("SHA1");
            ledgerId = lh.getId();
            LOG.info("Ledger ID: " + lh.getId());
            for (int i = 0; i < numEntriesToWrite; i++) {
                int randomInt = rng.nextInt(maxInt);
                byte[] entry = new String(Integer.toString(randomInt)).getBytes(charset);
                entries.add(entry);
                lh.asyncAddEntry(entry, this, sync);
            }

            // wait for all entries to be acknowledged
            synchronized (sync) {
                while (sync.counter < numEntriesToWrite) {
                    LOG.debug("Entries counter = " + sync.counter);
                    sync.wait();
                }
            }

            LOG.debug("*** ASYNC WRITE COMPLETE ***");
            // close ledger
            lh.close();

            // *** WRITING PART COMPLETED // READ PART BEGINS ***

            // open ledger
            lh = bkc.openLedger(ledgerId, digestType, ledgerPassword);
            LOG.debug("Number of entries written: " + (lh.getLastAddConfirmed() + 1));
            assertTrue("Verifying number of entries written", lh.getLastAddConfirmed() == (numEntriesToWrite - 1));

            // read entries
            ls = lh.readEntries(0, numEntriesToWrite - 1);

            LOG.debug("*** SYNC READ COMPLETE ***");

            // at this point, LedgerSequence ls is filled with the returned
            // values
            int i = 0;
            while (ls.hasMoreElements()) {
                byte[] origEntryBytes = entries.get(i++);
                byte[] retrEntryBytes = ls.nextElement().getEntry();

                LOG.debug("Original byte entry size: " + origEntryBytes.length);
                LOG.debug("Saved byte entry size: " + retrEntryBytes.length);

                String origEntry = new String(origEntryBytes, charset);
                String retrEntry = new String(retrEntryBytes, charset);

                LOG.debug("Original entry: " + origEntry);
                LOG.debug("Retrieved entry: " + retrEntry);

                assertTrue("Checking entry " + i + " for equality", origEntry.equals(retrEntry));
            }
            assertTrue("Checking number of read entries", i == numEntriesToWrite);

            lh.close();
        } catch (KeeperException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to ZooKeeper exception");
        } catch (BKException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to BookKeeper exception");
        } catch (InterruptedException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to interruption");
        }

    }

    @Test
    public void testReadWriteSyncSingleClient() throws IOException {
        try {
            // Create a BookKeeper client and a ledger
            bkc = new BookKeeper("127.0.0.1");
            lh = bkc.createLedger(digestType, ledgerPassword);
            // bkc.initMessageDigest("SHA1");
            ledgerId = lh.getId();
            LOG.info("Ledger ID: " + lh.getId());
            for (int i = 0; i < numEntriesToWrite; i++) {
                ByteBuffer entry = ByteBuffer.allocate(4);
                entry.putInt(rng.nextInt(maxInt));
                entry.position(0);
                entries.add(entry.array());
                lh.addEntry(entry.array());
            }
            lh.close();
            lh = bkc.openLedger(ledgerId, digestType, ledgerPassword);
            LOG.debug("Number of entries written: " + lh.getLastAddConfirmed());
            assertTrue("Verifying number of entries written", lh.getLastAddConfirmed() == (numEntriesToWrite - 1));

            ls = lh.readEntries(0, numEntriesToWrite - 1);
            int i = 0;
            while (ls.hasMoreElements()) {
                ByteBuffer origbb = ByteBuffer.wrap(entries.get(i++));
                Integer origEntry = origbb.getInt();
                ByteBuffer result = ByteBuffer.wrap(ls.nextElement().getEntry());
                LOG.debug("Length of result: " + result.capacity());
                LOG.debug("Original entry: " + origEntry);

                Integer retrEntry = result.getInt();
                LOG.debug("Retrieved entry: " + retrEntry);
                assertTrue("Checking entry " + i + " for equality", origEntry.equals(retrEntry));
            }
            lh.close();
        } catch (KeeperException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to ZooKeeper exception");
        } catch (BKException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to BookKeeper exception");
        } catch (InterruptedException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to interruption");
        }
    }

    @Test
    public void testReadWriteZero() throws IOException {
        try {
            // Create a BookKeeper client and a ledger
            bkc = new BookKeeper("127.0.0.1");
            lh = bkc.createLedger(digestType, ledgerPassword);
            // bkc.initMessageDigest("SHA1");
            ledgerId = lh.getId();
            LOG.info("Ledger ID: " + lh.getId());
            for (int i = 0; i < numEntriesToWrite; i++) {
                lh.addEntry(new byte[0]);
            }

            /*
             * Write a non-zero entry
             */
            ByteBuffer entry = ByteBuffer.allocate(4);
            entry.putInt(rng.nextInt(maxInt));
            entry.position(0);
            entries.add(entry.array());
            lh.addEntry(entry.array());

            lh.close();
            lh = bkc.openLedger(ledgerId, digestType, ledgerPassword);
            LOG.debug("Number of entries written: " + lh.getLastAddConfirmed());
            assertTrue("Verifying number of entries written", lh.getLastAddConfirmed() == numEntriesToWrite);

            ls = lh.readEntries(0, numEntriesToWrite - 1);
            int i = 0;
            while (ls.hasMoreElements()) {
                ByteBuffer result = ByteBuffer.wrap(ls.nextElement().getEntry());
                LOG.debug("Length of result: " + result.capacity());

                assertTrue("Checking if entry " + i + " has zero bytes", result.capacity() == 0);
            }
            lh.close();
        } catch (KeeperException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to ZooKeeper exception");
        } catch (BKException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to BookKeeper exception");
        } catch (InterruptedException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to interruption");
        }
    }

    @Test
    public void testMultiLedger() throws IOException {
        try {
            // Create a BookKeeper client and a ledger
            bkc = new BookKeeper("127.0.0.1");
            lh = bkc.createLedger(digestType, ledgerPassword);
            lh2 = bkc.createLedger(digestType, ledgerPassword);

            long ledgerId = lh.getId();
            long ledgerId2 = lh2.getId();

            // bkc.initMessageDigest("SHA1");
            LOG.info("Ledger ID 1: " + lh.getId() + ", Ledger ID 2: " + lh2.getId());
            for (int i = 0; i < numEntriesToWrite; i++) {
                lh.addEntry(new byte[0]);
                lh2.addEntry(new byte[0]);
            }

            lh.close();
            lh2.close();

            lh = bkc.openLedger(ledgerId, digestType, ledgerPassword);
            lh2 = bkc.openLedger(ledgerId2, digestType, ledgerPassword);

            LOG.debug("Number of entries written: " + lh.getLastAddConfirmed() + ", " + lh2.getLastAddConfirmed());
            assertTrue("Verifying number of entries written lh (" + lh.getLastAddConfirmed() + ")", lh
                    .getLastAddConfirmed() == (numEntriesToWrite - 1));
            assertTrue("Verifying number of entries written lh2 (" + lh2.getLastAddConfirmed() + ")", lh2
                    .getLastAddConfirmed() == (numEntriesToWrite - 1));

            ls = lh.readEntries(0, numEntriesToWrite - 1);
            int i = 0;
            while (ls.hasMoreElements()) {
                ByteBuffer result = ByteBuffer.wrap(ls.nextElement().getEntry());
                LOG.debug("Length of result: " + result.capacity());

                assertTrue("Checking if entry " + i + " has zero bytes", result.capacity() == 0);
            }
            lh.close();
            ls = lh2.readEntries(0, numEntriesToWrite - 1);
            i = 0;
            while (ls.hasMoreElements()) {
                ByteBuffer result = ByteBuffer.wrap(ls.nextElement().getEntry());
                LOG.debug("Length of result: " + result.capacity());

                assertTrue("Checking if entry " + i + " has zero bytes", result.capacity() == 0);
            }
            lh2.close();
        } catch (KeeperException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to ZooKeeper exception");
        } catch (BKException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to BookKeeper exception");
        } catch (InterruptedException e) {
            LOG.error("Test failed", e);
            fail("Test failed due to interruption");
        }
    }

    public void addComplete(int rc, LedgerHandle lh, long entryId, Object ctx) {
        SyncObj x = (SyncObj) ctx;
        synchronized (x) {
            x.counter++;
            x.notify();
        }
    }

    public void readComplete(int rc, LedgerHandle lh, Enumeration<LedgerEntry> seq, Object ctx) {
        ls = seq;
        synchronized (sync) {
            sync.value = true;
            sync.notify();
        }

    }

    @Before
    public void setUp() throws Exception{
        super.setUp();
        rng = new Random(System.currentTimeMillis()); // Initialize the Random
                                                      // Number Generator
        entries = new ArrayList<byte[]>(); // initialize the entries list
        entriesSize = new ArrayList<Integer>();
        sync = new SyncObj(); // initialize the synchronization data structure
        
    }

    /* Clean up a directory recursively */
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
