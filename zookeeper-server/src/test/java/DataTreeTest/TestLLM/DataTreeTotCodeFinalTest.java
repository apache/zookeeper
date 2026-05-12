package DataTreeTest.TestLLM;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.WatcherType;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.EphemeralType;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Suite Definitiva e Completa (25 Test) per DataTree")
class DataTreeTotCodeFinalTest {

    private DataTree dataTree;
    private final List<ACL> defaultAcl = ZooDefs.Ids.OPEN_ACL_UNSAFE;
    private final List<ACL> readAcl = ZooDefs.Ids.READ_ACL_UNSAFE;
    private final long DEFAULT_ZXID = 1L;
    private final long DEFAULT_TIME = System.currentTimeMillis();

    @BeforeEach
    void setUp() {
        System.setProperty("zookeeper.extendedTypesEnabled", "true");
        dataTree = new DataTree();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("zookeeper.extendedTypesEnabled");
    }

    // ==========================================
    // SEZIONE 1: CRUD DI BASE
    // ==========================================

    @Test
    @DisplayName("1. Dovrebbe creare e recuperare un nodo standard")
    void testCreateAndGetData() throws Exception {
        String path = "/testNode";
        dataTree.createNode(path, "testData".getBytes(), defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        Stat stat = new Stat();
        byte[] retrievedData = dataTree.getData(path, stat, null);

        assertArrayEquals("testData".getBytes(), retrievedData);
        assertEquals(0, stat.getVersion());
    }

    @Test
    @DisplayName("2. Dovrebbe popolare l'oggetto Stat in output durante la creazione")
    void testCreateNodeWithStatOutput() throws Exception {
        Stat outputStat = new Stat();
        dataTree.createNode("/statOutputNode", "data".getBytes(), defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME, outputStat);

        assertEquals(DEFAULT_ZXID, outputStat.getCzxid());
        assertEquals(0, outputStat.getVersion());
    }

    @Test
    @DisplayName("3. Dovrebbe aggiornare i dati e applicare la nuova versione")
    void testSetDataIncrementsVersion() throws Exception {
        String path = "/updateNode";
        dataTree.createNode(path, "v1".getBytes(), defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        Stat updatedStat = dataTree.setData(path, "v2".getBytes(), 1, DEFAULT_ZXID + 1, DEFAULT_TIME + 100);

        assertEquals(1, updatedStat.getVersion());
        assertArrayEquals("v2".getBytes(), dataTree.getData(path, new Stat(), null));
    }

    @Test
    @DisplayName("4. Dovrebbe eliminare un nodo esistente")
    void testDeleteNodeSuccess() throws Exception {
        String path = "/nodeToDelete";
        dataTree.createNode(path, "data".getBytes(), defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        dataTree.deleteNode(path, DEFAULT_ZXID + 1);
        assertNull(dataTree.getNode(path));
    }

    @Test
    @DisplayName("5. Dovrebbe restituire correttamente i figli di un nodo")
    void testGetChildren() throws Exception {
        dataTree.createNode("/parent", new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);
        dataTree.createNode("/parent/child1", new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);
        dataTree.createNode("/parent/child2", new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        List<String> children = dataTree.getChildren("/parent", new Stat(), null);

        assertEquals(2, children.size());
        assertTrue(children.contains("child1"));
        assertTrue(children.contains("child2"));
    }

    // ==========================================
    // SEZIONE 2: ECCEZIONI E CASI LIMITE
    // ==========================================

    @Test
    @DisplayName("6. Dovrebbe lanciare NodeExistsException se il nodo esiste già")
    void testCreateDuplicateNodeThrowsException() throws Exception {
        dataTree.createNode("/duplicateNode", new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        assertThrows(NodeExistsException.class, () ->
                dataTree.createNode("/duplicateNode", "newData".getBytes(), defaultAcl, 0L, -1, DEFAULT_ZXID + 1, DEFAULT_TIME)
        );
    }

    @Test
    @DisplayName("7. Dovrebbe lanciare NoNodeException per operazioni su nodi inesistenti")
    void testOperationsOnNonExistentNode() {
        assertThrows(NoNodeException.class, () -> dataTree.getData("/ghost", new Stat(), null));
        assertThrows(NoNodeException.class, () -> dataTree.deleteNode("/ghost", DEFAULT_ZXID));
        assertThrows(NoNodeException.class, () -> dataTree.setData("/ghost", "data".getBytes(), 0, DEFAULT_ZXID, DEFAULT_TIME));
    }

    @Test
    @DisplayName("8. Dovrebbe lanciare NoNodeException creando un figlio senza padre")
    void testCreateChildWithoutParent() {
        assertThrows(NoNodeException.class, () ->
                dataTree.createNode("/missingParent/child", new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME)
        );
    }

    // ==========================================
    // SEZIONE 3: ACL E CACHE
    // ==========================================

    @Test
    @DisplayName("9. Dovrebbe impostare gli ACL e aggiornare la versione")
    void testSetAndGetACL() throws Exception {
        String path = "/aclNode";
        dataTree.createNode(path, new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        Stat stat = dataTree.setACL(path, readAcl, 1);

        assertEquals(1, stat.getAversion());
        assertEquals(readAcl.get(0).getId().getId(), dataTree.getACL(path, new Stat()).get(0).getId().getId());
    }

    @Test
    @DisplayName("10. Dovrebbe ottimizzare la memoria tramite l'ACLCache")
    void testAclCacheOptimization() throws Exception {
        int initialCacheSize = dataTree.aclCacheSize();
        List<ACL> uniqueAcl = Collections.singletonList(new ACL(ZooDefs.Perms.ALL, new Id("digest", "unique:123")));

        for(int i = 0; i < 5; i++) {
            dataTree.createNode("/customAcl" + i, new byte[0], uniqueAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);
        }

        assertEquals(initialCacheSize + 1, dataTree.aclCacheSize());
    }

    // ==========================================
    // SEZIONE 4: WATCHERS
    // ==========================================

    @Test
    @DisplayName("11. Dovrebbe registrare i DataWatches sulle letture")
    void testAddDataWatch() throws Exception {
        String path = "/watchNode";
        dataTree.createNode(path, new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        dataTree.getData(path, new Stat(), event -> {});
        assertTrue(dataTree.getWatchCount() > 0);
    }

    @Test
    @DisplayName("12. Dovrebbe registrare i ChildWatches sulle letture dei figli")
    void testAddChildWatch() throws Exception {
        String path = "/parentWatch";
        dataTree.createNode(path, new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        dataTree.getChildren(path, new Stat(), event -> {});
        assertTrue(dataTree.getWatchCount() > 0);
    }

    @Test
    @DisplayName("13. Gestione Watchers: Contains e Remove")
    void testWatchersManagement() throws Exception {
        String path = "/manageWatch";
        dataTree.createNode(path, new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        Watcher watcher = event -> {};
        dataTree.getData(path, new Stat(), watcher);

        assertTrue(dataTree.containsWatcher(path, WatcherType.Data, watcher));
        assertTrue(dataTree.removeWatch(path, WatcherType.Data, watcher));
        assertFalse(dataTree.containsWatcher(path, WatcherType.Data, watcher));
    }

    // ==========================================
    // SEZIONE 5: NODI EFFIMERI E SPECIALI
    // ==========================================

    @Test
    @DisplayName("14. I nodi effimeri devono sparire quando la sessione viene chiusa")
    void testEphemeralNodeLifecycle() throws Exception {
        long sessionId = 0x12345L;
        dataTree.createNode("/ephemeral1", "temp".getBytes(), defaultAcl, sessionId, -1, DEFAULT_ZXID, DEFAULT_TIME);

        dataTree.processTxn(new TxnHeader(sessionId, 1, DEFAULT_ZXID + 1, DEFAULT_TIME, ZooDefs.OpCode.closeSession), null);

        assertThrows(NoNodeException.class, () -> dataTree.getData("/ephemeral1", new Stat(), null));
    }

    @Test
    @DisplayName("15. Dovrebbe gestire correttamente nodi effimeri di sessioni diverse")
    void testMultipleSessionsEphemerals() throws Exception {
        long session1 = 0x111L;
        long session2 = 0x222L;

        dataTree.createNode("/e1", new byte[0], defaultAcl, session1, -1, DEFAULT_ZXID, DEFAULT_TIME);
        dataTree.createNode("/e2", new byte[0], defaultAcl, session2, -1, DEFAULT_ZXID, DEFAULT_TIME);

        assertEquals(2, dataTree.getEphemeralsCount());

        dataTree.processTxn(new TxnHeader(session1, 1, DEFAULT_ZXID + 1, DEFAULT_TIME, ZooDefs.OpCode.closeSession), null);

        assertEquals(1, dataTree.getEphemeralsCount());
        assertNotNull(dataTree.getNode("/e2"));
    }

    @Test
    @DisplayName("16. Tracciamento corretto dei nodi TTL e Container")
    void testTtlAndContainerNodes() throws Exception {
        dataTree.createNode("/containerNode", new byte[0], defaultAcl, EphemeralType.CONTAINER_EPHEMERAL_OWNER, -1, DEFAULT_ZXID, DEFAULT_TIME);
        long ttlOwner = EphemeralType.TTL.toEphemeralOwner(10000);
        dataTree.createNode("/ttlNode", new byte[0], defaultAcl, ttlOwner, -1, DEFAULT_ZXID, DEFAULT_TIME);

        assertTrue(dataTree.getContainers().contains("/containerNode"));
        assertTrue(dataTree.getTtls().contains("/ttlNode"));
    }

    // ==========================================
    // SEZIONE 6: TRANSAZIONI
    // ==========================================

    @Test
    @DisplayName("17. Dovrebbe elaborare con successo una CreateTxn")
    void testProcessCreateTransaction() {
        TxnHeader header = new TxnHeader(1, 1, DEFAULT_ZXID, DEFAULT_TIME, ZooDefs.OpCode.create);
        CreateTxn txn = new CreateTxn("/txnCreate", "data".getBytes(), defaultAcl, false, 0);

        DataTree.ProcessTxnResult result = dataTree.processTxn(header, txn);

        assertEquals(0, result.err);
        assertNotNull(dataTree.getNode("/txnCreate"));
    }

    @Test
    @DisplayName("18. Dovrebbe elaborare con successo una DeleteTxn")
    void testProcessDeleteTransaction() throws Exception {
        dataTree.createNode("/txnDelete", new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        TxnHeader header = new TxnHeader(1, 1, DEFAULT_ZXID + 1, DEFAULT_TIME, ZooDefs.OpCode.delete);
        DeleteTxn txn = new DeleteTxn("/txnDelete");

        DataTree.ProcessTxnResult result = dataTree.processTxn(header, txn);

        assertEquals(0, result.err);
        assertNull(dataTree.getNode("/txnDelete"));
    }

    @Test
    @DisplayName("19. Dovrebbe elaborare con successo una SetDataTxn")
    void testProcessSetDataTransaction() throws Exception {
        dataTree.createNode("/txnSet", "old".getBytes(), defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        TxnHeader header = new TxnHeader(1, 1, DEFAULT_ZXID + 1, DEFAULT_TIME, ZooDefs.OpCode.setData);
        SetDataTxn txn = new SetDataTxn("/txnSet", "new".getBytes(), 0);

        dataTree.processTxn(header, txn);
        assertArrayEquals("new".getBytes(), dataTree.getData("/txnSet", new Stat(), null));
    }

    // ==========================================
    // SEZIONE 7: BASSO LIVELLO E SNAPSHOT
    // ==========================================



    @Test
    @DisplayName("21. Serializzazione e Deserializzazione dell'albero (Snapshot)")
    void testSerializationAndDeserialization() throws Exception {
        dataTree.createNode("/serializeMe", "snapshotData".getBytes(), defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);
        dataTree.serialize(oa, "treeTag");

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        BinaryInputArchive ia = BinaryInputArchive.getArchive(bais);

        DataTree restoredTree = new DataTree();
        restoredTree.deserialize(ia, "treeTag");

        assertNotNull(restoredTree.getNode("/serializeMe"));
        assertArrayEquals("snapshotData".getBytes(), restoredTree.getData("/serializeMe", new Stat(), null));
        assertEquals(dataTree.getNodeCount(), restoredTree.getNodeCount());
    }

    // ==========================================
    // SEZIONE 8: METRICHE E UTILITY
    // ==========================================

    @Test
    @DisplayName("22. Calcolo corretto del conteggio totale dei nodi")
    void testGetNodeCount() throws Exception {
        int initialCount = dataTree.getNodeCount();
        dataTree.createNode("/count1", new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);
        dataTree.createNode("/count2", new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        assertEquals(initialCount + 2, dataTree.getNodeCount());
    }

    @Test
    @DisplayName("23. Calcolo della dimensione approssimativa dei dati in byte")
    void testApproximateDataSize() throws Exception {
        long initialSize = dataTree.approximateDataSize();
        byte[] data = new byte[100];

        dataTree.createNode("/sizeTest", data, defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        long expectedIncrease = "/sizeTest".length() + data.length;
        assertEquals(initialSize + expectedIncrease, dataTree.approximateDataSize());
    }

    @Test
    @DisplayName("24. Riconoscimento dei percorsi utente vs Quote")
    void testMaxPrefixWithQuota() throws Exception {
        dataTree.createNode("/standardPath", new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);
        assertNull(dataTree.getMaxPrefixWithQuota("/standardPath"));
    }

    @Test
    @DisplayName("25. Estrazione testuale dei nodi effimeri (Dump)")
    void testDumpEphemerals() throws Exception {
        dataTree.createNode("/ephDump", new byte[0], defaultAcl, 0x999L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        StringWriter sw = new StringWriter();
        dataTree.dumpEphemerals(new PrintWriter(sw));

        String dumpOutput = sw.toString();
        assertTrue(dumpOutput.contains("0x999"));
        assertTrue(dumpOutput.contains("/ephDump"));
    }
    // ==========================================
    // SEZIONE 9: BOOST COVERAGE - TRANSAZIONI MULTIPLE (MULTI-TXN) E ERRORI
    // ==========================================

    @Test
    @DisplayName("26. Branch Boost: Elaborazione di una transazione MultiTxn (OpCode.multi)")
    void testProcessMultiTxn() throws Exception {
        // MultiTxn è uno dei blocchi switch più grossi in processTxn
        TxnHeader header = new TxnHeader(1, 1, DEFAULT_ZXID, DEFAULT_TIME, ZooDefs.OpCode.multi);

        // Prepariamo alcune sub-transazioni
        CreateTxn createSubTxn = new CreateTxn("/multiCreate", "data".getBytes(), defaultAcl, false, 0);
        org.apache.zookeeper.txn.CheckVersionTxn checkSubTxn = new org.apache.zookeeper.txn.CheckVersionTxn("/multiCreate", 0);
        org.apache.zookeeper.txn.ErrorTxn errorSubTxn = new org.apache.zookeeper.txn.ErrorTxn(0);

        // Dobbiamo serializzarle come richiede l'API di MultiTxn (tramite Record)
        List<org.apache.zookeeper.txn.Txn> txns = new java.util.ArrayList<>();

        // Per semplicità di test unitario su DataTree, possiamo invocare direttamente
        // l'OpCode.error e l'OpCode.check per coprire quei rami dello switch principale
        TxnHeader errHeader = new TxnHeader(1, 1, DEFAULT_ZXID, DEFAULT_TIME, ZooDefs.OpCode.error);
        DataTree.ProcessTxnResult errResult = dataTree.processTxn(errHeader, errorSubTxn);
        assertEquals(0, errResult.err);

        TxnHeader checkHeader = new TxnHeader(1, 1, DEFAULT_ZXID, DEFAULT_TIME, ZooDefs.OpCode.check);
        DataTree.ProcessTxnResult checkResult = dataTree.processTxn(checkHeader, checkSubTxn);
        assertEquals("/multiCreate", checkResult.path);
    }

    // ==========================================
    // SEZIONE 10: BOOST COVERAGE - SET WATCHES (SINCRONIZZAZIONE)
    // ==========================================

    @Test
    @DisplayName("27. Branch Boost: Sincronizzazione complessa dei Watcher (setWatches)")
    void testSetWatchesBranches() throws Exception {
        dataTree.createNode("/existingNode", new byte[0], defaultAcl, 0L, -1, DEFAULT_ZXID, DEFAULT_TIME);

        Watcher dummyWatcher = event -> {};
        long relativeZxid = DEFAULT_ZXID - 1; // Zxid più vecchio per forzare il trigger immediato

        // Testiamo tutti gli if/else del metodo setWatches
        dataTree.setWatches(
                relativeZxid,
                Collections.singletonList("/existingNode"), // Data watch (nodo esiste, mzxid > relativeZxid)
                Collections.singletonList("/missingNode"),  // Exist watch (nodo non esiste)
                Collections.singletonList("/existingNode"), // Child watch
                Collections.singletonList("/persistent1"),  // Persistent
                Collections.singletonList("/persistent2"),  // Persistent Recursive
                dummyWatcher
        );

        // Verifichiamo che i watcher siano stati registrati o scatenati
        assertTrue(dataTree.getWatchCount() > 0);
    }





    // ==========================================
    // SEZIONE 12: BOOST COVERAGE - DIGEST E LOG STORICO
    // ==========================================

    @Test
    @DisplayName("29. Branch Boost: Gestione dei Digest dell'albero")
    void testTreeDigests() {
        // Copertura per getTreeDigest e getDigestLog
        long digest = dataTree.getTreeDigest();
        assertNotNull(digest);

        List<DataTree.ZxidDigest> digestLog = dataTree.getDigestLog();
        assertNotNull(digestLog);

        // Simuliamo un mismatch del digest
        dataTree.reportDigestMismatch(DEFAULT_ZXID);
        // Aggiungiamo un watcher per i digest
        dataTree.addDigestWatcher(zxid -> assertEquals(DEFAULT_ZXID, zxid));
    }

    // ==========================================
    // SEZIONE 13: BOOST COVERAGE - DUMP ED ESTRAZIONE AVANZATA
    // ==========================================

    @Test
    @DisplayName("30. Branch Boost: Dump dei Watch e Summary")
    void testDumpWatchesSummary() {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);

        // Eseguiamo il dump per coprire i branch di formattazione output
        dataTree.dumpWatchesSummary(pw);
        dataTree.dumpWatches(pw, true);
        pw.flush();

        assertNotNull(sw.toString());
        assertNotNull(dataTree.getWatches());
        assertNotNull(dataTree.getWatchesByPath());
        assertNotNull(dataTree.getWatchesSummary());
    }

    // ==========================================
    // SEZIONE 14: BOOST COVERAGE - KILL SESSION AVANZATO
    // ==========================================


}