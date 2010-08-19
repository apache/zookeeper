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
#include <log4cpp/Category.hh>
#include <sys/types.h>
#include <sys/socket.h>

static log4cpp::Category &LOG = log4cpp::Category::getInstance("hedwig."__FILE__);

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
    LOG.errorStream() << "You seems to be out of memory";
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
	LOG.errorStream() << "Invalid SSL port given: [" << sslcolon << "]";
	free((void*)url);
	throw InvalidPortException();
      }
    }
    
    port = strtol(colon, NULL, 10);
    if (port == 0) {
      LOG.errorStream() << "Invalid port given: [" << colon << "]";
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
    LOG.errorStream() << "Couldn't resolve host [" << url << "]:" << hstrerror(err);
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

WaitConditionBase::WaitConditionBase() {
  pthread_mutex_init(&mutex, NULL);
  pthread_cond_init(&cond, NULL);  
}

WaitConditionBase::~WaitConditionBase() {
  pthread_mutex_destroy(&mutex);
  pthread_cond_destroy(&cond);
}
    
void WaitConditionBase::wait() {
  pthread_mutex_lock(&mutex);
  while (!isTrue()) {
    if (LOG.isDebugEnabled()) {
      LOG.debugStream() << "wait: condition is false for " << this;
    }

    pthread_cond_wait(&cond, &mutex); 
  }
  pthread_mutex_unlock(&mutex);
}

void WaitConditionBase::lock() {
  pthread_mutex_lock(&mutex);
}

void WaitConditionBase::signalAndUnlock() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "signal: signal " << this;
  }
  
  pthread_cond_signal(&cond);
  
  pthread_mutex_unlock(&mutex);
}

Mutex::Mutex() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Creating mutex " << this;
  }
  int error = pthread_mutex_init(&mutex, NULL);
  if (error != 0) {
    LOG.errorStream() << "Error initiating mutex " << error;
  }
}

Mutex::~Mutex() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Destroying mutex " << this;
  }

  int error = pthread_mutex_destroy(&mutex);
  if (error != 0) {
    LOG.errorStream() << "Error destroying mutex " << this << " " << error;
  }
}

void Mutex::lock() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Locking mutex " << this;
  }
    
  int error = pthread_mutex_lock(&mutex);
  if (error != 0) {
    LOG.errorStream() << "Error locking mutex " << this << " " << error;
  }
}

void Mutex::unlock() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Unlocking mutex " << this;
  }

  int error = pthread_mutex_unlock(&mutex);
  if (error != 0) {
    LOG.errorStream() << "Error unlocking mutex " << this << " " << error;
  }
}

std::size_t std::tr1::hash<HostAddress>::operator()(const HostAddress& address) const {
  return (address.ip() << 16) & (address.port());
}

std::size_t std::tr1::hash<DuplexChannel*>::operator()(const DuplexChannel* channel) const {
  return reinterpret_cast<std::size_t>(channel);
}

std::size_t std::tr1::hash<TopicSubscriber>::operator()(const TopicSubscriber& topicsub) const {
  std::string fullstr = topicsub.first + topicsub.second;
  return std::tr1::hash<std::string>()(fullstr);
}

