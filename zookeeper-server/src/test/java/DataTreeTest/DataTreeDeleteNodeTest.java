package DataTreeTest;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.DataTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeDeleteNodeTest {

    private DataTree dataTree;

    private static final byte[] VALID_DATA = "data".getBytes();
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

    private void assertNodeExists(String path) {
        assertNotNull(dataTree.getNode(path), "Il nodo " + path + " dovrebbe esistere");
    }

    private void assertNodeDoesNotExist(String path) {
        assertNull(dataTree.getNode(path), "Il nodo " + path + " non dovrebbe esistere");
    }

    @Test
    public void deleteNodeShouldRemoveExistingSimpleLeafNode() throws Exception {
        // T1 - path valido semplice, nodo da cancellare presente come foglia, zxid = 1L

        createValidNode("/a");

        dataTree.deleteNode("/a", 1L);

        assertNodeDoesNotExist("/a");
        assertNodeExists("/");
    }

    @Test
    public void deleteNodeShouldThrowNoNodeExceptionWhenOnlyRootExists() {
        // T2 - path valido semplice, DataTree con solo nodo radice, zxid = 1L

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.deleteNode("/a", 1L)
        );

        assertNodeExists("/");
        assertNodeDoesNotExist("/a");
    }

    @Test
    public void deleteNodeShouldThrowNoNodeExceptionForMissingNode() throws Exception {
        // T3 - path valido semplice, nodo da cancellare assente, zxid = 1L

        createValidNode("/a");

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.deleteNode("/x", 1L)
        );

        assertNodeExists("/");
        assertNodeExists("/a");
        assertNodeDoesNotExist("/x");
    }

    @Test
    public void deleteNodeShouldRemoveExistingMultilevelLeafNode() throws Exception {
        // T4 - path valido multilivello, nodo da cancellare presente come foglia, zxid = 1L

        createValidNode("/a");
        createValidNode("/a/b");

        dataTree.deleteNode("/a/b", 1L);

        assertNodeExists("/");
        assertNodeExists("/a");
        assertNodeDoesNotExist("/a/b");
    }

    @Test
    public void deleteNodeOnIntermediateNodeWithChildrenShouldBeHandledCorrectly() throws Exception {
        // T5 - path valido semplice, nodo da cancellare presente come nodo intermedio con figli

        createValidNode("/a");
        createValidNode("/a/b");

        try {
            dataTree.deleteNode("/a", 1L);

            // Se l'implementazione consente la cancellazione diretta del nodo intermedio,
            // almeno il nodo indicato deve risultare rimosso.
            assertNodeDoesNotExist("/a");
            assertNodeExists("/");
        } catch (Exception e) {
            // Se invece la cancellazione non è consentita, lo stato deve rimanere integro.
            assertNodeExists("/");
            assertNodeExists("/a");
            assertNodeExists("/a/b");
        }
    }

    @Test
    public void deleteNodeShouldRemoveNodeFromIndependentBranchOnly() throws Exception {
        // T6 - nodo da cancellare appartenente a un ramo indipendente

        createValidNode("/a");
        createValidNode("/x");

        dataTree.deleteNode("/x", 1L);

        assertNodeExists("/");
        assertNodeExists("/a");
        assertNodeDoesNotExist("/x");
    }

    @Test
    public void deleteNodeOnRootInInitialTreeShouldNotCorruptTree() {
        // T7 - path radice, DataTree nello stato iniziale

        assertThrows(
                Exception.class,
                () -> dataTree.deleteNode("/", 1L)
        );

        assertNodeExists("/");
    }

    @Test
    public void deleteNodeOnRootWithApplicationNodesShouldNotCorruptTree() throws Exception {
        // T8 - path radice, DataTree con nodi applicativi

        createValidNode("/a");

        assertThrows(
                Exception.class,
                () -> dataTree.deleteNode("/", 1L)
        );

        assertNodeExists("/");
        assertNodeExists("/a");
    }

    @Test
    public void deleteNodeWithNullPathShouldNotCorruptTree() {
        // T9 - path nullo

        assertThrows(
                Exception.class,
                () -> dataTree.deleteNode(null, 1L)
        );

        assertNodeExists("/");
    }

    @Test
    public void deleteNodeWithEmptyPathShouldNotCorruptTree() {
        // T10 - path vuoto o malformato: ""

        assertThrows(
                Exception.class,
                () -> dataTree.deleteNode("", 1L)
        );

        assertNodeExists("/");
    }

    @Test
    public void deleteNodeWithPathWithoutInitialSlashShouldNotCorruptTree() {
        // T11 - path vuoto o malformato: "a/b"

        assertThrows(
                Exception.class,
                () -> dataTree.deleteNode("a/b", 1L)
        );

        assertNodeExists("/");
    }

    @Test
    public void deleteNodeWithDoubleSlashPathShouldNotCorruptTree() throws Exception {
        // T12 - path vuoto o malformato: "/a//b", con altri nodi presenti

        createValidNode("/a");

        assertThrows(
                Exception.class,
                () -> dataTree.deleteNode("/a//b", 1L)
        );

        assertNodeExists("/");
        assertNodeExists("/a");
    }

    @Test
    public void deleteNodeShouldRemoveExistingLeafNodeWithZeroZxid() throws Exception {
        // T13 - path valido semplice, nodo foglia presente, zxid = 0L

        createValidNode("/a");

        dataTree.deleteNode("/a", 0L);

        assertNodeDoesNotExist("/a");
        assertNodeExists("/");
    }

    @Test
    public void deleteNodeShouldRemoveExistingLeafNodeWithNegativeZxid() throws Exception {
        // T14 - path valido semplice, nodo foglia presente, zxid = -1L

        createValidNode("/a");

        dataTree.deleteNode("/a", -1L);

        assertNodeDoesNotExist("/a");
        assertNodeExists("/");
    }

    @Test
    public void deleteNodeShouldThrowNoNodeExceptionAfterPreviousDeletion() throws Exception {
        // T15 - DataTree dopo una precedente cancellazione

        createValidNode("/a");

        dataTree.deleteNode("/a", 1L);

        assertNodeDoesNotExist("/a");

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.deleteNode("/a", 2L)
        );

        assertNodeExists("/");
        assertNodeDoesNotExist("/a");
    }

    @Test
    public void deleteNodeShouldRemoveLeafFromIndependentMultilevelBranchOnly() throws Exception {
        // T16 - DataTree con più nodi e rami indipendenti

        createValidNode("/a");
        createValidNode("/x");
        createValidNode("/x/y");

        dataTree.deleteNode("/x/y", 1L);

        assertNodeExists("/");
        assertNodeExists("/a");
        assertNodeExists("/x");
        assertNodeDoesNotExist("/x/y");
    }

    @Test
    public void deleteNodeShouldRemoveOnlyDeepLeafNode() throws Exception {
        // T17 - path valido multilivello, nodo da cancellare presente come foglia

        createValidNode("/a");
        createValidNode("/a/b");
        createValidNode("/a/b/c");

        dataTree.deleteNode("/a/b/c", 1L);

        assertNodeExists("/");
        assertNodeExists("/a");
        assertNodeExists("/a/b");
        assertNodeDoesNotExist("/a/b/c");
    }

    @Test
    public void deleteNodeOnMultilevelIntermediateNodeWithChildrenShouldBeHandledCorrectly() throws Exception {
        // T18 - nodo da cancellare presente come nodo intermedio con figli

        createValidNode("/a");
        createValidNode("/a/b");
        createValidNode("/a/b/c");

        try {
            dataTree.deleteNode("/a/b", 1L);

            // Se l'implementazione consente la cancellazione diretta del nodo intermedio,
            // almeno il nodo indicato deve risultare rimosso.
            assertNodeExists("/");
            assertNodeExists("/a");
            assertNodeDoesNotExist("/a/b");
        } catch (Exception e) {
            // Se invece la cancellazione non è consentita, lo stato deve rimanere integro.
            assertNodeExists("/");
            assertNodeExists("/a");
            assertNodeExists("/a/b");
            assertNodeExists("/a/b/c");
        }
    }
}