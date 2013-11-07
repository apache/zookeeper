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

#ifndef CPPASSERTHELPER_H_
#define CPPASSERTHELPER_H_

#include <cppunit/TestAssert.h>

// make it possible to specify location of the ASSERT call
#define CPPUNIT_ASSERT_EQUAL_LOC(expected,actual,file,line) \
  ( CPPUNIT_NS::assertEquals( (expected),              \
                              (actual),                \
                              CPPUNIT_NS::SourceLine(file,line),    \
                              "" ) )

#define CPPUNIT_ASSERT_EQUAL_MESSAGE_LOC(message,expected,actual,file,line) \
  ( CPPUNIT_NS::assertEquals( (expected),              \
                              (actual),                \
                              CPPUNIT_NS::SourceLine(file,line), \
                              (message) ) )

#endif /*CPPASSERTHELPER_H_*/
