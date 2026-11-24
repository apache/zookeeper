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

//! Znode chroot prefix manipulation and path boundary normalization routines.
//!
//! Historical C ZooKeeper clients manipulated chroot path prefixes via naive byte-level
//! concatenation (`prepend_string`) and substring prefix matching (`sub_string`), which
//! failed to respect component boundaries (for example, erroneously stripping `/app` from
//! `/app2/child` to yield `2/child`). This module provides reference implementations of the
//! historical C behaviors alongside robust, boundary-aware path normalization adopted by modern
//! native drivers and safe runtimes.
//!
//! Under boundary-aware normalization:
//! - Outbound client paths operating under an active chroot must be absolute (starting with `/`).
//!   A root path `/` translates directly to the chroot path itself, whereas non-root paths have
//!   the chroot prefix concatenated along boundary slashes. Sequential node creations directly
//!   targeting `/` under a chroot are rejected as invalid arguments (`ZBADARGUMENTS`) because
//!   resulting sequence nodes would escape the chroot namespace.
//! - Inbound server-originated paths have the chroot stripped strictly along path component
//!   boundaries (requiring an exact trailing `/` boundary or identical string match). Paths
//!   outside the configured chroot remain untruncated and trigger diagnostic warnings.
//! - Special system paths, specifically the ensemble dynamic configuration node
//!   `/zookeeper/config`, bypass chroot prefixing on outbound requests and are emitted directly
//!   to client watchers.

use std::borrow::Cow;

use super::path::{is_sequence, is_valid_path};

/// Reserved ZooKeeper cluster configuration znode path (`ZOO_CONFIG_NODE`).
pub const CONFIG_NODE: &[u8] = b"/zookeeper/config";

/// ZooKeeper error code constant indicating an invalid argument (`ZBADARGUMENTS`).
pub const ZBADARGUMENTS: i32 = -8;

/// Outcome of stripping a chroot prefix from a server-originated znode path.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Stripped<'a> {
    /// Normalized path presented to application callbacks and watchers.
    pub path: Cow<'a, [u8]>,
    /// Indicates whether the server-reported path fell outside the expected chroot namespace,
    /// prompting diagnostic logging matching C "server path %s does not include chroot path %s".
    pub mismatch: bool,
}

/// Reasons why an application path cannot be reconciled with the active chroot prefix.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum JoinError {
    /// Application path lacks a mandatory leading slash (`/`) required under active chroot.
    NotAbsolute,
    /// Sequential node creation targeted directly at `/` under a chroot, which would escape the
    /// chroot hierarchy.
    SequentialOnRoot,
    /// Combined path violates structural znode naming or character constraints.
    InvalidPath,
}

impl JoinError {
    /// Returns the corresponding ZooKeeper error code (`ZBADARGUMENTS`).
    pub fn rc(self) -> i32 {
        ZBADARGUMENTS
    }
}

/// Normalizes an optional chroot byte slice, filtering out empty slices and standalone root (`"/"`)
/// prefixes.
fn effective(chroot: Option<&[u8]>) -> Option<&[u8]> {
    chroot.filter(|c| c.len() > 1)
}

/// Evaluates whether the specified znode path targets the cluster dynamic configuration node
/// (`/zookeeper/config`).
pub fn is_config_node(path: &[u8]) -> bool {
    path == CONFIG_NODE
}

/// Reference implementation of historical C client path concatenation (`prepend_string`).
///
/// In the legacy C implementation, single-byte paths (i.e. `"/"`) are replaced by the chroot,
/// while all other paths are naively appended to the chroot buffer without boundary validation.
pub fn prepend_c<'a>(chroot: Option<&[u8]>, path: &'a [u8]) -> Cow<'a, [u8]> {
    let Some(root) = chroot else {
        return Cow::Borrowed(path);
    };
    if path.len() == 1 {
        return Cow::Owned(root.to_vec());
    }
    let mut out = Vec::with_capacity(root.len().saturating_add(path.len()));
    out.extend_from_slice(root);
    out.extend_from_slice(path);
    Cow::Owned(out)
}

/// Reference implementation of historical C client chroot stripping (`sub_string`).
///
/// Performs naive byte prefix stripping without verifying component delimiters, potentially
/// bisecting znode path segments if a node name shares a common prefix with the chroot.
pub fn strip_c<'a>(chroot: Option<&[u8]>, server: &'a [u8]) -> Stripped<'a> {
    let Some(root) = chroot else {
        return Stripped {
            path: Cow::Borrowed(server),
            mismatch: false,
        };
    };
    match server.strip_prefix(root) {
        None => Stripped {
            path: Cow::Borrowed(server),
            mismatch: true,
        },
        Some([]) => Stripped {
            path: Cow::Borrowed(b"/"),
            mismatch: false,
        },
        Some(rest) => Stripped {
            path: Cow::Borrowed(rest),
            mismatch: false,
        },
    }
}

/// Joins a client-supplied path with the active chroot prefix under legacy compatibility rules.
///
/// Validates the resulting concatenated path against legacy naming rules (`isValidPath`).
/// Returns an error if the path violates absolute formatting or escapes the chroot via sequential
/// root creation.
pub fn join_legacy<'a>(
    chroot: Option<&[u8]>,
    path: &'a [u8],
    mode: i32,
) -> Result<Cow<'a, [u8]>, JoinError> {
    let joined = match effective(chroot) {
        None => Cow::Borrowed(path),
        Some(root) => {
            if path.first() != Some(&b'/') {
                return Err(JoinError::NotAbsolute);
            }
            if path == b"/" {
                if is_sequence(mode) {
                    return Err(JoinError::SequentialOnRoot);
                }
                Cow::Owned(root.to_vec())
            } else {
                prepend_c(Some(root), path)
            }
        }
    };
    if is_valid_path(&joined, mode) {
        Ok(joined)
    } else {
        Err(JoinError::InvalidPath)
    }
}

/// Prepends the chroot prefix to a validated path along strict path component boundaries.
///
/// Designed for modern native drivers. Assumes `path` has already undergone canonical
/// validation and starts with an absolute root slash.
pub fn join_component<'a>(chroot: Option<&str>, path: &'a str) -> Cow<'a, str> {
    match chroot.filter(|c| c.len() > 1) {
        None => Cow::Borrowed(path),
        Some(root) if path == "/" => Cow::Owned(root.to_owned()),
        Some(root) => {
            let mut out = String::with_capacity(root.len().saturating_add(path.len()));
            out.push_str(root);
            out.push_str(path);
            Cow::Owned(out)
        }
    }
}

/// Determines the byte length of the chroot prefix if `server` resides strictly within the chroot
/// boundary.
fn boundary_prefix(root: &[u8], server: &[u8]) -> Option<usize> {
    let rest = server.strip_prefix(root)?;
    if rest.is_empty() || rest.first() == Some(&b'/') {
        Some(root.len())
    } else {
        None
    }
}

/// Strips the chroot prefix from a server path strictly along component boundaries.
///
/// Returns `"/"` if the path matches the chroot exactly. If the path does not reside within
/// the chroot namespace, the raw path is returned with `mismatch` flagged.
pub fn strip<'a>(chroot: Option<&[u8]>, server: &'a [u8]) -> Stripped<'a> {
    let Some(root) = effective(chroot) else {
        return Stripped {
            path: Cow::Borrowed(server),
            mismatch: false,
        };
    };
    match boundary_prefix(root, server) {
        None => Stripped {
            path: Cow::Borrowed(server),
            mismatch: true,
        },
        Some(n) => match server.get(n..) {
            Some([]) | None => Stripped {
                path: Cow::Borrowed(b"/"),
                mismatch: false,
            },
            Some(rest) => Stripped {
                path: Cow::Borrowed(rest),
                mismatch: false,
            },
        },
    }
}

/// Strips the chroot prefix from a watcher event path, honoring dynamic configuration bypass rules.
///
/// When `config_bypass` is asserted, events delivered on `/zookeeper/config` are returned unaltered
/// without triggering namespace mismatch flags.
pub fn strip_event<'a>(
    chroot: Option<&[u8]>,
    server: &'a [u8],
    config_bypass: bool,
) -> Stripped<'a> {
    let s = strip(chroot, server);
    if config_bypass && s.mismatch && is_config_node(server) {
        return Stripped {
            path: Cow::Borrowed(server),
            mismatch: false,
        };
    }
    s
}

/// Strips the chroot prefix from a UTF-8 string path along component boundaries for native client
/// consumption.
pub fn strip_str<'a>(chroot: Option<&str>, server: &'a str) -> (&'a str, bool) {
    let Some(root) = chroot.filter(|c| c.len() > 1) else {
        return (server, false);
    };
    match boundary_prefix(root.as_bytes(), server.as_bytes()) {
        None => (server, true),
        // The prefix terminates at a '/' boundary or string termination, guaranteeing valid UTF-8
        // character boundaries.
        Some(n) => match server.get(n..) {
            Some("") | None => ("/", false),
            Some(rest) => (rest, false),
        },
    }
}
