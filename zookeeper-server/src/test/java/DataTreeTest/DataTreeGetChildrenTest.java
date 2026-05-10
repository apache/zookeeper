package DataTreeTest;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeGetChildrenTest {

    private DataTree dataTree;

    private static final byte[] VALID_DATA = "data".getBytes();
    private static final List<ACL> VALID_ACL = ZooDefs.Ids.OPEN_ACL_UNSAFE;
    private static final long VALID_TIME = System.currentTimeMillis();

    private enum StatCase {
        VALID,
        NULL
    }

    private static class ValidWatcher implements Watcher {
        private int eventCount = 0;

        @Override
        public void process(WatchedEvent event) {
            eventCount++;
        }

        public int getEventCount() {
            return eventCount;
        }
    }

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
                -1,
                1L,
                VALID_TIME
        );
    }

    private Stat buildPreValuedStat() {
        Stat stat = new Stat();
        stat.setVersion(999);
        stat.setCversion(999);
        stat.setMzxid(999L);
        stat.setMtime(999L);
        return stat;
    }

    private Stat buildStat(StatCase statCase) {
        if (statCase == StatCase.NULL) {
            return null;
        }

        return new Stat();
    }

    private void assertChildrenEqual(List<String> actualChildren, String... expectedChildren) {
        Set<String> actualSet = actualChildren == null
                ? null
                : new HashSet<>(actualChildren);

        Set<String> expectedSet = new HashSet<>(Arrays.asList(expectedChildren));

        assertEquals(expectedSet, actualSet, "La lista dei figli restituita non è corretta");
    }

    private void executeEmptyPathAndReuseTree() throws Exception {
        try {
            dataTree.getChildren("", new Stat(), null);
        } catch (Exception ignored) {
            // Caso accettabile: path vuoto gestito con eccezione.
        }

        createValidNode("/safe");
        dataTree.getChildren("/safe", new Stat(), null);
    }

    static Stream<Arguments> childrenEqualityParameters() {
        return Stream.of(
                // T1 - path valido semplice, nodo presente senza figli
                Arguments.of(
                        Arrays.asList("/a"),
                        "/a",
                        StatCase.VALID,
                        false,
                        new String[]{}
                ),

                // T2 - path valido multilivello, nodo presente senza figli
                Arguments.of(
                        Arrays.asList("/a", "/a/b"),
                        "/a/b",
                        StatCase.VALID,
                        false,
                        new String[]{}
                ),

                // T8 - path valido semplice, nodo con un solo figlio, stat null
                Arguments.of(
                        Arrays.asList("/a", "/a/b"),
                        "/a",
                        StatCase.NULL,
                        false,
                        new String[]{"b"}
                ),

                // T11 - path valido semplice, nodo con un solo figlio
                Arguments.of(
                        Arrays.asList("/a", "/a/b"),
                        "/a",
                        StatCase.VALID,
                        false,
                        new String[]{"b"}
                ),

                // T12 - path valido semplice, nodo con più figli
                Arguments.of(
                        Arrays.asList("/a", "/a/b", "/a/c"),
                        "/a",
                        StatCase.VALID,
                        false,
                        new String[]{"b", "c"}
                ),

                // T14 - path valido semplice in albero ramificato
                Arguments.of(
                        Arrays.asList("/a", "/a/b", "/x", "/x/y"),
                        "/x",
                        StatCase.VALID,
                        false,
                        new String[]{"y"}
                ),

                // T17 - path valido multilivello, stat null
                Arguments.of(
                        Arrays.asList("/a", "/a/b"),
                        "/a/b",
                        StatCase.NULL,
                        false,
                        new String[]{}
                ),

                // T19 - path valido semplice, nodo con più figli, watcher valido
                Arguments.of(
                        Arrays.asList("/a", "/a/b", "/a/c"),
                        "/a",
                        StatCase.VALID,
                        true,
                        new String[]{"b", "c"}
                ),

                // T20 - path valido semplice in albero ramificato, watcher valido
                Arguments.of(
                        Arrays.asList("/a", "/a/b", "/x", "/x/y"),
                        "/x",
                        StatCase.VALID,
                        true,
                        new String[]{"y"}
                )
        );
    }

    @ParameterizedTest(name = "{index}: getChildren({1})")
    @MethodSource("childrenEqualityParameters")
    public void getChildrenShouldReturnExpectedChildren(
            List<String> initialPaths,
            String pathToQuery,
            StatCase statCase,
            boolean useWatcher,
            String[] expectedChildren
    ) throws Exception {

        for (String initialPath : initialPaths) {
            createValidNode(initialPath);
        }

        Stat stat = buildStat(statCase);
        ValidWatcher watcher = useWatcher ? new ValidWatcher() : null;

        List<String> actualChildren = dataTree.getChildren(
                pathToQuery,
                stat,
                watcher
        );

        assertChildrenEqual(actualChildren, expectedChildren);
    }

    @Test
    public void getChildrenOnRootInInitialTreeShouldReturnNonNullList() throws Exception {
        // T3 - path radice, DataTree nello stato iniziale, stat valido

        List<String> children = dataTree.getChildren(
                "/",
                new Stat(),
                null
        );

        assertNotNull(children);
    }

    @Test
    public void getChildrenWithNullPathShouldThrowException() {
        // T4 - path nullo

        assertThrows(
                Exception.class,
                () -> dataTree.getChildren(
                        null,
                        new Stat(),
                        null
                )
        );
    }

    @Test
    public void getChildrenWithEmptyPathShouldNotCorruptTree() {
        // T5 - path vuoto

        assertDoesNotThrow(this::executeEmptyPathAndReuseTree);
    }

    static Stream<Arguments> malformedPathParameters() {
        return Stream.of(
                // T6 - path malformato senza slash iniziale
                Arguments.of("a/b"),

                // T7 - path malformato con doppio slash
                Arguments.of("/a//b")
        );
    }

    @ParameterizedTest(name = "{index}: malformed path = {0}")
    @MethodSource("malformedPathParameters")
    public void getChildrenWithMalformedPathShouldThrowNoNodeException(String malformedPath) {
        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.getChildren(
                        malformedPath,
                        new Stat(),
                        null
                )
        );
    }

    @Test
    public void getChildrenWithPreValuedStatShouldUpdateStat() throws Exception {
        // T9 - path valido semplice, nodo con un solo figlio, stat già valorizzato

        createValidNode("/a");
        createValidNode("/a/b");

        Stat stat = buildPreValuedStat();

        dataTree.getChildren(
                "/a",
                stat,
                null
        );

        assertNotEquals(999, stat.getCversion());
    }

    @Test
    public void getChildrenWithWatcherShouldNotGenerateImmediateEvent() throws Exception {
        // T10 - path valido semplice, nodo senza figli, watcher valido

        createValidNode("/a");

        ValidWatcher watcher = new ValidWatcher();

        dataTree.getChildren(
                "/a",
                new Stat(),
                watcher
        );

        assertEquals(0, watcher.getEventCount());
    }

    @Test
    public void getChildrenShouldThrowNoNodeExceptionWhenNodeDoesNotExist() {
        // T13 - path valido semplice, nodo assente

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.getChildren(
                        "/x",
                        new Stat(),
                        null
                )
        );
    }

    @Test
    public void getChildrenOnRootWithNullStatShouldReturnNonNullList() throws Exception {
        // T15 - path radice, stat null

        List<String> actualChildren = dataTree.getChildren(
                "/",
                null,
                null
        );

        assertNotNull(actualChildren);
    }

    @Test
    public void getChildrenOnRootWithPreValuedStatShouldUpdateStat() throws Exception {
        // T16 - path radice, stat già valorizzato

        Stat stat = buildPreValuedStat();

        dataTree.getChildren(
                "/",
                stat,
                null
        );

        assertNotEquals(999, stat.getCversion());
    }

    @Test
    public void getChildrenOnMultilevelPathWithWatcherShouldNotGenerateImmediateEvent() throws Exception {
        // T18 - path valido multilivello, watcher valido

        createValidNode("/a");
        createValidNode("/a/b");

        ValidWatcher watcher = new ValidWatcher();

        dataTree.getChildren(
                "/a/b",
                new Stat(),
                watcher
        );

        assertEquals(0, watcher.getEventCount());
    }
}