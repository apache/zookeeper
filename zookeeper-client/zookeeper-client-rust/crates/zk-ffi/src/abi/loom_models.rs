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

//! Concurrency permutation models validating session shutdown and token lifecycle invariants under
//! Loom.
//!
//! Simulated Loom threads model the concurrent execution of the multithreaded C shim,
//! interleaving the I/O event worker, asynchronous completion dispatch thread,
//! and application callers. These tests verify strict ownership invariants across the C ABI
//! boundary. A token must either be dispatched to user callbacks via the dispatch worker or
//! returned immediately to the calling thread upon submission failure. Uncontrolled token
//! abandonment without callback dispatch constitutes a memory leak or lost completion and triggers
//! assertion failures.
//!
//! The models additionally verify synchronization barriers surrounding session close marks. The
//! dispatch thread must terminate only after processing all pending shutdown completions. If an
//! application callback terminates the dispatch worker abruptly, the shim cleanup handler must
//! safely reclaim orphaned tokens.
//!
//! These models leverage synthetic test fixtures and the stand in host vtable, executing
//! deterministically under simulated cooperative scheduling via `cargo xtask loom`.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::disallowed_macros,
    reason = "test code may panic and assert"
)]

use std::collections::BTreeMap;
use std::ffi::{CString, c_int};
use std::sync::Arc;

use loom::thread::{self, JoinHandle};
use zk_driver::sync::Mutex;
use zk_jute::rc::ZCLOSING;

use super::handle::test_support::{empty_outbox, init_args};
use super::handle::{
    zkrs_close, zkrs_dispatch_ended, zkrs_handle_new, zkrs_handle_release, zkrs_handle_retain,
    zkrs_resume, zkrs_thread_enter, zkrs_thread_exit, zkrs_wait_dispatch,
};
use super::outbox::{zkrs_outbox_finish, zkrs_outbox_next};
use super::raw;
use super::standin;
use super::submit::zkrs_submit;
use super::types::{
    ZKRS_DELIVER_QUEUED, ZKRS_OP_EXISTS, ZKRS_ROLE_DISPATCH, ZKRS_ROLE_FOREIGN, ZKRS_ROLE_IO,
    zkrs_handle, zkrs_item, zkrs_item_type, zkrs_outbox, zkrs_request, zkrs_sockaddr_view,
    zkrs_status, zkrs_step, zkrs_thread_op,
};

/// Default preemption bound constraining Loom thread scheduling permutations, overridable via
/// `LOOM_MAX_PREEMPTIONS`.
const DEFAULT_PREEMPTIONS: usize = 3;

/// Simulated ensemble server endpoint address for test session initialization without opening
/// network sockets.
const HOSTS: &str = "127.0.0.1:23751";

fn model(f: impl Fn() + Sync + Send + 'static) {
    let mut b = loom::model::Builder::new();
    if b.preemption_bound.is_none() {
        b.preemption_bound = Some(DEFAULT_PREEMPTIONS);
    }
    b.check(f);
}

/// Thread-safe wrapper encapsulating an exposed raw handle address for transfer across Loom thread
/// boundaries.
#[derive(Clone, Copy)]
struct H(usize);

impl H {
    fn of(h: *const zkrs_handle) -> Self {
        Self(h.expose_provenance())
    }

    fn get(self) -> *const zkrs_handle {
        std::ptr::with_exposed_provenance(self.0)
    }
}

/// Aggregated map tracking terminal completion outcomes observed by individual worker threads
/// (token ID -> return codes).
type Delivered = BTreeMap<usize, Vec<i32>>;

/// Instantiates a fully initialized multithreaded test handle matching state prior to background
/// worker startup.
fn ready() -> H {
    let addr = zkrs_sockaddr_view::from_addr(HOSTS.parse().ok());
    standin::set_resolve(vec![addr], Vec::new(), 0, 0);
    let hosts = CString::new(HOSTS).unwrap();
    let a = init_args(&hosts);
    let vt = standin::vtable();
    let mut out: *mut zkrs_handle = std::ptr::null_mut();
    let mut ob = empty_outbox();
    let mut rc: c_int = -1;
    let mut s = zkrs_handle_new(&a, &vt, 0x55, &mut out, &mut ob, &mut rc);
    assert!(!out.is_null());
    while s == zkrs_step::ZKRS_MORE {
        drop(raw::outbox_take(&mut ob));
        s = zkrs_resume(out, &mut ob, &mut rc);
    }
    drop(raw::outbox_take(&mut ob));
    assert_eq!((s, rc), (zkrs_step::ZKRS_DONE, 0));
    H::of(out)
}

/// Generates a standard `exists("/a")` test request envelope.
fn exists() -> zkrs_request {
    zkrs_request {
        op: ZKRS_OP_EXISTS,
        flags: 0,
        path: c"/a".as_ptr(),
        data: std::ptr::null(),
        datalen: -1,
        acl: std::ptr::null(),
        mode: 0,
        ttl: -1,
        version: -1,
        reconfig_version: -1,
        joining: std::ptr::null(),
        leaving: std::ptr::null(),
        members: std::ptr::null(),
        watch_fn: 0,
        watch_ctx: 0,
        wtype: 0,
        ops: std::ptr::null(),
        ops_len: 0,
        count: 0,
    }
}

/// Submits an asynchronous exists request associated with `token`, mirroring `zoo_aexists`.
fn submit(h: H, token: usize) -> zkrs_status {
    let rq = exists();
    let mut ob = empty_outbox();
    let st = zkrs_submit(h.get(), &rq, token, ZKRS_DELIVER_QUEUED, &mut ob);
    zkrs_outbox_finish(&mut ob);
    st
}

/// Tagged union representing event variants extracted from the driver outbox during test draining.
enum Seen {
    Result { token: usize, rc: i32 },
    Thread { op: u32, role: u32 },
    Other,
}

fn seen(it: &zkrs_item) -> Seen {
    if it.r#type == zkrs_item_type::ZKRS_ITEM_RESULT as u32 {
        Seen::Result {
            token: it.token,
            rc: raw::read_struct(it.result).rc,
        }
    } else if it.r#type == zkrs_item_type::ZKRS_ITEM_THREAD as u32 {
        Seen::Thread {
            op: it.thread_op,
            role: it.thread_role,
        }
    } else {
        Seen::Other
    }
}

const JOIN: u32 = zkrs_thread_op::ZKRS_THREAD_JOIN as u32;
const DETACH: u32 = zkrs_thread_op::ZKRS_THREAD_DETACH as u32;

/// Iteratively drains outbox items and invokes `on` for each observed event, recording completion
/// results into `got`.
fn drain(ob: &mut zkrs_outbox, got: &mut Delivered, on: &mut dyn FnMut(&Seen)) {
    let mut it = zkrs_item::empty();
    while zkrs_outbox_next(ob, &mut it) == 1 {
        let s = seen(&it);
        if let Seen::Result { token, rc } = s {
            got.entry(token).or_default().push(rc);
        }
        on(&s);
    }
    zkrs_outbox_finish(ob);
}

/// Drives an ABI operation to completion by resuming across intermediate barriers, mirroring
/// `zks_drive`.
fn drive(
    h: H,
    first: zkrs_step,
    ob: &mut zkrs_outbox,
    rc: &mut c_int,
    got: &mut Delivered,
    on: &mut dyn FnMut(&Seen),
) -> zkrs_step {
    let mut s = first;
    loop {
        drain(ob, got, on);
        if s != zkrs_step::ZKRS_MORE {
            return s;
        }
        s = zkrs_resume(h.get(), ob, rc);
    }
}

/// Simulates thread exit handling by invoking `zkrs_thread_exit`, relinquishing thread and handle
/// ownership references.
fn leave(h: H, role: u32, got: &mut Delivered) -> zkrs_step {
    let mut ob = empty_outbox();
    let mut rc: c_int = -1;
    let first = zkrs_thread_exit(h.get(), role, &mut ob, &mut rc);
    let end = drive(h, first, &mut ob, &mut rc, got, &mut |_| {});
    if end == zkrs_step::ZKRS_RELEASE {
        zkrs_handle_release(h.get());
    }
    zkrs_handle_release(h.get());
    end
}

/// Spawns a Loom worker simulating the multithreaded asynchronous callback dispatch loop
/// (`zks_dispatch_main`).
fn spawn_dispatch(h: H) -> JoinHandle<Delivered> {
    zkrs_handle_retain(h.get());
    zkrs_thread_enter(h.get(), ZKRS_ROLE_DISPATCH);
    thread::spawn(move || {
        let mut got = Delivered::new();
        loop {
            let mut ob = empty_outbox();
            let mut rc: c_int = -1;
            let first = zkrs_wait_dispatch(h.get(), &mut ob, &mut rc);
            if drive(h, first, &mut ob, &mut rc, &mut got, &mut |_| {}) == zkrs_step::ZKRS_EXIT {
                break;
            }
        }
        // Because the closing thread joins this worker, final handle teardown never runs here.
        assert_eq!(leave(h, ZKRS_ROLE_DISPATCH, &mut got), zkrs_step::ZKRS_DONE);
        got
    })
}

/// Spawns a Loom worker simulating the multithreaded network I/O loop (`zks_io_main`) exiting upon
/// session termination.
fn spawn_io(h: H) -> JoinHandle<Delivered> {
    zkrs_handle_retain(h.get());
    zkrs_thread_enter(h.get(), ZKRS_ROLE_IO);
    thread::spawn(move || {
        let mut got = Delivered::new();
        let _ = leave(h, ZKRS_ROLE_IO, &mut got);
        got
    })
}

/// Asserts that every submitted token underwent exactly one terminal transition (either rejected or
/// delivered).
fn check_once(tokens: &[usize], refused: &[usize], got: &Delivered) {
    let discarded = standin::discarded();
    assert!(discarded.is_empty(), "discarded {discarded:?}, got {got:?}");
    for t in tokens {
        let delivered = got.get(t).map_or(0, Vec::len);
        let back = usize::from(refused.contains(t));
        assert_eq!(
            delivered + back,
            1,
            "token {t}: got {got:?}, refused {refused:?}"
        );
    }
}

/// Verifies that closing a session from an external application thread delivers pending requests
/// with `ZCLOSING` exactly once.
#[test]
fn loom_abi_foreign_close_delivers_each_token_once() {
    model(|| {
        let _rec = standin::recorder();
        let h = ready();
        assert_eq!(submit(h, 41), zkrs_status::ZKRS_OK);
        assert_eq!(submit(h, 42), zkrs_status::ZKRS_OK);
        let mut dispatch = Some(spawn_dispatch(h));
        let mut io = Some(spawn_io(h));
        let mut got = Delivered::new();
        let mut on_threads = Vec::new();
        let mut ob = empty_outbox();
        let mut rc: c_int = -1;
        let first = zkrs_close(h.get(), ZKRS_ROLE_FOREIGN, &mut ob, &mut rc);
        let end = drive(h, first, &mut ob, &mut rc, &mut got, &mut |s| match s {
            Seen::Thread {
                op: JOIN,
                role: ZKRS_ROLE_IO,
            } => {
                on_threads.push(io.take().unwrap().join().unwrap());
            }
            Seen::Thread {
                op: JOIN,
                role: ZKRS_ROLE_DISPATCH,
            } => on_threads.push(dispatch.take().unwrap().join().unwrap()),
            Seen::Thread { .. } => panic!("a foreign close detaches no thread"),
            _ => {}
        });
        assert_eq!((end, rc), (zkrs_step::ZKRS_RELEASE, 0));
        zkrs_handle_release(h.get());
        assert!(io.is_none() && dispatch.is_none());
        assert!(got.is_empty(), "the closer delivered {got:?}");
        let [on_io, on_dispatch] = <[Delivered; 2]>::try_from(on_threads).unwrap();
        assert!(on_io.is_empty(), "{on_io:?}");
        let want: Delivered = [(41, vec![ZCLOSING]), (42, vec![ZCLOSING])].into();
        assert_eq!(on_dispatch, want);
        check_once(&[41, 42], &[], &on_dispatch);
    });
}

/// Spawns a dispatch worker simulating abnormal thread termination (e.g. `pthread_exit`) within an
/// application callback.
fn spawn_ending_dispatch(h: H) -> JoinHandle<Option<usize>> {
    zkrs_handle_retain(h.get());
    zkrs_thread_enter(h.get(), ZKRS_ROLE_DISPATCH);
    thread::spawn(move || {
        let mut ob = empty_outbox();
        let mut rc: c_int = -1;
        let first = zkrs_wait_dispatch(h.get(), &mut ob, &mut rc);
        assert_eq!(first, zkrs_step::ZKRS_DONE);
        let mut it = zkrs_item::empty();
        let taken = match zkrs_outbox_next(&mut ob, &mut it) {
            1 => match seen(&it) {
                Seen::Result { token, .. } => Some(token),
                _ => None,
            },
            _ => None,
        };
        zkrs_dispatch_ended(h.get(), &mut ob);
        zkrs_handle_release(h.get());
        taken
    })
}

/// Verifies that abnormal dispatch thread termination unblocks concurrent session close operations
/// without deadlocking.
#[test]
fn loom_abi_dispatch_end_wakes_a_foreign_close() {
    model(|| {
        let _rec = standin::recorder();
        let h = ready();
        assert_eq!(submit(h, 41), zkrs_status::ZKRS_OK);
        assert_eq!(submit(h, 42), zkrs_status::ZKRS_OK);
        let mut dispatch = Some(spawn_ending_dispatch(h));
        let mut io = Some(spawn_io(h));
        let mut taken = None;
        let mut got = Delivered::new();
        let mut ob = empty_outbox();
        let mut rc: c_int = -1;
        let first = zkrs_close(h.get(), ZKRS_ROLE_FOREIGN, &mut ob, &mut rc);
        let end = drive(h, first, &mut ob, &mut rc, &mut got, &mut |s| match s {
            Seen::Thread {
                op: JOIN,
                role: ZKRS_ROLE_IO,
            } => {
                let _ = io.take().unwrap().join().unwrap();
            }
            Seen::Thread {
                op: JOIN,
                role: ZKRS_ROLE_DISPATCH,
            } => taken = dispatch.take().unwrap().join().unwrap(),
            Seen::Thread { .. } => panic!("a foreign close detaches no thread"),
            _ => {}
        });
        assert_eq!((end, rc), (zkrs_step::ZKRS_RELEASE, 0));
        zkrs_handle_release(h.get());
        assert!(io.is_none() && dispatch.is_none());
        assert!(got.is_empty(), "the closer delivered {got:?}");
        let taken = taken.expect("the dispatch thread took a result");
        let other = if taken == 41 { 42 } else { 41 };
        assert_eq!(standin::discarded(), [other]);
    });
}

/// Verifies that session closure initiated from an I/O thread callback racing with concurrent
/// application submissions ensures exactly-once resolution for all active tokens.
#[test]
fn loom_abi_io_callback_close_racing_a_submit_ends_each_token_once() {
    model(|| {
        let _rec = standin::recorder();
        let h = ready();
        assert_eq!(submit(h, 41), zkrs_status::ZKRS_OK);
        // The calling thread represents the network I/O worker, holding both the session handle and
        // worker thread references.
        zkrs_handle_retain(h.get());
        zkrs_thread_enter(h.get(), ZKRS_ROLE_IO);
        let dispatch = Arc::new(Mutex::new(Some(spawn_dispatch(h))));
        let submitter = thread::spawn(move || submit(h, 43));
        let mut got = Delivered::new();
        let mut on_dispatch = None;
        let mut items = Vec::new();
        let mut ob = empty_outbox();
        let mut rc: c_int = -1;
        let first = zkrs_close(h.get(), ZKRS_ROLE_IO, &mut ob, &mut rc);
        let end = drive(h, first, &mut ob, &mut rc, &mut got, &mut |s| match s {
            Seen::Thread {
                op: JOIN,
                role: ZKRS_ROLE_DISPATCH,
            } => {
                let d = dispatch.lock_owner().take().unwrap();
                on_dispatch = Some(d.join().unwrap());
                items.push("join dispatch");
            }
            Seen::Thread {
                op: DETACH,
                role: ZKRS_ROLE_IO,
            } => items.push("detach io"),
            Seen::Thread { .. } => panic!("an unexpected thread item"),
            _ => {}
        });
        assert_eq!((end, rc), (zkrs_step::ZKRS_DONE, 0));
        assert_eq!(items, ["detach io", "join dispatch"]);
        // Ensure the concurrent application submission completes before session deallocation
        // progresses.
        let st = submitter.join().unwrap();
        // The I/O worker terminates its event loop and executes final session release.
        assert_eq!(leave(h, ZKRS_ROLE_IO, &mut got), zkrs_step::ZKRS_RELEASE);
        let on_dispatch = on_dispatch.expect("the close joined the dispatch thread");
        for (t, rcs) in &on_dispatch {
            got.entry(*t).or_default().extend_from_slice(rcs);
        }
        let refused: &[usize] = if st == zkrs_status::ZKRS_OK {
            &[]
        } else {
            &[43]
        };
        check_once(&[41, 43], refused, &got);
        assert_eq!(on_dispatch.get(&41), Some(&vec![ZCLOSING]));
    });
}
