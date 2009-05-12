package org.apache.bookkeeper.util;

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.IOException;

import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.LedgerSequence;
import org.apache.bookkeeper.proto.BookieServer;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZooKeeperServer;

import org.apache.log4j.Logger;

public class LocalBookKeeper {
    Logger LOG = Logger.getLogger(LocalBookKeeper.class);
	ConsoleAppender ca;
	int numberOfBookies;
	
	public LocalBookKeeper() {
		LOG = Logger.getRootLogger();
		ca = new ConsoleAppender(new PatternLayout());
		LOG.addAppender(ca);
		LOG.setLevel(Level.INFO);
		numberOfBookies = 3;
	}
	
	public LocalBookKeeper(int numberOfBookies){
		this();
		this.numberOfBookies = numberOfBookies;
		LOG.info("Running " + this.numberOfBookies + " bookie(s).");
	}
	
	private final String HOSTPORT = "127.0.0.1:2181";
	NIOServerCnxn.Factory serverFactory;
	ZooKeeperServer zks;
	ZooKeeper zkc;
	int ZooKeeperDefaultPort = 2181;
	File ZkTmpDir;

	//BookKeeper variables
	File tmpDirs[];
	BookieServer bs[];
	Integer initialPort = 5000;

	/**
	 * @param args
	 */
	
	private void runZookeeper() throws IOException{
		// create a ZooKeeper server(dataDir, dataLogDir, port)
		LOG.info("Starting ZK server");
		//ServerStats.registerAsConcrete();
		//ClientBase.setupTestEnv();
		ZkTmpDir = File.createTempFile("zookeeper", "test");
        ZkTmpDir.delete();
        ZkTmpDir.mkdir();
		    
		try {
			zks = new ZooKeeperServer(ZkTmpDir, ZkTmpDir, ZooKeeperDefaultPort);
			serverFactory =  new NIOServerCnxn.Factory(ZooKeeperDefaultPort);
			serverFactory.startup(zks);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			LOG.fatal("Exception while instantiating ZooKeeper", e);
		} 
		
        boolean b = ClientBase.waitForServerUp(HOSTPORT, ClientBase.CONNECTION_TIMEOUT);
        LOG.debug("ZooKeeper server up: " + b);
	}
	
	private void initializeZookeper(){
		LOG.info("Instantiate ZK Client");
		//initialize the zk client with values
		try {
			zkc = new ZooKeeper("127.0.0.1", ZooKeeperDefaultPort, new emptyWatcher());
			zkc.create("/ledgers", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			zkc.create("/ledgers/available", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			// create an entry for each requested bookie
			for(int i = 0; i < numberOfBookies; i++){
				zkc.create("/ledgers/available/127.0.0.1:" + 
					Integer.toString(initialPort + i), new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			}
		} catch (KeeperException e) {
			// TODO Auto-generated catch block
			LOG.fatal("Exception while creating znodes", e);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			LOG.fatal("Interrupted while creating znodes", e);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			LOG.fatal("Exception while creating znodes", e);
		}		
	}
	private void runBookies() throws IOException{
		LOG.info("Starting Bookie(s)");
		// Create Bookie Servers (B1, B2, B3)
		
		tmpDirs = new File[numberOfBookies];		
		bs = new BookieServer[numberOfBookies];
		
		for(int i = 0; i < numberOfBookies; i++){
			tmpDirs[i] = File.createTempFile("bookie" + Integer.toString(i), "test");
			tmpDirs[i].delete();
			tmpDirs[i].mkdir();
			
			bs[i] = new BookieServer(initialPort + i, tmpDirs[i], new File[]{tmpDirs[i]});
			bs[i].start();
		}		
	}
	
	public static void main(String[] args) throws IOException, InterruptedException {
		if(args.length < 1){
			usage();
			System.exit(-1);
		}
		LocalBookKeeper lb = new LocalBookKeeper(Integer.parseInt(args[0]));
		lb.runZookeeper();
		lb.initializeZookeper();
		lb.runBookies();
		while (true){
			Thread.sleep(5000);
		}
	}

	private static void usage() {
		System.err.println("Usage: LocalBookKeeper number-of-bookies");	
	}

	/*	User for testing purposes, void */
	class emptyWatcher implements Watcher{
		public void process(WatchedEvent event) {}
	}

}
