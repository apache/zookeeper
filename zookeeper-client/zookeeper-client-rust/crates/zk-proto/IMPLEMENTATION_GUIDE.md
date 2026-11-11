<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
//-->

# zk-proto Implementation Guide

This guide describes the inner structure of the `zk-proto` core for contributors. It divides the core into six packages: `requests`, `recv`, `watches`, `auth`, `hosts` and `session`. In this guide, a package is a group of modules with one job. It is not a Cargo package.

## 1. Rules for every package

### 1.1 Ownership

- A change to a shared module affects every package, so keep it small and explain it in the review.
- The doc comment on each method is its contract.
- A public signature is a contract with the drivers. A `pub(crate)` signature that another module calls is a contract between packages. When you change one, change every caller in the same commit. Private helper functions and private fields are free to change.
- Each sub-state struct belongs to one module. Only that module writes its fields. Other modules read them, or call the methods of the owner. The `Core` fields have fixed writers.

### 1.2 Code rules

- MSRV is Rust 1.85, edition 2024. Let-chains are not available. `gen` is sa reserved word, so the code uses `generation`.
- The workspace lints deny `unwrap_used`, `expect_used`, `panic`, `indexing_slicing`, `arithmetic_side_effects`, `dbg_macro`, `print_stdout`, `print_stderr`, `large_stack_arrays` and `large_stack_frames`. `unsafe_code` is forbidden. Use `get`, `checked_*`, `wrapping_*` and `saturating_*`. Do not use `as` casts. Use `try_from` and `from`.
- Production code never panics. Test modules may use a module-level `#[allow(clippy::unwrap_used, clippy::indexing_slicing, ...)]`.
- The only dependencies are `zk-jute` and `zeroize`. The core does no IO, so it needs no other crate. A new dependency needs a separate review.
- Every new file starts with the ASF header of the other files, then a blank line.

### 1.3 Checks for each package

Run these checks before you send a change:

```sh
cargo build -p zk-proto
cargo clippy -p zk-proto --all-targets -- -D warnings
cargo +1.85.0 check -p zk-proto --all-targets
cargo test -p zk-proto
cargo fmt -p zk-proto --check
```

A unit test tests one package. Build a `Connection` in the test module, or test the sub-state struct alone. The `new_connection_is_idle` test in `connection.rs` shows a minimal `Connection`. Tests of the public API go in `tests/`.

## 2. Shared structure

### 2.1 Shared modules

These modules hold the types and the plumbing of all packages.

| Module | Content |
|---|---|
| `time.rs` | `Millis` and its saturating arithmetic. |
| `secret.rs` | `SecretBytes` (zeroized on drop). |
| `hooks.rs` | `Tokens`, `Rng`, `LogFilter`, `DefaultWatcher` and test hooks. |
| `config.rs` | `Config`, `ClientId`, `HostSpec`, `HostToken`, `Endpoint`, `DriverMode`. |
| `compat.rs` | `Compat` with `LEGACY_C` and `NATIVE`. |
| `state.rs` | `State`, internal `Phase`, `SaslNegotiation`, `Cause`. |
| `errno.rs` | The errno values of the hint. |
| `log.rs` | `LogSite`, `LogArg`, `LogRecord` and the crate `Logger`. |
| `action.rs` | `Action`, `SaslCall`, the input result types. |
| `request.rs` | `Op`, `MultiOp`, `MultiOpRef`, `Request`, `Submitted`, `SubmitError`, `check_ttl`. |
| `output.rs` | `Output`, `OpResult`, `Reply`, `Stopped`, `Work`, `Outputs`. |
| `responses.rs` | `ReplyData` and the spare slot `Spares` of the DATA view. |
| `connection.rs` | `Connection`, `Core`, `Leftovers`, `new`, `resume`, accessors. |
| `native.rs` | The Java path check and connect string parser of the native client. |
| `legacy/` | Safe ports of the C algorithms with observable results: the hash table, the watcher tables, the address list, the server list, the path checks, the chroot and the connect string. |

### 2.2 The connection

`Connection<T: Tokens>` holds the shared fields and one sub-state struct for each module:

| Field | Type | Owner module | Package |
|---|---|---|---|
| `cfg`, `compat`, `mode`, `rng`, `default_watcher`, `log` | config and hooks | `connection.rs` (read-only after `new`) | none |
| `out` | `Outputs<T>` | `output.rs` | none |
| `core` | `Core` | see section 2.3 | none |
| `requests` | `Requests<T>` | `requests.rs` | requests |
| `recv` | `Recv` | `recv.rs` | recv |
| `watches` | `Watches<T::Watcher>` | `watches.rs` | watches |
| `auth` | `AuthState<T>` | `auth.rs` | auth |
| `sasl` | `SaslState` | `sasl.rs` | auth |
| `hosts` | `Hosts` | `hosts.rs` | hosts |
| `timers` | `Timers` | `timers.rs` | hosts |
| `interest` | `InterestState` | `interest.rs` | hosts |
| `session` | `SessionState` | `session.rs` | session |
| `teardown` | `TeardownState<T>` | `teardown.rs` | session |
| `close` | `CloseState` | `close.rs` | session |

`submit.rs` (requests) owns no struct. It writes `requests` through the `Requests` methods.

### 2.3 The `Core` fields and their writers

| Field | Writers | How |
|---|---|---|
| `state` | session, sasl, auth, teardown, close | `set_state` |
| `phase` | session, sasl, teardown | direct write of `core.phase` |
| `client_id`, `recv_timeout` | session | direct write |
| `last_zxid` | recv | direct write in `dispatch_reply` |
| `seen_rw_before` | session | direct write |
| `errno_hint` | any module | `set_errno_hint` |
| `has_transport`, `epoch` | session (open), teardown and close (lose) | `open_transport`, `lose_transport` |

Every module may read every `Core` field.

The transport epoch changes on every open and every loss. A job that stops at a barrier records the epoch. When it continues and the epoch changed, a teardown or forced close ran during the barrier, and the job stops.

### 2.4 Outputs and barriers

The outputs queue is in `output.rs`. The rules:

- Push an output with `self.out.push(..)`. Push a log with `self.emit_log(site, || args)`. The closure runs only when the filter enables the site.
- A barrier output (`Output::is_barrier`) stops the stage that pushed it. Push the barrier, then `self.out.park(Work::..(job))`, then return at once. Push nothing more in that call.
- `fail_pending_run` does not park. It returns true when it stops after a Direct entry, and the caller parks.
- The driver pops the barrier, takes the `Stopped` handle, delivers the barrier, and calls `Connection::resume`. `resume` calls the `continue_*` method of the job.
- A `Route::Direct` completion is a barrier with no remaining work. Park `Work::Hold`. In a run of failed entries, park after the last Direct entry of the run (see `fail_pending_run`).
- While a stage is suspended (`self.out.is_suspended()`), `handle_read` and `begin_interest` return `ZINVALIDSTATE`, `poll_action` returns `None`, and `run_pending_teardown` returns false.

| `Work` variant | Pushed by | Continued by |
|---|---|---|
| `Hold` | recv (Direct completion), the callers of `fail_pending_run` when it returns true | nothing |
| `Teardown(TeardownJob)` | teardown | `continue_teardown` |
| `Close(CloseJob)` | close | `continue_close` |
| `AuthReply(AuthReplyJob)` | auth | `continue_auth_reply` |
| `Finalize(FinalizeJob)` | session | `continue_finalize` |

### 2.5 Internal control flow

`recv::Flow` is the result of every frame handler:

- `Continue`: the read loop takes the next frame.
- `Stop`: a barrier parked a job, or SASL waits for mechanism work. The
  read loop stops and returns the rc of the frames so far.
- `Done(rc)`: the read loop stops with `rc`. A teardown inside a handler
  returns `Done(rc)` with the teardown rc.

## 3. Packages

### requests: requests.rs and submit.rs

Sources: spec "The state machine and driver interaction" and "Requests, transaction identifiers, and replies", and the doc comments of `requests.rs` and `submit.rs`.

Owns: `Requests<T>`, `OutFrame`, `FrameBytes`, `FrameKind`, `Pending<T>`, `Shape`, `WatchKind`, `WatchReg`, `Unwatch`, and `submit.rs` types (`Prepared`, `Checked`, `Rejected`).

Also in the package: `Requests::{new, next_xid, push_tail, push_priority, push_pending, has_unsent, connect_frame_unwritten, clear_pins, take_all}`, `OutFrame::{plain, secret, is_untouched, is_complete}`, `Shape::legacy_kind`, `pending_len`.

Main functions:

| Function | File |
|---|---|
| `pub fn poll_transmit(&mut self, out: &mut Vec<u8>, max: usize) -> usize` | requests.rs |
| `pub fn handle_transmitted(&mut self, n: usize, now: Millis)` | requests.rs |
| `fn may_send(&self, kind: FrameKind) -> bool` | requests.rs |
| `fn fail_pending_run(&mut self, entries: &mut VecDeque<Pending<T>>, rc: i32) -> bool` | requests.rs |
| `fn request_frame(xid: i32, op_type: i32, body: &[u8]) -> Vec<u8>` | requests.rs |
| `pub fn submit(&mut self, req: Request<T>, now: Millis) -> Result<Submitted<T>, SubmitError<T>>` | submit.rs |
| `pub fn submit_with_data(&mut self, req: Request<T>, data: Option<&[u8]>, now: Millis) -> Result<Submitted<T>, SubmitError<T>>` | submit.rs |
| `pub fn submit_multi_ref(&mut self, req: Request<T>, ops: &[MultiOpRef<'_>], now: Millis) -> Result<Submitted<T>, SubmitError<T>>` | submit.rs |
| `fn check_op(&mut self, op, defect, input: Input<'_>) -> Result<Checked<T::Watcher>, Rejected>` | submit.rs |
| `fn server_path(&self, path: &PathIn, mode: i32) -> Result<Vec<u8>, i32>` | submit.rs |
| `fn encode_multi<I>(&mut self, ops: I, read: bool) -> Result<(Vec<u8>, Vec<i32>), Rejected>`, where the items of `I` are owned `MultiOp`s or borrowed `&MultiOpRef`s (the private `SubOp` trait) | submit.rs |

Calls made:

- `self.timers.on_send(now)` in `handle_transmitted` (hosts).
- `self.on_connect_sent()` when the `Connect` frame completes (session).
- `self.check_remove_watches(..)` for the remove-watches row (watches).
- `self.teardown_completion(pending, rc)` in `fail_pending_run` (recv).
- `check_ttl`, `legacy::path`, `legacy::chroot`, `zk_jute` encoders.

Calls received:

- `poll_transmit`, `handle_transmitted`, `submit`, `pending_len`: driver.
- `may_send`: `poll_transmit`, `write_interest` (hosts).
- `fail_pending_run`: teardown and close (session).
- `request_frame`: submit, auth, timers (hosts), watches, close (session). Every module that queues a request frame uses it.
- `Requests::push_priority`, `push_tail`: auth, sasl, watches, session, timers, close.

Invariants:

- `tx` order: priority frames go before the first untouched frame. A frame with written bytes or a pin stays in place. An unsent `Connect` frame stays ahead of every priority insert.
- `fifo` order matches the wire order of request frames.
- A failed `submit` draws no xid and changes no queue.
- `unacked` is the count of copied bytes that `handle_transmitted` did not acknowledge yet. A transport loss clears it (`clear_pins`).
- Secret frames (`FrameBytes::Secret`) never leave as a plain `Vec` outside `poll_transmit`.
- `secret_end` is the end of the secret bytes in the driver's copy buffer. A secret copy sets it, and a plain copy leaves it. Each `poll_transmit` first erases the old copies past the buffer length up to `secret_end`, also with no transport. Other frames cause no erase.
- When `out` holds bytes on entry or after a secret copy, a growth of `out` erases the old buffer before it is freed.

### recv: recv.rs

Sources: the doc comments of `recv.rs` and the legacy C read loop.

Owns: `Recv` (`decoder`, `stalled`), `Flow`.

Also in the package: `Recv::{new, reset}`, `read_hint`.

Main functions:

| Function |
|---|
| `pub fn handle_read(&mut self, bytes: &[u8], now: Millis) -> ReadOutcome` |
| `pub fn body_room(&mut self, max: usize) -> &mut [u8]` |
| `pub fn handle_body_read(&mut self, n: usize, now: Millis) -> ReadOutcome` |
| `fn take_pushed(&mut self, pushed: Pushed, now: Millis) -> Took` |
| `fn dispatch_body(&mut self, body: &mut Vec<u8>, now: Millis) -> Flow` |
| `fn dispatch_reply(&mut self, hdr: ReplyHeader, body: &mut Vec<u8>, at: usize, now: Millis) -> Flow` (the record starts at `at`; a large DATA value takes `body`) |
| `fn decode_reply(&self, shape: &Shape, rest: &[u8], logs: &mut Vec<LogRecord>) -> Result<(Reply, i32), i32>` (test only; `dispatch_reply` uses `recv_reply_result`) |
| `fn strip_path(&self, server: &[u8], logs: &mut Vec<LogRecord>) -> Option<JString>` |
| `fn completion_logs(&self, pending: &Pending<T>, failed: bool, rc: i32, reply: Option<&Reply>) -> Vec<LogRecord>` |
| `fn teardown_completion(&self, pending: Pending<T>, rc: i32) -> Output<T>` |

Calls made:

- `self.timers.on_recv(now)` (hosts).
- `self.on_connect_response` (session), `self.on_sasl_reply` (auth),
  `self.on_watcher_event` (watches), `self.on_auth_reply` (auth),
  `self.on_close_reply` (session).
- `self.arm_watch`, `self.apply_unwatch` (watches).
- `self.teardown(cause, errno, now)` (session).
- `self.sasl_awaiting_mechanism()` (auth).

Calls received:

- `handle_read`, `read_hint`, `body_room`, `handle_body_read`: driver.
- `strip_path`: watches.
- `teardown_completion`: requests.
- `Recv::reset`: teardown T1 (session).

Invariants:

- `core.last_zxid` changes only for `zxid > 0`, also for error replies.
- `arm_watch` and `apply_unwatch` run before the completion output.
- `consumed` counts only the bytes the decoder took. The rest stay with the caller.
- A decode failure puts the FIFO head back before the teardown.

### watches: watches.rs

Sources: the doc comments of `watches.rs`, `legacy::watchers` and `legacy::hashtable`.

Owns: `Watches<W>`, `Store<W>`, `OrderedWatches<W>`.

Also in the package: `OrderedWatches::new`, `Watches::new`.

Main functions:

| Function |
|---|
| `pub fn has_watcher(&self, server_path: &[u8], wtype: i32, w: Option<&T::Watcher>) -> bool` |
| `pub fn detach_watcher(&mut self, w: &T::Watcher) -> Option<RemoveHint>` |
| `fn arm_watch(&mut self, reg: WatchReg<T::Watcher>, rc: i32)` |
| `fn apply_unwatch(&mut self, u: Unwatch<T::Watcher>)` |
| `fn check_remove_watches(&mut self, path: &[u8], wtype: i32, w: Option<&T::Watcher>, local: bool) -> Result<(), i32>` |
| `fn on_watcher_event(&mut self, zxid: i64, body: &[u8], now: Millis) -> Flow` |
| `fn session_watchers(&mut self) -> Vec<T::Watcher>` |
| `fn queue_set_watches(&mut self)` |
| `fn clear_watches(&mut self, reason: TerminateReason)` |

Calls made:

- `self.strip_path` (recv).
- `self.requests.push_priority`, `Self::request_frame` (requests).
- `self.teardown` on a decode failure (session).
- `self.default_watcher` hook, `legacy::watchers`, `legacy::hashtable`.

Calls received:

- `has_watcher`, `detach_watcher`: driver.
- `arm_watch`, `apply_unwatch`: recv.
- `check_remove_watches`: submit (requests).
- `on_watcher_event`: recv.
- `session_watchers`: `emit_session` (session).
- `queue_set_watches`: `start_finalize` (session).
- `clear_watches`: session expiry (session), close and abandon (session).

Invariants:

- `LegacyHashtable` keeps the C iteration order (`legacy::hashtable`).
  `Ordered` keeps registration order per path.
- A watcher is not armed twice on one path and type.
- One-shot watchers leave the table when the event collects them.
- SetWatches paths keep the chroot.

### auth: auth.rs and sasl.rs

Sources: the doc comments of `auth.rs` and `sasl.rs`.

Owns: `AuthState<T>`, `AuthEntry`, `AuthReplyJob`, `AuthReplyStep`, `SaslState`, and the `SaslNegotiation` value inside `Phase::Sasl`.

Also in the package: `AuthState::{new, take_tokens, first_scheme}`, `SaslState::{new, advance_gen, is_live}`, `sasl_awaiting_mechanism`.

Main functions:

| Function | File |
|---|---|
| `pub fn add_auth(&mut self, scheme, cred, done, now) -> Result<(), AddAuthError<T>>` | auth.rs |
| `fn on_auth_reply(&mut self, err: i32, now: Millis) -> Flow` | auth.rs |
| `fn continue_auth_reply(&mut self, job: AuthReplyJob, now: Millis)` | auth.rs |
| `fn queue_auth_resend(&mut self)` | auth.rs |
| `pub fn handle_sasl_started(..) -> i32` | sasl.rs |
| `pub fn handle_sasl_stepped(..) -> i32` | sasl.rs |
| `pub fn handle_sasl_addr_failed(..) -> i32` | sasl.rs |
| `pub fn mark_sasl_failed(&mut self)` | sasl.rs |
| `fn begin_sasl(&mut self, now: Millis)` | sasl.rs |
| `fn on_sasl_reply(&mut self, body: &[u8], now: Millis) -> Flow` | sasl.rs |
| `fn sasl_fail(&mut self, rc: i32, now: Millis) -> i32` | sasl.rs |
| `fn poll_sasl_action(&mut self) -> Option<Action>` | sasl.rs |
| `fn sasl_on_teardown(&mut self)` | sasl.rs |
| `fn sasl_on_destroy(&mut self)` | sasl.rs |

Calls made:

- `self.requests.push_priority`, `push_tail`, `Self::request_frame` (requests). Auth and SASL frames are `FrameBytes::Secret`.
- `self.teardown(Cause::AuthFailed | Cause::SaslTeardown, ..)` (session).
- `self.start_finalize(now)` from sasl (session).
- `self.set_state`, `self.out.park(Work::AuthReply(..))`.

Calls received:

- `add_auth`, `handle_sasl_*`, `mark_sasl_failed`: driver.
- `on_auth_reply`, `on_sasl_reply`: recv.
- `continue_auth_reply`: `resume`.
- `queue_auth_resend`: `continue_finalize` (session).
- `begin_sasl`: `on_connect_response` (session).
- `poll_sasl_action`: `poll_action` (hosts).
- `sasl_on_teardown`: teardown T1 (session). `sasl_on_destroy`: destroy (session).
- `AuthState::take_tokens`: teardown, close, `into_leftovers`.

Invariants:

- A token of `add_auth` stays in the core if and only if the call returns `Ok`.
- One AUTH reply completes only the token of the packet that it answers, in both profiles (`PerRequest`). The reply takes the oldest index of `in_flight`. `poll_transmit` records an index when it copies the first byte of an auth frame, so the queue holds the wire order.
- `in_flight` belongs to one transport epoch. A transport change makes the old indexes stale, and the next access clears them.
- `auth_lockpoints` (LEGACY_C) adds the lock points. `auth_insert` and `auth_reject_closed` keep the legacy insert position and state checks.
- At most one `Start` or `Step` call is outstanding. A `Dispose` never waits.
- A result with a stale `generation` changes nothing.
- `AuthReplyJob.epoch` must be live when the job continues.

### hosts: hosts.rs, timers.rs and interest.rs

Sources: the doc comments of `hosts.rs`, `timers.rs` and `interest.rs`.

Owns: `Hosts`, `ConnectDecision`, `Timers`, `InterestState`, `IterStep`.

Also in the package: `Hosts::new`, `current_server`, `Timers::{new, on_recv, on_send, reset_for_connect}`, `InterestState::new`, `queue_close_transport`.

Main functions:

| Function | File |
|---|---|
| `pub fn handle_host_resolved(&mut self, generation: u64, r: HostResolution, now: Millis)` | hosts.rs |
| `pub fn handle_resolved(&mut self, generation: u64, shuffle: bool, now: Millis) -> i32` | hosts.rs |
| `pub fn set_servers(&mut self, hosts: Vec<HostSpec>, now: Millis) -> u64` | hosts.rs |
| `pub fn set_resolve_delay(&mut self, ms: i32) -> i32` | hosts.rs |
| `pub fn cycle_next_server(&mut self)` | hosts.rs |
| `pub fn handle_rw_probe(&mut self, ep: Endpoint, outcome: ProbeOutcome, now: Millis)` | hosts.rs |
| `fn resolve_action(&mut self, now: Millis) -> Option<Action>` | hosts.rs |
| `fn connect_decision(&mut self, now: Millis) -> ConnectDecision` | hosts.rs |
| `fn hosts_on_handle_error(&mut self, now: Millis)` | hosts.rs |
| `fn hosts_on_session_lost(&mut self, now: Millis)` | hosts.rs |
| `fn probe_endpoint(&self) -> Option<Endpoint>` | hosts.rs |
| `fn run_timers(&mut self, now: Millis) -> Option<Action>` | timers.rs |
| `fn transport_timeout_ms(&self, now: Millis) -> u32` | timers.rs |
| `fn check_deadline_warn(&mut self, now: Millis)` | timers.rs |
| `fn queue_ping(&mut self, now: Millis)` | timers.rs |
| `pub fn begin_interest(&mut self, now: Millis) -> Result<(), i32>` | interest.rs |
| `pub fn poll_action(&mut self, now: Millis) -> Option<Action>` | interest.rs |
| `pub fn finish_interest(&mut self, now: Millis) -> InterestOutcome` | interest.rs |
| `pub fn interest_queued_ping(&self) -> bool` | interest.rs |
| `fn write_interest(&self) -> bool` | interest.rs |

Calls made:

- `self.teardown(..)` for `SocketCreate`, `RecvTimeout`, `TlsHandshakeTimeout`, `RwServerFound` (session).
- `self.forced_close_trigger(now)` from `handle_resolved`, in its commit step `hosts_commit` (session).
- `self.poll_sasl_action()` (auth).
- `self.requests.push_tail`, `Self::request_frame`, `may_send`, `has_unsent`, `connect_frame_unwritten` (requests).
- `self.rng` (`random`, `drand48`, `on_resolution`), `legacy::servers`.

Calls received:

- The `pub` functions: driver.
- `hosts_on_handle_error`, `hosts_on_session_lost`: teardown T5 (session).
- `queue_close_transport`: teardown, close, sasl.
- `Timers::on_recv`: recv. `Timers::on_send`: requests. `reset_for_connect`: session.

Invariants:

- `poll_action` yields actions in the order of the C interest iteration.
- A resolution result with a stale `generation` changes nothing.
- The cursor advances once per failure (`CursorPolicy::SingleAdvance`).
- `Millis` arithmetic saturates. No timer value overflows.
- The RO probe interval stays in `RW_PROBE_MIN_MS..=RW_PROBE_MAX_MS`.

### session: session.rs, teardown.rs and close.rs

Sources: spec "The state machine and driver interaction", "Session establishment and states", "Connection teardown", "Forced closure" and "Session closure", and the doc comments of `session.rs`, `teardown.rs` and `close.rs`.

Owns: `SessionState`, `FinalizeJob`, `FinalizeStep`, `TeardownState<T>`, `TeardownJob<T>`, `TeardownStage`, `ForcedSnapshot<T>`, `CloseState`, `CloseJob<T>`, `CloseKind`, `CloseStage`.

Also in the package: `on_connect_sent`, `TeardownState::new`, `TeardownJob::into_tokens`, `ForcedSnapshot::into_tokens`, `CloseJob::into_tokens`, `handle_forced_close`, `last_teardown_cause`, `is_closing`.

Main functions:

| Function | File |
|---|---|
| `pub fn handle_connect_started(&mut self, kind: ConnectStart, now: Millis)` | session.rs |
| `pub fn handle_tcp_connected(&mut self, now: Millis)` | session.rs |
| `pub fn handle_transport_ready(&mut self, now: Millis)` | session.rs |
| `fn on_connect_response(&mut self, body: &[u8], now: Millis) -> Flow` | session.rs |
| `fn start_finalize(&mut self, now: Millis) -> Flow` | session.rs |
| `fn continue_finalize(&mut self, job: FinalizeJob, now: Millis)` | session.rs |
| `fn emit_session(&mut self, state: State, extra_logs: Vec<LogRecord>) -> bool` | session.rs |
| `pub fn handle_transport_failed(&mut self, f: TransportFailure, now: Millis) -> i32` | teardown.rs |
| `pub fn run_pending_teardown(&mut self, now: Millis) -> bool` | teardown.rs |
| `fn teardown(&mut self, cause: Cause, errno: i32, now: Millis) -> i32` | teardown.rs |
| `fn continue_teardown(&mut self, job: TeardownJob<T>, now: Millis)` | teardown.rs |
| `fn forced_close_trigger(&mut self, now: Millis)` | teardown.rs |
| `pub fn begin_close(&mut self, now: Millis)` | close.rs |
| `pub fn queue_close_op(&mut self) -> bool` | close.rs |
| `pub fn close_step(&mut self, now: Millis) -> CloseProgress` | close.rs |
| `pub fn abandon(&mut self, now: Millis)` | close.rs |
| `pub fn destroy(&mut self, now: Millis)` | close.rs |
| `fn continue_close(&mut self, job: CloseJob<T>, now: Millis)` | close.rs |
| `fn on_close_reply(&mut self, now: Millis) -> Flow` | close.rs |

Calls made:

- `self.open_transport()`, `self.lose_transport()`, `self.set_state`.
- `self.timers.reset_for_connect` (hosts), `self.hosts_on_handle_error` (hosts), `self.queue_close_transport` (hosts).
- `self.requests.push_priority`, `take_all`, `clear_pins`, `fail_pending_run`, `Self::request_frame` (requests).
- `self.recv.reset()` (recv).
- `self.queue_set_watches`, `session_watchers`, `clear_watches` (watches).
- `self.begin_sasl`, `sasl_on_teardown`, `sasl_on_destroy`, `queue_auth_resend`, `self.auth.take_tokens` (auth).
- `self.out.park`, `take_all_work`, `mark_destroyed`.

Calls received:

- The `pub` functions: driver.
- `teardown`: recv, watches, auth, sasl, hosts, timers.
- `forced_close_trigger`: hosts (`handle_resolved` through `hosts_commit`), `handle_forced_close`.
- `on_connect_response`, `on_close_reply`: recv.
- `start_finalize`: sasl (auth).
- `continue_*`: `resume`.
- `emit_session`: teardown, finalize, and any module that reports a session state.
- `on_connect_sent`: requests.

Invariants:

- A teardown changes the transport epoch in T1. A stopped job with an old epoch stops when it continues.
- Teardown stages run in order T0 to T6. A barrier parks the job with its next stage.
- A forced close takes a snapshot at the trigger. The remaining stages run in `run_pending_teardown`. The visible state is 999 between them.
- After `destroy`, `resume` does nothing, and every token left in the core fails with `ZCLOSING` or goes to `into_leftovers`.
- `close.closing` never goes back to false.

## 4. Cross-package order

The packages depend on the signatures of the other packages. Unit tests use only the functions of their own package. The tests in `tests/` and the scenario and property tests of `zk-testkit` drive a whole `Connection`, so they run every package. After a change to one package, run both test sets.

## 5. Review checklist

1. Fix every new warning. Remove code that nothing uses.
2. The checks of section 1.3 pass.
3. No `todo!` is left: `rg 'todo[!]' crates/zk-proto/src` prints nothing.
4. A change of an observable behavior has a test in `tests/` or in `zk-testkit`, and it matches the legacy C sources.
