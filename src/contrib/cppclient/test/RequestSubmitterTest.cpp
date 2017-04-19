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

#include <gtest/gtest.h>
#include "zeus/client/RequestSubmitter.h"

namespace facebook {
namespace zeus {
namespace client {
TEST(RequestSubmitterTest, basicTest) {
  int calls = 0;
  {
    auto s = RequestSubmitter::delayed();
    s.setSubmitFunction([&]() { calls++; });
    EXPECT_EQ(0, calls);
    s.submit();
    EXPECT_EQ(1, calls);
  }
  EXPECT_EQ(1, calls);
  {
    auto s = RequestSubmitter::delayed();
    s.setSubmitFunction([&]() { calls++; });
    EXPECT_EQ(1, calls);
  }
  EXPECT_EQ(2, calls);
  {
    auto s = RequestSubmitter::immediately();
    s.setSubmitFunction([&]() { calls++; });
    EXPECT_EQ(3, calls);
  }
  EXPECT_EQ(3, calls);
  {
    RequestSubmitter s([&]() { calls++; });
    EXPECT_EQ(3, calls);
    auto f = s.extractSubmitFunction();
    EXPECT_EQ(3, calls);
    f();
    EXPECT_EQ(4, calls);
    EXPECT_THROW(s.submit(), std::logic_error);
  }
  EXPECT_EQ(4, calls);
}

TEST(RequestSubmitterTest, tempUsageTest) {
  // Verify that creating a temporary onSubmitOrDestructor submitter does what
  // we want: calls the submit function at the end of the statement.

  class LambdaRunner {
   public:
    LambdaRunner& run(folly::Function<void()>&& f) {
      f();
      return *this;
    }
  };

  int count = 0;
  auto generateRunner = [&count](RequestSubmitter* s) {
    s->setSubmitFunction([&count]() {
      EXPECT_EQ(2, count);
      count++;
    });
    return LambdaRunner();
  };
  generateRunner(RequestSubmitter::delayedPtr().get())
      .run([&count]() {
        EXPECT_EQ(0, count);
        count++;
      })
      .run([&count]() {
        EXPECT_EQ(1, count);
        count++;
      });
}
}
}
}
