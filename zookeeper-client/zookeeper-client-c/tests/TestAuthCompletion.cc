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

#include <cstring>
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

    // Discard held replies. They belong to a connection that is gone.
    void dropHeldReplies() {
        synchronized(mx_);
        held_.clear();
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

#ifdef THREADED
// The I/O thread runs completions and the test thread reads the results.
// A mutex guards the results, and a counter tells the test when to read.
static Mutex authResultsMx;
static AtomicInt authCompletions;

static void lockedAuthCompletion(int rc, const void *data)
{
    synchronized(authResultsMx);
    authCompletion(rc, data);
    ++authCompletions;
}

struct CompletionsAtLeast {
    CompletionsAtLeast(int count): count_(count) {}
    bool operator()() const {
        return authCompletions.get() >= count_;
    }
    int count_;
};

// The log callback has no context argument. The race test uses these
// globals. raceMx keeps the callback from reading raceHandle before
// zookeeper_init2 returns.
static Mutex raceMx;
static zhandle_t *raceHandle;
static bool raceFired;
static int raceRc;
static AuthResult raceResult;

// Call zoo_add_auth on the I/O thread in the window where the session is
// connected but send_auth_info has not run.
static void addAuthDuringEstablishment(const char *message)
{
    zhandle_t *zh;
    if (strstr(message, "session establishment complete") == NULL) {
        return;
    }
    {
        synchronized(raceMx);
        if (raceHandle == NULL || raceFired) {
            return;
        }
        raceFired = true;
        zh = raceHandle;
    }
    raceRc = zoo_add_auth(zh, "digest", "race:pass", 9,
            lockedAuthCompletion, &raceResult);
}
#endif

class Zookeeper_authCompletion : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_authCompletion);
#ifndef THREADED
    CPPUNIT_TEST(testEachReplyCompletesItsOwnRequest);
    CPPUNIT_TEST(testRejectedCredentialIsNotReportedOk);
    CPPUNIT_TEST(testResentCredentialDoesNotCompleteNewRequest);
    CPPUNIT_TEST(testUnsolicitedAuthReplyIsIgnored);
    CPPUNIT_TEST(testStoredCredentialsCompleteSeparately);
    CPPUNIT_TEST(testReconnectResendDoesNotCompleteNewRequest);
#else
    CPPUNIT_TEST(testConcurrentAddAuthSendsEachCredential);
    CPPUNIT_TEST(testAddAuthDuringSessionEstablishment);
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

    // Drive the event loop after a connection loss until the session is
    // established again. The first pass can only clear the reconnect delay.
    void reconnect() {
        for (int i = 0; i < 10 && zoo_state(zh) != ZOO_CONNECTED_STATE; i++) {
            int fd = 0;
            int interest = 0;
            timeval tv;
            if (zookeeper_interest(zh, &fd, &interest, &tv) == ZOK) {
                zookeeper_process(zh, interest);
            }
        }
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

    // Session establishment sends two stored credentials. The server
    // accepts one and rejects the other. Each completion gets the result of
    // its own frame.
    void testStoredCredentialsCompleteSeparately() {
        Mock_gettimeofday timeMock;
        AuthServer zkServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("localhost:2121", watcher, 10000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        zkServer.holdReplies();

        AuthResult bad, good;
        CPPUNIT_ASSERT_EQUAL((int)ZOK,
                zoo_add_auth(zh, "nosuchscheme", "x", 1, authCompletion, &bad));
        CPPUNIT_ASSERT_EQUAL((int)ZOK,
                zoo_add_auth(zh, "digest", "user:pass", 9, authCompletion, &good));
        connect();
        drive(2);

        // Session establishment puts each frame at the front of the send
        // queue. The last stored credential goes out first.
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

    // The connection drops while two stored credentials wait for replies.
    // The reconnect sends both credentials again without completions. A
    // new zoo_add_auth call does not receive the result of a resent frame.
    void testReconnectResendDoesNotCompleteNewRequest() {
        Mock_gettimeofday timeMock;
        AuthServer zkServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("localhost:2121", watcher, 10000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        zkServer.holdReplies();

        AuthResult first, second, fresh;
        CPPUNIT_ASSERT_EQUAL((int)ZOK,
                zoo_add_auth(zh, "digest", "user1:pass", 10, authCompletion, &first));
        CPPUNIT_ASSERT_EQUAL((int)ZOK,
                zoo_add_auth(zh, "digest", "user2:pass", 10, authCompletion, &second));
        connect();
        drive(2);
        CPPUNIT_ASSERT_EQUAL((size_t)2, zkServer.frameCount());

        // The pending completions receive the connection-loss code.
        zkServer.dropHeldReplies();
        zkServer.setConnectionLost();
        drive(1);
        zkServer.connectionLost = false;
        CPPUNIT_ASSERT_EQUAL(1, first.calls);
        CPPUNIT_ASSERT_EQUAL((int)ZCONNECTIONLOSS, first.rc);
        CPPUNIT_ASSERT_EQUAL(1, second.calls);
        CPPUNIT_ASSERT_EQUAL((int)ZCONNECTIONLOSS, second.rc);

        reconnect();
        drive(2);
        vector<string> creds = zkServer.creds();
        CPPUNIT_ASSERT_EQUAL((size_t)4, creds.size());
        set<string> resent(creds.begin() + 2, creds.end());
        CPPUNIT_ASSERT_EQUAL((size_t)1, resent.count("user1:pass"));
        CPPUNIT_ASSERT_EQUAL((size_t)1, resent.count("user2:pass"));

        CPPUNIT_ASSERT_EQUAL((int)ZOK,
                zoo_add_auth(zh, "nosuchscheme", "x", 1, authCompletion, &fresh));
        drive(1);
        CPPUNIT_ASSERT_EQUAL((size_t)5, zkServer.frameCount());

        zkServer.releaseReply();
        drive(1);
        zkServer.releaseReply();
        drive(1);
        CPPUNIT_ASSERT_EQUAL(0, fresh.calls);
        CPPUNIT_ASSERT_EQUAL(1, first.calls);
        CPPUNIT_ASSERT_EQUAL(1, second.calls);
        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTED_STATE, zoo_state(zh));

        zkServer.releaseReply();
        drive(1);
        CPPUNIT_ASSERT_EQUAL(1, fresh.calls);
        CPPUNIT_ASSERT_EQUAL((int)ZAUTHFAILED, fresh.rc);
    }
#else
    struct AddAuthArgs {
        zhandle_t *zh;
        int thread;
        int count;
        AuthResult *results;
        int failures;
    };

    static void *addAuthThread(void *arg) {
        AddAuthArgs *args = (AddAuthArgs *)arg;
        for (int i = 0; i < args->count; i++) {
            ostringstream cred;
            cred << "user" << args->thread << "-" << i << ":pass";
            string s = cred.str();
            if (zoo_add_auth(args->zh, "digest", s.c_str(), (int)s.size(),
                        lockedAuthCompletion, &args->results[i]) != ZOK) {
                args->failures++;
            }
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
    // exactly once, and each completion runs once with ZOK.
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

        authCompletions = 0;
        vector<AuthResult> results(THREADS * PER_THREAD);
        pthread_t threads[THREADS];
        AddAuthArgs args[THREADS];
        for (int t = 0; t < THREADS; t++) {
            args[t].zh = zh;
            args[t].thread = t;
            args[t].count = PER_THREAD;
            args[t].results = &results[t * PER_THREAD];
            args[t].failures = 0;
            CPPUNIT_ASSERT_EQUAL(0,
                    pthread_create(&threads[t], NULL, addAuthThread, &args[t]));
        }
        for (int t = 0; t < THREADS; t++) {
            pthread_join(threads[t], NULL);
        }
        for (int t = 0; t < THREADS; t++) {
            CPPUNIT_ASSERT_EQUAL(0, args[t].failures);
        }

        const size_t total = THREADS * PER_THREAD;
        ensureCondition(FrameCountAtLeast(zkServer, total), 5000);
        ensureCondition(CompletionsAtLeast((int)total), 5000);
        vector<string> creds = zkServer.creds();
        CPPUNIT_ASSERT_EQUAL(total, creds.size());
        set<string> unique(creds.begin(), creds.end());
        CPPUNIT_ASSERT_EQUAL(total, unique.size());
        {
            synchronized(authResultsMx);
            for (size_t i = 0; i < total; i++) {
                CPPUNIT_ASSERT_EQUAL(1, results[i].calls);
                CPPUNIT_ASSERT_EQUAL((int)ZOK, results[i].rc);
            }
        }
        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTED_STATE, zoo_state(zh));
    }

    // zoo_add_auth runs on the I/O thread after the session becomes
    // connected but before send_auth_info runs. This is the race with a
    // caller thread. send_auth_info must take back the queued frame and
    // send the credential once, with its completion. A second credential
    // then acts as a barrier for all earlier frames.
    void testAddAuthDuringSessionEstablishment() {
        Mock_gettimeofday timeMock;
        AuthServer zkServer;
        Mock_poll pollMock(&zkServer, ZookeeperServer::FD);
        CloseFinally guard(&zh);

        authCompletions = 0;
        raceResult = AuthResult();
        raceRc = ZAPIERROR;
        {
            synchronized(raceMx);
            raceFired = false;
            zh = zookeeper_init2("localhost:2121", watcher, 10000,
                    TEST_CLIENT_ID, 0, 0, addAuthDuringEstablishment);
            raceHandle = zh;
        }
        CPPUNIT_ASSERT(zh != 0);
        CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh), 1000) < 1000);
        ensureCondition(CompletionsAtLeast(1), 5000);

        AuthResult barrier;
        CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_add_auth(zh, "digest", "barrier:pass",
                12, lockedAuthCompletion, &barrier));
        ensureCondition(CompletionsAtLeast(2), 5000);
        {
            synchronized(raceMx);
            raceHandle = NULL;
        }

        CPPUNIT_ASSERT(raceFired);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, raceRc);
        vector<string> creds = zkServer.creds();
        CPPUNIT_ASSERT_EQUAL((size_t)2, creds.size());
        CPPUNIT_ASSERT_EQUAL(string("race:pass"), creds[0]);
        CPPUNIT_ASSERT_EQUAL(string("barrier:pass"), creds[1]);
        {
            synchronized(authResultsMx);
            CPPUNIT_ASSERT_EQUAL(1, raceResult.calls);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, raceResult.rc);
            CPPUNIT_ASSERT_EQUAL(1, barrier.calls);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, barrier.rc);
        }
        CPPUNIT_ASSERT_EQUAL(ZOO_CONNECTED_STATE, zoo_state(zh));
    }
#endif
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_authCompletion);
