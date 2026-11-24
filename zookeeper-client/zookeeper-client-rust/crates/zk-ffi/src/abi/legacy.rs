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

//! Canonical `repr(C)` memory layout mirrors corresponding to legacy ZooKeeper C client
//! data structures.
//!
//! This module defines binary compatible Rust representations matching the public structures
//! declared in `zookeeper.h` and `zookeeper.jute.h`. Memory writers in `abi::raw::writers`
//! marshal output results directly into these layouts, while request parsers in `submit` and
//! `multi` read caller provided ACL vectors and `zoo_op_t` transaction arrays through these
//! structural mirrors.
//!
//! Maintaining exact memory layout parity (size, field offset, and alignment) is vital to FFI
//! safety. The module depends strictly on standard library primitives, enabling external layout
//! verification suites (such as the `zk-ffi-layout` harness) to incorporate this file via
//! `#[path]` attributes and assert field-by-field binary equivalence against the host C
//! compiler's memory model.

#![allow(dead_code, reason = "the writers and the layout check use the mirrors")]

use std::ffi::{c_char, c_int};

/// Binary layout mirror of the legacy ZooKeeper node metadata structure (`struct Stat`).
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct CStat {
    pub(crate) czxid: i64,
    pub(crate) mzxid: i64,
    pub(crate) ctime: i64,
    pub(crate) mtime: i64,
    pub(crate) version: i32,
    pub(crate) cversion: i32,
    pub(crate) aversion: i32,
    pub(crate) ephemeral_owner: i64,
    pub(crate) data_length: i32,
    pub(crate) num_children: i32,
    pub(crate) pzxid: i64,
}

/// Binary layout mirror of the legacy dynamic string array container (`struct String_vector`).
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct CStringVector {
    pub(crate) count: i32,
    pub(crate) data: *mut *mut c_char,
}

/// Binary layout mirror of the legacy authentication identity descriptor (`struct Id`).
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct CId {
    pub(crate) scheme: *mut c_char,
    pub(crate) id: *mut c_char,
}

/// Binary layout mirror of the legacy Access Control List specification (`struct ACL`).
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct CAcl {
    pub(crate) perms: i32,
    pub(crate) id: CId,
}

/// Binary layout mirror of the legacy Access Control List array container (`struct ACL_vector`).
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct CAclVector {
    pub(crate) count: i32,
    pub(crate) data: *mut CAcl,
}

/// Binary layout mirror of the multi-transaction sub-operation outcome (`zoo_op_result_t`).
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct COpResult {
    pub(crate) err: c_int,
    pub(crate) value: *mut c_char,
    pub(crate) valuelen: c_int,
    pub(crate) stat: *mut CStat,
}

/// Binary layout mirror of the node creation payload variant (`zoo_op_t.create_op`).
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct CCreateOp {
    pub(crate) path: *const c_char,
    pub(crate) data: *const c_char,
    pub(crate) datalen: c_int,
    pub(crate) buf: *mut c_char,
    pub(crate) buflen: c_int,
    pub(crate) acl: *const CAclVector,
    pub(crate) flags: c_int,
    /// Optional Time-To-Live duration in milliseconds; unused by legacy
    /// `zoo_create2_op_init` initializers.
    pub(crate) ttl: i64,
}

/// Binary layout mirror of the node deletion payload variant (`zoo_op_t.delete_op`).
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct CDeleteOp {
    pub(crate) path: *const c_char,
    pub(crate) version: c_int,
}

/// Binary layout mirror of the node data mutation payload variant (`zoo_op_t.set_op`).
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct CSetOp {
    pub(crate) path: *const c_char,
    pub(crate) data: *const c_char,
    pub(crate) datalen: c_int,
    pub(crate) version: c_int,
    pub(crate) stat: *mut CStat,
}

/// Binary layout mirror of the version check payload variant (`zoo_op_t.check_op`).
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct CCheckOp {
    pub(crate) path: *const c_char,
    pub(crate) version: c_int,
}

/// Binary layout mirror of an individual tagged union operation in a batch (`zoo_op_t`).
///
/// Because `CCreateOp` constitutes the largest variant within the C union, embedding it
/// establishes identical overall struct sizing and memory alignment. Other operation variants
/// share the base offset of `create_op`. To ensure memory safety and avoid synthesizing
/// invalid Rust references to uninitialized union fields, sub-operation readers access
/// specific variant fields exclusively via raw pointer offset arithmetic based on the active
/// `type_` discriminator.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct CZooOp {
    pub(crate) type_: c_int,
    pub(crate) create_op: CCreateOp,
}
