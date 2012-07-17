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

package org.apache.zookeeper.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.client.FourLetterWordMain;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase.MainThread;
import org.apache.zookeeper.server.util.PortForwarder;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verify ZOOKEEPER-1489 - cause truncation followed by continued append to the
 * snaplog, verify that the newly appended information (after the truncation) is
 * readable.
 */
public class TruncateCorruptionTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory
            .getLogger(TruncateCorruptionTest.class);

    public interface Check {
        boolean doCheck();
    }

    public static boolean await(Check check, long timeoutMillis)
            throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (end > System.currentTimeMillis()) {
            if (check.doCheck()) {
                LOG.debug("await succeeded after "
                        + (System.currentTimeMillis() - end + timeoutMillis));
                return true;
            }
            Thread.sleep(50);
        }
        LOG.debug("await failed in {}", timeoutMillis);
        return false;
    }

    @Test
    public void testTransactionLogCorruption() throws Exception {
        // configure the ports for that test in a way so that we can disrupt the
        // connection for wrapper1
        ZookeeperServerWrapper wrapper1 = new ZookeeperServerWrapper(1, 7000);
        ZookeeperServerWrapper wrapper2 = new ZookeeperServerWrapper(2, 8000);
        ZookeeperServerWrapper wrapper3 = new ZookeeperServerWrapper(3, 8000);

        wrapper2.start();
        wrapper3.start();

        try {
            wrapper2.await(ClientBase.CONNECTION_TIMEOUT);
            wrapper3.await(ClientBase.CONNECTION_TIMEOUT);
        } catch (Exception e) {
            ClientBase.logAllStackTraces();
            throw e;
        }
        List<PortForwarder> pfs = startForwarding();
        Thread.sleep(1000);
        wrapper1.start();
        wrapper1.await(ClientBase.CONNECTION_TIMEOUT);

        final ZooKeeper zk1 = new ZooKeeper("localhost:8201",
                ClientBase.CONNECTION_TIMEOUT, new ZkWatcher("zk1"));
        waitForConnection(zk1);
        zk1.create("/test", "testdata".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        // wait a little until stuff is synced in between servers
        Thread.sleep(1000);
        wrapper2.stop();
        // wait for reconnect
        waitForConnection(zk1);
        zk1.create("/test2", "testdata".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        // now we stop them to force a situation where a TRUNC event is sent to
        // the followers
        wrapper3.stop();
        // simulate a short interruption in network in between

        stopForwarding(pfs);
        LOG.info("interrupted network connection ... waiting for zk1 and zk2 to realize");

        Assert.assertTrue(await(new Check() {

            public boolean doCheck() {
                if (zk1.getState() == States.CONNECTING) {
                    List<String> children;
                    try {
                        children = zk1.getChildren("/", false);

                        return children.size() != 0;
                    } catch (KeeperException.ConnectionLossException e) {
                        // just to be sure
                        return true;
                    } catch (Exception e) {
                        // silently fail
                    }
                }
                return false;
            }
        }, TimeUnit.MINUTES.toMillis(2)));

        // let's clean the data dir of zk3 so that an ensemble of 2 and 3 is
        // less advanced than 1 (just to force an event where we get a TRUNCATE
        // message)
        wrapper3.clean();
        wrapper2.start();
        wrapper3.start();
        LOG.info("Waiting for zk2 and zk3 to form a quorum");

        wrapper2.await(ClientBase.CONNECTION_TIMEOUT);
        wrapper3.await(ClientBase.CONNECTION_TIMEOUT);
        ZooKeeper zk2 = new ZooKeeper("localhost:8202",
                ClientBase.CONNECTION_TIMEOUT, new ZkWatcher("zk2"));
        waitForConnection(zk2);

        LOG.info("re-establishing network connection and waiting for zk1 to reconnect");
        pfs = startForwarding();
        waitForConnection(zk1);

        // create more data ...
        LOG.info("Creating node test3");
        zk1.create("/test3", "testdata".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Thread.sleep(250);
        LOG.info("List of children at zk2 before zk1 became master");
        List<String> children2 = zk2.getChildren("/", false);
        LOG.info(children2.toString());

        LOG.info("List of children at zk1 before zk1 became master");
        List<String> children1 = zk1.getChildren("/", false);
        LOG.info(children1.toString());

        // now cause zk1 to become master and test3 will be lost
        LOG.info("restarting zk2 and zk3 while cleaning zk3 to enforce zk1 to become master");
        wrapper2.stop();
        wrapper3.stop();
        wrapper3.clean();
        wrapper3.start();
        wrapper3.await(TimeUnit.MINUTES.toMillis(2));
        ZooKeeper zk3 = new ZooKeeper("localhost:8203",
                ClientBase.CONNECTION_TIMEOUT, new ZkWatcher("zk3"));
        waitForConnection(zk3);
        LOG.info("Zk1 and zk3 have a quorum, now starting zk2");
        wrapper2.start();
        waitForConnection(zk2);
        LOG.info("List of children at zk2");
        children2 = zk2.getChildren("/", false);
        LOG.info(children2.toString());

        waitForConnection(zk1);
        LOG.info("List of children at zk1");
        children1 = zk1.getChildren("/", false);
        Assert.assertTrue("test3 node is missing on zk1",
                children1.contains("test3"));
        Assert.assertTrue("test3 node is missing on zk2",
                children2.contains("test3"));
        Assert.assertEquals(children1, children2);
        stopForwarding(pfs);
    }

    /**
     * @param pfs
     * @throws Exception
     */
    private void stopForwarding(List<PortForwarder> pfs) throws Exception {
        for (PortForwarder pf : pfs) {
            pf.shutdown();
        }
    }

    /**
     * @return
     * @throws IOException
     */
    private List<PortForwarder> startForwarding() throws IOException {
        List<PortForwarder> res = new ArrayList<PortForwarder>();
        res.add(new PortForwarder(8301, 7301));
        res.add(new PortForwarder(8401, 7401));
        res.add(new PortForwarder(7302, 8302));
        res.add(new PortForwarder(7402, 8402));
        res.add(new PortForwarder(7303, 8303));
        res.add(new PortForwarder(7403, 8403));
        return res;
    }

    /**
     * @param zk
     * @throws InterruptedException
     */
    private void waitForConnection(final ZooKeeper zk)
            throws InterruptedException {
        Assert.assertTrue(await(new Check() {

            public boolean doCheck() {
                if (zk.getState() == States.CONNECTED) {
                    List<String> children;
                    try {
                        children = zk.getChildren("/", false);

                        return children.size() != 0;
                    } catch (Exception e) {
                        // silently fail
                    }
                }
                return false;
            }
        }, TimeUnit.MINUTES.toMillis(2)));
    }

    static class ZkWatcher implements Watcher {

        private final String clientId;

        ZkWatcher(String clientId) {
            this.clientId = clientId;
        }

        public void process(WatchedEvent event) {
            LOG.info("<<<EVENT>>> " + clientId + " - WatchedEvent: "
                    + event);
        }
    }

    public static class ZookeeperServerWrapper {

        private static final Logger LOG = LoggerFactory
                .getLogger(ZookeeperServerWrapper.class);

        private final MainThread server;
        private final int clientPort;

        public ZookeeperServerWrapper(int serverId, int portBase)
                throws IOException {
            clientPort = 8200 + serverId;

            // start client port on 8200 + serverId
            // start servers on portbase + 300 or + 400 (+serverId)
            String quorumCfgSection = "server.1=127.0.0.1:" + (portBase + 301)
                    + ":" + (portBase + 401)
                    + "\nserver.2=127.0.0.1:" + (portBase + 302) + ":"
                    + (portBase + 402)
                    + "\nserver.3=127.0.0.1:" + (portBase + 303) + ":"
                    + (portBase + 403);

            server = new MainThread(serverId, clientPort, quorumCfgSection);
        }

        public void start() throws Exception {
            server.start();
        }

        public void await(long timeout) throws Exception {
            long deadline = System.currentTimeMillis() + timeout;
            String result = "?";
            while (deadline > System.currentTimeMillis()) {
                try {
                    result = FourLetterWordMain.send4LetterWord("127.0.0.1",
                            clientPort, "stat");
                    if (result.startsWith("Zookeeper version:")) {
                        LOG.info("Started zookeeper server on port "
                                 + clientPort);
                        return;
                    }
                } catch (IOException e) {
                    // ignore as this is expected
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // ignore
                }
            }
            LOG.info(result);
            throw new Exception("Failed to connect to zookeeper server");
        }

        public void stop() {
            try {
                server.shutdown();
            } catch (InterruptedException e) {
                LOG.info("Interrupted while shutting down");
            }
        }

        public void clean() throws IOException {
            server.clean();
        }
    }
}
