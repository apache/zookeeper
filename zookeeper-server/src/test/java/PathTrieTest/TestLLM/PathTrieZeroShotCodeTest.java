package PathTrieTest.TestLLM;

import org.apache.zookeeper.common.PathTrie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite di test JUnit5 per la classe PathTrie di Apache ZooKeeper.
 * Verifica in modo completo l'inserimento, l'eliminazione, l'esistenza,
 * la ricerca del prefisso massimo e la pulizia dell'albero dei percorsi.
 */
public class PathTrieZeroShotCodeTest {

    private PathTrie pathTrie;

    @BeforeEach
    public void setUp() {
        pathTrie = new PathTrie();
    }

    // ========================================================================
    // TEST ADD PATH & EXISTS NODE
    // ========================================================================

    @Test
    public void testAddPath_And_ExistsNode_Success() {
        pathTrie.addPath("/nodeA/nodeB");

        // Verifica che il percorso completo esista
        assertTrue(pathTrie.existsNode("/nodeA/nodeB"), "Il nodo inserito dovrebbe esistere");

        // Verifica che anche il nodo intermedio esista nella struttura (sebbene senza property 'true')
        assertTrue(pathTrie.existsNode("/nodeA"), "Il nodo intermedio dovrebbe esistere nella struttura");
    }

    @Test
    public void testExistsNode_NonExistentPath_ReturnsFalse() {
        pathTrie.addPath("/nodeA/nodeB");

        assertFalse(pathTrie.existsNode("/nodeA/nodeC"), "Un nodo non inserito dovrebbe restituire false");
        assertFalse(pathTrie.existsNode("/randomPath"), "Un percorso inesistente dovrebbe restituire false");
    }

    @Test
    public void testAddPath_NullOrEmpty_ThrowsException() {
        assertThrows(NullPointerException.class, () -> pathTrie.addPath(null), "Aggiungere un path null lancia NPE");
        assertThrows(IllegalArgumentException.class, () -> pathTrie.addPath(""), "Aggiungere un path vuoto lancia IAE");
    }

    @Test
    public void testExistsNode_NullOrEmpty_ThrowsException() {
        assertThrows(NullPointerException.class, () -> pathTrie.existsNode(null));
        assertThrows(IllegalArgumentException.class, () -> pathTrie.existsNode(""));
    }

    // ========================================================================
    // TEST FIND MAX PREFIX
    // ========================================================================

    @Test
    public void testFindMaxPrefix_EmptyTrie_ReturnsRoot() {
        assertEquals("/", pathTrie.findMaxPrefix("/any/path"), "In un trie vuoto, il prefisso massimo è la radice");
    }

    @Test
    public void testFindMaxPrefix_ExactMatch() {
        pathTrie.addPath("/app/config");
        assertEquals("/app/config", pathTrie.findMaxPrefix("/app/config"), "Deve restituire il percorso esatto se matcha");
    }

    @Test
    public void testFindMaxPrefix_DeeperPath_ReturnsStoredPrefix() {
        pathTrie.addPath("/app/config");
        // Cerchiamo un figlio di un percorso esistente
        assertEquals("/app/config", pathTrie.findMaxPrefix("/app/config/database/url"));
    }

    @Test
    public void testFindMaxPrefix_IntermediateNode_ReturnsRoot() {
        pathTrie.addPath("/app/config/db");
        // /app/config è stato creato come nodo intermedio (property = false), non come percorso terminante
        assertEquals("/", pathTrie.findMaxPrefix("/app/config"));
    }

    @Test
    public void testFindMaxPrefix_MultipleOverlappingPaths() {
        pathTrie.addPath("/app");
        pathTrie.addPath("/app/config");

        assertEquals("/app", pathTrie.findMaxPrefix("/app/logs"));
        assertEquals("/app/config", pathTrie.findMaxPrefix("/app/config/cache"));
    }

    @Test
    public void testFindMaxPrefix_Null_ThrowsException() {
        assertThrows(NullPointerException.class, () -> pathTrie.findMaxPrefix(null));
    }

    // ========================================================================
    // TEST DELETE PATH
    // ========================================================================

    @Test
    public void testDeletePath_LeafNode_RemovesNodeSuccessfully() {
        pathTrie.addPath("/app/config");
        assertTrue(pathTrie.existsNode("/app/config"));

        pathTrie.deletePath("/app/config");

        // Essendo un nodo foglia, dovrebbe essere rimosso dai figli del genitore
        assertFalse(pathTrie.existsNode("/app/config"), "Il nodo foglia dovrebbe essere stato rimosso");
        assertEquals("/", pathTrie.findMaxPrefix("/app/config"));
    }

    @Test
    public void testDeletePath_IntermediateNodeWithChildren_RemovesPropertyOnly() {
        pathTrie.addPath("/app");
        pathTrie.addPath("/app/config");

        // Eliminiamo il nodo intermedio che però è stato esplicitamente aggiunto
        pathTrie.deletePath("/app");

        // Il nodo esiste ancora per ospitare '/app/config'
        assertTrue(pathTrie.existsNode("/app/config"), "Il nodo figlio deve rimanere intatto");
        assertTrue(pathTrie.existsNode("/app"), "Il nodo fisico deve esistere per il routing");

        // MA il prefisso massimo per un percorso sotto /app (che non sia /app/config) ora ricade sulla radice
        assertEquals("/", pathTrie.findMaxPrefix("/app/logs"), "La property 'app' deve essere stata revocata");
        assertEquals("/app/config", pathTrie.findMaxPrefix("/app/config/db"));
    }

    @Test
    public void testDeletePath_NonExistentPath_ReturnsWithoutError() {
        pathTrie.addPath("/app");
        assertDoesNotThrow(() -> pathTrie.deletePath("/non/existent/path"), "Eliminare un path inesistente non deve fallire");
    }

    @Test
    public void testDeletePath_NullOrEmpty_ThrowsException() {
        assertThrows(NullPointerException.class, () -> pathTrie.deletePath(null));
        assertThrows(IllegalArgumentException.class, () -> pathTrie.deletePath(""));
    }

    @Test
    public void testDeletePath_RootPath_ThrowsNullPointerException() {
        // Come visto nel codice sorgente:
        // split("/") restituisce un array vuoto.
        // Il ciclo for non viene eseguito. parent rimane rootNode.
        // rootNode.getParent() restituisce null.
        // chiamare deleteChild su null lancia NullPointerException.
        assertThrows(NullPointerException.class, () -> pathTrie.deletePath("/"),
                "Eliminare la radice non è supportato dall'implementazione e genera NPE");
    }

    // ========================================================================
    // TEST CLEAR
    // ========================================================================

    @Test
    public void testClear_RemovesAllChildren() {
        pathTrie.addPath("/node1");
        pathTrie.addPath("/node2/child");

        assertTrue(pathTrie.existsNode("/node1"));
        assertTrue(pathTrie.existsNode("/node2/child"));

        pathTrie.clear();

        assertFalse(pathTrie.existsNode("/node1"), "Il nodo 1 non deve più esistere");
        assertFalse(pathTrie.existsNode("/node2"), "Il nodo 2 non deve più esistere");
        assertEquals("/", pathTrie.findMaxPrefix("/node1"));
    }
}