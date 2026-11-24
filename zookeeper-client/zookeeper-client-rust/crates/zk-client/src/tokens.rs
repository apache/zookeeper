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

//! Implementation of the `zk_proto::Tokens` associated type bundle for the native client.
//!
//! Manages request completion tracking, unique watcher identity assignment, and authentication
//! handshake routing. Completion tokens retain sole ownership of their corresponding response
//! rendezvous [`Slot`]. Watcher tokens are represented by monotonically incremented [`WatchId`]
//! identifiers, preventing unintended watcher coalescing within the protocol core.
//!
//! Concurrency and failure invariant: Dropping a token prior to explicit completion automatically
//! closes its associated slot. This signals terminal client disconnection or dispatch cancellation
//! to awaiting response futures, preventing indefinite deadlocks when the state machine
//! terminates.

use std::sync::Arc;
use std::time::Instant;

use crate::observe::OpKind;
use crate::slot::Slot;

/// Unprocessed response payload and return code emitted by the protocol core.
pub(crate) use zk_proto::OpResult as RawResult;

/// Type-level marker associating native client token types with the `zk-proto` state machine.
#[derive(Debug)]
pub(crate) struct NativeTokens;

impl zk_proto::Tokens for NativeTokens {
    type Completion = NativeCompletion;
    type Watcher = WatchId;
    type Auth = AuthToken;
}

/// Globally unique opaque identifier assigned to an individual active watcher stream.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub(crate) struct WatchId(pub(crate) u64);

/// Completion dispatch targets for outbound wire requests.
#[derive(Debug)]
pub(crate) enum NativeCompletion {
    /// Standard request completion delivering results to an awaiting caller future.
    Reply(ReplyToken),
    /// Unmonitored fire-and-forget completion, utilized for asynchronous `RemoveWatches` cleanup
    /// frames.
    Discard,
    /// Dynamic ensemble configuration query routed internally to the configuration follower task.
    Follow,
}

/// Request completion token pairing an in-flight operation with its response rendezvous cell.
#[derive(Debug)]
pub(crate) struct ReplyToken {
    /// Response destination rendezvous cell, cleared upon completion handoff.
    slot: Option<Arc<Slot<RawResult>>>,
    /// Optional watcher identifier registered concurrently with this request.
    pub(crate) watch: Option<WatchId>,
    /// High-level operation category tracked for client side latency metrics.
    pub(crate) kind: OpKind,
    /// Monotonic timestamp recorded at request submission time for latency measurement.
    pub(crate) started: Instant,
}

impl ReplyToken {
    /// Constructs a new reply token binding the target result cell with optional watcher
    /// registration.
    pub(crate) fn new(slot: Arc<Slot<RawResult>>, watch: Option<WatchId>, kind: OpKind) -> Self {
        Self {
            slot: Some(slot),
            watch,
            kind,
            started: Instant::now(),
        }
    }

    /// Delivers the raw wire result to the awaiting consumer slot.
    ///
    /// Yields `Ok(counted)` on successful delivery, or returns `Err(r)` with the original result
    /// if the receiving future has already dropped its listener.
    pub(crate) fn complete(mut self, r: RawResult, count: bool) -> Result<bool, RawResult> {
        match self.slot.take() {
            Some(slot) => slot.fill(r, count),
            None => Err(r),
        }
    }
}

impl Drop for ReplyToken {
    fn drop(&mut self) {
        if let Some(slot) = self.slot.take() {
            slot.close();
        }
    }
}

/// Completion token managing asynchronous authentication (`add_auth`) responses.
///
/// Encapsulates auth completion within standard `RawResult` semantics, enabling unified
/// future polling machinery across both standard requests and session authentication calls.
#[derive(Debug)]
pub(crate) struct AuthToken {
    /// Target response rendezvous cell, cleared upon delivery.
    slot: Option<Arc<Slot<RawResult>>>,
    /// Timestamp marking operation initiation for telemetry recording.
    pub(crate) started: Instant,
}

impl AuthToken {
    /// Constructs an authentication completion token referencing the target slot.
    pub(crate) fn new(slot: Arc<Slot<RawResult>>) -> Self {
        Self {
            slot: Some(slot),
            started: Instant::now(),
        }
    }

    /// Completes the authentication request with protocol status code `rc`.
    ///
    /// Translates status code 0 into a void reply payload; non-zero codes reflect wire error
    /// statuses.
    pub(crate) fn complete(mut self, rc: i32, count: bool) -> Result<bool, RawResult> {
        let r = RawResult {
            rc,
            zxid: 0,
            origin: zk_proto::Origin::Reply,
            reply: (rc == 0).then_some(zk_proto::Reply::Void),
        };
        match self.slot.take() {
            Some(slot) => slot.fill(r, count),
            None => Err(r),
        }
    }
}

impl Drop for AuthToken {
    fn drop(&mut self) {
        if let Some(slot) = self.slot.take() {
            slot.close();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::slot::Taken;

    fn void() -> RawResult {
        RawResult {
            rc: 0,
            zxid: 0,
            origin: zk_proto::Origin::Reply,
            reply: None,
        }
    }

    #[test]
    fn a_dropped_token_closes_its_slot() {
        let slot = Slot::new();
        drop(ReplyToken::new(Arc::clone(&slot), None, OpKind::Sync));
        assert!(matches!(slot.try_take(), Some(Taken::Closed)));
        let slot = Slot::new();
        drop(AuthToken::new(Arc::clone(&slot)));
        assert!(matches!(slot.try_take(), Some(Taken::Closed)));
    }

    #[test]
    fn complete_fills_the_slot() {
        let slot = Slot::new();
        let t = ReplyToken::new(Arc::clone(&slot), None, OpKind::GetData);
        assert!(matches!(t.complete(void(), true), Ok(true)));
        assert!(matches!(
            slot.try_take(),
            Some(Taken::Value { counted: true, .. })
        ));
        let slot = Slot::new();
        assert!(matches!(
            AuthToken::new(Arc::clone(&slot)).complete(-115, false),
            Ok(false)
        ));
        assert!(
            matches!(slot.try_take(), Some(Taken::Value { v, .. }) if v.rc == -115 && v.reply.is_none())
        );
    }
}
