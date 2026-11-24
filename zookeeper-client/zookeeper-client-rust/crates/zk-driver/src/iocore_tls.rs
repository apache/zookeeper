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

//! TLS transport integration within the core I/O event loop.
//!
//! This module bridges [`IoCore`]'s nonblocking socket reactor with the sans-I/O cryptographic
//! session abstractions defined in [`crate::net::tls`]. Encrypted sessions share the same
//! readiness polling architecture, nonblocking socket primitives, continuation checkpoints, and
//! protocol state machine phases used by plaintext connections. Decrypted application byte streams
//! and encrypted wire records pass through a [`TlsConn`] trait object that mediates between the
//! operating system socket and the higher-level `zk_proto::Connection` wire protocol engine. The
//! driver runtime retains exclusive ownership of the underlying [`TcpSock`] descriptor, completely
//! insulating both the protocol state machine and the TLS cryptographic engine from direct network
//! system calls.
//!
//! # Architectural Pillars
//!
//! - **Trait-Decoupled Compilation**: These routines are unconditionally compiled regardless of
//!   whether a concrete cryptographic provider feature is enabled. Because `iocore_tls` interacts
//!   solely with the abstract [`TlsConn`] and [`TlsConnector`] traits, unit test harnesses can
//!   exercise the entire TLS state machine, barrier continuations, and buffer reclamation logic
//!   against synthetic sessions (such as `testutil::NullTls`) in plaintext builds.
//! - **Cooperative Outbox Barriers**: Just like plaintext execution in [`IoCore`], every TLS
//!   progression step respects outbox drain barriers. Before performing blocking certificate loads
//!   or after advancing protocol connection states, if the [`Outbox`] contains pending log records
//!   or barrier items, execution saves a resumption checkpoint in [`IoCore::cont`] and yields
//!   [`Progress::More`].
//! - **Failure Latching and Plaintext Preservation**: When a peer transmits a final application
//!   response immediately followed by a TLS `close_notify` alert, a fatal alert, or a TCP `FIN`,
//!   both the application record and the termination signal may arrive in the same `recv` call.
//!   To prevent premature connection teardown from discarding valid responses, transport and
//!   cryptographic errors during the data phase are latched in [`TlsTransport::pending`]. The
//!   driver drains and dispatches all decrypted frames through [`IoCore::frame_loop`] before
//!   reporting the latched failure to `Connection::handle_transport_failed`.
//! - **Kernel-Confirmed Transmission Acknowledgments**: In plaintext mode, bytes written to the
//!   socket correspond 1:1 with protocol frame bytes. Under TLS, encrypting a plaintext chunk via
//!   [`TlsConn::write_frame`] only stages ciphertext inside the TLS engine. This module tracks the
//!   originating plaintext byte count in [`TlsTransport::unacked`] and defers notifying
//!   `Connection::handle_transmitted` until all resulting ciphertext bytes have been accepted by
//!   the operating system kernel socket buffer. This credit releases the frames but does not
//!   govern reply matching: because a single window can hold multiple requests and the server can
//!   reply to a complete request before the remainder of the window is transmitted, the protocol
//!   core marks a TLS request as written when `Connection::poll_transmit` takes the last byte of
//!   its frame.
//! - **Adaptive High-Watermark Memory Reclamation**: Transmitting multi-megabyte ZooKeeper requests
//!   expands staging and ciphertext buffers up to [`MAX_CHUNK`]. Through [`WriteBursts`], the
//!   driver monitors outbound burst sizes and automatically deallocates oversized plaintext and
//!   ciphertext buffers once traffic returns to small routine envelopes, scrubbing sensitive memory
//!   via [`Zeroizing`].

use rustix::io::Errno;
use zeroize::Zeroizing;
use zk_proto::compat::TlsVerify;
use zk_proto::rc::ZOK;
use zk_proto::{Endpoint, Millis, TlsResourceAt, Tokens, TransportFailure};

use crate::iocore::{
    Cont, FlushError, FrameEnd, IoCore, Job, JobKind, Progress, READ_BUFFER_LEN, StepCtx,
    Transport, gate_open, wait_writable,
};
use crate::net::poll::PollEvents;
use crate::net::sock::{TcpSock, errno_value};
use crate::net::tls::{
    ReadFailure, TlsConn, TlsPurpose, TlsSetupError, TlsTransport, alert_attempt, jstring,
};
use crate::outbox::Outbox;
use crate::sync::Unlocked;

/// Maximum buffer size for a single staged transmission chunk retrieved from the protocol engine.
///
/// Under TLS, frame payloads cannot be lent zero-copy to the kernel socket because they must be
/// encrypted into discrete TLS records. Instead, outbound plaintext is extracted from
/// `Connection::poll_transmit` in chunks bounded by this 256 KiB limit. Because `rustls` fragments
/// plaintext into standard TLS records of at most 16 KiB plus AEAD overhead, a 256 KiB chunk
/// produces 16 encrypted records in a single pass. This batch size bounds per-session memory
/// overhead while amortizing `core` mutex acquisitions and `send` system calls across large
/// request payloads.
pub(crate) const MAX_CHUNK: usize = 256 * 1024;

/// Initiates the TLS handshake sequence over an established TCP transport socket.
///
/// Invoked either from [`IoCore::connect`] (when a nonblocking TCP `connect` completes
/// synchronously, as on loopback interfaces) or from `IoCore::process_connecting` (when
/// asynchronous `EINPROGRESS` connection establishment signals write readiness). The originating
/// entry point is recorded in [`IoCore::hs_origin`] so post-handshake continuations return to the
/// appropriate caller loop.
///
/// Coordination flow:
/// 1. **Deadline Anchoring and Log Barrier Check**: Anchors the handshake start timestamp in
///    [`IoCore::hs_now`]. If pending outbox log entries exist (such as the `ZkConnect` log emitted
///    when initiating the TCP connection), the socket is parked in [`Transport::Connecting`] with
///    [`Cont::Handshake`] and [`Progress::More`] is returned. This guarantees that connection
///    initiation logs are flushed to the host before any certificate loading or handshake logs are
///    emitted, preserving exact log ordering parity with the legacy C client.
/// 2. **Asynchronous Offload (`DeferPolicy::new_conn`)**: In runtimes that forbid blocking file I/O
///    on the reactor thread (such as the Tokio driver), queues a [`Job::NewConn`] work item,
///    stores the socket in [`Transport::Connecting`], and yields [`Progress::Wait`] with
///    [`Cont::Job`]. Once the background worker finishes reading certificate files and building the
///    TLS session, [`IoCore::complete_job`] resumes execution at [`handshake_with`].
/// 3. **Synchronous Session Construction**: In synchronous or OS-threaded drivers, invokes
///    [`TlsConnector::new_conn`] directly on the current thread, presenting the [`Unlocked`]
///    witness token to prove no internal mutexes are held, and delegates to [`handshake_with`].
///
/// Returns [`Progress::Done(rc)`], evaluating to `ZOK` when the session remains active.
pub(crate) fn handshake_start<T: Tokens>(
    io: &mut IoCore,
    cx: &StepCtx<'_, T>,
    proof: &mut Unlocked,
    sock: TcpSock,
    ep: Endpoint,
    verify: TlsVerify,
    ob: &mut Outbox<T>,
) -> Progress {
    // Capture the timestamp on the initial entry prior to any log-drain suspension so the protocol
    // handshake deadline reflects when TCP connected rather than when log callbacks finished.
    let _origin = *io.hs_now.get_or_insert_with(|| cx.clock.now());
    if ob.has_pending_logs() {
        // Suspend before invoking `new_conn` so pending connection logs reach the host first.
        io.conn = Transport::Connecting {
            sock,
            ep,
            tls: Some(verify),
        };
        io.cont = Cont::Handshake;
        return Progress::More;
    }
    if cx.defer.new_conn {
        // Offload potentially blocking certificate chain and private key file reads to a worker.
        io.deferred.push(Job::NewConn {
            purpose: TlsPurpose::Main,
            endpoint: ep.clone(),
        });
        io.conn = Transport::Connecting {
            sock,
            ep,
            tls: Some(verify),
        };
        io.job = Some(JobKind::NewConn);
        io.cont = Cont::Job;
        return Progress::Wait;
    }
    let result = match cx.tls {
        Some(c) => c.new_conn(proof, TlsPurpose::Main, &ep),
        None => Err(TlsSetupError::Resource {
            at: TlsResourceAt::ContextNew,
        }),
    };
    handshake_with(io, cx, proof, ob, sock, result)
}

/// Completes TLS connection setup following the instantiation of a [`TlsConn`] handle.
///
/// This entry point is invoked either synchronously after [`TlsConnector::new_conn`] returns in
/// [`handshake_start`], or asynchronously from [`IoCore::complete_job`] once an offloaded
/// [`Job::NewConn`] background task finishes.
///
/// Outcome handling:
/// - **`Ok(tls)`**: Acquires the `core` lock to notify the protocol engine via
///   `Connection::handle_tcp_connected` (which starts the TLS handshake timer). After verifying
///   that the connection still has an active transport, wraps `sock` and `tls` in a
///   [`TlsTransport`], extracts the initial `ClientHello` flight into `ct_out`, and installs
///   [`Transport::Tls`]. If `settle` encounters an outbox barrier, the `ClientHello` remains staged
///   in `ct_out` and [`Progress::More`] is returned; otherwise [`pump_out`] immediately attempts a
///   nonblocking write of the `ClientHello` to the socket.
/// - **`Err(TlsSetupError::Config)`**: Emulates a subtle legacy C client behavior (`zookeeper.c`
///   `init_ssl_for_socket`). When loading a CA bundle, client certificate, or private key fails,
///   the C client logs the error and returns `ZSSLCONNECTIONERROR` from the step, *without* closing
///   the underlying TCP descriptor! Instead, the socket is left open with write interest disabled
///   until the session receive timeout expires and tears it down. Placing `sock` into
///   [`Transport::Parked`] reproduces this exact quarantine lifecycle.
/// - **`Err(TlsSetupError::Resource)`**: Cryptographic context or session allocation failure closes
///   `sock` immediately and triggers standard transport teardown via
///   `TransportFailure::TlsResource`.
pub(crate) fn handshake_with<T: Tokens>(
    io: &mut IoCore,
    cx: &StepCtx<'_, T>,
    proof: &mut Unlocked,
    ob: &mut Outbox<T>,
    sock: TcpSock,
    result: Result<Box<dyn TlsConn>, TlsSetupError>,
) -> Progress {
    let now = io.hs_now.take().unwrap_or_else(|| cx.clock.now());
    match result {
        Ok(tls) => {
            let barrier = {
                let mut g = cx.core.lock(proof);
                let has = match g.conn.as_deref_mut() {
                    Some(c) => {
                        c.handle_tcp_connected(now);
                        c.has_transport()
                    }
                    None => false,
                };
                if has {
                    let mut t = TlsTransport::new(sock, tls);
                    // Pull the initial ClientHello flight from the sans-I/O engine into `ct_out`.
                    let _hello = t.tls.take_ciphertext(&mut t.ct_out);
                    io.conn = Transport::Tls(t);
                }
                io.settle(&mut g, cx, ob)
            };
            if barrier {
                // Retain the ClientHello in the outbound ciphertext buffer; socket write interest
                // will drive transmission once the caller drains the outbox and resumes.
                return handshake_end(io, true, ZOK);
            }
            // Attempt an immediate nonblocking flush of the ClientHello flight.
            let sent = match &mut io.conn {
                Transport::Tls(t) => pump_out(t),
                _ => Ok(true),
            };
            match sent {
                Ok(_) => Progress::Done(ZOK),
                Err(e) => {
                    let f = TransportFailure::TlsHandshake {
                        errno: errno_value(e),
                    };
                    let (rc, barrier) = io.fail(cx, proof, ob, f);
                    handshake_end(io, barrier, rc)
                }
            }
        }
        Err(TlsSetupError::Config { step }) => {
            // Quarantine the raw socket in `Transport::Parked` so the descriptor remains open with
            // write interest suppressed until the protocol receive timeout fires.
            let file = jstring(cx.tls.and_then(|c| c.path_for(step)));
            io.conn = Transport::Parked(sock);
            let (rc, barrier) = io.fail(cx, proof, ob, TransportFailure::TlsConfig { step, file });
            handshake_end(io, barrier, rc)
        }
        Err(TlsSetupError::Resource { at }) => {
            drop(sock);
            let f = TransportFailure::TlsResource { at, errno: 0 };
            let (rc, barrier) = io.fail(cx, proof, ob, f);
            handshake_end(io, barrier, rc)
        }
    }
}

/// Finalizes the handshake initialization step, returning terminal completion status or yielding
/// early at [`Cont::AfterHandshake`] if an outbox barrier requires draining.
fn handshake_end(io: &mut IoCore, barrier: bool, rc: i32) -> Progress {
    if barrier {
        io.cont = Cont::AfterHandshake { rc };
        return Progress::More;
    }
    Progress::Done(rc)
}

/// Advances the nonblocking TLS handshake state machine for one readiness turn of `process_step`.
///
/// Execution sequence:
/// 1. **Inbound Ciphertext Ingestion**: If `events.read` is signaled, reads incoming ciphertext
///    from the socket into `io.rx`. A zero-byte read (`Ok(0)`) indicates premature peer closure
///    mid-handshake, which is fed to `t.tls.feed_eof()` and flagged as a handshake failure.
///    Non-empty reads are fed to `t.tls.feed(data)`.
/// 2. **Alert Transmission and Post-Handshake Coalescing**: If `t.tls.feed(data)` reports a
///    cryptographic error (such as certificate verification failure or record corruption),
///    [`alert_attempt`] makes a single best-effort nonblocking write to transmit any fatal TLS
///    alert generated by the engine. Crucially, `failed` is only asserted if
///    `t.tls.is_handshaking()` remains true. If the incoming read contained the server's final
///    handshake flight (`Finished`) coalesced with subsequent application data and a post-handshake
///    error or alert, `is_handshaking()` is now `false`; deferring the latched error allows the
///    handshake to complete and drain valid application frames before reporting the data-phase
///    error in [`tls_frames`].
/// 3. **Outbound Flight Flushing**: Flushes any pending or newly generated handshake ciphertext
///    via [`pump_out`].
/// 4. **Completion or Yield**: If any socket or handshake error occurred, tears down the transport
///    via `TransportFailure::TlsHandshake`. If the handshake is still awaiting further peer flights
///    (`t.tls.is_handshaking()`), returns `Progress::Done(ZOK)` to wait for the next poll cycle.
/// 5. **Transition to Application Data Phase**: Once `!t.tls.is_handshaking()`, locks `core` and
///    invokes `Connection::handle_transport_ready` (which queues the ZooKeeper `ConnectRequest` or
///    initiates SASL). After settling any outbox barriers (yielding at [`Cont::HandshakeReady`] if
///    needed), proceeds to [`handshake_ready`].
pub(crate) fn handshake_step<T: Tokens>(
    io: &mut IoCore,
    cx: &StepCtx<'_, T>,
    proof: &mut Unlocked,
    events: PollEvents,
    ob: &mut Outbox<T>,
) -> Progress {
    let Transport::Tls(t) = &mut io.conn else {
        return Progress::Done(ZOK);
    };
    let mut failed: Option<i32> = None;
    if events.read {
        match io.rx.recv(&t.sock, READ_BUFFER_LEN) {
            Ok(0) => {
                // Peer closed the TCP connection before completing the TLS handshake.
                let _latched = t.tls.feed_eof();
                failed = Some(0);
            }
            Ok(n) => {
                let data = io.rx.data().get(..n).unwrap_or_default();
                if t.tls.feed(data).is_err() {
                    alert_attempt(&t.sock, &mut *t.tls, &t.ct_out);
                    // Cryptographic decode failures occurring after the final flight are latched
                    // for the data phase, allowing application frames to be drained first.
                    if t.tls.is_handshaking() {
                        failed = Some(0);
                    }
                }
            }
            Err(Errno::WOULDBLOCK) => {}
            Err(e) => failed = Some(errno_value(e)),
        }
    }
    if failed.is_none() {
        // Transmit any outbound handshake flights (e.g., client certificate, ChangeCipherSpec,
        // Finished) staged by the TLS engine.
        if let Err(e) = pump_out(t) {
            failed = Some(errno_value(e));
        }
    }
    if let Some(errno) = failed {
        return io.fail_end(cx, proof, ob, TransportFailure::TlsHandshake { errno });
    }
    if t.tls.is_handshaking() {
        // Handshake remains in progress. Yield ZOK and await the next socket readiness event.
        return Progress::Done(ZOK);
    }
    // Cryptographic negotiation has completed. Notify the protocol state machine that the
    // transport is ready to exchange ZooKeeper wire frames.
    let now = cx.clock.now();
    let barrier = {
        let mut g = cx.core.lock(proof);
        if let Some(conn) = g.conn.as_deref_mut() {
            conn.handle_transport_ready(now);
        }
        io.settle(&mut g, cx, ob)
    };
    if barrier {
        io.cont = Cont::HandshakeReady;
        return Progress::More;
    }
    handshake_ready(io, cx, proof, ob)
}

/// Finalizes a newly established TLS session, flushing initial protocol frames and processing any
/// piggybacked application data that arrived in the final handshake read.
///
/// Invoked immediately after `handle_transport_ready` in [`handshake_step`], or resumed via
/// [`Cont::HandshakeReady`] after draining outbox barriers (such as initial SASL `Start` calls).
pub(crate) fn handshake_ready<T: Tokens>(
    io: &mut IoCore,
    cx: &StepCtx<'_, T>,
    proof: &mut Unlocked,
    ob: &mut Outbox<T>,
) -> Progress {
    // Encrypt and transmit the initial ZooKeeper ConnectRequest (or SASL token) queued when the
    // transport became ready.
    if let Err(e) = tls_flush(io, cx, proof, None) {
        let f = TransportFailure::Io {
            errno: errno_value(e),
        };
        return io.fail_end(cx, proof, ob, f);
    }
    // Drain any decrypted application bytes that arrived coalesced with the peer's final handshake
    // flight into `rx_pending` before entering the frame parser.
    if let Transport::Tls(t) = &mut io.conn {
        if t.pending.is_none() {
            t.pending = take_plaintext(&mut *t.tls, &mut io.rx_pending);
        }
    }
    tls_frames(io, cx, proof, ob, true)
}

/// Reads incoming ciphertext from the network socket during the active application data phase and
/// advances the decrypted frame parsing loop.
///
/// Data-phase ingress pipeline:
/// 1. **Backpressure Gating**: Evaluates [`gate_open`] under the `core` lock. When the native
///    driver's [`crate::dispatch::ReadGate`] is saturated in the `Connected` state, socket reads
///    are suppressed to exert TCP-level backpressure on the server.
/// 2. **Ciphertext Read and Error Latching**: If `events.read` is set, no prior failure is latched
///    in `t.pending`, and the read gate is open, reads up to 64 KiB (`READ_BUFFER_LEN`) of
///    ciphertext into `io.rx`. Socket EOF (`Ok(0)`) is fed into `t.tls.feed_eof()`, while
///    ciphertext bytes are fed into `t.tls.feed(data)` (with [`alert_attempt`] on decode failure).
///    Any OS socket error is latched directly into `t.pending` as [`ReadFailure::Io`].
/// 3. **Plaintext Extraction**: Calls [`take_plaintext`] to append all newly decrypted bytes from
///    `t.tls` into `io.rx_pending`, capturing any terminal [`ReadFailure`] once the TLS engine's
///    decrypted buffer is exhausted.
/// 4. **Frame Dispatch**: Delegates to [`tls_frames`] to parse and dispatch complete ZooKeeper
///    frames from `io.rx_pending` before acting on any latched `t.pending` failure.
pub(crate) fn tls_read<T: Tokens>(
    io: &mut IoCore,
    cx: &StepCtx<'_, T>,
    proof: &mut Unlocked,
    events: PollEvents,
    ob: &mut Outbox<T>,
) -> Progress {
    let gate = {
        let g = cx.core.lock(proof);
        g.conn.as_deref().is_some_and(|c| gate_open(cx, c.state()))
    };
    let Transport::Tls(t) = &mut io.conn else {
        let rc = io.process_rc();
        return io.done(rc);
    };
    if events.read && t.pending.is_none() && gate {
        io.read_attempted = true;
        match io.rx.recv(&t.sock, READ_BUFFER_LEN) {
            Ok(0) => {
                // Peer closed the socket. Latch end-of-file state in the TLS engine so any
                // preceding decrypted records in the engine are drained first.
                let _latched = t.tls.feed_eof();
            }
            Ok(n) => {
                let data = io.rx.data().get(..n).unwrap_or_default();
                if t.tls.feed(data).is_err() {
                    alert_attempt(&t.sock, &mut *t.tls, &t.ct_out);
                }
            }
            Err(Errno::WOULDBLOCK) => {}
            Err(e) => t.pending = Some(ReadFailure::Io(errno_value(e))),
        }
    }
    if t.pending.is_none() {
        t.pending = take_plaintext(&mut *t.tls, &mut io.rx_pending);
    }
    tls_frames(io, cx, proof, ob, false)
}

/// Drives the decrypted frame dispatch loop and settles any deferred transport failures or
/// post-handshake TLS control transmissions.
///
/// Serves both as the tail of [`handshake_ready`] (`handshake = true`) and [`tls_read`]
/// (`handshake = false`), as well as the reentry point when [`IoCore::resume`] continues from
/// [`Cont::TlsFrames`].
///
/// Execution stages:
/// 1. **Frame Parsing (`IoCore::frame_loop`)**: Feeds decrypted plaintext from `io.rx_pending` into
///    `Connection::handle_read` one frame at a time. If an outbox barrier is encountered (such as a
///    synchronous completion, auth lockpoint, or SASL step), saves [`Cont::TlsFrames`] and returns
///    [`Progress::More`]. If frame parsing halts due to backpressure or an in-flight SASL mechanism
///    evaluation ([`FrameEnd::Held`]), concludes the turn immediately, retaining any latched
///    `t.pending` error until the held frames can be consumed.
/// 2. **Deferred Failure Settlement**: Once `frame_loop` returns [`FrameEnd::Done`] (confirming all
///    complete frames buffered ahead of the fault have been delivered), inspects `t.pending`. If a
///    [`ReadFailure`] was latched, translates it into the corresponding [`TransportFailure`]
///    (`Eof` for clean `close_notify`/EOF, `Io` for record corruption or syscall errors) and
///    invokes `Connection::handle_transport_failed`.
/// 3. **Post-Handshake Ciphertext Flushing**: In TLS 1.3, ingesting peer records during `tls_read`
///    (such as `KeyUpdate` or `NewSessionTicket` frames) can cause the TLS engine to generate
///    outbound response records even when no ZooKeeper application frames were sent. If
///    `t.tls.has_ciphertext()` is true, [`pump_out`] flushes those control records to the socket.
pub(crate) fn tls_frames<T: Tokens>(
    io: &mut IoCore,
    cx: &StepCtx<'_, T>,
    proof: &mut Unlocked,
    ob: &mut Outbox<T>,
    handshake: bool,
) -> Progress {
    // Handshake turns always report ZOK unless a fatal teardown error code was latched in
    // `stop_rc`, whereas normal data turns follow standard `process_rc` precedence (returning
    // ZNOTHING if a socket read was attempted without yielding a complete frame).
    let rc_of = |io: &IoCore| {
        if handshake {
            io.stop_rc.unwrap_or(ZOK)
        } else {
            io.process_rc()
        }
    };
    match io.frame_loop(cx, proof, ob) {
        // Because the TLS session may retain additional decrypted plaintext not yet transferred to
        // `rx`, synchronous reply holds over TLS always resume within the current turn.
        FrameEnd::More | FrameEnd::ReplyHold => {
            io.cont = Cont::TlsFrames { handshake };
            return Progress::More;
        }
        FrameEnd::Held => {
            let rc = rc_of(io);
            return io.done(rc);
        }
        FrameEnd::Done => {}
    }
    // All complete decrypted frames have now been dispatched. Check whether a transport or record
    // failure was latched behind them.
    let pending = match &io.conn {
        Transport::Tls(t) => t.pending,
        _ => None,
    };
    if let Some(p) = pending {
        let now = cx.clock.now();
        let (failed, barrier) = {
            let mut g = cx.core.lock(proof);
            let mut failed = None;
            if let Some(conn) = g.conn.as_deref_mut() {
                if conn.has_transport() {
                    if let Transport::Tls(t) = &mut io.conn {
                        t.pending = None;
                    }
                    let f = match p {
                        ReadFailure::Closed => TransportFailure::Eof,
                        ReadFailure::Data => TransportFailure::Io { errno: 0 },
                        ReadFailure::Io(errno) => TransportFailure::Io { errno },
                    };
                    failed = Some(conn.handle_transport_failed(f, now));
                }
            }
            (failed, io.settle(&mut g, cx, ob))
        };
        if let Some(rc) = failed {
            io.stop_rc.get_or_insert(rc);
        }
        let rc = rc_of(io);
        return io.end_after(barrier, rc);
    }
    // Flush any outbound TLS control records (e.g., TLS 1.3 KeyUpdate responses) generated while
    // processing incoming peer records.
    if let Transport::Tls(t) = &mut io.conn {
        if t.tls.has_ciphertext() {
            if let Err(e) = pump_out(t) {
                let f = TransportFailure::Io {
                    errno: errno_value(e),
                };
                return io.fail_end(cx, proof, ob, f);
            }
        }
    }
    let rc = rc_of(io);
    io.done(rc)
}

/// Drains decrypted application plaintext from the TLS engine into the pending receive buffer,
/// returning any latched transport failure encountered.
///
/// Sequential calls to [`TlsConn::read_plaintext`] continue until the engine returns `Ok(0)` (no
/// further plaintext buffered and no error latched) or `Err(f)`. Because `TlsConn` guarantees that
/// all plaintext decoded prior to a stream error or `close_notify` is yielded before `Err(f)` is
/// surfaced, this loop preserves every valid application byte in `rx_pending` before returning the
/// terminal [`ReadFailure`].
// Buffer append semantics require a mutable Vec destination.
#[allow(clippy::ptr_arg)]
pub(crate) fn take_plaintext(
    tls: &mut dyn TlsConn,
    rx_pending: &mut Vec<u8>,
) -> Option<crate::net::tls::ReadFailure> {
    loop {
        match tls.read_plaintext(rx_pending) {
            Ok(0) => return None,
            Ok(_) => {}
            Err(f) => return Some(f.into()),
        }
    }
}

/// Flushes pending application frames through the TLS encryption pipeline to the network socket,
/// enforcing kernel-confirmed frame transmission acknowledgments.
///
/// Wrapper around [`tls_flush_with`] for non-shutdown paths: timeout and session-level encryption
/// errors are suppressed into `Ok(())` because any underlying transport or TLS session fault will
/// be surfaced through the read path, whereas raw OS socket `send`/`poll` errors return `Err(e)`.
///
/// # Errors
///
/// Returns the underlying operating system `Errno` if socket transmission fails.
pub(crate) fn tls_flush<T: Tokens>(
    io: &mut IoCore,
    cx: &StepCtx<'_, T>,
    proof: &mut Unlocked,
    deadline: Option<Millis>,
) -> Result<(), Errno> {
    match tls_flush_with(io, cx, proof, deadline) {
        Ok(()) | Err(FlushError::Timeout | FlushError::Session) => Ok(()),
        Err(FlushError::Send(e) | FlushError::Poll(e)) => Err(e),
    }
}

/// Internal implementation of [`tls_flush`] with parameterized failure handling for both
/// nonblocking event-loop flushes (`deadline == None`) and bounded blocking shutdown flushes
/// (`deadline == Some(d)`).
///
/// Each iteration of the flush loop maintains a strict four-step invariant:
/// 1. **Failure Guard**: If a read-side failure is already latched in `t.pending`, aborts
///    immediately (returning `Err(FlushError::Session)` during a deadline-bounded close flush, or
///    `Ok(())` during normal nonblocking I/O).
/// 2. **Ciphertext Drain**: If unsent ciphertext remains in `t.ct_out` or inside `t.tls` from a
///    prior pass (`t.has_unsent()`), attempts to write it to the socket via [`pump_out`] or
///    [`pump_until`]. If the kernel socket buffer saturates (`!all_sent`), returns `Ok(())`
///    immediately; the remaining ciphertext stays in `t.ct_out` and `t.unacked` remains uncredited
///    until the next write-readiness event.
/// 3. **Transmission Acknowledgment**: Once `!t.has_unsent()` is confirmed, any nonzero
///    `t.unacked` plaintext byte count is taken and reported to `Connection::handle_transmitted`
///    under the `core` lock. Consequently, the protocol core releases a frame only after the
///    kernel has accepted every byte of its encrypted TLS record flight. This credit does not
///    govern `xid` reply matching: the protocol core marks a request for reply matching when
///    [`stage_chunk`] takes the last byte of its frame, because the server can reply before the
///    remainder of the window is transmitted.
/// 4. **Next Chunk Staging and Encryption**: Calls [`stage_chunk`] to pull up to [`MAX_CHUNK`]
///    of outbound plaintext into `io.tx_copy`. If zero bytes are available, the flush is complete.
///    Otherwise, encrypts `io.tx_copy` into the TLS session via [`TlsConn::write_frame`], clears
///    `io.tx_copy`, transfers the resulting ciphertext into `t.ct_out`, records `t.unacked = n`,
///    and loops back to step 1 to transmit the new flight.
pub(crate) fn tls_flush_with<T: Tokens>(
    io: &mut IoCore,
    cx: &StepCtx<'_, T>,
    proof: &mut Unlocked,
    deadline: Option<Millis>,
) -> Result<(), FlushError> {
    let stop = if deadline.is_some() {
        Err(FlushError::Session)
    } else {
        Ok(())
    };
    loop {
        let Transport::Tls(t) = &mut io.conn else {
            return Ok(());
        };
        if t.pending.is_some() {
            return stop;
        }
        // Step 2: Drain any in-flight ciphertext before crediting `unacked` or staging new chunks.
        if t.has_unsent() {
            let all_sent = match deadline {
                None => pump_out(t).map_err(FlushError::Send)?,
                Some(d) => pump_until(cx, t, d)?,
            };
            if !all_sent {
                // Socket send buffer is full (`EWOULDBLOCK`); resume transmission on next POLLOUT.
                return Ok(());
            }
        }
        // Step 3: All ciphertext from the previous chunk is now in the kernel. Credit the protocol
        // engine for the corresponding plaintext bytes.
        if t.unacked > 0 {
            let n = std::mem::take(&mut t.unacked);
            let now = cx.clock.now();
            let mut g = cx.core.lock(proof);
            let Some(conn) = g.conn.as_deref_mut() else {
                return Ok(());
            };
            if !conn.has_transport() {
                return Ok(());
            }
            conn.handle_transmitted(n, now);
        }
        // Step 4: Stage the next plaintext chunk (up to 256 KiB) from the protocol transmit queue.
        let n = stage_chunk(io, cx, proof);
        if n == 0 {
            return Ok(());
        }
        let Transport::Tls(t) = &mut io.conn else {
            io.tx_copy.clear();
            return Ok(());
        };
        // Encrypt the staged plaintext into TLS records and move them into `t.ct_out`.
        let taken = t.tls.write_frame(&io.tx_copy);
        io.tx_copy.clear();
        if taken.is_err() {
            // Retain unencrypted frames in the protocol engine (`unacked` is not credited) if the
            // TLS session rejected the write.
            return stop;
        }
        let _n = t.tls.take_ciphertext(&mut t.ct_out);
        t.unacked = n;
    }
}

/// Repeatedly transmits outbound ciphertext, polling for socket writability until the specified
/// deadline expires or all buffered data is written.
///
/// Used exclusively during graceful connection close ([`IoCore::close_flush`]). Returns `Ok(true)`
/// when all pending ciphertext has been completely transferred to the kernel.
fn pump_until<T: Tokens>(
    cx: &StepCtx<'_, T>,
    t: &mut TlsTransport,
    deadline: Millis,
) -> Result<bool, FlushError> {
    loop {
        if pump_out(t).map_err(FlushError::Send)? {
            return Ok(true);
        }
        wait_writable(cx.clock, cx.poll, &t.sock, deadline)?;
    }
}

/// Transmits pending outbound ciphertext using nonblocking socket writes until `ct_out` drains
/// or `EWOULDBLOCK` is encountered.
///
/// First pulls any newly generated ciphertext from `t.tls` into `t.ct_out`. As each `send`
/// succeeds, `t.mark_sent(n)` advances the `ct_sent` cursor and clears `ct_out` once the entire
/// buffer has been written. Returns `Ok(true)` if `ct_out` is completely empty, or `Ok(false)` if
/// the socket would block with unsent bytes remaining.
///
/// # Errors
///
/// Returns the underlying operating system `Errno` if a write syscall encounters a fatal error.
pub(crate) fn pump_out(t: &mut TlsTransport) -> Result<bool, Errno> {
    let _n = t.tls.take_ciphertext(&mut t.ct_out);
    while !t.ct_out.is_empty() {
        match t.sock.send(t.unsent()) {
            Ok(0) | Err(Errno::WOULDBLOCK) => return Ok(false),
            Ok(n) => t.mark_sent(n),
            Err(Errno::INTR) => {}
            Err(e) => return Err(e),
        }
    }
    Ok(true)
}

/// Acquires the `core` lock to pull the next chunk of outbound plaintext (up to [`MAX_CHUNK`])
/// from the protocol session into the driver staging buffer (`io.tx_copy`), returning the number
/// of bytes transferred.
///
/// A return value of zero indicates that no active transport exists or no outbound frames are
/// currently sendable under the protocol's phase-gating rules. After unlocking `core`, evaluates
/// [`release_large_buffers`] so that reaching the end of a burst (`n == 0`) can trigger buffer
/// capacity reclamation if warranted.
fn stage_chunk<T: Tokens>(io: &mut IoCore, cx: &StepCtx<'_, T>, proof: &mut Unlocked) -> usize {
    io.tx_copy.clear();
    let n = {
        let mut g = cx.core.lock(proof);
        match g.conn.as_deref_mut() {
            Some(conn) if conn.has_transport() => conn.poll_transmit(&mut io.tx_copy, MAX_CHUNK),
            _ => return 0,
        }
    };
    release_large_buffers(io, n);
    n
}

/// Reclaims oversized TLS transmit buffers after a consecutive series of small payload bursts.
///
/// Large transmission bursts cause `io.tx_copy`, `t.ct_out`, and the internal ciphertext buffer
/// inside [`TlsConn`] to expand up to approximately [`MAX_CHUNK`] (256 KiB). Routine ZooKeeper
/// traffic consisting of small request headers and heartbeats does not require this excess
/// capacity. Reclaiming it prevents long-lived idle sessions from permanently retaining high
/// memory watermarks after a one-off large write.
///
/// `n` represents the byte size of the chunk most recently staged by `poll_transmit` (where
/// `n == 0` marks the completion of a write burst).
pub(crate) fn release_large_buffers(io: &mut IoCore, n: usize) {
    let Transport::Tls(t) = &mut io.conn else {
        return;
    };
    // Large write operations expand `tx_copy` and at least one ciphertext buffer. Small
    // buffer capacities indicate memory was already reclaimed by a previous reduction.
    let cap = io.tx_copy.capacity().max(t.ct_out.capacity());
    if !io.write_bursts.release(n, cap) || cap <= READ_BUFFER_LEN {
        return;
    }
    if io.tx_copy.capacity() > READ_BUFFER_LEN {
        // Reallocate `tx_copy` to ensure previous memory holding sensitive plaintext payloads
        // is cleanly scrubbed by the `Zeroizing` wrapper upon drop.
        io.tx_copy = Zeroizing::new(Vec::new());
    }
    free_ciphertext_buffers(t);
}

/// Deallocates the outbound ciphertext buffer (`t.ct_out`) and any spare capacity retained inside
/// the [`TlsConn`] implementation once all ciphertext has been transmitted over the socket.
///
/// In `RustlsConn`, `take_ciphertext` uses `move_ciphertext`, which swaps `t.ct_out` with the
/// session's internal `tx` vector whenever `t.ct_out` is empty. Consequently, after a large burst,
/// one high-capacity allocation sits in `t.ct_out` and another sits inside `t.tls`. Replacing
/// `t.ct_out` with a fresh zero-capacity vector and then passing a local zero-capacity vector
/// (`held`) to `t.tls.take_ciphertext` swaps out the session's internal buffer so both oversized
/// allocations are dropped when `held` goes out of scope.
fn free_ciphertext_buffers(t: &mut TlsTransport) {
    if !t.ct_out.is_empty() || t.tls.has_ciphertext() {
        return;
    }
    t.ct_out = Vec::new();
    let mut held = Vec::new();
    let _n = t.tls.take_ciphertext(&mut held);
}

/// Tracks recent outbound write sizes across complete transmission bursts to determine when
/// dynamically expanded transmit buffers should be returned to the allocator.
///
/// A "write burst" comprises the sequence of non-empty staged chunks (`n > 0`) between two points
/// where `poll_transmit` returns `0` (indicating the protocol transmit queue has completely
/// drained). A burst is classified as *small* if its largest single chunk (`peak`) occupied less
/// than half of the current buffer capacity (`peak * 2 < cap`). Observing [`SMALL_BURSTS`] (8)
/// consecutive small bursts triggers memory reclamation.
///
/// Measuring whole bursts rather than individual chunks is essential: a 600 KiB request under TLS
/// is staged as two 256 KiB chunks followed by an 88 KiB trailing chunk and a terminal `0`.
/// Evaluating individual chunks would misclassify the 88 KiB tail as small, whereas tracking the
/// burst's `peak` (256 KiB) properly recognizes the burst as large and resets the counter.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct WriteBursts {
    /// Peak chunk size (in bytes) observed within the currently active write burst.
    peak: usize,
    /// Count of consecutive small write bursts observed, saturating at [`SMALL_BURSTS`].
    small: u8,
}

/// Threshold of consecutive small write bursts required to trigger transmit buffer reclamation.
pub(crate) const SMALL_BURSTS: u8 = 8;

impl WriteBursts {
    /// Records the staging of a transmit chunk containing `n` bytes against a buffer capacity of
    /// `cap` bytes.
    ///
    /// Calls with `n > 0` update the active burst's `peak` watermark and return `false`. Passing
    /// `n == 0` marks the end of a write burst: if data was sent during the burst (`peak > 0`),
    /// compares `peak` against `cap / 2`, either resetting `small` to `0` (for a large burst) or
    /// incrementing `small` and returning `true` once [`SMALL_BURSTS`] consecutive small bursts
    /// have completed.
    pub(crate) fn release(&mut self, n: usize, cap: usize) -> bool {
        if n > 0 {
            self.peak = self.peak.max(n);
            return false;
        }
        let peak = std::mem::take(&mut self.peak);
        if peak == 0 {
            // No data was staged since the last burst boundary; ignore repeated zero markers so
            // idle poll turns do not artificially age out the buffer.
            return false;
        }
        if peak.saturating_mul(2) >= cap {
            self.small = 0;
            return false;
        }
        self.small = self.small.saturating_add(1).min(SMALL_BURSTS);
        self.small >= SMALL_BURSTS
    }
}

/// Transmits a graceful TLS `close_notify` alert during connection termination.
///
/// Invoked by [`IoCore::close_flush`] only after all preceding application frames (including the
/// ZooKeeper `CLOSE_OP` request) have been completely flushed to the kernel and the server reply
/// wait has concluded. Queues the `close_notify` record via [`TlsConn::send_close_notify`] and
/// performs nonblocking socket writes (polling for writability up to `deadline`) until the alert
/// is sent or the remaining shutdown time budget expires. Transport errors are ignored during
/// teardown, and the client does not wait for a reciprocal `close_notify` from the peer.
pub(crate) fn close_notify<T: Tokens>(t: &mut TlsTransport, cx: &StepCtx<'_, T>, deadline: Millis) {
    t.tls.send_close_notify();
    loop {
        match pump_out(t) {
            Ok(true) | Err(_) => return,
            Ok(false) => {}
        }
        if wait_writable(cx.clock, cx.poll, &t.sock, deadline).is_err() {
            return;
        }
    }
}
