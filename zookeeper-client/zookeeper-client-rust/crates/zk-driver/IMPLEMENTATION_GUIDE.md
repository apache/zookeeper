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

# zk-driver implementation guide

This guide describes the inner structure of the `zk-driver` crate for contributors. It divides the crate into seven packages: `net`, `tls`, `iocore`, `mt`, `st`, `native` and `tokio`. In this guide, a package is a group of source files with one job. It is not a Cargo package. Read the guide before you make a change that crosses packages.

The sources are this guide and `design/specification.md` (the spec). The guide names spec sections by their titles. When the guide and the spec disagree, the spec wins. Three rules of the spec shape the code:

- `TlsConn` has no `wants_read` and no handshake method (`net/tls/mod.rs`).
- The native threaded driver resolves on a short-lived DNS thread (`native.rs`, `DeferPolicy::DNS_THREAD`).
- The native drivers set `CLOEXEC`. The drop-in does not (`DriverConfig::cloexec`).

## 1. Rules for every package

### 1.1 Ownership

- A change to a shared file affects every package, so keep it small and explain it in the review.
- `zk-proto` is the source of truth for the protocol. The driver never works around a proto bug. Fix the bug in `zk-proto`, with its own test.
- The doc comment on each function is its contract. When the contract and the spec disagree, the spec wins. Fix the code or the contract in the same change.
- A `pub` or `pub(crate)` signature is a contract with zk-ffi, zk-client or another package. When you change it, change every caller in the same commit. Private functions, fields and types are free to change.
- The fields of a struct belong to the file that declares it. Other files read them only through the methods of the owner. The `IoCore` fields belong to `iocore`. The `Core` fields have fixed writers.

### 1.2 Code rules

- MSRV is Rust 1.85, edition 2024. Let-chains are not available. `gen` is a reserved word.
- `fetch_update` is deprecated on new toolchains and 1.85 has no `try_update`. Use a `compare_exchange_weak` loop (see `dispatch.rs`).
- The workspace lints deny `unwrap_used`, `expect_used`, `panic`, `indexing_slicing`, `arithmetic_side_effects`, `dbg_macro`, `print_stdout`, `print_stderr`, `large_stack_arrays` and `large_stack_frames` (threshold 8192 bytes). `missing_docs` and `unreachable_pub` warn, and the checks use `-D warnings`. Use `get`, `checked_*`, `wrapping_*` and `saturating_*`. Do not use `as` casts.
- `unsafe_code` is forbidden in the crate and in its tests. Borrow file descriptors through `AsFd`, never through `BorrowedFd::borrow_raw`.
- Production code never panics. Test modules may use a module-level `#[allow(clippy::unwrap_used, clippy::indexing_slicing, ...)]`.
- The library emits diagnostics only as proto `LogRecord` items. Make them with `host::log_record`. Do not add a logging crate.
- The allowed dependencies are `zk-proto`, `zk-jute`, `zeroize`, `rustix`, the optional `tokio` (Unix only), `socket2` (Linux and Android only, for `TCP_MAXSEG`, because `rustix` has no setter for it), and the optional TLS crates of `Cargo.toml`: `rustls`, `rustls-pki-types`, `rustls-webpki`, the RustCrypto parsers and key crates, and the provider crate. Use `socket2` only for socket options that `rustix` does not have. Do not use `mio`, `polling`, `nix`, `crossbeam`, `parking_lot`, `log` or `tracing`. A new dependency or a new feature needs a separate review, because it changes the supply chain of every build.
- The library starts threads only in `spawn_std.rs`. Other code gets a `Spawn` object.
- Big values go on the heap. `Connection` is in a `Box` in `Core`. Do not keep a `Connection` or a rustls session by value in a stack frame. The spec target is 4 KiB per frame ("Socket abstractions, polling, and wakeups"). The lint stops at 8 KiB.
- Every new file starts with the ASF header of the other files, then a blank line.

### 1.3 Checks for each package

Run these checks before you send a change. Use an explicit feature set. Never use `--all-features`: exactly one TLS provider is allowed. In zsh, put only the feature list in a variable (`fs=tls,tls-ring`), then pass `--features $fs`.

```sh
for fs in "" "tls,tls-ring" "tls,tls-ring,tokio,std-thread"; do
  cargo build  -p zk-driver --no-default-features ${fs:+--features $fs}
  cargo clippy -p zk-driver --no-default-features ${fs:+--features $fs} --all-targets -- -D warnings
  cargo +1.85.0 check -p zk-driver --no-default-features ${fs:+--features $fs} --all-targets
  cargo test   -p zk-driver --no-default-features ${fs:+--features $fs}
done
cargo fmt -p zk-driver --check
```

The aws-lc providers (`tls-aws-lc-rs`, `tls-aws-lc-rs-fips`) need a C toolchain and CMake. Check them when the machine has them.

A change to the locks, the atomics, the outbox or the dispatch FIFO also needs the loom models and a clippy pass under `--cfg loom`:

```sh
cargo xtask loom -p zk-driver
RUSTFLAGS='--cfg loom' CARGO_TARGET_DIR=target/loom \
  cargo clippy -p zk-driver --all-targets -- -D warnings
```

A change to the socket code, the waker or other low-level code also needs the Miri runs (nightly with the `miri` component):

```sh
cargo xtask miri --only zk-driver,zk-driver-tls
```

### 1.4 Tests

- Unit tests use the helpers of `src/testutil.rs`: `TestHost`, `test_config`, `test_connection`, `native_connection`, `exists_request`, `socket_pair`, `RealPoll`, `NullTls` and `FakeConnector`. `RealPoll` does not use `RustixPoll`. `socket_pair` does not use `TcpSock::open`. Thus a unit test of one package does not depend on the code of `net`.
- A test may drive a `Connection` with proto calls (`begin_interest`, `poll_action`, the `handle_*` calls) to reach a state, then give it to the code under test.
- Fake-server tests are in `tests/` (integration test crates) and in `src/iocore_tests.rs`. The shared helpers are in `tests/common/mod.rs`. A helper that only one test file uses stays in that file. A helper that several test files use goes in `tests/common/mod.rs`.
- Put a new fake-server test in the test file of its package. Tests that use only the public API go in `tests/`.
- The zeroization tests have `#[ignore = "free-scan"]`. They need a scanner of freed heap blocks.
- The loom models are in `src/loom_models.rs`. They compile only under `--cfg loom`, and `cargo xtask loom` runs them with a preemption bound of 3. loom threads play the IO thread, the dispatch thread and the application threads. The models check that each completion token has exactly one delivery, that results arrive in the wire order, that the dispatch thread leaves only after the close mark, and that a result of `destroy` reaches the dispatch thread once, in a foreign close and in a close from a completion callback. A loom wait never times out, so a model must notify every timed waiter.
- `cargo xtask miri` runs the unit and integration tests under Miri with strict provenance. The `zk-driver` run uses host loopback sockets. The `zk-driver-tls` run takes the TLS unit tests. The task makes the TLS fixtures and the corpus outside Miri with the `zk-fixtures` tool of zk-testkit, because Miri cannot start OpenSSL. A test that Miri cannot run has `#[cfg_attr(miri, ignore = "<reason>")]`.

## 2. Shared structure

### 2.1 Shared files

These files hold the infrastructure of all packages.

| File | Content |
|---|---|
| `Cargo.toml` | Dependencies and the features `tls`, `tls-ring`, `tls-aws-lc-rs`, `tls-aws-lc-rs-fips`, `tokio`, `std-thread`. |
| `src/lib.rs` | The module tree, the re-exports and the provider `compile_error!` checks. |
| `src/sync.rs` | `Mutex`, `Guard`, `Condvar`, the `atomic` types and the `Unlocked` token. Under `--cfg loom` these types wrap the loom types. |
| `src/clock.rs` | `Clock`, `MonotonicClock`, `TestClock`. |
| `src/host.rs` | `DriverTokens`, `HostLeaves`, `Discarded`, the proto hook adapters, `connection_hooks`, `log_record`, `discard_leftovers`. |
| `src/outbox.rs` | `Item`, `Step`, `Flavor`, `ResumeAt`, `Publish`, `Outbox`, `Requeued`, `pump` and the FIFO rule. |
| `src/loom_models.rs` | The loom models of the dispatch FIFO, the close mark and the close tail (`--cfg loom` only). |
| `src/state.rs` | `Env`, `SaslAnswer`, `Core`, `read_conn`. The API-entry helpers `read_at_entry`, `with_conn_at_entry`, `Core::submit_or`, `Core::add_auth_or` and `Core::set_servers_native`. |
| `src/lifecycle.rs` | `Lifecycle`: refs, prolog, epilog, the close flag, the fork check. |
| `src/dispatch.rs` | `RouteSink` and `ReadGate` of the native drivers. |
| `src/net/tls/mod.rs` | `TlsConn`, `TlsFailure`, `TlsSetupError`, `TlsPurpose`, `TlsConnector`, `ReadFailure`, `TlsTransport`, `alert_attempt`. |
| `src/net/tls/{pem,x509,trust,legacy_verify,v1_leaf}.rs` | The trust store and the certificate verifiers. |
| `src/net/tls/{identity,legacy_pem}.rs` | The client identity: `Identity::load`, `LegacyClientCert`, PBES2 and traditional PEM key decryption. |
| `src/testutil.rs` | Unit-test helpers (section 1.4). |
| `tests/common/mod.rs` | Fake-server helpers: `IpHost`, `legacy_connection`, `native_connection`, `env`, `drain`, `kinds`, `wait_until`, `fixture`, the sink `Collect`, `TestSpawner`, `SlowHost`, the requests `exists_at`, `exists` and `exists_native`, and the `tls` module (`TlsAcceptor`, `certs`, `key`, `provider`, `legacy_path`, `Native`, `native_connector`, `legacy_connector`). |
| `tests/fixtures/tls/` | `gen.sh` and `trust/gen.sh`. The tests do not use committed TLS material. `zk_testkit::fixtures::tls_dir` runs both scripts at test time and keeps the output in the test cache. The output is the EC P-256 test certificates, `crl.pem`, `keys/` (the client-identity corpus) and `trust/`. `zk_testkit::fixtures::tls_corpus_dir` gives the trust-decision corpus that `c/tests/conformance/tls/gencorpus.sh` makes. The scripts need OpenSSL 3.4 to 3.x. `ZK_TEST_OPENSSL` selects the binary. |

### 2.2 The layers

```
zk-ffi (drop-in C ABI)             zk-client (native API)
   |                                   |
   v                                   v
threaded::Shared (mt)              native::NativeHandle (threads)
polled::Polled   (st)              tokio::TaskHandle    (tokio task)
   |                                   |
   +---------------+-------------------+
                   v
   iocore: IoCore::{interest_step, process_step, resume,
                    complete_job, flush_once, close_flush}, run_job
                   |                         |
                   v                         v
   net: TcpSock, Poll, probe,       iocore_tls -> TlsConn (tls: RustlsConn)
        resolve::{run, apply_results}         -> TlsConnector (tls: RustlsConnector)
                   |
                   v
   zk-proto: Connection (the sans-IO state machine)
```

`init::Init` (net) makes the `Connection` of a drop-in handle. Its `Ready` result goes to `Shared::from_init` or `Polled::from_init`.

### 2.3 Locks and the `Core` fields

The drop in handles have two locks: `core` (a `Mutex<Core>`) and the IO side (`io`, a `Mutex<IoCore>` or a wrapper). The mt handle also has the `fifo` lock of the dispatch FIFO (`DispatchQueue`). The lock order is `io`, then `core`, then `fifo`. The `fifo` lock is a leaf. A call that blocks (resolve, probe, poll, close flush) holds no `core` lock. An `Unlocked` token shows that the thread holds no `core` lock and no leaf lock. It makes no claim about `io`, so a thread that holds `io` can have a token. Entry points and thread roots create tokens by convention, and the compiler does not check this rule. Only `Mutex::lock(&mut Unlocked)` takes the `core` lock, except the nonblocking `try_lock` in the fork child cleanup.

The `sync.rs` facade clears lock poisoning. User code does not run under `core` or `fifo`. The Rust client calls some user hooks under `io` inside `catch_unwind`: wakers, `Metrics`, the log backend, `Resolve` and the SASL mechanism steps. A panic in them does not poison `io`. Other user code runs under `io` with no guard, for example the callbacks of a user rustls config (`TlsOptions::from_rustls`) and the destructors of user objects. A panic there, or any other panic under `io`, is a panic of the IO runner. The runner then tears down the session and does not resume protocol work. The teardown takes the locks, which can be poisoned, to give back the tokens.

The drivers take every lock and every shared atomic from `src/sync.rs`, never from `std::sync`. A build with `--cfg loom` then puts the loom types under the real driver code, and the loom models explore its interleavings. A `static` and the test clock keep the `std` atomics, because the loom constructors are not `const`.

| `Core` field | Writer |
|---|---|
| `conn` | The driver packages (`mt`, `st`, `native`, `tokio`) at the final close (take). Every package calls proto through it. |
| `stopped` | `Core::pump` (shared) sets it. `iocore::resume` takes it. |
| `requeued` | Driver `requeue`. `IoCore` steps restore it first. |
| `local` | `Core::pump` with the st flavor. `st` drains it. |
| `peer` | `iocore` (connect and close). Drivers read it for `peer_addr`. |
| `sasl` | Driver `sasl_result`. `iocore::resume` takes it. |

### 2.4 Outputs and barriers

Every step call writes into a caller `Outbox`. `Core::pump` moves the proto outputs into it, with the FIFO rule of the flavor. A barrier (a SASL item, a LOG flush before a blocking call, a stopped stage) ends the step with `Progress::More`. The driver then returns `Step::Drain` (or the step of `ResumeAt`). C drains the outbox and calls the resume entry point. The native drivers route the items to a `RouteSink` instead and continue at once.

### 2.5 Call graph

| Caller | Callee |
|---|---|
| `init` | `host::connection_hooks`, `Connection::new`, `resolve::run`, `Core::pump` |
| `mt`, `st` | `IoCore::{interest_step, process_step, resume, flush_once, close_flush, sasl_addrs}`, `Core::{pump, restore_requeued}`, `Lifecycle`, `Waker` (mt), `Env::step_ctx` |
| `native` | `IoCore::{interest_step, process_step, resume, take_jobs, complete_job, close_flush}`, `iocore::run_job` (DNS thread), `Poll`, `Waker`, `ReadGate`, `RouteSink`, `Spawn` |
| `tokio` | as `native`, with `spawn_blocking` for `run_job`, `AsyncFd` for readiness (one registration per wait) and `Notify` for wake |
| `iocore` | `TcpSock::{open, start_connect, send, recv, recv_spare, socket_error, local_addr, peer_addr}`, `map_revents`, `request_flags`, `probe`, `resolve::{run, apply_results}`, `iocore_tls::*` |
| `iocore_tls` | `TlsConn` methods, `TlsConnector::new_conn`, `alert_attempt` |
| `net::probe` | `TcpSock`, `Poll`, `TlsConnector::new_conn` (TLS probe) |
| `tls` | rustls, the trust and identity files |

## 3. Packages

### net

Files: `src/net/sock.rs`, `src/net/poll.rs`, `src/net/probe.rs`, `src/net/waker.rs`, `src/net/mod.rs`, `src/resolve.rs`, `src/init.rs`, `tests/net_probe.rs`.

Sources: spec "Socket abstractions, polling, and wakeups"; zookeeper.c `zookeeper_init_internal`, `resolve_hosts`, `zookeeper_set_sock_nodelay`, `ping_rw_server`.

Also in the package: `net/mod.rs`, `net/waker.rs` (`Waker`, `WakeRx`), `TcpSock::{from_owned, into_owned, raw, send, recv, recv_spare, socket_error, local_addr, peer_addr}`, `errno_value`, `ResolveRun::new`, `shuffle_on`.

Main functions:

| Function | File |
|---|---|
| `TcpSock::open(addr, cloexec) -> Result<Opened, Errno>` | sock.rs |
| `TcpSock::start_connect(&self, addr) -> Result<ConnectStart, Errno>` | sock.rs |
| `RustixPoll::poll` | poll.rs |
| `map_revents`, `clamp_timeout_ms`, `request_flags` | poll.rs |
| `probe(..) -> ProbeEnd`, `is_rw_reply` | probe.rs |
| `resolve::run`, `resolve::apply_results` | resolve.rs |
| `Init::new`, `Init::step`, `Init::abandon` | init.rs |

Calls made: `host::connection_hooks`, `Connection::new`, `zk_proto::legacy::connect::parse_init`, `HostLeaves::resolve`, `HostLeaves::rng_reseed`, `Core::pump`, rustix.

Calls received: `iocore` (sock, poll, probe, resolve), every driver (`Poll`), zk-ffi (`Init`).

Invariants:

- `open` sets `NONBLOCK`, `CLOEXEC` only when asked, `SO_NOSIGPIPE` on Apple, and `TCP_NODELAY`. A `TCP_NODELAY` failure is not an error (`Opened::nodelay_ok`).
- On Linux, for a path through the loopback device, `open` sets `TCP_MAXSEG` to one sixth of the receive buffer before the connect, and ignores a failure. The value is from 88 to 32767. No limit applies when one sixth of the buffer holds each loopback segment. Linux sends a window update only when the free window can at least double. If one peer segment is larger than half of the full window, the peer can wait for its persist timer, about 200 ms. The window is a part of the buffer, about half or more on loopback, so the limit keeps a margin. A loopback or unspecified address uses the loopback path. For another address, a UDP socket connects to it and sends no packet. If its source address is the same address, the address belongs to this host. That check costs four system calls for each connect attempt, and a failure gives no limit. The limit also makes the segments that the client sends smaller.
- `start_connect` maps `EINPROGRESS`, `EWOULDBLOCK` and `EINTR` to in-progress.
- `map_revents`: IN gives READ; OUT or HUP gives WRITE; ERR or NVAL gives READ and WRITE.
- The probe never blocks past its phase budget, and the waker or the abort flag ends it.
- The resolution loop flushes LOG items before each `resolve` leaf (`Progress::More`), and holds no `core` lock in the leaf.
- `Init` gives the prefix log lines before a port error.

### tls

Files: `src/net/tls/conn.rs`, `src/net/tls/connector.rs`, `src/net/tls/verify.rs`, `tests/tls_conn.rs`.

Sources: spec "TLS transport integration" and "TLS verification". Only the `tls` feature builds these files.

Main functions:

| Function | File |
|---|---|
| `RustlsConn::new`, `feed_all`, `process`, `latch` | conn.rs |
| `impl TlsConn for RustlsConn` (every method) | conn.rs |
| `RustlsConnector::legacy_config`, `TlsConnector::new_conn` | connector.rs |
| `impl ServerCertVerifier for ChainOnlyVerifier` | verify.rs |
| `webpki_verifier` | verify.rs |

Calls made: rustls, `rustls-webpki`, the trust and identity files (`pem`, `trust`, `identity`), `default_provider`.

`legacy_config` runs these steps in order: `TrustSet::load_ca_file`, `load_default_paths`, `Identity::load`, then the verifier. The legacy verifier is `legacy_verify::LegacyChainOnly`, and the identity resolver is `identity::LegacyClientCert`. `ChainOnlyVerifier` stays for the `legacy_c_compat` mode of zk-client and for the session tests of `conn.rs`. `tests/tls_identity.rs` holds the client-identity corpus.

Calls received: `iocore_tls` and `probe` through the `TlsConn` and `TlsConnector` traits.

Invariants:

- The first `take_ciphertext` gives the ClientHello.
- A failure is latched once. Plaintext that arrived before it is given first.
- `feed` consumes every byte or latches a failure.
- Each attempt and each probe gets a new `ClientConfig`.
- Key bytes stay in `SecretBytes`.
- CA, certificate and key files go through `connector::read_regular`. Each must be a regular file of at most `MAX_FILE` (32 MiB). A FIFO, a device or a directory candidate is skipped.
- `check_params` accepts PBKDF2 iteration counts from 1 to `PBKDF2_MAX_ITER` (10,000,000). A larger count fails with `KeyError::Bounds` before any derivation.
- The trust and identity files are shared files.

### iocore

Files: `src/iocore.rs`, `src/iocore_tls.rs`, `src/iocore_tests.rs`.

Sources: spec "Shared driver execution pipeline" and "Outbox management and synchronization barriers"; zookeeper.c `zookeeper_interest`, `zookeeper_process`, `check_events`, `flush_send_queue`, `recv_buffer`.

Main functions:

| Function | File |
|---|---|
| `run_job` | iocore.rs |
| `IoCore::{interest_step, process_step, resume, complete_job, flush_once, close_flush, sasl_addrs}` | iocore.rs |
| `handshake_start`, `handshake_step`, `tls_read`, `take_plaintext`, `tls_flush`, `pump_out`, `close_notify` | iocore_tls.rs |

Calls made: `net` (sock, poll, probe, resolve), `TlsConn`, `TlsConnector::new_conn`, `alert_attempt`, `Core::{pump, restore_requeued, resume_stopped}`, the proto handlers.

Calls received: `mt`, `st`, `native`, `tokio`.

Invariants:

- Pump the proto outputs after each proto call.
- A barrier stores `cont` and returns `More`. `resume` continues there.
- `interest_step` writes `out` only in the call that reaches `finish_interest`.
- Check `has_transport` after every relock of `core`.
- No `core` lock is held across a blocking call.
- The read gate is checked before each read and each `handle_read`.
- The st flavor reads `first_read_hint()` bytes for plaintext. When that read completes a frame length, the same call reads the frame body, as `recv_buffer` does. Outside the handshake reply, one call never reads past the end of one frame. After a 40-byte legacy reply, the 41-byte handshake read holds the first byte of the next frame, and the next call parses it.
- While the handshake reply is due, `first_read_hint()` is the whole 41-byte `ConnectResponse` frame. The st flavor then reads the reply in one `recv`, as the C primer read does. In all other states it is `read_hint()`.
- In every plaintext flavor, a read that fills its request and leaves the frame open gives a read of the rest of that frame in the same call. After one of those reads blocks, the later calls for that frame make no more of them (`rx_more_blocked`).
- A plaintext read that is wholly inside the current frame body goes straight into the body (`body_room`, `recv`, `handle_body_read`) under one `core` lock. This occurs only when no received bytes wait. Other reads go to `rx`.
- `IoCore::new` does not zero `rx` (`RxBuf`). A read of the full capacity clears the buffer and goes into the spare capacity (`recv_spare`), so the length becomes the read count. A smaller read zeroes only the bytes above the current length. The read size does not depend on the initialized part of the buffer. The parser sees only the bytes of the last read (`rx_fresh`) or `rx_pending`.
- `close_flush` returns `ZOK`, `ZOPERATIONTIMEOUT`, `ZCONNECTIONLOSS` or `ZSYSTEMERROR` and never exceeds its budgets.

### mt

Files: `src/threaded.rs`, `tests/mt_fakeserver.rs`.

Sources: spec "multithreaded driver design (`libzookeeper_mt`)"; mt_adaptor.c (`adaptor_init`, `do_io`, `do_completion`, `adaptor_finish`), zookeeper.c `zookeeper_close`.

Main functions:

| Function | File |
|---|---|
| `Shared::new` | threaded.rs |
| `thread_enter`, `thread_exit` | threaded.rs |
| `io_step`, `resume`, `wait_dispatch`, `close` | threaded.rs |
| `submit`, `submit_with_data`, `submit_multi_ref`, `add_auth`, `set_servers` | threaded.rs |
| `sasl_result`, `requeue`, `peer_addr` | threaded.rs |
| `interest_exported`, `process_exported` | threaded.rs |

Also in the package: `from_init`, `outbox`, `state`, `recv_timeout`, `client_id`, `DispatchQueue` (`Publish`).

Calls made: `iocore` steps, `Waker::{new, wake}`, `WakeRx`, `Lifecycle`, `Core::pump`, `Connection::{submit_with_data, submit_multi_ref, add_auth, set_servers, begin_close, queue_close_op, close_step, destroy}`, `parse_init` (set_servers).

Calls received: zk-ffi (mt build).

Invariants:

- A submit takes the `core` lock, calls proto, then wakes the IO thread.
- A wake adds WRITE to the poll events when the poll included an open transport in its data phase (`Transport::data_phase`). The data phase is a plaintext connection or a TLS session after its handshake. The process step thus sends a new request in the iteration of its wake, with no second interest call and poll. A connect in progress, a TLS handshake, a parked socket and a requested close get WRITE only from the socket. The flush sends only what proto releases.
- The dispatch FIFO keeps the wire order. A result with no outbox entry ahead of it goes to the FIFO in the pump, under the `core` lock. A later result waits in the outbox as a publish entry, and goes to the FIFO when the C drain reaches it. A pump on another thread thus cannot put a later result before an earlier one.
- The pump stops when the outbox holds an item that C sees and proto has more output. The later results stay in proto. A close from the callback of that item then pumps them before its ZCLOSING results and its close mark. Thus the dispatch thread delivers every reply that was decoded before the close.
- The close tail is the "Closing" line, the CLOSE_OP, the flush and wait, the "Freeing" line and `destroy`. It runs exactly once. A foreign close runs it after the IO join and before the close mark, so the dispatch thread delivers the results of `destroy`. Before the tail, a foreign close waits until the dispatch thread has delivered every queued result (`DispatchQueue::wait_idle`). The tail lines thus follow those callbacks, as in the C client, where the completion thread runs its callbacks before the close logs. When a completion callback continues a requeued foreign close, the close skips this wait, because the dispatch thread cannot wait for itself. The auth completions that `destroy` fails run on the closing thread (zookeeper.c `free_completions`). A close from a completion callback leaves it to the exit of the dispatch thread. That thread runs the tail and delivers its results itself, in order (`Outbox::deliver_here`). A close from an IO callback leaves it to the final close on the IO thread, after the dispatch thread exits. There, the results of `destroy` go to `discard_token`, as zookeeper.c `destroy` frees them with no callback. With no socket, the tail skips `close_flush` and the rc is `ZOK` (zookeeper.c `zookeeper_close`).
- The final close runs in the call that drops the last ref. It runs the tail when no close ran it, then the release. Only the release and the tail of a close from an IO callback call `discard_token`.
- A callback can end the dispatch thread, for example with `pthread_exit`. The cleanup handler of the shim then calls `Shared::dispatch_ended`. It sends the untaken batch items to `discard_token`, sets `dispatch_ended` and wakes `wait_idle`. The wait returns when `dispatch_ended` is set, so a foreign close does not hang. The thread keeps its ref, because the last ref runs the final close. The join returns only after the handler ran. So the epilog of a later close from a foreign thread or an IO callback gives back the ref of the ended thread. The C client never frees the results that are left, because it leaks the handle. The drop in discards them at the release, with no callback, and frees the handle. After a close from a completion callback, the thread exit runs the close tail. A `pthread_exit` after such a close thus leaks the handle, as in the C client.
- A forked child closes the socket and sends nothing.
- `interest_exported` reports fd -1 and `process_exported` gives `ZINVALIDSTATE`.

### st

Files: `src/polled.rs`, `tests/st_fakeserver.rs`.

Sources: spec "single threaded driver design (`libzookeeper_st`)"; zookeeper.c `zookeeper_interest`, `zookeeper_process`, `process_completions`, `zookeeper_close` (st paths).

Main functions:

| Function | File |
|---|---|
| `Polled::new` | polled.rs |
| `interest`, `process`, `resume` | polled.rs |
| `submit`, `submit_with_data`, `submit_multi_ref`, `add_auth`, `set_servers` | polled.rs |
| `close` | polled.rs |
| `wait_events` (a test and tool helper: poll the handle socket through `Env::poll`) | polled.rs |
| `sasl_result`, `requeue`, `peer_addr` | polled.rs |

Also in the package: `from_init`, `outbox`, the accessors.

Calls made: `iocore` steps, `IoCore::flush_once`, `close_flush`, `Core::pump`, `Core::local`, `Lifecycle`, proto.

Calls received: zk-ffi (st build).

Invariants:

- The local FIFO drains at the C drain points.
- A submit runs one nonblocking flush pass when a socket exists. A send failure calls `handle_forced_close` and drops the transport.
- A close inside a callback defers to the end of the drain (`StClose::Deferred`).
- The final close flushes, then releases (`Step::Release`). With no socket, it skips `close_flush` and the rc is `ZOK`.

### native

Files: `src/native.rs`, `src/spawn_std.rs`, `src/host_std.rs`, `tests/native_fakeserver.rs`.

Sources: spec "Native driver implementations".

Main functions:

| Function | File |
|---|---|
| `start`, `io_loop`, `dns_thread` | native.rs |
| `NativeShared::{pass, route}` | native.rs |
| `NativeHandle::{submit, submit_with_data, submit_multi_ref, add_auth, set_servers, close, wait_closed, detach}` | native.rs |
| `impl Spawn for StdSpawner` | spawn_std.rs |
| `StdHostLeaves::new`, `log_enabled`, `rng_random`, `rng_drand48`, `rng_reseed`, `resolve` | host_std.rs |

Also in the package: `taken`, `wake`, `gate`, `state`, `client_id`, `last_teardown_cause`, the other `StdHostLeaves` leaves.

Calls made: `iocore` steps, `take_jobs`, `run_job`, `complete_job`, `Waker`, `Poll`, `ReadGate`, `RouteSink`, `Spawn`, proto.

Calls received: zk-client.

Invariants:

- Only the IO thread drives the `IoCore`. Handles take only the `core` lock, then wake.
- Resolution runs on one DNS thread at a time (`DnsSlot`). Its result wakes the IO thread.
- A wake adds WRITE to the poll events when the poll included an open transport in its data phase and no close is requested. The `threaded` driver uses the same rule.
- Routed items that count close the read gate at capacity. `taken` opens it.
- `wait_closed` returns the exit value of the IO thread, or `None` at the timeout.
- `io_loop` catches a panic of a pass. `NativeShared::abort_after_panic` then releases the connection and gives back all tokens, with no protocol step. The exit value is `ZCONNECTIONLOSS`, as the Tokio `ExitGuard` gives.
- `StdHostLeaves::resolve` uses `ToSocketAddrs` and keeps the host order.

### tokio

Files: `src/tokio.rs`, `tests/tokio_fakeserver.rs`.

Sources: spec "Native driver implementations". Only Unix with the `tokio` feature builds these files. Use `::tokio::` paths in the module.

Main functions:

| Function | File |
|---|---|
| `spawn`, `run` | tokio.rs |
| `TaskHandle::{submit, submit_with_data, submit_multi_ref, add_auth, set_servers, close, closed, detach}` | tokio.rs |

Also in the package: `taken`, `gate`, `state`, `client_id`, `last_teardown_cause`.

Calls made: `iocore` steps, `take_jobs`, `run_job` in `spawn_blocking`, `complete_job`, `AsyncFd`, `Notify`, `watch`, proto.

Calls received: zk-client.

Invariants:

- `DeferPolicy::ALL`: no blocking call runs on a runtime worker.
- `Progress::Wait` means the task waits for the deferred jobs.
- `closed` resolves once with the exit value.
- The task holds no `core` lock across an `.await`.
- A `Notify` wake can add WRITE to the process events. The wake must come after the wait blocked, and the wait must watch the socket. The transport must be in its data phase (`Transport::data_phase`), and the driver must have no close request. The process step thus sends a new request in the pass of its wake, with no second interest step and `AsyncFd` registration. The flush sends only what proto releases. A wake that is stored before the wait adds no event. A busy producer thus gets one large send in the next pass, not many small sends.

## 4. Cross-package order

The packages depend on the signatures of the other packages. The `net` and `tls` packages depend on no other package. `iocore` uses `net` and `tls`. Each driver package (`mt`, `st`, `native`, `tokio`) uses `iocore`. The unit tests of each package use the helpers of section 1.4, so they test the package alone. The fake-server tests in `tests/` and at the end of `src/iocore_tests.rs` run the whole stack. After a change to `net`, `tls` or `iocore`, run the fake-server tests of every driver.

## 5. Review checklist

1. Fix every new warning. Remove code that nothing uses.
2. The checks of section 1.3 pass with each feature set.
3. No `todo!` is left: `rg 'todo[!]' crates/zk-driver/src` prints nothing.
4. The aws-lc providers build and pass their tests when the machine has a C toolchain and CMake.
