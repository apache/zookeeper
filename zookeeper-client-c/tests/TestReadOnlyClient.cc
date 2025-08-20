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

#ifdef THREADED
class Zookeeper_readOnly : public CPPUNIT_NS::TestFixture {
    CPPUNIT_TEST_SUITE(Zookeeper_readOnly);
    CPPUNIT_TEST(testReadOnly);
#ifdef HAVE_OPENSSL_H
    CPPUNIT_TEST(testReadOnlyWithSSL);
#endif
    CPPUNIT_TEST_SUITE_END();

    static void watcher(zhandle_t* zh, int type, int state,
                        const char* path, void* v) {
        watchctx_t *ctx = (watchctx_t*)v;

        if (state==ZOO_CONNECTED_STATE || state==ZOO_READONLY_STATE) {
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

    FILE *logfile;
public:

    Zookeeper_readOnly() {
      logfile = openlogfile("Zookeeper_readOnly");
    }

    ~Zookeeper_readOnly() {
      if (logfile) {
        fflush(logfile);
        fclose(logfile);
        logfile = 0;
      }
    }

    void setUp() {
        zoo_set_log_stream(logfile);
        zoo_set_debug_level(ZOO_LOG_LEVEL_DEBUG);
        stopServer();
    }

    void tearDown()
    {
        startServer();
    }

    void startServer() {
        char cmd[1024];
        sprintf(cmd, "%s start %s", ZKSERVER_CMD, "127.0.0.1:22181");
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    void stopServer() {
        char cmd[1024];
        sprintf(cmd, "%s stop %s", ZKSERVER_CMD, "127.0.0.1:22181");
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    void startReadOnly() {
        char cmd[1024];
        sprintf(cmd, "%s startCleanReadOnly", ZKSERVER_CMD);
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    void stopPeer() {
        char cmd[1024];
        sprintf(cmd, "%s stop", ZKSERVER_CMD);
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    zhandle_t* connectReadOnly(const char *address, watchctx_t *watch) {
        zhandle_t* zh = zookeeper_init(address, watcher, 10000, NULL, watch, ZOO_READONLY);
        watch->zh = zh;
        CPPUNIT_ASSERT(zh != 0);
        sleep(1);
        return zh;
    }

    void assertCanRead(zhandle_t* zh, const char *znode_path) {
        int len = 1024;
        char buf[len];
        int res = zoo_get(zh, znode_path, 0, buf, &len, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, res);
    }

    void assertCanNotWrite(zhandle_t* zh, const char *znode_path) {
        char path[1024];
        char buf[1024];
        int res = zoo_create(zh, znode_path, buf, 10, &ZOO_OPEN_ACL_UNSAFE, 0, path, 512);
        CPPUNIT_ASSERT_EQUAL((int)ZNOTREADONLY, res);
    }

    void testReadOnly()
    {
        startReadOnly();

        watchctx_t watch;
        zhandle_t* zh = connectReadOnly("localhost:22181", &watch);

        assertCanRead(zh, "/");

        assertCanNotWrite(zh, "/test");

        stopPeer();
    }

#ifdef HAVE_OPENSSL_H

    zhandle_t* connectReadOnlySSL(const char *address, const char *certs, watchctx_t *watch) {
        zhandle_t* zh = zookeeper_init_ssl(address, certs, watcher, 10000, NULL, watch, ZOO_READONLY);
        watch->zh = zh;
        CPPUNIT_ASSERT(zh != 0);
        sleep(1);
        return zh;
    }

    void testReadOnlyWithSSL() {
        startReadOnly();

        watchctx_t watch;
        zhandle_t* zh = connectReadOnlySSL("localhost:22281",
                                           "/tmp/certs/server.crt,/tmp/certs/client.crt,/tmp/certs/clientkey.pem,password",
                                           &watch);

        assertCanRead(zh, "/");

        assertCanNotWrite(zh, "/testSSL");

        stopPeer();
    }
#endif

};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_readOnly);
#endif
