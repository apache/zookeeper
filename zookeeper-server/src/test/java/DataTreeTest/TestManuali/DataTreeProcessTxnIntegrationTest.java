package DataTreeTest.TestManuali;

import org.apache.jute.Record;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DataTreeProcessTxnIntegrationTest {

    @Test
    public void processTxnShouldCreateNodeFromTxnHeaderAndCreateTxn() throws Exception {

        DataTree dataTree = new DataTree();

        byte[] data = "test-data".getBytes();

        TxnHeader header = new TxnHeader(
                1L,                 // clientId
                1,                  // cxid
                10L,                // zxid
                1000L,              // time
                OpCode.create       // tipo transazione
        );

        Record txn = new CreateTxn(
                "/a",
                data,
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                false,
                1
        );

        DataTree.ProcessTxnResult result =
                dataTree.processTxn(header, txn, false);

        assertEquals("/a", result.path);
        assertEquals(0, result.err);

        Stat stat = new Stat();
        byte[] storedData = dataTree.getData("/a", stat, null);

        assertArrayEquals(data, storedData);
        assertEquals(10L, stat.getCzxid());
        assertEquals(10L, stat.getMzxid());
        assertEquals(1000L, stat.getCtime());
        assertEquals(1000L, stat.getMtime());
    }
}