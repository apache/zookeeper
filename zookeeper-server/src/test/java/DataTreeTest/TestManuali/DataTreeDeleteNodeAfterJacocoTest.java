package DataTreeTest.TestManuali;



import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.DataTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeDeleteNodeAfterJacocoTest {

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

    @Test
    public void deleteOneChildWhenParentHasMultipleChildren() throws Exception {
        // T19 - parent con più figli: viene cancellato solo il figlio target

        createValidNode("/parent");
        createValidNode("/parent/child1");
        createValidNode("/parent/child2");

        dataTree.deleteNode("/parent/child1", 2L);

        assertNodeExists("/");
        assertNodeExists("/parent");
        assertNodeDoesNotExist("/parent/child1");
        assertNodeExists("/parent/child2");
    }

    @Test
    public void deleteTwoLeavesThenDeleteParent() throws Exception {
        // T20 - cancellazione progressiva dei figli e poi del parent

        createValidNode("/a");
        createValidNode("/a/b");
        createValidNode("/a/c");

        dataTree.deleteNode("/a/b", 2L);
        dataTree.deleteNode("/a/c", 3L);
        dataTree.deleteNode("/a", 4L);

        assertNodeExists("/");
        assertNodeDoesNotExist("/a/b");
        assertNodeDoesNotExist("/a/c");
        assertNodeDoesNotExist("/a");
    }

    @Test
    public void deleteMissingDeepNodeWithExistingParent() throws Exception {
        // T21 - nodo multilivello assente con parent presente

        createValidNode("/a");

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.deleteNode("/a/b", 2L)
        );

        assertNodeExists("/");
        assertNodeExists("/a");
        assertNodeDoesNotExist("/a/b");
    }

    @Test
    public void deleteExistingLeafWithLargeZxid() throws Exception {
        // T22 - cancellazione con zxid positivo elevato

        createValidNode("/a");

        dataTree.deleteNode("/a", Long.MAX_VALUE);

        assertNodeExists("/");
        assertNodeDoesNotExist("/a");
    }

    @Test
    public void deleteChildAfterDeletingSibling() throws Exception {
        // T23 - cancellazione sequenziale di due figli dello stesso parent

        createValidNode("/parent");
        createValidNode("/parent/child1");
        createValidNode("/parent/child2");

        dataTree.deleteNode("/parent/child1", 2L);
        dataTree.deleteNode("/parent/child2", 3L);

        assertNodeExists("/");
        assertNodeExists("/parent");
        assertNodeDoesNotExist("/parent/child1");
        assertNodeDoesNotExist("/parent/child2");
    }

    @Test
    public void deletePathWithTrailingSlashShouldNotCorruptTree() throws Exception {
        // T24 - path con slash finale

        createValidNode("/a");

        assertThrows(
                Exception.class,
                () -> dataTree.deleteNode("/a/", 2L)
        );

        assertNodeExists("/");
        assertNodeExists("/a");
    }

    @Test
    public void deleteDeepMissingNodeShouldNotCorruptExistingBranch() throws Exception {
        // T25 - path profondo assente in ramo parzialmente esistente

        createValidNode("/a");
        createValidNode("/a/b");

        assertThrows(
                KeeperException.NoNodeException.class,
                () -> dataTree.deleteNode("/a/b/c", 2L)
        );

        assertNodeExists("/");
        assertNodeExists("/a");
        assertNodeExists("/a/b");
        assertNodeDoesNotExist("/a/b/c");
    }
}