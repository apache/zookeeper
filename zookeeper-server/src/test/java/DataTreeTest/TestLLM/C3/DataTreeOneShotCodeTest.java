package DataTreeTest.TestLLM.C3;



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
    private static final byte[] SAMPLE_DATA = "test_data".getBytes();

    @BeforeEach
    public void setUp() {
        // Initialize a fresh DataTree before each test run
        dataTree = new DataTree();
    }

    @Test
    @DisplayName("Test successful creation of a standard node")
    public void testCreateNodeSuccess() throws Exception {
        String path = "/testNode";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        DataNode node = dataTree.getNode(path);
        assertNotNull(node, "Node should be created and retrievable");
        assertArrayEquals(SAMPLE_DATA, node.getData(), "Node data should match the inserted data");
        assertTrue(dataTree.getNodeCount() > 0, "Node count should be updated globally");
    }

    @Test
    @DisplayName("Test createNode throws NodeExistsException for duplicates")
    public void testCreateNodeThrowsNodeExistsException() throws Exception {
        String path = "/duplicateNode";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        assertThrows(NodeExistsException.class,
                () -> dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID + 1, TIME),
                "Creating a node that already exists should throw NodeExistsException");
    }

    @Test
    @DisplayName("Test createNode throws NoNodeException for a missing parent")
    public void testCreateNodeThrowsNoNodeExceptionForMissingParent() {
        String path = "/missingParent/child";

        assertThrows(NoNodeException.class,
                () -> dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME),
                "Creating a node with a missing parent should throw NoNodeException");
    }

    @Test
    @DisplayName("Test successful deletion of an existing node")
    public void testDeleteNodeSuccess() throws Exception {
        String path = "/nodeToDelete";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        assertNotNull(dataTree.getNode(path), "Node must exist prior to deletion");
        dataTree.deleteNode(path, ZXID + 1);

        assertNull(dataTree.getNode(path), "Node should be entirely removed from the DataTree");
    }

    @Test
    @DisplayName("Test deleteNode throws NoNodeException for non-existent paths")
    public void testDeleteNodeThrowsNoNodeException() {
        assertThrows(NoNodeException.class,
                () -> dataTree.deleteNode("/nonExistentNode", ZXID),
                "Deleting a non-existent node should throw NoNodeException");
    }

    @Test
    @DisplayName("Test setData and getData functionality")
    public void testSetAndGetData() throws Exception {
        String path = "/dataNode";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        byte[] newData = "updated_test_data".getBytes();
        Stat stat = dataTree.setData(path, newData, 0, ZXID + 1, TIME);

        assertEquals(1, stat.getVersion(), "Node version should increment after setting data");

        Stat retrievedStat = new Stat();
        byte[] retrievedData = dataTree.getData(path, retrievedStat, null);

        assertArrayEquals(newData, retrievedData, "Retrieved data should match the newly updated data");
        assertEquals(1, retrievedStat.getVersion(), "Retrieved stat should accurately reflect the updated version");
    }

    @Test
    @DisplayName("Test accurate retrieval of child nodes")
    public void testGetChildren() throws Exception {
        String parentPath = "/parent";
        dataTree.createNode(parentPath, new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);
        dataTree.createNode(parentPath + "/child1", new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);
        dataTree.createNode(parentPath + "/child2", new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        List<String> children = dataTree.getChildren(parentPath, new Stat(), null);

        assertNotNull(children);
        assertEquals(2, children.size(), "Parent should reflect exactly 2 children");
        assertTrue(children.contains("child1"));
        assertTrue(children.contains("child2"));
    }

    @Test
    @DisplayName("Test ephemeral node creation and session mapping")
    public void testEphemeralNodeCreationAndRetrieval() throws Exception {
        String ephemeralPath = "/ephemeralNode";
        dataTree.createNode(ephemeralPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, SESSION_ID, -1, ZXID, TIME);

        Set<String> ephemerals = dataTree.getEphemerals(SESSION_ID);
        assertNotNull(ephemerals);
        assertTrue(ephemerals.contains(ephemeralPath), "Ephemeral node should be tracked under its respective session ID");
        assertEquals(1, dataTree.getEphemeralsCount(), "Global ephemeral count should increment");
    }

    @Test
    @DisplayName("Test session termination cleans up ephemeral nodes (via Reflection)")
    public void testKillSessionRemovesEphemerals() throws Exception {
        String ephemeralPath = "/ephemeralToKill";
        dataTree.createNode(ephemeralPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, SESSION_ID, -1, ZXID, TIME);

        assertNotNull(dataTree.getNode(ephemeralPath));

        // killSession is package-private. Bypassing visibility via Reflection.
        Method killSessionMethod = DataTree.class.getDeclaredMethod("killSession", long.class, long.class);
        killSessionMethod.setAccessible(true);
        killSessionMethod.invoke(dataTree, SESSION_ID, ZXID + 1);

        assertNull(dataTree.getNode(ephemeralPath), "Ephemeral node should be deleted when the session is killed");
        assertTrue(dataTree.getEphemerals(SESSION_ID).isEmpty(), "Session mapping list should be clear");
    }

    @Test
    @DisplayName("Test setACL and getACL functionality")
    public void testSetAndGetACL() throws Exception {
        String path = "/aclNode";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        List<ACL> newAcl = Ids.READ_ACL_UNSAFE;
        Stat stat = dataTree.setACL(path, newAcl, 0);

        assertEquals(1, stat.getAversion(), "ACL aversion should increment upon modification");

        List<ACL> retrievedAcl = dataTree.getACL(path, new Stat());
        assertEquals(newAcl, retrievedAcl, "Retrieved ACL should perfectly match the updated ACL rules");
    }

    @Test
    @DisplayName("Test identification of special ZooKeeper paths (via Reflection)")
    public void testIsSpecialPath() throws Exception {
        // isSpecialPath is package-private. Bypassing visibility via Reflection.
        Method isSpecialPathMethod = DataTree.class.getDeclaredMethod("isSpecialPath", String.class);
        isSpecialPathMethod.setAccessible(true);

        assertTrue((boolean) isSpecialPathMethod.invoke(dataTree, "/"), "Root should be identified as a special path");
        assertTrue((boolean) isSpecialPathMethod.invoke(dataTree, "/zookeeper"), "Proc path should be identified as a special path");
        assertTrue((boolean) isSpecialPathMethod.invoke(dataTree, "/zookeeper/quota"), "Quota path should be identified as a special path");
        assertTrue((boolean) isSpecialPathMethod.invoke(dataTree, "/zookeeper/config"), "Config path should be identified as a special path");
        assertFalse((boolean) isSpecialPathMethod.invoke(dataTree, "/normalNode"), "Standard user nodes should not be special paths");
    }

    @Test
    @DisplayName("Test DataTree state serialization and deserialization")
    public void testSerializationAndDeserialization() throws Exception {
        String path = "/serializeNode";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);

        dataTree.serialize(oa, "tree_tag");

        byte[] serializedData = baos.toByteArray();
        assertNotNull(serializedData);
        assertTrue(serializedData.length > 0, "Serialized byte array should not be empty");

        DataTree restoredTree = new DataTree();
        ByteArrayInputStream bais = new ByteArrayInputStream(serializedData);
        BinaryInputArchive ia = BinaryInputArchive.getArchive(bais);

        restoredTree.deserialize(ia, "tree_tag");

        DataNode restoredNode = restoredTree.getNode(path);
        assertNotNull(restoredNode, "Node should exist in the newly deserialized tree");
        assertArrayEquals(SAMPLE_DATA, restoredNode.getData(), "Deserialized node data should perfectly match the original byte array");
    }

    @Test
    @DisplayName("Test container and TTL node tracking sets")
    public void testContainersAndTtls() throws Exception {
        String containerPath = "/containerNode";
        dataTree.createNode(containerPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, EphemeralType.CONTAINER_EPHEMERAL_OWNER, -1, ZXID, TIME);
        assertTrue(dataTree.getContainers().contains(containerPath), "Container node should be successfully mapped to the containers set");

        String ttlPath = "/ttlNode";
        long ttlOwner = EphemeralType.TTL.toEphemeralOwner(10000);
        dataTree.createNode(ttlPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, ttlOwner, -1, ZXID, TIME);
        assertTrue(dataTree.getTtls().contains(ttlPath), "TTL node should be successfully mapped to the TTLs set");
    }

    @Test
    @DisplayName("Test stat node metadata retrieval")
    public void testStatNode() throws Exception {
        String path = "/statNodeTest";
        dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

        Stat stat = dataTree.statNode(path, null);
        assertNotNull(stat, "Stat object returned should not be null");
        assertEquals(ZXID, stat.getCzxid(), "Czxid inside Stat should exactly match the creation ZXID");
    }
}