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
import java.util.Date;
import java.util.HashMap;

import com.yahoo.zookeeper.KeeperException;
import com.yahoo.zookeeper.Watcher;
import com.yahoo.zookeeper.ZooDefs;
import com.yahoo.zookeeper.ZooKeeper;
import com.yahoo.zookeeper.AsyncCallback.DataCallback;
import com.yahoo.zookeeper.AsyncCallback.StatCallback;
import com.yahoo.zookeeper.data.Stat;
import com.yahoo.zookeeper.proto.WatcherEvent;
import com.yahoo.zookeeper.server.ZooLog;

public class IntegrityCheck implements Watcher, StatCallback, DataCallback {
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

    IntegrityCheck(String hostPort, String path, int count) throws KeeperException,
            IOException {
        zk = new ZooKeeper(hostPort, 15000, this);
        this.path = path;
        this.count = count;
    }

    public void run() throws InterruptedException, KeeperException {
        try{
            ZooLog.logWarn("Creating znodes for "+path);
            doCreate();
            ZooLog.logWarn("Staring the test loop for "+path);
            while (true) {
                ZooLog.logWarn("Staring write cycle for "+path);
                doPopulate();
                waitOutstanding();
                ZooLog.logWarn("Staring read cycle for "+path);
                readAll();
                waitOutstanding();
            }
        }finally{
            ZooLog.logWarn("Test loop terminated for "+path);            
        }
    }

    void readAll() {
        for (int i = 0; i < count; i++) {
            String cpath = path + "/" + i;
            zk.getData(cpath, false, this, null);
            incOutstanding();
        }

    }

    void doCreate() throws KeeperException, InterruptedException {
        // create top level znode
        try{
            zk.create(path, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, 0);
        }catch(KeeperException e){
            if(e.getCode()!=KeeperException.Code.NodeExists)
                throw e;
        }
        iteration++;
        byte v[] = ("" + iteration).getBytes();
        // create child znodes
        for (int i = 0; i < count; i++) {
            String cpath = path + "/" + i;
            try{
                if(i%10==0)
                    ZooLog.logWarn("Creating znode "+cpath);
                zk.create(cpath, v, ZooDefs.Ids.OPEN_ACL_UNSAFE, 0);
            }catch(KeeperException e){
                if(e.getCode()!=KeeperException.Code.NodeExists)
                    throw e;
            }               
            lastValue.put(cpath, v);
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

    // watcher callback
    public void process(WatcherEvent event) {
        if(event.getState()==Event.KeeperStateSyncConnected){
            synchronized(this){
                notifyAll();
            }
        }
    }

    synchronized void ensureConnected(){
        while(zk.getState()!=ZooKeeper.States.CONNECTED){
            try {
                wait();
            } catch (InterruptedException e) {
                return;
            }
        }
    }
    
    /**
     * @param args
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("USAGE: IntegrityCheck zookeeperHostPort znode #children");
            return;
        }
        int childrenCount=0;
        try {
            childrenCount=Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            e.printStackTrace();
            System.exit(1);
        }

        try{
            final IntegrityCheck ctest = new IntegrityCheck(args[0], args[1],childrenCount);
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    System.out.println(new Date().toString()+": Error count = " + ctest.errorCount);
                }
            });
            while(true){
                try{
                    ctest.ensureConnected();
                    ctest.run();
                } catch (Exception e) {
                    e.printStackTrace();
                }                
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
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
                ZooLog.logError("ERROR: Got " + string + " expected >= "
                        + lastString);
                errorCount++;
            }
            lastValue.put(path, (byte[]) ctx);
        }
        decOutstanding();
    }
}
