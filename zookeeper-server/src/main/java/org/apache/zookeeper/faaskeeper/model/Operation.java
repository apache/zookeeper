package org.apache.zookeeper.faaskeeper.model;

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