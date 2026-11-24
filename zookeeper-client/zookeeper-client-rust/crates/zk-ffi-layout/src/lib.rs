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

//! Cross-language ABI layout and constant verification for the C compatibility layer.
//!
//! This crate validates that the host C compiler and `rustc` produce identical binary layouts
//! and numeric constants for every type and value crossing the FFI boundary of the drop-in C
//! client libraries:
//!
//! - The `#[repr(C)]` structural mirrors of public ZooKeeper records in
//!   `zk-ffi/src/abi/legacy.rs`, verified against `include/zookeeper.h` and the generated
//!   `zookeeper.jute.h`.
//! - The internal `#[repr(C)]` bridge types, enumerations, and constants in
//!   `zk-ffi/src/abi/types.rs`, verified against `c/shim/zkrs.h`.
//! - The protocol return codes in `zk-jute/src/rc.rs`, verified against `enum ZOO_ERRORS`.
//! - The wire operation codes in `zk-jute/src/opcode.rs`, verified against `proto.h`.
//!
//! Layout assertions verify total type size, alignment, field byte offsets, and field sizes,
//! while value assertions verify enumeration discriminants and preprocessor constants. Rust
//! definitions are incorporated directly via `#[path]` attributes so the test suite evaluates
//! the exact source definitions compiled into `zk-ffi`.
//!
//! During compilation, the build script compiles and executes a C layout probe across all four
//! feature permutations of the public headers (`plain`, `ssl`, `sasl`, and `full`), capturing
//! the C compiler's layout measurements for each configuration. Integration tests in `tests/`
//! then reconcile the C probe tables against the `rustc`-evaluated constant table.
//!
//! Complementary layout verifiers cover adjacent build stages: `cargo xtask layout gen`
//! synthesizes the public C probe (`c/tests/abi/layout/probe.c`) whose member catalog drives
//! this crate's public struct checks; `cargo xtask layout expect` and
//! `c/tests/abi/layout_check.c` validate target headers against golden architecture baselines;
//! and `c/shim/zks_layout_check.c` enforces compile time static assertions for 32-bit and
//! 64-bit `zkrs.h` layouts.

#![forbid(unsafe_code)]
// Unit tests imported via #[path] from zk-ffi modules use standard assertion macros.
#![cfg_attr(test, allow(clippy::disallowed_macros))]

use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::mem::offset_of;
use std::sync::OnceLock;

#[path = "../../zk-ffi/src/abi/legacy.rs"]
mod legacy;

#[path = "../../zk-ffi/src/abi/types.rs"]
#[allow(dead_code, unreachable_pub, reason = "the tables use only the layout")]
mod types;

#[path = "../../zk-jute/src/rc.rs"]
#[allow(dead_code, unreachable_pub, reason = "the tables use only the values")]
mod rc;

#[path = "../../zk-jute/src/opcode.rs"]
#[allow(dead_code, unreachable_pub, reason = "the tables use only the values")]
mod opcode;

/// Synthetic type group identifier for `ZKRS_*` preprocessor constants defined in `zkrs.h`.
pub const ZKRS_DEFINES: &str = "zkrs.h";
/// Synthetic type group identifier for protocol operation codes defined in `proto.h`.
pub const OPCODES: &str = "proto.h";
/// Type group identifier for public ZooKeeper status codes (`enum ZOO_ERRORS`).
pub const ZOO_ERRORS: &str = "enum ZOO_ERRORS";

/// A single measured structural layout property or constant value for an ABI type.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Fact {
    /// Property category: `sizeof`, `alignof`, `offsetof`, `fieldsize`, `signed`, or `value`.
    pub fact: &'static str,
    /// Target C type name (such as `struct Stat`) or symbolic value group identifier.
    pub ty: &'static str,
    /// Struct field, enum variant, or constant identifier, or `-` for whole-type properties.
    pub member: &'static str,
    /// Measured size/offset in bytes, signedness flag, or constant integer value.
    pub value: i64,
}

impl Fact {
    /// Constructs a layout or constant fact entry.
    #[must_use]
    pub const fn new(
        fact: &'static str,
        ty: &'static str,
        member: &'static str,
        value: i64,
    ) -> Self {
        Self {
            fact,
            ty,
            member,
            value,
        }
    }

    /// Returns true if this entry represents a constant or enum discriminant rather than a
    /// structural layout measurement.
    fn is_value(&self) -> bool {
        self.fact == "value"
    }
}

impl fmt::Display for Fact {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{} {} {} = {}",
            self.fact, self.ty, self.member, self.value
        )
    }
}

/// Captured C compiler probe output for a single header feature configuration.
#[derive(Clone, Copy, Debug)]
pub struct Probe {
    /// Configuration profile identifier (`plain`, `ssl`, `sasl`, or `full`).
    pub config: &'static str,
    /// Preprocessor defines and compiler include flags applied when building the probe.
    pub cflags: &'static str,
    /// Compiler diagnostic output if probe compilation failed, or empty on success.
    pub error: &'static str,
    /// Sequence of layout and constant facts emitted by the compiled C probe binary.
    pub facts: &'static [Fact],
}

/// Converts a `usize` byte measurement to `i64`, returning `-1` on overflow.
const fn n(v: usize) -> i64 {
    if v > i64::MAX as usize { -1 } else { v as i64 }
}

/// Evaluates the byte size of a struct field selected by a projection function.
const fn field_size<T, F>(_get: fn(&T) -> &F) -> i64 {
    n(size_of::<F>())
}

include!(concat!(env!("OUT_DIR"), "/facts.rs"));

/// Returns the captured C probe results for all active build configurations.
#[must_use]
pub fn probes() -> &'static [Probe] {
    C_PROBES
}

/// Computes the byte offset of a nested field relative to the enclosing parent structure.
const fn nested(member: usize, field: usize) -> i64 {
    match member.checked_add(field) {
        Some(v) => n(v),
        None => -1,
    }
}

/// Generates layout fact entries for a union variant of `zoo_op_t`.
///
/// Because all union variants share the starting byte offset of `create_op` (the largest
/// variant in `CZooOp`), nested field offsets are calculated relative to `create_op`.
/// The `head` arm emits the base offset and total size of the union member itself.
macro_rules! zoo_op_member {
    (head $m:literal, $t:ty) => {
        [
            Fact::new("offsetof", "zoo_op_t", $m, n(offset_of!(legacy::CZooOp, create_op))),
            Fact::new("fieldsize", "zoo_op_t", $m, n(size_of::<$t>())),
        ]
    };
    ($m:literal, $t:ty, [$($f:ident),*]) => {
        [$(
            Fact::new(
                "offsetof",
                "zoo_op_t",
                concat!($m, ".", stringify!($f)),
                nested(offset_of!(legacy::CZooOp, create_op), offset_of!($t, $f)),
            ),
            Fact::new(
                "fieldsize",
                "zoo_op_t",
                concat!($m, ".", stringify!($f)),
                field_size(|v: &$t| &v.$f),
            ),
        )*]
    };
}

/// Supplementary layout facts for the union variants inside `zoo_op_t`. Top-level facts for
/// `type` and `create_op` are emitted directly by the build script from `CZooOp`.
static ZOO_OP_FACTS: &[&[Fact]] = &[
    &zoo_op_member!(
        "create_op",
        legacy::CCreateOp,
        [path, data, datalen, buf, buflen, acl, flags, ttl]
    ),
    &zoo_op_member!(head "delete_op", legacy::CDeleteOp),
    &zoo_op_member!("delete_op", legacy::CDeleteOp, [path, version]),
    &zoo_op_member!(head "set_op", legacy::CSetOp),
    &zoo_op_member!("set_op", legacy::CSetOp, [path, data, datalen, version, stat]),
    &zoo_op_member!(head "check_op", legacy::CCheckOp),
    &zoo_op_member!("check_op", legacy::CCheckOp, [path, version]),
];

/// Returns the complete set of Rust ABI facts, combining build-generated entries with
/// the `zoo_op_t` union variant descriptors.
#[must_use]
pub fn rust_facts() -> &'static [Fact] {
    static ALL: OnceLock<Vec<Fact>> = OnceLock::new();
    ALL.get_or_init(|| {
        let mut all = RUST_FACTS.to_vec();
        for part in ZOO_OP_FACTS {
            all.extend_from_slice(part);
        }
        all
    })
}

/// Determines whether a C fact group requires a mandatory counterpart in Rust.
///
/// Every private `zkrs_*` type and shared constant table must exist in Rust, whereas public
/// C header types are verified only when explicitly mirrored by `zk-ffi`.
fn needs_rust(is_value: bool, ty: &str) -> bool {
    ty.starts_with("zkrs_") || (is_value && [ZKRS_DEFINES, ZOO_ERRORS, OPCODES].contains(&ty))
}

/// Determines whether Rust may define additional constants absent from the C headers.
///
/// The native Rust client supports protocol opcodes that the legacy C client never issues.
fn rust_may_add(ty: &str) -> bool {
    ty == OPCODES
}

/// Normalizes field and constant identifiers for cross-language comparison by stripping
/// underscores and folding ASCII characters to lowercase.
fn norm(member: &str) -> String {
    member
        .chars()
        .filter(|c| *c != '_')
        .map(|c| c.to_ascii_lowercase())
        .collect()
}

/// Normalized lookup key `(fact_kind, type_name, normalized_member)` used during reconciliation.
type Key = (&'static str, &'static str, String);

fn key(f: &Fact) -> Key {
    (f.fact, f.ty, norm(f.member))
}

/// Reconciles C probe facts against Rust facts, returning a diagnostic description for each
/// discrepancy.
///
/// Layout properties and value constants are partitioned into distinct groups per type.
/// Whenever Rust defines a group, every C fact in that group must match its Rust counterpart
/// and vice versa. Unmirrored C groups are flagged as errors only for private `zkrs_*` types
/// and mandatory constant tables.
#[must_use]
pub fn compare(c: &[Fact], rust: &[Fact]) -> Vec<String> {
    let mut bad = Vec::new();
    let mut rmap: BTreeMap<Key, i64> = BTreeMap::new();
    for f in rust {
        if rmap.insert(key(f), f.value).is_some() {
            bad.push(format!(
                "Rust has two facts for {} {} {}",
                f.fact, f.ty, f.member
            ));
        }
    }
    let rgroups: BTreeSet<(bool, &str)> = rust.iter().map(|f| (f.is_value(), f.ty)).collect();
    let cgroups: BTreeSet<(bool, &str)> = c.iter().map(|f| (f.is_value(), f.ty)).collect();
    let mut seen: BTreeSet<Key> = BTreeSet::new();
    let mut missing: BTreeSet<(bool, &str)> = BTreeSet::new();
    for f in c {
        let group = (f.is_value(), f.ty);
        if !rgroups.contains(&group) {
            if needs_rust(f.is_value(), f.ty) && missing.insert(group) {
                bad.push(format!(
                    "C {} {} has no Rust definition",
                    f.ty,
                    kind(f.is_value())
                ));
            }
            continue;
        }
        let k = key(f);
        match rmap.get(&k) {
            Some(v) if *v == f.value => {}
            Some(v) => bad.push(format!(
                "{} {} {}: C {}, Rust {v}",
                f.fact, f.ty, f.member, f.value
            )),
            None => bad.push(format!("C {f} has no Rust counterpart")),
        }
        seen.insert(k);
    }
    for f in rust {
        let group = (f.is_value(), f.ty);
        if seen.contains(&key(f)) || rust_may_add(f.ty) {
            continue;
        }
        if !cgroups.contains(&group) {
            if missing.insert(group) {
                bad.push(format!(
                    "Rust {} {} has no C definition",
                    f.ty,
                    kind(f.is_value())
                ));
            }
        } else {
            bad.push(format!("Rust {f} has no C counterpart"));
        }
    }
    bad
}

fn kind(is_value: bool) -> &'static str {
    if is_value { "values" } else { "layout" }
}
