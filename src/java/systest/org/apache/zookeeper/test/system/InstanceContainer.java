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

package org.apache.zookeeper.test.system;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.system.Instance.Reporter;

/**
 * This class starts up, 
 */
public class InstanceContainer implements Watcher, AsyncCallback.ChildrenCallback {
    private final class MyWatcher implements Watcher {
        String myNode;
        DataCallback dc;
        MyWatcher(String myNode, DataCallback dc) {
            this.myNode = myNode;
            this.dc = dc;
        }
        public void process(WatchedEvent event) {
            if (event.getPath() != null && event.getPath().equals(myNode)) {
                zk.getData(myNode, this, dc, this);
            }
        }
    }
    private final class MyDataCallback implements DataCallback {
        int lastVer;
        String myNode;
        Instance myInstance;

        MyDataCallback(String myNode, Instance myInstance, int ver) {
            this.myNode = myNode;
            this.myInstance = myInstance;
            lastVer = ver;
        }
        public void processResult(int rc, String path,
                Object ctx, byte[] data, Stat stat) {
            if (rc == KeeperException.Code.NONODE.intValue()) {
                // we can just ignore because the child watcher takes care of this
                return;
            }
            if (rc != KeeperException.Code.OK.intValue()) {
                zk.getData(myNode, (Watcher)ctx, this, ctx);
            }
            int currVer = stat.getVersion();
            if (currVer != lastVer) {
                String parts[] = new String(data).split(" ", 2);
                myInstance.configure(parts[1]);
                lastVer = currVer;
            }
        }
    }
    private final class MyReporter implements Reporter {
        String myReportNode;

        public MyReporter(String child) {
            myReportNode = reportsNode + '/' + child;
        }

        public void report(String report) throws KeeperException, InterruptedException {
            for(int j = 0; j < maxTries; j++) {
                try {
                    try {
                        zk.setData(myReportNode, report.getBytes(), -1);
                    } catch(NoNodeException e) {
                        zk.create(myReportNode, report.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                    }
                    break;
                } catch(ConnectionLossException e) {}
            }
        }
    }
    private static final Logger LOG = LoggerFactory.getLogger(InstanceContainer.class); 
    String name;
    String zkHostPort;
    // We only run if the readyNode exists
    String prefixNode;
    String statusNode = "available";
    String reportsNode = "reports";
    String assignmentsNode = "assignments";
    ZooKeeper zk;
    static final int sessTimeout = 5000;
    static final int maxTries = 3;
    public InstanceContainer(String name, String zkHostPort, String prefix) throws UnknownHostException {
        if (name.length() == 0 || name.equals("hostname")) {
            name = InetAddress.getLocalHost().getCanonicalHostName();
        }
        this.name = name;
        this.zkHostPort = zkHostPort;
        this.prefixNode = prefix;
        this.statusNode = prefix + '/' + this.statusNode + '/' + name;
        this.reportsNode = prefix + '/' + this.reportsNode;
        this.assignmentsNode = prefix + '/' + this.assignmentsNode + '/' + name;
    }
    
    private void rmnod(String path) throws InterruptedException, KeeperException {
        KeeperException lastException = null;
        for(int i = 0; i < maxTries; i++) {
            try {
                zk.delete(path, -1);
                lastException = null;
                break;
            } catch (KeeperException.NoNodeException e) {
                // cool this is what we want
                break;
            } catch (KeeperException e) {
                lastException = e;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
    }
    private void mknod_inner(String path, CreateMode mode) throws KeeperException, InterruptedException {
        for(int i = 0; i < maxTries; i++) {
            try {
                zk.create(path, null, Ids.OPEN_ACL_UNSAFE, mode);
                break;
            } catch (NodeExistsException e) {
                if (mode != CreateMode.EPHEMERAL) {
                    return;
                }
                Stat stat = zk.exists(path, false);
                if (stat == null) {
                    continue;
                }
                if (stat.getEphemeralOwner() != zk.getSessionId()) {
                    throw e;
                }
                break;
            } catch (ConnectionLossException e) {
                e.printStackTrace();
            }
        }
    }
    
    private void mknod(String path, CreateMode mode) throws KeeperException, InterruptedException {
        String subpath[] = path.split("/");
        StringBuilder sb = new StringBuilder();
        // We start at 1 because / will create an empty part first
        for(int i = 1; i < subpath.length; i++) {
            sb.append("/");
            sb.append(subpath[i]);
            CreateMode m = CreateMode.PERSISTENT;
            if (i == subpath.length-1) {
                m = mode;
            }
            mknod_inner(sb.toString(), m);
        }
    }
    
    public void run() throws IOException, InterruptedException, KeeperException {
        zk = new ZooKeeper(zkHostPort, sessTimeout, this);
        mknod(assignmentsNode, CreateMode.PERSISTENT);
        mknod(statusNode, CreateMode.EPHEMERAL);
        mknod(reportsNode, CreateMode.PERSISTENT);
        // Now we just start watching the assignments directory
        zk.getChildren(assignmentsNode, true, this, null);
    }
    
    /**
     * @param args the first parameter is the instance name, the second
     * is the ZooKeeper spec. if the instance name is the empty string
     * or "hostname", the hostname will be used.
     * @throws InterruptedException 
     * @throws IOException 
     * @throws UnknownHostException 
     * @throws KeeperException 
     */
    public static void main(String[] args) throws UnknownHostException, IOException, InterruptedException, KeeperException {
        if (args.length != 3) {
            System.err.println("USAGE: " + InstanceContainer.class.getName() + " name zkHostPort znodePrefix");
            System.exit(2);
        }
        new InstanceContainer(args[0], args[1], args[2]).run();
        while(true) {
            Thread.sleep(1000);
        }
    }

    public void process(WatchedEvent event) {
        if (KeeperState.Expired == event.getState()) {
            // It's all over
            LOG.error("Lost session");
            System.exit(4);
        }
        if (event.getPath() != null && event.getPath().equals(assignmentsNode)) {
            // children have changed, so read in the new list
            zk.getChildren(assignmentsNode, true, this, null);
        }
    }

    HashMap<String, Instance> instances = new HashMap<String, Instance>();
    public void processResult(int rc, String path, Object ctx,
            List<String> children) {
        if (rc != KeeperException.Code.OK.intValue()) {
            // try it again
            zk.getChildren(assignmentsNode, true, this, null);
            return;
        }
        HashMap<String, Instance> newList = new HashMap<String, Instance>();
        // check for differences
        Stat stat = new Stat();
        for(String child: children) {
            Instance i = instances.remove(child);
            if (i == null) {
                // Start up a new instance
                byte data[] = null;
                String myNode = assignmentsNode + '/' + child;
                while(true) {
                    try {
                        data = zk.getData(myNode, true, stat);
                        break;
                    } catch (NoNodeException e) {
                        // The node doesn't exist anymore, so skip it
                        break;
                    } catch (KeeperException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (data != null) {
                    String instanceSpec = new String(data);
                    int spaceIndex = instanceSpec.indexOf(' ');
                    String clazz;
                    String conf;
                    if (spaceIndex == -1) {
                        clazz = instanceSpec;
                        conf = null;
                    } else {
                        clazz = instanceSpec.substring(0, spaceIndex);
                        conf = instanceSpec.substring(spaceIndex+1);
                    }
                    try {
                        Class c = Class.forName(clazz);
                        i = (Instance)c.newInstance();
                        Reporter reporter = new MyReporter(child);
                        i.setReporter(reporter);
                        i.configure(conf);
                        i.start();
                        newList.put(child, i);
                        int ver = stat.getVersion();
                        Instance myInstance = i;
                        DataCallback dc = new MyDataCallback(myNode, myInstance, ver);
                        Watcher watcher = new MyWatcher(myNode, dc);
                        zk.getData(myNode, watcher, dc, watcher);
                    } catch (Exception e) {
                        LOG.warn("Skipping " + child, e);
                        if (e.getCause() != null) {
                            LOG.warn("Caused by", e.getCause());
                        }
                    }
                    
                }
            } else {
                // just move it to the new list
                newList.put(child, i);
            }
        }
        // kill anything that was removed for the children
        for(Map.Entry<String,Instance> i: instances.entrySet()) {
            i.getValue().stop();
            try {
                rmnod(reportsNode + '/' + i.getKey());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (KeeperException e) {
                e.printStackTrace();
            }
        }
        instances = newList;
    }

}
