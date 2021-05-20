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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import java.util.Properties;
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
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.Version;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.ZookeeperBanner;
import org.apache.zookeeper.common.StringUtils;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.proto.AuthPacket;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.DeleteRequest;
import org.apache.zookeeper.proto.GetSASLRequest;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.proto.SetACLRequest;
import org.apache.zookeeper.proto.SetDataRequest;
import org.apache.zookeeper.proto.SetSASLResponse;
import org.apache.zookeeper.server.DataTree.ProcessTxnResult;
import org.apache.zookeeper.server.RequestProcessor.RequestProcessorException;
import org.apache.zookeeper.server.ServerCnxn.CloseRequestException;
import org.apache.zookeeper.server.SessionTracker.Session;
import org.apache.zookeeper.server.SessionTracker.SessionExpirer;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.auth.ServerAuthenticationProvider;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.ReadOnlyZooKeeperServer;
import org.apache.zookeeper.server.util.JvmPauseMonitor;
import org.apache.zookeeper.server.util.OSMXBean;
import org.apache.zookeeper.server.util.RequestPathMetricsCollector;
import org.apache.zookeeper.txn.CreateSessionTxn;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.apache.zookeeper.util.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements a simple standalone ZooKeeperServer. It sets up the
 * following chain of RequestProcessors to process requests:
 * PrepRequestProcessor -&gt; SyncRequestProcessor -&gt; FinalRequestProcessor
 */
public class ZooKeeperServer implements SessionExpirer, ServerStats.Provider {

    protected static final Logger LOG;
    private static final RateLogger RATE_LOGGER;

    public static final String GLOBAL_OUTSTANDING_LIMIT = "zookeeper.globalOutstandingLimit";

    public static final String ENABLE_EAGER_ACL_CHECK = "zookeeper.enableEagerACLCheck";
    public static final String SKIP_ACL = "zookeeper.skipACL";
    public static final String ENFORCE_QUOTA = "zookeeper.enforceQuota";

    // When enabled, will check ACL constraints appertained to the requests first,
    // before sending the requests to the quorum.
    static final boolean enableEagerACLCheck;

    static final boolean skipACL;

    public static final boolean enforceQuota;

    public static final String SASL_SUPER_USER = "zookeeper.superUser";

    public static final String ALLOW_SASL_FAILED_CLIENTS = "zookeeper.allowSaslFailedClients";
    public static final String ZOOKEEPER_DIGEST_ENABLED = "zookeeper.digest.enabled";
    private static boolean digestEnabled;

    // Add a enable/disable option for now, we should remove this one when
    // this feature is confirmed to be stable
    public static final String CLOSE_SESSION_TXN_ENABLED = "zookeeper.closeSessionTxn.enabled";
    private static boolean closeSessionTxnEnabled = true;

    static {
        LOG = LoggerFactory.getLogger(ZooKeeperServer.class);

        RATE_LOGGER = new RateLogger(LOG);

        ZookeeperBanner.printBanner(LOG);

        Environment.logEnv("Server environment:", LOG);

        enableEagerACLCheck = Boolean.getBoolean(ENABLE_EAGER_ACL_CHECK);
        LOG.info("{} = {}", ENABLE_EAGER_ACL_CHECK, enableEagerACLCheck);

        skipACL = System.getProperty(SKIP_ACL, "no").equals("yes");
        if (skipACL) {
            LOG.info("{}==\"yes\", ACL checks will be skipped", SKIP_ACL);
        }

        enforceQuota = Boolean.parseBoolean(System.getProperty(ENFORCE_QUOTA, "false"));
        if (enforceQuota) {
            LOG.info("{} = {}, Quota Enforce enables", ENFORCE_QUOTA, enforceQuota);
        }

        digestEnabled = Boolean.parseBoolean(System.getProperty(ZOOKEEPER_DIGEST_ENABLED, "true"));
        LOG.info("{} = {}", ZOOKEEPER_DIGEST_ENABLED, digestEnabled);

        closeSessionTxnEnabled = Boolean.parseBoolean(
                System.getProperty(CLOSE_SESSION_TXN_ENABLED, "true"));
        LOG.info("{} = {}", CLOSE_SESSION_TXN_ENABLED, closeSessionTxnEnabled);
    }

    public static boolean isCloseSessionTxnEnabled() {
        return closeSessionTxnEnabled;
    }

    public static void setCloseSessionTxnEnabled(boolean enabled) {
        ZooKeeperServer.closeSessionTxnEnabled = enabled;
        LOG.info("Update {} to {}", CLOSE_SESSION_TXN_ENABLED,
                ZooKeeperServer.closeSessionTxnEnabled);
    }

    protected ZooKeeperServerBean jmxServerBean;
    protected DataTreeBean jmxDataTreeBean;

    public static final int DEFAULT_TICK_TIME = 3000;
    protected int tickTime = DEFAULT_TICK_TIME;
    public static final int DEFAULT_THROTTLED_OP_WAIT_TIME = 0; // disabled
    protected static volatile int throttledOpWaitTime =
        Integer.getInteger("zookeeper.throttled_op_wait_time", DEFAULT_THROTTLED_OP_WAIT_TIME);
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
    private ResponseCache getChildrenResponseCache;
    private final AtomicLong hzxid = new AtomicLong(0);
    public static final Exception ok = new Exception("No prob");
    protected RequestProcessor firstProcessor;
    protected JvmPauseMonitor jvmPauseMonitor;
    protected volatile State state = State.INITIAL;
    private boolean isResponseCachingEnabled = true;
    /* contains the configuration file content read at startup */
    protected String initialConfig;
    protected boolean reconfigEnabled;
    private final RequestPathMetricsCollector requestPathMetricsCollector;

    private boolean localSessionEnabled = false;
    protected enum State {
        INITIAL,
        RUNNING,
        SHUTDOWN,
        ERROR
    }

    /**
     * This is the secret that we use to generate passwords. For the moment,
     * it's more of a checksum that's used in reconnection, which carries no
     * security weight, and is treated internally as if it carries no
     * security weight.
     */
    private static final long superSecret = 0XB3415C00L;

    private final AtomicInteger requestsInProcess = new AtomicInteger(0);
    final Deque<ChangeRecord> outstandingChanges = new ArrayDeque<>();
    // this data structure must be accessed under the outstandingChanges lock
    final Map<String, ChangeRecord> outstandingChangesForPath = new HashMap<String, ChangeRecord>();

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
    public static final String INT_BUFFER_STARTING_SIZE_BYTES = "zookeeper.intBufferStartingSizeBytes";
    public static final int DEFAULT_STARTING_BUFFER_SIZE = 1024;
    public static final int intBufferStartingSizeBytes;

    public static final String GET_DATA_RESPONSE_CACHE_SIZE = "zookeeper.maxResponseCacheSize";
    public static final String GET_CHILDREN_RESPONSE_CACHE_SIZE = "zookeeper.maxGetChildrenResponseCacheSize";

    static {
        long configuredFlushDelay = Long.getLong(FLUSH_DELAY, 0);
        setFlushDelay(configuredFlushDelay);
        setMaxWriteQueuePollTime(Long.getLong(MAX_WRITE_QUEUE_POLL_SIZE, configuredFlushDelay / 3));
        setMaxBatchSize(Integer.getInteger(MAX_BATCH_SIZE, 1000));

        intBufferStartingSizeBytes = Integer.getInteger(INT_BUFFER_STARTING_SIZE_BYTES, DEFAULT_STARTING_BUFFER_SIZE);

        if (intBufferStartingSizeBytes < 32) {
            String msg = "Buffer starting size must be greater than 0."
                         + "Configure with \"-Dzookeeper.intBufferStartingSizeBytes=<size>\" ";
            LOG.error(msg);
            throw new IllegalArgumentException(msg);
        }

        LOG.info("{} = {}", INT_BUFFER_STARTING_SIZE_BYTES, intBufferStartingSizeBytes);
    }

    // Connection throttling
    private BlueThrottle connThrottle = new BlueThrottle();

    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification =
        "Internally the throttler has a BlockingQueue so "
        + "once the throttler is created and started, it is thread-safe")
    private RequestThrottler requestThrottler;
    public static final String SNAP_COUNT = "zookeeper.snapCount";

    /**
     * This setting sets a limit on the total number of large requests that
     * can be inflight and is designed to prevent ZooKeeper from accepting
     * too many large requests such that the JVM runs out of usable heap and
     * ultimately crashes.
     *
     * The limit is enforced by the {@link checkRequestSize(int, boolean)}
     * method which is called by the connection layer ({@link NIOServerCnxn},
     * {@link NettyServerCnxn}) before allocating a byte buffer and pulling
     * data off the TCP socket. The limit is then checked again by the
     * ZooKeeper server in {@link processPacket(ServerCnxn, ByteBuffer)} which
     * also atomically updates {@link currentLargeRequestBytes}. The request is
     * then marked as a large request, with the request size stored in the Request
     * object so that it can later be decremented from {@link currentLargeRequestsBytes}.
     *
     * When a request is completed or dropped, the relevant code path calls the
     * {@link requestFinished(Request)} method which performs the decrement if
     * needed.
     */
    private volatile int largeRequestMaxBytes = 100 * 1024 * 1024;

    /**
     * The size threshold after which a request is considered a large request
     * and is checked against the large request byte limit.
     */
    private volatile int largeRequestThreshold = -1;

    private final AtomicInteger currentLargeRequestBytes = new AtomicInteger(0);

    private AuthenticationHelper authHelper;

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
        this.requestPathMetricsCollector = new RequestPathMetricsCollector();
        this.authHelper = new AuthenticationHelper();
    }

    /**
     * Keeping this constructor for backward compatibility
     */
    public ZooKeeperServer(FileTxnSnapLog txnLogFactory, int tickTime, int minSessionTimeout, int maxSessionTimeout, int clientPortListenBacklog, ZKDatabase zkDb, String initialConfig) {
        this(txnLogFactory, tickTime, minSessionTimeout, maxSessionTimeout, clientPortListenBacklog, zkDb, initialConfig, QuorumPeerConfig.isReconfigEnabled());
    }

    /**
     *  * Creates a ZooKeeperServer instance. It sets everything up, but doesn't
     * actually start listening for clients until run() is invoked.
     *
     */
    public ZooKeeperServer(FileTxnSnapLog txnLogFactory, int tickTime, int minSessionTimeout, int maxSessionTimeout, int clientPortListenBacklog, ZKDatabase zkDb, String initialConfig, boolean reconfigEnabled) {
        serverStats = new ServerStats(this);
        this.txnLogFactory = txnLogFactory;
        this.txnLogFactory.setServerStats(this.serverStats);
        this.zkDb = zkDb;
        this.tickTime = tickTime;
        setMinSessionTimeout(minSessionTimeout);
        setMaxSessionTimeout(maxSessionTimeout);
        this.listenBacklog = clientPortListenBacklog;
        this.reconfigEnabled = reconfigEnabled;

        listener = new ZooKeeperServerListenerImpl(this);

        readResponseCache = new ResponseCache(Integer.getInteger(
            GET_DATA_RESPONSE_CACHE_SIZE,
            ResponseCache.DEFAULT_RESPONSE_CACHE_SIZE), "getData");

        getChildrenResponseCache = new ResponseCache(Integer.getInteger(
            GET_CHILDREN_RESPONSE_CACHE_SIZE,
            ResponseCache.DEFAULT_RESPONSE_CACHE_SIZE), "getChildren");

        this.initialConfig = initialConfig;

        this.requestPathMetricsCollector = new RequestPathMetricsCollector();

        this.initLargeRequestThrottlingSettings();

        this.authHelper = new AuthenticationHelper();

        LOG.info(
            "Created server with"
                + " tickTime {}"
                + " minSessionTimeout {}"
                + " maxSessionTimeout {}"
                + " clientPortListenBacklog {}"
                + " datadir {}"
                + " snapdir {}",
            tickTime,
            getMinSessionTimeout(),
            getMaxSessionTimeout(),
            getClientPortListenBacklog(),
            txnLogFactory.getDataDir(),
            txnLogFactory.getSnapDir());
    }

    public String getInitialConfig() {
        return initialConfig;
    }

    /**
     * Adds JvmPauseMonitor and calls
     * {@link #ZooKeeperServer(FileTxnSnapLog, int, int, int, int, ZKDatabase, String)}
     *
     */
    public ZooKeeperServer(JvmPauseMonitor jvmPauseMonitor, FileTxnSnapLog txnLogFactory, int tickTime, int minSessionTimeout, int maxSessionTimeout, int clientPortListenBacklog, ZKDatabase zkDb, String initialConfig) {
        this(txnLogFactory, tickTime, minSessionTimeout, maxSessionTimeout, clientPortListenBacklog, zkDb, initialConfig, QuorumPeerConfig.isReconfigEnabled());
        this.jvmPauseMonitor = jvmPauseMonitor;
        if (jvmPauseMonitor != null) {
            LOG.info("Added JvmPauseMonitor to server");
        }
    }

    /**
     * creates a zookeeperserver instance.
     * @param txnLogFactory the file transaction snapshot logging class
     * @param tickTime the ticktime for the server
     * @throws IOException
     */
    public ZooKeeperServer(FileTxnSnapLog txnLogFactory, int tickTime, String initialConfig) {
        this(txnLogFactory, tickTime, -1, -1, -1, new ZKDatabase(txnLogFactory), initialConfig, QuorumPeerConfig.isReconfigEnabled());
    }

    public ServerStats serverStats() {
        return serverStats;
    }

    public RequestPathMetricsCollector getRequestPathMetricsCollector() {
        return requestPathMetricsCollector;
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
        return new ZooKeeperServerConf(
            getClientPort(),
            zkDb.snapLog.getSnapDir().getAbsolutePath(),
            zkDb.snapLog.getDataDir().getAbsolutePath(),
            getTickTime(),
            getMaxClientCnxnsPerHost(),
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
    public ZooKeeperServer(File snapDir, File logDir, int tickTime) throws IOException {
        this(new FileTxnSnapLog(snapDir, logDir), tickTime, "");
    }

    /**
     * Default constructor, relies on the config for its argument values
     *
     * @throws IOException
     */
    public ZooKeeperServer(FileTxnSnapLog txnLogFactory) throws IOException {
        this(txnLogFactory, DEFAULT_TICK_TIME, -1, -1, -1, new ZKDatabase(txnLogFactory), "", QuorumPeerConfig.isReconfigEnabled());
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
        if (zkDb.isInitialized()) {
            setZxid(zkDb.getDataTreeLastProcessedZxid());
        } else {
            setZxid(zkDb.loadDataBase());
        }

        // Clean up dead sessions
        zkDb.getSessions().stream()
                        .filter(session -> zkDb.getSessionWithTimeOuts().get(session) == null)
                        .forEach(session -> killSession(session, zkDb.getDataTreeLastProcessedZxid()));

        // Make a clean snapshot
        takeSnapshot();
    }

    public void takeSnapshot() {
        takeSnapshot(false);
    }

    public void takeSnapshot(boolean syncSnap) {
        long start = Time.currentElapsedTime();
        try {
            txnLogFactory.save(zkDb.getDataTree(), zkDb.getSessionWithTimeOuts(), syncSnap);
        } catch (IOException e) {
            LOG.error("Severe unrecoverable error, exiting", e);
            // This is a severe error that we cannot recover from,
            // so we need to exit
            ServiceUtils.requestSystemExit(ExitCode.TXNLOG_ERROR_TAKING_SNAPSHOT.getValue());
        }
        long elapsed = Time.currentElapsedTime() - start;
        LOG.info("Snapshot taken in {} ms", elapsed);
        ServerMetrics.getMetrics().SNAPSHOT_TIME.add(elapsed);
    }

    public boolean shouldForceWriteInitialSnapshotAfterLeaderElection() {
        return txnLogFactory.shouldForceWriteInitialSnapshotAfterLeaderElection();
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
        submitRequest(si);
    }

    public void closeSession(long sessionId) {
        LOG.info("Closing session 0x{}", Long.toHexString(sessionId));

        // we do not want to wait for a session close. send it as soon as we
        // detect it!
        close(sessionId);
    }

    protected void killSession(long sessionId, long zxid) {
        zkDb.killSession(sessionId, zxid);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(
                LOG,
                ZooTrace.SESSION_TRACE_MASK,
                "ZooKeeperServer --- killSession: 0x" + Long.toHexString(sessionId));
        }
        if (sessionTracker != null) {
            sessionTracker.removeSession(sessionId);
        }
    }

    public void expire(Session session) {
        long sessionId = session.getSessionId();
        LOG.info(
            "Expiring session 0x{}, timeout of {}ms exceeded",
            Long.toHexString(sessionId),
            session.getTimeout());
        close(sessionId);
    }

    public void expire(long sessionId) {
        LOG.info("forcibly expiring session 0x{}", Long.toHexString(sessionId));

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
            throw new MissingSessionException("No session with sessionid 0x"
                                              + Long.toHexString(id)
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

    public void startdata() throws IOException, InterruptedException {
        //check to see if zkDb is not null
        if (zkDb == null) {
            zkDb = new ZKDatabase(this.txnLogFactory);
        }
        if (!zkDb.isInitialized()) {
            loadData();
        }
    }

    public synchronized void startup() {
        startupWithServerState(State.RUNNING);
    }

    public synchronized void startupWithoutServing() {
        startupWithServerState(State.INITIAL);
    }

    public synchronized void startServing() {
        setState(State.RUNNING);
        notifyAll();
    }

    private void startupWithServerState(State state) {
        if (sessionTracker == null) {
            createSessionTracker();
        }
        startSessionTracker();
        setupRequestProcessors();

        startRequestThrottler();

        registerJMX();

        startJvmPauseMonitor();

        registerMetrics();

        setState(state);

        requestPathMetricsCollector.start();

        localSessionEnabled = sessionTracker.isLocalSessionsEnabled();

        notifyAll();
    }

    protected void startJvmPauseMonitor() {
        if (this.jvmPauseMonitor != null) {
            this.jvmPauseMonitor.serviceStart();
        }
    }

    protected void startRequestThrottler() {
        requestThrottler = new RequestThrottler(this);
        requestThrottler.start();

    }

    protected void setupRequestProcessors() {
        RequestProcessor finalProcessor = new FinalRequestProcessor(this);
        RequestProcessor syncProcessor = new SyncRequestProcessor(this, finalProcessor);
        ((SyncRequestProcessor) syncProcessor).start();
        firstProcessor = new PrepRequestProcessor(this, syncProcessor);
        ((PrepRequestProcessor) firstProcessor).start();
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
        sessionTracker = new SessionTrackerImpl(this, zkDb.getSessionWithTimeOuts(), tickTime, createSessionTrackerServerId, getZooKeeperServerListener());
    }

    protected void startSessionTracker() {
        ((SessionTrackerImpl) sessionTracker).start();
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
            LOG.debug(
                "ZKShutdownHandler is not registered, so ZooKeeper server"
                    + " won't take any action on ERROR or SHUTDOWN server state changes");
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
            if (fullyShutDown && zkDb != null) {
                zkDb.clear();
            }
            LOG.debug("ZooKeeper server is not running, so not proceeding to shutdown!");
            return;
        }
        LOG.info("shutting down");

        // new RuntimeException("Calling shutdown").printStackTrace();
        setState(State.SHUTDOWN);

        // unregister all metrics that are keeping a strong reference to this object
        // subclasses will do their specific clean up
        unregisterMetrics();

        if (requestThrottler != null) {
            requestThrottler.shutdown();
        }

        // Since sessionTracker and syncThreads poll we just have to
        // set running to false and they will detect it during the poll
        // interval.
        if (sessionTracker != null) {
            sessionTracker.shutdown();
        }
        if (firstProcessor != null) {
            firstProcessor.shutdown();
        }
        if (jvmPauseMonitor != null) {
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

        requestPathMetricsCollector.shutdown();
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
        if (requestThrottler != null) {
            requestThrottler.throttleWake();
        }
    }

    public int getInProcess() {
        return requestsInProcess.get();
    }

    public int getInflight() {
        return requestThrottleInflight();
    }

    private int requestThrottleInflight() {
        if (requestThrottler != null) {
            return requestThrottler.getInflight();
        }
        return 0;
    }

    static class PrecalculatedDigest {
        final long nodeDigest;
        final long treeDigest;

        PrecalculatedDigest(long nodeDigest, long treeDigest) {
            this.nodeDigest = nodeDigest;
            this.treeDigest = treeDigest;
        }
    }


    /**
     * This structure is used to facilitate information sharing between PrepRP
     * and FinalRP.
     */
    static class ChangeRecord {
        PrecalculatedDigest precalculatedDigest;
        byte[] data;

        ChangeRecord(long zxid, String path, StatPersisted stat, int childCount, List<ACL> acl) {
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
            ChangeRecord changeRecord = new ChangeRecord(zxid, path, stat, childCount,
                    acl == null ? new ArrayList<>() : new ArrayList<>(acl));
            changeRecord.precalculatedDigest = precalculatedDigest;
            changeRecord.data = data;
            return changeRecord;
        }

    }

    byte[] generatePasswd(long id) {
        Random r = new Random(id ^ superSecret);
        byte[] p = new byte[16];
        r.nextBytes(p);
        return p;
    }

    protected boolean checkPasswd(long sessionId, byte[] passwd) {
        return sessionId != 0 && Arrays.equals(passwd, generatePasswd(sessionId));
    }

    long createSession(ServerCnxn cnxn, byte[] passwd, int timeout) {
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

    protected void revalidateSession(ServerCnxn cnxn, long sessionId, int sessionTimeout) throws IOException {
        boolean rc = sessionTracker.touchSession(sessionId, sessionTimeout);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(
                LOG,
                ZooTrace.SESSION_TRACE_MASK,
                "Session 0x" + Long.toHexString(sessionId) + " is valid: " + rc);
        }
        finishSessionInit(cnxn, rc);
    }

    public void reopenSession(ServerCnxn cnxn, long sessionId, byte[] passwd, int sessionTimeout) throws IOException {
        if (checkPasswd(sessionId, passwd)) {
            revalidateSession(cnxn, sessionId, sessionTimeout);
        } else {
            LOG.warn(
                "Incorrect password from {} for session 0x{}",
                cnxn.getRemoteSocketAddress(),
                Long.toHexString(sessionId));
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
            ConnectResponse rsp = new ConnectResponse(
                0,
                valid ? cnxn.getSessionTimeout() : 0,
                valid ? cnxn.getSessionId() : 0, // send 0 if session is no
                // longer valid
                valid ? generatePasswd(cnxn.getSessionId()) : new byte[16]);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
            bos.writeInt(-1, "len");
            rsp.serialize(bos, "connect");
            if (!cnxn.isOldClient) {
                bos.writeBool(this instanceof ReadOnlyZooKeeperServer, "readOnly");
            }
            baos.close();
            ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
            bb.putInt(bb.remaining() - 4).rewind();
            cnxn.sendBuffer(bb);

            if (valid) {
                LOG.debug(
                    "Established session 0x{} with negotiated timeout {} for client {}",
                    Long.toHexString(cnxn.getSessionId()),
                    cnxn.getSessionTimeout(),
                    cnxn.getRemoteSocketAddress());
                cnxn.enableRecv();
            } else {

                LOG.info(
                    "Invalid session 0x{} for client {}, probably expired",
                    Long.toHexString(cnxn.getSessionId()),
                    cnxn.getRemoteSocketAddress());
                cnxn.sendBuffer(ServerCnxnFactory.closeConn);
            }

        } catch (Exception e) {
            LOG.warn("Exception while establishing session, closing", e);
            cnxn.close(ServerCnxn.DisconnectReason.IO_EXCEPTION_IN_SESSION_INIT);
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
        enqueueRequest(si);
    }

    public void enqueueRequest(Request si) {
        if (requestThrottler == null) {
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
                if (requestThrottler == null) {
                    throw new RuntimeException("Not started");
                }
            }
        }
        requestThrottler.submitRequest(si);
    }

    public void submitRequestNow(Request si) {
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
                setLocalSessionFlag(si);
                firstProcessor.processRequest(si);
                if (si.cnxn != null) {
                    incInProcess();
                }
            } else {
                LOG.warn("Received packet at server of unknown type {}", si.type);
                // Update request accounting/throttling limits
                requestFinished(si);
                new UnimplementedRequestProcessor().processRequest(si);
            }
        } catch (MissingSessionException e) {
            LOG.debug("Dropping request.", e);
            // Update request accounting/throttling limits
            requestFinished(si);
        } catch (RequestProcessorException e) {
            LOG.error("Unable to process request", e);
            // Update request accounting/throttling limits
            requestFinished(si);
        }
    }

    public static int getSnapCount() {
        String sc = System.getProperty(SNAP_COUNT);
        try {
            int snapCount = Integer.parseInt(sc);

            // snapCount must be 2 or more. See org.apache.zookeeper.server.SyncRequestProcessor
            if (snapCount < 2) {
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
        LOG.info("tickTime set to {}", tickTime);
        this.tickTime = tickTime;
    }

    public static int getThrottledOpWaitTime() {
        return throttledOpWaitTime;
    }

    public static void setThrottledOpWaitTime(int time) {
        LOG.info("throttledOpWaitTime set to {}", time);
        throttledOpWaitTime = time;
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
        LOG.info("clientPortListenBacklog set to {}", backlog);
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

    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "the value won't change after startup")
    public void processConnectRequest(ServerCnxn cnxn, ByteBuffer incomingBuffer)
        throws IOException, ClientCnxnLimitException {

        BinaryInputArchive bia = BinaryInputArchive.getArchive(new ByteBufferInputStream(incomingBuffer));
        ConnectRequest connReq = new ConnectRequest();
        connReq.deserialize(bia, "connect");
        LOG.debug(
            "Session establishment request from client {} client's lastZxid is 0x{}",
            cnxn.getRemoteSocketAddress(),
            Long.toHexString(connReq.getLastZxidSeen()));

        long sessionId = connReq.getSessionId();
        int tokensNeeded = 1;
        if (connThrottle.isConnectionWeightEnabled()) {
            if (sessionId == 0) {
                if (localSessionEnabled) {
                    tokensNeeded = connThrottle.getRequiredTokensForLocal();
                } else {
                    tokensNeeded = connThrottle.getRequiredTokensForGlobal();
                }
            } else {
                tokensNeeded = connThrottle.getRequiredTokensForRenew();
            }
        }

        if (!connThrottle.checkLimit(tokensNeeded)) {
            throw new ClientCnxnLimitException();
        }
        ServerMetrics.getMetrics().CONNECTION_TOKEN_DEFICIT.add(connThrottle.getDeficit());

        ServerMetrics.getMetrics().CONNECTION_REQUEST_COUNT.add(1);

        boolean readOnly = false;
        try {
            readOnly = bia.readBool("readOnly");
            cnxn.isOldClient = false;
        } catch (IOException e) {
            // this is ok -- just a packet from an old client which
            // doesn't contain readOnly field
            LOG.warn(
                "Connection request from old client {}; will be dropped if server is in r-o mode",
                cnxn.getRemoteSocketAddress());
        }
        if (!readOnly && this instanceof ReadOnlyZooKeeperServer) {
            String msg = "Refusing session request for not-read-only client " + cnxn.getRemoteSocketAddress();
            LOG.info(msg);
            throw new CloseRequestException(msg, ServerCnxn.DisconnectReason.NOT_READ_ONLY_CLIENT);
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
            throw new CloseRequestException(msg, ServerCnxn.DisconnectReason.CLIENT_ZXID_AHEAD);
        }
        int sessionTimeout = connReq.getTimeOut();
        byte[] passwd = connReq.getPasswd();
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
        if (sessionId == 0) {
            long id = createSession(cnxn, passwd, sessionTimeout);
            LOG.debug(
                "Client attempting to establish new session: session = 0x{}, zxid = 0x{}, timeout = {}, address = {}",
                Long.toHexString(id),
                Long.toHexString(connReq.getLastZxidSeen()),
                connReq.getTimeOut(),
                cnxn.getRemoteSocketAddress());
        } else {
            validateSession(cnxn, sessionId);
            LOG.debug(
                "Client attempting to renew session: session = 0x{}, zxid = 0x{}, timeout = {}, address = {}",
                Long.toHexString(sessionId),
                Long.toHexString(connReq.getLastZxidSeen()),
                connReq.getTimeOut(),
                cnxn.getRemoteSocketAddress());
            if (serverCnxnFactory != null) {
                serverCnxnFactory.closeSession(sessionId, ServerCnxn.DisconnectReason.CLIENT_RECONNECT);
            }
            if (secureServerCnxnFactory != null) {
                secureServerCnxnFactory.closeSession(sessionId, ServerCnxn.DisconnectReason.CLIENT_RECONNECT);
            }
            cnxn.setSessionId(sessionId);
            reopenSession(cnxn, sessionId, passwd, sessionTimeout);
            ServerMetrics.getMetrics().CONNECTION_REVALIDATE_COUNT.add(1);

        }
    }

    /**
     * Validate if a particular session can be reestablished.
     *
     * @param cnxn
     * @param sessionId
     */
    protected void validateSession(ServerCnxn cnxn, long sessionId)
            throws IOException {
        // do nothing
    }

    public boolean shouldThrottle(long outStandingCount) {
        int globalOutstandingLimit = getGlobalOutstandingLimit();
        if (globalOutstandingLimit < getInflight() || globalOutstandingLimit < getInProcess()) {
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

    private void initLargeRequestThrottlingSettings() {
        setLargeRequestMaxBytes(Integer.getInteger("zookeeper.largeRequestMaxBytes", largeRequestMaxBytes));
        setLargeRequestThreshold(Integer.getInteger("zookeeper.largeRequestThreshold", -1));
    }

    public int getLargeRequestMaxBytes() {
        return largeRequestMaxBytes;
    }

    public void setLargeRequestMaxBytes(int bytes) {
        if (bytes <= 0) {
            LOG.warn("Invalid max bytes for all large requests {}. It should be a positive number.", bytes);
            LOG.warn("Will not change the setting. The max bytes stay at {}", largeRequestMaxBytes);
        } else {
            largeRequestMaxBytes = bytes;
            LOG.info("The max bytes for all large requests are set to {}", largeRequestMaxBytes);
        }
    }

    public int getLargeRequestThreshold() {
        return largeRequestThreshold;
    }

    public void setLargeRequestThreshold(int threshold) {
        if (threshold == 0 || threshold < -1) {
            LOG.warn("Invalid large request threshold {}. It should be -1 or positive. Setting to -1 ", threshold);
            largeRequestThreshold = -1;
        } else {
            largeRequestThreshold = threshold;
            LOG.info("The large request threshold is set to {}", largeRequestThreshold);
        }
    }

    public int getLargeRequestBytes() {
        return currentLargeRequestBytes.get();
    }

    private boolean isLargeRequest(int length) {
        // The large request limit is disabled when threshold is -1
        if (largeRequestThreshold == -1) {
            return false;
        }
        return length > largeRequestThreshold;
    }

    public boolean checkRequestSizeWhenReceivingMessage(int length) throws IOException {
        if (!isLargeRequest(length)) {
            return true;
        }
        if (currentLargeRequestBytes.get() + length <= largeRequestMaxBytes) {
            return true;
        } else {
            ServerMetrics.getMetrics().LARGE_REQUESTS_REJECTED.add(1);
            throw new IOException("Rejecting large request");
        }

    }

    private boolean checkRequestSizeWhenMessageReceived(int length) throws IOException {
        if (!isLargeRequest(length)) {
            return true;
        }

        int bytes = currentLargeRequestBytes.addAndGet(length);
        if (bytes > largeRequestMaxBytes) {
            currentLargeRequestBytes.addAndGet(-length);
            ServerMetrics.getMetrics().LARGE_REQUESTS_REJECTED.add(1);
            throw new IOException("Rejecting large request");
        }
        return true;
    }

    public void requestFinished(Request request) {
        int largeRequestLength = request.getLargeRequestSize();
        if (largeRequestLength != -1) {
            currentLargeRequestBytes.addAndGet(-largeRequestLength);
        }
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
            LOG.info("got auth packet {}", cnxn.getRemoteSocketAddress());
            AuthPacket authPacket = new AuthPacket();
            ByteBufferInputStream.byteBuffer2Record(incomingBuffer, authPacket);
            String scheme = authPacket.getScheme();
            ServerAuthenticationProvider ap = ProviderRegistry.getServerProvider(scheme);
            Code authReturn = KeeperException.Code.AUTHFAILED;
            if (ap != null) {
                try {
                    // handleAuthentication may close the connection, to allow the client to choose
                    // a different server to connect to.
                    authReturn = ap.handleAuthentication(
                        new ServerAuthenticationProvider.ServerObjs(this, cnxn),
                        authPacket.getAuth());
                } catch (RuntimeException e) {
                    LOG.warn("Caught runtime exception from AuthenticationProvider: {}", scheme, e);
                    authReturn = KeeperException.Code.AUTHFAILED;
                }
            }
            if (authReturn == KeeperException.Code.OK) {
                LOG.info("Session 0x{}: auth success for scheme {} and address {}",
                        Long.toHexString(cnxn.getSessionId()), scheme,
                        cnxn.getRemoteSocketAddress());
                ReplyHeader rh = new ReplyHeader(h.getXid(), 0, KeeperException.Code.OK.intValue());
                cnxn.sendResponse(rh, null, null);
            } else {
                if (ap == null) {
                    LOG.warn(
                        "No authentication provider for scheme: {} has {}",
                        scheme,
                        ProviderRegistry.listProviders());
                } else {
                    LOG.warn("Authentication failed for scheme: {}", scheme);
                }
                // send a response...
                ReplyHeader rh = new ReplyHeader(h.getXid(), 0, KeeperException.Code.AUTHFAILED.intValue());
                cnxn.sendResponse(rh, null, null);
                // ... and close connection
                cnxn.sendBuffer(ServerCnxnFactory.closeConn);
                cnxn.disableRecv();
            }
            return;
        } else if (h.getType() == OpCode.sasl) {
            processSasl(incomingBuffer, cnxn, h);
        } else {
            if (!authHelper.enforceAuthentication(cnxn, h.getXid())) {
                // Authentication enforcement is failed
                // Already sent response to user about failure and closed the session, lets return
                return;
            } else {
                Request si = new Request(cnxn, cnxn.getSessionId(), h.getXid(), h.getType(), incomingBuffer, cnxn.getAuthInfo());
                int length = incomingBuffer.limit();
                if (isLargeRequest(length)) {
                    // checkRequestSize will throw IOException if request is rejected
                    checkRequestSizeWhenMessageReceived(length);
                    si.setLargeRequestSize(length);
                }
                si.setOwner(ServerCnxn.me);
                submitRequest(si);
            }
        }
    }

    private static boolean isSaslSuperUser(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }

        Properties properties = System.getProperties();
        int prefixLen = SASL_SUPER_USER.length();

        for (String k : properties.stringPropertyNames()) {
            if (k.startsWith(SASL_SUPER_USER)
                && (k.length() == prefixLen || k.charAt(prefixLen) == '.')) {
                String value = properties.getProperty(k);

                if (value != null && value.equals(id)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean shouldAllowSaslFailedClientsConnect() {
        return Boolean.getBoolean(ALLOW_SASL_FAILED_CLIENTS);
    }

    private void processSasl(ByteBuffer incomingBuffer, ServerCnxn cnxn, RequestHeader requestHeader) throws IOException {
        LOG.debug("Responding to client SASL token.");
        GetSASLRequest clientTokenRecord = new GetSASLRequest();
        ByteBufferInputStream.byteBuffer2Record(incomingBuffer, clientTokenRecord);
        byte[] clientToken = clientTokenRecord.getToken();
        LOG.debug("Size of client SASL token: {}", clientToken.length);
        byte[] responseToken = null;
        try {
            ZooKeeperSaslServer saslServer = cnxn.zooKeeperSaslServer;
            try {
                // note that clientToken might be empty (clientToken.length == 0):
                // if using the DIGEST-MD5 mechanism, clientToken will be empty at the beginning of the
                // SASL negotiation process.
                responseToken = saslServer.evaluateResponse(clientToken);
                if (saslServer.isComplete()) {
                    String authorizationID = saslServer.getAuthorizationID();
                    LOG.info("Session 0x{}: adding SASL authorization for authorizationID: {}",
                            Long.toHexString(cnxn.getSessionId()), authorizationID);
                    cnxn.addAuthInfo(new Id("sasl", authorizationID));

                    if (isSaslSuperUser(authorizationID)) {
                        cnxn.addAuthInfo(new Id("super", ""));
                        LOG.info(
                            "Session 0x{}: Authenticated Id '{}' as super user",
                            Long.toHexString(cnxn.getSessionId()),
                            authorizationID);
                    }
                }
            } catch (SaslException e) {
                LOG.warn("Client {} failed to SASL authenticate: {}", cnxn.getRemoteSocketAddress(), e);
                if (shouldAllowSaslFailedClientsConnect() && !authHelper.isSaslAuthRequired()) {
                    LOG.warn("Maintaining client connection despite SASL authentication failure.");
                } else {
                    int error;
                    if (authHelper.isSaslAuthRequired()) {
                        LOG.warn(
                            "Closing client connection due to server requires client SASL authenticaiton,"
                                + "but client SASL authentication has failed, or client is not configured with SASL "
                                + "authentication.");
                        error = Code.SESSIONCLOSEDREQUIRESASLAUTH.intValue();
                    } else {
                        LOG.warn("Closing client connection due to SASL authentication failure.");
                        error = Code.AUTHFAILED.intValue();
                    }

                    ReplyHeader replyHeader = new ReplyHeader(requestHeader.getXid(), 0, error);
                    cnxn.sendResponse(replyHeader, new SetSASLResponse(null), "response");
                    cnxn.sendCloseSession();
                    cnxn.disableRecv();
                    return;
                }
            }
        } catch (NullPointerException e) {
            LOG.error("cnxn.saslServer is null: cnxn object did not initialize its saslServer properly.");
        }
        if (responseToken != null) {
            LOG.debug("Size of server SASL response: {}", responseToken.length);
        }

        ReplyHeader replyHeader = new ReplyHeader(requestHeader.getXid(), 0, Code.OK.intValue());
        Record record = new SetSASLResponse(responseToken);
        cnxn.sendResponse(replyHeader, record, "response");
    }

    // entry point for quorum/Learner.java
    public ProcessTxnResult processTxn(TxnHeader hdr, Record txn) {
        processTxnForSessionEvents(null, hdr, txn);
        return processTxnInDB(hdr, txn, null);
    }

    // entry point for FinalRequestProcessor.java
    public ProcessTxnResult processTxn(Request request) {
        TxnHeader hdr = request.getHdr();
        processTxnForSessionEvents(request, hdr, request.getTxn());

        final boolean writeRequest = (hdr != null);
        final boolean quorumRequest = request.isQuorum();

        // return fast w/o synchronization when we get a read
        if (!writeRequest && !quorumRequest) {
            return new ProcessTxnResult();
        }
        synchronized (outstandingChanges) {
            ProcessTxnResult rc = processTxnInDB(hdr, request.getTxn(), request.getTxnDigest());

            // request.hdr is set for write requests, which are the only ones
            // that add to outstandingChanges.
            if (writeRequest) {
                long zxid = hdr.getZxid();
                while (!outstandingChanges.isEmpty()
                        && outstandingChanges.peek().zxid <= zxid) {
                    ChangeRecord cr = outstandingChanges.remove();
                    ServerMetrics.getMetrics().OUTSTANDING_CHANGES_REMOVED.add(1);
                    if (cr.zxid < zxid) {
                        LOG.warn(
                            "Zxid outstanding 0x{} is less than current 0x{}",
                            Long.toHexString(cr.zxid),
                            Long.toHexString(zxid));
                    }
                    if (outstandingChangesForPath.get(cr.path) == cr) {
                        outstandingChangesForPath.remove(cr.path);
                    }
                }
            }

            // do not add non quorum packets to the queue.
            if (quorumRequest) {
                getZKDatabase().addCommittedProposal(request);
            }
            return rc;
        }
    }

    private void processTxnForSessionEvents(Request request, TxnHeader hdr, Record txn) {
        int opCode = (request == null) ? hdr.getType() : request.type;
        long sessionId = (request == null) ? hdr.getClientId() : request.sessionId;

        if (opCode == OpCode.createSession) {
            if (hdr != null && txn instanceof CreateSessionTxn) {
                CreateSessionTxn cst = (CreateSessionTxn) txn;
                sessionTracker.commitSession(sessionId, cst.getTimeOut());
            } else if (request == null || !request.isLocalSession()) {
                LOG.warn("*****>>>>> Got {} {}",  txn.getClass(), txn.toString());
            }
        } else if (opCode == OpCode.closeSession) {
            sessionTracker.removeSession(sessionId);
        }
    }

    private ProcessTxnResult processTxnInDB(TxnHeader hdr, Record txn, TxnDigest digest) {
        if (hdr == null) {
            return new ProcessTxnResult();
        } else {
            return getZKDatabase().processTxn(hdr, txn, digest);
        }
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

    public ResponseCache getGetChildrenResponseCache() {
        return isResponseCachingEnabled ? getChildrenResponseCache : null;
    }

    protected void registerMetrics() {
        MetricsContext rootContext = ServerMetrics.getMetrics().getMetricsProvider().getRootContext();

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
        rootContext.registerGauge("local_sessions", this.getSessionTracker()::getLocalSessionCount);

        OSMXBean osMbean = new OSMXBean();
        rootContext.registerGauge("open_file_descriptor_count", osMbean::getOpenFileDescriptorCount);
        rootContext.registerGauge("max_file_descriptor_count", osMbean::getMaxFileDescriptorCount);
        rootContext.registerGauge("connection_drop_probability", this::getConnectionDropChance);

        rootContext.registerGauge("last_client_response_size", stats.getClientResponseStats()::getLastBufferSize);
        rootContext.registerGauge("max_client_response_size", stats.getClientResponseStats()::getMaxBufferSize);
        rootContext.registerGauge("min_client_response_size", stats.getClientResponseStats()::getMinBufferSize);

        rootContext.registerGauge("outstanding_tls_handshake", this::getOutstandingHandshakeNum);
        rootContext.registerGauge("auth_failed_count", stats::getAuthFailedCount);
        rootContext.registerGauge("non_mtls_remote_conn_count", stats::getNonMTLSRemoteConnCount);
        rootContext.registerGauge("non_mtls_local_conn_count", stats::getNonMTLSLocalConnCount);
    }

    protected void unregisterMetrics() {

        MetricsContext rootContext = ServerMetrics.getMetrics().getMetricsProvider().getRootContext();

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

        rootContext.unregisterGauge("auth_failed_count");
        rootContext.unregisterGauge("non_mtls_remote_conn_count");
        rootContext.unregisterGauge("non_mtls_local_conn_count");


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

    /**
     * Grant or deny authorization to an operation on a node as a function of:
     * @param cnxn :    the server connection
     * @param acl :     set of ACLs for the node
     * @param perm :    the permission that the client is requesting
     * @param ids :     the credentials supplied by the client
     * @param path :    the ZNode path
     * @param setAcls : for set ACL operations, the list of ACLs being set. Otherwise null.
     */
    public void checkACL(ServerCnxn cnxn, List<ACL> acl, int perm, List<Id> ids, String path, List<ACL> setAcls) throws KeeperException.NoAuthException {
        if (skipACL) {
            return;
        }

        LOG.debug("Permission requested: {} ", perm);
        LOG.debug("ACLs for node: {}", acl);
        LOG.debug("Client credentials: {}", ids);

        if (acl == null || acl.size() == 0) {
            return;
        }
        for (Id authId : ids) {
            if (authId.getScheme().equals("super")) {
                return;
            }
        }
        for (ACL a : acl) {
            Id id = a.getId();
            if ((a.getPerms() & perm) != 0) {
                if (id.getScheme().equals("world") && id.getId().equals("anyone")) {
                    return;
                }
                ServerAuthenticationProvider ap = ProviderRegistry.getServerProvider(id.getScheme());
                if (ap != null) {
                    for (Id authId : ids) {
                        if (authId.getScheme().equals(id.getScheme())
                            && ap.matches(
                                new ServerAuthenticationProvider.ServerObjs(this, cnxn),
                                new ServerAuthenticationProvider.MatchValues(path, authId.getId(), id.getId(), perm, setAcls))) {
                            return;
                        }
                    }
                }
            }
        }
        throw new KeeperException.NoAuthException();
    }

    /**
     * check a path whether exceeded the quota.
     *
     * @param path
     *            the path of the node, used for the quota prefix check
     * @param lastData
     *            the current node data, {@code null} for none
     * @param data
     *            the data to be set, or {@code null} for none
     * @param type
     *            currently, create and setData need to check quota
     */
    public void checkQuota(String path, byte[] lastData, byte[] data, int type) throws KeeperException.QuotaExceededException {
        if (!enforceQuota) {
            return;
        }
        long dataBytes = (data == null) ? 0 : data.length;
        ZKDatabase zkDatabase = getZKDatabase();
        String lastPrefix = zkDatabase.getDataTree().getMaxPrefixWithQuota(path);
        if (StringUtils.isEmpty(lastPrefix)) {
            return;
        }

        switch (type) {
            case OpCode.create:
                checkQuota(lastPrefix, dataBytes, 1);
                break;
            case OpCode.setData:
                checkQuota(lastPrefix, dataBytes - (lastData == null ? 0 : lastData.length), 0);
                break;
             default:
                 throw new IllegalArgumentException("Unsupported OpCode for checkQuota: " + type);
        }
    }

    /**
     * check a path whether exceeded the quota.
     *
     * @param lastPrefix
                  the path of the node which has a quota.
     * @param bytesDiff
     *            the diff to be added to number of bytes
     * @param countDiff
     *            the diff to be added to the count
     */
    private void checkQuota(String lastPrefix, long bytesDiff, long countDiff)
            throws KeeperException.QuotaExceededException {
        LOG.debug("checkQuota: lastPrefix={}, bytesDiff={}, countDiff={}", lastPrefix, bytesDiff, countDiff);

        // now check the quota we set
        String limitNode = Quotas.limitPath(lastPrefix);
        DataNode node = getZKDatabase().getNode(limitNode);
        StatsTrack limitStats;
        if (node == null) {
            // should not happen
            LOG.error("Missing limit node for quota {}", limitNode);
            return;
        }
        synchronized (node) {
            limitStats = new StatsTrack(node.data);
        }
        //check the quota
        boolean checkCountQuota = countDiff != 0 && (limitStats.getCount() > -1 || limitStats.getCountHardLimit() > -1);
        boolean checkByteQuota = bytesDiff != 0 && (limitStats.getBytes() > -1 || limitStats.getByteHardLimit() > -1);

        if (!checkCountQuota && !checkByteQuota) {
            return;
        }

        //check the statPath quota
        String statNode = Quotas.statPath(lastPrefix);
        node = getZKDatabase().getNode(statNode);

        StatsTrack currentStats;
        if (node == null) {
            // should not happen
            LOG.error("Missing node for stat {}", statNode);
            return;
        }
        synchronized (node) {
            currentStats = new StatsTrack(node.data);
        }

        //check the Count Quota
        if (checkCountQuota) {
            long newCount = currentStats.getCount() + countDiff;
            boolean isCountHardLimit = limitStats.getCountHardLimit() > -1 ? true : false;
            long countLimit = isCountHardLimit ? limitStats.getCountHardLimit() : limitStats.getCount();

            if (newCount > countLimit) {
                String msg = "Quota exceeded: " + lastPrefix + " [current count=" + newCount + ", " + (isCountHardLimit ? "hard" : "soft") + "CountLimit=" + countLimit + "]";
                RATE_LOGGER.rateLimitLog(msg);
                if (isCountHardLimit) {
                    throw new KeeperException.QuotaExceededException(lastPrefix);
                }
            }
        }

        //check the Byte Quota
        if (checkByteQuota) {
            long newBytes = currentStats.getBytes() + bytesDiff;
            boolean isByteHardLimit = limitStats.getByteHardLimit() > -1 ? true : false;
            long byteLimit = isByteHardLimit ? limitStats.getByteHardLimit() : limitStats.getBytes();
            if (newBytes > byteLimit) {
                String msg = "Quota exceeded: " + lastPrefix + " [current bytes=" + newBytes + ", " + (isByteHardLimit ? "hard" : "soft") + "ByteLimit=" + byteLimit + "]";
                RATE_LOGGER.rateLimitLog(msg);
                if (isByteHardLimit) {
                    throw new KeeperException.QuotaExceededException(lastPrefix);
                }
            }
        }
    }

    public static boolean isDigestEnabled() {
        return digestEnabled;
    }

    public static void setDigestEnabled(boolean digestEnabled) {
        LOG.info("{} = {}", ZOOKEEPER_DIGEST_ENABLED, digestEnabled);
        ZooKeeperServer.digestEnabled = digestEnabled;
    }

    /**
     * Trim a path to get the immediate predecessor.
     *
     * @param path
     * @return
     * @throws KeeperException.BadArgumentsException
     */
    private String parentPath(String path) throws KeeperException.BadArgumentsException {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash == -1 || path.indexOf('\0') != -1 || getZKDatabase().isSpecialPath(path)) {
            throw new KeeperException.BadArgumentsException(path);
        }
        return lastSlash == 0 ? "/" : path.substring(0, lastSlash);
    }

    private String effectiveACLPath(Request request) throws KeeperException.BadArgumentsException, KeeperException.InvalidACLException {
        boolean mustCheckACL = false;
        String path = null;
        List<ACL> acl = null;

        switch (request.type) {
        case OpCode.create:
        case OpCode.create2: {
            CreateRequest req = new CreateRequest();
            if (buffer2Record(request.request, req)) {
                mustCheckACL = true;
                acl = req.getAcl();
                path = parentPath(req.getPath());
            }
            break;
        }
        case OpCode.delete: {
            DeleteRequest req = new DeleteRequest();
            if (buffer2Record(request.request, req)) {
                path = parentPath(req.getPath());
            }
            break;
        }
        case OpCode.setData: {
            SetDataRequest req = new SetDataRequest();
            if (buffer2Record(request.request, req)) {
                path = req.getPath();
            }
            break;
        }
        case OpCode.setACL: {
            SetACLRequest req = new SetACLRequest();
            if (buffer2Record(request.request, req)) {
                mustCheckACL = true;
                acl = req.getAcl();
                path = req.getPath();
            }
            break;
        }
        }

        if (mustCheckACL) {
            /* we ignore the extrapolated ACL returned by fixupACL because
             * we only care about it being well-formed (and if it isn't, an
             * exception will be raised).
             */
            PrepRequestProcessor.fixupACL(path, request.authInfo, acl);
        }

        return path;
    }

    private int effectiveACLPerms(Request request) {
        switch (request.type) {
        case OpCode.create:
        case OpCode.create2:
            return ZooDefs.Perms.CREATE;
        case OpCode.delete:
            return ZooDefs.Perms.DELETE;
        case OpCode.setData:
            return ZooDefs.Perms.WRITE;
        case OpCode.setACL:
            return ZooDefs.Perms.ADMIN;
        default:
            return ZooDefs.Perms.ALL;
        }
    }

    /**
     * Check Write Requests for Potential Access Restrictions
     * <p/>
     * Before a request is being proposed to the quorum, lets check it
     * against local ACLs. Non-write requests (read, session, etc.)
     * are passed along. Invalid requests are sent a response.
     * <p/>
     * While we are at it, if the request will set an ACL: make sure it's
     * a valid one.
     *
     * @param request
     * @return true if request is permitted, false if not.
     * @throws java.io.IOException
     */
    public boolean authWriteRequest(Request request) {
        int err;
        String pathToCheck;

        if (!enableEagerACLCheck) {
            return true;
        }

        err = KeeperException.Code.OK.intValue();

        try {
            pathToCheck = effectiveACLPath(request);
            if (pathToCheck != null) {
                checkACL(request.cnxn, zkDb.getACL(pathToCheck, null), effectiveACLPerms(request), request.authInfo, pathToCheck, null);
            }
        } catch (KeeperException.NoAuthException e) {
            LOG.debug("Request failed ACL check", e);
            err = e.code().intValue();
        } catch (KeeperException.InvalidACLException e) {
            LOG.debug("Request has an invalid ACL check", e);
            err = e.code().intValue();
        } catch (KeeperException.NoNodeException e) {
            LOG.debug("ACL check against non-existent node: {}", e.getMessage());
        } catch (KeeperException.BadArgumentsException e) {
            LOG.debug("ACL check against illegal node path: {}", e.getMessage());
        } catch (Throwable t) {
            LOG.error("Uncaught exception in authWriteRequest with: ", t);
            throw t;
        } finally {
            if (err != KeeperException.Code.OK.intValue()) {
                /*  This request has a bad ACL, so we are dismissing it early. */
                decInProcess();
                ReplyHeader rh = new ReplyHeader(request.cxid, 0, err);
                try {
                    request.cnxn.sendResponse(rh, null, null);
                } catch (IOException e) {
                    LOG.error("IOException : {}", e);
                }
            }
        }

        return err == KeeperException.Code.OK.intValue();
    }

    private boolean buffer2Record(ByteBuffer request, Record record) {
        boolean rv = false;
        try {
            ByteBufferInputStream.byteBuffer2Record(request, record);
            request.rewind();
            rv = true;
        } catch (IOException ex) {
        }

        return rv;
    }

    public int getOutstandingHandshakeNum() {
        if (serverCnxnFactory instanceof NettyServerCnxnFactory) {
            return ((NettyServerCnxnFactory) serverCnxnFactory).getOutstandingHandshakeNum();
        } else {
            return 0;
        }
    }

    public boolean isReconfigEnabled() {
        return this.reconfigEnabled;
    }

    public ZooKeeperServerShutdownHandler getZkShutdownHandler() {
        return zkShutdownHandler;
    }

}
