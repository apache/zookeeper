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

import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ZooKeeperServerBean;

/**
 * Follower MBean interface implementation
 */
public class FollowerBean extends ZooKeeperServerBean implements FollowerMXBean {

    private final Follower follower;

    public FollowerBean(Follower follower, ZooKeeperServer zks) {
        super(zks);
        this.follower = follower;
    }

    public String getName() {
        return "Follower";
    }

    public String getQuorumAddress() {
        return follower.sock.toString();
    }

    public String getLastQueuedZxid() {
        return "0x" + Long.toHexString(follower.getLastQueued());
    }

    public int getPendingRevalidationCount() {
        return follower.getPendingRevalidationsCount();
    }

    @Override
    public long getElectionTimeTaken() {
        return follower.self.getElectionTimeTaken();
    }

    @Override
    public int getObserverMasterPacketSizeLimit() {
        return follower.om == null ? -1 : follower.om.getPktsSizeLimit();
    }

    @Override
    public void setObserverMasterPacketSizeLimit(int sizeLimit) {
        ObserverMaster.setPktsSizeLimit(sizeLimit);
    }

    @Override
    public int getMaxConcurrentSnapSyncs() {
        final ObserverMaster om = follower.om;
        return om == null ? -1 : om.getMaxConcurrentSnapSyncs();
    }

    @Override
    public void setMaxConcurrentSnapSyncs(int maxConcurrentSnapshots) {
        final ObserverMaster om = follower.om;
        if (om != null) {
            om.setMaxConcurrentSnapSyncs(maxConcurrentSnapshots);
        }
    }

    @Override
    public int getMaxConcurrentDiffSyncs() {
        final ObserverMaster om = follower.om;
        return om == null ? -1 : om.getMaxConcurrentDiffSyncs();
    }

    @Override
    public void setMaxConcurrentDiffSyncs(int maxConcurrentDiffSyncs) {
        final ObserverMaster om = follower.om;
        if (om != null) {
            om.setMaxConcurrentDiffSyncs(maxConcurrentDiffSyncs);
        }
    }

}
