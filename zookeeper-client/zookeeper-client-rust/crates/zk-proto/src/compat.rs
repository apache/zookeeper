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

//! Protocol engine behavioral configuration profiles and compatibility policies.
//!
//! [`Compat`] defines the behavioral matrix reconciling historical C client quirks with
//! canonical Java/native driver specifications. The legacy drop-in C shim binds to
//! [`Compat::LEGACY_C`] to ensure strict binary and behavioral parity, whereas modern asynchronous
//! runtimes initialize from [`Compat::NATIVE`] with customizable policy extensions.
//!
//! Single-variant enums mark legacy C defects where historical behavior was demonstrably erroneous
//! or undefined; both profiles fix these defects, so no profile can select the legacy behavior.

pub use crate::legacy::addrvec::AddrEq;

/// Return code assignment strategy for pending requests when migrating from a read-only server to a
/// read-write quorum.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum RwSwitchRc {
    /// In-flight requests are failed with `ZCONNECTIONLOSS`, preventing the spurious `ZOK`
    /// synthetic completions emitted by historical C clients.
    ConnectionLoss,
}

/// Execution semantics governing forced connection termination.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum ForcedClose {
    /// Executes a clean teardown snapshot across active requests rather than replaying abandoned
    /// work on subsequent connections.
    Teardown,
}

/// Address iteration cursor advancement policy following network or handshake failures.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum CursorPolicy {
    /// Cursor advances exactly once per connection failure, preventing C's double-advance defect
    /// that could lock retries to a single host.
    SingleAdvance,
}

/// Error handling policy triggered by SASL negotiation failure.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum SaslFailure {
    /// Emits `AUTH_FAILED` session state notification and initiates transport teardown matching
    /// digest authentication failure semantics.
    Teardown,
}

/// Completion correlation semantics for asynchronous `add_auth` operations.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum AuthCompletion {
    /// Each incoming authentication response strictly completes the corresponding submission token
    /// in FIFO wire order.
    PerRequest,
}

/// Queue placement strategy for authentication packets submitted via `add_auth` on an active
/// connection.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum AuthInsert {
    /// Inserted at the priority boundary ahead of unstarted pending requests, matching C client
    /// priority insertion.
    Priority,
    /// Appended to the tail of the transmission queue in submission order.
    Tail,
}

/// Transmission ordering applied when resending authentication packets during session
/// re-establishment (`finalize_session_establishment`).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum AuthResend {
    /// Reverse-order transmission (`auth_n..auth_1`) reproducing legacy C stack traversal.
    LegacyReverseFront,
    /// Natural forward FIFO transmission (`auth_1..auth_n`).
    Forward,
}

/// Internal data structure implementation backing client watch registrations.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum WatchTable {
    /// Legacy chained hash table reproducing C bucket hashing and iteration order.
    LegacyHashtable,
    /// Canonical path-keyed ordered map preserving registration timestamp ordering.
    Ordered,
}

/// Criteria for detecting session expiration from initial connection response parameters.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ExpiryRule {
    /// Session is declared expired only if the server returns a different session ID than
    /// requested.
    Legacy,
    /// Session is declared expired if the session ID changes or if negotiated timeout is
    /// non-positive (`<= 0`), matching Java.
    Java,
}

/// Operational policy when DNS resolution fails across all configured ensemble host specifications.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum DnsFailure {
    /// Propagates the resolver error code directly from the interest calculation, halting
    /// connection attempts for that turn.
    LegacyRcPreserved,
    /// Preserves the previously resolved address list, allowing connection attempts to continue
    /// against known endpoints.
    UseLastGood,
}

/// Scheduling cadence governing periodic ensemble DNS re-resolution while disconnected.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ResolveCadence {
    /// Re-evaluates DNS resolution on every interest iteration while disconnected.
    EveryIterationWhileDisconnected,
    /// Executes DNS resolution once per exhaustive pass across the candidate host list.
    EachPass,
    /// Throttles DNS queries to occur at most once per configured millisecond duration.
    Throttled {
        /// Throttle interval duration in milliseconds.
        ms: u32,
    },
    /// Disables automatic periodic background resolution outside explicit initialization and
    /// `set_servers` updates.
    Never,
}

/// Reconnection backoff strategy applied after exhausting all ensemble host addresses.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Backoff {
    /// Single interest iteration delay equal to session timeout divided by 60 before re-attempting
    /// connection.
    OneShotSkip,
    /// Exponential backoff with randomized jitter starting at `reconnect_delay_ms` up to a
    /// configured ceiling.
    Deadline,
}

/// Session timeout negotiation parameter submitted in the outgoing `ConnectRequest` frame.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum HandshakeTimeout {
    /// Re-submits previously negotiated session timeout on reconnect handshakes.
    Negotiated,
    /// Consistently transmits configured timeout ([`crate::config::Config::timeout_ms`]) across all
    /// handshakes.
    Requested,
}

/// Protocol behavior during client-initiated graceful connection termination (`begin_close`).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum CloseMode {
    /// Immediately fails pending in-flight requests with `ZCLOSING` upon initiating close.
    LegacyFailPending,
    /// Drains and flushes pending requests prior to transmitting `closeSession` or expiring close
    /// deadline.
    Drain,
}

/// Protocol state machine response upon receiving a negative frame length header on the wire.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum NegativeLen {
    /// Stalls receive parsing until receive timeout deadline expires, matching historical C
    /// behavior.
    StallUntilTimeout,
    /// Immediately treats negative frame length as unrecoverable protocol corruption and terminates
    /// transport.
    Disconnect,
}

/// Znode chroot prefix handling policy.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ChrootPolicy {
    /// Legacy C byte-level prefix concatenation with escape validation.
    LegacyConcatNoEscape,
    /// Strict path component boundary joining and prefix stripping.
    Component,
}

/// Error code translation policy applied to uncompleted requests upon transport disconnection.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum DisconnectRc {
    /// Dispatches specific error return codes mapped to the individual teardown cause (e.g.
    /// `ZSSLCONNECTIONERROR`, `ZOPERATIONTIMEOUT`).
    PerCause,
    /// Normalizes all recoverable disconnections to `ZCONNECTIONLOSS`, reserving specialized codes
    /// strictly for terminal conditions.
    ConnectionLoss,
}

/// TLS certificate verification and hostname validation policy.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TlsVerify {
    /// Standard OpenSSL x509 chain verification without Server Name Indication (SNI) or hostname
    /// matching.
    LegacyChainOnly,
    /// WebPKI certificate verification enforcing host token hostname validation and SNI
    /// negotiation.
    WebPki,
}

/// Comprehensive behavioral configuration profile governing protocol engine execution.
///
/// Encapsulates all policy knobs, timeout treatments, authentication behaviors, and compatibility
/// switches. Callers instantiate one of the standard profiles ([`Compat::LEGACY_C`] or
/// [`Compat::NATIVE`]) and optionally tune specific fields.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[non_exhaustive]
pub struct Compat {
    /// Return code mapping for pending requests during read-only to read-write server failover.
    pub rw_switch_rc: RwSwitchRc,
    /// Execution semantics applied during client-initiated forced close.
    pub forced_close: ForcedClose,
    /// Cursor advancement policy across candidate ensemble addresses following transport failure.
    pub server_cursor: CursorPolicy,
    /// Strategy for evaluating socket address equivalence.
    pub addr_eq: AddrEq,
    /// Teardown and notification policy following SASL handshake negotiation failure.
    pub sasl_failure: SaslFailure,
    /// Completion dispatch semantics for asynchronous `add_auth` submission tokens.
    pub auth_completion: AuthCompletion,
    /// Outbound transmission queue placement policy for authentication frames submitted on active
    /// connections.
    pub auth_insert: AuthInsert,
    /// Rejects `add_auth` calls attempted while in `Closed` (0) state with `ZINVALIDSTATE`,
    /// conforming to ZOOKEEPER-800.
    pub auth_reject_closed: bool,
    /// Emits authentication synchronization barriers to coordinate legacy C single threaded event
    /// processing.
    pub auth_lockpoints: bool,
    /// Outbound transmission ordering applied when resubmitting authentication frames during
    /// session resumption.
    pub auth_resend: AuthResend,
    /// Re-transmits authentication packets during handshake finalization if submitted while
    /// associating.
    pub auth_dup_while_associating: bool,
    /// Underlying data structure backing client watch registrations.
    pub watch_table: WatchTable,
    /// Broadcasts session lifecycle events to all path-registered watchers in addition to the
    /// default watcher.
    pub session_fanout_to_path_watchers: bool,
    /// Purges all registered watchers from internal tables upon receiving session expiration from
    /// the ensemble.
    pub clear_watches_on_expiry: bool,
    /// Maximum payload byte limit for individual `SetWatches` reconstruction packets, or `None` for
    /// unchunked frames.
    pub set_watches_batch: Option<u32>,
    /// Enables modern native protocol operations (such as container nodes, TTLs, and recursive
    /// watch removals).
    pub native_ops: bool,
    /// Maximum allowable wire frame length in bytes, or `None` for unbounded frames.
    pub max_frame_len: Option<u32>,
    /// Criteria for classifying a handshake response as a session expiration.
    pub expiry: ExpiryRule,
    /// Error handling behavior when all configured ensemble endpoints fail DNS resolution.
    pub dns_failure: DnsFailure,
    /// DNS re-resolution cadence while resting in a disconnected state.
    pub resolve_cadence: ResolveCadence,
    /// Backoff strategy scheduled after completing an exhaustive round across all ensemble
    /// addresses.
    pub reconnect_backoff: Backoff,
    /// Session timeout requested during handshake negotiation.
    pub handshake_timeout: HandshakeTimeout,
    /// Transmits periodic heartbeat pings even when normal request traffic is actively outstanding.
    pub ping_when_outstanding: bool,
    /// Lifecycle execution semantics applied during client-initiated graceful closure.
    pub close_mode: CloseMode,
    /// State machine behavior upon encountering a negative frame length header on the wire.
    pub negative_frame_len: NegativeLen,
    /// Namespace prefix handling and boundary validation policy for chroot znodes.
    pub chroot: ChrootPolicy,
    /// Error code translation policy applied to uncompleted requests upon transport disconnection.
    pub disconnect_rc: DisconnectRc,
    /// TLS certificate verification and Server Name Indication (SNI) validation policy.
    pub tls_verify: TlsVerify,
}

/// Standard upper bound on chunked `SetWatches` protocol frame sizes (128 KiB).
pub const SET_WATCHES_BATCH: u32 = 128 * 1024;

/// Default maximum allowable wire frame payload size for native clients (4 MiB).
pub const NATIVE_MAX_FRAME_LEN: u32 = 4 * 1024 * 1024;

impl Compat {
    /// Operational profile enforcing strict behavioral and binary parity with historical C client
    /// libraries.
    pub const LEGACY_C: Self = Self {
        rw_switch_rc: RwSwitchRc::ConnectionLoss,
        forced_close: ForcedClose::Teardown,
        server_cursor: CursorPolicy::SingleAdvance,
        addr_eq: AddrEq::IpAndPort,
        sasl_failure: SaslFailure::Teardown,
        auth_completion: AuthCompletion::PerRequest,
        auth_insert: AuthInsert::Priority,
        auth_reject_closed: true,
        auth_lockpoints: true,
        auth_resend: AuthResend::LegacyReverseFront,
        auth_dup_while_associating: true,
        watch_table: WatchTable::LegacyHashtable,
        session_fanout_to_path_watchers: true,
        clear_watches_on_expiry: false,
        set_watches_batch: Some(SET_WATCHES_BATCH),
        native_ops: false,
        max_frame_len: None,
        expiry: ExpiryRule::Legacy,
        dns_failure: DnsFailure::LegacyRcPreserved,
        resolve_cadence: ResolveCadence::EveryIterationWhileDisconnected,
        reconnect_backoff: Backoff::OneShotSkip,
        handshake_timeout: HandshakeTimeout::Negotiated,
        ping_when_outstanding: false,
        close_mode: CloseMode::LegacyFailPending,
        negative_frame_len: NegativeLen::StallUntilTimeout,
        chroot: ChrootPolicy::LegacyConcatNoEscape,
        disconnect_rc: DisconnectRc::PerCause,
        tls_verify: TlsVerify::LegacyChainOnly,
    };

    /// Operational profile configured with modern asynchronous native Rust client semantics.
    pub const NATIVE: Self = Self {
        rw_switch_rc: RwSwitchRc::ConnectionLoss,
        forced_close: ForcedClose::Teardown,
        server_cursor: CursorPolicy::SingleAdvance,
        addr_eq: AddrEq::IpAndPort,
        sasl_failure: SaslFailure::Teardown,
        auth_completion: AuthCompletion::PerRequest,
        auth_insert: AuthInsert::Tail,
        auth_reject_closed: false,
        auth_lockpoints: false,
        auth_resend: AuthResend::Forward,
        auth_dup_while_associating: false,
        watch_table: WatchTable::Ordered,
        session_fanout_to_path_watchers: false,
        clear_watches_on_expiry: true,
        set_watches_batch: Some(SET_WATCHES_BATCH),
        native_ops: true,
        max_frame_len: Some(NATIVE_MAX_FRAME_LEN),
        expiry: ExpiryRule::Java,
        dns_failure: DnsFailure::UseLastGood,
        resolve_cadence: ResolveCadence::EachPass,
        reconnect_backoff: Backoff::Deadline,
        handshake_timeout: HandshakeTimeout::Requested,
        ping_when_outstanding: true,
        close_mode: CloseMode::Drain,
        negative_frame_len: NegativeLen::Disconnect,
        chroot: ChrootPolicy::Component,
        disconnect_rc: DisconnectRc::ConnectionLoss,
        tls_verify: TlsVerify::WebPki,
    };

    /// Indicates whether authentication synchronization barriers are emitted to the driver.
    #[must_use]
    pub const fn emits_lockpoints(&self) -> bool {
        self.auth_lockpoints
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn profiles_differ_where_the_ledger_says() {
        let l = Compat::LEGACY_C;
        let n = Compat::NATIVE;
        assert_eq!(l.set_watches_batch, n.set_watches_batch);
        assert_eq!(l.addr_eq, AddrEq::IpAndPort);
        assert!(l.emits_lockpoints());
        assert!(!n.emits_lockpoints());
        // Both profiles resolve each authentication completion token upon receiving its
        // corresponding reply, while LEGACY_C preserves C client semantics for remaining auth
        // parameters.
        assert_eq!(l.auth_completion, n.auth_completion);
        assert_eq!(l.auth_insert, AuthInsert::Priority);
        assert_eq!(n.auth_insert, AuthInsert::Tail);
        assert!(l.auth_reject_closed && !n.auth_reject_closed);
        assert_ne!(l, n);
    }
}
