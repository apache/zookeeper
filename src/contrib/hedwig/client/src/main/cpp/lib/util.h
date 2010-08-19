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

#ifndef HEDWIG_UTIL_H
#define HEDWIG_UTIL_H

#include <sys/socket.h>
#include <netinet/in.h>
#include <hedwig/exceptions.h>
#include <hedwig/callback.h>
#include <list>
#include <iostream>
#include <utility>
#include <tr1/functional>
#include <semaphore.h>
#include <pthread.h>

namespace Hedwig {
  typedef std::pair<const std::string, const std::string> TopicSubscriber;

  /**
     Representation of a hosts address
  */
  class HostAddress {
  public:
    HostAddress();
    ~HostAddress();

    bool operator==(const HostAddress& other) const;
    
    bool isNullHost() const;
    const std::string& getAddressString() const;
    uint32_t ip() const;
    uint16_t port() const;
    const sockaddr_in& socketAddress() const;

    static HostAddress fromString(std::string host);

  private:

    void parse_string();
    
    bool initialised;
    std::string address_str;
    struct sockaddr_in socket_addr;
  };

  class DuplexChannel;  
  
  class Mutex {
  public:
    Mutex();
    ~Mutex();
    
    void lock();
    void unlock();
  private:
    pthread_mutex_t mutex;
  };

  class WaitConditionBase {
  public:
    WaitConditionBase();
    virtual ~WaitConditionBase();
    
    void wait(); 
    void lock();
    void signalAndUnlock();

    virtual bool isTrue() = 0;
  private:

    pthread_mutex_t mutex;
    pthread_cond_t cond;    
  };

};

namespace std 
{
  namespace tr1 
  {
  /**
     Hash a host address. Takes the least significant 16-bits of the address and the 16-bits of the
     port and packs them into one 32-bit number. While collisons are theoretically very possible, they
     shouldn't happen as the hedwig servers should be in the same subnet.
  */
  template <> struct hash<Hedwig::HostAddress> : public unary_function<Hedwig::HostAddress, size_t> {
    size_t operator()(const Hedwig::HostAddress& address) const;
  };

  /**
     Hash a channel pointer, just returns the pointer.
  */
  template <> struct hash<Hedwig::DuplexChannel*> : public unary_function<Hedwig::DuplexChannel*, size_t> {
    size_t operator()(const Hedwig::DuplexChannel* channel) const;
  };

  /**
     Hash a channel pointer, just returns the pointer.
  */
  template <> struct hash<Hedwig::TopicSubscriber> : public unary_function<Hedwig::TopicSubscriber, size_t> {
    size_t operator()(const Hedwig::TopicSubscriber& topicsub) const;
  };
  }
}
#endif
