package org.apache.zookeeper.faaskeeper;
// ddb deps
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

// sqs deps
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AwsClient extends ProviderClient {
    private final AmazonSQS sqs;
    private final DynamoDbClient ddb;
    private final String userTable;
    private static final Logger LOG;
    private final String writerQueueName;
    private final String writerQueueUrl;
    static {
        LOG = LoggerFactory.getLogger(AwsClient.class);
    }
    
    public AwsClient(FaasKeeperConfig config) {
        super(config);

        this.ddb = DynamoDbClient.builder()
            .region(Region.of(config.getDeploymentRegion()))
            .build();
        this.sqs = AmazonSQSClientBuilder.standard()
            .withRegion(config.getDeploymentRegion()) // Specify the desired AWS region
            .build();
        
        this.userTable = String.format("faaskeeper-%s-users", config.getDeploymentName());
        this.writerQueueName = String.format("faaskeeper-%s-writer-sqs.fifo", config.getDeploymentName());
        this.writerQueueUrl = sqs.getQueueUrl(writerQueueName).getQueueUrl();
    }

    public void registerSession(String sessionId, String sourceAddr, boolean heartbeat) {
        Map<String, AttributeValue> itemValues = new HashMap<>();
        itemValues.put("user", AttributeValue.builder().s(sessionId).build());
        itemValues.put("source_addr", AttributeValue.builder().s(sourceAddr).build());
        itemValues.put("ephemerals", AttributeValue.builder().l(new ArrayList<>()).build());
        PutItemRequest request = PutItemRequest.builder()
            .tableName(userTable)
            .item(itemValues)
            .build();

        try {
            PutItemResponse response = ddb.putItem(request);
            LOG.info(userTable + " was successfully updated. The request id is " + response.responseMetadata().requestId());

        } catch (ResourceNotFoundException e) {
            LOG.error(String.format("Error: The Amazon DynamoDB table \"%s\" can't be found.\n", userTable));
        } catch (DynamoDbException e) {
            System.err.println(e.getMessage());
        }
    }

    public void sendRequest(String requestId, Map <String, Object> data) throws Exception {
        try {
            if (config.getWriterQueue() == QueueType.SQS) {
                ObjectMapper objectMapper = new ObjectMapper();
                String serializedData = objectMapper.writeValueAsString(data);
                LOG.info("Attempting to send msg: " + serializedData);

                SendMessageRequest send_msg_request = new SendMessageRequest()
                    .withMessageGroupId("0")
                    .withMessageDeduplicationId(requestId)
                    .withQueueUrl(writerQueueUrl)
                    .withMessageBody(serializedData);
                
                SendMessageResult res = sqs.sendMessage(send_msg_request);
                LOG.debug("Msg sent successfully: " + res.toString());
            } else {
                throw new UnsupportedOperationException("Unsupported queue type specified in sendRequest: " + config.getWriterQueue().name());
            }

        } catch(Exception e) {
            LOG.error("Error sending request: ", e);
            throw e;
        }
    }


}
