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

#include "zeus/client/RequestSubmitter.h"
#include <exception>
#include "common/logging/logging.h"

namespace facebook {
namespace zeus {
namespace client {
RequestSubmitter::RequestSubmitter(SubmitTime submitTime) noexcept
    : submitTime_(submitTime), functionSet_(false), alreadySubmitted_(false) {}

RequestSubmitter::RequestSubmitter(
    folly::Function<void()>&& submitFunction,
    SubmitTime submitTime)
    : submitTime_(submitTime),
      functionSet_(true),
      alreadySubmitted_(false),
      submit_(std::move(submitFunction)) {
  if (submitTime_ == SubmitTime::IMMEDIATELY) {
    submit();
  }
}

RequestSubmitter::RequestSubmitter(RequestSubmitter&& other) noexcept
    : submitTime_(other.submitTime_),
      functionSet_(other.functionSet_),
      alreadySubmitted_(other.alreadySubmitted_),
      submit_(std::move(other.submit_)) {
  other.alreadySubmitted_ = true;
}

RequestSubmitter& RequestSubmitter::operator=(
    RequestSubmitter&& other) noexcept {
  if (this == &other) {
    submitTime_ = other.submitTime_;
    functionSet_ = other.functionSet_;
    alreadySubmitted_ = other.alreadySubmitted_;
    submit_ = std::move(other.submit_);
  }
  return *this;
}

RequestSubmitter::~RequestSubmitter() {
  if (functionSet_ && !alreadySubmitted_) {
    try {
      submit_();
    } catch (const std::exception& e) {
      LOG(ERROR) << "exception thrown from submit function: " << e.what();
    } catch (...) {
      LOG(ERROR) << "non-exception thrown from submit function";
    }
  }
}

void RequestSubmitter::setSubmitFunction(
    folly::Function<void()>&& submitFunction) {
  if (functionSet_) {
    throw std::logic_error("multiple calls to setSubmitFunction");
  }
  submit_ = std::move(submitFunction);
  functionSet_ = true;
  if (submitTime_ == SubmitTime::IMMEDIATELY) {
    submit();
  }
}

folly::Function<void()> RequestSubmitter::extractSubmitFunction() {
  if (!functionSet_) {
    throw std::logic_error("trying to extract nonexistent submit function");
  }
  if (alreadySubmitted_) {
    throw std::logic_error(
        "trying to extract submit function that was already called");
  }
  functionSet_ = false;
  return std::move(submit_);
}

void RequestSubmitter::submit() {
  if (!functionSet_) {
    throw std::logic_error(
        "call to submit before the submit function has been set");
  }
  if (alreadySubmitted_) {
    return;
  }
  submit_();
  alreadySubmitted_ = true;
}
}
}
}
