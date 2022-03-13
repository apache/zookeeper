package org.apache.zookeeper.protocol;

import java.io.IOException;
import org.apache.jute.InputArchive;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;

public class ProtocolManager {
    private volatile Protocol protocol = null;

    public ConnectRequest deserializeConnectRequest(InputArchive inputArchive) throws IOException {
        if (protocol != null) {
            return protocol.deserializeConnectRequest(inputArchive);
        }

        try {
            final ConnectRequest request = DefaultProtocol.INSTANCE.deserializeConnectRequest(inputArchive);
            this.protocol = DefaultProtocol.INSTANCE;
            return request;
        } catch (IOException e) {
            final ConnectRequest request = ZK33Protocol.INSTANCE.deserializeConnectRequest(inputArchive);
            this.protocol = ZK33Protocol.INSTANCE;
            return request;
        }
    }

    public ConnectResponse deserializeConnectResponse(InputArchive inputArchive) throws IOException {
        if (protocol != null) {
            return protocol.deserializeConnectResponse(inputArchive);
        }

        try {
            final ConnectResponse response = DefaultProtocol.INSTANCE.deserializeConnectResponse(inputArchive);
            this.protocol = DefaultProtocol.INSTANCE;
            return response;
        } catch (IOException e) {
            final ConnectResponse response = ZK33Protocol.INSTANCE.deserializeConnectResponse(inputArchive);
            this.protocol = ZK33Protocol.INSTANCE;
            return response;
        }
    }
}
