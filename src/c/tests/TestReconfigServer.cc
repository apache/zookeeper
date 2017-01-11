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
#include <algorithm>
#include <sstream>
#include <vector>
#include <utility>
#include <cppunit/extensions/HelperMacros.h>
#include <unistd.h>
#include "zookeeper.h"

#include "Util.h"
#include "ZooKeeperQuorumServer.h"

#ifdef THREADED
class TestReconfigServer : public CPPUNIT_NS::TestFixture {
    CPPUNIT_TEST_SUITE(TestReconfigServer);
    CPPUNIT_TEST(testNonIncremental);
    CPPUNIT_TEST(testRemoveConnectedFollower);
    CPPUNIT_TEST(testRemoveFollower);
    CPPUNIT_TEST(testReconfigFailureWithoutAuth);
    CPPUNIT_TEST(testReconfigFailureWithoutServerSuperuserPasswordConfigured);
    CPPUNIT_TEST_SUITE_END();

  public:
    TestReconfigServer();
    virtual ~TestReconfigServer();
    void setUp();
    void tearDown();
    void testNonIncremental();
    void testRemoveConnectedFollower();
    void testRemoveFollower();
    void testReconfigFailureWithoutAuth();
    void testReconfigFailureWithoutServerSuperuserPasswordConfigured();
  private:
    static const uint32_t NUM_SERVERS;
    FILE* logfile_;
    std::vector<ZooKeeperQuorumServer*> cluster_;
    int32_t getLeader();
    std::vector<int32_t> getFollowers();
    void parseConfig(char* buf, int len, std::vector<std::string>& servers,
                     std::string& version);
    bool waitForConnected(zhandle_t* zh, uint32_t timeout_sec);
    zhandle_t* connectFollowers(std::vector<int32_t> &followers);
};

const uint32_t TestReconfigServer::NUM_SERVERS = 3;

TestReconfigServer::
TestReconfigServer() :
    logfile_(openlogfile("TestReconfigServer")) {
    zoo_set_log_stream(logfile_);
}

TestReconfigServer::
~TestReconfigServer() {
    if (logfile_) {
        fflush(logfile_);
        fclose(logfile_);
        logfile_ = NULL;
    }
}

void TestReconfigServer::
setUp() {
    ZooKeeperQuorumServer::tConfigPairs configs;
    configs.push_back(std::make_pair("reconfigEnabled", "true"));
    cluster_ = ZooKeeperQuorumServer::getCluster(NUM_SERVERS, configs,
        "SERVER_JVMFLAGS=-Dzookeeper.DigestAuthenticationProvider.superDigest=super:D/InIHSb7yEEbrWz8b9l71RjZJU="/* password is test */);
}

void TestReconfigServer::
tearDown() {
    for (int i = 0; i < cluster_.size(); i++) {
        delete cluster_[i];
    }
    cluster_.clear();
}

int32_t TestReconfigServer::
getLeader() {
    for (int32_t i = 0; i < cluster_.size(); i++) {
        if (cluster_[i]->isLeader()) {
            return i;
        }
    }
    return -1;
}

std::vector<int32_t> TestReconfigServer::
getFollowers() {
    std::vector<int32_t> followers;
    for (int32_t i = 0; i < cluster_.size(); i++) {
        if (cluster_[i]->isFollower()) {
            followers.push_back(i);
        }
    }
    return followers;
}

void TestReconfigServer::
parseConfig(char* buf, int len, std::vector<std::string>& servers,
            std::string& version) {
    std::string config(buf, len);
    std::stringstream ss(config);
    std::string line;
    std::string serverPrefix("server.");
    std::string versionPrefix("version=");
    servers.clear();
    while(std::getline(ss, line, '\n')) {
        if (line.compare(0, serverPrefix.size(), serverPrefix) == 0) {
            servers.push_back(line);
        } else if (line.compare(0, versionPrefix.size(), versionPrefix) == 0) {
            version = line.substr(versionPrefix.size());
        }
    }
}

bool TestReconfigServer::
waitForConnected(zhandle_t* zh, uint32_t timeout_sec) {
    for (uint32_t i = 0; i < timeout_sec; i++) {
        if (zoo_state(zh) == ZOO_CONNECTED_STATE) {
            return true;
        }
        sleep(1);
    }
    return false;
}

/**
 * 1. Connect to the leader.
 * 2. Remove a follower using incremental reconfig.
 * 3. Add the follower back using incremental reconfig.
 */
void TestReconfigServer::
testRemoveFollower() {
    std::vector<std::string> servers;
    std::string version;
    struct Stat stat;
    int len = 1024;
    char buf[len];

    // get config from leader.
    int32_t leader = getLeader();
    CPPUNIT_ASSERT(leader >= 0);
    std::string host = cluster_[leader]->getHostPort();
    zhandle_t* zk = zookeeper_init(host.c_str(), NULL, 10000, NULL, NULL, 0);
    CPPUNIT_ASSERT_EQUAL(true, waitForConnected(zk, 10));
    CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_getconfig(zk, 0, buf, &len, &stat));
    CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_add_auth(zk, "digest", "super:test", 10, NULL,(void*)ZOK));
    // check if all the servers are listed in the config.
    parseConfig(buf, len, servers, version);
    // initially should be 1<<32, which is 0x100000000. This is the zxid
    // of the first NEWLEADER message, used as the initial version
    CPPUNIT_ASSERT_EQUAL(std::string("100000000"), version);
    CPPUNIT_ASSERT_EQUAL(NUM_SERVERS, (uint32_t)(servers.size()));
    for (int i = 0; i < cluster_.size(); i++) {
        CPPUNIT_ASSERT(std::find(servers.begin(), servers.end(),
                       cluster_[i]->getServerString()) != servers.end());
    }

    // remove a follower.
    std::vector<int32_t> followers = getFollowers();
    len = 1024;
    CPPUNIT_ASSERT_EQUAL(NUM_SERVERS - 1,
                         (uint32_t)(followers.size()));
    std::stringstream ss;
    ss << followers[0];
    int rc = zoo_reconfig(zk, NULL, ss.str().c_str(), NULL, -1, buf, &len,
                          &stat);
    CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
    parseConfig(buf, len, servers, version);
    CPPUNIT_ASSERT_EQUAL(std::string("100000002"), version);
    CPPUNIT_ASSERT_EQUAL(NUM_SERVERS - 1, (uint32_t)(servers.size()));
    for (int i = 0; i < cluster_.size(); i++) {
        if (i == followers[0]) {
            continue;
        }
        CPPUNIT_ASSERT(std::find(servers.begin(), servers.end(),
                       cluster_[i]->getServerString()) != servers.end());
    }

    // add the follower back.
    len = 1024;
    std::string serverString = cluster_[followers[0]]->getServerString();
    rc = zoo_reconfig(zk, serverString.c_str(), NULL, NULL, -1, buf, &len,
                          &stat);
    CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
    parseConfig(buf, len, servers, version);
    CPPUNIT_ASSERT_EQUAL(NUM_SERVERS, (uint32_t)(servers.size()));
    for (int i = 0; i < cluster_.size(); i++) {
        CPPUNIT_ASSERT(std::find(servers.begin(), servers.end(),
                       cluster_[i]->getServerString()) != servers.end());
    }
    zookeeper_close(zk);
}

/**
 * 1. Connect to the leader.
 * 2. Remove a follower using non-incremental reconfig.
 * 3. Add the follower back using non-incremental reconfig.
 */
void TestReconfigServer::
testNonIncremental() {
    std::vector<std::string> servers;
    std::string version;
    struct Stat stat;
    int len = 1024;
    char buf[len];

    // get config from leader.
    int32_t leader = getLeader();
    CPPUNIT_ASSERT(leader >= 0);
    std::string host = cluster_[leader]->getHostPort();
    zhandle_t* zk = zookeeper_init(host.c_str(), NULL, 10000, NULL, NULL, 0);
    CPPUNIT_ASSERT_EQUAL(true, waitForConnected(zk, 10));
    CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_getconfig(zk, 0, buf, &len, &stat));
    CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_add_auth(zk, "digest", "super:test", 10, NULL,(void*)ZOK));

    // check if all the servers are listed in the config.
    parseConfig(buf, len, servers, version);
    // initially should be 1<<32, which is 0x100000000. This is the zxid
    // of the first NEWLEADER message, used as the initial version
    CPPUNIT_ASSERT_EQUAL(std::string("100000000"), version);
    CPPUNIT_ASSERT_EQUAL(NUM_SERVERS, (uint32_t)(servers.size()));
    for (int i = 0; i < cluster_.size(); i++) {
        CPPUNIT_ASSERT(std::find(servers.begin(), servers.end(),
                       cluster_[i]->getServerString()) != servers.end());
    }

    // remove a follower.
    std::vector<int32_t> followers = getFollowers();
    len = 1024;
    CPPUNIT_ASSERT_EQUAL(NUM_SERVERS - 1,
                         (uint32_t)(followers.size()));
    std::stringstream ss;
    for (int i = 1; i < followers.size(); i++) {
      ss << cluster_[followers[i]]->getServerString() << ",";
    }
    ss << cluster_[leader]->getServerString();

    int rc = zoo_reconfig(zk, NULL, NULL, ss.str().c_str(), -1, buf, &len,
                          &stat);
    CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
    parseConfig(buf, len, servers, version);
    CPPUNIT_ASSERT_EQUAL(std::string("100000002"), version);
    CPPUNIT_ASSERT_EQUAL(NUM_SERVERS - 1, (uint32_t)(servers.size()));
    for (int i = 0; i < cluster_.size(); i++) {
        if (i == followers[0]) {
            continue;
        }
        CPPUNIT_ASSERT(std::find(servers.begin(), servers.end(),
                       cluster_[i]->getServerString()) != servers.end());
    }

    // add the follower back.
    len = 1024;
    ss.str("");
    for (int i = 0; i < cluster_.size(); i++) {
      ss << cluster_[i]->getServerString() << ",";
    }
    rc = zoo_reconfig(zk, NULL, NULL, ss.str().c_str(), -1, buf, &len,
                          &stat);
    CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
    parseConfig(buf, len, servers, version);
    CPPUNIT_ASSERT_EQUAL(NUM_SERVERS, (uint32_t)(servers.size()));
    for (int i = 0; i < cluster_.size(); i++) {
        CPPUNIT_ASSERT(std::find(servers.begin(), servers.end(),
                       cluster_[i]->getServerString()) != servers.end());
    }
    zookeeper_close(zk);
}

zhandle_t* TestReconfigServer::
connectFollowers(std::vector<int32_t> &followers) {
    std::stringstream ss;
    int32_t leader = getLeader();
    CPPUNIT_ASSERT(leader >= 0);
    CPPUNIT_ASSERT_EQUAL(NUM_SERVERS - 1, (uint32_t)(followers.size()));
    for (int i = 0; i < followers.size(); i++) {
        ss << cluster_[followers[i]]->getHostPort() << ",";
    }
    ss << cluster_[leader]->getHostPort();
    std::string hosts = ss.str().c_str();
    zoo_deterministic_conn_order(true);
    zhandle_t* zk = zookeeper_init(hosts.c_str(), NULL, 10000, NULL, NULL, 0);
    CPPUNIT_ASSERT_EQUAL(true, waitForConnected(zk, 10));

    std::string connectedHost(zoo_get_current_server(zk));
    std::string portString = connectedHost.substr(connectedHost.find(":") + 1);
    uint32_t port;
    std::istringstream (portString) >> port;
    CPPUNIT_ASSERT_EQUAL(cluster_[followers[0]]->getClientPort(), port);
    return zk;
}

/**
 * 1. Connect to a follower.
 * 2. Remove the follower the client is connected to.
 */
void TestReconfigServer::
testRemoveConnectedFollower() {
    std::vector<std::string> servers;
    std::string version;
    struct Stat stat;
    int len = 1024;
    char buf[len];

    // connect to a follower.
    std::stringstream ss;
    std::vector<int32_t> followers = getFollowers();
    zhandle_t* zk = connectFollowers(followers);
    CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_add_auth(zk, "digest", "super:test", 10, NULL,(void*)ZOK));

    // remove the follower.
    len = 1024;
    ss.str("");
    ss << followers[0];
    zoo_reconfig(zk, NULL, ss.str().c_str(), NULL, -1, buf, &len, &stat);
    CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_getconfig(zk, 0, buf, &len, &stat));
    parseConfig(buf, len, servers, version);
    CPPUNIT_ASSERT_EQUAL(NUM_SERVERS - 1, (uint32_t)(servers.size()));
    for (int i = 0; i < cluster_.size(); i++) {
        if (i == followers[0]) {
            continue;
        }
        CPPUNIT_ASSERT(std::find(servers.begin(), servers.end(),
                       cluster_[i]->getServerString()) != servers.end());
    }
    zookeeper_close(zk);
}

/**
 * ZOOKEEPER-2014: only admin or users who are explicitly granted permission can do reconfig.
 */
void TestReconfigServer::
testReconfigFailureWithoutAuth() {
    std::vector<std::string> servers;
    std::string version;
    struct Stat stat;
    int len = 1024;
    char buf[len];

    // connect to a follower.
    std::stringstream ss;
    std::vector<int32_t> followers = getFollowers();
    zhandle_t* zk = connectFollowers(followers);

    // remove the follower.
    len = 1024;
    ss.str("");
    ss << followers[0];
    // No auth, should fail.
    CPPUNIT_ASSERT_EQUAL((int)ZNOAUTH, zoo_reconfig(zk, NULL, ss.str().c_str(), NULL, -1, buf, &len, &stat));
    // Wrong auth, should fail.
    CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_add_auth(zk, "digest", "super:wrong", 11, NULL,(void*)ZOK));
    CPPUNIT_ASSERT_EQUAL((int)ZNOAUTH, zoo_reconfig(zk, NULL, ss.str().c_str(), NULL, -1, buf, &len, &stat));
    // Right auth, should pass.
    CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_add_auth(zk, "digest", "super:test", 10, NULL,(void*)ZOK));
    CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_reconfig(zk, NULL, ss.str().c_str(), NULL, -1, buf, &len, &stat));
    CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_getconfig(zk, 0, buf, &len, &stat));
    parseConfig(buf, len, servers, version);
    CPPUNIT_ASSERT_EQUAL(NUM_SERVERS - 1, (uint32_t)(servers.size()));
    for (int i = 0; i < cluster_.size(); i++) {
        if (i == followers[0]) {
            continue;
        }
        CPPUNIT_ASSERT(std::find(servers.begin(), servers.end(),
                       cluster_[i]->getServerString()) != servers.end());
    }
    zookeeper_close(zk);
}

void TestReconfigServer::
testReconfigFailureWithoutServerSuperuserPasswordConfigured() {
    std::vector<std::string> servers;
    std::string version;
    struct Stat stat;
    int len = 1024;
    char buf[len];

    // Create a new quorum with the super user's password not configured.
    tearDown();
    ZooKeeperQuorumServer::tConfigPairs configs;
    configs.push_back(std::make_pair("reconfigEnabled", "true"));
    cluster_ = ZooKeeperQuorumServer::getCluster(NUM_SERVERS, configs, "");

    // connect to a follower.
    std::stringstream ss;
    std::vector<int32_t> followers = getFollowers();
    zhandle_t* zk = connectFollowers(followers);

    // remove the follower.
    len = 1024;
    ss.str("");
    ss << followers[0];
    // All cases should fail as server ensemble was not configured with the super user's password.
    CPPUNIT_ASSERT_EQUAL((int)ZNOAUTH, zoo_reconfig(zk, NULL, ss.str().c_str(), NULL, -1, buf, &len, &stat));
    CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_add_auth(zk, "digest", "super:", 11, NULL,(void*)ZOK));
    CPPUNIT_ASSERT_EQUAL((int)ZNOAUTH, zoo_reconfig(zk, NULL, ss.str().c_str(), NULL, -1, buf, &len, &stat));
    CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_add_auth(zk, "digest", "super:test", 10, NULL,(void*)ZOK));
    CPPUNIT_ASSERT_EQUAL((int)ZNOAUTH, zoo_reconfig(zk, NULL, ss.str().c_str(), NULL, -1, buf, &len, &stat));
    zookeeper_close(zk);
}

CPPUNIT_TEST_SUITE_REGISTRATION(TestReconfigServer);
#endif
