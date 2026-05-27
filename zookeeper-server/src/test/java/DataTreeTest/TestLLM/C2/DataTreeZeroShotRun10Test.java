package DataTreeTest.TestLLM.C2;

import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.WatcherType;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeZeroShotRun10Test {

    private DataTree dataTree;
    private List<ACL> defaultAcl;
    private DummyWatcher dummyWatcher;

    private static class DummyWatcher implements Watcher {
        @Override
        public void process(WatchedEvent event) {
            // No-op watcher for test purposes
        }
    }

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
        defaultAcl = new ArrayList<>();
        defaultAcl.add(new ACL(31, new Id("world", "anyone"))); // All permissions
        dummyWatcher = new DummyWatcher();
    }

    @Test
    public void testGetNodeRootExistsInitially() {
        assertNotNull(dataTree.getNode("/"), "Root node must exist upon initialization");
    }

    @Test
    public void testGetNodeCountGreaterThanZeroInitially() {
        assertTrue(dataTree.getNodeCount() > 0, "Initial node count should reflect system nodes (root, proc, quota)");
    }

    @Test
    public void testCreatePersistentNodeSuccessfully() throws Exception {
        dataTree.createNode("/testNode", "testData".getBytes(StandardCharsets.UTF_8), defaultAcl, 0L, -1, 1L, 1L);
        assertNotNull(dataTree.getNode("/testNode"), "Persistent node should be created");
        assertEquals(1, dataTree.getAllChildrenNumber("/testNode"));
    }

    @Test
    public void testCreateEphemeralNodeSuccessfully() throws Exception {
        long sessionId = 12345L;
        dataTree.createNode("/ephemeralNode", "data".getBytes(StandardCharsets.UTF_8), defaultAcl, sessionId, -1, 1L, 1L);
        Set<String> ephemerals = dataTree.getEphemerals(sessionId);
        assertTrue(ephemerals.contains("/ephemeralNode"), "Ephemeral node must be mapped to session ID");
    }

    @Test
    public void testCreateNodeThrowsNoNodeExceptionForMissingParent() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.createNode("/missingParent/child", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        }, "Should throw exception if parent path doesn't exist");
    }

    @Test
    public void testCreateNodeThrowsNodeExistsException() throws Exception {
        dataTree.createNode("/duplicateNode", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        assertThrows(NodeExistsException.class, () -> {
            dataTree.createNode("/duplicateNode", new byte[0], defaultAcl, 0L, -1, 2L, 2L);
        }, "Should throw exception when creating a node with an existing path");
    }

    @Test
    public void testCreateNodePopulatesStat() throws Exception {
        Stat stat = new Stat();
        dataTree.createNode("/statNode", new byte[0], defaultAcl, 0L, -1, 999L, 888L, stat);
        assertEquals(999L, stat.getCzxid(), "Output stat should reflect creation zxid");
    }

    @Test
    public void testDeleteNodeSuccessfully() throws Exception {
        dataTree.createNode("/nodeToDelete", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.deleteNode("/nodeToDelete", 2L);
        assertNull(dataTree.getNode("/nodeToDelete"), "Node should be removed from DataTree");
    }

    @Test
    public void testDeleteNodeThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.deleteNode("/nonExistentNode", 1L);
        }, "Deleting non-existent path should throw NoNodeException");
    }

    @Test
    public void testSetDataSuccessfully() throws Exception {
        dataTree.createNode("/setDataNode", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        Stat stat = dataTree.setData("/setDataNode", "newData".getBytes(StandardCharsets.UTF_8), 5, 2L, 2L);
        assertEquals(5, stat.getVersion(), "SetData should update the version");
        assertEquals(2L, stat.getMzxid(), "SetData should update mzxid");
    }

    @Test
    public void testSetDataThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.setData("/missingSetData", new byte[0], -1, 1L, 1L);
        }, "Setting data on missing node should throw NoNodeException");
    }

    @Test
    public void testGetDataSuccessfully() throws Exception {
        byte[] expectedData = "storedData".getBytes(StandardCharsets.UTF_8);
        dataTree.createNode("/getDataNode", expectedData, defaultAcl, 0L, -1, 1L, 1L);
        byte[] retrievedData = dataTree.getData("/getDataNode", new Stat(), null);
        assertArrayEquals(expectedData, retrievedData, "GetData should return identical byte array");
    }

    @Test
    public void testGetDataThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.getData("/missingGetData", new Stat(), null);
        }, "Getting data on missing node should throw NoNodeException");
    }

    @Test
    public void testGetDataPopulatesStat() throws Exception {
        dataTree.createNode("/getDataStatNode", new byte[0], defaultAcl, 0L, -1, 123L, 1L);
        Stat stat = new Stat();
        dataTree.getData("/getDataStatNode", stat, null);
        assertEquals(123L, stat.getCzxid(), "GetData should populate stat parameters");
    }

    @Test
    public void testGetDataRegistersWatch() throws Exception {
        dataTree.createNode("/getDataWatch", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/getDataWatch", new Stat(), dummyWatcher);
        assertEquals(1, dataTree.getWatchCount(), "Watch count should increment when adding a watcher via getData");
    }

    @Test
    public void testStatNodeSuccessfully() throws Exception {
        dataTree.createNode("/statCheckNode", new byte[0], defaultAcl, 0L, -1, 456L, 1L);
        Stat stat = dataTree.statNode("/statCheckNode", null);
        assertEquals(456L, stat.getCzxid(), "statNode should return correctly populated Stat object");
    }

    @Test
    public void testStatNodeThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.statNode("/missingStatNode", null);
        }, "statNode on missing node should throw exception");
    }

    @Test
    public void testStatNodeRegistersWatch() throws Exception {
        dataTree.createNode("/statWatchNode", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.statNode("/statWatchNode", dummyWatcher);
        assertEquals(1, dataTree.getWatchCount(), "statNode should add a watcher correctly");
    }

    @Test
    public void testGetChildrenSuccessfully() throws Exception {
        dataTree.createNode("/parent", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.createNode("/parent/child1", new byte[0], defaultAcl, 0L, -1, 2L, 2L);
        List<String> children = dataTree.getChildren("/parent", null, null);
        assertEquals(1, children.size(), "getChildren should return exactly one child");
        assertTrue(children.contains("child1"));
    }

    @Test
    public void testGetChildrenThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.getChildren("/missingParentForChildren", null, null);
        }, "getChildren on non-existent path should throw NoNodeException");
    }

    @Test
    public void testGetChildrenPopulatesStat() throws Exception {
        dataTree.createNode("/parentStat", new byte[0], defaultAcl, 0L, -1, 777L, 1L);
        Stat stat = new Stat();
        dataTree.getChildren("/parentStat", stat, null);
        assertEquals(777L, stat.getCzxid(), "getChildren should populate stat details");
    }

    @Test
    public void testGetChildrenRegistersWatch() throws Exception {
        dataTree.createNode("/parentWatch", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getChildren("/parentWatch", null, dummyWatcher);
        assertEquals(1, dataTree.getWatchCount(), "getChildren must increment watch count when passing watcher");
    }

    @Test
    public void testGetAllChildrenNumber() throws Exception {
        dataTree.createNode("/allChildren", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.createNode("/allChildren/c1", new byte[0], defaultAcl, 0L, -1, 2L, 2L);
        dataTree.createNode("/allChildren/c1/c2", new byte[0], defaultAcl, 0L, -1, 3L, 3L);
        assertEquals(2, dataTree.getAllChildrenNumber("/allChildren"), "Should accurately count all nested children");
    }

    @Test
    public void testSetACLSuccessfully() throws Exception {
        dataTree.createNode("/aclNode", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        Stat stat = dataTree.setACL("/aclNode", defaultAcl, 2);
        assertEquals(2, stat.getAversion(), "Aversion should be updated correctly by setACL");
    }

    @Test
    public void testSetACLThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.setACL("/missingAclNode", defaultAcl, 1);
        });
    }

    @Test
    public void testGetACLSuccessfully() throws Exception {
        dataTree.createNode("/getAclNode", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        List<ACL> acls = dataTree.getACL("/getAclNode", new Stat());
        assertFalse(acls.isEmpty(), "ACL list should not be empty");
        assertEquals(defaultAcl.size(), acls.size(), "Retrieved ACL size should match initial assignment");
    }

    @Test
    public void testGetACLThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.getACL("/missingGetAclNode", new Stat());
        });
    }

    @Test
    public void testAclCacheSizeIncreases() throws Exception {
        int initialSize = dataTree.aclCacheSize();
        dataTree.createNode("/cacheAclNode", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        assertTrue(dataTree.aclCacheSize() > initialSize, "ACL cache size should increase after creating node with ACLs");
    }

    @Test
    public void testGetEphemeralsCountMatches() throws Exception {
        dataTree.createNode("/ephemeral1", new byte[0], defaultAcl, 100L, -1, 1L, 1L);
        dataTree.createNode("/ephemeral2", new byte[0], defaultAcl, 200L, -1, 2L, 2L);
        assertEquals(2, dataTree.getEphemeralsCount(), "Total ephemeral node count should match creations");
    }

    @Test
    public void testGetSessionsContainsOwner() throws Exception {
        dataTree.createNode("/ephemeralSession", new byte[0], defaultAcl, 999L, -1, 1L, 1L);
        assertTrue(dataTree.getSessions().contains(999L), "Sessions collection should contain registered ephemeral owner IDs");
    }

    @Test
    public void testGetContainersNotNull() {
        assertNotNull(dataTree.getContainers(), "getContainers should return a valid, non-null set");
    }

    @Test
    public void testGetTtlsNotNull() {
        assertNotNull(dataTree.getTtls(), "getTtls should return a valid, non-null set");
    }

    @Test
    public void testApproximateDataSizePositive() throws Exception {
        long initialSize = dataTree.approximateDataSize();
        dataTree.createNode("/sizeNode", "testDataSize".getBytes(StandardCharsets.UTF_8), defaultAcl, 0L, -1, 1L, 1L);
        assertTrue(dataTree.approximateDataSize() > initialSize, "Approximate data size should increment correctly");
    }

    @Test
    public void testCachedApproximateDataSizeMatches() {
        assertEquals(dataTree.approximateDataSize(), dataTree.cachedApproximateDataSize(), "Cached and approximated sizes must match");
    }

    @Test
    public void testContainsWatcherData() throws Exception {
        dataTree.createNode("/containsWatch", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/containsWatch", new Stat(), dummyWatcher);
        assertTrue(dataTree.containsWatcher("/containsWatch", WatcherType.Data, dummyWatcher), "containsWatcher should verify watcher existence");
    }

    @Test
    public void testRemoveWatchData() throws Exception {
        dataTree.createNode("/removeWatch", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/removeWatch", new Stat(), dummyWatcher);
        assertTrue(dataTree.removeWatch("/removeWatch", WatcherType.Data, dummyWatcher), "removeWatch should return true if watcher successfully removed");
        assertFalse(dataTree.containsWatcher("/removeWatch", WatcherType.Data, dummyWatcher), "Watcher should be untraceable after removal");
    }

    @Test
    public void testRemoveCnxnClearsWatches() throws Exception {
        dataTree.createNode("/removeCnxn", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/removeCnxn", new Stat(), dummyWatcher);
        assertEquals(1, dataTree.getWatchCount(), "Watch count initially 1");
        dataTree.removeCnxn(dummyWatcher);
        assertEquals(0, dataTree.getWatchCount(), "Removing connection should clear registered watches");
    }

    @Test
    public void testShutdownWatcherNoException() throws Exception {
        dataTree.createNode("/shutdownNode", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/shutdownNode", new Stat(), dummyWatcher);
        dataTree.shutdownWatcher();
        assertDoesNotThrow(() -> dataTree.getWatchCount(), "Operating after shutdownWatcher should not result in catastrophic exceptions");
    }

    @Test
    public void testDumpEphemeralsOutputsData() throws Exception {
        dataTree.createNode("/dumpEphemeral", new byte[0], defaultAcl, 100L, -1, 1L, 1L);
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        dataTree.dumpEphemerals(printWriter);
        printWriter.flush();
        assertTrue(stringWriter.toString().contains("0x64"), "Dump should write hex string representing ephemeral owner");
    }

    @Test
    public void testDumpWatchesOutputsData() throws Exception {
        dataTree.createNode("/dumpWatch", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/dumpWatch", new Stat(), dummyWatcher);
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        dataTree.dumpWatches(printWriter, true);
        printWriter.flush();
        assertTrue(stringWriter.toString().contains("/dumpWatch"), "Dump should write out registered watches by path");
    }

    @Test
    public void testGetMaxPrefixWithQuotaReturnsNull() {
        assertNull(dataTree.getMaxPrefixWithQuota("/"), "Returns null when no quotas are configured against the root");
    }

    @Test
    public void testSetCversionPzxidSuccessfully() throws Exception {
        dataTree.createNode("/cversionNode", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.setCversionPzxid("/cversionNode", 10, 50L);
        Stat stat = dataTree.statNode("/cversionNode", null);
        assertEquals(10, stat.getCversion(), "setCversionPzxid must set the Cversion correctly");
        assertEquals(50L, stat.getPzxid(), "setCversionPzxid must set the Pzxid correctly");
    }

    @Test
    public void testSetCversionPzxidThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.setCversionPzxid("/nonExistentCversion", 10, 50L);
        });
    }
}