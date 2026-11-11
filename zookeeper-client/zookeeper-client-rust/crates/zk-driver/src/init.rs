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

//! Initialization state machine for legacy C drop in client handles
//!
//! The [`Init`] driver encapsulates the phased setup sequence performed by `zookeeper_init` prior
//! to spawning background I/O or completion worker threads, preserving exact legacy C ordering:
//!
//! 1. **Host string parsing**: Parses connection strings and optional chroot suffixes using legacy
//!    rules (`zk_proto::legacy::connect::parse_init`). Chroot validation failures fail fast before
//!    initiating any network resolution.
//! 2. **Initial host resolution**: Coordinates DNS resolution across configured server
//!    specifications through the `resolve` leaf (`zookeeper.c:1450`) using
//!    `crate::resolve::ResolveRun`. Log buffers are flushed before each leaf invocation and
//!    before PRNG reseeding. If host entry `k` contains a port syntax error, preceding entries
//!    `0..k-1` are resolved first; the driver then logs the port diagnostic and fails with
//!    `EINVAL` without reseeding the PRNG.
//! 3. **SASL initialization point** ([`InitStep::SaslPoint`]): If SASL parameters are supplied,
//!    pauses to allow the C host harness to instantiate its SASL authentication session.
//! 4. **Readiness transition** ([`InitStep::Ready`]): Signals that connection bootstrap is
//!    complete. The selected driver flavor then constructs its runtime handle (e.g., allocating
//!    self-pipe wakers in `threaded::Shared::new`).
//!
//! Whenever [`InitStep::More`] or [`InitStep::SaslPoint`] is returned, the FFI layer emits
//! `ZKRS_MORE`, allowing foreign callers to drain pending log items before resuming the
//! initialization loop.

use zk_jute::JString;
use zk_proto::legacy::connect::{HostParseError, HostParseErrorKind, parse_init};
use zk_proto::legacy::hashtable::c_str;
use zk_proto::{Action, Compat, Config, Connection, DriverMode, HostSpec, LogArg, LogSite, rc};

use crate::host::{DriverTokens, connection_hooks, discard_leftovers, log_record};
use crate::outbox::{Flavor, Item, Outbox, ResumeAt};
use crate::resolve::{ResolveEnd, ResolveRun};
use crate::state::{Core, Env};
use crate::sync::{Mutex, Unlocked};

/// Initialization arguments supplied to `zkrs_handle_new`.
pub struct InitArgs {
    /// Client configuration settings; host addresses, raw text, and chroot are populated from
    /// `host_string`.
    pub cfg: Config,
    /// Raw unparsed host connection string passed to `zookeeper_init`.
    pub host_string: Vec<u8>,
    /// Wire protocol compatibility profile (typically `Compat::LEGACY_C`).
    pub compat: Compat,
    /// Driver threading mode (`DriverMode::MT` for multithreaded or `DriverMode::ST` for single
    /// threaded).
    pub mode: DriverMode,
    /// Outbox event draining and queue-buffering flavor.
    pub flavor: Flavor,
}

/// Diagnostic descriptor representing an initialization failure.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct InitError {
    /// Posix errno code associated with the initialization failure, or `None` if retaining caller
    /// errno.
    pub errno: Option<i32>,
}

/// Phased progression outcome returned by [`Init::step`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InitStep {
    /// Diagnostic log records require draining before initiating blocking host resolution.
    More,
    /// SASL authentication checkpoint reached; caller must initialize foreign SASL state and
    /// resume.
    SaslPoint,
    /// Handle initialization succeeded; ready for conversion into active threaded or polled driver
    /// handles.
    Ready,
    /// Initialization encountered a fatal configuration or resolution error.
    Failed(InitError),
}

/// Internal phase tracking for the handle initialization state machine.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum InitPhase {
    /// Performing initial host DNS resolution.
    Resolve,
    /// Reporting deferred port syntax errors following prefix resolution.
    PortError,
    /// Waiting at the SASL initialization checkpoint.
    Sasl,
    /// Initialization completed successfully.
    Ready,
    /// Initialization failed permanently.
    Failed,
}

/// Active initialization state machine driving handle configuration and DNS bootstrap.
pub struct Init<T: DriverTokens> {
    /// Core protocol connection wrapped in a mutex for thread-safe state inspection.
    pub(crate) core: Mutex<Core<T>>,
    /// Host execution environment providing clock, logging, and resolution hooks.
    pub(crate) env: Env<T>,
    /// Outbox draining flavor governing event queue mechanics.
    pub(crate) flavor: Flavor,
    /// Active iterative host resolution execution tracker.
    pub(crate) run: Option<ResolveRun>,
    /// Deferred port syntax error encountered during connection string parsing.
    pub(crate) port_error: Option<HostParseError>,
    /// Indicates whether SASL authentication parameters were configured.
    pub(crate) sasl: bool,
    /// Current progression phase within the initialization lifecycle.
    pub(crate) phase: InitPhase,
    /// Terminal failure descriptor cached following an initialization error.
    error: Option<InitError>,
}

impl<T: DriverTokens> Init<T> {
    /// Parses connection arguments and constructs a bootstrapped [`Init`] state machine.
    ///
    /// If a port syntax error occurs after one or more valid host specifications, those valid
    /// prefix entries are retained so that DNS resolution can be attempted before logging the
    /// error, strictly matching legacy C behavior.
    ///
    /// Initial diagnostic log records are appended directly into `ob`.
    ///
    /// # Errors
    ///
    /// Returns [`InitError`] containing the corresponding OS errno if string parsing fails.
    pub fn new(args: InitArgs, env: Env<T>, ob: &mut Outbox<T>) -> Result<Self, InitError> {
        let InitArgs {
            mut cfg,
            host_string,
            compat,
            mode,
            flavor,
        } = args;
        let port_error = match parse_init(&host_string) {
            Ok(cs) => {
                cfg.hosts = cs.hosts.into_iter().map(HostSpec::Legacy).collect();
                cfg.host_text = JString::Bytes(cs.host_text);
                cfg.chroot = cs.chroot.map(JString::Bytes);
                None
            }
            Err(mut e) if e.entry.is_some() => {
                // Chroot validation preceded host parsing failure, guaranteeing valid chroot
                // boundary splitting.
                let (host_text, chroot) = split_chroot(&host_string);
                let prefix = std::mem::take(&mut e.prefix);
                cfg.hosts = prefix.into_iter().map(HostSpec::Legacy).collect();
                cfg.host_text = JString::Bytes(host_text);
                cfg.chroot = chroot.map(JString::Bytes);
                Some(e)
            }
            Err(e) => return Err(InitError { errno: e.errno }),
        };
        let sasl = cfg.sasl;
        let (rng, log, dw) = connection_hooks(&env.host);
        let now = env.clock.now();
        let conn = Box::new(Connection::new(cfg, compat, mode, rng, log, dw, now));
        let mut core = Core::new(conn);
        // Initial pump latches the stopped state. Subsequent steps resume execution before
        // polling.
        core.pump(flavor, ob, &*env.host);
        Ok(Self {
            core: Mutex::new(core),
            env,
            flavor,
            run: None,
            port_error,
            sasl,
            phase: InitPhase::Resolve,
            error: None,
        })
    }

    /// Advances the initialization state machine until it yields for external draining or
    /// completes.
    ///
    /// The initial call extracts `Resolve { initial: true }` from the connection's poll
    /// actions and executes the host resolution pipeline. If a deferred port syntax error exists,
    /// resolution proceeds across valid prefix entries before logging the port diagnostic and
    /// returning `InitStep::Failed(EINVAL)`.
    pub fn step(&mut self, proof: &mut Unlocked, ob: &mut Outbox<T>) -> InitStep {
        let step = self.net_step(proof, ob);
        if matches!(step, InitStep::More | InitStep::SaslPoint) {
            ob.set_resume(ResumeAt::Init);
        }
        step
    }

    /// Evaluates initialization phases sequentially until reaching a yield point or terminal
    /// state.
    fn net_step(&mut self, proof: &mut Unlocked, ob: &mut Outbox<T>) -> InitStep {
        loop {
            match self.phase {
                InitPhase::Resolve => {
                    if let Some(step) = self.resolve_step(proof, ob) {
                        return step;
                    }
                }
                InitPhase::PortError => {
                    let errno = self.port_error.take().map_or(Some(EINVAL), |e| {
                        self.push_port_line(&e, ob);
                        e.errno
                    });
                    return self.fail(InitError { errno });
                }
                InitPhase::Sasl => {
                    self.phase = InitPhase::Ready;
                    return InitStep::SaslPoint;
                }
                InitPhase::Ready => return InitStep::Ready,
                InitPhase::Failed => {
                    return InitStep::Failed(self.error.unwrap_or(InitError {
                        errno: Some(EINVAL),
                    }));
                }
            }
        }
    }

    /// Drives the initial hostname resolution phase, returning `None` if the phase transitioned
    /// without yielding.
    fn resolve_step(&mut self, proof: &mut Unlocked, ob: &mut Outbox<T>) -> Option<InitStep> {
        let now = self.env.clock.now();
        {
            let mut core = self.core.lock(proof);
            core.resume_stopped(now);
            if core.pump(self.flavor, ob, &*self.env.host) {
                return Some(InitStep::More);
            }
        }
        if self.run.is_none() {
            let action = self
                .core
                .lock(proof)
                .conn
                .as_deref_mut()
                .and_then(|c| c.poll_action(now));
            match action {
                Some(Action::Resolve {
                    generation, hosts, ..
                }) => self.run = Some(ResolveRun::new(generation, hosts)),
                // Newly initialized connections must initiate resolution before any socket
                // connection can be made.
                _ => {
                    return Some(self.fail(InitError {
                        errno: Some(EINVAL),
                    }));
                }
            }
        }
        let Some(run) = self.run.as_mut() else {
            return Some(self.fail(InitError {
                errno: Some(EINVAL),
            }));
        };
        let cx = self.env.resolve_ctx(&self.core, self.flavor);
        if self.port_error.is_some() {
            // Emulate legacy C client behavior: halt at the malformed entry without reseeding or
            // updating server sets.
            if let ResolveEnd::More = run.run_hosts(&cx, proof, ob) {
                return Some(InitStep::More);
            }
            self.phase = InitPhase::PortError;
            return None;
        }
        match run.run(&cx, proof, ob) {
            ResolveEnd::More => Some(InitStep::More),
            ResolveEnd::Done { rc } if rc == rc::ZOK => {
                self.phase = if self.sasl {
                    InitPhase::Sasl
                } else {
                    InitPhase::Ready
                };
                None
            }
            ResolveEnd::Done { .. } => {
                let errno = self
                    .core
                    .lock(proof)
                    .conn
                    .as_deref_mut()
                    .and_then(Connection::take_errno_hint);
                Some(self.fail(InitError { errno }))
            }
        }
    }

    /// Appends the legacy C diagnostic log message for an invalid or missing port specification
    /// into the outbox.
    fn push_port_line(&self, e: &HostParseError, ob: &mut Outbox<T>) {
        let site = match e.kind {
            HostParseErrorKind::NoPort => LogSite::ResolveNoPort,
            _ => LogSite::ResolveBadPort,
        };
        let args = vec![LogArg::Str(JString::Bytes(e.subject.clone()))];
        if let Some(record) = log_record(&*self.env.host, site, args) {
            ob.push(Item::Log(record));
        }
    }

    /// Transitions the state machine into the terminal `Failed` state and records the error code.
    fn fail(&mut self, error: InitError) -> InitStep {
        self.phase = InitPhase::Failed;
        self.error = Some(error);
        InitStep::Failed(error)
    }

    /// Consumes the initialization handle and releases its core state and environment to construct
    /// an active driver.
    #[must_use]
    pub(crate) fn into_parts(self) -> (Core<T>, Env<T>) {
        (self.core.into_inner(), self.env)
    }

    /// Aborts an uncompleted or failed initialization, cleanly surrendering all pending tokens to
    /// `discard_token`.
    pub fn abandon(self) {
        let (mut core, env) = self.into_parts();
        let host = &*env.host;
        // Drain any unconsumed requeued items and single threaded local FIFO entries so their
        // tokens are discarded.
        let mut ob = Outbox::new();
        core.restore_requeued(&mut ob);
        ob.extend_items(core.local.drain(..));
        ob.finish(host);
        if let Some(conn) = core.conn.take() {
            discard_leftovers(host, conn);
        }
    }
}

/// Posix errno constant for `EINVAL`.
const EINVAL: i32 = zk_proto::legacy::connect::EINVAL;

/// Separates a raw host connection string into the server address component and optional chroot
/// prefix.
///
/// Parsing terminates at the first null byte. A chroot comprising solely `/` is treated as absent.
fn split_chroot(raw: &[u8]) -> (Vec<u8>, Option<Vec<u8>>) {
    let s = c_str(raw);
    match s.iter().position(|&b| b == b'/') {
        Some(i) => {
            let host = s.get(..i).unwrap_or_default().to_vec();
            let root = s.get(i..).unwrap_or_default();
            let root = (root.len() > 1).then(|| root.to_vec());
            (host, root)
        }
        None => (s.to_vec(), None),
    }
}

#[cfg(test)]
mod tests {
    #![allow(
        clippy::unwrap_used,
        clippy::panic,
        clippy::indexing_slicing,
        clippy::arithmetic_side_effects
    )]

    use std::net::SocketAddr;
    use std::sync::Arc;

    use zk_proto::SecretBytes;
    use zk_testkit::tokens::{AuthId, TestTokens};

    use super::*;
    use crate::host::HostLeaves;
    use crate::testutil::TestHost;

    fn args(hosts: &[u8], sasl: bool) -> InitArgs {
        let mut cfg = Config::legacy(10_000);
        cfg.sasl = sasl;
        InitArgs {
            cfg,
            host_string: hosts.to_vec(),
            compat: Compat::LEGACY_C,
            mode: DriverMode::ST,
            flavor: Flavor::St,
        }
    }

    fn host_with(names: &[&[u8]]) -> Arc<TestHost> {
        let host = Arc::new(TestHost::new());
        for (i, name) in names.iter().enumerate() {
            let a: SocketAddr = format!("10.0.0.{}:2181", i + 1).parse().unwrap();
            host.set_addrs(name, vec![a]);
        }
        host
    }

    fn new_init(
        host: &Arc<TestHost>,
        hosts: &[u8],
        sasl: bool,
        ob: &mut Outbox<TestTokens>,
    ) -> Result<Init<TestTokens>, InitError> {
        let dyn_host: Arc<dyn HostLeaves<TestTokens>> = host.clone();
        Init::new(args(hosts, sasl), Env::new(dyn_host), ob)
    }

    /// Drains all items from the outbox and extracts emitted diagnostic log sites in FIFO
    /// sequence.
    fn drain(ob: &mut Outbox<TestTokens>) -> Vec<LogSite> {
        let mut sites = Vec::new();
        while let Some(item) = ob.pop() {
            if let Item::Log(r) = item {
                sites.push(r.site);
            }
        }
        sites
    }

    #[test]
    fn port_error_splits_the_raw_string_as_the_parse_does() {
        let split = |s: &[u8]| split_chroot(s);
        assert_eq!(
            split(b"a:1,b/app\0junk"),
            (b"a:1,b".to_vec(), Some(b"/app".to_vec()))
        );
        assert_eq!(split(b"a:1,b/"), (b"a:1,b".to_vec(), None));
        assert_eq!(split(b"a:1,b"), (b"a:1,b".to_vec(), None));
        assert_eq!(split(b"a:1\0/app"), (b"a:1".to_vec(), None));
        // Verify that only the valid host prefix preceding the malformed port entry is resolved.
        let host = host_with(&[b"a"]);
        let mut ob = Outbox::new();
        let mut init = new_init(&host, b"a:1,b/app", false, &mut ob).unwrap();
        let step = init.step(&mut Unlocked::new_at_entry(), &mut ob);
        assert!(matches!(step, InitStep::Failed(_)));
        assert_eq!(host.resolves(), 1);
        assert_eq!(drain(&mut ob), [LogSite::ResolveNoPort]);
    }

    #[test]
    fn abandon_discards_every_token() {
        let host = host_with(&[b"a"]);
        let mut ob = Outbox::new();
        let init = new_init(&host, b"a:1", false, &mut ob).unwrap();
        {
            let mut core = init.core.lock_owner();
            let conn = core.conn.as_deref_mut().unwrap();
            let cred = SecretBytes::from(b"pw".to_vec());
            let now = zk_proto::Millis(0);
            let added = conn.add_auth(JString::from("digest"), cred, Some(AuthId::new(1)), now);
            assert!(added.is_ok());
            core.local.push_back(Item::AuthResult {
                token: AuthId::new(2),
                rc: 0,
            });
            let mut left = Outbox::new();
            left.push(Item::AuthResult {
                token: AuthId::new(3),
                rc: 0,
            });
            core.requeued = Some(left.requeue());
        }
        init.abandon();
        assert_eq!(host.discarded(), ["auth", "auth", "auth"]);
    }
}
