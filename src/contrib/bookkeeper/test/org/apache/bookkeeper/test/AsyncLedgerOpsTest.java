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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Random;
import java.util.Set;

import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.client.LedgerEntry;
import org.apache.bookkeeper.client.AsyncCallback.CloseCallback;
import org.apache.bookkeeper.client.AsyncCallback.CreateCallback;
import org.apache.bookkeeper.client.AsyncCallback.OpenCallback;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.AsyncCallback.ReadCallback;
import org.apache.bookkeeper.client.BookKeeper.DigestType;
import org.apache.log4j.Logger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;

/**
 * This test tests read and write, synchronous and asynchronous, strings and
 * integers for a BookKeeper client. The test deployment uses a ZooKeeper server
 * and three BookKeepers.
 * 
 */
public class AsyncLedgerOpsTest extends BaseTestCase implements AddCallback, ReadCallback, CreateCallback,
        CloseCallback, OpenCallback {
    static Logger LOG = Logger.getLogger(BookieClientTest.class);

    DigestType digestType;
    
    public AsyncLedgerOpsTest(DigestType digestType) {
        super(3);
        this.digestType = digestType;
    }
    
    @Parameters
    public static Collection<Object[]> configs(){
        return Arrays.asList(new Object[][]{ {DigestType.MAC }, {DigestType.CRC32}});
    }
    
 
    byte[] ledgerPassword = "aaa".getBytes();
    LedgerHandle lh, lh2;
    long ledgerId;
    Enumeration<LedgerEntry> ls;

    // test related variables
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

    class ControlObj {
        LedgerHandle lh;

        void setLh(LedgerHandle lh) {
            this.lh = lh;
        }

        LedgerHandle getLh() {
            return lh;
        }
    }

    @Test
    public void testAsyncCreateClose() throws IOException {
        try {
            
            ControlObj ctx = new ControlObj();

            synchronized (ctx) {
                LOG.info("Going to create ledger asynchronously");
                bkc.asyncCreateLedger(3, 2, digestType, ledgerPassword, this, ctx);

                ctx.wait();
            }

            // bkc.initMessageDigest("SHA1");
            LedgerHandle lh = ctx.getLh();
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

            LOG.info("*** WRITE COMPLETE ***");
            // close ledger
            synchronized (ctx) {
                lh.asyncClose(this, ctx);
                ctx.wait();
            }

            // *** WRITING PART COMPLETE // READ PART BEGINS ***

            // open ledger
            synchronized (ctx) {
                bkc.asyncOpenLedger(ledgerId, digestType, ledgerPassword, this, ctx);
                ctx.wait();
            }
            lh = ctx.getLh();

            LOG.debug("Number of entries written: " + lh.getLastAddConfirmed());
            assertTrue("Verifying number of entries written", lh.getLastAddConfirmed() == (numEntriesToWrite - 1));

            // read entries
            lh.asyncReadEntries(0, numEntriesToWrite - 1, this, sync);

            synchronized (sync) {
                while (sync.value == false) {
                    sync.wait();
                }
            }

            LOG.debug("*** READ COMPLETE ***");

            // at this point, Enumeration<LedgerEntry> ls is filled with the returned
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
        } catch (InterruptedException e) {
            LOG.error(e);
            fail("InterruptedException");
        } // catch (NoSuchAlgorithmException e) {
        // e.printStackTrace();
        // }

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

    public void createComplete(int rc, LedgerHandle lh, Object ctx) {
        synchronized (ctx) {
            ControlObj cobj = (ControlObj) ctx;
            cobj.setLh(lh);
            cobj.notify();
        }
    }

    public void openComplete(int rc, LedgerHandle lh, Object ctx) {
        synchronized (ctx) {
            ControlObj cobj = (ControlObj) ctx;
            cobj.setLh(lh);
            cobj.notify();
        }
    }

    public void closeComplete(int rc, LedgerHandle lh, Object ctx) {
        synchronized (ctx) {
            ControlObj cobj = (ControlObj) ctx;
            cobj.notify();
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
    }

    



}