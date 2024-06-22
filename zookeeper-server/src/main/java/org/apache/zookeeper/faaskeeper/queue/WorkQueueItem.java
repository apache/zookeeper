package org.apache.zookeeper.faaskeeper.queue;

import java.util.concurrent.CompletableFuture;

import org.apache.zookeeper.faaskeeper.model.Operation;

public class WorkQueueItem {
    public final int requestID;
    public final Operation operation;
    public final CompletableFuture<?> future;

    public WorkQueueItem(int requestID, Operation operation, CompletableFuture<?> future) {
        this.requestID = requestID;
        this.operation = operation;
        this.future = future;
    }
}
