package org.apache.zookeeper.protocol;

import java.io.IOException;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;

public interface Protocol {
    ConnectRequest deserializeConnectRequest(InputArchive inputArchive) throws IOException;
    ConnectResponse deserializeConnectResponse(InputArchive inputArchive) throws IOException;
    void serializeConnectRequest(OutputArchive outputArchive, ConnectRequest connectRequest) throws IOException;
    void serializeConnectResponse(OutputArchive outputArchive, ConnectResponse connectResponse) throws IOException;
}
