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

# Workspace, crate architecture, build, link and packaging

## 1. Scope and summary

This document specifies how the Rust workspace, the C shim and the existing build systems (autotools, CMake, Maven) produce `libzookeeper_mt` and `libzookeeper_st` (shared and static) as drop-in replacements, and how the native Rust crates are built and shipped in the ASF source release. §12 lists the cross-document contracts that this document fixes.

Main decisions:

1. **Callback-frame contract (normative for every document).** No application code runs while a Rust frame is on the stack, on any thread. Everything that reaches application code leaves Rust as an item in an ordered *outbox*, and C shim code drains it after the `zkrs_*` call returns. That covers completions, watchers, auth completions, sync results, log callbacks, and Cyrus SASL steps, whose callbacks are application code. The loops of the two mt library threads are C functions in `zks_threads.c` that call `zkrs_io_step` and `zkrs_wait_dispatch`. The host vtable that Rust may call contains only Pure and Blocking C leaves, and none of them can reach application code or `log_message`. A C++ exception thrown from any callback therefore unwinds through C frames only, exactly as in legacy (07 DSP-14 is RE on every thread).
2. **Crate graph:** `zk-jute ← zk-proto ← zk-driver ← {zk-client, zk-ffi}`, with `zk-recipes` on top of zk-client. zk-ffi never depends on zk-client, so native-only code (tokio, tracing, native SASL, persistent watches, recipes) cannot be linked into `libzookeeper_*`.
3. **TLS is rustls in Rust for both profiles.** The TLS code lives in `zk-driver/src/net/tls/` behind the cargo feature `tls`, which needs exactly one provider feature: `tls-ring` (the default), `tls-aws-lc-rs` or `tls-aws-lc-rs-fips` (the FIPS build, §4.10). zk-ffi enables `tls` plus one provider when the build is configured with TLS. The one `TlsConn` implementation is `RustlsConn`. The drop-in uses the compat verifier `LegacyChainOnly` (`Compat::tls_verify`). OpenSSL is not used anywhere in the Rust client build: the shim has no TLS file and the host vtable has no TLS entry.
4. **SASL stays Cyrus, in hardened C.** Exactly two shim translation units include `<sasl/sasl.h>`: `zks_sasl_cyrus.c` (engine glue) and `zks_sasl_cb.c` (`zoo_sasl_make_*` and their callbacks). They are compiled with a dedicated hardening flag set (§4.9) and are excluded from the build when SASL is disabled.
5. **Pinned drop-in dependency closures, one per TLS configuration.** Without TLS: `rustix` (libc backend) plus `libc`, `errno` (which rustix renames `libc_errno`), `bitflags`, `linux-raw-sys` (Linux), `windows-sys` (Windows) and `zeroize`. With TLS: that set plus `rustls`, `rustls-pki-types`, `rustls-webpki`, `untrusted`, the crypto provider (`ring` by default; `aws-lc-rs` with `aws-lc-sys`, or with `aws-lc-fips-sys` for the FIPS build, as build options), the RustCrypto key-loading crates (`pkcs8`, `pkcs5`, `der`, `spki`, `pem-rfc7468`, `pbkdf2`, `scrypt`, `hmac`, `sha1`, `sha2`, `aes`, `des`, `cbc`, `cipher`, `md-5`; the FIPS build omits the RustCrypto KDF, cipher and digest crates `pbkdf2`, `scrypt`, `hmac`, `sha1`, `aes`, `cbc`, `cipher`, `des` and `md-5` and keeps `pkcs8` with only its `pkcs5` feature, §3.3 R3) and `base64ct`, with their transitive closure. There is no `rustls-pemfile`, no `rustls-native-certs`, no `rustls-platform-verifier` and no `openssl-probe`. Golden files and `cargo tree` in CI enforce each closure (§3.3).
6. **C-environment adapters that stay in C, in the shim:** Cyrus SASL, `getaddrinfo` with the legacy hints, the libc PRNG, thread creation (`pthread_create(NULL attr)` / `_beginthreadex`), Winsock start-up, and the log sink.
7. **One Rust build per configuration serves both flavors.** One `zk-ffi` staticlib is built with cargo profile `capi` (`panic = "abort"`, fat LTO). The shim is compiled twice, with and without `-DTHREADED`. The flavor reaches Rust at runtime. Cargo features follow only the configure options that apply to both flavors (TLS on or off, TLS provider).
    The C result layouts that the application receives (`String_vector`, `ACL_vector`, `Stat`, the string, data and multi results) are built by zk-ffi's audited `abi::raw` module with libc `malloc`/`calloc`, with legacy's sizes and allocation choices, so the application's `free()` and the `deallocate_*` functions work unchanged. C keeps delivery: callback invocation and the legacy post-callback deallocation order (05 §10).
8. **Each library is one pre-linked relocatable object per flavor.** The shim objects, `recordio.o`, `zookeeper.jute.o` and the Rust staticlib (including the crypto provider's C and assembly objects) are joined with `ld -r`. Every global outside the golden archive set is localized. The single object feeds both the shared library (version script on top) and the static archive (one member). No `zks_*`, `zkrs_*`, Rust std or crypto-provider symbol is global in either artifact (except on MSVC, DV-BLD-3).
9. **Golden ABI data:** `abi/symbols.tsv`, `absent.tsv`, `static-symbols.tsv`, `needed/<os>.txt`, `layout/`, `abixml/`, `release-deltas/`, `log-sites.tsv`. The build has one export-list format source (`abi/symbols.tsv` and `abi/static-symbols.tsv`): `gen-exports.awk`, and on CMake for Windows the `ZkExports.cmake` parser, whose output CI proves identical (§5), so a legacy-core build never needs cargo. All binary checks are `cargo xtask abi …`. With the Rust core, libssl and libcrypto are not DT_NEEDED.
10. **Build options:**
    - autotools: `--with-rust-client=no|yes|side-by-side|auto` (default `no` before M5 and `auto` from M5; `yes` and `side-by-side` are mandatory, and `auto` builds the Rust core when the host can build it and otherwise the legacy core with a notice, §6.1), `--with-rust-tls-provider=ring|aws-lc-rs|aws-lc-rs-fips`, `--with-rust-tls=yes` (TLS without OpenSSL headers, off by default), `--with-rust-tls-default-paths=FILE:DIR`, `--with-rust-workspace=DIR` (default auto: `$srcdir/rust`, then `$srcdir/../zookeeper-client-rust`), `--with-rust-staticlib=PATH` (link a staticlib that Maven built), `--with-rust-target=TRIPLE` (cross builds), `--with-cargo-vendor=DIR` (offline build from a `cargo vendor` directory), `--with-system-rust-crates[=DIR]` (offline build from distribution crates, §3.11), `--disable-werror` (§4.4);
    - CMake: `-DWITH_RUST_CLIENT=ON|OFF|AUTO` (unset: `OFF` before M5, `AUTO` from M5; §6.3), `-DZK_RUST_TLS_PROVIDER=ring|aws-lc-rs|aws-lc-rs-fips`, `-DWITH_RUST_TLS=ON|OFF` (default OFF), `-DZOOKEEPER_BUILD_SHARED=ON|OFF` (default OFF), `-DZK_RUST_TLS_DEFAULT_PATHS=FILE:DIR`, `-DZKRS_WORKSPACE=DIR` (default empty: `rust`, then `../zookeeper-client-rust`), `-DZKRS_STATICLIB=PATH`, `-DRust_TARGET=TRIPLE`, `-DZOOKEEPER_CARGO_VENDOR=DIR`, `-DZK_SYSTEM_RUST_CRATES=DIR`, `-DZK_WERROR=ON|OFF` (default ON);
    - Maven (§13): `-Prust` (builds the Rust module; implied by any `-Drust-client=…`), `-Drust-client=no|yes|side-by-side`, `-Drust-tls-provider=…`, `-Drust-tls=no|yes` (default `no`; maps to `--with-rust-tls` and to the zk-ffi feature set), `-Drust-system-crates=DIR`, `-Drust-cargo-vendor=DIR`, `-Dcargo.executable=…`, `-Dcargo.offline`.

    Every Rust-core build contains the nine behavior fixes DV-FIX-1..9, always on, and no build option selects them (00 §5.4, 07 §5.2). `--with-rust-client=no` builds the legacy core with unchanged behavior and exports (§6.2).

    The existing `--with-openssl` / `WITH_OPENSSL` switch keeps its name, its default and its decision procedure. The default `yes` / `ON` is an autodetect: TLS is built, and `zookeeper_init_ssl` and `HAVE_OPENSSL_H` are present, exactly in the configurations where the legacy build has them (§4.8). With the Rust core the probe only decides the zk-ffi TLS features. OpenSSL is never linked, and libssl and libcrypto are not DT_NEEDED (DV-TLS-4). The opt-in `--with-rust-tls=yes` / `WITH_RUST_TLS` enables TLS on a host without OpenSSL headers.
11. **Milestones:** M0 core and native API; M1 mt; M2 st and static archives; M3 TLS and SASL; M4 Windows; the first release with the opt-in follows M3; M5 default flip (the configure and CMake default becomes `auto`); M5R the native recipes crate (`zk-recipes`). The legacy C core stays a supported, buildable and maintained configuration: `--with-rust-client=no` / `-DWITH_RUST_CLIENT=OFF` prints no deprecation warning, and its pinned reference build is the differential oracle (08 §4.1).
12. **Jute (§8).** zk-jute's sources are generated by the Java `RustGenerator` (`Rcc -l rust`, Maven execution `generate-Rust-Jute` in `zookeeper-jute`) into the git-ignored `crates/zk-jute/src/generated/`. A std-only `zk-jute/build.rs` only checks that the files exist. Its types are the null-preserving `JString`/`JBuffer`/`JVec` (§8).
13. **Versioning.** The root pom is the single version source. `zk-ffi/build.rs` reads the header path from `ZK_VERSION_H`, which Maven, configure and CMake set, so the check also works in the `make dist` layout.
14. **Toolchain and supply chain.** The workspace uses MSRV 1.85, edition 2024 and resolver 3. One license allowlist applies to every closure (§3.8). `cargo-vet` must certify `safe-to-deploy` for every shipped drop-in closure. Crate sources are fetched, not vendored in the release: every build fetches them (network access) or reads them from a `cargo vendor` directory, and with `--locked` cargo checks every crate against its `Cargo.lock` checksum. Offline builds use `--with-cargo-vendor`, `-Drust-cargo-vendor` or `-Dcargo.offline`. Distributions build in the system-crates mode (§3.11) against their own packaged crates, so they can patch rustls or the provider in place.
15. **Maven (§13).** The module `zookeeper-client/zookeeper-client-rust` (packaging `pom`, not deployed) is in its own root-pom profile `rust`, which `-Prust` activates, and which also activates whenever the `rust-client` property is defined, because a Rust-core C build needs the staticlib. `-Pfull-build` alone builds no Rust code and never needs cargo (§6.5). The module builds the drop-in staticlib with `cargo build --locked --profile capi -p zk-ffi --features <set>` (never `--workspace`) and the native crates in a separate invocation, `cargo build --locked --workspace --release --exclude zk-ffi`. The C module links the staticlib through `--with-rust-staticlib`, so Maven builds the Rust code once.

## 2. Directory tree

```
zookeeper-client/zookeeper-client-rust/   Cargo workspace and Maven module (no C build logic except the test-only `cc` builds of zk-xcheck and of fuzz targets F8, F10 and F11)
  pom.xml                         Maven module (§13): packaging pom, not deployed; antrun cargo build/test;
                                  profiles cargo-test, rust-lint, rust-supply-chain, rust-test-coverage,
                                  rust-test-asan; release execution replace-rustclient-files-during-release
  Cargo.toml  Cargo.lock          workspace manifest (every member publish = false); lockfile committed
  .cargo/config.toml              xtask alias only (§3.9)
  deny.toml  deny-fips.toml       cargo-deny: every build; the FIPS build (§3.7)
  clippy.toml  unsafe-budget.toml
  LICENSE NOTICE README.md
  supply-chain/                   cargo-vet: config.toml audits.toml imports.lock
    closure/                      golden normal+build dependency closures (§3.3)
      zk-ffi-<target>.txt             drop-in closure, TLS on, ring (default configuration)
      zk-ffi-<target>-aws-lc-rs.txt   drop-in closure, TLS on, --with-rust-tls-provider=aws-lc-rs
                                      (x86_64 and aarch64 Linux only; other targets build without a golden)
      zk-ffi-<target>-aws-lc-rs-fips.txt  drop-in closure, TLS on, --with-rust-tls-provider=aws-lc-rs-fips
                                      (Linux x86_64 and aarch64 only)
      zk-ffi-<target>-notls.txt       drop-in closure, TLS off (--with-openssl=no, or no OpenSSL found)
      native-<featureset>.txt         native closures for the supported zk-client and zk-recipes
                                      feature sets: the default set, one per provider and one for
                                      tls-platform-verifier
  crates/
    zk-jute/       pkg apache-zookeeper-jute    src/generated/ (git-ignored; RustGenerator output, §8)
      build.rs                                  existence check of src/generated/ only (std-only, forbid(unsafe))
    zk-proto/      pkg apache-zookeeper-proto   sans-IO core; src/generated/log_sites.rs (committed)
    zk-driver/     pkg apache-zookeeper-driver  threaded / polled / tokio drivers, transport traits
      build.rs                                  default trust locations: ZK_TLS_DEFAULT_CERT_{FILE,DIR}, else
                                                the §6.1 platform list on the build host (std-only, forbid(unsafe))
      src/net/tls/                              features "tls" + "tls-ring" | "tls-aws-lc-rs" | "tls-aws-lc-rs-fips":
                                                TlsConn/TlsConnector, RustlsConn, PEM reader, DER view, trust
                                                inputs, LegacyChainOnly (with the v1-leaf path), client identity,
                                                legacy_pem
    zk-client/     pkg apache-zookeeper         native public API (04)
    zk-recipes/    pkg apache-zookeeper-recipes native recipes on zk-client only (04): DistributedLock,
                                                LeaderLatch, TreeCache; forbid(unsafe); milestone M5R
    zk-ffi/        pkg zk-ffi                   staticlib; the only shipped crate with unsafe
      build.rs                                  version cross-check only (forbid(unsafe))
      src/lib.rs                                safe: outbox storage, tokens, request conversion
      src/abi/*.rs                              #[unsafe(no_mangle)] extern "C" zkrs_*; calls into raw.rs
      src/abi/types.rs                          repr(C) zkrs.h types (cbindgen source; #[path]-included by
                                                zk-ffi-layout)
      src/abi/raw.rs                            abi::raw, the only reader and writer of C memory: views and the
                                                C-memory readers, struct reads, out-parameter writes, the
                                                host-vtable calls, the handle reference counting, the outbox
                                                impl and resolve-sink conversions (05 §8); parent of
                                                raw/writers.rs
      src/abi/raw/writers.rs                    abi::raw::writers: the 05 §10.1 result writers (legacy C result
                                                layouts with libc malloc/calloc) and the sync copy-out (05 §13)
                                                (deps: types.rs, legacy.rs, libc)
      src/abi/legacy.rs                         repr(C) mirrors of the public result structs that abi::raw::writers (raw/writers.rs) writes
      src/abi/standin/                          #[cfg(test)] Rust stand-ins for the C side (fake host, 08 §8.6)
      cbindgen.toml                             config for ../../../zookeeper-client-c/shim/zkrs.h (dev tool)
      UNSAFE.md                                 per-block invariants
    zk-ffi-layout/                              ctest layout checks of the zkrs.h types and of the abi/legacy.rs
                                                mirrors against the C headers (08 L2); #[path] includes of
                                                ../../zk-ffi/src/abi/{types,legacy}.rs; never shipped;
                                                own workspace and lockfile (excluded from members), stable toolchain only
    zk-testkit/                                 ManualClock, MemTransport, ServerSim, LegacyModel, ...
    zk-fakeserver/                              scripted TCP fake server (TLS via rustls, test-only)
    zk-xcheck/                                  compiles legacy C oracles with `cc` from zookeeper-client-c/;
                                                unsafe allowed; never shipped
  fuzz/                           cargo-fuzz targets (own workspace, excluded from members), including the
                                  reply-to-C-layout writers of abi::raw under ASan (abi/{types,legacy}.rs and
                                  abi/raw/writers.rs included by #[path], because zk-ffi is staticlib only)
  xtask/                          abi, layout, conformance, difftest, ledger, bench, gen, log-sites, check-version,
                                  semver
  dev/                            vendor.sh  gen-license-binary.sh
  design/                         this design set: 00-overview.md ... 08-verification.md (not built; part of the source release with the rest of the tree)
  target/                         git-ignored; Maven cargo output in target/cargo/ (removed by mvn clean)

Outside the workspace (paths relative to the repository root):

pom.xml                           profile rust (module zookeeper-client/zookeeper-client-rust, §13.1);
                                  rust-* properties (§13.1)
zookeeper-jute/
  pom.xml                         execution generate-Rust-Jute beside generate-C-Jute (§8)
  src/main/java/org/apache/jute/compiler/RustGenerator.java   Rcc -l rust (§8)
zookeeper-client/
  pom.xml                         full-build lists zookeeper-client-c only (§6.5)
  zookeeper-client-c/             the C half of the drop-in, and the legacy core
    configure.ac Makefile.am CMakeLists.txt pom.xml    serve both cores
    include/                        UNCHANGED, single copy, installed byte-identical
    generated/                      gitignored; zookeeper.jute.{c,h} from Rcc -l c (unchanged)
    src/                            legacy core (supported and maintained; the differential oracle, 08 §4.1)
                                    + cli.c load_gen.c recordio.c, which both cores use
    shim/                           the shim sources (file list owned by 05 §2)
      zkrs.h                        private Rust<->C ABI: cbindgen output, committed, drift-checked
      zks_internal.h                struct _zhandle, zks_req, zks_sync, thread-role TLS, ZKS_CALL
      zks_log_sites.h               generated from abi/log-sites.tsv, committed, drift-checked
      zks_sasl.h                    SASL glue interface for the drain and zks_handle.c; no Cyrus types
      zks_sasl_cb.h                 interface between the two SASL TUs; Cyrus types; included by those two only
      zks_data.c                    the 36 exported data objects
      zks_handle.c                  init variants (not _init_sasl), close, release, accessors,
                                    interest/process, pair cell, WSAStartup/WSACleanup,
                                    the debug-build handle registry (ZKS_HANDLE_REGISTRY, 05 §15.5)
      zks_async.c                   every zoo_a* entry point, the rc funnel, zoo_add_auth
      zks_multi.c                   zoo_amulti, the five zoo_*_op_init, op/ACL marshalling
      zks_sync.c                    THREADED only: the 23 sync wrappers, the heap sync record, the wait and the vector hand-over;
                                    the copy-out into caller buffers is a zk-ffi raw write (05 §13)
      zks_drain.c                   zks_drain, zks_drive, item dispatch, completion and watcher delivery
                                    with the legacy post-callback deallocation order, unwind cleanup (requeue)
      zks_log.c                     logLevel users, log_message, stream functions, Site emitter
      zks_env.c                     log_env, PACKAGE_STRING
      zks_errors.c                  zerror, zoo_version_str, is_unrecoverable
      zks_resolve.c                 resolve leaf (getaddrinfo), endpoint formatting, connected host
      zks_rng.c                     rng_* leaves (libc PRNG parity), zoo_deterministic_conn_order
      zks_threads.c                 THREADED only: thread creation, start latch, IO and dispatch loops,
                                    THREAD item execution (join/detach)
      zks_sasl_cyrus.c              SASL builds only, hardened: Cyrus engine glue (zks_sasl_start/
                                    step/dispose, zks_sasl_set_gen, password trampoline), zookeeper_init_sasl,
                                    leaked zoo_sasl_* internals
      zks_sasl_cb.c                 SASL builds only, hardened: zoo_sasl_make_basic_callbacks,
                                    zoo_sasl_make_password_callbacks and their callbacks
      zks_compat.c                  zoo_lock_auth/zoo_unlock_auth, close_zsock (static archive only)
      zks_layout_check.c            _Static_assert on every zkrs.h type
      zks_hashtable_stub.c          CMake only: member of the libhashtable.a archive (B3)
      analyzer-suppressions.txt     analyzer findings with their justifications (08 G-S6); not compiled
    fuzz/sasl_glue/                 libFuzzer harness for the SASL glue (C; clang only; never installed)
    fuzz/sasl_cb_block/             libFuzzer harness for the callback block (F13; clang only; never installed)
    abi/                            golden ABI data (generated from C only, plus recorded deltas)
      symbols.tsv absent.tsv static-symbols.tsv log-sites.tsv
      needed/<os>.txt  layout/<target>.expect  abixml/...  release-deltas/
      gen-exports.awk               export-list generator: .sym / .map / .exp / .def / .keep
                                    (on CMake for Windows, ZkExports.cmake; §5)
      prelink.sh                    flavor objects + libzk_ffi.a -> zookeeper_<flavor>_all.o + .lo
      tls-probe.sh                  the TLS decision and default trust locations for Maven builds (§13.7)
      README.md                     how the goldens are regenerated
    acinclude.m4                    gains ZK_RUST_CHECK, the core-selection check (§6.1)
    cmake/                          ZkRust.cmake ZkRustDetect.cmake ZkExports.cmake ZkHarden.cmake zookeeperConfig.cmake.in
    zookeeper_mt.pc.in zookeeper_st.pc.in   additive
    tests/                          legacy white-box suite (C core only)
    tests/public/  tests/conformance/        owned by 08
zookeeper-assembly/
  pom.xml                         profile rust-lib-package selects lib-package-rust.xml (§13.5)
  src/main/assembly/lib-package.xml       unchanged: the legacy-core lib package
  src/main/assembly/lib-package-rust.xml  the lib package with both cores, LICENSE-binary and the
                                          Rust workspace NOTICE (§13.5)
```

`.gitignore` gains `zookeeper-client/zookeeper-client-rust/crates/zk-jute/src/generated/` (as for `zookeeper-client-c/generated/`); the existing `target/` pattern (.gitignore:26) covers `target/`, `fuzz/target/` and `crates/zk-ffi-layout/target/`. Maven builds write under `zookeeper-client-rust/target/cargo/`; standalone autotools and CMake builds write under the build directory's `cargo/`. Other documents refer to shim files by the names above.

## 3. Crate graph, dependency rules and the callback-frame contract

### 3.1 Graph

```
 zookeeper.jute ──Rcc -l c──► generated/zookeeper.jute.{h,c} ────────────────────────────┐
       └──Rcc -l rust (generate-Rust-Jute, git-ignored)──► zk-jute                         │
                                       │  [forbid(unsafe); deps: none; std-only build.rs]   │
                                       ▲                                                   │
                                     zk-proto        [forbid(unsafe); deps: zk-jute, zeroize]
                                       ▲                                                   │
                                     zk-driver       [forbid(unsafe); deps: zk-proto, zk-jute, rustix;
                                     ▲     ▲          optional: tls + tls-ring | tls-aws-lc-rs |
                                     │     │          tls-aws-lc-rs-fips (rustls + provider + key crates),
                                     │     │          tokio (Unix only), std-thread]           │
              default-features=false,      features as needed                              │
              features = tls,tls-ring |    │                                               │
                    tls,tls-aws-lc-rs |    │                                               │
               tls,tls-aws-lc-rs-fips      │                                               │
                                     │     │                                               │
                         zk-ffi ─────┘     └───── zk-client  [forbid(unsafe); native API]  │
              [unsafe only in src/abi;               ▲     (never a dependency of zk-ffi)  │
               staticlib]                            │                                     │
                 │                               zk-recipes  [forbid(unsafe); deps:        │
                 │                                 zk-client only; native only]            │
                 │ zkrs_* (shim/zkrs.h)                                                    │
                 ▼                                                                         ▼
  shim/*.c + src/recordio.c + generated/zookeeper.jute.c + libzk_ffi.a ══ld -r══► zookeeper_<flavor>_all.o
                                                                    ══► libzookeeper_{st,mt}.{so.2,a,dylib}
```

### 3.2 Crates

| Crate dir | Package | crate-type | unsafe | Normal deps | Role |
|---|---|---|---|---|---|
| zk-jute | `apache-zookeeper-jute` | lib | `#![forbid(unsafe_code)]` (also in `build.rs`) | none; std-only build script that checks `src/generated/` exists | implementation crate |
| zk-proto | `apache-zookeeper-proto` | lib | forbid | zk-jute, zeroize (`default-features = false`, `features = ["alloc"]`, no derive) | implementation crate |
| zk-driver | `apache-zookeeper-driver` | lib | forbid (also in `build.rs`) | zk-proto, zk-jute, rustix; optional `tls`, `tls-ring`, `tls-aws-lc-rs`, `tls-aws-lc-rs-fips` (§3.7), tokio (Unix only), std-thread; std-only build script for the default trust locations (§6.1) | implementation crate |
| zk-client | `apache-zookeeper` | lib | forbid | zk-driver, zk-proto, zk-jute, bitflags, zeroize, rustix (`process`, `use-libc`; native `tls_env_allowed`, R6); optional per §10 | the native public API |
| zk-recipes | `apache-zookeeper-recipes` | lib | forbid | zk-client only (`apache-zookeeper`, `default-features = false`; the UUID of the Curator-compatible node prefixes comes from std `RandomState`, 04 §22.2) | native recipes (M5R) |
| zk-ffi | `zk-ffi` | `staticlib` only | `#![deny(unsafe_code)]` at the root; `#![allow(unsafe_code)]` on `mod abi` only; every unsafe operation other than the export attributes is in `abi::raw` | zk-driver (`default-features = false`; features per configure, below), zk-proto, zk-jute, libc (`malloc`/`calloc` in `abi::raw`; `free` only in `#[cfg(test)]` code), windows-sys (Windows: the `WSAPoll` fallback of §4.7 and the ANSI code-page conversion of TLS file paths, 06) | drop-in Rust core |
| zk-testkit, zk-fakeserver | — | lib/bin | forbid | workspace crates + proptest family; rustls for the fake server's TLS | test only |
| zk-xcheck | — | lib (tests) | allowed (FFI to C oracles) | `cc` (build) | test only; never a dependency of a shipped crate |
| zk-ffi-layout | — | tests only | allowed (ctest-generated layout tests) | `ctest` (build); the zk-ffi ABI modules `abi/types.rs` and `abi/legacy.rs` by `#[path]`, not a crate dependency | test only; never a dependency of a shipped crate |
| xtask | — | bin | forbid | `object`, `serde_json`, … | developer tool |

Every manifest in the workspace, and in the separate `fuzz/` and `crates/zk-ffi-layout` workspaces, sets `publish = false`. The native crates ship in the ASF source release only (§10). The package names above are the names that applications use in path dependencies.

zk-ffi is `staticlib` only, because `lto = "fat"` applies only to final artifacts. Nothing links it as an rlib. Its Rust tests are `#[cfg(test)]` unit tests inside the crate, including the C-side stand-ins in `src/abi/standin/`. The ctest-style layout checks, which need the Rust `repr(C)` definitions, are in the separate test crate `crates/zk-ffi-layout`, which includes the ABI modules by path and checks the layouts from the C headers: the `zkrs.h` types and the `abi/legacy.rs` mirrors of the public result structs that `abi::raw` writes. Inside the workspace, crates are renamed for brevity. The renames and the lockstep version pins live in the root `[workspace.dependencies]`, for example `zk-jute = { package = "apache-zookeeper-jute", path = "crates/zk-jute", version = "=3.10.0" }`, and member crates write `zk-jute.workspace = true`. The pins keep every crate at the one workspace version, and the release version rewrite therefore touches one manifest (§13.4).

Cargo features, with the same names in zk-ffi and zk-driver (the Maven compile step passes them as `--features tls,tls-${rust-tls-provider}`, §13.2):

```toml
# zk-ffi/Cargo.toml
[features]
default            = []
tls                = ["zk-driver/tls"]
tls-ring           = ["tls", "zk-driver/tls-ring"]
tls-aws-lc-rs      = ["tls", "zk-driver/tls-aws-lc-rs"]
tls-aws-lc-rs-fips = ["tls", "zk-driver/tls-aws-lc-rs-fips"]
```

The build systems pass either no TLS feature or `--features tls,tls-<provider>` (§4.8). zk-driver rejects `tls` without a provider, and any two providers together, with `compile_error!` (R3).

Tests of zk-jute and zk-proto that need `zk-testkit` (model tests, LegacyModel, ServerSim) live in `zk-testkit/tests/`, so zk-testkit is not a dev-dependency of either crate, which avoids the duplicate-crate pitfall of dev-dependency cycles. The recipe tests live in `zk-recipes/tests/` (08 §8.10), with zk-testkit as a dev-dependency of zk-recipes.

### 3.3 Dependency rules (CI-enforced)

- **R1. Downward edges only.** zk-jute ← zk-proto ← zk-driver ← {zk-client, zk-ffi}, and zk-client ← zk-recipes. Nothing depends on zk-ffi, and zk-ffi does not depend on zk-client. CI runs `cargo tree -p zk-ffi -e normal -i apache-zookeeper` and expects an empty result. The zk-ffi staticlib is always built with `-p zk-ffi` and never with `--workspace`, so features that zk-client enables on zk-driver (tokio, std-thread) cannot unify into it.
- **R2. Core crates.**
  - zk-jute has no third-party normal or build dependencies. It has one std-only, `forbid(unsafe_code)` build script, which only checks that the generated file exists and otherwise fails with the §8 message: "jute files are missing! Run `mvn -pl zookeeper-jute generate-sources` from the top level directory."
  - zk-proto's only third-party normal dependency is `zeroize` (`default-features = false`, `features = ["alloc"]`, no derive), which has no transitive dependencies. It holds secrets: the auth list, SASL tokens, and sensitive frames. zeroize contains `unsafe` (volatile writes and a compiler fence) and is part of the audited drop-in closure.
  - Dev-dependencies of zk-jute and zk-proto are limited to `proptest`, `proptest-state-machine` and `arbitrary`. `loom` is a dev-dependency of zk-driver only (`cfg(loom)` facade).
- **R3. zk-driver.**
  - Its only non-optional third-party dependency is `rustix` with features `std, net, event, pipe, use-libc`.
  - Sockets use `rustix::net` (`socket_with`, non-blocking `connect`, `sockopt::socket_error` for SO_ERROR, `sockopt::set_tcp_nodelay`, `send` with `SendFlags::NOSIGNAL`, the SO_NOSIGPIPE setter on Apple, `getsockname`/`getpeername` for the SASL START addresses). A `getsockname` or `getpeername` failure enters zk-proto through `handle_sasl_addr_failed(gen: u32, which: SaslAddr{Local, Peer}, now)`, which emits the core log site "getsockname" or "getpeername" (02). Readiness goes through the zk-driver `Poll` trait, whose default implementation `RustixPoll` calls `rustix::event::poll` (§4.7 for the Windows fallback; 03 §8.8). The mt waker uses `rustix::pipe`. socket2, mio and polling are not used by the C-facing drivers.
  - Feature `tls` holds the TLS module (§4.8). It needs exactly one provider feature: `tls-ring` (rustls `ring` provider), `tls-aws-lc-rs` (rustls `aws-lc-rs` provider, `aws-lc-sys` underneath) or `tls-aws-lc-rs-fips` (rustls `fips` with the aws-lc-rs `fips` feature, `aws-lc-fips-sys` underneath; §4.10). Each provider feature implies `tls`. The RustCrypto key-decryption crates and features (pkcs8 `encryption`; pkcs5 `pbes2`, `3des` and `sha1-insecure`; `pbkdf2`, `scrypt`, `hmac`, `sha1`, `aes`, `cbc`, `cipher`, `des`, `md-5`) and `legacy_pem.rs` are enabled by `tls-ring` and `tls-aws-lc-rs` only. In the FIPS build zk-driver enables `pkcs8` with only its `pkcs5` feature (parsing of `EncryptedPrivateKeyInfo` parameter types, no `encryption`, no pkcs5 `pbes2`), so pkcs5 `pbes2` and the crates `pbkdf2`, `scrypt`, `hmac`, `sha1`, `aes`, `cbc`, `cipher`, `des` and `md-5` are not in the FIPS closure, and `legacy_pem.rs` is not compiled. Key derivation and decryption use `aws_lc_rs::pbkdf2` and `aws_lc_rs::cipher`, so PBKDF2 and AES-CBC run in the FIPS module (06 §3.15.3). With `tls` and no provider, or with two providers on, the crate fails with a `compile_error!` that names the features:

    ```rust
    // zk-driver/src/net/tls/mod.rs
    // A shipped build has exactly one crypto provider.
    #[cfg(not(any(feature = "tls-ring", feature = "tls-aws-lc-rs",
                  feature = "tls-aws-lc-rs-fips")))]
    compile_error!("feature `tls` needs one of `tls-ring`, `tls-aws-lc-rs`, `tls-aws-lc-rs-fips`");
    #[cfg(any(all(feature = "tls-ring", feature = "tls-aws-lc-rs"),
              all(feature = "tls-ring", feature = "tls-aws-lc-rs-fips"),
              all(feature = "tls-aws-lc-rs", feature = "tls-aws-lc-rs-fips")))]
    compile_error!("enable only one of `tls-ring`, `tls-aws-lc-rs`, `tls-aws-lc-rs-fips`");
    ```

    Because two providers together do not compile, no job builds zk-driver or zk-ffi with `--all-features`. From M3, lint and test jobs use `--features tls,tls-ring` on every CI target, and on Linux x86_64 and aarch64 also `--features tls,tls-aws-lc-rs` and `--features tls,tls-aws-lc-rs-fips` (08 §14 `tls-aws-lc`, `tls-fips`). The closure goldens prove that shipped builds enable exactly one provider.
  - `tokio` is optional behind feature `tokio`, which zk-ffi never enables. The tokio dependency is declared under `[target.'cfg(unix)'.dependencies]`, and the tokio driver is `#[cfg(all(unix, feature = "tokio"))]`, so on Windows the feature enables nothing and the native profile uses the threaded driver (04).
  - zk-driver does not use `tracing`. It emits structured records that zk-client turns into `tracing` or `log` events and zk-ffi turns into legacy log lines through the outbox.
- **R4. Golden closures.** CI runs `cargo tree -p zk-ffi -e normal,build --target <t> --features <f> --prefix none --format '{p}' --no-dedupe`, keeps the package name and version, sorts, removes duplicates, and diffs against the golden for that configuration, which records one `name version` line per crate. A pinned build compares exact versions. The system-crates mode (§3.11) compares the names and requires each version to be semver-compatible with the golden's (the caret rule). Expected content:

  | Configuration | Golden | Closure |
  |---|---|---|
  | TLS off, Linux | `zk-ffi-<target>-notls.txt` | `rustix, libc, errno, bitflags, linux-raw-sys, zeroize` |
  | TLS off, macOS, FreeBSD | `zk-ffi-<target>-notls.txt` | the same minus `linux-raw-sys` |
  | TLS off, Windows | `zk-ffi-<target>-notls.txt` | `rustix, libc, errno, bitflags, windows-sys` (plus the `windows-targets`/`windows_*` import-library crates it pulls), `zeroize` |
  | TLS on, `ring` (default; `--features tls,tls-ring`) | `zk-ffi-<target>.txt` | the TLS-off set for the target, plus `rustls`, `rustls-pki-types`, `rustls-webpki`, `untrusted`, `ring`, `pkcs8`, `pkcs5`, `der`, `spki`, `pem-rfc7468`, `pbkdf2`, `scrypt`, `hmac`, `sha1`, `sha2`, `aes`, `des`, `cbc`, `cipher`, `md-5`, `base64ct`, and their transitive closure as Cargo.lock resolves it (for example `subtle`, `digest`, `crypto-common`, `block-buffer`, `inout`, `cpufeatures`, `const-oid`, `salsa20`, `cfg-if`, `getrandom`, `typenum`/`generic-array`); build-only: `cc` and its helpers (ring's build script) |
  | TLS on, `aws-lc-rs` (`--features tls,tls-aws-lc-rs`) | `zk-ffi-<target>-aws-lc-rs.txt` | as the `ring` row with `ring` replaced by `aws-lc-rs` and `aws-lc-sys`, plus their build-only dependencies |
  | TLS on, `aws-lc-rs-fips` (`--features tls,tls-aws-lc-rs-fips`), Linux x86_64 and aarch64 only | `zk-ffi-<target>-aws-lc-rs-fips.txt` | as the `aws-lc-rs` row with `aws-lc-fips-sys` (the FIPS module) and its build-only dependencies in place of `aws-lc-sys`, and without `pbkdf2`, `scrypt`, `hmac`, `sha1`, `aes`, `cbc`, `cipher`, `des` and `md-5` (R3). The golden pins the exact set that the rustls `fips` and aws-lc-rs `fips` features resolve to. |

  The `aws-lc-rs` row has a golden and CI job for x86_64-unknown-linux-gnu and aarch64-unknown-linux-gnu only (08 §10 G1, job `tls-aws-lc`); other targets build without a golden.

  The exact TLS-on sets are generated at M3 from the committed `Cargo.lock` and frozen. Any change is a golden update committed with the change that causes it. Further rules for every drop-in closure:
  - no proc-macro crates (der's `derive` feature stays off, zeroize has no derive);
  - the only build scripts that compile C or assembly are the crypto provider's (`ring`, `aws-lc-sys` or `aws-lc-fips-sys`); every other build script, zk-jute's and zk-driver's included, is Rust-only;
  - every input file is read by the in-tree PEM reader `zk-driver/src/net/tls/pem.rs` (RFC 1421 headers, base64 through `base64ct`), not by `rustls-pemfile` or the pki-types PEM API, because the legacy key formats need the `Proc-Type`/`DEK-Info` headers. `pem-rfc7468` is in the closure as a dependency of the RustCrypto key crates;
  - no `log`, `tracing`, `webpki-roots`, `rustls-native-certs`, `rustls-platform-verifier`, `rustls-pemfile`, `openssl-probe`, `openssl`, `openssl-sys` or `native-tls`.

  The same check runs for the supported native feature sets of zk-client and zk-recipes against `native-<featureset>.txt`: the default set, each TLS provider, and `tls-platform-verifier`. Every document's dependency choices must fit one of these closures.
- **R5. libc backend.** rustix must use `use-libc`, so every socket, poll and pipe call goes through libc and stays interposable (LD_PRELOAD shims, sanitizers, libc-level seccomp). The legacy client also calls libc for each of these. The `linux_raw` backend is rejected. TLS records travel over the same rustix socket, so TLS adds no syscall path of its own. Certificate and key files are read with `std::fs`, which also calls libc.
- **R6. Memory and std side channels in the drop-in.**
  - The Rust allocator never allocates or frees application-visible memory (05 §1, rule 1). The legacy C result layouts that the application or C frees are allocated by `abi::raw` with libc `malloc`/`calloc`, with legacy's allocation choice and size for each object. `abi::raw` only allocates: a NULL result for a non-zero size aborts (05 GLB-04), and C frees every application-visible object (05 §1, rule 1). Within one library this is the same C runtime that the shim uses, so the application's `free()`, the `deallocate_*` functions and the generated jute deallocators release them unchanged.
  - On Unix, Rust's `System` allocator calls `malloc`/`free`, so allocator interposition (jemalloc or tcmalloc via LD_PRELOAD) sees Rust heap traffic, as ASan does. rustls and the RustCrypto crates allocate through the same allocator.
  - On Windows, `System` uses `HeapAlloc(GetProcessHeap())`. That heap is never shared with the application, because no pointer from the Rust allocator is ever freed by C or by the application. Application-visible memory comes from the CRT `malloc`/`calloc` that `abi::raw` calls, as above.
  - There is no `#[global_allocator]` anywhere.
  - clippy `disallowed-methods` (zk-proto, zk-driver, zk-ffi) bans `std::env::*`, `std::thread::spawn`/`Builder::spawn` (except in `zk-driver/src/spawn_std.rs`, which only the native API uses), `std::thread::current`, `std::process::exit`, `std::panic::set_hook`, `std::io::{stdout,stderr}`, `rustls::crypto::CryptoProvider::install_default` and `get_default`, and `zk_driver::sync::Unlocked::new_at_entry` outside `with_handle`, zk-ffi's thread-step entries and zk-driver's own entry points (05 §7). openssl-probe is not a dependency of any drop-in or default native closure, and cargo-deny admits it only with `wrappers = ["rustls-native-certs"]`, which in turn is admitted only under the native-only `rustls-platform-verifier` (§3.7).
  - **Environment reads.** On non-failure paths, the drop-in's Rust code reads at most two environment variables, `SSL_CERT_FILE` and `SSL_CERT_DIR`, and only on TLS handles, once per TLS connection attempt or probe, in one function of `zk-driver/src/net/tls/trust.rs` that carries the only `#[allow(clippy::disallowed_methods)]` for an environment read. It reads them **only when `zkrs_init_args.tls_env_allowed` is set**. The shim computes that flag once at init (`getauxval(AT_SECURE) == 0` on Linux, `!issetugid()` on BSD and Apple, true on Windows), because OpenSSL ignores these variables in set-uid and set-gid processes (`ossl_safe_getenv`). This is parity: legacy `SSL_CTX_set_default_verify_paths` reads the same two variables on every attempt.
  - **Native `tls_env_allowed`.** zk-client computes the same flag for native TLS handles in safe Rust with rustix: `getuid() == geteuid() && getgid() == getegid()` on Unix, and true on Windows. The shim's computation above serves the drop-in only.
  - **Default locations.** When the variables are not read (`tls_env_allowed` false) or not set, trust.rs uses the compile-time locations `ZK_TLS_DEFAULT_CERT_FILE` and `ZK_TLS_DEFAULT_CERT_DIR` (read with `option_env!`). The build sets them from `--with-rust-tls-default-paths=FILE:DIR` (CMake `-DZK_RUST_TLS_DEFAULT_PATHS`), whose default is the OPENSSLDIR that configure records (`openssl version -d` from `DIR/bin/openssl` for `--with-openssl=DIR`, from `${OPENSSL_ROOT_DIR}/bin/openssl` in CMake when `OPENSSL_ROOT_DIR` is set, or from the first `openssl` on `PATH` when no installation is named, §4.8; when the selected binary does not exist, the platform list of §6.1). 06 §3.4 owns the loader rules. For a native cargo build with both variables unset, zk-driver's `build.rs` applies the same §6.1 list on the build host. Cross builds set the variables explicitly. An empty compile-time value means no default location of that kind. When a variable is set and read, its value is the only source for that kind (file or directory), with no fallback, as OpenSSL does. `openssl_probe::probe()` is never called: there is no run-time search of candidate locations, and the library never calls `setenv`.
  - The panic path also reads `RUST_BACKTRACE` (DV-BLD-5).
- **R7. No thread-local with `Drop`** in code reachable from application threads. This avoids `__cxa_thread_atexit` registrations that pin the DSO. The closure audit checks the third-party crates for `thread_local!` with `Drop` types; rustls, ring and the RustCrypto crates use none on the paths the drop-in calls.
- **R8. No `extern { static … }` in zk-ffi.** The exported `logLevel` and the shim-private conn-order flag are read only through C leaf functions (`log_enabled(level)`, `conn_permute_disabled()`). A Rust read of a C global that the application writes would be a Rust-level data race, and LTO could make it worse. Shim-private globals use C11 `_Atomic` with relaxed access (an Interlocked wrapper on MSVC). The SASL password mutex (§4.9) is the one shim-private global that is not an atomic.
- **R9. Callback-frame contract.** See §3.6. zk-ffi calls only the host-vtable leaves listed there.
- **R10. No process-global mutable state in our Rust crates.** xids, the read-only probe target and the RNG are per handle (02 §1, invariant 1). Process-global legacy effects (libc PRNG reseeding, the conn-order flag) live in C and are reached through the vtable. rustls is given its provider explicitly per `ClientConfig` (`ClientConfig::builder_with_provider`), never through the process-default provider, so the library cannot change the provider that other Rust code in the process sees. Third-party caches that are written once and then read only (ring's CPU-feature detection, getrandom's backend selection) are accepted.

### 3.4 Lint configuration

```toml
# zookeeper-client-rust/Cargo.toml
[workspace]
resolver = "3"
members  = ["crates/zk-jute", "crates/zk-proto", "crates/zk-driver",
            "crates/zk-client", "crates/zk-recipes", "crates/zk-ffi", "crates/zk-testkit",
            "crates/zk-fakeserver", "crates/zk-xcheck", "xtask"]
default-members = ["crates/zk-jute", "crates/zk-proto", "crates/zk-driver", "crates/zk-client",
                   "crates/zk-recipes"]
exclude  = ["fuzz", "crates/zk-ffi-layout"]

[workspace.package]
version      = "3.10.0"            # == ZOO_VERSION; set by replace-rustclient-files-during-release (§9, §13.4)
edition      = "2024"
rust-version = "1.85"
license      = "Apache-2.0"
repository   = "https://github.com/apache/zookeeper"
publish      = false               # source release only (§10); members write publish.workspace = true

[workspace.lints.rust]
unsafe_code            = "forbid"
unsafe_op_in_unsafe_fn = "deny"
missing_docs           = "warn"
unreachable_pub        = "warn"
rust_2018_idioms       = { level = "deny", priority = -1 }

[workspace.lints.clippy]
unwrap_used = "deny"
expect_used = "deny"
panic = "deny"
indexing_slicing = "deny"          # decode paths must not panic on server input
arithmetic_side_effects = "deny"   # use checked_ or wrapping_ arithmetic
as_conversions = "warn"
dbg_macro = "deny"
print_stdout = "deny"
print_stderr = "deny"
large_stack_arrays = "deny"
large_stack_frames = "deny"        # threshold in clippy.toml (03 §8.7)

[profile.release]
debug = "line-tables-only"

[profile.capi]                      # libzookeeper_{st,mt}
inherits = "release"
panic = "abort"
lto = "fat"
codegen-units = 1
opt-level = 2                       # same as the C default -O2
overflow-checks = false
debug-assertions = false

[profile.capi-dev]                  # --enable-debug / a CMake Debug configuration
inherits = "dev"
panic = "abort"
```

- Every crate except zk-ffi, zk-ffi-layout and zk-xcheck uses `[lints] workspace = true`. Each `lib.rs` (and any `build.rs`, zk-jute's, zk-driver's and zk-ffi's included) also starts with `#![forbid(unsafe_code)]`, so the source survives a manifest edit and cannot be overridden by an inner `allow`. Test crates may relax the clippy panic lints in `#[cfg(test)]` code.
- zk-client has exactly two scoped `#[allow(clippy::panic)]`: `ZPath::from_static` (the const-evaluated literal check) and `MultiResults::get` (a handle from another `MultiWriter`); xtask checks that no other allow exists in zk-client or zk-recipes (08 G9).
- zk-ffi has its own `[lints]` table:
  - `unsafe_code = "deny"`, with a module allow on `abi` only;
  - `unsafe_op_in_unsafe_fn`, `clippy::undocumented_unsafe_blocks`, `clippy::multiple_unsafe_ops_per_block`, `clippy::missing_safety_doc`, `clippy::ptr_as_ptr`, `clippy::large_stack_arrays` and `clippy::large_stack_frames` are all deny.

  Every C function pointer is typed `unsafe extern "C" fn`. C pointers that Rust stores (the `zhandle_t` back-pointer, `zks_req *` completion tokens, watcher `fn`/`ctx` pairs) are held as `usize` or `NonZeroUsize`, never as raw pointers. They are therefore `Send` without an `unsafe impl`. Rust passes them back to C leaves as integers (`uintptr_t`), never converts them to pointers and never dereferences them. Rust holds no Cyrus or TLS pointer: Cyrus state is a C per-handle session keyed by a generation number, and TLS state is plain Rust.
- `clippy.toml` sets `stack-size-threshold = 8192`, the 8 KiB stack budget of 03 §8.7 that `large_stack_frames` enforces.
- Under edition 2024, `#[unsafe(no_mangle)]` trips `unsafe_code`. That is why exported functions can live only in zk-ffi.
- clippy runs per configuration with explicit features, never with `--all-features` (R3). The `rust-lint` profile and the `rust-check` job run `-p zk-ffi` with no features and the workspace defaults, and from M3 also `-p zk-ffi --features tls,tls-ring`. `-p zk-ffi --features tls,tls-aws-lc-rs` runs only in the CI job `tls-aws-lc` and `-p zk-ffi --features tls,tls-aws-lc-rs-fips` only in `tls-fips` (08 §14), on images with those providers' build tools (§3.9, §4.10).

### 3.5 Platform adapters: Rust traits, C implementations

| Concern | Trait (zk-driver / zk-proto) | Drop-in implementation | Native implementation | How it is reached in the drop-in |
|---|---|---|---|---|
| Name resolution | `HostLeaves::resolve` (zk-driver, 03 §5.1) | `zks_resolve.c`: `getaddrinfo(AI_ADDRCONFIG, AF_UNSPEC, SOCK_STREAM, IPPROTO_TCP)` with the legacy retry. Errors (`gai`, `errno`) and skipped address families are returned as data. | std `ToSocketAddrs` behind zk-client's `Resolve` trait (inline in the thread driver, `spawn_blocking` in the tokio driver; 03 §8.5) | **Blocking** vtable leaf `resolve`, called with no Rust lock held (`&Unlocked` proof). Rust emits the legacy `getaddrinfo: %s` site through the outbox with a `GAI_STR` argument. |
| Host-shuffle RNG | `zk_proto::Rng{on_resolution, random, drand48}` | `zks_rng.c`: `srandom`/`srand48` reseed from `/dev/urandom` per resolution, `random()`, `drand48()`; on Windows no reseed, `rand()`, `(double)rand()/RAND_MAX` (07 SRV-04) | per-handle SplitMix64 in zk-client | `rng_reseed` is a Blocking leaf; `rng_random` and `rng_drand48` are Pure leaves |
| TLS | `TlsConnector`/`TlsConn` (03 §8.2.1 owns the trait signatures) | `RustlsConn` in `zk-driver/src/net/tls/` with `Compat::tls_verify = LegacyChainOnly` | the same `RustlsConn` with `WebPki`, or with the OS verifier of the opt-in `tls-platform-verifier` feature (04) | Plain Rust inside the driver, over the socket the driver owns. No vtable entry, no C code. |
| SASL | `SaslNegotiation` in zk-proto (quirks, framing, gating) | `zks_sasl_cyrus.c` (Cyrus) | DIGEST-MD5 in safe Rust; GSSAPI via `sasl-gssapi` | **Outbox items** `SASL{START, STEP, DISPOSE}`, because Cyrus callbacks are application code. C runs the step and returns the token with `zkrs_sasl_result(core, gen, view)`. |
| Threads (mt) | none in the drop-in | `zks_threads.c`: `pthread_create(&t, NULL, …)` / `_beginthreadex(NULL, 0, …)` (as winport.c:55). Join and detach run from THREAD outbox items. The thread role is C TLS (`zks_current_role`). | `zk-driver::spawn_std` | C creates the threads, owns their loops and joins or detaches them (§3.6) |
| Logging | outbox `LOG{site, level, time, args}` | `zks_log.c` formats with the pinned Site table and re-checks `logLevel` at emit time | `tracing` (default) or `log` in zk-client | Outbox item. Rust asks the Pure `log_enabled(level)` before it formats arguments. |
| Clock | `Clock` | std `Instant` (libc `clock_gettime`) for timers; zk-ffi stamps each LOG item with `SystemTime::now` (the `gettimeofday` clock) when it moves the record into the outbox | same | zk-proto stays clock-free |

### 3.6 Callback-frame contract (normative)

**CF1. Invariant.** No application code runs while a Rust frame is on the stack, on any thread. Application code means:
- completion functions and watchers (including the default watcher);
- `zoo_add_auth` completions;
- log callbacks and writes to the application's log `FILE*`;
- Cyrus callbacks, including `getpass` and application password callbacks reached through the shim's password trampoline.

libc functions, including interposed ones, are not application callbacks.

**CF2. Outbox.** The outbox is a small handle on the C stack (`zkrs_outbox ob = ZKRS_OUTBOX_INIT;`) that Rust fills. Every `zkrs_*` entry that can produce application-reaching work takes `zkrs_outbox *`. C reads it with `zkrs_outbox_next(&ob, &item)`, which returns borrowed views that stay valid until the next call on that outbox. C copies anything it hands to the application. `zkrs_outbox_finish` frees an empty outbox and resets the handle to its initial empty state, so the same handle is passed to the next `zkrs_*` call, and `zkrs_outbox_requeue` returns undelivered items to the handle on the unwind path. Item kinds (05 §5 owns the exact `zkrs.h` names):

| Item | Content | C action in `zks_drain` |
|---|---|---|
| `LOG` | site id, level, wall-clock stamp, typed args | re-check `logLevel`; format with the Site table; callback or stream |
| `RESULT` | completion token (`zks_req *`) and a `zkrs_result_view` in outbox storage (05 §10). The `zks_req` mode tells async, sync and auth apart. | async: `zks_deliver_result` calls the stateless abi::raw writer `zkrs_write_result` (for a multi, `zkrs_write_multi` into the application's `zoo_op_result_t` array; 05 §10.1, §12.3), which builds the legacy C objects with libc `malloc`/`calloc` and legacy sizes; C then calls the completion and frees in the legacy post-callback order (`zks_drain.c`). sync: `zks_sync_complete` calls `zkrs_write_result` to build the result into the heap `zks_sync` record and signals the waiter; the waiter wakes and copies out through `zkrs_sync_copy_out` (`buffer_len` truncation, `path_buffer` NUL rules; 05 §13). auth: call the completion. |
| `WATCH` | node or session event with the path as a NUL-terminated `zkrs_str` view in outbox storage, and the ordered, deduplicated `(fn, ctx)` list | `zks_deliver_watch` gets a `malloc(len + 1)` copy of the path from `zkrs_write_path` (abi::raw), calls each watcher with it, and frees it after the last watcher returns (05 §11) |
| `AUTH_LOCKPOINT` | — | `lock; unlock` on `zh->auth_lock` (default mutex attributes: `pthread_mutex_init(&zh->auth_lock, 0)`, as legacy `pthread_mutex_init(&zh->auth_h.lock,0)` at mt_adaptor.c:253) with no Rust frame and no Rust lock; a no-op in st |
| `SASL{START, STEP, DISPOSE}` | generation; START: the local and peer addresses only, as `zkrs_bytes` that carry the platform `sockaddr_in`/`sockaddr_in6` image written by zk-ffi (05 §19.2 owns `zkrs.h`); STEP: the server token as a bounded view (`is_null` for -1). The drain takes the params from the shim session's own copy, not from the item (CC-31 (00 §4)). | `zks_sasl_start/step/dispose` with the item's `gen` (§4.9); the glue rejects a stale `gen` itself (status STALE, no Cyrus call); after a START that is not STALE the drain calls `zks_sasl_set_gen`; after a START or STEP that is not STALE it calls `zkrs_sasl_result(core, gen, view)`, and after a STALE one it makes no result call |
| `THREAD{JOIN\|DETACH, IO\|DISPATCH}` | — | `pthread_join` of the named thread, or `pthread_detach(pthread_self())` (Windows equivalents under `_WIN32`) |

**CF3. Barriers.** Rust returns `ZKRS_MORE` immediately after these items: each `AUTH_LOCKPOINT`, each auth completion group, each run of sync `RESULT`s (Direct completions) in the mt IO step and in close, each SASL `START` and `STEP` item, each st session event and `DrainPoint`, and every `THREAD` item. A run is a sequence of consecutive Direct results with no callback item between them; it shares one barrier, which keeps the order, because nothing observable runs between them (02 and 05 specify it). A step also stops at a barrier before each Blocking vtable call (`resolve`, `rng_reseed`), in init, `zoo_set_servers`, the mt IO step and the st `zookeeper_interest`/`zookeeper_process` calls (05 §5), and before the IO step's TLS file reads, when LOG items are pending (05 §5): C drains them, so the log callback runs from a C frame, and `zkrs_resume` then continues into the blocking call. That costs one extra round trip, only on those paths and only when logs are pending. With SASL params, init also stops at the SASL point after resolution and the reseed and before the waker, so C creates the SASL session in the legacy order (05 §15.1). SASL `DISPOSE` is ordered by the outbox but is not a barrier. Its timing is legacy's (02 owns it): on a reconnect it comes immediately before the next `START` in the same outbox, as `zoo_sasl_connect` disposes the old Cyrus connection first (zk_sasl.c:215-217), and at destroy it comes after the transport is closed (zookeeper.c:719-749). A teardown alone emits none. C drains and calls `zkrs_resume(core, &ob, &rc)` (`zkrs_interest_resume(core, &o, &written, &ob, &rc)` in the st `zookeeper_interest`, 05 §14) until it gets `ZKRS_DONE`, `ZKRS_RELEASE` or `ZKRS_EXIT`. Rust runs the suspended continuation (proto barrier/resume) only inside the next call. This reproduces legacy orderings:
- a sync waiter is signalled before any later reply is processed;
- an auth callback runs before the AUTH_FAILED teardown continues, and a close made inside that callback is seen by the continuation;
- ZCLOSING auth completions are delivered before the close joins a library thread.

**CF4. Exception-safe consumption.** `zks_drain` holds a cleanup handler (`__attribute__((cleanup))`; `__try/__finally` on MSVC). If an application callback throws, the handler calls `zkrs_outbox_requeue`, and the handle delivers the untaken items at the start of the next driving call on that handle. The next driving call on that handle (`zkrs_interest`, `zkrs_process`, `zkrs_close`, `zkrs_io_step`) first delivers those items and completes the pending resume; `zkrs_submit` and `zkrs_add_auth` never do (05 §5). The legacy client also leaves the remaining queue entries queued in this case. `longjmp` out of a callback skips the handler; the handle state stays consistent because no Rust frame is on the stack, and the outbox storage leaks, as legacy's does.

**CF5. No blocking with pending output.** A `zkrs_*` call that would block in poll, in the close flush or close wait, or in the RW probe first returns if LOG items are pending in its outbox. Log lines and callbacks therefore come out on the same thread, in the same order, and before any wait, as in legacy.

**CF6. Library thread loops are C.** The step result is `zkrs_step {ZKRS_DONE, ZKRS_MORE, ZKRS_RELEASE, ZKRS_EXIT}`.

```c
/* shim/zks_threads.c (THREADED) */
static void *zks_io_main(void *arg)
{
    zhandle_t *zh = arg; const zkrs_handle *core = zh->core;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT; int rc = 0; zkrs_step s;
    zks_tls_zh = zh; zks_tls_role = ZKRS_ROLE_IO;   /* C TLS; set before the latch */
    ZKS_CALL(zkrs_thread_enter(core, ZKRS_ROLE_IO));
    zks_latch_arrive_and_wait(&zh->start_latch);
    do {
        s = ZKS_CALL(zkrs_io_step(core, &ob, &rc)); /* one interest/poll/process */
        while (s == ZKRS_MORE) {                    /* barrier: drain, then resume */
            zks_drain(zh, &ob);
            s = ZKS_CALL(zkrs_resume(core, &ob, &rc));
        }
        zks_drain(zh, &ob);
    } while (s != ZKRS_EXIT);
    s = ZKS_CALL(zkrs_thread_exit(core, ZKRS_ROLE_IO, &ob, &rc));
    zks_drive(zh, core, s, &ob, &rc);               /* can run the final close here */
    ZKS_CALL(zkrs_handle_release(core));            /* this thread's reference */
    return NULL;
}
/* 05 §15.2 defines the full loop, including the log callback captured
   before zkrs_thread_exit and the thread-terminated log line. */
/* zks_dispatch_main has the same shape with zkrs_wait_dispatch, which
   returns ZKRS_DONE with a batch, or ZKRS_EXIT. */
```

- `zkrs_io_step` performs one `do_io` iteration in Rust: interest, then `poll([waker, sock])` with no lock and no application code, then process (send, receive, TLS records, timers, resolve through the Blocking leaf). It returns at a barrier, before it would block with LOG items pending (CF5), or with `ZKRS_EXIT`.
- `zkrs_wait_dispatch` blocks on the dispatch FIFO and returns a batch in proto stream order.
- Sync-before-FIFO order: the IO step pumps queued completions and events into its outbox as publish entries, in proto order. When zkrs_outbox_next reaches one, it appends it to the dispatch FIFO under the FIFO leaf lock, after C has emitted the preceding LOG items (03 §3.4). A run of sync RESULTs ends at a barrier (CF3), so the IO thread signals each waiter before Rust processes the next frame.
- **Start.** Start uses a three-party latch (IO, dispatch and init thread). `zookeeper_init` stores `zh->core` before the first `pthread_create`, and that call is the happens-before edge. Callbacks can run before init returns, as in legacy. A `pthread_create` failure makes `zookeeper_init` return NULL with the errno from `pthread_create` (07 INIT-12).
- **Close.** `zookeeper_close` calls `zkrs_close(core, zks_current_role(zh), &ob, &rc)` and drives it with `zks_drive`. The role comes from C TLS. From a foreign thread, close joins both library threads; from the dispatch thread, it joins IO and detaches itself; from the IO thread, it detaches itself and joins dispatch. Each join or detach is a `THREAD` item that C executes. The final close runs on whichever thread drops the last reference and ends with `ZKRS_RELEASE`. A close while another close is in progress is a ZOK no-op.

**CF7. Host vtable (the only C functions Rust may call).** Every entry is Pure (non-blocking, no application code, callable with Rust locks held) or Blocking (may block or do I/O, no application code, callable only with the `&Unlocked` proof):
- Pure: `discard_token`, `snapshot_default_watcher`, `store_client_id`, `log_enabled`, `conn_permute_disabled`, `rng_random`, `rng_drand48`;
- Blocking: `rng_reseed`, `resolve`.

None of them calls application code or `log_message`. There are no TLS entries, no SASL entries, no delivery or log-emit entries, no `auth_lock`/`auth_unlock`, and no spawn, join or detach entries.

**CF8. Tokens.**
- A completion token is a `NonZeroUsize` holding a `zks_req *`. It is consumed exactly once, either by a `RESULT` item or by the Pure `discard_token`.
- The Rust wrapper's `Drop` calls `discard_token`. For a sync record, `discard_token` stores ZCLOSING and signals the waiter. This is legal until host release, and Rust aborts if it happens afterwards. So a driver that drops queued items (mt destroy, close from the IO thread) stays well defined, and each dropped token is disposed of by its own `Drop`.
- `zoo_add_auth` keeps its token iff `zkrs_add_auth` returns `ZKRS_OK`.

**CF9. Exception and panic behavior.**
- An exception from a callback on an application thread (st inline dispatch, a log line from a `zoo_a*` call, ZCLOSING auth completions in close, init-time logging) propagates to the application through C frames only. That is legacy behavior.
- On a library thread it reaches the C start routine and terminates the process, which is also legacy behavior.
- The shim is compiled with `-fexceptions` where the compiler accepts it (otherwise `-funwind-tables`), so its frames carry unwind tables and run cleanup handlers during C++ unwinding.
- Rust panics abort (§4.2). No foreign unwind can reach a Rust frame, so there is no `C-unwind` anywhere.

**CF10. The native API does not use the outbox to reach user code.** The native driver loops are Rust, in zk-driver (03 §7). Native "callbacks" are slot fills, per-watcher queue pushes and waker wake-ups on the IO side, which never run user code under a library lock (04 §4, §5.2). The same zk-driver step functions serve both front ends.

### 3.7 Third-party dependency allowlist (single table)

| Crate | Closure | Feature | License | Justification |
|---|---|---|---|---|
| rustix (`std,net,event,pipe,use-libc`; zk-client adds `process`) | drop-in + native | always (zk-driver); `process` in zk-client only | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT | sockets, SO_ERROR, TCP_NODELAY, NOSIGNAL/NOSIGPIPE, getsockname/getpeername, poll, pipe; Unix and Windows. `process` gives zk-client `getuid`/`geteuid`/`getgid`/`getegid` for the native `tls_env_allowed` (R6). The drop-in never enables it, because zk-ffi is built with `-p zk-ffi` (R1). |
| libc, errno (renamed `libc_errno` inside rustix), bitflags, linux-raw-sys | drop-in + native | transitive; libc is also a direct dependency of zk-ffi | MIT OR Apache-2.0 (linux-raw-sys: + LLVM-exception) | libc gives `abi::raw` `malloc`/`calloc` for the legacy C result layouts, which C frees (R6) |
| windows-sys (+ windows-targets) | drop-in + native (Windows) | transitive; also a direct dependency of zk-ffi on Windows | MIT OR Apache-2.0 | zk-ffi: the `WSAPoll` fallback (§4.7) and `MultiByteToWideChar(CP_ACP)` for TLS file paths that are not UTF-8 (06) |
| zeroize (`default-features=false`, `alloc`) | drop-in + native | always (zk-proto); also used by rustls and the key crates | Apache-2.0 OR MIT | secret wiping (06 §7) |
| rustls (`default-features=false`; `std, tls12` + provider), rustls-pki-types (`std`), rustls-webpki, untrusted | drop-in (TLS on) + native | zk-driver `tls` | Apache-2.0 OR ISC OR MIT; webpki and untrusted ISC | TLS 1.2/1.3 engine and chain building. rustls's `logging` feature stays off. PEM is not parsed here (next row). |
| base64ct | drop-in (TLS on) + native | zk-driver `tls` | Apache-2.0 OR MIT | constant-time base64 for the in-tree PEM reader `net/tls/pem.rs` (RFC 1421 headers kept for `legacy_pem`) |
| sha1 | drop-in (TLS on) + native | zk-driver `tls-ring`, `tls-aws-lc-rs` | MIT OR Apache-2.0 | PBKDF2-HMAC-SHA1 in encrypted PKCS#8 (pkcs5 `sha1-insecure`); not in the FIPS build |
| ring 0.17 (+ getrandom, cfg-if; build: cc) | drop-in (TLS on, default) + native | zk-driver `tls-ring` | Apache-2.0 AND ISC | default crypto provider |
| aws-lc-rs + aws-lc-sys | drop-in and native, `--with-rust-tls-provider=aws-lc-rs` / zk-client `tls-aws-lc-rs` | zk-driver `tls-aws-lc-rs` | ISC AND (Apache-2.0 OR ISC); aws-lc-sys adds OpenSSL-licensed parts | provider build option; per-crate license exception in `deny.toml`. A release includes it only after the ASF legal review of aws-lc-sys passes (00 §6). |
| aws-lc-rs (`fips`) + aws-lc-fips-sys | drop-in and native, `--with-rust-tls-provider=aws-lc-rs-fips` / zk-client `tls-aws-lc-rs-fips`; Linux x86_64 and aarch64 | zk-driver `tls-aws-lc-rs-fips` | as aws-lc-sys | the FIPS build (§4.10); allowed only by `deny-fips.toml`. A release includes it only after the ASF legal review of aws-lc-fips-sys passes (00 §6). |
| pkcs8 (`pkcs5`; `encryption` with the non-FIPS providers), pkcs5 (`pbes2`, `3des` and `sha1-insecure` with the non-FIPS providers), der, spki, pem-rfc7468, sha2, and with the non-FIPS providers pbkdf2, scrypt, hmac, aes, cbc, cipher, des and md-5, and their transitive closure (digest, subtle, …) | drop-in (TLS on) + native | zk-driver `tls`; the decryption crates and features with `tls-ring` and `tls-aws-lc-rs` only (R3) | Apache-2.0 OR MIT (subtle: BSD-3-Clause) | encrypted PKCS#8 (PBES2: PBKDF2 with HMAC-SHA1, -SHA224, -SHA256, -SHA384 or -SHA512, or scrypt; AES-128/192/256-CBC or DES-EDE3-CBC) and traditional OpenSSL PEM keys (`legacy_pem`, EVP_BytesToKey with MD5). `tls-aws-lc-rs-fips` enables `pkcs8` with only its `pkcs5` feature (parsing of `EncryptedPrivateKeyInfo`, no `encryption`, no pkcs5 `pbes2`), and not `pbkdf2`, `scrypt`, `hmac`, `sha1`, `aes`, `cbc`, `cipher`, `des` or `md-5`; `legacy_pem.rs` is not compiled. Key derivation and decryption use `aws_lc_rs::pbkdf2` and `aws_lc_rs::cipher`, and the FIPS build accepts the subset of DV-TLS-6 (06 §3.15.3) |
| tokio (`rt,net,time,sync,io-util`) and its deps (mio, socket2, pin-project-lite) | native only, Unix only | `tokio` | MIT | async driver (`[target.'cfg(unix)'.dependencies]`, R3). cargo-deny `wrappers` allow mio and socket2 only under tokio |
| rustls-platform-verifier (+ rustls-native-certs and openssl-probe on Linux and the BSDs; security-framework and core-foundation on Apple; windows-sys on Windows) | native only | zk-client `tls-platform-verifier` (opt-in; needs `tls-ring` or `tls-aws-lc-rs`; not combinable with `tls-aws-lc-rs-fips`) | MIT OR Apache-2.0 | OS trust store and OS verification policy behind `TlsOptionsBuilder::with_platform_verifier()` (04). Never in the drop-in: cargo-deny `wrappers` admit it only under `apache-zookeeper`, admit `rustls-native-certs` only with `wrappers = ["rustls-platform-verifier"]`, and admit `openssl-probe` only with `wrappers = ["rustls-native-certs"]`. It has its own native closure golden. |
| tracing (+tracing-core) | native only | `tracing` (default) | MIT | 04 §17 |
| futures-core | native only | `stream` (default) | MIT OR Apache-2.0 | `Stream` impls |
| log | native only | `log` | MIT OR Apache-2.0 | render LogRecords through `log` |
| md-5 (+digest family), getrandom | native (`sasl`); also in the drop-in TLS closure (md-5 with the non-FIPS providers only) | `sasl` | MIT OR Apache-2.0 | DIGEST-MD5 |
| libgssapi (+libgssapi-sys) | native only | `sasl-gssapi` | MIT | links system libgssapi_krb5; outside the unsafe budget |
| serde | native only | `serde` | MIT OR Apache-2.0 | — |
| proptest, proptest-state-machine, arbitrary, loom, object, serde_json (xtask), ctest (zk-ffi-layout tests), cc (xcheck) | dev/test only | — | MIT / Apache-2.0 | never linked into shipped artifacts |

Native `tls-rustls` adds no crate beyond the drop-in TLS closure: `add_system_roots()` uses the shared OpenSSL-location loader in `trust.rs`, not `rustls-native-certs` (04 §16.1). Only the opt-in `tls-platform-verifier` adds crates, and none of them can reach the drop-in.

Policy details:

- `deny.toml` `[bans]` denies: `openssl`, `openssl-sys`, `native-tls`, `rustls-pemfile` (unmaintained, and it drops the RFC 1421 headers that the in-tree PEM reader needs) and `webpki-roots` (an embedded root store is not the OpenSSL trust input), everywhere; `rustls-native-certs` everywhere except with `wrappers = ["rustls-platform-verifier"]`, and `openssl-probe` everywhere except with `wrappers = ["rustls-native-certs"]` (the drop-in and `add_system_roots()` use the compiled-in OpenSSL locations, R6, 04 §16.1); `rustls-platform-verifier` except under `apache-zookeeper` (`wrappers`), which keeps it out of every zk-ffi closure (R1).
- `aws-lc-rs` is allowed only with `wrappers = ["rustls", "rustls-webpki", "apache-zookeeper-driver"]`, so it can enter only through zk-driver's `tls-aws-lc-rs` and `tls-aws-lc-rs-fips`. `aws-lc-sys` is allowed only with `wrappers = ["aws-lc-rs"]`, and `deny.toml` denies `aws-lc-fips-sys`. The FIPS runs use `deny-fips.toml`, which is `deny.toml` with that ban replaced by `wrappers = ["aws-lc-rs"]` for `aws-lc-fips-sys`, plus its per-crate license exception.
- `socket2`, `mio` and `polling` are denied except through the `tokio` wrapper.
- `deny.toml` carries a `clarify` entry for ring if its manifest license field and its LICENSE file disagree at the pinned version.
- Any `git` or non-crates.io source is denied.
- cbindgen (MPL-2.0) is a developer tool. It is not in Cargo.lock and is not needed to build.

### 3.8 Supply-chain policy

- **cargo-deny** runs once per explicit feature set of G1 (08 §10 G6), never with `--all-features`.
  - `licenses.allow = ["Apache-2.0", "Apache-2.0 WITH LLVM-exception", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Zlib", "Unicode-3.0", "CC0-1.0"]`, with `confidence-threshold = 0.93`.
  - CC0-1.0 is a public-domain dedication. The ASF third-party license policy (apache.org/legal/resolved.html) allows such works in Apache products, with attribution, so CC0-1.0 crates are allowed and are attributed in `LICENSE`/`NOTICE` and `LICENSE-binary` as needed (§3.10).
  - Any other license, including MPL and the OpenSSL license (aws-lc-sys, aws-lc-fips-sys), needs a per-crate exception in the cargo-deny configuration. The aws-lc exceptions are build-specific: `deny.toml` carries the aws-lc-sys exception, and only `deny-fips.toml` carries the aws-lc-fips-sys one (§3.7).
  - Advisories: vulnerabilities and yanked crates fail, unmaintained crates warn.
  - Sources: crates.io only. A source replacement (a `cargo vendor` directory, or the distribution crates of §3.11) keeps the crates.io identity in `Cargo.lock`, so it passes this rule.
  - `multiple-versions = "warn"`, `wildcards = "deny"`.
- **Security updates.** The CI job `advisories` runs `cargo audit` against the RustSec advisory database and `cargo deny check advisories` over every closure golden, on every PR and daily on a schedule (08 §10 G10, §14). The closure goldens list the crates of each shipped configuration, so they show which shipped closures an advisory affects. A fix reaches users in a ZooKeeper release with the bumped crates. Distributions can instead patch rustls or the provider in place and rebuild in the system-crates mode (§3.11), which is their fast path (00 §2.8).
- **cargo-vet:** `safe-to-deploy` for every crate in every shipped drop-in closure (no exemptions), and for the native closures by M5. Audits are imported from Mozilla, Google, the Bytecode Alliance and ISRG. A crate in the TLS closure that no imported audit covers is audited before M3 exits.
- **Unsafe accounting:**
  - `forbid` is the enforcement for our crates.
  - A grep gate (08 §10 G3) fails if `unsafe` appears in a `.rs` file of a shipped crate outside `crates/zk-ffi/src/abi/`; the test-only `zk-xcheck`, `zk-ffi-layout`, `fuzz` and the `#[cfg(test)]` stand-in modules under `crates/zk-ffi/src/abi/standin/` are allowlisted by name.
  - zk-ffi's unsafe block count is pinned in `unsafe-budget.toml`, with per-block invariants in `zk-ffi/UNSAFE.md`. The budget covers the raw writers of `abi::raw` that build the legacy C result layouts and perform the sync copy-out (05 §10.1, §13; `src/abi/raw/writers.rs`), as well as the views, the vtable calls, the out-parameter writes, the struct reads, the handle reference counting, the outbox and resolve-sink pointer conversions, the Windows `WSAPoll` fallback and the Windows ANSI path conversion (05 §8). Miri, the `zk-ffi-layout` checks of the `abi/legacy.rs` mirrors and a cargo-fuzz target over the reply-to-C-layout writers under ASan cover the raw writers (08). The TLS engine adds no block.
  - The crypto crates contain `unsafe`, C and assembly for constant-time primitives and CPU intrinsics. This is audited third-party code and is accepted; `cargo geiger` reports it for information only.
- **Miri** (08 §8.6 owns the policy): zk-jute, zk-proto and zk-testkit on every PR; zk-ffi (production modules with strict provenance; the `#[cfg(test)]` C-side stand-ins in a separate invocation, `cargo miri test -p zk-ffi --lib abi::standin` with `-Zmiri-permissive-provenance`) and zk-driver nightly and on PRs that touch them. zk-driver runs under Miri without the `tls` feature; the pure-Rust TLS input modules (trust.rs, pem.rs, x509.rs, identity.rs, legacy_pem.rs, and the path-building and rule checks of v1_leaf.rs with a test `SignatureVerificationAlgorithm` in place of the provider) run under Miri on their own, and the TLS session paths, which reach ring or aws-lc foreign code, are covered by ASan, TSan and F14-F16 instead (08 §8.6).
- **cargo-semver-checks** on `apache-zookeeper` and `apache-zookeeper-recipes`, against the source of the previous release tag: `cargo xtask semver` checks the tag out in a worktree, runs `mvn -pl zookeeper-jute generate-sources` there and passes it with `--baseline-root` (08 G8), because they carry the native API that applications build against.

### 3.9 MSRV, edition and toolchain

- **MSRV 1.85, edition 2024.** 1.85 is the first release with edition 2024, which requires `#[unsafe(no_mangle)]` and `unsafe extern` (stable since 1.82), and it matches Debian 13.
  - CI pins two toolchains, the MSRV and stable (08 §14). A CI job builds every library crate with exactly 1.85 and `--locked`, in each shipped feature configuration. Pinned versions of rustls, ring, aws-lc-rs and the RustCrypto crates must build with it.
  - Test-only tools are exempt (ctest needs 1.88). The ctest crate `crates/zk-ffi-layout` is therefore its own workspace with its own lockfile, like `fuzz/`, and builds on the stable toolchain only. The workspace-wide invocations of the `cargo-test` and `rust-lint` profiles (§13.3) do not compile it, so a 1.85 toolchain builds, tests and lints the workspace; CI runs its checks in the stable job (08 §14).
  - The one MSRV value is carried by the MSRV CI job, the configure and CMake version gates (§6.1, §6.3), the Maven version check (§13.2) and DV-BLD-4, so a change of the MSRV changes all of them together.
  - rustix 1.x fits within it.
- No committed `rust-toolchain.toml`. The only committed cargo configuration is `zookeeper-client-rust/.cargo/config.toml`, which holds exactly `[alias] xtask = "run --locked --package xtask --"` and no source, build, target or profile keys, so distribution, offline and `RUSTFLAGS` setups are unaffected. Cargo finds this file from the current directory, so every rule that runs `cargo xtask` runs it in the workspace directory (the antrun `dir` of the Rust module, `cd $(RUST_WORKSPACE) &&` in `make abi-check`, `WORKING_DIRECTORY` in the CMake target `abi-check`). All other configuration is passed with `--config`, so distro and offline setups and the user's `RUSTFLAGS` are respected. The source-replacement file of the system-crates mode is generated into the build directory and passed with `--config` as well (§3.11).
- **C compiler and assembler for the crypto provider.** ring's build script compiles its C and assembly with the `cc` crate. The build systems pass the configured compiler and archiver to it as `CC_<triple>` and `AR_<triple>`, so cross builds and distro toolchains use one compiler for the shim and the provider. The aws-lc-rs provider also needs the build tools that aws-lc-sys documents, and the FIPS provider needs CMake and Go on the build host for aws-lc-fips-sys; configure and CMake check for them only when that provider is selected (§4.10).

### 3.10 Vendoring, ASF source release and licenses

- **Fetched, not vendored.** Third-party crate sources are in neither git nor the voted source release, and there is no separate vendor artifact. The source assembly (root pom.xml, `apache-release` profile) contains `Cargo.lock` and every workspace crate, and also the separate `fuzz/` and `crates/zk-ffi-layout` workspaces with their own lockfiles. `target/` and the git-ignored generated jute directory are excluded (§13.4).
- **Release builds.** A release build that includes the Rust module gets the crates in either of the two ways that any build has: `cargo fetch --locked` with network access, or a `cargo vendor --locked --sync fuzz/Cargo.toml` directory passed with `-Drust-cargo-vendor=DIR` (§13.1). Either way the build runs with `--locked`, and cargo checks each crate against the checksum that `Cargo.lock` records, so the build uses exactly the reviewed closure (§13.4).
- **Offline builds.** Users who build offline run `cargo vendor` themselves; the build documentation shows how. `dev/vendor.sh` runs `cargo vendor --locked --sync fuzz/Cargo.toml`. `./configure --with-cargo-vendor=DIR` (CMake `-DZOOKEEPER_CARGO_VENDOR=DIR`) adds `--offline --config 'source.crates-io.replace-with="vendored"' --config 'source.vendored.directory="DIR"'`. Maven builds use `-Drust-cargo-vendor=DIR` (§13.1), or `-Dcargo.offline` with a pre-populated `CARGO_HOME` (§13.2). The vendor directory holds every crate that the workspace `Cargo.lock` and `fuzz/Cargo.lock` resolve (native-only and test-only dependencies included, as in §3.11), not only the drop-in closure; it is still a single directory.
- **Distribution builds** use the system-crates mode (§3.11) instead of the pinned set.
- **RAT.** Every new file that the excludes do not cover carries the ASF header, and the generators emit it (§13.6). RAT excludes are the §13.6 list: `**/Cargo.lock`, `**/supply-chain/imports.lock`, `**/supply-chain/audits.toml`, `**/supply-chain/config.toml`, `**/supply-chain/closure/*.txt`, `**/abi/*.tsv`, `**/abi/abixml/**`, `**/fuzz/corpus/**`, `**/abi/needed/*.txt`, `**/abi/layout/*.expect`, `**/abi/release-deltas/*.txt`, `**/tests/conformance/ledger.tsv`, `**/tests/conformance/tls/corpus/**`, `**/tests/conformance/sasl/*.pw` and `**/crates/zk-jute/src/generated/**`.
- **Binary convenience tarball.** `dev/gen-license-binary.sh` produces `LICENSE-binary` from `cargo about` over the zk-ffi closure of the shipped configuration (TLS on, ring). It adds a hand-maintained section for compiled-in std components (`compiler_builtins`, `hashbrown`, `miniz_oxide`, `adler2`, `memchr`, `gimli`, `addr2line`, `object`, `rustc-demangle`).
  - Apache-2.0 is elected wherever a crate offers it (rustls, the RustCrypto crates, rustix).
  - ISC-only crates (rustls-webpki, untrusted) and BSD-3-Clause `subtle` are reproduced with their notices.
  - ring's terms are `Apache-2.0 AND ISC`, so both apply, and its complete LICENSE file, including the notices for code derived from BoringSSL, is reproduced.
  - These are all ASF Category A licenses. A CC0-1.0 crate, if the closure has one, is listed with its attribution (§3.8).
  - ASF convenience binaries use the `ring` provider. A release includes an aws-lc build (`aws-lc-rs` or `aws-lc-rs-fips`) only after the ASF legal review of aws-lc-sys and aws-lc-fips-sys passes (00 §6); its `LICENSE-binary` then adds the OpenSSL-licensed parts of the sys crate.
  - The lib package with the Rust module (`zookeeper-assembly` `lib-package-rust.xml`) adds `LICENSE-binary` and the Rust workspace `NOTICE` beside the C client's files (§13.5).

### 3.11 System-crates build mode

Distributions build the Rust core against the Rust crates they package themselves, so a rustls or provider fix can be applied by patching the distribution crate and rebuilding, without a ZooKeeper release.

- **Selection.** configure `--with-system-rust-crates[=DIR]`, CMake `-DZK_SYSTEM_RUST_CRATES=DIR`, Maven property `rust-system-crates` (default empty, which means off). With configure, `DIR` defaults to `/usr/share/cargo/registry`, the directory that Debian and Fedora populate; CMake and Maven name the directory explicitly. The mode excludes `--with-cargo-vendor` / `ZOOKEEPER_CARGO_VENDOR`; giving both is a configure or CMake error.
- **Source replacement.** The build writes a cargo configuration file into the build directory (`<builddir>/cargo/system-crates.toml`; under Maven `target/cargo/system-crates.toml`) that replaces crates.io with `DIR`: `[source.crates-io] replace-with = "system"` and `[source.system] directory = "DIR"`, or `local-registry = "DIR"` when `DIR` has an `index/` subdirectory. Every cargo invocation of the build passes it with `--config` and runs `--offline`. Nothing in the source tree configures cargo (§3.9).
- **Lockfile.** Before the first build, the build runs `cargo generate-lockfile --offline` with that configuration, which rewrites the workspace `Cargo.lock` against the system crates. The builds and tests then run with `--locked --offline`, so they cannot change the regenerated lockfile. Cargo resolves the whole workspace, so `DIR` must hold every crate that the workspace resolves, including the native-only and test-only dependencies. Because the lockfile in the source tree is rewritten, the mode is for builds from an unpacked source release or a distribution source package.
- **Checks that stay.** The closure-golden check (R4, 08 G1) compares crate names and semver-compatible versions instead of exact versions. cargo-deny and cargo-vet run against the regenerated lockfile. The ABI goldens, the prelink post-checks, symbol localization (§4.3, §4.5) and `xtask abi check` apply unchanged, so a system-crates library has the same exports, the same localized symbols and the same DT_NEEDED set as a pinned build.
- **CI.** A CI job builds this mode against a Debian/Fedora-style crate directory (08 §14).

## 4. Producing libzookeeper_mt and libzookeeper_st

### 4.1 One Rust build per configuration, runtime flavor

There is one cargo invocation per target, profile and TLS configuration:

```sh
cargo build -p zk-ffi --profile capi --locked \
      [--features tls,tls-ring | --features tls,tls-aws-lc-rs | --features tls,tls-aws-lc-rs-fips] \
      [--target TRIPLE] [--offline --config FILE]
# --target: only with --with-rust-target
# --offline plus --config 'source.crates-io.replace-with="vendored"' --config 'source.vendored.directory="DIR"' with --with-cargo-vendor (section 3.10), or --offline --config <builddir>/cargo/system-crates.toml with --with-system-rust-crates (section 3.11)
```

Maven runs it in the Rust module with `CARGO_TARGET_DIR=zookeeper-client-rust/target/cargo` (§13.2, §13.7); standalone builds run it from make or CMake with `CARGO_TARGET_DIR=<builddir>/cargo` (§6). It is never run with `--workspace` (R1).

The flavor and other build-time options reach Rust at runtime through `zkrs_init_args` (05 §6):
- `flavor` (`ZKRS_FLAVOR_MT`/`ST`), set by the shim from `#ifdef THREADED`;
- `sock_cloexec` (from `SOCK_CLOEXEC_ENABLED`, the configure option `--with-sock-cloexec`);
- `sasl` (per handle: set when `zookeeper_init_sasl` got non-NULL params, 05 §6);
- `tls`, and the nullable TLS fields (`tls_ca`, `tls_cert`, `tls_key`, `tls_passwd`) parsed by the shim from the cert string with `strtok_r`;
- `tls_env_allowed` (computed by the shim from `AT_SECURE`/`issetugid`, R6);
- `xid_seed` (`time(0)`);
- `c_char_signed` (`CHAR_MIN < 0` in the shim's compiler, 02 §3.3).

There are no layout cookies: public C structs never cross into Rust (§7).

TLS presence is a cargo feature, not a runtime flag, because it selects code and dependencies. A shim compiled with `ZKS_WITH_TLS` references the zk-ffi function `zkrs_feature_tls()`, which exists only when zk-ffi has the `tls` feature. A mismatched pair therefore fails the prelink post-check "no undefined `zkrs_*` reference" (§4.3) instead of failing at runtime. For a staticlib that Maven built (`--with-rust-staticlib`), configure also checks the other direction (§6.1).

Inside zk-ffi:
- `Compat` is `Compat::LEGACY_C` in every build, with `tls_verify = LegacyChainOnly`. It includes the behavior fixes of 00 §5.4 that live in the core. There is one struct, owned by 02 §14, with values from 07. The flavor never changes `Compat`.
- Per-driver settings form a separate `zk_proto::DriverMode {interest, session_delivery}` derived from the flavor: st is `LegacyExact` + `Inline`; mt is `Precise` + `Dispatch`. The lockpoint is always emitted under `LEGACY_C`; it is a C no-op in st.

Reasons for one build per configuration:
- The shim already carries the compile-time flavor split.
- One Rust object is tested once and shipped twice.
- Two cargo builds per configuration would double build time and invite feature-unification mistakes.
- The st library creates no threads, because only `zks_threads.c` (compiled with `THREADED` only) starts them.

CLOEXEC is parity: sockets get `SOCK_CLOEXEC` exactly when the C build option is on, and the mt waker pipe is created without CLOEXEC, as the legacy self-pipe is (08 PX-01; 07 GLB-05 is a runtime flag).

### 4.2 Panic, abort and failure policy

- **Shipped libraries use `panic = "abort"`.** A Rust panic is an internal bug. It runs std's default panic hook, which writes a message to file descriptor 2 and reads `RUST_BACKTRACE`, and then aborts.
  - No hook is installed, because hooks are process-global and would interfere with other Rust code in the process.
  - Avoiding the default hook's environment read on stable Rust would require such a hook, or `panic_immediate_abort`, which needs nightly `-Zbuild-std`.
  - This failure-path behavior is registered as DV-BLD-5 (B8).
- **Allocation failure in Rust also aborts,** after std's "memory allocation of N bytes failed" message on fd 2. The ledger's OOM policy (07 P7) applies:
  - shim allocations keep C's checked return codes;
  - request serialization uses `try_reserve_exact` and maps failure to ZMARSHALLINGERROR, as C does;
  - auth-frame encoding is infallible and aborts on OOM, because legacy reaches that rc only through allocation failure.
- **Every `zkrs_*` body runs inside `zk_ffi::guard(|| …)`,** which catches unwinding and aborts. It is inert under `capi`, effective under `cargo test` (which unwinds), and checked by Miri.
- **All `zkrs_*` functions and all vtable leaf types are `extern "C"`, not `"C-unwind"`.** Under CF1 no foreign unwind can reach a Rust frame. The only C code Rust calls is the vtable leaves, and they cannot throw. The crypto provider's C and assembly is called only from Rust and never calls out.
- **Rust invariant checks (`unreachable` arms, checked arithmetic that fails closed) are active in every build.** The legacy `assert()` calls are live under autotools but compiled out under CMake Release (`NDEBUG`). So a CMake Release build of the legacy client could continue past a state where the Rust core aborts. The shim keeps legacy's own `assert()` sites (op-init helpers), which still follow `NDEBUG`. This is registered as DV-BLD-5 (B9). Each such case is a state that legacy reaches only through UB.

### 4.3 Pre-link and localize (one object per flavor; Unix-like targets and MinGW)

`abi/prelink.sh` runs from both Make and CMake.

**Inputs:**
- the flavor's shim convenience archive (`libzks_<flavor>.a`), compiled `-prefer-pic -ffunction-sections -fdata-sections`. It contains the shim objects, the SASL glue objects when SASL is on, `recordio.o` and `zookeeper.jute.o`;
- `libzk_ffi.a`, which includes the crypto provider's C and assembly objects when TLS is on (cargo bundles build-script native libraries into the staticlib);
- the keep list, which is the flavor's rows of `symbols.tsv` (for the configured features) ∪ `static-symbols.tsv`, generated by `gen-exports.awk -v fmt=keep`.

Steps:

1. **ELF** (Linux, FreeBSD, illumos with GNU binutils), and MinGW with MinGW binutils:
   ```sh
   $LD -r -o zookeeper_mt_all.o --whole-archive libzks_mt.a --no-whole-archive libzk_ffi.a
   $OBJCOPY --keep-global-symbols=zookeeper_mt.keep zookeeper_mt_all.o   # other globals become local
   ```
   `ld -r` pulls from `libzk_ffi.a` only the members that resolve the shim's `zkrs_*` references and their dependencies: the fat-LTO object, the needed provider objects (`ring_core_*` or `aws_lc_*`) and the needed `compiler_builtins` members.
2. **Mach-O:**
   ```sh
   $CC -r -nostdlib -arch $ARCH -Wl,-exported_symbols_list,zookeeper_mt.keep.exp \
       -Wl,-force_load,libzks_mt.a libzk_ffi.a -o zookeeper_mt_all.o
   ```
3. **Post-checks** (in `prelink.sh` and repeated by `xtask abi check`):
   - the globally defined symbols equal the keep list exactly;
   - there are no undefined references to Rust symbols (`_ZN`, `_R`, `rust_`, `__rust`, `__rdl`, `__rg`) or to `zkrs_*`;
   - there are no undefined references to OpenSSL symbols (`SSL_*`, `ERR_*`, `EVP_*`, `X509_*`, `PEM_*`, `BIO_*`, `OPENSSL_*`, `CRYPTO_*`), so no build can depend on libssl or libcrypto by accident;
   - every remaining undefined symbol is provided by a library in the configuration's `needed/<os>.txt` (checked on the final `.so`);
   - `.note.GNU-stack` is present and not executable (this also covers the provider's assembly objects);
   - there are no TEXTRELs;
   - the GNU property note (x86 IBT/SHSTK, AArch64 BTI) is reported, not enforced. Stable rustc cannot mark its objects, and the provider's assembly may lack the markers, so the combined object carries no such property even when the shim is compiled with `-fcf-protection`/`-mbranch-protection` (DV-BLD-8). When stable rustc gains the options, the build passes them and the check becomes enforcing.
4. **libtool wrapper.** The script copies the object to `.libs/zookeeper_mt_all.o` and writes `zookeeper_mt_all.lo`:
   ```
   # zookeeper_mt_all.lo - a libtool object file
   # Generated by abi/prelink.sh for GNU libtool
   pic_object='.libs/zookeeper_mt_all.o'
   non_pic_object='zookeeper_mt_all.o'
   ```
   Both slots use the same PIC object. CI covers libtool 2.4.6, 2.4.7 and 2.5.x.

Effects:
- Neither artifact carries any `zks_*`, `zkrs_*`, Rust std or crypto-provider global.
- ring's and aws-lc's C and assembly symbols are version-prefixed and also localized, so they cannot collide with an application's own OpenSSL, BoringSSL or another copy of ring.
- `oa_*`/`ia_*` stay global, as legacy has them (§4.5).
- Two Rust staticlibs link in one executable (LINK-01).
- `compiler_builtins` intrinsics such as `__udivti3` cannot clash with libgcc.
- Interposition is unaffected. `ld -r` keeps relocations against global symbols, and the shim is compiled `-fPIC -fsemantic-interposition` without `-Bsymbolic`, so `zoo_get` → `zoo_awget` remains a PLT call to an exported symbol (ABI-06).

Proof gates: the whole-flavor recipe on ELF, Mach-O and MinGW, with libtool accepting the generated `.lo`, is an M1 exit criterion (08 §15.3 M1 (7)). On Mach-O the partial link must reduce the Rust staticlib's globals to the keep list and resolve the duplicate `rust_eh_personality` definitions. The variant with provider objects (TLS on) is an M3 exit gate (§6.5).

### 4.4 Shared libraries

```
ZKRS_LIB_LDFLAGS = -no-undefined -version-info 2 $(SOLARIS_LIB_LDFLAGS) $(SASL_LIB_LDFLAGS)
libzookeeper_mt_la_LDFLAGS = $(ZKRS_LIB_LDFLAGS) -export-symbols zookeeper_mt.sym $(ZKRS_GC_LDFLAGS)
```

- **No OpenSSL link flags.** The Rust core never adds `-lssl -lcrypto` (`OPENSSL_LIB_LDFLAGS` is used only by the legacy core). `$(SASL_LIB_LDFLAGS)` (`-lsasl2`) stays when SASL is on.
- **Exports.** `-export-symbols` becomes an anonymous version script (`{ global: …; local: *; };`, unversioned, no `.gnu.version_d`) on GNU ld, `-exported_symbols_list` on Darwin, and a `.def` on MinGW/Cygwin. The `.sym` is the flavor's `symbols.tsv` rows, so it hides the static-only rows that stay global in the object.
- **Identity is unchanged:**
  - SONAME `libzookeeper_{mt,st}.so.2`, file `.so.2.0.0`;
  - Darwin `libzookeeper_mt.2.dylib`, compatibility and current version 3.0.0;
  - `-version-info 2` is the ABI version and is never bumped for the drop-in.
- **`ZKRS_GC_LDFLAGS`:** `-Wl,--gc-sections` (ELF) or `-Wl,-dead_strip` (Darwin).
- **Interposition rules:**
  - forbidden: `-Bsymbolic`, `-Bsymbolic-functions`, protected visibility, and `-fvisibility=hidden` on public definitions;
  - shim internals are `__attribute__((visibility("hidden")))` and are localized anyway;
  - `logLevel` is read through `extern ZooLogLevel logLevel` at every site;
  - configure adds `-fsemantic-interposition` when accepted.
- **Shim C flags:** the legacy autotools warning set `-Wall -Werror -Wdeclaration-after-statement` (`AM_CFLAGS`, Makefile.am:23), with `-Werror` (`/WX` on MSVC, where legacy CMake uses `/W3` only, CMakeLists.txt:26-30) taken from `ZKS_WERROR` / `ZK_WERROR` (next bullet), `-D_GNU_SOURCE`, the shim feature macros (`-DZKS_WITH_TLS`, `-DZKS_WITH_SASL`), `-DTHREADED` for mt, `-fexceptions` (CF9), and the hardening sets of §4.9. The feature defines `HAVE_OPENSSL_H` and `HAVE_CYRUS_SASL_H` follow legacy placement (§4.8). The shim's own TUs do not depend on `HAVE_OPENSSL_H`. Among the shim TUs, only the two SASL glue files have `HAVE_CYRUS_SASL_H` in effect when `zookeeper.h` is included (§4.9): autotools gives the define to them alone, through their own per-target flags, and in every shim TU that does not define `ZKS_SASL_GLUE`, `zks_internal.h` undefines it before it includes `zookeeper.h`, which covers the CMake PUBLIC definition (05 §2). `NDEBUG` is handled as in the legacy builds.
- **`-Werror`.** `-Werror` (`/WX` on MSVC) is on by default for every shim TU, including the two SASL TUs, in autotools and CMake builds, and CI builds with it. Packagers turn it off with configure `--disable-werror` or CMake `-DZK_WERROR=OFF`, which removes `-Werror` from the shim and SASL flag sets only (configure substitutes `ZKS_WERROR`, §6.1). The legacy core keeps its own flags in every case.
- **Debug builds.** `--enable-debug` (or, in CMake, a Debug configuration) selects the cargo profile `capi-dev` and defines `ZKS_HANDLE_REGISTRY` for the shim TUs (CMake: `target_compile_definitions(<shim targets> PRIVATE $<$<CONFIG:Debug>:ZKS_HANDLE_REGISTRY>)`, which covers single-config and multi-config generators such as Visual Studio and Ninja Multi-Config): the shim keeps a mutex-protected registry of live handles, and every API entry aborts with a clear stderr message on use after close or on a double close (05 owns it). Release builds do not define it and behave as legacy, with no overhead.

### 4.5 Static archives

`libzookeeper_{mt,st}.a` contains exactly one member, `zookeeper_<flavor>_all.o`. Its global defined symbols are exactly:

- the flavor's rows of `symbols.tsv` (functions and the 36 data objects);
- `static-symbols.tsv`, which lists:
  - (a) the 161 header-declared, archive-only symbols: 154 `serialize_*`/`deserialize_*`, the six recordio archive functions, and `close_zsock`;
  - (b) the 18 `oa_*`/`ia_*` functions that `recordio.c` (compiled verbatim, recordio.c:67-283) defines globally.

  The `oa_*`/`ia_*` functions are not declared in any installed header, but they are kept. They cost nothing, remove a needless narrowing, and keep `recordio.c` unmodified.

Nothing else is global. That means no `zks_*`, no `zkrs_*`, no Rust std, no crypto-provider symbols, and none of the legacy internals (`hash`, `do_io`, `get_xid`, `adaptor_*`, `addrvec_*`, `hashtable_*`, …; DV-ABI-1). `cargo xtask abi static` enforces equality in both directions.

The single-member layout means a static link pulls the whole library. With `-ffunction-sections` and the application's `--gc-sections`, the cost is comparable to legacy, where `zookeeper.c` was one member anyway (DV-BLD-6). With TLS on, the member is larger because it contains rustls and the provider; `--gc-sections` drops the parts that the shim cannot reach.

CMake keeps the target `hashtable` as a static library with one member (`zks_hashtable_stub.c`, no global symbols). Both `-lhashtable` and `target_link_libraries(... hashtable)` keep working (B3). Autotools never installed a hashtable library (Makefile.am:39 `noinst_LTLIBRARIES = libhashtable.la`), so it builds none.

### 4.6 Native link dependencies

- configure probes `$RUSTC --crate-type staticlib --target $RUST_TARGET --print native-static-libs` on an empty crate. The results:
  - macOS arm64, measured: `-lSystem -lc -lm`.
  - linux-gnu, expected: `-lgcc_s -lutil -lrt -lpthread -lm -ldl -lc`.

  Duplicates and the driver-implied `-lc`/`-lSystem` are dropped, and the rest becomes `ZKRS_NATIVE_LIBS`. It goes into `LIBADD`, the `.la` `dependency_libs`, the `.pc` `Libs.private`, and the PUBLIC link libraries of the CMake `zookeeper` target (its link interface). ring needs no library beyond these. `xtask abi check` verifies that the real `--print native-static-libs` of the zk-ffi build (including the provider) is a subset of `ZKRS_NATIVE_LIBS`, so a provider that needs more fails CI instead of failing a user's static link.
- `abi/needed/<os>.txt` pins the DT_NEEDED and LC_LOAD_DYLIB entries per configuration. The golden is the reference build's set with the differences of DV-TLS-4 and DV-BLD-1 applied; E9 compares the built library with it (08 §3.2). The differences and the entries that stay:
  - Linux adds `libgcc_s.so.1` (std's `_Unwind_*`), and on glibc < 2.34 also `libdl`, `libutil` and `librt`, plus `libpthread` for st (legacy st linked no libpthread, Makefile.am:52) (DV-BLD-1).
  - macOS adds nothing.
  - **libssl and libcrypto are removed** in every configuration, with or without TLS (DV-TLS-4).
  - libsasl2 stays DT_NEEDED when SASL is on, because the SASL glue calls it.
- The `.la` `dependency_libs`, the `.pc` `Libs.private` and the CMake link interface contain no OpenSSL entry.
- The glibc symbol-version floor is that of the AlmaLinux 8 (glibc 2.28) CI image (08 E10); a library built on glibc 2.28 or older keeps it. ring obtains randomness through getrandom, which uses the libc `getrandom` function on that floor and adds no DT_NEEDED entry.

### 4.7 Windows

- **Scope (M4).** MSVC x64 static `zookeeper.lib` in both flavors, as legacy CMake builds them: `WANT_SYNCAPI` ON gives mt, OFF gives st. The one-member `hashtable.lib` is also built. Windows mt is in scope at M4: 03 §8.8 specifies the loopback socket-pair waker and the `WSAPoll` design. If the M4 spike fails, Windows ships on the legacy core for that release, recorded as DV-BLD-7 (§11), and the Rust core follows on Windows in the first release whose Windows build passes the M4 exit criteria of 08 §15.3 (00 CC-33).
- **Link.** MSVC has no partial link. `zk_ffi.lib` is merged into `zookeeper.lib` through `STATIC_LIBRARY_OPTIONS`, so Rust std, `zkrs_*` and the shim's cross-TU `zks_*` helpers stay visible in the `.lib` (DV-BLD-3). The crypto provider's symbols are also visible there, but they are version-prefixed and cannot collide with an application's OpenSSL. The opt-in DLL (`-DZOOKEEPER_BUILD_SHARED=ON`) exports only the `.def` names, with `DATA` on the 36 data objects.
- **CRT.** Rust's MSVC default is the dynamic CRT, which matches the legacy `/MD`. If `CMAKE_MSVC_RUNTIME_LIBRARY` selects the static CRT, `ZkRust.cmake` adds `-C target-feature=+crt-static`, which ring's build script also follows. One CRT serves the whole library: the shim and zk-ffi's `abi::raw` call the same CRT `malloc`/`calloc`, so the application frees the result layouts exactly as it frees those of the legacy library, which also allocated them with its CRT. The `+crt-static` rule also avoids mixed-CRT link conflicts (LNK2038/LNK4098). Memory from the Rust allocator never reaches C or the application (R6).
- **Winsock.** `winport.c` is not linked (DV-BLD-2), but its observable behavior is kept in the shim (`zks_handle.c`, `zks_wsa_startup`), as legacy `Win32WSAStartup` does it (winport.c:273-292):
  - `zookeeper_init*` on `_WIN32` calls `WSAStartup(MAKEWORD(2,0), &wsa)`. If that fails, or if the returned `wVersion` is not exactly 2.0 (in which case it first calls `WSACleanup()`), init logs the legacy line `Error initializing ws2_32.dll` and returns NULL (zookeeper.c:1387-1391).
  - `WSACleanup()` runs only in `zks_release` (zookeeper.c:4058-4060). Init failure paths after a successful `WSAStartup` do not call it, which keeps legacy's behavior (the Winsock reference stays taken).
  - Rust never calls `WSAStartup`.
- **Threads.** `_beginthreadex(NULL, 0, …)`, as winport.c:55 does.
- **Socket type.** `zkrs_interest_out.fd` is `intptr_t`, with -1 meaning none. Under `WIN32`, the shim writes through the prototype's `SOCKET *fd` as `(SOCKET)o.fd`, a full 8 bytes on Win64. That fixes the `*fd = (int)o.fd` truncation.
- **Waker and poll.**
  - The mt waker on Windows is a loopback TCP socket pair.
  - Polling uses `rustix::event::poll`, which maps to `WSAPoll` on Windows. The M4 spike confirms rustix's Windows coverage of `poll`.
  - The poll goes through the zk-driver `Poll` trait (03 §8.8), whose default implementation is `RustixPoll`. If rustix's coverage falls short, zk-ffi implements `Poll` with a single `WSAPoll` call in its unsafe module (counted in `unsafe-budget.toml`) and injects it at handle creation. zk-driver stays `forbid(unsafe_code)` and does not depend on zk-ffi, so the crate graph (§3.1) is unchanged. `Poll` is not a host-vtable entry, so the vtable list (CF7, 00 CC-6) is also unchanged.
  - The native threaded driver on Windows uses rustix. If rustix's coverage falls short there, the remedy is an upstream rustix fix, not unsafe code in zk-driver or zk-client. The threaded driver is the only native driver on Windows, from M4; the `tokio` feature is Unix only (R3).
- **TLS.** The trust inputs on Windows are only `SSL_CERT_FILE` and `SSL_CERT_DIR`, unless `-DZK_RUST_TLS_DEFAULT_PATHS` sets build defaults, because legacy OpenSSL's compiled-in OPENSSLDIR (for example C:\Program Files\Common Files\SSL) normally does not exist or holds no certificates there; a populated one is covered by DV-TLS-2. No OPENSSLDIR is recorded on Windows hosts. File paths are opened as UTF-8 through the wide API; when the bytes are not valid UTF-8 or the open fails with not-found, the loader retries with the ANSI code-page candidate that zk-ffi computes at handle creation (`MultiByteToWideChar(CP_ACP)` in its raw module), as OpenSSL does (06 owns the rule). The M4 spike also confirms ring's build with the MSVC x64 toolchain, with and without `+crt-static`. The FIPS provider is not offered on Windows (§4.10).
- **SASL.** SASL is optional on Windows, as in legacy: legacy CMake on Windows builds SASL only if Cyrus is found, and the SASL glue follows the same rule. The glue's process-wide password mutex is an `SRWLOCK` there. Windows SASL is tested in CI on a best-effort basis and is not an M4 gate. The hardening flags of §4.9 use their MSVC equivalents (`/GS`, `/guard:cf`, `/sdl`) where they exist. MSVC has neither `__builtin_*_overflow` nor `__attribute__((cleanup))`, so the glue uses the portable macros of §4.9 (`ZKS_ADD_OVF`, `ZKS_MUL_OVF`, `ZKS_CLEANUP`).
- **Toolchains.** Autotools on MinGW uses the windows-gnu target and §4.3. Cygwin is not supported by the Rust client (Rust tier 3): the §6.1 check fails there, so `--with-rust-client=yes|side-by-side` stops configure with a message naming `--with-rust-client=no`, and `auto` selects the legacy core with a notice.

### 4.8 TLS in the drop-in build

- **Where the code lives.** `zk-driver/src/net/tls/` (crate-level `#![forbid(unsafe_code)]`), behind the cargo feature `tls` plus one provider feature. The module list follows 06 §3.1:
  - `mod.rs`: the driver contract `TlsConn` (with `feed_eof() -> Result<(), TlsFailure>` and the plaintext latch of 03 §8.2.1), `TlsConnector::new_conn(&self, &Unlocked, TlsPurpose, &Endpoint)` (no time argument: the verifier uses the rustls time provider, the system clock in production and an injected provider in tests), `TlsConnector::path_for(step) -> Option<&[u8]>`, `TlsFailure`, `TlsSetupError {Config{step: zk_proto::TlsConfigStep}, Resource{at: zk_proto::TlsResourceAt}}`, `TlsPurpose{Main, Probe}`, the driver-side `TlsTransport`, and re-exports of `zk_proto::{TlsConfigStep, TlsResourceAt, Endpoint}` (03 §8.2.1 owns the signatures; `TlsConfigStep {CaFile, DefaultVerifyPaths, ClientCertChain, ClientKey, CheckPrivateKey}`, `TlsResourceAt {ContextNew, ConnectionNew}` and `Endpoint {addr: SocketAddr, host: HostToken}` are zk-proto types, CC-17 (00 §4)). On TCP-connect completion the driver calls `new_conn` first and then `handle_tcp_connected`;
  - `conn.rs`: `RustlsConn`, a `rustls::ClientConnection` driven over in-memory buffers (`read_tls`/`write_tls` into `Vec<u8>`) on the socket the driver owns;
  - `connector.rs`: `RustlsConnector`, one `ClientConfig` and `ClientConnection` per attempt and per probe;
  - `pem.rs`: the strict PEM block reader with RFC 1421 headers, base64 through `base64ct` (no `rustls-pemfile`);
  - `x509.rs`: a small read-only DER view of certificates (v1 accepted), OpenSSL name canonicalization and subject hash;
  - `trust.rs`: CA file (the step succeeds when the file yields at least one certificate or CRL, as `X509_load_cert_crl_file`, and fails when the file cannot be read, when a PEM block is malformed (06 §3.4), when `ca` is NULL, or when it yields neither a certificate nor a CRL; CRLs are counted and not used) and default verify paths (06 §3.4), shared by the drop-in and native `tls-rustls` (the only environment read, gated by `tls_env_allowed`; compile-time default locations; R6);
  - `legacy_verify.rs`: `LegacyChainOnly`, a `rustls::client::danger::ServerCertVerifier` with the rules (a)-(d) of 06 §3.5: (a) chain to a trust anchor through `verify_server_cert_signed_by_trust_anchor` without a name check; (b) a leaf byte-identical to a trust-set certificate is accepted only when that certificate is OpenSSL-self-signed (`EXFLAG_SS`, defined in 06 §3.4) and the leaf has no critical extension outside the processed set of 06 §3.14.3 (error 34; aux trust does not override it); (c) no SNI, the peer IP as `ServerName::IpAddress`, no host-name check; (d) only OpenSSL-self-signed, CA-capable, purpose-compatible trust inputs valid at now end a chain, plus `TRUSTED CERTIFICATE` inputs whose aux trust list names serverAuth or anyExtendedKeyUsage (explicit trust, 06 §3.4); rejected `TRUSTED CERTIFICATE` inputs (06 §3.4) are never anchors, bridges or rule-(b) matches; they stay issuer candidates, and the chain fails (error 28) when OpenSSL's chain building would select one (06 §3.4; on the v3 path the rejected-issuer post-check of 06 §3.5); trust inputs that are neither self-signed nor explicitly trusted are bridges, offered only as intermediates; a self-signed input that is not an anchor can be accepted only through path (b); in issuer selection (06 §3.14.3) it stays a trusted candidate, and a chain that selects it fails, as in OpenSSL (06 §3.4). A v3 leaf goes through webpki, followed by the keyCertSign post-check (`keycertsign_post_check`), the rejected-issuer post-check and the OpenSSL path post-check of 06 §3.5 (the last runs the issuer selection and finish rules of 06 §3.14.3, in `v1_leaf.rs`, over the leaf, the sent certificates and the trust set, with the leaf's own purpose and subjectAltName); the leaf is accepted only when webpki and all three post-checks accept. A v1 leaf, which webpki does not accept, goes through the in-tree `forbid(unsafe)` v1-leaf path of 06 §3.14, which checks the leaf signature against its issuer with the provider's `SignatureVerificationAlgorithm` and verifies the issuer chain by OpenSSL's rules. Handshake signatures use the provider's signature algorithms;
  - `v1_leaf.rs`: the v1-leaf verifier (06 §3.14): OpenSSL chain rules for an X.509 v1 leaf, and the same rules as the OpenSSL path post-check for a v3 leaf (06 §3.5), with signatures checked through the provider's `SignatureVerificationAlgorithm`;
  - `identity.rs`: client certificate chain and key loading (unencrypted PKCS#8, PKCS#1 RSA and SEC1 EC; encrypted PKCS#8 with PBES2, PBKDF2 with HMAC-SHA1, -SHA224, -SHA256, -SHA384 or -SHA512, or scrypt, and AES-128/192/256-CBC or DES-EDE3-CBC; scrypt parameters rejected before allocation when the memory need is over 32 MiB, OpenSSL's default `EVP_PBE_scrypt` maxmem; PKCS#12 PBE and other encryptions rejected, DV-TLS-5, and PBES1 failing in both cores under the reference default providers), the key-match check (06 §3.6 owns the rules);
  - `legacy_pem.rs`: traditional OpenSSL PEM encryption (`Proc-Type: 4,ENCRYPTED`, `DEK-Info`, AES-128/192/256-CBC, DES-EDE3-CBC or DES-CBC, EVP_BytesToKey with MD5).

  The native `WebPki` policy uses rustls's `WebPkiServerVerifier` and needs no file of its own.
- **Lock discipline.** Certificate and key files are re-read on every connection attempt and probe (TLS-02 parity). The reads and the `ClientConfig` construction run on the IO owner with no core lock held (`&Unlocked`), like the Blocking vtable leaves.
- **TLS decision procedure.** TLS presence in the C build matches the legacy autodetect exactly. `--with-openssl` and `WITH_OPENSSL` keep their names, their defaults and their probe, so `zookeeper_init_ssl` and `HAVE_OPENSSL_H` are present in exactly the configurations where legacy has them. The probe only decides the feature. With the Rust core, OpenSSL is never linked, and libssl and libcrypto are not DT_NEEDED (DV-TLS-4).
  - **configure.** `--with-openssl` defaults to `yes`, which means autodetect. For `yes` and for `=DIR`, configure runs the legacy probe unchanged (configure.ac:42-67): with `=DIR` it adds `-IDIR/include` and `-LDIR/lib` for the probe, then `AC_CHECK_HEADER(openssl/ssl.h, [AC_CHECK_LIB(ssl, SSL_CTX_new, ...)])`. TLS is on only when both checks pass. Otherwise configure warns "cannot build SSL support -- openssl not found" and builds without TLS. `--with-openssl=no` turns TLS off without a probe. With the Rust core, the probe's `-I`/`-L` flags are restored after the probe and never reach the shim or the Rust-core link line; cli/load_gen and the legacy core get them as in legacy (`ZK_OPENSSL_DIR_CPPFLAGS`/`ZK_OPENSSL_DIR_LDFLAGS`, §6.1, §6.2). When TLS is built, `-DHAVE_OPENSSL_H` (`OPENSSL_CPPFLAGS`) is in the library CPPFLAGS, as in legacy (Makefile.am:11-12, 22; §6.2), and `-DHAVE_CYRUS_SASL_H` (`SASL_CPPFLAGS`) is there when SASL is built.
  - **CMake.** `WITH_OPENSSL` defaults to `ON`. A path value is accepted, but as in legacy it does not set OPENSSL_ROOT_DIR (CMakeLists.txt:209 is `set(OPENSSL_ROOT_DIR,${WITH_OPENSSL})`, which sets a differently named variable). The legacy block runs unchanged. `find_package(OpenSSL)` runs as in legacy (CMakeLists.txt:205-219). TLS is on only when `OPENSSL_FOUND`; otherwise CMake prints the legacy warning "unable to find OpenSSL libraries! will build without SSL support." and builds without TLS. `OFF` turns TLS off. When TLS is built, `HAVE_OPENSSL_H` is a `PUBLIC` compile definition of the `zookeeper` target, as in legacy (CMakeLists.txt:213; §6.3), and `HAVE_CYRUS_SASL_H` is `PUBLIC` when SASL is built (CMakeLists.txt:227).
  - **"TLS is built"** means that the legacy autodetect found OpenSSL, or that `--with-rust-tls=yes` / `WITH_RUST_TLS=ON` is set. Both build systems define `HAVE_OPENSSL_H` by that one rule.
  - **Opt-in `--with-rust-tls=yes`** (CMake `-DWITH_RUST_TLS=ON`), Rust core only, off by default. It turns TLS on without the OpenSSL probe, so a host without OpenSSL headers can build a TLS library. It is additive: it only adds `zookeeper_init_ssl` to the export set of a host where the autodetect finds no OpenSSL. `--with-rust-tls=yes` together with `--with-openssl=no` is a configure error, because the two requests contradict each other.
  - With `--with-rust-client=no` the legacy core uses the probe result and links OpenSSL exactly as in legacy.
  - With `side-by-side`, the one probe result decides TLS for both trees, so both export the same set and the differential suite compares like with like. `side-by-side` together with `--with-rust-tls=yes` is a configure error when the legacy probe found no OpenSSL, because both trees share one `.sym` and the legacy tree cannot build TLS.
- **Mapping to features and exports:**

  | TLS decision (above) | `--with-rust-tls-provider` / `ZK_RUST_TLS_PROVIDER` | zk-ffi features | Closure golden | Exports | Shim macro |
  |---|---|---|---|---|---|
  | off (`--with-openssl=no` / `OFF`, or the probe found no OpenSSL and `--with-rust-tls` is not set) | ignored | none | `-notls` | without `zookeeper_init_ssl` (210/187 with SASL, 201/178 without) | — |
  | on (the probe found OpenSSL, or `--with-rust-tls=yes` / `WITH_RUST_TLS=ON`) | `ring` (default) | `tls,tls-ring` | default | with `zookeeper_init_ssl` (211/188 with SASL, 202/179 without) | `-DZKS_WITH_TLS` |
  | as above | `aws-lc-rs` | `tls,tls-aws-lc-rs` | `-aws-lc-rs` | as above | `-DZKS_WITH_TLS` |
  | as above, Linux x86_64 or aarch64 target | `aws-lc-rs-fips` | `tls,tls-aws-lc-rs-fips` | `-aws-lc-rs-fips` | as above | `-DZKS_WITH_TLS` |

  - **Default trust locations.** `--with-rust-tls-default-paths=FILE:DIR` (CMake `-DZK_RUST_TLS_DEFAULT_PATHS=FILE:DIR`) defaults to the OPENSSLDIR recorded at configure time: `FILE` is `OPENSSLDIR/cert.pem` and `DIR` is `OPENSSLDIR/certs`, where OPENSSLDIR comes from `openssl version -d` when that command is available, else from the platform list of §6.1. The command is `DIR/bin/openssl` for `--with-openssl=DIR` (CMake: `${OPENSSL_ROOT_DIR}/bin/openssl` when `OPENSSL_ROOT_DIR`, the variable that steers `find_package(OpenSSL)`, is set); when no installation is named, it is the first `openssl` on `PATH`. When the selected binary does not exist, the platform list applies; a `PATH` binary is never used for a named installation, because it can belong to a different library. Legacy trusts the OPENSSLDIR of the library it links, so this keeps the default trust set to that library's set. The build passes the pair to cargo as `ZK_TLS_DEFAULT_CERT_FILE` and `ZK_TLS_DEFAULT_CERT_DIR` (R6). 06 §3.4 owns the loader rules that use them.
  - The Maven property `c-client-openssl` (root pom default `yes`) passes `--with-openssl` unchanged, and the root property `rust-tls` (default `no`) passes `--with-rust-tls`. The Rust module derives the feature set of the Maven-built staticlib from the same decision (§13.7); `rust-tls-provider` (`ring`, `aws-lc-rs` or `aws-lc-rs-fips`) passes `--with-rust-tls-provider`.
- **Headers and the shim.** The installed `zookeeper.h` is unchanged: it includes `<openssl/ossl_typ.h>` and declares `zookeeper_init_ssl` only when the *application* defines `HAVE_OPENSSL_H`, and `zsock_t`/`zcert_t` keep their layouts. The shim's own TUs do not depend on `HAVE_OPENSSL_H`: they use `ZKS_WITH_TLS` for TLS presence, declare `zookeeper_init_ssl` themselves (its prototype has no OpenSSL type), use no OpenSSL type and include no OpenSSL header. The shim therefore builds on a host without OpenSSL headers. The OpenSSL headers serve only the TLS decision and the consumers:
  - `zks_internal.h` declares the shim's own `ZOOAPI zhandle_t *zookeeper_init_ssl(...)` prototype under `ZKS_WITH_TLS`;
  - `xtask abi headers` compiles a check TU with `HAVE_OPENSSL_H` on CI hosts that have the OpenSSL headers, and proves the shim prototype and the installed one are the same type;
  - `close_zsock` (static archive only, `zks_compat.c`) does not depend on `HAVE_OPENSSL_H`. It reads and writes only `sock`, whose offset is the same in both `zsock_t` layouts the application can see. It does not free `ssl_sock`/`ssl_ctx`, which the legacy body frees (zookeeper.c:648-670; DV-TLS-4). The library itself never touches those fields.
- **Consumers that define `HAVE_OPENSSL_H`** (CMake consumers get it from the `zookeeper` target's PUBLIC definitions, as in legacy) still need the OpenSSL headers to compile, because the installed header includes `<openssl/ossl_typ.h>`. In legacy the include directory reaches consumers through the PUBLIC `OpenSSL::SSL` link. The Rust core does not link `OpenSSL::SSL`, so CMake adds the OpenSSL include directory to the `zookeeper` target's PUBLIC include directories when the headers are found. With `WITH_RUST_TLS=ON` on a host without the headers, the library still builds with TLS, `HAVE_OPENSSL_H` is still PUBLIC (TLS is built), and CMake warns that consumers need the OpenSSL headers to compile against `zookeeper.h`. The shim TUs do not depend on the definition. They are compiled in the `zks_<flavor>` targets (§6.3). No OpenSSL library is added to any link interface (DV-TLS-4).
- **In-tree programs.** `cli.c` and `load_gen.c` are compiled with `-DHAVE_OPENSSL_H` only when TLS is on and a header-only probe finds `openssl/ossl_typ.h`; the probe and their compile use the `--with-openssl=DIR` include directory, as in legacy (configure.ac:48-58 keeps `-IDIR/include` in CPPFLAGS when the probe passes), so `cli_st`/`cli_mt` keep `-s/--ssl` (cli.c:956, 1074). When the autodetect turned TLS on, the headers are always present, as in legacy. With `--with-rust-tls=yes` on a host without the headers, `cli_st`/`cli_mt` are built without their TLS option, which is what legacy builds on such a host do.
- **Visible deviations.** 06 §3.13 holds the text of DV-TLS-1..6, and 07 holds the runtime rows. §11 lists the linkage row DV-TLS-4 with that text, followed by its build facts. By title: DV-TLS-1 protocol floor; DV-TLS-2 policy source; DV-TLS-3 algorithm and certificate acceptance; DV-TLS-4 linkage; DV-TLS-5 key formats; DV-TLS-6 key formats of the FIPS build (that build only).

### 4.9 SASL glue: isolation and hardening

- **Isolation.** Exactly two translation units include `<sasl/sasl.h>`: `zks_sasl_cyrus.c` and `zks_sasl_cb.c`. Among the shim TUs, only they have `HAVE_CYRUS_SASL_H` in effect when `zookeeper.h` is included: autotools gives the define only to `libzks_sasl_<flavor>.la`, and under CMake the PUBLIC definition reaches every shim TU, where `zks_internal.h` undefines it before `zookeeper.h` unless `ZKS_SASL_GLUE` is defined (05 §2). This matters because `zookeeper.h` includes `<sasl/sasl.h>` under that macro (zookeeper.h:42-44). Every other shim TU gets `-DZKS_WITH_SASL`, includes only `shim/zks_sasl.h` (no Cyrus types) and never sees Cyrus types. `shim/zks_sasl_cb.h` carries the interface between the two SASL TUs and has Cyrus types; only those two TUs include it. Because `zookeeper_init_sasl` takes a `zoo_sasl_params_t *` (a Cyrus type), it is defined in `zks_sasl_cyrus.c` and passes the parameters to the common init in `zks_handle.c` (`zks_init_common(…, NULL, sasl_params)`), which creates the session with `zks_sasl_sess_new` after the initial resolution, as legacy does (zookeeper.c:1450, 1472-1479; 05 §15.1). The leaked `zoo_sasl_*` internals also live in `zks_sasl_cyrus.c`. A CI check of the compiler depfiles verifies that no other file includes `<sasl/sasl.h>` or `zks_sasl_cb.h`, and a grep that no other file names a `sasl_*` type. With SASL off, neither file is compiled and the golden subsets keep the symbol differences.
- **Interface to the drain.** The glue interface is (06 §4.7.2 owns the details):
  ```c
  void zks_sasl_start(zks_sasl_sess *s, uint32_t gen, const zks_sasl_params *p,
                      zks_bytes local, zks_bytes peer, zkrs_sasl_out *out);
  void zks_sasl_step(zks_sasl_sess *s, uint32_t gen, zks_bytes challenge, zkrs_sasl_out *out);
  void zks_sasl_dispose(zks_sasl_sess *s, uint32_t gen);
  ```
  `zkrs_sasl_out` is `{status: CONTINUE|DONE|FAIL|STALE, mech, token}` (05 owns the constant values). The drain reads `p` from the session's own copy through the accessor `zks_sasl_sess_params` and records `gen` with `zks_sasl_set_gen`, which `zks_sasl_cyrus.c` defines. The mechlist reaches `sasl_client_start` as `p->mechlist`, the session's NUL-terminated copy; it is not a separate argument. The glue rejects a stale `gen` itself: it returns status STALE and makes no Cyrus call, so the libFuzzer harness also covers that check. Byte arguments are `zks_bytes`, which is `typedef zkrs_bytes zks_bytes` (`{p, len, is_null}`; 05 owns the layout), built by Rust from bounds-checked data. `local` and `peer` carry the platform `sockaddr_in`/`sockaddr_in6` image that zk-ffi writes from the rustix `getsockname`/`getpeername` result. `zks_sasl_start` validates family and length, calls `getnameinfo(NI_NUMERICHOST|NI_NUMERICSERV)` and formats `"%s;%s"` with `snprintf` into bounded buffers. On error it logs "getnameinfo: %s\n" at the legacy site (zk_sasl.c:185). This keeps the legacy address strings byte-identical, IPv6 scope suffixes included. C never keeps a pointer into a Cyrus output buffer past the next Cyrus call: `zkrs_sasl_result` copies the token into Rust at once. On a `sasl_client_new` failure the glue disposes `*pconn` if it is non-NULL. 06 §4.7 owns the full rules: ownership, serialization, the per-session password sink written by the library password callback with no ZooKeeper lock held, and the process-wide mutex that guards only the short in-block copy for the direct Cyrus path. No internal lock is ever held across application code, so unwinding needs no cleanup handler in the glue.
- **Build mechanics.** Automake has no per-file flags, so each flavor builds the two files into a separate convenience library, `libzks_sasl_<flavor>.la` (per flavor, because `zks_internal.h` depends on `THREADED`), linked into `libzks_<flavor>.la`. CMake uses `set_source_files_properties(... COMPILE_DEFINITIONS HAVE_CYRUS_SASL_H COMPILE_OPTIONS ${ZKS_HARDEN_SASL_CFLAGS})`. With `--with-sasl=DIR`, the Cyrus include directory is passed as `-isystem DIR/include`, so the strict warnings apply to our code only.
- **Flag sets.** configure (and `ZkHarden.cmake`) probes each flag and keeps those the compiler accepts. The rule is probe-and-drop on every build, release builds included: configure and CMake print a notice for each dropped flag, and no build requires the full set. `-D_FORTIFY_SOURCE=3` needs GCC 12 or later (or a Clang that supports it) and glibc 2.34 or later; `-ftrivial-auto-var-init=zero` needs GCC 12 or Clang 16. The glibc floor image (AlmaLinux 8, glibc 2.28) therefore builds with `-D_FORTIFY_SOURCE=2`.
  - `ZKS_HARDEN_SASL_CFLAGS`, for the two SASL TUs: `-D_FORTIFY_SOURCE=3` (falls back to `=2` where level 3 is not supported, and is omitted when optimization is off, because glibc warns and `-Werror` would fail the debug build), `-fstack-protector-strong -fstack-clash-protection -ftrivial-auto-var-init=zero -fno-strict-overflow -Wformat=2 -Wformat-security -Wconversion -Wsign-conversion`, `$(ZKS_WERROR)` (`-Werror` unless `--disable-werror` / `ZK_WERROR=OFF`, §4.4), and `-fcf-protection` (x86) or `-mbranch-protection=standard` (AArch64);
  - `ZKS_HARDEN_CFLAGS`, for every other shim TU: the same set without `-Wconversion -Wsign-conversion`;
  - `recordio.c` and the generated jute C keep the legacy flags (they are compiled verbatim, in `libzks_jute.la`).
- **Portable checked arithmetic and cleanup.** The two SASL TUs use the macros of `shim/zks_sasl_cb.h` (06 §4.7.9 owns the rules): `ZKS_ADD_OVF`/`ZKS_MUL_OVF` expand to `__builtin_add_overflow`/`__builtin_mul_overflow` on GCC and Clang and to explicit range checks on MSVC; `ZKS_CLEANUP` expands to `__attribute__((cleanup))` on GCC and Clang, and on MSVC the same functions release through an explicit `goto out` path. The glue is written so that both forms are correct.
- **Verification hooks in the build.**
  - `zookeeper-client-c/fuzz/sasl_glue/` is a libFuzzer harness (clang, `-fsanitize=fuzzer,address,undefined`) that drives `zks_sasl_start/step/dispose` against Cyrus's in-process PLAIN and DIGEST-MD5 plugins and a fake password callback, with fuzzed tokens, addresses, generations and params. The mechlist is fuzzed through the params: `zks_sasl_test_new` takes a mechlist argument. It links the two SASL glue objects, `zks_log.c`, a stub handle and a static Cyrus with the PLAIN and DIGEST-MD5 client plugins (06 §4.7.11). `make fuzz` and the CMake target `fuzz` build it; it is never installed.
  - The SASL conformance tests run under ASan, UBSan and Valgrind memcheck, and MSan where Cyrus can be instrumented.
  - CI runs the Clang static analyzer, `gcc -fanalyzer` and CodeQL on `shim/`.
  - A unit test checks the callback-block layout with `offsetof` and the single-`free()` release under LSan.

### 4.10 Crypto providers and the FIPS build

The design supports three provider builds of the drop-in and of the native TLS features. `ring` is the default; `aws-lc-rs` and `aws-lc-rs-fips` are build options.

| Provider | configure / CMake / Maven value | Cargo feature | Underlying crates | Targets | Closure golden |
|---|---|---|---|---|---|
| ring (default) | `ring` | `tls-ring` | rustls `ring`, ring | every target ring supports | `zk-ffi-<target>.txt` |
| aws-lc-rs | `aws-lc-rs` | `tls-aws-lc-rs` | rustls `aws_lc_rs`, aws-lc-rs, aws-lc-sys | every target that aws-lc-sys builds on; golden and CI job for x86_64-unknown-linux-gnu and aarch64-unknown-linux-gnu only (08 §10 G1, job `tls-aws-lc`); other targets build without a golden | `zk-ffi-<target>-aws-lc-rs.txt` |
| aws-lc-rs-fips | `aws-lc-rs-fips` | `tls-aws-lc-rs-fips` | rustls `fips`, aws-lc-rs `fips`, aws-lc-fips-sys | Linux x86_64 and aarch64 | `zk-ffi-<target>-aws-lc-rs-fips.txt` |

- **Selection.** `--with-rust-tls-provider`, CMake `ZK_RUST_TLS_PROVIDER`, Maven `rust-tls-provider`; exactly one Cargo provider feature, enforced by `compile_error!` (R3). The native zk-client features mirror these (§10).
- **FIPS build host.** aws-lc-fips-sys builds the FIPS module with CMake and Go, so configure and CMake check for `cmake` and `go` when `aws-lc-rs-fips` is selected, and fail with a message that names them. They also fail for any target other than Linux x86_64 and aarch64, the targets on which the FIPS build is validated.
- **FIPS policy (06 owns the rules).** The FIPS build enables rustls `fips` and sets `require_ems`, and offers only FIPS-approved cipher suites, key-exchange groups and signature algorithms. The connector builds each `ClientConfig` from `rustls::crypto::default_fips_provider()`, passed per config (R10), asserts `provider.fips()` once at handle creation and checks `ClientConfig::fips()` on every per-attempt config (06 §3.15.2). Every signature check, including the v1-leaf path of 06 §3.14, uses only the FIPS module.
- **FIPS key formats.** The FIPS build accepts unencrypted keys and PBES2 with PBKDF2-HMAC-SHA256/384/512 and AES-CBC only. It rejects the MD5- and DES-based forms (traditional PEM encryption, DES-EDE3-CBC, DES-CBC), PBKDF2-HMAC-SHA1 and -SHA224, and scrypt, which is DV-TLS-6 (06 §3.13, §3.15.3). `tls-aws-lc-rs-fips` enables `pkcs8` with only its `pkcs5` feature (parsing of `EncryptedPrivateKeyInfo`, no `encryption`, no pkcs5 `pbes2`), and not `pbkdf2`, `scrypt`, `hmac`, `sha1`, `aes`, `cbc`, `cipher`, `des` or `md-5`; `legacy_pem.rs` is not compiled (R3). Key derivation and decryption use `aws_lc_rs::pbkdf2` and `aws_lc_rs::cipher` (06 §3.15.3), so the FIPS library contains no non-approved key-decryption code.
- **Supply chain.** Each provider has its own closure golden, and cargo-deny runs with the build's own configuration: `deny.toml` for ring and aws-lc-rs, `deny-fips.toml` for the FIPS build (§3.7). CI builds and tests the aws-lc-rs and FIPS builds on Linux x86_64 and aarch64 (08 §14).
- **Release condition.** A release includes an aws-lc build only after the ASF legal review of aws-lc-sys and aws-lc-fips-sys passes (00 §6). ring stays the default and the provider of the ASF convenience binaries (§3.10).

## 5. Golden ABI files and checks

The golden ABI files follow 08 §3. This section owns their use in the build.

- `abi/symbols.tsv` is the single export source. Its columns are `name kind flavors feature rw size_lp64 size_ilp32 decl note`. Totals: mt 211 / st 188 with SSL+SASL, 210/187 with SASL only, 202/179 with SSL only, and 201/178 with neither. The `ssl` feature column means "TLS on", the outcome of the TLS decision of §4.8.
- `abi/static-symbols.tsv` holds the archive-only rows (§4.5), 179 in total.
- `abi/absent.tsv` lists names that must not be exported from the `.so`.
- `abi/needed/<os>.txt` lists DT_NEEDED / LC_LOAD_DYLIB per configuration; no configuration of the Rust core lists libssl or libcrypto (§4.6).
- The build has one export-list format source (`abi/symbols.tsv` and `abi/static-symbols.tsv`): `gen-exports.awk`, and on CMake for Windows the `ZkExports.cmake` parser, whose output CI proves identical. `abi/gen-exports.awk -v flavor=mt -v ssl=1 -v sasl=1 -v fmt=sym|map|exp|def|keep` writes these forms:
  - `sym` is for libtool;
  - `map` is the GNU ld version script for CMake;
  - `exp` is the underscore-prefixed Darwin list;
  - `def` is the Windows `.def` with `DATA`;
  - `keep` is used by `prelink.sh`.

  CMake on Windows, where awk is not guaranteed, uses an equivalent `file(STRINGS)` parser in `ZkExports.cmake`. CI checks that both produce identical output for every configuration. A legacy-core build never needs cargo.
- Both cores link with `-export-symbols`. For the legacy core, the generated list exports exactly the set that the legacy `-export-symbols-regex $(EXPORT_SYMBOLS)` (Makefile.am:48-70) exports, and CI proves it with E1.
- **Checks** are `cargo xtask abi check|static|headers|layout|consumers`, which are 08's E1–E16, S1–S3, L1–L3 and AR-09. `make abi-check`, the CMake target `abi-check` and, under Maven, the C module's verify phase (when `rust-client≠no`, §13.7) invoke them when cargo is available. There are no shell check scripts other than the post-checks that `abi/prelink.sh` runs on its own output (§4.3), which `xtask abi check` repeats.
- **Drift checks:**
  - `shim/zkrs.h` against `cbindgen --verify`;
  - zk-jute: `RustGenerator` output must be identical across two runs (determinism), plus the three-way Java/C/Rust codec agreement over every record (§8, 08 §8.4);
  - `zk-proto/src/generated/log_sites.rs` (the `LogSite` enum) and `shim/zks_log_sites.h` against `cargo xtask gen log-sites --check` (input `abi/log-sites.tsv`);
  - the closure goldens (§3.3 R4);
  - versions (§9).
- **Log-site table.** `abi/log-sites.tsv` is pinned to the final legacy C-core commit on main before the Rust core becomes the default. `cargo xtask log-sites` extracts it from the C core at that commit and re-extracts it whenever the pin moves. Legacy C-core changes may move or change log sites freely; the table follows the pin, not the current C sources (05 §16.2, CC-30). 08 pins the legacy reference build to the same commit.

## 6. Build-system integration

### 6.1 configure.ac (additions)

```m4
AC_ARG_WITH([rust-client],
  [AS_HELP_STRING([--with-rust-client=no|yes|side-by-side|auto],[client implementation (default: no before M5, auto from M5)])],
  [AS_CASE([$withval], [true], [with_rust_client=yes])],
  [with_rust_client=no; zk_rust_client_default=yes])  dnl from M5: [with_rust_client=auto]
dnl Core selection: detect and require (rules after this block). The AC_ARG_WITH calls of the Rust-only
dnl options sit at top level before this point, so that auto and the default no can see them; they are shown
dnl in the Rust block below for grouping. Each records zk_rust_requested_by=<option> when given:
dnl --with-rust-tls=yes, --with-rust-tls-provider (any value), --with-rust-tls-default-paths,
dnl --with-rust-workspace (any value but auto), --with-rust-staticlib, --with-rust-target, --with-cargo-vendor,
dnl --with-system-rust-crates.
AC_ARG_WITH([rust-workspace],
  [AS_HELP_STRING([--with-rust-workspace=DIR],[Rust workspace (default auto: $srcdir/rust, then $srcdir/../zookeeper-client-rust)])],
  [AS_IF([test "x$withval" != xauto], [zk_rust_requested_by=${zk_rust_requested_by:-"--with-rust-workspace=$withval"}])],
  [with_rust_workspace=auto])
AS_IF([test "x$with_rust_client" != xno], [ZK_RUST_CHECK])   dnl acinclude.m4: zk_rust_ok=yes|no, zk_rust_why
AS_CASE([$with_rust_client],
  [no], [AS_IF([test "x$zk_rust_client_default" = xyes && test -n "$zk_rust_requested_by"],
    [AC_MSG_ERROR([$zk_rust_requested_by needs --with-rust-client=yes])])],
  [yes|side-by-side], [AS_IF([test "x$zk_rust_ok" != xyes],
    [AC_MSG_ERROR([--with-rust-client=$with_rust_client: $zk_rust_why; --with-rust-client=no builds the legacy C client])])],
  [auto], [AS_IF([test "x$zk_rust_ok" = xyes], [with_rust_client=yes],
    [test -n "$zk_rust_requested_by"],
      [AC_MSG_ERROR([$zk_rust_requested_by needs the Rust client, which this host cannot build: $zk_rust_why])],
    [AC_MSG_NOTICE([building the legacy C client: $zk_rust_why]); with_rust_client=no])],
  [AC_MSG_ERROR([--with-rust-client must be no, yes, side-by-side or auto])])
AM_CONDITIONAL([RUST_CLIENT],   [test "x$with_rust_client" != xno])
AM_CONDITIONAL([LEGACY_CLIENT], [test "x$with_rust_client" != xyes])
AM_CONDITIONAL([RUST_SIDE_BY_SIDE], [test "x$with_rust_client" = xside-by-side])
dnl Automake needs every AM_CONDITIONAL to run on every configure run, so these sit outside the Rust block.
AM_CONDITIONAL([ZKRS_PREBUILT], [test "x$with_rust_client" != xno && test -n "$with_rust_staticlib"])
AM_CONDITIONAL([ZKRS_SYSTEM_CRATES], [test "x$with_rust_client" != xno && test "x$with_system_rust_crates" != xno])
dnl --with-openssl=DIR flags. The legacy block (configure.ac:42-67) keeps -IDIR/include and -LDIR/lib in
dnl CPPFLAGS/LDFLAGS when its probe passes. They are saved here, before the Rust block restores them,
dnl so that cli/load_gen and the legacy core get them as in legacy.
AS_IF([test "x$with_openssl" != xno && test "x$with_openssl" != xyes],
  [ZK_OPENSSL_DIR_CPPFLAGS="-I$with_openssl/include"; ZK_OPENSSL_DIR_LDFLAGS="-L$with_openssl/lib"],
  [ZK_OPENSSL_DIR_CPPFLAGS=; ZK_OPENSSL_DIR_LDFLAGS=])
AC_SUBST([ZK_OPENSSL_DIR_CPPFLAGS]) AC_SUBST([ZK_OPENSSL_DIR_LDFLAGS])
AS_IF([test "x$with_rust_client" != xno], [
  dnl ZK_RUST_CHECK (above) has already applied the Cygwin and DV-BLD-7 rules, the cargo/rustc >= 1.85 gate, the
  dnl cross-build rule and the crate-source check; this block only configures the selected Rust core.
  AC_ARG_VAR([CARGO],[cargo]) AC_ARG_VAR([RUSTC],[rustc])
  AC_PATH_PROG([CARGO],[cargo]) AC_PATH_PROG([RUSTC],[rustc])        dnl run by ZK_RUST_CHECK; version gate: >= 1.85
  AC_ARG_WITH([rust-staticlib],
    [AS_HELP_STRING([--with-rust-staticlib=PATH],[link this libzk_ffi.a (built by Maven); make never builds the Rust staticlib])],
    [zk_rust_requested_by=${zk_rust_requested_by:-"--with-rust-staticlib=$withval"}])
  AC_ARG_WITH([rust-target],
    [AS_HELP_STRING([--with-rust-target=TRIPLE],[pass --target TRIPLE to cargo (required for cross builds)])],
    [zk_rust_requested_by=${zk_rust_requested_by:-"--with-rust-target=$withval"}])
  dnl RUST_TARGET: the option, else `$RUSTC -vV | sed -n "s/^host: //p"` (host build).
  dnl Cross build ($host != $build) without --with-rust-target: a failed ZK_RUST_CHECK.
  dnl ZKRS_TARGET_ARGS: "--target $RUST_TARGET" only when --with-rust-target is given.
  AC_ARG_WITH([cargo-vendor],[AS_HELP_STRING([--with-cargo-vendor=DIR],[offline build from a cargo vendor directory])],
    [zk_rust_requested_by=${zk_rust_requested_by:-"--with-cargo-vendor=$withval"}])
  AC_ARG_WITH([system-rust-crates],
    [AS_HELP_STRING([--with-system-rust-crates[=DIR]],[offline build from distribution crates (default DIR: /usr/share/cargo/registry)])],
    [zk_rust_requested_by=${zk_rust_requested_by:-"--with-system-rust-crates=$withval"}
     AS_CASE([$withval], [yes], [with_system_rust_crates=/usr/share/cargo/registry])], [with_system_rust_crates=no])
  dnl CARGO_SOURCE_ARGS (section 3.10, 3.11):
  dnl   --with-cargo-vendor=DIR: "--offline --config source.crates-io.replace-with=... --config source.vendored.directory=DIR"
  dnl   --with-system-rust-crates: writes $(abs_builddir)/cargo/system-crates.toml ("directory = DIR", or
  dnl     "local-registry = DIR" when DIR/index exists) and sets "--offline --config <that file>";
  dnl     DIR must exist, else AC_MSG_ERROR
  dnl   both options together: AC_MSG_ERROR
  AC_ARG_ENABLE([werror],
    [AS_HELP_STRING([--disable-werror],[do not add -Werror to the shim and SASL glue flags])],
    [], [enable_werror=yes])
  dnl ZKS_WERROR = -Werror iff enable_werror != no (section 4.4); the legacy core keeps AM_CFLAGS
  AC_ARG_WITH([rust-tls-provider],
    [AS_HELP_STRING([--with-rust-tls-provider=ring|aws-lc-rs|aws-lc-rs-fips],[TLS crypto provider (default: ring)])],
    [zk_rust_requested_by=${zk_rust_requested_by:-"--with-rust-tls-provider=$withval"}],
    [with_rust_tls_provider=ring])   dnl action-if-given: a named provider, ring included, is a request
  AC_ARG_WITH([rust-tls],
    [AS_HELP_STRING([--with-rust-tls=yes|no],[enable TLS without the OpenSSL probe (default: no)])],
    [AS_IF([test "x$withval" = xyes], [zk_rust_requested_by=${zk_rust_requested_by:-"--with-rust-tls=yes"}])],
    [with_rust_tls=no])
  AC_ARG_WITH([rust-tls-default-paths],
    [AS_HELP_STRING([--with-rust-tls-default-paths=FILE:DIR],[default trust locations (default: from OPENSSLDIR)])],
    [zk_rust_requested_by=${zk_rust_requested_by:-"--with-rust-tls-default-paths=$withval"}])
  dnl TLS decision (section 4.8). The legacy OpenSSL block (configure.ac:42-67) runs unchanged before
  dnl this block; it sets with_openssl=no and warns "cannot build SSL support -- openssl not found"
  dnl when openssl/ssl.h or -lssl SSL_CTX_new is missing. zk_user_with_openssl keeps the value given.
  dnl ZKRS_TLS=yes when with_openssl != no after that block, or when with_rust_tls = yes.
  dnl with_rust_tls = yes with zk_user_with_openssl = no: AC_MSG_ERROR (contradictory requests).
  dnl Rust core: the probe's CPPFLAGS/LDFLAGS are restored after the ZK_OPENSSL_DIR_* save above, so
  dnl   they never reach the shim or the Rust-core link line; OPENSSL_LIB_LDFLAGS (-lssl -lcrypto) is
  dnl   used by the legacy core only; OPENSSL_CPPFLAGS follows the WANT_OPENSSL rule below.
  dnl side-by-side: the one decision applies to both trees.
  dnl side-by-side with with_rust_tls = yes when the legacy probe found no OpenSSL: AC_MSG_ERROR, because
  dnl   both trees share one .sym and the legacy tree cannot build TLS without OpenSSL.
  dnl HAVE_SSL01 = 1 iff ZKRS_TLS = yes (AC_SUBST after this block); ZKS_FEATURE_CPPFLAGS gains
  dnl   -DZKS_WITH_TLS iff ZKRS_TLS = yes.
  dnl Rust core: WANT_OPENSSL is true iff ZKRS_TLS = yes, so OPENSSL_CPPFLAGS (-DHAVE_OPENSSL_H) is in
  dnl   AM_CPPFLAGS exactly when TLS is built, as legacy Makefile.am:11-12, 22.
  dnl Rust core: after the TLS decision, re-issue AM_CONDITIONAL([WANT_OPENSSL],[test "x$ZKRS_TLS" = xyes]);
  dnl   the block at configure.ac:42-67 is otherwise unchanged.
  dnl ZK_TLS_DEFAULT_CERT_FILE, ZK_TLS_DEFAULT_CERT_DIR: --with-rust-tls-default-paths, else from the
  dnl   OPENSSLDIR that `openssl version -d` prints (when the selected openssl command exists:
  dnl   $with_openssl/bin/openssl for --with-openssl=DIR; the first openssl on PATH only when no
  dnl   installation is named; a PATH binary is never used for a named installation), as $OPENSSLDIR/cert.pem
  dnl   and $OPENSSLDIR/certs, else from ZK_TLS_PLATFORM_DIRS below; else both empty (no default
  dnl   location). Windows hosts record nothing. Passed to cargo in the environment, beside ZK_VERSION_H.
  dnl ZK_TLS_PLATFORM_DIRS="/usr/lib/ssl /etc/pki/tls /etc/ssl /usr/local/ssl /usr/local/etc/openssl@3 /opt/homebrew/etc/openssl@3"
  dnl   The platform list. Applied on the build host only when the selected openssl binary does not exist: the first
  dnl   entry <dir> that exists gives <dir>/cert.pem and <dir>/certs. Nothing is probed at run time.
  dnl   CMake (ZkRust.cmake), abi/tls-probe.sh and zk-driver/build.rs use the same list in the same order.
  AS_CASE([$with_rust_tls_provider],
    [ring], [ZKRS_TLS_PROVIDER_FEATURE=tls-ring],  dnl a named ring on a target outside the ring list: AC_MSG_ERROR
    [aws-lc-rs], [ZKRS_TLS_PROVIDER_FEATURE=tls-aws-lc-rs],     dnl also checks the aws-lc-sys build tools
    [aws-lc-rs-fips], [ZKRS_TLS_PROVIDER_FEATURE=tls-aws-lc-rs-fips], dnl section 4.10: Linux x86_64/aarch64
                                                                  dnl   targets only; checks cmake and go
                                                                  dnl a named provider is mandatory:
                                                                  dnl   a failed check is AC_MSG_ERROR; with
                                                                  dnl   TLS off, a notice that it is unused
    [AC_MSG_ERROR([--with-rust-tls-provider must be ring, aws-lc-rs or aws-lc-rs-fips])])
  dnl ZKRS_CARGO_FEATURES: "--features tls,$ZKRS_TLS_PROVIDER_FEATURE" iff ZKRS_TLS = yes, else ""
  ZKRS_PROFILE=capi; test "x$enable_debug" = xyes && ZKRS_PROFILE=capi-dev
  dnl ZKRS_STATICLIB: --with-rust-staticlib, else
  dnl   $(abs_builddir)/cargo/$(ZKRS_PROFILE)/libzk_ffi.a              (host build)
  dnl   $(abs_builddir)/cargo/$(RUST_TARGET)/$(ZKRS_PROFILE)/libzk_ffi.a (--with-rust-target)
  dnl Prebuilt lib: the file must exist, and $NM must show zkrs_feature_tls iff TLS is on (else error).
  AC_CHECK_TOOL([OBJCOPY],[objcopy])                                  dnl required on ELF/PE hosts
  AC_CHECK_TOOL([NM],[nm])
  ZK_VERSION_H='$(abs_srcdir)/include/zookeeper_version.h'           dnl passed to cargo (section 9)
  AC_SUBST([ZK_TLS_DEFAULT_CERT_FILE]) AC_SUBST([ZK_TLS_DEFAULT_CERT_DIR])
  dnl probe native-static-libs -> ZKRS_NATIVE_LIBS; probe -fsemantic-interposition, -fexceptions -> ZKS_CFLAGS
  dnl probe each hardening flag -> ZKS_HARDEN_CFLAGS, ZKS_HARDEN_SASL_CFLAGS (section 4.9);
  dnl   AC_MSG_NOTICE for each flag that the compiler rejects and that is dropped
  dnl ZKRS_TLS = yes and header-only probe openssl/ossl_typ.h (run with
  dnl   CPPFLAGS="$CPPFLAGS $ZK_OPENSSL_DIR_CPPFLAGS") -> ZK_CLI_SSL_CPPFLAGS =
  dnl   "-DHAVE_OPENSSL_H $ZK_OPENSSL_DIR_CPPFLAGS" (cli.c, load_gen.c only)
  dnl RUST_TARGET_ENV: RUST_TARGET with - replaced by _, for CC_<triple> and AR_<triple>
  AC_SUBST([RUST_TARGET]) AC_SUBST([RUST_TARGET_ENV]) AC_SUBST([ZKRS_TARGET_ARGS]) AC_SUBST([ZKRS_PROFILE])
  AC_SUBST([ZKRS_STATICLIB]) AC_SUBST([ZKRS_NATIVE_LIBS]) AC_SUBST([CARGO_SOURCE_ARGS])
  AC_SUBST([ZKRS_CARGO_FEATURES]) AC_SUBST([ZKS_FEATURE_CPPFLAGS]) AC_SUBST([RUST_WORKSPACE])
  dnl --enable-debug also adds -DZKS_HANDLE_REGISTRY to ZKS_FEATURE_CPPFLAGS (section 4.4)
  AC_SUBST([ZKS_CFLAGS]) AC_SUBST([ZKS_HARDEN_CFLAGS]) AC_SUBST([ZKS_HARDEN_SASL_CFLAGS]) AC_SUBST([ZKS_WERROR])
  AC_SUBST([ZKRS_GC_LDFLAGS]) AC_SUBST([ZK_VERSION_H]) AC_SUBST([ZK_CLI_SSL_CPPFLAGS])
])
dnl Export-list feature flags for gen-exports.awk (section 6.2). Both cores take their exports from the
dnl generated .sym, so these are set in every configuration. The legacy block leaves with_openssl=no and
dnl the SASL block leaves with_sasl=no when the probe fails (configure.ac:58-61, 176-179).
AS_IF([test "x$with_openssl" != xno], [ZK_LEGACY_SSL01=1], [ZK_LEGACY_SSL01=0])
AS_IF([test "x$have_sasl" = xyes && test "x$with_sasl" != xno], [HAVE_SASL01=1], [HAVE_SASL01=0])
AS_IF([test "x$with_rust_client" = xno], [HAVE_SSL01=$ZK_LEGACY_SSL01])
AC_SUBST([HAVE_SSL01]) AC_SUBST([HAVE_SASL01])
AM_INIT_AUTOMAKE([... subdir-objects])                                dnl for rs/ in side-by-side mode
AC_CONFIG_FILES([Makefile zookeeper_mt.pc zookeeper_st.pc])
```

**Core selection: detect and require.** configure builds what the host can build and honors what the user requires. `--with-rust-client` takes `no`, `yes`, `side-by-side` or `auto` (`true` means `yes`). The default is `no` before M5 and `auto` from M5 (§6.5).

- **The check.** `ZK_RUST_CHECK` (a macro added to the existing `acinclude.m4`) decides whether this host can build the Rust core. It passes when the host is not Cygwin, the Rust workspace is found (`--with-rust-workspace`), its `[workspace.package]` version equals `ZOO_VERSION` (the check that `zk-ffi/build.rs` makes later, §9, §13.4), and one of these holds:
  - `--with-rust-staticlib=PATH` names an existing file and `rustc` (or `RUSTC`) is found, because configure uses it for `RUST_TARGET` and `--print native-static-libs` (§4.6). No version, cargo or crate check runs, and the prebuilt-staticlib checks of the Rust block apply.
  - `rustc` and `cargo` (or `RUSTC` and `CARGO`) are found and both report 1.85 or later; a cross build (`$host` ≠ `$build`) has `--with-rust-target`; when the check runs without `--with-rust-tls-provider`, the target is one that the default provider `ring` supports (the list recorded in `acinclude.m4` and `ZkRustDetect.cmake`, taken from ring's build script at the pinned version); and the crates are available, from `--with-cargo-vendor=DIR`, from `--with-system-rust-crates[=DIR]` (the directory exists), or else from `cargo fetch --locked` run in the workspace, which succeeds with network access or a populated `CARGO_HOME`.

  The first condition that fails sets `zk_rust_why`, which names it (for example "cargo and rustc 1.85 or later not found", "the crypto provider ring does not support target <triple>", or "no crate source: cargo fetch failed; give --with-cargo-vendor=DIR or --with-system-rust-crates"). In a release to which DV-BLD-7 applies, the check also fails on a MinGW host with a reason that names DV-BLD-7, as §6.3 does on WIN32, so `auto` builds the legacy core and `yes` and `side-by-side` stop.
- **`yes` and `side-by-side` are mandatory.** A failed check stops configure with `zk_rust_why`, and the message names `--with-rust-client=no`.
- **`auto` detects.** A passed check selects the Rust core (`yes`). A failed check prints `building the legacy C client: <reason>` and selects the legacy core, except when a Rust-only option was given explicitly (`--with-rust-tls=yes`, `--with-rust-tls-provider` (any value, `ring` included), `--with-rust-tls-default-paths`, `--with-rust-workspace` (any value but `auto`), `--with-rust-staticlib`, `--with-rust-target`, `--with-cargo-vendor` or `--with-system-rust-crates`). Such an option is a requirement that the legacy core cannot meet, so configure stops with a message that names the option and the reason. `--disable-werror` / `ZK_WERROR=OFF` is not a request: it only relaxes flags of the Rust branch, and the legacy core ignores it.
- **`no`** selects the legacy core and runs no Rust check. When `--with-rust-client` is not given and the default is `no` (before M5), an explicit Rust-only option (the list of the `auto` bullet, `--with-rust-workspace` included) stops configure with `<option> needs --with-rust-client=yes`. An explicit `--with-rust-client=no` ignores them; Maven relies on this, because it passes them in every configuration (§13.7).

The same rule applies to each optional component of the Rust build. A component that the user names is mandatory, and configure stops with a clear message when the host cannot build it:

- `--with-rust-tls=yes` stops configure when it contradicts `--with-openssl=no`, or, in side-by-side mode, when the legacy probe found no OpenSSL (§4.8).
- `--with-rust-tls-provider=aws-lc-rs` stops configure when the aws-lc-sys build tools are missing, and `aws-lc-rs-fips` also when the target is not Linux x86_64 or aarch64 or CMake or Go is missing (§4.10). These checks run whenever the Rust core is selected. The provider takes effect only when TLS is built; when TLS is off, configure also prints a notice that the provider is not used. The provider `ring` needs only the C compiler and assembler that the shim build already uses, so it has no tool check. When no provider is named, its target condition is part of `ZK_RUST_CHECK` above. When the user names `ring` and gives no `--with-rust-staticlib`, configure stops with a message that names the option and the target if the target is not on the ring list.
- `--with-system-rust-crates` and `--with-cargo-vendor` stop configure when the directory does not exist, and giving both stops it too (§3.10, §3.11).

`--with-openssl` and `--with-sasl` keep their legacy behavior with both cores: when the library is not found, configure warns and builds without the feature, also when the option is given explicitly (configure.ac:58-61, 176-179). With the Rust core the OpenSSL result only decides TLS (§4.8).

**Default trust locations.** The `openssl` command that gives OPENSSLDIR is `DIR/bin/openssl` for `--with-openssl=DIR` (CMake: `${OPENSSL_ROOT_DIR}/bin/openssl` when `OPENSSL_ROOT_DIR` is set); when no installation is named, it is the first `openssl` on `PATH`. When the selected binary does not exist, the platform list applies; a `PATH` binary is never used for a named installation, because it can belong to a different library (for example LibreSSL on `PATH` and a Homebrew openssl@3 named by `--with-openssl=DIR` on macOS). The platform list is exactly `/usr/lib/ssl /etc/pki/tls /etc/ssl /usr/local/ssl /usr/local/etc/openssl@3 /opt/homebrew/etc/openssl@3`, in that order. configure and CMake apply it on the build host only when the selected `openssl` binary does not exist and no `--with-rust-tls-default-paths` / `-DZK_RUST_TLS_DEFAULT_PATHS` is given. The first entry `<dir>` that exists gives `FILE = <dir>/cert.pem` and `DIR = <dir>/certs`. When no entry exists, both values are empty. The build always passes both variables to cargo, so a drop-in build never falls back to `zk-driver/build.rs`. That build script handles native cargo builds: when `ZK_TLS_DEFAULT_CERT_FILE` and `ZK_TLS_DEFAULT_CERT_DIR` are both unset, it applies the same list on the build host and sets the two values with `cargo:rustc-env`; a variable that is set, even to the empty string, is used as given. It emits `cargo:rerun-if-env-changed` for both variables. The recorded values describe the build host, so cross builds pass `--with-rust-tls-default-paths` (or set the two variables) for the target. 06 §3.4 owns the loader rules that consume the values.

These options keep their meaning: `--with-openssl` (the legacy TLS autodetect, which decides the export of `zookeeper_init_ssl`; with the Rust core the decision selects the zk-ffi TLS features, §4.8), `--with-sasl`, `--with-syncapi`, `--with-sock-cloexec`, `--enable-asan` and `--enable-gcov`. ASan and gcov instrument the shim only. Rust instrumentation runs in separate CI jobs and in the Maven profiles `rust-test-asan` and `rust-test-coverage`.

### 6.2 Makefile.am (outline)

```make
EXPORT_GEN = $(AWK) -f $(srcdir)/abi/gen-exports.awk -v ssl=$(HAVE_SSL01) -v sasl=$(HAVE_SASL01) \
             $(srcdir)/abi/symbols.tsv $(srcdir)/abi/static-symbols.tsv
zookeeper_%.sym:  ... ; $(EXPORT_GEN) -v flavor=$* -v fmt=sym  > $@
zookeeper_%.keep: ... ; $(EXPORT_GEN) -v flavor=$* -v fmt=keep > $@

if RUST_CLIENT
ZKRS_STATICLIB = @ZKRS_STATICLIB@
if ZKRS_PREBUILT
# Built by Maven (zookeeper-client-rust). make never builds the Rust staticlib here.
$(ZKRS_STATICLIB):
	@test -f $@ || { echo "$@ is missing: build zookeeper-client-rust first" >&2; exit 1; }
else
# Standalone build: cargo output stays under the build directory.
.PHONY: zkrs-cargo
zkrs-cargo:
	CARGO_TARGET_DIR=$(abs_builddir)/cargo ZK_VERSION_H=$(ZK_VERSION_H) \
	  ZK_TLS_DEFAULT_CERT_FILE="$(ZK_TLS_DEFAULT_CERT_FILE)" ZK_TLS_DEFAULT_CERT_DIR="$(ZK_TLS_DEFAULT_CERT_DIR)" \
	  CC_$(RUST_TARGET_ENV)="$(CC)" AR_$(RUST_TARGET_ENV)="$(AR)" \
	  $(CARGO) build --manifest-path $(RUST_WORKSPACE)/Cargo.toml -p zk-ffi $(ZKRS_CARGO_FEATURES) \
	  --profile $(ZKRS_PROFILE) $(ZKRS_TARGET_ARGS) --locked $(CARGO_SOURCE_ARGS)
$(ZKRS_STATICLIB): zkrs-cargo
if ZKRS_SYSTEM_CRATES
# System-crates mode (section 3.11): regenerate Cargo.lock against the distribution crates once.
zkrs-cargo: cargo/system-crates.lock-stamp
cargo/system-crates.lock-stamp: cargo/system-crates.toml
	$(CARGO) generate-lockfile --manifest-path $(RUST_WORKSPACE)/Cargo.toml $(CARGO_SOURCE_ARGS) && touch $@
endif
endif

# ABI checks (section 5). Under Maven the C module passes CARGO_SOURCE_ARGS on the make command line (section 13.7).
.PHONY: abi-check
abi-check:
	cd $(RUST_WORKSPACE) && $(CARGO) $(CARGO_SOURCE_ARGS) xtask abi ...

# Feature defines as legacy (Makefile.am:11-22): OPENSSL_CPPFLAGS = -DHAVE_OPENSSL_H when TLS is built,
# SASL_CPPFLAGS = -DHAVE_CYRUS_SASL_H when SASL is built, both in the library CPPFLAGS:
#   AM_CPPFLAGS = -I${srcdir}/include -I${srcdir}/tests -I${srcdir}/generated $(SOLARIS_CPPFLAGS) \
#                 $(OPENSSL_CPPFLAGS) $(SASL_CPPFLAGS)
# Shim TUs use per-target CPPFLAGS. They do not depend on HAVE_OPENSSL_H (ZKS_WITH_TLS gives TLS
# presence). Only the SASL glue gets HAVE_CYRUS_SASL_H, so <sasl/sasl.h> stays in two TUs (section 4.9).
ZKS_CPPFLAGS = -I$(srcdir)/include -I$(srcdir)/generated -I$(srcdir)/shim $(SOLARIS_CPPFLAGS) $(ZKS_FEATURE_CPPFLAGS)
ZKS_OBJFLAGS = -prefer-pic -ffunction-sections -fdata-sections
SHIM_SRC = shim/zks_data.c shim/zks_handle.c shim/zks_async.c shim/zks_multi.c shim/zks_drain.c \
  shim/zks_log.c shim/zks_env.c shim/zks_errors.c shim/zks_resolve.c shim/zks_rng.c \
  shim/zks_compat.c shim/zks_layout_check.c
# Shim warning set: legacy AM_CFLAGS (Makefile.am:23, 28-29) with -Werror from ZKS_WERROR
# (--disable-werror, section 4.4); per-target CFLAGS replace AM_CFLAGS, so gcov is added here too.
ZKS_AM_CFLAGS = -Wall $(ZKS_WERROR) -Wdeclaration-after-statement
if ENABLEGCOV
ZKS_AM_CFLAGS += -fprofile-arcs -ftest-coverage
endif
SHIM_MT_SRC   = shim/zks_sync.c shim/zks_threads.c
SASL_SHIM_SRC = shim/zks_sasl_cyrus.c shim/zks_sasl_cb.c

noinst_LTLIBRARIES = libzks_jute.la libzks_st.la $(SASL_SHIM_LIB_st)
libzks_jute_la_SOURCES  = src/recordio.c generated/zookeeper.jute.c        # legacy flags, verbatim
libzks_jute_la_CPPFLAGS = $(AM_CPPFLAGS)                                   # as legacy; no zookeeper.h include
libzks_jute_la_CFLAGS   = $(AM_CFLAGS) $(ZKS_OBJFLAGS)
libzks_sasl_st_la_SOURCES  = $(SASL_SHIM_SRC)                             # if WANT_SASL
libzks_sasl_st_la_CPPFLAGS = $(ZKS_CPPFLAGS) -DHAVE_CYRUS_SASL_H $(SASL_ISYSTEM)
libzks_sasl_st_la_CFLAGS   = $(ZKS_AM_CFLAGS) $(ZKS_CFLAGS) $(ZKS_HARDEN_SASL_CFLAGS) $(ZKS_OBJFLAGS)
libzks_st_la_SOURCES  = $(SHIM_SRC)
libzks_st_la_CPPFLAGS = $(ZKS_CPPFLAGS)
libzks_st_la_CFLAGS   = $(ZKS_AM_CFLAGS) $(ZKS_CFLAGS) $(ZKS_HARDEN_CFLAGS) $(ZKS_OBJFLAGS)
libzks_st_la_LIBADD   = libzks_jute.la $(SASL_SHIM_LIB_st)
zookeeper_st_all.lo: libzks_st.la $(ZKRS_STATICLIB) zookeeper_st.keep $(srcdir)/abi/prelink.sh
	$(SHELL) $(srcdir)/abi/prelink.sh --cc "$(CC)" --ld "$(LD)" --objcopy "$(OBJCOPY)" --host-os "$(host_os)" \
	  --shim .libs/libzks_st.a --rust $(ZKRS_STATICLIB) --keep zookeeper_st.keep --out zookeeper_st_all
# mt: libzks_mt.la = $(SHIM_SRC) $(SHIM_MT_SRC) with -DTHREADED, plus libzks_sasl_mt.la; zookeeper_mt_all.lo likewise
ZKRS_LIB_LDFLAGS = -no-undefined -version-info 2 $(SOLARIS_LIB_LDFLAGS) $(SASL_LIB_LDFLAGS)   # no -lssl -lcrypto
ZKRS_LIBADD_st = zookeeper_st_all.lo -lm $(CLOCK_GETTIME_LIBS) $(ZKRS_NATIVE_LIBS)
ZKRS_LIBADD_mt = zookeeper_mt_all.lo -lm $(CLOCK_GETTIME_LIBS) -lpthread $(ZKRS_NATIVE_LIBS)
if RUST_SIDE_BY_SIDE
zkrslibdir = $(libdir)/zookeeper-rs                   # same SONAME; selected via LD_LIBRARY_PATH
zkrslib_LTLIBRARIES = rs/libzookeeper_st.la $(RS_MT)
rs_libzookeeper_st_la_SOURCES =
rs_libzookeeper_st_la_LIBADD  = $(ZKRS_LIBADD_st)
rs_libzookeeper_st_la_LDFLAGS = $(ZKRS_LIB_LDFLAGS) -export-symbols zookeeper_st.sym $(ZKRS_GC_LDFLAGS)
else
lib_LTLIBRARIES = libzookeeper_st.la $(MT_LIB)        # the Rust client is the $(libdir) library
libzookeeper_st_la_SOURCES =
libzookeeper_st_la_LIBADD  = $(ZKRS_LIBADD_st)
libzookeeper_st_la_LDFLAGS = $(ZKRS_LIB_LDFLAGS) -export-symbols zookeeper_st.sym $(ZKRS_GC_LDFLAGS)
endif
ZKS_CPPFLAGS_CLI = -I$(srcdir)/include -I$(srcdir)/generated $(SOLARIS_CPPFLAGS) $(SASL_CPPFLAGS)
cli_st_CPPFLAGS = $(ZKS_CPPFLAGS_CLI) $(ZK_CLI_SSL_CPPFLAGS)               # HAVE_OPENSSL_H only with headers
endif
if LEGACY_CLIENT
# legacy core sources, behavior and export set unchanged; the export list is generated (.sym).
# The --with-openssl=DIR flags that legacy keeps in the global CPPFLAGS/LDFLAGS come back per target.
libzkst_la_CPPFLAGS        = $(AM_CPPFLAGS) $(ZK_OPENSSL_DIR_CPPFLAGS)       # libzkmt likewise
libzookeeper_st_la_LDFLAGS = $(LIB_LDFLAGS) $(ZK_OPENSSL_DIR_LDFLAGS) -export-symbols zookeeper_st.sym
endif

pkgconfig_DATA = zookeeper_st.pc $(MT_PC)               # additive
EXTRA_DIST += abi shim/zkrs.h shim/zks_internal.h shim/zks_log_sites.h shim/zks_sasl.h shim/zks_sasl_cb.h \
  shim/analyzer-suppressions.txt shim/zks_hashtable_stub.c cmake CMakeLists.txt fuzz  # needed by both cores and by the shim build
dist-hook:                                              # portable (GNU tar and bsdtar)
	if test -d $(srcdir)/../zookeeper-client-rust; then \
	  mkdir -p $(distdir)/rust && \
	  tar -C $(srcdir)/../zookeeper-client-rust -cf - --exclude ./target --exclude ./fuzz/target --exclude ./crates/zk-ffi-layout/target --exclude .git . \
	    | tar -C $(distdir)/rust -xf - ; fi
```

- `pkginclude_HEADERS` is unchanged (Makefile.am:33). Side-by-side installs one header tree and two library trees.
- The `.pc` files carry `Libs.private: $(ZKRS_NATIVE_LIBS)` and `-lsasl2` when SASL is on, and no OpenSSL entry. Their `Cflags` define neither `HAVE_OPENSSL_H` nor `HAVE_CYRUS_SASL_H`; autotools consumers define them themselves, as in legacy.
- `cli_*` and `load_gen` link the `$(libdir)` library of the selected mode.
- The `make dist` tarball copies the workspace as it stands, so it includes `crates/zk-jute/src/generated/` when the jute sources have been generated; a tarball made without them fails in zk-jute's build script with the §8 message.
- `make check`:
  - legacy mode runs `zktest-{st,mt}` and `zktest-public-*`;
  - Rust mode runs `cargo test --locked --workspace --exclude zk-ffi` and `cargo test --locked -p zk-ffi $(ZKRS_CARGO_FEATURES)` (the configured TLS feature set; both skipped under Maven, where the `cargo-test` profile already ran them, §13.3), plus the conformance and public binaries against `libzookeeper_{st,mt}.la`;
  - side-by-side mode runs the public tests against both trees and the differential conformance suite with the pinned reference build of 08 §4.1 as the legacy side (08 owns the tier).
- `make fuzz` builds `fuzz/sasl_glue` and `fuzz/sasl_cb_block` when the compiler is clang and SASL is on.

### 6.3 CMake

```cmake
cmake_minimum_required(VERSION 3.15)                    # CMakeLists.txt:17; every configuration, both cores

# WITH_RUST_CLIENT: ON, OFF or AUTO. Unset means OFF before M5 and AUTO from M5 (section 6.5).
if(NOT DEFINED WITH_RUST_CLIENT)
  set(ZKRS_MODE OFF)                                    # from M5: AUTO
  set(ZKRS_MODE_DEFAULT ON)
elseif(WITH_RUST_CLIENT STREQUAL "AUTO")
  set(ZKRS_MODE AUTO)
elseif(WITH_RUST_CLIENT MATCHES "^(1|ON|YES|TRUE|Y|on|yes|true|y)$")
  set(ZKRS_MODE ON)
elseif(WITH_RUST_CLIENT MATCHES "^(0|OFF|NO|FALSE|N|off|no|false|n)$")
  set(ZKRS_MODE OFF)
else()
  message(FATAL_ERROR "WITH_RUST_CLIENT must be ON, OFF or AUTO")
endif()
option(ZOOKEEPER_BUILD_SHARED "Also build zookeeper_{mt,st} shared libraries" OFF)
# ZK_RUST_TLS_PROVIDER: ring, aws-lc-rs or aws-lc-rs-fips. A value the user sets (-D or a cache preset),
# ring included, is a request for the Rust core. The default is a normal variable, not a cache entry,
# so that a later configure run does not see the default as a user request (the WITH_RUST_CLIENT pattern).
if(DEFINED CACHE{ZK_RUST_TLS_PROVIDER} OR DEFINED ZK_RUST_TLS_PROVIDER)
  set(ZKRS_PROVIDER_GIVEN ON)
else()
  set(ZK_RUST_TLS_PROVIDER "ring")
endif()
set(ZKRS_WORKSPACE "" CACHE PATH "Rust workspace; empty means rust, then ../zookeeper-client-rust")
set(ZKRS_STATICLIB "" CACHE FILEPATH "Prebuilt libzk_ffi.a; empty means CMake runs cargo")
option(WITH_RUST_TLS "Enable TLS in the Rust client without the OpenSSL probe" OFF)
set(ZK_RUST_TLS_DEFAULT_PATHS "" CACHE STRING "FILE:DIR default trust locations; empty means from OPENSSLDIR")
set(ZOOKEEPER_CARGO_VENDOR "" CACHE PATH "cargo vendor directory for an offline build (section 3.10)")
set(ZK_SYSTEM_RUST_CRATES "" CACHE PATH "Distribution crate directory; empty means off (section 3.11)")
option(ZK_WERROR "Add -Werror (/WX) to the shim and SASL glue flags" ON)
set(ZKRS_CORE OFF)
include(ZkRustDetect)     # zk_rust_requested_by(ZKRS_REQUESTED_BY) names the first of WITH_RUST_TLS=ON,
                          # ZOOKEEPER_BUILD_SHARED=ON, ZK_RUST_TLS_PROVIDER (any value; ZKRS_PROVIDER_GIVEN),
                          # ZK_RUST_TLS_DEFAULT_PATHS (non-empty), ZKRS_WORKSPACE (non-empty), ZKRS_STATICLIB,
                          # Rust_TARGET, ZOOKEEPER_CARGO_VENDOR and ZK_SYSTEM_RUST_CRATES that is set;
                          # zk_rust_check(ZKRS_OK ZKRS_WHY): the configure check of section 6.1; an empty
                          # ZKRS_WORKSPACE resolves to the directory found, as a normal variable
zk_rust_requested_by(ZKRS_REQUESTED_BY)
if(ZKRS_MODE_DEFAULT AND ZKRS_MODE STREQUAL "OFF" AND ZKRS_REQUESTED_BY)
  message(FATAL_ERROR "${ZKRS_REQUESTED_BY} needs -DWITH_RUST_CLIENT=ON")
endif()
if(NOT ZKRS_MODE STREQUAL "OFF")
  zk_rust_check(ZKRS_OK ZKRS_WHY)
  if(ZKRS_OK)
    set(ZKRS_CORE ON)
  elseif(ZKRS_MODE STREQUAL "ON")
    message(FATAL_ERROR "WITH_RUST_CLIENT=ON: ${ZKRS_WHY}; -DWITH_RUST_CLIENT=OFF builds the legacy C client")
  elseif(ZKRS_REQUESTED_BY)
    message(FATAL_ERROR "${ZKRS_REQUESTED_BY} needs the Rust client, which this host cannot build: ${ZKRS_WHY}")
  else()
    message("-- building the legacy C client: ${ZKRS_WHY}")   # legacy message style (CMakeLists.txt:77, 217)
  endif()
endif()
if(ZKRS_CORE)
  include(ZkRust)       # cargo/rustc; Rust_TARGET (only if set: --target); profile capi|capi-dev;
                        # +crt-static; ZK_VERSION_H; CC_/AR_<triple>; ZKRS_CARGO_ARGS from
                        # ZOOKEEPER_CARGO_VENDOR or ZK_SYSTEM_RUST_CRATES (both set: FATAL_ERROR), with the
                        # generated system-crates.toml and the generate-lockfile step of section 3.11
  include(ZkHarden)     # probes ZKS_HARDEN_CFLAGS and ZKS_HARDEN_SASL_CFLAGS; a message for each dropped
                        # flag; ZK_WERROR; a Debug configuration adds ZKS_HANDLE_REGISTRY through
                        # target_compile_definitions(<shim targets> PRIVATE
                        # $<$<CONFIG:Debug>:ZKS_HANDLE_REGISTRY>), single- and multi-config (section 4.4)
  zk_rust_check_provider(${ZK_RUST_TLS_PROVIDER})  # section 4.10 checks (aws-lc-rs-fips: Linux x86_64/aarch64
                        # targets only, needs cmake and go; a named ring: target on the ring list);
                        # FATAL_ERROR on failure; runs whether or not TLS is built, and the TLS decision
                        # below says when the provider is unused
  # TLS decision (section 4.8): the option, find_package(OpenSSL) and the messages of the legacy block
  # (CMakeLists.txt:205-219) run here; its target_compile_definitions and target_link_libraries lines
  # (213, 214) do not, because the zookeeper target is created below and if(ZKRS_TLS) sets the definition.
  if(WITH_RUST_TLS AND WITH_OPENSSL STREQUAL "OFF")
    message(FATAL_ERROR "WITH_RUST_TLS=ON contradicts WITH_OPENSSL=OFF")
  endif()
  if((NOT WITH_OPENSSL STREQUAL "OFF" AND OPENSSL_FOUND) OR WITH_RUST_TLS)
    set(ZKRS_TLS ON)
    zk_rust_tls_features(ZKRS_TLS_FEATURES ${ZK_RUST_TLS_PROVIDER})   # --features tls,tls-<provider>
  elseif(ZKRS_PROVIDER_GIVEN)
    message("-- TLS is not built: ZK_RUST_TLS_PROVIDER=${ZK_RUST_TLS_PROVIDER} is not used")
  endif()
  zk_rust_tls_default_paths(ZKRS_TLS_ENV "${ZK_RUST_TLS_DEFAULT_PATHS}") # ZK_TLS_DEFAULT_CERT_{FILE,DIR}=...;
                        # option, else OPENSSLDIR from `openssl version -d` (${OPENSSL_ROOT_DIR}/bin/openssl
                        # when OPENSSL_ROOT_DIR is set, else the first openssl on PATH), else, when the
                        # selected binary does not exist, the platform list (section 6.1)
  if(ZKRS_STATICLIB)
    set(ZKRS_LIB ${ZKRS_STATICLIB})                                   # built elsewhere; checked like configure
  else()
    zk_rust_lib_path(ZKRS_LIB ${CMAKE_BINARY_DIR}/cargo)               # cargo/[<triple>/]<profile>/libzk_ffi.a
    add_custom_command(OUTPUT ${ZKRS_LIB} COMMAND ${CMAKE_COMMAND} -E env
          CARGO_TARGET_DIR=${CMAKE_BINARY_DIR}/cargo ZK_VERSION_H=${ZK_VERSION_H} ${ZKRS_TLS_ENV} ${ZKRS_CC_ENV}
          ${CARGO_EXECUTABLE} build -p zk-ffi ${ZKRS_TLS_FEATURES} --profile ${ZKRS_PROFILE}
          ${ZKRS_TARGET_ARGS} --locked ${ZKRS_CARGO_ARGS}
          WORKING_DIRECTORY ${ZKRS_WORKSPACE} DEPENDS ${ZKRS_RS_SOURCES} USES_TERMINAL)
  endif()
  add_library(zks_jute OBJECT src/recordio.c generated/zookeeper.jute.c)          # legacy flags
  add_library(zks_${flavor} STATIC ${shim_sources_for_flavor} $<TARGET_OBJECTS:zks_jute>)  # PIC, sections
  target_compile_options(zks_${flavor} PRIVATE ${ZKS_HARDEN_CFLAGS})
  if(CYRUS_SASL_FOUND)
    set_source_files_properties(shim/zks_sasl_cyrus.c shim/zks_sasl_cb.c PROPERTIES
        COMPILE_DEFINITIONS HAVE_CYRUS_SASL_H COMPILE_OPTIONS "${ZKS_HARDEN_SASL_CFLAGS}")
  endif()
  if(NOT MSVC)
    add_custom_command(OUTPUT ${ZK_ALL_OBJ} COMMAND sh ${CMAKE_CURRENT_SOURCE_DIR}/abi/prelink.sh ...
                       DEPENDS zks_${flavor} ${ZKRS_LIB})
    add_library(zookeeper STATIC ${ZK_ALL_OBJ})                         # EXTERNAL_OBJECT, one member
  else()
    add_library(zookeeper STATIC $<TARGET_OBJECTS:zks_${flavor}> $<TARGET_OBJECTS:zks_jute>)
    set_property(TARGET zookeeper APPEND PROPERTY STATIC_LIBRARY_OPTIONS ${ZKRS_LIB})   # DV-BLD-3
  endif()
  target_link_libraries(zookeeper PUBLIC ${ZKRS_NATIVE_LIBS})          # no OpenSSL::SSL / OpenSSL::Crypto
  if(ZKRS_TLS)
    target_compile_definitions(zookeeper PUBLIC HAVE_OPENSSL_H)        # legacy visibility (CMakeLists.txt:213)
    find_path(ZK_OPENSSL_INCLUDE_DIR openssl/ossl_typ.h HINTS ${OPENSSL_INCLUDE_DIR})   # headers only
    if(ZK_OPENSSL_INCLUDE_DIR)
      target_include_directories(zookeeper PUBLIC ${ZK_OPENSSL_INCLUDE_DIR})   # as the legacy PUBLIC OpenSSL::SSL
    else()                                                             # only possible with WITH_RUST_TLS
      message(WARNING "consumers need OpenSSL headers to compile against zookeeper.h; cli and load_gen skipped")
    endif()
  endif()
  if(CYRUS_SASL_FOUND)
    target_compile_definitions(zookeeper PUBLIC HAVE_CYRUS_SASL_H)     # legacy visibility (CMakeLists.txt:227)
    target_link_libraries(zookeeper PUBLIC CyrusSASL)
  endif()
  add_library(hashtable STATIC shim/zks_hashtable_stub.c)              # B3: no globals
  if(ZOOKEEPER_BUILD_SHARED) ... SOVERSION 2, MACHO versions 3.0.0, zk_apply_exports() ... endif()
endif()
```

- **Kept:** the target names `zookeeper` and `hashtable`, the directory-scoped `THREADED`/`USE_STATIC_LIB`, `HAVE_OPENSSL_H`/`HAVE_CYRUS_SASL_H` as PUBLIC compile definitions of the `zookeeper` target (legacy visibility), the `WITH_OPENSSL` option with its default `ON` and its `find_package(OpenSSL)` probe, and one flavor per configure via `WANT_SYNCAPI`. `HAVE_OPENSSL_H` is set when TLS is built (§4.8), which includes `WITH_RUST_TLS=ON`. The shim TUs are compiled in the `zks_<flavor>` targets and do not depend on `HAVE_OPENSSL_H`. The two SASL TUs get `HAVE_CYRUS_SASL_H` through their source properties, and in every other shim TU `zks_internal.h` undefines it before `zookeeper.h` (05 §2), so `<sasl/sasl.h>` stays in those two TUs (§4.9).
- **Rust core differences:** the probe only decides TLS, and the `zookeeper` target does not link `OpenSSL::SSL OpenSSL::Crypto` (DV-TLS-4). The OpenSSL include directory reaches consumers through the `zookeeper` target's PUBLIC include directories instead of through that link. `WITH_RUST_TLS` (default `OFF`) turns TLS on without `OPENSSL_FOUND`. The in-tree `cli` and `load_gen` targets inherit the PUBLIC `HAVE_OPENSSL_H`, as in legacy. With `WITH_RUST_TLS=ON` on a host without OpenSSL headers they cannot compile, so CMake skips them and says so in the same warning. Cargo output goes to `${CMAKE_BINARY_DIR}/cargo`; `--target` is passed only when `Rust_TARGET` is set.
- **CMake floor.** `zookeeper-client-c/CMakeLists.txt` has one `cmake_minimum_required(VERSION 3.15)`, at CMakeLists.txt:17, for every configuration, the legacy core included (DV-BLD-4). 3.15 is the oldest version with every CMake feature that the Rust branch uses, and `CMAKE_MSVC_RUNTIME_LIBRARY` sets the floor: the variable and its policy CMP0091 are new in 3.15, the CRT rule of §4.7 reads it, and the policy takes effect only when it is set before the first `project()`, so the minimum itself must be at least 3.15. The other version-bound features are older: `if(DEFINED CACHE{…})` (3.14), `STATIC_LIBRARY_OPTIONS` (3.13), the `COMPILE_OPTIONS` source-file property (3.11) and `USES_TERMINAL` on `add_custom_command` (3.2). The higher minimum does not change the legacy build: with the policies of 3.15, `/MD` stays the default MSVC runtime (CMP0091), and `/W3` still comes from the legacy `add_compile_options` (CMakeLists.txt:29; CMP0092).
- **Core selection.** `WITH_RUST_CLIENT` takes `ON`, `OFF` or `AUTO`, and unset means `OFF` before M5 and `AUTO` from M5 (§6.5). Any other value is a `FATAL_ERROR`. The rule is the configure rule of §6.1. `zk_rust_check` applies the configure check, including the `ring` target condition, which runs when the user has not set `ZK_RUST_TLS_PROVIDER`. `ON` is mandatory, and a failed check is a `FATAL_ERROR`. `AUTO` builds the Rust core when the check passes, and otherwise the legacy core with a `-- building the legacy C client: <reason>` message. The exception is an explicitly set Rust-only variable (`WITH_RUST_TLS=ON`, `ZOOKEEPER_BUILD_SHARED=ON`, `ZK_RUST_TLS_PROVIDER` set by the user to any value, `ring` included, `ZK_RUST_TLS_DEFAULT_PATHS` non-empty, `ZKRS_WORKSPACE` non-empty, `ZKRS_STATICLIB`, `Rust_TARGET`, `ZOOKEEPER_CARGO_VENDOR` or `ZK_SYSTEM_RUST_CRATES`): it is a requirement, so a failed check is then a `FATAL_ERROR` that names it. When `WITH_RUST_CLIENT` is unset and the default is `OFF` (before M5), such a variable is a `FATAL_ERROR` with `<variable> needs -DWITH_RUST_CLIENT=ON`. An explicit `-DWITH_RUST_CLIENT=OFF` ignores them. `ZK_WERROR=OFF` is not a request (§6.1). `ZK_RUST_TLS_PROVIDER` counts as set when it is defined before the CMake file supplies the default `ring`, which it does as a normal variable and not as a cache entry, so a later configure run does not count the default as a request. The detected result is not written back into the cache, so each configure run detects again. In a release to which DV-BLD-7 applies, or that ships before M4 exits, `zk_rust_check` fails on WIN32 with a reason that names the state (DV-BLD-7, or "the Windows port arrives at M4"), so `AUTO` builds the legacy core there and `ON` stops. The optional components follow §6.1: `WITH_RUST_TLS=ON` and a named TLS provider are mandatory. When the user names `ring` and gives no `ZKRS_STATICLIB`, CMake stops with a message that names the option and the target if the target is not on the ring list. `WITH_OPENSSL` and `WITH_CYRUS_SASL` keep their legacy warn-and-skip behavior (CMakeLists.txt:73-78, 211-218).
- Side-by-side is autotools-only, because CMake has no legacy install rules to sit beside.
- **Additive install rules:**
  - the six headers, plus `winconfig.h` on WIN32 only;
  - the libraries;
  - the `.pc` files;
  - `zookeeperConfig.cmake` exporting `zookeeper::zookeeper`.

### 6.4 Maven

Maven drives the Rust build the same way it drives the C client. §13 specifies the integration: the module pom, the root properties and profiles, the phase mapping, release and version rewriting, the assembly, the RAT excludes and the interaction with the C module's configure. The pieces above plug into it as follows:

- The Rust module builds the staticlib that the `ZKRS_PREBUILT` path of §6.2 (and `ZKRS_STATICLIB` in §6.3) links. The C module's configure receives it through `--with-rust-staticlib` and never builds the staticlib (§13.7).
- The Rust module derives the zk-ffi features from the same TLS decision as configure (§4.8, §13.7), including the root property `rust-tls`, which maps to `--with-rust-tls`. The configure check of the prebuilt staticlib (`zkrs_feature_tls` present iff TLS is on, §6.1) catches any mismatch.
- `make check` in Rust mode skips its cargo tests under Maven, because the `cargo-test` profile already ran them (§6.2, §13.3).

### 6.5 Rollout

Milestones and their build-specific gates (08 §15.3 owns the behavioral gates):

- **M0 (core and native API):** R1–R10 enforced in CI; the TLS-off closure goldens pass; cargo-deny, cargo-vet and the MSRV job are green; the native default-feature golden passes (the provider and `tls-platform-verifier` native goldens gate from M3; 08 §15.3 M0 (7)); the Maven jobs that pass `-Prust` build and lint the workspace. The Jenkins jobs pass `-Prust` once their agents have cargo (§13.8).
- **M1 (mt):** the whole-flavor prelink is proven on Linux x86_64/aarch64 (ELF), macOS and MinGW; libtool 2.4.6–2.5.x pass; `needed/<os>.txt` for the TLS-off, SASL-off configuration passes.
- **M2 (st and static archives):** S1–S3 and LINK-01..05 pass; the CMake `hashtable` archive passes B3; the s390x Jenkins job passes `-Prust` (§13.8).
- **M3 (TLS and SASL):**
  - TLS: the zk-driver `tls` module with `LegacyChainOnly` is complete; the TLS-on (`ring`) closure golden passes and every crate in it is `cargo-vet safe-to-deploy`; the native provider and `tls-platform-verifier` goldens pass; the prelink with provider objects passes §4.3 on every M1 target; the trust-decision differential corpus (OpenSSL `verify` against `LegacyChainOnly`, over the in-tree `ssl/gencerts.sh` set, a self-signed leaf in the CA file, expired and missing intermediates, wrong EKU and name mismatch, and the v1-leaf cases (08 TC-29 to TC-33 and TC-38 to TC-42, 06 §3.14.6)) agrees; the key-format corpus passes (unencrypted PKCS#1, SEC1 and PKCS#8; encrypted PKCS#8 with PBES2, with each PBKDF2 PRF HMAC-SHA1, -SHA224, -SHA256, -SHA384 and -SHA512 and with scrypt; traditional PEM with AES-CBC, DES-EDE3-CBC and DES-CBC; scrypt parameters whose memory need is over 32 MiB rejected before allocation; PBES1 rejected; rejected forms log the legacy line);
  - linkage: `needed/<os>.txt` in every configuration has no libssl or libcrypto and keeps libsasl2 when SASL is on (E9); no OpenSSL symbol is undefined; the shim compiles without OpenSSL headers; `xtask abi headers` passes the `zookeeper_init_ssl` prototype check; the TLS decision matches the legacy autodetect on hosts with and without OpenSSL headers, for autotools and CMake;
  - SASL: the two-TU isolation check passes; the compile-command audit shows the §4.9 flag sets; `fuzz/sasl_glue` (F12) runs 24 h under ASan and UBSan with no open crash, as do F13-F16 (08 §15.3 M3 (7)); the SASL conformance tests are clean under ASan, UBSan and Valgrind (and MSan where Cyrus is instrumented); the static analyzers are clean on `shim/`;
  - aws-lc providers: the aws-lc-rs and aws-lc-rs-fips builds pass IS-7 and TI-09, and the FIPS build also passes TK-18 (08 §15.3 M3 (4)), each with its own closure golden, every crate of which is `cargo-vet safe-to-deploy` (08 §15.3 M3 (11)), and the same TLS corpus, the FIPS build on Linux x86_64 and aarch64 (§4.10); a release includes either build only after the ASF legal review of aws-lc-sys and aws-lc-fips-sys passes (00 §6);
  - supply chain and build selection: the system-crates, offline-vendor, build-selection and lib-package CI jobs are green (08 §14, BLD-SEL-01).
- **First release with the opt-in** (08 §15.3): the release-note text of every entry of 00 §5.1 that applies to the release (DV-ABI, DV-BLD, DV-TLS and DV-FIX; DV-BLD-7 only when it applies, 08 §15.3 M4, and DV-TLS-6 only when the release ships the FIPS build) and the acknowledged behavior differences of 00 §5.2 are in the release notes; when the release builds the convenience lib package with the Rust module, its `LICENSE-binary` covers the TLS closure (§3.10, §13.5).
- **M4 (Windows):** the §4.7 items pass, including ring's MSVC build with and without `+crt-static`, environment-only trust inputs, both TLS path encodings, and Windows mt. Windows SASL is tested on a best-effort basis and is not a gate. If the spike fails, DV-BLD-7 applies (§4.7, §11).
- **M5 (default flip):** the configure default becomes `--with-rust-client=auto`, and an unset CMake `WITH_RUST_CLIENT` means `AUTO` (§6.1, §6.3). A host that can build the Rust core gets it; any other host gets the legacy core, with a notice that gives the reason. `--with-rust-client=yes|side-by-side` and `-DWITH_RUST_CLIENT=ON` stay mandatory requests. `--with-rust-client=no` / `-DWITH_RUST_CLIENT=OFF` stays a supported configuration and prints no deprecation warning. The selection tests of 08 (DV-BLD-4 and BLD-SEL-01) pass with both build systems. Under Maven the Rust module stays opt-in: the Maven root property `rust-client` keeps the default `no`, because profile activation cannot see a pom default (§13.1); from M5 the `full-build-*` CI jobs except `full-build-cppunit-tests`, and the `Jenkinsfile` and `Jenkinsfile-PreCommit` jobs once their agents have cargo (08 §14), pass `-Drust-client=yes`. `full-build-cppunit-tests` keeps testing the legacy core with `-Pfull-build` alone (§13.8). The native closures are `cargo-vet safe-to-deploy` (§3.8, 08 §15.3 M5 (3)).
- **M5R (native recipes):** the `zk-recipes` crate (`apache-zookeeper-recipes`) with DistributedLock, LeaderLatch and TreeCache, its closure golden and its tests (04 owns the API, 08 the tests, including the Curator interop test in the Java integration job).

The legacy core stays a supported, buildable and maintained configuration through and after these milestones. `--with-rust-client=no` / `-DWITH_RUST_CLIENT=OFF` builds it with unchanged behavior and exports (§6.2), `-Pfull-build` alone builds it and never needs cargo, and its pinned reference build stays the differential oracle (08 §4.1).

## 7. Header installation

- **The installed set is unchanged:** `zookeeper.h`, `zookeeper_version.h`, `zookeeper_log.h`, `proto.h`, `recordio.h` and the generated `zookeeper.jute.h`, installed to `$(includedir)/zookeeper/`. There is one copy in the tree. The shim compiles against `include/` and `generated/`. The `<openssl/ossl_typ.h>` and `<sasl/sasl.h>` includes under `HAVE_OPENSSL_H`/`HAVE_CYRUS_SASL_H` stay as they are.
- `shim/*.h` are never installed. `winconfig.h` is installed by CMake on WIN32 only. `win_getopt.h` is never installed.
- **`xtask abi headers` checks:**
  - `cmp` of each installed header against its source;
  - identical install trees across all modes and TLS providers;
  - `zookeeper.jute.h` against the `Rcc -l c` output;
  - every shim TU compiles against the installed headers under each combination of `THREADED`, `ZKS_WITH_TLS` and `ZKS_WITH_SASL`, and the SASL glue under `HAVE_CYRUS_SASL_H`;
  - a check TU compiled with `HAVE_OPENSSL_H` and `HAVE_CYRUS_SASL_H` (on CI hosts with both header sets) proves that the shim's private declarations of `zookeeper_init_ssl` and the other conditionally declared functions have the installed types.
- **Public C structs are never Rust inputs.** `zkrs.h` contains only its own `repr(C)` types and the C scalar and pointer types of the 05 §5-§7, §10, §10.1, §11, §13, §16.2 and §19.2 listings; it names no public type. The shim marshals `zoo_op_t[]` and `struct ACL_vector` into `zkrs_op`/`zkrs_acl` in C. There is no bindgen and there are no layout cookies. `_Static_assert` in `zks_layout_check.c` and ctest in the test crate `crates/zk-ffi-layout` check the `zkrs.h` types.
- **Public result structs are Rust outputs.** zk-ffi's `abi::raw` writes the legacy result layouts that the application receives: `struct String_vector`, `struct ACL_vector`, `struct Id`, `struct ACL`, `struct Stat`, the string and data results, and the multi results in the application's `zoo_op_result_t` array (05 §10). It writes them through the hand-written `repr(C)` mirrors in `zk-ffi/src/abi/legacy.rs`, which `zkrs.h` does not declare; C code uses the installed `zookeeper.h` and `zookeeper.jute.h` for the same types. `crates/zk-ffi-layout` checks every mirror against those installed headers with ctest on each CI target, and the L1 layout goldens (`abi/layout/<target>.expect`) pin the C side (08 §3).

## 8. Jute code generation

zk-jute's Rust is generated exactly as the C jute is: by a Java generator in `zookeeper-jute`, run by Maven at `generate-sources`, into a git-ignored directory of the consuming module. One generator implementation, in the module that owns the `.jute` grammar, serves Java, C and Rust.

**Generator.**
- `org.apache.jute.compiler.RustGenerator` sits beside `CGenerator`. It is selected by `Rcc -l rust`.
- The existing compiler needs two small changes. The language check in `rcc.jj` (rcc.jj:55, which accepts `c++`, `java` and `c`) also accepts `rust`. `JFile.genCode` (JFile.java:65-86) gains a `rust` branch that constructs `new RustGenerator(mName, mInclFiles, mRecords, outputDirectory)` and calls `genCode()`, as the `c` branch does for `CGenerator`.
- Rcc writes into the current directory (`curFile.genCode(language, new File("."))`, rcc.jj:77). That is why the Maven execution sets its working directory.

**Execution.** The execution `generate-Rust-Jute`, in `zookeeper-jute/pom.xml` runs at `generate-sources`. It is a copy of `generate-C-Jute` (zookeeper-jute/pom.xml:106-124) with two differences: the language argument and the working directory.

```xml
<execution>
  <id>generate-Rust-Jute</id>
  <phase>generate-sources</phase>
  <goals><goal>exec</goal></goals>
  <configuration>
    <workingDirectory>${project.basedir}/../zookeeper-client/zookeeper-client-rust/crates/zk-jute/src/generated/</workingDirectory>
    <executable>java</executable>
    <arguments>
      <argument>-classpath</argument>
      <classpath />
      <argument>org.apache.jute.compiler.generated.Rcc</argument>
      <argument>-l</argument>
      <argument>rust</argument>
      <argument>${project.basedir}/src/main/resources/zookeeper.jute</argument>
    </arguments>
  </configuration>
</execution>
```

The root pom lists `zookeeper-jute` before `zookeeper-client` (pom.xml:59-68), so the generated file exists before the Rust module builds. exec-maven-plugin creates the working directory when it is missing, as it does for `zookeeper-client-c/generated/`.

**Output.**
- The generator writes one file, `src/generated/zookeeper_jute.rs` (the `.jute` file name with `.` replaced by `_`). zk-jute's `lib.rs` includes it with `#[path = "generated/zookeeper_jute.rs"] mod generated;` and re-exports `generated::{data, proto}`.
- The file holds `pub mod data` and `pub mod proto`, for the jute modules `org.apache.zookeeper.data` and `org.apache.zookeeper.proto` (zookeeper.jute:19, 61). The generator skips `org.apache.zookeeper.server.quorum`, `org.apache.zookeeper.server.persistence` and `org.apache.zookeeper.txn` (zookeeper.jute:265, 284, 292). There are no cargo features.
- Each record becomes a struct with `#[derive(Debug, Clone, PartialEq, Eq, Default)]` and an `impl Record` with `encode`, `decode` and `encoded_len`. 02 §2.1 owns the trait and the wire and decode rules.
- Field mapping: `int` to `i32`, `long` to `i64`, `boolean` to `bool`, `ustring` to `JString`, `buffer` to `JBuffer`, `vector<T>` to `JVec<T>`, and a record reference to the generated struct path (for example `data::Stat`). These are the only types that `data` and `proto` use. For any other jute type (`byte`, `float`, `double`, `map`), the generator fails with a message that names the record and the field, so a `.jute` change that needs a new mapping is found at generation time.
- Names: record names are kept as they are (they are already CamelCase). Field names become snake_case. A field whose name is a Rust keyword uses a raw identifier (`int type;` becomes `r#type`).
- Records appear in `.jute` file order and fields in declaration order. The file starts with the ASF license header and a "generated by RustGenerator; do not edit" line. It contains no timestamp, no absolute path and no hash-ordered iteration, so two runs give identical bytes.
- Generated code contains no `unsafe`, no `unwrap` and no indexing. `encoded_len` uses checked arithmetic. The code passes the workspace lints of §3.4 and 02 §17.

**Not committed.** The generated directory is git-ignored, as `zookeeper-client-c/generated/` is (.gitignore:100). `.gitignore` gains `zookeeper-client/zookeeper-client-rust/crates/zk-jute/src/generated/`. `zk-jute/Cargo.toml` `include` lists `src/generated/zookeeper_jute.rs`, so the crate's file set contains the generated file although git ignores it.

**Missing-file check.** `zk-jute/build.rs` (std-only, `#![forbid(unsafe_code)]`) checks that `src/generated/zookeeper_jute.rs` exists. It generates nothing. It mirrors the C check (configure.ac:73-74, `AC_CHECK_FILES` on the generated jute files, which fails with "jute files are missing!") and names the Maven command that generates the Rust file. If the file is missing, it fails with: "jute files are missing! Run `mvn -pl zookeeper-jute generate-sources` from the top level directory." It emits `cargo:rerun-if-changed` for the generated file.

**Builds from a source release.** The ASF source release excludes the generated directory, as it excludes `zookeeper-client-c/generated/` (§13.4). A build from it, Maven or standalone, first runs `mvn -pl zookeeper-jute generate-sources`, which the zk-jute build-script message names; the `make dist` tarball carries the file when it was generated (§6.2).

**Drift protection.**
- A CI check (08 §14) runs `RustGenerator` twice and fails when the outputs differ, which detects non-determinism.
- The three-way codec agreement (Java/C/Rust) over every record, plus a coverage check that the record sets are equal, catches generator drift between languages (08 §8.4).

**C generation is unchanged.** `generated/zookeeper.jute.{c,h}` comes from Maven `generate-C-Jute` and is compiled verbatim with `recordio.c`, so every `deallocate_*`/`allocate_*` quirk and the static-only `serialize_*`/`deserialize_*` symbols are identical.

**Null preservation.** Strings, buffers and vectors use 02's null-preserving `JString`, `JBuffer` and `JVec`. A decoded reply vector with count -1 stays distinct from an empty vector, which C applications can see (07 DAT-04; the shim's view gives `data == NULL, count == -1`). Request vectors encode as in legacy.

**Result materialization is a zk-ffi raw write.** The proto core decodes typed replies, and zk-ffi's `abi::raw` builds the legacy layout from them with libc `malloc`/`calloc`, with the generated deserializers' allocation pattern (for example `calloc(count, sizeof(*v->data))` for a vector and `malloc(len+1)` for a string, recordio.c:275), so `deallocate_*` releases it (05 §10). Paths are already chroot-stripped (zk-proto applies chroot for both profiles), multi results are parsed by wire type, and lengths are bounded. There is no second decoder.

## 9. Version single source of truth

| Item | Source | Mechanism |
|---|---|---|
| Product version | root `pom.xml` `<version>` | — |
| `ZOO_VERSION` (zookeeper_version.h:25), CMake `project()`, `AC_INIT` | derived | existing release antrun `replace-cclient-files-during-release` (zookeeper-client-c/pom.xml:122) |
| `[workspace.package] version` and the `=` pins in `[workspace.dependencies]` | derived | antrun `replace-rustclient-files-during-release` (phase `none`) in `zookeeper-client-rust/pom.xml` first runs `cargo fetch --locked --manifest-path fuzz/Cargo.toml`, rewrites `zookeeper-client-rust/Cargo.toml` to `${project.version}` without `-SNAPSHOT`, then runs `cargo update --workspace --offline` and `cargo update --workspace --offline --manifest-path fuzz/Cargo.toml` (§13.4). The root `maven-release-plugin` `preparationGoals`/`completionGoals` gain `antrun:run@replace-rustclient-files-during-release`, and the `scm:add@add-cclient-files-during-release` `<includes>` gain `Cargo.toml`, `Cargo.lock` and `fuzz/Cargo.lock`. |
| `zoo_version_str()`, `PACKAGE_STRING` | `ZOO_VERSION`, `AC_INIT` | unchanged |
| SONAME `.so.2`, Darwin 3.0.0 | `-version-info 2` | independent; never bumped by the drop-in |

Enforcement:

- **`zk-ffi/build.rs`** (`forbid(unsafe)`) reads the header named by `ZK_VERSION_H`, which Maven, configure and CMake export to the cargo invocation.
  - If the variable is unset, it falls back to `$CARGO_MANIFEST_DIR/../../../zookeeper-client-c/include/zookeeper_version.h`, the in-tree layout.
  - If neither exists, the build fails with a message naming `ZK_VERSION_H`.
  - It emits `cargo:rerun-if-env-changed=ZK_VERSION_H` and `rerun-if-changed=<path>`.
  - This works in the `make dist` layout, where the workspace sits at `<dist>/rust` and configure points the variable at `<dist>/include/…`.
  - It fails if one release rewrite ran without the other.
  - Build-script environment reads are build-time only and do not affect R6.
- **`cargo xtask check-version`** in CI compares all of the files above.

## 10. Native Rust crates in the source release

- **Source release only.** Every crate has `publish = false` (§3.2, §3.4). The native crates ship in the ASF source release, and nowhere else: `apache-zookeeper` (zk-client; the native public API), `apache-zookeeper-recipes` (zk-recipes, from M5R), and the implementation crates `apache-zookeeper-driver`, `apache-zookeeper-proto` and `apache-zookeeper-jute`, which are documented as having no stability promise. zk-client re-exports only its own types. Publication to crates.io is outside this design.
- **Use.** A Rust application depends on `apache-zookeeper` (and `apache-zookeeper-recipes`) by path into an unpacked source release, after the jute generation of §8, or into the `rust/` directory of a `make dist` tree.
- **Versions** equal the ZooKeeper release. The workspace crates pin each other with `=x.y.z` (§3.2), so they move in lockstep, and the release rewrite of §13.4 sets them.
- **Feature table** (04 owns the API meaning; this is the one build-level list):

| Crate | Feature | Default | Enables |
|---|---|---|---|
| apache-zookeeper | `tokio` | **on** | `Runtime::Tokio`; zk-driver `tokio`. Unix only: on Windows the feature enables nothing, and the threaded driver serves async users through its async adapter (R3, 04) |
| | `tracing` | **on** | render LogRecords as `tracing` events |
| | `stream` | **on** | `Stream` impls (futures-core) |
| | `blocking` | off | blocking facade (`wait()` on `Slot`, 04 §15) |
| | `log` | off | render LogRecords through the `log` crate |
| | `tls-rustls` | off | `TlsOptions`, TLS with the `WebPki` verifier: zk-driver `tls`. Selects no provider: exactly one of `tls-ring`, `tls-aws-lc-rs` and `tls-aws-lc-rs-fips` is required, else `compile_error!`. Trust roots through the shared loaders only (no rustls-native-certs) |
| | `tls-ring` | off | implies `tls-rustls`; zk-driver `tls-ring`. The default provider |
| | `tls-aws-lc-rs` | off | implies `tls-rustls`; zk-driver `tls-aws-lc-rs` |
| | `tls-aws-lc-rs-fips` | off | implies `tls-rustls`; zk-driver `tls-aws-lc-rs-fips` (Linux x86_64 and aarch64) |
| | `tls-platform-verifier` | off | `TlsOptionsBuilder::with_platform_verifier()`: the OS trust store and OS verification policy through rustls-platform-verifier. Implies `tls-rustls`; needs `tls-ring` or `tls-aws-lc-rs`; with `tls-aws-lc-rs-fips` the build stops with `compile_error!` (the OS verifier checks signatures outside the FIPS module, 04 §16.1) |
| | `sasl` | off | DIGEST-MD5 in safe Rust (md-5, getrandom) |
| | `sasl-gssapi` | off | GSSAPI via libgssapi (system library) |
| | `serde` | off | serde impls |
| apache-zookeeper-recipes | — | — | no features; uses the zk-client features that the application enables |
| apache-zookeeper-driver | `tokio` | off | tokio driver (Unix only) |
| | `std-thread` | off | `spawn_std` (named std threads) for the native threaded driver |
| | `tls` | off | the TLS module (needs exactly one provider feature) |
| | `tls-ring` | off | `tls` with the ring provider |
| | `tls-aws-lc-rs` | off | `tls` with the aws-lc-rs provider |
| | `tls-aws-lc-rs-fips` | off | `tls` with the FIPS provider (§4.10) |
| apache-zookeeper-jute | — | — | no features |
| apache-zookeeper-proto | `testkit` | off | Read-only introspection for zk-testkit and the ledger gate; never enabled by zk-ffi or zk-client |

  The native SASL mechanisms are DIGEST-MD5 (`sasl`) and GSSAPI (`sasl-gssapi`). The native provider features mirror the drop-in's: each provider feature implies `tls-rustls`; `tls-ring` is the default provider, and `tls-aws-lc-rs` or `tls-aws-lc-rs-fips` replaces it. `tls-rustls` alone, or two provider features, stops the build with zk-driver's `compile_error!` (R3), so documentation, lint and test builds use explicit feature lists, never `--all-features`. The aws-lc features carry the same release condition as the drop-in aws-lc builds (§3.10, 00 §6).

## 11. Build-level deviations register

These are link-time, ABI, packaging or failure-path differences, including the DV-ABI-2 stubs of undeclared SASL internals (05 §21 lists their shim side). Each DV-* row is an accepted deviation of the design, and its last column is the text that the release notes carry. Rows without a DV-* ID are not observable and need no release note. The other runtime-semantic deviations belong to 07 (DV-FIX) and 06 §3.13 (DV-TLS); 00 §5.1 lists all IDs.

| ID (alias) | Deviation | Justification | Release note |
|---|---|---|---|
| DV-ABI-1 (B1) | The static archives do not contain the undeclared legacy internals that legacy archives define (`hash`, `do_io`, `get_xid`, `flush_send_queue`, `adaptor_*`, `api_prolog`, `addrvec_*`, `hashtable_*`, `deliverWatchers`, `MAX_RW_TIMEOUT`, …; about 95 symbols). The removed set is the reference-archive globals that appear in neither `abi/symbols.tsv` (the export set) nor `abi/static-symbols.tsv` (the 179-row archive keep list). Both goldens are authoritative. `oa_*`/`ia_*` and every header-declared symbol are kept. No `zks_*`/`zkrs_*` replacements appear. | Not declared in any installed header and not exported by any shared library. Their generic names collide with application symbols in legacy, and LINK-03 links with the Rust core. | The static libraries do not define internal symbols that no installed header declares. Programs that used them must stop doing so. The list of removed names follows this note. |
| DV-ABI-2 (D15) | `zoo_sasl_connect`, `zoo_sasl_client_start` and `zoo_sasl_client_step` are stubs that return ZSYSTEMERROR. The symbols are kept. `zoo_sasl_mark_failed` and `zoo_sasl_client_create`/`_destroy` stay functional. | Undeclared internals that drive negotiation from an application thread on state that the IO owner owns exclusively (00 §2.6 rule 4). | `zoo_sasl_connect`, `zoo_sasl_client_start` and `zoo_sasl_client_step` are not declared in any installed header and return ZSYSTEMERROR; the library drives SASL negotiation itself. |
| DV-BLD-2 (B2) | The Windows static library does not define the winport emulations (`pthread_*`, `close(SOCKET)`, `gettimeofday`, `drand48`, the `Win32WSAStartup`/`Win32WSACleanup` symbol names). Winsock start-up (`MAKEWORD(2,0)` with the 2.0 version check) and clean-up behavior, and the init failure log line, are kept by the shim (§4.7). | Removes UB in winport (missing returns in `pthread_cond_wait`/`broadcast`, `pthread_key_create` never storing the key, `pthread_key_delete` freeing a stack address) and the hijack of CRT `close()`. No installed header declares these. | On Windows, `zookeeper.lib` does not provide POSIX emulations (`pthread_*`, `close` on sockets, `gettimeofday`, `drand48`). Applications that relied on them must supply their own. |
| B3 | CMake `hashtable` becomes a one-member static library with no global symbols. | The Rust client has no hashtable. `-lhashtable` and `target_link_libraries(... hashtable)` keep working. Not observable. | — |
| DV-BLD-1 (B4) | Additional link dependencies from Rust std: DT_NEEDED `libgcc_s.so.1`, on glibc < 2.34 also `libdl`, `libutil` and `librt`, and for st also `libpthread` on glibc < 2.34 (legacy st linked no libpthread, Makefile.am:52). Static consumers need `ZKRS_NATIVE_LIBS`, which the `.la`, `.pc` and the CMake link interface propagate. | Unavoidable for a Rust-backed library. | The libraries depend on `libgcc_s.so.1` on Linux, and on glibc older than 2.34 also on `libdl`, `libutil` and `librt` (and `libpthread` for `libzookeeper_st`). Hand-written static link lines add the libraries that `pkg-config --static --libs` reports. |
| B5 | glibc std registers an `.init_array` entry (it records argc/argv), so the st library gains a load-time constructor. | No observable effect: no files read, no environment read, no threads. | — |
| DV-BLD-4 (B6) | Building the Rust client needs rustc and cargo ≥ 1.85, and a C compiler and assembler for the crypto provider: ring's (always present where the shim builds), the aws-lc-sys build requirements for the aws-lc-rs provider, or CMake and Go as well for the FIPS provider. A CMake build of either core needs CMake 3.15 or later, where the legacy file sets 3.5 (CMakeLists.txt:17); `zookeeper-client-c/CMakeLists.txt` has one minimum for every configuration, and `CMAKE_MSVC_RUNTIME_LIBRARY` sets it (§6.3). Autotools builds of the C client do not use CMake themselves; only the FIPS provider's crate build does. There is no OpenSSL build floor. Cygwin, and hosts with no rustc and cargo ≥ 1.85 (AIX and other targets below Rust tier 2), build the legacy core: `--with-rust-client=no` / `-DWITH_RUST_CLIENT=OFF` selects it, and `auto` (the configure and CMake default from M5) selects it with a notice (§6.1, §6.3). A target that has the toolchain but that `ring` does not support builds the legacy core: `auto` selects it with a notice, and `yes`, `side-by-side` and `ON` stop. | Build-host only. The legacy core stays buildable, so hosts without Rust 1.85 keep a supported library. One CMake minimum keeps one policy set for both cores, and hosts with an older CMake keep the autotools build. | Building the Rust-backed client needs Rust 1.85 or later. Hosts without it keep building the legacy core: `--with-rust-client=no` selects it, and the `auto` selection picks it with a notice. CMake builds of the C client, with either core, need CMake 3.15 or later; autotools builds of the C client do not use CMake themselves. |
| DV-BLD-3 (B7) | On MSVC, `zookeeper.lib` cannot hide Rust std, `zkrs_*`, `zks_*` or crypto-provider symbols, so linking it next to another Rust staticlib can fail with LNK2005. | MSVC has no partial link. The opt-in DLL avoids the problem. Provider symbols are version-prefixed and cannot collide with OpenSSL. | On Windows, an application that also links other Rust code should use the ZooKeeper DLL (`-DZOOKEEPER_BUILD_SHARED=ON`) instead of the static `zookeeper.lib`. |
| DV-BLD-5 (B8, B9) | On an internal Rust invariant failure (panic) or Rust allocation failure, the library writes std's panic or allocation-failure message to fd 2 through std's default hook, reads `RUST_BACKTRACE` on the panic path, and aborts, including in builds where legacy compiled `assert` out (CMake Release `NDEBUG`). The shim's own retained `assert()` sites still follow `NDEBUG`. The build uses the stable toolchain and installs no panic hook. | Failure path only. Every such state is reachable in legacy only through UB, an assert or a NULL dereference. A process-global panic hook would interfere with other Rust code in the process. | An internal error in the Rust core prints a message to standard error and aborts the process, in every build type. |
| DV-BLD-6 (B10) | Each static archive has a single pre-linked member and is compiled as PIC, where legacy had per-TU members, non-PIC in the autotools archives (the legacy CMake build already compiles with `-fPIC` on Unix, CMakeLists.txt:27). A static link pulls the whole library (including rustls and the provider when TLS is on) unless the application uses `--gc-sections`. | Needed to localize everything outside the golden set (§4.5). Not observable through the C API. | The static libraries contain one object; static links that want a smaller binary use `-Wl,--gc-sections`. |
| DV-BLD-7 | Applies only if the M4 Windows spike fails (§4.7): Windows ships on the legacy core for that release, and the Rust core follows on Windows in the first release whose Windows build passes the M4 exit criteria of 08 §15.3 (CC-33, 00 §4). | Windows mt depends on the poll, waker and provider items that the spike confirms; the legacy core stays buildable, so Windows users keep a supported library. | On Windows, this release builds the legacy C client; the Rust-backed client follows in a later release. |
| DV-BLD-8 | The libraries carry no GNU property note for x86 IBT/SHSTK or AArch64 BTI, even when a distribution builds with `-fcf-protection` or `-mbranch-protection` (§4.3). | Stable rustc cannot mark its objects, and the provider's assembly may lack the markers; a partial link of marked and unmarked objects drops the property. The build adopts the rustc options when they are stable. | The libraries are not marked for CET (IBT/SHSTK) or BTI, so a process that loads them runs without that enforcement, as with any unmarked library. |
| **DV-TLS-4 Linkage** | libssl and libcrypto are not DT_NEEDED, not in `.la` `dependency_libs`, not in `.pc` `Libs.private`, and not in the CMake link interface of the `zookeeper` target. Applications that used OpenSSL symbols without linking OpenSSL themselves must add `-lssl -lcrypto`. The library performs no process-global OpenSSL initialization and touches no OpenSSL error queue. Applications that compile with `HAVE_OPENSSL_H` still need the OpenSSL headers, as in legacy. Static-archive `close_zsock` does not free `ssl_sock`/`ssl_ctx`. The OpenSSL probe still decides whether TLS is built, so the default export set and `HAVE_OPENSSL_H` match legacy on every host (06 §3.11). | The library contains no OpenSSL code, so it neither links nor initializes OpenSSL. `close_zsock` is an undocumented internal; the library itself never populates those fields. | "libzookeeper does not link libssl or libcrypto and does not initialize OpenSSL. Applications that use OpenSSL symbols without linking OpenSSL themselves must add `-lssl -lcrypto` to their link line. Applications that define `HAVE_OPENSSL_H` still need the OpenSSL headers. The static-archive `close_zsock` does not free `ssl_sock` or `ssl_ctx`." |

Build facts behind DV-TLS-4: libssl and libcrypto are not in DT_NEEDED, `.la` `dependency_libs`, `.pc` `Libs.private` or the CMake link interface (`OpenSSL::SSL`/`OpenSSL::Crypto`) in any configuration (§4.4, §4.6, §6.3). The static-archive-only `close_zsock` closes `sock` only and does not free `zsock_t.ssl_sock`/`ssl_ctx`, which the legacy body frees (zookeeper.c:648-670); it also does not read `ssl_sock`, which lies past a 16-byte `zsock_t` in an application compiled without `HAVE_OPENSSL_H`. The TLS decision is the legacy autodetect (§4.8), so the export set matches legacy in every configuration that does not set the opt-in `--with-rust-tls=yes` / `WITH_RUST_TLS=ON`.

DV-TLS-1, DV-TLS-2, DV-TLS-3, DV-TLS-5 and DV-TLS-6 (FIPS build only) are runtime deviations; 06 §3.13 holds their text and 07 the rows. §4.8 lists them by title because the build selects the code that produces them. DV-FIX-1..9 are the runtime deviations of the behavior fixes of the Rust core, in every Rust-core build; 07 §5.1 holds their rows and release-note text.

## 12. Cross-document contracts fixed by this document

1. **Callback model:** the outbox model of §3.6 (CF1–CF10). mt IO and dispatch loops are C loops over `zkrs_io_step`/`zkrs_wait_dispatch`. Item kinds are LOG, RESULT, WATCH, AUTH_LOCKPOINT, SASL{START, STEP, DISPOSE} and THREAD{JOIN\|DETACH, IO\|DISPATCH}. `zkrs_step` is {DONE, MORE, RELEASE, EXIT}. SASL START and STEP and every THREAD item are barriers; a run of consecutive Direct results shares one barrier; pending LOG items are drained at a barrier before each Blocking vtable call (`resolve`, `rng_reseed`), in init, `zoo_set_servers`, the mt IO step and the st `zookeeper_interest`/`zookeeper_process` calls (05 §5), and before the IO step's TLS file reads (05 §5); SASL DISPOSE is ordered but not a barrier. The only Rust result slot is zk-client's private `Slot<T>` (04 §5.2); the drop-in has none.
2. **Sync path:** sync wrappers call the exported `zoo_a*` with `ZKS_SYNC_MARKER` (interposition, ABI-06). The wrapper allocates a heap `zks_sync` record with refcount 2 (one reference for the waiter, one for the completer; the `zks_req` points at it), as legacy `alloc_sync_completion` allocates, which keeps 07 RC-13's checked `ZSYSTEMERROR`. On the completing thread, zk-ffi's `abi::raw` writes the result into the record, and C signals at a barrier; the waiting thread copies out after it wakes, through zk-ffi's raw writers with the legacy rules (05 §13). A teardown result, including ZCONNECTIONLOSS on the RO→RW switch (DV-FIX-9), leaves every output untouched (05 §13, 07 ST-08). No library thread writes another thread's stack.
3. **Crate graph:** zk-driver is its own crate, and zk-ffi depends on it, never on zk-client. The workspace uses `crates/`.
4. **Drop-in closures:** the TLS-off closure {rustix (use-libc), libc, errno, bitflags, linux-raw-sys (Linux), windows-sys with its import-library crates (Windows), zeroize}, and the TLS-on closures of §3.3 R4 (rustls, rustls-pki-types, rustls-webpki, untrusted, the selected provider, the key-loading crates including pem-rfc7468, sha1, base64ct, with their transitive closure), with goldens `zk-ffi-<target>.txt`, `zk-ffi-<target>-aws-lc-rs.txt` and `zk-ffi-<target>-aws-lc-rs-fips.txt` (Linux x86_64 and aarch64) and `zk-ffi-<target>-notls.txt`. socket2, mio, polling, tokio, tracing, log, OpenSSL crates, `openssl-probe`, `rustls-pemfile`, `rustls-native-certs`, `rustls-platform-verifier` and `webpki-roots` are never in a drop-in closure.
5. **TLS:** `RustlsConn` in `zk-driver/src/net/tls/` is the one `TlsConn` implementation for both profiles. `Compat::tls_verify` selects `LegacyChainOnly` (LEGACY_C) or `WebPki` (NATIVE). There is no shim TLS file and no TLS vtable entry. TLS presence follows the legacy autodetect of `--with-openssl`/`WITH_OPENSSL` exactly, plus the opt-in `--with-rust-tls=yes`/`WITH_RUST_TLS`; a TLS build passes the zk-ffi features `tls,tls-<provider>` (§4.8). Feature names in zk-ffi and zk-driver: `tls`, `tls-ring`, `tls-aws-lc-rs`, `tls-aws-lc-rs-fips`; exactly one provider, enforced by `compile_error!`. zk-client mirrors them: `tls-rustls` (zk-driver `tls`, no provider) with exactly one of `tls-ring` (the default provider), `tls-aws-lc-rs` and `tls-aws-lc-rs-fips`, each of which implies `tls-rustls`; it adds the native-only `tls-platform-verifier`, which cannot be combined with `tls-aws-lc-rs-fips`.
6. **SASL:** Cyrus is called only by `zks_sasl_cyrus.c`, from SASL outbox items drained on the IO owner. `zks_sasl_cb.c` holds the `zoo_sasl_make_*` callback builders. These two files are the only ones that include `<sasl/sasl.h>` and `zks_sasl_cb.h`, and they are built with the §4.9 hardening flags. Other shim files see only `zks_sasl.h`.
7. **Host vtable:** Pure {`discard_token`, `snapshot_default_watcher`, `store_client_id`, `log_enabled`, `conn_permute_disabled`, `rng_random`, `rng_drand48`}; Blocking {`rng_reseed`, `resolve`}. The vtable has no other entries.
8. **Threads:** created by C with default attributes (`pthread_create(NULL)` / `_beginthreadex`). The thread role comes from C TLS (`zks_current_role`) and is passed to Rust as an argument. The drop-in uses no `std::thread` and no `std::thread::current()`.
9. **CLOEXEC:** Behavior matches legacy. Sockets follow the build option as a runtime flag, and the waker pipe has no CLOEXEC.
10. **Prefixes:** every shim internal is `zks_*`. Nothing outside the golden set is global in either artifact (except on MSVC).
11. **Static archive:** golden ∪ `static-symbols.tsv` (161 + 18 `oa_*`/`ia_*`). E12 holds for both the `.so` and the `.a`.
12. **Files and options:** the `abi/` file set, the awk generator, `xtask abi` checks, `--with-rust-client`/`WITH_RUST_CLIENT`/`rust-client`, `--with-rust-tls-provider`/`ZK_RUST_TLS_PROVIDER`/`rust-tls-provider`, `--with-rust-tls`/`WITH_RUST_TLS`/`rust-tls`, `--with-rust-tls-default-paths`/`ZK_RUST_TLS_DEFAULT_PATHS`, `--with-rust-workspace`/`ZKRS_WORKSPACE`, `ZOOKEEPER_BUILD_SHARED`, `--with-rust-staticlib`/`ZKRS_STATICLIB`, `--with-rust-target`/`Rust_TARGET`, `--with-cargo-vendor`/`ZOOKEEPER_CARGO_VENDOR`, `--with-system-rust-crates`/`ZK_SYSTEM_RUST_CRATES`/`rust-system-crates`, `--disable-werror`/`ZK_WERROR`, M0–M5 and M5R, `abi/log-sites.tsv` with the committed `LogSite` enum in zk-proto and `shim/zks_log_sites.h`, and the shim file list in §2.
13. **Result materialization:** zk-ffi's `abi::raw` builds the legacy C result layouts from the decoded replies with libc `malloc`/`calloc` and legacy sizes, and performs the sync copy-out; C keeps delivery and the post-callback deallocation order. No raw reply bytes cross, and there is no second decoder. zk-jute types are null-preserving.
14. **Windows:** WSAStartup (`MAKEWORD(2,0)`, exact 2.0 check) at init and WSACleanup in `zks_release` only, with the init log line, are kept in the shim; `zkrs_interest_out.fd` is `intptr_t` written through `SOCKET*`; Windows mt is in M4, and if the M4 spike fails, Windows ships on the legacy core for that release (DV-BLD-7).
15. **Compat:** one struct in 02 (`Compat`, 02 §14; `Compat::LEGACY_C`, `Compat::NATIVE`) with ledger values. Flavor-specific behavior lives in `zk_proto::DriverMode {interest, session_delivery}`, never in `Compat`. `DispatchMode {Thread, IoThread}` is a zk-driver type.
16. **Environment reads:** only `SSL_CERT_FILE`/`SSL_CERT_DIR`, only on TLS handles, only when `zkrs_init_args.tls_env_allowed` (the shim's `AT_SECURE`/`issetugid` result; native: zk-client's rustix uid/gid comparison); otherwise the compile-time `ZK_TLS_DEFAULT_CERT_FILE`/`ZK_TLS_DEFAULT_CERT_DIR`, whose default is the OPENSSLDIR recorded at configure time (`openssl version -d` of `$with_openssl/bin/openssl` or `${OPENSSL_ROOT_DIR}/bin/openssl` for a named installation, or of the first `openssl` on `PATH` when no installation is named; a `PATH` binary is never used for a named installation), else, when the selected binary does not exist, the first existing entry of the §6.1 platform list applied on the build host; never `openssl_probe::probe()`.
17. **Maven:** the module, properties, profiles, phase mapping, release rewrite, assembly, RAT excludes and C-module interaction of §13. The module is in the root-pom profile `rust`, which `-Prust` or any `-Drust-client=…` activates; `full-build` lists only the C module, so `-Pfull-build` alone never needs cargo (§6.5). The drop-in staticlib is built with `-p zk-ffi` and never with `--workspace`; the native crates are built by a separate `--workspace --exclude zk-ffi` invocation.

## 13. Maven integration

Maven drives the Rust build the same way it drives the C client (`zookeeper-client/zookeeper-client-c/pom.xml`). Each mechanism below mirrors a C-client mechanism one for one. Every C-side reference is at 60da6eb50.

### 13.1 Module and reactor placement

- **Module.** `zookeeper-client/zookeeper-client-rust/pom.xml` has artifactId `zookeeper-client-rust`, `<packaging>pom</packaging>`, and parent `zookeeper-client`. Its name is "Apache ZooKeeper - Client - Rust" and its description is "ZooKeeper Rust client".
- **Not deployed.** The module inherits `maven-deploy-plugin <skip>true</skip>` from `zookeeper-client/pom.xml`. Like the C client, it puts nothing on Maven Central.
- **Own opt-in profile.** The root `pom.xml` adds the module in its own profile `rust`. `zookeeper-client/pom.xml` keeps its `full-build` profile with `zookeeper-client-c` only:
  ```xml
  <profile>
    <id>rust</id>
    <activation><property><name>rust-client</name></property></activation>
    <modules><module>zookeeper-client/zookeeper-client-rust</module></modules>
  </profile>
  ```
  `-Prust` activates it, and so does any `-Drust-client=…`, because a Rust-core C build needs the staticlib. `-Pfull-build` alone builds the C client on the legacy core and never needs cargo (§6.5). A CI job that tests Rust code passes `-Prust` or `-Drust-client=…` (§13.8). A build without `-Pfull-build` and without the `rust` profile builds neither native client, as in legacy.
- **Reactor order when the drop-in is on.** `zookeeper-client-c/pom.xml` has a profile `rust-client-dropin`, which activates when the `rust-client` property is defined. Its only effect is a `pom`-type reactor-order dependency on the Rust module, so the reactor always builds the Rust staticlib before the C module links it. Declaration order alone is not relied on.
  ```xml
  <profile>
    <id>rust-client-dropin</id>
    <activation><property><name>rust-client</name></property></activation>
    <dependencies><dependency>
      <groupId>org.apache.zookeeper</groupId><artifactId>zookeeper-client-rust</artifactId>
      <version>${project.version}</version><type>pom</type><scope>provided</scope>
    </dependency></dependencies>
  </profile>
  ```
  Maven profile activation sees only user and system properties (`-D`), not the root-pom default, and it cannot test for two values. The profile is therefore active whenever `-Drust-client` is given, which covers `yes` and `side-by-side`, and the root `rust` profile then also puts the Rust module in the reactor. With an explicit `-Drust-client=no` the dependency only adds an ordering edge; nothing is linked from it.
- **Root module order.** Maven appends a profile's modules after the root `<modules>` list (pom.xml:59-68: zookeeper-website, zookeeper-jute, zookeeper-server, zookeeper-metrics-providers, zookeeper-client, zookeeper-recipes, zookeeper-assembly, zookeeper-compatibility-tests). The Rust module also declares `zookeeper-jute` and `zookeeper-server` as `provided` dependencies, which serve only as reactor-order edges, so the generated jute sources (§8) and the server jar that integration tests use exist before the Rust module builds, without reliance on declaration order.
- **Root-pom properties**, next to `c-client-openssl` and `c-client-sasl` (pom.xml:573-574):
  - `<rust-client>no</rust-client>` selects the legacy core. The other values are `yes` and `side-by-side`. They map 1:1 to `--with-rust-client=`. `side-by-side` also needs `cargo about` at package (§13.5). Under Maven the Rust module is opt-in, so the property has no `auto`: the Rust module's process-sources step fails on any other value with a message that names the three values, and configure therefore always receives an explicit choice (§6.1).
  - `<rust-tls-provider>ring</rust-tls-provider>`; the other values are `aws-lc-rs` and `aws-lc-rs-fips`.
  - `<rust-tls>no</rust-tls>`. It maps to `--with-rust-tls` and to the zk-ffi feature set (§13.2, §13.7).
  - `<rust-system-crates></rust-system-crates>` (empty: off). A distribution crate directory selects the system-crates mode (§3.11) for the Rust module's cargo invocations and the C module's `make abi-check` (§13.7).
  - `<rust-cargo-vendor></rust-cargo-vendor>` (empty: off). A `cargo vendor` directory: every cargo invocation of the Rust module, and the C module's `make abi-check` (§13.7), adds `--offline --config 'source.crates-io.replace-with="vendored"' --config 'source.vendored.directory="DIR"'`, and the Rust module skips `cargo fetch`. It excludes `rust-system-crates`: with both set, the module fails with a message that names them.
  - `<cargo.executable>cargo</cargo.executable>`.

  `c-client-openssl` (default `yes`) and `c-client-sasl` (default `yes`) keep their names, defaults and meaning (§13.7). `-Dcargo.offline`, or Maven offline mode `-o`, adds `--offline` to every cargo invocation and skips `cargo fetch`.

### 13.2 Lifecycle bindings

| Maven phase | C client (`zookeeper-client-c/pom.xml`) | Rust module (`zookeeper-client-rust/pom.xml`) |
|---|---|---|
| generate-sources | `antrun` mkdir `target/c`. Jute C is generated by `zookeeper-jute` (`generate-C-Jute`, Rcc `-l c` into `zookeeper-client-c/generated/`). | `antrun` mkdir `target/cargo`. Jute Rust is generated by `zookeeper-jute` (execution `generate-Rust-Jute`, Rcc `-l rust` into `zookeeper-client-rust/crates/zk-jute/src/generated/`, §8). |
| process-sources | `exec` `autoreconf -if`, then `configure --with-openssl=${c-client-openssl} --with-sasl=${c-client-sasl} --prefix=target/c …` | An antrun check of `rust-client` (fails on any value but `no`, `yes` and `side-by-side`, with a message that names the three, §13.1), then `exec` `${cargo.executable} --version` (fails early with a clear message when cargo is absent), then a version check that compiles no workspace code: antrun compares the versions that `${cargo.executable} --version` and `rustc --version` print with 1.85 and fails with "Building the Rust client needs Rust 1.85 or later" (a cargo older than 1.85 cannot parse the edition-2024 workspace manifest, so the check cannot be workspace code), then the TLS decision `sh ../zookeeper-client-c/abi/tls-probe.sh --with-openssl=${c-client-openssl} --with-rust-tls=${rust-tls}` into `target/cargo/tls-probe.properties` (§13.7). Then `cargo fetch --locked`, skipped when offline or when `rust-cargo-vendor` is set (§13.1). With `rust-system-crates` set, it instead writes `target/cargo/system-crates.toml` and runs `cargo generate-lockfile --offline --config target/cargo/system-crates.toml` (§3.11), and every later cargo invocation adds `--offline --config target/cargo/system-crates.toml`. |
| compile | `antrun` `make clean install` in `target/c` | `antrun` `<exec executable="${cargo.executable}" failonerror="true">`, two separate invocations: (1) the drop-in staticlib, `build --locked --profile capi -p zk-ffi ${zkrs.features}`, never with `--workspace` (R1); (2) the native crates, `build --locked --workspace --release --exclude zk-ffi`. Environment: `CARGO_TARGET_DIR=${project.build.directory}/cargo`, `ZK_VERSION_H=${basedir}/../zookeeper-client-c/include/zookeeper_version.h`, `ZK_TLS_DEFAULT_CERT_FILE` and `ZK_TLS_DEFAULT_CERT_DIR` from the probe, `base_dir=${basedir}/../..`, `CALLER=ANT`. All output stays under `target/`, so `mvn clean` removes it. The staticlib lands at `target/cargo/capi/libzk_ffi.a` (host build, no `--target`). |
| test | Profile `cppunit`, active unless `-DskipCppUnit`, with `<skip>${skipTests}</skip>`. Runs `make check`, with env `base_dir`/`CLOVER_HOME`/`CALLER`, so `tests/zkServer.sh` starts a Java server from `zookeeper-server/target`. | Profile `cargo-test` (§13.3). |
| verify | `make abi-check` when `rust-client≠no` and cargo is available (§13.7); otherwise none (CI runs `apache-rat:check spotbugs:check checkstyle:check` at verify) | Profiles `rust-lint` and `rust-supply-chain` (§13.3). |
| package | none (the assembly picks up `target/c`) | none, except that with `rust-client=side-by-side` it runs `dev/gen-license-binary.sh` for the lib package (§13.5). The crates are not packaged: they ship in the ASF source release (§10, §13.4). |

`zkrs.features` is set by an antrun `<condition>` from the probe result: `--features tls,tls-${rust-tls-provider}` when the TLS decision is on (the autodetect found OpenSSL, or `rust-tls=yes`), otherwise empty. The two compile invocations are separate because a `--workspace` build of zk-ffi would unify the zk-driver features that zk-client enables (`tokio`, `std-thread`) into the staticlib.

Before M3 the TLS module does not exist, so `zkrs.features` is empty whatever the probe finds; a job that passes `-Drust-client` before M3 also passes `-Dc-client-openssl=no`, so the configure cross-check agrees (08 §14).

The module runs only when the `rust` profile puts it in the reactor (§13.1), so it needs no skip switch: a build without `-Prust` and without `-Drust-client` runs no cargo.

### 13.3 Test, lint, supply-chain, coverage and ASan profiles

- **`cargo-test`** (phase test), active unless `-DskipCargoTest`, with `<skip>${skipTests}</skip>`. It runs two invocations, for the same reason as the two compile invocations (§13.2): `cargo test --locked --workspace --exclude zk-ffi`, and `cargo test --locked -p zk-ffi ${zkrs.features}`, which tests the shipped zk-ffi feature set. Both run with the same environment keys as the C module's `cppunit` profile (`base_dir`, `CLOVER_HOME`, `CALLER`). Integration tests start the server with the same `zookeeper-client-c/tests/zkServer.sh`, so the setup is shared and not duplicated. Test logs go to `target/cargo/TEST-*.txt`, the same shape as the cppunit logs that CI uploads. Neither invocation compiles the `fuzz/` or `crates/zk-ffi-layout` workspaces (§3.4), so the profile runs on the MSRV toolchain.
- **`rust-lint`** (phase verify), active unless `-DskipRustLint`:
  - `cargo fmt --check`;
  - `cargo clippy --locked --workspace --all-targets -- -D warnings`, plus the `rust-lint` zk-ffi clippy runs of §3.4 (explicit feature sets, never `--all-features`);
  - `cargo xtask ledger check`.

  The ABI check does not run in this profile; the C module runs it at verify, after `make install` (§13.7), because the libraries are built after the Rust module.
- **`rust-supply-chain`** (phase verify), opt-in, activated explicitly with `-Prust-supply-chain` (the CI job `full-build-rust-tests` passes it): `cargo deny check` (with `--config deny-fips.toml` for the FIPS feature set, §3.7), `cargo vet --locked`, `cargo audit`, and the closure golden diff of §3.3 R4 for every shipped configuration.
- **`rust-test-coverage`** mirrors `c-test-coverage`. It runs `cargo llvm-cov` and writes lcov to `target/cargo/coverage`.
- **`rust-test-asan`** mirrors `c-test-asan`. It runs `RUSTFLAGS=-Zsanitizer=address RUSTDOCFLAGS=-Zsanitizer=address cargo +nightly test -Zbuild-std --target <host triple>` (the explicit `--target` keeps build scripts and proc-macros uninstrumented) and is documented as nightly-only.
- When `rust-client≠no`, the C module's `c-test-asan` (configure `--enable-asan`) builds the instrumented shim and runs the cppunit public-API suite against the Rust-backed libraries. The Rust code inside them then runs under ASan's allocator and interceptors; it is instrumented itself only in `rust-test-asan`.

### 13.4 Release, versioning and source release

- **One version source.** The single source of truth is the root pom version (§9).
- **Version rewrite.** The antrun execution `replace-rustclient-files-during-release` (phase `none`) in `zookeeper-client-rust/pom.xml` mirrors `replace-cclient-files-during-release` (zookeeper-client-c/pom.xml:122):
  - It starts with `cargo fetch --locked --manifest-path fuzz/Cargo.toml`, before any file is rewritten. The process-sources fetch (§13.2) covers the main workspace only, and `cargo update --workspace --offline` fails when a locked crate is not cached; `--locked` accepts the lockfile only while the manifests are unchanged. The fetch is skipped when `rust-cargo-vendor` or `-Dcargo.offline` is set, because the vendor directory (§3.10) or the pre-populated `CARGO_HOME` then supplies these crates.
  - It then rewrites `version = "…"` under `[workspace.package]` in `zookeeper-client-rust/Cargo.toml`, and the `version = "=…"` pins in `[workspace.dependencies]` (§3.2), to `${project.version}` with `-SNAPSHOT` stripped. As in the C execution, this is a `replaceregexp` per pattern followed by a `<replace>` of the `-SNAPSHOT` token. The `[workspace.package]` line carries the marker comment `# == ZOO_VERSION`, so its pattern matches that one line only.
  - It then updates the workspace members' entries in `Cargo.lock` with `cargo update --workspace --offline`, and `fuzz/Cargo.lock`, which records the versions of the `fuzz/` workspace's path dependencies on workspace crates, with `cargo update --workspace --offline --manifest-path fuzz/Cargo.toml`.
- **Release-plugin wiring.** The root pom `maven-release-plugin` `preparationGoals` and `completionGoals` (pom.xml:1140-1141) gain `antrun:run@replace-rustclient-files-during-release`. The plugin configuration has no `<arguments>` element (pom.xml:1134-1147; the pluginManagement entry at pom.xml:831-834 has no configuration), and it gains none: `-Prust` is not part of the release arguments, because under Maven the Rust module is opt-in. The goal runs in the modules that are in the release reactor, so the Rust rewrite runs when the release includes the Rust module, as the C rewrite runs when the release includes the C module (`-Pfull-build`). The `<includes>` of `scm:add@add-cclient-files-during-release` gain `zookeeper-client/zookeeper-client-rust/Cargo.toml,zookeeper-client/zookeeper-client-rust/Cargo.lock,zookeeper-client/zookeeper-client-rust/fuzz/Cargo.lock`.
- **Header cross-check.** `zk-ffi/build.rs` checks the workspace version against the header named by `ZK_VERSION_H` (§9). The build therefore fails if one rewrite ran without the other. A release that runs the C rewrite and not the Rust rewrite leaves the next-development commit with `ZOO_VERSION` ahead of the workspace version, so every Rust-core build from that commit stops at this check (under `auto`, ZK_RUST_CHECK selects the legacy core with a notice) until `antrun:run@replace-rustclient-files-during-release` runs with the Rust module in the reactor.
- **What a release that includes the Rust module needs.** The build has these requirements:
  - `-Prust` in the forked release builds, for example through the release plugin's `-Darguments=-Prust` (any `-Drust-client=…` also activates the profile, §13.1); without it the Rust module is not in the release reactor and the Cargo.toml/Cargo.lock rewrite does not run;
  - cargo and rustc ≥ 1.85 on the release host (DV-BLD-4); the rewrite itself needs cargo for `cargo fetch` and `cargo update --workspace --offline`;
  - the crates that `Cargo.lock` and `fuzz/Cargo.lock` resolve: network access for `cargo fetch --locked`, or a `cargo vendor --locked --sync fuzz/Cargo.toml` directory passed with `-Drust-cargo-vendor=DIR` (§3.10, §13.1). With either, the build runs `--locked`, so cargo checks every crate against its `Cargo.lock` checksum;
  - the version rewrite of `Cargo.toml`, `Cargo.lock` and `fuzz/Cargo.lock` above, committed by the same `scm:add`/`scm:checkin` steps as the C files;
  - optionally `-DskipRustLint`, which skips the `rust-lint` profile (§13.3) in the release builds;
  - for the convenience lib package with the Rust core, the property set of §13.5.
- **Source release.** The `apache-release` source-release assembly includes the whole tree, so it includes `zookeeper-client-rust` with no descriptor change. It is the only distribution channel of the native crates (§10).
  - `target/` and the git-ignored generated jute are excluded, by the descriptor's default excludes and because they are generated.
  - Crate sources are **not** vendored into the source release (§3.10). Offline builds use `--with-cargo-vendor`, `-Drust-cargo-vendor=DIR`, or `-Dcargo.offline` with a pre-populated `CARGO_HOME`; distribution builds use the system-crates mode (§3.11).

### 13.5 Binary assembly (`lib-package`)

The C convenience lib package (`apache-zookeeper-<version>-lib.tar.gz`) is built by the `lib-assembly` execution of `zookeeper-assembly/pom.xml` (pom.xml:160-175), which runs only under `-Pfull-build` (`skip.lib.artifact`, pom.xml:34-41). Whether a release builds it, with or without the Rust module, is the release manager's choice. When it includes the Rust module, it carries both cores.

- **Legacy-core package.** Without the Rust module, `src/main/assembly/lib-package.xml` is unchanged: it collects `include/**/*` and `lib/*` from `zookeeper-client-c/target/c` into `usr/`, `bin/*` into `usr/`, and the C client's `LICENSE`.
- **Package with the Rust module.** The build uses exactly these properties: `-Pfull-build -Drust-client=side-by-side -Drust-tls=yes -Drust-tls-provider=ring`, with `c-client-openssl` and `c-client-sasl` at their defaults. `-Drust-client` activates the `rust` profile (§13.1). `rust-tls=yes` makes TLS part of both trees on every build host; on a host without OpenSSL headers the side-by-side rule of §6.1 stops configure, so the package never has a TLS-less legacy tree. The C module's `make install` then writes the legacy core to `target/c/lib/` and the Rust core to `target/c/lib/zookeeper-rs/` (§6.2, 08 §15.2).
- **Assembly.** The `lib-assembly` descriptor is the property `lib.package.descriptor`, which defaults to `lib-package.xml`. The profile `rust-lib-package` in `zookeeper-assembly/pom.xml`, activated by `rust-client=side-by-side` (a property-value activation), sets it to `lib-package-rust.xml`. That descriptor has the `lib-package.xml` fileSets, with `lib/zookeeper-rs` and `lib/zookeeper-rs/**` excluded from the `lib/*` set, plus:
  - before M5: `target/c/lib/zookeeper-rs/*` into `usr/lib/zookeeper-rs/`. The legacy core is in `usr/lib/`, and a process opts in to the Rust core with `LD_LIBRARY_PATH` (`DYLD_LIBRARY_PATH` on macOS) set to `usr/lib/zookeeper-rs`, as for a side-by-side install (08 §15.2);
  - from M5: `target/c/lib/zookeeper-rs/*` into `usr/lib/`, and `target/c/lib/*` (the legacy core) into `usr/lib/zookeeper-c/`. The Rust core is then the one that the default loader path finds, and a process selects the legacy core with the loader path set to `usr/lib/zookeeper-c`. Both trees have the same SONAME and one header tree, so the swap needs no relink;
  - in both forms, the `LICENSE-binary` of §3.10 and the Rust workspace `NOTICE` at the package root, beside the C client's `LICENSE`. Under the same property-value activation, the Rust module's `package` phase runs `dev/gen-license-binary.sh` into `target/lib-package/LICENSE-binary` (it needs `cargo about`), and the descriptor collects it from there.
- A build with `-Drust-client=yes` and the default descriptor gives a package whose `usr/lib/` holds the Rust core only, without `LICENSE-binary`. A lib package that carries the Rust core needs the property set above, because only that set adds `LICENSE-binary` and the Rust workspace `NOTICE`.

### 13.6 RAT and licensing hygiene

- Every new file that the excludes below do not cover carries the ASF header, including `.rs`, `build.rs`, `Cargo.toml`, `*.toml` outside `supply-chain/`, `xtask` source, shim C, `UNSAFE.md`, `shim/analyzer-suppressions.txt` and `.zks` scripts; `RustGenerator` (§8), `cargo xtask gen`, the cbindgen configuration and `cargo xtask difftest` emit it.
- The root pom RAT `<excludes>` gain these entries, under a `<!-- rust client -->` comment block next to the C client block (pom.xml:1121-1129):
  - `**/Cargo.lock`
  - `**/supply-chain/imports.lock`
  - `**/supply-chain/audits.toml`
  - `**/supply-chain/config.toml` (cargo-vet owns the format of these files and rejects other content under --locked)
  - `**/supply-chain/closure/*.txt`
  - `**/abi/*.tsv`
  - `**/abi/abixml/**`
  - `**/fuzz/corpus/**`
  - `**/abi/needed/*.txt`
  - `**/abi/layout/*.expect`
  - `**/abi/release-deltas/*.txt`
  - `**/tests/conformance/ledger.tsv`
  - `**/tests/conformance/tls/corpus/**`
  - `**/tests/conformance/sasl/*.pw` (SASL test password files: the library reads a password file's whole content as the secret, so they cannot carry a header)
  - `**/crates/zk-jute/src/generated/**` (generated and git-ignored; `RustGenerator` also emits the ASF header)

### 13.7 Interaction with the C module's build

- **Driven from Maven** (`CALLER=ANT`). The `configure` execution of `zookeeper-client-c/pom.xml` gains these arguments:
  - `--with-rust-client=${rust-client}`;
  - `--with-rust-staticlib=${project.basedir}/../zookeeper-client-rust/target/cargo/capi/libzk_ffi.a`;
  - `--with-rust-tls-provider=${rust-tls-provider}`;
  - `--with-rust-tls=${rust-tls}`.

  The arguments are passed in every configuration, always with an explicit `--with-rust-client`. Under an explicit `--with-rust-client=no`, configure ignores `--with-rust-staticlib`, `--with-rust-tls-provider` and `--with-rust-tls` (§6.1), so a legacy-core build ignores them. The C module links the staticlib that the Rust module already built and never builds Rust library code itself, so Maven builds the Rust code exactly once.
- **ABI check.** When `rust-client≠no` and cargo is available, the C module runs `make abi-check CARGO_SOURCE_ARGS="${zkrs.cargo.source.args}"` at verify, after `make install` (§5). The C module's verify antrun derives that property from `rust-cargo-vendor`, `rust-system-crates` and `cargo.offline` (or Maven offline mode `-o`), with the same `--offline --config …` arguments as the Rust module (§13.1, §13.2). The rule runs `cd $(RUST_WORKSPACE) && $(CARGO) $(CARGO_SOURCE_ARGS) xtask abi …`. This `xtask` run is the only cargo use in the C module; it builds no library code.
- **Properties passed through.** `c-client-openssl` and `c-client-sasl` keep their names and are passed through unchanged, as `--with-openssl=${c-client-openssl}` and `--with-sasl=${c-client-sasl}`. `--with-openssl` keeps the legacy autodetect, and its result decides whether `zookeeper_init_ssl` is exported (§4.8). OpenSSL is never linked with the Rust core. `--with-sasl` keeps the legacy autodetect: SASL is built when Cyrus is found, and otherwise configure warns "cannot build SASL support -- sasl2 not found" and builds without SASL (configure.ac:176-179). SASL support needs libsasl2-dev, because SASL stays on Cyrus in C.
- **One TLS decision for both modules.** The Rust module builds before configure runs, so it takes the TLS decision itself with `zookeeper-client-c/abi/tls-probe.sh`. The script reproduces configure.ac:42-67 for the Maven build with the same compiler (`$CC`, else `cc`) and the same `CPPFLAGS`/`LDFLAGS`: it adds `-IDIR/include -LDIR/lib` for `--with-openssl=DIR`, compiles a test that includes `openssl/ssl.h`, and links a call to `SSL_CTX_new` with `-lssl`. It then applies the rule of §6.1: TLS is on when the autodetect found OpenSSL or `--with-rust-tls=yes` is given, and `--with-rust-tls=yes` with `--with-openssl=no` is an error. It prints `tls=yes|no` and the default trust locations (`cert_file`, `cert_dir`) by the rule of §6.1, with `DIR/bin/openssl version -d` for `--with-openssl=DIR` when that program exists. configure runs its own unchanged probe and then checks the prebuilt staticlib: `zkrs_feature_tls` must be present exactly when its own decision is on (§6.1). A divergence between the two probes therefore fails configure with a message that names both results; it can never ship a library whose export set differs from the configured decision. The root property `rust-tls` (default `no`) carries `--with-rust-tls` to both the script and configure, so a Maven build on a host without OpenSSL headers can opt into TLS.
- **Standalone autotools or CMake builds** (`./configure --with-rust-client=yes` outside Maven, or `auto` when it selects the Rust core). With no `--with-rust-staticlib`, `make` runs `cargo build --locked --profile capi -p zk-ffi $(ZKRS_CARGO_FEATURES)` itself, with `CARGO_TARGET_DIR=$(abs_builddir)/cargo` (§6.2). This keeps the distro and source-release build paths working without Maven, as the C client does.

### 13.8 CI entry points

08 §14 owns the CI topology. The Maven entry points it uses:

- Every job that builds Rust code (it passes `-Prust` or `-Drust-client=…`) installs a pinned toolchain, MSRV 1.85 plus stable, with `clippy` and `rustfmt`, and caches `~/.cargo/registry` and `target/cargo`. Jobs that pass `-Prust-supply-chain`, and the `supply-chain` and `advisories` jobs, also install pinned cargo-deny, cargo-vet and cargo-audit; the `lib-package` job, which packages with `rust-client=side-by-side` (08 §14), installs pinned cargo-about.
- `full-build-rust-tests`: `-Pfull-build,rust,rust-supply-chain verify -Dtest=_ -DfailIfNoTests=false -DskipCppUnit`, which runs the cargo tests, the `rust-lint` profile and the supply-chain checks (08 §14).
- `full-build-dropin-cppunit`: `-Pfull-build -Drust-client=yes verify -Dtest=_ -DfailIfNoTests=false` (until M3 also `-Dc-client-openssl=no -Dc-client-sasl=no`, because TLS and SASL are M3 work; from M3 the root defaults apply, 08 §14), which activates the `rust` profile through the property. It runs the cppunit public-API suite and the conformance corpus against the Rust-backed libraries; the differential `[diff]` tier runs in the side-by-side `c-client-rust.yaml` jobs, where every case that a fix touches is a DEV test under its DV-FIX row (08 §4.1, §14).
- The provider jobs `tls-aws-lc` and `tls-fips` are verification jobs of `c-client-rust.yaml` on Linux x86_64 and aarch64; they configure with `--with-rust-tls-provider=aws-lc-rs` and `--with-rust-tls-provider=aws-lc-rs-fips` (08 §14); the Maven spelling of the option is `-Drust-tls-provider`.
- The system-crates job adds `-Drust-system-crates=<dir>` over a Debian/Fedora-style crate directory (§3.11, 08 §14).
- CI uploads `target/cargo/TEST-*.txt` on failure, as for cppunit.
- The other jobs define no `rust-client` property and pass no `-Prust` until M5, so the root default `no` applies, and they build no Rust code and need no cargo; from M5 the `full-build-*` jobs pass `-Drust-client=yes` (§6.5), except `full-build-cppunit-tests`, which keeps building and testing `-Pfull-build` alone on the legacy core, without cargo (08 §14).
- `Jenkinsfile` and `Jenkinsfile-PreCommit` need no change for agents without cargo; they pass `-Prust` once their agents have cargo, and from M5, on agents with cargo, `-Drust-client=yes` (§6.5). `Jenkinsfile-s390x` passes `-Prust` from M2 and stays on the legacy core for its C libraries (08 §13, §14). `-Pfull-build` alone never needs cargo, so an agent without cargo keeps building the legacy core, and a job adds `-Prust` whenever its agents get cargo (08 §14). s390x is a Rust tier-2 target.

## Interfaces defined

- Callback-frame contract (normative, all documents): no application code (completions, watchers, auth completions, log callbacks/stream, Cyrus callbacks) runs with a Rust frame on the stack on any thread; all such work leaves Rust as an ordered outbox drained by C after the zkrs_* call returns.
- Outbox ABI: zkrs_outbox on the C stack (ZKRS_OUTBOX_INIT); zkrs_outbox_next(&ob, &item) yields one item at a time as borrowed views valid until the next call on ob; zkrs_outbox_finish (frees an empty outbox and resets ob->impl to NULL, so the same ob is reused); zkrs_outbox_requeue from the drain's cleanup handler on unwind (untaken items are delivered at the start of the next driving call). Item kinds: LOG, RESULT (async/sync/auth by zks_req mode; a zkrs_result_view; at delivery C calls the stateless abi::raw writers zkrs_write_result / zkrs_write_multi, which build the legacy C layout with libc malloc/calloc, and C then delivers and frees in legacy order; sync: zkrs_write_result fills the heap record and the waiter copies out with zkrs_sync_copy_out), WATCH (node and session; a zkrs_str path view, copied by zkrs_write_path at delivery), AUTH_LOCKPOINT, SASL{START (gen + local/peer zkrs_bytes carrying the platform sockaddr_in/sockaddr_in6 image written by zk-ffi), STEP (gen + bounded token view), DISPOSE (gen)}, THREAD{JOIN|DETACH, IO|DISPATCH}. The drain takes the SASL params (including the NUL-terminated mechlist copy) from the shim session through zks_sasl_sess_params and passes the item's gen to zks_sasl_start(s, gen, p, local, peer, out) / zks_sasl_step(s, gen, challenge, out) / zks_sasl_dispose(s, gen); the glue returns STALE for a stale gen without a Cyrus call, and formats local/peer with getnameinfo(NI_NUMERICHOST|NI_NUMERICSERV) as "%s;%s"; zks_sasl_set_gen (defined in zks_sasl_cyrus.c) records the gen after START. getsockname/getpeername failures are core log sites through handle_sasl_addr_failed(gen: u32, which: SaslAddr{Local, Peer}, now).
- Step protocol: zkrs_step {ZKRS_DONE, ZKRS_MORE, ZKRS_RELEASE, ZKRS_EXIT}; barriers after AUTH_LOCKPOINT, auth completion groups, each run of consecutive sync (Direct) RESULTs with no callback item between them (mt IO step and close), SASL START and STEP items, st session events/DrainPoints and every THREAD item, and, when LOG items are pending, before each Blocking vtable call (resolve, rng_reseed) in init, zoo_set_servers, the mt IO step and the st zookeeper_interest/zookeeper_process calls (05 §5), and before the IO step's TLS file reads (05 §5), and at the init SASL point (sasl = 1: after resolution and the reseed, before the waker, 05 §15.1) (SASL DISPOSE is ordered, not a barrier); C drains and calls zkrs_resume(core, &ob, &rc) (zkrs_interest_resume in the st zookeeper_interest); a zkrs call never blocks while its outbox is non-empty.
- Entry names: zkrs_io_step, zkrs_wait_dispatch (returns a batch), zkrs_interest, zkrs_process, zkrs_close(core, role, &ob, &rc), zkrs_resume, zkrs_interest_resume(core, &o, &written, &ob, &rc), zkrs_sasl_result(core, gen, view), zkrs_feature_tls() (present only with the tls feature), zkrs_add_auth_check(core, cert, cert_len) (stages 1-3, no lock, before zoo_lock_auth), zkrs_add_auth(core, scheme, cert, cert_len, token, flags, ob) (05 §6 owns the signatures).
- mt thread loops are C (shim/zks_threads.c): created by pthread_create(NULL attrs) / _beginthreadex after zh->core is stored; three-party start latch; role in C TLS; close keyed on the caller role with THREAD items executed by C; final close on the thread that drops the last reference.
- Host vtable: Pure {discard_token, snapshot_default_watcher, store_client_id, log_enabled, conn_permute_disabled, rng_random, rng_drand48}; Blocking {rng_reseed, resolve} callable only with the &Unlocked proof; no TLS, SASL, delivery, log-emit, auth-lock or thread entries.
- Tokens: completion token NonZeroUsize(zks_req*), consumed exactly once by a RESULT item or discard_token; Drop calls discard_token (sync record gets ZCLOSING and is signalled); abort only after host release; zoo_add_auth token retained iff ZKRS_OK.
- Crate graph: zk-jute <- zk-proto <- zk-driver <- {zk-client (apache-zookeeper), zk-ffi}, and zk-client <- zk-recipes (apache-zookeeper-recipes, forbid(unsafe), M5R); zk-ffi never depends on zk-client and is never built with --workspace; dirs crates/<name>, plus fuzz/ and xtask/; every crate is publish=false and the native crates ship in the ASF source release only; zk-ffi is staticlib only and nothing links it as an rlib (its Rust tests are #[cfg(test)] unit tests, the C-side stand-ins in src/abi/standin/; the ctest layout checks of the zkrs.h types and the abi/legacy.rs mirrors are in crates/zk-ffi-layout); every unsafe operation in zk-ffi other than the export attributes is in abi::raw, the only reader and writer of C memory; fuzz/ and crates/zk-ffi-layout are their own workspaces, excluded from members, and zk-ffi-layout builds on the stable toolchain only.
- Cargo features (same names in zk-ffi and zk-driver): tls = [zk-driver/tls]; tls-ring = [tls, zk-driver/tls-ring]; tls-aws-lc-rs = [tls, zk-driver/tls-aws-lc-rs]; tls-aws-lc-rs-fips = [tls, zk-driver/tls-aws-lc-rs-fips] (Linux x86_64 and aarch64); zk-driver also tokio (Unix only), std-thread; tls with no provider or with two providers is a compile_error!; pkcs8 encryption, pkcs5 pbes2/3des/sha1-insecure, pbkdf2, scrypt, hmac, sha1, aes, cbc, cipher, des and md-5 only with the non-FIPS providers (the FIPS build enables pkcs8 with only its pkcs5 feature, does not compile legacy_pem.rs, and derives and decrypts with aws_lc_rs::pbkdf2 and aws_lc_rs::cipher); no --all-features builds of zk-driver/zk-ffi.
- TLS code in zk-driver/src/net/tls/ (03 §8.2.1 owns the trait signatures): mod.rs (TlsConn with feed_eof() -> Result<(), TlsFailure>, TlsConnector::new_conn(&self, &Unlocked, TlsPurpose, &Endpoint) -> Result<Box<dyn TlsConn>, TlsSetupError{Config{step: zk_proto::TlsConfigStep}, Resource{at: zk_proto::TlsResourceAt}}> with no time argument; called on TCP-connect completion before handle_tcp_connected; TlsConnector::path_for(step) -> Option<&[u8]>; TlsTransport), conn.rs (RustlsConn), connector.rs, pem.rs (RFC 1421 PEM over base64ct), x509.rs, trust.rs, legacy_verify.rs (LegacyChainOnly), v1_leaf.rs (the v1-leaf verifier, 06 §3.14), identity.rs, legacy_pem.rs.
- Drop-in closure goldens in supply-chain/closure/: zk-ffi-<target>.txt (TLS on, ring; default), zk-ffi-<target>-aws-lc-rs.txt (TLS on, aws-lc-rs + aws-lc-sys; Linux x86_64 and aarch64, other targets build without a golden), zk-ffi-<target>-aws-lc-rs-fips.txt (TLS on, aws-lc-rs fips + aws-lc-fips-sys; Linux x86_64 and aarch64), zk-ffi-<target>-notls.txt; native-<featureset>.txt for zk-client and zk-recipes; checked by cargo tree -p zk-ffi -e normal,build per target and feature set, with name and version per line (exact versions; names and semver-compatible versions in the system-crates mode); TLS-off set {rustix (std,net,event,pipe,use-libc), libc, errno, bitflags, linux-raw-sys (Linux), windows-sys (Windows), zeroize}; TLS-on adds rustls, rustls-pki-types, rustls-webpki, untrusted, the provider, pkcs8, pkcs5, der, spki, pem-rfc7468, pbkdf2, scrypt, hmac, sha1, sha2, aes, des, cbc, cipher, md-5, base64ct and their transitive closure (the FIPS closure without pbkdf2, scrypt, hmac, sha1, aes, cbc, cipher, des and md-5); openssl*, native-tls, rustls-pemfile, webpki-roots banned; rustls-native-certs banned except with wrappers = ["rustls-platform-verifier"] and openssl-probe banned except with wrappers = ["rustls-native-certs"], and rustls-platform-verifier is admitted only under apache-zookeeper (never in a zk-ffi closure); aws-lc-rs only with wrappers = ["rustls", "rustls-webpki", "apache-zookeeper-driver"] (zk-driver tls-aws-lc-rs and tls-aws-lc-rs-fips); aws-lc-sys only with wrappers = ["aws-lc-rs"]; aws-lc-fips-sys denied by deny.toml and admitted with wrappers = ["aws-lc-rs"] only by deny-fips.toml for the FIPS build.
- R6 environment rule: the drop-in's Rust code reads only SSL_CERT_FILE and SSL_CERT_DIR, only on TLS handles, once per TLS attempt or probe, only when zkrs_init_args.tls_env_allowed; otherwise it uses the compile-time ZK_TLS_DEFAULT_CERT_FILE/ZK_TLS_DEFAULT_CERT_DIR (from --with-rust-tls-default-paths, default the configure-recorded OPENSSLDIR from `openssl version -d` of $with_openssl/bin/openssl (CMake OPENSSL_ROOT_DIR/bin/openssl) for a named installation, or of the first openssl on PATH when none is named (a PATH binary is never used for a named installation); when the selected binary does not exist, the §6.1 platform list /usr/lib/ssl /etc/pki/tls /etc/ssl /usr/local/ssl /usr/local/etc/openssl@3 /opt/homebrew/etc/openssl@3 applied on the build host: first existing <dir> gives <dir>/cert.pem and <dir>/certs; zk-driver/build.rs applies the same list for native cargo builds with both variables unset); no fallback when a variable is set; native tls_env_allowed is zk-client's getuid()==geteuid() && getgid()==getegid() through rustix; openssl_probe::probe() is never called and openssl-probe is in no drop-in closure; plus RUST_BACKTRACE on the panic path; rustls providers are passed per ClientConfig, never installed process-wide.
- zkrs_init_args carries flavor (ZKRS_FLAVOR_MT|ST), host string, recv_timeout, client id, flags, tls and the nullable TLS fields (tls_ca, tls_cert, tls_key, tls_passwd), tls_env_allowed, sasl, xid_seed, sock_cloexec, c_char_signed; no layout cookies; zk-ffi uses Compat::LEGACY_C, which includes the core behavior fixes (tls_verify = LegacyChainOnly); DriverMode st = LegacyExact+Inline, mt = Precise+Dispatch.
- zkrs_interest_out.fd is intptr_t (-1 = none); the WIN32 shim writes through SOCKET*.
- Pre-link contract (abi/prelink.sh): inputs libzks_<flavor>.a (shim + SASL glue + recordio.o + zookeeper.jute.o, PIC, -ffunction-sections) + libzk_ffi.a (with provider objects) + keep list; output zookeeper_<flavor>_all.o and .lo whose global defined symbols are exactly symbols.tsv(flavor, features) ∪ static-symbols.tsv; no undefined Rust, zkrs_* or OpenSSL references; ELF/MinGW via ld -r + objcopy --keep-global-symbols; Mach-O via cc -r -Wl,-exported_symbols_list -Wl,-force_load; not used on MSVC.
- Static archive contract: one member; globals = flavor golden exports ∪ static-symbols.tsv (161 + 18 oa_*/ia_*); no zks_*, zkrs_*, Rust std or provider globals; CMake hashtable = one-member archive with no globals.
- Golden ABI files: abi/symbols.tsv, absent.tsv, static-symbols.tsv, needed/<os>.txt (no libssl/libcrypto in any Rust-core configuration; libsasl2 with SASL; libgcc_s on Linux; on glibc < 2.34 also libdl, libutil and librt, and libpthread for st), layout/, abixml/, log-sites.tsv, release-deltas/; one export-list format source (abi/symbols.tsv and abi/static-symbols.tsv): gen-exports.awk (fmt=sym|map|exp|def|keep), and on CMake for Windows the ZkExports.cmake parser, whose output CI proves identical (§5); checks are cargo xtask abi check|static|headers|layout|consumers.
- Build options: autotools --with-rust-client=no|yes|side-by-side|auto (true=yes; default no before M5, auto from M5; yes and side-by-side are mandatory and stop configure when the host cannot build the Rust core; auto builds it when ZK_RUST_CHECK passes (not Cygwin, workspace found with its version equal to ZOO_VERSION, and either an existing --with-rust-staticlib with rustc found or rustc/cargo >= 1.85, --with-rust-target for cross builds, a target that ring supports when --with-rust-tls-provider is not given, and crates from --with-cargo-vendor, --with-system-rust-crates or a successful cargo fetch --locked; in a release to which DV-BLD-7 applies, the check also fails on a MinGW host with a reason that names DV-BLD-7, as §6.3 does on WIN32, so auto builds the legacy core and yes and side-by-side stop) and otherwise the legacy core with a notice, unless a Rust-only option (--with-rust-tls=yes, --with-rust-tls-provider with any value, --with-rust-tls-default-paths, --with-rust-workspace with any value but auto, --with-rust-staticlib, --with-rust-target, --with-cargo-vendor, --with-system-rust-crates) was given, which then stops configure; --disable-werror is not a request; no runs no check; an explicit --with-rust-client=no ignores the Rust-only options, and with --with-rust-client not given and the default no (before M5) a Rust-only option stops configure with <option> needs --with-rust-client=yes), --with-rust-tls-provider=ring|aws-lc-rs|aws-lc-rs-fips (fips: Linux x86_64/aarch64 targets, cmake and go required; a named ring on an unsupported target stops, unless --with-rust-staticlib is given), --with-system-rust-crates[=DIR] (default DIR /usr/share/cargo/registry; generated source replacement passed with --config, Cargo.lock regenerated offline; excludes --with-cargo-vendor), --disable-werror (drops -Werror from the shim and SASL flag sets; default on), --enable-debug (capi-dev, ZKS_HANDLE_REGISTRY), --with-rust-tls=yes|no (default no; TLS without the OpenSSL probe; error with --with-openssl=no; "TLS is built" = autodetect found OpenSSL or --with-rust-tls=yes, and then autotools has -DHAVE_OPENSSL_H in the library CPPFLAGS (AM_CPPFLAGS), as legacy, and -DHAVE_CYRUS_SASL_H when SASL is built), --with-rust-tls-default-paths=FILE:DIR (default from OPENSSLDIR), --with-rust-staticlib=PATH (prebuilt staticlib from Maven; make runs no cargo), --with-rust-target=TRIPLE (adds --target; required for cross builds), --with-openssl (legacy autodetect kept exactly: default yes probes openssl/ssl.h and -lssl SSL_CTX_new, warns "cannot build SSL support -- openssl not found" and builds without TLS when absent; the decision selects the zk-ffi TLS features; OpenSSL never linked), --with-sasl, --with-rust-workspace=DIR (default auto: $srcdir/rust, then $srcdir/../zookeeper-client-rust), --with-cargo-vendor; standalone builds use CARGO_TARGET_DIR=$(abs_builddir)/cargo; side-by-side installs $(libdir)/zookeeper-rs/libzookeeper_{st,mt}.so.2 with the same SONAME; CMake minimum 3.15 for every configuration (cmake_minimum_required at CMakeLists.txt:17; CMAKE_MSVC_RUNTIME_LIBRARY sets the floor), WITH_RUST_CLIENT (ON, OFF or AUTO; unset: OFF before M5, AUTO from M5; the configure rule, with ZK_RUST_TLS_DEFAULT_PATHS non-empty, ZKRS_WORKSPACE non-empty and ZOOKEEPER_BUILD_SHARED=ON Rust-only requests, a named ring on an unsupported target a stop unless ZKRS_STATICLIB is given, ZK_WERROR=OFF not a request, and any other value a FATAL_ERROR), WITH_OPENSSL (default ON, gated on OPENSSL_FOUND; HAVE_OPENSSL_H PUBLIC when TLS is built, CMakeLists.txt:213; HAVE_CYRUS_SASL_H PUBLIC when SASL is built, CMakeLists.txt:227), WITH_RUST_TLS (default OFF), ZK_RUST_TLS_PROVIDER, ZK_RUST_TLS_DEFAULT_PATHS, ZKRS_WORKSPACE (default empty: rust, then ../zookeeper-client-rust), ZKRS_STATICLIB, ZOOKEEPER_BUILD_SHARED (default OFF), Rust_TARGET, ZOOKEEPER_CARGO_VENDOR, ZK_SYSTEM_RUST_CRATES, ZK_WERROR (default ON) (cargo output in ${CMAKE_BINARY_DIR}/cargo).
- Maven (§13): module zookeeper-client/zookeeper-client-rust (packaging pom, not deployed) in the root-pom profile rust, activated by -Prust or whenever the rust-client property is defined; -Pfull-build alone builds no Rust code and never needs cargo (full-build lists zookeeper-client-c only; §6.5), and no skip switch exists; rust-client-dropin profile in the C pom (activates when the rust-client property is defined; its only effect is a provided pom-type reactor-order dependency on zookeeper-client-rust); root properties rust-client (no), rust-tls-provider (ring; aws-lc-rs, aws-lc-rs-fips), rust-tls (no; maps to --with-rust-tls and to the zk-ffi feature set), rust-system-crates (empty: off; the §3.11 mode), rust-cargo-vendor (empty: off; a cargo vendor directory, offline source replacement, no cargo fetch, excludes rust-system-crates), cargo.executable (cargo), with c-client-openssl and c-client-sasl passed through unchanged; -Dcargo.offline; antrun bindings with CARGO_TARGET_DIR=target/cargo and ZK_VERSION_H; process-sources runs an antrun check of rust-client (fails on any value but no, yes and side-by-side), cargo --version, a cargo/rustc >= 1.85 version check that compiles no workspace code ("Building the Rust client needs Rust 1.85 or later") and zookeeper-client-c/abi/tls-probe.sh (reproduces configure.ac:42-67 plus the rust-tls rule for the staticlib features, and emits the default trust paths); compile runs cargo build --locked --profile capi -p zk-ffi --features <set> (never --workspace; <set> = tls,tls-${rust-tls-provider} when TLS is on, else empty) and, separately, cargo build --locked --workspace --release --exclude zk-ffi; output target/cargo/capi/libzk_ffi.a passed to configure as --with-rust-staticlib, with --with-rust-tls=${rust-tls}; profiles cargo-test (-DskipCargoTest; cargo test --locked --workspace --exclude zk-ffi and cargo test --locked -p zk-ffi --features <set>), rust-lint (-DskipRustLint: fmt, clippy, ledger check), rust-supply-chain (deny with deny-fips.toml for the FIPS set, vet, audit, closure goldens), rust-test-coverage, rust-test-asan; C-module verify runs make abi-check when rust-client≠no and cargo is available; the package phase runs only dev/gen-license-binary.sh (needs cargo about), and only with rust-client=side-by-side; zookeeper-jute execution generate-Rust-Jute; release antrun replace-rustclient-files-during-release wired into the maven-release-plugin goals, whose arguments do not carry -Prust (a release that includes the Rust module needs -Prust, cargo >= 1.85, network access or a vendor directory, and the Cargo.toml/Cargo.lock rewrite; -DskipRustLint optional); rust-client accepts no, yes and side-by-side only; lib package with the Rust module: -Pfull-build -Drust-client=side-by-side -Drust-tls=yes -Drust-tls-provider=ring, descriptor lib-package-rust.xml through the rust-lib-package profile, legacy core in usr/lib/ and Rust core in usr/lib/zookeeper-rs/ before M5, Rust core in usr/lib/ and legacy core in usr/lib/zookeeper-c/ from M5, plus LICENSE-binary and the workspace NOTICE; RAT excludes of §13.6.
- Shim compile macros: HAVE_OPENSSL_H and HAVE_CYRUS_SASL_H follow legacy placement (autotools library CPPFLAGS; CMake PUBLIC on zookeeper); the shim's own TUs do not depend on HAVE_OPENSSL_H; ZKS_WITH_TLS and ZKS_WITH_SASL are the shim feature macros; among the shim TUs, only zks_sasl_cyrus.c and zks_sasl_cb.c have HAVE_CYRUS_SASL_H in effect when zookeeper.h is included (autotools per-target flags; under CMake's PUBLIC definition every shim TU gets it, and zks_internal.h undefines it before zookeeper.h in every TU without ZKS_SASL_GLUE, 05 §2) and include <sasl/sasl.h> and zks_sasl_cb.h; other TUs use zks_sasl.h (no Cyrus types); zookeeper_init_sasl and the leaked zoo_sasl_* live in zks_sasl_cyrus.c; hardening sets ZKS_HARDEN_SASL_CFLAGS (SASL TUs) and ZKS_HARDEN_CFLAGS (other shim TUs), probe-and-drop with a notice per dropped flag on every build; -Werror (/WX) on every shim TU by default, removed by --disable-werror / ZK_WERROR=OFF; ZKS_HANDLE_REGISTRY in debug builds only; the SASL TUs use the portable macros ZKS_ADD_OVF/ZKS_MUL_OVF (__builtin_*_overflow on GCC/Clang, range checks on MSVC) and ZKS_CLEANUP (__attribute__((cleanup)) on GCC/Clang, goto-out cleanup on MSVC) from shim/zks_sasl_cb.h.
- Shim file list: zkrs.h, zks_internal.h, zks_log_sites.h, zks_sasl.h, zks_sasl_cb.h, zks_data.c, zks_handle.c, zks_async.c, zks_multi.c, zks_sync.c, zks_drain.c, zks_log.c, zks_env.c, zks_errors.c, zks_resolve.c, zks_rng.c, zks_threads.c, zks_sasl_cyrus.c, zks_sasl_cb.c, zks_compat.c, zks_layout_check.c, zks_hashtable_stub.c (CMake).
- Cargo profiles: capi (release, panic=abort, lto=fat, codegen-units=1, opt-level=2) for shipped libraries; capi-dev (dev + panic=abort) for debug builds.
- Version contract: workspace version == ZOO_VERSION == AC_INIT == CMake project(); Cargo.toml rewritten by replace-rustclient-files-during-release; zk-ffi/build.rs reads the header from env ZK_VERSION_H (set by Maven/configure/CMake), falling back to the in-tree path, failing if neither exists.
- zk-jute: generated by the Java RustGenerator (Rcc -l rust, Maven generate-Rust-Jute) into the git-ignored crates/zk-jute/src/generated/zookeeper_jute.rs, listed in Cargo.toml include (data + proto modules; rcc.jj and JFile.genCode gain a rust branch); std-only, forbid(unsafe) build.rs that only checks the file exists (§8 message); builds from a source release run the jute generation first; drift checked by RustGenerator determinism and the three-way codec test; null-preserving JString/JBuffer/JVec.
- Log sites: abi/log-sites.tsv -> committed zk-proto/src/generated/log_sites.rs (LogSite enum) and shim/zks_log_sites.h via cargo xtask gen log-sites, drift-checked; the table is pinned to the final legacy C-core commit on main before the Rust core becomes the default, extracted by cargo xtask log-sites at that commit and re-extracted whenever the pin moves (legacy C-core changes may move or change log sites freely); zk-ffi stamps wall-clock time per LOG item; C re-checks logLevel at emit.
- Native crates (source release only, publish = false): apache-zookeeper-jute, apache-zookeeper-proto, apache-zookeeper-driver (implementation crates) and apache-zookeeper (native API; cargo-semver-checks against the previous release tag), plus apache-zookeeper-recipes (M5R); lockstep versions == ZooKeeper release with = pins; apache-zookeeper default features {tokio (Unix only), tracing, stream}; optional blocking, log, tls-rustls (zk-driver tls; needs exactly one of tls-ring (default provider), tls-aws-lc-rs, tls-aws-lc-rs-fips, each of which implies tls-rustls; no rustls-native-certs), tls-platform-verifier (rustls-platform-verifier; native only; needs tls-ring or tls-aws-lc-rs, compile_error! with tls-aws-lc-rs-fips), sasl, sasl-gssapi, serde; explicit feature lists, never --all-features.
- System-crates mode (§3.11): --with-system-rust-crates / ZK_SYSTEM_RUST_CRATES / rust-system-crates; generated source replacement in the build directory passed with --config; Cargo.lock regenerated offline, then --locked --offline builds; closure goldens compared by name and semver-compatible version; cargo-deny, cargo-vet, ABI goldens and localization checks unchanged.
- FIPS build (§4.10): --with-rust-tls-provider=aws-lc-rs-fips / tls-aws-lc-rs-fips; rustls fips + require_ems, FIPS-approved suites, groups and signature algorithms only, provider.fips() asserted at handle creation and ClientConfig::fips() checked on every per-attempt config; key loading through aws_lc_rs::pbkdf2 and aws_lc_rs::cipher, without the pbkdf2, scrypt, hmac, sha1, aes, cbc, cipher, des and md-5 crates and without legacy_pem.rs (R3); Linux x86_64 and aarch64; its own closure golden and deny-fips.toml; in a release only after the ASF legal review of aws-lc-sys and aws-lc-fips-sys.

## Decisions

| Decision | Rationale | Alternatives |
|---|---|---|
| Use the outbox callback model on every thread, with the mt IO-thread and dispatch-thread loops in C (zks_threads.c), so no application code ever runs with a Rust frame on the stack; the vtable Rust may call is Pure and Blocking C leaves only, and thread join/detach are THREAD outbox items executed by C. | C++ exception propagation stays exactly legacy on all threads (application thread: to the application; library thread: terminate at the C start routine), so DSP-14 is RE everywhere. Every lock-held-callback deadlock is removed by construction, and fn-pointer calls stay out of Rust. Barriers (CF3) reproduce sync-before-FIFO, auth-before-continuation and auth-before-join ordering, and requeue on unwind (CF4) reproduces legacy behavior when a callback throws mid-drain. | Push model through vtable trampolines with Rust frames below user code: cheaper, but aborts on exceptions and makes the exception behavior a visible deviation. A hybrid with the outbox on application threads and push on library threads: still leaves Rust frames under IO-thread callbacks. |
| Split zk-driver out (zk-jute <- zk-proto <- zk-driver <- {zk-client, zk-ffi}) and pin each drop-in closure with golden files and cargo tree in CI; build the staticlib with -p zk-ffi only. | The crate graph is the barrier that keeps tokio, tracing, native SASL and persistent watches out of libzookeeper_*, and building zk-ffi alone means feature unification cannot leak them. rustix covers every socket, poll and pipe need through libc, which keeps syscalls interposable. zeroize is the one third-party crate in zk-proto, with audited unsafe. | zk-ffi depending on zk-client with default-features=false: fragile under feature unification. socket2/mio in the drop-in: larger unsafe surface, unconditional CLOEXEC, and edge-triggered readiness conflicts with st. |
| TLS is rustls in zk-driver behind `tls` plus exactly one provider feature (`tls-ring` default; `tls-aws-lc-rs` and `tls-aws-lc-rs-fips` options), with the same names in zk-ffi; two providers or none is a compile_error!; TLS presence follows the legacy `--with-openssl`/`WITH_OPENSSL` autodetect exactly (the probe only decides; OpenSSL is never linked), with an additive opt-in `--with-rust-tls=yes`/`WITH_RUST_TLS`; a TLS build selects `tls,tls-<provider>`; HAVE_OPENSSL_H and HAVE_CYRUS_SASL_H keep legacy placement (autotools library CPPFLAGS, CMake PUBLIC), and the shim's own TUs do not depend on HAVE_OPENSSL_H. | K9 (00 §3): memory-safe TLS for the drop-in. One TLS implementation for both front ends, no OpenSSL library at build or run time, and no TLS vtable entries. Keeping the autodetect means `zookeeper_init_ssl` and `HAVE_OPENSSL_H` are present in exactly the configurations where legacy has them, so no build script sees a different export set; hosts without OpenSSL headers opt in explicitly. A hard error for two providers makes it impossible for a FIPS build to silently use ring. The names match the Maven compile command (§13.2). Feature selection is per configuration, not per flavor, so one Rust build still serves both flavors. | "ring wins when both are on" (a FIPS build passing `tls,tls-aws-lc-rs-fips` with `tls = tls-ring` would silently use ring). OpenSSL in the shim over memory BIOs (keeps libssl/libcrypto and C TLS code). The openssl crate in Rust (enlarges the unsafe closure). A runtime TLS flag with TLS always compiled (larger closure for --with-openssl=no builds). |
| One closure golden per target and shipped configuration (`zk-ffi-<target>.txt` for TLS on with ring, `-aws-lc-rs.txt`, `-aws-lc-rs-fips.txt` on Linux x86_64 and aarch64, `-notls.txt`), with the crypto provider the only C-compiling build script and its symbols localized by the prelink; PEM parsed in-tree over base64ct; providers passed per ClientConfig. | Each shipped configuration is checkable on its own, so the "unsafe confined to zk-ffi plus an audited closure" claim stays honest as the closure grows. The in-tree PEM reader keeps the RFC 1421 headers that legacy key formats need. Localization and version prefixes keep ring and aws-lc from colliding with an application's OpenSSL. Per-config providers keep the library from changing process-global rustls state. | One golden for all configurations (cannot detect a provider leak into the TLS-off build). rustls-pemfile or the pki-types PEM API (drops the headers). Process-default provider (interferes with other Rust code in the process). |
| trust.rs reads SSL_CERT_FILE/SSL_CERT_DIR only when the shim says the process is not set-uid (tls_env_allowed); otherwise it uses the compile-time ZK_TLS_DEFAULT_CERT_FILE/ZK_TLS_DEFAULT_CERT_DIR, which default to the OPENSSLDIR recorded at configure time, else to the first existing entry of the §6.1 platform list on the build host; openssl-probe is in no drop-in closure. | Matches OpenSSL's ossl_safe_getenv rule and its single compiled-in default file and directory, keeps the env read in one audited function, and keeps the closure free of a crate whose `probe()` reads the variables itself and widens trust through its fallback search. | Calling openssl_probe::probe() (reads the variables itself, ignores the set-uid rule, falls back to more locations). A run-time search of candidate directories (trust depends on which directories happen to exist). Reading the variables unconditionally (a set-uid program could be made to trust an attacker CA). |
| SASL stays Cyrus in two hardened C TUs (zks_sasl_cyrus.c, zks_sasl_cb.c), isolated as the only includers of <sasl/sasl.h> and zks_sasl_cb.h, built in their own convenience library with probed hardening flags, fuzzed and sanitizer-tested. | K10 (00 §3): the public SASL API is bound to Cyrus types. Isolation keeps Cyrus types out of the rest of the shim; a separate convenience library is the portable way to give two automake TUs their own flags; probing keeps old compilers working. zookeeper_init_sasl moves to zks_sasl_cyrus.c because its parameter type is a Cyrus type. | A Rust SASL engine for the drop-in (cannot honor application sasl_callback_t arrays). Hardening flags on the whole shim only (misses -Wconversion on the code that handles untrusted tokens). |
| Pre-link each flavor's entire library (shim objects, recordio.o, zookeeper.jute.o and the Rust staticlib with provider objects) into one relocatable object and localize everything except golden exports ∪ static-symbols.tsv (including the 18 oa_*/ia_*). The same object serves the shared library and the one-member static archive. | This is the only way to satisfy 08 S3 and E12 for both artifacts. A pre-link of the Rust staticlib alone leaves zkrs_* and zks_* global in the .a. Keeping oa_*/ia_* leaves recordio.c verbatim. Interposition is unaffected because ld -r keeps relocations against global symbols. | Pre-link Rust only (zkrs_*/zks_* global in the .a); a version script only (Rust std and provider symbols leak into the .a); dropping oa_*/ia_* (an extra narrowing for no benefit). |
| One golden file set and one option spelling (--with-rust-client=no\|yes\|side-by-side\|auto, WITH_RUST_CLIENT, rust-client, --with-rust-tls-provider, M0-M5 and M5R), with one export-list format source (abi/symbols.tsv and abi/static-symbols.tsv): gen-exports.awk, and on CMake for Windows the ZkExports.cmake parser, whose output CI proves identical (§5); cargo xtask is the only checker of built artifacts. | One abi/ directory and one configure interface. awk generation means a legacy-core or distro build needs no cargo, while the checks (object parsing across formats) stay in Rust. Side-by-side is a $(libdir)/zookeeper-rs install with the same SONAME. | xtask as the generator (requires Rust even for the legacy build); shell check scripts (two implementations). |
| Maven builds the staticlib once in zookeeper-client-rust (§13) and the C module links it through --with-rust-staticlib; standalone autotools and CMake builds run cargo themselves with CARGO_TARGET_DIR under the build directory, and pass --target only when a target is given. The staticlib is built with -p zk-ffi only, and the native crates in a separate --workspace --exclude zk-ffi invocation. The Maven staticlib's TLS features follow the legacy autodetect for c-client-openssl, or the rust-tls property, taken by abi/tls-probe.sh and cross-checked by configure. | Rust code is built exactly once under Maven, all output stays under target/ (mvn clean), the distro and source-release paths keep working without Maven, and host builds keep cargo's simple output layout. A --workspace build of zk-ffi would unify zk-client's zk-driver features (tokio, std-thread) into the staticlib. Taking the same decision as configure keeps the Maven export set equal to the legacy autodetect, and the configure cross-check turns any divergence into a build error. | Always passing --target (needless layout difference for host builds); the C module running cargo under Maven (two builds); deriving the features from the c-client-openssl value alone (yes means autodetect, so a host without OpenSSL would get a TLS staticlib that configure rejects); always building the staticlib with TLS (breaks --with-openssl=no). |
| Drop-in threads are created by C with default attributes (pthread_create NULL / _beginthreadex); the thread role is C TLS. CLOEXEC is parity: socket CLOEXEC follows the C build option as a runtime flag, and the waker pipe has no CLOEXEC. | Stack size, signal mask and thread naming match legacy exactly, Rust never reads RUST_MIN_STACK, and std::thread::current() never registers a TLS destructor on foreign threads. CLOEXEC on every fd is visible to exec'd children and is not UB, so parity is required. | std::thread with a queried stack size; CLOEXEC everywhere; a cargo feature for sock-cloexec. |
| zk-jute sources come from the Java RustGenerator (Rcc -l rust, generate-Rust-Jute) into a git-ignored directory, checked by a std-only build.rs, and guarded by RustGenerator determinism and the three-way codec test; types are null-preserving JString/JBuffer/JVec. | The Rust jute is generated exactly as the C jute is, with one generator implementation in zookeeper-jute, the module that owns the grammar. Null vectors (count -1) are visible to C applications (DAT-04). | A Rust generator crate with committed output (a second parser of the .jute grammar to keep in agreement, and a freshness test instead of a single generation step); build.rs generation (a second, Rust-side jute parser run at every cargo build). |
| Result materialization and the sync copy-out are zk-ffi raw writes: abi::raw builds the legacy C result layouts with libc malloc/calloc (legacy sizes and allocation choices) and writes the caller buffers of sync calls; C keeps the exported entry points, sync waiting, the callback invocation and the post-callback deallocation order. | proto decodes typed replies already; a raw-byte handoff would need a second decoder and a raw-body API out of proto. Materialization is pure data shaping, so keeping it out of C limits the shim to the code that must be C (memory owners, the callback-frame rule, variadic or ABI-fixed entries, Cyrus). The writes stay in the one audited module that touches C memory, with the legacy deserialize allocation pattern (calloc(count), malloc(len+1)), so free() and deallocate_* work unchanged; Miri, the layout mirrors check and a fuzz target cover them. | Running the generated C deserialize_* over raw reply bytes; C materialization from Rust-owned views in a shim file (a larger shim for data shaping only). |
| Panic policy: panic=abort with no custom hook, catch_unwind guard at every zkrs_* entry (inert in capi). The failure-path stderr write and RUST_BACKTRACE read and abort-versus-NDEBUG-continue are registered as DV-BLD-5. | Avoiding the default hook would need a process-global hook or nightly panic_immediate_abort. The difference arises only on internal bugs or OOM, where legacy is UB or asserts. | A custom panic hook (interferes with other Rust code); -Zbuild-std panic_immediate_abort (nightly only). |
| ZK_VERSION_H env var for the zk-ffi build.rs version check; portable dist-hook using tar --exclude with mkdir and tar -C; release rewrite of Cargo.toml by replace-rustclient-files-during-release. | Works in the in-tree, Maven and make-dist layouts, and with GNU tar and bsdtar. The build.rs check fails if one release rewrite ran without the other. | A fixed relative path (breaks in the dist tarball); GNU-only --one-top-level. |
| Windows: keep the Winsock start-up (MAKEWORD(2,0), exact 2.0 check, WSACleanup on mismatch) and clean-up (only in zks_release) and the init log line in the shim, make the fd intptr-sized and written through SOCKET*, use _beginthreadex, and include mt in M4 with a loopback socket-pair waker and WSAPoll via rustix behind the zk-driver `Poll` trait (with a zk-ffi-injected WSAPoll fallback for the drop-in); TLS trust from the two environment variables only. | Winsock initialization is process-visible legacy behavior that winport's removal must not drop; the version and cleanup rules are winport.c:273-292 and zookeeper.c:4058-4060. A 4-byte write into a SOCKET* on Win64 would be a new defect. Legacy OpenSSL's compiled-in OPENSSLDIR (for example C:\Program Files\Common Files\SSL) normally does not exist or holds no certificates on Windows; a populated one is covered by DV-TLS-2. | Registering the WSAStartup removal as a deviation; requesting Winsock 2.2 (different version negotiation); deferring Windows mt without a fallback rule (DV-BLD-7 keeps Windows on the legacy core only if the M4 spike fails); using the Windows certificate store (not what legacy trusts). |
| One license allowlist with CC0-1.0 on it; aws-lc-sys and aws-lc-fips-sys only through per-crate cargo-deny exceptions, the FIPS one only in deny-fips.toml; ASF convenience binaries with ring; a release includes an aws-lc build only after the ASF legal review of both sys crates. | ring, rustls and RustCrypto are Category A, so the default TLS build ships in convenience binaries. CC0-1.0 is a public-domain dedication that the ASF policy allows with attribution. aws-lc-sys and aws-lc-fips-sys contain OpenSSL-licensed code, so the review is a release condition, not a design choice. One feature table and one license list remove cross-document conflicts. | Banning aws-lc entirely (no FIPS path); per-crate exceptions for every CC0 crate; shipping aws-lc builds before review. |
| Three provider builds, `ring` (default), `aws-lc-rs` and `aws-lc-rs-fips`, each with its own closure golden; the FIPS build uses rustls `fips` with `require_ems` and FIPS-approved algorithms only, asserts `provider.fips()` at handle creation and checks `ClientConfig::fips()` per attempt, derives and decrypts keys with `aws_lc_rs::pbkdf2` and `aws_lc_rs::cipher`, omits the RustCrypto KDF, cipher and digest crates (`pbkdf2`, `scrypt`, `hmac`, `sha1`, `aes`, `cbc`, `cipher`, `des`, `md-5`) and `legacy_pem.rs`, and is validated and accepted on Linux x86_64 and aarch64 only. | A FIPS deployment needs a provider whose module is validated; selecting it by a distinct feature makes the choice explicit and keeps ring-only builds free of aws-lc. Omitting the legacy key crates means the FIPS library contains no non-approved key-decryption code. Restricting the targets keeps the option from claiming a validation it does not have. | Calling the plain aws-lc-rs build a FIPS option; a FIPS build that keeps the legacy key crates and rejects only in code; offering FIPS on every target. |
| The Rust module sits in its own root-pom profile `rust`, activated by `-Prust` or by any `rust-client` property; `-Pfull-build` alone never needs cargo (§6.5), and there is no skip switch. | A Rust-core C build always defines `rust-client`, so it always gets the staticlib, while legacy-core builds, Jenkins agents and contributors without cargo keep building exactly as before. Jobs that test Rust say so explicitly. | Listing the module in `full-build` behind a skip property (every legacy-core full build then needs cargo or an extra flag). |
| The nine behavior fixes DV-FIX-1..9 are in every Rust-core build, and no build system selects them; the legacy core does not change. | A fix goes in or it does not. Each one removes a defect and is registered with release-note text (07). One behavior per core keeps one drop-in to build, test and support, and the differential tier compares that drop-in with the pinned legacy oracle. | — |
| `zookeeper-client-c/CMakeLists.txt` has one `cmake_minimum_required(VERSION 3.15)` for every configuration, set by `CMAKE_MSVC_RUNTIME_LIBRARY`, the newest CMake feature that the Rust branch uses (§6.3). | The oldest capable version disturbs the fewest hosts, and one minimum gives both cores one policy set. Autotools builds of the C client do not use CMake themselves; only the FIPS provider's crate build does. | A newer floor (excludes more hosts with no feature gain). |
| The native crates ship in the ASF source release only, with `publish = false` everywhere; no packaging profile runs. | The source release is the ASF distribution channel, and no step of the build or release needs a packaged crate. | — |
| Crate sources are fetched: every build, a release build included, fetches them or reads a `cargo vendor` directory, with cargo's Cargo.lock checksum check under `--locked`; offline users vendor themselves, and distributions use the system-crates mode (§3.11), which regenerates the lockfile against their crates and relaxes only the version comparison of the closure goldens. | No third-party source enters the voted artifact, the release build still uses exactly the reviewed closure, and distributions can patch rustls or the provider in place for a security fix while every ABI and symbol check still applies. | Vendored sources in the source release; no distribution mode (distributions would patch Cargo.lock by hand). |
| The libraries ship without the GNU CET/BTI property note (DV-BLD-8) until stable rustc can mark its objects. | Stable rustc cannot mark its objects and a partial link drops the property; the legacy library is unmarked unless the distribution adds the flags. Waiting would block every release on a toolchain feature. | Blocking releases on a stable rustc option; nightly-only marking flags. |
| Hardening flags are probe-and-drop on every build, with a notice per dropped flag; `-Werror` is on by default and in CI, with `--disable-werror` / `ZK_WERROR=OFF` for packagers. | Older supported compilers and the glibc 2.28 floor image (FORTIFY level 2) must build; a notice keeps the drop visible. `-Werror` keeps the shim warning-clean in CI, and packagers with newer compilers can still build when a new warning appears. | Requiring the full flag set for releases (fails on the glibc 2.28 floor image); no `-Werror` (warnings accumulate). |
| Native recipes are the separate crate zk-recipes (`apache-zookeeper-recipes`, forbid(unsafe), on zk-client only), with Curator-compatible node layouts, delivered at M5R. | Recipes are built on the public API only, so they need no access to internals and cannot widen the drop-in closure. Curator-compatible layouts let Rust and Java processes contend on the same paths. | Recipes inside zk-client (larger API surface for every user); a private node layout (no interoperation with Java). |
| The native profile has an opt-in `tls-platform-verifier` feature; cargo-deny keeps rustls-platform-verifier and its trust crates out of every zk-ffi closure. | Native applications that want the OS trust store and policy get it explicitly, while the default native trust input and the drop-in stay on the OpenSSL locations of legacy. | Using the OS verifier by default (different trust input from legacy); not offering it (native users must write their own verifier). |
| The legacy C core stays a supported, buildable and maintained configuration: `--with-rust-client=no` / `-DWITH_RUST_CLIENT=OFF` prints no deprecation warning, `-Pfull-build` alone never needs cargo, and the pinned legacy reference build is the differential oracle (08 §4.1). | Hosts without Rust 1.85 or provider support (DV-BLD-4), Jenkins agents without cargo and distributions keep a supported library, and every differential test keeps its reference. | `-Pfull-build` building the Rust module by default (every full build would need cargo). |
| Build selection detects and requires: configure and CMake build what the host can build and honor what the user requires. `--with-rust-client=yes\|side-by-side`, `-DWITH_RUST_CLIENT=ON`, `--with-rust-tls=yes`, `-DZOOKEEPER_BUILD_SHARED=ON`, a named TLS provider, `--with-rust-tls-default-paths` / `ZK_RUST_TLS_DEFAULT_PATHS`, `--with-rust-workspace` / `ZKRS_WORKSPACE`, an explicit crate source, `--with-rust-staticlib` / `ZKRS_STATICLIB`, and `--with-rust-target` / `Rust_TARGET` are mandatory and fail with a clear message; the default is `no` / `OFF` before M5, under which an explicit Rust-only option stops configure and names `--with-rust-client=yes` / `-DWITH_RUST_CLIENT=ON`, and `auto` / `AUTO` from M5, which builds the Rust core when rustc and cargo ≥ 1.85 and the crates are available and `ring` supports the target (or a provider other than `ring` is named), and otherwise the legacy core with a notice. `--with-openssl` and `--with-sasl` keep their legacy warn-and-skip behavior. Under Maven the Rust module stays opt-in. | From M5 a plain `./configure` or `cmake` gets the Rust core wherever it builds, and a host without Rust keeps a working build with no new flag. An explicit request never degrades silently into a different library. The legacy options keep their behavior, so no existing build script changes its result. Maven profile activation cannot see a pom default (§13.1), so a detected default there would need cargo on every full build. | A fixed `yes` default from M5 (every host without Rust needs a new flag); silent fallback for explicit requests (a requested Rust core could ship as the legacy core unnoticed); an `auto` Maven default. |
| The convenience lib package with the Rust module is side-by-side: the legacy core in `usr/lib/` and the Rust core in `usr/lib/zookeeper-rs/` from the first opt-in release, the Rust core in `usr/lib/` and the legacy core in `usr/lib/zookeeper-c/` from M5, from one fixed property set (§13.5). Whether a release builds it is the release manager's choice. | Both trees have the same SONAME, so users switch with the loader path and never relink, and the package's default core follows the build default. One fixed property set gives one shipped configuration, the one that `LICENSE-binary` describes. | A Rust-only package (no fallback without a rebuild); two separate lib packages (two artifacts to vote and verify). |
