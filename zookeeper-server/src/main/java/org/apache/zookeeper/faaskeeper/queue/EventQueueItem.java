package org.apache.zookeeper.faaskeeper.queue;

import com.fasterxml.jackson.databind.JsonNode;

// TODO: Move JsonNode result to CloudIndirect reuslt. Its not needed in base class
public abstract class EventQueueItem {
    public JsonNode result;
    private long timestamp;

    public EventQueueItem(JsonNode result) {
        this.result = result;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
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