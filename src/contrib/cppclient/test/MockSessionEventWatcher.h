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

#include <gmock/gmock.h>
#include "zeus/client/ZookeeperClient.h"

namespace facebook {
namespace zeus {
namespace client {

class MockSessionEventWatcher : public virtual ISessionEventWatcher {
 public:
  MOCK_METHOD1(
      addCallback,
      std::unique_ptr<IEventWatchCallbackIdentifier>(
          std::shared_ptr<folly::Function<void(SessionEvent)>>));
  virtual std::unique_ptr<IEventWatchCallbackIdentifier> addCallback(
      folly::Function<void(SessionEvent)>&& callback) override {
    return addCallback(std::make_shared<folly::Function<void(SessionEvent)>>(
        std::move(callback)));
  }

  MOCK_METHOD1(
      removeCallback,
      folly::Function<void(SessionEvent)>(
          std::shared_ptr<IEventWatchCallbackIdentifier>));
  virtual folly::Function<void(SessionEvent)> removeCallback(
      std::unique_ptr<IEventWatchCallbackIdentifier>&& i) override {
    return removeCallback(
        std::shared_ptr<IEventWatchCallbackIdentifier>(std::move(i)));
  }

  MOCK_METHOD1(
      removeCallbackSoon,
      void(std::shared_ptr<IEventWatchCallbackIdentifier>));
  virtual void removeCallbackSoon(
      std::unique_ptr<IEventWatchCallbackIdentifier>&& i) override {
    removeCallbackSoon(
        std::shared_ptr<IEventWatchCallbackIdentifier>(std::move(i)));
  }

  MOCK_METHOD1(getEventForState, folly::Future<SessionEvent>(SessionState));
};
}
}
}
