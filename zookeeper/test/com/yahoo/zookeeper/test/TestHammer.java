package com.yahoo.zookeeper.test;

import java.io.IOException;

import com.yahoo.zookeeper.KeeperException;
import com.yahoo.zookeeper.ZooKeeper;
import com.yahoo.zookeeper.AsyncCallback.VoidCallback;
import com.yahoo.zookeeper.ZooDefs.CreateFlags;
import com.yahoo.zookeeper.ZooDefs.Ids;

public class TestHammer implements VoidCallback {

    /**
     * @param args
     */
    static int REPS = 50000;
    public static void main(String[] args) {
            long startTime = System.currentTimeMillis();
            ZooKeeper zk = null;
            try {
                zk = new ZooKeeper(args[0], 10000, null);
            } catch (KeeperException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            } catch (IOException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            }
            for(int i = 0; i < REPS; i++) {
                try {
                    String name = zk.create("/testFile-", new byte[16], Ids.OPEN_ACL_UNSAFE,
                        CreateFlags.EPHEMERAL|CreateFlags.SEQUENCE);
                    zk.delete(name, -1, new TestHammer(), null);
                } catch(Exception e) {
                    i--;
                    e.printStackTrace();
                }
            }
            System.out.println("creates/sec=" + (REPS*1000/(System.currentTimeMillis()-startTime)));
    }

    public void processResult(int rc, String path, Object ctx) {
        // TODO Auto-generated method stub
        
    }

}
