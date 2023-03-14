/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import mockit.Mock;
import mockit.MockUp;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestThrottler;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ThrottledOpHelper {
    protected static final Logger LOG = LoggerFactory.getLogger(ThrottledOpHelper.class);

    public static final class RequestThrottleMock extends MockUp<RequestThrottler> {
        public static void throttleEveryNthOp(int n) {
            everyNthOp = n;
            opCounter = 0;
        }
        private static int everyNthOp = 0;
        private static int opCounter = 0;

        @Mock
        private boolean shouldThrottleOp(Request request, long elapsedTime) {
            if (everyNthOp > 0 && request.isThrottlable() && (++opCounter % everyNthOp == 0)) {
                opCounter %= everyNthOp;
                return true;
            }
            return false;
        }
    }

    public static void applyMockUps() {
        new RequestThrottleMock();
    }

    public void testThrottledOp(ZooKeeper zk, ZooKeeperServer zs) throws IOException, InterruptedException, KeeperException {
        final int N = 5; // must be greater than 3
        final int COUNT = 100;
        RequestThrottleMock.throttleEveryNthOp(N);
        LOG.info("Before create /ivailo nodes");
        int opCount = 0;
        for (int i = 0; i < COUNT; i++) {
            String nodeName = "/ivailo" + i;
            if (opCount % N == N - 1) {
                try {
                    zk.create(nodeName, "".getBytes(), Ids.OPEN_ACL_UNSAFE,
                        (i % 2 == 0) ? CreateMode.PERSISTENT : CreateMode.EPHEMERAL);
                    fail("Should have gotten ThrottledOp exception");
                } catch (KeeperException.ThrottledOpException e) {
                    // anticipated outcome
                    Stat stat = zk.exists(nodeName, null);
                    assertNull(stat);
                    zk.create(nodeName, "".getBytes(), Ids.OPEN_ACL_UNSAFE,
                        (i % 2 == 0) ? CreateMode.PERSISTENT : CreateMode.EPHEMERAL);
                } catch (KeeperException e) {
                    fail("Should have gotten ThrottledOp exception");
                }
                opCount += 3; // three ops issued
            } else {
                zk.create(nodeName, "".getBytes(), Ids.OPEN_ACL_UNSAFE,
                    (i % 2 == 0) ? CreateMode.PERSISTENT : CreateMode.EPHEMERAL);
                opCount++; // one op issued
            }
            if (opCount % N == N - 1) {
                try {
                    zk.setData(nodeName, nodeName.getBytes(), -1);
                    fail("Should have gotten ThrottledOp exception");
                } catch (KeeperException.ThrottledOpException e) {
                    // anticipated outcome & retry
                    zk.setData(nodeName, nodeName.getBytes(), -1);
                } catch (KeeperException e) {
                    fail("Should have gotten ThrottledOp exception");
                }
                opCount += 2; // two ops issued, one for retry
            } else {
                zk.setData(nodeName, nodeName.getBytes(), -1);
                opCount++; // one op issued
            }
        }
        LOG.info("Before delete /ivailo nodes");
        for (int i = 0; i < COUNT; i++) {
            String nodeName = "/ivailo" + i;
            if (opCount % N == N - 1) {
                try {
                    zk.exists(nodeName, null);
                    fail("Should have gotten ThrottledOp exception");
                } catch (KeeperException.ThrottledOpException e) {
                    // anticipated outcome & retry
                    Stat stat = zk.exists(nodeName, null);
                    assertNotNull(stat);
                    opCount += 2; // two ops issued, one is retry
                } catch (KeeperException e) {
                    fail("Should have gotten ThrottledOp exception");
                }
            } else {
                Stat stat = zk.exists(nodeName, null);
                assertNotNull(stat);
                opCount++;
            }
            if (opCount % N == N - 1) {
                try {
                    zk.getData(nodeName, null, null);
                    fail("Should have gotten ThrottledOp exception");
                } catch (KeeperException.ThrottledOpException e) {
                    // anticipated outcome & retry
                    byte[] data = zk.getData(nodeName, null, null);
                    assertEquals(nodeName, new String(data));
                    opCount += 2; // two ops issued, one is retry
                } catch (KeeperException e) {
                    fail("Should have gotten ThrottledOp exception");
                }
            } else {
                byte[] data = zk.getData(nodeName, null, null);
                assertEquals(nodeName, new String(data));
                opCount++;
            }
            if (opCount % N == N - 1) {
                try {
                    // version 0 should not trigger BadVersion exception
                    zk.delete(nodeName, 0);
                    fail("Should have gotten ThrottledOp exception");
                } catch (KeeperException.ThrottledOpException e) {
                    // anticipated outcome & retry
                    zk.delete(nodeName, -1);
                } catch (KeeperException e) {
                    fail("Should have gotten ThrottledOp exception");
                }
                opCount += 2; // two ops issues, one for retry
            } else {
                zk.delete(nodeName, -1);
                opCount++; // one op only issued
            }
            if (opCount % N == N - 1) {
                try {
                    zk.exists(nodeName, null);
                    fail("Should have gotten ThrottledOp exception");
                } catch (KeeperException.ThrottledOpException e) {
                    // anticipated outcome & retry
                    Stat stat = zk.exists(nodeName, null);
                    assertNull(stat);
                    opCount += 2; // two ops issued, one is retry
                } catch (KeeperException e) {
                    fail("Should have gotten ThrottledOp exception");
                }
            } else {
                Stat stat = zk.exists(nodeName, null);
                assertNull(stat);
                opCount++;
            }
        }
        LOG.info("After delete /ivailo");
        zk.close();
    }

    public void testThrottledAcl(ZooKeeper zk, ZooKeeperServer zs) throws Exception {
        RequestThrottleMock.throttleEveryNthOp(0);

        final ArrayList<ACL> ACL_PERMS =
          new ArrayList<ACL>() { {
            add(new ACL(ZooDefs.Perms.READ, ZooDefs.Ids.ANYONE_ID_UNSAFE));
            add(new ACL(ZooDefs.Perms.WRITE, ZooDefs.Ids.ANYONE_ID_UNSAFE));
            add(new ACL(ZooDefs.Perms.ALL, ZooDefs.Ids.AUTH_IDS));
        }};
        String path = "/path1";
        zk.create(path, path.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.addAuthInfo("digest", "pat:test".getBytes());
        List<ACL> defaultAcls = zk.getACL(path, null);
        assertEquals(1, defaultAcls.size());

        RequestThrottleMock.throttleEveryNthOp(2);

        path = "/path2";
        zk.create(path, path.getBytes(), Ids.OPEN_ACL_UNSAFE,
            CreateMode.PERSISTENT);
        try {
            zk.setACL(path, ACL_PERMS, -1);
            fail("Should have gotten ThrottledOp exception");
        } catch (KeeperException.ThrottledOpException e) {
            // expected
        } catch (KeeperException e) {
            fail("Should have gotten ThrottledOp exception");
        }
        List<ACL> acls = zk.getACL(path, null);
        assertEquals(1, acls.size());

        RequestThrottleMock.throttleEveryNthOp(0);

        path = "/path3";
        zk.create(path, path.getBytes(), Ids.OPEN_ACL_UNSAFE,
            CreateMode.PERSISTENT);
        zk.setACL(path, ACL_PERMS, -1);
        acls = zk.getACL(path, null);
        assertEquals(3, acls.size());
    }
}
