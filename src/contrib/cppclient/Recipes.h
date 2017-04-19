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

#include "zeus/client/ZookeeperClient.h"

namespace facebook {
namespace zeus {
namespace client {

/**
 * Delete the node at the given path along with all recursive children.  The
 * returned folly::Future gives the number of deleted nodes.  This Future can
 * possibly throw a ZookeeperNotEmptyException if we were unable to delete all
 * child nodes due to some other process adding them in parallel.  The caller
 * is responsible for guaranteeing the lifetime of the provided client until
 * the Future completes.
 *
 * Note that since this is implemented on top of Zookeeper primitives, it does
 * not obey the ordering guarantees one normally expects from Zookeeper
 * operations.  Namely, a call to exists(path) issued immediately after this
 * on the same session can return true even if the recursive deletion is
 * successful.  In order to be sure that the nodes have been deleted, it's
 * necessary to wait for the returned Future to complete.
 */
folly::Future<int> deleteRecursive(IZookeeperClient*, const std::string& path);
/**
 * Same as above, but we will guarantee the lifetime of the client.
 */
inline folly::Future<int> deleteRecursive(
    std::shared_ptr<IZookeeperClient> zk,
    const std::string& path) {
  auto f = deleteRecursive(zk.get(), path);
  return f.then([zk = std::move(zk)](int numDeleted) { return numDeleted; });
}

folly::Future<CreateResult> createNodeWithAncestors(
    IZookeeperClient* zk,
    const std::string& path,
    folly::ByteRange data,
    CreateMode createMode = CreateMode(),
    const ACL& = ACL());
inline folly::Future<CreateResult> createNodeWithAncestors(
    std::shared_ptr<IZookeeperClient> zk,
    const std::string& path,
    folly::ByteRange data,
    CreateMode createMode = CreateMode(),
    const ACL& acl = ACL()) {
  return createNodeWithAncestors(zk.get(), path, data, createMode, acl);
}
inline folly::Future<CreateResult> createNodeWithAncestors(
    IZookeeperClient* zk,
    const std::string& path,
    const std::string& data,
    CreateMode createMode = CreateMode(),
    const ACL& acl = ACL()) {
  return createNodeWithAncestors(
      zk, path, folly::StringPiece(data), createMode, acl);
}
inline folly::Future<CreateResult> createNodeWithAncestors(
    std::shared_ptr<IZookeeperClient> zk,
    const std::string& path,
    const std::string& data,
    CreateMode createMode = CreateMode(),
    const ACL& acl = ACL()) {
  return createNodeWithAncestors(
      std::move(zk), path, folly::StringPiece(data), createMode, acl);
}

folly::Future<bool> connectWithTimeout(IZookeeperClient&);

template <class F>
std::result_of_t<F(const std::shared_ptr<IZookeeperClient>&)>
retryOnNetworkException(
    std::shared_ptr<IZookeeperClient> conn,
    F&& operation) {
  auto f = operation(conn);
  return f.onError(
      [ conn = std::move(conn), operation = std::forward<F>(operation) ](
          folly::exception_wrapper ew) mutable
          ->std::result_of_t<F(const std::shared_ptr<IZookeeperClient>&)> {
            if (!ew.is_compatible_with<ZookeeperNetworkException>()) {
              return ew;
            }
            auto f2 = connectWithTimeout(*conn);
            return f2.then([
              conn = std::move(conn),
              operation = std::forward<F>(operation),
              ew = std::move(ew)
            ](bool connected) mutable {
              return connected
                  ? retryOnNetworkException(
                        std::move(conn), std::forward<F>(operation))
                  : ew;
            });
          });
}

folly::Future<bool> claimEphemeralNode(
    const std::shared_ptr<IZookeeperClient>&,
    const std::string& path,
    folly::ByteRange data);
inline folly::Future<bool> claimEphemeralNode(
    const std::shared_ptr<IZookeeperClient>& zk,
    const std::string& path,
    const std::string& data) {
  return claimEphemeralNode(zk, path, folly::StringPiece(data));
}
}
}
}
