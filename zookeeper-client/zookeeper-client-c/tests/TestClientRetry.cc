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

class Zookeeper_clientretry : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_clientretry);
#ifdef THREADED
    CPPUNIT_TEST(testRetry);
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
        zhandle_t *zk = zookeeper_init(hostPorts, watcher, 10000, 0,
                                       ctx, 0);
        ctx->zh = zk;
        sleep(1);
        return zk;
    }
    
    FILE *logfile;
public:

    Zookeeper_clientretry() {
      logfile = openlogfile("Zookeeper_clientretry");
    }

    ~Zookeeper_clientretry() {
      if (logfile) {
        fflush(logfile);
        fclose(logfile);
        logfile = 0;
      }
    }

    void setUp()
    {
        zoo_set_log_stream(logfile);

        char cmd[1024];
        sprintf(cmd, "%s stop %s", ZKSERVER_CMD, getHostPorts());
        CPPUNIT_ASSERT(system(cmd) == 0);

        /* we are testing that if max cnxns is exceeded the server does the right thing */
        sprintf(cmd, "export ZKMAXCNXNS=1;%s startClean %s", ZKSERVER_CMD, getHostPorts());
        CPPUNIT_ASSERT(system(cmd) == 0);

        struct sigaction act;
        act.sa_handler = SIG_IGN;
        sigemptyset(&act.sa_mask);
        act.sa_flags = 0;
        CPPUNIT_ASSERT(sigaction(SIGPIPE, &act, NULL) == 0);
    }
    
    void tearDown()
    {
        char cmd[1024];
        sprintf(cmd, "%s stop %s", ZKSERVER_CMD, getHostPorts());
        CPPUNIT_ASSERT(system(cmd) == 0);

        /* restart the server in "normal" mode */
        sprintf(cmd, "%s startClean %s", ZKSERVER_CMD, getHostPorts());
        CPPUNIT_ASSERT(system(cmd) == 0);

        struct sigaction act;
        act.sa_handler = SIG_IGN;
        sigemptyset(&act.sa_mask);
        act.sa_flags = 0;
        CPPUNIT_ASSERT(sigaction(SIGPIPE, &act, NULL) == 0);
    }

    bool waitForEvent(zhandle_t *zh, watchctx_t *ctx, int seconds) {
        time_t expires = time(0) + seconds;
        while(ctx->countEvents() == 0 && time(0) < expires) {
            yield(zh, 1);
        }
        return ctx->countEvents() > 0;
    }

    static zhandle_t *async_zk;

    void testRetry()
    {
      watchctx_t ctx1, ctx2;
      zhandle_t *zk1 = createClient(&ctx1);
      CPPUNIT_ASSERT_EQUAL(true, ctx1.waitForConnected(zk1));
      zhandle_t *zk2 = createClient(&ctx2);
      zookeeper_close(zk1);
      CPPUNIT_ASSERT_EQUAL(true, ctx2.waitForConnected(zk2));
      ctx1.zh = 0;  
    }
};

zhandle_t *Zookeeper_clientretry::async_zk;
const char Zookeeper_clientretry::hostPorts[] = "127.0.0.1:22181";
CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_clientretry);
