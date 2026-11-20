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
#include <netinet/in.h>
#include <vector>

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
    CPPUNIT_TEST(testFailoverTriesEveryCachedAddress);
    CPPUNIT_TEST(testFailedInterestSetsSafeOutputs);
    CPPUNIT_TEST(testResolverFailureBacksOff);
    CPPUNIT_TEST(testPartialResolutionKeepsConnection);
    CPPUNIT_TEST(testPartialResolutionWhileDisconnected);
    CPPUNIT_TEST(testAcceptedServerListBecomesCurrent);
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

    // Refuses each connect, and records the port of each attempt.
    class RefusingServer: public Mock_socket {
    public:
        RefusingServer() {
            connectReturns = -1;
            connectErrno = ECONNREFUSED;
        }
        virtual int callConnect(int s, const struct sockaddr *addr,
                socklen_t len) {
            ports.push_back(
                ntohs(((const struct sockaddr_in *)addr)->sin_port));
            return Mock_socket::callConnect(s, addr, len);
        }
        vector<int> ports;
    };

    static int portOf(const struct sockaddr_storage *addr) {
        return ntohs(((const struct sockaddr_in *)addr)->sin_port);
    }

#ifndef THREADED
    static int interestOnce(zhandle_t *zh) {
        int fd = -2;
        int interest = 0;
        timeval tv;
        return zookeeper_interest(zh, &fd, &interest, &tv);
    }
#endif

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

    // zoo_set_servers reports a resolver failure. The client keeps its
    // current server list, and it reconnects to the last good addresses.
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
        CPPUNIT_ASSERT_EQUAL(string("127.0.0.1:2121"), string(zh->hostname));
        CPPUNIT_ASSERT(zh->hostname_pending == 0);
        // A rejected list does not delay refreshes of the current list.
        CPPUNIT_ASSERT_EQUAL(0, zh->resolve_backoff_ms);

        int fd = -2;
        int interest = 0;
        timeval tv;
        int rc = zookeeper_interest(zh, &fd, &interest, &tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL((int)ZookeeperServer::FD, fd);
        CPPUNIT_ASSERT_EQUAL((int)ZOO_CONNECTING_STATE, zoo_state(zh));
    }

    // Failover must try each cached address in turn. Each connect failure
    // must move the cursor only one time, or a two-server list never
    // reaches the second server. The first pass after zookeeper_init is a
    // reconfig pass, because all addresses are new. Later passes are normal
    // passes. The test covers both kinds of pass.
    void testFailoverTriesEveryCachedAddress()
    {
        Mock_gettimeofday timeMock;
        RefusingServer refusingServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("127.0.0.1:2121,127.0.0.1:2122", watcher,
                TIMEOUT*1000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        CPPUNIT_ASSERT_EQUAL(2, (int)zh->addrs.count);
        Mock_getaddrinfo resolverOutage;

        // Some calls do not connect, because they apply the delay at the
        // end of the server list.
        for (int i = 0; i < 8; i++) {
            int fd = -2;
            int interest = 0;
            timeval tv;
            zookeeper_interest(zh, &fd, &interest, &tv);
        }
        CPPUNIT_ASSERT(refusingServer.ports.size() >= 5);
        for (size_t i = 0; i < refusingServer.ports.size(); i++) {
            CPPUNIT_ASSERT_EQUAL(i % 2 == 0 ? 2121 : 2122,
                    refusingServer.ports[i]);
        }
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
            rc = zookeeper_interest(zh, &fd, &interest, &tv);
            CPPUNIT_ASSERT_EQUAL((int)ZSYSTEMERROR, rc);
            CPPUNIT_ASSERT_EQUAL(2, resolverOutage.callCounter);
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

    // A failed lookup delays the next periodic lookup, also while the client
    // is not connected. The delay starts when the slow lookup ends, and it
    // doubles after each failed lookup. A complete lookup clears it.
    void testResolverFailureBacksOff()
    {
        Mock_gettimeofday timeMock;
        RefusingServer refusingServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("127.0.0.1:2121", watcher, TIMEOUT*1000,
                TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        Mock_getaddrinfo resolverOutage;
        resolverOutage.clock = &timeMock;
        resolverOutage.lookupMillis = 5000;

        // Each lookup takes 5 seconds and fails.
        interestOnce(zh);
        CPPUNIT_ASSERT_EQUAL(1, resolverOutage.callCounter);
        CPPUNIT_ASSERT_EQUAL(1000, zh->resolve_backoff_ms);
        CPPUNIT_ASSERT_EQUAL(-1, (int)zh->fd->sock);

        // The wait counts from the end of the lookup.
        interestOnce(zh);
        timeMock.millitick(900);
        interestOnce(zh);
        CPPUNIT_ASSERT_EQUAL(1, resolverOutage.callCounter);

        timeMock.millitick(200);
        interestOnce(zh);
        CPPUNIT_ASSERT_EQUAL(2, resolverOutage.callCounter);
        CPPUNIT_ASSERT_EQUAL(2000, zh->resolve_backoff_ms);

        timeMock.millitick(1500);
        interestOnce(zh);
        CPPUNIT_ASSERT_EQUAL(2, resolverOutage.callCounter);
        timeMock.millitick(600);
        interestOnce(zh);
        CPPUNIT_ASSERT_EQUAL(3, resolverOutage.callCounter);
        CPPUNIT_ASSERT_EQUAL(4000, zh->resolve_backoff_ms);
        CPPUNIT_ASSERT_EQUAL(1, (int)zh->addrs.count);

        // The resolver recovers. A complete lookup clears the wait.
        resolverOutage.failNode = "zk1.example.com";
        resolverOutage.lookupMillis = 0;
        timeMock.millitick(4100);
        int before = resolverOutage.callCounter;
        interestOnce(zh);
        CPPUNIT_ASSERT(resolverOutage.callCounter > before);
        CPPUNIT_ASSERT_EQUAL(0, zh->resolve_backoff_ms);
    }

    // While the client is connected, a partial periodic refresh keeps the
    // whole cached list. Thus the client keeps its connection to a host
    // that does not resolve.
    void testPartialResolutionKeepsConnection()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("127.0.0.1:2121,127.0.0.2:2122", watcher,
                TIMEOUT*1000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        CPPUNIT_ASSERT_EQUAL(2, (int)zh->addrs.count);
        forceConnected(zh, &timeMock.tv);
        for (uint32_t i = 0; i < zh->addrs.count; i++) {
            if (portOf(&zh->addrs.data[i]) == 2122) {
                zh->addr_cur = zh->addrs.data[i];
            }
        }
        CPPUNIT_ASSERT_EQUAL(2122, portOf(&zh->addr_cur));

        Mock_getaddrinfo partialOutage;
        partialOutage.failNode = "127.0.0.2";
        int rc = interestOnce(zh);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL((int)ZookeeperServer::FD, (int)zh->fd->sock);
        CPPUNIT_ASSERT_EQUAL((int)ZOO_CONNECTED_STATE, zoo_state(zh));
        CPPUNIT_ASSERT_EQUAL(2, (int)zh->addrs.count);
        CPPUNIT_ASSERT_EQUAL(1000, zh->resolve_backoff_ms);
    }

    // While the client is not connected, a partial periodic refresh also
    // keeps the whole cached list, and it starts the wait.
    void testPartialResolutionWhileDisconnected()
    {
        Mock_gettimeofday timeMock;
        RefusingServer refusingServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("127.0.0.1:2121,127.0.0.2:2122", watcher,
                TIMEOUT*1000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        CPPUNIT_ASSERT_EQUAL(-1, (int)zh->fd->sock);
        Mock_getaddrinfo partialOutage;
        partialOutage.failNode = "127.0.0.2";
        interestOnce(zh);
        CPPUNIT_ASSERT_EQUAL(2, (int)zh->addrs.count);
        CPPUNIT_ASSERT_EQUAL(1000, zh->resolve_backoff_ms);
    }

    // A server list becomes current if only some of its hosts resolve.
    // The client then uses the addresses that resolved.
    void testAcceptedServerListBecomesCurrent()
    {
        CloseFinally guard(&zh);

        zh = zookeeper_init("127.0.0.1:2121", watcher, TIMEOUT*1000,
                TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        Mock_getaddrinfo partialOutage;
        partialOutage.failNode = "127.0.0.3";
        CPPUNIT_ASSERT_EQUAL((int)ZOK,
                zoo_set_servers(zh, "127.0.0.2:2122,127.0.0.3:2123"));
        CPPUNIT_ASSERT_EQUAL(string("127.0.0.2:2122,127.0.0.3:2123"),
                string(zh->hostname));
        CPPUNIT_ASSERT(zh->hostname_pending == 0);
        CPPUNIT_ASSERT_EQUAL(1, (int)zh->addrs.count);
        CPPUNIT_ASSERT_EQUAL(2122, portOf(&zh->addrs.data[0]));
        CPPUNIT_ASSERT_EQUAL(0, zh->resolve_backoff_ms);
    }
#endif
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_resolverFailure);
