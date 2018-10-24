/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;

public class SledgeHammer extends Thread implements Watcher {
    ZooKeeper zk;

    int count;

    int readsPerWrite;

    public SledgeHammer(String hosts, int count, int readsPerWrite)
            throws IOException {
        zk = new ZooKeeper(hosts, 10000, this);
        this.count = count;
        this.readsPerWrite = readsPerWrite;
    }

    public void run() {
        try {
            Stat stat = new Stat();
            String path = zk.create("/hammers/hammer-", new byte[0],
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
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
                    List<String> childs =
                        zk.getChildren("/hammers", false);
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
                } catch (KeeperException.ConnectionLossException e) {
                    // ignore connection loss
                } catch (KeeperException e) {
                    e.printStackTrace();
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
            IOException {
        if (args.length != 3) {
            System.err
                    .println("USAGE: SledgeHammer zookeeper_server reps reads_per_rep");
            System.exit(3);
        }
        SledgeHammer h = new SledgeHammer(args[0], Integer.parseInt(args[1]),
                Integer.parseInt(args[2]));
        h.start();
        System.exit(0);
    }

    public void process(WatchedEvent event) {
        synchronized (this) {
            notifyAll();
        }
    }

}
