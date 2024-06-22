package org.apache.zookeeper.faaskeeper.model;

import java.util.Map;
import java.util.HashMap;

public class CreateNode extends RequestOperation {
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
        requestData.put("op", "create_node");
        requestData.put("path", this.path);
        requestData.put("session_id", this.sessionId);
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