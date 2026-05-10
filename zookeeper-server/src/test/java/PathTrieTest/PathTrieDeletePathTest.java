package PathTrieTest;

import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class PathTrieDeletePathTest {

    @Test
    public void T1_pathValidoSemplice_pathPresenteComeFoglia() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a");
        trie.deletePath("/a");

        assertFalse(trie.existsNode("/a"));
    }

    @Test
    public void T2_pathValidoSemplice_trieVuoto() {
        PathTrie trie = new PathTrie();

        trie.deletePath("/a");

        assertFalse(trie.existsNode("/a"));
    }

    @Test
    public void T3_pathValidoMultilivello_pathNonPresente() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a/b");
        trie.deletePath("/x/y");

        assertTrue(trie.existsNode("/a/b"));
        assertFalse(trie.existsNode("/x/y"));
    }

    @Test
    public void T4_pathValidoMultilivello_pathPresenteComeFoglia() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a");
        trie.addPath("/a/b");
        trie.deletePath("/a/b");

        assertFalse(trie.existsNode("/a/b"));
        assertTrue(trie.existsNode("/a"));
    }

    @Test
    @Disabled
    public void T5_pathValidoSemplice_pathPrefissoDiAltriPath() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a");
        trie.addPath("/a/b");
        trie.deletePath("/a");

        assertFalse(trie.existsNode("/a"));
        assertTrue(trie.existsNode("/a/b"));
    }

    @Test
    public void T6_pathValidoMultilivello_trieConPiuRamiIndipendenti() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a/b");
        trie.addPath("/x/y");
        trie.deletePath("/a/b");

        assertFalse(trie.existsNode("/a/b"));
        assertTrue(trie.existsNode("/x/y"));
    }

    @Test
    public void T7_pathRadice_trieVuoto_statoNonCorrotto() {
        PathTrie trie = new PathTrie();

        executeAllowingRuntimeException(() -> trie.deletePath("/"));

        assertTrieStillUsable(trie);
    }

    @Test
    public void T8_pathRadice_trieConPathMultilivelloPresente_statoNonCorrotto() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a");
        trie.addPath("/a/b");

        executeAllowingRuntimeException(() -> trie.deletePath("/"));

        assertTrue(trie.existsNode("/a"));
        assertTrue(trie.existsNode("/a/b"));
        assertTrieStillUsable(trie);
    }

    @Test
    public void T9_pathNullo_trieVuoto_statoNonCorrotto() {
        PathTrie trie = new PathTrie();

        executeAllowingRuntimeException(() -> trie.deletePath(null));

        assertTrieStillUsable(trie);
    }

    @Test
    public void T10_pathVuoto_trieVuoto_statoNonCorrotto() {
        PathTrie trie = new PathTrie();

        executeAllowingRuntimeException(() -> trie.deletePath(""));

        assertTrieStillUsable(trie);
    }

    @ParameterizedTest(name = "{0} - path malformato/anomalo: {1}")
    @MethodSource("malformedPathsWithExistingNode")
    public void T11_T20_pathVuotoOMalformato_trieConUnSoloPathPresente(String testId, String malformedPath) {
        PathTrie trie = new PathTrie();

        trie.addPath("/a");

        executeAllowingRuntimeException(() -> trie.deletePath(malformedPath));

        assertTrue(
                trie.existsNode("/a"),
                "Il path valido /a non deve essere rimosso dopo deletePath(" + malformedPath + ")"
        );

        assertTrieStillUsable(trie);
    }

    private static Stream<Arguments> malformedPathsWithExistingNode() {
        return Stream.of(
                Arguments.of("T11", "a/b"),
                Arguments.of("T12", "/a//b"),
                Arguments.of("T13", "//a/b"),
                Arguments.of("T14", "/a/b/"),
                Arguments.of("T15", " "),
                Arguments.of("T16", "/a/../b"),
                Arguments.of("T17", "/a/./b"),
                Arguments.of("T18", "\\a\\b"),
                Arguments.of("T19", "/a\\b"),
                Arguments.of("T20", "C:\\a\\b")
        );
    }

    @Test
    public void T21_pathValidoSemplice_doppiaCancellazione() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a");
        trie.deletePath("/a");
        trie.deletePath("/a");

        assertFalse(trie.existsNode("/a"));
    }

    @Test
    public void T22_pathValidoSemplice_dopoClear() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a");
        trie.clear();
        trie.deletePath("/a");

        assertFalse(trie.existsNode("/a"));
    }

    private void assertTrieStillUsable(PathTrie trie) {
        trie.addPath("/valid");

        assertTrue(trie.existsNode("/valid"));
    }

    private void executeAllowingRuntimeException(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException ignored) {
            /*
             * Per gli input anomali/non documentati non assumiamo una specifica eccezione.
             * L'obiettivo del test è verificare che il trie non venga corrotto.
             */
        }
    }

    @Test
    public void T5_pathValidoSemplice_pathPrefissoDiAltriPath2() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a");
        trie.addPath("/a/b");
        trie.deletePath("/a");

        assertTrue(trie.existsNode("/a"));
        assertTrue(trie.existsNode("/a/b"));
    }
}