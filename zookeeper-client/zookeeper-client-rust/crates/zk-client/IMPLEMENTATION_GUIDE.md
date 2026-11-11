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

# apache-zookeeper (zk-client) implementation guide

This guide describes the inner structure of the `apache-zookeeper` crate (directory `crates/zk-client`) for contributors. It divides the crate into eight packages: `values`, `codec`, `futures`, `watch`, `host-observe`, `client`, `tls` and `sasl`.

The sources of the design are this guide and `design/specification.md`, section "The Native Rust API" (cited as "spec"). The spec section "Drivers and I/O Architecture" gives the driver contract.

## 1. Rules for every package

### 1.1 Ownership

- A change to a shared file affects every package, so keep it small and explain it in the review.
- zk-client uses only the public API of `zk-proto`, `zk-driver` and `zk-jute`. It never re-exports their types. A fix in one of those crates goes into that crate.
- The doc comment of each function is its contract. When the contract and the spec disagree, the spec wins. Fix the code, or record the difference in section 6.
- A `pub(crate)` signature is a contract between packages. When you change it, change every caller in the same commit. Private functions, fields and types are free to change.
- The fields of a struct belong to the file that declares it. Other files use the methods of the owner. The `Router` and `Shared` fields are `pub(crate)` for reads only.

### 1.2 Code rules

- MSRV is Rust 1.85, edition 2024. Let-chains are not available. Check every std API that you use against 1.85.
- The workspace lints deny `unwrap_used`, `expect_used`, `panic`, `indexing_slicing`, `arithmetic_side_effects`, `dbg_macro`, `print_stdout`, `print_stderr`, `large_stack_arrays` and `large_stack_frames`. `missing_docs` and `unreachable_pub` warn, and
  the checks use `-D warnings`. The crate has `#![deny(missing_docs)]` and `#![forbid(unsafe_code)]`. Use `get`, `checked_*`, `wrapping_*` and `saturating_*`. Do not use `as` casts. Use `try_from` and `from`.
- Production code never panics. `ZPath::from_static` gives `None` for a bad literal; a `const` item unwraps it at compile time. Test modules may use a module-level `#[allow(clippy::unwrap_used, ...)]`.
- User code (`Resolve`, `Metrics`, wakers) runs inside `std::panic::catch_unwind` with `AssertUnwindSafe`, and with no zk-client lock held. A waker is taken out under its lock and called after the lock is released. The driver can hold its I/O owner lock. No client method takes that lock.
- Use `crate::sync::LockExt::lock_unpoisoned` for every `Mutex`, and `sync::wait` and `sync::wait_until` for every `Condvar`. A poisoned lock is never an error.
- A blocking wait first checks `sync::blocking_refused(tokio_driver)`. When it is true, the wait returns `Error::InvalidState` or `WatchError::InvalidState` at once. `tokio_driver` is true when the tokio driver runs the client of the wait.
- Allowed dependencies: `zk-driver`, `zk-proto`, `zk-jute`, `bitflags`, `zeroize`, `rustix`, and the optional `tokio` (Unix), `futures-core`, `tracing`, `log`, `rustls`, `rustls-platform-verifier`, `serde`, `md-5`, `getrandom` and `libgssapi`. Dev-dependencies: `zk-fakeserver` (with `tls`), `zk-testkit`, `tokio`, `proptest`. A new dependency or a new feature needs a separate review, because it changes the supply chain of every build.
- Every new file starts with the ASF header of the other files, then a blank line.

### 1.3 Checks for each package

Run these checks before you send a change. Use the three feature sets below. Never use `--all-features`: exactly one TLS provider is allowed (`lib.rs` has the `compile_error!` checks). A change to the `sasl` package also needs a feature set with `sasl`.

```sh
for fs in --features=tokio,tracing,stream \
          --no-default-features \
          --features=tokio,blocking,tls-rustls,tls-ring,log; do
  cargo build  -p apache-zookeeper $fs
  cargo clippy -p apache-zookeeper --all-targets $fs -- -D warnings
  cargo +1.85.0 check -p apache-zookeeper --all-targets $fs
  RUSTDOCFLAGS="-D warnings" cargo doc -p apache-zookeeper --no-deps $fs
  cargo test   -p apache-zookeeper $fs
done
cargo fmt -p apache-zookeeper --check
```

### 1.4 Tests

- Unit tests in the `#[cfg(test)] mod tests` of a source file test private state only. They build the inputs by hand: a `zk_proto::OpResult` (`RawResult`) for a decoder, a `Slot` for a future, a `StateCell` for the state logic. `Weak::new()` stands in for a missing `Shared`.
- Tests of the public API go in `tests/`. The fake-server tests use `tests/common/mod.rs`: `fake()`, `builder(connect_string)`, `WAIT`, `RealServer`, `real_port` and the other shared helpers. A helper that only one test file uses stays in that file. A helper that several test files use goes in `tests/common/mod.rs`.
- The test files have these groups. `api_*.rs` checks the API contracts. `c_*.rs` ports the C client suites. `fault_*.rs` injects faults through the fake server. `real_*.rs` runs against a real server. Most other files belong to one package.
- Real-server tests have `#[ignore = "real-server"]`. They take the port from `common::real_port(offset)`, which reads `ZK_TEST_PORT_BASE`. Parallel runs on one host use different bases. With no base, the test returns early. With `ZK_TEST_REQUIRE_SERVER` set, a missing base fails the test. `RealServer` stops its server on drop.
- The zeroization tests have `#[ignore = "free-scan"]`. The script `tests/free_scan/free_scan.sh` runs them with a scanner of freed heap blocks.
- `tests/smoke.rs` uses only the public value types.

## 2. Shared structure

### 2.1 Shared files

These files hold the infrastructure of all packages.

| File | Content |
|---|---|
| `Cargo.toml` | Dependencies and the features (`tokio`, `tracing`, `stream`, `blocking`, `log`, `tls-rustls`, `tls-ring`, `tls-aws-lc-rs`, `tls-aws-lc-rs-fips`, `tls-platform-verifier`, `serde`, `sasl`, `sasl-gssapi`). |
| `src/lib.rs` | The module tree, the re-exports and the provider `compile_error!` checks. |
| `src/sync.rs` | `LockExt`, `wait`, `wait_until`, the IO-side marker (`enter_io_side`, `on_io_side`, `blocking_refused`). |
| `src/secret.rs` | `SecretString`. |
| `src/types.rs` | `Zxid`, `SessionId`, `SessionPassword`, `Version`, `VersionArg`, `Stat`, `EphemeralKind`, `Ttl`, `CreateMode`. |
| `src/acl.rs` | `Perms`, `Id`, `Acl` and the standard lists. |
| `src/slot.rs` | `Slot<T>` and `Taken<T>`: the result cell and the read-gate count. |
| `src/tokens.rs` | `NativeTokens`, `WatchId`, `NativeCompletion`, `ReplyToken`, `AuthToken`, `RawResult`. |
| `src/rng.rs` | `NativeRng` (SplitMix64). |
| `src/route.rs` | `Router`: the `RouteSink` of the client. |
| `src/driver.rs` | `DriverHandle`, `CloseWait`, `ExitSignal`, `ClientSpawner`, `driver::start`. |
| `src/shared.rs` | `Shared`, `ClientConfig`, `OneshotReg`, `PersistentReg`: the submit path and the watch detach rule. |
| `src/blocking.rs` | `blocking::Client`. Each method calls the async method and `wait()`. |
| `src/follow.rs` | The config follower of `update_servers_from_config` (section 6). |
| `src/serde_impls.rs` | The `serde` rules of `Perms` and `Ttl` (feature `serde`). |
| `tests/common/mod.rs` | `RealServer`, `real_port`, `fake`, `builder`, `WAIT`, and the helpers that several test files share, among them `within`, `connect`, `setup`, `fixture`, `wait_info`, `thread` and `ca_only`. |
| `tests/common/proxy.rs` | `Proxy`: the loopback TCP proxy of the real-server tests, with the cut, hold and mute modes and a delay. The zk-recipes tests include this file with `#[path]`. |
| `tests/smoke.rs` | Value-type smoke tests. |

### 2.2 The layers

```
user code
   |  Client::op(..) -> ResponseFuture        (client: client.rs, builder.rs)
   v
path::checked -> ops::* -> Shared::request    (values, codec; shared.rs)
   |  register_oneshot / register_persistent: sink before submit
   v
DriverHandle::submit (core lock) -> zk_proto::Connection   (driver.rs)
   |
   |  IO side: zk-driver thread or tokio task, core unlocked
   v
Router::route(Item)                            (route.rs)
   |-- Result  -> ReplyToken::complete -> Slot<RawResult>
   |-- Watch   -> WatchSinks::deliver_event -> Delivery::apply   (watch)
   |-- Session -> StateCell::on_session -> Observer::state_changed (watch, host-observe)
   |-- Log     -> Observer::log                                (host-observe)
   v
ResponseFuture::poll / wait (caller task)      (futures)
   -> decode::* or multi::decode_* (caller task)   (codec)
   -> OneshotWatcher / PersistentWatcher made by decode::with_* (watch)
```

`ClientBuilder::build` (client) makes the pieces: the `Observer` (host-observe), the `StateCell` (watch), the `Router`, the `NativeHost` (host-observe), the TLS connector and error probe (tls), the proto `Connection` with `Compat::NATIVE`, and then `driver::start` and `Shared::new`.

### 2.3 Locks

| Lock | Owner | Rule |
|---|---|---|
| core (`zk-driver`) | driver | Taken only inside `DriverHandle` calls. No zk-client lock is held across a `DriverHandle` call. Host pure leaves run under it, so they never call user code. |
| `Router::sinks` | watch (`WatchSinks`) | Leaf. Plan under it, apply after it is released. |
| `Slot` | shared (`slot.rs`) | Leaf. The waker runs after release. |
| `EventQueue` | watch | Leaf. The waker runs after release. |
| `StateCell` | watch | Leaf. The wakers run after release. |
| `ReadyWaiters` | futures | Leaf. |
| `ExitSignal` | shared (`driver.rs`) | Leaf. |
| `TlsInner` cache and last error | tls | Leaf. File IO runs with the lock released. |
| `LastSaslError` | sasl | Leaf. The adapter writes it. The error probe of `build` reads it for `last_error_for`. |

No code holds two zk-client locks at once. User code runs with no zk-client lock held. The driver can hold its I/O owner lock. No client method takes that lock.

### 2.4 The read gate

The driver stops reading when `dispatch_queue_capacity` values wait in receivers. `Router::route` returns true for a value that a live receiver must still take. The receiver calls `Shared::taken` once when it takes or drops a counted value (`Taken::Value { counted: true, .. }` or `Slot::drop_receiver() == true`). Every consumer of a slot or a one-shot sink keeps this rule. Persistent events never count.

### 2.5 Call graph

| Caller | Callee |
|---|---|
| client (`Client::*`) | `path::checked`, `PathArg::to_zpath`, `ops::*`, `decode::*`, `decode::with_oneshot`, `decode::with_persistent`, `multi::decode_multi`, `multi::decode_multi_read`, `MultiWriter::to_ops`, `MultiReader::to_ops`, `Reconfig::wire`, `connect_string::parse_update`, `Shared::{request, request_by, register_oneshot, register_persistent, add_auth, read_conn, with_conn, driver}`, `ResponseFuture::ready`, `CloseFuture::{new, ready}`, `StateCell::terminal_error` |
| client (`ClientBuilder::build`) | `connect_string::parse`, `TlsOptions::{check, connector, error_probe, tls_verify}`, `Observer::new`, `StateCell::new`, `Router::new`, `NativeHost::new`, `zk_driver::host::connection_hooks`, `zk_proto::Connection::new`, `driver::start`, `Shared::new`, `Client::from_shared`, `Shared::add_auth` |
| client (`ConnectFuture`) | `StateWatcher::{poll_keyed, forget}`, `StateCell::{poll_changed, wait_changed}`, `ResponseFuture::poll` |
| futures | `Slot::{poll_take, try_take, wait_take, drop_receiver, is_ready}`, `driver::Gate::taken`, `Shared::{terminal_error, pending_len}`, `CloseWait::{poll, wait}`, `ReadyWaiters`, `blocking_refused` |
| watch | `Slot`, `driver::Gate::taken`, `Shared::{terminal_error, remove_watch, drop_watch_sink}`, `ResponseFuture::{ready, waiting}`, `decode::void`, `path` (`ZPathBuf::from_server`) |
| codec | `error::rc_result`, `Error::from_code`, `Stat::from_jute`, `ClientInfo::from_jute`, `config::parse_config`, `path::checked`, `ZPathBuf::from_server`, `OneshotReg::arm`, `PersistentReg::arm` |
| host-observe | `Resolve`, `sync::enter_io_side`, `tracing` or `log`, `Metrics` |
| tls | `rustls`, `zk_driver::net::tls::{NativeConfig, RustlsConnector, default_provider}`, `rustix::process` |
| sasl | `zk_proto::SaslSession` (the adapter implements it), `CredentialSource`, `SaslMechanismFactory`, `md-5`, `getrandom`, `libgssapi` (feature `sasl-gssapi`) |
| route (shared) | `ReplyToken::complete`, `AuthToken::complete`, `WatchSinks::*`, `Delivery::apply`, `event_from_proto`, `watch_error_for`, `StateCell::{on_session, set_closed}`, `last_error_for`, `Observer::*`, `ReadyWaiters::wake_all`, `Shared::drop_watch_sink` |

## 3. Packages

Each package lists its files, its design sources, its main functions, its calls, its invariants and its test coverage.

### values

Files: `src/path.rs`, `src/error.rs`, `src/connect_string.rs`, `src/config.rs`, `src/auth.rs`, `tests/values.rs`.

Design: spec "Client Construction and Configuration" (connect string), "Operations, Result Types, and Error Handling" (paths and errors), "Transactions and Dynamic Reconfiguration" (config text); Java `DigestAuthenticationProvider`, `QuorumPeerConfig`.

Main functions:

| Function | File |
|---|---|
| `check_static` (const fn, manual UTF-8 decode) | path.rs |
| `ZPath::{parent, name, join, starts_with}`, `Components::next` | path.rs |
| `Error::{code, from_code, is_connection_loss, is_session_terminal, is_retry_safe}` | error.rs |
| `Display` for `Error`, `DisconnectCause`, `MultiError`, `WatchError` | error.rs |
| `map_submit_error`, `last_error_for` | error.rs |
| `connect_string::{parse, parse_update}` | connect_string.rs |
| `parse_config`, `Reconfig::wire`, `EnsembleConfig::client_connect_string` | config.rs |
| `digest_id`, `sha1`, `base64` | auth.rs |

Calls made: `zk_proto::native::{validate_path_strict, parse_connect_string_native}`, `zk_proto::Cause`, `zk_proto::Stage`.

Calls received: client (paths, errors, connect string, `Reconfig`), codec (`Error::from_code`, `parse_config`), route (`last_error_for`), shared (`map_submit_error`), `Id::digest`.

Invariants:

- `check_static` accepts exactly the paths that `ZPath::new` accepts. A const item with a bad literal fails to compile.
- `Error::code` and `from_code` are inverse for every code of `Error`. `from_code` gives `None` for 0 and maps an unknown code to `Error::Unknown(rc)`. One `server_codes!` table in error.rs gives `code`, `from_code` and the `Display` text of each server code. The macro expands inside the one `impl Error` block, so the predicates stay in plain code.
- `is_retry_safe` follows the retry rule: reads are safe after `ConnectionLoss`, writes are not. A lost reply hides whether the server applied a write.
- `last_error_for` gives `Set` with a `DisconnectCause` for each proto `Cause`. The TLS error of the probe wins when it exists.
- `parse` rejects an empty host list and a bad port with `Error::InvalidArgument`. `parse_update` also rejects a chroot.
- `parse_config` is lenient: it skips a line that it cannot read and keeps the raw text and the hex version.
- `digest_id("u", "p")` equals the Java `generateDigest` output. Compare with `zk_fakeserver::digest_id`.

### codec

Files: `src/decode.rs`, `src/ops.rs`, `src/multi.rs`, `tests/codec_fake.rs`.

Design: spec "Operations, Result Types, and Error Handling" (create modes, ACLs, null data, operations and op codes), "Transactions and Dynamic Reconfiguration" (transactions), "Wire records" (the jute records).

Main functions:

| Function | File |
|---|---|
| `reply_of`, `void`, `create`, `exists`, `get_data`, `get_data_opt`, `stat`, `children`, `children2`, `acl`, `children_count`, `ephemerals`, `who_am_i`, `config` | decode.rs |
| `create_mode_wire`, `acl_to_jute`, `acl_from_jute`, `create` | ops.rs |
| `MultiWriter::{create, delete, set_data, check, to_ops}` (`to_ops` gives borrowed `MultiOpRef` sub-ops) | multi.rs |
| `MultiResults::get` | multi.rs |
| `MultiReader::{get_data, get_children, to_ops}` | multi.rs |
| `decode_multi`, `decode_multi_read` | multi.rs |

Calls made: `zk_proto::{Op, Reply, OpResult, MultiOp}`, the jute records, and from values `Error::from_code`, `parse_config`, `path::checked`.

Calls received: client (every builder and decoder), watch (`decode::void` for `remove`).

Invariants:

- A decoder never panics. A wrong reply variant gives `Error::MarshallingError`.
- `exists` maps `NoNode` to `Ok(None)`.
- `get_data` maps null data to an empty vector. `get_data_opt` keeps `None`.
- `create_mode_wire` gives the Java flag values and the TTL. A TTL mode uses op 21, a container op 19, every other mode op 15.
- `decode_multi` gives `MultiError::Aborted { index, error, per_op }` for a rolled-back transaction and `MultiError::Request` for a failed request. Its `MultiResults` accepts only the handles of the same `writer_id`.
- `MultiResults::get` returns `None` for a handle of another writer or a result of another type.
- Builders never fail. The path is already checked.

### futures

Files: `src/future.rs`, `tests/futures_fake.rs`.

Design: spec "Core Design Principles" (`ResponseFuture`), "Lifecycle Management and Teardown" (close), "Client Construction and Configuration" (backpressure), "Execution Runtimes" (blocking).

Main functions:

| Function | File |
|---|---|
| `ResponseFuture::{is_ready, wait, wait_timeout}`, `Future::poll`, `Drop` | future.rs |
| `CloseFuture::wait`, `Future::poll` | future.rs |
| `ReadyFuture::wait`, `Future::poll` | future.rs |

Calls made: `Slot`, `Shared::{taken, terminal_error, pending_len}`, `CloseWait::{poll, wait}`, `ReadyWaiters`, `sync::blocking_refused`.

Calls received: every caller of a request method; `blocking::Client`; `ConnectFuture` (client).

Invariants:

- Cancel safety: a value leaves the slot only when `poll` or `wait` returns it.
- The read gate: a counted value calls `Shared::taken` exactly once, in `poll`, `wait` or `Drop` (section 2.4).
- `Drop` in `Waiting` drops the receiver first, then the decoder. A decoder that holds a watch registration removes its sink in its own drop.
- A closed slot gives `Shared::terminal_error()`, or `Error::Closing` when the client is gone or not terminal.
- `wait_timeout` gives the future back unchanged after the timeout.
- `ReadyFuture` resolves when `pending_len` is below half of `max_pending`, at once with no limit, and with the terminal error on a terminal client. It uses the `ReadyWaiters` generation, so no wake is lost.
- `CloseFuture` resolves when the driver exits. The result is `Ok` for a clean close, and a second close gives the same result.

### watch

Files: `src/watch/mod.rs`, `src/watch/sinks.rs`, `src/watch/oneshot.rs`, `src/watch/persistent.rs`, `src/watch/state.rs`, `tests/watch_fake.rs`.

Design: spec "Watcher Abstractions and Event Streaming", "Watcher tables", "Operations, Result Types, and Error Handling" (`last_error`), "Lifecycle Management and Teardown" (close), "Execution Runtimes" (blocking).

Main functions:

| Function | File |
|---|---|
| `event_from_proto` | watch/mod.rs |
| `WatchSinks::{deliver_event, terminate, terminate_all}`, `Delivery::apply` | watch/sinks.rs |
| `OneshotWatcher::{remove, wait}`, `Future::poll`, `Drop` | watch/oneshot.rs |
| `EventQueue::{push, terminate, poll_next, wait_next, drop_receiver}` | watch/persistent.rs |
| `PersistentWatcher::{remove, recv}`, `Drop` | watch/persistent.rs |
| `StateCell::{on_session, set_closed, set_last_error, terminal_error, poll_changed, wait_changed}`, `StateWatcher::wait_changed` | watch/state.rs |

Calls made: `Slot`, `Shared::{taken, terminal_error, remove_watch, drop_watch_sink}`, `ResponseFuture::{ready, waiting}`, `decode::void`, `ZPathBuf::from_server`.

Calls received: route (sinks, deliveries, `event_from_proto`, the state cell), client (`StateWatcher::new`, `snapshot`), codec (`OneshotReg::arm` makes a `OneshotWatcher`), futures (none).

Invariants:

- A one-shot sink gives exactly one outcome. Only the first one-shot delivery of one `Watch` item counts.
- Several watchers get an event in the given order.
- A persistent queue with no bound never drops. With a bound, a full queue drops the event, counts it, and the next take gives `Lagged` first. A terminal error never takes a queue slot. After the terminal error, the watcher gives `None`.
- `Drop` and `remove` go through `Shared::drop_watch_sink` and `Shared::remove_watch`, so proto decides when `RemoveWatches` goes out.
- `on_session` follows the mapping of its doc comment. A terminal state never changes. `epoch` grows at each connect. Wakers run after the lock is released.
- `poll_changed` resolves at once after a terminal state, always.
- An event with a path that is not UTF-8 gives `None` and is dropped. A public `String` path cannot hold such bytes.

### host-observe

Files: `src/host.rs`, `src/observe.rs`, `tests/host_observe.rs`.

Design: spec "Client Construction and Configuration" (`Resolve`, `ServerOrder`, `DnsRefresh`), "Core Design Principles" (user code on the IO side), "Observability and Diagnostic Instrumentation", "Host resolution, DNS, and failover mechanics" (the resolve leaf).

Main functions:

| Function | File |
|---|---|
| `HostLeaves::resolve` for `NativeHost`, `StdResolve::resolve` | host.rs |
| `Observer::{log_enabled, log, request_completed, state_changed, watch_event, watch_lagged, pending_requests, run}` | observe.rs |

Calls made: `Resolve`, `std::net::ToSocketAddrs`, `sync::enter_io_side`, `catch_unwind`, `tracing` (feature `tracing`), `log` (feature `log` without `tracing`), `Metrics`.

Calls received: zk-driver (the host leaves), route (`Observer::*`), client (`NativeHost::new`, `Observer::new`).

Invariants:

- `resolve` runs the user resolver inside `enter_io_side` and `catch_unwind`. A panic, an error and an empty list all fail the host. The error maps as `StdHostLeaves` maps it (`gai_rc`, `errno`). `skipped_families` is empty.
- A legacy host spec gives `EAI_NONAME`.
- The pure leaves never call user code.
- `Observer::run` catches a panic of the hook, logs it once and disables the hook for this client.
- `Observer::log` and `Observer::hook_panicked` call the log backend inside `catch_unwind`. A panic of the backend drops that record. Logging stays on, and the session continues.
- `log` renders each `LogRecord` with the client name as a field. With neither `tracing` nor `log`, it does nothing and `log_enabled` is false.

### client

Files: `src/client.rs`, `src/builder.rs`, `tests/client_fake.rs`, `tests/client_real.rs`.

Design: spec "Client Construction and Configuration", "Execution Runtimes", "Core Design Principles" (call pattern), "Operations, Result Types, and Error Handling", "Transactions and Dynamic Reconfiguration" (reconfig and `update_servers`), "Modern Protocol Capabilities", "Lifecycle Management and Teardown", "Compatibility policies and operational modes" (`Compat::NATIVE`).

Main functions:

| Function | File |
|---|---|
| Every request method of `Client` | client.rs |
| `ClientBuilder::{connect, build}` | builder.rs |
| `ConnectFuture::wait`, `Future::poll` | builder.rs |

Calls made: section 2.5, rows "client".

Calls received: user code, `blocking::Client`.

Invariants:

- Each request method follows the pattern of the `client.rs` module doc. The submit happens before the method returns.
- A bad path gives `ResponseFuture::ready(Err(InvalidPath))` and sends nothing.
- A watched read registers its sink before the submit (`Shared::register_oneshot`).
- `get_config` and `get_config_watched` ignore the chroot.
- `build` sets `Compat::NATIVE` with `max_frame_len` and `tls_verify`, `Config::max_pending`, `restore_watches`, `connect_timeout_ms`, `reconnect_delay_ms`, `reconnect_jitter_pct` (25), `close_timeout_ms`, the session id and password, and the chroot.
- `connect` builds at once, so `ConnectFuture::state_watcher` is valid at once (section 6). It resolves after `Connected` (or `ConnectedReadOnly` when allowed) and after every builder `auth` result.
- `close` starts the close in the call and gives the same outcome on a second call. `detach` keeps the session and gives its credentials.
- `session`, `negotiated_timeout`, `last_zxid`, `connected_server` and `stats` read `Connection::{client_id, recv_timeout, last_zxid, current_server}` through `Shared::read_conn`, and `epoch` from the state cell. `negotiated_timeout` is `None` before the first connection. `connected_server` is `None` while not connected.
- `update_servers` parses with `connect_string::parse_update`, then calls `DriverHandle::set_servers`.

### tls

Files: `src/tls.rs`, `tests/tls_fake.rs`, `tests/tls_options.rs`, `tests/tls_keys.rs`, `tests/tls_real.rs`.

Design: spec "Native Security Configuration", "Security Architecture: TLS and SASL", "TLS transport integration" (the native connector).

Main functions:

| Function | File |
|---|---|
| `Display` for `TlsConfigError` | tls.rs |
| `TlsOptions::{legacy_c_compat, check, connector, error_probe}` | tls.rs |
| `NativeConfig::client_config` for `TlsInner` | tls.rs |
| `TlsOptionsBuilder::build` | tls.rs |

Calls made: `rustls`, `zk_driver::net::tls::{NativeConfig, RustlsConnector::native, default_provider, TlsSetupError, TlsPurpose}`, `rustix::process::{getuid, geteuid, getgid, getegid}`.

Calls received: client (`build` calls `check`, `connector`, `error_probe`, `tls_verify`), zk-driver (`client_config` at each attempt).

Invariants:

- `build` reads and checks every file, so a bad file fails early.
- `ReloadPolicy::OnChange` reads a changed file again at the next attempt. A failed reload fails the attempt and stores the error for `error_probe`.
- `client_config` does file IO with no lock held.
- Key bytes stay in zeroizing buffers.
- The environment rule uses `rustix` for the uid and gid reads. A set-uid or set-gid process ignores `SSL_CERT_FILE` and `SSL_CERT_DIR`, as OpenSSL does.
- `with_platform_verifier` (feature `tls-platform-verifier`) replaces every trust source with the OS verifier. `build` fails with `TlsConfigError::PlatformVerifier` when it mixes with `ca_file`, `add_system_roots` or `danger_disable_hostname_verification`. It also fails in a set-uid or set-gid process on Linux and the BSDs, and when the OS verifier does not start.

### sasl

Files: `src/sasl/mod.rs`, `src/sasl/adapter.rs`, `src/sasl/digest_md5.rs`, `src/sasl/gssapi.rs`, `tests/api_sasl.rs`, `tests/sasl_fake.rs`, `tests/sasl_native.rs`.

Design: spec "Native Security Configuration", "Security Architecture: TLS and SASL"; RFC 2831 (DIGEST-MD5).

Main functions:

| Function | File |
|---|---|
| `SaslConfig`, `SaslMechanism`, `SaslMechanismFactory`, `CredentialSource`, `SaslError` | sasl/mod.rs |
| `NativeSasl`: the `zk_proto::SaslSession` adapter | sasl/adapter.rs |
| The DIGEST-MD5 mechanism (feature `sasl`) | sasl/digest_md5.rs |
| The GSSAPI mechanism (feature `sasl-gssapi`) | sasl/gssapi.rs |

Calls made: `zk_proto::SaslSession`, `SecretString`, `md-5`, `getrandom`, `libgssapi`.

Calls received: client (`ClientBuilder::build` gives the adapter to the proto `Connection`), zk-proto (the `SaslSession` methods on the IO side), the error probe (`LastSaslError`).

Invariants:

- zk-proto owns the ZooKeeper SASL rules: the op 102 framing, the send gate and the deferred `CONNECTED`. This package only makes the mechanism tokens.
- Each start makes a new mechanism from the factory. No state carries over from an earlier connection.
- A panic of the mechanism or of the credential source becomes a `SaslErrorKind::Mechanism` error. The panic never crosses the driver.
- Every SASL failure is terminal. The session goes to `AuthFailed`. `SessionInfo::last_error` then carries the `SaslError`.
- Password bytes stay in `SecretString` and zeroizing buffers.

## 4. Cross-package order

Unit tests make each package testable alone. Each end-to-end test file in `tests/` also runs the packages below. After a change to a package, run every test file that uses it.

| Test file | Uses |
|---|---|
| `tests/values.rs` | values |
| `tests/codec_fake.rs` | values, codec, futures, watch, host-observe, client |
| `tests/futures_fake.rs` | all but tls and sasl |
| `tests/watch_fake.rs` | all but tls and sasl |
| `tests/host_observe.rs` | all but tls and sasl |
| `tests/client_fake.rs`, `tests/client_real.rs` | all but tls and sasl |
| `tests/tls_fake.rs` | all but sasl |
| `tests/sasl_fake.rs`, `tests/sasl_native.rs` | all but tls |

## 5. Review checklist

1. No `todo!` is left: `rg 'todo[!]' crates/zk-client/src` prints nothing.
2. The checks of section 1.3 pass with each feature set and with no warning.
3. The real-server tests pass with a `ZK_TEST_PORT_BASE` of your own.
4. A new difference from the spec goes in section 6.

## 6. Known differences and open issues

- Module layout: the futures are in `future.rs`, not `client.rs`, so the futures and client packages own different files. `decode.rs`, `ops.rs`, `shared.rs`, `route.rs`, `driver.rs`, `host.rs` and `follow.rs` are private modules that the spec does not name.
- The `serde` feature covers `Stat`, `Acl`, `Id`, `Perms`, `Zxid`, `SessionId` and `CreateMode`. It also covers `Version` and `Ttl`, because `Stat` and `CreateMode` hold them. `Perms` is its wire `i32`, and `Ttl` is its value in ms.
- `ClientBuilder::connect` builds at once. Nothing waits for the first poll. Thus `ConnectFuture::state_watcher` is valid at once.
- `get_children` returns `Vec<String>` (spec). `get_children_with_stat` gives the stat too. Versions take `impl VersionArg` (`Version` or `None`).
- `StateWatcher::last_error` is a spec addition. `StateWatcher` holds an `Arc<StateCell>`, and `wait_changed` (feature `blocking`) is an addition.
- `MultiResults::get` returns `Option`. `ReadyFuture` gives a `Result`.
- The drivers do not count bytes. Thus `ClientStats::{sent, received}` stay 0, and the client never calls `Metrics::bytes`.
- `blocking_refused` refuses a wait on the IO side of any client. For a client of the tokio driver, it also refuses a wait in any tokio runtime context. Tokio has no public test that tells a worker from a `spawn_blocking` thread, so such a client also refuses a wait in `spawn_blocking`. A client of the thread driver accepts a wait in each tokio context, also in a worker, because the wait cannot stop its driver. In such a context, a client of the tokio driver can use `Handle::block_on` on the future.
- With no `ClientBuilder::runtime`, the client uses the current tokio runtime only when it is a multi-thread runtime. A current thread runtime gets the thread driver.
- The futures and the one-shot watcher registrations keep a `driver::Gate`, the read gate reporter of the driver. Thus a take after the last `Client` drop still opens the read gate.
- `update_servers_from_config` uses the follower in `follow.rs`. It reads `/zookeeper/config` with a watch, through the completion kind `NativeCompletion::Follow`. Each new config text replaces the server list. The first read applies too.
- The tokio driver has no exit hook. The router ends the sinks at a terminal session state. A follower task also calls `Router::on_exit` when the driver task ends, or when a runtime shutdown drops it.
- `Connection::detach_watcher` gives a `RemoveHint` only when no request in flight sets the same watch. The router detaches again when a reply has no receiver, so the hint comes then.
- `Shared::release_watch` detaches the watcher and queues its `RemoveWatches` request under one core lock. A concurrent registration on the same path and watch class thus gets a later xid, and the removal does not cancel it.
- A connect, IO or socket cause with an OS errno gets `io::Error::from_raw_os_error` as its `last_error`. The errno comes from `Connection::last_teardown_errno`. A `Cause` with no errno gets a synthetic `io::Error` in `DisconnectCause`.
- `rustls` types (`ServerName`, `SupportedProtocolVersion`, `ClientConfig`) are in the public TLS API.
- `TlsOptions` loads the trust and identity files through the zk-driver loaders (`TrustSet`, `Identity`). Thus the native and the drop-in clients read the same file formats.
- `tls-platform-verifier` uses `rustls-platform-verifier`. The OS verifier replaces the trust sources of `TlsOptions`, so the two cannot mix.
- The `AuthToken` slot holds a `RawResult`, so `add_auth` uses the normal `ResponseFuture`.
