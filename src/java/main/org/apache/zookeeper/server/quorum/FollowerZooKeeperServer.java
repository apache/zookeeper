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

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.log4j.Logger;

import org.apache.jute.Record;
import org.apache.zookeeper.server.FinalRequestProcessor;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * Just like the standard ZooKeeperServer. We just replace the request
 * processors: FollowerRequestProcessor -> CommitProcessor ->
 * FinalRequestProcessor
 * 
 * A SyncRequestProcessor is also spawn off to log proposals from the leader.
 */
public class FollowerZooKeeperServer extends ZooKeeperServer {
    private static final Logger LOG = Logger.getLogger(FollowerZooKeeperServer.class);

    private QuorumPeer self;

    CommitProcessor commitProcessor;

    SyncRequestProcessor syncProcessor;

    /*
     * Pending sync requests
     */
    ConcurrentLinkedQueue<Request> pendingSyncs;
    
    /**
     * @param port
     * @param dataDir
     * @throws IOException
     */
    FollowerZooKeeperServer(File dataDir, File dataLogDir,
            QuorumPeer self,DataTreeBuilder treeBuilder) throws IOException {
        super(dataDir, dataLogDir, self.tickTime,treeBuilder);
        this.self = self;
        this.pendingSyncs = new ConcurrentLinkedQueue<Request>();
    }

    public Follower getFollower(){
        return self.follower;
    }
    
    @Override
    protected void createSessionTracker() {
        sessionTracker = new FollowerSessionTracker(this, sessionsWithTimeouts,
                self.getId());
    }

    @Override
    protected void setupRequestProcessors() {
        RequestProcessor finalProcessor = new FinalRequestProcessor(this);
        commitProcessor = new CommitProcessor(finalProcessor);
        firstProcessor = new FollowerRequestProcessor(this, commitProcessor);
        syncProcessor = new SyncRequestProcessor(this,
                new SendAckRequestProcessor(getFollower()));
    }

    @Override
    protected void revalidateSession(ServerCnxn cnxn, long sessionId,
            int sessionTimeout) throws IOException, InterruptedException {
        getFollower().validateSession(cnxn, sessionId, sessionTimeout);
    }

    /**
     * @return
     */
    public HashMap<Long, Integer> getTouchSnapshot() {
        if (sessionTracker != null) {
            return ((FollowerSessionTracker) sessionTracker).snapshot();
        }
        return new HashMap<Long, Integer>();
    }

    @Override
    public long getServerId() {
        return self.getId();
    }

    LinkedBlockingQueue<Request> pendingTxns = new LinkedBlockingQueue<Request>();

    public void logRequest(TxnHeader hdr, Record txn) {
        Request request = new Request(null, hdr.getClientId(), hdr.getCxid(),
                hdr.getType(), null, null);
        request.hdr = hdr;
        request.txn = txn;
        request.zxid = hdr.getZxid();
        if ((request.zxid & 0xffffffffL) != 0) {
            pendingTxns.add(request);
        }
        syncProcessor.processRequest(request);
    }

    public void commit(long zxid) {
        if (pendingTxns.size() == 0) {
            LOG.warn("Committing " + Long.toHexString(zxid)
                    + " without seeing txn");
            return;
        }
        long firstElementZxid = pendingTxns.element().zxid;
        if (firstElementZxid != zxid) {
            LOG.error("Committing zxid 0x" + Long.toHexString(zxid)
                    + " but next pending txn 0x"
                    + Long.toHexString(firstElementZxid));
            System.exit(12);
        }
        Request request = pendingTxns.remove();
        commitProcessor.commit(request);
    }
    
    public void sync(){
        if(pendingSyncs.size() ==0){
            LOG.warn("Not expecting a sync.");
            return;
        }
                
        commitProcessor.commit(pendingSyncs.remove());
    }
             
         
    @Override
    public int getGlobalOutstandingLimit() {
        return super.getGlobalOutstandingLimit() / (self.getQuorumSize() - 1);
    }

    /**
     * Do not do anything in the follower.
     */
    @Override
    public void addCommittedProposal(Request r) {
        //do nothing
    }
    
    @Override
    public void shutdown() {
        try {
            super.shutdown();
        } catch (Exception e) {
            LOG.error("FIXMSG",e);
        }
        try {
            if (syncProcessor != null) {
                syncProcessor.shutdown();
            }
        } catch (Exception e) {
            LOG.error("FIXMSG",e);
        }
    }
}
