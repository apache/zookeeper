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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.slf4j.Logger;

public class ParticipantRequestSyncer {
    private QuorumZooKeeperServer server;
    private final Logger log;
    private final Consumer<Request> syncRequest;
    private long nextEpoch = 0;
    private final List<Request> nextEpochTxns = new ArrayList<>();

    public ParticipantRequestSyncer(QuorumZooKeeperServer server, Logger log, Consumer<Request> syncRequest) {
        this.server = server;
        this.log = log;
        this.syncRequest = syncRequest;
    }

    public void syncRequest(Request request) {
        if (nextEpoch != 0) {
            // We can't persist requests from new leader epoch for now, as the new leader epoch
            // has not been committed yet. Otherwise, we could run into inconsistent if another
            // peer wins election and leads the same epoch.
            //
            // See also https://issues.apache.org/jira/browse/ZOOKEEPER-1277?page=com.atlassian.jira.plugin.system.issuetabpanels%3Acomment-tabpanel#comment-13149973
            log.debug("Block request(zxid: {}) on new leader epoch {}.", Long.toHexString(request.zxid), nextEpoch);
            nextEpochTxns.add(request);
            return;
        }
        if (ZxidUtils.isLastEpochZxid(request.zxid)) {
            nextEpoch = ZxidUtils.getEpochFromZxid(request.zxid) + 1;
            log.info("Receive last epoch zxid {}, preparing next epoch {}", Long.toHexString(request.zxid), nextEpoch);
        }
        syncRequest.accept(request);
    }

    public void finishCommit(long zxid) {
        if (ZxidUtils.isLastEpochZxid(zxid)) {
            log.info("Switch to new leader epoch {}", nextEpoch);
            server.confirmRolloverEpoch(nextEpoch);
            nextEpoch = 0;
            nextEpochTxns.forEach(syncRequest);
            nextEpochTxns.clear();
        }
    }
}
