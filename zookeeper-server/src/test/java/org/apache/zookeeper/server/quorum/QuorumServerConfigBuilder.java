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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.zookeeper.PortAssignment;


/*
 * Helper class to build / change Quorum Config String, like:
 * server.1=127.0.0.1:11228:11231|127.0.0.1:11230:11229;11227
 * server.2=127.0.0.1:11338:11331|127.0.0.1:11330:11229;11337
 *
 */
public class QuorumServerConfigBuilder {

  // map of (serverId -> clientPort)
  private final Map<Integer, Integer> clientIds = new HashMap<>();

  // map of (serverId -> (ServerAddress=host,quorumPort,electionPort) )
  private final Map<Integer, List<ServerAddress>> serverAddresses = new HashMap<>();
  private final String hostName;
  private final int numberOfServers;

  public QuorumServerConfigBuilder(String hostName, int numberOfServers, int numberOfServerAddresses) {
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

  public QuorumServerConfigBuilder(QuorumServerConfigBuilder otherBuilder) {
    this.numberOfServers = otherBuilder.clientIds.size();
    this.clientIds.putAll(otherBuilder.clientIds);
    this.hostName = otherBuilder.hostName;
    for (int i : otherBuilder.serverAddresses.keySet()) {
      List<ServerAddress> clonedServerAddresses = otherBuilder.serverAddresses.get(i).stream()
        .map(ServerAddress::clone).collect(Collectors.toList());
      this.serverAddresses.put(i, clonedServerAddresses);
    }
  }

  public int getClientPort(int serverId) {
    return clientIds.get(serverId);
  }

  public ServerAddress getServerAddress(int serverId, int addressId) {
    return serverAddresses.get(serverId).get(addressId);
  }

  public QuorumServerConfigBuilder changeHostName(int serverId, int addressId, String hostName) {
    serverAddresses.get(serverId).get(addressId).setHost(hostName);
    return this;
  }

  public QuorumServerConfigBuilder changeQuorumPort(int serverId, int addressId, int quorumPort) {
    serverAddresses.get(serverId).get(addressId).setQuorumPort(quorumPort);
    return this;
  }

  public QuorumServerConfigBuilder changeElectionPort(int serverId, int addressId, int electionPort) {
    serverAddresses.get(serverId).get(addressId).setElectionPort(electionPort);
    return this;
  }

  public QuorumServerConfigBuilder addNewServerAddress(int serverId) {
    serverAddresses.get(serverId).add(new ServerAddress(hostName));
    return this;
  }

  public QuorumServerConfigBuilder deleteLastServerAddress(int serverId) {
    serverAddresses.get(serverId).remove(serverAddresses.get(serverId).size() - 1);
    return this;
  }

  public String build() {
    return String.join("\n", buildAsStringList());
  }

  public List<String> buildAsStringList() {
    List<String> result = new ArrayList<>(numberOfServers);

    for (int serverId = 0; serverId < numberOfServers; serverId++) {
      String s = serverAddresses.get(serverId).stream()
        .map(ServerAddress::toString)
        .collect(Collectors.joining("|"));

      result.add(String.format("server.%d=%s;%d", serverId, s, clientIds.get(serverId)));
    }

    return result;
  }

  public static class ServerAddress {
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

    public String getHost() {
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

