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

package org.apache.zookeeper.server;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.log4j.Logger;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.server.SessionTracker.SessionExpirer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog.PlayBackListener;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.QuorumPacket;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * This class implements a simple standalone ZooKeeperServer. It sets up the
 * following chain of RequestProcessors to process requests:
 * PrepRequestProcessor -> SyncRequestProcessor -> FinalRequestProcessor
 */
public class ZooKeeperServer implements SessionExpirer, ServerStats.Provider {
    private static final Logger LOG = Logger.getLogger(ZooKeeperServer.class);

    /**
     * Create an instance of Zookeeper server
     */
    static public interface Factory {
        public ZooKeeperServer createServer() throws IOException;

        public NIOServerCnxn.Factory createConnectionFactory()
                throws IOException;
    }

    /**
     * The server delegates loading of the tree to an instance of the interface
     */
    public interface DataTreeBuilder {
        public DataTree build();
    }

    static public class BasicDataTreeBuilder implements DataTreeBuilder {
        public DataTree build() {
            return new DataTree();
        }
    }

    private static final int DEFAULT_TICK_TIME = 3000;
    protected int tickTime = DEFAULT_TICK_TIME;

    public static final int commitLogCount = 500;
    public int commitLogBuffer = 700;
    public LinkedList<Proposal> committedLog = new LinkedList<Proposal>();
    public long minCommittedLog, maxCommittedLog;
    private DataTreeBuilder treeBuilder;
    public DataTree dataTree;
    protected SessionTracker sessionTracker;
    private FileTxnSnapLog txnLogFactory = null;
    protected ConcurrentHashMap<Long, Integer> sessionsWithTimeouts;
    protected long hzxid = 0;
    final public static Exception ok = new Exception("No prob");
    protected RequestProcessor firstProcessor;
    LinkedBlockingQueue<Long> sessionsToDie = new LinkedBlockingQueue<Long>();
    protected volatile boolean running;
    /**
     * This is the secret that we use to generate passwords, for the moment it
     * is more of a sanity check.
     */
    final private long superSecret = 0XB3415C00L;
    int requestsInProcess;
    List<ChangeRecord> outstandingChanges = new ArrayList<ChangeRecord>();
    private NIOServerCnxn.Factory serverCnxnFactory;
    private int clientPort;
    
    void removeCnxn(ServerCnxn cnxn) {
        dataTree.removeCnxn(cnxn);
    }

 
    /**
     * Creates a ZooKeeperServer instance. Nothing is setup, use the setX
     * methods to prepare the instance (eg datadir, datalogdir, ticktime, 
     * builder, etc...)
     * 
     * @throws IOException
     */
    public ZooKeeperServer() {
        ServerStats.getInstance().setStatsProvider(this);
        treeBuilder = new BasicDataTreeBuilder();
    }

    
    /**
     * Creates a ZooKeeperServer instance. It sets everything up, but doesn't
     * actually start listening for clients until run() is invoked.
     *
     * @param dataDir the directory to put the data
     * @throws IOException
     */
    public ZooKeeperServer(FileTxnSnapLog txnLogFactory, int tickTime,
            DataTreeBuilder treeBuilder) throws IOException {
        this.txnLogFactory=txnLogFactory;
        this.tickTime = tickTime;
        this.treeBuilder = treeBuilder;
        ServerStats.getInstance().setStatsProvider(this);
        
        LOG.info("Created server");
    }

    /**
     * This constructor is for backward compatibility with the existing unit
     * test code.
     * It defaults to FileLogProvider persistence provider.
     */
    public ZooKeeperServer(File snapDir, File logDir, int tickTime)
            throws IOException {
        this(new FileTxnSnapLog(snapDir,logDir),
                tickTime,new BasicDataTreeBuilder());
    }

    /**
     * Default constructor, relies on the config for its agrument values
     *
     * @throws IOException
     */
    public ZooKeeperServer(FileTxnSnapLog txnLogFactory,DataTreeBuilder treeBuilder) throws IOException {
        this(txnLogFactory, DEFAULT_TICK_TIME, treeBuilder);
    }

    /**
     *  Restore sessions and data
     */
    public void loadData() throws IOException, InterruptedException {
        PlayBackListener listener=new PlayBackListener(){
            public void onTxnLoaded(TxnHeader hdr,Record txn){
                Request r = new Request(null, 0, hdr.getCxid(),hdr.getType(),
                        null, null);
                r.txn = txn;
                r.hdr = hdr;
                r.zxid = hdr.getZxid();
                addCommittedProposal(r);
            }
        };
        sessionsWithTimeouts = new ConcurrentHashMap<Long, Integer>();
        dataTree = treeBuilder.build();
        long zxid = txnLogFactory.restore(dataTree,sessionsWithTimeouts,listener);
        this.hzxid = zxid;
        // Clean up dead sessions
        LinkedList<Long> deadSessions = new LinkedList<Long>();
        for (long session : dataTree.getSessions()) {
            if (sessionsWithTimeouts.get(session) == null) {
                deadSessions.add(session);
            }
        }
        dataTree.initialized = true;
        for (long session : deadSessions) {
            // XXX: Is lastProcessedZxid really the best thing to use?
            killSession(session, dataTree.lastProcessedZxid);
        }
        // Make a clean snapshot
        takeSnapshot();
    }

    /**
     * maintains a list of last 500 or so committed requests. This is used for
     * fast follower synchronization.
     *
     * @param request committed request
     */

    public void addCommittedProposal(Request request) {
        synchronized (committedLog) {
            if (committedLog.size() > commitLogCount) {
                committedLog.removeFirst();
                minCommittedLog = committedLog.getFirst().packet.getZxid();
            }
            if (committedLog.size() == 0) {
                minCommittedLog = request.zxid;
                maxCommittedLog = request.zxid;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
            try {
                request.hdr.serialize(boa, "hdr");
                if (request.txn != null) {
                    request.txn.serialize(boa, "txn");
                }
                baos.close();
            } catch (IOException e) {
                // This really should be impossible
                LOG.error("FIXMSG",e);
            }
            QuorumPacket pp = new QuorumPacket(Leader.PROPOSAL, request.zxid,
                    baos.toByteArray(), null);
            Proposal p = new Proposal();
            p.packet = pp;
            p.request = request;
            committedLog.add(p);
            maxCommittedLog = p.packet.getZxid();
        }
    }

    public void takeSnapshot(){
        try {
            txnLogFactory.save(dataTree, sessionsWithTimeouts);
        } catch (IOException e) {
            LOG.error("Severe error, exiting",e);
            // This is a severe error that we cannot recover from,
            // so we need to exit
            System.exit(10);
        }
    }

    public void serializeSnapshot(OutputArchive oa) throws IOException,
            InterruptedException {
        SerializeUtils.serializeSnapshot(dataTree, oa, sessionsWithTimeouts);
    }

    public void deserializeSnapshot(InputArchive ia) throws IOException {
        sessionsWithTimeouts = new ConcurrentHashMap<Long, Integer>();
        dataTree = treeBuilder.build();

        SerializeUtils.deserializeSnapshot(dataTree,ia,sessionsWithTimeouts);
    }

    /**
     * This should be called from a synchronized block on this!
     */
    public long getZxid() {
        return hzxid;
    }

    synchronized long getNextZxid() {
        return ++hzxid;
    }

    long getTime() {
        return System.currentTimeMillis();
    }

    public void closeSession(long sessionId) throws InterruptedException {
        ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                                 "ZooKeeperServer --- Session to be closed: 0x"
                + Long.toHexString(sessionId));
        // we do not want to wait for a session close. send it as soon as we
        // detect it!
        submitRequest(null, sessionId, OpCode.closeSession, 0, null, null);
    }

    protected void killSession(long sessionId, long zxid) {
        dataTree.killSession(sessionId, zxid);
        ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                                     "ZooKeeperServer --- killSession: 0x"
                + Long.toHexString(sessionId));
        if (sessionTracker != null) {
            sessionTracker.removeSession(sessionId);
        }
    }

    public void expire(long sessionId) {
        try {
            ZooTrace.logTraceMessage(LOG,
                     ZooTrace.SESSION_TRACE_MASK,
                    "ZooKeeperServer --- Session to expire: 0x" 
                     + Long.toHexString(sessionId));
            closeSession(sessionId);
        } catch (Exception e) {
            LOG.error("FIXMSG",e);
        }
    }

    void touch(ServerCnxn cnxn) throws IOException {
        if (cnxn == null) {
            return;
        }
        long id = cnxn.getSessionId();
        int to = cnxn.getSessionTimeout();
        if (!sessionTracker.touchSession(id, to)) {
            throw new IOException("Missing session 0x" + Long.toHexString(id));
        }
    }

    public void startup() throws IOException, InterruptedException {
        if (dataTree == null) {
            loadData();
        }
        createSessionTracker();
        setupRequestProcessors();
        synchronized (this) {
            running = true;
            notifyAll();
        }
    }

    protected void setupRequestProcessors() {
        RequestProcessor finalProcessor = new FinalRequestProcessor(this);
        RequestProcessor syncProcessor = new SyncRequestProcessor(this,
                finalProcessor);
        firstProcessor = new PrepRequestProcessor(this, syncProcessor);
    }

    protected void createSessionTracker() {
        sessionTracker = new SessionTrackerImpl(this, sessionsWithTimeouts,
                tickTime, 1);
    }

    public boolean isRunning() {
        return running;
    }

    public void shutdown() {
        // new RuntimeException("Calling shutdown").printStackTrace();
        this.running = false;
        // Since sessionTracker and syncThreads poll we just have to
        // set running to false and they will detect it during the poll
        // interval.
        if (sessionTracker != null) {
            sessionTracker.shutdown();
        }
        if (firstProcessor != null) {
            firstProcessor.shutdown();
        }
        if (dataTree != null) {
            dataTree.clear();
        }
    }

    synchronized public void incInProcess() {
        requestsInProcess++;
    }

    synchronized public void decInProcess() {
        requestsInProcess--;
    }

    public int getInProcess() {
        return requestsInProcess;
    }

    /**
     * This structure is used to facilitate information sharing between PrepRP
     * and FinalRP.
     */
    static class ChangeRecord {
        ChangeRecord(long zxid, String path, StatPersisted stat, int childCount,
                List<ACL> acl) {
            this.zxid = zxid;
            this.path = path;
            this.stat = stat;
            this.childCount = childCount;
            this.acl = acl;
        }

        long zxid;

        String path;

        StatPersisted stat; /* Make sure to create a new object when changing */

        int childCount;

        List<ACL> acl; /* Make sure to create a new object when changing */

        @SuppressWarnings("unchecked")
        ChangeRecord duplicate(long zxid) {
            StatPersisted stat = new StatPersisted();
            if (this.stat != null) {
                DataTree.copyStatPersisted(this.stat, stat);
            }
            return new ChangeRecord(zxid, path, stat, childCount,
                    acl == null ? new ArrayList<ACL>() : new ArrayList(acl));
        }
    }

    byte[] generatePasswd(long id) {
        Random r = new Random(id ^ superSecret);
        byte p[] = new byte[16];
        r.nextBytes(p);
        return p;
    }

    protected boolean checkPasswd(long sessionId, byte[] passwd) {
        return sessionId != 0
                && Arrays.equals(passwd, generatePasswd(sessionId));
    }

    long createSession(ServerCnxn cnxn, byte passwd[], int timeout)
            throws InterruptedException {
        long sessionId = sessionTracker.createSession(timeout);
        Random r = new Random(sessionId ^ superSecret);
        r.nextBytes(passwd);
        ByteBuffer to = ByteBuffer.allocate(4);
        to.putInt(timeout);
        cnxn.setSessionId(sessionId);
        submitRequest(cnxn, sessionId, OpCode.createSession, 0, to, null);
        return sessionId;
    }

    protected void revalidateSession(ServerCnxn cnxn, long sessionId,
            int sessionTimeout) throws IOException, InterruptedException {
        boolean rc = sessionTracker.touchSession(sessionId, sessionTimeout);
        ZooTrace.logTraceMessage(LOG,ZooTrace.SESSION_TRACE_MASK,
                                 "Session 0x" + Long.toHexString(sessionId) +
                " is valid: " + rc);
        cnxn.finishSessionInit(rc);
    }

    public void reopenSession(ServerCnxn cnxn, long sessionId, byte[] passwd,
            int sessionTimeout) throws IOException, InterruptedException {
        if (!checkPasswd(sessionId, passwd)) {
            cnxn.finishSessionInit(false);
        } else {
            revalidateSession(cnxn, sessionId, sessionTimeout);
        }
    }

    public void closeSession(ServerCnxn cnxn, RequestHeader requestHeader)
            throws InterruptedException {
        closeSession(cnxn.getSessionId());
    }

    public long getServerId() {
        return 0;
    }

    /**
     * @param cnxn
     * @param sessionId
     * @param xid
     * @param bb
     */
    public void submitRequest(ServerCnxn cnxn, long sessionId, int type,
            int xid, ByteBuffer bb, List<Id> authInfo) {
        Request si = new Request(cnxn, sessionId, xid, type, bb, authInfo);
        submitRequest(si);
    }
    
    public void submitRequest(Request si) {
        if (firstProcessor == null) {
            synchronized (this) {
                try {
                    while (!running) {
                        wait(1000);
                    }
                } catch (InterruptedException e) {
                    LOG.error("FIXMSG",e);
                }
                if (firstProcessor == null) {
                    throw new RuntimeException("Not started");
                }
            }
        }
        try {
            touch(si.cnxn);
            boolean validpacket = Request.isValid(si.type);
            if (validpacket) {
                firstProcessor.processRequest(si);
                if (si.cnxn != null) {
                    incInProcess();
                }
            } else {
                LOG.warn("Dropping packet at server of type " + si.type);
                // if unvalid packet drop the packet.
            }
        } catch (IOException e) {
            LOG.error("FIXMSG",e);
        }
    }

    static public void byteBuffer2Record(ByteBuffer bb, Record record)
            throws IOException {
        BinaryInputArchive ia;
        ia = BinaryInputArchive.getArchive(new ByteBufferInputStream(bb));
        record.deserialize(ia, "request");
    }

    public static int getSnapCount() {
        String sc = System.getProperty("zookeeper.snapCount");
        try {
            return Integer.parseInt(sc);
        } catch (Exception e) {
            return 10000;
        }
    }

    public int getGlobalOutstandingLimit() {
        String sc = System.getProperty("zookeeper.globalOutstandingLimit");
        int limit;
        try {
            limit = Integer.parseInt(sc);
        } catch (Exception e) {
            limit = 1000;
        }
        return limit;
    }

    public void setServerCnxnFactory(NIOServerCnxn.Factory factory) {
        serverCnxnFactory = factory;
    }

    public NIOServerCnxn.Factory getServerCnxnFactory() {
        return serverCnxnFactory;
    }

    /**
     * return the last proceesed id from the 
     * datatree
     */
    public long getLastProcessedZxid() {
        return dataTree.lastProcessedZxid;
    }

    /**
     * return the outstanding requests
     * in the queue, which havent been 
     * processed yet
     */
    public long getOutstandingRequests() {
        return getInProcess();
    }

    /**
     * trunccate the log to get in sync with others 
     * if in a quorum
     * @param zxid the zxid that it needs to get in sync
     * with others
     * @throws IOException
     */
    public void truncateLog(long zxid) throws IOException {
        this.txnLogFactory.truncateLog(zxid);
    }
    
    /**
     * the snapshot and logwriter for this instance
     * @return
     */
    public FileTxnSnapLog getLogWriter() {
        return this.txnLogFactory;
    }
    
    public int getTickTime() {
        return tickTime;
    }

    public void setTickTime(int tickTime) {
        this.tickTime = tickTime;
    }

    public DataTreeBuilder getTreeBuilder() {
        return treeBuilder;
    }

    public void setTreeBuilder(DataTreeBuilder treeBuilder) {
        this.treeBuilder = treeBuilder;
    }
    
    public int getClientPort() {
        return clientPort;
    }

    public void setClientPort(int clientPort) {
        this.clientPort = clientPort;
    }
    
    public void setTxnLogFactory(FileTxnSnapLog txnLog) {
        this.txnLogFactory = txnLog;
    }
    
    public FileTxnSnapLog getTxnLogFactory() {
        return this.txnLogFactory;
    }
}
