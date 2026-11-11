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

//! In-memory mock ZooKeeper server for integration, protocol, and fault injection testing.
//!
//! The server binds to `127.0.0.1` on an OS-assigned ephemeral port and implements the ZooKeeper
//! wire protocol over synchronous `std::net` sockets and OS threads. An internal state machine
//! simulates Apache ZooKeeper ensemble semantics: maintaining a hierarchical in-memory data tree,
//! tracking client sessions and ephemeral nodes, enforcing access control lists (ACLs), firing
//! and managing watches, processing atomic multi-transaction batches, authenticating client
//! credentials, and returning standard ZooKeeper error codes. A programmable [`Script`] engine
//! matches incoming frames against [`Rule`] predicates to inject network or protocol faults (such
//! as connection closures, dropped replies, payload corruption, or synthetic errors). An audit
//! transcript records every inbound wire frame as a [`FrameRecord`].
//!
//! # Deterministic Execution
//!
//! Session identifiers, connection passwords, probabilistic fault rules, and packet chunking
//! boundaries are derived from a single deterministic seed. Frame processing across client
//! connections is serialized under a central state machine lock. Watch notifications are dispatched
//! in deterministic lexicographical order. Node timestamps (`ctime`, `mtime`) are computed from a
//! logical clock (`epoch_ms + zxid`), producing reproducible wire transcripts across test runs.
//! Only asynchronous wall-clock events, such as session lease expirations and explicit delay
//! actions, depend on system time.
//!
//! # Example
//!
//! ```
//! use zk_fakeserver::{Action, FakeServer, RawClient, Rule};
//! use zk_jute::opcode;
//! use zk_jute::proto::GetDataRequest;
//!
//! let server = FakeServer::start().map_err(|e| e.to_string())?;
//! server.add_rule(Rule::on_request().op(opcode::GET_DATA).then(Action::Close));
//! let (mut client, resp) = RawClient::open(server.addr(), 10_000).map_err(|e| e.to_string())?;
//! assert_ne!(resp.session_id, 0);
//! let req = GetDataRequest { path: "/".into(), watch: false };
//! assert!(client.call(opcode::GET_DATA, &req).is_err());
//! assert_eq!(server.transcript().len(), 2);
//! # Ok::<(), String>(())
//! ```
//!
//! # Protocol Support and Limitations
//!
//! - **SASL Authentication:** Supports the DIGEST-MD5 mechanism, configured via
//!   [`FakeServer::set_sasl_digest`]. Without configured SASL credentials, incoming SASL requests
//!   fail with `ZAUTHFAILED`.
//! - **Connection Teardown:** Simulated connection closures perform standard TCP shutdowns (FIN)
//!   rather than `SO_LINGER` RST resets to remain portable across standard library I/O targets.
//! - **Transport Security:** With the `tls` feature enabled, `TlsAcceptor` provides server side
//!   TLS encryption backed by `rustls`. Custom transports can be integrated via [`Acceptor`].

#![forbid(unsafe_code)]
// Permit unwrap, expect, and panic macros within unit tests for concise assertions.
#![cfg_attr(test, allow(clippy::disallowed_macros))]

mod client;
mod config;
mod consts;
mod digest;
mod link;
mod model;
mod path;
mod rng;
mod script;
mod server;
#[cfg(feature = "tls")]
mod tls;
mod transcript;
mod transport;

pub use client::{DEFAULT_WAIT, RawClient, RawEvent, RawReply};
pub use config::{AuthPolicy, Chunking, ClusterConfig, HandshakeStyle, ServerOptions};
pub use consts::{add_watch_mode, create_mode, event, perms, watcher_type};
pub use digest::digest_id;
pub use model::{NodeInfo, SessionInfo};
pub use script::{Action, Malformed, Rule, Script, Trigger};
pub use server::{Cluster, Ensemble, FakeServer};
#[cfg(feature = "tls")]
pub use tls::{PeerEnd, TlsAcceptor, TlsAcceptorBuilder, TlsSession};
pub use transcript::{ConnectInfo, Detail, FrameKind, FrameRecord, WatchSet};
pub use transport::{Acceptor, PlainAcceptor, Transport};
