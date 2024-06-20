package org.apache.zookeeper.faaskeeper.queue;
import com.fasterxml.jackson.databind.JsonNode;

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

class CloudExpectedtResult extends EventQueueItem {
    public CloudExpectedtResult(JsonNode result) {
        super(result);
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
