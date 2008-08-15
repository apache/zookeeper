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
package org.apache.zookeeper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.WatcherEvent;

/**
 * The command line client to ZooKeeper.
 * 
 */
public class ZooKeeperMain {

    static void usage() {
        System.err.println("ZooKeeper host:port cmd args");
        System.err.println("\tcreate path data acl");
        System.err.println("\tdelete path [version]");
        System.err.println("\tset path data [version]");
        System.err.println("\tget path [watch]");
        System.err.println("\tls path [watch]");
        System.err.println("\tgetAcl path");
        System.err.println("\tsetAcl path acl");
        System.err.println("\tstat path [watch]");
        System.err.println("\tsync path");
    }

    static private class MyWatcher implements Watcher {
        public void process(WatcherEvent event) {
            System.err.println(event.getPath() + ": " + event.getState() + "-"
                    + event.getType());
        }
    }

    static private int getPermFromString(String permString) {
        int perm = 0;
        for (int i = 0; i < permString.length(); i++) {
            switch (permString.charAt(i)) {
            case 'r':
                perm |= ZooDefs.Perms.READ;
                break;
            case 'w':
                perm |= ZooDefs.Perms.WRITE;
                break;
            case 'c':
                perm |= ZooDefs.Perms.CREATE;
                break;
            case 'd':
                perm |= ZooDefs.Perms.DELETE;
                break;
            case 'a':
                perm |= ZooDefs.Perms.ADMIN;
                break;
            default:
                System.err
                        .println("Unknown perm type: " + permString.charAt(i));
            }
        }
        return perm;
    }

    private static void printStat(Stat stat) {
        System.err.println("ctime = " + new Date(stat.getCtime()).toString());
        System.err.println("ctime = " + new Date(stat.getMtime()).toString());
        System.err.println("cversion = " + stat.getCversion());
        System.err.println("cZxid = " + stat.getCzxid());
        System.err.println("mZxid = " + stat.getMzxid());
        System.err.println("dataVersion = " + stat.getVersion());
        System.err.println("aclVersion = " + stat.getAversion());
    }

    public static void main(String args[]) throws NumberFormatException,
            KeeperException, IOException, InterruptedException {
        if (args.length == 1) {
            ZooKeeper zooKeeper = new ZooKeeper(args[0], 5000, new MyWatcher());
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    System.in));
            String line;
            while ((line = br.readLine()) != null) {
                line = "ignore " + line;
                args = line.split(" ");
                processCmd(args, zooKeeper);
            }
        } else if (args.length < 3) {
            usage();
        }

        ZooKeeper zooKeeper = new ZooKeeper(args[0], 5000, new MyWatcher());
        boolean watch = processCmd(args, zooKeeper);
        if (!watch) {
            System.exit(0);
        }
    }

    private static DataCallback dataCallback = new DataCallback() {

        public void processResult(int rc, String path, Object ctx, byte[] data,
                Stat stat) {
            System.out.println("rc = " + rc + " path = " + path + " data = "
                    + (data == null ? "null" : new String(data)) + " stat = ");
            printStat(stat);
        }

    };

    private static boolean processCmd(String[] args, ZooKeeper zooKeeper)
            throws KeeperException, IOException, InterruptedException {
        Stat stat = new Stat();
        if (args.length < 2) {
            return false;
        }
        if (args.length < 3) {
            usage();
            return false;
        }
        String cmd = args[1];
        boolean watch = args.length > 3;
        String path = args[2];
        List<ACL> acl = Ids.OPEN_ACL_UNSAFE;
        System.out.println("Processing " + cmd);
        if (cmd.equals("create") && args.length >= 4) {
            if (args.length == 5) {
                acl = parseACLs(args[4]);
            }
            String newPath = zooKeeper.create(path, args[3].getBytes(), acl, 0);
            System.err.println("Created " + newPath);
        } else if (cmd.equals("delete") && args.length >= 3) {
            zooKeeper.delete(path, watch ? Integer.parseInt(args[3]) : -1);
        } else if (cmd.equals("set") && args.length >= 4) {
            stat = zooKeeper.setData(path, args[3].getBytes(),
                    args.length > 4 ? Integer.parseInt(args[4]) : -1);
            printStat(stat);
        } else if (cmd.equals("aget") && args.length >= 3) {
            zooKeeper.getData(path, watch, dataCallback, path);
        } else if (cmd.equals("get") && args.length >= 3) {
            byte data[] = zooKeeper.getData(path, watch, stat);
            System.out.println(new String(data));
            printStat(stat);
        } else if (cmd.equals("ls") && args.length >= 3) {
            List<String> children = zooKeeper.getChildren(path, watch);
            System.out.println(children);
        } else if (cmd.equals("getAcl") && args.length >= 2) {
            acl = zooKeeper.getACL(path, stat);
            for (ACL a : acl) {
                System.out.println(a.getId() + ": "
                        + getPermString(a.getPerms()));
            }
        } else if (cmd.equals("setAcl") && args.length >= 4) {

            stat = zooKeeper.setACL(path, parseACLs(args[3]),
                    args.length > 4 ? Integer.parseInt(args[4]) : -1);
            printStat(stat);
        } else if (cmd.equals("stat") && args.length >= 3) {
            stat = zooKeeper.exists(path, watch);
            printStat(stat);
        } else {
            usage();
        }
        return watch;
    }

    private static String getPermString(int perms) {
        StringBuffer p = new StringBuffer();
        if ((perms & ZooDefs.Perms.CREATE) != 0) {
            p.append('c');
        }
        if ((perms & ZooDefs.Perms.DELETE) != 0) {
            p.append('d');
        }
        if ((perms & ZooDefs.Perms.READ) != 0) {
            p.append('r');
        }
        if ((perms & ZooDefs.Perms.WRITE) != 0) {
            p.append('w');
        }
        if ((perms & ZooDefs.Perms.ADMIN) != 0) {
            p.append('a');
        }
        return p.toString();
    }

    private static List<ACL> parseACLs(String aclString) {
        List<ACL> acl;
        String acls[] = aclString.split(",");
        acl = new ArrayList<ACL>();
        for (String a : acls) {
            int firstColon = a.indexOf(':');
            int lastColon = a.lastIndexOf(':');
            if (firstColon == -1 || lastColon == -1 || firstColon == lastColon) {
                System.err
                        .println(a + " does not have the form scheme:id:perm");
                continue;
            }
            ACL newAcl = new ACL();
            newAcl.setId(new Id(a.substring(0, firstColon), a.substring(
                    firstColon + 1, lastColon)));
            newAcl.setPerms(getPermFromString(a.substring(lastColon + 1)));
            acl.add(newAcl);
        }
        return acl;
    }

}
