package org.apache.zookeeper.faaskeeper.queue;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.zookeeper.faaskeeper.model.Operation;

public abstract class EventQueueItem {
    public JsonNode result;
    

    public EventQueueItem(JsonNode result) {
        this.result = result;
    }
}

enum EventType {
    CLOUD_INDIRECT_RESULT("CLOUD_INDIRECT_RESULT"),
    CLOUD_DIRECT_RESULT("CLOUD_DIRECT_RESULT"),
    CLOUD_EXPECTED_RESULT("CLOUD_EXPECTED_RESULT"),
    WATCH_NOTIFICATION("WATCH_NOTIFICATION");

    private final String value;

    EventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}

class CloudIndirectResult extends EventQueueItem {
    public CloudIndirectResult(JsonNode result) {
        super(result);
    }

    public String getEventType() {
        return EventType.CLOUD_INDIRECT_RESULT.getValue();
    }
}

class CloudDirectResult extends EventQueueItem {
    public CloudDirectResult(JsonNode result) {
        super(result);
    }

    public String getEventType() {
        return EventType.CLOUD_DIRECT_RESULT.getValue();
    }
}

class CloudExpectedResult extends EventQueueItem {
    public final int requestID;
    public final Operation op;
    public final CompletableFuture<?> future;
    public CloudExpectedResult(int requestID, Operation op, CompletableFuture<?> future) {
        super(null);
        this.requestID = requestID;
        this.op = op;
        this.future = future;
    }

    public String getEventType() {
        return EventType.CLOUD_EXPECTED_RESULT.getValue();
    }
}

class WatchNotification extends EventQueueItem {
    public WatchNotification(JsonNode result) {
        super(result);
    }

    public String getEventType() {
        return EventType.WATCH_NOTIFICATION.getValue();
    }
}
