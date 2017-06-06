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

package org.apache.zookeeper.server.upgrade;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.data.StatPersistedV1;
import org.apache.zookeeper.server.WatchManager;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.SetACLTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * This class maintains the tree data structure. It doesn't have any networking
 * or client connection code in it so that it can be tested in a stand alone
 * way.
 * <p>
 * The tree maintains two parallel data structures: a hashtable that maps from
 * full paths to DataNodes and a tree of DataNodes. All accesses to a path is
 * through the hashtable. The tree is traversed only when serializing to disk.
 */
public class DataTreeV1 {
    private static final Logger LOG = LoggerFactory.getLogger(DataTreeV1.class);

    /**
     * This hashtable provides a fast lookup to the datanodes. The tree is the
     * source of truth and is where all the locking occurs
     */
    private ConcurrentHashMap<String, DataNodeV1> nodes = new ConcurrentHashMap<String, DataNodeV1>();

    private WatchManager dataWatches = new WatchManager();

    private WatchManager childWatches = new WatchManager();

    /**
     * This hashtable lists the paths of the ephemeral nodes of a session.
     */
    private Map<Long, HashSet<String>> ephemerals = new ConcurrentHashMap<Long, HashSet<String>>();

    /**
     * return the ephemerals for this tree
     * @return the ephemerals for this tree
     */
    public Map<Long, HashSet<String>>  getEphemeralsMap() {
        return this.ephemerals;
    }
    
    public void setEphemeralsMap(Map<Long, HashSet<String>> ephemerals) {
        this.ephemerals = ephemerals;
    }
    
    @SuppressWarnings("unchecked")
    public HashSet<String> getEphemerals(long sessionId) {
        HashSet<String> retv = ephemerals.get(sessionId);
        if (retv == null) {
            return new HashSet<String>();
        }
        HashSet<String> cloned = null;
        synchronized(retv) {
            cloned =  (HashSet<String>) retv.clone();
        }
        return cloned;
    }
    
    public Collection<Long> getSessions() {
        return ephemerals.keySet();
    }

    public DataNodeV1 getNode(String path) {
        return nodes.get(path);
    }

    /**
     * This is a pointer to the root of the DataTree. It is the source of truth,
     * but we usually use the nodes hashmap to find nodes in the tree.
     */
    private DataNodeV1 root = new DataNodeV1(null, new byte[0], null, new StatPersistedV1());

    public DataTreeV1() {
        /* Rather than fight it, let root have an alias */
        nodes.put("", root);
        nodes.put("/", root);
    }

    static public void copyStatPersisted(StatPersistedV1 from, StatPersistedV1 to) {
        to.setAversion(from.getAversion());
        to.setCtime(from.getCtime());
        to.setCversion(from.getCversion());
        to.setCzxid(from.getCzxid());
        to.setMtime(from.getMtime());
        to.setMzxid(from.getMzxid());
        to.setVersion(from.getVersion());
        to.setEphemeralOwner(from.getEphemeralOwner());
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
        to.setDataLength(from.getDataLength());
        to.setNumChildren(from.getNumChildren());
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
     * @return the patch of the created node
     * @throws KeeperException
     */
    public String createNode(String path, byte data[], List<ACL> acl,
            long ephemeralOwner, long zxid, long time) 
            throws KeeperException.NoNodeException, KeeperException.NodeExistsException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        StatPersistedV1 stat = new StatPersistedV1();
        stat.setCtime(time);
        stat.setMtime(time);
        stat.setCzxid(zxid);
        stat.setMzxid(zxid);
        stat.setVersion(0);
        stat.setAversion(0);
        stat.setEphemeralOwner(ephemeralOwner);
        DataNodeV1 parent = nodes.get(parentName);
        if (parent == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (parent) {
            if (parent.children.contains(childName)) {
                throw new KeeperException.NodeExistsException();
            }
            int cver = parent.stat.getCversion();
            cver++;
            parent.stat.setCversion(cver);
            DataNodeV1 child = new DataNodeV1(parent, data, acl, stat);
            parent.children.add(childName);
            nodes.put(path, child);
            if (ephemeralOwner != 0) {
                HashSet<String> list = ephemerals.get(ephemeralOwner);
                if (list == null) {
                    list = new HashSet<String>();
                    ephemerals.put(ephemeralOwner, list);
                }
                synchronized(list) {
                    list.add(path);
                }
            }
        }
        dataWatches.triggerWatch(path, Event.EventType.NodeCreated);
        childWatches.triggerWatch(parentName.equals("")?"/":parentName, Event.EventType.NodeChildrenChanged);
        return path;
    }

    public void deleteNode(String path) throws KeeperException.NoNodeException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        DataNodeV1 node = nodes.get(path);
        if (node == null) {
            throw new KeeperException.NoNodeException();
        }
        nodes.remove(path);
        DataNodeV1 parent = nodes.get(parentName);
        if (parent == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (parent) {
            parent.children.remove(childName);
            parent.stat.setCversion(parent.stat.getCversion() + 1);
            long eowner = node.stat.getEphemeralOwner();
            if (eowner != 0) {
                HashSet<String> nodes = ephemerals.get(eowner);
                if (nodes != null) {
                    synchronized(nodes) {
                        nodes.remove(path);
                    }
                }
            }
            node.parent = null;
        }
        Set<Watcher> processed =
        dataWatches.triggerWatch(path, EventType.NodeDeleted);
        childWatches.triggerWatch(path, EventType.NodeDeleted, processed);
        childWatches.triggerWatch(parentName.equals("")?"/":parentName, EventType.NodeChildrenChanged);
    }

    public Stat setData(String path, byte data[], int version, long zxid,
            long time) throws KeeperException.NoNodeException {
        Stat s = new Stat();
        DataNodeV1 n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            n.data = data;
            n.stat.setMtime(time);
            n.stat.setMzxid(zxid);
            n.stat.setVersion(version);
            n.copyStat(s);
        }
        dataWatches.triggerWatch(path, EventType.NodeDataChanged);
        return s;
    }

    public byte[] getData(String path, Stat stat, Watcher watcher) throws KeeperException.NoNodeException {
        DataNodeV1 n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            n.copyStat(stat);
            if (watcher != null) {
                dataWatches.addWatch(path, watcher);
            }
            return n.data;
        }
    }

    public Stat statNode(String path, Watcher watcher) throws KeeperException.NoNodeException {
        Stat stat = new Stat();
        DataNodeV1 n = nodes.get(path);
        if (watcher != null) {
            dataWatches.addWatch(path, watcher);
        }
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            n.copyStat(stat);
            return stat;
        }
    }

    public ArrayList<String> getChildren(String path, Stat stat, Watcher watcher) throws KeeperException.NoNodeException {
        DataNodeV1 n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            ArrayList<String> children = new ArrayList<String>();
            children.addAll(n.children);
            if (watcher != null) {
                childWatches.addWatch(path, watcher);
            }
            return children;
        }
    }

    public Stat setACL(String path, List<ACL> acl, int version) throws KeeperException.NoNodeException {
        Stat stat = new Stat();
        DataNodeV1 n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            n.stat.setAversion(version);
            n.acl = acl;
            n.copyStat(stat);
            return stat;
        }
    }

    @SuppressWarnings("unchecked")
    public List<ACL> getACL(String path, Stat stat) throws KeeperException.NoNodeException {
        DataNodeV1 n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            n.copyStat(stat);
            return new ArrayList<ACL>(n.acl);
        }
    }

    static public class ProcessTxnResult {
        public long clientId;

        public int cxid;

        public long zxid;

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

        String debug = "";
        try {
            rc.clientId = header.getClientId();
            rc.cxid = header.getCxid();
            rc.zxid = header.getZxid();
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
                break;
            case OpCode.delete:
                DeleteTxn deleteTxn = (DeleteTxn) txn;
                debug = "Delete transaction for " + deleteTxn.getPath();
                deleteNode(deleteTxn.getPath());
                break;
            case OpCode.setData:
                SetDataTxn setDataTxn = (SetDataTxn) txn;
                debug = "Set data for  transaction for " + setDataTxn.getPath();
                break;
            case OpCode.setACL:
                SetACLTxn setACLTxn = (SetACLTxn) txn;
                debug = "Set ACL for  transaction for " + setACLTxn.getPath();
                break;
            case OpCode.closeSession:
                killSession(header.getClientId());
                break;
            case OpCode.error:
                ErrorTxn errTxn = (ErrorTxn) txn;
                break;
            }
        } catch (KeeperException e) {
            // These are expected errors since we take a lazy snapshot
            if (initialized
                    || (e.code() != Code.NONODE 
                            && e.code() != Code.NODEEXISTS)) {
                LOG.warn("Failed:" + debug, e);
            }
        }
        return rc;
    }

    void killSession(long session) {
        // the list is already removed from the ephemerals
        // so we do not have to worry about synchronyzing on
        // the list. This is only called from FinalRequestProcessor
        // so there is no need for synchornization. The list is not
        // changed here. Only create and delete change the list which
        // are again called from FinalRequestProcessor in sequence.
        HashSet<String> list = ephemerals.remove(session);
        if (list != null) {
            for (String path : list) {
                try {
                    deleteNode(path);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Deleting ephemeral node " + path
                                + " for session 0x"
                                + Long.toHexString(session));
                    }
                } catch (NoNodeException e) {
                    LOG.warn("Ignoring NoNodeException for path " + path
                            + " while removing ephemeral for dead session 0x"
                            + Long.toHexString(session));
                }
            }
        }
    }

    /**
     * this method uses a stringbuilder to create a new
     * path for children. This is faster than string
     * appends ( str1 + str2).
     * @param oa OutputArchive to write to.
     * @param path a string builder.
     * @throws IOException
     * @throws InterruptedException
     */
    void serializeNode(OutputArchive oa, StringBuilder path)
            throws IOException, InterruptedException {
        String pathString = path.toString();
        DataNodeV1 node = getNode(pathString);
        if (node == null) {
            return;
        }
        String children[] = null;
        synchronized (node) {
            scount++;
            oa.writeString(pathString, "path");
            oa.writeRecord(node, "node");
            children = node.children.toArray(new String[node.children.size()]);
        }
        path.append('/');
        int off = path.length();
        if (children != null) {
            for (String child : children) {
                //since this is single buffer being resused
                // we need
                // to truncate the previous bytes of string.
                path.delete(off, Integer.MAX_VALUE);
                path.append(child);
                serializeNode(oa, path);
            }
        }
    }

    int scount;

    public boolean initialized = false;

    public void serialize(OutputArchive oa, String tag) throws IOException,
            InterruptedException {
        scount = 0;
        serializeNode(oa, new StringBuilder(""));
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
            DataNodeV1 node = new DataNodeV1();
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
        StringBuilder sb = new StringBuilder("Sessions with Ephemerals ("
                + keys.size() + "):\n");
        for (long k : keys) {
            sb.append("0x" + Long.toHexString(k));
            sb.append(":\n");
            HashSet<String> tmp = ephemerals.get(k);
            synchronized(tmp) {
                for (String path : tmp) {
                    sb.append("\t" + path + "\n");
                }
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
