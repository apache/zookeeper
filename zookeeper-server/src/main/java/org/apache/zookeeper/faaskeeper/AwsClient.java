package org.apache.zookeeper.faaskeeper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

public class AwsClient extends ProviderClient {
    private DynamoDbClient ddb;
    private String userTable;
    
    public AwsClient(FaasKeeperConfig config) {
        super(config);
        this.ddb = DynamoDbClient.builder()
            .region(Region.of(config.getDeploymentRegion()))
            .build();
        this.userTable = String.format("faaskeeper-%s-users", config.getDeploymentName());
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

        // TODO: Add Logger for this
        try {
            PutItemResponse response = ddb.putItem(request);
            System.out.println(userTable + " was successfully updated. The request id is " + response.responseMetadata().requestId());

        } catch (ResourceNotFoundException e) {
            System.err.format("Error: The Amazon DynamoDB table \"%s\" can't be found.\n", userTable);
            System.err.println("Be sure that it exists and that you've typed its name correctly!");
        } catch (DynamoDbException e) {
            System.err.println(e.getMessage());
        }
    }
}
