/*
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

package org.apache.zookeeper.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.WatcherType;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree.ProcessTxnResult;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog.PlayBackListener;
import org.apache.zookeeper.server.persistence.TxnLog.TxnIterator;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.apache.zookeeper.server.quorum.QuorumPacket;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class maintains the in memory database of zookeeper
 * server states that includes the sessions, datatree and the
 * committed logs. It is booted up  after reading the logs
 * and snapshots from the disk.
 */
public class ZKDatabase {

    private static final Logger LOG = LoggerFactory.getLogger(ZKDatabase.class);

    /**
     * make sure on a clear you take care of
     * all these members.
     */
    protected DataTree dataTree;
    protected ConcurrentHashMap<Long, Integer> sessionsWithTimeouts;
    protected FileTxnSnapLog snapLog;
    protected long minCommittedLog, maxCommittedLog;

    /**
     * Default value is to use snapshot if txnlog size exceeds 1/3 the size of snapshot
     */
    public static final String SNAPSHOT_SIZE_FACTOR = "zookeeper.snapshotSizeFactor";
    public static final double DEFAULT_SNAPSHOT_SIZE_FACTOR = 0.33;
    private double snapshotSizeFactor;

    public static final String COMMIT_LOG_COUNT = "zookeeper.commitLogCount";
    public static final int DEFAULT_COMMIT_LOG_COUNT = 500;
    public int commitLogCount;
    protected static int commitLogBuffer = 700;
    protected Queue<Proposal> committedLog = new ArrayDeque<>();
    protected ReentrantReadWriteLock logLock = new ReentrantReadWriteLock();
    private volatile boolean initialized = false;

    /**
     * Number of txn since last snapshot;
     */
    private AtomicInteger txnCount = new AtomicInteger(0);

    /**
     * the filetxnsnaplog that this zk database
     * maps to. There is a one to one relationship
     * between a filetxnsnaplog and zkdatabase.
     * @param snapLog the FileTxnSnapLog mapping this zkdatabase
     */
    public ZKDatabase(FileTxnSnapLog snapLog) {
        dataTree = createDataTree();
        sessionsWithTimeouts = new ConcurrentHashMap<Long, Integer>();
        this.snapLog = snapLog;

        try {
            snapshotSizeFactor = Double.parseDouble(
                    System.getProperty(SNAPSHOT_SIZE_FACTOR,
                            Double.toString(DEFAULT_SNAPSHOT_SIZE_FACTOR)));
            if (snapshotSizeFactor > 1) {
                snapshotSizeFactor = DEFAULT_SNAPSHOT_SIZE_FACTOR;
                LOG.warn(
                    "The configured {} is invalid, going to use the default {}",
                    SNAPSHOT_SIZE_FACTOR,
                    DEFAULT_SNAPSHOT_SIZE_FACTOR);
            }
        } catch (NumberFormatException e) {
            LOG.error(
                "Error parsing {}, using default value {}",
                SNAPSHOT_SIZE_FACTOR,
                DEFAULT_SNAPSHOT_SIZE_FACTOR);
            snapshotSizeFactor = DEFAULT_SNAPSHOT_SIZE_FACTOR;
        }

        LOG.info("{} = {}", SNAPSHOT_SIZE_FACTOR, snapshotSizeFactor);

        try {
            commitLogCount = Integer.parseInt(
                    System.getProperty(COMMIT_LOG_COUNT,
                            Integer.toString(DEFAULT_COMMIT_LOG_COUNT)));
            if (commitLogCount < DEFAULT_COMMIT_LOG_COUNT) {
                commitLogCount = DEFAULT_COMMIT_LOG_COUNT;
                LOG.warn(
                    "The configured commitLogCount {} is less than the recommended {}, going to use the recommended one",
                    COMMIT_LOG_COUNT,
                    DEFAULT_COMMIT_LOG_COUNT);
            }
        } catch (NumberFormatException e) {
            LOG.error(
                "Error parsing {} - use default value {}",
                COMMIT_LOG_COUNT,
                DEFAULT_COMMIT_LOG_COUNT);
            commitLogCount = DEFAULT_COMMIT_LOG_COUNT;
        }
        LOG.info("{}={}", COMMIT_LOG_COUNT, commitLogCount);
    }

    /**
     * checks to see if the zk database has been
     * initialized or not.
     * @return true if zk database is initialized and false if not
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * clear the zkdatabase.
     * Note to developers - be careful to see that
     * the clear method does clear out all the
     * data structures in zkdatabase.
     */
    public void clear() {
        minCommittedLog = 0;
        maxCommittedLog = 0;
        /* to be safe we just create a new
         * datatree.
         */
        dataTree.shutdownWatcher();
        dataTree = createDataTree();
        sessionsWithTimeouts.clear();
        WriteLock lock = logLock.writeLock();
        try {
            lock.lock();
            committedLog.clear();
        } finally {
            lock.unlock();
        }
        initialized = false;
    }

    /**
     * the datatree for this zkdatabase
     * @return the datatree for this zkdatabase
     */
    public DataTree getDataTree() {
        return this.dataTree;
    }

    /**
     * the committed log for this zk database
     * @return the committed log for this zkdatabase
     */
    public long getmaxCommittedLog() {
        return maxCommittedLog;
    }

    /**
     * the minimum committed transaction log
     * available in memory
     * @return the minimum committed transaction
     * log available in memory
     */
    public long getminCommittedLog() {
        return minCommittedLog;
    }
    /**
     * Get the lock that controls the committedLog. If you want to get the pointer to the committedLog, you need
     * to use this lock to acquire a read lock before calling getCommittedLog()
     * @return the lock that controls the committed log
     */
    public ReentrantReadWriteLock getLogLock() {
        return logLock;
    }

    public synchronized Collection<Proposal> getCommittedLog() {
        final Collection<Proposal> result;
        ReadLock rl = logLock.readLock();
        // make a copy if this thread is not already holding a lock
        if (logLock.getReadHoldCount() > 0) {
            result = this.committedLog;
        } else {
            rl.lock();
            try {
                result = new ArrayList<>(this.committedLog);
            } finally {
                rl.unlock();
            }
        }
        return Collections.unmodifiableCollection(result);
    }

    /**
     * get the last processed zxid from a datatree
     * @return the last processed zxid of a datatree
     */
    public long getDataTreeLastProcessedZxid() {
        return dataTree.lastProcessedZxid;
    }

    /**
     * return the sessions in the datatree
     * @return the data tree sessions
     */
    public Collection<Long> getSessions() {
        return dataTree.getSessions();
    }

    /**
     * @return number of (global) sessions
     */
    public long getSessionCount() {
        return sessionsWithTimeouts.size();
    }

    /**
     * get sessions with timeouts
     * @return the hashmap of sessions with timeouts
     */
    public ConcurrentHashMap<Long, Integer> getSessionWithTimeOuts() {
        return sessionsWithTimeouts;
    }

    private final PlayBackListener commitProposalPlaybackListener = new PlayBackListener() {
        public void onTxnLoaded(TxnHeader hdr, Record txn, TxnDigest digest) {
            addCommittedProposal(hdr, txn, digest);
        }
    };

    /**
     * load the database from the disk onto memory and also add
     * the transactions to the committedlog in memory.
     * @return the last valid zxid on disk
     * @throws IOException
     */
    public long loadDataBase() throws IOException {
        long startTime = Time.currentElapsedTime();
        long zxid = snapLog.restore(dataTree, sessionsWithTimeouts, commitProposalPlaybackListener);
        initialized = true;
        long loadTime = Time.currentElapsedTime() - startTime;
        ServerMetrics.getMetrics().DB_INIT_TIME.add(loadTime);
        LOG.info("Snapshot loaded in {} ms, highest zxid is 0x{}, digest is {}",
                loadTime, Long.toHexString(zxid), dataTree.getTreeDigest());
        return zxid;
    }

    /**
     * Fast forward the database adding transactions from the committed log into memory.
     * @return the last valid zxid.
     * @throws IOException
     */
    public long fastForwardDataBase() throws IOException {
        long zxid = snapLog.fastForwardFromEdits(dataTree, sessionsWithTimeouts, commitProposalPlaybackListener);
        initialized = true;
        return zxid;
    }

    private void addCommittedProposal(TxnHeader hdr, Record txn, TxnDigest digest) {
        Request r = new Request(0, hdr.getCxid(), hdr.getType(), hdr, txn, hdr.getZxid());
        r.setTxnDigest(digest);
        addCommittedProposal(r);
    }

    /**
     * maintains a list of last <i>committedLog</i>
     *  or so committed requests. This is used for
     * fast follower synchronization.
     * @param request committed request
     */
    public void addCommittedProposal(Request request) {
        WriteLock wl = logLock.writeLock();
        try {
            wl.lock();
            if (committedLog.size() > commitLogCount) {
                committedLog.remove();
                minCommittedLog = committedLog.peek().packet.getZxid();
            }
            if (committedLog.isEmpty()) {
                minCommittedLog = request.zxid;
                maxCommittedLog = request.zxid;
            }

            byte[] data = SerializeUtils.serializeRequest(request);
            QuorumPacket pp = new QuorumPacket(Leader.PROPOSAL, request.zxid, data, null);
            Proposal p = new Proposal();
            p.packet = pp;
            p.request = request;
            committedLog.add(p);
            maxCommittedLog = p.packet.getZxid();
        } finally {
            wl.unlock();
        }
    }

    public boolean isTxnLogSyncEnabled() {
        boolean enabled = snapshotSizeFactor >= 0;
        if (enabled) {
            LOG.info("On disk txn sync enabled with snapshotSizeFactor {}", snapshotSizeFactor);
        } else {
            LOG.info("On disk txn sync disabled");
        }
        return enabled;
    }

    public long calculateTxnLogSizeLimit() {
        long snapSize = 0;
        try {
            File snapFile = snapLog.findMostRecentSnapshot();
            if (snapFile != null) {
                snapSize = snapFile.length();
            }
        } catch (IOException e) {
            LOG.error("Unable to get size of most recent snapshot");
        }
        return (long) (snapSize * snapshotSizeFactor);
    }

    /**
     * Get proposals from txnlog. Only packet part of proposal is populated.
     *
     * @param startZxid the starting zxid of the proposal
     * @param sizeLimit maximum on-disk size of txnlog to fetch
     *                  0 is unlimited, negative value means disable.
     * @return list of proposal (request part of each proposal is null)
     */
    public Iterator<Proposal> getProposalsFromTxnLog(long startZxid, long sizeLimit) {
        if (sizeLimit < 0) {
            LOG.debug("Negative size limit - retrieving proposal via txnlog is disabled");
            return TxnLogProposalIterator.EMPTY_ITERATOR;
        }

        TxnIterator itr = null;
        try {

            itr = snapLog.readTxnLog(startZxid, false);

            // If we cannot guarantee that this is strictly the starting txn
            // after a given zxid, we should fail.
            if ((itr.getHeader() != null) && (itr.getHeader().getZxid() > startZxid)) {
                LOG.warn(
                    "Unable to find proposals from txnlog for zxid: 0x{}",
                    Long.toHexString(startZxid));
                itr.close();
                return TxnLogProposalIterator.EMPTY_ITERATOR;
            }

            if (sizeLimit > 0) {
                long txnSize = itr.getStorageSize();
                if (txnSize > sizeLimit) {
                    LOG.info("Txnlog size: {} exceeds sizeLimit: {}", txnSize, sizeLimit);
                    itr.close();
                    return TxnLogProposalIterator.EMPTY_ITERATOR;
                }
            }
        } catch (IOException e) {
            LOG.error("Unable to read txnlog from disk", e);
            try {
                if (itr != null) {
                    itr.close();
                }
            } catch (IOException ioe) {
                LOG.warn("Error closing file iterator", ioe);
            }
            return TxnLogProposalIterator.EMPTY_ITERATOR;
        }
        return new TxnLogProposalIterator(itr);
    }

    public List<ACL> aclForNode(DataNode n) {
        return dataTree.getACL(n);
    }
    /**
     * remove a cnxn from the datatree
     * @param cnxn the cnxn to remove from the datatree
     */
    public void removeCnxn(ServerCnxn cnxn) {
        dataTree.removeCnxn(cnxn);
    }

    /**
     * kill a given session in the datatree
     * @param sessionId the session id to be killed
     * @param zxid the zxid of kill session transaction
     */
    public void killSession(long sessionId, long zxid) {
        dataTree.killSession(sessionId, zxid);
    }

    /**
     * write a text dump of all the ephemerals in the datatree
     * @param pwriter the output to write to
     */
    public void dumpEphemerals(PrintWriter pwriter) {
        dataTree.dumpEphemerals(pwriter);
    }

    public Map<Long, Set<String>> getEphemerals() {
        return dataTree.getEphemerals();
    }

    /**
     * the node count of the datatree
     * @return the node count of datatree
     */
    public int getNodeCount() {
        return dataTree.getNodeCount();
    }

    /**
     * the paths for  ephemeral session id
     * @param sessionId the session id for which paths match to
     * @return the paths for a session id
     */
    public Set<String> getEphemerals(long sessionId) {
        return dataTree.getEphemerals(sessionId);
    }

    /**
     * the last processed zxid in the datatree
     * @param zxid the last processed zxid in the datatree
     */
    public void setlastProcessedZxid(long zxid) {
        dataTree.lastProcessedZxid = zxid;
    }

    /**
     * the process txn on the data and perform digest comparision.
     * @param hdr the txnheader for the txn
     * @param txn the transaction that needs to be processed
     * @param digest the expected digest. A null value would skip the check
     * @return the result of processing the transaction on this
     * datatree/zkdatabase
     */
    public ProcessTxnResult processTxn(TxnHeader hdr, Record txn, TxnDigest digest) {
        return dataTree.processTxn(hdr, txn, digest);
    }

    /**
     * stat the path
     * @param path the path for which stat is to be done
     * @param serverCnxn the servercnxn attached to this request
     * @return the stat of this node
     * @throws KeeperException.NoNodeException
     */
    public Stat statNode(String path, ServerCnxn serverCnxn) throws KeeperException.NoNodeException {
        return dataTree.statNode(path, serverCnxn);
    }

    /**
     * get the datanode for this path
     * @param path the path to lookup
     * @return the datanode for getting the path
     */
    public DataNode getNode(String path) {
        return dataTree.getNode(path);
    }

    /**
     * get data and stat for a path
     * @param path the path being queried
     * @param stat the stat for this path
     * @param watcher the watcher function
     * @throws KeeperException.NoNodeException
     */
    public byte[] getData(String path, Stat stat, Watcher watcher) throws KeeperException.NoNodeException {
        return dataTree.getData(path, stat, watcher);
    }

    /**
     * set watches on the datatree
     * @param relativeZxid the relative zxid that client has seen
     * @param dataWatches the data watches the client wants to reset
     * @param existWatches the exists watches the client wants to reset
     * @param childWatches the child watches the client wants to reset
     * @param persistentWatches the persistent watches the client wants to reset
     * @param persistentRecursiveWatches the persistent recursive watches the client wants to reset
     * @param watcher the watcher function
     */
    public void setWatches(long relativeZxid, List<String> dataWatches, List<String> existWatches, List<String> childWatches,
                           List<String> persistentWatches, List<String> persistentRecursiveWatches, Watcher watcher) {
        dataTree.setWatches(relativeZxid, dataWatches, existWatches, childWatches, persistentWatches, persistentRecursiveWatches, watcher);
    }

    /**
     * Add a watch
     *
     * @param basePath
     *            watch base
     * @param watcher
     *            the watcher
     * @param mode
     *            a mode from ZooDefs.AddWatchModes
     */
    public void addWatch(String basePath, Watcher watcher, int mode) {
        dataTree.addWatch(basePath, watcher, mode);
    }

    /**
     * get acl for a path
     * @param path the path to query for acl
     * @param stat the stat for the node
     * @return the acl list for this path
     * @throws NoNodeException
     */
    public List<ACL> getACL(String path, Stat stat) throws NoNodeException {
        return dataTree.getACL(path, stat);
    }

    /**
     * get children list for this path
     * @param path the path of the node
     * @param stat the stat of the node
     * @param watcher the watcher function for this path
     * @return the list of children for this path
     * @throws KeeperException.NoNodeException
     */
    public List<String> getChildren(String path, Stat stat, Watcher watcher) throws KeeperException.NoNodeException {
        return dataTree.getChildren(path, stat, watcher);
    }

    /*
     * get all sub-children number of this node
     * */
    public int getAllChildrenNumber(String path) throws KeeperException.NoNodeException {
        return dataTree.getAllChildrenNumber(path);
    }

    /**
     * check if the path is special or not
     * @param path the input path
     * @return true if path is special and false if not
     */
    public boolean isSpecialPath(String path) {
        return dataTree.isSpecialPath(path);
    }

    /**
     * get the acl size of the datatree
     * @return the acl size of the datatree
     */
    public int getAclSize() {
        return dataTree.aclCacheSize();
    }

    /**
     * Truncate the ZKDatabase to the specified zxid
     * @param zxid the zxid to truncate zk database to
     * @return true if the truncate is successful and false if not
     * @throws IOException
     */
    public boolean truncateLog(long zxid) throws IOException {
        clear();

        // truncate the log
        boolean truncated = snapLog.truncateLog(zxid);

        if (!truncated) {
            return false;
        }

        loadDataBase();
        return true;
    }

    /**
     * deserialize a snapshot from an input archive
     * @param ia the input archive you want to deserialize from
     * @throws IOException
     */
    public void deserializeSnapshot(InputArchive ia) throws IOException {
        clear();
        SerializeUtils.deserializeSnapshot(getDataTree(), ia, getSessionWithTimeOuts());
        initialized = true;
    }

    /**
     * serialize the snapshot
     * @param oa the output archive to which the snapshot needs to be serialized
     * @throws IOException
     * @throws InterruptedException
     */
    public void serializeSnapshot(OutputArchive oa) throws IOException, InterruptedException {
        SerializeUtils.serializeSnapshot(getDataTree(), oa, getSessionWithTimeOuts());
    }

    /**
     * append to the underlying transaction log
     * @param si the request to append
     * @return true if the append was succesfull and false if not
     */
    public boolean append(Request si) throws IOException {
        if (this.snapLog.append(si)) {
            txnCount.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * roll the underlying log
     */
    public void rollLog() throws IOException {
        this.snapLog.rollLog();
        resetTxnCount();
    }

    /**
     * commit to the underlying transaction log
     * @throws IOException
     */
    public void commit() throws IOException {
        this.snapLog.commit();
    }

    /**
     * close this database. free the resources
     * @throws IOException
     */
    public void close() throws IOException {
        this.snapLog.close();
    }

    public synchronized void initConfigInZKDatabase(QuorumVerifier qv) {
        if (qv == null) {
            return; // only happens during tests
        }
        try {
            if (this.dataTree.getNode(ZooDefs.CONFIG_NODE) == null) {
                // should only happen during upgrade
                LOG.warn("configuration znode missing (should only happen during upgrade), creating the node");
                this.dataTree.addConfigNode();
            }
            this.dataTree.setData(
                ZooDefs.CONFIG_NODE,
                qv.toString().getBytes(UTF_8),
                -1,
                qv.getVersion(),
                Time.currentWallTime());
        } catch (NoNodeException e) {
            System.out.println("configuration node missing - should not happen");
        }
    }

    /**
     * Use for unit testing, so we can turn this feature on/off
     * @param snapshotSizeFactor Set to minus value to turn this off.
     */
    public void setSnapshotSizeFactor(double snapshotSizeFactor) {
        this.snapshotSizeFactor = snapshotSizeFactor;
    }

    /**
     * Check whether the given watcher exists in datatree
     *
     * @param path
     *            node to check watcher existence
     * @param type
     *            type of watcher
     * @param watcher
     *            watcher function
     */
    public boolean containsWatcher(String path, WatcherType type, Watcher watcher) {
        return dataTree.containsWatcher(path, type, watcher);
    }

    /**
     * Remove watch from the datatree
     *
     * @param path
     *            node to remove watches from
     * @param type
     *            type of watcher to remove
     * @param watcher
     *            watcher function to remove
     */
    public boolean removeWatch(String path, WatcherType type, Watcher watcher) {
        return dataTree.removeWatch(path, type, watcher);
    }

    // visible for testing
    public DataTree createDataTree() {
        return new DataTree();
    }

    /**
     * Reset the number of txn since last rollLog
     */
    public void resetTxnCount() {
        txnCount.set(0);
        snapLog.setTotalLogSize(0);
    }

    /**
     * Get the number of txn since last snapshot
     */
    public int getTxnCount() {
        return txnCount.get();
    }

    /**
     * Get the size of txn since last snapshot
     */
    public long getTxnSize() {
        return snapLog.getTotalLogSize();
    }

    public boolean compareDigest(TxnHeader header, Record txn, TxnDigest digest) {
        return dataTree.compareDigest(header, txn, digest);
    }
}
