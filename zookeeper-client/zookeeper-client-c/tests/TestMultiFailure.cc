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

#include <string.h>
#include <string>

#include "ZKMocks.h"
#include <proto.h>

using namespace std;

/* A sentinel error code. No result slot holds it after a multi completes. */
static const int SENTINEL_ERR = 0x5a5a5a5a;

static Stat makeStat(int32_t version)
{
    Stat stat;
    memset(&stat, 0, sizeof(stat));
    stat.czxid = 0x1111;
    stat.mzxid = 0x2222;
    stat.version = version;
    stat.dataLength = 1;
    stat.pzxid = 0x3333;
    return stat;
}

static void fillSentinel(zoo_op_result_t *results, int count)
{
    for (int i = 0; i < count; i++) {
        results[i].err = SENTINEL_ERR;
    }
}

/* A reply that has only a ReplyHeader. Servers send such a reply when they
 * reject a request as a whole, for example ZNOTREADONLY. */
class HeaderOnlyResponse: public Response
{
public:
    HeaderOnlyResponse(int rc): xid_(0), rc_(rc) {}
    virtual void setXID(int32_t xid) { xid_ = xid; }
    virtual string toString() const {
        oarchive *oa = create_buffer_oarchive();
        ReplyHeader h = {xid_, 1, rc_};
        serialize_ReplyHeader(oa, "hdr", &h);
        int32_t len = htonl(get_buffer_len(oa));
        string res((char*)&len, sizeof(len));
        res.append(get_buffer(oa), get_buffer_len(oa));
        close_buffer_oarchive(&oa, 1);
        return res;
    }
private:
    int32_t xid_;
    int rc_;
};

/* The reply that a server sends for a multi with a container create and a
 * set data. The server answers the container create with a Create2Response,
 * which has a path and a stat. */
class ContainerMultiResponse: public Response
{
public:
    ContainerMultiResponse(const char *path, const Stat& createStat,
            const Stat& setStat)
        : xid_(0), path_(path), createStat_(createStat), setStat_(setStat) {}
    virtual void setXID(int32_t xid) { xid_ = xid; }
    virtual string toString() const {
        oarchive *oa = create_buffer_oarchive();
        ReplyHeader h = {xid_, 1, ZOK};
        serialize_ReplyHeader(oa, "hdr", &h);

        MultiHeader create = {ZOO_CREATE2_OP, 0, 0};
        serialize_MultiHeader(oa, "multiheader", &create);
        Create2Response cr;
        cr.path = (char*)path_.c_str();
        cr.stat = createStat_;
        serialize_Create2Response(oa, "reply", &cr);

        MultiHeader set = {ZOO_SETDATA_OP, 0, 0};
        serialize_MultiHeader(oa, "multiheader", &set);
        SetDataResponse sr;
        sr.stat = setStat_;
        serialize_SetDataResponse(oa, "reply", &sr);

        MultiHeader done = {-1, 1, -1};
        serialize_MultiHeader(oa, "multiheader", &done);

        int32_t len = htonl(get_buffer_len(oa));
        string res((char*)&len, sizeof(len));
        res.append(get_buffer(oa), get_buffer_len(oa));
        close_buffer_oarchive(&oa, 1);
        return res;
    }
private:
    int32_t xid_;
    string path_;
    Stat createStat_;
    Stat setStat_;
};

#ifdef THREADED
/* A mock server that drops the connection when a multi request arrives. The
 * multi is then in flight when the connection fails. */
class DropOnMultiServer: public ZookeeperServer
{
public:
    virtual void onMessageReceived(const RequestHeader& rh, iarchive*) {
        if (rh.type == ZOO_MULTI_OP) {
            setServerDown(0);
            setConnectionLost();
        }
    }
};

/* Runs zoo_multi() on its own thread, so that a hang fails the test and does
 * not block the test run. */
struct MultiCall {
    zhandle_t *zh;
    int count;
    const zoo_op_t *ops;
    zoo_op_result_t *results;
    int rc;
    AtomicInt done;
    pthread_t thread;

    static void *run(void *arg) {
        MultiCall *call = (MultiCall*)arg;
        call->rc = zoo_multi(call->zh, call->count, call->ops, call->results);
        call->done = 1;
        return 0;
    }
    void start() {
        rc = ZAPIERROR;
        done = 0;
        pthread_create(&thread, 0, run, this);
    }
    bool operator()() const { return done.get() != 0; }
};
#endif

class Zookeeper_multiFailure : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_multiFailure);
    CPPUNIT_TEST(testValidationFailureFillsResults);
    CPPUNIT_TEST(testNullArguments);
#ifdef THREADED
    CPPUNIT_TEST(testConnectionLossFillsResults);
    CPPUNIT_TEST(testHeaderOnlyErrorReply);
    CPPUNIT_TEST(testContainerCreateKeepsAlignment);
    CPPUNIT_TEST(testZeroCountOnExpiredHandle);
    CPPUNIT_TEST(testMultiOnExpiredHandle);
#endif
    CPPUNIT_TEST_SUITE_END();

    zhandle_t *zh;
    FILE *logfile;

    static void watcher(zhandle_t *, int, int, const char *, void *) {}

public:
    Zookeeper_multiFailure() {
        logfile = openlogfile("Zookeeper_multiFailure");
    }

    ~Zookeeper_multiFailure() {
        if (logfile) {
            fflush(logfile);
            fclose(logfile);
            logfile = 0;
        }
    }

    void setUp()
    {
        zoo_set_log_stream(logfile);
        zoo_deterministic_conn_order(0);
        zh = 0;
    }

    void tearDown()
    {
        zookeeper_close(zh);
    }

    /* zoo_amulti() rejects a multi with an invalid path before it queues
     * the request. Every result slot then holds the return code. This
     * includes the slots after the invalid operation. */
    void testValidationFailureFillsResults()
    {
        zh = zookeeper_init("localhost:2121", watcher, 10000, 0, 0, 0);
        CPPUNIT_ASSERT(zh != 0);

        char buf[64];
        zoo_op_t ops[3];
        zoo_op_result_t results[3];
        zoo_create_op_init(&ops[0], "/a", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0,
                buf, sizeof(buf));
        zoo_delete_op_init(&ops[1], "no-leading-slash", -1);
        zoo_check_op_init(&ops[2], "/c", 0);
        fillSentinel(results, 3);

        int rc = zoo_amulti(zh, 3, ops, results, 0, 0);
        CPPUNIT_ASSERT(rc != ZOK);
        for (int i = 0; i < 3; i++) {
            CPPUNIT_ASSERT_EQUAL(rc, results[i].err);
        }
        CPPUNIT_ASSERT(results[0].value == NULL);
    }

    /* NULL operation or result arrays give ZBADARGUMENTS and do not crash. */
    void testNullArguments()
    {
        zh = zookeeper_init("localhost:2121", watcher, 10000, 0, 0, 0);
        CPPUNIT_ASSERT(zh != 0);

        zoo_op_t ops[1];
        zoo_op_result_t results[1];
        zoo_check_op_init(&ops[0], "/c", 0);

        fillSentinel(results, 1);
        CPPUNIT_ASSERT_EQUAL((int)ZBADARGUMENTS,
                zoo_amulti(zh, 1, NULL, results, 0, 0));
        CPPUNIT_ASSERT_EQUAL((int)ZBADARGUMENTS, results[0].err);

        CPPUNIT_ASSERT_EQUAL((int)ZBADARGUMENTS,
                zoo_amulti(zh, 1, ops, NULL, 0, 0));
    }

#ifdef THREADED
    /* A synchronous multi in flight when the connection fails returns
     * ZCONNECTIONLOSS. Every result slot also holds ZCONNECTIONLOSS. */
    void testConnectionLossFillsResults()
    {
        Mock_gettimeofday timeMock;
        DropOnMultiServer zkServer;
        Mock_poll pollMock(&zkServer, ZookeeperServer::FD);
        CloseFinally guard(&zh);

        zh = zookeeper_init("localhost:2121", watcher, 10000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh), 1000) < 1000);

        char buf[64];
        Stat stat;
        zoo_op_t ops[3];
        zoo_op_result_t results[3];
        zoo_create_op_init(&ops[0], "/a", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0,
                buf, sizeof(buf));
        zoo_set_op_init(&ops[1], "/b", "x", 1, -1, &stat);
        zoo_delete_op_init(&ops[2], "/c", -1);
        fillSentinel(results, 3);

        int rc = zoo_multi(zh, 3, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZCONNECTIONLOSS, rc);
        for (int i = 0; i < 3; i++) {
            CPPUNIT_ASSERT_EQUAL((int)ZCONNECTIONLOSS, results[i].err);
        }
        CPPUNIT_ASSERT(results[0].value == NULL);
        CPPUNIT_ASSERT(results[1].stat == NULL);
    }

    /* A read-only server answers a write multi with a ZNOTREADONLY header
     * and no body. The synchronous multi returns that error in every slot. */
    void testHeaderOnlyErrorReply()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        Mock_poll pollMock(&zkServer, ZookeeperServer::FD);
        CloseFinally guard(&zh);

        zh = zookeeper_init("localhost:2121", watcher, 10000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh), 1000) < 1000);

        zoo_op_t ops[2];
        zoo_op_result_t results[2];
        zoo_delete_op_init(&ops[0], "/a", -1);
        zoo_check_op_init(&ops[1], "/b", 0);
        fillSentinel(results, 2);

        zkServer.addOperationResponse(new HeaderOnlyResponse(ZNOTREADONLY));
        int rc = zoo_multi(zh, 2, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZNOTREADONLY, rc);
        for (int i = 0; i < 2; i++) {
            CPPUNIT_ASSERT_EQUAL((int)ZNOTREADONLY, results[i].err);
        }
        CPPUNIT_ASSERT(ClientConnected(zh)());
    }

    /* The Create2Response of a container create has a stat. The client must
     * consume the stat, so that it decodes the next sub-result correctly. */
    void testContainerCreateKeepsAlignment()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        Mock_poll pollMock(&zkServer, ZookeeperServer::FD);
        CloseFinally guard(&zh);

        zh = zookeeper_init("localhost:2121", watcher, 10000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        CPPUNIT_ASSERT(ensureCondition(ClientConnected(zh), 1000) < 1000);

        char buf[64];
        Stat setStat;
        memset(&setStat, 0, sizeof(setStat));
        zoo_op_t ops[2];
        zoo_op_result_t results[2];
        zoo_create_op_init(&ops[0], "/container", "", 0, &ZOO_OPEN_ACL_UNSAFE,
                ZOO_CONTAINER, buf, sizeof(buf));
        zoo_set_op_init(&ops[1], "/container", "x", 1, -1, &setStat);
        CPPUNIT_ASSERT_EQUAL((int)ZOO_CREATE_CONTAINER_OP, ops[0].type);
        fillSentinel(results, 2);

        zkServer.addOperationResponse(new ContainerMultiResponse("/container",
                makeStat(0), makeStat(7)));
        int rc = zoo_multi(zh, 2, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[0].err);
        CPPUNIT_ASSERT_EQUAL(string("/container"), string(buf));
        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[1].err);
        CPPUNIT_ASSERT_EQUAL(7, setStat.version);
        CPPUNIT_ASSERT_EQUAL((int64_t)0x2222, setStat.mzxid);
    }

    void expireSession(ZookeeperServer& zkServer)
    {
        zkServer.returnSessionExpired();
        zh = zookeeper_init("localhost:2121", watcher, 10000, TEST_CLIENT_ID, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        CPPUNIT_ASSERT(ensureCondition(SessionExpired(zh), 1000) < 1000);
    }

    /* A zero-count multi on an expired handle returns an error at once. It
     * must not wait for a reply that no I/O thread delivers. */
    void testZeroCountOnExpiredHandle()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        Mock_poll pollMock(&zkServer, ZookeeperServer::FD);
        CloseFinally guard(&zh);
        expireSession(zkServer);

        MultiCall call;
        call.zh = zh;
        call.count = 0;
        call.ops = NULL;
        call.results = NULL;
        call.start();
        bool returned = ensureCondition(call, 1000) < 1000;
        if (!returned) {
            /* Close the handle to wake the waiting thread. */
            zookeeper_close(zh);
            zh = 0;
        }
        pthread_join(call.thread, 0);
        CPPUNIT_ASSERT_MESSAGE("zoo_multi() hangs on an expired handle", returned);
        CPPUNIT_ASSERT(call.rc != ZOK);
    }

    /* A multi with operations on an expired handle also fails at once, and
     * every result slot holds the return code. */
    void testMultiOnExpiredHandle()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        Mock_poll pollMock(&zkServer, ZookeeperServer::FD);
        CloseFinally guard(&zh);
        expireSession(zkServer);

        zoo_op_t ops[2];
        zoo_op_result_t results[2];
        zoo_delete_op_init(&ops[0], "/a", -1);
        zoo_check_op_init(&ops[1], "/b", 0);
        fillSentinel(results, 2);

        int rc = zoo_multi(zh, 2, ops, results);
        CPPUNIT_ASSERT(rc != ZOK);
        for (int i = 0; i < 2; i++) {
            CPPUNIT_ASSERT_EQUAL(rc, results[i].err);
        }
    }
#endif
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_multiFailure);
