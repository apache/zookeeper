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

class DelegatingZookeeperClient : public virtual IZookeeperClient {
 public:
  explicit DelegatingZookeeperClient(
      std::unique_ptr<IZookeeperClient>&& delegate)
      : delegate_(std::move(delegate)) {}
  virtual ~DelegatingZookeeperClient() = default;

  virtual std::string getProperty(const std::string& key) const override {
    return delegate_->getProperty(key);
  }

  virtual int64_t getSessionID() const override {
    return delegate_->getSessionID();
  }

  virtual SessionState getState() const override {
    return delegate_->getState();
  }

  virtual SessionToken getSessionToken() const override {
    return delegate_->getSessionToken();
  }

  virtual std::chrono::milliseconds getSessionTimeout() const override {
    return delegate_->getSessionTimeout();
  }

  virtual folly::Optional<folly::SocketAddress> getConnectedHost()
      const override {
    return delegate_->getConnectedHost();
  }

  virtual void setServers(
      const std::vector<folly::SocketAddress>& servers) override {
    delegate_->setServers(servers);
  }

  virtual void close() override {
    delegate_->close();
  }

  virtual folly::Future<GetDataResult> getData(
      const std::string& path) override {
    return delegate_->getData(path);
  }

  virtual DataWithWatch getDataWithWatch(const std::string& path) override {
    return delegate_->getDataWithWatch(path);
  }

  virtual folly::Future<folly::Unit> deleteNode(
      const std::string& path,
      int version = -1) override {
    return delegate_->deleteNode(path, version);
  }

  virtual folly::Future<GetChildrenResult> getChildren(
      const std::string& path) override {
    return delegate_->getChildren(path);
  }

  virtual ChildrenWithWatch getChildrenWithWatch(
      const std::string& path) override {
    return delegate_->getChildrenWithWatch(path);
  }

  virtual folly::Future<folly::Optional<Stat>> exists(
      const std::string& path) override {
    return delegate_->exists(path);
  }

  virtual StatWithWatch existsWithWatch(const std::string& path) override {
    return delegate_->existsWithWatch(path);
  }

  virtual folly::Future<int64_t> getSubtreeSize(
      const std::string& path) override {
    return delegate_->getSubtreeSize(path);
  }

  virtual std::unique_ptr<IMultiOp> newMultiOp() const override {
    return delegate_->newMultiOp();
  }

  virtual folly::Future<std::vector<OpResponse>> multi(
      std::unique_ptr<IMultiOp>&& op) override {
    return delegate_->multi(std::move(op));
  }

  virtual folly::Future<GetAclResult> getAcl(const std::string& path) override {
    return delegate_->getAcl(path);
  }

  virtual folly::Future<folly::Unit>
  setAcl(const std::string& path, const ACL& acl, int version = -1) override {
    return delegate_->setAcl(path, acl, version);
  }

  virtual folly::Future<folly::Unit> addAuth(
      const std::string& scheme,
      const std::string& cert) override {
    return delegate_->addAuth(scheme, cert);
  }

  virtual std::unique_ptr<IEventWatchCallbackIdentifier> addCallback(
      folly::Function<void(SessionEvent)>&& callback) override {
    return delegate_->addCallback(std::move(callback));
  }

  virtual folly::Function<void(SessionEvent)> removeCallback(
      std::unique_ptr<IEventWatchCallbackIdentifier>&& i) override {
    return delegate_->removeCallback(std::move(i));
  }

  virtual void removeCallbackSoon(
      std::unique_ptr<IEventWatchCallbackIdentifier>&& i) override {
    delegate_->removeCallbackSoon(std::move(i));
  }

  virtual folly::Future<SessionEvent> getEventForState(
      SessionState state) override {
    return delegate_->getEventForState(state);
  }

 protected:
  virtual folly::Future<Stat> setDataInternal(
      const std::string& path,
      folly::ByteRange data,
      int version) override {
    return delegate_->setData(path, data, version);
  }

  virtual folly::Future<CreateResult> createNodeInternal(
      const std::string& path,
      folly::ByteRange data,
      CreateMode createMode,
      const ACL& acl) override {
    return delegate_->createNode(path, data, createMode, acl);
  }

 private:
  std::unique_ptr<IZookeeperClient> delegate_;
};
}
}
}
