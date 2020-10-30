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

package org.apache.zookeeper.server.quorum;

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used by Followers to host Observers. This reduces the network load on the Leader process by pushing
 * the responsibility for keeping Observers in sync off the leading peer.
 *
 * It is expected that Observers will continue to perform the initial vetting of clients and requests.
 * Observers send the request to the follower where it is received by an ObserverMaster.
 *
 * The ObserverMaster forwards a copy of the request to the ensemble Leader and inserts it into its own
 * request processor pipeline where it can be matched with the response comes back. All commits received
 * from the Leader will be forwarded along to every Learner connected to the ObserverMaster.
 *
 * New Learners connecting to a Follower will receive a LearnerHandler object and be party to its syncing logic
 * to be brought up to date.
 *
 * The logic is quite a bit simpler than the corresponding logic in Leader because it only hosts observers.
 */
public class ObserverMaster extends LearnerMaster implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(ObserverMaster.class);

    //Follower counter
    private final AtomicLong followerCounter = new AtomicLong(-1);

    private QuorumPeer self;
    private FollowerZooKeeperServer zks;
    private int port;

    private Set<LearnerHandler> activeObservers = Collections.newSetFromMap(new ConcurrentHashMap<LearnerHandler, Boolean>());

    private final ConcurrentHashMap<LearnerHandler, LearnerHandlerBean> connectionBeans = new ConcurrentHashMap<>();

    /**
     * we want to keep a log of past txns so that observers can sync up with us when we connect,
     * but we can't keep everything in memory, so this limits how much memory will be dedicated
     * to keeping recent txns.
     */
    private static final int PKTS_SIZE_LIMIT = 32 * 1024 * 1024;
    private static volatile int pktsSizeLimit = Integer.getInteger("zookeeper.observerMaster.sizeLimit", PKTS_SIZE_LIMIT);
    private ConcurrentLinkedQueue<QuorumPacket> proposedPkts = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<QuorumPacket> committedPkts = new ConcurrentLinkedQueue<>();
    private int pktsSize = 0;

    private long lastProposedZxid;

    // ensure ordering of revalidations returned to this learner
    private final Object revalidateSessionLock = new Object();

    private final ConcurrentLinkedQueue<Revalidation> pendingRevalidations = new ConcurrentLinkedQueue<>();

    static class Revalidation {

        public final long sessionId;
        public final int timeout;
        public final LearnerHandler handler;

        Revalidation(final Long sessionId, final int timeout, final LearnerHandler handler) {
            this.sessionId = sessionId;
            this.timeout = timeout;
            this.handler = handler;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final Revalidation that = (Revalidation) o;
            return sessionId == that.sessionId && timeout == that.timeout && handler.equals(that.handler);
        }

        @Override
        public int hashCode() {
            int result = (int) (sessionId ^ (sessionId >>> 32));
            result = 31 * result + timeout;
            result = 31 * result + handler.hashCode();
            return result;
        }

    }

    private Thread thread;
    private ServerSocket ss;
    private boolean listenerRunning;
    private ScheduledExecutorService pinger;

    Runnable ping = new Runnable() {
        @Override
        public void run() {
            for (LearnerHandler lh : activeObservers) {
                lh.ping();
            }
        }
    };

    ObserverMaster(QuorumPeer self, FollowerZooKeeperServer zks, int port) {
        this.self = self;
        this.zks = zks;
        this.port = port;
    }

    @Override
    public void addLearnerHandler(LearnerHandler learnerHandler) {
        if (!listenerRunning) {
            throw new RuntimeException(("ObserverMaster is not running"));
        }
    }

    @Override
    public void removeLearnerHandler(LearnerHandler learnerHandler) {
        activeObservers.remove(learnerHandler);
    }

    @Override
    public int syncTimeout() {
        return self.getSyncLimit() * self.getTickTime();
    }

    @Override
    public int getTickOfNextAckDeadline() {
        return self.tick.get() + self.syncLimit;
    }

    @Override
    public int getTickOfInitialAckDeadline() {
        return self.tick.get() + self.initLimit + self.syncLimit;
    }

    @Override
    public long getAndDecrementFollowerCounter() {
        return followerCounter.getAndDecrement();
    }

    @Override
    public void waitForEpochAck(long sid, StateSummary ss) throws IOException, InterruptedException {
        // since this is done by an active follower, we don't need to wait for anything
    }

    @Override
    public void waitForStartup() throws InterruptedException {
        // since this is done by an active follower, we don't need to wait for anything
    }

    @Override
    public synchronized long getLastProposed() {
        return lastProposedZxid;
    }

    @Override
    public long getEpochToPropose(long sid, long lastAcceptedEpoch) throws InterruptedException, IOException {
        return self.getCurrentEpoch();
    }

    @Override
    public ZKDatabase getZKDatabase() {
        return zks.getZKDatabase();
    }

    @Override
    public void waitForNewLeaderAck(long sid, long zxid) throws InterruptedException {
        // no need to wait since we are a follower
    }

    @Override
    public int getCurrentTick() {
        return self.tick.get();
    }

    @Override
    public void processAck(long sid, long zxid, SocketAddress localSocketAddress) {
        if ((zxid & 0xffffffffL) == 0) {
            /*
             * We no longer process NEWLEADER ack by this method. However,
             * the learner sends ack back to the leader after it gets UPTODATE
             * so we just ignore the message.
             */
            return;
        }

        throw new RuntimeException("Observers shouldn't send ACKS ack = " + Long.toHexString(zxid));
    }

    @Override
    public void touch(long sess, int to) {
        zks.getSessionTracker().touchSession(sess, to);
    }

    boolean revalidateLearnerSession(QuorumPacket qp) throws IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(qp.getData());
        DataInputStream dis = new DataInputStream(bis);
        long id = dis.readLong();
        boolean valid = dis.readBoolean();
        Iterator<Revalidation> itr = pendingRevalidations.iterator();
        if (!itr.hasNext()) {
            // not a learner session, handle locally
            return false;
        }
        Revalidation revalidation = itr.next();
        if (revalidation.sessionId != id) {
            // not a learner session, handle locally
            return false;
        }
        itr.remove();
        LearnerHandler learnerHandler = revalidation.handler;
        // create a copy here as the qp object is reused by the Follower and may be mutated
        QuorumPacket deepCopy = new QuorumPacket(
            qp.getType(),
            qp.getZxid(),
            Arrays.copyOf(qp.getData(), qp.getData().length),
            qp.getAuthinfo() == null ? null : new ArrayList<>(qp.getAuthinfo()));
        learnerHandler.queuePacket(deepCopy);
        // To keep consistent as leader, touch the session when it's
        // revalidating the session, only update if it's a valid session.
        if (valid) {
            touch(revalidation.sessionId, revalidation.timeout);
        }
        return true;
    }

    @Override
    public void revalidateSession(QuorumPacket qp, LearnerHandler learnerHandler) throws IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(qp.getData());
        DataInputStream dis = new DataInputStream(bis);
        long id = dis.readLong();
        int to = dis.readInt();
        synchronized (revalidateSessionLock) {
            pendingRevalidations.add(new Revalidation(id, to, learnerHandler));
            Learner learner = zks.getLearner();
            if (learner != null) {
                learner.writePacket(qp, true);
            }
        }
    }

    @Override
    public void submitLearnerRequest(Request si) {
        zks.processObserverRequest(si);
    }

    @Override
    public synchronized long startForwarding(LearnerHandler learnerHandler, long lastSeenZxid) {
        Iterator<QuorumPacket> itr = committedPkts.iterator();
        if (itr.hasNext()) {
            QuorumPacket packet = itr.next();
            if (packet.getZxid() > lastSeenZxid + 1) {
                LOG.error(
                    "LearnerHandler is too far behind (0x{} < 0x{}), disconnecting {} at {}",
                    Long.toHexString(lastSeenZxid + 1),
                    Long.toHexString(packet.getZxid()),
                    learnerHandler.getSid(),
                    learnerHandler.getRemoteAddress());
                learnerHandler.shutdown();
                return -1;
            } else if (packet.getZxid() == lastSeenZxid + 1) {
                learnerHandler.queuePacket(packet);
            }
            long queueHeadZxid = packet.getZxid();
            long queueBytesUsed = LearnerHandler.packetSize(packet);
            while (itr.hasNext()) {
                packet = itr.next();
                if (packet.getZxid() <= lastSeenZxid) {
                    continue;
                }
                learnerHandler.queuePacket(packet);
                queueBytesUsed += LearnerHandler.packetSize(packet);
            }
            LOG.info(
                "finished syncing observer from retained commit queue: sid {}, "
                    + "queue head 0x{}, queue tail 0x{}, sync position 0x{}, num packets used {}, "
                    + "num bytes used {}",
                learnerHandler.getSid(),
                Long.toHexString(queueHeadZxid),
                Long.toHexString(packet.getZxid()),
                Long.toHexString(lastSeenZxid),
                packet.getZxid() - lastSeenZxid,
                queueBytesUsed);
        }
        activeObservers.add(learnerHandler);
        return lastProposedZxid;
    }

    @Override
    public long getQuorumVerifierVersion() {
        return self.getQuorumVerifier().getVersion();
    }

    @Override
    public String getPeerInfo(long sid) {
        QuorumPeer.QuorumServer server = self.getView().get(sid);
        return server == null ? "" : server.toString();
    }

    @Override
    public byte[] getQuorumVerifierBytes() {
        return self.getLastSeenQuorumVerifier().toString().getBytes(UTF_8);
    }

    @Override
    public QuorumAuthServer getQuorumAuthServer() {
        return (self == null) ? null : self.authServer;
    }

    void proposalReceived(QuorumPacket qp) {
        proposedPkts.add(new QuorumPacket(Leader.INFORM, qp.getZxid(), qp.getData(), null));
    }

    private synchronized QuorumPacket removeProposedPacket(long zxid) {
        QuorumPacket pkt = proposedPkts.peek();
        if (pkt == null || pkt.getZxid() > zxid) {
            LOG.debug("ignore missing proposal packet for {}", Long.toHexString(zxid));
            return null;
        }
        if (pkt.getZxid() != zxid) {
            final String m = String.format(
                "Unexpected proposal packet on commit ack, expected zxid 0x%d got zxid 0x%d",
                zxid,
                pkt.getZxid());
            LOG.error(m);
            throw new RuntimeException(m);
        }
        proposedPkts.remove();
        return pkt;
    }

    private synchronized void cacheCommittedPacket(final QuorumPacket pkt) {
        committedPkts.add(pkt);
        pktsSize += LearnerHandler.packetSize(pkt);
        // remove 5 packets for every one added as we near the size limit
        for (int i = 0; pktsSize > pktsSizeLimit * 0.8 && i < 5; i++) {
            QuorumPacket oldPkt = committedPkts.poll();
            if (oldPkt == null) {
                pktsSize = 0;
                break;
            }
            pktsSize -= LearnerHandler.packetSize(oldPkt);
        }
        // enforce the size limit as a hard cap
        while (pktsSize > pktsSizeLimit) {
            QuorumPacket oldPkt = committedPkts.poll();
            if (oldPkt == null) {
                pktsSize = 0;
                break;
            }
            pktsSize -= LearnerHandler.packetSize(oldPkt);
        }
    }

    private synchronized void sendPacket(final QuorumPacket pkt) {
        for (LearnerHandler lh : activeObservers) {
            lh.queuePacket(pkt);
        }
        lastProposedZxid = pkt.getZxid();
    }

    synchronized void proposalCommitted(long zxid) {
        QuorumPacket pkt = removeProposedPacket(zxid);
        if (pkt == null) {
            return;
        }
        cacheCommittedPacket(pkt);
        sendPacket(pkt);
    }

    synchronized void informAndActivate(long zxid, long suggestedLeaderId) {
        QuorumPacket pkt = removeProposedPacket(zxid);
        if (pkt == null) {
            return;
        }

        // Build the INFORMANDACTIVATE packet
        QuorumPacket informAndActivateQP = Leader.buildInformAndActivePacket(zxid, suggestedLeaderId, pkt.getData());
        cacheCommittedPacket(informAndActivateQP);
        sendPacket(informAndActivateQP);
    }

    public synchronized void start() throws IOException {
        if (thread != null && thread.isAlive()) {
            return;
        }
        listenerRunning = true;
        int backlog = 10; // dog science
        InetAddress address = self.getQuorumAddress().getReachableOrOne().getAddress();
        if (self.shouldUsePortUnification() || self.isSslQuorum()) {
            boolean allowInsecureConnection = self.shouldUsePortUnification();
            if (self.getQuorumListenOnAllIPs()) {
                ss = new UnifiedServerSocket(self.getX509Util(), allowInsecureConnection, port, backlog);
            } else {
                ss = new UnifiedServerSocket(self.getX509Util(), allowInsecureConnection, port, backlog, address);
            }
        } else {
            if (self.getQuorumListenOnAllIPs()) {
                ss = new ServerSocket(port, backlog);
            } else {
                ss = new ServerSocket(port, backlog, address);
            }
        }
        thread = new Thread(this, "ObserverMaster");
        thread.start();
        pinger = Executors.newSingleThreadScheduledExecutor();
        pinger.scheduleAtFixedRate(ping, self.tickTime / 2, self.tickTime / 2, TimeUnit.MILLISECONDS);
    }

    public void run() {
        ServerSocket ss;
        synchronized (this) {
            ss = this.ss;
        }
        while (listenerRunning) {
            try {
                Socket s = ss.accept();

                // start with the initLimit, once the ack is processed
                // in LearnerHandler switch to the syncLimit
                s.setSoTimeout(self.tickTime * self.initLimit);
                BufferedInputStream is = new BufferedInputStream(s.getInputStream());
                LearnerHandler lh = new LearnerHandler(s, is, this);
                lh.start();
            } catch (Exception e) {
                if (listenerRunning) {
                    LOG.debug("Ignoring accept exception (maybe shutting down)", e);
                } else {
                    LOG.debug("Ignoring accept exception (maybe client closed)", e);
                }
            }
        }
        /*
         * we don't need to close ss because we only got here because listenerRunning is
         * false and that is set and then ss is closed() in stop()
         */
    }

    public synchronized void stop() {
        listenerRunning = false;
        if (pinger != null) {
            pinger.shutdownNow();
        }
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        for (LearnerHandler lh : activeObservers) {
            lh.shutdown();
        }
    }

    int getNumActiveObservers() {
        return activeObservers.size();
    }

    public Iterable<Map<String, Object>> getActiveObservers() {
        Set<Map<String, Object>> info = new HashSet<>();
        for (LearnerHandler lh : activeObservers) {
            info.add(lh.getLearnerHandlerInfo());
        }
        return info;
    }

    public void resetObserverConnectionStats() {
        for (LearnerHandler lh : activeObservers) {
            lh.resetObserverConnectionStats();
        }
    }

    int getPktsSizeLimit() {
        return pktsSizeLimit;
    }

    static void setPktsSizeLimit(final int sizeLimit) {
        pktsSizeLimit = sizeLimit;
    }

    @Override
    public void registerLearnerHandlerBean(final LearnerHandler learnerHandler, Socket socket) {
        LearnerHandlerBean bean = new LearnerHandlerBean(learnerHandler, socket);
        if (zks.registerJMX(bean)) {
            connectionBeans.put(learnerHandler, bean);
        }
    }

    @Override
    public void unregisterLearnerHandlerBean(final LearnerHandler learnerHandler) {
        LearnerHandlerBean bean = connectionBeans.remove(learnerHandler);
        if (bean != null) {
            MBeanRegistry.getInstance().unregister(bean);
        }
    }

}
