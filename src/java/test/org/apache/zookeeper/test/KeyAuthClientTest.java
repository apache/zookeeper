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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class KeyAuthClientTest extends ClientBase {
    private static final Logger LOG = LoggerFactory.getLogger(KeyAuthClientTest.class);

    static {
        System.setProperty("zookeeper.authProvider.1", "org.apache.zookeeper.server.auth.KeyAuthenticationProvider");
    }

    public void createNodePrintAcl(ZooKeeper zk, String path, String testName) {
        try {
            LOG.debug("KeyAuthenticationProvider Creating Test Node:" + path + ".\n");
            zk.create(path, null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            List<ACL> acls = zk.getACL(path, null);
            LOG.debug("Node: " + path + " Test:" + testName + " ACLs:");
            for (ACL acl : acls) {
                LOG.debug("  " + acl.toString());
            }
        } catch (Exception e) {
            LOG.debug("  EXCEPTION THROWN", e);
        }
    }

    public void preAuth() throws Exception {
        ZooKeeper zk = createClient();
        zk.addAuthInfo("key", "25".getBytes());
        try {
            createNodePrintAcl(zk, "/pre", "testPreAuth");
            zk.setACL("/", Ids.CREATOR_ALL_ACL, -1);
            zk.getChildren("/", false);
            zk.create("/abc", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            zk.setData("/abc", "testData1".getBytes(), -1);
            zk.create("/key", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            zk.setData("/key", "5".getBytes(), -1);
            Thread.sleep(1000);
        } catch (KeeperException e) {
            Assert.fail("test failed :" + e);
        } finally {
            zk.close();
        }
    }

    public void missingAuth() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.getData("/abc", false, null);
            Assert.fail("Should not be able to get data");
        } catch (KeeperException correct) {
            // correct
        }
        try {
            zk.setData("/abc", "testData2".getBytes(), -1);
            Assert.fail("Should not be able to set data");
        } catch (KeeperException correct) {
            // correct
        } finally {
            zk.close();
        }
    }

    public void validAuth() throws Exception {
        ZooKeeper zk = createClient();
        // any multiple of 5 will do...
        zk.addAuthInfo("key", "25".getBytes());
        try {
            createNodePrintAcl(zk, "/valid", "testValidAuth");
            zk.getData("/abc", false, null);
            zk.setData("/abc", "testData3".getBytes(), -1);
        } catch (KeeperException.AuthFailedException e) {
            Assert.fail("test failed :" + e);
        } finally {
            zk.close();
        }
    }

    public void validAuth2() throws Exception {
        ZooKeeper zk = createClient();
        // any multiple of 5 will do...
        zk.addAuthInfo("key", "125".getBytes());
        try {
            createNodePrintAcl(zk, "/valid2", "testValidAuth2");
            zk.getData("/abc", false, null);
            zk.setData("/abc", "testData3".getBytes(), -1);
        } catch (KeeperException.AuthFailedException e) {
            Assert.fail("test failed :" + e);
        } finally {
            zk.close();
        }
    }

    @Test
    public void testAuth() throws Exception {
        // NOTE: the tests need to run in-order, and older versions of
        // junit don't provide any way to order tests
        preAuth();
        missingAuth();
        validAuth();
        validAuth2();
    }

}
