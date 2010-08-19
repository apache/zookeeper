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
#include <cppunit/Test.h>
#include <cppunit/TestSuite.h>
#include <cppunit/extensions/HelperMacros.h>

#include "../lib/util.h"
#include <hedwig/exceptions.h>
#include <stdexcept>

using namespace CppUnit;

class UtilTestSuite : public CppUnit::TestFixture {
  CPPUNIT_TEST_SUITE( UtilTestSuite );
  CPPUNIT_TEST(testHostAddress);
  CPPUNIT_TEST_SUITE_END();

public:
  void setUp()
  {
  }

  void tearDown() 
  {
  }

  void testHostAddress() {
    // good address (no ports)
    Hedwig::HostAddress a1 = Hedwig::HostAddress::fromString("www.yahoo.com");
    CPPUNIT_ASSERT(a1.port() == 4080);

    // good address with ip (no ports)
    Hedwig::HostAddress a2 = Hedwig::HostAddress::fromString("127.0.0.1");
    CPPUNIT_ASSERT(a2.port() == 4080);
    CPPUNIT_ASSERT(a2.ip() == ((127 << 24) | 1));

    // good address
    Hedwig::HostAddress a3 = Hedwig::HostAddress::fromString("www.yahoo.com:80");
    CPPUNIT_ASSERT(a3.port() == 80);

    // good address with ip
    Hedwig::HostAddress a4 = Hedwig::HostAddress::fromString("127.0.0.1:80");
    CPPUNIT_ASSERT(a4.port() == 80);
    CPPUNIT_ASSERT(a4.ip() == ((127 << 24) | 1));

    // good address (with ssl)
    Hedwig::HostAddress a5 = Hedwig::HostAddress::fromString("www.yahoo.com:80:443");
    CPPUNIT_ASSERT(a5.port() == 80);

    // good address with ip
    Hedwig::HostAddress a6 = Hedwig::HostAddress::fromString("127.0.0.1:80:443");
    CPPUNIT_ASSERT(a6.port() == 80);
    CPPUNIT_ASSERT(a6.ip() == ((127 << 24) | 1));

    // nothing
    CPPUNIT_ASSERT_THROW(Hedwig::HostAddress::fromString(""), Hedwig::HostResolutionException);
    
    // nothing but colons
    CPPUNIT_ASSERT_THROW(Hedwig::HostAddress::fromString("::::::::::::::::"), Hedwig::ConfigurationException);
    
    // only port number
    CPPUNIT_ASSERT_THROW(Hedwig::HostAddress::fromString(":80"), Hedwig::HostResolutionException);
 
    // text after colon (isn't supported)
    CPPUNIT_ASSERT_THROW(Hedwig::HostAddress::fromString("www.yahoo.com:http"), Hedwig::ConfigurationException);
    
    // invalid hostname
    CPPUNIT_ASSERT_THROW(Hedwig::HostAddress::fromString("com.oohay.www:80"), Hedwig::HostResolutionException);
    
    // null
    CPPUNIT_ASSERT_THROW(Hedwig::HostAddress::fromString(NULL), std::logic_error);
  }
};

CPPUNIT_TEST_SUITE_NAMED_REGISTRATION( UtilTestSuite, "Util" );
