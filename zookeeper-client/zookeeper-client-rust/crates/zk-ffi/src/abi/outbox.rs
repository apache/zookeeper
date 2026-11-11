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

//! Asynchronous driver outbox draining and zero-copy C view staging.
//!
//! Driving iterations on a ZooKeeper handle populate a `zk-driver` `Outbox<CTokens>`.
//! The [`with_outbox`] coordination boundary manages this outbox within an [`FfiOutbox`]
//! referenced by the opaque pointer `zkrs_outbox.impl`. Client runtimes sequentially drain
//! items from this queue using `zkrs_outbox_next`. Each invocation of `zkrs_outbox_next`
//! stages an FFI-compatible C view of the next item within [`Staged`]. Staged views
//! (including zero-copy payload slices, NUL-terminated strings, and watcher arrays) remain
//! valid and pinned in memory until the subsequent call on the same outbox.
//!
//! Outbox lifecycle invariant: `zkrs_outbox_finish` deallocates the outbox and resets `impl`
//! to null only when no asynchronous driver resume checkpoint is pending. If a resume tag
//! is active, the empty outbox is retained so that `zkrs_resume` can query the resume state.
//! A driving iteration that produces neither items nor a resume tag leaves `impl` null,
//! completely avoiding heap allocations.
//!
//! Wall-clock timestamps for log entries are captured when [`with_outbox`] adopts items at
//! the conclusion of a driving cycle, rather than when the client runtime drains them.

use std::ffi::c_int;
use std::mem::{offset_of, size_of};
use std::net::{SocketAddr, SocketAddrV4, SocketAddrV6};
use std::ptr;
use std::sync::Arc;

use zeroize::Zeroizing;

use zk_driver::outbox::discard_item;
use zk_driver::{HostLeaves, Item, Outbox, SaslItem, ThreadOp, ThreadRole};
use zk_jute::data::{ACL, Stat};
use zk_jute::multi::{MultiBody, MultiItem};
use zk_jute::{JString, JVec, opcode};
use zk_proto::legacy::watchers::SESSION_EVENT;
use zk_proto::{LogRecord, OpResult, Origin, Reply, ReplyData, SaslCallKind};

use super::entry::ffi_guard;
use super::handle::Inner;
use super::logging::{self, LogViews};
use super::raw;
use super::sasl::{self, Taken};
use super::tokens::{CTokens, CWatcher};
use super::types::{
    ZKRS_ORIGIN_REPLY, ZKRS_ORIGIN_TEARDOWN, ZKRS_ROLE_DISPATCH, ZKRS_ROLE_IO, zkrs_acl_view,
    zkrs_buf, zkrs_bytes, zkrs_item, zkrs_item_type, zkrs_multi_op, zkrs_outbox, zkrs_result_view,
    zkrs_sasl_call, zkrs_sasl_kind, zkrs_stat, zkrs_str, zkrs_thread_op, zkrs_watch_event_view,
    zkrs_watcher,
};

/// Internal outbox state managed behind the opaque C pointer `zkrs_outbox.impl`.
pub(crate) struct FfiOutbox {
    /// Shared reference to the owning ZooKeeper client handle.
    handle: Arc<Inner>,
    /// Underlying driver outbox queue containing completed events and notifications.
    ob: Outbox<CTokens>,
    /// Wall-clock timestamp recorded when items were adopted (seconds and microseconds).
    stamp: (i64, i32),
    /// Staging arena maintaining memory backing for the active C item view.
    stage: Staged,
}

/// Staging arena maintaining memory backing for the most recently projected FFI item view.
///
/// Pointers exposed to C across `zkrs_outbox_next` borrow directly from internal vectors and
/// boxed allocations held within this structure, ensuring defined memory lifetimes until the
/// subsequent staging call or outbox destruction. String allocations are boxed slices to prevent
/// address invalidation under vector reallocation. Node payload buffers (`ReplyData`) are retained
/// directly from wire frames without intermediate copies or NUL termination; dropping the staged
/// payload returns the underlying frame buffer to the connection memory pool.
#[derive(Default)]
pub(crate) struct Staged {
    /// Staged log message views, including standalone log items and delivery trace lines.
    pub(crate) logs: LogViews,
    /// NUL-terminated heap string copies backing string views.
    strs: Vec<Box<[u8]>>,
    /// Znode payload data for GetData responses, retained directly from the decoded wire reply.
    /// Releasing this buffer returns the backing slice to the connection buffer pool.
    data: Option<ReplyData>,
    /// Node stat records backing result views and multi-operation sub-results.
    stats: Vec<zkrs_stat>,
    /// Child node name string views backing GetChildren responses.
    children: Vec<zkrs_str>,
    /// Access control list entry views backing GetACL responses.
    acl: Vec<zkrs_acl_view>,
    /// Sub-operation outcome descriptors backing multi-transaction result views.
    ops: Vec<zkrs_multi_op>,
    /// Registered C watcher callback descriptors backing watch event notifications.
    watchers: Vec<zkrs_watcher>,
    /// Staged operation result view for the active RESULT item.
    result: Option<zkrs_result_view>,
    /// Staged watch notification view for the active WATCH item.
    watch: Option<zkrs_watch_event_view>,
    /// Raw socket address images and zeroized authentication challenge/response tokens for SASL.
    sasl_bytes: Vec<Zeroizing<Box<[u8]>>>,
    /// Staged SASL authentication directive for the active SASL item.
    sasl: Option<zkrs_sasl_call>,
}

impl Staged {
    /// Releases all staged views and memory buffers associated with the previous item.
    pub(crate) fn clear(&mut self) {
        self.logs.clear();
        self.strs.clear();
        self.data = None;
        self.stats.clear();
        self.children.clear();
        self.acl.clear();
        self.ops.clear();
        self.watchers.clear();
        self.result = None;
        self.watch = None;
        self.sasl_bytes.clear();
        self.sasl = None;
    }

    /// Allocates a NUL-terminated copy of a byte slice and returns an FFI string view borrowing
    /// from it.
    ///
    /// Storing the allocation inside a boxed slice ensures that its heap address remains stable
    /// even if the enclosing vector reallocates, preserving pointer validity under Stacked Borrows.
    fn copy(&mut self, b: &[u8]) -> zkrs_str {
        self.strs.push(raw::c_bytes(b));
        self.strs
            .last()
            .map_or(zkrs_str::null(), |c| logging::str_view(c))
    }

    /// Projects a Jute string into an FFI string view, returning a null view if the string is empty
    /// or absent.
    fn jstr(&mut self, s: &JString) -> zkrs_str {
        match s.as_bytes() {
            Some(b) => self.copy(b),
            None => zkrs_str::null(),
        }
    }

    /// Retains a znode payload buffer in the staging area and returns an FFI buffer view
    /// referencing it without copying.
    ///
    /// Null payloads yield a null pointer with length -1. Empty payloads point to a static dummy
    /// byte to provide C callers with a valid, dereferenceable non-null pointer alongside length 0.
    fn own_data(&mut self, d: ReplyData) -> zkrs_buf {
        // Empty payloads lack an allocated heap address; referencing a static byte provides C
        // callers with a valid, readable pointer.
        static EMPTY: u8 = 0;
        if d.is_null() {
            return zkrs_buf::null();
        }
        let b = self.data.insert(d).as_bytes().unwrap_or_default();
        let at = if b.is_empty() {
            ptr::from_ref(&EMPTY)
        } else {
            b.as_ptr()
        };
        zkrs_buf {
            ptr: at.cast(),
            len: count(b.len()),
        }
    }

    /// Appends a node stat record to the staging buffer and returns its index for stable pointer
    /// resolution.
    fn push_stat(&mut self, s: &Stat) -> usize {
        self.stats.push(stat_view(s));
        self.stats.len().saturating_sub(1)
    }

    /// Resolves a staging buffer index into a stable reference pointer to a stat record, returning
    /// null if unassigned.
    fn stat_at(&self, n: Option<usize>) -> *const zkrs_stat {
        n.and_then(|n| self.stats.get(n))
            .map_or(ptr::null(), ptr::from_ref)
    }

    /// Stages an FFI result view for an asynchronous operation completion item.
    ///
    /// The `failed` flag indicates that no decoded reply body accompanied the outcome.
    /// Present components (payload data, stats, paths, children, ACLs, or sub-operations) are
    /// projected into corresponding FFI view fields without redundant heap copies.
    fn stage_result(
        &mut self,
        kind: u32,
        r: &mut OpResult,
        logs: &[LogRecord],
        stamp: (i64, i32),
    ) -> *const zkrs_result_view {
        let (delivery_logs, n_delivery_logs) = self.logs.stage(logs, stamp);
        let mut v = zkrs_result_view {
            kind,
            origin: match r.origin {
                Origin::Reply => ZKRS_ORIGIN_REPLY,
                Origin::Teardown => ZKRS_ORIGIN_TEARDOWN,
            },
            rc: r.rc,
            failed: i32::from(r.reply.is_none()),
            data: zkrs_buf::null(),
            stat: ptr::null(),
            path: zkrs_str::null(),
            children: ptr::null(),
            nchildren: 0,
            acl: ptr::null(),
            nacl: 0,
            ops: ptr::null(),
            nops: 0,
            delivery_logs,
            n_delivery_logs,
        };
        let stat = match &r.reply {
            Some(
                Reply::Stat(s)
                | Reply::Data { stat: s, .. }
                | Reply::PathStat(_, s)
                | Reply::Children2(_, s)
                | Reply::Acl(_, s),
            ) => Some(self.push_stat(s)),
            _ => None,
        };
        match &mut r.reply {
            Some(Reply::Data { data, .. }) => v.data = self.own_data(std::mem::take(data)),
            Some(Reply::Path(p) | Reply::PathStat(p, _)) => v.path = self.jstr(p),
            Some(Reply::Children(c) | Reply::Children2(c, _)) => {
                (v.children, v.nchildren) = self.stage_children(c);
            }
            Some(Reply::Acl(a, _)) => (v.acl, v.nacl) = self.stage_acl(a),
            Some(Reply::Multi(items)) => (v.ops, v.nops) = self.stage_ops(items),
            // Internal protocol replies not visible to the C client layer never reach the FFI
            // stage.
            _ => {}
        }
        v.stat = self.stat_at(stat);
        ptr::from_ref(self.result.insert(v))
    }

    /// Stages an array of child znode names as FFI string views. A null vector yields a null
    /// pointer with length -1.
    fn stage_children(&mut self, c: &JVec<JString>) -> (*const zkrs_str, i32) {
        let Some(items) = c.as_slice() else {
            return (ptr::null(), -1);
        };
        for s in items {
            let v = self.jstr(s);
            self.children.push(v);
        }
        (self.children.as_ptr(), count(self.children.len()))
    }

    /// Stages access control list records into FFI ACL views. A null vector yields a null pointer
    /// with length -1.
    fn stage_acl(&mut self, a: &JVec<ACL>) -> (*const zkrs_acl_view, i32) {
        let Some(items) = a.as_slice() else {
            return (ptr::null(), -1);
        };
        for e in items {
            let scheme = self.jstr(&e.id.scheme);
            let id = self.jstr(&e.id.id);
            self.acl.push(zkrs_acl_view {
                perms: e.perms,
                scheme,
                id,
            });
        }
        (self.acl.as_ptr(), count(self.acl.len()))
    }

    /// Stages multi-operation sub-results into FFI operation outcome descriptors.
    ///
    /// Stat structures are staged first to guarantee stable pointer addresses before
    /// constructing sub-operation records.
    fn stage_ops(&mut self, items: &[MultiItem]) -> (*const zkrs_multi_op, i32) {
        let stats: Vec<Option<usize>> = items
            .iter()
            .map(|it| match &it.body {
                MultiBody::SetData(r) => Some(self.push_stat(&r.stat)),
                _ => None,
            })
            .collect();
        for (it, st) in items.iter().zip(stats) {
            let err = match &it.body {
                MultiBody::Error(e) => e.err,
                _ => it.err,
            };
            let path = match &it.body {
                MultiBody::Create(r) => self.jstr(&r.path),
                MultiBody::Create2(r) => self.jstr(&r.path),
                _ => zkrs_str::null(),
            };
            let stat = self.stat_at(st);
            self.ops.push(zkrs_multi_op {
                err,
                failed: i32::from(it.wire_type == opcode::ERROR),
                path,
                stat,
            });
        }
        (self.ops.as_ptr(), count(self.ops.len()))
    }

    /// Constructs a staged view of a watch event notification or session state transition, binding
    /// registered C watchers.
    fn stage_watch(
        &mut self,
        event: (i32, i32, zkrs_str),
        watchers: Vec<CWatcher>,
        logs: &[LogRecord],
        stamp: (i64, i32),
    ) -> *const zkrs_watch_event_view {
        let (delivery_logs, n_delivery_logs) = self.logs.stage(logs, stamp);
        self.watchers
            .extend(watchers.into_iter().map(CWatcher::view));
        let (r#type, state, path) = event;
        let v = zkrs_watch_event_view {
            r#type,
            state,
            path,
            list: self.watchers.as_ptr(),
            n: u32::try_from(self.watchers.len()).unwrap_or(0),
            delivery_logs,
            n_delivery_logs,
        };
        ptr::from_ref(self.watch.insert(v))
    }

    /// Copies a raw byte buffer into zeroizing heap storage for SASL authentication.
    ///
    /// Allocating an additional terminating byte ensures that a valid non-null pointer is provided
    /// even for empty authentication tokens.
    fn keep(&mut self, b: &[u8]) -> zkrs_bytes {
        let mut c = vec![0u8; b.len().saturating_add(1)].into_boxed_slice();
        if let Some(dst) = c.get_mut(..b.len()) {
            dst.copy_from_slice(b);
        }
        self.sasl_bytes.push(Zeroizing::new(c));
        self.sasl_bytes
            .last()
            .map_or(zkrs_bytes::null(), |c| zkrs_bytes {
                p: c.as_ptr(),
                len: u32::try_from(b.len()).unwrap_or(u32::MAX),
                is_null: 0,
            })
    }

    /// Stages a SASL authentication directive into an FFI call view.
    ///
    /// START directives carry local and remote socket addresses; STEP directives provide server
    /// authentication challenge tokens; DISPOSE signals credential cleanup.
    fn stage_sasl(&mut self, s: SaslItem) -> *const zkrs_sasl_call {
        let mut v = zkrs_sasl_call {
            kind: 0,
            r#gen: s.call.generation,
            local: zkrs_bytes::null(),
            peer: zkrs_bytes::null(),
            token: zkrs_bytes::null(),
        };
        let kind = match s.call.kind {
            SaslCallKind::Start => {
                // Driver invariants guarantee both socket addresses on START; null pointers trigger
                // immediate handshake failure.
                if let Some((local, peer)) = s.addrs {
                    v.local = self.keep(&sockaddr_image(local));
                    v.peer = self.keep(&sockaddr_image(peer));
                }
                zkrs_sasl_kind::ZKRS_SASL_START
            }
            SaslCallKind::Step(token) => {
                if let Some(t) = token {
                    v.token = self.keep(t.expose());
                }
                zkrs_sasl_kind::ZKRS_SASL_STEP
            }
            SaslCallKind::Dispose => zkrs_sasl_kind::ZKRS_SASL_DISPOSE,
        };
        v.kind = kind as u32;
        ptr::from_ref(self.sasl.insert(v))
    }

    /// Identifies whether the staged SASL directive requires an explicit client response from C,
    /// returning its phase and generation.
    fn taken_sasl(&self) -> Option<(Taken, u32)> {
        let v = self.sasl?;
        if v.kind == zkrs_sasl_kind::ZKRS_SASL_START as u32 {
            Some((Taken::Start, v.r#gen))
        } else if v.kind == zkrs_sasl_kind::ZKRS_SASL_STEP as u32 {
            Some((Taken::Step, v.r#gen))
        } else {
            None
        }
    }

    /// Projects an internal driver item into its public FFI representation, discarding internal
    /// items directly via host leaves.
    fn stage(
        &mut self,
        item: Item<CTokens>,
        stamp: (i64, i32),
        host: &dyn HostLeaves<CTokens>,
    ) -> Option<zkrs_item> {
        let mut it = zkrs_item::empty();
        match item {
            Item::Log(rec) => {
                it.r#type = zkrs_item_type::ZKRS_ITEM_LOG as u32;
                it.log = self.logs.stage(std::slice::from_ref(&rec), stamp).0;
            }
            Item::Result {
                token,
                mut result,
                delivery_logs,
            } => {
                it.r#type = zkrs_item_type::ZKRS_ITEM_RESULT as u32;
                it.result = self.stage_result(token.kind(), &mut result, &delivery_logs, stamp);
                it.token = token.into_raw();
            }
            Item::AuthResult { token, rc } => {
                // Authentication results omit response bodies and delivery trace lines.
                let mut r = OpResult {
                    rc,
                    zxid: 0,
                    origin: Origin::Reply,
                    reply: (rc == 0).then_some(Reply::Void),
                };
                it.r#type = zkrs_item_type::ZKRS_ITEM_RESULT as u32;
                it.result = self.stage_result(token.kind(), &mut r, &[], stamp);
                it.token = token.into_raw();
            }
            Item::Watch {
                event,
                watchers,
                delivery_logs,
            } => {
                let path = self.jstr(&event.path);
                it.r#type = zkrs_item_type::ZKRS_ITEM_WATCH as u32;
                it.watch = self.stage_watch(
                    (event.event_type, event.state, path),
                    watchers,
                    &delivery_logs,
                    stamp,
                );
            }
            Item::Session {
                state,
                watchers,
                delivery_logs,
            } => {
                // Session events utilize an explicit empty path string rather than a null pointer.
                let path = self.copy(b"");
                it.r#type = zkrs_item_type::ZKRS_ITEM_WATCH as u32;
                it.watch = self.stage_watch(
                    (SESSION_EVENT, state.code(), path),
                    watchers,
                    &delivery_logs,
                    stamp,
                );
            }
            Item::AuthLockpoint => {
                it.r#type = zkrs_item_type::ZKRS_ITEM_AUTH_LOCKPOINT as u32;
            }
            Item::Thread { op, role } => {
                it.r#type = zkrs_item_type::ZKRS_ITEM_THREAD as u32;
                it.thread_op = match op {
                    ThreadOp::Join => zkrs_thread_op::ZKRS_THREAD_JOIN,
                    ThreadOp::Detach => zkrs_thread_op::ZKRS_THREAD_DETACH,
                } as u32;
                it.thread_role = match role {
                    ThreadRole::Io => ZKRS_ROLE_IO,
                    ThreadRole::Dispatch => ZKRS_ROLE_DISPATCH,
                };
            }
            Item::Sasl(s) => {
                it.r#type = zkrs_item_type::ZKRS_ITEM_SASL as u32;
                it.sasl = self.stage_sasl(s);
            }
            // Internal driver events not dispatched to C clients are discarded via host leaves.
            hidden @ Item::WatchTerminated { .. } => {
                discard_item(host, hidden);
                return None;
            }
        }
        Some(it)
    }
}

/// Projects a Jute `Stat` record into the C ABI `zkrs_stat` layout.
fn stat_view(s: &Stat) -> zkrs_stat {
    zkrs_stat {
        czxid: s.czxid,
        mzxid: s.mzxid,
        ctime: s.ctime,
        mtime: s.mtime,
        version: s.version,
        cversion: s.cversion,
        aversion: s.aversion,
        ephemeralOwner: s.ephemeral_owner,
        dataLength: s.data_length,
        numChildren: s.num_children,
        pzxid: s.pzxid,
    }
}

/// Copies byte slice `b` into buffer `img` at offset `off`, ignoring out-of-bounds writes.
fn put(img: &mut [u8], off: usize, b: &[u8]) {
    let dst = off
        .checked_add(b.len())
        .and_then(|end| img.get_mut(off..end));
    if let Some(dst) = dst {
        dst.copy_from_slice(b);
    }
}

/// Writes the address family into a socket address image, including platform length headers on
/// BSD/macOS systems.
fn put_family(img: &mut [u8], family_at: usize, family: c_int) {
    let fam = libc::sa_family_t::try_from(family).unwrap_or_default();
    put(img, family_at, &fam.to_ne_bytes());
    #[cfg(any(
        target_os = "macos",
        target_os = "ios",
        target_os = "tvos",
        target_os = "watchos",
        target_os = "visionos",
        target_os = "freebsd",
        target_os = "dragonfly",
        target_os = "netbsd",
        target_os = "openbsd"
    ))]
    {
        // BSD-derived kernels (macOS, FreeBSD, etc.) include an 8-bit length header at offset 0.
        let n = u8::try_from(img.len()).unwrap_or_default();
        put(img, 0, &[n]);
    }
}

/// Synthesizes a platform-native `sockaddr_in` binary image for an IPv4 address.
fn sockaddr_in_image(a: SocketAddrV4) -> Vec<u8> {
    let mut img = vec![0u8; size_of::<libc::sockaddr_in>()];
    put_family(
        &mut img,
        offset_of!(libc::sockaddr_in, sin_family),
        libc::AF_INET,
    );
    put(
        &mut img,
        offset_of!(libc::sockaddr_in, sin_port),
        &a.port().to_be_bytes(),
    );
    put(
        &mut img,
        offset_of!(libc::sockaddr_in, sin_addr),
        &a.ip().octets(),
    );
    img
}

/// Synthesizes a platform-native `sockaddr_in6` binary image for an IPv6 address.
///
/// Flow information and scope identifiers preserve the standard library's native byte ordering.
fn sockaddr_in6_image(a: SocketAddrV6) -> Vec<u8> {
    let mut img = vec![0u8; size_of::<libc::sockaddr_in6>()];
    put_family(
        &mut img,
        offset_of!(libc::sockaddr_in6, sin6_family),
        libc::AF_INET6,
    );
    put(
        &mut img,
        offset_of!(libc::sockaddr_in6, sin6_port),
        &a.port().to_be_bytes(),
    );
    put(
        &mut img,
        offset_of!(libc::sockaddr_in6, sin6_flowinfo),
        &a.flowinfo().to_ne_bytes(),
    );
    put(
        &mut img,
        offset_of!(libc::sockaddr_in6, sin6_addr),
        &a.ip().octets(),
    );
    put(
        &mut img,
        offset_of!(libc::sockaddr_in6, sin6_scope_id),
        &a.scope_id().to_ne_bytes(),
    );
    img
}

/// Synthesizes a platform-native socket address binary image matching the target C struct.
fn sockaddr_image(a: SocketAddr) -> Vec<u8> {
    match a {
        SocketAddr::V4(a) => sockaddr_in_image(a),
        SocketAddr::V6(a) => sockaddr_in6_image(a),
    }
}

/// Converts a collection length to a 32-bit signed integer count, saturating at `i32::MAX`.
fn count(n: usize) -> i32 {
    i32::try_from(n).unwrap_or(i32::MAX)
}

impl FfiOutbox {
    /// Constructs an empty outbox instance associated with the client handle.
    ///
    /// Following initialization, multithreaded outboxes bind to the driver's dispatch queue.
    pub(crate) fn new(handle: &Arc<Inner>) -> Self {
        let ob = handle.driver().map_or_else(Outbox::new, |d| d.outbox());
        Self {
            handle: Arc::clone(handle),
            ob,
            stamp: (0, 0),
            stage: Staged::default(),
        }
    }

    /// Indicates whether the outbox must be retained across FFI calls due to pending items or an
    /// active resume tag.
    pub(crate) fn keep(&self) -> bool {
        !self.ob.is_empty() || self.ob.resume_at().is_some()
    }

    /// Records the wall-clock timestamp when items were adopted from the driver event loop.
    fn adopt(&mut self, stamp: (i64, i32)) {
        self.stamp = stamp;
    }
}

/// Executes closure `f` within the context of an outbox, retaining it behind `ob->impl` if items or
/// resume tags persist.
///
/// Operates on the existing outbox within `ob->impl` when available. If no outbox exists,
/// evaluation proceeds with a stack-allocated buffer and allocates heap memory only if
/// [`FfiOutbox::keep`] evaluates to true. Concludes by timestamping staged items. Reconciles stale
/// SASL handshake requests before and after invoking `f`.
pub(crate) fn with_outbox<R>(
    ob: *mut zkrs_outbox,
    handle: &Arc<Inner>,
    f: impl FnOnce(&mut Outbox<CTokens>) -> R,
) -> R {
    sasl::settle_unanswered(handle);
    let mut boxed = raw::outbox_take(ob);
    let mut local = None;
    let b = match boxed.as_deref_mut() {
        Some(b) => b,
        None => local.insert(FfiOutbox::new(handle)),
    };
    let r = f(&mut b.ob);
    sasl::settle_unanswered(handle);
    b.adopt(logging::now());
    if !b.keep() {
        return r;
    }
    // Retain existing heap outboxes or promote stack-allocated outboxes if items or resume points
    // require persistence.
    if let Some(b) = boxed.or_else(|| local.map(Box::new)) {
        raw::outbox_into_impl(ob, b);
    }
    r
}

/// Extracts and takes ownership of the underlying driver outbox, destroying the FFI wrapper and
/// ending view lifetimes.
///
/// Returns `None` if `ob->impl` is null.
pub(crate) fn take_driver_outbox(ob: *mut zkrs_outbox) -> Option<Outbox<CTokens>> {
    raw::outbox_take(ob).map(|b| {
        let FfiOutbox { ob, .. } = *b;
        ob
    })
}

/// Advances to and stages the next queued item from the outbox into `it`.
///
/// Returns 1 and populates `*it` upon successfully staging an item, or returns 0 when the outbox is
/// exhausted or uninitialized. Pops the next driver event item and stages corresponding views
/// (logs, completion results, watch notifications, thread directives, or SASL calls) within
/// [`Staged`]. Outstanding SASL handshake requests are registered in the active handle table.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_outbox_next(ob: *mut zkrs_outbox, it: *mut zkrs_item) -> c_int {
    ffi_guard(|| {
        let Some(b) = raw::outbox_from_impl(ob) else {
            return 0;
        };
        b.stage.clear();
        let host = Arc::clone(b.handle.host());
        while let Some(item) = b.ob.pop() {
            if let Some(v) = b.stage.stage(item, b.stamp, &*host) {
                if let Some((kind, r#gen)) = b.stage.taken_sasl() {
                    sasl::note_taken(&b.handle, kind, r#gen);
                }
                raw::write_out(it, v);
                return 1;
            }
        }
        0
    })
}

/// Concludes outbox draining, releasing internal staging buffers and resetting `impl` to null
/// unless a resume tag is active.
///
/// If an asynchronous driver resume checkpoint is pending, the empty outbox container is retained
/// behind `ob->impl` to allow subsequent inspection via `zkrs_resume`.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_outbox_finish(ob: *mut zkrs_outbox) {
    ffi_guard(|| {
        let Some(mut b) = raw::outbox_take(ob) else {
            return;
        };
        b.stage.clear();
        let host = Arc::clone(b.handle.host());
        b.ob.finish(&*host);
        if b.ob.resume_at().is_some() {
            raw::outbox_into_impl(ob, b);
        }
    })
}

/// Unwinds an interrupted outbox drain (such as when a C++ exception interrupts callback delivery),
/// returning unconsumed items to the driver queue.
///
/// Delivers unconsumed outbox items and active resume checkpoints back to the driver handle via
/// `Driver::requeue`, then deallocates the outbox wrapper. If invoked prior to full driver
/// initialization, unconsumed items are cleanly terminated via host leaves.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_outbox_requeue(ob: *mut zkrs_outbox) {
    ffi_guard(|| {
        let Some(mut b) = raw::outbox_take(ob) else {
            return;
        };
        b.stage.clear();
        let rest = b.ob.requeue();
        if rest.is_empty() && rest.resume_at().is_none() {
            return;
        }
        match b.handle.driver() {
            Some(d) => d.requeue(rest),
            None => {
                // Prior to driver initialization, unconsumed items are terminated via host leaves.
                let host = Arc::clone(b.handle.host());
                let mut tmp = Outbox::new();
                tmp.restore(rest);
                tmp.finish(&*host);
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use zk_driver::ResumeAt;
    use zk_jute::JBuffer;
    use zk_jute::data::Id;
    use zk_jute::proto::{Create2Response, ErrorResponse, SetDataResponse};
    use zk_proto::{LogSite, State, TerminateReason, WatchedEvent};

    use super::super::entry::with_handle;
    use super::super::handle::test_support::{empty_outbox, new_handle};
    use super::super::handle::zkrs_handle_release;
    use super::super::standin;
    use super::super::tokens::CReq;
    use super::super::types::{ZKRS_K_DATA, ZKRS_K_MULTI, zkrs_log_view};
    use super::*;

    fn line() -> LogRecord {
        let site = LogSite::SetServersEmpty;
        LogRecord {
            site,
            level: site.level(),
            args: vec![],
        }
    }

    fn stat(version: i32) -> Stat {
        Stat {
            version,
            ..Stat::default()
        }
    }

    /// Extracts the payload bytes from an FFI string view omitting trailing NUL, or returns `None`
    /// for null views.
    fn text(s: zkrs_str) -> Option<Vec<u8>> {
        if s.ptr.is_null() {
            return None;
        }
        let n = usize::try_from(s.len).ok()?;
        let with_nul = raw::bytes(s.ptr.cast(), n.checked_add(1)?).ok()?;
        assert_eq!(with_nul.last(), Some(&0));
        with_nul.get(..n).map(<[u8]>::to_vec)
    }

    /// Single-step convenience test helper advancing the outbox via `zkrs_outbox_next`.
    fn next(ob: &mut zkrs_outbox) -> Option<zkrs_item> {
        let mut it = zkrs_item::empty();
        (zkrs_outbox_next(ob, &raw mut it) == 1).then_some(it)
    }

    fn logs_of(p: *const zkrs_log_view, n: u32) -> usize {
        raw::array(p, i32::try_from(n).unwrap_or(0))
            .unwrap_or_default()
            .len()
    }

    #[test]
    fn a_call_with_no_item_leaves_impl_null() {
        let _rec = crate::abi::standin::recorder();
        let h = new_handle();
        let mut ob = empty_outbox();
        let n = with_handle(h, |i, _| with_outbox(&raw mut ob, i, |o| o.len()));
        assert_eq!(n, 0);
        assert!(ob.r#impl.is_null());
        zkrs_handle_release(h);
    }

    #[test]
    fn next_gives_each_item_and_skips_watch_terminated() {
        let _rec = crate::abi::standin::recorder();
        let h = new_handle();
        let mut ob = empty_outbox();
        with_handle(h, |i, _| {
            with_outbox(&raw mut ob, i, |o| {
                o.push(Item::Log(line()));
                o.push(Item::Result {
                    token: CReq::new(0x1000, ZKRS_K_DATA, standin::discard_token),
                    result: OpResult {
                        rc: 0,
                        zxid: 9,
                        origin: Origin::Reply,
                        reply: Some(Reply::Data {
                            data: JBuffer::Bytes(b"xy".to_vec()).into(),
                            stat: stat(4),
                        }),
                    },
                    delivery_logs: vec![line(), line()],
                });
                o.push(Item::Watch {
                    event: WatchedEvent {
                        event_type: 3,
                        state: 3,
                        path: JString::Bytes(b"/n".to_vec()),
                        zxid: 0,
                    },
                    watchers: vec![CWatcher { f: 5, ctx: 6 }, CWatcher { f: 7, ctx: 8 }],
                    delivery_logs: vec![line()],
                });
                o.push(Item::WatchTerminated {
                    watchers: vec![CWatcher { f: 1, ctx: 2 }],
                    reason: TerminateReason::Removed,
                });
                o.push(Item::Thread {
                    op: ThreadOp::Join,
                    role: ThreadRole::Dispatch,
                });
            });
        });
        assert!(!ob.r#impl.is_null());

        let log = next(&mut ob);
        assert_eq!(
            log.map(|it| it.r#type),
            Some(zkrs_item_type::ZKRS_ITEM_LOG as u32)
        );
        let lv = log.map(|it| raw::read_struct(it.log));
        assert_eq!(lv.map(|v| v.nargs), Some(0));

        let res = next(&mut ob);
        assert_eq!(
            res.map(|it| (it.r#type, it.token)),
            Some((zkrs_item_type::ZKRS_ITEM_RESULT as u32, 0x1000))
        );
        if let Some(v) = res.map(|it| raw::read_struct(it.result)) {
            assert_eq!((v.kind, v.origin, v.rc, v.failed), (ZKRS_K_DATA, 0, 0, 0));
            assert_eq!(v.data.len, 2);
            // Data buffer views omit NUL terminators, mirroring legacy C client memory semantics.
            assert_eq!(
                raw::bytes(v.data.ptr.cast(), 2).ok(),
                Some(b"xy".as_slice())
            );
            assert_eq!(raw::read_struct(v.stat).version, 4);
            assert!(v.path.ptr.is_null());
            assert_eq!(logs_of(v.delivery_logs, v.n_delivery_logs), 2);
        }

        let w = next(&mut ob);
        assert_eq!(
            w.map(|it| it.r#type),
            Some(zkrs_item_type::ZKRS_ITEM_WATCH as u32)
        );
        if let Some(v) = w.map(|it| raw::read_struct(it.watch)) {
            assert_eq!((v.r#type, v.state), (3, 3));
            assert_eq!(text(v.path), Some(b"/n".to_vec()));
            let list = raw::array(v.list, i32::try_from(v.n).unwrap_or(0)).unwrap_or_default();
            assert_eq!(
                list,
                [
                    zkrs_watcher { r#fn: 5, ctx: 6 },
                    zkrs_watcher { r#fn: 7, ctx: 8 }
                ]
            );
            assert_eq!(logs_of(v.delivery_logs, v.n_delivery_logs), 1);
        }

        let t = next(&mut ob);
        assert_eq!(
            t.map(|it| (it.r#type, it.thread_op, it.thread_role)),
            Some((
                zkrs_item_type::ZKRS_ITEM_THREAD as u32,
                zkrs_thread_op::ZKRS_THREAD_JOIN as u32,
                ZKRS_ROLE_DISPATCH
            ))
        );
        assert!(next(&mut ob).is_none());
        // The result token was dispatched to C; confirm that token cleanup hooks were not triggered
        // prematurely.
        assert!(standin::discarded().is_empty());
        zkrs_outbox_finish(&raw mut ob);
        assert!(ob.r#impl.is_null());
        zkrs_handle_release(h);
    }

    #[test]
    fn next_stages_multi_session_and_auth_views() {
        let _rec = crate::abi::standin::recorder();
        let h = new_handle();
        let mut ob = empty_outbox();
        with_handle(h, |i, _| {
            with_outbox(&raw mut ob, i, |o| {
                o.push(Item::Result {
                    token: CReq::new(0x2000, ZKRS_K_MULTI, standin::discard_token),
                    result: OpResult {
                        rc: -101,
                        zxid: 0,
                        origin: Origin::Reply,
                        reply: Some(Reply::Multi(vec![
                            MultiItem {
                                wire_type: 15,
                                err: 0,
                                body: MultiBody::Create2(Create2Response {
                                    path: JString::Bytes(b"/c".to_vec()),
                                    stat: stat(1),
                                }),
                            },
                            MultiItem {
                                wire_type: 5,
                                err: 0,
                                body: MultiBody::SetData(SetDataResponse { stat: stat(2) }),
                            },
                            MultiItem {
                                wire_type: opcode::ERROR,
                                err: 0,
                                body: MultiBody::Error(ErrorResponse { err: -101 }),
                            },
                        ])),
                    },
                    delivery_logs: vec![],
                });
                o.push(Item::Result {
                    token: CReq::new(0x3000, 5, standin::discard_token),
                    result: OpResult {
                        rc: 0,
                        zxid: 0,
                        origin: Origin::Reply,
                        reply: Some(Reply::Acl(
                            JVec::Items(vec![ACL {
                                perms: 31,
                                id: Id {
                                    scheme: JString::Bytes(b"world".to_vec()),
                                    id: JString::Bytes(b"anyone".to_vec()),
                                },
                            }]),
                            stat(3),
                        )),
                    },
                    delivery_logs: vec![],
                });
                o.push(Item::Result {
                    token: CReq::new(0x4000, 3, standin::discard_token),
                    result: OpResult::teardown(-4),
                    delivery_logs: vec![],
                });
                o.push(Item::Session {
                    state: State::ExpiredSession,
                    watchers: vec![CWatcher { f: 5, ctx: 6 }],
                    delivery_logs: vec![],
                });
                o.push(Item::AuthResult {
                    token: CReq::new(0x5000, 0, standin::discard_token),
                    rc: -115,
                });
                o.push(Item::AuthLockpoint);
            });
        });

        let multi = next(&mut ob).map(|it| raw::read_struct(it.result));
        if let Some(v) = multi {
            assert_eq!((v.kind, v.rc, v.failed, v.nops), (ZKRS_K_MULTI, -101, 0, 3));
            let ops = raw::array(v.ops, v.nops).unwrap_or_default();
            let got: Vec<_> = ops
                .iter()
                .map(|o| (o.err, o.failed, text(o.path), o.stat.is_null()))
                .collect();
            assert_eq!(
                got,
                vec![
                    (0, 0, Some(b"/c".to_vec()), true),
                    (0, 0, None, false),
                    (-101, 1, None, true),
                ]
            );
            assert_eq!(
                ops.get(1).map(|o| raw::read_struct(o.stat).version),
                Some(2)
            );
        }
        assert!(multi.is_some());

        let acl = next(&mut ob).map(|it| raw::read_struct(it.result));
        if let Some(v) = acl {
            assert_eq!((v.nacl, raw::read_struct(v.stat).version), (1, 3));
            let e = raw::array(v.acl, v.nacl).unwrap_or_default();
            let got: Vec<_> = e
                .iter()
                .map(|a| (a.perms, text(a.scheme), text(a.id)))
                .collect();
            assert_eq!(
                got,
                vec![(31, Some(b"world".to_vec()), Some(b"anyone".to_vec()))]
            );
        }
        assert!(acl.is_some());

        let down = next(&mut ob).map(|it| raw::read_struct(it.result));
        assert_eq!(
            down.map(|v| (v.origin, v.rc, v.failed, v.stat.is_null(), v.nchildren)),
            Some((ZKRS_ORIGIN_TEARDOWN, -4, 1, true, 0))
        );

        let sess = next(&mut ob).map(|it| raw::read_struct(it.watch));
        assert_eq!(
            sess.map(|v| (v.r#type, v.state, text(v.path), v.n)),
            Some((SESSION_EVENT, -112, Some(Vec::new()), 1))
        );

        let auth = next(&mut ob);
        assert_eq!(auth.map(|it| it.token), Some(0x5000));
        assert_eq!(
            auth.map(|it| raw::read_struct(it.result))
                .map(|v| (v.kind, v.origin, v.rc, v.failed)),
            Some((0, ZKRS_ORIGIN_REPLY, -115, 1))
        );

        assert_eq!(
            next(&mut ob).map(|it| it.r#type),
            Some(zkrs_item_type::ZKRS_ITEM_AUTH_LOCKPOINT as u32)
        );
        assert!(next(&mut ob).is_none());
        assert!(standin::discarded().is_empty());
        zkrs_outbox_finish(&raw mut ob);
        assert!(ob.r#impl.is_null());
        zkrs_handle_release(h);
    }

    #[test]
    fn finish_without_a_tag_frees_the_box_and_discards() {
        let _rec = crate::abi::standin::recorder();
        let h = new_handle();
        let mut ob = empty_outbox();
        with_handle(h, |i, _| {
            with_outbox(&raw mut ob, i, |o| {
                o.push(Item::Result {
                    token: CReq::new(0x1000, ZKRS_K_DATA, standin::discard_token),
                    result: OpResult::teardown(-4),
                    delivery_logs: vec![],
                });
            });
        });
        assert!(!ob.r#impl.is_null());
        zkrs_outbox_finish(&raw mut ob);
        assert!(ob.r#impl.is_null());
        assert_eq!(standin::discarded(), vec![0x1000]);
        // Redundant finalization and polling against a null outbox pointer are safe no-ops.
        zkrs_outbox_finish(&raw mut ob);
        assert!(next(&mut ob).is_none());
        zkrs_handle_release(h);
    }

    #[test]
    fn finish_with_a_tag_keeps_the_empty_box() {
        let _rec = crate::abi::standin::recorder();
        let h = new_handle();
        let mut ob = empty_outbox();
        with_handle(h, |i, _| {
            with_outbox(&raw mut ob, i, |o| {
                o.push(Item::Log(line()));
                o.set_resume(ResumeAt::IoStep);
            });
        });
        zkrs_outbox_finish(&raw mut ob);
        assert!(!ob.r#impl.is_null());
        let b = raw::outbox_take(&raw mut ob);
        assert_eq!(
            b.as_ref().map(|b| (b.ob.is_empty(), b.ob.resume_at())),
            Some((true, Some(ResumeAt::IoStep)))
        );
        drop(b);
        zkrs_handle_release(h);
    }

    #[test]
    fn requeue_before_the_init_discards_and_frees() {
        let _rec = crate::abi::standin::recorder();
        let h = new_handle();
        let mut ob = empty_outbox();
        with_handle(h, |i, _| {
            with_outbox(&raw mut ob, i, |o| {
                o.push(Item::Result {
                    token: CReq::new(0x1000, ZKRS_K_DATA, standin::discard_token),
                    result: OpResult::teardown(-4),
                    delivery_logs: vec![],
                });
                o.push(Item::Log(line()));
            });
        });
        zkrs_outbox_requeue(&raw mut ob);
        assert!(ob.r#impl.is_null());
        assert_eq!(standin::discarded(), vec![0x1000]);
        zkrs_handle_release(h);
    }

    /// Validates that GetData views borrow directly from decoded wire frames without redundant
    /// memory copies.
    #[test]
    fn the_data_view_points_into_the_reply_buffer() {
        let _rec = crate::abi::standin::recorder();
        let h = new_handle();
        let mut ob = empty_outbox();
        let data = vec![7u8; 4096];
        let at = data.as_ptr();
        let reply = |data: JBuffer| OpResult {
            rc: 0,
            zxid: 1,
            origin: Origin::Reply,
            reply: Some(Reply::Data {
                data: data.into(),
                stat: stat(1),
            }),
        };
        with_handle(h, |i, _| {
            with_outbox(&raw mut ob, i, |o| {
                o.push(Item::Result {
                    token: CReq::new(0x1000, ZKRS_K_DATA, standin::discard_token),
                    result: reply(JBuffer::Bytes(data)),
                    delivery_logs: vec![],
                });
                o.push(Item::Result {
                    token: CReq::new(0x1001, ZKRS_K_DATA, standin::discard_token),
                    result: reply(JBuffer::Bytes(Vec::new())),
                    delivery_logs: vec![],
                });
                o.push(Item::Result {
                    token: CReq::new(0x1002, ZKRS_K_DATA, standin::discard_token),
                    result: reply(JBuffer::Null),
                    delivery_logs: vec![],
                });
            });
        });
        let view = |ob: &mut zkrs_outbox| next(ob).map(|it| raw::read_struct(it.result).data);
        let d = view(&mut ob);
        assert_eq!(d.map(|d| (d.ptr.cast::<u8>(), d.len)), Some((at, 4096)));
        let d = view(&mut ob);
        assert_eq!(d.map(|d| (d.ptr.is_null(), d.len)), Some((false, 0)));
        let d = view(&mut ob);
        assert_eq!(d.map(|d| (d.ptr.is_null(), d.len)), Some((true, -1)));
        assert!(next(&mut ob).is_none());
        zkrs_outbox_finish(&raw mut ob);
        assert!(standin::discarded().is_empty());
        zkrs_handle_release(h);
    }

    fn sasl_item(
        generation: u32,
        kind: SaslCallKind,
        addrs: Option<(SocketAddr, SocketAddr)>,
    ) -> Item<CTokens> {
        Item::Sasl(SaslItem {
            call: zk_proto::SaslCall { generation, kind },
            addrs,
        })
    }

    /// Extracts payload bytes from an FFI SASL byte buffer view, or returns `None` for null views.
    fn sasl_bytes(v: zkrs_bytes) -> Option<Vec<u8>> {
        if v.is_null != 0 {
            assert!(v.p.is_null() && v.len == 0);
            return None;
        }
        assert!(!v.p.is_null());
        let n = usize::try_from(v.len).ok()?;
        raw::bytes(v.p, n).ok().map(<[u8]>::to_vec)
    }

    /// Advances the outbox and extracts the staged SASL call view.
    fn next_sasl(ob: &mut zkrs_outbox) -> Option<zkrs_sasl_call> {
        let it = next(ob)?;
        assert_eq!(it.r#type, zkrs_item_type::ZKRS_ITEM_SASL as u32);
        Some(raw::read_struct(it.sasl))
    }

    #[test]
    fn a_start_item_has_two_address_images() {
        let _rec = crate::abi::standin::recorder();
        let h = new_handle();
        let mut ob = empty_outbox();
        let v4: SocketAddr = "127.0.0.1:2181"
            .parse()
            .unwrap_or_else(|_| SocketAddr::from(([0; 4], 0)));
        let v6 = SocketAddr::V6(SocketAddrV6::new(
            "fe80::1".parse().unwrap_or(std::net::Ipv6Addr::UNSPECIFIED),
            0x1234,
            0x0102_0304,
            7,
        ));
        with_handle(h, |i, _| {
            with_outbox(&raw mut ob, i, |o| {
                o.push(sasl_item(3, SaslCallKind::Start, Some((v4, v6))));
            });
        });
        let c = next_sasl(&mut ob);
        assert_eq!(
            c.map(|c| (c.kind, c.r#gen)),
            Some((zkrs_sasl_kind::ZKRS_SASL_START as u32, 3))
        );
        let (local, peer, token) = c.map_or((None, None, None), |c| {
            (sasl_bytes(c.local), sasl_bytes(c.peer), sasl_bytes(c.token))
        });
        assert_eq!(token, None);
        let local = local.unwrap_or_default();
        let peer = peer.unwrap_or_default();
        assert_eq!(local.len(), size_of::<libc::sockaddr_in>());
        assert_eq!(peer.len(), size_of::<libc::sockaddr_in6>());
        assert_eq!(local.len(), 16);
        assert_eq!(peer.len(), 28);
        let fam = |img: &[u8], at: usize| {
            let n = size_of::<libc::sa_family_t>();
            img.get(at..at.saturating_add(n)).map(<[u8]>::to_vec)
        };
        let inet = libc::sa_family_t::try_from(libc::AF_INET).unwrap_or_default();
        let inet6 = libc::sa_family_t::try_from(libc::AF_INET6).unwrap_or_default();
        assert_eq!(
            fam(&local, offset_of!(libc::sockaddr_in, sin_family)),
            Some(inet.to_ne_bytes().to_vec())
        );
        assert_eq!(
            fam(&peer, offset_of!(libc::sockaddr_in6, sin6_family)),
            Some(inet6.to_ne_bytes().to_vec())
        );
        if cfg!(target_os = "macos") {
            assert_eq!((local.first(), peer.first()), (Some(&16), Some(&28)));
        }
        let at = offset_of!(libc::sockaddr_in, sin_port);
        assert_eq!(
            local.get(at..at.saturating_add(6)),
            Some(&[0x08, 0x85, 127, 0, 0, 1][..])
        );
        assert!(local.get(8..).is_some_and(|z| z.iter().all(|b| *b == 0)));
        let at = offset_of!(libc::sockaddr_in6, sin6_port);
        assert_eq!(peer.get(at..at.saturating_add(2)), Some(&[0x12, 0x34][..]));
        let at = offset_of!(libc::sockaddr_in6, sin6_flowinfo);
        assert_eq!(
            peer.get(at..at.saturating_add(4)),
            Some(&0x0102_0304u32.to_ne_bytes()[..])
        );
        let at = offset_of!(libc::sockaddr_in6, sin6_addr);
        assert_eq!(peer.get(at..at.saturating_add(2)), Some(&[0xfe, 0x80][..]));
        let at = offset_of!(libc::sockaddr_in6, sin6_scope_id);
        assert_eq!(
            peer.get(at..at.saturating_add(4)),
            Some(&7u32.to_ne_bytes()[..])
        );
        assert_eq!(next(&mut ob).map(|it| it.r#type), None);
        zkrs_outbox_finish(&raw mut ob);
        zkrs_handle_release(h);
    }

    #[test]
    fn a_step_item_has_the_token_and_a_minus_one_token_is_null() {
        let _rec = crate::abi::standin::recorder();
        let h = new_handle();
        let mut ob = empty_outbox();
        with_handle(h, |i, _| {
            with_outbox(&raw mut ob, i, |o| {
                let t = zk_proto::SecretBytes::from_slice(b"challenge");
                o.push(sasl_item(4, SaslCallKind::Step(Some(t)), None));
                o.push(sasl_item(4, SaslCallKind::Step(None), None));
                o.push(sasl_item(
                    4,
                    SaslCallKind::Step(Some(zk_proto::SecretBytes::empty())),
                    None,
                ));
                o.push(sasl_item(2, SaslCallKind::Dispose, None));
                // Verify that a START directive missing required address structures yields null
                // views and fails.
                o.push(sasl_item(5, SaslCallKind::Start, None));
            });
        });
        let views = |c: zkrs_sasl_call| {
            (
                c.kind,
                c.r#gen,
                sasl_bytes(c.local),
                sasl_bytes(c.peer),
                sasl_bytes(c.token),
            )
        };
        let step = zkrs_sasl_kind::ZKRS_SASL_STEP as u32;
        assert_eq!(
            next_sasl(&mut ob).map(views),
            Some((step, 4, None, None, Some(b"challenge".to_vec())))
        );
        assert_eq!(
            next_sasl(&mut ob).map(views),
            Some((step, 4, None, None, None))
        );
        assert_eq!(
            next_sasl(&mut ob).map(views),
            Some((step, 4, None, None, Some(Vec::new())))
        );
        assert_eq!(
            next_sasl(&mut ob).map(views),
            Some((
                zkrs_sasl_kind::ZKRS_SASL_DISPOSE as u32,
                2,
                None,
                None,
                None
            ))
        );
        assert_eq!(
            next_sasl(&mut ob).map(views),
            Some((zkrs_sasl_kind::ZKRS_SASL_START as u32, 5, None, None, None))
        );
        zkrs_outbox_finish(&raw mut ob);
        assert!(ob.r#impl.is_null());
        zkrs_handle_release(h);
    }

    /// Test fixture simulating frame decoding and buffer management from live connection feeds.
    #[allow(
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::indexing_slicing,
        clippy::arithmetic_side_effects,
        reason = "test helpers may panic"
    )]
    mod received {
        use zk_jute::proto::{GetDataResponse, ReplyHeader};
        use zk_jute::{Record, encode_frame};
        use zk_proto::Connection;
        use zk_proto::action::ConnectStart;
        use zk_proto::config::{Config, DriverMode};
        use zk_proto::hooks::{AllLevels, NoDefaultWatcher, Rng, Tokens};
        use zk_proto::request::{InputDefect, Op, PathIn, Request, Route, Submitted};
        use zk_proto::{Compat, Millis, Output};

        use super::*;
        use crate::abi::legacy::CStat;

        pub(super) struct Tk;

        impl Tokens for Tk {
            type Completion = u32;
            type Watcher = u32;
            type Auth = u32;
        }

        struct ZeroRng;

        impl Rng for ZeroRng {
            fn on_resolution(&mut self) {}
            fn random(&mut self) -> i64 {
                0
            }
            fn drand48(&mut self) -> f64 {
                0.0
            }
        }

        fn send_all(c: &mut Connection<Tk>) {
            loop {
                let mut out = Vec::new();
                let n = c.poll_transmit(&mut out, usize::MAX);
                if n == 0 {
                    return;
                }
                c.handle_transmitted(n, Millis(0));
            }
        }

        fn drain(c: &mut Connection<Tk>) -> Vec<Output<Tk>> {
            let mut outs = Vec::new();
            loop {
                if let Some(o) = c.poll_output() {
                    outs.push(o);
                    continue;
                }
                match c.take_stopped() {
                    Some(s) => c.resume(s, Millis(0)),
                    None => return outs,
                }
            }
        }

        /// Feeds a wire frame directly into the connection decoder in place, returning the address
        /// of the decoding room.
        fn read_in_place(c: &mut Connection<Tk>, frame: &[u8]) -> usize {
            assert_eq!(c.handle_read(&frame[..4], Millis(0)).frames, 0);
            let mut first = None;
            let mut at = 4;
            while at < frame.len() {
                let room = c.body_room(64 * 1024);
                first.get_or_insert(room.as_ptr() as usize);
                let n = room.len();
                room.copy_from_slice(&frame[at..at + n]);
                at += n;
                assert_eq!(c.handle_body_read(n, Millis(0)).consumed, n);
            }
            first.unwrap()
        }

        /// Constructs an active client connection simulating an established session under legacy C
        /// compatibility.
        pub(super) fn connected() -> Connection<Tk> {
            let mut c = Connection::new(
                Config::legacy(30_000),
                Compat::LEGACY_C,
                DriverMode::ST,
                Box::new(ZeroRng),
                Box::new(AllLevels),
                Box::new(NoDefaultWatcher),
                Millis(0),
            );
            c.handle_connect_started(ConnectStart::InProgress, Millis(0));
            c.handle_tcp_connected(Millis(0));
            c.handle_transport_ready(Millis(0));
            send_all(&mut c);
            let mut body = Vec::new();
            body.extend_from_slice(&0i32.to_be_bytes());
            body.extend_from_slice(&30_000i32.to_be_bytes());
            body.extend_from_slice(&0x55i64.to_be_bytes());
            body.extend_from_slice(&16i32.to_be_bytes());
            body.extend_from_slice(&[0xAB; 16]);
            body.push(0);
            let mut frame = Vec::new();
            encode_frame(&body, &mut frame);
            let _ = read_in_place(&mut c, &frame);
            let _ = drain(&mut c);
            c
        }

        /// Returns standardized node metadata for test assertions.
        pub(super) fn reply_stat() -> Stat {
            Stat {
                czxid: 1,
                mzxid: 2,
                ctime: 3,
                mtime: 4,
                version: 5,
                cversion: 6,
                aversion: 7,
                ephemeral_owner: 8,
                data_length: 9,
                num_children: 10,
                pzxid: 11,
            }
        }

        /// Dispatches a GetData request for `/a` and decodes the response, returning the completion
        /// outcome and payload memory address.
        pub(super) fn get(c: &mut Connection<Tk>, data: Option<&[u8]>) -> (OpResult, usize) {
            let req = Request {
                op: Op::GetData {
                    path: PathIn::Legacy(Some(b"/a".to_vec())),
                    watch: None,
                },
                token: 7,
                route: Route::Queued,
                defect: InputDefect::default(),
            };
            let Ok(Submitted::Queued { xid }) = c.submit(req, Millis(0)) else {
                panic!("submit");
            };
            send_all(c);
            let mut body = Vec::new();
            ReplyHeader {
                xid,
                zxid: 9,
                err: 0,
            }
            .encode(&mut body);
            GetDataResponse {
                data: data.map(<[u8]>::to_vec).into(),
                stat: reply_stat(),
            }
            .encode(&mut body);
            let mut frame = Vec::new();
            encode_frame(&body, &mut frame);
            // Payload bytes follow the reply header and encoded length field.
            let at = read_in_place(c, &frame) + 20;
            let result = drain(c)
                .into_iter()
                .find_map(|o| match o {
                    Output::Completion {
                        token: 7, result, ..
                    } => Some(result),
                    _ => None,
                })
                .expect("a completion");
            (result, at)
        }

        /// Encapsulates the output of executing C response writers on a staged GetData result.
        pub(super) struct Written {
            pub(super) view_ptr: usize,
            pub(super) value: Option<Vec<u8>>,
            pub(super) stat: CStat,
        }

        /// Stages an operation result and executes C memory writers against the projected FFI view.
        pub(super) fn write(ob: &mut zkrs_outbox, i: &Arc<Inner>, result: OpResult) -> Written {
            with_outbox(&raw mut *ob, i, |o| {
                o.push(Item::Result {
                    token: CReq::new(0x1000, ZKRS_K_DATA, standin::discard_token),
                    result,
                    delivery_logs: vec![],
                });
            });
            let it = next(ob).expect("an item");
            let view_ptr = raw::read_struct(it.result).data.ptr as usize;
            let (value, stat) = raw::tests::write_data(it.result);
            Written {
                view_ptr,
                value,
                stat,
            }
        }
    }

    /// Verifies that C response writers produce identical buffer content and node metadata across
    /// various payload sizes.
    #[test]
    fn the_writer_copies_every_received_value_exactly() {
        let _rec = crate::abi::standin::recorder();
        let h = new_handle();
        let mut ob = empty_outbox();
        let mut c = received::connected();
        let want_stat = crate::abi::legacy::CStat {
            czxid: 1,
            mzxid: 2,
            ctime: 3,
            mtime: 4,
            version: 5,
            cversion: 6,
            aversion: 7,
            ephemeral_owner: 8,
            data_length: 9,
            num_children: 10,
            pzxid: 11,
        };
        let value = |n: usize| (0..n).map(|i| (i % 251) as u8).collect::<Vec<u8>>();
        let mut in_place = 0;
        // Adjust large payload thresholds under Miri execution to bound test duration.
        let big = if cfg!(miri) { 96 * 1024 } else { 1 << 20 };
        with_handle(h, |i, _| {
            let mut cases: Vec<Option<Vec<u8>>> = [0usize, 1, 64 * 1024, big]
                .into_iter()
                .map(|n| Some(value(n)))
                .collect();
            cases.push(None);
            cases.push(Some(Vec::new()));
            // Consecutive large reads: ensure that staging a subsequent reply recycles previous
            // frame buffers properly.
            cases.extend([Some(value(big)), Some(value(big)), Some(value(big))]);
            cases.push(Some(value(64 * 1024 + 7)));
            for data in cases {
                let (r, at) = received::get(&mut c, data.as_deref());
                let w = received::write(&mut ob, i, r);
                assert_eq!(w.value, data, "len {:?}", data.as_ref().map(Vec::len));
                assert_eq!(w.stat, want_stat);
                if w.view_ptr == at {
                    in_place += 1;
                }
            }
        });
        assert!(next(&mut ob).is_none());
        zkrs_outbox_finish(&raw mut ob);
        assert!(in_place >= 2, "the large views point into the frame body");
        assert!(standin::discarded().is_empty());
        zkrs_handle_release(h);
    }
}
