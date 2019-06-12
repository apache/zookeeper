package org.apache.zookeeper.test;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class GetDataListTest extends ClientBase {

    protected ZooKeeper zooKeeper;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        zooKeeper = createClient();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        zooKeeper.close();
    }

    final private String BASE_PATH = "/getDataMultiTest";
    final private int NUM_PATHS = 100;

    /**
     * @param numPaths         - num paths to create under root
     * @param pathToSkipCreate - skip creation of this path to test best-effort, set to -1 to not skip any path
     */
    private List<String> createTestData(byte[] testData, int numPaths, int pathToSkipCreate) throws KeeperException, InterruptedException {
        zooKeeper.create(BASE_PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        List<String> childPaths = new ArrayList<>(numPaths);

        for (int i = 0; i < numPaths; i++) {
            final String path = String.format("%s/%02d", BASE_PATH, i);
            childPaths.add(path);
            if (pathToSkipCreate != i) {
                zooKeeper.create(path, testData, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        }
        return childPaths;
    }

    // create 2 levels of data
    // todo expose byte[] to test different payload sizes.
    protected void createTestDataLarge(int firstLevelStart, int numFirstLevel, int numSecondLevel) throws Exception {
        if (zooKeeper.exists(BASE_PATH, false) == null) {
            zooKeeper.create(BASE_PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        for (int i = firstLevelStart; i < numFirstLevel; i++) {
            long now = System.currentTimeMillis();

            String firstLevelPath = String.format("%s/%d_%d", BASE_PATH, now, i);
            String data = String.format("I am \"%s\" data!", firstLevelPath);
            zooKeeper.create(firstLevelPath, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            // TODO: do this async
            for (int j = 0; j < numSecondLevel; j++) {
                String secondLevelPath = String.format("%s/%d", firstLevelPath, j);
                data = String.format("I am \"%s\" data!", secondLevelPath);
                zooKeeper.create(secondLevelPath,
                        data.getBytes(),
                        ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
            }
        }
    }

    // only necessary when testing on the same server
    private void cleanup() throws KeeperException, InterruptedException {
        // create root directory
        Stat stat = zooKeeper.exists(BASE_PATH, null);
        if (stat != null) {
            // get children and delete them
            List<String> children = zooKeeper.getChildren(BASE_PATH, null);
            for (String child : children) {
                String fullPath = BASE_PATH + "/" + child;
                // System.out.println("Deleting " + fullPath);
                zooKeeper.delete(fullPath, -1);
            }
            zooKeeper.delete(BASE_PATH, stat.getVersion());
        }
    }

    private void testWithSkip(byte[] testData, boolean async) throws KeeperException, InterruptedException {

        int pathToSkipCreate = 5;
        List<String> childPaths = createTestData(testData, NUM_PATHS, 5);

        Assert.assertEquals(NUM_PATHS, childPaths.size());

        List<OpResult> results;
        if (async) {
            BatchStats batchStats = new BatchStats();
            zooKeeper.getDataList(childPaths, true, batchStats, batchStats);
            batchStats.waitForNumRequests(1);
            results = batchStats.opResults;
        } else {
            results = zooKeeper.getDataList(childPaths, true);
        }
        Assert.assertEquals(NUM_PATHS, results.size());

        int index = 0;
        for (OpResult opResult : results) {
            if (index == pathToSkipCreate) {
                Assert.assertTrue(opResult instanceof OpResult.ErrorResult);
                OpResult.ErrorResult errorResult = (OpResult.ErrorResult) opResult;
                Assert.assertEquals(errorResult.getErr(), KeeperException.Code.NONODE.intValue());
            } else {
                Assert.assertTrue(opResult instanceof OpResult.GetDataResult);
            }
            index++;
        }

    }

    private void testWithoutSkip() throws KeeperException, InterruptedException {

        List<String> childPaths = createTestData("fooData".getBytes(), NUM_PATHS, -1);

        Assert.assertEquals(NUM_PATHS, childPaths.size());

        List<OpResult> results = zooKeeper.getDataList(childPaths, true);
        Assert.assertEquals(NUM_PATHS, results.size());
    }


    @Test
    public void testGetDataListBestEffort() {
        try {
            cleanup();
            testWithoutSkip();

            cleanup();
            // test with null data and non-null data.  Make sure it null data doesn't count as an error
            testWithSkip("fooData".getBytes(), false);

            cleanup();
            testWithSkip(null, false);

            cleanup();
            testWithSkip("fooDataBlah".getBytes(), true);

            cleanup();
            testWithSkip(null, true);

        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail("Error testing getDataList");
        }
    }


    class ReadNodesResults {
        long elapsedTime = 0;
        int totalNodesRead = 0;
        long totalBytesRead = 0;
        String syncAsync = "sync";
        boolean watch = false;
    }

    private ReadNodesResults readNodes(int numTopLevel, int batchSize, String syncOrAsync, boolean watch, boolean multi) throws KeeperException, InterruptedException {

        ReadNodesResults readNodesResults = new ReadNodesResults();

        long t0 = System.currentTimeMillis();
        List<String> children = zooKeeper.getChildren(BASE_PATH, false);

        if (batchSize == 0) {
            readNodesSingle(numTopLevel, children, syncOrAsync, watch, readNodesResults);
        } else {
            readNodesBatch(numTopLevel, children, batchSize, syncOrAsync, watch, multi, readNodesResults);
        }

        readNodesResults.elapsedTime = System.currentTimeMillis() - t0;
        return readNodesResults;
    }

    private void readNodesSingle(int numTopLevel, List<String> children, String syncOrAsync, boolean watch,
                                 ReadNodesResults readNodesResults)
            throws KeeperException, InterruptedException {
        if (syncOrAsync != null && syncOrAsync.equalsIgnoreCase("sync")) {
            readNodesSingleSync(numTopLevel, children, watch, readNodesResults);
        } else {
            readNodesSingleASync(numTopLevel, children, watch, readNodesResults);
        }
    }

    private void readNodesSingleSync(int numTopLevel, List<String> children, boolean watch, ReadNodesResults readNodesResults)
            throws KeeperException, InterruptedException {

        readNodesResults.syncAsync = "sync";
        readNodesResults.watch = watch;
        int topLevel = 0;
        for (String child : children) {
            List<String> subChildren = getChildrenAndReport(child, topLevel, readNodesResults.totalNodesRead, false);
            readNodesResults.totalNodesRead++;
            for (String subChild : subChildren) {
                // result is ignored
                Stat stat = new Stat();
                byte[] data = zooKeeper.getData(BASE_PATH + "/" + child + "/" + subChild, watch, stat);
                readNodesResults.totalBytesRead += data.length;
                readNodesResults.totalNodesRead++;
            }
            topLevel++;
            if (topLevel == numTopLevel) {
                break;
            }
        }
    }


    class ASyncSingleResults implements AsyncCallback.DataCallback {
        int totalBytesRead = 0;
        int totalNodesRead = 0;
        int results = 0;

        @Override
        public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat) {

            synchronized (this) {
                ASyncSingleResults aSyncResults = (ASyncSingleResults) ctx;
                aSyncResults.results++;
                aSyncResults.totalBytesRead += data.length;
                aSyncResults.totalNodesRead++;

                int length = stat.getDataLength();
                if (length != data.length && aSyncResults.totalNodesRead % 1000 == 0) {
                    System.out.println("length mismatch? " + length + " != " + data.length);
                }

                ctx.notifyAll();
            }
        }

        void waitForResult(int numResultsToWaitFor) throws InterruptedException {
            synchronized (this) {
                while (results < numResultsToWaitFor) {
                    this.wait();
                }
                results = 0;
            }
        }
    }

    private void readNodesSingleASync(int numTopLevel, List<String> children, boolean watch, ReadNodesResults readNodesResults)
            throws KeeperException, InterruptedException {

        // System.out.println("Running single async watch " + watch);

        readNodesResults.syncAsync = "async";
        readNodesResults.watch = watch;
        int topLevel = 0;

        ASyncSingleResults aSyncResults = new ASyncSingleResults();
        for (String child : children) {

            List<String> subChildren = getChildrenAndReport(child, topLevel, aSyncResults.totalNodesRead, false);
            aSyncResults.totalNodesRead++;

            int numAsyncSubmitted = 0;
            for (String subChild : subChildren) {
                // result is ignored
                final String fullPath = BASE_PATH + "/" + child + "/" + subChild;
                zooKeeper.getData(fullPath, watch, aSyncResults, aSyncResults);
                numAsyncSubmitted++;
            }
            aSyncResults.waitForResult(numAsyncSubmitted);

            topLevel++;
            if (topLevel == numTopLevel) {
                break;
            }
        }

        // wait for aSyncResults

        readNodesResults.totalBytesRead = aSyncResults.totalBytesRead;
        readNodesResults.totalNodesRead = aSyncResults.totalNodesRead;
    }


    private List<String> getChildrenAndReport(String topLevelName, int topLevel, int totalNodesRead,
                                              boolean report)
            throws KeeperException, InterruptedException {
        final String path = BASE_PATH + "/" + topLevelName;
        byte[] data = zooKeeper.getData(path, false, null);
        List<String> subChildren = zooKeeper.getChildren(path, false);
        if (report) {
            System.out.println(topLevel + ":" + topLevelName + ", " + new String(data)
                    + ", num children: " + subChildren.size()
                    + ", total nodes so far: " + totalNodesRead);
        }
        return subChildren;
    }


    public class BatchStats implements AsyncCallback.DataListCallback {
        int numRequests = 0;
        int numSubPaths = 0;
        int totalNodesRead = 0;
        int totalBytes = 0;
        int maxResponseSize = 0;

        List<OpResult> opResults = null;

        @Override
        public void processResult(int rc, Object ctx, List<OpResult> opResults) {

            synchronized (this) {
                this.opResults = opResults;
                int fullResponseSize = 0;
                for (OpResult result : opResults) {
                    fullResponseSize = processSingleOpResult(fullResponseSize, result);
                }
                maxResponseSize = Math.max(fullResponseSize, maxResponseSize);
                numSubPaths += opResults.size();
                numRequests++;
                this.notifyAll();
            }
        }

        int processSingleOpResult(int singleResponseSize, OpResult result) {
            totalNodesRead++;
            if (result instanceof OpResult.GetDataResult) {
                OpResult.GetDataResult getDataResult = (OpResult.GetDataResult) result;
                byte[] data = getDataResult.getData();
                int dataLength = data == null ? 0 : data.length;
                totalBytes += dataLength;
                singleResponseSize += dataLength;
                Stat stat = getDataResult.getStat();
                Assert.assertEquals(stat.getDataLength(), dataLength);
            }
            return singleResponseSize;
        }

        void waitForNumRequests(int numRequestsAsyncSubmitted) throws InterruptedException {
            synchronized (this) {
                while (numRequests < numRequestsAsyncSubmitted) {
                    this.wait();
                }
            }
        }

        void reset() {
            numRequests = 0;
            numSubPaths = 0;
            totalNodesRead = 0;
            totalBytes = 0;
            maxResponseSize = 0;
            opResults = null;
        }
    }


    private void readNodesBatch(int numTopLevel, List<String> children, int batchSize,
                                String syncOrAsync, boolean watch, boolean multi, ReadNodesResults readNodesResults)
            throws KeeperException, InterruptedException {
        int topLevel = 0;

        boolean async = syncOrAsync.equalsIgnoreCase("async");

        readNodesResults.syncAsync = syncOrAsync;
        readNodesResults.watch = watch;
        BatchStats batchStats = new BatchStats();

        int numRequestsAsyncSubmitted = 0;
        for (String topLevelChild : children) {
            List<String> subChildren = getChildrenAndReport(topLevelChild, topLevel, readNodesResults.totalNodesRead, false);
            readNodesResults.totalNodesRead++;
            List<String> paths = new ArrayList<>();
            for (String subChild : subChildren) {
                String curPath = BASE_PATH + "/" + topLevelChild + "/" + subChild;
                paths.add(curPath);
                if (paths.size() == batchSize) {
                    numRequestsAsyncSubmitted++;
                    doBatchRead(paths, batchStats, async, watch, multi);
                }
            }

            // send remainder
            int remainderSize = paths.size();
            if (remainderSize > 0) {
                numRequestsAsyncSubmitted++;
                doBatchRead(paths, batchStats, async, watch, multi);
            }

            if (!async) {
                readNodesResults.totalNodesRead += batchStats.totalNodesRead;
                readNodesResults.totalBytesRead += batchStats.totalBytes;

//                System.out.printf("Did %d requests, %d batchSubPath, %d total node reads, %d remainder, %d resp size\n",
//                        batchStats.numRequests, batchStats.numSubPaths, batchStats.totalNodesRead, remainderSize,
//                        batchStats.maxResponseSize);
                batchStats.reset();
            }

            topLevel++;
            if (topLevel == numTopLevel) {
                break;
            }
        }

        if (async) {

            // wait for all requests to finish
            batchStats.waitForNumRequests(numRequestsAsyncSubmitted);

            readNodesResults.totalBytesRead = batchStats.totalBytes;
            readNodesResults.totalNodesRead += batchStats.totalNodesRead;
        }

    }


    private void doBatchRead(List<String> paths, BatchStats batchStats, boolean async, boolean watch, boolean multi)
            throws KeeperException, InterruptedException {
        if (async) {
            if (multi) {
                Iterable<Op> ops = paths.stream().map(Op::getData).collect(Collectors.toList());
                zooKeeper.multi(ops, (rc, path, ctx, opResults) -> batchStats.processResult(rc, ctx, opResults), batchStats);
            } else {
                zooKeeper.getDataList(paths, watch, batchStats, batchStats);
            }
        } else {
            List<OpResult> opResults = multi ?
                    zooKeeper.multi(paths.stream().map(Op::getData).collect(Collectors.toList())) :
                    zooKeeper.getDataList(paths, watch);
            batchStats.numRequests++;

            int singleResponseSize = 0;

            for (OpResult result : opResults) {
                batchStats.processSingleOpResult(singleResponseSize, result);
            }
            batchStats.maxResponseSize = Math.max(singleResponseSize, batchStats.maxResponseSize);
            batchStats.numSubPaths += paths.size();
        }
        paths.clear();
    }


    private int changeDataToTriggerWatches(int numTopLevel, int modChange) throws KeeperException, InterruptedException {

        int totalNodesMutated = 0;
        try (ZooKeeper zooKeeper2 = new ZooKeeper(hostPort, CONNECTION_TIMEOUT, null)) {

            int topLevel = 0;
            // get the data out of all the nodes, drop it on the floor.
            List<String> children = zooKeeper2.getChildren(BASE_PATH, false);

            for (String topLevelChild : children) {
                List<String> subChildren = zooKeeper2.getChildren(BASE_PATH + "/" + topLevelChild, false);
                int i = 0;
                for (String subChild : subChildren) {
                    if (i % modChange == 0) {
                        String curPath = BASE_PATH + "/" + topLevelChild + "/" + subChild;
                        Stat stat = new Stat();
                        String data = new String(zooKeeper2.getData(curPath, false, stat));
                        // flip ! and ? to trigger a watch
                        char c = data.charAt(data.length() - 1);
                        String newData = data.substring(0, data.length() - 1) + ((c == '!') ? '?' : '!');
                        Stat newStat = zooKeeper2.setData(curPath, newData.getBytes(), stat.getVersion());
                        if (newStat.getVersion() > stat.getVersion()) {
                            totalNodesMutated++;
                        }
                    }
                    i++;
                }
                topLevel++;
                if (topLevel == numTopLevel) {
                    break;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return totalNodesMutated;
    }

    private void testWithoutWatch(int numTopLevelZNodes, int expectedTotalNodeRead, boolean multi) throws Exception {
        testReadNodesNoWatch(numTopLevelZNodes, 0, "sync", multi, expectedTotalNodeRead);
        testReadNodesNoWatch(numTopLevelZNodes, 0, "async", multi, expectedTotalNodeRead);
        testReadNodesNoWatch(numTopLevelZNodes, 100, "sync", multi, expectedTotalNodeRead);
        testReadNodesNoWatch(numTopLevelZNodes, 100, "async", multi, expectedTotalNodeRead);
    }


    public static class WatcherCounter {
        public final AtomicInteger numNodeDataChanged = new AtomicInteger(0);
        public Watcher watcher = event -> {
            if (event != null && event.getType().equals(Watcher.Event.EventType.NodeDataChanged)) {
                numNodeDataChanged.incrementAndGet();
            }
        };
    }


    private void testWithWatch(int numTopLevelZNodes, int expectedTotalNodesRead, int modForSetData) throws Exception {

        WatcherCounter watcherCounter = new WatcherCounter();

        zooKeeper.register(watcherCounter.watcher);

        testReadNodesAndWatch(numTopLevelZNodes, 0, "sync",
                expectedTotalNodesRead, watcherCounter.numNodeDataChanged, modForSetData);
        testReadNodesAndWatch(numTopLevelZNodes, 0, "async",
                expectedTotalNodesRead, watcherCounter.numNodeDataChanged, modForSetData);
        testReadNodesAndWatch(numTopLevelZNodes, 100, "sync",
                expectedTotalNodesRead, watcherCounter.numNodeDataChanged, modForSetData);
        testReadNodesAndWatch(numTopLevelZNodes, 100, "async",
                expectedTotalNodesRead, watcherCounter.numNodeDataChanged, modForSetData);

    }

    public void testReadNodesAndWatch(int numTopLevelZNodes,
                                      int batchSize,
                                      String syncOrAsync,
                                      int expectedTotalNodesRead,
                                      AtomicInteger numNodeDataChanged,
                                      int modForSetData) throws KeeperException, InterruptedException {
        ReadNodesResults readNodesResults = readNodes(numTopLevelZNodes, batchSize, syncOrAsync, true, false);
        Assert.assertEquals(expectedTotalNodesRead, readNodesResults.totalNodesRead);
        Assert.assertEquals(readNodesResults.syncAsync, syncOrAsync);

        Assert.assertTrue(readNodesResults.watch);
//            Assert.assertTrue(readNodesResults.elapsedTime > 200 && readNodesResults.elapsedTime < 1000);
        System.out.printf("Elapsed time %sms (%ss)\n", readNodesResults.elapsedTime, readNodesResults.elapsedTime / 1000);

        // now change data nodes, confirm watches fire
        int totalNodeMutated = changeDataToTriggerWatches(numTopLevelZNodes, modForSetData);
        Assert.assertEquals(totalNodeMutated, numNodeDataChanged.getAndSet(0));
    }

    public void testReadNodesNoWatch(int numTopLevelZNodes,
                                     int batchSize,
                                     String syncOrAsync,
                                     boolean multi,
                                     int expectedTotalNodesRead) throws KeeperException, InterruptedException {
        ReadNodesResults readNodesResults = readNodes(numTopLevelZNodes, batchSize, syncOrAsync, false, multi);
        Assert.assertEquals(expectedTotalNodesRead, readNodesResults.totalNodesRead);
        Assert.assertEquals(readNodesResults.syncAsync, syncOrAsync);

        Assert.assertFalse(readNodesResults.watch);
//            Assert.assertTrue(readNodesResults.elapsedTime > 200 && readNodesResults.elapsedTime < 1000);
        System.out.printf("Elapsed time %sms (%ss)\n", readNodesResults.elapsedTime, readNodesResults.elapsedTime / 1000);
    }

    // TODO: ACL test, where one of the path you don't have access to.  Should succeed with best effort


    // run multiple test with a relatively large dataset (~300,000 nodes)
    // assert roughly on benchmark timings between different getData strategies
    // TODO: reorganize tests to allow running a Suite / TestSetup
    @Test
    public void testsWithLargeData() {

        try {
            final int numTopLevelZNodes = 3;
            final int numSecondLevelZNodes = 1000;
            createTestDataLarge(0, numTopLevelZNodes, numSecondLevelZNodes);

            final int EXPECTED_TOTAL_NODES_READ = numTopLevelZNodes * numSecondLevelZNodes + numTopLevelZNodes;
            final int modForSetData = 10;

            //testWithoutWatch(numTopLevelZNodes, EXPECTED_TOTAL_NODES_READ, false);
            testWithoutWatch(numTopLevelZNodes, EXPECTED_TOTAL_NODES_READ, true);
            //testWithWatch(numTopLevelZNodes, EXPECTED_TOTAL_NODES_READ, modForSetData);

        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail("Error testing getDataList");
        }
    }


    public static void main(String[] argv) throws Exception {

        // TODO: add param to allow running against an existing server, skip setup / teardown

        int numLevels = 3;
        int batchReadSize = 0;
        String syncOrAsync = "async";
        boolean watch = false;
        boolean multi = false;

        if (argv != null) {

            if (argv.length >= 1) {
                numLevels = Integer.parseInt(argv[0]);
                System.out.println("Num levels " + numLevels);
            }
            if (argv.length >= 2) {
                batchReadSize = Integer.parseInt(argv[1]);
                System.out.println("Batch size " + batchReadSize);
            }
            if (argv.length >= 3) {
                syncOrAsync = argv[2];
                System.out.println("Sync or async: " + syncOrAsync);
            }
            if (argv.length == 4) {
                watch = Boolean.parseBoolean(argv[3]);
                System.out.println("watch " + watch);
            }
            if (argv.length == 5) {
                multi = Boolean.parseBoolean(argv[4]);
                System.out.println("Multi " + multi);
            }
        }

        if (watch && multi) {
            System.out.println("WARN: multi and watch not supported, setting watch to false");
            watch = false;
        }

        GetDataListTest getDataListTest = new GetDataListTest();
        getDataListTest.setUp();

        final int numSecondLevelZNodes = 1000;
        final int EXPECTED_TOTAL_NODES_READ = numLevels * numSecondLevelZNodes + numLevels;
        final int modForSetData = 100;

        System.out.println("Creating test data");
        long t0 = System.currentTimeMillis();
        getDataListTest.createTestDataLarge(0, numLevels, numSecondLevelZNodes);
        System.out.println("Done creating data, took " + (System.currentTimeMillis() - t0) + "ms");

        if (watch) {

            final AtomicInteger numNodeDataChanged = new AtomicInteger(0);
            final AtomicInteger numWatchesNonDataChange = new AtomicInteger(0);

            Watcher watcher = event -> {
                if (event != null && event.getType().equals(Watcher.Event.EventType.NodeDataChanged)) {
                    numNodeDataChanged.incrementAndGet();
                } else {
                    numWatchesNonDataChange.incrementAndGet();
                }
            };
            getDataListTest.zooKeeper.register(watcher);

            getDataListTest.testReadNodesAndWatch(numLevels, batchReadSize, syncOrAsync, EXPECTED_TOTAL_NODES_READ,
                    numNodeDataChanged, modForSetData);
        } else {
            getDataListTest.testReadNodesNoWatch(numLevels, batchReadSize, syncOrAsync, multi, EXPECTED_TOTAL_NODES_READ);
        }

        getDataListTest.tearDown();

    }

}
