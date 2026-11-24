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

//! Descriptor polling interfaces, timeout translations, and readiness event mappings for network
//! drivers.
//!
//! Driver I/O loops ([`crate::iocore`], [`crate::threaded`], [`crate::native`], [`crate::polled`],
//! and `crate::net::probe`) perform OS socket polling exclusively through the [`Poll`] trait.
//! The production implementation is [`RustixPoll`], backed by `rustix::event::poll`. Decoupling
//! polling through this trait allows injecting deterministic poll harnesses in unit tests and
//! Loom concurrency models without opening real kernel sockets.

use rustix::event::{PollFd, PollFlags, Timespec};

/// Abstraction for polling readiness across a collection of file descriptors.
///
/// Conforms to POSIX `poll` semantics. `timeout_ms` specifies the maximum wait duration in
/// milliseconds. Negative values indicate an indefinite wait.
pub trait Poll: Send + Sync {
    /// Blocks until at least one descriptor in `fds` reports readiness, an interrupt occurs, or
    /// the timeout expires.
    ///
    /// Returns the count of ready descriptors. An interrupted system call error (`EINTR`) is
    /// surfaced as `Err(Errno::INTR)` so the calling loop can re-evaluate timeouts or abort flags.
    ///
    /// # Errors
    ///
    /// Returns an error if the underlying OS poll system call fails.
    fn poll(&self, fds: &mut [PollFd<'_>], timeout_ms: i32) -> rustix::io::Result<usize>;
}

/// Standard polling implementation backed by `rustix::event::poll`.
#[derive(Clone, Copy, Debug, Default)]
pub struct RustixPoll;

impl Poll for RustixPoll {
    /// Invokes `rustix::event::poll` with timeout converted to high-resolution [`Timespec`].
    ///
    /// Does not automatically retry upon `EINTR`, leaving retry decisions to the calling loop.
    fn poll(&self, fds: &mut [PollFd<'_>], timeout_ms: i32) -> rustix::io::Result<usize> {
        let ts = timespec(timeout_ms);
        rustix::event::poll(fds, ts.as_ref())
    }
}

/// Converts a millisecond duration into a POSIX [`Timespec`]. Negative durations return `None`
/// (indefinite blocking).
fn timespec(timeout_ms: i32) -> Option<Timespec> {
    if timeout_ms < 0 {
        return None;
    }
    let secs = timeout_ms.checked_div(1000).unwrap_or(0);
    // Fractional millisecond component is bounded below 1000 ms, fitting safely in nanosecond
    // representation.
    let nanos = timeout_ms
        .checked_rem(1000)
        .unwrap_or(0)
        .saturating_mul(1_000_000);
    Some(Timespec {
        tv_sec: i64::from(secs),
        tv_nsec: nanos.into(),
    })
}

/// Readiness flags for a descriptor in terms of legacy C ZooKeeper interest masks.
///
/// Mirrors the bit definitions from `zookeeper.h`: `ZOOKEEPER_WRITE = 1 << 0`,
/// `ZOOKEEPER_READ = 1 << 1`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct PollEvents {
    /// Indicates whether the descriptor is readable or has reached EOF.
    pub read: bool,
    /// Indicates whether the descriptor is writable.
    pub write: bool,
}

/// Legacy ZooKeeper write interest bit mask (`ZOOKEEPER_WRITE`).
pub const ZOOKEEPER_WRITE: u32 = 1;

/// Legacy ZooKeeper read interest bit mask (`ZOOKEEPER_READ`).
pub const ZOOKEEPER_READ: u32 = 2;

impl PollEvents {
    /// Empty event set indicating neither read nor write interest.
    pub const NONE: Self = Self {
        read: false,
        write: false,
    };

    /// Decodes legacy ZooKeeper interest bit flags into a structured [`PollEvents`] set.
    #[must_use]
    pub const fn from_bits(bits: u32) -> Self {
        Self {
            read: bits & ZOOKEEPER_READ != 0,
            write: bits & ZOOKEEPER_WRITE != 0,
        }
    }

    /// Encodes this event set into legacy ZooKeeper interest bit flags.
    #[must_use]
    pub const fn bits(self) -> u32 {
        let r = if self.read { ZOOKEEPER_READ } else { 0 };
        let w = if self.write { ZOOKEEPER_WRITE } else { 0 };
        r | w
    }

    /// Returns `true` if neither read nor write readiness is asserted.
    #[must_use]
    pub const fn is_empty(self) -> bool {
        !self.read && !self.write
    }
}

/// Translates operating system `poll` return events (`revents`) into protocol readiness events.
///
/// - `POLLIN` translates to read readiness.
/// - `POLLOUT` and `POLLHUP` translate to write readiness, preserving legacy C driver semantics.
/// - `POLLERR` and `POLLNVAL` assert both read and write readiness to force immediate error
///   detection during subsequent I/O calls.
#[must_use]
pub fn map_revents(revents: PollFlags) -> PollEvents {
    let error = revents.intersects(PollFlags::ERR | PollFlags::NVAL);
    PollEvents {
        read: error || revents.contains(PollFlags::IN),
        write: error || revents.intersects(PollFlags::OUT | PollFlags::HUP),
    }
}

/// Clamps an arbitrary millisecond duration to a valid non-negative 32-bit poll timeout.
#[must_use]
pub fn clamp_timeout_ms(timeout_ms: i64) -> i32 {
    let clamped = timeout_ms.clamp(0, i64::from(i32::MAX));
    i32::try_from(clamped).unwrap_or(i32::MAX)
}

/// Constructs the operating system `poll` request flags corresponding to the given protocol
/// interest.
#[must_use]
pub fn request_flags(events: PollEvents) -> PollFlags {
    let mut flags = PollFlags::empty();
    if events.read {
        flags |= PollFlags::IN;
    }
    if events.write {
        flags |= PollFlags::OUT;
    }
    flags
}

#[cfg(test)]
mod tests {
    // Platform `tv_nsec` width varies between 64-bit (`i64`) and 32-bit (`i32`) targets.
    #![allow(clippy::useless_conversion)]

    use super::*;

    #[test]
    fn timespec_splits_the_milliseconds() {
        assert!(timespec(-1).is_none());
        let ts = timespec(1500).unwrap_or(Timespec {
            tv_sec: -1,
            tv_nsec: 0,
        });
        assert_eq!(ts.tv_sec, 1);
        assert_eq!(i64::from(ts.tv_nsec), 500_000_000);
        let max = timespec(i32::MAX).unwrap_or(Timespec {
            tv_sec: -1,
            tv_nsec: 0,
        });
        assert_eq!(max.tv_sec, i64::from(i32::MAX / 1000));
        assert_eq!(i64::from(max.tv_nsec), 647_000_000);
    }
}
