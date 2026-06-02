package DataTreeTest.TestLLM.C3;

import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.EphemeralType;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeTotCodeFinalTest {

    private DataTree dataTree;
    private List<ACL> defaultAcl;
    private DummyWatcher dummyWatcher;

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
        defaultAcl = ZooDefs.Ids.OPEN_ACL_UNSAFE;
        dummyWatcher = new DummyWatcher();
    }

    // --- State and Initialization Tests ---

    @Test
    public void testInitialNodeCount() {
        // Root, /zookeeper, /zookeeper/quota, /zookeeper/config
        assertTrue(dataTree.getNodeCount() >= 4, "Initial node count should contain special internal nodes");
    }

    @Test
    public void testAddConfigNodeDoesNotThrow() {
        assertDoesNotThrow(() -> dataTree.addConfigNode());
    }

    // --- Create Node Tests ---

    @Test
    public void testCreateNode_TypicalUseCase() throws Exception {
        dataTree.createNode("/typicalNode", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        assertNotNull(dataTree.getNode("/typicalNode"));
    }

    @Test
    public void testCreateNode_PopulatesOutputStat() throws Exception {
        Stat stat = new Stat();
        dataTree.createNode("/statNode", "data".getBytes(), defaultAcl, 0, -1, 1, 1, stat);
        assertEquals(1, stat.getCzxid());
        assertEquals(1, stat.getMzxid());
    }

    @Test
    public void testCreateNode_NodeExistsException() throws Exception {
        dataTree.createNode("/duplicateNode", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        assertThrows(NodeExistsException.class, () -> {
            dataTree.createNode("/duplicateNode", "data2".getBytes(), defaultAcl, 0, -1, 2, 2);
        });
    }

    @Test
    public void testCreateNode_NoNodeExceptionForMissingParent() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.createNode("/missingParent/child", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        });
    }

    // --- Ephemeral, Container, and TTL Node Tests ---

    @Test
    public void testCreateNode_Ephemeral() throws Exception {
        long sessionId = 999L;
        dataTree.createNode("/ephemeralNode", "data".getBytes(), defaultAcl, sessionId, -1, 1, 1);
        Set<String> ephemerals = dataTree.getEphemerals(sessionId);
        assertTrue(ephemerals.contains("/ephemeralNode"));
        assertEquals(1, dataTree.getEphemeralsCount());
        assertTrue(dataTree.getSessions().contains(sessionId));
    }

    @Test
    public void testCreateNode_Container() throws Exception {
        dataTree.createNode("/containerNode", "data".getBytes(), defaultAcl, EphemeralType.CONTAINER_EPHEMERAL_OWNER, -1, 1, 1);
        assertTrue(dataTree.getContainers().contains("/containerNode"));
    }

    @Test
    public void testCreateNode_TTL() throws Exception {
        long ttlOwner = EphemeralType.TTL.toEphemeralOwner(10000);
        dataTree.createNode("/ttlNode", "data".getBytes(), defaultAcl, ttlOwner, -1, 1, 1);
        assertTrue(dataTree.getTtls().contains("/ttlNode"));
    }

    // --- Delete Node Tests ---

    @Test
    public void testDeleteNode_TypicalUseCase() throws Exception {
        dataTree.createNode("/deleteMe", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        dataTree.deleteNode("/deleteMe", 2);
        assertThrows(NoNodeException.class, () -> dataTree.getData("/deleteMe", new Stat(), null));
    }

    @Test
    public void testDeleteNode_NoNodeException() {
        assertThrows(NoNodeException.class, () -> dataTree.deleteNode("/nonExistent", 1));
    }

    @Test
    public void testDeleteNode_RemovesEphemeral() throws Exception {
        long sessionId = 777L;
        dataTree.createNode("/ephemeralDelete", "data".getBytes(), defaultAcl, sessionId, -1, 1, 1);
        dataTree.deleteNode("/ephemeralDelete", 2);
        assertFalse(dataTree.getEphemerals(sessionId).contains("/ephemeralDelete"));
    }

    // --- SetData and GetData Tests ---

    @Test
    public void testSetData_TypicalUseCase() throws Exception {
        dataTree.createNode("/setDataNode", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        Stat stat = dataTree.setData("/setDataNode", "newData".getBytes(), -1, 2, 2);
        assertEquals(2, stat.getMzxid());
    }

    @Test
    public void testSetData_NoNodeException() {
        assertThrows(NoNodeException.class, () -> dataTree.setData("/nonExistent", "data".getBytes(), -1, 1, 1));
    }

    @Test
    public void testGetData_TypicalUseCase() throws Exception {
        byte[] expectedData = "testData".getBytes();
        dataTree.createNode("/getDataNode", expectedData, defaultAcl, 0, -1, 1, 1);
        Stat stat = new Stat();
        byte[] actualData = dataTree.getData("/getDataNode", stat, null);
        assertArrayEquals(expectedData, actualData);
    }

    @Test
    public void testGetData_NoNodeException() {
        assertThrows(NoNodeException.class, () -> dataTree.getData("/nonExistent", new Stat(), null));
    }

    // --- Stat and Children Tests ---

    @Test
    public void testStatNode_TypicalUseCase() throws Exception {
        dataTree.createNode("/statCheckNode", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        Stat stat = dataTree.statNode("/statCheckNode", null);
        assertNotNull(stat);
        assertEquals(1, stat.getCzxid());
    }

    @Test
    public void testStatNode_NoNodeException() {
        assertThrows(NoNodeException.class, () -> dataTree.statNode("/nonExistent", null));
    }

    @Test
    public void testGetChildren_TypicalUseCase() throws Exception {
        dataTree.createNode("/parent", "".getBytes(), defaultAcl, 0, -1, 1, 1);
        dataTree.createNode("/parent/child1", "".getBytes(), defaultAcl, 0, -1, 2, 2);
        List<String> children = dataTree.getChildren("/parent", new Stat(), null);
        assertEquals(1, children.size());
        assertTrue(children.contains("child1"));
    }

    @Test
    public void testGetChildren_NoNodeException() {
        assertThrows(NoNodeException.class, () -> dataTree.getChildren("/nonExistent", new Stat(), null));
    }

    @Test
    public void testGetAllChildrenNumber_Root() {
        assertTrue(dataTree.getAllChildrenNumber("/") >= 0);
    }

    @Test
    public void testGetAllChildrenNumber_SpecificNode() throws Exception {
        dataTree.createNode("/parentLeaf", "".getBytes(), defaultAcl, 0, -1, 1, 1);
        assertEquals(0, dataTree.getAllChildrenNumber("/parentLeaf"));
    }

    // --- ACL Tests ---

    @Test
    public void testSetACL_TypicalUseCase() throws Exception {
        dataTree.createNode("/aclNode", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        Stat stat = dataTree.setACL("/aclNode", defaultAcl, -1);
        assertNotNull(stat);
    }

    @Test
    public void testSetACL_NoNodeException() {
        assertThrows(NoNodeException.class, () -> dataTree.setACL("/nonExistent", defaultAcl, -1));
    }

    @Test
    public void testGetACL_TypicalUseCase() throws Exception {
        dataTree.createNode("/getAclNode", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        List<ACL> retrievedAcl = dataTree.getACL("/getAclNode", new Stat());
        assertFalse(retrievedAcl.isEmpty());
    }

    @Test
    public void testGetACL_NoNodeException() {
        assertThrows(NoNodeException.class, () -> dataTree.getACL("/nonExistent", new Stat()));
    }

    @Test
    public void testAclCacheSize() throws Exception {
        int initialSize = dataTree.aclCacheSize();
        dataTree.createNode("/aclCacheNode", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        assertTrue(dataTree.aclCacheSize() >= initialSize);
    }

    // --- Size and Metrics Tests ---

    @Test
    public void testApproximateDataSize() throws Exception {
        long initialSize = dataTree.approximateDataSize();
        dataTree.createNode("/sizeNode", "12345".getBytes(), defaultAcl, 0, -1, 1, 1);
        assertTrue(dataTree.approximateDataSize() > initialSize);
    }

    @Test
    public void testCachedApproximateDataSize() throws Exception {
        long initialSize = dataTree.cachedApproximateDataSize();
        dataTree.createNode("/cachedSizeNode", "12345".getBytes(), defaultAcl, 0, -1, 1, 1);
        assertTrue(dataTree.cachedApproximateDataSize() > initialSize);
    }

    @Test
    public void testGetMaxPrefixWithQuota_NoQuota() {
        assertNull(dataTree.getMaxPrefixWithQuota("/nonExistentQuota"));
    }

    // --- Cversion and State Operations ---

    @Test
    public void testSetCversionPzxid_TypicalUseCase() throws Exception {
        dataTree.createNode("/cversionNode", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        dataTree.setCversionPzxid("/cversionNode", 10, 5);
        Stat stat = dataTree.statNode("/cversionNode", null);
        assertEquals(10, stat.getCversion());
        assertEquals(5, stat.getPzxid());
    }

    @Test
    public void testSetCversionPzxid_NoNodeException() {
        assertThrows(NoNodeException.class, () -> dataTree.setCversionPzxid("/nonExistent", 5, 2));
    }

    // --- Watcher Tests ---

    @Test
    public void testAddWatch_IncreasesWatchCount() throws Exception {
        int initialWatches = dataTree.getWatchCount();
        dataTree.createNode("/watchNode", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        dataTree.addWatch("/watchNode", dummyWatcher, 1);
        assertTrue(dataTree.getWatchCount() > initialWatches);
    }

    @Test
    public void testContainsWatcher_ReturnsTrue() throws Exception {
        dataTree.createNode("/containsWatchNode", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        dataTree.addWatch("/containsWatchNode", dummyWatcher, 1);
        assertTrue(dataTree.containsWatcher("/containsWatchNode", Watcher.WatcherType.Data, dummyWatcher));
    }

    @Test
    public void testRemoveWatch_ReturnsTrue() throws Exception {
        dataTree.createNode("/removeWatchNode", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        dataTree.addWatch("/removeWatchNode", dummyWatcher, 1);
        assertTrue(dataTree.removeWatch("/removeWatchNode", Watcher.WatcherType.Data, dummyWatcher));
    }

    // --- Transaction Processing Tests ---

    @Test
    public void testProcessTxn_CreateOpCode() {
        TxnHeader header = new TxnHeader(1, 1, 1, 1, ZooDefs.OpCode.create);
        CreateTxn createTxn = new CreateTxn("/txnCreate", "data".getBytes(), defaultAcl, false, 0);
        DataTree.ProcessTxnResult result = dataTree.processTxn(header, createTxn);

        assertEquals(0, result.err);
        assertEquals("/txnCreate", result.path);
        assertNotNull(dataTree.getNode("/txnCreate"));
    }

    @Test
    public void testProcessTxn_DeleteOpCode() throws Exception {
        dataTree.createNode("/txnDelete", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        TxnHeader header = new TxnHeader(1, 1, 2, 2, ZooDefs.OpCode.delete);
        DeleteTxn deleteTxn = new DeleteTxn("/txnDelete");
        DataTree.ProcessTxnResult result = dataTree.processTxn(header, deleteTxn);

        assertEquals(0, result.err);
        assertNull(dataTree.getNode("/txnDelete"));
    }

    @Test
    public void testProcessTxn_SetDataOpCode() throws Exception {
        dataTree.createNode("/txnSetData", "data".getBytes(), defaultAcl, 0, -1, 1, 1);
        TxnHeader header = new TxnHeader(1, 1, 2, 2, ZooDefs.OpCode.setData);
        SetDataTxn setDataTxn = new SetDataTxn("/txnSetData", "newData".getBytes(), -1);
        DataTree.ProcessTxnResult result = dataTree.processTxn(header, setDataTxn);

        assertEquals(0, result.err);

        Stat stat = new Stat();
        byte[] actualData = dataTree.getData("/txnSetData", stat, null);
        assertArrayEquals("newData".getBytes(), actualData);
    }

    // --- Utility and Deprecated Methods ---

    @Test
    public void testCopyStatPersisted_Deprecated() {
        StatPersisted from = new StatPersisted();
        from.setCzxid(100L);
        StatPersisted to = new StatPersisted();
        DataTree.copyStatPersisted(from, to);
        assertEquals(100L, to.getCzxid());
    }

    @Test
    public void testCopyStat_Deprecated() {
        Stat from = new Stat();
        from.setCzxid(200L);
        Stat to = new Stat();
        DataTree.copyStat(from, to);
        assertEquals(200L, to.getCzxid());
    }

    @Test
    public void testCreateStat_StaticMethod() {
        StatPersisted stat = DataTree.createStat(123L, 456L, 789L);
        assertEquals(123L, stat.getCzxid());
        assertEquals(456L, stat.getCtime());
        assertEquals(789L, stat.getEphemeralOwner());
    }

    @Test
    public void testGetTreeDigest() {
        assertDoesNotThrow(() -> dataTree.getTreeDigest());
    }

    @Test
    public void testGetDigestLog() {
        assertNotNull(dataTree.getDigestLog());
    }

    // --- Dummy Watcher Implementation for Tests ---

    private static class DummyWatcher implements Watcher {
        @Override
        public void process(WatchedEvent event) {
            // No-op for tests
        }
    }
}