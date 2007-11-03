/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.yahoo.jute.InputArchive;
import com.yahoo.jute.OutputArchive;
import com.yahoo.jute.Record;
import com.yahoo.zookeeper.KeeperException;
import com.yahoo.zookeeper.Watcher;
import com.yahoo.zookeeper.KeeperException.Code;
import com.yahoo.zookeeper.Watcher.Event;
import com.yahoo.zookeeper.ZooDefs.OpCode;
import com.yahoo.zookeeper.data.ACL;
import com.yahoo.zookeeper.data.Stat;
import com.yahoo.zookeeper.txn.CreateTxn;
import com.yahoo.zookeeper.txn.DeleteTxn;
import com.yahoo.zookeeper.txn.ErrorTxn;
import com.yahoo.zookeeper.txn.SetACLTxn;
import com.yahoo.zookeeper.txn.SetDataTxn;
import com.yahoo.zookeeper.txn.TxnHeader;

/**
 * This class maintains the tree data structure. It doesn't have any networking
 * or client connection code in it so that it can be tested in a stand alone
 * way.
 * <p>
 * The tree maintains two parallel data structures: a hashtable that maps from
 * full paths to DataNodes and a tree of DataNodes. All accesses to a path is
 * through the hashtable. The tree is traversed only when serializing to disk.
 */
public class DataTree {
    /**
     * This hashtable provides a fast lookup to the datanodes. The tree is the
     * source of truth and is where all the locking occurs
     */
    private ConcurrentHashMap<String, DataNode> nodes = new ConcurrentHashMap<String, DataNode>();

    private WatchManager dataWatches = new WatchManager();

    private WatchManager childWatches = new WatchManager();

    /**
     * This hashtable lists the paths of the ephemeral nodes of a session.
     */
    private ConcurrentHashMap<Long, HashSet<String>> ephemerals = new ConcurrentHashMap<Long, HashSet<String>>();

    /** A debug string * */
    private String debug = "debug";

    public HashSet<String> getEphemerals(long sessionId) {
        HashSet<String> retv = ephemerals.get(sessionId);
        if (retv == null) {
            return new HashSet<String>();
        }
        return (HashSet<String>) retv.clone();
    }

    public Collection<Long> getSessions() {
        return ephemerals.keySet();
    }

    public DataNode getNode(String path) {
        return nodes.get(path);
    }

    /**
     * This is a pointer to the root of the DataTree. It is the source of truth,
     * but we usually use the nodes hashmap to find nodes in the tree.
     */
    private DataNode root = new DataNode(null, new byte[0], null, new Stat());

    public DataTree() {
        /* Rather than fight it, let root have an alias */
        nodes.put("", root);
        nodes.put("/", root);
    }

    static public void copyStat(Stat from, Stat to) {
        to.setAversion(from.getAversion());
        to.setCtime(from.getCtime());
        to.setCversion(from.getCversion());
        to.setCzxid(from.getCzxid());
        to.setMtime(from.getMtime());
        to.setMzxid(from.getMzxid());
        to.setVersion(from.getVersion());
        to.setEphemeralOwner(from.getEphemeralOwner());
    }

    // public void remooveInterest(String path, Watcher nw) {
    // DataNode n = nodes.get(path);
    // if (n == null) {
    // synchronized (nonExistentWatches) {
    // HashSet<Watcher> list = nonExistentWatches.get(path);
    // if (list != null) {
    // list.remove(nw);
    // }
    // }
    // }
    // synchronized (n) {
    // n.dataWatchers.remove(nw);
    // n.childWatchers.remove(nw);
    // }
    // }

    /**
     * @param path
     * @param data
     * @param acl
     * @param ephemeralOwner
     *                the session id that owns this node. -1 indicates this is
     *                not an ephemeral node.
     * @param zxid
     * @param time
     * @return
     * @throws KeeperException
     */
    private String createNode(String path, byte data[], ArrayList<ACL> acl,
            long ephemeralOwner, long zxid, long time) throws KeeperException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        Stat stat = new Stat();
        stat.setCtime(time);
        stat.setMtime(time);
        stat.setCzxid(zxid);
        stat.setMzxid(zxid);
        stat.setVersion(0);
        stat.setAversion(0);
        stat.setEphemeralOwner(ephemeralOwner);
        DataNode parent = nodes.get(parentName);
        if (parent == null) {
            throw new KeeperException(KeeperException.Code.NoNode);
        }
        synchronized (parent) {
            if (parent.children.contains(childName)) {
                throw new KeeperException(KeeperException.Code.NodeExists);
            }
            int cver = parent.stat.getCversion();
            cver++;
            parent.stat.setCversion(cver);
            DataNode child = new DataNode(parent, data, acl, stat);
            parent.children.add(childName);
            nodes.put(path, child);
            if (ephemeralOwner != 0) {
                HashSet<String> list = ephemerals.get(ephemeralOwner);
                if (list == null) {
                    list = new HashSet<String>();
                    ephemerals.put(ephemeralOwner, list);
                }
                list.add(path);
            }
        }
        dataWatches.triggerWatch(path, Event.EventNodeCreated);
        childWatches.triggerWatch(parentName, Event.EventNodeChildrenChanged);
        return path;
    }

    private void deleteNode(String path) throws KeeperException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        DataNode node = nodes.get(path);
        if (node == null) {
            throw new KeeperException(KeeperException.Code.NoNode);
        }
        nodes.remove(path);
        DataNode parent = nodes.get(parentName);
        if (parent == null) {
            throw new KeeperException(KeeperException.Code.NoNode);
        }
        synchronized (parent) {
            parent.children.remove(childName);
            parent.stat.setCversion(parent.stat.getCversion() + 1);
            long eowner = node.stat.getEphemeralOwner();
            if (eowner != 0) {
                HashSet<String> nodes = ephemerals.get(eowner);
                if (nodes != null) {
                    nodes.remove(path);
                }
            }
            node.parent = null;
        }
        ZooLog.logTextTraceMessage("dataWatches.triggerWatch " + path,
                ZooLog.EVENT_DELIVERY_TRACE_MASK);
        ZooLog.logTextTraceMessage("childWatches.triggerWatch " + parentName,
                ZooLog.EVENT_DELIVERY_TRACE_MASK);
        dataWatches.triggerWatch(path, Event.EventNodeDeleted);
        childWatches.triggerWatch(parentName, Event.EventNodeChildrenChanged);
    }

    private Stat setData(String path, byte data[], int version, long zxid,
            long time) throws KeeperException {
        Stat s = new Stat();
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException(KeeperException.Code.NoNode);
        }
        synchronized (n) {
            n.data = data;
            n.stat.setMtime(time);
            n.stat.setMzxid(zxid);
            n.stat.setVersion(version);
            copyStat(n.stat, s);
        }
        dataWatches.triggerWatch(path, Event.EventNodeDataChanged);
        return s;
    }

    public byte[] getData(String path, Stat stat, Watcher watcher)
            throws KeeperException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException(KeeperException.Code.NoNode);
        }
        synchronized (n) {
            copyStat(n.stat, stat);
            if (watcher != null) {
                dataWatches.addWatch(path, watcher);
            }
            return n.data;
        }
    }

    public Stat statNode(String path, Watcher watcher) throws KeeperException {
        Stat stat = new Stat();
        DataNode n = nodes.get(path);
        if (watcher != null) {
            dataWatches.addWatch(path, watcher);
        }
        if (n == null) {
            throw new KeeperException(KeeperException.Code.NoNode);
        }
        synchronized (n) {
            copyStat(n.stat, stat);
            return stat;
        }
    }

    public ArrayList<String> getChildren(String path, Stat stat, Watcher watcher)
            throws KeeperException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException(KeeperException.Code.NoNode);
        }
        synchronized (n) {
            ArrayList<String> children = new ArrayList<String>();
            children.addAll(n.children);
            Collections.sort(children);
            if (watcher != null) {
                childWatches.addWatch(path, watcher);
            }
            return children;
        }
    }

    private Stat setACL(String path, ArrayList<ACL> acl, int version)
            throws KeeperException {
        Stat stat = new Stat();
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException(KeeperException.Code.NoNode);
        }
        synchronized (n) {
            n.stat.setAversion(version);
            n.acl = acl;
            copyStat(n.stat, stat);
            return stat;
        }
    }

    @SuppressWarnings("unchecked")
    public ArrayList<ACL> getACL(String path, Stat stat) throws KeeperException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException(KeeperException.Code.NoNode);
        }
        synchronized (n) {
            copyStat(n.stat, stat);
            return (ArrayList<ACL>) n.acl.clone();
        }
    }

    static public class ProcessTxnResult {
        public long clientId;

        public int cxid;

        public long zxid;

        public int err;

        public int type;

        public String path;

        public Stat stat;

        /**
         * Equality is defined as the clientId and the cxid being the same. This
         * allows us to use hash tables to track completion of transactions.
         * 
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object o) {
            if (o instanceof ProcessTxnResult) {
                ProcessTxnResult other = (ProcessTxnResult) o;
                return other.clientId == clientId && other.cxid == cxid;
            }
            return false;
        }

        /**
         * See equals() to find the rational for how this hashcode is generated.
         * 
         * @see ProcessTxnResult#equals(Object)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            return (int) ((clientId ^ cxid) % Integer.MAX_VALUE);
        }

    }

    public volatile long lastProcessedZxid = 0;

    @SuppressWarnings("unchecked")
    public ProcessTxnResult processTxn(TxnHeader header, Record txn) {
        ProcessTxnResult rc = new ProcessTxnResult();

        try {
            rc.clientId = header.getClientId();
            rc.cxid = header.getCxid();
            rc.zxid = header.getZxid();
            rc.type = header.getType();
            rc.err = 0;
            if (rc.zxid > lastProcessedZxid) {
                lastProcessedZxid = rc.zxid;
            }
            switch (header.getType()) {
            case OpCode.create:
                CreateTxn createTxn = (CreateTxn) txn;
                debug = "Create transaction for " + createTxn.getPath();
                createNode(createTxn.getPath(), createTxn.getData(), createTxn
                        .getAcl(), createTxn.getEphemeral() ? header
                        .getClientId() : 0, header.getZxid(), header.getTime());
                rc.path = createTxn.getPath();
                break;
            case OpCode.delete:
                DeleteTxn deleteTxn = (DeleteTxn) txn;
                debug = "Delete transaction for " + deleteTxn.getPath();
                deleteNode(deleteTxn.getPath());
                break;
            case OpCode.setData:
                SetDataTxn setDataTxn = (SetDataTxn) txn;
                debug = "Set data for  transaction for " + setDataTxn.getPath();
                rc.stat = setData(setDataTxn.getPath(), setDataTxn.getData(),
                        setDataTxn.getVersion(), header.getZxid(), header
                                .getTime());
                break;
            case OpCode.setACL:
                SetACLTxn setACLTxn = (SetACLTxn) txn;
                rc.stat = setACL(setACLTxn.getPath(), setACLTxn.getAcl(),
                        setACLTxn.getVersion());
                break;
            case OpCode.closeSession:
                killSession(header.getClientId());
                break;
            case OpCode.error:
                ErrorTxn errTxn = (ErrorTxn) txn;
                rc.err = errTxn.getErr();
                break;
            }
        } catch (KeeperException e) {
            // These are expected errors since we take a lazy snapshot
            if (initialized
                    || (e.getCode() != Code.NoNode && e.getCode() != Code.NodeExists)) {
                ZooLog.logWarn(debug);
                ZooLog.logException(e);
            }
        }
        return rc;
    }

    void killSession(long session) {
        HashSet<String> list = ephemerals.remove(session);
        if (list != null) {
            for (String path : list) {
                try {
                    deleteNode(path);
                    ZooLog.logTextTraceMessage("Deleting ephemeral node "
                            + path + " for session " + session,
                            ZooLog.SESSION_TRACE_MASK);
                } catch (KeeperException e) {
                    ZooLog.logException(e);
                }
            }
        }
    }

    void serializeNode(OutputArchive oa, String path) throws IOException,
            InterruptedException {
        DataNode node = getNode(path);
        if (node == null) {
            return;
        }
        ArrayList<String> children = null;
        synchronized (node) {
            scount++;
            oa.writeString(path, "path");
            oa.writeRecord(node, "node");
            children = new ArrayList<String>(node.children);
        }
        if (children != null) {
            Collections.sort(children);
            for (String childName : children) {
                String childPath = path + '/' + childName;
                serializeNode(oa, childPath);
            }
        }
    }

    int scount;

    public boolean initialized = false;

    public void serialize(OutputArchive oa, String tag) throws IOException,
            InterruptedException {
        scount = 0;
        serializeNode(oa, "");
        // / marks end of stream
        // we need to check if clear had been called in between the snapshot.
        if (root != null) {
            oa.writeString("/", "path");
        }
    }

    public void deserialize(InputArchive ia, String tag) throws IOException {
        nodes.clear();
        String path = ia.readString("path");
        while (!path.equals("/")) {
            DataNode node = new DataNode();
            ia.readRecord(node, "node");
            nodes.put(path, node);
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash == -1) {
                root = node;
            } else {
                String parentPath = path.substring(0, lastSlash);
                node.parent = nodes.get(parentPath);
                node.parent.children.add(path.substring(lastSlash + 1));
                long eowner = node.stat.getEphemeralOwner();
                if (eowner != 0) {
                    HashSet<String> list = ephemerals.get(eowner);
                    if (list == null) {
                        list = new HashSet<String>();
                        ephemerals.put(eowner, list);
                    }
                    list.add(path);
                }
            }
            path = ia.readString("path");
        }
        nodes.put("/", root);
    }

    public String dumpEphemerals() {
        Set<Long> keys = ephemerals.keySet();
        StringBuffer sb = new StringBuffer("Sessions with Ephemerals ("
                + keys.size() + "):\n");
        for (long k : keys) {
            sb.append(Long.toHexString(k));
            sb.append(":\n");
            for (String path : ephemerals.get(k)) {
                sb.append("\t" + path + "\n");
            }
        }
        return sb.toString();
    }

    public void removeCnxn(Watcher watcher) {
        dataWatches.removeWatcher(watcher);
        childWatches.removeWatcher(watcher);
    }

    public void clear() {
        root = null;
        nodes.clear();
        ephemerals.clear();
        // dataWatches = null;
        // childWatches = null;
    }

}
