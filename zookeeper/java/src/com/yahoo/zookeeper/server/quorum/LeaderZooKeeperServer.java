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

import java.io.File;
import java.io.IOException;

import com.yahoo.zookeeper.server.FinalRequestProcessor;
import com.yahoo.zookeeper.server.PrepRequestProcessor;
import com.yahoo.zookeeper.server.RequestProcessor;
import com.yahoo.zookeeper.server.SessionTrackerImpl;
import com.yahoo.zookeeper.server.ZooKeeperServer;

/**
 * 
 * Just like the standard ZooKeeperServer. We just replace the request
 * processors: PrepRequestProcessor -> ProposalRequestProcessor ->
 * CommitProcessor -> Leader.ToBeAppliedRequestProcessor ->
 * FinalRequestProcessor
 */
public class LeaderZooKeeperServer extends ZooKeeperServer {
    Leader leader;

    long serverId;

    CommitProcessor commitProcessor;

    /**
     * @param port
     * @param dataDir
     * @throws IOException
     */
    LeaderZooKeeperServer(long serverId, File dataDir, File dataLogDir,
            Leader leader) throws IOException {
        super(dataDir, dataLogDir, leader.self.tickTime);
        this.serverId = serverId;
        this.leader = leader;
    }

    protected void setupRequestProcessors() {
        RequestProcessor finalProcessor = new FinalRequestProcessor(this);
        RequestProcessor toBeAppliedProcessor = new Leader.ToBeAppliedRequestProcessor(
                finalProcessor, leader.toBeApplied);
        commitProcessor = new CommitProcessor(toBeAppliedProcessor);
        RequestProcessor proposalProcessor = new ProposalRequestProcessor(this,
                commitProcessor);
        firstProcessor = new PrepRequestProcessor(this, proposalProcessor);
    }

    @Override
    public int getGlobalOutstandingLimit() {
        return super.getGlobalOutstandingLimit()
                / (leader.self.quorumPeers.size() - 1);
    }
    
    protected void createSessionTracker() {
        sessionTracker = new SessionTrackerImpl(this, sessionsWithTimeouts,
                tickTime, this.serverId);
    }


    public boolean touch(long sess, int to) {
        return sessionTracker.touchSession(sess, to);
    }

    public void setZxid(long zxid) {
        hzxid = zxid;
    }
}