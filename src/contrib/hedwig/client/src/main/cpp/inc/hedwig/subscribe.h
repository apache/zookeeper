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
#ifndef HEDWIG_SUBSCRIBE_H
#define HEDWIG_SUBSCRIBE_H

#include <string>

#include <hedwig/exceptions.h>
#include <hedwig/callback.h>
#include <hedwig/protocol.h>
#include <boost/noncopyable.hpp>

namespace Hedwig {

  /**
     Interface for subscribing to a hedwig instance. 
  */
  class Subscriber : private boost::noncopyable {
  public:
    virtual void subscribe(const std::string& topic, const std::string& subscriberId, const SubscribeRequest::CreateOrAttach mode) = 0;
    virtual void asyncSubscribe(const std::string& topic, const std::string& subscriberId, const SubscribeRequest::CreateOrAttach mode, const OperationCallbackPtr& callback) = 0;
    
    virtual void unsubscribe(const std::string& topic, const std::string& subscriberId) = 0;
    virtual void asyncUnsubscribe(const std::string& topic, const std::string& subscriberId, const OperationCallbackPtr& callback) = 0;  

    virtual void consume(const std::string& topic, const std::string& subscriberId, const MessageSeqId& messageSeqId) = 0;

    virtual void startDelivery(const std::string& topic, const std::string& subscriberId, const MessageHandlerCallbackPtr& callback) = 0;
    virtual void stopDelivery(const std::string& topic, const std::string& subscriberId) = 0;

    virtual void closeSubscription(const std::string& topic, const std::string& subscriberId) = 0;
  };
};

#endif
