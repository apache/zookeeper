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

#pragma once

#include <folly/SharedMutex.h>
#include <folly/futures/SharedPromise.h>
#include <list>
#include <mutex>
#include <unordered_map>
#include "zeus/client/ZookeeperClient.h"

namespace facebook {
namespace zeus {
namespace client {
namespace detail {

class SimpleSessionEventWatcher : public virtual ISessionEventWatcher {
 public:
  SimpleSessionEventWatcher();
  virtual ~SimpleSessionEventWatcher() = default;

  virtual std::unique_ptr<IEventWatchCallbackIdentifier> addCallback(
      folly::Function<void(SessionEvent)>&&) override;
  virtual folly::Function<void(SessionEvent)> removeCallback(
      std::unique_ptr<IEventWatchCallbackIdentifier>&&) override;
  virtual void removeCallbackSoon(
      std::unique_ptr<IEventWatchCallbackIdentifier>&&) override;

  virtual folly::Future<SessionEvent> getEventForState(SessionState) override;

  size_t getNextIndex() const;

  std::pair<size_t, SessionState> getNextIndexAndCurrentState() const;

  void onSessionEvent(SessionState);

 private:
  class Callback {
   public:
    explicit Callback(folly::Function<void(SessionEvent)>&& f)
        : f_(std::move(f)) {}

    void call(const SessionEvent&);
    folly::Function<void(SessionEvent)> extract();

   private:
    std::mutex lock_;
    folly::Function<void(SessionEvent)> f_;
  };

  class CallbackIdentifier : public virtual IEventWatchCallbackIdentifier {
   public:
    std::list<std::shared_ptr<Callback>>::iterator i_;
  };

  std::shared_ptr<Callback> removeCallbackPtr(
      std::unique_ptr<IEventWatchCallbackIdentifier>&&);

  mutable folly::SharedMutex stateLock_;
  size_t nextIndex_;
  SessionState lastState_;

  folly::SharedMutex callbacksLock_;
  std::list<std::shared_ptr<Callback>> callbacks_;

  std::mutex promisesLock_;
  std::unordered_map<SessionState, folly::SharedPromise<SessionEvent>>
      promises_;
};
}
}
}
}
