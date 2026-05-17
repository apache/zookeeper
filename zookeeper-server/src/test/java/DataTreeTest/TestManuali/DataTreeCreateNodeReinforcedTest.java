package DataTreeTest.TestManuali;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeCreateNodeReinforcedTest {

    private DataTree dataTree;

    private static final byte[] VALID_DATA = "data".getBytes();
    private static final byte[] OTHER_DATA = "other".getBytes();
    private static final List<ACL> VALID_ACL = ZooDefs.Ids.OPEN_ACL_UNSAFE;

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    private void createValidNode(String path) throws Exception {
        dataTree.createNode(
                path,
                VALID_DATA,
                VALID_ACL,
                0L,
                -1,
                1L,
                100L
        );
    }

    private String parentPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        String parent = path.substring(0, lastSlash);
        return parent.isEmpty() ? "/" : parent;
    }

    private String childName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void assertNodeExists(String path) {
        assertNotNull(dataTree.getNode(path), "Il nodo " + path + " dovrebbe esistere");
    }

    private void assertNodeDoesNotExist(String path) {
        assertNull(dataTree.getNode(path), "Il nodo " + path + " non dovrebbe esistere");
    }

    private void assertParentContainsChild(String path) throws Exception {
        String parent = parentPath(path);
        String child = childName(path);

        assertTrue(
                dataTree.getChildren(parent, null, null).contains(child),
                "Il padre " + parent + " dovrebbe contenere il figlio " + child
        );
    }

    private void assertStoredDataEquals(String path, byte[] expectedData) throws Exception {
        byte[] actualData = dataTree.getData(path, new Stat(), null);

        if (expectedData == null) {
            assertNull(actualData);
        } else {
            assertArrayEquals(expectedData, actualData);
        }
    }

    private void assertCreatedNodeMetadata(
            String path,
            long expectedZxid,
            long expectedTime,
            long expectedEphemeralOwner
    ) throws Exception {
        Stat stat = dataTree.statNode(path, null);

        assertEquals(expectedZxid, stat.getCzxid());
        assertEquals(expectedZxid, stat.getMzxid());
        assertEquals(expectedTime, stat.getCtime());
        assertEquals(expectedTime, stat.getMtime());
        assertEquals(expectedEphemeralOwner, stat.getEphemeralOwner());
    }

    private void assertCreateNodeState(
            String path,
            byte[] expectedData,
            long zxid,
            long time,
            long ephemeralOwner
    ) throws Exception {
        assertNodeExists(path);
        assertParentContainsChild(path);
        assertStoredDataEquals(path, expectedData);
        assertCreatedNodeMetadata(path, zxid, time, ephemeralOwner);
    }

    private void assertParentMetadataAfterCreate(
            String parent,
            Stat before,
            int parentCVersion,
            long zxid
    ) throws Exception {
        Stat after = dataTree.statNode(parent, null);

        int effectiveParentCVersion =
                parentCVersion == -1 ? before.getCversion() + 1 : parentCVersion;

        int expectedCversion =
                effectiveParentCVersion > before.getCversion()
                        ? effectiveParentCVersion
                        : before.getCversion();

        long expectedPzxid =
                effectiveParentCVersion > before.getCversion()
                        ? zxid
                        : before.getPzxid();

        assertEquals(expectedCversion, after.getCversion());
        assertEquals(expectedPzxid, after.getPzxid());
    }

    // T1, T2
    static Stream<Arguments> validPathParameters() {
        return Stream.of(
                Arguments.of("T1", Collections.emptyList(), "/a"),
                Arguments.of("T2", Collections.singletonList("/a"), "/a/b")
        );
    }

    @ParameterizedTest(name = "{0}: createNode valid path {2}")
    @MethodSource("validPathParameters")
    public void createNodeShouldCreateNodeWithConsistentState(
            String testId,
            List<String> initialPaths,
            String pathToCreate
    ) throws Exception {

        for (String initialPath : initialPaths) {
            createValidNode(initialPath);
        }

        long zxid = 10L;
        long time = 1000L;

        dataTree.createNode(
                pathToCreate,
                VALID_DATA,
                VALID_ACL,
                0L,
                -1,
                zxid,
                time
        );

        assertCreateNodeState(pathToCreate, VALID_DATA, zxid, time, 0L);
    }

    // T3
    @Test
    public void createNodeShouldThrowNoNodeExceptionWhenParentDoesNotExist() {
        int oldNodeCount = dataTree.getNodeCount();

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.createNode(
                        "/a/b",
                        VALID_DATA,
                        VALID_ACL,
                        0L,
                        -1,
                        1L,
                        100L
                )
        );

        assertNodeDoesNotExist("/a/b");
        assertEquals(oldNodeCount, dataTree.getNodeCount());
    }

    // T4
    @Test
    public void createNodeShouldNotOverwriteExistingNodeWhenNodeAlreadyExists() throws Exception {
        long firstZxid = 1L;
        long firstTime = 100L;

        dataTree.createNode(
                "/a",
                VALID_DATA,
                VALID_ACL,
                0L,
                -1,
                firstZxid,
                firstTime
        );

        int oldNodeCount = dataTree.getNodeCount();
        Stat oldStat = dataTree.statNode("/a", null);

        assertThrows(
                KeeperException.NodeExistsException.class,
                () -> dataTree.createNode(
                        "/a",
                        OTHER_DATA,
                        Collections.emptyList(),
                        0L,
                        -1,
                        2L,
                        200L
                )
        );

        assertNodeExists("/a");
        assertStoredDataEquals("/a", VALID_DATA);
        assertEquals(oldNodeCount, dataTree.getNodeCount());

        Stat newStat = dataTree.statNode("/a", null);
        assertEquals(oldStat.getCzxid(), newStat.getCzxid());
        assertEquals(oldStat.getMzxid(), newStat.getMzxid());
        assertEquals(oldStat.getCtime(), newStat.getCtime());
        assertEquals(oldStat.getMtime(), newStat.getMtime());
    }

    // T5, T6, T7, T8, T9
    static Stream<Arguments> invalidPathParameters() {
        return Stream.of(
                Arguments.of("T5", "/"),
                Arguments.of("T6", null),
                Arguments.of("T7", ""),
                Arguments.of("T8", "a/b"),
                Arguments.of("T9", "/a//b")
        );
    }

    @ParameterizedTest(name = "{0}: invalid path")
    @MethodSource("invalidPathParameters")
    public void createNodeWithInvalidPathShouldNotCorruptTree(
            String testId,
            String invalidPath
    ) {
        int oldNodeCount = dataTree.getNodeCount();

        assertThrows(
                Exception.class,
                () -> dataTree.createNode(
                        invalidPath,
                        VALID_DATA,
                        VALID_ACL,
                        0L,
                        -1,
                        1L,
                        100L
                )
        );

        assertNodeExists("/");
        assertEquals(oldNodeCount, dataTree.getNodeCount());
        assertNodeDoesNotExist("/a");
    }

    // T10, T11, T12
    static Stream<Arguments> dataParameters() {
        byte[] largeData = new byte[1024 * 1024];
        Arrays.fill(largeData, (byte) 1);

        return Stream.of(
                Arguments.of("T10", new byte[0]),
                Arguments.of("T11", null),
                Arguments.of("T12", largeData)
        );
    }

    @ParameterizedTest(name = "{0}: createNode data boundary")
    @MethodSource("dataParameters")
    public void createNodeShouldStoreDataConsistently(
            String testId,
            byte[] data
    ) throws Exception {
        long oldSize = dataTree.approximateDataSize();

        dataTree.createNode(
                "/a",
                data,
                VALID_ACL,
                0L,
                -1,
                1L,
                100L
        );

        assertNodeExists("/a");
        assertParentContainsChild("/a");
        assertStoredDataEquals("/a", data);
        assertTrue(dataTree.approximateDataSize() >= oldSize);
    }

    // T13
    @Test
    public void createNodeShouldStoreEmptyAclConsistently() throws Exception {
        dataTree.createNode(
                "/a",
                VALID_DATA,
                Collections.emptyList(),
                0L,
                -1,
                1L,
                100L
        );

        assertNodeExists("/a");
        assertParentContainsChild("/a");
        assertTrue(dataTree.getACL("/a", new Stat()).isEmpty());
    }

    // T14, T15
    static Stream<Arguments> specialAclParameters() {
        return Stream.of(
                Arguments.of("T14", null),
                Arguments.of("T15", Collections.singletonList(null))
        );
    }

    @ParameterizedTest(name = "{0}: createNode special ACL")
    @MethodSource("specialAclParameters")
    public void createNodeWithSpecialAclShouldKeepTreeConsistent(
            String testId,
            List<ACL> acl
    ) throws Exception {
        dataTree.createNode(
                "/a",
                VALID_DATA,
                acl,
                0L,
                -1,
                1L,
                100L
        );

        assertNodeExists("/a");
        assertParentContainsChild("/a");
        assertStoredDataEquals("/a", VALID_DATA);
    }

    // T16, T17, T18
    static Stream<Arguments> ephemeralOwnerParameters() {
        return Stream.of(
                Arguments.of("T16", -1L),
                Arguments.of("T17", 1L),
                Arguments.of("T18", 0L)
        );
    }

    @ParameterizedTest(name = "{0}: ephemeralOwner = {1}")
    @MethodSource("ephemeralOwnerParameters")
    public void createNodeShouldStoreEphemeralOwnerConsistently(
            String testId,
            long ephemeralOwner
    ) throws Exception {
        dataTree.createNode(
                "/a",
                VALID_DATA,
                VALID_ACL,
                ephemeralOwner,
                -1,
                1L,
                100L
        );

        Stat stat = dataTree.statNode("/a", null);

        assertNodeExists("/a");
        assertParentContainsChild("/a");
        assertEquals(ephemeralOwner, stat.getEphemeralOwner());

        if (ephemeralOwner != 0L) {
            assertTrue(dataTree.getEphemerals(ephemeralOwner).contains("/a"));
        } else {
            assertFalse(dataTree.getEphemerals(ephemeralOwner).contains("/a"));
        }
    }

    // T19, T20, T21
    static Stream<Arguments> parentCVersionParameters() {
        return Stream.of(
                Arguments.of("T19", 0),
                Arguments.of("T20", 1),
                Arguments.of("T21", -1)
        );
    }



    // T22, T23, T24
    static Stream<Arguments> zxidParameters() {
        return Stream.of(
                Arguments.of("T22", 5L),
                Arguments.of("T23", 0L),
                Arguments.of("T24", -1L)
        );
    }

    @ParameterizedTest(name = "{0}: zxid = {1}")
    @MethodSource("zxidParameters")
    public void createNodeShouldStoreZxidInNodeMetadata(
            String testId,
            long zxid
    ) throws Exception {
        dataTree.createNode(
                "/a",
                VALID_DATA,
                VALID_ACL,
                0L,
                -1,
                zxid,
                100L
        );

        Stat stat = dataTree.statNode("/a", null);

        assertNodeExists("/a");
        assertParentContainsChild("/a");
        assertEquals(zxid, stat.getCzxid());
        assertEquals(zxid, stat.getMzxid());
    }

    // T25, T26, T27
    static Stream<Arguments> timeParameters() {
        return Stream.of(
                Arguments.of("T25", System.currentTimeMillis()),
                Arguments.of("T26", 0L),
                Arguments.of("T27", -1L)
        );
    }

    @ParameterizedTest(name = "{0}: time = {1}")
    @MethodSource("timeParameters")
    public void createNodeShouldStoreTimeInNodeMetadata(
            String testId,
            long time
    ) throws Exception {
        dataTree.createNode(
                "/a",
                VALID_DATA,
                VALID_ACL,
                0L,
                -1,
                1L,
                time
        );

        Stat stat = dataTree.statNode("/a", null);

        assertNodeExists("/a");
        assertParentContainsChild("/a");
        assertEquals(time, stat.getCtime());
        assertEquals(time, stat.getMtime());
    }

    // T28
    @Test
    public void createNodeOnIndependentBranchShouldNotAlterExistingBranch() throws Exception {
        createValidNode("/a");
        createValidNode("/x");

        byte[] oldDataA = dataTree.getData("/a", new Stat(), null);

        dataTree.createNode(
                "/x/y",
                VALID_DATA,
                VALID_ACL,
                0L,
                -1,
                10L,
                100L
        );

        assertNodeExists("/a");
        assertNodeExists("/x");
        assertNodeExists("/x/y");

        assertParentContainsChild("/x/y");
        assertTrue(dataTree.getChildren("/", null, null).contains("a"));
        assertTrue(dataTree.getChildren("/", null, null).contains("x"));

        assertStoredDataEquals("/a", oldDataA);
        assertStoredDataEquals("/x/y", VALID_DATA);
        assertFalse(dataTree.getChildren("/a", null, null).contains("y"));
    }
}