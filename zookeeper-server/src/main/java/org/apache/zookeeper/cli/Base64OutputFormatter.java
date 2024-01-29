package org.apache.zookeeper.cli;

import java.util.Base64;

public class Base64OutputFormatter implements OutputFormatter {

    public static final Base64OutputFormatter INSTANCE = new Base64OutputFormatter();

    @Override
    public String format(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
