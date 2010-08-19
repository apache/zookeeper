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

#include "../lib/clientimpl.h"
#include <hedwig/exceptions.h>
#include <stdexcept>

using namespace CppUnit;

class PubSubDataTestSuite : public CppUnit::TestFixture {
  CPPUNIT_TEST_SUITE( PubSubDataTestSuite );
  CPPUNIT_TEST(createPubSubData);
  CPPUNIT_TEST_SUITE_END();

public:
  void setUp()
  {
  }

  void tearDown() 
  {
  }

  void createPubSubData() {
    
  }
};

CPPUNIT_TEST_SUITE_REGISTRATION( PubSubDataTestSuite );
