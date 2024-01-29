package org.apache.zookeeper.cli;

public interface OutputFormatter {

    String format(byte[] data);
}
