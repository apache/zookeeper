package PathTrieTest.TestLLM.C4;


import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PathTrieZeroShotRun10Test {

    private PathTrie trie;

    @BeforeEach
    public void setUp() {
        trie = new PathTrie();
    }

    // --- addPath Tests (8 tests) ---

    @Test
    void testAddPathSingleLevel() {
        trie.addPath("/node1");
        assertTrue(trie.existsNode("/node1"));
    }

    @Test
    void testAddPathMultiLevel() {
        trie.addPath("/node1/node2/node3");
        assertTrue(trie.existsNode("/node1/node2/node3"));
        assertTrue(trie.existsNode("/node1/node2")); // Parent nodes should also exist structurally
    }

    @Test
    void testAddPathSharedPrefix() {
        trie.addPath("/app/service1");
        trie.addPath("/app/service2");
        assertTrue(trie.existsNode("/app/service1"));
        assertTrue(trie.existsNode("/app/service2"));
        assertTrue(trie.existsNode("/app"));
    }

    @Test
    void testAddPathWithMultipleSlashes() {
        trie.addPath("///app//service///");
        // Internal split filters empty parts, so it normalizes to /app/service
        assertTrue(trie.existsNode("/app/service"));
    }

    @Test
    void testAddPathIdempotency() {
        trie.addPath("/node1");
        trie.addPath("/node1");
        assertTrue(trie.existsNode("/node1"));
        assertEquals("/node1", trie.findMaxPrefix("/node1"));
    }

    @Test
    void testAddPathNullThrowsNPE() {
        assertThrows(NullPointerException.class, () -> trie.addPath(null));
    }

    @Test
    void testAddPathEmptyThrowsIAE() {
        assertThrows(IllegalArgumentException.class, () -> trie.addPath(""));
    }

    @Test
    void testAddPathRootOnly() {
        trie.addPath("/");
        assertTrue(trie.existsNode("/"));
        // Root path is valid and should set the root node's property to true
        assertEquals("/", trie.findMaxPrefix("/"));
    }

    // --- existsNode Tests (7 tests) ---

    @Test
    void testExistsNodeSingleLevel() {
        trie.addPath("/a");
        assertTrue(trie.existsNode("/a"));
    }

    @Test
    void testExistsNodeMultiLevel() {
        trie.addPath("/a/b/c");
        assertTrue(trie.existsNode("/a/b/c"));
    }

    @Test
    void testExistsNodeNonExistent() {
        trie.addPath("/a/b");
        assertFalse(trie.existsNode("/a/c"));
        assertFalse(trie.existsNode("/x"));
    }

    @Test
    void testExistsNodePartialPath() {
        trie.addPath("/a/b/c");
        // nodes 'a' and 'b' are structurally created
        assertTrue(trie.existsNode("/a"));
        assertTrue(trie.existsNode("/a/b"));
    }

    @Test
    void testExistsNodeNullThrowsNPE() {
        assertThrows(NullPointerException.class, () -> trie.existsNode(null));
    }

    @Test
    void testExistsNodeEmptyThrowsIAE() {
        assertThrows(IllegalArgumentException.class, () -> trie.existsNode(""));
    }

    @Test
    void testExistsNodeAfterClear() {
        trie.addPath("/a/b");
        trie.clear();
        assertFalse(trie.existsNode("/a/b"));
        assertFalse(trie.existsNode("/a"));
    }

    // --- findMaxPrefix Tests (10 tests) ---

    @Test
    void testFindMaxPrefixExactMatch() {
        trie.addPath("/a/b/c");
        assertEquals("/a/b/c", trie.findMaxPrefix("/a/b/c"));
    }

    @Test
    void testFindMaxPrefixSubPathNoProperty() {
        trie.addPath("/a/b/c");
        // /a/b exists as a node, but property is not set for it, so prefix falls back to /
        assertEquals("/", trie.findMaxPrefix("/a/b"));
    }

    @Test
    void testFindMaxPrefixNoPropertyNodeReturnsRoot() {
        assertEquals("/", trie.findMaxPrefix("/a/b/c"));
    }

    @Test
    void testFindMaxPrefixNullThrowsNPE() {
        assertThrows(NullPointerException.class, () -> trie.findMaxPrefix(null));
    }

    @Test
    void testFindMaxPrefixMultiplePropertyNodes() {
        trie.addPath("/a");
        trie.addPath("/a/b");
        trie.addPath("/a/b/c");
        assertEquals("/a/b/c", trie.findMaxPrefix("/a/b/c/d"));
        assertEquals("/a/b", trie.findMaxPrefix("/a/b/x"));
        assertEquals("/a", trie.findMaxPrefix("/a/x/y"));
    }

    @Test
    void testFindMaxPrefixRootOnly() {
        assertEquals("/", trie.findMaxPrefix("/"));
    }

    @Test
    void testFindMaxPrefixWithTrailingSlashes() {
        trie.addPath("/a/b");
        assertEquals("/a/b", trie.findMaxPrefix("/a/b/c//"));
    }

    @Test
    void testFindMaxPrefixUnrelatedPath() {
        trie.addPath("/a/b");
        assertEquals("/", trie.findMaxPrefix("/x/y"));
    }

    @Test
    void testFindMaxPrefixBranching() {
        trie.addPath("/a/b");
        trie.addPath("/a/c");
        assertEquals("/a/c", trie.findMaxPrefix("/a/c/d"));
        assertEquals("/a/b", trie.findMaxPrefix("/a/b/d"));
    }

    @Test
    void testFindMaxPrefixOnEmptyString() {
        // split("") yields an empty array, loop doesn't execute, returns "/"
        assertEquals("/", trie.findMaxPrefix(""));
    }

    // --- deletePath Tests (9 tests) ---

    @Test
    void testDeletePathSingleLevelLeaf() {
        trie.addPath("/a");
        trie.deletePath("/a");
        assertFalse(trie.existsNode("/a"));
    }

    @Test
    void testDeletePathWithChildrenRemovesPropertyOnly() {
        trie.addPath("/a");
        trie.addPath("/a/b");
        trie.deletePath("/a");
        // /a still exists structurally because it has child /b
        assertTrue(trie.existsNode("/a"));
        assertTrue(trie.existsNode("/a/b"));
        // But /a no longer has the property
        assertEquals("/", trie.findMaxPrefix("/a"));
    }

    @Test
    void testDeletePathNonExistent() {
        trie.addPath("/a/b/c");
        // Deleting non-existent path branches gracefully handles null child
        trie.deletePath("/a/x/y");
        assertTrue(trie.existsNode("/a/b/c"));
    }

    @Test
    void testDeletePathNullThrowsNPE() {
        assertThrows(NullPointerException.class, () -> trie.deletePath(null));
    }

    @Test
    void testDeletePathEmptyThrowsIAE() {
        assertThrows(IllegalArgumentException.class, () -> trie.deletePath(""));
    }

    @Test
    void testDeletePathRootThrowsNPE() {
        // Implementation quirk: split("/") is empty array, parent ends up as rootNode
        // realParent becomes null (rootNode.parent is null), causing realParent.deleteChild to throw NPE
        assertThrows(NullPointerException.class, () -> trie.deletePath("/"));
    }

    @Test
    void testDeletePathChangesMaxPrefix() {
        trie.addPath("/a/b");
        assertEquals("/a/b", trie.findMaxPrefix("/a/b/c"));
        trie.deletePath("/a/b");
        assertEquals("/", trie.findMaxPrefix("/a/b/c"));
    }

    @Test
    void testDeletePathDoesNotCascadeUp() {
        trie.addPath("/a/b");
        trie.deletePath("/a/b");
        assertFalse(trie.existsNode("/a/b"));
        // The implementation does not cascade up to delete 'a' even though it's now a leaf
        assertTrue(trie.existsNode("/a"));
    }

    @Test
    void testDeletePathIndependentBranches() {
        trie.addPath("/a/b");
        trie.addPath("/a/c");
        trie.deletePath("/a/b");
        assertTrue(trie.existsNode("/a/c"));
        assertFalse(trie.existsNode("/a/b"));
    }

    // --- clear Tests (3 tests) ---

    @Test
    void testClearEmptyTrie() {
        assertDoesNotThrow(() -> trie.clear());
        assertEquals("/", trie.findMaxPrefix("/any/path"));
    }

    @Test
    void testClearPopulatedTrieExistsFalse() {
        trie.addPath("/a/b");
        trie.addPath("/c/d");
        trie.clear();
        assertFalse(trie.existsNode("/a"));
        assertFalse(trie.existsNode("/a/b"));
        assertFalse(trie.existsNode("/c"));
        assertFalse(trie.existsNode("/c/d"));
    }

    @Test
    void testClearPopulatedTrieFindMaxPrefixRoot() {
        trie.addPath("/x/y/z");
        trie.clear();
        assertEquals("/", trie.findMaxPrefix("/x/y/z"));
    }

    // --- Combinations & Edge Cases Tests (3 tests) ---

    @Test
    void testAddDeleteReaddCycle() {
        trie.addPath("/x/y");
        assertTrue(trie.existsNode("/x/y"));

        trie.deletePath("/x/y");
        assertFalse(trie.existsNode("/x/y"));

        trie.addPath("/x/y");
        assertTrue(trie.existsNode("/x/y"));
        assertEquals("/x/y", trie.findMaxPrefix("/x/y/z"));
    }

    @Test
    void testDeleteParentMaintainsChildMaxPrefix() {
        trie.addPath("/a");
        trie.addPath("/a/b");

        // Remove parent property
        trie.deletePath("/a");

        // Parent is no longer a valid max prefix
        assertEquals("/", trie.findMaxPrefix("/a"));
        // But child still is
        assertEquals("/a/b", trie.findMaxPrefix("/a/b/c"));
    }

    @Test
    void testDeepTreeOperations() {
        String path = "/level1/level2/level3/level4/level5/level6";
        trie.addPath(path);

        assertTrue(trie.existsNode(path));
        assertEquals(path, trie.findMaxPrefix(path + "/level7/level8"));

        trie.deletePath(path);
        assertFalse(trie.existsNode(path)); // leaf is removed
        assertTrue(trie.existsNode("/level1/level2/level3/level4/level5")); // structural parent preserved
        assertEquals("/", trie.findMaxPrefix(path + "/level7")); // property is completely gone
    }
}