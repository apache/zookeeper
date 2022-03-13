package org.apache.zookeeper.protocol;

import java.io.IOException;
import org.apache.jute.InputArchive;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;

public interface Protocol {
    ConnectRequest deserializeConnectRequest(InputArchive inputArchive) throws IOException;
    ConnectResponse deserializeConnectResponse(InputArchive inputArchive) throws IOException;
}
