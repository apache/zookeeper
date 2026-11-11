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

//! Platform-specific POSIX and Windows error number constants.
//!
//! Because the protocol core remains entirely decoupled from direct libc bindings,
//! target-specific system error constants are resolved at compile time. External
//! FFI shims and compatibility wrappers query these constants via
//! `Connection::take_errno_hint` to faithfully emulate the native C client's `errno`
//! reporting behaviors across various operating systems.

/// POSIX `EINVAL` (invalid argument) constant, identical across all supported platforms.
pub const EINVAL: i32 = 22;

/// POSIX `ENOMEM` (out of memory) constant, identical across all supported platforms.
pub const ENOMEM: i32 = 12;

/// POSIX `ESTALE` (stale file handle / identifier) constant for Linux and compatible Unix
/// platforms.
#[cfg(not(any(
    target_os = "macos",
    target_os = "ios",
    target_os = "freebsd",
    target_os = "netbsd",
    target_os = "openbsd",
    target_os = "dragonfly",
    windows
)))]
pub const ESTALE: i32 = 116;

/// POSIX `ETIMEDOUT` (connection timed out) constant for Linux and compatible Unix platforms.
#[cfg(not(any(
    target_os = "macos",
    target_os = "ios",
    target_os = "freebsd",
    target_os = "netbsd",
    target_os = "openbsd",
    target_os = "dragonfly",
    windows
)))]
pub const ETIMEDOUT: i32 = 110;

/// BSD/macOS `ESTALE` (stale file handle / identifier) constant.
#[cfg(any(
    target_os = "macos",
    target_os = "ios",
    target_os = "freebsd",
    target_os = "netbsd",
    target_os = "openbsd",
    target_os = "dragonfly"
))]
pub const ESTALE: i32 = 70;

/// BSD/macOS `ETIMEDOUT` (connection timed out) constant.
#[cfg(any(
    target_os = "macos",
    target_os = "ios",
    target_os = "freebsd",
    target_os = "netbsd",
    target_os = "openbsd",
    target_os = "dragonfly"
))]
pub const ETIMEDOUT: i32 = 60;

/// Windows CRT `ESTALE` constant.
#[cfg(windows)]
pub const ESTALE: i32 = 19;

/// Windows Winsock `WSAETIMEDOUT` error code constant.
#[cfg(windows)]
pub const ETIMEDOUT: i32 = 10060;

/// Linux `EHOSTDOWN` (host is down) error constant.
///
/// In the legacy C ZooKeeper client receive loop (`zookeeper.c`), this error
/// code is dispatched when encountering premature stream termination or a zero-length frame.
#[cfg(not(any(
    target_os = "macos",
    target_os = "ios",
    target_os = "freebsd",
    target_os = "netbsd",
    target_os = "openbsd",
    target_os = "dragonfly",
    windows
)))]
pub const EHOSTDOWN: i32 = 112;

/// BSD/macOS `EHOSTDOWN` (host is down) error constant.
#[cfg(any(
    target_os = "macos",
    target_os = "ios",
    target_os = "freebsd",
    target_os = "netbsd",
    target_os = "openbsd",
    target_os = "dragonfly"
))]
pub const EHOSTDOWN: i32 = 64;

/// Windows error mapping for host unreachable conditions, mapped to `EPIPE` by the C client.
#[cfg(windows)]
pub const EHOSTDOWN: i32 = 32;
