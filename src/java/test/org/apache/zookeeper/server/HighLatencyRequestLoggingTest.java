package org.apache.zookeeper.server;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.LogManager;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

@RunWith(MockitoJUnitRunner.class)
public class HighLatencyRequestLoggingTest extends ZKTestCase implements Watcher {

    private TestAppender testAppender = new TestAppender();

    @Before
    public void setup() {
        LogManager.getLogger(ServerStats.class).addAppender(testAppender);
    }

    @After
    public void tearDown() {
        LogManager.getLogger(ServerStats.class).removeAppender(testAppender);
    }

    class TestAppender extends AppenderSkeleton {
        private List<String> messages = new ArrayList<String>();

        @Override
        protected void append(LoggingEvent loggingEvent) {

        }

        @Override
        public void close() {

        }

        public void doAppend(LoggingEvent event) {
            synchronized (messages) {
                messages.add(event.getMessage().toString());
            }
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }

        public List<String> getMessages() {
            return messages;
        }
    }

    // Test Class to create ZNodes at the specified path and check them
    class ZNodeCreator implements Runnable {

        ZooKeeper zk;
        int startNum;
        int totalNum;

        ZNodeCreator(ZooKeeper zk, int startNum, int totalNum) {
            this.zk = zk;
            this.startNum = startNum;
            this.totalNum = totalNum;
        }

        @Override
        public void run() {
            for(int i = startNum; i < startNum + totalNum; i++) {
                try {
                    zk.create("/foo" + i, ("foobar" +  i).getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT);
                    Assert.assertEquals(new String(zk.getData("/foo" + i, null, null)), "foobar" + i);
                } catch (Exception e) {
                    Assert.fail("Failed to create ZNode. Exiting test.");
                }
            }
        }
    }

    // Basic test that verifies total number of requests above threshold time
    @Test
    public void testRequestWarningThreshold() throws IOException, KeeperException, InterruptedException {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();

        ZooKeeperServerMainTest.MainThread main =
                new ZooKeeperServerMainTest.MainThread(CLIENT_PORT, true, null, 0);
        main.start();

        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                        CONNECTION_TIMEOUT));
        // Get the stats object from the ZooKeeperServer to keep track of high latency requests.
        ServerStats stats = main.main.getServerStats();
        stats.setWaitForLoggingWarnThresholdMsgToFalse();
        stats.setDelayTimeForLoggingWarnThresholdMsg(1000);

        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT,
                ClientBase.CONNECTION_TIMEOUT, (Watcher) this);

        zk.create("/foo1", "fb".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        Assert.assertEquals(new String(zk.getData("/foo1", null, null)), "fb");

        zk.close();
        main.shutdown();
        main.join();
        main.deleteDirs();

        // Total Requests: 4
        // 1 Thread, each writing and verifying 1 znode1 = (1 threads * 1 znodes * 2 req = 2)
        // CreateSession, CloseSession
        verifyLogMessages(4, 5);

        Assert.assertTrue("waiting for server down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT,
                        ClientBase.CONNECTION_TIMEOUT));


    }

    // Test to verify count of requests that exceeded threshold and logger rate limiting with a single thread
    @Test
    public void testSingleThreadFrequentRequestWarningThresholdLogging() throws IOException, KeeperException, InterruptedException {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();

        ZooKeeperServerMainTest.MainThread main = new
                ZooKeeperServerMainTest.MainThread(CLIENT_PORT, true, null, 0);
        main.start();

        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                        CONNECTION_TIMEOUT));
        // Get the stats object from the ZooKeeperServer to keep track of high latency requests.
        ServerStats stats = main.main.getServerStats();
        stats.setWaitForLoggingWarnThresholdMsgToFalse();
        stats.setDelayTimeForLoggingWarnThresholdMsg(1000);

        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT,
                ClientBase.CONNECTION_TIMEOUT, this);

        Thread thread = new Thread(new ZNodeCreator(zk, 0, 5));
        thread.start();
        thread.join();

        zk.close();
        main.shutdown();
        main.join();
        main.deleteDirs();

        // Total Requests: 12
        // 1 Thread, each writing and verifying 5 znodes = (1 threads * 5 znodes * 2 req = 10)
        // CreateSession, CloseSession
        verifyLogMessages(12, 5);

        Assert.assertTrue("waiting for server down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT,
                        ClientBase.CONNECTION_TIMEOUT));
    }


    // Test to verify count of requests that exceeded threshold and logger rate limiting with a multiple threads and multiple times
    @Test
    public void testMultipleThreadsFrequentRequestWarningThresholdLogging()
            throws IOException, KeeperException, InterruptedException {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();

        ZooKeeperServerMainTest.MainThread main =
                new ZooKeeperServerMainTest.MainThread(CLIENT_PORT, true, null, 0);
        main.start();

        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                        CONNECTION_TIMEOUT));
        // Get the stats object from the ZooKeeperServer to keep track of high latency requests.
        ServerStats stats = main.main.getServerStats();
        stats.setWaitForLoggingWarnThresholdMsgToFalse();
        stats.setDelayTimeForLoggingWarnThresholdMsg(1000);

        final ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT,
                ClientBase.CONNECTION_TIMEOUT, this);

        Thread thread1 = new Thread(new ZNodeCreator(zk,0,3));
        Thread thread2 = new Thread(new ZNodeCreator(zk,10,3));

        thread1.start();
        thread2.start();

        thread1.join();
        thread2.join();

        Thread thread3 = new Thread(new ZNodeCreator(zk, 20,3));
        Thread thread4 = new Thread(new ZNodeCreator(zk,30,3));

        thread3.start();
        thread4.start();

        thread3.join();
        thread4.join();

        zk.close();
        main.shutdown();
        main.join();
        main.deleteDirs();

        // Total Requests: 26
        // 4 Threads, each writing and verifying 3 znodes = (4 threads * 3 znodes * 2 req = 24)
        // CreateSession, CloseSession
        verifyLogMessages(26, 5);

        Assert.assertTrue("waiting for server down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT,
                        ClientBase.CONNECTION_TIMEOUT));

    }

    // Parse the messages from ServerStats class logger and determine the number of high latency requests
    // Compare that value with expected value
    // The code retries after every 1000 ms, for 'minRetries' times
    // If any request takes long time to complete, the 'retries' are increase appropriately
    private void verifyLogMessages(int expectedCount, int minRetries) throws InterruptedException {
        Pattern reqExceededThresholdPattern = Pattern.compile("Request .* exceeded threshold. Took (\\d+) ms");
        Pattern numRequestExceededPattern = Pattern.compile("Number of requests that exceeded \\d+ ms in past \\d+ ms: (\\d+)");

        int retries = minRetries;
        int delayBetweenRetryMs = 1000;

        for(int i = 0 ; i < retries; i++) {
            synchronized (testAppender.getMessages()) {
                int countRequests = 0;
                int reqExecutionTime = 0;
                for (String msg : testAppender.getMessages()) {
                    Matcher reqExceededThresholdMatcher = reqExceededThresholdPattern.matcher(msg);
                    Matcher numRequestExceededMatcher = numRequestExceededPattern.matcher(msg);
                    while(reqExceededThresholdMatcher.find()) {
                        countRequests++;
                        reqExecutionTime += Long.parseLong(reqExceededThresholdMatcher.group(1));
                    }
                    while(numRequestExceededMatcher.find()) {
                        countRequests += Integer.parseInt(numRequestExceededMatcher.group(1));
                    }
                }
                retries = minRetries + (reqExecutionTime / delayBetweenRetryMs);
                // If certain requests are delayed, special 'ping' requests are sent in between
                // Thus we check if the counted requests are greater or equal to our expected count
                if(countRequests >= expectedCount) {
                    return;
                }
            }
            Thread.sleep(delayBetweenRetryMs);
        }
        Assert.fail("Didn't log expected number of requests. ExpectedCount: " + expectedCount);
    }

    @Override
    public void process(WatchedEvent event) {
    }
}
