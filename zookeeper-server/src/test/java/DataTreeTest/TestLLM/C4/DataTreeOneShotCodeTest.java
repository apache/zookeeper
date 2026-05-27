package DataTreeTest.TestLLM.C4;



import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.EphemeralType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeOneShotCodeTest {

    private DataTree dataTree;
    private static final long SESSION_ID = 0x123456789L;
    private static final long ZXID = 1L;
    private static final long TIME = System.currentTimeMillis();
    private static final byte[] SAMPLE_DATA = "test_payload_bytes".getBytes();

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    @Test
    @DisplayName("Test successful standard node creation and retrieval")
    public void testCreateNodeSuccess() throws Exception {
        String path = "/unprotectedNode";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        DataNode node = dataTree.getNode(path);
        assertNotNull(node, "Node should be created and discoverable within the tree map");
        assertArrayEquals(SAMPLE_DATA, node.getData(), "Node data payload should match original data bytes");
        assertTrue(dataTree.getNodeCount() > 0, "Global node count track should correctly increase");
    }

    @Test
    @DisplayName("Test createNode throws NodeExistsException for duplicate paths")
    public void testCreateNodeThrowsNodeExistsException() throws Exception {
        String path = "/duplicateZnode";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        assertThrows(NodeExistsException.class,
                () -> dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID + 1, TIME),
                "Creating an already existing path must throw NodeExistsException");
    }

    @Test
    @DisplayName("Test createNode throws NoNodeException if parent path doesn't exist")
    public void testCreateNodeThrowsNoNodeExceptionForMissingParent() {
        String path = "/missingParentNode/targetChild";

        assertThrows(NoNodeException.class,
                () -> dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME),
                "Creating a child znode under a non-existent parent must throw NoNodeException");
    }

    @Test
    @DisplayName("Test successful deletion of a znode")
    public void testDeleteNodeSuccess() throws Exception {
        String path = "/nodeToDelete";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        assertNotNull(dataTree.getNode(path));
        dataTree.deleteNode(path, ZXID + 1);

        assertNull(dataTree.getNode(path), "Znode reference should be completely erased from tracking map");
    }

    @Test
    @DisplayName("Test deleteNode throws NoNodeException for non-existent targets")
    public void testDeleteNodeThrowsNoNodeException() {
        assertThrows(NoNodeException.class,
                () -> dataTree.deleteNode("/nonExistentZnode", ZXID),
                "Deleting a non-existent node must throw NoNodeException");
    }

    @Test
    @DisplayName("Test data modification (setData) and value extraction (getData)")
    public void testSetAndGetData() throws Exception {
        String path = "/mutableDataNode";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        byte[] updatedData = "updated_data_payload".getBytes();
        Stat stat = dataTree.setData(path, updatedData, 0, ZXID + 1, TIME);

        assertEquals(0, stat.getVersion(), "Initial data variation checks should match expected version index");

        Stat retrievedStat = new Stat();
        byte[] retrievedData = dataTree.getData(path, retrievedStat, null);

        assertArrayEquals(updatedData, retrievedData, "Retrieved data should match the recent update payload");
        assertEquals(0, retrievedStat.getVersion(), "Retrieved version stats should match updated reference indices");
    }

    @Test
    @DisplayName("Test extraction of structural child nodes and hierarchy validation")
    public void testGetChildrenAndCounts() throws Exception {
        String parentPath = "/parentBranch";
        dataTree.createNode(parentPath, new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);
        dataTree.createNode(parentPath + "/childLeaf1", new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);
        dataTree.createNode(parentPath + "/childLeaf2", new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        List<String> children = dataTree.getChildren(parentPath, new Stat(), null);

        assertNotNull(children);
        assertEquals(2, children.size(), "Parent must contain exactly 2 immediate structural child nodes");
        assertTrue(children.contains("childLeaf1"));
        assertTrue(children.contains("childLeaf2"));
        assertEquals(2, dataTree.getAllChildrenNumber(parentPath), "getAllChildrenNumber should match total sub-child entries count");
    }

    @Test
    @DisplayName("Test tracking of session-tied ephemeral node lifecycles")
    public void testEphemeralNodeTracking() throws Exception {
        String ephemeralPath = "/ephemeralZnode";
        dataTree.createNode(ephemeralPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, SESSION_ID, -1, ZXID, TIME);

        Set<String> ephemerals = dataTree.getEphemerals(SESSION_ID);
        assertNotNull(ephemerals);
        assertTrue(ephemerals.contains(ephemeralPath), "Ephemeral path must be mapped to session owner registry");
        assertEquals(1, dataTree.getEphemeralsCount(), "Global short-lived metrics count should accurately increment");
    }

    @Test
    @DisplayName("Test terminal session execution auto-purges active ephemerals (via Reflection)")
    public void testKillSessionRemovesEphemerals() throws Exception {
        String ephemeralPath = "/volatileZnodeToPrune";
        dataTree.createNode(ephemeralPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, SESSION_ID, -1, ZXID, TIME);

        assertNotNull(dataTree.getNode(ephemeralPath));

        // killSession has package-private access; bypassing with Reflection.
        Method killSessionMethod = DataTree.class.getDeclaredMethod("killSession", long.class, long.class);
        killSessionMethod.setAccessible(true);
        killSessionMethod.invoke(dataTree, SESSION_ID, ZXID + 1);

        assertNull(dataTree.getNode(ephemeralPath), "Ephemeral znode should be auto-pruned when session owner dies");
        assertTrue(dataTree.getEphemerals(SESSION_ID).isEmpty(), "Session map registration entries should clear out completely");
    }

    @Test
    @DisplayName("Test setting and verifying explicit Access Control Lists")
    public void testSetAndGetACL() throws Exception {
        String path = "/securedAclZnode";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        List<ACL> uniqueAcls = Ids.READ_ACL_UNSAFE;
        Stat stat = dataTree.setACL(path, uniqueAcls, 1);

        assertEquals(1, stat.getAversion(), "ACL aversion index must track custom aversion assignment updates");

        List<ACL> verifiedAclList = dataTree.getACL(path, new Stat());
        assertEquals(uniqueAcls, verifiedAclList, "Extracted access control configurations do not match original inputs");
    }

    @Test
    @DisplayName("Test identification of reserved system core paths (via Reflection)")
    public void testIsSpecialPath() throws Exception {
        // isSpecialPath has package-private visibility; bypassing with Reflection.
        Method isSpecialPathMethod = DataTree.class.getDeclaredMethod("isSpecialPath", String.class);
        isSpecialPathMethod.setAccessible(true);

        assertTrue((boolean) isSpecialPathMethod.invoke(dataTree, "/"), "Root directory check failed");
        assertTrue((boolean) isSpecialPathMethod.invoke(dataTree, "/zookeeper"), "Internal proc subsystem separation verification failure");
        assertTrue((boolean) isSpecialPathMethod.invoke(dataTree, "/zookeeper/quota"), "Quota management space evaluation failure");
        assertTrue((boolean) isSpecialPathMethod.invoke(dataTree, "/zookeeper/config"), "Configuration layer namespace check failure");
        assertFalse((boolean) isSpecialPathMethod.invoke(dataTree, "/standardUserZnode"), "User storage environments should not match special system paths");
    }

    @Test
    @DisplayName("Test data tree state serialization and recovery stream cycles")
    public void testSerializationAndDeserializationStateIntegrity() throws Exception {
        String path = "/snapshotValidationNode";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);

        dataTree.serialize(oa, "tree_snapshot_checkpoint");
        byte[] rawOutputDataStream = baos.toByteArray();

        assertNotNull(rawOutputDataStream);
        assertTrue(rawOutputDataStream.length > 0, "Serialization stream output bytes cannot register empty structures");

        DataTree restoredTree = new DataTree();
        ByteArrayInputStream bais = new ByteArrayInputStream(rawOutputDataStream);
        BinaryInputArchive ia = BinaryInputArchive.getArchive(bais);

        restoredTree.deserialize(ia, "tree_snapshot_checkpoint");

        DataNode restoredNodeReference = restoredTree.getNode(path);
        assertNotNull(restoredNodeReference, "Recovered tree instance must preserve original checkpoint node fields");
        assertArrayEquals(SAMPLE_DATA, restoredNodeReference.getData(), "Restored content byte stream payload check mismatch");
    }

    @Test
    @DisplayName("Test advanced storage classes tracking scopes for Container and TTL znodes")
    public void testContainersAndTtls() throws Exception {
        String containerPath = "/autonomousContainerNode";
        dataTree.createNode(containerPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, EphemeralType.CONTAINER_EPHEMERAL_OWNER, -1, ZXID, TIME);
        assertTrue(dataTree.getContainers().contains(containerPath), "Containers collection tracking map missed the registered node configuration class");

        String ttlPath = "/expiringTtlNode";
        long derivedTtlBits = EphemeralType.TTL.toEphemeralOwner(30000);
        dataTree.createNode(ttlPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, derivedTtlBits, -1, ZXID, TIME);
        assertTrue(dataTree.getTtls().contains(ttlPath), "TTLs collection tracking map missed the registered node configuration class");
    }

    @Test
    @DisplayName("Test compilation of node metadata status fields")
    public void testStatNode() throws Exception {
        String path = "/statValidationTarget";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        Stat runtimeCompiledStat = dataTree.statNode(path, null);
        assertNotNull(runtimeCompiledStat, "Metadata payload layouts should not return null references");
        assertEquals(ZXID, runtimeCompiledStat.getCzxid(), "Czxid statistical value mismatch inside compiled meta stats record layout");
    }
}