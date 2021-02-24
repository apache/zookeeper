package testclient;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.apache.zookeeper.ZooDefs.Ids.OPEN_ACL_UNSAFE;

public class TestWatcher {

    private ZooKeeper zk;
    private String path;

    @Before
    public void init() {
        try {
            zk = new ZooKeeper("localhost:2181", 15000, new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    if (event.getState().equals(Event.KeeperState.SyncConnected)) {
                        // 创建节点
                        try {
                            path = "/master";
                            Stat stat = zk.exists(path, true);
                            if (stat == null) {
                                zk.create(path, "".getBytes(), OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                            }
                        } catch (KeeperException e) {
                            e.printStackTrace();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        zk.getData(path, true, new AsyncCallback.DataCallback() {
                            @Override
                            public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat) {
                                switch (KeeperException.Code.get(rc)) {
                                    case OK:
                                        System.out.println((String)ctx);
                                        break;
                                }
                            }
                        }, new Object());
                    }
                    if (event.getType().equals(Event.EventType.NodeDataChanged)) {
                        System.out.println(path + "节点发生变化");
                    }

                }
            });
        } catch (IOException e) {
            e.printStackTrace();

        }
    }

    @Test
    public void testWater() {
        try {
            Thread.sleep(70000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
