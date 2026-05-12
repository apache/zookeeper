package PathTrieTest.TestLLM;


import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A comprehensive JUnit 5 test suite for the PathTrie class.
 * This suite contains exactly 40 independent test cases covering typical use cases,
 * boundary cases, edge cases, exception handling, and state-dependent behaviors.
 */
public class PathTrieZeroShotRun10Test {

    private PathTrie pathTrie;

    @BeforeEach
    public void setUp() {
        pathTrie = new PathTrie();
    }

    // --- Basic State & Clear Tests (1-5) ---

    @Test
    public void test1_FindMaxPrefix_EmptyTrie_ReturnsRoot() {
        assertEquals("/", pathTrie.findMaxPrefix("/a/b"),
                "An empty trie should return the root prefix '/' for any path.");
    }

    @Test
    public void test2_ExistsNode_EmptyTrie_ForRandomPath_ReturnsFalse() {
        assertFalse(pathTrie.existsNode("/a/b"),
                "A random path should not exist in an empty trie.");
    }

    @Test
    public void test3_Clear_EmptyTrie_DoesNothing() {
        assertDoesNotThrow(() -> pathTrie.clear(),
                "Clearing an empty trie should execute without exceptions.");
        assertEquals("/", pathTrie.findMaxPrefix("/a"), "Trie should remain empty.");
    }

    @Test
    public void test4_Clear_PopulatedTrie_RemovesAll() {
        pathTrie.addPath("/node1");
        pathTrie.addPath("/node2/child");
        pathTrie.clear();
        assertFalse(pathTrie.existsNode("/node1"), "All nodes should be removed after clear.");
        assertFalse(pathTrie.existsNode("/node2/child"), "All nodes should be removed after clear.");
        assertEquals("/", pathTrie.findMaxPrefix("/node1"));
    }

    @Test
    public void test5_Clear_AddAfterClear_Works() {
        pathTrie.addPath("/node1");
        pathTrie.clear();
        pathTrie.addPath("/node2");
        assertTrue(pathTrie.existsNode("/node2"), "Trie should accept new paths after being cleared.");
        assertEquals("/node2", pathTrie.findMaxPrefix("/node2"));
    }

    // --- addPath Tests (6-17) ---

    @Test
    public void test6_AddPath_SingleNode() {
        pathTrie.addPath("/app");
        assertEquals("/app", pathTrie.findMaxPrefix("/app"), "Single level node should be found.");
    }

    @Test
    public void test7_AddPath_MultiLevelNode() {
        pathTrie.addPath("/app/config/db");
        assertEquals("/app/config/db", pathTrie.findMaxPrefix("/app/config/db"),
                "Multi-level node should be fully traversable and resolvable.");
    }

    @Test
    public void test8_AddPath_OverlappingPaths() {
        pathTrie.addPath("/app");
        pathTrie.addPath("/app/config");
        assertTrue(pathTrie.existsNode("/app"));
        assertTrue(pathTrie.existsNode("/app/config"));
    }

    @Test
    public void test9_AddPath_Duplicate_NoEffect() {
        pathTrie.addPath("/app/config");
        pathTrie.addPath("/app/config");
        assertTrue(pathTrie.existsNode("/app/config"), "Adding duplicate paths should not break the trie.");
        assertEquals("/app/config", pathTrie.findMaxPrefix("/app/config"));
    }

    @Test
    public void test10_AddPath_TrailingSlash_Normalized() {
        pathTrie.addPath("/app/config/");
        assertTrue(pathTrie.existsNode("/app/config"), "Trailing slashes should be normalized.");
    }

    @Test
    public void test11_AddPath_ConsecutiveSlashes_Normalized() {
        pathTrie.addPath("//app///config//");
        assertTrue(pathTrie.existsNode("/app/config"), "Consecutive slashes should be ignored.");
    }

    @Test
    public void test12_AddPath_WithoutLeadingSlash_Parsed() {
        pathTrie.addPath("app/config");
        assertTrue(pathTrie.existsNode("/app/config"), "Paths without leading slash should parse correctly.");
    }

    @Test
    public void test13_AddPath_RootPath_HandledGracefully() {
        pathTrie.addPath("/");
        assertEquals("/", pathTrie.findMaxPrefix("/"), "Adding root path sets root property to true gracefully.");
    }

    @Test
    public void test14_AddPath_IntermediateNodesAreCreatedStructurally() {
        pathTrie.addPath("/app/config/db");
        assertTrue(pathTrie.existsNode("/app/config"), "Intermediate structural nodes should exist.");
        assertTrue(pathTrie.existsNode("/app"), "Intermediate structural nodes should exist.");
    }

    @Test
    public void test15_AddPath_IntermediateNodesDoNotHaveProperty() {
        pathTrie.addPath("/app/config/db");
        // Intermediate node exists structurally but does NOT have the property flag for prefix matching
        assertEquals("/", pathTrie.findMaxPrefix("/app/config"));
    }

    @Test
    public void test16_AddPath_Null_ThrowsNPE() {
        assertThrows(NullPointerException.class, () -> pathTrie.addPath(null),
                "Adding a null path should throw NullPointerException.");
    }

    @Test
    public void test17_AddPath_EmptyString_ThrowsIAE() {
        assertThrows(IllegalArgumentException.class, () -> pathTrie.addPath(""),
                "Adding an empty string should throw IllegalArgumentException.");
    }

    // --- existsNode Tests (18-23) ---

    @Test
    public void test18_ExistsNode_ExactPath_ReturnsTrue() {
        pathTrie.addPath("/test/path");
        assertTrue(pathTrie.existsNode("/test/path"), "Should return true for exact explicitly added paths.");
    }

    @Test
    public void test19_ExistsNode_IntermediatePath_ReturnsTrue() {
        pathTrie.addPath("/test/path/deep");
        assertTrue(pathTrie.existsNode("/test/path"),
                "Should return true for intermediate paths created structurally.");
    }

    @Test
    public void test20_ExistsNode_NonExistentChild_ReturnsFalse() {
        pathTrie.addPath("/test/path");
        assertFalse(pathTrie.existsNode("/test/path/child"),
                "Should return false for non-existent children of valid paths.");
    }

    @Test
    public void test21_ExistsNode_Null_ThrowsNPE() {
        assertThrows(NullPointerException.class, () -> pathTrie.existsNode(null),
                "Should throw NullPointerException when checking null path.");
    }

    @Test
    public void test22_ExistsNode_EmptyString_ThrowsIAE() {
        assertThrows(IllegalArgumentException.class, () -> pathTrie.existsNode(""),
                "Should throw IllegalArgumentException when checking empty string path.");
    }

    @Test
    public void test23_ExistsNode_Root_ReturnsTrue() {
        assertTrue(pathTrie.existsNode("/"), "Root node should always structurally exist.");
    }

    // --- findMaxPrefix Tests (24-32) ---

    @Test
    public void test24_FindMaxPrefix_ExactMatch() {
        pathTrie.addPath("/base/level1");
        assertEquals("/base/level1", pathTrie.findMaxPrefix("/base/level1"));
    }

    @Test
    public void test25_FindMaxPrefix_DeeperQuery_ReturnsParentPrefix() {
        pathTrie.addPath("/base/level1");
        assertEquals("/base/level1", pathTrie.findMaxPrefix("/base/level1/level2/level3"),
                "Deeper queries should resolve to the longest registered parent prefix.");
    }

    @Test
    public void test26_FindMaxPrefix_Sibling_ReturnsRoot() {
        pathTrie.addPath("/base/level1");
        assertEquals("/", pathTrie.findMaxPrefix("/base/level2"),
                "Sibling paths should fall back to root if parent has no property.");
    }

    @Test
    public void test27_FindMaxPrefix_UnrelatedPath_ReturnsRoot() {
        pathTrie.addPath("/base");
        assertEquals("/", pathTrie.findMaxPrefix("/other"),
                "Unrelated paths should fall back to root.");
    }

    @Test
    public void test28_FindMaxPrefix_Null_ThrowsNPE() {
        assertThrows(NullPointerException.class, () -> pathTrie.findMaxPrefix(null),
                "Finding prefix of null should throw NullPointerException.");
    }

    @Test
    public void test29_FindMaxPrefix_EmptyString_ReturnsRoot() {
        pathTrie.addPath("/test");
        assertEquals("/", pathTrie.findMaxPrefix(""),
                "Finding max prefix for empty string effectively evaluates against root.");
    }

    @Test
    public void test30_FindMaxPrefix_Root_ReturnsRoot() {
        pathTrie.addPath("/test");
        assertEquals("/", pathTrie.findMaxPrefix("/"),
                "Finding max prefix of root should always return root.");
    }

    @Test
    public void test31_FindMaxPrefix_PartialMatchButNoProperty_ReturnsRoot() {
        pathTrie.addPath("/a/b/c");
        // /a/b matches structurally but lacks the property flag
        assertEquals("/", pathTrie.findMaxPrefix("/a/b/d"));
    }

    @Test
    public void test32_FindMaxPrefix_DeepestPrefixChosen() {
        pathTrie.addPath("/a");
        pathTrie.addPath("/a/b");
        pathTrie.addPath("/a/b/c");
        assertEquals("/a/b/c", pathTrie.findMaxPrefix("/a/b/c/d/e"),
                "Must return the deepest nested prefix matched.");
    }

    // --- deletePath Tests (33-40) ---

    @Test
    public void test33_DeletePath_LeafNode_RemovesPrefixAndStructuralNode() {
        pathTrie.addPath("/a/b");
        pathTrie.deletePath("/a/b");
        assertFalse(pathTrie.existsNode("/a/b"), "Deleted leaf node should be removed structurally.");
        assertEquals("/", pathTrie.findMaxPrefix("/a/b"));
    }

    @Test
    public void test34_DeletePath_IntermediateNode_UnsetsPropertyLeavesChildren() {
        pathTrie.addPath("/a");
        pathTrie.addPath("/a/b");
        pathTrie.deletePath("/a");
        assertTrue(pathTrie.existsNode("/a"), "Intermediate node must exist structurally for its children.");
        assertTrue(pathTrie.existsNode("/a/b"), "Child node must remain completely unaffected.");
        assertEquals("/", pathTrie.findMaxPrefix("/a/c"), "Deleted node should no longer act as a prefix.");
    }

    @Test
    public void test35_DeletePath_NonExistentPath_IgnoresGracefully() {
        pathTrie.addPath("/a");
        assertDoesNotThrow(() -> pathTrie.deletePath("/b"),
                "Deleting a non-existent path should return without error.");
        assertTrue(pathTrie.existsNode("/a"), "Existing paths should remain unharmed.");
    }

    @Test
    public void test36_DeletePath_Null_ThrowsNPE() {
        assertThrows(NullPointerException.class, () -> pathTrie.deletePath(null),
                "Deleting null should throw NullPointerException.");
    }

    @Test
    public void test37_DeletePath_EmptyString_ThrowsIAE() {
        assertThrows(IllegalArgumentException.class, () -> pathTrie.deletePath(""),
                "Deleting an empty string should throw IllegalArgumentException.");
    }

    @Test
    public void test38_DeletePath_Root_ThrowsNPE() {
        // Based on implementation, split("/") yields empty array, parent loop is skipped,
        // realParent attempts parent.getParent() where parent is rootNode (whose parent is null).
        // This validates the architectural limitation of the class.
        assertThrows(NullPointerException.class, () -> pathTrie.deletePath("/"),
                "Deleting root throws NPE based on the internal split array logic.");
    }

    @Test
    public void test39_DeletePath_MultipleSlashes_Normalized() {
        pathTrie.addPath("/a/b");
        pathTrie.deletePath("//a///b");
        assertFalse(pathTrie.existsNode("/a/b"), "Paths with extra slashes should be normalized and deleted.");
    }

    @Test
    public void test40_DeletePath_ReAddDeletedPath_Works() {
        pathTrie.addPath("/a/b");
        pathTrie.deletePath("/a/b");
        pathTrie.addPath("/a/b");
        assertTrue(pathTrie.existsNode("/a/b"), "A deleted path should be successfully re-added.");
        assertEquals("/a/b", pathTrie.findMaxPrefix("/a/b/c"));
    }
}