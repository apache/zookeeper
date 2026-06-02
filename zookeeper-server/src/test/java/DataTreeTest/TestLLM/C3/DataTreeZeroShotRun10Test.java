package DataTreeTest.TestLLM.C3;

import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeZeroShotRun10Test {

    private DataTree dataTree;
    private List<ACL> acl;

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
        acl = ZooDefs.Ids.OPEN_ACL_UNSAFE;
    }

    @Test
    public void testNodeCountAtInitialization() {
        assertTrue(dataTree.getNodeCount() >= 4, "Initial node count should be at least 4 for root and special paths");
    }

    @Test
    public void testAddConfigNodeDoesNotThrow() {
        assertDoesNotThrow(() -> dataTree.addConfigNode());
    }

    @Test
    public void testCreateNode() throws Exception {
        dataTree.createNode("/testNode", "data".getBytes(), acl, 0, -1, 1, 1);
        assertNotNull(dataTree.getNode("/testNode"));
    }

    @Test
    public void testCreateNodePopulatesStat() throws Exception {
        Stat stat = new Stat();
        dataTree.createNode("/testStat", "data".getBytes(), acl, 0, -1, 1, 1, stat);
        assertEquals(1, stat.getCzxid());
    }

    @Test
    public void testCreateNodeThrowsNodeExists() throws Exception {
        dataTree.createNode("/testExists", "data".getBytes(), acl, 0, -1, 1, 1);
        assertThrows(NodeExistsException.class, () -> {
            dataTree.createNode("/testExists", "data2".getBytes(), acl, 0, -1, 2, 2);
        });
    }

    @Test
    public void testCreateNodeThrowsNoNodeForMissingParent() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.createNode("/missing/child", "data".getBytes(), acl, 0, -1, 1, 1);
        });
    }

    @Test
    public void testDeleteNode() throws Exception {
        dataTree.createNode("/testDelete", "data".getBytes(), acl, 0, -1, 1, 1);
        dataTree.deleteNode("/testDelete", 2);
        assertNull(dataTree.getNode("/testDelete"));
    }

    @Test
    public void testDeleteNodeThrowsNoNode() {
        assertThrows(NoNodeException.class, () -> dataTree.deleteNode("/nonExistent", 1));
    }

    @Test
    public void testSetData() throws Exception {
        dataTree.createNode("/testSetData", "data".getBytes(), acl, 0, -1, 1, 1);
        Stat stat = dataTree.setData("/testSetData", "newData".getBytes(), -1, 2, 2);
        assertEquals(2, stat.getMzxid());
    }

    @Test
    public void testSetDataThrowsNoNode() {
        assertThrows(NoNodeException.class, () -> dataTree.setData("/nonExistent", "data".getBytes(), -1, 1, 1));
    }

    @Test
    public void testGetData() throws Exception {
        byte[] data = "testData".getBytes();
        dataTree.createNode("/testGetData", data, acl, 0, -1, 1, 1);
        Stat stat = new Stat();
        byte[] retrieved = dataTree.getData("/testGetData", stat, null);
        assertArrayEquals(data, retrieved);
    }

    @Test
    public void testGetDataThrowsNoNode() {
        assertThrows(NoNodeException.class, () -> dataTree.getData("/nonExistent", new Stat(), null));
    }

    @Test
    public void testStatNode() throws Exception {
        dataTree.createNode("/testStatNode", "data".getBytes(), acl, 0, -1, 1, 1);
        Stat stat = dataTree.statNode("/testStatNode", null);
        assertNotNull(stat);
    }

    @Test
    public void testStatNodeThrowsNoNode() {
        assertThrows(NoNodeException.class, () -> dataTree.statNode("/nonExistent", null));
    }

    @Test
    public void testGetChildren() throws Exception {
        dataTree.createNode("/parent", "".getBytes(), acl, 0, -1, 1, 1);
        dataTree.createNode("/parent/child1", "".getBytes(), acl, 0, -1, 2, 2);
        List<String> children = dataTree.getChildren("/parent", new Stat(), null);
        assertTrue(children.contains("child1"));
    }

    @Test
    public void testGetChildrenThrowsNoNode() {
        assertThrows(NoNodeException.class, () -> dataTree.getChildren("/nonExistent", new Stat(), null));
    }

    @Test
    public void testGetAllChildrenNumberRoot() {
        assertTrue(dataTree.getAllChildrenNumber("/") >= 0);
    }

    @Test
    public void testGetAllChildrenNumberForNewNode() throws Exception {
        dataTree.createNode("/parentLeaf", "".getBytes(), acl, 0, -1, 1, 1);
        assertEquals(0, dataTree.getAllChildrenNumber("/parentLeaf"));
    }

    @Test
    public void testSetACL() throws Exception {
        dataTree.createNode("/testAcl", "data".getBytes(), acl, 0, -1, 1, 1);
        Stat stat = dataTree.setACL("/testAcl", acl, -1);
        assertNotNull(stat);
    }

    @Test
    public void testSetACLThrowsNoNode() {
        assertThrows(NoNodeException.class, () -> dataTree.setACL("/nonExistent", acl, -1));
    }

    @Test
    public void testGetACL() throws Exception {
        dataTree.createNode("/testGetAcl", "data".getBytes(), acl, 0, -1, 1, 1);
        List<ACL> retrievedAcl = dataTree.getACL("/testGetAcl", new Stat());
        assertFalse(retrievedAcl.isEmpty());
    }

    @Test
    public void testGetACLThrowsNoNode() {
        assertThrows(NoNodeException.class, () -> dataTree.getACL("/nonExistent", new Stat()));
    }

    @Test
    public void testGetACLWithStatPopulatesStat() throws Exception {
        dataTree.createNode("/testGetAclStat", "data".getBytes(), acl, 0, -1, 1, 1);
        Stat stat = new Stat();
        dataTree.getACL("/testGetAclStat", stat);
        assertEquals(1, stat.getCzxid());
    }

    @Test
    public void testAclCacheSize() throws Exception {
        int initialSize = dataTree.aclCacheSize();
        dataTree.createNode("/testAclCache", "data".getBytes(), acl, 0, -1, 1, 1);
        assertTrue(dataTree.aclCacheSize() >= initialSize);
    }

    @Test
    public void testApproximateDataSize() {
        assertTrue(dataTree.approximateDataSize() >= 0);
    }

    @Test
    public void testCachedApproximateDataSize() {
        assertTrue(dataTree.cachedApproximateDataSize() >= 0);
    }

    @Test
    public void testCreateNodeIncreasesDataSize() throws Exception {
        long initialSize = dataTree.cachedApproximateDataSize();
        dataTree.createNode("/testSize", "12345".getBytes(), acl, 0, -1, 1, 1);
        assertTrue(dataTree.cachedApproximateDataSize() > initialSize);
    }

    @Test
    public void testDeleteNodeDecreasesDataSize() throws Exception {
        dataTree.createNode("/testDeleteSize", "12345".getBytes(), acl, 0, -1, 1, 1);
        long sizeAfterCreate = dataTree.cachedApproximateDataSize();
        dataTree.deleteNode("/testDeleteSize", 2);
        assertTrue(dataTree.cachedApproximateDataSize() < sizeAfterCreate);
    }

    @Test
    public void testSetDataChangesDataSize() throws Exception {
        dataTree.createNode("/testSetSize", "12".getBytes(), acl, 0, -1, 1, 1);
        long sizeAfterCreate = dataTree.cachedApproximateDataSize();
        dataTree.setData("/testSetSize", "1234567890".getBytes(), -1, 2, 2);
        assertTrue(dataTree.cachedApproximateDataSize() > sizeAfterCreate);
    }

    @Test
    public void testGetEphemeralsCountInitially() {
        assertEquals(0, dataTree.getEphemeralsCount());
    }

    @Test
    public void testGetEphemeralsCountAfterCreate() throws Exception {
        long sessionId = 12345L;
        dataTree.createNode("/testEphemeral", "data".getBytes(), acl, sessionId, -1, 1, 1);
        assertEquals(1, dataTree.getEphemeralsCount());
    }

    @Test
    public void testGetSessionsInitiallyEmpty() {
        assertTrue(dataTree.getSessions().isEmpty());
    }

    @Test
    public void testGetSessionsAfterCreate() throws Exception {
        long sessionId = 12345L;
        dataTree.createNode("/testSession", "data".getBytes(), acl, sessionId, -1, 1, 1);
        assertTrue(dataTree.getSessions().contains(sessionId));
    }

    @Test
    public void testGetEphemeralsBySessionId() throws Exception {
        long sessionId = 12345L;
        dataTree.createNode("/testEphmSession", "data".getBytes(), acl, sessionId, -1, 1, 1);
        Set<String> ephemerals = dataTree.getEphemerals(sessionId);
        assertTrue(ephemerals.contains("/testEphmSession"));
    }

    @Test
    public void testGetContainersInitiallyEmpty() {
        assertTrue(dataTree.getContainers().isEmpty());
    }

    @Test
    public void testGetTtlsInitiallyEmpty() {
        assertTrue(dataTree.getTtls().isEmpty());
    }

    @Test
    public void testGetMaxPrefixWithQuota() {
        assertNull(dataTree.getMaxPrefixWithQuota("/nonExistentQuota"));
    }

    @Test
    public void testSetCversionPzxid() throws Exception {
        dataTree.createNode("/testCversion", "data".getBytes(), acl, 0, -1, 1, 1);
        dataTree.setCversionPzxid("/testCversion", 5, 2);
        Stat stat = dataTree.statNode("/testCversion", null);
        assertEquals(5, stat.getCversion());
        assertEquals(2, stat.getPzxid());
    }

    @Test
    public void testSetCversionPzxidThrowsNoNode() {
        assertThrows(NoNodeException.class, () -> dataTree.setCversionPzxid("/nonExistent", 5, 2));
    }

    @Test
    public void testGetWatchCountInitiallyZero() {
        assertEquals(0, dataTree.getWatchCount());
    }
}