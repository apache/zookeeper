package DataTreeTest.TestManuali;



import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
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

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeProcessTxnReinforcedTest {

    private DataTree dataTree;

    private static final byte[] OLD_DATA = "old".getBytes();
    private static final byte[] NEW_DATA = "new".getBytes();

    private static final List<ACL> VALID_ACL = ZooDefs.Ids.OPEN_ACL_UNSAFE;
    private static final List<ACL> NEW_ACL = ZooDefs.Ids.READ_ACL_UNSAFE;

    private static final long CLIENT_ID = 1L;
    private static final int CXID = 1;
    private static final long TIME = 100L;

    @BeforeEach
    public void setUp() {
        dataTree = new DataTree();
    }

    private TxnHeader header(int type, long zxid) {
        return new TxnHeader(CLIENT_ID, CXID, zxid, TIME, type);
    }

    private void createNode(String path) throws Exception {
        dataTree.createNode(
                path,
                OLD_DATA,
                VALID_ACL,
                0L,
                -1,
                1L,
                TIME
        );
    }

    private void assertNodeExists(String path) {
        assertNotNull(dataTree.getNode(path), "Il nodo " + path + " dovrebbe esistere");
    }

    private void assertNodeDoesNotExist(String path) {
        assertNull(dataTree.getNode(path), "Il nodo " + path + " non dovrebbe esistere");
    }

    private void assertDataEquals(String path, byte[] expectedData) throws Exception {
        assertArrayEquals(expectedData, dataTree.getData(path, new Stat(), null));
    }

    private String parentPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        String parent = path.substring(0, lastSlash);
        return parent.isEmpty() ? "/" : parent;
    }

    private String childName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void assertParentContainsChild(String path) throws Exception {
        assertTrue(
                dataTree.getChildren(parentPath(path), null, null).contains(childName(path)),
                "Il padre dovrebbe contenere il figlio " + childName(path)
        );
    }

    private void assertParentDoesNotContainChild(String path) throws Exception {
        assertFalse(
                dataTree.getChildren(parentPath(path), null, null).contains(childName(path)),
                "Il padre non dovrebbe contenere il figlio " + childName(path)
        );
    }

    private byte[] serializeRecord(Record record) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive archive = BinaryOutputArchive.getArchive(baos);
        record.serialize(archive, "txn");
        return baos.toByteArray();
    }

    // T1
    @Test
    public void processTxnCreateShouldCreateNodeAndReturnConsistentResult() throws Exception {
        long zxid = 10L;
        CreateTxn txn = new CreateTxn("/a", NEW_DATA, VALID_ACL, false, -1);

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.create, zxid),
                txn,
                false
        );

        assertEquals("/a", rc.path);
        assertEquals(OpCode.create, rc.type);
        assertEquals(zxid, rc.zxid);
        assertEquals(Code.OK.intValue(), rc.err);

        assertNodeExists("/a");
        assertParentContainsChild("/a");
        assertDataEquals("/a", NEW_DATA);
        assertEquals(zxid, dataTree.lastProcessedZxid);

        Stat stat = dataTree.statNode("/a", null);
        assertEquals(zxid, stat.getCzxid());
        assertEquals(zxid, stat.getMzxid());
    }

    // T2
    @Test
    public void processTxnCreateMultilevelShouldCreateChildUnderExistingParent() throws Exception {
        createNode("/a");

        long zxid = 20L;
        CreateTxn txn = new CreateTxn("/a/b", NEW_DATA, VALID_ACL, false, -1);

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.create, zxid),
                txn,
                false
        );

        assertEquals("/a/b", rc.path);
        assertEquals(OpCode.create, rc.type);
        assertEquals(zxid, rc.zxid);
        assertEquals(Code.OK.intValue(), rc.err);

        assertNodeExists("/a");
        assertNodeExists("/a/b");
        assertParentContainsChild("/a/b");
        assertDataEquals("/a/b", NEW_DATA);
    }

    // T3
    @Test
    public void processTxnCreateExistingNodeShouldNotOverwriteState() throws Exception {
        createNode("/a");

        byte[] oldData = dataTree.getData("/a", new Stat(), null);
        Stat oldStat = dataTree.statNode("/a", null);
        int oldNodeCount = dataTree.getNodeCount();

        CreateTxn txn = new CreateTxn("/a", NEW_DATA, NEW_ACL, false, -1);

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.create, 20L),
                txn,
                false
        );

        assertEquals("/a", rc.path);
        assertEquals(OpCode.create, rc.type);
        assertEquals(Code.NODEEXISTS.intValue(), rc.err);

        assertNodeExists("/a");
        assertEquals(oldNodeCount, dataTree.getNodeCount());
        assertArrayEquals(oldData, dataTree.getData("/a", new Stat(), null));

        Stat newStat = dataTree.statNode("/a", null);
        assertEquals(oldStat.getCzxid(), newStat.getCzxid());
        assertEquals(oldStat.getMzxid(), newStat.getMzxid());
        assertEquals(oldStat.getCtime(), newStat.getCtime());
        assertEquals(oldStat.getMtime(), newStat.getMtime());
    }

    // T4
    @Test
    public void processTxnCreateWithoutParentShouldReturnNoNodeAndNotCreateNode() {
        int oldNodeCount = dataTree.getNodeCount();

        CreateTxn txn = new CreateTxn("/a/b", NEW_DATA, VALID_ACL, false, -1);

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.create, 10L),
                txn,
                false
        );

        assertEquals("/a/b", rc.path);
        assertEquals(OpCode.create, rc.type);
        assertEquals(Code.NONODE.intValue(), rc.err);

        assertNodeDoesNotExist("/a");
        assertNodeDoesNotExist("/a/b");
        assertEquals(oldNodeCount, dataTree.getNodeCount());
    }

    // T5
    @Test
    public void processTxnDeleteShouldRemoveNodeAndReturnConsistentResult() throws Exception {
        createNode("/a");

        long zxid = 30L;
        DeleteTxn txn = new DeleteTxn("/a");

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.delete, zxid),
                txn,
                false
        );

        assertEquals("/a", rc.path);
        assertEquals(OpCode.delete, rc.type);
        assertEquals(zxid, rc.zxid);
        assertEquals(Code.OK.intValue(), rc.err);

        assertNodeDoesNotExist("/a");
        assertParentDoesNotContainChild("/a");
        assertEquals(zxid, dataTree.lastProcessedZxid);
    }

    // T6
    @Test
    public void processTxnDeleteAbsentNodeShouldReturnNoNodeAndKeepState() throws Exception {
        createNode("/a");

        int oldNodeCount = dataTree.getNodeCount();
        byte[] oldData = dataTree.getData("/a", new Stat(), null);

        DeleteTxn txn = new DeleteTxn("/x");

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.delete, 30L),
                txn,
                false
        );

        assertEquals("/x", rc.path);
        assertEquals(OpCode.delete, rc.type);
        assertEquals(Code.NONODE.intValue(), rc.err);

        assertNodeExists("/a");
        assertNodeDoesNotExist("/x");
        assertEquals(oldNodeCount, dataTree.getNodeCount());
        assertArrayEquals(oldData, dataTree.getData("/a", new Stat(), null));
    }

    // T7
    @Test
    public void processTxnSetDataShouldUpdateDataAndMetadata() throws Exception {
        createNode("/a");

        long zxid = 40L;
        int version = 5;
        SetDataTxn txn = new SetDataTxn("/a", NEW_DATA, version);

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.setData, zxid),
                txn,
                false
        );

        assertEquals("/a", rc.path);
        assertEquals(OpCode.setData, rc.type);
        assertEquals(zxid, rc.zxid);
        assertEquals(Code.OK.intValue(), rc.err);
        assertNotNull(rc.stat);

        assertDataEquals("/a", NEW_DATA);
        assertEquals(version, rc.stat.getVersion());
        assertEquals(zxid, rc.stat.getMzxid());
        assertEquals(TIME, rc.stat.getMtime());
    }

    // T8
    @Test
    public void processTxnSetDataOnAbsentNodeShouldReturnNoNodeAndKeepState() throws Exception {
        createNode("/a");

        int oldNodeCount = dataTree.getNodeCount();
        byte[] oldData = dataTree.getData("/a", new Stat(), null);

        SetDataTxn txn = new SetDataTxn("/x", NEW_DATA, 1);

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.setData, 40L),
                txn,
                false
        );

        assertEquals("/x", rc.path);
        assertEquals(OpCode.setData, rc.type);
        assertEquals(Code.NONODE.intValue(), rc.err);

        assertNodeDoesNotExist("/x");
        assertEquals(oldNodeCount, dataTree.getNodeCount());
        assertArrayEquals(oldData, dataTree.getData("/a", new Stat(), null));
    }

    // T9
    @Test
    public void processTxnSetAclShouldUpdateAclAndAversion() throws Exception {
        createNode("/a");

        int version = 7;
        SetACLTxn txn = new SetACLTxn("/a", NEW_ACL, version);

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.setACL, 50L),
                txn,
                false
        );

        assertEquals("/a", rc.path);
        assertEquals(OpCode.setACL, rc.type);
        assertEquals(Code.OK.intValue(), rc.err);
        assertNotNull(rc.stat);

        assertEquals(NEW_ACL, dataTree.getACL("/a", new Stat()));
        assertEquals(version, rc.stat.getAversion());
    }

    // T10
    @Test
    public void processTxnSetAclOnAbsentNodeShouldReturnNoNodeAndKeepState() throws Exception {
        createNode("/a");

        List<ACL> oldAcl = dataTree.getACL("/a", new Stat());
        int oldNodeCount = dataTree.getNodeCount();

        SetACLTxn txn = new SetACLTxn("/x", NEW_ACL, 1);

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.setACL, 50L),
                txn,
                false
        );

        assertEquals("/x", rc.path);
        assertEquals(OpCode.setACL, rc.type);
        assertEquals(Code.NONODE.intValue(), rc.err);

        assertNodeDoesNotExist("/x");
        assertEquals(oldNodeCount, dataTree.getNodeCount());
        assertEquals(oldAcl, dataTree.getACL("/a", new Stat()));
    }

    // T11
    @Test
    public void processTxnCheckShouldNotModifyNodeState() throws Exception {
        createNode("/a");

        int oldNodeCount = dataTree.getNodeCount();
        byte[] oldData = dataTree.getData("/a", new Stat(), null);
        Stat oldStat = dataTree.statNode("/a", null);

        CheckVersionTxn txn = new CheckVersionTxn("/a", 0);

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.check, 60L),
                txn,
                false
        );

        assertEquals("/a", rc.path);
        assertEquals(OpCode.check, rc.type);
        assertEquals(Code.OK.intValue(), rc.err);

        assertEquals(oldNodeCount, dataTree.getNodeCount());
        assertArrayEquals(oldData, dataTree.getData("/a", new Stat(), null));

        Stat newStat = dataTree.statNode("/a", null);
        assertEquals(oldStat.getCzxid(), newStat.getCzxid());
        assertEquals(oldStat.getMzxid(), newStat.getMzxid());
        assertEquals(oldStat.getVersion(), newStat.getVersion());
    }

    // T12
    @Test
    public void processTxnErrorShouldReturnErrorCodeAndNotModifyTree() throws Exception {
        createNode("/a");

        int oldNodeCount = dataTree.getNodeCount();
        byte[] oldData = dataTree.getData("/a", new Stat(), null);

        ErrorTxn txn = new ErrorTxn(Code.BADVERSION.intValue());

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.error, 70L),
                txn,
                false
        );

        assertEquals(OpCode.error, rc.type);
        assertEquals(Code.BADVERSION.intValue(), rc.err);

        assertEquals(oldNodeCount, dataTree.getNodeCount());
        assertArrayEquals(oldData, dataTree.getData("/a", new Stat(), null));
    }

    // T13
    @Test
    public void processTxnValidMultiShouldApplyAllSubTransactions() throws Exception {
        createNode("/a");

        Txn createChild = new Txn(
                OpCode.create,
                serializeRecord(new CreateTxn("/a/b", NEW_DATA, VALID_ACL, false, -1))
        );

        Txn setData = new Txn(
                OpCode.setData,
                serializeRecord(new SetDataTxn("/a/b", "multi".getBytes(), 2))
        );

        MultiTxn multiTxn = new MultiTxn(Arrays.asList(createChild, setData));

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.multi, 80L),
                multiTxn,
                false
        );

        assertEquals(OpCode.multi, rc.type);
        assertEquals(Code.OK.intValue(), rc.err);
        assertNotNull(rc.multiResult);
        assertEquals(2, rc.multiResult.size());

        assertNodeExists("/a/b");
        assertParentContainsChild("/a/b");
        assertArrayEquals("multi".getBytes(), dataTree.getData("/a/b", new Stat(), null));
    }

    // T14
    @Test
    public void processTxnMultiWithErrorShouldReturnErrorAndKeepTreeConsistent() throws Exception {
        Txn createNode = new Txn(
                OpCode.create,
                serializeRecord(new CreateTxn("/a", NEW_DATA, VALID_ACL, false, -1))
        );

        Txn errorTxn = new Txn(
                OpCode.error,
                serializeRecord(new ErrorTxn(Code.NONODE.intValue()))
        );

        MultiTxn multiTxn = new MultiTxn(Arrays.asList(createNode, errorTxn));

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.multi, 90L),
                multiTxn,
                false
        );

        assertEquals(OpCode.multi, rc.type);
        assertNotNull(rc.multiResult);
        assertEquals(2, rc.multiResult.size());
        assertNotEquals(Code.OK.intValue(), rc.err);

        assertNodeDoesNotExist("/a");
        assertTrue(dataTree.getNodeCount() >= 0);
    }

    // T15
    @Test
    public void processTxnSubTxnShouldApplyOperationWithoutUpdatingLastProcessedZxid() {
        long oldLastProcessedZxid = dataTree.lastProcessedZxid;

        CreateTxn txn = new CreateTxn("/a", NEW_DATA, VALID_ACL, false, -1);

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.create, 100L),
                txn,
                true
        );

        assertEquals("/a", rc.path);
        assertEquals(OpCode.create, rc.type);
        assertEquals(Code.OK.intValue(), rc.err);

        assertNodeExists("/a");
        assertEquals(oldLastProcessedZxid, dataTree.lastProcessedZxid);
    }

    // T20
    @Test
    public void processTxnCreateWithBorderlineZxidShouldStoreZxidConsistently() throws Exception {
        long zxid = -1L;

        CreateTxn txn = new CreateTxn("/a", NEW_DATA, VALID_ACL, false, -1);

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.create, zxid),
                txn,
                false
        );

        assertEquals("/a", rc.path);
        assertEquals(OpCode.create, rc.type);
        assertEquals(zxid, rc.zxid);
        assertEquals(Code.OK.intValue(), rc.err);

        assertNodeExists("/a");

        Stat stat = dataTree.statNode("/a", null);
        assertEquals(zxid, stat.getCzxid());
        assertEquals(zxid, stat.getMzxid());
    }

    // T23
    @Test
    public void processTxnSetDataOnIndependentBranchShouldModifyOnlyTargetBranch() throws Exception {
        createNode("/a");
        createNode("/x");
        createNode("/x/y");

        byte[] oldDataA = dataTree.getData("/a", new Stat(), null);

        SetDataTxn txn = new SetDataTxn("/x/y", NEW_DATA, 3);

        DataTree.ProcessTxnResult rc = dataTree.processTxn(
                header(OpCode.setData, 160L),
                txn,
                false
        );

        assertEquals("/x/y", rc.path);
        assertEquals(OpCode.setData, rc.type);
        assertEquals(Code.OK.intValue(), rc.err);

        assertArrayEquals(NEW_DATA, dataTree.getData("/x/y", new Stat(), null));
        assertArrayEquals(oldDataA, dataTree.getData("/a", new Stat(), null));

        assertNodeExists("/a");
        assertNodeExists("/x");
        assertNodeExists("/x/y");
    }
}
