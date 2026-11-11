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

//! Synchronous blocking client facade.
//!
//! Provides a synchronous blocking API where each operation internally invokes the corresponding
//! asynchronous method on [`crate::Client`] and blocks the calling thread on completion via
//! [`ResponseFuture::wait`]. Blocking relies on condition variable notifications inside response
//! slots without requiring an active asynchronous runtime. Invocations originating from client I/O
//! threads are rejected with [`Error::InvalidState`] to prevent deadlocks.
//!
//! [`ResponseFuture::wait`]: crate::ResponseFuture::wait
//! [`Error::InvalidState`]: crate::Error::InvalidState

use std::net::SocketAddr;
use std::time::Duration;

use crate::acl::Acl;
use crate::auth::ClientInfo;
use crate::builder::{ClientBuilder, Runtime, ThreadOptions};
use crate::client::ClientStats;
use crate::config::{EnsembleConfig, Reconfig};
use crate::error::{ConnectError, MultiError, Result};
use crate::multi::{MultiReader, MultiResults, MultiWriter, ReadResult};
use crate::path::{PathArg, ZPathBuf};
use crate::types::{CreateMode, SessionId, SessionPassword, Stat, VersionArg, Zxid};
use crate::watch::{
    AddWatchMode, OneshotWatcher, PersistentWatcher, SessionInfo, StateWatcher, WatcherKind,
};

/// Helper macro defining synchronous client delegation methods.
///
/// Methods annotated with `=> wait` synchronously block on the underlying
/// [`crate::future::ResponseFuture`] before returning results. Non-annotated methods delegate to
/// synchronous getters on [`crate::Client`].
macro_rules! wait_forward {
    ($(
        $(#[$attr:meta])*
        fn $name:ident(&self $(, $arg:ident: $ty:ty)* $(,)?) -> $ret:ty $(=> $wait:ident)?;
    )+) => {
        $(
            $(#[$attr])*
            pub fn $name(&self $(, $arg: $ty)*) -> $ret {
                self.inner.$name($($arg),*)$(.$wait())?
            }
        )+
    };
}

/// Synchronous blocking ZooKeeper client handle.
///
/// Operates exclusively using [`Runtime::Thread`], dedicating an isolated background OS thread to
/// execute network I/O. Blocking calls can safely execute from standard threads, `spawn_blocking`
/// pools, or user worker threads without stalling driver event loops. Calling blocking methods
/// directly inside Tokio worker threads blocks that specific runtime worker, which is strongly
/// discouraged. Reentrant invocations from client I/O threads fail with [`Error::InvalidState`].
///
/// [`Error::InvalidState`]: crate::Error::InvalidState
#[derive(Clone, Debug)]
pub struct Client {
    inner: crate::Client,
}

impl Client {
    /// Establishes a client session, blocking the calling thread until connection and credentials
    /// succeed.
    ///
    /// Preserves [`Runtime::Thread`] configurations; any other runtime variant defaults to a
    /// dedicated thread.
    ///
    /// # Errors
    ///
    /// Returns connection errors documented on[`ConnectFuture`](crate::ConnectFuture).
    pub fn connect(builder: ClientBuilder) -> std::result::Result<Client, ConnectError> {
        let builder = match builder.runtime {
            Some(Runtime::Thread(_)) => builder,
            _ => builder.runtime(Runtime::Thread(ThreadOptions::default())),
        };
        builder.connect().wait().map(|inner| Client { inner })
    }

    /// Borrows the underlying asynchronous [`Client`] handle, enabling request pipelining across
    /// multiple concurrent operations.
    #[must_use]
    pub fn as_async(&self) -> &crate::Client {
        &self.inner
    }

    wait_forward! {
        /// Synchronously creates a new znode at the specified path; see [`crate::Client::create`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered during node creation.
        fn create(
            &self,
            path: impl PathArg,
            data: &[u8],
            mode: CreateMode,
            acl: &[Acl],
        ) -> Result<(ZPathBuf, Stat)> => wait;

        /// Synchronously deletes the znode at the specified path; see [`crate::Client::delete`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered during node deletion.
        fn delete(&self, path: impl PathArg, version: impl VersionArg) -> Result<()> => wait;

        /// Synchronously checks existence of the znode at the specified path;
        /// see [`crate::Client::exists`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while checking node existence.
        fn exists(&self, path: impl PathArg) -> Result<Option<Stat>> => wait;

        /// Synchronously retrieves the binary payload and metadata stat of a znode;
        /// see [`crate::Client::get_data`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while reading node data.
        fn get_data(&self, path: impl PathArg) -> Result<(Vec<u8>, Stat)> => wait;

        /// Synchronously retrieves the optional payload and metadata stat of a znode;
        /// see [`crate::Client::get_data_opt`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while reading node data.
        fn get_data_opt(&self, path: impl PathArg) -> Result<(Option<Vec<u8>>, Stat)> => wait;

        /// Synchronously writes binary payload into the specified znode;
        /// see [`crate::Client::set_data`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while updating node data.
        fn set_data(
            &self,
            path: impl PathArg,
            data: &[u8],
            version: impl VersionArg,
        ) -> Result<Stat> => wait;

        /// Synchronously lists child names of the specified znode;
        /// see [`crate::Client::get_children`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while enumerating child nodes.
        fn get_children(&self, path: impl PathArg) -> Result<Vec<String>> => wait;

        /// Synchronously lists child names and metadata stat of the specified znode;
        /// see [`crate::Client::get_children_with_stat`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while querying children and node stat.
        fn get_children_with_stat(&self, path: impl PathArg) -> Result<(Vec<String>, Stat)> => wait;

        /// Synchronously counts all recursive descendants rooted at the path;
        /// see [`crate::Client::get_all_children_number`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while computing recursive child counts.
        fn get_all_children_number(&self, path: impl PathArg) -> Result<u32> => wait;

        /// Synchronously lists ephemeral nodes created by this session matching a prefix;
        /// see [`crate::Client::get_ephemerals`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while querying ephemeral paths.
        fn get_ephemerals(&self, prefix: impl PathArg) -> Result<Vec<ZPathBuf>> => wait;

        /// Synchronously lists all ephemeral nodes created by this session;
        /// see [`crate::Client::get_ephemerals_all`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while querying all ephemeral paths.
        fn get_ephemerals_all(&self) -> Result<Vec<ZPathBuf>> => wait;

        /// Synchronously checks existence while arming a one-shot watch;
        /// see [`crate::Client::exists_watched`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while checking node existence.
        fn exists_watched(
            &self,
            path: impl PathArg,
        ) -> Result<(Option<Stat>, OneshotWatcher)> => wait;

        /// Synchronously reads node data while arming a one-shot watch;
        /// see [`crate::Client::get_data_watched`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while reading node data.
        fn get_data_watched(
            &self,
            path: impl PathArg,
        ) -> Result<(Vec<u8>, Stat, OneshotWatcher)> => wait;

        /// Synchronously enumerates children while arming a one-shot watch;
        /// see [`crate::Client::get_children_watched`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while querying child nodes.
        fn get_children_watched(
            &self,
            path: impl PathArg,
        ) -> Result<(Vec<String>, Stat, OneshotWatcher)> => wait;

        /// Synchronously registers a persistent or persistent-recursive watch;
        /// see [`crate::Client::add_watch`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while registering the persistent watch.
        fn add_watch(
            &self,
            path: impl PathArg,
            mode: AddWatchMode,
        ) -> Result<PersistentWatcher> => wait;

        /// Synchronously deregisters active watchers on a path;
        /// see [`crate::Client::remove_watches`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while removing watches.
        fn remove_watches(
            &self,
            path: impl PathArg,
            kind: WatcherKind,
            local_only: bool,
        ) -> Result<()> => wait;

        /// Synchronously retrieves Access Control List policies and metadata stat;
        /// see [`crate::Client::get_acl`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while reading node ACL.
        fn get_acl(&self, path: impl PathArg) -> Result<(Vec<Acl>, Stat)> => wait;

        /// Synchronously updates Access Control List policies; see [`crate::Client::set_acl`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while updating node ACL.
        fn set_acl(
            &self,
            path: impl PathArg,
            acl: &[Acl],
            aversion: impl VersionArg,
        ) -> Result<Stat> => wait;

        /// Synchronously flushes outstanding updates to establish leader consistency;
        /// see [`crate::Client::sync`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered during the sync barrier operation.
        fn sync(&self, path: impl PathArg) -> Result<()> => wait;

        /// Synchronously executes a batch of mutations as an atomic transaction;
        /// see [`crate::Client::multi`].
        ///
        /// # Errors
        ///
        /// Returns multi-operation errors detailing sub-operation outcomes.
        fn multi(
            &self,
            txn: &MultiWriter<'_>,
        ) -> std::result::Result<MultiResults, MultiError> => wait;

        /// Synchronously executes a batch of read queries; see [`crate::Client::multi_read`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered during batch read execution.
        fn multi_read(&self, ops: &MultiReader<'_>) -> Result<Vec<Result<ReadResult>>> => wait;

        /// Synchronously queries cluster ensemble configuration from `/zookeeper/config`;
        /// see [`crate::Client::get_config`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while reading cluster configuration.
        fn get_config(&self) -> Result<(EnsembleConfig, Stat)> => wait;

        /// Synchronously queries cluster configuration while arming a data watch;
        /// see [`crate::Client::get_config_watched`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while reading cluster configuration.
        fn get_config_watched(&self) -> Result<(EnsembleConfig, Stat, OneshotWatcher)> => wait;

        /// Synchronously reconfigures ensemble cluster membership;
        /// see [`crate::Client::reconfig`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered during cluster reconfiguration.
        fn reconfig(
            &self,
            change: &Reconfig,
            expected: Option<i64>,
        ) -> Result<(EnsembleConfig, Stat)> => wait;

        /// Replaces the local ensemble host list without blocking;
        /// see [`crate::Client::update_servers`].
        ///
        /// # Errors
        ///
        /// Returns an error if the connection string fails parsing or if the client is terminal.
        fn update_servers(&self, connect_string: &str) -> Result<()>;

        /// Appends an authentication credential packet and awaits acknowledgement;
        /// see [`crate::Client::add_auth`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered while adding credentials.
        fn add_auth(&self, scheme: &str, data: impl Into<Vec<u8>>) -> Result<()> => wait;

        /// Synchronously queries session identity and authentication details;
        /// see [`crate::Client::who_am_i`].
        ///
        /// # Errors
        ///
        /// Returns protocol errors encountered during identity query.
        fn who_am_i(&self) -> Result<Vec<ClientInfo>> => wait;

        /// Returns a snapshot of the current connection and session state;
        /// see [`crate::Client::state`].
        #[must_use]
        fn state(&self) -> SessionInfo;

        /// Subscribes to connection and session lifecycle transitions;
        /// see [`crate::Client::state_watcher`].
        #[must_use]
        fn state_watcher(&self) -> StateWatcher;

        /// Returns the assigned session identifier and password token;
        /// see [`crate::Client::session`].
        #[must_use]
        fn session(&self) -> (SessionId, SessionPassword);

        /// Returns the negotiated session heartbeat timeout duration;
        /// see [`crate::Client::negotiated_timeout`].
        #[must_use]
        fn negotiated_timeout(&self) -> Option<Duration>;

        /// Returns the highest transaction identifier (`zxid`) observed by this client;
        /// see [`crate::Client::last_zxid`].
        #[must_use]
        fn last_zxid(&self) -> Zxid;

        /// Returns the remote socket address of the active ensemble server;
        /// see [`crate::Client::connected_server`].
        #[must_use]
        fn connected_server(&self) -> Option<SocketAddr>;

        /// Compiles diagnostic counters and current connectivity telemetry;
        /// see [`crate::Client::stats`].
        #[must_use]
        fn stats(&self) -> ClientStats;

        /// Blocks until in-flight request backlog drops below backpressure thresholds;
        /// see [`crate::Client::ready`].
        ///
        /// # Errors
        ///
        /// Returns a terminal error if the underlying session closes or expires.
        fn ready(&self) -> Result<()> => wait;

        /// Initiates graceful session termination and blocks until the shutdown sequence
        /// completes; see [`crate::Client::close`].
        ///
        /// # Errors
        ///
        /// Returns errors encountered during graceful close teardown.
        fn close(&self) -> Result<()> => wait;
    }

    /// Detaches the client handle without closing the remote ensemble session;
    /// see [`crate::Client::detach`].
    #[must_use = "the session lives on; use the credentials or close it"]
    pub fn detach(self) -> (SessionId, SessionPassword) {
        self.inner.detach()
    }
}
