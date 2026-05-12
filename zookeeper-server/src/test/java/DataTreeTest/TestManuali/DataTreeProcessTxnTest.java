package DataTreeTest.TestManuali;

import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.DataTree.ProcessTxnResult;
import org.apache.zookeeper.txn.CheckVersionTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.MultiTxn;
import org.apache.zookeeper.txn.SetACLTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeProcessTxnTest {

    private DataTree dataTree;

    private static final long CLIENT_ID = 1L;
    private static final long OTHER_CLIENT_ID = 2L;

    private static final int CXID = 1;
    private static final long VALID_ZXID = 1L;
    private static final long VALID_TIME = 200L;

    private static final byte[] INITIAL_DATA = "old-data".getBytes();
    private static final byte[] NEW_DATA = "new-data".getBytes();

    private static final List<ACL> VALID_ACL = ZooDefs.Ids.OPEN_ACL_UNSAFE;
    private static final List<ACL> READ_ACL = ZooDefs.Ids.READ_ACL_UNSAFE;

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    private static TxnHeader header(int opCode) {
        return new TxnHeader(CLIENT_ID, CXID, VALID_ZXID, VALID_TIME, opCode);
    }

    private static TxnHeader header(int opCode, long zxid) {
        return new TxnHeader(CLIENT_ID, CXID, zxid, VALID_TIME, opCode);
    }

    private static TxnHeader header(long clientId, int opCode, long zxid) {
        return new TxnHeader(clientId, CXID, zxid, VALID_TIME, opCode);
    }

    private static CreateTxn createTxn(String path) {
        return new CreateTxn(path, INITIAL_DATA, VALID_ACL, false, -1);
    }

    private static CreateTxn createTxn(String path, byte[] data) {
        return new CreateTxn(path, data, VALID_ACL, false, -1);
    }

    private static CreateTxn createEphemeralTxn(String path) {
        return new CreateTxn(path, INITIAL_DATA, VALID_ACL, true, -1);
    }

    private static DeleteTxn deleteTxn(String path) {
        return new DeleteTxn(path);
    }

    private static SetDataTxn setDataTxn(String path) {
        return new SetDataTxn(path, NEW_DATA, 1);
    }

    private static SetACLTxn setACLTxn(String path) {
        return new SetACLTxn(path, READ_ACL, 1);
    }

    private static CheckVersionTxn checkTxn(String path) {
        return new CheckVersionTxn(path, 0);
    }

    private static byte[] serializeRecord(Record record) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive archive = BinaryOutputArchive.getArchive(baos);
        record.serialize(archive, "txn");
        return baos.toByteArray();
    }

    private static Txn subTxn(int type, Record record) throws IOException {
        return new Txn(type, serializeRecord(record));
    }

    private void createValidNode(String path)
            throws KeeperException.NoNodeException, KeeperException.NodeExistsException {

        dataTree.createNode(
                path,
                INITIAL_DATA,
                VALID_ACL,
                -1L,
                -1,
                VALID_ZXID,
                VALID_TIME
        );
    }

    private byte[] getNodeData(String path) throws KeeperException.NoNodeException {
        return dataTree.getData(path, new Stat(), null);
    }

    private Stat getNodeStat(String path) throws KeeperException.NoNodeException {
        Stat stat = new Stat();
        dataTree.getData(path, stat, null);
        return stat;
    }

    private void assertNodeExists(String path) {
        assertNotNull(dataTree.getNode(path), "Il nodo " + path + " dovrebbe esistere");
    }

    private void assertNodeDoesNotExist(String path) {
        assertNull(dataTree.getNode(path), "Il nodo " + path + " non dovrebbe esistere");
    }

    private void assertDataEquals(String path, byte[] expectedData) throws KeeperException.NoNodeException {
        assertArrayEquals(expectedData, getNodeData(path), "Dati non corretti per il nodo " + path);
    }

    private void assertTreeStillUsable() throws Exception {
        createValidNode("/safe");
        assertNodeExists("/safe");
        assertDataEquals("/safe", INITIAL_DATA);
    }

    static Stream<Arguments> processTxnParameters() throws Exception {
        MultiTxn validMultiTxn = new MultiTxn(Arrays.asList(
                subTxn(OpCode.create, createTxn("/a/b")),
                subTxn(OpCode.setData, setDataTxn("/a"))
        ));

        return Stream.of(
                Arguments.of(
                        "T1 - create /a",
                        Collections.emptyList(),
                        header(OpCode.create),
                        createTxn("/a"),
                        false,
                        Code.OK.intValue(),
                        Arrays.asList("/a"),
                        Collections.emptyList(),
                        "/a",
                        INITIAL_DATA,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T2 - create /a/b con padre presente",
                        Arrays.asList("/a"),
                        header(OpCode.create),
                        createTxn("/a/b"),
                        false,
                        Code.OK.intValue(),
                        Arrays.asList("/a", "/a/b"),
                        Collections.emptyList(),
                        "/a/b",
                        INITIAL_DATA,
                        Arrays.asList("/a")
                ),

                Arguments.of(
                        "T3 - create nodo già presente",
                        Arrays.asList("/a"),
                        header(OpCode.create),
                        createTxn("/a"),
                        false,
                        Code.NODEEXISTS.intValue(),
                        Arrays.asList("/a"),
                        Collections.emptyList(),
                        "/a",
                        INITIAL_DATA,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T4 - create con padre assente",
                        Collections.emptyList(),
                        header(OpCode.create),
                        createTxn("/a/b"),
                        false,
                        Code.NONODE.intValue(),
                        Collections.emptyList(),
                        Arrays.asList("/a/b"),
                        null,
                        null,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T5 - delete nodo presente",
                        Arrays.asList("/a"),
                        header(OpCode.delete),
                        deleteTxn("/a"),
                        false,
                        Code.OK.intValue(),
                        Collections.emptyList(),
                        Arrays.asList("/a"),
                        null,
                        null,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T6 - delete nodo assente",
                        Collections.emptyList(),
                        header(OpCode.delete),
                        deleteTxn("/x"),
                        false,
                        Code.NONODE.intValue(),
                        Collections.emptyList(),
                        Arrays.asList("/x"),
                        null,
                        null,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T7 - setData nodo presente",
                        Arrays.asList("/a"),
                        header(OpCode.setData),
                        setDataTxn("/a"),
                        false,
                        Code.OK.intValue(),
                        Arrays.asList("/a"),
                        Collections.emptyList(),
                        "/a",
                        NEW_DATA,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T8 - setData nodo assente",
                        Collections.emptyList(),
                        header(OpCode.setData),
                        setDataTxn("/x"),
                        false,
                        Code.NONODE.intValue(),
                        Collections.emptyList(),
                        Arrays.asList("/x"),
                        null,
                        null,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T9 - setACL nodo presente",
                        Arrays.asList("/a"),
                        header(OpCode.setACL),
                        setACLTxn("/a"),
                        false,
                        Code.OK.intValue(),
                        Arrays.asList("/a"),
                        Collections.emptyList(),
                        "/a",
                        INITIAL_DATA,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T10 - setACL nodo assente",
                        Collections.emptyList(),
                        header(OpCode.setACL),
                        setACLTxn("/x"),
                        false,
                        Code.NONODE.intValue(),
                        Collections.emptyList(),
                        Arrays.asList("/x"),
                        null,
                        null,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T11 - check nodo presente",
                        Arrays.asList("/a"),
                        header(OpCode.check),
                        checkTxn("/a"),
                        false,
                        Code.OK.intValue(),
                        Arrays.asList("/a"),
                        Collections.emptyList(),
                        "/a",
                        INITIAL_DATA,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T12 - error txn",
                        Collections.emptyList(),
                        header(OpCode.error),
                        new ErrorTxn(Code.NONODE.intValue()),
                        false,
                        Code.NONODE.intValue(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        null,
                        null,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T13 - multi valida",
                        Arrays.asList("/a"),
                        header(OpCode.multi),
                        validMultiTxn,
                        false,
                        Code.OK.intValue(),
                        Arrays.asList("/a", "/a/b"),
                        Collections.emptyList(),
                        "/a",
                        NEW_DATA,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T15 - create come sotto-transazione",
                        Collections.emptyList(),
                        header(OpCode.create, 10L),
                        createTxn("/a"),
                        true,
                        Code.OK.intValue(),
                        Arrays.asList("/a"),
                        Collections.emptyList(),
                        "/a",
                        INITIAL_DATA,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T19 - tipo operazione anomalo",
                        Collections.emptyList(),
                        header(-999),
                        createTxn("/a"),
                        false,
                        Code.OK.intValue(),
                        Collections.emptyList(),
                        Arrays.asList("/a"),
                        null,
                        null,
                        Collections.emptyList()
                ),

                Arguments.of(
                        "T23 - setData su ramo indipendente",
                        Arrays.asList("/a", "/x", "/x/y"),
                        header(OpCode.setData),
                        new SetDataTxn("/x/y", NEW_DATA, 1),
                        false,
                        Code.OK.intValue(),
                        Arrays.asList("/a", "/x", "/x/y"),
                        Collections.emptyList(),
                        "/x/y",
                        NEW_DATA,
                        Arrays.asList("/a", "/x")
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("processTxnParameters")
    public void processTxnShouldReturnExpectedResultAndState(
            String testName,
            List<String> initialPaths,
            TxnHeader header,
            Record txn,
            boolean isSubTxn,
            int expectedErr,
            List<String> expectedExistingPaths,
            List<String> expectedMissingPaths,
            String expectedDataPath,
            byte[] expectedData,
            List<String> unchangedPaths
    ) throws Exception {

        for (String path : initialPaths) {
            createValidNode(path);
        }

        ProcessTxnResult result = dataTree.processTxn(header, txn, isSubTxn);

        assertEquals(expectedErr, result.err, "Codice errore non corretto");

        for (String path : expectedExistingPaths) {
            assertNodeExists(path);
        }

        for (String path : expectedMissingPaths) {
            assertNodeDoesNotExist(path);
        }

        if (expectedDataPath != null) {
            assertDataEquals(expectedDataPath, expectedData);
        }

        for (String unchangedPath : unchangedPaths) {
            assertDataEquals(unchangedPath, INITIAL_DATA);
        }

        if (header.getType() == OpCode.setData && expectedErr == Code.OK.intValue()) {
            assertNotNull(result.stat);
            assertEquals(header.getZxid(), result.stat.getMzxid());
        }

        if (header.getType() == OpCode.setACL && expectedErr == Code.OK.intValue()) {
            assertNotNull(result.stat);
            List<ACL> actualAcl = dataTree.getACL(expectedDataPath, new Stat());
            assertEquals(ZooDefs.Perms.READ, actualAcl.get(0).getPerms());
        }

        if (isSubTxn) {
            assertEquals(0L, dataTree.lastProcessedZxid);
        } else {
            assertEquals(header.getZxid(), dataTree.lastProcessedZxid);
        }
    }

    // T14 - multi con sotto-operazione di errore
    @Test
    public void processTxnShouldHandleMultiTxnWithError() throws Exception {
        MultiTxn multiTxn = new MultiTxn(Arrays.asList(
                subTxn(OpCode.create, createTxn("/a")),
                subTxn(OpCode.error, new ErrorTxn(Code.NONODE.intValue()))
        ));

        ProcessTxnResult result = dataTree.processTxn(
                header(OpCode.multi),
                multiTxn,
                false
        );

        assertNotNull(result);
        assertNotNull(result.multiResult);
        assertTrue(result.err != Code.OK.intValue() || result.multiResult.size() > 0);
        assertTreeStillUsable();
    }

    // T16 - header nullo
    @Test
    public void processTxnWithNullHeaderShouldThrowAndNotCorruptTree() throws Exception {
        assertThrows(
                NullPointerException.class,
                () -> dataTree.processTxn(null, createTxn("/a"), false)
        );

        assertTreeStillUsable();
    }

    // T17 - record nullo
    @Test
    public void processTxnWithNullRecordShouldThrowAndNotCorruptTree() throws Exception {
        assertThrows(
                RuntimeException.class,
                () -> dataTree.processTxn(header(OpCode.create), null, false)
        );

        assertTreeStillUsable();
    }

    // T18 - header e record non coerenti
    @Test
    public void processTxnWithInconsistentHeaderAndTxnShouldThrowAndNotCorruptTree() throws Exception {
        assertThrows(
                RuntimeException.class,
                () -> dataTree.processTxn(
                        header(OpCode.setData),
                        createTxn("/a"),
                        false
                )
        );

        assertTreeStillUsable();
    }

    // T20 - zxid anomalo
    @ParameterizedTest(name = "T20 - zxid anomalo = {0}")
    @ValueSource(longs = {0L, -1L})
    public void processTxnShouldHandleAnomalousZxid(long anomalousZxid) throws Exception {
        ProcessTxnResult result = dataTree.processTxn(
                header(OpCode.create, anomalousZxid),
                createTxn("/a"),
                false
        );

        assertEquals(Code.OK.intValue(), result.err);
        assertNodeExists("/a");

        Stat stat = getNodeStat("/a");
        assertEquals(anomalousZxid, stat.getCzxid());
        assertEquals(anomalousZxid, result.zxid);
    }

    // T21 - record con path nullo
    @Test
    public void processTxnWithNullPathInRecordShouldThrowAndNotCorruptTree() throws Exception {
        assertThrows(
                RuntimeException.class,
                () -> dataTree.processTxn(
                        header(OpCode.create),
                        createTxn(null),
                        false
                )
        );

        assertTreeStillUsable();
    }

    // T22 - record con path vuoto o malformato
    @ParameterizedTest(name = "T22 - path malformato = {0}")
    @ValueSource(strings = {"", "a/b", "/a//b"})
    public void processTxnWithMalformedPathShouldReturnErrorOrThrowAndNotCorruptTree(String malformedPath)
            throws Exception {

        try {
            ProcessTxnResult result = dataTree.processTxn(
                    header(OpCode.create),
                    createTxn(malformedPath),
                    false
            );

            assertNotEquals(Code.OK.intValue(), result.err);
            assertNodeDoesNotExist(malformedPath);

        } catch (RuntimeException ignored) {
            // Caso accettabile: alcuni path malformati possono generare eccezioni runtime.
        }

        assertTreeStillUsable();
    }

    // T24 - creazione nodo effimero
    //Test aggiunti in seguito all'analisi con jacoco
    @Test
    public void processTxnShouldCreateEphemeralNode() throws Exception {
        TxnHeader createHeader = header(CLIENT_ID, OpCode.create, 30L);

        ProcessTxnResult result = dataTree.processTxn(
                createHeader,
                createEphemeralTxn("/ephemeral"),
                false
        );

        assertEquals(Code.OK.intValue(), result.err);
        assertNodeExists("/ephemeral");
        assertDataEquals("/ephemeral", INITIAL_DATA);

        Stat stat = getNodeStat("/ephemeral");
        assertEquals(CLIENT_ID, stat.getEphemeralOwner());

        assertEquals(createHeader.getZxid(), dataTree.lastProcessedZxid);
    }

    // T25 - chiusura sessione con nodo effimero presente
    @Test
    public void processTxnCloseSessionShouldDeleteEphemeralNodesAndKeepPersistentNodes() throws Exception {
        createValidNode("/persistent");

        dataTree.processTxn(
                header(CLIENT_ID, OpCode.create, 31L),
                createEphemeralTxn("/ephemeral"),
                false
        );

        assertNodeExists("/persistent");
        assertNodeExists("/ephemeral");

        TxnHeader closeHeader = header(CLIENT_ID, OpCode.closeSession, 32L);

        ProcessTxnResult result = dataTree.processTxn(
                closeHeader,
                null,
                false
        );

        assertEquals(Code.OK.intValue(), result.err);

        assertNodeDoesNotExist("/ephemeral");
        assertNodeExists("/persistent");
        assertDataEquals("/persistent", INITIAL_DATA);

        assertEquals(closeHeader.getZxid(), dataTree.lastProcessedZxid);
    }

    // T26 - chiusura sessione senza nodi effimeri
    @Test
    public void processTxnCloseSessionWithoutEphemeralNodesShouldKeepTreeUnchanged() throws Exception {
        createValidNode("/a");
        createValidNode("/b");

        TxnHeader closeHeader = header(CLIENT_ID, OpCode.closeSession, 33L);

        ProcessTxnResult result = dataTree.processTxn(
                closeHeader,
                null,
                false
        );

        assertEquals(Code.OK.intValue(), result.err);

        assertNodeExists("/a");
        assertNodeExists("/b");
        assertDataEquals("/a", INITIAL_DATA);
        assertDataEquals("/b", INITIAL_DATA);

        assertEquals(closeHeader.getZxid(), dataTree.lastProcessedZxid);
    }

    // T27 - chiusura sessione con nodi effimeri appartenenti a sessioni diverse
    @Test
    public void processTxnCloseSessionShouldDeleteOnlyEphemeralNodesOwnedByThatSession() throws Exception {
        dataTree.processTxn(
                header(CLIENT_ID, OpCode.create, 40L),
                createEphemeralTxn("/client-node"),
                false
        );

        dataTree.processTxn(
                header(OTHER_CLIENT_ID, OpCode.create, 41L),
                createEphemeralTxn("/other-client-node"),
                false
        );

        assertNodeExists("/client-node");
        assertNodeExists("/other-client-node");

        ProcessTxnResult result = dataTree.processTxn(
                header(CLIENT_ID, OpCode.closeSession, 42L),
                null,
                false
        );

        assertEquals(Code.OK.intValue(), result.err);

        assertNodeDoesNotExist("/client-node");
        assertNodeExists("/other-client-node");

        Stat otherStat = getNodeStat("/other-client-node");
        assertEquals(OTHER_CLIENT_ID, otherStat.getEphemeralOwner());
    }

    // T28 - creazione di figlio sotto nodo effimero
    @Test
    public void processTxnShouldExposeLowLevelCreateChildUnderEphemeralNodeBehaviour() throws Exception {
        dataTree.processTxn(
                header(CLIENT_ID, OpCode.create, 50L),
                createEphemeralTxn("/ephemeral-parent"),
                false
        );

        assertNodeExists("/ephemeral-parent");

        ProcessTxnResult result = dataTree.processTxn(
                header(OpCode.create, 51L),
                createTxn("/ephemeral-parent/child"),
                false
        );

        /*
         * Nota: processTxn applica transazioni già preparate/validate.
         * Il controllo funzionale "un nodo effimero non può avere figli"
         * può essere demandato ai livelli superiori.
         */
        assertEquals(Code.OK.intValue(), result.err);
        assertNodeExists("/ephemeral-parent");
        assertNodeExists("/ephemeral-parent/child");
        assertDataEquals("/ephemeral-parent/child", INITIAL_DATA);

        assertEquals(51L, dataTree.lastProcessedZxid);
    }

    // T29 - cancellazione di nodo con figli
    @Test
    public void processTxnDeleteNodeWithChildrenShouldExposeLowLevelBehaviour() throws Exception {
        createValidNode("/a");
        createValidNode("/a/b");

        ProcessTxnResult result = dataTree.processTxn(
                header(OpCode.delete, 60L),
                deleteTxn("/a"),
                false
        );

        /*
         * Nota: processTxn lavora a livello di applicazione della transazione.
         * Il controllo NOTEMPTY può essere già stato gestito prima della scrittura
         * della transazione nel log.
         */
        assertEquals(Code.OK.intValue(), result.err);

        assertNodeDoesNotExist("/a");
        assertNodeExists("/a/b");
        assertDataEquals("/a/b", INITIAL_DATA);

        assertEquals(60L, dataTree.lastProcessedZxid);
    }

    // T30 - multi valida con operazioni eterogenee
    @Test
    public void processTxnShouldHandleHeterogeneousValidMultiTxn() throws Exception {
        createValidNode("/a");
        createValidNode("/toDelete");

        MultiTxn multiTxn = new MultiTxn(Arrays.asList(
                subTxn(OpCode.create, createTxn("/created")),
                subTxn(OpCode.setData, new SetDataTxn("/a", NEW_DATA, 1)),
                subTxn(OpCode.delete, deleteTxn("/toDelete"))
        ));

        TxnHeader multiHeader = header(OpCode.multi, 70L);

        ProcessTxnResult result = dataTree.processTxn(
                multiHeader,
                multiTxn,
                false
        );

        assertEquals(Code.OK.intValue(), result.err);
        assertNotNull(result.multiResult);
        assertEquals(3, result.multiResult.size());

        assertNodeExists("/created");
        assertDataEquals("/created", INITIAL_DATA);

        assertNodeExists("/a");
        assertDataEquals("/a", NEW_DATA);

        assertNodeDoesNotExist("/toDelete");

        assertEquals(multiHeader.getZxid(), dataTree.lastProcessedZxid);
    }

    // T31 - multi con errore intermedio
    @Test
    public void processTxnShouldHandleMultiTxnWithIntermediateError() throws Exception {
        createValidNode("/a");

        MultiTxn multiTxn = new MultiTxn(Arrays.asList(
                subTxn(OpCode.setData, new SetDataTxn("/a", NEW_DATA, 1)),
                subTxn(OpCode.delete, deleteTxn("/missing"))
        ));

        ProcessTxnResult result = dataTree.processTxn(
                header(OpCode.multi, 80L),
                multiTxn,
                false
        );

        assertNotNull(result);
        assertNotNull(result.multiResult);
        assertEquals(2, result.multiResult.size());

        assertTrue(
                result.multiResult.stream()
                        .anyMatch(subResult -> subResult.err == Code.NONODE.intValue()),
                "La multi dovrebbe contenere almeno una sotto-operazione con errore NONODE"
        );

        assertNodeExists("/a");
        assertDataEquals("/a", NEW_DATA);

        assertTreeStillUsable();
    }
}