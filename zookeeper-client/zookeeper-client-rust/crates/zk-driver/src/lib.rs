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

//! Network transports, TLS engines, and event-driven runtime drivers for the ZooKeeper protocol
//! core.
//!
//! This crate provides the network execution scaffolding required to drive a deterministic
//! `zk_proto::Connection` state machine across concrete operating system sockets, DNS resolvers,
//! and cryptographic sessions. Rather than duplicating socket I/O, framing, and TLS state
//! management across each concurrency model, `zk-driver` centralizes transport progression inside
//! [`iocore::IoCore`] and `iocore_tls`, and layers four runtime drivers on top of that shared
//! engine:
//!
//! - **multithreaded Drop-in Driver** ([`threaded`]): Implements the concurrency contract of the
//!   legacy C `libzookeeper_mt` library (`Shared`), coordinating a dedicated background network
//!   I/O reactor thread, a dedicated completion dispatch thread, and arbitrary application threads
//!   across a self-pipe [`Waker`] and `threaded::DispatchQueue`.
//! - **single threaded Polled Driver** ([`polled`]): Implements the external event-loop contract of
//!   the legacy C `libzookeeper_st` library (`Polled`), where the host application drives the
//!   session via alternating calls to `zookeeper_interest` and `zookeeper_process` while supporting
//!   reentrant nested API calls and deferred closes from within user callbacks.
//! - **Native Threaded Driver** ([`native`]): Powers the dedicated-I/O-thread mode of the native
//!   Rust `apache-zookeeper` client (`NativeHandle`), streaming completions directly through a
//!   [`dispatch::RouteSink`] with bounded [`dispatch::ReadGate`] backpressure and offloading DNS
//!   lookups to short-lived background worker threads ([`iocore::DeferPolicy::DNS_THREAD`]).
//! - **Native Asynchronous Tokio Driver** (`tokio`, Unix-only): Powers the async task mode of the
//!   native Rust client (`tokio::TaskHandle`), multiplexing readiness via `AsyncFd`, wakeups via
//!   `tokio::sync::Notify`, and protocol deadlines via `tokio::time::sleep` while offloading all
//!   blocking DNS, TLS setup, and probing work to `spawn_blocking` ([`iocore::DeferPolicy::ALL`])
//!   and running SASL steps on `spawn_blocking` separately.
//!
//! # Outbox Barriers and Callback Decoupling
//!
//! To maintain strict safety boundaries across the C FFI frontier and prevent reentrancy deadlocks
//! when invoking foreign code, drop in drivers never execute application callbacks or log hooks
//! while holding internal locks or active Rust stack frames. Instead, each driving step accumulates
//! pending log records, completions, watcher events, and thread coordination directives inside a
//! sequential [`outbox::Outbox`]. Whenever an execution barrier is encountered, [`iocore::IoCore`]
//! records its exact resumption point in a continuation state machine and yields
//! [`iocore::Progress::More`] (`Step::More` / `ZKRS_MORE`). The surrounding C harness drains the
//! outbox after the Rust call returns and then invokes the driver's `resume` entry point to
//! continue from the saved checkpoint.
//!
//! # Synchronization Hierarchy and Safety
//!
//! All synchronization primitives route through [`sync`], which aliases `std::sync` in standard
//! builds and swaps in `loom::sync` under `--cfg loom` for exhaustive permutation model checking.
//! Locks obey a strict acquisition hierarchy of `io` (the outer I/O runner mutex) followed by
//! `core` (`state::Core`, protecting the `zk_proto::Connection` instance) followed by `fifo` (the
//! leaf completion queue lock). Acquiring `core` mutably borrows an [`sync::Unlocked`] witness
//! token for the duration of the guard, and blocking OS calls or TLS certificate loads likewise
//! require that token, allowing the borrow checker to statically prevent blocking operations while
//! holding the `core` mutex.
//!
//! Low-level network primitives, including nonblocking [`net::sock::TcpSock`] wrappers, [`Poll`]
//! readiness abstractions, coalescing self-pipe [`Waker`] channels, and bounded `isro` read-write
//! server probes (`net::probe`), reside in [`net`], with sans-I/O TLS session traits and `rustls`
//! certificate verification pipelines in [`net::tls`]. The entire crate is compiled under
//! `#![forbid(unsafe_code)]`, confining raw pointer marshaling and C ABI exports to `zk-ffi`.

#![forbid(unsafe_code)]
#![cfg_attr(test, allow(clippy::disallowed_macros))]
// The generated test harness main has a large frame with no source span.
// Test builds allow it. Library builds keep the workspace deny.
#![cfg_attr(test, allow(clippy::large_stack_frames))]

// The `tls` feature requires exactly one backing cryptographic provider feature.
#[cfg(all(
    feature = "tls",
    not(any(
        feature = "tls-ring",
        feature = "tls-aws-lc-rs",
        feature = "tls-aws-lc-rs-fips"
    ))
))]
compile_error!(
    "feature `tls` needs exactly one provider feature: `tls-ring`, `tls-aws-lc-rs` or `tls-aws-lc-rs-fips`"
);

#[cfg(any(
    all(feature = "tls-ring", feature = "tls-aws-lc-rs"),
    all(feature = "tls-ring", feature = "tls-aws-lc-rs-fips"),
    all(feature = "tls-aws-lc-rs", feature = "tls-aws-lc-rs-fips")
))]
compile_error!(
    "feature `tls` takes only one provider feature: select one of `tls-ring`, `tls-aws-lc-rs` or `tls-aws-lc-rs-fips`"
);

pub mod clock;
pub mod dispatch;
pub mod host;
pub mod host_std;
pub mod init;
pub mod iocore;
pub(crate) mod iocore_tls;
pub mod lifecycle;
pub mod native;
pub mod net;
pub mod outbox;
pub mod polled;
pub(crate) mod resolve;
#[cfg(feature = "std-thread")]
pub mod spawn_std;
pub mod state;
pub mod sync;
pub mod threaded;
#[cfg(all(unix, feature = "tokio"))]
pub mod tokio;

#[cfg(test)]
mod iocore_tests;
#[cfg(test)]
pub(crate) mod testutil;

pub use clock::{Clock, MonotonicClock, TestClock};
pub use dispatch::{ReadGate, RouteSink};
pub use host::{Discarded, DriverTokens, HostLeaves};
pub use host_std::StdHostLeaves;
pub use init::{Init, InitArgs, InitError, InitStep};
pub use iocore::{DeferPolicy, DriverConfig, InterestOut, IoCore, Progress, RawSock};
pub use lifecycle::Lifecycle;
pub use native::{NativeGate, NativeHandle, Spawn, StartError};
pub use net::poll::{Poll, PollEvents, RustixPoll};
pub use net::sock::TcpSock;
pub use net::waker::{WakeRx, Waker};
pub use outbox::{Item, Outbox, Requeued, ResumeAt, SaslItem, Step, ThreadOp, ThreadRole};
pub use polled::Polled;
#[cfg(feature = "std-thread")]
pub use spawn_std::StdSpawner;
pub use state::{Env, SaslAnswer};
pub use sync::Unlocked;
pub use threaded::{Role, Shared};
