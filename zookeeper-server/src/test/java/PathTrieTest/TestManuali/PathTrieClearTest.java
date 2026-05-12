package PathTrieTest.TestManuali;

import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class PathTrieClearTest {

    @ParameterizedTest(name = "{0} - clear svuota il trie")
    @MethodSource("clearCases")
    public void T1_T6_clear_svuotaIlTrie(
            String testId,
            String[] pathsToAdd,
            String pathToDelete,
            int clearCalls,
            String[] pathsExpectedAbsent
    ) {
        PathTrie trie = new PathTrie();

        for (String path : pathsToAdd) {
            trie.addPath(path);
        }

        if (pathToDelete != null) {
            trie.deletePath(pathToDelete);
        }

        for (int i = 0; i < clearCalls; i++) {
            trie.clear();
        }

        for (String path : pathsExpectedAbsent) {
            assertFalse(
                    trie.existsNode(path),
                    "Il path " + path + " dovrebbe essere assente nel caso " + testId
            );
        }
    }

    private static Stream<Arguments> clearCases() {
        return Stream.of(
                Arguments.of(
                        "T1",
                        new String[]{},
                        null,
                        1,
                        new String[]{"/a"}
                ),

                Arguments.of(
                        "T2",
                        new String[]{"/a"},
                        null,
                        1,
                        new String[]{"/a"}
                ),

                Arguments.of(
                        "T3",
                        new String[]{"/a/b/c"},
                        null,
                        1,
                        new String[]{"/a/b/c"}
                ),

                Arguments.of(
                        "T4",
                        new String[]{"/a/b", "/x/y"},
                        null,
                        1,
                        new String[]{"/a/b", "/x/y"}
                ),

                Arguments.of(
                        "T5",
                        new String[]{"/a"},
                        "/a",
                        1,
                        new String[]{"/a"}
                ),

                Arguments.of(
                        "T6",
                        new String[]{"/a"},
                        null,
                        2,
                        new String[]{"/a"}
                )
        );
    }

    @Test
    public void T7_trieRiutilizzatoDopoClear() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a");
        trie.clear();
        trie.addPath("/b");

        assertFalse(trie.existsNode("/a"));
        assertTrue(trie.existsNode("/b"));
    }

    @Test
    public void T8_prefixMatchingDopoClear() {
        PathTrie trie = new PathTrie();

        trie.addPath("/a/b");
        trie.clear();

        assertEquals("/", trie.findMaxPrefix("/a/b/c"));
    }
}