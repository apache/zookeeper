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
#include "zeus/client/DelegatingSessionEventWatcher.h"
#include "zeus/client/ZookeeperClient.h"

namespace facebook {
namespace zeus {
namespace client {

class MockZookeeperClient : public virtual IZookeeperClient,
                            public virtual DelegatingSessionEventWatcher<
                                std::shared_ptr<ISessionEventWatcher>> {
 public:
  explicit MockZookeeperClient(
      std::shared_ptr<ISessionEventWatcher> eventWatcher)
      : DelegatingSessionEventWatcher(std::move(eventWatcher)) {}

  MOCK_CONST_METHOD1(getProperty, std::string(const std::string&));
  MOCK_CONST_METHOD0(getState, SessionState());
  MOCK_CONST_METHOD0(getSessionID, int64_t());
  MOCK_CONST_METHOD0(getSessionToken, SessionToken());
  MOCK_CONST_METHOD0(getSessionTimeout, std::chrono::milliseconds());
  MOCK_CONST_METHOD0(getConnectedHost, folly::Optional<folly::SocketAddress>());
  MOCK_METHOD1(setServers, void(const std::vector<folly::SocketAddress>&));
  MOCK_METHOD0(close, void());
  MOCK_METHOD1(getData, folly::Future<GetDataResult>(const std::string&));
  MOCK_METHOD1(getDataWithWatch, DataWithWatch(const std::string&));
  MOCK_METHOD3(
      setDataInternal,
      folly::Future<Stat>(const std::string&, folly::ByteRange, int));
  MOCK_METHOD4(
      createNodeInternal,
      folly::Future<CreateResult>(
          const std::string&,
          folly::ByteRange,
          CreateMode,
          const ACL&));
  MOCK_METHOD2(deleteNode, folly::Future<folly::Unit>(const std::string&, int));
  MOCK_METHOD1(
      getChildren,
      folly::Future<GetChildrenResult>(const std::string&));
  MOCK_METHOD1(getChildrenWithWatch, ChildrenWithWatch(const std::string&));
  MOCK_METHOD1(
      exists,
      folly::Future<folly::Optional<Stat>>(const std::string&));
  MOCK_METHOD1(existsWithWatch, StatWithWatch(const std::string&));
  MOCK_METHOD1(getSubtreeSize, folly::Future<int64_t>(const std::string&));
  MOCK_CONST_METHOD0(newMultiOp, std::unique_ptr<IMultiOp>());
  MOCK_METHOD1(
      multi,
      folly::Future<std::vector<OpResponse>>(std::shared_ptr<IMultiOp>));
  virtual folly::Future<std::vector<OpResponse>> multi(
      std::unique_ptr<IMultiOp>&& op) override {
    return multi(std::shared_ptr<IMultiOp>(std::move(op)));
  }
  MOCK_METHOD1(getAcl, folly::Future<GetAclResult>(const std::string&));
  MOCK_METHOD3(
      setAcl,
      folly::Future<folly::Unit>(const std::string&, const ACL&, int));
  MOCK_METHOD2(
      addAuth,
      folly::Future<folly::Unit>(const std::string&, const std::string&));

 private:
  std::shared_ptr<ISessionEventWatcher> eventWatcher_;
};
}
}
}
