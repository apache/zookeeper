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
    private static final byte[] SAMPLE_DATA = "tot_test_data".getBytes();

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    @Nested
    @DisplayName("Node Creation and Deletion Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("Successfully create a standard znode path")
        public void testCreateNodeSuccess() throws Exception {
            String path = "/testNode";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            DataNode node = dataTree.getNode(path);
            assertNotNull(node, "Target node should be discoverable in map");
            assertArrayEquals(SAMPLE_DATA, node.getData(), "Data payloads mismatch");
            assertTrue(dataTree.getNodeCount() > 0, "Global tree size tracking failed");
        }

        @Test
        @DisplayName("Throw NodeExistsException when path already exists")
        public void testCreateNodeThrowsNodeExistsException() throws Exception {
            String path = "/duplicateNode";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            assertThrows(NodeExistsException.class,
                    () -> dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID + 1, TIME),
                    "Should enforce path uniqueness bounds");
        }

        @Test
        @DisplayName("Throw NoNodeException when intermediate parents are missing")
        public void testCreateNodeThrowsNoNodeExceptionForMissingParent() {
            String path = "/missingParent/targetChild";

            assertThrows(NoNodeException.class,
                    () -> dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME),
                    "Should reject orphaned node creation workflows");
        }

        @Test
        @DisplayName("Successfully delete an active znode track")
        public void testDeleteNodeSuccess() throws Exception {
            String path = "/nodeToDelete";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            assertNotNull(dataTree.getNode(path));
            dataTree.deleteNode(path, ZXID + 1);
            assertNull(dataTree.getNode(path), "Reference mapping should clear out post-deletion");
        }

        @Test
        @DisplayName("Throw NoNodeException when deleting a non-existent path")
        public void testDeleteNodeThrowsNoNodeException() {
            assertThrows(NoNodeException.class,
                    () -> dataTree.deleteNode("/nonExistentNode", ZXID),
                    "Should fail when trying to erase an unmapped znode");
        }
    }

    @Nested
    @DisplayName("Data and ACL Modifications")
    class MutationTests {

        @Test
        @DisplayName("Successfully update data and capture version modification shifts")
        public void testSetAndGetData() throws Exception {
            String path = "/dataNode";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            byte[] refreshedData = "new_refreshed_bytes".getBytes();
            Stat initialStat = dataTree.setData(path, refreshedData, 0, ZXID + 1, TIME);

            assertEquals(0, initialStat.getVersion(), "Initial state version indices should track correctly");

            Stat verificationStat = new Stat();
            byte[] processedBytes = dataTree.getData(path, verificationStat, null);

            assertArrayEquals(refreshedData, processedBytes, "Recovered memory payload mismatch");
            assertEquals(0, verificationStat.getVersion(), "Read statistical values failed correlation checks");
        }

        @Test
        @DisplayName("Successfully assign customized access control list parameters")
        public void testSetAndGetACL() throws Exception {
            String path = "/aclZnode";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            List<ACL> modifiedScheme = Ids.READ_ACL_UNSAFE;
            Stat operationalStat = dataTree.setACL(path, modifiedScheme, 1);

            assertEquals(1, operationalStat.getAversion(), "Aversion indexing did not safely match inputs");

            List<ACL> confirmedAclLayout = dataTree.getACL(path, new Stat());
            assertEquals(modifiedScheme, confirmedAclLayout, "Recovered protection configuration lists mismatched");
        }
    }

    @Nested
    @DisplayName("Hierarchy Traversal and System Core Rules")
    class TraversalTests {

        @Test
        @DisplayName("Accurately calculate and isolate immediate branch child nodes")
        public void testGetChildrenAndCounts() throws Exception {
            String parentPath = "/parentDirectory";
            dataTree.createNode(parentPath, new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);
            dataTree.createNode(parentPath + "/leafA", new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);
            dataTree.createNode(parentPath + "/leafB", new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            List<String> immediateLeaves = dataTree.getChildren(parentPath, new Stat(), null);

            assertNotNull(immediateLeaves);
            assertEquals(2, immediateLeaves.size(), "Discovered structural layout mismatch under root");
            assertTrue(immediateLeaves.contains("leafA"));
            assertTrue(immediateLeaves.contains("leafB"));
            assertEquals(2, dataTree.getAllChildrenNumber(parentPath), "Recursive indexing layout checks mismatched");
        }

        @Test
        @DisplayName("Bypass package protection boundaries via Reflection to check internal special paths")
        public void testIsSpecialPath() throws Exception {
            Method pathEvaluationMethod = DataTree.class.getDeclaredMethod("isSpecialPath", String.class);
            pathEvaluationMethod.setAccessible(true);

            assertTrue((boolean) pathEvaluationMethod.invoke(dataTree, "/"), "Root directory check failed");
            assertTrue((boolean) pathEvaluationMethod.invoke(dataTree, "/zookeeper"), "Proc workspace isolation failure");
            assertTrue((boolean) pathEvaluationMethod.invoke(dataTree, "/zookeeper/quota"), "Quota registry space evaluation failure");
            assertTrue((boolean) pathEvaluationMethod.invoke(dataTree, "/zookeeper/config"), "Config layer separation verification failure");
            assertFalse((boolean) pathEvaluationMethod.invoke(dataTree, "/customZnodeBranch"), "Standard user targets flagged as system workspace core rules");
        }
    }

    @Nested
    @DisplayName("Ephemeral Sessions and Multi-class Ownerships")
    class SessionTests {

        @Test
        @DisplayName("Track basic short-lived znode mappings accurately against specific sessions")
        public void testEphemeralRegistration() throws Exception {
            String trackingEphemeralPath = "/ephemeralLeaf";
            dataTree.createNode(trackingEphemeralPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, SESSION_ID, -1, ZXID, TIME);

            Set<String> sessionTrackedPaths = dataTree.getEphemerals(SESSION_ID);
            assertTrue(sessionTrackedPaths.contains(trackingEphemeralPath), "Target allocation registry context link lost");
            assertEquals(1, dataTree.getEphemeralsCount(), "Global structural mapping metric count discrepancy");
        }

        @Test
        @DisplayName("Track automated dynamic classes smoothly for both Containers and Time-To-Live nodes")
        public void testAdvancedStorageClasses() throws Exception {
            String activeContainer = "/containerBucket";
            dataTree.createNode(activeContainer, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, EphemeralType.CONTAINER_EPHEMERAL_OWNER, -1, ZXID, TIME);
            assertTrue(dataTree.getContainers().contains(activeContainer), "Containers list did not properly record znode configuration class");

            String activeTtlZnode = "/timeLockedZnode";
            long derivedOwnerBits = EphemeralType.TTL.toEphemeralOwner(15000);
            dataTree.createNode(activeTtlZnode, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, derivedOwnerBits, -1, ZXID, TIME);
            assertTrue(dataTree.getTtls().contains(activeTtlZnode), "TTL list did not properly record znode configuration class");
        }

        @Test
        @DisplayName("Bypass package access boundaries via Reflection to drop ephemeral structures upon session termination")
        public void testKillSessionRemovesEphemerals() throws Exception {
            String targetEphemeralPath = "/volatileLeafToPrune";
            dataTree.createNode(targetEphemeralPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, SESSION_ID, -1, ZXID, TIME);

            assertNotNull(dataTree.getNode(targetEphemeralPath));

            Method killSessionExecution = DataTree.class.getDeclaredMethod("killSession", long.class, long.class);
            killSessionExecution.setAccessible(true);
            killSessionExecution.invoke(dataTree, SESSION_ID, ZXID + 1);

            assertNull(dataTree.getNode(targetEphemeralPath), "Expired structural components failed structural pruning workflows");
            assertTrue(dataTree.getEphemerals(SESSION_ID).isEmpty(), "Allocation maps matching terminal keys must clear completely");
        }
    }

    @Nested
    @DisplayName("Archival State Logic and Persistence Streaming")
    class SerializationTests {

        @Test
        @DisplayName("Verify flawless state recovery across checkpoint binary output data streams")
        public void testSerializationCycle() throws Exception {
            String checkpointZnodePath = "/historicalStateCheckpoint";
            dataTree.createNode(checkpointZnodePath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            ByteArrayOutputStream outputStreamBuffer = new ByteArrayOutputStream();
            BinaryOutputArchive storageEncodingEngine = BinaryOutputArchive.getArchive(outputStreamBuffer);

            dataTree.serialize(storageEncodingEngine, "snapshot_archive");
            byte[] binaryDump = outputStreamBuffer.toByteArray();

            assertNotNull(binaryDump);
            assertTrue(binaryDump.length > 0, "Archival serialization byte arrays cannot initialize completely empty streams");

            DataTree recoveredTreeInstance = new DataTree();
            ByteArrayInputStream inputStreamBuffer = new ByteArrayInputStream(binaryDump);
            BinaryInputArchive storageDecodingEngine = BinaryInputArchive.getArchive(inputStreamBuffer);

            recoveredTreeInstance.deserialize(storageDecodingEngine, "snapshot_archive");

            DataNode validationCheckpointReference = recoveredTreeInstance.getNode(checkpointZnodePath);
            assertNotNull(validationCheckpointReference, "Recovered tree environment failed check maps for logged metrics");
            assertArrayEquals(SAMPLE_DATA, validationCheckpointReference.getData(), "Recovered binary content structures mismatch");
        }
    }
}
