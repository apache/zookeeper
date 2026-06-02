package DataTreeTest.TestLLM.C1;


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
            // No-op for testing
        }
    }

    @BeforeEach

    public void setUp() {
        dataTree = new DataTree();
        defaultAcl = new ArrayList<>();
        defaultAcl.add(new ACL(31, new Id("world", "anyone"))); // ALL permissions
        dummyWatcher = new DummyWatcher();
    }

    @Test
    public void testGetNodeRoot() {
        assertNotNull(dataTree.getNode("/"), "Root node should exist after initialization");
    }

    @Test
    public void testGetNodeCountInitially() {
        assertTrue(dataTree.getNodeCount() > 0, "Initial node count should be greater than 0 due to default system nodes");
    }

    @Test
    public void testCreatePersistentNode() throws Exception {
        dataTree.createNode("/persistent", "data".getBytes(StandardCharsets.UTF_8), defaultAcl, 0L, -1, 1L, 1L);
        assertNotNull(dataTree.getNode("/persistent"), "Persistent node should be created successfully");
    }

    @Test
    public void testCreateEphemeralNode() throws Exception {
        long sessionId = 12345L;
        dataTree.createNode("/ephemeral", "data".getBytes(StandardCharsets.UTF_8), defaultAcl, sessionId, -1, 1L, 1L);
        assertNotNull(dataTree.getNode("/ephemeral"), "Ephemeral node should be created");
        assertTrue(dataTree.getEphemerals(sessionId).contains("/ephemeral"), "Ephemeral list should contain the created node");
    }

    @Test
    public void testCreateNodeThrowsNoNodeExceptionForMissingParent() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.createNode("/missingparent/child", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        }, "Creating a node with a non-existent parent should throw NoNodeException");
    }

    @Test
    public void testCreateNodeThrowsNodeExistsException() throws Exception {
        dataTree.createNode("/duplicate", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        assertThrows(NodeExistsException.class, () -> {
            dataTree.createNode("/duplicate", new byte[0], defaultAcl, 0L, -1, 2L, 2L);
        }, "Creating an already existing node should throw NodeExistsException");
    }

    @Test
    public void testCreateNodePopulatesStat() throws Exception {
        Stat stat = new Stat();
        dataTree.createNode("/statnode", new byte[0], defaultAcl, 0L, -1, 123L, 456L, stat);
        assertEquals(123L, stat.getCzxid(), "Stat czxid should be populated from transaction");
        assertEquals(456L, stat.getCtime(), "Stat ctime should be populated from transaction");
    }

    @Test
    public void testDeleteExistingNode() throws Exception {
        dataTree.createNode("/todelete", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.deleteNode("/todelete", 2L);
        assertNull(dataTree.getNode("/todelete"), "Node should be null after deletion");
    }

    @Test
    public void testDeleteNodeThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.deleteNode("/nonexistent", 1L);
        }, "Deleting a missing node should throw NoNodeException");
    }

    @Test
    public void testSetDataSetVersion() throws Exception {
        dataTree.createNode("/versionnode", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        Stat stat = dataTree.setData("/versionnode", new byte[0], 5, 2L, 2L);
        assertEquals(5, stat.getVersion(), "SetData should update the version to the passed parameter");
    }

    @Test
    public void testSetDataThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.setData("/nonexistent", new byte[0], -1, 1L, 1L);
        }, "Setting data on a missing node should throw NoNodeException");
    }

    @Test
    public void testSetDataUpdatesMzxid() throws Exception {
        dataTree.createNode("/mzxidnode", "old".getBytes(StandardCharsets.UTF_8), defaultAcl, 0L, -1, 1L, 1L);
        Stat stat = dataTree.setData("/mzxidnode", "new".getBytes(StandardCharsets.UTF_8), 1, 999L, 2L);
        assertEquals(999L, stat.getMzxid(), "SetData should update mzxid to the provided transaction id");
    }

    @Test
    public void testGetDataSuccessfully() throws Exception {
        byte[] expectedData = "testdata".getBytes(StandardCharsets.UTF_8);
        dataTree.createNode("/getdata", expectedData, defaultAcl, 0L, -1, 1L, 1L);
        byte[] retrievedData = dataTree.getData("/getdata", new Stat(), null);
        assertArrayEquals(expectedData, retrievedData, "Retrieved data should match the inserted data");
    }

    @Test
    public void testGetDataThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.getData("/nonexistent", new Stat(), null);
        }, "Getting data from a non-existent node should throw NoNodeException");
    }

    @Test
    public void testGetDataPopulatesStat() throws Exception {
        dataTree.createNode("/getdatastat", new byte[0], defaultAcl, 0L, -1, 555L, 1L);
        Stat stat = new Stat();
        dataTree.getData("/getdatastat", stat, null);
        assertEquals(555L, stat.getCzxid(), "GetData should populate the provided stat object");
    }

    @Test
    public void testGetDataRegistersWatch() throws Exception {
        dataTree.createNode("/getdatawatch", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/getdatawatch", new Stat(), dummyWatcher);
        assertEquals(1, dataTree.getWatchCount(), "GetData with a watcher should increment the watch count");
    }

    @Test
    public void testStatNodeSuccessfully() throws Exception {
        dataTree.createNode("/statnode2", new byte[0], defaultAcl, 0L, -1, 777L, 1L);
        Stat stat = dataTree.statNode("/statnode2", null);
        assertEquals(777L, stat.getCzxid(), "StatNode should return a Stat object representing the node");
    }

    @Test
    public void testStatNodeThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.statNode("/nonexistent", null);
        }, "Stat on a missing node should throw NoNodeException");
    }

    @Test
    public void testStatNodeRegistersWatch() throws Exception {
        dataTree.createNode("/statnodewatch", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.statNode("/statnodewatch", dummyWatcher);
        assertEquals(1, dataTree.getWatchCount(), "StatNode with a watcher should increment the watch count");
    }

    @Test
    public void testGetChildrenSuccessfully() throws Exception {
        dataTree.createNode("/parent", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.createNode("/parent/child1", new byte[0], defaultAcl, 0L, -1, 2L, 2L);
        dataTree.createNode("/parent/child2", new byte[0], defaultAcl, 0L, -1, 3L, 3L);
        List<String> children = dataTree.getChildren("/parent", null, null);
        assertEquals(2, children.size(), "GetChildren should return all immediate children");
        assertTrue(children.contains("child1"));
        assertTrue(children.contains("child2"));
    }

    @Test
    public void testGetChildrenThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.getChildren("/nonexistent", null, null);
        }, "GetChildren on a missing node should throw NoNodeException");
    }

    @Test
    public void testGetChildrenPopulatesStat() throws Exception {
        dataTree.createNode("/parentstat", new byte[0], defaultAcl, 0L, -1, 888L, 1L);
        Stat stat = new Stat();
        dataTree.getChildren("/parentstat", stat, null);
        assertEquals(888L, stat.getCzxid(), "GetChildren should populate the given stat object");
    }

    @Test
    public void testGetChildrenRegistersWatch() throws Exception {
        dataTree.createNode("/parentwatch", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getChildren("/parentwatch", null, dummyWatcher);
        assertEquals(1, dataTree.getWatchCount(), "GetChildren with a watcher should increment the watch count");
    }

    @Test
    public void testGetAllChildrenNumber() throws Exception {
        dataTree.createNode("/allchildren", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.createNode("/allchildren/c1", new byte[0], defaultAcl, 0L, -1, 2L, 2L);
        dataTree.createNode("/allchildren/c1/c2", new byte[0], defaultAcl, 0L, -1, 3L, 3L);
        assertEquals(2, dataTree.getAllChildrenNumber("/allchildren"), "GetAllChildrenNumber should count nested children");
    }

    @Test
    public void testSetACLSuccessfully() throws Exception {
        dataTree.createNode("/setacl", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        List<ACL> newAcl = new ArrayList<>();
        newAcl.add(new ACL(15, new Id("world", "anyone"))); // Modified permissions
        Stat stat = dataTree.setACL("/setacl", newAcl, 2);
        assertEquals(2, stat.getAversion(), "SetACL should update the aversion to the specified parameter");
    }

    @Test
    public void testSetACLThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.setACL("/nonexistent", defaultAcl, 1);
        }, "Setting ACL on a non-existent node should throw NoNodeException");
    }

    @Test
    public void testGetACLSuccessfully() throws Exception {
        dataTree.createNode("/getacl", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        List<ACL> retrievedAcl = dataTree.getACL("/getacl", new Stat());
        assertEquals(defaultAcl.size(), retrievedAcl.size());
        assertEquals(defaultAcl.get(0).getId().getId(), retrievedAcl.get(0).getId().getId(), "Retrieved ACL should match inserted ACL");
    }

    @Test
    public void testGetACLThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.getACL("/nonexistent", new Stat());
        }, "Getting ACL on a missing node should throw NoNodeException");
    }

    @Test
    public void testGetEphemeralsCount() throws Exception {
        dataTree.createNode("/ephemeral1", new byte[0], defaultAcl, 100L, -1, 1L, 1L);
        dataTree.createNode("/ephemeral2", new byte[0], defaultAcl, 100L, -1, 2L, 2L);
        dataTree.createNode("/ephemeral3", new byte[0], defaultAcl, 200L, -1, 3L, 3L);
        assertEquals(3, dataTree.getEphemeralsCount(), "Total ephemerals count should reflect all ephemerals across sessions");
    }

    @Test
    public void testGetEphemeralsForSession() throws Exception {
        dataTree.createNode("/ephemeral1", new byte[0], defaultAcl, 100L, -1, 1L, 1L);
        dataTree.createNode("/ephemeral2", new byte[0], defaultAcl, 100L, -1, 2L, 2L);
        Set<String> ephemerals = dataTree.getEphemerals(100L);
        assertEquals(2, ephemerals.size(), "Should return only ephemerals for the specified session");
        assertTrue(ephemerals.contains("/ephemeral1"));
        assertTrue(ephemerals.contains("/ephemeral2"));
    }

    @Test
    public void testGetSessionsContainsSession() throws Exception {
        dataTree.createNode("/ephemeral_session", new byte[0], defaultAcl, 500L, -1, 1L, 1L);
        assertTrue(dataTree.getSessions().contains(500L), "Sessions list should contain the owner ID of created ephemeral nodes");
    }

    @Test
    public void testGetContainersNotNull() {
        assertNotNull(dataTree.getContainers(), "GetContainers should never return null");
    }

    @Test
    public void testGetTtlsNotNull() {
        assertNotNull(dataTree.getTtls(), "GetTtls should never return null");
    }

    @Test
    public void testApproximateDataSizePositive() throws Exception {
        long initialSize = dataTree.approximateDataSize();
        dataTree.createNode("/sized", "hello-world-data".getBytes(StandardCharsets.UTF_8), defaultAcl, 0L, -1, 1L, 1L);
        assertTrue(dataTree.approximateDataSize() > initialSize, "Approximate data size should increase after inserting a node");
    }

    @Test
    public void testCachedApproximateDataSizeMatches() {
        assertEquals(dataTree.approximateDataSize(), dataTree.cachedApproximateDataSize(), "Cached size should match computed size initially");
    }

    @Test
    public void testGetWatchCount() throws Exception {
        dataTree.createNode("/watchnode", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/watchnode", new Stat(), dummyWatcher);
        assertEquals(1, dataTree.getWatchCount(), "Watch count should accurately reflect total added watchers");
    }

    @Test
    public void testContainsWatcherData() throws Exception {
        dataTree.createNode("/containswatch", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/containswatch", new Stat(), dummyWatcher);
        assertTrue(dataTree.containsWatcher("/containswatch", WatcherType.Data, dummyWatcher), "ContainsWatcher should return true for registered watcher");
    }

    @Test
    public void testRemoveWatchData() throws Exception {
        dataTree.createNode("/removewatch", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/removewatch", new Stat(), dummyWatcher);
        assertTrue(dataTree.removeWatch("/removewatch", WatcherType.Data, dummyWatcher), "RemoveWatch should return true if watcher was present");
        assertFalse(dataTree.containsWatcher("/removewatch", WatcherType.Data, dummyWatcher), "Watcher should no longer be present after removal");
    }

    @Test
    public void testAclCacheSize() throws Exception {
        dataTree.createNode("/aclcache", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        assertTrue(dataTree.aclCacheSize() > 0, "ACL cache size should be greater than 0 after node creation");
    }

    @Test
    public void testDumpEphemerals() throws Exception {
        dataTree.createNode("/ephemeraldump", new byte[0], defaultAcl, 123L, -1, 1L, 1L);
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        dataTree.dumpEphemerals(pw);
        pw.flush();
        assertTrue(sw.toString().contains("0x7b"), "Dump output should include the hex representation of session ID");
    }

    @Test
    public void testDumpWatches() throws Exception {
        dataTree.createNode("/watchdump", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/watchdump", new Stat(), dummyWatcher);
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        dataTree.dumpWatches(pw, true);
        pw.flush();
        assertTrue(sw.toString().contains("/watchdump"), "Dump watches should output the watch paths");
    }

    @Test
    public void testDumpWatchesSummary() throws Exception {
        dataTree.createNode("/watchsummary", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/watchsummary", new Stat(), dummyWatcher);
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        dataTree.dumpWatchesSummary(pw);
        pw.flush();
        assertFalse(sw.toString().isEmpty(), "Watches summary should not be empty");
    }

    @Test
    public void testGetMaxPrefixWithQuotaNullForRoot() {
        assertNull(dataTree.getMaxPrefixWithQuota("/"), "Max prefix with quota should return null for root without quotas setup");
    }

    @Test
    public void testSetCversionPzxid() throws Exception {
        dataTree.createNode("/cversion", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.setCversionPzxid("/cversion", 10, 50L);
        Stat stat = dataTree.statNode("/cversion", null);
        assertEquals(10, stat.getCversion(), "SetCversionPzxid should update the cversion");
        assertEquals(50L, stat.getPzxid(), "SetCversionPzxid should update the pzxid");
    }

    @Test
    public void testSetCversionPzxidThrowsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.setCversionPzxid("/nonexistent", 10, 50L);
        }, "SetCversionPzxid on missing node should throw NoNodeException");
    }

    @Test
    public void testRemoveCnxn() throws Exception {
        dataTree.createNode("/removecnxn", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/removecnxn", new Stat(), dummyWatcher);
        assertEquals(1, dataTree.getWatchCount(), "Watch count should be 1 after registration");
        dataTree.removeCnxn(dummyWatcher);
        assertEquals(0, dataTree.getWatchCount(), "RemoveCnxn should clear watches for the given connection");
    }

    @Test
    public void testShutdownWatcher() throws Exception {
        dataTree.createNode("/shutdown", new byte[0], defaultAcl, 0L, -1, 1L, 1L);
        dataTree.getData("/shutdown", new Stat(), dummyWatcher);
        dataTree.shutdownWatcher();
        assertNotNull(dataTree, "Tree remains valid after shutting down watchers");
    }

    @Test
    public void testGetAllMapEphemerals() throws Exception {
        dataTree.createNode("/ephemeral_map", new byte[0], defaultAcl, 999L, -1, 1L, 1L);
        assertTrue(dataTree.getEphemerals().containsKey(999L), "The full ephemerals map should contain the session ID");
    }
}