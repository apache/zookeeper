/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.zookeeper.AsyncCallback.MultiCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.OpResult.CreateResult;
import org.apache.zookeeper.OpResult.ErrorResult;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.junit.Before;
import org.junit.Test;

public class MultiAsyncTransactionTest extends ClientBase {
    private ZooKeeper zk;
    private final AtomicInteger pendingOps = new AtomicInteger(0);

    @Before
    public void setUp() throws Exception {
        super.setUp();
        zk = createClient();
        pendingOps.set(0);
    }

    private static class MultiResult {
        int rc;
        List<OpResult> results;
    }

    private void finishPendingOps() {
        if (pendingOps.decrementAndGet() == 0) {
            synchronized (pendingOps) {
                pendingOps.notifyAll();
            }
        }
    }

    private void waitForPendingOps(int timeout) throws Exception {
        synchronized(pendingOps) {
            while(pendingOps.get() > 0) {
                pendingOps.wait(timeout);
            }
        }
    }

    /**
     * ZOOKEEPER-1624: PendingChanges of create sequential node request didn't
     * get rollbacked correctly when multi-op failed. This cause
     * create sequential node request in subsequent multi-op to failed because
     * sequential node name generation is incorrect.
     *
     * The check is to make sure that each request in multi-op failed with
     * the correct reason.
     */
    @Test
    public void testSequentialNodeCreateInAsyncMulti() throws Exception {
        final int iteration = 4;
        final List<MultiResult> results = new ArrayList<MultiResult>();

        pendingOps.set(iteration);

        List<Op> ops = Arrays.asList(
                Op.create("/node-", new byte[0], Ids.OPEN_ACL_UNSAFE,
                          CreateMode.PERSISTENT_SEQUENTIAL),
                Op.create("/dup", new byte[0], Ids.OPEN_ACL_UNSAFE,
                          CreateMode.PERSISTENT));


        for (int i = 0; i < iteration; ++i) {
            zk.multi(ops, new MultiCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx,
                        List<OpResult> opResults) {
                    MultiResult result = new MultiResult();
                    result.results = opResults;
                    result.rc = rc;
                    results.add(result);
                    finishPendingOps();
                }
            }, null);
        }

        waitForPendingOps(CONNECTION_TIMEOUT);

        // Check that return code of all request are correct
        assertEquals(KeeperException.Code.OK.intValue(), results.get(0).rc);
        assertEquals(KeeperException.Code.NODEEXISTS.intValue(), results.get(1).rc);
        assertEquals(KeeperException.Code.NODEEXISTS.intValue(), results.get(2).rc);
        assertEquals(KeeperException.Code.NODEEXISTS.intValue(), results.get(3).rc);

        // Check that the first operation is successful in all request
        assertTrue(results.get(0).results.get(0) instanceof CreateResult);
        assertEquals(KeeperException.Code.OK.intValue(),
                ((ErrorResult) results.get(1).results.get(0)).getErr());
        assertEquals(KeeperException.Code.OK.intValue(),
                ((ErrorResult) results.get(2).results.get(0)).getErr());
        assertEquals(KeeperException.Code.OK.intValue(),
                ((ErrorResult) results.get(3).results.get(0)).getErr());

        // Check that the second operation failed after the first request
        assertEquals(KeeperException.Code.NODEEXISTS.intValue(),
                ((ErrorResult) results.get(1).results.get(1)).getErr());
        assertEquals(KeeperException.Code.NODEEXISTS.intValue(),
                ((ErrorResult) results.get(2).results.get(1)).getErr());
        assertEquals(KeeperException.Code.NODEEXISTS.intValue(),
                ((ErrorResult) results.get(3).results.get(1)).getErr());

    }
}