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

//! Deterministic event driven harness for driving and verifying the ZooKeeper protocol state
//! machine.
//!
//! A [`Harness`] encapsulates a single [`Connection`], a mock ensemble model ([`ServerSim`]),
//! an in-memory network topology ([`Network`]), and a virtual monotonic timeline ([`ManualClock`]).
//! Acting as the nonblocking driver runtime, the harness orchestrates the interest evaluation
//! cycle, executes requested actions, transfers fragmented byte streams across simulated sockets,
//! and drains emitted protocol outputs into a verifiable record. The environment operates free of
//! real I/O or ambient system timers, guaranteeing strict run-to-run reproducibility for a given
//! random seed.

use core::fmt;
use std::collections::VecDeque;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use zk_jute::data::ACL;
use zk_jute::{JBuffer, JString, JVec, opcode};
use zk_proto::compat::CloseMode;
use zk_proto::legacy::connect::{HostSpec as LegacyHostSpec, NativeHost};
use zk_proto::{
    Action, CloseProgress, Compat, Config, ConnectStart, Connection, DriverMode, Endpoint,
    HostSpec, InputDefect, InterestOutcome, Leftovers, LogLevel, Millis, MultiOp, Op, OpResult,
    Origin, Output, PathIn, ProbeOutcome, Request, Route, SaslAddr, SaslCall, SaslCallKind,
    SaslError, SaslSession, SaslStep, SecretBytes, Stage, State, Submitted, TransportFailure, rc,
};

use crate::chunk::{Chunker, Chunking};
use crate::clock::ManualClock;
use crate::hooks::{SharedDefaultWatcher, SharedLogFilter};
use crate::net::{ConnectPlan, ConnectStyle, ECONNREFUSED, ECONNRESET, Network};
use crate::recorder::{ActionRecord, Event, Recorder, SaslKind};
use crate::rng::{LibcRandomClone, RngLog, ScriptedRng, SeededRng};
use crate::sasl::{SaslLog, SaslScript, ScriptedSasl};
use crate::server::{LinkId, LinkStatus, ServerOptions, ServerSim, open_acl};
use crate::tokens::{AuthTicket, TestTokens, Ticket, TokenMint, WatcherId};

/// Maximum byte budget per `poll_transmit` call, matching production plaintext driver limits:
/// protocol frames copy at most 64 KiB per invocation, reserving the remainder for zero-copy
/// frames.
pub const TRANSMIT_MAX: usize = 4 * 1024 * 1024;
/// Default timeout limit for [`Harness::connect`] and [`Harness::wait_for`], in simulated
/// milliseconds.
pub const DEFAULT_LIMIT_MS: u64 = 120_000;
/// Base port number assigned to the first mock ZooKeeper server listener.
pub const BASE_PORT: u16 = 2181;

/// Upper bound on iteration rounds within a single pump or I/O loop to prevent infinite loops.
const LOOP_LIMIT: usize = 100_000;
/// Maximum permissible simulation step count across an entire harness execution before aborting.
const STEP_LIMIT: u64 = 2_000_000;
/// Maximum consecutive active execution steps permitted at a single timestamp before advancing
/// the clock.
const BUSY_LIMIT: u32 = 256;

/// Formats a socket address into an endpoint specification for the active profile representation.
fn host_spec_for(native: bool, addr: SocketAddr) -> HostSpec {
    let host = addr.ip().to_string();
    if native {
        HostSpec::Native(NativeHost {
            host,
            port: addr.port(),
        })
    } else {
        HostSpec::Legacy(LegacyHostSpec {
            host: host.into_bytes(),
            service: addr.port().to_string().into_bytes(),
            port: i32::from(addr.port()),
        })
    }
}

/// Computes the loopback socket address for server `index` (`127.0.0.1:2181 + index`).
#[must_use]
pub fn server_addr(index: usize) -> SocketAddr {
    let off = u16::try_from(index).unwrap_or(u16::MAX);
    SocketAddr::new(
        IpAddr::V4(Ipv4Addr::LOCALHOST),
        BASE_PORT.saturating_add(off),
    )
}

/// Policy governing how the driver supplies inbound bytes to `handle_read`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReadMode {
    /// Delivers byte slices partitioned according to the active receive [`Chunking`] plan.
    Chunked,
    /// Constrains read slices to at most `read_hint()` bytes, emulating the single threaded C
    /// driver loop.
    Hint,
}

/// Policy controlling transport write scheduling relative to protocol interest flags.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WriteGate {
    /// Dispatches write passes only when the most recent interest evaluation requested write
    /// readiness.
    Interest,
    /// Polls and transmits available outbound bytes during every driver iteration regardless of
    /// interest state.
    Always,
}

/// Pseudorandom generator selection for connection shuffling, backoff jitter, and session
/// negotiation.
#[derive(Clone, Debug, PartialEq)]
pub enum RngChoice {
    /// Deterministic [`SeededRng`] initialized from the global harness seed.
    Seeded,
    /// Emulated glibc generator sequence reproducing C client `random()` and `drand48()` calls.
    Libc(Vec<u32>),
    /// Pre-programmed generator returning predetermined numeric sequences before falling back to
    /// seeded generation.
    Scripted {
        /// Pre-scripted 64-bit integer values returned for `random()` invocations.
        randoms: Vec<i64>,
        /// Pre-scripted floating-point values in `[0, 1)` returned for `drand48()` invocations.
        drands: Vec<f64>,
    },
}

/// Terminal error conditions encountered during deterministic harness execution.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum HarnessError {
    /// Expected predicate failed to become satisfied within the allotted simulated time limit.
    Timeout {
        /// Simulated timestamp at which the operation timed out.
        at: Millis,
    },
    /// Simulation exceeded the maximum safety step threshold, indicating a non-terminating loop.
    Runaway {
        /// Total number of discrete execution steps evaluated prior to termination.
        steps: u64,
    },
    /// Request submission was rejected synchronously by the protocol core.
    Rejected {
        /// Correlated ticket tracking the rejected request.
        ticket: Ticket,
        /// Lifecycle pipeline stage at which rejection occurred.
        stage: Stage,
        /// Standard ZooKeeper return code indicating the rejection cause.
        code: i32,
    },
    /// Authentication credential submission was rejected synchronously by the protocol core.
    AuthRejected {
        /// Standard ZooKeeper return code indicating the rejection cause.
        code: i32,
    },
    /// State machine or delivery invariant assertion failure.
    Assert(String),
}

impl fmt::Display for HarnessError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Timeout { at } => write!(f, "timeout at {} ms", at.0),
            Self::Runaway { steps } => write!(f, "runaway after {steps} steps"),
            Self::Rejected {
                ticket,
                stage,
                code,
            } => write!(f, "ticket {} rejected at {stage:?} with {code}", ticket.0),
            Self::AuthRejected { code } => write!(f, "add_auth rejected with {code}"),
            Self::Assert(s) => f.write_str(s),
        }
    }
}

impl std::error::Error for HarnessError {}

/// Details of a request submission rejected synchronously by the protocol state machine.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SubmitRejected {
    /// Tracking ticket associated with the rejected operation.
    pub ticket: Ticket,
    /// State machine pipeline stage at which the rejection was triggered.
    pub stage: Stage,
    /// ZooKeeper error code returned by the core.
    pub code: i32,
}

impl From<SubmitRejected> for HarnessError {
    fn from(r: SubmitRejected) -> Self {
        Self::Rejected {
            ticket: r.ticket,
            stage: r.stage,
            code: r.code,
        }
    }
}

/// Contextual execution state provided to user defined output interception hooks.
pub struct HookCtx<'a> {
    /// Mutable reference to the underlying connection, permitting nested submissions from
    /// completion callbacks.
    pub conn: &'a mut Connection<TestTokens>,
    /// Current simulated harness timestamp.
    pub now: Millis,
    /// Token mint for generating linear completion and authentication tokens.
    pub mint: &'a mut TokenMint,
    /// Event recorder capturing protocol interactions (the active output is recorded after hook
    /// completion).
    pub recorder: &'a mut Recorder,
}

impl HookCtx<'_> {
    /// Submits a queued request from within an output interception hook.
    ///
    /// # Errors
    ///
    /// Returns [`SubmitRejected`] if the connection state machine rejects the operation.
    pub fn submit(&mut self, op: Op<WatcherId>) -> Result<Ticket, SubmitRejected> {
        submit_on(
            self.conn,
            self.mint,
            self.recorder,
            self.now,
            op,
            Route::Queued,
            InputDefect::default(),
        )
    }
}

/// Interception callback invoked for each emitted protocol output prior to recording.
pub type OutputHook = Box<dyn FnMut(&mut HookCtx<'_>, &Output<TestTokens>)>;

/// Final execution artifacts and verification state harvested upon harness teardown.
#[derive(Debug)]
pub struct Finished {
    /// Incomplete completion tokens and buffered outputs still held by the connection at teardown.
    pub leftovers: Leftovers<TestTokens>,
    /// Audit trail of all recorded driver actions, transport events, and protocol outputs.
    pub recorder: Recorder,
    /// Mock server model capturing post-run znode tree state and session tables.
    pub server: ServerSim,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum LinkState {
    None,
    Connecting {
        addr: SocketAddr,
        plan: ConnectPlan,
        ready_at: Option<Millis>,
    },
    Open {
        link: LinkId,
    },
}

#[derive(Debug)]
enum SaslResult {
    Started(Result<(String, SaslStep), SaslError>),
    Stepped(Result<SaslStep, SaslError>),
}

#[derive(Debug)]
struct Deferred {
    at: Millis,
    generation: u32,
    result: SaslResult,
}

/// Fluent builder for constructing and parameterizing a deterministic [`Harness`].
pub struct HarnessBuilder {
    config: Config,
    compat: Compat,
    mode: Option<DriverMode>,
    seed: u64,
    servers: usize,
    server_options: ServerOptions,
    tx_chunking: Chunking,
    rx_chunking: Chunking,
    read_mode: Option<ReadMode>,
    write_gate: WriteGate,
    connect_style: ConnectStyle,
    rng: RngChoice,
    log_level: Option<LogLevel>,
    default_watcher: Option<WatcherId>,
    sasl: SaslScript,
    shuffle: bool,
    start: Millis,
}

impl fmt::Debug for HarnessBuilder {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("HarnessBuilder")
            .field("mode", &self.mode)
            .field("seed", &self.seed)
            .field("servers", &self.servers)
            .finish_non_exhaustive()
    }
}

impl HarnessBuilder {
    /// Initializes a default builder configuration targeting a single server listener at seed 0.
    #[must_use]
    pub fn new(config: Config, compat: Compat) -> Self {
        Self {
            config,
            compat,
            mode: None,
            seed: 0,
            servers: 1,
            server_options: ServerOptions::default(),
            tx_chunking: Chunking::Whole,
            rx_chunking: Chunking::Whole,
            read_mode: None,
            write_gate: WriteGate::Interest,
            connect_style: ConnectStyle::Immediate,
            rng: RngChoice::Seeded,
            log_level: Some(LogLevel::Debug),
            default_watcher: None,
            sasl: SaslScript::default(),
            shuffle: true,
            start: Millis(1_000),
        }
    }

    /// Configures the driver runtime mode (`ST`, `MT`, or `NATIVE`).
    #[must_use]
    pub const fn mode(mut self, mode: DriverMode) -> Self {
        self.mode = Some(mode);
        self
    }

    /// Sets the master pseudorandom seed governing server generation, backoff jitter, and address
    /// shuffling.
    #[must_use]
    pub const fn seed(mut self, seed: u64) -> Self {
        self.seed = seed;
        self
    }

    /// Configures the number of server listeners hosted by the simulated ensemble (deriving
    /// addresses via [`server_addr`]).
    #[must_use]
    pub const fn servers(mut self, n: usize) -> Self {
        self.servers = n;
        self
    }

    /// Overrides the requested session timeout in the client configuration, in milliseconds.
    #[must_use]
    pub const fn timeout(mut self, ms: i32) -> Self {
        self.config.timeout_ms = ms;
        self
    }

    /// Overrides the graceful drain timeout deadline during connection shutdown, in milliseconds.
    #[must_use]
    pub const fn close_timeout(mut self, ms: u32) -> Self {
        self.config.close_timeout_ms = ms;
        self
    }

    /// Supplies custom configuration options to the mock server ensemble.
    #[must_use]
    pub fn server_options(mut self, opts: ServerOptions) -> Self {
        self.server_options = opts;
        self
    }

    /// Configures symmetric byte stream fragmentation for both transmission and reception pipes.
    #[must_use]
    pub fn chunking(mut self, plan: Chunking) -> Self {
        self.tx_chunking = plan.clone();
        self.rx_chunking = plan;
        self
    }

    /// Configures stream fragmentation for client-to-server outbound transmissions.
    #[must_use]
    pub fn tx_chunking(mut self, plan: Chunking) -> Self {
        self.tx_chunking = plan;
        self
    }

    /// Configures stream fragmentation for server-to-client inbound receptions.
    #[must_use]
    pub fn rx_chunking(mut self, plan: Chunking) -> Self {
        self.rx_chunking = plan;
        self
    }

    /// Overrides the input consumption mode (`Chunked` or `Hint`).
    #[must_use]
    pub const fn read_mode(mut self, mode: ReadMode) -> Self {
        self.read_mode = Some(mode);
        self
    }

    /// Configures the write dispatch policy relative to protocol interest flags.
    #[must_use]
    pub const fn write_gate(mut self, gate: WriteGate) -> Self {
        self.write_gate = gate;
        self
    }

    /// Governs whether TCP connection establishment completes immediately or asynchronously with
    /// simulated latency.
    #[must_use]
    pub const fn connect_style(mut self, style: ConnectStyle) -> Self {
        self.connect_style = style;
        self
    }

    /// Configures the pseudorandom number generator backing client runtime decisions.
    #[must_use]
    pub fn rng(mut self, rng: RngChoice) -> Self {
        self.rng = rng;
        self
    }

    /// Sets the maximum logging verbosity threshold emitted during the run, or `None` to silence
    /// logs.
    #[must_use]
    pub const fn log_level(mut self, level: Option<LogLevel>) -> Self {
        self.log_level = level;
        self
    }

    /// Assigns an initial default session watcher identifier.
    #[must_use]
    pub const fn default_watcher(mut self, w: Option<WatcherId>) -> Self {
        self.default_watcher = w;
        self
    }

    /// Configures the scripted SASL authentication dialogue for simulated handshakes.
    #[must_use]
    pub fn sasl(mut self, script: SaslScript) -> Self {
        self.sasl = script;
        self
    }

    /// Controls whether resolved server address lists are shuffled during connection attempts.
    #[must_use]
    pub const fn shuffle(mut self, shuffle: bool) -> Self {
        self.shuffle = shuffle;
        self
    }

    /// Sets the initial timestamp of the simulated monotonic clock.
    #[must_use]
    pub const fn start_at(mut self, t: Millis) -> Self {
        self.start = t;
        self
    }

    /// Assembles and initializes the configured [`Harness`] without executing any initial steps.
    #[must_use]
    pub fn build(self) -> Harness {
        let mode = self.mode.unwrap_or(if self.compat.native_ops {
            DriverMode::NATIVE
        } else {
            DriverMode::ST
        });
        let read_mode = self.read_mode.unwrap_or(if mode == DriverMode::ST {
            ReadMode::Hint
        } else {
            ReadMode::Chunked
        });
        let mut config = self.config;
        let n = self.servers.max(1);
        if config.hosts.is_empty() {
            let mut text = Vec::new();
            for i in 0..n {
                let a = server_addr(i);
                let host = a.ip().to_string();
                if i > 0 {
                    text.push(b',');
                }
                text.extend_from_slice(format!("{host}:{}", a.port()).as_bytes());
                config.hosts.push(host_spec_for(self.compat.native_ops, a));
            }
            config.host_text = JString::Bytes(text);
        }
        let mut server = ServerSim::new(self.server_options, self.seed);
        for i in 0..n {
            server.add_listener(server_addr(i), false);
        }
        let mut net = Network::new();
        net.set_connect_style(self.connect_style);
        let rng_log = RngLog::new();
        let rng: Box<dyn zk_proto::Rng> = match self.rng {
            RngChoice::Seeded => Box::new(SeededRng::new(self.seed).with_log(rng_log.clone())),
            RngChoice::Libc(seeds) => {
                Box::new(LibcRandomClone::new(seeds).with_log(rng_log.clone()))
            }
            RngChoice::Scripted { randoms, drands } => {
                Box::new(ScriptedRng::new(randoms, drands, self.seed).with_log(rng_log.clone()))
            }
        };
        let log_filter = SharedLogFilter::new(self.log_level);
        let default_watcher = SharedDefaultWatcher::new(self.default_watcher);
        let clock = ManualClock::new(self.start);
        let conn = Box::new(Connection::new(
            config,
            self.compat,
            mode,
            rng,
            Box::new(log_filter.clone()),
            Box::new(default_watcher.clone()),
            clock.now(),
        ));
        Harness {
            conn,
            clock,
            server,
            net,
            recorder: Recorder::new(),
            mint: TokenMint::new(),
            compat: self.compat,
            mode,
            link: LinkState::None,
            tx_buf: Vec::new(),
            rx_buf: Vec::new(),
            tx_chunker: Chunker::new(self.tx_chunking),
            rx_chunker: Chunker::new(self.rx_chunking),
            read_mode,
            write_gate: self.write_gate,
            sasl_script: self.sasl,
            sasl_log: SaslLog::new(),
            sasl: None,
            deferred: VecDeque::new(),
            scheduled: VecDeque::new(),
            last_interest: None,
            hook: None,
            reset_after: None,
            write_budget: None,
            shuffle: self.shuffle,
            log_filter,
            default_watcher,
            rng_log,
            progress: false,
            reads_paused: false,
        }
    }
}

/// Deterministic execution harness orchestrating protocol interactions, network doubles, and event
/// auditing.
pub struct Harness {
    conn: Box<Connection<TestTokens>>,
    clock: ManualClock,
    server: ServerSim,
    net: Network,
    recorder: Recorder,
    mint: TokenMint,
    compat: Compat,
    mode: DriverMode,
    link: LinkState,
    tx_buf: Vec<u8>,
    rx_buf: Vec<u8>,
    tx_chunker: Chunker,
    rx_chunker: Chunker,
    read_mode: ReadMode,
    write_gate: WriteGate,
    sasl_script: SaslScript,
    sasl_log: SaslLog,
    sasl: Option<ScriptedSasl>,
    deferred: VecDeque<Deferred>,
    scheduled: VecDeque<(Millis, TransportFailure)>,
    last_interest: Option<InterestOutcome>,
    hook: Option<OutputHook>,
    reset_after: Option<usize>,
    write_budget: Option<usize>,
    shuffle: bool,
    log_filter: SharedLogFilter,
    default_watcher: SharedDefaultWatcher,
    rng_log: RngLog,
    progress: bool,
    reads_paused: bool,
}

impl fmt::Debug for Harness {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Harness")
            .field("now", &self.clock.now())
            .field("conn", &self.conn)
            .field("link", &self.link)
            .finish_non_exhaustive()
    }
}

fn submit_on(
    conn: &mut Connection<TestTokens>,
    mint: &mut TokenMint,
    recorder: &mut Recorder,
    now: Millis,
    op: Op<WatcherId>,
    route: Route,
    defect: InputDefect,
) -> Result<Ticket, SubmitRejected> {
    let (token, ticket) = mint.completion();
    recorder.issue(ticket);
    let req = Request {
        op,
        token,
        route,
        defect,
    };
    match conn.submit(req, now) {
        Ok(Submitted::Queued { xid }) => {
            recorder.push(now, Event::Submitted { ticket, xid });
            Ok(ticket)
        }
        Ok(Submitted::Local(_)) => {
            recorder.push(now, Event::SubmitLocal(ticket));
            Ok(ticket)
        }
        Err(e) => {
            recorder.push(
                now,
                Event::SubmitRejected {
                    ticket,
                    stage: e.stage,
                    code: e.code,
                },
            );
            Err(SubmitRejected {
                ticket,
                stage: e.stage,
                code: e.code,
            })
        }
    }
}

impl Harness {
    /// Creates a harness configured with a single server listener and default driver profile.
    #[must_use]
    pub fn new(config: Config, compat: Compat, seed: u64) -> Self {
        HarnessBuilder::new(config, compat).seed(seed).build()
    }

    /// Initiates a fluent builder pre-configured with client config and compatibility profile.
    #[must_use]
    pub fn builder(config: Config, compat: Compat) -> HarnessBuilder {
        HarnessBuilder::new(config, compat)
    }

    /// Instantiates a legacy single threaded (`ST`) client harness with a 10-second session
    /// timeout.
    #[must_use]
    pub fn st(seed: u64) -> Self {
        HarnessBuilder::new(Config::legacy(10_000), Compat::LEGACY_C)
            .mode(DriverMode::ST)
            .seed(seed)
            .build()
    }

    /// Instantiates a legacy multithreaded (`MT`) client harness with a 10-second session
    /// timeout.
    #[must_use]
    pub fn mt(seed: u64) -> Self {
        HarnessBuilder::new(Config::legacy(10_000), Compat::LEGACY_C)
            .mode(DriverMode::MT)
            .seed(seed)
            .build()
    }

    /// Instantiates a modern native driver harness with a 10-second session timeout and 2-second
    /// close drain deadline.
    #[must_use]
    pub fn native(seed: u64) -> Self {
        let mut config = Config::legacy(10_000);
        config.close_timeout_ms = 2_000;
        HarnessBuilder::new(config, Compat::NATIVE)
            .mode(DriverMode::NATIVE)
            .seed(seed)
            .build()
    }

    // ----- accessors -----

    /// Returns an immutable reference to the underlying protocol connection.
    #[must_use]
    pub const fn conn(&self) -> &Connection<TestTokens> {
        &self.conn
    }

    /// Returns a mutable reference to the underlying connection for direct manipulation (unpolled
    /// until the next pump).
    pub fn conn_mut(&mut self) -> &mut Connection<TestTokens> {
        &mut self.conn
    }

    /// Returns an immutable reference to the mock ZooKeeper server model.
    #[must_use]
    pub const fn server(&self) -> &ServerSim {
        &self.server
    }

    /// Returns a mutable reference to the mock server model to inspect or mutate ensemble state.
    pub fn server_mut(&mut self) -> &mut ServerSim {
        &mut self.server
    }

    /// Returns an immutable reference to the simulated network environment.
    #[must_use]
    pub const fn net(&self) -> &Network {
        &self.net
    }

    /// Returns a mutable reference to the simulated network environment for configuring routing
    /// rules and latency.
    pub fn net_mut(&mut self) -> &mut Network {
        &mut self.net
    }

    /// Returns an immutable reference to the event recorder capturing simulation activity.
    #[must_use]
    pub const fn recorder(&self) -> &Recorder {
        &self.recorder
    }

    /// Returns a mutable reference to the event recorder, enabling injection of diagnostic
    /// annotations.
    pub fn recorder_mut(&mut self) -> &mut Recorder {
        &mut self.recorder
    }

    /// Returns a mutable reference to the linear completion token mint.
    pub fn mint(&mut self) -> &mut TokenMint {
        &mut self.mint
    }

    /// Retrieves the current simulated timestamp from the monotonic clock.
    #[must_use]
    pub const fn now(&self) -> Millis {
        self.clock.now()
    }

    /// Returns the compatibility profile configured for this connection.
    #[must_use]
    pub const fn compat(&self) -> &Compat {
        &self.compat
    }

    /// Returns the active driver threading mode.
    #[must_use]
    pub const fn mode(&self) -> DriverMode {
        self.mode
    }

    /// Obtains the current externally observable connection lifecycle state.
    #[must_use]
    pub fn state(&self) -> State {
        self.conn.state()
    }

    /// Retrieves the outcome generated by the most recent interest resolution cycle, if completed.
    #[must_use]
    pub const fn last_interest(&self) -> Option<InterestOutcome> {
        self.last_interest
    }

    /// Obtains the identifier of the currently established server connection link, if active.
    #[must_use]
    pub const fn link(&self) -> Option<LinkId> {
        match self.link {
            LinkState::Open { link } => Some(link),
            LinkState::None | LinkState::Connecting { .. } => None,
        }
    }

    /// Accesses the audit log recording all pseudorandom number draws executed by the connection.
    #[must_use]
    pub const fn rng_log(&self) -> &RngLog {
        &self.rng_log
    }

    /// Accesses the audit log recording all SASL mechanism exchanges and challenges.
    #[must_use]
    pub const fn sasl_log(&self) -> &SaslLog {
        &self.sasl_log
    }

    /// Returns a reference to the shared dynamic log filter handle.
    #[must_use]
    pub const fn log_filter(&self) -> &SharedLogFilter {
        &self.log_filter
    }

    /// Returns a reference to the shared default session watcher slot.
    #[must_use]
    pub const fn default_watcher(&self) -> &SharedDefaultWatcher {
        &self.default_watcher
    }

    /// Constructs a request builder tailored to the active profile's path conventions.
    #[must_use]
    pub const fn ops(&self) -> Ops {
        Ops {
            native: self.compat.native_ops,
        }
    }

    // ----- settings -----

    /// Registers an output interception hook, invoked before outputs are committed to the
    /// recorder.
    pub fn set_hook(&mut self, hook: OutputHook) {
        self.hook = Some(hook);
    }

    /// Removes any currently registered output interception hook.
    pub fn clear_hook(&mut self) {
        self.hook = None;
    }

    /// Updates the scripted SASL dialogue executed during subsequent authentication handshakes.
    pub fn set_sasl(&mut self, script: SaslScript) {
        self.sasl_script = script;
    }

    /// Configures the transport write scheduling policy.
    pub fn set_write_gate(&mut self, gate: WriteGate) {
        self.write_gate = gate;
    }

    /// Sets the inbound read delivery policy.
    pub fn set_read_mode(&mut self, mode: ReadMode) {
        self.read_mode = mode;
    }

    /// Updates stream fragmentation strategies symmetrically for both read and write directions.
    pub fn set_chunking(&mut self, plan: Chunking) {
        self.tx_chunker = Chunker::new(plan.clone());
        self.rx_chunker = Chunker::new(plan);
    }

    /// Sets an explicit cumulative transmission byte allowance, simulating socket buffer
    /// backpressure.
    pub fn set_write_budget(&mut self, budget: Option<usize>) {
        self.write_budget = budget;
    }

    /// Suspends or resumes consumption of server-delivered bytes, buffering unread data in the
    /// mock server.
    pub fn pause_reads(&mut self, paused: bool) {
        self.reads_paused = paused;
    }

    /// Schedules an abrupt TCP connection reset to occur after transmitting `n` additional bytes.
    pub fn reset_after_bytes(&mut self, n: usize) {
        self.reset_after = Some(n);
    }

    /// Toggles whether resolved server address lists are shuffled during connection attempts.
    pub fn set_shuffle(&mut self, shuffle: bool) {
        self.shuffle = shuffle;
    }

    /// Reconfigures the server connection string dynamically, mirroring `zoo_set_servers`.
    ///
    /// Unlike initial connect sequences, dynamic server updates trigger synchronous address
    /// resolution across the in-memory network double, dispatching `handle_host_resolved` and
    /// recording resolution events.
    pub fn set_servers(&mut self, hosts: Vec<HostSpec>) -> (u64, i32) {
        let now = self.now();
        let generation = self.conn.set_servers(hosts.clone(), now);
        for (i, h) in hosts.iter().enumerate() {
            let r = self.net.resolve(i, h);
            self.conn.handle_host_resolved(generation, r, now);
        }
        let rc = self.conn.handle_resolved(generation, self.shuffle, now);
        self.recorder.push(
            now,
            Event::Action(ActionRecord::Resolve {
                generation,
                hosts: hosts.len(),
                initial: false,
                rc,
            }),
        );
        self.pump(now);
        (generation, rc)
    }

    /// Computes the formatted host specification for the designated server index.
    #[must_use]
    pub fn host_spec(&self, index: usize) -> HostSpec {
        host_spec_for(self.compat.native_ops, server_addr(index))
    }

    // ----- faults -----

    /// Injects an immediate server side TCP reset (`ECONNRESET`) onto the active link.
    pub fn reset_current(&mut self) {
        if let LinkState::Open { link } = self.link {
            self.server.reset_link(link);
        }
    }

    /// Injects an orderly server side socket close, delivering EOF once buffered bytes are
    /// drained.
    pub fn close_current(&mut self) {
        if let LinkState::Open { link } = self.link {
            self.server.close_link(link);
        }
    }

    /// Injects a transport failure synchronously into the connection, tearing down the active
    /// link.
    pub fn inject_failure(&mut self, f: TransportFailure) -> i32 {
        let now = self.now();
        let rc = self.report_failure(f, now);
        self.pump(now);
        rc
    }

    /// Schedules a transport failure to trigger deterministically once the simulation reaches
    /// timestamp `at`.
    pub fn schedule_failure(&mut self, at: Millis, f: TransportFailure) {
        self.scheduled.push_back((at, f));
    }

    // ----- requests -----

    /// Enqueues an operation for transmission to the server, assigning a tracked linear completion
    /// token.
    ///
    /// # Errors
    ///
    /// Returns [`SubmitRejected`] if the connection state machine rejects the operation
    /// synchronously.
    pub fn submit(&mut self, op: Op<WatcherId>) -> Result<Ticket, SubmitRejected> {
        self.submit_with(op, Route::Queued, InputDefect::default())
    }

    /// Submits a request with custom routing constraints and intentional protocol defect flags.
    ///
    /// # Errors
    ///
    /// Returns [`SubmitRejected`] if the connection state machine rejects the operation
    /// synchronously.
    pub fn submit_with(
        &mut self,
        op: Op<WatcherId>,
        route: Route,
        defect: InputDefect,
    ) -> Result<Ticket, SubmitRejected> {
        let now = self.now();
        let r = submit_on(
            &mut self.conn,
            &mut self.mint,
            &mut self.recorder,
            now,
            op,
            route,
            defect,
        );
        self.pump(now);
        r
    }

    /// Advances the simulation until the operation associated with `ticket` completes, returning
    /// the result.
    ///
    /// # Errors
    ///
    /// Returns [`HarnessError::Timeout`] if unfulfilled within [`DEFAULT_LIMIT_MS`], or a
    /// rejection error.
    pub fn wait_for(&mut self, ticket: Ticket) -> Result<OpResult, HarnessError> {
        self.wait_for_within(ticket, DEFAULT_LIMIT_MS)
    }

    /// Advances the simulation until `ticket` completes or the specified millisecond deadline
    /// expires.
    ///
    /// # Errors
    ///
    /// Returns [`HarnessError::Timeout`] if unfulfilled within `limit_ms`, or a rejection error.
    pub fn wait_for_within(
        &mut self,
        ticket: Ticket,
        limit_ms: u64,
    ) -> Result<OpResult, HarnessError> {
        self.run_until(|h| h.recorder.delivery(ticket).is_some(), limit_ms)?;
        match self.recorder.delivery(ticket) {
            Some(crate::recorder::Delivery::Completion(r)) => Ok(r.clone()),
            Some(crate::recorder::Delivery::Local) => Ok(OpResult {
                rc: rc::ZOK,
                zxid: 0,
                origin: Origin::Reply,
                reply: None,
            }),
            Some(crate::recorder::Delivery::Rejected(code)) => {
                let stage = self
                    .recorder
                    .all()
                    .iter()
                    .find_map(|r| match &r.event {
                        Event::SubmitRejected {
                            ticket: t, stage, ..
                        } if *t == ticket => Some(*stage),
                        _ => None,
                    })
                    .unwrap_or(Stage::Validate);
                Err(HarnessError::Rejected {
                    ticket,
                    stage,
                    code,
                })
            }
            None => Err(HarnessError::Timeout { at: self.now() }),
        }
    }

    /// Synchronously submits an operation and blocks until its completion is recorded.
    ///
    /// # Errors
    ///
    /// Propagates submission rejections, timeouts, or simulation loop runaways.
    pub fn call(&mut self, op: Op<WatcherId>) -> Result<OpResult, HarnessError> {
        let t = self.submit(op)?;
        self.wait_for(t)
    }

    /// Dispatches an authentication credential packet to the connection, tracking completion via
    /// an [`AuthTicket`].
    ///
    /// # Errors
    ///
    /// Returns [`HarnessError::AuthRejected`] if the connection rejects the credential submission.
    pub fn add_auth(&mut self, scheme: &str, cred: &[u8]) -> Result<AuthTicket, HarnessError> {
        let now = self.now();
        let (id, ticket) = self.mint.auth();
        let r = self.conn.add_auth(
            JString::from(scheme),
            SecretBytes::from_slice(cred),
            Some(id),
            now,
        );
        let out = match r {
            Ok(()) => {
                self.recorder.push(now, Event::AuthAdded(Some(ticket)));
                Ok(ticket)
            }
            Err(e) => {
                self.recorder.push(
                    now,
                    Event::AuthRejected {
                        ticket: e.done.map(|a| a.ticket()),
                        code: e.code,
                    },
                );
                Err(HarnessError::AuthRejected { code: e.code })
            }
        };
        self.pump(now);
        out
    }

    // ----- the run -----

    /// Drives the simulation loop until the connection reaches an active connected state
    /// (`Connected` or `ConnectedReadOnly`).
    ///
    /// # Errors
    ///
    /// Returns [`HarnessError::Timeout`] if connection negotiation fails to complete within
    /// [`DEFAULT_LIMIT_MS`].
    pub fn connect(&mut self) -> Result<(), HarnessError> {
        self.run_until(|h| h.conn.state().is_connected(), DEFAULT_LIMIT_MS)
    }

    /// Advances simulation time and drives driver steps for the specified millisecond duration.
    ///
    /// # Errors
    ///
    /// Returns [`HarnessError::Runaway`] if the simulation exceeds maximum step limits without
    /// advancing time.
    pub fn run_for(&mut self, ms: u64) -> Result<(), HarnessError> {
        let end = self.now().saturating_add_ms(ms);
        match self.run_until(|h| h.now() >= end, ms.saturating_add(1)) {
            Err(HarnessError::Timeout { .. }) | Ok(()) => Ok(()),
            Err(e) => Err(e),
        }
    }

    /// Advances the simulation iteratively until `pred` evaluates to true, automatically
    /// fast-forwarding time to the next scheduled event when intermediate steps are quiescent.
    ///
    /// # Errors
    ///
    /// Returns [`HarnessError::Timeout`] if the condition is not met within `limit_ms`, or
    /// [`HarnessError::Runaway`].
    pub fn run_until<F>(&mut self, mut pred: F, limit_ms: u64) -> Result<(), HarnessError>
    where
        F: FnMut(&mut Self) -> bool,
    {
        let deadline = self.now().saturating_add_ms(limit_ms);
        let mut steps: u64 = 0;
        let mut busy: u32 = 0;
        loop {
            if pred(self) {
                return Ok(());
            }
            if self.now() > deadline {
                return Err(HarnessError::Timeout { at: self.now() });
            }
            steps = steps.saturating_add(1);
            if steps > STEP_LIMIT {
                return Err(HarnessError::Runaway { steps });
            }
            let now = self.now();
            let progressed = self.step(now);
            if pred(self) {
                return Ok(());
            }
            if progressed && busy < BUSY_LIMIT {
                busy = busy.saturating_add(1);
                continue;
            }
            busy = 0;
            let next = self
                .next_wake(now)
                .min(deadline.saturating_add_ms(1))
                .max(now.saturating_add_ms(1));
            self.clock.advance_to(next);
        }
    }

    /// Calculates the nearest future timestamp at which an internal timer or simulated event
    /// requires execution.
    #[must_use]
    pub fn next_wake(&self, now: Millis) -> Millis {
        let mut best = Millis(u64::MAX);
        let t_ms = u64::try_from(self.conn.recv_timeout().max(0)).unwrap_or(0);
        let interest = match self.last_interest.and_then(|o| o.outputs) {
            Some(i) => u64::from(i.timeout_ms),
            None => t_ms.checked_div(60).unwrap_or(0),
        };
        best = best.min(now.saturating_add_ms(interest));
        if let Some(t) = self.server.next_wake(now) {
            best = best.min(t);
        }
        if let LinkState::Connecting {
            ready_at: Some(t), ..
        } = self.link
        {
            best = best.min(t);
        }
        if let Some(d) = self.deferred.iter().map(|d| d.at).min() {
            best = best.min(d);
        }
        if let Some(s) = self.scheduled.iter().map(|s| s.0).min() {
            best = best.min(s);
        }
        best.max(now.saturating_add_ms(1))
    }

    /// Executes a single discrete driver simulation cycle at simulated timestamp `now`.
    ///
    /// Evaluates scheduled timers, drains pending SASL dialogues, runs interest cycles, completes
    /// in-flight connections, transfers bidirectional stream buffers, and verifies link health.
    /// Returns `true` if observable progress occurred.
    pub fn step(&mut self, now: Millis) -> bool {
        let now = self.clock.advance_to(now);
        self.progress = false;
        self.server.tick(now);
        if self.conn.run_pending_teardown(now) {
            self.progress = true;
        }
        self.pump(now);
        self.run_scheduled(now);
        self.run_deferred(now);
        self.pump(now);
        self.interest_cycle(now);
        self.pump(now);
        self.finish_connect(now);
        self.pump(now);
        let write = match self.write_gate {
            WriteGate::Always => true,
            WriteGate::Interest => self
                .last_interest
                .and_then(|o| o.outputs)
                .is_some_and(|i| i.write),
        };
        if write {
            self.write_loop(now);
        }
        let read = self
            .last_interest
            .and_then(|o| o.outputs)
            .is_some_and(|i| i.read);
        let stalled = if read && !self.reads_paused {
            self.read_loop(now)
        } else {
            false
        };
        self.check_link(now, stalled || !read || self.reads_paused);
        self.pump(now);
        self.progress
    }

    /// Drains all emitted protocol outputs, unblocks stopped barriers, and executes queued actions.
    pub fn pump(&mut self, now: Millis) {
        for _ in 0..LOOP_LIMIT {
            if let Some(o) = self.conn.poll_output() {
                self.progress = true;
                let barrier = o.is_barrier();
                let stopped = if barrier {
                    self.conn.take_stopped()
                } else {
                    None
                };
                self.run_hook(now, &o);
                self.recorder.push(now, Event::Output(o));
                if let Some(s) = stopped {
                    self.resume(s, now);
                }
                continue;
            }
            if let Some(s) = self.conn.take_stopped() {
                self.progress = true;
                self.resume(s, now);
                continue;
            }
            if let Some(a) = self.conn.poll_action(now) {
                self.execute(a, now);
                continue;
            }
            break;
        }
    }

    /// Initiates and completes graceful connection shutdown adhering to the configured
    /// compatibility profile.
    ///
    /// Under `LegacyFailPending`, initiates immediate closure, flushes outbound queues within
    /// [`zk_proto::CLOSE_FLUSH_MS`], and halts transport. Under `Drain`, drives the loop until all
    /// pending operations complete before destroying the link.
    ///
    /// # Errors
    ///
    /// Returns [`HarnessError::Runaway`] if the drain cycle exceeds allowable step thresholds.
    pub fn close(&mut self) -> Result<(), HarnessError> {
        let now = self.now();
        self.conn.begin_close(now);
        self.pump(now);
        match self.compat.close_mode {
            CloseMode::LegacyFailPending => {
                if self.conn.queue_close_op() {
                    self.close_flush();
                    self.close_wait();
                }
            }
            CloseMode::Drain => {
                // If no close frame is queued, `close_step` completes immediately.
                let _ = self.conn.queue_close_op();
                self.pump(now);
                let r = self.run_until(
                    |h| {
                        let t = h.now();
                        h.conn.close_step(t) == CloseProgress::Complete
                    },
                    DEFAULT_LIMIT_MS,
                );
                if let Err(HarnessError::Runaway { steps }) = r {
                    return Err(HarnessError::Runaway { steps });
                }
            }
        }
        let now = self.now();
        self.conn.destroy(now);
        self.pump(now);
        Ok(())
    }

    /// Finalizes harness execution, transferring leftover tokens and model state into a
    /// [`Finished`] bundle.
    #[must_use]
    pub fn finish(self) -> Finished {
        let mut recorder = self.recorder;
        let leftovers = self.conn.into_leftovers();
        for c in &leftovers.completions {
            recorder.leftover(c.ticket());
        }
        for o in &leftovers.outputs {
            if let Output::Completion { token, .. } = o {
                recorder.leftover(token.ticket());
            }
        }
        Finished {
            leftovers,
            recorder,
            server: self.server,
        }
    }

    // ----- assert helpers -----

    /// Asserts that the connection currently occupies the expected lifecycle state.
    ///
    /// # Errors
    ///
    /// Returns [`HarnessError::Assert`] if the actual connection state diverges from `want`.
    pub fn expect_state(&self, want: State) -> Result<(), HarnessError> {
        let got = self.conn.state();
        if got == want {
            Ok(())
        } else {
            Err(HarnessError::Assert(format!(
                "state is {got:?}, want {want:?}"
            )))
        }
    }

    /// Asserts that the designated operation completed exactly once with the specified return
    /// code.
    ///
    /// # Errors
    ///
    /// Returns [`HarnessError::Assert`] if the operation failed to complete, completed multiple
    /// times, or returned a divergent code.
    pub fn expect_completion(&self, ticket: Ticket, want: i32) -> Result<&OpResult, HarnessError> {
        let n = self.recorder.completion_count(ticket);
        if n != 1 {
            return Err(HarnessError::Assert(format!(
                "ticket {} has {n} completions",
                ticket.0
            )));
        }
        match self.recorder.completion(ticket) {
            Some(r) if r.rc == want => Ok(r),
            Some(r) => Err(HarnessError::Assert(format!(
                "ticket {} completed with {}, want {want}",
                ticket.0, r.rc
            ))),
            None => Err(HarnessError::Assert(format!(
                "ticket {} has no completion",
                ticket.0
            ))),
        }
    }

    /// Asserts that every request ticket issued throughout the simulation completed exactly once.
    ///
    /// # Errors
    ///
    /// Returns [`HarnessError::Assert`] detailing all tickets that were dropped, duplicated, or
    /// unissued.
    pub fn expect_exactly_once(&self) -> Result<(), HarnessError> {
        self.recorder
            .check_exactly_once()
            .map_err(HarnessError::Assert)
    }

    // ----- internals -----

    fn resume(&mut self, s: zk_proto::Stopped, now: Millis) {
        let id = s.id();
        self.recorder.push(now, Event::Stopped(id));
        self.conn.resume(s, now);
        self.recorder.push(now, Event::Resumed(id));
    }

    fn run_hook(&mut self, now: Millis, o: &Output<TestTokens>) {
        if let Some(mut hook) = self.hook.take() {
            let mut ctx = HookCtx {
                conn: &mut self.conn,
                now,
                mint: &mut self.mint,
                recorder: &mut self.recorder,
            };
            hook(&mut ctx, o);
            self.hook = Some(hook);
        }
    }

    fn run_scheduled(&mut self, now: Millis) {
        let mut keep = VecDeque::new();
        while let Some((at, f)) = self.scheduled.pop_front() {
            if at <= now {
                let _ = self.report_failure(f, now);
                self.pump(now);
            } else {
                keep.push_back((at, f));
            }
        }
        self.scheduled = keep;
    }

    fn run_deferred(&mut self, now: Millis) {
        let mut keep = VecDeque::new();
        while let Some(d) = self.deferred.pop_front() {
            if d.at <= now {
                self.give_sasl(d.generation, d.result, now);
                self.pump(now);
            } else {
                keep.push_back(d);
            }
        }
        self.deferred = keep;
    }

    fn interest_cycle(&mut self, now: Millis) {
        match self.conn.begin_interest(now) {
            Err(code) => {
                self.recorder.push(now, Event::InterestRefused(code));
                self.last_interest = None;
            }
            Ok(()) => {
                for _ in 0..LOOP_LIMIT {
                    match self.conn.poll_action(now) {
                        Some(a) => self.execute(a, now),
                        None => break,
                    }
                }
                let out = self.conn.finish_interest(now);
                self.recorder.push(now, Event::Interest(out));
                self.last_interest = Some(out);
            }
        }
    }

    fn execute(&mut self, a: Action, now: Millis) {
        match a {
            Action::Resolve {
                generation,
                hosts,
                initial,
            } => {
                for (i, h) in hosts.iter().enumerate() {
                    let r = self.net.resolve(i, h);
                    self.conn.handle_host_resolved(generation, r, now);
                }
                let rc = self.conn.handle_resolved(generation, self.shuffle, now);
                self.recorder.push(
                    now,
                    Event::Action(ActionRecord::Resolve {
                        generation,
                        hosts: hosts.len(),
                        initial,
                        rc,
                    }),
                );
            }
            Action::Connect { endpoint, tls } => {
                self.progress = true;
                self.start_connect(&endpoint, tls.is_some(), now);
            }
            Action::CloseTransport => {
                self.progress = true;
                self.recorder
                    .push(now, Event::Action(ActionRecord::CloseTransport));
                self.drop_link();
            }
            Action::ProbeRw { endpoint, .. } => {
                self.progress = true;
                let addr = endpoint.addr;
                let outcome =
                    self.net
                        .probe(addr)
                        .unwrap_or_else(|| match self.server.listener(addr) {
                            Some(l) if l.accepting && !l.read_only => ProbeOutcome::ReadWrite,
                            Some(l) if l.accepting => ProbeOutcome::NotReadWrite,
                            _ => ProbeOutcome::Failed(None),
                        });
                self.recorder.push(
                    now,
                    Event::Action(ActionRecord::ProbeRw {
                        addr,
                        outcome: outcome.clone(),
                    }),
                );
                self.conn.handle_rw_probe(endpoint, outcome, now);
            }
            Action::Sasl(call) => {
                self.progress = true;
                self.sasl_call(call, now);
            }
        }
    }

    fn start_connect(&mut self, endpoint: &Endpoint, tls: bool, now: Millis) {
        let addr = endpoint.addr;
        self.net.note_connect(addr);
        let plan = self.net.plan(addr).unwrap_or(if self.server.accepts(addr) {
            ConnectPlan::Accept
        } else {
            ConnectPlan::Refuse {
                errno: ECONNREFUSED,
            }
        });
        self.recorder.push(
            now,
            Event::Action(ActionRecord::Connect { addr, tls, plan }),
        );
        self.tx_buf.clear();
        self.rx_buf.clear();
        match plan {
            ConnectPlan::Accept => match self.net.connect_style() {
                ConnectStyle::Immediate => match self.server.accept(addr, now) {
                    Some(link) => {
                        self.conn
                            .handle_connect_started(ConnectStart::Immediate, now);
                        self.link = LinkState::Open { link };
                        self.conn.handle_tcp_connected(now);
                        self.conn.handle_transport_ready(now);
                    }
                    None => {
                        self.conn
                            .handle_connect_started(ConnectStart::InProgress, now);
                        self.link = LinkState::Connecting {
                            addr,
                            plan: ConnectPlan::Refuse {
                                errno: ECONNREFUSED,
                            },
                            ready_at: Some(now),
                        };
                    }
                },
                ConnectStyle::InProgress { delay_ms } => {
                    self.conn
                        .handle_connect_started(ConnectStart::InProgress, now);
                    self.link = LinkState::Connecting {
                        addr,
                        plan,
                        ready_at: Some(now.saturating_add_ms(delay_ms)),
                    };
                }
            },
            ConnectPlan::Refuse { .. } => {
                self.conn
                    .handle_connect_started(ConnectStart::InProgress, now);
                self.link = LinkState::Connecting {
                    addr,
                    plan,
                    ready_at: Some(now),
                };
            }
            ConnectPlan::SocketFail { errno } => {
                // The client core requires an active transport representation to process
                // asynchronous socket creation failures; initialize the connecting link first.
                self.conn
                    .handle_connect_started(ConnectStart::InProgress, now);
                self.link = LinkState::Connecting {
                    addr,
                    plan,
                    ready_at: None,
                };
                let _ = self.report_failure(TransportFailure::SocketCreate { errno }, now);
            }
            ConnectPlan::Hang => {
                self.conn
                    .handle_connect_started(ConnectStart::InProgress, now);
                self.link = LinkState::Connecting {
                    addr,
                    plan,
                    ready_at: None,
                };
            }
        }
    }

    fn finish_connect(&mut self, now: Millis) {
        let LinkState::Connecting {
            addr,
            plan,
            ready_at: Some(at),
        } = self.link
        else {
            return;
        };
        if at > now {
            return;
        }
        self.progress = true;
        match plan {
            ConnectPlan::Accept => match self.server.accept(addr, now) {
                Some(link) => {
                    self.link = LinkState::Open { link };
                    self.conn.handle_tcp_connected(now);
                    self.conn.handle_transport_ready(now);
                }
                None => {
                    let _ = self.report_failure(
                        TransportFailure::TcpRefused {
                            errno: ECONNREFUSED,
                        },
                        now,
                    );
                }
            },
            ConnectPlan::Refuse { errno } => {
                let _ = self.report_failure(TransportFailure::TcpRefused { errno }, now);
            }
            ConnectPlan::SocketFail { errno } => {
                let _ = self.report_failure(TransportFailure::SocketCreate { errno }, now);
            }
            ConnectPlan::Hang => {}
        }
    }

    fn drop_link(&mut self) {
        if let LinkState::Open { link } = self.link {
            self.server.client_close(link);
        }
        self.link = LinkState::None;
        self.tx_buf.clear();
        self.rx_buf.clear();
    }

    fn report_failure(&mut self, f: TransportFailure, now: Millis) -> i32 {
        self.progress = true;
        let rc = self.conn.handle_transport_failed(f.clone(), now);
        self.recorder.push(now, Event::Failure { failure: f, rc });
        // The client core invalidates transport resources upon encountering any fatal transport
        // error; synchronize harness state by tearing down the simulated endpoint link.
        self.drop_link();
        rc
    }

    /// Pumps queued outbound bytes from the client state machine to the mock server, chunking
    /// transfers according to the active transmission strategy and respecting any rate budgets.
    ///
    /// Returns the cumulative number of bytes delivered to the server.
    fn write_loop(&mut self, now: Millis) -> usize {
        let LinkState::Open { link } = self.link else {
            return 0;
        };
        let mut total = 0usize;
        for _ in 0..LOOP_LIMIT {
            if self.server.status(link) != LinkStatus::Open {
                break;
            }
            if self.tx_buf.is_empty()
                && self.conn.poll_transmit(&mut self.tx_buf, TRANSMIT_MAX) == 0
            {
                break;
            }
            let mut n = self.tx_chunker.next_len(self.tx_buf.len());
            if let Some(b) = self.write_budget {
                n = n.min(b);
                self.write_budget = Some(b.saturating_sub(n));
            }
            let mut reset = false;
            if let Some(r) = self.reset_after {
                if r <= n {
                    n = r;
                    reset = true;
                    self.reset_after = None;
                } else {
                    self.reset_after = Some(r.saturating_sub(n));
                }
            }
            if n > 0 {
                let chunk: Vec<u8> = self.tx_buf.drain(..n).collect();
                self.server.client_write(link, &chunk, now);
                self.conn.handle_transmitted(n, now);
                self.recorder.push(now, Event::Transmit { bytes: n });
                self.progress = true;
                total = total.saturating_add(n);
            }
            if reset {
                self.server.reset_link(link);
                break;
            }
            if n == 0 {
                break;
            }
        }
        total
    }

    /// Transfers pending inbound wire frames from the mock server into the client core, fragmenting
    /// chunks according to reception policies or protocol frame hint limits.
    ///
    /// Returns `true` if unconsumed bytes remain buffered because the client parser stalled or
    /// requested backpressure.
    fn read_loop(&mut self, now: Millis) -> bool {
        let LinkState::Open { link } = self.link else {
            return false;
        };
        for _ in 0..LOOP_LIMIT {
            if self.link != (LinkState::Open { link }) {
                return false;
            }
            let more = self.server.take_available(link, now);
            self.rx_buf.extend_from_slice(&more);
            if self.rx_buf.is_empty() {
                return false;
            }
            let avail = self.rx_buf.len();
            let n = match self.read_mode {
                ReadMode::Chunked => self.rx_chunker.next_len(avail),
                ReadMode::Hint => {
                    let h = self.conn.read_hint();
                    if h == 0 {
                        return true;
                    }
                    self.rx_chunker.next_len(avail).min(h)
                }
            };
            let Some(bytes) = self.rx_buf.get(..n) else {
                return true;
            };
            let out = self.conn.handle_read(bytes, now);
            self.recorder.push(
                now,
                Event::Read {
                    len: n,
                    outcome: out,
                },
            );
            let used = out.consumed.min(self.rx_buf.len());
            self.rx_buf.drain(..used);
            if used > 0 {
                self.progress = true;
            }
            self.pump(now);
            if used == 0 {
                return true;
            }
        }
        true
    }

    fn check_link(&mut self, now: Millis, stalled: bool) {
        let LinkState::Open { link } = self.link else {
            return;
        };
        // Once the connection drain phase reaches completion under native client semantics,
        // inbound socket states and remote closures can be safely disregarded.
        if self.compat.close_mode == CloseMode::Drain
            && self.conn.is_closing()
            && self.conn.close_step(now) == CloseProgress::Complete
        {
            return;
        }
        match self.server.status(link) {
            LinkStatus::Open => {}
            LinkStatus::Eof => {
                if self.rx_buf.is_empty() || stalled {
                    let _ = self.report_failure(TransportFailure::Eof, now);
                }
            }
            LinkStatus::Reset | LinkStatus::Gone => {
                self.rx_buf.clear();
                let _ = self.report_failure(TransportFailure::Io { errno: ECONNRESET }, now);
            }
        }
    }

    fn close_flush(&mut self) {
        let start = self.now();
        let end = start.saturating_add_ms(u64::from(zk_proto::CLOSE_FLUSH_MS));
        loop {
            let now = self.now();
            let _ = self.write_loop(now);
            if self.tx_buf.is_empty() || self.link().is_none() || now >= end {
                break;
            }
            self.clock.advance(1);
        }
    }

    fn close_wait(&mut self) {
        let start = self.now();
        let end = start.saturating_add_ms(u64::from(zk_proto::CLOSE_WAIT_MS));
        loop {
            let now = self.now();
            self.server.tick(now);
            let ready = match self.link {
                LinkState::Open { link } => {
                    self.server.has_available(link, now)
                        || self.server.status(link) != LinkStatus::Open
                }
                LinkState::None | LinkState::Connecting { .. } => true,
            };
            if ready || now >= end {
                break;
            }
            let next = self
                .server
                .next_wake(now)
                .unwrap_or(end)
                .min(end)
                .max(now.saturating_add_ms(1));
            self.clock.advance_to(next);
        }
    }

    fn sasl_call(&mut self, call: SaslCall, now: Millis) {
        let generation = call.generation;
        let kind = match &call.kind {
            SaslCallKind::Start => SaslKind::Start,
            SaslCallKind::Step(t) => SaslKind::Step(t.as_ref().map(|s| s.expose().to_vec())),
            SaslCallKind::Dispose => SaslKind::Dispose,
        };
        self.recorder
            .push(now, Event::Action(ActionRecord::Sasl { generation, kind }));
        let result = match call.kind {
            SaslCallKind::Dispose => {
                self.sasl = None;
                return;
            }
            SaslCallKind::Start => {
                if let Some(which) = self.sasl_script.addr_failure {
                    self.give_addr_failure(generation, which, now);
                    return;
                }
                let mut s = ScriptedSasl::new(&self.sasl_script, self.sasl_log.clone());
                let r = s.start();
                self.sasl = Some(s);
                SaslResult::Started(r)
            }
            SaslCallKind::Step(tok) => {
                let script = &self.sasl_script;
                let log = &self.sasl_log;
                let s = self
                    .sasl
                    .get_or_insert_with(|| ScriptedSasl::new(script, log.clone()));
                SaslResult::Stepped(s.step(tok.as_ref().map(SecretBytes::expose)))
            }
        };
        match self.sasl_script.defer_ms {
            Some(d) => self.deferred.push_back(Deferred {
                at: now.saturating_add_ms(d),
                generation,
                result,
            }),
            None => self.give_sasl(generation, result, now),
        }
    }

    fn give_addr_failure(&mut self, generation: u32, which: SaslAddr, now: Millis) {
        let rc = self.conn.handle_sasl_addr_failed(generation, which, now);
        self.recorder
            .push(now, Event::SaslResult { generation, rc });
    }

    fn give_sasl(&mut self, generation: u32, result: SaslResult, now: Millis) {
        self.progress = true;
        let rc = match result {
            SaslResult::Started(r) => self.conn.handle_sasl_started(generation, r, now),
            SaslResult::Stepped(r) => self.conn.handle_sasl_stepped(generation, r, now),
        };
        self.recorder
            .push(now, Event::SaslResult { generation, rc });
    }
}

/// Helper factory for constructing ZooKeeper protocol request operations formatted for specific
/// client path representation profiles (legacy byte vectors vs. native UTF-8 strings).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Ops {
    /// Indicates whether paths are generated as native UTF-8 string descriptors (`PathIn::Native`)
    /// or legacy byte sequences (`PathIn::Legacy`).
    pub native: bool,
}

impl Ops {
    /// Constructs an operation factory configured to emit legacy byte-vector path representations.
    #[must_use]
    pub const fn legacy() -> Self {
        Self { native: false }
    }

    /// Constructs an operation factory configured to emit native UTF-8 string path representations.
    #[must_use]
    pub const fn native() -> Self {
        Self { native: true }
    }

    /// Transforms a string slice into the path encoding appropriate for this operation builder
    /// profile.
    #[must_use]
    pub fn path(&self, p: &str) -> PathIn {
        if self.native {
            PathIn::Native(JString::from(p))
        } else {
            PathIn::Legacy(Some(p.as_bytes().to_vec()))
        }
    }

    /// Returns the standard world-readable, world-writable ZooKeeper access control list
    /// specification.
    #[must_use]
    pub fn open_acl() -> JVec<ACL> {
        JVec::Items(open_acl())
    }

    /// Constructs a standard node creation request (`Op::Create`) with the default open ACL.
    #[must_use]
    pub fn create(&self, p: &str, data: &[u8], mode: i32) -> Op<WatcherId> {
        Op::Create {
            path: self.path(p),
            data: JBuffer::from(data),
            acl: Self::open_acl(),
            mode,
            ttl: -1,
            create2: false,
        }
    }

    /// Constructs an extended node creation request (`create2`) returning stat metadata, using the
    /// default open ACL.
    #[must_use]
    pub fn create2(&self, p: &str, data: &[u8], mode: i32) -> Op<WatcherId> {
        Op::Create {
            path: self.path(p),
            data: JBuffer::from(data),
            acl: Self::open_acl(),
            mode,
            ttl: -1,
            create2: true,
        }
    }

    /// Constructs a node creation request configured with an explicit time-to-live duration.
    #[must_use]
    pub fn create_ttl(&self, p: &str, data: &[u8], mode: i32, ttl: i64) -> Op<WatcherId> {
        Op::Create {
            path: self.path(p),
            data: JBuffer::from(data),
            acl: Self::open_acl(),
            mode,
            ttl,
            create2: true,
        }
    }

    /// Constructs a node deletion request targeted at a specific node version.
    #[must_use]
    pub fn delete(&self, p: &str, version: i32) -> Op<WatcherId> {
        Op::Delete {
            path: self.path(p),
            version,
        }
    }

    /// Constructs a node existence query, optionally registering a watcher.
    #[must_use]
    pub fn exists(&self, p: &str, watch: Option<WatcherId>) -> Op<WatcherId> {
        Op::Exists {
            path: self.path(p),
            watch,
        }
    }

    /// Constructs a payload retrieval request for a path, optionally registering a data watcher.
    #[must_use]
    pub fn get_data(&self, p: &str, watch: Option<WatcherId>) -> Op<WatcherId> {
        Op::GetData {
            path: self.path(p),
            watch,
        }
    }

    /// Constructs a node payload update request conditional upon matching the expected node
    /// version.
    #[must_use]
    pub fn set_data(&self, p: &str, data: &[u8], version: i32) -> Op<WatcherId> {
        Op::SetData {
            path: self.path(p),
            data: JBuffer::from(data),
            version,
        }
    }

    /// Constructs a child enumeration request without requesting associated stat metadata.
    #[must_use]
    pub fn get_children(&self, p: &str, watch: Option<WatcherId>) -> Op<WatcherId> {
        Op::GetChildren {
            path: self.path(p),
            watch,
            with_stat: false,
        }
    }

    /// Constructs a child enumeration request including stat metadata for the parent node.
    #[must_use]
    pub fn get_children2(&self, p: &str, watch: Option<WatcherId>) -> Op<WatcherId> {
        Op::GetChildren {
            path: self.path(p),
            watch,
            with_stat: true,
        }
    }

    /// Constructs an access control list retrieval request for the designated path.
    #[must_use]
    pub fn get_acl(&self, p: &str) -> Op<WatcherId> {
        Op::GetAcl { path: self.path(p) }
    }

    /// Constructs an access control list update request conditional on the target version.
    #[must_use]
    pub fn set_acl(&self, p: &str, acl: Vec<ACL>, version: i32) -> Op<WatcherId> {
        Op::SetAcl {
            path: self.path(p),
            acl: JVec::Items(acl),
            version,
        }
    }

    /// Constructs a leader synchronization request to guarantee linearizable subsequent reads.
    #[must_use]
    pub fn sync(&self, p: &str) -> Op<WatcherId> {
        Op::Sync { path: self.path(p) }
    }

    /// Constructs an atomic transaction request bundling multiple write mutations into a single
    /// unit.
    #[must_use]
    pub fn multi(&self, ops: Vec<MultiOp>) -> Op<WatcherId> {
        Op::Multi { ops }
    }

    /// Constructs a batched multi-read request evaluating multiple read operations concurrently.
    #[must_use]
    pub fn multi_read(&self, ops: Vec<MultiOp>) -> Op<WatcherId> {
        Op::MultiRead { ops }
    }

    /// Constructs a transactional node creation sub-operation with the default open ACL.
    #[must_use]
    pub fn m_create(&self, p: &str, data: &[u8], mode: i32) -> MultiOp {
        MultiOp::Create {
            op_type: opcode::CREATE,
            path: self.path(p),
            data: JBuffer::from(data),
            acl: Self::open_acl(),
            mode,
            ttl: -1,
            defect: InputDefect::default(),
        }
    }

    /// Constructs a transactional node deletion sub-operation conditional on version matching.
    #[must_use]
    pub fn m_delete(&self, p: &str, version: i32) -> MultiOp {
        MultiOp::Delete {
            path: self.path(p),
            version,
            defect: InputDefect::default(),
        }
    }

    /// Constructs a transactional payload update sub-operation conditional on version matching.
    #[must_use]
    pub fn m_set_data(&self, p: &str, data: &[u8], version: i32) -> MultiOp {
        MultiOp::SetData {
            path: self.path(p),
            data: JBuffer::from(data),
            version,
            defect: InputDefect::default(),
        }
    }

    /// Constructs a transactional version assertion sub-operation verifying state without mutation.
    #[must_use]
    pub fn m_check(&self, p: &str, version: i32) -> MultiOp {
        MultiOp::Check {
            path: self.path(p),
            version,
            defect: InputDefect::default(),
        }
    }

    /// Constructs a transactional read sub-operation retrieving node data within a multi-read
    /// batch.
    #[must_use]
    pub fn m_get_data(&self, p: &str) -> MultiOp {
        MultiOp::GetData { path: self.path(p) }
    }

    /// Constructs a transactional read sub-operation enumerating child nodes within a multi-read
    /// batch.
    #[must_use]
    pub fn m_get_children(&self, p: &str) -> MultiOp {
        MultiOp::GetChildren { path: self.path(p) }
    }

    /// Constructs an `AddWatch` request registering a persistent (mode 0) or persistent recursive
    /// (mode 1) watcher.
    #[must_use]
    pub fn add_watch(&self, p: &str, mode: i32, watch: WatcherId) -> Op<WatcherId> {
        Op::AddWatch {
            path: self.path(p),
            mode,
            watch,
        }
    }

    /// Constructs a `RemoveWatches` request dereferencing active watcher registrations by path and
    /// watcher type.
    #[must_use]
    pub fn remove_watches(
        &self,
        p: &str,
        wtype: i32,
        watcher: Option<WatcherId>,
        local: bool,
    ) -> Op<WatcherId> {
        Op::RemoveWatches {
            path: self.path(p),
            wtype,
            all: watcher.is_none(),
            watcher,
            local,
        }
    }

    /// Constructs an ephemeral node query retrieving all ephemeral znode paths created by the
    /// current session matching `prefix`.
    #[must_use]
    pub fn get_ephemerals(&self, prefix: &str) -> Op<WatcherId> {
        Op::GetEphemerals {
            prefix: self.path(prefix),
        }
    }

    /// Constructs a recursive child count query calculating the aggregate number of descendant
    /// nodes under `p`.
    #[must_use]
    pub fn get_all_children_number(&self, p: &str) -> Op<WatcherId> {
        Op::GetAllChildrenNumber { path: self.path(p) }
    }

    /// Constructs an authentication identity query retrieving the authenticated client identities
    /// bound to the current session.
    #[must_use]
    pub const fn who_am_i(&self) -> Op<WatcherId> {
        Op::WhoAmI
    }
}
