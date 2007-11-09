/**
 * 
 */
package com.yahoo.zookeeper.test;

import java.io.File;

import junit.framework.TestCase;

import org.junit.Test;

import com.yahoo.zookeeper.Watcher;
import com.yahoo.zookeeper.ZooKeeper;
import com.yahoo.zookeeper.ZooDefs.Ids;
import com.yahoo.zookeeper.data.Stat;
import com.yahoo.zookeeper.proto.WatcherEvent;
import com.yahoo.zookeeper.server.SyncRequestProcessor;
import com.yahoo.zookeeper.server.NIOServerCnxn;
import com.yahoo.zookeeper.server.ZooKeeperServer;

/**
 * @author breed
 * 
 */
public class RecoveryTest extends TestCase implements Watcher {
    static File baseTest = new File(System.getProperty("build.test.dir",
            "build"));

    @Test
    public void testRecovery() throws Exception {
        File tmpDir = File.createTempFile("test", ".junit", baseTest);
        tmpDir = new File(tmpDir + ".dir");
        tmpDir.mkdirs();
        ZooKeeperServer zs = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        int oldSnapCount = SyncRequestProcessor.snapCount;
        SyncRequestProcessor.snapCount = 1000;
        try {
            NIOServerCnxn.Factory f = new NIOServerCnxn.Factory(2344);
            f.startup(zs);
            System.out.println("starting up the the server -- sleeping");
            Thread.sleep(1000);
            ZooKeeper zk = new ZooKeeper("127.0.0.1:2344", 20000, this);
            String path;
            System.out.println("starting creating nodes");
            for (int i = 0; i < 10; i++) {
                path = "/" + i;
                zk
                        .create(path, (path + "!").getBytes(),
                                Ids.OPEN_ACL_UNSAFE, 0);
                for (int j = 0; j < 10; j++) {
                    String subpath = path + "/" + j;
                    zk.create(subpath, (subpath + "!").getBytes(),
                            Ids.OPEN_ACL_UNSAFE, 0);
                    for (int k = 0; k < 20; k++) {
                        String subsubpath = subpath + "/" + k;
                        zk.create(subsubpath, (subsubpath + "!").getBytes(),
                                Ids.OPEN_ACL_UNSAFE, 0);
                    }
                }
            }
            f.shutdown();
            Thread.sleep(1000);
            zs = new ZooKeeperServer(tmpDir, tmpDir, 3000);
            f = new NIOServerCnxn.Factory(2344);
            f.startup(zs);
            Thread.sleep(1000);
            Stat stat = new Stat();
            for (int i = 0; i < 10; i++) {
                path = "/" + i;
                System.out.println("Checking " + path);
                assertEquals(new String(zk.getData(path, false, stat)), path
                        + "!");
                for (int j = 0; j < 10; j++) {
                    String subpath = path + "/" + j;
                    assertEquals(new String(zk.getData(subpath, false, stat)),
                            subpath + "!");
                    for (int k = 0; k < 20; k++) {
                        String subsubpath = subpath + "/" + k;
                        assertEquals(new String(zk.getData(subsubpath, false,
                                stat)), subsubpath + "!");
                    }
                }
            }
            f.shutdown();
            Thread.sleep(2000);
            zs = new ZooKeeperServer(tmpDir, tmpDir, 3000);
            f = new NIOServerCnxn.Factory(2344);
            f.startup(zs);
            Thread.sleep(4000);
            stat = new Stat();
            System.out.println("Check 2");
            for (int i = 0; i < 10; i++) {
                path = "/" + i;
                assertEquals(new String(zk.getData(path, false, stat)), path
                        + "!");
                for (int j = 0; j < 10; j++) {
                    String subpath = path + "/" + j;
                    assertEquals(new String(zk.getData(subpath, false, stat)),
                            subpath + "!");
                    for (int k = 0; k < 20; k++) {
                        String subsubpath = subpath + "/" + k;
                        assertEquals(new String(zk.getData(subsubpath, false,
                                stat)), subsubpath + "!");
                    }
                }
            }
            f.shutdown();
        } finally {
            SyncRequestProcessor.snapCount = oldSnapCount;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.yahoo.zookeeper.Watcher#process(com.yahoo.zookeeper.proto.WatcherEvent)
     */
    public void process(WatcherEvent event) {
        // TODO Auto-generated method stub

    }
}
