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
#ifndef HEDWIG_CALLBACK_H
#define HEDWIG_CALLBACK_H

#include <string>
#include <hedwig/exceptions.h>
#include <hedwig/protocol.h>
#include <tr1/memory>

namespace Hedwig {
  class OperationCallback {
  public:
    virtual void operationComplete() = 0;
    virtual void operationFailed(const std::exception& exception) = 0;
    
    virtual ~OperationCallback() {};
  };
  typedef std::tr1::shared_ptr<OperationCallback> OperationCallbackPtr;

  class MessageHandlerCallback {
  public:
    virtual void consume(const std::string& topic, const std::string& subscriberId, const Message& msg, OperationCallbackPtr& callback) = 0;
    
    virtual ~MessageHandlerCallback() {};
  };
  typedef std::tr1::shared_ptr<MessageHandlerCallback> MessageHandlerCallbackPtr;
}

#endif
