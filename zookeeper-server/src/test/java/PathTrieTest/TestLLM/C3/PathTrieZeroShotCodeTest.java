package PathTrieTest.TestLLM.C3;


import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PathTrieZeroShotCodeTest {

    private PathTrie pathTrie;

    @BeforeEach
    public void setUp() {
        pathTrie = new PathTrie();
    }

    @Test
    @DisplayName("Test adding a path and checking its existence")
    public void testAddAndExistsNodeSuccess() {
        String testPath = "/zookeeper/quota/serviceA";
        assertFalse(pathTrie.existsNode(testPath), "Path should not exist prior to addition");

        pathTrie.addPath(testPath);
        assertTrue(pathTrie.existsNode(testPath), "Path must be recognized as existing post-addition");

        // Validate that intermediate structural components exist as well
        assertTrue(pathTrie.existsNode("/zookeeper"), "Intermediate root parent token missing");
        assertTrue(pathTrie.existsNode("/zookeeper/quota"), "Intermediate sub-branch directory missing");
    }

    @Test
    @DisplayName("Test successful deletion of a mapped path and leaf pruning")
    public void testDeletePathSuccess() {
        String targetPath = "/proc/status/nodeToPrune";
        pathTrie.addPath(targetPath);
        assertTrue(pathTrie.existsNode(targetPath));

        pathTrie.deletePath(targetPath);
        assertFalse(pathTrie.existsNode(targetPath), "Target leaf node should be successfully pruned and unmapped");
    }

    @Test
    @DisplayName("Test that deleting a non-existent path gracefully functions as a no-op")
    public void testDeleteNonExistentPathNoOp() {
        assertDoesNotThrow(() -> pathTrie.deletePath("/missing/unmapped/namespace"),
                "Erasing an unmapped path should terminate cleanly without raising structural errors");
    }

    @Test
    @DisplayName("Test maximum prefix match resolution engine paths")
    public void testFindMaxPrefixScenarios() {
        pathTrie.addPath("/zookeeper/quota");
        pathTrie.addPath("/zookeeper/quota/app1");

        // Scenario 1: Exact matching parameter path configuration
        assertEquals("/zookeeper/quota/app1", pathTrie.findMaxPrefix("/zookeeper/quota/app1"),
                "Exact structural lookup match failed");

        // Scenario 2: Deep tracking underneath an existing property node branch layout
        assertEquals("/zookeeper/quota/app1", pathTrie.findMaxPrefix("/zookeeper/quota/app1/subFolder/logs/file.log"),
                "Tracking evaluation underneath an active property node branch failed");

        // Scenario 3: Fallback match matching the closest registered ancestor node
        assertEquals("/zookeeper/quota", pathTrie.findMaxPrefix("/zookeeper/quota/app2"),
                "Resolution engine failed to match closest configured ancestor boundary");

        // Scenario 4: Absolute miss falling back to system namespace root symbol token
        assertEquals("/", pathTrie.findMaxPrefix("/unrelated/tenant/space"),
                "Complete lookup tree misses must safely revert back to absolute root namespace");
    }

    @Test
    @DisplayName("Test clearing the prefix tree completely unmaps registered nodes")
    public void testClearPurgesTrie() {
        pathTrie.addPath("/quota/limitA");
        pathTrie.addPath("/quota/limitB");
        assertTrue(pathTrie.existsNode("/quota/limitA"));
        assertTrue(pathTrie.existsNode("/quota/limitB"));

        pathTrie.clear();

        assertFalse(pathTrie.existsNode("/quota/limitA"), "Trie nodes must be completely unmapped post-clear");
        assertFalse(pathTrie.existsNode("/quota/limitB"), "Trie nodes must be completely unmapped post-clear");
    }

    @Test
    @DisplayName("Test all public interaction entries enforce NullPointerException constraints")
    public void testNullPathValidations() {
        NullPointerException addEx = assertThrows(NullPointerException.class, () -> pathTrie.addPath(null));
        assertEquals("Path cannot be null", addEx.getMessage(), "Mismatched null diagnostic text");

        NullPointerException delEx = assertThrows(NullPointerException.class, () -> pathTrie.deletePath(null));
        assertEquals("Path cannot be null", delEx.getMessage(), "Mismatched null diagnostic text");

        NullPointerException existsEx = assertThrows(NullPointerException.class, () -> pathTrie.existsNode(null));
        assertEquals("Path cannot be null", existsEx.getMessage(), "Mismatched null diagnostic text");

        NullPointerException prefixEx = assertThrows(NullPointerException.class, () -> pathTrie.findMaxPrefix(null));
        assertEquals("Path cannot be null", prefixEx.getMessage(), "Mismatched null diagnostic text");
    }

    @Test
    @DisplayName("Test that interaction entries reject empty value string parameters")
    public void testEmptyPathValidations() {
        IllegalArgumentException addEx = assertThrows(IllegalArgumentException.class, () -> pathTrie.addPath(""));
        assertTrue(addEx.getMessage().contains("Invalid path"), "Expected specific invalid prefix string message format");

        IllegalArgumentException delEx = assertThrows(IllegalArgumentException.class, () -> pathTrie.deletePath(""));
        assertTrue(delEx.getMessage().contains("Invalid path"), "Expected specific invalid prefix string message format");

        IllegalArgumentException existsEx = assertThrows(IllegalArgumentException.class, () -> pathTrie.existsNode(""));
        assertTrue(existsEx.getMessage().contains("Invalid path"), "Expected specific invalid prefix string message format");
    }
}