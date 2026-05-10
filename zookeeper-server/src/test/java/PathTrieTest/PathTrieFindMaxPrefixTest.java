package PathTrieTest;

import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class PathTrieFindMaxPrefixTest {

    @ParameterizedTest(name = "{0} - findMaxPrefix({4}) == {5}")
    @MethodSource("findMaxPrefixCases")
    public void T1_T13_T15_T25_findMaxPrefix_casiConPathNonNullo(
            String testId,
            String[] pathsToAdd,
            String pathToDelete,
            boolean clearBeforeAssert,
            String queriedPath,
            String expectedPrefix
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
                expectedPrefix,
                trie.findMaxPrefix(queriedPath),
                "Prefisso massimo inatteso per il caso " + testId + " con path interrogato: " + queriedPath
        );
    }

    private static Stream<Arguments> findMaxPrefixCases() {
        return Stream.of(
                Arguments.of(
                        "T1",
                        new String[]{"/a"},
                        null,
                        false,
                        "/a",
                        "/a"
                ),

                Arguments.of(
                        "T2",
                        new String[]{"/a/b/c"},
                        null,
                        false,
                        "/a/b/c",
                        "/a/b/c"
                ),

                Arguments.of(
                        "T3",
                        new String[]{"/a"},
                        null,
                        false,
                        "/a/b",
                        "/a"
                ),

                Arguments.of(
                        "T4",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/a/b/c",
                        "/a/b"
                ),

                Arguments.of(
                        "T5",
                        new String[]{"/a", "/a/b", "/a/b/c"},
                        null,
                        false,
                        "/a/b/c/d",
                        "/a/b/c"
                ),

                Arguments.of(
                        "T6",
                        new String[]{},
                        null,
                        false,
                        "/a",
                        "/"
                ),

                Arguments.of(
                        "T7",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/x/y",
                        "/"
                ),

                Arguments.of(
                        "T8",
                        new String[]{"/a/b", "/x/y"},
                        null,
                        false,
                        "/x/y/z",
                        "/x/y"
                ),

                Arguments.of(
                        "T9",
                        new String[]{"/a"},
                        null,
                        false,
                        "/ab/c",
                        "/"
                ),

                Arguments.of(
                        "T10",
                        new String[]{"/a", "/a/b"},
                        "/a/b",
                        false,
                        "/a/b/c",
                        "/a"
                ),

                Arguments.of(
                        "T11",
                        new String[]{"/a"},
                        null,
                        true,
                        "/a/b",
                        "/"
                ),

                Arguments.of(
                        "T12",
                        new String[]{},
                        null,
                        false,
                        "/",
                        "/"
                ),

                Arguments.of(
                        "T13",
                        new String[]{"/a", "/a/b"},
                        null,
                        false,
                        "/",
                        "/"
                ),

                Arguments.of(
                        "T15",
                        new String[]{},
                        null,
                        false,
                        "",
                        "/"
                ),

                Arguments.of(
                        "T16",
                        new String[]{},
                        null,
                        false,
                        "a/b",
                        "/"
                ),

                Arguments.of(
                        "T17",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/a//b",
                        "/a/b"
                ),

                Arguments.of(
                        "T18",
                        new String[]{},
                        null,
                        false,
                        "//a/b",
                        "/"
                ),

                Arguments.of(
                        "T19",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/a/b/",
                        "/a/b"
                ),

                Arguments.of(
                        "T20",
                        new String[]{},
                        null,
                        false,
                        " ",
                        "/"
                ),

                Arguments.of(
                        "T21",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/a/../b",
                        "/"
                ),

                Arguments.of(
                        "T22",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/a/./b",
                        "/"
                ),

                Arguments.of(
                        "T23",
                        new String[]{},
                        null,
                        false,
                        "\\a\\b",
                        "/"
                ),

                Arguments.of(
                        "T24",
                        new String[]{"/a/b"},
                        null,
                        false,
                        "/a\\b",
                        "/"
                ),

                Arguments.of(
                        "T25",
                        new String[]{},
                        null,
                        false,
                        "C:\\a\\b",
                        "/"
                )
        );
    }

    @Test
    public void T14_pathNullo_trieVuoto_eccezioneEStatoNonCorrotto() {
        PathTrie trie = new PathTrie();

        assertThrows(
                NullPointerException.class,
                () -> trie.findMaxPrefix(null)
        );

        assertTrieStillUsable(trie);
    }

    private void assertTrieStillUsable(PathTrie trie) {
        trie.addPath("/valid");

        assertTrue(trie.existsNode("/valid"));
        assertEquals("/valid", trie.findMaxPrefix("/valid/child"));
    }
}