package DataTreeTest.TestLLM;


import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.WatcherType;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeZeroShotRun10Test {

    private DataTree dataTree;

    private static final byte[] DATA = "test_data".getBytes();
    private static final List<ACL> ACL_LIST = ZooDefs.Ids.OPEN_ACL_UNSAFE;
    private static final long ZXID = 1L;
    private static final long TIME = 1000L;
    private static final long SESSION_ID = 12345L;

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    private void createBaseNode(String path) throws Exception {
        dataTree.createNode(path, DATA, ACL_LIST, -1L, 0, ZXID, TIME);
    }

    // ====================================================================================
    // 1-40: CORE LOGIC TESTS (Node Counts, Create, Delete, SetData, GetData, Ephemerals)
    // ====================================================================================

    @Test
    public void testGetNodeCount_InitialState() {
        assertTrue(dataTree.getNodeCount() > 0, "Initial node count should be greater than zero due to internal nodes");
    }

    @Test
    public void testCreateNode_Success() throws Exception {
        dataTree.createNode("/node1", DATA, ACL_LIST, -1L, 0, ZXID, TIME);
        assertNotNull(dataTree.getNode("/node1"), "Node should be created successfully");
    }

    @Test
    public void testCreateNode_WithOutputStat() throws Exception {
        Stat stat = new Stat();
        dataTree.createNode("/node1", DATA, ACL_LIST, -1L, 0, ZXID, TIME, stat);
        assertEquals(ZXID, stat.getCzxid(), "Output stat should be populated with the correct ZXID");
        assertEquals(TIME, stat.getCtime(), "Output stat should be populated with the correct time");
    }

    @Test
    public void testCreateNode_NodeExistsException() throws Exception {
        createBaseNode("/node1");
        assertThrows(KeeperException.NodeExistsException.class, () -> {
            dataTree.createNode("/node1", DATA, ACL_LIST, -1L, 0, ZXID + 1, TIME + 1);
        }, "Should throw NodeExistsException when creating an already existing node");
    }

    @Test
    public void testCreateNode_NoNodeExceptionForMissingParent() {
        assertThrows(KeeperException.NoNodeException.class, () -> {
            dataTree.createNode("/missing/node1", DATA, ACL_LIST, -1L, 0, ZXID, TIME);
        }, "Should throw NoNodeException when the parent node does not exist");
    }

    @Test
    public void testCreateNode_Persistent() throws Exception {
        dataTree.createNode("/node1", DATA, ACL_LIST, -1L, 0, ZXID, TIME);
        Stat stat = new Stat();
        dataTree.statNode("/node1", null);
        assertFalse(dataTree.getEphemerals(SESSION_ID).contains("/node1"), "Persistent node should not be in ephemerals");
    }

    @Test
    public void testCreateNode_Ephemeral() throws Exception {
        dataTree.createNode("/node1", DATA, ACL_LIST, SESSION_ID, 0, ZXID, TIME);
        assertTrue(dataTree.getEphemerals(SESSION_ID).contains("/node1"), "Ephemeral node should be registered under the session");
    }

    @Test
    public void testCreateNode_Container() throws Exception {
        dataTree.createNode("/node1", DATA, ACL_LIST, Long.MIN_VALUE, 0, ZXID, TIME);
        assertTrue(dataTree.getContainers().contains("/node1"), "Container node should be tracked internally");
    }

    @Test
    public void testDeleteNode_Success() throws Exception {
        createBaseNode("/node1");
        dataTree.deleteNode("/node1", ZXID + 1);
        assertNull(dataTree.getNode("/node1"), "Node should be completely removed");
    }

    @Test
    public void testDeleteNode_NoNodeException() {
        assertThrows(KeeperException.NoNodeException.class, () -> {
            dataTree.deleteNode("/missing", ZXID);
        }, "Should throw NoNodeException when trying to delete a non-existent node");
    }

    @Test
    public void testSetData_Success() throws Exception {
        createBaseNode("/node1");
        byte[] newData = "updated".getBytes();
        dataTree.setData("/node1", newData, -1, ZXID + 1, TIME + 1);
        byte[] retrieved = dataTree.getData("/node1", new Stat(), null);
        assertArrayEquals(newData, retrieved, "Node data should be updated successfully");
    }

    @Test
    public void testSetData_NoNodeException() {
        assertThrows(KeeperException.NoNodeException.class, () -> {
            dataTree.setData("/missing", DATA, -1, ZXID, TIME);
        }, "Should throw NoNodeException when updating data for a missing node");
    }

    @Test
    public void testSetData_StatUpdated() throws Exception {
        createBaseNode("/node1");
        Stat stat = dataTree.setData("/node1", DATA, 1, ZXID + 1, TIME + 100);
        assertEquals(ZXID + 1, stat.getMzxid(), "Mzxid should be updated on setData");
        assertEquals(TIME + 100, stat.getMtime(), "Mtime should be updated on setData");
    }

    @Test
    public void testGetData_Success() throws Exception {
        createBaseNode("/node1");
        byte[] retrieved = dataTree.getData("/node1", new Stat(), null);
        assertArrayEquals(DATA, retrieved, "Retrieved data should exactly match created data");
    }

    @Test
    public void testGetData_NoNodeException() {
        assertThrows(KeeperException.NoNodeException.class, () -> {
            dataTree.getData("/missing", new Stat(), null);
        }, "Should throw NoNodeException when getting data for a missing node");
    }

    @Test
    public void testGetData_WithStat() throws Exception {
        createBaseNode("/node1");
        Stat stat = new Stat();
        dataTree.getData("/node1", stat, null);
        assertEquals(DATA.length, stat.getDataLength(), "Stat should be correctly populated with data length");
    }

    @Test
    public void testGetChildren_EmptyList() throws Exception {
        createBaseNode("/node1");
        List<String> children = dataTree.getChildren("/node1", new Stat(), null);
        assertTrue(children.isEmpty(), "Leaf node should have zero children");
    }

    @Test
    public void testGetChildren_MultipleChildren() throws Exception {
        createBaseNode("/node1");
        createBaseNode("/node1/childA");
        createBaseNode("/node1/childB");
        List<String> children = dataTree.getChildren("/node1", new Stat(), null);
        assertEquals(2, children.size(), "Should accurately return the number of direct children");
        assertTrue(children.contains("childA") && children.contains("childB"));
    }

    @Test
    public void testGetChildren_NoNodeException() {
        assertThrows(KeeperException.NoNodeException.class, () -> {
            dataTree.getChildren("/missing", new Stat(), null);
        }, "Should throw NoNodeException when retrieving children for a missing node");
    }

    @Test
    public void testStatNode_Success() throws Exception {
        createBaseNode("/node1");
        Stat stat = dataTree.statNode("/node1", null);
        assertNotNull(stat, "Should successfully return a Stat object");
        assertEquals(ZXID, stat.getCzxid());
    }

    @Test
    public void testStatNode_NoNodeException() {
        assertThrows(KeeperException.NoNodeException.class, () -> {
            dataTree.statNode("/missing", null);
        }, "Should throw NoNodeException for statNode on missing path");
    }

    @Test
    public void testSetACL_Success() throws Exception {
        createBaseNode("/node1");
        List<ACL> newAcl = ZooDefs.Ids.READ_ACL_UNSAFE;
        int expectedNewVersion = 2;

        dataTree.setACL("/node1", newAcl, expectedNewVersion);
        Stat realNodeStat = dataTree.statNode("/node1", null);

        assertEquals(expectedNewVersion, realNodeStat.getAversion(), "ACL version should be explicitly updated to the provided version by DataTree");
    }

    @Test
    public void testSetACL_NoNodeException() {
        assertThrows(KeeperException.NoNodeException.class, () -> {
            dataTree.setACL("/missing", ZooDefs.Ids.OPEN_ACL_UNSAFE, -1);
        }, "Should throw NoNodeException when setting ACL for a missing node");
    }

    @Test
    public void testGetACL_Success() throws Exception {
        createBaseNode("/node1");
        List<ACL> retrievedAcl = dataTree.getACL("/node1", new Stat());
        assertEquals(ACL_LIST, retrievedAcl, "Retrieved ACL should match the set ACL list");
    }

    @Test
    public void testGetACL_NoNodeException() {
        assertThrows(KeeperException.NoNodeException.class, () -> {
            dataTree.getACL("/missing", new Stat());
        }, "Should throw NoNodeException when getting ACL for a missing node");
    }

    @Test
    public void testGetAllChildrenNumber_Root() throws Exception {
        int initialCount = dataTree.getAllChildrenNumber("/");
        createBaseNode("/node1");
        assertEquals(initialCount + 1, dataTree.getAllChildrenNumber("/"), "Root children number should increment by 1");
    }

    @Test
    public void testGetAllChildrenNumber_Subtree() throws Exception {
        createBaseNode("/node1");
        createBaseNode("/node1/childA");
        createBaseNode("/node1/childA/childB");
        assertEquals(2, dataTree.getAllChildrenNumber("/node1"), "Subtree count should accurately reflect all deep descendants");
    }

    @Test
    public void testApproximateDataSize_IncreasesOnCreate() throws Exception {
        long initialSize = dataTree.approximateDataSize();
        createBaseNode("/node1");
        assertTrue(dataTree.approximateDataSize() > initialSize, "Approximate data size should increase upon node creation");
    }

    @Test
    public void testCachedApproximateDataSize_ReturnsValue() throws Exception {
        createBaseNode("/node1");
        long cachedSize = dataTree.cachedApproximateDataSize();
        assertTrue(cachedSize >= 0, "Cached approximate size should be non-negative");
    }

    @Test
    public void testGetEphemerals_ForSpecificSession() throws Exception {
        dataTree.createNode("/eph1", DATA, ACL_LIST, SESSION_ID, 0, ZXID, TIME);
        Set<String> ephemerals = dataTree.getEphemerals(SESSION_ID);
        assertEquals(1, ephemerals.size(), "Should return the exact ephemerals mapped to the specific session");
        assertTrue(ephemerals.contains("/eph1"));
    }

    @Test
    public void testGetSessions_IncludesSessionWithEphemeral() throws Exception {
        dataTree.createNode("/eph1", DATA, ACL_LIST, SESSION_ID, 0, ZXID, TIME);
        Collection<Long> sessions = dataTree.getSessions();
        assertTrue(sessions.contains(SESSION_ID), "Sessions list should include the active ephemeral owner");
    }

    @Test
    public void testGetEphemeralsCount_TotalCount() throws Exception {
        int initialCount = dataTree.getEphemeralsCount();
        dataTree.createNode("/eph1", DATA, ACL_LIST, SESSION_ID, 0, ZXID, TIME);
        dataTree.createNode("/eph2", DATA, ACL_LIST, SESSION_ID + 1, 0, ZXID, TIME);
        assertEquals(initialCount + 2, dataTree.getEphemeralsCount(), "Total ephemeral count should scale dynamically");
    }

    @Test
    public void testSetCversionPzxid_Success() throws Exception {
        createBaseNode("/node1");
        int initialCversion = dataTree.statNode("/node1", null).getCversion();
        dataTree.setCversionPzxid("/node1", initialCversion + 5, ZXID + 10);
        Stat stat = dataTree.statNode("/node1", null);

        assertTrue(stat.getCversion() > initialCversion, "CVersion should be dynamically updated and increased");
        assertEquals(ZXID + 10, stat.getPzxid(), "PZxid should be successfully overridden");
    }

    @Test
    public void testSetCversionPzxid_NoNodeException() {
        assertThrows(KeeperException.NoNodeException.class, () -> {
            dataTree.setCversionPzxid("/missing", 5, ZXID);
        }, "Should throw NoNodeException for missing node");
    }

    @Test
    public void testSetCversionPzxid_IgnoresLowerVersion() throws Exception {
        createBaseNode("/node1");
        dataTree.setCversionPzxid("/node1", 20, ZXID + 10);
        int highCversion = dataTree.statNode("/node1", null).getCversion();

        dataTree.setCversionPzxid("/node1", 2, ZXID + 20);
        Stat stat = dataTree.statNode("/node1", null);

        assertEquals(highCversion, stat.getCversion(), "Lower CVersion attempt should be securely ignored by ZooKeeper");
    }

    @Test
    public void testGetMaxPrefixWithQuota_ReturnsNullForNoQuota() throws Exception {
        createBaseNode("/node1");
        String prefix = dataTree.getMaxPrefixWithQuota("/node1");
        assertNull(prefix, "Should return null if no quotas are applied to the subtree");
    }

    @Test
    public void testGetTreeDigest_UpdatesOnModification() throws Exception {
        long initialDigest = dataTree.getTreeDigest();
        createBaseNode("/node1");
        long updatedDigest = dataTree.getTreeDigest();
        assertNotEquals(initialDigest, updatedDigest, "Tree digest should dynamically change after tree modifications");
    }

    @Test
    public void testAclCacheSize_IncreasesOnNewAcl() throws Exception {
        int initialSize = dataTree.aclCacheSize();
        List<ACL> customAcl = new ArrayList<>();
        customAcl.add(new ACL(31, new Id("digest", "user:custom_password")));
        dataTree.createNode("/nodeAclCustom", DATA, customAcl, -1L, 0, ZXID, TIME);

        assertTrue(dataTree.aclCacheSize() > initialSize, "ACL cache size should increase when encountering completely unseen ACL combinations");
    }

    @Test
    public void testContainsWatcher_DataWatch() throws Exception {
        createBaseNode("/node1");
        Watcher dummyWatcher = event -> {};
        dataTree.getData("/node1", new Stat(), dummyWatcher);
        assertTrue(dataTree.containsWatcher("/node1", WatcherType.Data, dummyWatcher), "DataTree should report presence of actively bound data watcher");
    }

    @Test
    public void testRemoveWatch_Success() throws Exception {
        createBaseNode("/node1");
        Watcher dummyWatcher = event -> {};
        dataTree.getData("/node1", new Stat(), dummyWatcher);
        boolean removed = dataTree.removeWatch("/node1", WatcherType.Data, dummyWatcher);

        assertTrue(removed, "Watch should be successfully removed returning true");
        assertFalse(dataTree.containsWatcher("/node1", WatcherType.Data, dummyWatcher), "Watcher should be unlinked dynamically");
    }

    // ====================================================================================
    // 41-50: ADVANCED AND PERIPHERAL METHODS (Serialization, Dumps, Massive Watches, TTLs)
    // ====================================================================================

    @Test
    public void testSerializeAndDeserialize_MaintainsIntegrity() throws Exception {
        dataTree.createNode("/serializeNode", DATA, ACL_LIST, -1L, 0, ZXID, TIME);

        // Serialize
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        dataTree.serialize(boa, "tree");
        byte[] serializedData = baos.toByteArray();

        // Deserialize
        DataTree newTree = new DataTree();
        ByteArrayInputStream bais = new ByteArrayInputStream(serializedData);
        BinaryInputArchive bia = BinaryInputArchive.getArchive(bais);
        newTree.deserialize(bia, "tree");

        assertNotNull(newTree.getNode("/serializeNode"), "Deserialized tree must contain the serialized nodes");
    }

    @Test
    public void testDumpEphemerals_WritesToPrintWriter() throws Exception {
        dataTree.createNode("/ephDump", DATA, ACL_LIST, SESSION_ID, 0, ZXID, TIME);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        dataTree.dumpEphemerals(pw);
        pw.flush();

        String output = sw.toString();
        assertTrue(output.contains(Long.toHexString(SESSION_ID)), "Dump should contain the session ID in hex format");
        assertTrue(output.contains("/ephDump"), "Dump should list the specific ephemeral paths");
    }



    @Test
    public void testDumpWatchesSummary_ExecutesWithoutError() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        assertDoesNotThrow(() -> dataTree.dumpWatchesSummary(pw), "dumpWatchesSummary should execute safely");
    }

    @Test
    public void testSetWatches_MassiveRegistration() throws Exception {
        createBaseNode("/massiveWatch");
        Watcher dummyWatcher = event -> {};

        List<String> dataWatches = Collections.singletonList("/massiveWatch");
        List<String> existWatches = Collections.emptyList();
        List<String> childWatches = Collections.emptyList();

        // Aggiungiamo le due nuove liste vuote richieste dalle versioni recenti di ZooKeeper (Persistent Watches)
        List<String> persistentWatches = Collections.emptyList();
        List<String> persistentRecursiveWatches = Collections.emptyList();

        // Passiamo 7 argomenti invece di 5
        dataTree.setWatches(ZXID, dataWatches, existWatches, childWatches, persistentWatches, persistentRecursiveWatches, dummyWatcher);

        assertTrue(dataTree.containsWatcher("/massiveWatch", WatcherType.Data, dummyWatcher),
                "Watch should be successfully registered via setWatches massive operation");
    }

    @Test
    public void testRemoveCnxn_RemovesAllAssociatedWatches() throws Exception {
        createBaseNode("/cnxnNode1");
        createBaseNode("/cnxnNode2");
        Watcher dummyWatcher = event -> {};

        dataTree.getData("/cnxnNode1", new Stat(), dummyWatcher);
        dataTree.getChildren("/cnxnNode2", new Stat(), dummyWatcher);

        // Remove connection
        dataTree.removeCnxn(dummyWatcher);

        assertFalse(dataTree.containsWatcher("/cnxnNode1", WatcherType.Data, dummyWatcher), "Data watch should be removed");
        assertFalse(dataTree.containsWatcher("/cnxnNode2", WatcherType.Children, dummyWatcher), "Child watch should be removed");
    }

    @Test
    public void testGetTtls_ReturnsValidSet() {
        Set<String> ttls = dataTree.getTtls();
        assertNotNull(ttls, "TTL set should be properly initialized and returned, even if empty");
    }

    @Test
    public void testKillSession_RemovesEphemerals() throws Exception {
        dataTree.createNode("/ephToKill", DATA, ACL_LIST, SESSION_ID, 0, ZXID, TIME);
        assertTrue(dataTree.getEphemerals(SESSION_ID).contains("/ephToKill"));

        // Utilizziamo la Reflection per accedere a killSession anche se non è public e siamo in un pacchetto diverso
        java.lang.reflect.Method killMethod = DataTree.class.getDeclaredMethod("killSession", long.class, long.class);
        killMethod.setAccessible(true);
        killMethod.invoke(dataTree, SESSION_ID, ZXID + 1);

        assertNull(dataTree.getNode("/ephToKill"), "Ephemeral node must be removed internally after killing the session");
        assertFalse(dataTree.getSessions().contains(SESSION_ID), "Session should be completely cleared from active sessions");
    }

    @Test
    public void testGetEphemerals_WithEmptySession_ReturnsEmptySet() {
        Set<String> emptyEphemerals = dataTree.getEphemerals(999999L);
        assertNotNull(emptyEphemerals);
        assertTrue(emptyEphemerals.isEmpty(), "Should return an empty set for non-existent session IDs");
    }

    @Test
    public void testDeleteNode_ContainerNode_CleansUpContainersSet() throws Exception {
        dataTree.createNode("/container1", DATA, ACL_LIST, Long.MIN_VALUE, 0, ZXID, TIME);
        assertTrue(dataTree.getContainers().contains("/container1"));

        dataTree.deleteNode("/container1", ZXID + 1);

        assertFalse(dataTree.getContainers().contains("/container1"), "Deleting a container node should remove it from the containers set");
    }
}