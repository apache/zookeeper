package DataTreeTest.TestLLM.C2;

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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeTotCodeFinalTest {

    private DataTree dataTree;
    private static final long SESSION_ID = 0x123456789L;
    private static final long ZXID = 1L;
    private static final long TIME = System.currentTimeMillis();
    private static final byte[] SAMPLE_DATA = "test_data".getBytes();

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    @Nested
    @DisplayName("Node Creation and Deletion Tests")
    class NodeLifecycleTests {

        @Test
        @DisplayName("Successfully create a standard node")
        public void testCreateNodeSuccess() throws Exception {
            String path = "/testNode";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            DataNode node = dataTree.getNode(path);
            assertNotNull(node, "Node should exist in the tree.");
            assertArrayEquals(SAMPLE_DATA, node.getData(), "Data should match input.");
            assertTrue(dataTree.getNodeCount() > 0, "Node count should be updated.");
        }

        @Test
        @DisplayName("Throw NodeExistsException when creating duplicate node")
        public void testCreateNodeThrowsNodeExistsException() throws Exception {
            String path = "/duplicateNode";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            assertThrows(NodeExistsException.class,
                    () -> dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID + 1, TIME),
                    "Should throw exception if node already exists.");
        }

        @Test
        @DisplayName("Throw NoNodeException when parent is missing")
        public void testCreateNodeThrowsNoNodeExceptionForMissingParent() {
            String path = "/missingParent/child";

            assertThrows(NoNodeException.class,
                    () -> dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME),
                    "Should throw exception if parent path does not exist.");
        }

        @Test
        @DisplayName("Successfully delete an existing node")
        public void testDeleteNodeSuccess() throws Exception {
            String path = "/nodeToDelete";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            assertNotNull(dataTree.getNode(path));
            dataTree.deleteNode(path, ZXID + 1);
            assertNull(dataTree.getNode(path), "Node should be entirely removed.");
        }

        @Test
        @DisplayName("Throw NoNodeException when deleting non-existent node")
        public void testDeleteNodeThrowsNoNodeException() {
            assertThrows(NoNodeException.class,
                    () -> dataTree.deleteNode("/nonExistentNode", ZXID),
                    "Cannot delete a node that does not exist.");
        }
    }

    @Nested
    @DisplayName("Data and ACL Manipulation Tests")
    class DataAndAclTests {

        @Test
        @DisplayName("Successfully set and retrieve node data")
        public void testSetAndGetData() throws Exception {
            String path = "/dataNode";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            byte[] newData = "new_data_updated".getBytes();
            Stat stat = dataTree.setData(path, newData, 0, ZXID + 1, TIME);

            assertEquals(1, stat.getVersion(), "Node version should increment upon data change.");

            Stat retrievedStat = new Stat();
            byte[] retrievedData = dataTree.getData(path, retrievedStat, null);

            assertArrayEquals(newData, retrievedData, "Retrieved data must match the new data.");
            assertEquals(1, retrievedStat.getVersion(), "Retrieved stat must reflect updated version.");
        }

        @Test
        @DisplayName("Successfully set and retrieve ACLs")
        public void testSetAndGetACL() throws Exception {
            String path = "/aclNode";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            List<ACL> newAcl = Ids.READ_ACL_UNSAFE;
            Stat stat = dataTree.setACL(path, newAcl, 0);

            assertEquals(1, stat.getAversion(), "ACL version should increment.");

            List<ACL> retrievedAcl = dataTree.getACL(path, new Stat());
            assertEquals(newAcl, retrievedAcl, "Retrieved ACL must match the updated ACL.");
        }
    }

    @Nested
    @DisplayName("Tree Hierarchy and Special Path Tests")
    class TreeHierarchyTests {

        @Test
        @DisplayName("Successfully retrieve node children")
        public void testGetChildren() throws Exception {
            String parentPath = "/parent";
            dataTree.createNode(parentPath, new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);
            dataTree.createNode(parentPath + "/child1", new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);
            dataTree.createNode(parentPath + "/child2", new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            List<String> children = dataTree.getChildren(parentPath, new Stat(), null);

            assertNotNull(children);
            assertEquals(2, children.size(), "Should accurately report the number of children.");
            assertTrue(children.contains("child1"));
            assertTrue(children.contains("child2"));
        }

        @Test
        @DisplayName("Identify special ZooKeeper paths via Reflection")
        public void testIsSpecialPath() throws Exception {
            Method isSpecialPathMethod = DataTree.class.getDeclaredMethod("isSpecialPath", String.class);
            isSpecialPathMethod.setAccessible(true);

            assertTrue((boolean) isSpecialPathMethod.invoke(dataTree, "/"), "Root is a special path");
            assertTrue((boolean) isSpecialPathMethod.invoke(dataTree, "/zookeeper"), "Proc is a special path");
            assertTrue((boolean) isSpecialPathMethod.invoke(dataTree, "/zookeeper/quota"), "Quota is a special path");
            assertFalse((boolean) isSpecialPathMethod.invoke(dataTree, "/normalNode"), "Standard nodes are not special paths");
        }
    }

    @Nested
    @DisplayName("Session, Ephemeral, TTL, and Container Tests")
    class SessionAndEphemeralTests {

        @Test
        @DisplayName("Track ephemeral node creation under specific session")
        public void testEphemeralNodeCreation() throws Exception {
            String ephemeralPath = "/ephemeralNode";
            dataTree.createNode(ephemeralPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, SESSION_ID, -1, ZXID, TIME);

            Set<String> ephemerals = dataTree.getEphemerals(SESSION_ID);
            assertTrue(ephemerals.contains(ephemeralPath), "Ephemeral node must be mapped to its session.");
            assertEquals(1, dataTree.getEphemeralsCount());
        }

        @Test
        @DisplayName("Track TTL and Container node creation")
        public void testContainerAndTtlNodeCreation() throws Exception {
            String containerPath = "/containerNode";
            dataTree.createNode(containerPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, EphemeralType.CONTAINER_EPHEMERAL_OWNER, -1, ZXID, TIME);
            assertTrue(dataTree.getContainers().contains(containerPath), "Container node must be tracked.");

            String ttlPath = "/ttlNode";
            long ttlOwner = EphemeralType.TTL.toEphemeralOwner(10000);
            dataTree.createNode(ttlPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, ttlOwner, -1, ZXID, TIME);
            assertTrue(dataTree.getTtls().contains(ttlPath), "TTL node must be tracked.");
        }

        @Test
        @DisplayName("Clean up ephemeral nodes upon session kill via Reflection")
        public void testKillSessionRemovesEphemerals() throws Exception {
            String ephemeralPath = "/ephemeralToKill";
            dataTree.createNode(ephemeralPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, SESSION_ID, -1, ZXID, TIME);

            Method killSessionMethod = DataTree.class.getDeclaredMethod("killSession", long.class, long.class);
            killSessionMethod.setAccessible(true);
            killSessionMethod.invoke(dataTree, SESSION_ID, ZXID + 1);

            assertNull(dataTree.getNode(ephemeralPath), "Ephemeral node must be deleted when session dies.");
            assertTrue(dataTree.getEphemerals(SESSION_ID).isEmpty(), "Session mapping should be empty.");
        }
    }

    @Nested
    @DisplayName("Serialization Tests")
    class SerializationTests {

        @Test
        @DisplayName("Serialize and deserialize DataTree state")
        public void testSerializationAndDeserialization() throws Exception {
            String path = "/serializeNode";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            // Serialize state into byte array
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);
            dataTree.serialize(oa, "tree");
            byte[] serializedData = baos.toByteArray();

            assertTrue(serializedData.length > 0, "Serialized output should not be empty.");

            // Deserialize state into a fresh tree instance
            DataTree restoredTree = new DataTree();
            ByteArrayInputStream bais = new ByteArrayInputStream(serializedData);
            BinaryInputArchive ia = BinaryInputArchive.getArchive(bais);
            restoredTree.deserialize(ia, "tree");

            // Verify integrity
            DataNode restoredNode = restoredTree.getNode(path);
            assertNotNull(restoredNode, "Restored tree must contain the original node.");
            assertArrayEquals(SAMPLE_DATA, restoredNode.getData(), "Data integrity must be maintained across serialization.");
        }
    }
}