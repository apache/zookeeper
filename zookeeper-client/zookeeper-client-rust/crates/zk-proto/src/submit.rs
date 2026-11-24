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

//! Outbound request validation, binary serialization, and pipeline admission.
//!
//! This module implements request submission workflows, enforcing strict C-compatible
//! validation stages (including opcode compatibility, pending queue capacity throttling,
//! connection lifecycle close gates, TTL validation, znode path syntax, session state,
//! and atomic multi-transaction scanning). Validated requests are assigned sequential
//! transaction identifiers (`xid`), serialized into wire-format Jute frames, queued onto
//! the outbound transmission buffer (`tx`), and tracked in the pending FIFO queue.

use std::borrow::Cow;
use std::cell::Cell;

use zk_jute::data::ACL;
use zk_jute::multi::{self, MultiKind};
use zk_jute::proto::{
    AddWatchRequest, CheckVersionRequest, CheckWatchesRequest, CreateRequest, CreateTTLRequest,
    DeleteRequest, ExistsRequest, GetACLRequest, GetAllChildrenNumberRequest, GetChildren2Request,
    GetChildrenRequest, GetDataRequest, GetEphemeralsRequest, ReconfigRequest,
    RemoveWatchesRequest, SetACLRequest, SetDataRequest, SyncRequest,
};
use zk_jute::rc::{
    ZBADARGUMENTS, ZCLOSING, ZINVALIDSTATE, ZMARSHALLINGERROR, ZTHROTTLEDOP, ZUNIMPLEMENTED,
};
use zk_jute::{JString, JVec, Record, opcode};

use crate::compat::{ChrootPolicy, CloseMode};
use crate::connection::Connection;
use crate::hooks::Tokens;
use crate::legacy::chroot::{CONFIG_NODE, join_component, join_legacy};
use crate::legacy::path::{is_sequence, is_ttl, validate_path_strict};
use crate::log::{LogArg, LogRecord, LogSite};
use crate::request::{
    InputDefect, MultiOp, MultiOpRef, Op, PathIn, Request, Stage, SubmitError, Submitted, check_ttl,
};
use crate::requests::{
    FrameKind, OutFrame, Pending, REQUEST_HEAD_LEN, Shape, Unwatch, WatchKind, WatchReg,
};
use crate::time::Millis;

/// Fully validated protocol operation encapsulating its serialized wire payload and metadata.
pub(crate) struct Prepared<W> {
    /// ZooKeeper wire protocol opcode identifying the operation type.
    pub(crate) op_type: i32,
    /// Serialized request body. When `framed` is true, begins with [`REQUEST_HEAD_LEN`]
    /// reserved bytes accommodating the frame length prefix and request header.
    pub(crate) body: Vec<u8>,
    /// Indicates whether `body` includes preallocated frame header placeholder bytes.
    pub(crate) framed: bool,
    /// Expected structural schema classification for the corresponding server reply.
    pub(crate) shape: Shape,
    /// Optional watch registration to activate upon successful operation completion.
    pub(crate) watch: Option<WatchReg<W>>,
    /// Pending client side watch removal state to execute upon positive server acknowledgment.
    pub(crate) unwatch: Option<Unwatch<W>>,
    /// Pre-formatted diagnostic log record capturing request submission parameters,
    /// emitted once the frame has been committed to the transmission queue.
    pub(crate) log: Option<LogRecord>,
}

/// Outcome of operation validation and pre-transmission analysis.
pub(crate) enum Checked<W> {
    /// Operation successfully validated and prepared for network dispatch to the ensemble.
    Send(Prepared<W>),
    /// Operation represented an immediate local-only watch deregistration, requiring no network
    /// roundtrip.
    Local,
}

/// Borrowed payload references passed during request submission.
///
/// Allows callers to serialize ephemeral slices directly into outbound frame buffers
/// without preliminary heap allocations. Borrow lifetimes terminate upon return from submit.
#[derive(Clone, Copy)]
pub(crate) enum Input<'b> {
    /// No external borrowed memory; operation owns all payload buffers.
    Owned,
    /// Ephemeral byte slice providing node payload data for `create` or `setData` operations.
    Data(&'b [u8]),
    /// Borrowed slice of sub-operations comprising a batched write transaction.
    Multi(&'b [MultiOpRef<'b>]),
    /// Borrowed node payload slice paired with a request body pre-encoded by the submitter prior
    /// to acquiring the driver lock. Data validation evaluates the payload slice, while the
    /// serialization step uses the pre-encoded body only when it matches the validated request.
    Early(&'b [u8], &'b Cell<Vec<u8>>),
}

impl<'b> Input<'b> {
    /// Extracts the borrowed node payload slice if the input specifies borrowed data.
    fn data(self) -> Option<&'b [u8]> {
        match self {
            Self::Data(d) | Self::Early(d, _) => Some(d),
            Self::Owned | Self::Multi(_) => None,
        }
    }

    /// Extracts the pre-encoded request body if the submitter supplied one (`Self::Early`), or
    /// returns `None` otherwise.
    fn early(self) -> Option<&'b Cell<Vec<u8>>> {
        match self {
            Self::Early(_, body) => Some(body),
            Self::Owned | Self::Data(_) | Self::Multi(_) => None,
        }
    }
}

/// Composite protocol specification tuple comprising opcode, reply shape, and optional log site.
/// Native-only protocol extensions omit legacy diagnostic log sites.
type Spec = (i32, Shape, Option<LogSite>);

/// Rejection details identifying the pipeline validation stage and legacy error code.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Rejected {
    /// Processing stage where validation or admission was halted.
    pub(crate) stage: Stage,
    /// ZooKeeper protocol return code corresponding to the failure.
    pub(crate) code: i32,
}

impl<T: Tokens> Connection<T> {
    /// Validates, serializes, and admits an owned operation into the transmission pipeline.
    ///
    /// Enforces admission gates and execution sequence:
    /// - Enforces feature enablement: native-only opcodes submitted under `compat.native_ops ==
    ///   false` fail with `Stage::NotEnabled` and `ZUNIMPLEMENTED`.
    /// - Enforces in-flight limits: if `cfg.max_pending` is configured and the FIFO has reached
    ///   capacity, submission fails with `Stage::TooManyPending` and `ZTHROTTLEDOP`.
    /// - Enforces shutdown barriers: under `CloseMode::Drain` after `begin_close`, submissions are
    ///   rejected with `Stage::Validate` and `ZCLOSING`.
    /// - Delegates to `check_op` for detailed argument, path, and state validation. Failures
    ///   return the associated token inside `SubmitError`, preventing orphan completions.
    /// - Local-only operations (`Checked::Local`) bypass network queues and return
    ///   `Submitted::Local(token)`.
    /// - Wire dispatch (`Checked::Send`) acquires the next sequential transaction identifier
    ///   (`xid`), formats the wire frame via `body_frame`, enqueues onto `tx`, appends a `Pending`
    ///   record to the FIFO, and emits audit logs. Returns `Submitted::Queued { xid }`.
    /// - Rejections preserve queue state without consuming transaction IDs.
    ///
    /// # Errors
    ///
    /// Returns a [`SubmitError`] detailing the pipeline failure stage, ZooKeeper return code, and
    /// token.
    pub fn submit(&mut self, req: Request<T>, now: Millis) -> Result<Submitted<T>, SubmitError<T>> {
        self.submit_with_data(req, None, now)
    }

    /// Validates, serializes, and admits an operation utilizing a borrowed node payload buffer.
    ///
    /// Zero-copy submission semantics:
    /// - When `data` is `Some(bytes)`, `create` and `setData` operations serialize `bytes`
    ///   directly into the outbound network frame. The `data` field of the request is bypassed,
    ///   allowing callers to pass placeholder `JBuffer::Null`.
    /// - Memory is borrowed exclusively for the duration of the method invocation, avoiding
    ///   allocations.
    /// - When `data` is `None`, or for operations without data payloads, behaves identically to
    ///   [`Self::submit`].
    /// - Follows all admission gates, validation sequences, and error reporting contracts of
    ///   [`Self::submit`].
    ///
    /// # Errors
    ///
    /// Returns a [`SubmitError`] detailing the pipeline failure stage, ZooKeeper return code, and
    /// token.
    pub fn submit_with_data(
        &mut self,
        req: Request<T>,
        data: Option<&[u8]>,
        now: Millis,
    ) -> Result<Submitted<T>, SubmitError<T>> {
        self.submit_input(req, data.map_or(Input::Owned, Input::Data), now)
    }

    /// Validates, serializes, and admits a batched write transaction utilizing borrowed
    /// sub-operation references.
    ///
    /// Zero-copy transaction semantics:
    /// - For `Op::Multi`, sub-operations are read directly from `ops`. Owned sub-op vectors in the
    ///   request are ignored, permitting callers to provide empty storage.
    /// - Node paths and payload buffers serialize directly from borrowed references into the
    ///   outbound buffer.
    /// - Sub-operation scanning, validation ordering, diagnostic logging, and error codes match
    ///   owned operations.
    /// - For non-multi operations, `ops` is ignored and behavior aligns with [`Self::submit`].
    ///
    /// # Errors
    ///
    /// Returns a [`SubmitError`] detailing the pipeline failure stage, ZooKeeper return code, and
    /// token.
    pub fn submit_multi_ref(
        &mut self,
        req: Request<T>,
        ops: &[MultiOpRef<'_>],
        now: Millis,
    ) -> Result<Submitted<T>, SubmitError<T>> {
        self.submit_input(req, Input::Multi(ops), now)
    }

    /// Validates and admits an operation carrying a request body pre-encoded by
    /// [`FrameEncoder::prepare`].
    ///
    /// Admission gates, validation ordering, error codes, transaction identifier (`xid`)
    /// assignment, and diagnostic log records are identical to [`Self::submit_with_data`]. Only
    /// the binary serialization step differs: it reuses the pre-encoded body when that body
    /// matches the validated request, and re-encodes the request body otherwise.
    ///
    /// # Errors
    ///
    /// Returns a [`SubmitError`] detailing the pipeline failure stage, ZooKeeper return code, and
    /// token.
    pub fn submit_early(
        &mut self,
        early: EarlyRequest<'_, T>,
        now: Millis,
    ) -> Result<Submitted<T>, SubmitError<T>> {
        let EarlyRequest { req, data, body } = early;
        self.submit_input(req, Input::Early(data, &body), now)
    }

    /// Returns a [`FrameEncoder`] holding a copy of this connection's configured chroot prefix
    /// and chroot policy, enabling request body serialization without access to the connection.
    #[must_use]
    pub fn frame_encoder(&self) -> FrameEncoder {
        FrameEncoder {
            chroot: self
                .cfg
                .chroot
                .as_ref()
                .and_then(JString::as_bytes)
                .map(<[u8]>::to_vec),
            policy: self.compat.chroot,
        }
    }

    /// Coordinates admission gating, validation checks, and atomic queue insertion for request
    /// submission.
    fn submit_input(
        &mut self,
        req: Request<T>,
        input: Input<'_>,
        now: Millis,
    ) -> Result<Submitted<T>, SubmitError<T>> {
        // Monotonic timestamp parameter maintains driver interface parity across pipeline entry
        // points.
        let _ = now;
        let Request {
            op,
            token,
            route,
            defect,
        } = req;
        let gate = if op.is_native_only() && !self.compat.native_ops {
            Some((Stage::NotEnabled, ZUNIMPLEMENTED))
        } else if self
            .cfg
            .max_pending
            .is_some_and(|n| self.requests.fifo.len() >= n)
        {
            Some((Stage::TooManyPending, ZTHROTTLEDOP))
        } else if self.compat.close_mode == CloseMode::Drain && self.close.closing {
            Some((Stage::Validate, ZCLOSING))
        } else {
            None
        };
        if let Some((stage, code)) = gate {
            return Err(SubmitError { stage, code, token });
        }
        let prepared = match self.check_op(op, defect, input) {
            Ok(Checked::Send(p)) => p,
            Ok(Checked::Local) => return Ok(Submitted::Local(token)),
            Err(r) => {
                return Err(SubmitError {
                    stage: r.stage,
                    code: r.code,
                    token,
                });
            }
        };
        // Transaction identifiers are allocated strictly after successful validation to avoid gaps,
        // synchronizing transmission buffer ordering with FIFO queue tracking.
        let xid = self.requests.next_xid();
        let frame = Self::body_frame(xid, prepared.op_type, prepared.body, prepared.framed);
        self.requests
            .push_tail(OutFrame::plain(FrameKind::Request { xid }, frame));
        self.requests.push_pending(Pending {
            xid,
            shape: prepared.shape,
            route,
            token,
            watch: prepared.watch,
            unwatch: prepared.unwatch,
            written: false,
        });
        // Diagnostic log records are synthesized during validation before XID assignment;
        // update the leading parameter with the assigned transaction identifier.
        let log = prepared.log.map(|mut r| {
            if let Some(first) = r.args.first_mut() {
                *first = LogArg::I64(i64::from(xid));
            }
            r
        });
        self.out.push_log(log);
        Ok(Submitted::Queued { xid })
    }

    /// Executes multi-stage semantic validation and binary encoding for a protocol operation.
    ///
    /// Enforces the canonical C client validation sequence:
    /// 1. TTL flag and duration constraints.
    /// 2. Znode path syntax, character set, and sequential node rules.
    /// 3. Session lifecycle state checks (rejecting unrecoverable sessions with `ZINVALIDSTATE`).
    /// 4. Transaction sub-operation validity and compatibility scans.
    /// 5. Watch removal verification and local deregistration.
    /// 6. Binary Jute serialization and memory allocation.
    ///
    /// Failure mapping:
    /// - Sub-operation semantic defects and serialization failures yield `Stage::Enqueue` with
    ///   `ZMARSHALLINGERROR`.
    /// - Unimplemented opcodes yield `Stage::Validate` with `ZUNIMPLEMENTED`.
    /// - Pre-admission checks yield `Stage::Validate` with respective error codes.
    /// - Local-only watch deregistrations return `Checked::Local`.
    pub(crate) fn check_op(
        &mut self,
        op: Op<T::Watcher>,
        defect: InputDefect,
        input: Input<'_>,
    ) -> Result<Checked<T::Watcher>, Rejected> {
        // Individual operation helpers execute validation rows 1 through 5 and encode payloads.
        // Input defects and closing barriers in row 6 are evaluated subsequently.
        let prepared = match op {
            Op::Create {
                path,
                data: own,
                acl,
                mode,
                ttl,
                create2,
            } => self.submit_create(
                path,
                input.data().or(own.as_bytes()),
                acl,
                (mode, ttl, create2),
                input.early(),
            ),
            Op::Delete { path, version } => self.submit_delete(path, version),
            Op::Exists { path, watch } => self.submit_exists(path, watch),
            Op::GetData { path, watch } => self.submit_get_data(path, watch),
            Op::GetConfig { watch } => self.submit_get_config(watch),
            Op::SetData {
                path,
                data: own,
                version,
            } => self.submit_set_data(
                path,
                input.data().or(own.as_bytes()),
                version,
                input.early(),
            ),
            Op::GetAcl { path } => self.submit_get_acl(path),
            Op::SetAcl { path, acl, version } => {
                self.submit_set_acl(path, acl, version, Shape::Void)
            }
            Op::SetAclStat { path, acl, version } => {
                self.submit_set_acl(path, acl, version, Shape::Stat)
            }
            Op::GetChildren {
                path,
                watch,
                with_stat,
            } => self.submit_get_children(path, watch, with_stat),
            Op::Sync { path } => self.submit_sync(path),
            Op::Reconfig {
                joining,
                leaving,
                members,
                version,
            } => self.submit_reconfig(joining, leaving, members, version),
            Op::Multi { ops } => self.submit_write_multi(ops, input),
            Op::RemoveWatches {
                path,
                wtype,
                watcher,
                local,
                all,
            } => match self.submit_remove_watches(path, wtype, watcher, local, all)? {
                Some(p) => Ok(p),
                None => return Ok(Checked::Local),
            },
            Op::AddWatch { path, mode, watch } => self.submit_add_watch(path, mode, watch),
            Op::MultiRead { ops } => self.submit_multi(ops, true),
            Op::GetEphemerals { prefix } => self.submit_get_ephemerals(prefix),
            Op::GetAllChildrenNumber { path } => self.submit_children_count(path),
            Op::WhoAmI => self.submit_who_am_i(),
        }?;
        if defect.any() || self.close.closing {
            return Err(MARSHAL);
        }
        Ok(Checked::Send(prepared))
    }

    /// Validates and encodes a node creation request, verifying TTL flags, znode syntax, and ACL
    /// specifications.
    fn submit_create(
        &self,
        path: PathIn,
        data: Option<&[u8]>,
        acl: JVec<ACL>,
        (mode, ttl, create2): (i32, i64, bool),
        early: Option<&Cell<Vec<u8>>>,
    ) -> Result<Prepared<T::Watcher>, Rejected> {
        check_ttl(mode, ttl).map_err(validate)?;
        let (default, shape, site) = if create2 {
            (opcode::CREATE2, Shape::PathStat, LogSite::SendCreate2)
        } else {
            (opcode::CREATE, Shape::Path, LogSite::SendCreate)
        };
        let log = self.submit_path_log(site, &path);
        let server = self.server_path_owned(path, mode).map_err(validate)?;
        self.submit_check_state()?;
        let spec = (create_op_type(mode, default), shape);
        let tail = if is_ttl(mode) {
            DataTail::CreateTtl {
                acl: &acl,
                flags: mode,
                ttl,
            }
        } else {
            DataTail::Create {
                acl: &acl,
                flags: mode,
            }
        };
        Self::submit_data(server, log, spec, data, tail, early)
    }

    /// Validates and serializes a node deletion request, verifying path syntax and expected
    /// version constraints.
    fn submit_delete(&self, path: PathIn, version: i32) -> Result<Prepared<T::Watcher>, Rejected> {
        let spec = (opcode::DELETE, Shape::Void, Some(LogSite::SendDelete));
        self.submit_path_op(path, spec, None, |path, _| DeleteRequest { path, version })
    }

    /// Validates and serializes an exists probe, registering interest across data and existence
    /// watch tables.
    fn submit_exists(
        &self,
        path: PathIn,
        watch: Option<T::Watcher>,
    ) -> Result<Prepared<T::Watcher>, Rejected> {
        let spec = (opcode::EXISTS, Shape::Stat, Some(LogSite::SendExists));
        let watch = Some(WatchKind::Exist).zip(watch);
        self.submit_path_op(path, spec, watch, |path, watch| ExistsRequest {
            path,
            watch,
        })
    }

    /// Validates and serializes a `getData` retrieval request, attaching optional data watch
    /// registrations.
    fn submit_get_data(
        &self,
        path: PathIn,
        watch: Option<T::Watcher>,
    ) -> Result<Prepared<T::Watcher>, Rejected> {
        let spec = (opcode::GET_DATA, Shape::Data, Some(LogSite::SendGetData));
        let watch = Some(WatchKind::Data).zip(watch);
        self.submit_path_op(path, spec, watch, |path, watch| GetDataRequest {
            path,
            watch,
        })
    }

    /// Validates and serializes a `setData` payload update against expected node version
    /// constraints.
    fn submit_set_data(
        &self,
        path: PathIn,
        data: Option<&[u8]>,
        version: i32,
        early: Option<&Cell<Vec<u8>>>,
    ) -> Result<Prepared<T::Watcher>, Rejected> {
        let log = self.submit_path_log(LogSite::SendSetData, &path);
        let server = self.submit_path_state(path)?;
        let spec = (opcode::SET_DATA, Shape::Stat);
        let tail = DataTail::SetData { version };
        Self::submit_data(server, log, spec, data, tail, early)
    }

    /// Validates and serializes a `getAcl` metadata query for the specified znode path.
    fn submit_get_acl(&self, path: PathIn) -> Result<Prepared<T::Watcher>, Rejected> {
        let spec = (opcode::GET_ACL, Shape::Acl, Some(LogSite::SendGetAcl));
        self.submit_path_op(path, spec, None, |path, _| GetACLRequest { path })
    }

    /// Validates and serializes a `setAcl` modification request, configuring expected reply
    /// schemas.
    fn submit_set_acl(
        &self,
        path: PathIn,
        acl: JVec<ACL>,
        version: i32,
        shape: Shape,
    ) -> Result<Prepared<T::Watcher>, Rejected> {
        let spec = (opcode::SET_ACL, shape, Some(LogSite::SendSetAcl));
        self.submit_path_op(path, spec, None, |path, _| SetACLRequest {
            path,
            acl,
            version,
        })
    }

    /// Validates and serializes an asynchronous `sync` barrier request for the designated path.
    fn submit_sync(&self, path: PathIn) -> Result<Prepared<T::Watcher>, Rejected> {
        let spec = (opcode::SYNC, Shape::Path, Some(LogSite::SendSync));
        self.submit_path_op(path, spec, None, |path, _| SyncRequest { path })
    }

    /// Validates and serializes a native `getEphemerals` query, scoping prefixes to the configured
    /// chroot.
    fn submit_get_ephemerals(&self, prefix: PathIn) -> Result<Prepared<T::Watcher>, Rejected> {
        let spec = (opcode::GET_EPHEMERALS, Shape::Ephemerals, None);
        self.submit_path_op(prefix, spec, None, |prefix_path, _| GetEphemeralsRequest {
            prefix_path,
        })
    }

    /// Validates and serializes a native recursive child count query for the target path.
    fn submit_children_count(&self, path: PathIn) -> Result<Prepared<T::Watcher>, Rejected> {
        let spec = (opcode::GET_ALL_CHILDREN_NUMBER, Shape::ChildrenCount, None);
        self.submit_path_op(path, spec, None, |path, _| GetAllChildrenNumberRequest {
            path,
        })
    }

    /// Validates and serializes a native connection identity (`whoAmI`) query.
    fn submit_who_am_i(&self) -> Result<Prepared<T::Watcher>, Rejected> {
        self.submit_check_state()?;
        Ok(Prepared {
            op_type: opcode::WHO_AM_I,
            body: Vec::new(),
            framed: false,
            shape: Shape::WhoAmI,
            watch: None,
            unwatch: None,
            log: None,
        })
    }

    /// Serializes a cluster configuration retrieval request targeting the canonical
    /// `/zookeeper/config` znode.
    fn submit_get_config(
        &self,
        watch: Option<T::Watcher>,
    ) -> Result<Prepared<T::Watcher>, Rejected> {
        self.submit_check_state()?;
        let log = self.log.record(LogSite::SendGetConfig, || {
            vec![
                LogArg::I64(0),
                crate::log::str_arg(CONFIG_NODE),
                LogArg::Endpoint(self.current_server()),
            ]
        });
        self.submit_encoded(
            CONFIG_NODE.to_vec(),
            log,
            (opcode::GET_DATA, Shape::Data),
            Some(WatchKind::Data).zip(watch),
            |path, watch| GetDataRequest { path, watch },
        )
    }

    /// Validates and serializes child listing queries, supporting both standard and stat-augmented
    /// (`getChildren2`) variants.
    fn submit_get_children(
        &self,
        path: PathIn,
        watch: Option<T::Watcher>,
        with_stat: bool,
    ) -> Result<Prepared<T::Watcher>, Rejected> {
        let watch = Some(WatchKind::Child).zip(watch);
        if with_stat {
            self.submit_path_op(
                path,
                (
                    opcode::GET_CHILDREN2,
                    Shape::Children2,
                    Some(LogSite::SendGetChildren2),
                ),
                watch,
                |path, watch| GetChildren2Request { path, watch },
            )
        } else {
            self.submit_path_op(
                path,
                (
                    opcode::GET_CHILDREN,
                    Shape::Children,
                    Some(LogSite::SendGetChildren),
                ),
                watch,
                |path, watch| GetChildrenRequest { path, watch },
            )
        }
    }

    /// Validates session readiness and serializes dynamic ensemble reconfiguration parameters.
    fn submit_reconfig(
        &self,
        joining: JString,
        leaving: JString,
        members: JString,
        version: i64,
    ) -> Result<Prepared<T::Watcher>, Rejected> {
        self.submit_check_state()?;
        let body = encode(&ReconfigRequest {
            joining_servers: joining,
            leaving_servers: leaving,
            new_members: members,
            cur_config_id: version,
        })?;
        Ok(Prepared {
            op_type: opcode::RECONFIG,
            body,
            framed: false,
            shape: Shape::Data,
            watch: None,
            unwatch: None,
            log: self.log.record(LogSite::SendReconfig, || {
                vec![LogArg::I64(0), LogArg::Endpoint(self.current_server())]
            }),
        })
    }

    /// Prepares an atomic multi-operation write transaction, substituting borrowed sub-operations
    /// if present.
    fn submit_write_multi(
        &mut self,
        ops: Vec<MultiOp>,
        input: Input<'_>,
    ) -> Result<Prepared<T::Watcher>, Rejected> {
        match input {
            Input::Multi(refs) => self.submit_multi(refs, false),
            Input::Owned | Input::Data(_) | Input::Early(..) => self.submit_multi(ops, false),
        }
    }

    /// Validates and serializes an atomic multi-operation transaction (`Multi` or `MultiRead`).
    /// State checks run post-scan.
    fn submit_multi<I>(&mut self, ops: I, read: bool) -> Result<Prepared<T::Watcher>, Rejected>
    where
        I: IntoIterator<Item: SubOp, IntoIter: ExactSizeIterator>,
    {
        let ops = ops.into_iter();
        let count = i64::try_from(ops.len()).unwrap_or(i64::MAX);
        let (body, op_types) = self.encode_multi(ops, read)?;
        let (op_type, kind, log) = if read {
            // MultiRead operations represent a protocol extension that omits legacy C client
            // diagnostic log sites.
            (opcode::MULTI_READ, MultiKind::MultiRead, None)
        } else {
            let log = self.log.record(LogSite::SendMulti, || {
                vec![
                    LogArg::I64(0),
                    LogArg::I64(count),
                    LogArg::Endpoint(self.current_server()),
                ]
            });
            (opcode::MULTI, MultiKind::Multi, log)
        };
        Ok(Prepared {
            op_type,
            body,
            framed: true,
            shape: Shape::Multi { op_types, kind },
            watch: None,
            unwatch: None,
            log,
        })
    }

    /// Validates and serializes a watch removal request. Local-only watch deregistrations return
    /// `None` without server interaction.
    fn submit_remove_watches(
        &mut self,
        path: PathIn,
        wtype: i32,
        watcher: Option<T::Watcher>,
        local: bool,
        all: bool,
    ) -> Result<Option<Prepared<T::Watcher>>, Rejected> {
        let server = self.server_path(&path, 0).map_err(validate)?;
        if !local {
            self.submit_check_state()?;
        }
        // In native mode, comprehensive watch removal requests targeting all watchers without a
        // specific callback reference bypass local registration checks.
        let server_only = self.compat.native_ops && !local && all && watcher.is_none();
        if !server_only {
            self.check_remove_watches(&server, wtype, watcher.as_ref(), local)
                .map_err(validate)?;
        }
        if local {
            return Ok(None);
        }
        let req_path = JString::Bytes(server.clone());
        let (op_type, body) = if all {
            let body = encode(&RemoveWatchesRequest {
                path: req_path,
                r#type: wtype,
            })?;
            (opcode::REMOVE_WATCHES, body)
        } else {
            let body = encode(&CheckWatchesRequest {
                path: req_path,
                r#type: wtype,
            })?;
            (opcode::CHECK_WATCHES, body)
        };
        Ok(Some(Prepared {
            op_type,
            body,
            framed: false,
            shape: Shape::Void,
            watch: None,
            unwatch: Some(Unwatch {
                path: server,
                wtype,
                watcher,
            }),
            log: self.submit_path_log(LogSite::SendRemoveWatches, &path),
        }))
    }

    /// Validates and serializes a native `addWatch` registration request with persistent (mode 0)
    /// or recursive (mode 1) semantics.
    fn submit_add_watch(
        &self,
        path: PathIn,
        mode: i32,
        watch: T::Watcher,
    ) -> Result<Prepared<T::Watcher>, Rejected> {
        let server = self.submit_path_state(path)?;
        let kind = match mode {
            0 => WatchKind::Persistent,
            1 => WatchKind::PersistentRecursive,
            _ => return Err(validate(ZBADARGUMENTS)),
        };
        self.submit_encoded(
            server,
            None,
            (opcode::ADD_WATCH, Shape::Void),
            Some((kind, watch)),
            |path, _| AddWatchRequest { path, mode },
        )
    }

    /// Validates and encodes a standard single-path operation, executing znode path checks and
    /// session state verification.
    fn submit_path_op<R: Record>(
        &self,
        path: PathIn,
        (op_type, shape, site): Spec,
        watch: Option<(WatchKind, T::Watcher)>,
        make: impl FnOnce(JString, bool) -> R,
    ) -> Result<Prepared<T::Watcher>, Rejected> {
        // Diagnostic log records preserve the user-facing client path prior to chroot prepending.
        let log = site.and_then(|site| self.submit_path_log(site, &path));
        let server = self.submit_path_state(path)?;
        self.submit_encoded(server, log, (op_type, shape), watch, make)
    }

    /// Serializes a validated request record into a framed wire buffer, arming server-path watch
    /// registrations.
    fn submit_encoded<R: Record>(
        &self,
        server: Vec<u8>,
        log: Option<LogRecord>,
        (op_type, shape): (i32, Shape),
        watch: Option<(WatchKind, T::Watcher)>,
        make: impl FnOnce(JString, bool) -> R,
    ) -> Result<Prepared<T::Watcher>, Rejected> {
        let watch = watch.map(|(kind, watcher)| WatchReg {
            kind,
            path: server.clone(),
            watcher,
        });
        let body = encode_framed(&make(JString::Bytes(server), watch.is_some()))?;
        Ok(Prepared {
            op_type,
            body,
            framed: true,
            shape,
            watch,
            unwatch: None,
            log,
        })
    }

    /// Serializes `create` or `setData` records directly from borrowed payload slices without
    /// intermediate copies.
    ///
    /// A pre-encoded `early` body substitutes for serialization when `early_fits` confirms that
    /// it carries the validated server path and matches the expected frame length; otherwise (when
    /// `early` is `None` or `early_fits` fails), the record is serialized anew. Because the
    /// submitter constructed the buffer from the same request, all remaining record fields are
    /// guaranteed to match.
    fn submit_data(
        server: Vec<u8>,
        log: Option<LogRecord>,
        (op_type, shape): (i32, Shape),
        data: Option<&[u8]>,
        tail: DataTail<'_>,
        early: Option<&Cell<Vec<u8>>>,
    ) -> Result<Prepared<T::Watcher>, Rejected> {
        let record = DataRecord {
            path: Some(&server),
            data,
            tail,
        };
        let len = record.encoded_len();
        let body = match early.map(Cell::take) {
            Some(b) if early_fits(&b, len, &server) => b,
            _ => framed_with(len, |out| record.encode(out))?,
        };
        Ok(Prepared {
            op_type,
            body,
            framed: true,
            shape,
            watch: None,
            unwatch: None,
            log,
        })
    }

    /// Prepends configured chroot prefixes to a request path and executes structural validation.
    ///
    /// Validation and chroot join semantics:
    /// - Rejects `PathIn::Legacy(None)` with `ZBADARGUMENTS`.
    /// - Under `ChrootPolicy::LegacyConcatNoEscape`, delegates to `legacy::chroot::join_legacy`
    ///   with creation `mode` validation.
    /// - Under `ChrootPolicy::Component`, validates strict UTF-8 path syntax before joining via
    ///   `legacy::chroot::join_component`.
    /// - Returns the resolved server side path byte vector.
    pub(crate) fn server_path(&self, path: &PathIn, mode: i32) -> Result<Vec<u8>, i32> {
        self.server_path_cow(path, mode).map(Cow::into_owned)
    }

    /// Produces an owned server side znode path, reusing the existing buffer if no chroot prefix
    /// was prepended.
    fn server_path_owned(&self, path: PathIn, mode: i32) -> Result<Vec<u8>, i32> {
        let joined = match self.server_path_cow(&path, mode)? {
            Cow::Owned(v) => Some(v),
            Cow::Borrowed(_) => None,
        };
        if let Some(v) = joined {
            return Ok(v);
        }
        match path {
            PathIn::Legacy(Some(bytes)) | PathIn::Native(JString::Bytes(bytes)) => Ok(bytes),
            PathIn::Legacy(None) | PathIn::Native(JString::Null) => Err(ZBADARGUMENTS),
        }
    }

    /// Evaluates path syntax and joins chroot prefixes, borrowing the input slice when unmodified.
    fn server_path_cow<'p>(&self, path: &'p PathIn, mode: i32) -> Result<Cow<'p, [u8]>, i32> {
        let raw: &[u8] = match path {
            PathIn::Legacy(Some(bytes)) => bytes,
            PathIn::Legacy(None) => return Err(ZBADARGUMENTS),
            PathIn::Native(s) => s.as_bytes().ok_or(ZBADARGUMENTS)?,
        };
        self.server_path_raw(raw, mode)
    }

    /// Executes path validation and chroot path resolution directly against raw byte slices.
    fn server_path_raw<'p>(&self, raw: &'p [u8], mode: i32) -> Result<Cow<'p, [u8]>, i32> {
        let chroot = self.cfg.chroot.as_ref().and_then(JString::as_bytes);
        join_server(chroot, self.compat.chroot, raw, mode)
    }

    /// Scans and encodes the constituent sub-operations of a transaction batch into wire format.
    ///
    /// Validation and encoding contract:
    /// - Iterates `ops` sequentially. Any sub-operation opcode outside the permitted transaction
    ///   set immediately aborts with `ZUNIMPLEMENTED` and emits `LogSite::MultiUnimplemented`.
    ///   Legacy write transactions permit opcodes `{1, 19, 2, 5, 13}`; native mode adds `15` and
    ///   `21`.
    /// - Path syntax errors, session state failures, or input defects on individual sub-operations
    ///   yield `Stage::Enqueue` with `ZMARSHALLINGERROR` after the full opcode scan completes.
    /// - Unrecoverable session states reject even empty multi-transactions with `ZMARSHALLINGERROR`
    ///   to prevent synchronous callers from blocking indefinitely on uncompleted empty batches.
    /// - Returns the serialized payload buffer (prefixed with a [`REQUEST_HEAD_LEN`] header
    ///   reservation for in-place framing) alongside the sequence of sub-operation wire opcodes.
    /// - Shared between owned and borrowed sub-operation representations to guarantee identical
    ///   validation ordering and error reporting.
    pub(crate) fn encode_multi<I>(
        &mut self,
        ops: I,
        read: bool,
    ) -> Result<(Vec<u8>, Vec<i32>), Rejected>
    where
        I: IntoIterator<Item: SubOp, IntoIter: ExactSizeIterator>,
    {
        let ops = ops.into_iter();
        let mut types: Vec<i32> = Vec::new();
        if types.try_reserve_exact(ops.len()).is_err() {
            return Err(MARSHAL);
        }
        // Prepend a REQUEST_HEAD_LEN reservation for in-place framing, reusing the recycled
        // transmit buffer from a previously sent multi frame when available.
        let mut body = self.requests.take_tx_spare();
        if body.try_reserve(REQUEST_HEAD_LEN).is_err() {
            return Err(MARSHAL);
        }
        body.resize(REQUEST_HEAD_LEN, 0);
        // Matches C client behavior by scanning all sub-operations to allow subsequent unsupported
        // opcodes to trigger ZUNIMPLEMENTED.
        let mut failed = false;
        for op in ops {
            let op_type = op.op_type();
            if !op.allowed(self, read) {
                self.emit_log(LogSite::MultiUnimplemented, || {
                    vec![LogArg::I64(i64::from(op_type))]
                });
                return Err(validate(ZUNIMPLEMENTED));
            }
            types.push(op_type);
            if !failed && !op.append(self, &mut body) {
                failed = true;
            }
        }
        // Unrecoverable session states invalidate transaction submission, even when empty.
        if failed
            || self.core.state.is_unrecoverable()
            || body.try_reserve_exact(MULTI_HEADER_LEN).is_err()
        {
            return Err(MARSHAL);
        }
        multi::encode_end(&mut body);
        Ok((body, types))
    }

    /// Determines whether a sub-operation opcode is admissible within the active transaction mode.
    fn submit_multi_allowed(&self, op: &MultiOp, read: bool) -> bool {
        match op {
            MultiOp::Create { op_type, .. } => !read && self.multi_create_allowed(*op_type),
            MultiOp::Delete { .. } | MultiOp::SetData { .. } | MultiOp::Check { .. } => !read,
            MultiOp::GetData { .. } | MultiOp::GetChildren { .. } => read,
            MultiOp::Other { .. } => false,
        }
    }

    /// Evaluates whether a borrowed sub-operation reference is admissible within a write
    /// transaction.
    fn multi_ref_allowed(&self, op: &MultiOpRef<'_>, read: bool) -> bool {
        !read
            && match op {
                MultiOpRef::Create { op_type, .. } => self.multi_create_allowed(*op_type),
                MultiOpRef::Delete { .. }
                | MultiOpRef::SetData { .. }
                | MultiOpRef::Check { .. } => true,
                MultiOpRef::Other { .. } => false,
            }
    }

    /// Determines whether a specific creation opcode is permitted within a batched transaction
    /// under active compatibility.
    fn multi_create_allowed(&self, op_type: i32) -> bool {
        match op_type {
            opcode::CREATE | opcode::CREATE_CONTAINER => true,
            opcode::CREATE2 | opcode::CREATE_TTL => self.compat.native_ops,
            _ => false,
        }
    }

    /// Validates an individual multi-transaction sub-operation and serializes its header and record
    /// into the output buffer.
    fn submit_multi_append(&self, op: MultiOp, out: &mut Vec<u8>) -> bool {
        let op_type = op.op_type();
        // Sub-operation fields are moved directly into serialized records without secondary
        // copying.
        match op {
            MultiOp::Create {
                path,
                data,
                acl,
                mode,
                ttl,
                defect,
                ..
            } => {
                let Some(path) = self.multi_path(path, mode, defect) else {
                    return false;
                };
                if op_type == opcode::CREATE_TTL {
                    check_ttl(mode, ttl).is_ok()
                        && append(
                            op_type,
                            &CreateTTLRequest {
                                path,
                                data,
                                acl,
                                flags: mode,
                                ttl,
                            },
                            out,
                        )
                } else {
                    append(
                        op_type,
                        &CreateRequest {
                            path,
                            data,
                            acl,
                            flags: mode,
                        },
                        out,
                    )
                }
            }
            MultiOp::Delete {
                path,
                version,
                defect,
            } => self
                .multi_path(path, 0, defect)
                .is_some_and(|path| append(op_type, &DeleteRequest { path, version }, out)),
            MultiOp::SetData {
                path,
                data,
                version,
                defect,
            } => self.multi_path(path, 0, defect).is_some_and(|path| {
                append(
                    op_type,
                    &SetDataRequest {
                        path,
                        data,
                        version,
                    },
                    out,
                )
            }),
            MultiOp::Check {
                path,
                version,
                defect,
            } => self
                .multi_path(path, 0, defect)
                .is_some_and(|path| append(op_type, &CheckVersionRequest { path, version }, out)),
            MultiOp::GetData { path } => self
                .multi_path(path, 0, InputDefect::default())
                .is_some_and(|path| append(op_type, &GetDataRequest { path, watch: false }, out)),
            MultiOp::GetChildren { path } => self
                .multi_path(path, 0, InputDefect::default())
                .is_some_and(|path| {
                    append(op_type, &GetChildrenRequest { path, watch: false }, out)
                }),
            MultiOp::Other { .. } => false,
        }
    }

    /// Validates and serializes a borrowed sub-operation reference directly into the transaction
    /// output stream.
    fn multi_ref_append(&self, op: &MultiOpRef<'_>, out: &mut Vec<u8>) -> bool {
        let op_type = op.op_type();
        match *op {
            MultiOpRef::Create {
                path,
                data,
                ref acl,
                mode,
                ttl,
                defect,
                ..
            } => {
                let Some(server) = self.multi_ref_path(path, mode, defect) else {
                    return false;
                };
                let tail = if op_type == opcode::CREATE_TTL {
                    if check_ttl(mode, ttl).is_err() {
                        return false;
                    }
                    DataTail::CreateTtl {
                        acl,
                        flags: mode,
                        ttl,
                    }
                } else {
                    DataTail::Create { acl, flags: mode }
                };
                append_data(op_type, Some(&server), data, tail, out)
            }
            MultiOpRef::SetData {
                path,
                data,
                version,
                defect,
            } => self.multi_ref_path(path, 0, defect).is_some_and(|server| {
                append_data(
                    op_type,
                    Some(&server),
                    data,
                    DataTail::SetData { version },
                    out,
                )
            }),
            MultiOpRef::Delete {
                path,
                version,
                defect,
            }
            | MultiOpRef::Check {
                path,
                version,
                defect,
            } => self.multi_ref_path(path, 0, defect).is_some_and(|server| {
                let record = VersionRecord {
                    path: &server,
                    version,
                };
                append_with(op_type, record.encoded_len(), out, |o| record.encode(o))
            }),
            MultiOpRef::Other { .. } => false,
        }
    }

    /// Validates path syntax and state readiness for a borrowed sub-operation without unnecessary
    /// allocation.
    fn multi_ref_path<'p>(
        &self,
        path: Option<&'p [u8]>,
        mode: i32,
        defect: InputDefect,
    ) -> Option<Cow<'p, [u8]>> {
        let server = self.server_path_raw(path?, mode).ok()?;
        if self.core.state.is_unrecoverable() || defect.any() {
            return None;
        }
        Some(server)
    }

    /// Validates path syntax and session state for an owned sub-operation, returning the
    /// server side path.
    fn multi_path(&self, path: PathIn, mode: i32, defect: InputDefect) -> Option<JString> {
        let server = self.server_path_owned(path, mode).ok()?;
        if self.core.state.is_unrecoverable() || defect.any() {
            return None;
        }
        Some(JString::Bytes(server))
    }

    /// Executes path validation and session state verification for a single-path operation.
    fn submit_path_state(&self, path: PathIn) -> Result<Vec<u8>, Rejected> {
        let server = self.server_path_owned(path, 0).map_err(validate)?;
        self.submit_check_state()?;
        Ok(server)
    }

    /// Verifies that the connection session state is healthy, rejecting unrecoverable sessions with
    /// `ZINVALIDSTATE`.
    fn submit_check_state(&self) -> Result<(), Rejected> {
        if self.core.state.is_unrecoverable() {
            Err(validate(ZINVALIDSTATE))
        } else {
            Ok(())
        }
    }

    /// Generates diagnostic log records for request submission with placeholder transaction
    /// identifiers.
    fn submit_path_log(&self, site: LogSite, path: &PathIn) -> Option<LogRecord> {
        self.log.record(site, || {
            vec![
                LogArg::I64(0),
                path_arg(path),
                LogArg::Endpoint(self.current_server()),
            ]
        })
    }
}

/// Validates client znode path syntax and prepends the configured chroot prefix according to the
/// active [`ChrootPolicy`], dispatching to `join_legacy` or `join_native`.
fn join_server<'p>(
    chroot: Option<&[u8]>,
    policy: ChrootPolicy,
    raw: &'p [u8],
    mode: i32,
) -> Result<Cow<'p, [u8]>, i32> {
    match policy {
        ChrootPolicy::LegacyConcatNoEscape => join_legacy(chroot, raw, mode).map_err(|e| e.rc()),
        ChrootPolicy::Component => join_native(chroot, raw, mode),
    }
}

/// Minimum node payload byte length eligible for pre-lock serialization via
/// [`FrameEncoder::prepare`]; smaller payloads are inexpensive to serialize under the core lock.
pub const EARLY_FRAME_MIN: usize = 4 * 1024;

/// Standalone serializer that encodes `create` and `setData` request bodies without access to
/// the [`Connection`].
///
/// Multi-threaded drivers retain an encoder per client handle so submitters can serialize large
/// request bodies prior to acquiring the core lock, avoiding payload copies while the lock is
/// held. The encoder stores only the chroot prefix and chroot policy, which remain immutable
/// after connection initialization. Upon submission, the connection still verifies the
/// pre-encoded body against the validated server path before adopting it.
#[derive(Clone, Debug)]
pub struct FrameEncoder {
    /// Configured chroot prefix bytes of the connection, or `None` when no chroot is active.
    chroot: Option<Vec<u8>>,
    /// Chroot path join policy of the connection.
    policy: ChrootPolicy,
}

/// Outbound request paired with a pre-encoded wire body for admission via
/// [`Connection::submit_early`].
///
/// Constructed exclusively by [`FrameEncoder::prepare`] with private fields, guaranteeing that
/// the serialized body was derived from the accompanying request and payload slice.
pub struct EarlyRequest<'d, T: Tokens> {
    /// Outbound protocol request to validate and submit.
    req: Request<T>,
    /// Borrowed node payload byte slice associated with the request.
    data: &'d [u8],
    /// Pre-encoded request body beginning with reserved placeholder bytes for the frame length
    /// prefix and request header.
    body: Cell<Vec<u8>>,
}

impl<T: Tokens> From<EarlyRequest<'_, T>> for Request<T> {
    fn from(early: EarlyRequest<'_, T>) -> Self {
        early.req
    }
}

impl FrameEncoder {
    /// Validates and serializes the body of a large `create` or `setData` request prior to
    /// acquiring the core lock.
    ///
    /// Executes only the stateless preparation steps that are independent of mutable connection
    /// state: TTL constraint verification, znode path validation and chroot prefix resolution,
    /// and binary Jute record serialization. Returns the original [`Request`] unchanged when
    /// pre-lock encoding offers no benefit: for other operations, absent payloads or payloads
    /// smaller than [`EARLY_FRAME_MIN`], flagged input defects, or path and TTL parameters that
    /// fail validation. Subsequent submission to the connection evaluates and reports any
    /// validation or serialization error in the canonical pipeline stage order.
    ///
    /// # Errors
    ///
    /// Returns the original [`Request`] unchanged when an early request body is not produced.
    pub fn prepare<'d, T: Tokens>(
        &self,
        req: Request<T>,
        data: Option<&'d [u8]>,
    ) -> Result<EarlyRequest<'d, T>, Request<T>> {
        let Some(data) = data.filter(|d| d.len() >= EARLY_FRAME_MIN) else {
            return Err(req);
        };
        if req.defect.any() {
            return Err(req);
        }
        match self.encode(&req.op, data) {
            Some(body) => Ok(EarlyRequest {
                req,
                data,
                body: Cell::new(body),
            }),
            None => Err(req),
        }
    }

    /// Serializes the framed wire body of a `create` or `setData` request identically to the
    /// connection, returning `None` for other operations or when a validation check fails.
    fn encode<W>(&self, op: &Op<W>, data: &[u8]) -> Option<Vec<u8>> {
        let (path, mode, tail) = match op {
            Op::SetData { path, version, .. } => (path, 0, DataTail::SetData { version: *version }),
            Op::Create {
                path,
                acl,
                mode,
                ttl,
                ..
            } => {
                check_ttl(*mode, *ttl).ok()?;
                let tail = if is_ttl(*mode) {
                    DataTail::CreateTtl {
                        acl,
                        flags: *mode,
                        ttl: *ttl,
                    }
                } else {
                    DataTail::Create { acl, flags: *mode }
                };
                (path, *mode, tail)
            }
            _ => return None,
        };
        let raw: &[u8] = match path {
            PathIn::Legacy(Some(bytes)) => bytes,
            PathIn::Legacy(None) => return None,
            PathIn::Native(s) => s.as_bytes()?,
        };
        let server = join_server(self.chroot.as_deref(), self.policy, raw, mode).ok()?;
        let record = DataRecord {
            path: Some(&server),
            data: Some(data),
            tail,
        };
        framed_with(record.encoded_len(), |out| record.encode(out)).ok()
    }
}

/// Verifies whether a pre-encoded request body matches the expected framed byte length
/// (`len` plus [`REQUEST_HEAD_LEN`]) and contains the length-prefixed server path immediately
/// following the request header reservation.
fn early_fits(body: &[u8], len: usize, server: &[u8]) -> bool {
    let Some(end) = REQUEST_HEAD_LEN.checked_add(4) else {
        return false;
    };
    let Ok(n) = i32::try_from(server.len()) else {
        return false;
    };
    body.len() == len.saturating_add(REQUEST_HEAD_LEN)
        && body.get(REQUEST_HEAD_LEN..end) == Some(&n.to_be_bytes()[..])
        && body.get(end..).is_some_and(|rest| rest.starts_with(server))
}

/// Validates strict UTF-8 path syntax and prepends chroot prefixes along canonical component
/// boundaries.
fn join_native<'p>(chroot: Option<&[u8]>, raw: &'p [u8], mode: i32) -> Result<Cow<'p, [u8]>, i32> {
    let client = core::str::from_utf8(raw).map_err(|_| ZBADARGUMENTS)?;
    let sequential = is_sequence(mode);
    validate_path_strict(client, sequential).map_err(|_| ZBADARGUMENTS)?;
    let root = match chroot {
        None => None,
        Some(c) => Some(core::str::from_utf8(c).map_err(|_| ZBADARGUMENTS)?),
    };
    // Sequential node creation at root "/" under a chroot is prohibited as it would escape the
    // chroot namespace.
    let effective = root.is_some_and(|r| r.len() > 1);
    if effective && sequential && client == "/" {
        return Err(ZBADARGUMENTS);
    }
    Ok(match join_component(root, client) {
        Cow::Borrowed(b) => Cow::Borrowed(b.as_bytes()),
        Cow::Owned(o) => Cow::Owned(o.into_bytes()),
    })
}

/// Fixed byte length of a serialized `MultiHeader` structure (type, done flag, and return code).
const MULTI_HEADER_LEN: usize = 9;

/// Rejection outcome for serialization and buffer allocation failures.
const MARSHAL: Rejected = Rejected {
    stage: Stage::Enqueue,
    code: ZMARSHALLINGERROR,
};

/// Constructs a validation stage rejection pairing `Stage::Validate` with a ZooKeeper return code.
fn validate(code: i32) -> Rejected {
    Rejected {
        stage: Stage::Validate,
        code,
    }
}

/// Encodes a request record into a precisely sized buffer, returning `MARSHAL` upon allocation
/// exhaustion.
fn encode<R: Record>(record: &R) -> Result<Vec<u8>, Rejected> {
    let mut body = Vec::new();
    if body.try_reserve_exact(record.encoded_len()).is_err() {
        return Err(MARSHAL);
    }
    record.encode(&mut body);
    Ok(body)
}

/// Encodes a request record following a preallocated header placeholder, avoiding redundant copies
/// upon framing.
fn encode_framed<R: Record>(record: &R) -> Result<Vec<u8>, Rejected> {
    framed_with(record.encoded_len(), |out| record.encode(out))
}

/// Allocates a transmission frame buffer sized to contain header placeholders alongside the payload
/// record.
fn framed_with(len: usize, encode: impl FnOnce(&mut Vec<u8>)) -> Result<Vec<u8>, Rejected> {
    let mut frame = Vec::new();
    if frame
        .try_reserve_exact(len.saturating_add(REQUEST_HEAD_LEN))
        .is_err()
    {
        return Err(MARSHAL);
    }
    frame.resize(REQUEST_HEAD_LEN, 0);
    encode(&mut frame);
    Ok(frame)
}

/// Jute serializable view for `create` and `setData` operations referencing unowned payload memory
/// slices directly.
struct DataRecord<'a> {
    /// Target znode path byte slice, or `None` representing a Jute null string.
    path: Option<&'a [u8]>,
    /// Ephemeral node payload byte slice, or `None` representing a Jute null buffer.
    data: Option<&'a [u8]>,
    /// Operation-specific suffix fields (e.g. version constraints, ACL sets, flags, and TTL
    /// durations).
    tail: DataTail<'a>,
}

/// Trailing operation-specific fields appended after binary payload data.
enum DataTail<'a> {
    /// Suffix fields for `setData` operations capturing target data version.
    SetData { version: i32 },
    /// Suffix fields for standard `create` operations capturing ACL lists and creation flags.
    Create { acl: &'a JVec<ACL>, flags: i32 },
    /// Suffix fields for TTL `create` operations capturing ACL lists, creation flags, and
    /// expiration intervals.
    CreateTtl {
        acl: &'a JVec<ACL>,
        flags: i32,
        ttl: i64,
    },
}

impl DataRecord<'_> {
    /// Computes the precise serialized wire byte count produced by [`Self::encode`].
    fn encoded_len(&self) -> usize {
        let tail = match &self.tail {
            DataTail::SetData { version } => version.encoded_len(),
            DataTail::Create { acl, flags } => {
                acl.encoded_len().saturating_add(flags.encoded_len())
            }
            DataTail::CreateTtl { acl, flags, ttl } => acl
                .encoded_len()
                .saturating_add(flags.encoded_len())
                .saturating_add(ttl.encoded_len()),
        };
        wire_len(self.path)
            .saturating_add(wire_len(self.data))
            .saturating_add(tail)
    }

    /// Appends serialized fields to the destination buffer adhering to standard Jute record order.
    fn encode(&self, out: &mut Vec<u8>) {
        put_wire(self.path, out);
        put_wire(self.data, out);
        match &self.tail {
            DataTail::SetData { version } => version.encode(out),
            DataTail::Create { acl, flags } => {
                acl.encode(out);
                flags.encode(out);
            }
            DataTail::CreateTtl { acl, flags, ttl } => {
                acl.encode(out);
                flags.encode(out);
                ttl.encode(out);
            }
        }
    }
}

/// Jute serializable view for version-conditioned operations (`delete` and `checkVersion`)
/// borrowing path bytes.
struct VersionRecord<'a> {
    /// Target server side znode path byte slice.
    path: &'a [u8],
    /// Expected node state version constraint.
    version: i32,
}

impl VersionRecord<'_> {
    /// Computes the exact serialized wire byte count produced by [`Self::encode`].
    fn encoded_len(&self) -> usize {
        wire_len(Some(self.path)).saturating_add(self.version.encoded_len())
    }

    /// Serializes path and version fields directly to the target buffer.
    fn encode(&self, out: &mut Vec<u8>) {
        put_wire(Some(self.path), out);
        self.version.encode(out);
    }
}

/// Computes the wire length of a Jute string or buffer, representing `None` as a 4-byte negative
/// length indicator.
fn wire_len(b: Option<&[u8]>) -> usize {
    b.map_or(4, |b| wire_buffer(b).1.len().saturating_add(4))
}

/// Serializes length-prefixed bytes into the buffer, encoding `None` as `-1i32`.
fn put_wire(b: Option<&[u8]>, out: &mut Vec<u8>) {
    let (len, bytes) = b.map_or((-1, &[][..]), wire_buffer);
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(bytes);
}

/// Truncates buffer lengths exceeding `i32::MAX` to maintain strict Jute integer range
/// compatibility.
fn wire_buffer(b: &[u8]) -> (i32, &[u8]) {
    let len = i32::try_from(b.len()).unwrap_or(i32::MAX);
    let n = usize::try_from(len).unwrap_or(0);
    (len, b.get(..n).unwrap_or(b))
}

/// Appends the sub-operation header and serialized record, returning `false` if memory allocation
/// fails.
fn append<R: Record>(op_type: i32, record: &R, out: &mut Vec<u8>) -> bool {
    append_with(op_type, record.encoded_len(), out, |o| record.encode(o))
}

/// Encodes a multi-operation header and invokes a closure to serialize the payload into the
/// reserved buffer space.
fn append_with(
    op_type: i32,
    len: usize,
    out: &mut Vec<u8>,
    encode: impl FnOnce(&mut Vec<u8>),
) -> bool {
    if out
        .try_reserve(len.saturating_add(MULTI_HEADER_LEN))
        .is_err()
    {
        return false;
    }
    multi::encode_op_header(op_type, out);
    encode(out);
    true
}

/// Serializes the sub-operation header and borrowed data payload into a transaction buffer.
fn append_data(
    op_type: i32,
    path: Option<&[u8]>,
    data: Option<&[u8]>,
    tail: DataTail<'_>,
    out: &mut Vec<u8>,
) -> bool {
    let record = DataRecord { path, data, tail };
    append_with(op_type, record.encoded_len(), out, |o| record.encode(o))
}

/// Uniform abstraction unifying owned and borrowed sub-operations within transaction encoding
/// pipelines.
pub(crate) trait SubOp {
    /// Retrieves the protocol wire opcode of the sub-operation.
    fn op_type(&self) -> i32;
    /// Verifies whether the sub-operation is permitted under the active connection profile.
    fn allowed<T: Tokens>(&self, conn: &Connection<T>, read: bool) -> bool;
    /// Serializes the sub-operation into the target buffer, returning `false` on check or
    /// allocation failure.
    fn append<T: Tokens>(self, conn: &Connection<T>, out: &mut Vec<u8>) -> bool;
}

impl SubOp for MultiOp {
    fn op_type(&self) -> i32 {
        Self::op_type(self)
    }

    fn allowed<T: Tokens>(&self, conn: &Connection<T>, read: bool) -> bool {
        conn.submit_multi_allowed(self, read)
    }

    fn append<T: Tokens>(self, conn: &Connection<T>, out: &mut Vec<u8>) -> bool {
        conn.submit_multi_append(self, out)
    }
}

impl SubOp for &MultiOpRef<'_> {
    fn op_type(&self) -> i32 {
        MultiOpRef::op_type(self)
    }

    fn allowed<T: Tokens>(&self, conn: &Connection<T>, read: bool) -> bool {
        conn.multi_ref_allowed(self, read)
    }

    fn append<T: Tokens>(self, conn: &Connection<T>, out: &mut Vec<u8>) -> bool {
        conn.multi_ref_append(self, out)
    }
}

/// Resolves the creation wire opcode according to mode flags, matching C client
/// `get_create_op_type`.
fn create_op_type(mode: i32, default: i32) -> i32 {
    if mode == CONTAINER_MODE {
        opcode::CREATE_CONTAINER
    } else if is_ttl(mode) {
        opcode::CREATE_TTL
    } else {
        default
    }
}

/// Numerical flag value for container nodes (`ZOO_CONTAINER`).
const CONTAINER_MODE: i32 = 4;

/// Formats the original client-provided path for diagnostic log entries prior to chroot resolution.
fn path_arg(path: &PathIn) -> LogArg {
    match path {
        PathIn::Legacy(Some(bytes)) => crate::log::str_arg(bytes),
        PathIn::Legacy(None) => LogArg::Str(JString::Null),
        PathIn::Native(s) => LogArg::Str(s.clone()),
    }
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::indexing_slicing,
    clippy::panic,
    clippy::large_stack_frames
)]
mod tests {
    use super::*;
    use crate::compat::Compat;
    use crate::config::{Config, DriverMode};
    use crate::hooks::{AllLevels, NoDefaultWatcher, Rng};
    use crate::output::Output;
    use crate::request::Route;
    use crate::state::State;
    use zk_jute::JBuffer;

    struct Tk;
    impl Tokens for Tk {
        type Completion = u32;
        type Watcher = u32;
        type Auth = u32;
    }

    struct FixedRng;
    impl Rng for FixedRng {
        fn on_resolution(&mut self) {}
        fn random(&mut self) -> i64 {
            0
        }
        fn drand48(&mut self) -> f64 {
            0.0
        }
    }

    fn conn_with(cfg: Config, compat: Compat) -> Connection<Tk> {
        Connection::new(
            cfg,
            compat,
            DriverMode::ST,
            Box::new(FixedRng),
            Box::new(AllLevels),
            Box::new(NoDefaultWatcher),
            Millis(0),
        )
    }

    fn legacy() -> Connection<Tk> {
        conn_with(Config::legacy(30_000), Compat::LEGACY_C)
    }

    fn chrooted(compat: Compat) -> Connection<Tk> {
        let mut cfg = Config::legacy(30_000);
        cfg.chroot = Some(JString::from("/app"));
        conn_with(cfg, compat)
    }

    fn lp(p: &str) -> PathIn {
        PathIn::Legacy(Some(p.as_bytes().to_vec()))
    }

    fn np(p: &str) -> PathIn {
        PathIn::Native(JString::from(p))
    }

    const DEFECT: InputDefect = InputDefect {
        bad_value_buffer: true,
        bad_acl: false,
        marshal_oom: false,
    };

    fn create(path: PathIn, mode: i32, ttl: i64) -> Op<u32> {
        Op::Create {
            path,
            data: JBuffer::Bytes(vec![1]),
            acl: JVec::from(Vec::new()),
            mode,
            ttl,
            create2: false,
        }
    }

    fn req(op: Op<u32>, defect: InputDefect) -> Request<Tk> {
        Request {
            op,
            token: 7,
            route: Route::Queued,
            defect,
        }
    }

    /// Test utility submitting a request and asserting rejection, returning the failure stage and
    /// error code.
    fn fails(c: &mut Connection<Tk>, op: Op<u32>, defect: InputDefect) -> (Stage, i32) {
        match c.submit(req(op, defect), Millis(0)) {
            Err(e) => {
                assert_eq!(e.token, 7);
                (e.stage, e.code)
            }
            Ok(s) => panic!("submit passed: {s:?}"),
        }
    }

    fn queued(c: &mut Connection<Tk>, op: Op<u32>) -> i32 {
        match c.submit(req(op, InputDefect::default()), Millis(0)) {
            Ok(Submitted::Queued { xid }) => xid,
            Ok(Submitted::Local(_)) => panic!("local"),
            Err(e) => panic!("submit failed: {e:?}"),
        }
    }

    fn logs(c: &mut Connection<Tk>) -> Vec<LogRecord> {
        let mut v = Vec::new();
        while let Some(o) = c.poll_output() {
            if let Output::Log(r) = o {
                v.push(r);
            }
        }
        v
    }

    fn assert_untouched(c: &Connection<Tk>) {
        assert!(c.requests.tx.is_empty());
        assert!(c.requests.fifo.is_empty());
    }

    fn mdelete(path: PathIn) -> MultiOp {
        MultiOp::Delete {
            path,
            version: -1,
            defect: InputDefect::default(),
        }
    }

    fn mcheck(path: PathIn) -> MultiOp {
        MultiOp::Check {
            path,
            version: 3,
            defect: InputDefect::default(),
        }
    }

    const VALIDATE_BAD: (Stage, i32) = (Stage::Validate, ZBADARGUMENTS);
    const VALIDATE_STATE: (Stage, i32) = (Stage::Validate, ZINVALIDSTATE);
    const ENQUEUE: (Stage, i32) = (Stage::Enqueue, ZMARSHALLINGERROR);

    #[test]
    fn row1_ttl_wins_over_state_and_serialization() {
        let mut c = legacy();
        c.set_state(State::ExpiredSession);
        c.close.closing = true;
        assert_eq!(fails(&mut c, create(lp("/a"), 0, 0), DEFECT), VALIDATE_BAD);
        assert_eq!(fails(&mut c, create(lp("/a"), 5, 0), DEFECT), VALIDATE_BAD);
        assert_untouched(&c);
    }

    #[test]
    fn row2_path_wins_over_state_and_serialization() {
        let mut c = legacy();
        c.set_state(State::ExpiredSession);
        c.close.closing = true;
        let get = |path| Op::GetData { path, watch: None };
        assert_eq!(
            fails(&mut c, get(PathIn::Legacy(None)), DEFECT),
            VALIDATE_BAD
        );
        assert_eq!(fails(&mut c, get(lp("a")), DEFECT), VALIDATE_BAD);
        assert_eq!(fails(&mut c, get(lp("/a/")), DEFECT), VALIDATE_BAD);
        // Trailing slashes are permitted for sequential node creation, advancing validation to row
        // 3 (session state check).
        assert_eq!(
            fails(&mut c, create(lp("/a/"), 2, -1), DEFECT),
            VALIDATE_STATE
        );
        assert_untouched(&c);
    }

    #[test]
    fn row3_state_wins_over_serialization() {
        let mut c = legacy();
        c.set_state(State::AuthFailed);
        c.close.closing = true;
        let ops = [
            Op::Sync { path: lp("/a") },
            Op::GetConfig { watch: None },
            Op::Reconfig {
                joining: JString::Null,
                leaving: JString::Null,
                members: JString::Null,
                version: -1,
            },
            Op::RemoveWatches {
                path: lp("/a"),
                wtype: 3,
                watcher: None,
                local: false,
                all: false,
            },
        ];
        for op in ops {
            assert_eq!(fails(&mut c, op, DEFECT), VALIDATE_STATE);
        }
        assert_untouched(&c);
    }

    #[test]
    fn row5_runs_after_path_and_state() {
        // Path syntax and session state checks take precedence over watch removal validation.
        let mut c = legacy();
        let rw = |path, local| Op::RemoveWatches {
            path,
            wtype: 1,
            watcher: Some(3),
            local,
            all: true,
        };
        assert_eq!(fails(&mut c, rw(lp("/a/"), true), DEFECT), VALIDATE_BAD);
        c.set_state(State::ExpiredSession);
        assert_eq!(fails(&mut c, rw(lp("/a"), false), DEFECT), VALIDATE_STATE);
        assert_untouched(&c);
    }

    #[test]
    fn row4_unimplemented_wins_over_earlier_and_later_rows() {
        let mut c = legacy();
        c.set_state(State::ExpiredSession);
        c.close.closing = true;
        let ops = vec![
            mdelete(PathIn::Legacy(None)),
            MultiOp::Other { op_type: 4 },
            MultiOp::Other { op_type: 99 },
        ];
        assert_eq!(
            fails(&mut c, Op::Multi { ops }, DEFECT),
            (Stage::Validate, ZUNIMPLEMENTED)
        );
        let logs = logs(&mut c);
        assert_eq!(logs.len(), 1);
        assert_eq!(logs[0].site, LogSite::MultiUnimplemented);
        assert_eq!(logs[0].args, [LogArg::I64(4)]);
        assert_untouched(&c);
    }

    #[test]
    fn row4_sub_op_failures_are_enqueue_errors() {
        let mut c = legacy();
        let bad_path = vec![mdelete(lp("/a")), mcheck(PathIn::Legacy(None))];
        assert_eq!(
            fails(&mut c, Op::Multi { ops: bad_path }, InputDefect::default()),
            ENQUEUE
        );
        let defect = vec![MultiOp::SetData {
            path: lp("/a"),
            data: JBuffer::Null,
            version: 0,
            defect: DEFECT,
        }];
        assert_eq!(
            fails(&mut c, Op::Multi { ops: defect }, InputDefect::default()),
            ENQUEUE
        );
        // Operations containing valid sub-operations but accompanied by an input defect fail
        // during serialization (row 6).
        let ok = vec![mcheck(lp("/a"))];
        assert_eq!(
            fails(&mut c, Op::Multi { ops: ok.clone() }, DEFECT),
            ENQUEUE
        );
        c.set_state(State::ExpiredSession);
        assert_eq!(
            fails(&mut c, Op::Multi { ops: ok }, InputDefect::default()),
            ENQUEUE
        );
        assert!(logs(&mut c).is_empty());
        assert_untouched(&c);
        // An empty multi-operation transaction still validates session state to prevent loss of
        // completions.
        assert_eq!(
            fails(
                &mut c,
                Op::Multi { ops: Vec::new() },
                InputDefect::default()
            ),
            ENQUEUE
        );
        assert_untouched(&c);
        c.set_state(State::Connected);
        assert_eq!(queued(&mut c, Op::Multi { ops: Vec::new() }), 1);
    }

    #[test]
    fn row6_input_and_close_failures() {
        let mut c = legacy();
        let acl = InputDefect {
            bad_acl: true,
            ..InputDefect::default()
        };
        let set_acl = Op::SetAcl {
            path: lp("/a"),
            acl: JVec::Null,
            version: 0,
        };
        assert_eq!(fails(&mut c, set_acl, acl), ENQUEUE);
        c.close.closing = true;
        let op = Op::Delete {
            path: lp("/a"),
            version: 0,
        };
        assert_eq!(fails(&mut c, op, InputDefect::default()), ENQUEUE);
        assert_untouched(&c);
    }

    #[test]
    fn submit_gates_run_before_check_op() {
        let mut c = legacy();
        let who = fails(&mut c, Op::WhoAmI, InputDefect::default());
        assert_eq!(who, (Stage::NotEnabled, ZUNIMPLEMENTED));
        let add = Op::AddWatch {
            path: PathIn::Legacy(None),
            mode: 0,
            watch: 1,
        };
        assert_eq!(
            fails(&mut c, add, DEFECT),
            (Stage::NotEnabled, ZUNIMPLEMENTED)
        );

        let mut cfg = Config::legacy(30_000);
        cfg.max_pending = Some(1);
        let mut c = conn_with(cfg, Compat::LEGACY_C);
        assert_eq!(queued(&mut c, Op::Sync { path: lp("/a") }), 1);
        let sync = Op::Sync {
            path: PathIn::Legacy(None),
        };
        assert_eq!(
            fails(&mut c, sync, DEFECT),
            (Stage::TooManyPending, ZTHROTTLEDOP)
        );

        let mut compat = Compat::LEGACY_C;
        compat.close_mode = CloseMode::Drain;
        let mut c = conn_with(Config::legacy(30_000), compat);
        c.close.closing = true;
        let op = create(lp("/a"), 0, 0);
        assert_eq!(fails(&mut c, op, DEFECT), (Stage::Validate, ZCLOSING));
        assert_untouched(&c);
    }

    #[test]
    fn queued_request_has_frame_entry_and_log() {
        let mut c = chrooted(Compat::LEGACY_C);
        let op = Op::GetData {
            path: lp("/a"),
            watch: Some(5),
        };
        assert_eq!(queued(&mut c, op), 1);
        let body = GetDataRequest {
            path: JString::from("/app/a"),
            watch: true,
        }
        .to_bytes();
        let f = &c.requests.tx[0];
        assert_eq!(f.kind, FrameKind::Request { xid: 1 });
        assert_eq!(
            f.bytes.as_slice(),
            Connection::<Tk>::request_frame(1, opcode::GET_DATA, &body)
        );
        let p = &c.requests.fifo[0];
        assert_eq!((p.xid, p.token), (1, 7));
        assert_eq!(p.shape, Shape::Data);
        assert!(!p.written);
        let w = p.watch.as_ref().unwrap();
        assert_eq!(
            (w.kind, w.path.as_slice(), w.watcher),
            (WatchKind::Data, &b"/app/a"[..], 5)
        );
        let logs = logs(&mut c);
        assert_eq!(logs.len(), 1);
        assert_eq!(logs[0].site, LogSite::SendGetData);
        assert_eq!(logs[0].args[0], LogArg::I64(1));
        assert_eq!(logs[0].args[1], LogArg::Str(JString::from("/a")));
    }

    #[test]
    fn op_types_and_shapes() {
        let mut c = chrooted(Compat::LEGACY_C);
        let mut check = |op: Op<u32>, op_type: i32, shape: Shape| match c.check_op(
            op,
            InputDefect::default(),
            Input::Owned,
        ) {
            Ok(Checked::Send(p)) => {
                assert_eq!((p.op_type, &p.shape), (op_type, &shape));
                // Framed request bodies reserve a leading header placeholder region.
                p.body[usize::from(p.framed) * REQUEST_HEAD_LEN..].to_vec()
            }
            _ => panic!("check_op failed"),
        };
        check(create(lp("/a"), 0, -1), opcode::CREATE, Shape::Path);
        check(
            create(lp("/a"), 4, -1),
            opcode::CREATE_CONTAINER,
            Shape::Path,
        );
        check(create(lp("/a"), 5, 10), opcode::CREATE_TTL, Shape::Path);
        let c2 = Op::Create {
            path: lp("/a"),
            data: JBuffer::Null,
            acl: JVec::Null,
            mode: 0,
            ttl: -1,
            create2: true,
        };
        check(c2, opcode::CREATE2, Shape::PathStat);
        let e = Op::Exists {
            path: lp("/a"),
            watch: None,
        };
        check(e, opcode::EXISTS, Shape::Stat);
        check(Op::Sync { path: lp("/a") }, opcode::SYNC, Shape::Path);
        check(Op::GetAcl { path: lp("/a") }, opcode::GET_ACL, Shape::Acl);
        let d = Op::Delete {
            path: lp("/a"),
            version: 1,
        };
        check(d, opcode::DELETE, Shape::Void);
        let s = Op::SetData {
            path: lp("/a"),
            data: JBuffer::Null,
            version: 1,
        };
        check(s, opcode::SET_DATA, Shape::Stat);
        let a = Op::SetAcl {
            path: lp("/a"),
            acl: JVec::Null,
            version: 1,
        };
        check(a, opcode::SET_ACL, Shape::Void);
        let g = Op::GetChildren {
            path: lp("/a"),
            watch: None,
            with_stat: true,
        };
        check(g, opcode::GET_CHILDREN2, Shape::Children2);
        let g = Op::GetChildren {
            path: lp("/a"),
            watch: None,
            with_stat: false,
        };
        check(g, opcode::GET_CHILDREN, Shape::Children);
        let r = Op::Reconfig {
            joining: JString::Null,
            leaving: JString::Null,
            members: JString::Null,
            version: -1,
        };
        check(r, opcode::RECONFIG, Shape::Data);
        // Canonical cluster configuration paths (/zookeeper/config) bypass chroot prefixing.
        let body = check(Op::GetConfig { watch: None }, opcode::GET_DATA, Shape::Data);
        let want = GetDataRequest {
            path: JString::Bytes(CONFIG_NODE.to_vec()),
            watch: false,
        };
        assert_eq!(body, want.to_bytes());
    }

    #[test]
    fn native_ops_and_shapes() {
        let mut c = chrooted(Compat::NATIVE);
        let add = Op::AddWatch {
            path: np("/a"),
            mode: 1,
            watch: 4,
        };
        assert_eq!(queued(&mut c, add), 1);
        let w = c.requests.fifo[0].watch.as_ref().unwrap();
        assert_eq!(
            (w.kind, w.path.as_slice()),
            (WatchKind::PersistentRecursive, &b"/app/a"[..])
        );
        let bad = Op::AddWatch {
            path: np("/a"),
            mode: 2,
            watch: 4,
        };
        assert_eq!(fails(&mut c, bad, InputDefect::default()), VALIDATE_BAD);
        // Standard setACL omits response body decoding, while `SetAclStat` extracts the updated
        // node stat.
        for (stat, shape) in [(false, Shape::Void), (true, Shape::Stat)] {
            let (path, acl, version) = (np("/a"), JVec::Null, 1);
            let a = if stat {
                Op::SetAclStat { path, acl, version }
            } else {
                Op::SetAcl { path, acl, version }
            };
            match c.check_op(a, InputDefect::default(), Input::Owned) {
                Ok(Checked::Send(p)) => assert_eq!((p.op_type, p.shape), (opcode::SET_ACL, shape)),
                _ => panic!("check_op failed"),
            }
        }
        // Watch removal hints proceed to server dispatch even when no local registrations remain.
        let hint = Op::RemoveWatches {
            path: np("/gone"),
            wtype: 2,
            watcher: None,
            local: false,
            all: true,
        };
        match c.check_op(hint, InputDefect::default(), Input::Owned) {
            Ok(Checked::Send(p)) => assert_eq!(p.op_type, opcode::REMOVE_WATCHES),
            _ => panic!("check_op failed"),
        }
        let mut check = |op: Op<u32>, op_type: i32, shape: Shape| match c.check_op(
            op,
            InputDefect::default(),
            Input::Owned,
        ) {
            Ok(Checked::Send(p)) => assert_eq!(
                (p.op_type, p.shape, p.log.is_none()),
                (op_type, shape, true)
            ),
            _ => panic!("check_op failed"),
        };
        check(Op::WhoAmI, opcode::WHO_AM_I, Shape::WhoAmI);
        check(
            Op::GetEphemerals { prefix: np("/") },
            opcode::GET_EPHEMERALS,
            Shape::Ephemerals,
        );
        check(
            Op::GetAllChildrenNumber { path: np("/a") },
            opcode::GET_ALL_CHILDREN_NUMBER,
            Shape::ChildrenCount,
        );
        let ops = vec![
            MultiOp::GetData { path: np("/a") },
            MultiOp::GetChildren { path: np("/b") },
        ];
        check(
            Op::MultiRead { ops },
            opcode::MULTI_READ,
            Shape::Multi {
                op_types: vec![opcode::GET_DATA, opcode::GET_CHILDREN],
                kind: MultiKind::MultiRead,
            },
        );
    }

    #[test]
    fn legacy_chroot_join() {
        let c = chrooted(Compat::LEGACY_C);
        assert_eq!(c.server_path(&lp("/x"), 0), Ok(b"/app/x".to_vec()));
        assert_eq!(c.server_path(&lp("/"), 0), Ok(b"/app".to_vec()));
        assert_eq!(c.server_path(&lp("/"), 2), Err(ZBADARGUMENTS));
        assert_eq!(c.server_path(&lp("x"), 0), Err(ZBADARGUMENTS));
        assert_eq!(c.server_path(&PathIn::Legacy(None), 0), Err(ZBADARGUMENTS));
        assert_eq!(legacy().server_path(&lp("/x/"), 2), Ok(b"/x/".to_vec()));
    }

    #[test]
    fn component_chroot_join() {
        let c = chrooted(Compat::NATIVE);
        assert_eq!(c.server_path(&np("/x"), 0), Ok(b"/app/x".to_vec()));
        assert_eq!(c.server_path(&np("/"), 0), Ok(b"/app".to_vec()));
        assert_eq!(c.server_path(&np("/"), 2), Err(ZBADARGUMENTS));
        assert_eq!(c.server_path(&np("/x/."), 0), Err(ZBADARGUMENTS));
        let null = PathIn::Native(JString::Null);
        assert_eq!(c.server_path(&null, 0), Err(ZBADARGUMENTS));
        let raw = PathIn::Native(JString::Bytes(vec![b'/', 0xff]));
        assert_eq!(c.server_path(&raw, 0), Err(ZBADARGUMENTS));
        let plain = conn_with(Config::legacy(30_000), Compat::NATIVE);
        assert_eq!(plain.server_path(&np("/"), 2), Ok(b"/".to_vec()));
    }

    #[test]
    fn owned_server_path_reuses_the_request_buffer() {
        let ptr = |p: &PathIn| match p {
            PathIn::Legacy(Some(b)) | PathIn::Native(JString::Bytes(b)) => b.as_ptr(),
            _ => panic!("no bytes"),
        };
        // In the absence of an active chroot, the request buffer is reused directly without
        // reallocating.
        let plain = [
            (legacy(), lp("/x")),
            (conn_with(Config::legacy(30_000), Compat::NATIVE), np("/x")),
        ];
        for (c, p) in plain {
            let at = ptr(&p);
            let out = c.server_path_owned(p, 0).unwrap();
            assert_eq!((out.as_slice(), out.as_ptr()), (&b"/x"[..], at));
        }
        // Owned path resolution produces byte-identical output to borrowed path resolution.
        for (c, p) in [
            (chrooted(Compat::LEGACY_C), lp("/x")),
            (chrooted(Compat::LEGACY_C), lp("/")),
            (chrooted(Compat::LEGACY_C), lp("x")),
            (chrooted(Compat::LEGACY_C), PathIn::Legacy(None)),
            (chrooted(Compat::NATIVE), np("/x")),
            (chrooted(Compat::NATIVE), np("/x/.")),
            (chrooted(Compat::NATIVE), PathIn::Native(JString::Null)),
        ] {
            for mode in [0, 2] {
                let want = c.server_path(&p, mode);
                assert_eq!(c.server_path_owned(p.clone(), mode), want);
            }
        }
    }

    #[test]
    fn send_log_shows_the_client_path_and_failed_checks_log_nothing() {
        let mut c = chrooted(Compat::LEGACY_C);
        assert_eq!(queued(&mut c, create(lp("/a"), 0, -1)), 1);
        let logs1 = logs(&mut c);
        assert_eq!(logs1.len(), 1);
        assert_eq!(logs1[0].site, LogSite::SendCreate);
        assert_eq!(logs1[0].args[1], LogArg::Str(JString::from("/a")));
        // Early validation failures (TTL, path syntax, session state) discard preallocated log
        // records without emission.
        assert_eq!(
            fails(&mut c, create(lp("/a"), 5, 0), InputDefect::default()),
            VALIDATE_BAD
        );
        let get = |path| Op::GetData { path, watch: None };
        assert_eq!(
            fails(&mut c, get(lp("a")), InputDefect::default()),
            VALIDATE_BAD
        );
        c.set_state(State::ExpiredSession);
        assert_eq!(
            fails(&mut c, get(lp("/a")), InputDefect::default()),
            VALIDATE_STATE
        );
        assert_eq!(
            fails(
                &mut c,
                Op::GetConfig { watch: None },
                InputDefect::default()
            ),
            VALIDATE_STATE
        );
        assert!(logs(&mut c).is_empty());
    }

    #[test]
    fn encode_multi_layout() {
        let mut c = legacy();
        let ops = vec![
            MultiOp::Create {
                op_type: opcode::CREATE,
                path: lp("/a"),
                data: JBuffer::Bytes(vec![1, 2]),
                acl: JVec::Null,
                mode: 0,
                ttl: -1,
                defect: InputDefect::default(),
            },
            mcheck(lp("/b")),
        ];
        let (body, types) = c.encode_multi(ops, false).unwrap();
        assert_eq!(types, [opcode::CREATE, opcode::CHECK]);
        let mut want = Vec::new();
        multi::encode_op_header(opcode::CREATE, &mut want);
        CreateRequest {
            path: JString::from("/a"),
            data: JBuffer::Bytes(vec![1, 2]),
            acl: JVec::Null,
            flags: 0,
        }
        .encode(&mut want);
        multi::encode_op_header(opcode::CHECK, &mut want);
        CheckVersionRequest {
            path: JString::from("/b"),
            version: 3,
        }
        .encode(&mut want);
        multi::encode_end(&mut want);
        assert_eq!(body[..REQUEST_HEAD_LEN], [0; REQUEST_HEAD_LEN]);
        assert_eq!(body[REQUEST_HEAD_LEN..], want);
        assert_eq!(
            c.encode_multi(Vec::<MultiOp>::new(), false)
                .unwrap()
                .0
                .len(),
            REQUEST_HEAD_LEN + MULTI_HEADER_LEN
        );
    }

    /// Verifies that `encode_multi` reuses `tx_spare` buffer capacity while producing wire bytes
    /// identical to a freshly allocated buffer.
    #[test]
    fn encode_multi_reuses_the_transmit_spare() {
        let mut c = legacy();
        c.requests.tx_spare = vec![7; 8192];
        let at = c.requests.tx_spare.as_ptr();
        let (body, _) = c.encode_multi(vec![mcheck(lp("/b"))], false).unwrap();
        assert_eq!(body.as_ptr(), at);
        assert_eq!(c.requests.tx_spare.capacity(), 0);
        let (fresh, _) = legacy()
            .encode_multi(vec![mcheck(lp("/b"))], false)
            .unwrap();
        assert_eq!(body, fresh);
    }

    #[test]
    fn encode_multi_unimplemented_and_late_path_failure() {
        let mut c = legacy();
        // Unsupported opcodes within multi-transactions take precedence over path syntax failures.
        let ops = vec![mdelete(PathIn::Legacy(None)), MultiOp::Other { op_type: 7 }];
        let r = c.encode_multi(ops, false);
        assert_eq!(r, Err(validate(ZUNIMPLEMENTED)));
        assert_eq!(logs(&mut c).len(), 1);
        // Syntax defects among recognized opcodes fail with ZMARSHALLINGERROR following completion
        // of the type scan.
        let ops = vec![mcheck(lp("/a")), mdelete(lp("bad")), mcheck(lp("/c"))];
        assert_eq!(c.encode_multi(ops, false), Err(MARSHAL));
        assert!(logs(&mut c).is_empty());
        // Legacy compatibility profiles reject create2 and createTTL opcodes within transactions.
        let ttl = MultiOp::Create {
            op_type: opcode::CREATE_TTL,
            path: lp("/a"),
            data: JBuffer::Null,
            acl: JVec::Null,
            mode: 5,
            ttl: 10,
            defect: InputDefect::default(),
        };
        let r = c.encode_multi(vec![ttl.clone()], false);
        assert_eq!(r, Err(validate(ZUNIMPLEMENTED)));
        // Multi-transactions enforce strict separation: read batches prohibit writes, and write
        // batches prohibit reads.
        let get = MultiOp::GetData { path: lp("/a") };
        let r = c.encode_multi(vec![get], false);
        assert_eq!(r, Err(validate(ZUNIMPLEMENTED)));
        let r = c.encode_multi(vec![mcheck(lp("/a"))], true);
        assert_eq!(r, Err(validate(ZUNIMPLEMENTED)));

        let mut n = conn_with(Config::legacy(30_000), Compat::NATIVE);
        let (_, types) = n.encode_multi(vec![ttl], false).unwrap();
        assert_eq!(types, [opcode::CREATE_TTL]);
        let bad_ttl = MultiOp::Create {
            op_type: opcode::CREATE_TTL,
            path: np("/a"),
            data: JBuffer::Null,
            acl: JVec::Null,
            mode: 5,
            ttl: 0,
            defect: InputDefect::default(),
        };
        assert_eq!(n.encode_multi(vec![bad_ttl], false), Err(MARSHAL));
    }

    /// Test helper validating that a custom data record matches the wire serialization of
    /// generated Jute structs.
    fn same_bytes(rec: &DataRecord<'_>, want: &[u8]) {
        let mut out = Vec::new();
        rec.encode(&mut out);
        assert_eq!(out, want);
        assert_eq!(rec.encoded_len(), want.len());
    }

    #[test]
    fn data_records_encode_as_the_generated_records() {
        let entry = ACL {
            perms: 31,
            id: zk_jute::data::Id {
                scheme: JString::from("world"),
                id: JString::from("anyone"),
            },
        };
        let acls = [
            JVec::Null,
            JVec::Items(Vec::new()),
            JVec::Items(vec![entry]),
        ];
        let datas: [Option<&[u8]>; 3] = [None, Some(b""), Some(b"xyz")];
        let paths = [JString::Null, JString::from("/app/a")];
        for (data, path) in datas
            .into_iter()
            .flat_map(|d| paths.iter().map(move |p| (d, p)))
        {
            let buf = JBuffer::from(data.map(<[u8]>::to_vec));
            let set = DataRecord {
                path: path.as_bytes(),
                data,
                tail: DataTail::SetData { version: -7 },
            };
            let want = SetDataRequest {
                path: path.clone(),
                data: buf.clone(),
                version: -7,
            };
            same_bytes(&set, &want.to_bytes());
            for acl in &acls {
                let create = DataRecord {
                    path: path.as_bytes(),
                    data,
                    tail: DataTail::Create { acl, flags: 3 },
                };
                let want = CreateRequest {
                    path: path.clone(),
                    data: buf.clone(),
                    acl: acl.clone(),
                    flags: 3,
                };
                same_bytes(&create, &want.to_bytes());
                let ttl = DataRecord {
                    path: path.as_bytes(),
                    data,
                    tail: DataTail::CreateTtl {
                        acl,
                        flags: 5,
                        ttl: 0x00ff_0000_0001,
                    },
                };
                let want = CreateTTLRequest {
                    path: path.clone(),
                    data: buf.clone(),
                    acl: acl.clone(),
                    flags: 5,
                    ttl: 0x00ff_0000_0001,
                };
                same_bytes(&ttl, &want.to_bytes());
            }
        }
        assert_eq!(wire_buffer(b"abc"), (3, &b"abc"[..]));
        assert_eq!(wire_buffer(b""), (0, &b""[..]));
    }

    /// Captured outbound transmission frame and diagnostic log records for an accepted
    /// submission, or the pipeline rejection stage and error code.
    type Outcome = Result<(Vec<u8>, Vec<LogRecord>), (Stage, i32)>;

    /// Constructs a collection of data-bearing operations for verifying borrowed buffer
    /// serialization.
    fn data_ops(path: &str, data: JBuffer) -> Vec<Op<u32>> {
        let create = |mode, ttl, create2| Op::Create {
            path: lp(path),
            data: data.clone(),
            acl: JVec::Items(Vec::new()),
            mode,
            ttl,
            create2,
        };
        vec![
            Op::SetData {
                path: lp(path),
                data: data.clone(),
                version: 4,
            },
            create(0, -1, false),
            create(2, -1, true),
            create(5, 10, false),
            create(4, -1, false),
        ]
    }

    /// Submits an operation and captures resulting transmission frames and log records, or the
    /// rejection stage and code.
    fn outcome(
        c: &mut Connection<Tk>,
        op: Op<u32>,
        defect: InputDefect,
        data: Option<&[u8]>,
    ) -> Outcome {
        let r = match data {
            None => c.submit(req(op, defect), Millis(0)),
            Some(d) => c.submit_with_data(req(op, defect), Some(d), Millis(0)),
        };
        settle(c, r)
    }

    /// Extracts the queued transmission frame and diagnostic log records of an accepted
    /// submission, or returns the rejection stage and error code.
    fn settle(c: &mut Connection<Tk>, r: Result<Submitted<Tk>, SubmitError<Tk>>) -> Outcome {
        match r {
            Ok(Submitted::Queued { .. }) => {
                let f = c.requests.tx.pop_back().unwrap();
                let _ = c.requests.fifo.pop_back();
                Ok((f.bytes.as_slice().to_vec(), logs(c)))
            }
            Ok(Submitted::Local(_)) => panic!("local"),
            Err(e) => {
                assert_eq!(e.token, 7);
                assert!(logs(c).is_empty());
                Err((e.stage, e.code))
            }
        }
    }

    #[test]
    fn borrowed_data_encodes_as_owned_data() {
        for compat in [Compat::LEGACY_C, Compat::NATIVE] {
            let mut owned = chrooted(compat);
            let mut borrowed = chrooted(compat);
            for value in [&b"payload"[..], b""] {
                let ops = data_ops("/a", JBuffer::from(value));
                for (o, b) in ops.into_iter().zip(data_ops("/a", JBuffer::Null)) {
                    let want = outcome(&mut owned, o, InputDefect::default(), None);
                    let got = outcome(&mut borrowed, b, InputDefect::default(), Some(value));
                    assert!(want.is_ok());
                    assert_eq!(got, want);
                }
            }
            // Passing None preserves the internal payload buffer of the operation (here
            // representing a Jute null buffer).
            for op in data_ops("/a", JBuffer::Null) {
                let want = outcome(&mut owned, op, InputDefect::default(), None);
                let (frame, _) = want.unwrap();
                assert_eq!(
                    &frame[REQUEST_HEAD_LEN + 4 + 6..][..4],
                    &(-1i32).to_be_bytes()
                );
            }
        }
    }

    #[test]
    fn borrowed_data_keeps_the_stage_order() {
        // Validates priority across combinations of connection state, path syntax, TTL parameters,
        // and input defects.
        let closing = |c: &mut Connection<Tk>| c.close.closing = true;
        let expired = |c: &mut Connection<Tk>| c.set_state(State::ExpiredSession);
        let drain = || {
            let mut compat = Compat::LEGACY_C;
            compat.close_mode = CloseMode::Drain;
            let mut c = conn_with(Config::legacy(30_000), compat);
            c.close.closing = true;
            c
        };
        let throttled = || {
            let mut cfg = Config::legacy(30_000);
            cfg.max_pending = Some(0);
            conn_with(cfg, Compat::LEGACY_C)
        };
        let setups: [&dyn Fn() -> Connection<Tk>; 5] = [
            &legacy,
            &drain,
            &throttled,
            &|| {
                let mut c = legacy();
                expired(&mut c);
                c
            },
            &|| {
                let mut c = legacy();
                closing(&mut c);
                c
            },
        ];
        for setup in setups {
            for path in ["/a", "a", "/a/"] {
                for defect in [InputDefect::default(), DEFECT] {
                    let owned = data_ops(path, JBuffer::from(&b"v"[..]));
                    for (o, b) in owned.into_iter().zip(data_ops(path, JBuffer::Null)) {
                        let want = outcome(&mut setup(), o, defect, None);
                        let got = outcome(&mut setup(), b, defect, Some(b"v"));
                        assert_eq!(got, want, "{path} {defect:?}");
                    }
                }
            }
        }
        // Invalid TTL configurations trigger row 1 validation rejection regardless of buffer
        // borrowing mode.
        let mut c = legacy();
        let op = create(lp("/a"), 5, 0);
        assert_eq!(outcome(&mut c, op, DEFECT, Some(b"v")), Err(VALIDATE_BAD));
        assert_untouched(&c);
    }

    /// Constructs a synthetic node payload buffer exceeding [`EARLY_FRAME_MIN`] to trigger
    /// pre-lock encoding.
    fn big() -> Vec<u8> {
        let mut v = vec![0x5a; EARLY_FRAME_MIN + 3];
        v.extend_from_slice(b"end");
        v
    }

    /// Submits an operation via [`Connection::submit_early`] when [`FrameEncoder::prepare`]
    /// produces a pre-encoded body, falling back to [`Connection::submit_with_data`] otherwise;
    /// returns a boolean indicating whether the early submission path executed alongside the
    /// resulting [`Outcome`].
    fn early_outcome(
        c: &mut Connection<Tk>,
        enc: &FrameEncoder,
        op: Op<u32>,
        defect: InputDefect,
        data: &[u8],
    ) -> (bool, Outcome) {
        match enc.prepare(req(op, defect), Some(data)) {
            Ok(early) => {
                let r = c.submit_early(early, Millis(0));
                (true, settle(c, r))
            }
            Err(r) => {
                assert_eq!(r.token, 7);
                let r = c.submit_with_data(r, Some(data), Millis(0));
                (false, settle(c, r))
            }
        }
    }

    #[test]
    fn early_bodies_encode_as_borrowed_data() {
        let value = big();
        for compat in [Compat::LEGACY_C, Compat::NATIVE] {
            let plain = || conn_with(Config::legacy(30_000), compat);
            let rooted = || chrooted(compat);
            let setups: [&dyn Fn() -> Connection<Tk>; 2] = [&plain, &rooted];
            for setup in setups {
                let mut borrowed = setup();
                let mut early = setup();
                let enc = early.frame_encoder();
                for path in ["/a", "/a/b"] {
                    let ops = data_ops(path, JBuffer::Null);
                    for (o, b) in ops.into_iter().zip(data_ops(path, JBuffer::Null)) {
                        let want = outcome(&mut borrowed, o, InputDefect::default(), Some(&value));
                        let (used, got) =
                            early_outcome(&mut early, &enc, b, InputDefect::default(), &value);
                        assert!(used, "{path}");
                        assert!(want.is_ok());
                        assert_eq!(got, want, "{path}");
                    }
                }
                let set = |path| Op::SetData {
                    path,
                    data: JBuffer::Null,
                    version: 2,
                };
                let want = outcome(
                    &mut borrowed,
                    set(np("/n")),
                    InputDefect::default(),
                    Some(&value),
                );
                let (used, got) = early_outcome(
                    &mut early,
                    &enc,
                    set(np("/n")),
                    InputDefect::default(),
                    &value,
                );
                assert!(used);
                assert_eq!(got, want);
            }
        }
    }

    #[test]
    fn early_bodies_keep_the_stage_order() {
        let value = big();
        let drain = || {
            let mut compat = Compat::LEGACY_C;
            compat.close_mode = CloseMode::Drain;
            let mut c = conn_with(Config::legacy(30_000), compat);
            c.close.closing = true;
            c
        };
        let throttled = || {
            let mut cfg = Config::legacy(30_000);
            cfg.max_pending = Some(0);
            conn_with(cfg, Compat::LEGACY_C)
        };
        let expired = || {
            let mut c = legacy();
            c.set_state(State::ExpiredSession);
            c
        };
        let closing = || {
            let mut c = legacy();
            c.close.closing = true;
            c
        };
        let setups: [&dyn Fn() -> Connection<Tk>; 5] =
            [&legacy, &drain, &throttled, &expired, &closing];
        for setup in setups {
            for path in ["/a", "a", "/a/", ""] {
                for defect in [InputDefect::default(), DEFECT] {
                    let ops = data_ops(path, JBuffer::Null);
                    for (o, b) in ops.into_iter().zip(data_ops(path, JBuffer::Null)) {
                        let want = outcome(&mut setup(), o, defect, Some(&value));
                        let mut c = setup();
                        let enc = c.frame_encoder();
                        let (used, got) = early_outcome(&mut c, &enc, b, defect, &value);
                        // Trailing slashes are valid only for the sequential creation variant
                        // among `data_ops`, so the `/a/` case makes no assertion on `used`.
                        if path == "/a" && !defect.any() {
                            assert!(used, "{path}");
                        } else if path != "/a/" {
                            assert!(!used, "{path} {defect:?}");
                        }
                        assert_eq!(got, want, "{path} {defect:?}");
                        if got.is_err() {
                            assert_untouched(&c);
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn early_body_becomes_the_frame() {
        let value = big();
        let mut c = chrooted(Compat::LEGACY_C);
        let enc = c.frame_encoder();
        for op in data_ops("/a", JBuffer::Null) {
            let Ok(early) = enc.prepare(req(op, InputDefect::default()), Some(&value)) else {
                panic!("no early body");
            };
            let body = early.body.take();
            let at = body.as_ptr();
            early.body.set(body);
            assert!(matches!(
                c.submit_early(early, Millis(0)),
                Ok(Submitted::Queued { .. })
            ));
            let frame = c.requests.tx.back().unwrap();
            assert_eq!(frame.bytes.as_slice().as_ptr(), at);
        }
    }

    #[test]
    fn stale_early_bodies_are_encoded_again() {
        let value = big();
        let stale = [
            legacy().frame_encoder(),
            FrameEncoder {
                chroot: Some(b"/bpp".to_vec()),
                policy: ChrootPolicy::LegacyConcatNoEscape,
            },
        ];
        for enc in stale {
            let mut borrowed = chrooted(Compat::LEGACY_C);
            let mut early = chrooted(Compat::LEGACY_C);
            let ops = data_ops("/a", JBuffer::Null);
            for (o, b) in ops.into_iter().zip(data_ops("/a", JBuffer::Null)) {
                let want = outcome(&mut borrowed, o, InputDefect::default(), Some(&value));
                let (used, got) =
                    early_outcome(&mut early, &enc, b, InputDefect::default(), &value);
                assert!(used);
                assert!(want.is_ok());
                assert_eq!(got, want);
            }
        }
    }

    #[test]
    fn small_or_unchecked_requests_skip_early_encoding() {
        fn skips(enc: &FrameEncoder, op: Op<u32>, defect: InputDefect, data: Option<&[u8]>) {
            match enc.prepare(req(op, defect), data) {
                Ok(_) => panic!("early body"),
                Err(r) => assert_eq!(r.token, 7),
            }
        }
        let enc = legacy().frame_encoder();
        let value = big();
        let small = vec![0x5a; EARLY_FRAME_MIN - 1];
        for op in data_ops("/a", JBuffer::Null) {
            skips(&enc, op, InputDefect::default(), Some(&small));
        }
        for op in data_ops("/a", JBuffer::from(&value[..])) {
            skips(&enc, op, InputDefect::default(), None);
        }
        for op in data_ops("/a", JBuffer::Null) {
            skips(&enc, op, DEFECT, Some(&value));
        }
        for path in ["a", "", "/a//b"] {
            for op in data_ops(path, JBuffer::Null) {
                skips(&enc, op, InputDefect::default(), Some(&value));
            }
        }
        skips(
            &enc,
            create(lp("/a"), 5, 0),
            InputDefect::default(),
            Some(&value),
        );
        let none = Op::SetData {
            path: PathIn::Legacy(None),
            data: JBuffer::Null,
            version: 0,
        };
        skips(&enc, none, InputDefect::default(), Some(&value));
        let delete = Op::Delete {
            path: lp("/a"),
            version: -1,
        };
        skips(&enc, delete, InputDefect::default(), Some(&value));
    }

    #[test]
    fn rejected_early_requests_draw_no_xid() {
        let value = big();
        let mut c = legacy();
        let enc = c.frame_encoder();
        c.close.closing = true;
        for op in data_ops("/a", JBuffer::Null) {
            let (used, got) = early_outcome(&mut c, &enc, op, InputDefect::default(), &value);
            assert!(used);
            assert!(got.is_err());
            assert_untouched(&c);
        }
        c.close.closing = false;
        let mut fresh = legacy();
        let op = || data_ops("/a", JBuffer::from(&b"v"[..])).remove(0);
        assert_eq!(queued(&mut c, op()), queued(&mut fresh, op()));
    }

    /// Converts an owned `MultiOp` into a borrowed `MultiOpRef` view for zero-copy submission
    /// tests.
    fn as_ref_op(op: &MultiOp) -> MultiOpRef<'_> {
        fn path(p: &PathIn) -> Option<&[u8]> {
            match p {
                PathIn::Legacy(b) => b.as_deref(),
                PathIn::Native(s) => s.as_bytes(),
            }
        }
        match op {
            MultiOp::Create {
                op_type,
                path: p,
                data,
                acl,
                mode,
                ttl,
                defect,
            } => MultiOpRef::Create {
                op_type: *op_type,
                path: path(p),
                data: data.as_bytes(),
                acl: acl.clone(),
                mode: *mode,
                ttl: *ttl,
                defect: *defect,
            },
            MultiOp::Delete {
                path: p,
                version,
                defect,
            } => MultiOpRef::Delete {
                path: path(p),
                version: *version,
                defect: *defect,
            },
            MultiOp::SetData {
                path: p,
                data,
                version,
                defect,
            } => MultiOpRef::SetData {
                path: path(p),
                data: data.as_bytes(),
                version: *version,
                defect: *defect,
            },
            MultiOp::Check {
                path: p,
                version,
                defect,
            } => MultiOpRef::Check {
                path: path(p),
                version: *version,
                defect: *defect,
            },
            other => MultiOpRef::Other {
                op_type: other.op_type(),
            },
        }
    }

    /// Generates a comprehensive suite of multi-operation sub-requests exercising diverse payload
    /// shapes.
    fn ref_sub_ops(path: fn(&str) -> PathIn) -> Vec<MultiOp> {
        let entry = ACL {
            perms: 31,
            id: zk_jute::data::Id {
                scheme: JString::from("world"),
                id: JString::from("anyone"),
            },
        };
        let mk = |op_type, p: &str, data, acl, mode| MultiOp::Create {
            op_type,
            path: path(p),
            data,
            acl,
            mode,
            ttl: -1,
            defect: InputDefect::default(),
        };
        vec![
            mk(
                opcode::CREATE,
                "/a",
                JBuffer::Bytes(vec![1, 2]),
                JVec::Null,
                0,
            ),
            mk(
                opcode::CREATE,
                "/c",
                JBuffer::Null,
                JVec::Items(vec![entry]),
                2,
            ),
            mk(
                opcode::CREATE_CONTAINER,
                "/k",
                JBuffer::Bytes(Vec::new()),
                JVec::Items(Vec::new()),
                4,
            ),
            MultiOp::SetData {
                path: path("/s"),
                data: JBuffer::Bytes(vec![7; 8]),
                version: -1,
                defect: InputDefect::default(),
            },
            MultiOp::SetData {
                path: path("/n"),
                data: JBuffer::Null,
                version: 4,
                defect: InputDefect::default(),
            },
            mcheck(path("/b")),
            mdelete(path("/d")),
        ]
    }

    /// Generates native create2 and createTTL sub-operations for transaction validation tests.
    fn native_creates(ttl: i64) -> Vec<MultiOp> {
        let mk = |op_type, mode, ttl| MultiOp::Create {
            op_type,
            path: np("/t"),
            data: JBuffer::Bytes(vec![9]),
            acl: JVec::Items(Vec::new()),
            mode,
            ttl,
            defect: InputDefect::default(),
        };
        vec![mk(opcode::CREATE2, 0, -1), mk(opcode::CREATE_TTL, 5, ttl)]
    }

    /// Verifies serialization parity between owned and borrowed multi-operation representations.
    fn same_multi(
        mk: &dyn Fn() -> Connection<Tk>,
        ops: Vec<MultiOp>,
        expired: bool,
    ) -> Result<(Vec<u8>, Vec<i32>), Rejected> {
        let (mut owned, mut borrowed) = (mk(), mk());
        if expired {
            owned.set_state(State::ExpiredSession);
            borrowed.set_state(State::ExpiredSession);
        }
        let refs: Vec<MultiOpRef<'_>> = ops.iter().map(as_ref_op).collect();
        let got = borrowed.encode_multi(&refs, false);
        let want = owned.encode_multi(ops, false);
        assert_eq!(got, want);
        assert_eq!(logs(&mut borrowed), logs(&mut owned));
        want
    }

    #[test]
    fn ref_encoder_matches_the_owned_encoder() {
        let legacy_conns: [&dyn Fn() -> Connection<Tk>; 2] =
            [&legacy, &|| chrooted(Compat::LEGACY_C)];
        let native_conns: [&dyn Fn() -> Connection<Tk>; 2] = [
            &|| conn_with(Config::legacy(30_000), Compat::NATIVE),
            &|| chrooted(Compat::NATIVE),
        ];
        for mk in legacy_conns {
            let r = same_multi(mk, ref_sub_ops(lp), false);
            assert_eq!(r.map(|(_, t)| t.len()), Ok(7));
            // Legacy profiles reject native create variants during transaction type verification.
            let r = same_multi(mk, native_creates(10), false);
            assert_eq!(r, Err(validate(ZUNIMPLEMENTED)));
        }
        for mk in native_conns {
            let mut ops = ref_sub_ops(np);
            ops.extend(native_creates(10));
            let r = same_multi(mk, ops, false);
            assert_eq!(r.map(|(_, t)| t.len()), Ok(9));
            // Out-of-bounds TTL specifications trigger serialization-stage rejection following
            // type scans.
            let r = same_multi(mk, native_creates(0), false);
            assert_eq!(r, Err(MARSHAL));
        }
        for mk in legacy_conns {
            // Verifies rejection across null paths, invalid syntax, input defects, and corrupted
            // buffers.
            let null = vec![mcheck(lp("/a")), mdelete(PathIn::Legacy(None))];
            assert_eq!(same_multi(mk, null, false), Err(MARSHAL));
            let bad = vec![mdelete(lp("bad")), mcheck(lp("/c"))];
            assert_eq!(same_multi(mk, bad, false), Err(MARSHAL));
            let mut defect = ref_sub_ops(lp);
            if let Some(MultiOp::SetData { defect: d, .. }) = defect.get_mut(3) {
                *d = DEFECT;
            }
            assert_eq!(same_multi(mk, defect, false), Err(MARSHAL));
            // Unsupported opcodes take precedence over prior sub-operation defects and emit an
            // audit log.
            let unimpl = vec![
                mdelete(lp("bad")),
                MultiOp::Other { op_type: 7 },
                mcheck(lp("/c")),
            ];
            assert_eq!(same_multi(mk, unimpl, false), Err(validate(ZUNIMPLEMENTED)));
            let read = vec![MultiOp::GetData { path: lp("/a") }];
            assert_eq!(same_multi(mk, read, false), Err(validate(ZUNIMPLEMENTED)));
            // Unrecoverable session state causes immediate rejection whether sub-operations are
            // present or empty.
            assert_eq!(same_multi(mk, ref_sub_ops(lp), true), Err(MARSHAL));
            assert_eq!(same_multi(mk, Vec::new(), true), Err(MARSHAL));
        }
        // Read-only multi-transactions do not support borrowed sub-operation references.
        let mut c = legacy();
        let check = mcheck(lp("/a"));
        let refs = [as_ref_op(&check)];
        assert_eq!(c.encode_multi(&refs, true), Err(validate(ZUNIMPLEMENTED)));
    }

    /// Submits a write multi-transaction using either owned or borrowed sub-operations and returns
    /// outputs.
    fn multi_outcome(
        c: &mut Connection<Tk>,
        ops: Vec<MultiOp>,
        refs: Option<&[MultiOpRef<'_>]>,
        defect: InputDefect,
    ) -> Outcome {
        let r = match refs {
            None => c.submit(req(Op::Multi { ops }, defect), Millis(0)),
            Some(r) => {
                let op = Op::Multi { ops: Vec::new() };
                c.submit_multi_ref(req(op, defect), r, Millis(0))
            }
        };
        match r {
            Ok(Submitted::Queued { .. }) => {
                let f = c.requests.tx.pop_back().unwrap();
                let _ = c.requests.fifo.pop_back();
                Ok((f.bytes.as_slice().to_vec(), logs(c)))
            }
            Ok(Submitted::Local(_)) => panic!("local"),
            Err(e) => {
                assert_eq!(e.token, 7);
                Err((e.stage, e.code))
            }
        }
    }

    #[test]
    fn borrowed_multi_submits_as_owned_multi() {
        for compat in [Compat::LEGACY_C, Compat::NATIVE] {
            let mut owned = chrooted(compat);
            let mut borrowed = chrooted(compat);
            let path = if compat.native_ops { np } else { lp };
            let ops = ref_sub_ops(path);
            let refs: Vec<MultiOpRef<'_>> = ops.iter().map(as_ref_op).collect();
            let want = multi_outcome(&mut owned, ops.clone(), None, InputDefect::default());
            let got = multi_outcome(
                &mut borrowed,
                Vec::new(),
                Some(&refs),
                InputDefect::default(),
            );
            assert!(want.as_ref().is_ok_and(|(_, l)| l.len() == 1));
            assert_eq!(got, want);
            // Request-level defects trigger rejection at the serialization stage
            // (ZMARSHALLINGERROR).
            let want = multi_outcome(&mut owned, ops.clone(), None, DEFECT);
            let got = multi_outcome(&mut borrowed, Vec::new(), Some(&refs), DEFECT);
            assert_eq!(want, Err((Stage::Enqueue, ZMARSHALLINGERROR)));
            assert_eq!(got, want);
            // Sub-operation path syntax failures within multi-transactions trigger
            // ZMARSHALLINGERROR.
            let bad = vec![mdelete(path("bad"))];
            let bad_refs: Vec<MultiOpRef<'_>> = bad.iter().map(as_ref_op).collect();
            let want = multi_outcome(&mut owned, bad.clone(), None, InputDefect::default());
            let got = multi_outcome(
                &mut borrowed,
                Vec::new(),
                Some(&bad_refs),
                InputDefect::default(),
            );
            assert_eq!(want, Err((Stage::Enqueue, ZMARSHALLINGERROR)));
            assert_eq!(got, want);
            assert_untouched(&owned);
            assert_untouched(&borrowed);
        }
        // For non-multi operations, borrowed sub-operation parameters are safely disregarded.
        let check = mcheck(lp("/a"));
        let refs = [as_ref_op(&check)];
        let mut owned = legacy();
        let mut borrowed = legacy();
        let op = Op::Delete {
            path: lp("/x"),
            version: 2,
        };
        let want = owned.submit(req(op.clone(), InputDefect::default()), Millis(0));
        let got = borrowed.submit_multi_ref(req(op, InputDefect::default()), &refs, Millis(0));
        assert!(matches!(want, Ok(Submitted::Queued { xid: 1 })));
        assert!(matches!(got, Ok(Submitted::Queued { xid: 1 })));
        let frame = |c: &Connection<Tk>| c.requests.tx.back().map(|f| f.bytes.as_slice().to_vec());
        assert_eq!(frame(&borrowed), frame(&owned));
    }
}
