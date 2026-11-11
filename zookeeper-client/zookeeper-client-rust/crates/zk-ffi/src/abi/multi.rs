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

//! Multi-transaction request decomposition and C ABI boundary validation.
//!
//! Across the FFI boundary, C callers provide transactions as an array of `zoo_op_t`
//! structures accompanied by a prefix count that encompasses all operations up to and
//! including the first unsupported operation type. This module inspects each transaction
//! operation in place through raw pointer offsets into the `CZooOp` union layout,
//! constructing zero-copy borrowed `zk_proto::MultiOpRef` descriptors.
//!
//! Znode paths and data buffers remain pinned in caller-managed C memory throughout this
//! process. During the synchronous execution of `zkrs_submit`, `zk-proto` encodes these
//! borrowed buffers directly into the outgoing wire frame, ensuring that no borrowed
//! references outlive the call boundary. Legacy ZooKeeper C client semantics are rigorously
//! enforced: any unsupported operation type (outside the recognized set of create, container
//! create, delete, setData, and check) immediately triggers `ZUNIMPLEMENTED`, superseding
//! any earlier sub-operation errors, while malformed sub-operation buffers or ACL vectors
//! yield `ZMARSHALLINGERROR` during request serialization.

use std::ffi::{c_char, c_int};
use std::mem::offset_of;

use zk_jute::opcode;
use zk_proto::{InputDefect, MultiOpRef};

use super::legacy::{CAclVector, CCheckOp, CDeleteOp, CSetOp, CZooOp};
use super::raw;
use super::submit::acl_of_vec;
use super::types::zkrs_request;

/// Decomposes the valid prefix of a batch transaction request into borrowed sub-operation
/// descriptors.
///
/// Inspects the array slice `rq.ops[0..ops_len]` using raw pointer arithmetic over the C ABI
/// layout. For each operation, znode paths (`raw::cstr_ref`) and payload data buffers
/// (`raw::value_ref`) are borrowed without allocating heap storage. Scalar attributes (operation
/// versions, creation modes) and ACL records are extracted by value. If an ACL pointer or payload
/// buffer fails validation, a defect flag is attached to the descriptor to ensure proper reporting
/// during request serialization. An `ops_len` strictly smaller than the overall `count` indicates
/// that the C caller encountered an unsupported operation type, marking the end of the dispatchable
/// operation prefix.
///
/// All borrowed sub-operations are strictly bounded by the lifetime of `rq`. Because the caller
/// maintains the validity of `rq` across the synchronous submission call, all borrowed views expire
/// before C can deallocate or mutate the underlying memory buffers.
pub(crate) fn multi_ops(rq: &zkrs_request) -> Vec<MultiOpRef<'_>> {
    let p = rq.ops.cast::<CZooOp>();
    // A null pointer coupled with a non-zero length indicates an invalid caller contract.
    // Deferring this check through `submit::defects_of` causes the request to be rejected
    // during serialization without attempting illegal memory dereferences.
    match raw::array_len(p, rq.ops_len) {
        Ok(n) => (0..n).map(|i| multi_op(p.wrapping_add(i))).collect(),
        Err(_) => Vec::new(),
    }
}

/// Reads an individual legacy C transaction operation into a borrowed sub-operation descriptor.
///
/// Under the C ABI, `zoo_op_t` is an untagged union whose active member depends strictly on the
/// discriminant `type_`. Because create operations in transactions omit TTL fields, constructing a
/// full Rust reference to `CZooOp` would risk undefined behavior. Instead, individual fields are
/// copied via raw pointer byte offsets according to the discriminator.
fn multi_op<'a>(op: *const CZooOp) -> MultiOpRef<'a> {
    let op_type: c_int = field(op, offset_of!(CZooOp, type_));
    // Inspect only the union variant corresponding to the operation discriminant.
    // Unsupported opcodes bypass union member extraction entirely.
    match op_type {
        opcode::CREATE | opcode::CREATE_CONTAINER => {
            // The legacy C multi-operation path emits standard create requests and ignores TTL.
            let path: *const c_char = field(op, offset_of!(CZooOp, create_op.path));
            let p: *const c_char = field(op, offset_of!(CZooOp, create_op.data));
            let len: c_int = field(op, offset_of!(CZooOp, create_op.datalen));
            let v: *const CAclVector = field(op, offset_of!(CZooOp, create_op.acl));
            let (data, bad_value_buffer) = value_ref_of(p, len);
            let (acl, bad_acl) = acl_of_vec(v, false);
            MultiOpRef::Create {
                op_type,
                path: raw::cstr_ref(path),
                data,
                acl,
                mode: field(op, offset_of!(CZooOp, create_op.flags)),
                ttl: -1,
                defect: InputDefect {
                    bad_value_buffer,
                    bad_acl,
                    marshal_oom: false,
                },
            }
        }
        opcode::DELETE => {
            let d: CDeleteOp = field(op, offset_of!(CZooOp, create_op));
            MultiOpRef::Delete {
                path: raw::cstr_ref(d.path),
                version: d.version,
                defect: InputDefect::default(),
            }
        }
        opcode::SET_DATA => {
            let s: CSetOp = field(op, offset_of!(CZooOp, create_op));
            let (data, bad_value_buffer) = value_ref_of(s.data, s.datalen);
            MultiOpRef::SetData {
                path: raw::cstr_ref(s.path),
                data,
                version: s.version,
                defect: InputDefect {
                    bad_value_buffer,
                    ..InputDefect::default()
                },
            }
        }
        opcode::CHECK => {
            let k: CCheckOp = field(op, offset_of!(CZooOp, create_op));
            MultiOpRef::Check {
                path: raw::cstr_ref(k.path),
                version: k.version,
                defect: InputDefect::default(),
            }
        }
        t => MultiOpRef::Other { op_type: t },
    }
}

/// Reads a value of type `T` located at byte offset `off` from the operation pointer `op`.
///
/// In the C union memory representation, all operation structs align at the start offset
/// of `create_op`. Calculating the address via raw pointer offsetting and invoking
/// `raw::read_struct` ensures safe, copy-based field extraction without constructing
/// invalid intermediate references.
fn field<T: Copy>(op: *const CZooOp, off: usize) -> T {
    raw::read_struct(op.cast::<u8>().wrapping_add(off).cast::<T>())
}

/// Borrows the payload byte slice for an individual sub-operation.
///
/// Returns `(None, true)` if the buffer pointer or length violates FFI safety constraints,
/// signaling an input defect that prevents protocol encoding while preserving error diagnostics.
fn value_ref_of<'a>(p: *const c_char, len: i32) -> (Option<&'a [u8]>, bool) {
    match raw::value_ref(p, len) {
        Ok(v) => (v, false),
        Err(_) => (None, true),
    }
}

/// Test fixture utilities for synthesizing legacy C multi-operation layouts.
///
/// `TestOp` matches the ABI alignment and size of `zoo_op_t` using an untagged C union.
/// Test builders populate only the variant corresponding to the operation type, ensuring
/// that Miri flags any reads of uninitialized union memory.
#[cfg(test)]
pub(crate) mod test_support {
    use std::ffi::{CStr, c_char, c_int, c_void};

    use crate::abi::legacy::{CAclVector, CCheckOp, CCreateOp, CDeleteOp, CSetOp};

    /// Representation of a legacy `create2` operation without TTL fields, matching the memory
    /// layout initialized by `zoo_create2_op_init`.
    #[repr(C)]
    #[derive(Clone, Copy)]
    pub(crate) struct Create2 {
        path: *const c_char,
        data: *const c_char,
        datalen: c_int,
        buf: *mut c_char,
        buflen: c_int,
        acl: *const CAclVector,
        flags: c_int,
    }

    /// Untagged union mirroring the variant storage of `zoo_op_t`. The empty `none` variant leaves
    /// union memory unwritten.
    #[repr(C)]
    #[derive(Clone, Copy)]
    pub(crate) union Member {
        create: CCreateOp,
        create2: Create2,
        delete: CDeleteOp,
        set: CSetOp,
        check: CCheckOp,
        none: (),
    }

    /// Synthetic transaction operation mirroring the exact C ABI layout of `zoo_op_t`.
    #[repr(C)]
    #[derive(Clone, Copy)]
    pub(crate) struct TestOp {
        type_: c_int,
        m: Member,
    }

    impl TestOp {
        /// Constructs a test create operation without TTL parameters.
        pub(crate) fn create(
            t: i32,
            path: &CStr,
            data: *const c_char,
            datalen: i32,
            acl: *const CAclVector,
            mode: i32,
        ) -> Self {
            let create2 = Create2 {
                path: path.as_ptr(),
                data,
                datalen,
                buf: std::ptr::null_mut(),
                buflen: 0,
                acl,
                flags: mode,
            };
            Self {
                type_: t,
                m: Member { create2 },
            }
        }

        /// Constructs a test create operation carrying an explicit TTL field, matching
        /// `zoo_create_op_init`.
        pub(crate) fn create_ttl(t: i32, path: &CStr, mode: i32, ttl: i64) -> Self {
            let create = CCreateOp {
                path: path.as_ptr(),
                data: std::ptr::null(),
                datalen: -1,
                buf: std::ptr::null_mut(),
                buflen: 0,
                acl: std::ptr::null(),
                flags: mode,
                ttl,
            };
            Self {
                type_: t,
                m: Member { create },
            }
        }

        /// Constructs a test delete operation with a target path and expected node version.
        pub(crate) fn delete(path: &CStr, version: i32) -> Self {
            let delete = CDeleteOp {
                path: path.as_ptr(),
                version,
            };
            Self {
                type_: zk_jute::opcode::DELETE,
                m: Member { delete },
            }
        }

        /// Constructs a test setData operation with path, payload buffer, and expected version.
        pub(crate) fn set(path: &CStr, data: *const c_char, datalen: i32, version: i32) -> Self {
            let set = CSetOp {
                path: path.as_ptr(),
                data,
                datalen,
                version,
                stat: std::ptr::null_mut(),
            };
            Self {
                type_: zk_jute::opcode::SET_DATA,
                m: Member { set },
            }
        }

        /// Constructs a test check-version operation verifying the expected node version.
        pub(crate) fn check(path: &CStr, version: i32) -> Self {
            let check = CCheckOp {
                path: path.as_ptr(),
                version,
            };
            Self {
                type_: zk_jute::opcode::CHECK,
                m: Member { check },
            }
        }

        /// Constructs an uninitialized operation of type `t` with no active union payload.
        pub(crate) fn unset(t: i32) -> Self {
            Self {
                type_: t,
                m: Member { none: () },
            }
        }
    }

    /// Converts a test operation slice into a raw pointer suitable for `zkrs_request.ops`.
    pub(crate) fn ops_ptr(ops: &[TestOp]) -> *const c_void {
        ops.as_ptr().cast()
    }
}

#[cfg(test)]
mod tests {
    use std::ffi::CStr;

    use zk_jute::data::{ACL, Id};
    use zk_jute::{JString, JVec};

    use super::test_support::{TestOp, ops_ptr};
    use super::*;
    use crate::abi::legacy::{CAcl, CAclVector, CId};
    use crate::abi::types::ZKRS_OP_MULTI;

    fn multi(ops: &[TestOp]) -> zkrs_request {
        let n = i32::try_from(ops.len()).unwrap_or(0);
        zkrs_request {
            op: ZKRS_OP_MULTI,
            flags: 0,
            path: std::ptr::null(),
            data: std::ptr::null(),
            datalen: -1,
            acl: std::ptr::null(),
            mode: 0,
            ttl: -1,
            version: -1,
            reconfig_version: -1,
            joining: std::ptr::null(),
            leaving: std::ptr::null(),
            members: std::ptr::null(),
            watch_fn: 0,
            watch_ctx: 0,
            wtype: 0,
            ops: ops_ptr(ops),
            ops_len: n,
            count: n,
        }
    }

    fn legacy(p: &CStr) -> Option<&[u8]> {
        Some(p.to_bytes())
    }

    /// Synthesizes an expected create sub-operation reference descriptor with empty data and
    /// default mode.
    fn create_want(path: &CStr, acl: JVec<ACL>, defect: InputDefect) -> MultiOpRef<'_> {
        MultiOpRef::Create {
            op_type: opcode::CREATE,
            path: legacy(path),
            data: None,
            acl,
            mode: 0,
            ttl: -1,
            defect,
        }
    }

    fn empty() -> JVec<ACL> {
        JVec::Items(Vec::new())
    }

    #[test]
    fn the_test_op_has_the_layout_of_the_mirror() {
        assert_eq!(size_of::<TestOp>(), size_of::<CZooOp>());
        assert_eq!(align_of::<TestOp>(), align_of::<CZooOp>());
    }

    #[test]
    fn create_borrows_the_inputs() {
        let mut acl = [CAcl {
            perms: 1,
            id: CId {
                scheme: c"digest".as_ptr().cast_mut(),
                id: c"u:p".as_ptr().cast_mut(),
            },
        }];
        let v = CAclVector {
            count: 1,
            data: acl.as_mut_ptr(),
        };
        let ops = [TestOp::create(
            opcode::CREATE_CONTAINER,
            c"/c",
            c"ab".as_ptr(),
            2,
            &v,
            4,
        )];
        let want = MultiOpRef::Create {
            op_type: opcode::CREATE_CONTAINER,
            path: legacy(c"/c"),
            data: Some(b"ab"),
            acl: JVec::Items(vec![ACL {
                perms: 1,
                id: Id {
                    scheme: JString::from("digest"),
                    id: JString::from("u:p"),
                },
            }]),
            mode: 4,
            ttl: -1,
            defect: InputDefect::default(),
        };
        // Create operations in transaction batches omit TTL fields; this test also confirms that
        // Miri flags any unexpected TTL field reads.
        let rq = multi(&ops);
        assert_eq!(multi_ops(&rq), vec![want]);
    }

    #[test]
    fn create_ignores_the_ttl_and_accepts_a_null_acl() {
        // The legacy multi-operation implementation sends a standard create request without TTL.
        // Passing a null ACL vector translates to an empty ACL list without defects.
        let ops = [TestOp::create_ttl(opcode::CREATE, c"/t", 0, 77)];
        let rq = multi(&ops);
        let want = create_want(c"/t", empty(), InputDefect::default());
        assert_eq!(multi_ops(&rq), vec![want]);
    }

    #[test]
    fn create_reports_its_defects() {
        let null = std::ptr::null();
        // Passing a null data buffer pointer alongside a positive element count denotes a malformed
        // ACL vector.
        let bad = CAclVector {
            count: 3,
            data: std::ptr::null_mut(),
        };
        // A negative element count indicates a null ACL vector without triggering defect flags.
        let neg = CAclVector {
            count: -1,
            data: std::ptr::null_mut(),
        };
        let ops = [
            TestOp::create(opcode::CREATE, c"/a", null, -1, &bad, 0),
            TestOp::create(opcode::CREATE, c"/b", null, 9, std::ptr::null(), 0),
            TestOp::create(opcode::CREATE, c"/c", null, -1, &neg, 0),
        ];
        let defect = |bad_value_buffer, bad_acl| InputDefect {
            bad_value_buffer,
            bad_acl,
            marshal_oom: false,
        };
        let want = vec![
            create_want(c"/a", empty(), defect(false, true)),
            create_want(c"/b", empty(), defect(true, false)),
            create_want(c"/c", JVec::Null, defect(false, false)),
        ];
        let rq = multi(&ops);
        assert_eq!(multi_ops(&rq), want);
    }

    #[test]
    fn other_types_read_their_own_member() {
        let ops = [
            TestOp::delete(c"/d", 6),
            TestOp::check(c"/k", 7),
            TestOp::set(c"/s", c"".as_ptr(), 0, 8),
            TestOp::set(c"/n", std::ptr::null(), -1, 9),
            TestOp::set(c"/x", std::ptr::null(), -3, 10),
        ];
        let want = vec![
            MultiOpRef::Delete {
                path: legacy(c"/d"),
                version: 6,
                defect: InputDefect::default(),
            },
            MultiOpRef::Check {
                path: legacy(c"/k"),
                version: 7,
                defect: InputDefect::default(),
            },
            MultiOpRef::SetData {
                path: legacy(c"/s"),
                data: Some(b""),
                version: 8,
                defect: InputDefect::default(),
            },
            MultiOpRef::SetData {
                path: legacy(c"/n"),
                data: None,
                version: 9,
                defect: InputDefect::default(),
            },
            MultiOpRef::SetData {
                path: legacy(c"/x"),
                data: None,
                version: 10,
                defect: InputDefect {
                    bad_value_buffer: true,
                    ..InputDefect::default()
                },
            },
        ];
        let rq = multi(&ops);
        assert_eq!(multi_ops(&rq), want);
    }

    #[test]
    fn an_unknown_type_ends_the_prefix_and_reads_no_member() {
        // Operation type 99 has no initialized union variant; Miri validates that the parser leaves
        // union bytes untouched.
        let ops = [
            TestOp::check(c"/1", 1),
            TestOp::delete(c"/2", 2),
            TestOp::unset(99),
        ];
        let rq = multi(&ops);
        let got = multi_ops(&rq);
        assert_eq!(got.len(), 3);
        assert_eq!(got.last(), Some(&MultiOpRef::Other { op_type: 99 }));
    }

    #[test]
    fn the_prefix_keeps_the_order() {
        let ops = [TestOp::check(c"/1", 1), TestOp::delete(c"/2", 2)];
        // Sub-operation references borrow from the request; verifying that reference lifetimes
        // remain valid throughout evaluation.
        let mut rq = multi(&ops);
        let want = vec![
            MultiOpRef::Check {
                path: legacy(c"/1"),
                version: 1,
                defect: InputDefect::default(),
            },
            MultiOpRef::Delete {
                path: legacy(c"/2"),
                version: 2,
                defect: InputDefect::default(),
            },
        ];
        assert_eq!(multi_ops(&rq), want);
        // Only operations within the declared prefix length are evaluated.
        rq.ops_len = 1;
        assert_eq!(multi_ops(&rq).len(), 1);
        rq.ops_len = 0;
        assert!(multi_ops(&rq).is_empty());
        // A negative prefix length results in an empty operation list.
        rq.ops_len = -1;
        assert!(multi_ops(&rq).is_empty());
        // A null array pointer accompanied by a positive count represents a request-level defect
        // rather than a valid sub-operation.
        rq.ops = std::ptr::null();
        rq.ops_len = 2;
        assert!(multi_ops(&rq).is_empty());
    }
}
