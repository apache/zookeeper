package org.apache.zookeeper.faaskeeper.queue;

import java.util.Optional; 
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.LinkedBlockingQueue;

public class EventQueue {
    private static final Logger LOG;
    private LinkedBlockingQueue<EventQueueItem> queue;
    static {
        LOG = LoggerFactory.getLogger(EventQueue.class);
    }
    // private Map<String, List<Watch>> _watches;
    // private Lock _watchesLock;
    private boolean closing;

    public EventQueue() {
        // Initialize queue, watches, lock, and logger
        queue = new LinkedBlockingQueue<EventQueueItem>();
        closing = false;
    }

    public Optional<EventQueueItem> get() {
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

    // public void addExpectedResult(int requestId, Operation request, Future future) {
    // }

    // public void addDirectResult(int requestId, Object result, Future future) {
    // }

    public void addIndirectResult(JsonNode result) throws Exception {
        if (closing) {
            throw new Exception("Cannot add result to queue: EventQueue has been closed");
        }
        try {
            queue.add(new CloudIndirectResult(result));
        } catch (IllegalStateException e) {
            LOG.error("EventQueue add item failed", e);
            throw e;
        }
    }
}