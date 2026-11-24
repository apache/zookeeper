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

//! Bounded read-only server probing state machine (`isro` probe).
//!
//! # Architectural Role in `zk-driver`
//!
//! When a ZooKeeper client is connected in read-only mode (`r-o` partition) to a non-quorum server,
//! the protocol engine (`zk-proto`) periodically emits [`zk_proto::Action::ProbeRw`] for candidate
//! servers in the ensemble to discover whether a read-write quorum member has recovered.
//!
//! [`probe`] executes this check completely outside the driver's `core` mutex (`proof: &Unlocked`),
//! either inline on the I/O thread (`DeferPolicy::NONE` / `DeferPolicy::DNS_THREAD`) or on Tokio's
//! blocking thread pool (`DeferPolicy::ALL`). It opens an independent nonblocking TCP socket to
//! the target [`Endpoint`], optionally negotiates an ephemeral TLS session (`TlsPurpose::Probe`),
//! transmits the four-letter `"isro"` command, and inspects whether the reply begins with `"rw\0"`.
//! Both the socket and TLS session are torn down immediately upon completion without sending a TLS
//! `close_notify` alert. The resulting [`ProbeOutcome`] is reported back to the protocol engine via
//! `Connection::handle_rw_probe` in [`crate::iocore`]; probe failures never disrupt or tear down
//! the active read-only client session.

use rustix::event::{PollFd, PollFlags};
use rustix::io::Errno;
use zk_proto::compat::TlsVerify;
use zk_proto::{ConnectStart, Endpoint, ProbeOutcome, ProbeTlsFailure};

use crate::clock::Clock;
use crate::net::poll::{Poll, PollEvents, clamp_timeout_ms, request_flags};
use crate::net::sock::TcpSock;
use crate::net::tls::{
    TlsConn, TlsConnector, TlsFailure, TlsPurpose, TlsResourceAt, TlsSetupError, alert_attempt,
    jstring,
};
use crate::net::waker::{WakeRx, Waker};
use crate::sync::Unlocked;
use crate::sync::atomic::{AtomicBool, Ordering};

/// Default duration budget allocated to each distinct probe phase (TCP connect, TLS handshake, and
/// `isro` exchange).
pub const PROBE_PHASE_MS: u64 = 1000;

/// Execution context and platform services provided to the probe state machine.
pub struct ProbeCtx<'a> {
    /// nonblocking polling facade used to monitor descriptor readiness.
    pub poll: &'a dyn Poll,
    /// Monotonic clock provider used to evaluate phase deadlines.
    pub clock: &'a dyn Clock,
    /// Optional self-pipe waker pair allowing external thread wakes to interrupt probing.
    pub wake: Option<(&'a Waker, &'a WakeRx)>,
    /// Cancellation flag inspected after each poll cycle to abort probing during session teardown.
    pub abort: Option<&'a AtomicBool>,
    /// Optional TLS connector for establishing encrypted probe sessions.
    pub tls: Option<&'a dyn TlsConnector>,
    /// Flag indicating whether created probe sockets must enforce close-on-exec (`FD_CLOEXEC`).
    pub cloexec: bool,
    /// Timeout allocation for each individual probe phase in milliseconds.
    pub phase_ms: u64,
}

/// Terminal outcome of a read-write server probe invocation.
#[derive(Debug, PartialEq, Eq)]
pub enum ProbeEnd {
    /// Probing completed; outcome is reported to the protocol engine via `handle_rw_probe`.
    Done(ProbeOutcome),
    /// Probing was aborted prematurely due to handle closure or detachment.
    Aborted,
}

/// Executes a bounded read-only server probe across sequential phases.
///
/// Execution pipeline:
/// 1. **Socket Allocation**: Opens a nonblocking TCP socket via [`crate::net::TcpSock::open`].
///    Allocation failures immediately produce [`ProbeOutcome::Failed`].
/// 2. **TCP Connect Phase**: Initiates `connect` and polls for write readiness bounded by
///    `phase_ms`, followed by inspecting `SO_ERROR`. Connect timeouts or errors produce
///    [`ProbeOutcome::Failed`].
/// 3. **TLS Handshake Phase**: If TLS verification is active, instantiates a probe session via
///    [`TlsConnector::new_conn`] and drives the nonblocking handshake bounded by `phase_ms`.
///    Handshake or verification failures trigger an alert attempt before returning
///    [`ProbeOutcome::Failed`].
/// 4. **`isro` Command Exchange**: Transmits the four-letter `"isro"` request and reads the
///    server's reply, sharing a single `phase_ms` timeout budget. Plaintext exchanges read up to 10
///    bytes; TLS exchanges decrypt records until plaintext or EOF is observed.
/// 5. **Outcome Evaluation**: Evaluates whether the reply begins with `"rw\0"` via [`is_rw_reply`],
///    returning [`ProbeOutcome::ReadWrite`] if matching, or [`ProbeOutcome::NotReadWrite`]
///    otherwise.
/// 6. **Teardown**: Drops the TLS session without `close_notify` and closes the probe socket.
#[must_use]
pub fn probe(
    cx: &ProbeCtx<'_>,
    proof: &Unlocked,
    ep: &Endpoint,
    verify: Option<TlsVerify>,
) -> ProbeEnd {
    match run(cx, proof, ep, verify) {
        Ok(outcome) => ProbeEnd::Done(outcome),
        Err(Stop::Failed(f)) => ProbeEnd::Done(ProbeOutcome::Failed(f)),
        Err(Stop::Aborted) => ProbeEnd::Aborted,
    }
}

/// Evaluates whether the accumulated response bytes begin with `"rw\0"` conforming to C client
/// `strcmp(buf, "rw") == 0` validation.
#[must_use]
pub fn is_rw_reply(reply: &[u8]) -> bool {
    // Missing bytes correspond to zeroes in the zero-filled buffer.
    let byte = |i: usize| reply.get(i).copied().unwrap_or(0);
    byte(0) == b'r' && byte(1) == b'w' && byte(2) == 0
}

/// Maximum capacity of the probe reply buffer, matching legacy C client buffer sizing.
const REPLY_LEN: usize = 10;

/// Buffer capacity allocated for TLS ciphertext records, sized to hold a full TLS record frame
/// (16KB payload + overhead).
const CIPHERTEXT_BUF: usize = 17 * 1024;

/// Internal stop condition signaling premature probe termination.
#[derive(Debug)]
enum Stop {
    /// Probe failed due to network error, timeout, or TLS handshake failure.
    Failed(Option<ProbeTlsFailure>),
    /// Probe was aborted due to session cancellation.
    Aborted,
}

/// Outcome of a single bounded poll wait operation.
#[derive(Debug, PartialEq, Eq)]
enum Wait {
    /// Socket is ready for requested I/O operations or has encountered an error.
    Ready,
    /// Phase timeout expired before readiness was signaled.
    Timeout,
    /// Operating system poll system call failed.
    Failed,
    /// Probe was aborted due to session cancellation.
    Aborted,
}

/// Sequentially executes the probe connection, handshake, and request phases.
fn run(
    cx: &ProbeCtx<'_>,
    proof: &Unlocked,
    ep: &Endpoint,
    verify: Option<TlsVerify>,
) -> Result<ProbeOutcome, Stop> {
    if aborted(cx) {
        return Err(Stop::Aborted);
    }
    let sock = connect(cx, ep)?;
    match verify {
        None => exchange_plain(cx, &sock),
        Some(_) => {
            let mut tls = new_session(cx, proof, ep)?;
            let mut ct_out = Vec::new();
            let mut buf = vec![0u8; CIPHERTEXT_BUF];
            handshake(cx, &sock, &mut *tls, &mut ct_out, &mut buf)?;
            exchange_tls(cx, &sock, &mut *tls, &mut ct_out, &mut buf)
        }
    }
}

/// Opens the nonblocking probe socket and connects to the target endpoint within the phase
/// timeout.
fn connect(cx: &ProbeCtx<'_>, ep: &Endpoint) -> Result<TcpSock, Stop> {
    let fail = Stop::Failed(None);
    let sock = TcpSock::open(&ep.addr, cx.cloexec).map_err(|_| Stop::Failed(None))?;
    let sock = sock.sock;
    match sock.start_connect(&ep.addr) {
        Ok(ConnectStart::Immediate) => Ok(sock),
        Ok(ConnectStart::InProgress) => {
            let deadline = deadline(cx);
            match wait(cx, &sock, WRITE, deadline) {
                Wait::Ready => match sock.socket_error() {
                    Ok(()) => Ok(sock),
                    Err(_) => Err(fail),
                },
                Wait::Aborted => Err(Stop::Aborted),
                Wait::Timeout | Wait::Failed => Err(fail),
            }
        }
        Err(_) => Err(fail),
    }
}

/// Instantiates an ephemeral TLS session configured specifically for probing.
fn new_session(
    cx: &ProbeCtx<'_>,
    proof: &Unlocked,
    ep: &Endpoint,
) -> Result<Box<dyn TlsConn>, Stop> {
    let Some(connector) = cx.tls else {
        return Err(Stop::Failed(Some(ProbeTlsFailure::Resource {
            at: TlsResourceAt::ContextNew,
        })));
    };
    connector
        .new_conn(proof, TlsPurpose::Probe, ep)
        .map_err(|e| match e {
            TlsSetupError::Config { step } => Stop::Failed(Some(ProbeTlsFailure::Config {
                step,
                file: jstring(connector.path_for(step)),
            })),
            TlsSetupError::Resource { at } => Stop::Failed(Some(ProbeTlsFailure::Resource { at })),
        })
}

/// Drives the nonblocking TLS handshake to completion within the phase timeout.
///
/// Any ciphertext emitted by the TLS engine remaining unwritten at handshake completion
/// is retained in `ct_out` and transmitted during the subsequent data exchange.
fn handshake(
    cx: &ProbeCtx<'_>,
    sock: &TcpSock,
    tls: &mut dyn TlsConn,
    ct_out: &mut Vec<u8>,
    buf: &mut [u8],
) -> Result<(), Stop> {
    let fail = || Stop::Failed(Some(ProbeTlsFailure::Handshake));
    let deadline = deadline(cx);
    loop {
        tls.take_ciphertext(ct_out);
        flush(sock, ct_out).map_err(|_| fail())?;
        if !tls.is_handshaking() {
            return Ok(());
        }
        let want = PollEvents {
            read: true,
            write: !ct_out.is_empty(),
        };
        match wait(cx, sock, want, deadline) {
            Wait::Ready => {}
            Wait::Aborted => return Err(Stop::Aborted),
            Wait::Timeout | Wait::Failed => return Err(fail()),
        }
        match sock.recv(buf) {
            Ok(0) => {
                let _ = tls.feed_eof();
                return Err(fail());
            }
            Ok(n) => {
                if tls.feed(buf.get(..n).unwrap_or_default()).is_err() {
                    // Attempt sending a TLS alert record upon certificate verification or record
                    // decode errors.
                    alert_attempt(sock, tls, ct_out);
                    return Err(fail());
                }
            }
            Err(e) if in_progress(e) => {}
            Err(_) => return Err(fail()),
        }
    }
}

/// Transmits the `"isro"` request and reads the single frame plaintext reply within the phase
/// timeout.
fn exchange_plain(cx: &ProbeCtx<'_>, sock: &TcpSock) -> Result<ProbeOutcome, Stop> {
    let deadline = deadline(cx);
    let mut rest: &[u8] = b"isro";
    while !rest.is_empty() {
        match sock.send(rest) {
            Ok(n) => rest = rest.get(n..).unwrap_or_default(),
            Err(e) if in_progress(e) => wait_or_stop(cx, sock, WRITE, deadline)?,
            Err(_) => return Err(Stop::Failed(None)),
        }
    }
    let mut reply = [0u8; REPLY_LEN];
    loop {
        wait_or_stop(cx, sock, READ, deadline)?;
        match sock.recv(&mut reply) {
            Ok(_) => return Ok(outcome(&reply)),
            Err(e) if in_progress(e) => {}
            Err(_) => return Err(Stop::Failed(None)),
        }
    }
}

/// Transmits the `"isro"` request over TLS and reads decrypted plaintext response bytes within the
/// phase timeout.
fn exchange_tls(
    cx: &ProbeCtx<'_>,
    sock: &TcpSock,
    tls: &mut dyn TlsConn,
    ct_out: &mut Vec<u8>,
    buf: &mut [u8],
) -> Result<ProbeOutcome, Stop> {
    let deadline = deadline(cx);
    if tls.write_frame(b"isro").is_err() {
        alert_attempt(sock, tls, ct_out);
        return Err(Stop::Failed(None));
    }
    let mut plain = Vec::new();
    loop {
        tls.take_ciphertext(ct_out);
        flush(sock, ct_out).map_err(|_| Stop::Failed(None))?;
        match tls.read_plaintext(&mut plain) {
            Ok(0) => {}
            Ok(_) => {
                let reply = plain.get(..REPLY_LEN).unwrap_or(&plain);
                return Ok(outcome(reply));
            }
            Err(TlsFailure::Closed) => return Ok(ProbeOutcome::NotReadWrite),
            Err(_) => {
                alert_attempt(sock, tls, ct_out);
                return Err(Stop::Failed(None));
            }
        }
        let want = PollEvents {
            read: true,
            write: !ct_out.is_empty(),
        };
        wait_or_stop(cx, sock, want, deadline)?;
        match sock.recv(buf) {
            // Latch stream EOF or errors into the TLS engine to be surfaced by the next
            // read_plaintext call.
            Ok(0) => {
                let _ = tls.feed_eof();
            }
            Ok(n) => {
                let _ = tls.feed(buf.get(..n).unwrap_or_default());
            }
            Err(e) if in_progress(e) => {}
            Err(_) => return Err(Stop::Failed(None)),
        }
    }
}

/// Evaluates raw reply bytes to determine read-write versus read-only server status.
fn outcome(reply: &[u8]) -> ProbeOutcome {
    if is_rw_reply(reply) {
        ProbeOutcome::ReadWrite
    } else {
        ProbeOutcome::NotReadWrite
    }
}

/// Pre-configured poll event filter requesting read readiness.
const READ: PollEvents = PollEvents {
    read: true,
    write: false,
};

/// Pre-configured poll event filter requesting write readiness.
const WRITE: PollEvents = PollEvents {
    read: false,
    write: true,
};

/// Returns `true` if `errno` indicates a transient nonblocking condition (`EWOULDBLOCK` or
/// `EINTR`).
fn in_progress(e: Errno) -> bool {
    e == Errno::WOULDBLOCK || e == Errno::INTR
}

/// Returns `true` if the driver context's cancellation flag has been asserted.
fn aborted(cx: &ProbeCtx<'_>) -> bool {
    cx.abort.is_some_and(|f| f.load(Ordering::Acquire))
}

/// Calculates the monotonic timestamp deadline for a newly initiated phase.
fn deadline(cx: &ProbeCtx<'_>) -> u64 {
    cx.clock.now().0.saturating_add(cx.phase_ms)
}

/// Flushes pending ciphertext bytes to the socket until the kernel buffer is full or all bytes are
/// sent.
fn flush(sock: &TcpSock, ct_out: &mut Vec<u8>) -> Result<(), Errno> {
    while !ct_out.is_empty() {
        match sock.send(ct_out) {
            Ok(0) => break,
            Ok(n) => {
                ct_out.drain(..n.min(ct_out.len()));
            }
            Err(e) if in_progress(e) => break,
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

/// Waits for descriptor readiness, translating timeouts and poll errors into probe failures.
fn wait_or_stop(
    cx: &ProbeCtx<'_>,
    sock: &TcpSock,
    want: PollEvents,
    deadline: u64,
) -> Result<(), Stop> {
    match wait(cx, sock, want, deadline) {
        Wait::Ready => Ok(()),
        Wait::Aborted => Err(Stop::Aborted),
        Wait::Timeout | Wait::Failed => Err(Stop::Failed(None)),
    }
}

/// Blocks on descriptor polling until socket readiness is observed, the phase deadline expires, or
/// cancellation is signaled.
///
/// If a self-pipe waker is configured, it is included in the poll set to wake the probe
/// immediately upon session closure. Non-abort wakeups reset the waker and resume polling until the
/// deadline is reached.
fn wait(cx: &ProbeCtx<'_>, sock: &TcpSock, want: PollEvents, deadline: u64) -> Wait {
    let flags = request_flags(want);
    loop {
        if aborted(cx) {
            return Wait::Aborted;
        }
        let left = deadline.saturating_sub(cx.clock.now().0);
        let timeout = clamp_timeout_ms(i64::try_from(left).unwrap_or(i64::MAX));
        let polled = match cx.wake {
            Some((_, rx)) => {
                let mut fds = [PollFd::new(sock, flags), PollFd::new(rx, PollFlags::IN)];
                let r = cx.poll.poll(&mut fds, timeout);
                let [s, w] = &fds;
                r.map(|n| (n, s.revents(), w.revents()))
            }
            None => {
                let mut fds = [PollFd::new(sock, flags)];
                let r = cx.poll.poll(&mut fds, timeout);
                let [s] = &fds;
                r.map(|n| (n, s.revents(), PollFlags::empty()))
            }
        };
        let (n, sock_ev, wake_ev) = match polled {
            Ok(v) => v,
            Err(Errno::INTR) => continue,
            Err(_) => return Wait::Failed,
        };
        if !wake_ev.is_empty() {
            if let Some((waker, rx)) = cx.wake {
                rx.reset(waker);
            }
        }
        if aborted(cx) {
            return Wait::Aborted;
        }
        if !sock_ev.is_empty() {
            return Wait::Ready;
        }
        if n == 0 || cx.clock.now().0 >= deadline {
            return Wait::Timeout;
        }
    }
}
