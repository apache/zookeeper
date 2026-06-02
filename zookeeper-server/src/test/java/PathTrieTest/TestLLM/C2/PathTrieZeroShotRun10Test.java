package PathTrieTest.TestLLM.C2;


import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PathTrieZeroShotRun10Test {

    private PathTrie pathTrie;

    @BeforeEach
    public void setUp() {
        pathTrie = new PathTrie();
    }

    // --- Constructor & AddPath Tests (1-10) ---

    @Test
    public void testConstructor_InitialState_RootExists() {
        assertTrue(pathTrie.existsNode("/"), "Root node should exist upon initialization");
    }

    @Test
    public void testAddPath_SingleLevel() {
        pathTrie.addPath("/node1");
        assertTrue(pathTrie.existsNode("/node1"));
    }

    @Test
    public void testAddPath_MultiLevel() {
        pathTrie.addPath("/node1/node2/node3");
        assertTrue(pathTrie.existsNode("/node1/node2/node3"));
        assertTrue(pathTrie.existsNode("/node1/node2"), "Intermediate nodes should implicitly exist");
    }

    @Test
    public void testAddPath_Null_ThrowsException() {
        assertThrows(NullPointerException.class, () -> pathTrie.addPath(null));
    }

    @Test
    public void testAddPath_Empty_ThrowsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> pathTrie.addPath(""));
        assertEquals("Invalid path: ", ex.getMessage());
    }

    @Test
    public void testAddPath_DuplicatePath() {
        pathTrie.addPath("/duplicate");
        assertDoesNotThrow(() -> pathTrie.addPath("/duplicate"));
        assertTrue(pathTrie.existsNode("/duplicate"));
    }

    @Test
    public void testAddPath_RootPath() {
        pathTrie.addPath("/");
        assertTrue(pathTrie.existsNode("/"));
    }

    @Test
    public void testAddPath_ConsecutiveSlashes() {
        pathTrie.addPath("//a///b");
        assertTrue(pathTrie.existsNode("/a/b"), "Consecutive slashes should be normalized");
    }

    @Test
    public void testAddPath_TrailingSlash() {
        pathTrie.addPath("/a/b/");
        assertTrue(pathTrie.existsNode("/a/b"), "Trailing slash should be normalized");
    }

    @Test
    public void testAddPath_DeepNesting() {
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            path.append("/level").append(i);
        }
        pathTrie.addPath(path.toString());
        assertTrue(pathTrie.existsNode(path.toString()));
    }

    // --- ExistsNode Tests (11-20) ---

    @Test
    public void testExistsNode_Null_ThrowsException() {
        assertThrows(NullPointerException.class, () -> pathTrie.existsNode(null));
    }

    @Test
    public void testExistsNode_Empty_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> pathTrie.existsNode(""));
    }

    @Test
    public void testExistsNode_NonExistentSingle() {
        assertFalse(pathTrie.existsNode("/missing"));
    }

    @Test
    public void testExistsNode_NonExistentMulti() {
        assertFalse(pathTrie.existsNode("/missing/path"));
    }

    @Test
    public void testExistsNode_IntermediateNode_Exists() {
        pathTrie.addPath("/a/b/c");
        assertTrue(pathTrie.existsNode("/a/b"));
    }

    @Test
    public void testExistsNode_Root() {
        assertTrue(pathTrie.existsNode("/"));
    }

    @Test
    public void testExistsNode_PartialTokenMatch() {
        pathTrie.addPath("/abcdef");
        assertFalse(pathTrie.existsNode("/abc"), "Partial token match should return false");
    }

    @Test
    public void testExistsNode_ConsecutiveSlashes() {
        pathTrie.addPath("/a/b");
        assertTrue(pathTrie.existsNode("//a///b"));
    }

    @Test
    public void testExistsNode_TrailingSlash() {
        pathTrie.addPath("/a/b");
        assertTrue(pathTrie.existsNode("/a/b/"));
    }

    @Test
    public void testExistsNode_SpecialCharacters() {
        pathTrie.addPath("/a-1/b_2.ext");
        assertTrue(pathTrie.existsNode("/a-1/b_2.ext"));
    }

    // --- DeletePath Tests (21-31) ---

    @Test
    public void testDeletePath_Null_ThrowsException() {
        assertThrows(NullPointerException.class, () -> pathTrie.deletePath(null));
    }

    @Test
    public void testDeletePath_Empty_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> pathTrie.deletePath(""));
    }

    @Test
    public void testDeletePath_ExistingLeaf() {
        pathTrie.addPath("/a/b");
        pathTrie.deletePath("/a/b");
        assertFalse(pathTrie.existsNode("/a/b"));
    }

    @Test
    public void testDeletePath_ExistingIntermediate_KeepsRouting() {
        pathTrie.addPath("/a/b");
        pathTrie.addPath("/a");
        pathTrie.deletePath("/a");
        assertTrue(pathTrie.existsNode("/a/b"), "Child should remain accessible");
        assertTrue(pathTrie.existsNode("/a"), "Intermediate node must remain to route to child");
    }

    @Test
    public void testDeletePath_ExistingIntermediate_MaxPrefixChanges() {
        pathTrie.addPath("/a/b");
        pathTrie.addPath("/a");
        pathTrie.deletePath("/a");
        assertEquals("/", pathTrie.findMaxPrefix("/a"), "Node /a should no longer have the property set");
    }

    @Test
    public void testDeletePath_NonExistentPath() {
        pathTrie.addPath("/a");
        assertDoesNotThrow(() -> pathTrie.deletePath("/b"));
        assertTrue(pathTrie.existsNode("/a"));
    }

    @Test
    public void testDeletePath_Root_ThrowsException() {
        // Implementation detail: deleting root tries to access its null parent
        assertThrows(NullPointerException.class, () -> pathTrie.deletePath("/"));
    }

    @Test
    public void testDeletePath_DeepNode() {
        pathTrie.addPath("/a/b/c");
        pathTrie.deletePath("/a/b/c");
        assertTrue(pathTrie.existsNode("/a/b"));
        assertFalse(pathTrie.existsNode("/a/b/c"));
    }

    @Test
    public void testDeletePath_Idempotent() {
        pathTrie.addPath("/a");
        pathTrie.deletePath("/a");
        assertDoesNotThrow(() -> pathTrie.deletePath("/a"), "Deleting an already deleted path should be safe");
    }

    @Test
    public void testDeletePath_ConsecutiveSlashes() {
        pathTrie.addPath("/a/b");
        pathTrie.deletePath("//a///b");
        assertFalse(pathTrie.existsNode("/a/b"));
    }

    @Test
    public void testDeletePath_TrailingSlash() {
        pathTrie.addPath("/a/b");
        pathTrie.deletePath("/a/b/");
        assertFalse(pathTrie.existsNode("/a/b"));
    }

    // --- FindMaxPrefix Tests (32-39) ---

    @Test
    public void testFindMaxPrefix_Null_ThrowsException() {
        assertThrows(NullPointerException.class, () -> pathTrie.findMaxPrefix(null));
    }

    @Test
    public void testFindMaxPrefix_EmptyPath_ReturnsRoot() {
        assertEquals("/", pathTrie.findMaxPrefix(""));
    }

    @Test
    public void testFindMaxPrefix_NoPathsAdded_ReturnsRoot() {
        assertEquals("/", pathTrie.findMaxPrefix("/a/b"));
    }

    @Test
    public void testFindMaxPrefix_ExactMatch() {
        pathTrie.addPath("/a/b");
        assertEquals("/a/b", pathTrie.findMaxPrefix("/a/b"));
    }

    @Test
    public void testFindMaxPrefix_ChildPath_ReturnsParent() {
        pathTrie.addPath("/a/b");
        assertEquals("/a/b", pathTrie.findMaxPrefix("/a/b/c/d"));
    }

    @Test
    public void testFindMaxPrefix_IntermediateNodeNotAdded() {
        pathTrie.addPath("/a/b");
        // /a was not explicitly added, so it doesn't have the property flag
        assertEquals("/", pathTrie.findMaxPrefix("/a"));
    }

    @Test
    public void testFindMaxPrefix_MultipleAdded_FindsDeepest() {
        pathTrie.addPath("/a");
        pathTrie.addPath("/a/b");
        pathTrie.addPath("/a/b/c");
        assertEquals("/a/b", pathTrie.findMaxPrefix("/a/b/d"));
    }

    @Test
    public void testFindMaxPrefix_AfterDelete_ReturnsNextHighest() {
        pathTrie.addPath("/a");
        pathTrie.addPath("/a/b");
        pathTrie.deletePath("/a/b");
        assertEquals("/a", pathTrie.findMaxPrefix("/a/b/c"));
    }

    // --- Clear Test (40) ---

    @Test
    public void testClear_PopulatedTrie_RemovesAllNodes() {
        pathTrie.addPath("/a/b");
        pathTrie.addPath("/c");
        pathTrie.clear();
        assertFalse(pathTrie.existsNode("/a"));
        assertFalse(pathTrie.existsNode("/c"));
        assertEquals("/", pathTrie.findMaxPrefix("/a/b"), "Max prefix should fallback to root after clear");
    }
}