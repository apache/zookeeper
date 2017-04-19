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

#include "zeus/client/Recipes.h"
#include <folly/io/IOBuf.h>
#include <numeric>
#include "zeus/client/detail/Timekeeper.h"

namespace facebook {
namespace zeus {
namespace client {

namespace detail {
folly::Future<int> deleteRecursiveInternal(
    IZookeeperClient* zk,
    const std::string& path) {
  return zk->getChildren(path).then([zk, path](GetChildrenResult&& r) {
    std::vector<folly::Future<int>> fv;
    for (const auto& child : r.children) {
      fv.push_back(deleteRecursiveInternal(zk, path + "/" + child));
    }
    return folly::collect(fv).then(
        [ zk, path = std::move(path) ](std::vector<int> && nodeCounts) {
          return zk->deleteNode(path).then(
              [totalNodes =
                   std::accumulate(nodeCounts.begin(), nodeCounts.end(), 1)]() {
                return totalNodes;
              });
        });
  });
}
}

folly::Future<int> deleteRecursive(
    IZookeeperClient* zk,
    const std::string& path) {
  if (path == "/") {
    return folly::makeFuture<int>(ZookeeperBadArgumentsException());
  } else {
    return detail::deleteRecursiveInternal(zk, path);
  }
}

folly::Future<CreateResult> createNodeWithAncestors(
    IZookeeperClient* zk,
    const std::string& path,
    folly::ByteRange data,
    CreateMode createMode,
    const ACL& acl) {
  std::vector<std::string> pathParts;
  folly::split('/', path, pathParts);

  if (pathParts.size() <= 1 || pathParts.front() != "") {
    return folly::makeFuture<CreateResult>(ZookeeperBadArgumentsException());
  }

  if (pathParts.size() == 2 && pathParts[1] == "") {
    return folly::makeFuture<CreateResult>(ZookeeperNodeExistsException("/"));
  }

  for (int i = 1; i < pathParts.size(); ++i) {
    if (pathParts[i] == "") {
      return folly::makeFuture<CreateResult>(ZookeeperBadArgumentsException());
    }
  }

  std::string currentPath;
  std::vector<folly::Future<folly::Unit>> ancestorFutures;
  for (int i = 1; i < pathParts.size() - 1; ++i) {
    currentPath += "/" + pathParts[i];
    ancestorFutures.push_back(
        zk->createNode(currentPath, "", CreateMode(), acl)
            .then()
            .onError([](const ZookeeperNodeExistsException&) {}));
  }
  currentPath += "/" + pathParts.back();
  auto createFuture = zk->createNode(path, data, createMode, acl);

  return folly::collect(folly::collect(ancestorFutures).then(), createFuture)
      .then([](std::tuple<folly::Unit, CreateResult>&& t) {
        return std::move(std::get<1>(t));
      });
}

folly::Future<bool> connectWithTimeout(IZookeeperClient& conn) {
  auto tks = detail::getTimekeeperSingleton();
  if (tks) {
    return conn.getEventForState(SessionState::CONNECTED)
        .within(2 * conn.getSessionTimeout(), tks.get())
        .then([](SessionEvent) { return true; })
        .onError([](const folly::TimedOut&) { return false; });
  } else {
    return folly::makeFuture(false);
  }
}

folly::Future<bool> claimEphemeralNode(
    const std::shared_ptr<IZookeeperClient>& _conn,
    const std::string& path,
    folly::ByteRange _data) {
  return retryOnNetworkException(
      _conn,
      [ path, data = folly::IOBuf::copyBuffer(_data) ](
          const std::shared_ptr<IZookeeperClient>& conn) {
        return conn->createNode(path, data->coalesce(), CreateMode::ephemeral())
            .then([](const CreateResult&) { return true; })
            .onError([conn, path](const ZookeeperNodeExistsException&) {
              return conn->exists(path).then([sid = conn->getSessionID()](
                  folly::Optional<Stat> s) {
                return s && s.value().ephemeralOwner == sid;
              });
            });
      });
}
}
}
}
