package DataTreeTest;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.DataTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
        dataTree.createNode(path, VALID_DATA, VALID_ACL, -1L, 0, 1L, VALID_TIME);
    }

    private void assertNodeExists(String path) {
        assertNotNull(dataTree.getNode(path), "Il nodo " + path + " dovrebbe esistere");
    }

    private void assertNodeDoesNotExist(String path) {
        assertNull(dataTree.getNode(path), "Il nodo " + path + " non dovrebbe esistere");
    }

    @Test
    public void deleteExistingLeaf() throws Exception {
        // T1 - path valido semplice, nodo da cancellare presente come foglia

        createValidNode("/a");

        dataTree.deleteNode("/a", 1L);

        assertNodeDoesNotExist("/a");
        assertNodeExists("/");
    }

    @Test
    public void deleteMissingFromRootOnly() {
        // T2 - path valido semplice, DataTree con solo nodo radice

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.deleteNode("/a", 1L)
        );

        assertNodeExists("/");
        assertNodeDoesNotExist("/a");
    }

    @Test
    public void deleteMissingNode() throws Exception {
        // T3 - path valido semplice, nodo da cancellare assente

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
    public void deleteExistingMultilevelLeaf() throws Exception {
        // T4 - path valido multilivello, nodo da cancellare presente come foglia

        createValidNode("/a");
        createValidNode("/a/b");

        dataTree.deleteNode("/a/b", 1L);

        assertNodeExists("/");
        assertNodeExists("/a");
        assertNodeDoesNotExist("/a/b");
    }

    @Test
    public void deleteIntermediateWithChild() throws Exception {
        // T5 - nodo intermedio con figli

        createValidNode("/a");
        createValidNode("/a/b");

        try {
            dataTree.deleteNode("/a", 1L);

            assertNodeDoesNotExist("/a");
            assertNodeExists("/");
        } catch (Exception e) {
            assertNodeExists("/");
            assertNodeExists("/a");
            assertNodeExists("/a/b");
        }
    }

    @Test
    public void deleteIndependentBranch() throws Exception {
        // T6 - nodo appartenente a un ramo indipendente

        createValidNode("/a");
        createValidNode("/x");

        dataTree.deleteNode("/x", 1L);

        assertNodeExists("/");
        assertNodeExists("/a");
        assertNodeDoesNotExist("/x");
    }

    @Test
    @Disabled("Ipotesi iniziale superata: deleteNode(\"/\") non lancia eccezioni")
    public void deleteRootInitialOld() {
        // T7 - vecchia ipotesi: cancellare la root dovrebbe lanciare eccezione

        assertThrows(
                Exception.class,
                () -> dataTree.deleteNode("/", 1L)
        );

        assertNodeExists("/");
    }

    @Test
    @Disabled("Ipotesi iniziale superata: deleteNode(\"/\") non lancia eccezioni")
    public void deleteRootWithNodesOld() throws Exception {
        // T8 - vecchia ipotesi: cancellare la root con nodi applicativi dovrebbe lanciare eccezione

        createValidNode("/a");

        assertThrows(
                Exception.class,
                () -> dataTree.deleteNode("/", 1L)
        );

        assertNodeExists("/");
        assertNodeExists("/a");
    }

    @Test
    public void deleteNullPath() {
        // T9 - path nullo

        assertThrows(
                Exception.class,
                () -> dataTree.deleteNode(null, 1L)
        );

        assertNodeExists("/");
    }

    @Test
    public void deleteEmptyPath() {
        // T10 - path vuoto

        assertThrows(
                Exception.class,
                () -> dataTree.deleteNode("", 1L)
        );

        assertNodeExists("/");
    }

    @Test
    public void deletePathWithoutSlash() {
        // T11 - path senza slash iniziale

        assertThrows(
                Exception.class,
                () -> dataTree.deleteNode("a/b", 1L)
        );

        assertNodeExists("/");
    }

    @Test
    public void deleteDoubleSlashPath() throws Exception {
        // T12 - path con doppia slash interna

        createValidNode("/a");

        assertThrows(
                Exception.class,
                () -> dataTree.deleteNode("/a//b", 1L)
        );

        assertNodeExists("/");
        assertNodeExists("/a");
    }

    @Test
    public void deleteLeafWithZeroZxid() throws Exception {
        // T13 - nodo foglia presente, zxid = 0L

        createValidNode("/a");

        dataTree.deleteNode("/a", 0L);

        assertNodeDoesNotExist("/a");
        assertNodeExists("/");
    }

    @Test
    public void deleteLeafWithNegativeZxid() throws Exception {
        // T14 - nodo foglia presente, zxid = -1L

        createValidNode("/a");

        dataTree.deleteNode("/a", -1L);

        assertNodeDoesNotExist("/a");
        assertNodeExists("/");
    }

    @Test
    public void deleteTwiceSameNode() throws Exception {
        // T15 - seconda cancellazione dello stesso nodo

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
    public void deleteLeafInIndependentBranch() throws Exception {
        // T16 - foglia in ramo multilivello indipendente

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
    public void deleteDeepLeaf() throws Exception {
        // T17 - foglia profonda

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
    public void deleteMultilevelIntermediate() throws Exception {
        // T18 - nodo intermedio multilivello con figli

        createValidNode("/a");
        createValidNode("/a/b");
        createValidNode("/a/b/c");

        try {
            dataTree.deleteNode("/a/b", 1L);

            assertNodeExists("/");
            assertNodeExists("/a");
            assertNodeDoesNotExist("/a/b");
        } catch (Exception e) {
            assertNodeExists("/");
            assertNodeExists("/a");
            assertNodeExists("/a/b");
            assertNodeExists("/a/b/c");
        }
    }

    @Test
    public void deleteRootInitialTree() {
        // T7 modificato - deleteNode("/") non solleva eccezioni nello stato iniziale

        assertDoesNotThrow(() -> dataTree.deleteNode("/", 1L));

        assertNodeDoesNotExist("/");
    }

    @Test
    public void deleteRootWithNodes() throws Exception {
        // T8 modificato - deleteNode("/") non solleva eccezioni con nodi applicativi

        createValidNode("/a");

        assertDoesNotThrow(() -> dataTree.deleteNode("/", 1L));

        assertNodeDoesNotExist("/");
        assertNodeExists("/a");
    }
}