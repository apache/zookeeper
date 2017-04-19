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

#include "zeus/client/QueueSizeLimitedZookeeperClient.h"
#include "zeus/client/detail/SimpleSessionEventWatcher.h"
#include "zeus/client/test/MockZookeeperClient.h"

using ::testing::Expectation;
using ::testing::Return;
using ::testing::ByMove;

namespace facebook {
namespace zeus {
namespace client {

class QueueSizeLimitedZookeeperClientFixture : public ::testing::Test {
 public:
  void SetUp() override {
    sessionEventWatcher_ =
        std::make_shared<detail::SimpleSessionEventWatcher>();
    delegate_ = std::make_unique<MockZookeeperClient>(sessionEventWatcher_);
    rawDelegate_ = delegate_.get();
  }

 protected:
  std::unique_ptr<QueueSizeLimitedZookeeperClient> newClient(int queueSize) {
    return std::make_unique<QueueSizeLimitedZookeeperClient>(
        queueSize, std::move(delegate_));
  }

  std::shared_ptr<detail::SimpleSessionEventWatcher> sessionEventWatcher_;
  std::unique_ptr<MockZookeeperClient> delegate_;
  MockZookeeperClient* rawDelegate_;
};

TEST_F(QueueSizeLimitedZookeeperClientFixture, repeatedOverfillTest) {
  auto client = newClient(1);
  std::string path("/foo");

  for (int i = 0; i < 5; ++i) {
    LOG(INFO) << "iteration " << i;

    folly::Promise<GetDataResult> getDataPromise;
    EXPECT_CALL(*rawDelegate_, getData(path))
        .WillOnce(Return(ByMove(getDataPromise.getFuture())));

    auto getDataFuture = client->getData(path);

    EXPECT_EQ(1, client->numSubmittedIncompleteRequests());
    EXPECT_EQ(1, client->numTotalIncompleteRequests());

    folly::Promise<GetChildrenResult> getChildrenPromise;

    Expectation e = EXPECT_CALL(*rawDelegate_, getSessionID());
    EXPECT_CALL(*rawDelegate_, getChildren(path))
        .After(e)
        .WillOnce(Return(ByMove(getChildrenPromise.getFuture())));

    auto getChildrenFuture = client->getChildren(path);

    EXPECT_EQ(1, client->numSubmittedIncompleteRequests());
    EXPECT_EQ(2, client->numTotalIncompleteRequests());

    client->getSessionID();
    getDataPromise.setValue(
        GetDataResult{folly::IOBuf::copyBuffer("bar"), Stat()});

    EXPECT_EQ("bar", getDataFuture.get().data->moveToFbString());
    EXPECT_EQ(1, client->numSubmittedIncompleteRequests());
    EXPECT_EQ(1, client->numTotalIncompleteRequests());

    std::vector<std::string> children;
    children.push_back("child");
    getChildrenPromise.setValue(GetChildrenResult{children, Stat()});

    EXPECT_EQ("child", getChildrenFuture.get().children.at(0));
    EXPECT_EQ(0, client->numSubmittedIncompleteRequests());
    EXPECT_EQ(0, client->numTotalIncompleteRequests());
  }
}

TEST_F(QueueSizeLimitedZookeeperClientFixture, watchTest) {
  auto client = newClient(1);

  std::string path("/foo");
  folly::Promise<GetDataResult> getDataPromise;
  auto sessionEventWatcher =
      std::make_shared<detail::SimpleSessionEventWatcher>();
  folly::Promise<NodeEvent> watchPromise;
  EXPECT_CALL(*rawDelegate_, getDataWithWatch(path))
      .WillOnce(Return(ByMove(DataWithWatch{getDataPromise.getFuture(),
                                            sessionEventWatcher,
                                            watchPromise.getFuture()})));

  auto d = client->getDataWithWatch(path);

  std::vector<SessionEvent> events;
  d.sessionEventWatcher->addCallback(
      [&events](SessionEvent e) { events.push_back(e); });

  EXPECT_EQ(1, client->numSubmittedIncompleteRequests());
  EXPECT_EQ(1, client->numTotalIncompleteRequests());

  getDataPromise.setValue(
      GetDataResult{folly::IOBuf::copyBuffer("bar"), Stat()});
  EXPECT_EQ("bar", d.response.get().data->moveToFbString());

  EXPECT_TRUE(events.empty());

  sessionEventWatcher->onSessionEvent(SessionState::READONLY);
  EXPECT_EQ(1, events.size());
  EXPECT_EQ(SessionState::READONLY, events.at(0).state);
}
}
}
}
