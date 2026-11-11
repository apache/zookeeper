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

//! Deterministic in-memory network topology simulator for name resolution, connection planning,
//! and transport probing.
//!
//! Provides a simulated networking environment that avoids physical socket creation and system DNS
//! lookups. Test scenarios can script name resolution mapping tables, simulate network faults
//! (`ECONNREFUSED`, `ETIMEDOUT`, resolution failures), and dictate connection establishment timing.

use std::collections::BTreeMap;
use std::net::{IpAddr, SocketAddr};

use zk_proto::{HostResolution, HostSpec, ProbeOutcome, ResolveError};

/// POSIX connection reset by peer error code (`ECONNRESET`, Linux value 104).
pub const ECONNRESET: i32 = 104;
/// POSIX connection refused error code (`ECONNREFUSED`, Linux value 111).
pub const ECONNREFUSED: i32 = 111;
/// POSIX connection timed out error code (`ETIMEDOUT`, Linux value 110).
pub const ETIMEDOUT: i32 = 110;
/// POSIX too many open files error code (`EMFILE`, Linux value 24).
pub const EMFILE: i32 = 24;
/// POSIX name resolution error indicating host name not found (`EAI_NONAME`, glibc value -2).
pub const EAI_NONAME: i32 = -2;
/// POSIX name resolution error indicating temporary resolution failure (`EAI_AGAIN`, glibc -3).
pub const EAI_AGAIN: i32 = -3;

/// Simulated connection disposition strategy for target socket endpoints.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ConnectPlan {
    /// Connection succeeds and is accepted by the server listener bound to the address.
    Accept,
    /// Connection attempt initiates and fails with a TCP-level refusal (`TcpRefused`).
    Refuse {
        /// System error number reported for the connection refusal.
        errno: i32,
    },
    /// Connection attempt fails immediately during local socket allocation (`SocketCreate`).
    SocketFail {
        /// System error number reported for the socket allocation failure.
        errno: i32,
    },
    /// Connection attempt initiates and stalls indefinitely without completion or explicit error.
    Hang,
}

/// Execution timing profile governing successful connection completion.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ConnectStyle {
    /// Connection completes synchronously during the connect call (`ConnectStart::Immediate`).
    Immediate,
    /// Connection initiates asynchronously (`ConnectStart::InProgress`) and completes after a
    /// simulated delay.
    InProgress {
        /// Simulated latency duration in milliseconds before connection establishment completes.
        delay_ms: u64,
    },
}

/// Scripted DNS resolution failure descriptor tracking error codes and remaining trigger count.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Failure {
    err: ResolveError,
    remaining: Option<u32>,
}

/// In-memory network model managing DNS mappings, fault injection rules, and socket connection
/// plans.
#[derive(Clone, Debug)]
pub struct Network {
    names: BTreeMap<Vec<u8>, Vec<IpAddr>>,
    failures: BTreeMap<Vec<u8>, Failure>,
    skipped: BTreeMap<Vec<u8>, Vec<i32>>,
    plans: BTreeMap<SocketAddr, ConnectPlan>,
    probes: BTreeMap<SocketAddr, ProbeOutcome>,
    style: ConnectStyle,
    resolve_calls: usize,
    connects: Vec<SocketAddr>,
}

impl Default for Network {
    fn default() -> Self {
        Self::new()
    }
}

impl Network {
    /// Initializes a simulated network topology populated with default localhost mappings.
    #[must_use]
    pub fn new() -> Self {
        let mut names = BTreeMap::new();
        names.insert(b"localhost".to_vec(), vec![IpAddr::from([127_u8, 0, 0, 1])]);
        Self {
            names,
            failures: BTreeMap::new(),
            skipped: BTreeMap::new(),
            plans: BTreeMap::new(),
            probes: BTreeMap::new(),
            style: ConnectStyle::Immediate,
            resolve_calls: 0,
            connects: Vec::new(),
        }
    }

    /// Registers or overwrites a DNS host name mapping pointing to a sequence of IP addresses.
    pub fn add_name(&mut self, name: &str, addrs: Vec<IpAddr>) {
        self.names.insert(name.as_bytes().to_vec(), addrs);
    }

    /// Deregisters an existing host name mapping from the simulated DNS table.
    pub fn remove_name(&mut self, name: &str) {
        self.names.remove(name.as_bytes());
    }

    /// Injects a scripted DNS resolution failure for `name`, optionally bounded by an invocation
    /// count limit.
    pub fn fail_name(&mut self, name: &str, err: ResolveError, times: Option<u32>) {
        self.failures.insert(
            name.as_bytes().to_vec(),
            Failure {
                err,
                remaining: times,
            },
        );
    }

    /// Clears any active scripted DNS resolution fault rule for the designated host name.
    pub fn clear_failure(&mut self, name: &str) {
        self.failures.remove(name.as_bytes());
    }

    /// Configures address family filters that the simulated resolver omits for `name`.
    pub fn set_skipped_families(&mut self, name: &str, families: Vec<i32>) {
        self.skipped.insert(name.as_bytes().to_vec(), families);
    }

    /// Overrides the connection establishment disposition plan for a target socket address.
    pub fn set_plan(&mut self, addr: SocketAddr, plan: ConnectPlan) {
        self.plans.insert(addr, plan);
    }

    /// Removes any explicit connection disposition override for the target socket address.
    pub fn clear_plan(&mut self, addr: SocketAddr) {
        self.plans.remove(&addr);
    }

    /// Retrieves the active connection disposition override configured for a target socket address.
    #[must_use]
    pub fn plan(&self, addr: SocketAddr) -> Option<ConnectPlan> {
        self.plans.get(&addr).copied()
    }

    /// Configures a predetermined read-write mode probe outcome for an endpoint socket address.
    pub fn set_probe(&mut self, addr: SocketAddr, outcome: ProbeOutcome) {
        self.probes.insert(addr, outcome);
    }

    /// Clears any predetermined read-write probe outcome override for the target socket address.
    pub fn clear_probe(&mut self, addr: SocketAddr) {
        self.probes.remove(&addr);
    }

    /// Retrieves the predetermined read-write probe outcome configured for the specified address.
    #[must_use]
    pub fn probe(&self, addr: SocketAddr) -> Option<ProbeOutcome> {
        self.probes.get(&addr).cloned()
    }

    /// Configures the connection establishment timing mode across the simulated network.
    pub fn set_connect_style(&mut self, style: ConnectStyle) {
        self.style = style;
    }

    /// Returns the currently active connection establishment timing profile.
    #[must_use]
    pub const fn connect_style(&self) -> ConnectStyle {
        self.style
    }

    /// Returns the cumulative number of name resolution queries processed by this instance.
    #[must_use]
    pub const fn resolve_calls(&self) -> usize {
        self.resolve_calls
    }

    /// Returns a slice of all target endpoint addresses attempted across connect invocations, in
    /// chronological order.
    #[must_use]
    pub fn connects(&self) -> &[SocketAddr] {
        &self.connects
    }

    pub(crate) fn note_connect(&mut self, addr: SocketAddr) {
        self.connects.push(addr);
    }

    /// Resolves a host specification into concrete socket addresses following POSIX `getaddrinfo`
    /// semantics.
    ///
    /// Evaluates active fault injection rules before checking IP address literals and the
    /// simulated DNS table. Unmapped host names fail with `EAI_NONAME`.
    pub fn resolve(&mut self, index: usize, host: &HostSpec) -> HostResolution {
        self.resolve_calls = self.resolve_calls.saturating_add(1);
        let name = host.host_bytes().to_vec();
        let port = match host {
            HostSpec::Legacy(h) => u16::try_from(h.port).unwrap_or(0),
            HostSpec::Native(h) => h.port,
        };
        let skipped_families = self.skipped.get(&name).cloned().unwrap_or_default();
        if let Some(f) = self.failures.get_mut(&name) {
            let active = f.remaining.is_none_or(|n| n > 0);
            if active {
                f.remaining = f.remaining.map(|n| n.saturating_sub(1));
                return HostResolution {
                    host: index,
                    result: Err(f.err),
                    skipped_families,
                };
            }
        }
        let literal = core::str::from_utf8(&name)
            .ok()
            .and_then(|s| s.parse::<IpAddr>().ok());
        let result = match literal {
            Some(ip) => Ok(vec![SocketAddr::new(ip, port)]),
            None => match self.names.get(&name) {
                Some(ips) => Ok(ips.iter().map(|ip| SocketAddr::new(*ip, port)).collect()),
                None => Err(ResolveError {
                    gai_rc: EAI_NONAME,
                    errno: 0,
                }),
            },
        };
        HostResolution {
            host: index,
            result,
            skipped_families,
        }
    }
}
