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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class DataTreeStatNodeTest {

    private DataTree dataTree;

    private static final byte[] VALID_DATA = "data".getBytes();
    private static final byte[] CHILD_DATA = "child-data".getBytes();
    private static final byte[] NEW_DATA = "new-data".getBytes();

    private static final List<ACL> VALID_ACL = ZooDefs.Ids.OPEN_ACL_UNSAFE;
    private static final long VALID_TIME = System.currentTimeMillis();

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    private void createValidNode(String path) throws Exception {
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

    private void createValidNode(String path, byte[] data) throws Exception {
        dataTree.createNode(
                path,
                data,
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

    private void assertValidStatForCreatedNode(Stat stat) {
        assertNotNull(stat, "Lo Stat restituito non dovrebbe essere null");
        assertEquals(1L, stat.getCzxid(), "Lo czxid dovrebbe corrispondere allo zxid di creazione");
        assertEquals(1L, stat.getMzxid(), "Lo mzxid iniziale dovrebbe corrispondere allo zxid di creazione");
        assertEquals(0, stat.getVersion(), "La version iniziale dovrebbe essere 0");
    }

    @Test
    public void statNodeShouldReturnStatForExistingSimplePath() throws Exception {
        // T1 - path valido semplice, nodo da interrogare presente, watcher null

        createValidNode("/a", VALID_DATA);

        Stat stat = dataTree.statNode("/a", null);

        assertValidStatForCreatedNode(stat);
        assertNodeExists("/a");
    }

    @Test
    public void statNodeShouldReturnStatForExistingMultilevelPath() throws Exception {
        // T2 - path valido multilivello, nodo da interrogare presente in un path multilivello

        createValidNode("/a", VALID_DATA);
        createValidNode("/a/b", CHILD_DATA);

        Stat stat = dataTree.statNode("/a/b", null);

        assertValidStatForCreatedNode(stat);
        assertNodeExists("/a");
        assertNodeExists("/a/b");
    }

    @Test
    public void statNodeShouldReturnNonNullAndCoherentStatForExistingNode() throws Exception {
        // T3 - path valido semplice, Stat non nullo e coerente con il nodo creato

        createValidNode("/a", VALID_DATA);

        Stat stat = dataTree.statNode("/a", null);

        assertValidStatForCreatedNode(stat);
        assertNodeExists("/a");
    }

    @Test
    public void statNodeShouldReturnStatForChildNode() throws Exception {
        // T4 - path valido multilivello, Stat del nodo figlio restituito correttamente

        createValidNode("/a", VALID_DATA);
        createValidNode("/a/b", CHILD_DATA);

        Stat stat = dataTree.statNode("/a/b", null);

        assertValidStatForCreatedNode(stat);
        assertNodeExists("/a/b");
    }

    @Test
    public void statNodeShouldThrowNoNodeExceptionForMissingMultilevelPath() {
        // T5 - path valido multilivello, nodo da interrogare assente

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.statNode("/x/y", null)
        );

        assertNodeDoesNotExist("/x");
        assertNodeDoesNotExist("/x/y");
        assertNodeExists("/");
    }

    @Test
    public void statNodeOnRootInInitialTreeShouldReturnRootStat() throws Exception {
        // T6 - path radice, DataTree nello stato iniziale

        Stat stat = dataTree.statNode("/", null);

        assertNotNull(stat, "Lo Stat della root non dovrebbe essere null");
        assertNodeExists("/");
    }

    @Test
    public void statNodeWithNullPathShouldNotCorruptTree() {
        // T7 - path nullo

        assertThrows(
                Exception.class,
                () -> dataTree.statNode(null, null)
        );

        assertNodeExists("/");
    }

    @Test
    public void statNodeWithEmptyPathShouldNotCorruptTree() {
        // T8 - path vuoto o malformato: ""

        assertThrows(
                Exception.class,
                () -> dataTree.statNode("", null)
        );

        assertNodeExists("/");
    }

    @Test
    public void statNodeWithPathWithoutInitialSlashShouldNotCorruptTree() {
        // T9 - path vuoto o malformato: "a/b"

        assertThrows(
                Exception.class,
                () -> dataTree.statNode("a/b", null)
        );

        assertNodeExists("/");
    }

    @Test
    public void statNodeWithDoubleSlashPathShouldNotCorruptTree() {
        // T10 - path vuoto o malformato: "/a//b"

        assertThrows(
                Exception.class,
                () -> dataTree.statNode("/a//b", null)
        );

        assertNodeExists("/");
    }

    @Test
    public void statNodeShouldAcceptAndRegisterValidWatcherMock() throws Exception {
        // T11 - watcher valido: mock Mockito dell'interfaccia Watcher

        createValidNode("/a", VALID_DATA);

        Watcher watcher = mock(Watcher.class);

        Stat stat = dataTree.statNode("/a", watcher);

        assertValidStatForCreatedNode(stat);

        // statNode registra il watcher, ma non deve invocare direttamente process(...)
        verify(watcher, never()).process(any(WatchedEvent.class));

        dataTree.setData(
                "/a",
                NEW_DATA,
                -1,
                2L,
                System.currentTimeMillis()
        );

        // Dopo la modifica del nodo, il watcher registrato deve essere notificato
        verify(watcher, atLeastOnce()).process(any(WatchedEvent.class));
    }

    @Test
    public void statNodeWithNullWatcherShouldReturnStatWithoutRegisteringExplicitWatch() throws Exception {
        // T12 - watcher = null

        createValidNode("/a", VALID_DATA);

        Stat stat = dataTree.statNode("/a", null);

        assertValidStatForCreatedNode(stat);

        assertDoesNotThrow(() -> dataTree.setData(
                "/a",
                NEW_DATA,
                -1,
                2L,
                System.currentTimeMillis()
        ));

        Stat updatedStat = dataTree.statNode("/a", null);

        assertNotNull(updatedStat);
        assertEquals(2L, updatedStat.getMzxid());
    }

    @Test
    public void statNodeShouldThrowNoNodeExceptionForMissingSimplePath() {
        // T13 - path valido semplice, nodo da interrogare assente

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.statNode("/a", null)
        );

        assertNodeDoesNotExist("/a");
        assertNodeExists("/");
    }

    @Test
    public void statNodeOnIndependentBranchShouldNotAlterOtherBranches() throws Exception {
        // T14 - DataTree con più rami indipendenti

        createValidNode("/a", VALID_DATA);
        createValidNode("/x", VALID_DATA);
        createValidNode("/x/y", CHILD_DATA);

        Stat stat = dataTree.statNode("/x/y", null);

        assertValidStatForCreatedNode(stat);

        assertNodeExists("/a");
        assertNodeExists("/x");
        assertNodeExists("/x/y");

        Stat statA = dataTree.statNode("/a", null);
        Stat statXY = dataTree.statNode("/x/y", null);

        assertNotNull(statA);
        assertNotNull(statXY);
    }
}