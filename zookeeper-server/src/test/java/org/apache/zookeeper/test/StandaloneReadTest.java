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

import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ReadConsistencyMode;
import org.apache.zookeeper.client.ZNode;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO Standalone server tests.
 */
public class StandaloneReadTest extends ClientBase {

    protected static final Logger LOG = LoggerFactory.getLogger(StandaloneReadTest.class);

    @BeforeEach
    public void setup() {
        //System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest", "super:D/InIHSb7yEEbrWz8b9l71RjZJU="/* password is 'test'*/);
        //QuorumPeerConfig.setReconfigEnabled(true);
    }


    @Test
    public void testWrongReadConsistencyMode() throws Exception {
        ZooKeeper zk = createClient();
        String path = "/foo";

        assertThrows(NullPointerException.class, () -> zk.getData(null, path, null));
        assertThrows(IllegalArgumentException.class, () -> zk.getData(ReadConsistencyMode.DUMMY_READ, path, null));

        // clean-up
        zk.close();
    }

    @Test
    @Timeout(value = 30)
    public void testSequentialReadWithoutWatch() throws Exception {
        testReadWithoutWatch(ReadConsistencyMode.SEQUENTIAL_READ);
    }

    @Test
    @Timeout(value = 30)
    public void testSequentialReadWithWatch() throws Exception {
        testReadWithWatch(ReadConsistencyMode.SEQUENTIAL_READ);
    }

    @Test
    @Timeout(value = 30)
    public void testOrderedSequentialReadWithoutWatch() throws Exception {
        testReadWithoutWatch(ReadConsistencyMode.ORDERED_SEQUENTIAL_READ);
    }

    @Test
    @Timeout(value = 30)
    public void testOrderedSequentialReadWithWatch() throws Exception {
        testReadWithWatch(ReadConsistencyMode.ORDERED_SEQUENTIAL_READ);
    }

    @Test
    @Timeout(value = 30)
    public void testLinearizableReadWithoutWatch() throws Exception {
        testReadWithoutWatch(ReadConsistencyMode.LINEARIZABLE_READ);
    }

    @Test
    @Timeout(value = 30)
    public void testLinearizableReadWithWatch() throws Exception {
        testReadWithWatch(ReadConsistencyMode.LINEARIZABLE_READ);
    }

    private void testReadWithWatch(ReadConsistencyMode readMod) throws Exception {
        ZooKeeper zk = createClient();
        String path = "/foo";
        String data = "bar";
        zk.create(path, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        CountDownLatch countDownLatch = new CountDownLatch(1);
        MyWatcher watcher = new MyWatcher(countDownLatch);
        CompletableFuture<ZNode> future = zk.getData(readMod, path, watcher);
        ZNode zNode = future.get();
        Assert.assertEquals(path, zNode.getPath());
        Assert.assertEquals(data, new String(zNode.getData()));
        Assert.assertEquals(data.length(), zNode.getStat().getDataLength());

        zk.setData(path, "test-watch".getBytes(), -1);
        if (!countDownLatch.await(10, TimeUnit.SECONDS)) {
            Assert.fail("the client doesn't receive a watch event.");
        }
        // clean-up
        zk.close();
    }

    private void testReadWithoutWatch(ReadConsistencyMode readMode) throws Exception {

        ZooKeeper zk = createClient();
        String path = "/foo";
        String data = "bar";
        zk.create(path, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        CompletableFuture<ZNode> future = zk.getData(readMode, path, null);
        ZNode zNode = future.get();
        Assert.assertEquals(path, zNode.getPath());
        Assert.assertEquals(data, new String(zNode.getData()));
        Assert.assertEquals(data.length(), zNode.getStat().getDataLength());

        // clean-up
        zk.close();
    }

    private class MyWatcher implements Watcher {
        CountDownLatch countDownLatch;

        public MyWatcher(CountDownLatch countDownLatch) {
            this.countDownLatch = countDownLatch;
        }

        public void process(WatchedEvent event) {
            if (event.getType() != Event.EventType.None) {
                countDownLatch.countDown();
            }
        }

    }

}
