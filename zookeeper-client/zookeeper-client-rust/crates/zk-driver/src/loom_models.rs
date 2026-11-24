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

//! Loom concurrency permutation models for the multithreaded drop-in driver ([`Shared`]).
//!
//! When compiled with `--cfg loom`, the synchronization facade swaps standard synchronization
//! primitives for Loom's deterministic execution primitives. Loom worker threads execute simulated
//! I/O and event dispatch loops, driving the state transition functions of [`Shared`]. Loom
//! explores execution permutations across atomic operations, mutex contentions, and condition
//! variable notifications up to the configured preemption bound.
//!
//! Because these models require access to internal driver internals, such as socket-pair test rigs,
//! mock host leaves, and raw handle constructors, they are implemented as internal unit tests
//! rather than integration tests in `tests/`. They can be executed via `cargo xtask loom`.
//!
//! Key invariants validated across permutations:
//! - **Completion Queue Integrity**: Each completion token is dispatched to the event completion
//!   thread exactly once, thread wakeups are never dropped or coalesced invalidly, and the
//!   dispatch thread terminates only after observing both the stream close mark and the I/O thread
//!   exit status.
//! - **Close Mark Demarcation**: Teardowns initiated concurrently from external application
//!   threads, completion callbacks running on the I/O thread, or both racing simultaneously ensure
//!   every pending request receives exactly one terminal result code.
//! - **FIFO Ordering**: The dispatch thread delivers completion replies in strict request
//!   submission order, even when concurrent threads pump protocol state during handle teardown.
//! - **Teardown Residual Draining**: Residual outcomes generated during final
//!   [`zk_proto::Connection::destroy`] reach the dispatch thread exactly once. In foreign thread
//!   teardowns, these precede the close mark. During callback teardowns, the dispatch thread
//!   processes them during terminal cleanup.
//! - **Graceful Dispatch Thread Termination**: If a user callback terminates the dispatch thread
//!   while an external close is awaiting queued callback completions, the exit cleanup handler
//!   unblocks the waiting close without delivering stale outcomes to dead callbacks.
//!
//! Because Loom schedules threads cooperatively on a single operating system thread, any blocking
//! system call would deadlock the entire model run. To avoid blocking, test rigs pre-populate
//! socket buffers with mock replies before threads spawn, and simulated polling routines never
//! block indefinitely. Following initial step processing, the I/O thread cooperatively yields via
//! [`loom::thread::yield_now`] while awaiting teardown signals.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::BTreeMap;
use std::sync::Arc;

use loom::thread::{self, JoinHandle};
use rustix::event::{PollFd, PollFlags};
use rustix::io::Errno;
use zk_jute::rc::{ZCLOSING, ZCONNECTIONLOSS, ZOK};
use zk_proto::{Compat, DriverMode, LogSite, OpResult, PathIn, Request, State};
use zk_testkit::server::codec::{encode_reply, split_frames};
use zk_testkit::tokens::{CompletionId, TestTokens};

use super::{
    Rig, accept_frame, exists_xids, queued_exists, read_ev, stop_the_teardown,
    submit_a_sync_and_a_queued_request, write_ev,
};
use crate::clock::Clock;
use crate::host::HostLeaves;
use crate::net::poll::Poll;
use crate::outbox::{Flavor, Item, Outbox, Step, ThreadOp, ThreadRole};
use crate::state::{Core, Env};
use crate::sync::{Condvar, Mutex, Unlocked};
use crate::testutil::{RealPoll, TestHost, exists_request, test_connection};
use crate::threaded::{Role, Shared};

type S = Shared<TestTokens>;

/// Default maximum preemption bound explored by Loom; may be overridden via
/// `LOOM_MAX_PREEMPTIONS`.
const DEFAULT_PREEMPTIONS: usize = 3;

/// Executes the provided closure under Loom's model checker with bounded preemption exploration.
pub(crate) fn model(f: impl Fn() + Sync + Send + 'static) {
    let mut b = loom::model::Builder::new();
    if b.preemption_bound.is_none() {
        b.preemption_bound = Some(DEFAULT_PREEMPTIONS);
    }
    b.check(f);
}

/// Constructs a test driver handle around an unconnected mock connection, where teardown fails
/// pending requests with `ZCLOSING`.
fn handle() -> (Arc<S>, Arc<TestHost>) {
    let host = Arc::new(TestHost::new());
    let leaves: Arc<dyn HostLeaves<TestTokens>> = host.clone();
    let core = Core::new(Box::new(test_connection(DriverMode::MT)));
    let mut ob = Outbox::new();
    let shared = Shared::new(core, Env::new(leaves), &mut ob).unwrap();
    assert!(ob.is_empty());
    (shared, host)
}

fn exists(id: u64) -> Request<TestTokens> {
    exists_request(PathIn::Legacy(Some(b"/".to_vec())), id)
}

/// Synthesizes a mock decoded operation result associated with the given completion identifier.
fn decoded(id: u64) -> Item<TestTokens> {
    Item::Result {
        token: CompletionId::new(id),
        result: OpResult::teardown(ZCONNECTIONLOSS),
        delivery_logs: Vec::new(),
    }
}

/// Map associating request completion IDs with their delivered result codes.
type ByToken = BTreeMap<u64, Vec<i32>>;

/// Collector tracking completion results delivered to callbacks during model execution.
#[derive(Debug, Default)]
struct Delivered {
    /// Delivered result codes indexed by completion token ID.
    by_token: ByToken,
    /// Chronological sequence of delivered (token ID, return code) pairs.
    order: Vec<(u64, i32)>,
    /// Chronological sequence of delivered authentication completions.
    auths: Vec<(u64, i32)>,
}

impl Delivered {
    fn new() -> Self {
        Self::default()
    }

    fn is_empty(&self) -> bool {
        self.order.is_empty() && self.auths.is_empty()
    }

    /// Asserts that completion items carrying return code `rc` were delivered in monotonic token
    /// ID order.
    fn assert_in_order(&self, rc: i32) {
        let ids: Vec<u64> = self
            .order
            .iter()
            .filter(|(_, r)| *r == rc)
            .map(|(id, _)| *id)
            .collect();
        assert!(
            ids.is_sorted(),
            "results {rc} out of order: {:?}",
            self.order
        );
    }
}

fn note(seen: &mut Delivered, item: &Item<TestTokens>) {
    match item {
        Item::Result { token, result, .. } => {
            seen.by_token.entry(token.id()).or_default().push(result.rc);
            seen.order.push((token.id(), result.rc));
        }
        Item::AuthResult { token, rc } => seen.auths.push((token.id(), *rc)),
        _ => {}
    }
}

/// Drives driver step execution to completion, invoking `on` for each emitted outbox item.
fn drive(
    s: &S,
    first: Step,
    ob: &mut Outbox<TestTokens>,
    rc: &mut i32,
    mut on: impl FnMut(&Item<TestTokens>),
) -> Step {
    let mut step = first;
    loop {
        while let Some(item) = ob.pop() {
            on(&item);
        }
        if step != Step::More {
            return step;
        }
        step = s.resume(ob, rc);
    }
}

/// Thread-safe delivery counter allowing caller threads to block until a target delivery threshold
/// is met.
#[derive(Default)]
struct Count {
    n: Mutex<usize>,
    cv: Condvar,
}

impl Count {
    fn add(&self) {
        *self.n.lock_owner() += 1;
        self.cv.notify_all();
    }

    fn wait_for(&self, n: usize) {
        let mut got = self.n.lock_owner();
        while *got < n {
            got = self.cv.wait(got);
        }
    }
}

/// Spawns the simulated C dispatch loop thread, returning a handle yielding delivered completions.
fn spawn_dispatch(s: &Arc<S>) -> JoinHandle<Delivered> {
    spawn_counted_dispatch(s, Arc::default())
}

/// Spawns the simulated C dispatch loop thread, incrementing `count` for each delivered
/// completion.
fn spawn_counted_dispatch(s: &Arc<S>, count: Arc<Count>) -> JoinHandle<Delivered> {
    let s = Arc::clone(s);
    thread::spawn(move || {
        let mut seen = Delivered::new();
        loop {
            let mut ob = s.outbox();
            if s.wait_dispatch(&mut ob) == Step::Exit {
                break;
            }
            while let Some(item) = ob.pop() {
                note(&mut seen, &item);
                count.add();
            }
        }
        // Verify that the completion queue has recorded the close mark and terminal I/O exit.
        {
            let fifo = s.queue.fifo.lock_owner();
            assert!(fifo.close_published && fifo.io_exited && fifo.items.is_empty());
        }
        let mut ob = s.outbox();
        let mut rc = -1;
        let first = s.thread_exit(Role::Dispatch, &mut ob, &mut rc);
        assert_eq!(drive(&s, first, &mut ob, &mut rc, |_| {}), Step::Done(ZOK));
        seen
    })
}

/// Spawns a simulated I/O thread publishing decoded results for `ids` during its initial step.
fn spawn_io(s: &Arc<S>, ids: &'static [u64], early: bool) -> JoinHandle<()> {
    spawn_paced_io(s, ids, early, None)
}

/// Spawns a simulated I/O thread that optionally paces publication of individual results using a
/// delivery counter.
fn spawn_paced_io(
    s: &Arc<S>,
    ids: &'static [u64],
    early: bool,
    pace: Option<Arc<Count>>,
) -> JoinHandle<()> {
    let s = Arc::clone(s);
    thread::spawn(move || {
        match &pace {
            None => {
                let mut ob = s.outbox();
                for id in ids {
                    ob.push_publish(decoded(*id));
                }
                assert!(ob.pop().is_none());
            }
            Some(count) => {
                for (done, id) in (1..).zip(ids) {
                    let mut ob = s.outbox();
                    ob.push_publish(decoded(*id));
                    assert!(ob.pop().is_none());
                    count.wait_for(done);
                }
            }
        }
        if !early {
            while !s.life.close_requested() {
                thread::yield_now();
            }
            let mut ob = s.outbox();
            let mut rc = -1;
            assert_eq!(s.io_step(&mut ob, &mut rc), Step::Exit);
        }
        let mut ob = s.outbox();
        let mut rc = -1;
        let first = s.thread_exit(Role::Io, &mut ob, &mut rc);
        assert_eq!(drive(&s, first, &mut ob, &mut rc, |_| {}), Step::Done(ZOK));
    })
}

/// Executes session close from an external application thread, joining driver threads upon
/// completion.
fn close_foreign(s: &S, io: JoinHandle<()>, dispatch: JoinHandle<Delivered>) -> Delivered {
    let (seen, got) = close_foreign_seen(s, io, dispatch);
    assert!(seen.is_empty(), "{seen:?}");
    got
}

/// Executes session close from an external application thread, returning completions seen by both
/// caller and dispatch threads.
fn close_foreign_seen(
    s: &S,
    io: JoinHandle<()>,
    dispatch: JoinHandle<Delivered>,
) -> (Delivered, Delivered) {
    let mut io = Some(io);
    let mut dispatch = Some(dispatch);
    let mut got = None;
    let mut seen = Delivered::new();
    let mut ob = s.outbox();
    let mut rc = -1;
    let first = s.close(Role::Foreign, &mut ob, &mut rc);
    let end = drive(s, first, &mut ob, &mut rc, |item| match item {
        Item::Thread {
            op: ThreadOp::Join,
            role: ThreadRole::Io,
        } => io.take().unwrap().join().unwrap(),
        Item::Thread {
            op: ThreadOp::Join,
            role: ThreadRole::Dispatch,
        } => got = Some(dispatch.take().unwrap().join().unwrap()),
        other => note(&mut seen, other),
    });
    assert_eq!((end, rc), (Step::Release, ZOK));
    // External closing threads process no request completions directly; authentication callbacks
    // may run inline.
    assert!(seen.order.is_empty(), "{seen:?}");
    (seen, got.expect("the close joined the dispatch thread"))
}

fn expect(pairs: &[(u64, i32)]) -> ByToken {
    pairs.iter().map(|(id, rc)| (*id, vec![*rc])).collect()
}

/// Verifies that the completion queue delivers every pending completion token exactly once under
/// concurrent teardown.
#[test]
fn loom_completion_queue_delivers_each_token_once() {
    model(|| {
        let (s, host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        assert!(s.submit(exists(1)).is_ok());
        assert!(s.submit(exists(2)).is_ok());
        let dispatch = spawn_dispatch(&s);
        let io = spawn_io(&s, &[10, 11], false);
        let got = close_foreign(&s, io, dispatch);
        got.assert_in_order(ZCONNECTIONLOSS);
        assert_eq!(
            got.by_token,
            expect(&[
                (1, ZCLOSING),
                (2, ZCLOSING),
                (10, ZCONNECTIONLOSS),
                (11, ZCONNECTIONLOSS)
            ])
        );
        assert!(host.discarded().is_empty());
    });
}

/// Verifies that notification wakeups are reliably received by the dispatch thread for each
/// published batch.
#[test]
fn loom_completion_queue_wakes_the_dispatch_thread_for_each_publish() {
    model(|| {
        let (s, host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        let count = Arc::new(Count::default());
        let dispatch = spawn_counted_dispatch(&s, Arc::clone(&count));
        let io = spawn_paced_io(&s, &[10, 11], false, Some(Arc::clone(&count)));
        count.wait_for(2);
        let got = close_foreign(&s, io, dispatch);
        got.assert_in_order(ZCONNECTIONLOSS);
        assert_eq!(
            got.by_token,
            expect(&[(10, ZCONNECTIONLOSS), (11, ZCONNECTIONLOSS)])
        );
        assert!(host.discarded().is_empty());
    });
}

/// Verifies that premature I/O thread exit does not terminate the dispatch loop before a close
/// mark is published.
#[test]
fn loom_completion_queue_outlives_an_early_io_exit() {
    model(|| {
        let (s, host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        assert!(s.submit(exists(1)).is_ok());
        let dispatch = spawn_dispatch(&s);
        let io = spawn_io(&s, &[10], true);
        let got = close_foreign(&s, io, dispatch);
        assert_eq!(
            got.by_token,
            expect(&[(1, ZCLOSING), (10, ZCONNECTIONLOSS)])
        );
        assert!(host.discarded().is_empty());
    });
}

/// Spawns a dispatch thread configured to invoke dispatch termination during callback processing.
fn spawn_ending_dispatch(s: &Arc<S>) -> JoinHandle<Delivered> {
    let s = Arc::clone(s);
    thread::spawn(move || {
        let mut seen = Delivered::new();
        let mut ob = s.outbox();
        // External close publishes the close mark after the wait; initial wait retrieves published
        // I/O batches.
        assert_eq!(s.wait_dispatch(&mut ob), Step::Done(ZOK));
        note(&mut seen, &ob.pop().unwrap());
        s.dispatch_ended(&mut ob);
        seen
    })
}

/// Verifies that dispatch thread termination during callback execution properly wakes an external
/// close wait.
#[test]
fn loom_a_dispatch_end_wakes_a_foreign_close() {
    model(|| {
        let (s, host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        assert!(s.submit(exists(1)).is_ok());
        let dispatch = spawn_ending_dispatch(&s);
        let io = spawn_io(&s, &[10, 11], false);
        let (_, got) = close_foreign_seen(&s, io, dispatch);
        assert_eq!(got.order.len(), 1);
        assert!(got.by_token.values().all(|rcs| rcs.len() == 1));
        assert_eq!(host.discarded(), ["completion", "completion"]);
        assert_eq!(s.life.refs(), 0);
    });
}

/// Verifies that request submissions racing concurrent session closure either fail immediately or
/// complete exactly once.
#[test]
fn loom_a_submit_that_races_the_close_completes_once() {
    model(|| {
        let (s, host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        let dispatch = spawn_dispatch(&s);
        let io = spawn_io(&s, &[], false);
        let submitter = {
            let s = Arc::clone(&s);
            thread::spawn(move || s.submit(exists(7)).is_ok())
        };
        let got = close_foreign(&s, io, dispatch);
        let accepted = submitter.join().unwrap();
        if accepted {
            assert_eq!(got.by_token, expect(&[(7, ZCLOSING)]));
        } else {
            assert!(got.is_empty(), "{got:?}");
        }
        assert!(host.discarded().is_empty());
    });
}

// ---------------------------------------------------------------------
// Socket-pair based close mark and frame validation models.
// ---------------------------------------------------------------------

/// Synthetic ZooKeeper error code returned in simulated peer replies (`ZNONODE`).
const ZNONODE: i32 = -101;

/// Reads incoming socket data from the peer endpoint until at least `n` complete protocol frames
/// are accumulated.
fn recv_frames(r: &Rig, n: usize) -> Vec<u8> {
    let mut out = Vec::new();
    let mut buf = vec![0u8; 64 * 1024];
    while split_frames(&out).map_or(0, |f| f.len()) < n {
        let mut fds = [PollFd::new(&r.peer, PollFlags::IN)];
        assert_eq!(RealPoll.poll(&mut fds, 1000).unwrap(), 1, "no frame came");
        loop {
            match r.peer.recv(&mut buf) {
                Ok(0) => panic!("the driver closed the socket"),
                Ok(k) => out.extend_from_slice(&buf[..k]),
                Err(Errno::WOULDBLOCK) => break,
                Err(Errno::INTR) => {}
                Err(e) => panic!("peer recv: {e}"),
            }
        }
    }
    out
}

/// Constructs an active multithreaded test rig in the `Connected` state preloaded with queued
/// `exists` requests.
fn rig_with_replies(ids: &[u64]) -> Rig {
    let mut r = Rig::with_mode(false, vec![], Compat::LEGACY_C, DriverMode::MT);
    r.flavor = Flavor::Mt;
    // Disable close wait intervals so final teardown sends CLOSE_OP and tears down immediately.
    r.env.cfg.close_wait_ms = 0;
    let _ = r.process(write_ev());
    let _connect = recv_frames(&r, 1);
    r.peer_send(&accept_frame());
    let _ = r.process(read_ev());
    assert_eq!(r.state(), State::Connected);
    {
        let now = r.clock.now();
        let mut proof = Unlocked::new_at_entry();
        let mut core = r.core.lock(&mut proof);
        let conn = core.conn.as_deref_mut().unwrap();
        for id in ids {
            assert!(conn.submit(queued_exists(*id), now).is_ok());
        }
    }
    let _ = r.interest();
    let _ = r.process(write_ev());
    let xids = exists_xids(&recv_frames(&r, ids.len()));
    assert_eq!(xids.len(), ids.len(), "{xids:?}");
    let mut replies = Vec::new();
    for (zxid, xid) in (5..).zip(&xids) {
        replies.extend_from_slice(&encode_reply(*xid, zxid, ZNONODE, &[]));
    }
    r.peer_send(&replies);
    r.wait_readable();
    r
}

/// nonblocking polling facade implementation designed to avoid starving Loom's cooperative
/// single-thread scheduler.
struct ModelPoll;

impl Poll for ModelPoll {
    fn poll(&self, fds: &mut [PollFd<'_>], timeout_ms: i32) -> rustix::io::Result<usize> {
        let ready = RealPoll.poll(fds, 0)?;
        if ready == 0 && timeout_ms != 0 {
            thread::yield_now();
        }
        Ok(ready)
    }
}

/// Test wrapper anchoring a threaded driver handle and keeping peer sockets alive for the model
/// lifetime.
struct RigHandle {
    s: Arc<S>,
    host: Arc<TestHost>,
    _peer: crate::net::sock::TcpSock,
}

fn into_handle(r: Rig) -> RigHandle {
    let Rig {
        host,
        mut env,
        core,
        io,
        peer,
        ..
    } = r;
    env.poll = Arc::new(ModelPoll);
    let mut ob = Outbox::new();
    let s = Shared::new(core.into_inner(), env, &mut ob).unwrap();
    assert!(ob.is_empty());
    s.io.lock_owner().io = io;
    s.thread_enter(Role::Io);
    s.thread_enter(Role::Dispatch);
    RigHandle {
        s,
        host,
        _peer: peer,
    }
}

/// Synchronization coordinator managing access to the dispatch thread join handle across
/// concurrent teardowns.
#[derive(Clone)]
struct DispatchSlot {
    handle: Arc<Mutex<Option<JoinHandle<Delivered>>>>,
    got: Arc<Mutex<Option<Delivered>>>,
}

impl DispatchSlot {
    fn spawn(s: &Arc<S>) -> Self {
        Self {
            handle: Arc::new(Mutex::new(Some(spawn_dispatch(s)))),
            got: Arc::new(Mutex::new(None)),
        }
    }

    fn join(&self) {
        let h = self.handle.lock_owner().take().expect("one join only");
        let got = h.join().unwrap();
        *self.got.lock_owner() = Some(got);
    }

    fn delivered(&self) -> Delivered {
        self.got
            .lock_owner()
            .take()
            .expect("a close joined the dispatch thread")
    }
}

fn is_queueing_log(item: &Item<TestTokens>) -> bool {
    matches!(item, Item::Log(rec) if rec.site == LogSite::QueueingAsync)
}

/// Simulated C I/O worker thread driving socket polling and protocol stepping across the test rig.
fn io_thread(s: &S, close_in_log: bool, dispatch: &DispatchSlot) -> Delivered {
    let mut seen = Delivered::new();
    let mut closed = false;
    let mut ob = s.outbox();
    let mut rc = -1;
    let first = s.io_step(&mut ob, &mut rc);
    let end = drive(s, first, &mut ob, &mut rc, |item| {
        note(&mut seen, item);
        if close_in_log && !closed && is_queueing_log(item) {
            closed = true;
            let mut ob = s.outbox();
            let mut rc = -1;
            let first = s.close(Role::Io, &mut ob, &mut rc);
            let end = drive(s, first, &mut ob, &mut rc, |item| match item {
                Item::Thread {
                    op: ThreadOp::Join,
                    role: ThreadRole::Dispatch,
                } => dispatch.join(),
                Item::Thread {
                    op: ThreadOp::Join,
                    role: ThreadRole::Io,
                } => panic!("the IO thread joins itself"),
                other => note(&mut seen, other),
            });
            assert_eq!(end, Step::Done(ZOK));
        }
    });
    if end != Step::Exit {
        while !s.life.close_requested() {
            thread::yield_now();
        }
        let mut ob = s.outbox();
        let mut rc = -1;
        assert_eq!(s.io_step(&mut ob, &mut rc), Step::Exit);
    }
    let mut ob = s.outbox();
    let mut rc = -1;
    let first = s.thread_exit(Role::Io, &mut ob, &mut rc);
    let end = drive(s, first, &mut ob, &mut rc, |item| note(&mut seen, item));
    assert!(matches!(end, Step::Done(ZOK) | Step::Release), "{end:?}");
    seen
}

/// Initiates handle closure from an external application thread, coordinating joins across I/O and
/// dispatch workers.
fn foreign_close(
    s: &S,
    io: &mut Option<JoinHandle<Delivered>>,
    dispatch: &DispatchSlot,
) -> (Delivered, Delivered) {
    let mut seen = Delivered::new();
    let mut on_io = Delivered::new();
    let mut ob = s.outbox();
    let mut rc = -1;
    let first = s.close(Role::Foreign, &mut ob, &mut rc);
    let end = drive(s, first, &mut ob, &mut rc, |item| match item {
        Item::Thread {
            op: ThreadOp::Join,
            role: ThreadRole::Io,
        } => on_io = io.take().unwrap().join().unwrap(),
        Item::Thread {
            op: ThreadOp::Join,
            role: ThreadRole::Dispatch,
        } => dispatch.join(),
        other => note(&mut seen, other),
    });
    assert!(matches!(end, Step::Done(ZOK) | Step::Release), "{end:?}");
    (seen, on_io)
}

/// Merges completion outcome vectors delivered across distinct threads into a consolidated map.
fn merge(parts: &[&Delivered]) -> ByToken {
    let mut all = ByToken::new();
    for part in parts {
        for (id, rcs) in &part.by_token {
            all.entry(*id).or_default().extend_from_slice(rcs);
        }
    }
    all
}

/// Asserts that every submitted request received exactly one terminal outcome and in strict FIFO
/// dispatch order.
fn check_once(h: &RigHandle, ids: &[u64], all: &ByToken, dispatched: &Delivered) {
    dispatched.assert_in_order(ZNONODE);
    assert_eq!(all.keys().copied().collect::<Vec<_>>(), ids, "{all:?}");
    for (id, rcs) in all {
        assert!(
            matches!(rcs.as_slice(), [ZNONODE] | [ZCLOSING]),
            "request {id}: {rcs:?}"
        );
    }
    assert!(h.host.discarded().is_empty(), "{:?}", h.host.discarded());
    let mut proof = Unlocked::new_at_entry();
    assert!(h.s.core.lock(&mut proof).conn.is_none(), "no final close");
}

/// Verifies that closing a handle from inside an I/O thread logging callback safely delivers all
/// decoded replies.
#[test]
fn loom_a_close_from_an_io_log_callback_delivers_every_decoded_reply() {
    model(|| {
        let h = into_handle(rig_with_replies(&[2, 3]));
        let dispatch = DispatchSlot::spawn(&h.s);
        let on_io = io_thread(&h.s, true, &dispatch);
        let got = dispatch.delivered();
        assert!(on_io.is_empty(), "{on_io:?}");
        assert_eq!(got.by_token, expect(&[(2, ZNONODE), (3, ZNONODE)]));
        check_once(&h, &[2, 3], &got.by_token, &got);
    });
}

/// Validates outcomes when an external close races an in-progress I/O step holding decoded replies.
#[test]
fn loom_a_foreign_close_racing_an_io_step_gives_one_result_each() {
    model(|| {
        let h = into_handle(rig_with_replies(&[2, 3]));
        let dispatch = DispatchSlot::spawn(&h.s);
        let mut io = Some({
            let s = Arc::clone(&h.s);
            let d = dispatch.clone();
            thread::spawn(move || io_thread(&s, false, &d))
        });
        let (on_closer, on_io) = foreign_close(&h.s, &mut io, &dispatch);
        assert!(io.is_none(), "the close joined the IO thread");
        let got = dispatch.delivered();
        assert!(on_closer.is_empty() && on_io.is_empty());
        check_once(&h, &[2, 3], &got.by_token, &got);
    });
}

/// Validates mutual exclusion when an external teardown races an internal callback-driven teardown.
#[test]
fn loom_a_foreign_close_racing_an_io_callback_close_gives_one_result_each() {
    model(|| {
        let h = into_handle(rig_with_replies(&[2, 3]));
        let dispatch = DispatchSlot::spawn(&h.s);
        let mut io = Some({
            let s = Arc::clone(&h.s);
            let d = dispatch.clone();
            thread::spawn(move || io_thread(&s, true, &d))
        });
        let (on_closer, mut on_io) = foreign_close(&h.s, &mut io, &dispatch);
        // Ensure the I/O worker terminates cleanly so Loom accounts for all spawned threads.
        if let Some(io) = io.take() {
            on_io = io.join().unwrap();
        }
        let got = dispatch.delivered();
        let all = merge(&[&got, &on_closer, &on_io]);
        check_once(&h, &[2, 3], &all, &got);
    });
}

/// Constructs an active multithreaded test rig exhibiting abrupt peer stream shutdown.
fn rig_at_the_end_of_the_stream() -> Rig {
    let mut r = Rig::with_mode(false, vec![], Compat::LEGACY_C, DriverMode::MT);
    r.flavor = Flavor::Mt;
    let _ = r.process(write_ev());
    let _connect = recv_frames(&r, 1);
    r.peer_send(&accept_frame());
    let _ = r.process(read_ev());
    assert_eq!(r.state(), State::Connected);
    submit_a_sync_and_a_queued_request(&mut r);
    let _requests = recv_frames(&r, 3);
    rustix::net::shutdown(&r.peer, rustix::net::Shutdown::Write).unwrap();
    r.wait_readable();
    r
}

/// Validates terminal draining when I/O thread teardown stalls and residual outcomes are emitted
/// via `destroy`.
#[test]
fn loom_a_foreign_close_gives_a_destroy_result_to_the_dispatch_thread() {
    model(|| {
        let h = into_handle(rig_at_the_end_of_the_stream());
        stop_the_teardown(&h.s);
        let dispatch = spawn_dispatch(&h.s);
        let io = spawn_io(&h.s, &[], false);
        let (closer, got) = close_foreign_seen(&h.s, io, dispatch);
        assert_eq!(got.by_token, expect(&[(2, ZCLOSING)]));
        assert!(got.auths.is_empty(), "{got:?}");
        assert_eq!(closer.auths, [(3, ZCLOSING)]);
        assert!(h.host.discarded().is_empty(), "{:?}", h.host.discarded());
        let mut proof = Unlocked::new_at_entry();
        assert!(h.s.core.lock(&mut proof).conn.is_none(), "no final close");
    });
}

/// Spawns a dispatch worker whose initial completion callback initiates handle closure.
fn spawn_closing_dispatch(s: &Arc<S>, io: JoinHandle<()>) -> JoinHandle<(Delivered, Step)> {
    let s = Arc::clone(s);
    thread::spawn(move || {
        let mut io = Some(io);
        let mut seen = Delivered::new();
        loop {
            let mut ob = s.outbox();
            if s.wait_dispatch(&mut ob) == Step::Exit {
                break;
            }
            while let Some(item) = ob.pop() {
                note(&mut seen, &item);
                if io.is_none() {
                    continue;
                }
                let mut ob = s.outbox();
                let mut rc = -1;
                let first = s.close(Role::Dispatch, &mut ob, &mut rc);
                let end = drive(&s, first, &mut ob, &mut rc, |item| match item {
                    Item::Thread {
                        op: ThreadOp::Join,
                        role: ThreadRole::Io,
                    } => io.take().unwrap().join().unwrap(),
                    Item::Thread {
                        op: ThreadOp::Join,
                        role: ThreadRole::Dispatch,
                    } => panic!("the dispatch thread joins itself"),
                    other => note(&mut seen, other),
                });
                assert_eq!((end, rc), (Step::Done(ZOK), ZOK));
            }
        }
        assert!(io.is_none(), "the close joined the IO thread");
        let mut ob = s.outbox();
        let mut rc = -1;
        let first = s.thread_exit(Role::Dispatch, &mut ob, &mut rc);
        let end = drive(&s, first, &mut ob, &mut rc, |item| note(&mut seen, item));
        assert_eq!(rc, ZOK);
        (seen, end)
    })
}

/// Verifies teardown residual delivery when closure originates within a completion callback on the
/// dispatch thread.
#[test]
fn loom_a_close_from_a_completion_callback_delivers_destroy_results_once() {
    model(|| {
        let h = into_handle(rig_at_the_end_of_the_stream());
        stop_the_teardown(&h.s);
        h.s.thread_enter(Role::Foreign);
        let io = spawn_io(&h.s, &[], false);
        let dispatch = spawn_closing_dispatch(&h.s, io);
        let holder = {
            let s = Arc::clone(&h.s);
            thread::spawn(move || {
                let mut ob = s.outbox();
                let mut rc = -1;
                let first = s.thread_exit(Role::Foreign, &mut ob, &mut rc);
                let mut seen = Delivered::new();
                let end = drive(&s, first, &mut ob, &mut rc, |item| note(&mut seen, item));
                assert_eq!(rc, ZOK);
                (seen, end)
            })
        };
        let (got, dispatch_end) = dispatch.join().unwrap();
        let (on_holder, holder_end) = holder.join().unwrap();
        assert!(on_holder.is_empty(), "{on_holder:?}");
        assert_eq!(got.by_token, expect(&[(2, ZCLOSING)]));
        assert_eq!(got.auths, [(3, ZCLOSING)]);
        let releases = [dispatch_end, holder_end]
            .iter()
            .filter(|end| **end == Step::Release)
            .count();
        assert_eq!(releases, 1, "{dispatch_end:?} {holder_end:?}");
        assert!(h.host.discarded().is_empty(), "{:?}", h.host.discarded());
        let mut proof = Unlocked::new_at_entry();
        assert!(h.s.core.lock(&mut proof).conn.is_none(), "no final close");
    });
}
