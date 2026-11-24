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

//! Standard-library based host adaptation layer for native drivers and testing environments.
//!
//! While production native applications typically configure an asynchronous DNS resolver via
//! `zk-client`, [`StdHostLeaves`] provides a synchronous fallback environment backed by `std`.
//! Host resolution delegates to blocking `std::net::ToSocketAddrs` (`getaddrinfo`), pseudorandom
//! number generation is powered by an internal SplitMix64 generator seeded by system entropy,
//! and token drops cleanly deallocate memory without foreign FFI coordination.

use std::net::{SocketAddr, ToSocketAddrs};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use zk_proto::{ClientId, HostResolution, HostSpec, LogLevel, ResolveError, Tokens};

use crate::host::{Discarded, HostLeaves};
use crate::sync::{Mutex, Unlocked};

/// System `getaddrinfo` error code denoting an unresolvable hostname.
#[cfg(target_os = "linux")]
pub const EAI_NONAME: i32 = -2;

/// System `getaddrinfo` error code denoting an unresolvable hostname.
#[cfg(not(target_os = "linux"))]
pub const EAI_NONAME: i32 = 8;

/// Golden ratio additive constant driving state transitions in the SplitMix64 generator.
const GOLDEN_GAMMA: u64 = 0x9E37_79B9_7F4A_7C15;

/// IEEE 754 double-precision bit pattern for 1.0, used to project random mantissa bits into
/// [0.0, 1.0).
const ONE_BITS: u64 = 0x3FF0_0000_0000_0000;

/// Process-wide monotonic counter guaranteeing distinct seeds even across sub-microsecond
/// instantiations.
static SEED_COUNTER: AtomicU64 = AtomicU64::new(0);

/// Standard library host services implementation for native runtimes and test harnesses.
#[derive(Debug)]
pub struct StdHostLeaves {
    /// When true, preserves the exact server list ordering configured by the user without
    /// shuffling.
    pub permute_disabled: bool,
    /// Maximum log severity admitted by the host, or `None` if log emission is disabled.
    pub log_level: Option<LogLevel>,
    /// Thread safe synchronized PRNG state for server shuffling and reconnection jitter.
    pub(crate) rng: Mutex<u64>,
}

impl StdHostLeaves {
    /// Constructs a standard host leaves instance with server shuffling enabled, logging
    /// disabled, and PRNG state seeded from the clock and process ID.
    #[must_use]
    pub fn new() -> Self {
        Self {
            permute_disabled: false,
            log_level: None,
            rng: Mutex::new(fresh_seed()),
        }
    }

    /// Advances the SplitMix64 state and returns the next pseudorandom 64-bit integer.
    fn next_u64(&self) -> u64 {
        let mut state = self.rng.lock_owner();
        *state = state.wrapping_add(GOLDEN_GAMMA);
        mix(*state)
    }
}

impl Default for StdHostLeaves {
    fn default() -> Self {
        Self::new()
    }
}

/// Computes the SplitMix64 avalanche transformation over the input state.
fn mix(mut z: u64) -> u64 {
    z = (z ^ z.wrapping_shr(30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ z.wrapping_shr(27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^ z.wrapping_shr(31)
}

/// Derives a PRNG seed from the monotonic wall clock, process ID, and process wide atomic sequence
/// counter.
fn fresh_seed() -> u64 {
    let clock = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| {
        d.as_secs()
            .wrapping_mul(1_000_000_000)
            .wrapping_add(u64::from(d.subsec_nanos()))
    });
    let pid = u64::from(std::process::id()).rotate_left(32);
    let count = SEED_COUNTER.fetch_add(1, Ordering::Relaxed);
    mix(clock ^ pid ^ mix(count))
}

/// Extracts the target TCP port from a host specification, returning `None` if invalid.
fn entry_port(spec: &HostSpec) -> Option<u16> {
    match spec {
        HostSpec::Legacy(h) => u16::try_from(h.port).ok(),
        HostSpec::Native(h) => Some(h.port),
    }
}

/// Synchronously resolves host address entries using `std::net::ToSocketAddrs`.
fn lookup(spec: &HostSpec) -> Result<Vec<SocketAddr>, ResolveError> {
    let not_found = |errno: i32| ResolveError {
        gai_rc: EAI_NONAME,
        errno,
    };
    let host = std::str::from_utf8(spec.host_bytes()).map_err(|_| not_found(0))?;
    let port = entry_port(spec).ok_or_else(|| not_found(0))?;
    let addrs: Vec<SocketAddr> = (host, port)
        .to_socket_addrs()
        .map_err(|e| not_found(e.raw_os_error().unwrap_or(0)))?
        .collect();
    if addrs.is_empty() {
        Err(not_found(0))
    } else {
        Ok(addrs)
    }
}

impl<T: Tokens> HostLeaves<T> for StdHostLeaves {
    /// Releases token resources without foreign C cleanup obligations.
    fn discard_token(&self, token: Discarded<T>) {
        drop(token);
    }

    /// Returns `None` since native Rust clients manage watchers through dedicated channels rather
    /// than C tokens.
    fn snapshot_default_watcher(&self) -> Option<T::Watcher> {
        None
    }

    /// Ignores explicit storage notifications as native clients query session identity directly
    /// from the connection.
    fn store_client_id(&self, _id: &ClientId) {}

    fn log_enabled(&self, level: LogLevel) -> bool {
        self.log_level.is_some_and(|max| level.code() <= max.code())
    }

    fn conn_permute_disabled(&self) -> bool {
        self.permute_disabled
    }

    /// Generates a non-negative pseudorandom integer matching the 31-bit range of libc
    /// `random()`.
    fn rng_random(&self) -> i64 {
        i64::try_from(self.next_u64().wrapping_shr(33)).unwrap_or(0)
    }

    /// Generates a pseudorandom floating point number in [0.0, 1.0) matching libc `drand48()`.
    fn rng_drand48(&self) -> f64 {
        f64::from_bits(ONE_BITS | self.next_u64().wrapping_shr(12)) - 1.0
    }

    /// Reseeds the internal PRNG using fresh system entropy, mirroring C `setup_random` semantics.
    fn rng_reseed(&self, _proof: &Unlocked) {
        let seed = fresh_seed();
        let mut state = self.rng.lock_owner();
        *state = mix(*state ^ seed);
    }

    /// Resolves a single server address via synchronous standard library DNS lookups.
    fn resolve(&self, _proof: &Unlocked, host: usize, spec: &HostSpec) -> HostResolution {
        HostResolution {
            host,
            result: lookup(spec),
            skipped_families: Vec::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    #![allow(
        clippy::unwrap_used,
        clippy::indexing_slicing,
        clippy::arithmetic_side_effects
    )]

    use super::*;
    use zk_testkit::tokens::TestTokens;

    #[test]
    fn reseed_changes_the_stream() {
        let a = StdHostLeaves::new();
        *a.rng.lock_owner() = 42;
        let b = StdHostLeaves::new();
        *b.rng.lock_owner() = 42;
        assert_eq!(
            HostLeaves::<TestTokens>::rng_random(&a),
            HostLeaves::<TestTokens>::rng_random(&b)
        );
        HostLeaves::<TestTokens>::rng_reseed(&b, &Unlocked::new_at_entry());
        let xs: Vec<i64> = (0..4)
            .map(|_| HostLeaves::<TestTokens>::rng_random(&a))
            .collect();
        let ys: Vec<i64> = (0..4)
            .map(|_| HostLeaves::<TestTokens>::rng_random(&b))
            .collect();
        assert_ne!(xs, ys);
    }

    #[test]
    fn two_new_leaves_have_different_seeds() {
        let a = StdHostLeaves::new();
        let b = StdHostLeaves::new();
        assert_ne!(*a.rng.lock_owner(), *b.rng.lock_owner());
    }
}
