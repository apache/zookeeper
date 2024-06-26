package org.apache.zookeeper.faaskeeper.queue;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.zookeeper.faaskeeper.model.Operation;

public class WorkQueue {
    private static final Logger LOG;
    private LinkedBlockingQueue<WorkQueueItem> queue;
    static {
        LOG = LoggerFactory.getLogger(WorkQueue.class);
    }
    private boolean closing;
    private int requestCount = 0;

    public WorkQueue() {
        queue = new LinkedBlockingQueue<WorkQueueItem>();
        closing = false;
    }

    public <T> void addRequest(Operation op, CompletableFuture<T> future) throws Exception {
        if (closing) {
            throw new Exception("Cannot add result to queue: WorkQueue has been closed");
        }
        try {
            queue.add(new WorkQueueItem<>(requestCount, op, future));
            // TODO: remove this log
            LOG.debug("Pushed item to workqueue");
            requestCount += 1;
        } catch(Exception e) {
            LOG.error("WorkQueue add item failed", e);
            throw e;
        }
    }

    public Optional<WorkQueueItem> get() {
        try {
            return Optional.ofNullable(queue.poll(500, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            LOG.debug("Queue poll interrupted", e);
            return Optional.empty();
        }
    }

    public void close() {
        closing = true;
    }

    // TODO: Decide if waitClose is needed to be used anywhere
    public void waitClose(float timeout, float interval) throws TimeoutException {
        long start = System.currentTimeMillis();
        while(!queue.isEmpty() && System.currentTimeMillis() - start < timeout * 1000) {
            try {
                Thread.sleep((long) (interval * 1000));
            } catch (InterruptedException e) {
                LOG.debug("WorkQueue thread interrupted unexpectedly");
            }
        }

        if (!queue.isEmpty()) {
            throw new TimeoutException(String.format("WorkQueue didn't close in %.2f seconds", timeout));
        }
    }
}
