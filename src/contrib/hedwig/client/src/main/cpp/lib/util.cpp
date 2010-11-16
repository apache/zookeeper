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

#include <netdb.h>
#include <errno.h>
#include "util.h"
#include "channel.h"
#include <log4cxx/logger.h>
#include <sys/types.h>
#include <sys/socket.h>

static log4cxx::LoggerPtr logger(log4cxx::Logger::getLogger("hedwig."__FILE__));

using namespace Hedwig;

#define MAX_HOSTNAME_LENGTH 256
const std::string UNITIALISED_HOST("UNINITIALISED HOST");

const int DEFAULT_PORT = 4080;
const int DEFAULT_SSL_PORT = 9876;

HostAddress::HostAddress() : initialised(false), address_str() {
  memset(&socket_addr, 0, sizeof(struct sockaddr_in));
}

HostAddress::~HostAddress() {
}

bool HostAddress::isNullHost() const {
  return !initialised;
}

bool HostAddress::operator==(const HostAddress& other) const {
  return (other.ip() == ip() && other.port() == port());
}

const std::string& HostAddress::getAddressString() const {
  if (!isNullHost()) {
    return address_str;
  } else {
    return UNITIALISED_HOST;
  }
}
   
uint32_t HostAddress::ip() const {
  return ntohl(socket_addr.sin_addr.s_addr);;
}

uint16_t HostAddress::port() const {
  return ntohs(socket_addr.sin_port);
}

const struct sockaddr_in& HostAddress::socketAddress() const {
  return socket_addr;
}


void HostAddress::parse_string() {
  char* url = strdup(address_str.c_str());

  if (url == NULL) {
    LOG4CXX_ERROR(logger, "You seems to be out of memory");
    throw OomException();
  }
  int port = DEFAULT_PORT;
  int sslport = DEFAULT_SSL_PORT;

  char *colon = strchr(url, ':');
  if (colon) {
    *colon = 0;
    colon++;
    
    char* sslcolon = strchr(colon, ':');
    if (sslcolon) {
      *sslcolon = 0;
      sslcolon++;
      
      sslport = strtol(sslcolon, NULL, 10);
      if (sslport == 0) {
        LOG4CXX_ERROR(logger, "Invalid SSL port given: [" << sslcolon << "]");
	free((void*)url);
	throw InvalidPortException();
      }
    }
    
    port = strtol(colon, NULL, 10);
    if (port == 0) {
      LOG4CXX_ERROR(logger, "Invalid port given: [" << colon << "]");
      free((void*)url);
      throw InvalidPortException();
    }
  }

  int err = 0;
  
  struct addrinfo *addr;
  struct addrinfo hints;

  memset(&hints, 0, sizeof(struct addrinfo));
  hints.ai_family = AF_INET;

  err = getaddrinfo(url, NULL, &hints, &addr);
  if (err != 0) {
    LOG4CXX_ERROR(logger, "Couldn't resolve host [" << url << "]:" << hstrerror(err));
    free((void*)url);
    throw HostResolutionException();
  }

  sockaddr_in* sa_ptr = (sockaddr_in*)addr->ai_addr;
  socket_addr = *sa_ptr;
  socket_addr.sin_port = htons(port); 
  //socket_addr.sin_family = AF_INET;

  free((void*)url);
  free((void*)addr);
}

HostAddress HostAddress::fromString(std::string str) {
  HostAddress h;
  h.address_str = str;
  h.parse_string();
  h.initialised = true;
  return h;
}

