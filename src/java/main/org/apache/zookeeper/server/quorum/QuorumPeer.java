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


import static org.apache.zookeeper.server.ServerConfig.getClientPort;
import static org.apache.zookeeper.server.ServerConfig.getDataDir;
import static org.apache.zookeeper.server.ServerConfig.getDataLogDir;
import static org.apache.zookeeper.server.quorum.QuorumPeerConfig.getElectionAlg;
import static org.apache.zookeeper.server.quorum.QuorumPeerConfig.getElectionPort;
import static org.apache.zookeeper.server.quorum.QuorumPeerConfig.getInitLimit;
import static org.apache.zookeeper.server.quorum.QuorumPeerConfig.getServerId;
import static org.apache.zookeeper.server.quorum.QuorumPeerConfig.getServers;
import static org.apache.zookeeper.server.quorum.QuorumPeerConfig.getSyncLimit;
import static org.apache.zookeeper.server.quorum.QuorumPeerConfig.getTickTime;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.InputArchive;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.txn.TxnHeader;

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
 *
 * <h2>Configuration file</h2>
 *
 * When the main() method of this class is used to start the program, the file
 * "zoo.cfg" in the current directory will be used to obtain configuration
 * information. zoo.cfg is a Properties file, so keys and values are separated
 * by equals (=) and the key/value pairs are separated by new lines. The
 * following keys are used in the configuration file:
 * <ol>
 * <li>dataDir - The directory where the zookeeper data is stored.</li>
 * <li>clientPort - The port used to communicate with clients.</li>
 * <li>tickTime - The duration of a tick in milliseconds. This is the basic
 * unit of time in zookeeper.</li>
 * <li>initLimit - The maximum number of ticks that a follower will wait to
 * initially synchronize with a leader.</li>
 * <li>syncLimit - The maximum number of ticks that a follower will wait for a
 * message (including heartbeats) from the leader.</li>
 * <li>server.<i>id</i> - This is the host:port that the server with the
 * given id will use for the quorum protocol.</li>
 * </ol>
 * In addition to the zoo.cfg file. There is a file in the data directory called
 * "myid" that contains the server id as an ASCII decimal value.
 */
public class QuorumPeer extends Thread implements QuorumStats.Provider {
    private static final Logger LOG = Logger.getLogger(QuorumPeer.class);

    /**
     * Create an instance of a quorum peer
     */
    public interface Factory{
        public QuorumPeer create(NIOServerCnxn.Factory cnxnFactory) throws IOException;
        public NIOServerCnxn.Factory createConnectionFactory() throws IOException;
    }

    public static class QuorumServer {
        public QuorumServer(long id, InetSocketAddress addr) {
            this.id = id;
            this.addr = addr;
        }

        public InetSocketAddress addr;

        public long id;
    }

    public enum ServerState {
        LOOKING, FOLLOWING, LEADING;
    }
    /**
     * The servers that make up the cluster
     */
    ArrayList<QuorumServer> quorumPeers;
    public int getQuorumSize(){
        return quorumPeers.size();
    }
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
    volatile Vote currentVote;

    volatile boolean running = true;

    /**
     * The number of milliseconds of each tick
     */
    int tickTime;

    /**
     * The number of ticks that the initial synchronization phase can take
     */
    int initLimit;

    /**
     * The number of ticks that can pass between sending a request and getting
     * an acknowledgement
     */
    int syncLimit;

    /**
     * The current tick
     */
    int tick;

    /**
     * This class simply responds to requests for the current leader of this
     * node.
     * <p>
     * The request contains just an xid generated by the requestor.
     * <p>
     * The response has the xid, the id of this server, the id of the leader,
     * and the zxid of the leader.
     *
     * @author breed
     *
     */
    class ResponderThread extends Thread {
        ResponderThread() {
            super("ResponderThread");
        }

        public void run() {
            try {
                byte b[] = new byte[36];
                ByteBuffer responseBuffer = ByteBuffer.wrap(b);
                DatagramPacket packet = new DatagramPacket(b, b.length);
                while (true) {
                    udpSocket.receive(packet);
                    if (packet.getLength() != 4) {
                        LOG.warn("Got more than just an xid! Len = "
                                + packet.getLength());
                    } else {
                        responseBuffer.clear();
                        responseBuffer.getInt(); // Skip the xid
                        responseBuffer.putLong(myid);
                        switch (state) {
                        case LOOKING:
                            responseBuffer.putLong(currentVote.id);
                            responseBuffer.putLong(currentVote.zxid);
                            break;
                        case LEADING:
                            responseBuffer.putLong(myid);
                            try {
                                responseBuffer.putLong(leader.lastProposed);
                            } catch (NullPointerException npe) {
                                // This can happen in state transitions,
                                // just ignore the request
                            }
                            break;
                        case FOLLOWING:
                            responseBuffer.putLong(currentVote.id);
                            try {
                                responseBuffer.putLong(follower.getZxid());
                            } catch (NullPointerException npe) {
                                // This can happen in state transitions,
                                // just ignore the request
                            }
                        }
                        packet.setData(b);
                        udpSocket.send(packet);
                    }
                    packet.setLength(b.length);
                }
            } catch (Exception e) {
                LOG.warn("Unexpected exception",e);
            } finally {
                LOG.warn("QuorumPeer responder thread exited");
            }
        }
    }

    private ServerState state = ServerState.LOOKING;

    public void setPeerState(ServerState newState){
        state=newState;
    }

    public ServerState getPeerState(){
        return state;
    }

    DatagramSocket udpSocket;

    private InetSocketAddress myQuorumAddr;

    public InetSocketAddress getQuorumAddress(){
        return myQuorumAddr;
    }

    /**
     * the directory where the snapshot is stored.
     */
    private File dataDir;

    /**
     * the directory where the logs are stored.
     */
    private File dataLogDir;

    Election electionAlg;

    int electionPort;

    NIOServerCnxn.Factory cnxnFactory;

    public QuorumPeer(ArrayList<QuorumServer> quorumPeers, File dataDir,
            File dataLogDir, int electionAlg, int electionPort,long myid, int tickTime,
            int initLimit, int syncLimit,NIOServerCnxn.Factory cnxnFactory) throws IOException {
        super("QuorumPeer");
        this.cnxnFactory = cnxnFactory;
        this.quorumPeers = quorumPeers;
        this.dataDir = dataDir;
        this.electionPort = electionPort;
        this.dataLogDir = dataLogDir;
        this.myid = myid;
        this.tickTime = tickTime;
        this.initLimit = initLimit;
        this.syncLimit = syncLimit;
        currentVote = new Vote(myid, getLastLoggedZxid());
        for (QuorumServer p : quorumPeers) {
            if (p.id == myid) {
                myQuorumAddr = p.addr;
                break;
            }
        }
        if (myQuorumAddr == null) {
            throw new SocketException("My id " + myid + " not in the peer list");
        }
        if (electionAlg == 0) {
            udpSocket = new DatagramSocket(myQuorumAddr.getPort());
            new ResponderThread().start();
        }
        this.electionAlg = createElectionAlgorithm(electionAlg);
        QuorumStats.getInstance().setStatsProvider(this);
    }

    /**
     * This constructor is only used by the existing unit test code.
     */
    public QuorumPeer(ArrayList<QuorumServer> quorumPeers, File dataDir,
            File dataLogDir, int clientPort, int electionAlg, int electionPort,
            long myid, int tickTime, int initLimit, int syncLimit) throws IOException {
        this(quorumPeers,dataDir,dataLogDir,electionAlg,electionPort,myid,tickTime,
                initLimit,syncLimit,new NIOServerCnxn.Factory(clientPort));
    }
    /**
     *  The constructor uses the quorum peer config to instantiate the class
     */
    public QuorumPeer(NIOServerCnxn.Factory cnxnFactory) throws IOException {
        this(getServers(), new File(getDataDir()), new File(getDataLogDir()),
                getElectionAlg(), getElectionPort(),getServerId(),getTickTime(),
                getInitLimit(), getSyncLimit(),cnxnFactory);
    }

    public Follower follower;
    public Leader leader;

    protected Follower makeFollower(File dataDir,File dataLogDir) throws IOException {
        return new Follower(this, new FollowerZooKeeperServer(dataDir,
                dataLogDir, this,new ZooKeeperServer.BasicDataTreeBuilder()));
    }

    protected Leader makeLeader(File dataDir,File dataLogDir) throws IOException {
        return new Leader(this, new LeaderZooKeeperServer(dataDir, dataLogDir,
                this,new ZooKeeperServer.BasicDataTreeBuilder()));
    }

    private Election createElectionAlgorithm(int electionAlgorithm){
        Election le=null;
        //TODO: use a factory rather than a switch
        switch (electionAlgorithm) {
        case 0:
            // will create a new instance for each run of the protocol
            break;
        case 1:
            le = new AuthFastLeaderElection(this, this.electionPort);
            break;
        case 2:
            le = new AuthFastLeaderElection(this, this.electionPort, true);
            break;
        case 3:
            le = new FastLeaderElection(this,
                        new QuorumCnxManager(this.electionPort));
        default:
            assert false;
        }
        return le;
    }

    protected Election makeLEStrategy(){
        if(electionAlg==null)
            return new LeaderElection(this);
        return electionAlg;
    }

    synchronized protected void setLeader(Leader newLeader){
        leader=newLeader;
    }

    synchronized protected void setFollower(Follower newFollower){
        follower=newFollower;
    }

    synchronized public ZooKeeperServer getActiveServer(){
        if(leader!=null)
            return leader.zk;
        else if(follower!=null)
            return follower.zk;
        return null;
    }

    public void run() {
        /*
         * Main loop
         */
        while (running) {
            switch (state) {
            case LOOKING:
                try {
                    LOG.info("LOOKING");
                    currentVote = makeLEStrategy().lookForLeader();
                } catch (Exception e) {
                    LOG.warn("Unexpected exception",e);
                    state = ServerState.LOOKING;
                }
                break;
            case FOLLOWING:
                try {
                    LOG.info("FOLLOWING");
                    setFollower(makeFollower(dataDir,dataLogDir));
                    follower.followLeader();
                } catch (Exception e) {
                    LOG.warn("Unexpected exception",e);
                } finally {
                    follower.shutdown();
                    setFollower(null);
                    state = ServerState.LOOKING;
                }
                break;
            case LEADING:
                LOG.info("LEADING");
                try {
                    setLeader(makeLeader(dataDir,dataLogDir));
                    leader.lead();
                    setLeader(null);
                } catch (Exception e) {
                    LOG.warn("Unexpected exception",e);
                } finally {
                    if (leader != null) {
                        leader.shutdown("Forcing shutdown");
                        setLeader(null);
                    }
                    state = ServerState.LOOKING;
                }
                break;
            }
        }
        LOG.warn("QuorumPeer main thread exited");
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
        udpSocket.close();
    }

    long getLastLoggedZxid() {
        File[] list = dataLogDir.listFiles();
        if (list == null) {
            return 0;
        }
        long maxLog = -1;
        long maxSnapShot = 0;
        for (File f : list) {
            String name = f.getName();
            if (name.startsWith("log.")) {
                long zxid = ZooKeeperServer.getZxidFromName(f.getName(), "log");
                if (zxid > maxLog) {
                    maxLog = zxid;
                }
            } else if (name.startsWith("snapshot.")) {
                long zxid = ZooKeeperServer.getZxidFromName(f.getName(),
                        "snapshot");
                if (zxid > maxLog) {
                    maxSnapShot = zxid;
                }
            }
        }
        if (maxSnapShot > maxLog) {
            return maxSnapShot;
        }
        long zxid = maxLog;
        FileInputStream logStream = null;
        try {
            logStream = new FileInputStream(new File(dataLogDir, "log."
                    + Long.toHexString(maxLog)));
            BinaryInputArchive ia = BinaryInputArchive.getArchive(logStream);
            while (true) {
                byte[] bytes = ia.readBuffer("txnEntry");
                if (bytes.length == 0) {
                    // Since we preallocate, we define EOF to be an
                    // empty transaction
                    break;
                }
                int B = ia.readByte("EOR");
                if (B != 'B') {
                    break;
                }
                InputArchive bia = BinaryInputArchive
                        .getArchive(new ByteArrayInputStream(bytes));
                TxnHeader hdr = new TxnHeader();
                hdr.deserialize(bia, "hdr");
                zxid = hdr.getZxid();
            }
        } catch (IOException e) {
            LOG.warn("Unexpected exception", e);
        } finally {
            try {
                if (logStream != null) {
                    logStream.close();
                }
            } catch (IOException e) {
                LOG.warn("Unexpected exception",e);
            }
        }
        return zxid;
    }

    public static void runPeer(QuorumPeer.Factory qpFactory) {
        try {
            QuorumStats.registerAsConcrete();
            QuorumPeer self = qpFactory.create(qpFactory.createConnectionFactory());
            self.start();
            self.join();
        } catch (Exception e) {
            LOG.fatal("Unexpected exception",e);
        }
        System.exit(2);
    }

    public String[] getQuorumPeers() {
        List<String> l = new ArrayList<String>();
        synchronized (this) {
            if (leader != null) {
                synchronized (leader.followers) {
                    for (FollowerHandler fh : leader.followers) {
                        if (fh.s == null)
                            continue;
                        String s = fh.s.getRemoteSocketAddress().toString();
                        if (leader.isFollowerSynced(fh))
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
        switch (state) {
        case LOOKING:
            return QuorumStats.Provider.LOOKING_STATE;
        case LEADING:
            return QuorumStats.Provider.LEADING_STATE;
        case FOLLOWING:
            return QuorumStats.Provider.FOLLOWING_STATE;
        }
        return QuorumStats.Provider.UNKNOWN_STATE;
    }

    public static void main(String args[]) {
        if (args.length == 2) {
            ZooKeeperServer.main(args);
            return;
        }
        QuorumPeerConfig.parse(args);

        if (!QuorumPeerConfig.isStandalone()) {
            runPeer(new QuorumPeer.Factory() {
                public QuorumPeer create(NIOServerCnxn.Factory cnxnFactory)
                        throws IOException {
                    return new QuorumPeer(cnxnFactory);
                }
                public NIOServerCnxn.Factory createConnectionFactory()
                        throws IOException {
                    return new NIOServerCnxn.Factory(getClientPort());
                }
            });
        }else{
            // there is only server in the quorum -- run as standalone
            ZooKeeperServer.main(args);
        }
    }
}
