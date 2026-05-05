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
import java.util.Collections;
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
        NULL,
        PRE_VALUED
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

    private void assertNodeExists(String path) {
        assertNotNull(dataTree.getNode(path), "Il nodo " + path + " dovrebbe esistere");
    }

    private void assertNodeDoesNotExist(String path) {
        assertNull(dataTree.getNode(path), "Il nodo " + path + " non dovrebbe esistere");
    }

    private void assertChildrenEqual(List<String> actualChildren, String... expectedChildren) {
        assertNotNull(actualChildren, "La lista dei figli non dovrebbe essere null");

        Set<String> actualSet = new HashSet<>(actualChildren);
        Set<String> expectedSet = new HashSet<>(Arrays.asList(expectedChildren));

        assertEquals(expectedSet, actualSet, "La lista dei figli restituita non è corretta");
    }

    private Stat buildStat(StatCase statCase) {
        if (statCase == StatCase.NULL) {
            return null;
        }

        Stat stat = new Stat();

        if (statCase == StatCase.PRE_VALUED) {
            stat.setVersion(999);
            stat.setCversion(999);
            stat.setMzxid(999L);
            stat.setMtime(999L);
        }

        return stat;
    }

    private void assertTreeStillUsable() throws Exception {
        createValidNode("/safe");
        assertNodeExists("/safe");

        List<String> children = dataTree.getChildren("/safe", new Stat(), null);

        assertNotNull(children);
        assertTrue(children.isEmpty());
    }

    static Stream<Arguments> validGetChildrenParameters() {
        return Stream.of(
                // T1 - path valido semplice, nodo presente senza figli, stat valido, watcher null
                Arguments.of(
                        Arrays.asList("/a"),
                        "/a",
                        StatCase.VALID,
                        false,
                        Collections.emptyList(),
                        Collections.emptyList()
                ),

                // T2 - path valido multilivello, nodo presente senza figli, stat valido, watcher null
                Arguments.of(
                        Arrays.asList("/a", "/a/b"),
                        "/a/b",
                        StatCase.VALID,
                        false,
                        Collections.emptyList(),
                        Arrays.asList("/a")
                ),

                // T8 - path valido semplice, nodo con un solo figlio, stat null, watcher null
                Arguments.of(
                        Arrays.asList("/a", "/a/b"),
                        "/a",
                        StatCase.NULL,
                        false,
                        Arrays.asList("b"),
                        Collections.emptyList()
                ),

                // T9 - path valido semplice, nodo con un solo figlio, stat già valorizzato, watcher null
                Arguments.of(
                        Arrays.asList("/a", "/a/b"),
                        "/a",
                        StatCase.PRE_VALUED,
                        false,
                        Arrays.asList("b"),
                        Collections.emptyList()
                ),

                // T10 - path valido semplice, nodo senza figli, stat valido, watcher valido
                Arguments.of(
                        Arrays.asList("/a"),
                        "/a",
                        StatCase.VALID,
                        true,
                        Collections.emptyList(),
                        Collections.emptyList()
                ),

                // T11 - path valido semplice, nodo con un solo figlio
                Arguments.of(
                        Arrays.asList("/a", "/a/b"),
                        "/a",
                        StatCase.VALID,
                        false,
                        Arrays.asList("b"),
                        Collections.emptyList()
                ),

                // T12 - path valido semplice, nodo con più figli
                Arguments.of(
                        Arrays.asList("/a", "/a/b", "/a/c"),
                        "/a",
                        StatCase.VALID,
                        false,
                        Arrays.asList("b", "c"),
                        Collections.emptyList()
                ),

                // T14 - DataTree con più rami indipendenti, watcher null
                Arguments.of(
                        Arrays.asList("/a", "/a/b", "/x", "/x/y"),
                        "/x",
                        StatCase.VALID,
                        false,
                        Arrays.asList("y"),
                        Arrays.asList("/a", "/a/b")
                ),

                // T15 - path radice, stat null, watcher null
                Arguments.of(
                        Collections.emptyList(),
                        "/",
                        StatCase.NULL,
                        false,
                        Collections.emptyList(),
                        Collections.emptyList()
                ),

                // T16 - path radice, stat già valorizzato, watcher null
                Arguments.of(
                        Collections.emptyList(),
                        "/",
                        StatCase.PRE_VALUED,
                        false,
                        Collections.emptyList(),
                        Collections.emptyList()
                ),

                // T17 - path valido multilivello, stat null, watcher null
                Arguments.of(
                        Arrays.asList("/a", "/a/b"),
                        "/a/b",
                        StatCase.NULL,
                        false,
                        Collections.emptyList(),
                        Arrays.asList("/a")
                ),

                // T18 - path valido multilivello, stat valido, watcher valido
                Arguments.of(
                        Arrays.asList("/a", "/a/b"),
                        "/a/b",
                        StatCase.VALID,
                        true,
                        Collections.emptyList(),
                        Arrays.asList("/a")
                ),

                // T19 - path valido semplice, nodo con più figli, watcher valido
                Arguments.of(
                        Arrays.asList("/a", "/a/b", "/a/c"),
                        "/a",
                        StatCase.VALID,
                        true,
                        Arrays.asList("b", "c"),
                        Collections.emptyList()
                ),

                // T20 - DataTree con rami indipendenti, watcher valido
                Arguments.of(
                        Arrays.asList("/a", "/a/b", "/x", "/x/y"),
                        "/x",
                        StatCase.VALID,
                        true,
                        Arrays.asList("y"),
                        Arrays.asList("/a", "/a/b")
                )
        );
    }

    @ParameterizedTest(name = "{index}: getChildren({1})")
    @MethodSource("validGetChildrenParameters")
    public void getChildrenShouldReturnExpectedChildren(
            List<String> initialPaths,
            String pathToQuery,
            StatCase statCase,
            boolean useWatcher,
            List<String> expectedChildren,
            List<String> unchangedPaths
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

        assertChildrenEqual(actualChildren, expectedChildren.toArray(new String[0]));

        if (statCase == StatCase.PRE_VALUED) {
            assertNotNull(stat);
            assertNotEquals(999, stat.getCversion(), "Lo Stat dovrebbe essere aggiornato dal metodo");
        }

        if (useWatcher) {
            assertNotNull(watcher, "Il watcher valido dovrebbe essere accettato");
            assertEquals(
                    0,
                    watcher.getEventCount(),
                    "La getChildren registra il watcher ma non deve generare eventi immediati"
            );
        }

        for (String unchangedPath : unchangedPaths) {
            assertNodeExists(unchangedPath);
        }
    }

    @Test
    public void getChildrenOnRootInInitialTreeShouldReturnNonNullList() throws Exception {
        // T3 - path radice, DataTree nello stato iniziale, stat valido, watcher null

        Stat stat = new Stat();

        List<String> children = dataTree.getChildren(
                "/",
                stat,
                null
        );

        assertNotNull(children);
        assertNodeExists("/");
    }

    @Test
    public void getChildrenWithNullPathShouldThrowExceptionAndNotCorruptTree() throws Exception {
        // T4 - path nullo, DataTree nello stato iniziale

        assertThrows(
                Exception.class,
                () -> dataTree.getChildren(
                        null,
                        new Stat(),
                        null
                )
        );

        assertTreeStillUsable();
    }

    @Test
    public void getChildrenWithEmptyPathShouldThrowExceptionOrNotCorruptTree() throws Exception {
        // T5 - path vuoto o malformato, path = ""

        try {
            dataTree.getChildren(
                    "",
                    new Stat(),
                    null
            );
        } catch (Exception ignored) {
            // Caso accettabile: path vuoto gestito con eccezione.
        }

        assertTreeStillUsable();
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

        assertNodeDoesNotExist(malformedPath);
    }

    @Test
    public void getChildrenShouldThrowNoNodeExceptionWhenNodeDoesNotExist() {
        // T13 - path valido semplice, nodo da interrogare assente

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.getChildren(
                        "/x",
                        new Stat(),
                        null
                )
        );

        assertNodeDoesNotExist("/x");
    }
}