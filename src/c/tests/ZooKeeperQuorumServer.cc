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
#include "ZooKeeperQuorumServer.h"

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <vector>
#include <utility>
#include <unistd.h>

ZooKeeperQuorumServer::
ZooKeeperQuorumServer(uint32_t id, uint32_t numServers, std::string config, std::string env) :
    id_(id),
    env_(env),
    numServers_(numServers) {
    const char* root = getenv("ZKROOT");
    if (root == NULL) {
        assert(!"Environment variable 'ZKROOT' is not set");
    }
    root_ = root;
    createConfigFile(config);
    createDataDirectory();
    start();
}

ZooKeeperQuorumServer::
~ZooKeeperQuorumServer() {
    stop();
}

std::string ZooKeeperQuorumServer::
getHostPort() {
    std::stringstream ss;
    ss << "localhost:" << getClientPort();
    return ss.str();
}

uint32_t ZooKeeperQuorumServer::
getClientPort() {
    return CLIENT_PORT_BASE + id_;
}

void ZooKeeperQuorumServer::
start() {
    std::string command = root_ + "/bin/zkServer.sh start " +
                          getConfigFileName();
    if (!env_.empty()) {
        command = env_ + " " + command;
    }
    assert(system(command.c_str()) == 0);
}

void ZooKeeperQuorumServer::
stop() {
    std::string command = root_ + "/bin/zkServer.sh stop " +
                          getConfigFileName();
    assert(system(command.c_str()) == 0);
}

std::string ZooKeeperQuorumServer::
getMode() {
    char buf[1024];
    std::string result;
    std::string command = root_ + "/bin/zkServer.sh status " +
                          getConfigFileName();
    FILE* output = popen(command.c_str(), "r");
    do {
        if (fgets(buf, 1024, output) != NULL) {
            result += buf;
        }
    } while (!feof(output));
    pclose(output);
    if (result.find("Mode: leader") != std::string::npos) {
        return "leader";
    } else if (result.find("Mode: follower") != std::string::npos) {
        return "follower";
    } else {
        printf("%s\n", result.c_str());
        return "";
    }
}

bool ZooKeeperQuorumServer::
isLeader() {
    return getMode() == "leader";
}

bool ZooKeeperQuorumServer::
isFollower() {
    return getMode() == "follower";
}

void ZooKeeperQuorumServer::
createConfigFile(std::string config) {
    std::string command = "mkdir -p " + root_ + "/build/test/test-cppunit/conf";
    assert(system(command.c_str()) == 0);
    std::ofstream confFile;
    std::stringstream ss;
    ss << id_ << ".conf";
    std::string fileName = root_ + "/build/test/test-cppunit/conf/" + ss.str();
    confFile.open(fileName.c_str());
    confFile << "tickTime=2000\n";
    confFile << "clientPort=" << getClientPort() << "\n";
    confFile << "initLimit=5\n";
    confFile << "syncLimit=2\n";
    confFile << "dataDir=" << getDataDirectory() << "\n";
    for (int i = 0; i < numServers_; i++) {
        confFile << getServerString(i) << "\n";
    }
    // Append additional config, if any.
    if (!config.empty()) {
      confFile << config << std::endl;
    }
    confFile.close();
}

std::string ZooKeeperQuorumServer::
getConfigFileName() {
    std::stringstream ss;
    ss << id_ << ".conf";
    return root_ + "/build/test/test-cppunit/conf/" + ss.str();
}

void ZooKeeperQuorumServer::
createDataDirectory() {
    std::string dataDirectory = getDataDirectory();
    std::string command = "rm -rf " + dataDirectory;
    assert(system(command.c_str()) == 0);
    command = "mkdir -p " + dataDirectory;
    assert(system(command.c_str()) == 0);
    std::ofstream myidFile;
    std::string fileName = dataDirectory + "/myid";
    myidFile.open(fileName.c_str());
    myidFile << id_ << "\n";
    myidFile.close();
    setenv("ZOO_LOG_DIR", dataDirectory.c_str(), true);
}

std::string ZooKeeperQuorumServer::
getServerString() {
    return getServerString(id_);
}

std::string ZooKeeperQuorumServer::
getServerString(uint32_t id) {
    std::stringstream ss;
    ss << "server." << id << "=localhost:" << SERVER_PORT_BASE + id <<
          ":" << ELECTION_PORT_BASE + id << ":participant;localhost:" <<
          CLIENT_PORT_BASE + id;
    return ss.str();
}

std::string ZooKeeperQuorumServer::
getDataDirectory() {
    std::stringstream ss;
    ss << "data" << id_;
    return root_ + "/build/test/test-cppunit/" + ss.str();
}

std::vector<ZooKeeperQuorumServer*> ZooKeeperQuorumServer::
getCluster(uint32_t numServers) {
    std::vector<ZooKeeperQuorumServer*> cluster;
    for (int i = 0; i < numServers; i++) {
        cluster.push_back(new ZooKeeperQuorumServer(i, numServers));
    }

    // Wait until all the servers start, and fail if they don't start within 10
    // seconds.
    for (int i = 0; i < 10; i++) {
        int j = 0;
        for (; j < cluster.size(); j++) {
            if (cluster[j]->getMode() == "") {
                // The server hasn't started.
                sleep(1);
                break;
            }
        }
        if (j == cluster.size()) {
            return cluster;
        }
    }
    assert(!"The cluster didn't start for 10 seconds");
}

std::vector<ZooKeeperQuorumServer*> ZooKeeperQuorumServer::
getCluster(uint32_t numServers, ZooKeeperQuorumServer::tConfigPairs configs, std::string env) {
    std::vector<ZooKeeperQuorumServer*> cluster;
    std::string config;
    for (ZooKeeperQuorumServer::tConfigPairs::const_iterator iter = configs.begin(); iter != configs.end(); ++iter) {
        std::pair<std::string, std::string> pair = *iter;
        config += (pair.first + "=" + pair.second + "\n");
    }
    for (int i = 0; i < numServers; i++) {
        cluster.push_back(new ZooKeeperQuorumServer(i, numServers, config, env));
    }

    // Wait until all the servers start, and fail if they don't start within 10
    // seconds.
    for (int i = 0; i < 10; i++) {
        int j = 0;
        for (; j < cluster.size(); j++) {
            if (cluster[j]->getMode() == "") {
                // The server hasn't started.
                sleep(1);
                break;
            }
        }
        if (j == cluster.size()) {
            return cluster;
        }
    }
    assert(!"The cluster didn't start for 10 seconds");
}
