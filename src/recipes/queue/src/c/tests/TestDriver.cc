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

#include <string>
#include <cppunit/TestRunner.h>
#include <cppunit/CompilerOutputter.h>
#include <cppunit/TestResult.h>
#include <cppunit/TestResultCollector.h>
#include <cppunit/TextTestProgressListener.h>
#include <cppunit/BriefTestProgressListener.h>
#include <cppunit/extensions/TestFactoryRegistry.h>
#include <stdexcept>
#include <cppunit/Exception.h>
#include <cppunit/TestFailure.h>
#include <cppunit/XmlOutputter.h>
#include <fstream>

#include "Util.h"

using namespace std;

CPPUNIT_NS_BEGIN

class EclipseOutputter: public CompilerOutputter
{
public:
  EclipseOutputter(TestResultCollector *result,ostream &stream):
        CompilerOutputter(result,stream,"%p:%l: "),stream_(stream)
    {
    }
    virtual void printFailedTestName( TestFailure *failure ){}
    virtual void printFailureMessage( TestFailure *failure )
    {
      stream_<<": ";
      Message msg = failure->thrownException()->message();
      stream_<< msg.shortDescription();

      string text;
      for(int i=0; i<msg.detailCount();i++){
          text+=msg.detailAt(i);
          if(i+1!=msg.detailCount())
              text+=", ";
      }
      if(text.length()!=0)
          stream_ <<" ["<<text<<"]";
      stream_<<"\n";
    }
    ostream& stream_;
};

CPPUNIT_NS_END

int main( int argc, char* argv[] ) { 
   // if command line contains "-ide" then this is the post build check
   // => the output must be in the compiler error format.
   //bool selfTest = (argc > 1) && (std::string("-ide") == argv[1]);
   globalTestConfig.addConfigFromCmdLine(argc,argv);

   // Create the event manager and test controller
   CPPUNIT_NS::TestResult controller;
   // Add a listener that colllects test result
   CPPUNIT_NS::TestResultCollector result;
   controller.addListener( &result );
   
   // Add a listener that print dots as tests run.
   // CPPUNIT_NS::TextTestProgressListener progress;
   CPPUNIT_NS::BriefTestProgressListener progress;
   controller.addListener( &progress );
 
   CPPUNIT_NS::TestRunner runner;
   runner.addTest( CPPUNIT_NS::TestFactoryRegistry::getRegistry().makeTest() );
 
   try
   {
     cout << "Running "  <<  globalTestConfig.getTestName();
     runner.run( controller, globalTestConfig.getTestName());
     cout<<endl;

     // Print test in a compiler compatible format.
     CPPUNIT_NS::EclipseOutputter outputter( &result,cout);
     outputter.write(); 

 // Uncomment this for XML output
#ifdef ENABLE_XML_OUTPUT
     std::ofstream file( "tests.xml" );
     CPPUNIT_NS::XmlOutputter xml( &result, file );
     xml.setStyleSheet( "report.xsl" );
     xml.write();
     file.close();
#endif
   }
   catch ( std::invalid_argument &e )  // Test path not resolved
   {
     cout<<"\nERROR: "<<e.what()<<endl;
     return 0;
   }

   return result.wasSuccessful() ? 0 : 1;
 }
