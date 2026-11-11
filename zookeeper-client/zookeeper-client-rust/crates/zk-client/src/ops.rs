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

//! Protocol operation constructors translating public client arguments into wire-ready
//! [`zk_proto::Op`] specifications.
//!
//! Operation builders accept paths validated by [`crate::path::checked`], ensuring that
//! argument conversion never fails at runtime. Path strings are wrapped as [`PathIn::Native`],
//! deferring chroot prefix resolution to the connection core. All constructor functions are side-
//! effect free and execute prior to acquiring driver locks.

use zk_jute::data::ACL;
use zk_jute::{JBuffer, JString, JVec, opcode};
use zk_proto::{Op, PathIn, RemoveHint};

use crate::acl::{Acl, Id, Perms};
use crate::auth::lossy;
use crate::tokens::WatchId;
use crate::types::CreateMode;

/// Type alias representing a protocol operation carrying native watcher identifier tokens.
pub(crate) type NativeOp = Op<WatchId>;

/// Wraps a validated path slice as a native input path for chroot prefix resolution.
pub(crate) fn path_in(p: &str) -> PathIn {
    PathIn::Native(JString::from(p))
}

/// Maps a high-level [`CreateMode`] to its wire-level integer representation and associated
/// millisecond TTL.
///
/// Follows ZooKeeper server conventions: Persistent (0), Ephemeral (1), PersistentSequential (2),
/// EphemeralSequential (3), Container (4), PersistentWithTtl (5), and
/// PersistentSequentialWithTtl (6).
/// Modes without an associated TTL return `-1`.
pub(crate) fn create_mode_wire(mode: &CreateMode) -> (i32, i64) {
    match mode {
        CreateMode::Persistent => (0, NO_TTL),
        CreateMode::Ephemeral => (1, NO_TTL),
        CreateMode::PersistentSequential => (2, NO_TTL),
        CreateMode::EphemeralSequential => (3, NO_TTL),
        CreateMode::Container => (4, NO_TTL),
        CreateMode::PersistentWithTtl(t) => (5, ttl_wire(t.as_millis())),
        CreateMode::PersistentSequentialWithTtl(t) => (6, ttl_wire(t.as_millis())),
    }
}

/// Sentinel TTL value indicating that the operation does not specify a time-to-live.
const NO_TTL: i64 = -1;

/// Converts a millisecond TTL into wire-format integer, saturating safely on out-of-range values.
fn ttl_wire(ms: u64) -> i64 {
    i64::try_from(ms).unwrap_or(i64::MAX)
}

/// Resolves the specific wire opcode utilized for a `create` sub-operation within an atomic
/// transaction.
///
/// Selects Container (`opcode::CREATE_CONTAINER`), TTL modes (`opcode::CREATE_TTL`), or
/// `create2` (`opcode::CREATE2`).
/// Standalone operations defer opcode selection to protocol connection logic.
pub(crate) fn create_op_code(mode: &CreateMode) -> i32 {
    match mode {
        CreateMode::Container => opcode::CREATE_CONTAINER,
        CreateMode::PersistentWithTtl(_) | CreateMode::PersistentSequentialWithTtl(_) => {
            opcode::CREATE_TTL
        }
        _ => opcode::CREATE2,
    }
}

/// Serializes a slice of high-level [`Acl`] specifications into Jute wire records.
pub(crate) fn acl_to_jute(acl: &[Acl]) -> JVec<ACL> {
    JVec::Items(
        acl.iter()
            .map(|a| ACL {
                perms: a.perms.bits(),
                id: zk_jute::data::Id {
                    scheme: JString::from(a.id.scheme.as_ref()),
                    id: JString::from(a.id.id.as_ref()),
                },
            })
            .collect(),
    )
}

/// Deserializes a Jute vector of ACL records into high-level [`Acl`] entries, preserving unknown
/// permission bits and decoding non-UTF-8 identifiers lossily.
pub(crate) fn acl_from_jute(v: JVec<ACL>) -> Vec<Acl> {
    v.into_vec()
        .unwrap_or_default()
        .into_iter()
        .map(|a| Acl {
            perms: Perms::from_bits_retain(a.perms),
            id: Id::new(lossy(&a.id.scheme), lossy(&a.id.id)),
        })
        .collect()
}

/// Constructs a node creation operation (`create`).
///
/// Opcode selection between standard `create2`, container creation, or TTL assignment is
/// determined by the supplied [`CreateMode`]. Payload data is omitted here and passed directly to
/// [`crate::shared::Shared::request_with_data`] as a borrowed slice, allowing zero-copy
/// serialization into the outbound transmission buffer.
pub(crate) fn create(path: &str, mode: &CreateMode, acl: &[Acl]) -> NativeOp {
    let (mode, ttl) = create_mode_wire(mode);
    // Explicitly request create2 response semantics (returning both path and Stat metadata) across
    // all creation variants.
    Op::Create {
        path: path_in(path),
        data: JBuffer::Null,
        acl: acl_to_jute(acl),
        mode,
        ttl,
        create2: true,
    }
}

/// Constructs a node deletion operation (`opcode::DELETE`).
pub(crate) fn delete(path: &str, version: i32) -> NativeOp {
    Op::Delete {
        path: path_in(path),
        version,
    }
}

/// Constructs a node existence check (`opcode::EXISTS`) with an optional watch registration.
pub(crate) fn exists(path: &str, watch: Option<WatchId>) -> NativeOp {
    Op::Exists {
        path: path_in(path),
        watch,
    }
}

/// Constructs a node data query (`opcode::GET_DATA`) with an optional watch registration.
pub(crate) fn get_data(path: &str, watch: Option<WatchId>) -> NativeOp {
    Op::GetData {
        path: path_in(path),
        watch,
    }
}

/// Constructs an ensemble configuration query against `/zookeeper/config` bypassing chroot
/// prefixing.
pub(crate) fn get_config(watch: Option<WatchId>) -> NativeOp {
    Op::GetConfig { watch }
}

/// Constructs a node data modification operation (`opcode::SET_DATA`). Payload bytes are borrowed
/// during dispatch.
pub(crate) fn set_data(path: &str, version: i32) -> NativeOp {
    Op::SetData {
        path: path_in(path),
        data: JBuffer::Null,
        version,
    }
}

/// Constructs an Access Control List query (`opcode::GET_ACL`).
pub(crate) fn get_acl(path: &str) -> NativeOp {
    Op::GetAcl {
        path: path_in(path),
    }
}

/// Constructs an Access Control List update (`opcode::SET_ACL`) requesting resulting node metadata
/// stat.
pub(crate) fn set_acl(path: &str, acl: &[Acl], version: i32) -> NativeOp {
    Op::SetAclStat {
        path: path_in(path),
        acl: acl_to_jute(acl),
        version,
    }
}

/// Constructs a child enumeration query (`opcode::GET_CHILDREN` or `opcode::GET_CHILDREN2` when
/// stat is requested).
pub(crate) fn get_children(path: &str, watch: Option<WatchId>, with_stat: bool) -> NativeOp {
    Op::GetChildren {
        path: path_in(path),
        watch,
        with_stat,
    }
}

/// Constructs an asynchronous state synchronization barrier (`opcode::SYNC`).
pub(crate) fn sync(path: &str) -> NativeOp {
    Op::Sync {
        path: path_in(path),
    }
}

/// Constructs a dynamic cluster reconfiguration request (`opcode::RECONFIG`).
pub(crate) fn reconfig(
    joining: JString,
    leaving: JString,
    members: JString,
    version: i64,
) -> NativeOp {
    Op::Reconfig {
        joining,
        leaving,
        members,
        version,
    }
}

/// Constructs an explicit watch deregistration request (`opcode::REMOVE_WATCHES`) for local
/// watchers.
pub(crate) fn remove_watches(path: &str, wtype: i32, local: bool) -> NativeOp {
    Op::RemoveWatches {
        path: path_in(path),
        wtype,
        watcher: None,
        local,
        all: true,
    }
}

/// Constructs an untracked server watch deregistration triggered when the last local watcher on a
/// path is dropped.
pub(crate) fn remove_hint(hint: RemoveHint) -> NativeOp {
    Op::RemoveWatches {
        path: PathIn::Native(hint.path),
        wtype: hint.wtype,
        watcher: None,
        local: false,
        all: true,
    }
}

/// Constructs a persistent or persistent recursive watch registration (`opcode::ADD_WATCH`).
pub(crate) fn add_watch(path: &str, mode: i32, watch: WatchId) -> NativeOp {
    Op::AddWatch {
        path: path_in(path),
        mode,
        watch,
    }
}

/// Constructs a query enumerating ephemeral nodes created by the active session matching a path
/// prefix.
pub(crate) fn get_ephemerals(prefix: &str) -> NativeOp {
    Op::GetEphemerals {
        prefix: path_in(prefix),
    }
}

/// Constructs a query computing recursive child node counts rooted at the specified path.
pub(crate) fn get_all_children_number(path: &str) -> NativeOp {
    Op::GetAllChildrenNumber {
        path: path_in(path),
    }
}

/// Constructs a session identity introspection query (`opcode::WHO_AM_I`).
pub(crate) fn who_am_i() -> NativeOp {
    Op::WhoAmI
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn simple_builders_wrap_native_paths() {
        match delete("/a", 3) {
            Op::Delete { path, version } => {
                assert_eq!(path, PathIn::Native(JString::from("/a")));
                assert_eq!(version, 3);
            }
            _ => panic!("not a delete"),
        }
        assert!(matches!(who_am_i(), Op::WhoAmI));
    }

    use crate::acl::{CREATOR_ALL_ACL, OPEN_ACL_UNSAFE, READ_ACL_UNSAFE};
    use crate::types::Ttl;

    fn ttl(ms: u64) -> Ttl {
        Ttl::from_millis(ms).unwrap()
    }

    #[test]
    fn create_modes_use_the_java_flags() {
        let cases = [
            (CreateMode::Persistent, (0, -1), opcode::CREATE2),
            (CreateMode::Ephemeral, (1, -1), opcode::CREATE2),
            (CreateMode::PersistentSequential, (2, -1), opcode::CREATE2),
            (CreateMode::EphemeralSequential, (3, -1), opcode::CREATE2),
            (CreateMode::Container, (4, -1), opcode::CREATE_CONTAINER),
            (
                CreateMode::PersistentWithTtl(ttl(1000)),
                (5, 1000),
                opcode::CREATE_TTL,
            ),
            (
                CreateMode::PersistentSequentialWithTtl(Ttl::MAX),
                (6, 0xFF_FFFF_FFFF),
                opcode::CREATE_TTL,
            ),
        ];
        for (mode, wire, op) in cases {
            assert_eq!(create_mode_wire(&mode), wire, "{mode:?}");
            assert_eq!(create_op_code(&mode), op, "{mode:?}");
            // Verify that TTL encoding conforms strictly to wire protocol constraints.
            assert_eq!(
                zk_proto::check_ttl(wire.0, wire.1),
                Ok(()),
                "{mode:?} gives a TTL that proto refuses"
            );
        }
    }

    #[test]
    fn create_asks_for_the_stat_reply() {
        let op = create(
            "/a",
            &CreateMode::PersistentWithTtl(ttl(5)),
            OPEN_ACL_UNSAFE,
        );
        match op {
            Op::Create {
                path,
                data,
                acl,
                mode,
                ttl,
                create2,
            } => {
                assert_eq!(path, PathIn::Native(JString::from("/a")));
                // Payload data is transported separately as a borrowed slice during submission.
                assert_eq!(data, JBuffer::Null);
                assert_eq!(acl.len(), 1);
                assert_eq!((mode, ttl), (5, 5));
                assert!(create2);
            }
            _ => panic!("not a create"),
        }
        match create("/b", &CreateMode::Ephemeral, CREATOR_ALL_ACL) {
            Op::Create { mode, ttl, .. } => {
                assert_eq!((mode, ttl), (1, -1));
            }
            _ => panic!("not a create"),
        }
    }

    #[test]
    fn acl_round_trip() {
        let mut list = Vec::new();
        list.extend_from_slice(OPEN_ACL_UNSAFE);
        list.extend_from_slice(READ_ACL_UNSAFE);
        list.extend_from_slice(CREATOR_ALL_ACL);
        list.push(Acl {
            perms: Perms::READ | Perms::ADMIN,
            id: Id::ip("10.0.0.0/8"),
        });
        list.push(Acl {
            perms: Perms::WRITE,
            id: Id::new("digest", "u:abc="),
        });
        let wire = acl_to_jute(&list);
        let first = wire.iter().next().unwrap();
        assert_eq!(first.perms, 0x1f);
        assert_eq!(first.id.scheme, JString::from("world"));
        assert_eq!(first.id.id, JString::from("anyone"));
        assert_eq!(acl_from_jute(wire), list);
    }

    #[test]
    fn empty_acl_is_present() {
        assert_eq!(acl_to_jute(&[]), JVec::Items(Vec::new()));
        assert!(acl_from_jute(JVec::Null).is_empty());
    }

    #[test]
    fn acl_from_jute_keeps_unknown_bits_and_converts_lossily() {
        let wire = JVec::Items(vec![ACL {
            perms: 0x41,
            id: zk_jute::data::Id {
                scheme: JString::Bytes(vec![b'x', 0xff]),
                id: JString::Null,
            },
        }]);
        let acl = acl_from_jute(wire);
        let a = acl.first().unwrap();
        assert_eq!(a.perms.bits(), 0x41);
        assert!(a.perms.contains(Perms::READ));
        assert_eq!(a.id.scheme, "x\u{fffd}");
        assert_eq!(a.id.id, "");
    }
}
