package PathTrieTest.TestLLM;



import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class PathTrieFewShotCodeTest {

    private PathTrie pathTrie;

    @BeforeEach
    public void setUp() {
        pathTrie = new PathTrie();
    }

    // ========================================================================
    // Parametri per i test
    // ========================================================================

    static Stream<Arguments> validPathsParameters() {
        return Stream.of(
                // T1 - Path semplice
                Arguments.of(Arrays.asList("/a"), "/a"),

                // T2 - Path multilivello
                Arguments.of(Arrays.asList("/a/b"), "/a/b"),

                // T3 - Path con nodi intermedi inseriti esplicitamente
                Arguments.of(Arrays.asList("/a", "/a/b"), "/a/b"),

                // T4 - Path malformati ma gestiti dal trim/split interno
                Arguments.of(Arrays.asList("//a///b/"), "/a/b")
        );
    }

    static Stream<Arguments> invalidPathsParameters() {
        return Stream.of(
                // T5 - Path vuoto
                Arguments.of("")
        );
    }

    static Stream<Arguments> maxPrefixParameters() {
        return Stream.of(
                // T6 - Match esatto
                Arguments.of("/app/config", "/app/config", "/app/config"),

                // T7 - Figlio di un path esistente restituisce il padre
                Arguments.of("/app/config", "/app/config/db", "/app/config"),

                // T8 - Nodo intermedio non esplicitamente aggiunto restituisce root
                Arguments.of("/app/config/db", "/app", "/"),

                // T9 - Nessun match restituisce root
                Arguments.of("/app/config", "/test", "/")
        );
    }

    // ========================================================================
    // Test per addPath() ed existsNode()
    // ========================================================================

    @ParameterizedTest(name = "{index}: addPath({1})")
    @MethodSource("validPathsParameters")
    public void addPathShouldAddNodeAndExistsNodeShouldReturnTrueWhenPathIsValid(
            List<String> pathsToAdd, String pathToVerify) {

        for (String path : pathsToAdd) {
            pathTrie.addPath(path);
        }

        assertTrue(pathTrie.existsNode(pathToVerify), "Il nodo " + pathToVerify + " dovrebbe esistere");
    }

    @Test
    public void existsNodeShouldReturnFalseWhenPathDoesNotExist() {
        pathTrie.addPath("/a/b");

        assertFalse(pathTrie.existsNode("/a/c"), "Il nodo non dovrebbe esistere");
        assertFalse(pathTrie.existsNode("/x/y"), "Il nodo non dovrebbe esistere");
    }

    @ParameterizedTest(name = "{index}: invalid path = ''{0}''")
    @MethodSource("invalidPathsParameters")
    public void addPathShouldThrowIllegalArgumentExceptionWhenPathIsEmpty(String invalidPath) {
        assertThrows(
                IllegalArgumentException.class,
                () -> pathTrie.addPath(invalidPath)
        );
    }

    @Test
    public void addPathShouldThrowNullPointerExceptionWhenPathIsNull() {
        assertThrows(
                NullPointerException.class,
                () -> pathTrie.addPath(null)
        );
    }

    @Test
    public void existsNodeShouldThrowNullPointerExceptionWhenPathIsNull() {
        assertThrows(
                NullPointerException.class,
                () -> pathTrie.existsNode(null)
        );
    }

    // ========================================================================
    // Test per findMaxPrefix()
    // ========================================================================

    @ParameterizedTest(name = "{index}: findMaxPrefix({1}) expecting {2}")
    @MethodSource("maxPrefixParameters")
    public void findMaxPrefixShouldReturnCorrectPrefix(
            String pathToAdd, String pathToQuery, String expectedPrefix) {

        pathTrie.addPath(pathToAdd);

        String maxPrefix = pathTrie.findMaxPrefix(pathToQuery);

        assertEquals(expectedPrefix, maxPrefix);
    }

    @Test
    public void findMaxPrefixShouldReturnRootWhenTrieIsEmpty() {
        assertEquals("/", pathTrie.findMaxPrefix("/any/path"));
    }

    @Test
    public void findMaxPrefixShouldThrowNullPointerExceptionWhenPathIsNull() {
        assertThrows(
                NullPointerException.class,
                () -> pathTrie.findMaxPrefix(null)
        );
    }

    // ========================================================================
    // Test per deletePath()
    // ========================================================================

    @Test
    public void deletePathShouldRemoveLeafNodeCorrectly() {
        pathTrie.addPath("/a/b");
        assertTrue(pathTrie.existsNode("/a/b"));

        pathTrie.deletePath("/a/b");

        assertFalse(pathTrie.existsNode("/a/b"));
        assertEquals("/", pathTrie.findMaxPrefix("/a/b"));
    }

    @Test
    public void deletePathShouldUnsetPropertyWhenNodeHasChildren() {
        pathTrie.addPath("/a");
        pathTrie.addPath("/a/b");

        // Rimuoviamo il nodo genitore
        pathTrie.deletePath("/a");

        // Il nodo figlio deve ancora esistere e funzionare
        assertTrue(pathTrie.existsNode("/a/b"));
        assertEquals("/a/b", pathTrie.findMaxPrefix("/a/b/c"));

        // Il genitore non è più una property valida per il prefisso massimo
        assertEquals("/", pathTrie.findMaxPrefix("/a/x"));
    }

    @Test
    public void deletePathShouldNotThrowExceptionWhenPathDoesNotExist() {
        pathTrie.addPath("/a/b");

        assertDoesNotThrow(() -> pathTrie.deletePath("/x/y"));
        assertTrue(pathTrie.existsNode("/a/b"), "I nodi esistenti non devono essere alterati");
    }

    @Test
    public void deletePathShouldThrowNullPointerExceptionWhenRootIsDeleted() {
        // Come da implementazione interna, l'eliminazione della root causa NPE
        assertThrows(
                NullPointerException.class,
                () -> pathTrie.deletePath("/")
        );
    }

    @Test
    public void deletePathShouldThrowNullPointerExceptionWhenPathIsNull() {
        assertThrows(
                NullPointerException.class,
                () -> pathTrie.deletePath(null)
        );
    }

    // ========================================================================
    // Test per clear()
    // ========================================================================

    @Test
    public void clearShouldRemoveAllNodes() {
        pathTrie.addPath("/a/b");
        pathTrie.addPath("/x/y");

        assertTrue(pathTrie.existsNode("/a/b"));
        assertTrue(pathTrie.existsNode("/x/y"));

        pathTrie.clear();

        assertFalse(pathTrie.existsNode("/a/b"));
        assertFalse(pathTrie.existsNode("/x/y"));
        assertEquals("/", pathTrie.findMaxPrefix("/a/b"));
    }
}
