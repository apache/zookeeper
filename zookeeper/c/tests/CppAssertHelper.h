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
