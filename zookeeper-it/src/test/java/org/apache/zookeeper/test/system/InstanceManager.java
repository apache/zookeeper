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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

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
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.common.Time;

/**
 * This class doles out assignments to InstanceContainers that are registered to
 * a ZooKeeper znode. The znode will have four child nodes:
 *    * ready: this znode indicates that the InstanceManager is running
 *    * available: the children of this znode are ephemeral nodes representing
 *                 running InstanceContainers
 *    * assignments: there will be a child under this znode for each available
 *                   InstanceContainer. those znodes will have a child for each
 *                   assigned instance
 *    * reports: there will be a child under this znode for each instance that is
 *               running. it will have the report string from the instance.
 */
public class InstanceManager implements AsyncCallback.ChildrenCallback, Watcher {
    final private static Logger LOG = LoggerFactory.getLogger(InstanceManager.class);
    private ZooKeeper zk;
    private String prefixNode;
    private String reportsNode = "reports";
    private String readyNode = "ready";
    private String assignmentsNode = "assignments";
    private String statusNode = "available";
    private static final int maxTries = 3;
    private static final class Assigned {
        String container;
        int weight;
        Assigned(String container, int weight) {
            this.container = container;
            this.weight = weight;
        }
    }
    private static List<String> preferredList = new ArrayList<String>();
    static {
        String list = System.getProperty("ic.preferredList");
        if (list != null) {
            preferredList = Arrays.asList(list.split(","));
            System.err.println("Preferred List: " + preferredList);
        } else {
            System.err.println("Preferred List is empty");
        }
    }
    private Map<String, HashSet<Assigned>> assignments = new HashMap<String, HashSet<Assigned>>();
    private Map<String, Assigned> instanceToAssignment = new HashMap<String, Assigned>();
    public InstanceManager(ZooKeeper zk, String prefix) throws KeeperException, InterruptedException {
        this.zk = zk;
        this.prefixNode = prefix;
        this.readyNode = prefix + '/' + this.readyNode;
        this.assignmentsNode = prefix + '/' + this.assignmentsNode;
        this.reportsNode = prefix + '/' + this.reportsNode;
        this.statusNode = prefix + '/' + this.statusNode;
        for(int i = 0; i < maxTries; i++) {
            try {
                setupNodes(zk);
                break;
            } catch(ConnectionLossException e) {}
        }
        ConnectionLossException lastException = null;
        for(int i = 0; i < maxTries; i++) {
            try {
                List<String> children = zk.getChildren(statusNode, this);
                processResult(0, statusNode, null, children);
                lastException = null;
                break;
            } catch(ConnectionLossException e) {
                lastException = e;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
    }
    private void setupNodes(ZooKeeper zk) throws KeeperException,
            InterruptedException {
        try {
            zk.create(prefixNode, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch(NodeExistsException e) { /* this is ok */ }
        try {
            zk.create(assignmentsNode, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch(NodeExistsException e) { /* this is ok */ }
        try {
            zk.create(statusNode, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch(NodeExistsException e) { /* this is ok */ }
        try {
            zk.create(reportsNode, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch(NodeExistsException e) { /* this is ok */ }
        try {
            zk.create(readyNode, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch(NodeExistsException e) { /* this is ok */ }
    }

    synchronized public void processResult(int rc, String path, Object ctx,
            List<String> children) {
        if (rc != KeeperException.Code.OK.intValue()) {
            zk.getChildren(statusNode, this, this, null);
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Got " + children + " children from " + path);
        }
        Map<String, HashSet<Assigned>> newAssignments = new HashMap<String, HashSet<Assigned>>();
        for(String c: children) {
            HashSet<Assigned> a = assignments.remove(c);
            if (a != null) {
                newAssignments.put(c, a);
            } else {
                newAssignments.put(c, new HashSet<Assigned>());
            }
        }
        // Clean up the dead machines
        for(String dead: assignments.keySet()) {
            try {
                removeInstance(dead);
            } catch (KeeperException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assignments = newAssignments;
    }

    public void process(WatchedEvent event) {
        if (event.getPath().equals(statusNode)) {
            zk.getChildren(statusNode, this, this, null);
        }
    }
    synchronized public String assignInstance(String name, Class<? extends Instance> clazz, String params, int weight) throws NoAvailableContainers, DuplicateNameException, InterruptedException, KeeperException {
        if (weight < 1) {
            // if the weights are not above zero, things will get messed up
            weight = 1;
        }
        String instanceSpec = clazz.getName() + ' ' + params;
        if (instanceToAssignment.get(name) != null) {
            throw new DuplicateNameException(name + " already exists");
        }
        // find most idle node
        String mostIdle = null;
        int mostIdleWeight = Integer.MAX_VALUE;
        for(String preferred: preferredList) {
            HashSet<Assigned> assignmentList = assignments.get(preferred);
            int w = 0;
            if (assignmentList != null) {
                for(Assigned a: assignmentList) {
                    w += a.weight;
                }
                if (w < mostIdleWeight) {
                    mostIdleWeight = w;
                    mostIdle = preferred;
                }
            }
        }
        for(Entry<String, HashSet<Assigned>> e: assignments.entrySet()) {
            int w = 0;
            for(Assigned a: e.getValue()) {
                w += a.weight;
            }
            if (w < mostIdleWeight) {
                mostIdleWeight = w;
                mostIdle = e.getKey();
            }
        }
        if (mostIdle == null) {
            throw new NoAvailableContainers("No available containers");
        }
        Assigned a = new Assigned(mostIdle, weight);
        instanceToAssignment.put(name, a);
        HashSet<Assigned> as = assignments.get(mostIdle);
        if (as == null) {
            as = new HashSet<Assigned>();
            assignments.put(mostIdle, as);
        }
        as.add(a);
        KeeperException lastException = null;
        for(int i = 0; i < maxTries; i++) {
            try {
                zk.create(assignmentsNode + '/' + mostIdle + '/' + name, instanceSpec.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                return mostIdle;
            } catch(NodeExistsException e) {
                return mostIdle;
            } catch (KeeperException e) {
                lastException = e;
            }
        }
        throw lastException;
    }

    public void reconfigureInstance(String name, String params) throws NoAssignmentException, InterruptedException, KeeperException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Reconfiguring " + name + " with " + params);
        }
        Assigned assigned = instanceToAssignment.get(name);
        if (assigned == null) {
            throw new NoAssignmentException();
        }
        KeeperException lastException = null;
        for(int i = 0; i < maxTries; i++) {
            try {
                zk.setData(assignmentsNode + '/' + assigned.container + '/' + name, ("update " + params).getBytes(), -1);
                break;
            } catch (ConnectionLossException e) {
                lastException = e;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
    }

    private void doDelete(String path) throws InterruptedException, KeeperException {
        KeeperException lastException = null;
        for(int i = 0; i < maxTries; i++) {
            try {
                zk.delete(path, -1);
                return;
            } catch(NoNodeException e) {
                return;
            } catch (KeeperException e) {
                lastException = e;
            }
        }
        throw lastException;
    }
    synchronized public void removeInstance(String name) throws InterruptedException, KeeperException {
        Assigned assigned = instanceToAssignment.remove(name);
        if (assigned == null) {
            return;
        }
        assignments.get(assigned.container).remove(name);
        doDelete(assignmentsNode + '/' + assigned.container + '/' + name);
        doDelete(reportsNode + '/' + name);
    }

    synchronized boolean isAlive(String name) {
        return instanceToAssignment.get(name) != null;
    }

    public void resetStatus(String name) throws InterruptedException, KeeperException {
        KeeperException lastException = null;
        for(int i = 0; i < maxTries; i++) {
            try {
                zk.delete(reportsNode + '/' + name, -1);
                lastException = null;
                break;
            } catch(ConnectionLossException e) {
                lastException = e;
            } catch(NoNodeException e) {
                // great this is what we want!
            }
        }
        if (lastException != null) {
            throw lastException;
        }
    }

    public String getStatus(String name, long timeout) throws KeeperException, InterruptedException {
        Stat stat = new Stat();
        byte[] data = null;
        long endTime = Time.currentElapsedTime() + timeout;
        KeeperException lastException = null;
        for(int i = 0; i < maxTries && endTime > Time.currentElapsedTime(); i++) {
            try {
                data = zk.getData(reportsNode + '/' + name, false, stat);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Got Data: " + ((data == null) ? "null" : new String(data)));
                }
                lastException = null;
                break;
            } catch(ConnectionLossException e) {
                lastException = e;
            } catch(NoNodeException e) {
                final Object eventObj = new Object();
                synchronized(eventObj) {
                    // wait for the node to appear
                    Stat eStat = zk.exists(reportsNode + '/' + name, new Watcher() {
                        public void process(WatchedEvent event) {
                            synchronized(eventObj) {
                                eventObj.notifyAll();
                            }
                        }});
                    if (eStat == null) {
                        eventObj.wait(endTime - Time.currentElapsedTime());
                    }
                }
                lastException = e;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        return new String(data);
    }
    synchronized public void close() throws InterruptedException {
        for(String name: instanceToAssignment.keySet().toArray(new String[0])) {
            try {
                removeInstance(name);
            } catch(KeeperException e) {
                e.printStackTrace();
            }
        }
        try {
            doDelete(readyNode);
        } catch (KeeperException e) {
            e.printStackTrace();
        }
    }
}
