package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.server.*;

import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * test ReadOnlyZooKeeperServer
 */
public class ReadOnlyZooKeeperServerTest {

    /**
     * test method {@link ZooKeeperServer#processConnectRequest(org.apache.zookeeper.server.ServerCnxn, java.nio.ByteBuffer)}
     */
    @Test
    public void testReadOnlyZookeeperServer() {
        ReadOnlyZooKeeperServer readOnlyZooKeeperServer = new ReadOnlyZooKeeperServer(
                mock(FileTxnSnapLog.class), mock(QuorumPeer.class), mock(ZKDatabase.class));

        final ByteBuffer output = ByteBuffer.allocate(30);
        // serialize a connReq
        output.putInt(1);
        output.putLong(1L);
        output.putInt(500);
        output.putLong(123L);
        output.putInt(1);
        output.put((byte)1);
        // set readOnly false
        output.put((byte)0);
        output.flip();

        try {
            final NIOServerCnxn nioServerCnxn = mock(NIOServerCnxn.class);
            readOnlyZooKeeperServer.processConnectRequest(nioServerCnxn, output);
        } catch (Exception e) {
            // expect
            assertTrue(TestServerCnxn.instanceofCloseRequestException(e));
            assertEquals(TestServerCnxn.getReason(e), ServerCnxn.DisconnectReason.NOT_READ_ONLY_CLIENT);
        }
    }


}
