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

#ifndef SERVERCONTROL_H
#define SERVERCONTROL_H

#include <tr1/memory>
#include <exception>
#include <boost/thread/mutex.hpp>
#include "../lib/util.h"

namespace HedwigTest {
  const int DEFAULT_CONTROLSERVER_PORT = 5672;

  class TestException : public std::exception {};
  class CantConnectToServerControlDaemonException : public TestException {};
  class InvalidServerControlDaemonResponseException : public TestException {};
  class ErrorCreatingServerException : public TestException {};
  class ErrorKillingServerException : public TestException {};

  class TestServer {
  public:
    virtual void kill() = 0;
    virtual std::string& getAddress() = 0;
    virtual ~TestServer() {}
  };
  
  typedef std::tr1::shared_ptr<TestServer> TestServerPtr;

  class ServerControl {
  public:
    ServerControl(int port);
    ~ServerControl();
    
    TestServerPtr startZookeeperServer(int port);
    TestServerPtr startBookieServer(int port, TestServerPtr& zookeeperServer);
    TestServerPtr startPubSubServer(int port, std::string& region, TestServerPtr& zookeeperServer);
    
    struct ServerResponse {
      std::string status;
      std::string message; 
    };
    ServerResponse requestResponse(std::string request);

  public:
    int socketfd;
    boost::mutex socketlock;
  };
};

#endif
