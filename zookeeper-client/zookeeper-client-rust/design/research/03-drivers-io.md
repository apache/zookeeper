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

# zk-driver: drivers, I/O and the outbox callback model

## 0. Decisions in brief

- **Rust never runs below application code.** Every application callback runs from C shim frames, on every thread. This covers completions, watchers, `zoo_add_auth` completions, sync completions, log callbacks and Cyrus SASL callbacks (K2, CC-1 (00 §4)). Each driver step returns an ordered **outbox** to C (`zkrs_outbox` on the C stack, CC-2 (00 §4)). C takes items one at a time with `zkrs_outbox_next` and acts on them in `zks_drain`. Rust frames are on the stack only *inside* `zkrs_outbox_next` and the step entries, and no application code runs there.
  - The mt IO thread is a C loop over `zkrs_io_step`.
  - The mt dispatch thread is a C loop over `zkrs_wait_dispatch`, which returns a batch.
  - st `zookeeper_interest`, `zookeeper_process` and `zookeeper_close`, and every app-thread API call that logs, drain their outbox after Rust returns.
  - A step that stops at a barrier returns `ZKRS_MORE`. C drains, then calls `zkrs_resume` (`zkrs_interest_resume` in `zookeeper_interest`, §6.2). The other step results are `ZKRS_DONE`, `ZKRS_RELEASE` (final close finished, C frees `zh`) and `ZKRS_EXIT` (library-thread loops only).

  A C++ exception or `longjmp` out of any callback therefore unwinds through C frames only. On app threads it reaches the application, as in legacy. On library threads it leaves the pthread start routine, as in legacy (DSP-14 RE).
- **One I/O core shared by both C-facing drivers.** The threaded driver (mt) and the caller-polled driver (st) share `IoCore::{interest_step, process_step}`. They do C `zookeeper_interest`/`zookeeper_process` work in C order, over the zk-proto `Connection` API with proto-core's names (CC-17 (00 §4)). One mt IO iteration is interest, then `poll(2)` over `[waker, socket]`, then process. That is C `do_io` (mt_adaptor.c:369-482).
- **Threads are created by C, and the thread role comes from C.** `zks_threads.c` calls `pthread_create(&t, NULL, …)` (`_beginthreadex(NULL, 0, …)` on Windows) for exactly two threads per mt handle (K13). Joins and detaches are `THREAD` outbox items that C executes (CC-3 (00 §4)). Rust never asks which thread it is on: C passes the role from its own TLS (`zks_current_role`) where it matters (CC-4 (00 §4)). The drop-in has no `Spawner` and no Rust-created threads.
- **Sockets have one owner.** In mt only the IO thread touches the socket. That includes the forced close that `zoo_set_servers` requests: the state becomes 999 synchronously, and the IO thread closes the fd and runs the rest of the teardown. In st the socket belongs to whichever thread is inside the API, as in C.
- **Policy comes from zk-proto.** The drop-in uses `Compat::LEGACY_C`, which carries the core behavior fixes (proto-core names, ledger values; CC-15 (00 §4); 02 §14.3, 07 §5.1), and a per-driver `zk_proto::DriverMode {interest, session_delivery}` (CC-16 (00 §4)). The drivers implement these values and add no protocol policy of their own.
- **Sockets, pipes and polling use `rustix` (libc backend) only.** zk-driver uses neither socket2 nor mio. Sockets get `SOCK_CLOEXEC` only when the shim's runtime `sock_cloexec` flag (from `--with-sock-cloexec`) is set. The waker is a plain non-blocking pipe without CLOEXEC, as legacy (GLB-05).
- **TLS is rustls, in zk-driver, for both profiles (06 §3).** The one `TlsConn` implementation is `RustlsConn` (`zk-driver/src/net/tls/`, feature `tls`, which zk-ffi enables through one provider feature). It drives a `rustls::ClientConnection` over memory buffers. The driver owns every ciphertext byte on the socket, so the TLS path gets the same `MSG_NOSIGNAL`/`SO_NOSIGPIPE` sends as plaintext. The verifier policy comes from `Compat::tls_verify` (`LegacyChainOnly` for `LEGACY_C`, `WebPki` for `NATIVE`). The security section (06 §3.4-3.6) is normative for trust inputs, the verifier and key loading. OpenSSL is not used anywhere, and the host vtable has no TLS entries. A graceful close sends `close_notify` in both profiles; teardowns and forced closes send no `close_notify`. After a fatal TLS error that rustls detects, the fatal alert that rustls queued gets one non-blocking send attempt when no earlier ciphertext is waiting, as OpenSSL writes it in legacy (§4.3.5).
- **SASL is Cyrus, in C (06 §4).** The driver emits `SASL{START, STEP, DISPOSE}` items on the IO owner. On START the driver reads the local and peer addresses with rustix `getsockname`/`getpeername`, and zk-ffi writes each one into the item as a platform `sockaddr_in`/`sockaddr_in6` image (CC-31 (00 §4); 05 §19.2). START and STEP are barriers, so at most one is in flight per handle. DISPOSE is not a barrier. Dispose timing is legacy's (SAS-13 RE). A teardown closes the transport and emits no DISPOSE. On a reconnect the old Cyrus connection is disposed when the next SASL START runs, as legacy `zoo_sasl_connect` does at its top (zk_sasl.c:215-217), so proto yields DISPOSE immediately before START in the same outbox. At destroy DISPOSE follows the transport close, as legacy `destroy` closes the socket and then disposes the Cyrus client (zookeeper.c:719-749).
- **Crate.** The drivers live in **`zk-driver`** (K1). zk-ffi depends on zk-driver and zk-proto, never on `zk-client`/`apache-zookeeper`. tokio is an optional, Unix-only (`cfg(unix)`) feature that zk-ffi never enables. On Windows the native API has the threaded driver only (§7).

Milestones for this document (CC-26 (00 §4)): M0 delivers zk-driver's threaded and tokio drivers for the native API, the shared IoCore and the caller-polled driver core, and the sans-IO and driver tests (08 §15.3); M1 the mt drop-in over plaintext; M2 the st drop-in and the static archives; M3 `RustlsConn` and the SASL items; M4 Windows (the drop-in in both flavors and the native threaded driver); §8.8 holds the Windows design (CC-33 (00 §4)). If the M4 Windows spike fails, Windows ships on the legacy core for that release, recorded as DV-BLD-7 (§8.8).

---

## 1. Scope, crate and ownership

| Owned by zk-driver (`#![forbid(unsafe_code)]`) | Owned elsewhere |
|---|---|
| Step functions over `zk_proto::Connection`; the driver-side outbox, its item order and barrier rules; FIFO publication | Protocol decisions, timers, teardown, output order, log sites (zk-proto) |
| Sockets, the waker, `poll(2)`, frame reads and writes, the RW probe | Callback invocation and the post-callback deallocation order, sync waiting, errno, `struct timeval`, exported symbols, thread creation, getaddrinfo (the `resolve` leaf), Cyrus (c-shim) |
| TLS: `TlsConn`/`TlsConnector`, `RustlsConn`, `RustlsConnector`, the `LegacyChainOnly` verifier and its v1-leaf path, trust and identity loading, legacy PEM decryption | Trust-decision semantics (06 §3.4-3.6) and the TLS deviation list DV-TLS-1..6 (06 §3.13) |
| The reference-count lifecycle (`refs`, `close_requested`, `finalizing`, `io_exited`) and the close-by-role state machine | zk-ffi: the `zkrs_*` exports, the C-pointer tokens (integers, safe code) and the conversion of driver items into `zkrs_item`; every `unsafe` operation is in its `abi::raw` module: the C-memory readers and struct reads, the out-parameter writes, the host-vtable calls, the handle reference counting, the outbox `impl` and resolve-sink pointer conversions, the legacy C result layouts and the sync copy-out writers, the Windows ANSI path conversion and the Windows `WSAPoll` fallback (§8.8; 05 §8) |
| Native drivers: the native threaded driver (`DispatchMode::IoThread`) and the tokio task driver (feature `tokio`, Unix only), and their bounded IO-to-application hand-off (§7) | Native public API types, futures, the slots, per-watcher queues and session state that the IO side routes items into, `TlsOptions` (zk-client) |

```
zk-driver/src/
  lib.rs                   #![forbid(unsafe_code)]; no thread_local! with Drop in reachable code (R7)
  sync.rs                  std::sync facade; loom under cfg(loom); Unlocked proof; Unlocked::new_at_entry (05 §7); lock(&mut Unlocked)
  clock.rs                 Clock -> zk_proto::Millis; MonotonicClock
  host.rs                  HostLeaves: the Pure and Blocking leaves (zk-ffi implements them for the drop-in; the zk-client `Resolve` adapter for the native drivers)
  outbox.rs                Item<T>, SaslItem, Step, Outbox<T> (per call), publish-on-take, barrier/resume, requeue
  iocore.rs                IoCore::{interest_step, process_step}, Transport, InterestOut
  lifecycle.rs             refs / close_requested / finalizing / io_exited / final_close; close by role
  threaded.rs              mt drop-in: io_step, wait_dispatch, close; native threaded driver (IoThread)
  polled.rs                st drop-in
  dispatch.rs              native drivers: bounded hand-off (undelivered-item count, dispatch_queue_capacity) and its read gate
  tokio.rs                 cfg(all(unix, feature = "tokio")): native async task driver
  net/sock.rs              TcpSock over rustix::net (socket, connect, SO_ERROR, TCP_NODELAY, send flags)
  net/waker.rs             coalesced waker: pipe (Unix), loopback socket pair (Windows)
  net/probe.rs             bounded read-only-server probe (plaintext and TLS)
  net/tls/mod.rs           feature "tls": TlsConn, TlsConnector, TlsFailure, TlsSetupError, TlsPurpose;
                           re-exports zk_proto::{TlsConfigStep, TlsResourceAt, Endpoint}; TlsTransport (driver-side)
  net/tls/conn.rs          RustlsConn: rustls::ClientConnection over in-memory buffers
  net/tls/connector.rs     RustlsConnector: builds one ClientConfig and one ClientConnection per attempt and per probe
  net/tls/pem.rs           strict PEM block reader with RFC 1421 headers (Proc-Type, DEK-Info), base64 via base64ct
  net/tls/x509.rs          LegacyCert: read-only DER view (v1 accepted), OpenSSL name canonicalization and subject hash
  net/tls/trust.rs         trust-input loading (06 §3.4) into a TrustSet; the only environment read
  net/tls/legacy_verify.rs LegacyChainOnly (06 §3.5), its rejected-issuer and keyCertSign post-checks, and SPKI-based handshake signature checks
  net/tls/v1_leaf.rs       v1-leaf verifier for LegacyChainOnly: leaf signature by its issuer, issuer chain by OpenSSL rules; the same rules as the v3-leaf OpenSSL path post-check (06 §3.5, §3.14)
  net/tls/identity.rs      client chain and private key loading, scrypt/PBES2 parameter bounds, SPKI key-match check (06 §3.6)
  net/tls/legacy_pem.rs    traditional OpenSSL PEM decryption: EVP_BytesToKey(MD5), AES-CBC, DES-EDE3-CBC, DES-CBC
  spawn_std.rs             StdSpawner: the only module allowed std::thread::spawn (native; R6 exception; feature `std-thread`)
```

**Dependency closure** (K14; the TLS set is 06 §3.11).

- zk-driver normal deps: `zk-proto` (which brings `zeroize`, CC-19 (00 §4)), `zk-jute`, `rustix` with features `std, net, event, pipe, use-libc`. `rustix` brings `libc`, `errno` (rustix imports it under the name `libc_errno`; goldens and allowlists use the package name `errno` that cargo and cargo-deny report), `bitflags`, `linux-raw-sys` (for constants), and `windows-sys` on Windows.
- TLS features (01 §3.3, §4.8): `tls` is the TLS module. It needs exactly one provider feature: `tls-ring` (the rustls `ring` provider, the default), `tls-aws-lc-rs` (`aws-lc-rs`/`aws-lc-sys`) or `tls-aws-lc-rs-fips` (`aws-lc-rs` with its `fips` feature, sys crate `aws-lc-fips-sys`). The build selects one with `--with-rust-tls-provider=ring|aws-lc-rs|aws-lc-rs-fips` (CMake `ZK_RUST_TLS_PROVIDER`, Maven property `rust-tls-provider`). Each provider feature implies `tls`. zk-driver and zk-ffi use the same four feature names. When `tls` is enabled with no provider feature, or with more than one, a `compile_error!` stops the build, and the message names the three provider features.
- Lint and test jobs build with explicit feature sets (for example `tls,tls-ring`, `tls,tls-aws-lc-rs` and `tls,tls-aws-lc-rs-fips`), never with `--all-features`, because `--all-features` enables more than one provider.
- **The FIPS build** (`tls-aws-lc-rs-fips`) uses the aws-lc-rs `fips` feature (`aws-lc-fips-sys`, which needs CMake and Go on the build host) and the rustls `fips` feature. Every `ClientConfig` is built from `rustls::crypto::default_fips_provider()` with `require_ems = true`, and the provider offers only FIPS-approved cipher suites, key-exchange groups and signature algorithms. zk-ffi builds the provider once at handle creation and asserts `provider.fips()` (a false result is a build defect and aborts, DV-BLD-5); the connector checks `ClientConfig::fips()` on every per-attempt config, and a false result fails the attempt as `TlsResource{at: ContextNew}` (06 §3.15.2). Every signature check, the v1-leaf verifier (§8.2.4) included, uses only the FIPS module. Key loading in this build is restricted as §8.2.3 states (DV-TLS-6). The validation scope is Linux x86_64 and aarch64.
- The `tls` closure is, for the `ring` and `aws-lc-rs` builds, `rustls` 0.23.x (`default-features = false`, features `std, tls12` plus the provider), `rustls-pki-types`, `rustls-webpki`, `ring` (or `aws-lc-rs` with `aws-lc-sys`), `untrusted`, `zeroize`, `pkcs8` 0.10 (`encryption`), `pkcs5` 0.7 (`pbes2`, `3des`, `sha1-insecure`), `der`, `spki`, `pem-rfc7468`, `base64ct`, `aes`, `des`, `cbc`, `cipher`, `md-5`, `sha1`, `sha2`, `pbkdf2`, `hmac`, `scrypt`, and `rustix` (libc backend), with their transitive closure as `cargo tree` reports it. The FIPS build omits the key crates and features that 01 §3.3 R3 names for it; its golden is `zk-ffi-<target>-aws-lc-rs-fips.txt`. It includes no `rustls-pemfile`, because the PEM reader must see the RFC 1421 headers of traditional encrypted keys. It includes no `rustls-native-certs` and no `openssl-probe`: the trust loader (`trust.rs`) reads the default locations itself (§8.2.3). The rustls `logging` feature stays off, so the `log` crate is not linked.
- Native `tls-rustls` (zk-client) uses the same trust loader and adds no trust crates. It mirrors the drop-in provider features: `tls-ring` (the default provider), `tls-aws-lc-rs` or `tls-aws-lc-rs-fips`, forwarded to the zk-driver features of the same name (04). The opt-in native feature `tls-platform-verifier` (rustls-platform-verifier) lives in zk-client only (§7).
- zk-ffi depends on zk-driver with `default-features = false`. Its `tls`, `tls-ring`, `tls-aws-lc-rs` and `tls-aws-lc-rs-fips` features forward to the zk-driver features of the same name. The drop-in build is `cargo build --locked --profile capi -p zk-ffi --features <set>`, with no `--workspace`, so zk-client features never unify into the staticlib. `<set>` is `tls,tls-${rust-tls-provider}` when TLS is enabled, otherwise empty (01 §6.1, §13.2, §13.7).
- Optional: `tokio` (`rt, net, time, sync, io-util`), only under feature `tokio` and only as a `cfg(unix)` target dependency.
- Not in the drop-in closure: socket2, mio, polling, nix, crossbeam, parking_lot, tracing, log, fastrand, secrecy, futures-core, tokio, tokio-rustls, rustls-native-certs, rustls-platform-verifier, openssl-probe, rustls-pemfile, webpki-roots, openssl, openssl-sys, native-tls. `aws-lc-sys` is only in the `aws-lc-rs` closure and `aws-lc-fips-sys` only in the FIPS closure.
- zk-driver emits diagnostics only as zk-proto `LogRecord` values with a `LogSite` (CC-30 (00 §4)). zk-client turns them into `tracing` events for native users.
- The closure goldens are `supply-chain/closure/zk-ffi-<target>.txt` (TLS on, `ring`), `zk-ffi-<target>-aws-lc-rs.txt` (TLS on, `aws-lc-rs`), `zk-ffi-<target>-aws-lc-rs-fips.txt` (TLS on, FIPS build) and `zk-ffi-<target>-notls.txt` (TLS off), as 01 §3.3 names them. The `cargo tree` closure diff of 01 §3.3 R4 (08 §10 G1) checks them. In the system-crates build mode (`--with-system-rust-crates`, 01), G1 compares crate names and semver-compatible ranges instead of exact versions.
- Both aws-lc builds ship in a release only after the ASF legal review of `aws-lc-sys` and `aws-lc-fips-sys` has passed (a release prerequisite, 00 §6). `ring` stays the default.

---

## 2. Contract with zk-proto

The proto-core document is the single source of truth for the `Connection` API. This document uses its names: `Connection<T: Tokens>`, `Millis`, `Endpoint {addr: SocketAddr, host: HostToken}`, `Output<T>`/`poll_output`, `Action`/`poll_action` (`Connect{endpoint, tls}`, `ProbeRw{endpoint, tls}`, `Sasl(SaslCall)`), `begin_interest`/`finish_interest` (`InterestOutcome {rc, outputs: Option<Interest>}`), `handle_host_resolved(gen: u64, …)`/`handle_resolved(gen: u64, …)` (the resolver generation, a counter separate from the SASL `gen`), `handle_connect_started(ConnectStart)`, `handle_tcp_connected`, `handle_transport_ready`, `handle_transport_failed(TransportFailure)`, `handle_rw_probe(ep: Endpoint, outcome: ProbeOutcome, now)`, `handle_sasl_started(gen: u32, …)`/`handle_sasl_stepped(gen: u32, …)`, `handle_sasl_addr_failed(gen: u32, which: SaslAddr {Local, Peer}, now)`, `read_hint`, `handle_read` (`ReadOutcome {frames, consumed, rc}`), `poll_transmit`/`handle_transmitted`, `handle_forced_close`, `run_pending_teardown`, `submit`, `add_auth`, `set_servers`, `begin_close`, `queue_close_op`, `close_step`, `abandon`, `destroy`, `resume`, `is_suspended`, `has_transport`, `into_leftovers`, `last_teardown_cause() -> Option<Cause>`, `TlsConfigStep`, `TlsResourceAt`. `Cause` is public in zk-proto. The native drivers read `last_teardown_cause()` after a teardown, and zk-client maps it to its public `DisconnectCause` (02 §12.1; 04 §8.2). The drop-in drivers do not use it. Tokens are proto's `Tokens {Completion, Watcher, Auth}`. Time is `Millis`, supplied by the driver's `Clock`.

**What the drivers rely on** (all specified in 02):

| # | Proto behavior | Why the drivers need it |
|---|---|---|
| P-1 | **Barriers and `resume(now)`** (02 §5.3). `Completion{route: Direct}` (mt, CC-18 (00 §4); one suspension per run of consecutive Direct results with no callback item between them), `Auth{barrier: true}`, `AuthLockpoint` and `Session{barrier: true}` (st, `DriverMode::session_delivery = Inline`) suspend the continuation. The driver also returns `ZKRS_MORE` after `DrainPoint` and after SASL START/STEP items (§3.3). An outstanding Start/Step stops `handle_read` (P-9). Calls made in between (`begin_close`, `submit`) act on the state as it stands. | C runs these callbacks inline and then continues. If an st session-event callback closes the handle, pending requests get ZCLOSING, not ZCONNECTIONLOSS (zookeeper.c:3263-3266, then `cleanup_bufs`). Ledger CLS-07 and DSP-15 depend on it. |
| P-2 | **One ordered output stream.** `Log` is an `Output` variant, so log lines and callbacks keep their relative order. | Each outbox emits a log line before any later item becomes visible (§3.5). |
| P-3 | **`poll_transmit` pins every frame it copies** until `handle_transmitted` completes it or the transport is lost; T3 then fails the request of every frame queued for that transport, a partly written head included, so none of them goes onto a later connection. | mt copies the head out and sends it without the core lock; TLS holds bytes inside the session until their ciphertext is on the wire (§4.3.4). Without the pin, an `add_auth` priority insert could land in front of a half-sent frame (proto P3, AUT-02). |
| P-4 | **`handle_tcp_connected(now)` starts the TLS handshake deadline** at `now + T*2/3`, where `now` is the time of TCP-connect completion; expiry is a `TlsHandshakeTimeout` teardown with ZOPERATIONTIMEOUT, errno ETIMEDOUT. The TCP-connect phase of state 7 is exempt from the receive timeout; it reports `tv = T/3` when `recv_to <= 0`, otherwise `min(recv_to, T/3)` (ST-16(a)). `handle_transport_ready` means "transport ready, queue ConnectRequest". | ST-16; the deadline origin is TCP completion in both profiles (CC-15 (00 §4)). |
| P-5 | **An immediate TCP connect never shows state 1.** Without TLS the driver calls `handle_connect_started(Immediate)`, `handle_tcp_connected` and `handle_transport_ready` in one lock hold. With TLS the prior state (0 or 999) stays until `handle_transport_ready`: `handle_connect_started(Immediate)` does not change it, `new_conn` runs with no lock held, then `handle_tcp_connected` runs on success (06 §3.8). | Reproduces C's state on that path (zookeeper.c:2713-2722). |
| P-6 | **`TransportFailure::TlsConfig{step, file}` parks the attempt** (CC-14 (00 §4)): rc **ZSSLCONNECTIONERROR**, errno EINVAL, the legacy line for `step`, no teardown, no WRITE interest, no retry. The ordinary receive rule then fails over with ZOPERATIONTIMEOUT on both paths, also in state 7. On the immediate path `last_recv` keeps C's un-reset value, because `handle_tcp_connected` never runs before a config failure. | CC-14 (00 §4); zookeeper.c:2717-2720 and 3093-3094: `init_ssl_for_socket` returns ZBADARGUMENTS internally, and both callers return ZSSLCONNECTIONERROR. |
| P-7 | **Forced close, `Compat::forced_close = Teardown`** (a rebalance after `zoo_set_servers` or after a re-resolution inside `zookeeper_interest`; st submit-flush failure through `handle_forced_close`): state 999 in the same call, `CloseTransport` for the IO owner, pins cleared, `tx`, the FIFO and the pending auth tokens taken as one snapshot, no SASL item (a live Cyrus session is disposed at the next START or at destroy, DR2) and no other output. `run_pending_teardown` then runs the remaining stages on the IO owner in its next driving call, or in that call for a rebalance found in `zookeeper_interest`: the CONNECTING event if the state was connected, ZCONNECTIONLOSS for the snapshot alone, the state still 999, no delay and no cursor move. A request submitted after the trigger stays queued whole for the next connection (02 §12.3). | ST-13 (DV-FIX-8), RC-05. An API call runs no callback; every result has the thread of any other teardown; the outcome of a later request does not depend on when the IO owner runs. |
| P-8 | **`Compat::sasl_failure = Teardown`**: state -113 and a full teardown in the call that sees the failure: the AUTH_FAILED event, ZAUTHFAILED for all pending work, the SASL rc from the call, and no `Dispose` before destroy (02 §7.4). No driver flag exists; st and mt differ only through `DriverMode` (CC-16 (00 §4)). | ST-18 (DV-FIX-3; DRV-17). |
| P-9 | **`handle_read` stops** at a pending barrier, while a SASL Start/Step action is outstanding, and when the decoder stalls. `read_hint()` is 0 in those states. | mt reads up to 64 KiB per pass and TLS decrypts whole records. Frames after a barrier must not be processed until the callback has returned. |
| P-10 | **`InterestOutcome.outputs` is `None` exactly where C returns before writing** `*fd/*interest/*tv` (unrecoverable state; whole-list resolution failure with no socket under `dns_failure = LegacyRcPreserved`). With a socket open, a DNS failure runs timers and writes outputs (CC-22 (00 §4)). | §4.1 rule O1. |
| P-11 | **`Rng {on_resolution, random, drand48}`**; xids per handle, seeded by the shim with `time(0)` (K7, K8). | The shim's libc PRNG reproduces SRV-04. |

**Proto behaviors the drivers depend on (DR1-DR5).** 02 specifies each one. The ledger and the tests cite these IDs.

| # | Requirement | Where 02 specifies it |
|---|---|---|
| DR1 | `Output::AuthLockpoint`, a barrier, emitted *before* the state change and completion collection at each legacy `zoo_lock_auth` site: AUTH reply (zookeeper.c:2160), teardown collection and close collection (2062), auth resend (2216). Emitted under `LEGACY_C` in both flavors (C makes it a no-op in st); never under `NATIVE`. | 02 §5.4; CC-7 and CC-16 (00 §4) |
| DR2 | `SaslCall {gen: u32, kind: SaslCallKind {Start, Step(Option<SecretBytes>), Dispose}}`. `Dispose` is yielded at two points only, whenever a Cyrus session exists: immediately before the `Start` of the next negotiation, in the same outbox, as legacy `zoo_sasl_connect` disposes first (zk_sasl.c:215-217); and by `destroy`, after `CloseTransport`, as legacy `destroy` closes the socket before it disposes the Cyrus client (zookeeper.c:719-749). A teardown (T1) or forced close yields no `Dispose`. The SASL generation still bumps at teardown, so a late result for the old session is stale. At most one Start/Step is outstanding per handle. A `Dispose` (and any `Start` behind it) that is due while a Start/Step result is outstanding waits for that result, which is then ignored as stale. In the drop-in, zk-ffi always supplies that result: after a callback throw or a stale-rejected item it passes a stale `Err` result for the old generation once the teardown has advanced it (05 §5, §19.2). A stale `gen` in `handle_sasl_started(gen: u32, …)`/`handle_sasl_stepped(gen: u32, …)` is ignored. | 02 §4.1, §7.4, §12.1; CC-17 (00 §4); 06 §4.7.4 |
| DR3 | `TransportFailure::TlsConfig {step: TlsConfigStep, file: JString}` and `TlsResource {at: TlsResourceAt, errno}` emit the legacy line for the step. `file` is for the log line only; proto's `Config` keeps no file names. The driver fills `file` from the connector's own copy of the path (`TlsConnector::path_for`, §8.2.1); proto never opens a file. | 02 §4, §4.3; 06 §3.8 |
| DR4 | `handle_rw_probe(ep: Endpoint, outcome: ProbeOutcome {ReadWrite, NotReadWrite, Failed(Option<ProbeTlsFailure>)}, now)`, with `ProbeTlsFailure::Config {step, file}` for a probe config failure, so proto emits the line C logs on the probe path (`fail_on_error = 0` still logs). A probe failure never tears down the main connection. | 02 §4, §4.3, §9.5; 06 §3.8; zookeeper.c:2461-2522, 2876-2975 |
| DR5 | The `getaddrinfo: %s` ERROR line is emitted by proto, one per failing host entry in host order, emitted as each host's result is passed in (`handle_host_resolved`, §4.1 step 3), at the platform's legacy site (`Win32 message: %s\n` on Windows), with a `GAI_STR` argument that C renders per platform as zookeeper.c:982-988 (02 §3.3). | 02 §9.4; CC-5 (00 §4) |

**Driver events and the proto calls they make.**

| Driver event | Proto call | rc and effect (LEGACY_C) | C reference |
|---|---|---|---|
| `socket()` fails | `handle_transport_failed(SocketCreate)` | ZSYSTEMERROR | 2681-2689 |
| `connect()` error other than EINPROGRESS/EWOULDBLOCK | `TcpRefused` | ZCONNECTIONLOSS teardown | 2706-2711 |
| `connect()` EINPROGRESS | `handle_connect_started(InProgress)` | state 1, or 7 with TLS | 2700-2705 |
| `connect()` returns 0, no TLS | `handle_connect_started(Immediate)` + `handle_tcp_connected` + `handle_transport_ready`, in one lock hold | state 1 never visible | 2713-2734 |
| `connect()` returns 0, TLS | `handle_connect_started(Immediate)`; then `new_conn` with no lock; then `handle_tcp_connected` on success (§4.3.1) | state stays 0/999 | 2713-2722 |
| SO_ERROR != 0 on WRITE/HUP | `TcpRefused` | ZCONNECTIONLOSS teardown ("server refused to accept the client") | 3079-3110 |
| SO_ERROR == 0 | without TLS `handle_tcp_connected` + `handle_transport_ready`; with TLS `new_conn`, then `handle_tcp_connected` on success | | 3099-3115 |
| `new_conn` → `Config{step}` | `TlsConfig{step, file}` | ZSSLCONNECTIONERROR, EINVAL, parked | 2876-2918 |
| `new_conn` → `Resource{at}` | `TlsResource{at, errno}` | ZSSLCONNECTIONERROR teardown | 2866-2873, 2919-2932 |
| Fatal TLS error, EOF or recv error during the handshake | `TlsHandshake{errno}` | ZSSLCONNECTIONERROR teardown ("error in ssl connect") | 2946-2959 |
| Handshake complete | `handle_transport_ready` | state 2, ConnectRequest queued and flushed in the same call | 2831-2841, 2362-2391 |
| EOF, recv error, close_notify, alert or TLS record error in the data phase | `Eof` / `Io`, after the plaintext that came before it is processed (§4.3.3) | ZCONNECTIONLOSS teardown | 3133-3137, 403-419 |
| send error | `Io` | ZCONNECTIONLOSS teardown ("failed while flushing send queue") | 3120-3125 |
| poll failure in the close flush | `Poll` | ZSYSTEMERROR (close rc only) | CLS-06 |

The `errno` in each `TransportFailure` variant is the value that the legacy `handle_socket_error_msg` line prints. The driver passes the OS error of the last failed socket call on that connection, or 0 (02 §4). The TLS layer never sets errno.

---

## 3. The outbox model

### 3.1 Items and step results

```rust
// zk-driver/src/outbox.rs
/// Work that only C may do. zk-ffi turns each item into one zkrs_item.
pub enum Item<T: Tokens> {
    Log(LogRecord),                                        // emit now, on this thread
    Result { token: T::Completion, result: OpResult, delivery_logs: Vec<LogRecord> },   // async (st) or sync (Direct)
    AuthResult { token: T::Auth, rc: i32 },                // zoo_add_auth completion
    Watch { event: WatchedEvent, watchers: Vec<T::Watcher>, delivery_logs: Vec<LogRecord> },   // node event
    Session { state: State, watchers: Vec<T::Watcher>, delivery_logs: Vec<LogRecord> },        // session event; path "" added by C
    WatchTerminated { watchers: Vec<T::Watcher>, reason: TerminateReason },                    // native only; never reaches zk-ffi
    AuthLockpoint,                                         // C: lock, then unlock zh->auth_lock
    Sasl(SaslItem),                                        // Cyrus work; C answers START/STEP with zkrs_sasl_result
    Thread { op: ThreadOp, role: ThreadRole },             // Join | Detach  x  Io | Dispatch
}
/// One zk-proto SASL call, plus the addresses that START needs.
pub struct SaslItem {
    pub call: zk_proto::SaslCall,                       // {gen: u32, kind: Start | Step(Option<SecretBytes>) | Dispose}
    pub addrs: Option<(SocketAddr, SocketAddr)>,        // START only: (local, peer) from rustix getsockname/getpeername;
                                                        // zk-ffi writes each as a sockaddr_in/sockaddr_in6 image (05 §19.2)
}
pub enum Step { Done(i32), More, Release, Exit }          // ZKRS_DONE / MORE / RELEASE / EXIT
```

`SaslCallKind::Step(None)` is the server's -1 token (SAS-10).

| Driver item | `zkrs_item` kind (CC-2 (00 §4)) | Barrier (step returns `ZKRS_MORE` right after it) |
|---|---|---|
| `Log` | LOG (zk-ffi stamps wall-clock time when it pumps the record, CC-30 (00 §4)) | no |
| `Result` | RESULT; the `zks_req` mode tells async from sync | sync (Direct) results in the mt IO step and in close (CC-18 (00 §4)); a run of consecutive Direct results with no callback item between them shares one barrier, after the last of them |
| `AuthResult` | RESULT; `zks_req` mode "auth" | after the last item of each auth group |
| `Watch`, `Session` | WATCH (node or session) | st `Session` (`DrainPoint` semantics) |
| `WatchTerminated` | none: native only, never reaches zk-ffi | no |
| `AuthLockpoint` | AUTH_LOCKPOINT | always |
| `Sasl` | SASL{START, STEP, DISPOSE} | START and STEP. DISPOSE is not a barrier; the outbox puts it immediately before a reconnect's START, or after the transport close at destroy (§5.7). |
| `Thread` | THREAD{JOIN\|DETACH, IO\|DISPATCH} | always: every THREAD item (JOIN or DETACH, IO or DISPATCH) |

zk-ffi carries each item's `delivery_logs` in its RESULT or WATCH view, and C emits them on the delivering thread before it invokes the callbacks (02 §5.1).

This barrier set is c-shim's (05 §5).

CC-2's other mappings (00 §4): the final close returns `ZKRS_RELEASE`, a dropped token goes to the `discard_token` leaf (§9), and resolution is the Blocking leaf `resolve` (§8.5).

- **The C side.** zk-ffi stores the outbox behind the `zkrs_outbox` handle on the C stack. Every view an item exposes (result bodies, watcher lists, log arguments, SASL tokens and the SASL address images) is owned by the outbox and stays valid until the next `zkrs_outbox_next`, `zkrs_outbox_finish` or `zkrs_outbox_requeue` on it. C copies anything it hands to application code (05 §9). `zkrs_outbox_finish` resets the handle to empty (`impl` 0), so a C loop passes the same stack handle to its next `zkrs_*` call (05 §5).
- **The native side.** The IO side of the native drivers routes the same `Item`s itself, in order, into zk-client's slots, per-watcher queues and the session state, under the bounded hand-off of §7. No C is involved, and `Thread`, `AuthLockpoint` and `Sasl` never occur. `WatchTerminated` is native only and has no `zkrs_item` kind. zk-ffi never receives it, because `session_fanout_to_path_watchers` is true under `LEGACY_C` and the core then never emits it (02 §10.4); the native side routes it into the terminal `WatchError` of each sink (04 §4).

The C drain loop is c-shim's (`shim/zks_drain.c`; 05 owns the exact structs):

```c
/* Runs every item in C frames. No Rust frame is below a callback. */
void zks_drain(zhandle_t *zh, zkrs_outbox *ob)
{
    zkrs_outbox *guard __attribute__((cleanup(zks_outbox_unwind))) = ob;   /* requeue on unwind */
    zkrs_item it;
    ZKS_ASSERT_NOT_IN_RUST();
    while (ZKS_CALL(zkrs_outbox_next(ob, &it))) {
        switch (it.type) {
        case ZKRS_ITEM_LOG:            zks_log_emit(zh, it.log); break;
        case ZKRS_ITEM_RESULT:         zks_deliver_result(zh, it.token, it.result); break; /* async, sync or auth */
        case ZKRS_ITEM_WATCH:          zks_deliver_watch(zh, it.watch); break;            /* node or session */
        case ZKRS_ITEM_AUTH_LOCKPOINT: zks_auth_lockpoint(zh); break;
#ifdef ZKS_WITH_SASL
        case ZKRS_ITEM_SASL:           zks_sasl_item(zh, it.sasl); break;   /* calls zkrs_sasl_result */
#endif
#ifdef THREADED
        case ZKRS_ITEM_THREAD:         zks_thread_item(zh, it.thread_op, it.thread_role); break; /* join or detach */
#endif
        }
    }
    guard = NULL;
    ZKS_CALL(zkrs_outbox_finish(ob));
}
```

THREAD items exist only in mt, so the THREAD case is compiled only under `THREADED`. `zks_thread_item` is declared in `zks_internal.h` and defined (not static) in `zks_threads.c`. The SASL case is compiled only under `ZKS_WITH_SASL` (05 §19.2).

`zks_drive` loops `zks_drain` and `zkrs_resume` while the step is `ZKRS_MORE`, and calls `zks_release` on `ZKRS_RELEASE` (05 §5). `zookeeper_interest` runs the same loop over `zkrs_interest_resume` (§6.2). The DEBUG lines `Calling COMPLETION_<kind> for xid=%#x failed=%d rc=%d` (zookeeper.c:3343-3441), with the per-sub-op lines of a multi, are the first `delivery_logs` records of each async RESULT (02 §5.1), and the dispatch C code emits them right before it invokes the completion. They therefore carry the dispatch thread's `pthread_self()` and appear on the same thread and at the same point as in C. A sync RESULT carries "Processing sync_completion with type=%d xid=%#x rc=%d" (zookeeper.c:5425) the same way, emitted on the IO thread.

### 3.2 What runs where

| Entry (C caller) | Step results | Items C may see |
|---|---|---|
| `zks_io_main` → `zkrs_io_step(core, &ob, &rc)` (mt IO thread) | DONE, MORE, EXIT | LOG, RESULT (sync; auth), AUTH_LOCKPOINT, SASL. Async results and events are published to the dispatch FIFO (§3.4). |
| `zkrs_thread_exit(core, IO\|DISPATCH, &ob, &rc)` (end of a library thread) | DONE, MORE, RELEASE | when this thread runs the final close: LOG, RESULT (auth ZCLOSING), AUTH_LOCKPOINT, SASL DISPOSE |
| `zks_dispatch_main` → `zkrs_wait_dispatch(core, &ob, &rc)` (mt dispatch thread) | DONE with a batch, EXIT | RESULT (async), WATCH (node and session), in FIFO order |
| `zookeeper_interest` / `zookeeper_process` (st) | DONE, MORE, RELEASE | every kind except THREAD |
| `zookeeper_close` (both flavors) | DONE, MORE, RELEASE | RESULT (sync and auth ZCLOSING; st async), AUTH_LOCKPOINT, THREAD (mt), LOG, SASL DISPOSE; st also WATCH |
| `zoo_a*`, `zoo_add_auth` | a `zkrs_status`, plus an outbox | LOG only. A forced close inside the call (the st `zoo_a*` flush failure) sets state 999, closes the transport and takes the pending work as its snapshot, the request of the call included. It emits no SASL item, because a live Cyrus session is disposed at the next START or at destroy (DR2). Its event and the snapshot's results come from the IO owner's next driving call (§6.5, 02 §12.3), so no callback runs in these calls. A later `zoo_a*` or `zoo_add_auth` stays pending for the next connection and triggers nothing, because no transport exists. |
| `zoo_set_servers` (`zkrs_set_servers`), `zookeeper_init*` (`zkrs_handle_new`) | DONE, MORE (log flush before each resolve and the reseed; init also the SASL point, 05 §5, §15.1); `*rc` is the legacy rc or errno | LOG only. A `zoo_set_servers` rebalance that requires a move is a forced close with the same effects as in the row above, except that in mt it only marks the transport for the IO thread (P-7), so no callback runs in these calls. |

At the default INFO level, `zkrs_submit` produces no items, so the outbox stays empty and the hot path costs nothing. The DEBUG line `"Sending request xid=%#x for path [%s] to %s"` (zookeeper.c:4202) is a LOG item of the app thread's outbox, emitted by C after `zkrs_submit` returns, at the same thread and position as in C.

### 3.3 Barriers

The step returns `ZKRS_MORE` right after a barrier item. The continuation is not computed until C calls `zkrs_resume` (`zkrs_interest_resume` for a suspended `zkrs_interest`). The barriers are (05 §5):

- each run of sync (Direct) RESULTs in the mt IO step and in close, so every waiter is signalled before later frames are processed, as C does (zookeeper.c:3637-3656). A run of consecutive Direct results with no callback item between them shares one barrier, after the last result of the run (02 §5.3, 05 §5). The order is the same, because nothing observable runs between them;
- each auth group (the last `AuthResult` of the group), in every flavor;
- `AuthLockpoint`;
- SASL START and STEP items. C answers each with `zkrs_sasl_result` before it resumes, except for an item that the glue rejects as stale (status STALE, no Cyrus call), which gets no `zkrs_sasl_result` (05 §19.2). For such an item, and for one whose callback threw, zk-ffi hands the core a stale result after the receive-timeout teardown, so the next connection's DISPOSE and START are not delayed (05 §5, DR2). DISPOSE is not a barrier: the outbox places it immediately before a reconnect's START, or, in the final close, after the transport close and before `ZKRS_RELEASE`;
- st `Session` events and `DrainPoint`s, after the local FIFO has been moved into the outbox (§6.4);
- every `Thread` item (JOIN or DETACH, IO or DISPATCH), so the code after it (for example the final close on this thread) runs after C has joined or detached;
- **before blocking** (CF5): a step that would block in `poll`, in the close flush or close wait, or in the RW probe, or that is about to call a Blocking vtable leaf (`resolve`, `rng_reseed`) or to read TLS files in `new_conn` (§4.3.1, §8.4), first returns `ZKRS_MORE` if LOG items are pending in its outbox. C drains them, so pending LOG items reach the log callback from a C frame, and the blocking work runs inside the following `zkrs_resume` (`zkrs_interest_resume` for a suspended `zkrs_interest`). Log lines therefore come out on the same thread, in the same order and before any wait, as in C. The cost is one extra round trip, only on those paths and only when LOG items are pending.

### 3.4 Per-call outbox and FIFO publication

- Each driving call owns its outbox. Whenever the call runs proto under the core lock, it pumps every output that proto call produced into its outbox, then releases the lock. Outputs belong to the call whose proto call produced them. A closer never takes the IO thread's teardown items, and the IO thread never takes the closer's.
- **FIFO-bound outputs** (`Completion{route: Queued}`, `Watch`, `Session`) in mt are stored in the outbox as publish entries. When `zkrs_outbox_next` reaches one, Rust appends it to the dispatch FIFO under the FIFO leaf lock, notifies the condvar, and continues to the next entry. C never sees publish entries. A log line that precedes the output in proto order has therefore already been emitted by C on this thread when the dispatch thread can see the output. In st these outputs go to the handle-local FIFO, which is drained at drain points (§6.4).
- **Lock rule.** No step and no `zkrs_outbox_next` call returns with a lock held. Lock order: `zoo_lock_auth` (C only) → `io` (the IO-owner lock around `IoCore`, §5.1) → `core` → `fifo`. The dispatch thread takes only `fifo`. Rust never takes `auth_lock`. `zoo_add_auth` holds `auth_lock` around `zkrs_add_auth` (CC-7 (00 §4)).
- **Blocking leaves.** `resolve` and `rng_reseed` take the `&Unlocked` proof (K4), so they are called with no Rust lock held. `TlsConnector::new_conn` takes the same proof (06 §3.2). Each of these calls is preceded by the before-blocking barrier of §3.3 when LOG items are pending in the outbox.

### 3.5 Ordering guarantees

1. **Within one thread, exact C order.** A log line that comes before an item in proto order is emitted (by C, on this thread) before the item becomes visible to the dispatch thread. The IO thread's "session established" INFO line therefore comes before the dispatch thread's CONNECTED watcher. Sync results and auth completions run at their proto position on the producing thread.
2. **Between concurrent producers** (the IO thread's outbox and a closer's outbox), publication order is the order in which each thread takes its entries. The only interleaving this allows is a reply popped by the IO thread landing after the ZCLOSING fakes of a concurrent close. Ledger DSP-09 shows C can produce that order too, so it is in C's envelope (07 P1). No reservation slots are used, so no thread waits for another thread's undrained outbox, and a close from a callback cannot deadlock on ordering.
3. **mt sync-before-FIFO.** Direct results are RESULT barrier items on the IO thread at their proto position. They never pass through the FIFO, so a sync call can return before the watcher it triggered has run (zookeeper.c:3637-3656). Sync calls from dispatch callbacks keep working.

### 3.6 Nesting and unwinding

- An item handler (application code) may re-enter the API on the same thread, including `zookeeper_close`. That creates a nested outbox while the outer one is between items. No Rust lock is held, and the outer suspended continuation resumes only after the inner call returns. This is the re-entrancy C has, minus the internal locks C held (DSP-13, DRV-4).
- **C++ exceptions** (CC-32 (00 §4)). If a callback throws, the cleanup handler calls `zkrs_outbox_requeue`: the untaken items go back to the handle, together with any suspended continuation. The next driving call on that handle (interest, process, close, io_step) first delivers those items and completes the continuation, then does its own work. Legacy leaves the rest of its queue queued in the same way. If the throw came from a Cyrus callback during an st SASL START or STEP, no result exists for that generation: frame processing stays paused (P-9), the receive timeout tears the connection down, and zk-ffi then passes the stale result of 05 §5, so the next prime response yields DISPOSE and START as legacy `zoo_sasl_connect` does (zk_sasl.c:215-217). On a library thread an exception terminates the process before any requeue matters, as legacy.

### 3.7 Native drivers

The native threaded driver runs `loop { let ob = shared.io_step(); for item in ob { sink.route(item) } }` on its own std thread, with `DispatchMode::IoThread`: no dispatch thread and no drop-in FIFO. `sink` is `Shared::sink` (§5.1), a `RouteSink<T>`: zk-driver defines the trait (`pub trait RouteSink<T: Tokens>: Send + Sync { fn route(&self, item: Item<T>); }`) and zk-client implements it (04 §5.1). After it releases the core lock, the IO thread routes the items in outbox order itself into slots, per-watcher queues and the session state (04 §4). Routing never blocks the IO thread; when the undelivered-item count reaches the capacity, the read gate closes instead (§7). Resolution goes through the zk-client `Resolve` adapter (default std `ToSocketAddrs`, 04 §3), run inline; SASL is the native mechanism in zk-client. The tokio driver does the same inside its task, with the same capacity, and runs resolution, SASL and the RW probe through `spawn_blocking`.

---

## 4. IoCore: the shared step functions

```rust
// zk-driver/src/iocore.rs
pub struct IoCore {
    conn: Transport,
    rx: Box<[u8]>,            // heap read buffer, 64 KiB; st plaintext reads read_hint() bytes into it
    rx_pending: Vec<u8>,      // received plaintext that proto has not consumed yet (barrier, SASL wait)
    tx_copy: Vec<u8>,         // heap copy of the pinned head bytes (mt, native, TLS)
    rx_stalled: bool,         // negative frame length seen (WIRE-02)
}
enum Transport {
    None,
    Connecting(TcpSock),      // TCP connect in progress (state 1, or 7 with TLS)
    Plain(TcpSock),
    Tls(TlsTransport),        // handshake or data phase
    Parked(TcpSock),          // TLS config failed: socket kept, no session (CC-14 (00 §4))
}
pub struct InterestOut { pub rc: i32, pub written: bool, pub fd: RawSock /* -1 */, pub bits: u32, pub timeout_ms: i64 }
```

`InterestOut` maps to c-shim's `zkrs_interest(h, &zkrs_interest_out{fd, interest, timeout_ms}, &written, ob, &rc)` or, after a barrier, to `zkrs_interest_resume` with the same arguments. The call that reaches `finish_interest` writes them (05 §6).

**Buffered readiness.** `IoCore::buffered_ready()` is true when `rx_pending` is not empty, the decoder is not stalled, and proto is not suspended. It can be true only for TLS and for mt/native plaintext, because st plaintext reads exactly `read_hint()` bytes. When it is true, interest reports `tv = 0`, the mt IO step polls with timeout 0, and `process_step` treats the call as READ-ready even when `events` lacks READ (§4.3.3).

### 4.1 `interest_step(now)` (the body of zookeeper_interest, zookeeper.c:2618-2824)

1. Deliver requeued items and complete a pending resume (§3.6).
2. Lock. `begin_interest(now)`. On `Err(ZINVALIDSTATE)` return `{rc, written: false}`. **O1**: outputs untouched, as in C (2633-2634). Otherwise call `run_pending_teardown(now)`: the remaining stages of a forced close run here (02 §12.3). The driver drops a socket that proto has condemned before it pumps their outputs, so the socket is closed before any callback runs, as `cleanup` closes first (2103).
3. Run `poll_action(now)` until it returns `None`. Actions come in C order:
   - `Resolve{gen, hosts, initial}` (`gen: u64`, the resolver generation): a per-host loop. For each host entry in order: return `ZKRS_MORE` if LOG items are pending in the outbox (§3.3); unlock; call the `resolve` leaf for that entry (Blocking, no Rust lock, §8.5); relock; `handle_host_resolved(gen, r, now)`, which emits that host's lines (DR5). After the last host: if shuffle is on (`!conn_permute_disabled()`) and at least one address resolved, return `ZKRS_MORE` if LOG items are pending, then unlock, call the `rng_reseed` leaf and relock (02 §3.3; legacy calls `setup_random` only after at least one address resolved and only with permutation enabled, zookeeper.c:1028-1037). Then `handle_resolved(gen, shuffle, now)`, and then `run_pending_teardown(now)`, because a rebalance in `handle_resolved` is a forced close (02 §9.3, §12.3), whose stages run here on this path. So each host's failure line reaches the log callback before the next host's `getaddrinfo` and before `srandom`/`srand48`, as in C (zookeeper.c:985-987). For the initial resolution of a host string whose entry k has a port error (`HostParseError.entry = Some(k)`, 02 §11), the loop resolves the prefix, entries 0..k-1, in the same way and then fails the init with that error: its "no port in %s" or "invalid port in %s" line comes after their lines, no reseed runs, and the errno is EINVAL, as legacy `resolve_hosts` parses each port inside its loop (zookeeper.c:870-889).
   - `Connect{endpoint, tls}`: `TcpSock::connect` (non-blocking) and the proto calls of §2. With TLS on an immediate connect, start the handshake now (§4.3.1). Skipped when `close_requested` is set (mt).
   - `CloseTransport`: drop the transport now (this thread is the IO owner). This is a teardown, so a TLS session is dropped without close_notify (§4.3.5). No SASL item follows; a live Cyrus session stays until the next START or destroy (DR2).
   - `Sasl(call)` with `kind = Dispose`: emit the `Sasl` item and go on. Here proto yields it only immediately before the `Sasl(Start)` of a reconnect (DR2), so C disposes the old Cyrus connection and then runs START in the same drain, as legacy `zoo_sasl_connect` disposes at its top (zk_sasl.c:215-217).
   - `ProbeRw{endpoint, tls}`: return `ZKRS_MORE` first if the outbox holds items; then run the bounded probe (§8.4) with no core lock; relock; `handle_rw_probe(ep, outcome, now)`. A switch to a read/write server then runs the `RwServerFound` teardown in this step (02 §12.2).
   - `Sasl(call)` with `kind = Start | Step`: emit a `Sasl` item (§5.7; START carries the local and peer addresses) and return `ZKRS_MORE`. `zkrs_interest_resume` continues the action loop (`zkrs_resume` in the mt IO step, which has no interest outputs).
4. `finish_interest(now)` gives `InterestOutcome {rc, outputs}`. With `outputs == None` the shim writes nothing (`written = false`). Otherwise the driver adjusts the proto values:
   - OR in WRITE while the TLS session or `tx_copy` holds ciphertext or plaintext not yet on the wire, or while the TCP connect is in progress;
   - drop READ while `rx_stalled`;
   - `tv = 0` while `buffered_ready()` (TLS-04);
   - `fd` is the transport's fd read after any close in this call, so it is -1 when the socket was closed here (DRV-7).

   On the immediate path, a TLS config failure in this call reproduces what C writes before its early return: `fd = -1, interest = 0, tv = 0` (zookeeper.c:2650-2653, 2717-2720).
5. Outputs were pumped into the outbox as they were produced. Application code runs when C drains, after the step returns. In st this happens inside the same `zookeeper_interest` call (§6).

**O1 (CC-22 (00 §4)).** When C returns before writing `*fd`, `*interest` and `*tv` (unrecoverable state; whole-list resolution failure with no socket), the outputs stay untouched, because C's early return leaves the application's own previous values there. st loops that pre-set these values see C behavior. With a socket open, a DNS failure counts as "no change": timers, pings and interest run (SRV-06 FU), because this call may close the socket the application polls, and the outputs **are** written. The rc is the resolver rc unless a teardown occurs in the call. mt never uses these values; its IO loop handles the failure itself (DRV-15).

### 4.2 `process_step(events, now)` (the body of zookeeper_process + check_events, zookeeper.c:3530-3667, 3073-3212)

1. Deliver requeued items and complete a pending resume (§3.6).
2. Lock. If unrecoverable, return ZINVALIDSTATE and dispatch nothing (3537-3538). Call `run_pending_teardown(now)` as in §4.1 step 2: if the driver still holds a socket that proto has condemned (an mt forced close requested by an app thread), it closes it first, and the outputs of the remaining stages are pumped. If `!has_transport()`, return ZINVALIDSTATE, as C's `sock == -1` check does (3075-3076). In mt an app thread can condemn the socket while this step is sending without the lock, so the step checks `has_transport()` after every relock and returns ZINVALIDSTATE if it is false, reading nothing more from that socket; the next step runs the stages.
3. **TCP connecting, and WRITE or HUP:** read `SO_ERROR`. On error → `TcpRefused`, return its rc. On success without TLS → `handle_tcp_connected` + `handle_transport_ready`, then one non-blocking flush; **return ZOK without reading** (3099-3115). On success with TLS → start the handshake (§4.3.1) and return its rc.
4. **TLS handshake phase, any event:** §4.3.2. Return its rc.
5. **Parked (TLS config failed):** READ only. A recv EOF or error → `Eof`/`Io` teardown (ZCONNECTIONLOSS), as C's plain `recv` on that socket gives. Received bytes are discarded; a server never sends before the ClientHello (07 P6). WRITE never applies.
6. **WRITE, and sendable bytes exist:** one flush pass. Plaintext: loop `poll_transmit` (pins the head, P-3), copy, unlock, `send` until WouldBlock, relock, `handle_transmitted(n)`. TLS: §4.3.4. On error → `Io`; return that rc (3120-3125).
7. **READ (or `buffered_ready()` under TLS), and not `rx_stalled`:**
   - st plaintext reads exactly `read_hint()` bytes, as `recv_buffer` does (1932-2007), so at most one frame completes per call (DSP-17). mt and native plaintext read up to 64 KiB. TLS: §4.3.3.
   - Partial frame or WouldBlock → **ZNOTHING**, no dispatch (3205-3208).
   - EOF or error → `Eof`/`Io`.
   - Otherwise `handle_read(bytes)`. Proto may consume fewer bytes than offered; the rest stays in `rx_pending`. A negative length makes proto stall the decoder (`StallUntilTimeout`); the driver sets `rx_stalled`, drops READ, and lets the receive timeout give ZOPERATIONTIMEOUT without a spin (WIRE-02).
   - Proto stops at barriers (P-9). The step returns `ZKRS_MORE`; C drains; `zkrs_resume` re-enters this loop with `rx_pending`, all within the same `zookeeper_process` call.
8. The rc is `ReadOutcome.rc` (the teardown rc wins, except that a SASL failure gives the SASL rc, which `handle_sasl_*` also returns, 02 §7.4; else ZOK if a frame was processed in this call; else ZNOTHING) or the transport rc. It is returned in `*rc` with `ZKRS_DONE` after the last drain.

### 4.3 TLS in the I/O path

TLS uses the same sockets, the same `poll` loop and the same proto phases as plaintext. The only difference is that bytes pass through a `TlsConn` between the socket and proto. The driver owns the socket; the session never sees it.

```rust
// zk-driver/src/net/tls/mod.rs
pub struct TlsTransport {
    sock: TcpSock,
    tls: Box<dyn TlsConn>,           // RustlsConn in both profiles
    ct_out: Vec<u8>,                 // ciphertext taken from the session, not yet accepted by the kernel
    unacked: usize,                  // plaintext given to the session; its ciphertext is not fully sent
    pending: Option<ReadFailure>,    // latched TLS failure or recv error; acted on after the frame loop
}
enum ReadFailure { Closed, Data, Io }
```

#### 4.3.1 Handshake start

The handshake starts when TCP is established: in `process_step` on the EINPROGRESS path (state 7, `SO_ERROR == 0`), or in `interest_step` on the immediate path (state stays 0/999, P-5). On TCP-connect completion the driver calls `new_conn` first and then `handle_tcp_connected`, so proto hears of TCP completion only when a session exists. The phases are 06 §3.8's.

```text
handshake_start(ep):                                     // TCP is established
  now = clock.now()                                      // deadline origin: TCP completion; kept across a resume
  if LOG items are pending: return ZKRS_MORE             // logs drained before the file reads (§3.3)
  match connector.new_conn(&unlocked, TlsPurpose::Main, &ep)        // no driver lock; the drop-in reads the files here (TLS-02)
    Ok(s)             -> lock; conn.handle_tcp_connected(now); unlock   // deadline = now + 2/3·T (ST-16)
                         transport = Tls{s}; take ClientHello into ct_out; send (WouldBlock keeps WRITE)
    Err(Config{step}) -> lock; rc = handle_transport_failed(TlsConfig{step, file: jstring(connector.path_for(step))})
                         unlock; transport = Parked
    Err(Resource{at}) -> lock; rc = handle_transport_failed(TlsResource{at, errno}); unlock   // teardown
  return rc (ZOK when the session was created)
```

The verifier takes `now` from the rustls time provider of the `ClientConfig`. In production that is the system wall clock. Tests inject a fixed provider. So `new_conn` takes no time argument. `jstring` turns the `Option<&[u8]>` of `path_for` into proto's `JString`; `None` becomes `JString::Null`.

- In the drop-in, `new_conn` builds a fresh `ClientConfig` for every attempt, so the CA file, the default verify locations, the client chain and the key are re-read each time, as legacy builds a fresh `SSL_CTX` per attempt (TLS-02); natively, `ReloadPolicy` decides (§7). §8.2.3 lists the steps.
- A config failure gives ZSSLCONNECTIONERROR with errno EINVAL once, and parks the attempt (P-6, CC-14 (00 §4)). The parked socket has READ interest only, so there is no retry and no spin. The receive rule then fails over with ZOPERATIONTIMEOUT on both paths. On the immediate path no proto timestamp was reset before the failure, so C's un-reset `last_recv` drives the deadline (02 §7.2).
- The file reads run with no driver lock held, in mt and st alike.

#### 4.3.2 Handshake steps

```text
handshake_step(events):                                   // any process call in the handshake phase
  if READ or HUP:
      n = sock.recv(rx)            // EOF -> tls.feed_eof(); error -> TlsHandshake{errno} (ZSSLCONNECTIONERROR, teardown)
      tls.feed(&rx[..n])           // verify failure or fatal alert -> alert_attempt() (§4.3.5),
                                   //   then TlsHandshake{errno}
  pump_out()                       // WouldBlock keeps WRITE interest
  if !tls.is_handshaking():
      lock; conn.handle_transport_ready(now); unlock      // state 2; ConnectRequest queued
      flush (§4.3.4)                                      // ConnectRequest sent in this call
      plaintext and frame steps of §4.3.3                 // bytes that came with the last flight
  return ZOK                                              // st: ZOK for every handshake step
```

- Interest during the handshake: READ while the socket is open; WRITE while `ct_out` or the session holds ciphertext; `tv` is never later than the handshake deadline (proto `tls_left`). No `select`, no `FD_SET`, so the FD_SETSIZE hazard is gone (ST-16, DRV-10).
- The handshake deadline (2/3·T from TCP completion) is a proto timer, checked in `interest_step`. Expiry tears down with ZOPERATIONTIMEOUT, errno ETIMEDOUT.
- Proto picks the legacy "initiated connection" / "Initiated connection" INFO text from the `ConnectStart` of the attempt when `handle_transport_ready` runs.
- In mt, the handshake runs on the IO thread, one flight per wake, and a close wakes the thread between flights. In C it was a blocking `SSL_connect`/`select(1 s)` loop with no deadline (2937-2975).

#### 4.3.3 Data-phase reads and TLS-04

```text
tls_read(events):
  if READ:
      n = sock.recv(rx)                         // up to 64 KiB of ciphertext, every flavor
      n == 0 -> tls.feed_eof()                  // Closed is latched in the session
      error  -> pending = Some(Io)              // acted on after the frame loop
      n > 0  -> tls.feed(&rx[..n])              // a failure is latched; plaintext before it is kept
                                                // Err: alert_attempt() (§4.3.5), the fatal alert rustls queued
  if pending.is_none(): pending = take_plaintext()
  frames():                                     // resume re-enters here
      while !rx_pending.is_empty() && !rx_stalled:
          out = conn.handle_read(&rx_pending, now); rx_pending.drain(..out.consumed)
          if a barrier is pending: return ZKRS_MORE
          if out.consumed == 0: break           // partial frame, decoder stall or SASL wait
  if a SASL Start/Step is outstanding: return   // pending stays; act on it after the result
  if proto still has the transport:
      match pending { Closed -> Eof, Data | Io -> Io, None -> () }
  if tls.has_ciphertext(): pump_out()           // alerts, key updates

take_plaintext():                               // gives the latched failure, if any
  match tls.read_plaintext(&mut rx_pending)
      Err(f) -> Some(f)                         // no plaintext was before it
      Ok(_)  -> tls.read_plaintext(&mut rx_pending).err()   // the next call gives the latch
```

- **Plaintext before a failure.** `feed` never discards plaintext (§8.2.1). The frame loop runs over every byte decrypted before close_notify, an alert, a record error or a recv error, and only then does the driver act on the failure. Legacy `SSL_read` returns the decrypted data first (zookeeper.c:403-419). So a server's expiry ConnectResponse followed by close_notify in one read gives ZSESSIONEXPIRED (3160-3179), as in C, not ZCONNECTIONLOSS; and a final reply followed by an alert is delivered before the ZCONNECTIONLOSS teardown. If processing a frame already tore the connection down (expiry, xid mismatch), the latched failure is dropped with the transport.
- **Drain-all (TLS-04 FU, DRV-11).** One READ call dispatches every frame that is complete in the plaintext produced by its ciphertext read, each followed by its drain, with barriers honored. In legacy, decrypted bytes could stay inside the `SSL` object with no socket readiness to wake the loop (no `SSL_pending` check, zookeeper.c:403-419), which gave a spurious ZOPERATIONTIMEOUT. The visible change is that one st `zookeeper_process` call may dispatch more than one frame on a TLS connection (00 §5.2).
- **Buffered readiness.** If complete plaintext is still buffered when a call ends (only after an unwound drain, §3.6), `buffered_ready()` makes interest return `tv = 0` and the next `zookeeper_process` handle it even with `events == 0`. Without this an st loop that selects with `tv = 0` and gets no events would spin.
- **rc.** A read that yields no complete frame gives ZNOTHING, as C's `SSL_read` WANT_READ path did (EAGAIN → `recv_buffer` 0).
- Plaintext arrives in arbitrary record sizes. Frame decoding is proto's (`FrameDecoder`); record boundaries are not observable (07 P6).

#### 4.3.4 Data-phase writes and the acknowledgment rule

```text
tls_flush():
  loop:
    if pending.is_some(): break                   // a failure is latched: no more session output (§4.3.5)
    if unacked > 0:
        if !pump_out(): break                     // WouldBlock: wait for WRITE
        lock; conn.handle_transmitted(unacked, now); unlock; unacked = 0
    n = lock; conn.poll_transmit(&mut tx_copy, MAX_CHUNK); unlock    // pins the frames (P-3)
    if n == 0: break
    if tls.write_frame(&tx_copy[..n]).is_err(): break   // no take_ciphertext; the frames stay unsent (T3)
    tls.take_ciphertext(&mut ct_out); unacked = n
```

- **Acknowledgment rule.** Plaintext given to the session is acknowledged to proto only after all ciphertext the session produced for it has been accepted by the kernel. A frame is therefore complete (`last_send = now`) at the same point as in C, where `SSL_write` returned only when the record was written to the socket. A forced close or teardown while `unacked > 0` leaves those frames unsent in proto, and T3 fails their requests, so none of those frames goes onto the next connection (02 §12.1, §12.3).
- At most one `poll_transmit` chunk is inside the session at a time, so session memory stays bounded.
- One `write_frame` call per chunk. rustls takes the whole chunk in one call and keeps no partial-record retry state, so the legacy two-`SSL_write` prefix retry hazard (TLS-03) cannot occur. rustls reports errors as values, with no error queue, so the legacy error-queue handling (TLS-05) has no driver counterpart (06 §3.2).
- `pump_out()` sends `ct_out` with `TcpSock::send`, which uses `MSG_NOSIGNAL` or `SO_NOSIGPIPE` (§8.1). No TLS path can raise SIGPIPE (GLB-01, DRV-8).
- The close flush (§5.10) uses the same path with blocking-with-deadline sends.

#### 4.3.5 Teardown, forced close and release

- **Graceful close.** In both profiles, a final close that sent CLOSE_OP sends `close_notify`. When the close flush (§5.10 step 4) has put every queued byte on the wire, the driver calls `send_close_notify()` after the POLLIN wait, whatever that wait's outcome, and sends the alert record with non-blocking sends, polling for WRITE only within the remaining close budget (at least one send attempt). It never waits for the peer's `close_notify`. A send failure is ignored (the send is `MSG_NOSIGNAL`/`SO_NOSIGPIPE`, §8.1). The socket is then closed at the legacy point (§5.10 step 7). If the close flush failed or timed out, nothing more is sent. The native close path (04) uses the same rule. Legacy `close_zsock` calls `SSL_free` with no `SSL_shutdown` (zookeeper.c:648-670), so legacy sends no close_notify. The extra record is a wire-only difference that applications cannot observe (TLS-06, 07 P6), and the 08 wire-trace comparisons allow it.
- Teardown, forced close, a final close with no CLOSE_OP and the RW probe (§8.4) drop the `TlsTransport` with no `send_close_notify`, and the fd is closed by the IO owner.
- **Fatal alert after a TLS error (`alert_attempt()`, both profiles).** When rustls detects a fatal error itself (a verify failure, a bad record, a protocol violation), it queues a fatal alert, and `feed` returns the latched failure. If `ct_out` is empty, the driver moves the alert out with `take_ciphertext` and makes one non-blocking send (`MSG_NOSIGNAL`/`SO_NOSIGPIPE`, §8.1). It ignores the result, discards any unsent remainder and never waits for WRITE. If earlier ciphertext is still waiting in `ct_out`, the driver takes the session ciphertext into a scratch buffer and discards it, so the alert stays off the wire even when teardown waits for a barrier or a pending SASL result. In that window the session holds no ciphertext for `pump_out`, and `tls_flush` stops at the latched failure (§4.3.4). The rule is the same in every driver flavor, for the drop-in and the native API, in the handshake, in the data phase and on the RW probe. A received alert, a received close_notify and TCP EOF make rustls queue nothing, so nothing is sent. This is the legacy wire behavior: on the non-blocking legacy socket (zookeeper.c:2536-2538), OpenSSL writes its fatal alert inside `SSL_connect` or `SSL_read`, before the C code sees the error, when no record write is pending, and `SSL_free` in `close_zsock` (zookeeper.c:648-670) discards an alert that could not be written. 08 §5.1 confirms this OpenSSL 3.0 and 3.5 behavior before M3 work begins, against the releases frozen for M3. No forced or error teardown sends `close_notify`, and the RW probe never sends it.
- Only the IO owner drops a session. The `zoo_set_servers` forced close on an app thread only marks the transport for closing (P-7).

---

## 5. Threaded driver (libzookeeper_mt)

### 5.1 Structures

```rust
pub struct Shared<T: Tokens> {
    core: Mutex<Core<T>>,                 // Connection, requeued items, suspended continuation, peer-address cache
    io: Mutex<IoCore>,                    // IO-owner lock: the IO thread, or the final closer after the join
    fifo: Mutex<Fifo<T>>, fifo_cv: Condvar,
    waker: Waker,                         // created at handle creation (init-time errno on failure)
    life: Lifecycle,
    host: Arc<dyn HostLeaves>,            // zk-ffi: Pure and Blocking vtable leaves; native: the zk-client Resolve adapter (§7)
    tls: Option<Arc<dyn TlsConnector>>,   // RustlsConnector when the handle was made by zookeeper_init_ssl
    clock: Arc<dyn Clock>,
    poll: Arc<dyn Poll>,                  // RustixPoll; zk-ffi may inject its WSAPoll wrapper on Windows (§8.8)
    cfg: DriverConfig,                    // close_flush 3000 ms, close_wait 1500 ms, probe phase 1 s; native: dispatch_queue_capacity (§7)
    dispatch: DispatchMode,               // Thread (mt) | IoThread (native)
    sink: Option<Arc<dyn RouteSink<T>>>,  // native only: routing into slots, queues and state (§3.7)
}
struct Fifo<T: Tokens> { items: VecDeque<FifoItem<T>>, io_exited: bool }
struct Lifecycle { refs: AtomicU32, close_requested: AtomicBool, finalizing: AtomicBool, creator_pid: u32 }
```

- No `ThreadId`, `OnceLock` or start latch lives in Rust. The start latch is C's (`zks_latch`, 05 §15.2), and the thread role is a C argument (CC-4 (00 §4)).
- The `io` lock is taken only by the IO owner. It is never contended in normal running, and it is never held when a step returns to C. It is ordered after `auth_lock` and before `core`.
- In the drop-in, `HostLeaves` is the zk-ffi implementation of the final host vtable (CC-6 (00 §4)). **Pure:** `discard_token`, `snapshot_default_watcher`, `store_client_id`, `log_enabled`, `conn_permute_disabled`, `rng_random`, `rng_drand48`. **Blocking:** `rng_reseed`, `resolve`. No entry reaches application code or `log_message`. There are no TLS entries. In the native threaded driver, the implementation is the zk-client `Resolve` adapter (§7). Its `conn_permute_disabled` returns true for `ServerOrder::AsGiven` (02 §3.3), so the shared step passes `shuffle = false`; the tokio driver uses the same adapter.
- `zkrs_peer_addr` calls rustix `getpeername` on the driver's shared socket at each call (05 §17), as legacy does (zookeeper.c:1265-1275). Because the socket is shared, the fd cannot be closed under the call.

### 5.2 Init and start

1. `zks_init_common` callocs `zhandle_t`, emits `log_env` and "Initiating client connection" in C, then calls `zkrs_handle_new(&args, &zks_vtable, (uintptr_t)zh, &core, &ob, &err)`. Rust parses the host string (legacy rules in zk-proto; a chroot error fails here, before any resolution), copies the nullable TLS fields and `tls_env_allowed` into a `RustlsConnector` (no file is read at init: legacy reads them per attempt, TLS-02), runs the initial resolution through the `resolve` leaf (zookeeper.c:1450), resolving only the entries before the first port error when there is one and then failing (§4.1 step 3), runs the reseed when it is due, stops at the SASL point when SASL params were given (`ZKRS_MORE` with `*rc = ZKRS_INIT_SASL_POINT`; C then creates the SASL session with `zks_sasl_sess_new`, as legacy does after resolution, zookeeper.c:1472-1479, and a failure abandons the handle with ENOMEM), and then creates the waker (mt), as legacy creates its pipe in `adaptor_init` after resolution and the SASL client (zookeeper.c:1482; mt_adaptor.c:244). C stores `zh->core` as soon as `zkrs_handle_new` returns (before the first drain), drains and calls `zkrs_resume` while the step is `ZKRS_MORE` (the log flush before each resolve and before the reseed, and the SASL point, 05 §5, §15.1), then drains once more. On failure C frees and returns NULL with the errno Rust reported, abandoning the handle when one exists.
2. C initializes `auth_lock` (mt), then calls `zks_start_threads` (05 §15.1, §15.2): `zkrs_thread_enter(core, INIT)`, one `zkrs_handle_retain` per thread, the DEBUG line "starting threads..." emitted by C (mt_adaptor.c:221), `pthread_create(&zh->io_thread, NULL, zks_io_main, zh)`, then the dispatch thread. A `pthread_create` failure returns NULL with that errno (DRV-6 = INIT-12 = c-shim D13); every retained reference is dropped exactly once on that path.
3. Each library thread sets its C TLS role before it arrives at the three-party latch (IO, dispatch, init). Callbacks may already be running when init returns (C parity, mt_adaptor.c:189-207).

```c
static void *zks_io_main(void *arg)                      /* c-shim owns the final text */
{
    zhandle_t *zh = arg; const zkrs_handle *core = zh->core;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT; int rc = 0; zkrs_step s; log_callback_fn cb;
    zks_tls_zh = zh; zks_tls_role = ZKRS_ROLE_IO;        /* role before the latch */
    ZKS_CALL(zkrs_thread_enter(core, ZKRS_ROLE_IO));
    zks_latch_arrive_and_wait(&zh->start_latch);
    ZKS_LOG(zh, ZKS_SITE_IO_THREAD_STARTED);                 /* DEBUG, mt_adaptor.c:380 */
    do {
        s = ZKS_CALL(zkrs_io_step(core, &ob, &rc));
        while (s == ZKRS_MORE) { zks_drain(zh, &ob); s = ZKS_CALL(zkrs_resume(core, &ob, &rc)); }
        zks_drain(zh, &ob);
    } while (s != ZKRS_EXIT);
    cb = ZKS_LOAD(&zh->log_callback);                    /* captured before the epilog (mt_adaptor.c:475) */
    s = ZKS_CALL(zkrs_thread_exit(core, ZKRS_ROLE_IO, &ob, &rc));
    zks_drive(zh, core, s, &ob, &rc);                    /* may run the final close and release zh */
    ZKS_LOG_CB(cb, ZKS_SITE_IO_THREAD_TERMINATED);
    ZKS_CALL(zkrs_handle_release(core));
    return NULL;
}
```

`zks_dispatch_main` has the same shape with `zkrs_wait_dispatch`, `ZKS_LOG(zh, ZKS_SITE_COMPLETION_THREAD_STARTED)` right after the latch (mt_adaptor.c:492) and `ZKS_SITE_COMPLETION_THREAD_TERMINATED`. Rust never touches `zhandle_t` after it returns `ZKRS_RELEASE`. The three start lines ("starting threads...", "started IO thread", "started completion thread") are emitted by C, because `zkrs_thread_enter` has no outbox. The "IO thread terminated" and "completion thread terminated" DEBUG lines are emitted by C with the captured callback, as mt_adaptor.c:475-477 does.

### 5.3 Waker

```rust
pub struct Waker { pending: AtomicBool, rx: OwnedFd, tx: OwnedFd }
// rustix::pipe::pipe(): no CLOEXEC (mt_adaptor.c:244); both ends non-blocking
// (pipe_with(NONBLOCK) where available, else ioctl FIONBIO; the syscall choice is 07 P6).
impl Waker {
    /// Write one byte only on a false -> true change.
    pub fn wake(&self) { if !self.pending.swap(true, AcqRel) { let _ = rustix::io::write(&self.tx, &[1]); } }
    /// Empty the pipe, then allow the next wake.
    pub fn reset(&self) { let mut b = [0u8; 16];
        while matches!(rustix::io::read(&self.rx, &mut b), Ok(n) if n == b.len()) {}
        self.pending.store(false, Release); }   // then the IO thread locks core and reads state
}
```

- At most one byte is in flight, so the pipe never fills. C's EAGAIN → ZSYSTEMERROR → app-thread socket close path (mt_adaptor.c:337-353, zookeeper.c:4153-4161) cannot occur (DRV-1 = DSP-10).
- No wakeup is lost. If the IO thread's `store(false)` happens before the app's lock/unlock of `core`, then by mutex happens-before the app's `swap` reads false and writes a byte. Otherwise the IO thread sees the submission when it reads state under the lock.
- The fd count per handle (one pipe pair) is the same as legacy.
- If the pipe cannot be created, `zkrs_handle_new` emits the core ERROR site "Can't make a pipe %d" with the errno into its outbox (mt_adaptor.c:245; "Can't make a socket." on Windows, mt_adaptor.c:242, §8.8). On Windows the core first emits the legacy line of the failing pair step: "Error creating socket. %d" (mt_adaptor.c:134) or "%s. %d" (mt_adaptor.c:119, with the step texts of :147, :150, :156, :160), then "Can't make a socket." C drains them and returns NULL with that errno (05 §15.1).
- Wakes: every `submit`, `zoo_add_auth` (C does not wake; timing only, DSP-12), `zoo_set_servers` (forced close), and `close`.

### 5.4 One IO iteration (`zkrs_io_step`)

1. If `close_requested` → return `ZKRS_EXIT`. `zkrs_thread_exit(IO)` then sets `io_exited` (unless a close from this thread already did), notifies the dispatch thread, parks nothing (the `IoCore` stays in `io`) and runs the epilog (§5.8).
2. Take `io`. Run `interest_step(now)`. In mt, resolution runs only when proto yields `Resolve`, through the leaf, on the IO thread, as C runs getaddrinfo there.
3. If the outbox holds items, return `ZKRS_MORE` (CF5); the rest runs in `zkrs_resume`.
4. Compute the poll set from the outputs. On an early return (no outputs, for example a whole-list DNS failure), wait on the waker only, for `recv_timeout/60` (at least 1 ms). This replaces C polling uninitialized values (DRV-15 = SRV-06 mt part). While `buffered_ready()`, the timeout is 0.
5. `Poll::poll([waker, socket?], timeout)` (`RustixPoll` is `rustix::event::poll`; §8.8), with the timeout rounded **up** to whole ms. EINTR means no events. revents mapping: IN → READ; OUT|HUP → WRITE (mt_adaptor.c:403-404); ERR|NVAL → READ|WRITE, so the next recv or send reports the error and the attempt ends in a ZCONNECTIONLOSS teardown (on Windows in state 1 these bits, and HUP, are ignored, §8.8). C ignores ERR|NVAL: with an error-only revents it calls process with no events and polls again at once, a spin that ends at the receive timeout with ZOPERATIONTIMEOUT (DRV-18).
6. On waker IN → `reset()`. Run `process_step(events, now)` (READ implied while `buffered_ready()`).
7. If `state()` is negative (C's `is_unrecoverable`), the next call returns `ZKRS_EXIT` (mt_adaptor.c:465-468). This runs when the step and any teardown continuation have finished, so after a digest or SASL failure or an expiry the waiters are signalled, the auth completions have run and the FIFO items are published before the IO thread exits. Return `ZKRS_DONE`.

Every blocking operation in an iteration is bounded or interruptible by the waker:
- the TLS handshake is non-blocking, one flight per wake;
- the RW probe polls the waker (§8.4);
- the CLOSE flush belongs to the final close.

The exceptions are the `resolve` leaf, the TLS file reads in `new_conn`, and application callbacks, which C also runs on this thread.

### 5.5 Dispatch thread (`zkrs_wait_dispatch`)

```rust
fn wait_dispatch(&self, ob: &mut Outbox<T>) -> Step {
    let mut q = self.fifo.lock();
    while q.items.is_empty() && !(self.life.close_requested.load(Acquire) && q.io_exited) { q = self.fifo_cv.wait(q); }
    if q.items.is_empty() { return Step::Exit; }                 // mt_adaptor.c:497-509
    ob.extend_items(std::mem::take(&mut q.items));                // order unchanged
    Step::Done(ZOK)
}
```

- Items queued while a batch runs (for example ZCLOSING fakes from a close inside a callback) are newer than the batch, so they follow it in the next batch.
- The thread exits only when `close_requested`, `io_exited` and the FIFO is empty. After session expiry or an auth or SASL failure it stays until close (C).
- The FIFO is unbounded (C).

### 5.6 Sync calls (05 §13 mechanism, CC-8 (00 §4))

The sync wrapper (C):
0. `zoo_wget`/`zoo_reconfig` return ZBADARGUMENTS for a NULL `buffer_len` before anything else (RC-11, 05 §12 stage 0).
1. Returns ZSYSTEMERROR with errno EDEADLK when `zks_current_role(zh) == ZKRS_ROLE_IO` (DSP-04 = c-shim D7 = DRV-3). The check is C (CC-4 (00 §4)).
2. `calloc`s a `zks_sync` record with two references. Failure returns ZSYSTEMERROR (RC-13).
3. Calls the **exported** `zoo_aX` with `ZKS_SYNC_MARKER`, so ABI-06 interposition still sees sync traffic. `zks_submit` then calls `zkrs_submit(…, ZKRS_DELIVER_DIRECT)`, and zk-ffi maps `DIRECT` to `Route::Direct`.

Proto emits `Completion{route: Direct}` at the reply's proto position. The IO step pumps it as a RESULT barrier item (CC-18 (00 §4); no Rust slot exists in the drop-in). C's `zks_deliver_result` sees the sync mode and runs `zks_sync_complete`: it has zk-ffi's raw writers build the legacy C result objects into the heap record (for MULTI the per-op copies always; for every other kind only when `rc == 0 && !failed`; 05 §13), sets `rc`, `failed`, `done`, signals, and drops the completer reference. The step resumes only after that, so the waiter is signalled before later frames are processed. The waiter, on its own thread after it wakes, copies out into the caller's buffers through zk-ffi's raw copy-out writers (the legacy `buffer_len` truncation and `path_buffer` NUL rules), and frees the record. No library thread writes another thread's stack.

- Teardown and close: RESULT with the reason (`failed`), outputs untouched (zookeeper.c:2031-2039). This includes the RO→RW switch, where every waiter gets ZCONNECTIONLOSS (02 §12.2), and a SASL failure (02 §7.4). Under a forced close (02 §12.3), a waiter whose request is in the trigger's snapshot stays blocked until the IO thread emits its RESULT with ZCONNECTIONLOSS at T3, or until a close before T3 gives it ZCLOSING. A waiter whose request was submitted after the trigger stays blocked until that request's reply on the next connection, or until a later teardown or close completes it.
- After `close_requested`, submit fails with ZMARSHALLINGERROR and the completion is never invoked (RC-02).
- A sync token dropped inside Rust goes to `discard_token`, which completes the record with ZCLOSING and signals, so a waiter can never hang (CC-9 (00 §4)).

zk-client's private `Slot<T>` (04 §5.2: `Mutex<SlotState<T>>` + `Condvar`), which backs every native `ResponseFuture` and the blocking `wait()`, is the only Rust result slot; the drop-in has none.

### 5.7 Callbacks on the IO thread, the auth lockpoint and SASL items

| Callback | When | Frames below it |
|---|---|---|
| auth completion | AUTH reply (any rc); connection teardown | `zks_io_main` → `zks_drain` (C only) |
| auth completion, ZCLOSING | `zookeeper_close` with refs > 1 | the closing thread's `zookeeper_close` → `zks_drive` → `zks_drain` |
| Cyrus SASL callbacks | a SASL item during the IO step's drain | `zks_drain` → `zks_sasl_item` → Cyrus |
| log callback | any LOG item of the IO step | `zks_drain` → `zks_log_emit` |
| sync completion (library code) | a sync RESULT item | `zks_drain` → `zks_sync_complete` (the result objects are built by zk-ffi's raw writers) |

**Auth lockpoint (CC-7 (00 §4)).** Proto emits `Output::AuthLockpoint` (DR1) before the state change and completion collection at each legacy `zoo_lock_auth` site: the AUTH reply (zookeeper.c:2160), teardown and close collection (2062), and auth resend (2216). The driver pumps it as an `AuthLockpoint` barrier. C calls the exported `zoo_lock_auth(zh); zoo_unlock_auth(zh);` on `zh->auth_lock` with no Rust frame and no Rust lock, then resumes (`zks_auth_lockpoint`, 05 §19.9). The `zoo_lock_auth`/`zoo_unlock_auth` call counts that symbol interposition sees are not an ABI contract; the shim takes the lock at the AUTH_LOCKPOINT sites (CC-7 (00 §4); AR-06 in 08 §3.6 excludes them). The shim's `zh->auth_lock` is initialized exactly as legacy initializes its auth lock: `pthread_mutex_init(&zh->auth_lock, 0)` (default attributes; legacy `pthread_mutex_init(&zh->auth_h.lock, 0)`, mt_adaptor.c:253) (05 §19.9), and `zoo_add_auth` locks and unlocks it as legacy does. A caller that already holds `zoo_lock_auth` and then calls `zoo_add_auth` behaves as in legacy (undefined for a default mutex). An application that holds `zoo_lock_auth` therefore stalls IO-side auth processing, and the suspension delays everything after it, as in C. Completions run after the lockpoint, as in C (2169-2188). Lock order is `auth_lock → core`, because `zoo_add_auth` holds `auth_lock` around `zkrs_add_auth`, and the lockpoint takes `auth_lock` alone. LA-01 is [diff] parity. In st, `zoo_lock_auth` is a no-op and the lockpoint does nothing in C.

**SASL items (06 §4.6-4.7).** Each item wraps proto's `SaslCall {gen: u32, kind: Start | Step(Option<SecretBytes>) | Dispose}`. zk-ffi turns it into a `zkrs_sasl_call {kind, gen, local, peer, token}` (05 owns zkrs.h).
- `Start`: the driver reads the local and peer addresses with rustix `getsockname`/`getpeername` on the IO-owned socket when it builds the item, and stores them in `SaslItem.addrs` (CC-31 (00 §4)). No fd is lent to C. zk-ffi writes each address into outbox storage as the platform `struct sockaddr_in` or `struct sockaddr_in6` image and passes it as the `zkrs_bytes` `local` and `peer` fields of `zkrs_sasl_call` (05 §19.2).
  - If either read fails, the driver builds no START item and calls `handle_sasl_addr_failed(gen, which, now)` with `which = SaslAddr::Local` (getsockname) or `SaslAddr::Peer` (getpeername). The core then logs the legacy "getsockname" or "getpeername" site and "SASL authentication failed. rc=%d", and fails the negotiation with ZSYSTEMERROR (02 §7.4). These are core (Rust) sites in `abi/log-sites.tsv`; the C glue does not log them (06 §4.7.8). A START item therefore always carries two valid addresses.
  - The drain calls `zks_sasl_start(s, gen, p, local, peer, &out)` (in `shim/zks_sasl_cyrus.c`). The glue first rejects a stale gen (status STALE, no Cyrus call). It then disposes any Cyrus connection still present, as legacy does at this point (zk_sasl.c:215-217); normally there is none, because the DISPOSE item just before the START has disposed it. It validates the family and length of each image, calls `getnameinfo(NI_NUMERICHOST|NI_NUMERICSERV)` and formats `"%s;%s"` with `snprintf` into bounded buffers. On a `getnameinfo` error it logs "getnameinfo: %s\n" at the legacy site (zk_sasl.c:185). Legacy formatting, IPv6 scope suffixes included, is therefore byte-identical. The glue creates the connection in the per-handle `zks_sasl_sess` and calls `sasl_client_start` with `p->mechlist`, the session's NUL-terminated mechlist copy in `zks_sasl_params` (06 §4.7.2, §4.7.5). When the status is not STALE, the drain then records `gen` through `zks_sasl_set_gen` (defined in the same file; a no-op when START left no connection).
- `Step(token)`: `token == None` is the server's -1 token, passed to Cyrus as `(NULL, 0)` (SAS-10). Token bytes were bounds-checked by exact-length frame decoding. C calls `zks_sasl_step(s, gen, challenge, &out)`.
- `Dispose`: legacy timing (SAS-13 RE, DR2). A teardown (T1) or forced close closes the transport and yields no `Dispose`; the Cyrus connection stays as legacy leaves it. A SASL failure is such a teardown, and because it is terminal, its connection stays until the final close. On a reconnect proto yields `Dispose` for the old session immediately before the next `Start`, in the same outbox, as legacy `zoo_sasl_connect` disposes first (zk_sasl.c:215-217). The final close emits it after it drops the transport, as legacy `destroy` closes the socket (zookeeper.c:719-723) and then disposes the Cyrus client (744-749). No Cyrus callback therefore runs at a point where legacy runs none. C calls `zks_sasl_dispose(s, gen)`, which disposes, zeroes the session's secret copies and sets the connection to NULL. It gives no result.
- C answers `Start` and `Step` with `zkrs_sasl_result(core, gen, view)`, which copies the glue output (`zkrs_sasl_out {status, mech, token}`) into Rust at once. The effects (frames, logs, the deferred CONNECTED event) are produced in the following `zkrs_resume`. The glue rejects a stale `gen` itself (status STALE, no Cyrus call; 06 §4.7.2), and proto ignores a stale `gen` in `handle_sasl_started(gen: u32, …)`/`handle_sasl_stepped(gen: u32, …)`.
- **Serialization.** START and STEP are barriers, so at most one of them is in flight per handle. DISPOSE is not a barrier; the outbox puts it immediately before a reconnect's START, or, in the final close, after the transport drop and before `ZKRS_RELEASE`. All SASL items are drained on the IO owner (the mt IO thread, the st caller, or the final closer after the IO thread has been joined or has exited), so a `Dispose` can never run concurrently with a `Start` or `Step`. No C lock is needed for this.

### 5.8 Lifetime: refs, prolog, epilog

- Holders of `refs` (the `api_prolog`/`api_epilog` equivalent): the IO and dispatch threads (from `zkrs_thread_enter` to `zkrs_thread_exit`), the init thread during start, `close`, and in st every `zookeeper_interest`/`zookeeper_process` call until it returns. Async and sync API calls take no ref (C: `api_prolog`/`api_epilog`, zookeeper.c:3214-3224, are called only by `zookeeper_interest` 2643, `zookeeper_process` 3539, `zookeeper_close` 4007 and mt_adaptor.c:220, 278, 378, 414, 490; a call after close is undefined, zookeeper.h:885-887).
- `epilog`: `if refs.fetch_sub(1) == 1 && close_requested { final_close in this call }`.
- Rust memory is kept alive by `Arc` strong references (C's, one per library thread, one per outbox, one per call frame; 05 §4). `refs` decides only **who runs `final_close` and when**, including the `ZKRS_RELEASE` that makes C free `zhandle_t`.

### 5.9 Close (`zkrs_close(core, role, &ob, &rc)`, keyed on the caller's role)

```text
zkrs_close(core, role):                // role from zks_current_role(zh): IO | DISPATCH | FOREIGN
  if getpid() != creator_pid            -> forked-child path (5.11); DONE(ZOK)
  refs += 1 (before publishing close)                         // zookeeper.c:4007-4008
  if close_requested.swap(true)         -> epilog; DONE(ZOK)   // nested or second close: no-op (DRV-5)
  if refs > 1:
     lock core; begin_close(now); pump outputs; unlock
        // sync RESULT(ZCLOSING) items, one barrier per run (02 §5.3), signalled on THIS thread;
        // async fakes published to the FIFO; AUTH_LOCKPOINT; auth RESULT(ZCLOSING) group (barrier);
        // tx kept, flushed before CLOSE_OP (close_mode LegacyFailPending, CLS-02)
     role == IO?       yes: THREAD{DETACH, IO}; fifo.io_exited = true             // barrier
                       no : waker.wake(); THREAD{JOIN, IO}                         // barrier
     role == DISPATCH? yes: THREAD{DETACH, DISPATCH}                               // barrier
                       no : fifo_cv.notify_all(); THREAD{JOIN, DISPATCH}           // barrier; it drains all first
     epilog (may run final_close in this call, then RELEASE)
  else: final_close (st outside callbacks)
```

The drain order puts the ZCLOSING auth completions before the joins, as legacy `free_completions` runs before `adaptor_finish` (zookeeper.c:4016-4019, CC-3 (00 §4)).

| Caller | IO thread | Dispatch thread | `final_close` runs on | Returns |
|---|---|---|---|---|
| foreign thread (mt) | wake + join | notify + join (it delivers every queued item, ZCLOSING included) | caller | ZOK |
| dispatch-thread callback (mt) | wake + join | detach self | dispatch thread, in its `zkrs_thread_exit` after it drains | ZOK |
| IO-thread callback (auth, log, SASL) (mt) | detach self; `io_exited = true` | notify + join | IO thread, in its `zkrs_thread_exit` | ZOK. Items published later go to a FIFO nobody reads and are discarded at destroy (zookeeper.c:689-710). Auth completions still fire. |
| any thread while a close is in progress | — | — | — | ZOK, no-op |
| st, outside callbacks | — | — | caller | flush rc |
| st, inside a callback | — | — | the enclosing interest/process call | close: ZOK; enclosing call: its own rc, with `zh` already released |

The IO thread leaves its loop after the close's wake. Its `IoCore` (socket still open) stays in `io` for the final close.

### 5.10 `final_close` (exactly once; items go into the outbox of the call that drops the last ref)

1. `finalizing.swap(true)` guard.
2. Take `io`, then `core`. `queue_close_op()` queues CLOSE_OP if connected. Unlock `core`.
3. LOG "Closing zookeeper sessionId=%#llx to %s" if connected (zookeeper.c:4030-4031), on this thread. Return `ZKRS_MORE` before blocking (CF5).
4. Flush, then wait (02 §13):
   - flush `tx` for at most 3000 ms (`CLOSE_FLUSH_MS`): copy the head, unlock, blocking-with-deadline `send` through `poll`, relock, `handle_transmitted`. Under TLS this is §4.3.4 with blocking sends.
   - then poll POLLIN without reading for at most 1500 ms (`CLOSE_WAIT_MS`).
   - Under TLS, when the flush sent every queued byte: `send_close_notify()` and non-blocking sends of the alert within the remaining close budget, with no wait for the peer's alert (§4.3.5).
   - The close rc is ZOK, ZOPERATIONTIMEOUT, ZCONNECTIONLOSS or ZSYSTEMERROR (CLS-06). The alert send does not change it.

   No lock is held while blocking, and no application code runs during the wait, because items are drained only before and after it.
5. LOG "Freeing zookeeper resources for sessionId=%#llx" (zookeeper.c:4053).
6. Lock `core`. `destroy(now)`; pump:
   - mt: AUTH_LOCKPOINT and auth RESULT(ZCLOSING) items (they still fire). FIFO leftovers and `into_leftovers()` tokens are dropped, and each drop goes to `discard_token` (legacy `destroy` releases them without calling them, zookeeper.c:689-710).
   - st: auth inline, then the local FIFO as RESULT/WATCH items (zookeeper.c:676-714).
7. After that drain (in the following `zkrs_resume`), in this order: drop the transport (the socket is closed after the destroy callbacks, as legacy `destroy` closes it at 719-723; a TLS `close_notify`, if any, was sent in step 4), then emit SASL `Dispose{gen}` if a Cyrus session exists (not a barrier), as legacy `destroy` disposes the Cyrus client after the socket close (744-749). C runs the DISPOSE in the final drain.
8. Take every C-resource-owning value out of the driver state, zero the host token, and return `ZKRS_RELEASE` with the close rc. The outbox puts the DISPOSE before the release. C runs `zks_release` (frees `zhandle_t`). Rust state is freed when the last `Arc` goes.

### 5.11 Forked child (DRV-14 = CLS-11)

`zkrs_close` in a child (pid differs from `creator_pid`):
- no THREAD items (no join on thread ids that do not exist in the child);
- no CLOSE_OP;
- no items that reach application code;
- no SASL `Dispose` and no `ZKRS_RELEASE`, so C does not destroy `auth_lock` (a parent thread may have held it at fork) and does not call `sasl_dispose`.

Rust tries `core.try_lock()` and `io.try_lock()`. If both succeed, it closes the socket fd and the waker fds it can reach, without dropping the TLS session object (its memory is leaked with the rest). If either fails (a parent thread held it at fork), it closes nothing reachable only through that lock. `zhandle_t`, the Rust state and any unreachable fds are leaked deliberately. The only goal is not to end the parent's session. Documented constraint, as in legacy: after a fork in a multithreaded parent, only async-signal-safe operations are reliable.

### 5.12 mt-exported `zookeeper_interest`/`zookeeper_process`

c-shim D14 = DSP-18 (DRV-12 is an alias). The functions validate their arguments and the unrecoverable state as C does, then report "no socket": interest writes `fd = -1`, `interest = 0`, `tv = recv_timeout/3` and returns ZOK; process returns ZINVALIDSTATE. They never drive the state machine.

---

## 6. Caller-polled driver (libzookeeper_st)

### 6.1 Structure

One `Mutex` holds the `Connection`, the `IoCore` and the local FIFO. `DriverMode {interest: LegacyExact, session_delivery: Inline}`: `Session` outputs and `DrainPoint` are barriers. `Compat::LEGACY_C` is the same as for mt (flavor never changes `Compat`, K5). The auth lockpoint is still emitted and is a C no-op. There are no threads and no waker. The mutex makes accidental cross-thread use memory-safe without changing single-thread behavior (DSP-19). It is released around the `resolve` leaf and around TLS file loading.

### 6.2 `zookeeper_interest`

```c
int zookeeper_interest(zhandle_t *zh, ZKS_FD_T *fd, int *interest, struct timeval *tv)  /* SOCKET* on WIN32 */
{
    const zkrs_handle *core;
    zkrs_interest_out o; zkrs_outbox ob = ZKRS_OUTBOX_INIT; int written = 0, rc = ZOK; zkrs_step s;
    if (zh == NULL || fd == NULL || interest == NULL || tv == NULL) return ZBADARGUMENTS;
    ZKS_REGISTRY_CHECK(zh, __func__);   /* debug builds only, 05 §15.5 */
    core = zh->core;
    s = ZKS_CALL(zkrs_interest(core, &o, &written, &ob, &rc));   /* prolog + interest_step */
    for (;;) {                                                   /* callbacks inline; may release zh */
        zks_drain(zh, &ob);
        if (s != ZKRS_MORE) break;
        s = ZKS_CALL(zkrs_interest_resume(core, &o, &written, &ob, &rc));
    }
    if (s == ZKRS_RELEASE) zks_release(zh, core);                /* zh and core are dead after this */
    if (written) {                           /* caller memory: written even after release */
        *fd = (ZKS_FD_T)o.fd; *interest = o.interest;
        tv->tv_sec = (long)(o.timeout_ms / 1000); tv->tv_usec = (long)((o.timeout_ms % 1000) * 1000);
    }
    return rc;
}
```

- `o` is written by the call that reaches `finish_interest`, after the callbacks drained at earlier barriers of this call; it stays valid because it is a C local.
- The remaining stages of a forced close (§6.5) run before the interest work, as C's forced close precedes the rest of `zookeeper_interest` (2645), so for that call `o` is filled after their callbacks. If one of those callbacks closes the handle, `o` is `fd = -1`, `interest = 0`, `tv = 0`.
- The fd is the real OS socket, -1 when there is none, and -1 when this call closed it (DRV-7).
- The rc set is C's (RC-14), with ZSSLCONNECTIONERROR for a TLS config failure on the immediate path (CC-14 (00 §4)).

### 6.3 `zookeeper_process`

`zookeeper_process` = `zkrs_process(core, events, &ob, &rc)` + `zks_drive`, with exact reads:
- at most one connect completion (then return);
- at most one flush pass;
- at most one frame on plaintext (ZNOTHING if incomplete);
- under TLS, every frame complete in the plaintext of one ciphertext read (TLS-04, DRV-11), and buffered plaintext counts as READ;
- `events == 0` is legal;
- it returns ZINVALIDSTATE when there is no socket.

Dispatch runs only when `check_events` succeeded (DSP-16).

### 6.4 Drain points (where C calls `process_completions`)

| C site | st outbox |
|---|---|
| `queue_session_event` → `process_completions` (zookeeper.c:3263-3266) | `Session` → local FIFO. Barrier: move every local FIFO item into the outbox and return `ZKRS_MORE`. On `zkrs_resume`, items queued by callbacks during that drain are moved and delivered the same way before proto resumes. |
| `free_completions` calls auth inline (2062-2077) | `AuthLockpoint` (no-op), auth RESULT items, then `resume` |
| end of `cleanup` (2119-2121), end of `zookeeper_process` (3662-3664) | final drain of the local FIFO before `ZKRS_DONE` |
| `destroy` → `process_completions` (711-713) | `final_close` step 6: auth, then FIFO items |

Resulting order (C st):
- **connection loss:** earlier items, CONNECTING, auth, ZCONNECTIONLOSS fakes;
- **close:** auth (ZCLOSING), then async ZCLOSING;
- **reply-path auth failure:** auth, AUTH_FAILED, ZAUTHFAILED fakes;
- **SASL failure:** inside the driving call: earlier items, AUTH_FAILED, auth (ZAUTHFAILED), ZAUTHFAILED fakes (DV-FIX-3; C delivers nothing here);
- **forced close:** in the next `zookeeper_interest` or `zookeeper_process` call, or in the `zookeeper_interest` call that found the rebalance: earlier items, CONNECTING if the state was connected at the trigger, auth (ZCONNECTIONLOSS) and ZCONNECTIONLOSS fakes for the trigger's snapshot (DV-FIX-8; C delivers nothing here).

Because teardown is suspended at the barrier (P-1), a session-event callback that closes the handle makes pending requests complete with **ZCLOSING**, as in C.

### 6.5 Async API calls in st

C flushes inline on the caller through `nonblocking_send(zh, rc)`. It calls `adaptor_send_queue(zh, 0)` = `flush_send_queue(zh, 0)` (st_adaptor.c:67-70) **whatever `rc` is**, closes the socket and sets state 999 if the flush fails, and then returns ZMARSHALLINGERROR for `rc < 0`, else ZOK (zookeeper.c:4153-4161). Every submit that reaches the serialization stage calls it: single ops (4199-4206), multi (4970-4978) and remote remove-watches (5070-5077). It is skipped only by the early returns: ZBADARGUMENTS and ZINVALIDSTATE before serialization, ZNOWATCHER and the local removal (4996-5030), and ZUNIMPLEMENTED from multi (4975-4977). (Remote remove-watches also skips it on an encode or allocation failure, 5040-5060; those paths are reachable only through OOM, 07 P7.)

The st driver therefore:
1. runs `submit` under the mutex;
2. runs one non-blocking flush pass when `submit` returned `Ok(Submitted::Queued)` **or** `SubmitError {stage: Enqueue}` (ZMARSHALLINGERROR: an `InputDefect`, the closing case, an encode failure). The pass is plaintext, or §4.3.4 under TLS. It sends only what proto releases, so nothing during the handshake or without a connected transport. The pass is skipped for `Stage::Validate` codes (ZBADARGUMENTS, ZINVALIDSTATE, ZNOWATCHER, ZUNIMPLEMENTED) and for `Submitted::Local`, as in C;
3. runs no drain (async calls never run callbacks).

If the flush fails, the driver calls `handle_forced_close` here (P-7): the caller is the socket owner, so the socket (and TLS session) is closed now and the state is 999. The call returns its own rc: **ZOK** after a successful submit, ZMARSHALLINGERROR after an Enqueue-stage failure (RC-05/ST-13). So a submit that fails at the serialization stage can still send earlier queued requests, or trigger the forced close, as in C.

The forced close's event and results do not run in this call, because an async call never runs callbacks, and it may itself be running inside a callback. The trigger takes the requests and auth tokens pending at that point as its snapshot, the request this call submitted included. The next `zookeeper_interest` or `zookeeper_process` call delivers them (02 §12.3): the CONNECTING event if the state was connected at the trigger, then ZCONNECTIONLOSS for the snapshot's requests and auth tokens. A `zoo_a*` call that succeeded therefore returns ZOK, and its completion gets ZCONNECTIONLOSS in the application's next driving call. `zookeeper_interest` then goes on in the same call and connects with no delay. `zookeeper_process` returns ZINVALIDSTATE, as C does with no socket (3075-3076). A `zookeeper_close` before T3 starts, one from the CONNECTING callback included, delivers ZCLOSING to the snapshot and to later work, in submission order, and no event. `zoo_set_servers` in st behaves the same way.

A request submitted after the trigger is outside the snapshot. This holds for a `zoo_a*` call before the next driving call and for one from a callback that the driving call runs. The flush pass sends nothing, because proto releases nothing with no transport, so the pass cannot fail and no second trigger can follow. C's flush sends nothing in state 999 and cannot trigger a second close: its loop needs a connected state or a SASL negotiation in progress (zookeeper.c:5160), and with sock -1 `nonblocking_send` has nothing to close (zookeeper.c:4156). The call returns its own rc, and the request goes out whole on the next connection, behind the ConnectRequest, the auth frames and SetWatches. A `zoo_add_auth` in the same window records its entry and gets its result from an AUTH reply on that connection. If that attempt fails, its teardown fails the request like any queued request (ST-07).

mt has no app-thread flush (DSP-11).

### 6.6 Close in st

The st rows of the §5.9 table cover close in st. A close inside a callback queues ZCLOSING fakes through `begin_close`. They are delivered in the same drain after the current callback returns. The enclosing interest or process call then gets `ZKRS_RELEASE`, and the shim returns that call's rc without touching `zh` (tests/TestOperations.cc:509-583, CLS-07).

---

## 7. Native drivers (never linked into libzookeeper_*)

- **Threaded (`Runtime::Thread`, `DispatchMode::IoThread`).** One `std::thread` per client, from `StdSpawner` in `spawn_std.rs`, named (`zk-io`) and with a configurable stack. It runs the same `io_step` loop with `DriverMode {Precise, Dispatch}` and no C dispatch thread. It routes items under the bounded hand-off below, and runs resolution through the zk-client `Resolve` adapter (default std `ToSocketAddrs`, 04 §3) and SASL with the native mechanism, inline. It is the only native driver on Windows, from M4.
- **Tokio (`cfg(all(unix, feature = "tokio"))`).**
  - Each client has one task. It owns the `IoCore`, with its fd wrapped in `tokio::io::unix::AsyncFd`. The core is an `Arc<std::sync::Mutex<_>>`, and no lock is held across `.await`.
  - `AsyncFd` is a Unix-only tokio API, so the `tokio` feature, the tokio dependency and `tokio.rs` exist only under `cfg(unix)`. On Windows, async users use the threaded driver's async adapter or the blocking facade (04).
  - It waits on `notify`, readiness and `sleep_until(deadline)` with a hand-written `poll_fn`, so the tokio `macros` feature and its proc-macro crate stay out of the closure, then runs `process_step` and routes the items under the bounded hand-off. Resolution, SASL, TLS file loading and the RW probe (§8.4) go through `spawn_blocking`.
  - Submission is synchronous inside the API call (xid and wire order fixed at the call, K21). Items are handled in outbox order, so a watch event is sent before the completion of the op that triggered it.
- **Bounded hand-off (`dispatch_queue_capacity`, default 4096, both native drivers).** After each core call the IO side routes the Output items in order into slots, per-watcher queues and the session state (04 §4). The driver counts undelivered items: results and auth outcomes in slots whose receiver is alive and has not taken them, and one-shot watch events not yet returned (04 §4). Persistent-watch events are not counted. While the count is at or above the capacity, the IO side stops reading frames (read gate as specified here) but still sends requests and pings, and the receive timer runs. A receiver that takes or drops an item decrements the count and wakes the IO side (§5.3 waker or tokio `notify`).
  - The capacity is set through the zk-client builder (04). Per-watcher queues are unbounded unless `persistent_watch_buffer` sets a bound (04), so a slow watch-stream consumer grows memory and does not stall IO. `max_pending_requests` is likewise unbounded unless set.
  - **Read gate.** The IO side never blocks on the hand-off. While the count is at or above `dispatch_queue_capacity`, the IO side stops reading frames: interest drops READ, `process_step` does not read the socket and does not call `handle_read`, and `buffered_ready()` is false. The gate applies only in phase Established (state 3 or 5). TLS handshake records, the ConnectResponse and SASL frames are read whatever the count is, so a stalled consumer does not stop a reconnect; the gate closes again once the session is established and the count is still at the capacity. The gate is checked before each socket read and before each `handle_read` call of the frame loop. One `handle_read` call adds the items of the frames in the bytes it is given, so the count can go over the capacity by at most one read (64 KiB of frames). Items that do not come from frames (teardown fakes, timer-driven session events, close outputs) are always routed at once and counted, and never wait.
  - While the gate is closed, the IO side still sends queued requests and pings, and the proto receive timer keeps running. A consumer that stalls for longer than about 2/3·T therefore ends the connection with ConnectionLoss (receive timeout). The session then reconnects while the consumer is stalled, and its watches are re-registered (04 documents this and advises users to keep their handling fast or to hand work off to another thread). An application that holds many completed `ResponseFuture`s without awaiting them also counts against the capacity.
  - When a take or drop brings the count below the capacity, the woken IO side reopens the gate.
- **TLS (zk-client `tls-rustls`).** Both native drivers use the same `RustlsConn` and the same §4.3 path, with `Compat::tls_verify = WebPki`: the `ServerName` is the connect-string host token (`Endpoint.host`), SNI is sent for DNS names, and only configured roots are trusted unless the application asks for more (06 §3.12). With the opt-in zk-client feature `tls-platform-verifier`, `TlsOptionsBuilder::with_platform_verifier()` makes the connector use rustls-platform-verifier, that is, the OS trust store and the OS verification policy (06 §3.12). The drop-in never uses it, and cargo-deny bans the crate from the zk-ffi closure (§9). zk-client builds the `RustlsConnector` from `TlsOptions` (its `ReloadPolicy` decides whether files are re-read per attempt). System roots (`add_system_roots()`) come from the same trust loader (`trust.rs`) as the drop-in, with the OpenSSL default locations. zk-client computes the loader's `tls_env_allowed` input itself: on Unix with rustix, `getuid() == geteuid() && getgid() == getegid()`, and true on Windows (as the shim sets it, 05 §6), so the variables are ignored in set-uid and set-gid processes. The native `TlsOptionsBuilder::build` with `DefaultRoots` fails when that loader reports zero usable roots (04 §16.1). This check is native-only; the drop-in `DefaultVerifyPaths` step never fails for that reason (§8.2.3). No tokio-rustls is needed, because the session is sans-IO.
- **Close (`close_mode = Drain`).** §5.4 steps 1 and 7, §5.9 and §5.10 are the drop-in's. A terminal state does not end the native IO side: with no transport it waits on the waker (tokio: `notify`) until close, detach or the last-clone drop arrives. On a close or the last-clone drop, `queue_close_op()` returns false, `close_step` reports `Complete` at once, and the IO side calls `destroy(now)`, routes the outputs, executes `Sasl(Dispose)` when proto yields it, and exits. A native close calls `begin_close(now)` and `queue_close_op()` under the core lock in the API call. The IO side keeps running its interest and process steps and calls `close_step(now)` after each pass. On `CloseProgress::Complete` it sends `close_notify` under §4.3.5 when the close was graceful, calls `destroy(now)`, routes the outputs, executes `CloseTransport` and `Sasl(Dispose)`, and exits. `detach()` calls `abandon(now)` (02 §13); the IO side routes its outputs and exits. The close result and `Drop` semantics are in 04 §13.

---

## 8. I/O abstractions

### 8.1 TCP (`TcpSock`, rustix::net)

| Operation | Implementation | C reference |
|---|---|---|
| create | `socket_with(family, STREAM, flags, None)`. `flags` includes `CLOEXEC` **only if** the shim's runtime `sock_cloexec` flag is set. Non-blocking through `SocketFlags::NONBLOCK` where supported, else `ioctl(FIONBIO)` (07 P6; C uses `fcntl(O_NONBLOCK)`). `socket()` failure → `SocketCreate` (ZSYSTEMERROR). Same rules for the probe socket. | 2625-2629, 2681-2689, 2532-2540 |
| options | `TCP_NODELAY`; failure logs the legacy WARN "Unable to set TCP_NODELAY, latency may be effected" and continues. Apple: `setsockopt(SO_NOSIGPIPE)` on every socket, probe included (DRV-8). | 2553-2570 |
| connect | EINPROGRESS/EWOULDBLOCK → in progress; other errors → `TcpRefused` (ZCONNECTIONLOSS) | 2697-2711 |
| establish | `sockopt::socket_error` (SO_ERROR), checked only on WRITE/HUP (and on Windows also on each T/3 tick in state 7, §8.8) | 3079-3110 |
| send | `send(buf, SendFlags::NOSIGNAL)` on Linux, Android, the BSDs and illumos; flags 0 on Apple (the socket option does it) and on Windows (no SIGPIPE). Plaintext and TLS ciphertext use this one path. | 313-329, 403-410 |
| recv | `rustix::net::recv` into the heap buffer; EINTR retried (07 P6) | 1932-2007 |
| addresses | `getpeername`/`getsockname` on the driver's socket: per call for `zkrs_peer_addr` (05 §17), and by the IO owner for SASL `Start` items | 1265-1275 |

Legacy `SEND_FLAGS` is `MSG_NOSIGNAL` on Linux only; on Apple it passes `SO_NOSIGPIPE` as a send flag, which has no effect, and on the BSDs it is 0 (zookeeper.c:313-329). TLS writes went through `SSL_write` on the fd with no suppression. All three are GLB-01 (FU).

`RawSock` is `i32` on Unix and pointer-sized on Windows. `zkrs_interest_out.fd` is `intptr_t`, and the shim writes it through `SOCKET*` under WIN32.

### 8.2 TLS (`zk-driver/src/net/tls/`, feature `tls`)

The security section (06 §3.2-3.7) is normative for this subsection. It lists what the driver relies on.

#### 8.2.1 Driver contract

```rust
// zk-driver/src/net/tls/mod.rs   (#![forbid(unsafe_code)])
pub use zk_proto::{Endpoint, TlsConfigStep, TlsResourceAt};

/// Sans-IO TLS client session for one attempt or one RW probe.
/// The one implementation is RustlsConn (both profiles).
pub trait TlsConn: Send {
    fn is_handshaking(&self) -> bool;
    fn wants_read(&self) -> bool;
    fn has_ciphertext(&self) -> bool;                            // ciphertext waits for take_ciphertext
    fn handshake(&mut self) -> Result<(), TlsFailure>;           // moves handshake bytes out; Ok while in progress
    /// Takes all bytes. Never discards plaintext: a fatal error, an alert
    /// or close_notify is latched. Err tells the caller that a failure is latched.
    fn feed(&mut self, ciphertext: &[u8]) -> Result<(), TlsFailure>;
    fn feed_eof(&mut self) -> Result<(), TlsFailure>;            // latches Closed (Handshake while handshaking)
    /// Appends all plaintext decrypted before a latched failure.
    /// Only the call after that plaintext is taken returns the failure.
    fn read_plaintext(&mut self, out: &mut Vec<u8>) -> Result<usize, TlsFailure>;
    fn write_frame(&mut self, plaintext: &[u8]) -> Result<(), TlsFailure>;          // one call per chunk
    fn take_ciphertext(&mut self, out: &mut Vec<u8>) -> usize;
    /// Queues the close_notify alert for take_ciphertext. Graceful close only (§4.3.5).
    fn send_close_notify(&mut self);
}
#[derive(Clone, Copy)]
pub enum TlsFailure { Handshake, Data, Closed }
pub enum TlsSetupError { Config { step: TlsConfigStep }, Resource { at: TlsResourceAt } }  // Config: errno EINVAL
pub enum TlsPurpose { Main, Probe }
pub trait TlsConnector: Send + Sync {
    /// Make a new session. The drop-in reads all files again on each call; natively ReloadPolicy decides (§4.3.1).
    /// Call only with no driver lock held (the Unlocked proof).
    /// No time argument: the verifier uses the rustls time provider
    /// (the system clock in production, an injected provider in tests).
    fn new_conn(&self, u: &Unlocked, purpose: TlsPurpose, peer: &Endpoint)
        -> Result<Box<dyn TlsConn>, TlsSetupError>;
    /// The step's %s log argument: the connector's own copy of the cert-string field
    /// for that step. None for a NULL field and for steps that print no path;
    /// the driver passes None to proto as JString::Null, which prints as "(null)".
    fn path_for(&self, step: TlsConfigStep) -> Option<&[u8]>;
}
```

- This subsection owns the zk-driver TLS trait signatures: `TlsConn`, `TlsConnector` and the driver-side `TlsTransport` (§4.3), all in `net/tls/mod.rs`. 06 §3.1-3.2 use the same names.
- `Endpoint` is proto's `{addr: SocketAddr, host: HostToken}`. `LegacyChainOnly` uses only `addr`; `WebPki` uses `host`.
- `TlsConfigStep` is proto's `{CaFile, DefaultVerifyPaths, ClientCertChain, ClientKey, CheckPrivateKey}`; `TlsResourceAt` is `{ContextNew, ConnectionNew}`. The driver passes `TlsConfig {step, file}` (with `file` built from `path_for(step)`), `TlsResource {at, errno}` and `TlsHandshake {errno}` to proto (DR3). `CaFile` prints `ca`, `ClientCertChain` prints `cert`, `ClientKey` prints `key`; the other steps print no path. On the probe a config failure is `ProbeTlsFailure::Config {step, file}`.
- A session in the handshake phase reports every failure as `Handshake`; the driver acts on it at once (§4.3.2). In the data phase the driver acts on a latched failure only after the frame loop (§4.3.3).
- **Latch contract** (this subsection owns it; 06 §3.2 relies on it). `feed` and `feed_eof` never discard plaintext: a fatal error, an alert, close_notify or EOF is latched in the session. `read_plaintext` returns the plaintext already decrypted first. When that plaintext has been taken, the next call returns the latched failure. The driver's `take_plaintext` (§4.3.3) depends on this order.

#### 8.2.2 `RustlsConn`

```rust
// zk-driver/src/net/tls/conn.rs
/// One rustls client session over memory buffers. It never sees the socket.
pub struct RustlsConn {
    conn: rustls::ClientConnection,   // send buffer limit off: write_frame never writes short
    tx: Vec<u8>,                      // ciphertext for the socket
    rx: Vec<u8>,                      // plaintext not yet given to the driver
    latched: Option<TlsFailure>,      // first fatal error, alert, close_notify or EOF
}
impl RustlsConn {
    /// Give ciphertext to rustls. Take out the plaintext of each batch
    /// before acting on an error, so no plaintext is lost.
    fn feed_all(&mut self, mut ct: &[u8]) -> Result<(), TlsFailure> {
        let mut stalled = false;
        while !ct.is_empty() && self.latched.is_none() {
            match self.conn.read_tls(&mut ct) {
                Ok(0) => break,                                    // ct is not empty: no EOF here
                Ok(_) => { stalled = false; self.process(); }
                Err(_) if stalled => self.latch(TlsFailure::Data), // no progress: stop
                Err(_) => { stalled = true; self.process(); }      // buffer full: process, then go on
            }
        }
        self.latched.map_or(Ok(()), Err)
    }
    /// Process the buffered records, take out their plaintext, and queue any alert.
    fn process(&mut self) {
        let r = self.conn.process_new_packets();
        self.take_plaintext();                          // reader() until WouldBlock; latches Closed
        if r.is_err() { self.latch_error(); }           // Handshake while handshaking, else Data
        self.pump_out();                                // an alert goes to tx
    }
}
```

- No busy loop on a full buffer (06 §3.2). A `read_tls` error processes the buffered records and feeding goes on; a second error with no progress in between latches `Data`. 08 §8.1 tests it (fault-injecting transport).
- `read_plaintext` moves `rx` out and returns `Ok(n)` while `rx` holds bytes; when `rx` is empty it returns the latched failure, else `Ok(0)`.
- The plaintext of records before a bad record must stay readable. `take_plaintext` reads `reader()` after a `process_new_packets` error too. The fault-injection tests (§12, item 8) check this against the pinned rustls release. If that release does not keep the plaintext, `feed_all` gives rustls one TLS record at a time (by the 5-byte record header), so a bad record is never processed together with the good records before it.
- `feed_eof` gives rustls an empty read (`read_tls` returns 0), so rustls records the peer EOF, and latches `Closed` (or `Handshake` while handshaking). Plaintext received before it is still returned first.
- `take_ciphertext` moves `tx` out. `handshake()` is `pump_out()` and returns a latched `Handshake` failure: rustls advances the handshake inside `feed`. The first `take_ciphertext` after `new_conn` yields the ClientHello.
- The session is boxed, so `ClientConnection` lives on the heap, not on the stack of a C-created thread.
- Dropping a `RustlsConn` sends nothing. Only the graceful-close path of §4.3.5 calls `send_close_notify()` (TLS-06). A fatal alert that rustls queued leaves the session only through the driver's `alert_attempt()` (§4.3.5).

#### 8.2.3 `RustlsConnector`: one configuration per attempt and per probe

```rust
// zk-driver/src/net/tls/connector.rs
pub struct RustlsConnector { src: ConfigSource, provider: Arc<rustls::crypto::CryptoProvider> }
enum ConfigSource {
    /// Drop-in: the zookeeper_init_ssl fields; files re-read by every new_conn (TLS-02).
    Legacy { ca: Option<LegacyPath>, cert: Option<LegacyPath>, key: Option<LegacyPath>,
             passwd: Option<Zeroizing<Vec<u8>>>, env_allowed: bool, verify: TlsVerify },
    /// Native: built by zk-client from TlsOptions (04 §16.1).
    Native(NativeTlsSource),
}
/// One cert-string path: the original bytes (the path_for log argument) and the paths to open.
/// Unix: one candidate. Windows: the UTF-8 decoding (if valid), then the ANSI code-page
/// conversion from zk-ffi (§8.8).
struct LegacyPath { raw: Vec<u8>, open: Vec<PathBuf> }
/// Compile-time default locations (trust.rs). None or an empty value means no default location of that kind (01 §3.3 R6).
const DEFAULT_CERT_FILE: Option<&str> = option_env!("ZK_TLS_DEFAULT_CERT_FILE");
const DEFAULT_CERT_DIR: Option<&str> = option_env!("ZK_TLS_DEFAULT_CERT_DIR");
```

The drop-in fields come from `zkrs_init_args` (nullable ca, cert, key, passwd, and `tls_env_allowed`), which the shim fills from its `strtok_r` split of the cert string (06 §3.10). The shim copies the legacy cert string (the `zookeeper_init_ssl` cert argument) at init, splits it, and zeroes and frees its copy before init returns. Rust keeps its own copies of the four fields; the handle does not keep the cert string. The shim sets `tls_env_allowed` to false in set-uid/set-gid processes (`getauxval(AT_SECURE)` on Linux, `issetugid()` on BSD and Apple). The provider is passed explicitly to every `ClientConfig`; the process default provider is never installed. On Windows each of the three paths is opened by the rule of §8.8 (UTF-8 first, then the ANSI code page); the log argument of `path_for` stays the original bytes.

The build passes the default locations to cargo as `ZK_TLS_DEFAULT_CERT_FILE` and `ZK_TLS_DEFAULT_CERT_DIR`. The configure option `--with-rust-tls-default-paths=FILE:DIR` sets them. Its default is the OPENSSLDIR recorded at configure time (`$OPENSSLDIR/cert.pem` and `$OPENSSLDIR/certs`), read with `openssl version -d` from the `openssl` binary of the OpenSSL installation that the legacy probe selected: `$with_openssl/bin/openssl` for `--with-openssl=DIR`, `openssl` in `OPENSSL_ROOT_DIR/bin` in CMake, and otherwise the first `openssl` on PATH. When that binary is not available, configure and CMake apply the platform list of 01 §6.1 (`/usr/lib/ssl /etc/pki/tls /etc/ssl /usr/local/ssl /usr/local/etc/openssl@3 /opt/homebrew/etc/openssl@3`) on the build host, and use `<dir>/cert.pem` and `<dir>/certs` from the first entry that exists. 06 §3.4 states this rule. For a native cargo build with `ZK_TLS_DEFAULT_CERT_FILE`/`ZK_TLS_DEFAULT_CERT_DIR` unset, zk-driver's `build.rs` applies the same list on the build host. Cross builds set the variables explicitly. Nothing is probed at run time, and `openssl_probe::probe()` is never called.

`new_conn` performs the steps of `init_ssl_for_socket` (zookeeper.c:2847-2935) in the same order and stops at the first failure. 06 §3.3-3.7 is normative for each step. Each failure gives the legacy log line for that step (proto emits it, DR3/DR4; the texts are pinned in `abi/log-sites.tsv`):

| Step | Work | Failure | Legacy line |
|---|---|---|---|
| context | `ClientConfig::builder_with_provider(p).with_protocol_versions(&[&TLS13, &TLS12])`; `resumption = Resumption::disabled()` (a fresh `SSL_CTX` per attempt has no cross-attempt session state) | `Resource{ContextNew}` | "error creating ssl context" (2867, 2869) |
| `CaFile` | 06 §3.4, which follows `X509_load_cert_crl_file`. PEM blocks labeled `CERTIFICATE`, `X509 CERTIFICATE` and `TRUSTED CERTIFICATE` are trust inputs (the aux trust and reject settings of `TRUSTED CERTIFICATE` are applied); `X509 CRL` blocks are parsed and counted but not used, because legacy sets no CRL-check flags; well-formed private-key blocks, well-formed blocks with other labels and text outside blocks are ignored. The aux data of `TRUSTED CERTIFICATE` blocks is honored by the OpenSSL rules of 06 §3.4. The load fails, all or nothing, when the file cannot be opened or read, when any PEM block of any label has invalid base64, when a certificate, CRL or unencrypted private-key block has invalid DER, or when the file yields neither a certificate nor a CRL. Invalid DER means what OpenSSL's `d2i_X509` refuses: a certificate block that OpenSSL decodes and `LegacyCert` does not parse adds no trust input and does not fail the load (06 §3.4, DV-TLS-3). A file that yields at least one certificate or at least one CRL loads, as `X509_load_cert_crl_file` loads it. A NULL path fails, as `SSL_CTX_load_verify_locations(NULL, NULL)` does. | `Config{CaFile}` | "Failed to load CA file %s" |
| `DefaultVerifyPaths` | 06 §3.4. File: `SSL_CERT_FILE` if set and `env_allowed`, else the compile-time `ZK_TLS_DEFAULT_CERT_FILE`. Directory: `SSL_CERT_DIR` if set and `env_allowed` (split on `:`, `;` on Windows), else the compile-time `ZK_TLS_DEFAULT_CERT_DIR`. A set and allowed variable replaces the default, even when its target is missing or empty. `env_allowed` is false in set-uid/set-gid processes, as OpenSSL ignores the variables there. On Windows the build normally sets no default, so only the variables are used. In a directory the loader uses OpenSSL hash-name lookup: only files named `HHHHHHHH.N` (the subject-name hash, then `.0` up to the first suffix whose file is missing or does not load: no certificate, or a corrupt block) are read, a certificate counts only when its OpenSSL subject hash matches the name, and other files are ignored. OpenSSL 3.0 also consults a default `X509_LOOKUP_store` over the same directory value when it is a single path and the `by_dir` pass finds no certificate with the wanted subject; that lookup reads `<hash>.<digits>` names with the hex part compared case-insensitively and no stop at a suffix gap. OpenSSL 3.5 adds no default store location. The loader applies the `by_dir` rule alone; where OpenSSL 3.0 accepts through the store lookup, or where OpenSSL 3.0 or 3.5 keeps a certificate that comes before a corrupt block in a hash-named file, or a certificate in a hash-named file of another subject, the stricter verdict is DV-TLS-3 (06 §3.4). Missing, empty or malformed default locations add no trust inputs and do not fail the step, which matches `SSL_CTX_set_default_verify_paths`. | only on a resource error (in Rust, allocation failure, which aborts, 07 P7); the legacy line stays in the Site table with no reachable trigger | "Call to SSL_CTX_set_default_verify_paths failed" |
| `ClientCertChain` (only when cert, key **and** passwd are all non-NULL, as legacy) | 06 §3.6: the first certificate block is the leaf, later certificate blocks are the chain in file order. The chain is not checked against the OpenSSL security level (DV-TLS-2) | `Config{ClientCertChain}` | "Failed to load client certificate chain from %s" |
| `ClientKey` | 06 §3.6: the first private-key block. Unencrypted PKCS#8, PKCS#1 RSA and SEC1 EC; encrypted PKCS#8 with PBES2 (PBKDF2 with HMAC-SHA1, HMAC-SHA224, HMAC-SHA256, HMAC-SHA384 or HMAC-SHA512 (the accepted subset of the PRFs OpenSSL loads; other PRFs that OpenSSL loads are DV-TLS-5), or scrypt; with AES-128/192/256-CBC or DES-EDE3-CBC; `pkcs8`+`pkcs5`); traditional PEM encryption (`Proc-Type: 4,ENCRYPTED` + `DEK-Info`, `EVP_BytesToKey` with MD5, AES-128/192/256-CBC, DES-EDE3-CBC or DES-CBC; `legacy_pem`). scrypt parameters are rejected before any allocation when their memory need is over 32 MiB, which matches OpenSSL's default `EVP_PBE_scrypt` maxmem. PKCS#12 PBE, and ciphers or PRFs outside these sets, are rejected (DV-TLS-5); PBES1 fails in both cores under the reference default providers. The FIPS build accepts only unencrypted keys and PBES2 with PBKDF2-HMAC-SHA256/384/512 and AES-128/192/256-CBC; it rejects traditional PEM encryption (MD5-based), DES-EDE3-CBC, DES-CBC, PBKDF2-HMAC-SHA1 or -SHA224, and scrypt (DV-TLS-6, 06 §3.15.3). The passphrase is the first `min(strlen(passwd), 1024)` bytes. A passwd given with an unencrypted key is ignored, as OpenSSL does. A key the provider cannot load fails here (DV-TLS-5; the provider limits are in 06 §3.6). A key/cert mismatch of the same key type (the same OpenSSL key slot as the leaf, but not the leaf's key) also fails here, as `SSL_CTX_use_PrivateKey_file` does. | `Config{ClientKey}` | "Failed to load client private key from %s" |
| `CheckPrivateKey` | 06 §3.6: the SPKI comparison of key and leaf for any remaining case, that is, a key in a different OpenSSL key slot than the leaf (the `SSL_CTX_check_private_key` step) | `Config{CheckPrivateKey}` | "SSL_CTX_check_private_key failed" |
| session | verifier per §8.2.4; `with_client_cert_resolver` (the configured identity is sent regardless of the server's CA list) or `with_no_client_auth`; `enable_sni = false`; `ClientConnection::new(cfg, ServerName::IpAddress(peer ip))` | `Resource{ConnectionNew}` | "error creating ssl context" (2928) |

A `Config` failure on the main connection parks the attempt (§4.3.1). On the probe every failure is a probe failure (§8.4). The `%s` argument is the connector's nullable path copy; a NULL is passed as `JString::Null`, which the shim's log renderer prints as "(null)" on every platform (02 §2.1, §3.3). Legacy passes the NULL pointer to `%s` (zookeeper.c:2881), which glibc prints as "(null)" and other libcs may crash on; "(null)" everywhere is FU on those libcs.

#### 8.2.4 Verifiers (`Compat::tls_verify`)

**`LegacyChainOnly`** (`LEGACY_C`) reproduces the OpenSSL trust decision of zookeeper.c:2876-2890 (`SSL_VERIFY_PEER|FAIL_IF_NO_PEER_CERT`, CA file plus default verify paths, purpose `ssl_server`, no partial chains, no name check, no SNI). It is a custom `rustls::client::danger::ServerCertVerifier`. 06 §3.5 is normative; its rules (00 §2.5.2) are:

- (a) A leaf that is not OpenSSL-self-signed must chain to a trust anchor. A v1 leaf takes the v1-leaf path below. A v3 leaf is checked with `rustls::client::verify_server_cert_signed_by_trust_anchor` against the anchors valid at `now`, with the server's intermediates plus the trust-set bridges as candidates, then with the keyCertSign post-check and the rejected-issuer post-check of 06 §3.5 and OpenSSL's keyUsage and Netscape cert-type rules for `ssl_server`, and last with the OpenSSL path post-check of 06 §3.5: `v1_leaf::verify_v3_path` runs the issuer selection and finish rules of the v1-leaf path below (06 §3.14.3) over the leaf, the sent certificates and the trust set, with no sent certificate above a trusted bridge or a self-signed sent certificate, and with the leaf's own EKU, keyUsage and nsCertType purpose and its subjectAltName, because webpki's path building ignores keyUsage on CA certificates, the anchor's pathLenConstraint, the AKID and the subject CN, and retries every same-name issuer. The leaf is accepted only when webpki and all three post-checks accept.
- (b) A leaf that is byte-identical to a trust-set certificate is accepted **only when that certificate is OpenSSL-self-signed** (`EXFLAG_SS`, defined in 06 §3.4) and not a rejected input. The acceptance applies the mimic rule of 06 §3.5, a validity check (`notBefore <= now < notAfter`) and the `ssl_server` purpose check, which an aux trust list naming serverAuth or anyExtendedKeyUsage satisfies, as in 06 §3.14.3 Purpose. The leaf must also have no critical extension outside the processed set of 06 §3.14.3 (error 34; aux trust does not override it). A `CA:TRUE` leaf is accepted this way. A self-signed leaf that is not in the trust set is rejected.
- (c) No SNI is sent (`enable_sni = false`). The peer name passed to rustls is `ServerName::IpAddress(peer ip)`, and there is no hostname check: `verify_server_name` is never called.
- (d) As specified in 06 §3.5: only OpenSSL-self-signed, CA-capable (`X509_check_ca`), purpose-compatible trust inputs that are valid at `now` become anchors, plus `TRUSTED CERTIFICATE` inputs whose aux trust list names serverAuth or anyExtendedKeyUsage (explicit trust, 06 §3.4). A `TRUSTED CERTIFICATE` input whose reject list names serverAuth or anyExtendedKeyUsage, or whose non-empty trust list names neither, is a rejected issuer: it is never an anchor, a bridge or a rule-(b) match; it stays an issuer candidate, and the chain fails (error 28) when OpenSSL's chain building would select it (06 §3.4; on the v3 path the rejected-issuer post-check of 06 §3.5). Trust inputs that are neither self-signed nor explicitly trusted are bridges, offered only as intermediates; a self-signed input that is not an anchor can be accepted only through path (b); in issuer selection (06 §3.14.3) it stays a trusted candidate, and a chain that selects it fails, as in OpenSSL (06 §3.4). A chain must therefore end at a self-signed trusted certificate or at an explicitly trusted `TRUSTED CERTIFICATE` input, as OpenSSL requires without `X509_V_FLAG_PARTIAL_CHAIN`.

**v1 leaves** (`v1_leaf.rs`, `forbid(unsafe_code)`; 06 §3.1 owns the module and 06 §3.14 the rules). OpenSSL accepts an X.509 v1 leaf at any depth below a valid v3 chain, and webpki rejects v1 certificates, so rule (a) has a second path for them. v3 leaves still go through webpki; for them the same rules run only as the post-check of (a). For a v1 leaf that is not accepted by (b):

- the issuer is chosen as OpenSSL 3.x chooses it, with `ossl_x509_likely_issued` (issuer name, AKID per `X509_check_akid`, signature-algorithm key type) as the match test: trust-set candidates first, where every time-valid candidate must succeed (the ambiguity rule of 06 §3.14.3), and otherwise the first time-valid sent certificate in sent order, or, when none is time-valid, the sent candidate with the latest notAfter, which then fails the validity check (`find_issuer`). No sent certificate is taken above a trusted bridge or a self-signed sent certificate. A chosen issuer that is a rejected input fails the chain (error 28), and one with keyUsage but without keyCertSign fails it (error 32), before the signature check;
- the leaf's signature is checked against the chosen issuer with the provider's `SignatureVerificationAlgorithm`;
- the issuer chain is then verified to an anchor of rule (d) with OpenSSL's rules: basicConstraints and pathlen, keyUsage keyCertSign, the notAfter rule and the `ssl_server` purpose;
- a v1 intermediate is rejected, as OpenSSL rejects it (`check_ca` returns 0, error 79, `X509_V_ERR_INVALID_CA`); a v1 self-signed anchor is accepted.

The v1-leaf path is differentially tested against OpenSSL 3.0 and 3.5 (06 §3.14.6; 08 §5.2). It is part of the legacy trust decision, so the v1 case is not a deviation.

Handshake signatures are verified from the SPKI of the accepted leaf with the provider's `signature_verification_algorithms`, so v1 leaves accepted by (b) or by the v1-leaf path work (06 §3.5). Before any path runs, the verifier rejects a leaf whose own key type, curve, size or point form no provider algorithm verifies (`leaf_key_usable`, 06 §3.5; DV-TLS-3), because the chain checks read only issuer keys and path (b) verifies no signature. In the FIPS build every one of these checks uses the FIPS module only.

The reference OpenSSL lines are 3.0.x LTS and 3.5.x LTS, at the latest patch release when M3 starts and then frozen (08 §4.1). OpenSSL 1.1.1 is out of support and is not a reference.

**`WebPki`** (`NATIVE`) checks the hostname against the connect-string host token and sends SNI for DNS names (06 §3.12).

The differential trust-decision corpus (OpenSSL `verify` against `LegacyChainOnly`) is owned by 08 §5.2; the rules it checks are 06 §3.5. The visible differences DV-TLS-1..6 are owned by 06 §3.13.

#### 8.2.5 Secrets

- `passwd` is held in `Zeroizing<Vec<u8>>` from `zkrs_handle_new` to release.
- Decrypted key bytes are `Zeroizing<Vec<u8>>`. The `PrivateKeyDer` is dropped once the per-attempt `ClientConfig` is built; the provider's own key object lives as long as that configuration, which is one attempt or one probe.
- The legacy cert string is copied by the shim at init, parsed, and zeroed and freed before init returns. The handle does not keep it (§8.2.3).
- No secret is logged. Log lines carry file paths only.
- Secrets are protected by zeroization only. The driver uses no `madvise(MADV_DONTDUMP)` and no `mlock`.

### 8.3 Frames

- Framing is decoded by zk-jute's `FrameDecoder` inside proto (`handle_read`, `read_hint`).
- The driver reads exactly `read_hint()` bytes in st plaintext. In mt, native and every TLS read it reads up to 64 KiB into the heap buffer.
- Legacy `max_frame_len` is unlimited (WIRE-01); native is 4 MiB (CC-15 (00 §4)). The body grows as bytes arrive (proto).
- Writes come from `poll_transmit` with the P-3 pin. mt and native copy the head into `tx_copy` and send it without the core lock. st sends under the handle mutex. TLS adds the acknowledgment rule (§4.3.4).

### 8.4 Read-only probe (DRV-13 = SRV-11)

The probe runs inline in the step, as in C (zookeeper.c:2461-2522; the tokio driver runs it through `spawn_blocking`, below), against the endpoint proto names (the next address, `addrvec_peek`):
1. Open a new non-blocking socket (same CLOEXEC rule, `TCP_NODELAY`, `SO_NOSIGPIPE` on Apple).
2. `connect`; poll for WRITE for at most 1 s, then `SO_ERROR`.
3. With TLS: if LOG items are pending, the step first stops at a barrier (`ZKRS_MORE`) and C drains them, as before the main connection's `new_conn` (§4.3.1, 06 §3.3); then `new_conn(&unlocked, TlsPurpose::Probe, &ep)`, a fresh session; in the drop-in its files are re-read (TLS-02), and natively `ReloadPolicy` decides (§7). The handshake runs non-blocking (send, poll, recv, feed) for at most 1 s. A verify failure or bad record, in this step or in the read of step 4, calls alert_attempt() (§4.3.5).
4. Send `"isro"` (plaintext, or one `write_frame` and its ciphertext), then read. Send and read share a 1 s budget.
   - Plaintext: one successful `recv` of at most 10 bytes, as C does.
   - TLS: feed ciphertext until the first plaintext bytes, EOF or the deadline; take at most the first 10 plaintext bytes (C's single `SSL_read` of 10 bytes).
5. Success iff the first three bytes of a zero-filled 10-byte buffer are `'r'`, `'w'`, `0`. This is exactly C's `strcmp(buf, "rw") == 0` rule (SRV-11, 02 §9.5), which stops at the first difference or at the NUL of `"rw"`, so it never reads past byte 3.
6. The socket is closed and the session dropped, with no `close_notify`, on every path. C leaked the socket when `connect` failed.

The result goes to proto as `handle_rw_probe(ep: Endpoint, outcome: ProbeOutcome {ReadWrite | NotReadWrite | Failed(Option<ProbeTlsFailure>)}, now)` (DR4), so the legacy lines are logged and the main connection is never torn down (C's `fail_on_error = 0`). In mt and the native threaded driver the waker is in every probe poll set, and a close aborts the probe. The tokio driver runs the probe through `spawn_blocking` and awaits its result before it continues the step, so at most one probe runs and the step order is the same as inline. Its poll set holds no waker, so the probe checks the driver's close flag and its deadline after each poll returns, and a close aborts it within one poll wait (at most 1 s). In st the probe blocks the caller for at most about 3 s; C could block without bound (blocking connect, an unbounded TLS loop, `SO_RCVTIMEO` with an uninitialized `tv_usec`).

### 8.5 Resolution

- **Drop-in.** Resolution is the Blocking vtable leaf `resolve` (CC-5 (00 §4)), called through zk-ffi's `HostLeaves` implementation with the `&Unlocked` proof, so no Rust lock is held. It performs `getaddrinfo(AI_ADDRCONFIG, AF_UNSPEC, SOCK_STREAM, IPPROTO_TCP)`, retries without flags on `EAI_BADFLAGS`/`EAI_ADDRFAMILY`, keeps AF_INET/AF_INET6 in order with duplicates and reports each skipped family as data, and maps EAI codes to errno as `getaddrinfo_errno` does (zookeeper.c:786-1040). It logs nothing; errors come back as data, and proto emits the platform's legacy resolver line (`getaddrinfo: %s`, or `Win32 message: %s\n` on Windows; DR5).
- It runs on the IO thread (mt), on the caller (st, `zoo_set_servers`), or on the init thread. Every path, `zoo_set_servers` included, uses the per-host loop of §4.1 step 3: a log flush before each `resolve` call, `handle_host_resolved` after each host, a log flush before the `rng_reseed` call, then `handle_resolved`. A transient DNS failure in `zoo_set_servers` keeps legacy's commit-then-retry behavior; only an unparsable string is not committed (CC-23 (00 §4), c-shim D9).
- **Native.** The zk-client `Resolve` adapter (default std `ToSocketAddrs`; 04 §3), inline (threaded driver) or through `spawn_blocking` (tokio), with no lock held.

### 8.6 Clock and randomness

- `Clock::now() -> Millis`, monotonic from a per-handle origin. `ManualClock` (zk-testkit, 08 §8.1) is used in tests. TLS certificate validity uses the rustls time provider (`UnixTime`): the system wall clock in production, as OpenSSL uses, and an injected provider in tests (§4.3.1).
- Randomness is proto's injected `Rng {on_resolution, random, drand48}`. The drop-in is `ShimRng` over the leaves `rng_reseed` (Blocking), `rng_random` and `rng_drand48` (Pure), which reproduce the libc process-global PRNG effects (SRV-04). The native implementation is SplitMix64 in zk-client (CC-19 (00 §4)).

### 8.7 Signals, fds, stack

- **Signals.** No handlers and no mask changes. Every send, plaintext or TLS ciphertext, uses `MSG_NOSIGNAL` or `SO_NOSIGPIPE` (§8.1), so no path can raise SIGPIPE (GLB-01). Rust's executable-only runtime init does not run in a staticlib.
- **fds.** Socket CLOEXEC follows the shim runtime flag. The waker pipe is not CLOEXEC (legacy). Each handle has one pipe pair. TLS adds no fd.
- **Stack budget on C-created threads.** These threads have default pthread stacks: 128 KiB on musl, 512 KiB on macOS, RLIMIT_STACK on glibc. Application callbacks never run with Rust frames below them (§3), so callback stack depth equals legacy's C-only depth. For the Rust frames themselves:
  - no Rust stack array or local larger than 4 KiB in zk-proto, zk-jute or zk-driver; read and write buffers and TLS sessions live on the heap;
  - decoding of vectors and multi results is iterative (proto/jute);
  - `clippy::large_stack_arrays` and `clippy::large_stack_frames` (threshold 8 KiB) are denied in zk-proto, zk-jute, zk-driver and zk-ffi;
  - third-party frames (rustls, ring or aws-lc-rs, RustCrypto) are not covered by the lints, so CI measures them: it runs `io_step`, `wait_dispatch` and the st steps over the fault-injecting transport, including full TLS handshakes with RSA-4096 and ECDSA P-384 chains and encrypted-key loading, on a 64 KiB-stack thread on musl and macOS. It records peak stack use with a guard-page probe and fails above 32 KiB.

### 8.8 Windows (M4, CC-33 (00 §4))

Windows has the mt and st drop-in drivers and the native threaded driver. It has no tokio driver (§7).

- **Types.** `RawSock` is `SOCKET` width; `zkrs_interest_out.fd` is `intptr_t`; the shim writes `SOCKET*` for st `zookeeper_interest`.
- **Winsock.** `zks_wsa_startup` per handle (`MAKEWORD(2,0)` with legacy's version check, the same log line and NULL return on failure), and `WSACleanup` in `zks_release` (zookeeper.c:4058-4060).
- **Threads.** `_beginthreadex(NULL, 0, …)` in `zks_threads.c`; join is `WaitForSingleObject` + `CloseHandle`; detach is `CloseHandle`. winport's pthread emulation is not used (DV-BLD-2).
- **Waker: loopback socket pair**, as legacy `create_socket_pair` (mt_adaptor.c:125-164), built by the driver with rustix::net: a listener on `127.0.0.1:0` with `listen(1)`, a connecting socket, `accept`, then the listener is closed. Both ends are non-blocking (`ioctl FIONBIO`). The driver checks that the accepted peer address equals the connector's local address and retries once otherwise, so another local process cannot take the wake channel; legacy's `SO_REUSEADDR` on the listener is not set (it has no use with port 0). Both changes are 07 P6. `wake()` is one `send` of one byte under the same `AtomicBool` coalescing; `reset()` is `recv` until a short read. On failure the core first emits the legacy line of the failing pair step: "Error creating socket. %d" (mt_adaptor.c:134) or "%s. %d" (mt_adaptor.c:119, with the step texts of :147, :150, :156, :160), then "Can't make a socket." (mt_adaptor.c:242). All are core sites in `abi/log-sites.tsv`, emitted by `zkrs_handle_new`. A peer-check failure after the retry has no legacy step, so it emits only the last line. Init fails with the WSA errno. Each handle has two sockets, as in legacy.
- **Readiness.** The IO step polls through the zk-driver trait `Poll` (`fn poll(&self, fds: &mut [rustix::event::PollFd<'_>], timeout_ms: i32) -> rustix::io::Result<usize>`, `Send + Sync`), held in `Shared::poll`. The default implementation is `RustixPoll`, which calls `rustix::event::poll` (WSAPoll on Windows) over `[waker_rx, socket]`, with the Unix revents mapping. zk-driver calls `Poll` only and contains no `unsafe`; it cannot call zk-ffi, because the crate graph is zk-driver ← zk-ffi (K1). If rustix lacks a needed WSAPoll case, zk-ffi implements `Poll` with a `windows-sys` `WSAPoll` call in its unsafe module behind a safe function (in the unsafe budget) and injects it when it creates the handle. `Poll` is a Rust trait, not a host vtable entry, so the CC-6 list is unchanged. The native threaded driver does not link zk-ffi and always uses `RustixPoll`: native Windows threaded support therefore needs rustix's WSAPoll coverage, and the remedy for a gap is an upstream rustix fix. zk-driver and zk-client never get `unsafe` for it. Legacy used `select` without `exceptfds` (mt_adaptor.c:444), so a refused non-blocking connect was never reported. WSAPoll on Windows builds before 10.0.19041 can also miss it.
  - State 1 (plain TCP connecting): legacy behavior is kept. The driver does not read `SO_ERROR` on the T/3 tick, and it maps POLLERR, POLLHUP and POLLNVAL on the connecting socket to no interest bit at all (neither READ nor WRITE), because legacy `select` never reported them (mt_adaptor.c:444). The 2/3·T receive timeout ends a refused attempt and fails over with ZOPERATIONTIMEOUT, as in legacy (WIN-05 RE).
  - State 7 (TLS, TCP connecting, ST-16): the receive timeout does not apply, so legacy would wait for ever. The driver reads `SO_ERROR` on every T/3 tick and ends a refused attempt with ZCONNECTIONLOSS ("server refused to accept the client"). This is FU as part of ST-16 (WIN-05).
- **SIGPIPE** does not exist; send flags are 0.
- **TLS trust inputs** are only `SSL_CERT_FILE` and `SSL_CERT_DIR`, unless the build sets `ZK_TLS_DEFAULT_CERT_FILE`/`ZK_TLS_DEFAULT_CERT_DIR` (§8.2.3). The variables are read with `std::env::var_os`, which uses the wide API.
- **TLS file paths** (the ca, cert and key fields of the cert string) are bytes, and the driver opens them as OpenSSL opens a file name on Windows. It decodes the bytes as UTF-8 and opens the result through the wide API. If the bytes are not valid UTF-8, or that open fails with not-found, it retries with the bytes converted from the ANSI code page. zk-ffi does that conversion (`MultiByteToWideChar(CP_ACP, …)` in its unsafe module) when it creates the handle, and passes the result to the connector as a second candidate path, so zk-driver stays `forbid(unsafe_code)`. The `%s` log argument stays the original bytes. TI-06 (08) tests both the UTF-8 and the ANSI case.
- **Legacy-core fallback** (CC-33 (00 §4)). If the M4 Windows spike fails, Windows ships on the legacy core for that release. This is recorded as DV-BLD-7, and the Rust core follows on Windows in the first release whose Windows build passes the M4 exit criteria of 08 §15.3.

---

## 9. Unsafe and third-party code

| Need | How zk-driver stays `forbid(unsafe_code)` |
|---|---|
| Run C callbacks | It doesn't. Items go back to C (§3). |
| Build C result objects, sync copy-out | zk-ffi's `abi::raw` writers (05 §10.1, §13) |
| Windows ANSI path conversion | zk-ffi's unsafe module, at handle creation (§8.8) |
| Threads with C attributes | C creates them; Rust is entered through `zkrs_thread_enter` and the step entries |
| getaddrinfo, libc PRNG | Blocking/Pure vtable leaves through zk-ffi's `HostLeaves` |
| Cyrus | SASL items executed in C (06 §4.7) |
| TLS | `RustlsConn` in safe Rust over rustls; crypto in audited third-party crates |
| poll, pipe, sockets, sockopts | rustix safe API with I/O-safety types; a Windows WSAPoll gap is filled by a zk-ffi `Poll` implementation injected into the driver (§8.8) |
| raw fd for st apps | `AsRawFd::as_raw_fd()` (safe) |
| errno | integers in step results; C assigns `errno` |
| panics | `panic = "abort"` in the `capi` profile; `ffi_guard` (catch_unwind) at every `zkrs_*` export |

**Tokens (CC-9 (00 §4)).** zk-ffi tokens are `CReq(NonZeroUsize)`, consumed by exactly one RESULT item or by `Drop`. `Drop` calls the Pure leaf `discard_token`, which frees the `zks_req` without calling anything; a sync record is completed with ZCLOSING and signalled. A `Drop` after the host token was zeroed aborts with a diagnostic, which is a bug in final-close ordering, not a reachable state. No orphan list exists.

**Third-party crates linked into libzookeeper_*:** rustix (`use-libc`; Bytecode Alliance; I/O-safety types), libc, errno, bitflags, linux-raw-sys, windows-sys, zeroize, and under `tls` the closure of §1 (06 §3.11). The `use-libc` backend keeps every syscall interposable. The crypto crates (ring or aws-lc-rs, the RustCrypto primitives) contain `unsafe` or assembly for constant-time code and CPU intrinsics; they are pinned, `cargo-vet safe-to-deploy`, and accepted as audited third-party code. ring's and aws-lc's C and assembly symbols (aws-lc-fips-sys included) are prefixed, and the `ld -r` step localizes them, so they cannot collide with an application's OpenSSL (K16). CI gates:
- the zk-ffi closure goldens (`supply-chain/closure/zk-ffi-<target>.txt`, `zk-ffi-<target>-aws-lc-rs.txt`, `zk-ffi-<target>-aws-lc-rs-fips.txt` and `zk-ffi-<target>-notls.txt`, 01 §3.3), with CI jobs for the aws-lc-rs and FIPS builds on Linux x86_64 and aarch64;
- `#![forbid(unsafe_code)]` and the G3 unsafe grep (08 §10) keep `unsafe` out of zk-proto, zk-jute and zk-driver; cargo-geiger output is informational (08 G4);
- cargo-deny bans openssl, openssl-sys, native-tls, rustls-pemfile and webpki-roots everywhere, rustls-native-certs and openssl-probe everywhere except as dependencies of rustls-platform-verifier, rustls-platform-verifier in the zk-ffi closure, and socket2, mio and polling except through tokio (01 §3.7). The G2 graph check (08 §10) keeps tokio, tokio-rustls, tracing, log and futures-core out of the zk-ffi graph. Build-specific cargo-deny exceptions allow aws-lc-rs/aws-lc-sys only in the aws-lc-rs provider build and aws-lc-rs/aws-lc-fips-sys only in the FIPS build;
- cargo-vet `safe-to-deploy`.

---

## 10. Sequence diagrams

### 10.1 mt async op with a log line

```
App A: zoo_aget -> zkrs_submit (lock; proto.submit; unlock) -> empty outbox at INFO -> waker.wake() -> ZOK
IO (C loop): zkrs_io_step: interest_step; poll -> waker IN, socket OUT; process_step: flush GetData(X)
IO: next step: poll -> socket IN; process_step: handle_read -> [Log(debug), Completion(X, Queued)] into the outbox
IO (C): zks_drain: next() -> LOG -> C emits on IO; next() -> Rust publishes X to the FIFO (notify); empty
D  (C): zkrs_wait_dispatch -> batch [RESULT(X)]; zks_drain: C emits X's delivery_logs ("Calling COMPLETION_DATA for xid=...") on D;
        zk-ffi raw writers build the C result objects; C calls cb(rc, value, len, &stat, data); C frees
```

### 10.2 Connection loss (mt), pending: async A, sync S, auth U

```
IO step: recv ECONNRESET -> handle_transport_failed(Io) -> proto teardown outputs (lazy past barriers):
   close transport (Rust, now; no SASL item, a Cyrus session stays until the next START),
   Session(CONNECTING) [publish], Completion(S, Direct, ZCONNECTIONLOSS) [barrier]
IO drain (C): Session published; RESULT(S) -> zks_sync_complete -> waiter T wakes
   zkrs_resume -> Completion(A) [publish]; AUTH_LOCKPOINT [barrier] -> C lock/unlock auth_lock
   zkrs_resume -> collect -> AuthResult(U, ZCONNECTIONLOSS) [barrier] -> U runs on IO (C frames only)
   zkrs_resume -> state 0, backoff; DONE
IO, next connection: ConnectResponse -> SASL DISPOSE(old gen), SASL START(new gen) [barrier] in one outbox
D: W1..Wn, default watcher (CONNECTING, ""), then A(ZCONNECTIONLOSS)   (may overlap U, as in C)
```

### 10.3 st connection loss (inside `zookeeper_process`)

```
process_step -> teardown: Session -> local FIFO; barrier: FIFO moved into the outbox -> MORE
drain: earlier items, W1..Wn, default watcher  (a callback that calls zookeeper_close -> begin_close:
       pending fail with ZCLOSING, fakes appended, delivered by this same drain loop)
resume -> remaining ZCONNECTIONLOSS fakes (if any) -> local FIFO; AUTH_LOCKPOINT (C no-op); AuthResult(U) [barrier]
resume -> DrainPoint: fakes delivered; DONE(rc)
```

### 10.4 Close from a foreign thread (mt), pending: async A, B, sync S

```
T: zookeeper_close -> zkrs_close(FOREIGN): refs 2->3; close_requested; lock; begin_close; unlock
T drain: RESULT(S, ZCLOSING) -> signal; A, B published (ZCLOSING); AUTH_LOCKPOINT; AuthResult(ZCLOSING) on T
   THREAD{JOIN, IO} (Rust woke IO first) -> pthread_join(io)
      IO: step sees close_requested -> EXIT; zkrs_thread_exit: io_exited, notify; epilog 3->2
   THREAD{JOIN, DISPATCH} -> pthread_join(disp)
      D: drains everything, ending with A, B (ZCLOSING); EXIT; zkrs_thread_exit: epilog 2->1
   epilog 1->0 -> final_close on T: LOG "Closing..." [MORE]; flush CLOSE_OP <= 3 s; POLLIN wait <= 1.5 s;
   (TLS: close_notify sent without blocking, no wait for the peer's)
   LOG "Freeing..."; destroy (leftovers -> discard_token); drop transport; SASL DISPOSE if any; RELEASE
T: final drain runs DISPOSE; zks_release frees zh; return ZOK
```

### 10.5 Close from a completion callback (mt dispatch thread)

```
D drain: cb_A -> zookeeper_close -> zkrs_close(DISPATCH): begin_close fakes B, C published; auth on D;
   THREAD{JOIN, IO}; THREAD{DETACH, DISPATCH}; epilog 3->2; DONE(ZOK); cb_A returns
D: outer batch continues; next wait_dispatch returns B, C; then EXIT; zkrs_thread_exit -> final_close on D -> RELEASE
```

### 10.6 Sync call from a watcher (mt)

```
D: zoo_get (C wrapper) -> role != IO -> calloc zks_sync -> zoo_awget(..., ZKS_SYNC_MARKER, sc) -> DIRECT
D: waits on sc->c (no timeout)
IO: reply -> Completion(Direct) at its proto position -> RESULT barrier -> zks_sync_complete: zk-ffi raw writers
    build the result into sc; signal
IO: zkrs_resume -> later frames
D: wakes, copies out into the caller's buffers (zk-ffi raw writers), frees sc, returns ZOK to the watcher;
   FIFO items wait for D
```

### 10.7 st TLS connect on the EINPROGRESS path, then pipelined replies

```
interest: Connect{endpoint, tls} -> connect EINPROGRESS -> state 7; written: fd, READ|WRITE, tv = T/3
process(WRITE): SO_ERROR 0 -> new_conn(Main): files read -> handle_tcp_connected (deadline 2/3·T)
   -> ClientHello sent -> ZOK
process(READ): recv -> feed (ServerHello..Finished) -> handshake done -> handle_transport_ready
   -> state 2, ConnectRequest flushed -> ZOK
process(READ): recv 1 record holding ConnectResponse + 2 replies -> read_plaintext -> handle_read:
   ConnectResponse -> Session(CONNECTED) barrier -> MORE; drain: watchers; resume ->
   reply 1, reply 2 into the local FIFO -> final drain: both completions in this one call (TLS-04) -> ZOK
```

### 10.8 TLS config failure (EINPROGRESS path)

```
process(WRITE): SO_ERROR 0 -> new_conn -> Err(Config{CaFile})
   -> handle_transport_failed(TlsConfig{CaFile, file}): LOG "Failed to load CA file %s"; errno EINVAL
   -> transport Parked; returns ZSSLCONNECTIONERROR
interest: written: fd, READ only, tv from the receive rule (state 7, no exemption while parked)
interest (2/3·T after the attempt start): receive timeout -> ZOPERATIONTIMEOUT teardown -> next server
```

### 10.9 Expiry reply followed by close_notify (TLS, st)

```
process(READ): recv 1 read holding ConnectResponse(new session id) + close_notify
   -> feed: plaintext kept, Closed latched -> read_plaintext: ConnectResponse; next call: Err(Closed)
   -> handle_read: expired -> state -112; teardown SessionExpired (EXPIRED event, ZSESSIONEXPIRED)
   -> the latched Closed is dropped with the transport -> ZSESSIONEXPIRED, as legacy
```

### 10.10 SASL failure (mt), pending: sync S, async A, auth U

```
IO: SASL reply err < 0, or a mechanism failure in zkrs_resume -> state -113; rc = SASL rc; teardown SaslTeardown:
    transport dropped (no close_notify); generation bumped; no DISPOSE
    Session(-113) published
    RESULT(S, ZAUTHFAILED) [barrier] -> signal; A published (ZAUTHFAILED)
    AUTH_LOCKPOINT; AuthResult(U, ZAUTHFAILED) on IO [barrier]; DONE(rc)
IO: next zkrs_io_step -> EXIT; zkrs_thread_exit: io_exited, notify; epilog
D: W1..Wn, default watcher (AUTH_FAILED, ""), then A(ZAUTHFAILED); D stays until close
T: request calls -> ZINVALIDSTATE; S's thread wakes with ZAUTHFAILED, outputs untouched
T: zookeeper_close -> join IO, join D -> final_close: no CLOSE_OP (not connected), no socket to drop,
   SASL DISPOSE, RELEASE
```

### 10.11 Forced close inside an st async call, pending: A (sent), B (the request of this call), auth U; N submitted after the trigger

```
T: zoo_aget(B) -> zkrs_submit -> flush fails -> handle_forced_close: socket dropped at once (no close_notify); state 999;
   generation bumped; no DISPOSE; snapshot taken {tx, A, B, U}; outbox: LOG only -> returns ZOK; zoo_state() reads 999
T: zoo_aget(N) -> zkrs_submit: N queued whole; the flush pass sends nothing and cannot fail, no second trigger -> returns ZOK
T: zookeeper_interest -> run_pending_teardown: Session(CONNECTING) -> local FIFO; barrier: FIFO moved -> MORE
   drain: earlier items, W1..Wn, default watcher  (a callback that calls zookeeper_close -> begin_close:
          A, B and N fail with ZCLOSING, delivered by this same drain loop; a callback that calls zoo_aget
          queues that request behind N)
   resume -> A, B (ZCONNECTIONLOSS) -> local FIFO; AUTH_LOCKPOINT (C no-op); AuthResult(U) [barrier]
   resume -> DrainPoint: A, B delivered; N still queued; state stays 999, no delay, cursor unmoved
   resume -> the interest action loop goes on in this call: Connect{next server} -> state 1 (0/999 path: P-5)
T: later calls: wire order ConnectRequest, then after the reply the auth frames, SetWatches, N
```

### 10.12 Forced close from `zoo_set_servers` (mt), pending: sync S, async A, auth U; N submitted after the trigger

```
T: zoo_set_servers -> resolve -> apply_resolution: reconfig -> state 999; socket condemned; snapshot taken {tx, S, A, U};
   waker; outbox: LOG only -> returns ZOK; zoo_state() reads 999
T': zoo_aget(N) -> zkrs_submit: N queued whole (nothing is sent with no transport), before or after the IO steps below
IO: wakes from poll -> process_step: socket dropped here (no close_notify, no DISPOSE); run_pending_teardown:
    Session(CONNECTING) published; RESULT(S, ZCONNECTIONLOSS) [barrier] -> signal; A published (ZCONNECTIONLOSS)
    AUTH_LOCKPOINT; AuthResult(U, ZCONNECTIONLOSS) on IO [barrier]; no socket -> ZINVALIDSTATE, ignored by the loop
IO: next iteration: interest_step -> Connect{selected through reconfig}; the connection sends no frame of the snapshot;
    wire order ConnectRequest, then after the reply the auth frames, SetWatches, N
D: W1..Wn, default watcher (CONNECTING, ""), then A(ZCONNECTIONLOSS)
```

---

## 11. Driver-level deviation register (ledger IDs are authoritative)

| ID | Ledger / c-shim ID | C behavior | New behavior | Class |
|---|---|---|---|---|
| DRV-1 | DSP-10 | Full self-pipe → app thread closes the live socket (mt_adaptor.c:337-353) | Coalesced waker; never fails; app threads never touch the socket | race / UAF |
| DRV-2 | ST-13 (DV-FIX-8) | Forced close on the app thread races the IO thread (zookeeper.c:1219-1226); a partial frame resumes mid-frame | State 999 synchronously in the call; fd closed by the IO owner, which then runs the rest of the teardown; no frame queued at the trigger goes onto the next connection, and a frame queued later goes out whole | race / stream corruption |
| DRV-3 | DSP-04 / D7 | Sync API on the IO thread deadlocks | ZSYSTEMERROR, errno EDEADLK (C check) | deadlock |
| DRV-4 | DSP-13 / D8 | Auth and log callbacks under `zh_lock`, `reconfig_lock` or `watchers_lock` | No internal lock held across any callback; C's own `auth_lock` is still taken at the legacy sites (AUTH_LOCKPOINT) | deadlock |
| DRV-5 | CLS-05 | Nested close from a close-path auth completion deadlocks; a concurrent second close is UB | Close while closing → ZOK no-op | deadlock / UB |
| DRV-6 | INIT-12 / D13 | `pthread_create` failure → `assert` | NULL, errno from `pthread_create` | crash / hang |
| DRV-7 | SRV-13 | Stale closed fd returned by interest | fd -1 when closed in this call | use of closed fd |
| DRV-8 | GLB-01 | SIGPIPE on macOS (flag misuse), on the BSDs (flags 0) and on every TLS write | NOSIGNAL / SO_NOSIGPIPE on every send, TLS ciphertext included | crash |
| DRV-10 | ST-16 | TCP phase spins with tv=0; blocking TLS handshake with no deadline; `FD_SET` with fd ≥ FD_SETSIZE | in the TCP phase, tv = T/3 when recv_to <= 0, otherwise min(recv_to, T/3) (ST-16(a)); non-blocking rustls handshake with a deadline 2/3·T from TCP completion → ZOPERATIONTIMEOUT | spin / hang / UB |
| — | ST-17 | TLS config failure on the EINPROGRESS path: state 7, retried on every write event (spin), no failover | rc ZSSLCONNECTIONERROR, errno EINVAL once (CC-14 (00 §4)); attempt parked with READ only; the receive rule fails over with ZOPERATIONTIMEOUT on both paths | spin / hang |
| DRV-11 | TLS-04 | Decrypted frames stall inside the session (no `SSL_pending`) | Every frame complete in the plaintext of one read is dispatched in the same call (st TLS: more than one frame per process call); buffered plaintext gives tv = 0 | liveness |
| DRV-12 | alias of D14 / DSP-18 | mt interest/process race the IO thread | "No socket" values | data race |
| DRV-13 | SRV-11 | RW probe: blocking connect, uninitialized `tv_usec`, fd leak, unbounded TLS | Bounded per phase, always closed, interruptible in mt | UB / leak / hang |
| DRV-14 | CLS-11 | Child close joins phantom threads and ends the parent's session | Child closes reachable fds only; leaks the rest | UB / cross-process damage |
| DRV-15 | SRV-06 (mt) | mt polls uninitialized fd/tv after an early interest error | Waits T/60 on the waker | UB |
| DRV-16 | WIRE-02 | Negative frame length → busy spin until timeout | READ dropped; same ZOPERATIONTIMEOUT at the same time | spin |
| DRV-17 | ST-18 (DV-FIX-3) | mt SASL failure: sync waiters block until another thread closes | Full teardown in both flavors: AUTH_FAILED event; sync, async and auth get ZAUTHFAILED (02 §7.4) | deadlock |
| DRV-18 | DSP-22 | POLLERR/POLLNVAL alone sets no interest bit (mt_adaptor.c:403-404): poll returns at once and the loop spins until the receive timeout (ZOPERATIONTIMEOUT) | Mapped to READ\|WRITE (§5.4 step 5); the next recv/send reports the error (ZCONNECTIONLOSS teardown); on Windows in state 1 no interest bit is set, as legacy (WIN-05 RE, §8.8) | spin (07 P2) |

DRV-3, DRV-10/ST-16/ST-17, DRV-11, DRV-12, DRV-15/SRV-06 and DRV-18 are acknowledged behavior differences (FIX-UB-ONLY) that 00 §5.2 lists for the release notes. DRV-2 and DRV-17 are the driver side of the registered behavior fixes DV-FIX-8 and DV-FIX-3 (00 §5.1). The visible TLS differences that come from rustls itself are DV-TLS-1..6, specified in 06 §3.13 (DV-TLS-1 Protocol floor, DV-TLS-2 Policy source, DV-TLS-3 Algorithm and certificate acceptance, DV-TLS-4 Linkage, DV-TLS-5 Key formats, DV-TLS-6 Key formats in the FIPS build); the driver adds none of its own. X.509 v1 leaves are accepted as OpenSSL accepts them (§8.2.4) and are not part of DV-TLS-3.

IDs that name no driver deviation:
- **DRV-9.** CLOEXEC is at parity (GLB-05).
- **TLS-03 / TLS-05** as driver items: rustls has no partial-record retry state and no error queue (§4.3.4, 06 §3.2).

Not deviations:
- `zoo_add_auth` waking IO (timing, DSP-12);
- poll timeouts rounded up;
- EINTR retry;
- cross-thread publication order within C's envelope (§3.5, DSP-09);
- mt `zoo_a*` after close does no app-thread flush (DSP-11);
- TLS record boundaries and ciphertext read sizes (07 P6);
- bytes received on a parked TLS attempt are discarded (a server sends nothing before the ClientHello);
- the Windows waker's peer check and the missing `SO_REUSEADDR`;
- the `close_notify` alert on a graceful TLS close (TLS-06, wire only, 07 P6; §4.3.5).

Kept for parity:
- callbacks before init returns;
- exactly two threads with default attributes;
- auth completions on the IO or closing thread;
- sync bypasses the FIFO;
- items published after the dispatch thread exits are discarded;
- unsent frames flushed before CLOSE_OP;
- blocking getaddrinfo on the IO thread;
- st inline flush, also after a submit that fails at the serialization stage (§6.5);
- st close returns the flush rc and mt close returns ZOK;
- the waker pipe and its fd count;
- untouched interest outputs on early returns;
- `zoo_lock_auth` gating of IO-side auth processing, with `auth_lock` a default mutex as in legacy;
- Cyrus dispose timing (SAS-13): none at teardown, at the next START on a reconnect (zk_sasl.c:215-217), and after the transport close at destroy (zookeeper.c:719-749);
- TLS files re-read on every attempt and probe (TLS-02); no close_notify on a teardown, a forced close or the probe; after a fatal TLS error that rustls detects, one non-blocking send attempt of its fatal alert when no earlier ciphertext is waiting, as OpenSSL writes it (§4.3.5);
- plaintext decrypted before close_notify or an alert is processed before the teardown (TLS-05);
- probe TLS failures are logged with the legacy lines and never tear down the connection.

---

## 12. Verification specific to drivers

1. **Frame rule.** In every conformance callback, a test hook (`zkc_ev`) records `_Unwind_Backtrace` or `backtrace()` symbol prefixes. CI asserts that no `zkrs_`, `_ZN` or `_R` frame is below an application callback on any thread, including Cyrus callbacks. A C++ case throws from an st watcher, an mt log callback during `zoo_awget`, and a ZCLOSING auth completion, and expects the exception to propagate to the application (DSP-14 [diff]); a follow-up call delivers the requeued items.
2. **Ordering.** The CONNECTED watcher comes after the session-established INFO line (PER-ROLE, both libraries). "Calling COMPLETION_*" lines carry the dispatch thread's id. The OR-* and CL-* corpus covers these cases.
3. **Close matrix.** foreign, dispatch and IO thread × {connected, connecting, TLS handshaking, expired, SASL-failed} × {sync waiter, auth pending}. In the SASL-failed state the teardown has already completed the sync waiter and the auth completion with ZAUTHFAILED, so the close has nothing pending (DEV under DV-FIX-3). Asserts the `final_close` thread, the THREAD item sequence, blocking bounds, the invoked and discarded sets, the SASL DISPOSE position (after the transport drop and before RELEASE; none at teardown; immediately before START on a reconnect), and the rc.
4. **st close from a session-event callback during teardown** gives ZCLOSING to pending requests (P-1 [diff]).
5. **LA-01** parity through AUTH_LOCKPOINT, including the auth-resend site. `auth_lock` is checked to be a default-attribute mutex, as legacy initializes it (mt_adaptor.c:253). No test relocks it on the same thread, because that is undefined for a default mutex, as in legacy.
6. **Waker stress.** 10⁶ async calls while the IO thread is stalled in a log callback: no ZSYSTEMERROR, at most one byte in the pipe.
7. **loom** over the waker, prolog/epilog, the dispatch exit condition, publish-on-take, nested close from an IO callback (no deadlock), and SASL ordering (no START/STEP overlap; DISPOSE immediately before a reconnect's START, and after the transport close and before RELEASE at destroy).
8. **Fault-injecting transport.**
   - EAGAIN at every offset, partial writes, a priority insert while the head is pinned (P-3), forced close with a partial head and forced close with `unacked > 0` under TLS (in both the request fails with ZCONNECTIONLOSS, and the next connection starts at a frame boundary with none of those frames, and a request submitted after the trigger arrives whole, behind the auth frames and SetWatches).
   - TLS records carrying several frames and frames split across records; a stalled handshake (P-4); EOF and RST during the handshake (ZSSLCONNECTIONERROR).
   - A TLS config failure on both paths (ZSSLCONNECTIONERROR, EINVAL, then ZOPERATIONTIMEOUT; immediate-path outputs `-1/0/0` and the un-reset `last_recv`).
   - **Plaintext before a failure:** an expiry ConnectResponse plus close_notify in one TLS read gives ZSESSIONEXPIRED; a final reply plus a fatal alert in one read delivers the reply, then a ZCONNECTIONLOSS teardown; a good record followed by a corrupt record gives the good record's frames first (this also checks the pinned rustls release, §8.2.2).
   - **Fatal alert discarded:** a bad record with `ct_out` not empty and a barrier pending, and the same with a SASL result pending, sends no alert: neither `tls_flush` nor `pump_out` puts it on the wire before the teardown.
   - **st submit flush:** a submit that fails at the serialization stage (closing handle, bad value buffer, invalid multi sub-op) still flushes earlier queued requests; a flush failure there gives the forced close with state 999 and ZMARSHALLINGERROR; ZBADARGUMENTS, ZINVALIDSTATE, ZNOWATCHER and ZUNIMPLEMENTED send nothing.
   - **Forced close delivery:** a flush failure inside `zoo_a*`, from an application frame and from a callback, runs no callback in that call and returns the call's rc with state 999; the next `zookeeper_interest` and, separately, the next `zookeeper_process` deliver the CONNECTING event (only if the state was connected) and ZCONNECTIONLOSS for everything pending at the trigger; a rebalance that a re-resolution finds inside `zookeeper_interest` delivers them in that call; a close before T3 starts gives ZCLOSING and no event, for the snapshot and for later requests. In mt, `zoo_set_servers` from an application thread and from a dispatch callback delivers on the IO and dispatch threads, with the state 999 when the call returns, and a mid-send IO thread's `handle_transmitted` after the trigger is ignored and leaves frames queued after the trigger unstarted.
   - **Requests after the trigger:** in st, a `zoo_a*` before the next driving call and one from a CONNECTING callback; in mt, a submit from another thread with the IO step forced to run before it and after it. Each returns its own rc, no second trigger fires, nothing of the request is sent before the next connection, and the transcript there reads ConnectRequest, auth frames, SetWatches, request. A sync waiter in the snapshot wakes with ZCONNECTIONLOSS and its outputs untouched; a sync waiter on a later request gets the reply. A `zoo_add_auth` after the trigger completes from an AUTH reply on the next connection. A second `zoo_set_servers` move before the IO step leaves one snapshot and one CONNECTING event. A `zoo_set_servers` move during a suspended teardown keeps that teardown's cause and rcs.
   - **SASL failure delivery:** for a server error reply, a mechanism failure and an address failure, in st and mt: the AUTH_FAILED event, ZAUTHFAILED for sync, async and auth, the SASL rc from st `zookeeper_process`, no DISPOSE until the final close, and the mt IO thread exits while the dispatch thread drains.

   The TLS cases run `RustlsConn` against an in-process rustls server in zk-testkit, and against real servers in the 08 §12 matrix (IS-7).
9. **TLS-04.** An st loop that selects with the returned tv gets every pipelined reply without a spurious ZOPERATIONTIMEOUT; an `events == 0` call with buffered plaintext dispatches it.
10. **SIGPIPE.** A peer reset during plaintext and TLS writes, with the default SIGPIPE disposition, on Linux and macOS (FreeBSD best effort, Tier 3, 08 §13): the process survives and gets ZCONNECTIONLOSS (GLB-01).
11. **Probe.** Plaintext and TLS probes against `"rw"`, `"ro"`, `"rw\0x"`, a silent server, a refused port and a TLS config failure: result, bounds, no fd leak, the logged lines.
12. **st granularity**, interest outputs left untouched on early returns (O1), and DRV-7.
13. **Stack budget** (§8.7) including TLS handshakes, and the closure golden per provider (the `cargo tree` diff of 01 §3.3 R4, 08 §10 G1).
14. **Windows (M4).** The same suites on Windows mt and st, plus waker peer-check cases, a refused connect in state 7 (ZCONNECTIONLOSS on a T/3 tick) and a refused connect in state 1 (ZOPERATIONTIMEOUT after 2/3·T, as legacy), and TLS file paths in UTF-8 and in the ANSI code page (TI-06).
15. **close_notify.** A graceful TLS close sends exactly one `close_notify` record after CLOSE_OP and never waits for the peer's alert; teardown, forced close, a close with no CLOSE_OP and the probe send none; a peer that does not read does not extend the close beyond its budget. A verify failure and a corrupt record each send the fatal alert record once when no earlier ciphertext is waiting, and nothing when it is; a received alert, close_notify or EOF sends nothing. The wire-trace comparisons of 08 allow the extra record.
16. **Before-blocking barrier.** With LOG items pending, the step returns `ZKRS_MORE` before the `resolve` and `rng_reseed` leaves and before the TLS file reads of `new_conn`, and the log callback sees the lines before the blocking call starts.
17. **Native bounded hand-off.** With a consumer that stops taking results and one-shot events: the read gate closes when the undelivered-item count reaches `dispatch_queue_capacity`, requests and pings are still sent, the connection ends with ConnectionLoss after about 2/3·T, and the session reconnects with its watches re-registered while the consumer is still stalled, because the gate applies only in phase Established (TLS handshake records, the ConnectResponse and SASL frames are read at the capacity, and the gate closes again once the session is established); with a consumer that resumes (takes or drops items), the IO side reopens the gate on the wake. Completed `ResponseFuture`s that are never awaited count; persistent-watch events do not. Non-frame items (teardown fakes) are routed with the gate closed.

---

## Interfaces defined

- zk_driver::outbox::Item<T: zk_proto::Tokens> {Log, Result{token, result, delivery_logs}, AuthResult{token, rc}, Watch{event, watchers, delivery_logs}, Session{state, watchers, delivery_logs}, WatchTerminated{watchers, reason: TerminateReason} (native only, no zkrs_item kind, never reaches zk-ffi), AuthLockpoint, Sasl(SaslItem{call: zk_proto::SaslCall{gen: u32, kind: Start | Step(Option<SecretBytes>) | Dispose}, addrs: Option<(SocketAddr, SocketAddr)> (local, peer) for Start, written by zk-ffi as platform sockaddr_in/sockaddr_in6 images in zkrs_bytes}), Thread{Join|Detach, Io|Dispatch}} and Step {Done(rc), More, Release, Exit}; zk-ffi maps them to zkrs_item kinds LOG, RESULT, WATCH, AUTH_LOCKPOINT, SASL{START,STEP,DISPOSE} (zkrs_sasl_call {kind, gen, local, peer, token}), THREAD and to zkrs_step DONE/MORE/RELEASE/EXIT. Barriers (05 §5): sync RESULT in the mt IO step and close (a run of consecutive Direct results with no callback item between them shares one barrier), each auth group, AUTH_LOCKPOINT, SASL START and STEP, st Session/DrainPoint, every THREAD item (JOIN or DETACH, IO or DISPATCH), and "before blocking with a non-empty outbox" (before poll waits, the close flush, the RW probe, the Blocking leaves resolve and rng_reseed, and the TLS file reads of new_conn). SASL DISPOSE is not a barrier and has legacy timing (SAS-13): none at teardown or forced close; on a reconnect immediately before START in the same outbox (zk_sasl.c:215-217); at destroy after the transport close and before RELEASE (zookeeper.c:719-749).
- Outbox rules: one outbox per driving call, drained by the calling thread; mt FIFO-bound outputs are publish entries, appended to the dispatch FIFO by zkrs_outbox_next when reached (after the preceding LOG items of the same outbox); untaken items and a suspended continuation are requeued on unwind and delivered first by the next driving call; no lock is held when a step or zkrs_outbox_next returns; lock order auth_lock (C) -> io -> core -> fifo.
- Callback-frame contract: no application code (completion, watcher, auth, log, Cyrus, getpass) runs with a Rust frame below it, on any thread. mt IO thread: C loop over zkrs_io_step / zkrs_resume; dispatch thread: C loop over zkrs_wait_dispatch (batch); thread ends: zkrs_thread_exit(role) may run the final close and return RELEASE.
- zkrs entries used by the drivers (05 owns zkrs.h): zkrs_handle_new(args, vt, host, &core, ob, &errno); zkrs_handle_retain/release/abandon; zkrs_thread_enter(core, role); zkrs_thread_exit(core, role, ob, rc); zkrs_io_step(core, ob, rc); zkrs_wait_dispatch(core, ob, rc); zkrs_interest(core, &zkrs_interest_out{intptr_t fd, int32 interest, int64 timeout_ms}, &written, ob, rc); zkrs_process(core, events, ob, rc); zkrs_close(core, role, ob, rc); zkrs_resume(core, ob, rc); zkrs_interest_resume(core, &zkrs_interest_out, &written, ob, rc); zkrs_sasl_result(core, gen, view); zkrs_submit(..., token, QUEUED|DIRECT, ob); zkrs_add_auth_check(core, cert, cert_len) (stages 1-3 of 05 §12.1; no lock, no outbox; called before zoo_lock_auth, as zookeeper.c:5309-5328); zkrs_add_auth(..., ob); zkrs_set_servers(core, hosts, ob, rc) -> zkrs_step (driven by zks_drive through its log-flush barriers); zkrs_peer_addr(core, out) (live getpeername, 05 §17); zkrs_outbox_next/finish/requeue.
- Host vtable as used by the drivers (CC-6 (00 §4)): Pure discard_token, snapshot_default_watcher, store_client_id, log_enabled, conn_permute_disabled, rng_random, rng_drand48; Blocking rng_reseed, resolve (called only with the &Unlocked proof). No TLS entries, no app-reaching entries.
- zk-proto contract used (DR1-DR5, specified in 02): Output::AuthLockpoint barrier at each legacy zoo_lock_auth site under LEGACY_C; SaslCall{gen: u32, kind: Start | Step(Option<SecretBytes>) | Dispose} with at most one Start/Step outstanding, Dispose yielded immediately before the Start of a reconnect and by destroy after CloseTransport (never by teardown or forced close; the SASL generation bumps at teardown), and handle_sasl_started/handle_sasl_stepped(gen: u32, ...); handle_sasl_addr_failed(gen: u32, which: SaslAddr{Local, Peer}, now) when getsockname or getpeername fails (core log sites, no START); handle_resolved(gen: u64, ...) for the resolver generation; last_teardown_cause() -> Option<Cause> (Cause public in zk-proto; read by the native drivers for zk-client's DisconnectCause); TransportFailure::TlsConfig{step: TlsConfigStep, file: JString} (file for the log line only, from TlsConnector::path_for), TlsResource{at: TlsResourceAt, errno} and TlsHandshake{errno}; handle_rw_probe(ep: Endpoint, outcome: ProbeOutcome, now) logs probe TLS failures (ProbeTlsFailure::Config{step, file}); per-host getaddrinfo lines emitted by handle_host_resolved(gen: u64, HostResolution, now) as each host's result is passed in, then handle_resolved(gen: u64, shuffle, now) after the last host and the reseed; Action::Connect{endpoint, tls} and ProbeRw{endpoint, tls} with zk_proto::Endpoint{addr: SocketAddr, host: HostToken}.
- zk_driver::IoCore::{interest_step(now) -> InterestOut{rc, written, fd, bits, timeout_ms}, process_step(events, now) -> rc}: shared by mt and st; Transport {None, Connecting, Plain, Tls(TlsTransport), Parked}; rx_pending for unconsumed plaintext; buffered_ready() gives tv 0 and implies READ; both steps call run_pending_teardown first, after dropping a condemned socket, and interest_step calls it again after handle_resolved. O1: written=false exactly where C returns before writing (unrecoverable; resolve failure with no socket); DNS failure with a socket writes outputs.
- TLS I/O path: on TCP-connect completion the driver calls new_conn first, with no driver lock held, then handle_tcp_connected(now at TCP completion) on success; Config{step} -> TlsConfig{step, file} (ZSSLCONNECTIONERROR, EINVAL, Parked, READ only, un-reset last_recv on the immediate path); Resource{at} -> TlsResource{at, errno} teardown; handshake failure, EOF or recv error -> TlsHandshake{errno} teardown; completion -> handle_transport_ready and flush in the same call. Data phase: up to 64 KiB ciphertext per read; plaintext decrypted before a close_notify, alert, record error or recv error is processed before the failure is acted on; every complete frame processed (TLS-04); plaintext acknowledged to proto only after its ciphertext is fully sent; all ciphertext through TcpSock::send (no SIGPIPE); close_notify only on a graceful close (after a fully flushed CLOSE_OP and the POLLIN wait, non-blocking within the remaining close budget, no wait for the peer's alert; TLS-06, wire only), never on a teardown, forced close or probe; after a fatal TLS error that rustls detects, one non-blocking send attempt of its fatal alert when ct_out is empty (alert_attempt, as OpenSSL writes it), else the alert is taken out of the session and discarded at once (§4.3.5).
- zk_driver::net::tls::{TlsConn{is_handshaking, wants_read, has_ciphertext, handshake, feed (never discards plaintext; latches failures), feed_eof -> Result, read_plaintext (plaintext first, the latched failure on the next call), write_frame, take_ciphertext, send_close_notify}, TlsConnector{new_conn(&self, &Unlocked, TlsPurpose{Main, Probe}, &zk_proto::Endpoint) -> Result<Box<dyn TlsConn>, TlsSetupError{Config{step: zk_proto::TlsConfigStep}, Resource{at: zk_proto::TlsResourceAt}}> (no time argument; the verifier uses the rustls time provider), path_for(step) -> Option<&[u8]>}, TlsFailure{Handshake, Data, Closed}, TlsTransport (driver-side)}, all in net/tls/mod.rs, with signatures owned by 03 §8.2.1; module names per 06 §3.1 (mod, conn, connector, pem, x509, trust, legacy_verify, v1_leaf, identity, legacy_pem). One implementation: RustlsConn over rustls::ClientConnection with memory buffers. RustlsConnector {Legacy{ca, cert, key: Option<LegacyPath{raw, open}>, passwd, env_allowed, verify}, Native}; on Windows a cert-string path opens as UTF-8 through the wide API first, then as the ANSI code-page conversion that zk-ffi supplies, when the bytes are not valid UTF-8 or the first open fails with not-found: one ClientConfig per attempt and per probe, legacy step order and log lines, resumption disabled, provider passed explicitly; trust inputs, verifier and key loading per 06 §3.4-3.6 (one trust loader, trust.rs, for drop-in and native; CA file: CERTIFICATE, X509 CERTIFICATE and TRUSTED CERTIFICATE blocks, TRUSTED CERTIFICATE aux data by the OpenSSL rules, CRLs parsed and counted but not used, a file that yields neither a certificate nor a CRL fails the CaFile step with "Failed to load CA file %s"; default paths: SSL_CERT_FILE/SSL_CERT_DIR only when tls_env_allowed, otherwise the compile-time ZK_TLS_DEFAULT_CERT_FILE/ZK_TLS_DEFAULT_CERT_DIR (configure-recorded OPENSSLDIR from the openssl binary of the OpenSSL installation the legacy probe selected, else the 01 §6.1 platform list applied on the build host by configure/CMake or by zk-driver's build.rs for a native cargo build), openssl_probe::probe() never called, OpenSSL hash-name lookup in directories, the DefaultVerifyPaths step fails only on a resource error; keys: unencrypted PKCS#8, PKCS#1 RSA, SEC1 EC, encrypted PKCS#8 PBES2 (PBKDF2 with HMAC-SHA1/SHA224/SHA256/SHA384/SHA512, or scrypt; AES-128/192/256-CBC or DES-EDE3-CBC), traditional PEM encryption (EVP_BytesToKey MD5; AES-*-CBC, DES-EDE3-CBC, DES-CBC), scrypt over 32 MiB rejected before allocation, PKCS#12 PBE and ciphers or PRFs outside these sets rejected (DV-TLS-5), PBES1 failing in both cores under the reference default providers, in the FIPS build only unencrypted keys and PBES2 with PBKDF2-HMAC-SHA256, -SHA384 or -SHA512 and AES-128/192/256-CBC (DV-TLS-6), same-type key/cert mismatch fails at ClientKey; a NULL path prints "(null)" on every platform; LegacyChainOnly rules (a)-(d) plus the v1-leaf path (v1_leaf.rs: leaf signature by its issuer through the provider's SignatureVerificationAlgorithm, issuer chain to an anchor by OpenSSL's rules, v1 intermediates rejected, v1 self-signed anchors accepted; v3 leaves through webpki, then the keyCertSign post-check, the rejected-issuer post-check and the OpenSSL path post-check v1_leaf::verify_v3_path, all of which must accept); WebPki for native, or rustls-platform-verifier with the native feature tls-platform-verifier).
- zk_driver::Poll: `poll(&self, fds, timeout_ms)`, Send + Sync; default RustixPoll (rustix::event::poll); zk-ffi may inject a WSAPoll implementation on Windows (§8.8); not a host vtable entry. Windows: mt and st drop-in drivers and the native threaded driver from M4, no tokio driver; if the M4 spike fails, Windows ships on the legacy core for that release (DV-BLD-7) and the Rust core follows on Windows in the first release whose Windows build passes the M4 exit criteria of 08 §15.3.
- zk_driver::Waker: AtomicBool-coalesced waker, one per handle; Unix non-blocking pipe without CLOEXEC; Windows loopback socket pair with a peer check; wake() writes at most one byte per false->true transition.
- zk_driver::net::TcpSock (rustix::net): CLOEXEC only when the shim runtime sock_cloexec flag is set; TCP_NODELAY with the legacy WARN on failure; SO_NOSIGPIPE on Apple for every socket; send with NOSIGNAL on Linux, Android, BSDs and illumos; SO_ERROR on establish (and on T/3 ticks in state 7 on Windows; state 1 keeps the legacy receive-timeout failover); getpeername/getsockname only by the IO owner.
- RW probe: next endpoint; connect <= 1 s; TLS handshake <= 1 s with a fresh Probe session; send "isro" and one read sharing 1 s; success iff the zero-filled 10-byte buffer starts with 'r','w',0; socket closed on every path; result reported as ProbeOutcome, never a teardown; waker in the poll set (mt).
- st async submit: one non-blocking flush pass after Ok(Queued) and after SubmitError{stage: Enqueue} (ZMARSHALLINGERROR), as C's nonblocking_send; no pass for Validate-stage codes (ZBADARGUMENTS, ZINVALIDSTATE, ZNOWATCHER, ZUNIMPLEMENTED) or Submitted::Local; a flush failure calls handle_forced_close and the call returns its own rc, with state 999 and no callback in the call; the next zookeeper_interest or zookeeper_process delivers the forced close's event and the results of the trigger's snapshot; a submit after the trigger stays queued for the next connection, and its flush pass sends nothing and cannot fail.
- zk-driver crate: normal deps zk-proto, zk-jute, rustix(std,net,event,pipe,use-libc); feature tls with provider features tls-ring (default provider), tls-aws-lc-rs and tls-aws-lc-rs-fips (each implies tls; tls with no provider or with more than one is a compile_error!), selected by --with-rust-tls-provider=ring|aws-lc-rs|aws-lc-rs-fips (CMake ZK_RUST_TLS_PROVIDER, Maven rust-tls-provider); zk-ffi has the same four feature names and forwards them; lint and test jobs use explicit feature sets, never --all-features; optional tokio (cfg(unix) only) and std-thread, never enabled by zk-ffi; zk-ffi never depends on zk-client. TLS closure per 06 §3.11: rustls, rustls-pki-types, rustls-webpki, ring (or aws-lc-rs with aws-lc-sys or aws-lc-fips-sys), untrusted, zeroize, pkcs8, pkcs5 0.7, der, spki, pem-rfc7468, base64ct, aes, des, cbc, cipher, md-5, sha1, sha2, pbkdf2, hmac, scrypt and rustix (libc backend) with their transitive closure; the FIPS build omits pkcs8 encryption, pkcs5 pbes2/3des/sha1-insecure, pbkdf2, scrypt, hmac, sha1, aes, cbc, cipher, des and md-5 (01 §3.3 R3); no rustls-pemfile, no rustls-native-certs, no rustls-platform-verifier, no openssl-probe. FIPS build: aws-lc-rs fips (CMake and Go on the build host), rustls fips, default_fips_provider with require_ems and only FIPS-approved suites, groups and signature algorithms, provider.fips() asserted at handle creation and ClientConfig::fips() checked on every per-attempt config (a false result fails the attempt as TlsResource{at: ContextNew}), validated on Linux x86_64 and aarch64. One closure golden per provider build (zk-ffi-<target>.txt, -aws-lc-rs.txt, -aws-lc-rs-fips.txt, -notls.txt) and build-specific cargo-deny exceptions for aws-lc-sys and aws-lc-fips-sys; both aws-lc builds ship only after the ASF legal review of aws-lc-sys and aws-lc-fips-sys (00 §6).
- Native close (close_mode = Drain; threaded and tokio drivers): §5.4 steps 1 and 7, §5.9 and §5.10 are the drop-in's; a terminal state does not end the native IO side: with no transport it waits on the waker (tokio: notify) until close, detach or the last-clone drop arrives; on a close or the last-clone drop, queue_close_op() returns false, close_step reports Complete at once, and the IO side calls destroy(now), routes the outputs, executes Sasl(Dispose) when proto yields it, and exits; every native close runs begin_close(now) and queue_close_op() under the core lock in the API call; the IO side keeps running its interest and process steps and calls close_step(now) after each pass; on CloseProgress::Complete it sends close_notify under §4.3.5 when the close was graceful, calls destroy(now), routes the outputs, executes CloseTransport and Sasl(Dispose) and exits; detach() calls abandon(now), and the IO side routes its outputs and exits.
- Native bounded hand-off (dispatch.rs; threaded and tokio drivers): the IO side routes Output items in order, through zk_driver's RouteSink<T> (route(&self, Item<T>), Send + Sync, implemented by zk-client), into slots, per-watcher queues and the session state (04 §4), with no dispatch thread; it counts undelivered items (results and auth outcomes in slots with a live receiver that has not taken them, one-shot watch events not yet returned; persistent-watch events not counted), capacity dispatch_queue_capacity (default 4096 items); a count at or above the capacity closes the read gate in phase Established only (no socket read, no handle_read, READ dropped from interest; TLS handshake records, the ConnectResponse and SASL frames are always read) while requests and pings are still sent and the receive timer runs, so a stalled consumer ends the connection with ConnectionLoss after about 2/3·T; overshoot is at most one read of frames plus non-frame items, which are never held back; a receiver that takes or drops an item decrements the count and wakes the IO side. Per-watcher stream buffers (persistent_watch_buffer) and max_pending_requests are unbounded unless set (04).
- Sync calls (drop-in): C wrapper checks zks_current_role == IO -> ZSYSTEMERROR/EDEADLK; calloc'd zks_sync (ZSYSTEMERROR on OOM); exported zoo_aX with ZKS_SYNC_MARKER; DIRECT -> Route::Direct; completed by a RESULT barrier item on the IO (or closing) thread: zks_sync_complete has zk-ffi's abi::raw writers build the legacy C result objects into the heap record (same malloc/calloc choice and sizes as legacy) and signals; the waiter copies out after wake through zk-ffi's raw copy-out writers (legacy buffer_len and path_buffer NUL rules); a teardown result, including ZCONNECTIONLOSS on the RO->RW switch (DV-FIX-9), leaves every output untouched (05 §13, 07 ST-08).
- Lifecycle: refs/close_requested/finalizing/io_exited; zkrs_close keyed on the C-supplied role (FOREIGN, DISPATCH, IO) emitting THREAD{JOIN|DETACH} barrier items (every THREAD item is a barrier) after the ZCLOSING auth group; final_close runs in the call that drops the last ref (CLOSE_OP flush <= 3000 ms, POLLIN wait <= 1500 ms, the TLS close_notify when the flush completed, destroy, transport drop, then SASL DISPOSE, as legacy destroy) and ends with ZKRS_RELEASE; forked-child close only closes fds reachable under try_lock and leaks everything else.

## Decisions

| Decision | Rationale | Alternatives |
|---|---|---|
| Use the outbox model everywhere. Driver steps return an ordered outbox; C drains it and runs all application code from C frames, including on the mt IO thread (a C loop over zkrs_io_step) and the dispatch thread (a C loop over zkrs_wait_dispatch). | The only model under which C++ exceptions and longjmp from any callback behave exactly as in C (DSP-14 RE). Rust stack use below callbacks is zero. Cyrus callbacks and log callbacks also run in C frames. The drivers stay forbid(unsafe) with no function pointers in Rust. | Push trampolines called from Rust (exceptions abort); trampolines on library threads only (exceptions on IO-thread log or auth callbacks still meet Rust frames). |
| Each call owns the outputs its own proto calls produced. mt FIFO-bound outputs are published when zkrs_outbox_next reaches them, after the preceding log items. Cross-call interleaving is accepted as inside C's envelope (07 P1/DSP-09). | Exact per-thread C order (log before item; the CONNECTED INFO line before the CONNECTED watcher) with no reservation slots, which would deadlock when a close from an IO-thread log callback joins a dispatch thread waiting on an unfilled slot. | Push to the FIFO under the core lock (logs then follow items); reserved FIFO slots (deadlock risk); a barrier after every log line (cost). |
| Barriers are c-shim's set: Direct results in mt (one barrier per run of consecutive Direct results with no callback item between them), auth groups, AuthLockpoint, st Session/DrainPoint, SASL START and STEP, every THREAD item; plus "before blocking" in the driver. SASL DISPOSE is ordered by the outbox, not a barrier. | Reproduces C's inline-callback-then-continue semantics (ZCLOSING when an st session-event callback closes; waiter signalled before later frames; auth callback before handle_error continues). DISPOSE has no result and no continuation that depends on it. | Eager teardown (visible rc differences); DISPOSE as a barrier (an extra round trip with nothing to wait for). |
| SASL DISPOSE has legacy timing (SAS-13 RE): a teardown or forced close emits none; on a reconnect DISPOSE comes immediately before START in the same outbox; in the final close it comes after the transport drop and before RELEASE. The SASL generation bumps at teardown, so a late result for the old session is stale. | Legacy disposes the old connection at the top of `zoo_sasl_connect` (zk_sasl.c:215-217), and `destroy` closes the socket (zookeeper.c:719-723) before it disposes the Cyrus client (744-749). Keeping both points means no Cyrus callback (a SASL_CB_LOG, for example) runs at a point where legacy runs none. | DISPOSE at every teardown, right after CloseTransport (a Cyrus callback can then run earlier than in legacy); DISPOSE before CloseTransport at destroy (an order legacy never produces). |
| Threads are created, joined and detached by C (pthread_create with NULL attributes); joins and detaches are THREAD items; the role is a C argument from C TLS. | C default stack, signal mask and naming come for free; spawn failure is handled in C with errno; no std::thread::current() and no TLS destructors (R7). | Spawner trait through the vtable; std::thread with a queried stack size; join/detach vtable leaves (CC-3 (00 §4) keeps blocking app-thread work out of the vtable). |
| Sync calls use the c-shim heap record: a RESULT barrier item on the completing thread has zk-ffi's raw writers build the result into the record and signals; the waiter copies out through zk-ffi's raw copy-out writers. | Keeps ABI-06 interposition, C's IO-thread completion point and its order relative to the FIFO, C's checked ZSYSTEMERROR on allocation failure (RC-13), and no library thread writes another thread's stack. | Rust SyncSlot (loses interposition); copy-out into caller buffers on the IO thread (writes another thread's stack). |
| Forced close is a full teardown split at the API-call boundary: state 999 synchronously, the pending work taken as a snapshot and no callback in the call; mt fd closed by the IO thread, st by the caller at once; the event and the snapshot's ZCONNECTIONLOSS results come from the IO owner's next driving call, with no delay and an unmoved cursor; a request submitted after the trigger stays queued for the next connection. | DV-FIX-8. C's later ZRUNTIMEINCONSISTENCY or timeout, its replay and its mid-frame resume harm correct applications. C runs no callback inside these API calls, and the IO owner is where every other teardown runs. The snapshot makes the result independent of when the IO owner runs and limits the deviation to work pending when the connection closed. | C's forced close without cleanup. Running the teardown's callbacks in the API call's own frame (callbacks inside `zoo_a*`, and auth completions on an application thread in mt). |
| st async calls run the inline flush after every submit that reaches the serialization stage, success or ZMARSHALLINGERROR, and skip it only for C's early returns. | `nonblocking_send` flushes whatever rc is (zookeeper.c:4153-4161, 4199-4206, 4970-4978, 5070-5077). Wire timing and the forced-close trigger then match C. | Flush only after a successful submit (changes wire timing and when the forced close fires). |
| zoo_lock_auth parity through AUTH_LOCKPOINT barrier items: C locks and unlocks auth_lock in the drain at each legacy site; Rust never takes it. | LA-01 parity with lock order auth_lock -> core and no lock held across a callback (CC-7 (00 §4)). | Rust-held auth gate across calls (inversion risk); IO side never takes the lock (visible). |
| zookeeper_interest leaves its outputs untouched exactly where C returns before writing them; with a socket, a DNS failure runs timers and writes the outputs. | Untouched outputs are the application's own previous values, a deterministic C behavior. Only the SRV-06 liveness fix needs written outputs, because the socket may close in that call (CC-22 (00 §4)). | Always write fd=-1/0/T/60 (visible to st loops). |
| Sockets and the waker use rustix only. Socket CLOEXEC follows the runtime sock_cloexec flag; the pipe has no CLOEXEC. | Parity with --with-sock-cloexec and mt_adaptor.c:244; keeps syscalls interposable (use-libc); fits the closure. | socket2 (always CLOEXEC); eventfd or kqueue waker (fd-count differences). |
| TLS is RustlsConn in zk-driver for both profiles, sans-IO over memory buffers; the driver owns all socket I/O; one ClientConfig per attempt and probe; verifier chosen by Compat::tls_verify; types are proto's (Endpoint, TlsConfigStep, TlsResourceAt) and the trait signatures are §8.2.1's, which 06 §3.1-3.2 use. | Legacy uses OpenSSL; with rustls the library has no OpenSSL dependency closure, and rustls keeps all TLS state machine code in memory-safe Rust (06 §3.1). It keeps the unsafe budget in audited crates, needs no TLS vtable entries, gives SIGPIPE-free TLS writes and drain-all reads by construction, and lets native and drop-in share one tested path. One vocabulary for proto and drivers (CC-17 (00 §4)). | OpenSSL over memory BIOs in the shim (keeps OpenSSL in the closure and TLS code in C); a socket-owning TLS stream (SIGPIPE, two fd owners, hidden plaintext); driver-local TLS step and endpoint types. |
| Trust inputs and the verifier follow 06 §3.4-3.5: one trust loader (trust.rs) for drop-in and native; a CA file loads as X509_load_cert_crl_file loads it, and fails the CaFile step when the file cannot be read, when a PEM block is malformed (06 §3.4), when `ca` is NULL, or when it yields neither a certificate nor a CRL; default paths that fail only on a resource error; OpenSSL hash-name lookup in directories; the environment only when tls_env_allowed, otherwise the compile-time default locations, with no run-time probing; anchors only from OpenSSL-self-signed, CA-capable, purpose-compatible trust inputs valid at now, plus `TRUSTED CERTIFICATE` inputs whose aux trust list names serverAuth or anyExtendedKeyUsage (rule (d), 06 §3.5); a byte-identical leaf accepted only when it is OpenSSL-self-signed. | Matches what an OpenSSL-linked libzookeeper trusts and never widens trust (08 P6). The compile-time defaults keep the trust inputs fixed by the build, and the `tls_env_allowed` gate matches OpenSSL's `ossl_safe_getenv` in set-uid/set-gid processes. | Every CERTIFICATE block as an anchor and every file in SSL_CERT_DIR (trusts more than OpenSSL, accepts partial chains); a DefaultVerifyPaths failure on unusable locations (fails where legacy connects); openssl-probe at run time (bypasses tls_env_allowed and substitutes a system bundle for a missing path, which OpenSSL never does). |
| TLS handshake: TCP phase exempt, with tv = T/3 when recv_to <= 0, otherwise min(recv_to, T/3) (ST-16(a)); non-blocking handshake with a 2/3·T deadline from TCP completion; the connector runs before handle_tcp_connected; immediate connect keeps 0/999; a config error returns ZSSLCONNECTIONERROR with EINVAL, parks the attempt with READ only, and fails over on the receive rule. | ST-16 and CC-14 (00 §4) exactly; the call order keeps C's un-reset last_recv on the immediate path; only the spin, the unbounded block and the FD_SETSIZE hazard are removed. | Deadline from attempt start (changes failover timing); ZBADARGUMENTS on config errors (not what legacy returns); handle_tcp_connected before new_conn (resets timestamps C does not reset). |
| TLS plaintext is acknowledged to proto only after its ciphertext is fully sent; at most one chunk is inside the session. | Frame completion and last_send at C's point; bounded memory. | Acknowledge at write_frame (a frame counts as sent before its ciphertext is on the wire); unbounded session buffering. |
| TLS reads drain all plaintext and process every complete frame in the call; a close_notify, alert or record error is latched and acted on only after the frames decrypted before it; buffered plaintext gives tv 0 and implies READ. | Removes the legacy stall (TLS-04) with the smallest visible change, never lets an st loop spin with tv 0 and no events, and keeps SSL_read's data-before-error order, so an expiry reply before close_notify still gives ZSESSIONEXPIRED. | One frame per call under TLS (re-creates the stall); tearing down at the feed error (turns ZSESSIONEXPIRED into ZCONNECTIONLOSS). |
| The RW probe keeps C's single read and C's exact strcmp rule, with per-phase bounds and an always-closed socket. | Same probe outcome as C for every server reply; only the unbounded waits and the fd leak go. | Read until EOF (accepts split replies C rejects). |
| SASL failure is a full teardown in the call that sees it, as the digest path: AUTH_FAILED event, ZAUTHFAILED for all pending work, IO thread exit in mt, and a DISPOSE only at the final close. | DV-FIX-3 (ST-18). In C watchers never learn of the failure, async work waits until close, and an mt sync caller blocks until another thread closes. | C's silent state change. |
| Windows mt uses a loopback socket-pair waker and WSAPoll through the zk-driver `Poll` trait, with SO_ERROR checks on T/3 ticks only in state 7 (TLS, TCP connecting). State 1 keeps legacy's receive-timeout failover with ZOPERATIONTIMEOUT. | CC-33 (00 §4) puts Windows mt in scope at M4; the socket pair keeps legacy's fd count; the state-7 tick removes a hang that WSAPoll and legacy select share (FU, ST-16), and limiting it to state 7 keeps the state-1 rc, log line and failover timing of legacy (WIN-05 RE). | select() as legacy (FD_SET limits, missed connect failures); the SO_ERROR tick in state 1 too (changes rc, log line and failover timing); the legacy core for Windows (used only as the DV-BLD-7 fallback when the M4 spike fails). |
| Drivers live in zk-driver, whose normal dependencies are zk-proto, zk-jute and rustix, with TLS behind tls plus one provider feature (tls-ring, tls-aws-lc-rs or tls-aws-lc-rs-fips) and tokio behind an optional, Unix-only feature zk-ffi never enables. | Keeps native-only dependencies out of libzookeeper_* by construction and matches the pinned closure golden. One feature spelling for zk-driver and zk-ffi (01 §3.3 R3, §10). | Drivers inside zk-client (feature-unification risk); separate provider spellings per crate. |
| Three provider builds: ring (default), aws-lc-rs and aws-lc-rs-fips, exactly one per build. The FIPS build uses default_fips_provider with require_ems, only FIPS-approved suites, groups and signature algorithms, the FIPS module for every signature check, and the DV-TLS-6 key-format set; each build has its own closure golden and cargo-deny exceptions. | Gives distributions and regulated users a validated module (Linux x86_64 and aarch64) without changing the default build. The ASF legal review of aws-lc-sys and aws-lc-fips-sys is a release prerequisite for both aws-lc builds (00 §6). | ring only (no FIPS path); an unrestricted key loader in the FIPS build (MD5, DES and scrypt outside the module). |
| A graceful close sends close_notify after the fully flushed CLOSE_OP and the POLLIN wait, with non-blocking sends inside the remaining close budget and no wait for the peer; teardowns, forced closes and the RW probe send no close_notify. After a fatal TLS error that rustls detects, the driver in both profiles makes one non-blocking send of its fatal alert, and drops the alert when `ct_out` is not empty. | A clean TLS shutdown lets the server tell a closed session from a truncated stream. Legacy sends no close_notify (close_zsock: SSL_free, no SSL_shutdown, zookeeper.c:648-670), and the extra record is wire-only (TLS-06, 07 P6). The fatal-alert rule is what OpenSSL does on the non-blocking legacy socket: it writes the alert inside `SSL_connect` or `SSL_read`, before the C code sees the error, when no record write is pending, and `SSL_free` discards it otherwise. The server therefore sees the same alert as from legacy. The close rc and timing bounds stay as legacy. | No close_notify, as legacy; a bidirectional shutdown that waits for the peer's alert (extends the close); dropping the fatal alert after a TLS error (the server would see EOF where legacy sends the alert). |
| X.509 v1 leaves follow OpenSSL: accepted at any depth below a valid v3 chain through the in-tree v1-leaf verifier (v1_leaf.rs), v1 intermediates rejected, v1 self-signed anchors accepted; v3 leaves through webpki, then the same module's rules as the OpenSSL path post-check. | Operators follow the documented examples, and ssl/gencerts.sh makes v1 leaves on OpenSSL <= 3.1. The verifier is the acceptance path only for the v1-leaf case (for v3 leaves it can only reject what webpki accepted), uses the provider's signature algorithms, applies OpenSSL's chain rules and is differentially tested against OpenSSL 3.0 and 3.5. | Rejecting v1 leaves issued by a CA (a deviation that breaks the documented setup). |
| Pending LOG items are drained before every Blocking leaf call (resolve, rng_reseed) and before the TLS file reads of new_conn, through the before-blocking barrier. | The log callback sees each line before the blocking work starts, on the same thread and in C order; the callback-frame rule is unchanged. The cost is one round trip, only on those paths and only when LOG items are pending. | Deliver the logs after the blocking call (a log callback sees lines late, after a possibly long getaddrinfo or file read). |
| The IO side of the native drivers routes items itself into slots, per-watcher queues and the session state, and bounds the undelivered results, auth outcomes and one-shot events by dispatch_queue_capacity (default 4096). A count at the capacity closes the read gate in phase Established; requests, pings and the receive timer continue, and a reconnect's handshake frames are always read. Per-watcher queues and max_pending_requests are unbounded unless set. | Bounded memory between IO and the application without blocking the IO side and without a dispatch thread. A consumer stalled for longer than about 2/3·T ends the connection with ConnectionLoss; the session reconnects and its watches are re-registered. Unbounded per-watcher buffers match Java, so a slow watch-stream consumer costs memory and never stalls IO. | An unbounded hand-off (memory grows without limit under a stalled consumer); blocking the IO side at the capacity (pings stop and the session expires). |
| The tokio driver is Unix-only (cfg(unix)); on Windows the native API has the threaded driver only, from M4. | tokio's AsyncFd is a Unix API. The threaded driver's async adapter and the blocking facade (04) serve Windows async and sync users with one tested Windows IO path. | A Windows tokio driver over a second readiness model. |
| Windows TLS file paths open as UTF-8 through the wide API first, then, when the bytes are not valid UTF-8 or the open fails with not-found, as the ANSI code-page conversion that zk-ffi computes. | This is how OpenSSL opens a file name on Windows, so every path that worked with legacy works. The unsafe conversion stays in zk-ffi. TI-06 tests both cases. | UTF-8 only (breaks ANSI paths that legacy opened). |
