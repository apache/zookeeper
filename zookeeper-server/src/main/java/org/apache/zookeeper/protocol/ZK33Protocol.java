package org.apache.zookeeper.protocol;

import java.io.IOException;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;

/**
 * ZooKeeper 3.3 and earlier doesn't handle ReadOnly field of {@link ConnectRequest} and {@link ConnectResponse}.
 */
public class ZK33Protocol implements Protocol {
    @Override
    public ConnectRequest deserializeConnectRequest(InputArchive inputArchive) throws IOException {
        final ConnectRequest request = new ConnectRequest();
        inputArchive.startRecord("connect");
        request.setProtocolVersion(inputArchive.readInt("protocolVersion"));
        request.setLastZxidSeen(inputArchive.readLong("lastZxidSeen"));
        request.setTimeOut(inputArchive.readInt("timeOut"));
        request.setSessionId(inputArchive.readLong("sessionId"));
        request.setPasswd(inputArchive.readBuffer("passwd"));
        inputArchive.endRecord("connect");
        return request;
    }

    @Override
    public ConnectResponse deserializeConnectResponse(InputArchive inputArchive) throws IOException {
        final ConnectResponse response = new ConnectResponse();
        inputArchive.startRecord("connect");
        response.setProtocolVersion(inputArchive.readInt("protocolVersion"));
        response.setTimeOut(inputArchive.readInt("timeOut"));
        response.setSessionId(inputArchive.readLong("sessionId"));
        response.setPasswd(inputArchive.readBuffer("passwd"));
        response.setReadOnly(false); // old version doesn't have readonly concept
        inputArchive.endRecord("connect");
        return response;
    }

    @Override
    public void serializeConnectRequest(OutputArchive outputArchive, ConnectRequest connectRequest) throws IOException {
        outputArchive.startRecord(connectRequest, "connect");
        outputArchive.writeInt(connectRequest.getProtocolVersion(), "protocolVersion");
        outputArchive.writeLong(connectRequest.getLastZxidSeen(), "lastZxidSeen");
        outputArchive.writeInt(connectRequest.getTimeOut(), "timeOut");
        outputArchive.writeLong(connectRequest.getSessionId(), "sessionId");
        outputArchive.writeBuffer(connectRequest.getPasswd(), "passwd");
        outputArchive.endRecord(connectRequest, "connect");
    }

    @Override
    public void serializeConnectResponse(OutputArchive outputArchive, ConnectResponse connectResponse) throws IOException {
        outputArchive.startRecord(connectResponse, "connect");
        outputArchive.writeInt(connectResponse.getProtocolVersion(),"protocolVersion");
        outputArchive.writeInt(connectResponse.getTimeOut(),"timeOut");
        outputArchive.writeLong(connectResponse.getSessionId(),"sessionId");
        outputArchive.writeBuffer(connectResponse.getPasswd(),"passwd");
        outputArchive.endRecord(connectResponse, "connect");
    }
}
