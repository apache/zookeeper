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

#include <folly/Function.h>

namespace facebook {
namespace zeus {
namespace client {
/**
 * Used to delay the placing of Zookeeper requests on the wire.  This way
 * callbacks can be attached to returned futures before the request is on the
 * wire, guaranteeing that those callbacks will run in the per-session Zookeeper
 * completion thread.  Methods of IZookeeperClient will call setSubmitFunction
 * with a lambda that puts the corresponding request on the wire, and the caller
 * can decide when this should happen (either immediately as soon as the
 * callback is set, explicitly by calling submit(), or at RequestSubmitter
 * destruction time).
 */
class RequestSubmitter {
 public:
  /**
   * Used to indicate when the provided submit function should be invoked:
   * either immediately after it is set, or on a call to submit() or destruction
   * of the RequestSubmitter object, whichever comes first.
   */
  enum class SubmitTime {
    IMMEDIATELY,
    ON_SUBMIT_OR_DTOR,
  };

  explicit RequestSubmitter(SubmitTime) noexcept;

  static RequestSubmitter immediately() noexcept {
    return RequestSubmitter(SubmitTime::IMMEDIATELY);
  }

  static RequestSubmitter delayed() noexcept {
    return RequestSubmitter(SubmitTime::ON_SUBMIT_OR_DTOR);
  }

  static std::unique_ptr<RequestSubmitter> delayedPtr() {
    return std::make_unique<RequestSubmitter>(delayed());
  }

  /**
   * Constructs a ReqeustSubmitter with the given submit function set, as if as
   * follows:
   *
   *     RequestSubmitter s(submitImmediately);
   *     s.setSubmitFunction(f);
   */
  explicit RequestSubmitter(
      folly::Function<void()>&& f,
      SubmitTime = SubmitTime::ON_SUBMIT_OR_DTOR);

  /**
   * Move the input to a new RequestSubmitter.  After the move, this object will
   * be in the same state as if submit has been successfully called.
   */
  RequestSubmitter(RequestSubmitter&&) noexcept;
  RequestSubmitter& operator=(RequestSubmitter&&) noexcept;

  /**
   * Calls the submit function if it hasn't been called already and a submit
   * function was set, swallowing any exceptions.
   */
  ~RequestSubmitter();

  /**
   * Set the function to be called on submit (or immediately, depending on how
   * the object was constructed).  A std::logic_error will be thrown if this is
   * called multiple times.
   */
  void setSubmitFunction(folly::Function<void()>&& submit);

  /**
   * Extract the submit function and leave this object in a state as though
   * setSubmitFunction had never been called.  Throws a std::logic_error if the
   * submit function has either not been set or has already been called.
   */
  folly::Function<void()> extractSubmitFunction();

  /**
   * Call the function passed in via setSubmitFunction.  A std::logic_error will
   * be thrown if setSubmitFunction has not yet been called.  If this function
   * is called more than once, the first successful call with invoke the
   * passed-in function, and subsequent calls will do nothing.  Calls which
   * throw an exception (either because setSubmitFunction had not been called or
   * because the underlying function threw an exception) do not count in this
   * sense, and a call to submit after an exception was thrown will attempt to
   * invoke the underlying function again.
   */
  void submit();

 private:
  SubmitTime submitTime_;
  bool functionSet_ : 1;
  bool alreadySubmitted_ : 1;
  folly::Function<void()> submit_;
};
}
}
}
