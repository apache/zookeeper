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
#include <zookeeper.h>

class Zookeeper_logClientEnv : public CPPUNIT_NS::TestFixture {
    CPPUNIT_TEST_SUITE(Zookeeper_logClientEnv);
    CPPUNIT_TEST(testLogClientEnv);
    CPPUNIT_TEST_SUITE_END();

    static void log_no_clientenv(const char *message) {
      CPPUNIT_ASSERT(::strstr(message, "Client environment") == NULL);
    }

    static void log_clientenv(const char *message) {
      static int first;

      if (!first) {
        CPPUNIT_ASSERT(::strstr(message, "Client environment") != NULL);
        first = 1;
      }
    }

public:

    void testLogClientEnv() {
      zhandle_t* zh;

      zh = zookeeper_init2("localhost:22181", NULL, 0, NULL, NULL, 0, log_clientenv);
      CPPUNIT_ASSERT(zh != 0);
      zookeeper_close(zh);

      zh = zookeeper_init2("localhost:22181", NULL, 0, NULL, NULL, ZOO_NO_LOG_CLIENTENV, log_no_clientenv);
      CPPUNIT_ASSERT(zh != 0);
      zookeeper_close(zh);
    }
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_logClientEnv);

