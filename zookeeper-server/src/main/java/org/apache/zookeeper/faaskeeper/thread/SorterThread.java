package org.apache.zookeeper.faaskeeper.thread;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.Queue;
import java.util.LinkedList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.zookeeper.faaskeeper.queue.EventQueue;
import org.apache.zookeeper.faaskeeper.queue.EventQueueItem;
import org.apache.zookeeper.faaskeeper.queue.CloudDirectResult;
import org.apache.zookeeper.faaskeeper.queue.CloudExpectedResult;
import org.apache.zookeeper.faaskeeper.queue.CloudIndirectResult;
import org.apache.zookeeper.faaskeeper.queue.WatchNotification;
import org.apache.zookeeper.faaskeeper.model.Node;


public class SorterThread implements Runnable {
    private Future<?> threadFuture;
    private final ExecutorService executorService;
    private volatile boolean running = true;
    private final EventQueue eventQueue;
    private static final Logger LOG;
    static {
        LOG = LoggerFactory.getLogger(SorterThread.class);
    }

    public SorterThread(EventQueue eventQueue) {
        executorService = Executors.newSingleThreadExecutor();
        this.eventQueue = eventQueue;
        this.start();
    }

    public void start() {
        threadFuture = executorService.submit(this);
    }

    @Override
    public void run() {
        LOG.debug("Starting SorterThread loop");
        boolean processedResult;
        Queue<CloudExpectedResult> futures = new LinkedList<>();
        Optional<EventQueueItem> result;

        while (running) {
            processedResult = false;
            result = eventQueue.get();

            if (!result.isPresent()) {
                LOG.debug("EventQueue empty");
                // TODO call check timeout of futures
                continue;
            }

            EventQueueItem event = result.get();

            if (event instanceof CloudDirectResult<?>) {
                CloudDirectResult<?> directResult = (CloudDirectResult<?>) event;
                // TODO: Handle if result not none and result is instance of NodeType (ie Read ops)

                if (directResult.result instanceof Exception) {
                    directResult.future.completeExceptionally((Exception) directResult.result);
                } else {
                    LOG.debug("Completing future...");
                    directResult.future.complete(directResult.result);
                }
                processedResult = true;
                
            } else if (event instanceof CloudIndirectResult) {
                CloudIndirectResult indirectResult = (CloudIndirectResult) event;
                
                int reqID = Integer.parseInt(indirectResult.result
                        .get("event")
                        .asText().split("-")[1]);
                
                if (futures.isEmpty()) {
                    LOG.error(String.format("Ignoring the result: %s with req_id: %d due to non-existent future",
                        indirectResult.result.toString(), reqID));
                }

                CloudExpectedResult expectedResult = futures.remove();
                // enforce ordering
                assert expectedResult.requestID == reqID;
                // expectedResult
                processedResult = true;
                expectedResult.op.processResult(indirectResult.result, expectedResult.future);
                
            } else if (event instanceof CloudExpectedResult) {
                event.setTimestamp(System.currentTimeMillis());
                LOG.debug("RECVD CloudExpectedResult");
                futures.add((CloudExpectedResult) event);            
            } else if (event instanceof WatchNotification) {
                WatchNotification watchNotification = (WatchNotification) event;
                LOG.debug("RECVD WatchNotification");
                // Handle WatchNotification event
            } else {
                LOG.error("Unknown event type: " + event.getClass().getName());
            }

            if (!processedResult) {
                // TODO: Call check_timeout
            }
        }

        LOG.debug("SorterThread loop exited successfully");
    }

    public void stop() {
        running = false;
        try {
            threadFuture.get();
            executorService.shutdown();
            LOG.debug("Successfully stopped SorterThread");
        } catch (InterruptedException e) {
            LOG.error("SorterThread shutdown interrupted: ", e);
        } catch(ExecutionException e) {
            LOG.error("Error in SorterThread execution: ", e);
        }
    }
}
