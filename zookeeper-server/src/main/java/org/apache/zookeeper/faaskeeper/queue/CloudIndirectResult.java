package org.apache.zookeeper.faaskeeper.queue;
import com.fasterxml.jackson.databind.JsonNode;

public class CloudIndirectResult extends EventQueueItem {
    public CloudIndirectResult(JsonNode result) {
        super(result);
    }

    public String getEventType() {
        return EventType.CLOUD_INDIRECT_RESULT.getValue();
    }
}