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
package org.apache.zookeeper.server.quorum;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.security.sasl.SaslException;

import org.apache.zookeeper.common.AtomicFileOutputStream;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.jmx.ZKMBeanInfo;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ZooKeeperThread;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.auth.QuorumAuth;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthLearner;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthServer;
import org.apache.zookeeper.server.quorum.auth.SaslQuorumAuthLearner;
import org.apache.zookeeper.server.quorum.auth.SaslQuorumAuthServer;
import org.apache.zookeeper.server.quorum.auth.NullQuorumAuthLearner;
import org.apache.zookeeper.server.quorum.auth.NullQuorumAuthServer;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class manages the quorum protocol. There are three states this server
 * can be in:
 * <ol>
 * <li>Leader election - each server will elect a leader (proposing itself as a
 * leader initially).</li>
 * <li>Follower - the server will synchronize with the leader and replicate any
 * transactions.</li>
 * <li>Leader - the server will process requests and forward them to followers.
 * A majority of followers must log the request before it can be accepted.
 * </ol>
 *
 * This class will setup a datagram socket that will always respond with its
 * view of the current leader. The response will take the form of:
 *
 * <pre>
 * int xid;
 *
 * long myid;
 *
 * long leader_id;
 *
 * long leader_zxid;
 * </pre>
 *
 * The request for the current leader will consist solely of an xid: int xid;
 */
public class QuorumPeer extends ZooKeeperThread implements QuorumStats.Provider {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumPeer.class);

    QuorumBean jmxQuorumBean;
    LocalPeerBean jmxLocalPeerBean;
    LeaderElectionBean jmxLeaderElectionBean;
    QuorumCnxManager qcm;
    QuorumAuthServer authServer;
    QuorumAuthLearner authLearner;
    // VisibleForTesting. This flag is used to know whether qLearner's and
    // qServer's login context has been initialized as ApacheDS has concurrency
    // issues. Refer https://issues.apache.org/jira/browse/ZOOKEEPER-2712
    private boolean authInitialized = false;

    /* ZKDatabase is a top level member of quorumpeer 
     * which will be used in all the zookeeperservers
     * instantiated later. Also, it is created once on 
     * bootup and only thrown away in case of a truncate
     * message from the leader
     */
    private ZKDatabase zkDb;

    public static class QuorumServer {
        private QuorumServer(long id, InetSocketAddress addr,
                InetSocketAddress electionAddr) {
            this.id = id;
            this.addr = addr;
            this.electionAddr = electionAddr;
        }

        // VisibleForTesting
        public QuorumServer(long id, InetSocketAddress addr) {
            this.id = id;
            this.addr = addr;
            this.electionAddr = null;
        }
        
        private QuorumServer(long id, InetSocketAddress addr,
                    InetSocketAddress electionAddr, LearnerType type) {
            this.id = id;
            this.addr = addr;
            this.electionAddr = electionAddr;
            this.type = type;
        }
        
        public QuorumServer(long id, String hostname,
                            Integer port, Integer electionPort,
                            LearnerType type) {
	    this.id = id;
	    this.hostname=hostname;
	    if (port!=null){
                this.port=port;
	    }
	    if (electionPort!=null){
                this.electionPort=electionPort;
	    }
	    if (type!=null){
                this.type = type;
	    }
	    this.recreateSocketAddresses();
	}

        /**
         * Performs a DNS lookup of hostname and (re)creates the this.addr and
         * this.electionAddr InetSocketAddress objects as appropriate
         *
         * If the DNS lookup fails, this.addr and electionAddr remain
         * unmodified, unless they were never set. If this.addr is null, then
         * it is set with an unresolved InetSocketAddress object. this.electionAddr
         * is handled similarly.
         */
        public void recreateSocketAddresses() {
            InetAddress address = null;
            try {
                // the time, in milliseconds, before {@link InetAddress#isReachable} aborts
                // in {@link #getReachableAddress}.
                int ipReachableTimeout = 0;
                String ipReachableValue = System.getProperty("zookeeper.ipReachableTimeout");
                if (ipReachableValue != null) {
                    try {
                        ipReachableTimeout = Integer.parseInt(ipReachableValue);
                    } catch (NumberFormatException e) {
                        LOG.error("{} is not a valid number", ipReachableValue);
                    }
                }
                // zookeeper.ipReachableTimeout is not defined
                if (ipReachableTimeout <= 0) {
                    address = InetAddress.getByName(this.hostname);
                } else {
                    address = getReachableAddress(this.hostname, ipReachableTimeout);
                }
                LOG.info("Resolved hostname: {} to address: {}", this.hostname, address);
                this.addr = new InetSocketAddress(address, this.port);
                if (this.electionPort > 0){
                    this.electionAddr = new InetSocketAddress(address, this.electionPort);
                }
            } catch (UnknownHostException ex) {
                LOG.warn("Failed to resolve address: {}", this.hostname, ex);
                // Have we succeeded in the past?
                if (this.addr != null) {
                    // Yes, previously the lookup succeeded. Leave things as they are
                    return;
                }
                // The hostname has never resolved. Create our InetSocketAddress(es) as unresolved
                this.addr = InetSocketAddress.createUnresolved(this.hostname, this.port);
                if (this.electionPort > 0){
                    this.electionAddr = InetSocketAddress.createUnresolved(this.hostname,
                                                                           this.electionPort);
                }
            }
        }

        /**
         * Resolve the hostname to IP addresses, and find one reachable address.
         *
         * @param hostname the name of the host
         * @param timeout the time, in milliseconds, before {@link InetAddress#isReachable}
         *                aborts
         * @return a reachable IP address. If no such IP address can be found,
         *         just return the first IP address of the hostname.
         *
         * @exception UnknownHostException
         */
        public InetAddress getReachableAddress(String hostname, int timeout) 
                throws UnknownHostException {
            InetAddress[] addresses = InetAddress.getAllByName(hostname);
            for (InetAddress a : addresses) {
                try {
                    if (a.isReachable(timeout)) {
                        return a;
                    } 
                } catch (IOException e) {
                    LOG.warn("IP address {} is unreachable", a);
                }
            }
            // All the IP addresses are unreachable, just return the first one.
            return addresses[0];
        }

        public InetSocketAddress addr;

        public InetSocketAddress electionAddr;
        
        public String hostname;

        public int port=2888;

        public int electionPort=-1;

        public long id;
        
        public LearnerType type = LearnerType.PARTICIPANT;
    }

    public enum ServerState {
        LOOKING, FOLLOWING, LEADING, OBSERVING;
    }
    
    /*
     * A peer can either be participating, which implies that it is willing to
     * both vote in instances of consensus and to elect or become a Leader, or
     * it may be observing in which case it isn't.
     * 
     * We need this distinction to decide which ServerState to move to when 
     * conditions change (e.g. which state to become after LOOKING). 
     */
    public enum LearnerType {
        PARTICIPANT, OBSERVER;
    }
    
    /*
     * To enable observers to have no identifier, we need a generic identifier
     * at least for QuorumCnxManager. We use the following constant to as the
     * value of such a generic identifier. 
     */
    
    static final long OBSERVER_ID = Long.MAX_VALUE;

    /*
     * Record leader election time
     */
    public long start_fle, end_fle;
    
    /*
     * Default value of peer is participant
     */
    private LearnerType learnerType = LearnerType.PARTICIPANT;
    
    public LearnerType getLearnerType() {
        return learnerType;
    }
    
    /**
     * Sets the LearnerType both in the QuorumPeer and in the peerMap
     */
    public void setLearnerType(LearnerType p) {
        learnerType = p;
        if (quorumPeers.containsKey(this.myid)) {
            this.quorumPeers.get(myid).type = p;
        } else {
            LOG.error("Setting LearnerType to " + p + " but " + myid 
                    + " not in QuorumPeers. ");
        }
        
    }
    /**
     * The servers that make up the cluster
     */
    protected Map<Long, QuorumServer> quorumPeers;
    public int getQuorumSize(){
        return getVotingView().size();
    }
    
    /**
     * QuorumVerifier implementation; default (majority). 
     */
    
    private QuorumVerifier quorumConfig;
    
    /**
     * My id
     */
    private long myid;


    /**
     * get the id of this quorum peer.
     */
    public long getId() {
        return myid;
    }

    /**
     * This is who I think the leader currently is.
     */
    volatile private Vote currentVote;
    
    /**
     * ... and its counterpart for backward compatibility
     */
    volatile private Vote bcVote;
        
    public synchronized Vote getCurrentVote(){
        return currentVote;
    }
       
    public synchronized void setCurrentVote(Vote v){
        currentVote = v;
    }
    
    synchronized Vote getBCVote() {
        if (bcVote == null) {
            return currentVote;
        } else {
            return bcVote;
        }
    }

    synchronized void setBCVote(Vote v) {
        bcVote = v;
    }
    
    volatile boolean running = true;

    /**
     * The number of milliseconds of each tick
     */
    protected int tickTime;

    /**
     * Minimum number of milliseconds to allow for session timeout.
     * A value of -1 indicates unset, use default.
     */
    protected int minSessionTimeout = -1;

    /**
     * Maximum number of milliseconds to allow for session timeout.
     * A value of -1 indicates unset, use default.
     */
    protected int maxSessionTimeout = -1;

    /**
     * The number of ticks that the initial synchronization phase can take
     */
    protected int initLimit;

    /**
     * The number of ticks that can pass between sending a request and getting
     * an acknowledgment
     */
    protected int syncLimit;
    
    /**
     * Enables/Disables sync request processor. This option is enabled
     * by default and is to be used with observers.
     */
    protected boolean syncEnabled = true;

    /**
     * The current tick
     */
    protected AtomicInteger tick = new AtomicInteger();

    /**
     * Whether or not to listen on all IPs for the two quorum ports
     * (broadcast and fast leader election).
     */
    protected boolean quorumListenOnAllIPs = false;

    /**
     * Enable/Disables quorum authentication using sasl. Defaulting to false.
     */
    protected boolean quorumSaslEnableAuth;

    /**
     * If this is false, quorum peer server will accept another quorum peer client
     * connection even if the authentication did not succeed. This can be used while
     * upgrading ZooKeeper server. Defaulting to false (required).
     */
    protected boolean quorumServerSaslAuthRequired;

    /**
     * If this is false, quorum peer learner will talk to quorum peer server
     * without authentication. This can be used while upgrading ZooKeeper
     * server. Defaulting to false (required).
     */
    protected boolean quorumLearnerSaslAuthRequired;

    /**
     * Kerberos quorum service principal. Defaulting to 'zkquorum/localhost'.
     */
    protected String quorumServicePrincipal;

    /**
     * Quorum learner login context name in jaas-conf file to read the kerberos
     * security details. Defaulting to 'QuorumLearner'.
     */
    protected String quorumLearnerLoginContext;

    /**
     * Quorum server login context name in jaas-conf file to read the kerberos
     * security details. Defaulting to 'QuorumServer'.
     */
    protected String quorumServerLoginContext;

    // TODO: need to tune the default value of thread size
    private static final int QUORUM_CNXN_THREADS_SIZE_DEFAULT_VALUE = 20;
    /**
     * The maximum number of threads to allow in the connectionExecutors thread
     * pool which will be used to initiate quorum server connections.
     */
    protected int quorumCnxnThreadsSize = QUORUM_CNXN_THREADS_SIZE_DEFAULT_VALUE;

    /**
     * Keeps time taken for leader election in milliseconds. Sets the value to
     * this variable only after the completion of leader election.
     */
    private long electionTimeTaken = -1;

    /**
     * @deprecated As of release 3.4.0, this class has been deprecated, since
     * it is used with one of the udp-based versions of leader election, which
     * we are also deprecating. 
     * 
     * This class simply responds to requests for the current leader of this
     * node.
     * <p>
     * The request contains just an xid generated by the requestor.
     * <p>
     * The response has the xid, the id of this server, the id of the leader,
     * and the zxid of the leader.
     *
     *
     */
    @Deprecated
    class ResponderThread extends ZooKeeperThread {
        ResponderThread() {
            super("ResponderThread");
        }

        volatile boolean running = true;
        
        @Override
        public void run() {
            try {
                byte b[] = new byte[36];
                ByteBuffer responseBuffer = ByteBuffer.wrap(b);
                DatagramPacket packet = new DatagramPacket(b, b.length);
                while (running) {
                    udpSocket.receive(packet);
                    if (packet.getLength() != 4) {
                        LOG.warn("Got more than just an xid! Len = "
                                + packet.getLength());
                    } else {
                        responseBuffer.clear();
                        responseBuffer.getInt(); // Skip the xid
                        responseBuffer.putLong(myid);
                        Vote current = getCurrentVote();
                        switch (getPeerState()) {
                        case LOOKING:
                            responseBuffer.putLong(current.getId());
                            responseBuffer.putLong(current.getZxid());
                            break;
                        case LEADING:
                            responseBuffer.putLong(myid);
                            try {
                                long proposed;
                                synchronized(leader) {
                                    proposed = leader.lastProposed;
                                }
                                responseBuffer.putLong(proposed);
                            } catch (NullPointerException npe) {
                                // This can happen in state transitions,
                                // just ignore the request
                            }
                            break;
                        case FOLLOWING:
                            responseBuffer.putLong(current.getId());
                            try {
                                responseBuffer.putLong(follower.getZxid());
                            } catch (NullPointerException npe) {
                                // This can happen in state transitions,
                                // just ignore the request
                            }
                            break;
                        case OBSERVING:
                            // Do nothing, Observers keep themselves to
                            // themselves. 
                            break;
                        }
                        packet.setData(b);
                        udpSocket.send(packet);
                    }
                    packet.setLength(b.length);
                }
            } catch (RuntimeException e) {
                LOG.warn("Unexpected runtime exception in ResponderThread",e);
            } catch (IOException e) {
                LOG.warn("Unexpected IO exception in ResponderThread",e);
            } finally {
                LOG.warn("QuorumPeer responder thread exited");
            }
        }
    }

    private ServerState state = ServerState.LOOKING;

    public synchronized void setPeerState(ServerState newState){
        state=newState;
    }

    public synchronized ServerState getPeerState(){
        return state;
    }

    DatagramSocket udpSocket;

    private InetSocketAddress myQuorumAddr;

    public InetSocketAddress getQuorumAddress(){
        return myQuorumAddr;
    }

    private int electionType;

    Election electionAlg;

    ServerCnxnFactory cnxnFactory;
    private FileTxnSnapLog logFactory = null;

    private final QuorumStats quorumStats;

    public static QuorumPeer testingQuorumPeer() throws SaslException {
        return new QuorumPeer();
    }

    protected QuorumPeer() throws SaslException {
        super("QuorumPeer");
        quorumStats = new QuorumStats(this);
        initialize();
    }
    
   
    /**
     * For backward compatibility purposes, we instantiate QuorumMaj by default.
     */
    
    public QuorumPeer(Map<Long, QuorumServer> quorumPeers, File dataDir,
            File dataLogDir, int electionType,
            long myid, int tickTime, int initLimit, int syncLimit,
            ServerCnxnFactory cnxnFactory) throws IOException {
        this(quorumPeers, dataDir, dataLogDir, electionType, myid, tickTime, 
        		initLimit, syncLimit, false, cnxnFactory, 
        		new QuorumMaj(countParticipants(quorumPeers)));
    }
    
    public QuorumPeer(Map<Long, QuorumServer> quorumPeers, File dataDir,
            File dataLogDir, int electionType,
            long myid, int tickTime, int initLimit, int syncLimit,
            boolean quorumListenOnAllIPs,
            ServerCnxnFactory cnxnFactory, 
            QuorumVerifier quorumConfig) throws IOException {
        this();
        this.cnxnFactory = cnxnFactory;
        this.quorumPeers = quorumPeers;
        this.electionType = electionType;
        this.myid = myid;
        this.tickTime = tickTime;
        this.initLimit = initLimit;
        this.syncLimit = syncLimit;        
        this.quorumListenOnAllIPs = quorumListenOnAllIPs;
        this.logFactory = new FileTxnSnapLog(dataLogDir, dataDir);
        this.zkDb = new ZKDatabase(this.logFactory);
        if(quorumConfig == null)
            this.quorumConfig = new QuorumMaj(countParticipants(quorumPeers));
        else this.quorumConfig = quorumConfig;
    }

    public void initialize() throws SaslException {
        // init quorum auth server & learner
        if (isQuorumSaslAuthEnabled()) {
            Set<String> authzHosts = new HashSet<String>();
            for (QuorumServer qs : getView().values()) {
                authzHosts.add(qs.hostname);
            }
            authServer = new SaslQuorumAuthServer(isQuorumServerSaslAuthRequired(),
                    quorumServerLoginContext, authzHosts);
            authLearner = new SaslQuorumAuthLearner(isQuorumLearnerSaslAuthRequired(),
                    quorumServicePrincipal, quorumLearnerLoginContext);
            authInitialized = true;
        } else {
            authServer = new NullQuorumAuthServer();
            authLearner = new NullQuorumAuthLearner();
        }
    }

    QuorumStats quorumStats() {
        return quorumStats;
    }
    
    @Override
    public synchronized void start() {
        loadDataBase();
        cnxnFactory.start();        
        startLeaderElection();
        super.start();
    }

    private void loadDataBase() {
        File updating = new File(getTxnFactory().getSnapDir(),
                                 UPDATING_EPOCH_FILENAME);
		try {
            zkDb.loadDataBase();

            // load the epochs
            long lastProcessedZxid = zkDb.getDataTree().lastProcessedZxid;
    		long epochOfZxid = ZxidUtils.getEpochFromZxid(lastProcessedZxid);
            try {
            	currentEpoch = readLongFromFile(CURRENT_EPOCH_FILENAME);
                if (epochOfZxid > currentEpoch && updating.exists()) {
                    LOG.info("{} found. The server was terminated after " +
                             "taking a snapshot but before updating current " +
                             "epoch. Setting current epoch to {}.",
                             UPDATING_EPOCH_FILENAME, epochOfZxid);
                    setCurrentEpoch(epochOfZxid);
                    if (!updating.delete()) {
                        throw new IOException("Failed to delete " +
                                              updating.toString());
                    }
                }
            } catch(FileNotFoundException e) {
            	// pick a reasonable epoch number
            	// this should only happen once when moving to a
            	// new code version
            	currentEpoch = epochOfZxid;
            	LOG.info(CURRENT_EPOCH_FILENAME
            	        + " not found! Creating with a reasonable default of {}. This should only happen when you are upgrading your installation",
            	        currentEpoch);
            	writeLongToFile(CURRENT_EPOCH_FILENAME, currentEpoch);
            }
            if (epochOfZxid > currentEpoch) {
            	throw new IOException("The current epoch, " + ZxidUtils.zxidToString(currentEpoch) + ", is older than the last zxid, " + lastProcessedZxid);
            }
            try {
            	acceptedEpoch = readLongFromFile(ACCEPTED_EPOCH_FILENAME);
            } catch(FileNotFoundException e) {
            	// pick a reasonable epoch number
            	// this should only happen once when moving to a
            	// new code version
            	acceptedEpoch = epochOfZxid;
            	LOG.info(ACCEPTED_EPOCH_FILENAME
            	        + " not found! Creating with a reasonable default of {}. This should only happen when you are upgrading your installation",
            	        acceptedEpoch);
            	writeLongToFile(ACCEPTED_EPOCH_FILENAME, acceptedEpoch);
            }
            if (acceptedEpoch < currentEpoch) {
            	throw new IOException("The accepted epoch, " + ZxidUtils.zxidToString(acceptedEpoch) + " is less than the current epoch, " + ZxidUtils.zxidToString(currentEpoch));
            }
        } catch(IOException ie) {
            LOG.error("Unable to load database on disk", ie);
            throw new RuntimeException("Unable to run quorum server ", ie);
        }
	}

    ResponderThread responder;
    
    synchronized public void stopLeaderElection() {
        responder.running = false;
        responder.interrupt();
    }
    synchronized public void startLeaderElection() {
    	try {
    		currentVote = new Vote(myid, getLastLoggedZxid(), getCurrentEpoch());
    	} catch(IOException e) {
    		RuntimeException re = new RuntimeException(e.getMessage());
    		re.setStackTrace(e.getStackTrace());
    		throw re;
    	}
        for (QuorumServer p : getView().values()) {
            if (p.id == myid) {
                myQuorumAddr = p.addr;
                break;
            }
        }
        if (myQuorumAddr == null) {
            throw new RuntimeException("My id " + myid + " not in the peer list");
        }
        if (electionType == 0) {
            try {
                udpSocket = new DatagramSocket(myQuorumAddr.getPort());
                responder = new ResponderThread();
                responder.start();
            } catch (SocketException e) {
                throw new RuntimeException(e);
            }
        }
        this.electionAlg = createElectionAlgorithm(electionType);
    }
    
    /**
     * Count the number of nodes in the map that could be followers.
     * @param peers
     * @return The number of followers in the map
     */
    protected static int countParticipants(Map<Long,QuorumServer> peers) {
      int count = 0;
      for (QuorumServer q : peers.values()) {
          if (q.type == LearnerType.PARTICIPANT) {
              count++;
          }
      }
      return count;
    }
    
    /**
     * This constructor is only used by the existing unit test code.
     * It defaults to FileLogProvider persistence provider.
     */
    public QuorumPeer(Map<Long,QuorumServer> quorumPeers, File snapDir,
            File logDir, int clientPort, int electionAlg,
            long myid, int tickTime, int initLimit, int syncLimit)
        throws IOException
    {
        this(quorumPeers, snapDir, logDir, electionAlg,
                myid,tickTime, initLimit,syncLimit, false,
                ServerCnxnFactory.createFactory(new InetSocketAddress(clientPort), -1),
                new QuorumMaj(countParticipants(quorumPeers)));
    }
    
    /**
     * This constructor is only used by the existing unit test code.
     * It defaults to FileLogProvider persistence provider.
     */
    public QuorumPeer(Map<Long,QuorumServer> quorumPeers, File snapDir,
            File logDir, int clientPort, int electionAlg,
            long myid, int tickTime, int initLimit, int syncLimit, 
            QuorumVerifier quorumConfig)
        throws IOException
    {
        this(quorumPeers, snapDir, logDir, electionAlg,
                myid,tickTime, initLimit,syncLimit, false,
                ServerCnxnFactory.createFactory(new InetSocketAddress(clientPort), -1),
                quorumConfig);
    }
    
    /**
     * returns the highest zxid that this host has seen
     * 
     * @return the highest zxid for this host
     */
    public long getLastLoggedZxid() {
        if (!zkDb.isInitialized()) {
        	loadDataBase();
        }
        return zkDb.getDataTreeLastProcessedZxid();
    }
    
    public Follower follower;
    public Leader leader;
    public Observer observer;

    protected Follower makeFollower(FileTxnSnapLog logFactory) throws IOException {
        return new Follower(this, new FollowerZooKeeperServer(logFactory, 
                this,new ZooKeeperServer.BasicDataTreeBuilder(), this.zkDb));
    }
     
    protected Leader makeLeader(FileTxnSnapLog logFactory) throws IOException {
        return new Leader(this, new LeaderZooKeeperServer(logFactory,
                this,new ZooKeeperServer.BasicDataTreeBuilder(), this.zkDb));
    }
    
    protected Observer makeObserver(FileTxnSnapLog logFactory) throws IOException {
        return new Observer(this, new ObserverZooKeeperServer(logFactory,
                this, new ZooKeeperServer.BasicDataTreeBuilder(), this.zkDb));
    }

    protected Election createElectionAlgorithm(int electionAlgorithm){
        Election le=null;
                
        //TODO: use a factory rather than a switch
        switch (electionAlgorithm) {
        case 0:
            le = new LeaderElection(this);
            break;
        case 1:
            le = new AuthFastLeaderElection(this);
            break;
        case 2:
            le = new AuthFastLeaderElection(this, true);
            break;
        case 3:
            qcm = createCnxnManager();
            QuorumCnxManager.Listener listener = qcm.listener;
            if(listener != null){
                listener.start();
                le = new FastLeaderElection(this, qcm);
            } else {
                LOG.error("Null listener when initializing cnx manager");
            }
            break;
        default:
            assert false;
        }
        return le;
    }

    protected Election makeLEStrategy(){
        LOG.debug("Initializing leader election protocol...");
        if (getElectionType() == 0) {
            electionAlg = new LeaderElection(this);
        }        
        return electionAlg;
    }

    synchronized protected void setLeader(Leader newLeader){
        leader=newLeader;
    }

    synchronized protected void setFollower(Follower newFollower){
        follower=newFollower;
    }
    
    synchronized protected void setObserver(Observer newObserver){
        observer=newObserver;
    }

    synchronized public ZooKeeperServer getActiveServer(){
        if(leader!=null)
            return leader.zk;
        else if(follower!=null)
            return follower.zk;
        else if (observer != null)
            return observer.zk;
        return null;
    }

    @Override
    public void run() {
        setName("QuorumPeer" + "[myid=" + getId() + "]" +
                cnxnFactory.getLocalAddress());

        LOG.debug("Starting quorum peer");
        try {
            jmxQuorumBean = new QuorumBean(this);
            MBeanRegistry.getInstance().register(jmxQuorumBean, null);
            for(QuorumServer s: getView().values()){
                ZKMBeanInfo p;
                if (getId() == s.id) {
                    p = jmxLocalPeerBean = new LocalPeerBean(this);
                    try {
                        MBeanRegistry.getInstance().register(p, jmxQuorumBean);
                    } catch (Exception e) {
                        LOG.warn("Failed to register with JMX", e);
                        jmxLocalPeerBean = null;
                    }
                } else {
                    p = new RemotePeerBean(s);
                    try {
                        MBeanRegistry.getInstance().register(p, jmxQuorumBean);
                    } catch (Exception e) {
                        LOG.warn("Failed to register with JMX", e);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            jmxQuorumBean = null;
        }

        try {
            /*
             * Main loop
             */
            while (running) {
                switch (getPeerState()) {
                case LOOKING:
                    LOG.info("LOOKING");

                    if (Boolean.getBoolean("readonlymode.enabled")) {
                        LOG.info("Attempting to start ReadOnlyZooKeeperServer");

                        // Create read-only server but don't start it immediately
                        final ReadOnlyZooKeeperServer roZk = new ReadOnlyZooKeeperServer(
                                logFactory, this,
                                new ZooKeeperServer.BasicDataTreeBuilder(),
                                this.zkDb);
    
                        // Instead of starting roZk immediately, wait some grace
                        // period before we decide we're partitioned.
                        //
                        // Thread is used here because otherwise it would require
                        // changes in each of election strategy classes which is
                        // unnecessary code coupling.
                        Thread roZkMgr = new Thread() {
                            public void run() {
                                try {
                                    // lower-bound grace period to 2 secs
                                    sleep(Math.max(2000, tickTime));
                                    if (ServerState.LOOKING.equals(getPeerState())) {
                                        roZk.startup();
                                    }
                                } catch (InterruptedException e) {
                                    LOG.info("Interrupted while attempting to start ReadOnlyZooKeeperServer, not started");
                                } catch (Exception e) {
                                    LOG.error("FAILED to start ReadOnlyZooKeeperServer", e);
                                }
                            }
                        };
                        try {
                            roZkMgr.start();
                            setBCVote(null);
                            setCurrentVote(makeLEStrategy().lookForLeader());
                        } catch (Exception e) {
                            LOG.warn("Unexpected exception",e);
                            setPeerState(ServerState.LOOKING);
                        } finally {
                            // If the thread is in the the grace period, interrupt
                            // to come out of waiting.
                            roZkMgr.interrupt();
                            roZk.shutdown();
                        }
                    } else {
                        try {
                            setBCVote(null);
                            setCurrentVote(makeLEStrategy().lookForLeader());
                        } catch (Exception e) {
                            LOG.warn("Unexpected exception", e);
                            setPeerState(ServerState.LOOKING);
                        }
                    }
                    break;
                case OBSERVING:
                    try {
                        LOG.info("OBSERVING");
                        setObserver(makeObserver(logFactory));
                        observer.observeLeader();
                    } catch (Exception e) {
                        LOG.warn("Unexpected exception",e );                        
                    } finally {
                        observer.shutdown();
                        setObserver(null);
                        setPeerState(ServerState.LOOKING);
                    }
                    break;
                case FOLLOWING:
                    try {
                        LOG.info("FOLLOWING");
                        setFollower(makeFollower(logFactory));
                        follower.followLeader();
                    } catch (Exception e) {
                        LOG.warn("Unexpected exception",e);
                    } finally {
                        follower.shutdown();
                        setFollower(null);
                        setPeerState(ServerState.LOOKING);
                    }
                    break;
                case LEADING:
                    LOG.info("LEADING");
                    try {
                        setLeader(makeLeader(logFactory));
                        leader.lead();
                        setLeader(null);
                    } catch (Exception e) {
                        LOG.warn("Unexpected exception",e);
                    } finally {
                        if (leader != null) {
                            leader.shutdown("Forcing shutdown");
                            setLeader(null);
                        }
                        setPeerState(ServerState.LOOKING);
                    }
                    break;
                }
            }
        } finally {
            LOG.warn("QuorumPeer main thread exited");
            try {
                MBeanRegistry.getInstance().unregisterAll();
            } catch (Exception e) {
                LOG.warn("Failed to unregister with JMX", e);
            }
            jmxQuorumBean = null;
            jmxLocalPeerBean = null;
        }
    }

    public void shutdown() {
        running = false;
        if (leader != null) {
            leader.shutdown("quorum Peer shutdown");
        }
        if (follower != null) {
            follower.shutdown();
        }
        cnxnFactory.shutdown();
        if(udpSocket != null) {
            udpSocket.close();
        }
        
        if(getElectionAlg() != null){
            this.interrupt();
        	getElectionAlg().shutdown();
        }
        try {
            zkDb.close();
        } catch (IOException ie) {
            LOG.warn("Error closing logs ", ie);
        }
    }

    /**
     * A 'view' is a node's current opinion of the membership of the entire
     * ensemble.
     */
    public Map<Long,QuorumPeer.QuorumServer> getView() {
        return Collections.unmodifiableMap(this.quorumPeers);
    }

    /**
     * Observers are not contained in this view, only nodes with 
     * PeerType=PARTICIPANT.
     */
    public Map<Long,QuorumPeer.QuorumServer> getVotingView() {
        return QuorumPeer.viewToVotingView(getView());
    }

    static Map<Long,QuorumPeer.QuorumServer> viewToVotingView(
            Map<Long,QuorumPeer.QuorumServer> view) {
        Map<Long,QuorumPeer.QuorumServer> ret =
            new HashMap<Long, QuorumPeer.QuorumServer>();
        for (QuorumServer server : view.values()) {
            if (server.type == LearnerType.PARTICIPANT) {
                ret.put(server.id, server);
            }
        }
        return ret;
    }

    /**
     * Returns only observers, no followers.
     */
    public Map<Long,QuorumPeer.QuorumServer> getObservingView() {
        Map<Long,QuorumPeer.QuorumServer> ret = 
            new HashMap<Long, QuorumPeer.QuorumServer>();
        Map<Long,QuorumPeer.QuorumServer> view = getView();
        for (QuorumServer server : view.values()) {            
            if (server.type == LearnerType.OBSERVER) {
                ret.put(server.id, server);
            }
        }        
        return ret;
    }
    
    /**
     * Check if a node is in the current view. With static membership, the
     * result of this check will never change; only when dynamic membership
     * is introduced will this be more useful.
     */
    public boolean viewContains(Long sid) {
        return this.quorumPeers.containsKey(sid);
    }
    
    /**
     * Only used by QuorumStats at the moment
     */
    public String[] getQuorumPeers() {
        List<String> l = new ArrayList<String>();
        synchronized (this) {
            if (leader != null) {
                for (LearnerHandler fh : leader.getLearners()) {
                    if (fh.getSocket() != null) {
                        String s = fh.getSocket().getRemoteSocketAddress().toString();
                        if (leader.isLearnerSynced(fh))
                            s += "*";
                        l.add(s);
                    }
                }
            } else if (follower != null) {
                l.add(follower.sock.getRemoteSocketAddress().toString());
            }
        }
        return l.toArray(new String[0]);
    }

    public String getServerState() {
        switch (getPeerState()) {
        case LOOKING:
            return QuorumStats.Provider.LOOKING_STATE;
        case LEADING:
            return QuorumStats.Provider.LEADING_STATE;
        case FOLLOWING:
            return QuorumStats.Provider.FOLLOWING_STATE;
        case OBSERVING:
            return QuorumStats.Provider.OBSERVING_STATE;
        }
        return QuorumStats.Provider.UNKNOWN_STATE;
    }


    /**
     * get the id of this quorum peer.
     */
    public long getMyid() {
        return myid;
    }

    /**
     * set the id of this quorum peer.
     */
    public void setMyid(long myid) {
        this.myid = myid;
    }

    /**
     * Get the number of milliseconds of each tick
     */
    public int getTickTime() {
        return tickTime;
    }

    /**
     * Set the number of milliseconds of each tick
     */
    public void setTickTime(int tickTime) {
        LOG.info("tickTime set to " + tickTime);
        this.tickTime = tickTime;
    }

    /** Maximum number of connections allowed from particular host (ip) */
    public int getMaxClientCnxnsPerHost() {
        ServerCnxnFactory fac = getCnxnFactory();
        if (fac == null) {
            return -1;
        }
        return fac.getMaxClientCnxnsPerHost();
    }
    
    /** minimum session timeout in milliseconds */
    public int getMinSessionTimeout() {
        return minSessionTimeout == -1 ? tickTime * 2 : minSessionTimeout;
    }

    /** minimum session timeout in milliseconds */
    public void setMinSessionTimeout(int min) {
        LOG.info("minSessionTimeout set to " + min);
        this.minSessionTimeout = min;
    }

    /** maximum session timeout in milliseconds */
    public int getMaxSessionTimeout() {
        return maxSessionTimeout == -1 ? tickTime * 20 : maxSessionTimeout;
    }

    /** minimum session timeout in milliseconds */
    public void setMaxSessionTimeout(int max) {
        LOG.info("maxSessionTimeout set to " + max);
        this.maxSessionTimeout = max;
    }

    /**
     * Get the number of ticks that the initial synchronization phase can take
     */
    public int getInitLimit() {
        return initLimit;
    }

    /**
     * Set the number of ticks that the initial synchronization phase can take
     */
    public void setInitLimit(int initLimit) {
        LOG.info("initLimit set to " + initLimit);
        this.initLimit = initLimit;
    }

    /**
     * Get the current tick
     */
    public int getTick() {
        return tick.get();
    }
    
    /**
     * Return QuorumVerifier object
     */
    
    public QuorumVerifier getQuorumVerifier(){
        return quorumConfig;
        
    }
    
    public void setQuorumVerifier(QuorumVerifier quorumConfig){
       this.quorumConfig = quorumConfig;
    }
    
    /**
     * Get an instance of LeaderElection
     */
        
    public Election getElectionAlg(){
        return electionAlg;
    }
        
    /**
     * Get the synclimit
     */
    public int getSyncLimit() {
        return syncLimit;
    }

    /**
     * Set the synclimit
     */
    public void setSyncLimit(int syncLimit) {
        this.syncLimit = syncLimit;
    }
    
    
    /**
     * The syncEnabled can also be set via a system property.
     */
    public static final String SYNC_ENABLED = "zookeeper.observer.syncEnabled";
    
    /**
     * Return syncEnabled.
     * 
     * @return
     */
    public boolean getSyncEnabled() {
        if (System.getProperty(SYNC_ENABLED) != null) {
            LOG.info(SYNC_ENABLED + "=" + Boolean.getBoolean(SYNC_ENABLED));   
            return Boolean.getBoolean(SYNC_ENABLED);
        } else {        
            return syncEnabled;
        }
    }
    
    /**
     * Set syncEnabled.
     * 
     * @param syncEnabled
     */
    public void setSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    /**
     * Gets the election type
     */
    public int getElectionType() {
        return electionType;
    }

    /**
     * Sets the election type
     */
    public void setElectionType(int electionType) {
        this.electionType = electionType;
    }

    public boolean getQuorumListenOnAllIPs() {
        return quorumListenOnAllIPs;
    }

    public void setQuorumListenOnAllIPs(boolean quorumListenOnAllIPs) {
        this.quorumListenOnAllIPs = quorumListenOnAllIPs;
    }

    public ServerCnxnFactory getCnxnFactory() {
        return cnxnFactory;
    }

    public void setCnxnFactory(ServerCnxnFactory cnxnFactory) {
        this.cnxnFactory = cnxnFactory;
    }

    public void setQuorumPeers(Map<Long,QuorumServer> quorumPeers) {
        this.quorumPeers = quorumPeers;
    }

    public int getClientPort() {
        return cnxnFactory.getLocalPort();
    }

    public void setClientPortAddress(InetSocketAddress addr) {
    }
 
    public void setTxnFactory(FileTxnSnapLog factory) {
        this.logFactory = factory;
    }
    
    public FileTxnSnapLog getTxnFactory() {
        return this.logFactory;
    }

    /**
     * set zk database for this node
     * @param database
     */
    public void setZKDatabase(ZKDatabase database) {
        this.zkDb = database;
    }

    protected ZKDatabase getZkDb() {
        return zkDb;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * get reference to QuorumCnxManager
     */
    public QuorumCnxManager getQuorumCnxManager() {
        return qcm;
    }
    private long readLongFromFile(String name) throws IOException {
    	File file = new File(logFactory.getSnapDir(), name);
		BufferedReader br = new BufferedReader(new FileReader(file));
		String line = "";
		try {
			line = br.readLine();
    		return Long.parseLong(line);
    	} catch(NumberFormatException e) {
    		throw new IOException("Found " + line + " in " + file);
    	} finally {
    		br.close();
    	}
    }

    private long acceptedEpoch = -1;
    private long currentEpoch = -1;

	public static final String CURRENT_EPOCH_FILENAME = "currentEpoch";

	public static final String ACCEPTED_EPOCH_FILENAME = "acceptedEpoch";

    public static final String UPDATING_EPOCH_FILENAME = "updatingEpoch";

	/**
	 * Write a long value to disk atomically. Either succeeds or an exception
	 * is thrown.
	 * @param name file name to write the long to
	 * @param value the long value to write to the named file
	 * @throws IOException if the file cannot be written atomically
	 */
    private void writeLongToFile(String name, long value) throws IOException {
        File file = new File(logFactory.getSnapDir(), name);
        AtomicFileOutputStream out = new AtomicFileOutputStream(file);
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(out));
        boolean aborted = false;
        try {
            bw.write(Long.toString(value));
            bw.flush();
            
            out.flush();
        } catch (IOException e) {
            LOG.error("Failed to write new file " + file, e);
            // worst case here the tmp file/resources(fd) are not cleaned up
            //   and the caller will be notified (IOException)
            aborted = true;
            out.abort();
            throw e;
        } finally {
            if (!aborted) {
                // if the close operation (rename) fails we'll get notified.
                // worst case the tmp file may still exist
                out.close();
            }
        }
    }

    public long getCurrentEpoch() throws IOException {
		if (currentEpoch == -1) {
			currentEpoch = readLongFromFile(CURRENT_EPOCH_FILENAME);
		}
		return currentEpoch;
	}
	
	public long getAcceptedEpoch() throws IOException {
		if (acceptedEpoch == -1) {
			acceptedEpoch = readLongFromFile(ACCEPTED_EPOCH_FILENAME);
		}
		return acceptedEpoch;
	}
	
	public void setCurrentEpoch(long e) throws IOException {
		currentEpoch = e;
		writeLongToFile(CURRENT_EPOCH_FILENAME, e);
		
	}
	
	public void setAcceptedEpoch(long e) throws IOException {
		acceptedEpoch = e;
		writeLongToFile(ACCEPTED_EPOCH_FILENAME, e);
	}

    /**
     * Updates leader election info to avoid inconsistencies when
     * a new server tries to join the ensemble.
     * See ZOOKEEPER-1732 for more info.
     */
    protected void updateElectionVote(long newEpoch) {
        Vote currentVote = getCurrentVote();
        setBCVote(currentVote);
        if (currentVote != null) {
            setCurrentVote(new Vote(currentVote.getId(),
                currentVote.getZxid(),
                currentVote.getElectionEpoch(),
                newEpoch,
                currentVote.getState()));
        }
    }

    void setQuorumServerSaslRequired(boolean serverSaslRequired) {
        quorumServerSaslAuthRequired = serverSaslRequired;
        LOG.info("{} set to {}", QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED,
                serverSaslRequired);
    }

    void setQuorumLearnerSaslRequired(boolean learnerSaslRequired) {
        quorumLearnerSaslAuthRequired = learnerSaslRequired;
        LOG.info("{} set to {}", QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED,
                learnerSaslRequired);
    }

    void setQuorumSaslEnabled(boolean enableAuth) {
        quorumSaslEnableAuth = enableAuth;
        if (!quorumSaslEnableAuth) {
            LOG.info("QuorumPeer communication is not secured!");
        } else {
            LOG.info("{} set to {}",
                    QuorumAuth.QUORUM_SASL_AUTH_ENABLED, enableAuth);
        }
    }

    void setQuorumServicePrincipal(String servicePrincipal) {
        quorumServicePrincipal = servicePrincipal;
        LOG.info("{} set to {}",QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL,
                quorumServicePrincipal);
    }

    void setQuorumLearnerLoginContext(String learnerContext) {
        quorumLearnerLoginContext = learnerContext;
        LOG.info("{} set to {}", QuorumAuth.QUORUM_LEARNER_SASL_LOGIN_CONTEXT,
                quorumLearnerLoginContext);
    }

    void setQuorumServerLoginContext(String serverContext) {
        quorumServerLoginContext = serverContext;
        LOG.info("{} set to {}", QuorumAuth.QUORUM_SERVER_SASL_LOGIN_CONTEXT,
                quorumServerLoginContext);
    }

    void setQuorumCnxnThreadsSize(int qCnxnThreadsSize) {
        if (qCnxnThreadsSize > QUORUM_CNXN_THREADS_SIZE_DEFAULT_VALUE) {
            quorumCnxnThreadsSize = qCnxnThreadsSize;
        }
        LOG.info("quorum.cnxn.threads.size set to {}", quorumCnxnThreadsSize);
    }

    boolean isQuorumSaslAuthEnabled() {
        return quorumSaslEnableAuth;
    }

    private boolean isQuorumServerSaslAuthRequired() {
        return quorumServerSaslAuthRequired;
    }

    private boolean isQuorumLearnerSaslAuthRequired() {
        return quorumLearnerSaslAuthRequired;
    }

    // VisibleForTesting. Returns true if both the quorumlearner and
    // quorumserver login has been finished. Otherwse, false.
    public boolean hasAuthInitialized(){
        return authInitialized;
    }

    public QuorumCnxManager createCnxnManager() {
        return new QuorumCnxManager(this.getId(),
                                    this.getView(),
                                    this.authServer,
                                    this.authLearner,
                                    this.tickTime * this.syncLimit,
                                    this.getQuorumListenOnAllIPs(),
                                    this.quorumCnxnThreadsSize,
                                    this.isQuorumSaslAuthEnabled());
    }

    /**
     * Sets the time taken for leader election in milliseconds.
     *
     * @param electionTimeTaken
     *            time taken for leader election
     */
    void setElectionTimeTaken(long electionTimeTaken) {
        this.electionTimeTaken = electionTimeTaken;
    }

    /**
     * @return the time taken for leader election in milliseconds.
     */
    long getElectionTimeTaken() {
        return electionTimeTaken;
    }
}
