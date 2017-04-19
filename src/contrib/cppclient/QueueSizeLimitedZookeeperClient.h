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

#include <folly/Synchronized.h>
#include <mutex>
#include <queue>
#include "zeus/client/DelegatingZookeeperClient.h"

namespace facebook {
namespace zeus {
namespace client {

/**
 * Delegating client that limits the total number of outstanding asynchronous
 * requests on the delegate.  All asynchronous calls are non-blocking, but the
 * corresponding call will not be issued to the delegate until the number of
 * requests outstanding on the delegate drops below the specified maximum.
 * Order of requests is preserved.
 */
class QueueSizeLimitedZookeeperClient
    : public virtual DelegatingZookeeperClient {
 public:
  QueueSizeLimitedZookeeperClient(
      size_t maxSubmittedIncompleteRequests,
      std::unique_ptr<IZookeeperClient>&& delegate);
  virtual ~QueueSizeLimitedZookeeperClient();

  /**
   * Get the current number of requests that have been issued to the delegate
   * but not yet completed.
   */
  size_t numSubmittedIncompleteRequests() const;

  /**
   * Get the number of requests not yet submitted to the delegate.
   */
  size_t numWaitingRequests() const;

  /**
   * Get the total number of outstanding requests, whether they have been issued
   * to the delegate or not.
   */
  size_t numTotalIncompleteRequests() const;

  /**
   * Get the currently configured maximum number of outstanding requests on the
   * delegate.
   */
  size_t getMaxSubmittedIncompleteRequests() const;

  /**
   * Change the maximum number of outstanding requests on the delegate.
   */
  void setMaxSubmittedIncompleteRequests(size_t);

  virtual folly::Future<GetDataResult> getData(
      const std::string& path) override;

  virtual DataWithWatch getDataWithWatch(const std::string& path) override;

  virtual folly::Future<folly::Unit> deleteNode(
      const std::string& path,
      int version = -1) override;

  virtual folly::Future<GetChildrenResult> getChildren(
      const std::string& path) override;

  virtual ChildrenWithWatch getChildrenWithWatch(
      const std::string& path) override;

  virtual folly::Future<folly::Optional<Stat>> exists(
      const std::string& path) override;

  virtual StatWithWatch existsWithWatch(const std::string& path) override;

  virtual folly::Future<std::vector<OpResponse>> multi(
      std::unique_ptr<IMultiOp>&& op) override;

  virtual folly::Future<GetAclResult> getAcl(const std::string& path) override;

  virtual folly::Future<folly::Unit>
  setAcl(const std::string& path, const ACL& acl, int version = -1) override;

  virtual folly::Future<folly::Unit> addAuth(
      const std::string& scheme,
      const std::string& cert) override;

 protected:
  virtual folly::Future<Stat> setDataInternal(
      const std::string& path,
      folly::ByteRange data,
      int version) override;

  virtual folly::Future<CreateResult> createNodeInternal(
      const std::string& path,
      folly::ByteRange data,
      CreateMode createMode,
      const ACL& acl) override;

 private:
  struct QueueState {
    size_t maxOutstandingRequests;
    size_t outstandingRequests;
    std::queue<folly::Promise<folly::Unit>> queuedRequests;
    folly::Future<folly::Unit> previousRequestSubmitted;
    bool takingRequests;
  };

  // Returns a future to wait on before submitting the next request and a
  // promise to complete once it's submitted.
  std::pair<folly::Future<folly::Unit>, folly::Promise<folly::Unit>>
  getQueueFutureAndPromise();
  static void maybeFireRequests(
      const std::shared_ptr<folly::Synchronized<QueueState>>&,
      bool requestJustCompleted);

  template <class T>
  folly::Future<T> queueRequest(folly::Function<folly::Future<T>()>&&);

  template <class T>
  std::tuple<
      folly::Future<T>,
      std::shared_ptr<ISessionEventWatcher>,
      folly::Future<NodeEvent>>
  queueWithWatch(folly::Function<std::tuple<
                     folly::Future<T>,
                     std::shared_ptr<ISessionEventWatcher>,
                     folly::Future<NodeEvent>>()>&&);

  folly::Future<Stat>
  delegateSetData(const std::string& path, folly::ByteRange data, int version);

  folly::Future<CreateResult> delegateCreateNode(
      const std::string& path,
      folly::ByteRange data,
      CreateMode createMode,
      const ACL& acl);

  std::shared_ptr<folly::Synchronized<QueueState>> state_;
};
}
}
}
