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

//! Dynamic logging filters and shared default watcher hooks for test double environments.
//!
//! Provides thread safe, mutable hook primitives allowing test scenarios to inspect,
//! reconfigure, or mute diagnostic logging thresholds and mutate session level default
//! watcher registrations concurrently while the client state machine is actively executing.

use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::{Arc, Mutex, PoisonError};

use zk_proto::{DefaultWatcher, LogFilter, LogLevel};

use crate::tokens::WatcherId;

/// Immutable threshold based diagnostic log filter enabling messages up to a configured severity.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LevelFilter {
    max: Option<LogLevel>,
}

impl LevelFilter {
    /// Constructs a static log filter that admits log events up to and including `max`.
    #[must_use]
    pub const fn up_to(max: LogLevel) -> Self {
        Self { max: Some(max) }
    }

    /// Constructs a static log filter that suppresses all diagnostic log emission.
    #[must_use]
    pub const fn off() -> Self {
        Self { max: None }
    }
}

impl LogFilter for LevelFilter {
    fn enabled(&self, level: LogLevel) -> bool {
        self.max.is_some_and(|m| level.code() <= m.code())
    }
}

/// Thread safe atomically mutable diagnostic log filter permitting dynamic threshold adjustments.
///
/// Internal filtering state maps to ZooKeeper C binding severity levels (`ZOO_LOG_LEVEL_*`).
/// Setting the threshold to zero completely silences logging outputs.
#[derive(Clone, Debug, Default)]
pub struct SharedLogFilter {
    level: Arc<AtomicI32>,
}

impl SharedLogFilter {
    /// Constructs a shared log filter initialized to admit events up to `max`.
    #[must_use]
    pub fn new(max: Option<LogLevel>) -> Self {
        let f = Self::default();
        f.set(max);
        f
    }

    /// Dynamically updates the maximum enabled log severity threshold across all handles.
    pub fn set(&self, max: Option<LogLevel>) {
        self.level
            .store(max.map_or(0, LogLevel::code), Ordering::SeqCst);
    }

    /// Retrieves the current atomic integer representation of the active log severity threshold.
    #[must_use]
    pub fn get(&self) -> i32 {
        self.level.load(Ordering::SeqCst)
    }
}

impl LogFilter for SharedLogFilter {
    fn enabled(&self, level: LogLevel) -> bool {
        level.code() <= self.get()
    }
}

/// Thread safe interior mutable registration handle for the session level default watcher.
///
/// Enables test harnesses to dynamically update or clear the root default watcher callback
/// token concurrently without tearing down the underlying client driver instance.
#[derive(Clone, Debug, Default)]
pub struct SharedDefaultWatcher {
    slot: Arc<Mutex<Option<WatcherId>>>,
}

impl SharedDefaultWatcher {
    /// Constructs a shared default watcher container initialized with `w`.
    #[must_use]
    pub fn new(w: Option<WatcherId>) -> Self {
        let s = Self::default();
        s.set(w);
        s
    }

    /// Atomically replaces the registered session level default watcher identifier.
    pub fn set(&self, w: Option<WatcherId>) {
        *self.slot.lock().unwrap_or_else(PoisonError::into_inner) = w;
    }

    /// Retrieves a snapshot of the currently registered session level default watcher identifier.
    #[must_use]
    pub fn get(&self) -> Option<WatcherId> {
        *self.slot.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

impl DefaultWatcher<WatcherId> for SharedDefaultWatcher {
    fn snapshot(&self) -> Option<WatcherId> {
        self.get()
    }
}
