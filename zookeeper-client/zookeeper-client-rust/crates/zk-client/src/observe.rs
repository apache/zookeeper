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

//! Client telemetry, structured logging integration, and operational metrics distribution.
//!
//! Bridges structured `LogRecord` events emitted by `zk-proto` into ecosystem logging frameworks
//! (`tracing` via feature `tracing`, `log` via feature `log`, or zero-cost compile-time no-op).
//! Renders printf-style C formatting strings lazily only when active log levels permit emission.
//!
//! Also coordinates pluggable user defined [`Metrics`] callbacks. Metrics hooks execute outside
//! the protocol `core` lock (though potentially under the driver `io` lock) within a
//! [`catch_unwind`] boundary so that callback panics never unwind into the I/O event loop. Any
//! metric hook that panics is logged once and deactivated for the remainder of the client's
//! lifecycle.
//!
//! Logging backend dispatch is likewise isolated within a [`catch_unwind`] boundary so that a
//! logger panic discards the individual record without disrupting session execution.

use std::any::Any;
use std::fmt::{self, Write as _};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use zk_proto::{LogArg, LogLevel, LogRecord, LogSite};

use crate::error::Error;
use crate::watch::{EventKind, SessionState};

/// High level request categorization utilized for telemetry metrics and idempotent retry
/// classification.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum OpKind {
    /// Node creation request (`create`).
    Create,
    /// Node deletion request (`delete`).
    Delete,
    /// Node existence check (`exists`).
    Exists,
    /// Node data payload retrieval (`get_data`).
    GetData,
    /// Node data modification (`set_data`).
    SetData,
    /// Node Access Control List inspection (`get_acl`).
    GetAcl,
    /// Node Access Control List update (`set_acl`).
    SetAcl,
    /// Child node enumeration (`get_children`).
    GetChildren,
    /// Total recursive or shallow child count query (`get_all_children_number`).
    GetAllChildrenNumber,
    /// Ephemeral node enumeration owned by session (`get_ephemerals`).
    GetEphemerals,
    /// Asynchronous server state synchronization barrier (`sync`).
    Sync,
    /// Atomic transaction execution (`multi`).
    Multi,
    /// Atomic transaction read-only query batch (`multi_read`).
    MultiRead,
    /// Watcher registration (`add_watch`).
    AddWatch,
    /// Watcher deregistration (`remove_watches`), either explicit or triggered by stream drop.
    RemoveWatches,
    /// Dynamic ensemble configuration retrieval (`get_config`).
    GetConfig,
    /// Dynamic ensemble cluster reconfiguration (`reconfig`).
    Reconfig,
    /// Session authentication credential submission (`add_auth`).
    AddAuth,
    /// Client session identity introspection (`who_am_i`).
    WhoAmI,
    /// Explicit session termination and socket closure (`close`).
    Close,
}

/// Pluggable telemetry interface capturing operational client metrics.
///
/// All methods provide default no-op implementations. The background I/O loop invokes callbacks
/// inside an unwinding boundary outside the `core` lock (though potentially under the driver `io`
/// lock). Callbacks must remain nonblocking to avoid stalling the I/O loop; if any method panics,
/// the hook is automatically deactivated for the client instance.
pub trait Metrics: Send + Sync + 'static {
    /// Invoked upon completion of a ZooKeeper operation, reporting status and round-trip elapsed
    /// latency.
    fn request_completed(&self, op: OpKind, result: Result<(), &Error>, latency: Duration) {
        let _ = (op, result, latency);
    }
    /// Invoked when client connectivity or session lifecycle transitions occur.
    fn state_changed(&self, state: SessionState) {
        let _ = state;
    }
    /// Reserved hook for network throughput accounting.
    fn bytes(&self, sent: usize, received: usize) {
        let _ = (sent, received);
    }
    /// Invoked when an event notification is delivered to an active watcher stream.
    fn watch_event(&self, kind: EventKind) {
        let _ = kind;
    }
    /// Invoked when a bounded watcher channel drops events due to channel saturation.
    fn watch_lagged(&self, missed: u64) {
        let _ = missed;
    }
    /// Invoked with the current count of in-flight pending requests awaiting server replies.
    fn pending_requests(&self, n: usize) {
        let _ = n;
    }
}

/// Telemetry coordinator formatting structured logs and dispatching metric events.
pub(crate) struct Observer {
    /// Optional client identifier label attached to all emitted log records.
    pub(crate) name: Option<Arc<str>>,
    /// Configured metrics collection hook.
    metrics: Option<Arc<dyn Metrics>>,
    /// Atomic flag disabling metric dispatch if a user callback panics.
    disabled: AtomicBool,
}

impl std::fmt::Debug for Observer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Observer")
            .field("name", &self.name)
            .field("metrics", &self.metrics.is_some())
            .finish_non_exhaustive()
    }
}

impl Observer {
    /// Constructs a telemetry observer for the designated client instance.
    pub(crate) fn new(name: Option<String>, metrics: Option<Arc<dyn Metrics>>) -> Self {
        Self {
            name: name.map(Arc::from),
            metrics,
            disabled: AtomicBool::new(false),
        }
    }

    /// Indicates whether a metrics hook is registered for this client.
    pub(crate) fn has_metrics(&self) -> bool {
        self.metrics.is_some()
    }

    /// Evaluates whether log records of severity `level` should be generated by `zk-proto`.
    ///
    /// Executes under core protocol synchronization. MUST remain nonblocking without executing
    /// user code.
    pub(crate) fn log_enabled(&self, level: LogLevel) -> bool {
        backend::enabled(level)
    }

    /// Dispatches a structured log record to the active logging framework backend.
    ///
    /// Selects logging target hierarchy based on event category (`session`, `request`, `watch`,
    /// or `proto`). Runs on the I/O runner, with the `core` and `fifo` locks released.
    pub(crate) fn log(&self, rec: &LogRecord) {
        if !backend::enabled(rec.level) {
            return;
        }
        let site = rec.site.info().name;
        emit_guarded(
            Target::of(rec.site),
            rec.level,
            self.name.as_deref(),
            site,
            &Rendered(rec),
        );
    }

    /// Dispatches request completion telemetry to the metrics hook.
    ///
    /// Maps server return codes through [`Error::from_code`]. Operations like `exists` returning
    /// `NoNode` are treated as successful API query completions (`Ok(())`).
    pub(crate) fn request_completed(&self, op: OpKind, rc: i32, latency: Duration) {
        let ok = rc == 0 || (op == OpKind::Exists && rc == Error::NoNode.code());
        self.run(|m| {
            // Successful completions pass `Ok(())` directly without error code translation.
            let err = if ok { None } else { Error::from_code(rc) };
            match &err {
                None => m.request_completed(op, Ok(()), latency),
                Some(e) => m.request_completed(op, Err(e), latency),
            }
        });
    }

    /// Notifies the metrics hook of a session lifecycle state transition.
    pub(crate) fn state_changed(&self, state: SessionState) {
        self.run(|m| m.state_changed(state));
    }

    /// Notifies the metrics hook of a delivered watcher event.
    pub(crate) fn watch_event(&self, kind: EventKind) {
        self.run(|m| m.watch_event(kind));
    }

    /// Reports dropped watcher event notifications caused by channel queue saturation.
    pub(crate) fn watch_lagged(&self, missed: u64) {
        self.run(|m| m.watch_lagged(missed));
    }

    /// Dispatches updated in-flight pending request queue length telemetry.
    pub(crate) fn pending_requests(&self, n: usize) {
        self.run(|m| m.pending_requests(n));
    }

    /// Executes a closure against the metrics hook guarded by [`catch_unwind`].
    ///
    /// Disables future hook invocations upon catching a panic, logging a single error event.
    fn run(&self, f: impl FnOnce(&dyn Metrics)) {
        let Some(hook) = self.metrics.as_deref() else {
            return;
        };
        if self.disabled.load(Ordering::Acquire) {
            return;
        }
        if let Err(payload) = catch_unwind(AssertUnwindSafe(|| f(hook))) {
            // Emit a diagnostic error only for the initial panic; concurrent panics discard their
            // payloads silently.
            if self.disabled.swap(true, Ordering::AcqRel) {
                discard(payload);
            } else {
                self.hook_panicked(
                    "the metrics hook panicked and is disabled for this client",
                    payload,
                );
            }
        }
    }

    /// Emits an error log event when a user callback or hook panics, safely discarding the panic
    /// payload.
    pub(crate) fn hook_panicked(&self, what: &str, payload: Box<dyn Any + Send>) {
        if backend::enabled(LogLevel::Error) {
            let msg = PanicText {
                what,
                payload: &*payload,
            };
            emit_guarded(
                Target::Client,
                LogLevel::Error,
                self.name.as_deref(),
                "HookPanicked",
                &msg,
            );
        }
        discard(payload);
    }
}

/// Dispatches a log record to the active logging backend within an unwinding boundary.
///
/// Isolates the I/O event loop from panics originating in application logger implementations while
/// keeping logging active for subsequent records.
fn emit_guarded(
    target: Target,
    level: LogLevel,
    client: Option<&str>,
    site: &str,
    msg: &dyn fmt::Display,
) {
    if let Err(payload) = catch_unwind(AssertUnwindSafe(|| {
        backend::emit(target, level, client, site, msg);
    })) {
        discard(payload);
    }
}

/// Discards a panic payload inside an unwinding boundary to guard against secondary panics during
/// payload destruction.
fn discard(payload: Box<dyn Any + Send>) {
    if let Err(again) = catch_unwind(AssertUnwindSafe(move || drop(payload))) {
        std::mem::forget(again);
    }
}

/// Formatter rendering a hook panic context alongside any string panic payload.
struct PanicText<'a> {
    what: &'a str,
    payload: &'a (dyn Any + Send),
}

impl fmt::Display for PanicText<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.what)?;
        if let Some(s) = self.payload.downcast_ref::<&'static str>() {
            write!(f, ": {s}")
        } else if let Some(s) = self.payload.downcast_ref::<String>() {
            write!(f, ": {s}")
        } else {
            Ok(())
        }
    }
}

/// Structured logging target category determining the logger namespace.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
enum Target {
    /// Client-level events (`apache_zookeeper`), such as diagnostic hook panics.
    Client,
    /// Session lifecycle events (`apache_zookeeper::session`), including connections and
    /// authentication.
    Session,
    /// Request dispatch and completion telemetry (`apache_zookeeper::request`).
    Request,
    /// Watcher event delivery and restoration telemetry (`apache_zookeeper::watch`).
    Watch,
    /// Wire protocol framing anomalies and server violations (`apache_zookeeper::proto`).
    Proto,
}

impl Target {
    fn of(site: LogSite) -> Self {
        use LogSite as S;
        match site {
            S::SendGetData
            | S::SendGetConfig
            | S::SendReconfig
            | S::SendSetData
            | S::SendCreate
            | S::SendCreate2
            | S::SendDelete
            | S::SendExists
            | S::SendGetChildren
            | S::SendGetChildren2
            | S::SendSync
            | S::SendGetAcl
            | S::SendSetAcl
            | S::SendMulti
            | S::SendRemoveWatches
            | S::MultiUnimplemented
            | S::CompletionData
            | S::CompletionStat
            | S::CompletionStringList
            | S::CompletionStringListStat
            | S::CompletionString
            | S::CompletionStringStat
            | S::CompletionAclList
            | S::CompletionVoid
            | S::CompletionMulti
            | S::CompletionUnsupported
            | S::ResponseLatency
            | S::ClearedByClose
            | S::QueueingAsync
            | S::SyncCompletion
            | S::SyncUnsupported => Self::Request,
            S::CleanupSessionState
            | S::CleanupSessionConnecting
            | S::FinalizeSessionEvent
            | S::SendSetWatches
            | S::CallingWatcher
            | S::ProcessingWatcherEvent
            | S::ProcessingSetWatches
            | S::WatcherOom => Self::Watch,
            S::ChrootMismatch | S::UnexpectedResponse | S::SockUnexpectedXid => Self::Proto,
            _ => Self::Session,
        }
    }

    /// Returns the static logger target namespace string.
    #[cfg(all(feature = "log", not(feature = "tracing")))]
    const fn name(self) -> &'static str {
        match self {
            Self::Client => "apache_zookeeper",
            Self::Session => "apache_zookeeper::session",
            Self::Request => "apache_zookeeper::request",
            Self::Watch => "apache_zookeeper::watch",
            Self::Proto => "apache_zookeeper::proto",
        }
    }
}

/// Lazy string formatter rendering structured protocol log records into human readable text.
///
/// Deferring string formatting until backend emission ensures that filtered or disabled log events
/// incur zero runtime allocation or string formatting overhead.
struct Rendered<'a>(&'a LogRecord);

impl fmt::Display for Rendered<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write_format(f, self.0.site.info().format, &self.0.args)
    }
}

/// Formats a C-style printf format string using structured arguments from a log record.
///
/// Parses format specifiers, widths, and flags, honoring alternate hex formatting (`#`) and
/// conversion specifiers. Unmatched specifiers render as `?`, surplus arguments append to the
/// output, and trailing newlines are stripped to allow logger backends to handle line
/// termination.
fn write_format(f: &mut fmt::Formatter<'_>, format: &str, args: &[LogArg]) -> fmt::Result {
    let mut args = args.iter();
    let mut chars = format.trim_end_matches('\n').chars().peekable();
    while let Some(c) = chars.next() {
        if c != '%' {
            f.write_char(c)?;
            continue;
        }
        let mut alt = false;
        while let Some(&n) = chars.peek() {
            match n {
                '#' => alt = true,
                '-' | '+' | ' ' | '0' => {}
                _ => break,
            }
            chars.next();
        }
        while chars
            .peek()
            .is_some_and(|n| n.is_ascii_digit() || *n == '.' || *n == '*')
        {
            chars.next();
        }
        while chars
            .peek()
            .is_some_and(|n| matches!(n, 'l' | 'h' | 'z' | 'j' | 't' | 'q' | 'L'))
        {
            chars.next();
        }
        let Some(conv) = chars.next() else {
            return f.write_char('%');
        };
        if conv == '%' {
            f.write_char('%')?;
            continue;
        }
        match args.next() {
            Some(arg) => write_arg(f, arg, conv, alt)?,
            None => f.write_char('?')?,
        }
    }
    for arg in args {
        f.write_char(' ')?;
        write_arg(f, arg, 's', false)?;
    }
    Ok(())
}

/// Formats an individual structured log argument according to the active format specifier.
fn write_arg(f: &mut fmt::Formatter<'_>, arg: &LogArg, conv: char, alt: bool) -> fmt::Result {
    let hex = matches!(conv, 'x' | 'X');
    match arg {
        LogArg::I64(v) if hex => write_hex(f, int_bits(*v), alt),
        LogArg::U64(v) if hex => write_hex(f, *v, alt),
        LogArg::I64(v) => write!(f, "{v}"),
        LogArg::U64(v) => write!(f, "{v}"),
        LogArg::Ptr(v) => write_hex(f, *v, true),
        LogArg::Str(s) => match s.as_bytes() {
            Some(b) => write!(f, "{}", String::from_utf8_lossy(b)),
            None => f.write_str("(null)"),
        },
        LogArg::Endpoint(Some(addr)) => write!(f, "{addr}"),
        LogArg::Endpoint(None) => f.write_str("(none)"),
        LogArg::GaiStr { gai_rc, errno: 0 } => {
            write!(f, "name resolution failed (getaddrinfo rc {gai_rc})")
        }
        LogArg::ErrnoStr(errno) | LogArg::GaiStr { errno, .. } => {
            write!(f, "{}", std::io::Error::from_raw_os_error(*errno))
        }
    }
}

/// Extracts the integer bit pattern for hexadecimal formatting, truncating to 32 bits when
/// appropriate.
fn int_bits(v: i64) -> u64 {
    match i32::try_from(v) {
        Ok(n) => u64::from(u32::from_ne_bytes(n.to_ne_bytes())),
        Err(_) => u64::from_ne_bytes(v.to_ne_bytes()),
    }
}

/// Formats an integer as hexadecimal, prepending `0x` if the alternate flag (`#`) is specified and
/// the value is non-zero.
fn write_hex(f: &mut fmt::Formatter<'_>, v: u64, alt: bool) -> fmt::Result {
    if alt && v != 0 {
        write!(f, "{v:#x}")
    } else {
        write!(f, "{v:x}")
    }
}

/// Structured logging implementation backed by the `tracing` ecosystem (feature `tracing`).
#[cfg(feature = "tracing")]
mod backend {
    use std::fmt::Display;

    use tracing::Level;
    use tracing::level_filters::{LevelFilter, STATIC_MAX_LEVEL};
    use zk_proto::LogLevel;

    use super::Target;

    /// Translates internal [`LogLevel`] values to the corresponding `tracing::Level`.
    fn level_of(level: LogLevel) -> Level {
        match level {
            LogLevel::Error => Level::ERROR,
            LogLevel::Warn => Level::WARN,
            LogLevel::Info => Level::INFO,
            LogLevel::Debug => Level::DEBUG,
        }
    }

    /// Evaluates whether the requested log level is enabled under the current `tracing`
    /// subscriber filter.
    pub(super) fn enabled(level: LogLevel) -> bool {
        let level = level_of(level);
        level <= STATIC_MAX_LEVEL && level <= LevelFilter::current()
    }

    /// Generates static emission dispatchers binding log events to constant target metadata for
    /// `tracing`.
    macro_rules! target_fn {
        ($fn_name:ident, $target:literal) => {
            fn $fn_name(level: LogLevel, client: Option<&str>, site: &str, msg: &dyn Display) {
                match level {
                    LogLevel::Error => tracing::error!(target: $target, client, site, "{}", msg),
                    LogLevel::Warn => tracing::warn!(target: $target, client, site, "{}", msg),
                    LogLevel::Info => tracing::info!(target: $target, client, site, "{}", msg),
                    LogLevel::Debug => tracing::debug!(target: $target, client, site, "{}", msg),
                }
            }
        };
    }

    target_fn!(emit_client, "apache_zookeeper");
    target_fn!(emit_session, "apache_zookeeper::session");
    target_fn!(emit_request, "apache_zookeeper::request");
    target_fn!(emit_watch, "apache_zookeeper::watch");
    target_fn!(emit_proto, "apache_zookeeper::proto");

    /// Dispatches a structured event to `tracing`, annotating with optional client name and site
    /// tags.
    pub(super) fn emit(
        target: Target,
        level: LogLevel,
        client: Option<&str>,
        site: &str,
        msg: &dyn Display,
    ) {
        let f = match target {
            Target::Client => emit_client,
            Target::Session => emit_session,
            Target::Request => emit_request,
            Target::Watch => emit_watch,
            Target::Proto => emit_proto,
        };
        f(level, client, site, msg);
    }
}

/// Standard logging implementation backed by the `log` crate (feature `log` without `tracing`).
#[cfg(all(feature = "log", not(feature = "tracing")))]
mod backend {
    use std::fmt::{self, Display};

    use zk_proto::LogLevel;

    use super::Target;

    /// Translates internal [`LogLevel`] values to the corresponding `log::Level`.
    fn level_of(level: LogLevel) -> log::Level {
        match level {
            LogLevel::Error => log::Level::Error,
            LogLevel::Warn => log::Level::Warn,
            LogLevel::Info => log::Level::Info,
            LogLevel::Debug => log::Level::Debug,
        }
    }

    /// Evaluates whether the requested log level is enabled under the active `log` filter.
    pub(super) fn enabled(level: LogLevel) -> bool {
        let level = level_of(level);
        level <= log::STATIC_MAX_LEVEL && level <= log::max_level()
    }

    /// Formats client instance name prefix when structured logging fields are unsupported.
    struct Prefix<'a>(Option<&'a str>);

    impl Display for Prefix<'_> {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            match self.0 {
                Some(name) => write!(f, "[{name}] "),
                None => Ok(()),
            }
        }
    }

    /// Formats and emits a log message through the `log` facade.
    pub(super) fn emit(
        target: Target,
        level: LogLevel,
        client: Option<&str>,
        site: &str,
        msg: &dyn Display,
    ) {
        log::log!(
            target: target.name(),
            level_of(level),
            "{}{} (site {})",
            Prefix(client),
            msg,
            site
        );
    }
}

/// Zero-cost compile time stub module utilized when all logging backend features are disabled.
#[cfg(not(any(feature = "tracing", feature = "log")))]
mod backend {
    use std::fmt::Display;

    use zk_proto::LogLevel;

    use super::Target;

    /// Returns false unconditionally when logging is compiled out.
    pub(super) fn enabled(_level: LogLevel) -> bool {
        false
    }

    /// No-op emission stub.
    pub(super) fn emit(
        _target: Target,
        _level: LogLevel,
        _client: Option<&str>,
        _site: &str,
        _msg: &dyn Display,
    ) {
    }
}

#[cfg(test)]
mod tests {
    use std::net::SocketAddr;
    use std::sync::Mutex;
    use std::sync::atomic::AtomicUsize;

    use zk_jute::JString;

    use super::*;
    use crate::sync::LockExt;

    /// Test metrics implementation recording invocation counts and optionally injecting panics.
    #[derive(Default)]
    struct Counting {
        calls: AtomicUsize,
        panic_on_state: bool,
        seen: Mutex<Vec<String>>,
    }

    impl Metrics for Counting {
        fn request_completed(&self, op: OpKind, result: Result<(), &Error>, _latency: Duration) {
            self.calls.fetch_add(1, Ordering::SeqCst);
            self.seen
                .lock_unpoisoned()
                .push(format!("req {op:?} {}", result.is_ok()));
        }
        fn state_changed(&self, state: SessionState) {
            self.calls.fetch_add(1, Ordering::SeqCst);
            if self.panic_on_state {
                std::panic::panic_any(String::from("hook bug"));
            }
            self.seen.lock_unpoisoned().push(format!("state {state:?}"));
        }
        fn watch_event(&self, kind: EventKind) {
            self.calls.fetch_add(1, Ordering::SeqCst);
            self.seen.lock_unpoisoned().push(format!("watch {kind:?}"));
        }
        fn watch_lagged(&self, missed: u64) {
            self.calls.fetch_add(1, Ordering::SeqCst);
            self.seen.lock_unpoisoned().push(format!("lagged {missed}"));
        }
        fn pending_requests(&self, n: usize) {
            self.calls.fetch_add(1, Ordering::SeqCst);
            self.seen.lock_unpoisoned().push(format!("pending {n}"));
        }
    }

    fn observer(hook: &Arc<Counting>) -> Observer {
        let m: Arc<dyn Metrics> = Arc::clone(hook) as Arc<dyn Metrics>;
        Observer::new(Some("c1".into()), Some(m))
    }

    fn text(site: LogSite, args: Vec<LogArg>) -> String {
        let rec = LogRecord {
            site,
            level: site.level(),
            args,
        };
        Rendered(&rec).to_string()
    }

    #[test]
    fn every_hook_method_runs() {
        let hook = Arc::new(Counting::default());
        let o = observer(&hook);
        assert!(o.has_metrics());
        o.request_completed(OpKind::GetData, 0, Duration::from_millis(3));
        o.state_changed(SessionState::Connected);
        o.watch_event(EventKind::NodeCreated);
        o.watch_lagged(4);
        o.pending_requests(2);
        assert_eq!(
            *hook.seen.lock_unpoisoned(),
            vec![
                "req GetData true",
                "state Connected",
                "watch NodeCreated",
                "lagged 4",
                "pending 2"
            ]
        );
    }

    #[test]
    fn an_error_code_reaches_the_hook_as_an_error() {
        let hook = Arc::new(Counting::default());
        let o = observer(&hook);
        o.request_completed(OpKind::Delete, -101, Duration::ZERO);
        assert_eq!(*hook.seen.lock_unpoisoned(), vec!["req Delete false"]);
    }

    #[test]
    fn a_panicking_hook_is_disabled_for_this_client() {
        let hook = Arc::new(Counting {
            panic_on_state: true,
            ..Counting::default()
        });
        let o = observer(&hook);
        o.state_changed(SessionState::Connecting);
        assert_eq!(hook.calls.load(Ordering::SeqCst), 1);
        // Subsequent invocations across all metric methods are bypassed once the hook is disabled.
        o.state_changed(SessionState::Connected);
        o.watch_event(EventKind::NodeDeleted);
        o.pending_requests(1);
        o.request_completed(OpKind::Create, 0, Duration::ZERO);
        assert_eq!(hook.calls.load(Ordering::SeqCst), 1);
        assert!(hook.seen.lock_unpoisoned().is_empty());

        // Independent client instances sharing the same hook execute invocations normally.
        let other = observer(&hook);
        other.watch_lagged(9);
        assert_eq!(hook.calls.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn no_hook_is_a_no_op() {
        let o = Observer::new(None, None);
        assert!(!o.has_metrics());
        o.state_changed(SessionState::Closed);
        o.request_completed(OpKind::Close, 0, Duration::ZERO);
        o.pending_requests(0);
    }

    #[test]
    fn sites_map_to_the_four_targets() {
        assert_eq!(Target::of(LogSite::SendGetData), Target::Request);
        assert_eq!(Target::of(LogSite::CompletionVoid), Target::Request);
        assert_eq!(Target::of(LogSite::CallingWatcher), Target::Watch);
        assert_eq!(Target::of(LogSite::SendSetWatches), Target::Watch);
        assert_eq!(Target::of(LogSite::SockUnexpectedXid), Target::Proto);
        assert_eq!(Target::of(LogSite::ChrootMismatch), Target::Proto);
        assert_eq!(Target::of(LogSite::SessionEstablished), Target::Session);
        assert_eq!(Target::of(LogSite::SockConnectFailed), Target::Session);
        assert_eq!(Target::of(LogSite::AuthSchemeFailed), Target::Session);
    }

    #[test]
    fn a_record_renders_its_site_format() {
        let addr: SocketAddr = "127.0.0.1:2181".parse().unwrap();
        assert_eq!(
            text(
                LogSite::SessionEstablished,
                vec![
                    LogArg::Endpoint(Some(addr)),
                    LogArg::Ptr(0x1234),
                    LogArg::I64(4000),
                    LogArg::Str(JString::from("")),
                ]
            ),
            "session establishment complete on server 127.0.0.1:2181, \
             sessionId=0x1234, negotiated timeout=4000 "
        );
        // Trailing newline delimiters present in C format strings are normalized and omitted.
        assert_eq!(
            text(
                LogSite::Closing,
                vec![LogArg::Ptr(0), LogArg::Endpoint(None)]
            ),
            "Closing zookeeper sessionId=0 to (none)"
        );
        // `%#x` conversion for signed 32-bit integers renders full two's complement hexadecimal
        // representations.
        assert_eq!(
            text(
                LogSite::CompletionVoid,
                vec![LogArg::I64(-2), LogArg::I64(0), LogArg::I64(-101)]
            ),
            "Calling COMPLETION_VOID for xid=0xfffffffe failed=0 rc=-101"
        );
        assert_eq!(
            text(
                LogSite::SendGetData,
                vec![
                    LogArg::I64(5),
                    LogArg::Str(JString::Null),
                    LogArg::Endpoint(Some(addr))
                ]
            ),
            "Sending request xid=0x5 for path [(null)] to 127.0.0.1:2181"
        );
    }

    #[test]
    fn a_missing_or_extra_argument_is_not_lost() {
        assert_eq!(
            text(LogSite::AuthSchemeSucceeded, Vec::new()),
            "Authentication scheme ? succeeded"
        );
        assert_eq!(
            text(LogSite::PingResponse, vec![LogArg::I64(3), LogArg::U64(9)]),
            "Got ping response in 3 ms 9"
        );
        assert_eq!(
            text(
                LogSite::ResolveGai,
                vec![LogArg::GaiStr {
                    gai_rc: 8,
                    errno: 0
                }]
            ),
            "getaddrinfo: name resolution failed (getaddrinfo rc 8)"
        );
    }

    #[test]
    fn a_socket_site_renders_the_errno_text() {
        let s = text(
            LogSite::SockConnectFailed,
            vec![
                LogArg::Endpoint(None),
                LogArg::I64(-4),
                LogArg::I64(111),
                LogArg::ErrnoStr(111),
            ],
        );
        let errno = std::io::Error::from_raw_os_error(111).to_string();
        assert_eq!(
            s,
            format!("Socket (none) zk retcode=-4, errno=111({errno}): connect() call failed")
        );
    }

    #[test]
    fn a_panic_text_names_the_message() {
        let p: Box<dyn Any + Send> = Box::new("boom");
        let t = PanicText {
            what: "hook",
            payload: &*p,
        };
        assert_eq!(t.to_string(), "hook: boom");
        let p: Box<dyn Any + Send> = Box::new(7_u8);
        let t = PanicText {
            what: "hook",
            payload: &*p,
        };
        assert_eq!(t.to_string(), "hook");
    }

    #[cfg(feature = "tracing")]
    #[test]
    fn tracing_filters_by_level_and_carries_the_fields() {
        use tracing::field::{Field, Visit};
        use tracing::span::{Attributes, Id, Record};
        use tracing::{Event, Metadata, Subscriber};

        #[derive(Default)]
        struct Fields {
            client: Option<String>,
            site: Option<String>,
            message: Option<String>,
        }

        impl Visit for Fields {
            fn record_str(&mut self, field: &Field, value: &str) {
                match field.name() {
                    "client" => self.client = Some(value.to_owned()),
                    "site" => self.site = Some(value.to_owned()),
                    _ => {}
                }
            }
            fn record_debug(&mut self, field: &Field, value: &dyn std::fmt::Debug) {
                if field.name() == "message" {
                    self.message = Some(format!("{value:?}"));
                }
            }
        }

        type Seen = Arc<Mutex<Vec<(String, tracing::Level, Fields)>>>;

        struct Capture {
            seen: Seen,
        }

        impl Subscriber for Capture {
            fn enabled(&self, m: &Metadata<'_>) -> bool {
                *m.level() <= tracing::Level::INFO
            }
            fn max_level_hint(&self) -> Option<tracing::level_filters::LevelFilter> {
                Some(tracing::level_filters::LevelFilter::INFO)
            }
            fn new_span(&self, _: &Attributes<'_>) -> Id {
                Id::from_u64(1)
            }
            fn record(&self, _: &Id, _: &Record<'_>) {}
            fn record_follows_from(&self, _: &Id, _: &Id) {}
            fn event(&self, e: &Event<'_>) {
                let mut f = Fields::default();
                e.record(&mut f);
                self.seen.lock_unpoisoned().push((
                    e.metadata().target().to_owned(),
                    *e.metadata().level(),
                    f,
                ));
            }
            fn enter(&self, _: &Id) {}
            fn exit(&self, _: &Id) {}
        }

        let seen: Seen = Arc::default();
        let sub = Capture {
            seen: Arc::clone(&seen),
        };
        let named = Observer::new(Some("c1".into()), None);
        let anon = Observer::new(None, None);
        let addr: SocketAddr = "127.0.0.1:2181".parse().unwrap();
        let emit_all = || {
            named.log(&LogRecord {
                site: LogSite::InitiatedConnection,
                level: LogLevel::Info,
                args: vec![LogArg::Endpoint(Some(addr))],
            });
            // Debug-level records fall below the active subscriber threshold and are filtered out.
            named.log(&LogRecord {
                site: LogSite::PingResponse,
                level: LogLevel::Debug,
                args: vec![LogArg::I64(1)],
            });
            anon.log(&LogRecord {
                site: LogSite::UnexpectedResponse,
                level: LogLevel::Error,
                args: Vec::new(),
            });

            // Panics originating within metrics callbacks are caught and recorded as error events.
            let hook = Arc::new(Counting {
                panic_on_state: true,
                ..Counting::default()
            });
            let o = observer(&hook);
            o.state_changed(SessionState::Connecting);
            o.state_changed(SessionState::Connecting);
        };
        tracing::subscriber::with_default(sub, || {
            assert!(named.log_enabled(LogLevel::Error));
            assert!(named.log_enabled(LogLevel::Info));
            assert!(!named.log_enabled(LogLevel::Debug));
            emit_all();
            tracing::callsite::rebuild_interest_cache();
            seen.lock_unpoisoned().clear();
            emit_all();
        });
        let seen = seen.lock_unpoisoned();
        assert_eq!(
            seen.len(),
            3,
            "{:?}",
            seen.iter().map(|s| &s.0).collect::<Vec<_>>()
        );
        let (target, level, f) = &seen[0];
        assert_eq!(target, "apache_zookeeper::session");
        assert_eq!(*level, tracing::Level::INFO);
        assert_eq!(f.client.as_deref(), Some("c1"));
        assert_eq!(f.site.as_deref(), Some("InitiatedConnection"));
        assert_eq!(
            f.message.as_deref(),
            Some("Initiated connection to server 127.0.0.1:2181")
        );
        let (target, level, f) = &seen[1];
        assert_eq!(target, "apache_zookeeper::proto");
        assert_eq!(*level, tracing::Level::ERROR);
        assert_eq!(f.client, None);
        let (target, level, f) = &seen[2];
        assert_eq!(target, "apache_zookeeper");
        assert_eq!(*level, tracing::Level::ERROR);
        assert_eq!(f.client.as_deref(), Some("c1"));
        assert_eq!(
            f.message.as_deref(),
            Some("the metrics hook panicked and is disabled for this client: hook bug")
        );
    }

    #[cfg(all(feature = "log", not(feature = "tracing")))]
    #[test]
    fn log_filters_by_level_and_names_the_client() {
        struct Capture;
        static SEEN: Mutex<Vec<(String, log::Level, String)>> = Mutex::new(Vec::new());
        impl log::Log for Capture {
            fn enabled(&self, m: &log::Metadata<'_>) -> bool {
                m.level() <= log::Level::Info
            }
            fn log(&self, r: &log::Record<'_>) {
                if self.enabled(r.metadata()) {
                    SEEN.lock_unpoisoned().push((
                        r.target().to_owned(),
                        r.level(),
                        r.args().to_string(),
                    ));
                }
            }
            fn flush(&self) {}
        }
        static LOGGER: Capture = Capture;
        log::set_logger(&LOGGER).unwrap();
        log::set_max_level(log::LevelFilter::Info);

        let o = Observer::new(Some("log-test".into()), None);
        assert!(o.log_enabled(LogLevel::Warn));
        assert!(!o.log_enabled(LogLevel::Debug));
        o.log(&LogRecord {
            site: LogSite::SendGetData,
            level: LogLevel::Debug,
            args: Vec::new(),
        });
        o.log(&LogRecord {
            site: LogSite::DelayingConnection,
            level: LogLevel::Warn,
            args: vec![LogArg::Str(JString::from("a:1"))],
        });
        let seen: Vec<_> = SEEN
            .lock_unpoisoned()
            .iter()
            .filter(|s| s.2.starts_with("[log-test]"))
            .cloned()
            .collect();
        assert_eq!(seen.len(), 1);
        assert_eq!(seen[0].0, "apache_zookeeper::session");
        assert_eq!(seen[0].1, log::Level::Warn);
        assert_eq!(
            seen[0].2,
            "[log-test] Delaying connection after exhaustively trying all servers [a:1] \
             (site DelayingConnection)"
        );
    }

    /// Verifies that log filtering reports disabled status when no logging backend is compiled in.
    #[cfg(not(any(feature = "tracing", feature = "log")))]
    #[test]
    fn no_backend_enables_nothing() {
        let o = Observer::new(Some("c1".into()), None);
        assert!(!o.log_enabled(LogLevel::Error));
        o.log(&LogRecord {
            site: LogSite::Closing,
            level: LogLevel::Info,
            args: Vec::new(),
        });
    }
}
