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

//! Standard library thread spawning adapter (`StdSpawner`).
//!
//! This module encapsulates direct invocations of `std::thread::Builder::spawn`
//! for the native Rust multithreaded driver runtime. Unlike the C compatibility
//! drop-in driver (where the C host harness manages dedicated OS threads via FFI),
//! the native Rust driver delegates background worker thread creation to this
//! component under the `std-thread` feature flag.

use crate::native::{Spawn, ThreadBody};

/// Standard library thread spawner configuring named background threads with optional stack
/// allocation limits.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct StdSpawner {
    /// Configured thread stack allocation in bytes, or `None` to adopt the operating system
    /// default.
    pub stack_size: Option<usize>,
}

impl StdSpawner {
    /// Constructs a thread spawner using default platform stack limits.
    #[must_use]
    pub const fn new() -> Self {
        Self { stack_size: None }
    }

    /// Spawns a detached background OS thread using a dynamically allocated thread name.
    ///
    /// Invoked during client construction when user configuration provides customized,
    /// non-static thread identifiers.
    ///
    /// # Errors
    ///
    /// Returns an `std::io::Error` if operating system thread creation fails.
    pub fn spawn_owned(&self, name: String, body: ThreadBody) -> std::io::Result<()> {
        let builder = std::thread::Builder::new().name(name);
        let builder = match self.stack_size {
            Some(size) => builder.stack_size(size),
            None => builder,
        };
        builder.spawn(body).map(drop)
    }
}

impl Spawn for StdSpawner {
    /// Spawns a detached background thread via `std::thread::Builder`, dropping the join handle.
    fn spawn(&self, name: &'static str, body: ThreadBody) -> std::io::Result<()> {
        self.spawn_owned(name.to_owned(), body)
    }
}
