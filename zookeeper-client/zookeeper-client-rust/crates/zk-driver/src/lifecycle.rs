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

//! Reference counting and shutdown lifecycle coordination for drop in client handles.
//!
//! # Architectural Role in `zk-driver`
//!
//! The internal reference count (`refs`) mirrors the `api_prolog` / `api_epilog` tracking of the
//! legacy C client. Rather than managing memory deallocation (which is handled safely via Rust
//! [`std::sync::Arc`] references), this count coordinates shutdown sequencing to determine which
//! execution thread or reentrant call frame assumes responsibility for executing the final session
//! closure (`CLOSE_OP` wire flush, `conn.destroy`, and `Step::Release`) and when.
//!
//! Reference ownership rules:
//! - **Active Reference Holders**: Background I/O and completion dispatch threads (between
//!   `thread_enter` and `thread_exit` in [`crate::threaded`]), the initiating thread during
//!   bootstrap, an active `close` caller, and each active driving frame in single threaded mode
//!   (`zookeeper_interest` / `zookeeper_process` in [`crate::polled`]). Normal asynchronous and
//!   synchronous data operations (`submit`, `add_auth`, `set_servers`) do not retain lifecycle
//!   references across returns.
//! - **Finalization Trigger**: The invocation of [`Lifecycle::epilog`] that decrements the
//!   reference count to zero following an explicit [`Lifecycle::request_close`] triggers final
//!   session cleanup, claimed exactly once via [`Lifecycle::begin_final`].
//!
//! Role-specific and reentrant teardown state machines built on top of [`Lifecycle`] are
//! implemented in [`crate::threaded`] (`CloseStage` / `FinalStage`) and [`crate::polled`]
//! (`StClose`).

use crate::sync::atomic::{AtomicBool, AtomicU32, Ordering};

/// Concurrency and shutdown tracking state for a drop-in handle.
#[derive(Debug)]
pub struct Lifecycle {
    refs: AtomicU32,
    close_requested: AtomicBool,
    finalizing: AtomicBool,
    creator_pid: u32,
}

impl Default for Lifecycle {
    fn default() -> Self {
        Self::new()
    }
}

impl Lifecycle {
    /// Constructs a fresh lifecycle tracker initialized with zero references, capturing the creator
    /// process ID to detect post-fork parent/child boundaries (`is_forked_child`).
    #[must_use]
    pub fn new() -> Self {
        Self {
            refs: AtomicU32::new(0),
            close_requested: AtomicBool::new(false),
            finalizing: AtomicBool::new(false),
            creator_pid: std::process::id(),
        }
    }

    /// Increments the active reference count (`api_prolog`) and returns the updated total.
    ///
    /// Close operations sample this count prior to advertising shutdown intentions via
    /// [`Lifecycle::request_close`], matching `zookeeper_close` ordering. Sampling `refs` after
    /// publishing `close_requested` could overlook references held by background threads that
    /// observed the close signal and exited immediately.
    pub fn prolog(&self) -> u32 {
        self.refs.fetch_add(1, Ordering::AcqRel).saturating_add(1)
    }

    /// Decrements the active reference count (`api_epilog`).
    ///
    /// Returns `true` if this call released the final reference (`prev == 1`) after a close request
    /// was registered, indicating that the calling thread or frame must execute final session
    /// teardown.
    pub fn epilog(&self) -> bool {
        let mut prev = self.refs.load(Ordering::Acquire);
        // Emulate atomic fetch_update via a compare-and-swap retry loop to maintain MSRV
        // compatibility; zero saturates.
        while let Some(next) = prev.checked_sub(1) {
            match self
                .refs
                .compare_exchange_weak(prev, next, Ordering::AcqRel, Ordering::Acquire)
            {
                Ok(_) => break,
                Err(seen) => prev = seen,
            }
        }
        prev == 1 && self.close_requested.load(Ordering::Acquire)
    }

    /// Returns the current active reference count.
    #[must_use]
    pub fn refs(&self) -> u32 {
        self.refs.load(Ordering::Acquire)
    }

    /// Initiates a close request on the handle.
    ///
    /// Returns `true` if this is the first close request registered. Subsequent or reentrant close
    /// requests return `false`.
    pub fn request_close(&self) -> bool {
        !self.close_requested.swap(true, Ordering::AcqRel)
    }

    /// Checks whether an explicit close request has been initiated.
    #[must_use]
    pub fn close_requested(&self) -> bool {
        self.close_requested.load(Ordering::Acquire)
    }

    /// Returns a direct reference to the atomic close request flag, used by `StepCtx::abort`.
    #[must_use]
    pub fn close_flag(&self) -> &AtomicBool {
        &self.close_requested
    }

    /// Claims ownership of the final teardown execution, returning `true` exactly once across all
    /// competing threads or frames.
    pub fn begin_final(&self) -> bool {
        !self.finalizing.swap(true, Ordering::AcqRel)
    }

    /// Evaluates whether execution is occurring within a child process spawned via `fork()`.
    #[must_use]
    pub fn is_forked_child(&self) -> bool {
        std::process::id() != self.creator_pid
    }
}
