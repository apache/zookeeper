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

//! Differential testing and cross validation against legacy C client source routines.
//!
//! This crate establishes a verified boundary harness for comparative testing,
//! compiling extracted C reference implementations (including hash table structures,
//! address vector permutation algorithms, watcher registration maps, and extracted
//! core routines from `zookeeper.c`). It exposes safe Rust wrappers that test suites
//! execute alongside equivalent ports in `zk_proto::legacy` using randomized and
//! deterministic inputs to prove behavioral equivalence.
//!
//! Because the underlying legacy C routines rely on global and process-wide mutable state,
//! access is strictly synchronized through [`with_c`], serializing execution under a
//! global reentrancy lock. Compilation of C sources is enabled conditionally on supported
//! Unix platforms where source trees are present, surfaced via the [`C_AVAILABLE`] flag.

#![cfg_attr(test, allow(clippy::disallowed_macros))]

pub mod rng;

#[cfg(zkx_c)]
mod ffi;

#[cfg(zkx_c)]
mod safe;

#[cfg(zkx_c)]
pub use safe::{CApi, CHashTable, Delivery, LogLine, ZtEntry, with_c};

/// Manifest metadata generated during build time extraction of C reference sources.
pub mod manifest {
    include!(concat!(env!("OUT_DIR"), "/extract_manifest.rs"));
}

/// Indicates whether legacy C reference routines were compiled into the test harness.
pub const C_AVAILABLE: bool = cfg!(zkx_c);
