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

//! Panic freedom and token conservation under arbitrary driver inputs.
//!
//! Drives the public `Connection` API with randomized call sequences, arbitrary
//! byte streams, stale resolution/SASL generations, injected transport failures,
//! and clock rewinds interleaved with normal harness execution. Verifies that
//! the state machine never panics and every submitted token is returned
//! exactly once.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod props_common;

use props_common::{OpKind, PATHS, Profile, check_once, config, make_op, op_kind, profile};
use proptest::prelude::*;
use zk_jute::{JString, rc};
use zk_proto::{
    ConnectStart, Endpoint, HostResolution, HostToken, InputDefect, Millis, ProbeOutcome,
    ProbeTlsFailure, ResolveError, Route, SaslAddr, SaslError, SaslStep, SecretBytes, Stopped,
    TlsConfigStep, TlsResourceAt, TransportFailure,
};
use zk_testkit::{Event, Harness, HarnessError, WatcherId};

#[derive(Clone, Debug)]
enum Chaos {
    /// Advance normal harness execution by the given duration in milliseconds.
    Run(u64),
    /// Feed arbitrary bytes to `handle_read`.
    Read(Vec<u8>),
    Fail(TransportFailure),
    ForcedClose,
    RunPending,
    BeginInterest,
    PollAction,
    FinishInterest,
    PollTransmit(usize),
    Transmitted(usize),
    Submit {
        kind: OpKind,
        path: usize,
        watch: bool,
        direct: bool,
        defect: bool,
    },
    AddAuth(bool),
    ConnectStarted(bool),
    TcpConnected,
    TransportReady,
    HostResolved {
        gen_delta: i8,
        host: usize,
        ok: bool,
    },
    Resolved {
        gen_delta: i8,
        shuffle: bool,
    },
    SetServers(Vec<usize>),
    SetResolveDelay(i32),
    CycleServer,
    Probe(u8),
    SaslStarted {
        gen_delta: i8,
        ok: bool,
        done: bool,
        token: Option<Vec<u8>>,
    },
    SaslStepped {
        gen_delta: i8,
        ok: bool,
        done: bool,
        token: Option<Vec<u8>>,
    },
    SaslAddrFailed(i8),
    MarkSaslFailed,
    /// Drain outputs; either retain barrier `Stopped` handles or resume them immediately.
    Pump(bool),
    /// Resume a previously retained `Stopped` handle.
    ResumeHeld(usize),
    BeginClose,
    QueueCloseOp,
    CloseStep,
    Abandon,
    Destroy,
    DetachWatcher,
    Hints,
    /// Offset subsequent timestamps backward by the given milliseconds.
    Rewind(u64),
}

fn failure() -> impl Strategy<Value = TransportFailure> {
    let errno = prop_oneof![Just(0i32), Just(104), Just(111), Just(-1), any::<i32>()];
    let at = prop_oneof![
        Just(TlsResourceAt::ContextNew),
        Just(TlsResourceAt::ConnectionNew)
    ];
    let step = prop_oneof![
        Just(TlsConfigStep::CaFile),
        Just(TlsConfigStep::DefaultVerifyPaths),
        Just(TlsConfigStep::ClientCertChain),
        Just(TlsConfigStep::ClientKey),
        Just(TlsConfigStep::CheckPrivateKey),
    ];
    (0u8..8, errno, at, step).prop_map(|(k, errno, at, step)| match k {
        0 => TransportFailure::SocketCreate { errno },
        1 => TransportFailure::TcpRefused { errno },
        2 => TransportFailure::Io { errno },
        3 => TransportFailure::Eof,
        4 => TransportFailure::TlsResource { at, errno },
        5 => TransportFailure::TlsHandshake { errno },
        6 => TransportFailure::TlsConfig {
            step,
            file: JString::from("ca.pem"),
        },
        _ => TransportFailure::Poll { errno },
    })
}

fn bytes(max: usize) -> impl Strategy<Value = Vec<u8>> {
    proptest::collection::vec(any::<u8>(), 0..max)
}

fn chaos() -> impl Strategy<Value = Chaos> {
    prop_oneof![
        6 => (0u64..4_000).prop_map(Chaos::Run),
        4 => bytes(48).prop_map(Chaos::Read),
        2 => failure().prop_map(Chaos::Fail),
        1 => Just(Chaos::ForcedClose),
        2 => Just(Chaos::RunPending),
        1 => Just(Chaos::BeginInterest),
        2 => Just(Chaos::PollAction),
        1 => Just(Chaos::FinishInterest),
        1 => (0usize..64).prop_map(Chaos::PollTransmit),
        1 => (0usize..128).prop_map(Chaos::Transmitted),
        4 => (op_kind(), 0..PATHS.len(), any::<bool>(), any::<bool>(), proptest::bool::weighted(0.1))
            .prop_map(|(kind, path, watch, direct, defect)| Chaos::Submit {
                kind,
                path,
                watch,
                direct,
                defect
            }),
        1 => any::<bool>().prop_map(Chaos::AddAuth),
        1 => any::<bool>().prop_map(Chaos::ConnectStarted),
        1 => Just(Chaos::TcpConnected),
        1 => Just(Chaos::TransportReady),
        1 => (any::<i8>(), 0usize..4, any::<bool>())
            .prop_map(|(gen_delta, host, ok)| Chaos::HostResolved { gen_delta, host, ok }),
        1 => (any::<i8>(), any::<bool>())
            .prop_map(|(gen_delta, shuffle)| Chaos::Resolved { gen_delta, shuffle }),
        1 => proptest::collection::vec(0usize..3, 0..4).prop_map(Chaos::SetServers),
        1 => prop_oneof![Just(-1i32), Just(0), Just(50), any::<i32>()].prop_map(Chaos::SetResolveDelay),
        1 => Just(Chaos::CycleServer),
        1 => any::<u8>().prop_map(Chaos::Probe),
        1 => (any::<i8>(), any::<bool>(), any::<bool>(), proptest::option::of(bytes(8)))
            .prop_map(|(gen_delta, ok, done, token)| Chaos::SaslStarted { gen_delta, ok, done, token }),
        1 => (any::<i8>(), any::<bool>(), any::<bool>(), proptest::option::of(bytes(8)))
            .prop_map(|(gen_delta, ok, done, token)| Chaos::SaslStepped { gen_delta, ok, done, token }),
        1 => any::<i8>().prop_map(Chaos::SaslAddrFailed),
        1 => Just(Chaos::MarkSaslFailed),
        4 => any::<bool>().prop_map(Chaos::Pump),
        2 => (0usize..4).prop_map(Chaos::ResumeHeld),
        1 => Just(Chaos::BeginClose),
        1 => Just(Chaos::QueueCloseOp),
        1 => Just(Chaos::CloseStep),
        1 => prop_oneof![9 => Just(Chaos::Hints), 1 => Just(Chaos::Abandon)],
        1 => prop_oneof![9 => Just(Chaos::DetachWatcher), 1 => Just(Chaos::Destroy)],
        1 => (0u64..5_000).prop_map(Chaos::Rewind),
    ]
}

/// Chaos driver state tracking retained suspension handles and observed generations.
struct Driver {
    held: Vec<Stopped>,
    /// Highest DNS resolution generation observed in an emitted action.
    resolve_gen: u64,
    /// Highest SASL generation observed in an emitted action.
    sasl_gen: u32,
    rewind: u64,
    closing: bool,
    destroyed: bool,
}

fn shift_u64(base: u64, d: i8) -> u64 {
    if d >= 0 {
        base.saturating_add(u64::from(d.unsigned_abs() % 3))
    } else {
        base.saturating_sub(u64::from(d.unsigned_abs() % 3))
    }
}

fn shift_u32(base: u32, d: i8) -> u32 {
    u32::try_from(shift_u64(u64::from(base), d)).unwrap_or(u32::MAX)
}

fn note_action(d: &mut Driver, a: &zk_proto::Action) {
    match a {
        zk_proto::Action::Resolve { generation, .. } => {
            d.resolve_gen = d.resolve_gen.max(*generation);
        }
        zk_proto::Action::Sasl(c) => d.sasl_gen = d.sasl_gen.max(c.generation),
        _ => {}
    }
}

fn mech_result(ok: bool, done: bool, token: &Option<Vec<u8>>) -> Result<SaslStep, SaslError> {
    if ok {
        Ok(SaslStep {
            token: token.clone().map(SecretBytes::new),
            done,
        })
    } else {
        Err(SaslError {
            rc: rc::ZSYSTEMERROR,
            errstring: b"chaos".to_vec(),
            errdetail: Vec::new(),
        })
    }
}

fn apply(h: &mut Harness, p: Profile, d: &mut Driver, c: &Chaos) -> Result<(), TestCaseError> {
    let now = Millis(h.now().0.saturating_sub(d.rewind));
    match c {
        Chaos::Run(ms) => {
            // Only advance the harness when no barrier handles are retained and
            // `destroy`/`abandon` has not been called.
            if d.held.is_empty() && !d.destroyed {
                if let Err(HarnessError::Runaway { steps }) = h.run_for(*ms) {
                    return Err(TestCaseError::fail(format!("runaway {steps}")));
                }
            }
        }
        Chaos::Read(b) => {
            let o = h.conn_mut().handle_read(b, now);
            prop_assert!(
                o.consumed <= b.len(),
                "consumed {} of {}",
                o.consumed,
                b.len()
            );
        }
        Chaos::Fail(f) => {
            let _ = h.conn_mut().handle_transport_failed(f.clone(), now);
        }
        Chaos::ForcedClose => h.conn_mut().handle_forced_close(now),
        Chaos::RunPending => {
            let _ = h.conn_mut().run_pending_teardown(now);
        }
        Chaos::BeginInterest => {
            let _ = h.conn_mut().begin_interest(now);
        }
        Chaos::PollAction => {
            if let Some(a) = h.conn_mut().poll_action(now) {
                note_action(d, &a);
                h.recorder_mut()
                    .push(now, Event::Note(format!("dropped {a:?}")));
            }
        }
        Chaos::FinishInterest => {
            let _ = h.conn_mut().finish_interest(now);
        }
        Chaos::PollTransmit(max) => {
            let mut buf = Vec::new();
            let n = h.conn_mut().poll_transmit(&mut buf, *max);
            // `poll_transmit` appends at most `max` bytes and returns the exact count appended.
            prop_assert!(n <= *max, "copied {} of max {}", n, max);
            prop_assert_eq!(n, buf.len());
        }
        Chaos::Transmitted(n) => h.conn_mut().handle_transmitted(*n, now),
        Chaos::Submit {
            kind,
            path,
            watch,
            direct,
            defect,
        } => {
            let ops = h.ops();
            let op = make_op(&ops, *kind, *path, *watch);
            let route = if *direct && p == Profile::Mt {
                Route::Direct
            } else {
                Route::Queued
            };
            let mut def = InputDefect::default();
            if *defect {
                def.bad_acl = true;
            }
            if d.held.is_empty() {
                let _ = h.submit_with(op, route, def);
            }
        }
        Chaos::AddAuth(bad) => {
            if d.held.is_empty() {
                let _ = h.add_auth(if *bad { "" } else { "digest" }, b"a:b");
            }
        }
        Chaos::ConnectStarted(imm) => {
            let k = if *imm {
                ConnectStart::Immediate
            } else {
                ConnectStart::InProgress
            };
            h.conn_mut().handle_connect_started(k, now);
        }
        Chaos::TcpConnected => h.conn_mut().handle_tcp_connected(now),
        Chaos::TransportReady => h.conn_mut().handle_transport_ready(now),
        Chaos::HostResolved {
            gen_delta,
            host,
            ok,
        } => {
            let generation = shift_u64(d.resolve_gen, *gen_delta);
            let result = if *ok {
                Ok(vec![zk_testkit::server_addr(*host)])
            } else {
                Err(ResolveError {
                    gai_rc: -2,
                    errno: 0,
                })
            };
            let r = HostResolution {
                host: *host,
                result,
                skipped_families: Vec::new(),
            };
            h.conn_mut().handle_host_resolved(generation, r, now);
        }
        Chaos::Resolved { gen_delta, shuffle } => {
            let generation = shift_u64(d.resolve_gen, *gen_delta);
            let _ = h.conn_mut().handle_resolved(generation, *shuffle, now);
        }
        Chaos::SetServers(list) => {
            let hosts = list.iter().map(|i| h.host_spec(*i)).collect();
            let g = h.conn_mut().set_servers(hosts, now);
            d.resolve_gen = d.resolve_gen.max(g);
        }
        Chaos::SetResolveDelay(ms) => {
            let _ = h.conn_mut().set_resolve_delay(*ms);
        }
        Chaos::CycleServer => h.conn_mut().cycle_next_server(),
        Chaos::Probe(k) => {
            let ep = Endpoint {
                addr: zk_testkit::server_addr(usize::from(*k % 3)),
                host: HostToken {
                    index: usize::from(*k % 4),
                    name: b"127.0.0.1".to_vec(),
                },
            };
            let outcome = match k % 4 {
                0 => ProbeOutcome::ReadWrite,
                1 => ProbeOutcome::NotReadWrite,
                2 => ProbeOutcome::Failed(None),
                _ => ProbeOutcome::Failed(Some(ProbeTlsFailure::Handshake)),
            };
            h.conn_mut().handle_rw_probe(ep, outcome, now);
        }
        Chaos::SaslStarted {
            gen_delta,
            ok,
            done,
            token,
        } => {
            let g = shift_u32(d.sasl_gen, *gen_delta);
            let r = mech_result(*ok, *done, token).map(|s| ("DIGEST-MD5".to_owned(), s));
            let _ = h.conn_mut().handle_sasl_started(g, r, now);
        }
        Chaos::SaslStepped {
            gen_delta,
            ok,
            done,
            token,
        } => {
            let g = shift_u32(d.sasl_gen, *gen_delta);
            let _ = h
                .conn_mut()
                .handle_sasl_stepped(g, mech_result(*ok, *done, token), now);
        }
        Chaos::SaslAddrFailed(gd) => {
            let g = shift_u32(d.sasl_gen, *gd);
            let _ = h.conn_mut().handle_sasl_addr_failed(g, SaslAddr::Peer, now);
        }
        Chaos::MarkSaslFailed => h.conn_mut().mark_sasl_failed(),
        Chaos::Pump(hold) => pump(h, d, *hold, now),
        Chaos::ResumeHeld(i) => {
            if !d.held.is_empty() {
                let s = d.held.remove(*i % d.held.len());
                h.conn_mut().resume(s, now);
            }
        }
        Chaos::BeginClose => h.conn_mut().begin_close(now),
        Chaos::QueueCloseOp => {
            let _ = h.conn_mut().queue_close_op();
        }
        Chaos::CloseStep => {
            let _ = h.conn_mut().close_step(now);
        }
        Chaos::Abandon => {
            d.destroyed = true;
            h.conn_mut().abandon(now);
        }
        Chaos::Destroy => {
            d.destroyed = true;
            h.conn_mut().destroy(now);
        }
        Chaos::DetachWatcher => {
            let _ = h.conn_mut().detach_watcher(&WatcherId::new(1, 0));
            let _ = h.conn().has_watcher(b"/a", 1, None);
        }
        Chaos::Hints => {
            let _ = h.conn_mut().take_errno_hint();
            let _ = h.conn().read_hint();
            let _ = h.conn().recv_timeout();
            let _ = h.conn().current_server();
            let _ = h.conn().pending_len();
        }
        Chaos::Rewind(ms) => d.rewind = *ms,
    }
    // `is_closing` is monotonic once set (spec "Close").
    let closing = h.conn().is_closing();
    prop_assert!(closing || !d.closing, "is_closing went back to false");
    d.closing = closing;
    Ok(())
}

/// Drains pending outputs. When `hold` is set, retains any barrier `Stopped`
/// handle so the state machine remains suspended until a later `ResumeHeld`.
fn pump(h: &mut Harness, d: &mut Driver, hold: bool, now: Millis) {
    for _ in 0..10_000 {
        let Some(o) = h.conn_mut().poll_output() else {
            break;
        };
        let barrier = o.is_barrier();
        h.recorder_mut().push(now, Event::Output(o));
        if barrier {
            if let Some(s) = h.conn_mut().take_stopped() {
                if hold {
                    d.held.push(s);
                    break;
                }
                h.conn_mut().resume(s, now);
            }
        }
    }
}

fn run(
    p: Profile,
    seed: u64,
    sasl: bool,
    script: &[Chaos],
    end_resume: bool,
) -> Result<(), TestCaseError> {
    // Configure a 100 ms reconnect delay under the native profile so full-pass
    // host cycling without a live server backs off instead of spinning.
    let mut h = p
        .builder_with(seed, |c| {
            c.reconnect_delay_ms = 100;
            // Enable SASL when requested so scripted mechanism callbacks can hit live handshakes.
            c.sasl = sasl;
        })
        .build();
    let mut d = Driver {
        held: Vec::new(),
        resolve_gen: 0,
        sasl_gen: 0,
        rewind: 0,
        closing: false,
        destroyed: false,
    };
    // Pre-connect half of the test cases so fuzz inputs exercise the connected state.
    if seed % 2 == 0 {
        let _ = h.run_until(|h| h.state().is_connected(), 30_000);
    }
    for c in script {
        apply(&mut h, p, &mut d, c)?;
    }
    if end_resume {
        let now = h.now();
        for s in d.held.drain(..) {
            h.conn_mut().resume(s, now);
        }
        pump(&mut h, &mut d, false, now);
        // `resume` is a no-op after `destroy`; verify that a non-destroyed instance
        // is no longer suspended once all held handles are resumed.
        if !d.destroyed && h.conn().is_suspended() {
            return Err(TestCaseError::fail("suspended after every resume"));
        }
        if !d.destroyed {
            let _ = h.close();
        }
    }
    // Dropping unresumed `Stopped` handles returns their tokens via `into_leftovers`.
    drop(d);
    let f = h.finish();
    check_once(&f)
}

proptest! {
    #![proptest_config(config(0x5eed_0006))]

    // Invariant: the protocol state machine never panics under any API call
    // sequence or wire input, and every token is yielded exactly once.
    #[test]
    fn chaos_driver_never_panics(
        p in profile(),
        seed in any::<u64>(),
        sasl in proptest::bool::weighted(0.3),
        script in proptest::collection::vec(chaos(), 1..48),
        end_resume in any::<bool>(),
    ) {
        run(p, seed, sasl, &script, end_resume)?;
    }
}
