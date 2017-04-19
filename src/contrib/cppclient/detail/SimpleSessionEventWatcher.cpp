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

#include "zeus/client/detail/SimpleSessionEventWatcher.h"

namespace facebook {
namespace zeus {
namespace client {
namespace detail {

void SimpleSessionEventWatcher::Callback::call(const SessionEvent& e) {
  std::unique_lock<std::mutex> g(lock_);
  if (f_) {
    try {
      f_(e);
    } catch (const std::exception& ex) {
      LOG(ERROR) << "uncaught exception from session event callback: "
                 << ex.what();
      std::terminate();
    } catch (...) {
      LOG(ERROR) << "uncaught non-exception from session event callback";
      std::terminate();
    }
  }
}

folly::Function<void(SessionEvent)>
SimpleSessionEventWatcher::Callback::extract() {
  std::unique_lock<std::mutex> g(lock_);
  auto rval = std::move(f_);
  f_ = nullptr;
  return rval;
}

SimpleSessionEventWatcher::SimpleSessionEventWatcher() : nextIndex_(0) {}

std::unique_ptr<IEventWatchCallbackIdentifier>
SimpleSessionEventWatcher::addCallback(
    folly::Function<void(SessionEvent)>&& callback) {
  folly::SharedMutex::ReadHolder sg(stateLock_);
  if (nextIndex_ > 0) {
    try {
      callback(SessionEvent{nextIndex_ - 1, lastState_});
    } catch (...) {
    }
  }
  sg.unlock();

  auto callbackPtr = std::make_shared<Callback>(std::move(callback));
  auto rval = std::make_unique<CallbackIdentifier>();
  folly::SharedMutex::WriteHolder cg(callbacksLock_);
  callbacks_.push_front(std::move(callbackPtr));
  rval->i_ = callbacks_.begin();
  return std::move(rval);
}

std::shared_ptr<SimpleSessionEventWatcher::Callback>
SimpleSessionEventWatcher::removeCallbackPtr(
    std::unique_ptr<IEventWatchCallbackIdentifier>&& i) {
  auto* callbackIdentifier = dynamic_cast<CallbackIdentifier*>(i.get());
  if (!callbackIdentifier) {
    throw UnrecognizedCallbackIdentifierException();
  }
  folly::SharedMutex::WriteHolder g(callbacksLock_);
  auto callbackPtr = *callbackIdentifier->i_;
  callbacks_.erase(callbackIdentifier->i_);
  g.unlock();
  return callbackPtr;
}

void SimpleSessionEventWatcher::removeCallbackSoon(
    std::unique_ptr<IEventWatchCallbackIdentifier>&& i) {
  removeCallbackPtr(std::move(i));
}

folly::Function<void(SessionEvent)> SimpleSessionEventWatcher::removeCallback(
    std::unique_ptr<IEventWatchCallbackIdentifier>&& i) {
  return removeCallbackPtr(std::move(i))->extract();
}

void SimpleSessionEventWatcher::onSessionEvent(SessionState state) {
  folly::SharedMutex::WriteHolder sg(stateLock_);
  auto index = nextIndex_++;
  lastState_ = state;
  sg.unlock();

  SessionEvent e{index, state};

  folly::SharedMutex::ReadHolder cg(callbacksLock_);
  auto callbacksCopy = callbacks_;
  cg.unlock();
  for (auto& callback : callbacksCopy) {
    callback->call(e);
  }

  std::unique_lock<std::mutex> pg(promisesLock_);
  auto i = promises_.find(state);
  if (i != promises_.end()) {
    i->second.setValue(e);
    promises_.erase(i);
  }
}

folly::Future<SessionEvent> SimpleSessionEventWatcher::getEventForState(
    SessionState state) {
  folly::SharedMutex::ReadHolder sg(stateLock_);
  if (nextIndex_ > 0 && lastState_ == state) {
    return folly::makeFuture(SessionEvent{nextIndex_ - 1, state});
  }

  std::unique_lock<std::mutex> pg(promisesLock_);
  return promises_[state].getFuture();
}

size_t SimpleSessionEventWatcher::getNextIndex() const {
  folly::SharedMutex::ReadHolder g(stateLock_);
  return nextIndex_;
}

std::pair<size_t, SessionState>
SimpleSessionEventWatcher::getNextIndexAndCurrentState() const {
  folly::SharedMutex::ReadHolder g(stateLock_);
  return std::make_pair(nextIndex_, lastState_);
}
}
}
}
}
