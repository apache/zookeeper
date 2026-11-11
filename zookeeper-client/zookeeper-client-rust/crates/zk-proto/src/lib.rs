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

//! Deterministic, pure-state ZooKeeper client protocol engine.
//!
//! This crate implements the core ZooKeeper client protocol state machine completely
//! decoupled from network I/O, thread orchestration, and system timers. External runtime
//! drivers feed incoming wire bytes, client operations, and monotonic millisecond timestamps into
//! the state engine, which deterministically yields outbound wire buffers, client-facing
//! event notifications, and interest deadlines.
//!
//! State transitions, transaction ID (xid) tracking, heartbeat deadlines, authentication
//! workflows, and watch event dispatching are strictly driven by discrete inputs, ensuring
//! full reproducibility, comprehensive testability under simulation, and seamless integration
//! into both legacy C-compatible event loops and modern asynchronous native runtimes.

#![forbid(unsafe_code)]
// Clippy configurations relaxed for test assertion macros.
#![cfg_attr(test, allow(clippy::disallowed_macros))]

pub mod action;
pub mod compat;
pub mod config;
pub mod errno;
pub mod hooks;
pub mod legacy;
pub mod log;
pub mod native;
pub mod output;
pub mod request;
pub mod secret;
pub mod state;
pub mod time;

mod auth;
mod close;
mod connection;
mod hosts;
mod interest;
mod recv;
mod requests;
mod responses;
mod sasl;
mod session;
mod submit;
mod teardown;
mod timers;
mod watches;

pub use zk_jute::rc;

pub use action::{
    Action, CloseProgress, ConnectStart, HostResolution, Interest, InterestOutcome, ProbeOutcome,
    ProbeTlsFailure, ReadOutcome, RemoveHint, ResolveError, SaslAddr, SaslCall, SaslCallKind,
    SaslError, SaslStep, TlsConfigStep, TlsResourceAt, TransportFailure,
};
pub use close::{CLOSE_FLUSH_MS, CLOSE_WAIT_MS};
pub use compat::Compat;
pub use config::{
    ClientId, Config, DriverMode, Endpoint, HostSpec, HostToken, InterestBits, SessionDelivery,
};
pub use connection::{Connection, Leftovers};
pub use hooks::{DefaultWatcher, LogFilter, Rng, SaslSession, Tokens};
pub use log::{LogArg, LogLevel, LogRecord, LogSite, SiteInfo};
pub use output::{OpResult, Origin, Output, Reply, Stopped, TerminateReason, WatchedEvent};
pub use request::{
    AddAuthError, InputDefect, MultiOp, MultiOpRef, Op, PathIn, Request, Route, Stage, SubmitError,
    Submitted, check_ttl,
};
pub use responses::ReplyData;
pub use secret::SecretBytes;
pub use state::{Cause, State};
pub use time::Millis;
