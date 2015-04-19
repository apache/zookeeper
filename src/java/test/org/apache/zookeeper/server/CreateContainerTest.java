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

package org.apache.zookeeper.server;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.*;

public class CreateContainerTest extends ClientBase {
    private ZooKeeper zk;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        zk = createClient();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        zk.close();
    }

    @Test
    public void testCreate()
            throws IOException, KeeperException, InterruptedException {
        createNoStatVerifyResult("/foo");
        createNoStatVerifyResult("/foo/child");
    }

    @Test
    public void testCreateWithStat()
            throws IOException, KeeperException, InterruptedException {
        Stat stat = createWithStatVerifyResult("/foo");
        Stat childStat = createWithStatVerifyResult("/foo/child");
        // Don't expect to get the same stats for different creates.
        Assert.assertFalse(stat.equals(childStat));
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    public void testCreateWithNullStat()
            throws IOException, KeeperException, InterruptedException {
        final String name = "/foo";
        Assert.assertNull(zk.exists(name, false));

        Stat stat = null;
        // If a null Stat object is passed the create should still
        // succeed, but no Stat info will be returned.
        zk.createContainer(name, name.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, stat);
        Assert.assertNull(stat);
        Assert.assertNotNull(zk.exists(name, false));
    }

    @Test
    public void testSimpleDeletion()
            throws IOException, KeeperException, InterruptedException {
        zk.createContainer("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE);
        zk.create("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.delete("/foo/bar", -1);  // should cause "/foo" to get deleted when checkContainers() is called

        ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(),
                serverFactory.getZooKeeperServer().firstProcessor, 1, 100);
        containerManager.checkContainers();

        Thread.sleep(1000);

        Assert.assertNull("Container should have been deleted", zk.exists("/foo", false));
    }

    @Test
    public void testSimpleDeletionAsync()
            throws IOException, KeeperException, InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        AsyncCallback.Create2Callback cb = new AsyncCallback.Create2Callback() {
            @Override
            public void processResult(int rc, String path, Object ctx, String name, Stat stat) {
                Assert.assertEquals(ctx, "context");
                latch.countDown();
            }
        };
        zk.createContainer("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, cb, "context");
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        zk.create("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.delete("/foo/bar", -1);  // should cause "/foo" to get deleted when checkContainers() is called

        ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(),
                serverFactory.getZooKeeperServer().firstProcessor, 1, 100);
        containerManager.checkContainers();

        Thread.sleep(1000);

        Assert.assertNull("Container should have been deleted", zk.exists("/foo", false));
    }

    @Test
    public void testCascadingDeletion()
            throws IOException, KeeperException, InterruptedException {
        zk.createContainer("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE);
        zk.createContainer("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE);
        zk.create("/foo/bar/one", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.delete("/foo/bar/one", -1);  // should cause "/foo/bar" and "/foo" to get deleted when checkContainers() is called

        ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(),
                serverFactory.getZooKeeperServer().firstProcessor, 1, 100);
        containerManager.checkContainers();
        Thread.sleep(1000);
        containerManager
                .checkContainers();
        Thread.sleep(1000);

        Assert.assertNull("Container should have been deleted", zk.exists("/foo/bar", false));
        Assert.assertNull("Container should have been deleted", zk.exists("/foo", false));
    }

    @Test
    public void testFalseEmpty()
            throws IOException, KeeperException, InterruptedException {
        zk.createContainer("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE);
        zk.create("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(),
                serverFactory.getZooKeeperServer().firstProcessor, 1, 100) {
            @Override
            protected Collection<String> getCandidates() {
                return Collections.singletonList("/foo");
            }
        };
        containerManager.checkContainers();
        Thread.sleep(1000);

        Assert.assertNotNull("Container should have not been deleted", zk.exists("/foo", false));
    }

    @Test
    public void testMaxPerMinute()
            throws IOException, KeeperException, InterruptedException {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<String>();
        RequestProcessor processor = new RequestProcessor() {
            @Override
            public void processRequest(Request request) throws RequestProcessorException {
                queue.add(new String(request.request.array()));
            }

            @Override
            public void shutdown() {
            }
        };
        final ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(), processor, 1, 2) {
            @Override
            protected long getMinIntervalMs() {
                return 1000;
            }

            @Override
            protected Collection<String> getCandidates() {
                return Arrays.asList("/one", "/two", "/three", "/four");
            }
        };
        Executors.newSingleThreadExecutor().submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                containerManager.checkContainers();
                return null;
            }
        });
        Assert.assertEquals(queue.poll(5, TimeUnit.SECONDS), "/one");
        Assert.assertEquals(queue.poll(5, TimeUnit.SECONDS), "/two");
        Assert.assertEquals(queue.size(), 0);
        Thread.sleep(1000);
        Assert.assertEquals(queue.size(), 0);

        Assert.assertEquals(queue.poll(5, TimeUnit.SECONDS), "/three");
        Assert.assertEquals(queue.poll(5, TimeUnit.SECONDS), "/four");
    }

    private void createNoStatVerifyResult(String newName)
            throws KeeperException, InterruptedException {
        Assert.assertNull("Node existed before created", zk.exists(newName, false));
        zk.createContainer(newName, newName.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE);
        Assert.assertNotNull("Node was not created as expected",
                zk.exists(newName, false));
    }
    private Stat createWithStatVerifyResult(String newName)
            throws KeeperException, InterruptedException {
        Assert.assertNull("Node existed before created", zk.exists(newName, false));
        Stat stat = new Stat();
        zk.createContainer(newName, newName.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, stat);
        validateCreateStat(stat, newName);

        Stat referenceStat = zk.exists(newName, false);
        Assert.assertNotNull("Node was not created as expected", referenceStat);
        Assert.assertEquals(referenceStat, stat);

        return stat;
    }

    private void validateCreateStat(Stat stat, String name) {
        Assert.assertEquals(stat.getCzxid(), stat.getMzxid());
        Assert.assertEquals(stat.getCzxid(), stat.getPzxid());
        Assert.assertEquals(stat.getCtime(), stat.getMtime());
        Assert.assertEquals(0, stat.getCversion());
        Assert.assertEquals(0, stat.getVersion());
        Assert.assertEquals(0, stat.getAversion());
        Assert.assertEquals(0, stat.getEphemeralOwner());
        Assert.assertEquals(name.length(), stat.getDataLength());
        Assert.assertEquals(0, stat.getNumChildren());
    }
}
