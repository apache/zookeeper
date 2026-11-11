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

//! Asynchronous event and response dispatch router implementing [`zk_driver::RouteSink`].
//!
//! The router acts as the central demultiplexer between the low-level I/O driver loop and the
//! client's asynchronous response mechanisms, watcher distribution trees, session state cells,
//! and telemetry observers.
//!
//! Invariants and concurrency architecture: The driver invokes `route` from the I/O event loop
//! in strict wire/outbox order with protocol state machine locks surrendered. Router methods
//! guarantee nonblocking execution. Response slots are populated immediately, watcher delivery
//! actions are planned under a short lived `sinks` leaf lock and dispatched after lock release,
//! and payload decoding is deferred to caller task futures.
//!
//! The boolean return value of `route` informs the driver's read gate backpressure accounting,
//! indicating whether a delivered item occupies pending capacity awaiting application consumption.

use std::sync::{Arc, Mutex, OnceLock, Weak};

use zk_driver::{Item, RouteSink};
use zk_proto::{LogRecord, State};

use crate::error::{LastError, LastErrorUpdate, WatchError, last_error_for};
use crate::follow::{self, ConfigFollow};
use crate::future::ReadyWaiters;
use crate::observe::{Observer, OpKind};
use crate::shared::Shared;
use crate::sync::{LockExt, enter_io_side};
use crate::tokens::{NativeCompletion, NativeTokens, RawResult, WatchId};
use crate::watch::{
    Applied, Delivery, SessionState, StateCell, WatchSinks, event_from_proto, watch_error_for,
};

/// Callback closure extracting transport-layer TLS errors during connection establishment.
///
/// Abstracted behind a dynamic function pointer to decouple routing logic from compile-time TLS
/// feature flags.
pub(crate) type TlsErrorSource = Arc<dyn Fn() -> Option<LastError> + Send + Sync>;

/// Core event dispatch router for a native client instance.
pub(crate) struct Router {
    /// Synchronized registry of active watcher delivery sinks protected by a leaf lock.
    pub(crate) sinks: Mutex<WatchSinks>,
    /// Thread-safe cell publishing the client's current session connectivity state.
    pub(crate) state: Arc<StateCell>,
    /// Telemetry hook coordinating structured log output and operational metrics.
    pub(crate) observe: Arc<Observer>,
    /// Synchronization manager waking tasks registered with `Client::ready`.
    pub(crate) ready: ReadyWaiters,
    /// Weak backreference to shared client state, preventing cyclic retention by background
    /// driver threads.
    shared: OnceLock<Weak<Shared>>,
    /// Optional hook querying transport-level TLS diagnostic errors.
    tls_error: Option<TlsErrorSource>,
    /// Dynamic ensemble configuration tracking task for `/zookeeper/config`.
    pub(crate) follow: ConfigFollow,
    /// Numeric client identifier identifying the associated I/O thread context.
    client: u64,
}

impl std::fmt::Debug for Router {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Router")
            .field("client", &self.client)
            .finish_non_exhaustive()
    }
}

impl Router {
    /// Instantiates a new router bound to the given client instance.
    pub(crate) fn new(
        state: Arc<StateCell>,
        observe: Arc<Observer>,
        tls_error: Option<TlsErrorSource>,
        client: u64,
    ) -> Arc<Self> {
        Arc::new(Self {
            sinks: Mutex::new(WatchSinks::new()),
            state,
            observe,
            ready: ReadyWaiters::new(),
            shared: OnceLock::new(),
            tls_error,
            follow: ConfigFollow::default(),
            client,
        })
    }

    /// Associates the router with the client's shared state once initialization is complete.
    pub(crate) fn bind(&self, shared: &Arc<Shared>) {
        let _ = self.shared.set(Arc::downgrade(shared));
    }

    /// Upgrades the weak reference to the shared client state if still active.
    fn shared(&self) -> Option<Arc<Shared>> {
        self.shared.get().and_then(Weak::upgrade)
    }

    /// Emits structured diagnostic log records forwarded from the driver outbox.
    fn log_all(&self, logs: &[LogRecord]) {
        for rec in logs {
            self.observe.log(rec);
        }
    }

    /// Executes planned watcher notifications outside any mutex to preserve lock ordering.
    ///
    /// Returns `true` if any delivered event requires backpressure permit accounting,
    /// and logs lag events when bounded watcher channels discard notifications.
    fn apply(&self, deliveries: Vec<Delivery>) -> bool {
        let mut counted = false;
        for d in deliveries {
            let Applied { counted: c, lagged } = d.apply();
            counted |= c;
            if let Some(missed) = lagged {
                self.observe.watch_lagged(missed);
            }
        }
        counted
    }

    /// Dispatches a terminal error notification to all registered watcher streams.
    fn terminate_all(&self, err: &WatchError) {
        let deliveries = self.sinks.lock_unpoisoned().terminate_all(err);
        self.apply(deliveries);
    }

    /// Demultiplexes an inbound protocol response to its target completion token.
    ///
    /// When the awaiting future has been dropped prior to completion, cleans up any associated
    /// watcher registrations to allow `zk-proto` to transmit `RemoveWatches` frames if necessary.
    fn on_result(&self, token: NativeCompletion, result: RawResult) -> bool {
        let t = match token {
            NativeCompletion::Discard => return false,
            NativeCompletion::Follow => {
                if let Some(shared) = self.shared() {
                    follow::on_result(&shared, result);
                }
                self.ready.wake_all();
                return false;
            }
            NativeCompletion::Reply(t) => t,
        };
        let kind = t.kind;
        let started = t.started;
        let watch = t.watch;
        let rc = result.rc;
        let counted = match t.complete(result, true) {
            Ok(counted) => counted,
            Err(_dropped) => {
                if let Some(id) = watch {
                    self.detach_orphan(id);
                }
                false
            }
        };
        self.after_request(kind, rc, started);
        counted
    }

    /// Deregisters an orphaned watcher registration whose originating response listener was
    /// abandoned.
    fn detach_orphan(&self, id: WatchId) {
        if let Some(shared) = self.shared() {
            shared.drop_watch_sink(id);
        }
    }

    /// Records completion telemetry and wakes futures awaiting connection readiness or completion.
    fn after_request(&self, kind: OpKind, rc: i32, started: std::time::Instant) {
        self.observe.request_completed(kind, rc, started.elapsed());
        if self.observe.has_metrics() {
            if let Some(n) = self.shared().and_then(|s| s.pending_len()) {
                self.observe.pending_requests(n);
            }
        }
        self.ready.wake_all();
    }

    /// Processes session lifecycle transitions dispatched by the driver.
    fn on_session(&self, state: State) {
        let (closing, cause, errno) = self
            .shared()
            .and_then(|s| {
                s.read_conn(|c| {
                    (
                        c.is_closing(),
                        c.last_teardown_cause(),
                        c.last_teardown_errno(),
                    )
                })
            })
            .unwrap_or((true, None, None));
        let update = match (state, cause) {
            (State::Connected | State::ReadOnly, _) | (_, None) => LastErrorUpdate::Keep,
            (_, Some(cause)) => {
                let tls = self.tls_error.as_ref().and_then(|f| f());
                last_error_for(cause, errno, tls)
            }
        };
        let Some(new) = self.state.on_session(state, closing, update) else {
            return;
        };
        self.observe.state_changed(new);
        if let Some(err) = terminal_watch_error(new) {
            self.terminate_all(&err);
            self.ready.wake_all();
        }
    }

    /// Handles clean driver shutdown upon background thread termination.
    ///
    /// Transitions state to `Closed`, aborts active watcher streams with terminal errors, and
    /// wakes any pending `ready` notification waiters. Subsequent calls are idempotent no-ops.
    pub(crate) fn on_exit(&self) {
        if self.state.set_closed() {
            self.observe.state_changed(SessionState::Closed);
        }
        self.terminate_all(&WatchError::Closed);
        self.ready.wake_all();
    }
}

/// Maps a terminal session state transition to its corresponding [`WatchError`].
fn terminal_watch_error(state: SessionState) -> Option<WatchError> {
    match state {
        SessionState::Expired => Some(WatchError::SessionExpired),
        SessionState::AuthFailed => Some(WatchError::AuthFailed),
        SessionState::Closed => Some(WatchError::Closed),
        _ => None,
    }
}

impl RouteSink<NativeTokens> for Router {
    /// Dispatches an outbox item received from the protocol driver event loop.
    ///
    /// Executes under the client I/O thread marker, preventing illegal nested blocking calls
    /// within user callbacks.
    fn route(&self, item: Item<NativeTokens>) -> bool {
        let _io = enter_io_side(self.client);
        match item {
            Item::Log(rec) => {
                self.observe.log(&rec);
                false
            }
            Item::Result {
                token,
                result,
                delivery_logs,
            } => {
                self.log_all(&delivery_logs);
                self.on_result(token, result)
            }
            Item::AuthResult { token, rc } => {
                let started = token.started;
                let counted = token.complete(rc, true).unwrap_or(false);
                self.after_request(OpKind::AddAuth, rc, started);
                counted
            }
            Item::Watch {
                event,
                watchers,
                delivery_logs,
            } => {
                self.log_all(&delivery_logs);
                // Events failing conversion lack valid recipient streams. Retain existing sinks
                // until dropped.
                let Some(ev) = event_from_proto(&event) else {
                    return false;
                };
                let kind = ev.kind;
                if let Some(shared) = self.shared() {
                    follow::on_watch(&shared, &watchers);
                }
                let deliveries = self.sinks.lock_unpoisoned().deliver_event(watchers, &ev);
                let counted = self.apply(deliveries);
                self.observe.watch_event(kind);
                counted
            }
            Item::Session {
                state,
                watchers: _,
                delivery_logs,
            } => {
                self.log_all(&delivery_logs);
                self.on_session(state);
                false
            }
            Item::WatchTerminated { watchers, reason } => {
                if let Some(shared) = self.shared() {
                    follow::on_terminated(&shared, &watchers, reason);
                }
                let err = watch_error_for(reason);
                let deliveries = self.sinks.lock_unpoisoned().terminate(watchers, &err);
                self.apply(deliveries);
                false
            }
            Item::AuthLockpoint | Item::Sasl(_) | Item::Thread { .. } => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn terminal_states_map_to_watch_errors() {
        assert_eq!(
            terminal_watch_error(SessionState::Expired),
            Some(WatchError::SessionExpired)
        );
        assert_eq!(terminal_watch_error(SessionState::Connected), None);
    }

    #[test]
    fn a_discarded_result_does_not_count() {
        let r = Router::new(
            StateCell::new(),
            Arc::new(Observer::new(None, None)),
            None,
            1,
        );
        let res = RawResult {
            rc: 0,
            zxid: 0,
            origin: zk_proto::Origin::Reply,
            reply: None,
        };
        assert!(!r.on_result(NativeCompletion::Discard, res));
    }
}
