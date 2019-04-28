package org.apache.zookeeper.benchmark;

import java.io.IOException;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

public class SyncBenchmarkClient extends BenchmarkClient {

    private static ZooKeeper zk = null;

    public SyncBenchmarkClient(BenchmarkConfig config) {
        super(config);
        try {
            zk = new ZooKeeper("127.0.0.1:2180", 60000, null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void create() {
        try {
            zk.create("/benchmark/b", null, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT_SEQUENTIAL);
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void run() {
        create();
    }
}