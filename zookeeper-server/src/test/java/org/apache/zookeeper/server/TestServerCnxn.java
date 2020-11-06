package org.apache.zookeeper.server;

import org.apache.jute.Record;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.ReplyHeader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;

/**
 * class TestServerCnxn extends ServerCnxn
 * for getting access to NIOServerCnxn.CloseRequestException
 * @see org.apache.zookeeper.server.quorum.ReadOnlyZooKeeperServerTest
 */
public class TestServerCnxn extends ServerCnxn {

    public TestServerCnxn(ZooKeeperServer zkServer) {
        super(zkServer);
    }

    public static boolean instanceofCloseRequestException(Exception e) {
        return e instanceof NIOServerCnxn.CloseRequestException;
    }

    public static ServerCnxn.DisconnectReason getReason(Exception e) {
        return ((NIOServerCnxn.CloseRequestException) e).getReason();
    }

    @Override
    int getSessionTimeout() {
        return 0;
    }

    @Override
    public void close(DisconnectReason reason) {
        
    }

    @Override
    public int sendResponse(ReplyHeader h, Record r, String tag, String cacheKey, Stat stat, int opCode) throws IOException {
        return 0;
    }

    @Override
    public void sendCloseSession() {

    }

    @Override
    public void process(WatchedEvent event) {

    }

    @Override
    public long getSessionId() {
        return 0;
    }

    @Override
    void setSessionId(long sessionId) {

    }

    @Override
    void sendBuffer(ByteBuffer... buffers) {

    }

    @Override
    void enableRecv() {

    }

    @Override
    void disableRecv(boolean waitDisableRecv) {

    }

    @Override
    void setSessionTimeout(int sessionTimeout) {

    }

    @Override
    protected ServerStats serverStats() {
        return null;
    }

    @Override
    public InetSocketAddress getRemoteSocketAddress() {
        return null;
    }

    @Override
    public int getInterestOps() {
        return 0;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public Certificate[] getClientCertificateChain() {
        return new Certificate[0];
    }

    @Override
    public void setClientCertificateChain(Certificate[] chain) {

    }
}
