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

//! High-fidelity ports of legacy C client algorithms and cross-runtime compatibility utilities.
//!
//! To ensure drop-in binary and behavioral equivalence with historical C ZooKeeper clients,
//! these modules implement verbatim algorithmic translations of C data structures, hashing
//! strategies, address selection heuristics, and watcher dispatch orderings. Where legacy C
//! implementations contained acknowledged flaws or undefined behaviors, these ports document
//! the historical quirk alongside the normalized specification adopted by this engine.
//!
//! Primary subsystem implementations include:
//! - [`hashtable`]: Open-addressing hash table emulation preserving C bucket chaining,
//!   string hash computation, and memory traversal order.
//! - [`watchers`]: Multi-map watcher tracking data structures maintaining C collection
//!   and registration dispatch sequences.
//! - [`addrvec`]: Cyclic address vectors managing ensemble host shuffling, retry cursor
//!   advancement, and address rotation.
//! - [`servers`]: Dynamic server list rebalancing and probabilistic server selection algorithms.
//! - [`path`]: Path validation logic supporting both legacy permissive C rules and strict Java
//!   `PathUtils` standards.
//! - [`chroot`]: Canonical znode chroot path prefix concatenation, path boundary enforcement, and
//!   prefix stripping.
//! - [`connect`]: Robust connection string parsing accommodating legacy host-port formats and
//!   modern Java syntax.
//!
//! ### Embedded NUL Byte Semantics
//!
//! Whereas native C implementations operate on null-terminated strings (`char*`), Rust
//! APIs handle explicit-length byte slices (`&[u8]`). To preserve strict compatibility with
//! foreign function interfaces without introducing security vulnerabilities, two complementary
//! handling strategies are enforced:
//!
//! - *Computation Functions*: Subroutines computing transient values or keys from external
//!   strings truncate input at the first embedded NUL byte, precisely mimicking C string
//!   termination. This convention governs connection string parsing and hash table lookup keys.
//! - *Validation Functions*: Subroutines validating persistent paths or state reject inputs
//!   containing embedded NUL bytes outright. This ensures paths stored in cluster znodes or
//!   transferred across wire boundaries never diverge from the exact byte representations
//!   submitted by the caller.
//!
//! Under foreign function interfaces via the C ABI, both strategies produce identical results
//! to historical C client executions.

pub mod addrvec;
pub mod chroot;
pub mod connect;
pub mod hashtable;
pub mod path;
pub mod servers;
pub mod watchers;
