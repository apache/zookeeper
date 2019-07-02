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
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import javax.security.sasl.SaslException;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.Environment;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.Version;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.ZookeeperBanner;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.proto.AuthPacket;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;
import org.apache.zookeeper.proto.GetSASLRequest;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.proto.SetSASLResponse;
import org.apache.zookeeper.server.DataTree.ProcessTxnResult;
import org.apache.zookeeper.server.RequestProcessor.RequestProcessorException;
import org.apache.zookeeper.server.ServerCnxn.CloseRequestException;
import org.apache.zookeeper.server.SessionTracker.Session;
import org.apache.zookeeper.server.SessionTracker.SessionExpirer;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.auth.ServerAuthenticationProvider;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.ReadOnlyZooKeeperServer;
import org.apache.zookeeper.server.util.JvmPauseMonitor;
import org.apache.zookeeper.server.util.OSMXBean;
import org.apache.zookeeper.txn.CreateSessionTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class implements a simple standalone ZooKeeperServer. It sets up the
 * following chain of RequestProcessors to process requests:
 * PrepRequestProcessor -&gt; SyncRequestProcessor -&gt; FinalRequestProcessor
 */
public class ZooKeeperServer implements SessionExpirer, ServerStats.Provider {
    protected static final Logger LOG;

    public static final String GLOBAL_OUTSTANDING_LIMIT = "zookeeper.globalOutstandingLimit";

    static {
        LOG = LoggerFactory.getLogger(ZooKeeperServer.class);

        ZookeeperBanner.printBanner(LOG);

        Environment.logEnv("Server environment:", LOG);
    }

    protected ZooKeeperServerBean jmxServerBean;
    protected DataTreeBean jmxDataTreeBean;

    public static final int DEFAULT_TICK_TIME = 3000;
    protected int tickTime = DEFAULT_TICK_TIME;
    /** value of -1 indicates unset, use default */
    protected int minSessionTimeout = -1;
    /** value of -1 indicates unset, use default */
    protected int maxSessionTimeout = -1;
    /** Socket listen backlog. Value of -1 indicates unset */
    protected int listenBacklog = -1;
    protected SessionTracker sessionTracker;
    private FileTxnSnapLog txnLogFactory = null;
    private ZKDatabase zkDb;
    private ResponseCache readResponseCache;
    private final AtomicLong hzxid = new AtomicLong(0);
    public final static Exception ok = new Exception("No prob");
    protected RequestProcessor firstProcessor;
    protected JvmPauseMonitor jvmPauseMonitor;
    protected volatile State state = State.INITIAL;
    private boolean isResponseCachingEnabled = true;
    /* contains the configuration file content read at startup */
    protected String initialConfig;

    protected enum State {
        INITIAL, RUNNING, SHUTDOWN, ERROR
    }

    /**
     * This is the secret that we use to generate passwords. For the moment,
     * it's more of a checksum that's used in reconnection, which carries no
     * security weight, and is treated internally as if it carries no
     * security weight.
     */
    static final private long superSecret = 0XB3415C00L;

    private final AtomicInteger requestsInProcess = new AtomicInteger(0);
    final Deque<ChangeRecord> outstandingChanges = new ArrayDeque<>();
    // this data structure must be accessed under the outstandingChanges lock
    final Map<String, ChangeRecord> outstandingChangesForPath =
        new HashMap<String, ChangeRecord>();

    protected ServerCnxnFactory serverCnxnFactory;
    protected ServerCnxnFactory secureServerCnxnFactory;

    private final ServerStats serverStats;
    private final ZooKeeperServerListener listener;
    private ZooKeeperServerShutdownHandler zkShutdownHandler;
    private volatile int createSessionTrackerServerId = 1;

    private static final String FLUSH_DELAY = "zookeeper.flushDelay";
    private static volatile long flushDelay;
    private static final String MAX_WRITE_QUEUE_POLL_SIZE = "zookeeper.maxWriteQueuePollTime";
    private static volatile long maxWriteQueuePollTime;
    private static final String MAX_BATCH_SIZE = "zookeeper.maxBatchSize";
    private static volatile int maxBatchSize;

    /**
     * Starting size of read and write ByteArroyOuputBuffers. Default is 32 bytes.
     * Flag not used for small transfers like connectResponses.
     */
    public static final String INT_BUFFER_STARTING_SIZE_BYTES =
            "zookeeper.intBufferStartingSizeBytes";
    public static final int DEFAULT_STARTING_BUFFER_SIZE = 1024;
    public static final int intBufferStartingSizeBytes;

    static {
        long configuredFlushDelay = Long.getLong(FLUSH_DELAY, 0);
        setFlushDelay(configuredFlushDelay);
        setMaxWriteQueuePollTime(Long.getLong(MAX_WRITE_QUEUE_POLL_SIZE, configuredFlushDelay / 3));
        setMaxBatchSize(Integer.getInteger(MAX_BATCH_SIZE, 1000));

        intBufferStartingSizeBytes = Integer.getInteger(
                INT_BUFFER_STARTING_SIZE_BYTES,
                DEFAULT_STARTING_BUFFER_SIZE);

        if (intBufferStartingSizeBytes < 32) {
            String msg = "Buffer starting size must be greater than 0." +
                    "Configure with \"-Dzookeeper.intBufferStartingSizeBytes=<size>\" ";
            LOG.error(msg);
            throw new IllegalArgumentException(msg);
        }

        LOG.info(INT_BUFFER_STARTING_SIZE_BYTES + " = " + intBufferStartingSizeBytes);
    }

    // Connection throttling
    private BlueThrottle connThrottle;

    void removeCnxn(ServerCnxn cnxn) {
        zkDb.removeCnxn(cnxn);
    }

    /**
     * Creates a ZooKeeperServer instance. Nothing is setup, use the setX
     * methods to prepare the instance (eg datadir, datalogdir, ticktime,
     * builder, etc...)
     *
     */
    public ZooKeeperServer() {
        listener = new ZooKeeperServerListenerImpl(this);
        serverStats = new ServerStats(this);
    }

    /**
     * Creates a ZooKeeperServer instance. It sets everything up, but doesn't
     * actually start listening for clients until run() is invoked.
     *
     */
    public ZooKeeperServer(FileTxnSnapLog txnLogFactory, int tickTime,
            int minSessionTimeout, int maxSessionTimeout, int clientPortListenBacklog,
            ZKDatabase zkDb, String initialConfig) {
        serverStats = new ServerStats(this);
        this.txnLogFactory = txnLogFactory;
        this.txnLogFactory.setServerStats(this.serverStats);
        this.zkDb = zkDb;
        this.tickTime = tickTime;
        setMinSessionTimeout(minSessionTimeout);
        setMaxSessionTimeout(maxSessionTimeout);
        this.listenBacklog = clientPortListenBacklog;

        listener = new ZooKeeperServerListenerImpl(this);

        readResponseCache = new ResponseCache();

        connThrottle = new BlueThrottle();

        this.initialConfig = initialConfig;

        LOG.info("Created server with tickTime " + tickTime
                + " minSessionTimeout " + getMinSessionTimeout()
                + " maxSessionTimeout " + getMaxSessionTimeout()
                + " clientPortListenBacklog " + getClientPortListenBacklog()
                + " datadir " + txnLogFactory.getDataDir()
                + " snapdir " + txnLogFactory.getSnapDir());

    }

    public String getInitialConfig() {
        return initialConfig;
    }

    /**
     * Adds JvmPauseMonitor and calls
     * {@link #ZooKeeperServer(FileTxnSnapLog, int, int, int, int, ZKDatabase, String)}
     *
     */
    public ZooKeeperServer(JvmPauseMonitor jvmPauseMonitor, FileTxnSnapLog txnLogFactory, int tickTime,
                           int minSessionTimeout, int maxSessionTimeout, int clientPortListenBacklog,
                           ZKDatabase zkDb, String initialConfig) {
        this(txnLogFactory, tickTime, minSessionTimeout, maxSessionTimeout, clientPortListenBacklog, zkDb, initialConfig);
        this.jvmPauseMonitor = jvmPauseMonitor;
        if(jvmPauseMonitor != null) {
            LOG.info("Added JvmPauseMonitor to server");
        }
    }

    /**
     * creates a zookeeperserver instance.
     * @param txnLogFactory the file transaction snapshot logging class
     * @param tickTime the ticktime for the server
     * @throws IOException
     */
    public ZooKeeperServer(FileTxnSnapLog txnLogFactory, int tickTime, String initialConfig)
            throws IOException {
        this(txnLogFactory, tickTime, -1, -1, -1, new ZKDatabase(txnLogFactory), initialConfig);
    }

    public ServerStats serverStats() {
        return serverStats;
    }

    public BlueThrottle connThrottle() {
        return connThrottle;
    }

    public void dumpConf(PrintWriter pwriter) {
        pwriter.print("clientPort=");
        pwriter.println(getClientPort());
        pwriter.print("secureClientPort=");
        pwriter.println(getSecureClientPort());
        pwriter.print("dataDir=");
        pwriter.println(zkDb.snapLog.getSnapDir().getAbsolutePath());
        pwriter.print("dataDirSize=");
        pwriter.println(getDataDirSize());
        pwriter.print("dataLogDir=");
        pwriter.println(zkDb.snapLog.getDataDir().getAbsolutePath());
        pwriter.print("dataLogSize=");
        pwriter.println(getLogDirSize());
        pwriter.print("tickTime=");
        pwriter.println(getTickTime());
        pwriter.print("maxClientCnxns=");
        pwriter.println(getMaxClientCnxnsPerHost());
        pwriter.print("minSessionTimeout=");
        pwriter.println(getMinSessionTimeout());
        pwriter.print("maxSessionTimeout=");
        pwriter.println(getMaxSessionTimeout());
        pwriter.print("clientPortListenBacklog=");
        pwriter.println(getClientPortListenBacklog());

        pwriter.print("serverId=");
        pwriter.println(getServerId());
    }

    public ZooKeeperServerConf getConf() {
        return new ZooKeeperServerConf
            (getClientPort(),
             zkDb.snapLog.getSnapDir().getAbsolutePath(),
             zkDb.snapLog.getDataDir().getAbsolutePath(),
             getTickTime(),
             serverCnxnFactory.getMaxClientCnxnsPerHost(),
             getMinSessionTimeout(),
             getMaxSessionTimeout(),
             getServerId(),
             getClientPortListenBacklog());
    }

    /**
     * This constructor is for backward compatibility with the existing unit
     * test code.
     * It defaults to FileLogProvider persistence provider.
     */
    public ZooKeeperServer(File snapDir, File logDir, int tickTime)
            throws IOException {
        this( new FileTxnSnapLog(snapDir, logDir),
                tickTime, "");
    }

    /**
     * Default constructor, relies on the config for its argument values
     *
     * @throws IOException
     */
    public ZooKeeperServer(FileTxnSnapLog txnLogFactory)
        throws IOException
    {
        this(txnLogFactory, DEFAULT_TICK_TIME, -1, -1, -1, new ZKDatabase(txnLogFactory), "");
    }

    /**
     * get the zookeeper database for this server
     * @return the zookeeper database for this server
     */
    public ZKDatabase getZKDatabase() {
        return this.zkDb;
    }

    /**
     * set the zkdatabase for this zookeeper server
     * @param zkDb
     */
    public void setZKDatabase(ZKDatabase zkDb) {
       this.zkDb = zkDb;
    }

    /**
     *  Restore sessions and data
     */
    public void loadData() throws IOException, InterruptedException {
        /*
         * When a new leader starts executing Leader#lead, it
         * invokes this method. The database, however, has been
         * initialized before running leader election so that
         * the server could pick its zxid for its initial vote.
         * It does it by invoking QuorumPeer#getLastLoggedZxid.
         * Consequently, we don't need to initialize it once more
         * and avoid the penalty of loading it a second time. Not
         * reloading it is particularly important for applications
         * that host a large database.
         *
         * The following if block checks whether the database has
         * been initialized or not. Note that this method is
         * invoked by at least one other method:
         * ZooKeeperServer#startdata.
         *
         * See ZOOKEEPER-1642 for more detail.
         */
        if(zkDb.isInitialized()){
            setZxid(zkDb.getDataTreeLastProcessedZxid());
        }
        else {
            setZxid(zkDb.loadDataBase());
        }

        // Clean up dead sessions
        List<Long> deadSessions = new ArrayList<>();
        for (Long session : zkDb.getSessions()) {
            if (zkDb.getSessionWithTimeOuts().get(session) == null) {
                deadSessions.add(session);
            }
        }

        for (long session : deadSessions) {
            // XXX: Is lastProcessedZxid really the best thing to use?
            killSession(session, zkDb.getDataTreeLastProcessedZxid());
        }

        // Make a clean snapshot
        takeSnapshot();
    }

    public void takeSnapshot() {
        takeSnapshot(false);
    }

    public void takeSnapshot(boolean syncSnap){
        long start = Time.currentElapsedTime();
        try {
            txnLogFactory.save(zkDb.getDataTree(), zkDb.getSessionWithTimeOuts(), syncSnap);
        } catch (IOException e) {
            LOG.error("Severe unrecoverable error, exiting", e);
            // This is a severe error that we cannot recover from,
            // so we need to exit
            System.exit(ExitCode.TXNLOG_ERROR_TAKING_SNAPSHOT.getValue());
        }
        long elapsed = Time.currentElapsedTime() - start;
        LOG.info("Snapshot taken in " + elapsed + " ms");
        ServerMetrics.getMetrics().SNAPSHOT_TIME.add(elapsed);
    }

    @Override
    public long getDataDirSize() {
        if (zkDb == null) {
            return 0L;
        }
        File path = zkDb.snapLog.getDataDir();
        return getDirSize(path);
    }

    @Override
    public long getLogDirSize() {
        if (zkDb == null) {
            return 0L;
        }
        File path = zkDb.snapLog.getSnapDir();
        return getDirSize(path);
    }

    private long getDirSize(File file) {
        long size = 0L;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    size += getDirSize(f);
                }
            }
        } else {
            size = file.length();
        }
        return size;
    }

    public long getZxid() {
        return hzxid.get();
    }

    public SessionTracker getSessionTracker() {
        return sessionTracker;
    }

    long getNextZxid() {
        return hzxid.incrementAndGet();
    }

    public void setZxid(long zxid) {
        hzxid.set(zxid);
    }

    private void close(long sessionId) {
        Request si = new Request(null, sessionId, 0, OpCode.closeSession, null, null);
        setLocalSessionFlag(si);
        submitRequest(si);
    }

    public void closeSession(long sessionId) {
        LOG.info("Closing session 0x" + Long.toHexString(sessionId));

        // we do not want to wait for a session close. send it as soon as we
        // detect it!
        close(sessionId);
    }

    protected void killSession(long sessionId, long zxid) {
        zkDb.killSession(sessionId, zxid);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                                         "ZooKeeperServer --- killSession: 0x"
                    + Long.toHexString(sessionId));
        }
        if (sessionTracker != null) {
            sessionTracker.removeSession(sessionId);
        }
    }

    public void expire(Session session) {
        long sessionId = session.getSessionId();
        LOG.info("Expiring session 0x" + Long.toHexString(sessionId)
                + ", timeout of " + session.getTimeout() + "ms exceeded");
        close(sessionId);
    }

    public static class MissingSessionException extends IOException {
        private static final long serialVersionUID = 7467414635467261007L;

        public MissingSessionException(String msg) {
            super(msg);
        }
    }

    void touch(ServerCnxn cnxn) throws MissingSessionException {
        if (cnxn == null) {
            return;
        }
        long id = cnxn.getSessionId();
        int to = cnxn.getSessionTimeout();
        if (!sessionTracker.touchSession(id, to)) {
            throw new MissingSessionException(
                    "No session with sessionid 0x" + Long.toHexString(id)
                    + " exists, probably expired and removed");
        }
    }

    protected void registerJMX() {
        // register with JMX
        try {
            jmxServerBean = new ZooKeeperServerBean(this);
            MBeanRegistry.getInstance().register(jmxServerBean, null);

            try {
                jmxDataTreeBean = new DataTreeBean(zkDb.getDataTree());
                MBeanRegistry.getInstance().register(jmxDataTreeBean, jmxServerBean);
            } catch (Exception e) {
                LOG.warn("Failed to register with JMX", e);
                jmxDataTreeBean = null;
            }
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            jmxServerBean = null;
        }
    }

    public void startdata()
    throws IOException, InterruptedException {
        //check to see if zkDb is not null
        if (zkDb == null) {
            zkDb = new ZKDatabase(this.txnLogFactory);
        }
        if (!zkDb.isInitialized()) {
            loadData();
        }
    }

    public synchronized void startup() {
        if (sessionTracker == null) {
            createSessionTracker();
        }
        startSessionTracker();
        setupRequestProcessors();

        registerJMX();

        startJvmPauseMonitor();

        registerMetrics();

        setState(State.RUNNING);
        notifyAll();
    }

    protected void startJvmPauseMonitor() {
        if (this.jvmPauseMonitor != null) {
            this.jvmPauseMonitor.serviceStart();
        }
    }

    protected void setupRequestProcessors() {
        RequestProcessor finalProcessor = new FinalRequestProcessor(this);
        RequestProcessor syncProcessor = new SyncRequestProcessor(this,
                finalProcessor);
        ((SyncRequestProcessor)syncProcessor).start();
        firstProcessor = new PrepRequestProcessor(this, syncProcessor);
        ((PrepRequestProcessor)firstProcessor).start();
    }

    public ZooKeeperServerListener getZooKeeperServerListener() {
        return listener;
    }

    /**
     * Change the server ID used by {@link #createSessionTracker()}. Must be called prior to
     * {@link #startup()} being called
     *
     * @param newId ID to use
     */
    public void setCreateSessionTrackerServerId(int newId) {
        createSessionTrackerServerId = newId;
    }

    protected void createSessionTracker() {
        sessionTracker = new SessionTrackerImpl(this, zkDb.getSessionWithTimeOuts(),
                tickTime, createSessionTrackerServerId, getZooKeeperServerListener());
    }

    protected void startSessionTracker() {
        ((SessionTrackerImpl)sessionTracker).start();
    }

    /**
     * Sets the state of ZooKeeper server. After changing the state, it notifies
     * the server state change to a registered shutdown handler, if any.
     * <p>
     * The following are the server state transitions:
     * <ul><li>During startup the server will be in the INITIAL state.</li>
     * <li>After successfully starting, the server sets the state to RUNNING.
     * </li>
     * <li>The server transitions to the ERROR state if it hits an internal
     * error. {@link ZooKeeperServerListenerImpl} notifies any critical resource
     * error events, e.g., SyncRequestProcessor not being able to write a txn to
     * disk.</li>
     * <li>During shutdown the server sets the state to SHUTDOWN, which
     * corresponds to the server not running.</li></ul>
     *
     * @param state new server state.
     */
    protected void setState(State state) {
        this.state = state;
        // Notify server state changes to the registered shutdown handler, if any.
        if (zkShutdownHandler != null) {
            zkShutdownHandler.handle(state);
        } else {
            LOG.debug("ZKShutdownHandler is not registered, so ZooKeeper server "
                    + "won't take any action on ERROR or SHUTDOWN server state changes");
        }
    }

    /**
     * This can be used while shutting down the server to see whether the server
     * is already shutdown or not.
     *
     * @return true if the server is running or server hits an error, false
     *         otherwise.
     */
    protected boolean canShutdown() {
        return state == State.RUNNING || state == State.ERROR;
    }

    /**
     * @return true if the server is running, false otherwise.
     */
    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public void shutdown() {
        shutdown(false);
    }

    /**
     * Shut down the server instance
     * @param fullyShutDown true if another server using the same database will not replace this one in the same process
     */
    public synchronized void shutdown(boolean fullyShutDown) {
        if (!canShutdown()) {
            LOG.debug("ZooKeeper server is not running, so not proceeding to shutdown!");
            return;
        }
        LOG.info("shutting down");

        // new RuntimeException("Calling shutdown").printStackTrace();
        setState(State.SHUTDOWN);

        // unregister all metrics that are keeping a strong reference to this object
        // subclasses will do their specific clean up
        unregisterMetrics();

        // Since sessionTracker and syncThreads poll we just have to
        // set running to false and they will detect it during the poll
        // interval.
        if (sessionTracker != null) {
            sessionTracker.shutdown();
        }
        if (firstProcessor != null) {
            firstProcessor.shutdown();
        }
        if(jvmPauseMonitor != null) {
            jvmPauseMonitor.serviceStop();
        }

        if (zkDb != null) {
            if (fullyShutDown) {
                zkDb.clear();
            } else {
                // else there is no need to clear the database
                //  * When a new quorum is established we can still apply the diff
                //    on top of the same zkDb data
                //  * If we fetch a new snapshot from leader, the zkDb will be
                //    cleared anyway before loading the snapshot
                try {
                    //This will fast forward the database to the latest recorded transactions
                    zkDb.fastForwardDataBase();
                } catch (IOException e) {
                    LOG.error("Error updating DB", e);
                    zkDb.clear();
                }
            }
        }

        unregisterJMX();
    }

    protected void unregisterJMX() {
        // unregister from JMX
        try {
            if (jmxDataTreeBean != null) {
                MBeanRegistry.getInstance().unregister(jmxDataTreeBean);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister with JMX", e);
        }
        try {
            if (jmxServerBean != null) {
                MBeanRegistry.getInstance().unregister(jmxServerBean);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister with JMX", e);
        }
        jmxServerBean = null;
        jmxDataTreeBean = null;
    }

    public void incInProcess() {
        requestsInProcess.incrementAndGet();
    }

    public void decInProcess() {
        requestsInProcess.decrementAndGet();
    }

    public int getInProcess() {
        return requestsInProcess.get();
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

        ChangeRecord duplicate(long zxid) {
            StatPersisted stat = new StatPersisted();
            if (this.stat != null) {
                DataTree.copyStatPersisted(this.stat, stat);
            }
            return new ChangeRecord(zxid, path, stat, childCount,
                    acl == null ? new ArrayList<ACL>() : new ArrayList<ACL>(acl));
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

    long createSession(ServerCnxn cnxn, byte passwd[], int timeout) {
        if (passwd == null) {
            // Possible since it's just deserialized from a packet on the wire.
            passwd = new byte[0];
        }
        long sessionId = sessionTracker.createSession(timeout);
        Random r = new Random(sessionId ^ superSecret);
        r.nextBytes(passwd);
        ByteBuffer to = ByteBuffer.allocate(4);
        to.putInt(timeout);
        cnxn.setSessionId(sessionId);
        Request si = new Request(cnxn, sessionId, 0, OpCode.createSession, to, null);
        setLocalSessionFlag(si);
        submitRequest(si);
        return sessionId;
    }

    /**
     * set the owner of this session as owner
     * @param id the session id
     * @param owner the owner of the session
     * @throws SessionExpiredException
     */
    public void setOwner(long id, Object owner) throws SessionExpiredException {
        sessionTracker.setOwner(id, owner);
    }

    protected void revalidateSession(ServerCnxn cnxn, long sessionId,
            int sessionTimeout) throws IOException {
        boolean rc = sessionTracker.touchSession(sessionId, sessionTimeout);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                    "Session 0x" + Long.toHexString(sessionId) +
                            " is valid: " + rc);
        }
        finishSessionInit(cnxn, rc);
    }

    public void reopenSession(ServerCnxn cnxn, long sessionId, byte[] passwd,
            int sessionTimeout) throws IOException {
        if (checkPasswd(sessionId, passwd)) {
            revalidateSession(cnxn, sessionId, sessionTimeout);
        } else {
            LOG.warn("Incorrect password from " + cnxn.getRemoteSocketAddress()
                    + " for session 0x" + Long.toHexString(sessionId));
            finishSessionInit(cnxn, false);
        }
    }

    public void finishSessionInit(ServerCnxn cnxn, boolean valid) {
        // register with JMX
        try {
            if (valid) {
                if (serverCnxnFactory != null && serverCnxnFactory.cnxns.contains(cnxn)) {
                    serverCnxnFactory.registerConnection(cnxn);
                } else if (secureServerCnxnFactory != null && secureServerCnxnFactory.cnxns.contains(cnxn)) {
                    secureServerCnxnFactory.registerConnection(cnxn);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
        }

        try {
            ConnectResponse rsp = new ConnectResponse(0, valid ? cnxn.getSessionTimeout()
                    : 0, valid ? cnxn.getSessionId() : 0, // send 0 if session is no
                            // longer valid
                            valid ? generatePasswd(cnxn.getSessionId()) : new byte[16]);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
            bos.writeInt(-1, "len");
            rsp.serialize(bos, "connect");
            if (!cnxn.isOldClient) {
                bos.writeBool(
                        this instanceof ReadOnlyZooKeeperServer, "readOnly");
            }
            baos.close();
            ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
            bb.putInt(bb.remaining() - 4).rewind();
            cnxn.sendBuffer(bb);

            if (valid) {
                LOG.debug("Established session 0x"
                        + Long.toHexString(cnxn.getSessionId())
                        + " with negotiated timeout " + cnxn.getSessionTimeout()
                        + " for client "
                        + cnxn.getRemoteSocketAddress());
                cnxn.enableRecv();
            } else {

                LOG.info("Invalid session 0x"
                        + Long.toHexString(cnxn.getSessionId())
                        + " for client "
                        + cnxn.getRemoteSocketAddress()
                        + ", probably expired");
                cnxn.sendBuffer(ServerCnxnFactory.closeConn);
            }

        } catch (Exception e) {
            LOG.warn("Exception while establishing session, closing", e);
            cnxn.close();
        }
    }

    public void closeSession(ServerCnxn cnxn, RequestHeader requestHeader) {
        closeSession(cnxn.getSessionId());
    }

    public long getServerId() {
        return 0;
    }

    /**
     * If the underlying Zookeeper server support local session, this method
     * will set a isLocalSession to true if a request is associated with
     * a local session.
     *
     * @param si
     */
    protected void setLocalSessionFlag(Request si) {
    }

    public void submitRequest(Request si) {
        if (firstProcessor == null) {
            synchronized (this) {
                try {
                    // Since all requests are passed to the request
                    // processor it should wait for setting up the request
                    // processor chain. The state will be updated to RUNNING
                    // after the setup.
                    while (state == State.INITIAL) {
                        wait(1000);
                    }
                } catch (InterruptedException e) {
                    LOG.warn("Unexpected interruption", e);
                }
                if (firstProcessor == null || state != State.RUNNING) {
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
                LOG.warn("Received packet at server of unknown type " + si.type);
                new UnimplementedRequestProcessor().processRequest(si);
            }
        } catch (MissingSessionException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Dropping request: " + e.getMessage());
            }
        } catch (RequestProcessorException e) {
            LOG.error("Unable to process request:" + e.getMessage(), e);
        }
    }

    public static int getSnapCount() {
        String sc = System.getProperty("zookeeper.snapCount");
        try {
            int snapCount = Integer.parseInt(sc);

            // snapCount must be 2 or more. See org.apache.zookeeper.server.SyncRequestProcessor
            if( snapCount < 2 ) {
                LOG.warn("SnapCount should be 2 or more. Now, snapCount is reset to 2");
                snapCount = 2;
            }
            return snapCount;
        } catch (Exception e) {
            return 100000;
        }
    }

    public int getGlobalOutstandingLimit() {
        String sc = System.getProperty(GLOBAL_OUTSTANDING_LIMIT);
        int limit;
        try {
            limit = Integer.parseInt(sc);
        } catch (Exception e) {
            limit = 1000;
        }
        return limit;
    }

    public static long getSnapSizeInBytes() {
        long size = Long.getLong("zookeeper.snapSizeLimitInKb", 4194304L); // 4GB by default
        if (size <= 0) {
            LOG.info("zookeeper.snapSizeLimitInKb set to a non-positive value {}; disabling feature", size);
        }
        return size * 1024; // Convert to bytes
    }

    public void setServerCnxnFactory(ServerCnxnFactory factory) {
        serverCnxnFactory = factory;
    }

    public ServerCnxnFactory getServerCnxnFactory() {
        return serverCnxnFactory;
    }

    public ServerCnxnFactory getSecureServerCnxnFactory() {
        return secureServerCnxnFactory;
    }

    public void setSecureServerCnxnFactory(ServerCnxnFactory factory) {
        secureServerCnxnFactory = factory;
    }

    /**
     * return the last proceesed id from the
     * datatree
     */
    public long getLastProcessedZxid() {
        return zkDb.getDataTreeLastProcessedZxid();
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
     * return the total number of client connections that are alive
     * to this server
     */
    public int getNumAliveConnections() {
        int numAliveConnections = 0;

        if (serverCnxnFactory != null) {
            numAliveConnections += serverCnxnFactory.getNumAliveConnections();
        }

        if (secureServerCnxnFactory != null) {
            numAliveConnections += secureServerCnxnFactory.getNumAliveConnections();
        }

        return numAliveConnections;
    }

    /**
     * trunccate the log to get in sync with others
     * if in a quorum
     * @param zxid the zxid that it needs to get in sync
     * with others
     * @throws IOException
     */
    public void truncateLog(long zxid) throws IOException {
        this.zkDb.truncateLog(zxid);
    }

    public int getTickTime() {
        return tickTime;
    }

    public void setTickTime(int tickTime) {
        LOG.info("tickTime set to " + tickTime);
        this.tickTime = tickTime;
    }

    public int getMinSessionTimeout() {
        return minSessionTimeout;
    }

    public void setMinSessionTimeout(int min) {
        this.minSessionTimeout = min == -1 ? tickTime * 2 : min;
        LOG.info("minSessionTimeout set to {}", this.minSessionTimeout);
    }

    public int getMaxSessionTimeout() {
        return maxSessionTimeout;
    }

    public void setMaxSessionTimeout(int max) {
        this.maxSessionTimeout = max == -1 ? tickTime * 20 : max;
        LOG.info("maxSessionTimeout set to {}", this.maxSessionTimeout);
    }

    public int getClientPortListenBacklog() {
        return listenBacklog;
    }

    public void setClientPortListenBacklog(int backlog) {
        this.listenBacklog = backlog;
        LOG.info("clientPortListenBacklog set to " + backlog);
    }

    public int getClientPort() {
        return serverCnxnFactory != null ? serverCnxnFactory.getLocalPort() : -1;
    }

    public int getSecureClientPort() {
        return secureServerCnxnFactory != null ? secureServerCnxnFactory.getLocalPort() : -1;
    }

    /** Maximum number of connections allowed from particular host (ip) */
    public int getMaxClientCnxnsPerHost() {
        if (serverCnxnFactory != null) {
            return serverCnxnFactory.getMaxClientCnxnsPerHost();
        }
        if (secureServerCnxnFactory != null) {
            return secureServerCnxnFactory.getMaxClientCnxnsPerHost();
        }
        return -1;
    }

    public void setTxnLogFactory(FileTxnSnapLog txnLog) {
        this.txnLogFactory = txnLog;
    }

    public FileTxnSnapLog getTxnLogFactory() {
        return this.txnLogFactory;
    }

    /**
     * Returns the elapsed sync of time of transaction log in milliseconds.
     */
    public long getTxnLogElapsedSyncTime() {
        return txnLogFactory.getTxnLogElapsedSyncTime();
    }

    public String getState() {
        return "standalone";
    }

    public void dumpEphemerals(PrintWriter pwriter) {
        zkDb.dumpEphemerals(pwriter);
    }

    public Map<Long, Set<String>> getEphemerals() {
        return zkDb.getEphemerals();
    }

    public double getConnectionDropChance() {
        return connThrottle.getDropChance();
    }

    public void processConnectRequest(ServerCnxn cnxn, ByteBuffer incomingBuffer)
        throws IOException, ClientCnxnLimitException {

        if (connThrottle.checkLimit(1) == false) {
            throw new ClientCnxnLimitException();
        }
        ServerMetrics.getMetrics().CONNECTION_TOKEN_DEFICIT.add(connThrottle.getDeficit());

        BinaryInputArchive bia = BinaryInputArchive.getArchive(new ByteBufferInputStream(incomingBuffer));
        ConnectRequest connReq = new ConnectRequest();
        connReq.deserialize(bia, "connect");
        if (LOG.isDebugEnabled()) {
            LOG.debug("Session establishment request from client "
                    + cnxn.getRemoteSocketAddress()
                    + " client's lastZxid is 0x"
                    + Long.toHexString(connReq.getLastZxidSeen()));
        }
        ServerMetrics.getMetrics().CONNECTION_REQUEST_COUNT.add(1);
        boolean readOnly = false;
        try {
            readOnly = bia.readBool("readOnly");
            cnxn.isOldClient = false;
        } catch (IOException e) {
            // this is ok -- just a packet from an old client which
            // doesn't contain readOnly field
            LOG.warn("Connection request from old client "
                    + cnxn.getRemoteSocketAddress()
                    + "; will be dropped if server is in r-o mode");
        }
        if (!readOnly && this instanceof ReadOnlyZooKeeperServer) {
            String msg = "Refusing session request for not-read-only client "
                + cnxn.getRemoteSocketAddress();
            LOG.info(msg);
            throw new CloseRequestException(msg);
        }
        if (connReq.getLastZxidSeen() > zkDb.dataTree.lastProcessedZxid) {
            String msg = "Refusing session request for client "
                + cnxn.getRemoteSocketAddress()
                + " as it has seen zxid 0x"
                + Long.toHexString(connReq.getLastZxidSeen())
                + " our last zxid is 0x"
                + Long.toHexString(getZKDatabase().getDataTreeLastProcessedZxid())
                + " client must try another server";

            LOG.info(msg);
            throw new CloseRequestException(msg);
        }
        int sessionTimeout = connReq.getTimeOut();
        byte passwd[] = connReq.getPasswd();
        int minSessionTimeout = getMinSessionTimeout();
        if (sessionTimeout < minSessionTimeout) {
            sessionTimeout = minSessionTimeout;
        }
        int maxSessionTimeout = getMaxSessionTimeout();
        if (sessionTimeout > maxSessionTimeout) {
            sessionTimeout = maxSessionTimeout;
        }
        cnxn.setSessionTimeout(sessionTimeout);
        // We don't want to receive any packets until we are sure that the
        // session is setup
        cnxn.disableRecv();
        long sessionId = connReq.getSessionId();
        if (sessionId == 0) {
            long id = createSession(cnxn, passwd, sessionTimeout);
            LOG.debug("Client attempting to establish new session:" +
                            " session = 0x{}, zxid = 0x{}, timeout = {}, address = {}",
                    Long.toHexString(id),
                    Long.toHexString(connReq.getLastZxidSeen()),
                    connReq.getTimeOut(),
                    cnxn.getRemoteSocketAddress());
        } else {
            long clientSessionId = connReq.getSessionId();
            LOG.debug("Client attempting to renew session:" +
                            " session = 0x{}, zxid = 0x{}, timeout = {}, address = {}",
                    Long.toHexString(clientSessionId),
                    Long.toHexString(connReq.getLastZxidSeen()),
                    connReq.getTimeOut(),
                    cnxn.getRemoteSocketAddress());
            if (serverCnxnFactory != null) {
                serverCnxnFactory.closeSession(sessionId);
            }
            if (secureServerCnxnFactory != null) {
                secureServerCnxnFactory.closeSession(sessionId);
            }
            cnxn.setSessionId(sessionId);
            reopenSession(cnxn, sessionId, passwd, sessionTimeout);
            ServerMetrics.getMetrics().CONNECTION_REVALIDATE_COUNT.add(1);

        }
    }

    public boolean shouldThrottle(long outStandingCount) {
        if (getGlobalOutstandingLimit() < getInProcess()) {
            return outStandingCount > 0;
        }
        return false;
    }

    long getFlushDelay() {
        return flushDelay;
    }

    static void setFlushDelay(long delay) {
        LOG.info("{}={}", FLUSH_DELAY, delay);
        flushDelay = delay;
    }

    long getMaxWriteQueuePollTime() {
        return maxWriteQueuePollTime;
    }

    static void setMaxWriteQueuePollTime(long maxTime) {
        LOG.info("{}={}", MAX_WRITE_QUEUE_POLL_SIZE, maxTime);
        maxWriteQueuePollTime = maxTime;
    }

    int getMaxBatchSize() {
        return maxBatchSize;
    }

    static void setMaxBatchSize(int size) {
        LOG.info("{}={}", MAX_BATCH_SIZE, size);
        maxBatchSize = size;
    }

    public void processPacket(ServerCnxn cnxn, ByteBuffer incomingBuffer) throws IOException {
        // We have the request, now process and setup for next
        InputStream bais = new ByteBufferInputStream(incomingBuffer);
        BinaryInputArchive bia = BinaryInputArchive.getArchive(bais);
        RequestHeader h = new RequestHeader();
        h.deserialize(bia, "header");

        // Need to increase the outstanding request count first, otherwise
        // there might be a race condition that it enabled recv after
        // processing request and then disabled when check throttling.
        //
        // Be aware that we're actually checking the global outstanding
        // request before this request.
        //
        // It's fine if the IOException thrown before we decrease the count
        // in cnxn, since it will close the cnxn anyway.
        cnxn.incrOutstandingAndCheckThrottle(h);

        // Through the magic of byte buffers, txn will not be
        // pointing
        // to the start of the txn
        incomingBuffer = incomingBuffer.slice();
        if (h.getType() == OpCode.auth) {
            LOG.info("got auth packet " + cnxn.getRemoteSocketAddress());
            AuthPacket authPacket = new AuthPacket();
            ByteBufferInputStream.byteBuffer2Record(incomingBuffer, authPacket);
            String scheme = authPacket.getScheme();
            ServerAuthenticationProvider ap = ProviderRegistry.getServerProvider(scheme);
            Code authReturn = KeeperException.Code.AUTHFAILED;
            if(ap != null) {
                try {
                    // handleAuthentication may close the connection, to allow the client to choose
                    // a different server to connect to.
                    authReturn = ap.handleAuthentication(new ServerAuthenticationProvider.ServerObjs(this, cnxn), authPacket.getAuth());
                } catch(RuntimeException e) {
                    LOG.warn("Caught runtime exception from AuthenticationProvider: " + scheme + " due to " + e);
                    authReturn = KeeperException.Code.AUTHFAILED;
                }
            }
            if (authReturn == KeeperException.Code.OK) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Authentication succeeded for scheme: " + scheme);
                }
                LOG.info("auth success " + cnxn.getRemoteSocketAddress());
                ReplyHeader rh = new ReplyHeader(h.getXid(), 0,
                        KeeperException.Code.OK.intValue());
                cnxn.sendResponse(rh, null, null);
            } else {
                if (ap == null) {
                    LOG.warn("No authentication provider for scheme: "
                            + scheme + " has "
                            + ProviderRegistry.listProviders());
                } else {
                    LOG.warn("Authentication failed for scheme: " + scheme);
                }
                // send a response...
                ReplyHeader rh = new ReplyHeader(h.getXid(), 0,
                        KeeperException.Code.AUTHFAILED.intValue());
                cnxn.sendResponse(rh, null, null);
                // ... and close connection
                cnxn.sendBuffer(ServerCnxnFactory.closeConn);
                cnxn.disableRecv();
            }
            return;
        } else if (h.getType() == OpCode.sasl) {
            Record rsp = processSasl(incomingBuffer,cnxn);
            ReplyHeader rh = new ReplyHeader(h.getXid(), 0, KeeperException.Code.OK.intValue());
            cnxn.sendResponse(rh,rsp, "response"); // not sure about 3rd arg..what is it?
            return;
        } else {
            Request si = new Request(cnxn, cnxn.getSessionId(), h.getXid(),
              h.getType(), incomingBuffer, cnxn.getAuthInfo());
            si.setOwner(ServerCnxn.me);
            // Always treat packet from the client as a possible
            // local request.
            setLocalSessionFlag(si);
            submitRequest(si);
            return;
        }
    }

    private Record processSasl(ByteBuffer incomingBuffer, ServerCnxn cnxn) throws IOException {
        LOG.debug("Responding to client SASL token.");
        GetSASLRequest clientTokenRecord = new GetSASLRequest();
        ByteBufferInputStream.byteBuffer2Record(incomingBuffer,clientTokenRecord);
        byte[] clientToken = clientTokenRecord.getToken();
        LOG.debug("Size of client SASL token: " + clientToken.length);
        byte[] responseToken = null;
        try {
            ZooKeeperSaslServer saslServer  = cnxn.zooKeeperSaslServer;
            try {
                // note that clientToken might be empty (clientToken.length == 0):
                // if using the DIGEST-MD5 mechanism, clientToken will be empty at the beginning of the
                // SASL negotiation process.
                responseToken = saslServer.evaluateResponse(clientToken);
                if (saslServer.isComplete()) {
                    String authorizationID = saslServer.getAuthorizationID();
                    LOG.info("adding SASL authorization for authorizationID: " + authorizationID);
                    cnxn.addAuthInfo(new Id("sasl",authorizationID));
                    if (System.getProperty("zookeeper.superUser") != null &&
                        authorizationID.equals(System.getProperty("zookeeper.superUser"))) {
                        cnxn.addAuthInfo(new Id("super", ""));
                    }
                }
            }
            catch (SaslException e) {
                LOG.warn("Client failed to SASL authenticate: " + e, e);
                if ((System.getProperty("zookeeper.allowSaslFailedClients") != null)
                  &&
                  (System.getProperty("zookeeper.allowSaslFailedClients").equals("true"))) {
                    LOG.warn("Maintaining client connection despite SASL authentication failure.");
                } else {
                    LOG.warn("Closing client connection due to SASL authentication failure.");
                    cnxn.close();
                }
            }
        }
        catch (NullPointerException e) {
            LOG.error("cnxn.saslServer is null: cnxn object did not initialize its saslServer properly.");
        }
        if (responseToken != null) {
            LOG.debug("Size of server SASL response: " + responseToken.length);
        }
        // wrap SASL response token to client inside a Response object.
        return new SetSASLResponse(responseToken);
    }

    // entry point for quorum/Learner.java
    public ProcessTxnResult processTxn(TxnHeader hdr, Record txn) {
        return processTxn(null, hdr, txn);
    }

    // entry point for FinalRequestProcessor.java
    public ProcessTxnResult processTxn(Request request) {
        return processTxn(request, request.getHdr(), request.getTxn());
    }

    private ProcessTxnResult processTxn(Request request, TxnHeader hdr,
                                        Record txn) {
        ProcessTxnResult rc;
        int opCode = request != null ? request.type : hdr.getType();
        long sessionId = request != null ? request.sessionId : hdr.getClientId();

        if (opCode == OpCode.createSession) {
            if (hdr != null && txn instanceof CreateSessionTxn) {
                CreateSessionTxn cst = (CreateSessionTxn) txn;
                sessionTracker.commitSession(sessionId, cst.getTimeOut());
            } else if (request == null || !request.isLocalSession()) {
                LOG.warn("*****>>>>> Got "
                        + txn.getClass() + " "
                        + txn.toString());
            }
        } else if (opCode == OpCode.closeSession) {
            sessionTracker.removeSession(sessionId);
        }

        if (hdr != null) {
            rc = getZKDatabase().processTxn(hdr, txn);
        } else {
            rc = new ProcessTxnResult();
        }
        return rc;
    }

    public Map<Long, Set<Long>> getSessionExpiryMap() {
        return sessionTracker.getSessionExpiryMap();
    }

    /**
     * This method is used to register the ZooKeeperServerShutdownHandler to get
     * server's error or shutdown state change notifications.
     * {@link ZooKeeperServerShutdownHandler#handle(State)} will be called for
     * every server state changes {@link #setState(State)}.
     *
     * @param zkShutdownHandler shutdown handler
     */
    void registerServerShutdownHandler(ZooKeeperServerShutdownHandler zkShutdownHandler) {
        this.zkShutdownHandler = zkShutdownHandler;
    }

    public boolean isResponseCachingEnabled() {
        return isResponseCachingEnabled;
    }

    public void setResponseCachingEnabled(boolean isEnabled) {
        isResponseCachingEnabled = isEnabled;
    }

    public ResponseCache getReadResponseCache() {
        return isResponseCachingEnabled ? readResponseCache : null;
    }

    protected void registerMetrics() {
        MetricsContext rootContext = ServerMetrics
                .getMetrics()
                .getMetricsProvider()
                .getRootContext();

        final ZKDatabase zkdb = this.getZKDatabase();
        final ServerStats stats = this.serverStats();

        rootContext.registerGauge("avg_latency", stats::getAvgLatency);

        rootContext.registerGauge("max_latency", stats::getMaxLatency);
        rootContext.registerGauge("min_latency", stats::getMinLatency);

        rootContext.registerGauge("packets_received", stats::getPacketsReceived);
        rootContext.registerGauge("packets_sent", stats::getPacketsSent);
        rootContext.registerGauge("num_alive_connections", stats::getNumAliveClientConnections);

        rootContext.registerGauge("outstanding_requests", stats::getOutstandingRequests);
        rootContext.registerGauge("uptime", stats::getUptime);

        rootContext.registerGauge("znode_count", zkdb::getNodeCount);

        rootContext.registerGauge("watch_count", zkdb.getDataTree()::getWatchCount);
        rootContext.registerGauge("ephemerals_count", zkdb.getDataTree()::getEphemeralsCount);

        rootContext.registerGauge("approximate_data_size", zkdb.getDataTree()::cachedApproximateDataSize);

        rootContext.registerGauge("global_sessions", zkdb::getSessionCount);
        rootContext.registerGauge("local_sessions",
                this.getSessionTracker()::getLocalSessionCount);

        OSMXBean osMbean = new OSMXBean();
        rootContext.registerGauge("open_file_descriptor_count", osMbean::getOpenFileDescriptorCount);
        rootContext.registerGauge("max_file_descriptor_count", osMbean::getMaxFileDescriptorCount);
        rootContext.registerGauge("connection_drop_probability", this::getConnectionDropChance);

        rootContext.registerGauge("last_client_response_size", stats.getClientResponseStats()::getLastBufferSize);
        rootContext.registerGauge("max_client_response_size", stats.getClientResponseStats()::getMaxBufferSize);
        rootContext.registerGauge("min_client_response_size", stats.getClientResponseStats()::getMinBufferSize);

    }

    protected void unregisterMetrics() {

        MetricsContext rootContext = ServerMetrics
                .getMetrics()
                .getMetricsProvider()
                .getRootContext();

        rootContext.unregisterGauge("avg_latency");

        rootContext.unregisterGauge("max_latency");
        rootContext.unregisterGauge("min_latency");

        rootContext.unregisterGauge("packets_received");
        rootContext.unregisterGauge("packets_sent");
        rootContext.unregisterGauge("num_alive_connections");

        rootContext.unregisterGauge("outstanding_requests");
        rootContext.unregisterGauge("uptime");

        rootContext.unregisterGauge("znode_count");

        rootContext.unregisterGauge("watch_count");
        rootContext.unregisterGauge("ephemerals_count");
        rootContext.unregisterGauge("approximate_data_size");

        rootContext.unregisterGauge("global_sessions");
        rootContext.unregisterGauge("local_sessions");

        rootContext.unregisterGauge("open_file_descriptor_count");
        rootContext.unregisterGauge("max_file_descriptor_count");
        rootContext.unregisterGauge("connection_drop_probability");

        rootContext.unregisterGauge("last_client_response_size");
        rootContext.unregisterGauge("max_client_response_size");
        rootContext.unregisterGauge("min_client_response_size");

    }

    /**
     * Hook into admin server, useful to expose additional data
     * that do not represent metrics.
     *
     * @param response a sink which collects the data.
     */
    public void dumpMonitorValues(BiConsumer<String, Object> response) {
      ServerStats stats = serverStats();
      response.accept("version", Version.getFullVersion());
      response.accept("server_state", stats.getServerState());
    }
}
