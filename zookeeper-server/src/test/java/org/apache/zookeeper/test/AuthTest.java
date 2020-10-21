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
        // the default digestAlg is: SHA1
        System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest", "super:D/InIHSb7yEEbrWz8b9l71RjZJU=");
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
        assertEquals("super:D/InIHSb7yEEbrWz8b9l71RjZJU=", DigestAuthenticationProvider.generateDigest("super:test"));
        assertEquals("super:yyuhPKumRtNj4r8GnSbbwuq1vhE=", DigestAuthenticationProvider.generateDigest("super:zookeeper"));
        assertEquals("super:t6lQTvqID/Gl5Or0n4FYE6kKP8w=", DigestAuthenticationProvider.generateDigest(("super:foo")));
        assertEquals("super:hTdNN4QH4isoRvCrQ1Jf7REREQ4=", DigestAuthenticationProvider.generateDigest(("super:bar")));
    }

    // This test is used to check the correctness of the algorithm
    // For the same digest algorithm and input, the output of digest hash is the constant.
    @Test
    public void testDigest() throws NoSuchAlgorithmException {
        assertEquals("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3", getGeneratedDigestStr(DigestAuthenticationProvider.digest("test")));
        assertEquals("8a0444ded963cf1118dd34aa1acaafec268c654d", getGeneratedDigestStr(DigestAuthenticationProvider.digest("zookeeper")));
        assertEquals("0beec7b5ea3f0fdbc95d0dd47f3c5bc275da8a33", getGeneratedDigestStr(DigestAuthenticationProvider.digest(("foo"))));
        assertEquals("62cdb7020ff920e5aa642c3d4066950dd1f01f4d", getGeneratedDigestStr(DigestAuthenticationProvider.digest(("bar"))));
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
