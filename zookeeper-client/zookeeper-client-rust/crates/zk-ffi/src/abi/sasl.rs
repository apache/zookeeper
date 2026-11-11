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

//! SASL authentication bridge integrating external Cyrus SASL negotiation with the driver.
//!
//! Cyrus SASL negotiation runs within the C runtime layer rather than Rust. The Rust driver emits
//! negotiation requests as `ZKRS_ITEM_SASL` items into the outbox, and the outbox draining loop
//! hands tokens over to C. The C layer performs Cyrus SASL client steps and returns the results via
//! [`zkrs_sasl_result`]. This export validates the memory views, maps Cyrus negotiation statuses
//! into high-level [`SaslAnswer`] types, and routes answers back into the runtime driver. Responses
//! tagged with mismatched or obsolete generation counters are safely discarded.
//!
//! For compatibility, [`zkrs_sasl_mark_failed`] mirrors legacy `zoo_sasl_mark_failed`, setting the
//! internal authentication state to failed without generating user events or initiating transport
//! teardown.
//!
//! All SASL exports remain present across all compilation configurations to preserve a unified C
//! ABI header (`zkrs.h`). In builds compiled without SASL support (`--no-default-features`), no
//! SASL outbox items are emitted, and attempts to initialize SASL sessions fail with `EINVAL`.
//!
//! Outstanding SASL operations (`START` or `STEP`) taken by C are tracked in an internal tracking
//! table. When a connection teardown occurs (e.g. transport disconnect or socket timeout) while C
//! holds an unanswered SASL request, the driver marks the request generation as stale and unblocks
//! the runtime state machine, allowing subsequent connection cycles to dispatch fresh negotiation
//! tokens.

use std::ffi::c_int;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError, Weak};

use zk_driver::SaslAnswer;
use zk_proto::{Cause, Config, SaslError, SaslStep, State, rc};

use super::entry::with_handle;
use super::handle::{Driver, Inner};
use super::raw;
use super::types::{zkrs_handle, zkrs_init_args, zkrs_sasl_out, zkrs_sasl_status};

/// Configures SASL negotiation flags during handle initialization.
///
/// If `a->sasl` is set to zero, initialization proceeds without SASL. Otherwise, enables SASL
/// within the configuration so that connection progression pauses for SASL authentication.
/// If compiled without SASL support, returns `EINVAL`.
pub(crate) fn setup(a: &zkrs_init_args, cfg: &mut Config) -> Result<(), c_int> {
    if a.sasl == 0 {
        return Ok(());
    }
    enable(cfg)
}

/// Enables SASL negotiation within the driver configuration.
#[cfg(feature = "sasl")]
#[allow(clippy::unnecessary_wraps, reason = "the build without SASL fails")]
fn enable(cfg: &mut Config) -> Result<(), c_int> {
    cfg.sasl = true;
    Ok(())
}

/// Rejects SASL activation when compiled without the `sasl` feature.
#[cfg(not(feature = "sasl"))]
fn enable(_cfg: &mut Config) -> Result<(), c_int> {
    Err(libc::EINVAL)
}

/// Categorizes pending SASL requests dispatched to the C Cyrus layer.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Taken {
    /// Initial mechanism selection and client first-message generation (`START`).
    Start,
    /// Challenge-response exchange iteration (`STEP`).
    Step,
}

/// Snapshot of connection and transport state used to detect connection resets across SASL cycles.
///
/// Captures active transport presence, session state, and termination diagnostics. If any of these
/// attributes change while a SASL step is in flight with C, the negotiation epoch is invalidated.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Mark {
    /// Indicates whether an active network transport exists.
    transport: bool,
    /// Current session lifecycle state.
    state: State,
    /// Diagnostic cause for the most recent connection termination, if any.
    cause: Option<Cause>,
    /// System errno associated with the most recent connection termination, if any.
    errno: Option<i32>,
}

/// In-flight SASL item currently being processed by the external C Cyrus layer.
struct Record {
    /// Weak reference to the associated handle, preventing circular retention while retaining
    /// address uniqueness.
    handle: Weak<Inner>,
    /// Distinguishes between initial start and subsequent challenge steps.
    kind: Taken,
    /// Unique generation epoch counter for the in-flight SASL item.
    generation: u32,
    /// Connection state snapshot recorded at the time C acquired the item.
    mark: Option<Mark>,
}

impl Record {
    /// Returns true if this record belongs to the specified handle instance.
    fn is_for(&self, h: &Arc<Inner>) -> bool {
        std::ptr::eq(self.handle.as_ptr(), Arc::as_ptr(h))
    }
}

/// Thread-safe registry tracking in-flight SASL requests across all active handles.
static TAKEN: Mutex<Vec<Record>> = Mutex::new(Vec::new());

/// Fast atomic count of active SASL records, allowing driver polling loops to bypass lock
/// acquisition when empty.
static N_TAKEN: AtomicUsize = AtomicUsize::new(0);

/// Acquires the global SASL tracking table mutex, recovering from poison conditions.
fn table() -> MutexGuard<'static, Vec<Record>> {
    TAKEN.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Prunes entries corresponding to finalized handles and updates the atomic counter.
fn prune(t: &mut Vec<Record>) {
    t.retain(|r| r.handle.strong_count() > 0);
    N_TAKEN.store(t.len(), Ordering::Release);
}

/// Captures a snapshot of the current connection state from the driver.
fn mark_of(d: &Driver) -> Option<Mark> {
    d.read_conn(|c| Mark {
        transport: c.has_transport(),
        state: c.state(),
        cause: c.last_teardown_cause(),
        errno: c.last_teardown_errno(),
    })
}

/// Registers an in-flight SASL request taken from the outbox by the C draining thread.
///
/// Records the item kind, generation counter, and connection snapshot. Replaces any prior record
/// for the same handle since ZooKeeper enforces at most one pending SASL request per session.
pub(crate) fn note_taken(h: &Arc<Inner>, kind: Taken, generation: u32) {
    // Capture connection state prior to locking the registry table to avoid lock-order inversion.
    let mark = h.driver().and_then(mark_of);
    let mut t = table();
    t.retain(|r| !r.is_for(h));
    t.push(Record {
        handle: Arc::downgrade(h),
        kind,
        generation,
        mark,
    });
    prune(&mut t);
}

/// Removes and returns the in-flight record matching the specified handle and generation counter.
fn take_answered(h: &Arc<Inner>, generation: u32) -> Option<Taken> {
    let mut t = table();
    let at = t
        .iter()
        .position(|r| r.is_for(h) && r.generation == generation);
    let kind = at.map(|n| t.swap_remove(n).kind);
    prune(&mut t);
    kind
}

/// Evaluates whether connection state changed between snapshot creation and evaluation, indicating
/// a teardown.
fn torn_down(then: Option<Mark>, now: Option<Mark>) -> bool {
    match (then, now) {
        (Some(a), Some(b)) => a != b,
        // Complete connection loss indicates handle teardown; pending waits are terminated.
        (Some(_), None) => true,
        (None, _) => false,
    }
}

/// Resolves abandoned in-flight SASL requests whose underlying connections have been torn down.
///
/// Invoked by driving routines before and after driver executions. If connection state changes
/// indicate that the underlying transport dropped while C held an unreturned SASL item, synthesizes
/// a failure answer for the expired generation to unblock the driver state machine.
pub(crate) fn settle_unanswered(h: &Arc<Inner>) {
    if N_TAKEN.load(Ordering::Acquire) == 0 {
        return;
    }
    let Some(d) = h.driver() else {
        return;
    };
    let stale = {
        // Acquire table lock before inspecting driver connection state to maintain consistent lock
        // ordering.
        let mut t = table();
        let found = t.iter().position(|r| r.is_for(h));
        let gone = found.and_then(|n| {
            let then = t.get(n).and_then(|r| r.mark);
            torn_down(then, mark_of(d)).then(|| t.swap_remove(n))
        });
        prune(&mut t);
        gone
    };
    if let Some(r) = stale {
        give(d, unanswered(r.kind, r.generation));
    }
}

/// Constructs a synthetic failure answer for an abandoned SASL request.
fn unanswered(kind: Taken, generation: u32) -> SaslAnswer {
    let e = SaslError {
        rc: rc::ZSYSTEMERROR,
        errstring: Vec::new(),
        errdetail: Vec::new(),
    };
    match kind {
        Taken::Start => SaslAnswer::Started {
            generation,
            result: Err(e),
        },
        Taken::Step => SaslAnswer::Stepped {
            generation,
            result: Err(e),
        },
    }
}

/// Forwards a completed SASL answer to the appropriate driver instance.
fn give(d: &Driver, answer: SaslAnswer) {
    match d {
        Driver::Mt(s) => s.sasl_result(answer),
        Driver::St(p) => p.sasl_result(answer),
    }
}

/// Transforms raw Cyrus C completion outputs into a typed [`SaslAnswer`].
///
/// Unpacks authentication status flags, mechanism strings, and token buffers. Status values
/// other than `ZKRS_SASL_CONTINUE` or `ZKRS_SASL_DONE`, invalid memory views, or empty mechanism
/// names on `START` requests are mapped to `ZSYSTEMERROR`.
fn answer_of(kind: Taken, generation: u32, v: &zkrs_sasl_out) -> SaslAnswer {
    let fail = || SaslError {
        rc: rc::ZSYSTEMERROR,
        errstring: Vec::new(),
        errdetail: Vec::new(),
    };
    let done = if v.status == zkrs_sasl_status::ZKRS_SASL_DONE as i32 {
        Some(true)
    } else if v.status == zkrs_sasl_status::ZKRS_SASL_CONTINUE as i32 {
        Some(false)
    } else {
        None
    };
    let step = done.and_then(|done| {
        raw::view_bytes(&v.token)
            .ok()
            .map(|token| SaslStep { token, done })
    });
    match kind {
        Taken::Start => {
            let mech = raw::view_bytes(&v.mech)
                .ok()
                .flatten()
                .and_then(|m| String::from_utf8(m.expose().to_vec()).ok())
                .filter(|m| !m.is_empty());
            let result = match (mech, step) {
                (Some(m), Some(s)) => Ok((m, s)),
                _ => Err(fail()),
            };
            SaslAnswer::Started { generation, result }
        }
        Taken::Step => SaslAnswer::Stepped {
            generation,
            result: step.ok_or_else(fail),
        },
    }
}

/// Delivers Cyrus SASL negotiation results from the C layer to the asynchronous driver.
///
/// Invoked by C once per `START` or `STEP` item. Safely deserializes the output view and
/// resolves the matching in-flight request by generation counter. Out-of-order or stale
/// generations are safely ignored.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_sasl_result(h: *const zkrs_handle, r#gen: u32, v: *const zkrs_sasl_out) {
    with_handle(h, |i, _| {
        let out = raw::read_struct(v);
        let Some(d) = i.driver() else {
            return;
        };
        let Some(kind) = take_answered(i, r#gen) else {
            return;
        };
        give(d, answer_of(kind, r#gen, &out));
    });
}

/// Marks the handle's SASL negotiation as failed, mirroring legacy `zoo_sasl_mark_failed`.
///
/// Sets the authentication status to failed without generating watcher events or tearing down the
/// session.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_sasl_mark_failed(h: *const zkrs_handle) {
    with_handle(h, |i, _| {
        if let Some(d) = i.driver() {
            let _ = d.with_conn(|c, _| c.mark_sasl_failed());
        }
    });
}

#[cfg(test)]
mod tests {
    use super::super::handle::test_support::{init_args, new_handle};
    use super::super::handle::zkrs_handle_release;
    use super::super::standin;
    use super::super::types::zkrs_bytes;
    use super::*;

    fn args(sasl: u32) -> zkrs_init_args {
        zkrs_init_args {
            flavor: 0,
            hosts: std::ptr::null(),
            sasl,
            ..init_args(c"")
        }
    }

    fn view(b: &[u8]) -> zkrs_bytes {
        zkrs_bytes {
            p: b.as_ptr(),
            len: u32::try_from(b.len()).unwrap_or(u32::MAX),
            is_null: 0,
        }
    }

    fn out(status: i32, mech: zkrs_bytes, token: zkrs_bytes) -> zkrs_sasl_out {
        zkrs_sasl_out {
            status,
            mech,
            token,
        }
    }

    const CONTINUE: i32 = zkrs_sasl_status::ZKRS_SASL_CONTINUE as i32;
    const DONE: i32 = zkrs_sasl_status::ZKRS_SASL_DONE as i32;
    const FAIL: i32 = zkrs_sasl_status::ZKRS_SASL_FAIL as i32;
    const STALE: i32 = zkrs_sasl_status::ZKRS_SASL_STALE as i32;

    /// Tuple representing an optional token byte vector and completion flag.
    type Step = (Option<Vec<u8>>, bool);

    /// Unpacks a `Started` SASL answer into its constituent components.
    fn started(a: SaslAnswer) -> (u32, Result<(String, Step), i32>) {
        match a {
            SaslAnswer::Started { generation, result } => (
                generation,
                result
                    .map(|(m, s)| (m, (s.token.map(|t| t.expose().to_vec()), s.done)))
                    .map_err(|e| e.rc),
            ),
            SaslAnswer::Stepped { .. } => (0, Err(0)),
        }
    }

    /// Unpacks a `Stepped` SASL answer into its constituent components.
    fn stepped(a: SaslAnswer) -> (u32, Result<Step, i32>) {
        match a {
            SaslAnswer::Stepped { generation, result } => (
                generation,
                result
                    .map(|s| (s.token.map(|t| t.expose().to_vec()), s.done))
                    .map_err(|e| e.rc),
            ),
            SaslAnswer::Started { .. } => (0, Err(0)),
        }
    }

    #[test]
    fn setup_follows_the_sasl_flag_and_the_feature() {
        let mut cfg = Config::legacy(30_000);
        assert_eq!(setup(&args(0), &mut cfg), Ok(()));
        assert!(!cfg.sasl);
        let r = setup(&args(1), &mut cfg);
        if cfg!(feature = "sasl") {
            assert_eq!(r, Ok(()));
            assert!(cfg.sasl);
        } else {
            assert_eq!(r, Err(libc::EINVAL));
            assert!(!cfg.sasl);
        }
    }

    #[test]
    fn a_start_answer_has_the_mechanism_and_the_token() {
        let o = out(CONTINUE, view(b"DIGEST-MD5"), view(b"tok"));
        assert_eq!(
            started(answer_of(Taken::Start, 7, &o)),
            (
                7,
                Ok(("DIGEST-MD5".to_owned(), (Some(b"tok".to_vec()), false)))
            )
        );
        let o = out(DONE, view(b"PLAIN"), zkrs_bytes::null());
        assert_eq!(
            started(answer_of(Taken::Start, 8, &o)),
            (8, Ok(("PLAIN".to_owned(), (None, true))))
        );
        // An empty token buffer is semantically distinct from an absent (null) token.
        let o = out(CONTINUE, view(b"X"), view(b""));
        assert_eq!(
            started(answer_of(Taken::Start, 9, &o)),
            (9, Ok(("X".to_owned(), (Some(Vec::new()), false))))
        );
    }

    /// Verifies that failed Cyrus statuses and corrupted buffer views map to `ZSYSTEMERROR`.
    #[test]
    fn a_failed_status_or_a_bad_view_is_zsystemerror() {
        let sys = Err(rc::ZSYSTEMERROR);
        for status in [FAIL, STALE, 2, -3, i32::MIN] {
            let o = out(status, view(b"PLAIN"), view(b"t"));
            assert_eq!(started(answer_of(Taken::Start, 3, &o)), (3, sys.clone()));
            assert_eq!(
                stepped(answer_of(Taken::Step, 3, &o)).1,
                Err(rc::ZSYSTEMERROR)
            );
        }
        let bad = [
            // Null flag accompanied by non-zero length or non-null pointer.
            zkrs_bytes {
                p: std::ptr::null(),
                len: 1,
                is_null: 1,
            },
            zkrs_bytes {
                p: b"x".as_ptr(),
                len: 0,
                is_null: 1,
            },
            // Null pointer with positive length, or length exceeding maximum capacity bounds.
            zkrs_bytes {
                p: std::ptr::null(),
                len: 4,
                is_null: 0,
            },
            zkrs_bytes {
                p: b"x".as_ptr(),
                len: 0x8000_0000,
                is_null: 0,
            },
        ];
        for b in bad {
            let o = out(DONE, view(b"PLAIN"), b);
            assert_eq!(started(answer_of(Taken::Start, 4, &o)), (4, sys.clone()));
            assert_eq!(
                stepped(answer_of(Taken::Step, 4, &o)).1,
                Err(rc::ZSYSTEMERROR)
            );
            let o = out(DONE, b, view(b"t"));
            assert_eq!(started(answer_of(Taken::Start, 4, &o)), (4, sys.clone()));
        }
        // Initial START requests require valid UTF-8 mechanism identifiers.
        for mech in [zkrs_bytes::null(), view(b""), view(&[0xff, 0xfe])] {
            let o = out(CONTINUE, mech, view(b"t"));
            assert_eq!(started(answer_of(Taken::Start, 5, &o)), (5, sys.clone()));
        }
    }

    #[test]
    fn a_step_answer_ignores_the_mechanism() {
        let o = out(CONTINUE, zkrs_bytes::null(), view(b"resp"));
        assert_eq!(
            stepped(answer_of(Taken::Step, 2, &o)),
            (2, Ok((Some(b"resp".to_vec()), false)))
        );
        let o = out(DONE, view(b"junk"), zkrs_bytes::null());
        assert_eq!(
            stepped(answer_of(Taken::Step, 2, &o)),
            (2, Ok((None, true)))
        );
    }

    #[test]
    fn an_unanswered_item_gets_an_empty_zsystemerror() {
        let start = match unanswered(Taken::Start, 11) {
            SaslAnswer::Started {
                generation,
                result: Err(e),
            } => Some((generation, e)),
            SaslAnswer::Started { .. } | SaslAnswer::Stepped { .. } => None,
        };
        assert_eq!(
            start,
            Some((
                11,
                SaslError {
                    rc: rc::ZSYSTEMERROR,
                    errstring: Vec::new(),
                    errdetail: Vec::new(),
                }
            ))
        );
        assert_eq!(
            stepped(unanswered(Taken::Step, 12)),
            (12, Err(rc::ZSYSTEMERROR))
        );
    }

    #[test]
    fn only_a_changed_mark_is_a_teardown() {
        let m = Mark {
            transport: true,
            state: State::Associating,
            cause: None,
            errno: None,
        };
        assert!(!torn_down(Some(m), Some(m)));
        assert!(!torn_down(None, Some(m)));
        assert!(!torn_down(None, None));
        assert!(torn_down(Some(m), None));
        for other in [
            Mark {
                transport: false,
                ..m
            },
            Mark {
                state: State::Connecting,
                ..m
            },
            Mark {
                cause: Some(Cause::SaslTeardown),
                ..m
            },
            Mark {
                errno: Some(libc::ETIMEDOUT),
                ..m
            },
        ] {
            assert!(torn_down(Some(m), Some(other)));
        }
    }

    #[test]
    fn the_table_answers_each_taken_item_once() {
        let _rec = standin::recorder();
        let h1 = new_handle();
        let h2 = new_handle();
        let (k1, k2, again, other) = with_handle(h1, |a, _| {
            with_handle(h2, |b, _| {
                note_taken(a, Taken::Start, 5);
                note_taken(b, Taken::Step, 5);
                // Registering a subsequent item replaces any prior record for the same handle.
                note_taken(a, Taken::Step, 6);
                let stale = take_answered(a, 5);
                let k1 = take_answered(a, 6);
                let again = take_answered(a, 6);
                let k2 = take_answered(b, 5);
                (k1, k2, again, stale)
            })
        });
        assert_eq!(
            (k1, k2, again, other),
            (Some(Taken::Step), Some(Taken::Step), None, None)
        );
        zkrs_handle_release(h1);
        zkrs_handle_release(h2);
    }

    #[test]
    fn a_released_handle_leaves_no_record() {
        let _rec = standin::recorder();
        let h = new_handle();
        with_handle(h, |i, _| note_taken(i, Taken::Start, 1));
        zkrs_handle_release(h);
        let h2 = new_handle();
        // Pruning occurs on the subsequent table modification.
        let n = with_handle(h2, |i, _| {
            note_taken(i, Taken::Start, 1);
            let n = table()
                .iter()
                .filter(|r| r.handle.strong_count() > 0)
                .count();
            let _ = take_answered(i, 1);
            n
        });
        assert!(n >= 1);
        assert!(table().iter().all(|r| r.handle.strong_count() > 0));
        zkrs_handle_release(h2);
    }

    #[test]
    fn a_result_without_a_driver_or_a_record_changes_nothing() {
        let _rec = standin::recorder();
        let h = new_handle();
        let o = out(DONE, view(b"PLAIN"), zkrs_bytes::null());
        zkrs_sasl_result(h, 3, &raw const o);
        with_handle(h, |i, _| {
            note_taken(i, Taken::Start, 3);
            // Handles lacking drivers retain records without blocking since no event loop waits on
            // them.
            settle_unanswered(i);
            assert_eq!(take_answered(i, 3), Some(Taken::Start));
        });
        zkrs_handle_release(h);
    }
}
