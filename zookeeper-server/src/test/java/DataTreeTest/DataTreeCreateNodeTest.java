package DataTreeTest;

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

public class DataTreeCreateNodeTest {

    private DataTree dataTree;

    private static final byte[] VALID_DATA = "data".getBytes();
    private static final List<ACL> VALID_ACL = ZooDefs.Ids.OPEN_ACL_UNSAFE;
    private static final long VALID_TIME = System.currentTimeMillis();

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    private void createValidNode(String path)
            throws KeeperException.NoNodeException, KeeperException.NodeExistsException {

        dataTree.createNode(
                path,
                VALID_DATA,
                VALID_ACL,
                -1L,
                0,
                1L,
                VALID_TIME
        );
    }

    private void assertNodeExists(String path) {
        assertNotNull(dataTree.getNode(path), "Il nodo " + path + " dovrebbe esistere");
    }

    private void assertNodeDoesNotExist(String path) {
        assertNull(dataTree.getNode(path), "Il nodo " + path + " non dovrebbe esistere");
    }

    static Stream<Arguments> validCreateNodeParameters() {
        return Stream.of(
                // T1 - path valido semplice
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T2 - path valido multilivello, padre presente
                Arguments.of(
                        Arrays.asList("/a"),
                        "/a/b",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a", "/a/b")
                ),

                // T10 - data vuota
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        new byte[0],
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T11 - data null
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        null,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T12 - data grande
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        new byte[1024 * 1024],
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T13 - ACL vuota
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        Collections.emptyList(),
                        -1L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T14 - ACL nulla
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        null,
                        -1L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T15 - ACL contenente elemento nullo
                // Comportamento osservato: il nodo viene creato
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        Collections.singletonList(null),
                        -1L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T16 - ephemeralOwner = -1
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T17 - ephemeralOwner = 1L
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        1L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T18 - ephemeralOwner = 0L
                // Caso valido: nodo persistente
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        0L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T19 - parentCVersion = 0
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T20 - parentCVersion = 1
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        1,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T21 - parentCVersion = -1
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        -1,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T22 - zxid = 1L
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T23 - zxid = 0L
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        0L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T24 - zxid = -1L
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        -1L,
                        VALID_TIME,
                        Arrays.asList("/a")
                ),

                // T25 - time corrente
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        System.currentTimeMillis(),
                        Arrays.asList("/a")
                ),

                // T26 - time = 0L
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        0L,
                        Arrays.asList("/a")
                ),

                // T27 - time = -1L
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        -1L,
                        Arrays.asList("/a")
                ),

                // T28 - ramo indipendente
                Arguments.of(
                        Arrays.asList("/a", "/x"),
                        "/x/y",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        VALID_TIME,
                        Arrays.asList("/a", "/x", "/x/y")
                )
        );
    }

    @ParameterizedTest(name = "{index}: createNode({1})")
    @MethodSource("validCreateNodeParameters")
    public void createNodeShouldCreateExpectedNodes(
            List<String> initialPaths,
            String pathToCreate,
            byte[] data,
            List<ACL> acl,
            long ephemeralOwner,
            int parentCVersion,
            long zxid,
            long time,
            List<String> expectedPaths
    ) throws Exception {

        for (String initialPath : initialPaths) {
            createValidNode(initialPath);
        }

        dataTree.createNode(
                pathToCreate,
                data,
                acl,
                ephemeralOwner,
                parentCVersion,
                zxid,
                time
        );

        for (String expectedPath : expectedPaths) {
            assertNodeExists(expectedPath);
        }
    }

    @Test
    public void createNodeShouldThrowNoNodeExceptionWhenParentDoesNotExist() {
        // T3 - path valido multilivello con padre assente

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.createNode(
                        "/a/b",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        VALID_TIME
                )
        );

        assertNodeDoesNotExist("/a/b");
    }

    @Test
    public void createNodeShouldThrowNodeExistsExceptionWhenNodeAlreadyExists() throws Exception {
        // T4 - nodo già presente

        createValidNode("/a");

        assertThrows(
                KeeperException.NodeExistsException.class,
                () -> dataTree.createNode(
                        "/a",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        2L,
                        VALID_TIME
                )
        );

        assertNodeExists("/a");
    }

    @Test
    public void createNodeOnRootShouldNotCorruptTree() {
        // T5 - path radice

        assertThrows(
                Exception.class,
                () -> dataTree.createNode(
                        "/",
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        VALID_TIME
                )
        );

        assertNodeExists("/");
    }

    @Test
    public void createNodeWithNullPathShouldNotCorruptTree() {
        // T6 - path nullo

        assertThrows(
                Exception.class,
                () -> dataTree.createNode(
                        null,
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        VALID_TIME
                )
        );

        assertNodeExists("/");
        assertNodeDoesNotExist("/a");
    }

    static Stream<Arguments> malformedPathParameters() {
        return Stream.of(
                // T7 - path vuoto
                Arguments.of(""),

                // T8 - path senza slash iniziale
                Arguments.of("a/b"),

                // T9 - path con doppio slash
                Arguments.of("/a//b")
        );
    }

    @ParameterizedTest(name = "{index}: malformed path = {0}")
    @MethodSource("malformedPathParameters")
    public void createNodeWithMalformedPathShouldNotCorruptTree(String malformedPath) {
        // T7, T8, T9 - path vuoto o malformato

        assertThrows(
                Exception.class,
                () -> dataTree.createNode(
                        malformedPath,
                        VALID_DATA,
                        VALID_ACL,
                        -1L,
                        0,
                        1L,
                        VALID_TIME
                )
        );

        assertNodeExists("/");
        assertNodeDoesNotExist("/a");
    }

    @Test
    public void createNodeWithEphemeralOwnerShouldRegisterEphemeralNode() throws Exception {
        // Verifica specifica T17

        long sessionId = 1L;

        dataTree.createNode(
                "/a",
                VALID_DATA,
                VALID_ACL,
                sessionId,
                0,
                1L,
                VALID_TIME
        );

        assertNodeExists("/a");
        assertTrue(dataTree.getEphemerals(sessionId).contains("/a"));
    }

    @Test
    public void createNodeWithZeroEphemeralOwnerShouldCreatePersistentNode() throws Exception {
        // Verifica specifica T18
        // ephemeralOwner = 0L indica nodo persistente

        long persistentOwner = 0L;

        dataTree.createNode(
                "/a",
                VALID_DATA,
                VALID_ACL,
                persistentOwner,
                0,
                1L,
                VALID_TIME
        );

        assertNodeExists("/a");

        Stat stat = dataTree.statNode("/a", null);
        assertEquals(0L, stat.getEphemeralOwner());

        assertFalse(dataTree.getEphemerals(persistentOwner).contains("/a"));
    }

    @Test
    public void createNodeShouldStoreEmptyDataCorrectly() throws Exception {
        // Verifica specifica T10

        dataTree.createNode(
                "/a",
                new byte[0],
                VALID_ACL,
                -1L,
                0,
                1L,
                VALID_TIME
        );

        byte[] storedData = dataTree.getData("/a", new Stat(), null);

        assertNotNull(storedData);
        assertEquals(0, storedData.length);
    }

    @Test
    public void createNodeShouldStoreNullDataCorrectly() throws Exception {
        // Verifica specifica T11

        dataTree.createNode(
                "/a",
                null,
                VALID_ACL,
                -1L,
                0,
                1L,
                VALID_TIME
        );

        byte[] storedData = dataTree.getData("/a", new Stat(), null);

        assertNull(storedData);
    }

    @Test
    public void createNodeShouldStoreLargeDataCorrectly() throws Exception {
        // Verifica specifica T12

        byte[] largeData = new byte[1024 * 1024];

        dataTree.createNode(
                "/a",
                largeData,
                VALID_ACL,
                -1L,
                0,
                1L,
                VALID_TIME
        );

        byte[] storedData = dataTree.getData("/a", new Stat(), null);

        assertArrayEquals(largeData, storedData);
    }

    @Test
    public void createNodeOnIndependentBranchShouldNotAlterExistingBranch() throws Exception {
        // Verifica specifica T28

        createValidNode("/a");
        createValidNode("/x");

        dataTree.createNode(
                "/x/y",
                VALID_DATA,
                VALID_ACL,
                -1L,
                0,
                1L,
                VALID_TIME
        );

        assertNodeExists("/a");
        assertNodeExists("/x");
        assertNodeExists("/x/y");
    }
}