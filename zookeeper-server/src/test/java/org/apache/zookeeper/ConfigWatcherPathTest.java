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

package org.apache.zookeeper;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;

public class ConfigWatcherPathTest extends ClientBase {
    private void join(Consumer<CompletableFuture<Void>> task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        task.accept(future);
        future.join();
    }

    private AsyncCallback.DataCallback complete(CompletableFuture<Void> future) {
        return (rc, path, ctx, data, stat) -> {
            if (rc == 0) {
                future.complete(null);
            } else {
                future.completeExceptionally(KeeperException.create(KeeperException.Code.get(rc), path));
            }
        };
    }

    private void testConfigWatcherPathWithChroot(String chroot) throws Exception {
        ZooKeeper zk1 = createClient(hostPort + chroot);

        BlockingQueueWatcher configWatcher = new BlockingQueueWatcher();

        // given|>config watcher: attach to config node multiple times
        byte[] configData = zk1.getConfig(configWatcher, null);
        join(future -> zk1.getConfig(configWatcher, complete(future), null));

        // given|>default watcher: attach to config node multiple times
        BlockingQueueWatcher defaultWatcher = new BlockingQueueWatcher();
        zk1.getWatchManager().setDefaultWatcher(defaultWatcher);
        zk1.getConfig(true, null);
        zk1.getConfig(defaultWatcher, null);

        // when: make change to config node
        ZooKeeper zk2 = createClient();
        zk2.addAuthInfo("digest", "super:test".getBytes());
        zk2.setData(ZooDefs.CONFIG_NODE, configData, -1);

        // then|>config watcher: only one event with path "/zookeeper/config"
        WatchedEvent configEvent = configWatcher.takeEvent(Duration.ofSeconds(10));
        assertEquals("/zookeeper/config", configEvent.getPath());
        assertNull(configWatcher.pollEvent(Duration.ofMillis(10)));

        // then|>default watcher: only one event with path "/zookeeper/config"
        WatchedEvent defaultWatcherEvent = defaultWatcher.takeEvent(Duration.ofSeconds(10));
        assertEquals("/zookeeper/config", defaultWatcherEvent.getPath());
        assertNull(defaultWatcher.pollEvent(Duration.ofMillis(10)));

        // given: all watchers fired
        // when: make change to config node
        zk2.setData(ZooDefs.CONFIG_NODE, configData, -1);

        // then: no more events
        assertNull(configWatcher.pollEvent(Duration.ofMillis(10)));
        assertNull(defaultWatcher.pollEvent(Duration.ofMillis(10)));
    }

    @Test
    public void testConfigWatcherPathWithNoChroot() throws Exception {
        testConfigWatcherPathWithChroot("");
    }

    @Test
    public void testConfigWatcherPathWithShortChroot() throws Exception {
        testConfigWatcherPathWithChroot("/short");
    }

    @Test
    public void testConfigWatcherPathWithLongChroot() throws Exception {
        testConfigWatcherPathWithChroot("/pretty-long-chroot-path");
    }

    @Test
    public void testConfigWatcherPathWithChrootZooKeeperTree() throws Exception {
        testConfigWatcherPathWithChroot("/zookeeper");
        testConfigWatcherPathWithChroot("/zookeeper/a");
        testConfigWatcherPathWithChroot("/zookeeper/config");
        testConfigWatcherPathWithChroot("/zookeeper/config/a");
    }

    @Test
    public void testConfigWatcherPathWithChrootZoo() throws Exception {
        // "/zoo" is prefix of "/zookeeper/config"
        testConfigWatcherPathWithChroot("/zoo");
    }

    private void testDataWatcherPathWithChroot(String chroot) throws Exception {
        assertTrue("/zookeeper/config".startsWith(chroot));
        String leafPath = "/zookeeper/config".substring(chroot.length());
        String dataPath = leafPath.isEmpty() ? "/" : leafPath;
        PathUtils.validatePath(dataPath);

        ZooKeeper zk1 = createClient(hostPort + chroot);

        BlockingQueueWatcher dataWatcher = new BlockingQueueWatcher();
        BlockingQueueWatcher configWatcher = new BlockingQueueWatcher();

        // given|>config watcher: attach to config node multiple times
        byte[] configData = zk1.getConfig(configWatcher, null);
        zk1.getConfig(configWatcher, null);

        // given|>data watcher: attach to config node through getData multiple times
        zk1.getData(dataPath, dataWatcher, null);
        join(future -> zk1.getData(dataPath, dataWatcher, complete(future), null));

        // given|>default watcher: attach to config node through getData and getConfig multiple times
        BlockingQueueWatcher defaultWatcher = new BlockingQueueWatcher();
        zk1.getWatchManager().setDefaultWatcher(defaultWatcher);
        zk1.getData(dataPath, true, null);
        zk1.getData(dataPath, defaultWatcher, null);
        zk1.getConfig(true, null);
        zk1.getConfig(defaultWatcher, null);

        // when: make change to config node
        ZooKeeper zk2 = createClient();
        zk2.addAuthInfo("digest", "super:test".getBytes());
        zk2.setData(ZooDefs.CONFIG_NODE, configData, -1);

        // then|>data watcher: only one event with path dataPath
        WatchedEvent dataEvent = dataWatcher.takeEvent(Duration.ofSeconds(10));
        assertEquals(dataPath, dataEvent.getPath());
        assertNull(dataWatcher.pollEvent(Duration.ofMillis(10)));

        // then|>config watcher: only one event with path "/zookeeper/config"
        WatchedEvent configEvent = configWatcher.takeEvent(Duration.ofSeconds(10));
        assertEquals("/zookeeper/config", configEvent.getPath());
        assertNull(configWatcher.pollEvent(Duration.ofMillis(10)));

        if (dataPath.equals("/zookeeper/config")) {
            // then|>default watcher: only one event with path "/zookeeper/config"
            WatchedEvent defaultWatcherEvent = defaultWatcher.takeEvent(Duration.ofSeconds(10));
            assertEquals("/zookeeper/config", defaultWatcherEvent.getPath());
        } else {
            // then|>default watcher: two events with path dataPath and "/zookeeper/config"
            Set<String> defaultWatcherPaths = new HashSet<>();
            defaultWatcherPaths.add(dataPath);
            defaultWatcherPaths.add("/zookeeper/config");

            WatchedEvent defaultWatcherEvent1 = defaultWatcher.takeEvent(Duration.ofSeconds(10));
            assertThat(defaultWatcherPaths, hasItem(defaultWatcherEvent1.getPath()));
            defaultWatcherPaths.remove(defaultWatcherEvent1.getPath());

            WatchedEvent defaultWatcherEvent2 = defaultWatcher.takeEvent(Duration.ofSeconds(10));
            assertNotNull(defaultWatcherEvent2);
            assertThat(defaultWatcherPaths, hasItem(defaultWatcherEvent2.getPath()));
        }
        assertNull(defaultWatcher.pollEvent(Duration.ofMillis(10)));

        // given: all watchers fired
        // when: make change to config node
        zk2.setData(ZooDefs.CONFIG_NODE, configData, -1);

        // then: no more events
        assertNull(dataWatcher.pollEvent(Duration.ofMillis(10)));
        assertNull(configWatcher.pollEvent(Duration.ofMillis(10)));
        assertNull(defaultWatcher.pollEvent(Duration.ofMillis(10)));
    }

    @Test
    public void testDataWatcherPathWithNoChroot() throws Exception {
        testDataWatcherPathWithChroot("");
    }

    @Test
    public void testDataWatcherPathWithChrootZooKeeper() throws Exception {
        testDataWatcherPathWithChroot("/zookeeper");
    }

    @Test
    public void testDataWatcherPathWithChrootZooKeeperConfig() throws Exception {
        testDataWatcherPathWithChroot("/zookeeper/config");
    }

    @Test
    public void testDataWatcherPathWithChrootAndConfigPath() throws Exception {
        try (ZooKeeper zk1 = createClient(hostPort + "/root1"); ZooKeeper zk2 = createClient()) {
            // given: watcher client path "/zookeeper/config" in chroot "/root1"
            BlockingQueueWatcher dataWatcher = new BlockingQueueWatcher();
            zk1.addWatch("/zookeeper/config", dataWatcher, AddWatchMode.PERSISTENT);

            // and: watch for "/zookeeper/config" in server
            BlockingQueueWatcher configWatcher = new BlockingQueueWatcher();
            byte[] configData = zk1.getConfig(configWatcher, null);

            // when: make change to config node
            zk2.addAuthInfo("digest", "super:test".getBytes());
            zk2.setData(ZooDefs.CONFIG_NODE, configData, -1);

            // then: config watcher works normally
            WatchedEvent configEvent = configWatcher.takeEvent(Duration.ofSeconds(10));
            assertEquals("/zookeeper/config", configEvent.getPath());

            // and: no data watcher for "/zookeeper/config" in chroot "/root1"
            assertNull(dataWatcher.pollEvent(Duration.ofSeconds(1)));
        }
    }
}
