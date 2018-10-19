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

package org.apache.zookeeper.test;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.junit.Assert;
import org.junit.Test;

public class ChrootTest extends ClientBase {
    private static class MyWatcher implements Watcher {
        private final String path;
        private String eventPath;
        private CountDownLatch latch = new CountDownLatch(1);

        public MyWatcher(String path) {
            this.path = path;
        }
        public void process(WatchedEvent event) {
            System.out.println("latch:" + path + " " + event.getPath());
            this.eventPath = event.getPath();
            latch.countDown();
        }
        public boolean matches() throws InterruptedException {
            if (!latch.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)) {
                Assert.fail("No watch received within timeout period " + path);
            }
            return path.equals(eventPath);
        }
    }

    @Test
    public void testChrootSynchronous()
        throws IOException, InterruptedException, KeeperException
    {
        ZooKeeper zk1 = createClient();
        try {
            zk1.create("/ch1", null, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
        } finally {
            if(zk1 != null)
                zk1.close();
        }
        ZooKeeper zk2 = createClient(hostPort + "/ch1");
        try {
            Assert.assertEquals("/ch2",
                    zk2.create("/ch2", null, Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT));
        } finally {
            if(zk2 != null)
                zk2.close();
        }

        zk1 = createClient();
        zk2 = createClient(hostPort + "/ch1");
        try {
            // check get
            MyWatcher w1 = new MyWatcher("/ch1");
            Assert.assertNotNull(zk1.exists("/ch1", w1));
            MyWatcher w2 = new MyWatcher("/ch1/ch2");
            Assert.assertNotNull(zk1.exists("/ch1/ch2", w2));

            MyWatcher w3 = new MyWatcher("/ch2");
            Assert.assertNotNull(zk2.exists("/ch2", w3));

            // set watches on child
            MyWatcher w4 = new MyWatcher("/ch1");
            zk1.getChildren("/ch1",w4);
            MyWatcher w5 = new MyWatcher("/");
            zk2.getChildren("/",w5);

            // check set
            zk1.setData("/ch1", "1".getBytes(), -1);
            zk2.setData("/ch2", "2".getBytes(), -1);

            // check watches
            Assert.assertTrue(w1.matches());
            Assert.assertTrue(w2.matches());
            Assert.assertTrue(w3.matches());

            // check exceptions
            try {
                zk2.setData("/ch3", "3".getBytes(), -1);
            } catch (KeeperException.NoNodeException e) {
                Assert.assertEquals("/ch3", e.getPath());
            }

            Assert.assertTrue(Arrays.equals("1".getBytes(),
                    zk1.getData("/ch1", false, null)));
            Assert.assertTrue(Arrays.equals("2".getBytes(),
                    zk1.getData("/ch1/ch2", false, null)));
            Assert.assertTrue(Arrays.equals("2".getBytes(),
                    zk2.getData("/ch2", false, null)));

            // check delete
            zk2.delete("/ch2", -1);
            Assert.assertTrue(w4.matches());
            Assert.assertTrue(w5.matches());

            zk1.delete("/ch1", -1);
            Assert.assertNull(zk1.exists("/ch1", false));
            Assert.assertNull(zk1.exists("/ch1/ch2", false));
            Assert.assertNull(zk2.exists("/ch2", false));
        } finally {
            if(zk1 != null)
                zk1.close();
            if(zk2 != null)
                zk2.close();
        }
    }
}
