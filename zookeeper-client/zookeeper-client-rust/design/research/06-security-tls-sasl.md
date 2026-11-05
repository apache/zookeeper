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

# Authentication and Transport Security (TLS, SASL, digest auth, secrets)

## 1. Scope and decisions at a glance

This document covers transport security (TLS) and authentication for two consumers:

- the drop-in libraries `libzookeeper_{mt,st}` (C shim + `zk-ffi` + safe core);
- the native Rust API (`apache-zookeeper`).

The authentication covered is SASL and the `zoo_add_auth` digest/ip/super schemes.

C references are to `zookeeper-client/zookeeper-client-c` at 60da6eb50. The C locations this document relies on are: zookeeper.c:403-419 (send/recv), 648-670 (`close_zsock`), 1450 (`update_addrs` at init), 1472-1479 (SASL client creation after resolution), 1521-1535 (cert string), 2655-2723 (connect), 2831-2976 (`init_ssl_for_handler`/`init_ssl_for_socket`), 3015-3069 (SASL request/response), 3072-3095 (`check_events`), 3136-3200 (SASL dispatch; 3147-3150 and 3190-3193 call `zoo_sasl_mark_failed` without a log line), 2461-2522 (RO probe), 2055-2243, 5301-5350 (auth); zk_sasl.c:67-400 (engine; `_zsasl_fail` at 147-154 is the only source of "SASL authentication failed. rc=%d"), 405-666 (callbacks and block); zookeeper.h:35-44, 302-324, 576, 633-638, 690-697; ssl/gencerts.sh:43-116 and tests/TestClient.cc:965-987 (the `testSSL` fixture).

| Topic | Decision |
|---|---|
| Drop-in TLS backend | **rustls 0.23.x in Rust** (`zk-driver/src/net/tls/`, `#![forbid(unsafe_code)]`, cargo feature `tls`, enabled by zk-ffi), in one of three provider builds: `ring` (the default), `aws-lc-rs` and the FIPS build `aws-lc-rs-fips` (cargo features `tls-ring`, `tls-aws-lc-rs`, `tls-aws-lc-rs-fips`; §3.15). OpenSSL is not used anywhere (K9, 00 §3). |
| Trust decision (drop-in) | `Compat::tls_verify = LegacyChainOnly`: a custom `ServerCertVerifier` that reproduces OpenSSL's chain-only decision of zookeeper.c:2876-2890: CA file plus OpenSSL default verify paths, no name check, no SNI, and rule (b): an end-entity certificate that is OpenSSL-self-signed and byte-identical to a trust-set certificate is accepted after the validity check and the `ssl_server` purpose check, which an aux trust list naming serverAuth or anyExtendedKeyUsage satisfies (§3.5). An X.509 v1 leaf below a valid chain is verified by the in-tree v1-leaf verifier with OpenSSL's chain rules (§3.14); v3 leaves go through webpki and then the keyCertSign, rejected-issuer and OpenSSL path post-checks (§3.5). |
| Trust decision (native) | `Compat::tls_verify = WebPki`: hostname verification against the connect-string host token, SNI for DNS names (§3.12). |
| TLS phases | TCP connect and the TLS handshake are phases of the zk-proto machine. The TCP-connect phase stays exempt from the receive timeout, as in C; only its `tv=0` spin is fixed. The handshake is non-blocking and has a deadline of 2/3·T measured from TCP-connect completion, with teardown `ZOPERATIONTIMEOUT` (07 ST-16). |
| Visible state during the handshake | Reproduced. EINPROGRESS path: 7. Immediate-connect path: the pre-connect value (0 or 999) until the handshake completes, which is what C shows while it blocks inline. |
| TLS config failure (CA/default paths/cert/key) | CC-14 (00 §4): **`ZSSLCONNECTIONERROR`** from the driving call, `errno = EINVAL`, the legacy log line for the failing step, **no teardown**, no WRITE interest, no retry spin. The receive rule then fails over with `ZOPERATIONTIMEOUT` on both paths; the immediate path keeps C's un-reset `last_recv`. The default-verify-paths step fails only on a resource error (§3.4). |
| Cert string | `strtok_r` in the shim, with token semantics identical to `strtok` (FU: removes a data race). The shim's copy is zeroed and freed before init returns, and the handle does not keep it; Rust keeps its own copies of the fields. |
| SASL engine (drop-in) | Cyrus libsasl2, called only from hardened shim C (`shim/zks_sasl_cyrus.c`, `shim/zks_sasl_cb.c`) from SASL outbox items drained on the IO owner with no Rust frame on the stack (K2, K10; §4.7). The ZooKeeper-level quirks live in **zk-proto**. |
| Where SASL, auth and log callbacks run | Callback-frame rule, **C loop on every thread** (CC-1, 00 §4): every `zkrs_*` call that can produce application-reaching work returns an ordered outbox that C drains after Rust returns, including on the mt IO thread, whose loop is C. No vtable entry runs callbacks. |
| SASL failure | `Compat::sasl_failure = Teardown` (DV-FIX-3, ST-18; 00 §5.1): state AUTH_FAILED, an AUTH_FAILED session event, the socket closed, and `ZAUTHFAILED` for all pending sync, async and auth work, as C's digest-auth failure path. The st driving call returns the SASL rc as in C, the mt IO thread exits as in C, and the Cyrus connection stays until the final close, as in C (§4.5). |
| Shared SASL secret buffer | **FU (DAT-13; §4.7.6-§4.7.7; CC-25, 00 §4).** The calloc block keeps its exact layout and single-`free()` contract. Each ZooKeeper-driven `sasl_conn_t` gets the secret through a per-session trampoline copy. The library's own password callback runs with no ZooKeeper lock held and writes only into that copy. The process-wide password mutex guards only the short in-block copy on the direct Cyrus path. No internal lock is ever held across application code. |
| `zoo_add_auth` | Legacy list policy: append-only, reverse resend before SetWatches, one reply completes all pending, AUTH_FAILED terminal, duplicate AUTH frame while ASSOCIATING without SASL kept (`auth_dup_while_associating = true`). Token retained iff `ZKRS_OK` (CC-10, 00 §4). |
| `zoo_lock_auth` | CC-7 (00 §4): **AUTH_LOCKPOINT barrier items** at each legacy site (AUTH reply, teardown collection, close collection, auth resend). C does `lock; unlock` on `zh->auth_lock` with no Rust frame and no Rust lock. The lock is initialized as in legacy, with default attributes (mt_adaptor.c:253). `zoo_add_auth` holds the lock around `zkrs_add_auth` (order auth → core). LA-01 is `[diff]` parity. |
| RO→RW switch | Auth completions get a uniform `ZCONNECTIONLOSS` (`rw_switch_rc = ConnectionLoss`; DV-FIX-9, 00 §5.1), where C gives `ZOK`. |
| Graceful close | After the CloseSession exchange, both profiles send TLS close_notify without waiting for the peer's, then close the socket (TLS-06, wire only, 07 P6). Forced and error teardowns send no close_notify, and neither does the RW probe. After a fatal TLS error that rustls detects, the driver in both profiles makes one non-blocking send of the fatal alert that rustls queued; the alert is dropped when earlier ciphertext is still waiting in `ct_out`. This is what OpenSSL does inside `SSL_connect` and `SSL_read` in legacy (§3.2, 03 §4.3.5). |
| Secrets | `zk_proto::SecretBytes` over `zeroize` (CC-19, 00 §4). Decrypted TLS key bytes are `Zeroizing<Vec<u8>>`. The shim zeroes C buffers with `zks_secure_zero` on every return, and also on unwind with GCC and Clang (§4.7.7). Zeroization is the only control on memory: no `madvise` and no `mlock` (§7). Nothing secret is logged. |
| Deviations | TLS: DV-TLS-4 in every TLS build, DV-TLS-1/2/3/5 for `zookeeper_init_ssl` handles, and DV-TLS-6 in the FIPS build only (DEVIATE-VISIBLE, §3.13). DV-FIX-3 changes the SASL failure path (§4.5), DV-FIX-6 changes the `zerror(-15)` text (§3.8), DV-FIX-8 changes the auth rc and the point of completion on a forced close (§5.5), and DV-FIX-9 changes the RO→RW auth rc (§5.5). Everything else in this document is RE, FU or 07 P6 (TLS-03, TLS-06 and the TLS-08 errno text). |

## 2. Cross-document contracts this document uses

1. **Callback-frame rule (K2; CC-1, 00 §4).** No application code runs with a Rust frame on the stack, on any thread. Application code here means auth completions, Cyrus SASL calls (Cyrus calls the application's `sasl_callback_t` entries, `zoo_sasl_password_t.callback` and `getpass`), and log callbacks.
   - Every `zkrs_*` call that can produce application-reaching work returns `zkrs_step {DONE, MORE, RELEASE, EXIT}` and fills a `zkrs_outbox` on the C stack. C drains it with `zkrs_outbox_next` and calls `zkrs_resume` (`zkrs_interest_resume` in `zookeeper_interest`) after a barrier (`ZKRS_MORE`). An exception unwinding through the drain calls `zkrs_outbox_requeue`; the handle delivers untaken items at the start of the next driving call (CC-32, 00 §4).
   - The mt IO loop and dispatch loop are C (`zks_threads.c`) calling `zkrs_io_step` and `zkrs_wait_dispatch`. A C++ exception from a callback therefore unwinds through C frames only, on every thread (DSP-14 RE).
2. **Item kinds (CC-2, 00 §4; 05 owns `zkrs.h`).** `LOG`, `RESULT` (async, sync and auth, told apart by the `zks_req` mode), `WATCH` (node and session), `AUTH_LOCKPOINT`, `SASL{START, STEP, DISPOSE}`, `THREAD{JOIN|DETACH, IO|DISPATCH}`. This document uses `RESULT` (auth completions; CC-18: a Direct completion is a RESULT barrier item, no slot), `AUTH_LOCKPOINT`, `SASL` and `LOG`. The SASL item uses 05's `zkrs.h` names (`zkrs_sasl_call`, `zkrs_sasl_out`, `zkrs_sasl_result`), restated in §4.6.
3. **Host vtable (CC-6, 00 §4).** Pure: `discard_token`, `snapshot_default_watcher`, `store_client_id`, `log_enabled`, `conn_permute_disabled`, `rng_random`, `rng_drand48`. Blocking: `rng_reseed`, `resolve`. There are no TLS, SASL, auth-lock, logging or thread entries. No vtable entry reaches application code or `log_message` (K4).
4. **One `Compat`, owned by 02 (CC-15, 00 §4); values from 07.** Fields this document uses:

   | Field | `LEGACY_C` | `NATIVE` | Ledger |
   |---|---|---|---|
   | `sasl_failure` | `Teardown` | `Teardown` | ST-18, DV-FIX-3 |
   | `auth_completion` | `LegacyBroadcast` | `PerRequest` | AUT-03 |
   | `auth_resend` | `LegacyReverseFront` | `Forward` | AUT-01 |
   | `auth_dup_while_associating` | `true` | `false` | AUT-01 |
   | `rw_switch_rc` | `ConnectionLoss` | `ConnectionLoss` | ST-08, DV-FIX-9 |
   | `forced_close` | `Teardown` | `Teardown` | ST-13, DV-FIX-8 |
   | `tls_verify` | `LegacyChainOnly` | `WebPki` | TLS-01, TLS-11 |

   `Compat::LEGACY_C` is the one drop-in value and carries the core behavior fixes (02 §14.3; 00 §5.1). Of those, DV-FIX-3 (§4.5), DV-FIX-8 and DV-FIX-9 (§5.5) touch this document.

   The TLS handshake deadline (2/3·T from TCP-connect completion) and the config-failure rc are unconditional: each has one value, so neither is a `Compat` field (CC-15). The SASL reply xid is not checked in either profile and is not a knob: while the SASL gate is up only SASL frames are in flight and no pings are sent (state 2), so every reply in the Sasl phase is the SASL reply (SAS-02).
5. **`DriverMode` (CC-16, 00 §4)** is `{interest: LegacyExact|Precise, session_delivery: Inline|Dispatch}` only. No security behavior depends on it. The AUTH_LOCKPOINT is always emitted under `LEGACY_C`; it is a C no-op in st.
6. **Tokens.** An auth completion token is `CReq(NonZeroUsize)` holding a `zks_req*`. It is consumed exactly once, by RESULT delivery or by `Drop` → `discard_token` (CC-9, 00 §4). The Cyrus connection is never named by Rust: it lives in the shim's per-handle `zks_sasl_sess`, and Rust refers to it only by the SASL generation number.
7. **Thread identity (CC-4, 00 §4).** The role comes from C TLS (`zks_current_role`) and is passed as an argument. Rust never calls `std::thread::current()`.
8. **xids** are per handle (K8). SASL requests take the next xid from the handle's generator.
9. **Crate graph (K1, 00 §3).** `zk-jute ← zk-proto ← zk-driver ← {zk-client, zk-ffi}`, and `zk-client ← zk-recipes`. TLS code (`RustlsConn`, `LegacyChainOnly`, trust and key loading) lives in zk-driver behind feature `tls`, shared by both consumers. Native-only: tokio, tracing, native SASL, persistent watches and the recipes.
10. **Vocabulary (CC-17, 00 §4; 02 names).** `Output`/`poll_output`, `Action`/`poll_action`, `handle_tcp_connected`, `handle_transport_ready`, `handle_transport_failed(TransportFailure)`, `TlsConfigStep`, `TlsResourceAt`, `zk_proto::Endpoint{addr: SocketAddr, host: HostToken}`, `ProbeTlsFailure`, `SaslCall{gen: u32, kind}`, `SaslStep{token, done}`. These shared TLS types are owned by zk-proto (02 §4). Time is `Millis(u64)`.
11. **Naming.** Shim internals use `zks_*`; private ABI types and functions use `zkrs_*`. The shim lives in `zookeeper-client-c/shim/`.

## 3. Transport security (TLS)

### 3.1 Backend and placement

- **Library.** `rustls` 0.23.x (pinned), `rustls-pki-types`, `rustls-webpki`, with one crypto provider per build. There are three provider builds (§3.15): `ring` 0.17 (the default), `aws-lc-rs` (non-FIPS `aws-lc-sys`) and `aws-lc-rs-fips` (aws-lc-rs feature `fips`, sys crate `aws-lc-fips-sys`, rustls feature `fips`). The build option `--with-rust-tls-provider=ring|aws-lc-rs|aws-lc-rs-fips` (CMake `-DZK_RUST_TLS_PROVIDER=…`, Maven `-Drust-tls-provider=…`) selects the build.
  - **Cargo features.** `tls`, `tls-ring` (the default provider), `tls-aws-lc-rs` and `tls-aws-lc-rs-fips`, with the same names in zk-driver and zk-ffi. When TLS is built, the build passes `--features tls,tls-${rust-tls-provider}` to the zk-ffi staticlib build; otherwise it passes no TLS feature (01 §4.8, §13.2). Each provider feature implies `tls`.
  - **Exactly one provider.** When `tls` is enabled with no provider feature or with more than one, a `compile_error!` stops the build with a message that names the three features. Lint and test jobs therefore use explicit feature sets and never `--all-features` (08 §14).
  - The provider is fixed per build; there is no runtime switch.
  - **Release gate.** Neither aws-lc build is in a release until the ASF legal review of `aws-lc-sys` and `aws-lc-fips-sys` passes (00 §6, release prerequisites). `ring` stays the default.
- **Placement.** `zk-driver/src/net/tls/`:

  | Module | Contents |
  |---|---|
  | `mod.rs` | `TlsConn`, `TlsConnector`, `TlsFailure`, `TlsSetupError`, `TlsPurpose`, `TlsTransport` (the driver-side socket, session and ciphertext state, 03 §4.3); re-exports `zk_proto::{TlsConfigStep, TlsResourceAt, Endpoint}` |
  | `conn.rs` | `RustlsConn`: `rustls::ClientConnection` over in-memory buffers |
  | `connector.rs` | `RustlsConnector`: builds one `ClientConfig` and one `ClientConnection` per attempt and per probe |
  | `pem.rs` | strict PEM block reader with RFC 1421 headers (`Proc-Type`, `DEK-Info`), base64 via `base64ct` |
  | `x509.rs` | `LegacyCert`: a small read-only DER view of a certificate (accepts v1), OpenSSL name canonicalization and subject hash |
  | `trust.rs` | trust-input loading (§3.4) into a `TrustSet`; the only environment read |
  | `legacy_verify.rs` | `LegacyChainOnly` (§3.5), its rejected-issuer and keyCertSign post-checks, and SPKI-based handshake signature checks |
  | `v1_leaf.rs` | the v1-leaf verifier (§3.14): OpenSSL chain rules for an X.509 v1 leaf, and the same rules as the OpenSSL path post-check for a v3 leaf (§3.5), with signatures checked through the provider's `SignatureVerificationAlgorithm` |
  | `identity.rs` | client chain and private key loading, scrypt/PBES2 parameter bounds, SPKI key-match check (§3.6) |
  | `legacy_pem.rs` | traditional OpenSSL PEM decryption: `EVP_BytesToKey(MD5)`, AES-CBC, DES-EDE3-CBC, DES-CBC |

  Every file is `#![forbid(unsafe_code)]`. zk-ffi's `tls-ring`, `tls-aws-lc-rs` and `tls-aws-lc-rs-fips` enable the zk-driver features of the same name. zk-client mirrors them: `tls-rustls` with exactly one of `tls-ring` (the default provider), `tls-aws-lc-rs` or `tls-aws-lc-rs-fips` (§3.12; 04 §16.1).
- **Socket ownership.** The Rust driver owns the socket and does every `send` with `MSG_NOSIGNAL`, or with `SO_NOSIGPIPE` set on Apple (GLB-01). TLS only transforms bytes in memory.
- **Why rustls.** One TLS stack serves both profiles. Legacy uses OpenSSL; with rustls the library has no OpenSSL dependency closure, and rustls keeps all TLS state machine code in memory-safe Rust, so there is no TLS code in C. The cost is the visible policy differences DV-TLS-1..6 (§3.13), which the release notes state.
- **No TLS code in C.** The shim has no TLS translation unit, the host vtable has no `tls_*` entry, no token type names a C TLS object, and the library never creates an OpenSSL object or calls `OPENSSL_init_ssl`.

### 3.2 Driver contract and `RustlsConn`

These traits are the driver contract. 03 §8.2.1 owns their signatures, and this document uses the same names. `RustlsConn` is the only implementation. The shared TLS types come from zk-proto (02 §4).

```rust
// zk-driver/src/net/tls/mod.rs   (#![forbid(unsafe_code)], feature "tls")
pub use zk_proto::{Endpoint, TlsConfigStep, TlsResourceAt};
// TlsConfigStep { CaFile, DefaultVerifyPaths, ClientCertChain, ClientKey, CheckPrivateKey }
// TlsResourceAt { ContextNew, ConnectionNew }

/// One sans-IO TLS client session: one connection attempt or one RW probe.
pub trait TlsConn: Send {
    fn is_handshaking(&self) -> bool;
    fn wants_read(&self) -> bool;
    fn has_ciphertext(&self) -> bool;                            // ciphertext waits for take_ciphertext
    fn handshake(&mut self) -> Result<(), TlsFailure>;           // moves handshake bytes out; Ok while in progress
    fn feed(&mut self, ciphertext: &[u8]) -> Result<(), TlsFailure>;  // never discards plaintext; Err tells the caller that a failure is latched (03 §8.2.1)
    fn feed_eof(&mut self) -> Result<(), TlsFailure>;            // latches Closed (Handshake while handshaking)
    fn read_plaintext(&mut self, out: &mut Vec<u8>) -> Result<usize, TlsFailure>; // data first, then the error
    fn write_frame(&mut self, plaintext: &[u8]) -> Result<(), TlsFailure>;        // one call per chunk
    fn take_ciphertext(&mut self, out: &mut Vec<u8>) -> usize;
    fn send_close_notify(&mut self);                             // graceful close only; queues the alert
}
#[derive(Clone, Copy)]
pub enum TlsFailure { Handshake, Data, Closed }
pub enum TlsPurpose { Main, Probe }
pub enum TlsSetupError { Config { step: TlsConfigStep }, Resource { at: TlsResourceAt } }
pub trait TlsConnector: Send + Sync {
    /// Make a new session. The drop-in reads all files again on each call; natively ReloadPolicy decides (03 §4.3.1).
    /// Call only with no driver lock held (the `Unlocked` proof).
    /// The driver calls it on TCP-connect completion, before handle_tcp_connected.
    fn new_conn(&self, _: &Unlocked, purpose: TlsPurpose, peer: &Endpoint)
        -> Result<Box<dyn TlsConn>, TlsSetupError>;
    /// The cert-string field that the step's log line prints. None when that field is NULL
    /// (the line prints "(null)") or when the step's line has no path argument.
    /// TlsSetupError::Config carries no file name; the driver takes it from here.
    fn path_for(&self, step: TlsConfigStep) -> Option<&[u8]>;
}
// zk-driver/src/net/tls/mod.rs also holds TlsTransport, the driver-side state (03 §4.3).
```

```rust
// zk-driver/src/net/tls/conn.rs
// Sketch of the reader-drain and latch logic. 03 §8.2.2 owns the struct and its methods.
pub struct RustlsConn {
    conn: rustls::ClientConnection,
    tx: Vec<u8>,                  // ciphertext for the socket
    rx: Vec<u8>,                  // plaintext not yet given to the core
    latched: Option<TlsFailure>,  // first failure; given to the core after rx is empty
}

impl RustlsConn {
    pub(crate) fn new(cfg: Arc<rustls::ClientConfig>, name: ServerName<'static>)
        -> Result<Self, rustls::Error> {
        let mut conn = rustls::ClientConnection::new(cfg, name)?;
        conn.set_buffer_limit(None);    // a whole chunk always fits: write_frame never writes short
        let mut c = RustlsConn { conn, tx: Vec::new(), rx: Vec::new(), latched: None };
        c.pump_out();                   // ClientHello
        Ok(c)
    }
    fn pump_out(&mut self) {
        while self.conn.wants_write() {
            // Writing to a Vec cannot fail.
            if self.conn.write_tls(&mut self.tx).unwrap_or(0) == 0 { break; }
        }
    }
    fn latch(&mut self, f: TlsFailure) {
        let f = if self.conn.is_handshaking() { TlsFailure::Handshake } else { f };
        if self.latched.is_none() { self.latched = Some(f); }
    }
    /// A record or alert error: Handshake while handshaking, else Data.
    fn latch_error(&mut self) { self.latch(TlsFailure::Data); }
    /// Move all decrypted bytes to rx (reader() until WouldBlock).
    /// Latches Closed at the end of the stream.
    fn take_plaintext(&mut self) {
        let mut chunk = [0u8; 4096];
        loop {
            match self.conn.reader().read(&mut chunk) {
                Ok(0) => { self.latch(TlsFailure::Closed); return; }   // close_notify
                Ok(n) => self.rx.extend_from_slice(&chunk[..n]),
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => return,
                Err(_) => { self.latch(TlsFailure::Closed); return; }  // EOF without close_notify
            }
        }
    }
    /// Process the buffered records, take out their plaintext, and queue any alert.
    fn process(&mut self) {
        let r = self.conn.process_new_packets();
        // Records before a bad record were valid. Keep their plaintext.
        self.take_plaintext();          // latches Closed
        if r.is_err() { self.latch_error(); }   // Handshake while handshaking, else Data
        self.pump_out();                // an alert goes to tx; after a fatal error the driver's alert_attempt() (03 §4.3.5)
    }
    /// Give ciphertext to rustls. Take out the plaintext of each batch
    /// before acting on an error, so no plaintext is lost.
    fn feed_all(&mut self, mut ct: &[u8]) -> Result<(), TlsFailure> {
        let mut stalled = false;
        while !ct.is_empty() && self.latched.is_none() {
            match self.conn.read_tls(&mut ct) {
                Ok(0) => break,                                    // ct is not empty: no EOF here
                Ok(_) => { stalled = false; self.process(); }
                Err(_) if stalled => { self.latch(TlsFailure::Data); }   // no progress: stop
                Err(_) => { stalled = true; self.process(); }      // buffer full: process, then go on
            }
        }
        // Err tells the caller that a failure is latched. A handshake failure has no
        // plaintext before it; in the data phase the driver acts only after the frame loop.
        self.latched.map_or(Ok(()), Err)
    }
}

impl TlsConn for RustlsConn {
    fn feed(&mut self, ct: &[u8]) -> Result<(), TlsFailure> { self.feed_all(ct) }
    fn feed_eof(&mut self) -> Result<(), TlsFailure> {
        if self.conn.is_handshaking() {             // EOF before the handshake ends
            self.latch(TlsFailure::Handshake);
            return Err(TlsFailure::Handshake);
        }
        let _ = self.conn.read_tls(&mut &[][..]);   // an empty read records EOF
        self.process();
        self.latch(TlsFailure::Closed);             // no-op when process() latched first
        Ok(())
    }
    fn read_plaintext(&mut self, out: &mut Vec<u8>) -> Result<usize, TlsFailure> {
        if !self.rx.is_empty() {                    // data first
            let n = self.rx.len();
            out.append(&mut self.rx);
            return Ok(n);
        }
        match self.latched { Some(f) => Err(f), None => Ok(0) }
    }
    fn write_frame(&mut self, plaintext: &[u8]) -> Result<(), TlsFailure> {
        if let Some(f) = self.latched { return Err(f); }
        self.conn.writer().write_all(plaintext).map_err(|_| TlsFailure::Data)?;  // one call per chunk
        self.pump_out();
        Ok(())
    }
    fn send_close_notify(&mut self) {
        if self.latched.is_some() || self.conn.is_handshaking() { return; }   // nothing on a failed session
        self.conn.send_close_notify();
        self.pump_out();                            // the alert record goes to tx
    }
    // is_handshaking/wants_read map to ClientConnection; has_ciphertext is !tx.is_empty();
    // take_ciphertext moves tx out; handshake() is pump_out() plus a latched Handshake failure.
}
```

Rules:

- **No busy loop on a full buffer.** This is a TLS policy requirement on `feed_all` (03 §8.2.2): a `read_tls` error with no progress latches `Data`. The first `read_tls` error (buffer full) processes the buffered records and feeding goes on; a second error with no progress in between stops the loop. §10 tests it.
- **One write per chunk.** Each `poll_transmit` chunk enters rustls in one `write_all` (03 §4.3.4); there is no partial-record retry state (TLS-03 does not apply).
- **Plaintext is drained at once.** Every complete ZooKeeper frame already decrypted is processed in the same driving call, and `zookeeper_interest` returns `tv=0` while one is buffered (TLS-04, FU, 00 §5.2).
- **Plaintext before a failure is delivered first** (the latch contract of 03 §8.2.1). A record error, an alert, close_notify or a TCP EOF is latched. `feed` returns the latched failure, and in the data phase the driver acts on it only after the frame loop (03 §4.3.3); `read_plaintext` returns every byte decrypted before the failure, and returns the latched failure only when `rx` is empty. The driver therefore processes every frame that arrived before the failure, then tears down (`Io`/`Eof`, ZCONNECTIONLOSS). This is C's order: `SSL_read` returns the data, and only the next read sees the error or EOF (zookeeper.c:403-419). Example: a ConnectResponse that reports expiry, followed by close_notify in the same socket read, gives ZSESSIONEXPIRED, not ZCONNECTIONLOSS. A failure during the handshake has no plaintext before it, so `feed` reports it at once as `Handshake`.
- **Fatal alert after an error (both profiles).** When rustls detects a fatal error itself (a verify failure, a bad record, a protocol violation), `process` puts the fatal alert that rustls queued into `tx`. When no earlier ciphertext is waiting in the driver's `ct_out`, the driver makes one non-blocking send of it, ignores the result and never waits for WRITE; when `ct_out` is not empty, the driver takes the alert out of the session and discards it, so it never reaches the wire (03 §4.3.5). The rule is the same for the drop-in and the native API. This is what OpenSSL does on the non-blocking legacy socket (zookeeper.c:2536-2538): it writes the fatal alert inside `SSL_connect` or `SSL_read`, before the C code sees the error, when no record write is pending, and `SSL_free` discards it otherwise (zookeeper.c:648-670). 08 §5.1 confirms this OpenSSL 3.0 and 3.5 behavior before M3 work begins, against the releases frozen for M3. A received alert, close_notify or EOF queues nothing.
- **Errors are errors.** A record or alert error ends the connection; it is never mistaken for would-block (TLS-05 is RE, 07 SEC-14; the OpenSSL error-queue part does not apply to rustls).
- **No session reuse.** `Resumption::disabled()`.
- **close_notify on graceful close (both profiles).** After the CloseSession exchange, or when that exchange ends within the close budget, the driver calls `send_close_notify()`, flushes the alert with non-blocking sends within the remaining close budget, and closes the socket. It never waits for the peer's close_notify. Forced closes, teardowns after an error (SASL failure included) and the RW probe send no close_notify. C never calls `SSL_shutdown` (zookeeper.c:648-670), so this is one extra TLS record on the wire that applications cannot observe (TLS-06, 07 P6). The 08 wire-trace comparisons allow it.
- **Lifetime.** In the drop-in, a new `ClientConfig` and `ClientConnection` are built per attempt and per probe, so certificate files are re-read each time (TLS-02 RE); natively, `ReloadPolicy` decides (04 §16.1). Only the IO owner holds a `RustlsConn`, including on the forced-close path (02 §12.3).

### 3.3 Per-attempt setup, in legacy order

`init_ssl_for_socket` (zookeeper.c:2847-2935) runs when TCP connect completes: in `check_events` on WRITE in state 7 (EINPROGRESS path, so the rc comes from `zookeeper_process`), or inline after an immediate `connect` (so the rc comes from `zookeeper_interest`). At exactly those points, on the IO owner and with no driver lock held, the driver calls `RustlsConnector::new_conn` first. Only when it succeeds does the driver call `handle_tcp_connected(now)`, with `now` taken at TCP-connect completion (§3.8; 03 §4.3.1). The steps run in C's order and stop at the first failure:

```rust
// zk-driver/src/net/tls/connector.rs (LegacyChainOnly profile)
fn new_conn(&self, _: &Unlocked, purpose: TlsPurpose, peer: &Endpoint)
    -> Result<Box<dyn TlsConn>, TlsSetupError> {
    use TlsConfigStep::*;
    let cfg_err = |step| TlsSetupError::Config { step };
    // The field layout is 03 §8.2.3's: RustlsConnector { src: ConfigSource, provider }.
    let ConfigSource::Legacy { ca, cert, key, passwd, env_allowed, .. } = &self.src
        else { unreachable!("the LegacyChainOnly profile uses ConfigSource::Legacy") };
    // 1. SSL_CTX_new analogue.
    let b = ClientConfig::builder_with_provider(self.provider.clone())
        .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
        .map_err(|_| TlsSetupError::Resource { at: TlsResourceAt::ContextNew })?;
    // 2. CA file (SSL_CTX_load_verify_locations).
    let mut trust = TrustSet::default();
    trust.load_ca_file(ca.as_deref()).map_err(|_| cfg_err(CaFile))?;
    // 3. Default verify paths. Fails only on a resource error (§3.4).
    trust.load_default_paths(*env_allowed).map_err(|_| cfg_err(DefaultVerifyPaths))?;
    // 4. Client identity, only when cert, key and passwd are all present.
    let ident = match (cert, key, passwd) {
        (Some(c), Some(k), Some(p)) =>                     // ClientCertChain, ClientKey, CheckPrivateKey
            Some(Identity::load(c, k, p, &self.provider)?),
        _ => None,
    };
    // 5. Verifier and config.
    let v = Arc::new(LegacyChainOnly::new(trust, self.provider.signature_verification_algorithms));
    let b = b.dangerous().with_custom_certificate_verifier(v);
    let mut cfg = match ident {
        Some(ck) => b.with_client_cert_resolver(Arc::new(LegacyClientCert(Arc::new(ck)))),
        None => b.with_no_client_auth(),
    };
    cfg.enable_sni = false;                                // C sends no SNI
    cfg.resumption = Resumption::disabled();               // C builds a new context per attempt
    // 6. SSL_new analogue. The name is the peer IP; it is never checked.
    let name = ServerName::IpAddress(peer.addr.ip().into());
    let conn = RustlsConn::new(Arc::new(cfg), name)
        .map_err(|_| TlsSetupError::Resource { at: TlsResourceAt::ConnectionNew })?;
    Ok(Box::new(conn))
}
```

- The driver maps `Config{step}` to `handle_transport_failed(TransportFailure::TlsConfig{step, file: jstring(connector.path_for(step))})` (03 §4.3.1; `None` becomes `JString::Null`): `CaFile` prints `ca`, `ClientCertChain` prints `cert`, `ClientKey` prints `key`; the other steps print no path. It maps `Resource{at}` to `TransportFailure::TlsResource{at, errno}` (§3.8).
- `LegacyClientCert` implements `rustls::client::ResolvesClientCert`: `resolve` returns the key whenever the key can sign one of the offered schemes, ignoring `root_hint_subjects`, as OpenSSL sends its configured certificate regardless of the server's CA list; `has_certs` is true. With no identity, rustls answers a CertificateRequest with an empty Certificate message, as OpenSSL does.
- No ALPN, no early data, no ECH, no key log. In the `ring` and `aws-lc-rs` builds `require_ems` is false, which is the rustls default without its `fips` feature and matches OpenSSL, which does not require Extended Master Secret. The FIPS build sets it to true and checks `cfg.fips()` before step 6 (§3.15).
- The provider object and its algorithm tables are built once per handle; only files are re-read per attempt.
- **Logs before file reads.** When LOG items are pending in the outbox at the point where the IO step would call `new_conn`, the step stops at a barrier (`ZKRS_MORE`); C drains the LOG items, which runs the log callback from a C frame, and `zkrs_resume` then continues into `new_conn` and its file reads (05). The cost is one extra round trip, only when logs are pending.
- **File names on Windows.** Path fields are opened by the rule of §3.4 ("File names on Windows").
- `new_conn` takes no time argument. The verifier gets `now` from the rustls time provider: the system clock in production, which is the time OpenSSL uses for validity checks, and an injected provider in tests (03 §8.2.1).

### 3.4 Trust inputs (drop-in)

**CA file** (`SSL_CTX_load_verify_locations(ctx, ca, NULL)`):

- `ca == NULL` (for example the cert string `""`) fails the step, as OpenSSL returns 0 when both arguments are NULL. The log argument is NULL. The shim's printf driver renders it as `(null)` on every platform. Legacy passes the NULL pointer to `%s` (zookeeper.c:2881), which glibc prints as `(null)` and some C libraries crash on; the uniform `(null)` is FU there (07).
- The file is read whole and parsed as PEM. Recognized labels are `CERTIFICATE`, `X509 CERTIFICATE` and `TRUSTED CERTIFICATE` (trust inputs), `X509 CRL` (parsed and counted, not used: legacy sets no CRL-check flag), and private-key labels (a well-formed block is ignored). Text outside blocks and well-formed blocks with other labels are ignored, as `PEM_X509_INFO_read_bio` does.
- The step succeeds when the file yields at least one certificate or at least one CRL, as OpenSSL `X509_load_cert_crl_file` does. A file that holds only CRLs therefore loads and adds no trust input; the default paths then decide the handshake, as in legacy.
- The load fails, all or nothing, when the file cannot be opened or read, when any PEM block of any label has invalid base64, when a certificate, CRL or unencrypted private-key block has invalid DER, or when the file yields neither a certificate nor a CRL. The step then logs "Failed to load CA file %s".
- Invalid DER here means what OpenSSL's `d2i_X509` refuses. A certificate block that OpenSSL decodes and `LegacyCert` does not parse does not fail the load: it adds no trust input, and a chain that needs it is rejected (DV-TLS-3). The block still counts as a certificate for the success rule above. `pem.rs` makes this test with a structural decode of the X.509 `Certificate` template (tags, lengths and nesting of the fields), which 08 TC-26 and TC-28 compare with OpenSSL. A corrupt block in a default file or in a hash-named file (below) has the same meaning.
- `TRUSTED CERTIFICATE` blocks carry an `X509_CERT_AUX` after the certificate. The certificate is used and the aux trust settings are applied as OpenSSL's `X509_check_trust` applies them for `X509_TRUST_SSL_SERVER`: a reject list naming serverAuth or anyExtendedKeyUsage marks the certificate Rejected; a trust list naming serverAuth or anyExtendedKeyUsage makes it an anchor even if it is not self-signed; a non-empty trust list without them also marks it Rejected. A Rejected input stays in the trust set, as OpenSSL keeps it in the store. It never ends a chain, is never a bridge or a path-(b) match, and a chain whose trusted-first issuer choice at any step is a Rejected input fails (`X509_V_ERR_CERT_REJECTED`, error 28), as OpenSSL's `check_trust` (`obj_trust` returns `X509_TRUST_REJECTED`) does. Without aux settings the default rules below apply.

**Default verify paths** (`SSL_CTX_set_default_verify_paths`):

- **Environment rule.** OpenSSL ignores `SSL_CERT_FILE` and `SSL_CERT_DIR` in set-uid/set-gid processes (`ossl_safe_getenv`). The shim computes `zkrs_init_args.tls_env_allowed` once at init (`getauxval(AT_SECURE) == 0` on Linux, `!issetugid()` on BSD and Apple, true on Windows) and passes the result to Rust. The native zk-client computes the same input in safe Rust with rustix, as `getuid() == geteuid() && getgid() == getegid()` on Unix, and true on Windows. `trust.rs` reads the variables per attempt with `std::env::var_os`, and only when `tls_env_allowed` is set. This one function is the only environment read in the drop-in's Rust code (01 §3.3, R6).
- **File.** If the variable is allowed and `SSL_CERT_FILE` is set, the file is **exactly that path**, even when it does not exist (then it contributes nothing). Otherwise the build default file (below).
- **Directory.** If the variable is allowed and `SSL_CERT_DIR` is set, the directories are **exactly** that value split on `:` (`;` on Windows), existing or not. Otherwise the build default directory (below). OpenSSL has one compiled-in default directory, so only one default directory is used.
- **Build default.** The default file and directory are compile-time constants (`option_env!("ZK_TLS_DEFAULT_CERT_FILE")`, `option_env!("ZK_TLS_DEFAULT_CERT_DIR")`), so nothing is read from the environment at run time to find them. The loader uses them when a variable is not set or not allowed. An unset or empty value means no default location of that kind (01 §3.3 R6).
  - **Source.** The build option `--with-rust-tls-default-paths=FILE:DIR` (CMake `-DZK_RUST_TLS_DEFAULT_PATHS`) sets them. configure and CMake pass the values to cargo as `ZK_TLS_DEFAULT_CERT_FILE` and `ZK_TLS_DEFAULT_CERT_DIR`.
  - **Default of the option.** The OPENSSLDIR recorded at configure time: `$OPENSSLDIR/cert.pem` and `$OPENSSLDIR/certs`. OPENSSLDIR comes from `openssl version -d`, run with the `openssl` binary of the OpenSSL installation that the legacy probe selected: `$with_openssl/bin/openssl` for `--with-openssl=DIR`, `$OPENSSL_ROOT_DIR/bin/openssl` in CMake, and otherwise the first `openssl` on `PATH`. The binary comes from the probed installation because a `PATH` binary can belong to another library (for example LibreSSL on `PATH` and a Homebrew openssl@3 named by `--with-openssl=DIR` on macOS), whose OPENSSLDIR can hold a different, wider root set than the library legacy links.
  - **Platform list.** When no such `openssl` binary is available, configure and CMake apply the platform list of 01 §6.1 on the build host. They take the first entry `<dir>` of that list that exists and use `<dir>/cert.pem` and `<dir>/certs`. Nothing is probed at run time.
  - **Native cargo builds.** When `ZK_TLS_DEFAULT_CERT_FILE` and `ZK_TLS_DEFAULT_CERT_DIR` are unset, zk-driver's build.rs applies the same platform list on the build host, with the same `<dir>/cert.pem` and `<dir>/certs` rule, and sets the two constants from the result.
  - **Cross builds.** The recorded value describes the build host, which can differ from the target. Cross builds set the locations explicitly: `--with-rust-tls-default-paths` for the target, or `ZK_TLS_DEFAULT_CERT_FILE`/`ZK_TLS_DEFAULT_CERT_DIR` for a cargo build.
  - On Windows there is no build default unless the option sets one; only the variables are used (legacy OpenSSL's compiled-in OPENSSLDIR (for example C:\Program Files\Common Files\SSL) normally does not exist or holds no certificates there; a populated one is covered by DV-TLS-2).
- **No run-time search.** The loader never searches other locations at run time, and it never substitutes a system bundle when a variable names a missing path, which OpenSSL never does either. The drop-in closure has no `openssl-probe` crate, so `openssl_probe::probe()` is never called (§3.11).
- **Hash directory semantics.** OpenSSL's `by_dir` lookup reads a directory only through subject-hash file names. For each directory, the loader considers only files named `HHHHHHHH.N` (8 lower-case hex digits, a decimal suffix), takes suffixes from `.0` upward and stops at the first suffix whose file is missing or does not load (no certificate, or a corrupt block), as `by_dir` does. A certificate in such a file is a trust input only when the OpenSSL subject hash of its subject name equals `HHHHHHHH`. OpenSSL adds every certificate of a file it reads to the store, so it can also find a certificate of another subject in that file; the loader does not use such a certificate, and where OpenSSL accepts through it, the stricter verdict is DV-TLS-3. CRL files (`HHHHHHHH.rN`) are ignored.
  - **OpenSSL 3.0 store lookup.** OpenSSL 3.0's `X509_STORE_set_default_paths_ex` also adds `X509_LOOKUP_store()` with no URI, and that lookup takes `SSL_CERT_DIR` (through `ossl_safe_getenv`) or else `X509_get_default_cert_dir()` as its URI. It is consulted only when the earlier lookups find no certificate with the wanted subject, and it opens only when the value is a single path (a list with a separator does not open, and the lookup then adds nothing). Its `file:` loader reads every entry named `<hash>.<digits>` for the wanted subject hash, with the hex part compared case-insensitively and no stop at a suffix gap. OpenSSL 3.0 therefore also finds a certificate in `HHHHHHHH.1` when `.0` is missing, and in an upper-case hash name. OpenSSL 3.5 passes no default URI to its store lookup, so it reads the directory through `by_dir` alone.
  - **Rule applied.** The loader applies the `by_dir` rule above for every reference line and does not reproduce the OpenSSL 3.0 store lookup. It therefore never trusts a certificate that OpenSSL 3.5 does not find. Where OpenSSL 3.0 accepts through the store lookup, the stricter verdict is DV-TLS-3. A hash-named file that holds a valid certificate before a corrupt block is a separate case: `by_dir` stops at that file, but OpenSSL 3.0 and 3.5 have already added the certificate to the store, and the second trusted search of chain building finds it. That search runs in every libssl handshake, because the peer chain is the untrusted list. The loader treats the file as not loaded, and the stricter verdict is DV-TLS-3 on both lines.
- **OpenSSL subject hash** (`X509_NAME_hash_ex`), in `x509.rs`:
  1. For each RDN, for each attribute value whose tag is UTF8String, BMPString, UniversalString, PrintableString, T61String (as Latin-1), IA5String or VisibleString: convert to UTF-8, remove leading and trailing whitespace, collapse each inner run of whitespace to one space, lower-case ASCII `A`-`Z`, and re-encode as UTF8String. Other values stay as they are.
  2. DER-encode each RDN as a SET (DER order), and concatenate the SETs without the outer SEQUENCE header.
  3. Take SHA-1 of the result (from the selected provider crate: `ring::digest::SHA1_FOR_LEGACY_USE_ONLY` or the aws-lc-rs equivalent; in the FIPS build the FIPS module computes it, a non-signature use of SHA-1). The hash is the first four bytes read as a little-endian `u32`.

  The same canonical form is used for name comparison in §3.5 (`X509_NAME_cmp`). A differential test checks it against `openssl x509 -subject_hash` over the corpus (§10).
- **Failure.** The step fails only on a resource error, as `SSL_CTX_set_default_verify_paths` does: OpenSSL's `X509_STORE_set_default_paths` ignores the load results and fails only when it cannot allocate its lookup methods.
  - Missing or empty default locations do not fail the step. Neither does an existing file that does not parse (the default file contributes nothing, all or nothing as `X509_load_cert_crl_file`; a hash-named file contributes nothing and ends the walk of that hash's suffixes) or a directory with no matching hash-named certificate file. When no default location yields a certificate, the step succeeds with no anchors from the defaults.
  - The loader's only resource error is allocation failure, which aborts under 07 P7. The step therefore has no reachable failure in practice, as in legacy.
  - The failure path stays defined: `TlsConfigStep::DefaultVerifyPaths` gives `ZSSLCONNECTIONERROR`, `errno = EINVAL`, the line "Call to SSL_CTX_set_default_verify_paths failed" (zookeeper.c:2888) and no teardown (CC-14, 00 §4). The site stays in the Site table.
  - The native `add_system_roots` check for zero usable roots is native-only (§3.12); the drop-in step never fails for that reason.

**Re-read.** All trust files are read again on every connection attempt and every RW probe (TLS-02 parity). The default bundle (about 150 certificates) and a hash directory of the same size parse in a few milliseconds; attempts are rare.

**File names on Windows.** The cert-string fields are C byte strings. OpenSSL opens a file name on Windows as UTF-8 first, through the wide API (`_wfopen`), and falls back to the narrow ANSI open when the bytes are not valid UTF-8 or the wide open fails with ENOENT or EBADF (`openssl_fopen`); the design retries on `ErrorKind::NotFound`, the only one of the two that a path open returns. The design reproduces that order:

1. At handle creation zk-ffi converts each path field (`ca`, `cert`, `key`) into up to two candidates: the UTF-8 decoding, when the bytes are valid UTF-8, and the ANSI code-page decoding through `MultiByteToWideChar(CP_ACP, …)` in its raw module. The connector holds both as `PathBuf`s.
2. `trust.rs` and `identity.rs` open the UTF-8 candidate first. When there is none, or its open fails with `ErrorKind::NotFound`, they open the ANSI candidate. Any other open error fails the step at once.
3. The environment variables are read as wide strings with `std::env::var_os` and used without conversion. OpenSSL 3 reads them with `getenv` (the ANSI code-page form) unless `OPENSSL_WIN32_UTF8` is set, then opens them by the rule of item 2; the wide read opens the same file whenever the value is representable in the ANSI code page. Hash-directory entries are formed from the directory path in the same encoding.

On other platforms the bytes are the path (`OsStr::from_bytes`). 08 TI-06 tests both Windows candidates.

**Classification into a `TrustSet`.** Each trust input `t`, in load order (CA file in file order, default file, default directory), is classified once per attempt:

```rust
// zk-driver/src/net/tls/trust.rs
pub struct TrustSet {
    certs: Vec<TrustedCert>,            // every trust input with its Role, load order (path (b), §3.14)
    anchor_candidates: Vec<TrustedCert>,// may end a chain; time filter at verify time
    bridges: Vec<CertificateDer<'static>>, // trusted but not self-signed: offered as intermediates
    rejected: Vec<TrustedCert>,         // Role::Rejected: issuer candidates that fail the chain (28)
}
fn classify(t: &LegacyCert) -> Role {
    if t.aux_rejects_server_auth() { return Role::Rejected; }
    if t.aux_trusts_server_auth() { return Role::Anchor; }          // explicit trust
    if t.aux_has_trust_list() { return Role::Rejected; }           // trust list without serverAuth/anyEKU
    if t.openssl_self_signed() && t.openssl_check_ca() != 0 && t.eku_allows_ssl_server() {
        Role::Anchor                    // OpenSSL ends chains only at such a certificate
    } else if t.openssl_self_signed() {
        Role::LeafOnly                  // accepted only through path (b); in issuer selection
                                        // (§3.14.3) it stays a trusted candidate, and a chain
                                        // that selects it fails, as in OpenSSL
    } else {
        Role::Bridge                    // rule (d): an issuer candidate, never a chain end
    }
}
```

- `openssl_self_signed()` is OpenSSL 3.x `EXFLAG_SS`, defined in full here. Canonical subject equals canonical issuer; the AKID is consistent with the certificate itself (`X509_check_akid`: a key identifier that differs from the SKID, a serial that differs, or a directory name that differs from the issuer is a mismatch; an absent AKID or SKID is not); and the signature algorithm's key type matches the SPKI key type. The self-signature itself is never verified, as in OpenSSL without `X509_V_FLAG_CHECK_SS_SIGNATURE`.
- `openssl_check_ca()` is `X509_check_ca`: 0 if keyUsage is present without keyCertSign; with basicConstraints, 1 for CA:TRUE and 0 otherwise; without basicConstraints, 3 for a self-signed v1 certificate, 4 if keyUsage is present, 5 if a Netscape cert type has a CA bit, else 0. For 5, the Netscape SSL CA bit is also required (`check_ssl_ca`).
- `eku_allows_ssl_server()`: no EKU, or an EKU that contains id-kp-serverAuth, Netscape SGC or Microsoft SGC (`xku_reject(x, XKU_SSL_SERVER|XKU_SGC)`).
- Anchors go into a `rustls::RootCertStore` through `RootCertStore::add`, which accepts v1 trust anchors. Anchor validity is checked at verify time (§3.5, step a1).

### 3.5 `LegacyChainOnly`: the compat verifier

**Reference behavior.** OpenSSL as configured by zookeeper.c:2876-2890: `SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT`, the trust inputs of §3.4, purpose and trust `ssl_server` (libssl sets them for a client), default flags (no `X509_V_FLAG_PARTIAL_CHAIN`, no CRL check, no policy check, `X509_V_FLAG_TRUSTED_FIRST` on), no host name, no SNI. The reference OpenSSL lines are 3.0 and 3.5 LTS, each at its latest patch release when M3 starts and then frozen (08 §4.1). 1.1.1 is out of support and is not a reference (its `EXFLAG_SS` rule uses keyUsage instead of the signature-algorithm match).

**Rules.**

- **(a) Chain to an anchor.** A leaf that is not OpenSSL-self-signed is accepted only when it chains to a trust anchor. A v3 leaf is checked through `verify_server_cert_signed_by_trust_anchor`, then, in this order, the keyCertSign post-check, the rejected-issuer post-check, the leaf's validity and OpenSSL's keyUsage and nsCertType rules for `ssl_server`, and the OpenSSL path post-check (below), which applies OpenSSL's issuer selection and chain rules (§3.14.3) to the path that OpenSSL would build; the leaf is accepted only when all of them accept. A v1 leaf, which webpki does not parse, is checked by the v1-leaf verifier (§3.14), which applies OpenSSL's chain rules to the whole path.
- **(b) Self-signed trust-set leaf.** A leaf that is byte-identical to a trust-set certificate is accepted directly, after the validity check and the `ssl_server` purpose check, which an aux trust list naming serverAuth or anyExtendedKeyUsage satisfies, as in §3.14.3 Purpose, and when the leaf has no critical extension outside the processed set of §3.14.3 (error 34; aux trust does not override it), **only when that certificate is OpenSSL-self-signed** (`EXFLAG_SS`, defined in full in §3.4: canonical issuer == canonical subject, an AKID consistent per `X509_check_akid`, and a signature-algorithm key type that matches the SPKI key type; the self-signature is not verified).
- **(c) No name, no SNI.** No SNI is sent. The peer name is `ServerName::IpAddress` of the peer address, and no host name is checked.
- **(d) Anchors are self-signed or explicitly trusted.** Only OpenSSL-self-signed, CA-capable, purpose-compatible trust inputs that are valid at `now` end a chain, plus `TRUSTED CERTIFICATE` inputs whose aux trust list names serverAuth or anyExtendedKeyUsage (explicit trust, §3.4). A trust input that is neither self-signed nor explicitly trusted is offered to path building as an intermediate (a bridge), never as a chain end, so a partial chain is rejected as OpenSSL rejects it.

**Algorithm.** Given the end-entity certificate `L`, the intermediates `I` sent by the server, and `now`:

```text
parse L with LegacyCert (v1 and v3)                          -> on error: reject (bad encoding)
reject unless leaf_key_usable(L)                              # the SPKI key type, curve, size and point form are ones that an
                                                              # algorithm of algs.all verifies; else the handshake
                                                              # signature could not verify; DV-TLS-3
if L is OpenSSL-self-signed:
    if L.der is byte-identical to some t in TrustSet.certs, t not Rejected:  # path (b), rule (b); v1 or v3
        C = { t in TrustSet.certs : canon(t.subject) == canon(L.subject) and check_issued(t, L) }
                                                              # C includes Rejected inputs, as the store lookup does
        if some member of C valid at now is Rejected: reject  # error 28, or order-dependent: fail closed
        if |C| > 1: require that L is the only member of C valid at now   # mimic rule; else reject
        accept iff  valid_at(L, now)                          # notBefore <= now < notAfter
               and  (the matched t's aux trust list names serverAuth or anyExtendedKeyUsage (§3.4)
                     or ssl_server_purpose(L))                # EKU, keyUsage, nsCertType; check_purpose
                                                              # consults X509_check_trust first for a
                                                              # trusted certificate
               and  L has no critical extension outside the processed set of §3.14.3
                                                              # X509_V_ERR_UNHANDLED_CRITICAL_EXTENSION (34);
                                                              # aux trust does not override it
        (handshake signatures: from the SPKI, below)
    else:
        reject                                                # X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT
else if L is X.509 v1:                                        # path (a), rule (a), v1 leaf
    accept iff v1_leaf::verify(L, I, TrustSet, now, algs.all) # §3.14: OpenSSL chain rules, own path building
else:                                                         # path (a), rule (a): chain to an anchor
    P  = ParsedCertificate::try_from(L)                       -> malformed: reject
    R  = RootCertStore of anchor_candidates valid at now      # a1: OpenSSL checks anchor time too
    I' = [c in I ++ TrustSet.bridges : now < notAfter(c)]    # rule (d): a bridge never ends a chain;
                                                              # OpenSSL notAfter rule: an expired candidate
                                                              # cannot end up in an accepted path
    verify_server_cert_signed_by_trust_anchor(&P, &R, &I', now, algs.all)  -> error: reject
    reject if some x in Reach(L, I')                          # keyCertSign post-check (below):
              has keyUsage without keyCertSign                # X509_V_ERR_KEYUSAGE_NO_CERTSIGN (32)
    reject if some r in TrustSet.rejected                     # rejected-issuer post-check (below)
              has check_issued(r, x) for x = L or some x in I'
    require valid_at(L, now)                                  # notBefore <= now < notAfter, OpenSSL rule
    ssl_server_purpose(L) restricted to keyUsage and nsCertType   # webpki already checked EKU
    accept iff v1_leaf::verify_v3_path(L, I, TrustSet, now, algs.all)
                                                              # OpenSSL path post-check (below): §3.14.3
                                                              # selection and finish rules over L, I, TrustSet
never call verify_server_name; ignore server_name and OCSP    # rule (c)
```

- **`leaf_key_usable(L)`** checks the leaf's own public key. No other check of `verify_server_cert` does this: webpki and `verify_sig` use only the issuer's SPKI, and path (b) verifies no signature. It holds when some algorithm of `algs.all` has a `public_key_alg_id()` equal to L's SPKI algorithm (the key type and, for ECDSA, the curve) and, for `rsaEncryption`, when the modulus size is one that the provider's RSA algorithms take (2048 to 8192 bits), and, for an EC key in the `ring` build, when the point is uncompressed (ring verifies uncompressed points only). An RSA key outside that range, a DSA, Ed448 or RSA-PSS (`id-RSASSA-PSS`) key, a P-521 key or a compressed-point EC key in the `ring` build and a brainpool or secp256k1 key are therefore rejected on every path, v1 or v3, and the verdict of `verify_server_cert` does not wait for a handshake signature. The check can only reject. Where OpenSSL also rejects (for example error 66, `X509_V_ERR_EE_KEY_TOO_SMALL`, at security level 2), the verdicts agree; where the oracle ACCEPTs, the case is DV-TLS-3 (08 TC-23, TC-33, TC-34, TC-46, TC-54).
- **Path (b).** An OpenSSL-self-signed leaf that is byte-identical to a certificate in the trust set (CA file, default file or default directory; a Rejected input is never a match, and a time-valid Rejected input in the mimic set `C` rejects the leaf, as OpenSSL's `check_trust` rejects it with error 28) is accepted after the validity check and the `ssl_server` purpose check, which an aux trust list naming serverAuth or anyExtendedKeyUsage satisfies, as in §3.14.3 Purpose, and when it has no critical extension outside the processed set of §3.14.3 (`X509_V_ERR_UNHANDLED_CRITICAL_EXTENSION`, error 34; aux trust does not override it), whether it is v1 or v3. OpenSSL replaces the leaf with the store certificate, so the chain is that certificate alone with no untrusted part, and `check_purpose` consults `X509_check_trust` first for it. A leaf that is byte-identical to a trust-set certificate but is not OpenSSL-self-signed gets no special treatment: it goes through path (a), as in OpenSSL. Path (b) covers the in-tree `testSSL` setup, where the CA file is `server.crt` (tests/TestClient.cc:976): a leaf signed by `root.crt` (ssl/gencerts.sh:113) whose DN equals the root's DN and which has no AKID, so it is OpenSSL-self-signed. It also covers the small-deployment setup of a self-signed `CA:TRUE` leaf in the CA file.
- **gencerts output across OpenSSL lines.** A gencerts leaf made with `openssl x509 -req` and no extensions (OpenSSL ≤ 3.1, and LibreSSL) has no AKID and the same DN as its issuer, so it is OpenSSL-self-signed, and OpenSSL 3.0 accepts it with the CA file `server.crt`. Path (b) accepts it too. OpenSSL 3.2 and later add SKID/AKID to that output. The leaf is then not self-signed, its issuer `root.crt` is not in the trust set, and OpenSSL rejects it (error 20, "unable to get local issuer certificate"). Path (a) rejects it for the same reason. Both cores agree in both cases, so trust is never wider than legacy (08 §1, 08 P6).
- **Mimic rule (same-subject ambiguity only).** OpenSSL replaces a self-signed leaf with the first time-valid trust-store certificate with the same subject that passes `check_issued`, and rejects the leaf if the two are not byte-identical ("self-signed untrusted mimic"). The order among same-subject store entries is internal to OpenSSL. The rule above accepts only when that choice cannot matter, and fails closed in the remaining, order-dependent case; where OpenSSL accepts by store order, the difference is DV-TLS-3. The corpus covers it.
- **keyCertSign post-check (path (a), v3 leaf; `legacy_verify::keycertsign_post_check`).** rustls-webpki does not check keyUsage on CA certificates, so a path that webpki accepts can contain an issuer whose keyUsage lacks keyCertSign. OpenSSL rejects such a path (`ossl_x509_signing_allowed` in `internal_verify`, error 32; for an intermediate `X509_check_ca` also returns 0, error 79). The check runs right after `verify_server_cert_signed_by_trust_anchor` accepts, before the rejected-issuer post-check. webpki does not return the path it built, so the check covers every certificate that can lie on it. `Reach(L, I')` is the set of certificates of `I'` that are reachable from `L` by `check_issued`: `x` is in it when `check_issued(x, L)` holds, or when `check_issued(x, y)` holds for some `y` already in it. The leaf is rejected when any member has a keyUsage extension without keyCertSign. Each certificate of `I'` enters the set at most once and no signature is verified, so the work is at most |I'|² `check_issued` tests. The check can only reject, so it never accepts more than OpenSSL (08 P6). OpenSSL takes one issuer per step (trusted first, then the first time-valid sent certificate, §3.14.3). Where OpenSSL's path does not use that certificate (its choice takes another certificate of the same name, or its path reaches an anchor first) and OpenSSL accepts, the stricter verdict is a DV-TLS-3 case, such as the order-dependent same-name case (§3.13). The corpus covers both outcomes (08 TC-47), and a unit test (08 TC-16) confirms that webpki alone accepts a chain through an intermediate whose keyUsage lacks keyCertSign.
  - **Code comments.** The doc comment of `keycertsign_post_check` states that the function exists only for OpenSSL parity, because webpki does not enforce keyCertSign on CA certificates. It also states how to remove the check if the project accepts webpki's behavior, that is, no keyUsage check on CA certificates beyond the OpenSSL path post-check: delete the function and its call in `LegacyChainOnly::verify_server_cert`; delete its unit tests and the TC-16 webpki-behavior unit test, and change the TC-47 expectations in 08 §5.2 from DEV under DV-TLS-3 to EXACT; and delete the keyCertSign item from the DV-TLS-3 change and release-note text in §3.13 and in its copies in 00 §5.1 and 07 §5.1 and the matching clause of 07 TLS-14, the TC-47 clause of the DV-TLS-3 row in 08 §5.5 and the TC-47 clause of 08 §15.3 M3 (3). The comment notes that the OpenSSL path post-check keeps its own keyCertSign rule on the issuer that OpenSSL chooses (`step`, §3.14.3), so the verdict stays within OpenSSL's after the removal.
- **Rejected-issuer post-check (path (a), v3 leaf).** With `X509_V_FLAG_TRUSTED_FIRST`, OpenSSL looks in the store first at every step and takes a Rejected input as the issuer when `check_issued` accepts it; `check_trust` then fails the chain (error 28). Example: the CA file holds intermediate I as a `TRUSTED CERTIFICATE` with a serverAuth reject list, the server sends leaf + I, and the root is trusted. OpenSSL takes the store copy of I and rejects; webpki, which sees only `I'`, would build L→I→R and accept. `verify_server_cert_signed_by_trust_anchor` does not return the path it built, so the check applies to every certificate that can be on it: the path is rejected when a Rejected input passes `check_issued` for L or for any certificate of `I'`, valid at `now` or not (OpenSSL takes an expired store issuer when no time-valid one exists, then fails). This is never wider than OpenSSL (08 P6). Where OpenSSL accepts because its path does not reach that certificate, or because a time-valid non-Rejected store issuer comes first in store order, the difference is DV-TLS-3. The v1-leaf verifier and the OpenSSL path post-check apply the exact rule (§3.14.3).
- **OpenSSL path post-check (path (a), v3 leaf).** webpki's path building and rules differ from OpenSSL 3.x chain building, and some differences accept where OpenSSL rejects: an intermediate with `CA:TRUE` whose keyUsage lacks keyCertSign (OpenSSL: `X509_check_ca` returns 0, error 79); an anchor's pathLenConstraint that the path exceeds (error 25), because a webpki `TrustAnchor` keeps no basicConstraints; an issuer whose AKID does not match, or a same-name issuer with the wrong key that OpenSSL chooses and then fails on its signature, because webpki ignores the AKID and tries every same-name issuer after a signature failure, while OpenSSL takes one issuer per step (`ossl_x509_likely_issued`; the first time-valid store match, trusted first, otherwise `find_issuer`'s first time-valid sent certificate) and never retries; a path that goes on through a sent certificate above a trusted bridge or above a self-signed sent certificate, because webpki considers every candidate at every step, while OpenSSL consults no sent certificate at those points (errors 2 and 19); and a leaf without a dNSName SAN whose CN violates a dNSName constraint, because webpki ignores the CN where OpenSSL's `NAME_CONSTRAINTS_check_CN` checks it. After webpki accepts, `v1_leaf::verify_v3_path` (§3.14.2) therefore runs the issuer selection and the finish rules of §3.14.3 over `L`, `I` and the `TrustSet`: trusted-first selection, the `find_issuer` order, no sent certificate above a trusted bridge or a self-signed sent certificate, the ambiguity rule, keyCertSign on the chosen issuer, the Rejected-input check, `check_ca`, the `ssl_server` CA purpose, every pathLenConstraint including the anchor's, signatures on that path, and name constraints including `NAME_CONSTRAINTS_check_CN`, with the leaf's own validity, EKU, keyUsage and nsCertType purpose and its subjectAltName. The leaf is accepted only when webpki and all three post-checks accept, so the verdict is never wider than OpenSSL's (08 P6). The post-check's ambiguity and budget rejections are DV-TLS-3 cases (§3.14.3). The corpus covers each difference (08 §5.2: TC-44; the v3-leaf variants of TC-40 and TC-42 for the ambiguity and budget rejections).
- **`check_issued(t, L)`** is OpenSSL 3.x `ossl_x509_likely_issued`: canon(L.issuer) == canon(t.subject), L's AKID is consistent with t per `X509_check_akid`, and the key type of L's signature algorithm matches t's SPKI key type. No signature is verified.
- **`ssl_server_purpose(L)`** is `check_purpose_ssl_server(x, ca=0)`: EKU absent or containing serverAuth/SGC; keyUsage absent or containing digitalSignature, keyEncipherment or keyAgreement; Netscape cert type absent or containing SSL server. A `CA:TRUE` leaf is accepted (no basicConstraints check on the end entity); webpki would reject a CA certificate used as an end entity, which is one reason path (b) does not go through webpki. On path (a), webpki rejects a v3 leaf with `CA:TRUE` that is not self-signed (`CaUsedAsEndEntity`), where OpenSSL accepts it at depth 0 (DV-TLS-3).
- **Validity.** OpenSSL treats a certificate as valid when `notBefore <= now < notAfter`. Path (b), the anchor filter, the leaf check after webpki in path (a), the filter of intermediate candidates in path (a) and every check of the v1-leaf verifier (§3.14) use this rule. webpki's own inclusive `notAfter` check is therefore never the deciding check, so `LegacyChainOnly` never accepts at `now == notAfter` where OpenSSL rejects (08 P6).
- **What webpki adds, and what the post-checks add.** For a v3 leaf, webpki builds a path over `I'` and checks on it: signatures with `algs.all`, intermediate validity, basicConstraints `CA:TRUE` and the pathLenConstraint of each intermediate, EKU on every certificate, dNSName, iPAddress and rfc822Name name constraints over subjectAltName entries, and critical extensions against its own set. It does not check keyUsage on CA certificates, the anchor's pathLenConstraint, the AKID, the subject CN or the leaf's own public key (`leaf_key_usable` above does), and it rejects every directoryName constraint instead of applying it. The keyCertSign post-check adds keyUsage keyCertSign on every certificate of `I'` that can lie on webpki's path (error 32), whatever issuer OpenSSL would choose. The rejected-issuer post-check adds the Rejected-input check on every certificate that can lie on that path (error 28). The OpenSSL path post-check supplies OpenSSL's side: one issuer choice per step as OpenSSL makes it, the signatures on that path, keyCertSign and `check_ca` on every issuer, the `ssl_server` CA purpose including nsCertType, every pathLenConstraint including the anchor's, name constraints including directoryName subtrees and `NAME_CONSTRAINTS_check_CN`, the notAfter rule and the Rejected-input check. The verdict is the intersection of webpki and the three post-checks. The stricter verdicts of the two fail-closed post-checks are DV-TLS-3 cases where OpenSSL's path does not use the certificate. The remaining stricter verdicts of the webpki side are DV-TLS-3 cases: directoryName constraints, a non-self-signed `CA:TRUE` leaf, X.509 v2 certificates, SGC-only EKU, malformed DER, the six-intermediate limit, a v3-leaf chain with a self-issued intermediate below a CA whose pathLenConstraint or nameConstraints OpenSSL does not apply to that self-issued certificate (webpki counts it and applies them), a v3-leaf chain in which a certificate's issuer name equals its issuer's subject name only in OpenSSL's canonical form (webpki compares the names byte for byte), the algorithm set, which in the `ring` build takes no compressed EC point, and its critical-extension set. The anchor's own self-signature is never checked by either implementation.
- **Corpus gate.** The trust corpus (08 §5.2: TC-01a, TC-01b, TC-04, TC-08, TC-09, the TC-16 webpki-behavior unit test, the OpenSSL path post-check cases TC-44 and TC-49, the keyCertSign post-check cases TC-47, and the v1-leaf cases of §3.14.6) confirms these rules against both OpenSSL lines before M3 exits.

**rustls 0.23 APIs used** (all public, safe):

| Purpose | API |
|---|---|
| Custom verifier | `rustls::client::danger::ServerCertVerifier` (`verify_server_cert`, `verify_tls12_signature`, `verify_tls13_signature`, `supported_verify_schemes`; the provided `requires_raw_public_keys` and `root_hint_subjects` keep their defaults), returning `ServerCertVerified::assertion()` and `HandshakeSignatureValid::assertion()` |
| Installing it | `ClientConfig::builder_with_provider(..).with_protocol_versions(..)?.dangerous().with_custom_certificate_verifier(Arc<dyn ServerCertVerifier>)` |
| Chain check | `rustls::client::verify_server_cert_signed_by_trust_anchor(&ParsedCertificate, &RootCertStore, &[CertificateDer], UnixTime, &[&dyn SignatureVerificationAlgorithm])` |
| Leaf parse for path (a) | `rustls::server::ParsedCertificate::try_from(&CertificateDer)` |
| Algorithms | `CryptoProvider::signature_verification_algorithms: WebPkiSupportedAlgorithms` (`all`, `mapping`, `supported_schemes()`) |
| TLS 1.3 handshake signature | `rustls::crypto::verify_tls13_signature_with_raw_key(msg, &SubjectPublicKeyInfoDer, dss, &WebPkiSupportedAlgorithms)` |
| TLS 1.2 handshake signature | `pki_types::SignatureVerificationAlgorithm::{public_key_alg_id, verify_signature}` through `WebPkiSupportedAlgorithms::mapping` (below) |
| No SNI, peer IP as name | `ClientConfig::enable_sni = false`; `ServerName::IpAddress(pki_types::IpAddr::from(std::net::IpAddr))` |
| Client key | `CryptoProvider::key_provider.load_private_key(PrivateKeyDer)`, `SigningKey::{public_key, algorithm}`, `sign::CertifiedKey::new`, `client::ResolvesClientCert` |

**Handshake signatures from the SPKI.** `rustls::crypto::verify_tls12_signature` parses the certificate with webpki, which rejects v1 certificates. Path (b) and the v1-leaf verifier accept v1 leaves (the in-tree `gencerts.sh` produces them with OpenSSL ≤ 3.1), so both signature checks work from the SPKI that `LegacyCert` extracts, with the provider's algorithms:

```rust
// zk-driver/src/net/tls/legacy_verify.rs
fn verify_tls12_with_spki(msg: &[u8], spki: &Spki<'_>, dss: &DigitallySignedStruct,
                          algs: &WebPkiSupportedAlgorithms) -> Result<HandshakeSignatureValid, Error> {
    let cands = algs.mapping.iter().find(|(s, _)| *s == dss.scheme).map(|(_, a)| *a)
        .ok_or(Error::PeerMisbehaved(PeerMisbehaved::SignedHandshakeWithUnadvertisedSigScheme))?;
    for alg in cands {
        // Use the algorithm for this key type only (same rule as webpki).
        if alg.public_key_alg_id().as_ref() != spki.alg_id_value { continue; }
        return alg.verify_signature(spki.key_bits, msg, dss.signature())
            .map(|()| HandshakeSignatureValid::assertion())
            .map_err(|_| Error::InvalidCertificate(CertificateError::BadSignature));
    }
    Err(Error::InvalidCertificate(CertificateError::BadSignature))
}
impl ServerCertVerifier for LegacyChainOnly {
    fn verify_tls12_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct)
        -> Result<HandshakeSignatureValid, Error> {
        verify_tls12_with_spki(m, &LegacyCert::parse(c)?.spki, d, &self.algs)
    }
    fn verify_tls13_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct)
        -> Result<HandshakeSignatureValid, Error> {
        let spki = LegacyCert::parse(c)?.spki_der();     // TLS 1.3 scheme rules apply inside
        rustls::crypto::verify_tls13_signature_with_raw_key(m, &spki, d, &self.algs)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> { self.algs.supported_schemes() }
    // verify_server_cert: the algorithm above
}
```

The `cert` that rustls passes to the signature methods is the end-entity certificate that `verify_server_cert` accepted.

**Outcome.** Any rejection makes rustls fail the handshake with an alert; the driver reports `TlsFailure::Handshake`, which is the C "error in ssl connect" path (§3.7). The specific rustls error is not application-visible.

### 3.6 Client identity (drop-in)

Loaded only when the cert-string fields `cert`, `key` and `passwd` are all non-NULL (zookeeper.c:2892). Otherwise the client has no identity.

**Certificate chain** (`SSL_CTX_use_certificate_chain_file`; step `ClientCertChain`): the first `CERTIFICATE`, `X509 CERTIFICATE` or `TRUSTED CERTIFICATE` block is the leaf (aux data dropped); later `CERTIFICATE`/`X509 CERTIFICATE` blocks form the chain in file order; other blocks are skipped. The step fails when the file cannot be read, when there is no leaf, or when a certificate block fails to decode. The chain is sent as DER exactly as read; rustls does not parse the client's own certificates, so v1 client certificates work. The chain is not checked against the OpenSSL security level, which `SSL_CTX_use_certificate_chain_file` applies to its certificates, so a chain file that the reference legacy core refuses at this step can load here (DV-TLS-2).

**Private key** (`SSL_CTX_use_PrivateKey_file(..., SSL_FILETYPE_PEM)`; step `ClientKey`): the first block whose label is a private-key label is used and other blocks are skipped, so a key file may also hold certificates. The passphrase is the first `min(strlen(passwd), 1024)` bytes of the cert-string passwd, which is what `PEM_def_callback` copies (`PEM_BUFSIZE`).

| Block | Handling |
|---|---|
| `PRIVATE KEY` (PKCS#8) | `PrivateKeyDer::Pkcs8` |
| `RSA PRIVATE KEY` (PKCS#1) | `PrivateKeyDer::Pkcs1` |
| `EC PRIVATE KEY` (SEC1, named curve) | `PrivateKeyDer::Sec1` |
| `ENCRYPTED PRIVATE KEY` | `pkcs8::EncryptedPrivateKeyInfo::try_from(der)?`, parameter bounds check (below), then `.decrypt(passphrase)` (pkcs8 0.10 with `encryption`; pkcs5 0.7 with `pbes2`, `3des`, `sha1-insecure`): PBES2 with PBKDF2 (PRF HMAC-SHA1, HMAC-SHA224, HMAC-SHA256, HMAC-SHA384 or HMAC-SHA512, the accepted subset of the PRFs OpenSSL loads; other PRFs that OpenSSL loads are DV-TLS-5) or scrypt, and AES-128/192/256-CBC or DES-EDE3-CBC. The result is `PrivateKeyDer::Pkcs8` |
| any key label with `Proc-Type: 4,ENCRYPTED` and `DEK-Info` | traditional OpenSSL PEM encryption (below), then parsed per label |
| `DSA PRIVATE KEY`; a decoded key of a type or size the provider does not load (DSA, Ed448, RSA-PSS `id-RSASSA-PSS`, RSA under 2048 bits, an unsupported curve, a key outside the provider's limits below); any other cipher or scheme | step fails (DV-TLS-5) |

- **FIPS build.** The table above is the accepted set of the `ring` and `aws-lc-rs` builds. The FIPS build accepts a subset, decrypted inside the FIPS module (§3.15.3); every other encrypted form fails the step (DV-TLS-6).
- An unencrypted key ignores the passwd, as OpenSSL does.
- Decrypted key bytes live only in `Zeroizing<Vec<u8>>` (and `pkcs8::SecretDocument`, which zeroizes on drop) until `load_private_key` has taken them.
- `provider.key_provider.load_private_key` failing (unsupported key type, size or curve) fails the step (DV-TLS-5). The key providers load RSA of at least 2048 bits (`rsaEncryption`, PKCS#1 or PKCS#8), ECDSA and Ed25519 keys only, so DSA, Ed448 and RSA-PSS (PKCS#8 `id-RSASSA-PSS`) keys fail here, as do RSA keys under 2048 bits and EC keys on curves the provider does not support (for example P-521 in the `ring` build), although OpenSSL loads an RSA-PSS key into its RSA_PSS slot. Each provider also has its own limits, which OpenSSL does not have. `ring` 0.17 refuses an RSA key with a modulus above 4096 bits or a public exponent below 65537, and a SEC1 key without its public key. aws-lc-rs refuses an RSA key with a modulus above 8192 bits. These keys also fail here (DV-TLS-5).

**PBES2 parameter bounds** (`identity.rs`). The key-derivation parameters come from the file, so they are checked after `EncryptedPrivateKeyInfo` is parsed and **before** `decrypt` runs. A large scrypt `N` or `r` would otherwise make the RustCrypto allocation fail, which aborts the process under `panic = "abort"`. OpenSSL instead fails with an error at its default `EVP_PBE_scrypt` memory limit (`maxmem`, `SCRYPT_MAX_MEM`, 32 MiB). The scrypt parameters are therefore rejected before any allocation when their memory need is over 32 MiB. The step `ClientKey` fails, with its legacy line, when:

- scrypt: `128·r·(N + 2) + 128·r·p > 32 MiB` (OpenSSL's `Vlen + Blen` against `maxmem`), or `p·r ≥ 2^30`, or `N` is not a power of two greater than 1, or an explicit `keyLength` differs from the cipher's key length;
- PBKDF2: the PRF is not one of HMAC-SHA1, HMAC-SHA224, HMAC-SHA256, HMAC-SHA384 and HMAC-SHA512 (DV-TLS-5), or an explicit `keyLength` differs from the cipher's key length.

A very large PBKDF2 iteration count is not capped: the IO owner spends the time, as legacy does inside `SSL_CTX_use_PrivateKey_file` (RE). All arithmetic is checked.

**Traditional PEM decryption** (`legacy_pem.rs`, over RustCrypto `md-5`, `aes`, `des`, `cbc`, `cipher`):

```text
headers:  Proc-Type: 4,ENCRYPTED                  (any other Proc-Type value: fail)
          DEK-Info: <CIPHER>,<IV as hex>          (missing: fail)
CIPHER    key length  IV length
AES-128-CBC   16          16
AES-192-CBC   24          16
AES-256-CBC   32          16
DES-EDE3-CBC  24           8
DES-CBC        8           8                      (any other name: fail, DV-TLS-5)
IV        exactly 2*IVlen hex digits (upper or lower case)
salt      = IV[0..8]
EVP_BytesToKey(cipher, MD5, salt, pass, count = 1):
          D1 = MD5(pass || salt)
          Di = MD5(D(i-1) || pass || salt)        i = 2, 3, ...
          key = first keylen bytes of D1 || D2 || ...   (DES-CBC: D1[0..8]; AES-128: D1; others: D1 || D2)
plain     = CBC-decrypt(key, IV, body), then strict PKCS#7 padding removal (bad padding: fail)
```

The derived key, the intermediate digests and the plaintext are held in `Zeroizing` buffers. A wrong passphrase normally shows as bad padding or as a DER parse failure of the plaintext; both fail the step, as OpenSSL's "bad decrypt".

DES-CBC: OpenSSL 3 decrypts single DES only with its legacy provider, so the reference legacy core fails the ClientKey step for such a key, and the Rust core loads it (DV-TLS-2).

**Key/certificate match** (OpenSSL's `ssl_set_pkey` plus `SSL_CTX_check_private_key`). OpenSSL keeps one key slot per key type. Loading a key whose slot already holds a certificate checks the pair inside the key-load step; a key in a different slot than the certificate fails only at the check step. The design reproduces which step fails:

```text
leaf slot = slot(leaf SPKI algorithm)   rsaEncryption -> RSA, id-RSASSA-PSS -> RSA_PSS,
                                         id-ecPublicKey -> ECC, id-Ed25519 -> ED25519, id-Ed448 -> ED448
key slot  = slot(SigningKey::algorithm() and the key's SPKI algorithm)
key SPKI  = SigningKey::public_key()     (pinned rustls release; CI asserts Some for every corpus key)
if key slot == leaf slot:
    same key  = same algorithm OID, same curve OID for ECC, same subjectPublicKey bits
                (RSA parameters NULL or absent are treated alike)
    not same  -> step ClientKey fails        "Failed to load client private key from %s"
else          -> step CheckPrivateKey fails   "SSL_CTX_check_private_key failed"
```

A leaf whose EC point is in compressed form does not compare equal to the uncompressed key SPKI and fails at `ClientKey` (DV-TLS-5).

### 3.7 Log lines, rc and errno per step

The core emits every line through the pinned Site table (`LogSite`, generated from `abi/log-sites.tsv`; CC-30, 00 §4), so log callbacks obey the callback-frame rule and `func@line` stays `init_ssl_for_socket@<line>`. "Main" is the session connection (`fail_on_error = 1`); "Probe" is the RW probe (`fail_on_error = 0`).

| Step / event | Site (zookeeper.c) | Main: rc, errno, effect | Probe |
|---|---|---|---|
| `ClientConfig` builder fails (`SSL_CTX_new`) | ERROR "error creating ssl context" @2867, then `handle_socket_error_msg` "error creating ssl context" @2869 | `TlsResource{at: ContextNew}`: teardown `ZSSLCONNECTIONERROR` | the 2867 line; probe fails |
| `CaFile` | ERROR "Failed to load CA file %s" @2881 (arg: `ca`) | `TlsConfig{CaFile}`: `ZSSLCONNECTIONERROR`, `errno = EINVAL`, **no teardown** | same line, `errno = EINVAL`; probe fails |
| `DefaultVerifyPaths` | ERROR "Call to SSL_CTX_set_default_verify_paths failed" @2888 | unreachable: the only resource error is allocation failure, which aborts (07 P7; §3.4). The site stays in the pinned Site table, and the defined path is `TlsConfig{DefaultVerifyPaths}`, as CA file | unreachable; same line if reached |
| `ClientCertChain` | ERROR "Failed to load client certificate chain from %s" @2898 (arg: `cert`) | `TlsConfig{ClientCertChain}`, as CA file | same line; probe fails |
| `ClientKey` | ERROR "Failed to load client private key from %s" @2907 (arg: `key`) | `TlsConfig{ClientKey}`, as CA file | same line; probe fails |
| `CheckPrivateKey` | ERROR "SSL_CTX_check_private_key failed" @2915 | `TlsConfig{CheckPrivateKey}`, as CA file | same line; probe fails |
| `ClientConnection::new` fails (`SSL_new`) | `handle_socket_error_msg` "error creating ssl context" @2928 | `TlsResource{at: ConnectionNew}`: teardown `ZSSLCONNECTIONERROR` | ERROR "error creating ssl context" @2930 |
| Handshake fails (verify failure, alert, protocol error) | `handle_socket_error_msg` "error in ssl connect" @2959 | `TlsHandshake`: teardown `ZSSLCONNECTIONERROR` | ERROR "error in ssl connect" @2961 |
| Handshake deadline (2/3·T from TCP completion) | the receive-timeout line (02 §4.3; teardown cause `TlsHandshakeTimeout`) | teardown `ZOPERATIONTIMEOUT`, `errno = ETIMEDOUT` | the probe's own ≤1 s budget (DRV-13) |
| "error in ssl connect (after select)" @2968/2970 | kept in the Site table | unreachable: no `select` | — |

- **`handle_socket_error_msg` lines** print `errno=%d(%s)`. The value is the `errno` field of the `TransportFailure` variant, which the driver fills from the last failed socket call on that connection, or 0 (02 §4). The TLS layer never sets errno, so the value is typically `EAGAIN`, which matches what OpenSSL left in C. The value is log text only and not observable by applications (07 P6).
- **Config failure (CC-14, 00 §4).** No teardown; the phase becomes TlsConfigFailed: READ interest only, no WRITE interest, no retry. The receive rule (2/3·T) then tears down with `ZOPERATIONTIMEOUT`. EINPROGRESS path: `zookeeper_process` returns `ZSSLCONNECTIONERROR` (zookeeper.c:3093-3094), state stays 7. Immediate path: `zookeeper_interest` returns `ZSSLCONNECTIONERROR` via `api_epilog` (zookeeper.c:2717-2720) with the outputs C writes (`fd = -1, interest = 0, tv = 0`), the state unchanged (0/999), and `last_recv` not reset.
- In mt these rcs are internal to the IO thread, as in C; only the log lines and the later failover are visible.

### 3.8 TLS phases in zk-proto

02's inputs (CC-17, 00 §4) that TLS uses:

```rust
pub enum ConnectStart { InProgress, Immediate }
impl<T: Tokens> Connection<T> {
    pub fn handle_connect_started(&mut self, kind: ConnectStart, now: Millis);
    /// SO_ERROR == 0, or Immediate. TLS handles: called after new_conn succeeds;
    /// starts the handshake phase and its deadline.
    pub fn handle_tcp_connected(&mut self, now: Millis);
    /// TLS handshake done, or plaintext TCP up: queue the ConnectRequest, state 2.
    pub fn handle_transport_ready(&mut self, now: Millis);
    pub fn handle_transport_failed(&mut self, f: TransportFailure, now: Millis) -> i32;
}
pub enum TransportFailure {                 // 02 §4; errno is the value the log line prints
    SocketCreate { errno: i32 }, TcpRefused { errno: i32 }, Io { errno: i32 }, Eof,
    TlsResource { at: TlsResourceAt, errno: i32 },   // at selects the SSL_CTX_new or SSL_new site
    TlsHandshake { errno: i32 },
    TlsConfig { step: TlsConfigStep, file: JString }, // file: the step's %s argument, for the log line only
    Poll { errno: i32 },
}
pub enum TlsResourceAt { ContextNew, ConnectionNew }
pub enum TlsConfigStep { CaFile, DefaultVerifyPaths, ClientCertChain, ClientKey, CheckPrivateKey }
pub enum ProbeTlsFailure { Config { step: TlsConfigStep, file: JString }, Resource { at: TlsResourceAt }, Handshake }
```

Driver order on TCP-connect completion (EINPROGRESS: WRITE event with `SO_ERROR == 0`; Immediate: right after `connect`), as 03 §4.3.1:

1. Take `now`.
2. Call `TlsConnector::new_conn` with no lock held.
3. On success, call `handle_tcp_connected(now)`, which starts the handshake phase and its deadline, and send the first flight.
4. On `Config{step}`, call `handle_transport_failed(TlsConfig{step, file})`. `handle_tcp_connected` is not called, so on the immediate path `last_recv` keeps C's un-reset value.
5. On `Resource{at}`, call `handle_transport_failed(TlsResource{at, errno})`.

The RW probe reports its outcome through `handle_rw_probe(ep, ProbeOutcome, now)` (02 §4; 03 §2, DR4).

| Event | Visible state | Core action and rc | C reference / class |
|---|---|---|---|
| `connect()` EINPROGRESS, TLS | 7 | Phase TcpConnecting. Interest WRITE. Exempt from the receive timeout. `tv` = T/3 when `recv_to <= 0`, otherwise `min(recv_to, T/3)` (C: 0 when `recv_to <= 0`) | 2697-2705, 2749, 2806. Exemption RE; spin fix FU (SEC-2) |
| `connect()` immediate, TLS | unchanged (0/999) | setup, then `handle_tcp_connected`, in the same call. Handshake phase. Deadline 2/3·T from TCP-connect completion | 2715-2722 (C blocks inline with 0/999). State RE; non-blocking FU (SEC-1) |
| SO_ERROR != 0 in state 7 | → 0 | teardown `ZCONNECTIONLOSS` ("server refused to accept the client") | 3079-3090, RE |
| Setup → `TlsConfig{step, file}` | unchanged (7, or 0/999) | §3.7. `ZSSLCONNECTIONERROR`, `errno = EINVAL`, **no teardown**, TlsConfigFailed, receive deadline → `ZOPERATIONTIMEOUT` | 2717-2720, 3093-3094. rc and no-teardown RE; no spin and the deadline on the EINPROGRESS path FU (SEC-3) |
| Setup → `TlsResource{at}` | → 0 | teardown `ZSSLCONNECTIONERROR` | 2866-2870, 2924-2930, RE |
| Handshake fatal | → 0 | teardown `ZSSLCONNECTIONERROR` | 2946-2963, RE |
| Handshake deadline | → 0 | teardown `ZOPERATIONTIMEOUT`, `errno = ETIMEDOUT` | C: unbounded `SSL_connect`/`select` loop (2937-2975). FU (SEC-1) |
| Handshake complete | → 2 | `handle_transport_ready`: ConnectRequest queued and flushed in the same driving call. The transient state 1 is never observable | 2831-2841, 3099-3115, RE |
| Data-phase TLS error, close_notify or EOF | → 0 | after every frame decrypted before it is processed (§3.2): teardown `ZCONNECTIONLOSS` | 403-419, 1940-2003, RE (TLS-05) |
| Graceful close (`zookeeper_close` after the CloseSession exchange) | closed | `send_close_notify()`, non-blocking flush within the remaining close budget, socket closed; no wait for the peer's alert (§3.2) | 648-670 (no `SSL_shutdown`). Wire-only, 07 P6 (TLS-06) |

Interest and timeout during the handshake phase (07 ST-16):

- READ when the session wants ciphertext; WRITE only when ciphertext is pending. In the TlsHandshaking phase the core reports `write = false`, and the driver ORs in `has_ciphertext()`.
- `tv` is never later than the handshake deadline.
- If a complete ZooKeeper frame is already decrypted and buffered, `tv` = 0 (TLS-04).
- In st, `zookeeper_process` returns ZOK for every handshake step.
- No `select` and no `FD_SET`.
- **Windows, state 7.** Windows reports a refused non-blocking connect only through exceptfds, which the legacy mt loop does not pass (mt_adaptor.c:444), so legacy never sees the WRITE event of the SO_ERROR row and hangs in state 7. The driver reads SO_ERROR on each T/3 tick in state 7 and takes the SO_ERROR row (03 §8.8; WIN-05, FU as ST-16). Plain TCP connect (state 1) keeps the legacy behavior on Windows: POLLERR, POLLHUP and POLLNVAL on the connecting socket set no interest bit (03 §8.8), so the attempt waits for the 2/3·T receive timeout and fails over with `ZOPERATIONTIMEOUT` (WIN-05 RE).

These are consequences of SEC-1; the only call sequences they change are ones that hung or spun in C.

`ZOO_SSL_CONNECTING_STATE` remains an exported, undeclared `const int` 7. Watchers never receive 7. `zerror(ZSSLCONNECTIONERROR)` returns "ssl connection error" (DV-FIX-6, 07 RC-16; 05 §18).

### 3.9 Read-only probe

The probe (zookeeper.c:2461-2522) uses `TlsPurpose::Probe`, its own `RustlsConn` and the drivers' ≤1 s per-phase budget (DRV-13). Any probe TLS failure, including a config failure, is a probe failure: it logs the probe-column line of §3.7, sets `errno = EINVAL` for config failures, and never tears down the main connection. The driver reports it as `ProbeOutcome::Failed(Some(ProbeTlsFailure::Config { step, file }))`, `Resource { at }` or `Handshake` (02 §4). The probe sends `"isro"` with one `write_frame`, then follows 03 §8.4: it feeds ciphertext until the first plaintext bytes, EOF or the deadline, takes at most the first 10 plaintext bytes (C's single read of 10 bytes), and applies C's `strcmp(buf, "rw") == 0` rule over a zero-filled 10-byte buffer (success iff the first three bytes are `'r'`, `'w'`, `0`). A fatal alert that rustls queues during the probe follows §3.2: one non-blocking send when no earlier ciphertext is waiting; the probe never sends close_notify.

### 3.10 Cert string (`zookeeper_init_ssl`, shim C)

The body is 05 §15.1's:

```c
zhandle_t *zookeeper_init_ssl(const char *host, const char *cert, watcher_fn fn, int recv_timeout,
                              const clientid_t *clientid, void *context, int flags)
{
    struct zks_tls_args t = { 1, NULL, NULL, NULL, NULL };    /* tls=1, ca, cert, key, passwd */
    char *copy, *save = NULL; size_t n; zhandle_t *zh; int err;
    if (cert == NULL) { errno = EINVAL; return NULL; }       /* INIT-13: legacy strdup(NULL) crash */
    n = strlen(cert);
    if ((copy = strdup(cert)) == NULL) { errno = ENOMEM; return NULL; }
    t.ca     = strtok_r(copy, ",", &save);                   /* strtok_s on WIN32 */
    t.cert   = strtok_r(NULL, ",", &save);
    t.key    = strtok_r(NULL, ",", &save);
    t.passwd = strtok_r(NULL, ",", &save);
    zh = zks_init_common(host, fn, recv_timeout, clientid, context, flags, NULL, &t, NULL);
    err = errno;
    zks_secure_zero(copy, n); free(copy);                    /* Rust holds its own copies */
    errno = err;
    return zh;
}
```

Preserved (zookeeper.c:1521-1535):

- empty fields collapse, so `"ca,,key,pw"` gives cert=key and key=pw;
- at most 4 fields are used;
- whitespace is kept;
- `""` gives all four fields NULL with `tls = 1`, and each attempt then takes the CA-file config row;
- the client identity is used only when cert, key and passwd are all present.

zk-ffi copies `ca`, `cert` and `key` (paths, not secret) as owned bytes, and copies `passwd` into zeroizing storage with `abi::raw::cstr_secret` (05 §8) during `zkrs_handle_new`; the connector holds it as `Option<Zeroizing<Vec<u8>>>` until release (03 §8.2.3). The shim keeps no `zcert_t` and no cert-string copy in the handle: the cert string is copied at init, parsed, and zeroed and freed before init returns (legacy keeps `certstr` for the handle's lifetime, zookeeper.c:730-735; the difference is not observable).

**SEC-5 (FU).** Concurrent `zookeeper_init_ssl` calls do not race on a global `strtok` pointer; in legacy they do, which is a data race (UB). A caller's in-progress `strtok` scan is not clobbered, as it is in legacy; no correct program relies on that. 08 ER-05 checks the field-shift rows (EXACT); 08 §4.5 INIT-14 tests the clobber row and concurrent init as DEV.

### 3.11 Build options, headers and linkage

| Option (autotools / CMake) | Default | Exports | DT_NEEDED / link | Build host needs |
|---|---|---|---|---|
| `--with-openssl[=DIR]` / `WITH_OPENSSL` | `yes` / `ON`, which means autodetect, exactly as legacy. configure checks for `openssl/ssl.h` and `-lssl` `SSL_CTX_new` (configure.ac:42-67; `=DIR` adds `DIR/include` and `DIR/lib` to the check). CMake runs `find_package(OpenSSL)` and gates TLS on `OPENSSL_FOUND` (CMakeLists.txt:205-219; as in legacy, a `WITH_OPENSSL` path does not set OPENSSL_ROOT_DIR, because line 209 is `set(OPENSSL_ROOT_DIR,${WITH_OPENSSL})`). When OpenSSL is found, TLS is built. Otherwise configure warns "cannot build SSL support -- openssl not found" (CMake prints its legacy warning) and the library is built without TLS. `no` / `OFF` disables TLS. The probe only decides the feature: OpenSSL is never linked. With `--with-rust-client=side-by-side` the same probe result decides TLS for both trees; with `--with-rust-client=no` the legacy core keeps its TLS decision and behavior. | `zookeeper_init_ssl`, in exactly the configurations where legacy exports it | nothing for TLS (libssl and libcrypto are not DT_NEEDED: DV-TLS-4) | the OpenSSL headers and libssl for the probe only; TLS on selects zk-ffi `tls,tls-<provider>` |
| `--with-rust-tls=yes` / `WITH_RUST_TLS` (Maven: root property `rust-tls`, default `no`) | `no` / `OFF`. Opt-in and additive: enables TLS when the probe finds no OpenSSL headers. | `zookeeper_init_ssl` | nothing for TLS | no OpenSSL; selects zk-ffi `tls,tls-<provider>` |
| `--with-rust-tls-provider=ring\|aws-lc-rs\|aws-lc-rs-fips` / `ZK_RUST_TLS_PROVIDER` (Maven `rust-tls-provider`) | `ring` | — | — | `ring`: C compiler; `aws-lc-rs`: C compiler and the aws-lc-sys build requirements (01 §3.9); `aws-lc-rs-fips`: C compiler, CMake and Go, on Linux x86_64 or aarch64 (§3.15) |
| `--with-rust-tls-default-paths=FILE:DIR` / `ZK_RUST_TLS_DEFAULT_PATHS` | the OPENSSLDIR recorded at configure time: from `openssl version -d` of the probed installation's `openssl` binary (`$with_openssl/bin/openssl`, `$OPENSSL_ROOT_DIR/bin/openssl` in CMake, else the first on `PATH`), else the first existing entry of the platform list of 01 §6.1 (rule in §3.4). Passed to cargo as `ZK_TLS_DEFAULT_CERT_FILE` / `ZK_TLS_DEFAULT_CERT_DIR`. | — | — | — |
| `--with-sasl[=DIR]` / `WITH_CYRUS_SASL` | auto, as legacy | `zookeeper_init_sasl`, `zoo_sasl_make_*`, the 6 leaked `zoo_sasl_*` | libsasl2 (unchanged) | Cyrus headers |

- **Maven.** The Rust module builds before configure runs. `abi/tls-probe.sh` (01 §13.7) reproduces configure.ac:42-67 for the Maven build and emits the default trust paths by the rule of §3.4. The root property `rust-tls` (default `no`) maps to `--with-rust-tls` and to the zk-ffi feature set, so a Maven build takes the same TLS decision as configure.
- **ABI meaning kept.** `--with-openssl` decides whether `zookeeper_init_ssl` is exported by the same decision procedure as legacy, with the same flags, defaults and probe. `zookeeper_init_ssl` and `HAVE_OPENSSL_H` are therefore present in exactly the configurations where legacy has them. The golden subsets with and without TLS and with and without SASL are unchanged (INIT-16, K17). A build without TLS defines no `zookeeper_init_ssl` stub. Only the opt-in `--with-rust-tls=yes` exports `zookeeper_init_ssl` on a host where the probe finds no OpenSSL; it is off by default and has no legacy counterpart.
- **Headers unchanged.** `zookeeper.h` still includes `<openssl/ossl_typ.h>` when `HAVE_OPENSSL_H` is defined and `<sasl/sasl.h>` under `HAVE_CYRUS_SASL_H` (zookeeper.h:35-44); `zsock_t`/`zcert_t` keep their layout (302-324).
  - **Feature defines follow legacy exactly.** Autotools puts `-DHAVE_OPENSSL_H` in the library CPPFLAGS when TLS is built (Makefile.am `OPENSSL_CPPFLAGS`) and `-DHAVE_CYRUS_SASL_H` when SASL is built (`SASL_CPPFLAGS`). CMake sets `target_compile_definitions(zookeeper PUBLIC HAVE_OPENSSL_H)` when TLS is built and `PUBLIC HAVE_CYRUS_SASL_H` when SASL is built (CMakeLists.txt:213, 227), so CMake consumers get them as before. Autotools consumers define them themselves, as before.
  - "TLS is built" means that the legacy autodetect found OpenSSL, or that `--with-rust-tls=yes` / `WITH_RUST_TLS=ON` is set. With the opt-in on a host without OpenSSL headers, CMake consumers therefore also get `HAVE_OPENSSL_H`, and they need the OpenSSL headers to compile against `zookeeper.h`, as every consumer that defines it does (DV-TLS-4).
  - No OpenSSL library is in any link interface (DV-TLS-4).
- **Shim compilation.** The shim's own translation units do not depend on `HAVE_OPENSSL_H`. They use `ZKS_WITH_TLS`, and the shim declares `zookeeper_init_ssl` itself (its prototype uses no OpenSSL type). The shim TUs include `zookeeper.h` only through `zks_internal.h`, their first include (`zookeeper_log.h`, which includes `zookeeper.h`, comes after it; 05 §2), which undefines `HAVE_OPENSSL_H` before that include, so no shim TU includes an OpenSSL header or uses an OpenSSL type, also on a `--with-rust-tls=yes` host without OpenSSL headers. Outside the two SASL TUs (which define `ZKS_SASL_GLUE` before they include `zks_internal.h`), `zks_internal.h` also undefines `HAVE_CYRUS_SASL_H` before the include, so only those two TUs include `<sasl/sasl.h>` (§4.7.1); the others use `ZKS_WITH_SASL`. The library never reads or writes `zsock_t.ssl_sock`/`ssl_ctx`.
- **`close_zsock`** (static archives only, ABI-02, zookeeper.h:576) closes `sock` and sets it to -1. It does not read `ssl_sock`. Legacy reads it, and it lies beyond the struct when the application was compiled without `HAVE_OPENSSL_H` (out-of-bounds read; FU, SEC-17). It does not free application-placed OpenSSL objects (DV-TLS-4).
- **Closure (K14, 00 §3).** The supply-chain closure of the drop-in TLS build is `rustls`, `rustls-pki-types`, `rustls-webpki`, `ring` (or `aws-lc-rs` with `aws-lc-sys`, or `aws-lc-rs` with `aws-lc-fips-sys` in the FIPS build), `untrusted`, `zeroize`, `pkcs8`, `pkcs5`, `der`, `spki`, `pem-rfc7468`, `base64ct`, `aes`, `des`, `cbc`, `cipher`, `md-5`, `sha1`, `sha2`, `pbkdf2`, `hmac`, `scrypt`, and `rustix` (libc backend), with their transitive closure as `cargo tree` reports it. In the `aws-lc-rs-fips` build, `pkcs8` has only its `pkcs5` feature, `pkcs5` has no `pbes2`, `3des` or `sha1-insecure`, and `pbkdf2`, `scrypt`, `hmac`, `sha1`, `aes`, `cbc`, `cipher`, `des` and `md-5` are not in the closure (§3.15.3; 01 §3.3 R3).
  - It includes no `rustls-pemfile`, no `rustls-native-certs` and no `openssl-probe`. The PEM reader is `pem.rs`, because it must see RFC 1421 headers. The default trust locations are compile-time constants (§3.4), so no trust-location crate is needed.
  - Native `tls-rustls` uses the same trust loader (`trust.rs`), with no extra trust crates. The native-only feature `tls-platform-verifier` adds `rustls-platform-verifier` (§3.12); cargo-deny bans that crate from the zk-ffi closure, and it has its own native-feature closure golden.
  - All crates are pinned, `cargo-vet safe-to-deploy`, and recorded in one closure golden per provider build (01 §2, §3.3): `supply-chain/closure/zk-ffi-<target>.txt` (`ring`), `supply-chain/closure/zk-ffi-<target>-aws-lc-rs.txt` (`aws-lc-rs`) and `supply-chain/closure/zk-ffi-<target>-aws-lc-rs-fips.txt` (FIPS). In the system-crates build mode (01) the closure check compares crate names and semver-compatible ranges instead of exact versions (08 G1).
  - The crypto crates contain `unsafe` and assembly for constant-time primitives and CPU features. This is audited third-party code, and our crates stay `forbid(unsafe_code)`.
  - cargo-deny has build-specific exceptions (01 §3.7). `aws-lc-rs` is admitted only through the wrappers `rustls`, `rustls-webpki` and `apache-zookeeper-driver`, so it enters only through zk-driver's provider feature. `deny.toml` (the `ring` and `aws-lc-rs` builds) admits `aws-lc-sys` only with `wrappers = ["aws-lc-rs"]` and denies `aws-lc-fips-sys`; `deny-fips.toml` replaces that ban with `wrappers = ["aws-lc-rs"]` for `aws-lc-fips-sys`, plus its per-crate license exception (01 §3.7). The ring closure golden (08 G1) and G2 prove that the `ring` build contains none of the three.
- **Symbols.** ring's and aws-lc's C and assembly symbols are version-prefixed and are localized by the `ld -r` step (K16), so they cannot collide with an application's OpenSSL. On MSVC they stay visible in `zookeeper.lib` like the other Rust symbols (DV-BLD-3).
- **Licensing.** ring 0.17 (Apache-2.0 AND ISC), rustls (Apache-2.0/ISC/MIT) and RustCrypto (Apache-2.0/MIT) are ASF Category A. CC0-1.0 is a public-domain dedication, which ASF policy (apache.org/legal/resolved.html) allows in Apache products with attribution; cargo-deny allows it, and attribution goes in LICENSE/NOTICE as needed. aws-lc-sys and aws-lc-fips-sys include OpenSSL-licensed parts; the ASF legal review of both is a release prerequisite for the two aws-lc builds (§3.1, 00 §6).
- **Targets.** A target that ring does not support builds the legacy core: `auto` selects it with a notice (01 §6.1).

### 3.12 Native TLS (semantics; API names owned by 04 §16.1 `TlsOptions`)

- `Compat::NATIVE.tls_verify = WebPki`: `rustls::client::WebPkiServerVerifier::builder_with_provider(roots, provider)`. The `ServerName` is the **connect-string host token** (`Endpoint.host`; the resolver keeps `(token, SocketAddr)` pairs), not the resolved IP; IP-literal tokens verify against IP SANs.
- SNI is sent for DNS names and never for IP literals.
- Only the configured roots are trusted. System roots are added only when requested, through the same default-location loader as §3.4. zk-client computes the loader's `tls_env_allowed` input with rustix as `getuid() == geteuid() && getgid() == getegid()` on Unix, and true on Windows (as the shim sets it, 05 §6). The compile-time default locations come from zk-driver's build (§3.4: `ZK_TLS_DEFAULT_CERT_FILE`/`ZK_TLS_DEFAULT_CERT_DIR` when set at build time, else the 01 §6.1 platform list applied on the build host).
- **Zero usable roots (native only).** When system roots are requested and the shared loader reports zero usable roots, `TlsOptionsBuilder::build` fails with `TlsConfigError::DefaultRoots`. The drop-in `DefaultVerifyPaths` step never fails for that reason (§3.4), and `legacy_c_compat` keeps the drop-in rule.
- **Platform verifier (native only, opt-in).** The feature `tls-platform-verifier` adds `TlsOptionsBuilder::with_platform_verifier()`, which verifies through `rustls-platform-verifier`: the OS trust store and the OS verification policy (Security.framework on Apple targets, the CryptoAPI chain engine on Windows; on Linux and the BSDs, webpki over the roots that rustls-native-certs finds). On targets where the roots come from rustls-native-certs, `build()` refuses the platform verifier when `tls_env_allowed` is false, because that path reads `SSL_CERT_FILE`/`SSL_CERT_DIR` with no set-uid check (04 §16.1). `add_system_roots()` keeps the OpenSSL-location loader of §3.4. The drop-in never uses the platform verifier, and cargo-deny bans the crate from the zk-ffi closure (§3.11). The feature cannot be combined with `tls-aws-lc-rs-fips` (`compile_error!`), because the OS verifier checks signatures outside the FIPS module (§3.15.2; 04 §16.1).
- `danger_disable_hostname_verification` keeps chain verification and logs a WARN once per client.
- The client uses TLS 1.2 or later. The provider is the one that zk-client's provider feature selects: `tls-ring` (the default provider), `tls-aws-lc-rs` or `tls-aws-lc-rs-fips`, with the same build rules, release gate and FIPS behavior as the drop-in (§3.1, §3.15).
- `TlsOptions::legacy_c_compat(cert_string)` builds a `RustlsConnector` with `LegacyChainOnly` and the loaders of §3.4-§3.6, so a migrating application gets the drop-in's exact trust decision and key formats.
- The builder accepts the key formats of §3.6; passphrases are a zk-client `Zeroizing<String>` newtype (CC-19, 00 §4).
- The handshake is non-blocking with the same 2/3·T deadline from TCP completion. File loading runs with every lock released (thread driver) or in `spawn_blocking` (tokio).
- Feature: `tls-rustls` (CC-34, 00 §4), opt-in (the native default features are `tokio`, `tracing` and `stream`), with one provider feature that enables the zk-driver feature of the same name. It uses the drop-in's trust loader and adds no trust crates (§3.11).

### 3.13 TLS deviations (DEVIATE-VISIBLE; 00 §5.1)

Each deviation is part of the design and has the release-note text given in its last column. DV-TLS-4 applies to every TLS build; DV-TLS-1, DV-TLS-2, DV-TLS-3 and DV-TLS-5 apply to handles made by `zookeeper_init_ssl`; DV-TLS-6 applies to the FIPS build only.

| ID | Change | Justification and impact | Release note |
|---|---|---|---|
| **DV-TLS-1 Protocol floor** | TLS 1.2 and 1.3 only. Servers that offer only SSLv3, TLS 1.0 or TLS 1.1 do not connect; legacy connects to them when the system OpenSSL allows those versions. Server-initiated renegotiation is refused. | rustls implements neither old versions nor renegotiation. ZooKeeper servers default to TLSv1.2+ and do not renegotiate, so the practical impact is low. | "The C client supports TLS 1.2 and TLS 1.3 only and refuses renegotiation. Servers that offer only SSLv3, TLS 1.0 or TLS 1.1 do not connect." |
| **DV-TLS-2 Policy source** | `openssl.cnf`, distro crypto-policies, OpenSSL seclevel and OpenSSL FIPS mode do not influence the client. Default verify paths come from `SSL_CERT_FILE`/`SSL_CERT_DIR` or the build-time default (`--with-rust-tls-default-paths`, which defaults to the OPENSSLDIR recorded at configure time), not from the OPENSSLDIR compiled into the system OpenSSL at run time. Windows certificate stores are not consulted. The OpenSSL provider configuration does not apply either: keys encrypted with single DES (traditional DES-CBC), which OpenSSL 3 decrypts only with the legacy provider, load. Client certificate chains are not checked against the OpenSSL security level: a chain file with a SHA-1-signed certificate or a certificate with an RSA key under 2048 bits loads, where the reference legacy core can fail the ClientCertChain step. | The library contains no OpenSSL. On distributions whose OpenSSL uses the standard locations, the trust inputs are the same. FIPS operation comes from the FIPS provider build (§3.15), not from OpenSSL FIPS mode. | "System OpenSSL configuration does not apply to the C client: `openssl.cnf`, distribution crypto policies, the OpenSSL security level and OpenSSL FIPS mode have no effect. Trust locations are fixed at build time (`--with-rust-tls-default-paths`, by default the OPENSSLDIR found at configure time) or set at run time with `SSL_CERT_FILE` and `SSL_CERT_DIR`; set-uid and set-gid processes ignore the variables. Windows certificate stores are not used. For FIPS operation, build with `--with-rust-tls-provider=aws-lc-rs-fips`. Client keys encrypted with single DES (DES-CBC) load without the OpenSSL legacy provider. Client certificate chain files are not checked against the OpenSSL security level." |
| **DV-TLS-3 Algorithm and certificate acceptance** | Certificates are accepted with the rustls/webpki algorithm set; the v1-leaf verifier (§3.14) uses the same signature algorithms. Rejected where OpenSSL may accept: DSA keys; Ed448; ECDSA P-521 in the `ring` build (the aws-lc builds accept it) and ECDSA curves other than P-256, P-384 and P-521 in every build; RSA-PSS SPKI keys (`id-RSASSA-PSS`); critical extensions that the verifier does not process; certificates whose DER encoding the verifier rejects as malformed where OpenSSL tolerates it (for example a negative serial number, a duplicate extension, a non-canonical time encoding, or a v1 certificate that carries extensions; such a certificate in a trust file adds no trust input); RSA keys of 1024 to 2047 bits, which stock OpenSSL at security level 1 accepts; RSA keys of more than 8192 bits; signatures with SHA-224 (RSA or ECDSA), and ECDSA with SHA-512 on P-256 or P-384 in the `ring` build; v3-leaf chains with more than six intermediates (webpki's path-building limit); server certificates whose EKU allows only Netscape or Microsoft SGC, and SGC-only intermediates, on v3-leaf paths; directoryName name constraints on v3-leaf paths (webpki rejects them instead of applying them); a server certificate with basicConstraints CA:TRUE that is not self-signed (a CA certificate used as a server certificate); X.509 v2 certificates; a v3-leaf chain with a self-issued intermediate below a CA whose pathLenConstraint or nameConstraints OpenSSL does not apply to that self-issued certificate (webpki counts it and applies them); a v3-leaf chain in which a certificate's issuer name equals its issuer's subject name only in OpenSSL's canonical form, for example a different case or string type (webpki compares the names byte for byte); a server certificate or issuer whose EC public key uses a compressed point, in the `ring` build (ring verifies uncompressed points only); a self-signed leaf or a chain whose OpenSSL verdict depends on the order of several time-valid same-subject trust-store certificates (the mimic rule of §3.5, and the ambiguity rule and work budget of §3.14.3 in the v1-leaf verifier and the OpenSSL path post-check, fail closed); a v3-leaf chain with a certificate that a rejected `TRUSTED CERTIFICATE` input could issue, where OpenSSL's path does not use that input (the rejected-issuer post-check of §3.5 fails closed); a v3-leaf chain in which a server-sent certificate or a trusted intermediate that could issue a certificate of the path has keyUsage without keyCertSign, where OpenSSL's path does not use that certificate, for example because OpenSSL's issuer choice at that step takes another certificate of the same name (the order-dependent same-name case: the keyCertSign post-check of §3.5 fails closed); a trust certificate that OpenSSL 3.0 finds in a hash directory only through its default store lookup, in a file whose hash part is upper-case or whose suffix follows a gap (§3.4; OpenSSL 3.5 does not read such files either); a trust certificate in a hash-named file that also holds a corrupt block, which OpenSSL 3.0 and 3.5 keep (§3.4); a trust certificate in a hash-named file that is named for another subject's hash, which OpenSSL loads with that file (§3.4). SHA-1- and MD5-signed certificates and RSA keys under 2048 bits are rejected as OpenSSL 3 rejects them at security level 2 (self-signatures of anchors and of path-(b) leaves are checked by neither side). Cipher-suite and group offers differ (AEAD ECDHE suites only). | Consequence of the rustls/webpki algorithm set. It matches the policy of the common distribution OpenSSL configuration, SECLEVEL=2 (Debian, Ubuntu, RHEL), which also rejects SHA-1 and MD5 signatures and RSA keys under 2048 bits. The remaining differences are the cases listed; the differential corpus (§10) lists every such case and asserts rejection. | "The C client accepts the certificate algorithms of rustls/webpki, which match the OpenSSL SECLEVEL=2 policy of Debian, Ubuntu and RHEL. It rejects DSA keys, Ed448 keys, RSA-PSS public keys, P-521 keys in the default `ring` build, ECDSA keys on curves other than P-256, P-384 and P-521 (for example brainpool or secp256k1) in every build, RSA keys under 2048 bits (also on hosts whose OpenSSL runs at security level 1) or over 8192 bits, SHA-224 signatures, ECDSA signatures with SHA-512 on P-256 or P-384 keys in the default `ring` build, v3 certificate chains with more than six intermediates, critical extensions it does not process, certificates with malformed DER encodings (for example negative serial numbers or duplicate extensions), v3 server certificates (and their intermediates) whose extended key usage allows only SGC, v3 certificate chains below a CA certificate with directoryName name constraints, a server certificate with basicConstraints CA:TRUE that is not self-signed (a CA certificate used as a server certificate), and X.509 v2 certificates. Only AEAD cipher suites with ECDHE key exchange are offered (the TLS 1.3 suites, and on TLS 1.2 ECDHE with AES-GCM or ChaCha20-Poly1305), so TLS 1.2 servers that offer only CBC, static-RSA or DHE suites do not connect. When the trust store (the CA file and the default locations) holds several certificates with the same subject and OpenSSL's verdict would depend on their order in the store, the server certificate is rejected, for X.509 v1 and v3 certificates alike. v3 chains with a certificate that a trust-store entry marked as rejected could issue are rejected. v3 chains are also rejected when the server sends, or the trust store holds as an intermediate, a CA certificate whose key usage lacks certificate signing and that could issue a certificate of the chain, even where OpenSSL's chain does not use that certificate. v3 chains are rejected when a self-issued intermediate exceeds a path length constraint counted with self-issued certificates. v3 chains are also rejected when a self-issued intermediate has a name outside a name constraint of a CA above it. v3 chains are rejected when a certificate's issuer name and its issuer's subject name differ in case, spacing or string encoding. In the default `ring` build, certificates with compressed EC points are rejected. Reissue such certificates with RSA keys of 2048 to 8192 bits, P-256, P-384 or Ed25519. Hash directories are read by lower-case `HHHHHHHH.N` names with suffixes from `.0` up to the first gap or the first file that does not load. A file with a corrupt block contributes no certificate. Each certificate must be in a file named for its own subject hash. Repair such files, and run `openssl rehash` on a directory whose certificates OpenSSL found under other names." |
| **DV-TLS-4 Linkage** | libssl and libcrypto are not DT_NEEDED, not in `.la` `dependency_libs`, not in `.pc` `Libs.private`, and not in the CMake link interface of the `zookeeper` target. Applications that used OpenSSL symbols without linking OpenSSL themselves must add `-lssl -lcrypto`. The library performs no process-global OpenSSL initialization and touches no OpenSSL error queue. Applications that compile with `HAVE_OPENSSL_H` still need the OpenSSL headers, as in legacy. Static-archive `close_zsock` does not free `ssl_sock`/`ssl_ctx`. The OpenSSL probe still decides whether TLS is built, so the default export set and `HAVE_OPENSSL_H` match legacy on every host (§3.11). | The library contains no OpenSSL code, so it neither links nor initializes OpenSSL. `close_zsock` is an undocumented internal; the library itself never populates those fields. | "libzookeeper does not link libssl or libcrypto and does not initialize OpenSSL. Applications that use OpenSSL symbols without linking OpenSSL themselves must add `-lssl -lcrypto` to their link line. Applications that define `HAVE_OPENSSL_H` still need the OpenSSL headers. The static-archive `close_zsock` does not free `ssl_sock` or `ssl_ctx`." |
| **DV-TLS-5 Key formats** | PKCS#12 and engine/provider keys are not supported, as in legacy. Rejected with the legacy "Failed to load client private key from %s" line: traditional PEM encryption other than AES-128/192/256-CBC, DES-EDE3-CBC and DES-CBC (for example `AES-*-CFB`, `CAMELLIA-*`); encrypted PKCS#8 with PKCS#12 PBE schemes (OpenSSL 1.0's `pkcs8 -topk8` default, `-v1 PBE-SHA1-3DES`; PBES1, which uses single DES or RC2, fails in both cores under the OpenSSL 3 default providers); PBES2 with a PBKDF2 PRF other than HMAC-SHA1, HMAC-SHA224, HMAC-SHA256, HMAC-SHA384 or HMAC-SHA512 (for example `-v2prf hmacWithSHA512-256` or `hmacWithSM3`); PBES2 with ciphers other than AES-CBC or DES-EDE3-CBC; DSA, Ed448, RSA-PSS (id-RSASSA-PSS) and unsupported-curve keys (for example P-521 in the `ring` build); RSA keys under 2048 bits; any other key that the build's provider key loader refuses (in the `ring` build, RSA keys above its maximum modulus size or with a public exponent below 65537, and SEC1 keys without the public key); EC keys with explicit curve parameters; a leaf with a compressed EC point. | The accepted set is the one of §3.6. The common OpenSSL 1.1+/3.x defaults (PBES2, PBKDF2-HMAC-SHA256, AES-256-CBC), the PBKDF2 PRFs HMAC-SHA1, -SHA224, -SHA256, -SHA384 and -SHA512, and all traditional AES, 3DES and DES encryptions are supported. | "Client keys in PKCS#12, engine or provider form are not supported, as before. Keys encrypted with PKCS#12 PBE, with a PBKDF2 PRF outside HMAC-SHA1/224/256/384/512, or with a cipher other than AES-CBC and DES-EDE3-CBC (traditional PEM: also DES-CBC) fail with the log line 'Failed to load client private key from …'. Convert such a key with `openssl pkcs8 -topk8 -v2 aes-256-cbc -in old-key.pem -out new-key.pem`. Client keys of type DSA, Ed448 or RSA-PSS, EC keys on curves the provider does not support or with explicit curve parameters, and a client certificate with a compressed EC point also fail with that line; use RSA of at least 2048 bits with the public exponent 65537 and a modulus no larger than the provider supports (PKCS#1 or PKCS#8 rsaEncryption), P-256, P-384 or Ed25519 keys. In the default `ring` build, a SEC1 key file must include the public key." |
| **DV-TLS-6 FIPS build key formats** (FIPS build only) | The FIPS build accepts unencrypted PKCS#8, PKCS#1 and SEC1 keys, and encrypted PKCS#8 with PBES2, PBKDF2 with HMAC-SHA256, HMAC-SHA384 or HMAC-SHA512, and AES-128/192/256-CBC, decrypted in the FIPS module (§3.15.3). It rejects, with the `ClientKey` line: traditional PEM encryption (MD5-based `EVP_BytesToKey`, with any cipher), DES-EDE3-CBC and DES-CBC, PBKDF2 with HMAC-SHA1 or HMAC-SHA224, and scrypt. The build also requires Extended Master Secret on TLS 1.2 (`require_ems`) and offers and accepts only the FIPS provider's cipher suites, key-exchange groups and signature algorithms, a subset of the DV-TLS-3 set. | Only FIPS-approved algorithms run, and only inside the FIPS module. MD5, DES and scrypt are not approved. The PBKDF2 interface that the build uses, `aws_lc_rs::pbkdf2`, offers HMAC-SHA256, -SHA384 and -SHA512 as SHA-2 PRFs (no SHA-224), and the build takes those only (§3.15.3). | "In the FIPS build (`--with-rust-tls-provider=aws-lc-rs-fips`), an encrypted client key must use PBES2 with PBKDF2-HMAC-SHA256, -SHA384 or -SHA512 and AES-CBC. Convert other keys with `openssl pkcs8 -topk8 -v2 aes-256-cbc -v2prf hmacWithSHA256 -in old-key.pem -out new-key.pem`. TLS 1.2 servers must support Extended Master Secret, and only FIPS-approved cipher suites, groups and signature algorithms are used." |

The OpenSSL error-queue parts of TLS-03 and TLS-05 do not apply to rustls. The risk that the compat verifier differs from OpenSSL trust decisions is covered by the corpus of §10 (00 §7).

### 3.14 The v1-leaf verifier (`v1_leaf.rs`)

#### 3.14.1 Scope

`LegacyChainOnly` accepts X.509 v1 certificates where OpenSSL accepts them:

- **v1 leaves at any depth below a valid chain.** A v1 end-entity certificate that is not accepted by path (b) is accepted when it chains to an anchor by OpenSSL's rules, through up to `MAX_DEPTH` (100) v3 intermediates, OpenSSL's default verify depth (§3.14.3).
- **v1 self-signed anchors.** `X509_check_ca` returns 3 for a self-signed v1 certificate, so `classify` makes it an anchor (§3.4), and both this verifier and the webpki path (`RootCertStore::add`) accept it as a chain end.
- **v1 intermediates are rejected**, as OpenSSL rejects them: `X509_check_ca` returns 0 for a v1 certificate that is not self-signed, and OpenSSL requires 1 for an intermediate (`X509_V_ERR_INVALID_CA`, error 79).

webpki does not parse v1 certificates, so v3 leaves go through webpki (§3.5) and v1 leaves go through this verifier. The verifier is the acceptance path for v1 leaves only.

- **OpenSSL path post-check for v3 leaves.** The same module also runs, as `verify_v3_path`, after webpki has accepted a v3 leaf (§3.5). It applies the issuer selection and the finish rules of §3.14.3 to the path that OpenSSL would build, with the leaf's own EKU, keyUsage and nsCertType purpose and its subjectAltName. It can only reject: a v3 leaf is accepted only when webpki and this check both accept, so the verdict is never wider than either.

The case matters because ZooKeeper operators follow the documented examples: `ssl/gencerts.sh` signs the client and server leaves with `openssl x509 -req` (lines 82 and 113), which makes v1 leaves on OpenSSL ≤ 3.1. Operators who follow the script with their own DNs get a v1 leaf whose DN differs from its CA's, and OpenSSL accepts such a leaf when its CA is trusted. (The in-tree fixture gives the leaf the root's DN, which makes it OpenSSL-self-signed; path (b) covers that case, §3.5.)

The module is `#![forbid(unsafe_code)]` in zk-driver behind feature `tls`. It parses with `LegacyCert` (`x509.rs`), which exposes for this purpose the TBS bytes, the signature algorithm and value, the validity, basicConstraints with pathLenConstraint, keyUsage, extKeyUsage, nsCertType, SKID and AKID, subjectAltName, nameConstraints and the list of critical extensions. Signatures are checked only through the provider's `SignatureVerificationAlgorithm` objects.

#### 3.14.2 Interface

```rust
// zk-driver/src/net/tls/v1_leaf.rs   (#![forbid(unsafe_code)], feature "tls")
pub(crate) const MAX_DEPTH: usize = 100;      // OpenSSL's default verify depth (intermediates)
pub(crate) const WORK_BUDGET: usize = 256;    // candidate evaluations per verification

/// Never accepts where OpenSSL rejects; rejects only in the DV-TLS-3 cases (and, in the FIPS build, the DV-TLS-6 cases) where OpenSSL may accept. L is v1 and not OpenSSL-self-signed (path (b) is decided first).
pub(crate) fn verify(leaf: &LegacyCert<'_>, sent: &[LegacyCert<'_>], trust: &TrustSet,
                     now: UnixTime, algs: &[&dyn SignatureVerificationAlgorithm])
    -> Result<(), V1Reject>;
/// The OpenSSL path post-check of §3.5: the same rules for a v3 leaf that webpki has accepted.
/// L is v3 and not OpenSSL-self-signed. Never accepts where OpenSSL rejects.
pub(crate) fn verify_v3_path(leaf: &LegacyCert<'_>, sent: &[LegacyCert<'_>], trust: &TrustSet,
                             now: UnixTime, algs: &[&dyn SignatureVerificationAlgorithm])
    -> Result<(), V1Reject>;
pub(crate) enum V1Reject { InvalidCa, PathLength, Purpose, Expired, Signature, NoIssuer,
                           NameConstraints, CriticalExtension, ChainTooLong, Ambiguous, Budget,
                           KeyUsage, Rejected }
```

`algs` is `WebPkiSupportedAlgorithms::all` of the build's provider, the same set as the webpki path. `V1Reject` is for tests and DEBUG logs; every variant is a handshake failure (§3.5, Outcome).

#### 3.14.3 Algorithm

```text
verify(L, sent, trust, now):
  require L.version == v1, not openssl_self_signed(L), no extensions field   # else: not this verifier / reject
  require valid_at(L, now)                                 # notBefore <= now < notAfter
  require ssl_server_purpose(L)                            # §3.5; a v1 leaf has no EKU, keyUsage or nsCertType
  return extend([L])

verify_v3_path(L, sent, trust, now):                       # OpenSSL path post-check (§3.5), after webpki accepts
  require L.version == v3, not openssl_self_signed(L)
  require valid_at(L, now)                                 # notBefore <= now < notAfter
  require ssl_server_purpose(L)                            # §3.5: the leaf's EKU, keyUsage and nsCertType
  return extend([L])                                       # same selection and finish; name constraints
                                                           # use L's subjectAltName (below)

extend(chain):                                             # x is the top of the chain
  x = last(chain)
  if intermediates(chain) > MAX_DEPTH: reject ChainTooLong # X509_V_ERR_CERT_CHAIN_TOO_LONG (22)
  T = { t in trust.certs : issued(t, x), t not in chain }  # trust set first (X509_V_FLAG_TRUSTED_FIRST);
                                                           # Rejected and no-keyCertSign inputs included
  if T is not empty:
      V = { t in T : valid_at(t, now) }                    # distinct by DER
      if V is empty: reject Expired                        # OpenSSL takes an expired store issuer, then fails
      return all(step(chain, t) for t in V)                # ambiguity rule; |V| == 1 in practice
  if a certificate taken from the trust set is already in chain: reject NoIssuer
                                                           # OpenSSL stops using sent certificates once
                                                           # a store certificate is added (error 2)
  if x is OpenSSL-self-signed: reject NoIssuer             # no sent issuer above a self-signed sent
                                                           # certificate (error 19)
  S = [ c in sent, in sent order : issued(c, x), c not in chain ]
  if S is empty: reject NoIssuer                           # error 2 / 20
  y = first c in S with valid_at(c, now), else the c in S with the latest notAfter   # find_issuer
  return step(chain, y)

step(chain, y):
  spend one unit of WORK_BUDGET                            # exhausted: reject Budget
  if y is Role::Rejected in trust: reject Rejected         # X509_V_ERR_CERT_REJECTED (28), check_trust
  if y has keyUsage without keyCertSign: reject KeyUsage   # X509_V_ERR_KEYUSAGE_NO_CERTSIGN (32)
  verify_sig(last(chain), y)                               # -> Signature
  if y is an anchor (Role::Anchor in trust): return finish(chain ++ [y])
  return extend(chain ++ [y])                              # a sent certificate goes on upward;
                                                           # a bridge goes on through the trust set only

finish(chain = [L, c1, ..., cn, A]):
  every c in chain is valid_at(now)                        # -> Expired (a1 for A)
  every ci: check_ca(ci) == 1                              # -> InvalidCa (79); v1 intermediates give 0
  check_ca(A) != 0                                         # -> InvalidCa
  every ci and A: ssl_server CA purpose                    # -> Purpose (26); A: aux trust first (§3.4)
  every pathLenConstraint holds                            # -> PathLength (25)
  every name constraint holds                              # -> NameConstraints
  no certificate has a critical extension outside the processed set   # -> CriticalExtension (34)
  accept
```

The rules, as OpenSSL 3.x `x509_vfy.c` and `v3_purp.c` apply them with the parameters of §3.5:

- **`issued(y, x)`** is `ossl_x509_likely_issued`, the test that OpenSSL 3.x chain building uses (the `check_issued` callback, `X509_STORE_CTX_get1_issuer`, `find_issuer`): `canon(x.issuer) == canon(y.subject)` (the canonical form of §3.4, as `X509_NAME_cmp`); x's AKID is consistent with y per `X509_check_akid`; and the key type of x's signature algorithm matches y's SPKI key type. It is the same test as `check_issued` in §3.5. keyUsage does not take part in issuer selection. OpenSSL checks it later, in `internal_verify` (`ossl_x509_signing_allowed`, error 32), so `step` checks it on the chosen issuer before the signature. T, V and S therefore contain issuers without keyCertSign, as OpenSSL's candidate sets do: when OpenSSL's trusted-first or `find_issuer` choice is such an issuer, both reject, and the ambiguity rule sees it as one of the candidates. On a v3-leaf path webpki compares issuer and subject names byte for byte (DV-TLS-3).
- **Rejected inputs** (§3.4) are issuer candidates in T, as OpenSSL keeps them in the store. When `step` reaches one, the chain fails (`Rejected`, error 28), as `check_trust` fails it.
- **`check_ca`** is `X509_check_ca` as defined in §3.4. An intermediate must give exactly 1 (basicConstraints `CA:TRUE`, and keyCertSign when keyUsage is present), because OpenSSL treats `X509_V_FLAG_X509_STRICT` as implicit for intermediates. The anchor must give a non-zero value, so a self-signed v1 anchor (3) is accepted.
- **Purpose.** Each CA certificate passes `check_purpose_ssl_server(x, ca = 1)`: EKU absent or allowing serverAuth or SGC (`xku_reject(x, XKU_SSL_SERVER|XKU_SGC)`), then `check_ssl_ca` (for `check_ca` value 5, the Netscape SSL CA bit). For the anchor, a `TRUSTED CERTIFICATE` aux trust list that names serverAuth or anyExtendedKeyUsage satisfies the purpose, as `check_purpose` consults `X509_check_trust` first for trusted certificates; a Rejected input never reaches `finish`, because `step` rejects it (§3.4).
- **Path length.** A CA's pathLenConstraint bounds the number of intermediates below it that are not self-issued (issuer == subject), as OpenSSL counts them (`X509_V_ERR_PATH_LENGTH_EXCEEDED`). The anchor's constraint counts too. On a v3-leaf path webpki also counts self-issued intermediates (DV-TLS-3).
- **Validity (the notAfter rule).** Every certificate of the path, the anchor included, must satisfy `notBefore <= now < notAfter`, the OpenSSL rule of §3.5. Among sent certificates OpenSSL's `find_issuer` takes the first time-valid issuer and otherwise the one with the latest notAfter, which then fails the validity check; the verifier makes the same choice, so an expired candidate never ends up in an accepted path.
- **Signatures.** `verify_sig(x, y)` checks x's signature with y's SPKI: it selects the algorithm in `algs` whose `signature_alg_id()` equals x's signatureAlgorithm and whose `public_key_alg_id()` equals y's SPKI algorithm, and calls `verify_signature(y.spki.key_bits, x.tbs, x.signature)`. x's `tbsCertificate.signature` must equal its outer signatureAlgorithm, as webpki requires. No match means an unsupported algorithm (DV-TLS-3). In the `ring` build the check fails for an issuer key with a compressed EC point (DV-TLS-3). The anchor's own self-signature is not verified, as in OpenSSL without `X509_V_FLAG_CHECK_SS_SIGNATURE`.
- **Name constraints** (`NAME_CONSTRAINTS_check`). Each CA's nameConstraints apply to every certificate below it that is not self-issued, and always to the leaf: the subject DN against directoryName subtrees, subject emailAddress attributes and rfc822Name SANs against rfc822Name subtrees, dNSName and iPAddress SANs against their subtrees, and for the leaf, when it has no dNSName SAN, its subject CN when it has host-name syntax against dNSName subtrees (`NAME_CONSTRAINTS_check_CN`; a v1 leaf has no SAN, so its CN is always checked). A constraint of a form OpenSSL does not support fails the path, as in OpenSSL. On a v3-leaf path webpki also applies nameConstraints to self-issued intermediates (DV-TLS-3).
- **Critical extensions.** The processed set is basicConstraints, keyUsage, extKeyUsage, nsCertType, subjectAltName and nameConstraints. A critical extension outside it rejects the path (DV-TLS-3). On a v3-leaf path webpki applies its own set, which has no nsCertType, so there a critical nsCertType also rejects (DV-TLS-3). Path (b) applies the processed set to the self-signed leaf (§3.5), and aux trust does not override it.
- **Depth.** At most `MAX_DEPTH` = 100 intermediates, OpenSSL's default verify depth; legacy sets no depth (zookeeper.c has no `SSL_CTX_set_verify_depth` call). The webpki path keeps webpki's limit of six (DV-TLS-3).
- **Candidate order.** At each step the trust set is consulted first and the sent certificates second, as `X509_V_FLAG_TRUSTED_FIRST` (on by default) does. A bridge continues the path upward through the trust set only (rule (d)), because once OpenSSL adds a store certificate it does not consult the sent certificates again. The leaf does not count as a store certificate, even when it is byte-identical to one. A self-signed sent certificate has no sent issuer above it. Because the trust set is consulted first at every step, OpenSSL's alternative-chain retry finds no path that this order misses.
- **Ambiguity rule.** The order among several matching trust-store certificates is internal to OpenSSL (as for the mimic rule of §3.5). When more than one distinct time-valid trust-set certificate issues the top certificate, the verifier evaluates each and accepts only when every one leads to acceptance; otherwise it rejects (`Ambiguous`). It therefore accepts only when OpenSSL's choice cannot matter, and fails closed otherwise. The ambiguity and budget rejections are DV-TLS-3 cases, on v1 leaves and in the v3 post-check alike (08 TC-40, TC-42).
- **Work budget.** Each candidate evaluation spends one unit of `WORK_BUDGET` (256), far above any real chain; an exhausted budget rejects. This bounds the work that a hostile set of same-name certificates can cause.

The TLS handshake signature of a v1 leaf is checked from its SPKI (§3.5, "Handshake signatures from the SPKI"). `LegacyChainOnly` applies `leaf_key_usable` to L (§3.5) before `verify` or `verify_v3_path` runs, because `verify_sig` reads only issuer keys.

#### 3.14.4 Composition with the other paths

- Path (b) runs first. A v1 leaf that is OpenSSL-self-signed is decided by path (b) only (accepted when byte-identical to a trust-set certificate, otherwise rejected), as in OpenSSL.
- A v1 leaf that is byte-identical to a trust-set certificate but not self-signed gets no special treatment; this verifier decides it, as OpenSSL builds its chain.
- The verifier reads the same `TrustSet` (anchors, bridges, and Rejected inputs, which stay issuer candidates and fail the chain when chosen) as path (a), so the trust inputs of §3.4 mean the same for v1 and v3 leaves.
- A v3 leaf is accepted only when `verify_server_cert_signed_by_trust_anchor`, the keyCertSign post-check, the rejected-issuer post-check and `verify_v3_path` all accept (§3.5). `verify_v3_path` never accepts a leaf that webpki rejected.

#### 3.14.5 FIPS build

In the FIPS build `algs` comes from `rustls::crypto::default_fips_provider()`, so every signature check of this verifier runs in the FIPS module (§3.15). The verifier computes no digest of its own; the SHA-1 subject hash that the trust loader uses for hash directories (§3.4) is also computed by the FIPS module.

#### 3.14.6 Differential tests

The v1-leaf cases are part of the trust-decision corpus (§10; 08 §5.2). Each case runs the O2 invocation of 08 §5.1 (`openssl verify -purpose sslserver -CAfile <ca> -untrusted <chain> -attime T -auth_level L <leaf>`, with SSL_CERT_DIR at the case's defaults/ directory (08 §5.1 for default-file cases)) under OpenSSL 3.0 and 3.5 (the pinned reference releases, 08 §4.1), `v1_leaf::verify` through `LegacyChainOnly` with the same inputs and the same fixed `now`, and, in the end-to-end tier, real handshakes of both cores. Accept or reject must agree in every case, except cases tagged DV-TLS-3 (every provider build) or DV-TLS-6 (FIPS build), which must reject. Cases:

- a v1 leaf made as `gencerts.sh` makes it (`openssl x509 -req` on OpenSSL 3.0) but with a DN that differs from the root's, signed by `root.crt`: with `root.crt` in the CA file (accept), and with the root absent (reject);
- a v1 leaf under one, two and seven v3 intermediates; an intermediate as a trusted bridge in the CA file with the root in the default directory; a v1 leaf under a v1 self-signed root (accept);
- a v1 intermediate (reject, error 79); an intermediate without basicConstraints, with `CA:FALSE`, or with keyUsage lacking keyCertSign (reject);
- a bridge in the CA file with the root, whose own issuer only the server sends (reject, error 2); a self-signed sent certificate before a certificate with the same name and key issued by the trusted root (reject, error 19); the same pair in the other sent order (accept); each with a v1 and a v3 leaf (08 TC-49);
- pathLenConstraint 0 on the root over one intermediate (reject); pathLenConstraint 0 over a self-issued intermediate (accept); pathLenConstraint 0 on an intermediate CA over a self-issued intermediate, and a self-issued intermediate outside the permitted name subtree of the CA above it (accept, 08 TC-52); an issuer name that equals its issuer's subject name only in OpenSSL's canonical form (accept, 08 TC-53);
- an intermediate whose EKU is clientAuth only (reject); an intermediate whose EKU is SGC only (accept, as OpenSSL);
- expired, not-yet-valid and `now == notAfter` leaf, intermediate and root; an expired and a valid same-name intermediate in both sent orders;
- a leaf signature that does not verify under the named issuer; an intermediate whose AKID does not match its issuer's SKID; two time-valid same-subject roots with different keys (ambiguity rule: reject unless both accept; DV-TLS-3 where OpenSSL accepts by store order);
- two same-name issuers, one without keyCertSign, in both orders (trust set and sent list): REJECT on both cores where OpenSSL picks the no-certsign one (the oracle with error 79 from `X509_check_ca`; the Rust reason is 32); in the trust set the ambiguity rule rejects both orders (DV-TLS-3 where OpenSSL accepts by store order);
- a `TRUSTED CERTIFICATE` intermediate in the CA file, with a serverAuth reject list or with a trust list that names neither serverAuth nor anyExtendedKeyUsage, that the server also sends, with the root trusted (REJECT on both cores, error 28);
- name constraints: a directoryName subtree that excludes the leaf DN, a permitted dNSName subtree that the leaf CN does not match, a subject emailAddress against rfc822Name subtrees;
- 101 intermediates (reject, error 22); a set of same-name certificates that exhausts the work budget (reject; DV-TLS-3 where OpenSSL accepts by store order);
- a SHA-1-signed v1 leaf and an RSA-1024 issuer (DV-TLS-3: reject); an unknown critical extension on an intermediate (reject in both).

The OpenSSL path post-check (`verify_v3_path`) is tested the same way by the v3-leaf post-check cases of §10 (08 TC-44): each is a chain that webpki alone accepts and OpenSSL rejects, and both cores must reject it. For a v3 leaf, the two same-name issuers in the sent list also reach the keyCertSign post-check (§3.5), which rejects both orders (08 TC-47: DV-TLS-3 where OpenSSL accepts by its issuer choice); for a v1 leaf the sent-list case is decided by OpenSSL's issuer choice alone, as listed above. The ambiguity and budget cases also run with a v3 leaf (08 TC-40, TC-42).

08 F15 fuzzes the verifier with the other trust-decision inputs. Its pure-Rust parts run under Miri; only tests that reach the provider skip Miri (08 §8.6).

### 3.15 Provider builds and the FIPS build

#### 3.15.1 The three builds

| Build | Selection | Cargo features | Sys crate | Build host | Closure golden | Targets |
|---|---|---|---|---|---|---|
| `ring` (default) | `--with-rust-tls-provider=ring` | `tls,tls-ring` | — (ring's own C and assembly) | C compiler | `zk-ffi-<target>.txt` | every target ring supports |
| `aws-lc-rs` | `--with-rust-tls-provider=aws-lc-rs` | `tls,tls-aws-lc-rs` | `aws-lc-sys` | C compiler, aws-lc-sys build requirements | `zk-ffi-<target>-aws-lc-rs.txt` | every target aws-lc-rs supports (CI and closure golden: Linux x86_64 and aarch64) |
| `aws-lc-rs-fips` | `--with-rust-tls-provider=aws-lc-rs-fips` | `tls,tls-aws-lc-rs-fips` | `aws-lc-fips-sys` | C compiler, CMake, Go | `zk-ffi-<target>-aws-lc-rs-fips.txt` | Linux x86_64 and aarch64 |

- CMake `ZK_RUST_TLS_PROVIDER` and the Maven property `rust-tls-provider` take the same three values. zk-client has the same provider features (§3.12).
- cargo-deny runs per build with that build's exceptions (§3.11). CI has jobs for the `aws-lc-rs` and FIPS builds on Linux x86_64 and aarch64 (08 §14), which run TL-*, TK-*, the trust and key corpora with provider-tagged verdicts, and TI-09.
- **Release gate.** The ASF legal review of `aws-lc-sys` and `aws-lc-fips-sys` must pass before either aws-lc build is in a release (00 §6, release prerequisites). `ring` stays the default.

#### 3.15.2 FIPS configuration

- **Crates.** aws-lc-rs with feature `fips` (sys crate `aws-lc-fips-sys`) and rustls with feature `fips`. The provider is `rustls::crypto::default_fips_provider()`.
- **Assertion at init.** zk-ffi builds the provider once at handle creation and asserts `provider.fips()`; a false result is a build defect and aborts with the standard panic message (DV-BLD-5). zk-client checks instead of aborting: `TlsOptionsBuilder::build` and `legacy_c_compat` check `ClientConfig::fips()` and report `TlsConfigError::NotFips`, and `ClientBuilder::build`/`connect` make the same check for a `from_rustls` config (04 §16.1).
- **Per attempt.** The config of §3.3 keeps `require_ems = true` (the rustls default with `fips`), so a TLS 1.2 server must support Extended Master Secret. After building the config the connector checks `cfg.fips()`. A false result fails the attempt as `TlsResource{at: ContextNew}` (§3.7), which fails closed and cannot occur with a correct build.
- **Algorithms.** Only the FIPS provider's cipher suites, key-exchange groups and signature verification algorithms are offered and accepted; the design adds none.
- **Signature checks.** Every certificate and handshake signature check uses the FIPS provider's `SignatureVerificationAlgorithm` objects, and so only the FIPS module: the webpki path of `LegacyChainOnly`, the v1-leaf verifier (§3.14.5), and the TLS 1.2 and 1.3 handshake signatures from the SPKI (§3.5). The client key is loaded by the FIPS provider's `key_provider`. The SHA-1 subject hash of §3.4 is computed by the module (a non-signature use).
- **Configuration and failure rules** are those of §3.3-§3.8 unchanged; only the accepted key formats differ (§3.15.3).

#### 3.15.3 Key loading in the FIPS build

- Unencrypted `PRIVATE KEY`, `RSA PRIVATE KEY` and `EC PRIVATE KEY` blocks load as in §3.6.
- `ENCRYPTED PRIVATE KEY` is parsed with `pkcs8::EncryptedPrivateKeyInfo` and accepted only for PBES2 with PBKDF2 and AES-CBC:
  - the PRF is HMAC-SHA256, HMAC-SHA384 or HMAC-SHA512, derived with `aws_lc_rs::pbkdf2::derive` and `PBKDF2_HMAC_SHA256`, `PBKDF2_HMAC_SHA384` or `PBKDF2_HMAC_SHA512`; the iteration count must be between 1 and `u32::MAX`, and an explicit `keyLength` must equal the cipher's key length;
  - the cipher is AES-128-CBC, AES-192-CBC or AES-256-CBC, decrypted with `aws_lc_rs::cipher::PaddedBlockDecryptingKey::cbc_pkcs7` over `UnboundCipherKey::new(&AES_128 | &AES_192 | &AES_256, key)` and the 16-byte IV from the parameters.
  The derived key and the plaintext are `Zeroizing` buffers, as in §3.6.
- Rejected at the `ClientKey` step with its legacy line (DV-TLS-6): traditional PEM encryption (`Proc-Type: 4,ENCRYPTED`, which needs MD5-based `EVP_BytesToKey`), DES-EDE3-CBC and DES-CBC, PBKDF2 with HMAC-SHA1 or HMAC-SHA224, scrypt, and everything DV-TLS-5 rejects.
- `legacy_pem.rs` and the RustCrypto cipher and KDF code are not compiled into the FIPS build's key path; the FIPS closure golden records the resulting crate set.

#### 3.15.4 Validation scope

- The FIPS build is supported on Linux x86_64 and aarch64. configure and CMake stop with an error when `aws-lc-rs-fips` is selected for another target.
- The build uses the aws-lc FIPS module as `aws-lc-fips-sys` provides it. The module's validation status belongs to the module; the library asserts FIPS mode (§3.15.2) and runs no non-FIPS cryptography in the TLS path.

## 4. SASL

### 4.1 zk-proto contract

```rust
// zk-proto/src/sasl.rs   (#![forbid(unsafe_code)])
pub struct SaslCall { pub gen: u32, pub kind: SaslCallKind }   // gen: never 0; new for every Start
pub enum SaslCallKind { Start, Step(Option<SecretBytes>), Dispose } // Step(None): server sent a -1 token
pub struct SaslStep { pub token: Option<SecretBytes>, pub done: bool }   // done=false: SASL_CONTINUE
pub struct SaslError { pub rc: i32, pub errstring: Vec<u8>, pub errdetail: Vec<u8> }
                                                              // rc ZSYSTEMERROR for mechanism errors
pub trait SaslSession: Send {                                 // native mechanisms only
    fn start(&mut self) -> Result<(String, SaslStep), SaslError>;
    fn step(&mut self, challenge: Option<&[u8]>) -> Result<SaslStep, SaslError>;
}
impl<T: Tokens> Connection<T> {
    /// A result whose gen is not the current one is ignored (returns ZOK).
    pub fn handle_sasl_started(&mut self, gen: u32, r: Result<(String, SaslStep), SaslError>, now: Millis) -> i32;
    pub fn handle_sasl_stepped(&mut self, gen: u32, r: Result<SaslStep, SaslError>, now: Millis) -> i32;
    /// Both drivers (drop-in START addresses; native SaslContext): getsockname or getpeername
    /// failed before START. ZSYSTEMERROR, no START.
    pub fn handle_sasl_addr_failed(&mut self, gen: u32, which: SaslAddr, now: Millis) -> i32;
}
pub enum SaslAddr { Local /* getsockname */, Peer /* getpeername */ }
```

- `Action::Sasl(SaslCall)` is yielded by `poll_action` (and during read processing). While a Start or Step is outstanding, frame processing pauses (`read_hint() == 0`); at most one Start or Step is in flight per handle.
- `Dispose` is yielded at exactly the two points where legacy disposes the Cyrus connection, on the IO owner, and is never yielded by `begin_close` on a foreign thread. It carries the gen of the session to drop and expects no result. Native drivers drop the `Box<dyn SaslSession>`.
  - **Reconnect.** When an earlier generation's session is live, the core yields its `Dispose` at the next prime response, immediately before the new `Start` in the same outbox. This is legacy `zoo_sasl_connect`, which disposes the old connection first (zk_sasl.c:215-217). The `Dispose` precedes the driver's address reads, so the old connection is disposed also when an address read then fails, as in legacy.
  - **Final close.** `destroy` yields `CloseTransport` first and `Dispose` after it. This is the legacy `destroy` order (zookeeper.c:719-749: `close_zsock` at 719-723, `zoo_sasl_client_destroy` at 744-749).
  - **Teardown.** A teardown (T1) or a forced close yields no `Dispose`: the Cyrus connection of the dead session stays allocated until the next `Start` or the final close, as in legacy (SAS-13 RE). The core's generation still advances at teardown, so a late result for the old session (for example from a requeued item) is ignored as stale; the `Dispose` that follows later carries the old session's own gen, which the glue still holds.
  - **Outstanding result.** If a `Start` or `Step` result is still outstanding when a `Dispose` is due, the `Dispose` and the `Start` behind it wait for that result, which the core then ignores as stale (02 §7.4). In the drop-in, C can leave a `START` or `STEP` item unanswered: an application Cyrus callback throws during an st item, or the glue's stale check fires (§4.6). zk-ffi records the `gen` of that item. After the receive timeout tears the connection down and advances the generation, zk-ffi passes `handle_sasl_started` or `handle_sasl_stepped(old_gen, Err(SaslError{rc: ZSYSTEMERROR, ..}))` in that same core call (05 §5, §19.2). The core ignores it as stale and the wait ends, so the next prime response yields `Dispose` and then `Start`, as legacy `zoo_sasl_connect` disposes the stalled connection and starts again (zk_sasl.c:215-217; zookeeper.c:3188-3189).
- The core keeps a per-connection `SaslNegotiation { is_gssapi, gssapi_last_packet, awaiting_final }`, **created fresh at every prime response** (SEC-6, SAS-04 FU).
- The native adapter wraps 04's public `SaslMechanism` (04 §16.2). The drop-in does not implement `SaslSession` at all: Cyrus runs in C from SASL outbox items (§4.6). In the drop-in a `FAIL` status from C becomes `SaslError{rc: ZSYSTEMERROR}`; the Cyrus text was already logged in C.

### 4.2 Framing and send gating

- **Request.** `RequestHeader{xid: next per-handle xid, type: 102}` plus `GetSASLRequest{token}`. A zero-length or absent token encodes as length 0, never -1 (zookeeper.c:3026-3028). No completion entry is created. The frame is marked sensitive. The core emits DEBUG "SASL: Queued request len=%d rc=%d" (`queue_sasl_request`).
- **Position.** Priority insert before the first frame that has not started sending (C's front insert without the splice hazard, AUT-02 FU).
- **Gate.** From the prime response until COMPLETE or FAILED, only `FrameKind::Sasl` frames may be sent (zookeeper.c:5159-5172). st interest bits stay legacy-exact, including C's WRITE-while-gated (SAS-07 RE). The mt driver uses Precise interest.
- **Inbound.** While negotiating, every complete frame goes to `SaslNegotiation` (3141-3158) without an xid check.
  - Decoding is bounded by the true frame length. A short or malformed reply returns the raw `DecodeError::c_rc()`: `-E2BIG`, `-EINVAL` or `-ENOMEM`, or `hdr.err` (CC-24, 00 §4; SAS-08 RE). Only C's over-read beyond the frame is removed (SEC-15, 07 P4).
  - After each reply the core emits DEBUG "SASL: Processed response len=%d rc=%d" (`process_sasl_response`), after the step's own lines, as in C.
- **Timer.** SASL has no separate timer. The 2/3·T receive rule applies, refreshed per complete frame.

### 4.3 Negotiation rules (in zk-proto; identical in both profiles unless noted)

| Step | Rule | C ref |
|---|---|---|
| Prime response OK, SASL configured | Drop-in: Rust reads the local and peer addresses with rustix `getsockname`/`getpeername` on the driver's socket. On a failure the driver calls `handle_sasl_addr_failed(gen, which: SaslAddr{Local, Peer}, now)` (02 §4), and the core emits the ERROR "getsockname" or "getpeername" site, then ERROR "SASL authentication failed. rc=%d" with ZSYSTEMERROR, and fails the negotiation (§4.5) **without** yielding `Start`. Otherwise yield `Start`; state stays 2 | zookeeper.c:3181-3193; zk_sasl.c:219-237 |
| `mech` returned | `is_gssapi = mech == "GSSAPI"` | zk_sasl.c:324-334 |
| DIGEST-MD5 with a non-empty initial response | the core emits DEBUG "SASL start %s: refusing reauthenticate", drops the token and sends an empty one | zk_sasl.c:298-322 |
| After start | send iff `!done \|\| token non-empty`; `done` with no token sends nothing and stalls to `ZOPERATIONTIMEOUT` (RE, SAS-06) | zk_sasl.c:336-341 |
| Reply `hdr.err < 0`, or reply decode error | SASL failure with rc = err (or the decode rc). **No** "SASL authentication failed" line: C calls `zoo_sasl_mark_failed` silently here | 3047-3060, 3147-3150 |
| Server token received | the core emits DEBUG "SASL intermediate server_data_len:%d" (-1 for a -1 token) before the GSSAPI check and before yielding a Step | zk_sasl.c:358-359 |
| GSSAPI with `gssapi_last_packet` | complete without a Step | zk_sasl.c:361-366 |
| Server token -1 | `Step(None)`; C calls Cyrus with `(NULL, 0)` (SEC-8; C passed `(NULL, UINT_MAX)`, UB) | zk_sasl.c:368 |
| After step | send iff `!done \|\| token non-empty`; `done`: GSSAPI sets `gssapi_last_packet`, otherwise COMPLETE when no token was sent | zk_sasl.c:383-397 |
| Step done **with** a token, non-GSSAPI | send it, set `awaiting_final`, COMPLETE on the server's reply, which the SASL path consumes (SEC-7, SAS-05 FU) | zk_sasl.c:383-397; 3600-3625 |
| Mechanism error (`FAIL` from the glue, or a native `SaslError`) | SASL failure with rc `ZSYSTEMERROR`. The drop-in glue has already logged the Cyrus line (§4.7.8); the core then emits ERROR "SASL authentication failed. rc=%d", which is `_zsasl_fail`'s line | zk_sasl.c:147-154, 260-263, 286-291, 375-381 |
| Reconnect | at the prime response, `Dispose` of the old session immediately before the new `Start` (§4.1); new `SaslNegotiation` (SEC-6) | zk_sasl.c:215-219 |

### 4.4 Finalize

On COMPLETE, `finalize` runs as `finalize_session_establishment` (2986-3008):

1. State becomes 3, or 5 if read-only. `reconfig` is cleared.
2. Priority segment: `auth_n … auth_1, SetWatches`, ahead of held-back requests. The auth resend is preceded by an AUTH_LOCKPOINT (§5.5).
3. The CONNECTED/READONLY session event is queued.
4. The gate is lifted.

`zoo_add_auth` calls made during SASL are recorded only, and are sent by step 2.

### 4.5 SASL failure (`Compat::sasl_failure = Teardown`)

What C does on a SASL failure (zk_sasl.c:135-154; zookeeper.c:3147-3150, 3190-3193):

- state -113;
- **no event**;
- no cleanup, and the socket stays open;
- the mt IO thread exits;
- pending work waits until `zookeeper_close` (`ZCLOSING`), so an mt sync caller blocks until another thread closes;
- permanent, except that a `zoo_set_servers` move with the socket still open closes it and sets state 999 (zookeeper.c:1222-1226), after which an st application can reconnect; the mt IO thread has already exited.

The drop-in follows C's sibling digest-auth failure path (zookeeper.c:3590-3597, 2101-2132; **SEC-9 = ST-18 = DV-FIX-3, 00 §5.1; 02 §7.4**), and keeps the rc and the log lines of C's SASL path:

| Aspect | Drop-in behavior | Same as |
|---|---|---|
| State | -113, terminal, set in the call that sees the failure | both C paths |
| Socket | Closed by the teardown, on the IO owner, with no TLS close_notify (§3.2) | digest (`cleanup` → `close_zsock`, 2103) |
| Event | AUTH_FAILED session event with its DEBUG line, for the default watcher and the path watchers | digest (2104-2107) |
| Pending work | Sync, async and unsent requests get `ZAUTHFAILED`, sync outputs untouched, and auth completions get `ZAUTHFAILED` after an AUTH_LOCKPOINT | digest (`cleanup_bufs`, 2062-2076) |
| Callback thread | mt: event and async results on the dispatch thread; sync signal and auth completions on the IO thread at their barriers. st: all inline in the driving call | digest (DSP-20) |
| IO thread | Exits when the step has finished; the dispatch thread stays until close, and `zookeeper_close` joins both | both C paths (mt_adaptor.c:465-468) |
| Current server | `zoo_get_current_server` reports the address at the cursor (the server to be tried next) after the failure (T5, 02 §7.4) | digest (`handle_error`, 2124-2132); C's SASL path leaves it on the failed connection's server |
| st rc | the driving call (`zookeeper_process`, or `zookeeper_interest` for a requeued item) returns the SASL rc | C's SASL path (3147-3151, 3190-3193) |
| Log lines | "SASL authentication failed. rc=%d" only where `_zsasl_fail` runs (§4.3), then the teardown's DEBUG lines; no socket-error line | C's SASL path, then digest (which calls `handle_error`, not `handle_socket_error_msg`, 3594-3597) |
| Later request, interest and process calls | ZINVALIDSTATE | both C paths |
| Later `zoo_set_servers` | Commits its list and leaves the state at -113, because no transport remains | digest (the socket is already closed); on C's SASL path a move with the socket still open closes it and sets state 999 |
| Cyrus connection | No DISPOSE at the failure. The final close disposes it after the socket close (§4.1). The SASL generation advances at T1, so a late result is stale | both C paths (zookeeper.c:719-749) |

- **Justification.** In C's SASL path watchers never learn of the failure, async work waits until close, and a sync caller blocks until *another* thread closes the handle; a program that waits for its outstanding call before closing deadlocks.
- **`zoo_sasl_mark_failed`** stays state-only, as legacy (ABI-12; 05 §19). It runs no teardown.
- **Native** (`Teardown`): the same teardown, with terminal `AuthFailed`; every pending request and watcher fails.

### 4.6 Drop-in: SASL outbox items and the drain

zk-ffi turns `Action::Sasl` into a `ZKRS_ITEM_SASL` item. START and STEP are barriers (Rust returns `ZKRS_MORE` right after them); DISPOSE is an ordinary item. The types are 05's `shim/zkrs.h` names (05 §19.2):

```c
/* shim/zkrs.h (cbindgen; 05 owns the file) */
typedef struct zkrs_bytes {           /* a bounded byte view */
    const uint8_t *p;                 /* NULL when is_null; non-NULL when len > 0; any value when len == 0 (05 §19.2) */
    uint32_t len;                     /* <= 0x7fffffff */
    uint8_t is_null;                  /* 1: jute null (-1 token); then p == NULL, len == 0 */
} zkrs_bytes;

/* 05 §19.2 owns the numeric values of every constant named here. */
/* zkrs_sasl_kind: ZKRS_SASL_START, ZKRS_SASL_STEP, ZKRS_SASL_DISPOSE */
typedef struct zkrs_sasl_call {
    uint32_t kind;                    /* zkrs_sasl_kind */
    uint32_t gen;                     /* never 0; matches the shim session after START */
    zkrs_bytes local, peer;   /* START only: platform sockaddr image (sockaddr_in or sockaddr_in6), len = its size; else is_null */
    zkrs_bytes token;                 /* STEP only: the server token, exact frame bounds */
} zkrs_sasl_call;

/* zkrs_sasl_status: ZKRS_SASL_CONTINUE, ZKRS_SASL_DONE, ZKRS_SASL_FAIL,
 * ZKRS_SASL_STALE (-2; glue to drain only: stale gen, no Cyrus call; never sent to Rust) */
typedef struct zkrs_sasl_out {
    int32_t status;                   /* zkrs_sasl_status */
    zkrs_bytes mech;                  /* START: chosen mechanism; else is_null */
    zkrs_bytes token;                 /* Cyrus clientout; is_null when Cyrus gave none */
} zkrs_sasl_out;

/* Only inside the drain of a SASL START/STEP item. Copies everything before it returns.
 * A stale gen is ignored. The drain never passes a STALE status; any status other than
 * CONTINUE or DONE is treated as FAIL (05 §19.2). The resulting legacy rc comes back
 * through the *rc of zkrs_resume or zkrs_interest_resume. */
void zkrs_sasl_result(const zkrs_handle *h, uint32_t gen, const zkrs_sasl_out *v);
```

- **Address views (CC-31, 00 §4).** Rust gets the local and peer addresses with rustix `getsockname`/`getpeername` on the driver's own socket; no fd is lent to C. zk-ffi writes each address into `local` and `peer` as the platform `struct sockaddr_in` or `struct sockaddr_in6` image, with `len` equal to its size. If either call fails, the driver calls `handle_sasl_addr_failed(gen, which, now)`: the core logs the "getsockname" or "getpeername" core site and fails the negotiation with `ZSYSTEMERROR` without emitting START (§4.3). A START item therefore always carries two address images.
- **Token bytes** stay valid until the matching `zkrs_sasl_result` returns or the outbox advances.
- **Result rc.** `zkrs_sasl_result` returns nothing. The SASL rc reaches C as the driving call's rc through `zkrs_resume` or `zkrs_interest_resume`, so the st driving call (`zookeeper_process`, or `zookeeper_interest` for a requeued item) returns it when it is not ZOK, which reproduces `return rc` from `process_sasl_response`/`zoo_sasl_client_start` (zookeeper.c:3141-3150, 3188-3193). The SASL packet goes out on a later WRITE event, as in C.

The drain (`shim/zks_drain.c`) dispatches the item to the glue through the Cyrus-free header `shim/zks_sasl.h`. Every glue call carries the generation, and the glue rejects a stale one itself (§4.7.2). The drain passes the address views to the glue unchanged and records the generation after START:

```c
/* shim/zks_drain.c: runs on the IO owner, no Rust frame below.
   The glue functions exist only in zks_sasl_cyrus.c, which SASL builds compile.
   This function and its drain case are therefore under ZKS_WITH_SASL. */
#ifdef ZKS_WITH_SASL
static void zks_sasl_item(zhandle_t *zh, const zkrs_sasl_call *c)
{
    struct zks_sasl_sess *s = zh->sasl;      /* Rust emits SASL items only when init_args.sasl = 1 */
    zkrs_sasl_out out = ZKS_SASL_OUT_FAIL;
    if (s == NULL) {                         /* cannot occur; fail cleanly, no Cyrus call */
        if (c->kind != ZKRS_SASL_DISPOSE) ZKS_CALL(zkrs_sasl_result(zh->core, c->gen, &out));
        return;
    }
    switch (c->kind) {
    case ZKRS_SASL_START:                    /* start disposes any old connection first */
        zks_sasl_start(s, c->gen, zks_sasl_sess_params(s), c->local, c->peer, &out);
        if (out.status != ZKRS_SASL_STALE)
            zks_sasl_set_gen(s, c->gen);     /* no-op when START left no connection */
        break;
    case ZKRS_SASL_STEP:
        zks_sasl_step(s, c->gen, c->token, &out);
        break;
    case ZKRS_SASL_DISPOSE:
        zks_sasl_dispose(s, c->gen);         /* a stale gen is a no-op in the glue */
        return;                              /* DISPOSE has no result */
    default:
        return;
    }
    if (out.status == ZKRS_SASL_STALE) return;            /* the glue made no Cyrus call; no result */
    ZKS_CALL(zkrs_sasl_result(zh->core, c->gen, &out));   /* Rust copies before it returns */
}
#endif
```

An item whose generation is stale gets no reply: the glue makes no Cyrus call and the drain does not call `zkrs_sasl_result`. Rust emits each generation's START once and its STEPs only after that START succeeded, so a stale START or STEP can come only from a defect. Rust then gets no result, its outstanding Start or Step keeps frame processing paused, and the receive rule tears the connection down (fail closed). zk-ffi then hands the core a stale `Err` result for the unanswered item (05 §5, §19.2), so the next connection's DISPOSE and START are not delayed (§4.1). Rust also ignores a result with a stale generation (CC-17, 00 §4). Cyrus therefore runs with no Rust frame and no internal lock, on the IO owner (the mt IO thread, the st caller, or, for the DISPOSE of the final close, the thread that runs the final close after the IO thread is joined or has exited), which is the legacy thread for Cyrus callbacks and `getpass`.

### 4.7 Hardened Cyrus glue

#### 4.7.1 Isolation

- Exactly two translation units include `<sasl/sasl.h>`:
  - `shim/zks_sasl_cyrus.c`: the engine glue (`zks_sasl_sess`, start/step/dispose, the per-session callback array and PASS trampoline), `zookeeper_init_sasl`, `zks_sasl_sess_new`, the session accessors `zks_sasl_sess_params`, `zks_sasl_gen` and `zks_sasl_set_gen`, and the leaked `zoo_sasl_*` internals;
  - `shim/zks_sasl_cb.c`: `zoo_sasl_make_basic_callbacks`, `zoo_sasl_make_password_callbacks`, their callbacks, the calloc block, and the static password mutex.
- `zookeeper.h` includes `<sasl/sasl.h>` under `HAVE_CYRUS_SASL_H` (zookeeper.h:42-44). The library build defines that macro as legacy does when SASL is built (§3.11). These two files are the only shim TUs in which it is in effect when `zookeeper.h` is included: every other shim TU includes `zookeeper.h` through `zks_internal.h`, which undefines it first (§3.11). The other shim TUs use `ZKS_WITH_SASL` and see the session only as `struct zks_sasl_sess *` and the params only as an incomplete `struct zoo_sasl_params`.
- `shim/zks_sasl.h` (included by the drain, `zks_handle.c` and the two files) has no Cyrus type. `shim/zks_sasl_cb.h` (Cyrus types; the interface from the callback file to the glue: `zks_is_library_pass_cb` and `zks_getsecret_unlocked`) may be included only by the two files.
- CI checks the compiler depfiles: `<sasl/sasl.h>` and `zks_sasl_cb.h` appear only in the dependencies of the two TUs.
- Both files are excluded from the build when SASL is disabled; the golden subsets keep the symbol differences (INIT-16).

#### 4.7.2 Narrow, bounded interface

```c
/* shim/zks_sasl.h -- the drain's and the init path's view of the glue. No Cyrus types. */
#include "zkrs.h"

typedef zkrs_bytes zks_bytes;          /* {p, len, is_null}, built by Rust or by the drain */
typedef zkrs_sasl_out zks_sasl_out;    /* {status, mech, token} */
#define ZKS_SASL_LEN_CAP 0x7fffffffu   /* Cyrus uses unsigned; we keep every length in int range */
#define ZKS_SASL_OUT_FAIL { ZKRS_SASL_FAIL, {NULL, 0, 1}, {NULL, 0, 1} }

struct zks_sasl_params {               /* owned by the session, except callbacks */
    char *service, *host, *mechlist;   /* NUL-terminated copies from zoo_sasl_params_t; each may be NULL */
    const void *callbacks;             /* borrowed sasl_callback_t[]; may be NULL */
};
struct zks_sasl_sess;                  /* defined in zks_sasl_cyrus.c */
typedef struct zks_sasl_sess zks_sasl_sess;
typedef struct zks_sasl_params zks_sasl_params;
struct zoo_sasl_params;                /* incomplete here */

struct zks_sasl_sess *zks_sasl_sess_new(zhandle_t *zh, const struct zoo_sasl_params *p);  /* NULL: ENOMEM */
void zks_sasl_sess_free(struct zks_sasl_sess *s);     /* dispose + free copies + free s */
void zks_sasl_sess_clear(struct zks_sasl_sess *s);    /* dispose + free copies; keeps s */

const zks_sasl_params *zks_sasl_sess_params(const zks_sasl_sess *s);
uint32_t  zks_sasl_gen(const struct zks_sasl_sess *s);        /* 0: no connection */
void      zks_sasl_set_gen(struct zks_sasl_sess *s, uint32_t gen);   /* no-op without a connection */

/* The three glue functions. Each one rejects a stale gen itself:
 * status ZKRS_SASL_STALE (DISPOSE: no effect) and no Cyrus call. */
void zks_sasl_start(zks_sasl_sess *s, uint32_t gen, const zks_sasl_params *p,
                    zks_bytes local, zks_bytes peer, zkrs_sasl_out *out);
void zks_sasl_step(zks_sasl_sess *s, uint32_t gen, zks_bytes challenge, zkrs_sasl_out *out);
void zks_sasl_dispose(zks_sasl_sess *s, uint32_t gen);
```

- **Generations.** Every glue call carries `gen`. The glue rejects a stale generation before any Cyrus call: START rejects 0 and the live session's gen; STEP and DISPOSE reject any gen other than the live one. A rejected START or STEP sets status `ZKRS_SASL_STALE`, and the drain then does not call `zkrs_sasl_result` for that item; a rejected DISPOSE does nothing. The drain records the generation with `zks_sasl_set_gen` after a START that was not rejected. The check sits in the glue so that the libFuzzer harness covers it (§4.7.11).
- **Byte arguments.** Server tokens are `zks_bytes` built by Rust from exact-length frame decoding; a -1 token is `is_null = 1, p = NULL, len = 0` (SAS-10). `local` and `peer` hold the platform `struct sockaddr_in` or `struct sockaddr_in6` image that zk-ffi writes (§4.6). The drain passes them unchanged. The fuzz harness passes fuzzed views directly. The glue checks every view on entry: `len <= ZKS_SASL_LEN_CAP`, `is_null` implies `len == 0` and `p == NULL`, and `len > 0` implies `p != NULL`; a bad view fails the call with `ZKRS_SASL_FAIL` before any Cyrus call that uses it. An address view must also be non-null, and its family and length must agree: family `AF_INET` with `len == sizeof(struct sockaddr_in)`, or family `AF_INET6` with `len == sizeof(struct sockaddr_in6)`. The family is read with `memcpy` at `offsetof(struct sockaddr, sa_family)`. Any other address view fails the START with `ZKRS_SASL_FAIL` before `sasl_client_new` (any old connection has already been disposed, as `zoo_sasl_connect` disposes first).
- **Params.** `p` comes from the session's own copies (`zks_sasl_sess_params(s)`), or from the fuzz harness. Its strings are NUL-terminated, built with `strnlen` capped at `ZKS_SASL_LEN_CAP`. `p->mechlist` is NULL when the application gave none, as in legacy.
- **Outputs.** `clientout`/`clientoutlen` and the chosen mechanism are returned as views. The drain passes them to `zkrs_sasl_result` at once, and Rust copies them before it returns. C never keeps a pointer into a Cyrus output buffer past the next Cyrus call: the glue clears `out` at the start of every call.
- **Lengths.** `clientoutlen` is `unsigned` in Cyrus and is checked against `ZKS_SASL_LEN_CAP` before use. The mechanism name is checked with `strnlen(mech, SASL_MECHNAMEMAX + 1) <= SASL_MECHNAMEMAX`.

#### 4.7.3 Ownership and lifetime

```c
/* shim/zks_sasl_cyrus.c */
struct zks_secret {                     /* one secret given to Cyrus for one connection */
    struct zks_secret *next;
    size_t size;                        /* bytes in buf */
    _Alignas(max_align_t) unsigned char buf[];   /* a sasl_secret_t, then its data */
};
struct zks_pass_ctx {                   /* context of zks_sasl_pass_trampoline */
    sasl_callback_ft orig_proc;         /* the application's SASL_CB_PASS proc */
    void *orig_ctx;                     /* and its context */
    int is_library;                     /* orig_proc is the library's own callback */
    struct zks_secret *secrets;         /* all copies for this connection */
    unsigned nsecrets;
};
struct zks_sasl_sess {
    struct zks_sasl_params params;      /* owned copies; borrowed callbacks */
    const zhandle_t *log_zh;            /* log target only; NULL in the fuzz harness */
    sasl_conn_t *conn;                  /* NULL when there is no connection */
    uint32_t gen;                       /* gen of conn; 0 when conn == NULL */
    sasl_callback_t *cb;                /* per-session copy of the callback array, or NULL */
    struct zks_pass_ctx pass;
};
```

- **Creation (05 §15.1).** `zookeeper_init_sasl` (in `zks_sasl_cyrus.c`) passes the params to `zks_init_common(…, NULL /* tls */, sasl_params)`. `zks_init_common` calls `zks_sasl_sess_new(zh, params)` at the SASL point of init: `zkrs_handle_new` stops there after the initial resolution and the reseed and before it creates the mt waker. This is the legacy order (resolution at zookeeper.c:1450, SASL client creation at 1472-1479, the pipe in `adaptor_init` at 1482), so a resolution errno wins over a SASL allocation errno, and a SASL allocation errno wins over a waker errno. A failure there makes init return NULL with `errno = ENOMEM`. NULL `sasl_params` behaves like `zookeeper_init2`.
- `zks_sasl_sess_new` uses `calloc` for the session and copies `service`, `host` and `mechlist` (`strnlen` with `ZKS_SASL_LEN_CAP`, overflow-checked `malloc`, `memcpy`, NUL; a string at the cap fails with ENOMEM). The application's `sasl_callback_t*` stays borrowed and must outlive the handle, as in legacy (zk_sasl.c:90-114). Contents are not validated at init; failures surface after the prime response as AUTH_FAILED (INIT-15 RE).
- `conn`, `cb` and the secret copies exist only between a START and the next dispose. The internal `zks_conn_dispose` runs on every reconnect (the DISPOSE item right before the next START, and again inside that START, as `zoo_sasl_connect` disposes first), at final close (the DISPOSE item after the transport is closed, §4.1), and finally in `zks_sasl_sess_free` at release. A teardown alone does not dispose, as in legacy. After it, `conn == NULL`, `gen == 0`, `cb == NULL` and no secret copy exists.
- `gen` is recorded by the drain after START (`zks_sasl_set_gen`) whenever START left a connection, including after a failed `sasl_client_start`, so the later DISPOSE for that generation finds it. A STEP or DISPOSE whose gen differs is rejected by the glue before any Cyrus call (§4.7.2).
- At release, `zks_release` calls `zks_sasl_sess_free(zh->sasl)` before freeing `zh` (05 §15.4). A forked child does not dispose (DRV-14).

#### 4.7.4 Serialization

- Rust guarantees, per handle, at most one SASL START or STEP in flight. SASL items are drained only on the IO owner: the mt IO thread, the st caller, or, for the DISPOSE of the final close, the thread that runs the final close after the IO thread is joined or has exited. DISPOSE comes through the same outbox order on the IO owner: on a reconnect right before the START, and at the final close after the transport is closed. The final close runs only after the IO thread has finished (or on it).
- Therefore no two glue calls on one session can overlap, and `zks_sasl_dispose` never races a step. The session needs no lock in C.
- Different handles may run their glue calls at the same time on different threads. They share nothing except the borrowed application callback array, which Cyrus only reads, and the library's callback block. ZooKeeper sessions never write the block. The only write to it, the in-block copy on the direct Cyrus path, is serialized by the password mutex (§4.7.7).

#### 4.7.5 Start, step and dispose

```c
/* shim/zks_sasl_cyrus.c */
static void zks_out_clear(zks_sasl_out *o) { *o = (zks_sasl_out)ZKS_SASL_OUT_FAIL; }

static int zks_bytes_ok(zks_bytes b)
{
    if (b.len > ZKS_SASL_LEN_CAP) return 0;
    if (b.is_null) return b.len == 0 && b.p == NULL;
    return b.len == 0 || b.p != NULL;
}

/* The internal dispose: no generation check. Used by START, by a matching DISPOSE
 * and by zks_sasl_sess_clear/zks_sasl_sess_free. */
static void zks_conn_dispose(struct zks_sasl_sess *s);

/* Format "ip;port" as _zsasl_getipport does. a holds a sockaddr_in or sockaddr_in6 image.
 * Returns ZOK or ZSYSTEMERROR. */
static int zks_ipport(const struct zks_sasl_sess *s, zks_bytes a,
                      char out[NI_MAXHOST + NI_MAXSERV], char host[NI_MAXHOST])
{
    struct sockaddr_storage ss; char hb[NI_MAXHOST], pb[NI_MAXSERV];
    int fl = NI_NUMERICHOST | NI_NUMERICSERV, e, n;
    sa_family_t fam;
    /* zk-ffi sends only valid addresses. A bad view fails here, with no log. */
    if (!zks_bytes_ok(a) || a.is_null
        || a.len < offsetof(struct sockaddr, sa_family) + sizeof fam) return ZSYSTEMERROR;
    memcpy(&fam, a.p + offsetof(struct sockaddr, sa_family), sizeof fam);
    if (!((fam == AF_INET && a.len == sizeof(struct sockaddr_in))
          || (fam == AF_INET6 && a.len == sizeof(struct sockaddr_in6)))) return ZSYSTEMERROR;
    memset(&ss, 0, sizeof ss);
    memcpy(&ss, a.p, a.len);
#ifdef NI_WITHSCOPEID
    if (ss.ss_family == AF_INET6) fl |= NI_WITHSCOPEID;
#endif
    e = getnameinfo((struct sockaddr *)&ss, (socklen_t)a.len, hb, sizeof hb, pb, sizeof pb, fl);
    if (e != 0) { zks_log_site(s->log_zh, ZKS_SITE_SASL_GETNAMEINFO, gai_strerror(e)); return ZSYSTEMERROR; }
    n = snprintf(out, NI_MAXHOST + NI_MAXSERV, "%s;%s", hb, pb);
    if (n < 0 || (size_t)n >= NI_MAXHOST + NI_MAXSERV) return ZSYSTEMERROR;   /* truncation check */
    if (host != NULL) memcpy(host, hb, sizeof hb);
    return ZOK;
}

void zks_sasl_start(struct zks_sasl_sess *s, uint32_t gen, const struct zks_sasl_params *p,
                    zks_bytes local, zks_bytes peer, zkrs_sasl_out *out)
{
    char lip[NI_MAXHOST + NI_MAXSERV], rip[NI_MAXHOST + NI_MAXSERV], host[NI_MAXHOST];
    const char *chosen = NULL, *cout = NULL; unsigned coutlen = 0; int sr;

    zks_out_clear(out);                                  /* every exit below is FAIL unless set */
    if (gen == 0 || gen == s->gen) {                     /* stale or repeated START */
        out->status = ZKRS_SASL_STALE;                   /* no Cyrus call, no state change */
        return;
    }
    zks_conn_dispose(s);                                 /* zoo_sasl_connect disposes first */
    if (zks_ipport(s, local, lip, NULL) != ZOK) return;
    if (zks_ipport(s, peer, rip, host) != ZOK) return;
    zks_log_site(s->log_zh, ZKS_SITE_SASL_HOST, lip, rip);          /* DEBUG "Zookeeper Host: %s %s" */

    if (zks_cb_build(s, p) != 0) {                       /* §4.7.6: array of 4096+ entries (SAS-14) */
        zks_log_site(s->log_zh, ZKS_SITE_SASL_NEW, sasl_errstring(SASL_NOMEM, NULL, NULL));
        return;                                          /* as a sasl_client_new SASL_NOMEM failure */
    }
    sr = sasl_client_new(p->service, p->host ? p->host : host, lip, rip, s->cb, 0, &s->conn);
    if (sr != SASL_OK) {
        zks_log_site(s->log_zh, ZKS_SITE_SASL_NEW, sasl_errstring(sr, NULL, NULL));
        /* Cyrus may return an error with *pconn still set, and that connection points at
         * s->cb. zks_conn_dispose disposes s->conn if it is non-NULL, then frees s->cb. */
        zks_conn_dispose(s);
        return;
    }

    /* p->mechlist is the session's NUL-terminated copy (or NULL), as legacy passes it. */
    sr = sasl_client_start(s->conn, p->mechlist, NULL, &cout, &coutlen, &chosen);
    if (sr != SASL_OK && sr != SASL_CONTINUE) {
        zks_log_site(s->log_zh, ZKS_SITE_SASL_START_ERR, sasl_errstring(sr, NULL, NULL),
                     sasl_errdetail(s->conn));
        return;
    }
    if (chosen == NULL || strnlen(chosen, SASL_MECHNAMEMAX + 1) > SASL_MECHNAMEMAX
        || coutlen > ZKS_SASL_LEN_CAP || (coutlen > 0 && cout == NULL)) {
        return;
    }
    zks_log_site(s->log_zh, ZKS_SITE_SASL_START, sr, chosen, (int)coutlen);
    out->status = (sr == SASL_CONTINUE) ? ZKRS_SASL_CONTINUE : ZKRS_SASL_DONE;
    out->mech  = (zks_bytes){ (const uint8_t *)chosen, (uint32_t)strlen(chosen), 0 };
    out->token = (zks_bytes){ coutlen ? (const uint8_t *)cout : NULL, coutlen, cout == NULL };
}

void zks_sasl_step(struct zks_sasl_sess *s, uint32_t gen, zks_bytes in, zkrs_sasl_out *out)
{
    const char *cout = NULL; unsigned coutlen = 0; int sr;
    zks_out_clear(out);
    if (gen == 0 || gen != s->gen) {                     /* stale: no Cyrus call */
        out->status = ZKRS_SASL_STALE;
        return;
    }
    if (s->conn == NULL || !zks_bytes_ok(in)) return;
    /* A -1 token goes to Cyrus as (NULL, 0) (SAS-10). */
    sr = sasl_client_step(s->conn, in.is_null ? NULL : (const char *)in.p,
                          in.is_null ? 0u : (unsigned)in.len, NULL, &cout, &coutlen);
    zks_log_site(s->log_zh, ZKS_SITE_SASL_STEP_OUT, sr, (int)coutlen);
    if (sr != SASL_OK && sr != SASL_CONTINUE) {
        zks_log_site(s->log_zh, ZKS_SITE_SASL_STEP_ERR, sasl_errstring(sr, NULL, NULL),
                     sasl_errdetail(s->conn));
        return;
    }
    if (coutlen > ZKS_SASL_LEN_CAP || (coutlen > 0 && cout == NULL)) return;
    out->status = (sr == SASL_CONTINUE) ? ZKRS_SASL_CONTINUE : ZKRS_SASL_DONE;
    out->token = (zks_bytes){ coutlen ? (const uint8_t *)cout : NULL, coutlen, cout == NULL };
}

void zks_sasl_dispose(struct zks_sasl_sess *s, uint32_t gen)
{
    if (gen != 0 && gen == s->gen) zks_conn_dispose(s);   /* a stale gen has no effect */
}

static void zks_conn_dispose(struct zks_sasl_sess *s)
{
    struct zks_secret *x, *nx;
    if (s->conn != NULL) sasl_dispose(&s->conn);       /* Cyrus may use the secrets and cb until here */
    s->conn = NULL;
    s->gen = 0;
    for (x = s->pass.secrets; x != NULL; x = nx) {     /* zero, then free */
        nx = x->next;
        zks_secure_zero(x->buf, x->size);
        free(x);
    }
    s->pass.secrets = NULL; s->pass.nsecrets = 0;
    free(s->cb); s->cb = NULL;                         /* the copy holds no secret */
}
```

- `zks_ipport` validates family and length, calls `getnameinfo(NI_NUMERICHOST|NI_NUMERICSERV)` (plus `NI_WITHSCOPEID` for IPv6 where it exists) and formats "%s;%s" with `snprintf` into bounded buffers. On a `getnameinfo` error it logs "getnameinfo: %s\n" at the legacy site (zk_sasl.c:185). The output is byte-identical to legacy, including IPv6 scope suffixes.
- The glue does not log "SASL authentication failed. rc=%d": zk-proto emits it when it receives `FAIL` (§4.3, §4.7.8).
- No `sasl_setprop`, no security layer (qop=auth), `prompt_need` NULL, `secflags = 0`. The library never calls `sasl_client_init`/`sasl_client_done` (SAS-01 RE).
- The DIGEST-MD5 initial-response drop and the GSSAPI final packet are zk-proto rules (§4.3); the glue has no mechanism-specific code.

#### 4.7.6 Per-session callback array and the PASS trampoline (DAT-13: FU)

When the glue calls `sasl_client_new`, it passes a **shim-built per-session copy** of the application's array, in which only the first `SASL_CB_PASS` entry is replaced by `zks_sasl_pass_trampoline` with the per-session `struct zks_pass_ctx` as context. Cyrus returns the first entry with a matching id, so later PASS entries are unreachable in legacy and in the drop-in and are copied unchanged. Every other entry keeps its original `proc` and `context`.

```c
/* shim/zks_sasl_cyrus.c */
#define ZKS_SASL_MAX_CB      4096u   /* entries before SASL_CB_LIST_END; Cyrus has about 20 ids (SAS-14) */
#define ZKS_SASL_MAX_SECRETS 16u     /* PASS calls per connection; Cyrus makes one or two (SAS-14) */

typedef int (*zks_pass_fn)(sasl_conn_t *, void *, int, sasl_secret_t **);
static int zks_sasl_pass_trampoline(sasl_conn_t *conn, void *context, int id, sasl_secret_t **psecret);

/* Copy the application's array and wrap its first SASL_CB_PASS entry. */
static int zks_cb_build(struct zks_sasl_sess *s, const struct zks_sasl_params *p)
{
    const sasl_callback_t *app = p->callbacks;
    size_t n = 0, bytes, i; int wrapped = 0;
    if (app == NULL) { s->cb = NULL; return 0; }           /* Cyrus then uses global callbacks */
    while (app[n].id != SASL_CB_LIST_END) {                /* the walk Cyrus itself does */
        if (++n >= ZKS_SASL_MAX_CB) return -1;
    }
    if (ZKS_ADD_OVF(n, (size_t)1, &bytes)
        || ZKS_MUL_OVF(bytes, sizeof *app, &bytes)) return -1;
    if ((s->cb = malloc(bytes)) == NULL) abort();          /* 07 P7 */
    memcpy(s->cb, app, bytes);                             /* includes the LIST_END entry */
    for (i = 0; i < n && !wrapped; i++) {
        if (s->cb[i].id != SASL_CB_PASS) continue;
        s->pass.orig_proc  = s->cb[i].proc;                /* record the original */
        s->pass.orig_ctx   = s->cb[i].context;
        s->pass.is_library = zks_is_library_pass_cb(s->cb[i].proc);
        s->cb[i].proc      = (sasl_callback_ft)&zks_sasl_pass_trampoline;
        s->cb[i].context   = &s->pass;
        wrapped = 1;
    }
    return 0;
}

/* Copy len secret bytes into a new per-session buffer and give it to Cyrus. */
static int zks_pass_store(void *arg, const unsigned char *data, size_t len, sasl_secret_t **out)
{
    struct zks_pass_ctx *pc = arg;
    const size_t hdr = offsetof(sasl_secret_t, data);
    size_t need, total; struct zks_secret *x; unsigned char *dst; sasl_secret_t *sec;

    if (len > ZKS_SASL_LEN_CAP) return SASL_BUFOVER;
    if (pc->nsecrets >= ZKS_SASL_MAX_SECRETS) return SASL_NOMEM;   /* the 17th request (SAS-14) */
    if (ZKS_ADD_OVF(hdr, len, &need) || ZKS_ADD_OVF(need, (size_t)1, &need))
        return SASL_BUFOVER;
    if (need < sizeof(sasl_secret_t)) need = sizeof(sasl_secret_t);   /* a whole struct, always */
    if (ZKS_ADD_OVF(offsetof(struct zks_secret, buf), need, &total)) return SASL_BUFOVER;
    if ((x = calloc(1, total)) == NULL) return SASL_NOMEM;           /* zero-filled */
    x->size = need;
    sec = (sasl_secret_t *)(void *)x->buf;                 /* buf is max-aligned */
    sec->len = (unsigned long)len;
    dst = x->buf + hdr;                                    /* not sec->data: keep the true object size
                                                              visible to _FORTIFY_SOURCE */
    if (len) memcpy(dst, data, len);
    dst[len] = '\0';
    x->next = pc->secrets; pc->secrets = x; pc->nsecrets++;
    *out = sec;                                            /* valid until zks_sasl_dispose */
    return SASL_OK;
}

static int zks_sasl_pass_trampoline(sasl_conn_t *conn, void *context, int id, sasl_secret_t **psecret)
{
    struct zks_pass_ctx *pc = context;
    sasl_secret_t *got = NULL; int rc;
    if (pc == NULL || psecret == NULL || id != SASL_CB_PASS) return SASL_BADPARAM;
    *psecret = NULL;
    if (pc->is_library) {
        /* The library's own callback: the legacy password rules with no lock held.
         * The bytes go to this session's sink; the shared block is not written. */
        return zks_getsecret_unlocked(conn, pc->orig_ctx, zks_pass_store, pc, psecret);
    }
    /* Application callback: call it with its own context. No lock is held. */
    rc = ((zks_pass_fn)pc->orig_proc)(conn, pc->orig_ctx, id, &got);
    if (rc != SASL_OK) return rc;
    if (got == NULL) return SASL_FAIL;
    if (got->len > ZKS_SASL_LEN_CAP) return SASL_BUFOVER;
    return zks_pass_store(pc, (const unsigned char *)got + offsetof(sasl_secret_t, data),
                          (size_t)got->len, psecret);
}
```

Lifecycle of a secret copy: created by the trampoline on each Cyrus PASS request (a zero-filled buffer of at least `sizeof(sasl_secret_t)` holding `len` bytes plus a NUL), owned by the session, handed to Cyrus, which may keep the pointer for the life of that `sasl_conn_t` (the reason the legacy buffer had a stable address); after `sasl_dispose`, every copy is zeroed with `zks_secure_zero` and freed. The buffer is never smaller than `sizeof(sasl_secret_t)`, so plugin code that copies `sizeof(sasl_secret_t) + len` bytes and `_FORTIFY_SOURCE` object-size checks stay in bounds. The application's own `sasl_secret_t` is not touched or freed by the library, as in legacy.

Why this is FIX-UB-ONLY: the application-visible block keeps its layout and single-`free()` contract; the bytes Cyrus receives are the bytes the original callback produced, with the same return codes; the library holds no lock while application code runs, as in legacy, so no correct application can depend on the shared buffer or the absence of the mutex. No ZooKeeper session's Cyrus connection reads a buffer that another session can rewrite, ZooKeeper sessions never write the shared in-block buffer, and the one remaining in-block write (direct Cyrus users of the block) is serialized by one mutex. The formal data race of DAT-13 therefore does not occur for ZooKeeper sessions. An application that passes the block to its own Cyrus connections keeps the legacy header contract (zookeeper.h:695-697) and its residual race, unchanged from legacy: those connections read the shared `sasl_secret_t` with no lock while another thread may write it under the mutex. The documented sharing contract (zookeeper.h:695-697) remains good advice for rotation, since two handles may still read different file contents at different times.

One non-observable difference: the per-session array is copied at each START, so an application that rewrites entries of its array while a connection is open (a race with Cyrus's reads in legacy) has the change take effect at the next connection.

**Hardening limits (SAS-14, FU-hardening; 00 §5.2).** Two bounds have no legacy counterpart. An application callback array of 4096 or more entries before `SASL_CB_LIST_END` fails the START with `SASL_NOMEM`: the glue logs the `sasl_client_new` line ("allocating SASL connection state: %s") with the `SASL_NOMEM` text and returns `FAIL`, so the negotiation fails with `ZSYSTEMERROR` as for a `sasl_client_new` failure. More than 16 secret requests on one connection make the seventeenth PASS request return `SASL_NOMEM` to Cyrus. Neither limit is reachable with the shipped mechanisms (Cyrus has about 20 callback ids and makes one or two PASS requests per connection); they bound hostile or corrupted input.

#### 4.7.7 The library callback block and the password mutex (`shim/zks_sasl_cb.c`)

The block and the callbacks keep zk_sasl.c:440-666 byte for byte in layout and rules:

- one `calloc` block with `callbacks[5]` at offset 0 in the order GETREALM, USER, AUTHNAME, PASS, END; then `struct zsasl_secret_ctx { password_file, context, callback, secret }`; then the union of `sasl_secret_t` and `sizeof(sasl_secret_t) + 1023` bytes; then the strings (user, realm, password file). It is freed with one `free()`.
- `_zsasl_getsecret` rules: the file is re-read on each call (`fopen("rt")`, `fread` of at most 1023 bytes, `ferror` → `SASL_FAIL`); without a transform callback the first line is used; with a callback, the file is required (`SASL_BADPARAM` otherwise) and a returned length over 1023 gives `SASL_BUFOVER`; without a file, `getpassphrase("Password: ")` (or `getpass`; on `_WIN32`, `zks_getpassphrase` into a caller stack buffer, §4.7.7), NULL → `SASL_FAIL`; the result is NUL-truncated (`strlen`); a length over 1023 gives `SASL_BUFOVER`; `getpass` runs on the IO-owner thread.
- `zoo_sasl_make_password_callbacks(…, NULL)` returns NULL with `errno = EINVAL` instead of dereferencing NULL (SEC-13, NUL-06). `zoo_sasl_make_basic_callbacks` calls it with `{password_file, NULL, NULL}`.
- Size computation uses `strnlen(·, ZKS_SASL_LEN_CAP)` and `ZKS_ADD_OVF` (§4.7.9) for `offsetof(block, strings) + user_len + realm_len + file_len`; an overflow returns NULL with `errno = ENOMEM`, the legacy allocation-failure result.

```c
/* shim/zks_sasl_cb.c */
#include "zks_sasl_cb.h"  /* ZSASL_MAX_PASSWORD_LEN, zks_secret_sink (05 §19.5); ZKS_ADD_OVF, ZKS_CLEANUP (§4.7.9) */

/* The only global mutable state in the SASL files. Static: only _zsasl_getsecret takes it,
 * around the in-block copy, and no application code runs while it is held. */
#ifdef _WIN32
static SRWLOCK zks_sasl_pass_mutex = SRWLOCK_INIT;
static void zks_sasl_pass_lock(void)   { AcquireSRWLockExclusive(&zks_sasl_pass_mutex); }
static void zks_sasl_pass_unlock(void) { ReleaseSRWLockExclusive(&zks_sasl_pass_mutex); }
#else
static pthread_mutex_t zks_sasl_pass_mutex = PTHREAD_MUTEX_INITIALIZER;
static void zks_sasl_pass_lock(void)   { (void)pthread_mutex_lock(&zks_sasl_pass_mutex); }
static void zks_sasl_pass_unlock(void) { (void)pthread_mutex_unlock(&zks_sasl_pass_mutex); }
#endif

/* Zero a password buffer. Each function below calls it at its single exit label "out";
 * on GCC and Clang ZKS_CLEANUP also calls it when a C++ exception unwinds the frame. */
static void zks_zero_pw(char (*b)[ZSASL_MAX_PASSWORD_LEN + 1]) { zks_secure_zero(*b, sizeof *b); }

/* Legacy password rules. Writes at most 1023 bytes plus NUL to pw.
 * It calls application code (the transform callback, getpass) and runs with no
 * library lock held. Every exit goes through "out". */
static int zks_getsecret_compute(struct zsasl_secret_ctx *c, char pw[ZSASL_MAX_PASSWORD_LEN + 1],
                                 size_t *out_len)
{
    char buf[ZSASL_MAX_PASSWORD_LEN + 1] ZKS_CLEANUP(zks_zero_pw);
    char np[ZSASL_MAX_PASSWORD_LEN + 1] ZKS_CLEANUP(zks_zero_pw);
    char *password = NULL, *nl; size_t len = 0; int res = SASL_OK;
#ifdef _WIN32
    char gp[ZSASL_MAX_PASSWORD_LEN + 2];   /* Windows prompt buffer, this caller's own; zeroed at out */
#endif

    if (c->password_file != NULL) {
        FILE *fh = fopen(c->password_file, "rt");
        if (fh == NULL) { res = SASL_FAIL; goto out; }
        len = fread(buf, 1, ZSASL_MAX_PASSWORD_LEN, fh);
        if (ferror(fh)) { fclose(fh); res = SASL_FAIL; goto out; }
        fclose(fh);
        buf[len] = '\0';
        password = buf;
    }
    if (c->callback != NULL) {
        if (password == NULL) { res = SASL_BADPARAM; goto out; }      /* needs the file */
        res = c->callback(password, len, c->context, np, ZSASL_MAX_PASSWORD_LEN, &len);
        if (res != SASL_OK) goto out;
        if (len > ZSASL_MAX_PASSWORD_LEN) { res = SASL_BUFOVER; goto out; }
        np[len] = '\0';
        password = np;
    } else if (password != NULL) {
        if ((nl = memchr(password, '\n', len)) != NULL) *nl = '\0';     /* first line only */
    } else {
#ifdef _WIN32
        password = zks_getpassphrase("Password: ", gp, sizeof gp);
#else
        password = getpassphrase("Password: ");
#endif
        if (password == NULL) { res = SASL_FAIL; goto out; }
    }
    len = strnlen(password, ZSASL_MAX_PASSWORD_LEN + 1);                /* NUL truncates */
    if (len > ZSASL_MAX_PASSWORD_LEN) {
        zks_secure_zero(password, strnlen(password, ZKS_SASL_LEN_CAP));  /* getpass buffer, as C */
        res = SASL_BUFOVER; goto out;
    }
    memcpy(pw, password, len);
    pw[len] = '\0';
    if (password != buf && password != np) zks_secure_zero(password, len);   /* getpass buffer */
    *out_len = len;
out:
    zks_zero_pw(&buf); zks_zero_pw(&np);     /* explicit form; the cleanup attribute repeats it */
#ifdef _WIN32
    zks_secure_zero(gp, sizeof gp);
#endif
    return res;
}

/* SASL_CB_PASS proc stored in the block: legacy behavior for direct Cyrus users of the block. */
static int _zsasl_getsecret(sasl_conn_t *conn, void *context, int id, sasl_secret_t **psecret)
{
    struct zsasl_secret_ctx *c = context;
    char pw[ZSASL_MAX_PASSWORD_LEN + 1] ZKS_CLEANUP(zks_zero_pw);
    size_t len = 0; int res; sasl_secret_t *x;
    if (conn == NULL || psecret == NULL || id != SASL_CB_PASS) return SASL_BADPARAM;  /* pw unused */
    if ((res = zks_getsecret_compute(c, pw, &len)) != SASL_OK) goto out;   /* no lock held */
    x = c->secret;
    zks_sasl_pass_lock();                    /* serialize writes to the shared buffer; short, no callback */
    memcpy((unsigned char *)x + offsetof(sasl_secret_t, data), pw, len + 1);
    memset((unsigned char *)x + offsetof(sasl_secret_t, data) + len + 1, 0,
           ZSASL_MAX_PASSWORD_LEN - len);
    x->len = len;
    zks_sasl_pass_unlock();
    *psecret = x;
out:
    zks_zero_pw(&pw);
    return res;
}

/* The trampoline's body for the library callback. No lock is held.
 * Same rules; the bytes go to the session sink, and the shared block is not written. */
int zks_getsecret_unlocked(sasl_conn_t *conn, void *context, zks_secret_sink sink,
                           void *sink_arg, sasl_secret_t **psecret)
{
    char pw[ZSASL_MAX_PASSWORD_LEN + 1] ZKS_CLEANUP(zks_zero_pw);
    size_t len = 0; int res;
    if (conn == NULL || psecret == NULL) return SASL_BADPARAM;            /* pw unused */
    res = zks_getsecret_compute(context, pw, &len);
    if (res == SASL_OK) res = sink(sink_arg, (const unsigned char *)pw, len, psecret);
    zks_zero_pw(&pw);
    return res;
}

int zks_is_library_pass_cb(sasl_callback_ft proc)
{
    return proc == (sasl_callback_ft)&_zsasl_getsecret;
}
```

**Zero-on-exit rule (both compiler forms).** `ZKS_CLEANUP` (§4.7.9, `shim/zks_sasl_cb.h`) is `__attribute__((cleanup(fn)))` on GCC and Clang and expands to nothing on MSVC, which has no such attribute. Each of the three functions above is therefore written so that both forms are correct: every return taken after a password buffer may hold secret bytes goes through the single exit label `out`, which zeroes the buffers explicitly; the only earlier returns are the argument checks, before any buffer is written. On GCC and Clang the attribute zeroes the buffers again on every exit and also when a C++ exception from the transform callback or `getpass` unwinds the frame (with `-fexceptions`, §4.7.10); zeroing twice is harmless. On MSVC the explicit cleanup at `out` covers every return; a C++ exception that unwinds the frame skips it, so the stack buffer may keep the password until the stack is reused. Legacy zeroes the password only on the over-length and success paths (zk_sasl.c:586, 597), and not on the other error paths or on unwind.

- **Mutex scope.** No internal lock is ever held across application code.
  - The library password callback (`zoo_sasl_password_t.callback`, and `getpass`) runs from the trampoline through `zks_getsecret_unlocked`, with no ZooKeeper lock held, and writes into the per-session sink that the trampoline owns.
  - The process-wide `zks_sasl_pass_mutex` guards only the short, non-callback in-block copy (`memcpy`, `memset`, `len`) in `_zsasl_getsecret`, which runs for direct Cyrus users of the block (an application that passes the block to its own Cyrus connections). The password is computed before the lock is taken.
  - The mutex is static in `zks_sasl_cb.c`. It is never held across a Cyrus call or application code.
  - Application-written `SASL_CB_PASS` callbacks are also called with no lock held.
  - Because no lock spans application code, a C++ exception from the transform callback or `getpass` unwinds with no lock to release, so unwinding needs no cleanup handler for a lock.
- **`getpass`.** Two handles on different IO threads may call `getpass` at the same time. Legacy holds no lock there either (zk_sasl.c:572), so this is RE. On Windows each prompt uses its caller's stack buffer, so concurrent prompts share no state (FU against a legacy Windows build that has no prompt, 07 WIN-07).
- **Windows prompt (`zks_getpassphrase`).** On `_WIN32` the glue has no `getpass`, so `zks_sasl_cb.c` defines the static helper `char *zks_getpassphrase(const char *prompt, char *buf, size_t cap)`. It writes `prompt` to stderr, reads characters with `_getch()` (no echo) into `buf` until CR or LF ends input or 1023 characters are stored, NUL-terminates `buf` and returns it. It returns NULL on EOF. It uses no static data, so the only copy of the password is in the caller's buffer, which `zks_getsecret_compute` zeroes at `out`. All other password rules are unchanged.
- ZooKeeper sessions do not write the in-block buffer. The block is opaque per the header, so this is not observable.
- Every password stack buffer is zeroed on every return path by the explicit cleanup at `out`, and on GCC and Clang also by the `ZKS_CLEANUP` handler when application code throws. On MSVC a throw skips the explicit cleanup (above). (A `longjmp` out of the transform callback skips cleanup handlers, as it skips C's own code.)
- `zks_secure_zero` maps to `explicit_bzero`, `memset_s`, `SecureZeroMemory`, or a volatile-store fallback.

#### 4.7.8 Log sites

Every SASL line keeps its pinned `func@line` from `abi/log-sites.tsv` (CC-30, 00 §4). The lines are split by who has the data. The three Rust rows are core sites in `abi/log-sites.tsv`; the C glue does not log them:

| Site | Level and text | Legacy | Emitted by |
|---|---|---|---|
| `SASL_FAILED` | ERROR "SASL authentication failed. rc=%d" | `_zsasl_fail` zk_sasl.c:151 | **Rust** (zk-proto), on a glue `FAIL`, a native mechanism error, or a getsockname/getpeername failure; never on a server error or decode failure (C is silent there, zookeeper.c:3147-3150) |
| `SASL_GETSOCKNAME` / `_GETPEERNAME` | ERROR "getsockname" / "getpeername" | `zoo_sasl_connect` :224, :236 | **Rust**, from `handle_sasl_addr_failed` after the driver's rustix address read fails; no START follows |
| `SASL_STEP_IN` | DEBUG "SASL intermediate server_data_len:%d" | `zoo_sasl_client_step` :358 | **Rust**, before the GSSAPI last-packet check and before the STEP item |
| `ZKS_SITE_SASL_GETNAMEINFO` | ERROR "getnameinfo: %s\n" | `_zsasl_getipport` :185 | C glue |
| `ZKS_SITE_SASL_HOST` | DEBUG "Zookeeper Host: %s %s" | :246 | C glue |
| `ZKS_SITE_SASL_NEW` | ERROR "allocating SASL connection state: %s" | :260 | C glue |
| `ZKS_SITE_SASL_START_ERR` / `ZKS_SITE_SASL_START` | ERROR "Starting SASL negotiation: %s %s" / DEBUG "SASL start sr:%d mech:%s client_data_len:%d" | `zoo_sasl_client_start` :287, :294 | C glue |
| `ZKS_SITE_SASL_STEP_OUT` / `ZKS_SITE_SASL_STEP_ERR` | DEBUG "SASL intermediate sr:%d client_data_len:%d" / ERROR "During SASL negotiation: %s %s" | `zoo_sasl_client_step` :371, :376 | C glue |

The C glue logs through `zks_log_site` in the drain (a NULL `log_zh` uses the global stream as `LOGCALLBACK(NULL)` does). zk-proto also emits the protocol-level lines (§4.2, §4.3). The relative order matches C: Rust lines produced before an item are pumped into the outbox ahead of it and emitted first; the glue's lines are emitted during the drain; Rust lines that follow from the result (for example the rc line after "During SASL negotiation") are produced in the following `zkrs_resume`. No line prints a secret: tokens are logged by length only.

#### 4.7.9 C coding rules (both files; enforced in CI)

- no `strcpy`, `strcat`, `sprintf`, `vsprintf`, `gets`, `strtok` (CI greps the two files and runs clang-tidy `bugprone-*`, `cert-*`);
- `snprintf` with a truncation check for every `"ip;port"` string;
- `ZKS_ADD_OVF`/`ZKS_MUL_OVF` for every size computation (callback array, secret copies, block layout, string copies), with every operand of type `size_t`, and `ZKS_CLEANUP` on every password stack buffer (§4.7.7). The portable macros are defined in `shim/zks_sasl_cb.h`, so the same code builds with GCC, Clang and MSVC:

  ```c
  /* shim/zks_sasl_cb.h. The overflow macros return nonzero on overflow, as the GCC/Clang
   * builtins do; callers do not read *r after an overflow. */
  #if defined(__GNUC__) || defined(__clang__)
  #define ZKS_ADD_OVF(a, b, r) __builtin_add_overflow((a), (b), (r))
  #define ZKS_MUL_OVF(a, b, r) __builtin_mul_overflow((a), (b), (r))
  #define ZKS_CLEANUP(fn)      __attribute__((cleanup(fn)))
  #else                        /* MSVC: explicit range checks; explicit "goto out" cleanup */
  static inline int zks_add_ovf(size_t a, size_t b, size_t *r)
  { if (b > SIZE_MAX - a) return 1; *r = a + b; return 0; }
  static inline int zks_mul_ovf(size_t a, size_t b, size_t *r)
  { if (a != 0 && b > SIZE_MAX / a) return 1; *r = a * b; return 0; }
  #define ZKS_ADD_OVF(a, b, r) zks_add_ovf((a), (b), (r))
  #define ZKS_MUL_OVF(a, b, r) zks_mul_ovf((a), (b), (r))
  #define ZKS_CLEANUP(fn)      /* the explicit cleanup at "out" runs instead */
  #endif
  ```

  The glue is written so that both forms are correct: every operand is `size_t`, so the builtins and the range checks give the same result, and every function with a `ZKS_CLEANUP` buffer also zeroes it explicitly at its single exit (§4.7.7). CI greps the two files for direct `__builtin_*_overflow` and `__attribute__((cleanup` uses outside `zks_sasl_cb.h`;
- `strnlen` with an explicit cap for every string of external origin (`ZKS_SASL_LEN_CAP`, `SASL_MECHNAMEMAX`, the 1023-byte password cap);
- `zks_secure_zero` on every secret copy before free, and on every secret stack buffer at the function's single exit, with `ZKS_CLEANUP` on that buffer;
- every Cyrus return code checked; every view and every generation validated on entry;
- no global mutable state except the documented password mutex, and no lock held across application code;
- allocation failure in the glue's own buffers aborts (07 P7), except where legacy returned a code (`SASL_NOMEM` in the PASS path, `NULL`/`ENOMEM` in `zoo_sasl_make_*`, `ENOMEM` in init).

#### 4.7.10 Build hardening (both files; the rest of the shim without the conversion warnings)

- `-D_FORTIFY_SOURCE=3` (2 where the toolchain lacks 3) `-fstack-protector-strong -fstack-clash-protection -ftrivial-auto-var-init=zero -fno-strict-overflow -Wformat=2 -Wformat-security -Wconversion -Wsign-conversion -Werror` for these TUs;
- `-fcf-protection` (x86) or `-mbranch-protection=standard` (AArch64) where supported. These instrument the C code, but the linked library carries no IBT, SHSTK or BTI property note, because the Rust objects have none and the linker keeps only properties that every input has (DV-BLD-8, 00 §5.1). The rustc flags are adopted when they are stable;
- `-fexceptions`, so the `ZKS_CLEANUP` handlers run during C++ unwinding (as for the whole shim, 01 §3.6, CF9); on MSVC the explicit cleanup at each function's `out` label covers every return, and unwinding skips it (§4.7.7);
- configure and CMake probe each flag (01 §4.9, §6.1, §6.3) and drop the ones the compiler does not accept, in every build including releases, and print a notice for each dropped flag. There is no release gate on the full set. `-D_FORTIFY_SOURCE=3` falls back to 2 where the toolchain lacks level 3 (for example glibc 2.28 on AlmaLinux 8);
- `-Werror` is on by default and in CI. Packagers can turn it off with configure `--disable-werror` or CMake `ZK_WERROR=OFF`. Cyrus headers are included as system headers so that their own warnings do not trip `-Werror`;
- the whole shim gets the same flags except `-Wconversion -Wsign-conversion` (`ZKS_HARDEN_CFLAGS`, 01 §4.9).

#### 4.7.11 Verification

- **libFuzzer harness `fuzz/sasl_glue`** (built with `-fsanitize=fuzzer,address,undefined`), linking `zks_sasl_cyrus.c`, `zks_sasl_cb.c`, `zks_log.c` and a static Cyrus with the PLAIN and DIGEST-MD5 client plugins:

  ```c
  /* fuzz/sasl_glue/fuzz_sasl_glue.c */
  static int fake_pass(sasl_conn_t *c, void *ctx, int id, sasl_secret_t **out)
  {   /* a fixed application secret; ctx holds a static sasl_secret_t */
      (void)c; if (id != SASL_CB_PASS) return SASL_BADPARAM; *out = ctx; return SASL_OK;
  }
  int LLVMFuzzerInitialize(int *argc, char ***argv)
  {
      setenv("SASL_PATH", "/nonexistent", 1);           /* no plugin dlopen */
      if (sasl_client_init(NULL) != SASL_OK) abort();
      sasl_client_add_plugin("PLAIN", &plain_client_plug_init);
      sasl_client_add_plugin("DIGEST-MD5", &digestmd5_client_plug_init);
      return 0;
  }
  int LLVMFuzzerTestOneInput(const uint8_t *d, size_t n)
  {
      struct fz in = fz_init(d, n);                     /* bounded reader over the input */
      sasl_callback_t *cb = fz_u8(&in) & 1
          ? zoo_sasl_make_password_callbacks("u", NULL, &fz_pw)   /* library block, temp file */
          : fz_app_callbacks(&in, fake_pass);                       /* application array */
      char *mech = fz_cstr(&in);                        /* fuzzed mechlist, NULL or a C string */
      struct zks_sasl_sess *s = zks_sasl_test_new(cb, "zookeeper", "zk-sasl-md5", mech);
      zks_bytes lo = fz_addr(&in), pe = fz_addr(&in);   /* sockaddr_in/_in6 images; includes bad views */
      uint32_t live = 0;                                /* the gen the drain would have recorded */
      zks_sasl_out o;
      while (fz_more(&in)) {
          uint32_t g = fz_gen(&in, live);               /* 0, live, live + 1, or arbitrary */
          switch (fz_u8(&in) % 3) {
          case 0:                                       /* START, then record gen as the drain does */
              zks_sasl_start(s, g, zks_sasl_sess_params(s), lo, pe, &o);
              if (o.status != ZKRS_SASL_STALE) { zks_sasl_set_gen(s, g); live = zks_sasl_gen(s); }
              break;
          case 1:                                       /* STEP: is_null, len and p combinations */
              zks_sasl_step(s, g, fz_view_or_null(&in), &o);
              break;
          default:                                      /* DISPOSE; a later STEP must fail cleanly */
              zks_sasl_dispose(s, g);
              live = zks_sasl_gen(s);
              continue;
          }
          fz_check_out(&o, g, live);    /* views well formed; STALE iff g is stale; no Cyrus call then */
      }
      zks_sasl_sess_clear(s);
      if (zks_sasl_test_live_secrets() != 0) abort();   /* every copy freed */
      free(s); free(mech); fz_free_callbacks(cb);       /* single free() of the block */
      return 0;
  }
  ```

  It also covers bad views (`is_null` with non-zero length or a non-NULL `p`, `p == NULL` with length, address views that are too short or too long, and address views whose family and length disagree), stale and zero generations on all three glue functions (status STALE and no Cyrus call, checked by an interposer count), the `ZKS_SASL_MAX_CB`/`ZKS_SASL_MAX_SECRETS` limits (SAS-14: `SASL_NOMEM` at 4096 entries and at the seventeenth secret request), an interposed `sasl_client_new` that fails with `*pconn` set (no leak, no dangling callback pointer), short secrets (the secret buffer is at least `sizeof(sasl_secret_t)`), and the zeroing of every secret (a test-only allocator hook checks that freed secret buffers are zero). Corpus seeds come from recorded DIGEST-MD5 exchanges (fake-server `proxy` mode).
- The SASL conformance tests (TestSASLAuth ports, DIGEST-MD5 reconnect, password-file and transform-callback cases, shared-block handles under TSan) run under ASan, UBSan and Valgrind memcheck, and MSan where Cyrus can be instrumented. A C++ transform callback that throws propagates to the application with no library lock held (a second handle then authenticates) and, on GCC and Clang builds, leaves the password stack buffers zeroed.
- Clang static analyzer, `gcc -fanalyzer` and CodeQL run on `shim/` in CI.
- A unit test checks the calloc block layout with `offsetof` (callbacks at 0, then `secret_ctx`, the secret union, the strings) and the single-`free()` release under LSan.
- The depfile isolation check of §4.7.1.

#### 4.7.12 Leaked internals

| Symbol | Behavior |
|---|---|
| `zoo_sasl_client_create(params)` | real: builds a `zks_sasl_sess` with copies of the params, not attached to a handle (opaque `zoo_sasl_client_t`); NULL on allocation failure |
| `zoo_sasl_client_destroy(sc)` | real: `zks_sasl_sess_clear`; the caller frees the object, as with legacy |
| `zoo_sasl_mark_failed(zh)` | faithful: `zkrs_sasl_mark_failed(core)` marks the negotiation failed and sets AUTH_FAILED: state only, no event and no teardown, as legacy (zk_sasl.c:135-141; 02 §7.4) |
| `zoo_sasl_connect`, `zoo_sasl_client_start`, `zoo_sasl_client_step` | return `ZSYSTEMERROR` (DV-ABI-2): they would drive negotiation from an application thread on state the IO owner holds exclusively |

The shim links `-lsasl2`. DT_NEEDED and `.la` are unchanged for SASL.

### 4.8 Native SASL (semantics; API names owned by 04 §16.2)

- Feature `sasl`: a built-in DIGEST-MD5 client in safe Rust (qop=auth, `md-5`, random cnonce). The server name defaults to `zk-sasl-md5`.
- Feature `sasl-gssapi`: GSSAPI through `libgssapi` (`SaslConfig::Gssapi`); it is outside our unsafe budget and never linked into libzookeeper. rsasl is not offered (CC-34, 00 §4).
- An application can supply a custom mechanism factory.
- A new mechanism instance per connection; the credential source is called on every authentication, so rotation works.
- Mechanisms run with every lock released on the thread driver and through `spawn_blocking` under tokio.
- **Failures are terminal.** Any native SASL failure (a mechanism error, a credential-source error of either kind, or a server rejection) ends the session with `Cause::SaslTeardown` and the `AuthFailed` state (`sasl_failure = Teardown`; 04 §16.2). The client does not retry on another server; the application decides whether to create a new client.
- Cyrus is never linked into native builds.

## 5. Digest auth (`zoo_add_auth` and native `add_auth`)

### 5.1 Core model (zk-proto)

```rust
// zk-proto/src/auth.rs
struct AuthEntry<A> { scheme: JString, cred: SecretBytes, done: Option<A> }
pub struct AddAuthError<T: Tokens> { pub code: i32, pub done: Option<T::Auth> }   // token handed back
impl<T: Tokens> Connection<T> {
    /// Token retained iff Ok.
    pub fn add_auth(&mut self, scheme: JString, cred: SecretBytes, done: Option<T::Auth>, now: Millis)
        -> Result<(), AddAuthError<T>>;
}
```

Legacy policy:

- **Append-only.** No dedup or removal until destroy (zookeeper.c:575-588).
- **Gathering.** One collection takes the completion of every entry, so each completion fires at most once (559-573).
- **`AuthPacket{type 0, scheme, auth}`** is sent with xid -4 and op 100. A NULL credential or `certLen == 0` encodes as length 0 (2191-2209). The frame is sensitive.
- **Encoding is infallible**; allocation failure aborts (07 P7). C's "ZMARSHALLINGERROR with the completion still registered" (RC-08) is reachable in C only through allocation failure, so the drop-in has one ownership rule: retained iff `ZKRS_OK` (CC-10, 00 §4). There is no second ownership state.

### 5.2 Shim entry

The body is 05 §12.1's:

```c
int zoo_add_auth(zhandle_t *zh, const char *scheme, const char *cert, int certLen,
                 void_completion_t completion, const void *data)
{
    const zkrs_handle *core;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT;
    struct zks_req *r = NULL;
    uint32_t f = 0;
    zkrs_status s;
    if (scheme == NULL || zh == NULL) return ZBADARGUMENTS;
    ZKS_REGISTRY_CHECK(zh, __func__);   /* debug builds only, 05 §15.5 */
    core = zh->core;
    if (completion != NULL && (r = zks_req_new(ZKRS_K_VOID, ZKS_REQ_AUTH, ZKS_FN(completion), data)) == NULL)
        f = ZKRS_AUTH_F_ALLOC_FAILED;            /* reported by Rust after the state checks */
    s = ZKS_CALL(zkrs_add_auth_check(core, cert, certLen));   /* stages 1-3 without the lock,
                                                    as zookeeper.c:5309-5328 */
    if (s != ZKRS_OK) { zks_req_free(r); return zks_funnel(s); }
    zoo_lock_auth(zh);                           /* exported, as zookeeper.c:5330; st: returns 0.
                                                    lock order: auth_lock -> core */
    s = ZKS_CALL(zkrs_add_auth(core, scheme, cert, certLen, (uintptr_t)r, f, &ob));
    zoo_unlock_auth(zh);
    zks_drain(zh, &ob);                          /* log items only; after the unlock */
    if (s != ZKRS_OK) zks_req_free(r);           /* not retained */
    return zks_funnel(s);
}
```

Order in zk-proto `add_auth` (zookeeper.c:5301-5350; CC-11, 00 §4):

1. `state < 0` → `EARLY_STATE` (ZINVALIDSTATE).
2. `zoo_state() == 0` → `EARLY_STATE` (ZOOKEEPER-800).
3. If `cert != NULL && certLen != 0`: a negative `certLen` gives `SYSTEM` (ZSYSTEMERROR; C's `calloc` fails). Otherwise zk-ffi copies `certLen` bytes into `SecretBytes` (raw.rs, the only C-memory reader) after `try_reserve_exact`; an allocation failure gives `SYSTEM` (ZSYSTEMERROR), as C's checked `calloc` does (zookeeper.c:5319-5322; 07 P7).
4. `ZKRS_AUTH_F_ALLOC_FAILED` → `SYSTEM` (ZSYSTEMERROR). The shim's request allocation failure is reported at the stage where legacy's unchecked entry allocation is (07 RC-08; GLB-04), so the rc order stays exact.
5. Register, then queue (§5.3). Status `ZKRS_OK`; the token is retained.

`zkrs_add_auth_check` runs stages 1-3 with no lock, as legacy does (zookeeper.c:5309-5328, lock at 5330). A call on an expired or closed handle, or with a negative `certLen`, therefore returns at once, also when the caller or another thread holds `zoo_lock_auth`. `zkrs_add_auth` runs stages 1-3 again under the lock, because the state can change between the two calls, as it can in legacy; then it runs stages 4-5.

`zoo_add_auth` has no close check in C. The core accepts it after `begin_close`, and the token is delivered with `ZCLOSING` at final close, as C's destroy path does.

### 5.3 Send and resend ordering

| State at call | Action | C ref |
|---|---|---|
| 3 or 5 | encode **the entry just added** and insert it at the priority boundary; wake the IO thread | 5339-5346, 2231-2246 |
| 2 without SASL | as above, **and** finalize sends every entry again, so the server sees the new entry twice, as in C (`auth_dup_while_associating = true`, AUT-01 RE) | 5340-5345, 2994-2999 |
| 2 with SASL; 999, 1, 7; in native (`auth_completion = PerRequest`) also 0 | record only | 5339-5346 |

On each establishment the priority segment is `[auth_n … auth_1, SetWatches]` in legacy, and forward order in native. Both precede user requests queued earlier (TestClient.cc:800-806).

Sending the specific new entry (AUT-06), boundary insertion (AUT-02) and the immediate wake (DSP-12) change no rc, event or wire content a correct application can depend on (07 P1, 07 P6).

### 5.4 Reply handling (zookeeper.c:2151-2189, 3585-3598)

1. The core emits `AUTH_LOCKPOINT` (barrier), then on resume: if `err != 0`, state becomes -113; it logs the result with the **first** entry's scheme (AUT-05); it emits every pending token as RESULT items in list order with `rc = err` (one barrier group).
2. C runs the completions (IO thread, or st caller), then calls `zkrs_resume`. If the state became -113, the continuation does teardown `ZAUTHFAILED`: SESSION_EVENT(AUTH_FAILED), sync waiters woken, async fakes queued after the event (AUT-04 RE).
3. st `zookeeper_process` returns `ZAUTHFAILED`.

A reply that arrives while the list is empty is logged with the scheme printed as `(null)` and otherwise ignored (SEC-10, AUT-07; C dereferences NULL at 2173-2177).

Resent auths carry no completion. Native (`PerRequest`): replies are matched FIFO to in-flight auth packets.

### 5.5 Teardown, close, RO→RW, threads, and `zoo_lock_auth`

- **Teardown** (disconnect, timeout, expiry, xid mismatch, socket failure, TLS, SASL failure, forced close): after the session event and request fakes, the core emits `AUTH_LOCKPOINT` and then the pending auth completions as RESULT items with the teardown rc (2062-2076).
- **RO→RW.** Auth completions get `ZCONNECTIONLOSS`, uniform with the other pending work (`rw_switch_rc = ConnectionLoss`, DV-FIX-9, 00 §5.1). C's `cleanup(zh, ZOK)` gives them ZOK.
- **Forced close.** The trigger takes the auth tokens pending at that point, and they get `ZCONNECTIONLOSS` on the IO owner's next driving call, after an AUTH_LOCKPOINT, so none runs inside `zoo_a*` or `zoo_set_servers` (DV-FIX-8, 02 §12.3). The auth entries stay in the list and are resent on the next connection. A `zoo_add_auth` after the trigger records its entry with no frame, as in legacy (zookeeper.c:5340-5346), and its completion comes from an AUTH reply on the next connection.
- **Close.** `begin_close`/`destroy` emit `AUTH_LOCKPOINT` and the RESULT items with `ZCLOSING`. The C caller of `zkrs_close` drains them **on the closing thread**, including when close is called from the IO thread, and before any THREAD JOIN item (CC-3, 00 §4).
- **Thread identity** is reproduced (DSP-20): reply path on the IO thread or the st caller; teardown on the thread performing it, which for a forced close is the IO owner; close on the closing thread. No internal lock is held during any completion (SEC-11, DSP-13), which removes C's self-deadlock when a teardown-path completion calls `zoo_a*` or `zookeeper_close` under the non-recursive `zh_lock`. `testCloseInAuthCompletion` must pass on every path.
- **`zoo_lock_auth`/`zoo_unlock_auth`** (exported, undeclared): mt locks/unlocks the per-handle `zh->auth_lock` and returns the pthread rc; st returns 0. The shim initializes the lock exactly as legacy, with `pthread_mutex_init(&zh->auth_lock, 0)` (default attributes, mt_adaptor.c:253; 05 §3). Legacy takes the lock in `zoo_add_auth` and at four IO-side and close sites. Each of the four becomes an AUTH_LOCKPOINT barrier emitted *before* the state change and collection (CC-7, 00 §4); `zoo_add_auth` holds the lock around `zkrs_add_auth`:

  | C site | Drop-in |
  |---|---|
  | `zoo_add_auth` (5330, 2236) | the shim holds the lock around `zkrs_add_auth` |
  | AUTH reply: state change and collection (2160-2171) | lockpoint, then state change and collection |
  | teardown collection (2062) | lockpoint, then collection |
  | close collection (`begin_close` via zookeeper.c:4016 and `destroy` via 682; both reach 2062-2066) | lockpoint, then collection |
  | auth resend in finalize (2216) | lockpoint, then resend |

  ```c
  /* shim/zks_drain.c */
  static void zks_auth_lockpoint(zhandle_t *zh)
  {
      zoo_lock_auth(zh);       /* exported, as zookeeper.c:2062/2160/2216; mt: waits while the
                                  application holds it; st: returns 0 */
      zoo_unlock_auth(zh);
      /* A thread that already holds the lock behaves as in legacy (default mutex). */
  }
  ```

  The lockpoint runs with no Rust frame and no Rust lock, and takes `auth_lock` alone, so no lock-order inversion exists anywhere (order auth → core in `zoo_add_auth`). An application holding the lock therefore stalls IO-side auth processing as in legacy, and **LA-01 is `[diff]` parity**. C has an inversion at the teardown site (`zh_lock` → `auth_lock` against an application holding `auth_lock` while calling an API); the drop-in has none, and the behavior stays inside C's scheduling envelope (07 P1). A caller that already holds `zoo_lock_auth` and then calls `zoo_add_auth` on the same thread behaves as in legacy: stages 1-3 fail without the lock, and otherwise the relock of a default mutex is undefined. The shim adds no diagnostic there, so this is not a deviation.

  The number of `zoo_lock_auth`/`zoo_unlock_auth` calls that symbol interposition sees is not an ABI contract. The shim takes the lock at the AUTH_LOCKPOINT sites (CC-7, 00 §4) and once around `zkrs_add_auth`, where legacy makes two lock calls for a connected `zoo_add_auth` (zookeeper.c:5330, 2236). AR-06 (08 §3.6) excludes these call counts.

## 6. Composition

- **Native.** TLS is a transport stage and SASL a session stage. Order: TCP → TLS → ConnectRequest/Response → SASL → finalize (auth, SetWatches) → Connected. This lifts ZOOKEEPER-3482 for Rust users.
- **Drop-in.** No public entry combines them (`init_ssl` passes `sasl = NULL` and `init_sasl` passes `cert = NULL`, 1520-1544). No export is added (SAS-09). `zks_init_common` accepts both, so a later release can add one.

## 7. Secrets

**Assets:**

- digest credentials, kept for the handle's lifetime for resend;
- SASL passwords (library stack buffers, per-session secret copies, Cyrus);
- DIGEST-MD5 response tokens;
- the TLS key passphrase (4th cert-string field) and decrypted TLS private keys;
- the 16-byte `clientid_t.passwd`;
- native credential-source outputs.

**In scope:** freed or uninitialized heap disclosure through later bugs, core dumps and swap, logs, accidental `Debug`.

**Out of scope:** same-uid ptrace, root/kernel compromise, side channels.

**Controls:**

1. **`zk_proto::SecretBytes`**, a newtype over `zeroize::Zeroizing<Vec<u8>>` (`zeroize` with `default-features = false, features = ["alloc"]`, no derive; CC-19, 00 §4). It is not `Clone`, its `Debug` shows only the length (02 §3.3), and it is allocated with `try_reserve_exact`. It holds `AuthEntry.cred`, SASL tokens and challenges, encoded AuthPacket and GetSASLRequest frames (zeroed on drop, including after a partial write). The connector holds the TLS passphrase in `Zeroizing<Vec<u8>>` (03 §8.2.5). `secrecy` is not used anywhere; native passphrases use a zk-client `Zeroizing<String>` newtype.
2. **TLS key material.** PEM bodies, derived keys and decrypted key DER are `Zeroizing` buffers until the provider's `load_private_key` takes them.
3. **No secret logging at any level.** Auth logs carry the scheme only, SASL logs lengths only, TLS logs file paths only. DEBUG packet dumps skip sensitive frames.
4. **C shim.** `zks_secure_zero()` (`explicit_bzero`, `memset_s`, `SecureZeroMemory`, or a volatile fallback) is applied to: the library password stack buffers, in full, on every return (explicit cleanup at the single exit) and on unwind with GCC and Clang (`ZKS_CLEANUP`; §4.7.7); every per-session SASL secret copy at dispose; the cert-string copy (including `passwd`) when `zookeeper_init_ssl` returns; `clientid_t.passwd` at release (it cannot be zeroed earlier because `zoo_client_id` exposes it).
5. **Copies outside these controls.** Copies inside rustls and the provider (rustls zeroizes its key schedule; provider key objects may not), inside Cyrus, and in the in-block SASL secret when an application uses the block directly; the application's own `sasl_secret_t`; stack copies from Rust moves; kernel buffers.
6. **Zeroization only.** Zeroization is the only memory control. The library uses no `madvise(MADV_DONTDUMP)` and no `mlock`, as C, so core dumps and swap may contain secrets that are live at that moment; the controls above bound how long they are live.

## 8. Ledger rows this document implements (SEC aliases)

The ledger (07) owns each row and its class; this table maps the SEC aliases to the rows.

| ID | Ledger row | Class | Change |
|---|---|---|---|
| SEC-1 | ST-16(b) | FU | Non-blocking handshake; 2/3·T deadline from TCP-connect completion; `ZOPERATIONTIMEOUT`; no `select`/`FD_SET` |
| SEC-2 | ST-16(a) | FU | TCP-connect phase stays exempt; in state 7, `tv` = T/3 when `recv_to <= 0` (C: 0), otherwise `min(recv_to, T/3)` |
| SEC-3 | ST-17 (rc text: C's public rc is `ZSSLCONNECTIONERROR`, CC-14, 00 §4) | FU | Config failure: rc, errno, log and no-teardown as C; no WRITE interest, no retry spin; the receive deadline gives `ZOPERATIONTIMEOUT` on the EINPROGRESS path too |
| SEC-4 | INIT-13 | FU | `zookeeper_init_ssl(cert=NULL)` → NULL, `EINVAL` |
| SEC-5 | INIT-14 | FU | `strtok_r` (removes a data race; the caller's scan is not clobbered) |
| SEC-6 | SAS-04 | FU | SASL negotiation state reset per connection (the wire-desync half is an acknowledged behavior difference, 00 §5.2) |
| SEC-7 | SAS-05 | FU | A final token's reply is consumed by the SASL path |
| SEC-8 | SAS-10 | FU | Server token -1 → Cyrus `(NULL, 0)` |
| SEC-9 | ST-18 | DV (DV-FIX-3) | SASL failure is a full teardown (§4.5) |
| SEC-10 | AUT-07 | FU | AUTH reply with an empty list is ignored |
| SEC-11 | DSP-13 (auth part) | FU | No internal lock held during auth completions |
| SEC-12 | ABI-05, AUT-09 | RE | `zoo_lock_auth` parity through AUTH_LOCKPOINT barrier items at the four legacy sites (§5.5; CC-7, 00 §4); tested by 08 LA-01 |
| SEC-13 | NUL-06 | FU | `zoo_sasl_make_password_callbacks(…, NULL)` → NULL, `EINVAL` |
| SEC-14 | TLS-03, TLS-04, TLS-05, GLB-01 (TLS part); TLS-03 is 07 P6 and TLS-05 is RE | TLS-04 and GLB-01 parts FU; TLS-03 part 07 P6; TLS-05 part RE | Plaintext drained and every buffered frame processed, including frames that arrived before a record error, alert, close_notify or EOF; record/alert errors end the connection instead of being read as would-block (TLS-05, RE); SIGPIPE suppressed on the TLS path. TLS-03 and the OpenSSL error-queue part of TLS-05 do not apply to rustls |
| SEC-15 | SAS-11 (rc RE per CC-24, 00 §4) | FU (07 P4) | SASL reply over-read removed; raw `DecodeError::c_rc()` rcs unchanged |
| SEC-16 | DAT-13 | FU | Per-session secret copies through the PASS trampoline; the library callback runs with no lock held and writes only the per-session copy; the process-wide password mutex guards only the in-block write on the direct Cyrus path; block layout and single `free()` unchanged |
| SEC-17 | ABI-02 (body) | FU | Static `close_zsock` touches only `sock` (no out-of-bounds read of `ssl_sock`) |
| SEC-18 | SAS-14 (00 §5.2) | FU (hardening) | SASL glue limits with no legacy counterpart: a callback array of 4096 or more entries fails START with `SASL_NOMEM`, and a seventeenth secret request on one connection returns `SASL_NOMEM`; unreachable with the shipped mechanisms (§4.7.6) |
| DV-TLS-1..6 | TLS-12 (1); TLS-01, TLS-10, TLS-13 (2); TLS-01, TLS-09, TLS-14 (3); TLS-02, TLS-15, GLB-08, ABI-02, ABI-09, ABI-16 (4); TLS-10 (5); TLS-10, TLS-13, TLS-14 (6, FIPS build only) | DV | §3.13; 00 §5.1 |
| SEC-19 | TLS-06 | 07 P6 (wire only) | close_notify after the CloseSession exchange on a graceful close, without waiting for the peer's; no close_notify on a forced or error teardown or from the RW probe; after a fatal TLS error that rustls detects, the driver in both profiles makes one non-blocking send of the fatal alert that rustls queued, and drops the alert when `ct_out` is not empty, as OpenSSL writes its fatal alert inside `SSL_connect` and `SSL_read` in legacy (03 §4.3.5) (§3.2) |
| SEC-20 | TLS-14 (v1 part) | RE | v1 leaves below a valid chain and v1 self-signed anchors accepted, v1 intermediates rejected, as OpenSSL (§3.14) |
| — | TLS-08 (errno in `handle_socket_error_msg` lines: last failed socket call or 0) | 07 P6 (log text only) | §3.7 |
| — | TLS-09 (NULL CA path) | FU where the C library crashed on a NULL `%s` | `(null)` printed on every platform (§3.4) |
| — | TLS-01 (no name check, no SNI, CA file plus default paths, identity only with cert+key+passwd), TLS-02, TLS-07, TLS-09, TLS-10 (scrypt bounds above 32 MiB fail `ClientKey`, RE per 07 P7), SAS-01/02/03/06/07/08/09/13, AUT-01/03/04/05/06/08, DSP-14, DSP-20, RC-08, INIT-15/16 | RE | As specified above. **Mechanism of these rows:** TLS-01 is rustls in zk-driver with the `LegacyChainOnly` verifier (DV-TLS-1..6 for policy); TLS-03/05 have no OpenSSL error-queue part; SAS-03's quirks live in zk-proto; RC-08 has one ownership state (retained iff `ZKRS_OK`); SAS-13 disposes the Cyrus connection at the next START and at final close, as legacy (§4.1); DAT-13 is FU (SEC-16); TLS-09's `DefaultVerifyPaths` step fails only on a resource error, and a CA file that yields at least one certificate or CRL loads (§3.4) |
| DV-FIX-9 | ST-08 | DV | `rw_switch_rc = ConnectionLoss`: auth completions get `ZCONNECTIONLOSS` on the RO→RW switch (§5.5) |
| DV-FIX-8 | ST-13 | DV | `forced_close = Teardown`: auth completions pending at the trigger get `ZCONNECTIONLOSS` on the IO owner's next driving call (§5.5) |
| DV-FIX-3 | ST-18 (SEC-9) | DV | `sasl_failure = Teardown`: full teardown with an AUTH_FAILED event and `ZAUTHFAILED` for all pending work (§4.5) |

## 9. Milestones

- **M3 (TLS and SASL, CC-26, 00 §4).** Exit: the M3 gate of 08 (TL-*, TK-*, TI-*, SA-*, SU-*, OR-05, ER-05 and the TLS and SASL rows of 08 §4.5, DV-FIX-3 and the TLS cases of DV-FIX-8); IS-5/6/7 across the server matrix; E9 asserts that libssl and libcrypto are **absent** from DT_NEEDED and that libsasl2 is present; the trust-decision corpus (with the v1-leaf cases of §3.14.6) and the key corpus of §10 pass against OpenSSL 3.5 and 3.0; the `aws-lc-rs` and FIPS CI jobs pass on Linux x86_64 and aarch64 (§3.15.1); F12 (`fuzz/sasl_glue`) and F13-F16 run 24 h each with no open crash (08 §15.3 M3 (7)); TestSASLAuth and testSSL* are green on both cores with the M3 `testSSL` fixture (§10).
- The first release with opt-in follows M3. M4 adds Windows mt (CC-33, 00 §4); TLS needs nothing extra there because the TLS code is platform-neutral Rust, apart from the file-name rule of §3.4 (08 TI-06). SASL on Windows stays optional, as in legacy: it is built only when CMake finds Cyrus, the glue uses the SRWLOCK form of the password mutex (§4.7.7), and CI tests it on a best-effort basis; it is not an M4 gate. If the M4 Windows spike fails, Windows ships on the legacy core for that release (DV-BLD-7, CC-33).

## 10. Conformance tests

- **Existing, run against both libraries:** TestClient auth cases (`testAuth`, `testCloseInAuthCompletion`, ZOOKEEPER-1108), all TestSASLAuth cases, `testSSL`, `testSSLNoClientCert`, `testSSLServerOnly`, `testReadOnlyWithSSL`.
  - **`testSSL` fixture.** The CA file is `server.crt`, a leaf signed by `root.crt` with the same DN (ssl/gencerts.sh:43-116; tests/TestClient.cc:975-987). Legacy accepts it only when the leaf has no AKID, so that it is OpenSSL-self-signed (path (b)): this is the `openssl x509 -req` output of OpenSSL ≤ 3.1 and LibreSSL (v1). OpenSSL 3.2 and later add SKID/AKID, and legacy then rejects the set (error 20). The M3 fixture is therefore generated so that the leaf has no AKID: OpenSSL ≤ 3.1 `x509 -req` without extensions, or on OpenSSL 3.2+ `x509 -req -extfile` with `subjectKeyIdentifier=none` and `authorityKeyIdentifier=none` (a v3 leaf without AKID, which OpenSSL accepts by path (b); 08 TC-01a/TC-01b record both verdicts). Any change of the in-tree fixture is a test-only change applied to both cores.
  - The corpus records REJECT on both cores for the 3.2+ gencerts set, whose leaf is not OpenSSL-self-signed (08 §5.2, TC-01b).
- **New conformance cases (`[diff]`, except those marked DEV):**
  - wire capture `auth_n…auth_1, SetWatches` before held-back requests;
  - the duplicate AUTH frame when added while ASSOCIATING without SASL;
  - one reply completing two pending auths;
  - `ZINVALIDSTATE` in the state-0 gap;
  - DEV under DV-FIX-9: auth completions on RO→RW are `ZOK` on the oracle and `ZCONNECTIONLOSS` on the Rust core;
  - DEV under DV-FIX-8: on a forced close, an auth completion pending at the trigger stays pending on the oracle and gets `ZCONNECTIONLOSS` in the next driving call on the Rust core; a `zoo_add_auth` after the trigger completes from an AUTH reply on the next connection on both;
  - DEV under DV-FIX-3: a SASL failure in st and mt, with the oracle's silent state change and the Rust core's teardown (event, `ZAUTHFAILED` for sync, async and auth, socket closed), the same st rc and the same DISPOSE point on both;
  - graceful close sends one close_notify record after the CloseSession exchange; the wire-trace comparison allows this extra record (TLS-06, 07 P6), and forced and error teardowns and the RW probe send none; a verify failure and a corrupt record send the fatal alert once when no earlier ciphertext is waiting, as the legacy core does, and a received alert, close_notify or EOF sends nothing;
  - completion thread roles;
  - LA-01 (AUTH reply while the app holds `zoo_lock_auth`): parity;
  - st `zoo_state` is 7 during an EINPROGRESS handshake and 0/999 during an immediate one;
  - each TLS config step returns `ZSSLCONNECTIONERROR` with `errno == EINVAL`, the legacy line and no teardown, on both paths (08 TL-06 for the immediate path, 08 §4.5 ST-17 for the EINPROGRESS path; CC-14, 00 §4);
  - `SSL_CERT_FILE` names an existing empty or unparsable file and no other default location yields a certificate: the `DefaultVerifyPaths` step succeeds with no anchor from the defaults, as in legacy, and the CA file alone decides the handshake;
  - TL-09b (08 §4.4): a ConnectResponse that reports expiry, followed by close_notify in the same read, gives ZSESSIONEXPIRED; a reply followed by a fatal alert in the same read delivers the reply, then ZCONNECTIONLOSS;
  - SASL log lines: "SASL authentication failed. rc=%d" after a glue failure and after a getsockname failure, and not after a server error reply;
  - cert-string field shifts;
  - close order: at `zookeeper_close` the socket is closed before `sasl_dispose` runs (interposer call order), as in legacy `destroy`;
  - dispose timing: after a teardown, `sasl_dispose` for the old connection runs at the next SASL start, immediately before that start's `sasl_client_new`, and not at the teardown (interposer call order), as in legacy `zoo_sasl_connect`; its position relative to the `getsockname`/`getpeername` calls, which Rust makes when it builds the START item, is a syscall order (07 P6) and is not compared;
  - a throwing C++ st watcher, auth completion and SASL password callback propagate to the app (callback-frame rule).
- **`[rs]` / DEV cases:**
  - stalled handshake → `ZOPERATIONTIMEOUT`, close returns;
  - CA failure on the EINPROGRESS path: no CPU spin, failover at 2/3·T;
  - SASL stale-flag reconnect, final-token reply, -1 token, malformed reply rc set;
  - empty-list AUTH reply;
  - `strtok` caller-state preservation;
  - shared SASL callback block across handles with concurrent authentication under TSan (no report).
- **Fault-injecting transport** (zk-testkit `TlsPeer`): the TL-09 and TL-09b cases at every split offset; a record error after valid records in the same read (the valid frames are delivered first); EOF and RST during the handshake (`TlsHandshake`); a `read_tls` buffer-full condition with no progress (`Data`, no busy loop).
- **Trust-decision differential corpus** (08 §5.2: generated by `tests/conformance/tls/gencorpus.sh`, committed at `tests/conformance/tls/corpus/<case>/`, with fixed keys and `-attime`/fixed `now`). Each case runs the O2 invocation of 08 §5.1 (`openssl verify -purpose sslserver -CAfile <ca> -untrusted <chain> -attime T -auth_level L <leaf>`, with SSL_CERT_DIR at the case's defaults/ directory (08 §5.1 for default-file cases)), under OpenSSL 3.5 and 3.0, and `LegacyChainOnly` with the same inputs; accept/reject must agree, except rows marked DV-TLS-3 (every build) or DV-TLS-6 (FIPS build), which must reject. Its end-to-end tier replays each case through real handshakes of both cores against zk-fakeserver (08 §5.2). Cases include:
  - the v1-leaf cases of §3.14.6;
  - the OpenSSL path post-check cases, each a chain that webpki alone accepts (REJECT on both cores): a v3 leaf under a `CA:TRUE` intermediate whose keyUsage lacks keyCertSign; a root with pathLenConstraint 0 over one intermediate; an intermediate whose AKID does not match the root's SKID; two same-subject sent intermediates, the wrong-key one first, with a leaf without AKID; and a CN-only v3 leaf under a permitted dNSName constraint that the CN does not match;
  - the sent-certificate cases (08 TC-49), with v3 and v1 leaves: a bridge in the CA file with the root, whose own issuer only the server sends (REJECT on both cores, error 2); a self-signed sent certificate before a certificate with the same name and key issued by the trusted root (REJECT on both cores, error 19); the same pair in the other sent order (ACCEPT on both cores);
  - the keyCertSign post-check cases (08 TC-47), with a v3 leaf: two time-valid same-name intermediates sent by the server, one whose keyUsage lacks keyCertSign, in both sent orders; the same pair with one of them as a trusted bridge in the CA file; a sent intermediate without keyCertSign that has the name of the anchor that issues the leaf; and a sent cross-certificate of the anchor together with a sent issuer of it without keyCertSign. The Rust core rejects every variant; where OpenSSL's path uses the certificate without keyCertSign, both cores reject (the oracle with error 79 from `X509_check_ca`; the Rust reason is 32), and where it does not, the oracle accepts and the case is DV-TLS-3. The TC-16 webpki-behavior unit test runs `verify_server_cert_signed_by_trust_anchor` alone on a chain through an intermediate whose keyUsage lacks keyCertSign and asserts that webpki accepts it, while `LegacyChainOnly` rejects it;
  - the `gencerts.sh` sets from OpenSSL 3.0 (no AKID: both accept) and OpenSSL 3.2+ (AKID: both reject); a non-self-signed leaf in the CA file with its root also present (path (a): accept); a non-self-signed leaf in the CA file with its issuer absent (reject); a self-signed leaf in the CA file (v1, v3, v3 `CA:TRUE`, v3 with keyUsage lacking keyCertSign); a self-signed leaf in the CA file with an unknown critical extension, plain and as a `TRUSTED CERTIFICATE` with a serverAuth trust list (REJECT on both cores, error 34); a self-signed leaf not in the trust set; a same-subject mimic; two same-subject trust inputs;
  - expired, not-yet-valid and boundary-time leaf, intermediate and root; missing intermediates; an intermediate-only CA file (partial chain: reject); an intermediate in the CA file with its root in the default directory;
  - wrong EKU on leaf and intermediate; SGC-only EKU; keyUsage keyCertSign-only leaf; Netscape cert type client-only; host-name mismatch (accept);
  - root without basicConstraints and keyUsage; root with keyUsage lacking keyCertSign; path-length and name-constraint violations; a directoryName constraint on an intermediate that permits the leaf DN (webpki rejects; DV-TLS-3 where the oracle accepts, 08 TC-18); unknown critical extension;
  - SHA-1 and MD5 signed intermediates, RSA-1024, DSA, P-521, Ed448, RSA-PSS, brainpool P-256r1 and secp256k1 leaves and intermediates, and an RSA-1024 self-signed leaf in the CA file (path (b)) (DV-TLS-3); an RSA-16384 intermediate (RSA keys of more than 8192 bits), a SHA-224-signed leaf (RSA and ECDSA) and an ecdsa-with-SHA512 P-256 leaf and P-384 leaf in the `ring` build (DV-TLS-3, 08 TC-48); Ed25519 (accepted by both); a non-self-signed v3 server certificate with `CA:TRUE` and X.509 v2 certificates (DV-TLS-3); v3-leaf chains of more than six intermediates; a self-issued intermediate below a CA with pathLenConstraint 0 and a self-issued intermediate outside the permitted name subtree of the CA above it (v3 leaf: DV-TLS-3 where the oracle accepts; v1 leaf: accepted by both, 08 TC-52); an issuer name that equals its issuer's subject name only in OpenSSL's canonical form, by case, spacing or string type (v3 leaf: DV-TLS-3 where the oracle accepts; v1 leaf: accepted by both, 08 TC-53); a compressed-point EC leaf and intermediate (DV-TLS-3 in the `ring` build, provider-tagged in the aws-lc builds, 08 TC-54); DER oddities that OpenSSL tolerates (negative serial, duplicate extensions, non-canonical time encodings, a v1 certificate with an extensions field: DV-TLS-3 where the Rust core rejects), and the same certificates in the CA file next to a valid root (the file loads in both cores);
  - `TRUSTED CERTIFICATE` with aux trust and reject lists (a rejected certificate never matches path (b)); a self-signed leaf in the CA file as `TRUSTED CERTIFICATE` with a serverAuth aux trust list and an EKU of clientAuth only (path (b): the aux trust satisfies the purpose, ACCEPT on both cores, 08 TC-21); the server sends an intermediate that the CA file holds as `TRUSTED CERTIFICATE` with a serverAuth reject list, with the root trusted (REJECT on both cores, error 28); a rejected input that passes `check_issued` for a sent certificate where OpenSSL's path does not use it, because a time-valid non-rejected store issuer comes first in store order (the post-check rejects: DV-TLS-3 where OpenSSL accepts); two same-name issuers, one without keyCertSign, in both orders (trust set and sent list; REJECT on both cores where OpenSSL picks the no-certsign one, the oracle with error 79 from `X509_check_ca` and the Rust reason 32; in the trust set the ambiguity rule rejects both orders, DV-TLS-3 where OpenSSL accepts by store order; with a v3 leaf the keyCertSign post-check also rejects both sent-list orders, DV-TLS-3 where OpenSSL accepts); CA file with only a CRL (the `CaFile` step succeeds in both cores and the default paths decide), with a garbage block, empty (the `CaFile` step fails in both cores), unreadable, `ca = NULL`;
  - hash-directory names with correct and wrong hashes, a suffix gap and an upper-case hash name (Rust rejects both; DV-TLS-3 where OpenSSL 3.0 accepts them through its default store lookup, §3.4), an unloadable `.0` before a valid `.1` (REJECT on both cores), a `.0` with a valid certificate before a corrupt block (Rust rejects; DV-TLS-3 against OpenSSL 3.0 and 3.5, §3.4), a hash-named file of one subject that also holds the root of another subject (Rust rejects; DV-TLS-3 against OpenSSL 3.0 and 3.5), a `SSL_CERT_DIR` list of two directories; a set-uid process ignoring the variables; `SSL_CERT_FILE=/nonexistent` (no anchors from the file location; the system bundle is not substituted); subject-hash values against `openssl x509 -subject_hash`.
- **Client-identity corpus**, compared step by step (which log line) with the legacy library built against OpenSSL: PKCS#1, SEC1, PKCS#8; encrypted PKCS#8 with PBES2/PBKDF2-HMAC-SHA256/AES-256-CBC, PBKDF2-HMAC-SHA1/AES-128-CBC, scrypt, DES-EDE3-CBC; scrypt with `N`/`r` above the 32 MiB limit, `p·r ≥ 2^30`, and a mismatched `keyLength` (the `ClientKey` line in both cores; no abort; also an F14 fuzz seed); PBKDF2-HMAC-SHA224, -SHA384 and -SHA512 (accepted by both cores); PBKDF2-HMAC-SHA512-256 (DV-TLS-5); PKCS#12 PBE (DV-TLS-5); PBES1 (both cores reject under the reference default providers); traditional AES-128/192/256-CBC and DES-EDE3-CBC; traditional DES-CBC (DV-TLS-2: the reference legacy core fails `ClientKey` without the OpenSSL legacy provider); a SHA-1-signed client leaf and an RSA-1024 intermediate in the chain file (DV-TLS-2, 08 TK-20); traditional AES-128-CFB and CAMELLIA-256-CBC (DV-TLS-5, 08 TK-13); PBES2 with CAMELLIA-256-CBC (DV-TLS-5, 08 TK-05 (e)); a P-521 key in the `ring` build (DV-TLS-5, 08 TK-19); an Ed448 key, an RSA-1024 key (08 TK-14b), an RSA-PSS (`id-RSASSA-PSS`) key, an EC key with explicit curve parameters, and a leaf with a compressed EC point with its matching key (DV-TLS-5); RSA keys of 8192 and 9000 bits, an RSA key with the public exponent 3 and a SEC1 key without the public key (DV-TLS-5 per provider, 08 TK-19); wrong passphrase; same-type mismatched key (`ClientKey` step); different-type key (`CheckPrivateKey` step); key file with a certificate first; passphrase longer than 1024 bytes; cert without key, key without passwd (no identity). In the FIPS build the same corpus runs with the DV-TLS-6 cases tagged: PBKDF2-HMAC-SHA256/384/512 with AES-128/192/256-CBC load, and traditional PEM encryption, DES-EDE3-CBC, PBKDF2-HMAC-SHA1/-SHA224 and scrypt fail at `ClientKey`.
- **Cipher suites and renegotiation:** a TLS 1.2 server that offers only CBC, only static-RSA or only DHE suites (DV-TLS-3: the Rust core does not connect); a TLS 1.2 server that requests renegotiation after the handshake (DV-TLS-1: refused).
- **FIPS build:** init asserts `provider.fips()`; every per-attempt config reports `fips()`; a TLS 1.2 server without Extended Master Secret fails the handshake; a handshake offers only the FIPS provider's suites and groups (wire capture).
- **Windows file names (08 TI-06):** a CA, cert and key path in UTF-8 with non-ASCII characters, and the same names in the ANSI code page, both load; a UTF-8 name that is not found falls back to the ANSI decoding.
- **Sans-IO (zk-proto):** every row of §3.8 and §4.3; every failure source of §4.5 gives the same teardown; stale-gen results ignored.
- **Concurrency:** concurrent `zookeeper_init_ssl` under TSan; `zoo_add_auth` racing a teardown under TSan.
- **Secrets:** a test allocator asserts that `SecretBytes`, sensitive frames and decrypted key buffers are zero before free; the SASL harness asserts the same for secret copies.
- **SASL hardening:** §4.7.11.

## Interfaces defined

- `zk_driver::net::tls` (features `tls`, `tls-ring` | `tls-aws-lc-rs` | `tls-aws-lc-rs-fips`, exactly one; `#![forbid(unsafe_code)]`): `TlsConn {is_handshaking, wants_read, has_ciphertext, handshake, feed, feed_eof() -> Result (latches Closed; Handshake while handshaking), read_plaintext (data first, then the latched error), write_frame, take_ciphertext, send_close_notify (graceful close only)}`, `TlsConnector::{new_conn(&Unlocked, TlsPurpose{Main, Probe}, &zk_proto::Endpoint{addr: SocketAddr, host: HostToken}) -> Result<Box<dyn TlsConn>, TlsSetupError{Config{step: TlsConfigStep}, Resource{at: TlsResourceAt}}>` (called on TCP-connect completion, before `handle_tcp_connected`; no time argument: the verifier uses the rustls time provider), `path_for(step) -> Option<&[u8]>}` (signatures owned by 03 §8.2.1), `TlsFailure{Handshake, Data, Closed}`, `TlsTransport` (driver-side, in `net/tls/mod.rs`); sole implementation `RustlsConn` (rustls::ClientConnection over Vec<u8> buffers via read_tls/write_tls, set_buffer_limit(None), one write_all per poll_transmit chunk, plaintext drained per feed, failures latched behind buffered plaintext) built by `RustlsConnector` per attempt and per probe.
- zk-proto TLS types (02 names, owned by zk-proto): `TlsConfigStep {CaFile, DefaultVerifyPaths, ClientCertChain, ClientKey, CheckPrivateKey}`, `TlsResourceAt {ContextNew, ConnectionNew}`, `Endpoint {addr: SocketAddr, host: HostToken}`, `TransportFailure {SocketCreate{errno}, TcpRefused{errno}, Io{errno}, Eof, TlsResource{at, errno}, TlsHandshake{errno}, TlsConfig{step, file: JString} (file for the log line only), Poll{errno}}`, `ProbeTlsFailure {Config{step, file}, Resource{at}, Handshake}`; `handle_connect_started(ConnectStart)`, `handle_tcp_connected`, `handle_transport_ready`, `handle_transport_failed(..) -> rc` (TlsConfig: ZSSLCONNECTIONERROR, errno EINVAL, no teardown); `Compat::tls_verify {LegacyChainOnly, WebPki}`.
- `LegacyChainOnly: rustls::client::danger::ServerCertVerifier` (rules (a)-(d) of §3.5): path (b): an OpenSSL-self-signed leaf (`EXFLAG_SS`) byte-identical to a trust-set certificate that is not Rejected, v1 or v3, accepted after the validity check and the ssl_server purpose check, which an aux trust list naming serverAuth or anyExtendedKeyUsage satisfies (§3.14.3 Purpose), and the critical-extension check of §3.14.3 (error 34), with the mimic rule only for same-subject ambiguity and a reject when a time-valid Rejected input is in the mimic set; any other self-signed leaf rejected; a non-self-signed leaf always goes through path (a); a v1 leaf in path (a) goes to `v1_leaf::verify` (§3.14: OpenSSL `ossl_x509_likely_issued` issuer selection, keyCertSign (error 32) and Rejected-input (error 28) checks on the chosen issuer, `check_ca` (1 for intermediates, non-zero for the anchor), ssl_server CA purpose, pathLenConstraint, name constraints, the notAfter rule, signatures through the provider's `SignatureVerificationAlgorithm`, depth 100, trusted-first candidate order with the ambiguity rule, a work budget of 256); a v3 leaf in path (a) `ParsedCertificate::try_from` + `rustls::client::verify_server_cert_signed_by_trust_anchor(&parsed, &RootCertStore{anchor_candidates valid at now: self-signed anchors and explicitly trusted TRUSTED CERTIFICATE inputs}, &[server intermediates ++ trusted bridges], now, algs.all)` + the keyCertSign post-check `legacy_verify::keycertsign_post_check` (error 32 when a certificate of those intermediates reachable from the leaf by `check_issued` has keyUsage without keyCertSign) + the rejected-issuer post-check + keyUsage/nsCertType + the OpenSSL path post-check `v1_leaf::verify_v3_path` (the §3.14.3 issuer selection and finish rules over the leaf, the sent certificates and the trust set: trusted-first selection, `find_issuer` order, no sent certificate above a trusted bridge or a self-signed sent certificate, the ambiguity rule, keyCertSign on the chosen issuer, `check_ca`, pathLenConstraint including the anchor's, name constraints including `NAME_CONSTRAINTS_check_CN`, with the leaf's own EKU/keyUsage/nsCertType purpose and its SAN; accept only when webpki and all three post-checks accept); never `verify_server_name`; handshake signatures from the SPKI (TLS 1.2 via `WebPkiSupportedAlgorithms::mapping` and `SignatureVerificationAlgorithm::verify_signature`; TLS 1.3 via `rustls::crypto::verify_tls13_signature_with_raw_key`); `ClientConfig.enable_sni = false`, `resumption = Resumption::disabled()`, `ServerName::IpAddress(peer ip)`.
- Trust loading (`trust.rs`, shared by the drop-in and native): CA file PEM (CERTIFICATE / X509 CERTIFICATE / TRUSTED CERTIFICATE with aux rules; CRLs parsed and counted, not used; all-or-nothing; the step succeeds when the file yields at least one certificate or CRL, and a file that yields neither fails `CaFile` with "Failed to load CA file %s"); default paths: `SSL_CERT_FILE`/`SSL_CERT_DIR` used exactly as given (even if missing) when set and `tls_env_allowed` (drop-in: `zkrs_init_args.tls_env_allowed` from `getauxval(AT_SECURE)`/`issetugid()`; native: rustix `getuid() == geteuid() && getgid() == getegid()` on Unix, true on Windows); else the compile-time `ZK_TLS_DEFAULT_CERT_FILE`/`ZK_TLS_DEFAULT_CERT_DIR` (`option_env!`), set by `--with-rust-tls-default-paths`, which defaults to the configure-recorded OPENSSLDIR (`openssl version -d` of the probed installation's `openssl` binary: `$with_openssl/bin/openssl`, `$OPENSSL_ROOT_DIR/bin/openssl` in CMake, else the first on `PATH`; else the first existing entry of the 01 §6.1 platform list on the build host; a native cargo build with the variables unset applies the same list in zk-driver's build.rs); no run-time search, no openssl-probe; Windows env only; hash-dir `HHHHHHHH.N` with OpenSSL canonical subject hash; `DefaultVerifyPaths` fails only on a resource error; re-read per attempt and probe.
- Client identity: chain PEM; keys PKCS#1/SEC1/PKCS#8, encrypted PKCS#8 (pkcs8 0.10 `EncryptedPrivateKeyInfo`, parameter bounds before `decrypt`: scrypt rejected before allocation when `128·r·(N+2) + 128·r·p > 32 MiB` (OpenSSL's default `EVP_PBE_scrypt` maxmem), `p·r < 2^30`, keyLength matches the cipher, PBKDF2 PRF HMAC-SHA1, HMAC-SHA224, HMAC-SHA256, HMAC-SHA384 or HMAC-SHA512; pkcs5 0.7 PBES2 with PBKDF2 or scrypt, AES-CBC or DES-EDE3-CBC), traditional PEM (`EVP_BytesToKey` MD5, count 1, salt IV[0..8]; AES-128/192/256-CBC, DES-EDE3-CBC, DES-CBC); PKCS#12 PBE and other encryptions rejected (DV-TLS-5; PBES1 fails in both cores under the reference default providers); FIPS build: unencrypted keys and PBES2 with PBKDF2-HMAC-SHA256/384/512 and AES-128/192/256-CBC only, decrypted in the FIPS module through `aws_lc_rs::pbkdf2` and `aws_lc_rs::cipher::PaddedBlockDecryptingKey` (DV-TLS-6); passphrase = first min(strlen, 1024) bytes; slot-based key match (ClientKey vs CheckPrivateKey step); `LegacyClientCert: ResolvesClientCert`. Windows path fields: UTF-8 candidate first, ANSI code-page candidate (`MultiByteToWideChar(CP_ACP)` in zk-ffi) on invalid UTF-8 or not-found.
- Provider builds (§3.15): `ring` (default), `aws-lc-rs`, `aws-lc-rs-fips` (`rustls::crypto::default_fips_provider()`, `provider.fips()` asserted at init, `require_ems = true`, `ClientConfig::fips()` checked per attempt; Linux x86_64 and aarch64 only); the two aws-lc builds ship only after the ASF legal review (00 §6).
- Native only: feature `tls-platform-verifier`, `TlsOptionsBuilder::with_platform_verifier()` (rustls-platform-verifier; banned from the zk-ffi closure).
- zk-proto SASL: `Action::Sasl(SaslCall{gen: u32, kind: SaslCallKind{Start, Step(Option<SecretBytes>), Dispose}})`, `handle_sasl_started(gen, Result<(String, SaslStep), SaslError>, now) -> rc`, `handle_sasl_stepped(gen, Result<SaslStep, SaslError>, now) -> rc`, `SaslStep{token, done}`, `SaslNegotiation` per connection; `SaslSession` trait for native mechanisms; Rust emits the "SASL authentication failed. rc=%d", "getsockname", "getpeername" and "SASL intermediate server_data_len:%d" sites (core sites in `abi/log-sites.tsv`); `handle_sasl_addr_failed(gen: u32, which: SaslAddr{Local, Peer}, now) -> rc` (02 §4) after a rustix `getsockname`/`getpeername` failure, with no START; `Dispose` immediately before the next `Start` on a reconnect and after `CloseTransport` at final close, never at teardown alone.
- `shim/zkrs.h` (05 names and constant values): `zkrs_bytes{p, len, is_null}`, `zkrs_sasl_call{kind START|STEP|DISPOSE, gen, local, peer, token}` (`local`/`peer`: `zkrs_bytes` with the platform `sockaddr_in`/`sockaddr_in6` image written by zk-ffi), `zkrs_sasl_out{status CONTINUE|DONE|FAIL|STALE, mech, token}` (STALE = -2: glue to drain only), `zkrs_sasl_result(core, gen, view)` (inside a SASL item only; copies before return; stale gen ignored; rc via `zkrs_resume`); `zkrs_init_args` TLS fields `tls`, `tls_ca`, `tls_cert`, `tls_key`, `tls_passwd` plus `tls_env_allowed` (05 §6).
- `shim/zks_sasl.h` (no Cyrus types): `zks_bytes` = `zkrs_bytes`, `zks_sasl_out` = `zkrs_sasl_out`, `struct zks_sasl_params`, opaque `struct zks_sasl_sess`, glue `void zks_sasl_start(zks_sasl_sess *s, uint32_t gen, const zks_sasl_params *p, zks_bytes local, zks_bytes peer, zkrs_sasl_out *out)`, `void zks_sasl_step(zks_sasl_sess *s, uint32_t gen, zks_bytes challenge, zkrs_sasl_out *out)`, `void zks_sasl_dispose(zks_sasl_sess *s, uint32_t gen)` (each rejects a stale gen with STALE and no Cyrus call; `local`/`peer` are `sockaddr_in`/`sockaddr_in6` images whose family and length the glue validates before `getnameinfo(NI_NUMERICHOST|NI_NUMERICSERV)` and the bounded `snprintf` of "%s;%s"; mechlist from `p->mechlist`, with no separate accessor); on `sasl_client_new` failure the glue disposes `*pconn` if it is non-NULL; `zks_sasl_sess_new(zh, params)` (called by `zks_init_common` after resolution; NULL → ENOMEM), `zks_sasl_sess_free`, `zks_sasl_sess_clear`, `zks_sasl_gen`, `zks_sasl_set_gen` (defined in `zks_sasl_cyrus.c`; called by the drain after a START that was not STALE), `zks_sasl_sess_params`.
- `shim/zks_sasl_cb.h` (Cyrus types; two TUs only): portable `ZKS_ADD_OVF`/`ZKS_MUL_OVF` (`__builtin_*_overflow` on GCC and Clang, explicit range checks on MSVC) and `ZKS_CLEANUP` (`__attribute__((cleanup))` on GCC and Clang, explicit `goto out` cleanup on MSVC); `zks_is_library_pass_cb(proc)`, `zks_getsecret_unlocked(conn, ctx, sink, sink_arg, psecret)` (legacy password rules, no lock held, bytes to the per-session sink); per-session callback array copy with `zks_sasl_pass_trampoline` (no lock held for either the library or an application callback) and `struct zks_pass_ctx{orig_proc, orig_ctx, is_library, secrets}`; secret copies zero-filled and at least `sizeof(sasl_secret_t)`; the one process-wide password mutex is static in `zks_sasl_cb.c`, and `_zsasl_getsecret` takes it only around the short in-block write; no internal lock is held across application code.
- Shim `zoo_add_auth` (05 §12.1): `zkrs_add_auth_check(core, cert, cert_len)` first (stages 1-3, no lock, no outbox, zookeeper.c:5309-5328), then auth_lock around `zkrs_add_auth(core, scheme, cert, cert_len, token, flags (ZKRS_AUTH_F_ALLOC_FAILED), ob)`; `zkrs_status {OK, EARLY_STATE, SYSTEM}`; token retained iff OK. `zks_auth_lockpoint` for `ZKRS_ITEM_AUTH_LOCKPOINT`; `auth_lock` initialized with default attributes, as legacy; `zoo_lock_auth`/`zoo_unlock_auth` call counts seen by interposition are not an ABI contract (AR-06, 08 §3.6, excludes them).
- Shim `zookeeper_init_ssl` (05 §15.1): `strtok_r` ×4 into `struct zks_tls_args`; NULL cert → NULL/EINVAL; identity only if cert && key && passwd; the copy is zeroed and freed before init returns, and the handle does not keep it. `close_zsock`: closes `sock` only.
- `zks_secure_zero(void*, size_t)`: `explicit_bzero`/`memset_s`/`SecureZeroMemory`/volatile fallback.
- Build: `--with-openssl[=DIR]`/`WITH_OPENSSL` (names, defaults and autodetect probe exactly as legacy: default yes/ON means autodetect; TLS, the `zookeeper_init_ssl` export and `HAVE_OPENSSL_H` are present exactly where legacy has them; the probe only decides the feature and OpenSSL is never linked; TLS on selects zk-ffi `tls,tls-<provider>`; side-by-side uses the same probe result), `--with-rust-tls=yes`/`WITH_RUST_TLS` (opt-in, off by default: TLS without OpenSSL headers), `--with-rust-tls-provider=ring|aws-lc-rs|aws-lc-rs-fips`/`ZK_RUST_TLS_PROVIDER`/Maven `rust-tls-provider` → cargo features `tls-ring`/`tls-aws-lc-rs`/`tls-aws-lc-rs-fips` (same names in zk-driver and zk-ffi; none or more than one is a `compile_error!`; no `--all-features` in CI), `--with-rust-tls-default-paths=FILE:DIR`/`ZK_RUST_TLS_DEFAULT_PATHS` (default: the configure-recorded OPENSSLDIR; passed to cargo as `ZK_TLS_DEFAULT_CERT_FILE`/`ZK_TLS_DEFAULT_CERT_DIR`), `--with-sasl`/`WITH_CYRUS_SASL` unchanged; feature defines as legacy: autotools `-DHAVE_OPENSSL_H` in the library CPPFLAGS when TLS is built and `-DHAVE_CYRUS_SASL_H` when SASL is built, CMake `HAVE_OPENSSL_H` and `HAVE_CYRUS_SASL_H` PUBLIC on `zookeeper` (CMakeLists.txt:213, 227), where TLS is built when the autodetect found OpenSSL or the opt-in is set; shim TUs do not depend on `HAVE_OPENSSL_H` (`zks_internal.h` undefines it, and `HAVE_CYRUS_SASL_H` outside the two SASL TUs, before including `zookeeper.h`); Maven: `abi/tls-probe.sh` (01 §13.7) reproduces configure.ac:42-67 and emits the default trust paths, and the root property `rust-tls` (default `no`) maps to `--with-rust-tls` and the zk-ffi feature set; closure goldens `zk-ffi-<target>.txt`, `zk-ffi-<target>-aws-lc-rs.txt` and `zk-ffi-<target>-aws-lc-rs-fips.txt`; SASL TU hardening with `-Werror` on by default (`--disable-werror`, `ZK_WERROR=OFF`) and probe-and-drop flags with a notice per dropped flag.
- Exported data `ZOO_SSL_CONNECTING_STATE` (const int 7, undeclared); `zerror(ZSSLCONNECTIONERROR)` returns "ssl connection error" (DV-FIX-6).

## Decisions

| Decision | Rationale | Alternatives |
|---|---|---|
| The drop-in TLS backend is rustls 0.23 in zk-driver (`forbid(unsafe_code)`, feature `tls`), driven over memory buffers by `RustlsConn`, in three provider builds: ring (the default), aws-lc-rs and the FIPS build aws-lc-rs-fips (`tls-ring`, `tls-aws-lc-rs`, `tls-aws-lc-rs-fips`; exactly one per build). The library contains no OpenSSL, no shim TLS file and no `tls_*` vtable leaf. | One TLS stack serves both profiles. With rustls the library has no OpenSSL dependency closure, and rustls keeps all TLS state machine code in memory-safe Rust, so there is no TLS code in C. The driver keeps sole socket ownership, MSG_NOSIGNAL and a core-owned deadline. | OpenSSL from shim C over memory BIOs (keeps policy parity but keeps OpenSSL and TLS glue in C). The `openssl` crate (enlarges the unsafe closure). `SSL_set_fd` on the Rust fd (SIGPIPE, two fd owners, hidden plaintext). |
| `LegacyChainOnly` follows rules (a)-(d) of §3.5: anchors are OpenSSL-self-signed, CA-capable, purpose-compatible, time-valid trust inputs, plus explicitly trusted `TRUSTED CERTIFICATE` inputs (§3.4); non-self-signed trust inputs bridge as intermediates; a leaf byte-identical to a trust-set certificate is accepted after the validity check and the ssl_server purpose check (which an aux trust list naming serverAuth or anyExtendedKeyUsage satisfies) only when that certificate is OpenSSL-self-signed (path (b)); other leaves go through `verify_server_cert_signed_by_trust_anchor` plus OpenSSL's keyUsage/nsCertType rules, the keyCertSign and rejected-issuer post-checks, and the OpenSSL path post-check, which applies OpenSSL's issuer selection and chain rules (§3.14.3) to the path OpenSSL would build (v1 leaves through the v1-leaf verifier, §3.14); no name check, no SNI. | Keeps small deployments that trust a self-signed server certificate (the in-tree tests) working, matches OpenSSL on the gencerts output of every OpenSSL line, keeps partial-chain rejection and the anchor-level, keyUsage, AKID and name-constraint checks that webpki alone does not apply, and uses only public rustls APIs. Trust is never wider than legacy (08 §1, 08 P6); a corpus against `openssl verify` guards it. | Accepting any byte-identical trust-set leaf (accepts a non-self-signed leaf whose issuer is absent, which OpenSSL rejects with error 20). Plain `WebPkiServerVerifier` without names (rejects CA:TRUE and v1 leaves, accepts partial chains). OpenSSL-rule path building as the only acceptance path for v3 leaves (the verdict would then rest on the in-tree code alone, without webpki's checks). |
| After webpki accepts a v3 leaf, the keyCertSign post-check rejects the chain (error 32) when any certificate of `I'` that can lie on the path from the leaf by `check_issued` has keyUsage without keyCertSign. The stricter verdicts it adds where OpenSSL's path does not use that certificate, such as the order-dependent same-name case, are part of DV-TLS-3. Its code comments state that it exists only for OpenSSL parity and how to remove it (the function, its tests and the DV-TLS-3 text). A TC-16 unit test confirms that webpki alone accepts such a chain. | rustls-webpki does not enforce keyCertSign on CA certificates, and `verify_server_cert_signed_by_trust_anchor` does not return the path it built. Checking every certificate that can lie on that path never accepts more than OpenSSL, independent of how exactly the OpenSSL path post-check reproduces OpenSSL's issuer choice. The unit test reports a webpki change that would make the check redundant. | Relying on the OpenSSL path post-check alone (its keyCertSign rule then rests only on the reproduction of OpenSSL's issuer choice). Checking only the certificates that OpenSSL would choose in a second, separate selection (duplicates the post-check). |
| Handshake signatures are verified from the SPKI that our DER view extracts, for TLS 1.2 through `WebPkiSupportedAlgorithms::mapping` and for TLS 1.3 through `verify_tls13_signature_with_raw_key`. | `verify_tls12_signature` parses the certificate with webpki, which rejects the v1 leaves that path (b) and the v1-leaf verifier accept. | Rejecting v1 leaves everywhere (breaks gencerts certificates from OpenSSL ≤ 3.1). |
| v1 leaves get full OpenSSL parity: an in-tree, `forbid(unsafe)` v1-leaf verifier (§3.14) accepts a v1 leaf at any depth below a valid chain, with OpenSSL's issuer matching, `check_ca`, purpose, pathLenConstraint, name-constraint, validity and depth rules, signatures through the provider's `SignatureVerificationAlgorithm`, and a fail-closed rule where OpenSSL's choice is order-dependent; v1 self-signed anchors are accepted and v1 intermediates rejected; v3 leaves stay on webpki. | ZooKeeper operators follow the documented examples, and `ssl/gencerts.sh` makes v1 leaves on OpenSSL ≤ 3.1, which OpenSSL accepts under a trusted CA. The verifier is small, safe Rust, used only where webpki cannot parse the leaf, and differentially tested against OpenSSL 3.0 and 3.5. | Rejecting v1 leaves issued by a CA (breaks deployments built from the documented scripts). Re-implementing OpenSSL path building for every leaf (replaces webpki where it works). |
| The FIPS build uses aws-lc-rs `fips` with rustls `fips` and `require_ems`, the FIPS provider's suites, groups and signature algorithms only, `provider.fips()` asserted at init and `ClientConfig::fips()` checked per attempt; every signature check, the v1-leaf verifier included, and every key decryption runs in the FIPS module; key loading accepts unencrypted keys and PBES2 with PBKDF2-HMAC-SHA256/384/512 and AES-CBC only (DV-TLS-6); Linux x86_64 and aarch64. | Gives deployments that require FIPS 140-3 cryptography a supported build without OpenSSL, while the default build keeps the wider legacy key formats. Each provider build has its own closure golden, and cargo-deny runs with the build's own configuration (`deny.toml` for `ring` and `aws-lc-rs`, `deny-fips.toml` for FIPS), so the closure stays audited per build. | OpenSSL FIPS mode (the library contains no OpenSSL). Running the RustCrypto key decryption in the FIPS build (cryptography outside the module). |
| Graceful close sends close_notify in both profiles, flushed without blocking within the close budget, with no wait for the peer; forced and error teardowns and the RW probe send no close_notify. After a fatal TLS error that rustls detects, the driver in both profiles makes one non-blocking send of the fatal alert that rustls queued, and drops it when `ct_out` is not empty. | A clean TLS shutdown lets the server tell a close from a truncation. The record is wire-only and no application can observe it (07 P6); it never delays close. The fatal-alert rule is OpenSSL's behavior on the non-blocking legacy socket, so the server sees the same alert as from legacy. | Never sending it, as C (a truncation-like close on every shutdown). Waiting for the peer's close_notify (can delay close by up to the budget). Dropping the fatal alert after an error (the server would see EOF where legacy sends the alert). |
| Windows path fields are opened as UTF-8 first through the wide API, then with the ANSI code page on invalid UTF-8 or not-found. | This is OpenSSL's `openssl_fopen` order on Windows, so every path that legacy opens still opens. | UTF-8 only (breaks ANSI-encoded paths that legacy opens). ANSI only (breaks UTF-8 paths). |
| `RustlsConn` latches a record error, alert, close_notify or EOF and delivers every byte decrypted before it first; only a handshake failure is reported by `feed` at once. | C reads data before it sees the error or EOF, so a ConnectResponse that reports expiry followed by close_notify gives ZSESSIONEXPIRED in C; returning the error from `feed` would lose that plaintext and give ZCONNECTIONLOSS. | Failing `feed` on any error (drops data the server already sent). |
| Default verify paths: the environment variables are used exactly as given (and only when allowed); otherwise the compile-time default from `--with-rust-tls-default-paths`, which defaults to the configure-recorded OPENSSLDIR (`openssl version -d` of the `openssl` binary of the installation the legacy probe selected, else the 01 §6.1 platform list applied on the build host); no run-time search; hash directories are read by OpenSSL's subject-hash names, by the `by_dir` rule; the step fails only on a resource error. | Matches what an OpenSSL-linked libzookeeper trusts, keeps the set-uid rule, never substitutes the system bundle for a missing path named by a variable (which would widen trust), and needs no trust-location crate. Missing or empty defaults never fail the step, as in legacy, so a host whose CA file suffices always connects. | Run-time search of candidate locations through openssl-probe (`probe()` reads the environment behind the set-uid rule and substitutes the system bundle). Reading every file in a directory (trusts files OpenSSL never reads). Reproducing the OpenSSL 3.0 default store lookup (case-insensitive hash names, no gap stop), which OpenSSL 3.5 does not do and which would trust more than a legacy core linked against 3.5. Failing the step when existing defaults yield no certificate (fails attempts that legacy completes). The OS trust store (changes the trust set on macOS and Windows). |
| Client keys: PKCS#1/SEC1/PKCS#8, encrypted PKCS#8 via pkcs8/pkcs5 with parameter bounds checked before decryption, traditional OpenSSL PEM encryption (AES-*-CBC, DES-EDE3-CBC, DES-CBC) via a small `legacy_pem` module (`EVP_BytesToKey` MD5); scrypt parameters over 32 MiB are rejected before allocation; the key/cert match reproduces which OpenSSL step fails. | Covers the formats OpenSSL users actually produce, keeps the legacy log line per step, keeps decrypted bytes in zeroizing buffers, and turns an oversized scrypt parameter into the same step failure OpenSSL gives at its default `EVP_PBE_scrypt` maxmem instead of an abort. | Unencrypted keys only (breaks the documented passwd field). Decrypting without bounds (process abort on a hostile key file). A Rust OpenSSL binding for key parsing (pulls OpenSSL back in). |
| A TLS config failure returns ZSSLCONNECTIONERROR with errno=EINVAL and C's log line and does not tear down; only WRITE interest and retries are dropped, and the receive deadline fails over with ZOPERATIONTIMEOUT (CC-14, 00 §4). | Verified C behavior on both public paths (zookeeper.c:2717-2720, 3093-3094). The only defect is the EINPROGRESS-path spin and hang. | ZBADARGUMENTS (C's internal rc only). Immediate teardown (changes the pending rc and the reconnect cadence). |
| The TCP-connect phase stays exempt from the receive timeout and only its tv=0 spin is fixed; the handshake is non-blocking with a 2/3·T deadline from TCP-connect completion; the visible state is 7 on the EINPROGRESS path and 0/999 during an immediate-connect handshake. | Matches 07 ST-16 with no new visible deviation. | A deadline from attempt start (changes failover timing). Reporting 7 uniformly. |
| `--with-openssl`/`WITH_OPENSSL` keep their names, defaults and autodetect probe, so TLS, the `zookeeper_init_ssl` export and `HAVE_OPENSSL_H` (PUBLIC in CMake) are present in exactly the configurations where legacy has them; the probe only decides the feature, and OpenSSL is never linked. The opt-in `--with-rust-tls=yes` (`WITH_RUST_TLS`) enables TLS without OpenSSL headers. | The default export set and the consumer defines match legacy on every host, so no build script or consumer sees a change (01 §4.8). Hosts that want TLS without installing OpenSSL use the additive opt-in. | Enabling TLS on every host by default (adds an export where legacy has none). Requiring OpenSSL headers for every TLS build (no TLS on a host without OpenSSL). |
| All user-reaching security callbacks follow the callback-frame rule with the C loop on every thread: auth completions are RESULT items, Cyrus runs from SASL items in the drain, log lines are LOG items; results return through `zkrs_sasl_result`. | DSP-14 holds on every thread; no Rust frame is ever below application code; no vtable entry reaches application code (CC-1, 00 §4). | A `run_items` vtable entry with Rust frames below callbacks on the IO thread (breaks DSP-14 there). |
| SASL stays Cyrus in hardened C: two isolated TUs, a Cyrus-free bounded interface of three glue functions that each carry the generation and reject a stale one (`zks_bytes` views, copy-at-once outputs, length caps), a shim-owned per-handle session whose generation the drain records after START, single-owner serialization, coding rules, build hardening, a libFuzzer harness and sanitizer runs. | The public API is bound to Cyrus types, so the engine must stay in C. The rules minimize the C code that touches untrusted bytes, remove ownership ambiguity, and put the stale-generation check where the fuzz harness reaches it. | Moving SASL to Rust for the drop-in (the public API exposes `sasl_callback_t`). Calling Cyrus from Rust through FFI (application callbacks under Rust frames). |
| SASL log sites are split by who has the data: Rust emits the rc, getsockname/getpeername and server_data_len lines; the glue emits the lines that need Cyrus results. The rc line fires only where C's `_zsasl_fail` runs. | Rust owns the address reads and the failure transition (05 §16.2); C's `process_sasl_response` path is silent on server errors (zookeeper.c:3147-3150, verified). | All SASL lines in the glue (the glue never sees address failures or server errors). |
| The PASS trampoline gives each Cyrus connection its own zero-filled secret copy; the library's own password callback runs with no lock held and writes only into that per-session sink; direct Cyrus users of the block take the process-wide mutex around the short in-block write only. No internal lock is held across application code. | Removes the DAT-13 race with no layout change and serializes every write of the shared buffer, while holding no lock across application code (K2, DSP-13): an interactive prompt or a throwing transform callback cannot stall, deadlock or lock out other handles, and unwinding needs no lock cleanup. | Holding the mutex around the library callback and the copy (a lock across application code: serializes all handles behind a prompt, can self-deadlock, and needs a cleanup handler on unwind). Verbatim in-place update (keeps the race). |
| SASL failure is a full teardown as on the digest-failure path: AUTH_FAILED, the event, the socket closed, ZAUTHFAILED for sync, async and auth; the st driving call returns the SASL rc; the Cyrus connection stays until the final close. | Watchers learn of the failure, pending work ends at once, and no sync caller can deadlock. The rc and the dispose point stay C's, so only the cleanup changes (DV-FIX-3). | C's silent state change. |
| `zoo_add_auth` keeps the legacy list policy including the duplicate AUTH frame while ASSOCIATING without SASL; encoding is infallible and the token is retained iff ZKRS_OK; a shim allocation failure is a flag reported at legacy's stage. | Every rc, wire order and completion fan-out matches C; one ownership state removes a use-after-free class (CC-10, 00 §4); the rc order stays exact under OOM (05 §12.1). | `ZKRS_LATE_RETAINED` (a second ownership state). Aborting on the shim allocation (C's checked rc set has ZSYSTEMERROR there). Dropping the duplicate frame (wire-visible). |
| `zoo_lock_auth` parity comes from AUTH_LOCKPOINT barrier items at all four legacy sites, handled in C with lock/unlock of `auth_lock`, which is initialized with default attributes as in legacy; `zoo_add_auth` holds the lock around `zkrs_add_auth`. | LA-01 parity with no lock-order inversion and no lock held across callbacks (CC-7, 00 §4); same-thread relock behavior stays legacy. | A vtable lock taken by Rust around collection (inverts lock order). Never taking it on the IO side (fails LA-01). |
| Auth completions on the RO→RW switch receive a uniform ZCONNECTIONLOSS (DV-FIX-9). | C's ZOK reports success for an auth that the new connection resends, which is misleading; the uniform rc matches the other pending work. The deviation is registered with a release note. | C's ZOK (keeps the misleading rc). |
| Secrets in Rust use `SecretBytes` over zeroize; TLS key material uses `Zeroizing` buffers; C uses `zks_secure_zero`, with explicit cleanup at a single exit and `ZKS_CLEANUP` on password stack buffers. | One small audited crate (CC-19, 00 §4); a single type for all protocol secrets; C buffers are zeroed on every return, and on GCC and Clang also when application code throws; the same source is correct on MSVC, which has no cleanup attribute. | `secrecy` (a second crate for the same job). A `black_box` wipe (no guarantee). Explicit zeroing only on normal returns (misses unwinds). |
| SASL protocol quirks live in zk-proto; the C glue has no mechanism-specific code. | One implementation serves both profiles and is testable sans-IO. | Quirks in the shim (the native API would duplicate them). |
| Secrets in memory are protected by zeroization only, with no `madvise(MADV_DONTDUMP)` and no `mlock`. | C does neither; zeroization bounds how long secrets are live without changing core-dump contents or needing memory-lock limits. | `MADV_DONTDUMP` (changes core-dump contents that operators rely on). `mlock` (fails under `RLIMIT_MEMLOCK`). |
| Native SASL failures are terminal (`Cause::SaslTeardown`); the application decides whether to create a new client. | One native SASL failure path in zk-proto; a retry policy belongs to the application, which knows whether a credential error is transient. | Retrying transient credential errors on the next server (needs a recoverable SASL cause and can loop on a broken credential). |
| `-Werror` on the SASL TUs is on by default and in CI, with `--disable-werror` / `ZK_WERROR=OFF` for packagers; hardening flags are probed and dropped with a notice. | Warnings in the code that handles untrusted bytes fail CI, while distribution builds with newer compilers are not blocked. | Unconditional `-Werror` (breaks packaging on new compilers). A release gate on the full flag set (blocks older supported toolchains). |
