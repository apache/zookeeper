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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.junit.Test;

public class AuthTest extends ClientBase {
    static {
        // password is test
        System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest",
                "super:D/InIHSb7yEEbrWz8b9l71RjZJU=");        
    }

    @Test
    public void testSuper() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.addAuthInfo("digest", "pat:pass".getBytes());
            zk.create("/path1", null, Ids.CREATOR_ALL_ACL,
                    CreateMode.PERSISTENT);
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
}