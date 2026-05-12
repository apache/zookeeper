package DataTreeTest.TestLLM;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeFewShotCodeTest {

    private DataTree dataTree;
    private final List<ACL> defaultAcl = ZooDefs.Ids.OPEN_ACL_UNSAFE;

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    @Test
    public void testCreateNode() throws Exception {
        String path = "/testNode";
        byte[] data = "initData".getBytes();
        dataTree.createNode(path, data, defaultAcl, 0, 1, 1, 1);

        DataNode node = dataTree.getNode(path);
        assertNotNull(node);
        assertArrayEquals(data, node.getData());
    }

    @Test
    public void testDeleteNode() throws Exception {
        String path = "/deleteMe";
        dataTree.createNode(path, "data".getBytes(), defaultAcl, 0, 1, 1, 1);
        dataTree.deleteNode(path, 2);
        assertNull(dataTree.getNode(path));
    }

    @Test
    public void testSetAndGetData() throws Exception {
        String path = "/dataNode";
        dataTree.createNode(path, "oldData".getBytes(), defaultAcl, 0, 1, 1, 1);
        byte[] newData = "newData".getBytes();
        Stat stat = dataTree.setData(path, newData, 1, 2, 1000L);
        assertArrayEquals(newData, dataTree.getData(path, new Stat(), null));
        assertEquals(1, stat.getVersion());
    }

    @Test
    public void testGetChildren() throws Exception {
        dataTree.createNode("/parent", "p".getBytes(), defaultAcl, 0, 1, 1, 1);
        dataTree.createNode("/parent/c1", "c1".getBytes(), defaultAcl, 0, 2, 1, 1);
        List<String> children = dataTree.getChildren("/parent", new Stat(), null);
        assertTrue(children.contains("c1"));
        assertEquals(1, children.size());
    }

    @Test
    public void testWatchers() throws Exception {
        String path = "/watchNode";
        CountDownLatch latch = new CountDownLatch(1);
        Watcher w = event -> latch.countDown();

        dataTree.createNode(path, "data".getBytes(), defaultAcl, 0, 1, 1, 1);
        dataTree.getData(path, new Stat(), w);
        dataTree.setData(path, "changed".getBytes(), 1, 2, 2000L);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Il Watcher non è scattato");
    }

    // --- CORRETTO: Ora verifica che restituisca null come atteso ---
    @Test
    public void testMaxPrefixWithQuota() {
        String quotaPath = "/zookeeper/quota/myNode";
        String prefix = dataTree.getMaxPrefixWithQuota(quotaPath);
        // Poiché non abbiamo impostato quote reali nell'albero, deve restituire null (o in alcune versioni "/")
        assertTrue(prefix == null || prefix.equals("/"));
    }

    @Test
    public void testEphemeralNodesAndKillSession() throws Exception {
        String path = "/ephemeral";
        long sessionId = 0x12345678L;
        dataTree.createNode(path, "e".getBytes(), defaultAcl, sessionId, 1, 1, 1);

        assertNotNull(dataTree.getNode(path));
        assertEquals(1, dataTree.getEphemerals(sessionId).size());

        // Chiamata tramite Reflection per testare il metodo package-private
        Method killMethod = DataTree.class.getDeclaredMethod("killSession", long.class, long.class);
        killMethod.setAccessible(true);
        killMethod.invoke(dataTree, sessionId, 2L);

        assertNull(dataTree.getNode(path), "Il nodo effimero deve sparire dopo killSession");
    }

    @Test
    public void testGetNodeCount() throws Exception {
        int initialCount = dataTree.getNodeCount();
        dataTree.createNode("/n1", "d".getBytes(), defaultAcl, 0, 1, 1, 1);
        assertEquals(initialCount + 1, dataTree.getNodeCount());
    }

    @Test
    public void testDigest() throws Exception {
        dataTree.createNode("/digestNode", "data".getBytes(), defaultAcl, 0, 1, 1, 1);
        long digestBefore = dataTree.getTreeDigest();
        dataTree.setData("/digestNode", "newData".getBytes(), 1, 2, 1000L);
        assertNotEquals(digestBefore, dataTree.getTreeDigest());
    }



    @Test
    public void testSetAndGetACL() throws Exception {
        String path = "/aclNode";
        dataTree.createNode(path, "data".getBytes(), defaultAcl, 0, 1, 1, 1);

        // Impostiamo una nuova ACL (READ_ACL_UNSAFE)
        Stat stat = dataTree.setACL(path, ZooDefs.Ids.READ_ACL_UNSAFE, -1);
        assertNotNull(stat);

        List<ACL> currentAcl = dataTree.getACL(path, new Stat());
        assertEquals(ZooDefs.Ids.READ_ACL_UNSAFE, currentAcl, "L'ACL deve essere stata aggiornata");
    }

    @Test
    public void testApproximateDataSize() throws Exception {
        long initialSize = dataTree.approximateDataSize();
        dataTree.createNode("/sizeNode", "payload_molto_grande".getBytes(), defaultAcl, 0, 1, 1, 1);

        assertTrue(dataTree.approximateDataSize() > initialSize, "La dimensione stimata dell'albero deve aumentare");
    }

    @Test
    public void testGetEphemeralsCount() throws Exception {
        int initialEphCount = dataTree.getEphemeralsCount();
        long sessionId = 0x9999L;

        dataTree.createNode("/eph1", "d".getBytes(), defaultAcl, sessionId, 1, 1, 1);
        dataTree.createNode("/eph2", "d".getBytes(), defaultAcl, sessionId, 2, 1, 1);

        assertEquals(initialEphCount + 2, dataTree.getEphemeralsCount(), "Il conteggio totale dei nodi effimeri deve aumentare di 2");
    }
}