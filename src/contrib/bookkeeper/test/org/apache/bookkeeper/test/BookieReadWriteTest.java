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
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;

import org.apache.bookkeeper.client.AddCallback;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.LedgerSequence;
import org.apache.bookkeeper.client.ReadCallback;
import org.apache.bookkeeper.proto.BookieServer;
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
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZooKeeperServer;

import org.apache.zookeeper.ZooDefs.Ids;
import org.junit.Test;

/**
 * This test tests read and write, synchronous and 
 * asynchronous, strings and integers for a BookKeeper client. 
 * The test deployment uses a ZooKeeper server 
 * and three BookKeepers. 
 * 
 */

public class BookieReadWriteTest 
	extends junit.framework.TestCase 
	implements AddCallback, ReadCallback{

	//Depending on the taste, select the amount of logging
	// by decommenting one of the two lines below
	//static Logger LOG = Logger.getRootLogger();
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
	LedgerHandle lh;
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
    
    @Test
	public void testReadWriteAsyncSingleClient() throws IOException{
		try {
			// Create a BookKeeper client and a ledger
			bkc = new BookKeeper("127.0.0.1");
			lh = bkc.createLedger(ledgerPassword);
			bkc.initMessageDigest("SHA1");
			ledgerId = lh.getId();
			LOG.info("Ledger ID: " + lh.getId());
			for(int i = 0; i < numEntriesToWrite; i++){
				ByteBuffer entry = ByteBuffer.allocate(4);
				entry.putInt(rng.nextInt(maxInt));
				entry.position(0);
				
				entries.add(entry.array());
				entriesSize.add(entry.array().length);
				bkc.asyncAddEntry(lh, entry.array(), this, sync);
			}
			
			// wait for all entries to be acknowledged
			synchronized (sync) {
				if (sync.counter < numEntriesToWrite){
					LOG.debug("Entries counter = " + sync.counter);
					sync.wait();
				}
			}
			
			LOG.debug("*** WRITE COMPLETED ***");
			// close ledger 
			bkc.closeLedger(lh);
			
			//*** WRITING PART COMPLETED // READ PART BEGINS ***
			
			// open ledger
			lh = bkc.openLedger(ledgerId, ledgerPassword);
			LOG.debug("Number of entries written: " + lh.getLast());
			assertTrue("Verifying number of entries written", lh.getLast() == numEntriesToWrite);		
			
			//read entries
			bkc.asyncReadEntries(lh, 0, numEntriesToWrite - 1, this, (Object) sync);
			
			synchronized (sync) {
				while(sync.value == false){
					sync.wait();
				}				
			}
			
			assertTrue("Checking number of read entries", ls.size() == numEntriesToWrite);
			
			LOG.debug("*** READ COMPLETED ***");
			
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
			bkc.closeLedger(lh);
		} catch (KeeperException e) {
			e.printStackTrace();
		} catch (BKException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		
	}
	
    @Test
	public void testSyncReadAsyncWriteStringsSingleClient() throws IOException{
    	LOG.info("TEST READ WRITE STRINGS MIXED SINGLE CLIENT");
		String charset = "utf-8";
		LOG.debug("Default charset: "  + Charset.defaultCharset());
		try {
			// Create a BookKeeper client and a ledger
			bkc = new BookKeeper("127.0.0.1");
			lh = bkc.createLedger(ledgerPassword);
			bkc.initMessageDigest("SHA1");
			ledgerId = lh.getId();
			LOG.info("Ledger ID: " + lh.getId());
			for(int i = 0; i < numEntriesToWrite; i++){
				int randomInt = rng.nextInt(maxInt);
				byte[] entry = new String(Integer.toString(randomInt)).getBytes(charset);
				entries.add(entry);
				bkc.asyncAddEntry(lh, entry, this, sync);
			}
			
			// wait for all entries to be acknowledged
			synchronized (sync) {
				if (sync.counter < numEntriesToWrite){
					LOG.debug("Entries counter = " + sync.counter);
					sync.wait();
				}
			}
			
			LOG.debug("*** ASYNC WRITE COMPLETED ***");
			// close ledger 
			bkc.closeLedger(lh);
			
			//*** WRITING PART COMPLETED // READ PART BEGINS ***
			
			// open ledger
			lh = bkc.openLedger(ledgerId, ledgerPassword);
			LOG.debug("Number of entries written: " + lh.getLast());
			assertTrue("Verifying number of entries written", lh.getLast() == numEntriesToWrite);		
			
			//read entries			
			ls = bkc.readEntries(lh, 0, numEntriesToWrite - 1);
			
			assertTrue("Checking number of read entries", ls.size() == numEntriesToWrite);
			
			LOG.debug("*** SYNC READ COMPLETED ***");
			
			// at this point, LedgerSequence ls is filled with the returned values
			int i = 0;
			while(ls.hasMoreElements()){
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
			bkc.closeLedger(lh);
		} catch (KeeperException e) {
			e.printStackTrace();
		} catch (BKException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		
	}
    
    public void testReadWriteSyncSingleClient() throws IOException {
		try {
			// Create a BookKeeper client and a ledger
			bkc = new BookKeeper("127.0.0.1");
			lh = bkc.createLedger(ledgerPassword);
			bkc.initMessageDigest("SHA1");
			ledgerId = lh.getId();
			LOG.info("Ledger ID: " + lh.getId());
			for(int i = 0; i < numEntriesToWrite; i++){
				ByteBuffer entry = ByteBuffer.allocate(4);
				entry.putInt(rng.nextInt(maxInt));
				entry.position(0);
				entries.add(entry.array());				
				bkc.addEntry(lh, entry.array());
			}
			bkc.closeLedger(lh);
			lh = bkc.openLedger(ledgerId, ledgerPassword);
			LOG.debug("Number of entries written: " + lh.getLast());
			assertTrue("Verifying number of entries written", lh.getLast() == numEntriesToWrite);		
			
			ls = bkc.readEntries(lh, 0, numEntriesToWrite - 1);
			int i = 0;
			while(ls.hasMoreElements()){
			    ByteBuffer origbb = ByteBuffer.wrap(entries.get(i++));
				Integer origEntry = origbb.getInt();
				ByteBuffer result = ByteBuffer.wrap(ls.nextElement().getEntry());
				LOG.debug("Length of result: " + result.capacity());
				LOG.debug("Original entry: " + origEntry);

				Integer retrEntry = result.getInt();
				LOG.debug("Retrieved entry: " + retrEntry);
				assertTrue("Checking entry " + i + " for equality", origEntry.equals(retrEntry));
			}
			bkc.closeLedger(lh);
		} catch (KeeperException e) {
			e.printStackTrace();
		} catch (BKException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
	}
	
    public void testReadWriteZero() throws IOException {
		try {
			// Create a BookKeeper client and a ledger
			bkc = new BookKeeper("127.0.0.1");
			lh = bkc.createLedger(ledgerPassword);
			bkc.initMessageDigest("SHA1");
			ledgerId = lh.getId();
			LOG.info("Ledger ID: " + lh.getId());
			for(int i = 0; i < numEntriesToWrite; i++){				
				bkc.addEntry(lh, new byte[0]);
			}
			
			/*
			 * Write a non-zero entry
			 */
			ByteBuffer entry = ByteBuffer.allocate(4);
			entry.putInt(rng.nextInt(maxInt));
			entry.position(0);
			entries.add(entry.array());				
			bkc.addEntry(lh, entry.array());
			
			bkc.closeLedger(lh);
			lh = bkc.openLedger(ledgerId, ledgerPassword);
			LOG.debug("Number of entries written: " + lh.getLast());
			assertTrue("Verifying number of entries written", lh.getLast() == (numEntriesToWrite + 1));		
			
			ls = bkc.readEntries(lh, 0, numEntriesToWrite - 1);
			int i = 0;
			while(ls.hasMoreElements()){
				ByteBuffer result = ByteBuffer.wrap(ls.nextElement().getEntry());
				LOG.debug("Length of result: " + result.capacity());
				
				assertTrue("Checking if entry " + i + " has zero bytes", result.capacity() == 0);
			}
			bkc.closeLedger(lh);
		} catch (KeeperException e) {
			e.printStackTrace();
		} catch (BKException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
	}
    
    
	public void addComplete(int rc, long ledgerId, long entryId, Object ctx) {
		SyncObj x = (SyncObj) ctx;
		synchronized (x) {
			x.counter++;
			x.notify();
		}
	}

	public void readComplete(int rc, long ledgerId, LedgerSequence seq,
			Object ctx) {
		ls = seq;				
		synchronized (sync) {
			sync.value = true;
			sync.notify();
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
		
		rng = new Random(System.currentTimeMillis());	// Initialize the Random Number Generator 
		entries = new ArrayList<byte[]>(); // initialize the  entries list
		entriesSize = new ArrayList<Integer>(); 
		sync = new SyncObj(); // initialize the synchronization data structure
	}
	
	protected void tearDown(){
		LOG.debug("TearDown");

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

	/*	Clean up a directory recursively */
	protected boolean cleanUpDir(File dir){
		if (dir.isDirectory()) {
			System.err.println("Cleaning up " + dir.getName());
            String[] children = dir.list();
            for (String string : children) {
				boolean success = cleanUpDir(new File(dir, string));
				if (!success) return false;
			}
        }
        // The directory is now empty so delete it
        return dir.delete();		
	}

	/*	User for testing purposes, void */
	class emptyWatcher implements Watcher{
		public void process(WatchedEvent event) {}
	}

}
