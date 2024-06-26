package org.apache.zookeeper.faaskeeper.provider;

import java.util.HashMap;
import java.util.Map;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DynamoReader {
    private static final Logger LOG;
    static {
        LOG = LoggerFactory.getLogger(DynamoReader.class);
    }

    // Method to determine the DynamoDB type of a value
    private static String dynamoDBType(Object value) {
        if (value instanceof String) {
            return "S";
        } else if (value instanceof Integer) {
            return "N";
        } else if (value instanceof byte[]) {
            return "B";
        } else if (value instanceof Object[]) {
            return "L";
        } else {
            throw new IllegalArgumentException("Unsupported data type for DynamoDB: " + value.getClass().getSimpleName());
        }
    }

    // TODO: Implement serialization for []Object too if needed
    // Method to convert the value to a suitable format for DynamoDB
    private static String dynamoDBValue(Object value) {
        if (value instanceof byte[]) {
            return Base64.getEncoder().encodeToString((byte[]) value);
        } else if (value instanceof String){
            return (String) value;
        } else if (value instanceof Integer) {
            return String.valueOf(value);
        } else {
            LOG.error("Unhandled datatype in dynamoDBValue. Default toString() value will be returned");
            return value.toString();
        }
    }

    // Method to convert items to a DynamoDB compatible format
    public static Map<String, Map<String, Object>> convertItems(Map<String, Object> items) {
        Map<String, Map<String, Object>> convertedItems = new HashMap<>();
        for (Map.Entry<String, Object> entry : items.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String type = dynamoDBType(value);
            Object formattedValue = dynamoDBValue(value);
            Map<String, Object> valueMap = new HashMap<>();
            valueMap.put(type, formattedValue);
            convertedItems.put(key, valueMap);
        }
        return convertedItems;
    }
}