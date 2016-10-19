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

#include <signal.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/select.h>

#include "CollectionUtil.h"
#include "ThreadingUtil.h"

using namespace Util;

#include "Vector.h"
using namespace std;

#include <cstring>
#include <list>

#include <zookeeper.h>
#include <errno.h>
#include <recordio.h>
#include "Util.h"

#ifdef THREADED
    static void yield(zhandle_t *zh, int i)
    {
        sleep(i);
    }
#else
    static void yield(zhandle_t *zh, int seconds)
    {
        int fd;
        int interest;
        int events;
        struct timeval tv;
        int rc;
        time_t expires = time(0) + seconds;
        time_t timeLeft = seconds;
        fd_set rfds, wfds, efds;
        FD_ZERO(&rfds);
        FD_ZERO(&wfds);
        FD_ZERO(&efds);

        while(timeLeft >= 0) {
            zookeeper_interest(zh, &fd, &interest, &tv);
            if (fd != -1) {
                if (interest&ZOOKEEPER_READ) {
                    FD_SET(fd, &rfds);
                } else {
                    FD_CLR(fd, &rfds);
                }
                if (interest&ZOOKEEPER_WRITE) {
                    FD_SET(fd, &wfds);
                } else {
                    FD_CLR(fd, &wfds);
                }
            } else {
                fd = 0;
            }
            FD_SET(0, &rfds);
            if (tv.tv_sec > timeLeft) {
                tv.tv_sec = timeLeft;
            }
            rc = select(fd+1, &rfds, &wfds, &efds, &tv);
            timeLeft = expires - time(0);
            events = 0;
            if (FD_ISSET(fd, &rfds)) {
                events |= ZOOKEEPER_READ;
            }
            if (FD_ISSET(fd, &wfds)) {
                events |= ZOOKEEPER_WRITE;
            }
            zookeeper_process(zh, events);
        }
    }
#endif

typedef struct evt {
    string path;
    int type;
} evt_t;

typedef struct watchCtx {
private:
    list<evt_t> events;
    watchCtx(const watchCtx&);
    watchCtx& operator=(const watchCtx&);
public:
    bool connected;
    zhandle_t *zh;
    Mutex mutex;

    watchCtx() {
        connected = false;
        zh = 0;
    }
    ~watchCtx() {
        if (zh) {
            zookeeper_close(zh);
            zh = 0;
        }
    }

    evt_t getEvent() {
        evt_t evt;
        mutex.acquire();
        CPPUNIT_ASSERT( events.size() > 0);
        evt = events.front();
        events.pop_front();
        mutex.release();
        return evt;
    }

    int countEvents() {
        int count;
        mutex.acquire();
        count = events.size();
        mutex.release();
        return count;
    }

    void putEvent(evt_t evt) {
        mutex.acquire();
        events.push_back(evt);
        mutex.release();
    }

    bool waitForConnected(zhandle_t *zh) {
        time_t expires = time(0) + 10;
        while(!connected && time(0) < expires) {
            yield(zh, 1);
        }
        return connected;
    }
    bool waitForDisconnected(zhandle_t *zh) {
        time_t expires = time(0) + 15;
        while(connected && time(0) < expires) {
            yield(zh, 1);
        }
        return !connected;
    }
} watchctx_t;

#ifdef THREADED
class Zookeeper_multi : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_multi);
//FIXME: None of these tests pass in single-threaded mode. It seems to be a
//flaw in the test suite setup.
    CPPUNIT_TEST(testCreate);
    CPPUNIT_TEST(testCreateDelete);
    CPPUNIT_TEST(testInvalidVersion);
    CPPUNIT_TEST(testNestedCreate);
    CPPUNIT_TEST(testSetData);
    CPPUNIT_TEST(testUpdateConflict);
    CPPUNIT_TEST(testDeleteUpdateConflict);
    CPPUNIT_TEST(testAsyncMulti);
    CPPUNIT_TEST(testMultiFail);
    CPPUNIT_TEST(testCheck);
    CPPUNIT_TEST(testWatch);
    CPPUNIT_TEST(testSequentialNodeCreateInAsyncMulti);
    CPPUNIT_TEST_SUITE_END();

    static void watcher(zhandle_t *, int type, int state, const char *path,void*v){
        watchctx_t *ctx = (watchctx_t*)v;

        if (state == ZOO_CONNECTED_STATE) {
            ctx->connected = true;
        } else {
            ctx->connected = false;
        }
        if (type != ZOO_SESSION_EVENT) {
            evt_t evt;
            evt.path = path;
            evt.type = type;
            ctx->putEvent(evt);
        }
    }

    static const char hostPorts[];

    const char *getHostPorts() {
        return hostPorts;
    }
    
    zhandle_t *createClient(watchctx_t *ctx) {
        return createClient(hostPorts, ctx);
    }

    zhandle_t *createClient(const char *hp, watchctx_t *ctx) {
        zhandle_t *zk = zookeeper_init(hp, watcher, 10000, 0, ctx, 0);
        ctx->zh = zk;
        CPPUNIT_ASSERT_EQUAL(true, ctx->waitForConnected(zk));
        return zk;
    }
        
    FILE *logfile;
public:

    Zookeeper_multi() {
      logfile = openlogfile("Zookeeper_multi");
    }

    ~Zookeeper_multi() {
      if (logfile) {
        fflush(logfile);
        fclose(logfile);
        logfile = 0;
      }
    }

    void setUp()
    {
        zoo_set_log_stream(logfile);
    }

    void tearDown()
    {
    }
    
    static volatile int count;

    static void multi_completion_fn(int rc, const void *data) {
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        count++;
    }

    static void multi_completion_fn_no_assert(int rc, const void *data) {
        count++;
    }

    static void waitForMultiCompletion(int seconds) {
        time_t expires = time(0) + seconds;
        while(count == 0 && time(0) < expires) {
            sleep(1);
        }
        count--;
    }

    static void resetCounter() {
        count = 0;
    }

    /**
     * Test basic multi-op create functionality 
     */
    void testCreate() {
        int rc;
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
       
        int sz = 512;
        char p1[sz];
        char p2[sz];
        char p3[sz];
        p1[0] = p2[0] = p3[0] = '\0';

        int nops = 3 ;
        zoo_op_t ops[nops];
        zoo_op_result_t results[nops];

        zoo_create_op_init(&ops[0], "/multi1", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p1, sz);
        zoo_create_op_init(&ops[1], "/multi1/a", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p2, sz);
        zoo_create_op_init(&ops[2], "/multi1/b", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p3, sz);
        
        rc = zoo_multi(zk, nops, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        CPPUNIT_ASSERT(strcmp(p1, "/multi1") == 0);
        CPPUNIT_ASSERT(strcmp(p2, "/multi1/a") == 0);
        CPPUNIT_ASSERT(strcmp(p3, "/multi1/b") == 0);

        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[0].err);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[1].err);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[2].err);
    }

    /**
     * Test create followed by delete 
     */
    void testCreateDelete() {
        int rc;
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        int sz = 512;
        char p1[sz];
        p1[0] = '\0';
        int nops = 2 ;
        zoo_op_t ops[nops];
        zoo_op_result_t results[nops];

        zoo_create_op_init(&ops[0], "/multi2", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p1, sz);
        zoo_delete_op_init(&ops[1], "/multi2", 0);
        
        rc = zoo_multi(zk, nops, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        // '/multi2' should have been deleted
        rc = zoo_exists(zk, "/multi2", 0, NULL);
        CPPUNIT_ASSERT_EQUAL((int)ZNONODE, rc);
    }

    /** 
     * Test invalid versions
     */
    void testInvalidVersion() {
        int rc;
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        int nops = 4;
        zoo_op_t ops[nops];
        zoo_op_result_t results[nops];

        zoo_create_op_init(&ops[0], "/multi3", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
        zoo_delete_op_init(&ops[1], "/multi3", 1);
        zoo_create_op_init(&ops[2], "/multi3", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
        zoo_create_op_init(&ops[3], "/multi3/a", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
        
        rc = zoo_multi(zk, nops, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZBADVERSION, rc);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[0].err);
        CPPUNIT_ASSERT_EQUAL((int)ZBADVERSION, results[1].err);
        CPPUNIT_ASSERT_EQUAL((int)ZRUNTIMEINCONSISTENCY, results[2].err);
        CPPUNIT_ASSERT_EQUAL((int)ZRUNTIMEINCONSISTENCY, results[3].err);
    }

    /**
     * Test nested creates that rely on state in earlier op in multi
     */
    void testNestedCreate() {
        int rc;
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        int sz = 512;
        char p1[sz];
        p1[0] = '\0';
        int nops = 6;
        zoo_op_t ops[nops];
        zoo_op_result_t results[nops];

        /* Create */
        zoo_create_op_init(&ops[0], "/multi4", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p1, sz);
        zoo_create_op_init(&ops[1], "/multi4/a", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p1, sz);
        zoo_create_op_init(&ops[2], "/multi4/a/1", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p1, sz);

        /* Delete */
        zoo_delete_op_init(&ops[3], "/multi4/a/1", 0);
        zoo_delete_op_init(&ops[4], "/multi4/a", 0);
        zoo_delete_op_init(&ops[5], "/multi4", 0);
        
        rc = zoo_multi(zk, nops, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        // Verify tree deleted
        rc = zoo_exists(zk, "/multi4/a/1", 0, NULL);
        CPPUNIT_ASSERT_EQUAL((int)ZNONODE, rc);
        
        rc = zoo_exists(zk, "/multi4/a", 0, NULL);
        CPPUNIT_ASSERT_EQUAL((int)ZNONODE, rc);
  
        rc = zoo_exists(zk, "/multi4", 0, NULL);
        CPPUNIT_ASSERT_EQUAL((int)ZNONODE, rc);
    }

    /**
     * Test setdata functionality
     */
    void testSetData() {
        int rc;
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        int sz = 512;
        struct Stat s1;

        char buf[sz];
        int blen = sz ;

        char p1[sz], p2[sz];
        
        int nops = 2;
        zoo_op_t ops[nops];
        zoo_op_result_t results[nops];

        zoo_create_op_init(&ops[0], "/multi5",   "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p1, sz);
        zoo_create_op_init(&ops[1], "/multi5/a", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p2, sz);
       
        rc = zoo_multi(zk, nops, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        
        yield(zk, 5);

        zoo_op_t setdata_ops[nops];
        zoo_op_result_t setdata_results[nops];

        zoo_set_op_init(&setdata_ops[0], "/multi5",   "1", 1, 0, &s1);
        zoo_set_op_init(&setdata_ops[1], "/multi5/a", "2", 1, 0, &s1);

        rc = zoo_multi(zk, nops, setdata_ops, setdata_results);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[0].err);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[1].err);
        
        memset(buf, '\0', blen);
        rc = zoo_get(zk, "/multi5", 0, buf, &blen, &s1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL(1, blen);
        CPPUNIT_ASSERT(strcmp("1", buf) == 0);
        
        memset(buf, '\0', blen);
        rc = zoo_get(zk, "/multi5/a", 0, buf, &blen, &s1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL(1, blen);
        CPPUNIT_ASSERT(strcmp("2", buf) == 0);
    }

    /**
     * Test update conflicts
     */
    void testUpdateConflict() {
        int rc;
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        int sz = 512;
        char buf[sz];
        int blen = sz;
        char p1[sz];
        p1[0] = '\0';
        struct Stat s1;
        int nops = 3;
        zoo_op_t ops[nops];
        zoo_op_result_t results[nops];

        zoo_create_op_init(&ops[0], "/multi6", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p1, sz);
        zoo_set_op_init(&ops[1], "/multi6", "X", 1, 0, &s1);
        zoo_set_op_init(&ops[2], "/multi6", "Y", 1, 0, &s1);
        
        rc = zoo_multi(zk, nops, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZBADVERSION, rc);

        //Updating version solves conflict -- order matters
        ops[2].set_op.version = 1;
        rc = zoo_multi(zk, nops, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
       
        memset(buf, 0, sz);
        rc = zoo_get(zk, "/multi6", 0, buf, &blen, &s1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL(blen, 1);
        CPPUNIT_ASSERT(strncmp(buf, "Y", 1) == 0);
    }

    /**
     * Test delete-update conflicts
     */
    void testDeleteUpdateConflict() {
        int rc;
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        int sz = 512;
        char buf[sz];
        int blen;
        char p1[sz];
        p1[0] = '\0';
        struct Stat stat;
        int nops = 3;
        zoo_op_t ops[nops];
        zoo_op_result_t results[nops];

        zoo_create_op_init(&ops[0], "/multi7", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p1, sz);
        zoo_delete_op_init(&ops[1], "/multi7", 0);
        zoo_set_op_init(&ops[2], "/multi7", "Y", 1, 0, &stat);
        
        rc = zoo_multi(zk, nops, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZNONODE, rc);

        // '/multi' should never have been created as entire op should fail
        rc = zoo_exists(zk, "/multi7", 0, NULL);
        CPPUNIT_ASSERT_EQUAL((int)ZNONODE, rc);
    }

    void testAsyncMulti() {
        int rc;
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
       
        int sz = 512;
        char p1[sz], p2[sz], p3[sz];
        p1[0] = '\0';
        p2[0] = '\0';
        p3[0] = '\0';

        int nops = 3;
        zoo_op_t ops[nops];
        zoo_op_result_t results[nops];

        zoo_create_op_init(&ops[0], "/multi8",   "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p1, sz);
        zoo_create_op_init(&ops[1], "/multi8/a", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p2, sz);
        zoo_create_op_init(&ops[2], "/multi8/b", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p3, sz);
 
        rc = zoo_amulti(zk, nops, ops, results, multi_completion_fn, 0);
        waitForMultiCompletion(10);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        CPPUNIT_ASSERT(strcmp(p1, "/multi8") == 0);
        CPPUNIT_ASSERT(strcmp(p2, "/multi8/a") == 0);
        CPPUNIT_ASSERT(strcmp(p3, "/multi8/b") == 0);

        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[0].err);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[1].err);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[2].err);
    }

    void testMultiFail() {
        int rc;
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
       
        int sz = 512;
        char p1[sz], p2[sz], p3[sz];

        p1[0] = '\0';
        p2[0] = '\0';
        p3[0] = '\0';

        int nops = 3;
        zoo_op_t ops[nops];
        zoo_op_result_t results[nops];

        zoo_create_op_init(&ops[0], "/multi9",   "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p1, sz);
        zoo_create_op_init(&ops[1], "/multi9",   "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p2, sz);
        zoo_create_op_init(&ops[2], "/multi9/b", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p3, sz);
        
        rc = zoo_multi(zk, nops, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZNODEEXISTS, rc);
    }
    
    /**
     * Test basic multi-op check functionality 
     */
    void testCheck() {
        int rc;
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        int sz = 512;
        char p1[sz];
        p1[0] = '\0';
        struct Stat s1;

        rc = zoo_create(zk, "/multi0", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p1, sz);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        // Conditionally create /multi0/a' only if '/multi0' at version 0
        int nops = 2;
        zoo_op_t ops[nops];
        zoo_op_result_t results[nops];

        zoo_check_op_init(&ops[0], "/multi0", 0);
        zoo_create_op_init(&ops[1], "/multi0/a", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p1, sz);
        
        rc = zoo_multi(zk, nops, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[0].err);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[1].err);

        // '/multi0/a' should have been created as it passed version check
        rc = zoo_exists(zk, "/multi0/a", 0, NULL);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
 
        // Only create '/multi0/b' if '/multi0' at version 10 (which it's not)
        zoo_op_t ops2[nops];
        zoo_check_op_init(&ops2[0], "/multi0", 10);
        zoo_create_op_init(&ops2[1], "/multi0/b", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, p1, sz);
        
        rc = zoo_multi(zk, nops, ops2, results);
        CPPUNIT_ASSERT_EQUAL((int)ZBADVERSION, rc);

        CPPUNIT_ASSERT_EQUAL((int)ZBADVERSION, results[0].err);
        CPPUNIT_ASSERT_EQUAL((int)ZRUNTIMEINCONSISTENCY, results[1].err);

        // '/multi0/b' should NOT have been created
        rc = zoo_exists(zk, "/multi0/b", 0, NULL);
        CPPUNIT_ASSERT_EQUAL((int)ZNONODE, rc);
    }

    /**
     * Do a multi op inside a watch callback context.
     */
    static void doMultiInWatch(zhandle_t *zk, int type, int state, const char *path, void *ctx) {
        int rc;
        int sz = 512;
        char p1[sz];
        p1[0] = '\0';
        struct Stat s1;

        int nops = 1;
        zoo_op_t ops[nops];
        zoo_op_result_t results[nops];

        zoo_set_op_init(&ops[0], "/multiwatch", "1", 1, -1, NULL);

        rc = zoo_multi(zk, nops, ops, results);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, results[0].err);

        memset(p1, '\0', sz);
        rc = zoo_get(zk, "/multiwatch", 0, p1, &sz, &s1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL(1, sz);
        CPPUNIT_ASSERT(strcmp("1", p1) == 0);
        count++;
    }

    /**
     * Test multi-op called from a watch
     */
     void testWatch() {
        int rc;
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        int sz = 512;
        char p1[sz];
        p1[0] = '\0';

        rc = zoo_create(zk, "/multiwatch", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        // create a watch on node '/multiwatch'
        rc = zoo_wget(zk, "/multiwatch", doMultiInWatch, &ctx, p1, &sz, NULL);

        // setdata on node '/multiwatch' this should trip the watch
        rc = zoo_set(zk, "/multiwatch", NULL, -1, -1);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);

        // wait for multi completion in doMultiInWatch
        waitForMultiCompletion(5);
     }

     /**
      * ZOOKEEPER-1624: PendingChanges of create sequential node request didn't
      * get rollbacked correctly when multi-op failed. This caused
      * create sequential node request in subsequent multi-op to failed because
      * sequential node name generation is incorrect.
      *
      * The check is to make sure that each request in multi-op failed with
      * the correct reason.
      */
     void testSequentialNodeCreateInAsyncMulti() {
         int rc;
         watchctx_t ctx;
         zhandle_t *zk = createClient(&ctx);

         int iteration = 4;
         int nops = 2;

         zoo_op_result_t results[iteration][nops];
         zoo_op_t ops[nops];
         zoo_create_op_init(&ops[0], "/node-",   "", 0, &ZOO_OPEN_ACL_UNSAFE, ZOO_SEQUENCE, NULL, 0);
         zoo_create_op_init(&ops[1], "/dup", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
         for (int i = 0; i < iteration ; ++i) {
           rc = zoo_amulti(zk, nops, ops, results[i], multi_completion_fn_no_assert, 0);
           CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
         }

         waitForMultiCompletion(10);

         CPPUNIT_ASSERT_EQUAL((int)ZOK, results[0][0].err);
         CPPUNIT_ASSERT_EQUAL((int)ZOK, results[1][0].err);
         CPPUNIT_ASSERT_EQUAL((int)ZOK, results[2][0].err);
         CPPUNIT_ASSERT_EQUAL((int)ZOK, results[3][0].err);

         CPPUNIT_ASSERT_EQUAL((int)ZOK, results[0][1].err);
         CPPUNIT_ASSERT_EQUAL((int)ZNODEEXISTS, results[1][1].err);
         CPPUNIT_ASSERT_EQUAL((int)ZNODEEXISTS, results[2][1].err);
         CPPUNIT_ASSERT_EQUAL((int)ZNODEEXISTS, results[3][1].err);

         resetCounter();
     }
};

volatile int Zookeeper_multi::count;
const char Zookeeper_multi::hostPorts[] = "127.0.0.1:22181";
CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_multi);
#endif
