package org.apache.zookeeper.faaskeeper;

import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Future;

/**
 * Abstract base class for all provider-agnostic operations submitted to FK instance.
 */
public abstract class Operation {
    protected String sessionId;
    protected String path;

    public Operation(String sessionId, String path) {
        this.sessionId = sessionId;
        this.path = path;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getPath() {
        return path;
    }

    public abstract String getName();
}

/**
 * Base class for all operations submitted to FK work queue.
 */
abstract class RequestOperation extends Operation {
    public RequestOperation(String sessionId, String path) {
        super(sessionId, path);
    }

    public RequestOperation(Map<String, Object> data) {
        super((String) data.get("sessionId"), (String) data.get("path"));
    }

    public abstract Map<String, Object> generateRequest();

    public boolean isCloudRequest() {
        return true;
    }
}

class CreateNode extends RequestOperation {
    private byte[] value;
    private int flags;
    public CreateNode(String sessionId, String path, byte[] value, int flags) {
        super(sessionId, path);
        this.value = value;
        this.flags = flags;
    }

    public CreateNode(Map<String, Object> data) {
        super(data);
        this.value = (byte[]) data.get("data");
        this.flags = (int) data.get("flags");
    }

    public Map<String, Object> generateRequest() {
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("path", this.path);
        requestData.put("sessionId", this.sessionId);
        requestData.put("version", -1);
        // FIXME: Handle flags in FK. 0 is passed  as flag instead of actual value
        requestData.put("flags", 0);
        requestData.put("data", this.value);

        return requestData;
    }

    public String getName() {
        return "create";
    }

}