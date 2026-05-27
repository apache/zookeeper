package PathTrieTest.TestLLM.C2;



import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PathTrieOneShotCodeTest {

    private PathTrie pathTrie;

    @BeforeEach
    public void setUp() {
        pathTrie = new PathTrie();
    }

    @Test
    @DisplayName("Test adding a path and checking its existence")
    public void testAddAndExistsNodeSuccess() {
        String testPath = "/zookeeper/quota/serviceA";
        assertFalse(pathTrie.existsNode(testPath), "Path should not exist before being added");

        pathTrie.addPath(testPath);
        assertTrue(pathTrie.existsNode(testPath), "Path should exist after being successfully added");

        // Validate that all intermediate path components exist as well
        assertTrue(pathTrie.existsNode("/zookeeper"), "Intermediate root component should exist");
        assertTrue(pathTrie.existsNode("/zookeeper/quota"), "Intermediate sub-branch component should exist");
    }

    @Test
    @DisplayName("Test successful deletion of a path and leaf node pruning")
    public void testDeletePathSuccess() {
        String targetPath = "/proc/status/nodeToPrune";
        pathTrie.addPath(targetPath);
        assertTrue(pathTrie.existsNode(targetPath));

        pathTrie.deletePath(targetPath);
        assertFalse(pathTrie.existsNode(targetPath), "Leaf node should be completely pruned and non-existent");

        // Parent components should still structurally exist if needed
        assertTrue(pathTrie.existsNode("/proc/status"), "Parent paths should remain intact if they contain structure");
    }

    @Test
    @DisplayName("Test that deleting a non-existent path behaves gracefully as a no-op")
    public void testDeleteNonExistentPathNoOp() {
        assertDoesNotThrow(() -> pathTrie.deletePath("/missing/unmapped/namespace"),
                "Deleting a path that does not exist should handle safely without throwing exceptions");
    }

    @Test
    @DisplayName("Test maximum common prefix isolation scenarios")
    public void testFindMaxPrefixScenarios() {
        pathTrie.addPath("/zookeeper/quota");
        pathTrie.addPath("/zookeeper/quota/app1");

        // Scenario 1: Exact path configuration match
        assertEquals("/zookeeper/quota/app1", pathTrie.findMaxPrefix("/zookeeper/quota/app1"),
                "Exact structural match resolution failed");

        // Scenario 2: Trailing lookups under a valid configuration property branch path
        assertEquals("/zookeeper/quota/app1", pathTrie.findMaxPrefix("/zookeeper/quota/app1/subFolder/logs/file.log"),
                "Evaluating paths nested below an active property branch failed");

        // Scenario 3: Fallback match to the closest matching ancestor configuration path
        assertEquals("/zookeeper/quota", pathTrie.findMaxPrefix("/zookeeper/quota/app2"),
                "Resolution engine failed to fall back to the closest configured ancestor");

        // Scenario 4: Extreme prefix match failure falling back to system root namespace token
        assertEquals("/", pathTrie.findMaxPrefix("/unrelated/tenant/space"),
                "Complete lookup misses must cleanly fall back to absolute root symbol");
    }

    @Test
    @DisplayName("Test clearing the prefix tree completely unmaps registered nodes")
    public void testClearPurgesTrie() {
        pathTrie.addPath("/quota/limitA");
        pathTrie.addPath("/quota/limitB");
        assertTrue(pathTrie.existsNode("/quota/limitA"));
        assertTrue(pathTrie.existsNode("/quota/limitB"));

        pathTrie.clear();

        assertFalse(pathTrie.existsNode("/quota/limitA"), "Trie nodes must be completely unmapped after clear");
        assertFalse(pathTrie.existsNode("/quota/limitB"), "Trie nodes must be completely unmapped after clear");
    }

    @Test
    @DisplayName("Test all interaction endpoints enforce NullPointerException guardrails")
    public void testNullPathValidations() {
        NullPointerException addEx = assertThrows(NullPointerException.class, () -> pathTrie.addPath(null));
        assertEquals("Path cannot be null", addEx.getMessage());

        NullPointerException delEx = assertThrows(NullPointerException.class, () -> pathTrie.deletePath(null));
        assertEquals("Path cannot be null", delEx.getMessage());

        NullPointerException existsEx = assertThrows(NullPointerException.class, () -> pathTrie.existsNode(null));
        assertEquals("Path cannot be null", existsEx.getMessage());

        NullPointerException prefixEx = assertThrows(NullPointerException.class, () -> pathTrie.findMaxPrefix(null));
        assertEquals("Path cannot be null", prefixEx.getMessage());
    }

    @Test
    @DisplayName("Test that interaction entries reject empty value string parameters")
    public void testEmptyPathValidations() {
        IllegalArgumentException addEx = assertThrows(IllegalArgumentException.class, () -> pathTrie.addPath(""));
        assertTrue(addEx.getMessage().contains("Invalid path"), "Expected specific invalid prefix string message content");

        IllegalArgumentException delEx = assertThrows(IllegalArgumentException.class, () -> pathTrie.deletePath(""));
        assertTrue(delEx.getMessage().contains("Invalid path"), "Expected specific invalid prefix string message content");

        IllegalArgumentException existsEx = assertThrows(IllegalArgumentException.class, () -> pathTrie.existsNode(""));
        assertTrue(existsEx.getMessage().contains("Invalid path"), "Expected specific invalid prefix string message content");
    }
}