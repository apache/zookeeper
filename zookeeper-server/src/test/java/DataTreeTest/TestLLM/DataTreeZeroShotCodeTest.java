package DataTreeTest.TestLLM;


import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


public class DataTreeZeroShotCodeTest {

    private DataTree dataTree;
    private final List<ACL> openAcl = ZooDefs.Ids.OPEN_ACL_UNSAFE;

    @BeforeEach
    public void setup() {
        dataTree = new DataTree();
    }

    @Test
    @DisplayName("Creazione e recupero dati tramite API pubblica")
    public void testCreateAndGetNode() throws Exception {
        String path = "/test-node";
        byte[] data = "payload".getBytes();

        // createNode è public
        dataTree.createNode(path, data, openAcl, 0, -1, 1, 1000L);

        // Uso getData (public) invece di accedere a node.data
        Stat stat = new Stat();
        byte[] retrievedData = dataTree.getData(path, stat, null);

        assertArrayEquals(data, retrievedData);
        assertEquals(1, stat.getCzxid());
    }

    @Test
    @DisplayName("Eliminazione nodo e verifica tramite statNode")
    public void testDeleteNode() throws Exception {
        dataTree.createNode("/parent", "p".getBytes(), openAcl, 0, -1, 1, 1000L);
        dataTree.createNode("/parent/child", "c".getBytes(), openAcl, 0, -1, 2, 1001L);

        dataTree.deleteNode("/parent/child", 3);

        // statNode è public, lancia NoNodeException se il nodo non esiste
        assertThrows(org.apache.zookeeper.KeeperException.NoNodeException.class, () -> {
            dataTree.statNode("/parent/child", null);
        });

        // Verifica che il pzxid del padre sia aggiornato tramite API pubblica
        Stat parentStat = dataTree.statNode("/parent", null);
        assertEquals(3, parentStat.getPzxid());
    }

    @Test
    @DisplayName("Chiusura sessione tramite processTxn (API pubblica)")
    public void testEphemeralCleanup() throws Exception {
        long sessionId = 0xABCDL;
        String path = "/ephemeral-node";

        dataTree.createNode(path, "data".getBytes(), openAcl, sessionId, -1, 1, 1000L);

        // Invece di killSession (package-private), usiamo processTxn con OpCode.closeSession
        TxnHeader header = new TxnHeader(sessionId, 0, 2, 1001L, ZooDefs.OpCode.closeSession);
        dataTree.processTxn(header, null); // Il dispatch interno chiamerà killSession

        assertThrows(org.apache.zookeeper.KeeperException.NoNodeException.class, () -> {
            dataTree.statNode(path, null);
        });
    }


    @Test
    @DisplayName("Conteggio figli tramite API pubblica")
    public void testGetAllChildrenNumber() throws Exception {
        dataTree.createNode("/a", new byte[0], openAcl, 0, -1, 1, 1000L);
        dataTree.createNode("/a/b", new byte[0], openAcl, 0, -1, 2, 1001L);

        // getAllChildrenNumber è public
        assertEquals(1, dataTree.getAllChildrenNumber("/a"));
    }

    @Test
    @DisplayName("Monitoraggio dimensione dati (API pubblica)")
    public void testApproximateDataSize() throws Exception {
        long initialSize = dataTree.cachedApproximateDataSize();
        String path = "/size";
        byte[] data = "123".getBytes();

        dataTree.createNode(path, data, openAcl, 0, -1, 1, 1000L);

        // cachedApproximateDataSize è public
        long expectedSize = initialSize + path.length() + data.length;
        assertEquals(expectedSize, dataTree.cachedApproximateDataSize());
    }
}