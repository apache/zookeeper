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

//! Native client syntax utilities and Java ZooKeeper compatibility helpers.
//!
//! Exposes connection string parsing, chroot manipulation, and path validation utilities
//! conforming strictly to the canonical Java ZooKeeper client specifications. While the
//! underlying implementations reside within [`crate::legacy`] to share parsing logic with
//! legacy C-client compatibility routines, this module surfaces a cohesive, stable interface
//! for modern native drivers without coupling them directly to internal legacy module hierarchies.
//!
//! Key capabilities exposed include:
//! - [`parse_connect_string_native`]: Validates and decomposes connection strings formatted
//!   as comma-separated host-port specifications with an optional trailing chroot path (e.g.,
//!   `"h1:2181,h2/chroot"`).
//! - [`validate_path_strict`]: Implements strict znode path validation conforming to Java's
//!   `PathUtils.validatePath`.
//! - [`join_component`]: Prepends the configured chroot prefix to outgoing znode paths along
//!   component boundaries.
//! - [`strip_str`]: Strips the chroot prefix from server-originated paths and event notifications.

pub use crate::legacy::chroot::{join_component, strip_str};
pub use crate::legacy::connect::{
    NativeConnectString, NativeHost, NativeParseError, parse_connect_string_native,
};
pub use crate::legacy::path::{PathError, PathErrorKind, validate_path_strict};
