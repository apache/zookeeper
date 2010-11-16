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
#include "../lib/channel.h"
#include "../lib/util.h"
#include <hedwig/protocol.h>
#include <hedwig/callback.h>
#include <iostream>

#include <log4cxx/logger.h>
#include <log4cxx/basicconfigurator.h>
#include <log4cxx/propertyconfigurator.h>
#include <log4cxx/helpers/exception.h>

#include "servercontrol.h"
#include "util.h"

#include <cppunit/extensions/TestFactoryRegistry.h>
#include <cppunit/ui/text/TextTestRunner.h>

#include <cppunit/TextTestProgressListener.h>
#include <cppunit/TestResult.h>

HedwigCppTextTestProgressListener gprogress;

int main( int argc, char **argv)
{
  try {
    if (getenv("LOG4CXX_CONF") == NULL) {
      std::cerr << "Set LOG4CXX_CONF in your environment to get logging." << std::endl;
      log4cxx::BasicConfigurator::configure();
    } else {
      log4cxx::PropertyConfigurator::configure(getenv("LOG4CXX_CONF"));
    }
  } catch (std::exception &e) {
    std::cerr << "exception caught while configuring log4cpp via : " << e.what() << std::endl;
  } catch (...) {
    std::cerr << "unknown exception while configuring log4cpp vi'." << std::endl;
  }
  std::string testPath = (argc > 2) ? std::string(argv[2]) : "";

  CppUnit::TextTestRunner runner;

  if (argc > 1) {
    CppUnit::TestFactoryRegistry &registry = CppUnit::TestFactoryRegistry::getRegistry(argv[1]);
    
    runner.addTest( registry.makeTest() );
  } else {
    CppUnit::TestFactoryRegistry &registry = CppUnit::TestFactoryRegistry::getRegistry("*");
    registry.addRegistry("Util");
    registry.addRegistry("Subscribe");
    registry.addRegistry("Publish"); 
    registry.addRegistry("PubSub");
    
    runner.addTest( registry.makeTest() );
  }
  
  runner.eventManager().addListener( &gprogress );

  bool ret = runner.run(testPath);
  google::protobuf::ShutdownProtobufLibrary();
  
  return (ret == true) ? 0 : 1;
}
