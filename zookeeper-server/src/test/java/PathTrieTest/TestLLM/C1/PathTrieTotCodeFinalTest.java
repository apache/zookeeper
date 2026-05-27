package PathTrieTest.TestLLM.C1;



import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PathTrieTotCodeFinalTest {

    private PathTrie pathTrie;

    @BeforeEach
    public void setUp() {
        pathTrie = new PathTrie();
    }

    @Nested
    @DisplayName("Path Registration and Existence Verification")
    class PathLifecycleTests {

        @Test
        @DisplayName("Verify successful storage and retrieval of deep path structures")
        public void testAddAndExistsNodeSuccess() {
            String targetPath = "/node/subNode/leaf";
            assertFalse(pathTrie.existsNode(targetPath), "Path should not exist prior to addition");

            pathTrie.addPath(targetPath);
            assertTrue(pathTrie.existsNode(targetPath), "Path should exist post-addition");

            // Check that intermediate nodes exist structurally
            assertTrue(pathTrie.existsNode("/node"), "Parent node component must exist");
            assertTrue(pathTrie.existsNode("/node/subNode"), "Sub-parent node component must exist");
        }

        @Test
        @DisplayName("Verify path pruning and child-erasure logic operations")
        public void testDeletePathSuccess() {
            String branchPath = "/quota/zookeeper/limit";
            pathTrie.addPath(branchPath);
            assertTrue(pathTrie.existsNode(branchPath));

            pathTrie.deletePath(branchPath);
            // Because deleteChild resets property to false and prunes empty leaves
            assertFalse(pathTrie.existsNode(branchPath), "Target leaf should be deleted");
        }

        @Test
        @DisplayName("Verify that deleting an unmapped path behaves safely as a no-op")
        public void testDeleteNonExistentPathNoOp() {
            assertDoesNotThrow(() -> pathTrie.deletePath("/missing/path/structure"),
                    "Deleting a non-existent path should return gracefully without errors");
        }
    }

    @Nested
    @DisplayName("Prefix Resolution Engine Evaluation")
    class PrefixMatchingTests {

        @Test
        @DisplayName("Verify precise maximum prefix isolation matching across node properties")
        public void testFindMaxPrefixScenarios() {
            pathTrie.addPath("/zookeeper/quota");
            pathTrie.addPath("/zookeeper/quota/app1");

            // Direct match exact property node
            assertEquals("/zookeeper/quota/app1", pathTrie.findMaxPrefix("/zookeeper/quota/app1"),
                    "Exact match tracking failure");

            // Match structural deep child trailing under verified property node branch
            assertEquals("/zookeeper/quota/app1", pathTrie.findMaxPrefix("/zookeeper/quota/app1/subFolder/file.txt"),
                    "Deep tracking underneath matched property node branch failed");

            // Fallback match to intermediate node
            assertEquals("/zookeeper/quota", pathTrie.findMaxPrefix("/zookeeper/quota/app2"),
                    "Fallback matching logic failed to match closest configured ancestor");

            // Absolute miss fallback rule
            assertEquals("/", pathTrie.findMaxPrefix("/unrelated/user/space"),
                    "Total prefix misses must seamlessly fall back to root namespace symbol");
        }
    }

    @Nested
    @DisplayName("State Cleanup and Erasure Logic")
    class StateCleanupTests {

        @Test
        @DisplayName("Verify full system structural purge clears down registered nodes")
        public void testClearPurgesTrie() {
            pathTrie.addPath("/quota/a");
            pathTrie.addPath("/quota/b");
            assertTrue(pathTrie.existsNode("/quota/a"));
            assertTrue(pathTrie.existsNode("/quota/b"));

            pathTrie.clear();

            assertFalse(pathTrie.existsNode("/quota/a"), "Trie nodes must be completely unmapped post-clear");
            assertFalse(pathTrie.existsNode("/quota/b"), "Trie nodes must be completely unmapped post-clear");
        }
    }

    @Nested
    @DisplayName("Exception Boundaries and Argument Validations")
    class ExceptionBoundaryTests {

        @Test
        @DisplayName("Ensure all input points cleanly enforce NullPointerException with designated diagnostics")
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
        @DisplayName("Ensure operational modification contexts reject empty path configurations")
        public void testEmptyPathValidations() {
            IllegalArgumentException addEx = assertThrows(IllegalArgumentException.class, () -> pathTrie.addPath(""));
            assertTrue(addEx.getMessage().contains("Invalid path"));

            IllegalArgumentException delEx = assertThrows(IllegalArgumentException.class, () -> pathTrie.deletePath(""));
            assertTrue(delEx.getMessage().contains("Invalid path"));

            IllegalArgumentException existsEx = assertThrows(IllegalArgumentException.class, () -> pathTrie.existsNode(""));
            assertTrue(existsEx.getMessage().contains("Invalid path"));
        }
    }
}