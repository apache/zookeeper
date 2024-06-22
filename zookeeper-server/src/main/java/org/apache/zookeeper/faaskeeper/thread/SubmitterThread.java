package org.apache.zookeeper.faaskeeper.thread;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.zookeeper.faaskeeper.queue.WorkQueue;
// import org.apache.zookeeper.faaskeeper.queue.EventQueue;
import org.apache.zookeeper.faaskeeper.queue.WorkQueueItem;
import org.apache.zookeeper.faaskeeper.provider.ProviderClient;
import org.apache.zookeeper.faaskeeper.model.CreateNode;

public class SubmitterThread implements Runnable {
    private Future<?> future;
    private final ExecutorService executorService;
    private volatile boolean running = true;
    private static final Logger LOG;
        static {
        LOG = LoggerFactory.getLogger(SubmitterThread.class);
    }
    private final WorkQueue workQueue;
    private final ProviderClient providerClient;
    private final String sessionID;

    public SubmitterThread(WorkQueue workQueue, ProviderClient providerClient, String sessionID) {
        executorService = Executors.newSingleThreadExecutor();
        this.workQueue = workQueue;
        this.providerClient = providerClient;
        this.sessionID = sessionID;
        this.start();
    }

    public void start() {
        future = executorService.submit(this);
    }

    @Override
    public void run() {
        while(running) {
            try {
                Optional<WorkQueueItem> result = workQueue.get();
                if (!result.isPresent()) {
                    // TODO: Remove this LOG later
                    LOG.debug("work queue empty");
                    continue;
                }

                WorkQueueItem request = result.get();
                String opName = request.operation.getName();

                if (opName == "create") {
                    // TODO: Remove this log
                    LOG.debug("Sending create req to providerClient");
                    CreateNode op = (CreateNode) request.operation;
                    providerClient.sendRequest(sessionID + "-" + String.valueOf(request.requestID), op.generateRequest());
                }

                // TODO: Handle other ops. Start with regSession op. Need to create opType for regsession too

            } catch (Exception e) {
                LOG.error("Exception in processing WorkQueue events in submitter thread", e);
                // TODO: Push exception to eventQueue
            }
        }

        LOG.debug("SubmitterThread loop existed successfully");
    }

    public void stop() {
        running = false;
        try {
            future.get();
            executorService.shutdown();
            LOG.debug("Successfully stopped Submitter thread");
        } catch (InterruptedException e) {
            LOG.error("Submitter Thread shutdown interrupted: ", e);
        } catch(ExecutionException e) {
            LOG.error("Error in Submitter thread execution: ", e);
        }
    }
}
