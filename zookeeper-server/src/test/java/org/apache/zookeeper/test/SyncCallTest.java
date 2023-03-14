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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.AsyncCallback.Children2Callback;
import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.AsyncCallback.Create2Callback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.DummyWatcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.Test;

public class SyncCallTest extends ClientBase implements ChildrenCallback, Children2Callback, StringCallback, VoidCallback, Create2Callback {

    private CountDownLatch opsCount;

    List<Integer> results = new LinkedList<>();
    Integer limit = 100 + 1 + 100 + 100;

    @Test
    public void testSync() throws Exception {
        try {
            LOG.info("Starting ZK:{}", (new Date()).toString());
            opsCount = new CountDownLatch(limit);
            ZooKeeper zk = createClient();

            LOG.info("Beginning test:{}", (new Date()).toString());
            for (int i = 0; i < 50; i++) {
                zk.create("/test" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, (StringCallback) this, results);
            }

            for (int i = 50; i < 100; i++) {
                zk.create("/test" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, (Create2Callback) this, results);
            }
            zk.sync("/test", this, results);
            for (int i = 0; i < 100; i++) {
                zk.delete("/test" + i, 0, this, results);
            }
            for (int i = 0; i < 100; i++) {
                zk.getChildren("/", DummyWatcher.INSTANCE, (ChildrenCallback) this, results);
            }
            for (int i = 0; i < 100; i++) {
                zk.getChildren("/", DummyWatcher.INSTANCE, (Children2Callback) this, results);
            }
            LOG.info("Submitted all operations:{}", (new Date()).toString());

            if (!opsCount.await(10000, TimeUnit.MILLISECONDS)) {
                fail("Haven't received all confirmations" + opsCount.getCount());
            }

            for (int i = 0; i < limit; i++) {
                assertEquals(0, (int) results.get(i));
            }

        } catch (IOException e) {
            System.out.println(e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    public void processResult(int rc, String path, Object ctx, List<String> children) {
        ((List<Integer>) ctx).add(rc);
        opsCount.countDown();
    }

    @SuppressWarnings("unchecked")
    public void processResult(int rc, String path, Object ctx, List<String> children, Stat stat) {
        ((List<Integer>) ctx).add(rc);
        opsCount.countDown();
    }

    @SuppressWarnings("unchecked")
    public void processResult(int rc, String path, Object ctx, String name) {
        ((List<Integer>) ctx).add(rc);
        opsCount.countDown();

    }

    @SuppressWarnings("unchecked")
    @Override
    public void processResult(int rc, String path, Object ctx) {
        ((List<Integer>) ctx).add(rc);
        opsCount.countDown();

    }

    @SuppressWarnings("unchecked")
    @Override
    public void processResult(int rc, String path, Object ctx, String name, Stat stat) {
        ((List<Integer>) ctx).add(rc);
        opsCount.countDown();
    }

}
