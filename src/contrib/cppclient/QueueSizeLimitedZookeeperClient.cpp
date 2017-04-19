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

namespace facebook {
namespace zeus {
namespace client {
QueueSizeLimitedZookeeperClient::QueueSizeLimitedZookeeperClient(
    size_t maxSubmittedIncompleteRequests,
    std::unique_ptr<IZookeeperClient>&& delegate)
    : DelegatingZookeeperClient(std::move(delegate)),
      state_(std::make_shared<folly::Synchronized<QueueState>>()) {
  if (maxSubmittedIncompleteRequests == 0) {
    throw std::logic_error("max submitted incomplete requests cannot be zero");
  }
  auto w = state_->wlock();
  w->maxOutstandingRequests = maxSubmittedIncompleteRequests;
  w->outstandingRequests = 0;
  w->takingRequests = true;
  w->previousRequestSubmitted = folly::makeFuture();
}

QueueSizeLimitedZookeeperClient::~QueueSizeLimitedZookeeperClient() {
  state_->wlock()->takingRequests = false;
}

size_t QueueSizeLimitedZookeeperClient::numSubmittedIncompleteRequests() const {
  return state_->rlock()->outstandingRequests;
}

size_t QueueSizeLimitedZookeeperClient::numWaitingRequests() const {
  return state_->rlock()->queuedRequests.size();
}

size_t QueueSizeLimitedZookeeperClient::numTotalIncompleteRequests() const {
  auto r = state_->rlock();
  return r->outstandingRequests + r->queuedRequests.size();
}

size_t QueueSizeLimitedZookeeperClient::getMaxSubmittedIncompleteRequests()
    const {
  return state_->rlock()->maxOutstandingRequests;
}

void QueueSizeLimitedZookeeperClient::setMaxSubmittedIncompleteRequests(
    size_t maxOutstandingRequests) {
  state_->wlock()->maxOutstandingRequests = maxOutstandingRequests;
  maybeFireRequests(state_, false);
}

void QueueSizeLimitedZookeeperClient::maybeFireRequests(
    const std::shared_ptr<folly::Synchronized<QueueState>>& state,
    bool requestJustCompleted) {
  auto w = state->wlock();
  if (requestJustCompleted) {
    w->outstandingRequests--;
  }
  std::vector<folly::Promise<folly::Unit>> promises;
  while (w->maxOutstandingRequests > w->outstandingRequests &&
         !w->queuedRequests.empty()) {
    w->outstandingRequests++;
    promises.push_back(std::move(w->queuedRequests.front()));
    w->queuedRequests.pop();
  }
  w.unlock();
  for (auto& promise : promises) {
    promise.setValue();
  }
}

std::pair<folly::Future<folly::Unit>, folly::Promise<folly::Unit>>
QueueSizeLimitedZookeeperClient::getQueueFutureAndPromise() {
  folly::Promise<folly::Unit> p;
  auto newRequestSubmitted = p.getFuture();
  auto w = state_->wlock();
  if (w->queuedRequests.empty() &&
      w->maxOutstandingRequests > w->outstandingRequests) {
    w->outstandingRequests++;
    auto rval =
        std::make_pair(std::move(w->previousRequestSubmitted), std::move(p));
    w->previousRequestSubmitted = std::move(newRequestSubmitted);
    return rval;
  } else {
    w->queuedRequests.emplace();
    auto rval = std::make_pair(
        folly::collect(
            w->queuedRequests.back().getFuture(),
            std::move(w->previousRequestSubmitted))
            .then(),
        std::move(p));
    w->previousRequestSubmitted = std::move(newRequestSubmitted);
    return rval;
  }
}

template <class T>
folly::Future<T> QueueSizeLimitedZookeeperClient::queueRequest(
    folly::Function<folly::Future<T>()>&& _op) {
  auto p = getQueueFutureAndPromise();
  return p.first.then([
    state = state_,
    op = std::move(_op),
    submittedPromise = std::move(p.second)
  ]() mutable {
    try {
      auto r = state->rlock();
      auto f = r->takingRequests ? op() : folly::Promise<T>().getFuture();
      r.unlock();
      submittedPromise.setValue();
      return f.then([state](folly::Try<T>&& t) {
        maybeFireRequests(state, true);
        return folly::makeFuture(std::move(t));
      });
    } catch (...) {
      if (!submittedPromise.isFulfilled()) {
        submittedPromise.setValue();
      }
      throw;
    }
  });
}

folly::Future<GetDataResult> QueueSizeLimitedZookeeperClient::getData(
    const std::string& path) {
  return queueRequest<GetDataResult>(
      [this, path]() { return DelegatingZookeeperClient::getData(path); });
}

folly::Future<folly::Unit> QueueSizeLimitedZookeeperClient::deleteNode(
    const std::string& path,
    int version) {
  return queueRequest<folly::Unit>([this, path, version]() {
    return DelegatingZookeeperClient::deleteNode(path, version);
  });
}

folly::Future<GetChildrenResult> QueueSizeLimitedZookeeperClient::getChildren(
    const std::string& path) {
  return queueRequest<GetChildrenResult>(
      [this, path]() { return DelegatingZookeeperClient::getChildren(path); });
}

folly::Future<folly::Optional<Stat>> QueueSizeLimitedZookeeperClient::exists(
    const std::string& path) {
  return queueRequest<folly::Optional<Stat>>(
      [this, path]() { return DelegatingZookeeperClient::exists(path); });
}

folly::Future<std::vector<OpResponse>> QueueSizeLimitedZookeeperClient::multi(
    std::unique_ptr<IMultiOp>&& _op) {
  return queueRequest<std::vector<OpResponse>>(
      [ this, op = std::move(_op) ]() mutable {
        return DelegatingZookeeperClient::multi(std::move(op));
      });
}

folly::Future<GetAclResult> QueueSizeLimitedZookeeperClient::getAcl(
    const std::string& path) {
  return queueRequest<GetAclResult>(
      [this, path]() { return DelegatingZookeeperClient::getAcl(path); });
}

folly::Future<folly::Unit> QueueSizeLimitedZookeeperClient::setAcl(
    const std::string& path,
    const ACL& acl,
    int version) {
  return queueRequest<folly::Unit>([this, path, acl, version]() {
    return DelegatingZookeeperClient::setAcl(path, acl, version);
  });
}

folly::Future<folly::Unit> QueueSizeLimitedZookeeperClient::addAuth(
    const std::string& scheme,
    const std::string& cert) {
  return queueRequest<folly::Unit>([this, scheme, cert]() {
    return DelegatingZookeeperClient::addAuth(scheme, cert);
  });
}

folly::Future<Stat> QueueSizeLimitedZookeeperClient::delegateSetData(
    const std::string& path,
    folly::ByteRange data,
    int version) {
  return DelegatingZookeeperClient::setDataInternal(path, data, version);
}

folly::Future<Stat> QueueSizeLimitedZookeeperClient::setDataInternal(
    const std::string& path,
    folly::ByteRange data,
    int version) {
  return queueRequest<Stat>(
      [ this, path, buf = folly::IOBuf::copyBuffer(data), version ]() {
        return delegateSetData(path, buf->coalesce(), version);
      });
}

folly::Future<CreateResult> QueueSizeLimitedZookeeperClient::delegateCreateNode(
    const std::string& path,
    folly::ByteRange data,
    CreateMode createMode,
    const ACL& acl) {
  return DelegatingZookeeperClient::createNodeInternal(
      path, data, createMode, acl);
}

folly::Future<CreateResult> QueueSizeLimitedZookeeperClient::createNodeInternal(
    const std::string& path,
    folly::ByteRange data,
    CreateMode createMode,
    const ACL& acl) {
  return queueRequest<CreateResult>(
      [ this, path, buf = folly::IOBuf::copyBuffer(data), createMode, acl ]() {
        return delegateCreateNode(path, buf->coalesce(), createMode, acl);
      });
}

template <class T>
std::tuple<
    folly::Future<T>,
    std::shared_ptr<ISessionEventWatcher>,
    folly::Future<NodeEvent>>
QueueSizeLimitedZookeeperClient::queueWithWatch(
    folly::Function<std::tuple<
        folly::Future<T>,
        std::shared_ptr<ISessionEventWatcher>,
        folly::Future<NodeEvent>>()>&& _op) {
  auto sessionEventWatcher =
      std::make_shared<detail::SimpleSessionEventWatcher>();
  auto _watchPromise = std::make_unique<folly::Promise<NodeEvent>>();
  auto watchFuture = _watchPromise->getFuture();
  auto dataFuture = queueRequest<T>([
    this,
    op = std::move(_op),
    sessionEventWatcher,
    watchPromise = std::move(_watchPromise)
  ]() mutable {
    auto r = op();
    std::get<1>(r)->addCallback([sessionEventWatcher](SessionEvent e) {
      sessionEventWatcher->onSessionEvent(e.state);
    });
    std::get<2>(r).then([promise = std::move(watchPromise)](
        folly::Try<NodeEvent> && t) { promise->setTry(std::move(t)); });
    return std::move(std::get<0>(r));
  });
  return std::make_tuple(
      std::move(dataFuture), sessionEventWatcher, std::move(watchFuture));
}

DataWithWatch QueueSizeLimitedZookeeperClient::getDataWithWatch(
    const std::string& path) {
  auto t = queueWithWatch<GetDataResult>([this, path]() {
    auto r = DelegatingZookeeperClient::getDataWithWatch(path);
    return std::make_tuple(
        std::move(r.response), r.sessionEventWatcher, std::move(r.watch));
  });
  return DataWithWatch{
      std::move(std::get<0>(t)), std::get<1>(t), std::move(std::get<2>(t))};
}

ChildrenWithWatch QueueSizeLimitedZookeeperClient::getChildrenWithWatch(
    const std::string& path) {
  auto t = queueWithWatch<GetChildrenResult>([this, path]() {
    auto r = DelegatingZookeeperClient::getChildrenWithWatch(path);
    return std::make_tuple(
        std::move(r.response), r.sessionEventWatcher, std::move(r.watch));
  });
  return ChildrenWithWatch{
      std::move(std::get<0>(t)), std::get<1>(t), std::move(std::get<2>(t))};
}

StatWithWatch QueueSizeLimitedZookeeperClient::existsWithWatch(
    const std::string& path) {
  auto t = queueWithWatch<folly::Optional<Stat>>([this, path]() {
    auto r = DelegatingZookeeperClient::existsWithWatch(path);
    return std::make_tuple(
        std::move(r.response), r.sessionEventWatcher, std::move(r.watch));
  });
  return StatWithWatch{
      std::move(std::get<0>(t)), std::get<1>(t), std::move(std::get<2>(t))};
}
}
}
}
