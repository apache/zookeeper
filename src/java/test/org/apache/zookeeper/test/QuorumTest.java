package org.apache.zookeeper.test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;

import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Before;

import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumStats;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;

public class QuorumTest extends ClientTest {
    private static final Logger LOG = Logger.getLogger(QuorumTest.class);

    static File baseTest = new File(System.getProperty("build.test.dir", "build"));
    File s1dir, s2dir, s3dir, s4dir, s5dir;
    QuorumPeer s1, s2, s3, s4, s5;
    @Before
    protected void setUp() throws Exception {
        s1dir = File.createTempFile("test", ".junit", baseTest);
        s1dir = new File(s1dir + ".dir");
        s1dir.mkdirs();
        s2dir = File.createTempFile("test", ".junit", baseTest);
        s2dir = new File(s2dir + ".dir");
        s2dir.mkdirs();
        s3dir = File.createTempFile("test", ".junit", baseTest);
        s3dir = new File(s3dir + ".dir");
        s3dir.mkdirs();
        s4dir = File.createTempFile("test", ".junit", baseTest);
        s4dir = new File(s4dir + ".dir");
        s4dir.mkdirs();
        s5dir = File.createTempFile("test", ".junit", baseTest);
        s5dir = new File(s5dir + ".dir");
        s5dir.mkdirs();
        startServers();
        LOG.warn("Setup finished");
    }
    void startServers() throws IOException, InterruptedException {
		QuorumStats.registerAsConcrete();
        int tickTime = 2000;
        int initLimit = 3;
        int syncLimit = 3;
        ArrayList<QuorumServer> peers = new ArrayList<QuorumServer>();
        hostPort = "127.0.0.1:2181,127.0.0.1:2182,127.0.0.1:2183,127.0.0.1:2184,127.0.0.1:2185";
        peers.add(new QuorumServer(1, new InetSocketAddress("127.0.0.1", 3181)));
        peers.add(new QuorumServer(2, new InetSocketAddress("127.0.0.1", 3182)));
        peers.add(new QuorumServer(3, new InetSocketAddress("127.0.0.1", 3183)));
        peers.add(new QuorumServer(4, new InetSocketAddress("127.0.0.1", 3184)));
        peers.add(new QuorumServer(5, new InetSocketAddress("127.0.0.1", 3185)));
        LOG.warn("creating QuorumPeer 1");
        s1 = new QuorumPeer(peers, s1dir, s1dir, 2181, 0,  1181, 1, tickTime, initLimit, syncLimit);
        LOG.warn("creating QuorumPeer 2");
        s2 = new QuorumPeer(peers, s2dir, s2dir, 2182, 0, 1182, 2, tickTime, initLimit, syncLimit);
        LOG.warn("creating QuorumPeer 3");
        s3 = new QuorumPeer(peers, s3dir, s3dir, 2183, 0, 1183, 3, tickTime, initLimit, syncLimit);
        LOG.warn("creating QuorumPeer 4");
        s4 = new QuorumPeer(peers, s4dir, s4dir, 2184, 0, 1184, 4, tickTime, initLimit, syncLimit);
        LOG.warn("creating QuorumPeer 5");
        s5 = new QuorumPeer(peers, s5dir, s5dir, 2185, 0, 1185, 5, tickTime, initLimit, syncLimit);
        LOG.warn("start QuorumPeer 1");
        s1.start();
        LOG.warn("start QuorumPeer 2");
        s2.start();
        LOG.warn("start QuorumPeer 3");
        s3.start();
        LOG.warn("start QuorumPeer 4");
        s4.start();
        LOG.warn("start QuorumPeer 5");
        s5.start();
        LOG.warn("started QuorumPeer 5");
        Thread.sleep(5000);
    }
    @After
    protected void tearDown() throws Exception {
        LOG.warn("TearDown started");
        s1.shutdown();
        s2.shutdown();
        s3.shutdown();
        s4.shutdown();
        s5.shutdown();
        Thread.sleep(5000);
		QuorumStats.unregister();
    }
}
