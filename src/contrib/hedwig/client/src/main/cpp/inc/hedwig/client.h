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

namespace Hedwig {

  class ClientImpl;
  typedef std::tr1::shared_ptr<ClientImpl> ClientImplPtr;

  class Configuration {
  public:
    Configuration() {};

    virtual const std::string& getDefaultServer() const;    
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
