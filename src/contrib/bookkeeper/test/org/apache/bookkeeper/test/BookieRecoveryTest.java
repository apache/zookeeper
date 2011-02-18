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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.LedgerEntry;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.AsyncCallback.RecoverCallback;
import org.apache.bookkeeper.client.BookKeeper.DigestType;
import org.apache.bookkeeper.proto.BookieServer;
import org.apache.bookkeeper.tools.BookKeeperTools;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * This class tests the bookie recovery admin functionality.
 */
public class BookieRecoveryTest extends BaseTestCase {
    static Logger LOG = Logger.getLogger(BookieRecoveryTest.class);

    // Object used for synchronizing async method calls
    class SyncObject {
        boolean value;

        public SyncObject() {
            value = false;
        }
    }

    // Object used for implementing the Bookie RecoverCallback for this jUnit
    // test. This verifies that the operation completed successfully.
    class BookieRecoverCallback implements RecoverCallback {
        @Override
        public void recoverComplete(int rc, Object ctx) {
            LOG.info("Recovered bookie operation completed with rc: " + rc);
            assertTrue(rc == Code.OK.intValue());
            SyncObject sync = (SyncObject) ctx;
            synchronized (sync) {
                sync.value = true;
                sync.notify();
            }
        }
    }

    // Objects to use for this jUnit test.
    DigestType digestType;
    SyncObject sync;
    BookieRecoverCallback bookieRecoverCb;
    BookKeeperTools bkTools;

    // Constructor
    public BookieRecoveryTest(DigestType digestType) {
        super(3);
        this.digestType = digestType;
    }

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        // Set up the configuration properties needed.
        System.setProperty("digestType", digestType.toString());
        System.setProperty("passwd", "");
        sync = new SyncObject();
        bookieRecoverCb = new BookieRecoverCallback();
        bkTools = new BookKeeperTools(HOSTPORT);
    }

    @After
    @Override
    public void tearDown() throws Exception {
        // Release any resources used by the BookKeeperTools instance.
        bkTools.shutdown();
        super.tearDown();
    }

    /**
     * Helper method to create a number of ledgers
     * 
     * @param numLedgers
     *            Number of ledgers to create
     * @return List of LedgerHandles for each of the ledgers created
     * @throws BKException
     * @throws KeeperException
     * @throws IOException
     * @throws InterruptedException
     */
    private List<LedgerHandle> createLedgers(int numLedgers) throws BKException, KeeperException, IOException,
            InterruptedException {
        List<LedgerHandle> lhs = new ArrayList<LedgerHandle>();
        for (int i = 0; i < numLedgers; i++) {
            lhs.add(bkc.createLedger(digestType, System.getProperty("passwd").getBytes()));
        }
        return lhs;
    }

    /**
     * Helper method to write dummy ledger entries to all of the ledgers passed.
     * 
     * @param numEntries
     *            Number of ledger entries to write for each ledger
     * @param startEntryId
     *            The first entry Id we're expecting to write for each ledger
     * @param lhs
     *            List of LedgerHandles for all ledgers to write entries to
     * @throws BKException
     * @throws InterruptedException
     */
    private void writeEntriestoLedgers(int numEntries, long startEntryId, List<LedgerHandle> lhs) throws BKException,
            InterruptedException {
        for (LedgerHandle lh : lhs) {
            for (int i = 0; i < numEntries; i++) {
                lh.addEntry(("LedgerId: " + lh.getId() + ", EntryId: " + (startEntryId + i)).getBytes());
            }
        }
    }

    /**
     * Helper method to startup a new bookie server with the indicated port
     * number
     * 
     * @param port
     *            Port to start the new bookie server on
     * @throws IOException
     */
    private void startNewBookie(int port)
    throws IOException, InterruptedException {
        File f = File.createTempFile("bookie", "test");
        tmpDirs.add(f);
        f.delete();
        f.mkdir();
        BookieServer server = new BookieServer(port, HOSTPORT, f, new File[] { f });
        server.start();
        bs.add(server);
        while(!server.isRunning()){
            Thread.sleep(500);
        }
        LOG.info("New bookie on port " + port + " has been created.");
    }

    /**
     * Helper method to verify that we can read the recovered ledger entries.
     * 
     * @param numLedgers
     *            Number of ledgers to verify
     * @param startEntryId
     *            Start Entry Id to read
     * @param endEntryId
     *            End Entry Id to read
     * @throws BKException
     * @throws InterruptedException
     */
    private void verifyRecoveredLedgers(int numLedgers, long startEntryId, long endEntryId) throws BKException,
            InterruptedException {
        // Get a set of LedgerHandles for all of the ledgers to verify
        List<LedgerHandle> lhs = new ArrayList<LedgerHandle>();
        for (int i = 0; i < numLedgers; i++) {
            lhs.add(bkc.openLedger(i + 1, digestType, System.getProperty("passwd").getBytes()));
        }
        // Read the ledger entries to verify that they are all present and
        // correct in the new bookie.
        for (LedgerHandle lh : lhs) {
            Enumeration<LedgerEntry> entries = lh.readEntries(startEntryId, endEntryId);
            while (entries.hasMoreElements()) {
                LedgerEntry entry = entries.nextElement();
                assertTrue(new String(entry.getEntry()).equals("LedgerId: " + entry.getLedgerId() + ", EntryId: "
                        + entry.getEntryId()));
            }
        }

    }

    /**
     * This tests the asynchronous bookie recovery functionality by writing
     * entries into 3 bookies, killing one bookie, starting up a new one to
     * replace it, and then recovering the ledger entries from the killed bookie
     * onto the new one. We'll verify that the entries stored on the killed
     * bookie are properly copied over and restored onto the new one.
     * 
     * @throws Exception
     */
    @Test
    public void testAsyncBookieRecoveryToSpecificBookie() throws Exception {
        // Create the ledgers
        int numLedgers = 3;
        List<LedgerHandle> lhs = createLedgers(numLedgers);

        // Write the entries for the ledgers with dummy values.
        int numMsgs = 10;
        writeEntriestoLedgers(numMsgs, 0, lhs);

        // Shutdown the first bookie server
        LOG.info("Finished writing all ledger entries so shutdown one of the bookies.");
        bs.get(0).shutdown();
        bs.remove(0);

        // Startup a new bookie server
        int newBookiePort = initialPort + numBookies;
        startNewBookie(newBookiePort);

        // Write some more entries for the ledgers so a new ensemble will be
        // created for them.
        writeEntriestoLedgers(numMsgs, 10, lhs);

        // Call the async recover bookie method.
        InetSocketAddress bookieSrc = new InetSocketAddress(InetAddress.getLocalHost().getHostAddress(), initialPort);
        InetSocketAddress bookieDest = new InetSocketAddress(InetAddress.getLocalHost().getHostAddress(), newBookiePort);
        LOG.info("Now recover the data on the killed bookie (" + bookieSrc + ") and replicate it to the new one ("
                + bookieDest + ")");
        // Initiate the sync object
        sync.value = false;
        bkTools.asyncRecoverBookieData(bookieSrc, bookieDest, bookieRecoverCb, sync);

        // Wait for the async method to complete.
        synchronized (sync) {
            while (sync.value == false) {
                sync.wait();
            }
        }

        // Verify the recovered ledger entries are okay.
        verifyRecoveredLedgers(numLedgers, 0, 2 * numMsgs - 1);
    }

    /**
     * This tests the asynchronous bookie recovery functionality by writing
     * entries into 3 bookies, killing one bookie, starting up a few new
     * bookies, and then recovering the ledger entries from the killed bookie
     * onto random available bookie servers. We'll verify that the entries
     * stored on the killed bookie are properly copied over and restored onto
     * the other bookies.
     * 
     * @throws Exception
     */
    @Test
    public void testAsyncBookieRecoveryToRandomBookies() throws Exception {
        // Create the ledgers
        int numLedgers = 3;
        List<LedgerHandle> lhs = createLedgers(numLedgers);

        // Write the entries for the ledgers with dummy values.
        int numMsgs = 10;
        writeEntriestoLedgers(numMsgs, 0, lhs);

        // Shutdown the first bookie server
        LOG.info("Finished writing all ledger entries so shutdown one of the bookies.");
        bs.get(0).shutdown();
        bs.remove(0);

        // Startup three new bookie servers
        for (int i = 0; i < 3; i++) {
            int newBookiePort = initialPort + numBookies + i;
            startNewBookie(newBookiePort);
        }

        // Write some more entries for the ledgers so a new ensemble will be
        // created for them.
        writeEntriestoLedgers(numMsgs, 10, lhs);

        // Call the async recover bookie method.
        InetSocketAddress bookieSrc = new InetSocketAddress(InetAddress.getLocalHost().getHostAddress(), initialPort);
        InetSocketAddress bookieDest = null;
        LOG.info("Now recover the data on the killed bookie (" + bookieSrc
                + ") and replicate it to a random available one");
        // Initiate the sync object
        sync.value = false;
        bkTools.asyncRecoverBookieData(bookieSrc, bookieDest, bookieRecoverCb, sync);

        // Wait for the async method to complete.
        synchronized (sync) {
            while (sync.value == false) {
                sync.wait();
            }
        }

        // Verify the recovered ledger entries are okay.
        verifyRecoveredLedgers(numLedgers, 0, 2 * numMsgs - 1);
    }

    /**
     * This tests the synchronous bookie recovery functionality by writing
     * entries into 3 bookies, killing one bookie, starting up a new one to
     * replace it, and then recovering the ledger entries from the killed bookie
     * onto the new one. We'll verify that the entries stored on the killed
     * bookie are properly copied over and restored onto the new one.
     * 
     * @throws Exception
     */
    @Test
    public void testSyncBookieRecoveryToSpecificBookie() throws Exception {
        // Create the ledgers
        int numLedgers = 3;
        List<LedgerHandle> lhs = createLedgers(numLedgers);

        // Write the entries for the ledgers with dummy values.
        int numMsgs = 10;
        writeEntriestoLedgers(numMsgs, 0, lhs);

        // Shutdown the first bookie server
        LOG.info("Finished writing all ledger entries so shutdown one of the bookies.");
        bs.get(0).shutdown();
        bs.remove(0);

        // Startup a new bookie server
        int newBookiePort = initialPort + numBookies;
        startNewBookie(newBookiePort);

        // Write some more entries for the ledgers so a new ensemble will be
        // created for them.
        writeEntriestoLedgers(numMsgs, 10, lhs);

        // Call the sync recover bookie method.
        InetSocketAddress bookieSrc = new InetSocketAddress(InetAddress.getLocalHost().getHostAddress(), initialPort);
        InetSocketAddress bookieDest = new InetSocketAddress(InetAddress.getLocalHost().getHostAddress(), newBookiePort);
        LOG.info("Now recover the data on the killed bookie (" + bookieSrc + ") and replicate it to the new one ("
                + bookieDest + ")");
        bkTools.recoverBookieData(bookieSrc, bookieDest);

        // Verify the recovered ledger entries are okay.
        verifyRecoveredLedgers(numLedgers, 0, 2 * numMsgs - 1);
    }

    /**
     * This tests the synchronous bookie recovery functionality by writing
     * entries into 3 bookies, killing one bookie, starting up a few new
     * bookies, and then recovering the ledger entries from the killed bookie
     * onto random available bookie servers. We'll verify that the entries
     * stored on the killed bookie are properly copied over and restored onto
     * the other bookies.
     * 
     * @throws Exception
     */
    @Test
    public void testSyncBookieRecoveryToRandomBookies() throws Exception {
        // Create the ledgers
        int numLedgers = 3;
        List<LedgerHandle> lhs = createLedgers(numLedgers);

        // Write the entries for the ledgers with dummy values.
        int numMsgs = 10;
        writeEntriestoLedgers(numMsgs, 0, lhs);

        // Shutdown the first bookie server
        LOG.info("Finished writing all ledger entries so shutdown one of the bookies.");
        bs.get(0).shutdown();
        bs.remove(0);

        // Startup three new bookie servers
        for (int i = 0; i < 3; i++) {
            int newBookiePort = initialPort + numBookies + i;
            startNewBookie(newBookiePort);
        }

        // Write some more entries for the ledgers so a new ensemble will be
        // created for them.
        writeEntriestoLedgers(numMsgs, 10, lhs);

        // Call the sync recover bookie method.
        InetSocketAddress bookieSrc = new InetSocketAddress(InetAddress.getLocalHost().getHostAddress(), initialPort);
        InetSocketAddress bookieDest = null;
        LOG.info("Now recover the data on the killed bookie (" + bookieSrc
                + ") and replicate it to a random available one");
        bkTools.recoverBookieData(bookieSrc, bookieDest);

        // Verify the recovered ledger entries are okay.
        verifyRecoveredLedgers(numLedgers, 0, 2 * numMsgs - 1);
    }

}
