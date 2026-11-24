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

//! In-memory ZooKeeper state machine modeling the hierarchical data tree, session lifecycles,
//! watch tables, and protocol request processing.
//!
//! Decoupled from socket I/O, this module processes decoded Jute protocol frames and enqueues
//! serialized replies and watch notifications onto per-connection output queues. State transition
//! semantics follow Apache ZooKeeper's `PrepRequestProcessor`, `FinalRequestProcessor`,
//! `DataTree`, and `WatchManager`. Ordered search trees (`BTreeMap` and `BTreeSet`) replace Java's
//! hash-ordered collections so child listings, watch delivery, and wire transcripts remain
//! deterministic across runs.

use std::collections::{BTreeMap, BTreeSet};
use std::sync::Arc;
use std::time::{Duration, Instant};

use zk_jute::data::{ACL, ClientInfo, Id, Stat, StatPersisted};
use zk_jute::proto::{
    AddWatchRequest, AuthPacket, CheckVersionRequest, CheckWatchesRequest, ConnectResponse,
    Create2Response, CreateRequest, CreateResponse, CreateTTLRequest, DeleteRequest, ErrorResponse,
    ExistsRequest, ExistsResponse, GetACLRequest, GetACLResponse, GetAllChildrenNumberRequest,
    GetAllChildrenNumberResponse, GetChildren2Response, GetChildrenRequest, GetChildrenResponse,
    GetDataRequest, GetDataResponse, GetEphemeralsRequest, GetEphemeralsResponse, MultiHeader,
    ReconfigRequest, ReplyHeader, SetACLRequest, SetACLResponse, SetDataRequest, SetDataResponse,
    SetWatches, SetWatches2, SyncRequest, SyncResponse, WatcherEvent, WhoAmIResponse,
};
use zk_jute::rc::{
    ZAUTHFAILED, ZBADARGUMENTS, ZBADVERSION, ZINVALIDACL, ZMARSHALLINGERROR, ZNEWCONFIGNOQUORUM,
    ZNOAUTH, ZNOCHILDRENFOREPHEMERALS, ZNODEEXISTS, ZNONODE, ZNOTEMPTY, ZNOTREADONLY, ZNOWATCHER,
    ZRECONFIGDISABLED, ZRUNTIMEINCONSISTENCY, ZUNIMPLEMENTED,
};
use zk_jute::{JBuffer, JString, JVec, Reader, Record, begin_frame, finish_frame, opcode};

use crate::config::{AuthPolicy, Chunking, ClusterConfig};
use crate::link::{ConnLink, Out, fixed_chunks};
use crate::rng::SplitMix64;
use crate::transcript::{ConnectInfo, widen};
use crate::{digest, event, path, perms};

/// Sentinel `ephemeral_owner` value marking a container znode (`i64::MIN`).
const CONTAINER_OWNER: i64 = i64::MIN;
/// High-byte mask identifying extended `ephemeral_owner` encodings such as TTL znodes.
const EXTENDED_MASK: u64 = 0xFF00_0000_0000_0000;
/// Maximum TTL duration in milliseconds (`0x00FF_FFFF_FFFF`).
const MAX_TTL: i64 = 0x00FF_FFFF_FFFF;

/// One-shot watch bitflag.
const W_STANDARD: u8 = 1;
/// Persistent watch bitflag retained across notifications until explicitly removed.
const W_PERSISTENT: u8 = 2;
/// Persistent recursive watch bitflag monitoring an entire subtree.
const W_RECURSIVE: u8 = 4;

/// System znode paths that cannot be deleted by client requests.
const SPECIAL: [&str; 4] = ["/", "/zookeeper", "/zookeeper/quota", "/zookeeper/config"];

/// Path of the cluster configuration znode.
pub(crate) const CONFIG_NODE: &str = "/zookeeper/config";

type Res<T> = Result<T, i32>;

/// Returns whether `owner` encodes a TTL znode expiration window.
fn is_ttl_owner(owner: i64) -> bool {
    owner != CONTAINER_OWNER && (owner as u64) & EXTENDED_MASK == EXTENDED_MASK
}

/// Returns whether `owner` is a client session ID rather than 0, a container, or a TTL encoding.
fn is_session_owner(owner: i64) -> bool {
    owner != 0 && owner != CONTAINER_OWNER && !is_ttl_owner(owner)
}

/// Extracts the millisecond TTL duration from an extended `ephemeral_owner` value.
fn ttl_of(owner: i64) -> i64 {
    owner & MAX_TTL
}

/// Returns a single `world:anyone` ACL entry with the given permission mask.
fn world_acl(perm: i32) -> Vec<ACL> {
    vec![ACL {
        perms: perm,
        id: Id {
            scheme: "world".into(),
            id: "anyone".into(),
        },
    }]
}

/// Single znode entry in the in-memory data tree.
#[derive(Debug, Clone)]
struct Node {
    data: Vec<u8>,
    null_data: bool,
    acl: Vec<ACL>,
    st: StatPersisted,
    children: BTreeSet<String>,
    modified_at: Instant,
}

impl Node {
    fn new(acl: Vec<ACL>, now: Instant) -> Self {
        Self {
            data: Vec::new(),
            null_data: false,
            acl,
            st: StatPersisted::default(),
            children: BTreeSet::new(),
            modified_at: now,
        }
    }

    fn stat(&self) -> Stat {
        Stat {
            czxid: self.st.czxid,
            mzxid: self.st.mzxid,
            ctime: self.st.ctime,
            mtime: self.st.mtime,
            version: self.st.version,
            cversion: self.st.cversion,
            aversion: self.st.aversion,
            ephemeral_owner: self.st.ephemeral_owner,
            data_length: i32::try_from(self.data.len()).unwrap_or(i32::MAX),
            num_children: i32::try_from(self.children.len()).unwrap_or(i32::MAX),
            pzxid: self.st.pzxid,
        }
    }

    fn data_buf(&self) -> JBuffer {
        if self.null_data {
            JBuffer::Null
        } else {
            JBuffer::from(self.data.clone())
        }
    }
}

/// Internal session record tracking credentials, negotiated timeout, and last activity.
#[derive(Debug, Clone)]
struct Session {
    passwd: [u8; 16],
    timeout_ms: i32,
    last_seen: Instant,
    conn: Option<u64>,
}

/// Snapshot of a client session exposed for test inspection.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionInfo {
    /// Unique 64-bit session ID assigned during the initial handshake.
    pub id: i64,
    /// 16-byte session password used to re-attach the session on reconnect.
    pub passwd: [u8; 16],
    /// Negotiated session timeout in milliseconds.
    pub timeout_ms: i32,
    /// Listener slot index hosting the session's active connection, if connected.
    pub server: Option<usize>,
    /// Connection ID currently attached to this session, if connected.
    pub conn: Option<u64>,
    /// Sorted paths of ephemeral znodes owned by this session.
    pub ephemerals: Vec<String>,
}

/// Read-only snapshot of a znode's payload, stat metadata, ACLs, and child names.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NodeInfo {
    /// Znode payload bytes, or `None` if the znode holds a null Jute buffer.
    pub data: Option<Vec<u8>>,
    /// Znode stat metadata.
    pub stat: Stat,
    /// Access control list on the znode.
    pub acl: Vec<ACL>,
    /// Lexicographically sorted immediate child names.
    pub children: Vec<String>,
}

/// State tracked for an open client connection.
#[derive(Debug)]
pub(crate) struct ConnEntry {
    pub(crate) server: usize,
    pub(crate) link: Arc<ConnLink>,
    pub(crate) session: Option<i64>,
    auth: Vec<Id>,
    pub(crate) chunking: Chunking,
}

/// Decoded ZooKeeper creation mode flags (`CreateMode.fromFlag`).
#[derive(Debug, Clone, Copy)]
struct Mode {
    ephemeral: bool,
    sequential: bool,
    container: bool,
    ttl: bool,
}

fn mode_from_flag(flags: i32) -> Option<Mode> {
    let m = |ephemeral, sequential, container, ttl| Mode {
        ephemeral,
        sequential,
        container,
        ttl,
    };
    Some(match flags {
        0 => m(false, false, false, false),
        1 => m(true, false, false, false),
        2 => m(false, true, false, false),
        3 => m(true, true, false, false),
        4 => m(false, false, true, false),
        5 => m(false, false, false, true),
        6 => m(false, true, false, true),
        _ => return None,
    })
}

/// Common parameters extracted from `CREATE`, `CREATE2`, `CREATE_TTL`, and `CREATE_CONTAINER`.
#[derive(Debug, Clone)]
struct CreateArgs {
    path: JString,
    data: JBuffer,
    acl: JVec<ACL>,
    flags: i32,
    ttl: i64,
}

impl From<CreateRequest> for CreateArgs {
    fn from(q: CreateRequest) -> Self {
        Self {
            path: q.path,
            data: q.data,
            acl: q.acl,
            flags: q.flags,
            ttl: -1,
        }
    }
}

impl From<CreateTTLRequest> for CreateArgs {
    fn from(q: CreateTTLRequest) -> Self {
        Self {
            path: q.path,
            data: q.data,
            acl: q.acl,
            flags: q.flags,
            ttl: q.ttl,
        }
    }
}

/// Sub-operation within a `multi` or `multiRead` request batch.
#[derive(Debug, Clone)]
enum MultiOp {
    Create(i32, CreateArgs),
    Delete(DeleteRequest),
    SetData(SetDataRequest),
    Check(CheckVersionRequest),
    GetData(GetDataRequest),
    GetChildren(GetChildrenRequest),
}

/// Opcode and encoded response body produced by a successful `multi` sub-operation.
type OpOk = (i32, Vec<u8>);

/// Pending watch event queued by a state mutation.
///
/// Retains the znode's ACL at the time of the mutation so delivery checks `READ` permission.
#[derive(Debug, Clone)]
enum Trig {
    Created(String, Vec<ACL>),
    Deleted(String),
    Changed(String, Vec<ACL>),
    Children(String, Vec<ACL>),
}

/// Watch table mapping znode paths to per-connection watch mode bitflags.
#[derive(Debug, Clone, Default)]
struct WatchTable {
    map: BTreeMap<String, BTreeMap<u64, u8>>,
}

impl WatchTable {
    fn add(&mut self, path: &str, conn: u64, bits: u8) {
        let e = self
            .map
            .entry(path.to_owned())
            .or_default()
            .entry(conn)
            .or_insert(0);
        *e |= bits;
    }

    fn has(&self, path: &str, conn: u64, mode: Option<u8>) -> bool {
        self.map
            .get(path)
            .and_then(|m| m.get(&conn))
            .is_some_and(|b| mode.map_or(*b != 0, |m| *b & m != 0))
    }

    /// Removes watch bits matching `mode` (or all bits when `None`), returning whether any matched.
    fn remove(&mut self, path: &str, conn: u64, mode: Option<u8>) -> bool {
        let Some(m) = self.map.get_mut(path) else {
            return false;
        };
        let Some(bits) = m.get_mut(&conn) else {
            return false;
        };
        let removed = match mode {
            Some(md) => {
                let had = *bits & md != 0;
                *bits &= !md;
                had
            }
            None => {
                let had = *bits != 0;
                *bits = 0;
                had
            }
        };
        if *bits == 0 {
            m.remove(&conn);
        }
        if m.is_empty() {
            self.map.remove(path);
        }
        removed
    }

    /// Collects connections watching `p` and clears one-shot watches (`WatchManager.triggerWatch`).
    ///
    /// One-shot watches on `p` fire and are removed; persistent watches on `p` and recursive
    /// watches on `p` or its ancestors fire and remain registered.
    fn trigger(&mut self, p: &str) -> BTreeSet<u64> {
        let mut out = BTreeSet::new();
        for (i, local) in path::self_and_ancestors(p).into_iter().enumerate() {
            let Some(m) = self.map.get_mut(local) else {
                continue;
            };
            if i == 0 {
                for (c, b) in m.iter_mut() {
                    out.insert(*c);
                    *b &= !W_STANDARD;
                }
                m.retain(|_, b| *b != 0);
            } else {
                for (c, b) in m.iter() {
                    if *b & W_RECURSIVE != 0 {
                        out.insert(*c);
                    }
                }
            }
            if m.is_empty() {
                self.map.remove(local);
            }
        }
        out
    }

    fn drop_conn(&mut self, conn: u64) {
        for m in self.map.values_mut() {
            m.remove(&conn);
        }
        self.map.retain(|_, m| !m.is_empty());
    }

    fn count(&self) -> usize {
        self.map.values().map(BTreeMap::len).sum()
    }
}

/// Unencoded protocol reply containing the header fields and optional serialized body.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Reply {
    pub(crate) xid: i32,
    pub(crate) zxid: i64,
    pub(crate) err: i32,
    pub(crate) body: Option<Vec<u8>>,
}

impl Reply {
    /// Encodes the reply into a 4-byte length-prefixed Jute frame.
    pub(crate) fn encode(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let start = begin_frame(&mut out);
        ReplyHeader {
            xid: self.xid,
            zxid: self.zxid,
            err: self.err,
        }
        .encode(&mut out);
        if let Some(b) = &self.body {
            out.extend_from_slice(b);
        }
        finish_frame(&mut out, start);
        out
    }
}

/// Result of processing a single client request frame in the model.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Handled {
    pub(crate) reply: Option<Reply>,
    pub(crate) close: bool,
}

/// Outcome of processing a client `ConnectRequest` handshake.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum ConnectOutcome {
    /// Send `resp`, then close the connection if `close` is `true`.
    Respond { resp: ConnectResponse, close: bool },
    /// Close the connection immediately without sending a handshake response.
    Close,
}

/// Encodes a length-prefixed `WatcherEvent` notification frame.
pub(crate) fn event_frame(event_type: i32, state: i32, p: &str, zxid: i64) -> Vec<u8> {
    let mut out = Vec::new();
    let start = begin_frame(&mut out);
    ReplyHeader {
        xid: zk_jute::xid::WATCHER_EVENT,
        zxid,
        err: 0,
    }
    .encode(&mut out);
    WatcherEvent {
        r#type: event_type,
        state,
        path: JString::from(p),
    }
    .encode(&mut out);
    finish_frame(&mut out, start);
    out
}

fn dec<T: Record>(body: &[u8]) -> Res<T> {
    T::from_bytes(body).map_err(|_| ZMARSHALLINGERROR)
}

/// Decodes a create request payload (`CREATE`, `CREATE2`, `CREATE_CONTAINER`, or `CREATE_TTL`).
fn dec_create(op: i32, body: &[u8]) -> Res<CreateArgs> {
    if op == opcode::CREATE_TTL {
        dec::<CreateTTLRequest>(body).map(CreateArgs::from)
    } else {
        dec::<CreateRequest>(body).map(CreateArgs::from)
    }
}

/// Extracts the UTF-8 string from a `JString`, returning `ZBADARGUMENTS` if null.
fn path_of(s: &JString) -> Res<String> {
    s.as_str().map(str::to_owned).ok_or(ZBADARGUMENTS)
}

/// Checks `expected` against `current` (`-1` matches any version) and returns `current + 1`.
fn check_version(current: i32, expected: i32) -> Res<i32> {
    if expected != -1 && expected != current {
        return Err(ZBADVERSION);
    }
    Ok(current.wrapping_add(1))
}

/// Splits `s` on `':'` and strips trailing empty segments to match Java's `String.split(":")`.
fn java_split_colon(s: &str) -> Vec<&str> {
    let mut parts: Vec<&str> = s.split(':').collect();
    while parts.last().is_some_and(|p| p.is_empty()) {
        parts.pop();
    }
    parts
}

/// Parses an IPv4 or IPv6 address with an optional `/prefix` length (such as `10.0.0.0/8`).
fn parse_ip(expr: &str) -> Option<(std::net::IpAddr, u32)> {
    let (addr, bits) = match expr.split_once('/') {
        Some((a, b)) => (a, Some(b.parse::<u32>().ok()?)),
        None => (expr, None),
    };
    let ip: std::net::IpAddr = addr.parse().ok()?;
    let full = if ip.is_ipv4() { 32 } else { 128 };
    let bits = bits.unwrap_or(full);
    if bits > full {
        return None;
    }
    Some((ip, bits))
}

/// Returns whether `client` falls within the IP address or CIDR subnet `expr`.
fn ip_matches(client: &str, expr: &str) -> bool {
    let (Some((c, _)), Some((e, bits))) = (parse_ip(client), parse_ip(expr)) else {
        return client == expr;
    };
    let to_u128 = |ip: std::net::IpAddr| match ip {
        std::net::IpAddr::V4(v) => u128::from(u32::from(v)),
        std::net::IpAddr::V6(v) => u128::from(v),
    };
    if c.is_ipv4() != e.is_ipv4() {
        return false;
    }
    let width: u32 = if c.is_ipv4() { 32 } else { 128 };
    let shift = width.saturating_sub(bits);
    let (cv, ev) = (to_u128(c), to_u128(e));
    if shift >= 128 {
        return true;
    }
    cv.wrapping_shr(shift) == ev.wrapping_shr(shift)
}

/// In-memory ZooKeeper cluster state machine shared across all listener endpoints.
///
/// Owns the znode data tree, active sessions, watch tables, and transaction counter (`zxid`).
#[derive(Debug)]
pub(crate) struct Model {
    pub(crate) cfg: ClusterConfig,
    tree: BTreeMap<String, Node>,
    zxid: i64,
    sessions: BTreeMap<i64, Session>,
    next_session: i64,
    pub(crate) conns: BTreeMap<u64, ConnEntry>,
    data_w: WatchTable,
    child_w: WatchTable,
    pub(crate) rng: SplitMix64,
    last_reap: Instant,
}

impl Model {
    /// Creates a model initialized with the built-in system znodes (`/`, `/zookeeper`,
    /// `/zookeeper/quota`, and `/zookeeper/config`).
    pub(crate) fn new(cfg: ClusterConfig) -> Self {
        let now = Instant::now();
        let mut rng = SplitMix64::new(cfg.seed);
        let low = rng.next_u64() & 0x00FF_FFFF_FFFF_0000;
        let next_session = (u64::from(cfg.server_id).wrapping_shl(56) | low) as i64;
        let mut m = Self {
            cfg,
            tree: BTreeMap::new(),
            zxid: 0,
            sessions: BTreeMap::new(),
            next_session,
            conns: BTreeMap::new(),
            data_w: WatchTable::default(),
            child_w: WatchTable::default(),
            rng,
            last_reap: now,
        };
        m.reset_tree(now);
        m
    }

    fn reset_tree(&mut self, now: Instant) {
        self.tree.clear();
        let mut root = Node::new(world_acl(perms::ALL), now);
        root.children.insert("zookeeper".into());
        let mut zk = Node::new(world_acl(perms::ALL), now);
        zk.children.insert("quota".into());
        zk.children.insert("config".into());
        self.tree.insert("/".into(), root);
        self.tree.insert("/zookeeper".into(), zk);
        self.tree.insert(
            "/zookeeper/quota".into(),
            Node::new(world_acl(perms::ALL), now),
        );
        self.tree
            .insert(CONFIG_NODE.into(), Node::new(world_acl(perms::READ), now));
    }

    /// Closes open connections, clears sessions and watches, resets the data tree, and sets `zxid`
    /// to zero.
    pub(crate) fn reset(&mut self) {
        let now = Instant::now();
        for c in self.conns.values() {
            c.link.kill();
        }
        self.reset_tree(now);
        self.sessions.clear();
        self.zxid = 0;
        self.data_w = WatchTable::default();
        self.child_w = WatchTable::default();
    }

    /// Returns the last committed transaction identifier (`zxid`).
    pub(crate) const fn zxid(&self) -> i64 {
        self.zxid
    }

    /// Sets the current transaction identifier (`zxid`).
    pub(crate) fn set_zxid(&mut self, z: i64) {
        self.zxid = z;
    }

    fn next_zxid(&mut self) -> i64 {
        self.zxid = self.zxid.wrapping_add(1);
        self.zxid
    }

    fn time(&self, z: i64) -> i64 {
        self.cfg.epoch_ms.wrapping_add(z)
    }

    // ---- Connection lifecycle and outbound frame dispatch ----

    /// Registers a newly accepted client connection with its peer IP identity and chunking mode.
    pub(crate) fn conn_opened(
        &mut self,
        conn: u64,
        server: usize,
        link: Arc<ConnLink>,
        peer_ip: String,
        chunking: Chunking,
    ) {
        let auth = vec![Id {
            scheme: "ip".into(),
            id: peer_ip.into(),
        }];
        self.conns.insert(
            conn,
            ConnEntry {
                server,
                link,
                session: None,
                auth,
                chunking,
            },
        );
    }

    /// Removes a closed connection and its watches while leaving its session intact until expiry.
    pub(crate) fn conn_closed(&mut self, conn: u64) {
        if let Some(c) = self.conns.remove(&conn) {
            if let Some(s) = c.session.and_then(|id| self.sessions.get_mut(&id)) {
                if s.conn == Some(conn) {
                    s.conn = None;
                }
            }
        }
        self.data_w.drop_conn(conn);
        self.child_w.drop_conn(conn);
    }

    /// Enqueues `data` on `conn`'s output queue segmented according to its [`Chunking`] policy.
    pub(crate) fn send(&mut self, conn: u64, data: Vec<u8>) {
        let Some(c) = self.conns.get(&conn) else {
            return;
        };
        let chunks = match c.chunking {
            Chunking::Whole => vec![data.len()],
            Chunking::Fixed(n) => fixed_chunks(data.len(), n),
            Chunking::Random { max } => {
                let mut out = Vec::new();
                let mut left = data.len();
                let max = u64::try_from(max.max(1)).unwrap_or(1);
                while left > 0 {
                    let n = usize::try_from(self.rng.below(max).saturating_add(1))
                        .unwrap_or(1)
                        .min(left);
                    out.push(n);
                    left = left.saturating_sub(n);
                }
                if out.is_empty() {
                    out.push(0);
                }
                out
            }
        };
        c.link.push(Out::Bytes { data, chunks });
    }

    /// Enqueues `data` on `conn`'s output queue with explicit write chunk sizes.
    pub(crate) fn send_chunks(&self, conn: u64, data: Vec<u8>, chunks: Vec<usize>) {
        if let Some(c) = self.conns.get(&conn) {
            c.link.push(Out::Bytes { data, chunks });
        }
    }

    /// Pushes an [`Out`] control item onto `conn`'s output queue.
    pub(crate) fn push(&self, conn: u64, item: Out) {
        if let Some(c) = self.conns.get(&conn) {
            c.link.push(item);
        }
    }

    /// Encodes and enqueues a watch notification frame on `conn`.
    pub(crate) fn notify(&mut self, conn: u64, event_type: i32, state: i32, p: &str, zxid: i64) {
        self.send(conn, event_frame(event_type, state, p, zxid));
    }

    /// Returns the session ID bound to `conn`, or 0 if not yet handshaken.
    pub(crate) fn session_of(&self, conn: u64) -> i64 {
        self.conns.get(&conn).and_then(|c| c.session).unwrap_or(0)
    }

    // ---- Session management and heartbeat tracking ----

    fn touch(&mut self, session: i64, now: Instant) {
        if let Some(s) = self.sessions.get_mut(&session) {
            s.last_seen = now;
        }
    }

    /// Refreshes `last_seen` on all active sessions when a server restarts with `keep_sessions`.
    pub(crate) fn touch_all(&mut self) {
        let now = Instant::now();
        for s in self.sessions.values_mut() {
            s.last_seen = now;
        }
    }

    fn passwd_for(&self, id: i64) -> [u8; 16] {
        let mut r = SplitMix64::new(self.cfg.seed ^ (id as u64));
        let mut pw = [0u8; 16];
        r.fill(&mut pw);
        pw
    }

    /// Processes a client `ConnectRequest` handshake (`ZooKeeperServer.processConnectRequest`).
    pub(crate) fn handle_connect(
        &mut self,
        conn: u64,
        info: &ConnectInfo,
        read_only_server: bool,
        force_invalid: bool,
    ) -> ConnectOutcome {
        if read_only_server && !info.read_only.unwrap_or(false) {
            return ConnectOutcome::Close;
        }
        if info.last_zxid_seen > self.zxid {
            return ConnectOutcome::Close;
        }
        let now = Instant::now();
        let timeout = info
            .time_out
            .max(self.cfg.min_session_timeout_ms)
            .min(self.cfg.max_session_timeout_ms);
        let invalid = ConnectOutcome::Respond {
            resp: ConnectResponse {
                protocol_version: 0,
                time_out: 0,
                session_id: 0,
                passwd: JBuffer::from(vec![0u8; 16]),
                read_only: read_only_server,
            },
            close: true,
        };
        if force_invalid {
            return invalid;
        }
        let id = if info.session_id == 0 {
            let _createsession_zxid = self.next_zxid();
            let id = self.next_session;
            self.next_session = self.next_session.wrapping_add(1);
            let passwd = self.passwd_for(id);
            self.sessions.insert(
                id,
                Session {
                    passwd,
                    timeout_ms: timeout,
                    last_seen: now,
                    conn: Some(conn),
                },
            );
            id
        } else {
            let id = info.session_id;
            let Some(s) = self
                .sessions
                .get_mut(&id)
                .filter(|s| info.passwd.as_deref() == Some(&s.passwd[..]))
            else {
                return invalid;
            };
            s.timeout_ms = timeout;
            s.last_seen = now;
            let old = s.conn.replace(conn);
            self.detach(old.filter(|o| *o != conn));
            id
        };
        if let Some(c) = self.conns.get_mut(&conn) {
            c.session = Some(id);
        }
        ConnectOutcome::Respond {
            resp: ConnectResponse {
                protocol_version: 0,
                time_out: timeout,
                session_id: id,
                passwd: JBuffer::from(self.passwd_for(id).to_vec()),
                read_only: read_only_server,
            },
            close: false,
        }
    }

    /// Kills `conn`'s transport link and detaches it from its session.
    fn detach(&mut self, conn: Option<u64>) {
        if let Some(c) = conn.and_then(|c| self.conns.get_mut(&c)) {
            c.link.kill();
            c.session = None;
        }
    }

    /// Removes a session and its ephemeral znodes, firing deletion watches.
    fn end_session(&mut self, id: i64, z: i64) {
        let mut trig = Vec::new();
        for p in self.ephemerals_of(id) {
            self.remove_node(&p, z, &mut trig);
        }
        self.sessions.remove(&id);
        self.fire(trig, z);
    }

    /// Expires session `id`, closing its connection and deleting its ephemeral znodes.
    pub(crate) fn expire_session(&mut self, id: i64) -> bool {
        let Some(s) = self.sessions.get(&id) else {
            return false;
        };
        self.detach(s.conn);
        let z = self.next_zxid();
        self.end_session(id, z);
        true
    }

    /// Expires all active sessions and deletes their ephemeral znodes.
    pub(crate) fn expire_all(&mut self) {
        let ids: Vec<i64> = self.sessions.keys().copied().collect();
        for id in ids {
            self.expire_session(id);
        }
    }

    /// Returns snapshots of all active sessions.
    pub(crate) fn sessions(&self) -> Vec<SessionInfo> {
        self.sessions
            .iter()
            .map(|(id, s)| SessionInfo {
                id: *id,
                passwd: s.passwd,
                timeout_ms: s.timeout_ms,
                server: s.conn.and_then(|c| self.conns.get(&c)).map(|c| c.server),
                conn: s.conn,
                ephemerals: self.ephemerals_of(*id),
            })
            .collect()
    }

    fn ephemerals_of(&self, id: i64) -> Vec<String> {
        self.tree
            .iter()
            .filter(|(_, n)| n.st.ephemeral_owner == id)
            .map(|(p, _)| p.clone())
            .collect()
    }

    /// Expires idle sessions and reaps container/TTL znodes when their intervals elapse.
    pub(crate) fn tick(&mut self, now: Instant) {
        if self.cfg.session_expiry {
            let stale: Vec<i64> = self
                .sessions
                .iter()
                .filter(|(_, s)| {
                    let t = Duration::from_millis(u64::try_from(s.timeout_ms).unwrap_or(0));
                    now.saturating_duration_since(s.last_seen) > t
                })
                .map(|(id, _)| *id)
                .collect();
            for id in stale {
                self.expire_session(id);
            }
        }
        if let Some(every) = self.cfg.reap_interval {
            if now.saturating_duration_since(self.last_reap) >= every {
                self.last_reap = now;
                self.reap(now);
            }
        }
    }

    /// Deletes empty container znodes (`cversion > 0`) and expired TTL znodes (`ContainerManager`).
    pub(crate) fn reap(&mut self, now: Instant) -> Vec<String> {
        let victims: Vec<String> = self
            .tree
            .iter()
            .filter(|(_, n)| {
                let owner = n.st.ephemeral_owner;
                if !n.children.is_empty() {
                    return false;
                }
                if owner == CONTAINER_OWNER {
                    return n.st.cversion > 0;
                }
                if is_ttl_owner(owner) {
                    let ttl = Duration::from_millis(u64::try_from(ttl_of(owner)).unwrap_or(0));
                    return now.saturating_duration_since(n.modified_at) > ttl;
                }
                false
            })
            .map(|(p, _)| p.clone())
            .collect();
        for p in &victims {
            let z = self.next_zxid();
            let mut trig = Vec::new();
            self.remove_node(p, z, &mut trig);
            self.fire(trig, z);
        }
        victims
    }

    // ---- Access control list (ACL) evaluation and normalization ----

    fn auth_of(&self, conn: u64) -> Option<&[Id]> {
        self.conns.get(&conn).map(|c| c.auth.as_slice())
    }

    /// Checks whether `conn` holds `perm` against `acl` (`ZooKeeperServer.checkACL`).
    ///
    /// Admin operations (`conn == 0`) and connections authenticated with `super` bypass ACL checks.
    fn check_acl(&self, conn: u64, acl: &[ACL], perm: i32) -> Res<()> {
        let Some(ids) = self.auth_of(conn) else {
            return Ok(());
        };
        if acl.is_empty() || ids.iter().any(|i| i.scheme.as_str() == Some("super")) {
            return Ok(());
        }
        for a in acl {
            if a.perms & perm == 0 {
                continue;
            }
            let scheme = a.id.scheme.as_str().unwrap_or("");
            let expr = a.id.id.as_str().unwrap_or("");
            if scheme == "world" && expr == "anyone" {
                return Ok(());
            }
            let granted = ids.iter().any(|id| {
                let have = id.id.as_str().unwrap_or("");
                id.scheme.as_str() == Some(scheme)
                    && match scheme {
                        "ip" => ip_matches(have, expr),
                        "digest" => have == expr,
                        _ => false,
                    }
            });
            if granted {
                return Ok(());
            }
        }
        Err(ZNOAUTH)
    }

    /// Validates and expands `auth` ACL entries using `conn`'s credentials
    /// (`PrepRequestProcessor.fixupACL`).
    fn fixup_acl(&self, conn: u64, acl: JVec<ACL>) -> Res<Vec<ACL>> {
        let mut uniq: Vec<ACL> = Vec::new();
        for a in acl.into_vec().unwrap_or_default() {
            if !uniq.contains(&a) {
                uniq.push(a);
            }
        }
        if uniq.is_empty() {
            return Err(ZINVALIDACL);
        }
        let ids = self.auth_of(conn).unwrap_or(&[]);
        let mut out = Vec::new();
        for a in uniq {
            let scheme = a.id.scheme.as_str().ok_or(ZINVALIDACL)?;
            let id = a.id.id.as_str().unwrap_or("");
            match scheme {
                "world" if id == "anyone" => out.push(a),
                "auth" => {
                    let before = out.len();
                    let digests = ids.iter().filter(|c| c.scheme.as_str() == Some("digest"));
                    out.extend(digests.map(|c| ACL {
                        perms: a.perms,
                        id: c.clone(),
                    }));
                    if out.len() == before {
                        return Err(ZINVALIDACL);
                    }
                }
                "digest" if java_split_colon(id).len() == 2 => out.push(a),
                "ip" if parse_ip(id).is_some() => out.push(a),
                _ => return Err(ZINVALIDACL),
            }
        }
        Ok(out)
    }

    // ---- Watch indexing, triggering, and event delivery ----

    fn deliver(&mut self, conns: BTreeSet<u64>, ty: i32, p: &str, acl: Option<&[ACL]>, z: i64) {
        for c in conns {
            if let Some(acl) = acl {
                if self.check_acl(c, acl, perms::READ).is_err() {
                    continue;
                }
            }
            self.notify(c, ty, event::SYNC_CONNECTED, p, z);
        }
    }

    /// Delivers a watch notification to `conn` if it has `READ` permission on `acl`.
    fn deliver_one(&mut self, conn: u64, ty: i32, p: &str, acl: &[ACL], z: i64) {
        self.deliver(BTreeSet::from([conn]), ty, p, Some(acl), z);
    }

    /// Fires and delivers watch notifications queued in `trig`.
    fn fire(&mut self, trig: Vec<Trig>, z: i64) {
        for t in trig {
            match t {
                Trig::Created(p, acl) => {
                    let c = self.data_w.trigger(&p);
                    self.deliver(c, event::NODE_CREATED, &p, Some(&acl), z);
                }
                Trig::Deleted(p) => {
                    let first = self.data_w.trigger(&p);
                    let second: BTreeSet<u64> = self
                        .child_w
                        .trigger(&p)
                        .difference(&first)
                        .copied()
                        .collect();
                    self.deliver(first, event::NODE_DELETED, &p, None, z);
                    self.deliver(second, event::NODE_DELETED, &p, None, z);
                }
                Trig::Changed(p, acl) => {
                    let c = self.data_w.trigger(&p);
                    self.deliver(c, event::NODE_DATA_CHANGED, &p, Some(&acl), z);
                }
                Trig::Children(p, acl) => {
                    let c = self.child_w.trigger(&p);
                    self.deliver(c, event::NODE_CHILDREN_CHANGED, &p, Some(&acl), z);
                }
            }
        }
    }

    /// Returns the total number of znodes in the data tree.
    pub(crate) fn node_count(&self) -> usize {
        self.tree.len()
    }

    /// Returns the total number of registered watches across data and child watch tables.
    pub(crate) fn watch_count(&self) -> usize {
        self.data_w.count().saturating_add(self.child_w.count())
    }

    // ---- Tree node mutations and query execution ----

    /// Removes `p` from the data tree and records deletion and parent child-watch triggers.
    fn remove_node(&mut self, p: &str, z: i64, trig: &mut Vec<Trig>) {
        if self.tree.remove(p).is_none() {
            return;
        }
        trig.push(Trig::Deleted(p.to_owned()));
        if let Some(parent) = path::parent(p) {
            if let Some(pn) = self.tree.get_mut(parent) {
                pn.children.remove(path::name(p));
                pn.st.pzxid = z;
                trig.push(Trig::Children(parent.to_owned(), pn.acl.clone()));
            }
        }
    }

    /// Creates a znode after checking parent existence, ACLs, sequential naming, and ephemeral
    /// constraints.
    fn do_create(
        &mut self,
        conn: u64,
        session: i64,
        op: i32,
        a: CreateArgs,
        z: i64,
        trig: &mut Vec<Trig>,
    ) -> Res<(String, Stat)> {
        let mode = mode_from_flag(a.flags).ok_or(ZBADARGUMENTS)?;
        if mode.ttl && !self.cfg.ttl_enabled {
            return Err(ZUNIMPLEMENTED);
        }
        if mode.ttl {
            if a.ttl <= 0 || a.ttl > MAX_TTL {
                return Err(ZBADARGUMENTS);
            }
        } else if a.ttl >= 0 {
            return Err(ZBADARGUMENTS);
        }
        let p = path_of(&a.path)?;
        if p.contains('\0') {
            return Err(ZBADARGUMENTS);
        }
        let parent = path::parent(&p).ok_or(ZBADARGUMENTS)?.to_owned();
        let acl = self.fixup_acl(conn, a.acl)?;
        let pnode = self.tree.get(&parent).ok_or(ZNONODE)?;
        self.check_acl(conn, &pnode.acl, perms::CREATE)?;
        let cv = pnode.st.cversion;
        let final_path = if mode.sequential {
            format!("{p}{cv:010}")
        } else {
            p
        };
        if !path::is_valid(&final_path) {
            return Err(ZBADARGUMENTS);
        }
        if self.tree.contains_key(&final_path) {
            return Err(ZNODEEXISTS);
        }
        if is_session_owner(pnode.st.ephemeral_owner) {
            return Err(ZNOCHILDRENFOREPHEMERALS);
        }
        let owner = if mode.container || op == opcode::CREATE_CONTAINER {
            CONTAINER_OWNER
        } else if mode.ttl {
            (EXTENDED_MASK as i64) | a.ttl
        } else if mode.ephemeral {
            session
        } else {
            0
        };
        let now = Instant::now();
        let t = self.time(z);
        let mut node = Node::new(acl.clone(), now);
        node.null_data = a.data.is_null();
        node.data = a.data.into_bytes().unwrap_or_default();
        node.st = StatPersisted {
            czxid: z,
            mzxid: z,
            ctime: t,
            mtime: t,
            version: 0,
            cversion: 0,
            aversion: 0,
            ephemeral_owner: owner,
            pzxid: z,
        };
        let stat = node.stat();
        let name = path::name(&final_path).to_owned();
        self.tree.insert(final_path.clone(), node);
        let mut parent_acl = Vec::new();
        if let Some(pn) = self.tree.get_mut(&parent) {
            pn.children.insert(name);
            pn.st.cversion = cv.wrapping_add(1);
            pn.st.pzxid = z;
            parent_acl.clone_from(&pn.acl);
        }
        trig.push(Trig::Created(final_path.clone(), acl));
        trig.push(Trig::Children(parent, parent_acl));
        Ok((final_path, stat))
    }

    /// Deletes a znode after checking existence, version, emptiness, and parent `DELETE` ACL.
    fn do_delete(&mut self, conn: u64, q: &DeleteRequest, z: i64, trig: &mut Vec<Trig>) -> Res<()> {
        let p = path_of(&q.path)?;
        if p.contains('\0') || SPECIAL.contains(&p.as_str()) {
            return Err(ZBADARGUMENTS);
        }
        let parent = path::parent(&p).ok_or(ZBADARGUMENTS)?;
        let pnode = self.tree.get(parent).ok_or(ZNONODE)?;
        self.check_acl(conn, &pnode.acl, perms::DELETE)?;
        let node = self.tree.get(&p).ok_or(ZNONODE)?;
        check_version(node.st.version, q.version)?;
        if !node.children.is_empty() {
            return Err(ZNOTEMPTY);
        }
        self.remove_node(&p, z, trig);
        Ok(())
    }

    /// Updates a znode's payload, increments its version, and queues a data-changed trigger.
    fn do_set_data(
        &mut self,
        conn: u64,
        q: SetDataRequest,
        z: i64,
        trig: &mut Vec<Trig>,
    ) -> Res<Stat> {
        let p = path_of(&q.path)?;
        if !path::is_valid(&p) {
            return Err(ZBADARGUMENTS);
        }
        let node = self.tree.get(&p).ok_or(ZNONODE)?;
        self.check_acl(conn, &node.acl, perms::WRITE)?;
        let version = check_version(node.st.version, q.version)?;
        let t = self.time(z);
        let node = self.tree.get_mut(&p).ok_or(ZNONODE)?;
        node.null_data = q.data.is_null();
        node.data = q.data.into_bytes().unwrap_or_default();
        node.st.version = version;
        node.st.mzxid = z;
        node.st.mtime = t;
        node.modified_at = Instant::now();
        trig.push(Trig::Changed(p, node.acl.clone()));
        Ok(node.stat())
    }

    /// Checks that a znode exists and matches `q.version` without mutating state.
    fn do_check(&self, conn: u64, q: &CheckVersionRequest) -> Res<Stat> {
        let p = path_of(&q.path)?;
        if !path::is_valid(&p) {
            return Err(ZBADARGUMENTS);
        }
        let node = self.tree.get(&p).ok_or(ZNONODE)?;
        self.check_acl(conn, &node.acl, perms::READ)?;
        check_version(node.st.version, q.version)?;
        Ok(node.stat())
    }

    /// Replaces a znode's ACL list and increments `aversion`.
    fn do_set_acl(&mut self, conn: u64, q: SetACLRequest) -> Res<Stat> {
        let p = path_of(&q.path)?;
        if !path::is_valid(&p) {
            return Err(ZBADARGUMENTS);
        }
        if !self.tree.contains_key(&p) {
            return Err(ZNONODE);
        }
        let acl = self.fixup_acl(conn, q.acl)?;
        let node = self.tree.get(&p).ok_or(ZNONODE)?;
        self.check_acl(conn, &node.acl, perms::ADMIN)?;
        let aversion = check_version(node.st.aversion, q.version)?;
        let node = self.tree.get_mut(&p).ok_or(ZNONODE)?;
        node.acl = acl;
        node.st.aversion = aversion;
        Ok(node.stat())
    }

    /// Reads a znode's payload and stat, registering a one-shot data watch when `q.watch` is set.
    fn do_get_data(&mut self, conn: u64, q: &GetDataRequest) -> Res<(JBuffer, Stat)> {
        let p = path_of(&q.path)?;
        let node = self.tree.get(&p).ok_or(ZNONODE)?;
        self.check_acl(conn, &node.acl, perms::READ)?;
        let out = (node.data_buf(), node.stat());
        if q.watch {
            self.data_w.add(&p, conn, W_STANDARD);
        }
        Ok(out)
    }

    /// Reads a znode's child names and stat, registering a one-shot child watch when `watch` is
    /// set.
    fn do_get_children(&mut self, conn: u64, p: &JString, watch: bool) -> Res<(Vec<String>, Stat)> {
        let p = path_of(p)?;
        let node = self.tree.get(&p).ok_or(ZNONODE)?;
        self.check_acl(conn, &node.acl, perms::READ)?;
        let out = (node.children.iter().cloned().collect(), node.stat());
        if watch {
            self.child_w.add(&p, conn, W_STANDARD);
        }
        Ok(out)
    }

    /// Checks whether a znode exists and returns its stat, registering a one-shot watch on `p`
    /// when `q.watch` is set.
    fn do_exists(&mut self, conn: u64, q: &ExistsRequest) -> Res<Stat> {
        let p = path_of(&q.path)?;
        if p.contains('\0') {
            return Err(ZBADARGUMENTS);
        }
        if let Some(node) = self.tree.get(&p) {
            self.check_acl(conn, &node.acl, perms::READ)?;
        }
        if q.watch {
            self.data_w.add(&p, conn, W_STANDARD);
        }
        self.tree.get(&p).map(Node::stat).ok_or(ZNONODE)
    }

    /// Returns a znode's ACL list and stat, redacting digest hashes unless the caller has `ADMIN`
    /// permission.
    fn do_get_acl(&self, conn: u64, q: &GetACLRequest) -> Res<Vec<u8>> {
        let p = path_of(&q.path)?;
        let node = self.tree.get(&p).ok_or(ZNONODE)?;
        self.check_acl(conn, &node.acl, perms::READ | perms::ADMIN)?;
        let acl = if self.check_acl(conn, &node.acl, perms::ADMIN).is_ok() {
            node.acl.clone()
        } else {
            node.acl
                .iter()
                .map(|a| {
                    let mut a = a.clone();
                    if a.id.scheme.as_str() == Some("digest") {
                        let user =
                            a.id.id
                                .as_str()
                                .unwrap_or("")
                                .split(':')
                                .next()
                                .unwrap_or("");
                        a.id.id = JString::from(format!("{user}:x"));
                    }
                    a
                })
                .collect()
        };
        Ok(GetACLResponse {
            acl: JVec::from(acl),
            stat: node.stat(),
        }
        .to_bytes())
    }

    /// Counts all descendant znodes under `q.path`.
    fn do_all_children(&self, conn: u64, q: &GetAllChildrenNumberRequest) -> Res<Vec<u8>> {
        let p = path_of(&q.path)?;
        let node = self.tree.get(&p).ok_or(ZNONODE)?;
        self.check_acl(conn, &node.acl, perms::READ)?;
        let prefix = if p == "/" {
            "/".to_owned()
        } else {
            format!("{p}/")
        };
        let n = self
            .tree
            .keys()
            .filter(|k| k.as_str() != "/" && k.starts_with(&prefix))
            .count();
        Ok(GetAllChildrenNumberResponse {
            total_number: i32::try_from(n).unwrap_or(i32::MAX),
        }
        .to_bytes())
    }

    /// Returns all ephemeral znode paths owned by `session` under `q.prefix_path`.
    fn do_get_ephemerals(&self, session: i64, q: &GetEphemeralsRequest) -> Vec<u8> {
        let prefix = q.prefix_path.as_str().map(str::trim).unwrap_or("");
        let all = self.ephemerals_of(session);
        let list: Vec<JString> = all
            .into_iter()
            .filter(|p| prefix.is_empty() || prefix == "/" || p.starts_with(prefix))
            .map(JString::from)
            .collect();
        GetEphemeralsResponse {
            ephemerals: JVec::from(list),
        }
        .to_bytes()
    }

    /// Encodes a `WhoAmIResponse` listing the authenticated identities on `conn`.
    fn do_who_am_i(&self, conn: u64) -> Vec<u8> {
        let infos: Vec<ClientInfo> = self
            .auth_of(conn)
            .unwrap_or(&[])
            .iter()
            .map(|id| {
                let scheme = id.scheme.as_str().unwrap_or("");
                let raw = id.id.as_str().unwrap_or("");
                let user = match scheme {
                    "digest" => raw.split(':').next().unwrap_or(""),
                    "ip" => raw,
                    _ => "",
                };
                ClientInfo {
                    auth_scheme: id.scheme.clone(),
                    user: JString::from(user),
                }
            })
            .collect();
        WhoAmIResponse {
            client_info: JVec::from(infos),
        }
        .to_bytes()
    }

    /// Re-registers watches from `SetWatches`/`SetWatches2`, firing immediately for znodes
    /// modified since `relative_zxid`.
    fn do_set_watches(&mut self, conn: u64, w: SetWatches2) {
        let rel = w.relative_zxid;
        let names = |v: &JVec<JString>| -> Vec<String> {
            v.iter()
                .filter_map(|s| s.as_str().map(str::to_owned))
                .collect()
        };
        let none = zk_jute::xid::WATCHER_EVENT.into();
        for p in names(&w.data_watches) {
            match self.tree.get(&p) {
                None => self.notify(conn, event::NODE_DELETED, event::SYNC_CONNECTED, &p, none),
                Some(n) if n.st.mzxid > rel => {
                    let acl = n.acl.clone();
                    self.deliver_one(conn, event::NODE_DATA_CHANGED, &p, &acl, none);
                }
                Some(_) => self.data_w.add(&p, conn, W_STANDARD),
            }
        }
        for p in names(&w.exist_watches) {
            match self.tree.get(&p) {
                Some(n) => {
                    let acl = n.acl.clone();
                    self.deliver_one(conn, event::NODE_CREATED, &p, &acl, none);
                }
                None => self.data_w.add(&p, conn, W_STANDARD),
            }
        }
        for p in names(&w.child_watches) {
            match self.tree.get(&p) {
                None => self.notify(conn, event::NODE_DELETED, event::SYNC_CONNECTED, &p, none),
                Some(n) if n.st.pzxid > rel => {
                    let acl = n.acl.clone();
                    self.deliver_one(conn, event::NODE_CHILDREN_CHANGED, &p, &acl, none);
                }
                Some(_) => self.child_w.add(&p, conn, W_STANDARD),
            }
        }
        for p in names(&w.persistent_watches) {
            self.child_w.add(&p, conn, W_PERSISTENT);
            self.data_w.add(&p, conn, W_PERSISTENT);
        }
        for p in names(&w.persistent_recursive_watches) {
            self.data_w.add(&p, conn, W_RECURSIVE);
        }
    }

    /// Checks or removes watches on `p` matching watcher type `ty`.
    fn watch_query(&mut self, conn: u64, p: &str, ty: i32, remove: bool) -> Res<bool> {
        let std_mode = Some(W_STANDARD);
        Ok(match (ty, remove) {
            (1, false) => self.child_w.has(p, conn, std_mode),
            (2, false) => self.data_w.has(p, conn, std_mode),
            (3, false) => self.child_w.has(p, conn, None) || self.data_w.has(p, conn, None),
            (4, false) => self.data_w.has(p, conn, Some(W_PERSISTENT)),
            (5, false) => self.data_w.has(p, conn, Some(W_RECURSIVE)),
            (1, true) => self.child_w.remove(p, conn, std_mode),
            (2, true) => self.data_w.remove(p, conn, std_mode),
            (3, true) => {
                let a = self.child_w.remove(p, conn, None);
                let b = self.data_w.remove(p, conn, None);
                a || b
            }
            (4, true) => {
                let a = self.child_w.remove(p, conn, Some(W_PERSISTENT));
                let b = self.data_w.remove(p, conn, Some(W_PERSISTENT));
                a || b
            }
            (5, true) => self.data_w.remove(p, conn, Some(W_RECURSIVE)),
            _ => return Err(ZMARSHALLINGERROR),
        })
    }

    /// Processes a `reconfig` request and updates `/zookeeper/config`.
    fn do_reconfig(&mut self, q: &ReconfigRequest, z: i64, trig: &mut Vec<Trig>) -> Res<Vec<u8>> {
        if !self.cfg.reconfig_enabled {
            return Err(ZRECONFIGDISABLED);
        }
        let node = self.tree.get(CONFIG_NODE).ok_or(ZNONODE)?;
        let text = String::from_utf8_lossy(&node.data).into_owned();
        let (mut servers, version) = parse_config(&text);
        if q.cur_config_id != -1 && q.cur_config_id != version {
            return Err(ZBADVERSION);
        }
        let entries = |s: &str| -> Vec<String> {
            s.split([',', '\n'])
                .map(str::trim)
                .filter(|e| !e.is_empty())
                .map(str::to_owned)
                .collect()
        };
        if let Some(nm) = q.new_members.as_str() {
            servers.clear();
            for e in entries(nm) {
                let id = server_id_of(&e).ok_or(ZBADARGUMENTS)?;
                servers.insert(id, e);
            }
        } else {
            for e in entries(q.leaving_servers.as_str().unwrap_or("")) {
                let id: u64 = e.parse().map_err(|_| ZBADARGUMENTS)?;
                servers.remove(&id);
            }
            for e in entries(q.joining_servers.as_str().unwrap_or("")) {
                let id = server_id_of(&e).ok_or(ZBADARGUMENTS)?;
                servers.insert(id, e);
            }
        }
        if servers.is_empty() {
            return Err(ZNEWCONFIGNOQUORUM);
        }
        let mut data = String::new();
        for line in servers.values() {
            data.push_str(line);
            data.push('\n');
        }
        data.push_str(&format!("version={z:x}"));
        let t = self.time(z);
        let node = self.tree.get_mut(CONFIG_NODE).ok_or(ZNONODE)?;
        node.data = data.into_bytes();
        node.null_data = false;
        node.st.version = node.st.version.wrapping_add(1);
        node.st.mzxid = z;
        node.st.mtime = t;
        trig.push(Trig::Changed(CONFIG_NODE.to_owned(), node.acl.clone()));
        Ok(GetDataResponse {
            data: node.data_buf(),
            stat: node.stat(),
        }
        .to_bytes())
    }

    /// Replaces `/zookeeper/config` payload bytes directly without advancing `zxid` or firing
    /// watches.
    pub(crate) fn set_config(&mut self, text: &str) {
        if let Some(n) = self.tree.get_mut(CONFIG_NODE) {
            n.data = text.as_bytes().to_vec();
            n.null_data = false;
        }
    }

    /// Evaluates an `AuthPacket` against the configured [`AuthPolicy`] and records authenticated
    /// identities on `conn`.
    fn do_auth(&mut self, conn: u64, xid: i32, body: &[u8]) -> Handled {
        let reject = answer(xid, 0, Err(ZAUTHFAILED), true);
        let Ok(q) = AuthPacket::from_bytes(body) else {
            return reject;
        };
        let creds = String::from_utf8_lossy(q.auth.as_bytes().unwrap_or(&[])).into_owned();
        let new_ids = match q.scheme.as_str() {
            Some("digest") => {
                let ok = match &self.cfg.auth {
                    AuthPolicy::AcceptAll => true,
                    AuthPolicy::RejectAll => false,
                    AuthPolicy::Only(list) => list.contains(&creds),
                };
                if !ok {
                    return reject;
                }
                let id = digest::digest_id(&creds);
                let mut ids = Vec::new();
                if self.cfg.super_digest.as_deref() == Some(id.as_str()) {
                    ids.push(Id {
                        scheme: "super".into(),
                        id: "".into(),
                    });
                }
                ids.push(Id {
                    scheme: "digest".into(),
                    id: id.into(),
                });
                ids
            }
            Some("ip") if self.cfg.auth != AuthPolicy::RejectAll => Vec::new(),
            _ => return reject,
        };
        if let Some(c) = self.conns.get_mut(&conn) {
            for id in new_ids {
                if !c.auth.contains(&id) {
                    c.auth.push(id);
                }
            }
        }
        answer(xid, 0, Ok(None), false)
    }

    // ---- Request dispatch and transaction execution ----

    /// Dispatches a post-handshake request frame and returns the reply to enqueue.
    pub(crate) fn handle_request(
        &mut self,
        conn: u64,
        xid: i32,
        op: i32,
        body: &[u8],
        read_only: bool,
    ) -> Handled {
        let session = self.session_of(conn);
        self.touch(session, Instant::now());
        if read_only && is_write(op) {
            return answer(xid, self.zxid, Err(ZNOTREADONLY), false);
        }
        match op {
            opcode::PING => answer(xid, self.zxid, Ok(None), false),
            opcode::CLOSE => {
                let z = self.next_zxid();
                if session != 0 {
                    if let Some(c) = self.conns.get_mut(&conn) {
                        c.session = None;
                    }
                    self.end_session(session, z);
                }
                answer(xid, z, Ok(None), true)
            }
            opcode::SET_AUTH => self.do_auth(conn, xid, body),
            opcode::SASL => answer(xid, 0, Err(ZAUTHFAILED), true),
            _ if is_write(op) && op != opcode::SYNC => {
                let z = self.next_zxid();
                let res = self.write_op(conn, session, op, body, z);
                answer(xid, z, res, false)
            }
            _ => match self.read_op(conn, session, op, body) {
                Some(res) => answer(xid, self.zxid, res, false),
                None => answer(xid, self.zxid, Err(ZUNIMPLEMENTED), true),
            },
        }
    }

    /// Executes a write operation under transaction ID `z` (advanced even when the operation
    /// fails).
    fn write_op(
        &mut self,
        conn: u64,
        session: i64,
        op: i32,
        body: &[u8],
        z: i64,
    ) -> Res<Option<Vec<u8>>> {
        let mut trig = Vec::new();
        let res = match op {
            opcode::CREATE | opcode::CREATE2 | opcode::CREATE_CONTAINER | opcode::CREATE_TTL => {
                dec_create(op, body)
                    .and_then(|a| self.create_reply(conn, session, op, a, z, &mut trig))
                    .map(|(_, b)| Some(b))
            }
            opcode::DELETE | opcode::DELETE_CONTAINER => dec::<DeleteRequest>(body)
                .and_then(|q| self.do_delete(conn, &q, z, &mut trig))
                .map(|()| None),
            opcode::SET_DATA => dec::<SetDataRequest>(body)
                .and_then(|q| self.do_set_data(conn, q, z, &mut trig))
                .map(|stat| Some(SetDataResponse { stat }.to_bytes())),
            opcode::SET_ACL => dec::<SetACLRequest>(body)
                .and_then(|q| self.do_set_acl(conn, q))
                .map(|stat| Some(SetACLResponse { stat }.to_bytes())),
            opcode::CHECK => dec::<CheckVersionRequest>(body)
                .and_then(|q| self.do_check(conn, &q))
                .map(|stat| Some(SetDataResponse { stat }.to_bytes())),
            opcode::RECONFIG => dec::<ReconfigRequest>(body)
                .and_then(|q| self.do_reconfig(&q, z, &mut trig))
                .map(Some),
            opcode::MULTI => {
                decode_ops(body, false).map(|ops| Some(self.do_multi(conn, session, ops, z)))
            }
            _ => Err(ZUNIMPLEMENTED),
        };
        self.fire(trig, z);
        res
    }

    /// Creates a znode and encodes either a `CreateResponse` or `Create2Response`.
    fn create_reply(
        &mut self,
        conn: u64,
        session: i64,
        op: i32,
        a: CreateArgs,
        z: i64,
        trig: &mut Vec<Trig>,
    ) -> Res<OpOk> {
        let (p, stat) = self.do_create(conn, session, op, a, z, trig)?;
        Ok(if op == opcode::CREATE {
            (opcode::CREATE, CreateResponse { path: p.into() }.to_bytes())
        } else {
            let resp = Create2Response {
                path: p.into(),
                stat,
            };
            (opcode::CREATE2, resp.to_bytes())
        })
    }

    /// Executes a read-only operation, returning `None` if `op` is unrecognized.
    fn read_op(
        &mut self,
        conn: u64,
        session: i64,
        op: i32,
        body: &[u8],
    ) -> Option<Res<Option<Vec<u8>>>> {
        Some(match op {
            opcode::SET_WATCHES | opcode::SET_WATCHES2 => {
                self.op_set_watches(conn, body, op == opcode::SET_WATCHES2)
            }
            opcode::MULTI_READ => {
                decode_ops(body, true).map(|ops| Some(self.do_multi_read(conn, ops)))
            }
            opcode::SYNC => {
                dec::<SyncRequest>(body).map(|q| Some(SyncResponse { path: q.path }.to_bytes()))
            }
            opcode::EXISTS => dec::<ExistsRequest>(body)
                .and_then(|q| self.do_exists(conn, &q))
                .map(|stat| Some(ExistsResponse { stat }.to_bytes())),
            opcode::GET_DATA => dec::<GetDataRequest>(body)
                .and_then(|q| self.do_get_data(conn, &q))
                .map(|(data, stat)| Some(GetDataResponse { data, stat }.to_bytes())),
            opcode::GET_CHILDREN | opcode::GET_CHILDREN2 => {
                self.op_get_children(conn, body, op == opcode::GET_CHILDREN2)
            }
            opcode::GET_ACL => dec::<GetACLRequest>(body)
                .and_then(|q| self.do_get_acl(conn, &q))
                .map(Some),
            opcode::GET_ALL_CHILDREN_NUMBER => dec::<GetAllChildrenNumberRequest>(body)
                .and_then(|q| self.do_all_children(conn, &q))
                .map(Some),
            opcode::GET_EPHEMERALS => {
                dec::<GetEphemeralsRequest>(body).map(|q| Some(self.do_get_ephemerals(session, &q)))
            }
            opcode::WHO_AM_I => Ok(Some(self.do_who_am_i(conn))),
            opcode::ADD_WATCH => self.op_add_watch(conn, body),
            // `CheckWatchesRequest` and `RemoveWatchesRequest` share the same wire layout.
            opcode::CHECK_WATCHES | opcode::REMOVE_WATCHES => dec::<CheckWatchesRequest>(body)
                .and_then(|q| {
                    let remove = op == opcode::REMOVE_WATCHES;
                    self.op_watch_query(conn, &q.path, q.r#type, remove)
                }),
            _ => return None,
        })
    }

    /// Decodes `SET_WATCHES` or `SET_WATCHES2` and applies the watch registrations.
    fn op_set_watches(&mut self, conn: u64, body: &[u8], v2: bool) -> Res<Option<Vec<u8>>> {
        let w = if v2 {
            dec::<SetWatches2>(body)?
        } else {
            widen(dec::<SetWatches>(body)?)
        };
        self.do_set_watches(conn, w);
        Ok(None)
    }

    /// Executes `GET_CHILDREN` or `GET_CHILDREN2`.
    fn op_get_children(&mut self, conn: u64, body: &[u8], two: bool) -> Res<Option<Vec<u8>>> {
        let q = dec::<GetChildrenRequest>(body)?;
        let (children, stat) = self.do_get_children(conn, &q.path, q.watch)?;
        let children = strings(children);
        Ok(Some(if two {
            GetChildren2Response { children, stat }.to_bytes()
        } else {
            GetChildrenResponse { children }.to_bytes()
        }))
    }

    /// Registers a persistent or persistent recursive watch for an `ADD_WATCH` request.
    fn op_add_watch(&mut self, conn: u64, body: &[u8]) -> Res<Option<Vec<u8>>> {
        let q = dec::<AddWatchRequest>(body)?;
        let p = path_of(&q.path)?;
        match q.mode {
            0 => {
                self.data_w.add(&p, conn, W_PERSISTENT);
                self.child_w.add(&p, conn, W_PERSISTENT);
            }
            1 => self.data_w.add(&p, conn, W_RECURSIVE),
            _ => return Err(ZMARSHALLINGERROR),
        }
        Ok(Some(ErrorResponse { err: 0 }.to_bytes()))
    }

    /// Checks or removes a watch registration, returning `ZNOWATCHER` if no matching watch exists.
    fn op_watch_query(
        &mut self,
        conn: u64,
        p: &JString,
        ty: i32,
        remove: bool,
    ) -> Res<Option<Vec<u8>>> {
        let p = path_of(p)?;
        if self.watch_query(conn, &p, ty, remove)? {
            Ok(None)
        } else {
            Err(ZNOWATCHER)
        }
    }

    /// Executes an atomic `MULTI` transaction batch.
    ///
    /// If any sub-operation fails, the data tree is rolled back to its pre-batch snapshot. Per
    /// `DataTree.processTxn`, sub-operations before the failure report `err = 0`, the failing
    /// sub-operation reports its error code, and subsequent sub-operations report
    /// `ZRUNTIMEINCONSISTENCY`.
    fn do_multi(&mut self, conn: u64, session: i64, ops: Vec<MultiOp>, z: i64) -> Vec<u8> {
        let snapshot = self.tree.clone();
        let mut trig = Vec::new();
        let mut results: Vec<Res<OpOk>> = Vec::new();
        let mut failed = None;
        let n = ops.len();
        for (i, op) in ops.into_iter().enumerate() {
            let r = match op {
                MultiOp::Create(code, a) => self.create_reply(conn, session, code, a, z, &mut trig),
                MultiOp::Delete(q) => self
                    .do_delete(conn, &q, z, &mut trig)
                    .map(|()| (opcode::DELETE, Vec::new())),
                MultiOp::SetData(q) => self
                    .do_set_data(conn, q, z, &mut trig)
                    .map(|stat| (opcode::SET_DATA, SetDataResponse { stat }.to_bytes())),
                MultiOp::Check(q) => self.do_check(conn, &q).map(|_| (opcode::CHECK, Vec::new())),
                MultiOp::GetData(_) | MultiOp::GetChildren(_) => Err(ZMARSHALLINGERROR),
            };
            if let Err(e) = r {
                failed = Some((i, e));
                break;
            }
            results.push(r);
        }
        if let Some((at, err)) = failed {
            self.tree = snapshot;
            results = (0..n)
                .map(|i| {
                    Err(match i.cmp(&at) {
                        core::cmp::Ordering::Less => 0,
                        core::cmp::Ordering::Equal => err,
                        core::cmp::Ordering::Greater => ZRUNTIMEINCONSISTENCY,
                    })
                })
                .collect();
        } else {
            self.fire(trig, z);
        }
        encode_results(results)
    }

    /// Executes a `multiRead` batch of read-only sub-operations.
    fn do_multi_read(&mut self, conn: u64, ops: Vec<MultiOp>) -> Vec<u8> {
        let mut results = Vec::new();
        for op in ops {
            results.push(match op {
                MultiOp::GetData(q) => self.do_get_data(conn, &q).map(|(data, stat)| {
                    (opcode::GET_DATA, GetDataResponse { data, stat }.to_bytes())
                }),
                MultiOp::GetChildren(q) => {
                    self.do_get_children(conn, &q.path, q.watch).map(|(c, _)| {
                        let resp = GetChildrenResponse {
                            children: strings(c),
                        };
                        (opcode::GET_CHILDREN, resp.to_bytes())
                    })
                }
                _ => Err(ZMARSHALLINGERROR),
            });
        }
        encode_results(results)
    }

    // ---- Test inspection and administrative mutations ----

    /// Returns a snapshot of the znode at `p`, or `None` if it does not exist.
    pub(crate) fn node(&self, p: &str) -> Option<NodeInfo> {
        self.tree.get(p).map(|n| NodeInfo {
            data: if n.null_data {
                None
            } else {
                Some(n.data.clone())
            },
            stat: n.stat(),
            acl: n.acl.clone(),
            children: n.children.iter().cloned().collect(),
        })
    }

    /// Creates a znode bypassing client ACL checks and fires matching watches.
    pub(crate) fn admin_create(&mut self, p: &str, data: &[u8], flags: i32) -> Res<String> {
        let z = self.next_zxid();
        let mut trig = Vec::new();
        let ttl = if matches!(flags, 5 | 6) { MAX_TTL } else { -1 };
        let a = CreateArgs {
            path: p.into(),
            data: JBuffer::from(data),
            acl: JVec::from(world_acl(perms::ALL)),
            flags,
            ttl,
        };
        let r = self.do_create(0, 0, opcode::CREATE, a, z, &mut trig);
        self.fire(trig, z);
        r.map(|(p, _)| p)
    }

    /// Sets a znode's payload bypassing client ACL checks and fires matching watches.
    pub(crate) fn admin_set_data(&mut self, p: &str, data: &[u8]) -> Res<Stat> {
        let z = self.next_zxid();
        let mut trig = Vec::new();
        let q = SetDataRequest {
            path: p.into(),
            data: JBuffer::from(data),
            version: -1,
        };
        let r = self.do_set_data(0, q, z, &mut trig);
        self.fire(trig, z);
        r
    }

    /// Deletes a znode bypassing client ACL checks and fires matching watches.
    pub(crate) fn admin_delete(&mut self, p: &str) -> Res<()> {
        let z = self.next_zxid();
        let mut trig = Vec::new();
        let q = DeleteRequest {
            path: p.into(),
            version: -1,
        };
        let r = self.do_delete(0, &q, z, &mut trig);
        self.fire(trig, z);
        r
    }
}

/// Returns whether `op` is a write operation rejected by read-only servers.
const fn is_write(op: i32) -> bool {
    matches!(
        op,
        opcode::SYNC
            | opcode::CREATE
            | opcode::CREATE2
            | opcode::CREATE_TTL
            | opcode::CREATE_CONTAINER
            | opcode::DELETE
            | opcode::DELETE_CONTAINER
            | opcode::SET_DATA
            | opcode::RECONFIG
            | opcode::SET_ACL
            | opcode::MULTI
            | opcode::CHECK
    )
}

/// Builds a [`Handled`] reply from `xid`, `zxid`, and an operation result `res`.
pub(crate) fn answer(xid: i32, zxid: i64, res: Res<Option<Vec<u8>>>, close: bool) -> Handled {
    let reply = match res {
        Ok(body) => Reply {
            xid,
            zxid,
            err: 0,
            body,
        },
        Err(err) => Reply {
            xid,
            zxid,
            err,
            body: None,
        },
    };
    Handled {
        reply: Some(reply),
        close,
    }
}

/// Converts a `Vec<String>` into a `JVec<JString>`.
fn strings(v: Vec<String>) -> JVec<JString> {
    JVec::from(v.into_iter().map(JString::from).collect::<Vec<_>>())
}

/// Parses `/zookeeper/config` text into a map of `server.<id>` lines and the hex `version`.
fn parse_config(text: &str) -> (BTreeMap<u64, String>, i64) {
    let mut servers = BTreeMap::new();
    let mut version = 0;
    for line in text.lines().map(str::trim) {
        if let Some(v) = line.strip_prefix("version=") {
            version = i64::from_str_radix(v, 16).unwrap_or(0);
        } else if let Some(id) = server_id_of(line) {
            servers.insert(id, line.to_owned());
        }
    }
    (servers, version)
}

/// Extracts the numeric server ID from a `server.<id>=...` configuration line.
fn server_id_of(entry: &str) -> Option<u64> {
    let rest = entry.strip_prefix("server.")?;
    let (id, _) = rest.split_once('=')?;
    id.parse().ok()
}

/// Decodes the sub-operations of a `MULTI` or `MULTI_READ` request body.
///
/// Sub-operation opcodes that do not match `read` fail with `ZMARSHALLINGERROR`.
fn decode_ops(body: &[u8], read: bool) -> Res<Vec<MultiOp>> {
    let mut r = Reader::new(body);
    let mut ops = Vec::new();
    let m = |_| ZMARSHALLINGERROR;
    loop {
        let h = MultiHeader::decode(&mut r).map_err(m)?;
        if h.done {
            break;
        }
        let op = match (h.r#type, read) {
            (opcode::CREATE | opcode::CREATE2 | opcode::CREATE_CONTAINER, false) => {
                MultiOp::Create(h.r#type, CreateRequest::decode(&mut r).map_err(m)?.into())
            }
            (opcode::CREATE_TTL, false) => MultiOp::Create(
                h.r#type,
                CreateTTLRequest::decode(&mut r).map_err(m)?.into(),
            ),
            (opcode::DELETE | opcode::DELETE_CONTAINER, false) => {
                MultiOp::Delete(DeleteRequest::decode(&mut r).map_err(m)?)
            }
            (opcode::SET_DATA, false) => {
                MultiOp::SetData(SetDataRequest::decode(&mut r).map_err(m)?)
            }
            (opcode::CHECK, false) => {
                MultiOp::Check(CheckVersionRequest::decode(&mut r).map_err(m)?)
            }
            (opcode::GET_DATA, true) => {
                MultiOp::GetData(GetDataRequest::decode(&mut r).map_err(m)?)
            }
            (opcode::GET_CHILDREN, true) => {
                MultiOp::GetChildren(GetChildrenRequest::decode(&mut r).map_err(m)?)
            }
            _ => return Err(ZMARSHALLINGERROR),
        };
        ops.push(op);
    }
    Ok(ops)
}

/// Encodes `MULTI` or `MULTI_READ` sub-operation results (`MultiResponse.serialize`).
fn encode_results(results: Vec<Res<OpOk>>) -> Vec<u8> {
    let mut out = Vec::new();
    for r in results {
        let (ty, err, body) = match r {
            Ok((ty, body)) => (ty, 0, body),
            Err(e) => (opcode::ERROR, e, ErrorResponse { err: e }.to_bytes()),
        };
        MultiHeader {
            r#type: ty,
            done: false,
            err,
        }
        .encode(&mut out);
        out.extend_from_slice(&body);
    }
    zk_jute::multi::encode_end(&mut out);
    out
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
mod tests {
    use super::{W_PERSISTENT, W_RECURSIVE, W_STANDARD, WatchTable, ip_matches, java_split_colon};

    #[test]
    fn watch_table_modes() {
        let mut t = WatchTable::default();
        t.add("/a", 1, W_STANDARD);
        t.add("/a", 2, W_PERSISTENT);
        t.add("/", 3, W_RECURSIVE);
        t.add("/", 4, W_STANDARD);
        let fired: Vec<u64> = t.trigger("/a").into_iter().collect();
        assert_eq!(fired, vec![1, 2, 3]);
        let fired: Vec<u64> = t.trigger("/a").into_iter().collect();
        assert_eq!(fired, vec![2, 3]);
        assert!(t.remove("/a", 2, Some(W_PERSISTENT)));
        assert!(!t.remove("/a", 2, Some(W_PERSISTENT)));
        assert!(t.has("/", 4, Some(W_STANDARD)));
    }

    #[test]
    fn ip_and_digest_rules() {
        assert!(ip_matches("127.0.0.1", "127.0.0.1"));
        assert!(ip_matches("10.1.2.3", "10.0.0.0/8"));
        assert!(!ip_matches("11.1.2.3", "10.0.0.0/8"));
        assert_eq!(java_split_colon("a:"), vec!["a"]);
        assert_eq!(java_split_colon(":b").len(), 2);
    }
}
