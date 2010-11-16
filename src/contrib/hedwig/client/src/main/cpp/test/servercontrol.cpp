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

#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netinet/tcp.h>

#include <string>
#include <string.h>
#include <stdlib.h>
#include "servercontrol.h"


#include <log4cxx/logger.h>

#include "util.h"

#include <sstream>   
#include <time.h>

static log4cxx::LoggerPtr logger(log4cxx::Logger::getLogger("hedwig."__FILE__));

extern HedwigCppTextTestProgressListener gprogress;

using namespace HedwigTest;

const int MAX_COMMAND_LN = 256;

class TestServerImpl : public TestServer {
public:
  TestServerImpl(std::string& address, ServerControl& sc);
  ~TestServerImpl();
  void kill();
  std::string& getAddress();

private:
  std::string address;
  ServerControl& sc;
};

TestServerImpl::TestServerImpl(std::string& address, ServerControl& sc) : address(address), sc(sc)  {
}

TestServerImpl::~TestServerImpl() {
}

void TestServerImpl::kill() {
  std::ostringstream sstr;
  sstr << "KILL " << address << std::endl;
  ServerControl::ServerResponse resp = sc.requestResponse(sstr.str());
  if (resp.status != "OK") {
    LOG4CXX_ERROR(logger, "Error killing Server " << resp.message);
    throw ErrorKillingServerException();
  }
}

std::string& TestServerImpl::getAddress() {
  return address;
}
 
ServerControl::ServerControl(int port) {
  socketfd = socket(PF_INET, SOCK_STREAM, IPPROTO_TCP);
  
  if (-1 == socketfd) {
  LOG4CXX_ERROR(logger, "Couldn't create socket");
    throw CantConnectToServerControlDaemonException();
  }

  sockaddr_in addr;
  addr.sin_family = AF_INET; 
  addr.sin_port = htons(port);
  addr.sin_addr.s_addr = inet_addr("127.0.0.1"); 
    
  if (-1 == ::connect(socketfd, (const sockaddr *)&addr, sizeof(struct sockaddr))) {
  LOG4CXX_ERROR(logger, "Couldn't connect socket");
    close(socketfd);
    throw CantConnectToServerControlDaemonException();
  }
  
  requestResponse("TEST " + gprogress.getTestName() + "\n");
}

ServerControl::~ServerControl() {
  close(socketfd);
}
  

ServerControl::ServerResponse ServerControl::requestResponse(std::string request) {
  socketlock.lock();
  char response[MAX_COMMAND_LN];

  LOG4CXX_DEBUG(logger, "REQ: " << request.c_str() << " " << request.length());
  send(socketfd, request.c_str(), request.length(), 0);
  
  memset(response, 0, MAX_COMMAND_LN);
  recv(socketfd, response, MAX_COMMAND_LN, 0);
  LOG4CXX_DEBUG(logger, "RESP: " << response);

  socketlock.unlock();

  char* space = strchr(response, ' ');
  if (space == NULL) {
    throw InvalidServerControlDaemonResponseException();
  }
  char* status = response;
  *space = 0;
  
  char* message = space+1;
  char* cr = strchr(message, '\n');
  if (cr != NULL) {
    *cr = 0;
  }
  if (strlen(message) < 1) {
    throw InvalidServerControlDaemonResponseException();
  }
  LOG4CXX_DEBUG(logger, "$" << message << "$");
  ServerControl::ServerResponse resp = { std::string(status), std::string(message) };
  return resp;
}
  
TestServerPtr ServerControl::startZookeeperServer(int port) {  
  std::ostringstream sstr;
  sstr << "START ZOOKEEPER " << port << std::endl;

  std::string req(sstr.str());
  LOG4CXX_DEBUG(logger, req);

  ServerControl::ServerResponse resp = requestResponse(req);
  if (resp.status == "OK") {
    return TestServerPtr(new TestServerImpl(resp.message, *this));
  } else {
    LOG4CXX_ERROR(logger, "Error creating zookeeper on port " << port << " " << resp.message);
    throw ErrorCreatingServerException();
  }
}

TestServerPtr ServerControl::startBookieServer(int port, TestServerPtr& zookeeperServer) {
  std::ostringstream sstr;
  sstr << "START BOOKKEEPER " << port << " " << zookeeperServer->getAddress() << std::endl;

  std::string req(sstr.str());
  LOG4CXX_DEBUG(logger, req);

  ServerControl::ServerResponse resp = requestResponse(req);
  if (resp.status == "OK") {
    return TestServerPtr(new TestServerImpl(resp.message, *this));
  } else {
    LOG4CXX_ERROR(logger, "Error creating bookkeeper on port " << port << " " << resp.message);
    throw ErrorCreatingServerException();
  }
}

TestServerPtr ServerControl::startPubSubServer(int port, std::string& region, TestServerPtr& zookeeperServer) {
  std::ostringstream sstr;
  sstr << "START HEDWIG " << port << " " << region << " " << zookeeperServer->getAddress() << std::endl;

  std::string req(sstr.str());
  LOG4CXX_DEBUG(logger, req);

  ServerControl::ServerResponse resp = requestResponse(req);
  if (resp.status == "OK") {
    return TestServerPtr(new TestServerImpl(resp.message, *this));
  } else {
    LOG4CXX_ERROR(logger, "Error creating hedwig on port " << port << " " << resp.message);
    throw ErrorCreatingServerException();
  }
}
