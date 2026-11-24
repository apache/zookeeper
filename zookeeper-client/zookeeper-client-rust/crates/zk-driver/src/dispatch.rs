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

//! Bounded flow control and backpressure hand-off mechanisms for native drivers.
//!
//! Following each invocation of the protocol state machine, the I/O driver drains its
//! sequential outbox and dispatches items directly into client side completion slots,
//! watcher queues, and session tracking states via a [`RouteSink`].
//!
//! To prevent unbounded memory growth when client consumption lags behind network throughput,
//! a [`ReadGate`] tracks the number of unconsumed in-flight items. Whenever this count
//! reaches or exceeds `dispatch_queue_capacity`, the I/O driver suspends socket read interest
//! while in the `Established` session phase. Request transmission, heartbeat pings, and
//! session expiration timers continue uninterrupted during read suspension.
//!
//! Receive liveness while the gate is closed: because no frames are read during read suspension,
//! on each interest pass in the connection's data phase the driver queries the socket's unread
//! byte count (`FIONREAD`) and refreshes the protocol receive timer
//! (`Connection::note_recv_alive`) when new bytes have arrived. On the first gated pass, bytes
//! already buffered by the driver (`rx_fresh`, `rx_pending`) or waiting in the socket count once
//! as a receive; on subsequent passes, only growth of the socket's unread count since the previous
//! pass counts. Bytes that merely wait unread do not count again because nothing consumes them
//! while the gate is closed and the driver buffers cannot grow, so counting them repeatedly would
//! keep a dead server's session alive indefinitely. Because heartbeat pings continue to go out, a
//! live server's ping replies keep arriving, keep the socket's unread count growing, and keep the
//! session connected however long the backlog persists, whereas a server that stops sending adds
//! no bytes and still incurs the receive timeout. Checking the unread count reads no frames, so
//! the driver does not buffer more data and the backlog does not grow; however, once the socket
//! receive buffer is full no further growth is visible, and even a live server incurs the receive
//! timeout. Only the native threaded and Tokio drivers pass a read gate; the threaded and polled
//! drivers behind the C drop-in libraries pass no gate and never take this path.
//!
//! Backpressure protocol rules:
//! - The sink indicates whether an item consumes capacity: RPC results, authentication
//!   outcomes with active receivers, and one-shot watch triggers account for capacity,
//!   whereas persistent watcher notifications are unmetered.
//! - When consumer channels receive or drop an item, they invoke [`ReadGate::taken`]. If the
//!   backlog transitions below capacity, the gate signals the caller to awaken the I/O loop.
//! - Because a single socket read burst (up to 64 KiB) is processed atomically before
//!   re-evaluating gate capacity, the backlog may transiently exceed the configured threshold
//!   by at most the contents of one read buffer.

use zk_proto::Tokens;

use crate::outbox::Item;
use crate::sync::atomic::{AtomicUsize, Ordering};

/// Outbox item routing sink transferring protocol completions into client queues.
pub trait RouteSink<T: Tokens>: Send + Sync {
    /// Dispatches a single outbox item in FIFO sequence without blocking.
    ///
    /// Returns `true` if this item contributes toward backpressure queue capacity accounting.
    fn route(&self, item: Item<T>) -> bool;
}

/// Atomic flow-control gate enforcing backpressure bounds on dispatched items.
#[derive(Debug)]
pub struct ReadGate {
    count: AtomicUsize,
    capacity: usize,
}

impl ReadGate {
    /// Initializes a new open read gate with the specified buffer capacity (minimum 1).
    #[must_use]
    pub fn new(capacity: usize) -> Self {
        Self {
            count: AtomicUsize::new(0),
            capacity: capacity.max(1),
        }
    }

    /// Indicates whether the current in-flight backlog is strictly below configured capacity.
    #[must_use]
    pub fn is_open(&self) -> bool {
        self.count.load(Ordering::Acquire) < self.capacity
    }

    /// Returns the current number of in-flight unconsumed items.
    #[must_use]
    pub fn count(&self) -> usize {
        self.count.load(Ordering::Acquire)
    }

    /// Increments the in-flight item count when a capacity-metered item is routed.
    pub fn routed(&self) {
        let _ = update(&self.count, |c| c.checked_add(1));
    }

    /// Decrements the in-flight count upon item consumption or drop.
    ///
    /// Returns `true` if this transition dropped the backlog below the capacity threshold,
    /// indicating that socket read interest should be re-enabled.
    pub fn taken(&self) -> bool {
        update(&self.count, |c| c.checked_sub(1)).is_some_and(|prev| prev == self.capacity)
    }
}

/// Atomically mutates an atomic counter using an optimistic compare-and-swap retry loop.
fn update(counter: &AtomicUsize, f: impl Fn(usize) -> Option<usize>) -> Option<usize> {
    let mut cur = counter.load(Ordering::Acquire);
    loop {
        let next = f(cur)?;
        match counter.compare_exchange_weak(cur, next, Ordering::AcqRel, Ordering::Acquire) {
            Ok(prev) => return Some(prev),
            Err(seen) => cur = seen,
        }
    }
}
