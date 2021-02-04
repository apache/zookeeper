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

#ifdef THREADED

#include <cppunit/extensions/HelperMacros.h>
#include "CppAssertHelper.h"

#include <sys/socket.h>
#include <unistd.h>

#include <zookeeper.h>

#include "Util.h"
#include "WatchUtil.h"

class Zookeeper_SASLAuth : public CPPUNIT_NS::TestFixture {
    CPPUNIT_TEST_SUITE(Zookeeper_SASLAuth);
    CPPUNIT_TEST(testServerRequireClientSASL);
#ifdef HAVE_CYRUS_SASL_H
    CPPUNIT_TEST(testClientSASL);
#ifdef ZOO_IPV6_ENABLED
    CPPUNIT_TEST(testClientSASLOverIPv6);
#endif/* ZOO_IPV6_ENABLED */
    CPPUNIT_TEST(testClientSASLReadOnly);
    CPPUNIT_TEST(testClientSASLPacketOrder);
#endif /* HAVE_CYRUS_SASL_H */
    CPPUNIT_TEST_SUITE_END();
    FILE *logfile;
    static const char hostPorts[];
    static const char jaasConf[];
    static void watcher(zhandle_t *, int type, int state, const char *path,void*v){
        watchctx_t *ctx = (watchctx_t*)v;

        if (state == ZOO_CONNECTED_STATE || state == ZOO_READONLY_STATE) {
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
    Zookeeper_SASLAuth() {
      logfile = openlogfile("Zookeeper_SASLAuth");
    }

    ~Zookeeper_SASLAuth() {
      if (logfile) {
        fflush(logfile);
        fclose(logfile);
        logfile = 0;
      }
    }

    void setUp() {
        zoo_set_log_stream(logfile);

        // Create SASL configuration file for server.
        FILE *conff = fopen("Zookeeper_SASLAuth.jaas.conf", "wt");
        CPPUNIT_ASSERT(conff);
        size_t confLen = strlen(jaasConf);
        CPPUNIT_ASSERT_EQUAL(fwrite(jaasConf, 1, confLen, conff), confLen);
        CPPUNIT_ASSERT_EQUAL(fclose(conff), 0);
        conff = NULL;

        // Create password file for client.
        FILE *passf = fopen("Zookeeper_SASLAuth.password", "wt");
        CPPUNIT_ASSERT(passf);
        CPPUNIT_ASSERT(fputs("mypassword", passf) > 0);
        CPPUNIT_ASSERT_EQUAL(fclose(passf), 0);
        passf = NULL;
    }

    void startServer(bool useJaasConf = true, bool readOnly = false) {
        char cmd[1024];
        sprintf(cmd, "%s startRequireSASLAuth %s %s",
                ZKSERVER_CMD,
                useJaasConf ? "Zookeeper_SASLAuth.jaas.conf" : "",
                readOnly ? "true" : "");
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    void stopServer() {
        char cmd[1024];
        sprintf(cmd, "%s stop", ZKSERVER_CMD);
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    void testServerRequireClientSASL() {
        startServer(false);

        watchctx_t ctx;
        int rc = 0;
        zhandle_t *zk = zookeeper_init(hostPorts, watcher, 10000, 0, &ctx, 0);
        ctx.zh = zk;
        CPPUNIT_ASSERT(zk);

        // Wait for handle to be connected.
        CPPUNIT_ASSERT(ctx.waitForConnected(zk));

        char pathbuf[80];
        struct Stat stat_a = {0};

        rc = zoo_create2(zk, "/serverRequireClientSASL", "", 0,
                         &ZOO_OPEN_ACL_UNSAFE, 0, pathbuf, sizeof(pathbuf), &stat_a);
        CPPUNIT_ASSERT_EQUAL((int)ZSESSIONCLOSEDREQUIRESASLAUTH, rc);

        stopServer();
    }

#ifdef HAVE_CYRUS_SASL_H

    // We need to disable the deprecation warnings as Apple has
    // decided to deprecate all of CyrusSASL's functions with OS 10.11
    // (see MESOS-3030, ZOOKEEPER-4201). We are using GCC pragmas also
    // for covering clang.
#ifdef __APPLE__
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
#endif

    void testClientSASLHelper(const char *hostPorts, const char *path) {
        startServer();

        // Initialize Cyrus SASL.
        CPPUNIT_ASSERT_EQUAL(sasl_client_init(NULL), SASL_OK);

        // Initialize SASL parameters.
        zoo_sasl_params_t sasl_params = { 0 };

        sasl_params.service = "zookeeper";
        sasl_params.host = "zk-sasl-md5";
        sasl_params.mechlist = "DIGEST-MD5";
        sasl_params.callbacks = zoo_sasl_make_basic_callbacks(
            "myuser", NULL, "Zookeeper_SASLAuth.password");

        // Connect.
        watchctx_t ctx;
        int rc = 0;
        zhandle_t *zk = zookeeper_init_sasl(hostPorts, watcher, 10000, NULL,
            &ctx, /*flags*/0, /*log_callback*/NULL, &sasl_params);
        ctx.zh = zk;
        CPPUNIT_ASSERT(zk);

        // Wait for SASL auth to complete and handle to be connected.
        CPPUNIT_ASSERT(ctx.waitForConnected(zk));

        // Leave mark.
        char pathbuf[80];
        struct Stat stat_a = {0};
        rc = zoo_create2(zk, path, "", 0,
            &ZOO_OPEN_ACL_UNSAFE, 0, pathbuf, sizeof(pathbuf), &stat_a);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        // Stop and restart the server to test automatic reconnect & re-auth.
        stopServer();
        CPPUNIT_ASSERT(ctx.waitForDisconnected(zk));
        startServer();

        // Wait for automatic SASL re-auth to complete.
        CPPUNIT_ASSERT(ctx.waitForConnected(zk));

        // Check mark left above.
        rc = zoo_exists(zk, path, /*watch*/false, &stat_a);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        stopServer();
    }

    void testClientSASL() {
        testClientSASLHelper(hostPorts, "/clientSASL");
    }

    void testClientSASLOverIPv6() {
        const char *ipAndPort = "::1:22181";

        testClientSASLHelper(ipAndPort, "/clientSASLOverIPv6");
    }

    void testClientSASLReadOnly() {
        startServer(/*useJaasConf*/ true, /*readOnly*/ true);

        // Initialize Cyrus SASL.
        CPPUNIT_ASSERT_EQUAL(sasl_client_init(NULL), SASL_OK);

        // Initialize SASL parameters.
        zoo_sasl_params_t sasl_params = { 0 };

        sasl_params.service = "zookeeper";
        sasl_params.host = "zk-sasl-md5";
        sasl_params.mechlist = "DIGEST-MD5";
        sasl_params.callbacks = zoo_sasl_make_basic_callbacks(
            "myuser", NULL, "Zookeeper_SASLAuth.password");

        // Connect.
        watchctx_t ctx;
        int rc = 0;
        zhandle_t *zk = zookeeper_init_sasl(hostPorts, watcher, 10000, NULL,
            &ctx, /*flags*/ZOO_READONLY, /*log_callback*/NULL, &sasl_params);
        ctx.zh = zk;
        CPPUNIT_ASSERT(zk);

        // Wait for SASL auth to complete and handle to be connected.
        CPPUNIT_ASSERT(ctx.waitForConnected(zk));

        // Assert can read.
        char buf[1024];
        int len = sizeof(buf);
        rc = zoo_get(zk, "/", 0, buf, &len, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        // Assert can not write.
        char path[1024];
        rc = zoo_create(zk, "/test", "hello", 5, &ZOO_OPEN_ACL_UNSAFE, 0, path, sizeof(path));
        CPPUNIT_ASSERT_EQUAL((int)ZNOTREADONLY, rc);

        stopServer();
    }

    void testClientSASLPacketOrder() {
        startServer();

        // Initialize Cyrus SASL.
        CPPUNIT_ASSERT_EQUAL(sasl_client_init(NULL), SASL_OK);

        // Initialize SASL parameters.
        zoo_sasl_params_t sasl_params = { 0 };

        sasl_params.service = "zookeeper";
        sasl_params.host = "zk-sasl-md5";
        sasl_params.mechlist = "DIGEST-MD5";
        sasl_params.callbacks = zoo_sasl_make_basic_callbacks(
            "myuser", NULL, "Zookeeper_SASLAuth.password");

        // Connect.
        watchctx_t ctx;
        int rc = 0;
        zhandle_t *zk = zookeeper_init_sasl(hostPorts, watcher, 10000, NULL,
            &ctx, /*flags*/0, /*log_callback*/NULL, &sasl_params);
        ctx.zh = zk;
        CPPUNIT_ASSERT(zk);

        // No wait: try and queue a packet before SASL auth is complete.
        char buf[1024];
        int len = sizeof(buf);
        rc = zoo_get(zk, "/", 0, buf, &len, 0);
        CPPUNIT_ASSERT_EQUAL((int)ZOK, rc);

        stopServer();
    }

#ifdef __APPLE__
#pragma GCC diagnostic pop
#endif

#endif /* HAVE_CYRUS_SASL_H */
};

const char Zookeeper_SASLAuth::hostPorts[] = "127.0.0.1:22181";

const char Zookeeper_SASLAuth::jaasConf[] =
  "Server {\n"
  "  org.apache.zookeeper.server.auth.DigestLoginModule required\n"
  "    user_myuser=\"mypassword\";\n"
  "};\n";

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_SASLAuth);

#endif /* THREADED */
