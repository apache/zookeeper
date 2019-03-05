package org.apache.zookeeper.server;

public class DataCacheRecord {
    public String path;
    public DataNode node;
    public long size;

    DataCacheRecord(String path, DataNode node, long size) {
        this.path = path;
        this.node = node;
        this.size = size;
    }
}
