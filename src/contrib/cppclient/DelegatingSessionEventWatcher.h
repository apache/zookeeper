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
#include <list>
#include <mutex>
#include "zeus/client/ZookeeperClient.h"

namespace facebook {
namespace zeus {
namespace client {

/**
 * Provides an RAII-mechanism for registering callbacks to a delegate
 * ISessionEventWatcher.  Callbacks registered through here will be
 * automatically deregistered in this object's destructor.
 * getEventForState() calls are simply routed through to the delegate.
 * The template parameter T should be a pointer type to ISessionEventWatcher,
 * such as ISessionEventWatcher* or std::shared_ptr<ISessionEventWatcher>.
 */
template <class T>
class DelegatingSessionEventWatcher : public virtual ISessionEventWatcher {
 public:
  explicit DelegatingSessionEventWatcher(T delegate)
      : delegate_(std::move(delegate)) {}

  virtual ~DelegatingSessionEventWatcher() {
    // We made a promise to RAII-style deregister all callback registrations
    // that went through us.  Fulfill that promise here.
    auto delegate = getRLock();
    std::unique_lock<std::mutex> g(callbacksLock_);
    for (auto& callbackIdentifier : delegateCallbackIdentifiers_) {
      (*delegate)->removeCallback(std::move(callbackIdentifier));
    }
  }

  /**
   * Change the delegate ISessionEventWatcher.  All callbacks will be migrated
   * to the new delegate, unless the new delegate is null, in which case they
   * will only be removed from the previous delegate.  The previous delegate is
   * returned.
   */
  T setDelegate(T newDelegate) {
    auto g = delegate_.wlock();
    std::unique_lock<std::mutex> cg(callbacksLock_);
    if (newDelegate) {
      for (auto& callbackIdentifier : delegateCallbackIdentifiers_) {
        auto callback = (*g)->removeCallback(std::move(callbackIdentifier));
        callbackIdentifier = newDelegate->addCallback(std::move(callback));
      }
    } else {
      for (auto& callbackIdentifier : delegateCallbackIdentifiers_) {
        (*g)->removeCallback(std::move(callbackIdentifier));
      }
      delegateCallbackIdentifiers_.clear();
    }
    std::swap(*g, newDelegate);
    return newDelegate;
  }

  virtual std::unique_ptr<IEventWatchCallbackIdentifier> addCallback(
      folly::Function<void(SessionEvent)>&& callback) override {
    auto dg = getRLock();
    // First, register the callback through the delegate.
    auto delegateIdentifier = (*dg)->addCallback(std::move(callback));

    // We need to hold on the the callback identifier that the delegate gave
    // us so that we can deregister on destruction.  So, we stuff that
    // identifiers in our own list and give the client another callback
    // identifier that points into our list.  That way we can deregister whether
    // on client request or on destruction.
    auto rval = std::make_unique<CallbackIdentifier>();
    std::unique_lock<std::mutex> g(callbacksLock_);
    delegateCallbackIdentifiers_.push_front(std::move(delegateIdentifier));
    rval->i_ = delegateCallbackIdentifiers_.begin();
    return std::move(rval);
  }

  virtual folly::Function<void(SessionEvent)> removeCallback(
      std::unique_ptr<IEventWatchCallbackIdentifier>&& i) override {
    auto dg = getRLock();
    return (*dg)->removeCallback(removeCallbackInternal(std::move(i)));
  }

  virtual void removeCallbackSoon(
      std::unique_ptr<IEventWatchCallbackIdentifier>&& i) override {
    auto dg = getRLock();
    (*dg)->removeCallbackSoon(removeCallbackInternal(std::move(i)));
  }

  virtual folly::Future<SessionEvent> getEventForState(
      SessionState state) override {
    return (*getRLock())->getEventForState(state);
  }

 protected:
  typename folly::Synchronized<T>::ConstLockedPtr getRLock() const {
    return delegate_.rlock();
  }

 private:
  std::unique_ptr<IEventWatchCallbackIdentifier> removeCallbackInternal(
      std::unique_ptr<IEventWatchCallbackIdentifier>&& i) {
    auto* ci = dynamic_cast<CallbackIdentifier*>(i.get());
    if (!ci) {
      throw UnrecognizedCallbackIdentifierException();
    }
    // The callback identifier contents are an iterator into our list.
    std::unique_lock<std::mutex> g(callbacksLock_);
    // First, look into the contents of that list element so that we can
    // deregister from the delegate.  (Actual deregistration from the delegate
    // can take place outside the lock, however.)
    auto delegateIdentifier = std::move(*ci->i_);
    // Then, remove the wrapping callback identifier from our list.
    delegateCallbackIdentifiers_.erase(ci->i_);
    // Finally, return the underlying identifier for deregistration from the
    // delegate.
    return delegateIdentifier;
  }

  class CallbackIdentifier : public virtual IEventWatchCallbackIdentifier {
   public:
    std::list<std::unique_ptr<IEventWatchCallbackIdentifier>>::iterator i_;
  };

  folly::Synchronized<T> delegate_;

  std::mutex callbacksLock_;
  std::list<std::unique_ptr<IEventWatchCallbackIdentifier>>
      delegateCallbackIdentifiers_;
};
}
}
}
