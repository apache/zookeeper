package org.apache.zookeeper.server;

import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 test cases for org.apache.zookeeper.server.DataTree
 * Based on Apache ZooKeeper 3.9.4 Documentation.
 */
public class DataTreeFewShotDocTest {

    private DataTree dataTree;
    private final List<ACL> defaultAcl = ZooDefs.Ids.OPEN_ACL_UNSAFE;

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    @Test
    public void T1_createNode_validPersistentPath_createsSuccessfully() throws Exception {
        String path = "/node_t1";
        byte[] data = "data1".getBytes();

        dataTree.createNode(path, data, defaultAcl, 0L, -1, 1L, 1000L);

        Stat stat = new Stat();
        byte[] retrievedData = dataTree.getData(path, stat, null);
        assertArrayEquals(data, retrievedData);
        assertEquals(1L, stat.getCzxid());
    }

    @Test
    public void T2_createNode_duplicatePath_throwsNodeExistsException() throws Exception {
        String path = "/node_t2";
        dataTree.createNode(path, new byte[0], defaultAcl, 0L, -1, 1L, 1000L);

        assertThrows(NodeExistsException.class, () -> {
            dataTree.createNode(path, new byte[0], defaultAcl, 0L, -1, 2L, 1001L);
        });
    }

    @Test
    public void T3_createNode_missingParent_throwsNoNodeException() {
        String path = "/parent_missing/child";

        assertThrows(NoNodeException.class, () -> {
            dataTree.createNode(path, new byte[0], defaultAcl, 0L, -1, 1L, 1000L);
        });
    }

    @Test
    public void T4_deleteNode_existingPath_removesNodeSuccessfully() throws Exception {
        String path = "/node_t4";
        dataTree.createNode(path, new byte[0], defaultAcl, 0L, -1, 1L, 1000L);

        // Assicuriamoci che esista prima di cancellarlo
        assertNotNull(dataTree.statNode(path, null));

        dataTree.deleteNode(path, 2L);

        assertThrows(NoNodeException.class, () -> {
            dataTree.statNode(path, null);
        });
    }

    @Test
    public void T5_deleteNode_nonExistingPath_throwsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.deleteNode("/non_existing_t5", 1L);
        });
    }

    @Test
    public void T6_setData_existingPath_updatesDataAndStat() throws Exception {
        String path = "/node_t6";
        dataTree.createNode(path, "old".getBytes(), defaultAcl, 0L, -1, 1L, 1000L);

        int newVersion = 1; // Simuliamo la versione calcolata dal RequestProcessor
        Stat stat = dataTree.setData(path, "new".getBytes(), newVersion, 2L, 1001L);

        assertEquals(newVersion, stat.getVersion());
        assertEquals(2L, stat.getMzxid());

        byte[] updatedData = dataTree.getData(path, new Stat(), null);
        assertArrayEquals("new".getBytes(), updatedData);
    }

    @Test
    public void T7_setData_nonExistingPath_throwsNoNodeException() {
        assertThrows(NoNodeException.class, () -> {
            dataTree.setData("/non_existing_t7", "data".getBytes(), 0, 1L, 1000L);
        });
    }

    @Test
    public void T8_getChildren_parentWithChildren_returnsCorrectList() throws Exception {
        dataTree.createNode("/parent_t8", new byte[0], defaultAcl, 0L, -1, 1L, 1000L);
        dataTree.createNode("/parent_t8/child1", new byte[0], defaultAcl, 0L, -1, 2L, 1001L);
        dataTree.createNode("/parent_t8/child2", new byte[0], defaultAcl, 0L, -1, 3L, 1002L);

        List<String> children = dataTree.getChildren("/parent_t8", new Stat(), null);

        assertEquals(2, children.size());
        assertTrue(children.contains("child1"));
        assertTrue(children.contains("child2"));
    }

    @Test
    public void T9_getEphemerals_activeSession_returnsEphemeralPaths() throws Exception {
        long sessionId = 0x123456789L;
        String path1 = "/ephemeral_1";
        String path2 = "/ephemeral_2";

        // Creazione nodi effimeri legati alla sessionId
        dataTree.createNode(path1, new byte[0], defaultAcl, sessionId, -1, 1L, 1000L);
        dataTree.createNode(path2, new byte[0], defaultAcl, sessionId, -1, 2L, 1001L);

        Set<String> ephemerals = dataTree.getEphemerals(sessionId);

        assertEquals(2, ephemerals.size());
        assertTrue(ephemerals.contains(path1));
        assertTrue(ephemerals.contains(path2));
    }

    @Test
    public void T10_processTxn_createOp_modifiesTreeCorrectly() {
        String path = "/txn_node_t10";
        TxnHeader header = new TxnHeader(1L, 1, 10L, 1000L, ZooDefs.OpCode.create);
        CreateTxn txn = new CreateTxn(path, "txnData".getBytes(), defaultAcl, false, 0);

        DataTree.ProcessTxnResult result = dataTree.processTxn(header, txn);

        assertEquals(0, result.err);
        assertEquals(path, result.path);

        assertDoesNotThrow(() -> {
            dataTree.statNode(path, null);
        });
    }

    @Test
    public void T11_approximateDataSize_increasesWithNewNodes() throws Exception {
        long initialSize = dataTree.cachedApproximateDataSize();

        String path = "/size_node";
        byte[] payload = "12345".getBytes(); // 5 bytes
        dataTree.createNode(path, payload, defaultAcl, 0L, -1, 1L, 1000L);

        long newSize = dataTree.cachedApproximateDataSize();
        long expectedIncrease = path.length() + payload.length;

        assertEquals(initialSize + expectedIncrease, newSize);
    }
}