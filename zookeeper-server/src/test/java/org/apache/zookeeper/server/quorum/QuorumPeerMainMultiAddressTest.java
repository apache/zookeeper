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

package org.apache.zookeeper.server.quorum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.DummyWatcher;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ReconfigTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


public class QuorumPeerMainMultiAddressTest extends QuorumPeerTestBase {

  private static final int FIRST_SERVER = 0;
  private static final int SECOND_SERVER = 1;
  private static final int THIRD_SERVER = 2;
  private static final int FIRST_ADDRESS = 0;
  private static final int SECOND_ADDRESS = 1;
  private static final String UNREACHABLE_HOST = "invalid.hostname.unreachable.com";
  private static final String IPV6_LOCALHOST = "[0:0:0:0:0:0:0:1]";

  // IPv4 by default, change to IPV6_LOCALHOST to test with servers binding to IPv6
  private String hostName = "127.0.0.1";

  private int zNodeId = 0;

  @BeforeEach
  public void setUp() throws Exception {
    System.setProperty(QuorumPeer.CONFIG_KEY_MULTI_ADDRESS_ENABLED, "true");
    ClientBase.setupTestEnv();
    System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest", "super:D/InIHSb7yEEbrWz8b9l71RjZJU="/* password is 'test'*/);
    QuorumPeerConfig.setReconfigEnabled(true);

    // just to get rid of the unrelated 'InstanceAlreadyExistsException' in the logs
    System.setProperty("zookeeper.jmx.log4j.disable", "true");
  }

  @AfterEach
  public void tearDown() throws Exception {
    super.tearDown();
    System.clearProperty(QuorumPeer.CONFIG_KEY_MULTI_ADDRESS_ENABLED);
    System.clearProperty("zookeeper.jmx.log4j.disable");
  }


  @Test
  public void shouldStartClusterWithMultipleAddresses() throws Exception {
    // we have three ZK servers, each server has two quorumPort and two electionPort registered
    QuorumServerConfigBuilder quorumConfig = new QuorumServerConfigBuilder(hostName, 3, 2);

    // we launch the three servers, each server having the same configuration
    QuorumServerConfigBuilder builderForServer1 = new QuorumServerConfigBuilder(quorumConfig);
    QuorumServerConfigBuilder builderForServer2 = new QuorumServerConfigBuilder(quorumConfig);
    QuorumServerConfigBuilder builderForServer3 = new QuorumServerConfigBuilder(quorumConfig);
    launchServers(Arrays.asList(builderForServer1, builderForServer2, builderForServer3));

    checkIfZooKeeperQuorumWorks(quorumConfig);
  }


  @Test
  public void shouldStartClusterWithMultipleAddresses_IPv6() throws Exception {
    hostName = IPV6_LOCALHOST;

    shouldStartClusterWithMultipleAddresses();
  }


  @Test
  public void shouldStartClusterWhenSomeAddressesAreUnreachable() throws Exception {
    // we have three ZK servers, each server has two quorumPort and two electionPort registered
    // in the config we misconfigure one of the addresses for each servers
    QuorumServerConfigBuilder quorumConfig = new QuorumServerConfigBuilder(hostName, 3, 2)
      .changeHostName(FIRST_SERVER, SECOND_ADDRESS, UNREACHABLE_HOST)
      .changeHostName(SECOND_SERVER, SECOND_ADDRESS, UNREACHABLE_HOST)
      .changeHostName(THIRD_SERVER, SECOND_ADDRESS, UNREACHABLE_HOST);

    // we prepare the same initial config for all the three servers
    QuorumServerConfigBuilder builderForServer1 = new QuorumServerConfigBuilder(quorumConfig);
    QuorumServerConfigBuilder builderForServer2 = new QuorumServerConfigBuilder(quorumConfig);
    QuorumServerConfigBuilder builderForServer3 = new QuorumServerConfigBuilder(quorumConfig);

    // we test here:
    // - if the Leader can bind to the correct address and not die with BindException or
    //   SocketException for trying to bind to a wrong address / port
    // - if the ZK server can 'select' the correct address to connect when trying to form a quorum
    //   with the other servers
    launchServers(Arrays.asList(builderForServer1, builderForServer2, builderForServer3));

    checkIfZooKeeperQuorumWorks(quorumConfig);
  }


  @Test
  public void shouldStartClusterWhenSomeAddressesAreUnreachable_IPv6() throws Exception {
    hostName = IPV6_LOCALHOST;

    shouldStartClusterWhenSomeAddressesAreUnreachable();
  }


  @Test
  public void shouldReconfigIncrementallyByAddingMoreAddresses() throws Exception {
    // we have three ZK servers, each server has two quorumPort and two electionPort registered
    QuorumServerConfigBuilder initialQuorumConfig = new QuorumServerConfigBuilder(hostName, 3, 2);

    // we launch the three servers, each server should use the same initial config
    launchServers(Arrays.asList(initialQuorumConfig, initialQuorumConfig, initialQuorumConfig));

    checkIfZooKeeperQuorumWorks(initialQuorumConfig);

    // we create a new config where we add a new address to each server with random available ports
    QuorumServerConfigBuilder newQuorumConfig = new QuorumServerConfigBuilder(initialQuorumConfig)
      .addNewServerAddress(FIRST_SERVER);


    ZooKeeperAdmin zkAdmin = newZooKeeperAdmin(initialQuorumConfig);

    // initiating a new incremental reconfig, by using the updated ports
    ReconfigTest.reconfig(zkAdmin, newQuorumConfig.buildAsStringList(), null, null, -1);

    checkIfZooKeeperQuorumWorks(newQuorumConfig);
  }


  @Test
  public void shouldReconfigIncrementallyByDeletingSomeAddresses() throws Exception {
    // we have three ZK servers, each server has three quorumPort and three electionPort registered
    QuorumServerConfigBuilder initialQuorumConfig = new QuorumServerConfigBuilder(hostName, 3, 3);

    // we launch the three servers, each server should use the same initial config
    launchServers(Arrays.asList(initialQuorumConfig, initialQuorumConfig, initialQuorumConfig));

    checkIfZooKeeperQuorumWorks(initialQuorumConfig);

    // we create a new config where we delete a few address from each server
    QuorumServerConfigBuilder newQuorumConfig = new QuorumServerConfigBuilder(initialQuorumConfig)
      .deleteLastServerAddress(FIRST_SERVER)
      .deleteLastServerAddress(SECOND_SERVER)
      .deleteLastServerAddress(SECOND_SERVER)
      .deleteLastServerAddress(THIRD_SERVER);

    ZooKeeperAdmin zkAdmin = newZooKeeperAdmin(initialQuorumConfig);

    // initiating a new incremental reconfig, by using the updated ports
    ReconfigTest.reconfig(zkAdmin, newQuorumConfig.buildAsStringList(), null, null, -1);

    checkIfZooKeeperQuorumWorks(newQuorumConfig);
  }

  @Test
  public void shouldReconfigNonIncrementally() throws Exception {
    // we have three ZK servers, each server has two quorumPort and two electionPort registered
    QuorumServerConfigBuilder initialQuorumConfig = new QuorumServerConfigBuilder(hostName, 3, 2);

    // we launch the three servers, each server should use the same initial config
    launchServers(Arrays.asList(initialQuorumConfig, initialQuorumConfig, initialQuorumConfig));

    checkIfZooKeeperQuorumWorks(initialQuorumConfig);

    // we create a new config where we delete and add a few address for each server
    QuorumServerConfigBuilder newQuorumConfig = new QuorumServerConfigBuilder(initialQuorumConfig)
      .deleteLastServerAddress(FIRST_SERVER)
      .deleteLastServerAddress(SECOND_SERVER)
      .deleteLastServerAddress(SECOND_SERVER)
      .deleteLastServerAddress(THIRD_SERVER)
      .addNewServerAddress(SECOND_SERVER)
      .addNewServerAddress(THIRD_SERVER);

    ZooKeeperAdmin zkAdmin = newZooKeeperAdmin(initialQuorumConfig);

    // initiating a new non-incremental reconfig, by using the updated ports
    ReconfigTest.reconfig(zkAdmin, null, null, newQuorumConfig.buildAsStringList(), -1);

    checkIfZooKeeperQuorumWorks(newQuorumConfig);
  }


  @Test
  public void shouldReconfigIncrementally_IPv6() throws Exception {

    hostName = IPV6_LOCALHOST;

    // we have three ZK servers, each server has two quorumPort and two electionPort registered
    QuorumServerConfigBuilder initialQuorumConfig = new QuorumServerConfigBuilder(hostName, 3, 2);

    // we launch the three servers, each server should use the same initial config
    launchServers(Arrays.asList(initialQuorumConfig, initialQuorumConfig, initialQuorumConfig));

    checkIfZooKeeperQuorumWorks(initialQuorumConfig);

    // we create a new config where we delete and add a few address for each server
    QuorumServerConfigBuilder newQuorumConfig = new QuorumServerConfigBuilder(initialQuorumConfig)
      .deleteLastServerAddress(FIRST_SERVER)
      .deleteLastServerAddress(SECOND_SERVER)
      .deleteLastServerAddress(SECOND_SERVER)
      .deleteLastServerAddress(THIRD_SERVER)
      .addNewServerAddress(SECOND_SERVER)
      .addNewServerAddress(THIRD_SERVER);

    ZooKeeperAdmin zkAdmin = newZooKeeperAdmin(initialQuorumConfig);

    // initiating a new incremental reconfig, by using the updated ports
    ReconfigTest.reconfig(zkAdmin, newQuorumConfig.buildAsStringList(), null, null, -1);

    checkIfZooKeeperQuorumWorks(newQuorumConfig);
  }

  @Test
  public void shouldFailToReconfigWithMultipleAddressesWhenFeatureIsDisabled() throws Exception {
    System.setProperty(QuorumPeer.CONFIG_KEY_MULTI_ADDRESS_ENABLED, "false");

    // we have three ZK servers, each server has a single quorumPort and single electionPort registered
    QuorumServerConfigBuilder initialQuorumConfig = new QuorumServerConfigBuilder(hostName, 3, 1);

    // we launch the three servers, each server should use the same initial config
    launchServers(Arrays.asList(initialQuorumConfig, initialQuorumConfig, initialQuorumConfig));

    checkIfZooKeeperQuorumWorks(initialQuorumConfig);

    // we create a new config where we add a new address to one of the servers with random available ports
    QuorumServerConfigBuilder newQuorumConfig = new QuorumServerConfigBuilder(initialQuorumConfig)
            .addNewServerAddress(FIRST_SERVER);

    ZooKeeperAdmin zkAdmin = newZooKeeperAdmin(initialQuorumConfig);

    // initiating a new incremental reconfig by using the updated ports, expecting exceptions here
    try {
      ReconfigTest.reconfig(zkAdmin, newQuorumConfig.buildAsStringList(), null, null, -1);
      fail("Reconfig succeeded with multiple addresses without exception when the MultiAddress feature is disabled");
    } catch (KeeperException.BadArgumentsException e) {
      // do nothing, this is what we expected
    } catch (Exception e) {
      fail("Reconfig failed in a wrong way. We expected KeeperException.BadArgumentsException.");
    }
  }

  private void launchServers(List<QuorumServerConfigBuilder> builders) throws IOException, InterruptedException {

    numServers = builders.size();

    servers = new Servers();
    servers.clientPorts = new int[numServers];
    servers.mt = new MainThread[numServers];
    servers.zk = new ZooKeeper[numServers];

    for (int i = 0; i < numServers; i++) {
      QuorumServerConfigBuilder quorumServerConfigBuilder = builders.get(i);
      String quorumCfgSection = quorumServerConfigBuilder.build();
      LOG.info(String.format("starting server %d with quorum config:\n%s", i, quorumCfgSection));
      servers.clientPorts[i] = quorumServerConfigBuilder.getClientPort(i);
      servers.mt[i] = new MainThread(i, servers.clientPorts[i], quorumCfgSection);
      servers.mt[i].start();
      servers.restartClient(i, this);
    }

    waitForAll(servers, ZooKeeper.States.CONNECTED);

    for (int i = 0; i < numServers; i++) {
      servers.zk[i].close(5000);
    }
  }

  private void checkIfZooKeeperQuorumWorks(QuorumServerConfigBuilder builder) throws IOException,
    InterruptedException, KeeperException {

    LOG.info("starting to verify if Quorum works");
    zNodeId += 1;
    String zNodePath = "/foo_" + zNodeId;
    ZooKeeper zk = connectToZkServer(builder, FIRST_SERVER);
    zk.create(zNodePath, "foobar1".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    assertEquals(new String(zk.getData(zNodePath, null, null)), "foobar1");
    zk.close(1000);


    zk = connectToZkServer(builder, SECOND_SERVER);
    assertEquals(new String(zk.getData(zNodePath, null, null)), "foobar1");
    zk.close(1000);

    zk = connectToZkServer(builder, THIRD_SERVER);
    assertEquals(new String(zk.getData(zNodePath, null, null)), "foobar1");
    zk.close(1000);

    LOG.info("Quorum verification finished successfully");

  }

  private ZooKeeper connectToZkServer(QuorumServerConfigBuilder builder, int serverId) throws IOException, InterruptedException {
    QuorumServerConfigBuilder.ServerAddress server = builder.getServerAddress(serverId, FIRST_ADDRESS);
    int clientPort = builder.getClientPort(serverId);
    ZooKeeper zk = new ZooKeeper(server.getHost() + ":" + clientPort, ClientBase.CONNECTION_TIMEOUT, this);
    waitForOne(zk, ZooKeeper.States.CONNECTED);
    return zk;
  }

  private ZooKeeperAdmin newZooKeeperAdmin(
    QuorumServerConfigBuilder quorumConfig) throws IOException {
    ZooKeeperAdmin zkAdmin = new ZooKeeperAdmin(
      hostName + ":" + quorumConfig.getClientPort(FIRST_SERVER),
      ClientBase.CONNECTION_TIMEOUT,
      DummyWatcher.INSTANCE);
    zkAdmin.addAuthInfo("digest", "super:test".getBytes());
    return zkAdmin;
  }


}
