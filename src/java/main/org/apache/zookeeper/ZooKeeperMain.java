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

import org.apache.log4j.Logger;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;

/**
 * The command line client to ZooKeeper.
 * 
 */
public class ZooKeeperMain {
    /**
     * the logger for this class
     */
    private static final Logger LOG = Logger.getLogger(ZooKeeperMain.class);
    
    static void usage() {
        LOG.info("message", new IOException("USAGE"));
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
        System.err.println("\tsetquota -n|-b val path");
        System.err.println("\tlistquota path");
        System.err.println("\tdelquotsssa [-n|-b] path");
        }

    static private class MyWatcher implements Watcher {
        public void process(WatchedEvent event) {
            System.err.println(event);
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
        System.err.println("cZxid = " + stat.getCzxid());
        System.err.println("ctime = " + new Date(stat.getCtime()).toString());
        System.err.println("mZxid = " + stat.getMzxid());
        System.err.println("mtime = " + new Date(stat.getMtime()).toString());
        System.err.println("pZxid = " + stat.getPzxid());
        System.err.println("cversion = " + stat.getCversion());
        System.err.println("dataVersion = " + stat.getVersion());
        System.err.println("aclVersion = " + stat.getAversion());
        System.err.println("ephemeralOwner = " + stat.getEphemeralOwner());
        System.err.println("dataLength = " + stat.getDataLength());
        System.err.println("numChildren = " + stat.getNumChildren());
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
            System.exit(-1);
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
    
    /**
     * trim the quota tree to recover unwanted tree elements
     * in the quota's tree
     * @param zk the zookeeper client
     * @param path the path to start from and go up and see if their
     * is any unwanted parent in the path.
     * @return true if sucessful
     * @throws KeeperException
     * @throws IOException
     * @throws InterruptedException
     */
    private static boolean trimProcQuotas(ZooKeeper zk, String path) throws
            KeeperException, IOException, InterruptedException {
        if (Quotas.quotaZookeeper.equals(path)) {
            return true;
        }
        List<String> children = zk.getChildren(path, false);
        if (children.size() == 0) {
            zk.delete(path, -1);
            String parent = path.substring(0, path.lastIndexOf('/'));
            return trimProcQuotas(zk, parent);
        }
        else {
            return true;
        }
    }

    /**
     * this method deletes quota for a node.
     * @param zk the zookeeper client
     * @param path the path to delete quota for
     * @param bytes true if number of bytes needs to
     * be unset
     * @param numNodes true if number of nodes needs 
     * to be unset
     * @return true if quota deletion is successful
     * @throws KeeperException
     * @throws IOException
     * @throws InterruptedException
     */
    public static boolean delQuota(ZooKeeper zk, String path,
            boolean bytes, boolean numNodes) throws KeeperException,
            IOException, InterruptedException {
        String parentPath = Quotas.quotaZookeeper + path;
        String quotaPath = Quotas.quotaZookeeper + path + "/" + Quotas.limitNode;
        if (zk.exists(quotaPath, false) == null) {
            System.out.println("Quota does not exist for " + path);
            return true;
        }
        byte[] data = null;
        try {
           data = zk.getData(quotaPath, false, new Stat());
        } catch(KeeperException.NoNodeException ne) {
            System.err.println("quota does not exist for " + path);
        }
        StatsTrack strack = new StatsTrack(new String(data));
        if (bytes && !numNodes) {
            strack.setBytes(-1L);
            zk.setData(quotaPath, strack.toString().getBytes(), -1);
        }
        else if (!bytes && numNodes) {
            strack.setCount(-1);
            zk.setData(quotaPath, strack.toString().getBytes(), -1);
        }
        else if (bytes && numNodes) {
            // delete till you can find a node with more than
            // one child
            List<String> children = zk.getChildren(parentPath, false);
            /// delete the direct children first
            for (String child: children) {
                zk.delete(parentPath + "/" + child, -1);
            }
            // cut the tree till their is more than one child
            trimProcQuotas(zk, parentPath);
        }
        return true;
    }
    
    private static void checkIfParentQuota(ZooKeeper zk, String path)
        throws InterruptedException, KeeperException
    {
        final String[] splits = path.split("/");
        String quotaPath = Quotas.quotaZookeeper;
        for (String str: splits) {
            if (str.length() == 0) {
                // this should only be for the beginning of the path
                // i.e. "/..." - split(path)[0] is empty string before first '/'
                continue;
            }
            quotaPath += "/" + str;
            List<String> children =  null;
            try {
                children = zk.getChildren(quotaPath, false);
            } catch(KeeperException.NoNodeException ne) {
                return;
            }
            if (children.size() == 0) {
                return;
            }
            for (String child: children) {
                if (Quotas.limitNode.equals(child)) {
                    throw new IllegalArgumentException(path + " has a parent " 
                            + quotaPath + " which has a quota");
                }
            }
        }
    }
        
    /**
     * this method creates a quota node for the path
     * @param zk the ZooKeeper client
     * @param path the path for which quota needs to be created
     * @param bytes the limit of bytes on this path
     * @param numNodes the limit of number of nodes on this path
     * @return true if its successful and false if not.
     */
    public static boolean createQuota(ZooKeeper zk, String path, 
            long bytes, int numNodes) throws KeeperException, IOException,
            InterruptedException {
        // check if the path exists. We cannot create 
        // quota for a path that already exists in zookeeper
        // for now.
        Stat initStat = zk.exists(path, false);
        if (initStat == null) {
            throw new IllegalArgumentException(path + " does not exist.");
        }
        // now check if their is already existing 
        // parent or child that has quota
        
        String quotaPath = Quotas.quotaZookeeper;
        // check for more than 2 children -- 
        // if zookeeper_stats and zookeeper_qutoas
        // are not the children then this path 
        // is an ancestor of some path that 
        // already has quota
        String realPath = Quotas.quotaZookeeper + path;
        try {
           List<String> children = zk.getChildren(realPath, false);
           for (String child: children) {
               if (!child.startsWith("zookeeper_")) {
                   throw new IllegalArgumentException(path + " has child " + 
                           child + " which has a quota");
               }
           }
        } catch(KeeperException.NoNodeException ne) {
            //this is fine
            // we can proceed further
        }
        
        //check for any parent that has been quota
        checkIfParentQuota(zk, path);
        
        // this is valid node for quota
        // start creating all the parents
        if (zk.exists(quotaPath, false) == null) {
            try {
                zk.create(Quotas.procZookeeper, null, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
                zk.create(Quotas.quotaZookeeper, null, Ids.OPEN_ACL_UNSAFE, 
                        CreateMode.PERSISTENT);
            } catch(KeeperException.NodeExistsException ne) {
                // do nothing
            }
        }
        
        // now create the direct children 
        // and the stat and quota nodes
        String[] splits = path.split("/");
        for (int i=1; i<splits.length; i++) {
            quotaPath = quotaPath + "/" + splits[i];
            try {
                zk.create(quotaPath, null, Ids.OPEN_ACL_UNSAFE , 
                        CreateMode.PERSISTENT);
            } catch(KeeperException.NodeExistsException ne) {
                //do nothing
            }
        }
        String statPath = quotaPath + "/" + Quotas.statNode;
        quotaPath = quotaPath + "/" + Quotas.limitNode;
        StatsTrack strack = new StatsTrack(null);
        strack.setBytes(bytes);
        strack.setCount(numNodes);
        try {
            zk.create(quotaPath, strack.toString().getBytes(), 
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            StatsTrack stats = new StatsTrack(null);
            stats.setBytes(0L);
            stats.setCount(0);
            zk.create(statPath, stats.toString().getBytes(), 
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch(KeeperException.NodeExistsException ne) {
            byte[] data = zk.getData(quotaPath, false , new Stat());
            StatsTrack strackC = new StatsTrack(new String(data));
            if (bytes != -1L) {
                strackC.setBytes(bytes);
            }
            if (numNodes != -1) {
                strackC.setCount(numNodes);
            }
            zk.setData(quotaPath, strackC.toString().getBytes(), -1);
        }
        return true;
    }
   
    private static boolean processCmd(String[] args, ZooKeeper zooKeeper)
            throws KeeperException, IOException, InterruptedException {
        Stat stat = new Stat();
        if (args.length < 2) {
            usage();
            return false;
        }
        
        String cmd = args[1];
        boolean watch = args.length > 3;
        String path = null;
        List<ACL> acl = Ids.OPEN_ACL_UNSAFE;
        System.out.println("Processing " + cmd);
        if (cmd.equals("create") && args.length >= 4) {
            if (args.length == 5) {
                acl = parseACLs(args[4]);
            }
            path = args[2];
            String newPath = zooKeeper.create(path, args[3].getBytes(), acl, CreateMode.PERSISTENT);
            System.err.println("Created " + newPath);
        } else if (cmd.equals("delete") && args.length >= 3) {
            path = args[2];
            zooKeeper.delete(path, watch ? Integer.parseInt(args[3]) : -1);
        } else if (cmd.equals("set") && args.length >= 4) {
            path = args[2];
            stat = zooKeeper.setData(path, args[3].getBytes(),
                    args.length > 4 ? Integer.parseInt(args[4]) : -1);
            printStat(stat);
        } else if (cmd.equals("aget") && args.length >= 3) {
            path = args[2];
            zooKeeper.getData(path, watch, dataCallback, path);
        } else if (cmd.equals("get") && args.length >= 3) {
            path = args[2];
            byte data[] = zooKeeper.getData(path, watch, stat);
            System.out.println(new String(data));
            printStat(stat);
        } else if (cmd.equals("ls") && args.length >= 3) {
            path = args[2];
            List<String> children = zooKeeper.getChildren(path, watch);
            System.out.println(children);
        } else if (cmd.equals("getAcl") && args.length >= 2) {
            path = args[2];
            acl = zooKeeper.getACL(path, stat);
            for (ACL a : acl) {
                System.out.println(a.getId() + ": "
                        + getPermString(a.getPerms()));
            }
        } else if (cmd.equals("setAcl") && args.length >= 4) {
            path = args[2];
            stat = zooKeeper.setACL(path, parseACLs(args[3]),
                    args.length > 4 ? Integer.parseInt(args[4]) : -1);
            printStat(stat);
        } else if (cmd.equals("stat") && args.length >= 3) {
            path = args[2];
            stat = zooKeeper.exists(path, watch);
            printStat(stat);
        } else if (cmd.equals("listquota") && args.length >= 3) {
            path = args[2];
            String absolutePath = Quotas.quotaZookeeper + path + "/" + Quotas.limitNode;
            byte[] data =  null;
            try {   
                System.err.println("absolute path is " + absolutePath);
                data = zooKeeper.getData(absolutePath, false, stat);
                StatsTrack st = new StatsTrack(new String(data));
                System.out.println("Output quota for " + path + " " 
                        + st.toString());
                
                data = zooKeeper.getData(Quotas.quotaZookeeper + path + "/" +
                        Quotas.statNode, false, stat);
                System.out.println("Output stat for " + path + " " +
                            new StatsTrack(new String(data)).toString());
            } catch(KeeperException.NoNodeException ne) {
                System.err.println("quota for " + path + " does not exist.");
            }
            
        } else if (cmd.equals("setquota") && args.length > 4) {
            String option = args[2];
            path = args[4];
            System.err.println("Comment: the parts are " +
            		"option " + option + " path " + 
            		args[4] + " val " + args[3]);
            String val = args[3];
            if ("-b".equals(option)) {
                // we are setting the bytes quota
                createQuota(zooKeeper, path, Long.parseLong(val), -1);
            } else if ("-n".equals(option)) {
                // we are setting the num quota
                createQuota(zooKeeper, path, -1L, Integer.parseInt(val));
            }
            else {
                usage();
            }
        } else if (cmd.equals("delquota") && args.length >= 3) {
            //if neither option -n or -b is specified, we delete 
            // the quota node for thsi node.
            if (args.length == 4) {
                //this time we have an option
                String option = args[2];
                path = args[3];
                if ("-b".equals(option)) {
                    delQuota(zooKeeper, path, true, false);
                } else if ("-n".equals(option)) {
                    delQuota(zooKeeper, path, false, true);
                }
            }
            else if (args.length == 3) {
                path = args[2];
                // we dont have an option specified.
                // just delete whole quota node
                delQuota(zooKeeper, path, true, true);
            }
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
