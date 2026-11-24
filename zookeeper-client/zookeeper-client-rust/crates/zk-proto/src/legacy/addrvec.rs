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

//! Cyclic ensemble address vector and permutation shuffle algorithms.
//!
//! Provides an algorithmic port of the historical C client address collection (`addrvec.c`).
//! An address vector maintains a sequence of resolved ensemble server endpoints alongside an
//! iteration cursor. Upon exhausting available candidates, iteration cycles back to the origin.
//! The in-place Fisher-Yates shuffle replicates the exact permutation sequence of the C
//! implementation when supplied with identical PRNG outputs (`random()`).
//!
//! ### Address Equivalence Semantics
//!
//! In historical C implementations, address comparison inspected only the address family and IP
//! bytes, inadvertently ignoring TCP port numbers. This crate supports both the historical
//! port-agnostic comparison policy ([`AddrEq::IpOnly`]) for strict C compatibility and the
//! corrected full-socket comparison policy ([`AddrEq::IpAndPort`]) used by modern native runtimes.

use std::net::SocketAddr;

/// Trait abstracting elements that expose an underlying IP socket address.
///
/// Enables endpoint representations to bundle supplemental metadata (such as original
/// hostname tokens or TLS SNI configurations) while participating uniformly in address-based
/// matching and filtering routines.
pub trait EndpointAddr {
    /// Extracts the resolved network socket address associated with this endpoint.
    fn socket_addr(&self) -> SocketAddr;
}

impl EndpointAddr for SocketAddr {
    fn socket_addr(&self) -> SocketAddr {
        *self
    }
}

/// Strategy for evaluating whether two socket addresses represent the same ensemble server.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum AddrEq {
    /// Legacy C client matching rule (`addrvec_contains`): matches address family and IP address,
    /// ignoring port numbers and IPv6 scope IDs.
    IpOnly,
    /// Corrected strict matching rule: matches both IP address and TCP port number, ignoring IPv6
    /// scope and flow information.
    IpAndPort,
}

impl AddrEq {
    /// Determines whether two socket addresses are equivalent under the selected comparison
    /// strategy.
    pub fn same(self, a: &SocketAddr, b: &SocketAddr) -> bool {
        match self {
            AddrEq::IpOnly => a.ip() == b.ip(),
            AddrEq::IpAndPort => a.ip() == b.ip() && a.port() == b.port(),
        }
    }
}

/// Policy for evaluating structural equivalence between two ensemble address vectors.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ListEq {
    /// Asymmetric legacy C rule (`addrvec_eq`): verifies equal element count and asserts every
    /// item in the old list exists in the new list (permitting duplicates).
    LegacyOneWay,
    /// Bidirectional set-equivalence rule: verifies identical element count and mutual two-way
    /// containment across both lists.
    Bidirectional,
}

/// Determines whether an address vector contains an entry matching `addr` under the specified
/// comparison policy (`addrvec_contains`).
pub fn contains<T: EndpointAddr>(list: &[T], addr: &SocketAddr, eq: AddrEq) -> bool {
    list.iter().any(|x| eq.same(&x.socket_addr(), addr))
}

/// Legacy asymmetric address list comparison (`addrvec_eq`).
///
/// Verifies that both lists share equal length and that every element in `old` has a corresponding
/// match in `new`.
pub fn legacy_eq<A: EndpointAddr, B: EndpointAddr>(old: &[A], new: &[B], eq: AddrEq) -> bool {
    old.len() == new.len() && old.iter().all(|a| contains(new, &a.socket_addr(), eq))
}

/// Symmetric address list comparison asserting equal length and mutual bidirectional containment.
pub fn bidirectional_eq<A: EndpointAddr, B: EndpointAddr>(a: &[A], b: &[B], eq: AddrEq) -> bool {
    legacy_eq(a, b, eq) && legacy_eq(b, a, eq)
}

/// Evaluates equivalence between two endpoint sequences using the specified list comparison and
/// address equality rules.
pub fn lists_equal<A: EndpointAddr, B: EndpointAddr>(
    old: &[A],
    new: &[B],
    list_eq: ListEq,
    addr_eq: AddrEq,
) -> bool {
    match list_eq {
        ListEq::LegacyOneWay => legacy_eq(old, new, addr_eq),
        ListEq::Bidirectional => bidirectional_eq(old, new, addr_eq),
    }
}

/// Shuffles an endpoint slice in place matching the exact algorithmic steps of `addrvec_shuffle`.
///
/// Traverses indices from `len - 1` down to 1, sampling a pseudo-random integer via `random`,
/// computing the swap target modulo `i + 1`, and exchanging elements when indices diverge.
/// Euclidean modulo reduction ensures negative random values never produce out-of-bounds indices.
pub fn shuffle<T, F: FnMut() -> i64>(list: &mut [T], mut random: F) {
    let mut i = list.len().saturating_sub(1);
    while i > 0 {
        let modulus = i64::try_from(i).map_or(i64::MAX, |x| x.saturating_add(1));
        let j = usize::try_from(random().rem_euclid(modulus)).unwrap_or(0);
        if i != j && j < list.len() {
            list.swap(i, j);
        }
        i = i.saturating_sub(1);
    }
}

/// Cyclic address list container with stateful iteration cursor (`addrvec_t`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AddrVec<T> {
    data: Vec<T>,
    next: usize,
}

impl<T> Default for AddrVec<T> {
    fn default() -> Self {
        Self {
            data: Vec::new(),
            next: 0,
        }
    }
}

impl<T> From<Vec<T>> for AddrVec<T> {
    fn from(data: Vec<T>) -> Self {
        Self { data, next: 0 }
    }
}

impl<T> AddrVec<T> {
    /// Constructs an empty address vector with cursor reset to zero.
    pub fn new() -> Self {
        Self::default()
    }

    /// Provides slice access to the underlying collection of endpoints.
    pub fn as_slice(&self) -> &[T] {
        &self.data
    }

    /// Returns the total count of endpoints currently held in the vector.
    pub fn len(&self) -> usize {
        self.data.len()
    }

    /// Indicates whether the vector contains zero endpoints.
    pub fn is_empty(&self) -> bool {
        self.data.is_empty()
    }

    /// Current zero-based index of the cursor indicating the next endpoint to be yielded.
    pub fn cursor(&self) -> usize {
        self.next
    }

    /// Appends a new endpoint to the end of the collection without perturbing the cursor position
    /// (`addrvec_append`).
    pub fn push(&mut self, item: T) {
        self.data.push(item);
    }

    /// Clears all elements from the vector and resets the cursor to zero (`addrvec_free`).
    pub fn clear(&mut self) {
        self.data.clear();
        self.next = 0;
    }

    /// Resets the iteration cursor to the beginning of the collection without modifying elements.
    pub fn reset(&mut self) {
        self.next = 0;
    }

    /// Indicates whether unvisited endpoints remain before reaching the end of the list
    /// (`addrvec_hasnext`).
    pub fn has_next(&self) -> bool {
        !self.data.is_empty() && self.next < self.data.len()
    }

    /// Indicates whether the cursor has advanced past the final element of a non-empty list
    /// (`addrvec_atend`).
    pub fn at_end(&self) -> bool {
        !self.data.is_empty() && self.next >= self.data.len()
    }

    /// Returns the endpoint at the current cursor and advances iteration forward (`addrvec_next`).
    ///
    /// Upon reaching the end of the collection, the cursor wraps cyclically back to the first
    /// element.
    pub fn next_item(&mut self) -> Option<&T> {
        if self.at_end() {
            self.next = 0;
        }
        if !self.has_next() {
            return None;
        }
        let index = self.next;
        self.next = self.next.saturating_add(1);
        self.data.get(index)
    }

    /// Peeks at the next endpoint to be returned by [`AddrVec::next_item`] without advancing the
    /// cursor (`addrvec_peek`).
    pub fn peek(&self) -> Option<&T> {
        if self.data.is_empty() {
            return None;
        }
        let index = if self.at_end() { 0 } else { self.next };
        self.data.get(index)
    }

    /// Permutes elements in place using the Fisher-Yates shuffle algorithm while preserving cursor
    /// index (`addrvec_shuffle`).
    pub fn shuffle<F: FnMut() -> i64>(&mut self, random: F) {
        shuffle(&mut self.data, random);
    }

    /// Consumes the address vector and unpacks the underlying contiguous storage vector.
    pub fn into_vec(self) -> Vec<T> {
        self.data
    }
}

impl<T: EndpointAddr> AddrVec<T> {
    /// Determines whether the vector contains an endpoint matching the given socket address.
    pub fn contains(&self, addr: &SocketAddr, eq: AddrEq) -> bool {
        contains(&self.data, addr, eq)
    }
}
