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

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ServerCnxn;

public class LearnerSyncRequest extends Request {

    LearnerHandler fh;

    SyncedLearnerTracker syncedAckSet;

    //命名
    volatile boolean canCommitted = false;

    CountDownLatch countDownLatch = new CountDownLatch(1);
    //long lastProposed = -1L;

    //boolean isQuorum = false;

    public LearnerSyncRequest(
        LearnerHandler fh, long sessionId, int xid, int type, ByteBuffer bb, List<Id> authInfo) {
        super(null, sessionId, xid, type, bb, authInfo);
        this.fh = fh;
        syncedAckSet = new SyncedLearnerTracker();
        //isQuorum = new AtomicBoolean(false);
    }

    public LearnerSyncRequest(
            ServerCnxn cnxn, LearnerHandler fh, long sessionId, int xid, int type, ByteBuffer bb, List<Id> authInfo) {
        super(cnxn, sessionId, xid, type, bb, authInfo);
        this.fh = fh;
        syncedAckSet = new SyncedLearnerTracker();
        //isQuorum = new AtomicBoolean(false);
    }

    public static LearnerSyncRequest makeLearnerSyncRequest (Request r) {
        return new LearnerSyncRequest(r.cnxn, null, r.sessionId, r.cxid, r.type, r.request, r.authInfo);
    }

    public SyncedLearnerTracker getSyncedAckSet() {
        return syncedAckSet;
    }

//    @Override
//    public boolean equals(Object o) {
//        if (this == o) return true;
//        if (o == null || getClass() != o.getClass()) return false;
//        LearnerSyncRequest that = (LearnerSyncRequest) o;
//        return lastProposed == that.lastProposed &&
//                sessionId == that.sessionId &&
//                cxid == that.cxid &&
//                type == that.type;
//    }
//
//    @Override
//    public int hashCode() {
//        return Objects.hash(lastProposed, sessionId, cxid, type);
//    }
//
//    @Override
//    public String toString() {
//        return "LearnerSyncRequest{" +
//                "lastProposed=" + lastProposed +
//                ", sessionId=" + sessionId +
//                ", cxid=" + cxid +
//                ", type=" + type +
//                '}';
//    }

//    public void setQuorum(boolean quorum) {
//        isQuorum = quorum;
//    }

//    public void setLastProposed(long lastProposed) {
//        this.lastProposed = lastProposed;
//    }

    public boolean isFromLeader() {
        return this.fh == null;
    }

    public void setCanCommitted(boolean canCommitted) {
        this.canCommitted = canCommitted;
    }

    public CountDownLatch getCountDownLatch() {
        return countDownLatch;
    }
}
