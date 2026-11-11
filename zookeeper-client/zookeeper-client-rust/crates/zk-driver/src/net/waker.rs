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

//! Coalescing self-pipe wakeup mechanism for interrupting driver event polling loops.
//!
//! Inter-thread signaling in [`crate::threaded`] and [`crate::native`] utilizes a nonblocking
//! POSIX pipe pair:
//! - [`Waker`] provides a thread-safe write handle allowing arbitrary submission and control
//!   threads to wake the I/O reactor.
//! - [`WakeRx`] provides the dedicated read handle monitored by the driver's polling loop alongside
//!   the active network socket (and inside `crate::net::probe`).
//!
//! Wakeup requests are coalesced via an [`AtomicBool`]: only a state transition from `false` to
//! `true` writes a single byte into the pipe. This invariant guarantees that at most one byte ever
//! resides in the pipe buffer, eliminating pipe saturation and write blocking.
//!
//! In adherence to POSIX fork safety and compatibility requirements, descriptor handles may be
//! independently closed across child processes ([`Waker::close_in_child`]) without requiring
//! `unsafe` blocks. Close-on-exec (`FD_CLOEXEC`) is configured conditionally based on driver mode.

use std::os::fd::{AsFd, BorrowedFd, OwnedFd};

use rustix::io::{Errno, FdFlags};

use crate::net::sock::set_nonblocking;
use crate::sync::Mutex;
use crate::sync::atomic::{AtomicBool, Ordering};

/// Thread-safe write handle of the self-pipe waker.
#[derive(Debug)]
pub struct Waker {
    pending: AtomicBool,
    tx: Mutex<Option<OwnedFd>>,
}

/// Read handle of the self-pipe waker, retained exclusively by the active I/O thread.
#[derive(Debug)]
pub struct WakeRx {
    rx: OwnedFd,
}

impl Waker {
    /// Creates a new nonblocking self-pipe pair for thread synchronization.
    ///
    /// Both descriptor endpoints are placed in nonblocking mode via `O_NONBLOCK`. If `cloexec` is
    /// true, both descriptors have `FD_CLOEXEC` asserted.
    ///
    /// # Errors
    ///
    /// Returns an [`Errno`] if pipe allocation or descriptor flag manipulation fails.
    pub fn new(cloexec: bool) -> Result<(Self, WakeRx), Errno> {
        let (rx, tx) = rustix::pipe::pipe()?;
        // Using fcntl(F_SETFL, O_NONBLOCK) guarantees consistent behavior across all POSIX
        // platforms.
        set_nonblocking(rx.as_fd())?;
        set_nonblocking(tx.as_fd())?;
        if cloexec {
            rustix::io::fcntl_setfd(&rx, FdFlags::CLOEXEC)?;
            rustix::io::fcntl_setfd(&tx, FdFlags::CLOEXEC)?;
        }
        let waker = Self {
            pending: AtomicBool::new(false),
            tx: Mutex::new(Some(tx)),
        };
        Ok((waker, WakeRx { rx }))
    }

    /// Signals the driver I/O thread, coalescing multiple concurrent wake requests into a single
    /// pipe write.
    ///
    /// If a previous wakeup signal has not yet been drained by [`WakeRx::reset`], this call
    /// completes immediately as a no-op without performing a redundant write syscall.
    pub fn wake(&self) {
        if self.pending.swap(true, Ordering::AcqRel) {
            return;
        }
        let tx = self.tx.lock_owner();
        if let Some(fd) = tx.as_ref() {
            let _ = rustix::io::write(fd, &[1]);
        }
    }

    /// Attempts to close the pipe write descriptor following a `fork`, preventing descriptor
    /// leakage in child processes.
    ///
    /// Uses nonblocking lock acquisition (`try_lock`) to avoid deadlocking if a parent thread held
    /// the mutex at the time of `fork`.
    pub fn close_in_child(&self) -> bool {
        match self.tx.try_lock() {
            Some(mut tx) => {
                drop(tx.take());
                true
            }
            None => false,
        }
    }
}

impl WakeRx {
    /// Drains all pending wakeup bytes from the pipe and clears the atomic signaling flag.
    ///
    /// Called by the driver I/O loop upon observing read readiness on the pipe descriptor.
    /// Draining all accumulated bytes prior to clearing `pending` guarantees that subsequent
    /// wakeups trigger fresh descriptor readiness without race conditions.
    pub fn reset(&self, waker: &Waker) {
        let mut buf = [0u8; 16];
        while matches!(rustix::io::read(&self.rx, &mut buf), Ok(n) if n == buf.len()) {}
        waker.pending.store(false, Ordering::Release);
    }
}

impl AsFd for WakeRx {
    fn as_fd(&self) -> BorrowedFd<'_> {
        self.rx.as_fd()
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic)]
    #![allow(clippy::disallowed_macros)]

    use rustix::fs::OFlags;
    use rustix::io::FdFlags;

    use super::*;

    /// Verifies that both pipe ends are nonblocking and correctly propagate CLOEXEC settings.
    #[test]
    fn both_ends_are_non_blocking() {
        // Under Miri emulation, fcntl(F_SETFD) is unsupported; test only non-CLOEXEC mode.
        let modes: &[bool] = if cfg!(miri) { &[false] } else { &[false, true] };
        for &cloexec in modes {
            let (waker, rx) = Waker::new(cloexec).unwrap();
            let tx = waker.tx.try_lock().unwrap();
            let tx = tx.as_ref().unwrap();
            for fd in [rx.as_fd(), tx.as_fd()] {
                assert!(
                    rustix::fs::fcntl_getfl(fd)
                        .unwrap()
                        .contains(OFlags::NONBLOCK)
                );
                let flags = rustix::io::fcntl_getfd(fd).unwrap();
                if !cfg!(miri) {
                    assert_eq!(flags.contains(FdFlags::CLOEXEC), cloexec);
                }
            }
            // An empty nonblocking pipe must return EAGAIN immediately without blocking.
            let mut buf = [0u8; 1];
            assert_eq!(rustix::io::read(&rx, &mut buf), Err(Errno::AGAIN));
        }
    }
}
