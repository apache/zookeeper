package org.apache.zookeeper.faaskeeper.queue;

import java.util.concurrent.CompletableFuture;

import org.apache.zookeeper.faaskeeper.model.Operation;

public class WorkQueueItem<T> {
    public final int requestID;
    public final Operation operation;
    public final CompletableFuture<T> future;

    public WorkQueueItem(int requestID, Operation operation, CompletableFuture<T> future) {
        this.requestID = requestID;
        this.operation = operation;
        this.future = future;
    }
}
