package PathTrieTest.TestLLM;



import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zero-shot comprehensive test suite for Apache ZooKeeper's PathTrie class.
 * Tests node addition, deletion, prefix matching, and tree clearing.
 */
public class PathTrieOneShotTest {

    private PathTrie pathTrie;

    @BeforeEach
    public void setUp() {
        // Initialize a fresh PathTrie before each test
        pathTrie = new PathTrie();
    }

    @Test
    public void testFindMaxPrefix_EmptyTrie_ReturnsRoot() {
        // In an empty trie, the maximum prefix for any path should default to the root "/"
        assertEquals("/", pathTrie.findMaxPrefix("/zookeeper/quota"));
        assertEquals("/", pathTrie.findMaxPrefix("/"));
    }

    @Test
    public void testAddPath_And_FindMaxPrefix_ExactMatch() {
        pathTrie.addPath("/nodeA/nodeB");

        // Exact match should return the full path
        assertEquals("/nodeA/nodeB", pathTrie.findMaxPrefix("/nodeA/nodeB"));
    }

    @Test
    public void testFindMaxPrefix_WithDeeperPath_ReturnsPrefix() {
        pathTrie.addPath("/nodeA/nodeB");

        // Querying a child of an existing path should return the stored parent prefix
        assertEquals("/nodeA/nodeB", pathTrie.findMaxPrefix("/nodeA/nodeB/nodeC/nodeD"));
    }

    @Test
    public void testFindMaxPrefix_WithShallowerPath_ReturnsRoot() {
        pathTrie.addPath("/nodeA/nodeB");

        // Querying a parent path that wasn't explicitly added should return root
        // (because "/nodeA" is just an intermediate trie node, not a stored path endpoint)
        assertEquals("/", pathTrie.findMaxPrefix("/nodeA"));
    }

    @Test
    public void testFindMaxPrefix_MultipleOverlappingPaths() {
        pathTrie.addPath("/app");
        pathTrie.addPath("/app/config");
        pathTrie.addPath("/app/config/db");

        assertEquals("/app", pathTrie.findMaxPrefix("/app/logs"));
        assertEquals("/app/config", pathTrie.findMaxPrefix("/app/config/cache"));
        assertEquals("/app/config/db", pathTrie.findMaxPrefix("/app/config/db/connections"));
    }

    @Test
    public void testDeletePath_ExistingPath() {
        pathTrie.addPath("/test/path");
        assertEquals("/test/path", pathTrie.findMaxPrefix("/test/path/child"));

        // Delete the path
        pathTrie.deletePath("/test/path");

        // After deletion, it should fall back to the root
        assertEquals("/", pathTrie.findMaxPrefix("/test/path/child"));
        assertEquals("/", pathTrie.findMaxPrefix("/test/path"));
    }

    @Test
    public void testDeletePath_NestedPaths_DoesNotAffectSiblingsOrParents() {
        pathTrie.addPath("/parent");
        pathTrie.addPath("/parent/child1");
        pathTrie.addPath("/parent/child2");

        // Delete one child
        pathTrie.deletePath("/parent/child1");

        // child1 is gone, falls back to parent
        assertEquals("/parent", pathTrie.findMaxPrefix("/parent/child1/grandchild"));

        // child2 remains intact
        assertEquals("/parent/child2", pathTrie.findMaxPrefix("/parent/child2/grandchild"));

        // parent remains intact
        assertEquals("/parent", pathTrie.findMaxPrefix("/parent"));
    }

    @Test
    public void testDeletePath_NonExistentPath_DoesNotThrowException() {
        pathTrie.addPath("/existing");

        // Deleting a path that doesn't exist shouldn't crash the application
        assertDoesNotThrow(() -> pathTrie.deletePath("/non/existent/path"));

        // Existing paths should still work
        assertEquals("/existing", pathTrie.findMaxPrefix("/existing/child"));
    }

    @Test
    public void testClear_RemovesAllPaths() {
        pathTrie.addPath("/path1");
        pathTrie.addPath("/path2/child");
        pathTrie.addPath("/path3");

        assertEquals("/path2/child", pathTrie.findMaxPrefix("/path2/child"));

        // Clear the trie
        pathTrie.clear();

        // Everything should fall back to root
        assertEquals("/", pathTrie.findMaxPrefix("/path1"));
        assertEquals("/", pathTrie.findMaxPrefix("/path2/child"));
        assertEquals("/", pathTrie.findMaxPrefix("/path3"));
    }

    @Test

    public void testRootPathHandling() {
        // L'aggiunta della radice viene ignorata o gestita senza errori
        pathTrie.addPath("/");
        assertEquals("/", pathTrie.findMaxPrefix("/"));
        assertEquals("/", pathTrie.findMaxPrefix("/any/path"));

        // Poiché il nodo radice non ha un "parent", tentare di rimuoverlo
        // in ZooKeeper scatena nativamente una NullPointerException.
        // Registriamo e validiamo questo comportamento architetturale:
        assertThrows(NullPointerException.class, () -> pathTrie.deletePath("/"));

        // La radice continua a rispondere correttamente
        assertEquals("/", pathTrie.findMaxPrefix("/any/path"));
    }

    @Test
    public void testNullPathHandling() {
        // ZooKeeper usually expects valid paths. Depending on the exact implementation,
        // passing null might throw a NullPointerException. This tests robustness.
        assertThrows(NullPointerException.class, () -> pathTrie.addPath(null));
        assertThrows(NullPointerException.class, () -> pathTrie.deletePath(null));
        assertThrows(NullPointerException.class, () -> pathTrie.findMaxPrefix(null));
    }
}