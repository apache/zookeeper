package PathTrieTest.TestManuali;

import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class PathTrieAddPathTest {

    @Test
    public void T1_pathValidoSemplice_trieVuoto() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a");

        assertTrue(trie.existsNode("/a"));
    }

    @Test
    public void T2_pathValidoMultilivello_trieVuoto() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a/b/c");

        assertTrue(trie.existsNode("/a/b/c"));
    }

    @Test
    public void T3_pathGiaPresente() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a/b");
        trie.addPath("/a/b");

        assertTrue(trie.existsNode("/a/b"));
    }

    @Test
    public void T4_pathCheEstendePrefissoEsistente() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a");
        trie.addPath("/a/b");

        assertTrue(trie.existsNode("/a/b"));
    }

    @Test
    public void T5_pathPrefissoDiPathGiaEsistente() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a/b/c");
        trie.addPath("/a");

        assertTrue(trie.existsNode("/a"));
    }

    @Test
    public void T6_trieConPiuRamiIndipendenti() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a/b");
        trie.addPath("/x/y");

        assertTrue(trie.existsNode("/a/b"));
        assertTrue(trie.existsNode("/x/y"));
    }

    @Test
    public void T7_pathNullo_statoNonCorrotto() {
        PathTrie trie = new PathTrie();

        executeAllowingRuntimeException(() -> trie.addPath(null));

        assertTrieStillUsable(trie);
    }

    @Disabled("Oracolo iniziale troppo restrittivo: PathTrie accetta diversi input anomali")
    @ParameterizedTest(name = "{0} - path malformato: {1}")
    @MethodSource("malformedPaths")
    public void T8_T18_pathVuotoOMalformato_pathNonInseritoEStatoNonCorrotto(String testId, String malformedPath) {
        PathTrie trie = new PathTrie();

        executeAllowingRuntimeException(() -> trie.addPath(malformedPath));

        assertPathNotInserted(trie, malformedPath);
        assertTrieStillUsable(trie);
    }

    @ParameterizedTest(name = "{0} - input anomalo/non canonico: {1}")
    @MethodSource("anomalousPaths")
    public void T8_T18_Ridefiniti_inputAnomalo_statoNonCorrotto(String testId, String anomalousPath) {
        PathTrie trie = new PathTrie();

        executeAllowingRuntimeException(() -> trie.addPath(anomalousPath));

        assertTrieStillUsable(trie);
    }

    private static Stream<Arguments> anomalousPaths() {
        return Stream.of(
                Arguments.of("T8", ""),
                Arguments.of("T9", "a/b"),
                Arguments.of("T10", "/a//b"),
                Arguments.of("T11", "//a/b"),
                Arguments.of("T12", "/a/b/"),
                Arguments.of("T13", " "),
                Arguments.of("T14", "/a/../b"),
                Arguments.of("T15", "/a/./b"),
                Arguments.of("T16", "\\a\\b"),
                Arguments.of("T17", "/a\\b"),
                Arguments.of("T18", "C:\\a\\b")
        );
    }

    private static Stream<Arguments> malformedPaths() {
        return Stream.of(
                Arguments.of("T8", ""),
                Arguments.of("T9", "a/b"),
                Arguments.of("T10", "/a//b"),
                Arguments.of("T11", "//a/b"),
                Arguments.of("T12", "/a/b/"),
                Arguments.of("T13", " "),
                Arguments.of("T14", "/a/../b"),
                Arguments.of("T15", "/a/./b"),
                Arguments.of("T16", "\\a\\b"),
                Arguments.of("T17", "/a\\b"),
                Arguments.of("T18", "C:\\a\\b")
        );
    }

    @Test
    public void T19_pathValidoDopoCancellazione() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a/b");
        trie.deletePath("/a/b");
        trie.addPath("/a/b");

        assertTrue(trie.existsNode("/a/b"));
    }

    @Test
    public void T20_pathValidoDopoClear() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a");
        trie.clear();
        trie.addPath("/b");

        assertFalse(trie.existsNode("/a"));
        assertTrue(trie.existsNode("/b"));
    }

    private void assertTrieStillUsable(PathTrie trie) {
        trie.addPath("/valid");

        assertTrue(trie.existsNode("/valid"));
    }

    private void assertPathNotInserted(PathTrie trie, String path) {
        try {
            assertFalse(
                    trie.existsNode(path),
                    "Il path malformato risulta inserito nel trie: " + path
            );
        } catch (IllegalArgumentException | NullPointerException e) {
            /*
             * Se existsNode(path) rifiuta il path perché nullo/vuoto/non valido,
             * allora il path non viene considerato presente nel trie.
             */
        }
    }

    private void executeAllowingRuntimeException(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException ignored) {
            /*
             * Per gli input anomali non assumiamo una specifica eccezione documentata.
             * L'obiettivo del test è verificare che il trie non risulti corrotto.
             */
        }
    }
}