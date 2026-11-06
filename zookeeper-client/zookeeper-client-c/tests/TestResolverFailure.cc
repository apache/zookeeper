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

#include <cppunit/extensions/HelperMacros.h>
#include <string.h>

extern "C" {
#include <src/addrvec.h>
}

#include "LibCMocks.h"
#include "ZKMocks.h"

using namespace std;

/*
 * These tests simulate a resolver outage after the client has resolved its
 * server list. The client must keep its last good addresses. It must send
 * pings, enforce the receive timeout, and reconnect to the cached addresses.
 * The tests use the single-threaded library, because they drive the event
 * loop directly.
 */
class Zookeeper_resolverFailure : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_resolverFailure);
#ifndef THREADED
    CPPUNIT_TEST(testResolverFailureKeepsPinging);
    CPPUNIT_TEST(testResolverFailureKeepsReceiveTimeout);
    CPPUNIT_TEST(testResolverFailureReconnectsToCachedAddress);
    CPPUNIT_TEST(testRejectedServerListKeepsReconnecting);
    CPPUNIT_TEST(testFailedInterestSetsSafeOutputs);
#endif
    CPPUNIT_TEST_SUITE_END();

    static const int TIMEOUT = 9; // session timeout in seconds

    zhandle_t *zh;
    FILE *logfile;

    static void watcher(zhandle_t *, int, int, const char *, void *) {}

    class PingCountingServer: public ZookeeperServer {
    public:
        PingCountingServer(): pingCount_(0) {}
        virtual void onMessageReceived(const RequestHeader& rh, iarchive* ia) {
            if (rh.type == ZOO_PING_OP) {
                pingCount_++;
            }
        }
        int pingCount_;
    };

public:
    Zookeeper_resolverFailure() {
        logfile = openlogfile("Zookeeper_resolverFailure");
    }

    ~Zookeeper_resolverFailure() {
        if (logfile) {
            fflush(logfile);
            fclose(logfile);
            logfile = 0;
        }
    }

    void setUp()
    {
        zoo_set_log_stream(logfile);
        zoo_deterministic_conn_order(1);
        zh = 0;
    }

    void tearDown()
    {
        zookeeper_close(zh);
        zh = 0;
    }

#ifndef THREADED
    // A resolver outage on a connected session must not stop the pings.
    void testResolverFailureKeepsPinging()
    {
        Mock_gettimeofday timeMock;
        PingCountingServer zkServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("127.0.0.1:2121", watcher, TIMEOUT*1000,
                TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        forceConnected(zh, &timeMock.tv);
        Mock_getaddrinfo resolverOutage;

        // A ping is due after one third of the session timeout.
        timeMock.tick(TIMEOUT/3 + 1);
        int fd = -2;
        int interest = 0;
        timeval tv;
        int rc = zookeeper_interest(zh, &fd, &interest, &tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT(resolverOutage.callCounter > 0);
        CPPUNIT_ASSERT_EQUAL((int)ZookeeperServer::FD, fd);
        CPPUNIT_ASSERT(interest & ZOOKEEPER_READ);

        rc = zookeeper_process(zh, interest);
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING, rc);
        CPPUNIT_ASSERT_EQUAL(1, zkServer.pingCount_);
        CPPUNIT_ASSERT_EQUAL((int)ZOO_CONNECTED_STATE, zoo_state(zh));
        CPPUNIT_ASSERT_EQUAL(1, (int)zh->addrs.count);
    }

    // A resolver outage must not hide a dead connection.
    void testResolverFailureKeepsReceiveTimeout()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("127.0.0.1:2121", watcher, TIMEOUT*1000,
                TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        forceConnected(zh, &timeMock.tv);
        Mock_getaddrinfo resolverOutage;

        // No data arrives for longer than two thirds of the session timeout.
        timeMock.tick(TIMEOUT);
        int fd = -2;
        int interest = 0;
        timeval tv;
        int rc = zookeeper_interest(zh, &fd, &interest, &tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOPERATIONTIMEOUT, rc);
        CPPUNIT_ASSERT_EQUAL(-1, (int)zh->fd->sock);
    }

    // A disconnected client must reconnect to the cached addresses.
    void testResolverFailureReconnectsToCachedAddress()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("127.0.0.1:2121", watcher, TIMEOUT*1000,
                TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        CPPUNIT_ASSERT_EQUAL(-1, (int)zh->fd->sock);
        Mock_getaddrinfo resolverOutage;

        int fd = -2;
        int interest = 0;
        timeval tv;
        int rc = zookeeper_interest(zh, &fd, &interest, &tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT(resolverOutage.callCounter > 0);
        CPPUNIT_ASSERT_EQUAL((int)ZookeeperServer::FD, fd);
        CPPUNIT_ASSERT_EQUAL((int)ZOO_CONNECTING_STATE, zoo_state(zh));
        CPPUNIT_ASSERT_EQUAL(0, memcmp(&zh->addr_cur, &zh->addrs.data[0],
                                       sizeof(zh->addr_cur)));
    }

    // zoo_set_servers reports a resolver failure, and the client still
    // reconnects to the last good addresses.
    void testRejectedServerListKeepsReconnecting()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("127.0.0.1:2121", watcher, TIMEOUT*1000,
                TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        Mock_getaddrinfo resolverOutage;
        CPPUNIT_ASSERT_EQUAL((int)ZSYSTEMERROR,
                zoo_set_servers(zh, "zk1.example.com:2121"));

        int fd = -2;
        int interest = 0;
        timeval tv;
        int rc = zookeeper_interest(zh, &fd, &interest, &tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL((int)ZookeeperServer::FD, fd);
        CPPUNIT_ASSERT_EQUAL((int)ZOO_CONNECTING_STATE, zoo_state(zh));
    }

    // Event loops such as do_io() ignore the return code. Thus an error
    // return must give values that are safe to pass to poll().
    void testFailedInterestSetsSafeOutputs()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("127.0.0.1:2121", watcher, TIMEOUT*1000,
                TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        forceConnected(zh, &timeMock.tv);

        // With no cached addresses, a resolver failure is still an error.
        {
            Mock_getaddrinfo resolverOutage;
            addrvec_free(&zh->addrs);
            int fd = 12345;
            int interest = ZOOKEEPER_READ | ZOOKEEPER_WRITE;
            timeval tv = { 0, 0 };
            int rc = zookeeper_interest(zh, &fd, &interest, &tv);
            CPPUNIT_ASSERT_EQUAL((int)ZSYSTEMERROR, rc);
            CPPUNIT_ASSERT_EQUAL(-1, fd);
            CPPUNIT_ASSERT_EQUAL(0, interest);
            CPPUNIT_ASSERT_EQUAL(TIMEOUT*1000/60,
                    (int)(tv.tv_sec*1000 + tv.tv_usec/1000));
        }

        // A terminal state also gives safe values.
        zh->state = ZOO_EXPIRED_SESSION_STATE;
        int fd = 12345;
        int interest = ZOOKEEPER_READ | ZOOKEEPER_WRITE;
        timeval tv = { 0, 0 };
        int rc = zookeeper_interest(zh, &fd, &interest, &tv);
        CPPUNIT_ASSERT_EQUAL((int)ZINVALIDSTATE, rc);
        CPPUNIT_ASSERT_EQUAL(-1, fd);
        CPPUNIT_ASSERT_EQUAL(0, interest);
        CPPUNIT_ASSERT(tv.tv_sec != 0 || tv.tv_usec != 0);
    }
#endif
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_resolverFailure);
