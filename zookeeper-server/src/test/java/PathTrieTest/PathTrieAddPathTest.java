package PathTrieTest;

import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PathTrieAddPathTest {

    private PathTrie trie;

    @BeforeEach
    public void setUp() {
        trie = new PathTrie();
    }

    static Stream<Arguments> addPathParameters() {
        return Stream.of(
                // T1 - path valido semplice, trie vuoto
                Arguments.of(
                        Collections.emptyList(),
                        "/a",
                        Arrays.asList("/a")
                ),

                // T2 - path valido multilivello, trie vuoto
                Arguments.of(
                        Collections.emptyList(),
                        "/a/b/c",
                        Arrays.asList("/a/b/c")
                ),

                // T3 - path già presente
                Arguments.of(
                        Arrays.asList("/a/b"),
                        "/a/b",
                        Arrays.asList("/a/b")
                ),

                // T4 - path che estende un prefisso esistente
                Arguments.of(
                        Arrays.asList("/a"),
                        "/a/b",
                        Arrays.asList("/a", "/a/b")
                ),

                // T5 - path che è prefisso di un path già esistente
                Arguments.of(
                        Arrays.asList("/a/b/c"),
                        "/a",
                        Arrays.asList("/a", "/a/b/c")
                ),

                // T6 - path indipendente dai path già presenti
                Arguments.of(
                        Arrays.asList("/a/b"),
                        "/x/y",
                        Arrays.asList("/a/b", "/x/y")
                )
        );
    }

    @ParameterizedTest(name = "{index}: initialPaths={0}, addPath({1})")
    @MethodSource("addPathParameters")
    public void addPathShouldMakePathExistInTrie(
            List<String> initialPaths,
            String pathToAdd,
            List<String> expectedPaths) {

        for (String initialPath : initialPaths) {
            trie.addPath(initialPath);
        }

        trie.addPath(pathToAdd);

        for (String expectedPath : expectedPaths) {
            assertTrue(trie.existsNode(expectedPath));
        }
    }

    // T7 - path nullo
    @Test
    public void addPathShouldThrowExceptionWhenPathIsNull() {
        assertThrows(NullPointerException.class, () -> trie.addPath(null));
    }

    // T8 - path vuoto
    @Test
    public void addPathShouldThrowExceptionWhenPathIsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> trie.addPath(""));
    }

    static Stream<Arguments> malformedPathParameters() {
        return Stream.of(
                // T9 - path senza slash iniziale
                Arguments.of("a/b"),

                // T10 - path con slash multipli interni
                Arguments.of("/a//b"),

                // T11 - path con doppio slash iniziale
                Arguments.of("//a/b"),

                // T12 - path con slash finale
                Arguments.of("/a/b/"),

                // T13 - path composto da spazio
                Arguments.of(" "),

                // T14 - path con componente ..
                Arguments.of("/a/../b"),

                // T15 - path con componente .
                Arguments.of("/a/./b"),

                // T16 - path con backslash stile Windows
                Arguments.of("\\a\\b"),

                // T17 - path misto slash e backslash
                Arguments.of("/a\\b"),

                // T18 - path stile Windows completo
                Arguments.of("C:\\a\\b")
        );
    }

    @ParameterizedTest(name = "{index}: malformed addPath({0})")
    @MethodSource("malformedPathParameters")
    public void addPathWithMalformedPathShouldNotCorruptTrie(String malformedPath) {

        try {
            trie.addPath(malformedPath);
        } catch (RuntimeException ignored) {
            // In questo caso accettiamo che il metodo possa rifiutare il path.
            // L'obiettivo del test è verificare che la struttura non venga corrotta.
        }

        trie.addPath("/valid");

        assertTrue(trie.existsNode("/valid"));
    }

    // T19 - path valido semplice, trie dopo cancellazione
    @Test
    public void addPathShouldWorkAfterDeletePath() {
        trie.addPath("/a/b");

        trie.deletePath("/a/b");

        trie.addPath("/a/b");

        assertTrue(trie.existsNode("/a/b"));
    }

    // T20 - path valido semplice, trie dopo reset
    @Test
    public void addPathShouldWorkAfterClear() {
        trie.addPath("/a");

        trie.clear();

        trie.addPath("/b");

        assertFalse(trie.existsNode("/a"));
        assertTrue(trie.existsNode("/b"));
    }
}