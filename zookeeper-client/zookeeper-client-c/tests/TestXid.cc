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

#include <stdint.h>
#include <string.h>
#include <limits>
#include <set>
#include <string>
#include <vector>

#include "ZKMocks.h"

using namespace std;

static const int32_t XID_MAX = numeric_limits<int32_t>::max();

/* These tests check that each handle allocates xids in the same way as the
 * Java client. Xids start at 1, are always positive, and wrap from INT32_MAX
 * back to 1. */
class Zookeeper_xid : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_xid);
    CPPUNIT_TEST(testNewHandleStartsAtOne);
    CPPUNIT_TEST(testWrapFromMaxToOne);
    CPPUNIT_TEST(testHandlesHaveIndependentCounters);
    CPPUNIT_TEST(testNullHandle);
#ifndef THREADED
    CPPUNIT_TEST(testRequestsAcrossWrap);
    CPPUNIT_TEST(testMismatchLogOrder);
#else
    CPPUNIT_TEST(testConcurrentAllocationAcrossWrap);
#endif
    CPPUNIT_TEST_SUITE_END();

    zhandle_t *zh;
    FILE *logfile;

    static void watcher(zhandle_t *, int, int, const char *, void *) {}

public:
    Zookeeper_xid() {
        logfile = openlogfile("Zookeeper_xid");
    }

    ~Zookeeper_xid() {
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

    void testNewHandleStartsAtOne()
    {
        zh = zookeeper_init("localhost:2121", watcher, 10000, 0, 0, 0);
        CPPUNIT_ASSERT(zh != 0);
        CPPUNIT_ASSERT_EQUAL(1, get_xid(zh));
        CPPUNIT_ASSERT_EQUAL(2, get_xid(zh));
        CPPUNIT_ASSERT_EQUAL(3, get_xid(zh));
    }

    void testWrapFromMaxToOne()
    {
        zhandle_t h;
        memset(&h, 0, sizeof(h));
        h.xid = XID_MAX - 1;
        CPPUNIT_ASSERT_EQUAL(XID_MAX - 1, get_xid(&h));
        CPPUNIT_ASSERT_EQUAL(XID_MAX, get_xid(&h));
        CPPUNIT_ASSERT_EQUAL(1, get_xid(&h));
        CPPUNIT_ASSERT_EQUAL(2, get_xid(&h));
    }

    void testHandlesHaveIndependentCounters()
    {
        zhandle_t a;
        zhandle_t b;
        memset(&a, 0, sizeof(a));
        memset(&b, 0, sizeof(b));
        a.xid = 1;
        b.xid = 1;
        CPPUNIT_ASSERT_EQUAL(1, get_xid(&a));
        CPPUNIT_ASSERT_EQUAL(2, get_xid(&a));
        CPPUNIT_ASSERT_EQUAL(1, get_xid(&b));
        CPPUNIT_ASSERT_EQUAL(3, get_xid(&a));
        CPPUNIT_ASSERT_EQUAL(2, get_xid(&b));
    }

    void testNullHandle()
    {
        CPPUNIT_ASSERT_EQUAL(0, get_xid(NULL));
        // An API call with a NULL handle fails before it sends a request.
        CPPUNIT_ASSERT_EQUAL((int)ZBADARGUMENTS,
                zoo_awget(NULL, "/a", 0, 0, NULL, NULL));
    }

#ifndef THREADED
    class GetCompletion: public AsyncCompletion {
    public:
        GetCompletion(): called_(false), rc_(ZAPIERROR) {}
        virtual void dataCompl(int rc, const char *value, int len,
                const Stat *stat) {
            called_ = true;
            rc_ = rc;
            if (rc == ZOK) {
                value_.assign(value, len);
            }
        }
        bool called_;
        int rc_;
        string value_;
    };

    // Send requests whose xids cross the wrap. Each reply must reach its
    // own completion, and the session must stay connected.
    void testRequestsAcrossWrap()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        CloseFinally guard(&zh);

        zh = zookeeper_init("localhost:2121", watcher, 10000, TEST_CLIENT_ID,
                0, 0);
        CPPUNIT_ASSERT(zh != 0);
        forceConnected(zh, &timeMock.tv);
        zh->xid = XID_MAX - 1;

        const int count = 4;
        GetCompletion res[count];
        for (int i = 0; i < count; i++) {
            char value[2] = { (char)('0' + i), 0 };
            zkServer.addOperationResponse(new ZooGetResponse(value, 1));
            CPPUNIT_ASSERT_EQUAL((int)ZOK,
                    zoo_aget(zh, "/x", 0, asyncCompletion, &res[i]));
        }
        CPPUNIT_ASSERT_EQUAL(3, (int)zh->xid);

        int fd = 0;
        int interest = 0;
        timeval tv;
        int rc = zookeeper_interest(zh, &fd, &interest, &tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        while ((rc = zookeeper_process(zh, interest)) == ZOK) {
            millisleep(10);
        }
        CPPUNIT_ASSERT_EQUAL((int)ZNOTHING, rc);

        for (int i = 0; i < count; i++) {
            CPPUNIT_ASSERT(res[i].called_);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, res[i].rc_);
            CPPUNIT_ASSERT_EQUAL(string(1, (char)('0' + i)), res[i].value_);
        }
        CPPUNIT_ASSERT_EQUAL((int)ZOO_CONNECTED_STATE, zoo_state(zh));
    }

    // This reply ignores the request xid, so the client sees a mismatch.
    class WrongXidGetResponse: public ZooGetResponse {
    public:
        WrongXidGetResponse(): ZooGetResponse("1", 1, 0x7777) {}
        virtual void setXID(int32_t) {}
    };

    static string capturedLog;
    static void captureLog(const char *message) {
        capturedLog += message;
        capturedLog += "\n";
    }

    // The mismatch log line names the expected xid first and the received
    // xid second.
    void testMismatchLogOrder()
    {
        Mock_gettimeofday timeMock;
        ZookeeperServer zkServer;
        CloseFinally guard(&zh);
        capturedLog.clear();

        zh = zookeeper_init2("localhost:2121", watcher, 10000, TEST_CLIENT_ID,
                0, 0, captureLog);
        CPPUNIT_ASSERT(zh != 0);
        forceConnected(zh, &timeMock.tv);
        zh->xid = 0x10;

        GetCompletion res;
        zkServer.addOperationResponse(new WrongXidGetResponse());
        CPPUNIT_ASSERT_EQUAL((int)ZOK,
                zoo_aget(zh, "/x", 0, asyncCompletion, &res));

        int fd = 0;
        int interest = 0;
        timeval tv;
        int rc = zookeeper_interest(zh, &fd, &interest, &tv);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        while ((rc = zookeeper_process(zh, interest)) == ZOK) {
            millisleep(10);
        }
        CPPUNIT_ASSERT_EQUAL((int)ZRUNTIMEINCONSISTENCY, rc);
        CPPUNIT_ASSERT_MESSAGE(capturedLog,
                capturedLog.find("expected 0x10, but received 0x7777")
                != string::npos);
    }
#else
    struct AllocArgs {
        zhandle_t *zh;
        int count;
        vector<int32_t> xids;
    };

    static void *allocXids(void *arg) {
        AllocArgs *args = (AllocArgs *)arg;
        for (int i = 0; i < args->count; i++) {
            args->xids.push_back(get_xid(args->zh));
        }
        return 0;
    }

    // Many threads allocate xids on one handle across the wrap. Each xid
    // must be positive and unique, and the counter must not skip a value.
    void testConcurrentAllocationAcrossWrap()
    {
        const int threads = 8;
        const int perThread = 10000;
        const int total = threads * perThread;
        zhandle_t h;
        memset(&h, 0, sizeof(h));
        // Half of the xids come before the wrap, and half come after it.
        h.xid = XID_MAX - total / 2 + 1;

        AllocArgs args[threads];
        pthread_t tids[threads];
        for (int i = 0; i < threads; i++) {
            args[i].zh = &h;
            args[i].count = perThread;
            CPPUNIT_ASSERT_EQUAL(0,
                    pthread_create(&tids[i], 0, allocXids, &args[i]));
        }
        for (int i = 0; i < threads; i++) {
            pthread_join(tids[i], 0);
        }

        set<int32_t> seen;
        for (int i = 0; i < threads; i++) {
            for (size_t j = 0; j < args[i].xids.size(); j++) {
                int32_t xid = args[i].xids[j];
                CPPUNIT_ASSERT(xid > 0);
                CPPUNIT_ASSERT(seen.insert(xid).second);
            }
        }
        CPPUNIT_ASSERT_EQUAL((size_t)total, seen.size());
        CPPUNIT_ASSERT_EQUAL(total / 2 + 1, (int)h.xid);
        CPPUNIT_ASSERT(seen.count(XID_MAX) == 1);
        CPPUNIT_ASSERT(seen.count(1) == 1);
    }
#endif
};

#ifndef THREADED
string Zookeeper_xid::capturedLog;
#endif

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_xid);
