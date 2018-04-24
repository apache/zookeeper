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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReconfigExceptionTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory
            .getLogger(ReconfigExceptionTest.class);
    private static String authProvider = "zookeeper.DigestAuthenticationProvider.superDigest";
    // Use DigestAuthenticationProvider.base64Encode or
    // run ZooKeeper jar with org.apache.zookeeper.server.auth.DigestAuthenticationProvider to generate password.
    // An example:
    // java -cp zookeeper-3.6.0-SNAPSHOT.jar:lib/log4j-1.2.17.jar:lib/slf4j-log4j12-1.7.5.jar:
    // lib/slf4j-api-1.7.5.jar org.apache.zookeeper.server.auth.DigestAuthenticationProvider super:test
    // The password here is 'test'.
    private static String superDigest = "super:D/InIHSb7yEEbrWz8b9l71RjZJU=";
    private QuorumUtil qu;
    private ZooKeeperAdmin zkAdmin;

    @Before
    public void setup() throws InterruptedException {
        System.setProperty(authProvider, superDigest);
        QuorumPeerConfig.setReconfigEnabled(true);

        // Get a three server quorum.
        qu = new QuorumUtil(1);
        qu.disableJMXTest = true;

        try {
            qu.startAll();
        } catch (IOException e) {
            Assert.fail("Fail to start quorum servers.");
        }

        resetZKAdmin();
    }

    @After
    public void tearDown() throws Exception {
        System.clearProperty(authProvider);
        try {
            if (qu != null) {
                qu.tearDown();
            }
            if (zkAdmin != null) {
                zkAdmin.close();
            }
        } catch (Exception e) {
            // Ignore.
        }
    }

    @Test(timeout = 10000)
    public void testReconfigDisabled() throws InterruptedException {
        QuorumPeerConfig.setReconfigEnabled(false);
        try {
            reconfigPort();
            Assert.fail("Reconfig should be disabled.");
        } catch (KeeperException e) {
            Assert.assertTrue(e.code() == KeeperException.Code.RECONFIGDISABLED);
        }
    }

    @Test(timeout = 10000)
    public void testReconfigFailWithoutAuth() throws InterruptedException {
        try {
            reconfigPort();
            Assert.fail("Reconfig should fail without auth.");
        } catch (KeeperException e) {
            // However a failure is still expected as user is not authenticated, so ACL check will fail.
            Assert.assertTrue(e.code() == KeeperException.Code.NOAUTH);
        }
    }

    @Test(timeout = 10000)
    public void testReconfigEnabledWithSuperUser() throws InterruptedException {
        try {
            zkAdmin.addAuthInfo("digest", "super:test".getBytes());
            Assert.assertTrue(reconfigPort());
        } catch (KeeperException e) {
            Assert.fail("Reconfig should not fail, but failed with exception : " + e.getMessage());
        }
    }

    @Test(timeout = 10000)
    public void testReconfigFailWithAuthWithNoACL() throws InterruptedException {
        resetZKAdmin();

        try {
            zkAdmin.addAuthInfo("digest", "user:test".getBytes());
            reconfigPort();
            Assert.fail("Reconfig should fail without a valid ACL associated with user.");
        } catch (KeeperException e) {
            // Again failure is expected because no ACL is associated with this user.
            Assert.assertTrue(e.code() == KeeperException.Code.NOAUTH);
        }
    }

    @Test(timeout = 10000)
    public void testReconfigEnabledWithAuthAndWrongACL() throws InterruptedException {
        resetZKAdmin();

        try {
            zkAdmin.addAuthInfo("digest", "super:test".getBytes());
            // There is ACL however the permission is wrong - need WRITE permission at leaste.
            ArrayList<ACL> acls = new ArrayList<ACL>(
                    Collections.singletonList(
                            new ACL(ZooDefs.Perms.READ,
                                    new Id("digest", "user:tl+z3z0vO6PfPfEENfLF96E6pM0="/* password is test */))));
            zkAdmin.setACL(ZooDefs.CONFIG_NODE, acls, -1);
            resetZKAdmin();
            zkAdmin.addAuthInfo("digest", "user:test".getBytes());
            reconfigPort();
            Assert.fail("Reconfig should fail with an ACL that is read only!");
        } catch (KeeperException e) {
            Assert.assertTrue(e.code() == KeeperException.Code.NOAUTH);
        }
    }

    @Test(timeout = 10000)
    public void testReconfigEnabledWithAuthAndACL() throws InterruptedException {
        resetZKAdmin();

        try {
            zkAdmin.addAuthInfo("digest", "super:test".getBytes());
            ArrayList<ACL> acls = new ArrayList<ACL>(
                    Collections.singletonList(
                            new ACL(ZooDefs.Perms.WRITE,
                            new Id("digest", "user:tl+z3z0vO6PfPfEENfLF96E6pM0="/* password is test */))));
            zkAdmin.setACL(ZooDefs.CONFIG_NODE, acls, -1);
            resetZKAdmin();
            zkAdmin.addAuthInfo("digest", "user:test".getBytes());
            Assert.assertTrue(reconfigPort());
        } catch (KeeperException e) {
            Assert.fail("Reconfig should not fail, but failed with exception : " + e.getMessage());
        }
    }

    // Utility method that recreates a new ZooKeeperAdmin handle, and wait for the handle to connect to
    // quorum servers.
    private void resetZKAdmin() throws InterruptedException {
        String cnxString;
        ClientBase.CountdownWatcher watcher = new ClientBase.CountdownWatcher();
        try {
            cnxString = "127.0.0.1:" + qu.getPeer(1).peer.getClientPort();
            if (zkAdmin != null) {
                zkAdmin.close();
            }
            zkAdmin = new ZooKeeperAdmin(cnxString,
                    ClientBase.CONNECTION_TIMEOUT, watcher);
        } catch (IOException e) {
            Assert.fail("Fail to create ZooKeeperAdmin handle.");
            return;
        }

        try {
            watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        } catch (InterruptedException | TimeoutException e) {
            Assert.fail("ZooKeeper admin client can not connect to " + cnxString);
        }
    }

    private boolean reconfigPort() throws KeeperException, InterruptedException {
        List<String> joiningServers = new ArrayList<String>();
        int leaderId = 1;
        while (qu.getPeer(leaderId).peer.leader == null)
            leaderId++;
        int followerId = leaderId == 1 ? 2 : 1;
        joiningServers.add("server." + followerId + "=localhost:"
                + qu.getPeer(followerId).peer.getQuorumAddress().getPort() /*quorum port*/
                + ":" + qu.getPeer(followerId).peer.getElectionAddress().getPort() /*election port*/
                + ":participant;localhost:" + PortAssignment.unique()/* new client port */);
        zkAdmin.reconfigure(joiningServers, null, null, -1, new Stat());
        return true;
    }
}
