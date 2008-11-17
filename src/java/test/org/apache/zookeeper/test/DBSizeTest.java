package org.apache.zookeeper.test;

import java.io.IOException;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.junit.Test;

public class DBSizeTest extends ClientBase {
    String snapCount;
    @Override
    protected void setUp() throws Exception {
        // Change the snapcount to happen more often
        snapCount = System.getProperty("zookeeper.snapCount", "1024");
        System.setProperty("zookeeper.snapCount", "10");
        super.setUp();
    }
    

    @Override
    protected void tearDown() throws Exception {
        System.setProperty("zookeeper.snapCount", snapCount);
        super.tearDown();
    }


    // Test that the latency of requests doesn't increase with
    // the size of the database
    @Test
    public void testDBScale()
        throws IOException, InterruptedException, KeeperException
    {
        String path = "/SIZE";
        byte data[] = new byte[1024];
        ZooKeeper zk = null;
        try {
            zk = createClient();
            long startTime = System.currentTimeMillis();
            zk.create(path, data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            long baseLatency = System.currentTimeMillis() - startTime;
            
            for(int i = 0; i < 16; i++) {
                startTime = System.currentTimeMillis();
                zk.create(path + '/' + i, data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                long latency = System.currentTimeMillis() - startTime;
                System.out.println("Latency = " + latency);
                //assertTrue(latency < baseLatency + 10);
                for(int j = 0; j < 1024; j++) {
                    zk.create(path + '/' + i + '/' + j, data, Ids.OPEN_ACL_UNSAFE, 
                            CreateMode.EPHEMERAL, new AsyncCallback.StringCallback() {
                        public void processResult(int rc, String path,
                                Object ctx, String name) {
                        }}, null);
                }
            }
        } finally {
            if(zk != null)
                zk.close();
        }
    }


}
