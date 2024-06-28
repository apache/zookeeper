package org.apache.zookeeper.faaskeeper.model;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
/**
 * Base class for all operations submitted to FK work queue.
 */
// TODO: add logging
public abstract class RequestOperation extends Operation {
    public RequestOperation(String sessionId, String path) {
        super(sessionId, path);
    }

    public RequestOperation(Map<String, Object> data) {
        super((String) data.get("sessionId"), (String) data.get("path"));
    }

    public abstract Map<String, Object> generateRequest();

    public boolean isCloudRequest() {
        return true;
    }

    public void processResult(JsonNode result, CompletableFuture<Node> future) {
        String status = result.get("status").asText();
        if ("success".equals(status)) {
            Node n = new Node(result.get("path").asText());
            try {
                JsonNode sysCounterNode = result.get("system_counter");
                if (sysCounterNode.isArray()) {
                    List<BigInteger> sysCounter = new ArrayList<>();
                    for (JsonNode val: sysCounterNode) {
                        sysCounter.add(new BigInteger(val.asText()));
                    }
                    n.setCreated(new Version(SystemCounter.fromRawData(sysCounter), null));
                } else {
                    throw new IllegalArgumentException("System counter data is not an array");
                }
                future.complete(n);
            } catch (Exception e) {
                System.out.println(e);
                future.completeExceptionally(e);
            }
        } else {
            String reason = result.get("reason").asText();
            switch (reason) {
                case "node_exists":
                    future.completeExceptionally(new RuntimeException("Node already exists: " + result.get("path").asText()));
                    break;
                case "node_doesnt_exist":
                    future.completeExceptionally(new RuntimeException("Node does not exist: " + result.get("path").asText()));
                    break;
                case "update_not_committed":
                    future.completeExceptionally(new RuntimeException("Update could not be applied"));
                    break;
                default:
                    future.completeExceptionally(new RuntimeException("Unknown error occurred: " + reason));
            }
        }
    }
}