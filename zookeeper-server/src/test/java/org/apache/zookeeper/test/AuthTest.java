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
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.auth.DigestAuthenticationProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class AuthTest extends ClientBase {

    @BeforeAll
    public static void setup() {
        // password is test
        // the default digestAlg is: SHA-256
        System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest", "super:wjySwxg860UATFtciuZ1lpzrCHrPeov6SPu/ZD56uig=");
        System.setProperty("zookeeper.authProvider.1", "org.apache.zookeeper.test.InvalidAuthProvider");
    }

    @AfterAll
    public static void teardown() {
        System.clearProperty("zookeeper.DigestAuthenticationProvider.superDigest");
        System.clearProperty(DigestAuthenticationProvider.DIGEST_ALGORITHM_KEY);
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
    public void testBadAuthNotifiesWatch() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.addAuthInfo("FOO", "BAR".getBytes());
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

    @Test
    public void testBadAuthThenSendOtherCommands() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.addAuthInfo("INVALID", "BAR".getBytes());
            zk.exists("/foobar", false);
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

    @Test
    public void testSuper() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.addAuthInfo("digest", "pat:pass".getBytes());
            zk.create("/path1", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            zk.close();
            // verify no auth
            zk = createClient();
            try {
                zk.getData("/path1", false, null);
                fail("auth verification");
            } catch (KeeperException.NoAuthException e) {
                // expected
            }
            zk.close();
            // verify bad pass fails
            zk = createClient();
            zk.addAuthInfo("digest", "pat:pass2".getBytes());
            try {
                zk.getData("/path1", false, null);
                fail("auth verification");
            } catch (KeeperException.NoAuthException e) {
                // expected
            }
            zk.close();
            // verify super with bad pass fails
            zk = createClient();
            zk.addAuthInfo("digest", "super:test2".getBytes());
            try {
                zk.getData("/path1", false, null);
                fail("auth verification");
            } catch (KeeperException.NoAuthException e) {
                // expected
            }
            zk.close();
            // verify super with correct pass success
            zk = createClient();
            zk.addAuthInfo("digest", "super:test".getBytes());
            zk.getData("/path1", false, null);
        } finally {
            zk.close();
        }
    }

    @Test
    public void testSuperACL() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.addAuthInfo("digest", "pat:pass".getBytes());
            zk.create("/path1", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            zk.close();
            // verify super can do anything and ignores ACLs
            zk = createClient();
            zk.addAuthInfo("digest", "super:test".getBytes());
            zk.getData("/path1", false, null);

            zk.setACL("/path1", Ids.READ_ACL_UNSAFE, -1);
            zk.create("/path1/foo", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);

            zk.setACL("/path1", Ids.OPEN_ACL_UNSAFE, -1);

        } finally {
            zk.close();
        }
    }

    @Test
    public void testOrdinaryACL() throws Exception {
        ZooKeeper zk = createClient();
        try {
            String path = "/path1";
            zk.create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.addAuthInfo("digest", "username1:password1".getBytes());
            List<ACL> list = new ArrayList<>();
            int perm = ZooDefs.Perms.ALL;
            String userPassword = "username1:password1";
            Id id = new Id("auth", userPassword);
            list.add(new ACL(perm, id));
            zk.setACL(path, list, -1);
            zk.close();

            zk = createClient();
            zk.addAuthInfo("digest", "super:test".getBytes());
            zk.getData(path, false, null);
            zk.close();

            zk = createClient();
            try {
                zk.getData(path, false, null);
                fail("should have NoAuthException");
            } catch (KeeperException.NoAuthException e) {
                // expected
            }
            zk.addAuthInfo("digest", "username1:password1".getBytes());
            zk.getData(path, false, null);
        } finally {
            zk.close();
        }
    }

    @Test
    public void testGenerateDigest() throws NoSuchAlgorithmException {
        assertEquals("super:wjySwxg860UATFtciuZ1lpzrCHrPeov6SPu/ZD56uig=", DigestAuthenticationProvider.generateDigest("super:test"));
        assertEquals("super:Ie58Fw6KA4ucTEDj23imIltKrXNDxQg8Rwtu0biQFcU=", DigestAuthenticationProvider.generateDigest("super:zookeeper"));
        assertEquals("super:rVOiTPnqEqlpIRXqSoE6+7h6SzbHUrfAe34i8n/gmRU=", DigestAuthenticationProvider.generateDigest(("super:foo")));
        assertEquals("super:vs70GBagNcqIhGR4R6rXP8E3lvJPYhzMpAMx8ghbTUk=", DigestAuthenticationProvider.generateDigest(("super:bar")));
    }

    // This test is used to check the correctness of the algorithm
    // For the same digest algorithm and input, the output of digest hash is the constant.
    @Test
    public void testDigest() throws NoSuchAlgorithmException {
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", getGeneratedDigestStr(DigestAuthenticationProvider.digest("test")));
        assertEquals("456831beef3fc1500939995d7369695f48642664a02d5eab9d807592a08b2384", getGeneratedDigestStr(DigestAuthenticationProvider.digest("zookeeper")));
        assertEquals("2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae", getGeneratedDigestStr(DigestAuthenticationProvider.digest(("foo"))));
        assertEquals("fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9", getGeneratedDigestStr(DigestAuthenticationProvider.digest(("bar"))));
    }

    // this method is used to generate the digest String to help us to compare the result generated by some online tool easily
    protected static String getGeneratedDigestStr(byte[] bytes) {
        StringBuilder stringBuilder = new StringBuilder("");
        if (bytes == null || bytes.length <= 0) {
            return null;
        }
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            String hv = Integer.toHexString(v);
            if (hv.length() < 2) {
                stringBuilder.append(0);
            }
            stringBuilder.append(hv);
        }
        return stringBuilder.toString();
    }

    public enum DigestAlgEnum {
        SHA_1("SHA1"),
        SHA_256("SHA-256"),
        SHA3_256("SHA3-256");

        private String name;

        DigestAlgEnum(String name) {
            this.name = name;
        }

        public String getName() {
            return this.name;
        }

        public static List<String> getValues() {
            List<String> digestList = new ArrayList<>();
            for (DigestAlgEnum digest : values()) {
                digestList.add(digest.getName());
            }
            return digestList;
        }
    }

}
