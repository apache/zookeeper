package DataTreeTest.TestLLM;



import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive JUnit 5 test suite for the DataTree class based on ZooKeeper 3.9.4.
 * Tests cover public APIs for node manipulation, session tracking, and transaction processing.
 */
public class DataTreeZeroShoDocTest {

    private DataTree dataTree;
    private final List<ACL> openAcl = ZooDefs.Ids.OPEN_ACL_UNSAFE;

    @BeforeEach
    public void setUp() {
        // Initialize a fresh DataTree before each test
        dataTree = new DataTree();
    }

    // --- Core Operations (Create, Read, Update, Delete) ---

    @Test
    @DisplayName("Create a persistent node and retrieve its data via public API")
    public void testCreateAndGetData() throws Exception {
        String path = "/testNode";
        byte[] data = "payload".getBytes();

        dataTree.createNode(path, data, openAcl, 0L, -1, 1L, 1000L);

        Stat stat = new Stat();
        byte[] retrievedData = dataTree.getData(path, stat, null);

        assertArrayEquals(data, retrievedData, "The retrieved data should match the inserted data.");
        assertEquals(1L, stat.getCzxid(), "The creation transaction ID (czxid) should match.");
        assertEquals(0, stat.getVersion(), "Initial node version should be 0.");
    }

    @Test
    @DisplayName("Creating an existing node throws NodeExistsException")
    public void testCreateDuplicateNode() throws Exception {
        String path = "/duplicateNode";
        dataTree.createNode(path, "data".getBytes(), openAcl, 0L, -1, 1L, 1000L);

        assertThrows(NodeExistsException.class, () -> {
            dataTree.createNode(path, "newData".getBytes(), openAcl, 0L, -1, 2L, 1001L);
        }, "Should throw NodeExistsException when attempting to create a node that already exists.");
    }

    @Test
    @DisplayName("Update node data and verify version increment")
    public void testSetData() throws Exception {
        String path = "/updateNode";
        dataTree.createNode(path, "initial".getBytes(), openAcl, 0L, -1, 1L, 1000L);

        Stat stat = dataTree.setData(path, "updated".getBytes(), 0, 2L, 1001L);

        assertEquals(1, stat.getVersion(), "Node version should increment to 1 after an update.");
        assertEquals(2L, stat.getMzxid(), "Modified transaction ID (mzxid) should reflect the update.");

        byte[] newData = dataTree.getData(path, new Stat(), null);
        assertArrayEquals("updated".getBytes(), newData);
    }

    @Test
    @DisplayName("Delete a node and verify NoNodeException on subsequent access")
    public void testDeleteNode() throws Exception {
        String path = "/deleteNode";
        dataTree.createNode(path, new byte[0], openAcl, 0L, -1, 1L, 1000L);

        // Assert existence
        assertNotNull(dataTree.statNode(path, null));

        // Perform deletion
        dataTree.deleteNode(path, 2L);

        assertThrows(NoNodeException.class, () -> {
            dataTree.statNode(path, null);
        }, "statNode should throw NoNodeException if the node has been deleted.");
    }

    // --- Children and Hierarchy ---

    @Test
    @DisplayName("Retrieve children of a parent node")
    public void testGetChildren() throws Exception {
        dataTree.createNode("/parent", new byte[0], openAcl, 0L, -1, 1L, 1000L);
        dataTree.createNode("/parent/child1", new byte[0], openAcl, 0L, -1, 2L, 1001L);
        dataTree.createNode("/parent/child2", new byte[0], openAcl, 0L, -1, 3L, 1002L);

        List<String> children = dataTree.getChildren("/parent", new Stat(), null);

        assertEquals(2, children.size(), "Should return exactly 2 children.");
        assertTrue(children.contains("child1"));
        assertTrue(children.contains("child2"));
    }

    // --- Ephemeral Nodes & Sessions ---

    @Test
    @DisplayName("Ephemeral nodes track by session ID and are removed on session close via processTxn")
    public void testEphemeralNodeCleanup() throws Exception {
        long sessionId = 0x123ABCDL;
        String path = "/ephemeralNode";

        // Create ephemeral node (ephemeralOwner = sessionId)
        dataTree.createNode(path, "temp".getBytes(), openAcl, sessionId, -1, 1L, 1000L);

        Set<String> ephemerals = dataTree.getEphemerals(sessionId);
        assertTrue(ephemerals.contains(path), "Node should be tracked under the session's ephemerals.");

        // Simulate session termination using the public transactional API
        TxnHeader header = new TxnHeader(sessionId, 0, 2L, 1001L, ZooDefs.OpCode.closeSession);
        dataTree.processTxn(header, null);

        assertThrows(NoNodeException.class, () -> {
            dataTree.statNode(path, null);
        }, "Ephemeral node should be deleted when its owning session is closed.");

        assertTrue(dataTree.getEphemerals(sessionId).isEmpty(), "Ephemeral set for the session should be empty.");
    }

    // --- Watches ---

    @Test
    @DisplayName("Watchers trigger properly on data change")
    public void testDataWatcher() throws Exception {
        String path = "/watchedNode";
        Watcher mockWatcher = mock(Watcher.class);

        dataTree.createNode(path, "v1".getBytes(), openAcl, 0L, -1, 1L, 1000L);

        // Attach watcher
        dataTree.getData(path, new Stat(), mockWatcher);

        // Update data to trigger the watch
        dataTree.setData(path, "v2".getBytes(), 0, 2L, 1001L);

        ArgumentCaptor<WatchedEvent> captor = ArgumentCaptor.forClass(WatchedEvent.class);
        verify(mockWatcher, times(1)).process(captor.capture());

        WatchedEvent event = captor.getValue();
        assertEquals(Watcher.Event.EventType.NodeDataChanged, event.getType());
        assertEquals(path, event.getPath());
    }

    // --- Transaction Processing ---

    @Test
    @DisplayName("Process a create transaction directly via processTxn")
    public void testProcessCreateTxn() {
        String path = "/txnNode";
        TxnHeader header = new TxnHeader(1L, 1, 10L, 1000L, ZooDefs.OpCode.create);
        CreateTxn txn = new CreateTxn(path, "txnData".getBytes(), openAcl, false, 0);

        DataTree.ProcessTxnResult result = dataTree.processTxn(header, txn);

        assertEquals(0, result.err, "Transaction should execute without errors.");
        assertEquals(path, result.path, "Result should reflect the correct path.");

        // Validate the tree state reflects the processed transaction
        assertDoesNotThrow(() -> {
            dataTree.statNode(path, null);
        });
    }

    // --- Metrics and Utilities ---

    @Test
    @DisplayName("Verify approximate data size matches inserted bytes")
    public void testApproximateDataSize() throws Exception {
        long initialSize = dataTree.cachedApproximateDataSize();

        String path = "/sizeTest";
        byte[] payload = "1234567890".getBytes(); // 10 bytes
        dataTree.createNode(path, payload, openAcl, 0L, -1, 1L, 1000L);

        // Formula: Initial + Path Length + Data Length
        long expectedSize = initialSize + path.length() + payload.length;

        assertEquals(expectedSize, dataTree.cachedApproximateDataSize());
    }

    @Test
    @DisplayName("Check if diagnostic dump logic runs without throwing exceptions")
    public void testDiagnosticsDump() throws Exception {
        dataTree.createNode("/test", new byte[0], openAcl, 0x1L, -1, 1L, 1000L);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        assertDoesNotThrow(() -> {
            dataTree.dumpEphemerals(pw);
            dataTree.dumpWatchesSummary(pw);
        }, "Dumping diagnostic data to a PrintWriter should not throw an exception.");
    }
}
