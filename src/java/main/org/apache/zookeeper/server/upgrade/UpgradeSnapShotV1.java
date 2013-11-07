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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.Record;
import org.apache.log4j.Logger;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.data.StatPersistedV1;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.CreateSessionTxn;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * This class gets the old snapshot 
 * and the old dataDir and creates 
 * an brand new snapshot that is 
 * then converted to the new snapshot
 * for upgrading.           
 */
public class UpgradeSnapShotV1 implements UpgradeSnapShot {
    private static final Logger LOG = Logger.getLogger(UpgradeSnapShotV1.class);
    
    ConcurrentHashMap<Long, Integer> sessionsWithTimeouts = 
        new ConcurrentHashMap<Long, Integer>();
    File dataDir;
    File snapShotDir;
    DataTreeV1 oldDataTree;
   
    /**
     * upgrade from version 1 to version 2
     * @param dataDir
     * @param snapShotDir
     */
    public UpgradeSnapShotV1(File dataDir, File snapShotDir) {
        this.dataDir = dataDir;
        this.snapShotDir = snapShotDir;
        oldDataTree = new DataTreeV1();
    }
    
    /**
     * deseriluize from an inputarchive
     * @param oldTree the tree to be created
     * @param ia the input archive to be read from
     * @param sessions the sessions to be created
     * @throws IOException 
     */
    private void deserializeSnapshot(DataTreeV1 oldTree, InputArchive ia,
            Map<Long, Integer> sessions) throws IOException {
        int count = ia.readInt("count");
        while (count > 0) {
            long id = ia.readLong("id");
            int to = ia.readInt("timeout");
            sessions.put(id, to);
            if (LOG.isTraceEnabled()) {
                ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                        "loadData --- session in archive: " + id
                        + " with timeout: " + to);
            }
            count--;
        }
        oldTree.deserialize(ia, "tree");
    }
    
    /**
     * play the log from this logstream into the datatree
     * @param logStream
     * @return
     * @throws IOException
     */
    public long playLog(InputArchive logStream) throws IOException {
        long highestZxid = 0;
        try {
            while (true) {
                byte[] bytes = logStream.readBuffer("txnEntry");
                if (bytes.length == 0) {
                    // Since we preallocate, we define EOF to be an
                    // empty transaction
                    throw new EOFException();
                }
                InputArchive ia = BinaryInputArchive
                        .getArchive(new ByteArrayInputStream(bytes));
                TxnHeader hdr = new TxnHeader();
                Record txn = SerializeUtils.deserializeTxn(ia, hdr);
                if (logStream.readByte("EOR") != 'B') {
                    LOG.warn("Last transaction was partial.");
                    throw new EOFException("Last transaction was partial.");
                }
                if (hdr.getZxid() <= highestZxid && highestZxid != 0) {
                    LOG.error(highestZxid + "(higestZxid) >= "
                            + hdr.getZxid() + "(next log) for type "
                            + hdr.getType());
                } else {
                    highestZxid = hdr.getZxid();
                }
                switch (hdr.getType()) {
                case OpCode.createSession:
                    sessionsWithTimeouts.put(hdr.getClientId(),
                            ((CreateSessionTxn) txn).getTimeOut());
                    if (LOG.isTraceEnabled()) {
                        ZooTrace.logTraceMessage(LOG,
                                                 ZooTrace.SESSION_TRACE_MASK,
                                "playLog --- create session in log: 0x"
                                        + Long.toHexString(hdr.getClientId())
                                        + " with timeout: "
                                        + ((CreateSessionTxn) txn).getTimeOut());
                    }
                    // give dataTree a chance to sync its lastProcessedZxid
                    oldDataTree.processTxn(hdr, txn);
                    break;
                case OpCode.closeSession:
                    sessionsWithTimeouts.remove(hdr.getClientId());
                    if (LOG.isTraceEnabled()) {
                        ZooTrace.logTraceMessage(LOG,
                                ZooTrace.SESSION_TRACE_MASK,
                                "playLog --- close session in log: 0x"
                                        + Long.toHexString(hdr.getClientId()));
                    }
                    oldDataTree.processTxn(hdr, txn);
                    break;
                default:
                    oldDataTree.processTxn(hdr, txn);
                }
                Request r = new Request(null, 0, hdr.getCxid(), hdr.getType(),
                        null, null);
                r.txn = txn;
                r.hdr = hdr;
                r.zxid = hdr.getZxid();
            }
        } catch (EOFException e) {
            // expected in some cases - see comments in try block
        }
        return highestZxid;
    }

   
    
    /**
     * apply the log files to the datatree
     * @param oldTree the datatreee to apply the logs to
     * @param logFiles the logs to be applied
     * @throws IOException
     */
    private long processLogFiles(DataTreeV1 oldTree, 
            File[] logFiles) throws IOException {
        long zxid = 0;
        for (File f: logFiles) { 
            LOG.info("Processing log file: " + f);
            InputStream logIs = 
                new BufferedInputStream(new FileInputStream(f));
            zxid = playLog(BinaryInputArchive.getArchive(logIs));
            logIs.close();
        }
        return zxid;
    }
    
    /**
     * create the old snapshot database
     * apply logs to it and create the final
     * database
     * @throws IOException
     */
    private void loadThisSnapShot() throws IOException {  
        // pick the most recent snapshot
        File snapshot = findMostRecentSnapshot();
        if (snapshot == null) {
            throw new IOException("Invalid snapshots " +
            		"or not snapshots in " + snapShotDir);
        }
        InputStream inputstream = new BufferedInputStream(
                new FileInputStream(snapshot));
        InputArchive ia = BinaryInputArchive.getArchive(inputstream);
        deserializeSnapshot(oldDataTree, ia, sessionsWithTimeouts);
        //ok done with the snapshot 
        // now apply the logs
        long snapshotZxid = oldDataTree.lastProcessedZxid;
        File[] files = FileTxnLog.getLogFiles(
                dataDir.listFiles(), snapshotZxid);
        long zxid = processLogFiles(oldDataTree, files);
        //check for this zxid to be sane
        if (zxid != oldDataTree.lastProcessedZxid) {
            LOG.error("Zxids not equal " + " log zxid " +
                    zxid + " datatree processed " + oldDataTree.lastProcessedZxid);
        }
    }
    
    /**
     * find the most recent snapshot 
     * in the snapshot directory
     * @return
     * @throws IOException
     */
    private File findMostRecentSnapshot() throws IOException {
        List<File> files = Util.sortDataDir(snapShotDir.listFiles(),
                "snapshot", false);
        for (File f: files) {
            try {
                if (Util.isValidSnapshot(f))
                    return f;
            } catch(IOException e) {
                LOG.info("Invalid snapshot " + f, e);
            }
        }
        return null;
    }
    
    /**
     * convert the old stat to new stat
     * @param oldStat the old stat
     * @return the new stat
     */
    private StatPersisted convertStat(StatPersistedV1 oldStat) {
        StatPersisted stat = new StatPersisted();
        stat.setAversion(oldStat.getAversion());
        stat.setCtime(oldStat.getCtime());
        stat.setCversion(oldStat.getCversion());
        stat.setCzxid(oldStat.getCzxid());
        stat.setEphemeralOwner(oldStat.getEphemeralOwner());
        stat.setMtime(oldStat.getMtime());
        stat.setMzxid(oldStat.getMzxid());
        stat.setVersion(oldStat.getVersion());
        return stat;
    }
    
    /**
     * convert a given old datanode to new datanode
     * @param dt the new datatree
     * @param parent the parent of the datanode to be constructed
     * @param oldDataNode the old datanode 
     * @return the new datanode
     */
    private DataNode convertDataNode(DataTree dt, DataNode parent, 
            DataNodeV1 oldDataNode) {
        StatPersisted stat = convertStat(oldDataNode.stat);
        DataNode dataNode =  new DataNode(parent, oldDataNode.data,
                dt.convertAcls(oldDataNode.acl), stat);
        dataNode.setChildren(oldDataNode.children);
        return dataNode;
    }
    
    /**
     * recurse through the old datatree and construct the 
     * new data tree
     * @param dataTree the new datatree to be constructed
     * @param path the path to start with
     */
    private void recurseThroughDataTree(DataTree dataTree, String path) {
        if (path == null)
            return;
        DataNodeV1 oldDataNode = oldDataTree.getNode(path);
        HashSet<String> children = oldDataNode.children;
        DataNode parent = null;
        if ("".equals(path)) {
            parent = null;
        }
        else {
            int lastSlash = path.lastIndexOf('/');
            String parentPath = path.substring(0, lastSlash);
            parent = dataTree.getNode(parentPath);
        }
        DataNode thisDatNode = convertDataNode(dataTree, parent,
                                    oldDataNode);
        dataTree.addDataNode(path, thisDatNode);
        if (children == null || children.size() == 0) {
            return;
        }
        else {
            for (String child: children) {
                recurseThroughDataTree(dataTree, path + "/" +child);
            }
        }
    }   
    
    private DataTree convertThisSnapShot() throws IOException {
        // create a datatree 
        DataTree dataTree = new DataTree();
        DataNodeV1 oldDataNode = oldDataTree.getNode("");
        if (oldDataNode == null) {
            //should never happen
            LOG.error("Upgrading from an empty snapshot.");
        }
        
        recurseThroughDataTree(dataTree, "");
        dataTree.lastProcessedZxid = oldDataTree.lastProcessedZxid;
        return dataTree;
    }
    
    public DataTree getNewDataTree() throws IOException {
        loadThisSnapShot();
        DataTree dt = convertThisSnapShot();
        return dt;
    }
    
    public ConcurrentHashMap<Long, Integer> getSessionWithTimeOuts() {
        return this.sessionsWithTimeouts;
    }
}