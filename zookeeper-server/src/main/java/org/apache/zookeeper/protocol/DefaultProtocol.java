package org.apache.zookeeper.protocol;

import java.io.IOException;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;

public class DefaultProtocol implements Protocol {
    @Override
    public ConnectRequest deserializeConnectRequest(InputArchive inputArchive) throws IOException {
        final ConnectRequest request = new ConnectRequest();
        request.deserialize(inputArchive, "connect");
        return request;
    }

    @Override
    public ConnectResponse deserializeConnectResponse(InputArchive inputArchive) throws IOException {
        final ConnectResponse response = new ConnectResponse();
        response.deserialize(inputArchive, "connect");
        return response;
    }

    @Override
    public void serializeConnectRequest(OutputArchive outputArchive, ConnectRequest connectRequest) throws IOException {
        connectRequest.serialize(outputArchive, "connect");
    }

    @Override
    public void serializeConnectResponse(OutputArchive outputArchive, ConnectResponse connectResponse) throws IOException {
        connectResponse.serialize(outputArchive, "connect");
    }
}
