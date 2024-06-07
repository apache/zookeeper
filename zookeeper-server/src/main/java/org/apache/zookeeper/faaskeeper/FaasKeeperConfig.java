package org.apache.zookeeper.faaskeeper;
import java.util.Map;

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

enum QueueType {
    DYNAMODB, SQS;

    public static QueueType deserialize(String val) {
        switch (val) {
            case "dynamodb":
                return DYNAMODB;
            case "sqs":
                return SQS;
            default:
                throw new IllegalArgumentException("Unknown QueueType: " + val);
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

    public static AWSConfig deserialize(Map<String, String> data) {
        AWSConfig cfg = new AWSConfig();
        cfg.dataBucket = data.get("data-bucket");
        return cfg;
    }
}

class FaasKeeperConfig {
    private boolean verbose;
    private CloudProvider provider;
    private String region;
    private String deploymentName;
    private int heartbeatFrequency;
    private StorageType userStorage;
    private QueueType writerQueue;
    private AWSConfig providerCfg;
    private ClientChannel clientChannel;

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

    public static FaasKeeperConfig deserialize(Map<String, String> data) {
        FaasKeeperConfig cfg = new FaasKeeperConfig();
        cfg.verbose = Boolean.parseBoolean(data.get("verbose"));
        cfg.provider = CloudProvider.deserialize(data.get("cloud-provider"));
        cfg.region = data.get("deployment-region");
        cfg.deploymentName = data.get("deployment-name");
        cfg.heartbeatFrequency = Integer.parseInt(data.get("heartbeat-frequency"));
        cfg.userStorage = StorageType.deserialize(data.get("user-storage"));
        cfg.writerQueue = QueueType.deserialize(data.get("worker-queue"));
        cfg.clientChannel = ClientChannel.deserialize(data.get("client-channel"));

        if (cfg.provider == CloudProvider.AWS) {
            cfg.providerCfg = AWSConfig.deserialize(data);
        } else {
            throw new UnsupportedOperationException("Provider not supported");
        }

        return cfg;
    }
}