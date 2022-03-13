package org.apache.zookeeper.protocol;

import java.io.IOException;
import org.apache.jute.InputArchive;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;

public class ProtocolManager {
    private volatile Protocol protocol = null;

    public boolean isZK33Protol() {
        return protocol != null && protocol instanceof ZK33Protocol;
    }

    public ConnectRequest deserializeConnectRequest(InputArchive inputArchive) throws IOException {
        if (protocol != null) {
            return protocol.deserializeConnectRequest(inputArchive);
        }

        final ConnectRequest request = ZK33Protocol.INSTANCE.deserializeConnectRequest(inputArchive);
        try {
            request.setReadOnly(inputArchive.readBool("readOnly"));
            this.protocol = DefaultProtocol.INSTANCE;
            return request;
        } catch (Exception e) {
            request.setReadOnly(false); // old version doesn't have readonly concept
            this.protocol = ZK33Protocol.INSTANCE;
            return request;
        }
    }

    public ConnectResponse deserializeConnectResponse(InputArchive inputArchive) throws IOException {
        if (protocol != null) {
            return protocol.deserializeConnectResponse(inputArchive);
        }

        final ConnectResponse response = ZK33Protocol.INSTANCE.deserializeConnectResponse(inputArchive);
        try {
            response.setReadOnly(inputArchive.readBool("readOnly"));
            this.protocol = DefaultProtocol.INSTANCE;
            return response;
        } catch (Exception e) {
            response.setReadOnly(false); // old version doesn't have readonly concept
            this.protocol = ZK33Protocol.INSTANCE;
            return response;
        }
    }
}
