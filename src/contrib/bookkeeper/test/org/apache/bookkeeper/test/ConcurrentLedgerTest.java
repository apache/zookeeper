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

package org.apache.bookkeeper.test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.bookkeeper.bookie.Bookie;
import org.apache.bookkeeper.bookie.BookieException;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.WriteCallback;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import junit.framework.TestCase;

/**
 * Tests writing to concurrent ledgers
 */
public class ConcurrentLedgerTest extends TestCase {
    Bookie bookie;
    File txnDir, ledgerDir;
    int recvTimeout = 10000;
    Semaphore throttle;
    
    @Override
    @Before
    public void setUp() throws IOException {
        String txnDirName = System.getProperty("txnDir");
        if (txnDirName != null) {
            txnDir = new File(txnDirName);
        }
        String ledgerDirName = System.getProperty("ledgerDir");
        if (ledgerDirName != null) {
            ledgerDir = new File(ledgerDirName);
        }
        File tmpFile = File.createTempFile("book", ".txn", txnDir);
        tmpFile.delete();
        txnDir = new File(tmpFile.getParent(), tmpFile.getName()+".dir");
        txnDir.mkdirs();
        tmpFile = File.createTempFile("book", ".ledger", ledgerDir);
        ledgerDir = new File(tmpFile.getParent(), tmpFile.getName()+".dir");
        ledgerDir.mkdirs();
        
        bookie = new Bookie(5000, null, txnDir, new File[] {ledgerDir});
    }
    
    static void recursiveDelete(File f) {
        if (f.isFile()) {
            f.delete();
        } else {
            for(File i: f.listFiles()) {
                recursiveDelete(i);
            }
            f.delete();
        }
    }
    
    @Override
    @After
    public void tearDown() {
        try {
            bookie.shutdown();
            recursiveDelete(txnDir);
            recursiveDelete(ledgerDir);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    byte zeros[] = new byte[16];

    int iterations = 51;
    {
        String iterationsString = System.getProperty("iterations");
        if (iterationsString != null) {
            iterations = Integer.parseInt(iterationsString);
        }
    }
    int iterationStep = 25;
    {
        String iterationsString = System.getProperty("iterationStep");
        if (iterationsString != null) {
            iterationStep = Integer.parseInt(iterationsString);
        }
    }
    @Test
    public void testConcurrentWrite() throws IOException, InterruptedException, BookieException {
        int size = 1024;
        int totalwrites = 128;
        if (System.getProperty("totalwrites") != null) {
            totalwrites = Integer.parseInt(System.getProperty("totalwrites"));
        }
        System.out.println("Running up to " + iterations + " iterations");
        System.out.println("Total writes = " + totalwrites);
        int ledgers;
        for(ledgers = 1; ledgers <= iterations; ledgers += iterationStep) {
            long duration = doWrites(ledgers, size, totalwrites);
            System.out.println(totalwrites + " on " + ledgers + " took " + duration + " ms");
        }
        System.out.println("ledgers " + ledgers);
        for(ledgers = 1; ledgers <= iterations; ledgers += iterationStep) {
            long duration = doReads(ledgers, size, totalwrites);
            System.out.println(ledgers + " read " + duration + " ms");
        }
    }

    private long doReads(int ledgers, int size, int totalwrites)
            throws IOException, InterruptedException, BookieException {
        long start = System.currentTimeMillis();
        for(int i = 1; i <= totalwrites/ledgers; i++) {
            for(int j = 1; j <= ledgers; j++) {
                ByteBuffer entry = bookie.readEntry(j, i);
                // skip the ledger id and the entry id
                entry.getLong();
                entry.getLong();
                assertEquals(j + "@" + i, j+2, entry.getLong());
                assertEquals(j + "@" + i, i+3, entry.getLong());
            }
        }
        long finish = System.currentTimeMillis();
        return finish - start;
    }
    private long doWrites(int ledgers, int size, int totalwrites)
            throws IOException, InterruptedException, BookieException {
        throttle = new Semaphore(10000);
        WriteCallback cb = new WriteCallback() {
            @Override
            public void writeComplete(int rc, long ledgerId, long entryId,
                    InetSocketAddress addr, Object ctx) {
                AtomicInteger counter = (AtomicInteger)ctx;
                counter.getAndIncrement();
                throttle.release();
            }
        };
        AtomicInteger counter = new AtomicInteger();
        long start = System.currentTimeMillis();
        for(int i = 1; i <= totalwrites/ledgers; i++) {
            for(int j = 1; j <= ledgers; j++) {
                ByteBuffer bytes = ByteBuffer.allocate(size);
                bytes.putLong(j);
                bytes.putLong(i);
                bytes.putLong(j+2);
                bytes.putLong(i+3);
                bytes.put(("This is ledger " + j + " entry " + i).getBytes());
                bytes.position(0);
                bytes.limit(bytes.capacity());
                throttle.acquire();
                bookie.addEntry(bytes, cb, counter, zeros);
            }
        }
        long finish = System.currentTimeMillis();
        return finish - start;
    }
}
