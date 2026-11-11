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

//! Parity tests for the legacy `TestOperations.cc` (`Zookeeper_operations`) suite.
//!
//! Where the C suite drives `zookeeper_interest` and `zookeeper_process` with a mocked clock,
//! socket, and server, these tests drive the driver core against the harness clock and server
//! model under both legacy profiles. Multithreaded test cases requiring OS threads reside in
//! `zk-client/tests/c_operations.rs`.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

mod common;
mod parity_g2;

use std::cell::Cell;
use std::rc::Rc;

use common::{Profile, cause};
use parity_g2::{
    CHANGED, connect_response, is_completion_of, last_send_at, ping_reply, ping_times,
    watches_since,
};
use zk_jute::{opcode, rc};
use zk_proto::{Cause, Millis, Reply, State};
use zk_testkit::server::{Rule, RuleAction};
use zk_testkit::{Harness, Ticket, WatcherId};

/// Session timeout matching the C ping test cases (`TIMEOUT = 9` seconds).
const T9: i32 = 9_000;

/// Constructs a connected legacy harness configured with a 9-second session timeout.
fn connected_t9(p: Profile, seed: u64) -> Harness {
    let mut h = p.builder_with(seed, |c| c.timeout_ms = T9).build();
    h.connect().unwrap();
    assert_eq!(h.conn().recv_timeout(), T9, "{p:?}");
    h
}

/// Extracts the payload from a `getData` completion.
fn data_of(h: &Harness, t: Ticket) -> Vec<u8> {
    match h.recorder().completion(t).and_then(|r| r.reply.as_ref()) {
        Some(Reply::Data { data, .. }) => data.as_bytes().unwrap_or_default().to_vec(),
        other => panic!("no data reply for {t:?}: {other:?}"),
    }
}

/// `Zookeeper_operations::testPing`: an idle session transmits a ping once `T/3` has elapsed since
/// the last transmission, and the ping reply refreshes the receive timestamp. Because the legacy
/// core suppresses pings while a request is in flight, the receive timeout triggers `2T/3` after
/// the ping reply.
#[test]
fn test_ping() {
    for p in Profile::LEGACY {
        let mut h = connected_t9(p, 201);
        let base = last_send_at(&h);
        h.run_until(|h| !ping_times(h).is_empty(), 5_000).unwrap();
        let ping = ping_times(&h)[0];
        assert_eq!(ping - base, 3_000, "{p:?}");
        // Server responds to the ping immediately.
        h.run_for(10).unwrap();
        assert_eq!(ping_times(&h).len(), 1, "{p:?}");
        assert!(h.state().is_connected(), "{p:?}");

        h.server_mut()
            .add_rule(Rule::on_request().path("/x/y/1").then(RuleAction::Ignore));
        let ops = h.ops();
        let t = h.submit(ops.get_data("/x/y/1", None)).unwrap();
        h.run_for(5_000).unwrap();
        assert_eq!(ping_times(&h).len(), 1, "{p:?}");
        assert!(h.state().is_connected(), "{p:?}");
        h.run_until(|h| h.recorder().completion(t).is_some(), 5_000)
            .unwrap();
        h.expect_completion(t, rc::ZOPERATIONTIMEOUT).unwrap();
        assert_eq!(cause(&h), Some(Cause::RecvTimeout), "{p:?}");
        let gap = h.now().0 - ping;
        assert!((6_000..=6_001).contains(&gap), "{p:?}: {gap}");
    }
}

/// `Zookeeper_operations::testUnsolicitedPing` (ZOOKEEPER-2253): an unsolicited ping reply is
/// silently accepted without emitting outputs and refreshes the receive timestamp, deferring the
/// receive timeout to `2T/3` after its arrival.
#[test]
fn test_unsolicited_ping() {
    for p in Profile::LEGACY {
        let mut h = connected_t9(p, 202);
        // Suppress server ping replies so only the injected frame refreshes the receive timestamp.
        h.server_mut()
            .add_rule(Rule::on_request().op(opcode::PING).then(RuleAction::Ignore));
        let start = h.now().0;
        h.run_for(2_000).unwrap();
        assert!(ping_times(&h).is_empty(), "{p:?}");
        let link = h.link().unwrap();
        let mark = h.recorder().mark();
        h.server_mut().push_raw(link, ping_reply());
        let at = h.now();
        h.step(at);
        assert!(h.state().is_connected(), "{p:?}");
        assert!(watches_since(&h, mark).is_empty(), "{p:?}");
        assert_eq!(cause(&h), None, "{p:?}");
        // Without the timestamp refresh, teardown would occur 6 s after the handshake response.
        h.run_until(|h| h.now().0 >= start + 6_500, 10_000).unwrap();
        assert!(h.state().is_connected(), "{p:?}");
        h.run_until(|h| !h.state().is_connected(), 10_000).unwrap();
        assert_eq!(cause(&h), Some(Cause::RecvTimeout), "{p:?}");
        let gap = h.now().0 - at.0;
        assert!((6_000..=6_001).contains(&gap), "{p:?}: {gap}");
    }
}

/// `Zookeeper_operations::testTimeoutCausedByWatches1`: a watch event arriving 1 ms before a ping
/// deadline refreshes only the receive timestamp; the ping is still transmitted `T/3` after the
/// last send.
#[test]
fn test_timeout_caused_by_watches1() {
    for p in Profile::LEGACY {
        let mut h = connected_t9(p, 203);
        let base = last_send_at(&h);
        let link = h.link().unwrap();
        let mark = h.recorder().mark();
        h.server_mut().push_event(link, CHANGED, "/x/y/z", 1);
        h.step(Millis(base + 2_999));
        assert_eq!(h.now().0, base + 2_999, "{p:?}");
        let ev = watches_since(&h, mark);
        assert_eq!(ev.len(), 1, "{p:?}: {ev:?}");
        assert_eq!((ev[0].0, ev[0].1.as_str()), (CHANGED, "/x/y/z"), "{p:?}");
        assert!(ping_times(&h).is_empty(), "{p:?}");
        h.run_until(|h| !ping_times(h).is_empty(), 5_000).unwrap();
        assert_eq!(ping_times(&h)[0] - base, 3_000, "{p:?}");
        assert!(h.state().is_connected(), "{p:?}");
    }
}

/// `Zookeeper_operations::testTimeoutCausedByWatches2`: a watch event arriving `T/3` after a
/// completed request does not postpone the ping deadline; the ping is transmitted exactly `T/3`
/// after the request.
#[test]
fn test_timeout_caused_by_watches2() {
    for p in Profile::LEGACY {
        let mut h = connected_t9(p, 204);
        h.server_mut().create("/x", b"", 0).unwrap();
        h.server_mut().create("/x/y", b"", 0).unwrap();
        h.server_mut().create("/x/y/1", b"2", 0).unwrap();
        let ops = h.ops();
        let t = h.submit(ops.get_data("/x/y/1", None)).unwrap();
        h.wait_for(t).unwrap();
        h.expect_completion(t, rc::ZOK).unwrap();
        assert_eq!(data_of(&h, t), b"2", "{p:?}");
        let begin = last_send_at(&h);
        let link = h.link().unwrap();
        let mark = h.recorder().mark();
        h.server_mut().push_event(link, CHANGED, "/x/y/z", 1);
        h.step(Millis(begin + 3_000));
        assert_eq!(watches_since(&h, mark).len(), 1, "{p:?}");
        h.run_until(|h| !ping_times(h).is_empty(), 5_000).unwrap();
        assert_eq!(ping_times(&h)[0] - begin, 3_000, "{p:?}");
        assert!(h.state().is_connected(), "{p:?}");
    }
}

/// `Zookeeper_operations::testCloseWhileInProgressFromMain` (ZOOKEEPER-2894): closing immediately
/// after submitting a request without an intervening step fails the queued operation exactly once
/// with `ZCLOSING` and leaves no residual completions.
#[test]
fn test_close_while_in_progress_from_main() {
    for p in Profile::LEGACY {
        let mut h = p.harness(205);
        h.connect().unwrap();
        let ops = h.ops();
        let t = h.submit(ops.get_data("/x/y/1", None)).unwrap();
        h.close().unwrap();
        h.expect_completion(t, rc::ZCLOSING).unwrap();
        h.expect_exactly_once().unwrap();
        let f = h.finish();
        assert!(f.leftovers.completions.is_empty(), "{p:?}");
        assert_eq!(f.server.request_count(opcode::CLOSE), 1, "{p:?}");
    }
}

/// Initiates session close from within the completion callback of an initial request after
/// submitting `second`, returning both request tickets.
fn close_from_completion(h: &mut Harness, second: zk_proto::Op<WatcherId>) -> (Ticket, Ticket) {
    h.server_mut().create("/x", b"", 0).unwrap();
    h.server_mut().create("/x/y", b"", 0).unwrap();
    h.server_mut().create("/x/y/1", b"1", 0).unwrap();
    let ops = h.ops();
    let first = h.submit(ops.get_data("/x/y/1", None)).unwrap();
    let slot: Rc<Cell<Option<Ticket>>> = Rc::new(Cell::new(None));
    let seen = Rc::clone(&slot);
    let mut op = Some(second);
    h.set_hook(Box::new(move |ctx, o| {
        if is_completion_of(o, first) {
            if let Some(op) = op.take() {
                seen.set(Some(ctx.submit(op).unwrap()));
                ctx.conn.begin_close(ctx.now);
            }
        }
    }));
    h.run_until(|h| h.recorder().completion(first).is_some(), 5_000)
        .unwrap();
    h.clear_hook();
    h.close().unwrap();
    (first, slot.get().expect("the completion hook did not run"))
}

/// `Zookeeper_operations::testCloseWhileInProgressFromCompletion` (ZOOKEEPER-2894): submitting a
/// second request and closing the handle from the first request's completion preserves the first
/// reply and fails the second request once with `ZCLOSING`.
#[test]
fn test_close_while_in_progress_from_completion() {
    for p in Profile::LEGACY {
        let mut h = p.harness(206);
        h.connect().unwrap();
        let ops = h.ops();
        let (first, second) = close_from_completion(&mut h, ops.get_data("/x/y/2", None));
        h.expect_completion(first, rc::ZOK).unwrap();
        assert_eq!(data_of(&h, first), b"1", "{p:?}");
        h.expect_completion(second, rc::ZCLOSING).unwrap();
        h.expect_exactly_once().unwrap();
        assert!(h.finish().leftovers.completions.is_empty(), "{p:?}");
    }
}

/// `Zookeeper_operations::testCloseWhileMultiInProgressFromMain` (ZOOKEEPER-2891): closing
/// immediately after submitting a `multi` operation fails it once with `ZCLOSING`.
#[test]
fn test_close_while_multi_in_progress_from_main() {
    for p in Profile::LEGACY {
        let mut h = p.harness(207);
        h.connect().unwrap();
        let ops = h.ops();
        let t = h
            .submit(ops.multi(vec![
                ops.m_create("/a", b"", 0),
                ops.m_create("/a/b", b"", 0),
            ]))
            .unwrap();
        h.close().unwrap();
        h.expect_completion(t, rc::ZCLOSING).unwrap();
        h.expect_exactly_once().unwrap();
        assert!(h.finish().leftovers.completions.is_empty(), "{p:?}");
    }
}

/// `Zookeeper_operations::testCloseWhileMultiInProgressFromCompletion` (ZOOKEEPER-2891):
/// submitting a `multi` operation and closing the handle from a `getData` completion fails the
/// `multi` once with `ZCLOSING`.
#[test]
fn test_close_while_multi_in_progress_from_completion() {
    for p in Profile::LEGACY {
        let mut h = p.harness(208);
        h.connect().unwrap();
        let ops = h.ops();
        let multi = ops.multi(vec![
            ops.m_create("/a", b"", 0),
            ops.m_create("/a/b", b"", 0),
        ]);
        let (first, second) = close_from_completion(&mut h, multi);
        h.expect_completion(first, rc::ZOK).unwrap();
        h.expect_completion(second, rc::ZCLOSING).unwrap();
        h.expect_exactly_once().unwrap();
        assert!(h.finish().leftovers.completions.is_empty(), "{p:?}");
    }
}

/// Suppresses the automatic server handshake and injects the `ConnectResponse` frame in chunks of
/// `split` bytes, verifying that the session remains in `ASSOCIATING` until the final chunk
/// transitions it to `CONNECTED`.
fn handshake_in_parts(p: Profile, seed: u64, read_only: Option<bool>, split: &[usize]) {
    let mut h = p.builder(seed).build();
    h.server_mut()
        .add_rule(Rule::on_connect().then(RuleAction::Ignore));
    h.run_until(
        |h| h.state() == State::Associating && h.link().is_some(),
        5_000,
    )
    .unwrap();
    h.run_for(10).unwrap();
    assert_eq!(h.state(), State::Associating, "{p:?}");
    let bytes = connect_response(10_000, 0x5eed, read_only);
    let want = if read_only.is_some() { 41 } else { 40 };
    assert_eq!(bytes.len(), want);
    let link = h.link().unwrap();
    let mut at = 0;
    for (i, n) in split.iter().enumerate() {
        let end = if i + 1 == split.len() {
            bytes.len()
        } else {
            at + n
        };
        h.server_mut().push_raw(link, bytes[at..end].to_vec());
        at = end;
        let now = h.now();
        h.step(now);
        if at < bytes.len() {
            assert_eq!(h.state(), State::Associating, "{p:?} after {at} bytes");
        }
    }
    assert_eq!(h.state(), State::Connected, "{p:?}");
    assert_eq!(h.conn().client_id().session_id, 0x5eed, "{p:?}");
    assert_eq!(h.conn().recv_timeout(), 10_000, "{p:?}");
    assert_eq!(
        h.recorder().session_states().last(),
        Some(&State::Connected),
        "{p:?}"
    );
}

/// `Zookeeper_operations::testConnectResponseFull`: a complete 41-byte handshake frame (37-byte
/// body including the read-only flag) delivered in a single read transitions to `CONNECTED`.
#[test]
fn test_connect_response_full() {
    for p in Profile::LEGACY {
        handshake_in_parts(p, 209, Some(false), &[41]);
    }
}

/// `Zookeeper_operations::testConnectResponseNoReadOnlyFlag`: a legacy 40-byte handshake frame
/// (36-byte body omitting the read-only flag) delivered in a single read transitions to
/// `CONNECTED`.
#[test]
fn test_connect_response_no_read_only_flag() {
    for p in Profile::LEGACY {
        handshake_in_parts(p, 210, None, &[40]);
    }
}

/// `Zookeeper_operations::testConnectResponseSplitAtReadOnlyFlag`: delivering the first 40 bytes
/// of a 41-byte handshake frame retains `ASSOCIATING` until the trailing read-only byte arrives.
#[test]
fn test_connect_response_split_at_read_only_flag() {
    for p in Profile::LEGACY {
        handshake_in_parts(p, 211, Some(false), &[40, 1]);
    }
}

/// `Zookeeper_operations::testConnectResponseNoReadOnlyFlagSplit`: a 40-byte handshake frame
/// omitting the read-only flag delivered in two 20-byte chunks remains in `ASSOCIATING` after the
/// first chunk and transitions to `CONNECTED` after the second.
#[test]
fn test_connect_response_no_read_only_flag_split() {
    for p in Profile::LEGACY {
        handshake_in_parts(p, 212, None, &[20, 20]);
    }
}

/// `Zookeeper_operations::testConcurrentOperations1` (single threaded variant): two pipelined
/// `getData` requests complete in submission order with their respective payloads.
#[test]
fn test_concurrent_operations1() {
    for p in Profile::LEGACY {
        let mut h = p.harness(213);
        h.connect().unwrap();
        h.server_mut().create("/x", b"", 0).unwrap();
        h.server_mut().create("/x/y", b"", 0).unwrap();
        h.server_mut().create("/x/y/1", b"1", 0).unwrap();
        h.server_mut().create("/x/y/2", b"2", 0).unwrap();
        let ops = h.ops();
        let t1 = h.submit(ops.get_data("/x/y/1", None)).unwrap();
        let t2 = h.submit(ops.get_data("/x/y/2", None)).unwrap();
        h.wait_for(t2).unwrap();
        h.expect_completion(t1, rc::ZOK).unwrap();
        h.expect_completion(t2, rc::ZOK).unwrap();
        assert_eq!(data_of(&h, t1), b"1", "{p:?}");
        assert_eq!(data_of(&h, t2), b"2", "{p:?}");
        assert_eq!(h.recorder().completion_order(), vec![t1, t2], "{p:?}");
        h.expect_exactly_once().unwrap();
    }
}

/// `Zookeeper_operations::testOperationsAndDisconnectConcurrently1` (single threaded variant):
/// when two `getData` requests are pipelined and the connection drops after the first reply, the
/// first completes with `ZOK` while the second fails with `ZCONNECTIONLOSS`.
#[test]
fn test_operations_and_disconnect_concurrently1() {
    for p in Profile::LEGACY {
        let mut h = p.harness(214);
        h.connect().unwrap();
        h.server_mut().create("/x", b"", 0).unwrap();
        h.server_mut().create("/x/y", b"", 0).unwrap();
        h.server_mut().create("/x/y/1", b"1", 0).unwrap();
        h.server_mut().create("/x/y/2", b"2", 0).unwrap();
        h.server_mut()
            .add_rule(Rule::on_request().path("/x/y/2").then(RuleAction::Ignore));
        let ops = h.ops();
        let t1 = h.submit(ops.get_data("/x/y/1", None)).unwrap();
        let t2 = h.submit(ops.get_data("/x/y/2", None)).unwrap();
        h.wait_for(t1).unwrap();
        assert!(h.recorder().completion(t2).is_none(), "{p:?}");
        h.close_current();
        h.wait_for(t2).unwrap();
        h.expect_completion(t1, rc::ZOK).unwrap();
        assert_eq!(data_of(&h, t1), b"1", "{p:?}");
        let r2 = h.expect_completion(t2, rc::ZCONNECTIONLOSS).unwrap();
        assert!(r2.reply.is_none(), "{p:?}");
        h.expect_exactly_once().unwrap();
    }
}

/// `Zookeeper_operations::testOperationsAndDisconnectConcurrently2` (single threaded variant):
/// advancing the clock by 10 s while two `getData` requests are queued triggers a receive timeout
/// on the next step, failing both with `ZOPERATIONTIMEOUT`.
#[test]
fn test_operations_and_disconnect_concurrently2() {
    for p in Profile::LEGACY {
        let mut h = p.harness(215);
        h.connect().unwrap();
        let ops = h.ops();
        let t1 = h.submit(ops.get_data("/x/y/1", None)).unwrap();
        let t2 = h.submit(ops.get_data("/x/y/2", None)).unwrap();
        let later = h.now().offset_ms(10_000);
        h.step(later);
        h.expect_completion(t1, rc::ZOPERATIONTIMEOUT).unwrap();
        h.expect_completion(t2, rc::ZOPERATIONTIMEOUT).unwrap();
        assert_eq!(cause(&h), Some(Cause::RecvTimeout), "{p:?}");
        h.expect_exactly_once().unwrap();
    }
}

/// `Zookeeper_operations::testAsyncWatcher1` (multithreaded variant): an `exists` request with
/// the default watcher (`watch = 1`) registers a watch that fires once with the target path upon a
/// subsequent `CHANGED` event.
#[test]
fn test_async_watcher1() {
    for p in Profile::LEGACY {
        let w0 = WatcherId::new(20, 0);
        let mut h = p.builder(216).default_watcher(Some(w0)).build();
        h.connect().unwrap();
        h.server_mut().create("/x", b"", 0).unwrap();
        h.server_mut().create("/x/y", b"", 0).unwrap();
        h.server_mut().create("/x/y/z", b"", 0).unwrap();
        let ops = h.ops();
        let r = h.call(ops.exists("/x/y/z", Some(w0))).unwrap();
        assert_eq!(r.rc, rc::ZOK, "{p:?}");
        let mark = h.recorder().mark();
        h.server_mut().set_data("/x/y/z", b"new").unwrap();
        h.run_for(100).unwrap();
        let ev = watches_since(&h, mark);
        assert_eq!(ev, vec![(CHANGED, "/x/y/z".to_string(), vec![w0])], "{p:?}");
    }
}

/// `Zookeeper_operations::testAsyncGetOperation` (multithreaded variant): an asynchronous
/// `getData` request completes with `ZOK` and payload `"1"`.
#[test]
fn test_async_get_operation() {
    for p in Profile::LEGACY {
        let mut h = p.harness(217);
        h.connect().unwrap();
        h.server_mut().create("/x", b"", 0).unwrap();
        h.server_mut().create("/x/y", b"", 0).unwrap();
        h.server_mut().create("/x/y/1", b"1", 0).unwrap();
        let ops = h.ops();
        let t = h.submit(ops.get_data("/x/y/1", None)).unwrap();
        h.wait_for(t).unwrap();
        h.expect_completion(t, rc::ZOK).unwrap();
        assert_eq!(data_of(&h, t), b"1", "{p:?}");
        h.expect_exactly_once().unwrap();
    }
}
