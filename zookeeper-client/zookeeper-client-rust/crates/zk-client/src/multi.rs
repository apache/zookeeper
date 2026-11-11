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

//! Atomic transactions (`MultiWriter`), consistent snapshot read batches (`MultiReader`), and
//! structured transaction outcomes.
//!
//! Znode paths are validated upon insertion into the builder, guaranteeing that syntax errors
//! fail immediately at insertion time. Operation data buffers remain borrowed throughout
//! transaction assembly. [`Client::multi`](crate::Client::multi) serializes the entire batch
//! directly into the transmission frame under driver lock without performing intermediate heap
//! copies.

use std::marker::PhantomData;
use std::sync::atomic::{AtomicU64, Ordering};

use zk_jute::multi::{MultiBody, MultiItem};
use zk_jute::{opcode, rc};
use zk_proto::{InputDefect, MultiOp, MultiOpRef, Reply};

use crate::acl::Acl;
use crate::decode::{names, rc_error, server_path};
use crate::error::{Error, MultiError};
use crate::ops;
use crate::path::{self, ZPathBuf};
use crate::tokens::RawResult;
use crate::types::{CreateMode, Stat, Version};

/// Monotonic sequence generator allocating unique identifiers to [`MultiWriter`] instances to
/// detect cross-writer handle misuse.
static NEXT_WRITER_ID: AtomicU64 = AtomicU64::new(1);

/// Allocates a unique writer instance identifier, wrapping safely after `2^64` allocations.
fn next_writer_id() -> u64 {
    NEXT_WRITER_ID.fetch_add(1, Ordering::Relaxed)
}

/// Representation of a single mutating transaction sub-operation with borrowed inputs.
#[derive(Clone, Debug)]
enum WriteOp<'a> {
    Create {
        path: &'a str,
        data: &'a [u8],
        mode: CreateMode,
        acl: &'a [Acl],
    },
    Delete {
        path: &'a str,
        version: Version,
    },
    SetData {
        path: &'a str,
        data: &'a [u8],
        version: Version,
    },
    Check {
        path: &'a str,
        version: Version,
    },
}

/// Fluent builder for assembling atomic transactional mutation batches (ZooKeeper `multi` opcode).
#[derive(Clone, Debug)]
pub struct MultiWriter<'a> {
    ops: Vec<WriteOp<'a>>,
    writer_id: u64,
}

impl Default for MultiWriter<'_> {
    fn default() -> Self {
        Self::new()
    }
}

impl<'a> MultiWriter<'a> {
    /// Initializes an empty transactional write builder.
    #[must_use]
    pub fn new() -> Self {
        Self {
            ops: Vec::new(),
            writer_id: next_writer_id(),
        }
    }

    /// Enqueues a node creation sub-operation (`create2`, `createContainer`, or `createTtl` based
    /// on the specified mode).
    ///
    /// # Errors
    ///
    /// Returns [`Error::InvalidPath`] if the target path fails syntax validation. Sequential modes
    /// accept trailing slashes.
    pub fn create(
        &mut self,
        path: &'a str,
        data: &'a [u8],
        mode: CreateMode,
        acl: &'a [Acl],
    ) -> Result<OpHandle<Created>, Error> {
        path::checked(&path, mode.is_sequential())?;
        Ok(self.push(WriteOp::Create {
            path,
            data,
            mode,
            acl,
        }))
    }

    /// Enqueues a node deletion sub-operation conditional on the specified node version.
    ///
    /// # Errors
    ///
    /// Returns [`Error::InvalidPath`] if the target path is malformed.
    pub fn delete(&mut self, path: &'a str, version: Version) -> Result<OpHandle<()>, Error> {
        path::checked(&path, false)?;
        Ok(self.push(WriteOp::Delete { path, version }))
    }

    /// Enqueues a node data modification sub-operation conditional on the specified version.
    ///
    /// # Errors
    ///
    /// Returns [`Error::InvalidPath`] if the target path is malformed.
    pub fn set_data(
        &mut self,
        path: &'a str,
        data: &'a [u8],
        version: Version,
    ) -> Result<OpHandle<Stat>, Error> {
        path::checked(&path, false)?;
        Ok(self.push(WriteOp::SetData {
            path,
            data,
            version,
        }))
    }

    /// Enqueues an optimistic version check sub-operation against the specified znode path and
    /// version.
    ///
    /// # Errors
    ///
    /// Returns [`Error::InvalidPath`] if the target path is malformed.
    pub fn check(&mut self, path: &'a str, version: Version) -> Result<OpHandle<()>, Error> {
        path::checked(&path, false)?;
        Ok(self.push(WriteOp::Check { path, version }))
    }

    /// Appends a validated sub-operation and returns a typed index handle.
    fn push<T>(&mut self, op: WriteOp<'a>) -> OpHandle<T> {
        let index = self.ops.len();
        self.ops.push(op);
        OpHandle::new(index, self.writer_id)
    }

    /// Returns the number of enqueued sub-operations in the transaction.
    #[must_use]
    pub fn len(&self) -> usize {
        self.ops.len()
    }

    /// Returns `true` if the transaction contains no sub-operations.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.ops.is_empty()
    }

    /// Returns the unique identifier assigned to this transaction builder.
    pub(crate) fn writer_id(&self) -> u64 {
        self.writer_id
    }

    /// Transforms enqueued sub-operations into borrowed protocol references for wire
    /// serialization.
    ///
    /// Path slices and payload buffers are borrowed directly, enabling zero-copy serialization
    /// into outbound protocol frames.
    pub(crate) fn to_ops(&self) -> Vec<MultiOpRef<'_>> {
        let defect = InputDefect::default();
        self.ops
            .iter()
            .map(|op| match op {
                WriteOp::Create {
                    path,
                    data,
                    mode,
                    acl,
                } => {
                    let (wire_mode, ttl) = ops::create_mode_wire(mode);
                    MultiOpRef::Create {
                        op_type: ops::create_op_code(mode),
                        path: Some(path.as_bytes()),
                        data: Some(data),
                        acl: ops::acl_to_jute(acl),
                        mode: wire_mode,
                        ttl,
                        defect,
                    }
                }
                WriteOp::Delete { path, version } => MultiOpRef::Delete {
                    path: Some(path.as_bytes()),
                    version: version.get(),
                    defect,
                },
                WriteOp::SetData {
                    path,
                    data,
                    version,
                } => MultiOpRef::SetData {
                    path: Some(path.as_bytes()),
                    data: Some(data),
                    version: version.get(),
                    defect,
                },
                WriteOp::Check { path, version } => MultiOpRef::Check {
                    path: Some(path.as_bytes()),
                    version: version.get(),
                    defect,
                },
            })
            .collect()
    }
}

/// Strongly typed index handle referencing a specific sub-operation within a [`MultiWriter`].
#[derive(Debug)]
pub struct OpHandle<T> {
    index: usize,
    writer_id: u64,
    _t: PhantomData<fn() -> T>,
}

impl<T> Clone for OpHandle<T> {
    fn clone(&self) -> Self {
        *self
    }
}

impl<T> Copy for OpHandle<T> {}

impl<T> OpHandle<T> {
    /// Constructs a typed handle for the specified operation index and writer identifier.
    pub(crate) fn new(index: usize, writer_id: u64) -> Self {
        Self {
            index,
            writer_id,
            _t: PhantomData,
        }
    }

    /// Zero-based position of the sub-operation within the transaction batch.
    #[must_use]
    pub fn index(&self) -> usize {
        self.index
    }
}

/// Result payload returned by a successful `create` sub-operation within an atomic transaction.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Created {
    /// Final absolute znode path created on the ensemble, with client chroot stripped.
    pub path: ZPathBuf,
    /// Metadata stat structure of the newly created node.
    pub stat: Stat,
}

/// Result of an individual sub-operation executed within a committed transaction.
#[derive(Clone, Debug, PartialEq, Eq)]
#[non_exhaustive]
pub enum OpResult {
    /// Node creation outcome, including assigned path and stat.
    Create(Created),
    /// Successful node deletion.
    Delete,
    /// Successful node data update, including resulting stat.
    SetData(Stat),
    /// Successful optimistic version validation check.
    Check,
}

/// Ordered collection of sub-operation results returned by a successfully committed transaction.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MultiResults {
    results: Vec<OpResult>,
    writer_id: u64,
}

impl MultiResults {
    /// Constructs the result container bound to the specified writer identifier.
    pub(crate) fn new(results: Vec<OpResult>, writer_id: u64) -> Self {
        Self { results, writer_id }
    }

    /// Retrieves a typed reference to the sub-operation result corresponding to the given
    /// [`OpHandle`].
    ///
    /// Returns `None` if the handle originated from a different [`MultiWriter`] instance, if the
    /// index is out of bounds, or if the result variant does not match the expected type `T`.
    #[must_use]
    pub fn get<T: FromOpResult>(&self, h: OpHandle<T>) -> Option<&T> {
        if h.writer_id != self.writer_id {
            return None;
        }
        self.results.get(h.index).and_then(T::from_op_result)
    }

    /// Returns an iterator over individual sub-operation results in transaction submission order.
    pub fn iter(&self) -> std::slice::Iter<'_, OpResult> {
        self.results.iter()
    }

    /// Consumes the container, returning the ordered results as a `Vec<OpResult>`.
    #[must_use]
    pub fn into_vec(self) -> Vec<OpResult> {
        self.results
    }
}

mod sealed {
    /// Prevents external implementations of [`FromOpResult`](super::FromOpResult).
    pub trait Sealed {}
}

/// Sealed trait governing typed result extraction from an [`OpResult`].
pub trait FromOpResult: sealed::Sealed + Sized {
    /// Attempts to extract a typed reference from an [`OpResult`], returning `None` on variant
    /// mismatch.
    fn from_op_result(r: &OpResult) -> Option<&Self>;
}

impl sealed::Sealed for Created {}
impl FromOpResult for Created {
    fn from_op_result(r: &OpResult) -> Option<&Self> {
        match r {
            OpResult::Create(c) => Some(c),
            _ => None,
        }
    }
}

impl sealed::Sealed for Stat {}
impl FromOpResult for Stat {
    fn from_op_result(r: &OpResult) -> Option<&Self> {
        match r {
            OpResult::SetData(s) => Some(s),
            _ => None,
        }
    }
}

impl sealed::Sealed for () {}
impl FromOpResult for () {
    fn from_op_result(r: &OpResult) -> Option<&Self> {
        match r {
            OpResult::Delete | OpResult::Check => Some(&()),
            _ => None,
        }
    }
}

/// Representation of a read-only query sub-operation.
#[derive(Clone, Debug)]
enum ReadOp<'a> {
    GetData(&'a str),
    GetChildren(&'a str),
}

/// Batch builder for atomic read-only snapshot queries executed under a single ensemble read
/// transaction.
#[derive(Clone, Debug, Default)]
pub struct MultiReader<'a> {
    ops: Vec<ReadOp<'a>>,
}

impl<'a> MultiReader<'a> {
    /// Initializes an empty read batch builder.
    #[must_use]
    pub fn new() -> Self {
        Self { ops: Vec::new() }
    }

    /// Enqueues a `get_data` query and returns its zero-based position in the results.
    ///
    /// # Errors
    ///
    /// Returns [`Error::InvalidPath`] if the path fails syntax validation.
    pub fn get_data(&mut self, path: &'a str) -> Result<usize, Error> {
        path::checked(&path, false)?;
        Ok(self.push(ReadOp::GetData(path)))
    }

    /// Enqueues a `get_children` query and returns its zero-based position in the results.
    ///
    /// # Errors
    ///
    /// Returns [`Error::InvalidPath`] if the path fails syntax validation.
    pub fn get_children(&mut self, path: &'a str) -> Result<usize, Error> {
        path::checked(&path, false)?;
        Ok(self.push(ReadOp::GetChildren(path)))
    }

    /// Appends a validated read sub-operation and returns its index.
    fn push(&mut self, op: ReadOp<'a>) -> usize {
        let index = self.ops.len();
        self.ops.push(op);
        index
    }

    /// Returns the number of enqueued read operations in the batch.
    #[must_use]
    pub fn len(&self) -> usize {
        self.ops.len()
    }

    /// Returns `true` if the read batch contains no sub-operations.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.ops.is_empty()
    }

    /// Transforms enqueued read queries into protocol sub-operations.
    pub(crate) fn to_ops(&self) -> Vec<MultiOp> {
        self.ops
            .iter()
            .map(|op| match op {
                ReadOp::GetData(p) => MultiOp::GetData {
                    path: ops::path_in(p),
                },
                ReadOp::GetChildren(p) => MultiOp::GetChildren {
                    path: ops::path_in(p),
                },
            })
            .collect()
    }
}

/// Result of an individual read query executed within a multi-read snapshot batch.
#[derive(Clone, Debug, PartialEq, Eq)]
#[non_exhaustive]
pub enum ReadResult {
    /// Payload and metadata stat retrieved by `get_data` (null payloads are represented as empty
    /// byte vectors).
    Data {
        /// Node binary payload.
        data: Vec<u8>,
        /// Node metadata stat.
        stat: Stat,
    },
    /// Child znode names retrieved by `get_children`.
    Children(Vec<String>),
}

/// Decodes the protocol reply payload of an atomic transaction.
///
/// Successfully committed transactions return [`MultiResults`] containing typed outcomes for every
/// sub-operation. If any sub-operation fails, the entire transaction rolls back, returning
/// [`MultiError::Aborted`] identifying the primary failing sub-operation index along with per-
/// operation error codes. Unhandled protocol or framing errors map to [`MultiError::Request`].
pub(crate) fn decode_multi(r: RawResult, writer_id: u64) -> Result<MultiResults, MultiError> {
    let items = match r.reply {
        Some(Reply::Multi(items)) => items,
        Some(_) => return Err(MultiError::Request(Error::MarshallingError)),
        None => return Err(MultiError::Request(no_reply_error(r.rc))),
    };
    // The server returns an error response body for each sub-operation of a rolled back
    // transaction.
    let rolled_back = r.rc != rc::ZOK
        || items
            .iter()
            .any(|i| matches!(i.body, MultiBody::Error(_)) || i.err != rc::ZOK);
    if rolled_back {
        return Err(aborted(&items, r.rc));
    }
    items
        .into_iter()
        .map(write_result)
        .collect::<Result<Vec<_>, Error>>()
        .map(|results| MultiResults::new(results, writer_id))
        .map_err(MultiError::Request)
}

/// Translates a raw return code from a response lacking a payload into an [`Error`].
fn no_reply_error(code: i32) -> Error {
    if code == rc::ZOK {
        Error::MarshallingError
    } else {
        rc_error(code)
    }
}

/// Extracts the numeric error code from a multi-item response header or error body.
fn item_code(i: &MultiItem) -> i32 {
    match &i.body {
        MultiBody::Error(e) => e.err,
        _ => i.err,
    }
}

/// Constructs a [`MultiError::Aborted`] diagnostic structure from a rolled-back transaction
/// response.
///
/// Pinpoints the primary causal error by identifying the first sub-operation whose return code is
/// neither `0` nor `RuntimeInconsistency`, which ZooKeeper servers attach to subsequent aborted
/// operations in the batch.
fn aborted(items: &[MultiItem], code: i32) -> MultiError {
    let codes: Vec<i32> = items.iter().map(item_code).collect();
    let index = codes
        .iter()
        .position(|&c| c != rc::ZOK && c != rc::ZRUNTIMEINCONSISTENCY)
        .or_else(|| codes.iter().position(|&c| c != rc::ZOK));
    let Some(index) = index else {
        return MultiError::Request(no_reply_error(code));
    };
    let error = codes
        .get(index)
        .map_or(Error::MarshallingError, |&c| rc_error(c));
    let per_op = codes
        .iter()
        .map(|&c| (c != rc::ZOK).then(|| rc_error(c)))
        .collect();
    MultiError::Aborted {
        index,
        error,
        per_op,
    }
}

/// Decodes an individual committed sub-operation response item according to its opcode.
fn write_result(i: MultiItem) -> Result<OpResult, Error> {
    match (i.wire_type, i.body) {
        (opcode::CREATE, MultiBody::Create(c)) => Ok(OpResult::Create(Created {
            path: server_path(c.path)?,
            stat: Stat::default(),
        })),
        (opcode::CREATE2, MultiBody::Create2(c)) => Ok(OpResult::Create(Created {
            path: server_path(c.path)?,
            stat: Stat::from_jute(&c.stat),
        })),
        (opcode::SET_DATA, MultiBody::SetData(s)) => {
            Ok(OpResult::SetData(Stat::from_jute(&s.stat)))
        }
        (opcode::DELETE, MultiBody::Empty) => Ok(OpResult::Delete),
        (opcode::CHECK, MultiBody::Empty) => Ok(OpResult::Check),
        _ => Err(Error::MarshallingError),
    }
}

/// Decodes the reply payload of a multi-read snapshot batch.
///
/// Unlike mutating transactions, an individual query failure in a multi-read batch does not abort
/// other queries. Each item is decoded independently into `Result<ReadResult, Error>`.
pub(crate) fn decode_multi_read(r: RawResult) -> Result<Vec<Result<ReadResult, Error>>, Error> {
    match r.reply {
        Some(Reply::MultiRead(items)) => Ok(items.into_iter().map(read_result).collect()),
        Some(_) => Err(Error::MarshallingError),
        None => Err(no_reply_error(r.rc)),
    }
}

/// Decodes an individual multi-read response item according to its payload body.
fn read_result(i: MultiItem) -> Result<ReadResult, Error> {
    match i.body {
        MultiBody::Error(e) => Err(no_reply_error(e.err)),
        MultiBody::GetData(d) => Ok(ReadResult::Data {
            data: d.data.into_bytes().unwrap_or_default(),
            stat: Stat::from_jute(&d.stat),
        }),
        MultiBody::GetChildren(c) => names(c.children).map(ReadResult::Children),
        _ => Err(Error::MarshallingError),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writers_have_their_own_ids() {
        let a = MultiWriter::new();
        let b = MultiWriter::default();
        assert_ne!(a.writer_id(), b.writer_id());
        assert!(a.is_empty());
        assert_eq!(b.len(), 0);
    }

    use zk_jute::data::Stat as JStat;
    use zk_jute::proto::{
        Create2Response, CreateResponse, ErrorResponse, GetChildrenResponse, GetDataResponse,
        SetDataResponse,
    };
    use zk_jute::{JBuffer, JString, JVec};
    use zk_proto::{Origin, PathIn};

    use crate::acl::OPEN_ACL_UNSAFE;
    use crate::types::Ttl;

    fn np(p: &str) -> PathIn {
        PathIn::Native(JString::from(p))
    }

    fn jstat(version: i32) -> JStat {
        JStat {
            version,
            ..JStat::default()
        }
    }

    fn item(wire_type: i32, err: i32, body: MultiBody) -> MultiItem {
        MultiItem {
            wire_type,
            err,
            body,
        }
    }

    fn error_item(err: i32) -> MultiItem {
        item(opcode::ERROR, err, MultiBody::Error(ErrorResponse { err }))
    }

    fn result(rc: i32, reply: Option<Reply>) -> RawResult {
        RawResult {
            rc,
            zxid: 0,
            origin: Origin::Reply,
            reply,
        }
    }

    #[test]
    fn writer_adds_ops_in_order() {
        let ttl = Ttl::from_millis(100).unwrap();
        let mut w = MultiWriter::new();
        let c = w
            .create("/a", b"d", CreateMode::Persistent, OPEN_ACL_UNSAFE)
            .unwrap();
        let s = w.set_data("/a", b"e", Version::new(0)).unwrap();
        let k = w.check("/a", Version::new(1)).unwrap();
        let d = w.delete("/a", Version::ANY).unwrap();
        let t = w
            .create(
                "/t",
                b"",
                CreateMode::PersistentWithTtl(ttl),
                OPEN_ACL_UNSAFE,
            )
            .unwrap();
        let x = w
            .create("/x", b"", CreateMode::Container, OPEN_ACL_UNSAFE)
            .unwrap();
        let q = w
            .create("/q/", b"", CreateMode::EphemeralSequential, &[])
            .unwrap();
        assert_eq!(
            [
                c.index(),
                s.index(),
                k.index(),
                d.index(),
                t.index(),
                x.index(),
                q.index()
            ],
            [0, 1, 2, 3, 4, 5, 6]
        );
        assert_eq!(w.len(), 7);
        let ops = w.to_ops();
        let types: Vec<i32> = ops.iter().map(MultiOpRef::op_type).collect();
        assert_eq!(
            types,
            [
                opcode::CREATE2,
                opcode::SET_DATA,
                opcode::CHECK,
                opcode::DELETE,
                opcode::CREATE_TTL,
                opcode::CREATE_CONTAINER,
                opcode::CREATE2
            ]
        );
        match ops.first().unwrap() {
            MultiOpRef::Create {
                path,
                data,
                acl,
                mode,
                ttl,
                defect,
                ..
            } => {
                assert_eq!(*path, Some(b"/a".as_slice()));
                assert_eq!(*data, Some(b"d".as_slice()));
                assert_eq!(acl.len(), 1);
                assert_eq!((*mode, *ttl), (0, -1));
                assert!(!defect.any());
            }
            other => panic!("not a create: {other:?}"),
        }
        assert!(matches!(
            ops.get(1).unwrap(),
            MultiOpRef::SetData { version: 0, .. }
        ));
        assert!(matches!(
            ops.get(2).unwrap(),
            MultiOpRef::Check { version: 1, .. }
        ));
        assert!(matches!(
            ops.get(3).unwrap(),
            MultiOpRef::Delete { version: -1, .. }
        ));
        assert!(matches!(
            ops.get(4).unwrap(),
            MultiOpRef::Create {
                mode: 5,
                ttl: 100,
                ..
            }
        ));
        assert!(matches!(
            ops.get(5).unwrap(),
            MultiOpRef::Create {
                mode: 4,
                ttl: -1,
                ..
            }
        ));
        match ops.get(6).unwrap() {
            MultiOpRef::Create {
                path, mode, acl, ..
            } => {
                assert_eq!(*path, Some(b"/q/".as_slice()));
                assert_eq!(*mode, 3);
                // Preserves empty ACL list representation, enabling server side validation refusal.
                assert_eq!(*acl, JVec::Items(Vec::new()));
            }
            other => panic!("not a create: {other:?}"),
        }
    }

    #[test]
    fn reader_adds_ops_in_order() {
        let mut r = MultiReader::new();
        assert_eq!(r.get_data("/a").unwrap(), 0);
        assert_eq!(r.get_children("/").unwrap(), 1);
        assert_eq!(r.get_data("/b").unwrap(), 2);
        assert_eq!(r.len(), 3);
        assert_eq!(
            r.to_ops(),
            vec![
                MultiOp::GetData { path: np("/a") },
                MultiOp::GetChildren { path: np("/") },
                MultiOp::GetData { path: np("/b") },
            ]
        );
    }

    fn committed() -> Reply {
        Reply::Multi(vec![
            item(
                opcode::CREATE2,
                0,
                MultiBody::Create2(Create2Response {
                    path: JString::from("/a0000000001"),
                    stat: jstat(0),
                }),
            ),
            item(
                opcode::CREATE,
                0,
                MultiBody::Create(CreateResponse {
                    path: JString::from("/b"),
                }),
            ),
            item(
                opcode::SET_DATA,
                0,
                MultiBody::SetData(SetDataResponse { stat: jstat(3) }),
            ),
            item(opcode::CHECK, 0, MultiBody::Empty),
            item(opcode::DELETE, 0, MultiBody::Empty),
        ])
    }

    #[test]
    fn decode_multi_gives_typed_results() {
        let mut w = MultiWriter::new();
        let c = w
            .create("/a", b"", CreateMode::PersistentSequential, OPEN_ACL_UNSAFE)
            .unwrap();
        let c1 = w
            .create("/b", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
            .unwrap();
        let s = w.set_data("/a", b"", Version::ANY).unwrap();
        let k = w.check("/a", Version::ANY).unwrap();
        let d = w.delete("/a", Version::ANY).unwrap();
        let res = decode_multi(result(0, Some(committed())), w.writer_id()).unwrap();
        let created = res.get(c).unwrap();
        assert_eq!(created.path, "/a0000000001");
        assert_eq!(res.get(c1).unwrap().path, "/b");
        assert_eq!(res.get(c1).unwrap().stat, Stat::default());
        assert_eq!(res.get(s).unwrap().version, Version::new(3));
        assert!(res.get(k).is_some());
        assert!(res.get(d).is_some());
        let all = res.clone().into_vec();
        assert_eq!(all.len(), 5);
        assert_eq!(all.get(3), Some(&OpResult::Check));
        assert_eq!(all.get(4), Some(&OpResult::Delete));
        assert_eq!(res.iter().count(), 5);
    }

    #[test]
    fn get_refuses_other_writers_and_types() {
        let mut w = MultiWriter::new();
        let c = w
            .create("/a", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
            .unwrap();
        let res = decode_multi(result(0, Some(committed())), w.writer_id()).unwrap();
        assert!(res.get(c).is_some());
        // Verifies that identical operation indices from distinct writer instances are rejected.
        let mut other = MultiWriter::new();
        let oc = other
            .create("/a", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
            .unwrap();
        assert!(res.get(oc).is_none());
        // Verifies that type mismatches between handle and result variant return None.
        let wrong: OpHandle<Stat> = OpHandle::new(0, w.writer_id());
        assert!(res.get(wrong).is_none());
        // Verifies that out-of-bounds indices return None.
        let past: OpHandle<()> = OpHandle::new(9, w.writer_id());
        assert!(res.get(past).is_none());
    }

    #[test]
    fn decode_multi_refuses_bad_replies() {
        let id = 1;
        let req = |r| decode_multi(r, id);
        assert_eq!(
            req(result(0, None)),
            Err(MultiError::Request(Error::MarshallingError))
        );
        assert_eq!(
            req(result(0, Some(Reply::Void))),
            Err(MultiError::Request(Error::MarshallingError))
        );
        assert_eq!(
            req(result(0, Some(Reply::MultiRead(Vec::new())))),
            Err(MultiError::Request(Error::MarshallingError))
        );
        // Framing defect: body payload variant does not correspond to header opcode.
        let bad = Reply::Multi(vec![item(
            opcode::DELETE,
            0,
            MultiBody::SetData(SetDataResponse { stat: jstat(0) }),
        )]);
        assert_eq!(
            req(result(0, Some(bad))),
            Err(MultiError::Request(Error::MarshallingError))
        );
        // Decoding defect: server path payload contains invalid UTF-8 bytes.
        let bad = Reply::Multi(vec![item(
            opcode::CREATE,
            0,
            MultiBody::Create(CreateResponse {
                path: JString::Bytes(vec![0xff]),
            }),
        )]);
        assert_eq!(
            req(result(0, Some(bad))),
            Err(MultiError::Request(Error::MarshallingError))
        );
        // Empty transaction commit yields an empty results collection without error.
        let res = req(result(0, Some(Reply::Multi(Vec::new())))).unwrap();
        assert_eq!(res.iter().count(), 0);
    }

    #[test]
    fn decode_multi_reports_the_failed_op() {
        // ZooKeeper server semantics: operations preceding failure report success, the failing
        // operation returns its causal code, and subsequent operations report
        // RuntimeInconsistency.
        let items = vec![
            error_item(0),
            error_item(rc::ZNONODE),
            error_item(rc::ZRUNTIMEINCONSISTENCY),
        ];
        let got = decode_multi(result(rc::ZNONODE, Some(Reply::Multi(items))), 1);
        assert_eq!(
            got,
            Err(MultiError::Aborted {
                index: 1,
                error: Error::NoNode,
                per_op: vec![None, Some(Error::NoNode), Some(Error::RuntimeInconsistency)],
            })
        );
        let e: Error = got.unwrap_err().into();
        assert_eq!(e, Error::NoNode);
    }

    #[test]
    fn decode_multi_aborts_on_error_items_with_a_zero_rc() {
        let items = vec![
            error_item(rc::ZRUNTIMEINCONSISTENCY),
            error_item(rc::ZBADVERSION),
        ];
        match decode_multi(result(0, Some(Reply::Multi(items))), 1) {
            Err(MultiError::Aborted { index, error, .. }) => {
                assert_eq!(index, 1);
                assert_eq!(error, Error::BadVersion);
            }
            other => panic!("not aborted: {other:?}"),
        }
        // Fallback selection: when all failing items report RuntimeInconsistency, select the
        // earliest failing index.
        let items = vec![error_item(0), error_item(rc::ZRUNTIMEINCONSISTENCY)];
        match decode_multi(result(0, Some(Reply::Multi(items))), 1) {
            Err(MultiError::Aborted { index, error, .. }) => {
                assert_eq!(index, 1);
                assert_eq!(error, Error::RuntimeInconsistency);
            }
            other => panic!("not aborted: {other:?}"),
        }
    }

    #[test]
    fn decode_multi_request_errors() {
        // Top-level transport or cluster state refusals fail the entire request without partial
        // decoding.
        assert_eq!(
            decode_multi(result(rc::ZNOTREADONLY, None), 1),
            Err(MultiError::Request(Error::NotReadOnly))
        );
        assert_eq!(
            decode_multi(RawResult::teardown(rc::ZCONNECTIONLOSS), 1),
            Err(MultiError::Request(Error::ConnectionLoss))
        );
        // Non-zero top-level return code without sub-operation error items indicates aggregate
        // transaction refusal.
        let items = vec![error_item(0)];
        assert_eq!(
            decode_multi(result(rc::ZSESSIONMOVED, Some(Reply::Multi(items))), 1),
            Err(MultiError::Request(Error::SessionMoved))
        );
    }

    fn read_items() -> Vec<MultiItem> {
        vec![
            item(
                opcode::GET_DATA,
                0,
                MultiBody::GetData(GetDataResponse {
                    data: JBuffer::from(b"v".to_vec()),
                    stat: jstat(2),
                }),
            ),
            item(
                opcode::GET_CHILDREN,
                0,
                MultiBody::GetChildren(GetChildrenResponse {
                    children: JVec::Items(vec![JString::from("c1"), JString::from("c2")]),
                }),
            ),
            item(
                opcode::GET_DATA,
                0,
                MultiBody::GetData(GetDataResponse {
                    data: JBuffer::Null,
                    stat: jstat(0),
                }),
            ),
        ]
    }

    #[test]
    fn decode_multi_read_gives_each_result() {
        let got = decode_multi_read(result(0, Some(Reply::MultiRead(read_items())))).unwrap();
        assert_eq!(got.len(), 3);
        assert_eq!(
            got.first().unwrap(),
            &Ok(ReadResult::Data {
                data: b"v".to_vec(),
                stat: Stat::from_jute(&jstat(2)),
            })
        );
        assert_eq!(
            got.get(1).unwrap(),
            &Ok(ReadResult::Children(vec!["c1".to_owned(), "c2".to_owned()]))
        );
        // Server null data payload decodes to an empty byte vector.
        assert!(matches!(
            got.get(2).unwrap(),
            Ok(ReadResult::Data { data, .. }) if data.is_empty()
        ));
    }

    #[test]
    fn decode_multi_read_refuses_bad_replies() {
        assert_eq!(
            decode_multi_read(result(0, None)),
            Err(Error::MarshallingError)
        );
        assert_eq!(
            decode_multi_read(result(0, Some(Reply::Multi(Vec::new())))),
            Err(Error::MarshallingError)
        );
        // Individual query framing defects fail their respective index without corrupting
        // surrounding items.
        let mut items = read_items();
        items.push(item(opcode::DELETE, 0, MultiBody::Empty));
        items.push(item(
            opcode::GET_CHILDREN,
            0,
            MultiBody::GetChildren(GetChildrenResponse {
                children: JVec::Items(vec![JString::Bytes(vec![0xfe])]),
            }),
        ));
        items.push(error_item(0));
        let got = decode_multi_read(result(0, Some(Reply::MultiRead(items)))).unwrap();
        assert_eq!(got.len(), 6);
        assert!(got.first().unwrap().is_ok());
        assert_eq!(got.get(3).unwrap(), &Err(Error::MarshallingError));
        assert_eq!(got.get(4).unwrap(), &Err(Error::MarshallingError));
        assert_eq!(got.get(5).unwrap(), &Err(Error::MarshallingError));
    }

    #[test]
    fn decode_multi_read_mixed_results() {
        let mut items = read_items();
        items.insert(1, error_item(rc::ZNONODE));
        items.push(error_item(rc::ZNOAUTH));
        let got = decode_multi_read(result(0, Some(Reply::MultiRead(items)))).unwrap();
        assert_eq!(got.len(), 5);
        assert!(got.first().unwrap().is_ok());
        assert_eq!(got.get(1).unwrap(), &Err(Error::NoNode));
        assert!(got.get(2).unwrap().is_ok());
        assert!(got.get(3).unwrap().is_ok());
        assert_eq!(got.get(4).unwrap(), &Err(Error::NoAuth));
        // Top-level return code without reply payload fails the entire batch.
        assert_eq!(
            decode_multi_read(result(rc::ZNOTREADONLY, None)),
            Err(Error::NotReadOnly)
        );
    }
}
