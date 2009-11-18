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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.regex.Pattern;

import junit.framework.TestCase;

import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.WriterAppender;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientTest;
import org.junit.Test;


/**
 * Test stand-alone server.
 *
 */
public class QuorumPeerMainTest extends TestCase implements Watcher {
    protected static final Logger LOG =
        Logger.getLogger(QuorumPeerMainTest.class);

    public static class MainThread extends Thread {
        final File confFile;
        final TestQPMain main;

        public MainThread(int myid, int clientPort, String quorumCfgSection)
            throws IOException
        {
            super("QuorumPeer with myid:" + myid
                    + " and clientPort:" + clientPort);
            File tmpDir = ClientBase.createTmpDir();
            confFile = new File(tmpDir, "zoo.cfg");

            FileWriter fwriter = new FileWriter(confFile);
            fwriter.write("tickTime=2000\n");
            fwriter.write("initLimit=10\n");
            fwriter.write("syncLimit=5\n");

            File dataDir = new File(tmpDir, "data");
            if (!dataDir.mkdir()) {
                throw new IOException("Unable to mkdir " + dataDir);
            }
            fwriter.write("dataDir=" + dataDir.toString() + "\n");

            fwriter.write("clientPort=" + clientPort + "\n");
            fwriter.write(quorumCfgSection + "\n");
            fwriter.flush();
            fwriter.close();

            File myidFile = new File(dataDir, "myid");
            fwriter = new FileWriter(myidFile);
            fwriter.write(Integer.toString(myid));
            fwriter.flush();
            fwriter.close();

            main = new TestQPMain();
        }

        public void run() {
            String args[] = new String[1];
            args[0] = confFile.toString();
            try {
                main.initializeAndRun(args);
            } catch (Exception e) {
                // test will still fail even though we just log/ignore
                LOG.error("unexpected exception in run", e);
            }
        }

        public void shutdown() {
            main.shutdown();
        }
    }

    public static  class TestQPMain extends QuorumPeerMain {
        public void shutdown() {
            super.shutdown();
        }
    }

    /**
     * Verify the ability to start a cluster.
     */
    @Test
    public void testQuorum() throws Exception {
        LOG.info("STARTING " + getName());
        ClientBase.setupTestEnv();

        final int CLIENT_PORT_QP1 = 3181;
        final int CLIENT_PORT_QP2 = CLIENT_PORT_QP1 + 3;

        String quorumCfgSection =
            "server.1=localhost:" + (CLIENT_PORT_QP1 + 1)
            + ":" + (CLIENT_PORT_QP1 + 2)
            + "\nserver.2=localhost:" + (CLIENT_PORT_QP2 + 1)
            + ":" + (CLIENT_PORT_QP2 + 2);

        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
        q1.start();
        q2.start();

        assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("localhost:" + CLIENT_PORT_QP1,
                        CONNECTION_TIMEOUT));
        assertTrue("waiting for server 2 being up",
                ClientBase.waitForServerUp("localhost:" + CLIENT_PORT_QP2,
                        CONNECTION_TIMEOUT));


        ZooKeeper zk = new ZooKeeper("localhost:" + CLIENT_PORT_QP1,
                ClientBase.CONNECTION_TIMEOUT, this);

        zk.create("/foo_q1", "foobar1".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        assertEquals(new String(zk.getData("/foo_q1", null, null)), "foobar1");
        zk.close();

        zk = new ZooKeeper("localhost:" + CLIENT_PORT_QP2,
                ClientBase.CONNECTION_TIMEOUT, this);

        zk.create("/foo_q2", "foobar2".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        assertEquals(new String(zk.getData("/foo_q2", null, null)), "foobar2");
        zk.close();

        q1.shutdown();
        q2.shutdown();

        assertTrue("waiting for server 1 down",
                ClientBase.waitForServerDown("localhost:" + CLIENT_PORT_QP1,
                        ClientBase.CONNECTION_TIMEOUT));
        assertTrue("waiting for server 2 down",
                ClientBase.waitForServerDown("localhost:" + CLIENT_PORT_QP2,
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
            final int CLIENT_PORT_QP1 = 3191;
            final int CLIENT_PORT_QP2 = CLIENT_PORT_QP1 + 3;

            String quorumCfgSection =
                "server.1=localhost:" + (CLIENT_PORT_QP1 + 1)
                + ":" + (CLIENT_PORT_QP1 + 2)
                + "\nserver.2=fee.fii.foo.fum:" + (CLIENT_PORT_QP2 + 1)
                + ":" + (CLIENT_PORT_QP2 + 2);

            MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
            q1.start();

            boolean isup =
                ClientBase.waitForServerUp("localhost:" + CLIENT_PORT_QP1,
                        5000);

            assertFalse("Server never came up", isup);

            q1.shutdown();

            assertTrue("waiting for server 1 down",
                    ClientBase.waitForServerDown("localhost:" + CLIENT_PORT_QP1,
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
            "server.1=localhost:" + PortAssignment.unique()
            + ":" + electionPort1
            + "\nserver.2=localhost:" + PortAssignment.unique()
            + ":" +  electionPort2;
        
        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
        q1.start();
        q2.start();
        
        assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("localhost:" + CLIENT_PORT_QP1,
                        CONNECTION_TIMEOUT));
        assertTrue("waiting for server 2 being up",
                    ClientBase.waitForServerUp("localhost:" + CLIENT_PORT_QP2,
                            CONNECTION_TIMEOUT));
            
        byte[] b = new byte[4];
        int length = 1024*1024*1024;
        ByteBuffer buff = ByteBuffer.wrap(b);
        buff.putInt(length);
        buff.position(0);
        SocketChannel s = SocketChannel.open(new InetSocketAddress("localhost", electionPort1));
        s.write(buff);
        s.close();
        buff.position(0);
        s = SocketChannel.open(new InetSocketAddress("localhost", electionPort2));
        s.write(buff);
        s.close();
        
        ZooKeeper zk = new ZooKeeper("localhost:" + CLIENT_PORT_QP1,
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
            final int CLIENT_PORT_QP1 = 3171;
            final int CLIENT_PORT_QP2 = CLIENT_PORT_QP1 + 3;

            String quorumCfgSection =
                "server.1=localhost:" + (CLIENT_PORT_QP1 + 1)
                + ":" + (CLIENT_PORT_QP1 + 2)
                + "\nserver.2=localhost:" + (CLIENT_PORT_QP2 + 1)
                + ":" + (CLIENT_PORT_QP2 + 2);

            MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
            MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
            q1.start();
            q2.start();

            assertTrue("waiting for server 1 being up",
                    ClientBase.waitForServerUp("localhost:" + CLIENT_PORT_QP1,
                            CONNECTION_TIMEOUT));
            assertTrue("waiting for server 2 being up",
                    ClientBase.waitForServerUp("localhost:" + CLIENT_PORT_QP2,
                            CONNECTION_TIMEOUT));

            q1.shutdown();
            q2.shutdown();

            assertTrue("waiting for server 1 down",
                    ClientBase.waitForServerDown("localhost:" + CLIENT_PORT_QP1,
                            ClientBase.CONNECTION_TIMEOUT));
            assertTrue("waiting for server 2 down",
                    ClientBase.waitForServerDown("localhost:" + CLIENT_PORT_QP2,
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

    public void process(WatchedEvent event) {
        // ignore for this test
    }
}
