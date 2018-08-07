package org.apache.zookeeper.server;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SocketUtil {

    private static final Logger LOG = LoggerFactory.getLogger(SocketUtil.class);

    public static final String NETWORK_BUFFER_SIZE = "zookeeper.NetworkBufferSize";
    protected static int networkBufferSize;


    static {
        networkBufferSize = Integer.getInteger(NETWORK_BUFFER_SIZE, -1);
        LOG.info("{} = {}", NETWORK_BUFFER_SIZE, networkBufferSize);
    }

    public static void setNetworkBufferSize(int networkBufferSize) {
        SocketUtil.networkBufferSize = networkBufferSize;
        LOG.info("{} changed to {}", NETWORK_BUFFER_SIZE, SocketUtil.networkBufferSize);
    }

    public static int getNetworkBufferSize() {
        return networkBufferSize;
    }

    /**
     * Set socket parameters. Also log warning message if it is unable to set
     * buffer size to the specified value.
     *
     * @param name - name of socket for log printing
     * @param sock
     * @throws SocketException
     */
    public static void setSocketBufferSize(String name, Socket sock)
            throws SocketException {
        if (networkBufferSize <= 0) {
            return;
        }

        sock.setSendBufferSize(networkBufferSize);
        int size = sock.getSendBufferSize();
        if (size != networkBufferSize) {
            LOG.warn("Unable to set send buffer size on {}, expected = " +
                    "{}, actual = {}", name, networkBufferSize, size);
        }

        sock.setReceiveBufferSize(networkBufferSize);
        size = sock.getReceiveBufferSize();
        if (size != networkBufferSize) {
            LOG.warn("Unable to set receive buffer size on {}, " +
                    "expected = {}, actual = {}", name, networkBufferSize,
                    size);
        }
    }
}
