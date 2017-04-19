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

#include "zeus/client/DelegatingSessionEventWatcher.h"
#include "zeus/client/test/MockSessionEventWatcher.h"

using namespace facebook;
using namespace facebook::zeus::client;

using ::testing::_;
using ::testing::Return;
using ::testing::ByMove;

namespace facebook {
namespace zeus {
namespace client {

MATCHER_P(SharedPtrMatches, rawPtr, "") {
  return arg.get() == rawPtr;
};

class DelegatingSessionEventWatcherFixture : public ::testing::Test {
 public:
  void SetUp() override {
    mockWatcher_ = std::make_shared<MockSessionEventWatcher>();
    mockWatcher2_ = std::make_shared<MockSessionEventWatcher>();
  }

 protected:
  std::shared_ptr<MockSessionEventWatcher> mockWatcher_;
  std::shared_ptr<MockSessionEventWatcher> mockWatcher2_;
};
}
}
}

// checks that callback is migrated on call to setDelegate
TEST_F(DelegatingSessionEventWatcherFixture, switchDelegateTest) {
  auto id1 = std::make_unique<IEventWatchCallbackIdentifier>();
  auto rawId1 = id1.get();

  auto id2 = std::make_unique<IEventWatchCallbackIdentifier>();
  auto rawId2 = id2.get();

  bool secondCalled = false;

  EXPECT_CALL(*mockWatcher_, addCallback(_))
      .WillOnce(Return(ByMove(std::move(id1))));
  EXPECT_CALL(*mockWatcher_, removeCallback(SharedPtrMatches(rawId1)))
      .WillOnce(Return(ByMove([](SessionEvent) {})));
  EXPECT_CALL(*mockWatcher2_, addCallback(_))
      .WillOnce(Return(ByMove(std::move(id2))));
  EXPECT_CALL(*mockWatcher2_, removeCallback(SharedPtrMatches(rawId2)))
      .WillOnce(Return(ByMove([&secondCalled](SessionEvent) {
        EXPECT_FALSE(secondCalled);
        secondCalled = true;
      })));

  auto delegating = std::make_unique<
      DelegatingSessionEventWatcher<std::shared_ptr<ISessionEventWatcher>>>(
      mockWatcher_);
  auto returnedId = delegating->addCallback([](SessionEvent) {});
  auto returnedDelegate = delegating->setDelegate(mockWatcher2_);
  EXPECT_EQ(mockWatcher_, returnedDelegate);
  auto returnedCallback2 = delegating->removeCallback(std::move(returnedId));
  returnedCallback2(SessionEvent());
  EXPECT_TRUE(secondCalled);
}

// checks that null case is handled in setDelegate
TEST_F(DelegatingSessionEventWatcherFixture, switchDelegateToNullTest) {
  auto id = std::make_unique<IEventWatchCallbackIdentifier>();
  auto rawId = id.get();

  EXPECT_CALL(*mockWatcher_, addCallback(_))
      .WillOnce(Return(ByMove(std::move(id))));
  EXPECT_CALL(*mockWatcher_, removeCallback(SharedPtrMatches(rawId)))
      .WillOnce(Return(ByMove([](SessionEvent) {})));

  auto delegating = std::make_unique<
      DelegatingSessionEventWatcher<std::shared_ptr<ISessionEventWatcher>>>(
      mockWatcher_);
  delegating->addCallback([](SessionEvent) {});
  auto returnedDelegate = delegating->setDelegate(nullptr);
  EXPECT_EQ(mockWatcher_, returnedDelegate);
}
