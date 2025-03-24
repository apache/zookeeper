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
    CPPUNIT_TEST(testClientSASLWithPasswordFileNewline);
    CPPUNIT_TEST(testClientSASLWithPasswordFileFirstLine);
    CPPUNIT_TEST(testClientSASLWithPasswordEncryptedText);
    CPPUNIT_TEST(testClientSASLWithPasswordEncryptedBinary);
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

    void testClientSASLHelper(const char *hostPorts, const char *path,
                              const sasl_callback_t *callbacks) {
        startServer();

        // Initialize Cyrus SASL.
        CPPUNIT_ASSERT_EQUAL(sasl_client_init(NULL), SASL_OK);

        // Initialize SASL parameters.
        zoo_sasl_params_t sasl_params = { 0 };

        sasl_params.service = "zookeeper";
        sasl_params.host = "zk-sasl-md5";
        sasl_params.mechlist = "DIGEST-MD5";
        sasl_params.callbacks = callbacks;

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

    void testClientSASLHelper(const char *hostPorts, const char *path,
                              const char *password_file) {
        const sasl_callback_t *callbacks = zoo_sasl_make_basic_callbacks(
            "myuser", NULL, password_file);
        testClientSASLHelper(hostPorts, path, callbacks);
    }

    void testClientSASLHelper(const char *hostPorts, const char *path) {
        testClientSASLHelper(hostPorts, path, "Zookeeper_SASLAuth.password");
    }

    void testClientSASLHelper(const char *hostPorts, const char *path,
                              zoo_sasl_password_t *password) {
        const sasl_callback_t *callbacks = zoo_sasl_make_password_callbacks(
            "myuser", NULL, password);
        testClientSASLHelper(hostPorts, path, callbacks);
    }

    void testClientSASL() {
        testClientSASLHelper(hostPorts, "/clientSASL");
    }

    void testClientSASL(const char *password_file, const char *content, size_t content_len,
                        const char *path, zoo_sasl_password_callback_t callback) {
        // Create password file for client.
        FILE *passf = fopen(password_file, "wt");
        CPPUNIT_ASSERT(passf);

        // Write the specified content into the file.
        size_t len = fwrite(content, sizeof(content[0]), content_len, passf);
        CPPUNIT_ASSERT_EQUAL(len, content_len);
        CPPUNIT_ASSERT_EQUAL(fclose(passf), 0);
        passf = NULL;

        zoo_sasl_password_t passwd = {password_file, this, callback};
        testClientSASLHelper(hostPorts, path, &passwd);
    }

    void testClientSASLWithPasswordFileNewline() {
        // Insert a newline immediately after the correct password.
        const char content[] = "mypassword\nabc";
        testClientSASL("Zookeeper_SASLAuth.password.newline",
                       content,
                       sizeof(content) - 1,
                       "/clientSASLWithPasswordFileNewline",
                       NULL);
    }

    void testClientSASLWithPasswordFileFirstLine() {
        // Insert multiple newlines and check if only the first line is accepted as the
        // actual password.
        const char content[] = "mypassword\nabc\nxyz";
        testClientSASL("Zookeeper_SASLAuth.password.firstline",
                       content,
                       sizeof(content) - 1,
                       "/clientSASLWithPasswordFileFirstLine",
                       NULL);
    }

    int decryptPassword(const char *content, size_t content_len,
                        char incr, char *buf, size_t buf_len,
                        size_t *passwd_len) {
        CPPUNIT_ASSERT(content_len <= buf_len);

        // A simple decryption that only increases each character by a fixed value.
        for (size_t i = 0; i < content_len; ++i) {
            buf[i] = content[i] + incr;
        }
        *passwd_len = content_len;

        // Since null terminator has not been appended to buf, use memcmp.  
        CPPUNIT_ASSERT_EQUAL(memcmp(buf, "mypassword", *passwd_len), 0);
        return SASL_OK;
    }

    static int textPasswordCallback(const char *content, size_t content_len,
                                    void *context, char *buf, size_t buf_len,
                                    size_t *passwd_len) {
        Zookeeper_SASLAuth *auth = static_cast<Zookeeper_SASLAuth *>(context);
        return auth->decryptPassword(content, content_len, 1, buf, buf_len, passwd_len);
    }

    void testClientSASLWithPasswordEncryptedText() {
        // Encrypt "mypassword" by subtracting 1 from each character in it as plain text.
        const char content[] = {0x6C, 0x78, 0x6F, 0x60, 0x72, 0x72, 0x76, 0x6E, 0x71, 0x63};
        testClientSASL("Zookeeper_SASLAuth.password.encrypted.text",
                       content,
                       sizeof(content),
                       "/clientSASLWithPasswordEncryptedText",
                       textPasswordCallback);
    }

    static int binaryPasswordCallback(const char *content, size_t content_len,
                                      void *context, char *buf, size_t buf_len,
                                      size_t *passwd_len) {
        Zookeeper_SASLAuth *auth = static_cast<Zookeeper_SASLAuth *>(context);
        return auth->decryptPassword(content, content_len, 'a', buf, buf_len, passwd_len);
    }

    void testClientSASLWithPasswordEncryptedBinary() {
        // Encrypt "mypassword" by subtracting 'a' from each character in it as binary format.
        const char content[] = {0x0C, 0x18, 0x0F, 0x00, 0x12, 0x12, 0x16, 0x0E, 0x11, 0x03};
        testClientSASL("Zookeeper_SASLAuth.password.encrypted.binary",
                       content,
                       sizeof(content),
                       "/clientSASLWithPasswordEncryptedBinary",
                       binaryPasswordCallback);
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
