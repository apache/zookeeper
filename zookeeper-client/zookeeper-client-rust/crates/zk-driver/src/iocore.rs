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

//! Core I/O execution engine and protocol driving pipeline shared across driver runtimes.
//!
//! [`IoCore`] is the engine that connects the  protocol state machine (`zk_proto::Connection`) to
//! operating system sockets, descriptor readiness pollers, hostname resolvers, connectivity
//! probes, and cryptographic TLS sessions. [`IoCore`] is used by every runtime flavor in
//! `zk-driver`—the single threaded caller-polled C shim ([`crate::polled::Polled`]), the
//! multithreaded C shim ([`crate::threaded::Shared`]), the native OS threaded Rust driver
//! (`crate::native::NativeShared`), and the asynchronous Tokio task driver
//! (`crate::tokio::TaskShared`)—delegates its transport lifecycle and wire I/O progression to an
//! owned [`IoCore`] instance.
//!
//! # Concurrency and Lock Hierarchy
//!
//! - **Exclusive I/O Ownership**: An [`IoCore`] is owned and mutated exclusively by the active I/O
//!   execution context, whether that is the dedicated background I/O thread in multithreaded mode,
//!   the caller's event loop in single threaded mode, the native asynchronous task, or the final
//!   teardown handler during session shutdown.
//! - **Strict Lock Ordering**: The outer `io` owner mutex is always acquired before locking the
//!   inner protocol `core` mutex (`crate::state::Core`), which in turn precedes any leaf `fifo`
//!   lock.
//! - **Compile-Time Blocking Exclusion**: No internal mutex is ever held across blocking operating
//!   system calls (`getaddrinfo`, PRNG reseeding, TLS certificate file loading, `poll` waits, or
//!   bounded `close_flush` writes). Acquiring the `core` mutex requires mutably borrowing an
//!   [`Unlocked`] witness token (`cx.core.lock(proof)`), whereas all blocking host leaves and
//!   socket waits require presenting `&Unlocked`, making it a compile time borrow-checker error to
//!   invoke blocking operations while `core` is locked.
//! - **Post-Relock Transport Validation**: Because the `core` lock is released between protocol
//!   actions and socket operations, concurrent application threads (or reentrant user callbacks in
//!   single threaded mode) may initiate session closure or forced teardown while `core` is
//!   unlocked. Every routine that re-acquires `core` re-verifies `Connection::has_transport` before
//!   acting on the socket or crediting transmitted bytes.
//!
//! # Cooperative Continuation State Machine
//!
//! To prevent reentrancy deadlocks and cross language stack unwinding hazards (such as C++
//! exceptions or `longjmp` thrown from user callbacks), `IoCore` never invokes application
//! callbacks or logging hooks inline. Instead, protocol outputs accumulate in a caller supplied
//! [`Outbox`].
//!
//! - **`IoCore::interest_step`**: Drives protocol state transitions (DNS resolution, TCP/TLS
//!   connection initiation, read-write server probing, SASL handshakes, and heartbeat timer checks)
//!   and computes descriptor readiness interest and polling timeouts, implementing the semantics of
//!   `zookeeper_interest`.
//! - **`IoCore::process_step`**: Consumes socket readiness events, flushes outbound frame queues,
//!   ingests incoming wire bytes, and drives frame deserialization, implementing
//!   `zookeeper_process` and `check_events`.
//!
//! Whenever a step encounters an outbox barrier (such as a synchronous completion, an inline
//! session state transition, an authentication lockpoint, a SASL step, or pending log records that
//! must be flushed prior to a blocking call), `IoCore` saves its exact resumption point in
//! `IoCore::cont` (`Cont`) and returns [`Progress::More`]. The foreign FFI harness or runtime
//! driver drains the [`Outbox`] outside of all Rust locks and then invokes `IoCore::resume` to
//! continue execution from the saved continuation checkpoint.
//!
//! # Configurable Offloading of Blocking Work
//!
//! Different runtime environments impose different constraints on blocking operations, governed by
//! [`DeferPolicy`]:
//! - Drop-in C drivers ([`DeferPolicy::NONE`]) execute DNS lookups, TLS context setup, and `isro`
//!   probes inline on the calling thread after flushing pending log records.
//! - The native threaded driver ([`DeferPolicy::DNS_THREAD`]) offloads DNS resolution to an
//!   ephemeral worker thread so that an already-connected socket can continue exchanging heartbeats
//!   and data frames while dynamic ensemble re-resolution runs concurrently in the background
//!   (`IoCore::bg_start`).
//! - The Tokio driver ([`DeferPolicy::ALL`]) offloads all blocking work (`Job::Resolve`,
//!   `Job::NewConn`, and `Job::Probe`) to `spawn_blocking` by yielding [`Progress::Wait`] and
//!   resuming via `IoCore::complete_job`.
//!
//! # Two-Tier Plaintext Ingress and Zero-Copy Framing
//!
//! Plaintext socket reads use a two-tier buffering strategy paired with direct zero-copy frame
//! body ingestion:
//! - When no unparsed bytes remain buffered and the protocol frame decoder is positioned inside an
//!   open frame payload body (`Connection::body_room`), `IoCore::body_part` reads from the socket
//!   directly into the protocol engine's target frame allocation under a single `core` lock scope,
//!   bypassing intermediate driver buffers entirely.
//! - Otherwise, `IoCore::plain_recv` reads into the 64 KiB primary heap buffer (`rx`). If the
//!   secondary overflow buffer (`rx_pending`) is empty, `IoCore::rx_fresh` records the read count
//!   so `IoCore::frame_steps` can parse frames directly from `rx` without copying. Only trailing
//!   partial-frame remnants that survive the turn are spilled into `rx_pending` via
//!   `IoCore::spill_fresh`.
//! - In single threaded mode (`Flavor::St`), the first plaintext read requests
//!   `Connection::first_read_hint` bytes: while the handshake reply is due, the 41-byte
//!   `ConnectResponse` frame is read in one `recv`, as the C primer read does; after a 40-byte
//!   legacy reply, that read can hold bytes of the next frame, which are kept for the next turn.
//!   In other states it requests `Connection::read_hint` bytes (reading the 4-byte length prefix
//!   and then the single frame body in one turn, without reading past the end of the current
//!   frame). In multithreaded and native modes, reads batch up to `DriverConfig::max_read` (64 KiB)
//!   per syscall.
//!
//! # Outbound Staging and Zero-Copy Frame Lending
//!
//! Outbound plaintext transmission (`IoCore::plain_flush`) pulls serialized wire frames from
//! `Connection::poll_transmit` into a [`Zeroizing`] buffer (`tx_copy`). Small frames are batched up
//! to 64 KiB, whereas large unencrypted request frames between 512 KiB and `TX_LEND_MAX` (4 MiB)
//! are lent zero-copy by swapping their backing `Vec<u8>` directly into `tx_copy`. nonblocking
//! socket writes proceed outside the `core` lock, and kernel accepted byte counts are credited back
//! via `Connection::handle_transmitted`. Once traffic returns to small packets, `WriteBursts`
//! reclaims oversized buffer allocations and scrubs any sensitive credential remnants.

use std::cell::Cell;
use std::net::SocketAddr;
use std::sync::Arc;

use rustix::event::{PollFd, PollFlags};
use rustix::io::Errno;
use zeroize::Zeroizing;
use zk_proto::compat::TlsVerify;
use zk_proto::rc::{
    ZCONNECTIONLOSS, ZINVALIDSTATE, ZNOTHING, ZOK, ZOPERATIONTIMEOUT, ZSYSTEMERROR,
};
use zk_proto::{
    Action, ConnectStart, Connection, Endpoint, HostResolution, HostSpec, LogArg, LogSite, Millis,
    ReadOutcome, SaslCallKind, State, Stopped, TlsResourceAt, Tokens, TransportFailure,
};

use crate::clock::Clock;
use crate::dispatch::ReadGate;
use crate::host::{HostLeaves, log_record};
use crate::iocore_tls;
use crate::iocore_tls::WriteBursts;
use crate::net::poll::{Poll, PollEvents, ZOOKEEPER_READ, ZOOKEEPER_WRITE};
use crate::net::probe::{ProbeCtx, ProbeEnd, probe};
use crate::net::sock::{TcpSock, errno_value};
use crate::net::tls::{TlsConn, TlsConnector, TlsPurpose, TlsSetupError, TlsTransport};
use crate::net::waker::{WakeRx, Waker};
use crate::outbox::{Flavor, Item, Outbox, SaslItem};
use crate::resolve::{ResolveCtx, ResolveEnd, ResolveRun, apply_results, shuffle_on};
use crate::state::{Core, SaslAnswer};
use crate::sync::atomic::{AtomicBool, Ordering};
use crate::sync::{Mutex, Unlocked};

/// Operating system socket descriptor written back to foreign caller pointers.
#[cfg(unix)]
pub type RawSock = std::os::fd::RawFd;

/// Standard capacity allocated for heap-backed socket read buffers (64 KiB).
///
/// Sized to match typical OS socket receive buffer bursts and accommodate four 16 KiB TLS records
/// per `recv` syscall while keeping `IoCore` itself compact on the stack.
pub const READ_BUFFER_LEN: usize = 64 * 1024;

/// Fixed-capacity (`READ_BUFFER_LEN`) receive buffer for plaintext and TLS ciphertext reads.
///
/// Allocated with uninitialized spare capacity (`Vec::with_capacity`) to avoid a 64 KiB zeroing
/// pass on handle initialization. The underlying vector's `len` counts the initialized bytes while
/// its capacity remains fixed at `READ_BUFFER_LEN`. Full-capacity reads clear the vector and
/// write directly into spare capacity via [`TcpSock::recv_spare`], so `len` becomes the read count,
/// whereas smaller reads zero-initialize only the bytes above `len` so syscall read lengths remain
/// identical to `want`.
pub(crate) struct RxBuf {
    buf: Vec<u8>,
}

impl RxBuf {
    /// Allocates an uninitialized receive buffer with fixed capacity `READ_BUFFER_LEN`.
    fn new() -> Self {
        Self {
            buf: Vec::with_capacity(READ_BUFFER_LEN),
        }
    }

    /// Reads up to `want` bytes from `sock` into the leading region of the buffer.
    ///
    /// Newly received bytes occupy `data()[..n]`; because residual bytes from earlier reads may
    /// remain initialized past index `n`, callers must slice strictly to the returned byte count.
    pub(crate) fn recv(&mut self, sock: &TcpSock, want: usize) -> Result<usize, Errno> {
        if want >= READ_BUFFER_LEN {
            self.buf.clear();
            return sock.recv_spare(&mut self.buf);
        }
        if self.buf.len() < want {
            self.buf.resize(want, 0);
        }
        match self.buf.get_mut(..want) {
            Some(room) => sock.recv(room),
            None => Ok(0),
        }
    }

    /// Returns the initialized prefix of the buffer.
    pub(crate) fn data(&self) -> &[u8] {
        &self.buf
    }

    /// Returns the fixed buffer capacity.
    #[cfg(test)]
    pub(crate) fn capacity(&self) -> usize {
        self.buf.capacity()
    }

    /// Populates the entire buffer capacity with a sentinel `byte` to detect unoverwritten regions
    /// in tests.
    #[cfg(test)]
    pub(crate) fn fill(&mut self, byte: u8) {
        self.buf.clear();
        self.buf.resize(READ_BUFFER_LEN, byte);
    }

    /// Stages `bytes` at the front of the buffer to simulate a socket read of identical payload.
    #[cfg(test)]
    pub(crate) fn put(&mut self, bytes: &[u8]) {
        if self.buf.len() < bytes.len() {
            self.buf.resize(bytes.len(), 0);
        }
        if let Some(room) = self.buf.get_mut(..bytes.len()) {
            room.copy_from_slice(bytes);
        }
    }
}

/// Maximum single frame payload length accepted from the protocol core without intermediary
/// copying (4 MiB).
///
/// On plaintext connections, `Connection::poll_transmit` can lend serialized message frames
/// between `LEND_MIN` (512 KiB) and this 4 MiB threshold directly into `IoCore::tx_copy` by
/// swapping the underlying `Vec<u8>`, permitting zero-copy socket transmission in a single write
/// pass while bounding the amount of memory pinned in `tx_copy`.
pub(crate) const TX_LEND_MAX: usize = 4 * 1024 * 1024;

/// Immutable operational parameters configuring driver I/O behaviors, buffer limits, and timeouts.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DriverConfig {
    /// Grace period (in milliseconds) allocated for flushing pending outbound requests (including
    /// `CLOSE_OP`) to the socket during graceful session close (`close_flush`).
    pub close_flush_ms: u32,
    /// Maximum duration (in milliseconds) to poll for server acknowledgement or EOF after flushing
    /// `CLOSE_OP` during session termination.
    pub close_wait_ms: u32,
    /// Per-phase timeout budget (in milliseconds) enforced during each stage (TCP connect, TLS
    /// handshake, and `isro` command exchange) of read-write connectivity probing.
    pub probe_phase_ms: u64,
    /// Backpressure dispatch queue capacity threshold for native client event routing; when in-
    /// flight unconsumed items reach this count, [`ReadGate`] closes and suspends socket reads.
    pub dispatch_queue_capacity: usize,
    /// Maximum byte volume ingested during a single initial read operation in multithreaded and
    /// native drivers (clamped to [`READ_BUFFER_LEN`]).
    pub max_read: usize,
    /// Controls whether the `FD_CLOEXEC` flag is applied when opening network sockets. Native
    /// drivers unconditionally enable this, whereas drop-in C drivers follow `sock_cloexec` config.
    pub cloexec: bool,
}

impl Default for DriverConfig {
    fn default() -> Self {
        Self {
            close_flush_ms: zk_proto::CLOSE_FLUSH_MS,
            close_wait_ms: zk_proto::CLOSE_WAIT_MS,
            probe_phase_ms: crate::net::probe::PROBE_PHASE_MS,
            dispatch_queue_capacity: 4096,
            max_read: READ_BUFFER_LEN,
            cloexec: false,
        }
    }
}

/// Output parameters populated by `IoCore::interest_step`, corresponding to the out-parameters
/// of C `zookeeper_interest(zhandle_t *zh, int *fd, int *interest, struct timeval *tv)`.
///
/// The `written` flag is tracked separately from `rc` because legacy C error paths distinguish
/// between early validation failures (which return an error code such as `ZINVALIDSTATE` or whole-
/// list `ZSYSTEMERROR` while leaving the caller's `*fd`, `*interest`, and `*tv` untouched) and
/// mid-step connection failures (which write `*fd = -1`, `*interest = 0`, and `*tv = {0, 0}`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct InterestOut {
    /// Return code denoting the execution status of the interest evaluation.
    pub rc: i32,
    /// Indicates whether the `fd`, `bits`, and `timeout_ms` output parameters were modified during
    /// this turn and should be written back to foreign caller pointers.
    pub written: bool,
    /// Monitored socket descriptor, or `-1` if no transport is active or if the connection was
    /// torn down during this turn.
    pub fd: RawSock,
    /// Bitmask of registered socket readiness interests (`ZOOKEEPER_READ | ZOOKEEPER_WRITE`).
    pub bits: u32,
    /// Recommended polling deadline timeout in milliseconds (`0` when buffered data is ready).
    pub timeout_ms: i64,
}

impl InterestOut {
    /// Baseline output representation prior to interest evaluation: status `ZOK` with no values
    /// committed (`written = false`).
    pub const UNWRITTEN: Self = Self {
        rc: 0,
        written: false,
        fd: -1,
        bits: 0,
        timeout_ms: 0,
    };
}

/// Concrete network transport state machine backing a connection attempt.
///
/// Transitions progress from `None` to `Connecting` (or directly to `Plain`/`Tls` if a loopback
/// `connect()` completes synchronously), and then from `Connecting` to `Plain`, `Tls`, or `Parked`.
pub(crate) enum Transport {
    /// No underlying socket is allocated.
    None,
    /// nonblocking TCP three-way handshake is in progress (`EINPROGRESS`), or TCP connected and is
    /// waiting for outbox log draining or offloaded `Job::NewConn` completion before starting TLS.
    Connecting {
        /// nonblocking TCP socket handle.
        sock: TcpSock,
        /// Target server endpoint descriptor, retained for TLS SNI and hostname verification.
        ep: Endpoint,
        /// TLS peer verification profile active for this connection attempt, or `None` for plain.
        tls: Option<TlsVerify>,
    },
    /// Established plaintext TCP stream transport ready for wire frame exchange.
    Plain(TcpSock),
    /// Active TLS transport session in either the cryptographic handshake or application data
    /// phase.
    Tls(TlsTransport),
    /// Quarantined TCP transport following a legacy TLS configuration failure
    /// (`TlsSetupError::Config`).
    ///
    /// In upstream `zookeeper.c`, when loading a certificate or private key file fails during
    /// `init_ssl_for_socket`, the C client logs the error and returns `ZSSLCONNECTIONERROR` without
    /// closing the TCP socket. The raw socket is retained here without a TLS session and with write
    /// interest suppressed, allowing `process_parked` to watch for remote EOF until the protocol
    /// receive timeout fires and tears down the attempt cleanly.
    Parked(TcpSock),
}

impl Transport {
    /// Borrows the underlying TCP socket handle, if one is presently active.
    pub(crate) fn sock(&self) -> Option<&TcpSock> {
        match self {
            Self::None => None,
            Self::Connecting { sock, .. } | Self::Plain(sock) | Self::Parked(sock) => Some(sock),
            Self::Tls(t) => Some(t.sock()),
        }
    }

    /// Determines whether the transport is currently in the application data transfer phase
    /// (an established plaintext TCP stream or a TLS session that has completed its handshake).
    ///
    /// Outbound payload flushes are restricted to the data phase; during connection establishment
    /// (`Connecting`), `WRITE` readiness signals TCP handshake completion, whereas during a TLS
    /// handshake it drives cryptographic record exchange. Quarantined `Parked` sockets never
    /// register write interest.
    pub(crate) fn data_phase(&self) -> bool {
        match self {
            Self::Plain(_) => true,
            Self::Tls(t) => !t.tls.is_handshaking(),
            Self::None | Self::Connecting { .. } | Self::Parked(_) => false,
        }
    }
}

/// Continuation checkpoint capturing the exact resumption point for a suspended driver turn.
///
/// Because [`IoCore`] cannot keep Rust stack frames or mutex guards alive while the foreign C
/// harness or native router drains [`Outbox`] items, both [`IoCore::interest_step`] and
/// [`IoCore::process_step`] are structured as explicit state machines. Whenever an operation hits
/// an outbox barrier (`Progress::More`) or offloads a blocking task (`Progress::Wait`), `IoCore`
/// stores the corresponding [`Cont`] variant in `self.cont`. Subsequent calls to
/// [`IoCore::resume`] (or [`IoCore::complete_job`]) dispatch on this variant to pick up execution
/// immediately after the barrier.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Cont {
    /// No execution step is currently suspended.
    None,
    /// `interest_step` suspended within its protocol action loop (`interest_actions`) or after
    /// `begin_interest` / `run_pending_teardown` produced a barrier.
    Interest,
    /// `process_step` suspended within its plaintext frame ingestion and dispatch loop
    /// (`plain_frames`).
    Process {
        /// Registered readiness events preserved for the resumed invocation.
        events: PollEvents,
    },
    /// `interest_step` suspended during its initial `prelude` before entering `interest_begin`.
    InterestBegin,
    /// `interest_step` completed `interest_finish`, but final outbox settlement hit a barrier;
    /// resumption restores `out` and concludes the step.
    InterestEnd {
        /// Captured interest evaluation results to write to caller output upon resumption.
        out: InterestOut,
    },
    /// TLS handshake startup paused prior to `TlsConnector::new_conn` so pending connection log
    /// records (`ZkConnect`, `TcpNoDelay`) can drain to the host first.
    Handshake,
    /// TLS handshake startup finished with status `rc`, but settling its initial protocol outputs
    /// hit an outbox barrier; resumption routes `rc` through `after_handshake`.
    AfterHandshake {
        /// Completion status code resulting from TLS connection establishment.
        rc: i32,
    },
    /// `process_step` suspended during its initial `prelude` or after `run_pending_teardown`
    /// before dispatching on transport state in `process_begin`.
    ProcessBegin {
        /// Registered readiness events preserved for the resumed invocation.
        events: PollEvents,
    },
    /// `process_step` suspended after flushing outbound buffers in `process_data`; socket read
    /// processing (`process_read`) follows next upon resumption.
    ProcessRead {
        /// Registered readiness events preserved for the resumed invocation.
        events: PollEvents,
    },
    /// `process_step` suspended after asynchronous TCP connection establishment
    /// (`handle_tcp_connected` and `handle_transport_ready`) hit a barrier; `connected_flush`
    /// follows next.
    ProcessFlush,
    /// TLS frame processing loop suspended at an outbox barrier within `iocore_tls::tls_frames`.
    TlsFrames {
        /// True if the frames being processed arrived piggybacked on the final TLS handshake
        /// flight.
        handshake: bool,
    },
    /// TLS handshake completed and `handle_transport_ready` reached an outbox barrier; resumption
    /// enters `iocore_tls::handshake_ready` to flush initial requests and read piggybacked frames.
    HandshakeReady,
    /// Execution step finished its work, but terminal outbox settlement hit a barrier; resumption
    /// yields the recorded status code `rc`.
    End {
        /// Terminal return code for the driving step.
        rc: i32,
    },
    /// Execution step is parked awaiting completion of an off-thread deferred background task
    /// ([`Job`]); resumed via [`IoCore::complete_job`].
    Job,
}

/// Identifies the driver execution entry point that initiated a TLS handshake.
///
/// A nonblocking TCP `connect()` can either complete synchronously during `interest_step` (common
/// on local loopback interfaces) or complete asynchronously on a subsequent `process_step` when the
/// socket becomes writable. When the ensuing TLS handshake setup suspends at a log barrier or
/// offloads `Job::NewConn`, `IoCore` records `StepKind` in `self.hs_origin` so `after_handshake`
/// knows whether to return into the middle of the `interest_actions` loop or conclude
/// `process_step`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum StepKind {
    /// Initiated synchronously within `interest_step` upon immediate TCP connection.
    Interest,
    /// Initiated within `process_step` following asynchronous `EINPROGRESS` completion.
    Process,
}

/// Classification of deferred asynchronous background tasks offloaded from the main I/O loop when
/// configured by [`DeferPolicy`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum JobKind {
    /// DNS hostname resolution and optional PRNG reseeding task.
    Resolve,
    /// Cryptographic TLS session construction task (`TlsConnector::new_conn`).
    NewConn,
    /// Read-write server connectivity probing task (`isro` probe).
    Probe,
}

/// State tracking an in-flight asynchronous DNS resolution task and its associated server
/// generation.
///
/// Because `zk_proto::Connection` increments resolver generation tags whenever the server list is
/// updated via `set_servers`, resolution outcomes apply only if they match the active generation.
/// [`IoCore`] maintains a two-slot coalescing pipeline (`dns` for the currently executing lookup
/// and `dns_next` for the latest queued update): if multiple `Resolve` actions arrive while a
/// background DNS worker is already running, intermediate host lists are overwritten in `dns_next`
/// and identical host lists coalesce into the running job.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct DnsRun {
    /// Target resolver generation associated with this host set.
    pub(crate) generation: u64,
    /// Sequence of server host specifications to resolve.
    pub(crate) hosts: Arc<[HostSpec]>,
    /// Indicates whether random address shuffling (and PRNG reseeding) must follow successful
    /// resolution.
    pub(crate) shuffle: bool,
}

impl DnsRun {
    /// Assembles a background resolution job from this tracking record.
    fn job(&self) -> Job {
        Job::Resolve {
            generation: self.generation,
            hosts: Arc::clone(&self.hosts),
            shuffle: self.shuffle,
        }
    }
}

/// Error conditions encountered during outbound socket transmission passes (`plain_flush` and
/// `iocore_tls::tls_flush_with`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FlushError {
    /// Socket write syscall failed, mapped to `TransportFailure::Io` and `ZCONNECTIONLOSS`.
    Send(Errno),
    /// Polling descriptor writability returned an OS error, resulting in `ZSYSTEMERROR`.
    Poll(Errno),
    /// Blocking send during `close_flush` exceeded its configured deadline budget, triggering
    /// `ZOPERATIONTIMEOUT`.
    Timeout,
    /// TLS session engine rejected an encrypted record or encountered a latched failure, mapped to
    /// `ZCONNECTIONLOSS` during `close_flush`.
    Session,
}

/// Ingestion outcome from a single plaintext socket read turn (`read_part`).
enum Part {
    /// Successfully read the indicated number of bytes (`0` indicates `EWOULDBLOCK`).
    Got(usize),
    /// Reading directly into a frame body (`body_part`) completed a full protocol frame, and
    /// settling the resulting outputs hit an outbox barrier requiring caller draining.
    Barrier,
    /// Socket read encountered an unrecoverable I/O error or remote EOF.
    Failed(TransportFailure),
}

/// Terminal condition describing how the frame parsing loop (`frame_loop`) concluded.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FrameEnd {
    /// Frame ingestion suspended at an outbox barrier requiring external event draining before
    /// parsing can continue (`Progress::More`).
    More,
    /// Frame ingestion completed: no further complete frames remain buffered in memory, or the
    /// protocol decoder stalled on a negative frame length.
    Done,
    /// Frame processing halted because an asynchronous SASL handshake step is awaiting its host
    /// mechanism response, or because the native [`ReadGate`] closed under backpressure.
    Held,
    /// Frame ingestion halted at a multithreaded synchronous reply barrier with no trailing
    /// buffered input or pending protocol output, allowing the plaintext turn to complete directly
    /// while deferring hold resumption to the next turn's prelude (or discarding it on close).
    ReplyHold,
}

/// Control-flow indicator returned by `run_action` within the `interest_actions` loop.
enum Flow {
    /// Continue polling and evaluating subsequent protocol actions in the current interest turn.
    Continue,
    /// Suspend or conclude the interest evaluation turn and return the specified [`Progress`].
    Return(Progress),
}

/// Status descriptor returned upon concluding or yielding a driving turn in [`IoCore`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Progress {
    /// Driving step completed with the specified ZooKeeper status code (`ZKRS_DONE`).
    Done(i32),
    /// Execution paused at an outbox barrier or before a blocking call; caller must drain the
    /// [`Outbox`] and invoke `IoCore::resume` (`ZKRS_MORE`).
    More,
    /// Asynchronous/hybrid drivers only: step yielded to await completion of offloaded background
    /// tasks (`IoCore::take_jobs`), which will resume execution via `IoCore::complete_job`.
    Wait,
}

/// Policy configuring which blocking operations are offloaded to background worker threads.
///
/// - In drop-in C FFI shims (`polled` and `threaded`), all operations execute inline on the calling
///   thread ([`DeferPolicy::NONE`]) to preserve exact single-thread or dedicated-I/O-thread C
///   semantics.
/// - In the native OS-threaded Rust driver (`native`), DNS resolution is offloaded to a short-lived
///   background thread ([`DeferPolicy::DNS_THREAD`]) so slow `getaddrinfo` lookups during dynamic
///   reconfiguration never stall active socket heartbeats or traffic.
/// - In the Tokio driver (`tokio`), all blocking operations—DNS resolution, TLS certificate
///   loading, and synchronous `isro` connectivity probes—are offloaded to
///   `tokio::task::spawn_blocking` ([`DeferPolicy::ALL`]) so the cooperative async reactor task
///   never blocks a runtime worker.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DeferPolicy {
    /// Defer hostname resolution (`getaddrinfo`) and PRNG reseeding.
    pub resolve: bool,
    /// Defer cryptographic TLS session instantiation and certificate file loading.
    pub new_conn: bool,
    /// Defer read-write server connectivity probing (`isro`).
    pub probe: bool,
}

impl DeferPolicy {
    /// Execute all operations synchronously inline on the driving thread.
    pub const NONE: Self = Self {
        resolve: false,
        new_conn: false,
        probe: false,
    };
    /// Offload DNS resolution to a dedicated background thread; run TLS setup and probes inline.
    pub const DNS_THREAD: Self = Self {
        resolve: true,
        new_conn: false,
        probe: false,
    };
    /// Offload DNS resolution, TLS setup, and read-write probing to background blocking tasks.
    pub const ALL: Self = Self {
        resolve: true,
        new_conn: true,
        probe: true,
    };
}

/// Specification of a blocking task offloaded for deferred asynchronous execution.
#[derive(Debug)]
pub(crate) enum Job {
    /// Sequentially resolve all host entries for a specific resolver generation.
    Resolve {
        /// Monotonic generation identifier assigned to this resolution run.
        generation: u64,
        /// Host specifications to resolve in order.
        hosts: Arc<[HostSpec]>,
        /// Indicates whether PRNG reseeding must be triggered if at least one host resolves.
        shuffle: bool,
    },
    /// Initialize a new TLS client session context, loading any configured certificate files.
    NewConn {
        /// Intended application purpose (`Main` session vs `Probe`) for the TLS connection.
        purpose: TlsPurpose,
        /// Target server endpoint descriptor.
        endpoint: Endpoint,
    },
    /// Conduct a bounded read-write (`isro`) connectivity probe against a prospective server.
    Probe {
        /// Target endpoint to probe.
        endpoint: Endpoint,
        /// TLS verification profile, if probing over an encrypted port.
        tls: Option<TlsVerify>,
    },
}

/// Completion payload resulting from an offloaded background task executed by [`run_job`].
pub(crate) enum JobDone {
    /// Completed DNS resolution results matching an assigned generation.
    Resolved {
        /// Resolver generation identifier.
        generation: u64,
        /// Resolved address outcomes corresponding to input host entries.
        results: Vec<HostResolution>,
    },
    /// Initialized TLS session handle, or the setup error encountered.
    NewConn(Result<Box<dyn TlsConn>, TlsSetupError>),
    /// Read-write connectivity probe execution outcome.
    Probe {
        /// Probed target endpoint.
        endpoint: Endpoint,
        /// Probe completion outcome (`Done` or `Aborted`).
        end: ProbeEnd,
    },
}

/// Execution environment services supplied to background worker threads executing offloaded tasks.
pub(crate) struct JobCtx<'a, T: Tokens> {
    /// Host integration platform leaves providing DNS resolution and PRNG reseeding.
    pub(crate) host: &'a dyn HostLeaves<T>,
    /// Connectivity probing services and TLS connector reference.
    pub(crate) probe: ProbeCtx<'a>,
}

/// Executes an offloaded background task outside the `core` state lock.
///
/// The [`Unlocked`] witness token guarantees that neither `core` nor any leaf lock is held by the
/// calling thread, while permitting the outer `io` runner lock to remain held during inline
/// execution.
///
/// Execution flows:
/// - `Resolve`: Iterates sequentially through `hosts` invoking [`HostLeaves::resolve`], followed by
///   [`HostLeaves::rng_reseed`] if address shuffling is enabled and at least one host resolved to a
///   non-empty address list.
/// - `NewConn`: Delegates to [`TlsConnector::new_conn`] (or yields `Resource { at: ContextNew }` if
///   no TLS connector is configured).
/// - `Probe`: Delegates directly to [`crate::net::probe::probe`].
pub(crate) fn run_job<T: Tokens>(cx: &JobCtx<'_, T>, proof: &Unlocked, job: Job) -> JobDone {
    match job {
        Job::Resolve {
            generation,
            hosts,
            shuffle,
        } => {
            let mut results = Vec::with_capacity(hosts.len());
            let mut any = false;
            for (i, spec) in hosts.iter().enumerate() {
                let r = cx.host.resolve(proof, i, spec);
                any = any || r.result.as_ref().is_ok_and(|a| !a.is_empty());
                results.push(r);
            }
            if shuffle && any {
                cx.host.rng_reseed(proof);
            }
            JobDone::Resolved {
                generation,
                results,
            }
        }
        Job::NewConn { purpose, endpoint } => JobDone::NewConn(match cx.probe.tls {
            Some(c) => c.new_conn(proof, purpose, &endpoint),
            None => Err(TlsSetupError::Resource {
                at: TlsResourceAt::ContextNew,
            }),
        }),
        Job::Probe { endpoint, tls } => {
            let end = probe(&cx.probe, proof, &endpoint, tls);
            JobDone::Probe { endpoint, end }
        }
    }
}

/// Evaluates whether the native backpressure read gate permits reading additional frames from the
/// network.
///
/// Backpressure gating is intentionally restricted to established sessions
/// (`state.is_connected()`). While a session is still associating or negotiating SASL
/// authentication (`!state.is_connected()`), the gate always evaluates as open so that connection
/// handshakes and authentication challenges can complete even if the application's completion queue
/// is temporarily full from a prior epoch.
pub(crate) fn gate_open<T: Tokens>(cx: &StepCtx<'_, T>, state: State) -> bool {
    !state.is_connected() || cx.gate.is_none_or(ReadGate::is_open)
}

/// Blocks the calling thread until `sock` becomes writable or the given deadline expires.
///
/// Used exclusively during bounded graceful shutdown flushes ([`IoCore::close_flush`]).
///
/// # Errors
///
/// Returns `FlushError::Timeout` upon deadline expiration, or `FlushError::Poll` if the OS `poll`
/// syscall fails. Sockets reporting `POLLERR` or `POLLNVAL` return `Ok(())`, deferring concrete
/// error extraction to the subsequent `send` syscall.
pub(crate) fn wait_writable(
    clock: &dyn Clock,
    poll: &dyn Poll,
    sock: &TcpSock,
    deadline: Millis,
) -> Result<(), FlushError> {
    loop {
        let left = zk_proto::time::until(clock.now(), deadline);
        if left < 0 {
            return Err(FlushError::Timeout);
        }
        let ms = i32::try_from(left).unwrap_or(i32::MAX);
        let mut fds = [PollFd::new(sock, PollFlags::OUT)];
        match poll.poll(&mut fds, ms) {
            Err(Errno::INTR) => {}
            Err(e) => return Err(FlushError::Poll(e)),
            Ok(0) => return Err(FlushError::Timeout),
            Ok(_) => return Ok(()),
        }
    }
}

/// Transmits buffered plaintext bytes up to the specified deadline, polling for write readiness
/// before each transmission attempt.
fn send_until<T: Tokens>(
    cx: &StepCtx<'_, T>,
    sock: &TcpSock,
    buf: &[u8],
    deadline: Millis,
) -> Result<usize, FlushError> {
    loop {
        wait_writable(cx.clock, cx.poll, sock, deadline)?;
        match sock.send(buf) {
            Ok(n) if n > 0 => return Ok(n),
            Ok(_) | Err(Errno::INTR | Errno::WOULDBLOCK) => {}
            Err(e) => return Err(FlushError::Send(e)),
        }
    }
}

/// Translates a low-level transport failure into the corresponding POSIX `errno` value exposed to
/// legacy single threaded C callers (`zkrs_last_errno`).
///
/// Clean EOF transitions map to `EHOSTDOWN`, matching upstream `recv_buffer` semantics in
/// `zookeeper.c`. Failures originating from TLS certificate/key configuration validation set their
/// `EINVAL` hint directly inside `Connection::tls_config_failed` and return `None` here.
fn failure_errno(f: &TransportFailure) -> Option<i32> {
    match *f {
        TransportFailure::SocketCreate { errno }
        | TransportFailure::TcpRefused { errno }
        | TransportFailure::Io { errno }
        | TransportFailure::TlsResource { errno, .. }
        | TransportFailure::TlsHandshake { errno }
        | TransportFailure::Poll { errno } => Some(errno).filter(|&e| e != 0),
        TransportFailure::Eof => Some(zk_proto::errno::EHOSTDOWN),
        TransportFailure::TlsConfig { .. } => None,
    }
}

/// Ephemeral operational context assembled by a runtime driver for driving a single [`IoCore`]
/// step.
///
/// Bundles borrowed references to shared environment services (`core`, `host`, `tls`, `clock`,
/// `poll`), optional thread-interruption primitives (`wake`, `abort`), and runtime execution
/// policies (`cfg`, `flavor`, `defer`, `gate`). Because [`StepCtx`] holds only immutable borrows
/// (with `core` wrapped in a [`Mutex`]), it can be constructed cheaply on the stack for each turn.
pub(crate) struct StepCtx<'a, T: Tokens> {
    /// Guarded core state machine lock protecting protocol and shared driver states.
    pub(crate) core: &'a Mutex<Core<T>>,
    /// Host integration platform leaves providing OS primitives, RNG, and logger filters.
    pub(crate) host: &'a dyn HostLeaves<T>,
    /// TLS connection factory configured on this handle, or `None` if unencrypted.
    pub(crate) tls: Option<&'a dyn TlsConnector>,
    /// Monotonic time provider evaluating protocol timeouts and heartbeats.
    pub(crate) clock: &'a dyn Clock,
    /// Polling engine executing descriptor readiness queries and socket timeouts.
    pub(crate) poll: &'a dyn Poll,
    /// Self-pipe waker pair capable of interrupting blocking readiness loops (used by synchronous
    /// `isro` probes in multithreaded drivers).
    pub(crate) wake: Option<(&'a Waker, &'a WakeRx)>,
    /// Atomic flag indicating that handle closure or detachment has been requested, aborting
    /// active connection attempts and probes.
    pub(crate) abort: Option<&'a AtomicBool>,
    /// Immutable driver configuration limits and timeouts.
    pub(crate) cfg: &'a DriverConfig,
    /// Operational driver flavor selecting between single threaded (`St`), multithreaded (`Mt`),
    /// and native (`Native`) outbox queueing semantics.
    pub(crate) flavor: Flavor,
    /// Concurrency policy governing background offloading of blocking tasks.
    pub(crate) defer: DeferPolicy,
    /// Backpressure read gate controlling frame ingestion, active in native drivers.
    pub(crate) gate: Option<&'a ReadGate>,
}

impl<T: Tokens> StepCtx<'_, T> {
    /// Derives an ephemeral probing context for testing server read-write connectivity.
    pub(crate) fn probe_ctx(&self) -> ProbeCtx<'_> {
        ProbeCtx {
            poll: self.poll,
            clock: self.clock,
            wake: self.wake,
            abort: self.abort,
            tls: self.tls,
            cloexec: self.cfg.cloexec,
            phase_ms: self.cfg.probe_phase_ms,
        }
    }
}

/// Core I/O management container encapsulating the active transport, receive/transmit staging
/// buffers, continuation checkpoints, and deferred background task state for a client session.
///
/// The fields of [`IoCore`] are organized into five cooperating subsystems:
/// 1. **Transport and TLS Handshake Tracking** (`conn`, `hs_now`, `hs_origin`): Owns the active
///    socket or TLS transport and tracks when and from which step a TLS handshake began.
/// 2. **Two-Tier Receive Pipeline** (`rx`, `rx_fresh`, `rx_pending`, `rx_more_blocked`,
///    `rx_stalled`): Manages the 64 KiB primary read buffer (`rx`), the unconsumed zero-copy slice
///    length (`rx_fresh`), the spill buffer for partial frames or barrier-deferred plaintext
///    (`rx_pending`), and decoder stall/would-block flags.
/// 3. **Outbound Staging and Adaptive Reclamation** (`tx_copy`, `tx_sent`, `write_bursts`): Stages
///    serialized frames (or zero-copy lent frame buffers) inside a [`Zeroizing`] vector, tracks
///    partial nonblocking write progress, and reclaims oversized buffer allocations.
/// 4. **Continuation and Offload Coordination** (`cont`, `held`, `resolve`, `deferred`, `job`,
///    `dns`, `dns_next`, `bg_start`): Preserves suspended execution state across outbox barriers
///    (`Progress::More`) and coordinates foreground/background offloaded work (`Progress::Wait`).
/// 5. **Turn-Level Status and Diagnostics** (`last_rc`, `early`, `read_attempted`, `frames_seen`,
///    `stop_rc`, `errno`): Accumulates per-turn flags and error codes needed to synthesize exact
///    legacy C return codes and `errno` values.
pub struct IoCore {
    /// Active network transport handle (`None`, `Connecting`, `Plain`, `Tls`, or `Parked`).
    pub(crate) conn: Transport,
    /// Primary heap buffer dedicated to socket reads and TLS ciphertext ingestion
    /// (`READ_BUFFER_LEN` = 64 KiB), allocated without upfront zero-initialization.
    pub(crate) rx: RxBuf,
    /// Number of unprocessed plaintext bytes currently residing at the front of `rx` ready for
    /// zero-copy parsing by `frame_steps` before any spill to `rx_pending`.
    rx_fresh: usize,
    /// Secondary receive buffer holding plaintext bytes that spilled from `rx` across partial
    /// frames, barrier suspensions, SASL negotiation pauses, or TLS record decryption.
    pub(crate) rx_pending: Vec<u8>,
    /// Zeroized staging buffer holding outbound frame bytes copied or lent from
    /// `Connection::poll_transmit`.
    pub(crate) tx_copy: Zeroizing<Vec<u8>>,
    /// Number of bytes from the front of `tx_copy` already accepted by kernel socket `send` calls
    /// on a plaintext transport.
    pub(crate) tx_sent: u32,
    /// Adaptive burst history tracker managing reclamation of oversized transmit buffers.
    pub(crate) write_bursts: WriteBursts,
    /// Latched when a follow-up plaintext read for an open frame body returns `EWOULDBLOCK`,
    /// suppressing further follow-up reads in subsequent turns until a frame completes.
    pub(crate) rx_more_blocked: bool,
    /// Latched when frame deserialization encounters a corrupt negative length under
    /// `NegativeLen::StallUntilTimeout`, permanently halting socket reads until reconnect.
    pub(crate) rx_stalled: bool,
    /// Saved execution continuation checkpoint for resuming an interrupted driver step.
    pub(crate) cont: Cont,
    /// State machine tracking an active inline iterative host DNS resolution sequence.
    pub(crate) resolve: Option<ResolveRun>,
    /// Queue of blocking tasks offloaded during the current turn for asynchronous execution.
    pub(crate) deferred: Vec<Job>,
    /// Execution status code recorded at the conclusion of the most recent completed driving step.
    pub(crate) last_rc: i32,
    /// Early return status code cached when a synchronous connection attempt or TLS setup fails
    /// during `interest_step`.
    pub(crate) early: Option<i32>,
    /// Protocol action temporarily stashed when `poll_action` and `settle` simultaneously produce
    /// an action and an outbox barrier, or when `ProbeRw` waits for prior outbox items to drain.
    pub(crate) held: Option<Action>,
    /// Diagnostic flag indicating whether at least one socket `recv` syscall was attempted during
    /// the current `process_step` turn (used to distinguish `ZNOTHING` from `ZOK`).
    pub(crate) read_attempted: bool,
    /// Diagnostic flag indicating whether one or more complete wire frames were decoded and
    /// delivered to the protocol state machine during the current `process_step` turn.
    pub(crate) frames_seen: bool,
    /// First non-`ZOK` / non-`ZNOTHING` teardown or SASL error code encountered during the current
    /// `process_step` turn.
    pub(crate) stop_rc: Option<i32>,
    /// Monotonic timestamp recording when a TLS handshake sequence was initiated, preserved across
    /// initial log-drain barriers.
    pub(crate) hs_now: Option<Millis>,
    /// Originating execution entry point (`Interest` vs `Process`) that triggered TLS handshake
    /// initiation.
    pub(crate) hs_origin: StepKind,
    /// Specific deferred foreground task kind currently awaiting completion (`Cont::Job`).
    pub(crate) job: Option<JobKind>,
    /// Active asynchronous DNS resolution sequence currently executing in the foreground or
    /// background.
    pub(crate) dns: Option<DnsRun>,
    /// Coalesced subsequent DNS resolution sequence waiting for the active `dns` run to complete.
    pub(crate) dns_next: Option<DnsRun>,
    /// Offloaded background DNS resolution task ready to be spawned concurrently while an existing
    /// socket remains connected.
    pub(crate) bg_start: Option<Job>,
    /// Low-level OS `errno` cached from the most recent socket failure, exposed to C callers.
    pub(crate) errno: Option<i32>,
    /// Indicates that an `interest_step`, `process_step`, `resume`, or standalone flush turn is
    /// currently executing.
    in_turn: bool,
    /// Cached monotonic timestamp for the active turn, invalidated across potentially blocking
    /// operations.
    turn_at: Cell<Option<Millis>>,
}

impl std::fmt::Debug for IoCore {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("IoCore")
            .field("fd", &self.fd())
            .field("rx_pending", &self.rx_pending.len())
            .field("rx_stalled", &self.rx_stalled)
            .field("cont", &self.cont)
            .finish_non_exhaustive()
    }
}

impl IoCore {
    /// Instantiates an idle I/O core container with no active transport and a heap-allocated
    /// 64 KiB primary read buffer.
    #[must_use]
    pub fn new() -> Self {
        Self {
            conn: Transport::None,
            rx: RxBuf::new(),
            rx_fresh: 0,
            rx_pending: Vec::new(),
            tx_copy: Zeroizing::new(Vec::new()),
            tx_sent: 0,
            write_bursts: WriteBursts::default(),
            rx_more_blocked: false,
            rx_stalled: false,
            cont: Cont::None,
            resolve: None,
            deferred: Vec::new(),
            last_rc: ZOK,
            early: None,
            held: None,
            read_attempted: false,
            frames_seen: false,
            stop_rc: None,
            hs_now: None,
            hs_origin: StepKind::Interest,
            job: None,
            dns: None,
            dns_next: None,
            bg_start: None,
            errno: None,
            in_turn: false,
            turn_at: Cell::new(None),
        }
    }

    /// Returns the monotonic timestamp for protocol evaluations within the current turn.
    ///
    /// Within an active turn, the timestamp is sampled lazily on first demand and reused across
    /// subsequent millisecond-resolution timer checks unless invalidated by a potentially blocking
    /// DNS, socket connect, or TLS setup operation. Calls outside a turn sample the clock directly.
    fn turn_now(&self, clock: &dyn Clock) -> Millis {
        if !self.in_turn {
            return clock.now();
        }
        if let Some(at) = self.turn_at.get() {
            return at;
        }
        let at = clock.now();
        self.turn_at.set(Some(at));
        at
    }

    /// Invalidates the cached turn timestamp after a potentially blocking operation such as DNS
    /// resolution or socket connection setup.
    fn turn_refresh(&self) {
        self.turn_at.set(None);
    }

    /// Executes a turn entry closure with an uninitialized turn timestamp cache.
    fn in_turn<R>(&mut self, f: impl FnOnce(&mut Self) -> R) -> R {
        self.in_turn_at(None, f)
    }

    /// Executes a turn entry closure seeded with an optional pre-sampled timestamp `at` so the turn
    /// can reuse a caller's recent clock reading without carrying stale state from prior turns.
    fn in_turn_at<R>(&mut self, at: Option<Millis>, f: impl FnOnce(&mut Self) -> R) -> R {
        self.in_turn = true;
        self.turn_at.set(at);
        let r = f(self);
        self.in_turn = false;
        self.turn_at.set(None);
        r
    }

    /// Retrieves the active underlying socket descriptor, or `-1` if no socket is connected.
    #[must_use]
    pub fn fd(&self) -> RawSock {
        self.conn.sock().map_or(-1, TcpSock::raw)
    }

    /// Indicates whether the driver execution step is presently suspended at a continuation point.
    #[must_use]
    pub fn is_suspended(&self) -> bool {
        self.cont != Cont::None
    }

    /// Indicates whether an underlying network socket handle is currently open.
    #[must_use]
    pub fn has_socket(&self) -> bool {
        self.conn.sock().is_some()
    }

    /// Indicates whether the active transport is operating as an authenticated or handshaking TLS
    /// session.
    #[must_use]
    pub fn has_tls_session(&self) -> bool {
        matches!(self.conn, Transport::Tls(_))
    }

    /// Evaluates whether unconsumed received plaintext is already buffered in memory
    /// (`rx_pending`), permitting protocol frame progress without waiting for a network socket
    /// read.
    ///
    /// When this returns `true`, both `interest_finish` and the runtime poll loops override the
    /// poll timeout to `0` ms (and synthesize read readiness) so the reactor immediately processes
    /// the in-memory bytes instead of sleeping on a socket that may receive no further packets.
    #[must_use]
    pub fn buffered_ready(&self, suspended: bool, gate_open: bool) -> bool {
        !self.rx_pending.is_empty() && !self.rx_stalled && !suspended && gate_open
    }

    /// Abruptly tears down the active network transport, closing the OS socket descriptor and
    /// clearing all receive and transmit staging state.
    pub(crate) fn drop_transport(&mut self) {
        self.conn = Transport::None;
        self.rx_fresh = 0;
        self.rx_pending.clear();
        self.tx_copy.clear();
        self.tx_sent = 0;
        self.rx_more_blocked = false;
        self.rx_stalled = false;
    }

    /// Closes socket descriptors immediately in forked child processes, deliberately leaking the
    /// boxed TLS session state to avoid executing cryptographic destructors or sending alerts on
    /// a descriptor shared with the parent process.
    pub(crate) fn forked_close(&mut self) {
        match std::mem::replace(&mut self.conn, Transport::None) {
            Transport::Tls(t) => {
                let TlsTransport { sock, tls, .. } = t;
                std::mem::forget(tls);
                drop(sock);
            }
            other => drop(other),
        }
    }

    /// Drains all foreground blocking tasks deferred during the preceding execution turn for
    /// asynchronous scheduling by the runtime driver.
    pub(crate) fn take_jobs(&mut self) -> Vec<Job> {
        std::mem::take(&mut self.deferred)
    }

    /// Extracts any background DNS resolution task ready to be spawned concurrently alongside
    /// active socket I/O.
    pub(crate) fn take_bg_job(&mut self) -> Option<Job> {
        self.bg_start.take()
    }

    /// Ingests the outcome of an offloaded DNS resolution task and either resumes a waiting
    /// foreground step or applies background reconfiguration results to the active session.
    ///
    /// When `self.job.is_some()`, the driver suspended at [`Cont::Job`] with no open socket, so
    /// completion is routed through [`Self::complete_job`] to resume `interest_actions`. When
    /// `self.job.is_none()`, the resolution ran concurrently in the background while a socket was
    /// already open; this method validates the generation via [`Self::dns_target`], applies the
    /// resolved addresses via [`apply_results`] (which triggers transport teardown if ensemble
    /// rebalancing requires migrating to a new server), and settles any resulting outbox items.
    pub(crate) fn complete_resolve<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        done: JobDone,
        ob: &mut Outbox<T>,
        out: &mut InterestOut,
    ) -> Progress {
        if self.job.is_some() {
            return self.complete_job(cx, proof, done, ob, out);
        }
        let JobDone::Resolved {
            generation,
            results,
        } = done
        else {
            return Progress::Done(self.last_rc);
        };
        let Some((generation, shuffle)) = self.dns_target(cx, generation) else {
            return Progress::Done(self.last_rc);
        };
        let now = cx.clock.now();
        let barrier = {
            let mut g = cx.core.lock(proof);
            // Session termination does not require updated server addresses.
            // Dynamic updates must not interrupt active close draining.
            match g.conn.as_deref_mut() {
                Some(conn) if !conn.is_closing() => {
                    let _rc = apply_results(conn, generation, results, shuffle, now);
                }
                _ => {}
            }
            self.settle(&mut g, cx, ob)
        };
        if self.cont != Cont::None {
            // Suspended execution steps resume paused barrier processing during prelude.
            return Progress::More;
        }
        if barrier {
            self.cont = Cont::End { rc: self.last_rc };
            return Progress::More;
        }
        Progress::Done(self.last_rc)
    }

    /// Concludes the active deferred DNS resolution run and determines whether its outcome remains
    /// current or was superseded by a newer `set_servers` call.
    ///
    /// Returns `Some((generation, shuffle))` if no newer resolution was queued in `self.dns_next`.
    /// The active `run.generation` is reported rather than the initial job's `generation` because
    /// [`Self::defer_resolve`] coalesces duplicate host lists in-place by advancing
    /// `run.generation`.
    /// If `self.dns_next` holds a newer host list, the completed result is discarded as stale, the
    /// next job is promoted into `self.dns` and queued into `deferred` (if waiting) or `bg_start`
    /// (if running in the background), and `None` is returned.
    fn dns_target<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        generation: u64,
    ) -> Option<(u64, bool)> {
        let Some(run) = self.dns.take() else {
            return Some((generation, shuffle_on(cx.host)));
        };
        let Some(next) = self.dns_next.take() else {
            return Some((run.generation, run.shuffle));
        };
        let job = next.job();
        self.dns = Some(next);
        if self.job.is_some() {
            self.deferred.push(job);
        } else {
            self.bg_start = Some(job);
        }
        None
    }

    /// Evaluates protocol state transitions and calculates socket polling interest.
    ///
    /// Implements the full state progression of `zookeeper_interest` across four stages:
    /// 1. **Prelude**: Restores any requeued outbox items left behind by an interrupted callback,
    ///    delivers any pending SASL mechanism answers to the protocol core, and resumes any
    ///    suspended protocol barrier stages. If the prelude itself hits an outbox barrier, saves
    ///    [`Cont::InterestBegin`] and yields [`Progress::More`].
    /// 2. **State Validation (`interest_begin`)**: Invokes `Connection::begin_interest`. If the
    ///    session is unrecoverable or already closed, populates `out.rc` and returns immediately
    ///    with `out.written = false` (leaving caller descriptor and timeout pointers untouched).
    ///    Otherwise, executes any pending forced-close teardown snapshot (`run_pending_teardown`)
    ///    and drops condemned sockets.
    /// 3. **Protocol Action Dispatch (`interest_actions`)**: Loops through scheduled actions in
    ///    strict legacy C precedence:
    ///    - `Resolve`: Drives host resolution inline via [`crate::resolve::ResolveRun`] (yielding
    ///      before each blocking DNS call to flush pending logs) or offloads via `defer_resolve`.
    ///    - `Connect`: Opens nonblocking TCP sockets (`CLOEXEC` and `TCP_NODELAY`), logs
    ///      `ZkConnect`, initiates `start_connect`, updates cached peer addresses, and starts TLS
    ///      handshakes when immediate connection succeeds.
    ///    - `CloseTransport`: Tears down active socket transports and clears peer address caches.
    ///    - `Sasl(Dispose)`: Queues SASL session disposal notifications into the outbox.
    ///    - `ProbeRw`: Flushes any prior outbox items, then executes (or offloads) a bounded non-
    ///      blocking read-write (`isro`) probe outside of locks and reports outcomes via
    ///      `handle_rw_probe`.
    ///    - `Sasl(Start | Step)`: Queries local/peer socket addresses for `Start`, pushes the SASL
    ///      challenge item to the outbox, and yields [`Progress::More`].
    /// 4. **Interest Output Synthesis (`interest_finish`)**: In single threaded mode
    ///    (`Flavor::St`), flushes the outbound queue if a heartbeat ping was queued during step 3.
    ///    Invokes `Connection::finish_interest` and synthesizes `out.fd`, `out.bits`, and
    ///    `out.timeout_ms`: enabling write interest while buffers contain unsent bytes or TCP
    ///    `connect` is in progress, suppressing read interest when the decoder is stalled or the
    ///    backpressure gate is closed, and forcing `timeout_ms = 0` when unconsumed bytes wait in
    ///    `rx_pending`.
    pub(crate) fn interest_step<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        out: &mut InterestOut,
    ) -> Progress {
        self.in_turn(|io| io.interest_turn(cx, proof, ob, out))
    }

    fn interest_turn<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        out: &mut InterestOut,
    ) -> Progress {
        if self.job.is_some() {
            return Progress::Wait;
        }
        if self.cont != Cont::None {
            return Progress::Done(ZINVALIDSTATE);
        }
        self.early = None;
        self.held = None;
        if self.prelude(cx, proof, ob) {
            self.cont = Cont::InterestBegin;
            return Progress::More;
        }
        self.interest_begin(cx, proof, ob, out)
    }

    /// Consumes network descriptor readiness events, flushes outbound queues, ingests incoming
    /// wire bytes, and drives the protocol frame deserialization loop.
    ///
    /// Implements the full execution pipeline of `zookeeper_process` and `check_events`:
    /// 1. **Prelude**: Restores requeued outbox events, applies pending SASL answers, and resumes
    ///    suspended protocol barrier stages (yielding at [`Cont::ProcessBegin`] if a barrier is
    ///    encountered).
    /// 2. **Health and Transport Check (`process_begin`)**: Validates that the connection exists
    ///    and is not in an unrecoverable state (returning `ZINVALIDSTATE` otherwise). Runs any
    ///    pending forced close teardown and verifies that an active transport remains open.
    /// 3. **Asynchronous TCP Connect Completion (`process_connecting`)**:
    ///    - When `events.write` is signaled on a [`Transport::Connecting`] socket, queries
    ///      `getsockopt(SO_ERROR)`. Non-zero errors report `TransportFailure::TcpRefused`.
    ///    - Successful plaintext connections transition to [`Transport::Plain`], notify
    ///      `handle_tcp_connected` and `handle_transport_ready`, perform an immediate outbound
    ///      flush (`connected_flush`), and conclude the turn with `Done(ZOK)`.
    ///    - Successful TLS connections initiate the cryptographic handshake via
    ///      [`iocore_tls::handshake_start`].
    ///    - If `events.read` is signaled *without* `events.write` (e.g., when a single threaded
    ///      event loop maps `POLLHUP` into `POLLIN`), `connecting_read` performs a 4-byte probe
    ///      read to surface remote resets or EOF.
    /// 4. **In-Flight TLS Handshake (`iocore_tls::handshake_step`)**: Advances cryptographic record
    ///    exchange when the transport is a handshaking [`Transport::Tls`].
    /// 5. **Quarantined Socket Monitoring (`process_parked`)**: Reads from [`Transport::Parked`]
    ///    sockets solely to detect remote EOF or reset while awaiting receive timeout expiration.
    /// 6. **Outbound Data Flush (`process_data`)**: On `events.write`, flushes staged plaintext or
    ///    TLS ciphertext to the socket and credits transmitted byte counts to the protocol core.
    /// 7. **Inbound Frame Ingestion (`process_read`)**: Checks [`ReadGate`] backpressure, reads
    ///    from the socket (using zero-copy `body_part` or two-tier `rx`/`rx_pending` buffers for
    ///    plaintext, or `iocore_tls::tls_read` for TLS), and drives `frame_loop` until buffered
    ///    frames are exhausted or an outbox barrier pauses execution.
    /// 8. **Return Code Synthesis (`process_rc`)**: Computes the final turn return code (`stop_rc`
    ///    on error, `ZOK` if frames were decoded, `ZNOTHING` if a read attempt yielded no complete
    ///    frame, or `ZOK` for write-only turns).
    pub(crate) fn process_step<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        events: PollEvents,
        ob: &mut Outbox<T>,
    ) -> Progress {
        self.in_turn(|io| io.process_turn(cx, proof, events, ob))
    }

    fn process_turn<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        events: PollEvents,
        ob: &mut Outbox<T>,
    ) -> Progress {
        if self.job.is_some() {
            return Progress::Wait;
        }
        if self.cont != Cont::None {
            return Progress::Done(ZINVALIDSTATE);
        }
        self.read_attempted = false;
        self.frames_seen = false;
        self.stop_rc = None;
        if self.prelude(cx, proof, ob) {
            self.cont = Cont::ProcessBegin { events };
            return Progress::More;
        }
        self.process_begin(cx, proof, events, ob)
    }

    /// Resumes an interrupted driving step from its stored [`Cont`] continuation checkpoint after
    /// the caller has drained the [`Outbox`].
    ///
    /// Before branching to the saved continuation point, [`Self::prelude`] runs to restore any
    /// items requeued during callback unwinding, deliver any [`SaslAnswer`] produced by the host's
    /// SASL callback during the outbox drain, and resume any [`zk_proto::Stopped`] stage inside the
    /// protocol engine. If `prelude` itself encounters another barrier (for example, a multi-stage
    /// teardown or auth completion continuing after a lockpoint), `self.cont` is preserved and
    /// [`Progress::More`] is returned again so the caller drains the newly generated items before
    /// the saved [`Cont`] branch executes.
    pub(crate) fn resume<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        out: &mut InterestOut,
    ) -> Progress {
        self.in_turn(|io| io.resume_turn(cx, proof, ob, out))
    }

    fn resume_turn<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        out: &mut InterestOut,
    ) -> Progress {
        if self.job.is_some() {
            return Progress::Wait;
        }
        let cont = std::mem::replace(&mut self.cont, Cont::None);
        if cont == Cont::None {
            return Progress::Done(self.last_rc);
        }
        if self.prelude(cx, proof, ob) {
            // Prelude hit a secondary barrier. Keep the original continuation parked until all
            // prelude barrier outputs have been drained.
            self.cont = cont;
            return Progress::More;
        }
        match cont {
            Cont::None | Cont::Job => Progress::Done(self.last_rc),
            Cont::InterestBegin => self.interest_begin(cx, proof, ob, out),
            Cont::Interest => self.interest_actions(cx, proof, ob, out),
            Cont::InterestEnd { out: saved } => {
                *out = saved;
                self.done(saved.rc)
            }
            Cont::Handshake => match std::mem::replace(&mut self.conn, Transport::None) {
                Transport::Connecting {
                    sock,
                    ep,
                    tls: Some(verify),
                } => {
                    let p = iocore_tls::handshake_start(self, cx, proof, sock, ep, verify, ob);
                    self.after_handshake_progress(cx, proof, ob, out, p)
                }
                other => {
                    self.conn = other;
                    self.after_handshake(cx, proof, ob, out, ZOK)
                }
            },
            Cont::AfterHandshake { rc } => self.after_handshake(cx, proof, ob, out, rc),
            Cont::ProcessBegin { events } => self.process_begin(cx, proof, events, ob),
            Cont::ProcessRead { events } => self.process_read(cx, proof, events, ob),
            Cont::ProcessFlush => self.connected_flush(cx, proof, ob),
            Cont::Process { .. } => self.plain_frames(cx, proof, ob),
            Cont::TlsFrames { handshake } => {
                let p = iocore_tls::tls_frames(self, cx, proof, ob, handshake);
                self.track(p)
            }
            Cont::HandshakeReady => {
                let p = iocore_tls::handshake_ready(self, cx, proof, ob);
                self.track(p)
            }
            Cont::End { rc } => self.done(rc),
        }
    }

    /// Delivers the completion outcome of an offloaded foreground background task ([`JobDone`]) to
    /// the suspended execution step and resumes driving.
    ///
    /// Invoked by the native threaded driver or Tokio task driver after a step returned
    /// [`Progress::Wait`]:
    /// - **`JobDone::Resolved`**: Validates via [`Self::dns_target`] whether a newer `set_servers`
    ///   host list arrived while DNS was in flight. If superseded, queues the replacement job and
    ///   returns [`Progress::Wait`] again. Otherwise, applies the resolved addresses under the
    ///   `core` lock and resumes `interest_actions`.
    /// - **`JobDone::NewConn`**: Extracts the waiting [`Transport::Connecting`] socket, completes
    ///   TLS setup via [`iocore_tls::handshake_with`], and routes the result back to the
    ///   originating step (`Interest` or `Process`) via `after_handshake_progress`.
    /// - **`JobDone::Probe`**: Reports the `isro` probe outcome to `Connection::handle_rw_probe`
    ///   via [`Self::apply_probe`] and resumes `interest_actions`.
    pub(crate) fn complete_job<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        done: JobDone,
        ob: &mut Outbox<T>,
        out: &mut InterestOut,
    ) -> Progress {
        self.job = None;
        if self.cont == Cont::Job {
            self.cont = Cont::None;
        }
        match done {
            JobDone::Resolved {
                generation,
                results,
            } => {
                self.job = Some(JobKind::Resolve);
                let Some((generation, shuffle)) = self.dns_target(cx, generation) else {
                    // A newer ensemble reconfiguration arrived while DNS was running. Wait for the
                    // newly queued replacement resolution job.
                    self.cont = Cont::Job;
                    return Progress::Wait;
                };
                self.job = None;
                let now = cx.clock.now();
                let barrier = {
                    let mut g = cx.core.lock(proof);
                    if let Some(conn) = g.conn.as_deref_mut() {
                        let _rc = apply_results(conn, generation, results, shuffle, now);
                    }
                    self.settle(&mut g, cx, ob)
                };
                if barrier {
                    self.cont = Cont::Interest;
                    return Progress::More;
                }
                self.interest_actions(cx, proof, ob, out)
            }
            JobDone::NewConn(result) => match std::mem::replace(&mut self.conn, Transport::None) {
                Transport::Connecting {
                    sock, tls: Some(_), ..
                } => {
                    let p = iocore_tls::handshake_with(self, cx, proof, ob, sock, result);
                    self.after_handshake_progress(cx, proof, ob, out, p)
                }
                other => {
                    // Discard TLS session if connection attempt terminated while background job
                    // was in flight.
                    self.conn = other;
                    self.after_handshake(cx, proof, ob, out, ZOK)
                }
            },
            JobDone::Probe { endpoint, end } => {
                if self.apply_probe(cx, proof, ob, endpoint, end) {
                    self.cont = Cont::Interest;
                    return Progress::More;
                }
                self.interest_actions(cx, proof, ob, out)
            }
        }
    }

    /// Performs a single opportunistic nonblocking flush pass for single threaded client
    /// submissions (`Polled::submit_by` and single threaded ping flushes in `interest_finish`).
    ///
    /// Mirrors legacy C `adaptor_send_queue` in `st_adaptor.c`, which immediately calls
    /// `flush_send_queue(zh, 0)` upon request submission or ping queueing. Returns `false` if a
    /// socket write error occurred, prompting the caller to trigger forced transport teardown.
    pub(crate) fn flush_once<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
    ) -> bool {
        // When invoked from an interest turn (e.g. flushing a queued ping), execute within the
        // enclosing turn scope.
        if self.in_turn {
            return self.flush_turn(cx, proof);
        }
        self.in_turn(|io| io.flush_turn(cx, proof))
    }

    /// Flushes queued outbound frames using the caller's pre-sampled timestamp `now` so request
    /// submission and its immediate flush share a single clock sample.
    pub(crate) fn flush_once_at<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        now: Millis,
    ) -> bool {
        // Preserve the enclosing turn's timestamp when flushing from inside an active turn.
        if self.in_turn {
            return self.flush_turn(cx, proof);
        }
        self.in_turn_at(Some(now), |io| io.flush_turn(cx, proof))
    }

    fn flush_turn<T: Tokens>(&mut self, cx: &StepCtx<'_, T>, proof: &mut Unlocked) -> bool {
        match &self.conn {
            Transport::Plain(_) => self.plain_flush(cx, proof, None).is_ok(),
            Transport::Tls(t) if !t.tls.is_handshaking() => {
                iocore_tls::tls_flush(self, cx, proof, None).is_ok()
            }
            _ => true,
        }
    }

    /// Coordinates bounded outbound buffer draining, server acknowledgment waiting, and graceful
    /// TLS shutdown during client close (`zookeeper_close`).
    ///
    /// Replicates the legacy C `zookeeper_close` wire shutdown sequence across three bounded
    /// phases without holding the `core` mutex across blocking `poll`/`send` calls:
    /// 1. **Outbound Flush Phase (`cfg.close_flush_ms`)**: If the session is in the connected data
    ///    phase with an active transport, flushes all pending outbound frames (including the
    ///    `CLOSE_OP` request queued by the caller) using polling writes up to `close_flush_ms`.
    ///    Flush timeouts map to `ZOPERATIONTIMEOUT`, poll failures to `ZSYSTEMERROR`, and socket or
    ///    TLS session errors to `ZCONNECTIONLOSS`.
    /// 2. **Server Reply Wait Phase (`cfg.close_wait_ms`)**: If the flush succeeded (`ZOK`), polls
    ///    the socket for `POLLIN` up to `close_wait_ms` to allow the server to process `CLOSE_OP`
    ///    and reply (or close its end of the TCP stream), emitting `CloseWaitTimeout` or
    ///    `CloseWaitError` log records if the wait times out or faults.
    /// 3. **TLS `close_notify` Phase**: On TLS connections where all application data was cleanly
    ///    transmitted and acknowledged, transmits a best effort TLS `close_notify` alert within the
    ///    combined `close_flush_ms + close_wait_ms` budget from `start`.
    pub(crate) fn close_flush<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
    ) -> i32 {
        let start = cx.clock.now();
        let session = {
            let g = cx.core.lock(proof);
            g.conn
                .as_deref()
                .filter(|c| c.state().is_connected() && c.has_transport())
                .map(|c| c.client_id().session_id)
        };
        let Some(session) = session.filter(|_| self.conn.data_phase()) else {
            return ZOK;
        };
        // Phase 1: Flush pending outbound requests and `CLOSE_OP` within `close_flush_ms`.
        let flush_deadline = start.saturating_add_ms(u64::from(cx.cfg.close_flush_ms));
        let flushed = if matches!(self.conn, Transport::Tls(_)) {
            iocore_tls::tls_flush_with(self, cx, proof, Some(flush_deadline))
        } else {
            self.plain_flush(cx, proof, Some(flush_deadline))
        };
        let rc = match flushed {
            Ok(()) => ZOK,
            Err(FlushError::Timeout) => ZOPERATIONTIMEOUT,
            Err(FlushError::Poll(_)) => ZSYSTEMERROR,
            Err(FlushError::Send(_) | FlushError::Session) => ZCONNECTIONLOSS,
        };
        if rc != ZOK {
            return rc;
        }
        // Phase 2: Poll for server acknowledgement (`POLLIN` or EOF) up to `close_wait_ms`.
        // Wait concludes upon receiving POLLIN, an error condition, or timeout expiration.
        let wait_deadline = cx
            .clock
            .now()
            .saturating_add_ms(u64::from(cx.cfg.close_wait_ms));
        let mut waited = None;
        if let Some(sock) = self.conn.sock() {
            loop {
                let left = zk_proto::time::until(cx.clock.now(), wait_deadline);
                if left < 0 {
                    break;
                }
                let ms = i32::try_from(left).unwrap_or(i32::MAX);
                let mut fds = [PollFd::new(sock, PollFlags::IN)];
                let r = cx.poll.poll(&mut fds, ms);
                if r != Err(Errno::INTR) {
                    waited = Some(r);
                    break;
                }
            }
        }
        let id = LogArg::Ptr(u64::from_ne_bytes(session.to_ne_bytes()));
        let line = match waited {
            Some(Ok(0)) => Some((
                LogSite::CloseWaitTimeout,
                LogArg::I64(i64::from(cx.cfg.close_wait_ms)),
            )),
            Some(Err(_)) => Some((LogSite::CloseWaitError, LogArg::I64(-1))),
            Some(Ok(_)) | None => None,
        };
        if let Some((site, arg)) = line {
            if let Some(rec) = log_record(cx.host, site, vec![arg, id]) {
                ob.push(Item::Log(rec));
            }
        }
        // Phase 3: Emit a graceful TLS `close_notify` alert if all outbound records were flushed.
        if let Transport::Tls(t) = &mut self.conn {
            let all_out = t.pending.is_none()
                && t.ct_out.is_empty()
                && t.unacked == 0
                && !t.tls.has_ciphertext();
            if all_out {
                let budget = u64::from(cx.cfg.close_flush_ms)
                    .saturating_add(u64::from(cx.cfg.close_wait_ms));
                iocore_tls::close_notify(t, cx, start.saturating_add_ms(budget));
            }
        }
        rc
    }

    /// Queries the local and remote socket addresses (`getsockname` and `getpeername`) associated
    /// with the active transport for Cyrus SASL / GSSAPI channel binding initialization.
    pub(crate) fn sasl_addrs(&self) -> Result<(SocketAddr, SocketAddr), zk_proto::SaslAddr> {
        let Some(sock) = self.conn.sock() else {
            return Err(zk_proto::SaslAddr::Local);
        };
        let local = sock.local_addr().map_err(|_| zk_proto::SaslAddr::Local)?;
        let peer = sock.peer_addr().map_err(|_| zk_proto::SaslAddr::Peer)?;
        Ok((local, peer))
    }
}

/// Shared operational routines handling step transitions, outbox settlement, and protocol actions.
impl IoCore {
    /// Concludes the active step with the designated status code and resets suspension state.
    pub(crate) fn done(&mut self, rc: i32) -> Progress {
        self.cont = Cont::None;
        self.last_rc = rc;
        Progress::Done(rc)
    }

    /// Records the progression outcome of a subsidiary driving routine (such as `iocore_tls`
    /// helpers), updating `last_rc` and clearing `cont` when the step reaches `Progress::Done`.
    pub(crate) fn track(&mut self, p: Progress) -> Progress {
        match p {
            Progress::Done(rc) => self.done(rc),
            other => other,
        }
    }

    /// Synchronizes driver transport state with the protocol state machine and drains generated
    /// protocol outputs into the caller's [`Outbox`].
    ///
    /// This method is the universal post-mutation synchronization point invoked at the end of
    /// every `core` lock scope in [`IoCore`] and [`crate::iocore_tls`]. If a protocol state
    /// transition inside `Connection` tore down the transport (`!conn.has_transport()`), `settle`
    /// immediately closes the OS socket via [`Self::drop_transport`] and clears `g.peer` *before*
    /// pumping protocol outputs into `ob`. This ordering invariant guarantees that no watcher or
    /// completion callback invoked when the caller drains `ob` can ever observe a stale open file
    /// descriptor or stale remote peer address.
    ///
    /// Returns `true` if `g.pump` encountered an outbox barrier requiring the current step to
    /// suspend and yield [`Progress::More`].
    pub(crate) fn settle<T: Tokens>(
        &mut self,
        g: &mut Core<T>,
        cx: &StepCtx<'_, T>,
        ob: &mut Outbox<T>,
    ) -> bool {
        let has = g.conn.as_deref().is_some_and(Connection::has_transport);
        if !has {
            if self.has_socket() {
                self.drop_transport();
            }
            g.peer = None;
        }
        g.pump(cx.flavor, ob, cx.host)
    }

    /// Delivers a low-level [`TransportFailure`] to the protocol core, caches the corresponding
    /// POSIX `errno`, and settles resulting outbox notifications.
    ///
    /// Returns `(rc, barrier)`, where `rc` is the protocol error code returned by
    /// `Connection::handle_transport_failed` and `barrier` indicates whether `settle` paused at an
    /// outbox barrier.
    pub(crate) fn fail<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        f: TransportFailure,
    ) -> (i32, bool) {
        let now = cx.clock.now();
        let mut g = cx.core.lock(proof);
        let errno = failure_errno(&f);
        let rc = g
            .conn
            .as_deref_mut()
            .map_or(ZOK, |c| c.handle_transport_failed(f, now));
        if rc != ZOK {
            self.errno = errno;
        }
        let barrier = self.settle(&mut g, cx, ob);
        (rc, barrier)
    }

    /// Retrieves and clears the recorded operating system `errno` from the most recent transport
    /// error.
    pub(crate) fn take_errno(&mut self) -> Option<i32> {
        self.errno.take()
    }

    /// Reports a transport failure to the protocol core, latches the first error code in
    /// `self.stop_rc`, and concludes the execution turn (or suspends at [`Cont::End`] if teardown
    /// outputs hit an outbox barrier).
    pub(crate) fn fail_end<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        f: TransportFailure,
    ) -> Progress {
        let (rc, barrier) = self.fail(cx, proof, ob, f);
        self.stop_rc.get_or_insert(rc);
        self.end_after(barrier, rc)
    }

    /// Concludes the step with `rc`, or suspends execution at [`Cont::End`] if outbox events hit a
    /// barrier that must be drained before returning `rc` to the caller.
    pub(crate) fn end_after(&mut self, barrier: bool, rc: i32) -> Progress {
        if barrier {
            self.cont = Cont::End { rc };
            return Progress::More;
        }
        self.done(rc)
    }

    /// Calculates the definitive return code for a `process_step` turn following legacy C
    /// `zookeeper_process` precedence rules:
    /// 1. If a teardown or SASL error occurred during the turn, return its latched `stop_rc`.
    /// 2. Otherwise, if at least one complete wire frame was parsed (`frames_seen`), return `ZOK`.
    /// 3. Otherwise, if a socket read syscall was attempted (`read_attempted`) and yielded no
    ///    complete frame, return `ZNOTHING`.
    /// 4. Otherwise (e.g., a write-only readiness turn), return `ZOK`.
    pub(crate) fn process_rc(&self) -> i32 {
        self.stop_rc.unwrap_or(if self.frames_seen {
            ZOK
        } else if self.read_attempted {
            ZNOTHING
        } else {
            ZOK
        })
    }

    /// Executes step initialization at the start of `interest_step`, `process_step`, and `resume`:
    /// 1. Restores any previously requeued outbox items (returning `true` immediately so they are
    ///    drained before any new protocol work runs).
    /// 2. Applies any pending [`SaslAnswer`] recorded by the host or native SASL mechanism since
    ///    the last yield.
    /// 3. Resumes any [`zk_proto::Stopped`] stage inside `Connection` that parked at a barrier on
    ///    the preceding turn.
    /// 4. Settles any outputs generated by the SASL answer or resumed barrier job.
    fn prelude<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
    ) -> bool {
        let mut g = cx.core.lock(proof);
        if g.restore_requeued(ob) {
            return true;
        }
        // Defer sampling the clock unless a SASL response or a halted stage requires a timestamp.
        if g.sasl.is_none() && g.stopped.is_none() {
            return self.settle(&mut g, cx, ob);
        }
        let now = self.turn_now(cx.clock);
        if let Some(answer) = g.sasl.take() {
            if let Some(conn) = g.conn.as_deref_mut() {
                let rc = match answer {
                    SaslAnswer::Started { generation, result } => {
                        conn.handle_sasl_started(generation, result, now)
                    }
                    SaslAnswer::Stepped { generation, result } => {
                        conn.handle_sasl_stepped(generation, result, now)
                    }
                };
                if rc != ZOK {
                    self.stop_rc.get_or_insert(rc);
                }
            }
        }
        g.resume_stopped(now);
        self.settle(&mut g, cx, ob)
    }

    /// Conducts preliminary interest validation, executes any deferred forced-close teardown, and
    /// transitions into the action evaluation loop.
    fn interest_begin<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        out: &mut InterestOut,
    ) -> Progress {
        let now = self.turn_now(cx.clock);
        let barrier = {
            let mut g = cx.core.lock(proof);
            let Some(conn) = g.conn.as_deref_mut() else {
                out.rc = ZINVALIDSTATE;
                out.written = false;
                return self.done(ZINVALIDSTATE);
            };
            if let Err(rc) = conn.begin_interest(now) {
                out.rc = rc;
                out.written = false;
                return self.done(rc);
            }
            // If an asynchronous forced close (e.g., from a failed single threaded submit flush)
            // captured a teardown snapshot, begin failing its pending requests now.
            conn.run_pending_teardown(now);
            self.settle(&mut g, cx, ob)
        };
        if barrier {
            self.cont = Cont::Interest;
            return Progress::More;
        }
        self.interest_actions(cx, proof, ob, out)
    }

    /// Drives the iterative protocol action loop during an interest evaluation turn, then
    /// delegates to `interest_finish` to populate output descriptors.
    ///
    /// Loop structure on each iteration:
    /// 1. **Inline DNS Resolution (`self.resolve`)**: If a synchronous [`ResolveRun`] is active
    ///    (`DeferPolicy::resolve == false`), advances it via `ResolveRun::run`. The resolver yields
    ///    `ResolveEnd::More` whenever diagnostic log records must be flushed before the next
    ///    blocking `getaddrinfo` or `rng_reseed` leaf call.
    /// 2. **Early Failure Short-Circuit (`self.early`)**: If a preceding `Connect` or synchronous
    ///    TLS setup failed during this turn, breaks out of the action loop immediately to report
    ///    the failure in `interest_finish`.
    /// 3. **Action Polling (`self.held` / `g.set_servers` / `conn.poll_action`)**: First consumes
    ///    any action stashed in `self.held` across a prior barrier. Otherwise, locks `core` and
    ///    checks `g.set_servers` (giving dynamic ensemble updates priority as a synthetic
    ///    `Action::Resolve`) before calling `conn.poll_action(now)`. If `settle` reports an outbox
    ///    barrier alongside a polled action, the action is stashed in `self.held` and the turn
    ///    suspends at [`Cont::Interest`] so the barrier drains before `run_action` executes.
    /// 4. **Action Execution (`run_action`)**: Executes the polled action outside the `core` lock.
    fn interest_actions<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        out: &mut InterestOut,
    ) -> Progress {
        loop {
            if let Some(run) = self.resolve.as_mut() {
                let rcx = ResolveCtx {
                    core: cx.core,
                    host: cx.host,
                    clock: cx.clock,
                    flavor: cx.flavor,
                };
                let end = run.run(&rcx, proof, ob);
                // Adopt the post-lookup timestamp sampled by the resolution run, or clear the turn
                // timestamp if no lookup occurred, so the next evaluation samples a fresh reading.
                self.turn_at.set(run.last_at());
                match end {
                    ResolveEnd::More => {
                        self.cont = Cont::Interest;
                        return Progress::More;
                    }
                    ResolveEnd::Done { .. } => {
                        self.resolve = None;
                        let barrier = {
                            let mut g = cx.core.lock(proof);
                            let stopped = g.stopped.is_some();
                            self.settle(&mut g, cx, ob) || stopped
                        };
                        if barrier {
                            self.cont = Cont::Interest;
                            return Progress::More;
                        }
                    }
                }
            }
            if self.early.is_some() {
                break;
            }
            let action = match self.held.take() {
                Some(a) => a,
                None => {
                    let now = self.turn_now(cx.clock);
                    let (action, barrier) = {
                        let mut g = cx.core.lock(proof);
                        // Outstanding dynamic server list updates take precedence and run as
                        // resolution actions before polling core actions.
                        let action = match g.set_servers.take() {
                            Some((generation, hosts)) => Some(Action::Resolve {
                                generation,
                                hosts,
                                initial: false,
                            }),
                            None => g.conn.as_deref_mut().and_then(|c| c.poll_action(now)),
                        };
                        (action, self.settle(&mut g, cx, ob))
                    };
                    let Some(action) = action else {
                        if barrier {
                            self.cont = Cont::Interest;
                            return Progress::More;
                        }
                        break;
                    };
                    if barrier {
                        // A protocol output barrier coincided with the polled action. Stash the
                        // action in `self.held` and drain the outbox before executing the action.
                        self.held = Some(action);
                        self.cont = Cont::Interest;
                        return Progress::More;
                    }
                    action
                }
            };
            let flow = self.run_action(cx, proof, ob, action);
            self.turn_refresh();
            match flow {
                Flow::Continue => {}
                Flow::Return(p) => return p,
            }
        }
        self.interest_finish(cx, proof, ob, out)
    }

    /// Dispatches an individual protocol [`Action`] encountered during the `interest_actions` loop.
    ///
    /// Action semantics:
    /// - **`Action::Resolve`**: Skipped if the connection is already closing (so shutdown flushes
    ///   never block behind DNS lookups). Otherwise, either delegates to [`Self::defer_resolve`]
    ///   when `cx.defer.resolve` is enabled, or installs a synchronous [`ResolveRun`] in
    ///   `self.resolve` to be driven at the top of the `interest_actions` loop.
    /// - **`Action::Connect`**: Delegates to [`Self::connect`] to open a nonblocking TCP socket
    ///   and initiate connection establishment.
    /// - **`Action::CloseTransport`**: Drops the active socket transport and clears `Core::peer`.
    /// - **`Action::Sasl`**:
    ///   - `Dispose` pushes an outbox item and continues without yielding.
    ///   - `Start` queries local and peer socket addresses via [`Self::sasl_addrs`]. On success,
    ///     pushes the `Start` item to the outbox and yields `Progress::More` so the host or native
    ///     SASL mechanism can initialize and return a [`SaslAnswer::Started`]. If querying socket
    ///     addresses fails, reports `handle_sasl_addr_failed` to the protocol core.
    ///   - `Step` pushes the challenge token to the outbox and yields `Progress::More`.
    /// - **`Action::ProbeRw`**: If any outbox items are pending, stashes the `ProbeRw` action back
    ///   into `self.held` and yields `Progress::More` so all queued logs/events are delivered
    ///   before the probe runs. Once the outbox is empty, either offloads [`Job::Probe`] (under
    ///   `cx.defer.probe`) or executes [`crate::net::probe::probe`] inline outside of locks.
    fn run_action<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        action: Action,
    ) -> Flow {
        let more = |io: &mut Self| {
            io.cont = Cont::Interest;
            Flow::Return(Progress::More)
        };
        match action {
            Action::Resolve {
                generation, hosts, ..
            } => {
                // Session termination does not require address resolution.
                // Avoid stalling close flushes behind slow DNS lookups.
                let closing = {
                    let g = cx.core.lock(proof);
                    g.conn.as_deref().is_some_and(Connection::is_closing)
                };
                if closing {
                    return Flow::Continue;
                }
                if cx.defer.resolve {
                    return self.defer_resolve(cx, generation, hosts);
                }
                self.resolve = Some(ResolveRun::new(generation, hosts));
                Flow::Continue
            }
            Action::Connect { endpoint, tls } => self.connect(cx, proof, ob, endpoint, tls),
            Action::CloseTransport => {
                self.drop_transport();
                cx.core.lock(proof).peer = None;
                Flow::Continue
            }
            Action::Sasl(call) => match call.kind {
                SaslCallKind::Dispose => {
                    ob.push(Item::Sasl(SaslItem { call, addrs: None }));
                    Flow::Continue
                }
                SaslCallKind::Start => match self.sasl_addrs() {
                    Ok(addrs) => {
                        ob.push(Item::Sasl(SaslItem {
                            call,
                            addrs: Some(addrs),
                        }));
                        more(self)
                    }
                    Err(which) => {
                        let now = cx.clock.now();
                        let barrier = {
                            let mut g = cx.core.lock(proof);
                            if let Some(conn) = g.conn.as_deref_mut() {
                                let _rc = conn.handle_sasl_addr_failed(call.generation, which, now);
                            }
                            self.settle(&mut g, cx, ob)
                        };
                        if barrier { more(self) } else { Flow::Continue }
                    }
                },
                SaslCallKind::Step(_) => {
                    ob.push(Item::Sasl(SaslItem { call, addrs: None }));
                    more(self)
                }
            },
            Action::ProbeRw { endpoint, tls } => {
                if !ob.is_empty() {
                    // Drain any pending outbox items before blocking or offloading the probe.
                    self.held = Some(Action::ProbeRw { endpoint, tls });
                    return more(self);
                }
                if cx.defer.probe {
                    self.deferred.push(Job::Probe { endpoint, tls });
                    self.job = Some(JobKind::Probe);
                    self.cont = Cont::Job;
                    return Flow::Return(Progress::Wait);
                }
                let end = probe(&cx.probe_ctx(), proof, &endpoint, tls);
                if self.apply_probe(cx, proof, ob, endpoint, end) {
                    more(self)
                } else {
                    Flow::Continue
                }
            }
        }
    }

    /// Defers a host resolution action for asynchronous execution in native and Tokio drivers
    /// (`DeferPolicy::resolve == true`).
    ///
    /// Coalescing and concurrency behavior:
    /// - If a resolution job (`self.dns`) is already running for the *same* `hosts` list, the
    ///   in-flight run's `generation` and `shuffle` flags are updated in place and any pending
    ///   `dns_next` is cleared, avoiding a redundant DNS lookup.
    /// - If a resolution job is already running for a *different* `hosts` list, the new request is
    ///   parked in `self.dns_next` (overwriting any older queued update) to be launched by
    ///   [`Self::dns_target`] as soon as the current lookup finishes.
    /// - **Background vs. Foreground Execution**: When an active network socket already exists
    ///   (`self.has_socket()`), the job is placed in `self.bg_start` and the interest loop
    ///   continues (`Flow::Continue`). This allows heartbeats, frame reads/writes, and close
    ///   flushes on the current connection to proceed without interruption while DNS resolves in
    ///   the background. Conversely, when no socket is open, the driver cannot connect until DNS
    ///   completes, so it queues the job in `self.deferred` and suspends with `Progress::Wait` at
    ///   [`Cont::Job`].
    fn defer_resolve<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        generation: u64,
        hosts: Arc<[HostSpec]>,
    ) -> Flow {
        let run = DnsRun {
            generation,
            hosts,
            shuffle: shuffle_on(cx.host),
        };
        let job = match self.dns.as_mut() {
            Some(cur) if cur.hosts == run.hosts => {
                cur.generation = run.generation;
                cur.shuffle = run.shuffle;
                self.dns_next = None;
                None
            }
            Some(_) => {
                self.dns_next = Some(run);
                None
            }
            None => {
                let job = run.job();
                self.dns = Some(run);
                Some(job)
            }
        };
        if self.has_socket() {
            if let Some(job) = job {
                self.bg_start = Some(job);
            }
            return Flow::Continue;
        }
        if let Some(job) = job {
            self.deferred.push(job);
        }
        self.job = Some(JobKind::Resolve);
        self.cont = Cont::Job;
        Flow::Return(Progress::Wait)
    }

    /// Executes the `Action::Connect` directive, opening a nonblocking TCP socket, initiating the
    /// connection attempt, and starting the TLS handshake if applicable.
    ///
    /// Step-by-step sequence:
    /// 1. Checks `cx.abort`: if handle closure has been requested, skips opening a socket.
    /// 2. Opens a nonblocking [`TcpSock`] (applying `FD_CLOEXEC` if configured and attempting
    ///    `TCP_NODELAY`). If `TCP_NODELAY` fails, queues a `LogSite::TcpNoDelay` warning log
    ///    without failing the connection, matching `zookeeper.c`.
    /// 3. Emits `LogSite::ZkConnect` immediately prior to calling `start_connect(&addr)`.
    /// 4. If `socket()` or `connect()` fails synchronously, reports `SocketCreate` or `TcpRefused`
    ///    via [`Self::early_failure`], which caches the error code in `self.early` to short-circuit
    ///    the remainder of `interest_actions`.
    /// 5. Locks `core` and notifies `Connection::handle_connect_started`. If `start_connect`
    ///    returned [`ConnectStart::Immediate`] and the connection is plaintext (`tls.is_none()`),
    ///    immediately transitions the protocol state machine through `handle_tcp_connected` and
    ///    `handle_transport_ready` in the same lock scope.
    /// 6. Installs the transport:
    ///    - `(true, None)`: Immediate plaintext connection -> [`Transport::Plain`].
    ///    - `(true, Some(verify))`: Immediate TCP connection with TLS -> sets
    ///      `self.hs_origin = StepKind::Interest` and initiates [`iocore_tls::handshake_start`]
    ///      (pausing at [`Cont::Handshake`] first if `settle` hit an outbox barrier).
    ///    - `(false, _)`: Asynchronous `EINPROGRESS` connection -> [`Transport::Connecting`],
    ///      which will be completed in `process_connecting` once `poll` reports writability.
    fn connect<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        endpoint: Endpoint,
        tls: Option<TlsVerify>,
    ) -> Flow {
        if cx.abort.is_some_and(|a| a.load(Ordering::Acquire)) {
            return Flow::Continue;
        }
        let addr = endpoint.addr;
        let opened = match TcpSock::open(&addr, cx.cfg.cloexec) {
            Ok(o) => o,
            Err(e) => {
                let f = TransportFailure::SocketCreate {
                    errno: errno_value(e),
                };
                return self.early_failure(cx, proof, ob, f);
            }
        };
        if !opened.nodelay_ok {
            if let Some(rec) = log_record(cx.host, LogSite::TcpNoDelay, Vec::new()) {
                ob.push(Item::Log(rec));
            }
        }
        let sock = opened.sock;
        // Replicate legacy C logging emitted immediately prior to nonblocking connect initiation.
        if let Some(rec) = log_record(cx.host, LogSite::ZkConnect, Vec::new()) {
            ob.push(Item::Log(rec));
        }
        let start = match sock.start_connect(&addr) {
            Ok(s) => s,
            Err(e) => {
                drop(sock);
                let f = TransportFailure::TcpRefused {
                    errno: errno_value(e),
                };
                return self.early_failure(cx, proof, ob, f);
            }
        };
        let immediate = start == ConnectStart::Immediate;
        let now = cx.clock.now();
        let (installed, barrier) = {
            let mut g = cx.core.lock(proof);
            let mut installed = false;
            if let Some(conn) = g.conn.as_deref_mut() {
                conn.handle_connect_started(start, now);
                if conn.has_transport() {
                    installed = true;
                    if immediate && tls.is_none() {
                        conn.handle_tcp_connected(now);
                        conn.handle_transport_ready(now);
                    }
                }
            }
            if installed {
                g.peer = Some(addr);
            }
            let barrier = self.settle(&mut g, cx, ob);
            (installed.then_some(sock), barrier)
        };
        let Some(sock) = installed else {
            return if barrier {
                self.cont = Cont::Interest;
                Flow::Return(Progress::More)
            } else {
                Flow::Continue
            };
        };
        match (immediate, tls) {
            (true, None) => self.conn = Transport::Plain(sock),
            (true, Some(verify)) => {
                self.hs_origin = StepKind::Interest;
                self.hs_now = None;
                if barrier {
                    self.conn = Transport::Connecting {
                        sock,
                        ep: endpoint,
                        tls,
                    };
                    self.cont = Cont::Handshake;
                    return Flow::Return(Progress::More);
                }
                return match iocore_tls::handshake_start(
                    self, cx, proof, sock, endpoint, verify, ob,
                ) {
                    Progress::Done(rc) => {
                        if rc != ZOK {
                            self.early = Some(rc);
                        }
                        Flow::Continue
                    }
                    other => Flow::Return(other),
                };
            }
            (false, _) => {
                self.conn = Transport::Connecting {
                    sock,
                    ep: endpoint,
                    tls,
                }
            }
        }
        if barrier {
            self.cont = Cont::Interest;
            return Flow::Return(Progress::More);
        }
        Flow::Continue
    }

    /// Records an immediate socket creation or connection failure during `interest_step`, caching
    /// the error code in `self.early` so `interest_finish` writes `fd = -1, bits = 0, timeout = 0`.
    fn early_failure<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        f: TransportFailure,
    ) -> Flow {
        let (rc, barrier) = self.fail(cx, proof, ob, f);
        self.early = Some(rc);
        if barrier {
            self.cont = Cont::Interest;
            return Flow::Return(Progress::More);
        }
        Flow::Continue
    }

    /// Ingests read-write (`isro`) probe outcomes into the protocol core via
    /// `Connection::handle_rw_probe`, returning `true` if settling the resulting outputs hit an
    /// outbox barrier.
    ///
    /// If the probe was aborted due to session closure (`ProbeEnd::Aborted`), returns `false`
    /// without mutating protocol state.
    fn apply_probe<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        endpoint: Endpoint,
        end: ProbeEnd,
    ) -> bool {
        let ProbeEnd::Done(outcome) = end else {
            return false;
        };
        let now = cx.clock.now();
        let mut g = cx.core.lock(proof);
        if let Some(conn) = g.conn.as_deref_mut() {
            conn.handle_rw_probe(endpoint, outcome, now);
        }
        self.settle(&mut g, cx, ob)
    }

    /// Finalizes interest evaluation via `Connection::finish_interest` and synthesizes the
    /// [`InterestOut`] descriptor and readiness parameters.
    ///
    /// Key details:
    /// - **single threaded Ping Flush**: In single threaded mode (`Flavor::St`), if timer
    ///   evaluation during `interest_actions` queued a heartbeat ping (`interest_queued_ping`),
    ///   upstream `zookeeper_interest` calls `send_ping` -> `flush_send_queue(zh, 0)` immediately.
    ///   If that nonblocking write fails (`ping_failed`), `PingSendFailed` is logged and `out` is
    ///   populated with `rc = ZCONNECTIONLOSS`, `fd = self.fd()`, `bits = 0`, and `timeout_ms = 0`.
    /// - **Readiness Bitmask Synthesis**:
    ///   - `ZOOKEEPER_READ` is set when `i.read` is requested, the frame decoder is not stalled
    ///     (`!self.rx_stalled`), and the native backpressure gate is open (`gate`).
    ///   - `ZOOKEEPER_WRITE` is set when the protocol core requests write interest (`i.write`) *or*
    ///     when driver-level staging buffers hold unsent bytes (`unsent`: `Transport::Connecting`,
    ///     non-empty `tx_copy`, or unsent TLS ciphertext in `TlsTransport`). `Transport::Parked`
    ///     deliberately reports `unsent = false` so quarantined sockets never register write
    ///     interest.
    /// - **Buffered-Ready Zero Timeout**: If unconsumed plaintext is already waiting in
    ///   `self.rx_pending` ([`Self::buffered_ready`]), `timeout_ms` is overridden to `0` so the
    ///   event loop does not block waiting for network packets before processing buffered data.
    fn interest_finish<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        out: &mut InterestOut,
    ) -> Progress {
        // In single threaded mode, legacy C client flushes outbound queues immediately when
        // queueing ping requests.
        let mut ping_failed = false;
        if cx.flavor == Flavor::St && self.early.is_none() {
            let ping = cx
                .core
                .lock(proof)
                .conn
                .as_deref()
                .is_some_and(Connection::interest_queued_ping);
            ping_failed = ping && !self.flush_once(cx, proof);
        }
        let now = self.turn_now(cx.clock);
        let (outcome, suspended, gate, barrier) = {
            let mut g = cx.core.lock(proof);
            let Some(conn) = g.conn.as_deref_mut() else {
                out.rc = ZINVALIDSTATE;
                out.written = false;
                return self.done(ZINVALIDSTATE);
            };
            let outcome = conn.finish_interest(now);
            let suspended = conn.is_suspended();
            let gate = gate_open(cx, conn.state());
            (outcome, suspended, gate, self.settle(&mut g, cx, ob))
        };
        if let Some(rc) = self.early.take() {
            *out = InterestOut {
                rc,
                written: true,
                fd: -1,
                bits: 0,
                timeout_ms: 0,
            };
        } else if let Some(i) = outcome.outputs {
            let fd = if i.has_socket { self.fd() } else { -1 };
            let unsent = match &self.conn {
                Transport::Connecting { .. } => true,
                Transport::Plain(_) => !self.tx_copy.is_empty(),
                Transport::Tls(t) => t.has_unsent() || !self.tx_copy.is_empty(),
                Transport::None | Transport::Parked(_) => false,
            };
            let mut bits = 0;
            if i.read && !self.rx_stalled && gate {
                bits |= ZOOKEEPER_READ;
            }
            if i.write || (fd >= 0 && unsent) {
                bits |= ZOOKEEPER_WRITE;
            }
            let timeout_ms = if self.buffered_ready(suspended, gate) {
                0
            } else {
                i64::from(i.timeout_ms)
            };
            *out = InterestOut {
                rc: outcome.rc,
                written: true,
                fd,
                bits,
                timeout_ms,
            };
        } else {
            out.rc = outcome.rc;
            out.written = false;
        }
        if ping_failed {
            // Replicate legacy C error return semantics: output descriptor is recorded, but
            // interest bits and timeout remain zeroed.
            let arg = LogArg::I64(i64::from(ZCONNECTIONLOSS));
            if let Some(rec) = log_record(cx.host, LogSite::PingSendFailed, vec![arg]) {
                ob.push(Item::Log(rec));
            }
            *out = InterestOut {
                rc: ZCONNECTIONLOSS,
                written: true,
                fd: self.fd(),
                bits: 0,
                timeout_ms: 0,
            };
        }
        if barrier {
            self.cont = Cont::InterestEnd { out: *out };
            return Progress::More;
        }
        self.done(out.rc)
    }

    /// Propagates TLS handshake initiation progress (`Progress::Done` vs `More`/`Wait`) back to
    /// the originating execution step (`Interest` or `Process`).
    fn after_handshake_progress<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        out: &mut InterestOut,
        p: Progress,
    ) -> Progress {
        match p {
            Progress::Done(rc) => self.after_handshake(cx, proof, ob, out, rc),
            other => other,
        }
    }

    /// Resumes the originating driving step following TLS handshake initiation completion.
    ///
    /// When initiated from [`StepKind::Interest`] (synchronous TCP connect during `interest_step`),
    /// any non-`ZOK` setup failure is latched into `self.early` and execution returns to
    /// `interest_actions` to finish computing interest outputs. When initiated from
    /// [`StepKind::Process`] (asynchronous TCP connect completion during `process_step`), the turn
    /// concludes directly with `self.done(rc)`.
    fn after_handshake<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        out: &mut InterestOut,
        rc: i32,
    ) -> Progress {
        match self.hs_origin {
            StepKind::Interest => {
                if rc != ZOK {
                    self.early = Some(rc);
                }
                self.interest_actions(cx, proof, ob, out)
            }
            StepKind::Process => self.done(rc),
        }
    }

    /// Validates session invariants, runs pending forced close teardowns, and dispatches to the
    /// active transport handler (`process_connecting`, `iocore_tls::handshake_step`,
    /// `process_parked`, or `process_data`).
    fn process_begin<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        events: PollEvents,
        ob: &mut Outbox<T>,
    ) -> Progress {
        let now = self.turn_now(cx.clock);
        let (has, barrier) = {
            let mut g = cx.core.lock(proof);
            let Some(conn) = g.conn.as_deref_mut() else {
                return self.done(ZINVALIDSTATE);
            };
            if conn.state().is_unrecoverable() {
                return self.done(ZINVALIDSTATE);
            }
            conn.run_pending_teardown(now);
            let has = conn.has_transport();
            (has, self.settle(&mut g, cx, ob))
        };
        if barrier {
            self.cont = Cont::ProcessBegin { events };
            return Progress::More;
        }
        if !has {
            return self.done(ZINVALIDSTATE);
        }
        match &self.conn {
            Transport::None => self.done(ZINVALIDSTATE),
            Transport::Connecting { .. } => self.process_connecting(cx, proof, events, ob),
            Transport::Tls(t) if t.tls.is_handshaking() => {
                let p = iocore_tls::handshake_step(self, cx, proof, events, ob);
                self.track(p)
            }
            Transport::Parked(_) => self.process_parked(cx, proof, events, ob),
            Transport::Plain(_) | Transport::Tls(_) => self.process_data(cx, proof, events, ob),
        }
    }

    /// Evaluates asynchronous TCP connection readiness (`EINPROGRESS` completion), handling
    /// `getsockopt(SO_ERROR)` checks and transitioning to plaintext or TLS handshaking.
    ///
    /// On POSIX sockets, an asynchronous `connect()` signals completion via write readiness
    /// (`events.write`). If `events.write` is not set, a read-only readiness notification is
    /// routed to [`Self::connecting_read`] to probe for premature peer resets. When `events.write`
    /// is asserted:
    /// 1. Queries `TcpSock::socket_error()` (`SO_ERROR`). Any non-zero error fails the connection
    ///    with `TransportFailure::TcpRefused`.
    /// 2. For TLS endpoints (`tls: Some(verify)`), sets `self.hs_origin = StepKind::Process` and
    ///    delegates to [`iocore_tls::handshake_start`].
    /// 3. For plaintext endpoints (`tls: None`), transitions `self.conn` to [`Transport::Plain`],
    ///    notifies `handle_tcp_connected` and `handle_transport_ready` under the `core` lock, and
    ///    executes [`Self::connected_flush`] to transmit the initial `ConnectRequest` frame.
    fn process_connecting<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        events: PollEvents,
        ob: &mut Outbox<T>,
    ) -> Progress {
        if !events.write {
            if events.read {
                return self.connecting_read(cx, proof, ob);
            }
            return self.done(ZOK);
        }
        let status = self.conn.sock().map_or(Ok(()), TcpSock::socket_error);
        if let Err(e) = status {
            let f = TransportFailure::TcpRefused {
                errno: errno_value(e),
            };
            return self.fail_end(cx, proof, ob, f);
        }
        match std::mem::replace(&mut self.conn, Transport::None) {
            Transport::Connecting {
                sock,
                ep,
                tls: Some(verify),
            } => {
                self.hs_origin = StepKind::Process;
                self.hs_now = None;
                let p = iocore_tls::handshake_start(self, cx, proof, sock, ep, verify, ob);
                self.track(p)
            }
            Transport::Connecting { sock, .. } => {
                let now = cx.clock.now();
                self.conn = Transport::Plain(sock);
                let barrier = {
                    let mut g = cx.core.lock(proof);
                    if let Some(conn) = g.conn.as_deref_mut() {
                        conn.handle_tcp_connected(now);
                        conn.handle_transport_ready(now);
                    }
                    self.settle(&mut g, cx, ob)
                };
                if barrier {
                    self.cont = Cont::ProcessFlush;
                    return Progress::More;
                }
                self.connected_flush(cx, proof, ob)
            }
            other => {
                self.conn = other;
                self.done(ZOK)
            }
        }
    }

    /// Probes for remote connection closure or reset when receiving read readiness
    /// (`ZOOKEEPER_READ`) on a [`Transport::Connecting`] socket without write readiness
    /// (`ZOOKEEPER_WRITE`).
    ///
    /// Certain single threaded caller event loops translate `POLLHUP` or `POLLERR` into
    /// `ZOOKEEPER_READ` without setting `ZOOKEEPER_WRITE`. In upstream `zookeeper.c`, such an
    /// event falls through into the read branch and calls `recv_buffer` for the 4-byte frame
    /// length, which immediately surfaces `0` (EOF) or the pending socket `errno` (such as
    /// `ECONNREFUSED`).
    fn connecting_read<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
    ) -> Progress {
        let Some(sock) = self.conn.sock() else {
            return self.done(ZOK);
        };
        let mut len = [0u8; 4];
        match sock.recv(&mut len) {
            Ok(0) => self.fail_end(cx, proof, ob, TransportFailure::Eof),
            Ok(_) | Err(Errno::WOULDBLOCK) => self.done(ZNOTHING),
            Err(e) => {
                let f = TransportFailure::Io {
                    errno: errno_value(e),
                };
                self.fail_end(cx, proof, ob, f)
            }
        }
    }

    /// Executes an immediate nonblocking outbound flush upon plaintext TCP connection completion,
    /// transmitting the newly queued `ConnectRequest` handshake frame.
    fn connected_flush<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
    ) -> Progress {
        match self.plain_flush(cx, proof, None) {
            Err(FlushError::Send(e)) => {
                let f = TransportFailure::Io {
                    errno: errno_value(e),
                };
                self.fail_end(cx, proof, ob, f)
            }
            _ => {
                let barrier = {
                    let mut g = cx.core.lock(proof);
                    self.settle(&mut g, cx, ob)
                };
                self.end_after(barrier, ZOK)
            }
        }
    }

    /// Reads from a quarantined [`Transport::Parked`] socket solely to observe remote EOF or
    /// transport reset errors while awaiting the protocol receive timeout.
    fn process_parked<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        events: PollEvents,
        ob: &mut Outbox<T>,
    ) -> Progress {
        if !events.read {
            return self.done(ZOK);
        }
        let Transport::Parked(sock) = &self.conn else {
            return self.done(ZOK);
        };
        match self.rx.recv(sock, READ_BUFFER_LEN) {
            Ok(0) => self.fail_end(cx, proof, ob, TransportFailure::Eof),
            Ok(_) | Err(Errno::WOULDBLOCK) => self.done(ZNOTHING),
            Err(e) => {
                let f = TransportFailure::Io {
                    errno: errno_value(e),
                };
                self.fail_end(cx, proof, ob, f)
            }
        }
    }

    /// Handles data-phase socket readiness events, flushing outbound queues on `events.write`
    /// before proceeding to inbound frame processing (`process_read`).
    ///
    /// Following the outbound flush, `core` is locked to settle any generated outputs and verify
    /// that the transport was not closed. If `settle` hits a barrier while the transport remains
    /// active, execution suspends at [`Cont::ProcessRead`] so that resuming after the outbox drain
    /// still processes any concurrent `events.read` readiness from the same poll turn.
    fn process_data<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        events: PollEvents,
        ob: &mut Outbox<T>,
    ) -> Progress {
        if events.write {
            let sent = if matches!(self.conn, Transport::Tls(_)) {
                iocore_tls::tls_flush(self, cx, proof, None)
            } else {
                match self.plain_flush(cx, proof, None) {
                    Err(FlushError::Send(e)) => Err(e),
                    _ => Ok(()),
                }
            };
            if let Err(e) = sent {
                let f = TransportFailure::Io {
                    errno: errno_value(e),
                };
                return self.fail_end(cx, proof, ob, f);
            }
            let (has, barrier) = {
                let mut g = cx.core.lock(proof);
                let has = g.conn.as_deref().is_some_and(Connection::has_transport);
                (has, self.settle(&mut g, cx, ob))
            };
            if barrier {
                self.cont = if has {
                    Cont::ProcessRead { events }
                } else {
                    Cont::End { rc: ZINVALIDSTATE }
                };
                return Progress::More;
            }
            if !has {
                return self.done(ZINVALIDSTATE);
            }
        }
        self.process_read(cx, proof, events, ob)
    }

    /// Evaluates read readiness and in-memory buffer availability, ingests incoming socket data,
    /// and drives protocol frame deserialization.
    ///
    /// Read progression rules:
    /// - Queries `is_suspended()`, [`gate_open`], and `first_read_hint()` under the `core` lock.
    /// - Proceeds if either the OS socket reported read readiness (`events.read`) *or* unconsumed
    ///   plaintext is already waiting in `rx_pending` ([`Self::buffered_ready`]), provided the
    ///   decoder has not stalled on a negative frame length (`!self.rx_stalled`).
    /// - Delegates TLS transports to [`iocore_tls::tls_read`].
    /// - For plaintext transports, only performs a new socket read (`plain_read`) when
    ///   `rx_pending` is completely empty, `events.read` is signaled, and the backpressure gate is
    ///   open. When `rx_pending` already holds bytes from an earlier partial read or barrier pause,
    ///   `plain_frames` consumes those buffered bytes first before touching the socket.
    fn process_read<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        events: PollEvents,
        ob: &mut Outbox<T>,
    ) -> Progress {
        let (suspended, gate, hint) = {
            let g = cx.core.lock(proof);
            match g.conn.as_deref() {
                Some(c) => (
                    c.is_suspended(),
                    gate_open(cx, c.state()),
                    c.first_read_hint(),
                ),
                None => return self.done(ZINVALIDSTATE),
            }
        };
        let ready = events.read || self.buffered_ready(suspended, gate);
        if !ready || self.rx_stalled {
            return self.done(self.process_rc());
        }
        match &self.conn {
            Transport::Tls(_) => {
                let p = iocore_tls::tls_read(self, cx, proof, events, ob);
                return self.track(p);
            }
            Transport::Plain(_) => {}
            _ => return self.done(self.process_rc()),
        }
        if self.rx_pending.is_empty() && events.read && gate {
            if let Some(p) = self.plain_read(cx, proof, ob, hint) {
                return p;
            }
        }
        self.plain_frames(cx, proof, ob)
    }

    /// Conducts plaintext socket reads into staging or zero-copy frame buffers, continuing across
    /// frame length-to-body boundaries or multi-chunk large frames as appropriate for the driver
    /// flavor.
    ///
    /// Sizing policy (`want`):
    /// - **single threaded (`Flavor::St`)**: Requests `hint.min(cap)` bytes, where `hint` is
    ///   `Connection::first_read_hint` (the 41-byte `ConnectResponse` frame size while the
    ///   handshake reply is due, else `Connection::read_hint`: 4 bytes when awaiting a frame length
    ///   header, or the remaining body deficit when a frame is open). Outside the handshake this
    ///   mirrors C `recv_buffer`, which never reads past the end of a single frame in one
    ///   `zookeeper_process` call; the handshake read can leave bytes of the next frame for the
    ///   next turn.
    /// - **multithreaded & Native (`Flavor::Mt` / `Flavor::Native`)**: Requests `cap` (up to
    ///   `cfg.max_read` = 64 KiB) to batch multiple small frames in a single `recv` syscall.
    ///
    /// Whenever the initial [`Self::read_part`] fills its entire `want` request (`n == want`),
    /// [`Self::read_rest`] is invoked to parse the ingested bytes and, if a frame remains open,
    /// immediately read the remainder of that frame's body in the same turn.
    fn plain_read<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        hint: usize,
    ) -> Option<Progress> {
        let st = cx.flavor == Flavor::St;
        let cap = if st {
            READ_BUFFER_LEN
        } else {
            cx.cfg.max_read.min(READ_BUFFER_LEN)
        };
        let want = if st { hint.min(cap) } else { cap };
        if want == 0 {
            return None;
        }
        match self.read_part(cx, proof, ob, want) {
            Part::Got(n) if n == want => self.read_rest(cx, proof, ob, cap, st),
            Part::Got(_) => None,
            Part::Barrier => Some(self.frames_more()),
            Part::Failed(f) => Some(self.fail_end(cx, proof, ob, f)),
        }
    }

    /// Suspends execution at [`Cont::Process`] when an outbox barrier is encountered during
    /// plaintext frame processing.
    fn frames_more(&mut self) -> Progress {
        self.cont = Cont::Process {
            events: PollEvents::NONE,
        };
        Progress::More
    }

    /// Reads remainder bytes for an open frame across length-to-body transitions or multi-chunk
    /// large payloads, bounded by `cap` (64 KiB) per syscall.
    ///
    /// Subtlety of `free` and `rx_more_blocked`:
    /// - First runs `frame_loop` to consume the bytes just read by `plain_read` (or the previous
    ///   loop iteration). If `frame_loop` completed a frame (`self.frames_seen == true`),
    ///   [`Self::body_hint`] returns `0` and the loop exits—ensuring a turn never initiates a
    ///   second socket read once at least one frame has completed.
    /// - If `frame_loop` consumed a 4-byte frame length header and opened the frame's body without
    ///   completing the frame, `body_hint` returns the remaining body byte count (`rest`).
    /// - In single threaded mode (`st == true`), `free` starts as `true`: completing the 4-byte
    ///   length header always grants one immediate follow-up `read_part` for the body (matching C
    ///   `recv_buffer` reading the payload right after the length header), even if an earlier turn
    ///   had set `rx_more_blocked`.
    /// - In multithreaded/native modes (`st == false`), if an earlier follow-up read for the same
    ///   open frame already returned `EWOULDBLOCK` (`self.rx_more_blocked == true`), no further
    ///   follow-up reads are attempted in subsequent turns until the frame completes.
    fn read_rest<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        cap: usize,
        st: bool,
    ) -> Option<Progress> {
        let mut free = st;
        loop {
            match self.frame_loop(cx, proof, ob) {
                FrameEnd::More => return Some(self.frames_more()),
                FrameEnd::Held | FrameEnd::ReplyHold => return Some(self.done(self.process_rc())),
                FrameEnd::Done => {}
            }
            if !free && self.rx_more_blocked {
                return None;
            }
            free = false;
            let rest = self.body_hint(cx, proof).min(cap);
            if rest == 0 {
                return None;
            }
            match self.read_part(cx, proof, ob, rest) {
                Part::Got(0) => {
                    self.rx_more_blocked = true;
                    return None;
                }
                Part::Got(n) if n == rest => {}
                Part::Got(_) => return None,
                Part::Barrier => return Some(self.frames_more()),
                Part::Failed(f) => return Some(self.fail_end(cx, proof, ob, f)),
            }
        }
    }

    /// Ingests up to `want` plaintext bytes from the network socket, taking the fast zero-copy
    /// [`Self::body_part`] path when reading wholly inside an open frame body, or falling back to
    /// [`Self::plain_recv`] into `self.rx`.
    fn read_part<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        want: usize,
    ) -> Part {
        if self.rx_fresh == 0 && self.rx_pending.is_empty() {
            if let Some(part) = self.body_part(cx, proof, ob, want) {
                return part;
            }
        }
        match self.plain_recv(want) {
            Ok(n) => Part::Got(n),
            Err(f) => Part::Failed(f),
        }
    }

    /// Attempts a zero-copy socket read directly into the allocated payload buffer of an open
    /// message frame inside `Connection`.
    ///
    /// Fast-path preconditions:
    /// - Called only when `rx_fresh == 0` and `rx_pending` is empty (no unparsed bytes ahead of
    ///   the socket stream).
    /// - Under the `core` lock, verifies that the backpressure gate is open and that
    ///   `conn.read_hint() >= want` (meaning the frame decoder has already parsed the 4-byte
    ///   length header and the open frame body needs at least `want` more bytes).
    /// - Requests a mutable slice of length `want` via `conn.body_room(want)`. If the decoder is
    ///   still reading the 4-byte length header or cannot expose `want` contiguous body bytes,
    ///   `body_room` returns an empty slice and `body_part` returns `None` so `read_part` falls
    ///   back to `plain_recv`.
    ///
    /// When `body_room` succeeds, `sock.recv(room)` reads directly from the kernel into the frame's
    /// target `Vec<u8>` (safe to call under the `core` lock because `sock` is nonblocking), and
    /// `conn.handle_body_read(n, now)` commits the bytes and dispatches the frame if complete.
    fn body_part<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        want: usize,
    ) -> Option<Part> {
        let Transport::Plain(sock) = &self.conn else {
            return None;
        };
        let now = self.turn_now(cx.clock);
        let mut g = cx.core.lock(proof);
        let conn = g.conn.as_deref_mut()?;
        if !gate_open(cx, conn.state()) || conn.read_hint() < want {
            return None;
        }
        let room = conn.body_room(want);
        if room.len() != want {
            return None;
        }
        let got = sock.recv(room);
        self.read_attempted = true;
        let n = match got {
            Ok(0) => return Some(Part::Failed(TransportFailure::Eof)),
            Ok(n) => n,
            Err(Errno::WOULDBLOCK) => return Some(Part::Got(0)),
            Err(e) => {
                let errno = errno_value(e);
                return Some(Part::Failed(TransportFailure::Io { errno }));
            }
        };
        let r = conn.handle_body_read(n, now);
        self.note_outcome(r);
        if self.settle(&mut g, cx, ob) {
            return Some(Part::Barrier);
        }
        Some(Part::Got(n))
    }

    /// Reads up to `want` plaintext bytes from the socket into the primary 64 KiB heap reception
    /// buffer (`self.rx`).
    ///
    /// Before overwriting `self.rx`, any unconsumed bytes from a prior read (`self.rx_fresh > 0`)
    /// are spilled into `self.rx_pending`. If `self.rx_pending` is empty when the `recv` succeeds,
    /// `self.rx_fresh` is set to `n` so [`Self::frame_steps`] can parse directly from `self.rx`
    /// without copying into `rx_pending`. If `rx_pending` already holds earlier bytes, the newly
    /// read slice is appended to `rx_pending` to preserve strict byte stream ordering.
    fn plain_recv(&mut self, want: usize) -> Result<usize, TransportFailure> {
        self.read_attempted = true;
        // Preserve unparsed ingress bytes by spilling to pending storage before refilling the
        // reception buffer.
        self.spill_fresh();
        let Transport::Plain(sock) = &self.conn else {
            return Ok(0);
        };
        let got = self.rx.recv(sock, want.min(READ_BUFFER_LEN));
        match got {
            Ok(0) => Err(TransportFailure::Eof),
            Ok(n) => {
                if self.rx_pending.is_empty() {
                    self.rx_fresh = n;
                } else {
                    let data = self.rx.data().get(..n).unwrap_or_default();
                    self.rx_pending.extend_from_slice(data);
                }
                Ok(n)
            }
            Err(Errno::WOULDBLOCK) => Ok(0),
            Err(e) => Err(TransportFailure::Io {
                errno: errno_value(e),
            }),
        }
    }

    /// Returns a contiguous slice of unconsumed ingress plaintext, reading from the zero-copy
    /// primary buffer (`rx[..rx_fresh]`) when populated, or from `rx_pending` otherwise.
    ///
    /// Invariant: `rx_fresh > 0` and `!rx_pending.is_empty()` are mutually exclusive; whenever
    /// bytes are moved to `rx_pending`, `rx_fresh` is reset to `0`.
    fn rx_input(&self) -> &[u8] {
        if self.rx_fresh > 0 {
            self.rx.data().get(..self.rx_fresh).unwrap_or_default()
        } else {
            self.rx_pending.as_slice()
        }
    }

    /// Consumes `n` bytes from the head of the unparsed ingress buffer, shifting any remaining
    /// unconsumed bytes from `rx[n..rx_fresh]` into `rx_pending` and resetting `rx_fresh` to `0`.
    fn rx_consume(&mut self, n: usize) {
        if self.rx_fresh > 0 {
            let rest = self.rx.data().get(n..self.rx_fresh).unwrap_or_default();
            self.rx_pending.extend_from_slice(rest);
            self.rx_fresh = 0;
        } else {
            self.rx_pending.drain(..n);
        }
    }

    /// Transfers any unconsumed zero-copy bytes from `rx[..rx_fresh]` into `rx_pending` so that
    /// `self.rx` is free to be overwritten by subsequent socket reads or TLS ciphertext reads.
    fn spill_fresh(&mut self) {
        if self.rx_fresh > 0 {
            self.rx_consume(0);
        }
    }

    /// Queries the protocol decoder for the number of additional bytes expected to complete the
    /// currently open frame body during [`Self::read_rest`].
    ///
    /// Returns `0` if a complete frame was already decoded during this turn (`self.frames_seen`),
    /// if the decoder stalled (`self.rx_stalled`), or if unparsed bytes remain in `rx_pending`.
    fn body_hint<T: Tokens>(&self, cx: &StepCtx<'_, T>, proof: &mut Unlocked) -> usize {
        if self.frames_seen || self.rx_stalled || !self.rx_pending.is_empty() {
            return 0;
        }
        let g = cx.core.lock(proof);
        match g.conn.as_deref() {
            Some(c) if c.has_transport() && !c.is_suspended() && gate_open(cx, c.state()) => {
                c.read_hint()
            }
            _ => 0,
        }
    }

    /// Drives the plaintext frame ingestion loop and maps completion outcomes to execution
    /// progress codes.
    fn plain_frames<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
    ) -> Progress {
        match self.frame_loop(cx, proof, ob) {
            FrameEnd::More => {
                self.cont = Cont::Process {
                    events: PollEvents::NONE,
                };
                Progress::More
            }
            FrameEnd::Done | FrameEnd::Held | FrameEnd::ReplyHold => self.done(self.process_rc()),
        }
    }

    /// Feeds buffered ingress plaintext (`rx_input`) to `Connection::handle_read` across sequential
    /// iterations, pumping outbox notifications after each pass and spilling any trailing unparsed
    /// bytes from `rx` into `rx_pending` before returning.
    pub(crate) fn frame_loop<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
    ) -> FrameEnd {
        let end = self.frame_steps(cx, proof, ob);
        self.spill_fresh();
        end
    }

    /// Iterative frame parsing pipeline driving `Connection::handle_read` across buffered bytes.
    ///
    /// On each iteration:
    /// 1. Polls and emits any SASL authentication challenges queued by the preceding frame via
    ///    [`Self::process_sasl`] (yielding [`FrameEnd::More`] when a `Start` or `Step` challenge
    ///    requires host/mechanism evaluation).
    /// 2. Returns [`FrameEnd::Done`] if `rx_input()` is empty or `self.rx_stalled` is set.
    /// 3. Under the `core` lock, checks [`Self::frame_gate`] to verify transport liveness, absence
    ///    of suspended barrier stages, and [`ReadGate`] capacity.
    /// 4. Invokes `conn.handle_read(self.rx_input(), now)` and commits consumption via
    ///    [`Self::apply_read`].
    /// 5. Settles protocol outputs into `ob` (yielding [`FrameEnd::More`] on a barrier,
    ///    [`FrameEnd::ReplyHold`] at a lone synchronous reply hold, [`FrameEnd::Held`] if waiting
    ///    on SASL negotiation, or [`FrameEnd::Done`] if fewer bytes remain than needed to complete
    ///    the next frame).
    fn frame_steps<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
    ) -> FrameEnd {
        loop {
            if self.process_sasl(cx, proof, ob) {
                return FrameEnd::More;
            }
            if self.rx_input().is_empty() || self.rx_stalled {
                return FrameEnd::Done;
            }
            let now = self.turn_now(cx.clock);
            let mut g = cx.core.lock(proof);
            if let Some(end) = self.frame_gate(&mut g, cx, ob) {
                return end;
            }
            // Terminate frame parsing if the protocol connection has been closed.
            let Some(conn) = g.conn.as_deref_mut() else {
                return FrameEnd::Done;
            };
            let r = conn.handle_read(self.rx_input(), now);
            let has = conn.has_transport();
            let stalled = has && conn.read_hint() == 0;
            let (n, waits) = self.apply_read(r, has, stalled);
            if self.settle(&mut g, cx, ob) {
                return self.barrier_end(&g, cx);
            }
            if waits {
                return FrameEnd::Held;
            }
            if n == 0 {
                return FrameEnd::Done;
            }
        }
    }

    /// Classifies an outbox barrier encountered during a frame parsing iteration.
    ///
    /// When a multithreaded synchronous reply parks a hold continuation and neither buffered socket
    /// input nor pending protocol output remains, resuming the hold immediately would perform no
    /// further work before ending the turn. Returning [`FrameEnd::ReplyHold`] leaves the hold
    /// parked in `g.stopped` until the next turn's prelude (or connection teardown on close) while
    /// still blocking subsequent frame reads until the caller delivers the synchronous reply.
    fn barrier_end<T: Tokens>(&self, g: &Core<T>, cx: &StepCtx<'_, T>) -> FrameEnd {
        let parked = cx.flavor == Flavor::Mt
            && self.rx_input().is_empty()
            && g.stopped.as_ref().is_some_and(Stopped::is_hold)
            && g.conn.as_deref().is_some_and(|c| !c.has_output());
        if parked {
            FrameEnd::ReplyHold
        } else {
            FrameEnd::More
        }
    }

    /// Evaluates admission conditions for a frame parsing iteration under the `core` lock,
    /// checking transport validity, protocol suspension state, and [`ReadGate`] backpressure.
    #[inline]
    fn frame_gate<T: Tokens>(
        &mut self,
        g: &mut Core<T>,
        cx: &StepCtx<'_, T>,
        ob: &mut Outbox<T>,
    ) -> Option<FrameEnd> {
        let Some(conn) = g.conn.as_deref() else {
            return Some(FrameEnd::Done);
        };
        if !conn.has_transport() || conn.is_suspended() {
            return Some(if self.settle(g, cx, ob) {
                FrameEnd::More
            } else {
                FrameEnd::Done
            });
        }
        if !gate_open(cx, conn.state()) {
            return Some(FrameEnd::Held);
        }
        None
    }

    /// Commits the byte consumption outcome of `Connection::handle_read`, advancing receive buffers
    /// and detecting decoder stalls or SASL mechanism waits.
    ///
    /// Returns `(consumed, waits)`, where `waits` is `true` when `handle_read` consumed `0` bytes
    /// on an active, non-stalled transport with non-empty `rx_pending`—indicating that the protocol
    /// engine is in `Phase::Sasl` awaiting an external SASL mechanism step before it can parse
    /// subsequent frames.
    #[inline]
    fn apply_read(&mut self, r: ReadOutcome, has: bool, stalled: bool) -> (usize, bool) {
        let n = r.consumed.min(self.rx_input().len());
        self.rx_consume(n);
        self.note_outcome(r);
        if stalled {
            self.rx_stalled = true;
        }
        let waits = n == 0 && has && !stalled && !self.rx_pending.is_empty();
        (n, waits)
    }

    /// Records frame completion (`frames_seen`), clears `rx_more_blocked` when a frame completes,
    /// and latches the earliest non-`ZOK` / non-`ZNOTHING` status code into `self.stop_rc`.
    #[inline]
    fn note_outcome(&mut self, r: ReadOutcome) {
        if r.frames > 0 {
            self.frames_seen = true;
            self.rx_more_blocked = false;
        }
        if r.rc != ZOK && r.rc != ZNOTHING {
            self.stop_rc.get_or_insert(r.rc);
        }
    }

    /// Polls and stages SASL authentication challenges generated during frame ingestion.
    ///
    /// When the protocol engine receives `ConnectResponse` (initiating SASL) or a server SASL
    /// challenge packet during `handle_read`, it queues a [`zk_proto::SaslCall`]. Following legacy
    /// C client flow, `frame_steps` immediately extracts that call via `poll_sasl_call` and stages
    /// an [`Item::Sasl`] in the outbox within the same `process_step` turn rather than waiting for
    /// the next `interest_step`. Returns `true` when a `Start` or `Step` challenge (or an address-
    /// failure teardown barrier) requires yielding [`Progress::More`].
    fn process_sasl<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
    ) -> bool {
        loop {
            let mut g = cx.core.lock(proof);
            let Some(conn) = g.conn.as_deref_mut() else {
                return false;
            };
            if !conn.has_transport() {
                return false;
            }
            let Some(call) = conn.poll_sasl_call() else {
                return false;
            };
            match call.kind {
                SaslCallKind::Dispose => ob.push(Item::Sasl(SaslItem { call, addrs: None })),
                SaslCallKind::Step(_) => {
                    ob.push(Item::Sasl(SaslItem { call, addrs: None }));
                    return true;
                }
                SaslCallKind::Start => match self.sasl_addrs() {
                    Ok(addrs) => {
                        ob.push(Item::Sasl(SaslItem {
                            call,
                            addrs: Some(addrs),
                        }));
                        return true;
                    }
                    Err(which) => {
                        let now = self.turn_now(cx.clock);
                        let rc = conn.handle_sasl_addr_failed(call.generation, which, now);
                        if rc != ZOK {
                            self.stop_rc.get_or_insert(rc);
                        }
                        if self.settle(&mut g, cx, ob) {
                            return true;
                        }
                    }
                },
            }
        }
    }

    /// Outbound socket transmission loop transferring serialized protocol requests across
    /// plaintext TCP streams.
    ///
    /// Execution loop:
    /// 1. If `self.tx_copy` is empty, calls [`Self::stage_plain`] to copy or zero-copy lend the
    ///    next batch of serialized bytes from `Connection::poll_transmit`. Returns `Ok(())` when
    ///    no sendable bytes remain.
    /// 2. Outside of the `core` lock, transmits `self.tx_copy[self.sent_len()..]` over `sock`
    ///    (either via nonblocking `sock.send` when `deadline == None`, or via polling
    ///    [`send_until`] during `close_flush`).
    /// 3. Advances `self.tx_sent` via [`Self::plain_sent`] (clearing `tx_copy` once the entire
    ///    staged buffer has been written) and locks `core` to credit the transmitted byte count
    ///    back to `Connection::handle_transmitted`.
    pub(crate) fn plain_flush<T: Tokens>(
        &mut self,
        cx: &StepCtx<'_, T>,
        proof: &mut Unlocked,
        deadline: Option<Millis>,
    ) -> Result<(), FlushError> {
        loop {
            if self.tx_copy.is_empty() && !self.stage_plain(cx, proof) {
                return Ok(());
            }
            let Transport::Plain(sock) = &self.conn else {
                return Ok(());
            };
            let rest = self.tx_copy.get(self.sent_len()..).unwrap_or_default();
            let n = match deadline {
                None => match sock.send(rest) {
                    Ok(n) => n,
                    Err(Errno::INTR) => continue,
                    Err(Errno::WOULDBLOCK) => return Ok(()),
                    Err(e) => return Err(FlushError::Send(e)),
                },
                Some(d) => send_until(cx, sock, rest, d)?,
            };
            if n == 0 {
                return Ok(());
            }
            let n = self.plain_sent(n);
            let now = self.turn_now(cx.clock);
            let mut g = cx.core.lock(proof);
            let Some(conn) = g.conn.as_deref_mut() else {
                return Ok(());
            };
            if !conn.has_transport() {
                return Ok(());
            }
            conn.handle_transmitted(n, now);
        }
    }

    /// Extracts serialized outbound request bytes from `Connection::poll_transmit` into
    /// `self.tx_copy` (permitting zero-copy frame lending up to [`TX_LEND_MAX`]).
    ///
    /// When `poll_transmit` returns `0` (marking the end of a write burst) and [`WriteBursts`]
    /// indicates that [`crate::iocore_tls::SMALL_BURSTS`] consecutive small bursts have completed,
    /// any oversized `tx_copy` allocation (> `READ_BUFFER_LEN`) is replaced with a fresh
    /// zero-capacity [`Zeroizing`] vector, scrubbing any residual credential bytes on drop.
    fn stage_plain<T: Tokens>(&mut self, cx: &StepCtx<'_, T>, proof: &mut Unlocked) -> bool {
        let n = {
            let mut g = cx.core.lock(proof);
            match g.conn.as_deref_mut() {
                Some(conn) if conn.has_transport() => {
                    conn.poll_transmit(&mut self.tx_copy, TX_LEND_MAX)
                }
                _ => return false,
            }
        };
        let cap = self.tx_copy.capacity();
        if self.write_bursts.release(n, cap) && cap > READ_BUFFER_LEN {
            // Explicitly reallocate to release oversized transmit buffers while ensuring secret
            // frame memory is zeroed.
            self.tx_copy = Zeroizing::new(Vec::new());
        }
        n > 0
    }

    /// Records `n` kernel accepted bytes against `self.tx_copy`, clearing `tx_copy` and resetting
    /// `tx_sent` to `0` once the staged buffer has been completely written, and returning the byte
    /// count to credit via `Connection::handle_transmitted`.
    fn plain_sent(&mut self, n: usize) -> usize {
        let sent = self.sent_len();
        let n = n.min(self.tx_copy.len().saturating_sub(sent));
        let sent = sent.saturating_add(n);
        if sent >= self.tx_copy.len() {
            self.tx_copy.clear();
            self.tx_sent = 0;
        } else {
            // Payload length is bounded by TX_LEND_MAX (4 MiB), fitting safely within u32.
            self.tx_sent = u32::try_from(sent).unwrap_or(u32::MAX);
        }
        n
    }

    /// Returns the cumulative byte count already transmitted from the active `tx_copy` buffer.
    pub(crate) fn sent_len(&self) -> usize {
        usize::try_from(self.tx_sent).unwrap_or(usize::MAX)
    }
}

impl Default for IoCore {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::socket_pair;

    #[test]
    fn drop_transport_clears_the_receive_state() {
        let (a, _b) = socket_pair();
        let mut io = IoCore::new();
        io.conn = Transport::Plain(a);
        io.rx_pending.extend_from_slice(b"x");
        io.rx_fresh = 3;
        io.rx_stalled = true;
        assert!(io.fd() >= 0);
        io.drop_transport();
        assert_eq!(io.fd(), -1);
        assert_eq!(io.rx_fresh, 0);
        assert!(io.rx_pending.is_empty() && !io.rx_stalled);
    }

    /// Verifies that zero-copy bytes unconsumed by the protocol parser spill into pending buffers
    /// in sequential order.
    #[test]
    fn the_rest_of_a_fresh_read_moves_to_rx_pending() {
        let mut io = IoCore::new();
        io.rx.put(b"abc");
        io.rx_fresh = 3;
        assert_eq!(io.rx_input(), b"abc");
        io.rx_consume(1);
        assert_eq!(
            (io.rx_fresh, io.rx_pending.as_slice()),
            (0, b"bc".as_slice())
        );
        assert_eq!(io.rx_input(), b"bc");
        io.rx_consume(1);
        assert_eq!(io.rx_input(), b"c");
        io.spill_fresh();
        assert_eq!(io.rx_pending, b"c");
    }

    /// Verifies that a newly constructed `IoCore` allocates full `READ_BUFFER_LEN` capacity with
    /// zero initialized length.
    #[test]
    fn a_new_core_has_an_empty_read_buffer_of_full_capacity() {
        let io = IoCore::new();
        assert_eq!(io.rx.capacity(), READ_BUFFER_LEN);
        assert!(io.rx.data().is_empty());
        assert_eq!(io.rx_fresh, 0);
    }

    /// Transmits `bytes` from `peer` and spins until `io`'s socket receive queue holds the full
    /// payload.
    #[allow(clippy::unwrap_used)]
    fn send_now(io: &IoCore, peer: &TcpSock, bytes: &[u8]) {
        assert_eq!(peer.send(bytes).unwrap(), bytes.len());
        let Some(sock) = io.conn.sock() else {
            return;
        };
        let start = std::time::Instant::now();
        while rustix::io::ioctl_fionread(sock).unwrap() < bytes.len() as u64 {
            assert!(start.elapsed() < std::time::Duration::from_secs(10));
            std::thread::yield_now();
        }
    }

    /// Streams `len` copies of `byte` from `peer` while draining them via `plain_recv(want)`,
    /// asserting that each read exposes only newly received bytes and updates `rx_fresh`
    /// accurately.
    #[allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]
    fn send_and_read(io: &mut IoCore, peer: &TcpSock, byte: u8, len: usize, want: usize) {
        let data = vec![byte; len];
        std::thread::scope(|s| {
            s.spawn(|| {
                let mut off = 0;
                while let Some(rest) = data.get(off..).filter(|r| !r.is_empty()) {
                    off += peer.send(rest).unwrap();
                }
            });
            let mut got = 0;
            while got < len {
                let n = io.plain_recv(want).unwrap();
                assert!(n > 0 && n <= want, "read {n} of {want}");
                assert_eq!(io.rx_fresh, n);
                assert_eq!(io.rx_input().len(), n);
                assert!(io.rx_input().iter().all(|b| *b == byte), "a stale byte");
                io.rx_consume(n);
                assert!(io.rx_pending.is_empty());
                got += n;
            }
        });
        assert!(io.rx_input().is_empty());
    }

    /// Verifies that single-threaded exact-frame-sized reads never expose residual bytes from
    /// preceding larger reads.
    #[test]
    fn st_sized_reads_show_only_new_bytes() {
        let (a, peer) = socket_pair();
        let mut io = IoCore::new();
        io.conn = Transport::Plain(a);
        send_and_read(&mut io, &peer, 0x11, 4, 4);
        send_and_read(&mut io, &peer, 0x22, 37, 37);
        send_and_read(&mut io, &peer, 0x33, READ_BUFFER_LEN, READ_BUFFER_LEN);
        send_and_read(&mut io, &peer, 0x44, 4, 4);
        send_and_read(&mut io, &peer, 0x55, 37, 37);
        send_and_read(&mut io, &peer, 0x66, 3000, 3000);
        send_and_read(&mut io, &peer, 0x77, 4, 4);
        assert_eq!(io.rx.capacity(), READ_BUFFER_LEN);
    }

    /// Verifies that interleaving full-capacity multithreaded reads with smaller bounded reads
    /// exposes only newly received bytes.
    #[test]
    fn full_and_small_reads_show_only_new_bytes() {
        let (a, peer) = socket_pair();
        let mut io = IoCore::new();
        io.conn = Transport::Plain(a);
        send_and_read(&mut io, &peer, 0xA1, READ_BUFFER_LEN, READ_BUFFER_LEN);
        send_and_read(&mut io, &peer, 0xB2, 10, 10);
        send_and_read(&mut io, &peer, 0xC3, 3000, READ_BUFFER_LEN);
        send_and_read(&mut io, &peer, 0xD4, 2 * READ_BUFFER_LEN, READ_BUFFER_LEN);
        send_and_read(&mut io, &peer, 0xE5, 10, READ_BUFFER_LEN);
        assert_eq!(io.rx.capacity(), READ_BUFFER_LEN);
    }

    /// Verifies that partial frames spanning multiple reads preserve byte ordering across
    /// `spill_fresh` and `rx_consume` on both full-capacity and bounded read paths.
    #[test]
    fn a_split_frame_keeps_its_byte_order() {
        let (a, peer) = socket_pair();
        let mut io = IoCore::new();
        io.conn = Transport::Plain(a);
        send_now(&io, &peer, b"abc");
        assert_eq!(io.plain_recv(READ_BUFFER_LEN), Ok(3));
        assert_eq!(io.rx_fresh, 3);
        send_now(&io, &peer, b"defg");
        assert_eq!(io.plain_recv(READ_BUFFER_LEN), Ok(4));
        assert_eq!(io.rx_fresh, 0);
        assert_eq!(io.rx_input(), b"abcdefg");
        io.rx_consume(5);
        assert_eq!(io.rx_input(), b"fg");
        send_now(&io, &peer, b"hi");
        assert_eq!(io.plain_recv(2), Ok(2));
        assert_eq!(io.rx_input(), b"fghi");
        io.rx_consume(4);
        assert!(io.rx_input().is_empty());
        send_now(&io, &peer, b"jklmn");
        assert_eq!(io.plain_recv(5), Ok(5));
        assert_eq!(io.rx_fresh, 5);
        io.rx_consume(2);
        assert_eq!((io.rx_fresh, io.rx_input()), (0, b"lmn".as_slice()));
        send_now(&io, &peer, b"op");
        assert_eq!(io.plain_recv(READ_BUFFER_LEN), Ok(2));
        assert_eq!(io.rx_input(), b"lmnop");
    }
}
