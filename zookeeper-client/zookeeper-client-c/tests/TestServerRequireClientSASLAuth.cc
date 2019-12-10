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

#include <sys/socket.h>
#include <unistd.h>

#include <zookeeper.h>

#include "Util.h"
#include "WatchUtil.h"

ZOOAPI int zoo_create2(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl, int mode,
        char *path_buffer, int path_buffer_len, struct Stat *stat);

class Zookeeper_serverRequireClientSASL : public CPPUNIT_NS::TestFixture {
    CPPUNIT_TEST_SUITE(Zookeeper_serverRequireClientSASL);
#ifdef THREADED
    CPPUNIT_TEST(testServerRequireClientSASL);
#endif
    CPPUNIT_TEST_SUITE_END();
    FILE *logfile;
    static const char hostPorts[];
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

public:
    Zookeeper_serverRequireClientSASL() {
      logfile = openlogfile("Zookeeper_serverRequireClientSASL");
    }

    ~Zookeeper_serverRequireClientSASL() {
      if (logfile) {
        fflush(logfile);
        fclose(logfile);
        logfile = 0;
      }
    }

    void setUp() {
        zoo_set_log_stream(logfile);
    }

    void startServer() {
        char cmd[1024];
        sprintf(cmd, "%s startRequireSASLAuth", ZKSERVER_CMD);
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    void stopServer() {
        char cmd[1024];
        sprintf(cmd, "%s stop", ZKSERVER_CMD);
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    void testServerRequireClientSASL() {
        startServer();

        watchctx_t ctx;
        int rc = 0;
        zhandle_t *zk = zookeeper_init(hostPorts, watcher, 10000, 0, &ctx, 0);
        ctx.zh = zk;
        CPPUNIT_ASSERT(zk);

        char pathbuf[80];
        struct Stat stat_a = {0};

        rc = zoo_create2(zk, "/serverRequireClientSASL", "", 0,
                         &ZOO_OPEN_ACL_UNSAFE, 0, pathbuf, sizeof(pathbuf), &stat_a);
        CPPUNIT_ASSERT_EQUAL((int)ZSESSIONCLOSEDREQUIRESASLAUTH, rc);

        stopServer();
    }
};

const char Zookeeper_serverRequireClientSASL::hostPorts[] = "127.0.0.1:22181";

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_serverRequireClientSASL);
