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

//! Synchronization utilities, poison-resilient mutex helpers, and I/O context markers.
//!
//! Internal synchronization primitives adhere strictly to leaf-lock discipline and never execute
//! arbitrary user closures while holding mutexes. Consequently, any mutex poisoned by a thread
//! unwind retains internally consistent data. The helpers provided here extract guards from
//! [`PoisonError`] to ensure transient thread panics do not cause permanent lock failures.
//!
//! In addition, thread-local I/O markers enforce execution runtime invariants by detecting and
//! preventing illegal synchronous blocking calls within event loop contexts.

use std::cell::Cell;
use std::panic::{AssertUnwindSafe, catch_unwind};
#[cfg(any(test, feature = "blocking"))]
use std::sync::Condvar;
use std::sync::{Mutex, MutexGuard, PoisonError};
use std::task::Waker;
#[cfg(any(test, feature = "blocking"))]
use std::time::Instant;

/// Extension trait providing poison-resilient acquisition for standard library [`Mutex`]
/// instances.
pub(crate) trait LockExt<T> {
    /// Acquires the lock, recovering the underlying guard even if a previous holder panicked.
    fn lock_unpoisoned(&self) -> MutexGuard<'_, T>;
}

impl<T> LockExt<T> for Mutex<T> {
    fn lock_unpoisoned(&self) -> MutexGuard<'_, T> {
        self.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

/// Dispatches a task waker notification within an unwinding boundary without holding locks.
///
/// Because wakers invoke arbitrary task scheduling logic, this ensures that task panics do not
/// compromise the background I/O driver thread.
pub(crate) fn wake_quietly(w: Waker) {
    let _ = catch_unwind(AssertUnwindSafe(|| w.wake()));
}

/// Suspends the calling thread on a condition variable, recovering the guard upon poison.
#[cfg(any(test, feature = "blocking"))]
pub(crate) fn wait<'a, T>(cv: &Condvar, guard: MutexGuard<'a, T>) -> MutexGuard<'a, T> {
    cv.wait(guard).unwrap_or_else(PoisonError::into_inner)
}

/// Blocks on `cv` until notified or `deadline` expires, returning the guard and a timeout flag.
#[cfg(any(test, feature = "blocking"))]
pub(crate) fn wait_until<'a, T>(
    cv: &Condvar,
    guard: MutexGuard<'a, T>,
    deadline: Instant,
) -> (MutexGuard<'a, T>, bool) {
    let left = deadline.saturating_duration_since(Instant::now());
    if left.is_zero() {
        return (guard, true);
    }
    let (guard, result) = cv
        .wait_timeout(guard, left)
        .unwrap_or_else(PoisonError::into_inner);
    (guard, result.timed_out())
}

thread_local! {
    /// Client ID identifier active on the current I/O thread, or 0 if outside I/O context.
    ///
    /// Plain `Cell<u64>` avoids registering thread-exit destructors that could inhibit dynamic
    /// unloading.
    static IO_SIDE: Cell<u64> = const { Cell::new(0) };
}

/// RAII scope guard restoring the prior thread-local I/O marker on drop.
#[derive(Debug)]
pub(crate) struct IoSideGuard {
    prev: u64,
}

impl Drop for IoSideGuard {
    fn drop(&mut self) {
        let prev = self.prev;
        IO_SIDE.with(|c| c.set(prev));
    }
}

/// Enters an I/O thread execution scope for client `client`, returning an RAII restoration guard.
///
/// Wraps driver event processing, DNS resolution worker routines, and observer callbacks to
/// enforce execution runtime boundaries.
pub(crate) fn enter_io_side(client: u64) -> IoSideGuard {
    let prev = IO_SIDE.with(|c| c.replace(client));
    IoSideGuard { prev }
}

/// Checks whether the calling thread is currently executing inside an active client I/O context.
#[cfg(any(test, feature = "blocking"))]
pub(crate) fn on_io_side() -> bool {
    IO_SIDE.with(|c| c.get() != 0)
}

/// Determines whether synchronous blocking operations must be rejected with `Error::InvalidState`.
///
/// Blocking on a client I/O thread risks deadlocking the event loop and is unconditionally
/// rejected. Clients running on the Tokio driver likewise reject synchronous blocking inside any
/// active Tokio runtime context (including `spawn_blocking` threads, which Tokio does not publicly
/// distinguish from reactor workers) to prevent stalling the driver task.
#[cfg(any(test, feature = "blocking"))]
pub(crate) fn blocking_refused(tokio_driver: bool) -> bool {
    on_io_side() || (tokio_driver && in_tokio_runtime())
}

#[cfg(all(any(test, feature = "blocking"), unix, feature = "tokio"))]
fn in_tokio_runtime() -> bool {
    tokio::runtime::Handle::try_current().is_ok()
}

#[cfg(all(any(test, feature = "blocking"), not(all(unix, feature = "tokio"))))]
fn in_tokio_runtime() -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_marker_nests_and_restores() {
        assert!(!on_io_side());
        {
            let _outer = enter_io_side(7);
            assert!(on_io_side());
            {
                let _inner = enter_io_side(9);
                assert!(on_io_side());
            }
            assert!(on_io_side());
        }
        assert!(!on_io_side());
    }

    #[test]
    fn a_poisoned_lock_gives_its_guard() {
        let m = std::sync::Arc::new(Mutex::new(5));
        let m2 = std::sync::Arc::clone(&m);
        let _ = std::panic::catch_unwind(move || {
            let _g = m2.lock_unpoisoned();
            std::panic::panic_any("poison");
        });
        assert_eq!(*m.lock_unpoisoned(), 5);
    }
}
