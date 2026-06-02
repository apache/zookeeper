package PathTrieTest.TestLLM.C1;


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

    // --- State & Initialization Tests ---

    @Test
    public void testConstructor_InitialState_RootExists() {
        assertTrue(pathTrie.existsNode("/"), "Root node should exist upon initialization");
    }

    // --- addPath Tests ---

    @Test
    public void testAddPath_ValidSingleLevel_ExistsNodeReturnsTrue() {
        pathTrie.addPath("/node1");
        assertTrue(pathTrie.existsNode("/node1"));
    }

    @Test
    public void testAddPath_ValidMultiLevel_ExistsNodeReturnsTrue() {
        pathTrie.addPath("/node1/node2/node3");
        assertTrue(pathTrie.existsNode("/node1/node2/node3"));
        assertTrue(pathTrie.existsNode("/node1/node2")); // Intermediate node should exist
    }

    @Test
    public void testAddPath_NullPath_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> pathTrie.addPath(null));
    }

    @Test
    public void testAddPath_EmptyPath_ThrowsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> pathTrie.addPath(""));
        assertEquals("Invalid path: ", ex.getMessage());
    }

    @Test
    public void testAddPath_DuplicatePath_SuccessfullyAdded() {
        pathTrie.addPath("/duplicate");
        pathTrie.addPath("/duplicate");
        assertTrue(pathTrie.existsNode("/duplicate"));
    }

    @Test
    public void testAddPath_RootPath_SuccessfullyAdded() {
        pathTrie.addPath("/");
        assertTrue(pathTrie.existsNode("/"));
    }

    @Test
    public void testAddPath_TrailingSlashNormalized() {
        pathTrie.addPath("/a/b/");
        assertTrue(pathTrie.existsNode("/a/b"), "Trailing slashes should be normalized out");
    }

    @Test
    public void testAddPath_ConsecutiveSlashesNormalized() {
        pathTrie.addPath("//a///b");
        assertTrue(pathTrie.existsNode("/a/b"), "Consecutive slashes should be normalized out");
    }

    @Test
    public void testAddPath_SpaceComponent_FilteredOut() {
        pathTrie.addPath("/a/ /b");
        assertTrue(pathTrie.existsNode("/a/b"), "Whitespace-only components should be filtered out by split");
    }

    @Test
    public void testAddPath_SpecialCharacters() {
        pathTrie.addPath("/a-1/b_2.ext");
        assertTrue(pathTrie.existsNode("/a-1/b_2.ext"));
    }

    // --- existsNode Tests ---

    @Test
    public void testExistsNode_NullPath_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> pathTrie.existsNode(null));
    }

    @Test
    public void testExistsNode_EmptyPath_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> pathTrie.existsNode(""));
    }

    @Test
    public void testExistsNode_NonExistentSingleLevel_ReturnsFalse() {
        assertFalse(pathTrie.existsNode("/missing"));
    }

    @Test
    public void testExistsNode_NonExistentMultiLevel_ReturnsFalse() {
        assertFalse(pathTrie.existsNode("/missing/path"));
    }

    @Test
    public void testExistsNode_IntermediateNode_ReturnsTrue() {
        pathTrie.addPath("/a/b/c");
        assertTrue(pathTrie.existsNode("/a/b"));
    }

    @Test
    public void testExistsNode_RootPath_ReturnsTrue() {
        assertTrue(pathTrie.existsNode("/"));
    }

    @Test
    public void testExistsNode_PartialToken_ReturnsFalse() {
        pathTrie.addPath("/abcdef");
        assertFalse(pathTrie.existsNode("/abc"), "Partial token match should return false");
    }

    @Test
    public void testExistsNode_TrailingSlash_Normalized() {
        pathTrie.addPath("/a");
        assertTrue(pathTrie.existsNode("/a/"));
    }

    // --- deletePath Tests ---

    @Test
    public void testDeletePath_NullPath_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> pathTrie.deletePath(null));
    }

    @Test
    public void testDeletePath_EmptyPath_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> pathTrie.deletePath(""));
    }

    @Test
    public void testDeletePath_ExistingLeaf_RemovesNode() {
        pathTrie.addPath("/a/b");
        pathTrie.deletePath("/a/b");
        assertFalse(pathTrie.existsNode("/a/b"));
    }

    @Test
    public void testDeletePath_ExistingIntermediate_KeepsNodeForChildren() {
        pathTrie.addPath("/a/b");
        pathTrie.addPath("/a");
        pathTrie.deletePath("/a");
        assertTrue(pathTrie.existsNode("/a/b"), "Child should remain");
        assertTrue(pathTrie.existsNode("/a"), "Intermediate node should remain to route to child");
    }

    @Test
    public void testDeletePath_ExistingIntermediate_RemovesProperty() {
        pathTrie.addPath("/a/b");
        pathTrie.addPath("/a");
        pathTrie.deletePath("/a");
        assertEquals("/", pathTrie.findMaxPrefix("/a"), "Node /a should no longer have the property set");
    }

    @Test
    public void testDeletePath_NonExistentPath_DoesNothing() {
        pathTrie.addPath("/a");
        assertDoesNotThrow(() -> pathTrie.deletePath("/b"));
        assertTrue(pathTrie.existsNode("/a"));
    }

    @Test
    public void testDeletePath_RootPath_ThrowsNullPointerException() {
        pathTrie.addPath("/");
        // Edge case: Current PathTrie implementation throws NPE when attempting to delete the root node
        assertThrows(NullPointerException.class, () -> pathTrie.deletePath("/"));
    }

    @Test
    public void testDeletePath_DeepNode_LeavesParentIntact() {
        pathTrie.addPath("/a/b/c");
        pathTrie.deletePath("/a/b/c");
        assertTrue(pathTrie.existsNode("/a/b"));
        assertFalse(pathTrie.existsNode("/a/b/c"));
    }

    @Test
    public void testDeletePath_MultipleTimes_Safe() {
        pathTrie.addPath("/a");
        pathTrie.deletePath("/a");
        assertDoesNotThrow(() -> pathTrie.deletePath("/a")); // Deleting already deleted path
    }

    @Test
    public void testDeletePath_ConsecutiveSlashes_Normalized() {
        pathTrie.addPath("/a/b");
        pathTrie.deletePath("//a///b");
        assertFalse(pathTrie.existsNode("/a/b"));
    }

    // --- findMaxPrefix Tests ---

    @Test
    public void testFindMaxPrefix_NullPath_ThrowsNullPointerException() {
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
    public void testFindMaxPrefix_ExactMatch_ReturnsPath() {
        pathTrie.addPath("/a/b");
        assertEquals("/a/b", pathTrie.findMaxPrefix("/a/b"));
    }

    @Test
    public void testFindMaxPrefix_ChildPath_ReturnsParent() {
        pathTrie.addPath("/a/b");
        assertEquals("/a/b", pathTrie.findMaxPrefix("/a/b/c/d"));
    }

    @Test
    public void testFindMaxPrefix_IntermediateNodeNotExplicitlyAdded_ReturnsRoot() {
        pathTrie.addPath("/a/b");
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
    public void testFindMaxPrefix_UnrelatedPath_ReturnsRoot() {
        pathTrie.addPath("/a");
        assertEquals("/", pathTrie.findMaxPrefix("/b"));
    }

    @Test
    public void testFindMaxPrefix_AfterDelete_ReturnsNextHighest() {
        pathTrie.addPath("/a");
        pathTrie.addPath("/a/b");
        pathTrie.deletePath("/a/b");
        assertEquals("/a", pathTrie.findMaxPrefix("/a/b/c"));
    }

    // --- clear Tests ---

    @Test
    public void testClear_PopulatedTrie_RemovesAllNodes() {
        pathTrie.addPath("/a/b");
        pathTrie.addPath("/c");
        pathTrie.clear();
        assertFalse(pathTrie.existsNode("/a"));
        assertFalse(pathTrie.existsNode("/c"));
        assertEquals("/", pathTrie.findMaxPrefix("/a/b"));
    }

    @Test
    public void testClear_EmptyTrie_DoesNothing() {
        assertDoesNotThrow(() -> pathTrie.clear());
        assertTrue(pathTrie.existsNode("/"));
    }

    // --- Mixed / Complex Scenario Tests ---

    @Test
    public void testMixedOperations_ComplexScenario() {
        pathTrie.addPath("/a/b/c");
        pathTrie.addPath("/a/b/d");

        pathTrie.deletePath("/a/b/c");
        assertTrue(pathTrie.existsNode("/a/b/d"));
        assertFalse(pathTrie.existsNode("/a/b/c"));

        // Even though /a/b/c and /a/b/d were added, the deepest max prefix for /a/b is still /
        // because /a/b itself was never explicitly added (its property is false)
        assertEquals("/", pathTrie.findMaxPrefix("/a/b"));

        pathTrie.clear();
        assertFalse(pathTrie.existsNode("/a/b/d"));
    }
}