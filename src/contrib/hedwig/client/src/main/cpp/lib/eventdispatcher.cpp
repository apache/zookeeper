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

#include "eventdispatcher.h"

#include <log4cpp/Category.hh>

static log4cpp::Category &LOG = log4cpp::Category::getInstance("hedwig."__FILE__);

using namespace Hedwig;

EventDispatcher::EventDispatcher() : service(), dummy_work(NULL), t(NULL) {
}

void EventDispatcher::run_forever() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Starting event dispatcher";
  }

  while (true) {
    try {
      service.run();
      break;
    } catch (std::exception &e) {
      LOG.errorStream() << "Exception in dispatch handler. " << e.what();
    }
  }
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Event dispatcher done";
  }
}

void EventDispatcher::start() {
  if (t) {
    return;
  }
  dummy_work = new boost::asio::io_service::work(service);
  t = new boost::thread(boost::bind(&EventDispatcher::run_forever, this));
}

void EventDispatcher::stop() {
  if (!t) {
    return;
  }
  delete dummy_work;
  dummy_work = NULL;
  
  service.stop();
  
  t->join();
  delete t;
  t = NULL;
}

EventDispatcher::~EventDispatcher() {
  delete dummy_work;
}

boost::asio::io_service& EventDispatcher::getService() {
  return service;
}
