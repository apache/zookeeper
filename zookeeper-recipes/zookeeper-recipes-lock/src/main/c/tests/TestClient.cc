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

#include <stdlib.h>
#include <sys/select.h>
#include <cppunit/TestAssert.h>


using namespace std;

#include <cstring>
#include <list>

#include <unistd.h>
#include <zookeeper.h>
#include <zoo_lock.h>

static void yield(zhandle_t *zh, int i)
{
    sleep(i);
}

typedef struct evt {
    string path;
    int type;
} evt_t;

typedef struct watchCtx {
private:
    list<evt_t> events;
public:
    bool connected;
    zhandle_t *zh;
    
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
        evt = events.front();
        events.pop_front();
        return evt;
    }

    int countEvents() {
        int count;
        count = events.size();
        return count;
    }

    void putEvent(evt_t evt) {
        events.push_back(evt);
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

class Zookeeper_locktest : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_locktest);
    CPPUNIT_TEST(testlock);
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
        zhandle_t *zk = zookeeper_init(hostPorts, watcher, 10000, 0,
                                       ctx, 0);
        ctx->zh = zk;
        sleep(1);
        return zk;
    }
    
public:

#define ZKSERVER_CMD "./tests/zkServer.sh"

    void setUp()
    {
        char cmd[1024];
        sprintf(cmd, "%s startClean %s", ZKSERVER_CMD, getHostPorts());
        CPPUNIT_ASSERT(system(cmd) == 0);
    }
    

    void startServer() {
        char cmd[1024];
        sprintf(cmd, "%s start %s", ZKSERVER_CMD, getHostPorts());
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    void stopServer() {
        tearDown();
    }

    void tearDown()
    {
        char cmd[1024];
        sprintf(cmd, "%s stop %s", ZKSERVER_CMD, getHostPorts());
        CPPUNIT_ASSERT(system(cmd) == 0);
    }
    

    void testlock()
    {
        watchctx_t ctx;
        int rc;
        struct Stat stat;
        char buf[1024];
        int blen;
        struct String_vector strings;
        const char *testName;
        zkr_lock_mutex_t mutexes[3];
        int count = 3;
        int i = 0;
        char* path = "/test-lock";
        for (i=0; i< 3; i++) {
            zhandle_t *zh = createClient(&ctx);
            zkr_lock_init(&mutexes[i], zh, path, &ZOO_OPEN_ACL_UNSAFE);
            zkr_lock_lock(&mutexes[i]);
        }
        sleep(30);
        zkr_lock_mutex leader = mutexes[0];
        zkr_lock_mutex mutex;
        int ret = strcmp(leader.id, leader.ownerid);
        CPPUNIT_ASSERT(ret == 0);
        for(i=1; i < count; i++) {
            mutex = mutexes[i];
            CPPUNIT_ASSERT(strcmp(mutex.id, mutex.ownerid) != 0);
        } 
        zkr_lock_unlock(&leader);
        sleep(30);
        zkr_lock_mutex secondleader = mutexes[1];
        CPPUNIT_ASSERT(strcmp(secondleader.id , secondleader.ownerid) == 0);
        for (i=2; i<count; i++) {
            mutex = mutexes[i];
            CPPUNIT_ASSERT(strcmp(mutex.id, mutex.ownerid) != 0);
        }
    }

};

const char Zookeeper_locktest::hostPorts[] = "127.0.0.1:22181";
CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_locktest);
