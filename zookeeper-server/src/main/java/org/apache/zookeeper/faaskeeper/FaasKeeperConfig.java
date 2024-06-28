package org.apache.zookeeper.faaskeeper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

import org.apache.zookeeper.faaskeeper.model.QueueType;

enum StorageType {
    PERSISTENT, KEY_VALUE, IN_MEMORY;

    public static StorageType deserialize(String val) {
        switch (val) {
            case "persistent":
                return PERSISTENT;
            case "key-value":
                return KEY_VALUE;
            default:
                throw new IllegalArgumentException("Unknown StorageType: " + val);
        }
    }
}

enum ClientChannel {
    TCP, SQS;

    public static ClientChannel deserialize(String val) {
        switch (val) {
            case "tcp":
                return TCP;
            case "sqs":
                return SQS;
            default:
                throw new IllegalArgumentException("Unknown ClientChannel: " + val);
        }
    }
}

enum CloudProvider {
    AWS;

    public static String serialize(CloudProvider val) {
        if (val != AWS) {
            throw new IllegalArgumentException("Invalid CloudProvider for serialization: " + val);
        }
        return "aws";
    }

    public static CloudProvider deserialize(String val) {
        if ("aws".equals(val)) {
            return AWS;
        } else {
            throw new IllegalArgumentException("Unknown CloudProvider: " + val);
        }
    }
}

class AWSConfig {
    private String dataBucket;

    public String getDataBucket() {
        return dataBucket;
    }

    public static AWSConfig deserialize(String dataBucket) {
        AWSConfig cfg = new AWSConfig();
        cfg.dataBucket = dataBucket;
        return cfg;
    }
}

public class FaasKeeperConfig {
    private int port;
    private boolean verbose;
    private CloudProvider provider;
    private String region;
    private String deploymentName;
    private int heartbeatFrequency;
    private StorageType userStorage;
    private QueueType writerQueue;
    private AWSConfig providerCfg;
    private ClientChannel clientChannel;

    public int getPort() {
        return port;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public CloudProvider getCloudProvider() {
        return provider;
    }

    public String getDeploymentRegion() {
        return region;
    }

    public String getDeploymentName() {
        return deploymentName;
    }

    public int getHeartbeatFrequency() {
        return heartbeatFrequency;
    }

    public StorageType getUserStorage() {
        return userStorage;
    }

    public QueueType getWriterQueue() {
        return writerQueue;
    }

    public AWSConfig getProviderConfig() {
        return providerCfg;
    }

    public ClientChannel getClientChannel() {
        return clientChannel;
    }

    public static FaasKeeperConfig buildFromConfigJson(String configFilePath) throws IOException, JsonProcessingException, Exception {
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(configFilePath)));
        FaasKeeperConfig cfg = FaasKeeperConfig.deserialize(content);
        return cfg;
    }

    public FaasKeeperConfig() {}

    public static FaasKeeperConfig deserialize(String jsonString) throws JsonProcessingException, Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(jsonString);

        // Benchmarking config not used currently
        // JsonNode configurationNode = rootNode.get("configuration");
        // boolean benchmarking = configurationNode.get("benchmarking").asText().equals("True");
        // int benchmarkingFrequency = configurationNode.get("benchmarking-frequency").asInt();

        FaasKeeperConfig cfg = new FaasKeeperConfig();
        
        cfg.port = rootNode.get("port").asInt();
        if (cfg.port <= 0 || cfg.port > 65535) {
            throw new Exception("Port number is out of range: " + cfg.port);
        }

        cfg.verbose = rootNode.get("verbose").asBoolean();
        cfg.provider = CloudProvider.deserialize(rootNode.get("cloud-provider").asText());

        cfg.region = rootNode.get("deployment-region").asText();
        if ("".equals(cfg.region)) {
            throw new Exception("Deployment region is not set");
        }

        cfg.deploymentName = rootNode.get("deployment-name").asText();
        if ("".equals(cfg.deploymentName)) {
            throw new Exception("Deployment name is not set");
        }

        cfg.heartbeatFrequency = rootNode.get("heartbeat-frequency").asInt();
        if (cfg.heartbeatFrequency <= 0) {
            throw new Exception("Invalid heartbeat frequency: " + Integer.toString(cfg.heartbeatFrequency));
        }

        cfg.userStorage = StorageType.deserialize(rootNode.get("user-storage").asText());
        cfg.writerQueue = QueueType.deserialize(rootNode.get("worker-queue").asText());
        cfg.clientChannel = ClientChannel.deserialize(rootNode.get("client-channel").asText());

        if (cfg.provider == CloudProvider.AWS) {
            JsonNode awsNode = rootNode.get("aws");
            if (awsNode == null) {
                throw new Exception("AWS configuration missing in config.");
            }

            String dataBucket = awsNode.get("data-bucket").asText();
            if ("".equals(dataBucket)) {
                throw new Exception("Data bucket is not set in aws configuration");
            }

            cfg.providerCfg = AWSConfig.deserialize(dataBucket);
        } else {
            throw new UnsupportedOperationException("Provider not supported");
        }

        return cfg;
    }
}