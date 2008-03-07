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

package com.yahoo.zookeeper.server.quorum;


import static com.yahoo.zookeeper.server.quorum.QuorumPeerConfig.*;

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

import com.yahoo.jute.BinaryInputArchive;
import com.yahoo.jute.InputArchive;
import com.yahoo.zookeeper.server.NIOServerCnxn;
import com.yahoo.zookeeper.server.ZooKeeperServer;
import com.yahoo.zookeeper.server.ZooLog;
import com.yahoo.zookeeper.server.quorum.Vote;
import com.yahoo.zookeeper.server.quorum.FastLeaderElection;
import com.yahoo.zookeeper.server.quorum.QuorumCnxManager;
import com.yahoo.zookeeper.txn.TxnHeader;

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
public class QuorumPeer extends Thread {
	/**
	 * Create an instance of a quorum peer 
	 */
	public interface Factory{
		public QuorumPeer create() throws IOException;
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

    boolean running = true;

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
                        ZooLog.logError("Got more than just an xid! Len = "
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
                ZooLog.logException(e);
            } finally {
                ZooLog.logError("QuorumPeer responder thread exited");
            }
        }
    }

    public ServerState state = ServerState.LOOKING;

    DatagramSocket udpSocket;

    InetSocketAddress myQuorumAddr;

    /**
     * the directory where the snapshot is stored.
     */
    private File dataDir;

    /**
     * the directory where the logs are stored.
     */
    private File dataLogDir;

    int clientPort;

    int electionAlg;
    
    int electionPort;

    NIOServerCnxn.Factory cnxnFactory;

    public QuorumPeer(ArrayList<QuorumServer> quorumPeers, File dataDir,
            File dataLogDir, int clientPort, int electionAlg, int electionPort,
            long myid, int tickTime, int initLimit, int syncLimit) throws IOException {
        super("QuorumPeer");
        this.clientPort = clientPort;
        this.cnxnFactory = new NIOServerCnxn.Factory(clientPort, this);
        this.quorumPeers = quorumPeers;
        this.dataDir = dataDir;
        this.electionAlg = electionAlg;
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
    }

    public QuorumPeer() throws IOException {
    	// use quorum peer config to instantiate the class 
		this(getServers(), new File(getDataDir()), new File(getDataLogDir()),
				getClientPort(), getElectionAlg(), getElectionPort(),
				getServerId(), getTickTime(), getInitLimit(), getSyncLimit());
	}
    public Follower follower;

    public Leader leader;

    protected Follower makeFollower() throws IOException {
		return new Follower(this, new FollowerZooKeeperServer(dataDir,
				dataLogDir, this));
	}

	protected Leader makeLeader() throws IOException {
		return new Leader(this, new LeaderZooKeeperServer(dataDir, dataLogDir,
				this));
	}
    
    public void run() {

        /*
         * Main loop
         */
        Election le = null;
        switch(electionAlg){
        case 1:
            le = new AuthFastLeaderElection(this, this.electionPort);
            break;
        case 2:
            le = new AuthFastLeaderElection(this, this.electionPort, true);                break;
        case 3:
            le =
                new FastLeaderElection(this,
                        new QuorumCnxManager(this.electionPort));
        }

        while (running) {
            switch (state) {
            case LOOKING:
                try {
                    ZooLog.logWarn("LOOKING");
                    long init, end, diff;
                    switch (electionAlg) {
                    // Legacy algorithm
                    case 0:
                       init = System.currentTimeMillis();
                        currentVote = new LeaderElection(this).lookForLeader();
                        end = System.currentTimeMillis();
                        diff = end - init;
                        ZooLog.logWarn("Leader election latency: " + diff + " " + currentVote.id);
                        break;
                    // All other algorithms
                    default:
                        init = System.currentTimeMillis();
                        if(le != null) currentVote = le.lookForLeader();
                        end = System.currentTimeMillis();
                        diff = end - init;
                        ZooLog.logWarn("Leader election latency: " + diff);
                        break;
                    } 
                } catch (Exception e) {
                    ZooLog.logException(e);
                    state = ServerState.LOOKING;
                }
                break;            
            case FOLLOWING:
                try {
                    ZooLog.logWarn("FOLLOWING");
                    follower = makeFollower();
                    follower.followLeader();
                } catch (Exception e) {
                    ZooLog.logException(e);
                } finally {
                    follower.shutdown();
                    follower = null;
                    state = ServerState.LOOKING;
                }
                break;
            case LEADING:
                ZooLog.logWarn("LEADING");
                try {
                    leader = makeLeader();
                    leader.lead();
                    leader = null;
                } catch (Exception e) {
                    ZooLog.logException(e);
                } finally {
                    if (leader != null) {
                        leader.shutdown("Forcing shutdown");
                    }
                    state = ServerState.LOOKING;
                }
                break;
            }
        }
        ZooLog.logError("QuorumPeer main thread exited");
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
            ZooLog.logWarn(e.toString());
        } finally {
            try {
                if (logStream != null) {
                    logStream.close();
                }
            } catch (IOException e) {
                ZooLog.logException(e);
            }
        }
        return zxid;
    }

    public static void runPeer(QuorumPeer.Factory qpFactory) {
		try {
			QuorumPeer self = qpFactory.create();
			self.start();
			self.join();
		} catch (Exception e) {
			ZooLog.logException(e);
		}
		System.exit(2);
	}
    
    public static void main(String args[]) {
		if (args.length == 2) {
			ZooKeeperServer.main(args);
			return;
		}
		QuorumPeerConfig.parse(args);
		if (!QuorumPeerConfig.isStandalone()) {
			runPeer(new QuorumPeer.Factory() {
				public QuorumPeer create() throws IOException {
					return new QuorumPeer();
				}
			});
		}else{
			// there is only server in the quorum -- run as standalone
			ZooKeeperServer.main(args);
		}
	}
}
