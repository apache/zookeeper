package org.apache.zookeeper.faaskeeper.queue;

import com.fasterxml.jackson.databind.JsonNode;

public class WatchNotification extends EventQueueItem {
    public WatchNotification(JsonNode result) {
        super(result);
    }

    public String getEventType() {
        return EventType.WATCH_NOTIFICATION.getValue();
    }
}

