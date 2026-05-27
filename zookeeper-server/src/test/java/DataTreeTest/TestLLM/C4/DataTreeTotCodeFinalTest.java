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
    private static final long ZXID = 100L;
    private static final long TIME = System.currentTimeMillis();
    private static final byte[] SAMPLE_DATA = "tot_test_payload".getBytes();

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    @Nested
    @DisplayName("Node Mutation & Lifecycle Verification")
    class NodeLifecycleTests {

        @Test
        @DisplayName("Verify successful creation and node count incrementation")
        public void testCreateNodeSuccess() throws Exception {
            String path = "/validNode";
            int initialCount = dataTree.getNodeCount();

            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            DataNode node = dataTree.getNode(path);
            assertNotNull(node, "Node should exist within lookup map");
            assertArrayEquals(SAMPLE_DATA, node.getData(), "Data payload matching failed");
            assertEquals(initialCount + 1, dataTree.getNodeCount(), "Global node count did not increment properly");
        }

        @Test
        @DisplayName("Ensure NodeExistsException is thrown upon duplicate path insertion")
        public void testCreateNodeThrowsNodeExistsException() throws Exception {
            String path = "/duplicateZnode";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            assertThrows(NodeExistsException.class,
                    () -> dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID + 1, TIME),
                    "Expected NodeExistsException when creating duplicate znode configuration");
        }

        @Test
        @DisplayName("Ensure NoNodeException is thrown when creating child path under non-existent parent")
        public void testCreateNodeThrowsNoNodeExceptionForMissingParent() {
            String path = "/orphanParent/targetChild";

            assertThrows(NoNodeException.class,
                    () -> dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME),
                    "Expected NoNodeException due to non-existent parent path namespace");
        }

        @Test
        @DisplayName("Verify smooth node deletion path")
        public void testDeleteNodeSuccess() throws Exception {
            String path = "/targetDeletionNode";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            assertNotNull(dataTree.getNode(path));
            dataTree.deleteNode(path, ZXID + 1);

            assertNull(dataTree.getNode(path), "Znode tracking map reference should be nullified post-deletion");
        }

        @Test
        @DisplayName("Ensure NoNodeException is triggered when trying to delete a missing node")
        public void testDeleteNodeThrowsNoNodeException() {
            assertThrows(NoNodeException.class,
                    () -> dataTree.deleteNode("/absentNode", ZXID),
                    "Expected NoNodeException when trying to delete an invalid node target");
        }
    }

    @Nested
    @DisplayName("Data Mutations & ACL Constraint Verification")
    class DataAndAclTests {

        @Test
        @DisplayName("Verify updating data updates node state and increments internal versioning statistics")
        public void testSetAndGetData() throws Exception {
            String path = "/mutableDataNode";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            byte[] updatedData = "refactored_payload_data".getBytes();
            Stat trackingStat = dataTree.setData(path, updatedData, 0, ZXID + 2, TIME + 1000);

            assertEquals(0, trackingStat.getVersion(), "Initial state update version tracking should reflect input parameter index");

            Stat readStat = new Stat();
            byte[] pulledData = dataTree.getData(path, readStat, null);

            assertArrayEquals(updatedData, pulledData, "Pulled payload state does not match structural updates");
            assertEquals(0, readStat.getVersion(), "Read statistical layout version should match structural update tracking index");
        }

        @Test
        @DisplayName("Verify setting custom access controls increments structural ACL verification indices")
        public void testSetAndGetACL() throws Exception {
            String path = "/securedAclNode";
            dataTree.createNode(path, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            List<ACL> uniqueAclScheme = Ids.READ_ACL_UNSAFE;
            Stat statusMetadata = dataTree.setACL(path, uniqueAclScheme, 1);

            assertEquals(1, statusMetadata.getAversion(), "Aversion should accurately match targeted update bounds");

            List<ACL> dynamicPulledAcls = dataTree.getACL(path, new Stat());
            assertEquals(uniqueAclScheme, dynamicPulledAcls, "Pulled structural access validation entries mismatch");
        }
    }

    @Nested
    @DisplayName("Structural Traversal & Path Profiling Verification")
    class HierarchyAndPathTests {

        @Test
        @DisplayName("Verify mapping precision of immediate children structures")
        public void testGetChildrenAndCounts() throws Exception {
            String rootPath = "/hierarchyRoot";
            dataTree.createNode(rootPath, new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);
            dataTree.createNode(rootPath + "/subBranchA", new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);
            dataTree.createNode(rootPath + "/subBranchB", new byte[0], Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            List<String> discoveredBranches = dataTree.getChildren(rootPath, new Stat(), null);

            assertNotNull(discoveredBranches);
            assertEquals(2, discoveredBranches.size(), "Discovered tracking branch mismatch under designated directory root");
            assertTrue(discoveredBranches.contains("subBranchA"));
            assertTrue(discoveredBranches.contains("subBranchB"));

            int globalRecurseChildren = dataTree.getAllChildrenNumber(rootPath);
            assertEquals(2, globalRecurseChildren, "Recursive structural indexing checks failed");
        }

        @Test
        @DisplayName("Bypass visibility boundaries via Reflection to inspect internal special paths safely")
        public void testIsSpecialPathBypass() throws Exception {
            Method targetSpecialPathMethod = DataTree.class.getDeclaredMethod("isSpecialPath", String.class);
            targetSpecialPathMethod.setAccessible(true);

            assertTrue((boolean) targetSpecialPathMethod.invoke(dataTree, "/"), "System root namespace check failure");
            assertTrue((boolean) targetSpecialPathMethod.invoke(dataTree, "/zookeeper"), "Internal server subsystem boundary check failure");
            assertTrue((boolean) targetSpecialPathMethod.invoke(dataTree, "/zookeeper/quota"), "Quota tracking engine boundary check failure");
            assertFalse((boolean) targetSpecialPathMethod.invoke(dataTree, "/userAppNode"), "Standard user namespace flagged as system internal");
        }
    }

    @Nested
    @DisplayName("Session Expirations & Transient Types Verification")
    class SessionAndTransientTests {

        @Test
        @DisplayName("Verify session assignment maps and logs ephemeral nodes effectively")
        public void testEphemeralTrackingAndRegistration() throws Exception {
            String operationalEphemeralPath = "/ephemeralLeaf";
            dataTree.createNode(operationalEphemeralPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, SESSION_ID, -1, ZXID, TIME);

            Set<String> sessionTrackedPaths = dataTree.getEphemerals(SESSION_ID);
            assertTrue(sessionTrackedPaths.contains(operationalEphemeralPath), "Target session allocation map context mapping lost");
            assertEquals(1, dataTree.getEphemeralsCount(), "Global short-lived configuration count check failed");
        }

        @Test
        @DisplayName("Verify specialized Container and Time-To-Live storage classes register smoothly")
        public void testSpecializedEphemeralClasses() throws Exception {
            String proxyContainerNode = "/autonomousContainer";
            dataTree.createNode(proxyContainerNode, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, EphemeralType.CONTAINER_EPHEMERAL_OWNER, -1, ZXID, TIME);
            assertTrue(dataTree.getContainers().contains(proxyContainerNode), "Target context class mapping failed on container registry");

            String activeTtlNode = "/expiringTtlZnode";
            long calculatedOwnerBits = EphemeralType.TTL.toEphemeralOwner(5000);
            dataTree.createNode(activeTtlNode, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, calculatedOwnerBits, -1, ZXID, TIME);
            assertTrue(dataTree.getTtls().contains(activeTtlNode), "Target context class mapping failed on TTL tracker layout registry");
        }

        @Test
        @DisplayName("Bypass package access boundaries via Reflection to clean up node structures upon session death")
        public void testKillSessionClearsState() throws Exception {
            String ephemeralPath = "/volatileZnodeToPrune";
            dataTree.createNode(ephemeralPath, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, SESSION_ID, -1, ZXID, TIME);

            assertNotNull(dataTree.getNode(ephemeralPath));

            Method executeKillSession = DataTree.class.getDeclaredMethod("killSession", long.class, long.class);
            executeKillSession.setAccessible(true);
            executeKillSession.invoke(dataTree, SESSION_ID, ZXID + 1);

            assertNull(dataTree.getNode(ephemeralPath), "Short-lived session node failed structural cleanup sequence during purge processing");
            assertTrue(dataTree.getEphemerals(SESSION_ID).isEmpty(), "Allocation maps for matching expired identifiers should be cleared");
        }
    }

    @Nested
    @DisplayName("Persistence & Archival Encoding Logic Verification")
    class DataTreeSerializationTests {

        @Test
        @DisplayName("Verify precise operational recovery metrics over simulated storage streaming boundaries")
        public void testDeepDataTreeStateSerializationCycle() throws Exception {
            String stateValidationNode = "/checkpointZnodeData";
            dataTree.createNode(stateValidationNode, SAMPLE_DATA, Ids.OPEN_ACL_UNSAFE, -1, -1, ZXID, TIME);

            ByteArrayOutputStream outputStreamBuffer = new ByteArrayOutputStream();
            BinaryOutputArchive runtimeOutputArchiver = BinaryOutputArchive.getArchive(outputStreamBuffer);

            dataTree.serialize(runtimeOutputArchiver, "snapshot_tag");
            byte[] binaryArchivalOutput = outputStreamBuffer.toByteArray();

            assertTrue(binaryArchivalOutput.length > 0, "Archival serialization byte streams should not be completely empty");

            DataTree restoredRuntimeEngineInstance = new DataTree();
            ByteArrayInputStream inputStreamBuffer = new ByteArrayInputStream(binaryArchivalOutput);
            BinaryInputArchive runtimeInputArchiver = BinaryInputArchive.getArchive(inputStreamBuffer);

            restoredRuntimeEngineInstance.deserialize(runtimeInputArchiver, "snapshot_tag");

            DataNode verifiedNodeCheckpoint = restoredRuntimeEngineInstance.getNode(stateValidationNode);
            assertNotNull(verifiedNodeCheckpoint, "Recovered tree environment failed lookup checks for registered historical targets");
            assertArrayEquals(SAMPLE_DATA, verifiedNodeCheckpoint.getData(), "Recovered memory array does not retain original structural bytes");
        }
    }
}