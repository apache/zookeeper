package org.apache.zookeeper.faaskeeper.queue;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequest;
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.services.sqs.model.Message;
import org.apache.zookeeper.faaskeeper.FaasKeeperConfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

// import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SqsListener implements Runnable {
    private final AmazonSQS sqs;
    private final String clientQueueUrl;
    private final String clientQueueName;
    // private final EventQueue eventQueue;
    private Future<?> future;
    // private final CountDownLatch latch;
    private final ExecutorService executorService;
    private volatile boolean running = true;
    private static final Logger LOG;
        static {
        LOG = LoggerFactory.getLogger(SqsListener.class);
    }

    public SqsListener(EventQueue eventQueue, FaasKeeperConfig config) {
        this.sqs = AmazonSQSClientBuilder.standard()
            .withRegion(config.getDeploymentRegion()) // Specify the desired AWS region
            .build();

        this.clientQueueName = String.format("faaskeeper-%s-client-sqs", config.getDeploymentName());
        this.clientQueueUrl = sqs.getQueueUrl(clientQueueName).getQueueUrl();
        // this.eventQueue = eventQueue;
        // this.latch = new CountDownLatch(1);
        this.executorService = Executors.newSingleThreadExecutor();
        this.start();
    }

    public void start() {
        this.future = executorService.submit(this);
    }

    @Override
    public void run() {
        LOG.debug("Starting SQS listener loop");

        while (running) {
            ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(clientQueueUrl)
                    .withWaitTimeSeconds(5);
            
            ReceiveMessageResult result = sqs.receiveMessage(receiveMessageRequest);
            List<Message> messages = result.getMessages();

            if (messages.isEmpty()) {
                continue;
            }

            List<DeleteMessageBatchRequestEntry> deleteEntries = new ArrayList<>();

            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                // JSONObject data = new JSONObject(msg.getBody());
                LOG.info("Received message: " + msg.getBody());
                ObjectMapper objectMapper = new ObjectMapper();

                try {
                    JsonNode node = objectMapper.readTree(msg.getBody());
                } catch (JsonProcessingException e) {
                    LOG.error("Error parsing message body: " + e.getMessage());
                    continue;
                }

                // if (data.has("type") && "heartbeat".equals(data.getString("type"))) {
                //     // FIXME: add heartbeats
                // } else if (data.has("watch-event")) {
                //     // eventQueue.addWatchNotification(data);
                // } else {
                //     // eventQueue.addIndirectResult(data);
                // }

                deleteEntries.add(new DeleteMessageBatchRequestEntry(String.valueOf(i), msg.getReceiptHandle()));
            }

            if (!deleteEntries.isEmpty()) {
                DeleteMessageBatchRequest deleteRequest = new DeleteMessageBatchRequest(clientQueueUrl, deleteEntries);
                // What if this throws an error?
                sqs.deleteMessageBatch(deleteRequest);
            }
        }

        LOG.debug("SQS listener loop exited successfully");
        // latch.countDown();
    }

    public void stop() {
        running = false;
        try {
            // latch.await();
            this.future.get();
            executorService.shutdown();
            LOG.info("Successfully stopped SQS listener thread");
        } catch (InterruptedException e) {
            LOG.error("SqsListener Thread shutdown interrupted: ", e);
        } catch(ExecutionException e) {
            LOG.error("Error in sqs listener thread execution: ", e);
        }
    }
}
