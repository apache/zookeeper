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

import java.io.ByteArrayOutputStream;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.WriterAppender;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;


/**
 * Test stand-alone server.
 *
 */
public class QuorumPeerMainTest extends QuorumPeerTestBase {
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

        zk.create("/foo_q1", "foobar1".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertEquals(new String(zk.getData("/foo_q1", null, null)), "foobar1");
        zk.close();

        zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP2,
                ClientBase.CONNECTION_TIMEOUT, this);

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
                        5000);

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
}
