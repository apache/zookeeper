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
#include "CppAssertHelper.h"

#include <deque>
#include <set>
#include <sstream>
#include <string>
#include <vector>

#include "ZKMocks.h"
#include <proto.h>

using namespace std;

// SetAuth reply. The server sends only a reply header.
class AuthResponse: public Response
{
public:
    AuthResponse(int rc): rc_(rc) {}
    virtual string toString() const {
        oarchive* oa = create_buffer_oarchive();
        ReplyHeader h = {AUTH_XID, 0, rc_};
        serialize_ReplyHeader(oa, "hdr", &h);
        int32_t len = htonl(get_buffer_len(oa));
        string res((char*)&len, sizeof(len));
        res.append(get_buffer(oa), get_buffer_len(oa));
        close_buffer_oarchive(&oa, 1);
        return res;
    }
private:
    int rc_;
};

// Mock server that records SetAuth frames in arrival order. Accepts the
// digest scheme and rejects all others. Tests can hold replies and release
// them in the same order.
class AuthServer: public ZookeeperServer
{
public:
    AuthServer(): holdReplies_(false) {}

    virtual void onMessageReceived(const RequestHeader& rh, iarchive* ia) {
        if (rh.type != ZOO_SETAUTH_OP) {
            return;
        }
        AuthPacket pkt;
        deserialize_AuthPacket(ia, "req", &pkt);
        string cred;
        if (pkt.auth.buff != 0 && pkt.auth.len > 0) {
            cred.assign(pkt.auth.buff, pkt.auth.len);
        }
        int rc = strcmp(pkt.scheme, "digest") == 0 ? ZOK : ZAUTHFAILED;
        bool reply;
        {
            synchronized(mx_);
            schemes_.push_back(pkt.scheme);
            creds_.push_back(cred);
            reply = !holdReplies_;
            if (!reply) {
                held_.push_back(rc);
            }
        }
        deallocate_AuthPacket(&pkt);
        if (reply) {
            addRecvResponse(new AuthResponse(rc));
        }
    }

    void holdReplies() {
        synchronized(mx_);
        holdReplies_ = true;
    }

    void releaseReply() {
        int rc;
        {
            synchronized(mx_);
            CPPUNIT_ASSERT(!held_.empty());
            rc = held_.front();
            held_.pop_front();
        }
        addRecvResponse(new AuthResponse(rc));
    }

    vector<string> schemes() const {
        synchronized(mx_);
        return schemes_;
    }

    vector<string> creds() const {
        synchronized(mx_);
        return creds_;
    }

    size_t frameCount() const {
        synchronized(mx_);
        return schemes_.size();
    }

private:
    mutable Mutex mx_;
    bool holdReplies_;
    deque<int> held_;
    vector<string> schemes_;
    vector<string> creds_;
};

// Result of one zoo_add_auth completion.
struct AuthResult
{
    AuthResult(): calls(0), rc(ZAPIERROR) {}
    int calls;
    int rc;
};

static void authCompletion(int rc, const void *data)
{
    AuthResult *res = (AuthResult *)data;
    res->calls++;
    res->rc = rc;
}

class Zookeeper_authCompletion : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_authCompletion);
#ifndef THREADED
    CPPUNIT_TEST(testEachReplyCompletesItsOwnRequest);
    CPPUNIT_TEST(testRejectedCredentialIsNotReportedOk);
    CPPUNIT_TEST(testResentCredentialDoesNotCompleteNewRequest);
    CPPUNIT_TEST(testUnsolicitedAuthReplyIsIgnored);
#else
    CPPUNIT_TEST(testConcurrentAddAuthSendsEachCredential);
#endif
    CPPUNIT_TEST_SUITE_END();

    zhandle_t *zh;
    FILE *logfile;

    static void watcher(zhandle_t *, int, int, const char *, void *) {}

public:
    Zookeeper_authCompletion() {
        logfile = openlogfile("Zookeeper_authCompletion");
    }

    ~Zookeeper_authCompletion() {
        if (logfile) {
            fflush(logfile);
            fclose(logfile);
            logfile = 0;
        }
    }

    void setUp() {
        zoo_set_log_stream(logfile);
        zoo_deterministic_conn_order(0);
        zh = 0;
    }

    void tearDown() {
        zookeeper_close(zh);
    }

#ifndef THREADED
    // Drive the event loop. Each pass sends queued frames and reads at most
    // one reply. Stop if the handle becomes unrecoverable.
    void drive(int passes) {
        for (int i = 0; i < passes; i++) {
            int fd = 0;
            int interest = 0;
            timeval tv;
            int rc = zookeeper_interest(zh, &fd, &interest, &tv);
            if (rc != ZOK) {
                return;
            }
            rc = zookeeper_process(zh, interest);
            if (rc != ZOK && rc != ZNOTHING) {
                return;
            }
        }
    }

    // Complete a real handshake so session establishment sends stored
    // credentials.
    void connect() {
        int fd = 0;
        int interest = 0;
        timeval tv;
        int rc = zookeeper_interest(zh, &fd, &interest, &tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        rc = zookeeper_process(zh, interest);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL(ZOO_ASSOCIATING_STATE, zoo_state(zh));
        rc = zookeeper_process(zh, interest);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTED_STATE, zoo_state(zh));
    }

    // Two credentials go out in the order of the zoo_add_auth calls. Each
    // completion gets the result of its own frame.
    void testEachReplyCompletesItsOwnRequest() {
        Mock_gettimeofday timeMock;
        AuthServer zkServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("localhost:2121", watcher, 10000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        forceConnected(zh);
        zkServer.holdReplies();

        AuthResult good, bad;
        CPPUNIT_ASSERT_EQUAL((int)ZOK,
                zoo_add_auth(zh, "digest", "user:pass", 9, authCompletion, &good));
        CPPUNIT_ASSERT_EQUAL((int)ZOK,
                zoo_add_auth(zh, "nosuchscheme", "x", 1, authCompletion, &bad));
        drive(1);

        vector<string> schemes = zkServer.schemes();
        CPPUNIT_ASSERT_EQUAL((size_t)2, schemes.size());
        CPPUNIT_ASSERT_EQUAL(string("digest"), schemes[0]);
        CPPUNIT_ASSERT_EQUAL(string("nosuchscheme"), schemes[1]);

        zkServer.releaseReply();
        drive(1);
        CPPUNIT_ASSERT_EQUAL(1, good.calls);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, good.rc);
        CPPUNIT_ASSERT_EQUAL(0, bad.calls);
        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTED_STATE, zoo_state(zh));

        zkServer.releaseReply();
        drive(1);
        CPPUNIT_ASSERT_EQUAL(1, good.calls);
        CPPUNIT_ASSERT_EQUAL(1, bad.calls);
        CPPUNIT_ASSERT_EQUAL((int)ZAUTHFAILED, bad.rc);
        CPPUNIT_ASSERT_EQUAL(ZOO_AUTH_FAILED_STATE, zoo_state(zh));
    }

    // Rejected credential first, then a valid credential. The rejected
    // completion never receives ZOK.
    void testRejectedCredentialIsNotReportedOk() {
        Mock_gettimeofday timeMock;
        AuthServer zkServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("localhost:2121", watcher, 10000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        forceConnected(zh);

        AuthResult bad, good;
        CPPUNIT_ASSERT_EQUAL((int)ZOK,
                zoo_add_auth(zh, "nosuchscheme", "x", 1, authCompletion, &bad));
        CPPUNIT_ASSERT_EQUAL((int)ZOK,
                zoo_add_auth(zh, "digest", "user:pass", 9, authCompletion, &good));
        drive(5);

        CPPUNIT_ASSERT_EQUAL(1, bad.calls);
        CPPUNIT_ASSERT_EQUAL((int)ZAUTHFAILED, bad.rc);
        vector<string> schemes = zkServer.schemes();
        CPPUNIT_ASSERT(schemes.size() >= 1);
        CPPUNIT_ASSERT_EQUAL(string("nosuchscheme"), schemes[0]);
        // The session failed on the first rejection, so the second
        // completion also receives ZAUTHFAILED.
        CPPUNIT_ASSERT_EQUAL(1, good.calls);
        CPPUNIT_ASSERT_EQUAL((int)ZAUTHFAILED, good.rc);
        CPPUNIT_ASSERT_EQUAL(ZOO_AUTH_FAILED_STATE, zoo_state(zh));
    }

    // Session establishment sends the stored credential. A later
    // zoo_add_auth call does not receive the result of that frame.
    void testResentCredentialDoesNotCompleteNewRequest() {
        Mock_gettimeofday timeMock;
        AuthServer zkServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("localhost:2121", watcher, 10000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        zkServer.holdReplies();

        // The handle is not connected. The handshake sends this credential
        // with its completion.
        AuthResult first, second;
        CPPUNIT_ASSERT_EQUAL((int)ZOK,
                zoo_add_auth(zh, "digest", "user:pass", 9, authCompletion, &first));
        CPPUNIT_ASSERT_EQUAL((size_t)0, zkServer.frameCount());
        connect();
        drive(1);
        CPPUNIT_ASSERT_EQUAL((size_t)1, zkServer.frameCount());

        // Add a second credential while the first reply is still in flight.
        CPPUNIT_ASSERT_EQUAL((int)ZOK,
                zoo_add_auth(zh, "nosuchscheme", "x", 1, authCompletion, &second));
        zkServer.releaseReply();
        drive(1);
        CPPUNIT_ASSERT_EQUAL(1, first.calls);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, first.rc);
        CPPUNIT_ASSERT_EQUAL(0, second.calls);

        CPPUNIT_ASSERT_EQUAL((size_t)2, zkServer.frameCount());
        zkServer.releaseReply();
        drive(1);
        CPPUNIT_ASSERT_EQUAL(1, first.calls);
        CPPUNIT_ASSERT_EQUAL(1, second.calls);
        CPPUNIT_ASSERT_EQUAL((int)ZAUTHFAILED, second.rc);
    }

    // A handle with no credentials receives auth replies. The client
    // ignores them. The session stays connected.
    void testUnsolicitedAuthReplyIsIgnored() {
        Mock_gettimeofday timeMock;
        AuthServer zkServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("localhost:2121", watcher, 10000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        forceConnected(zh);

        zkServer.addRecvResponse(new AuthResponse(ZOK));
        zkServer.addRecvResponse(new AuthResponse(ZAUTHFAILED));
        int rc = zookeeper_process(zh, ZOOKEEPER_READ);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        rc = zookeeper_process(zh, ZOOKEEPER_READ);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTED_STATE, zoo_state(zh));
    }
#else
    struct AddAuthArgs {
        zhandle_t *zh;
        int thread;
        int count;
    };

    static void *addAuthThread(void *arg) {
        AddAuthArgs *args = (AddAuthArgs *)arg;
        for (int i = 0; i < args->count; i++) {
            ostringstream cred;
            cred << "user" << args->thread << "-" << i << ":pass";
            string s = cred.str();
            zoo_add_auth(args->zh, "digest", s.c_str(), (int)s.size(), NULL, NULL);
        }
        return NULL;
    }

    struct FrameCountAtLeast {
        FrameCountAtLeast(const AuthServer& server, size_t count):
            server_(server), count_(count) {}
        bool operator()() const {
            return server_.frameCount() >= count_;
        }
        const AuthServer& server_;
        size_t count_;
    };

    // Concurrent zoo_add_auth calls. Each call sends its own credential
    // exactly once.
    void testConcurrentAddAuthSendsEachCredential() {
        const int THREADS = 8;
        const int PER_THREAD = 50;
        Mock_gettimeofday timeMock;
        AuthServer zkServer;
        Mock_poll pollMock(&zkServer, ZookeeperServer::FD);
        CloseFinally guard(&zh);

        zh = zookeeper_init("localhost:2121", watcher, 10000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh), 1000) < 1000);

        pthread_t threads[THREADS];
        AddAuthArgs args[THREADS];
        for (int t = 0; t < THREADS; t++) {
            args[t].zh = zh;
            args[t].thread = t;
            args[t].count = PER_THREAD;
            CPPUNIT_ASSERT_EQUAL(0,
                    pthread_create(&threads[t], NULL, addAuthThread, &args[t]));
        }
        for (int t = 0; t < THREADS; t++) {
            pthread_join(threads[t], NULL);
        }

        const size_t total = THREADS * PER_THREAD;
        ensureCondition(FrameCountAtLeast(zkServer, total), 5000);
        vector<string> creds = zkServer.creds();
        CPPUNIT_ASSERT_EQUAL(total, creds.size());
        set<string> unique(creds.begin(), creds.end());
        CPPUNIT_ASSERT_EQUAL(total, unique.size());
        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTED_STATE, zoo_state(zh));
    }
#endif
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_authCompletion);
