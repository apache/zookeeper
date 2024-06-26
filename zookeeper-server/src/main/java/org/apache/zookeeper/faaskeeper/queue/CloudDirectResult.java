package org.apache.zookeeper.faaskeeper.queue;

import java.util.concurrent.CompletableFuture;

public class CloudDirectResult<T> extends EventQueueItem {
    public final int requestID;
    public final CompletableFuture<Object> future;
    public final T result;

    public CloudDirectResult(int requestID, T result, CompletableFuture<Object> future) {
        super(null);
        this.requestID = requestID;
        this.future = future;
        this.result = result;
    }

    public String getEventType() {
        return EventType.CLOUD_DIRECT_RESULT.getValue();
    }
}