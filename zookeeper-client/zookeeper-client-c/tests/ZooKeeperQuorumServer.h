/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
#ifndef ZOOKEEPER_QUORUM_SERVER_H
#define ZOOKEEPER_QUORUM_SERVER_H

#include <stdint.h>
#include <string>
#include <vector>
#include <utility>

class ZooKeeperQuorumServer {
  public:
    ~ZooKeeperQuorumServer();
    typedef std::vector<std::pair<std::string, std::string> > tConfigPairs;
    static std::vector<ZooKeeperQuorumServer*> getCluster(uint32_t numServers);
    static std::vector<ZooKeeperQuorumServer*> getCluster(uint32_t numServers,
        tConfigPairs configs, /* Additional config options as a list of key/value pairs. */
        std::string env       /* Additional environment variables when starting zkServer.sh. */);
    std::string getHostPort();
    uint32_t getClientPort();
    void start();
    void stop();
    bool isLeader();
    bool isFollower();
    std::string getServerString();

  private:
    ZooKeeperQuorumServer();
    ZooKeeperQuorumServer(uint32_t id, uint32_t numServers, std::string config = "",
                          std::string env = "");
    ZooKeeperQuorumServer(const ZooKeeperQuorumServer& that);
    const ZooKeeperQuorumServer& operator=(const ZooKeeperQuorumServer& that);
    void createConfigFile(std::string config = "");
    std::string getConfigFileName();
    void createDataDirectory();
    std::string getDataDirectory();
    static std::string getServerString(uint32_t id);
    std::string getMode();

    static const uint32_t SERVER_PORT_BASE = 2000;
    static const uint32_t ELECTION_PORT_BASE = 3000;
    static const uint32_t CLIENT_PORT_BASE = 4000;

    uint32_t id_;
    std::string env_;
    uint32_t numServers_;
    std::string root_;
};

#endif  // ZOOKEEPER_QUORUM_SERVER_H
