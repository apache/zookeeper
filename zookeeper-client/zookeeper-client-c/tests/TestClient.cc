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
#include "ZKMocks.h"

struct buff_struct_2 {
    int32_t len;
    int32_t off;
    char *buffer;
};

// TODO(br33d): the vast majority of this test is not usable with single threaded.
//              it needs a overhaul to work properly with both threaded and single
//              threaded (ZOOKEEPER-2640)
#ifdef THREADED
// For testing LogMessage Callback functionality
list<string> logMessages;
void logMessageHandler(const char* message) {
    cout << "Log Message Received: [" << message << "]" << endl;
    logMessages.push_back(message);
}

static int Stat_eq(struct Stat* a, struct Stat* b)
{
    if (a->czxid != b->czxid) return 0;
    if (a->mzxid != b->mzxid) return 0;
    if (a->ctime != b->ctime) return 0;
    if (a->mtime != b->mtime) return 0;
    if (a->version != b->version) return 0;
    if (a->cversion != b->cversion) return 0;
    if (a->aversion != b->aversion) return 0;
    if (a->ephemeralOwner != b->ephemeralOwner) return 0;
    if (a->dataLength != b->dataLength) return 0;
    if (a->numChildren != b->numChildren) return 0;
    if (a->pzxid != b->pzxid) return 0;
    return 1;
}
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

class Zookeeper_simpleSystem : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_simpleSystem);
    CPPUNIT_TEST(testLogCallbackSet);
    CPPUNIT_TEST(testLogCallbackInit);
    CPPUNIT_TEST(testLogCallbackClear);
    CPPUNIT_TEST(testAsyncWatcherAutoReset);
    CPPUNIT_TEST(testDeserializeString);
    CPPUNIT_TEST(testFirstServerDown);
    CPPUNIT_TEST(testNonexistentHost);
#ifdef THREADED
    CPPUNIT_TEST(testNullData);
#ifdef ZOO_IPV6_ENABLED
    CPPUNIT_TEST(testIPV6);
#endif
    CPPUNIT_TEST(testCreate);
    CPPUNIT_TEST(testPath);
    CPPUNIT_TEST(testPathValidation);
    CPPUNIT_TEST(testPing);
    CPPUNIT_TEST(testAcl);
    CPPUNIT_TEST(testChroot);
    CPPUNIT_TEST(testAuth);
    CPPUNIT_TEST(testHangingClient);
    CPPUNIT_TEST(testWatcherAutoResetWithGlobal);
    CPPUNIT_TEST(testWatcherAutoResetWithLocal);
    CPPUNIT_TEST(testGetChildren2);
    CPPUNIT_TEST(testLastZxid);
    CPPUNIT_TEST(testRemoveWatchers);
#endif
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

    zhandle_t *createClient(watchctx_t *ctx, log_callback_fn logCallback) {
        zhandle_t *zk = zookeeper_init2(hostPorts, watcher, 10000, 0, ctx, 0, logCallback);
        ctx->zh = zk;
        sleep(1);
        return zk;
    }

    zhandle_t *createClient(const char *hp, watchctx_t *ctx) {
        zhandle_t *zk = zookeeper_init(hp, watcher, 10000, 0, ctx, 0);
        ctx->zh = zk;
        sleep(1);
        return zk;
    }
    
    zhandle_t *createchClient(watchctx_t *ctx, const char* chroot) {
        zhandle_t *zk = zookeeper_init(chroot, watcher, 10000, 0, ctx, 0);
        ctx->zh = zk;
        sleep(1);
        return zk;
    }
        
    FILE *logfile;
public:

    Zookeeper_simpleSystem() {
      logfile = openlogfile("Zookeeper_simpleSystem");
    }

    ~Zookeeper_simpleSystem() {
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

    void startServer() {
        char cmd[1024];
        sprintf(cmd, "%s start %s", ZKSERVER_CMD, getHostPorts());
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    void stopServer() {
        char cmd[1024];
        sprintf(cmd, "%s stop %s", ZKSERVER_CMD, getHostPorts());
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    void tearDown()
    {
    }
    
    /** have a callback in the default watcher **/
    static void default_zoo_watcher(zhandle_t *zzh, int type, int state, const char *path, void *context){
        int zrc = 0;
        struct String_vector str_vec = {0, NULL};
        zrc = zoo_wget_children(zzh, "/mytest", default_zoo_watcher, NULL, &str_vec);
    }

    /** ZOOKEEPER-1057 This checks that the client connects to the second server when the first is not reachable **/
    void testFirstServerDown() {
        watchctx_t ctx;

        zoo_deterministic_conn_order(true);

        zhandle_t* zk = createClient("127.0.0.1:22182,127.0.0.1:22181", &ctx);
        CPPUNIT_ASSERT(zk != 0);
        CPPUNIT_ASSERT(ctx.waitForConnected(zk));
    }

    /* Checks that a non-existent host will not block the connection*/
    void testNonexistentHost() {
      char hosts[] = "jimmy:5555,127.0.0.1:22181";
      watchctx_t ctx;
      zoo_deterministic_conn_order(true /* disable permute */);
      zhandle_t *zh = createClient(hosts, &ctx);
      CPPUNIT_ASSERT(ctx.waitForConnected(zh));
      zoo_deterministic_conn_order(false /* enable permute */);
    }

    /** this checks for a deadlock in calling zookeeper_close and calls from a default watcher that might get triggered just when zookeeper_close() is in progress **/
    void testHangingClient() {
        int zrc = 0;
        char buff[10] = "testall";
        char path[512];
        watchctx_t *ctx;
        struct String_vector str_vec = {0, NULL};
        zhandle_t *zh = zookeeper_init(hostPorts, NULL, 10000, 0, ctx, 0);
        sleep(1);
        zrc = zoo_create(zh, "/mytest", buff, 10, &ZOO_OPEN_ACL_UNSAFE, 0, path, 512);
        zrc = zoo_wget_children(zh, "/mytest", default_zoo_watcher, NULL, &str_vec);
        zrc = zoo_create(zh, "/mytest/test1", buff, 10, &ZOO_OPEN_ACL_UNSAFE, 0, path, 512);
        zrc = zoo_wget_children(zh, "/mytest", default_zoo_watcher, NULL, &str_vec);
        zrc = zoo_delete(zh, "/mytest/test1", -1);
        zookeeper_close(zh);
    }

    void testBadDescriptor() {
        int zrc = 0;
        watchctx_t *ctx;
        zhandle_t *zh = zookeeper_init(hostPorts, NULL, 10000, 0, ctx, 0);
        sleep(1);
        zh->io_count = 0;
        //close socket
        close(zh->fd);
        sleep(1);
        //Check that doIo isn't spinning
        CPPUNIT_ASSERT(zh->io_count < 2);
        zookeeper_close(zh);
    }
    

    void testPing()
    {
        watchctx_t ctxIdle;
        watchctx_t ctxWC;
        zhandle_t *zkIdle = createClient(&ctxIdle);
        zhandle_t *zkWatchCreator = createClient(&ctxWC);

        CPPUNIT_ASSERT(zkIdle);
        CPPUNIT_ASSERT(zkWatchCreator);

        char path[80];
        sprintf(path, "/testping");
        int rc = zoo_create(zkWatchCreator, path, "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        for(int i = 0; i < 30; i++) {
            sprintf(path, "/testping/%i", i);
            rc = zoo_create(zkWatchCreator, path, "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        }

        for(int i = 0; i < 30; i++) {
            sprintf(path, "/testping/%i", i);
            struct Stat stat;
            rc = zoo_exists(zkIdle, path, 1, &stat);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        }

        for(int i = 0; i < 30; i++) {
            sprintf(path, "/testping/%i", i);
            usleep(500000);
            rc = zoo_delete(zkWatchCreator, path, -1);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        }
        struct Stat stat;
        CPPUNIT_ASSERT_EQUAL((int)ZNONODE, zoo_exists(zkIdle, "/testping/0", 0, &stat));
    }

    bool waitForEvent(zhandle_t *zh, watchctx_t *ctx, int seconds) {
        time_t expires = time(0) + seconds;
        while(ctx->countEvents() == 0 && time(0) < expires) {
            yield(zh, 1);
        }
        return ctx->countEvents() > 0;
    }

#define COUNT 100

    static zhandle_t *async_zk;
    static volatile int count;
    static const char* hp_chroot;

    static void statCompletion(int rc, const struct Stat *stat, const void *data) {
        int tmp = (int) (long) data;
        CPPUNIT_ASSERT_EQUAL(tmp, rc);
    }

    static void stringCompletion(int rc, const char *value, const void *data) {
        char *path = (char*)data;

        if (rc == ZCONNECTIONLOSS && path) {
            // Try again
            rc = zoo_acreate(async_zk, path, "", 0,  &ZOO_OPEN_ACL_UNSAFE, 0, stringCompletion, 0);
        } else if (rc != ZOK) {
            // fprintf(stderr, "rc = %d with path = %s\n", rc, (path ? path : "null"));
        }
        if (path) {
            free(path);
        }
    }

    static void stringStatCompletion(int rc, const char *value, const struct Stat *stat,
            const void *data) {
        stringCompletion(rc, value, data);
        CPPUNIT_ASSERT(stat != 0);
    }

    static void create_completion_fn(int rc, const char* value, const void *data) {
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        count++;
    }

    static void waitForCreateCompletion(int seconds) {
        time_t expires = time(0) + seconds;
        while(count == 0 && time(0) < expires) {
            sleep(1);
        }
        count--;
    }

    static void watcher_chroot_fn(zhandle_t *zh, int type,
                                    int state, const char *path,void *watcherCtx) {
        // check for path
        char *client_path = (char *) watcherCtx;
        CPPUNIT_ASSERT(strcmp(client_path, path) == 0);
        count ++;
    }

    static void waitForChrootWatch(int seconds) {
        time_t expires = time(0) + seconds;
        while (count == 0 && time(0) < expires) {
            sleep(1);
        }
        count--;
    }

    static void waitForVoidCompletion(int seconds) {
        time_t expires = time(0) + seconds;
        while(count == 0 && time(0) < expires) {
            sleep(1);
        }
        count--;
    }

    static void voidCompletion(int rc, const void *data) {
        int tmp = (int) (long) data;
        CPPUNIT_ASSERT_EQUAL(tmp, rc);
        count++;
    }

    static void verifyCreateFails(const char *path, zhandle_t *zk) {
      CPPUNIT_ASSERT_EQUAL((int)ZBADARGUMENTS, zoo_create(zk,
          path, "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0));
    }

    static void verifyCreateOk(const char *path, zhandle_t *zk) {
      CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_create(zk,
          path, "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0));
    }

    static void verifyCreateFailsSeq(const char *path, zhandle_t *zk) {
      CPPUNIT_ASSERT_EQUAL((int)ZBADARGUMENTS, zoo_create(zk,
          path, "", 0, &ZOO_OPEN_ACL_UNSAFE, ZOO_SEQUENCE, 0, 0));
    }

    static void verifyCreateOkSeq(const char *path, zhandle_t *zk) {
      CPPUNIT_ASSERT_EQUAL((int)ZOK, zoo_create(zk,
          path, "", 0, &ZOO_OPEN_ACL_UNSAFE, ZOO_SEQUENCE, 0, 0));
    }


    /**
       returns false if the vectors dont match
    **/
    bool compareAcl(struct ACL_vector acl1, struct ACL_vector acl2) {
        if (acl1.count != acl2.count) {
            return false;
        }
        struct ACL *aclval1 = acl1.data;
        struct ACL *aclval2 = acl2.data;
        if (aclval1->perms != aclval2->perms) {
            return false;
        }
        struct Id id1 = aclval1->id;
        struct Id id2 = aclval2->id;
        if (strcmp(id1.scheme, id2.scheme) != 0) {
            return false;
        }
        if (strcmp(id1.id, id2.id) != 0) {
            return false;
        }
        return true;
    }

    void testDeserializeString() {
        char *val_str;
        int rc = 0;
        int val = -1;
        struct iarchive *ia;
        struct buff_struct_2 *b;
        struct oarchive *oa = create_buffer_oarchive();
        oa->serialize_Int(oa, "int", &val);
        b = (struct buff_struct_2 *) oa->priv;
        ia = create_buffer_iarchive(b->buffer, b->len);
        rc = ia->deserialize_String(ia, "string", &val_str);
        CPPUNIT_ASSERT_EQUAL(-EINVAL, rc);
    }
        
    void testAcl() {
        int rc;
        struct ACL_vector aclvec;
        struct Stat stat;
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        rc = zoo_create(zk, "/acl", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        rc = zoo_get_acl(zk, "/acl", &aclvec, &stat  );
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        bool cmp = compareAcl(ZOO_OPEN_ACL_UNSAFE, aclvec);
        CPPUNIT_ASSERT_EQUAL(true, cmp);
        rc = zoo_set_acl(zk, "/acl", -1, &ZOO_READ_ACL_UNSAFE);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        rc = zoo_get_acl(zk, "/acl", &aclvec, &stat);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        cmp = compareAcl(ZOO_READ_ACL_UNSAFE, aclvec);
        CPPUNIT_ASSERT_EQUAL(true, cmp);
    }


    void testAuth() {
        int rc;
        count = 0;
        watchctx_t ctx1, ctx2, ctx3, ctx4, ctx5;
        zhandle_t *zk = createClient(&ctx1);
        struct ACL_vector nodeAcl;
        struct ACL acl_val;
        rc = zoo_add_auth(0, "", 0, 0, voidCompletion, (void*)-1);
        CPPUNIT_ASSERT_EQUAL((int) ZBADARGUMENTS, rc);

        rc = zoo_add_auth(zk, 0, 0, 0, voidCompletion, (void*)-1);
        CPPUNIT_ASSERT_EQUAL((int) ZBADARGUMENTS, rc);

        // auth as pat, create /tauth1, close session
        rc = zoo_add_auth(zk, "digest", "pat:passwd", 10, voidCompletion,
                          (void*)ZOK);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        waitForVoidCompletion(3);
        CPPUNIT_ASSERT(count == 0);

        rc = zoo_create(zk, "/tauth1", "", 0, &ZOO_CREATOR_ALL_ACL, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        {
            //create a new client
            zk = createClient(&ctx4);
            rc = zoo_add_auth(zk, "digest", "", 0, voidCompletion, (void*)ZOK);
            CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
            waitForVoidCompletion(3);
            CPPUNIT_ASSERT(count == 0);

            rc = zoo_add_auth(zk, "digest", "", 0, voidCompletion, (void*)ZOK);
            CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
            waitForVoidCompletion(3);
            CPPUNIT_ASSERT(count == 0);
        }

        //create a new client
        zk = createClient(&ctx2);

        rc = zoo_add_auth(zk, "digest", "pat:passwd2", 11, voidCompletion,
                          (void*)ZOK);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        waitForVoidCompletion(3);
        CPPUNIT_ASSERT(count == 0);

        char buf[1024];
        int blen = sizeof(buf);
        struct Stat stat;
        rc = zoo_get(zk, "/tauth1", 0, buf, &blen, &stat);
        CPPUNIT_ASSERT_EQUAL((int)ZNOAUTH, rc);
        // add auth pat w/correct pass verify success
        rc = zoo_add_auth(zk, "digest", "pat:passwd", 10, voidCompletion,
                          (void*)ZOK);

        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        rc = zoo_get(zk, "/tauth1", 0, buf, &blen, &stat);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        waitForVoidCompletion(3);
        CPPUNIT_ASSERT(count == 0);
        //create a new client
        zk = createClient(&ctx3);
        rc = zoo_add_auth(zk, "digest", "pat:passwd", 10, voidCompletion, (void*) ZOK);
        waitForVoidCompletion(3);
        CPPUNIT_ASSERT(count == 0);
        rc = zoo_add_auth(zk, "ip", "none", 4, voidCompletion, (void*)ZOK);
        //make the server forget the auths
        waitForVoidCompletion(3);
        CPPUNIT_ASSERT(count == 0);

        stopServer();
        CPPUNIT_ASSERT(ctx3.waitForDisconnected(zk));
        startServer();
        CPPUNIT_ASSERT(ctx3.waitForConnected(zk));
        // now try getting the data
        rc = zoo_get(zk, "/tauth1", 0, buf, &blen, &stat);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        // also check for get
        rc = zoo_get_acl(zk, "/", &nodeAcl, &stat);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        // check if the acl has all the perms
        CPPUNIT_ASSERT_EQUAL((int)1, (int)nodeAcl.count);
        acl_val = *(nodeAcl.data);
        CPPUNIT_ASSERT_EQUAL((int) acl_val.perms, ZOO_PERM_ALL);
        // verify on root node
        rc = zoo_set_acl(zk, "/", -1, &ZOO_CREATOR_ALL_ACL);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);

        rc = zoo_set_acl(zk, "/", -1, &ZOO_OPEN_ACL_UNSAFE);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);

        //[ZOOKEEPER-1108], test that auth info is sent to server, if client is not
        //connected to server when zoo_add_auth was called.
        zhandle_t *zk_auth = zookeeper_init(hostPorts, NULL, 10000, 0, NULL, 0);
        rc = zoo_add_auth(zk_auth, "digest", "pat:passwd", 10, voidCompletion, (void*)ZOK);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        sleep(2);
        CPPUNIT_ASSERT(count == 1);
        count  = 0;
        CPPUNIT_ASSERT_EQUAL((int) ZOK, zookeeper_close(zk_auth));

        struct sockaddr addr;
        socklen_t addr_len = sizeof(addr);
        zk = createClient(&ctx5);
        stopServer();
        CPPUNIT_ASSERT(ctx5.waitForDisconnected(zk));
        CPPUNIT_ASSERT(zookeeper_get_connected_host(zk, &addr, &addr_len) == NULL);
        addr_len = sizeof(addr);
        startServer();
        CPPUNIT_ASSERT(ctx5.waitForConnected(zk));
        CPPUNIT_ASSERT(zookeeper_get_connected_host(zk, &addr, &addr_len) != NULL);
    }

    void testCreate() {
        watchctx_t ctx;
        int rc = 0;
        zhandle_t *zk = createClient(&ctx);
        CPPUNIT_ASSERT(zk);
        char pathbuf[80];

        struct Stat stat_a = {0};
        struct Stat stat_b = {0};
        rc = zoo_create2(zk, "/testcreateA", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, 0, pathbuf, sizeof(pathbuf), &stat_a);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        CPPUNIT_ASSERT(strcmp(pathbuf, "/testcreateA") == 0);
        CPPUNIT_ASSERT(stat_a.czxid > 0);
        CPPUNIT_ASSERT(stat_a.mtime > 0);

        rc = zoo_create2(zk, "/testcreateB", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, 0, pathbuf, sizeof(pathbuf), &stat_b);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        CPPUNIT_ASSERT(strcmp(pathbuf, "/testcreateB") == 0);
        CPPUNIT_ASSERT(stat_b.czxid > 0);
        CPPUNIT_ASSERT(stat_b.mtime > 0);

        // Should get different Stats back from different creates
        CPPUNIT_ASSERT(Stat_eq(&stat_a, &stat_b) != 1);
    }

    void testGetChildren2() {
        int rc;
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);

        rc = zoo_create(zk, "/parent", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        rc = zoo_create(zk, "/parent/child_a", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        rc = zoo_create(zk, "/parent/child_b", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        rc = zoo_create(zk, "/parent/child_c", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        rc = zoo_create(zk, "/parent/child_d", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        struct String_vector strings;
        struct Stat stat_a, stat_b;

        rc = zoo_get_children2(zk, "/parent", 0, &strings, &stat_a);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        rc = zoo_exists(zk, "/parent", 0, &stat_b);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        CPPUNIT_ASSERT(Stat_eq(&stat_a, &stat_b));
        CPPUNIT_ASSERT(stat_a.numChildren == 4);
    }

    void testIPV6() {
        watchctx_t ctx;
        zhandle_t *zk = createClient("::1:22181", &ctx);
        CPPUNIT_ASSERT(zk);
        int rc = 0;
        rc = zoo_create(zk, "/ipv6", NULL, -1,
                        &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
    }

    void testNullData() {
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        CPPUNIT_ASSERT(zk);
        int rc = 0;
        rc = zoo_create(zk, "/mahadev", NULL, -1,
                        &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        char buffer[512];
        struct Stat stat;
        int len = 512;
        rc = zoo_wget(zk, "/mahadev", NULL, NULL, buffer, &len, &stat);
        CPPUNIT_ASSERT_EQUAL( -1, len);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        rc = zoo_set(zk, "/mahadev", NULL, -1, -1);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        rc = zoo_wget(zk, "/mahadev", NULL, NULL, buffer, &len, &stat);
        CPPUNIT_ASSERT_EQUAL( -1, len);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
    }

    void testPath() {
        watchctx_t ctx;
        char pathbuf[20];
        zhandle_t *zk = createClient(&ctx);
        CPPUNIT_ASSERT(zk);
        int rc = 0;

        memset(pathbuf, 'X', 20);
        rc = zoo_create(zk, "/testpathpath0", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, 0, pathbuf, 0);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        CPPUNIT_ASSERT_EQUAL('X', pathbuf[0]);

        rc = zoo_create(zk, "/testpathpath1", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, 0, pathbuf, 1);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        CPPUNIT_ASSERT(strlen(pathbuf) == 0);

        rc = zoo_create(zk, "/testpathpath2", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, 0, pathbuf, 2);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        CPPUNIT_ASSERT(strcmp(pathbuf, "/") == 0);

        rc = zoo_create(zk, "/testpathpath3", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, 0, pathbuf, 3);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        CPPUNIT_ASSERT(strcmp(pathbuf, "/t") == 0);

        rc = zoo_create(zk, "/testpathpath7", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, 0, pathbuf, 15);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        CPPUNIT_ASSERT(strcmp(pathbuf, "/testpathpath7") == 0);

        rc = zoo_create(zk, "/testpathpath8", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, 0, pathbuf, 16);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        CPPUNIT_ASSERT(strcmp(pathbuf, "/testpathpath8") == 0);
    }

    void testPathValidation() {
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        CPPUNIT_ASSERT(zk);

        verifyCreateFails(0, zk);
        verifyCreateFails("", zk);
        verifyCreateFails("//", zk);
        verifyCreateFails("///", zk);
        verifyCreateFails("////", zk);
        verifyCreateFails("/.", zk);
        verifyCreateFails("/..", zk);
        verifyCreateFails("/./", zk);
        verifyCreateFails("/../", zk);
        verifyCreateFails("/foo/./", zk);
        verifyCreateFails("/foo/../", zk);
        verifyCreateFails("/foo/.", zk);
        verifyCreateFails("/foo/..", zk);
        verifyCreateFails("/./.", zk);
        verifyCreateFails("/../..", zk);
        verifyCreateFails("/foo/bar/", zk);
        verifyCreateFails("/foo//bar", zk);
        verifyCreateFails("/foo/bar//", zk);

        verifyCreateFails("foo", zk);
        verifyCreateFails("a", zk);

        // verify that trailing fails, except for seq which adds suffix
        verifyCreateOk("/createseq", zk);
        verifyCreateFails("/createseq/", zk);
        verifyCreateOkSeq("/createseq/", zk);
        verifyCreateOkSeq("/createseq/.", zk);
        verifyCreateOkSeq("/createseq/..", zk);
        verifyCreateFailsSeq("/createseq//", zk);
        verifyCreateFailsSeq("/createseq/./", zk);
        verifyCreateFailsSeq("/createseq/../", zk);

        verifyCreateOk("/.foo", zk);
        verifyCreateOk("/.f.", zk);
        verifyCreateOk("/..f", zk);
        verifyCreateOk("/..f..", zk);
        verifyCreateOk("/f.c", zk);
        verifyCreateOk("/f", zk);
        verifyCreateOk("/f/.f", zk);
        verifyCreateOk("/f/f.", zk);
        verifyCreateOk("/f/..f", zk);
        verifyCreateOk("/f/f..", zk);
        verifyCreateOk("/f/.f/f", zk);
        verifyCreateOk("/f/f./f", zk);
    }

    void testChroot() {
        // the c client async callbacks do
        // not callback with the path, so
        // we dont need to test taht for now
        // we should fix that though soon!
        watchctx_t ctx, ctx_ch;
        zhandle_t *zk, *zk_ch;
        char buf[60];
        int rc, len;
        struct Stat stat;
        const char* data = "garbage";
        const char* retStr = "/chroot";
        const char* root= "/";
        zk_ch = createchClient(&ctx_ch, "127.0.0.1:22181/testch1/mahadev");
        CPPUNIT_ASSERT(zk_ch != NULL);
        zk = createClient(&ctx);
        // first test with a NULL zk handle, make sure client library does not
        // dereference a null pointer, but instead returns ZBADARGUMENTS
        rc = zoo_create(NULL, "/testch1", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int) ZBADARGUMENTS, rc);
        rc = zoo_create(zk, "/testch1", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        rc = zoo_create(zk, "/testch1/mahadev", data, 7, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        // try an exists with /
        len = 60;
        rc = zoo_get(zk_ch, "/", 0, buf, &len, &stat);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        //check if the data is the same
        CPPUNIT_ASSERT(strncmp(buf, data, 7) == 0);
        //check for watches
        rc = zoo_wexists(zk_ch, "/chroot", watcher_chroot_fn, (void *) retStr, &stat);
        //now check if we can do create/delete/get/sets/acls/getChildren and others
        //check create
        rc = zoo_create(zk_ch, "/chroot", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0,0);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        waitForChrootWatch(3);
        CPPUNIT_ASSERT(count == 0);
        rc = zoo_create(zk_ch, "/chroot/child", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        rc = zoo_exists(zk, "/testch1/mahadev/chroot/child", 0, &stat);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);

        rc = zoo_delete(zk_ch, "/chroot/child", -1);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        rc = zoo_exists(zk, "/testch1/mahadev/chroot/child", 0, &stat);
        CPPUNIT_ASSERT_EQUAL((int) ZNONODE, rc);
        rc = zoo_wget(zk_ch, "/chroot", watcher_chroot_fn, (char*) retStr,
                      buf, &len, &stat);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        rc = zoo_set(zk_ch, "/chroot",buf, 3, -1);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        waitForChrootWatch(3);
        CPPUNIT_ASSERT(count == 0);
        // check for getchildren
        struct String_vector children;
        rc = zoo_get_children(zk_ch, "/", 0, &children);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL((int)1, (int)children.count);
        //check if te child if chroot
        CPPUNIT_ASSERT(strcmp((retStr+1), children.data[0]) == 0);
        // check for get/set acl
        struct ACL_vector acl;
        rc = zoo_get_acl(zk_ch, "/", &acl, &stat);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT_EQUAL((int)1, (int)acl.count);
        CPPUNIT_ASSERT_EQUAL((int)ZOO_PERM_ALL, (int)acl.data->perms);
        // set acl
        rc = zoo_set_acl(zk_ch, "/chroot", -1,  &ZOO_READ_ACL_UNSAFE);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        // see if you add children
        rc = zoo_create(zk_ch, "/chroot/child1", "",0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZNOAUTH, rc);
        //add wget children test
        rc = zoo_wget_children(zk_ch, "/", watcher_chroot_fn, (char*) root, &children);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        //now create a node
        rc = zoo_create(zk_ch, "/child2", "",0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        waitForChrootWatch(3);
        CPPUNIT_ASSERT(count == 0);
        //check for one async call just to make sure
        rc = zoo_acreate(zk_ch, "/child3", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0,
                         create_completion_fn, 0);
        waitForCreateCompletion(3);
        CPPUNIT_ASSERT(count == 0);

        //ZOOKEEPER-1027 correctly return path_buffer without prefixed chroot
        const char* path = "/zookeeper1027";
        char path_buffer[1024];
        int path_buffer_len=sizeof(path_buffer);
        rc = zoo_create(zk_ch, path, "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, path_buffer, path_buffer_len);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        CPPUNIT_ASSERT_EQUAL(string(path), string(path_buffer));
    }

    // Test creating normal handle via zookeeper_init then explicitly setting callback
    void testLogCallbackSet()
    {
        watchctx_t ctx;
        CPPUNIT_ASSERT(logMessages.empty());
        zhandle_t *zk = createClient(&ctx);

        zoo_set_log_callback(zk, &logMessageHandler);
        CPPUNIT_ASSERT_EQUAL(zoo_get_log_callback(zk), &logMessageHandler);

        // Log 10 messages and ensure all go to callback
        int expected = 10;
        for (int i = 0; i < expected; i++)
        {
            LOG_INFO(LOGCALLBACK(zk), "%s #%d", __FUNCTION__, i);
        }
        CPPUNIT_ASSERT(expected == logMessages.size());
    }

    // Test creating handle via zookeeper_init2 to ensure all connection messages go to callback
    void testLogCallbackInit()
    {
        logMessages.clear();
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx, &logMessageHandler);
        CPPUNIT_ASSERT_EQUAL(zoo_get_log_callback(zk), &logMessageHandler);

        // All the connection messages should have gone to the callback -- don't
        // want this to be a maintenance issue so we're not asserting exact count
        int numBefore = logMessages.size();
        CPPUNIT_ASSERT(numBefore != 0);

        // Log 10 messages and ensure all go to callback
        int expected = 10;
        for (int i = 0; i < expected; i++)
        {
            LOG_INFO(LOGCALLBACK(zk), "%s #%d", __FUNCTION__, i);
        }
        CPPUNIT_ASSERT(logMessages.size() == numBefore + expected);
    }

    // Test clearing log callback -- logging should resume going to logstream
    void testLogCallbackClear()
    {
        logMessages.clear();
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx, &logMessageHandler);
        CPPUNIT_ASSERT_EQUAL(zoo_get_log_callback(zk), &logMessageHandler);

        // All the connection messages should have gone to the callback -- again, we don't
        // want this to be a maintenance issue so we're not asserting exact count
        int numBefore = logMessages.size();
        CPPUNIT_ASSERT(numBefore > 0);

        // Clear log_callback
        zoo_set_log_callback(zk, NULL);

        // Future log messages should go to logstream not callback
        LOG_INFO(LOGCALLBACK(zk), __FUNCTION__);
        int numAfter = logMessages.size();
        CPPUNIT_ASSERT_EQUAL(numBefore, numAfter);
    }

    void testAsyncWatcherAutoReset()
    {
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        watchctx_t lctx[COUNT];
        int i;
        char path[80];
        int rc;
        evt_t evt;

        async_zk = zk;
        for(i = 0; i < COUNT; i++) {
            sprintf(path, "/awar%d", i);
            rc = zoo_awexists(zk, path, watcher, &lctx[i], statCompletion, (void*)ZNONODE);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        }

        yield(zk, 0);

        for(i = 0; i < COUNT/4; i++) {
            sprintf(path, "/awar%d", i);
            rc = zoo_acreate(zk, path, "", 0,  &ZOO_OPEN_ACL_UNSAFE, 0,
                stringCompletion, strdup(path));
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        }

        for(i = COUNT/4; i < COUNT/2; i++) {
            sprintf(path, "/awar%d", i);
            rc = zoo_acreate2(zk, path, "", 0,  &ZOO_OPEN_ACL_UNSAFE, 0,
                stringStatCompletion, strdup(path));
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        }

        yield(zk, 3);
        for(i = 0; i < COUNT/2; i++) {
            sprintf(path, "/awar%d", i);
            CPPUNIT_ASSERT_MESSAGE(path, waitForEvent(zk, &lctx[i], 5));
            evt = lctx[i].getEvent();
            CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path.c_str(), ZOO_CREATED_EVENT, evt.type);
            CPPUNIT_ASSERT_EQUAL(string(path), evt.path);
        }

        for(i = COUNT/2 + 1; i < COUNT*10; i++) {
            sprintf(path, "/awar%d", i);
            rc = zoo_acreate(zk, path, "", 0,  &ZOO_OPEN_ACL_UNSAFE, 0, stringCompletion, strdup(path));
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        }

        yield(zk, 1);
        stopServer();
        CPPUNIT_ASSERT(ctx.waitForDisconnected(zk));
        startServer();
        CPPUNIT_ASSERT(ctx.waitForConnected(zk));
        yield(zk, 3);
        for(i = COUNT/2+1; i < COUNT; i++) {
            sprintf(path, "/awar%d", i);
            CPPUNIT_ASSERT_MESSAGE(path, waitForEvent(zk, &lctx[i], 5));
            evt = lctx[i].getEvent();
            CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_CREATED_EVENT, evt.type);
            CPPUNIT_ASSERT_EQUAL(string(path), evt.path);
        }
    }

    void testWatcherAutoReset(zhandle_t *zk, watchctx_t *ctxGlobal,
                              watchctx_t *ctxLocal)
    {
        bool isGlobal = (ctxGlobal == ctxLocal);
        int rc;
        struct Stat stat;
        char buf[1024];
        int blen;
        struct String_vector strings;
        const char *testName;

        rc = zoo_create(zk, "/watchtest", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        rc = zoo_create(zk, "/watchtest/child", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, ZOO_EPHEMERAL, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        if (isGlobal) {
            testName = "GlobalTest";
            rc = zoo_get_children(zk, "/watchtest", 1, &strings);
            deallocate_String_vector(&strings);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
            blen = sizeof(buf);
            rc = zoo_get(zk, "/watchtest/child", 1, buf, &blen, &stat);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
            rc = zoo_exists(zk, "/watchtest/child2", 1, &stat);
            CPPUNIT_ASSERT_EQUAL((int)ZNONODE, rc);
        } else {
            testName = "LocalTest";
            rc = zoo_wget_children(zk, "/watchtest", watcher, ctxLocal,
                                 &strings);
            deallocate_String_vector(&strings);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
            blen = sizeof(buf);
            rc = zoo_wget(zk, "/watchtest/child", watcher, ctxLocal,
                         buf, &blen, &stat);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
            rc = zoo_wexists(zk, "/watchtest/child2", watcher, ctxLocal,
                            &stat);
            CPPUNIT_ASSERT_EQUAL((int)ZNONODE, rc);
        }

        CPPUNIT_ASSERT(ctxLocal->countEvents() == 0);

        stopServer();
        CPPUNIT_ASSERT_MESSAGE(testName, ctxGlobal->waitForDisconnected(zk));
        startServer();
        CPPUNIT_ASSERT_MESSAGE(testName, ctxLocal->waitForConnected(zk));

        CPPUNIT_ASSERT(ctxLocal->countEvents() == 0);

        rc = zoo_set(zk, "/watchtest/child", "1", 1, -1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        struct Stat stat1, stat2;
        rc = zoo_set2(zk, "/watchtest/child", "1", 1, -1, &stat1);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        CPPUNIT_ASSERT(stat1.version >= 0);
        rc = zoo_set2(zk, "/watchtest/child", "1", 1, stat1.version, &stat2);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        rc = zoo_set(zk, "/watchtest/child", "1", 1, stat2.version);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        rc = zoo_create(zk, "/watchtest/child2", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        CPPUNIT_ASSERT_MESSAGE(testName, waitForEvent(zk, ctxLocal, 5));

        evt_t evt = ctxLocal->getEvent();
        CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_CHANGED_EVENT, evt.type);
        CPPUNIT_ASSERT_EQUAL(string("/watchtest/child"), evt.path);

        CPPUNIT_ASSERT_MESSAGE(testName, waitForEvent(zk, ctxLocal, 5));
        // The create will trigget the get children and the
        // exists watches
        evt = ctxLocal->getEvent();
        CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_CREATED_EVENT, evt.type);
        CPPUNIT_ASSERT_EQUAL(string("/watchtest/child2"), evt.path);
        CPPUNIT_ASSERT_MESSAGE(testName, waitForEvent(zk, ctxLocal, 5));
        evt = ctxLocal->getEvent();
        CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_CHILD_EVENT, evt.type);
        CPPUNIT_ASSERT_EQUAL(string("/watchtest"), evt.path);

        // Make sure Pings are giving us problems
        sleep(5);

        CPPUNIT_ASSERT(ctxLocal->countEvents() == 0);

        stopServer();
        CPPUNIT_ASSERT_MESSAGE(testName, ctxGlobal->waitForDisconnected(zk));
        startServer();
        CPPUNIT_ASSERT_MESSAGE(testName, ctxGlobal->waitForConnected(zk));

        if (isGlobal) {
            testName = "GlobalTest";
            rc = zoo_get_children(zk, "/watchtest", 1, &strings);
            deallocate_String_vector(&strings);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
            blen = sizeof(buf);
            rc = zoo_get(zk, "/watchtest/child", 1, buf, &blen, &stat);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
            rc = zoo_exists(zk, "/watchtest/child2", 1, &stat);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        } else {
            testName = "LocalTest";
            rc = zoo_wget_children(zk, "/watchtest", watcher, ctxLocal,
                                 &strings);
            deallocate_String_vector(&strings);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
            blen = sizeof(buf);
            rc = zoo_wget(zk, "/watchtest/child", watcher, ctxLocal,
                         buf, &blen, &stat);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
            rc = zoo_wexists(zk, "/watchtest/child2", watcher, ctxLocal,
                            &stat);
            CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
        }

        zoo_delete(zk, "/watchtest/child2", -1);

        CPPUNIT_ASSERT_MESSAGE(testName, waitForEvent(zk, ctxLocal, 5));

        evt = ctxLocal->getEvent();
        CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_DELETED_EVENT, evt.type);
        CPPUNIT_ASSERT_EQUAL(string("/watchtest/child2"), evt.path);

        CPPUNIT_ASSERT_MESSAGE(testName, waitForEvent(zk, ctxLocal, 5));
        evt = ctxLocal->getEvent();
        CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_CHILD_EVENT, evt.type);
        CPPUNIT_ASSERT_EQUAL(string("/watchtest"), evt.path);

        stopServer();
        CPPUNIT_ASSERT_MESSAGE(testName, ctxGlobal->waitForDisconnected(zk));
        startServer();
        CPPUNIT_ASSERT_MESSAGE(testName, ctxLocal->waitForConnected(zk));

        zoo_delete(zk, "/watchtest/child", -1);
        zoo_delete(zk, "/watchtest", -1);

        CPPUNIT_ASSERT_MESSAGE(testName, waitForEvent(zk, ctxLocal, 5));

        evt = ctxLocal->getEvent();
        CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_DELETED_EVENT, evt.type);
        CPPUNIT_ASSERT_EQUAL(string("/watchtest/child"), evt.path);

        // Make sure nothing is straggling
        sleep(1);
        CPPUNIT_ASSERT(ctxLocal->countEvents() == 0);
    }

    void testWatcherAutoResetWithGlobal()
    {
      {
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        int rc = zoo_create(zk, "/testarwg", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        rc = zoo_create(zk, "/testarwg/arwg", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
      }

      {
        watchctx_t ctx;
        zhandle_t *zk = createchClient(&ctx, "127.0.0.1:22181/testarwg/arwg");

        testWatcherAutoReset(zk, &ctx, &ctx);
      }
    }

    void testWatcherAutoResetWithLocal()
    {
      {
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        int rc = zoo_create(zk, "/testarwl", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
        rc = zoo_create(zk, "/testarwl/arwl", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
      }

      {
        watchctx_t ctx;
        watchctx_t lctx;
        zhandle_t *zk = createchClient(&ctx, "127.0.0.1:22181/testarwl/arwl");
        testWatcherAutoReset(zk, &ctx, &lctx);
      }
    }

    void testLastZxid() {
      // ZOOKEEPER-1417: Test that c-client only update last zxid upon
      // receiving request response only.
      const int timeout = 5000;
      int rc;
      watchctx_t ctx1, ctx2;
      zhandle_t *zk1 = createClient(&ctx1);
      zhandle_t *zk2 = createClient(&ctx2);
      CPPUNIT_ASSERT(zk1);
      CPPUNIT_ASSERT(zk2);

      int64_t original = zk2->last_zxid;

      // Create txn to increase system zxid
      rc = zoo_create(zk1, "/lastzxid", "", 0,
                      &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
      CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

      // This should be enough time for zk2 to receive ping request
      usleep(timeout/2 * 1000);

      // check that zk1's last zxid is updated
      struct Stat stat;
      rc = zoo_exists(zk1, "/lastzxid", 0, &stat);
      CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
      CPPUNIT_ASSERT_EQUAL((int64_t) zk1->last_zxid, stat.czxid);
      // zk2's last zxid should remain the same
      CPPUNIT_ASSERT_EQUAL(original, (int64_t) zk2->last_zxid);

      // Perform read and also register a watch
      rc = zoo_wexists(zk2, "/lastzxid", watcher, &ctx2, &stat);
      CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);
      int64_t updated = zk2->last_zxid;
      // check that zk2's last zxid is updated
      CPPUNIT_ASSERT_EQUAL(updated, stat.czxid);

      // Increment system zxid again
      rc = zoo_set(zk1, "/lastzxid", NULL, -1, -1);
      CPPUNIT_ASSERT_EQUAL((int) ZOK, rc);

      // Wait for zk2 to get watch event
      CPPUNIT_ASSERT(waitForEvent(zk2, &ctx2, 5));
      // zk2's last zxid should remain the same
      CPPUNIT_ASSERT_EQUAL(updated, (int64_t) zk2->last_zxid);
    }

	static void watcher_rw(zhandle_t *zh,
		                   int type,
		                   int state,
		                   const char *path,
		                   void *ctx) {
		count++;
	}

	static void watcher_rw2(zhandle_t *zh,
		                    int type,
		                    int state,
		                    const char *path,
		                    void *ctx) {
		count++;
    }

    void testRemoveWatchers() {
      char *path = "/something";
      char buf[1024];
      int blen = sizeof(buf);		
      int rc;
      watchctx_t ctx;
      zhandle_t *zk;

      /* setup path */
      zk = createClient(&ctx);
      CPPUNIT_ASSERT(zk);

      rc = zoo_create(zk, path, "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
      CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

      rc = zoo_create(zk, "/something2", "", 0,
                      &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
      CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

      /* remove all watchers */
      count = 0;
      rc = zoo_wget(zk, path, watcher_rw, NULL, buf, &blen, NULL);
      rc = zoo_wget(zk, path, watcher_rw2, NULL, buf, &blen, NULL);
      rc = zoo_remove_all_watches(zk, path, ZWATCHTYPE_ANY, 0);
			CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
			rc = zoo_set(zk, path, "nowatch", 7, -1);
      CPPUNIT_ASSERT(count == 0);
			
      /* remove a specific watcher before it's added (should fail) */
      rc = zoo_remove_watches(zk, path, ZWATCHTYPE_DATA,
                               watcher_rw, NULL, 0);
      CPPUNIT_ASSERT_EQUAL((int)ZNOWATCHER, rc);

      /* now add a specific watcher and then remove it */
      rc = zoo_wget(zk, path, watcher_rw, NULL,
                    buf, &blen, NULL);
      CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
      rc = zoo_remove_watches(zk, path, ZWATCHTYPE_DATA,
                               watcher_rw, NULL, 0);
      CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

      /* ditto for children watcher */
      rc = zoo_remove_watches(zk, path, ZWATCHTYPE_CHILD,
                               watcher_rw, NULL, 0);
      CPPUNIT_ASSERT_EQUAL((int)ZNOWATCHER, rc);

      struct String_vector str_vec = {0, NULL};
      rc = zoo_wget_children(zk, path, watcher_rw, NULL,
                             &str_vec);
      CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
      rc = zoo_remove_watches(zk, path, ZWATCHTYPE_CHILD,
                               watcher_rw, NULL, 0);
      CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

      /* add a watch, stop the server, and have remove fail */
      rc = zoo_wget(zk, path, watcher_rw, NULL,
                    buf, &blen, NULL);
      CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);
      stopServer();
      ctx.waitForDisconnected(zk);
      rc = zoo_remove_watches(zk, path, ZWATCHTYPE_DATA,
                               watcher_rw, NULL, 0);
      CPPUNIT_ASSERT_EQUAL((int)ZCONNECTIONLOSS, rc);

      zookeeper_close(zk);

      /* bring the server back */
      startServer();
      zk = createClient(&ctx);

      /* add a watch, stop the server, and remove it locally */
      void* ctx1=(void*)0x1;
      void* ctx2=(void*)0x2;

      rc = zoo_wget(zk, path, watcher_rw, ctx1,
                    buf, &blen, NULL);
      CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

      rc = zoo_wget(zk, "/something2", watcher_rw, ctx2,
                         buf, &blen, NULL);
      CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

      stopServer();
      rc = zoo_remove_watches(zk, path, ZWATCHTYPE_DATA,
                               watcher_rw, ctx1, 1);
      CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

      rc = zoo_remove_watches(zk, path, ZWATCHTYPE_DATA,
                                    watcher_rw, ctx1, 1);
      CPPUNIT_ASSERT_EQUAL((int)ZNOWATCHER, rc);

      rc = zoo_remove_watches(zk, "/something2", ZWATCHTYPE_DATA,
                                          watcher_rw, ctx2, 1);
      CPPUNIT_ASSERT_EQUAL((int)ZOK,rc);
    }
};

volatile int Zookeeper_simpleSystem::count;
zhandle_t *Zookeeper_simpleSystem::async_zk;
const char Zookeeper_simpleSystem::hostPorts[] = "127.0.0.1:22181";
CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_simpleSystem);
#endif
