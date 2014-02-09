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

package org.apache.zookeeper.server.quorum;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.LineNumberReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.WriterAppender;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.common.AtomicFileOutputStream;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;


/**
 * Test stand-alone server.
 *
 */
public class QuorumPeerMainTest extends QuorumPeerTestBase {
    protected static final Logger LOG =
        Logger.getLogger(QuorumPeerMainTest.class);

    private Servers servers;
    private int numServers = 0;

    @After
    public void tearDown() throws Exception {
        if (servers == null || servers.mt == null) {
            LOG.info("No servers to shutdown!");
            return;
        }
        for (int i = 0; i < numServers; i++) {
            if (i < servers.mt.length) {
                servers.mt[i].shutdown();
            }
        }
    }

	/**
     * Verify the ability to start a cluster.
     */
    @Test
    public void testQuorum() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT_QP1 = PortAssignment.unique();
        final int CLIENT_PORT_QP2 = PortAssignment.unique();

        String quorumCfgSection =
            "server.1=127.0.0.1:" + PortAssignment.unique()
            + ":" + PortAssignment.unique()
            + "\nserver.2=127.0.0.1:" + PortAssignment.unique()
            + ":" + PortAssignment.unique();

        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
        q1.start();
        q2.start();

        Assert.assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                        CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 2 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                        CONNECTION_TIMEOUT));


        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP1,
                ClientBase.CONNECTION_TIMEOUT, this);
        waitForOne(zk, States.CONNECTED);
        zk.create("/foo_q1", "foobar1".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertEquals(new String(zk.getData("/foo_q1", null, null)), "foobar1");
        zk.close();

        zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP2,
                ClientBase.CONNECTION_TIMEOUT, this);
        waitForOne(zk, States.CONNECTED);
        zk.create("/foo_q2", "foobar2".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertEquals(new String(zk.getData("/foo_q2", null, null)), "foobar2");
        zk.close();

        q1.shutdown();
        q2.shutdown();

        Assert.assertTrue("waiting for server 1 down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                        ClientBase.CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 2 down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2,
                        ClientBase.CONNECTION_TIMEOUT));
    }

    /**
     * Test early leader abandonment.
     */
    @Test
    public void testEarlyLeaderAbandonment() throws Exception {
        ClientBase.setupTestEnv();

        final int SERVER_COUNT = 3;
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < SERVER_COUNT; i++) {
        	clientPorts[i] = PortAssignment.unique();
        	sb.append("server."+i+"=127.0.0.1:"+PortAssignment.unique()+":"+PortAssignment.unique()+"\n");
        }
        String quorumCfgSection = sb.toString();

        MainThread mt[] = new MainThread[SERVER_COUNT];
        ZooKeeper zk[] = new ZooKeeper[SERVER_COUNT];
        for(int i = 0; i < SERVER_COUNT; i++) {
        	mt[i] = new MainThread(i, clientPorts[i], quorumCfgSection);
        	mt[i].start();
        	zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, this);
        }
        
        waitForAll(zk, States.CONNECTED);
        
        // we need to shutdown and start back up to make sure that the create session isn't the first transaction since
        // that is rather innocuous.
        for(int i = 0; i < SERVER_COUNT; i++) {
        	mt[i].shutdown();
        }
        
        waitForAll(zk, States.CONNECTING);
        
        for(int i = 0; i < SERVER_COUNT; i++) {
        	mt[i].start();
        }
        
        waitForAll(zk, States.CONNECTED);
        
        // ok lets find the leader and kill everything else, we have a few
        // seconds, so it should be plenty of time
        int leader = -1;
        Map<Long, Proposal> outstanding = null;
        for(int i = 0; i < SERVER_COUNT; i++) {
        	if (mt[i].main.quorumPeer.leader == null) {
        		mt[i].shutdown();
        	} else {
        		leader = i;
        		outstanding = mt[leader].main.quorumPeer.leader.outstandingProposals;
        	}
        }
        
        try {
        	zk[leader].create("/zk"+leader, "zk".getBytes(), Ids.OPEN_ACL_UNSAFE,
        			CreateMode.PERSISTENT);
        	Assert.fail("create /zk" + leader + " should have failed");
        } catch(KeeperException e) {}
        
        // just make sure that we actually did get it in process at the 
        // leader
        Assert.assertTrue(outstanding.size() == 1);
        Assert.assertTrue(((Proposal)outstanding.values().iterator().next()).request.hdr.getType() == OpCode.create);
        // make sure it has a chance to write it to disk
        Thread.sleep(1000);
        mt[leader].shutdown();
        waitForAll(zk, States.CONNECTING);
        for(int i = 0; i < SERVER_COUNT; i++) {
        	if (i != leader) {
        		mt[i].start();
        	}
        }
        for(int i = 0; i < SERVER_COUNT; i++) {
        	if (i != leader) {
        		waitForOne(zk[i], States.CONNECTED);
        		zk[i].create("/zk" + i, "zk".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        	}
        }
        
        mt[leader].start();
        waitForAll(zk, States.CONNECTED);
        // make sure everything is consistent
        for(int i = 0; i < SERVER_COUNT; i++) {
        	for(int j = 0; j < SERVER_COUNT; j++) {
        		if (i == leader) {
         			Assert.assertTrue((j==leader?("Leader ("+leader+")"):("Follower "+j))+" should not have /zk" + i, zk[j].exists("/zk"+i, false) == null);
        		} else {
         			Assert.assertTrue((j==leader?("Leader ("+leader+")"):("Follower "+j))+" does not have /zk" + i, zk[j].exists("/zk"+i, false) != null);
        		}
        	}
        }
        for(int i = 0; i < SERVER_COUNT; i++) {
        	zk[i].close();
        }
        for(int i = 0; i < SERVER_COUNT; i++) {
        	mt[i].shutdown();
        }
    }
    
    /**
     * Test the case of server with highest zxid not present at leader election and joining later.
     * This test case is for reproducing the issue and fixing the bug mentioned in  ZOOKEEPER-1154
	 * and ZOOKEEPER-1156.
     */
    @Test
    public void testHighestZxidJoinLate() throws Exception {
        numServers = 3;
        servers = LaunchServers(numServers);
        String path = "/hzxidtest";
        int leader=-1;

        // find the leader
        for (int i=0; i < numServers; i++) {
            if (servers.mt[i].main.quorumPeer.leader != null) {
                leader = i;
            }
        }
        
        // make sure there is a leader
        Assert.assertTrue("There should be a leader", leader >=0);

        int nonleader = (leader+1)%numServers;

        byte[] input = new byte[1];
        input[0] = 1;
        byte[] output;

        // Create a couple of nodes
        servers.zk[leader].create(path+leader, input, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        servers.zk[leader].create(path+nonleader, input, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        
        // make sure the updates indeed committed. If it is not
        // the following statement will throw.
        output = servers.zk[leader].getData(path+nonleader, false, null);
        
        // Shutdown every one else but the leader
        for (int i=0; i < numServers; i++) {
            if (i != leader) {
                servers.mt[i].shutdown();
            }
        }

        input[0] = 2;

        // Update the node on the leader
        servers.zk[leader].setData(path+leader, input, -1, null, null);     
        
        // wait some time to let this get written to disk
        Thread.sleep(500);

        // shut the leader down
        servers.mt[leader].shutdown();

        System.gc();

        waitForAll(servers.zk, States.CONNECTING);

        // Start everyone but the leader
        for (int i=0; i < numServers; i++) {
            if (i != leader) {
                servers.mt[i].start();
            }
        }

        // wait to connect to one of these
        waitForOne(servers.zk[nonleader], States.CONNECTED);

        // validate that the old value is there and not the new one
        output = servers.zk[nonleader].getData(path+leader, false, null);

        Assert.assertEquals(
                "Expecting old value 1 since 2 isn't committed yet",
                output[0], 1);

        // Do some other update, so we bump the maxCommttedZxid
        // by setting the value to 2
        servers.zk[nonleader].setData(path+nonleader, input, -1);

        // start the old leader 
        servers.mt[leader].start();

        // connect to it
        waitForOne(servers.zk[leader], States.CONNECTED);

        // make sure it doesn't have the new value that it alone had logged
        output = servers.zk[leader].getData(path+leader, false, null);
        Assert.assertEquals(
                "Validating that the deposed leader has rolled back that change it had written",
                output[0], 1);
        
        // make sure the leader has the subsequent changes that were made while it was offline
        output = servers.zk[leader].getData(path+nonleader, false, null);
        Assert.assertEquals(
                "Validating that the deposed leader caught up on changes it missed",
                output[0], 2);
    }

    private void waitForOne(ZooKeeper zk, States state) throws InterruptedException {
    	while(zk.getState() != state) {
    		Thread.sleep(500);
    	}
    }
    
	private void waitForAll(ZooKeeper[] zks, States state) throws InterruptedException {
		int iterations = 10;
		boolean someoneNotConnected = true;
        while(someoneNotConnected) {
        	if (iterations-- == 0) {
        		ClientBase.logAllStackTraces();
			throw new RuntimeException("Waiting too long");
        	}
        	
        	someoneNotConnected = false;
        	for(ZooKeeper zk: zks) {
        		if (zk.getState() != state) {
        			someoneNotConnected = true;
        		}
        	}
        	Thread.sleep(1000);
        }
	}

    // This class holds the servers and clients for those servers
  	private class Servers {
  	    MainThread mt[];
  	    ZooKeeper zk[];
  	}
  	
  	/**
  	 * This is a helper function for launching a set of servers
  	 *  
  	 * @param numServers
  	 * @return
  	 * @throws IOException
  	 * @throws InterruptedException
  	 */
  	private Servers LaunchServers(int numServers) throws IOException, InterruptedException {
  	    int SERVER_COUNT = numServers;
  	    Servers svrs = new Servers();
  	    final int clientPorts[] = new int[SERVER_COUNT];
  	    StringBuilder sb = new StringBuilder();
  	    for(int i = 0; i < SERVER_COUNT; i++) {
  	        clientPorts[i] = PortAssignment.unique();
  	        sb.append("server."+i+"=127.0.0.1:"+PortAssignment.unique()+":"+PortAssignment.unique()+"\n");
  	    }
  	    String quorumCfgSection = sb.toString();
  
  	    MainThread mt[] = new MainThread[SERVER_COUNT];
  	    ZooKeeper zk[] = new ZooKeeper[SERVER_COUNT];
  	    for(int i = 0; i < SERVER_COUNT; i++) {
  	        mt[i] = new MainThread(i, clientPorts[i], quorumCfgSection);
  	        mt[i].start();
  	        zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, this);
  	    }
  
  	    waitForAll(zk, States.CONNECTED);
  
  	    svrs.mt = mt;
  	    svrs.zk = zk;
  	    return svrs;
  	}


    /**
     * Verify handling of bad quorum address
     */
    @Test
    public void testBadPeerAddressInQuorum() throws Exception {
        ClientBase.setupTestEnv();

        // setup the logger to capture all logs
        Layout layout =
            Logger.getRootLogger().getAppender("CONSOLE").getLayout();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setThreshold(Level.WARN);
        Logger qlogger = Logger.getLogger("org.apache.zookeeper.server.quorum");
        qlogger.addAppender(appender);

        try {
            final int CLIENT_PORT_QP1 = PortAssignment.unique();
            final int CLIENT_PORT_QP2 = PortAssignment.unique();

            String quorumCfgSection =
                "server.1=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique()
                + "\nserver.2=fee.fii.foo.fum:" + PortAssignment.unique()
                + ":" + PortAssignment.unique();

            MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
            q1.start();

            boolean isup =
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                        30000);

            Assert.assertFalse("Server never came up", isup);

            q1.shutdown();

            Assert.assertTrue("waiting for server 1 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                            ClientBase.CONNECTION_TIMEOUT));

        } finally {
            qlogger.removeAppender(appender);
        }

        LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
        String line;
        boolean found = false;
        Pattern p =
            Pattern.compile(".*Cannot open channel to .* at election address .*");
        while ((line = r.readLine()) != null) {
            found = p.matcher(line).matches();
            if (found) {
                break;
            }
        }
        Assert.assertTrue("complains about host", found);
    }

    /**
     * Verify handling of inconsistent peer type
     */
    @Test
    public void testInconsistentPeerType() throws Exception {
        ClientBase.setupTestEnv();

        // setup the logger to capture all logs
        Layout layout =
            Logger.getRootLogger().getAppender("CONSOLE").getLayout();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setThreshold(Level.INFO);
        Logger qlogger = Logger.getLogger("org.apache.zookeeper.server.quorum");
        qlogger.addAppender(appender);

        // test the most likely situation only: server is stated as observer in 
        // servers list, but there's no "peerType=observer" token in config
        try {
            final int CLIENT_PORT_QP1 = PortAssignment.unique();
            final int CLIENT_PORT_QP2 = PortAssignment.unique();
            final int CLIENT_PORT_QP3 = PortAssignment.unique();

            String quorumCfgSection =
                "server.1=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique()
                + "\nserver.2=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique()
                + "\nserver.3=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique() + ":observer";

            MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
            MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
            MainThread q3 = new MainThread(3, CLIENT_PORT_QP3, quorumCfgSection);
            q1.start();
            q2.start();
            q3.start();

            Assert.assertTrue("waiting for server 1 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                            CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 2 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                            CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 3 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP3,
                            CONNECTION_TIMEOUT));

            q1.shutdown();
            q2.shutdown();
            q3.shutdown();

            Assert.assertTrue("waiting for server 1 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                            ClientBase.CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 2 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2,
                            ClientBase.CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 3 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP3,
                            ClientBase.CONNECTION_TIMEOUT));

        } finally {
            qlogger.removeAppender(appender);
        }

        LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
        String line;
        boolean warningPresent = false;
        boolean defaultedToObserver = false;
        Pattern pWarn =
            Pattern.compile(".*Peer type from servers list.* doesn't match peerType.*");
        Pattern pObserve = Pattern.compile(".*OBSERVING.*");
        while ((line = r.readLine()) != null) {
            if (pWarn.matcher(line).matches()) { 
                warningPresent = true;
            }
            if (pObserve.matcher(line).matches()) {
                defaultedToObserver = true;
            }
            if (warningPresent && defaultedToObserver) {
                break;
            }
        }
        Assert.assertTrue("Should warn about inconsistent peer type", 
                warningPresent && defaultedToObserver);
    }

    /**
     * verify if bad packets are being handled properly 
     * at the quorum port
     * @throws Exception
     */
    @Test
    public void testBadPackets() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT_QP1 = PortAssignment.unique();
        final int CLIENT_PORT_QP2 = PortAssignment.unique();
        int electionPort1 = PortAssignment.unique();
        int electionPort2 = PortAssignment.unique();
        String quorumCfgSection =
            "server.1=127.0.0.1:" + PortAssignment.unique()
            + ":" + electionPort1
            + "\nserver.2=127.0.0.1:" + PortAssignment.unique()
            + ":" +  electionPort2;
        
        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
        q1.start();
        q2.start();
        
        Assert.assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                        CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 2 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                            CONNECTION_TIMEOUT));
            
        byte[] b = new byte[4];
        int length = 1024*1024*1024;
        ByteBuffer buff = ByteBuffer.wrap(b);
        buff.putInt(length);
        buff.position(0);
        SocketChannel s = SocketChannel.open(new InetSocketAddress("127.0.0.1", electionPort1));
        s.write(buff);
        s.close();
        buff.position(0);
        s = SocketChannel.open(new InetSocketAddress("127.0.0.1", electionPort2));
        s.write(buff);
        s.close();
        
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP1,
                ClientBase.CONNECTION_TIMEOUT, this);
        waitForOne(zk, States.CONNECTED);
        zk.create("/foo_q1", "foobar1".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertEquals(new String(zk.getData("/foo_q1", null, null)), "foobar1");
        zk.close();
        q1.shutdown();
        q2.shutdown();
    }


    /**
     * Verify handling of quorum defaults
     * * default electionAlg is fast leader election
     */
    @Test
    public void testQuorumDefaults() throws Exception {
        ClientBase.setupTestEnv();

        // setup the logger to capture all logs
        Layout layout =
            Logger.getRootLogger().getAppender("CONSOLE").getLayout();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setImmediateFlush(true);
        appender.setThreshold(Level.INFO);
        Logger zlogger = Logger.getLogger("org.apache.zookeeper");
        zlogger.addAppender(appender);

        try {
            final int CLIENT_PORT_QP1 = PortAssignment.unique();
            final int CLIENT_PORT_QP2 = PortAssignment.unique();

            String quorumCfgSection =
                "server.1=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique()
                + "\nserver.2=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique();

            MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
            MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
            q1.start();
            q2.start();

            Assert.assertTrue("waiting for server 1 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                            CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 2 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                            CONNECTION_TIMEOUT));

            q1.shutdown();
            q2.shutdown();

            Assert.assertTrue("waiting for server 1 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                            ClientBase.CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 2 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2,
                            ClientBase.CONNECTION_TIMEOUT));

        } finally {
            zlogger.removeAppender(appender);
        }
        os.close();
        LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
        String line;
        boolean found = false;
        Pattern p =
            Pattern.compile(".*FastLeaderElection.*");
        while ((line = r.readLine()) != null) {
            found = p.matcher(line).matches();
            if (found) {
                break;
            }
        }
        Assert.assertTrue("fastleaderelection used", found);
    }

    /**
     * Verifies that QuorumPeer exits immediately
     */
    @Test
    public void testQuorumPeerExitTime() throws Exception {
        long maxwait = 3000;
        final int CLIENT_PORT_QP1 = PortAssignment.unique();
        String quorumCfgSection =
            "server.1=127.0.0.1:" + PortAssignment.unique()
            + ":" + PortAssignment.unique()
            + "\nserver.2=127.0.0.1:" + PortAssignment.unique()
            + ":" + PortAssignment.unique();
        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        q1.start();
        // Let the notifications timeout
        Thread.sleep(30000);
        long start = System.currentTimeMillis();
        q1.shutdown();
        long end = System.currentTimeMillis();
        if ((end - start) > maxwait) {
           Assert.fail("QuorumPeer took " + (end -start) +
                    " to shutdown, expected " + maxwait);
        }
    }

    static long readLongFromFile(File file) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(file));
        String line = "";
        try {
            line = br.readLine();
            return Long.parseLong(line);
        } catch(NumberFormatException e) {
            throw new IOException("Found " + line + " in " + file);
        } finally {
            br.close();
        }
    }

    static void writeLongToFile(File file, long value) throws IOException {
        AtomicFileOutputStream out = new AtomicFileOutputStream(file);
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(out));
        try {
            bw.write(Long.toString(value));
            bw.flush();
            out.flush();
            out.close();
        } catch (IOException e) {
            LOG.error("Failed to write new file " + file, e);
            out.abort();
            throw e;
        }
    }

    /**
     * ZOOKEEPER-1653 Make sure the server starts if the current epoch is less
     * than the epoch from last logged zxid and updatingEpoch file exists.
     */
    @Test
    public void testUpdatingEpoch() throws Exception {
        // Create a cluster and restart them multiple times to bump the epoch.
        numServers = 3;
        servers = LaunchServers(numServers);
        File currentEpochFile;
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < numServers; j++) {
                servers.mt[j].shutdown();
            }
            waitForAll(servers.zk, States.CONNECTING);
            for (int j = 0; j < numServers; j++) {
                servers.mt[j].start();
            }
            waitForAll(servers.zk, States.CONNECTED);
        }

        // Current epoch is 11 now.
        for (int i = 0; i < numServers; i++) {
            currentEpochFile = new File(
                new File(servers.mt[i].dataDir, "version-2"),
                QuorumPeer.CURRENT_EPOCH_FILENAME);
            LOG.info("Validating current epoch: " + servers.mt[i].dataDir);
            Assert.assertEquals("Current epoch should be 11.", 11,
                                readLongFromFile(currentEpochFile));
        }

        // Find a follower and get epoch from the last logged zxid.
        int followerIndex = -1;
        for (int i = 0; i < numServers; i++) {
            if (servers.mt[i].main.quorumPeer.leader == null) {
                followerIndex = i;
                break;
            }
        }
        Assert.assertTrue("Found a valid follower",
                          followerIndex >= 0 && followerIndex < numServers);
        MainThread follower = servers.mt[followerIndex];
        long zxid = follower.main.quorumPeer.getLastLoggedZxid();
        long epochFromZxid = ZxidUtils.getEpochFromZxid(zxid);

        // Shutdown the cluster
        for (int i = 0; i < numServers; i++) {
          servers.mt[i].shutdown();
        }
        waitForAll(servers.zk, States.CONNECTING);

        // Make current epoch less than epoch from the last logged zxid.
        // The server should fail to start.
        File followerDataDir = new File(follower.dataDir, "version-2");
        currentEpochFile = new File(followerDataDir,
                QuorumPeer.CURRENT_EPOCH_FILENAME);
        writeLongToFile(currentEpochFile, epochFromZxid - 1);
        follower.start();
        Assert.assertTrue(follower.mainFailed.await(10, TimeUnit.SECONDS));

        // Touch the updateEpoch file. Now the server should start.
        File updatingEpochFile = new File(followerDataDir,
                QuorumPeer.UPDATING_EPOCH_FILENAME);
        updatingEpochFile.createNewFile();
        for (int i = 0; i < numServers; i++) {
          servers.mt[i].start();
        }
        waitForAll(servers.zk, States.CONNECTED);
        Assert.assertNotNull("Make sure the server started with acceptEpoch",
                             follower.main.quorumPeer.getActiveServer());
        Assert.assertFalse("updatingEpoch file should get deleted",
                           updatingEpochFile.exists());
    }
}
