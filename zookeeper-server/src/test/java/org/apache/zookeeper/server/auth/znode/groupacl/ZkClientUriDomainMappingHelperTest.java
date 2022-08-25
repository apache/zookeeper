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

package org.apache.zookeeper.server.auth.znode.groupacl;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.DummyWatcher;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ZkClientUriDomainMappingHelperTest extends ZKTestCase {
  private static final Logger LOG =
      LoggerFactory.getLogger(ZkClientUriDomainMappingHelperTest.class);
  private static final String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
  private static final String CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH = "/zookeeper/uri-domain-map";
  private static final int CONNECTION_TIMEOUT = 300000;
  private static final String[] MAPPING_PATHS = {
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH,
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/bar",
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/bar/bar0",
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/bar/bar1",
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/foo",
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/foo/foo1",
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/foo/foo2",
      CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/foo/bar1"
  };

  private ZooKeeperServer zookeeperServer;
  private ZooKeeper zookeeperClientConnection;
  private ServerCnxnFactory serverCnxnFactory;

  @Before
  public void setUp() throws IOException, InterruptedException, KeeperException {
    LOG.info("Starting Zk...");
    zookeeperServer = new ZooKeeperServer(testBaseDir, testBaseDir, 3000);
    final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
    serverCnxnFactory = ServerCnxnFactory.createFactory(PORT, -1);
    serverCnxnFactory.startup(zookeeperServer);

    LOG.info("Waiting for server startup");
    Assert.assertTrue("waiting for server being up ",
        ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));
    zookeeperClientConnection = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, DummyWatcher.INSTANCE);
  }

  @After
  public void cleanUp() throws InterruptedException, IOException, KeeperException {
    // Delete mapping znodes if they exist
    for (int i = MAPPING_PATHS.length - 1; i >= 0; i--) {
      if (zookeeperClientConnection.exists(MAPPING_PATHS[i], null) != null) {
        zookeeperClientConnection.delete(MAPPING_PATHS[i], -1);
      }
    }


    if (zookeeperClientConnection != null) {
      zookeeperClientConnection.close();
      zookeeperClientConnection = null;
    }
    if (serverCnxnFactory != null) {
      serverCnxnFactory.closeAll(ServerCnxn.DisconnectReason.SERVER_SHUTDOWN);
      serverCnxnFactory.shutdown();
      serverCnxnFactory = null;
    }
    if (zookeeperServer != null) {
      zookeeperServer.getZKDatabase().close();
      zookeeperServer.shutdown();
      zookeeperServer = null;
    }
    Assert.assertTrue("waiting for ZK server to shutdown",
        ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT));
  }

  /**
   * Create a dummy mapping and verify that the helper correctly updates changes to the mapping
   * stored in ZNodes.
   *
   * The following mapping will be used
   * . (root)
   * └── _CLIENT_URI_DOMAIN_MAPPING (mapping root path)
   *     ├── bar (application domain)
   *     │   ├── bar0 (client URI)
   *     │   └── bar1 (client URI)
   *     └── foo (application domain)
   *         ├── foo1 (client URI)
   *         ├── foo2 (client URI)
   *         └── bar1 (client URI)
   */
  @Test
  public void testB_ZkClientUriDomainMappingHelper() throws KeeperException, InterruptedException {
    for (String path : MAPPING_PATHS) {
      zookeeperClientConnection
          .create(path, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    ClientUriDomainMappingHelper helper = new ZkClientUriDomainMappingHelper(zookeeperServer);

    // For bar0, we should only get foo
    Assert.assertEquals(Collections.singleton("bar"), helper.getDomains("bar0"));

    // For bar1, we should get bar and foo
    Assert.assertEquals(new HashSet<>(Arrays.asList("bar", "foo")), helper.getDomains("bar1"));

    // Add a new application domain and add bar1 to it
    zookeeperClientConnection
        .create(CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/new", null, ZooDefs.Ids.OPEN_ACL_UNSAFE,
            CreateMode.PERSISTENT);
    zookeeperClientConnection.create(CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/new/bar1", null,
        ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

    // For bar1, we should get bar, foo, and new
    Assert
        .assertEquals(new HashSet<>(Arrays.asList("bar", "foo", "new")), helper.getDomains("bar1"));

    // Remove the application domain and bar1
    zookeeperClientConnection.delete(CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/new/bar1", -1);
    zookeeperClientConnection.delete(CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH + "/new", -1);

    // For bar1, we should get bar and foo
    Assert.assertEquals(new HashSet<>(Arrays.asList("bar", "foo")), helper.getDomains("bar1"));
  }
}
