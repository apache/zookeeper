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
#ifndef HEDWIG_CLIENT_H
#define HEDWIG_CLIENT_H

#include <string>
#include <tr1/memory>

#include <hedwig/subscribe.h>
#include <hedwig/publish.h>
#include <hedwig/exceptions.h>
#include <boost/noncopyable.hpp>
#include <boost/shared_ptr.hpp>

namespace Hedwig {

  class ClientImpl;
  typedef boost::shared_ptr<ClientImpl> ClientImplPtr;

  class Configuration {
  public:
    static const std::string DEFAULT_SERVER;
    static const std::string MESSAGE_CONSUME_RETRY_WAIT_TIME;
    static const std::string SUBSCRIBER_CONSUME_RETRY_WAIT_TIME;
    static const std::string MAX_MESSAGE_QUEUE_SIZE;
    static const std::string RECONNECT_SUBSCRIBE_RETRY_WAIT_TIME;
    static const std::string SYNC_REQUEST_TIMEOUT;

  public:
    Configuration() {};
    virtual int getInt(const std::string& key, int defaultVal) const = 0;
    virtual const std::string get(const std::string& key, const std::string& defaultVal) const = 0;
    virtual bool getBool(const std::string& key, bool defaultVal) const = 0;

    virtual ~Configuration() {}
  };

  /** 
      Main Hedwig client class. This class is used to acquire an instance of the Subscriber of Publisher.
  */
  class Client : private boost::noncopyable {
  public: 
    Client(const Configuration& conf);

    /**
       Retrieve the subscriber object
    */
    Subscriber& getSubscriber();

    /**
       Retrieve the publisher object
    */
    Publisher& getPublisher();

    ~Client();

  private:
    ClientImplPtr clientimpl;
  };

 
};

#endif
