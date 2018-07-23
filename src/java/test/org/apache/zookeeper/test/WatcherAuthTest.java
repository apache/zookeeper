package org.apache.zookeeper.test;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

import static org.apache.zookeeper.test.ClientBase.createTmpDir;

public class WatcherAuthTest {

    protected static final Logger LOG = LoggerFactory.getLogger(WatcherTest.class);

    private class MyWatcher extends ClientBase.CountdownWatcher {
        LinkedBlockingQueue<WatchedEvent> events =
                new LinkedBlockingQueue<WatchedEvent>();

        @Override
        public void process(WatchedEvent event) {
            super.process(event);
            if (event.getState() == Event.KeeperState.AuthFailed) {
                try {
                    events.put(event);
                } catch (InterruptedException e) {
                    LOG.warn("ignoring interrupt during event.put");
                }
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        // Reset to default value since some test cases set this to true.
        // Needed for JDK7 since unit test can run is random order
        System.setProperty(ZKClientConfig.DISABLE_AUTO_WATCH_RESET, "false");
    }

    // Note: This test only works with a real ZKServer, running with TestServer masks the bug
    @Ignore
    @Test
    public void testWatcherCanGetAuthFailedEvents() throws Exception {
        MyWatcher myWatcher = new MyWatcher();
        System.setProperty("zookeeper.authProvider.1","org.apache.zookeeper.server.auth.SASLAuthenticationProvider");
        try {
            File tmpDir = createTmpDir();
            File saslConfFile = new File(tmpDir, "jaas.conf");
            FileWriter fwriter = new FileWriter(saslConfFile);

            fwriter.write("" +
                    "Server {\n" +
                    "          org.apache.zookeeper.server.auth.DigestLoginModule required\n" +
                    "          user_super=\"test\";\n" +
                    "};\n" +
                    "Client {\n" +
                    "       org.apache.zookeeper.server.auth.DigestLoginModule required\n" +
                    "       username=\"super\"\n" +
                    "       password=\"test1\";\n" + // NOTE: wrong password ('test' != 'test1') : this is to test SASL authentication failure.
                    "};" + "\n");
            fwriter.close();
            System.setProperty("java.security.auth.login.config",saslConfFile.getAbsolutePath());
        }
        catch (IOException e) {
            // could not create tmp directory to hold JAAS conf file.
        }

        // Specify your ZK Server endpoints here
        ZooKeeper zk = new ZooKeeper("127.0.0.1:2281", 20000, myWatcher);

        while (myWatcher.events.size() == 0) {
            Thread.sleep(100);
        }
        Assert.assertEquals(1, myWatcher.events.size());
    }
}
