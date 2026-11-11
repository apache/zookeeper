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

//! Access Control List (ACL) types, authorization identities, and canonical permission sets.
//!
//! Provides abstractions for fine grained node authorization: bitflag permission sets ([`Perms`]),
//! authorization scheme identities ([`Id`]), and individual rules ([`Acl`]). Convenience constants
//! ([`OPEN_ACL_UNSAFE`], [`READ_ACL_UNSAFE`], [`CREATOR_ALL_ACL`]) match ZooKeeper defaults. Wire-
//! level Jute conversions are handled across `ops.rs` and `decode.rs`.

use std::borrow::Cow;

bitflags::bitflags! {
    /// Bitflags representing ZooKeeper operation permission privileges.
    ///
    /// Preserves unrecognized bits received from server responses via `Perms::from_bits_retain`
    /// to support protocol forward compatibility.
    #[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
    pub struct Perms: i32 {
        /// Permission to inspect node data contents and enumerate child znode names.
        const READ = 1;
        /// Permission to overwrite node data contents.
        const WRITE = 2;
        /// Permission to create immediate child znodes subordinate to this path.
        const CREATE = 4;
        /// Permission to delete immediate child znodes subordinate to this path.
        const DELETE = 8;
        /// Permission to modify this znode's Access Control List (ACL).
        const ADMIN = 16;
        /// Aggregate mask granting all standard ZooKeeper permissions.
        const ALL = 0x1f;
    }
}

/// Authorization principal composed of an authentication scheme and a scheme-specific identifier.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct Id {
    /// The authentication scheme name (such as `"world"`, `"auth"`, `"digest"`, or `"ip"`).
    pub scheme: Cow<'static, str>,
    /// Scheme-specific identity string identifying the authenticated entity or network constraint.
    pub id: Cow<'static, str>,
}

impl Id {
    /// Universal identifier matching all users unconditionally (`"world:anyone"`).
    pub const ANYONE: Id = Id {
        scheme: Cow::Borrowed("world"),
        id: Cow::Borrowed("anyone"),
    };

    /// Special pseudo-scheme identifier resolving to the authenticated credentials of the invoking
    /// session.
    pub const AUTH_IDS: Id = Id {
        scheme: Cow::Borrowed("auth"),
        id: Cow::Borrowed(""),
    };

    /// Constructs an ACL identity from scheme and identity strings.
    #[must_use]
    pub fn new(scheme: impl Into<Cow<'static, str>>, id: impl Into<Cow<'static, str>>) -> Id {
        Id {
            scheme: scheme.into(),
            id: id.into(),
        }
    }

    /// Constructs an IP-based authorization identity for a host address or subnet CIDR block.
    #[must_use]
    pub fn ip(cidr: &str) -> Id {
        Id::new("ip", cidr.to_owned())
    }

    /// Constructs a SHA-1 digest identity formatted as `user:base64(sha1(user:password))`,
    /// conforming to Java `DigestAuthenticationProvider.generateDigest` conventions.
    #[must_use]
    pub fn digest(user: &str, password: &str) -> Id {
        Id::new("digest", crate::auth::digest_id(user, password))
    }
}

/// Individual ACL rule pairing granted privileges with an authorization principal.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct Acl {
    /// Bitmask of permitted operation actions granted by this rule.
    pub perms: Perms,
    /// Authorization principal to which the permissions apply.
    pub id: Id,
}

/// Canonical unauthenticated ACL granting full permissions to all callers.
pub const OPEN_ACL_UNSAFE: &[Acl] = &[Acl {
    perms: Perms::ALL,
    id: Id::ANYONE,
}];

/// Canonical read-only ACL permitting arbitrary callers to inspect data and child listings.
pub const READ_ACL_UNSAFE: &[Acl] = &[Acl {
    perms: Perms::READ,
    id: Id::ANYONE,
}];

/// Canonical creator-restricted ACL granting full permissions to the authenticated identities of
/// the creating session.
pub const CREATOR_ALL_ACL: &[Acl] = &[Acl {
    perms: Perms::ALL,
    id: Id::AUTH_IDS,
}];
