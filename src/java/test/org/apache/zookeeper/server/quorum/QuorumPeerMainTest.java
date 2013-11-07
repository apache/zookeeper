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
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.regex.Pattern;

import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.WriterAppender;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase.MainThread;
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
        LOG.info("STARTING " + getName());
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

        assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                        CONNECTION_TIMEOUT));
        assertTrue("waiting for server 2 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                        CONNECTION_TIMEOUT));


        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP1,
                ClientBase.CONNECTION_TIMEOUT, this);

        zk.create("/foo_q1", "foobar1".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        assertEquals(new String(zk.getData("/foo_q1", null, null)), "foobar1");
        zk.close();

        zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP2,
                ClientBase.CONNECTION_TIMEOUT, this);

        zk.create("/foo_q2", "foobar2".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        assertEquals(new String(zk.getData("/foo_q2", null, null)), "foobar2");
        zk.close();

        q1.shutdown();
        q2.shutdown();

        assertTrue("waiting for server 1 down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                        ClientBase.CONNECTION_TIMEOUT));
        assertTrue("waiting for server 2 down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2,
                        ClientBase.CONNECTION_TIMEOUT));
    }

    /**
     * Verify handling of bad quorum address
     */
    @Test
    public void testBadPeerAddressInQuorum() throws Exception {
        LOG.info("STARTING " + getName());
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

            assertFalse("Server never came up", isup);

            q1.shutdown();

            assertTrue("waiting for server 1 down",
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
        assertTrue("complains about host", found);
    }

    /**
     * verify if bad packets are being handled properly 
     * at the quorum port
     * @throws Exception
     */
    public void testBadPackets() throws Exception {
        LOG.info("STARTING " + getName());
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
        
        assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                        CONNECTION_TIMEOUT));
        assertTrue("waiting for server 2 being up",
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
        assertEquals(new String(zk.getData("/foo_q1", null, null)), "foobar1");
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
        LOG.info("STARTING " + getName());
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

            assertTrue("waiting for server 1 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                            CONNECTION_TIMEOUT));
            assertTrue("waiting for server 2 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                            CONNECTION_TIMEOUT));

            q1.shutdown();
            q2.shutdown();

            assertTrue("waiting for server 1 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                            ClientBase.CONNECTION_TIMEOUT));
            assertTrue("waiting for server 2 down",
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
        assertTrue("fastleaderelection used", found);
    }
   
    /**
     * Test the case of server with highest zxid not present at leader election and joining later.
     * This test case is for reproducing the issue and fixing the bug mentioned in  ZOOKEEPER-1154
     * and ZOOKEEPER-1156.
     */
    @Test
    public void testHighestZxidJoinLate() throws Exception {
        int numServers = 3;
        Servers svrs = LaunchServers(numServers);
        String path = "/hzxidtest";
        int leader=-1;

        // find the leader
        for (int i=0; i < numServers; i++) {
            if (svrs.mt[i].main.quorumPeer.leader != null) {
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
        svrs.zk[leader].create(path+leader, input, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        svrs.zk[leader].create(path+nonleader, input, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        
        // make sure the updates indeed committed. If it is not
        // the following statement will throw.
        output = svrs.zk[leader].getData(path+nonleader, false, null);
        
        // Shutdown every one else but the leader
        for (int i=0; i < numServers; i++) {
            if (i != leader) {
                svrs.mt[i].shutdown();
            }
        }

        input[0] = 2;

        // Update the node on the leader
        svrs.zk[leader].setData(path+leader, input, -1, null, null);     
        
        // wait some time to let this get written to disk
        Thread.sleep(500);

        // shut the leader down
        svrs.mt[leader].shutdown();

        System.gc();

        waitForAll(svrs.zk, States.CONNECTING);

        // Start everyone but the leader
        for (int i=0; i < numServers; i++) {
            if (i != leader) {
                svrs.mt[i].start();
            }
        }

        // wait to connect to one of these
        waitForOne(svrs.zk[nonleader], States.CONNECTED);

        // validate that the old value is there and not the new one
        output = svrs.zk[nonleader].getData(path+leader, false, null);

        Assert.assertEquals(
                "Expecting old value 1 since 2 isn't committed yet",
                output[0], 1);

        // Do some other update, so we bump the maxCommttedZxid
        // by setting the value to 2
        svrs.zk[nonleader].setData(path+nonleader, input, -1);

        // start the old leader 
        svrs.mt[leader].start();

        // connect to it
        waitForOne(svrs.zk[leader], States.CONNECTED);

        // make sure it doesn't have the new value that it alone had logged
        output = svrs.zk[leader].getData(path+leader, false, null);
        Assert.assertEquals(
                "Validating that the deposed leader has rolled back that change it had written",
                output[0], 1);
        
        // make sure the leader has the subsequent changes that were made while it was offline
        output = svrs.zk[leader].getData(path+nonleader, false, null);
        Assert.assertEquals(
                "Validating that the deposed leader caught up on changes it missed",
                output[0], 2);
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
}
