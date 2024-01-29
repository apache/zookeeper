package org.apache.zookeeper.cli;

import static java.nio.charset.StandardCharsets.UTF_8;

public class PlainOutputFormatter implements OutputFormatter {

    public static final PlainOutputFormatter INSTANCE = new PlainOutputFormatter();

    @Override
    public String format(byte[] data) {
        return new String(data, UTF_8);
    }
}
