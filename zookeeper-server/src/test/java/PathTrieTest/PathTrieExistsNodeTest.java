package PathTrieTest;

import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class PathTrieExistsNodeTest {

    @ParameterizedTest(name = "{0} - existsNode({4}) == {5}")
    @MethodSource("existsNodeCases")
    public void T1_T11_T14_T23_existsNode_casiConPathNonNulloENonVuoto(
            String testId,
            String[] pathsToAdd,
            String pathToDelete,
            boolean clearBeforeAssert,
            String queriedPath,
            boolean expected
    ) {
        PathTrie trie = new PathTrie();

        for (String path : pathsToAdd) {
            trie.addPath(path);
        }

        if (pathToDelete != null) {
            trie.deletePath(pathToDelete);
        }

        if (clearBeforeAssert) {
            trie.clear();
        }

        assertEquals(
                expected,
                trie.existsNode(queriedPath),
                "Esito inatteso per il caso " + testId + " con path interrogato: " + queriedPath
        );
    }

    private static Stream<Arguments> existsNodeCases() {
        return Stream.of(
                Arguments.of(
                        "T1",
                        new String[]{"/a"},
                        null,
                        false,
                        "/a",
                        true
                ),

                Arguments.of(
                        "T2",
                        new String[]{},
                        null,
                        false,
                        "/a",
                        false
                ),

                Arguments.of(
                        "T3",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/x/y",
                        false
                ),

                Arguments.of(
                        "T4",
                        new String[]{"/a/b/c"},
                        null,
                        false,
                        "/a/b/c",
                        true
                ),

                Arguments.of(
                        "T5",
                        new String[]{"/a/b/c"},
                        null,
                        false,
                        "/a",
                        true
                ),

                Arguments.of(
                        "T6",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/a/b/c",
                        false
                ),

                Arguments.of(
                        "T7",
                        new String[]{"/a/b", "/x/y"},
                        null,
                        false,
                        "/x/y",
                        true
                ),

                Arguments.of(
                        "T8",
                        new String[]{"/a/b", "/x/y"},
                        "/a/b",
                        false,
                        "/x/y",
                        true
                ),

                Arguments.of(
                        "T9",
                        new String[]{"/a"},
                        "/a",
                        false,
                        "/a",
                        false
                ),

                Arguments.of(
                        "T10",
                        new String[]{"/a"},
                        null,
                        true,
                        "/a",
                        false
                ),

                Arguments.of(
                        "T11",
                        new String[]{},
                        null,
                        false,
                        "/",
                        true
                ),

                Arguments.of(
                        "T14",
                        new String[]{},
                        null,
                        false,
                        "a/b",
                        false
                ),

                Arguments.of(
                        "T15",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/a//b",
                        true
                ),

                Arguments.of(
                        "T16",
                        new String[]{},
                        null,
                        false,
                        "//a/b",
                        false
                ),

                Arguments.of(
                        "T17",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/a/b/",
                        true
                ),

                Arguments.of(
                        "T18",
                        new String[]{},
                        null,
                        false,
                        " ",
                        true
                ),

                Arguments.of(
                        "T19",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/a/../b",
                        false
                ),

                Arguments.of(
                        "T20",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/a/./b",
                        false
                ),

                Arguments.of(
                        "T21",
                        new String[]{},
                        null,
                        false,
                        "\\a\\b",
                        false
                ),

                Arguments.of(
                        "T22",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/a\\b",
                        false
                ),

                Arguments.of(
                        "T23",
                        new String[]{},
                        null,
                        false,
                        "C:\\a\\b",
                        false
                )
        );
    }

    @Test
    public void T12_pathNullo_trieVuoto_eccezioneEStatoNonCorrotto() {
        PathTrie trie = new PathTrie();

        assertThrows(
                NullPointerException.class,
                () -> trie.existsNode(null)
        );

        assertTrieStillUsable(trie);
    }

    @Test
    public void T13_pathVuoto_trieVuoto_eccezioneEStatoNonCorrotto() {
        PathTrie trie = new PathTrie();

        assertThrows(
                IllegalArgumentException.class,
                () -> trie.existsNode("")
        );

        assertTrieStillUsable(trie);
    }

    private void assertTrieStillUsable(PathTrie trie) {
        trie.addPath("/valid");

        assertTrue(trie.existsNode("/valid"));
    }
}
