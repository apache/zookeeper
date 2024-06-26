package org.apache.zookeeper.faaskeeper.model;

import java.util.Map;
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
                // TODO: Implement version
                // n.setCreated(new Version(SystemCounter.fromRawData((List<Integer>) result.get("system_counter")), null));
                future.complete(n);
            } catch (Exception e) {
                future.completeExceptionally(new RuntimeException("Error setting created version: ", e));
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