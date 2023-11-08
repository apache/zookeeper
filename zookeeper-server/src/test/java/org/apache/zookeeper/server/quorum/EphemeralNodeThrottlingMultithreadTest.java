package org.apache.zookeeper.server.quorum;

import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class EphemeralNodeThrottlingMultithreadTest extends QuorumPeerTestBase {

    public static final String EPHEMERAL_BYTE_LIMIT_KEY = "zookeeper.ephemeralNodes.total.byte.limit";
    static final String TEST_PATH = "/ephemeral-throttling-multithread-test";
    static final int DEFAULT_EPHEMERALNODES_TOTAL_BYTE_LIMIT = (int) (Math.pow(2d, 20d) * .5);
    static final int NUM_SERVERS = 5;

    @BeforeClass
    public static void setUpClass() {
        System.setProperty(EPHEMERAL_BYTE_LIMIT_KEY, Integer.toString(DEFAULT_EPHEMERALNODES_TOTAL_BYTE_LIMIT));
    }

    @AfterClass
    public static void tearDownClass() {
        System.clearProperty(EPHEMERAL_BYTE_LIMIT_KEY);
    }

    // Tests multithreaded creates and deletes against the leader and a follower server
    @Test
    public void multithreadedRequestsTest() throws Exception {
        // 50% of 1mb jute max buffer
        int totalEphemeralNodesByteLimit = (int) (Math.pow(2d, 20d) * .5);
        System.setProperty("zookeeper.ephemeralNodes.total.byte.limit", Integer.toString(totalEphemeralNodesByteLimit));

        servers = LaunchServers(NUM_SERVERS);
        ZooKeeper leaderServer = servers.zk[servers.findLeader()];
        ZooKeeper followerServer = servers.zk[servers.findAnyFollower()];

        runMultithreadedRequests(leaderServer);
        runMultithreadedRequests(followerServer);

        int leaderSessionEphemeralsByteSum = 0;
        for (String nodePath : leaderServer.getEphemerals()) {
            leaderSessionEphemeralsByteSum += BinaryOutputArchive.getSerializedStringByteSize(nodePath);
        }
        // TODO: What % delta do we want to allow here?
        assertEquals(totalEphemeralNodesByteLimit, leaderSessionEphemeralsByteSum, totalEphemeralNodesByteLimit/20d);

        int followerSessionEphemeralsByteSum = 0;
        for (String nodePath : leaderServer.getEphemerals()) {
            followerSessionEphemeralsByteSum += BinaryOutputArchive.getSerializedStringByteSize(nodePath);
        }
        assertEquals(totalEphemeralNodesByteLimit, followerSessionEphemeralsByteSum, totalEphemeralNodesByteLimit/20d);

        servers.shutDownAllServers();
    }

    private void runMultithreadedRequests(ZooKeeper server) {
        int threadPoolCount = 8;
        int deleteRequestThreads = 2;
        int createRequestThreads = threadPoolCount - deleteRequestThreads;
        // Spin up threads to repeatedly send CREATE requests to server
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolCount);
        for (int i = 0; i < createRequestThreads; i++) {
            final int threadID = i;
            executor.submit(() ->{
                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < 10000) {
                    try {
                        server.create(TEST_PATH +"_"+threadID+"_", new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
                    } catch (KeeperException.TotalEphemeralLimitExceeded expectedException) {
                        //  Ignore Ephemeral Count exceeded exception, as this is expected to occur
                    } catch (Exception e) {
                        LOG.warn("Thread encountered an exception but ignored it:\n" + e.getMessage());
                    }
                }
            });
        }

        // Spin up threads to repeatedly send DELETE requests to server
        // After a 1-second sleep, this should run concurrently with the create threads, but then end before create threads end
        // so that we still have time to hit the limit and can then assert that limit was upheld correctly
        for (int i = 0; i < deleteRequestThreads; i++) {
            executor.submit(() -> {
                long startTime = System.currentTimeMillis();
                try {
                    // Brief sleep to reduce chance that ephemeral nodes not yet created
                    Thread.sleep(1000);
                    while (System.currentTimeMillis() - startTime < 4000) {
                        for (String ephemeralNode : server.getEphemerals()) {
                            server.delete(ephemeralNode, -1);
                        }
                    }
                } catch (KeeperException.TotalEphemeralLimitExceeded expectedException) {
                    //  Ignore Ephemeral Count exceeded exception, as this is expected to occur
                } catch (Exception e) {
                    LOG.warn("Thread encountered an exception but ignored it:\n" + e.getMessage());
                }
            });
        }

        executor.shutdown();
        try {
            if(!executor.awaitTermination(12000, TimeUnit.MILLISECONDS)) {
                LOG.warn("Threads did not finish in the given time!");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOG.error(e.getMessage());
            executor.shutdownNow();
        }
    }
}
