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
#include "../lib/clientimpl.h"
#include <hedwig/exceptions.h>
#include <hedwig/callback.h>
#include <stdexcept>
#include <pthread.h>

static log4cpp::Category &UTILLOG = log4cpp::Category::getInstance("hedwigtest."__FILE__);


class SimpleWaitCondition : public Hedwig::WaitConditionBase {
public:
  SimpleWaitCondition() : flag(false) {};
  ~SimpleWaitCondition() { wait(); }

  void setTrue() { UTILLOG.debugStream() << "Setting flag " << &flag << " to true"; flag=true; UTILLOG.debugStream() << "Flag now " << flag; }
  bool isTrue() {
    UTILLOG.debugStream() << &flag << " isTrue? " << flag;
    return flag;
  }
private:
  bool flag;
};

class TestCallback : public Hedwig::OperationCallback {
public:
  TestCallback(SimpleWaitCondition* cond) 
    : cond(cond) {
  }

  virtual void operationComplete() {
    UTILLOG.debugStream() << "operationComplete";
    cond->lock();
    cond->setTrue();
    cond->signalAndUnlock();
  }
  
  virtual void operationFailed(const std::exception& exception) {
    UTILLOG.debugStream() << "operationFailed: " << exception.what();
    cond->lock();
    cond->setTrue();
    cond->signalAndUnlock();
  }    
private:
  SimpleWaitCondition *cond;
};


class TestServerConfiguration : public Hedwig::Configuration {
public:
  TestServerConfiguration(HedwigTest::TestServerPtr& server) : server(server), address(server->getAddress()) {}
  
  virtual const std::string& getDefaultServer() const {
    return address;
  }
  
private:
  HedwigTest::TestServerPtr server;
  const std::string address;
};
