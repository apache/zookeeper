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

//! Strongly typed error models, server return code mappings, and diagnostic classifications.
//!
//! Encapsulates ZooKeeper wire protocol error codes, client side validation failures,
//! transaction rollback details ([`MultiError`]), watcher delivery interruptions ([`WatchError`]),
//! and transport disconnection causes ([`DisconnectCause`]). All server codes map directly
//! to canonical C client `ZOO_ERRORS` and Java `KeeperException.Code` enumerations.

use std::borrow::Cow;
use std::sync::Arc;

use zk_proto::{Cause, Stage};

use crate::observe::OpKind;
use crate::path::PathError;

/// Comprehensive error enumeration representing operation, protocol, or transport failures.
///
/// Designed with value semantics (`Clone`, `Eq`, `Hash`) to allow fan-out propagation across
/// multiple in-flight requests when connection teardowns occur. Wire return codes maintain
/// 1:1 parity with standard C `ZOO_ERRORS` definitions. When recoverable disconnections happen,
/// active requests fail with [`Error::ConnectionLoss`], while underlying socket diagnostics
/// are preserved in [`SessionInfo::last_error`](crate::SessionInfo::last_error).
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum Error {
    /// Server system or internal execution error (-1).
    SystemError,
    /// State inconsistency detected at runtime between client expectation and server state (-2).
    RuntimeInconsistency,
    /// Data corruption or inconsistency detected during transaction processing (-3).
    DataInconsistency,
    /// Transport connection severed or dropped during request transmission or awaiting reply (-4).
    ConnectionLoss,
    /// Protocol frame serialization or deserialization failure encountered while processing
    /// response payloads (-5).
    MarshallingError,
    /// Requested operation is unsupported by the target server ensemble (-6).
    Unimplemented,
    /// client side operation timed out prior to receiving a response from the ensemble (-7).
    OperationTimeout,
    /// Request parameters rejected by the server due to invalid formatting or constraints (-8).
    BadArguments,
    /// Client or server is in an invalid lifecycle state to process this request (-9).
    InvalidState,
    /// Dynamic reconfiguration rejected because the proposed quorum configuration cannot achieve
    /// majority consensus (-13).
    NewConfigNoQuorum,
    /// Reconfiguration rejected because another cluster membership transition is currently in
    /// progress (-14).
    ReconfigInProgress,
    /// Secure transport handshake or TLS communication failure (-15).
    SslConnectionError,
    /// Generic client API invocation error (-100).
    ApiError,
    /// Target znode does not exist at the specified hierarchical path (-101).
    NoNode,
    /// Operation rejected due to insufficient permissions under the znode's Access Control
    /// List (-102).
    NoAuth,
    /// Expected optimistic concurrency version does not match the node's current server
    /// version (-103).
    BadVersion,
    /// Ephemeral znodes cannot serve as parent nodes for child znodes (-108).
    NoChildrenForEphemerals,
    /// Node creation failed because a znode already exists at the specified path (-110).
    NodeExists,
    /// Deletion failed because the target directory znode contains subordinate children (-111).
    NotEmpty,
    /// Client session has expired and been invalidated by the ensemble; requires establishing a
    /// new session (-112).
    SessionExpired,
    /// Invalid callback registration encountered (-113).
    InvalidCallback,
    /// Supplied Access Control List specification is malformed or invalid (-114).
    InvalidAcl,
    /// Authentication credentials rejected by the ensemble; terminal session state (-115).
    AuthFailed,
    /// Operation rejected because the client session is currently closing or has
    /// terminated (-116).
    Closing,
    /// Sentinel code indicating no change or empty result set (-117).
    Nothing,
    /// Session was reassigned to another server connection (-118).
    SessionMoved,
    /// State-modifying write operation attempted against a read-only server instance (-119).
    NotReadOnly,
    /// Ephemeral znode creation rejected on a local, non-replicated session (-120).
    EphemeralOnLocalSession,
    /// Specified watcher registration could not be located for deregistration (-121).
    NoWatcher,
    /// Dynamic cluster reconfiguration has been administratively disabled on the ensemble (-123).
    ReconfigDisabled,
    /// Connection closed because ensemble security policy mandates SASL authentication (-124).
    SessionClosedRequireSaslAuth,
    /// Request throttled by the server due to high cluster load or resource exhaustion (-127).
    ThrottledOp,
    /// Session identifier not recognized by the ensemble (Java client -12).
    UnknownSession,
    /// server side processing deadline expired prior to completing the request (Java client -122).
    RequestTimeout,
    /// Target namespace resource quota exceeded (Java client -125).
    QuotaExceeded,
    /// Unrecognized non-zero wire error return code returned by server.
    Unknown(i32),
    /// Client side path validation failure detected before transmitting request to the network.
    InvalidPath(PathError),
    /// Client side parameter validation failure detected prior to request dispatch.
    InvalidArgument(Cow<'static, str>),
    /// Operation refused locally because client pending request queue capacity has been exceeded.
    TooManyPendingRequests,
}

/// Generates numeric code lookups, reverse conversions, and standard display formatting across
/// server error variants.
macro_rules! server_codes {
    ($($variant:ident = $code:literal => $text:literal,)+) => {
        /// Returns the canonical C client integer error code, or the closest legacy equivalent for
        /// client side errors.
        #[must_use]
        pub fn code(&self) -> i32 {
            match self {
                $(Self::$variant => $code,)+
                Self::Unknown(rc) => *rc,
                Self::InvalidPath(_) | Self::InvalidArgument(_) => -8,
                Self::TooManyPendingRequests => -127,
            }
        }

        /// Translates a raw 32-bit wire protocol status code into an [`Error`]. Returns `None` for
        /// status code 0 (success).
        #[must_use]
        pub fn from_code(code: i32) -> Option<Error> {
            let e = match code {
                0 => return None,
                $($code => Self::$variant,)+
                other => Self::Unknown(other),
            };
            Some(e)
        }

        /// Formats error display output matching Java `KeeperException` naming conventions.
        fn write_text(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            let text = match self {
                $(Self::$variant => $text,)+
                Self::Unknown(rc) => return write!(f, "KeeperErrorCode = Unknown error {rc}"),
                // Client side synthetic errors lack corresponding ZooKeeper wire return codes.
                // Display strings describe the local failure condition.
                Self::InvalidPath(e) => return write!(f, "{e}"),
                Self::InvalidArgument(why) => return write!(f, "invalid argument: {why}"),
                Self::TooManyPendingRequests => {
                    return f.write_str("too many pending requests (max_pending_requests)");
                }
            };
            write!(f, "KeeperErrorCode = {text}")
        }
    };
}

impl Error {
    server_codes! {
        SystemError = -1 => "SystemError",
        RuntimeInconsistency = -2 => "RuntimeInconsistency",
        DataInconsistency = -3 => "DataInconsistency",
        ConnectionLoss = -4 => "ConnectionLoss",
        MarshallingError = -5 => "MarshallingError",
        Unimplemented = -6 => "Unimplemented",
        OperationTimeout = -7 => "OperationTimeout",
        BadArguments = -8 => "BadArguments",
        InvalidState = -9 => "InvalidState",
        UnknownSession = -12 => "Unknown session",
        NewConfigNoQuorum = -13 => "NewConfigNoQuorum",
        ReconfigInProgress = -14 => "ReconfigInProgress",
        SslConnectionError = -15 => "SSL connection error",
        ApiError = -100 => "APIError",
        NoNode = -101 => "NoNode",
        NoAuth = -102 => "NoAuth",
        BadVersion = -103 => "BadVersion",
        NoChildrenForEphemerals = -108 => "NoChildrenForEphemerals",
        NodeExists = -110 => "NodeExists",
        NotEmpty = -111 => "Directory not empty",
        SessionExpired = -112 => "Session expired",
        InvalidCallback = -113 => "Invalid callback",
        InvalidAcl = -114 => "InvalidACL",
        AuthFailed = -115 => "AuthFailed",
        Closing = -116 => "Closing",
        Nothing = -117 => "Nothing",
        SessionMoved = -118 => "Session moved",
        NotReadOnly = -119 => "Not a read-only call",
        EphemeralOnLocalSession = -120 => "Ephemeral node on local session",
        NoWatcher = -121 => "No such watcher",
        RequestTimeout = -122 => "Request timeout",
        ReconfigDisabled = -123 => "Reconfig is disabled",
        SessionClosedRequireSaslAuth = -124 => "Session closed because client failed to authenticate",
        QuotaExceeded = -125 => "Quota has exceeded",
        ThrottledOp = -127 => "Op throttled due to high load",
    }

    /// Indicates whether this error belongs to the connection loss family:
    /// ([`ConnectionLoss`](Self::ConnectionLoss),
    ///  [`OperationTimeout`](Self::OperationTimeout),
    ///  [`SslConnectionError`](Self::SslConnectionError),
    ///  [`RuntimeInconsistency`](Self::RuntimeInconsistency)).
    #[must_use]
    pub fn is_connection_loss(&self) -> bool {
        matches!(
            self,
            Self::ConnectionLoss
                | Self::OperationTimeout
                | Self::SslConnectionError
                | Self::RuntimeInconsistency
        )
    }

    /// Indicates whether this error represents an unrecoverable terminal session termination
    /// ([`SessionExpired`](Self::SessionExpired), [`AuthFailed`](Self::AuthFailed),
    ///  [`Closing`](Self::Closing)).
    #[must_use]
    pub fn is_session_terminal(&self) -> bool {
        matches!(
            self,
            Self::SessionExpired | Self::AuthFailed | Self::Closing
        )
    }

    /// Evaluates whether an operation of kind `op` can be safely retried without risk of duplicate
    /// application.
    ///
    /// Following connection loss errors, non-idempotent operations (such as node creations,
    /// sequential node allocations, transactions, and cluster reconfigurations) return `false`
    /// because in-flight mutation frames may have been committed before network disconnection.
    /// Mutation operations are likewise deemed unsafe unless guarded by known revision versions.
    /// Read-only queries and errors that fail deterministically before execution are always safe
    /// to retry.
    #[must_use]
    pub fn is_retry_safe(&self, op: OpKind) -> bool {
        if !self.is_connection_loss() {
            return true;
        }
        !matches!(
            op,
            OpKind::Create
                | OpKind::Delete
                | OpKind::SetData
                | OpKind::SetAcl
                | OpKind::Multi
                | OpKind::Reconfig
                | OpKind::AddAuth
        )
    }
}

impl std::fmt::Display for Error {
    /// Formats the error string corresponding to ZooKeeper `KeeperException` identifiers.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.write_text(f)
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::InvalidPath(e) => Some(e),
            _ => None,
        }
    }
}

impl From<PathError> for Error {
    fn from(e: PathError) -> Self {
        Self::InvalidPath(e)
    }
}

/// Crate-wide specialized [`Result`](std::result::Result) type defaulting to [`Error`].
pub type Result<T, E = Error> = std::result::Result<T, E>;

/// Thread-safe shared diagnostic error capturing socket I/O, TLS, or protocol teardown causes.
pub(crate) type LastError = Arc<dyn std::error::Error + Send + Sync>;

/// Diagnostic failure returned when initial client connection establishment or builder execution
/// fails.
#[derive(Debug, Clone)]
pub struct ConnectError {
    error: Error,
    cause: Option<LastError>,
}

impl ConnectError {
    /// Instantiates a connect error pairing high-level status with optional low level cause.
    pub(crate) fn new(error: Error, cause: Option<LastError>) -> Self {
        Self { error, cause }
    }

    /// Returns the primary operation error.
    #[must_use]
    pub fn error(&self) -> &Error {
        &self.error
    }
}

impl std::fmt::Display for ConnectError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match &self.cause {
            Some(c) => write!(f, "{}: {}", self.error, c),
            None => write!(f, "{}", self.error),
        }
    }
}

impl std::error::Error for ConnectError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match &self.cause {
            Some(c) => Some(&**c),
            None => None,
        }
    }
}

impl From<ConnectError> for Error {
    fn from(e: ConnectError) -> Self {
        e.error
    }
}

impl From<Error> for ConnectError {
    fn from(e: Error) -> Self {
        Self::new(e, None)
    }
}

/// Root cause classification for transport disconnections when no more specific error is
/// available.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum DisconnectCause {
    /// No heartbeat ping or server response frame received within the negotiated session
    /// inactivity window (2/3 session timeout).
    ReceiveTimeout,
    /// TLS security handshake failed to negotiate before the connection deadline expired.
    TlsHandshakeTimeout,
    /// Inbound transaction XID did not match the header of the oldest pending in-flight request.
    XidMismatch,
    /// Received protocol frame payload was malformed or exceeded the configured frame size limit.
    MalformedFrame,
    /// Ensemble server actively terminated the socket connection.
    ServerClosed,
}

impl std::fmt::Display for DisconnectCause {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::ReceiveTimeout => "no frame arrived within 2/3 of the session timeout",
            Self::TlsHandshakeTimeout => "the TLS handshake did not end before its deadline",
            Self::XidMismatch => "a reply did not match the oldest pending request",
            Self::MalformedFrame => "a frame from the server was malformed or too large",
            Self::ServerClosed => "the server closed the connection",
        })
    }
}

impl std::error::Error for DisconnectCause {}

/// Transactional failure detailing the rollback or execution failure of a `multi` atomic batch.
#[derive(Clone, Debug, PartialEq, Eq)]
#[non_exhaustive]
pub enum MultiError {
    /// Ensemble rejected and atomically rolled back the transaction batch.
    #[non_exhaustive]
    Aborted {
        /// Zero-based position index of the first operation in the batch that triggered the
        /// failure.
        index: usize,
        /// Specific error cause that provoked the transaction abort.
        error: Error,
        /// Positional breakdown of execution status across all batch operations:
        /// `None` for operations preceding `index`, `error` at `index`, and subsequent operations
        /// tagged with `RuntimeInconsistency` per ZooKeeper transaction protocol rules.
        per_op: Vec<Option<Error>>,
    },
    /// The transaction batch request failed as a unit due to connection loss, auth failure, or
    /// client side validation.
    Request(Error),
}

impl From<MultiError> for Error {
    fn from(e: MultiError) -> Self {
        match e {
            MultiError::Aborted { error, .. } => error,
            MultiError::Request(error) => error,
        }
    }
}

impl From<Error> for MultiError {
    fn from(e: Error) -> Self {
        Self::Request(e)
    }
}

impl std::fmt::Display for MultiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Aborted { index, error, .. } => {
                write!(
                    f,
                    "transaction rolled back: operation {index} failed: {error}"
                )
            }
            Self::Request(error) => write!(f, "{error}"),
        }
    }
}

impl std::error::Error for MultiError {}

/// Interruption or termination reason encountered by an asynchronous event watcher stream.
#[derive(Clone, Debug, PartialEq, Eq)]
#[non_exhaustive]
pub enum WatchError {
    /// Watcher was explicitly deregistered via a `remove_watches` invocation.
    Removed,
    /// Watch registration was discarded after network reconnection due to disabled watch
    /// restoration (`restore_watches(false)`).
    ConnectionLost,
    /// Underlying client session expired on the ensemble, invalidating all associated ephemeral
    /// watches.
    SessionExpired,
    /// Session authentication failed during reconnection; watcher registrations terminated.
    AuthFailed,
    /// Client was explicitly closed, detached, or all client instances dropped.
    Closed,
    /// Bounded event delivery queue saturated, resulting in dropped events; the watcher remains
    /// active.
    Lagged {
        /// Count of event notifications discarded due to channel backpressure.
        missed: u64,
    },
    /// Attempted illegal synchronous blocking wait inside an asynchronous event dispatch callback.
    InvalidState,
}

impl std::fmt::Display for WatchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Removed => f.write_str("the watcher was removed"),
            Self::ConnectionLost => {
                f.write_str("the watch was not restored after the connection was lost")
            }
            Self::SessionExpired => f.write_str("the session expired"),
            Self::AuthFailed => f.write_str("authentication failed"),
            Self::Closed => f.write_str("the client is closed"),
            Self::Lagged { missed } => {
                write!(
                    f,
                    "the watcher dropped {missed} events because its buffer was full"
                )
            }
            Self::InvalidState => {
                f.write_str("a blocking wait is not allowed on the IO side of the client")
            }
        }
    }
}

impl std::error::Error for WatchError {}

/// Maps request submission gate rejections from `zk-proto` into appropriate client [`Error`]
/// variants.
pub(crate) fn map_submit_error(stage: Stage, code: i32) -> Error {
    match stage {
        Stage::TooManyPending => Error::TooManyPendingRequests,
        _ => Error::from_code(code).unwrap_or(Error::SystemError),
    }
}

/// Evaluates a raw wire return code into a `Result<(), Error>`, mapping 0 to `Ok(())`.
pub(crate) fn rc_result(rc: i32) -> Result<(), Error> {
    match Error::from_code(rc) {
        None => Ok(()),
        Some(e) => Err(e),
    }
}

/// State transition instruction updating the recorded `SessionInfo::last_error` diagnostic.
#[derive(Clone)]
pub(crate) enum LastErrorUpdate {
    /// Retain previously recorded diagnostic error unchanged.
    Keep,
    /// Reset diagnostic error to empty.
    Clear,
    /// Record the specified diagnostic cause.
    Set(LastError),
}

/// Translates low-level protocol teardown causes and OS socket errors into updated session
/// diagnostics.
///
/// Incorporates OS error numbers from `errno` where available, falls back to synthetic I/O error
/// messages for platform independence, and integrates TLS or SASL handshake diagnostic sources.
pub(crate) fn last_error_for(
    cause: Cause,
    errno: Option<i32>,
    tls: Option<LastError>,
) -> LastErrorUpdate {
    let disconnect = |c: DisconnectCause| -> LastErrorUpdate { LastErrorUpdate::Set(Arc::new(c)) };
    let io = |kind: std::io::ErrorKind, text: &'static str| -> LastErrorUpdate {
        LastErrorUpdate::Set(Arc::new(std::io::Error::new(kind, text)))
    };
    let os = |kind: std::io::ErrorKind, text: &'static str| -> LastErrorUpdate {
        match errno {
            Some(e) => LastErrorUpdate::Set(Arc::new(std::io::Error::from_raw_os_error(e))),
            None => io(kind, text),
        }
    };
    // Transport layer TLS and receive timeouts propagate the underlying probed error, excluding
    // unrelated SASL errors.
    let tls_error = tls
        .as_ref()
        .filter(|e| !is_sasl(e))
        .map(|e| LastErrorUpdate::Set(Arc::clone(e)));
    match cause {
        Cause::ConnectFailed => os(
            std::io::ErrorKind::ConnectionRefused,
            "the TCP connect to the server failed",
        ),
        Cause::Io => os(
            std::io::ErrorKind::ConnectionReset,
            "a socket read or write failed",
        ),
        Cause::SocketCreate => os(
            std::io::ErrorKind::AddrNotAvailable,
            "no socket could be made, or no server address is known",
        ),
        Cause::Eof => disconnect(DisconnectCause::ServerClosed),
        Cause::Tls => tls_error.unwrap_or_else(|| {
            io(
                std::io::ErrorKind::InvalidData,
                "the TLS handshake or a TLS record failed",
            )
        }),
        Cause::TlsHandshakeTimeout => disconnect(DisconnectCause::TlsHandshakeTimeout),
        Cause::RecvTimeout => {
            tls_error.unwrap_or_else(|| disconnect(DisconnectCause::ReceiveTimeout))
        }
        Cause::XidMismatch => disconnect(DisconnectCause::XidMismatch),
        Cause::ProtocolError => disconnect(DisconnectCause::MalformedFrame),
        // Read-write server migrations and administrative reconnects represent normal routing
        // events rather than errors.
        Cause::RwServerFound | Cause::ForcedClose => LastErrorUpdate::Keep,
        // Terminal session states record the fatal disconnect cause explicitly; diagnostic error
        // retention is omitted.
        Cause::SessionExpired | Cause::AuthFailed => LastErrorUpdate::Clear,
        Cause::SaslTeardown => sasl_teardown(tls),
        _ => LastErrorUpdate::Keep,
    }
}

/// Determines whether the supplied diagnostic error represents a native SASL authentication
/// failure.
#[cfg(feature = "sasl")]
fn is_sasl(e: &LastError) -> bool {
    e.is::<crate::sasl::SaslError>()
}

/// Stub predicate returning false when SASL feature support is compiled out.
#[cfg(not(feature = "sasl"))]
fn is_sasl(_e: &LastError) -> bool {
    false
}

/// Generates diagnostic error updates for SASL negotiation teardown events.
#[cfg(feature = "sasl")]
fn sasl_teardown(probed: Option<LastError>) -> LastErrorUpdate {
    use crate::sasl::{SaslError, SaslErrorKind};
    match probed {
        Some(e) if is_sasl(&e) => LastErrorUpdate::Set(e),
        _ => LastErrorUpdate::Set(Arc::new(SaslError::new(
            SaslErrorKind::ServerRejected,
            "the server refused the SASL authentication",
        ))),
    }
}

/// Generates fallback I/O error diagnostic when a SASL teardown occurs on a build without SASL
/// feature support.
#[cfg(not(feature = "sasl"))]
fn sasl_teardown(_probed: Option<LastError>) -> LastErrorUpdate {
    LastErrorUpdate::Set(Arc::new(std::io::Error::new(
        std::io::ErrorKind::PermissionDenied,
        "the SASL negotiation failed",
    )))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Verifies the `Display` text and [`Error`] conversion of [`MultiError::Aborted`], which is
    /// `#[non_exhaustive]` and can only be constructed within this crate.
    #[test]
    fn aborted_text_and_error() {
        let e = MultiError::Aborted {
            index: 2,
            error: Error::BadVersion,
            per_op: vec![None, None, Some(Error::BadVersion)],
        };
        assert_eq!(
            e.to_string(),
            "transaction rolled back: operation 2 failed: KeeperErrorCode = BadVersion"
        );
        assert_eq!(Error::from(e), Error::BadVersion);
    }

    #[test]
    fn submit_errors() {
        assert_eq!(
            map_submit_error(Stage::TooManyPending, -127),
            Error::TooManyPendingRequests
        );
        assert_eq!(
            map_submit_error(Stage::NotEnabled, -6),
            Error::Unimplemented
        );
        assert_eq!(map_submit_error(Stage::Validate, -8), Error::BadArguments);
        assert_eq!(map_submit_error(Stage::Enqueue, -116), Error::Closing);
        assert_eq!(map_submit_error(Stage::Validate, 0), Error::SystemError);
    }

    /// The text of a `Set` update, or a marker for the other kinds.
    fn describe(u: &LastErrorUpdate) -> String {
        match u {
            LastErrorUpdate::Keep => "keep".to_owned(),
            LastErrorUpdate::Clear => "clear".to_owned(),
            LastErrorUpdate::Set(e) => format!("set: {e}"),
        }
    }

    fn set_cause(u: &LastErrorUpdate) -> Option<DisconnectCause> {
        match u {
            LastErrorUpdate::Set(e) => e.downcast_ref::<DisconnectCause>().copied(),
            _ => None,
        }
    }

    fn set_io(u: &LastErrorUpdate) -> Option<std::io::ErrorKind> {
        match u {
            LastErrorUpdate::Set(e) => e.downcast_ref::<std::io::Error>().map(|e| e.kind()),
            _ => None,
        }
    }

    #[test]
    fn last_error_table() {
        use DisconnectCause as D;
        let cases = [
            (Cause::Eof, D::ServerClosed),
            (Cause::TlsHandshakeTimeout, D::TlsHandshakeTimeout),
            (Cause::RecvTimeout, D::ReceiveTimeout),
            (Cause::XidMismatch, D::XidMismatch),
            (Cause::ProtocolError, D::MalformedFrame),
        ];
        for (cause, want) in cases {
            assert_eq!(
                set_cause(&last_error_for(cause, None, None)),
                Some(want),
                "{cause:?}"
            );
        }
        for cause in [
            Cause::ConnectFailed,
            Cause::Io,
            Cause::SocketCreate,
            Cause::Tls,
        ] {
            let u = last_error_for(cause, None, None);
            assert!(set_io(&u).is_some(), "{cause:?}: {}", describe(&u));
        }
        assert_eq!(
            set_io(&last_error_for(Cause::ConnectFailed, None, None)),
            Some(std::io::ErrorKind::ConnectionRefused)
        );
        for cause in [Cause::RwServerFound, Cause::ForcedClose] {
            assert_eq!(describe(&last_error_for(cause, None, None)), "keep");
        }
        for cause in [Cause::SessionExpired, Cause::AuthFailed] {
            assert_eq!(describe(&last_error_for(cause, None, None)), "clear");
        }
        #[cfg(not(feature = "sasl"))]
        assert!(set_io(&last_error_for(Cause::SaslTeardown, None, None)).is_some());
    }

    #[cfg(feature = "sasl")]
    #[test]
    fn a_sasl_teardown_gives_a_sasl_error() {
        use crate::sasl::{CredentialError, SaslError, SaslErrorKind};
        let kind_of = |u: &LastErrorUpdate| match u {
            LastErrorUpdate::Set(e) => e.downcast_ref::<SaslError>().map(|e| e.kind.clone()),
            _ => None,
        };
        let mech: LastError = Arc::new(SaslError::new(
            SaslErrorKind::Credential(CredentialError::Permanent),
            "no password",
        ));
        let u = last_error_for(Cause::SaslTeardown, None, Some(Arc::clone(&mech)));
        assert_eq!(
            kind_of(&u),
            Some(SaslErrorKind::Credential(CredentialError::Permanent))
        );
        let tls: LastError = Arc::new(std::io::Error::other("bad CA file"));
        for probed in [None, Some(tls)] {
            let u = last_error_for(Cause::SaslTeardown, None, probed);
            assert_eq!(kind_of(&u), Some(SaslErrorKind::ServerRejected));
        }
        // SASL authentication errors do not preempt errors associated with TLS failures.
        for cause in [Cause::Tls, Cause::RecvTimeout] {
            let u = last_error_for(cause, None, Some(Arc::clone(&mech)));
            assert_eq!(kind_of(&u), None, "{cause:?}");
        }
    }

    #[test]
    fn the_os_errno_wins_for_the_io_causes() {
        let timed_out = std::io::Error::from_raw_os_error(60);
        for cause in [Cause::ConnectFailed, Cause::Io, Cause::SocketCreate] {
            let (raw, text) = match last_error_for(cause, Some(60), None) {
                LastErrorUpdate::Set(e) => (
                    e.downcast_ref::<std::io::Error>()
                        .and_then(std::io::Error::raw_os_error),
                    e.to_string(),
                ),
                LastErrorUpdate::Keep | LastErrorUpdate::Clear => (None, String::new()),
            };
            assert_eq!(raw, Some(60), "{cause:?}");
            assert_eq!(text, timed_out.to_string(), "{cause:?}");
        }
        // Non-I/O disconnect causes ignore raw OS error numbers.
        let u = last_error_for(Cause::Eof, Some(60), None);
        assert_eq!(set_cause(&u), Some(DisconnectCause::ServerClosed));
    }

    #[test]
    fn tls_probe_error_wins() {
        let tls: LastError = Arc::new(std::io::Error::other("bad CA file"));
        for cause in [Cause::RecvTimeout, Cause::Tls] {
            let u = last_error_for(cause, None, Some(Arc::clone(&tls)));
            assert_eq!(describe(&u), "set: bad CA file", "{cause:?}");
        }
        // Unrelated disconnect causes retain their inherent diagnostic error classification.
        let u = last_error_for(Cause::Eof, None, Some(tls));
        assert_eq!(set_cause(&u), Some(DisconnectCause::ServerClosed));
    }
}
