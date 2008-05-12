package com.yahoo.zookeeper.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import com.yahoo.zookeeper.KeeperException;
import com.yahoo.zookeeper.Watcher;
import com.yahoo.zookeeper.ZooKeeper;
import com.yahoo.zookeeper.ZooDefs.CreateFlags;
import com.yahoo.zookeeper.ZooDefs.Ids;
import com.yahoo.zookeeper.data.Stat;
import com.yahoo.zookeeper.proto.WatcherEvent;

public class SledgeHammer extends Thread implements Watcher {
    ZooKeeper zk;

    int count;

    int readsPerWrite;

    public SledgeHammer(String hosts, int count, int readsPerWrite)
            throws KeeperException, IOException {
        zk = new ZooKeeper(hosts, 10000, this);
        this.count = count;
        this.readsPerWrite = readsPerWrite;
    }

    public void run() {
        try {
            Stat stat = new Stat();
            String path = zk.create("/hammers/hammer-", new byte[0],
                    Ids.OPEN_ACL_UNSAFE, CreateFlags.SEQUENCE);
            byte tag[] = (path + " was here!").getBytes();
            synchronized (this) {
                String startPath = "/hammers/start";
                System.out.println("Waiting for " + startPath);
                while (zk.exists(startPath, true) == null) {
                    wait();
                }
                System.out.println("Running");
            }
            for (int i = 0; i < count; i++) {
                try {
                    System.out.print(i + "\r");
                    ArrayList<String> childs = zk
                            .getChildren("/hammers", false);
                    Collections.shuffle(childs);
                    for (String s : childs) {
                        if (s.startsWith("hammer-")) {
                            s = "/hammers/" + s;
                            zk.setData(s, tag, -1);
                            for (int j = 0; j < readsPerWrite; j++) {
                                zk.getData(s, false, stat);
                            }
                            break;
                        }
                    }
                } catch (KeeperException e) {
                    if (e.getCode() != KeeperException.Code.ConnectionLoss) {
                        e.printStackTrace();
                    }
                }
            }
            System.out.println();
            zk.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * @param args
     * @throws IOException
     * @throws KeeperException
     * @throws NumberFormatException
     */
    public static void main(String[] args) throws NumberFormatException,
            KeeperException, IOException {
        if (args.length != 3) {
            System.err
                    .println("USAGE: SledgeHammer zookeeper_server reps reads_per_rep");
            System.exit(3);
        }
        SledgeHammer h = new SledgeHammer(args[0], Integer.parseInt(args[1]),
                Integer.parseInt(args[2]));
        h.run();
        System.exit(0);
    }

    public void process(WatcherEvent event) {
        synchronized (this) {
            notifyAll();
        }
    }

}
