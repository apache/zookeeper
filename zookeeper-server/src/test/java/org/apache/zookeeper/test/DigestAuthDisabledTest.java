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

import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class DigestAuthDisabledTest extends ClientBase {

    @BeforeAll
    public static void setUpEnvironment() {
        System.setProperty("zookeeper.DigestAuthenticationProvider.enabled", "false");
    }

    @AfterAll
    public static void cleanUpEnvironment() {
        System.clearProperty("zookeeper.DigestAuthenticationProvider.enabled");
    }

    private final CountDownLatch authFailed = new CountDownLatch(1);

    @Override
    protected TestableZooKeeper createClient(String hp) throws IOException, InterruptedException {
        MyWatcher watcher = new MyWatcher();
        return createClient(watcher, hp);
    }

    private class MyWatcher extends CountdownWatcher {

        @Override
        public synchronized void process(WatchedEvent event) {
            if (event.getState() == KeeperState.AuthFailed) {
                authFailed.countDown();
            } else {
                super.process(event);
            }
        }

    }

    @Test
    public void testDigestAuthDisabledTriggersAuthFailed() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.addAuthInfo("digest", "roger:muscadet".getBytes());
            zk.getData("/path1", false, null);
            fail("Should get auth state error");
        } catch (KeeperException.AuthFailedException e) {
            if (!authFailed.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)) {
                fail("Should have called my watcher");
            }
        } finally {
            zk.close();
        }
    }
}
