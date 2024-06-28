package org.apache.zookeeper.faaskeeper.model;

import java.util.Map;

public abstract class DirectOperation extends Operation {
    // TODO: define watch datatype
    private Object watch;
    
    public DirectOperation(String sessionId, String path, Object watch) {
        super(sessionId, path);
        // TODO: Use actual value
        watch = null;

    }

    public DirectOperation(Map<String, Object> data) {
        super((String) data.get("sessionId"), (String) data.get("path"));
        watch = data.get("watch");
    }

    public boolean isCloudRequest() {
        return false;
    }

    public Object getWatch() {
        return watch;
    }
}
