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

//! Synchronization primitives, compile-time lock exclusion proofs, and Loom permutation facades.
//!
//! # Architectural Role in `zk-driver`
//!
//! All driver synchronization primitives, including mutual exclusion locks, condition variables,
//! and atomic primitives, are routed through this module. In standard production builds, this
//! facade aliases types directly from [`std::sync`]. In model checking configurations,
//! it redirects to `loom::sync` equivalents, enabling exhaustive concurrency and interleaving
//! verification of production driver code (`threaded`, `native`, `dispatch`, `lifecycle`) without
//! source modifications.
//!
//! Global static primitives and test clocks retain standard library atomics because standard
//! constructors are `const`-compatible whereas Loom constructors are dynamic. Consequently,
//! [`Mutex::new`] and [`Condvar::new`] are `const fn` only in non-Loom environments.
//!
//! # Lock Hierarchy and Compile-Time Exclusion (`Unlocked`)
//!
//! - **Hierarchy Order (`io -> core -> fifo`)**: The `io` lock ([`Mutex::lock_owner`]) represents
//!   exclusive I/O runner ownership (`IoCore`, `StIo`, `IoSide`, `NativeIo`, `TaskIo`); under
//!   normal execution paths, only the designated I/O runner acquires it, preventing contention
//!   with request submission threads. The `core` lock protects `Core<T>` (and thus
//!   `zk_proto::Connection<T>`). The `fifo` lock (`DispatchQueue::fifo` or `SaslHosts`) is a pure
//!   leaf lock under which no other lock is ever acquired. The outer `io` lock may remain held
//!   across blocking operations (mirroring the legacy C client's `reconfig_lock` during DNS
//!   resolution).
//! - **User Hooks Under `io`**: The native driver retains the `io` lock while routing outbox items
//!   and executing SASL steps, safely invoking user hooks and wakers without deadlock risk because
//!   client handle methods never acquire `io`.
//! - **Borrow-Checked Exclusion for `core` ([`Unlocked`])**: Production paths acquire the `core`
//!   mutex via [`Mutex::lock`], which mutably borrows an [`Unlocked`] witness token for the
//!   lifetime of the returned [`Guard`]. Because blocking host leaves and outbox routers also
//!   require a reference to [`Unlocked`], the borrow checker statically rejects blocking calls
//!   while a `core` guard is live within the same call chain. External API entry points and worker
//!   thread roots instantiate [`Unlocked`] tokens by convention.
//! - **Poison Resilience**: The synchronization facade recovers inner state across poisoned locks.
//!   User code never executes under `core` or `fifo`. Within the native Rust client, user hooks
//!   invoked under `io` (wakers, `Metrics`, log backends, `Resolve`, and SASL steps) are wrapped in
//!   `catch_unwind` so panics cannot poison `io`. If an unguarded panic occurs on the I/O runner
//!   (such as inside a custom `rustls` callback or user destructor), the runner aborts the session
//!   and acquires any poisoned locks solely to release the transport and reclaim tokens.

use std::marker::PhantomData;
use std::ops::{Deref, DerefMut};
use std::sync::{PoisonError, TryLockError};
use std::time::Duration;

#[cfg(loom)]
use loom::sync as imp;
#[cfg(not(loom))]
use std::sync as imp;

/// Re-exported atomic primitives conforming to standard or Loom interfaces depending on
/// configuration.
pub mod atomic {
    #[cfg(loom)]
    pub use loom::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};
    #[cfg(not(loom))]
    pub use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};
}

/// Zero-sized witness token certifying that the calling thread holds neither the `core` mutex nor
/// any leaf lock.
///
/// External API entry points and worker thread roots instantiate this token via
/// [`Unlocked::new_at_entry`]. [`Mutex::lock`] mutably borrows the token (`&'a mut Unlocked`) for
/// the lifetime of the returned [`Guard`], preventing blocking host operations or outbox routing
/// (which also require the token) from executing while `core` is locked. The token imposes no
/// restriction on the outer `io` runner lock.
///
/// The token is neither [`Send`] nor [`Sync`], ensuring it remains confined to the stack of its
/// originating thread and cannot be held across `.await` points in `Send` futures.
#[derive(Debug)]
pub struct Unlocked {
    _not_send: PhantomData<*const ()>,
}

impl Unlocked {
    /// Constructs a witness token at an external API boundary or worker thread entry point.
    ///
    /// Callers must ensure neither `core` nor any leaf lock (`fifo`, `SaslHosts`) is held on the
    /// current thread; the outer `io` lock may be held because it precedes `core` in the lock
    /// hierarchy.
    #[must_use]
    pub const fn new_at_entry() -> Self {
        Self {
            _not_send: PhantomData,
        }
    }
}

/// Poison-resilient mutual exclusion primitive.
#[derive(Debug, Default)]
pub struct Mutex<T> {
    inner: imp::Mutex<T>,
}

/// RAII lock guard for [`Mutex`], tying guard lifetime to the borrowed [`Unlocked`] witness token.
#[derive(Debug)]
pub struct Guard<'a, T> {
    inner: imp::MutexGuard<'a, T>,
}

impl<T> Deref for Guard<'_, T> {
    type Target = T;

    fn deref(&self) -> &T {
        &self.inner
    }
}

impl<T> DerefMut for Guard<'_, T> {
    fn deref_mut(&mut self) -> &mut T {
        &mut self.inner
    }
}

impl<T> Mutex<T> {
    /// Constructs a new mutex wrapping the given value.
    #[cfg(not(loom))]
    pub const fn new(value: T) -> Self {
        Self {
            inner: imp::Mutex::new(value),
        }
    }

    /// Constructs a new mutex wrapping the given value under Loom simulation.
    #[cfg(loom)]
    pub fn new(value: T) -> Self {
        Self {
            inner: imp::Mutex::new(value),
        }
    }

    /// Acquires the lock by mutably borrowing the [`Unlocked`] witness token for the lifetime of
    /// the returned guard.
    ///
    /// Primary entry point for acquiring the `core` state mutex while enforcing compile-time
    /// exclusion of blocking calls.
    pub fn lock<'a>(&'a self, proof: &'a mut Unlocked) -> Guard<'a, T> {
        let _ = proof;
        self.lock_owner()
    }

    /// Acquires the mutex directly without requiring an [`Unlocked`] proof token.
    ///
    /// Used for acquiring the outer `io` owner lock (which sits above `core` in the lock hierarchy)
    /// or terminal leaf locks (`DispatchQueue::fifo`, `SaslHosts`). Production code never invokes
    /// this method on the `core` mutex, reserving direct unguarded acquisition of `core` for unit
    /// test assertions.
    pub fn lock_owner(&self) -> Guard<'_, T> {
        Guard {
            inner: self.inner.lock().unwrap_or_else(PoisonError::into_inner),
        }
    }

    /// Attempts to acquire the mutex without blocking the calling thread.
    ///
    /// Utilized during forked child process cleanup (`forked_close`) where parent threads that do
    /// not exist in the child process may have left locks held at the moment of `fork()`.
    /// Because this nonblocking attempt never waits on contended locks, it is safe to invoke on
    /// `core` without an [`Unlocked`] witness token.
    pub fn try_lock(&self) -> Option<Guard<'_, T>> {
        match self.inner.try_lock() {
            Ok(g) => Some(Guard { inner: g }),
            Err(TryLockError::Poisoned(p)) => Some(Guard {
                inner: p.into_inner(),
            }),
            Err(TryLockError::WouldBlock) => None,
        }
    }

    /// Returns a mutable reference to the underlying data without locking.
    pub fn get_mut(&mut self) -> &mut T {
        self.inner.get_mut().unwrap_or_else(PoisonError::into_inner)
    }

    /// Consumes the mutex, returning the protected underlying value.
    pub fn into_inner(self) -> T {
        self.inner
            .into_inner()
            .unwrap_or_else(PoisonError::into_inner)
    }
}

/// Condition variable coordinating thread signaling with [`Guard`].
#[derive(Debug, Default)]
pub struct Condvar {
    inner: imp::Condvar,
}

impl Condvar {
    /// Constructs a new condition variable.
    #[cfg(not(loom))]
    pub const fn new() -> Self {
        Self {
            inner: imp::Condvar::new(),
        }
    }

    /// Constructs a new condition variable under Loom simulation.
    #[cfg(loom)]
    pub fn new() -> Self {
        Self {
            inner: imp::Condvar::new(),
        }
    }

    /// Atomically releases the lock and suspends the calling thread until notified.
    pub fn wait<'a, T>(&self, guard: Guard<'a, T>) -> Guard<'a, T> {
        Guard {
            inner: self
                .inner
                .wait(guard.inner)
                .unwrap_or_else(PoisonError::into_inner),
        }
    }

    /// Atomically releases the lock and suspends the calling thread until notified or until
    /// `timeout` expires.
    ///
    /// Returns the recovered lock guard along with a boolean indicating whether the timeout
    /// expired.
    pub fn wait_timeout<'a, T>(
        &self,
        guard: Guard<'a, T>,
        timeout: Duration,
    ) -> (Guard<'a, T>, bool) {
        let (g, r) = self
            .inner
            .wait_timeout(guard.inner, timeout)
            .unwrap_or_else(PoisonError::into_inner);
        (Guard { inner: g }, r.timed_out())
    }

    /// Unparks a single waiting thread blocked on this condition variable.
    pub fn notify_one(&self) {
        self.inner.notify_one();
    }

    /// Unparks all waiting threads blocked on this condition variable.
    pub fn notify_all(&self) {
        self.inner.notify_all();
    }
}
