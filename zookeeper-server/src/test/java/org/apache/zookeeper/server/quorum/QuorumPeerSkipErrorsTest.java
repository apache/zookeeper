package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class QuorumPeerSkipErrorsTest extends QuorumPeerTestBase {
    @Test
    public void testRequestSkipConsistencyAfterLeaderElection() throws Exception {
        LeaderZooKeeperServer.setSkipTxnEnabled(true);

        numServers = 3;
        servers = LaunchServers(numServers);
        int leader = servers.findLeader();

        // make sure there is a leader
        assertTrue("There should be a leader", leader >= 0);

        int nonleader = (leader + 1) % numServers;
        byte[] input = new byte[1];
        input[0] = 1;

        // this will be an existing node to be used to create error txns
        servers.zk[leader].create("/client", "client-data".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        Thread.sleep(500);

        // Shutdown every one else but the leader
        for (int i = 0; i < numServers; i++) {
            if (i != leader) {
                servers.mt[i].shutdown();
            }
        }
        Thread.sleep(500);

        // shut the leader down
        servers.mt[leader].shutdown();
        System.gc();

        waitForAll(servers.zk, ZooKeeper.States.CONNECTING);

        // Start everyone but the leader
        for (int i = 0; i < numServers; i++) {
            if (i != leader) {
                servers.mt[i].start();
            }
        }

        // wait to connect to one of these
        waitForOne(servers.zk[nonleader], ZooKeeper.States.CONNECTED);

        // start the old leader
        servers.mt[leader].start();
        waitForOne(servers.zk[leader], ZooKeeper.States.CONNECTED);

        String mainNodePath = "/client";
        String mainNodeData = "test-data";

        // start with an error
        try {
            servers.zk[leader].create(mainNodePath, mainNodeData.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
            assertEquals("KeeperErrorCode = NodeExists for /client", e.getMessage());
        }

        // one client per server to simulate some normal load
        // - create a new node that doest exist
        // - try to create a node that already exists -> error transaction
        Thread[] clients = new Thread[numServers];
        for (int cid = 0; cid < numServers; cid++) {
            final int id = cid;
            clients[cid] = new Thread(() -> {
                for (int i = 0; i < 10000; i++) {
                    String threadNodePath = "/client-" + id + "-" + i;
                    String threadNodeData = "test-data";

                    try {
                        // valid transaction
                        servers.zk[id].create(threadNodePath, threadNodeData.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                        // valid read
                        servers.zk[id].getData(threadNodePath, null, null);
                        // invalid transaction
                        servers.zk[id].create(mainNodePath, mainNodeData.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                    } catch (InterruptedException | KeeperException e) {
                    }
                }
            });
        }

        // start the client threads
        for (Thread client : clients) {
            client.start();
        }

        // let the client threads finish work
        for (Thread client : clients) {
            client.join();
        }

        leader = servers.findLeader();
        Leader leaderPeer = servers.mt[leader].main.quorumPeer.leader;
        assertEquals(leaderPeer.lastCommitted, leaderPeer.lastProposed);
        assertEquals(0, leaderPeer.zk.commitProcessor.pendingRequests.size());
        assertEquals(0, leaderPeer.zk.commitProcessor.committedRequests.size());
        assertEquals(0, leaderPeer.zk.commitProcessor.queuedRequests.size());
        assertEquals(0, leaderPeer.zk.commitProcessor.queuedWriteRequests.size());
        assertEquals(0, leaderPeer.zk.commitProcessor.numRequestsProcessing.get());
    }

    @Test
    public void testRequestSkipConsistency() throws Exception {
        LeaderZooKeeperServer.setSkipTxnEnabled(true);

        numServers = 3;
        servers = LaunchServers(numServers);
        int leader = servers.findLeader();

        // shared node for both client 1 and client 2
        servers.zk[leader].create("/shared", "shared-data".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEquals(new String(servers.zk[leader].getData("/shared", null, null)), "shared-data");

        // znode owned by client 1
        servers.zk[leader].create("/client-1", "client-1-data".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEquals(new String(servers.zk[leader].getData("/client-1", null, null)), "client-1-data");

        // znode owned by client 2
        servers.zk[leader].create("/client-2", "client-2-data".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEquals(new String(servers.zk[leader].getData("/client-2", null, null)), "client-2-data");

        Thread[] clients = new Thread[numServers];
        for (int cid = 0; cid < numServers; cid++) {
            final int id = cid;
            clients[cid] = new Thread(() -> {
                for (int i = 0; i < 10000; i++) {
                    try {
                        String threadNodePath = "/client-" + id + "-" + i;
                        String threadNodeData = "data-" + id + "-" + i;

                        servers.zk[id].setData("/shared", threadNodeData.getBytes(), -1);
                        servers.zk[id].create(threadNodePath, threadNodeData.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                        servers.zk[id].create(threadNodePath, threadNodeData.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                    } catch (InterruptedException | KeeperException e) {
                    }
                }
            });
        }

        for (Thread client : clients) {
            client.start();
        }

        for (Thread client : clients) {
            client.join();
        }

        leader = servers.findLeader();
        Leader leaderPeer = servers.mt[leader].main.quorumPeer.leader;
        assertEquals(leaderPeer.lastCommitted, leaderPeer.lastProposed);
        assertEquals(0, leaderPeer.zk.commitProcessor.pendingRequests.size());
        assertEquals(0, leaderPeer.zk.commitProcessor.committedRequests.size());
        assertEquals(0, leaderPeer.zk.commitProcessor.queuedRequests.size());
        assertEquals(0, leaderPeer.zk.commitProcessor.queuedWriteRequests.size());
        assertEquals(0, leaderPeer.zk.commitProcessor.numRequestsProcessing.get());
    }
}