package PathTrieTest.TestLLM.C2;


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
    @DisplayName("Path Registration & Structural Lifecycles")
    class LifecycleTests {

        @Test
        @DisplayName("Verify flawless tree layout initialization and component lookup matching")
        public void testAddAndExistsNodeSuccess() {
            String targetPath = "/zookeeper/quota/appNode";
            assertFalse(pathTrie.existsNode(targetPath), "Path shouldn't exist before addition initialization");

            pathTrie.addPath(targetPath);
            assertTrue(pathTrie.existsNode(targetPath), "Path must verify as valid post-addition processing");

            // Validate structural path element partitioning
            assertTrue(pathTrie.existsNode("/zookeeper"), "Primary root directory branch missing");
            assertTrue(pathTrie.existsNode("/zookeeper/quota"), "Intermediate branch subsystem missing");
        }

        @Test
        @DisplayName("Verify precise child leaf pruning and context properties erasure transitions")
        public void testDeletePathSuccess() {
            String testPath = "/proc/status/limitNode";
            pathTrie.addPath(testPath);
            assertTrue(pathTrie.existsNode(testPath));

            pathTrie.deletePath(testPath);
            assertFalse(pathTrie.existsNode(testPath), "Target leaf node components failed to clean up safely");
        }

        @Test
        @DisplayName("Ensure deleting an unmapped component returns safely without raising structural errors")
        public void testDeleteNonExistentPathGracefulNoOp() {
            assertDoesNotThrow(() -> pathTrie.deletePath("/absent/system/namespace"),
                    "Erasing an invalid unmapped component path layout must execute as a clean no-op");
        }
    }

    @Nested
    @DisplayName("Prefix Evaluation Resolution Checks")
    class MatchingTests {

        @Test
        @DisplayName("Verify maximum common prefix isolation metrics against overlapping node bounds")
        public void testFindMaxPrefixScenarios() {
            pathTrie.addPath("/zookeeper/quota");
            pathTrie.addPath("/zookeeper/quota/serviceA");

            // Exact direct match rule assertion
            assertEquals("/zookeeper/quota/serviceA", pathTrie.findMaxPrefix("/zookeeper/quota/serviceA"),
                    "Exact parameter matching tracking index failure");

            // Deep structural trailing folder lookup underneath matched branch boundary
            assertEquals("/zookeeper/quota/serviceA", pathTrie.findMaxPrefix("/zookeeper/quota/serviceA/metrics/logs/file.log"),
                    "Deep tracking evaluation underneath matched node configuration property bounds failed");

            // Fallback match assertion mapping closest ancestor node metadata property
            assertEquals("/zookeeper/quota", pathTrie.findMaxPrefix("/zookeeper/quota/serviceB"),
                    "Fallback verification routine failed to track adjacent common ancestor branch configuration");

            // Absolute miss fallback constraint target verification
            assertEquals("/", pathTrie.findMaxPrefix("/unrelated/tenant/environment"),
                    "Total lookup tree structure misses must safely roll back to absolute system namespace root token");
        }
    }

    @Nested
    @DisplayName("State Clear & Memory Erasure Safety")
    class StructuralPurgeTests {

        @Test
        @DisplayName("Verify standard runtime purge entirely unmaps active configuration paths")
        public void testClearPurgesTrie() {
            pathTrie.addPath("/tenant/alfa");
            pathTrie.addPath("/tenant/beta");
            assertTrue(pathTrie.existsNode("/tenant/alfa"));
            assertTrue(pathTrie.existsNode("/tenant/beta"));

            pathTrie.clear();

            assertFalse(pathTrie.existsNode("/tenant/alfa"), "Trie maps failed structural pruning cleanup sequences");
            assertFalse(pathTrie.existsNode("/tenant/beta"), "Trie maps failed structural pruning cleanup sequences");
        }
    }

    @Nested
    @DisplayName("Exception Guardrails & Argument Constraints")
    class ValidationTests {

        @Test
        @DisplayName("Verify all interaction methods enforce NullPointerException with correct diagnostics tracking")
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
        @DisplayName("Ensure all interaction methods seamlessly reject empty value string parameters")
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
