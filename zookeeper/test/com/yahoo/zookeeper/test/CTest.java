package com.yahoo.zookeeper.test;

/**
 * This is a simple test to check the integrity of ZooKeeper servers. The client
 * simply cycles through blasting changes to ZooKeeper and the checking what it
 * gets back.
 * 
 * The check is very simple. The value of the last successful read or write is
 * stored in lastValue. When we issue a request, that value becomes a possible
 * value. The difficulty is that when a communication error happens, the client
 * doesn't know if the set actually went through. So, our invariant that we
 * check for is that we always read a value that is greater than or equal to
 * a value that we have previously read or set. (Each time we set a value, the
 * value will be one more than the previous set.)
 */
import java.io.IOException;
import java.util.HashMap;

import com.yahoo.zookeeper.KeeperException;
import com.yahoo.zookeeper.Watcher;
import com.yahoo.zookeeper.ZooDefs;
import com.yahoo.zookeeper.ZooKeeper;
import com.yahoo.zookeeper.AsyncCallback.DataCallback;
import com.yahoo.zookeeper.AsyncCallback.StatCallback;
import com.yahoo.zookeeper.data.Stat;
import com.yahoo.zookeeper.proto.WatcherEvent;

public class CTest implements Watcher, StatCallback, DataCallback {
    ZooKeeper zk;

    HashMap<String, byte[]> lastValue = new HashMap<String, byte[]>();

    int count;

    String path;

    int iteration;

    int outstanding;

    int errorCount;

    synchronized void incOutstanding() {
        outstanding++;
    }

    synchronized void decOutstanding() {
        outstanding--;
        notifyAll();
    }

    synchronized void waitOutstanding() throws InterruptedException {
        while (outstanding > 0) {
            wait();
        }
    }

    CTest(String hostPort, String path, int count) throws KeeperException,
            IOException {
        zk = new ZooKeeper(hostPort, 15000, this);
        this.path = path;
        this.count = count;
    }

    public void run() throws InterruptedException {
        doCreate();
        while (true) {
            doPopulate();
            waitOutstanding();
            Thread.sleep(2000);
            readAll();
            waitOutstanding();
            Thread.sleep(1000);
        }
    }

    void readAll() {
        for (int i = 0; i < count; i++) {
            String cpath = path + "/" + i;
            zk.getData(cpath, false, this, null);
            incOutstanding();
        }

    }

    void doCreate() {
        iteration++;
        byte v[] = ("" + iteration).getBytes();
        for (int i = 0; i < count; i++) {
            String cpath = path + "/" + i;
            try {
                zk.create(cpath, v, ZooDefs.Ids.OPEN_ACL_UNSAFE, 0);
                lastValue.put(cpath, v);
            } catch (KeeperException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    void doPopulate() {
        iteration++;
        byte v[] = ("" + iteration).getBytes();
        for (int i = 0; i < count; i++) {
            String cpath = path + "/" + i;
            zk.setData(cpath, v, -1, this, v);
            incOutstanding();
        }
    }

    /**
     * @param args
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err
                    .println("USAGE: CTest zookeeperHostPort znode #children");
            return;
        }
        try {
            final CTest ctest = new CTest(args[0], args[1], Integer
                    .parseInt(args[2]));
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    System.out.println("Error count = " + ctest.errorCount);
                }
            });
            ctest.run();
        } catch (NumberFormatException e) {
            e.printStackTrace();
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void process(WatcherEvent event) {
    }

    @Override
    public void processResult(int rc, String path, Object ctx, Stat stat) {
        if (rc == 0) {
            lastValue.put(path, (byte[]) ctx);
        }
        decOutstanding();
    }

    @Override
    public void processResult(int rc, String path, Object ctx, byte[] data,
            Stat stat) {
        if (rc == 0) {
            String string = new String(data);
            String lastString = null;
            byte[] v = lastValue.get(path);
            if (v != null) {
                lastString = new String(v);
            }
            if (lastString != null
                    && Integer.parseInt(string) < Integer.parseInt(lastString)) {
                System.err.println("ERROR: Got " + string + " expected >= "
                        + lastString);
                errorCount++;
            }
            lastValue.put(path, (byte[]) ctx);
        }
        decOutstanding();
    }
}
