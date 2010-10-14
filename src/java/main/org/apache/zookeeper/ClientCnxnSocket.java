package org.apache.zookeeper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

import org.apache.jute.BinaryInputArchive;
import org.apache.log4j.Logger;
import org.apache.zookeeper.ClientCnxn.Packet;
import org.apache.zookeeper.proto.ConnectResponse;
import org.apache.zookeeper.server.ByteBufferInputStream;

abstract class ClientCnxnSocket {
    private static final Logger LOG = Logger.getLogger(ClientCnxnSocket.class);

    protected boolean initialized;

    protected final ByteBuffer lenBuffer = ByteBuffer.allocateDirect(4);

    protected ByteBuffer incomingBuffer = lenBuffer;
    protected long sentCount = 0;
    protected long recvCount = 0;
    protected long lastHeard;
    protected long lastSend;
    protected long now;
    protected ClientCnxn.SendThread sendThread;
    protected LinkedList<ClientCnxn.Packet> outgoingQueue;
    /**
     * The sessionId is only available here for Log and Exception messages.
     * Otherwise the socket doesn't know it.
     */
    protected long sessionId;

    void introduce(ClientCnxn.SendThread sendThread,
            LinkedList<ClientCnxn.Packet> outgoingQueue, long sessionId) {
        this.sendThread = sendThread;
        this.outgoingQueue = outgoingQueue;
        this.sessionId = sessionId;
    }

    void updateNow() {
        now = System.currentTimeMillis();
    }

    int getIdleRecv() {
        return (int) (now - lastHeard);
    }

    int getIdleSend() {
        return (int) (now - lastSend);
    }

    long getSentCount() {
        return sentCount;
    }

    long getRecvCount() {
        return recvCount;
    }

    void updateLastHeard() {
        this.lastHeard = now;
    }

    void updateLastSend() {
        this.lastSend = now;
    }

    void updateLastSendAndHeard() {
        this.lastSend = now;
        this.lastHeard = now;
    }

    protected void readLength() throws IOException {
        int len = incomingBuffer.getInt();
        if (len < 0 || len >= ClientCnxn.packetLen) {
            throw new IOException("Packet len" + len + " is out of range!");
        }
        incomingBuffer = ByteBuffer.allocate(len);
    }

    void readConnectResult() throws IOException {
        if (LOG.isTraceEnabled()) {
            StringBuffer buf = new StringBuffer("0x[");
            for (byte b : incomingBuffer.array()) {
                buf.append(Integer.toHexString(b) + ",");
            }
            buf.append("]");
            LOG.trace("readConnectRestult " + incomingBuffer.remaining() + " "
                    + buf.toString());
        }
        ByteBufferInputStream bbis = new ByteBufferInputStream(incomingBuffer);
        BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
        ConnectResponse conRsp = new ConnectResponse();
        conRsp.deserialize(bbia, "connect");
        this.sessionId = conRsp.getSessionId();
        sendThread.onConnected(conRsp.getTimeOut(), this.sessionId,
                conRsp.getPasswd());
    }

    abstract boolean isConnected();

    abstract void connect(InetSocketAddress addr) throws IOException;

    abstract SocketAddress getRemoteSocketAddress();

    abstract SocketAddress getLocalSocketAddress();

    abstract void cleanup();

    abstract void close();

    abstract void wakeupCnxn();

    abstract void enableWrite();

    abstract void enableReadWriteOnly();

    abstract void doTransport(int waitTimeOut, List<Packet> pendingQueue)
            throws IOException;

    abstract void testableCloseSocket() throws IOException;
}
