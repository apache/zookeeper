package com.yahoo.zookeeper.test;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.concurrent.LinkedBlockingQueue;

import junit.framework.TestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.yahoo.zookeeper.KeeperException;
import com.yahoo.zookeeper.Watcher;
import com.yahoo.zookeeper.ZooKeeper;
import com.yahoo.zookeeper.AsyncCallback.DataCallback;
import com.yahoo.zookeeper.AsyncCallback.StringCallback;
import com.yahoo.zookeeper.AsyncCallback.VoidCallback;
import com.yahoo.zookeeper.KeeperException.Code;
import com.yahoo.zookeeper.ZooDefs.CreateFlags;
import com.yahoo.zookeeper.ZooDefs.Ids;
import com.yahoo.zookeeper.data.Stat;
import com.yahoo.zookeeper.proto.WatcherEvent;
import com.yahoo.zookeeper.server.NIOServerCnxn;
import com.yahoo.zookeeper.server.ZooKeeperServer;
import com.yahoo.zookeeper.server.ZooLog;

public class AsyncTest extends TestCase implements Watcher, StringCallback, VoidCallback, DataCallback {
    protected static String hostPort = "127.0.0.1:33221";
    LinkedBlockingQueue<WatcherEvent> events = new LinkedBlockingQueue<WatcherEvent>();
    static File baseTest = new File(System.getProperty("build.test.dir", "build"));
    NIOServerCnxn.Factory f = null;
    QuorumTest qt = new QuorumTest();
    @Before
    protected void setUp() throws Exception {
        qt.setUp();
        hostPort = ClientTest.hostPort;
    }

    protected void restart() throws Exception {
        qt.startServers();
    }
    
    @After
    protected void tearDown() throws Exception {
        qt.tearDown();
    	ZooLog.logError("Client test shutdown");
        if (f != null) {
            f.shutdown();
        }
        ZooLog.logError("Client test shutdown finished");
    }
    
    boolean bang;
    
    class HammerThread extends Thread implements Watcher, StringCallback, VoidCallback {
        ZooKeeper zk;
        public void run() {
        try {
            zk = new ZooKeeper(hostPort, 30000, this);
            while(bang) {
                zk.create("/test-", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateFlags.SEQUENCE, this, null);
                incOut();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        }
        int outstanding;
        synchronized void incOut() throws InterruptedException {
            outstanding++;
            while(outstanding > 30) {
                wait();
            }
        }
        synchronized void decOut() {
            outstanding--;
            notifyAll();
        }

        public void process(WatcherEvent event) {
        }

        public void processResult(int rc, String path, Object ctx, String name) {
            try {
                decOut();
                zk.delete(path, -1, this, null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void processResult(int rc, String path, Object ctx) {
        }
    }
    
    @Test
    public void testHammer() throws Exception {
        Thread.sleep(1000);
        bang = true;
        for(int i = 0; i < 100; i++) {
            new HammerThread().start();
        }
        Thread.sleep(5000);
        tearDown();
        bang = false;
        restart();
        Thread.sleep(5000);
        String parts[] = hostPort.split(",");
        String prevList[] = null;
        for(String hp: parts) {
            ZooKeeper zk = new ZooKeeper(hp, 30000, this);
            String list[] = zk.getChildren("/", false).toArray(new String[0]);
            if (prevList != null) {
                assertEquals(prevList.length, list.length);
                for(int i = 0; i < list.length; i++) {
                    assertEquals(prevList[i], list[i]);
                }
            }
            prevList = list;
        }
    }
    
    LinkedList<Integer> results = new LinkedList<Integer>();
    @Test
    public void testAsync() throws KeeperException, IOException,
            InterruptedException {
        ZooKeeper zk = null;
            zk = new ZooKeeper(hostPort, 30000, this);
            zk.addAuthInfo("digest", "ben:passwd".getBytes());
            zk.create("/ben", new byte[0], Ids.READ_ACL_UNSAFE, 0, this, results);
            zk.create("/ben/2", new byte[0], Ids.CREATOR_ALL_ACL, 0, this, results);
            zk.delete("/ben", -1, this, results);
            zk.create("/ben2", new byte[0], Ids.CREATOR_ALL_ACL, 0, this, results);
            zk.getData("/ben2", false, this, results);
            synchronized(results) {
                while(results.size() < 5) {
                    results.wait();
                }
            }
            assertEquals(0, (int)results.get(0));
            assertEquals(Code.NoAuth, (int)results.get(1));
            assertEquals(0, (int)results.get(2));
            assertEquals(0, (int)results.get(3));
            assertEquals(0, (int)results.get(4));
            zk.close();

            zk = new ZooKeeper(hostPort, 30000, this);
            zk.addAuthInfo("digest", "ben:passwd2".getBytes());
            try {
                zk.getData("/ben2", false, new Stat());
                fail("Should have received a permission error");
            } catch(KeeperException e) {
                assertEquals(Code.NoAuth, e.getCode());
            }
            zk.close();

            zk = new ZooKeeper(hostPort, 30000, this);
            zk.addAuthInfo("digest", "ben:passwd".getBytes());
            zk.getData("/ben2", false, new Stat());
            zk.close();

    }

    public void process(WatcherEvent event) {
        // TODO Auto-generated method stub
        
    }

    public void processResult(int rc, String path, Object ctx, String name) {
        ((LinkedList<Integer>)ctx).add(rc);
        synchronized(ctx) {
            ctx.notifyAll();
        }
    }

    public void processResult(int rc, String path, Object ctx) {
        ((LinkedList<Integer>)ctx).add(rc);
        synchronized(ctx) {
            ctx.notifyAll();
        }
    }

    public void processResult(int rc, String path, Object ctx, byte[] data,
            Stat stat) {
        ((LinkedList<Integer>)ctx).add(rc);
        synchronized(ctx) {
            ctx.notifyAll();
        }
    }
}
