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

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

public class ReconfigExceptionTest extends ReconfigExceptionTestCase {
    @Test(timeout = 10000)
    public void testReconfigDisabledByDefault() throws InterruptedException {
        try {
            reconfigPort();
            Assert.fail("Reconfig should be disabled by default.");
        } catch (KeeperException e) {
            Assert.assertTrue(e.code() == KeeperException.Code.RECONFIGDISABLED);
        }
    }

    @Test(timeout = 10000)
    public void testReconfigFailWithoutAuth() throws InterruptedException {
        // Now enable reconfig feature by turning on the switch.
        QuorumPeerConfig.setReconfigEnabled(true);

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
        QuorumPeerConfig.setReconfigEnabled(true);

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
        QuorumPeerConfig.setReconfigEnabled(true);

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
        QuorumPeerConfig.setReconfigEnabled(true);

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
        QuorumPeerConfig.setReconfigEnabled(true);

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
}
