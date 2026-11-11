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

//! Dynamic ensemble reconfiguration and probabilistic server selection algorithms.
//!
//! Implements a faithful algorithmic port of the ZOOKEEPER-1355 dynamic reconfiguration
//! rebalancing protocol (`update_addrs`, `get_next_server_in_reconfig`, and
//! `zoo_cycle_next_server` in `zookeeper.c`). When ensemble membership changes, connected clients
//! compute transition probabilities based on old and new cluster sizes to probabilistically
//! migrate to newly added servers, thereby distributing client traffic evenly without thundering
//! herd stampedes. This implementation preserves verbatim 32-bit unsigned wrapping arithmetic,
//! IEEE-754 double-precision float calculations, and deterministic pseudo-random draw sequences
//! (`drand48`).

use std::net::SocketAddr;

use super::addrvec::{AddrEq, AddrVec, EndpointAddr, ListEq, contains, lists_equal};

/// Outcome yielded by [`ServerSet::update`] upon evaluating a freshly resolved ensemble server
/// list.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum UpdateOutcome {
    /// Resolved server list is semantically identical to the active configuration; no rebalancing
    /// needed.
    Unchanged,
    /// Resolved server list differs from the active configuration and has replaced it.
    Changed {
        /// Indicates whether probabilistic rebalancing requires the client to disconnect and
        /// migrate to a new server.
        reconfig: bool,
    },
}

/// Ensemble server list manager maintaining active, deprecated, and newly introduced endpoints.
///
/// Encapsulates the C client handle fields `addrs`, `addrs_old`, `addrs_new`,
/// `addr_cur`, `reconfig`, `pNew`, and `pOld`.
#[derive(Clone, Debug, PartialEq)]
pub struct ServerSet<T> {
    /// Active committed sequence of ensemble server endpoints (`addrs`).
    pub addrs: AddrVec<T>,
    /// Endpoints retained across reconfiguration that existed in the prior server set
    /// (`addrs_old`).
    pub old: AddrVec<T>,
    /// Novel endpoints introduced by reconfiguration that were absent from the prior set
    /// (`addrs_new`).
    pub new: AddrVec<T>,
    /// Currently selected server endpoint actively being connected or probed (`addr_cur`).
    pub current: Option<T>,
    /// Flag indicating that dynamic reconfiguration migration is active and selecting via
    /// probabilities.
    pub reconfig: bool,
    /// Probability threshold for selecting a candidate server from the newly added pool `new`
    /// (`pNew`).
    pub p_new: f64,
    /// Probability threshold for selecting a candidate server from the retained pool `old`
    /// (`pOld`).
    pub p_old: f64,
}

impl<T> Default for ServerSet<T> {
    fn default() -> Self {
        Self {
            addrs: AddrVec::new(),
            old: AddrVec::new(),
            new: AddrVec::new(),
            current: None,
            reconfig: false,
            p_new: 0.0,
            p_old: 0.0,
        }
    }
}

/// Clamps collection length to a 32-bit unsigned integer to replicate C `unsigned int` overflow
/// boundaries.
fn count_u32(n: usize) -> u32 {
    u32::try_from(n).unwrap_or(u32::MAX)
}

impl<T: EndpointAddr + Clone> ServerSet<T> {
    /// Initializes an empty server set structure matching a newly created legacy client handle.
    pub fn new() -> Self {
        Self::default()
    }

    /// Evaluates a newly resolved ensemble server list and computes migration probabilities.
    ///
    /// Replicates `update_addrs` execution upon successful DNS resolution. List equivalence
    /// and address identity are evaluated according to the provided `list_eq` and `addr_eq`
    /// strategies (legacy C defaults to [`ListEq::LegacyOneWay`] and [`AddrEq::IpOnly`]).
    ///
    /// When cluster expansion occurs and the client is currently connected to an existing server,
    /// a single pseudo-random draw (`drand48`) determines whether the client must disconnect and
    /// migrate to a newly added server to balance cluster load.
    pub fn update<F: FnMut() -> f64>(
        &mut self,
        resolved: Vec<T>,
        list_eq: ListEq,
        addr_eq: AddrEq,
        mut drand48: F,
    ) -> UpdateOutcome {
        if lists_equal(self.addrs.as_slice(), &resolved, list_eq, addr_eq) {
            return UpdateOutcome::Unchanged;
        }
        let found_current = self
            .current
            .as_ref()
            .is_some_and(|cur| contains(&resolved, &cur.socket_addr(), addr_eq));

        self.reconfig = true;
        self.old.clear();
        self.new.clear();
        for item in &resolved {
            if self.addrs.contains(&item.socket_addr(), addr_eq) {
                self.old.push(item.clone());
            } else {
                self.new.push(item.clone());
            }
        }

        // Perform arithmetic using wrapping 32-bit unsigned integers to emulate legacy C integer
        // semantics.
        let num_old = count_u32(self.old.len());
        let num_new = count_u32(self.new.len());
        let count = count_u32(self.addrs.len());
        let total = num_old.wrapping_add(num_new);

        if total > count {
            // Ensemble capacity expanded: probabilistically migrate connected clients to new
            // servers.
            if found_current {
                let threshold = 1.0 - f64::from(count) / f64::from(total);
                if drand48() <= threshold {
                    self.p_new = 1.0;
                    self.p_old = 0.0;
                } else {
                    self.reconfig = false;
                }
            } else {
                self.p_new = 1.0;
                self.p_old = 0.0;
            }
        } else if found_current {
            // Ensemble contracted or remained stable while currently connected server remains
            // valid.
            self.reconfig = false;
        } else {
            let numerator = num_old.wrapping_mul(count.wrapping_sub(total));
            let denominator = total.wrapping_mul(count.wrapping_sub(num_old));
            // Preserve IEEE-754 division by zero yielding NaN as observed in C.
            self.p_old = f64::from(numerator) / f64::from(denominator);
            self.p_new = 1.0 - self.p_old;
        }

        self.addrs = AddrVec::from(resolved);
        UpdateOutcome::Changed {
            reconfig: self.reconfig,
        }
    }

    /// In-place variant of [`Self::update`] that reuses storage when the candidate list is
    /// unchanged.
    ///
    /// If the list is unchanged, `resolved` remains populated for reuse by the caller.
    /// If the list differs, `resolved` is consumed and moved into internal storage.
    pub(crate) fn update_from<F: FnMut() -> f64>(
        &mut self,
        resolved: &mut Vec<T>,
        list_eq: ListEq,
        addr_eq: AddrEq,
        drand48: F,
    ) -> UpdateOutcome {
        if lists_equal(self.addrs.as_slice(), resolved, list_eq, addr_eq) {
            return UpdateOutcome::Unchanged;
        }
        self.update(core::mem::take(resolved), list_eq, addr_eq, drand48)
    }

    /// Selects the next server candidate during active reconfiguration
    /// (`get_next_server_in_reconfig`).
    ///
    /// Evaluates migration probability against a random draw. Returns `false` when both candidate
    /// pools are exhausted, clearing `current`.
    fn next_in_reconfig<F: FnMut() -> f64>(&mut self, drand48: &mut F) -> bool {
        let take_new = drand48() <= self.p_new;
        if self.new.has_next() && (take_new || !self.old.has_next()) {
            self.current = self.new.next_item().cloned();
            return true;
        }
        if self.old.has_next() {
            self.current = self.old.next_item().cloned();
            return true;
        }
        self.current = None;
        false
    }

    /// Advances to and returns the next target server endpoint according to
    /// `zoo_cycle_next_server`.
    ///
    /// While reconfiguration mode is active, candidates are selected probabilistically using
    /// `drand48`. Once reconfiguration pools are exhausted, reconfiguration mode terminates and
    /// selection reverts to standard round-robin rotation across `addrs`.
    pub fn cycle_next<F: FnMut() -> f64>(&mut self, mut drand48: F) -> Option<&T> {
        self.current = None;
        if self.reconfig {
            if self.next_in_reconfig(&mut drand48) {
                return self.current.as_ref();
            }
            self.reconfig = false;
        }
        self.current = self.addrs.next_item().cloned();
        self.current.as_ref()
    }

    /// Returns the resolved socket address of the active connection target, if selected.
    pub fn current_addr(&self) -> Option<SocketAddr> {
        self.current.as_ref().map(EndpointAddr::socket_addr)
    }
}
