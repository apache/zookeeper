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

#include "zeus/client/DelegatingZookeeperClient.h"

namespace facebook {
namespace zeus {
namespace client {

class NetworkErrorInjectingClient : public virtual DelegatingZookeeperClient {
 public:
  /**
    * Every one in `errorRate` create, delete, or getChildren requests will
   * throw a `ZookeeperTimeoutException`, and every one in `errorRate` of those
   * will submit the request to the delegate first.
    */
  NetworkErrorInjectingClient(
      std::unique_ptr<IZookeeperClient>&& delegate,
      uint32_t errorRate)
      : DelegatingZookeeperClient(std::move(delegate)), errorRate_(errorRate) {}
  virtual ~NetworkErrorInjectingClient() = default;

 public:
  virtual folly::Future<GetChildrenResult> getChildren(
      const std::string& path) override;

  virtual folly::Future<folly::Unit> deleteNode(
      const std::string& path,
      int version = -1) override;

  /**
   * Do not inject network errors.
   */
  CreateResult safeCreateNode(
      const std::string& path,
      const std::string& data,
      CreateMode createMode);

 protected:
  virtual folly::Future<CreateResult> createNodeInternal(
      const std::string& path,
      folly::ByteRange data,
      CreateMode createMode,
      const ACL& acl) override;

 private:
  template <class T>
  folly::Future<T> maybeInjectNetworkErrors(
      folly::Function<folly::Future<T>()>&&);

  uint32_t errorRate_;
};
}
}
}
