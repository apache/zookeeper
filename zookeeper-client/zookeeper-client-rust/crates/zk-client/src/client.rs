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

//! High-level ZooKeeper asynchronous client facade and session lifecycle coordinator.
//!
//! Exposes pipelined data tree operations, one-shot and persistent watcher registrations,
//! cluster reconfiguration routines, and session lifecycle management.
//!
//! ### Request Pipeline Architecture
//! All request methods adhere to a strictly ordered submission discipline:
//! 1. **Path Validation**: Input paths are validated via [`crate::path::checked`].
//!    Validation failures immediately yield a [`ResponseFuture::ready`] containing
//!    [`Error::InvalidPath`], preventing invalid packets from entering the driver queue
//!    or wire.
//! 2. **Watcher Pre-registration**: For monitored reads, watcher sinks are registered with
//!    [`Shared::register_oneshot`] or [`Shared::register_persistent`] prior to wire dispatch,
//!    preventing race conditions between incoming responses and subsequent watch events.
//! 3. **Protocol Encoding and Dispatch**: Request specifications are constructed via
//!    [`crate::ops`] and dispatched through [`Shared::request`], accompanied by dedicated
//!    deserialization parsers ([`crate::decode`] or [`crate::multi`]).
//!
//! Because request dispatch executes synchronously prior to returning each future, invocation
//! order guarantees transaction identifier assignment order and deterministic wire sequence.

use std::net::SocketAddr;
use std::sync::{Arc, Weak};
use std::time::Duration;

use zk_jute::JString;
use zk_proto::{Op, SecretBytes};

use crate::acl::Acl;
use crate::auth::ClientInfo;
use crate::builder::ClientBuilder;
use crate::config::{EnsembleConfig, Reconfig};
use crate::connect_string;
use crate::decode::{self, Decoder};
use crate::error::{Error, MultiError};
use crate::future::{CloseFuture, ReadyFuture, ResponseFuture};
use crate::multi::{self, MultiReader, MultiResults, MultiWriter, ReadResult};
use crate::observe::OpKind;
use crate::ops::{self, NativeOp};
use crate::path::{self, PathArg, ZPath, ZPathBuf};
use crate::shared::Shared;
use crate::tokens::{RawResult, WatchId};
use crate::types::{CreateMode, SessionId, SessionPassword, Stat, VersionArg, Zxid, wire_version};
use crate::watch::{
    AddWatchMode, OneshotWatcher, PersistentWatcher, SessionInfo, StateWatcher, WatcherKind,
};

/// Canonical ZooKeeper dynamic ensemble configuration node path; immune to client chroot prefix
/// transformations.
const CONFIG_NODE: &str = "/zookeeper/config";

/// RAII lifecycle guard triggering graceful session teardown when the final active [`Client`]
/// handle is dropped.
///
/// In-flight futures, background watchers, and the response router retain weak references or
/// temporary shared state. This guard isolates session ownership specifically to client facade
/// clones held by application code.
#[derive(Debug)]
struct LifeGuard {
    shared: Weak<Shared>,
}

impl Drop for LifeGuard {
    /// Initiates background graceful session shutdown without blocking the current thread.
    fn drop(&mut self) {
        if let Some(shared) = self.shared.upgrade() {
            shared.driver.close();
        }
    }
}

/// Asynchronous ZooKeeper client handle.
///
/// All clones of a client instance share an underlying connection session, background I/O driver,
/// and routing state. The session remains active until explicitly closed via [`Client::close`],
/// disconnected via [`Client::detach`], or until all client clones are dropped.
/// [`Client`] implements [`Send`], [`Sync`], [`Clone`], and [`Unpin`].
#[derive(Clone, Debug)]
pub struct Client {
    /// RAII guard placed first in declaration order.
    _life: Arc<LifeGuard>,
    shared: Arc<Shared>,
}

/// Non-owning client reference that permits state inspection without extending session lifetime.
///
/// Created via [`Client::downgrade`]. Can be upgraded back into a full [`Client`] instance via
/// [`WeakClient::upgrade`] as long as at least one owning client clone remains active. Once all
/// owning handles have dropped, upgrade attempts return `None`. Auxiliary components running in
/// the background hold weak clients to avoid keeping sessions open indefinitely.
#[derive(Clone, Debug)]
pub struct WeakClient {
    life: Weak<LifeGuard>,
    shared: Weak<Shared>,
}

impl WeakClient {
    /// Attempts to upgrade to a strong [`Client`] reference sharing the same session, returning
    /// `None` if all owning client handles have already dropped.
    #[must_use]
    pub fn upgrade(&self) -> Option<Client> {
        let life = self.life.upgrade()?;
        let shared = self.shared.upgrade()?;
        Some(Client {
            _life: life,
            shared,
        })
    }
}

/// Diagnostic metrics and runtime statistics for a client instance.
#[derive(Clone, Debug)]
pub struct ClientStats {
    /// Count of requests currently queued in memory or awaiting server responses on the wire.
    pub pending: usize,
    /// Cumulative payload bytes transmitted to the server (untracked by native drivers,
    /// defaults to `0`).
    pub sent: u64,
    /// Cumulative payload bytes received from the server (untracked by native drivers,
    /// defaults to `0`).
    pub received: u64,
    /// Total number of successful re-connections established following the initial connection.
    pub reconnects: u64,
    /// Highest ZooKeeper transaction identifier (`zxid`) observed by this client across all
    /// sessions.
    pub last_zxid: Zxid,
    /// Server-negotiated session timeout duration, populated once the initial connection handshake
    /// succeeds.
    pub negotiated_timeout: Option<Duration>,
    /// Remote socket address of the ensemble member to which the client is currently connected.
    pub server: Option<SocketAddr>,
}

impl Client {
    /// Initializes a fluent [`ClientBuilder`] configured with the provided connection string.
    pub fn builder(connect_string: impl Into<String>) -> ClientBuilder {
        ClientBuilder::new(connect_string.into())
    }

    /// Constructs a client facade instance wrapping shared state. Invoked internally by
    /// [`ClientBuilder::connect`].
    pub(crate) fn from_shared(shared: Arc<Shared>) -> Self {
        Self {
            _life: Arc::new(LifeGuard {
                shared: Arc::downgrade(&shared),
            }),
            shared,
        }
    }

    /// Creates a non-owning weak handle that does not prevent session termination when all strong
    /// handles drop.
    #[must_use]
    pub fn downgrade(&self) -> WeakClient {
        WeakClient {
            life: Arc::downgrade(&self._life),
            shared: Arc::downgrade(&self.shared),
        }
    }

    /// Exposes internal shared client state for crate components.
    pub(crate) fn shared(&self) -> &Arc<Shared> {
        &self.shared
    }

    /// Validates path arguments and dispatches a single protocol request, rejecting invalid paths
    /// immediately.
    fn request_at<T: 'static>(
        &self,
        path: &impl PathArg,
        sequential: bool,
        kind: OpKind,
        build: impl FnOnce(&str) -> NativeOp,
        decode: Decoder<T>,
    ) -> ResponseFuture<T> {
        self.request_data_at(path, sequential, kind, None, build, decode)
    }

    /// Validates path arguments and dispatches a request carrying an uncopied borrowed byte
    /// buffer, encoding the payload directly into the outgoing frame buffer.
    fn request_data_at<T: 'static>(
        &self,
        path: &impl PathArg,
        sequential: bool,
        kind: OpKind,
        data: Option<&[u8]>,
        build: impl FnOnce(&str) -> NativeOp,
        decode: Decoder<T>,
    ) -> ResponseFuture<T> {
        match path::checked(path, sequential) {
            Ok(p) => self
                .shared
                .request_with_data(build(p), data, kind, None, decode),
            Err(e) => ResponseFuture::ready(Err(Error::InvalidPath(e))),
        }
    }

    /// Dispatches a read request with an associated one-shot watch token.
    ///
    /// Pre-registers the watcher sink before enqueuing the request frame to prevent race
    /// conditions. If the server response indicates success, the watcher is armed. On protocol
    /// errors, the sink is cleaned up.
    fn watched<B: 'static, T: 'static>(
        &self,
        path: &impl PathArg,
        kind: OpKind,
        watcher: WatcherKind,
        build: impl FnOnce(&str, WatchId) -> NativeOp,
        base: fn(RawResult) -> Result<B, Error>,
        join: fn(B, OneshotWatcher) -> T,
    ) -> ResponseFuture<T> {
        let p = match path::checked(path, false) {
            Ok(p) => p,
            Err(e) => return ResponseFuture::ready(Err(Error::InvalidPath(e))),
        };
        let reg = self
            .shared
            .register_oneshot(ZPath::new_unchecked(p).to_buf(), watcher);
        let id = reg.id();
        self.shared.request(
            build(p, id),
            kind,
            Some(id),
            decode::with_oneshot(base, reg, join),
        )
    }

    // ---- CRUD ----

    /// Creates a new znode at the specified path.
    ///
    /// Paths for sequential modes are validated using sequential naming rules. Upon success,
    /// returns the server-assigned path (with any chroot prefix stripped) and the node's initial
    /// metadata stat. Opcode selection transparently matches the requested [`CreateMode`].
    pub fn create(
        &self,
        path: impl PathArg,
        data: &[u8],
        mode: CreateMode,
        acl: &[Acl],
    ) -> ResponseFuture<(ZPathBuf, Stat)> {
        let sequential = mode.is_sequential();
        self.request_data_at(
            &path,
            sequential,
            OpKind::Create,
            Some(data),
            |p| ops::create(p, &mode, acl),
            decode::boxed(decode::create),
        )
    }

    /// Deletes the znode located at `path`, optionally conditioned on matching an expected
    /// version.
    pub fn delete(&self, path: impl PathArg, version: impl VersionArg) -> ResponseFuture<()> {
        let v = wire_version(&version);
        self.request_at(
            &path,
            false,
            OpKind::Delete,
            |p| ops::delete(p, v),
            decode::boxed(decode::void),
        )
    }

    /// Checks whether a znode exists at `path`, returning `Ok(None)` if the node is absent.
    pub fn exists(&self, path: impl PathArg) -> ResponseFuture<Option<Stat>> {
        self.request_at(
            &path,
            false,
            OpKind::Exists,
            |p| ops::exists(p, None),
            decode::boxed(decode::exists),
        )
    }

    /// Retrieves the binary data and metadata stat of the znode at `path`, mapping null payloads
    /// to empty vectors.
    pub fn get_data(&self, path: impl PathArg) -> ResponseFuture<(Vec<u8>, Stat)> {
        self.request_at(
            &path,
            false,
            OpKind::GetData,
            |p| ops::get_data(p, None),
            decode::boxed(decode::get_data),
        )
    }

    /// Retrieves the binary data and metadata stat of the znode at `path`, returning `None` for
    /// null payloads.
    pub fn get_data_opt(&self, path: impl PathArg) -> ResponseFuture<(Option<Vec<u8>>, Stat)> {
        self.request_at(
            &path,
            false,
            OpKind::GetData,
            |p| ops::get_data(p, None),
            decode::boxed(decode::get_data_opt),
        )
    }

    /// Updates the binary content of the znode at `path`, optionally checking expected version.
    pub fn set_data(
        &self,
        path: impl PathArg,
        data: &[u8],
        version: impl VersionArg,
    ) -> ResponseFuture<Stat> {
        let v = wire_version(&version);
        self.request_data_at(
            &path,
            false,
            OpKind::SetData,
            Some(data),
            |p| ops::set_data(p, v),
            decode::boxed(decode::stat),
        )
    }

    /// Queries the child node names of the specified znode, discarding the stat record.
    pub fn get_children(&self, path: impl PathArg) -> ResponseFuture<Vec<String>> {
        self.request_at(
            &path,
            false,
            OpKind::GetChildren,
            |p| ops::get_children(p, None, true),
            decode::boxed(decode::children),
        )
    }

    /// Queries both child node names and current metadata stat for the znode located at `path`.
    pub fn get_children_with_stat(
        &self,
        path: impl PathArg,
    ) -> ResponseFuture<(Vec<String>, Stat)> {
        self.request_at(
            &path,
            false,
            OpKind::GetChildren,
            |p| ops::get_children(p, None, true),
            decode::boxed(decode::children2),
        )
    }

    /// Recursively counts all descendant nodes rooted beneath `path` (ZooKeeper 3.6+).
    pub fn get_all_children_number(&self, path: impl PathArg) -> ResponseFuture<u32> {
        self.request_at(
            &path,
            false,
            OpKind::GetAllChildrenNumber,
            ops::get_all_children_number,
            decode::boxed(decode::children_count),
        )
    }

    /// Enumerates all active ephemeral znodes created by this session whose paths match the given
    /// prefix (ZooKeeper 3.6+).
    pub fn get_ephemerals(&self, prefix: impl PathArg) -> ResponseFuture<Vec<ZPathBuf>> {
        self.request_at(
            &prefix,
            false,
            OpKind::GetEphemerals,
            ops::get_ephemerals,
            decode::boxed(decode::ephemerals),
        )
    }

    /// Enumerates all active ephemeral znodes owned by the current session across the entire tree.
    pub fn get_ephemerals_all(&self) -> ResponseFuture<Vec<ZPathBuf>> {
        self.get_ephemerals("/")
    }

    // ---- One-shot Watchers ----

    /// Checks znode existence while registering a one-shot watcher triggered by node creation,
    /// data change, or deletion.
    pub fn exists_watched(
        &self,
        path: impl PathArg,
    ) -> ResponseFuture<(Option<Stat>, OneshotWatcher)> {
        self.watched(
            &path,
            OpKind::Exists,
            WatcherKind::Data,
            |p, w| ops::exists(p, Some(w)),
            decode::exists,
            |s, w| (s, w),
        )
    }

    /// Retrieves znode payload and metadata stat while registering a one-shot data watcher
    /// triggered by mutations or deletion.
    pub fn get_data_watched(
        &self,
        path: impl PathArg,
    ) -> ResponseFuture<(Vec<u8>, Stat, OneshotWatcher)> {
        self.watched(
            &path,
            OpKind::GetData,
            WatcherKind::Data,
            |p, w| ops::get_data(p, Some(w)),
            decode::get_data,
            |(d, s), w| (d, s, w),
        )
    }

    /// Queries child names and metadata stat while registering a one-shot child watcher
    /// triggered by child additions or removals.
    pub fn get_children_watched(
        &self,
        path: impl PathArg,
    ) -> ResponseFuture<(Vec<String>, Stat, OneshotWatcher)> {
        self.watched(
            &path,
            OpKind::GetChildren,
            WatcherKind::Children,
            |p, w| ops::get_children(p, Some(w), true),
            decode::children2,
            |(c, s), w| (c, s, w),
        )
    }

    // ---- Persistent Watchers ----

    /// Registers a persistent or persistent-recursive watcher on `path` (ZooKeeper 3.6+).
    pub fn add_watch(
        &self,
        path: impl PathArg,
        mode: AddWatchMode,
    ) -> ResponseFuture<PersistentWatcher> {
        let p = match path::checked(&path, false) {
            Ok(p) => p,
            Err(e) => return ResponseFuture::ready(Err(Error::InvalidPath(e))),
        };
        let reg = self
            .shared
            .register_persistent(ZPath::new_unchecked(p).to_buf(), mode);
        let id = reg.id();
        self.shared.request(
            ops::add_watch(p, mode.wire(), id),
            OpKind::AddWatch,
            Some(id),
            decode::with_persistent(reg),
        )
    }

    /// Deregisters all active watchers of the specified kind on `path`.
    ///
    /// When `local_only` is true, watchers are removed locally without querying the ensemble,
    /// terminating with [`crate::error::WatchError::Removed`].
    pub fn remove_watches(
        &self,
        path: impl PathArg,
        kind: WatcherKind,
        local_only: bool,
    ) -> ResponseFuture<()> {
        let wtype = kind.wire();
        self.request_at(
            &path,
            false,
            OpKind::RemoveWatches,
            |p| ops::remove_watches(p, wtype, local_only),
            decode::boxed(decode::void),
        )
    }

    // ---- Access Control Lists (ACL) ----

    /// Retrieves the Access Control List policies and metadata stat of the znode at `path`.
    pub fn get_acl(&self, path: impl PathArg) -> ResponseFuture<(Vec<Acl>, Stat)> {
        self.request_at(
            &path,
            false,
            OpKind::GetAcl,
            ops::get_acl,
            decode::boxed(decode::acl),
        )
    }

    /// Updates the Access Control List policies of the znode at `path`, conditioned on the
    /// expected ACL version.
    pub fn set_acl(
        &self,
        path: impl PathArg,
        acl: &[Acl],
        aversion: impl VersionArg,
    ) -> ResponseFuture<Stat> {
        let v = wire_version(&aversion);
        self.request_at(
            &path,
            false,
            OpKind::SetAcl,
            |p| ops::set_acl(p, acl, v),
            decode::boxed(decode::stat),
        )
    }

    // ---- Consistency Barriers ----

    /// Synchronizes client view of `path` with the ensemble leader state.
    pub fn sync(&self, path: impl PathArg) -> ResponseFuture<()> {
        self.request_at(
            &path,
            false,
            OpKind::Sync,
            ops::sync,
            decode::boxed(decode::void),
        )
    }

    /// Dispatches a `sync` request for the root path `/` to confirm replayed authentication
    /// following reconnection.
    ///
    /// Because a server processes requests on a single connection in order, a successful reply to
    /// this request confirms that the server has processed the authentication requests replayed
    /// earlier on the same connection. A read-only server replies to the `sync` with `NOTREADONLY`
    /// only after processing the replayed authentication, which likewise confirms it; the returned
    /// future resolves to `Ok(())` in both cases.
    pub(crate) fn auth_barrier(&self) -> ResponseFuture<()> {
        self.request_at(
            &"/",
            false,
            OpKind::Sync,
            ops::sync,
            decode::boxed(decode::auth_barrier),
        )
    }

    // ---- Atomic Transactions ----

    /// Executes a batch of mutating operations as an atomic transaction.
    pub fn multi(&self, txn: &MultiWriter<'_>) -> ResponseFuture<MultiResults, MultiError> {
        let writer_id = txn.writer_id();
        // Sub-operations are borrowed directly from the writer, encoding straight into the wire
        // frame without copying.
        let ops = txn.to_ops();
        self.shared.request_by(
            Op::Multi { ops: Vec::new() },
            OpKind::Multi,
            None,
            Box::new(move |r| multi::decode_multi(r, writer_id)),
            |d, req| d.submit_multi_ref(req, &ops),
        )
    }

    /// Executes a read only batch of queries in a single round trip (ZooKeeper 3.6+).
    pub fn multi_read(
        &self,
        ops: &MultiReader<'_>,
    ) -> ResponseFuture<Vec<Result<ReadResult, Error>>> {
        self.shared.request(
            Op::MultiRead { ops: ops.to_ops() },
            OpKind::MultiRead,
            None,
            decode::boxed(multi::decode_multi_read),
        )
    }

    // ---- Cluster Reconfiguration ----

    /// Queries active ensemble membership configuration from `/zookeeper/config` (unaffected
    /// by chroot prefixes).
    pub fn get_config(&self) -> ResponseFuture<(EnsembleConfig, Stat)> {
        self.shared.request(
            ops::get_config(None),
            OpKind::GetConfig,
            None,
            decode::boxed(decode::config),
        )
    }

    /// Queries ensemble configuration while establishing a data watch monitoring dynamic
    /// membership updates.
    pub fn get_config_watched(&self) -> ResponseFuture<(EnsembleConfig, Stat, OneshotWatcher)> {
        let reg = self.shared.register_oneshot(
            ZPath::new_unchecked(CONFIG_NODE).to_buf(),
            WatcherKind::Data,
        );
        let id = reg.id();
        self.shared.request(
            ops::get_config(Some(id)),
            OpKind::GetConfig,
            Some(id),
            decode::with_oneshot(decode::config, reg, |(c, s), w| (c, s, w)),
        )
    }

    /// Reconfigures cluster ensemble membership dynamically, optionally conditioned on expected
    /// configuration version.
    pub fn reconfig(
        &self,
        change: &Reconfig,
        expected: Option<i64>,
    ) -> ResponseFuture<(EnsembleConfig, Stat)> {
        let (joining, leaving, members) = change.wire();
        self.shared.request(
            ops::reconfig(joining, leaving, members, expected.unwrap_or(-1)),
            OpKind::Reconfig,
            None,
            decode::boxed(decode::config),
        )
    }

    /// Dynamically updates the local server connection list without restarting the client
    /// session (ZOOKEEPER-1355).
    ///
    /// # Errors
    ///
    /// Returns [`Error::InvalidArgument`] if the connection string fails parsing or includes an
    /// incompatible chroot specification. If the client has already entered a disconnected state,
    /// returns the corresponding error.
    pub fn update_servers(&self, connect_string: &str) -> Result<(), Error> {
        let hosts = connect_string::parse_update(connect_string)?;
        if let Some(e) = self.shared.terminal_error() {
            return Err(e);
        }
        if self.shared.driver.set_servers(hosts) == 0 {
            // The driver terminated during the window between state check and invocation.
            return Err(self.shared.terminal_error().unwrap_or(Error::Closing));
        }
        Ok(())
    }

    // ---- Authentication ----

    /// Appends session authentication credentials (`opcode::ADD_AUTH`).
    ///
    /// Credentials are automatically re-transmitted upon successful connection re-establishment.
    /// If the server rejects the credential packet, the session transitions to an unrecoverable
    /// [`Error::AuthFailed`] state.
    pub fn add_auth(&self, scheme: &str, data: impl Into<Vec<u8>>) -> ResponseFuture<()> {
        let cred = SecretBytes::new(data.into());
        self.shared.add_auth(JString::from(scheme), cred)
    }

    /// Inspects the authenticated user identities and permissions associated with the current
    /// session (ZooKeeper 3.9+).
    pub fn who_am_i(&self) -> ResponseFuture<Vec<ClientInfo>> {
        self.shared.request(
            ops::who_am_i(),
            OpKind::WhoAmI,
            None,
            decode::boxed(decode::who_am_i),
        )
    }

    // ---- Session State and Lifecycle ----

    /// Returns a snapshot of the current connection and session state.
    #[must_use]
    pub fn state(&self) -> SessionInfo {
        self.shared.router.state.snapshot()
    }

    /// Subscribes to connection and session lifecycle transitions via an asynchronous watcher
    /// stream.
    #[must_use]
    pub fn state_watcher(&self) -> StateWatcher {
        StateWatcher::new(Arc::clone(&self.shared.router.state))
    }

    /// Retrieves the assigned session identifier and credential password token.
    /// Prior to initial connection establishment, returns zero or pre-configured credentials.
    #[must_use]
    pub fn session(&self) -> (SessionId, SessionPassword) {
        let id = self.shared.driver.client_id();
        (
            SessionId(id.session_id),
            SessionPassword::from_bytes(id.passwd),
        )
    }

    /// Returns the negotiated session heartbeat timeout duration established during connection
    /// negotiation.
    #[must_use]
    pub fn negotiated_timeout(&self) -> Option<Duration> {
        if self.state().epoch == 0 {
            return None;
        }
        let ms = self.shared.read_conn(|c| c.recv_timeout())?;
        u64::try_from(ms).ok().map(Duration::from_millis)
    }

    /// Returns the highest transaction identifier (`zxid`) observed by this client connection.
    #[must_use]
    pub fn last_zxid(&self) -> Zxid {
        Zxid(self.shared.read_conn(|c| c.last_zxid()).unwrap_or(0))
    }

    /// Returns the remote socket address of the ensemble node currently serving this client
    /// connection.
    #[must_use]
    pub fn connected_server(&self) -> Option<SocketAddr> {
        if !self.state().state.is_connected() {
            return None;
        }
        self.shared.read_conn(|c| c.current_server()).flatten()
    }

    /// Compiles diagnostic counters and current connectivity telemetry for this client.
    #[must_use]
    pub fn stats(&self) -> ClientStats {
        ClientStats {
            pending: self.shared.pending_len().unwrap_or(0),
            sent: 0,
            received: 0,
            reconnects: self.state().epoch.saturating_sub(1),
            last_zxid: self.last_zxid(),
            negotiated_timeout: self.negotiated_timeout(),
            server: self.connected_server(),
        }
    }

    /// Returns a future that resolves when in-flight request backlog drops below half of
    /// `max_pending_requests`. Resolves immediately if backpressure bounding is disabled.
    pub fn ready(&self) -> ReadyFuture {
        ReadyFuture::new(Arc::downgrade(&self.shared))
    }

    /// Initiates graceful session termination.
    ///
    /// New submissions are immediately rejected with [`Error::Closing`], queued requests are
    /// flushed to the wire, and a final `CloseSession` packet is dispatched to the ensemble.
    /// Subsequent calls are idempotent.
    pub fn close(&self) -> CloseFuture {
        self.shared.driver.close();
        CloseFuture::new(self.shared.driver.close_wait(&self.shared.router))
    }

    /// Detaches the client handle without closing the remote session on the ensemble.
    ///
    /// In-flight requests fail with [`Error::Closing`] and registered watchers receive terminal
    /// close notifications. The returned session identifier and credentials can be supplied to a
    /// subsequent client instance to resume the session.
    #[must_use = "the session lives on; use the credentials or close it"]
    pub fn detach(self) -> (SessionId, SessionPassword) {
        let session = self.session();
        self.shared.driver.detach();
        session
    }
}
