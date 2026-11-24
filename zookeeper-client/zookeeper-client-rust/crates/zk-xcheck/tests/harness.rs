// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Verifies that the legacy C cross-check harness is compiled and linked on Unix targets.
//!
//! Because differential test suites in sibling modules are gated on `#[cfg(zkx_c)]`, an
//! unconditional test here ensures that missing C sources or build regressions fail the test
//! suite rather than silently skipping cross-validation.

// Intentional assertion against a compile time configuration constant.
#![allow(clippy::panic, clippy::assertions_on_constants, missing_docs)]
#![allow(clippy::disallowed_macros)]

#[cfg(unix)]
#[test]
fn c_harness_is_built_on_unix() {
    assert!(
        zk_xcheck::C_AVAILABLE,
        "the build script did not build the C sources, so the C cross-checks are off; \
         check the cargo warnings of zk-xcheck and ZKX_C_SRC"
    );
    assert!(
        !zk_xcheck::manifest::ZOOKEEPER_C_EXTRACTS.is_empty(),
        "the C extract manifest is empty"
    );
}
