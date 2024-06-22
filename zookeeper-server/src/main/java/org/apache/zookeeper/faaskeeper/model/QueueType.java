package org.apache.zookeeper.faaskeeper.model;

public enum QueueType {
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