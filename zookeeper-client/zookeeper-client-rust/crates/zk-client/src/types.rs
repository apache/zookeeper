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

//! Domain model primitives: identifiers, transaction coordinates, node metadata, and creation
//! policies.
//!
//! This module provides strongly typed abstractions for ZooKeeper transaction IDs ([`Zxid`]),
//! session credentials ([`SessionId`], [`SessionPassword`]), state modification versions
//! ([`Version`]), znode metadata ([`Stat`]), time-to-live boundaries ([`Ttl`]), and node lifecycle
//! modes ([`CreateMode`]). Protocol level wire encoding for operations is handled separately.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use zeroize::Zeroizing;

use crate::error::Error;

/// Monotonically increasing ZooKeeper transaction identifier assigned by the ensemble leader.
///
/// Encodes an epoch number in the high 32 bits and a sequential counter in the low 32 bits.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Default)]
#[cfg_attr(
    feature = "serde",
    derive(serde::Serialize, serde::Deserialize),
    serde(transparent)
)]
pub struct Zxid(pub i64);

/// Unique 64-bit identifier assigned to an active ZooKeeper client session.
///
/// Formats via [`Display`](std::fmt::Display) and [`Debug`](std::fmt::Debug) as a 16-digit
/// zero-padded hexadecimal string (`0x...`), matching canonical ZooKeeper server and client log
/// conventions.
#[derive(Copy, Clone, PartialEq, Eq, Hash)]
#[cfg_attr(
    feature = "serde",
    derive(serde::Serialize, serde::Deserialize),
    serde(transparent)
)]
pub struct SessionId(pub i64);

impl std::fmt::Display for SessionId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "0x{:016x}", self.0)
    }
}

impl std::fmt::Debug for SessionId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Display::fmt(self, f)
    }
}

/// Cryptographic session secret used to authenticate session reconnection across ensemble members.
///
/// Wraps 16 bytes of secret key material in memory protected by zeroization on drop, and
/// suppresses secret exposure by formatting as `<redacted>` in [`Debug`](std::fmt::Debug).
#[derive(Clone, PartialEq, Eq)]
pub struct SessionPassword(Zeroizing<[u8; 16]>);

impl SessionPassword {
    /// Constructs a session password wrapper from a 16-byte cryptographic array.
    #[must_use]
    pub fn from_bytes(b: [u8; 16]) -> Self {
        Self(Zeroizing::new(b))
    }

    /// Accesses the raw secret bytes. Callers should handle these bytes with care to prevent
    /// disclosure.
    #[must_use]
    pub fn as_bytes(&self) -> &[u8; 16] {
        &self.0
    }
}

impl std::fmt::Debug for SessionPassword {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("<redacted>")
    }
}

/// Optimistic concurrency control version counter associated with a znode's data, ACL, or
/// children.
///
/// Version numbers start at 0 upon node creation and increment sequentially on each state
/// modification. [`Version::ANY`] represents the wire sentinel `-1`, disabling conditional
/// version checks.
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[cfg_attr(
    feature = "serde",
    derive(serde::Serialize, serde::Deserialize),
    serde(transparent)
)]
pub struct Version(i32);

impl Version {
    /// Sentinel version matching any current server version (wire representation `-1`).
    pub const ANY: Version = Version(-1);

    /// Instantiates a version counter from an integer value.
    #[must_use]
    pub const fn new(v: i32) -> Self {
        Self(v)
    }

    /// Extracts the underlying raw wire integer version.
    #[must_use]
    pub const fn get(self) -> i32 {
        self.0
    }
}

mod sealed {
    /// Private trait sealing [`super::VersionArg`] against external implementations.
    pub trait Sealed {
        /// Extracts the wire version integer, returning -1 when unconstrained.
        fn wire_version(&self) -> i32;
    }
}

/// Polymorphic argument accepting explicit [`Version`] values or optional conditions
/// (`Option<Version>`).
///
/// Enables ergonomic API usage across conditional and unconditional operations: passing `None` or
/// [`Version::ANY`] performs an unconditional update/delete, whereas passing an explicit `Version`
/// enforces optimistic concurrency verification against the znode's current version on the server.
pub trait VersionArg: sealed::Sealed {}

impl sealed::Sealed for Version {
    fn wire_version(&self) -> i32 {
        self.0
    }
}
impl VersionArg for Version {}

impl sealed::Sealed for Option<Version> {
    fn wire_version(&self) -> i32 {
        self.map_or(Version::ANY.0, |v| v.0)
    }
}
impl VersionArg for Option<Version> {}

/// Extracts the 32-bit wire representation of a version argument for protocol dispatch.
pub(crate) fn wire_version(v: &impl VersionArg) -> i32 {
    sealed::Sealed::wire_version(v)
}

/// Comprehensive metadata and revision attributes associated with a ZooKeeper znode.
///
/// Mirrors the wire `Stat` record defined in the ZooKeeper protocol specification, capturing
/// transaction coordinates, modification timestamps, optimistic locking versions, and lifecycle
/// ownership.
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq, Hash)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct Stat {
    /// Transaction ID of the change that created this znode.
    pub czxid: Zxid,
    /// Transaction ID of the change that last modified this znode's data.
    pub mzxid: Zxid,
    /// Transaction ID of the change that last added or removed children under this znode.
    pub pzxid: Zxid,
    /// Time when this znode was created, measured in milliseconds since the Unix epoch.
    pub ctime: i64,
    /// Time when this znode was last modified, measured in milliseconds since the Unix epoch.
    pub mtime: i64,
    /// Number of data changes made to this znode.
    pub version: Version,
    /// Number of changes made to the children of this znode.
    pub cversion: Version,
    /// Number of modifications applied to this znode's access control list (ACL).
    pub aversion: Version,
    /// Session ID of the ephemeral owner, or extended bitfield designating container or TTL
    /// semantics.
    pub ephemeral_owner: i64,
    /// Length of the data payload stored in this znode, in bytes.
    pub data_length: i32,
    /// Total count of immediate child znodes subordinate to this path.
    pub num_children: i32,
}

/// Marker byte indicating an extended ephemeral owner field (matching Java `EphemeralType`).
const EXTENDED_MARK: u64 = 0xFF;
/// Bitmask isolating the lower 40 bits of an extended owner, representing a millisecond TTL
/// duration.
const TTL_MASK: u64 = 0x00FF_FFFF_FFFF;
/// Bitmask selecting the 16-bit extended type field within an extended ephemeral owner value.
const EXTENDED_TYPE_MASK: u64 = 0xFFFF;

/// Converts a millisecond timestamp relative to Unix epoch into a [`SystemTime`], saturating on
/// overflow.
fn ms_to_time(ms: i64) -> SystemTime {
    let abs = Duration::from_millis(ms.unsigned_abs());
    let t = if ms >= 0 {
        UNIX_EPOCH.checked_add(abs)
    } else {
        UNIX_EPOCH.checked_sub(abs)
    };
    t.unwrap_or(UNIX_EPOCH)
}

impl Stat {
    /// Returns the znode creation timestamp converted to [`SystemTime`].
    #[must_use]
    pub fn ctime(&self) -> SystemTime {
        ms_to_time(self.ctime)
    }

    /// Returns the timestamp of the last data modification converted to [`SystemTime`].
    #[must_use]
    pub fn mtime(&self) -> SystemTime {
        ms_to_time(self.mtime)
    }

    /// Decodes the ephemeral owner identifier into an [`EphemeralKind`] according to ZooKeeper
    /// specification rules.
    ///
    /// Distinguishes persistent znodes (owner 0), container znodes (`i64::MIN`), TTL znodes
    /// (encoded within extended bitfields), and standard ephemeral session ownership. Older
    /// ZooKeeper server releases transmit 0 for container and TTL node responses on the wire.
    #[must_use]
    pub fn ephemeral_kind(&self) -> EphemeralKind {
        let owner = self.ephemeral_owner;
        if owner == 0 {
            return EphemeralKind::None;
        }
        if owner == i64::MIN {
            return EphemeralKind::Container;
        }
        let bits = u64::from_be_bytes(owner.to_be_bytes());
        if bits.wrapping_shr(56) == EXTENDED_MARK && bits.wrapping_shr(40) & EXTENDED_TYPE_MASK == 0
        {
            return EphemeralKind::Ttl(Duration::from_millis(bits & TTL_MASK));
        }
        EphemeralKind::Session(SessionId(owner))
    }

    /// Converts a low level Jute deserialized `Stat` record into the strongly typed [`Stat`]
    /// domain struct.
    pub(crate) fn from_jute(s: &zk_jute::data::Stat) -> Self {
        Self {
            czxid: Zxid(s.czxid),
            mzxid: Zxid(s.mzxid),
            pzxid: Zxid(s.pzxid),
            ctime: s.ctime,
            mtime: s.mtime,
            version: Version(s.version),
            cversion: Version(s.cversion),
            aversion: Version(s.aversion),
            ephemeral_owner: s.ephemeral_owner,
            data_length: s.data_length,
            num_children: s.num_children,
        }
    }
}

/// Categorization of node ownership and lifecycle management policy.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum EphemeralKind {
    /// Persistent znode not bound to any session or automatic expiration rule.
    None,
    /// Ephemeral znode whose lifecycle is tied to the indicated active session.
    Session(SessionId),
    /// Container znode eligible for automatic server side deletion when its children list becomes
    /// empty.
    Container,
    /// Persistent znode scheduled for server side expiration after the configured duration elapses
    /// without child updates.
    Ttl(Duration),
}

/// Validated time-to-live duration for TTL-enabled persistent znodes.
///
/// Invariant: Must fall strictly within the inclusive range `1..=0xFF_FFFF_FFFF` milliseconds
/// (bounded by the 40-bit representation supported by server side `EphemeralType.TTL`,
/// approximately 34.8 years).
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub struct Ttl(u64);

impl Ttl {
    /// Maximum allowable TTL duration supported by the ZooKeeper wire protocol (0xFF_FFFF_FFFF
    /// milliseconds).
    pub const MAX: Ttl = Ttl(0xFF_FFFF_FFFF);

    /// Constructs a validated TTL from a [`Duration`].
    ///
    /// # Errors
    ///
    /// Returns [`Error::InvalidArgument`] if the duration resolves to zero milliseconds or
    /// exceeds [`Ttl::MAX`].
    pub fn new(d: Duration) -> Result<Ttl, Error> {
        let ms = u64::try_from(d.as_millis()).unwrap_or(u64::MAX);
        Self::from_millis(ms)
    }

    /// Constructs a validated TTL from a raw millisecond value.
    ///
    /// # Errors
    ///
    /// Returns [`Error::InvalidArgument`] if `ms` is zero or exceeds [`Ttl::MAX`].
    pub fn from_millis(ms: u64) -> Result<Ttl, Error> {
        if ms == 0 || ms > Self::MAX.0 {
            return Err(Error::InvalidArgument(
                "TTL must be in 1..=0xFF_FFFF_FFFF ms".into(),
            ));
        }
        Ok(Ttl(ms))
    }

    /// Returns the configured duration in integer milliseconds.
    #[must_use]
    pub const fn as_millis(self) -> u64 {
        self.0
    }

    /// Converts the TTL into a [`Duration`].
    #[must_use]
    pub const fn as_duration(self) -> Duration {
        Duration::from_millis(self.0)
    }
}

/// Creation policy governing the persistence, sequencing, and expiration characteristics of a new
/// znode.
///
/// Encapsulates TTL configurations directly inside the respective variants, ensuring type safe
/// construction of TTL nodes without risk of missing duration parameters.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
#[non_exhaustive]
pub enum CreateMode {
    /// Standard persistent znode that remains in the hierarchy until explicitly removed.
    Persistent,
    /// Persistent znode whose name is automatically appended with a monotonically increasing
    /// sequence counter.
    PersistentSequential,
    /// Ephemeral znode that is automatically removed when the creating client session terminates.
    Ephemeral,
    /// Ephemeral znode automatically assigned a monotonically increasing sequential name suffix.
    EphemeralSequential,
    /// Container znode designed for leader election or group membership, automatically collected
    /// when empty (ZooKeeper 3.5.3+).
    Container,
    /// Persistent znode configured with an expiration TTL, eligible for collection after
    /// inactivity (ZooKeeper 3.5.3+).
    PersistentWithTtl(Ttl),
    /// Sequential persistent znode governed by an expiration TTL (ZooKeeper 3.5.3+).
    PersistentSequentialWithTtl(Ttl),
}

impl CreateMode {
    /// Indicates whether this creation mode instructs the ensemble to append a monotonic sequence
    /// suffix.
    #[must_use]
    pub fn is_sequential(&self) -> bool {
        matches!(
            self,
            Self::PersistentSequential
                | Self::EphemeralSequential
                | Self::PersistentSequentialWithTtl(_)
        )
    }

    /// Indicates whether the created node's existence is tied to the lifespan of the client
    /// session.
    #[must_use]
    pub fn is_ephemeral(&self) -> bool {
        matches!(self, Self::Ephemeral | Self::EphemeralSequential)
    }

    /// Extracts the associated [`Ttl`] configuration if this mode defines a time-to-live
    /// policy.
    #[must_use]
    pub fn ttl(&self) -> Option<Ttl> {
        match self {
            Self::PersistentWithTtl(t) | Self::PersistentSequentialWithTtl(t) => Some(*t),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_args() {
        assert_eq!(wire_version(&Version::new(3)), 3);
        assert_eq!(wire_version(&None::<Version>), -1);
        assert_eq!(wire_version(&Some(Version::new(4))), 4);
        assert_eq!(Version::ANY.get(), -1);
    }
}
