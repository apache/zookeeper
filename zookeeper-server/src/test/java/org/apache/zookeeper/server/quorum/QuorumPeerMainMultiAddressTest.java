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

import static org.junit.Assert.assertEquals;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.DummyWatcher;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ReconfigTest;
import org.junit.Before;
import org.junit.Test;


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

  @Before
  public void setUp() throws Exception {
    ClientBase.setupTestEnv();
    System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest", "super:D/InIHSb7yEEbrWz8b9l71RjZJU="/* password is 'test'*/);
    QuorumPeerConfig.setReconfigEnabled(true);
  }


  @Test
  public void shouldStartClusterWithMultipleAddresses() throws Exception {
    // we have three ZK servers, each server has two quorumPort and two electionPort registered
    QuorumConfigBuilder quorumConfig = new QuorumConfigBuilder(hostName, 3, 2);

    // we launch the three servers, each server having the same configuration
    QuorumConfigBuilder builderForServer1 = new QuorumConfigBuilder(quorumConfig);
    QuorumConfigBuilder builderForServer2 = new QuorumConfigBuilder(quorumConfig);
    QuorumConfigBuilder builderForServer3 = new QuorumConfigBuilder(quorumConfig);
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
    QuorumConfigBuilder quorumConfig = new QuorumConfigBuilder(hostName, 3, 2);

    // we launch the three servers
    // in the config of each server, we misconfigure one of the addresses for the other two servers
    QuorumConfigBuilder builderForServer1 = new QuorumConfigBuilder(quorumConfig)
      .changeHostName(SECOND_SERVER, SECOND_ADDRESS, UNREACHABLE_HOST)
      .changeHostName(THIRD_SERVER, SECOND_ADDRESS, UNREACHABLE_HOST);

    QuorumConfigBuilder builderForServer2 = new QuorumConfigBuilder(quorumConfig)
      .changeHostName(FIRST_SERVER, SECOND_ADDRESS, UNREACHABLE_HOST)
      .changeHostName(THIRD_SERVER, SECOND_ADDRESS, UNREACHABLE_HOST);

    QuorumConfigBuilder builderForServer3 = new QuorumConfigBuilder(quorumConfig)
      .changeHostName(FIRST_SERVER, SECOND_ADDRESS, UNREACHABLE_HOST)
      .changeHostName(SECOND_SERVER, SECOND_ADDRESS, UNREACHABLE_HOST);

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
    QuorumConfigBuilder initialQuorumConfig = new QuorumConfigBuilder(hostName, 3, 2);

    // we launch the three servers, each server should use the same initial config
    launchServers(Arrays.asList(initialQuorumConfig, initialQuorumConfig, initialQuorumConfig));

    checkIfZooKeeperQuorumWorks(initialQuorumConfig);

    // we create a new config where we add a new address to each server with random available ports
    QuorumConfigBuilder newQuorumConfig = new QuorumConfigBuilder(initialQuorumConfig)
      .addNewServerAddress(FIRST_SERVER);


    ZooKeeperAdmin zkAdmin = newZooKeeperAdmin(initialQuorumConfig);

    // initiating a new incremental reconfig, by adding new ports to each server
    ReconfigTest.reconfig(zkAdmin, newQuorumConfig.buildAsStringList(), null, null, -1);

    checkIfZooKeeperQuorumWorks(newQuorumConfig);
  }


  @Test
  public void shouldReconfigIncrementallyByDeletingSomeAddresses() throws Exception {
    // we have three ZK servers, each server has three quorumPort and three electionPort registered
    QuorumConfigBuilder initialQuorumConfig = new QuorumConfigBuilder(hostName, 3, 3);

    // we launch the three servers, each server should use the same initial config
    launchServers(Arrays.asList(initialQuorumConfig, initialQuorumConfig, initialQuorumConfig));

    checkIfZooKeeperQuorumWorks(initialQuorumConfig);

    // we create a new config where we delete a few address from each server
    QuorumConfigBuilder newQuorumConfig = new QuorumConfigBuilder(initialQuorumConfig)
      .deleteLastServerAddress(FIRST_SERVER)
      .deleteLastServerAddress(SECOND_SERVER)
      .deleteLastServerAddress(SECOND_SERVER)
      .deleteLastServerAddress(THIRD_SERVER);

    ZooKeeperAdmin zkAdmin = newZooKeeperAdmin(initialQuorumConfig);

    // initiating a new incremental reconfig, by adding new ports to each server
    ReconfigTest.reconfig(zkAdmin, newQuorumConfig.buildAsStringList(), null, null, -1);

    checkIfZooKeeperQuorumWorks(newQuorumConfig);
  }

  @Test
  public void shouldReconfigNonIncrementallyByChangingAllAddresses() throws Exception {
    // we have three ZK servers, each server has two quorumPort and two electionPort registered
    QuorumConfigBuilder initialQuorumConfig = new QuorumConfigBuilder(hostName, 3, 2);

    // we launch the three servers, each server should use the same initial config
    launchServers(Arrays.asList(initialQuorumConfig, initialQuorumConfig, initialQuorumConfig));

    checkIfZooKeeperQuorumWorks(initialQuorumConfig);

    // we create a new config where no ports are the same
    // each server will have two new quorumPorts and two new electionPorts
    QuorumConfigBuilder newQuorumConfig = new QuorumConfigBuilder(hostName, 3, 2);


    ZooKeeperAdmin zkAdmin = newZooKeeperAdmin(initialQuorumConfig);

    // initiating a new non-incremental reconfig, by adding new ports to each server
    ReconfigTest.reconfig(zkAdmin, null, null, newQuorumConfig.buildAsStringList(), -1);

    checkIfZooKeeperQuorumWorks(newQuorumConfig);
  }


  @Test
  public void shouldReconfigIncrementally_IPv6() throws Exception {

    hostName = IPV6_LOCALHOST;

    // we have three ZK servers, each server has two quorumPort and two electionPort registered
    QuorumConfigBuilder initialQuorumConfig = new QuorumConfigBuilder(hostName, 3, 2);

    // we launch the three servers, each server should use the same initial config
    launchServers(Arrays.asList(initialQuorumConfig, initialQuorumConfig, initialQuorumConfig));

    checkIfZooKeeperQuorumWorks(initialQuorumConfig);

    // we create a new config where we delete and add a few address for each server
    QuorumConfigBuilder newQuorumConfig = new QuorumConfigBuilder(initialQuorumConfig)
      .deleteLastServerAddress(FIRST_SERVER)
      .deleteLastServerAddress(SECOND_SERVER)
      .deleteLastServerAddress(SECOND_SERVER)
      .deleteLastServerAddress(THIRD_SERVER)
      .addNewServerAddress(SECOND_SERVER)
      .addNewServerAddress(THIRD_SERVER);

    ZooKeeperAdmin zkAdmin = newZooKeeperAdmin(initialQuorumConfig);

    // initiating a new incremental reconfig, by adding new ports to each server
    ReconfigTest.reconfig(zkAdmin, newQuorumConfig.buildAsStringList(), null, null, -1);

    checkIfZooKeeperQuorumWorks(newQuorumConfig);
  }


  private Servers launchServers(List<QuorumConfigBuilder> builders) throws IOException, InterruptedException {

    numServers = builders.size();

    servers = new Servers();
    servers.clientPorts = new int[numServers];
    servers.mt = new MainThread[numServers];
    servers.zk = new ZooKeeper[numServers];

    for (int i = 0; i < numServers; i++) {
      QuorumConfigBuilder quorumConfigBuilder = builders.get(i);
      String quorumCfgSection = quorumConfigBuilder.build();
      LOG.info(String.format("starting server %d with quorum config:\n%s", i, quorumCfgSection));
      servers.clientPorts[i] = quorumConfigBuilder.getClientPort(i);
      servers.mt[i] = new MainThread(i, servers.clientPorts[i], quorumCfgSection);
      servers.mt[i].start();
      servers.restartClient(i, this);
    }

    waitForAll(servers, ZooKeeper.States.CONNECTED);

    for (int i = 0; i < numServers; i++) {
      servers.zk[i].close(1000);
    }
    return servers;
  }

  private void checkIfZooKeeperQuorumWorks(QuorumConfigBuilder builder) throws IOException,
    InterruptedException, KeeperException {

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

  }

  private ZooKeeper connectToZkServer(QuorumConfigBuilder builder, int serverId) throws IOException, InterruptedException {
    ServerAddress server = builder.getServerAddress(serverId, FIRST_ADDRESS);
    int clientPort = builder.getClientPort(serverId);
    ZooKeeper zk = new ZooKeeper(server.getHost() + ":" + clientPort, ClientBase.CONNECTION_TIMEOUT, this);
    waitForOne(zk, ZooKeeper.States.CONNECTED);
    return zk;
  }

  private ZooKeeperAdmin newZooKeeperAdmin(
    QuorumConfigBuilder quorumConfig) throws IOException {
    ZooKeeperAdmin zkAdmin = new ZooKeeperAdmin(
      hostName + ":" + quorumConfig.getClientPort(FIRST_SERVER),
      ClientBase.CONNECTION_TIMEOUT,
      DummyWatcher.INSTANCE);
    zkAdmin.addAuthInfo("digest", "super:test".getBytes());
    return zkAdmin;
  }


  private static class QuorumConfigBuilder {

    // map of (serverId -> clientPort)
    private final Map<Integer, Integer> clientIds = new HashMap<>();

    // map of (serverId -> (ServerAddress=host,quorumPort,electionPort) )
    private final Map<Integer, List<ServerAddress>> serverAddresses = new HashMap<>();
    private final String hostName;
    private final int numberOfServers;

    private QuorumConfigBuilder(String hostName, int numberOfServers, int numberOfServerAddresses) {
      this.numberOfServers = numberOfServers;
      this.hostName = hostName;
      for (int serverId = 0; serverId < numberOfServers; serverId++) {
        clientIds.put(serverId, PortAssignment.unique());

        List<ServerAddress> addresses = new ArrayList<>();
        serverAddresses.put(serverId, addresses);

        for (int serverAddressId = 0; serverAddressId < numberOfServerAddresses; serverAddressId++) {
          addresses.add(new ServerAddress(hostName));
        }

      }
    }

    private QuorumConfigBuilder(QuorumConfigBuilder otherBuilder) {
      this.numberOfServers = otherBuilder.clientIds.size();
      this.clientIds.putAll(otherBuilder.clientIds);
      this.hostName = otherBuilder.hostName;
      for (int i : otherBuilder.serverAddresses.keySet()) {
        List<ServerAddress> clonedServerAddresses = otherBuilder.serverAddresses.get(i).stream()
          .map(ServerAddress::clone).collect(Collectors.toList());
        this.serverAddresses.put(i, clonedServerAddresses);
      }
    }

    private int getClientPort(int serverId) {
      return clientIds.get(serverId);
    }

    private ServerAddress getServerAddress(int serverId, int addressId) {
      return serverAddresses.get(serverId).get(addressId);
    }

    private QuorumConfigBuilder changeHostName(int serverId, int addressId, String hostName) {
      serverAddresses.get(serverId).get(addressId).setHost(hostName);
      return this;
    }

    private QuorumConfigBuilder changeQuorumPort(int serverId, int addressId, int quorumPort) {
      serverAddresses.get(serverId).get(addressId).setQuorumPort(quorumPort);
      return this;
    }

    private QuorumConfigBuilder changeElectionPort(int serverId, int addressId, int electionPort) {
      serverAddresses.get(serverId).get(addressId).setElectionPort(electionPort);
      return this;
    }

    private QuorumConfigBuilder addNewServerAddress(int serverId) {
      serverAddresses.get(serverId).add(new ServerAddress(hostName));
      return this;
    }

    private QuorumConfigBuilder deleteLastServerAddress(int serverId) {
      serverAddresses.get(serverId).remove(serverAddresses.get(serverId).size() - 1);
      return this;
    }

    private String build() {
      return String.join("\n", buildAsStringList());
    }

    private List<String> buildAsStringList() {
      List<String> result = new ArrayList<>(numberOfServers);

      for (int serverId = 0; serverId < numberOfServers; serverId++) {
        String s = serverAddresses.get(serverId).stream()
          .map(ServerAddress::toString)
          .collect(Collectors.joining("|"));

        result.add(String.format("server.%d=%s;%d", serverId, s, clientIds.get(serverId)));
      }

      return result;
    }
  }

  private static class ServerAddress {
    private String host;
    private int quorumPort;
    private int electionPort;

    private ServerAddress(String host) {
      this(host, PortAssignment.unique(), PortAssignment.unique());

    }

    private ServerAddress(String host, int quorumPort, int electionPort) {
      this.host = host;
      this.quorumPort = quorumPort;
      this.electionPort = electionPort;
    }

    private String getHost() {
      return host;
    }

    private void setHost(String host) {
      this.host = host;
    }

    private void setQuorumPort(int quorumPort) {
      this.quorumPort = quorumPort;
    }

    private void setElectionPort(int electionPort) {
      this.electionPort = electionPort;
    }

    @Override
    public ServerAddress clone() {
      return new ServerAddress(host, quorumPort, electionPort);
    }

    @Override
    public String toString() {
      return String.format("%s:%d:%d", host, quorumPort, electionPort);
    }
  }
}
