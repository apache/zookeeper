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

#include "zeus/client/test/NetworkErrorInjectingClient.h"

namespace facebook {
namespace zeus {
namespace client {
template <class T>
folly::Future<T> NetworkErrorInjectingClient::maybeInjectNetworkErrors(
    folly::Function<folly::Future<T>()>&& f) {
  if (folly::Random::oneIn(errorRate_)) {
    if (folly::Random::oneIn(errorRate_)) {
      f();
    }
    return folly::makeFuture<T>(ZookeeperTimeoutException());
  }

  return f();
}

folly::Future<GetChildrenResult> NetworkErrorInjectingClient::getChildren(
    const std::string& path) {
  return maybeInjectNetworkErrors<GetChildrenResult>(
      [this, &path]() { return DelegatingZookeeperClient::getChildren(path); });
}

folly::Future<folly::Unit> NetworkErrorInjectingClient::deleteNode(
    const std::string& path,
    int version) {
  return maybeInjectNetworkErrors<folly::Unit>([this, &path, version]() {
    return DelegatingZookeeperClient::deleteNode(path, version);
  });
}

CreateResult NetworkErrorInjectingClient::safeCreateNode(
    const std::string& path,
    const std::string& data,
    CreateMode createMode) {
  return DelegatingZookeeperClient::createNodeInternal(
             path, folly::StringPiece(data), createMode, ACL())
      .get();
}

folly::Future<CreateResult> NetworkErrorInjectingClient::createNodeInternal(
    const std::string& path,
    folly::ByteRange data,
    CreateMode createMode,
    const ACL& acl) {
  return maybeInjectNetworkErrors<CreateResult>(
      [f = DelegatingZookeeperClient::createNodeInternal(
           path, data, createMode, acl)]() mutable { return std::move(f); });
}
}
}
}
