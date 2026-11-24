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

//! Native Rust client for Apache ZooKeeper.
//!
//! Built atop the low level `zk-proto` state machine and `zk-driver` network I/O
//! abstractions in `Compat::NATIVE` mode, this crate provides an ergonomic, asynchronous
//! ZooKeeper client API. It features validated path abstractions, strictly typed
//! operation results, cancellation-safe futures, event watcher streams, and a fluent
//! client configuration builder, while delegating wire protocol framing and socket state
//! management to the underlying layers.
//!
//! Request submission methods dispatch operations immediately into the underlying driver's
//! pipeline before returning dedicated response futures, ensuring that application method
//! invocation order strictly matches wire transmission order.
//!
//! For architectural layering, concurrency invariants, and component interactions,
//! refer to `IMPLEMENTATION_GUIDE.md`.
//!
//! # Examples
//!
//! Connect, create an ephemeral sequential node, and read a node back:
//!
//! ```no_run
//! use apache_zookeeper::{acl, Client, CreateMode, Error};
//! use std::time::Duration;
//!
//! #[tokio::main]
//! async fn main() -> Result<(), Box<dyn std::error::Error>> {
//!     let zk = Client::builder("zk1:2181,zk2:2181,[2001:db8::3]:2181/app")
//!         .session_timeout(Duration::from_secs(15))
//!         .connect()
//!         .await?;
//!
//!     let (me, _) = zk
//!         .create("/workers/w-", b"host-a", CreateMode::EphemeralSequential, acl::OPEN_ACL_UNSAFE)
//!         .await?;
//!     match zk.get_data("/config/limits").await {
//!         Ok((data, stat)) => println!("{me}: limits v{} = {data:?}", stat.version.get()),
//!         Err(Error::NoNode) => println!("no limits configured"),
//!         Err(e) => return Err(e.into()),
//!     }
//!     zk.close().await?;
//!     Ok(())
//! }
//! ```
//!
//! Request pipelining: Operation dispatch order governs wire transmission order
//! regardless of future polling order:
//!
//! ```no_run
//! # use apache_zookeeper::{acl, Client, CreateMode};
//! # async fn run(zk: &Client) {
//! let a = zk.create("/q/item-", b"1", CreateMode::PersistentSequential, acl::OPEN_ACL_UNSAFE);
//! let b = zk.create("/q/item-", b"2", CreateMode::PersistentSequential, acl::OPEN_ACL_UNSAFE);
//! let c = zk.get_children("/q"); // sees a and b
//! let (rb, ra, rc) = tokio::join!(b, a, c); // a has the lower sequence number
//! # let _ = (rb, ra, rc);
//! # }
//! ```
//!
//! Resilient one-shot watch pattern with re-registration:
//!
//! ```no_run
//! # use apache_zookeeper::{Client, EventKind, WatchError};
//! # fn apply(_: &[u8]) {}
//! # async fn run(zk: &Client) -> Result<(), Box<dyn std::error::Error>> {
//! loop {
//!     let (data, _stat, watcher) = zk.get_data_watched("/config/limits").await?;
//!     apply(&data);
//!     match watcher.await {
//!         Ok(ev) if ev.kind == EventKind::NodeDeleted => return Ok(()),
//!         Ok(_) => continue, // changed: read again and set the watch again
//!         Err(WatchError::SessionExpired) => return Ok(()), // make a new client
//!         Err(e) => return Err(e.into()),
//!     }
//! }
//! # }
//! ```
//!
//! Persistent recursive watch with lag detection and state recovery:
//!
//! ```no_run
//! # use apache_zookeeper::{AddWatchMode, Client, WatchError};
//! # async fn run(zk: &Client) -> Result<(), Box<dyn std::error::Error>> {
//! let mut w = zk.add_watch("/services", AddWatchMode::PersistentRecursive).await?;
//! let mut view = zk.get_children("/services").await?;
//! while let Some(item) = w.next().await {
//!     match item {
//!         Ok(ev) => println!("{:?} at {}", ev.kind, ev.path),
//!         Err(WatchError::Lagged { missed }) => {
//!             eprintln!("EventQueue overflow: {missed} events dropped; refreshing tree state");
//!             view = zk.get_children("/services").await?;
//!         }
//!         Err(terminal) => {
//!             eprintln!("Persistent watch terminated: {terminal}");
//!             break;
//!         }
//!     }
//! }
//! # let _ = view;
//! # Ok(())
//! # }
//! ```
//!
//! Multi-operation transaction batch with strongly typed result extraction:
//!
//! ```no_run
//! # use apache_zookeeper::{
//! #     acl, Client, CreateMode, Created, Error, MultiError, MultiWriter, Stat,
//! # };
//! # async fn run(
//! #     zk: &Client, owner: Stat, counter: Stat, n: u64,
//! # ) -> Result<(), Box<dyn std::error::Error>> {
//! let payload = b"job".to_vec();
//! let next = n.to_be_bytes();
//! let mut txn = MultiWriter::new();
//! txn.check("/lock/owner", owner.version)?;
//! let mode = CreateMode::PersistentSequential;
//! let job = txn.create("/jobs/j-", &payload, mode, acl::OPEN_ACL_UNSAFE)?;
//! let ctr = txn.set_data("/jobs/counter", &next, counter.version)?;
//! match zk.multi(&txn).await {
//!     Ok(r) => {
//!         let created: Option<&Created> = r.get(job);
//!         let new_ctr: Option<&Stat> = r.get(ctr);
//!         println!("{created:?} {new_ctr:?}");
//!     }
//!     Err(MultiError::Aborted { index: 0, error: Error::BadVersion, .. }) => {
//!         eprintln!("Lock acquisition failed: version conflict");
//!     }
//!     Err(e) => return Err(e.into()),
//! }
//! # Ok(())
//! # }
//! ```
//!
//! Secure TLS configuration featuring server verification and encrypted client credentials:
//!
//! ```no_run
//! # #[cfg(feature = "tls-rustls")]
//! # async fn run(
//! #     passphrase: apache_zookeeper::SecretString,
//! # ) -> Result<(), Box<dyn std::error::Error>> {
//! # use apache_zookeeper::{Client, TlsOptions};
//! let tls = TlsOptions::builder()
//!     .ca_file("/etc/zk/ca.pem") // only this root is trusted
//!     .client_identity("/etc/zk/client.pem", "/etc/zk/client.key", Some(passphrase))
//!     .build()?; // Validates certificate chain and private key formats
//! let zk = Client::builder("zk1.example.com:2281,zk2.example.com:2281")
//!     .tls(tls)
//!     .connect()
//!     .await?;
//! # let _ = zk;
//! # Ok(())
//! # }
//! ```
//!
//! Zero allocation static path definitions and synchronous session migration:
//!
//! ```no_run
//! # #[cfg(feature = "blocking")]
//! # fn run(zk: apache_zookeeper::Client, hosts: &str) -> Result<(), Box<dyn std::error::Error>> {
//! # use apache_zookeeper::{Client, ZPath};
//! const LEADER: ZPath<'static> = ZPath::from_static("/election/leader").unwrap();
//!
//! // Process A: detach session credentials
//! let (id, pw) = zk.detach();
//! // Process B: attach session credentials in synchronous environment lacking an async runtime
//! let zk = apache_zookeeper::blocking::Client::connect(Client::builder(hosts).session(id, pw))?;
//! let children = zk.get_children("/workers")?;
//! let leader = zk.get_data(LEADER)?;
//! # let _ = (children, leader);
//! # Ok(())
//! # }
//! ```

#![forbid(unsafe_code)]
#![deny(missing_docs)]
// Clippy configuration for unit test modules.
#![cfg_attr(
    test,
    allow(
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::indexing_slicing,
        clippy::arithmetic_side_effects,
        clippy::disallowed_macros
    )
)]
// The generated test harness main has a large frame with no source span.
// Test builds allow it. Library builds keep the workspace deny.
#![cfg_attr(test, allow(clippy::large_stack_frames))]

#[cfg(all(
    feature = "tls-rustls",
    not(any(
        feature = "tls-ring",
        feature = "tls-aws-lc-rs",
        feature = "tls-aws-lc-rs-fips"
    ))
))]
compile_error!(
    "feature `tls-rustls` needs exactly one TLS provider feature: \
     `tls-ring`, `tls-aws-lc-rs` or `tls-aws-lc-rs-fips`"
);

#[cfg(any(
    all(feature = "tls-ring", feature = "tls-aws-lc-rs"),
    all(feature = "tls-ring", feature = "tls-aws-lc-rs-fips"),
    all(feature = "tls-aws-lc-rs", feature = "tls-aws-lc-rs-fips")
))]
compile_error!(
    "enable only one TLS provider feature: \
     `tls-ring`, `tls-aws-lc-rs` or `tls-aws-lc-rs-fips`"
);

#[cfg(all(feature = "tls-platform-verifier", feature = "tls-aws-lc-rs-fips"))]
compile_error!(
    "feature `tls-platform-verifier` cannot be combined with `tls-aws-lc-rs-fips`: \
     the OS verifier checks signatures outside the FIPS module"
);

pub mod acl;
mod auth;
#[cfg(feature = "blocking")]
pub mod blocking;
mod builder;
mod client;
mod config;
mod connect_string;
mod decode;
mod driver;
mod error;
mod follow;
mod future;
mod host;
mod multi;
mod observe;
mod ops;
mod path;
mod rng;
mod route;
#[cfg(feature = "sasl")]
pub mod sasl;
mod secret;
#[cfg(feature = "serde")]
mod serde_impls;
mod shared;
mod slot;
mod sync;
#[cfg(test)]
mod test_config;
#[cfg(feature = "tls-rustls")]
mod tls;
mod tokens;
mod types;
mod watch;

pub use acl::{Acl, Id, Perms};
pub use auth::ClientInfo;
pub use builder::{
    ClientBuilder, ConnectFuture, DnsRefresh, Resolve, Runtime, ServerOrder, ThreadOptions,
};
pub use client::{Client, ClientStats, WeakClient};
pub use config::{EnsembleConfig, Reconfig, ServerEntry, ServerRole};
pub use error::{ConnectError, DisconnectCause, Error, MultiError, Result, WatchError};
pub use future::{CloseFuture, ReadyFuture, ResponseFuture};
pub use multi::{
    Created, FromOpResult, MultiReader, MultiResults, MultiWriter, OpHandle, OpResult, ReadResult,
};
pub use observe::{Metrics, OpKind};
pub use path::{Components, PathArg, PathError, PathErrorReason, ZPath, ZPathBuf};
#[cfg(feature = "sasl")]
pub use sasl::{
    CredentialError, CredentialSource, SaslConfig, SaslContext, SaslError, SaslErrorKind,
    SaslMechanism, SaslMechanismFactory, SaslStep,
};
pub use secret::SecretString;
#[cfg(feature = "tls-rustls")]
pub use tls::{ReloadPolicy, TlsConfigError, TlsOptions, TlsOptionsBuilder};
pub use types::{
    CreateMode, EphemeralKind, SessionId, SessionPassword, Stat, Ttl, Version, VersionArg, Zxid,
};
pub use watch::{
    AddWatchMode, Changed, EventKind, Next, OneshotWatcher, PersistentWatcher, SessionInfo,
    SessionState, StateWatcher, WatchedEvent, WatcherKind,
};
