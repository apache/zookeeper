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

# C shim and the zk-ffi boundary

## 1. Scope, principles and the split of responsibility

This document specifies the two pieces between existing C applications and the safe Rust core:

- **`c-shim`**: C sources that define every exported symbol and data object of `libzookeeper_{mt,st}`, the variadic logging contract, the jute/recordio C code, the Cyrus SASL glue, all `errno` handling, **every invocation of application code** and the legacy post-callback deallocation order, the sync waiting, and the two mt library-thread loops.
- **`zk-ffi`**: the only shipped Rust crate allowed `unsafe` (the test-only zk-xcheck, crates/zk-ffi-layout and fuzz targets are never shipped, 01 §3.2), and only in `src/abi`: every unsafe operation other than the export attributes is in `abi::raw` (§8) (the test-only `#[cfg(test)]` stand-ins in `src/abi/standin/` excepted, 01 §3.8; nothing shipped). It exposes a private internal C ABI (`zkrs_*`) to the shim and calls back into the shim through one host vtable. No vtable entry can reach application code. Its audited raw module `abi::raw` is the only Rust code that reads or writes C memory. It builds the legacy C result layouts with libc `malloc`/`calloc` and performs the sync copy-out into caller buffers (§10.1).

Two security components sit on opposite sides of the boundary (06 §3 for TLS, 06 §4 for SASL):

- **TLS is Rust.** The drop-in uses rustls inside `zk-driver` (`zk-driver/src/net/tls/`, cargo feature `tls` plus exactly one provider feature, `tls-ring` (default), `tls-aws-lc-rs` or `tls-aws-lc-rs-fips`, which zk-ffi enables under the same names, 01 §3.3 R3, §4.8, §4.10) with the `LegacyChainOnly` verifier (`Compat::tls_verify`). OpenSSL is not used anywhere. The shim's only TLS work is to parse the `zookeeper_init_ssl` cert string, compute the set-uid environment rule, and pass four borrowed strings and that flag to Rust (§15.1).
- **SASL is C.** Its API is bound to Cyrus SASL, so the engine stays in shim C, confined to two translation units behind a narrow, bounded, generation-checked interface, with a secret trampoline and hardened build and coding rules (§19).

The callback-frame rule (K2) is normative for all documents:

> **R-CB (callback-frame rule).** No application code ever runs with a Rust frame below it, on any thread. Every `zkrs_*` call that can produce user-visible effects returns them in an ordered *outbox*. The calling C frame drains the outbox after Rust has returned. Where legacy runs user code and then continues work that the code could affect (auth completions, st session events, sync results, SASL steps, thread joins), Rust stops at a *barrier*, returns `ZKRS_MORE`, and continues only when C calls `zkrs_resume` (`zkrs_interest_resume` for a suspended `zkrs_interest`).

Six rules govern the split:

1. **All application-visible memory is libc memory.** Result vectors, strings, data buffers and watcher paths are allocated by `abi::raw` with libc `malloc`/`calloc`, with the same `malloc`-or-`calloc` choice and the same size per object as legacy, so the application's `free()`, `deallocate_*` and the generated jute deallocators work unchanged (§10.1). They come from the same CRT as the shim, because both are in one library. `struct Stat` temporaries, log buffers, the SASL callback block, the sync record, the `zhandle_t` itself and the endpoint buffer are created by C. C frees all of it with libc, in the legacy order. Rust never hands memory from the Rust allocator to application code, and C never frees Rust memory.
2. **Application pointers cross only as integers, except as writer destinations.** Completion functions, contexts, watcher functions and the handle back-pointer reach Rust only as `uintptr_t` tokens. Rust gives them back to C; only C casts them back and calls them. Caller output buffers (`results[]`, `buffer`, `path_buffer`, `stat`) reach Rust only as raw pointer arguments of the `abi::raw` writers, for the duration of that one call (§10.1).
3. **R-CB** (above). The mt dispatch loop and the mt IO loop are C functions that call Rust step functions. Cyrus SASL calls (whose callbacks are application code) are made by C from SASL outbox items. Thread join and detach are made by C from THREAD outbox items. Rust calls shim C only through vtable entries classed *Pure* or *Blocking* (§7). zk-ffi's direct libc and OS calls, such as `malloc` and `calloc` in the `abi::raw` writers (§10.1) and the WSAPoll fallback (§7), reach no application code.
4. **One home for each legacy rule.** `abi::raw` owns the legacy result layouts and every write into them after submission (the call-time `results[]` writes of `zoo_amulti` stay in C, §12.3): result materialization, the multi `results[]` writers, the sync copy-out with its truncation and NUL rules, and the NULL/-1 conventions (§10.1). The shim owns delivery (the callback invocation and the post-callback deallocation order), log formatting and `errno`. The pure legacy functions (`isValidPath`, `prepend_string`, `sub_string`, host-string parsing, create-op selection) live in `zk_proto::legacy` (02 §11). The validation *order* of each C entry point lives in zk-proto `submit` under `Compat::LEGACY_C`, which returns the final legacy code (`SubmitError.code`). zk-ffi only converts raw inputs (`abi::raw`, §8), passes `InputDefect` flags (bad value buffer, unusable ACL, shim marshal OOM) that zk-proto reports at the legacy stage, and maps the code to `zkrs_status`. Neither side re-implements the other's part.
5. **The delegation edges of §20.1 mirror C exactly, except the sync getconfig edges of DV-FIX-5 (§13).** The exported entries listed there call the same exported functions as legacy, through the PLT, and the same static helpers, so the AR-06 wrappers (`zoo_a*` and the sync wrappers) see the same hit counts (08 AR-06). Library-internal calls that legacy makes to other exported functions are not reproduced: `is_unrecoverable` (zookeeper.c:2104, 2116, 2633, 3537, 3594, 4127, 4185, 4229, 4264, 5009, 5309; mt_adaptor.c:467), `zoo_get_log_callback` through `LOGCALLBACK` at every enabled `LOG_*` site (zookeeper_log.h:28-39), `zoo_get_current_server` in log arguments (zookeeper.c:2114, 2144, 2227, 2244, 4031, …), `zoo_state` in `zoo_add_auth` (5314), `zookeeper_interest`/`zookeeper_process` from the mt IO loop (mt_adaptor.c:392-464), `zoo_cycle_next_server` (zookeeper.c:2679), `zoo_sasl_connect`, `zoo_sasl_client_start`, `zoo_sasl_client_step`, `zoo_sasl_mark_failed`, `zoo_sasl_client_create` and `zoo_sasl_client_destroy` (zookeeper.c:3188-3191, 3059, 3149, 1474, 746; zk_sasl.c:150), `zookeeper_close` from `api_epilog` (zookeeper.c:3222; the final close runs through `zks_release`, §15.4), `deallocate_Buffer` (zookeeper.c:615), `log_message` and `zoo_get_log_stream` for core-originated lines (every `LOG_*` site and zookeeper.c:2142; zk_log.c:193-194; Rust sites render through `zks_log_emit`, §16.2), `zoo_htonll` in the handshake (zookeeper.c:2304-2349), and the generated record deallocators (for example `deallocate_GetDataResponse`, zookeeper.c:3352; also 382, 3060, 3364, 3376, 3388, 3404, 3419, 3431, 3480, 3580, 5448, 5456, 5494, 5514), where C frees the record's members directly (§10). Like the `zoo_lock_auth`/`zoo_unlock_auth` counts, their interposition counts are not an ABI contract, and AR-06 (08 §3.6) excludes them. The shim takes the lock at the AUTH_LOCKPOINT sites (CC-7 (00 §4), §19.9) and once around `zkrs_add_auth` (§12.1).
6. **Public C input structs never cross into Rust, and `zkrs.h` never names a public type** (K3, CC-28 (00 §4)). `zkrs.h` contains only its own `repr(C)` types and the C scalar and pointer types of the `zkrs.h` listings (§5-§7, §10, §10.1, §11, §13, §16.2, §19.2); it names no public type. The shim marshals `zoo_op_t[]` and `struct ACL_vector` into these types, so union-variant selection happens in C, the language that defines the union. `zkrs.h` does not include `zookeeper.h`. The only public records that Rust touches are the result records that the `abi::raw` writers fill (`struct Stat`, `String_vector`, `ACL_vector`, `ACL`, `Id`, `zoo_op_result_t`). None of them is a union. `abi::raw` writes them through private `repr(C)` mirrors in `abi/legacy.rs`, and `crates/zk-ffi-layout` checks every mirror against the installed headers on every target (§2).

**Shim inventory and size.** The table gives each hand-written shim file, its role, an estimated size in lines, and the reason it is C. The reasons are: **(M)** it creates, frees or writes memory that the application sees; **(R)** R-CB requires that application code is called from a C frame; **(A)** it is a variadic or ABI-fixed exported entry, or data whose layout the ABI fixes; **(S)** it uses Cyrus types or calls Cyrus. The sizes are design estimates, sized against the legacy code that each file replaces. The build measures them, and 00 §1 states the total.

| File | Role | Est. lines | Why C |
|---|---|---|---|
| `zks_internal.h` | private structs, prototypes, `ZKS_CALL` | 250 | M (`struct _zhandle`, `zks_req`, `zks_sync`) |
| `zks_data.c` | 36 exported data objects | 100 | A (`STT_OBJECT`, size, copy relocations) |
| `zks_handle.c` | init, close, release, accessors, interest/process | 600 | A, M (`zhandle_t`, `clientid_t`, the TLS cert-string copy) |
| `zks_async.c` | `zoo_a*` entries, funnel, `zoo_add_auth` | 550 | A (exported entries, PLT delegation, §20.1), M (`zks_req`) |
| `zks_multi.c` | `zoo_amulti`, op-init helpers, op/ACL marshalling, multi completion | 300 | A (union variant selection, rule 6), M (call-time `results[]` writes, `zks_req` per-op kinds) |
| `zks_sync.c` | 23 sync wrappers, the sync record, waiting, vector hand-over (mt) | 450 | M (sync record, ownership of the result vectors, §13) |
| `zks_drain.c` | drain, drive, item dispatch, result and watcher delivery (§10, §11), unwind requeue | 230 | R (callback calls), M (post-callback frees in the legacy order) |
| `zks_log.c` | `log_message`, log streams, Site emitter, printf mini-driver | 450 | A (variadic `log_message`), M (per-thread log buffer), platform `snprintf` parity |
| `zks_env.c` | `log_env` | 80 | A (init-time C lines with real varargs) |
| `zks_errors.c` | `zerror`, `zoo_version_str`, `is_unrecoverable` | 100 | A (static strings returned to the caller) |
| `zks_resolve.c` | `resolve` entry, endpoint formatting, `zookeeper_get_connected_host` | 200 | M (per-thread endpoint buffer, caller `sockaddr`); `getaddrinfo` is libc |
| `zks_rng.c` | libc PRNG entries, `zoo_deterministic_conn_order` | 80 | A (process-global libc PRNG effects, exported global) |
| `zks_threads.c` | thread creation, start latch, IO and dispatch loops, THREAD items (mt) | 300 | R (loops that drain outboxes), A (thread ids in `zhandle_t`) |
| `zks_compat.c` | `zoo_lock_auth`/`zoo_unlock_auth`, `close_zsock` | 80 | A (leaked internals, archive-only `close_zsock`) |
| `zks_layout_check.c` | `_Static_assert` for `zkrs.h` | 150 | layout check of the C side |
| `zks_hashtable_stub.c` | CMake `hashtable` archive member | 5 | A (archive set) |
| `zks_sasl.h`, `zks_sasl_cb.h` | SASL glue interfaces | 120 | S |
| `zks_sasl_cyrus.c` | Cyrus glue, trampoline, `zookeeper_init_sasl`, leaked `zoo_sasl_*` | 650 | S, R (Cyrus callbacks are application code) |
| `zks_sasl_cb.c` | callback-block helpers, password callback, password mutex | 350 | S, M (single-`free()` block) |
| **Total, hand-written** | | **≈ 5,050** | |

The legacy C that these files replace is about 9,060 lines (`zookeeper.c` 5,939, `zk_sasl.c` 670, `mt_adaptor.c` 603, `zk_hashtable.c` 493, `winport.c` 307, `hashtable/hashtable.c` 274, `addrvec.c` 271, `zk_log.c` 209, `hashtable/hashtable_itr.c` 176, `st_adaptor.c` 115, at 60da6eb50). The protocol, session, watcher, server-list, TLS and I/O logic of that code moves to Rust. Generated and verbatim files are not counted above: `zkrs.h` (cbindgen), `zks_log_sites.h` (one row for each of about 150 pinned sites), `recordio.c` (374 lines, verbatim) and the generated jute C.

Pure data shaping is in zk-ffi, not in the shim. Result materialization (the legacy `String_vector`, `ACL_vector`, `Stat`, string, data and multi result layouts, and the `Id`/`ACL` contents) and the sync copy-out into caller buffers are `abi::raw` writers (§10.1). They allocate with libc `malloc`/`calloc` and write C memory only inside the audited raw module, so the shim has no materialization code. They are about 500 lines of Rust in `abi::raw`, which the unsafe accounting of 01 §3.8 and the G-rows of 08 count. Miri, the `zk-ffi-layout` tests and a fuzz target under ASan cover them (§22). C keeps the parts that must be C: the callback invocation and the legacy post-callback deallocation order in `zks_drain.c`, the exported entry points, the sync waiting and the vector hand-over in `zks_sync.c`, and the per-thread endpoint buffer. Op and ACL marshalling stays in C because C selects the union variant (rule 6). Endpoint formatting writes the per-thread buffer that `zoo_get_current_server` returns (M).

## 2. Layering, naming and files

```
application ──► c-shim (exported C ABI; installed headers byte-identical)
                  │ zkrs_* calls: borrowed inputs, outbox out      ▲ vtable calls: Pure | Blocking only
                  ▼                                                │ (no application code reachable)
                zk-ffi (staticlib; unsafe only in src/abi) ────────┘
                  │ safe Rust: tokens, InputDefect conversion, status mapping, outbox storage
                  ▼
                zk-driver (feature `tls`: rustls) ─► zk-proto ─► zk-jute   (#![forbid(unsafe_code)])
```

zk-ffi never depends on zk-client (K1), so tokio, tracing, native SASL and persistent watches cannot reach the C libraries.

| Prefix | Defined in | Visibility | Meaning |
|---|---|---|---|
| `zoo_*`, `zookeeper_*`, `ZOO_*`, `log_message`, `logLevel`, `(de)allocate_*`, `oa_*`/`ia_*`, … | c-shim, recordio.c, generated jute C | exported per `abi/symbols.tsv` (`.so`); `symbols.tsv` ∪ `abi/static-symbols.tsv` (`.a`) | the legacy ABI |
| `zkrs_*` | zk-ffi | never global in a shipped artifact (except MSVC, DV-BLD-3) | Rust-implemented private ABI |
| `zks_*` | c-shim | `static` or `visibility("hidden")`; never global in a shipped artifact (except MSVC, DV-BLD-3) | shim internals |

No other prefix is used for shim internals. Build requirement (K16, 01 §4.3–4.5): per flavor, the **whole** library content (shim objects, `recordio.o`, `zookeeper.jute.o`, the zk-ffi staticlib) is joined with `ld -r` (`cc -r` on Mach-O) into one relocatable object and localized to the golden set: the flavor's rows of `symbols.tsv` plus `static-symbols.tsv`, which holds the 161 header-declared archive-only symbols (154 `serialize_*`/`deserialize_*`, six recordio archive functions, `close_zsock`) and the 18 `oa_*`/`ia_*` functions that verbatim `recordio.c` defines globally. The `.so` adds a version script or exports list generated by `abi/gen-exports.awk`. There is no `-Bsymbolic`, so interposition is preserved. No `zks_*`, `zkrs_*`, Rust std or crypto-provider symbol is global in the `.so` or `.a`, and two Rust staticlibs can coexist in one link. `cargo xtask abi` is the only checker of built artifacts (K17).

Private header `zookeeper-client-c/shim/zkrs.h` is generated by cbindgen from zk-ffi, committed, drift-checked in CI (`cbindgen --verify`), never installed, and includes only `<stdint.h>` and `<stddef.h>`. Layout agreement is checked for the `zkrs.h` types and for the `abi::raw` mirrors of the public result records (`struct Stat`, `String_vector`, `ACL_vector`, `ACL`, `Id`, `zoo_op_result_t`, §10.1): `_Static_assert` in `zks_layout_check.c` covers the `zkrs.h` types, and the ctest-style layout tests in the separate test crate `crates/zk-ffi-layout` check the Rust definitions of both against the C headers (`zkrs.h`, `zookeeper.h`, `zookeeper.jute.h`) on every CI target. That crate is its own Cargo workspace with its own lockfile, like `fuzz/`, and builds on the stable toolchain only, because `ctest` needs rustc 1.88; the workspace-wide `cargo-test` and `rust-lint` invocations do not compile it, so the 1.85 floor of DV-BLD-4 holds (01 §3.4, §3.9). zk-ffi is `staticlib` only, and nothing links it as an rlib; its own Rust tests are `#[cfg(test)]` unit tests inside the crate (01 §3.2). There are no runtime layout cookies and no bindgen.

Shim sources (directory `zookeeper-client-c/shim/`; this list is authoritative, CC-29 (00 §4)):

| File | Contents |
|---|---|
| `zkrs.h` | private Rust↔C ABI (cbindgen output, committed) |
| `zks_internal.h` | `struct _zhandle`, `struct zks_req`, `struct zks_sync`, the thread-role TLS declarations and the inline `zks_current_role` (§3), `ZKS_CALL` macro, the `zookeeper_init_ssl` prototype under `ZKS_WITH_TLS`, the `zks_thread_item` and `zks_start_threads` prototypes under `THREADED`, and the prototypes of every cross-TU shim helper, among them `zks_init_common` (called from `zks_sasl_cyrus.c`), `zks_release` (called from `zks_drain.c`), `zks_aremove_watches` (called from `zks_sync.c`), `zks_multi_complete` (called from `zks_drain.c`), `zks_sync_complete` and `zks_sync_complete_local` (under `THREADED`, called from `zks_drain.c` and `zks_async.c`), `zks_log_emit` (called from `zks_drain.c`), `zks_log_site` (defined in `zks_log.c`, called from the two SASL TUs), `zks_submit_prepared` (called from `zks_multi.c`), `zks_format_endpoint` (defined in `zks_resolve.c`, called from `zks_log.c` for ENDPOINT args and from `zoo_get_current_server`), `zks_log_env` (defined in `zks_env.c`, called from `zks_init_common`), `zks_secure_zero` (used by `zks_handle.c` and the two SASL TUs) and `zks_req_new`/`zks_req_free` (used by `zks_async.c` and `zks_multi.c`; `zks_drain.c` uses `zks_req_free`), all hidden; the first include of every shim TU and the only include of `zookeeper.h`, after it undefines `HAVE_OPENSSL_H` and, unless `ZKS_SASL_GLUE` is defined, `HAVE_CYRUS_SASL_H` (§2 preprocessor rules) |
| `zks_log_sites.h` | `{func, line, fmt}` per pinned site, generated from `abi/log-sites.tsv`, committed, drift-checked |
| `zks_data.c` | the 36 exported data objects (§18) |
| `zks_handle.c` | `zookeeper_init`, `_init2`, `_init_ssl`, the common init, close, release, accessors, interest/process, pair cell, the definitions of the thread-role TLS variables (§3), the debug-build handle registry (`ZKS_HANDLE_REGISTRY`, §15.5) |
| `zks_async.c` | every `zoo_a*` entry point, the funnel, `zoo_add_auth`, `zks_aremove_watches` (hidden; not exported) |
| `zks_multi.c` | `zoo_amulti`, the five `zoo_*_op_init`, op/ACL marshalling, `zks_multi_complete` (calls the `abi::raw` multi writer, §12.3) |
| `zks_sync.c` | (`THREADED` only) the 23 sync wrappers, `zks_wget_children_` helpers, the static `zks_wgetconfig_`, the static `zks_remove_watches`, the sync record, its wait and the vector hand-over (§13) |
| `zks_drain.c` | `zks_drain`, `zks_drive`, item dispatch (THREAD items go to `zks_thread_item` under `THREADED`; SASL items go to the static `zks_sasl_item` under `ZKS_WITH_SASL`), result delivery (`zks_deliver_result`, §10), watcher delivery (`zks_deliver_watch`, §11), unwind cleanup |
| `zks_log.c` | `logLevel` users, `log_message`, stream functions, Site emitter, printf mini-driver |
| `zks_env.c` | `log_env`, `PACKAGE_STRING` |
| `zks_errors.c` | `zerror`, `zoo_version_str`, `is_unrecoverable` |
| `zks_resolve.c` | `resolve` vtable entry (getaddrinfo), endpoint formatting, `zookeeper_get_connected_host`, the static `zks_sockaddr_from_view` (§17) |
| `zks_rng.c` | `rng_*` vtable entries (libc PRNG parity), `zoo_deterministic_conn_order` |
| `zks_threads.c` | (`THREADED` only) thread creation (`zks_start_threads`, declared in `zks_internal.h`, §15.2), start latch, IO and dispatch loops, THREAD item execution (`zks_thread_item`, declared in `zks_internal.h`, §15.3) |
| `zks_sasl.h` | the SASL glue interface: no Cyrus types (§19.2) |
| `zks_sasl_cb.h` | (SASL builds) interface from `zks_sasl_cb.c` to `zks_sasl_cyrus.c`: Cyrus types; included only by the two SASL TUs (§19.5) |
| `zks_sasl_cyrus.c` | (SASL builds) engine glue: session, the accessors `zks_sasl_sess_params`, `zks_sasl_gen` and `zks_sasl_set_gen`, `zks_sasl_start/step/dispose`, secret trampoline and its per-session sink, `zookeeper_init_sasl`, leaked `zoo_sasl_*` |
| `zks_sasl_cb.c` | (SASL builds) `zoo_sasl_make_basic_callbacks`, `zoo_sasl_make_password_callbacks`, their callback functions, `zks_getsecret_unlocked` and the password mutex |
| `zks_compat.c` | `zoo_lock_auth`/`zoo_unlock_auth`, `close_zsock` (static archive only) |
| `zks_layout_check.c` | `_Static_assert` on every `zkrs.h` type, and `sizeof(int) == sizeof(int32_t)` for the `int *` outputs that the writers take as `int32_t *` |
| `zks_hashtable_stub.c` | CMake only: the single member, with no globals, of the `hashtable` static archive (CC-27 (00 §4)) |
| `analyzer-suppressions.txt` | the analyzer findings that do not block the merge, each with its justification (08 G-S6); not compiled |
| `src/recordio.c`, `generated/zookeeper.jute.c` | shipped verbatim (§18) |

The shim has no TLS source file. TLS runs in Rust (06 §3).

Preprocessor rules (the shim feature macros are `ZKS_WITH_TLS` and `ZKS_WITH_SASL`, set by the build, 01 §4.4, §6.1):

- The feature defines follow legacy exactly (01 §6.2, §6.3). Autotools puts `-DHAVE_OPENSSL_H` in the library CPPFLAGS when TLS is built (Makefile.am `OPENSSL_CPPFLAGS`) and `-DHAVE_CYRUS_SASL_H` when SASL is built (`SASL_CPPFLAGS`). CMake sets `target_compile_definitions(zookeeper PUBLIC HAVE_OPENSSL_H)` when TLS is built and `PUBLIC HAVE_CYRUS_SASL_H` when SASL is built (CMakeLists.txt:213, 227). "TLS is built" means that the legacy autodetect found OpenSSL, or that `--with-rust-tls=yes` / `WITH_RUST_TLS=ON` is set (01 §4.8).
- The shim's own translation units do not depend on `HAVE_OPENSSL_H`. Every shim TU includes `zookeeper.h` only through `zks_internal.h`, which undefines `HAVE_OPENSSL_H` before that include (06 §3.11). No shim TU therefore includes an OpenSSL header or uses an OpenSSL type, also on a `--with-rust-tls=yes` host without OpenSSL headers, and the shim needs no OpenSSL library. `zookeeper.h` declares `zookeeper_init_ssl` only under `HAVE_OPENSSL_H` (zookeeper.h:539-574), so `zks_internal.h` repeats its exact prototype under `ZKS_WITH_TLS`, and the `xtask abi headers` prototype check proves the signature (01 §4.8). `close_zsock` reads only `zsock_t.sock`, whose offset does not depend on `HAVE_OPENSSL_H` (zookeeper.h:313-324).
- `zookeeper.h` includes `<sasl/sasl.h>` when `HAVE_CYRUS_SASL_H` is defined (zookeeper.h:42-44). `zks_sasl_cyrus.c` and `zks_sasl_cb.c` are always compiled with `HAVE_CYRUS_SASL_H`, because they use the `zookeeper.h` SASL declarations (`zoo_sasl_params_t`, `zookeeper_init_sasl`). They define `ZKS_SASL_GLUE` before they include `zks_internal.h`. In every other shim TU, `zks_internal.h` undefines `HAVE_CYRUS_SASL_H` before it includes `zookeeper.h` (06 §3.11, §4.7.1), so only the two SASL TUs include `<sasl/sasl.h>`. No other shim TU depends on that macro: every other TU uses `ZKS_WITH_SASL` for build presence and refers to `struct zoo_sasl_params` only as an incomplete type. CI checks the compiler depfiles and fails if any other TU reaches `sasl/sasl.h` or `zks_sasl_cb.h`.
- `zks_internal.h` is the first include of every shim TU. `zookeeper_log.h`, `proto.h` and `recordio.h` are included only after it (zookeeper_log.h:22 includes `zookeeper.h`, which the include guard then skips), so no TU parses `zookeeper.h` with `HAVE_OPENSSL_H` or, outside the SASL glue, `HAVE_CYRUS_SASL_H` still defined by the CMake PUBLIC definitions. The CI depfile check also verifies that `zks_internal.h` precedes them.

The shim is compiled with `-fexceptions` (GCC/Clang) so that its frames carry unwind tables and run cleanup handlers during C++ unwinding, and with `-fsemantic-interposition` where accepted, default visibility for public functions, and without `-Bsymbolic`. The two SASL TUs add the hardening flags of §19.7.

zk-ffi is built **once** per TLS configuration (K15: one staticlib, `capi` profile, `panic = "abort"`, fat LTO) and linked into both flavors. The command is `cargo build --locked --profile capi -p zk-ffi --features <set>` (`--profile capi-dev` in debug builds, 01 §4.4), never with `--workspace`, because a workspace build would unify zk-client features (tokio, std threads) into the staticlib. `<set>` is `tls,tls-<provider>` when TLS is built, otherwise empty (01 §4.1, §6.1, §13.2). zk-ffi always uses `Compat::LEGACY_C`. The flavor is a field of `zkrs_init_args`; it selects `zk_proto::DriverMode` and never changes `Compat`.

## 3. `zhandle_t` and the Rust handle

`zhandle_t` stays opaque (`typedef struct _zhandle zhandle_t`, zookeeper.h:297). The tag `_zhandle` must be kept because it appears in C++ mangled names. The shim allocates it with `calloc`, so the pointer an application holds is a C object at a stable address.

```c
/* shim/zks_internal.h -- never installed */
struct zks_watch_cell {               /* default watcher and context, read and written as a pair */
    zks_spin_t lock;                  /* atomic_flag spinlock; st needs no extra library */
    watcher_fn fn;                    /* never NULL: zks_null_watcher sentinel */
    void *ctx;
};
struct _zhandle {
    clientid_t client_id;             /* zoo_client_id() returns &zh->client_id */
    struct zks_watch_cell dw;
    ZKS_ATOMIC(log_callback_fn) log_callback;   /* NULL: use the log stream */
    const zkrs_handle *core;          /* C-owned strong reference; set before any library thread starts */
    int flags;
    struct zks_sasl_sess *sasl;       /* SASL params and current Cyrus session; NULL without SASL */
#ifdef THREADED
#ifdef _WIN32
    HANDLE io_thread, dispatch_thread;   /* from _beginthreadex */
    HANDLE auth_lock;                 /* CreateMutex(NULL, FALSE, NULL), as winport.c:37-44; zoo_lock_auth/zoo_unlock_auth */
#else
    pthread_t io_thread, dispatch_thread;
    pthread_mutex_t auth_lock;        /* pthread_mutex_init(.., 0), as mt_adaptor.c:253; zoo_lock_auth/zoo_unlock_auth */
#endif
    struct zks_latch start_latch;     /* 3 parties: IO, dispatch, init thread */
#endif
#ifdef _WIN32
    int wsa_started;
#endif
};
```

- **`clientid_t`.** Written at init (`memcpy` of the caller's id, or zeroed) and by the Pure vtable entry `store_client_id`, which the core calls after a successful handshake and before it queues the CONNECTED/READONLY event. The write is a plain `memcpy`, unsynchronized, as in legacy (applications read the struct through the pointer).
- **Default watcher and context.** The default watcher and its context live in a pair cell under a spinlock. `zoo_set_watcher`, `zoo_set_context`, `zoo_get_context`, the `watch=1` wrappers and the `snapshot_default_watcher` entry read or write the pair inside the lock. Concurrent `zoo_set_watcher`/`zoo_set_context` cannot produce a torn `(new fn, old ctx)` capture. That is invisible to correct applications (legacy has the same tearing as a data race), so it is FU (07 WAT-15).
- **Log callback.** A relaxed atomic. Legacy stores it unsynchronized; the visible semantics are the same.
- **No TLS state.** The cert string is parsed at init, copied by Rust inside `zkrs_handle_new`, then zeroed and freed by the shim (§15.1). The handle holds no `zcert_t`.
- **`auth_lock`.** The shim initializes `zh->auth_lock` exactly as legacy initializes its auth lock (`pthread_mutex_init(&zh->auth_h.lock, 0)`, default attributes, mt_adaptor.c:253); the shim's call is `pthread_mutex_init(&zh->auth_lock, 0)`. On Windows legacy's `pthread_mutex_init` is winport's, which creates the mutex with `CreateMutex(NULL, FALSE, NULL)` (winport.c:37-44), and its lock and unlock are `WaitForSingleObject(.., INFINITE)` and `ReleaseMutex` (winport.c:26-35). The shim does not link winport (DV-BLD-2), so under `_WIN32` it declares `auth_lock` as a `HANDLE`, creates it with the same `CreateMutex` call, and `zoo_lock_auth`/`zoo_unlock_auth` return the same rc expressions as winport. A Win32 mutex is recursive, so a same-thread relock succeeds on Windows, as in legacy. It makes the call when it creates the mt handle (§15.1). `zoo_add_auth` and the auth lockpoints lock and unlock it as legacy does (§12.1, §19.9). A caller that already holds `zoo_lock_auth` and then calls `zoo_add_auth` on the same thread behaves as in legacy: the relock of a default mutex is undefined. The shim adds no diagnostic for that case, so it is not a deviation.
- **`core`.** `zkrs_handle *` is `Arc::into_raw(Arc<Inner>)`. C holds exactly one strong reference, dropped by `zks_release` (§15.4). Each mt library thread holds its own reference (§15.2). **Rule S1:** every shim function reads `zh->core` into a local before calling Rust and never reads `zh` or `zh->core` after a call that can release the handle (`zkrs_close`, `zkrs_process`, `zkrs_interest`, `zkrs_resume`, the thread exits), other than through the step protocol of §5.
- **Host token.** Every vtable entry that needs the handle receives `(uintptr_t)zh`. C casts it back and hands `zh` to watchers, so watchers receive the application's own pointer, even before `zookeeper_init` returns in mt.
- **Thread roles** (CC-4 (00 §4), K13). `zks_internal.h` declares `extern ZKS_THREAD_LOCAL const zhandle_t *zks_tls_zh; extern ZKS_THREAD_LOCAL uint32_t zks_tls_role;` with hidden visibility (`ZKS_THREAD_LOCAL` is `__thread` on GCC/Clang and `__declspec(thread)` on MSVC, as zookeeper.c:5354-5358), and `zks_handle.c`, which both flavors compile, defines them once, so `zks_threads.c`, `zks_sync.c` and `zks_handle.c` use the same per-thread variables. Each library thread's own start routine sets them before it arrives at the start latch. `zks_current_role(zh)`, a `static inline` function in `zks_internal.h`, returns `zks_tls_role` if `zks_tls_zh == zh`, else `ZKRS_ROLE_FOREIGN`. It reads only the calling thread's TLS, so it is race-free, allocates nothing, and registers no destructor. Rust never asks which thread it is on; C passes the role as an argument where it matters (`zkrs_close`, `zkrs_thread_enter`, `zkrs_thread_exit`). `std::thread::current()` and `thread_local!` with a `Drop` type are banned in zk-ffi and zk-driver's drop-in paths (clippy `disallowed-methods`).

## 4. Tokens, handle lifetime and the soundness argument

| Token | Rust type | C meaning | Consumers |
|---|---|---|---|
| completion | `CReq(NonZeroUsize)`: not `Clone`/`Copy` | `struct zks_req *` (malloc'd per accepted request) | exactly one of: handed to C in an outbox `RESULT` item, or `Drop` |
| watcher | `CWatcher { f: usize, ctx: usize }`: `Copy + Eq + Hash` | `(watcher_fn, void *)` | compared by value (legacy `(fn, ctx)` identity, zk_hashtable.c:151-160) |
| host | `AtomicUsize` in `FfiHost` | `zhandle_t *` | Pure/Blocking vtable entries; zeroed before `ZKRS_RELEASE` is returned |

**Token disposal** (CC-9 (00 §4)). `CReq` has two consumers. (a) Delivery: the token moves into an outbox item; when C takes the item (`zkrs_outbox_next`) Rust forgets it (`into_raw`). (b) `Drop`: calls the Pure entry `discard_token`, which frees the `zks_req` without calling anything, except that a *sync* record is completed with ZCLOSING and signalled so a waiter can never hang. `Drop` after the host was zeroed aborts with a diagnostic; that is a bug in final-close ordering, not a reachable state. The driver's "drop queued items" semantics (mt destroy, `into_leftovers`) are therefore well defined. zk-proto and zk-driver stay generic over `Tokens { Completion, Watcher, Auth }` (02 §3.1); zk-ffi uses `CReq` for both `Completion` and `Auth`.

**Handle lifetime rule.** Every `zkrs_*` entry that takes a handle does:

```rust
// zk-ffi/src/abi/entry.rs (no unsafe block; the pointer conversion is raw::handle_arc)
pub(crate) fn with_handle<R>(p: *const zkrs_handle, f: impl FnOnce(&Arc<Inner>, &mut Unlocked) -> R) -> R {
    // raw::handle_arc adds a strong reference (rule S1) and rebuilds the Arc; it is dropped when
    // `h` goes out of scope.
    let h = raw::handle_arc(p);
    let mut u = Unlocked::new_at_entry();
    ffi_guard(|| f(&h, &mut u))
}
```

The call frame owns a strong reference for its whole duration. No `zkrs_*` signature takes a Rust reference derived from the C pointer, and no method can deallocate `self`. Sync waits happen in C after `zkrs_submit` has returned, on a heap record (§13), so no Rust frame ever sleeps across a foreign close.

**Soundness argument.**

1. *No pointers in safe code.* Tokens are integers, `Send + Sync` without any `unsafe impl`. Rust never casts them back.
2. *Validity is C's obligation, as in legacy.* A `zks_req` lives from a `ZKRS_OK` submit until its delivery or discard. Application pointers (`fn`, `ctx`, `data`, multi `results[]`, `create_op.buf`, `set_op.stat`) must outlive the request; that is the legacy contract.
3. *Linearity.* Every retained token reaches C again exactly once. A token passed with any other status is not retained, and C frees it (CC-10 (00 §4): retained iff `ZKRS_OK`).
4. *Borrowed views.* C→Rust inputs (including the Cyrus output view in `zkrs_sasl_result`) are read through `abi::raw` (§8) during the call and copied into owned values. Rust→C views point into outbox storage that Rust owns and does not touch until the next `zkrs_outbox_next`/`finish`/`requeue` on that outbox. C reads them, and C passes them back to the `abi::raw` writers inside that window (§10.1). No view outlives that window. The writers write only through the destination pointers that C passes for that call, under the legacy contract that those buffers are valid, and every object they allocate belongs to C when the call returns.
5. *No re-entry under Rust locks.* No vtable entry reaches application code, and vtable entries never call `zkrs_*` (enforced by a C TLS flag `zks_in_vtable` checked by the `ZKS_CALL` macro that wraps every Rust call; release builds abort with a message). A thread therefore cannot re-enter Rust while Rust holds a lock on that thread.
6. *Threads.* All application code runs in C frames on the thread legacy uses (§12, §15).
7. *Panics.* `ffi_guard` (`catch_unwind`, then `abort`) wraps every export. Release builds use `panic = "abort"`.

## 5. The outbox and step protocol (normative for all documents)

```c
typedef struct zkrs_outbox { void *impl; } zkrs_outbox;   /* C stack; ZKRS_OUTBOX_INIT = {NULL} */

typedef enum {
    ZKRS_DONE    = 0,   /* the call finished; *rc is valid */
    ZKRS_MORE    = 1,   /* stopped at a barrier: drain, then call zkrs_resume
                           (zkrs_interest_resume for a suspended zkrs_interest) */
    ZKRS_RELEASE = 2,   /* final close finished: drain, then C frees zh and drops its reference */
    ZKRS_EXIT    = 3    /* library-thread loops only: leave the loop */
} zkrs_step;

typedef enum { ZKRS_ITEM_LOG = 1, ZKRS_ITEM_RESULT, ZKRS_ITEM_WATCH,
               ZKRS_ITEM_AUTH_LOCKPOINT, ZKRS_ITEM_SASL, ZKRS_ITEM_THREAD } zkrs_item_type;
typedef enum { ZKRS_THREAD_JOIN = 1, ZKRS_THREAD_DETACH } zkrs_thread_op;
typedef struct zkrs_item {
    uint32_t type;
    const zkrs_log_view *log;            /* LOG */
    uintptr_t token;                     /* RESULT: ownership passes to C */
    const zkrs_result_view *result;      /* RESULT: async, sync or auth (zks_req mode tells); carries delivery_logs */
    const zkrs_watch_event_view *watch;  /* WATCH: node or session event; carries delivery_logs */
    const zkrs_sasl_call *sasl;          /* SASL: START | STEP | DISPOSE (§19.2) */
    uint32_t thread_op;                  /* THREAD: JOIN | DETACH */
    uint32_t thread_role;                /* THREAD: ZKRS_ROLE_IO | ZKRS_ROLE_DISPATCH */
} zkrs_item;

int  zkrs_outbox_next(zkrs_outbox *ob, zkrs_item *it);  /* 1: item; 0: empty. Views valid until the next call on ob */
void zkrs_outbox_finish(zkrs_outbox *ob);               /* frees an empty outbox and resets ob->impl to NULL; the same
                                                           ob may then be passed to the next zkrs_* call */
void zkrs_outbox_requeue(zkrs_outbox *ob);              /* unwind path: undelivered items go back to the handle */
zkrs_step zkrs_resume(const zkrs_handle *h, zkrs_outbox *ob, int *rc);
zkrs_step zkrs_interest_resume(const zkrs_handle *h, zkrs_interest_out *out, int *written, zkrs_outbox *ob, int *rc);
```

- **Resume entries.** `zkrs_resume` continues a suspended step. `zkrs_interest_resume` is the same continuation for a suspended `zkrs_interest`, called only from the st `zookeeper_interest` loop (§14), because the outputs of `zkrs_interest` can be written only in the call that reaches `finish_interest`, and Rust keeps no C pointer past one call (§1 rule 2, §4). In the rest of this document, "`zkrs_resume` continues" covers both entries when the suspended step is a `zkrs_interest`.
- An outbox is created by Rust on the first item and holds an `Arc<Inner>` plus owned, NUL-terminated item storage. `zkrs_outbox_finish` frees it and resets `ob->impl` to NULL, so the C loops reuse one C-stack `zkrs_outbox` across calls (`zks_drive` into `zkrs_resume`, `zookeeper_interest` into `zkrs_interest_resume`, `zks_io_main` into the next `zkrs_io_step`, `zks_init_common` into `zkrs_resume`), and a call that produces no item leaves `impl` NULL. It is drained only by the thread that made the call. Items appear in exactly the order the core produced them.
- **Barriers** (Rust returns `ZKRS_MORE` right after the item):
  - `AUTH_LOCKPOINT`, emitted before the state change and completion collection at each legacy `zoo_lock_auth` site (§19.9; always emitted under `LEGACY_C`, a C no-op in st);
  - each auth completion group (all flavors), so a close made inside an auth callback is seen by the continuation;
  - each run of sync RESULT items (Direct completions) in the mt IO step and in close (CC-18 (00 §4): a Direct completion is a RESULT barrier item in the producing thread's outbox), so every waiter is signalled before later frames are processed or the close continues, as legacy does. A run of consecutive Direct results with no callback item between them shares one barrier, after its last RESULT (02 §5.3). A Queued completion between them (an mt FIFO publish entry, which runs no application code on the draining thread) does not end the run; any other item does. A run comes from one stage (T3, begin_close or destroy) and never spans reply frames: in the IO step each Direct reply ends its run, because handle_read stops after it (02 §5.3, §7.1). Delivery keeps the legacy order, because nothing observable runs between them;
  - session events in st (proto `Session{barrier}` / `DrainPoint`, `DriverMode::session_delivery = Inline`);
  - SASL `START` and `STEP` items (the result comes back through `zkrs_sasl_result` before `zkrs_resume`; for an item whose generation is stale, the glue returns `ZKRS_SASL_STALE` with no Cyrus call and the drain sends no result, §19.2);
  - every THREAD item: `JOIN` or `DETACH`, for the IO role or the dispatch role (a join must finish before close continues, and a detach must take effect before the next close step runs);
  - the **log flush**: when LOG items are pending before a Blocking vtable call (`resolve`, `rng_reseed`) or before the TLS file reads of the IO step, the step stops at a barrier after the last pending LOG item. C drains them, so the log callback runs from a C frame and sees each line before the blocking work starts, as in legacy, and `zkrs_resume` then continues into the blocking call. This costs one extra round trip, only on those paths and only when logs are pending. Every entry that can reach these calls returns `zkrs_step` (`zkrs_handle_new`, `zkrs_set_servers`, `zkrs_io_step`, `zkrs_interest`, `zkrs_interest_resume`, `zkrs_process`, §6);
  - **before any wait**: a step that would block in `poll`, in the close flush or close wait, or in the RW probe first returns `ZKRS_MORE` when LOG items are pending in its outbox; C drains them, so each line reaches the log callback before the wait, as in legacy (01 §3.6 CF5, 03 §3.3).
  - the **SASL point** of init: with `zkrs_init_args.sasl = 1`, `zkrs_handle_new` returns `ZKRS_MORE` with `*rc = ZKRS_INIT_SASL_POINT` after the resolution loop and the reseed and before the waker, so C creates the SASL session in the legacy order (§15.1). It is the only barrier with no item before it.

  SASL `DISPOSE` is ordered by the outbox and is not a barrier.
- **Untaken items** (CC-32 (00 §4)). If a drain is cut short by an exception, the cleanup handler requeues the untaken items, and the handle delivers them at the start of the next driving call on that handle, before any new work. Rust completes a suspended barrier continuation at the same point.
- **Log time** (CC-30 (00 §4)). zk-ffi stamps the wall-clock time (`SystemTime::now`, the same clock as `gettimeofday`) when it pumps each zk-proto Log output into the outbox, so zk-proto stays clock-free and delayed emission does not change timestamps. `pthread_self()` in the prefix is correct because the producing thread drains its own outbox. The Windows thread id is that of §16.

```c
/* shim/zks_drain.c */
static void zks_outbox_unwind(zkrs_outbox **g) { if (*g != NULL) ZKS_CALL(zkrs_outbox_requeue(*g)); }

/* Runs every queued effect in C frames. No Rust frame is below the callbacks. */
void zks_drain(zhandle_t *zh, zkrs_outbox *ob)
{
    zkrs_outbox *guard __attribute__((cleanup(zks_outbox_unwind))) = ob;
    zkrs_item it;
    ZKS_ASSERT_NOT_IN_RUST();
    while (ZKS_CALL(zkrs_outbox_next(ob, &it))) {
        switch (it.type) {
        case ZKRS_ITEM_LOG:            zks_log_emit(zh, it.log); break;
        case ZKRS_ITEM_RESULT:         zks_deliver_result(zh, it.token, it.result); break;
        case ZKRS_ITEM_WATCH:          zks_deliver_watch(zh, it.watch); break;
        case ZKRS_ITEM_AUTH_LOCKPOINT: zks_auth_lockpoint(zh); break;
#ifdef ZKS_WITH_SASL
        case ZKRS_ITEM_SASL:           zks_sasl_item(zh, it.sasl); break;     /* calls zkrs_sasl_result */
#endif
#ifdef THREADED
        case ZKRS_ITEM_THREAD:         zks_thread_item(zh, it.thread_op, it.thread_role); break;   /* mt only */
#endif
        }
    }
    guard = NULL;
    ZKS_CALL(zkrs_outbox_finish(ob));
}

/* Drives one zkrs call to completion. */
int zks_drive(zhandle_t *zh, const zkrs_handle *core, zkrs_step s, zkrs_outbox *ob, int *rc)
{
    for (;;) {
        zks_drain(zh, ob);
        if (s == ZKRS_MORE) { s = ZKS_CALL(zkrs_resume(core, ob, rc)); continue; }
        if (s == ZKRS_RELEASE) zks_release(zh, core);     /* zh and core are dead after this */
        return *rc;
    }
}
```

**C++ exceptions and `longjmp`.** A callback that throws unwinds through `zks_drain` and the public entry point to the application, exactly as legacy. On the way, the cleanup handler requeues undelivered items; the handle delivers them at the start of the next driving call (legacy leaves them queued in the same way). Trampolines free their temporaries and zero their password buffers in cleanup handlers. No ZooKeeper lock is held across application code, so no lock needs a cleanup handler to release it on unwind (§19.5). On a library thread, the exception reaches the C start routine and the runtime terminates, as legacy does. No Rust frame is ever unwound, so DSP-14 is RE on every thread. MSVC builds use `__try/__finally` in place of `cleanup`, except in the two SASL glue files, which use the portable `ZKS_CLEANUP` macro (§19.7). `longjmp` out of a callback skips the cleanup handlers; the handle state stays consistent because Rust is not on the stack, but the outbox storage leaks (legacy leaks too).

If an application Cyrus callback throws during an st SASL item, `zkrs_sasl_result` is never called for that generation. When the next driving call completes the suspended barrier continuation, zk-ffi records the `gen` of the SASL `START` or `STEP` item that C took but did not answer. Frame processing stays paused. The receive timeout tears the connection down and advances the generation (02 §12.1 T1). In that same core call, zk-ffi then passes the recorded call to the core as a result for the old generation: `handle_sasl_started` or `handle_sasl_stepped(old_gen, Err(SaslError{rc: ZSYSTEMERROR, errstring: empty, errdetail: empty}))`. The core ignores this result as stale, and it ends the wait of 02 §7.4. At the next prime response the core therefore yields `DISPOSE` for the old session and then `START` (§19.3), as legacy `zoo_sasl_connect` does after the stalled negotiation (zk_sasl.c:215-217). If the throw passes through a Cyrus frame that has no unwind tables, the process terminates in both cores (08 SU-09).

## 6. The private ABI: functions Rust exports

Every function is `ffi_guard`ed and called through `ZKS_CALL`. Out-parameters are written only where stated.

```c
/* ---- lifecycle ---- */
zkrs_step zkrs_handle_new(const zkrs_init_args *a, const zkrs_host_vtable *vt, uintptr_t host,
                          zkrs_handle **out, zkrs_outbox *ob, int *rc);
                                                     /* *rc: 0, or the init errno. *out stays NULL when
                                                        the call fails before the handle exists (parse
                                                        errors of the chroot); otherwise it is set before
                                                        the first barrier (log flush, §5), and a later
                                                        failure (a host entry's port error after the
                                                        earlier entries were resolved, resolution, waker)
                                                        needs zkrs_handle_abandon. With a->sasl = 1, a
                                                        ZKRS_MORE with *rc == ZKRS_INIT_SASL_POINT marks
                                                        the SASL point before the waker (§15.1) */
void zkrs_handle_retain(const zkrs_handle *h);       /* +1 strong reference (one per library thread) */
void zkrs_handle_release(const zkrs_handle *h);      /* -1 strong reference */
void zkrs_handle_abandon(const zkrs_handle *h);      /* init failed: discard all state, zero host, drop C ref */
void zkrs_thread_enter(const zkrs_handle *h, uint32_t role);                      /* api_prolog */
zkrs_step zkrs_thread_exit(const zkrs_handle *h, uint32_t role, zkrs_outbox *ob, int *rc); /* api_epilog;
                                                       may run the final close on this thread */
/* ---- build feature probe ---- */
uint32_t zkrs_feature_tls(void);                     /* present only with the tls feature; the shim built
                                                        with ZKS_WITH_TLS references it, so a mismatched
                                                        pair fails the prelink check (01 §4.1) */
/* ---- mt library-thread steps ---- */
zkrs_step zkrs_io_step(const zkrs_handle *h, zkrs_outbox *ob, int *rc);       /* one interest/poll/process */
zkrs_step zkrs_wait_dispatch(const zkrs_handle *h, zkrs_outbox *ob, int *rc); /* blocks; DONE with a batch, or EXIT */
/* ---- driving calls (any flavor) ---- */
zkrs_step zkrs_interest(const zkrs_handle *h, zkrs_interest_out *out, int *written, zkrs_outbox *ob, int *rc);
zkrs_step zkrs_process(const zkrs_handle *h, int32_t events, zkrs_outbox *ob, int *rc);
zkrs_step zkrs_close(const zkrs_handle *h, uint32_t caller_role, zkrs_outbox *ob, int *rc);
zkrs_step zkrs_resume(const zkrs_handle *h, zkrs_outbox *ob, int *rc);
zkrs_step zkrs_interest_resume(const zkrs_handle *h, zkrs_interest_out *out, int *written, zkrs_outbox *ob, int *rc);
                                                     /* zkrs_resume for a suspended zkrs_interest; writes *out
                                                        and *written in the call that reaches finish_interest;
                                                        Rust keeps neither pointer */
void      zkrs_sasl_result(const zkrs_handle *h, uint32_t gen, const zkrs_sasl_out *v); /* §19.2 */
/* ---- requests (never deliver callbacks; may queue log items) ---- */
zkrs_status zkrs_submit(const zkrs_handle *h, const zkrs_request *rq, uintptr_t token,
                        uint32_t delivery /* QUEUED | DIRECT */, zkrs_outbox *ob);
zkrs_status zkrs_add_auth(const zkrs_handle *h, const char *scheme, const char *cert, int32_t cert_len,
                          uintptr_t token /* 0: none */, uint32_t flags /* ZKRS_AUTH_F_ALLOC_FAILED */,
                          zkrs_outbox *ob);
zkrs_status zkrs_add_auth_check(const zkrs_handle *h, const char *cert, int32_t cert_len);
                                                     /* stages 1-3 of §12.1 only; no lock, no outbox */
zkrs_status zkrs_validate_ttl(int32_t mode, int64_t ttl);                     /* stateless */
/* ---- state and server list ---- */
int32_t zkrs_state(const zkrs_handle *h);            /* legacy ints incl. 0, 7, 999, -112, -113 */
int32_t zkrs_recv_timeout(const zkrs_handle *h);
zkrs_step zkrs_set_servers(const zkrs_handle *h, const char *hosts, zkrs_outbox *ob, int *rc); /* legacy rc */
int32_t zkrs_set_resolution_delay(const zkrs_handle *h, int32_t delay_ms);
void    zkrs_cycle_next_server(const zkrs_handle *h, zkrs_outbox *ob);
void    zkrs_current_server(const zkrs_handle *h, zkrs_sockaddr_view *out);   /* `cur` (02 §9.2); family 0 if none */
int32_t zkrs_peer_addr(const zkrs_handle *h, zkrs_sockaddr_view *out);        /* 0 iff state==3 and getpeername ok */
void    zkrs_sasl_mark_failed(const zkrs_handle *h);                          /* leaked zoo_sasl_mark_failed */
/* ---- result writers (abi::raw; stateless, no handle, no outbox; §10.1) ---- */
void    zkrs_write_result(const zkrs_result_view *v, const zkrs_result_dst *d);
char   *zkrs_write_path(const zkrs_str *s);          /* watcher path: malloc(len + 1); never NULL */
void    zkrs_write_multi(const zkrs_result_view *v, void *results /* zoo_op_result_t[] */,
                         const uint8_t *op_kind, int32_t count);
void    zkrs_sync_copy_out(const zkrs_sync_src *s, const zkrs_sync_dst *d);
/* ---- outbox: §5 ---- */
```

```c
typedef enum {
    ZKRS_OK = 0,             /* token retained; delivered exactly once */
    ZKRS_OK_LOCAL,           /* remove-watches local=1 done; token NOT retained */
    ZKRS_EARLY_BADARGS,      /* ttl or path stage */
    ZKRS_EARLY_STATE,        /* state < 0; add_auth state == 0 */
    ZKRS_LATE,               /* serialization stage: encode, InputDefect flags, closing */
    ZKRS_UNIMPLEMENTED,      /* multi sub-op type not in {1,19,2,5,13} */
    ZKRS_NOWATCHER,
    ZKRS_SYSTEM              /* add_auth: negative certLen or shim entry OOM */
} zkrs_status;

typedef struct zkrs_acl { int32_t perms; const char *scheme; const char *id; } zkrs_acl; /* NULL string -> jute null */
typedef struct zkrs_op {
    int32_t type;                        /* raw zoo_op_t.type */
    const char *path;
    const char *data; int32_t datalen;   /* create, set */
    int32_t version;                     /* delete, set, check */
    int32_t mode;                        /* create */
    const zkrs_acl *acl; int32_t nacl;   /* create: raw ACL_vector.count (NULL vector -> 0) */
    uint32_t flags;                      /* ZKRS_OPF_BAD_ACL */
} zkrs_op;
typedef struct zkrs_request {
    uint32_t op;                 /* ZKRS_OP_* */
    uint32_t flags;              /* ZKRS_F_CREATE2 | RAW_PATH | REMOVE_ALL | LOCAL
                                    | MARSHAL_FAILED | BAD_ACL */
    const char *path;            /* application pointer, may be NULL */
    const char *data; int32_t datalen;
    const zkrs_acl *acl; int32_t nacl;       /* NULL vector: acl NULL, nacl 0 */
    int32_t mode; int64_t ttl; int32_t version; int64_t reconfig_version;
    const char *joining, *leaving, *members; /* NULL -> jute null */
    uintptr_t watch_fn, watch_ctx;
    int32_t wtype;
    const zkrs_op *ops; int32_t ops_len;     /* marshalled prefix: up to and including the first unsupported type */
    int32_t count;                           /* original multi count */
} zkrs_request;

typedef struct zkrs_interest_out { intptr_t fd; int32_t interest; int64_t timeout_ms; } zkrs_interest_out;

/* resolve, zkrs_current_server, zkrs_peer_addr and ENDPOINT log arguments; never SASL: SASL
 * addresses are zkrs_bytes holding a platform sockaddr image (§19.2). */
typedef struct zkrs_sockaddr_view {
    uint32_t family;                         /* 0: none; ZKRS_AF_INET = 4; ZKRS_AF_INET6 = 6 */
    uint16_t port;                           /* host byte order */
    uint8_t  addr[16];                       /* network byte order; the first 4 bytes for IPv4 */
    uint32_t flowinfo, scope_id;             /* IPv6 only; 0 for IPv4 */
} zkrs_sockaddr_view;

typedef struct zkrs_init_args {
    uint32_t flavor;                         /* ZKRS_FLAVOR_MT | ZKRS_FLAVOR_ST, from #ifdef THREADED */
    const char *hosts;                       /* borrowed; parsed by zk-proto legacy rules */
    int32_t recv_timeout;
    uint32_t have_client_id;                 /* clientid != NULL */
    int64_t client_id; uint8_t client_passwd[16];
    int32_t flags;                           /* raw init flags (ZOO_READONLY, ...) */
    uint32_t tls;                            /* 1 only from zookeeper_init_ssl */
    const char *tls_ca, *tls_cert, *tls_key, *tls_passwd;   /* borrowed for the call; any may be NULL */
    uint32_t tls_env_allowed;                /* 1: Rust may read SSL_CERT_FILE/SSL_CERT_DIR (not set-uid) */
    uint32_t sasl;                           /* 1 when zookeeper_init_sasl got non-NULL params */
    int32_t xid_seed;                        /* (int32_t)time(0) (K8) */
    uint32_t sock_cloexec;                   /* SOCK_CLOEXEC_ENABLED build option */
    uint32_t c_char_signed;                  /* CHAR_MIN < 0 in the shim's compiler; djb2 signedness for LegacyWatchTable (02 §3.3) */
} zkrs_init_args;
```

`delivery` is `ZKRS_DELIVER_QUEUED` (async, through the per-handle FIFO) or `ZKRS_DELIVER_DIRECT` (sync and auth). zk-ffi maps `DIRECT` to `Route::Direct` when it builds the `zk_proto::Request`.

`ZKRS_F_MARSHAL_FAILED`, `ZKRS_F_BAD_ACL`, `ZKRS_OPF_BAD_ACL` and a defective value buffer (detected by `abi::raw::value_buf`) become `InputDefect` flags. zk-proto reports them at the legacy serialization stage (§12), so a shim allocation failure or an unusable buffer never reorders return codes.

`zkrs_init_args` has no layout cookies (CC-28 (00 §4)). Rust copies every string during `zkrs_handle_new`: `tls_passwd` goes straight into zeroizing storage (`abi::raw::cstr_secret`, which returns `Option<Zeroizing<Vec<u8>>>`), and zk-ffi moves that buffer into the connector's `Option<Zeroizing<Vec<u8>>>` (03 §8.2.3) without another copy, and the three paths are kept as owned bytes for the per-attempt file reads (TLS-02). `tls = 1` with all four strings NULL is valid: it is what `zookeeper_init_ssl(…, "")` produces, and every connection attempt then takes the TLS config-failure row (CC-14 (00 §4)).

`tls_env_allowed` reproduces OpenSSL's rule that `SSL_CERT_FILE` and `SSL_CERT_DIR` are ignored in privileged processes (`ossl_safe_getenv`). `zks_fill_init_args` computes it once per init: `getauxval(AT_SECURE) == 0` on Linux, `!issetugid()` on the BSDs and Apple, 1 on Windows. In a set-uid or set-gid process the field is therefore 0. Rust reads the two variables per attempt only when it is 1. When it is 0, the shared trust loader uses the compile-time `ZK_TLS_DEFAULT_CERT_FILE` / `ZK_TLS_DEFAULT_CERT_DIR` (06 §3.4), which configure and CMake set from `--with-rust-tls-default-paths=FILE:DIR` (default: the OPENSSLDIR that `openssl version -d` reports at configure time for the OpenSSL installation that the legacy probe selected, that is `$with_openssl/bin/openssl` for `--with-openssl=DIR`, `OPENSSL_ROOT_DIR/bin` in CMake, and otherwise the first `openssl` on PATH; without one, the platform list of 01 §6.1). Nothing is probed at run time. Without the rule, an attacker-set `SSL_CERT_FILE` would add trust anchors in a set-uid application. `zks_layout_check.c` asserts the field's offset, and the `zk-ffi-layout` tests check it against the Rust definition.

**`zkrs.h` constants.** The enums of the §5 and §6 listings (`zkrs_step`, `zkrs_item_type`, `zkrs_thread_op`, `zkrs_status`) and the SASL constants of §19.2 give their values in place. The other constants have these values. The Rust definitions in `abi/types.rs` carry them, so the cbindgen output in `shim/zkrs.h` must match this table, and `cbindgen --verify` fails on any drift.

| Constant | Value | Used in |
|---|---|---|
| `ZKRS_ROLE_FOREIGN`, `ZKRS_ROLE_INIT`, `ZKRS_ROLE_IO`, `ZKRS_ROLE_DISPATCH` | 0, 1, 2, 3 (FOREIGN is 0, so a thread whose role TLS was never set reads as foreign) | `zks_tls_role`, `zkrs_close`, `zkrs_thread_enter`/`_exit`, `zkrs_item.thread_role` |
| `ZKRS_FLAVOR_MT`, `ZKRS_FLAVOR_ST` | 1, 2 | `zkrs_init_args.flavor` |
| `ZKRS_DELIVER_QUEUED`, `ZKRS_DELIVER_DIRECT` | 0, 1 | `zkrs_submit` `delivery` |
| `ZKRS_ORIGIN_REPLY`, `ZKRS_ORIGIN_TEARDOWN` | 0, 1 | `zkrs_result_view.origin` (§10) |
| `ZKRS_K_VOID`, `ZKRS_K_STAT`, `ZKRS_K_DATA`, `ZKRS_K_STRINGLIST`, `ZKRS_K_STRINGLIST_STAT`, `ZKRS_K_ACLLIST`, `ZKRS_K_STRING`, `ZKRS_K_MULTI`, `ZKRS_K_STRING_STAT` | 0 to 8 in that order, equal to the legacy `COMPLETION_*` values (zookeeper.c:204-212) | `zkrs_result_view.kind`, `zks_req.kind`, `zks_sync.kind` |
| `ZKRS_OP_CREATE`, `ZKRS_OP_DELETE`, `ZKRS_OP_EXISTS`, `ZKRS_OP_GETDATA`, `ZKRS_OP_SETDATA`, `ZKRS_OP_GETACL`, `ZKRS_OP_SETACL`, `ZKRS_OP_GETCHILDREN`, `ZKRS_OP_GETCHILDREN2`, `ZKRS_OP_SYNC`, `ZKRS_OP_GETCONFIG`, `ZKRS_OP_RECONFIG`, `ZKRS_OP_MULTI`, `ZKRS_OP_REMOVE_WATCHES` | 1 to 14 in that order; 0 is not an operation | `zkrs_request.op` (`ZKRS_REQUEST_INIT(op)`) |
| `ZKRS_F_CREATE2`, `ZKRS_F_RAW_PATH`, `ZKRS_F_REMOVE_ALL`, `ZKRS_F_LOCAL`, `ZKRS_F_MARSHAL_FAILED`, `ZKRS_F_BAD_ACL` | bits `0x01`, `0x02`, `0x04`, `0x08`, `0x10`, `0x20` | `zkrs_request.flags` |
| `ZKRS_OPF_BAD_ACL` | bit `0x01` | `zkrs_op.flags` |
| `ZKRS_AUTH_F_ALLOC_FAILED` | bit `0x01` | `zkrs_add_auth` `flags` |
| `ZKRS_INIT_SASL_POINT` | -1 (never an errno) | `*rc` of the `ZKRS_MORE` that `zkrs_handle_new` returns at the SASL point of init (§15.1) |
| `ZKRS_AF_INET`, `ZKRS_AF_INET6` | 4, 6 (0: no address) | `zkrs_sockaddr_view.family` |
| `ZKRS_ARG_I64`, `ZKRS_ARG_U64`, `ZKRS_ARG_PTR`, `ZKRS_ARG_STR`, `ZKRS_ARG_ENDPOINT`, `ZKRS_ARG_ERRNO_STR`, `ZKRS_ARG_GAI_STR` | 0 to 6 in that order | `zkrs_log_arg.kind` (§16.2) |

`ZKRS_OUTBOX_INIT` (`{NULL}`), `ZKRS_REQUEST_INIT` (`#define ZKRS_REQUEST_INIT(o) {.op = (o)}`, every other field zero), `ZKRS_RESULT_DST_INIT` and `ZKRS_SYNC_DST_INIT` (all zero) are written in the `trailer` of `zk-ffi/cbindgen.toml`, so they appear in `zkrs.h` and `cbindgen --verify` checks them.

## 7. The host vtable (C functions Rust calls)

Every entry is **Pure** (non-blocking, no application code, may be called with Rust locks held) or **Blocking** (may block or do I/O, no application code, only callable with no Rust lock held). There is no third class. This is the complete list (CC-6 (00 §4)). Rust makes a Blocking call only after it has flushed every pending LOG item to C through the log-flush barrier of §5.

```c
typedef struct zkrs_host_vtable {
    /* ---- Pure ---- */
    void   (*discard_token)(uintptr_t token);   /* free zks_req uninvoked; sync record -> ZCLOSING + signal */
    void   (*snapshot_default_watcher)(uintptr_t host, zkrs_watcher *out);   /* pair cell, under its spinlock */
    void   (*store_client_id)(uintptr_t host, int64_t id, const uint8_t passwd[16]);
    int    (*log_enabled)(uint32_t level);      /* reads the exported logLevel with the macro rule */
    int    (*conn_permute_disabled)(void);
    long   (*rng_random)(void);
    double (*rng_drand48)(void);
    /* ---- Blocking ---- */
    void   (*rng_reseed)(void);                 /* srandom/srand48 from /dev/urandom (getpid fallback);
                                                   does nothing on Windows */
    int    (*resolve)(const char *name, const char *port, int32_t *gai_rc,
                      void (*push)(void *sink, const zkrs_sockaddr_view *),
                      void (*skip)(void *sink, int32_t family), void *sink); /* returns errno */
} zkrs_host_vtable;
```

- **TLS has no vtable entry.** rustls runs in zk-driver over in-memory buffers, with the socket owned by the driver; the CA, chain and key files are read by Rust on the IO owner, on every connection attempt and probe (TLS-02). Each TLS config failure is reported as ZSSLCONNECTIONERROR with `errno = EINVAL` and the legacy line for the failing step, through the Site table (CC-14 (00 §4), 06 §3).
- **SASL has no vtable entry.** Cyrus callbacks (password callback, `getpass`) are application code, so all Cyrus calls are made by C from a `ZKRS_ITEM_SASL` item (§19).
- **Threads are not in the vtable** (CC-3 (00 §4)). C creates them (§15.2) and C joins or detaches them when it drains a THREAD item (§15.3).
- **Polling is not in the vtable.** The IO step polls through the zk-driver `Poll` trait (03 §8.8). The default implementation is `RustixPoll` (`rustix::event::poll`, WSAPoll on Windows). Only if rustix lacks a needed WSAPoll case does zk-ffi implement `Poll` with one `WSAPoll` call in `abi::raw` (`raw::wsapoll`, §8; counted in the unsafe budget) and inject it at handle creation (03 §8.8). The trait is a Rust interface inside the process, so the K1 graph and this vtable list stay as they are.
- **`resolve`** (CC-5 (00 §4)) performs `getaddrinfo` with `AI_ADDRCONFIG`, `AF_UNSPEC`, `SOCK_STREAM`, `IPPROTO_TCP`, retried without flags on `EAI_BADFLAGS`/`EAI_ADDRFAMILY`, keeps only AF_INET/AF_INET6 in order (duplicates included) and calls `skip` with the raw `ai_family` of every other result, in order, and maps EAI codes to errno as `getaddrinfo_errno`. It logs nothing and returns errors and skipped families as data; zk-ffi passes the skipped families in `HostResolution.skipped_families`, and the core emits the WARN "skipping unknown address family %x for %s" for each (02 §9.4, zookeeper.c:1016-1017). Rust queues the platform's legacy site (zookeeper.c:982-988): `Win32 message: %s\n` on Windows, `getaddrinfo: %s\n` elsewhere. The `GAI_STR` argument renders `gai_strerror(gai_rc)` on Windows and on Linux with GCC-compatible compilers, and `strerror(errno)` on other platforms (02 §3.3). `push` and `skip` are Rust `extern "C"` functions wrapped in `ffi_guard`; `sink` is created with `ptr::from_mut(&mut v).cast()`, and the outer `&mut` is not used until `resolve` returns.
- **`rng_*`** reproduce the legacy process-global PRNG effects (K7, 07 SRV-04): the libc `srandom`/`srand48` reseed and the `random`/`drand48` draws. An unseeded application that happens to rely on the reseed would otherwise silently get a fixed libc sequence. On Windows `rng_reseed` does nothing, `rng_random` calls `rand()` and `rng_drand48` returns `(double)rand()/RAND_MAX`, as legacy.
- **Calls after release.** `FfiHost.host` is an `AtomicUsize`. zk-ffi swaps it to 0 just before returning `ZKRS_RELEASE`, after taking every C-resource-owning value (leftover tokens) out of the driver state explicitly and dropping it; final close does not rely on `Arc` drop order. Every entry that takes `host` goes through a helper that aborts with a diagnostic if it reads 0. Debug builds assert that the inventory of live tokens is empty at that point. The Cyrus session is C-owned and is disposed through the outbox before release (§19.3).

**Lock discipline in Rust.** `Unlocked` is a zero-sized proof defined in `zk-driver/src/sync.rs`. Its only constructor, `Unlocked::new_at_entry()`, is `pub` and `#[doc(hidden)]`, because zk-ffi's `with_handle` and thread-step entries call it, and zk-driver's own entry points are its only other callers; clippy `disallowed-methods` rejects any other call site. It is not `Clone`, `Copy`, `Send` or `Sync`. Every mutex in zk-ffi and zk-driver (the IO-owner lock `io`, the core lock and the leaf lock for the FIFO) is acquired through `lock(&self, u: &mut Unlocked)`, or as a leaf through a method on a live guard. Blocking vtable wrappers take `&Unlocked`, so a Blocking call while any guard lives does not compile. In release builds the core lock records its owner in an atomic and aborts with a message on same-thread re-entry, so a classification mistake fails loudly instead of deadlocking. Lock order: `zoo_lock_auth` (C, never taken by Rust) → `io` → core → FIFO (03 §3.4). The pair-cell spinlock, the sync-record mutex and the SASL password mutex are C leaves taken only inside Pure entries or C code, never around a Rust call and never around application code.

## 8. Raw boundary readers and writers (`zk-ffi/src/abi/raw.rs`, `zk-ffi/src/abi/raw/writers.rs`)

Every conversion between C memory and Rust goes through this module; nothing else in zk-ffi calls `slice::from_raw_parts`, `CStr::from_ptr` or `set_len`.

```rust
/// len == 0 -> &[] whatever p is. p NULL with len > 0 -> Err(NullData); the caller applies the legacy rule.
pub(crate) fn bytes<'a>(p: *const u8, len: usize) -> Result<&'a [u8], RawError>;
/// Legacy (ptr, i32) value buffer: -1 -> None (pointer ignored); 0 -> Some(empty); > 0 -> copied;
/// < -1, or NULL with > 0 -> Err(BadValue). Lengths are checked against isize::MAX before any slice exists.
pub(crate) fn value_buf(p: *const c_char, len: i32) -> Result<Option<Vec<u8>>, RawError>;
/// NUL-terminated C string -> owned bytes. NULL -> None.
pub(crate) fn cstr_opt(p: *const c_char) -> Option<Vec<u8>>;
/// NUL-terminated C string copied straight into zeroizing storage (TLS passwd). NULL -> None.
pub(crate) fn cstr_secret(p: *const c_char) -> Option<Zeroizing<Vec<u8>>>;
/// zkrs_bytes view from C (Cyrus output): is_null -> None; len > 0x7fffffff, or p NULL with len > 0 -> Err.
pub(crate) fn view_bytes(v: *const zkrs_bytes) -> Result<Option<SecretBytes>, RawError>;
/// Counted array of repr(C) zkrs_* records. count <= 0 -> &[]; NULL with count > 0 -> Err.
pub(crate) fn array<'a, T>(p: *const T, count: i32) -> Result<&'a [T], RawError>;
/// Builds a NUL-terminated owned copy for any string handed to C (log STR args, zkrs_str, paths).
pub(crate) fn c_bytes(b: &[u8]) -> Box<[u8]>;
/// libc allocation for objects that C and the application free with free(). NULL for a non-zero
/// size aborts (GLB-04); for a zero size the libc result is kept as it is, as legacy keeps it.
pub(crate) fn c_malloc(n: usize) -> *mut u8;                   // malloc(n), also malloc(0)
pub(crate) fn c_calloc(count: i32, size: usize) -> *mut u8;    // calloc(count, size), also count 0;
                                                               // count -1 -> NULL with no call, which
                                                               // is what legacy's calloc(-1, size) returns
/// Handle pointer from C -> owned strong reference. Increments the strong count of the Arc that
/// zkrs_handle_new gave out with Arc::into_raw and rebuilds the Arc (rule S1: the C caller holds a
/// strong reference at entry). The signature is safe because every zkrs_* entry is the only caller
/// and S1 is the contract of the private ABI; the two pointer operations are unsafe blocks here.
pub(crate) fn handle_arc(p: *const zkrs_handle) -> Arc<Inner>;
/// Gives back one strong reference: Arc::decrement_strong_count on a pointer that zkrs_handle_new
/// gave out. zkrs_handle_release, zkrs_handle_abandon and the drop of an outbox's Arc<Inner> use it.
pub(crate) fn handle_release(p: *const zkrs_handle);
/// zkrs_outbox.impl <-> the Rust outbox (§5). outbox_into_impl stores the boxed outbox in impl as a
/// pointer (Box::into_raw, cast to *mut c_void); outbox_from_impl casts impl back to the outbox for one
/// call on that outbox (NULL -> None). No integer-to-pointer cast is made.
/// Only zkrs_outbox_next/finish/requeue and the entries that fill an outbox use them.
pub(crate) fn outbox_into_impl(ob: *mut zkrs_outbox, b: Box<Outbox>);
pub(crate) fn outbox_from_impl<'a>(ob: *mut zkrs_outbox) -> Option<&'a mut Outbox>;
/// Out-parameter writes through C pointers: scalars (*rc, *written, the *out of zkrs_handle_new) and
/// structs (*it in zkrs_outbox_next, the zkrs_interest_out of zkrs_interest and zkrs_interest_resume,
/// the zkrs_sockaddr_view of zkrs_current_server and zkrs_peer_addr). A raw pointer write; never a
/// &mut to C memory.
pub(crate) fn write_out<T: Copy>(dst: *mut T, v: T);
/// Reads a repr(C) struct that C passes by pointer (zkrs_init_args, zkrs_host_vtable (copied once at
/// zkrs_handle_new), zkrs_request, zkrs_sasl_out) into a copy; the pointers inside it go through the
/// readers above.
pub(crate) fn read_struct<T: Copy>(p: *const T) -> T;
/// Calls through the host vtable (§7). One wrapper per entry, each a call through the entry's
/// unsafe extern "C" fn pointer: Pure {discard_token, snapshot_default_watcher, store_client_id,
/// log_enabled, conn_permute_disabled, rng_random, rng_drand48}; Blocking {rng_reseed, resolve},
/// whose wrappers take &Unlocked (§7 lock discipline).
pub(crate) fn vt_discard_token(vt: &zkrs_host_vtable, token: usize);
pub(crate) struct HostAddrs { pub addrs: Vec<SocketAddr>, pub skipped_families: Vec<i32> }
pub(crate) fn vt_resolve(vt: &zkrs_host_vtable, u: &Unlocked, name: &CStr, port: &CStr,
                         out: &mut HostAddrs) -> (i32, i32);   // (errno, gai_rc)
// … vt_snapshot_default_watcher, vt_store_client_id, vt_log_enabled, vt_conn_permute_disabled,
//   vt_rng_random, vt_rng_drand48, vt_rng_reseed: the same shape.
/// The resolve push and skip callbacks: sink back to the HostAddrs that vt_resolve passed (§7), for
/// one push or skip.
pub(crate) fn sink_from_ptr<'a>(sink: *mut c_void) -> &'a mut HostAddrs;
/// Windows only: one cert-string path field -> the open candidates, in order: the UTF-8 candidate
/// (when the bytes are valid UTF-8) and the ANSI code-page candidate from
/// MultiByteToWideChar(CP_ACP, …). Called at zkrs_handle_new; the connector opens them in that
/// order (06 §3.4).
#[cfg(windows)]
pub(crate) fn tls_path_candidates(p: *const c_char) -> Option<Vec<OsString>>;
/// Windows only, and only when rustix lacks a needed WSAPoll case (§7, 03 §8.8): the one WSAPoll
/// call behind the zk-driver Poll implementation that zk-ffi injects at handle creation.
#[cfg(windows)]
pub(crate) fn wsapoll(fds: &mut [WSAPOLLFD], timeout_ms: i32) -> io::Result<usize>;
/// The writers of §10.1: legacy result layouts and the sync copy-out. They take the raw pointers
/// that the zkrs_write_result, zkrs_write_path, zkrs_write_multi and zkrs_sync_copy_out exports receive.
pub(crate) fn write_result(v: *const zkrs_result_view, d: *const zkrs_result_dst);
pub(crate) fn write_path(s: *const zkrs_str) -> *mut c_char;   // malloc(len + 1), never NULL
pub(crate) fn write_multi(v: *const zkrs_result_view, results: *mut COpResult, kinds: *const u8, count: i32);
pub(crate) fn sync_copy_out(s: *const zkrs_sync_src, d: *const zkrs_sync_dst);
```

The §10.1 writers (write_result, write_path, write_multi, sync_copy_out, c_malloc, c_calloc) live in the child module `abi::raw::writers` (`src/abi/raw/writers.rs`). It is part of the audited raw module and depends only on `abi/types.rs`, `abi/legacy.rs` and `libc`, so the F10 fuzz target can include those three files by `#[path]` (08 §8.5). The listing above is the interface of `abi::raw` as a whole: the functions named in this paragraph are defined in `writers.rs`, and the others in `raw.rs`.

The signatures are safe for the reason given for `handle_arc`; every pointer dereference is an `unsafe` block inside `abi::raw`.

- Inputs are copied into owned `Vec`s (or `Zeroizing<Vec<u8>>`/`SecretBytes` for secrets) with `ptr::copy_nonoverlapping`; no long-lived `&[u8]` view of application or Cyrus memory exists.
- A `Err` from `value_buf` becomes the bad-value-buffer `InputDefect`; zk-proto reports it at the serialization stage (CC-12 (00 §4)).
- A `Err` from `view_bytes` is treated as a Cyrus error (SASL failure with ZSYSTEMERROR); it cannot occur with a conforming Cyrus.
- Legacy rules applied here: `zoo_add_auth` with `cert == NULL` and `certLen > 0` is an empty credential; Cyrus output `(NULL, 0)` is "no token"; ACL vectors with `count == 0, data == NULL` (as `allocate_ACL_vector(v, 0)` produces) are empty; `zoo_amulti(count 0, ops NULL)` is valid.
- Outputs are written with raw pointer writes (`ptr::write`, `ptr::copy_nonoverlapping`, `&raw mut` field places) into the destinations that C passes for the call. No `&mut` reference to application memory is created, because the application may alias it.
- Miri tests (Tree Borrows and Stacked Borrows) and ASan tests cover every NULL/0 combination above and every writer of §10.1.

## 9. Buffer ownership

| Buffer | Direction | Owner / allocator | Lifetime |
|---|---|---|---|
| path, data, scheme, cert bytes, hosts string, reconfig strings | C→Rust args | application | borrowed for the call; Rust copies |
| TLS cert string copy (`certstr` with the four fields) | C→Rust args | shim `strdup` | borrowed by `zkrs_handle_new`; zeroed and freed by `zookeeper_init_ssl` when init returns |
| `zkrs_op[]`, `zkrs_acl[]` marshalled from `zoo_op_t[]`/`ACL_vector` | C→Rust args | shim stack (≤ 16 entries) or one `malloc` per call | freed when the call returns; the shim never keeps `ops` |
| multi `results[]`, `create_op.buf`/`buflen` (copied into `results[i]` at call time), `set_op.stat` | kept in `zks_req` | application | until the multi completion (async) or `zoo_multi` returns (sync); the shim passes `results[]` to the `abi::raw` multi writer only during that completion (§10.1, §12.3) |
| `zks_req` | C malloc → token → C | shim | submit → one delivery or discard |
| `zks_sync` (sync record) | heap, refcount 2 | shim `calloc`; the reply objects in it from libc `malloc`/`calloc` called by `abi::raw` | waiter and completer each drop one reference; the last one frees it and the objects left in it (§13) |
| outbox storage (log views, result/watch views, SASL addresses and server token) | Rust→C | Rust | until the next `zkrs_outbox_next`/`finish`/`requeue` on that outbox |
| data buffer, `String_vector`, `ACL_vector`, strings, `struct Stat` handed to callbacks | C→application | libc `malloc`/`calloc` called by `abi::raw` (§10.1); `Stat` on the C stack, written by `abi::raw` | async: freed by C after the callback by re-reading the struct; sync children/ACL: ownership passes to the caller |
| watcher path string | C→application | libc `malloc` called by `abi::raw` (`zkrs_write_path`) | freed by C after all watchers of the event return |
| log buffer | C→application | shim `ZKS_THREAD_LOCAL char[4096]` | valid only during the callback |
| endpoint string | C→application | shim `ZKS_THREAD_LOCAL char[134]` | until the next endpoint formatting on that thread (§16.2) |
| Cyrus output (`clientout`, chosen mechanism) | C (Cyrus)→Rust | Cyrus | copied by `zkrs_sasl_result` before the next Cyrus call |
| SASL session (`zks_sasl_sess`: copies of service, host, mechlist; `sasl_conn_t*`) | shim | shim `calloc` (session) and capped `malloc`+`memcpy` (strings) | handle lifetime: `zks_sasl_sess_new`, then `zks_sasl_sess_free`; the Cyrus connection is disposed right before the next `START` and at final close, as legacy (§19.3) |
| per-session callback array and secret copies | shim→Cyrus | shim `malloc` (array), `calloc` (secret copies) | one Cyrus connection; zeroed (secrets) and freed at dispose |
| application `sasl_callback_t[]` | application→shim | application | borrowed for the handle's lifetime, as legacy (zookeeper.h:629-631) |
| SASL callback block from `zoo_sasl_make_*` | C→application | shim, one `calloc` | freed by the application with `free()` |

## 10. Result delivery: the nine completion kinds

The core computes `rc` and `failed` per legacy rules and zk-ffi builds a view in outbox storage. `kind` uses the legacy `COMPLETION_*` values.

```c
typedef struct { const char *ptr; int32_t len; } zkrs_str;  /* ptr[len] == 0 (zk-ffi appends it); NULL iff absent */
typedef struct { const char *ptr; int32_t len; } zkrs_buf;  /* len -1: null znode data */
typedef struct { int64_t czxid, mzxid, ctime, mtime; int32_t version, cversion, aversion;
                 int64_t ephemeralOwner; int32_t dataLength, numChildren; int64_t pzxid; } zkrs_stat;
typedef struct { int32_t perms; zkrs_str scheme, id; } zkrs_acl_view;
typedef struct { int32_t err, failed; zkrs_str path; const zkrs_stat *stat; } zkrs_multi_op;
typedef struct zkrs_result_view {
    uint32_t kind;      /* 0 VOID 1 STAT 2 DATA 3 STRINGLIST 4 STRINGLIST_STAT 5 ACLLIST 6 STRING 7 MULTI 8 STRING_STAT */
    uint32_t origin;    /* ZKRS_ORIGIN_REPLY | ZKRS_ORIGIN_TEARDOWN */
    int32_t  rc, failed;
    zkrs_buf data; const zkrs_stat *stat; zkrs_str path;  /* path already chroot-stripped */
    const zkrs_str *children; int32_t nchildren;           /* -1: jute null vector */
    const zkrs_acl_view *acl; int32_t nacl;
    const zkrs_multi_op *ops; int32_t nops;
    const zkrs_log_view *delivery_logs; uint32_t n_delivery_logs;  /* emitted before the callback (02 §5.1) */
} zkrs_result_view;
```

The delivery DEBUG lines are core sites. zk-proto puts them first in `delivery_logs`, with legacy's values, so the view needs no xid and C formats nothing itself. An async completion that legacy runs through `deserialize_response` carries "Calling COMPLETION_<KIND> for xid=%#x failed=%d rc=%d" (zookeeper.c:3343-3441; the STRING and STRING_STAT texts are "failed=%d, rc=%d"). An async MULTI carries "Calling COMPLETION_MULTI" and then, per sub-op in order, the line of the sub-op's kind followed by that sub-op's strip ERROR (zookeeper.c:3298-3336, 3440-3449). A sync reply carries "Processing sync_completion with type=%d xid=%#x rc=%d" (zookeeper.c:5425-5426), for MULTI followed by the same per-sub-op lines (5530). Auth completions, the local remove-watches completion and the teardown of a sync waiter carry no line, as in legacy (zookeeper.c:2032-2038, 5021-5029). The `failed` and `rc` printed in a top-level "Calling COMPLETION_*" line are legacy's `hdr.err != 0` and `hdr.err`, from the reply header or from the header that teardown fakes (zookeeper.c:3482, 2046-2048).

zk-ffi maps `Origin::Reply` to `ZKRS_ORIGIN_REPLY` and `Origin::Teardown` (02 §5.1) to `ZKRS_ORIGIN_TEARDOWN`, so the RO→RW multi gets the `cleanup_failed_multi` semantics of §12.3.

**Invariant.** `failed == 0` implies a decoded body, and `rc` is never ZOK with `failed == 1`. Every RO→RW result is ZCONNECTIONLOSS with `failed == 1` (DV-FIX-9, 07 ST-08). For async, multi and sync deliveries alike, the caller's outputs are not written from a body when `failed == 1` (§13).

**Results are materialized from views, not by running generated `deserialize_*` over raw bytes** (K11). The strings must already be chroot-stripped, multi items must be parsed by wire type, `failed` is computed by the core, and `deserialize_*` does not bound lengths against the frame. The `abi::raw` writers (§10.1) reproduce the legacy allocation layout exactly: `calloc(count, sizeof(char*))` even for count 0, one `malloc(len+1)` per string, `count == -1` → `data == NULL, count == -1` (JRecord.java:203-214 generates the vector deserializers; recordio.c `ia_deserialize_buffer` and `ia_deserialize_string` the buffer and string allocations). zk-jute vectors are null-preserving (`JVec`, K18).

C keeps delivery. `zks_deliver_result` asks the writers to fill its temporaries, calls the completion, and frees the temporaries afterwards in the legacy order, from the structs as the callback left them:

```c
void zks_deliver_result(zhandle_t *zh, uintptr_t token, const zkrs_result_view *v)
{
    struct zks_req *r = (struct zks_req *)token;
    struct zks_tmp tmp __attribute__((cleanup(zks_tmp_free))) = ZKS_TMP_INIT;  /* also runs on unwind */
    struct Stat st;
    zkrs_result_dst d = ZKRS_RESULT_DST_INIT;                                /* all NULL */
    uint32_t i;
    tmp.req = r;
    /* delivery_logs are the core's delivery lines, in legacy order, on the delivering thread, before
     * the callback, zks_multi_complete or zks_sync_complete (02 §5.1): for an async result
     * "Calling COMPLETION_<KIND> for xid=%#x failed=%d rc=%d" (zookeeper.c:3343-3441; MULTI adds the
     * per-sub-op lines), for a sync reply "Processing sync_completion with type=%d xid=%#x rc=%d"
     * (zookeeper.c:5425), then any strip-mismatch ERROR. Auth results carry none. */
    for (i = 0; i < v->n_delivery_logs; i++) zks_log_emit(zh, &v->delivery_logs[i]);
#ifdef THREADED
    if (r->mode == ZKS_REQ_SYNC) { zks_sync_complete(r, v); return; }     /* §13; no user code; mt only */
#endif
    if (r->kind == ZKRS_K_MULTI) { zks_multi_complete(r, v); return; }    /* §12.3 */
    if (r->fn.v == NULL) return;                                           /* NUL-05 */
    if (!v->failed) {                                    /* abi::raw builds the legacy objects (§10.1) */
        d.stat = &st; d.value = &tmp.val; d.value_len = &tmp.val_len;
        d.strings = &tmp.sv; d.acl = &tmp.av; d.str = &tmp.str;            /* the writer fills only what
                                                                              the kind has */
        ZKS_CALL(zkrs_write_result(v, &d));
    }
    switch (r->kind) {
    case ZKRS_K_VOID: r->fn.v(v->rc, r->data); break;
    case ZKRS_K_STAT: r->fn.s(v->rc, v->failed ? NULL : &st, r->data); break;
    case ZKRS_K_DATA:
        if (v->failed) { r->fn.d(v->rc, NULL, 0, NULL, r->data); break; }
        r->fn.d(v->rc, tmp.val, tmp.val_len, &st, r->data);                /* (NULL, -1) for a null znode */
        break;                                   /* cleanup: free(value) */
    case ZKRS_K_STRINGLIST: case ZKRS_K_STRINGLIST_STAT:
        if (v->failed) { ZKS_CALL_STRINGS(r, v->rc, NULL, NULL); break; }
        ZKS_CALL_STRINGS(r, v->rc, &tmp.sv, &st);
        break;                                   /* cleanup: deallocate_String_vector on the struct as left */
    case ZKRS_K_ACLLIST:
        if (v->failed) { r->fn.acl(v->rc, NULL, NULL, r->data); break; }
        r->fn.acl(v->rc, &tmp.av, &st, r->data); /* non-const, H:1216 */
        break;                                   /* cleanup: deallocate_ACL_vector on the struct as left */
    case ZKRS_K_STRING: case ZKRS_K_STRING_STAT:
        if (v->failed) { ZKS_CALL_STRING(r, v->rc, NULL, NULL); break; }
        ZKS_CALL_STRING(r, v->rc, tmp.str, &st);
        break;                                   /* cleanup: free(str) */
    }
}
```

`zks_tmp_free` runs `free(tmp.val)`, `deallocate_String_vector(&tmp.sv)`, `deallocate_ACL_vector(&tmp.av)`, `free(tmp.str)` and `zks_req_free(tmp.req)`, so the request is freed on return and on unwind. Members the writer did not fill stay zero, so these calls do nothing for them.

| Kind | On `failed` | On success | Freed after the call |
|---|---|---|---|
| VOID | `(rc, data)` | `(rc, data)` | — |
| STAT | `(rc, NULL, data)`, incl. `exists` ZNONODE | `(rc, &stackStat, data)` | stack |
| DATA | `(rc, NULL, 0, NULL, data)` | `(rc, copy, len, &stat, data)`; null znode `(NULL, -1)`; empty `(malloc(0), 0)`; not NUL-terminated | `free(value)` |
| STRINGLIST / _STAT | `(rc, NULL[, NULL], data)` | `(rc, &sv[, &stat], data)` | `deallocate_String_vector` on the struct as left |
| ACLLIST | `(rc, NULL, NULL, data)` | `(rc, &av, &stat, data)` (mutable) | `deallocate_ACL_vector` on the struct as left |
| STRING / _STAT | `(rc, NULL[, NULL], data)` | `(rc, stripped path[, &stat], data)` | `free` |
| MULTI | §12.3 | §12.3 | — |

`abi::raw` writes `struct Stat` field by field through its `repr(C)` mirror, so `zkrs_stat` and `struct Stat` need not agree in layout (i386 `Stat` is 68 bytes, LP64 `Stat` is 72). Allocation failure during materialization calls `abort()`. In legacy, the generated vector `calloc` is unchecked (JRecord.java:208), and the recordio string and buffer allocations return -ENOMEM, which the completion paths ignore (recordio.c:254-257, 275-278; zookeeper.c:3348-3352). Both end in a NULL dereference or a callback with undefined contents (GLB-04, 07 P7).

### 10.1 The `abi::raw` result writers

The writers are stateless `zkrs_*` entries (§6). They take no handle and no outbox, are `ffi_guard`ed and called through `ZKS_CALL`, and run on the thread that delivers or copies out. They read only the view they are given (outbox storage, valid until the next call on that outbox, §4) or the record image C passes, and they write only the destinations C passes. The public records are written by `abi::raw::writers` (`src/abi/raw/writers.rs`) through private mirrors in `abi/legacy.rs`:

```rust
// zk-ffi/src/abi/legacy.rs: repr(C) mirrors of zookeeper.jute.h and zookeeper.h records, written only by
// abi::raw. zk-ffi-layout includes this file by #[path] and checks size, alignment and every field offset
// against the C headers on each target.
#[repr(C)] pub(crate) struct CStat { czxid: i64, mzxid: i64, ctime: i64, mtime: i64, version: i32,
    cversion: i32, aversion: i32, ephemeral_owner: i64, data_length: i32, num_children: i32, pzxid: i64 }
#[repr(C)] pub(crate) struct CStringVector { count: i32, data: *mut *mut c_char }
#[repr(C)] pub(crate) struct CId { scheme: *mut c_char, id: *mut c_char }
#[repr(C)] pub(crate) struct CAcl { perms: i32, id: CId }
#[repr(C)] pub(crate) struct CAclVector { count: i32, data: *mut CAcl }
#[repr(C)] pub(crate) struct COpResult { err: c_int, value: *mut c_char, valuelen: c_int, stat: *mut CStat }
```

```c
/* zkrs.h: writer destinations. Public types are passed as void *; NULL means "not wanted". */
typedef struct zkrs_result_dst {
    void    *stat;          /* struct Stat *: written when the view has a stat */
    char   **value;         /* DATA: malloc(len) copy (malloc(0) for empty); NULL for a null znode */
    int32_t *value_len;     /* DATA: len, or -1 for a null znode */
    void    *strings;       /* struct String_vector *: calloc(count, sizeof(char *)) + malloc(len+1) each */
    void    *acl;           /* struct ACL_vector *: calloc(count, sizeof(struct ACL)) + scheme/id strings */
    char   **str;           /* STRING: malloc(len + 1) copy of the stripped path */
    int32_t *str_len;       /* STRING: len (the sync copy-out needs it) */
    zkrs_multi_copy **ops; int32_t *nops;   /* MULTI into a sync record: calloc'd per-op copies */
} zkrs_result_dst;
typedef struct zkrs_multi_copy { int32_t err, failed; char *path; int32_t path_len;
                                 uint32_t has_stat; zkrs_stat stat; } zkrs_multi_copy;
```

- **`zkrs_write_result`** builds the objects that the view's kind has, into the destinations that are non-NULL: the `Stat`; the DATA buffer with `malloc(len)` (also for `len == 0`) or NULL for a null znode, as `ia_deserialize_buffer` does (recordio.c); the STRING copy with `malloc(len + 1)`, as `ia_deserialize_string` and the chroot `strdup` of `sub_string` do (zookeeper.c:1727-1744); the vectors with `calloc(count, sizeof element)` and one `malloc(len + 1)` per string, `count == -1` giving `data == NULL, count == -1`. The `Id` strings of each `ACL` are `malloc(len + 1)` copies. A NULL `zkrs_str` (jute null string) gives a NULL pointer. For MULTI into a sync record it writes one `zkrs_multi_copy` per op, with a `malloc(len + 1)` path copy, so the waiter can run the §12.3 writers after the outbox is gone. A MULTI view with `failed = 1` and `nops = 0` (reply None, proto P16) writes no copies: `*ops` is NULL and `*nops` is 0, and `zkrs_sync_copy_out` applies the §12.3 rule `{err = rc, failed = 1}` on the waiting thread.
- **`zkrs_write_path`** returns the watcher path as `malloc(len + 1)` (`""` for session events, never NULL), for `zks_deliver_watch` (§11).
- **`zkrs_write_multi`** runs the legacy multi completion writers (`op_result_string_completion`, `op_result_stat_completion`) over the caller's `results[]`, per the per-op kinds copied at submit (§12.3). A view with `failed = 1` and `nops = 0` (reply None, proto P16) applies `{err = v.rc, failed = 1}` to each of the `count` results, using `op_kind`.
- **`zkrs_sync_copy_out`** runs the legacy rc==0 copy-out from the sync record into the caller's buffers on the waiting thread, with the `buffer_len` truncation and the `path_buffer` NUL rules (§13).

Every allocation goes through `c_malloc`/`c_calloc` (§8), which call libc. The shim and zk-ffi are one library with one CRT, so the application's `free()`, `deallocate_*` and the generated jute deallocators free these objects unchanged. C frees them after the callback, in the legacy order. The verification of §22 covers the writers: Miri over the `abi::raw` unit tests, the `zk-ffi-layout` checks of every mirror, ASan runs of the C delivery paths, and a fuzz target that drives zk-proto-decoded replies through the writers and the `deallocate_*` paths under ASan (08 §8.5).

## 11. Watcher delivery

The core resolves the watcher list when the event is received (one-shot removal for node events, a snapshot for session events) and fixes the legacy order (exist before data for CREATED/CHANGED; child, exist, data for DELETED; session events with the default watcher last, deduplicated by first-collected `(fn, ctx)`). Session fan-out order is the bit-identical `LegacyWatchTable` order (K6, WAT-05). The default pair is snapshotted through `snapshot_default_watcher` when the event is queued.

```c
typedef struct { uintptr_t fn, ctx; } zkrs_watcher;
typedef struct { int32_t type, state; zkrs_str path; const zkrs_watcher *list; uint32_t n;
                 const zkrs_log_view *delivery_logs; uint32_t n_delivery_logs; } zkrs_watch_event_view;

void zks_deliver_watch(zhandle_t *zh, const zkrs_watch_event_view *ev)
{
    char *path __attribute__((cleanup(zks_free_charp))) = NULL;   /* freed on return and on unwind */
    uint32_t i;
    for (i = 0; i < ev->n_delivery_logs; i++)                    /* "Calling a watcher for node", strip ERROR (02 §5.1) */
        zks_log_emit(zh, &ev->delivery_logs[i]);
    if (ev->n == 0) return;  /* empty list (WAT-07) or unknown event type: the lines only, no callback */
    path = ZKS_CALL(zkrs_write_path(&ev->path));                 /* abi::raw malloc(len + 1); session: "" */
    for (i = 0; i < ev->n; i++)
        ((watcher_fn)ev->list[i].fn)(zh, ev->type, ev->state, path, (void *)ev->list[i].ctx);
}
```

- Node events carry the server's state and the chroot-stripped path (`zk_proto::legacy::strip`, stripped where decoded, CC-21 (00 §4)). Session events carry the client state (3, 5, 1, -112, -113) and `""`.
- `zoo_set_watcher(zh, fn)` stores `fn ? fn : zks_null_watcher` in the pair cell and returns the previous value (never NULL). `zookeeper_init*` stores the same way. The `watch=1` wrappers read the pair inside the cell lock at call time and pass it explicitly, so a `watch=1` registration always sets the server watch flag.
- Thread: mt dispatch thread; st inline in the driving call. Watch events left queued at an mt close from the IO thread are dropped (nothing to free in C, because the path copy happens only at delivery).

The chroot and path rules are the 02 §11 pure functions (`legacy::prepend`, `is_valid_legacy`, `legacy::strip`) under `ChrootPolicy::LegacyConcatNoEscape`, which zk-proto applies (02 §11, §14.3). A 1-byte path other than `"/"` and a sequential create on `"/"` under a chroot return ZBADARGUMENTS before anything is sent (DV-FIX-7; 07 PTH-01, PTH-04). Inside `zoo_amulti` such a sub-op fails the call as any invalid sub-path does, with ZMARSHALLINGERROR. C's other quirks are preserved: `"foo"` under `/app` becomes `/appfoo`; `"/"` becomes the chroot; stripping is a plain prefix match (`/app2/x` → `2/x`). Async and sync getconfig both use `ZKRS_F_RAW_PATH`, so neither is chroot-prefixed (DV-FIX-5, §13). A NULL path with a chroot returns ZBADARGUMENTS (PTH-01). Exhaustive differential tests compare the ports with the legacy C functions.

## 12. Async entry points and the return-code funnel

| Stage | Where | Condition | Result |
|---|---|---|---|
| 0 | C | sync only: `buffer_len == NULL` (`zoo_wget`, `zoo_reconfig`), before `zh` | ZBADARGUMENTS |
| 1 | zk-proto `submit` | create*: TTL mode and (`ttl <= 0` or `> 0xFFFFFFFFFF`); non-TTL mode and `ttl >= 0` | EARLY_BADARGS |
| 2 | C | `zh == NULL` (if so, C first calls `zkrs_validate_ttl` so stage 1 still wins) | ZBADARGUMENTS |
| 3 | zk-proto `submit` | path NULL, or `prepend` + `isValidPath(prefixed, mode)` fails | EARLY_BADARGS |
| 4 | zk-proto `submit` | `state < 0` | EARLY_STATE |
| 5 | zk-proto `submit` (serialization stage) | encode failure (`try_reserve`); `InputDefect`: unusable value buffer (`len < -1`, or NULL with `len > 0`), `BAD_ACL` (NULL ACL vector in `zoo_aset_acl`, or `count > 0` with `data == NULL`), `MARSHAL_FAILED` or `token == 0` (shim OOM); multi sub-op path/state/value/ACL failure; close requested | LATE |
| 6 | zk-proto | queued | OK |

Stage 5 applies to single ops and multi alike (CC-12 (00 §4)): it is what legacy's own funnel returns for a serialization failure, and legacy reaches serialization only after the state check. `zoo_aset_acl` dereferences the vector after `Request_path_init` (zookeeper.c:4767-4772).

```c
static int zks_funnel(zkrs_status s)
{
    switch (s) {
    case ZKRS_OK: case ZKRS_OK_LOCAL:  return ZOK;
    case ZKRS_EARLY_BADARGS:           return ZBADARGUMENTS;
    case ZKRS_EARLY_STATE:             return ZINVALIDSTATE;
    case ZKRS_UNIMPLEMENTED:           return ZUNIMPLEMENTED;
    case ZKRS_NOWATCHER:               return ZNOWATCHER;
    case ZKRS_SYSTEM:                  return ZSYSTEMERROR;
    default:                           return ZMARSHALLINGERROR;   /* LATE */
    }
}

/* One template; every zoo_a* body fills a zkrs_request and calls zks_submit. zks_submit_raw is
   the same call returning the raw zkrs_status, for zks_aremove_watches, which must tell
   ZKRS_OK_LOCAL apart from ZKRS_OK (§12.2). Both are static in zks_async.c. */
static zkrs_status zks_submit_raw(zhandle_t *zh, zkrs_request *rq, unsigned kind, zks_fn fn, const void *data)
{
    const zkrs_handle *core = zh->core;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT;
    int sync = (fn.v == ZKS_SYNC_MARKER);
    struct zks_req *r = zks_req_new(kind, sync ? ZKS_REQ_SYNC : ZKS_REQ_ASYNC, fn, data);  /* may be NULL */
    zkrs_status s;
    if (r != NULL && sync) r->sync = (struct zks_sync *)data;
    s = ZKS_CALL(zkrs_submit(core, rq, (uintptr_t)r, sync ? ZKRS_DELIVER_DIRECT : ZKRS_DELIVER_QUEUED, &ob));
    zks_drain(zh, &ob);                           /* app-thread log lines only: a forced close inside
                                                     the call yields no SASL item (§19.3); never callbacks */
    if (s != ZKRS_OK) zks_req_free(r);           /* ZKRS_OK_LOCAL included: token not retained */
    return s;
}

static int zks_submit(zhandle_t *zh, zkrs_request *rq, unsigned kind, zks_fn fn, const void *data)
{
    return zks_funnel(zks_submit_raw(zh, rq, kind, fn, data));
}

int zoo_awget(zhandle_t *zh, const char *path, watcher_fn w, void *wctx, data_completion_t dc, const void *data)
{
    zkrs_request rq = ZKRS_REQUEST_INIT(ZKRS_OP_GETDATA);
    if (zh == NULL) return ZBADARGUMENTS;
    ZKS_REGISTRY_CHECK(zh, __func__);   /* debug builds only, §15.5 */
    rq.path = path; rq.watch_fn = (uintptr_t)w; rq.watch_ctx = (uintptr_t)wctx;
    return zks_submit(zh, &rq, ZKRS_K_DATA, ZKS_FN(dc), data);
}

int zoo_aget(zhandle_t *zh, const char *path, int watch, data_completion_t dc, const void *data)
{
    struct zks_pair p;
    if (zh == NULL) return ZBADARGUMENTS;                /* NUL-01 */
    ZKS_REGISTRY_CHECK(zh, __func__);   /* debug builds only, §15.5 */
    p = zks_watch_cell_read(&zh->dw);
    return zoo_awget(zh, path, watch ? p.fn : NULL, p.ctx, dc, data);   /* exported, through the PLT */
}
```

ACL marshalling (`zoo_acreate*`, `zoo_aset_acl`): a NULL vector gives `acl = NULL, nacl = 0`. For create, legacy sends count 0 (zookeeper.c:4347-4349); for set_acl the shim sets `BAD_ACL`. Otherwise `nacl = count` (raw, so a negative count goes to the wire as in legacy), `count > 0 && data == NULL` sets `BAD_ACL`, and each entry becomes `{perms, scheme, id}`. If the marshal array `malloc` fails, the shim sets `MARSHAL_FAILED`.

Per-function notes:

- `zoo_acreate[2]_ttl`: `mode`, `ttl`, `ZKRS_F_CREATE2`; op 19 for container mode, 21 for TTL modes (`zk_proto::legacy::create_opcode`). `zoo_acreate` calls `zoo_acreate_ttl(..., -1, ...)` and `zoo_acreate2` calls `zoo_acreate2_ttl(..., -1, ...)` by exported name.
- `zoo_areconfig`: stages 2, 4, 5. NULL strings become jute null.
- `zoo_agetconfig` → `zoo_awgetconfig` (exported); `zoo_awgetconfig` uses `/zookeeper/config` with `ZKRS_F_RAW_PATH`.
- `zoo_aexists` → `zoo_awexists` (exported).
- `zoo_aget_children[2]` and `zoo_awget_children[2]` both call the **static** `zks_awget_children[2]_` helper, as legacy (zookeeper.c:4606-4698).

### 12.1 `zoo_add_auth`

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
    ZKS_REGISTRY_CHECK(zh, __func__);   /* debug builds only, §15.5 */
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
    zks_drain(zh, &ob);
    if (s != ZKRS_OK) zks_req_free(r);
    return zks_funnel(s);
}
```

Order inside zk-proto (zookeeper.c:5301-5350):

1. `state < 0` → EARLY_STATE.
2. `state == 0` → EARLY_STATE (ZOOKEEPER-800).
3. `cert != NULL && certLen != 0`: a negative `certLen` → SYSTEM (legacy `calloc(1, certLen)` fails); otherwise the bytes are copied into `SecretBytes` after `try_reserve_exact`, and an allocation failure → SYSTEM, as legacy's checked `calloc` (zookeeper.c:5319-5322).
4. `ZKRS_AUTH_F_ALLOC_FAILED` → SYSTEM. Legacy crashed on its unchecked `malloc` of the entry at this point (GLB-04); the shim keeps the rc order exact by reporting the failure at the same stage.
5. The entry is appended and the token retained. If the state is connected (or Associating with `auth_dup_while_associating` and no SASL), the packet is queued. Auth-frame encoding is infallible and aborts on OOM (CC-10 (00 §4), 07 P7), so `ZKRS_OK` is the only retaining status.

`zkrs_add_auth_check` runs stages 1-3 with no lock and returns their status, and the shim takes `auth_lock` only after it returns `ZKRS_OK`. Legacy runs these checks without the lock (zookeeper.c:5309-5328) and takes it at 5330. Thus a call on an expired or closed handle, or with a negative `certLen`, returns ZINVALIDSTATE or ZSYSTEMERROR at once, also when the caller or another thread holds `zoo_lock_auth`. `zkrs_add_auth` runs stages 1-3 again under the lock, because the state can change between the two calls, as it can in legacy; then it runs stages 4-5.

The shim holds `auth_lock` around the whole `zkrs_add_auth` call, which covers legacy's two hold windows (registration at 5330-5338 and send at 2236-2243) within one hold. It takes the lock through the exported `zoo_lock_auth`/`zoo_unlock_auth`, as legacy does, so the lock is the default-attribute mutex of §3 and a caller that already holds `zoo_lock_auth` gets the legacy (undefined) relock behavior. A connected `zoo_add_auth` therefore makes one `zoo_lock_auth`/`zoo_unlock_auth` pair where legacy makes two. Interposition call counts of these two functions are not an ABI contract, and AR-06 (08 §3.6) excludes them. Delivery is `DIRECT`: auth completions arrive as RESULT items after an `AUTH_LOCKPOINT` item, on the IO thread (reply and disconnect teardown), the st caller, or the closing thread (ZCLOSING), including when an mt close runs on the IO thread.

### 12.2 `zoo_aremove_watches`, `zoo_aremove_all_watches`

```c
/* Not exported, like legacy aremove_watches (zookeeper.c:4988). It has hidden visibility and is
   declared in zks_internal.h, not static, because the mt sync wrappers in zks_sync.c call it with
   a record. The sync-record path is compiled only under THREADED, as zks_sync.c is mt only. */
int zks_aremove_watches(zhandle_t *zh, const char *path, ZooWatcherType wtype, watcher_fn w,
                        void *wctx, int local, void_completion_t fn, const void *data, int all, int sync)
{
    zkrs_request rq = ZKRS_REQUEST_INIT(ZKRS_OP_REMOVE_WATCHES);
#ifdef THREADED
    struct zks_sync *rec = sync ? (struct zks_sync *)data : NULL;
#endif
    zkrs_status s;
    if (zh == NULL) return ZBADARGUMENTS;
    ZKS_REGISTRY_CHECK(zh, __func__);   /* debug builds only, §15.5 */
    rq.path = path; rq.wtype = (int32_t)wtype; rq.watch_fn = (uintptr_t)w; rq.watch_ctx = (uintptr_t)wctx;
    rq.flags = (local ? ZKRS_F_LOCAL : 0) | (all ? ZKRS_F_REMOVE_ALL : 0);
    s = zks_submit_raw(zh, &rq, ZKRS_K_VOID, sync ? ZKS_FN(ZKS_SYNC_MARKER) : ZKS_FN(fn), data);  /* §12 */
    if (s == ZKRS_OK_LOCAL) {
#ifdef THREADED
        if (rec != NULL) zks_sync_complete_local(rec);   /* rc ZOK, done, signal: legacy notify_sync_completion */
#endif
        /* async: data never touched (WAT-13) */
    }
    return zks_funnel(s);                                /* ZKRS_OK_LOCAL -> ZOK */
}

int zoo_aremove_watches(zhandle_t *zh, const char *path, ZooWatcherType wtype, watcher_fn w,
                        void *wctx, int local, void_completion_t *completion, const void *data)
{
    void_completion_t fn;
    memcpy(&fn, &completion, sizeof fn);       /* the pointer VALUE is the function (zookeeper.c:3747) */
    return zks_aremove_watches(zh, path, wtype, w, wctx, local, fn, data, 0, 0);
}
```

zk-proto order (zookeeper.c:4988-5084): prepend and validate with mode 0 (EARLY_BADARGS; NULL path with chroot → EARLY_BADARGS, PTH-01); `!local && state < 0` → EARLY_STATE; `pathHasWatcher` over the active tables (NULL watcher always matches; invalid `wtype` → NOWATCHER); `local` → remove now and return `OK_LOCAL` (token not retained, completion never called); else send CheckWatches (17) or RemoveWatches (18) and prune local tables only on ZOK.

### 12.3 `zoo_amulti`

```c
int zoo_amulti(zhandle_t *zh, int count, const zoo_op_t *ops, zoo_op_result_t *results,
               void_completion_t completion, const void *data)
{
    zkrs_request rq = ZKRS_REQUEST_INIT(ZKRS_OP_MULTI);
    struct zks_op_marshal m;                       /* stack array <= 16 entries, else one malloc */
    struct zks_req *r;
    int i, n;
    if (zh == NULL) return ZBADARGUMENTS;          /* NUL-01 */
    ZKS_REGISTRY_CHECK(zh, __func__);              /* debug builds only, §15.5 */
    if (count > 0 && (ops == NULL || results == NULL)) return ZBADARGUMENTS;   /* MUL-08, before any write */
    for (i = 0; i < count; i++) {                  /* call-time writes, zookeeper.c:4880-4912 */
        switch (ops[i].type) {
        case ZOO_CREATE_OP: case ZOO_CREATE_CONTAINER_OP:
            results[i].value = ops[i].create_op.buf; results[i].valuelen = ops[i].create_op.buflen; break;
        case ZOO_SETDATA_OP: results[i].stat = ops[i].set_op.stat; break;
        case ZOO_DELETE_OP: case ZOO_CHECK_OP: break;
        default: goto writes_done;                 /* legacy stops at an unsupported type */
        }
    }
writes_done:
    n = zks_marshal_ops(&m, ops, count);           /* union variant chosen in C; prefix up to and
                                                      including the first unsupported type; OOM -> MARSHAL_FAILED */
    r = zks_req_new_multi(completion, data, results, count, ops);  /* copies per-op kind; keeps results only */
    rq.ops = m.ops; rq.ops_len = n; rq.count = count; rq.flags |= m.flags;
    i = zks_submit_prepared(zh, &rq, r);           /* same as zks_submit with kind MULTI */
    zks_marshal_free(&m);
    return i;
}
```

`zks_req_new_multi` copies each op's result-writer kind (STRING for creates, STAT for set, VOID otherwise) into its own array and keeps only `results`; it never keeps `ops`, so an application may free or reuse `ops` immediately (08 DA-13). zk-proto rules: an op type outside {1, 19, 2, 5, 13} → UNIMPLEMENTED, which overrides earlier errors (and fires the "Unimplemented sub-op type=%d in multi-op" ERROR site); other per-op failures, including an expired handle, bad value buffers and unusable ACLs → LATE; there is no top-level state check, so `count <= 0` returns ZOK even when expired (RC-06).

Completion (`zks_multi_complete`, async on the dispatch thread or st inline; the sync copy-out runs the same writer on the waiting thread, §13). C calls the `abi::raw` multi writer and then the completion:

```c
/* shim/zks_multi.c */
/* hidden; declared in zks_internal.h because zks_deliver_result in zks_drain.c calls it */
void zks_multi_complete(struct zks_req *r, const zkrs_result_view *v)
{
    ZKS_CALL(zkrs_write_multi(v, r->results, r->op_kind, r->count));   /* results[] writes, §10.1 */
    if (r->fn.v) r->fn.v(v->rc, r->data);                               /* NUL-05 */
}
```

```rust
// zk-ffi/src/abi/raw/writers.rs (sketch): the legacy op_result_string_completion / op_result_stat_completion
// rules. results points at the caller's zoo_op_result_t[count]; nops <= count (legacy contract).
if v.failed == 1 && v.nops == 0 {                          // reply None: cleanup_failed_multi, every op
    for i in 0..count as usize {
        let res = results.wrapping_add(i);
        unsafe { (&raw mut (*res).err).write(v.rc) };
        match kinds[i] {
            K_STRING => unsafe { (&raw mut (*res).value).write(ptr::null_mut()) }, // valuelen never updated
            K_STAT => unsafe { (&raw mut (*res).stat).write(ptr::null_mut()) },
            _ => {}
        }
    }
    return;
}
for (i, o) in v.ops().iter().enumerate() {
    let res = results.wrapping_add(i);
    unsafe { (&raw mut (*res).err).write(o.err) };
    match kinds[i] {
        K_STRING => unsafe {
            let value = (&raw const (*res).value).read();          // read at completion time, as legacy
            match o.path() {
                Some(p) if !value.is_null() && o.failed == 0 => {
                    let cap = (&raw const (*res).valuelen).read();
                    let n = p.iter().position(|&b| b == 0).unwrap_or(p.len()); // strlen, as op_result_string_completion
                    let len = (n as i64 + 1).min(cap as i64);
                    if len > 0 {
                        ptr::copy_nonoverlapping(p.as_ptr(), value.cast(), (len - 1) as usize);
                        value.add((len - 1) as usize).write(0);
                    }
                }
                _ => (&raw mut (*res).value).write(ptr::null_mut()), // valuelen never updated
            }
        },
        K_STAT => unsafe {
            let st = (&raw const (*res).stat).read();
            match o.stat() {
                Some(s) if !st.is_null() && o.err == 0 && o.failed == 0 => write_stat(st, s),
                _ => (&raw mut (*res).stat).write(ptr::null_mut()),
            }
        },
        _ => {}
    }
}
```

The multi's delivery lines are in the view's `delivery_logs` (§10), and `zks_deliver_result` emits them before it calls `zks_multi_complete`: "Calling COMPLETION_MULTI for xid=%#x failed=%d rc=%d" with the reply-header or teardown values (`failed=0 rc=0` for a decoded reply, whose completion rc `deserialize_multi` computes afterwards), then, for each sub-op in order, the line of its kind (STRING for creates, STAT for set, VOID for delete and check) with the multi's xid, `failed` = the sub-result is an error result and `rc` = its err (for a failed multi, `failed` = 1 and the multi's rc for every sub-op, as `cleanup_failed_multi` passes, zookeeper.c:3298-3306), each STRING line followed by that sub-op's strip ERROR (zookeeper.c:3308-3336, 3440-3449). Legacy writes each `results[i]` between these lines; the writers run after the last one. No application code other than a log callback runs in between, and `results[]` belongs to the pending multi until its completion runs.

The core produces `ops[]` by wire type (MUL-07, proto P15) with `failed = 1` for every error result. A header error with no decoded body (async, or a header-only sync reply) or a teardown has reply None and no items (proto P16): the view has `nops = 0` and `failed = 1`, and the `abi::raw` multi writers then apply `{err = v.rc, failed = 1}` to each of the `count` results, using `op_kind`. Sync `zoo_multi` with `origin == TEARDOWN` writes nothing into `results[]`; a header-only error gets the async semantics (MUL-06). A sync header error that carries a body is decoded by wire type, and its rc comes from the sub-results (02 §7.1, 08 MU-04). An async multi in the RO→RW switch gets ZCONNECTIONLOSS (`cleanup_failed_multi` semantics, ST-08).

## 13. Sync API wrappers (mt only)

```c
struct zks_sync {                      /* heap; one reference for the waiter, one for the completer */
#ifdef _WIN32
    SRWLOCK m; CONDITION_VARIABLE c;
#else
    pthread_mutex_t m; pthread_cond_t c;
#endif
    int refs, done, rc, failed, origin;
    uint32_t kind;                     /* ZKRS_K_* of the wrapper */
    char *data; int32_t data_len;      /* abi::raw malloc copy; -1 for a null znode */
    char *path; int32_t path_len;      /* abi::raw malloc copy of the stripped path */
    struct Stat stat;                  /* zero unless a decoded reply filled it */
    struct String_vector strs; struct ACL_vector acl;   /* legacy layout (abi::raw); ownership -> caller */
    zkrs_multi_copy *ops; int32_t nops; uint8_t *op_kind;   /* sync multi: per-op copies and kinds */
};

/* zkrs.h: the sync copy-out. The source is the record's reply image; the destination is the
 * caller's outputs, NULL where the wrapper has none. int and int32_t agree (zks_layout_check.c). */
typedef struct zkrs_sync_src {
    uint32_t kind; int32_t rc, failed, origin;           /* rc: the MULTI rule of §12.3 with nops = 0 */
    const char *data; int32_t data_len;
    const char *path; int32_t path_len;
    const void *stat;                                    /* const struct Stat *: the record's */
    const zkrs_multi_copy *ops; int32_t nops; const uint8_t *op_kind;
} zkrs_sync_src;
typedef struct zkrs_sync_dst {
    char *buffer; int32_t *buffer_len;                   /* DATA */
    char *path_buffer; int32_t path_buffer_len;          /* STRING, STRING_STAT */
    void *stat;                                          /* struct Stat * */
    void *results; int32_t count;                        /* MULTI: zoo_op_result_t[] */
} zkrs_sync_dst;
```

- **Allocation.** `zks_sync_new(kind)` uses `calloc` and returns NULL on failure; the wrapper returns ZSYSTEMERROR, as `alloc_sync_completion` does (mt_adaptor.c:72-80, also a `calloc`).
- **Completion (`zks_sync_complete`, completing thread, no user code).** `zkrs_write_result` (§10.1) builds the reply into the record's members with no lock held, through destinations that point at those members: for MULTI the per-op copies always (the record also takes over the per-op kinds array of the `zks_req`), and for every other kind only when `rc == 0 && !failed`. The waiter reads them only after it sees `done` under the mutex, so the lock orders the writes. Then lock; set `rc`, `failed`, `done`; `pthread_cond_signal` (`WakeConditionVariable` on Windows); unlock; drop the completer reference. The completer touches nothing after its unreference. The completing thread is the IO thread (reply, disconnect teardown) or the closing thread (ZCLOSING), as legacy. In the mt IO step and in close, a run of sync results ends at a barrier (§5), so every waiter is signalled before later frames are processed or the close continues.
- **Waiting.** `zks_sync_wait` waits on the condvar with a `pthread_cleanup_push` handler that drops the waiter reference if the thread is cancelled, so a cancelled waiter cannot leave a dangling stack record; the completer then frees the record and every object in it with libc `free` and `deallocate_*`. On Windows, `zks_sync_wait` uses `SleepConditionVariableSRW` with `INFINITE`. Windows has no pthread cancellation, so no cleanup handler is needed. `zks_sync_complete` uses `WakeConditionVariable`.
- **Copy-out on the waiting thread, after wake** (K12). The caller's buffers are written only by the caller's thread, by `zkrs_sync_copy_out` (§10.1), and only when `rc == ZOK`, except MULTI: its per-op writers run for every rc, as legacy `deserialize_multi` runs for every reply (zookeeper.c:5529-5531). The vectors are not caller buffers: the wrapper hands them over in C, with a struct assignment to `*strings` or `*acl`, or `deallocate_*` when the pointer is NULL, as legacy does. When `failed == 0` the record holds the decoded reply:
  - DATA (`zoo_wget`, `zoo_get`, `zoo_[w]getconfig`, `zoo_reconfig`): `len = data_len <= cap ? data_len : cap`; copy `len` bytes only if `len > 0 && buffer != NULL`; `*buffer_len = len`; `*stat` if non-NULL. This is the legacy formula; for `cap < -1` it reports `cap` and copies nothing (DAT-05).
  - STRING/STRING_STAT (`zoo_create*`): `n` is the length of the stripped path up to its first NUL byte (legacy `strlen(client_path)`, zookeeper.c:5486, 5504); `len = min(n + 1, path_buffer_len)`; if `len > 0` and `path_buffer != NULL`, write `len - 1` bytes plus NUL. Stat only if non-NULL.
  - STAT: `*stat` only when non-NULL. ZNONODE is `failed`; the exist-table watch is armed by the core.
  - STRINGLIST[_STAT]: `*strings` receives the record's vector (ownership passes); `strings == NULL` frees it. A NULL stat is tolerated (DAT-07).
  - ACLLIST: same transfer; `acl == NULL` frees it.
  - MULTI: the §12.3 writers run on the waiting thread from the copied per-op data. A record with `nops = 0`, `failed = 1` and `origin != TEARDOWN` (a header-only error) takes `{err = rc, failed = 1}` for each of the `count` results, using the record's `op_kind`.
  - VOID: rc only.
- **Error rcs** leave every output untouched, except that a MULTI reply runs the §12.3 per-op writers. A teardown result never writes, for MULTI (`origin == TEARDOWN`) too. This includes ZCONNECTIONLOSS on the RO→RW switch (DV-FIX-9) and on a forced close (DV-FIX-8), and ZAUTHFAILED on a SASL failure (DV-FIX-3).
- **IO-thread guard.** If `zks_current_role(zh) == ZKRS_ROLE_IO`, the wrapper returns ZSYSTEMERROR with `errno = EDEADLK` before anything is queued (DSP-04).

Example and delegation:

```c
int zoo_wget(zhandle_t *zh, const char *path, watcher_fn w, void *wctx,
             char *buffer, int *buffer_len, struct Stat *stat)
{
    struct zks_sync *s; int rc;
    if (buffer_len == NULL) return ZBADARGUMENTS;
    if (zh != NULL) ZKS_REGISTRY_CHECK(zh, __func__);   /* debug builds only, §15.5 */
    if (zh != NULL && zks_current_role(zh) == ZKRS_ROLE_IO) { errno = EDEADLK; return ZSYSTEMERROR; } /* DSP-04 */
    if ((s = zks_sync_new(ZKRS_K_DATA)) == NULL) return ZSYSTEMERROR;
    rc = zoo_awget(zh, path, w, wctx, (data_completion_t)ZKS_SYNC_MARKER, s);   /* exported, interposable */
    if (rc == ZOK) {
        zks_sync_wait(s);
        rc = s->rc;
        if (rc == ZOK) {                               /* legacy rc==0 copy-out, in abi::raw */
            zkrs_sync_src src = zks_sync_src(s);       /* view of the record's members */
            zkrs_sync_dst dst = ZKRS_SYNC_DST_INIT;    /* all NULL */
            dst.buffer = buffer; dst.buffer_len = (int32_t *)buffer_len; dst.stat = stat;
            ZKS_CALL(zkrs_sync_copy_out(&src, &dst));  /* truncation formula */
        }
    } else {
        zks_sync_drop_completer_ref(s);   /* not submitted: the completer reference is never used */
    }
    zks_sync_unref(s);
    return rc;
}
```

In `zoo_create2_ttl`, the rc==0 branch passes `path_buffer`, `path_buffer_len` and `stat` in `zkrs_sync_dst`. `zkrs_sync_copy_out` copies the path by the STRING rule and the record's `Stat`. In `zoo_[w]get_children[2]` and `zoo_get_acl`, the rc==0 branch runs the copy-out for the `Stat` and then hands the record's vector over in C, as legacy does.

**Sync getconfig (DV-FIX-5, 07 PTH-05).** `zoo_getconfig` and `zoo_wgetconfig` read the absolute config node under any chroot. Legacy calls `zoo_wget` on `ZOO_CONFIG_NODE`, which prepends the chroot and so reads the wrong node (zookeeper.c:5677-5687). Both call the static `zks_wgetconfig_`, which is `zoo_wget`'s body with `zoo_awgetconfig` (exported, through the PLT) in place of `zoo_awget`: `zoo_awgetconfig(zh, w, wctx, (data_completion_t)ZKS_SYNC_MARKER, s)`. The request therefore carries `ZKRS_F_RAW_PATH`, and the watch is keyed by the raw path, as for async getconfig. Under no chroot the bytes on the wire and the rc are legacy's. `zoo_getconfig` returns ZBADARGUMENTS for a NULL `zh`, reads the pair cell (`zks_watch_cell_read(&zh->dw)`) and passes `watch ? p.fn : NULL` and `p.ctx`, as `zoo_aget` does (§12).

The 23 wrappers follow legacy delegation exactly (§20.1), except that edge. `zoo_remove_watches`/`zoo_remove_all_watches` call the static `zks_remove_watches` in `zks_sync.c`, as legacy `zoo_remove_watches`/`zoo_remove_all_watches` call the static `remove_watches` (zookeeper.c:5868-5890, 5911-5922). `zks_remove_watches` checks `path == NULL` first (ZBADARGUMENTS), then calls the hidden `zks_aremove_watches(..., sync = 1)` (§12.2); a local removal completes the record immediately, so the wait returns ZOK at once. `zoo_set_acl` casts away const and calls `zoo_aset_acl`. `zoo_remove_all_watches` is compiled only into mt.

st compiles none of the sync-record code: `zks_sync.c` is mt only, and the calls to `zks_sync_complete` (§10) and `zks_sync_complete_local` (§12.2) are under `#ifdef THREADED`. No st caller passes `ZKS_SYNC_MARKER`, so no st request has mode `ZKS_REQ_SYNC`.

A sync result bypasses the FIFO, so it can return before earlier async completions and watch events, and a sync call from a dispatch-thread callback works because the IO thread completes it. On a SASL failure, pending sync waiters get ZAUTHFAILED as part of the teardown (`Compat::sasl_failure = Teardown`; DV-FIX-3, ST-18, 02 §7.4).

## 14. `zookeeper_interest` / `zookeeper_process`

st:

```c
int zookeeper_interest(zhandle_t *zh, ZKS_FD_T *fd, int *interest, struct timeval *tv)  /* SOCKET* on WIN32 */
{
    const zkrs_handle *core;
    zkrs_interest_out o; zkrs_outbox ob = ZKRS_OUTBOX_INIT; int written = 0, rc = ZOK; zkrs_step s;
    if (zh == NULL || fd == NULL || interest == NULL || tv == NULL) return ZBADARGUMENTS;
    ZKS_REGISTRY_CHECK(zh, __func__);   /* debug builds only, §15.5 */
    core = zh->core;
    s = ZKS_CALL(zkrs_interest(core, &o, &written, &ob, &rc));
    for (;;) {                               /* inline callbacks; may release zh */
        zks_drain(zh, &ob);
        if (s != ZKRS_MORE) break;
        s = ZKS_CALL(zkrs_interest_resume(core, &o, &written, &ob, &rc));
    }
    if (s == ZKRS_RELEASE) zks_release(zh, core);   /* zh and core are dead after this */
    if (written) {                           /* outputs are caller memory: written even after release */
        *fd = (ZKS_FD_T)o.fd; *interest = o.interest;
        tv->tv_sec = (long)(o.timeout_ms / 1000); tv->tv_usec = (long)((o.timeout_ms % 1000) * 1000);
    }
    return rc;
}

int zookeeper_process(zhandle_t *zh, int events)
{
    const zkrs_handle *core; zkrs_outbox ob = ZKRS_OUTBOX_INIT; int rc = ZOK; zkrs_step s;
    if (zh == NULL) return ZBADARGUMENTS;
    ZKS_REGISTRY_CHECK(zh, __func__);   /* debug builds only (§15.5) */
    core = zh->core;
    s = ZKS_CALL(zkrs_process(core, events, &ob, &rc));
    return zks_drive(zh, core, s, &ob, &rc);
}
```

- **Out-parameters** (CC-22 (00 §4)). `written` is false only on the unrecoverable-state return and on a whole-list resolve failure with no socket open; the outputs then stay unwritten, so an st loop that pre-sets them sees them unchanged. A DNS failure while a socket is open writes them: timers, pings and interest run in that iteration and the application needs the resulting fd, bits and tv (SRV-06, FU). The rc is the resolver rc unless a teardown occurs in the call. After a timeout or RW switch closes the socket inside the call, `fd` is -1 (SRV-13). `o` and `written` are C locals that `zkrs_interest` and `zkrs_interest_resume` receive anew at each call. When an exception unwinds `zookeeper_interest`, its suspended continuation completes in the next driving call (§5), and the outputs of that continuation go nowhere, because the frame that held them is gone.
- `ZKS_FD_T` is `int` on POSIX and `SOCKET` under `WIN32`, matching the header prototype. `zkrs_interest_out.fd` is `intptr_t` so the full 8-byte `SOCKET` round-trips on Win64.
- The fd value, bits (`DriverMode::interest = LegacyExact`), timeout values, one plaintext frame per call, ZNOTHING, and the inline callback points are driver semantics. Under TLS one call may dispatch more than one already-decrypted frame, and interest returns tv=0 while one is buffered (TLS-04, FU).
- The SASL rc: when a SASL negotiation fails (a server rejection, a mechanism or address failure, a reply decode error), the driving call (`zookeeper_process`, or `zookeeper_interest` for a requeued item) returns the SASL rc, as legacy's `return rc` from `check_events` after `process_sasl_response` or `zoo_sasl_connect`/`zoo_sasl_client_start` (zookeeper.c:3145-3150, 3188-3193); it arrives in `*rc` with `ZKRS_DONE`, from `zkrs_process`, from the last `zkrs_resume` or from the last `zkrs_interest_resume`. The teardown of 02 §7.4 runs in the same call, so the AUTH_FAILED event and the ZAUTHFAILED completions are delivered before the call returns (DV-FIX-3).
- **Forced close under R-CB (DV-FIX-8, 02 §12.3).** A forced close inside an API call (`zoo_a*` flush failure, a rebalance in `zoo_set_servers`) sets state 999 and closes or marks the transport in that call, and its outbox holds LOG items only, so no application callback runs in the API frame. The core takes the work pending at that point as a snapshot in the same call. In st the next `zookeeper_interest` or `zookeeper_process` is the driving call that delivers the event and the snapshot's ZCONNECTIONLOSS results, from its own C frame through its drive loop (`zks_drive` in `zookeeper_process`); `zookeeper_process` then returns ZINVALIDSTATE, and `zookeeper_interest` goes on to its normal work and rc. A forced close found in `zookeeper_interest` itself delivers in that call. In mt the IO thread does the same from its C loop, sync and auth results run there, and the event and async results run on the dispatch thread (03 §10.11, §10.12). A sync record in the snapshot stays waiting until that RESULT item. A request or `zoo_add_auth` made after the trigger, from any frame, is outside the snapshot and stays pending for the next connection, and its token stays with the core.

mt: both symbols stay exported and are not connected to the threaded driver (DSP-18): after the legacy argument and unrecoverable-state checks, `zookeeper_interest` writes `*fd = -1`, `*interest = 0`, `tv = recv_timeout/3` and returns ZOK; `zookeeper_process` returns ZINVALIDSTATE.

## 15. Lifecycle

### 15.1 Init

The order reproduces `zookeeper_init_internal` (zookeeper.c:1348-1505):

```c
/* shim/zks_handle.c. Hidden visibility (§2 naming rule) and declared in zks_internal.h, not static,
   because zookeeper_init_sasl in zks_sasl_cyrus.c also calls it. */
zhandle_t *zks_init_common(const char *host, watcher_fn fn, int recv_timeout, const clientid_t *cid,
        void *ctx, int flags, log_callback_fn logcb, const struct zks_tls_args *tls,
        const struct zoo_sasl_params *sasl)
{
    zhandle_t *zh = calloc(1, sizeof *zh);
    zkrs_init_args a; zkrs_handle *core = NULL; zkrs_outbox ob = ZKRS_OUTBOX_INIT; int err = 0; zkrs_step s;
#ifdef THREADED
    int auth_lock_ready = 0;         /* set once zh->auth_lock is initialized; fail_core destroys it */
#endif
    if (zh == NULL) { errno = ENOMEM; return NULL; }
    ZKS_STORE(&zh->log_callback, logcb);
    if (!(flags & ZOO_NO_LOG_CLIENTENV)) zks_log_env(zh);                 /* C */
#ifdef _WIN32
    if (zks_wsa_startup(zh) != 0) {                                      /* Win32WSAStartup parity */
        ZKS_LOG(zh, ZKS_SITE_INIT_WSA_FAILED); err = errno; goto fail; }
#endif
    ZKS_LOG(zh, ZKS_SITE_INIT_INITIATING, host, recv_timeout, (void *)fn,
            cid ? cid->client_id : 0, (!cid || !cid->passwd[0]) ? "<null>" : "<hidden>", ctx, flags);
    zks_watch_cell_init(&zh->dw, fn ? fn : zks_null_watcher, ctx);
    if (host == NULL || *host == 0) { err = EINVAL; goto fail; }
    if (cid) memcpy(&zh->client_id, cid, sizeof *cid);
    zks_fill_init_args(&a, zh, host, recv_timeout, cid, flags, tls, sasl != NULL);  /* TLS strings borrowed;
                                                                           also sets tls_env_allowed and c_char_signed */
    s = ZKS_CALL(zkrs_handle_new(&a, &zks_vtable, (uintptr_t)zh, &core, &ob, &err));
    zh->core = core;                                  /* set before the first barrier; NULL on a chroot error */
    while (s == ZKRS_MORE) {                          /* log flush before each resolve and the reseed (§5);
                                                         with sasl = 1, also the SASL point before the waker */
        zks_drain(zh, &ob);
#ifdef ZKS_WITH_SASL
        if (err == ZKRS_INIT_SASL_POINT) {            /* after resolution and the reseed, as 1472-1479 */
            err = 0;
            if ((zh->sasl = zks_sasl_sess_new(zh, sasl)) == NULL) { err = ENOMEM; goto fail_core; }
        }
#endif
        s = ZKS_CALL(zkrs_resume(core, &ob, &err)); } /* the resume after the SASL point creates the waker */
    zks_drain(zh, &ob);                               /* resolver error lines; "Can't make a pipe" */
    if (err != 0) { if (core == NULL) goto fail; goto fail_core; }   /* fail_core abandons the handle */
    ZKS_REGISTRY_ADD(zh);            /* debug builds only (§15.5); before any library thread can run */
#ifdef THREADED
#ifdef _WIN32
    zh->auth_lock = CreateMutex(NULL, FALSE, NULL);   /* as winport.c:37-44 (legacy pthread_mutex_init) */
#else
    pthread_mutex_init(&zh->auth_lock, 0);   /* default attributes, as mt_adaptor.c:253 (legacy &zh->auth_h.lock) */
#endif
    auth_lock_ready = 1;
    if ((err = zks_start_threads(zh)) != 0) goto fail_core;              /* INIT-12 */
#endif
    return zh;                       /* mt: callbacks may already be running */
fail_core:
#ifdef THREADED
#ifdef _WIN32
    if (auth_lock_ready && zh->auth_lock != NULL) CloseHandle(zh->auth_lock);   /* created above */
#else
    if (auth_lock_ready) pthread_mutex_destroy(&zh->auth_lock);          /* initialized above */
#endif
#endif
#ifdef ZKS_WITH_SASL
    if (zh->sasl) { zks_sasl_sess_free(zh->sasl); zh->sasl = NULL; }
#endif
    ZKS_REGISTRY_REMOVE(zh);         /* no-op when zh was not added */
    ZKS_CALL(zkrs_handle_abandon(core));
fail:
    free(zh); errno = err; return NULL;
}
```

`zks_fill_init_args` sets `tls_env_allowed` (§6) on every init, TLS or not; Rust uses it only on TLS handles. It also sets `c_char_signed` to `CHAR_MIN < 0` as the shim's compiler sees it (so `-funsigned-char` and `-fsigned-char` take effect), and zk-ffi copies it into `Config.c_char_signed` for the djb2 hash of `LegacyWatchTable` (02 §3.3, §10.1).

`zkrs_init_args` has no fields for the native-only `Config` knobs, so zk-ffi fills them. zk-ffi builds `zk_proto::Config` with the legacy values of the native-only fields: `connect_timeout_ms = None`; `reconnect_delay_ms`, `reconnect_jitter_pct` and `close_timeout_ms = 0`; `max_pending = None`; `restore_watches = true`; `resolve_delay_ms` starts at 0 and changes only through `zkrs_set_resolution_delay`. `Compat::resolve_cadence` is `EveryIterationWhileDisconnected` (02 §3.3; 08 NT-08(d)).

`zkrs_handle_new` resolves the hosts one at a time through the Blocking `resolve` entry and then runs `rng_reseed` if it is due. It stops at the log-flush barrier of §5 before each of these calls when LOG items are pending, so a resolver failure line for one host reaches the log callback before the next host is resolved, as in legacy `resolve_hosts` (zookeeper.c:962-990), which also logs before `setup_random` reseeds (1035-1038). It sets `*out` before its first barrier, so `zkrs_resume` has a handle. A chroot error fails before resolution and before the handle exists, as legacy validates the chroot before `update_addrs` (zookeeper.c:1443-1450), and leaves `*out` NULL. A port error (no `:`, bad port) of host entry k is reported only after entries 0..k-1 have been resolved through the per-host loop, with their LOG items and log-flush barriers, because legacy parses each entry's port inside the `resolve_hosts` loop, after `getaddrinfo` has run for the earlier entries (zookeeper.c:870-889, 962, 1023-1024); its "no port in %s" or "invalid port in %s" line follows their lines, no reseed runs, and the result is EINVAL. That failure, and every later one, returns `ZKRS_DONE` with the errno and `*out` set, and C abandons the handle. After the resolution loop and the reseed, `zkrs_handle_new` creates the mt waker, as legacy creates its pipe in `adaptor_init` after resolution (zookeeper.c:1450, 1482; mt_adaptor.c:244). Legacy copies the SASL client between the two (zookeeper.c:1472-1479), and the shim keeps that order. When `zkrs_init_args.sasl = 1`, `zkrs_handle_new` returns `ZKRS_MORE` after the resolution loop and the reseed, before it creates the mt waker, with `*rc` set to `ZKRS_INIT_SASL_POINT` (no errno is negative, so the value never collides with a failure). C then calls `zks_sasl_sess_new`; on failure it abandons the handle with ENOMEM, and no waker exists yet. Otherwise C sets `err` back to 0, and `zkrs_resume` then creates the waker. Thus a resolution errno wins over the SASL ENOMEM, and the SASL ENOMEM wins over a waker errno, as in legacy. With `sasl = 0` the SASL point does not occur, and the step goes from the reseed straight to the waker.

| Cause | errno | Source |
|---|---|---|
| `zookeeper_init_ssl` cert NULL | EINVAL | C (INIT-13) |
| `zookeeper_init_ssl` cert copy (`strdup`) | ENOMEM | C, as legacy (zookeeper.c:1525-1529) |
| `calloc(zh)` | ENOMEM | C |
| `WSAStartup` (Windows) | as legacy | C, same log line |
| host NULL or "" | EINVAL | C |
| bad chroot | EINVAL | zk-proto legacy parse, before resolution |
| host without `:`; bad port | EINVAL | zk-proto, in the per-host loop after the earlier hosts are resolved |
| every host fails `getaddrinfo` | mapping of the last failure | `resolve` entry |
| `","`/`",,"` | EINVAL (legacy: stale) | zk-proto |
| SASL params copy (`calloc`, capped `malloc`) | ENOMEM | C, at the SASL point after resolution and the reseed, before the waker, as legacy |
| waker or thread creation | raw OS errno (EMFILE, EAGAIN) | Rust (waker, after the resolution loop, the reseed and the SASL point) / C (`pthread_create`, INIT-12). On waker creation failure `zkrs_handle_new` (or the `zkrs_resume` that continues it) emits the core ERROR site "Can't make a pipe %d" with the errno (mt_adaptor.c:245; "Can't make a socket." on Windows, mt_adaptor.c:242) into its outbox, and C drains it before it returns NULL. On Windows the core first emits the legacy line of the failing pair step: "Error creating socket. %d" (mt_adaptor.c:134) or "%s. %d" (mt_adaptor.c:119, with the step texts of :147, :150, :156, :160), then "Can't make a socket." These are rows of `abi/log-sites.tsv` |

TLS configuration is not checked at init. The CA, chain and key are read at each connection attempt, as legacy (TLS-02, CC-14 (00 §4)).

`zookeeper_init_ssl` (shim C) only parses the cert string. It passes four borrowed strings to Rust through `zkrs_init_args` and frees its copy when init returns:

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

Preserved from zookeeper.c:1521-1535: empty fields collapse (`"ca,,key,pw"` gives cert=key and key=pw); at most 4 fields are used; whitespace is kept; `""` gives all four NULL with `tls = 1`; the client identity is used only when cert, key and passwd are all non-NULL (06 §3). `strtok_r` tokenizes identically to `strtok`, but it does not clobber the caller's `strtok` state or race other init calls (INIT-14/SEC-5; 08 ER-05 checks the field-shift rows (EXACT), and 08 §4.5 INIT-14 tests the clobber row and concurrent init as DEV). `zookeeper_init_ssl` is exported exactly when TLS is enabled by the legacy decision procedure: `--with-openssl` / `WITH_OPENSSL` keep their names, defaults and autodetect probe (TLS is built when `openssl/ssl.h` and `-lssl SSL_CTX_new` are found), or the opt-in `--with-rust-tls=yes` / `WITH_RUST_TLS` enables it without OpenSSL headers (01 §4.8). The shim then has `ZKS_WITH_TLS`. The function keeps its ABI meaning and needs no OpenSSL library: the probe only decides the feature, and OpenSSL is never linked (DV-TLS-4). `zookeeper_init_sasl` is defined in `zks_sasl_cyrus.c` and calls `zks_init_common(…, NULL, sasl_params)`; NULL `sasl_params` behaves like `zookeeper_init2`.

### 15.2 mt threads (C)

```c
/* shim/zks_threads.c (THREADED only). It passes the static zks_io_main and zks_dispatch_main
   of this file to pthread_create, so it lives here. zks_init_common in zks_handle.c calls it,
   so it has hidden visibility and is declared in zks_internal.h under THREADED, not static.
   zks_stop_io_thread is used only here and stays static. */
int zks_start_threads(zhandle_t *zh)
{
    const zkrs_handle *core = zh->core;
    int err;
    zks_latch_init(&zh->start_latch, 3);                 /* IO, dispatch, init thread */
    ZKS_CALL(zkrs_thread_enter(core, ZKRS_ROLE_INIT));   /* api_prolog */
    ZKS_CALL(zkrs_handle_retain(core));                  /* reference owned by the IO thread */
    ZKS_LOG(zh, ZKS_SITE_STARTING_THREADS);                  /* DEBUG, mt_adaptor.c:221 */
    if ((err = pthread_create(&zh->io_thread, NULL, zks_io_main, zh)) != 0) {
        ZKS_CALL(zkrs_handle_release(core));
        zks_latch_destroy(&zh->start_latch);
        return err; }
    ZKS_CALL(zkrs_handle_retain(core));
    if ((err = pthread_create(&zh->dispatch_thread, NULL, zks_dispatch_main, zh)) != 0) {
        ZKS_CALL(zkrs_handle_release(core));
        zks_stop_io_thread(zh);                          /* close_requested, open latch, wake, join */
        zks_latch_destroy(&zh->start_latch);
        return err; }
    zks_latch_arrive_and_wait(&zh->start_latch);         /* publishes both thread ids */
    zks_init_epilog(zh, core);                           /* api_epilog; cannot release during init */
    return 0;
}

static void *zks_io_main(void *arg)
{
    zhandle_t *zh = arg; const zkrs_handle *core = zh->core;
    zkrs_outbox ob = ZKRS_OUTBOX_INIT; int rc = 0; zkrs_step s; log_callback_fn cb;
    zks_tls_zh = zh; zks_tls_role = ZKRS_ROLE_IO;        /* before the latch */
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
    ZKS_CALL(zkrs_handle_release(core));                 /* this thread's reference */
    return NULL;
}
```

`zks_init_epilog` (static in `zks_threads.c`) calls `zkrs_thread_exit(core, ZKRS_ROLE_INIT, &ob, &rc)` and drains; it cannot return `ZKRS_RELEASE` during init. On a `pthread_create` failure the prolog is discarded with the handle by `zkrs_handle_abandon`.

`zks_dispatch_main` is the same shape with `zkrs_wait_dispatch` (it returns `ZKRS_DONE` with a batch, or `ZKRS_EXIT`), `ZKS_LOG(zh, ZKS_SITE_COMPLETION_THREAD_STARTED)` right after `zks_latch_arrive_and_wait` (mt_adaptor.c:492), and `ZKS_SITE_COMPLETION_THREAD_TERMINATED`. The three start lines are emitted by C because `zkrs_thread_enter` has no outbox. Threads get default pthread attributes (`_beginthreadex(NULL, 0, …)` on Windows), the inherited signal mask and no name, as legacy (K13). On Windows `zks_start_threads` calls `_beginthreadex` in place of `pthread_create` and stores the returned `HANDLE` in `zh->io_thread`/`zh->dispatch_thread`; a zero return is a failure with `errno`, as winport.c:52-58. `zks_thread_item` (§15.3) joins and detaches with these HANDLEs. The init thread is the third latch party, so its stores to `zh->io_thread`/`zh->dispatch_thread` are ordered before any library thread runs application code; the start delay this adds is not observable. A `pthread_create` failure returns NULL with that errno (INIT-12); each retained reference is dropped exactly once on every path, and an LSan test injects the failure.

The IO step is one `do_io` iteration: interest, `poll` over waker and socket (blocking, in Rust, with no lock and no application code), process; TLS records are encrypted and decrypted by rustls inside this step. It returns `ZKRS_MORE` at barriers and `ZKRS_EXIT` when `close_requested` or an unrecoverable state ends the loop (mt_adaptor.c:465-468). zk-driver owns the step's internals (`IoCore::{interest_step, process_step}`); this section fixes the interface. `DriverMode` for mt is `{interest: Precise, session_delivery: Dispatch}`.

### 15.3 Close

```c
int zookeeper_close(zhandle_t *zh)
{
    const zkrs_handle *core; zkrs_outbox ob = ZKRS_OUTBOX_INIT; int rc = ZOK; zkrs_step s;
    if (zh == NULL) return ZBADARGUMENTS;
    ZKS_REGISTRY_CHECK(zh, __func__);   /* debug builds only (§15.5) */
    core = zh->core;
    s = ZKS_CALL(zkrs_close(core, zks_current_role(zh), &ob, &rc));
    return zks_drive(zh, core, s, &ob, &rc);        /* zh may be released before this returns */
}

/* shim/zks_threads.c (THREADED only) */
/* THREAD items: C joins or detaches with no Rust frame on the stack (CC-3 (00 §4)).
   THREAD items exist only in mt. The function is declared in zks_internal.h and is not static,
   because zks_drain() in zks_drain.c calls it under #ifdef THREADED. */
void zks_thread_item(zhandle_t *zh, uint32_t op, uint32_t role)
{
#ifdef _WIN32
    /* The HANDLEs are the ones _beginthreadex returned (§15.2). No winport emulation (DV-BLD-2). */
    HANDLE h = role == ZKRS_ROLE_IO ? zh->io_thread : zh->dispatch_thread;
    if (op == ZKRS_THREAD_JOIN) {
        WaitForSingleObject(h, INFINITE);
        CloseHandle(h);
    } else {
        CloseHandle(h);                             /* DETACH names the caller's own role */
    }
#else
    if (op == ZKRS_THREAD_JOIN)
        pthread_join(role == ZKRS_ROLE_IO ? zh->io_thread : zh->dispatch_thread, NULL);
    else
        pthread_detach(pthread_self());             /* DETACH names the caller's own role */
#endif
}
```

`zkrs_close` implements close by calling thread with the driver's reference counting (03 §5.9). In every mt case, the ZCLOSING auth completions are drained before the first join, as legacy:

- **mt, foreign thread:** begin close (sync waiters get ZCLOSING RESULT items, drained and signalled on this thread; async fakes go to the FIFO; an `AUTH_LOCKPOINT` then the auth ZCLOSING RESULT group) → waker set → `THREAD{JOIN, IO}` → `THREAD{JOIN, DISPATCH}` (the dispatch thread drains everything first) → final close on this thread (CLOSE_OP flush ≤ 3000 ms, wait ≤ 1500 ms, "Closing…"/"Freeing…" LOG items, transport closed, then SASL `DISPOSE`, as legacy destroy, zookeeper.c:719-749) → `ZKRS_RELEASE`, rc ZOK.
- **mt, dispatch-thread callback:** begin close → `THREAD{JOIN, IO}` → `THREAD{DETACH, DISPATCH}` → `ZKRS_DONE`/ZOK. The dispatch loop delivers the remaining items; its `zkrs_thread_exit` runs the final close and returns `ZKRS_RELEASE`.
- **mt, IO-thread callback** (auth, log, SASL): begin close → `THREAD{DETACH, IO}`, IO marked exited → `THREAD{JOIN, DISPATCH}` → ZOK. The next IO step returns `ZKRS_EXIT`; items queued after the dispatch thread exited are discarded (`discard_token`), auth completions still fire, and the IO thread's exit runs the final close.
- **st, outside a callback:** final close inline (`Compat::close_mode = LegacyFailPending`); auth ZCLOSING then async ZCLOSING are delivered inline; rc is the flush rc.
- **st, inside a callback:** begin close; the fakes are delivered by the enclosing drain; the enclosing `zookeeper_process`/`zookeeper_interest` gets `ZKRS_RELEASE` at its epilog and returns its own rc with `zh` already released.
- **Close while a close is in progress** (including from the closer's own ZCLOSING auth completion): ZOK, no-op (CLS-05).
- **Forked child** (pid differs from the creator; CLS-11): no THREAD items, no CLOSE_OP, no items that reach application code, no SASL `DISPOSE` and no `ZKRS_RELEASE`. Rust closes the fds it can reach; `zh`, the Rust state and the Cyrus session are leaked deliberately (03 §5.11). The only goal is not to end the parent's session.

On a TLS connection, the graceful final close sends the TLS close_notify alert after the CloseSession exchange, or when that exchange ends within the close budget. The driver calls `send_close_notify()`, flushes the alert without blocking within the remaining close budget, and closes the socket; it never waits for the peer's close_notify. Forced and error teardowns send no close_notify, and neither does the RW probe (03 §4.3.5). The extra record is wire-only and not observable by applications (07 P6; 06 §3). After a fatal TLS error that rustls detects (a verify failure, a bad record, a protocol violation), the driver makes one non-blocking send of the fatal alert that rustls queued, and drops the alert when earlier ciphertext is still waiting in `ct_out`; the native API follows the same rule. This is what OpenSSL does on the non-blocking legacy socket: it writes the fatal alert inside `SSL_connect` or `SSL_read`, before the C code sees the error, when no record write is pending, and `SSL_free` discards it otherwise (03 §4.3.5).

### 15.4 Release

```c
/* shim/zks_handle.c. Hidden visibility and declared in zks_internal.h, not static,
   because zks_drive in zks_drain.c calls it on ZKRS_RELEASE (§5). */
void zks_release(zhandle_t *zh, const zkrs_handle *core)
{
#ifdef ZKS_WITH_SASL
    if (zh->sasl)                            /* conn already disposed via DISPOSE; frees copies and s */
        zks_sasl_sess_free(zh->sasl);
#endif
    zks_secure_zero(zh->client_id.passwd, sizeof zh->client_id.passwd);
#ifdef THREADED
#ifdef _WIN32
    if (zh->auth_lock != NULL) CloseHandle(zh->auth_lock);   /* as winport.c:46-50 */
#else
    pthread_mutex_destroy(&zh->auth_lock);
#endif
    zks_latch_destroy(&zh->start_latch);
#endif
#ifdef _WIN32
    if (zh->wsa_started) WSACleanup();       /* Win32WSACleanup parity (zookeeper.c:4058-4060) */
#endif
    ZKS_REGISTRY_REMOVE(zh);                 /* debug builds only (§15.5) */
    free(zh);
    ZKS_CALL(zkrs_handle_release(core));     /* the C-owned reference */
}
```

Rust has zeroed its host token and freed every C-resource-owning value before returning `ZKRS_RELEASE`, so no vtable call can reach the freed `zh`. Rust state, including rustls sessions and the TLS passphrase copy (zeroized on drop), is freed when the last strong reference (C's, a library thread's, or an outbox's) goes. `zks_sasl_sess_free` disposes a Cyrus connection defensively if one is still present.

### 15.5 Use-after-close registry (debug builds)

Use after close and double close are caller-contract violations (07 CLS-10). Release builds behave as legacy: the behavior is undefined, and the shim adds no check and no overhead. Debug builds detect them:

- **Selection.** configure `--enable-debug` (configure.ac:92-94) or a CMake `Debug` build defines `ZKS_HANDLE_REGISTRY` for the shim. Without it, `ZKS_REGISTRY_ADD`, `ZKS_REGISTRY_REMOVE` and `ZKS_REGISTRY_CHECK` expand to nothing.
- **Registry.** `zks_handle.c` keeps a set of live `zhandle_t *` values under one process-wide mutex (`pthread_mutex_t` with `PTHREAD_MUTEX_INITIALIZER`; `SRWLOCK` on Windows). `zks_init_common` adds `zh` before any library thread can run (§15.1), and `zks_release` removes it before `free(zh)` (§15.4); the init failure path removes it if it was added. The mutex is a leaf. It is held only for the set operation, never around a Rust call or application code.
- **Check.** Every exported entry that takes a `zhandle_t *` runs `ZKS_REGISTRY_CHECK(zh, __func__)` where it first uses `zh`, after its legacy NULL checks (NUL-01..04), so the rcs for NULL handles are unchanged. A handle that is not in the set makes the shim write `zookeeper: <function>: zhandle_t <ptr> is closed or invalid (use after close or double close)` to stderr and call `abort()`. A second `zookeeper_close` after the release is reported in the same way. A close while a close is in progress is not a violation, because the handle is still live (CLS-05).
- **Limit.** An address that `calloc` reuses for a later handle is live again, so a stale pointer that happens to equal a new handle is not detected.

## 16. Logging

### 16.1 C-owned pieces (verbatim semantics)

- `ZooLogLevel logLevel = ZOO_LOG_LEVEL_INFO;` exported, default visibility, read with a plain load at every site, never cached.
- `log_message(cb, curLevel, line, func, fmt, ...)`: variadic, non-filtering, `vsnprintf`. Prefix mt `"%s:%ld(0x%lx):%s@%s@%d: "` (header 4095), st `"%s:%ld:%s@%s@%d: "` (header 4096); time `"%Y-%m-%d %H:%M:%S"` + `",%03d"` via `localtime_r`; pid cached on first use. On Windows the mt prefix thread id is `GetCurrentThreadId()`, the value legacy prints through winport's `pthread_self().thread_id` (zk_log.c:174, winport.c:66-71). Sink: `cb(buf)` without newline, or `fprintf(stream, "%s\n", buf); fflush`.
- `zoo_set_debug_level` (unsigned-enum clamping), `zoo_set_log_stream`, `zoo_get_log_stream` (NULL → stderr, lazily): verbatim.
- Buffers: `static ZKS_THREAD_LOCAL char zks_log_buf[4096]`, `zks_time_buf[1024]` in both flavors, with `ZKS_THREAD_LOCAL` (`__thread` on GCC/Clang, `__declspec(thread)` on MSVC, as zookeeper.c:5354-5358). This removes the TSD keys, the constructor and the dangling destructor after `dlclose`; st per-thread buffers remove a race (DSP-19). `curLevel` outside 0..4 prints `ZOO_INVALID` and the header length is clamped (LOG-09/10).

### 16.2 Core-originated lines: pinned Site table, emitted from the outbox

- `zookeeper-client-c/abi/log-sites.tsv` (08 owns the checks) is extracted from the C sources being replaced: `site_id | func | line | level | printf format | arg types`. It is pinned to the final commit of the legacy C core on main before the Rust core becomes the default, the same commit as the reference build of 08 §4.1. `cargo xtask log-sites` extracts the table from the C sources at the pinned commit and re-extracts it whenever the pin moves. Legacy C-core changes may move or change log sites freely: the table follows the pin, not the current C sources.
- `cargo xtask gen log-sites` generates the `LogSite` enum in zk-proto (`zk-proto/src/generated/log_sites.rs`, committed, no build script) and `shim/zks_log_sites.h` (`{func, line, fmt}`); `--check` is the drift check (CC-30 (00 §4)). zk-proto emits structured `LogRecord`s; zk-ffi stamps the time and copies them into the outbox. In the legacy profile only table sites may be emitted, which keeps idle handles silent at INFO and above.
- A `ZKRS_ITEM_LOG` view carries `level`, `site`, the record's `tv_sec`/`tv_usec`, and typed args (`I64 | U64 | PTR | STR | ENDPOINT | ERRNO_STR | GAI_STR`). STR args are NUL-terminated copies or NULL. A NULL STR arg prints `(null)` on every platform, for example the NULL CA path in "Failed to load CA file %s": the printf driver passes the literal `"(null)"` to `snprintf` in place of the NULL pointer. That is the glibc output; on a libc that crashed on a NULL `%s`, it is FU (07). The `zkrs.h` types are:

  ```c
  typedef struct zkrs_log_arg {
      uint32_t kind;                 /* ZKRS_ARG_* (§6) */
      int64_t i;                     /* I64 */
      uint64_t u;                    /* U64 */
      uintptr_t p;                   /* PTR */
      zkrs_str s;                    /* STR */
      zkrs_sockaddr_view ep;         /* ENDPOINT */
      int32_t gai_rc, err;           /* GAI_STR: both; ERRNO_STR: err */
  } zkrs_log_arg;
  typedef struct zkrs_log_view {
      uint32_t level, site;
      int64_t tv_sec; int32_t tv_usec;
      const zkrs_log_arg *args; uint32_t nargs;
  } zkrs_log_view;
  ```

- `zks_log_emit` checks `log_enabled` again at emit time (the macro rule, reading `logLevel`), builds the prefix from the record time, and renders the pinned format with `zks_printf_driver`: one `snprintf` per conversion spec with its single typed argument, so platform printf quirks match (`%p` of NULL, `%#llx`).
- `ENDPOINT` args are formatted through `zks_format_endpoint` into the calling thread's 134-byte buffer, reproducing the legacy overwrite of the `zoo_get_current_server` buffer by library logging (DAT-15, CC-13 (00 §4)).
- TLS lines are Rust sites with their legacy `func@line`: "Failed to load CA file %s", "Call to SSL_CTX_set_default_verify_paths failed", "Failed to load client certificate chain from %s", "Failed to load client private key from %s", "SSL_CTX_check_private_key failed", "error creating ssl context", "error in ssl connect" (06 §3).
- SASL lines that come before or bypass Cyrus are core (Rust) sites in `abi/log-sites.tsv`: "getsockname", "getpeername" (local and peer addresses come from rustix; zk-proto emits these lines from `handle_sasl_addr_failed`, 02 §7.4), "SASL intermediate server_data_len:%d" (logged before the GSSAPI last-packet check, zk_sasl.c:358-366) and "SASL authentication failed. rc=%d". The C glue does not log them.
- Delivery lines are core sites carried in the `delivery_logs` of RESULT and WATCH items and emitted by `zks_deliver_result` and `zks_deliver_watch` on the delivering thread, before the callbacks (§10, §11, 02 §5.1): "Calling COMPLETION_<KIND> for xid=%#x failed=%d rc=%d" for each kind, including the per-sub-op lines of a multi (zookeeper.c:3343-3441), "Processing sync_completion with type=%d xid=%#x rc=%d" (5425), "Calling a watcher for node [%s], type = %d event=%s" (3476) and the strip ERROR "server path %s does not include chroot path %s" (1733). C emits none of them on its own.
- Lines emitted directly by C with real varargs, through the same pinned `{func, line, fmt}` entries: `log_env` (8 INFO lines), "Initiating client connection…", the WSA failure line, the SASL glue lines that need Cyrus results (§19.2), the three thread-start DEBUG lines "starting threads...", "started IO thread" and "started completion thread" (§15.2), the two thread-terminated DEBUG lines (captured callback), and "New server list cannot be empty" and "Resolution delay cannot be %d" (checked before `zh` is used).

### 16.3 Per-handle callback

`zoo_get_log_callback(NULL)` returns NULL; `zoo_set_log_callback(NULL, …)` is a no-op; otherwise relaxed atomic load/store.

## 17. Accessors, server list and endpoints

```c
const char *zoo_get_current_server(zhandle_t *zh)
{
    zkrs_sockaddr_view v;
    if (zh == NULL) return "null";                   /* NUL-02: format_endpoint_info(NULL) literal */
    ZKS_REGISTRY_CHECK(zh, __func__);   /* debug builds only, §15.5 */
    ZKS_CALL(zkrs_current_server(zh->core, &v));     /* family 0 when no address: ":0" ("0.0.0.0:0" on
                                                        Windows, as the inet_ntoa path of zookeeper.c:5382-5388) */
    return zks_format_endpoint(&v);                  /* verbatim port of format_endpoint_info */
}

struct sockaddr *zookeeper_get_connected_host(zhandle_t *zh, struct sockaddr *addr, socklen_t *addr_len)
{
    zkrs_sockaddr_view v; struct sockaddr_storage ss; socklen_t n;
    if (zh == NULL || addr == NULL || addr_len == NULL) return NULL;
    ZKS_REGISTRY_CHECK(zh, __func__);   /* debug builds only, §15.5 */
    if (ZKS_CALL(zkrs_peer_addr(zh->core, &v)) != 0) return NULL;   /* state exactly 3 */
    n = zks_sockaddr_from_view(&v, &ss);                            /* sockaddr_in or _in6; its socklen_t */
    memcpy(addr, &ss, *addr_len < n ? *addr_len : n); *addr_len = n;
    return addr;
}
```

`"null"` is the literal that `format_endpoint_info(NULL)` returns (zookeeper.c:5367-5368); legacy crashes earlier in `lock_reconfig` (CC-13 (00 §4)). `zkrs_peer_addr` calls `getpeername` through `rustix::net` on the driver's shared socket, so the application thread never races a close of the fd.

`zoo_get_current_server` reports the core's `cur` (02 §9.2). After a failure it is the address at the `addrs` cursor, as in legacy: the address after the failed one when `addrs` chose the failed server, otherwise the unmoved cursor address, which can be the failed server (02 §9.2). The next connect chooses that server unless a reconfig is pending, in which case the report can differ from the server chosen, as in legacy. A SASL failure is such a failure, as on the digest path; legacy's SASL path leaves the report on the failed connection's server (DV-FIX-3). A callback that reads the report during T2-T4 of a teardown reads the server of the closed connection when it runs before T5: every st callback and an mt auth completion, as in legacy, where `cleanup` runs before `handle_error` advances. An mt completion-thread callback or a woken sync caller runs independently of the IO thread and reads that server or the next one. A forced close has no T5: the report stays on the closed connection's server, in its callbacks and until the next connect.

| Function | Shim | Notes |
|---|---|---|
| `zoo_state` | `zh ? zkrs_state(core) : 0` | can return 0, 7, 999 |
| `is_unrecoverable` | `zkrs_state(core) < 0 ? ZINVALIDSTATE : ZOK` | NULL → ZINVALIDSTATE (NUL-01..04) |
| `zoo_recv_timeout` | `zkrs_recv_timeout` | NULL → 0 |
| `zoo_client_id` | `&zh->client_id` | NULL → NULL |
| `zoo_get_context` / `zoo_set_context` | pair cell | get(NULL) → NULL; set(NULL) no-op |
| `zoo_set_watcher` | pair cell | NULL zh → NULL |
| `zoo_set_servers` | NULL hosts (checked first, also with a NULL zh) → the legacy ERROR "New server list cannot be empty" (zookeeper.c:1555) and ZBADARGUMENTS; NULL zh with non-NULL hosts → ZBADARGUMENTS (NUL-02); else `zkrs_set_servers`, driven by `zks_drive` through its log-flush barriers (§5) | D9 (below) |
| `zoo_set_servers_resolution_delay` | `< -1` → ZBADARGUMENTS with the legacy "Resolution delay cannot be %d" line; NULL zh → ZBADARGUMENTS | |
| `zoo_cycle_next_server` | pass-through; NULL zh → no-op | |
| `zoo_deterministic_conn_order` | C global read by `conn_permute_disabled` | |

**`zoo_set_servers` (D9, CC-23 (00 §4)).** A deterministic parse error (missing `:`, bad port, chroot suffix) returns ZBADARGUMENTS **without committing** the string. Otherwise the string is committed first and then resolved, as legacy: a resolution failure returns ZSYSTEMERROR with the new string committed, and later re-resolutions keep using it, so the client migrates once DNS recovers. A rebalance that requires a move is a forced close (`Compat::forced_close = Teardown`; DV-FIX-8, ST-13, §14): `zoo_state()` reads 999 when the call returns, no callback runs in the call, and only the IO owner closes the fd. The CONNECTING event, if the state was connected, and ZCONNECTIONLOSS for the work pending at the move follow on the IO owner, and none of that work is replayed on the next connection. A request submitted after the move stays queued and goes out on the next connection (02 §12.3). Same rcs, never ZINVALIDSTATE.

## 18. Exported data, jute/recordio, version, zerror

`zks_data.c` copies verbatim the `const int`, `struct Id`, `struct ACL` and `struct ACL_vector` definitions of zookeeper.c:107-201, leaving out the macros and the two static `*2String` functions and defining the `*_DEF` values it needs from zk_adaptor.h, plus `logLevel`: 30 `const int` (29 declared plus `ZOO_SSL_CONNECTING_STATE`), 5 writable structs (`ZOO_ANYONE_ID_UNSAFE`, `ZOO_AUTH_IDS`, the three `ACL_vector`s backed by writable static `ACL` arrays with literal-backed strings) and `logLevel`: 36 data exports, compiled by the C compiler so `STT_OBJECT`, `st_size`, section placement and copy relocations match.

- `recordio.c` and the generated `zookeeper.jute.{c,h}` ship verbatim (all `deallocate_*`/`allocate_*` semantics; `serialize_*`/`deserialize_*` only in the static archives). `deallocate_vector` and `format_log_message` stay undefined.
- `zerror` is verbatim, plus the case -15 → "ssl connection error" (DV-FIX-6, 07 RC-16); legacy has no case for ZSSLCONNECTIONERROR and returns "unknown error" (zookeeper.c:5227-5299). `c > 0` → `strerror(c)`. `zoo_version_str` returns `ZOO_VERSION`.
- Op-init helpers verbatim, with live `assert(op)` in autotools builds.
- `close_zsock` (static archives only): the legacy body (zookeeper.c:648-670) compiled without its `HAVE_OPENSSL_H` branch. It closes `sock` if it is not -1 and sets it to -1. It does not free `ssl_sock`/`ssl_ctx`: the library never creates them, and the library does not link OpenSSL (06 §3, DV-TLS-4). Only an application that fills a `zsock_t` with its own OpenSSL objects and passes it to this archive-only function sees a leak in place of a free; DV-TLS-4 covers this case. The installed header and the `zsock_t`/`zcert_t` layouts are unchanged.

## 19. SASL, `zoo_lock_auth` and the leaked internals

The SASL engine is Cyrus libsasl2, called from shim C (06 §4). The ZooKeeper-level protocol (framing, send gating, the deferred CONNECTED event, the DIGEST-MD5 and GSSAPI quirks, failure policy) lives in zk-proto's per-connection `SaslNegotiation`, reset at every prime response (SAS-04). The shim's job is to run Cyrus with the smallest possible surface for unsafe memory operations.

### 19.1 Isolation

- Exactly two translation units include `<sasl/sasl.h>`: `zks_sasl_cyrus.c` (the engine glue, the session accessors, the trampoline, `zookeeper_init_sasl`, the leaked `zoo_sasl_*`) and `zks_sasl_cb.c` (`zoo_sasl_make_basic_callbacks`, `zoo_sasl_make_password_callbacks`, their callback functions and the password mutex). They are always compiled with `HAVE_CYRUS_SASL_H` and define `ZKS_SASL_GLUE`; in every other shim TU, `zks_internal.h` undefines `HAVE_CYRUS_SASL_H` before it includes `zookeeper.h` (§2 preprocessor rules, 06 §4.7.1). No other shim file sees Cyrus types or depends on that macro.
- `zks_sasl.h` is the only interface between them and the rest of the shim. It contains no Cyrus types.
- `zks_sasl_cb.h` is the interface from `zks_sasl_cb.c` to `zks_sasl_cyrus.c` (`zks_is_library_pass_cb`, `zks_getsecret_unlocked` and the sink type, §19.5). It uses Cyrus types and is included only by the two SASL TUs; the CI depfile check enforces that.
- Both files are excluded from the build when SASL is disabled (`ZKS_WITH_SASL` unset). The golden subsets keep the symbol differences (`symbols.tsv` `feature` column).
- Linkage is unchanged: the shim links `-lsasl2`; DT_NEEDED and `.la` keep libsasl2.
- On Windows SASL stays optional, as in legacy: CMake builds the two SASL TUs only when it finds Cyrus (`WITH_CYRUS_SASL`, `find_package(CyrusSASL)`, CMakeLists.txt:66-79, 190-192). The glue's Windows forms are the `SRWLOCK` password mutex (§19.5) and the MSVC forms of the §19.7 macros. On `_WIN32`, `zks_sasl_cb.c` defines the static helper `static char *zks_getpassphrase(const char *prompt, char *buf, size_t cap)`, which writes the prompt to stderr and reads up to 1023 characters with `_getch()` (no echo) until CR or LF, into a caller-supplied buffer: the password function's local `char gp[ZSASL_MAX_PASSWORD_LEN + 2]`, which is zeroed on every exit path through `out`. It returns NULL on EOF. The helper has no static data, so G-S4 holds and concurrent prompts on different IO threads do not share a buffer. All other password rules are unchanged. CI tests Windows SASL on a best-effort basis; it is not an M4 gate (§23).

### 19.2 The item flow and the bounded interface

The core emits SASL work as `ZKRS_ITEM_SASL` items (proto `SaslCall { gen: u32, kind: Start | Step(Option<SecretBytes>) | Dispose }`, 02 §7.4, CC-17 (00 §4)). The local and peer addresses come from rustix `getsockname`/`getpeername` on the driver's own socket; no fd is lent to C (CC-31 (00 §4)). If either call fails, the driver builds no `START` item and calls `handle_sasl_addr_failed(gen: u32, which: SaslAddr{Local, Peer}, now)` (02 §7.4). zk-proto then logs the legacy "getsockname"/"getpeername" core site and fails the negotiation with ZSYSTEMERROR, as `zoo_sasl_connect` does (zk_sasl.c:219-237). A `START` item therefore always carries two addresses. zk-ffi writes each address into outbox storage as the platform `struct sockaddr_in` or `struct sockaddr_in6` image.

`zkrs.h` is owned by 05, including the numeric value of every constant (the listings of §5 and §6, the constants table of §6, and here `ZKRS_SASL_CONTINUE = 0`, `ZKRS_SASL_DONE = 1`, `ZKRS_SASL_FAIL = -1`, `ZKRS_SASL_STALE = -2`). 06 §4.7.2 owns the detailed glue rules, and this section mirrors its signatures exactly.

```c
/* zkrs.h (Rust-defined; c-shim owns the file, K3) */
typedef struct zkrs_bytes { const uint8_t *p; uint32_t len; uint8_t is_null; } zkrs_bytes;
typedef enum { ZKRS_SASL_START = 1, ZKRS_SASL_STEP, ZKRS_SASL_DISPOSE } zkrs_sasl_kind;
typedef struct zkrs_sasl_call {
    uint32_t kind;                        /* zkrs_sasl_kind */
    uint32_t gen;                         /* the session generation (never 0; wraps and skips 0) */
    zkrs_bytes local, peer;               /* START only: platform sockaddr image (sockaddr_in or
                                             sockaddr_in6), len = its size; else is_null */
    zkrs_bytes token;                     /* STEP only: server token; -1 -> is_null, p NULL, len 0 */
} zkrs_sasl_call;
typedef enum { ZKRS_SASL_CONTINUE = 0, ZKRS_SASL_DONE = 1, ZKRS_SASL_FAIL = -1,
               ZKRS_SASL_STALE = -2 } zkrs_sasl_status;   /* STALE: glue to drain only */
typedef struct zkrs_sasl_out {
    int32_t status;                       /* zkrs_sasl_status */
    zkrs_bytes mech;                      /* START: chosen mechanism; else is_null */
    zkrs_bytes token;                     /* Cyrus clientout; is_null when Cyrus gave NULL */
} zkrs_sasl_out;

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

Every glue call carries the generation, and the glue rejects a stale generation itself, before any Cyrus call:

- `zks_sasl_start` sets `ZKRS_SASL_STALE` when `gen == 0` or `gen == zks_sasl_gen(s)` (a START for a generation that already has a connection).
- `zks_sasl_step` sets `ZKRS_SASL_STALE` when `gen == 0` or `gen != zks_sasl_gen(s)`. With no connection the session generation is 0, so a STEP without a connection is stale.
- `zks_sasl_dispose` does nothing when `gen == 0` or `gen != zks_sasl_gen(s)`.

Because the check is in the glue, the libFuzzer harness drives it with fuzzed generations (§19.8). The drain records the generation of a new connection through `zks_sasl_set_gen` after START.

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

Rust emits no SASL item when `zkrs_init_args.sasl == 0`, and a library built without `ZKS_WITH_SASL` always passes `sasl = 0`. The no-SASL drain therefore needs no `ZKRS_ITEM_SASL` case.

Interface rules (06 §4.7.2):

- The server token view is built by Rust from the exact-length frame (WIRE-04, SAS-11: no over-read), so its `len` is already bounded. A -1 token becomes `is_null = 1, p = NULL, len = 0` and is passed to Cyrus as `(NULL, 0)` (SAS-10; legacy passed `(NULL, UINT_MAX)`).
- The address views are the platform `struct sockaddr_in` or `struct sockaddr_in6` image that zk-ffi writes into outbox storage from the driver's address, with `len` equal to the size of that structure. The drain passes them to the glue unchanged. `zks_sasl_start` validates the family and the length of each view before use, with the exact checks of 06 §4.7.2 and §4.7.5, and copies the bytes into a `struct sockaddr_storage`. A bad or null address view fails the START (FAIL) before `sasl_client_new`; any old connection has already been disposed, as `zoo_sasl_connect` disposes first (06 §4.7.2).
- The params come from the session's own copies through `zks_sasl_sess_params`, so the three glue functions are pure over their arguments; the fuzz harness passes its own params, addresses, tokens and generations (§19.8). The mechlist reaches `sasl_client_start` from `p->mechlist`, the session's NUL-terminated copy, which `zks_sasl_sess_new` built with `strnlen` capped at `ZKS_SASL_LEN_CAP` (§19.3). `p->mechlist` keeps legacy's NULL when the application gave none. There is no separate mechlist accessor.
- Cyrus output (`clientout`, `clientoutlen`, the chosen mechanism) is returned in `out` as views and copied into Rust at once by `zkrs_sasl_result(core, gen, view)`. C never keeps a pointer into a Cyrus output buffer past the next Cyrus call: each glue function resets `out` on entry.
- Lengths are `uint32_t`, matching Cyrus `unsigned`, and are checked against `ZKS_SASL_LEN_CAP` (0x7fffffff) before use. The chosen mechanism name must satisfy `strnlen(mech, SASL_MECHNAMEMAX + 1) <= SASL_MECHNAMEMAX` (06 §4.7.2). A value over its cap is a Cyrus error (`ZKRS_SASL_FAIL`). Every view is validated on entry (`len <= cap`, `is_null` implies `len == 0` and `p == NULL`, `len > 0` implies `p != NULL`); a bad view fails the call before any Cyrus call.
- `zkrs_sasl_result` is called at most once per START/STEP item, and never for a stale item or for DISPOSE. `ZKRS_SASL_STALE` never reaches Rust; Rust treats any status other than CONTINUE or DONE as FAIL. Rust emits START and STEP only for the live generation, so the glue's check guards against ordering bugs. If it fires, the barrier is resumed with no result. The negotiation stays unanswered, the receive timeout ends the connection, and zk-ffi then hands the core the stale result of §5, so the next connection's `DISPOSE` and `START` are not delayed. Rust also ignores a result whose `gen` is not the current one. The generation advances at teardown, so a late result for the old connection is always stale.

What each glue function does (zk_sasl.c:201-400):

- **`zks_sasl_start`**: checks `gen` (STALE as above); disposes an existing connection (as `zoo_sasl_connect` does first, zk_sasl.c:215-217), which sets the session generation to 0; the `DISPOSE` item that precedes every `START` after the first one (§19.3; 02 §7.4 yields it only when an earlier `START` created a session) normally leaves no connection here, so this dispose is a defensive step; validates the family and length of both address views; formats `"ip;port"` for both addresses with `getnameinfo(NI_NUMERICHOST | NI_NUMERICSERV)` (plus `NI_WITHSCOPEID` for `AF_INET6` where the platform defines it, as zk_sasl.c:176-181) and `snprintf(buf, NI_MAXHOST + NI_MAXSERV, "%s;%s", host, port)` with a truncation check (legacy used `sprintf`, zk_sasl.c:189), so the text is byte-identical to legacy, IPv6 scope suffixes included; on a `getnameinfo` error, logs "getnameinfo: %s\n" at the legacy site (zk_sasl.c:185) and fails; logs "Zookeeper Host: %s %s" at DEBUG; builds the per-session callback array (§19.5); calls `sasl_client_new(service, host ? host : numeric peer, local, remote, callbacks, 0, &conn)` and then `sasl_client_start(conn, p->mechlist, NULL, &out, &outlen, &mech)`. There is no `setprop` and no security layer (qop=auth); the library never calls `sasl_client_init`/`sasl_client_done` (SAS-01). A connection that START leaves, including after a failed `sasl_client_start`, gets its generation from the drain's `zks_sasl_set_gen`, so the later DISPOSE for that generation finds it.
- **`sasl_client_new` failure.** Cyrus can return an error with `*pconn` still allocated (its `RETURN_VAL` path in `lib/client.c`), and that connection then points at the per-session callback array. The glue logs "allocating SASL connection state: %s", then disposes `*pconn` when it is non-NULL (`if (s->conn != NULL) sasl_dispose(&s->conn);`) before it frees the callback array and the secret copies, and fails with ZSYSTEMERROR. Legacy (zk_sasl.c:250-264) leaves that connection in `sc->conn` until the next connect or destroy, where `sasl_dispose` frees it; the borrowed application array it points to stays valid, so legacy has no dangling pointer. The shim's array is its own, so the order matters here.
- **`zks_sasl_step`**: checks `gen` (STALE as above, which covers the no-connection case); checks the `challenge` view; calls `sasl_client_step(conn, in, inlen, NULL, &out, &outlen)`.
- **`zks_sasl_dispose`**: for the live generation only, calls `sasl_dispose` (Cyrus may use the secrets until here); then zeroes and frees the session's secret copies, frees its callback array, and sets the generation to 0. START's internal dispose, `zks_sasl_sess_clear` and `zks_sasl_sess_free` use the same body with no generation check.
- Status: `SASL_CONTINUE` → CONTINUE; `SASL_OK` → DONE; any other Cyrus code, a `getnameinfo` failure, a bad view or a cap violation → FAIL, which zk-proto turns into the SASL failure with ZSYSTEMERROR (legacy `_zsasl_fail(zh, ZSYSTEMERROR)`). A stale generation → STALE, with no Cyrus call. Every Cyrus return code is checked.
- C-emitted lines, with the legacy text and pinned `func@line`: "getnameinfo: %s\n", "Zookeeper Host: %s %s" (DEBUG), "allocating SASL connection state: %s", "Starting SASL negotiation: %s %s", "SASL start sr:%d mech:%s client_data_len:%d" (DEBUG), "During SASL negotiation: %s %s", "SASL intermediate sr:%d client_data_len:%d" (DEBUG). They are logged in the glue because they need `sasl_errstring`/`sasl_errdetail` on the live connection. The session keeps `zh` only for the log callback. "SASL authentication failed. rc=%d", the getsockname/getpeername failures and "SASL intermediate server_data_len:%d" are core (Rust) sites; the glue does not log them (§16.2).

The DIGEST-MD5 initial-response drop, the send rule (`!done || token non-empty`), the GSSAPI last-packet rule and the final-token reply (SAS-05) are zk-proto decisions on the copied result; the glue does not implement them.

### 19.3 Ownership, lifetime and generations

- `sasl_conn_t*` lives in the shim-owned per-handle `zks_sasl_sess`, with a generation that the drain records at START (`zks_sasl_set_gen`) and that matches the Rust `SaslCall.gen`. The glue rejects a stale generation itself, before any Cyrus call (§19.2).
- The Cyrus connection is disposed, and the pointer set to NULL, at the same two points as in legacy:
  - **Next START.** Legacy disposes the old connection at the top of `zoo_sasl_connect` (zk_sasl.c:215-217: `if (sc->conn) sasl_dispose(&sc->conn);`). zk-proto yields `Sasl(Dispose)` immediately before `Sasl(Start)`, so the two items are in the same outbox, in that order, and the drain runs them on the IO owner. The `DISPOSE` carries the generation of the connection it ends, and the `START` carries the new generation.
  - **Final close.** Legacy destroy closes the socket and then destroys the SASL client (zookeeper.c:719-749: `close_zsock` at 719-723, then `zoo_sasl_client_destroy` at 744-749). zk-proto yields `Sasl(Dispose)` after `CloseTransport` in the final close, before `ZKRS_RELEASE`. `zks_release` therefore normally finds no connection.
- A teardown (connection loss, receive timeout, forced close, SASL failure) yields no `DISPOSE`. The Cyrus connection stays in the session until the next `START` or the final close, as in legacy; after a SASL failure no `START` follows, so the final close disposes it. A Cyrus callback (a `SASL_CB_LOG`, for example) runs at the same point as in legacy (07 SAS-13). The generation still advances at teardown, so a late result for the old session is rejected as stale (§19.2).
- `zks_sasl_sess_new` copies `service`, `host` and `mechlist` from `zoo_sasl_params_t` with `strdup` semantics, as legacy (zk_sasl.c:90-114). NULL fields stay NULL. A string at or above `ZKS_SASL_LEN_CAP` fails init with ENOMEM (failure path only). `zks_sasl_sess_free` disposes any connection, frees the owned copies and frees the session object. `zks_sasl_sess_clear` does the same but keeps the object, for `zoo_sasl_client_destroy` (§19.10).
- The application's `sasl_callback_t*` array stays borrowed and must outlive the handle, as legacy (zookeeper.h:629-631). It is read again at each `START`, which is when legacy passes it to `sasl_client_new`. The params are not validated at init; failures surface after the prime response as AUTH_FAILED (INIT-15).

### 19.4 Serialization

- Per handle, at most one SASL START/STEP item is in flight. Rust emits the next one only after `zkrs_sasl_result` for the previous one and the resume.
- SASL items are drained only on the IO owner: the mt IO thread, the st caller, or, for the DISPOSE of the final close, the thread that runs the final close after the IO thread is joined or has exited. mt `zookeeper_interest`/`zookeeper_process` do not drive the connection (DSP-18), so no application thread can run Cyrus.
- The reconnect `DISPOSE` is drained on the IO owner right before its `START`. `DISPOSE` from close goes through the same outbox ordering. In an mt close from a foreign or dispatch thread, the final close (and its `DISPOSE`) runs only after the IO thread has been joined, so it is never concurrent with a `START` or `STEP`.
- This removes the cross-thread dispose race without a session lock in C.

### 19.5 Secret trampoline (DAT-13, FU)

The application-visible block from `zoo_sasl_make_*_callbacks` keeps its exact layout and the single-`free()` contract (§19.6). The race fix is in what the shim gives Cyrus:

- At `START`, the glue builds a **per-session callback array**: a copy of the application's array up to and including `SASL_CB_LIST_END`, sized with `ZKS_MUL_OVF` (§19.7). An application array of 4096 or more entries fails the START with `SASL_NOMEM` (07 SAS-14). In the copy, only the first `SASL_CB_PASS` entry (the one Cyrus uses) is changed: its `proc` becomes `zks_sasl_pass_trampoline` and its `context` becomes the session's `zks_pass_ctx` record, which keeps the original `proc` and `context`. Other callback ids pass through unchanged, with their original contexts. A NULL application array is passed to Cyrus as NULL, as legacy.
- The trampoline gets the secret and stores it in a **per-session sink** that it owns (`zks_pass_store`): a new zero-filled buffer of at least `sizeof(sasl_secret_t)` that holds `len` bytes plus a NUL, bounded by the secret's `len`, and by the 1023-byte legacy cap for the library's own callback. It returns that buffer to Cyrus, which uses it for the life of that `sasl_conn_t`. A later secret on the same connection gets a new buffer; the old one is kept until dispose, because Cyrus may still hold it. At most 16 are kept per connection, and a seventeenth secret request returns `SASL_NOMEM` (07 SAS-14). Legacy has neither this limit nor the array limit. Both are unreachable with the shipped mechanisms and bound hostile or corrupted input. Dispose zeroes (`zks_secure_zero`) and frees every buffer.
- **Library callback.** When the original `proc` is the library's own password callback (`zks_is_library_pass_cb`), the trampoline calls `zks_getsecret_unlocked` with its per-session sink. That function runs the legacy password rules (file read, the application's transform callback, `getpass`) with no ZooKeeper lock held, and writes the password only into the sink. It does not write the shared in-block secret, so ZooKeeper sessions never touch the shared buffer.
- **Direct Cyrus users.** When an application passes a library-made block to its own Cyrus connection, Cyrus calls `_zsasl_getsecret` directly, without the trampoline. `_zsasl_getsecret` computes the password with the same legacy rules (`zks_getsecret_compute`, no lock held) and then takes the process-wide `zks_sasl_pass_mutex` only around its short in-block copy (`memcpy`, trailing `memset`, `len` store). No callback runs inside that copy. Every write to a shared in-block secret buffer is therefore serialized, so the formal data race of DAT-13 is removed. Cyrus reads the in-block buffer after `_zsasl_getsecret` returns, as in legacy, so the header's sharing contract for rotating credentials (zookeeper.h:695-697) still applies to these direct users.
- **Application-written callbacks** are called with no lock held. Their secret goes to the per-session sink, so Cyrus never reads a buffer that another session can rewrite.
- **No lock spans application code.** The password mutex is held only across the in-block copy, and no other ZooKeeper lock is held while any password callback runs (K2, DSP-13). An exception or `longjmp` from the transform callback or `getpass` therefore cannot leave a lock held, and unwinding needs no cleanup handler to release one. A handle that waits at a terminal prompt delays no other handle.
- No correct application can depend on the shared buffer, so this is FIX-UB-ONLY (DAT-13, CC-25 (00 §4)).

`zks_sasl_cb.h` is the only path from `zks_sasl_cyrus.c` into `zks_sasl_cb.c`. The mutex is private to `zks_sasl_cb.c`:

```c
/* shim/zks_sasl_cb.h -- Cyrus types; included only by zks_sasl_cyrus.c and zks_sasl_cb.c */
#define ZSASL_MAX_PASSWORD_LEN 1023
/* Receives the computed password (len <= 1023, then a NUL). Runs no application code. */
typedef int (*zks_secret_sink)(void *arg, const unsigned char *p, size_t len, sasl_secret_t **out);
int zks_is_library_pass_cb(sasl_callback_ft proc);    /* proc == &_zsasl_getsecret */
/* The legacy password rules for the block context ctx. Takes no lock and writes no shared
 * memory: the password goes only to sink. Calls application code (transform callback, getpass). */
int zks_getsecret_unlocked(sasl_conn_t *conn, void *ctx, zks_secret_sink sink,
                           void *sink_arg, sasl_secret_t **psecret);
```

```c
/* shim/zks_sasl_cyrus.c: Cyrus calls this for SASL_CB_PASS on a shim-made connection. */
typedef int (*zks_pass_fn)(sasl_conn_t *, void *, int, sasl_secret_t **);

static int zks_sasl_pass_trampoline(sasl_conn_t *conn, void *context, int id, sasl_secret_t **psecret)
{
    struct zks_pass_ctx *pc = context;                                /* one per session */
    sasl_secret_t *got = NULL;
    int rc;
    if (pc == NULL || psecret == NULL || id != SASL_CB_PASS) return SASL_BADPARAM;
    *psecret = NULL;
    if (pc->is_library)                       /* library rules; no lock; bytes go to this session's sink */
        return zks_getsecret_unlocked(conn, pc->orig_ctx, zks_pass_store, pc, psecret);
    rc = ((zks_pass_fn)pc->orig_proc)(conn, pc->orig_ctx, id, &got);   /* application code; no lock held */
    if (rc != SASL_OK) return rc;
    if (got == NULL) return SASL_FAIL;
    if (got->len > ZKS_SASL_LEN_CAP) return SASL_BUFOVER;
    return zks_pass_store(pc, (const unsigned char *)got + offsetof(sasl_secret_t, data),
                          (size_t)got->len, psecret);                 /* bounded copy to session memory */
}
```

`zks_pass_store` is the per-session sink (a `zks_secret_sink`). It returns `SASL_BUFOVER` for a length over `ZKS_SASL_LEN_CAP`, `SASL_NOMEM` when the session already holds 16 copies or the allocation fails (failure path only; it becomes the SASL failure), and otherwise `SASL_OK` with `*out` set to the new buffer. The application's own `sasl_secret_t` is not touched or freed by the library, as in legacy.

`zks_sasl_pass_mutex` is a `pthread_mutex_t` initialized statically with `PTHREAD_MUTEX_INITIALIZER`. Its critical section contains no callback and no call that can block on another lock, so a same-thread relock cannot happen and no error-checking type is needed. On Windows it is an `SRWLOCK` initialized with `SRWLOCK_INIT`. In st, Rust std already brings libpthread into the link (01 §4.6), so it adds no dependency. The mutex is never held across a Cyrus call, a Rust call or application code.

### 19.6 Callback helpers (`zks_sasl_cb.c`)

`zoo_sasl_make_basic_callbacks` and `zoo_sasl_make_password_callbacks` keep the legacy block (zk_sasl.c:440-666):

- one `calloc` block, with `callbacks[5]` at offset 0 in the order GETREALM, USER, AUTHNAME, PASS, END; then the secret context, the in-block secret (`sizeof(sasl_secret_t) + 1023`), and the strings; freed with one `free()`;
- the block size is computed with `ZKS_ADD_OVF` (§19.7); string lengths with `strnlen(s, ZKS_SASL_LEN_CAP)`; overflow or a string at the cap returns NULL with ENOMEM;
- `zoo_sasl_make_password_callbacks(…, NULL)` returns NULL with `errno = EINVAL` instead of dereferencing NULL (NUL-06/SEC-13).

The password callback keeps its rules byte for byte in `zks_getsecret_compute` (legacy `_zsasl_getsecret`, zk_sasl.c:486-601), which both `_zsasl_getsecret` and `zks_getsecret_unlocked` call: the `conn`/`psecret` check (and, in `_zsasl_getsecret`, the `id` check; the trampoline checks `id` itself) returns `SASL_BADPARAM`; the file is re-read on each call (`fopen("rt")`, `fread` of at most 1023 bytes, `ferror` → `SASL_FAIL`); without a transform callback the first line is used; with a callback the file is required (`SASL_BADPARAM` otherwise) and a returned length over 1023 gives `SASL_BUFOVER`; without a file, `getpassphrase("Password: ")` (or `getpass`), NULL → `SASL_FAIL` (on `_WIN32`, `zks_getpassphrase("Password: ", gp, sizeof gp)`, §19.1); NUL truncates (`strlen`); a length over 1023 gives `SASL_BUFOVER` after the source buffer is zeroed; `getpass` runs on the IO owner. `zks_getsecret_unlocked` gives the result to the caller's sink. The `SASL_CB_PASS` proc stored in the block is `_zsasl_getsecret`, which writes the in-block secret under the password mutex. The code is owned by 06 §4.7.7 (which also gives `zks_getsecret_compute`); its lock and entry points are:

```c
/* shim/zks_sasl_cb.c */
#ifdef _WIN32
static SRWLOCK zks_sasl_pass_mutex = SRWLOCK_INIT;
static void zks_sasl_pass_lock(void)   { AcquireSRWLockExclusive(&zks_sasl_pass_mutex); }
static void zks_sasl_pass_unlock(void) { ReleaseSRWLockExclusive(&zks_sasl_pass_mutex); }
#else
static pthread_mutex_t zks_sasl_pass_mutex = PTHREAD_MUTEX_INITIALIZER;   /* the only global mutable state */
static void zks_sasl_pass_lock(void)   { (void)pthread_mutex_lock(&zks_sasl_pass_mutex); }
static void zks_sasl_pass_unlock(void) { (void)pthread_mutex_unlock(&zks_sasl_pass_mutex); }
#endif

/* SASL_CB_PASS proc stored in the block: legacy behavior for direct Cyrus users of the block.
 * The mutex covers only the in-block copy. No callback runs while it is held. */
/* ZKS_CLEANUP (zks_sasl_cb.h, §19.7) zeroes pw also during unwind on GCC and Clang and is empty on
 * MSVC. Every return taken after pw may hold secret bytes goes through out:, which zeroes pw;
 * the only earlier return is the argument check, before pw is written (06 §4.7.7). */
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

`zks_getsecret_compute`, `_zsasl_getsecret` and `zks_getsecret_unlocked` keep the password in stack buffers that are zeroed in full with `zks_secure_zero` on every exit, where legacy zeroes only the first `strlen` bytes; that is invisible hardening. Every return taken after a buffer may hold secret bytes zeroes the buffers explicitly (through the `out:` label in `zks_getsecret_compute` and `_zsasl_getsecret`), which is the whole MSVC form; the only earlier returns are the argument checks, before any buffer is written (06 §4.7.7). On GCC and Clang, `ZKS_CLEANUP(zks_zero_pw)` also zeroes them when a C++ exception unwinds through the frame. The cleanup handler releases no lock, because none is held there. (A `longjmp` out of the transform callback skips cleanup handlers, as it skips C's own code.)

### 19.7 Coding and build rules for both SASL files

Coding rules; the CI gates G-S1 to G-S6 (08 §6.1) check them where a mechanical check exists:

- no `strcpy`/`strcat`/`sprintf`/`vsprintf`/`gets`/`strtok`;
- `snprintf` with truncation checks for the `"ip;port"` strings;
- `ZKS_ADD_OVF`/`ZKS_MUL_OVF` for every size computation (callback-block layout, per-session array, secret copies). These portable macros are defined in `shim/zks_sasl_cb.h`: they use `__builtin_add_overflow`/`__builtin_mul_overflow` on GCC and Clang and explicit range checks on MSVC;
- `ZKS_CLEANUP` for cleanup, also defined in `shim/zks_sasl_cb.h`: `__attribute__((cleanup))` on GCC and Clang, and explicit `goto out` cleanup on MSVC. The glue is written so that both forms are correct: every exit passes through the `out:` cleanup;
- `strnlen` with explicit caps;
- `zks_secure_zero` (`explicit_bzero`/`memset_s`/`SecureZeroMemory`, volatile fallback) on every secret copy before free;
- every Cyrus return code checked;
- no global mutable state except `zks_sasl_pass_mutex` (statically initialized, in `zks_sasl_cb.c`);
- no lock held across application code or a Cyrus call.

Build hardening for these TUs (every other shim TU gets the same set without `-Wconversion -Wsign-conversion`, 01 §4.9): `-D_FORTIFY_SOURCE=3 -fstack-protector-strong -fstack-clash-protection -ftrivial-auto-var-init=zero -fno-strict-overflow -Wformat=2 -Wformat-security -Wconversion -Wsign-conversion -Werror`, plus `-fcf-protection` or `-mbranch-protection=standard` where supported. Cyrus headers are included as system headers (`-isystem`), so the warning flags apply only to shim code. configure and CMake probe each hardening flag and drop one that the compiler or C library does not support, with a notice for each dropped flag, in every build including releases (`_FORTIFY_SOURCE=3` falls back to 2, for example on glibc 2.28; 01 §4.9). `-Werror` is on by default and in CI. Packagers turn it off with configure `--disable-werror` or CMake `ZK_WERROR=OFF`. The linked library carries no IBT/SHSTK or BTI property note, because the Rust objects carry none, even where these TUs are compiled with `-fcf-protection` or `-mbranch-protection` (DV-BLD-8).

### 19.8 SASL verification

- `fuzz/sasl_glue`: a libFuzzer harness drives `zks_sasl_start`/`zks_sasl_step`/`zks_sasl_dispose` directly with fuzzed server tokens, address views, params and generations (06 §4.7.11). It fuzzes the mechlist through the params: `zks_sasl_test_new` takes a mechlist argument, which reaches `sasl_client_start` as `p->mechlist`. The harness runs against Cyrus's in-process PLAIN and DIGEST-MD5 plugins and a fake password callback, under ASan and UBSan. Generations and dispose ordering are part of the fuzzed input, so the glue's own stale-generation check (`ZKRS_SASL_STALE`, no Cyrus call) is covered.
- The SASL conformance tests (TestSASLAuth ports, DIGEST-MD5 reconnect, password-file cases) run under ASan, UBSan and Valgrind memcheck, and MSan where Cyrus can be instrumented.
- Clang static analyzer, `gcc -fanalyzer` and CodeQL run on `shim/` in CI.
- A unit test checks the callback-block layout with `offsetof` and the single-`free()` release under LSan.
- A concurrency test shares one library-made block across handles that reconnect concurrently, under TSan, and checks that no handle reads a secret another handle is writing.
- A unit test forces `sasl_client_new` to fail with `*pconn` set (Cyrus interposer) and checks under ASan that the connection is disposed before the callback array is freed.
- A C++ test throws from a transform callback and checks that `zks_sasl_pass_mutex` is free afterwards and, on GCC and Clang builds, that the password stack buffers were zeroed (08 SU-09).
- Unit tests check the two SAS-14 limits: a callback array of 4096 entries fails START with `SASL_NOMEM`, and a seventeenth secret request on one connection returns `SASL_NOMEM`.
- A test blocks one handle's library password callback (a transform callback that waits on a condition) and checks that a second handle sharing the block completes its password fetch and its negotiation meanwhile.

### 19.9 `zoo_lock_auth` parity (CC-7 (00 §4))

mt `zoo_lock_auth`/`zoo_unlock_auth` lock/unlock `zh->auth_lock` and return the pthread rc (on Windows, the rc of winport's mutex functions, §3); st returns 0. Legacy takes this lock on the IO and closing threads while collecting auth completions (zookeeper.c:2062, 2160) and while queueing resent auth packets (2216). The core emits a `ZKRS_ITEM_AUTH_LOCKPOINT` barrier **before** the state change and completion collection at each legacy site: AUTH reply, teardown collection, close collection, and auth resend. C handles it in the drain with no Rust frame and no Rust lock, then resumes:

```c
static void zks_auth_lockpoint(zhandle_t *zh)
{
    zoo_lock_auth(zh);       /* exported, as zookeeper.c:2062/2160/2216; mt: waits while the
                                application holds it; st: returns 0 */
    zoo_unlock_auth(zh);
}
```

An application holding the lock therefore stalls IO-side auth processing exactly as legacy, and LA-01 is `[diff]` parity. Lock order stays `auth_lock → core` (shim `zoo_add_auth`), and the lockpoint takes `auth_lock` alone with no Rust lock held, so no inversion exists, including at the teardown site. The shim initializes `zh->auth_lock` exactly as legacy initializes its auth lock (`pthread_mutex_init(&zh->auth_h.lock, 0)`, default attributes, mt_adaptor.c:253); the shim's call is `pthread_mutex_init(&zh->auth_lock, 0)`. A same-thread relock (for example, `zoo_add_auth` called while the caller holds `zoo_lock_auth`, or a lockpoint on a thread whose callback returned with the lock held) behaves as in legacy, which is undefined for a default mutex. On Windows the lock is a `CreateMutex` HANDLE, as winport.c:37-44, which is recursive, so the relock succeeds there, as in legacy (§3). The shim adds no diagnostic, so this is not a deviation.

### 19.10 Leaked internals

| Symbol | Behavior |
|---|---|
| `zoo_lock_auth`, `zoo_unlock_auth` | §19.9 |
| `zoo_sasl_client_create`, `zoo_sasl_client_destroy` | real (ABI-12, RE): create calls `zks_sasl_sess_new` (copies service/host/mechlist, borrows callbacks, not attached to a handle) and returns NULL on allocation failure; destroy calls `zks_sasl_sess_clear` (disposes any connection, frees the owned copies) but does not free the object itself, which the caller frees, as legacy (zk_sasl.c:90-133). The object is opaque. |
| `zoo_sasl_mark_failed(zh)` | faithful: `zkrs_sasl_mark_failed(core)` marks the negotiation failed and sets AUTH_FAILED: state only, no event and no teardown, as legacy (zk_sasl.c:135-141; 02 §7.4) |
| `zoo_sasl_connect`, `zoo_sasl_client_start`, `zoo_sasl_client_step` | stubs returning ZSYSTEMERROR (**DV-ABI-2**): they drive negotiation from an application thread on state the IO owner holds exclusively |
| `zoo_create2_op_init`, `ZOO_SSL_CONNECTING_STATE` | verbatim |
| `zoo_htonll` (recordio.h:74), `zoo_get_log_stream` (zookeeper_log.h:44): declared without `ZOOAPI` | verbatim |
| `close_zsock` | §18 |

The six `zoo_sasl_*` internals live in `zks_sasl_cyrus.c`, so the SASL symbol subset comes only from the two SASL TUs.

## 20. Symbol mapping

### 20.1 Delegation graph (mirrors C for these edges; generates the AR-06 expected hit counts)

| Exported function | Calls (exported, through the PLT) | Static helper |
|---|---|---|
| `zoo_aget` | `zoo_awget` | — |
| `zoo_agetconfig` | `zoo_awgetconfig` | — |
| `zoo_aexists` | `zoo_awexists` | — |
| `zoo_aget_children`, `zoo_awget_children` | — | `zks_awget_children_` |
| `zoo_aget_children2`, `zoo_awget_children2` | — | `zks_awget_children2_` |
| `zoo_acreate` / `zoo_acreate2` | `zoo_acreate_ttl` / `zoo_acreate2_ttl` | — |
| `zoo_aremove_watches`, `zoo_aremove_all_watches` | — | `zks_aremove_watches` |
| `zoo_create` → `zoo_create_ttl` | `zoo_create_ttl`; `zoo_acreate_ttl` | — |
| `zoo_create2` → `zoo_create2_ttl` | `zoo_create2_ttl`; `zoo_acreate2_ttl` | — |
| `zoo_delete` | `zoo_adelete` | — |
| `zoo_exists` → `zoo_wexists` | `zoo_wexists`; `zoo_awexists` | — |
| `zoo_get` → `zoo_wget` | `zoo_wget`; `zoo_awget` | — |
| `zoo_getconfig`, `zoo_wgetconfig` (DV-FIX-5; the one edge that differs from legacy, and AR-06 takes its count from this table) | `zoo_awgetconfig` | `zks_wgetconfig_` |
| `zoo_reconfig` | `zoo_areconfig` | — |
| `zoo_set` → `zoo_set2` | `zoo_set2`; `zoo_aset` | — |
| `zoo_get_children`, `zoo_wget_children` | `zoo_awget_children` | `zks_wget_children_` |
| `zoo_get_children2`, `zoo_wget_children2` | `zoo_awget_children2` | `zks_wget_children2_` |
| `zoo_get_acl` / `zoo_set_acl` / `zoo_multi` | `zoo_aget_acl` / `zoo_aset_acl` / `zoo_amulti` | — |
| `zoo_remove_watches`, `zoo_remove_all_watches` | — | `zks_remove_watches` → `zks_aremove_watches` |
| `zookeeper_init_ssl`, `zookeeper_init_sasl` | — | `zks_init_common` |
| `zoo_add_auth` | `zoo_lock_auth`, `zoo_unlock_auth` (one window, §12.1) | — |
| (library threads) auth lockpoints | `zoo_lock_auth`, `zoo_unlock_auth` (§19.9) | — |

The expected hit counts of AR-06 (08 §3.6) are generated by running the interposer against the reference build, not written by hand, and they give identical call counts for the edges of this table, except the sync getconfig edge of DV-FIX-5, whose count comes from this table. The `zoo_lock_auth`/`zoo_unlock_auth` counts are not part of them: these call counts are not an ABI contract, and AR-06 (08 §3.6) excludes them. The shim takes the lock at the AUTH_LOCKPOINT sites (CC-7 (00 §4)) and once in `zoo_add_auth`. AR-06 also excludes the library-internal calls of rule 5 (§1) to `is_unrecoverable`, `zoo_get_log_callback`, `zoo_get_current_server`, `zoo_state`, `zookeeper_interest`, `zookeeper_process`, `zoo_cycle_next_server`, `zoo_sasl_connect`, `zoo_sasl_client_start`, `zoo_sasl_client_step`, `zoo_sasl_mark_failed`, `zoo_sasl_client_create`, `zoo_sasl_client_destroy`, `zookeeper_close`, `deallocate_Buffer`, `log_message` and `zoo_get_log_stream` (for core-originated lines), `zoo_htonll` and the generated record deallocators, which the shim does not reproduce.

### 20.2 Groups (mt 175 functions + 36 data = 211; st 152 + 36 = 188, with SSL and SASL)

| Group | Count (mt / st) | Symbols | File | Deviations |
|---|---|---|---|---|
| Const data | 30 / 30 | `ZOOKEEPER_READ/WRITE`, 7 create modes, `ZOO_SEQUENCE`, 8 states incl. `ZOO_SSL_CONNECTING_STATE`, 6 events, 6 perms | `zks_data.c` | — |
| Writable data | 6 / 6 | `ZOO_ANYONE_ID_UNSAFE`, `ZOO_AUTH_IDS`, 3 `ACL_vector`, `logLevel` | `zks_data.c` | — |
| Init | 4 / 4 | `zookeeper_init`, `_init2`, `_init_ssl`†, `_init_sasl`‡ | `zks_handle.c`, `zks_sasl_cyrus.c` | INIT-12, INIT-13, INIT-14 |
| Close | 1 / 1 | `zookeeper_close` | `zks_handle.c` | CLS-05, CLS-11 |
| Event loop | 2 / 2 | `zookeeper_interest`, `zookeeper_process` | `zks_handle.c` | DSP-18 (mt), SRV-06 |
| State accessors | 5 / 5 | `zoo_state`, `zoo_recv_timeout`, `zoo_client_id`, `zoo_get_context`, `zoo_set_context` | `zks_handle.c` | NUL-01..04 |
| Watcher/log cells | 3 / 3 | `zoo_set_watcher`, `zoo_get_log_callback`, `zoo_set_log_callback` | `zks_handle.c` | NUL-01..04 |
| Server list | 6 / 6 | `zoo_set_servers`, `_resolution_delay`, `zoo_cycle_next_server`, `zoo_get_current_server`, `zookeeper_get_connected_host`, `zoo_deterministic_conn_order` | `zks_handle.c`, `zks_resolve.c`, `zks_rng.c` | NUL-01..04, D9 |
| Async, watch-capable | 10 / 10 | `zoo_aget`/`awget`, `agetconfig`/`awgetconfig`, `aexists`/`awexists`, `aget_children`/`awget_children`, `aget_children2`/`awget_children2` | `zks_async.c` | NUL-01, NUL-05, PTH-01 |
| Async create | 4 / 4 | `zoo_acreate`, `_ttl`, `zoo_acreate2`, `_ttl` | `zks_async.c` | NUL-05, DAT-09/10 |
| Async other | 6 / 6 | `zoo_adelete`, `zoo_aset`, `zoo_async`, `zoo_aget_acl`, `zoo_aset_acl`, `zoo_areconfig` | `zks_async.c` | NUL-05, DAT-09/10 |
| Multi | 6 / 6 | `zoo_amulti` + 5 `zoo_*_op_init` | `zks_multi.c` | NUL-01, NUL-05, MUL-08, DAT-09/10 |
| Auth | 1 / 1 | `zoo_add_auth` | `zks_async.c` | DSP-13, GLB-04 |
| Remove watches, async | 2 / 2 | `zoo_aremove_watches`, `zoo_aremove_all_watches` | `zks_async.c` | WAT-13 |
| Sync | 23 / 0 | the 23 sync functions | `zks_sync.c` | DAT-05/06/07, MUL-06, DSP-04 |
| Logging | 4 / 4 | `log_message`, `zoo_set_debug_level`, `zoo_set_log_stream`, `zoo_get_log_stream` | `zks_log.c` | LOG-09/10, DSP-19 |
| Misc | 3 / 3 | `zerror`, `zoo_version_str`, `is_unrecoverable` | `zks_errors.c` | NUL-01..04 |
| SASL helpers | 2 / 2 ‡ | `zoo_sasl_make_basic_callbacks`, `_password_callbacks` | `zks_sasl_cb.c` | NUL-06, DAT-13 |
| Leaked internals | 9 / 9 | `zoo_lock_auth`, `zoo_unlock_auth`, `zoo_sasl_client_create/destroy/start/step`‡, `zoo_sasl_connect`‡, `zoo_sasl_mark_failed`‡, `zoo_htonll` (declared in recordio.h:74 without ZOOAPI) | `zks_compat.c`, `zks_sasl_cyrus.c`, `recordio.c` | DV-ABI-2 |
| Jute memory | 84 / 84 | 79 `deallocate_*` (String, Buffer, 72 records, 5 vectors) + 5 `allocate_*` | generated, `recordio.c` | — |
| Static archive only | — | `close_zsock`, `serialize_*`/`deserialize_*`, recordio archive functions; `oa_*`/`ia_*` (`static-symbols.tsv`) | `zks_compat.c`, `recordio.c`, generated | DV-TLS-4 (`close_zsock`) |
| Never defined | — | `deallocate_vector`, `format_log_message`; `zoo_remove_all_watches` in st | — | — |

Non-jute functions: 91 (mt) / 68 (st). † only in TLS builds (−1 otherwise): the legacy `--with-openssl` / `WITH_OPENSSL` autodetect found `openssl/ssl.h` and `-lssl SSL_CTX_new` (the probe only decides; OpenSSL is never linked, DV-TLS-4), or the opt-in `--with-rust-tls=yes` / `WITH_RUST_TLS` is set. ‡ only with Cyrus SASL (−9 in all: `zookeeper_init_sasl`, the 2 helpers and 6 leaked). Totals: 211/188 with both, 210/187 with SASL only, 202/179 with SSL only, 201/178 with neither. `cargo xtask abi check|static` diffs the built libraries against the golden lists and checks that no `zkrs_`, `zks_`, Rust-mangled or crypto-provider symbol is global (the MSVC `zookeeper.lib` is checked by 08 S3w, DV-BLD-3).

## 21. Deviation register (shim/boundary-owned)

IDs are the ledger's (07 §4; the registers are listed in 00 §5); the shim's local aliases are in parentheses. Every FU row removes UB, a crash, memory corruption or a deadlock that no correct application can depend on, except SAS-14, which is FU-hardening: it bounds input that the shipped mechanisms never produce, and WIN-07, which removes a build failure. Everything else matches legacy, apart from the registered behavior fixes DV-FIX-1..9 (07 §5.1; §23 of this document): the shim implements DV-FIX-5 and DV-FIX-6 (§13, §18), and zk-proto implements the others.

| ID (alias) | Class | Legacy | New | Justification |
|---|---|---|---|---|
| NUL-01..04, PTH-01, MUL-08 (D1) | FU | NULL `zh` crashes in `zoo_aget`/`agetconfig`/`aexists`/`aget_children[2]`, the int-watch sync wrappers, `zoo_amulti`, `zoo_set_servers`, `zoo_cycle_next_server`, `zoo_set_servers_resolution_delay`, `zoo_set_watcher`, `zoo_get_context`, `zoo_recv_timeout`, `is_unrecoverable`, `zookeeper_get_connected_host`, `zoo_get_current_server`; `zoo_client_id(NULL)` returns an invalid pointer; NULL path with a chroot; `zoo_amulti` with NULL `ops`/`results` and `count > 0` | ZBADARGUMENTS for rc-returning functions (multi: before the call-time writes); accessors return NULL, `"null"`, 0, ZINVALIDSTATE or no-op (§17) | crash |
| WAT-13 (D2) | FU | mt `zoo_aremove_watches(local=1)` writes through the caller's `data` as a sync record (zookeeper.c:5021-5029) | async: `data` never touched, ZOK, no completion (the st behavior). Sync wrappers still complete their own record | memory corruption |
| NUL-05 (D3) | FU | NULL completion function crashes or asserts at reply or teardown | skipped at delivery; submission unchanged | crash |
| DAT-09/10 (D4) | FU | single ops and multi sub-ops: value `len < -1`, or NULL with `len > 0`, and ACL `count > 0` with `data == NULL` crash in serialization; a NULL ACL vector in `zoo_aset_acl`/`zoo_set_acl` crashes when the vector is copied (zookeeper.c:4772) | ZMARSHALLINGERROR at the serialization stage, after the state check; nothing sent (CC-12 (00 §4)) | crash |
| DAT-05/06/07 (D5) | FU | `zoo_wget`/`zoo_reconfig` with `*buffer_len < -1` (on ZOK), NULL `buffer`/`path_buffer` with positive length, NULL stat in `zoo_[w]get_children2` crash | the request is submitted as in legacy; on ZOK the impossible copy is skipped and `*buffer_len` is written by the legacy formula; NULL stat tolerated | crash |
| MUL-06 (D6) | FU | sync `zoo_multi` on a bodyless header error asserts | rc = header err; results as async | crash |
| DSP-04 (D7) | FU (00 §5.2) | sync API on the mt IO thread deadlocks forever | ZSYSTEMERROR, `errno = EDEADLK`, nothing sent | deadlock |
| DSP-13 (D8) | FU | auth completions on teardown/close and log callbacks run under non-recursive internal locks; API re-entry self-deadlocks | no internal lock held during any callback, including the SASL password callbacks (§19.5); same thread and order | deadlock |
| SRV-07, parse errors only (D9) | FU (00 §5.2) | `zoo_set_servers` commits an unparsable host string, leaving the handle permanently unresolvable (mt then polls uninitialized fd/tv) | a deterministic parse error is not committed; resolution failures are committed as in legacy | permanent liveness failure |
| INIT-13/SEC-4, INIT-14/SEC-5 (D10) | FU (INIT-14: 00 §5.2) | `zookeeper_init_ssl` uses `strtok` (clobbers caller state, races); `strdup(NULL)` crash | `strtok_r`, same tokenization; NULL cert → NULL, EINVAL | data race / crash |
| LOG-09/10, DSP-19 (D11) | FU | `log_message` OOB level read, header overflow, process-static st buffers | `ZOO_INVALID`, clamped header, per-thread buffers | UB / data race |
| INIT-12 (D13) | FU | `pthread_create` failure only `assert`ed (abort, or hang under NDEBUG) | `zookeeper_init` returns NULL with the pthread errno | crash / hang |
| DSP-18 (D14) | FU (00 §5.2) | mt `zookeeper_interest`/`zookeeper_process` race the IO thread | interest: fd -1, no interest, ZOK; process: ZINVALIDSTATE; argument and state checks unchanged | data race |
| DV-ABI-2 / ABI-12 (D15) | **DEVIATE-VISIBLE** (00 §5.1; text copied from 01 §11) | `zoo_sasl_connect/client_start/client_step` act on internal state from an application thread | `zoo_sasl_connect`, `zoo_sasl_client_start` and `zoo_sasl_client_step` are stubs that return ZSYSTEMERROR. The symbols are kept. `zoo_sasl_mark_failed` and `zoo_sasl_client_create`/`_destroy` stay functional. Release note: "`zoo_sasl_connect`, `zoo_sasl_client_start` and `zoo_sasl_client_step` are not declared in any installed header and return ZSYSTEMERROR; the library drives SASL negotiation itself." | Undeclared internals that drive negotiation from an application thread on state that the IO owner owns exclusively (00 §2.6 rule 4). |
| GLB-04 (D16) | FU (07 P7) | unchecked allocations (result materialization, watcher path, `zoo_add_auth` entry) | materialization and watcher path (`abi::raw`, §10.1): `abort()`; `zoo_add_auth` entry: ZSYSTEMERROR at legacy's stage. Every allocation legacy checks keeps its rc (sync record, completion entry, init, ACL/op marshal → ZMARSHALLINGERROR stage 5) | UB under OOM |
| DAT-13 (DSP-13 for the lock rule) | FU (00 §5.2) | the library password callback rewrites the in-block secret in place without a lock; handles that share a block race, and Cyrus reads a buffer another session can rewrite | ZooKeeper sessions: the trampoline runs the library rules (`zks_getsecret_unlocked`) or the application callback with no lock held and stores the secret only in its per-session sink; direct Cyrus users: `_zsasl_getsecret` writes the in-block secret under `zks_sasl_pass_mutex`, which guards only that short copy; no lock spans application code; block layout and `free()` contract unchanged (§19.5) | data race |
| NUL-06/SEC-13 | FU | `zoo_sasl_make_password_callbacks(…, NULL)` dereferences NULL | NULL, `errno = EINVAL` | crash |
| SAS-10/SEC-8 | FU | a -1 server token is passed to Cyrus as `(NULL, UINT_MAX)` | `(NULL, 0)` | UB |
| SAS-11/SEC-15 | FU | the SASL reply decoder can read past the frame | exact-length view; rcs unchanged (CC-24 (00 §4)) | over-read |
| SAS-14 | FU (hardening; 00 §5.2) | no limit on the application callback array length or on secret requests per Cyrus connection | an array of 4096 or more entries fails START, and a seventeenth secret request on one connection returns `SASL_NOMEM`; both become the SASL failure with ZSYSTEMERROR (§19.5) | unreachable with the shipped mechanisms; bounds hostile or corrupted input |
| CLS-11 (DRV-14) | FU | close in a forked child joins thread ids that do not exist there and ends the parent's session | no join, no CLOSE_OP, no callbacks, no release (§15.3) | UB / cross-process corruption |
| WAT-15 | FU | `zoo_set_watcher`/`zoo_set_context` write the pair unlocked, so a concurrent `watch=1` call can capture a torn (fn, ctx) | the pair is read and written inside a spinlock cell; same values | data race |
| ABI-02 | FU | static `close_zsock` reads `ssl_sock`/`ssl_ctx` past a 16-byte `zsock_t` of an application built without `HAVE_OPENSSL_H` | reads and writes only `sock` (§18) | over-read |
| SAS-12 | FU | an oversized Cyrus `clientoutlen` converts to a negative `int`; `sprintf` of "ip;port" | lengths checked against 0x7fffffff (§19.2); checked `snprintf` (§19.7) | UB |
| SRV-04 (assert) | FU | `setup_random` asserts on a failed `/dev/urandom` read (zookeeper.c:772) | getpid fallback (§7) | crash |
| WIN-07 | FU | a legacy Windows build with Cyrus has no password prompt and does not link | `zks_getpassphrase` (§19.1) | build failure |

`auth_lock` keeps legacy's default attributes and relock behavior (§3, §19.9), so it is not a deviation either. The handle registry of §15.5 exists only in debug builds and only aborts on caller-contract violations (07 CLS-10); release builds keep the legacy behavior. A NULL STR log argument prints `(null)` on every platform (§16.2), which is FU only on a libc that crashed on it.

**Cross-document DEVIATE-VISIBLE rows with shim-visible effects** (owned elsewhere, registered in 00 §5.1): DV-ABI-1 (the static archives do not define about 95 undeclared legacy internals: the reference-archive globals that appear in neither `abi/symbols.tsv`, the export set, nor `abi/static-symbols.tsv`, the 179-row archive keep list; both goldens are authoritative), DV-BLD-1 (new link dependencies: DT_NEEDED `libgcc_s.so.1`, on glibc < 2.34 also `libdl`, `libutil` and `librt`, and for st `libpthread`; 01 §11), DV-BLD-2 (the Windows library does not define the winport emulations; the shim keeps Winsock start-up and clean-up and creates `auth_lock` and the thread handles without winport, §3, §15.1-§15.4; 01 §11), DV-BLD-3 (MSVC `zookeeper.lib` cannot hide Rust std, `zkrs_*`, `zks_*` or crypto-provider symbols; 01 §11), DV-BLD-6 (one pre-linked archive member), DV-BLD-7 (Windows ships on the legacy core for a release if the M4 spike fails, §23), DV-BLD-8 (no IBT/SHSTK or BTI property note, §19.7), DV-TLS-1..6 (06 §3.13; DV-TLS-6 applies to the FIPS build only), and DV-FIX-1..9 (the behavior fixes, §23). For the shim, DV-TLS-4 means libssl and libcrypto are not DT_NEEDED or in `.la` `dependency_libs`, and `close_zsock` does not free `ssl_sock`/`ssl_ctx` (§18). Its release note tells applications that used OpenSSL symbols through the library's own dependency to link `-lssl -lcrypto` themselves.

C++ exception propagation through R-CB is identical to legacy on every path (DSP-14 RE, D12, §5), and the IO side takes `auth_lock` at every lockpoint (LA-01 parity, §19.9). Neither is a deviation.

Behaviors that change nothing a correct application can observe (not deviations): heap sync record with copy-out on the waiting thread; three-party start latch; auth lockpoint position relative to state updates; log records emitted after the step returns (timestamps taken when pumped); C++-unwind cleanup requeueing undelivered items; one `auth_lock` hold around the whole of `zoo_add_auth`; the cert string copied and freed at init; the application callback array snapshotted per Cyrus connection; result objects built by `abi::raw` with libc in place of the generated deserializers, with the same allocation calls and sizes; one barrier for a run of consecutive Direct results; the TLS close_notify on a graceful close (07 P6).

Core guarantees relied on (specified in 02, 03 and 07):

- `Compat::LEGACY_C`, never changed by flavor; `DriverMode` st `{LegacyExact, Inline}`, mt `{Precise, Dispatch}`.
- `rw_switch_rc = ConnectionLoss`: uniform ZCONNECTIONLOSS on RO→RW (DV-FIX-9).
- `forced_close = Teardown`: state 999 inside the API call, the pending work taken as a snapshot, and no callback there; the event and the snapshot's ZCONNECTIONLOSS results come from the IO owner's next driving call, and later requests stay queued for the next connection (DV-FIX-8, §14).
- `sasl_failure = Teardown`: AUTH_FAILED event, ZAUTHFAILED for all pending work, the SASL rc from the st driving call, the mt IO thread exits, and `DISPOSE` comes only at the final close (DV-FIX-3).
- `close_mode = LegacyFailPending`; `auth_dup_while_associating = true`.
- `tls_verify = LegacyChainOnly`. TLS: TCP-connect phase exempt from the receive timeout, with no `tv = 0` spin: in state 7, `tv = T/3` when `recv_to <= 0`, otherwise `min(recv_to, T/3)` (07 ST-16(a)); handshake deadline 2/3·T from TCP-connect completion (ST-16); on Windows the T/3 tick reads `SO_ERROR` only in state 7 (ST-16), and a plain TCP connect (state 1) keeps the legacy 2/3·T receive timeout with ZOPERATIONTIMEOUT, because legacy's `select` has no exceptfds (mt_adaptor.c:444; WIN-05); config failure ZSSLCONNECTIONERROR with errno EINVAL, the legacy line, no teardown and no spin (ST-17, CC-14 (00 §4)); `SSL_CERT_FILE`/`SSL_CERT_DIR` read only when `tls_env_allowed`.
- Wakeups cannot fail; only the IO owner closes the socket; multi replies parsed by wire type.
- zk-ffi builds `zk_proto::Config` with the legacy values of the native-only fields (§15.1).

## 22. Verification hooks specific to this boundary

- **Rust fake host:** `#[cfg(test)]` unit tests inside zk-ffi (the crate is `staticlib` only, so no external test crate links it): a vtable of Rust `extern "C"` functions drives zk-ffi under Miri (Tree Borrows and Stacked Borrows) and ASan: token linearity, outbox view lifetimes, `with_handle` reference counting, st close inside a callback inside `zkrs_process`, nested close from a ZCLOSING auth completion, foreign close while another thread waits on a sync record, THREAD item ordering (every JOIN and DETACH is a barrier), SASL generation handling (stale results ignored, generation advanced at teardown, and the recorded stale result of §5 after an unanswered START or STEP ends the wait of 02 §7.4), SASL `DISPOSE` emitted right before the next `START` and, in the final close, after the transport is closed, and no `DISPOSE` at a teardown, host-zeroed abort.
- **raw.rs matrix:** every NULL/0 combination of §8 under Miri and ASan, including `view_bytes` caps.
- **Result writers (§10.1):** `abi::raw` unit tests under Miri drive every writer over C-layout buffers: each completion kind with counts -1, 0 and n, null and empty data, NULL strings, chroot-stripped paths, every multi per-op kind with `value`/`stat` NULL and non-NULL and `valuelen` 0, 1 and short, and the sync copy-out truncation formulas (including `cap < -1`). The ASan suite runs the C delivery paths, so `free()` and `deallocate_*` release every writer allocation with no leak. A fuzz target (08 §8.5) feeds zk-proto-decoded arbitrary reply bodies through the writers and the `deallocate_*` paths under ASan.
- **Log flush:** a log callback records each line and a fake `resolve` records each call; with several failing hosts at init and in `zoo_set_servers`, every resolver failure line reaches the callback before the next `resolve` call, and the pending lines before `rng_reseed` and before the TLS file reads reach it before those start.
- **Use-after-close registry (§15.5):** in a `ZKS_HANDLE_REGISTRY` build, an API call on a released handle and a second `zookeeper_close` each abort with the registry message (death tests); a release build has no registry symbols and no check.
- **Layout:** `_Static_assert` and the ctest-style tests of `crates/zk-ffi-layout` for every `zkrs.h` type, including `zkrs_init_args.tls_env_allowed` and `zkrs_init_args.c_char_signed`, `zkrs_sasl_call`, `zkrs_sasl_out`, `zkrs_result_dst`, `zkrs_sync_src` and `zkrs_sync_dst`, and for every `abi::raw` mirror of a public result record (`CStat`, `CStringVector`, `CId`, `CAcl`, `CAclVector`, `COpResult`) against `zookeeper.h` and `zookeeper.jute.h` on every CI target (i686 included; s390x by the L1 probe from cross-compiled objects, 08 §3.5).
- **Set-uid environment rule:** a set-uid test binary with `SSL_CERT_FILE` pointing at an attacker CA gets `tls_env_allowed = 0` and rejects the attacker chain (08 TI-05).
- **C++ exceptions:** a throw from an st watcher is caught by the application around `zookeeper_process`, and the next `zookeeper_process` delivers the remaining items; a throw from an init log callback propagates (TestLogClientEnv); a throw from an st Cyrus password callback leaves the negotiation to time out, the password mutex is free afterwards, zk-ffi hands the core the stale result of §5 after the teardown, and the next connection's `DISPOSE` and `START` come at its prime response with no further delay, so the `DISPOSE` disposes the old Cyrus session and the session reaches CONNECTED again; a throw on the mt dispatch thread terminates with no Rust frame on the stack (checked by the `ZKS_ASSERT_NOT_IN_RUST` counter and under ASan).
- **Fault injection:** `pthread_create` failure for each thread under LSan; `ops` freed right after `zoo_amulti` returns under ASan; sync waiter cancellation under ASan; `zoo_add_auth` entry OOM with each state (rc order); `sasl_client_new` failure with `*pconn` set (§19.8).
- **Sync waiters on a teardown:** per wrapper, a waiter gets ZCONNECTIONLOSS on the RO→RW switch and, when its request is in the trigger's snapshot, on a forced close, and ZAUTHFAILED on a SASL failure, with every output untouched. A waiter on a request submitted after the trigger gets the reply from the next connection.
- **Forced close under R-CB:** a Rust fake host asserts that the outbox of `zkrs_submit` and `zkrs_set_servers` holds LOG items only when a forced close fires inside the call, and that the next `zkrs_interest`, `zkrs_process` or `zkrs_io_step` carries the event and the snapshot's results. A `zoo_a*` called from an st callback triggers the same path. A `zkrs_submit` after the trigger returns OK with an outbox of LOG items only, its token appears in no later item of those stages, and a `zkrs_close` before they run carries ZCLOSING for the snapshot and for that token.
- **Shim fixes:** sync `zoo_getconfig` and `zoo_wgetconfig` under a chroot send `/zookeeper/config`, and `zerror(-15)` returns "ssl connection error"; each is a DEV test under its DV-FIX row that also asserts the legacy oracle's behavior.
- **SASL:** §19.8.
- **AR-06:** interposer hit counts generated from the reference build for the §20.1 edges, with the sync getconfig edge of DV-FIX-5 taken from the §20.1 table, excluding the `zoo_lock_auth`/`zoo_unlock_auth` counts and the library-internal calls of rule 5 (§1, §20.1).
- **LA-01:** `[diff]` parity with legacy.
- **Differential C tests:** legacy `isValidPath`, `prepend_string`, `sub_string`, `format_endpoint_info`, the sync copy-out and the multi writers against the ports (the `abi::raw` writers for the last two).
- **Black-box ABI corpus and log goldens:** as 08 §3.6 and LG-*, including the TLS and SASL lines of §16.2.

## 23. Delivery by milestone

The shim follows the unified timeline (CC-26 (00 §4)). The legacy core stays buildable and maintained, and its pinned reference build is the differential oracle (08 §4.1). Build selection is `--with-rust-client=no|yes|side-by-side|auto`, `-DWITH_RUST_CLIENT=ON|OFF|AUTO` (default `no` / `OFF` before M5 and `auto` / `AUTO` from M5; `auto` builds the Rust core where the host can build it and the legacy core elsewhere, with a notice; 01 §6.1, §6.3), and in Maven the opt-in property `rust-client` (`no|yes|side-by-side`, default `no`), which passes `--with-rust-client` and `--with-rust-staticlib` to the C module's configure, together with `--with-rust-tls=${rust-tls}` and `--with-rust-tls-provider=${rust-tls-provider}` (01 §13.7). The C module's `rust-client-dropin` profile activates when the `rust-client` property is defined; its only effect is a pom-type reactor-order dependency on the Rust module (01 §13.1). The root property `rust-tls` (default `no`) maps to `--with-rust-tls` and to the zk-ffi feature set, and `abi/tls-probe.sh` reproduces the configure.ac:42-67 TLS decision for the Maven build and emits the default trust paths (01 §13.7). The Rust module is in its own root-pom profile `rust`, which `-Prust` activates and which also activates whenever the `rust-client` property is defined, because a Rust-core C build needs the staticlib. `-Pfull-build` alone never needs cargo (01 §6.5, §13). The TLS provider is `--with-rust-tls-provider=ring|aws-lc-rs|aws-lc-rs-fips` (CMake `ZK_RUST_TLS_PROVIDER`, Maven `rust-tls-provider`), `ring` by default (01 §4.10, 06 §3).

The nine behavior fixes DV-FIX-1..9 are in every Rust-core build, each with its release note (07 §5.1; the list is 00 §5.4). No build option selects them. DV-FIX-1, 2, 3, 4, 7, 8 and 9 are `Compat::LEGACY_C` values in zk-proto (02 §14.3). DV-FIX-5 (sync getconfig uses the absolute config node, §13) and DV-FIX-6 (`zerror(-15)`, §18) are unconditional shim code. The shim's `Rng` stays the libc one (§7, 07 SRV-04). The legacy C core (`--with-rust-client=no`) is not changed by this design.

| Milestone | Shim and boundary content |
|---|---|
| M0 | `zkrs.h` and the outbox/step protocol with the Rust fake host; raw.rs matrix under Miri; core and native API (other documents) |
| M1 | result writers under Miri, F10, DA-12; mt: `zks_threads.c`, sync wrappers, auth lockpoints, THREAD items, close by role, the debug-build handle registry; the whole-flavor pre-link proven on ELF (Linux x86_64/aarch64), Mach-O (macOS) and MinGW is an M1 exit criterion (08 §15.3 M1 (7)) |
| M2 | st and static archives: `static-symbols.tsv` localization, `close_zsock`, `hashtable` stub archive |
| M3 | TLS (`zookeeper_init_ssl` parsing, `tls_env_allowed`, `zkrs_feature_tls`; rustls compat verifier in zk-driver) and SASL (hardened glue, trampoline, password mutex, fuzz harness). First release with opt-in follows M3 |
| M4 | Windows: MSVC static `zookeeper.lib` in both flavors, WSAStartup/WSACleanup, `SOCKET`/`intptr_t` fd, `_beginthreadex`, polling through the zk-driver `Poll` trait (`RustixPoll`; the zk-ffi WSAPoll fallback only if rustix's coverage falls short, §7, 03 §8.8), `__try/__finally`, the MSVC forms of `ZKS_ADD_OVF`/`ZKS_MUL_OVF`/`ZKS_CLEANUP`, `SRWLOCK` password mutex. SASL on Windows stays optional, as in legacy: it is built only when CMake finds Cyrus, and CI tests it on a best-effort basis, not as an M4 gate (§19.1). If the M4 spike fails, Windows ships on the legacy core for that release (DV-BLD-7), and the Rust core follows on Windows in the first release whose Windows build passes the M4 exit criteria of 08 §15.3 (CC-33 (00 §4)) |
| M5 | default flip: the configure and CMake default becomes `auto` (01 §6.5); no shim change |
| M5R | none: the native recipes crate `zk-recipes` has no shim or zkrs.h content (04 §22) |

## Interfaces defined

- R-CB callback-frame rule (normative for all documents): no application code ever runs with a Rust frame below it; every user-visible effect leaves Rust in an ordered outbox drained by the calling C frame; barriers return ZKRS_MORE and continue on zkrs_resume (zkrs_interest_resume for a suspended zkrs_interest).
- zkrs_outbox {void *impl} on the C stack; zkrs_outbox_next(ob, zkrs_item*) -> 1|0 (views valid until the next call on ob); zkrs_outbox_finish(ob) (frees an empty outbox and resets ob->impl to NULL, so the same ob is reused for the next zkrs_* call); zkrs_outbox_requeue(ob) on unwind (untaken items delivered at the start of the next driving call).
- Item kinds: LOG | RESULT{token, zkrs_result_view} (async, sync and auth, told apart by zks_req mode) | WATCH{zkrs_watch_event_view} (node and session) (RESULT and WATCH carry delivery_logs, which C emits on the delivering thread before the callbacks, 02 §5.1) | AUTH_LOCKPOINT | SASL{zkrs_sasl_call: START | STEP | DISPOSE, gen} | THREAD{JOIN | DETACH, IO | DISPATCH}.
- Barriers: AUTH_LOCKPOINT, auth completion groups, each run of sync RESULTs in the mt IO step and in close (consecutive Direct results with no callback item between them share one barrier; Queued completions do not end a run, 02 §5.3), st session events (DrainPoint), SASL START/STEP, every THREAD item (JOIN or DETACH, IO or DISPATCH), and the log flush: pending LOG items are delivered at a barrier before every Blocking vtable call (resolve, rng_reseed) and before the IO step's TLS file reads; and before any wait: a step that would block in poll, in the close flush or close wait, or in the RW probe first returns ZKRS_MORE when LOG items are pending in its outbox (01 §3.6 CF5, 03 §3.3); and the init SASL point (sasl = 1: ZKRS_MORE with rc = ZKRS_INIT_SASL_POINT after resolution and the reseed, before the waker). SASL DISPOSE is ordered, not a barrier; it is emitted immediately before the next START (zk_sasl.c:215-217) and, in the final close, after the transport is closed (legacy destroy order, zookeeper.c:719-749); a teardown emits none. For a stale SASL START or STEP the glue returns STALE with no Cyrus call, and the drain makes no zkrs_sasl_result call.
- zkrs_step {DONE, MORE, RELEASE, EXIT}: returned by zkrs_handle_new, zkrs_set_servers, zkrs_interest, zkrs_process, zkrs_close, zkrs_resume, zkrs_interest_resume (out, written, ob, rc: zkrs_resume for a suspended zkrs_interest, writing *out and *written in the call that reaches finish_interest), zkrs_io_step, zkrs_wait_dispatch (DONE with a batch, or EXIT), zkrs_thread_exit; RELEASE means C drains, frees zh and drops its strong reference (zks_release).
- zkrs_handle* = Arc::into_raw(Arc<Inner>); every zkrs_* entry increments the strong count for the call (with_handle); C owns one reference, each mt library thread one (zkrs_handle_retain/zkrs_handle_release); C never reads zh or zh->core after a call that can release.
- zkrs_handle_new(args, vtable, host, out, ob, rc) -> zkrs_step (rc 0 or the init errno; *out NULL on a chroot error, otherwise set before the first barrier, and a later failure, including a host entry's port error after the earlier entries were resolved, is abandoned by C; with sasl = 1 it returns ZKRS_MORE with rc = ZKRS_INIT_SASL_POINT after the resolution loop and the reseed, C creates the SASL session (ENOMEM abandons the handle), and zkrs_resume continues; the mt waker is created after the resolution loop, the reseed and the SASL point); zkrs_set_servers(core, hosts, ob, rc) -> zkrs_step (legacy rc), driven by zks_drive; zkrs_handle_abandon; zkrs_thread_enter(core, role) / zkrs_thread_exit(core, role, ob, rc) = api_prolog/api_epilog; uint32_t zkrs_feature_tls(void), present only with the tls feature and referenced by a shim built with ZKS_WITH_TLS.
- zkrs_init_args {flavor, hosts, recv_timeout, have_client_id, client_id, client_passwd[16], flags, tls, tls_ca, tls_cert, tls_key, tls_passwd, tls_env_allowed, sasl, xid_seed, sock_cloexec, c_char_signed}; the four TLS strings are borrowed for the call and copied by Rust (passwd into zeroizing storage); tls_env_allowed computed by zks_fill_init_args (getauxval(AT_SECURE) == 0 on Linux, !issetugid() on BSD/Apple, 1 on Windows; 0 in set-uid/set-gid processes, so Rust then ignores SSL_CERT_FILE/SSL_CERT_DIR); no layout cookies.
- zookeeper_init_ssl: NULL cert -> NULL/EINVAL; strdup + strtok_r (strtok_s on WIN32) into ca, cert, key, passwd; passed with tls=1 to zkrs_handle_new; the copy is zeroed and freed when init returns.
- mt loops are C (zks_threads.c): IO thread loops zkrs_io_step; dispatch thread loops zkrs_wait_dispatch; threads created with pthread_create(NULL attr) / _beginthreadex(NULL, 0, ...); 3-party start latch including the init thread; joins and detaches executed by C from THREAD items.
- zkrs_close(core, caller_role, ob, rc): role comes from C TLS (zks_current_role: IO, DISPATCH or FOREIGN); ZKRS_ROLE_INIT is passed only to zkrs_thread_enter and to the matching zkrs_thread_exit in zks_init_epilog; Rust never queries thread identity.
- zkrs_submit(core, const zkrs_request*, token, QUEUED|DIRECT, ob) -> zkrs_status {OK, OK_LOCAL, EARLY_BADARGS, EARLY_STATE, LATE, UNIMPLEMENTED, NOWATCHER, SYSTEM}; token retained iff OK; C funnel maps to ZOK/ZBADARGUMENTS/ZINVALIDSTATE/ZMARSHALLINGERROR/ZUNIMPLEMENTED/ZNOWATCHER/ZSYSTEMERROR; DIRECT maps to zk_proto Route::Direct; validation order is zk-proto submit's.
- zkrs_request uses only zkrs.h types: zkrs_acl{perms, scheme, id} and zkrs_op{type, path, data, datalen, version, mode, acl, nacl, flags} marshalled by C from ACL_vector/zoo_op_t; MARSHAL_FAILED, BAD_ACL and the bad value buffer are InputDefect flags reported as ZMARSHALLINGERROR at the serialization stage; zkrs.h never includes zookeeper.h.
- zkrs_add_auth_check(core, cert, cert_len) (stages 1-3, called before zoo_lock_auth, as zookeeper.c:5309-5328); zkrs_add_auth(core, scheme, cert, cert_len, token|0, flags (ZKRS_AUTH_F_ALLOC_FAILED), ob); zkrs_validate_ttl(mode, ttl); zkrs_sasl_mark_failed(core).
- zkrs_host_vtable: Pure {discard_token, snapshot_default_watcher, store_client_id, log_enabled, conn_permute_disabled, rng_random, rng_drand48}; Blocking {rng_reseed, resolve}; no TLS, SASL, thread or poll entries (polling uses the zk-driver Poll trait, default RustixPoll; zk-ffi injects a WSAPoll implementation only as the fallback of 03 §8.8); no entry reaches application code; Blocking entries require &Unlocked; host token is AtomicUsize zeroed before RELEASE.
- Token contract: completion token CReq(NonZeroUsize) = struct zks_req*; consumed by RESULT delivery or Drop -> discard_token (sync record completed with ZCLOSING); abort only after host release; watcher token (fn, ctx) by value.
- zk-ffi/src/abi/raw.rs and its child module zk-ffi/src/abi/raw/writers.rs (abi::raw::writers: the result writers, c_malloc and c_calloc; it depends only on abi/types.rs, abi/legacy.rs and libc): the only place raw C memory is read or written (bytes, value_buf, cstr_opt, cstr_secret, view_bytes, array, c_bytes, c_malloc, c_calloc, handle_arc (increments the strong count and rebuilds the Arc; rule S1), handle_release (the strong-count decrement of zkrs_handle_release, zkrs_handle_abandon and the outbox drop), outbox_into_impl/outbox_from_impl (the zkrs_outbox impl conversion), write_out (scalar and struct out-parameters: *rc, *written, the *out of zkrs_handle_new, the zkrs_item of zkrs_outbox_next, the zkrs_interest_out of zkrs_interest and zkrs_interest_resume, the zkrs_sockaddr_view of zkrs_current_server and zkrs_peer_addr), read_struct (zkrs_init_args, zkrs_host_vtable (copied once at zkrs_handle_new), zkrs_request and zkrs_sasl_out read through C pointers), the vt_* wrappers that call every host-vtable entry, sink_from_ptr (the resolve push and skip sink, a HostAddrs), on Windows tls_path_candidates (converts a cert-string path field into the UTF-8 candidate, when valid, and the ANSI code-page candidate through MultiByteToWideChar(CP_ACP, …) at zkrs_handle_new; the connector opens them in that order, 06 §3.4) and the WSAPoll fallback call (§7, 03 §8.8), and the result writers); every unsafe operation in our code other than the export attributes is here; len 0 -> empty slice whatever the pointer; every string handed to C is NUL-terminated; outputs are written with raw pointer writes, never through &mut references to application memory.
- Result writers (abi::raw::writers in src/abi/raw/writers.rs, stateless zkrs_* entries, §10.1): zkrs_write_result(view, zkrs_result_dst{stat, value, value_len, strings, acl, str, str_len, ops, nops}); zkrs_write_path(zkrs_str) -> malloc(len + 1) watcher path; zkrs_write_multi(view, results, op_kind, count) (op_result_string_completion / op_result_stat_completion rules; a view with failed = 1 and nops = 0 applies {err = rc, failed = 1} to each of the count results); zkrs_sync_copy_out(zkrs_sync_src, zkrs_sync_dst) (buffer_len truncation, path_buffer NUL rules). Allocation is libc malloc/calloc with legacy's choice and size per object (calloc(count, size) for vectors, also count 0; data NULL for count -1; malloc(len + 1) per string; malloc(len) for data, also 0); NULL for a non-zero size aborts (GLB-04). Public result records are written through repr(C) mirrors (CStat, CStringVector, CId, CAcl, CAclVector, COpResult) that zk-ffi-layout checks against zookeeper.h and zookeeper.jute.h; zkrs.h passes them as void *. C keeps delivery: the callback invocation and the legacy post-callback frees, from the structs as the callback left them.
- zkrs_log_view {level, site, tv_sec, tv_usec, args, nargs} and zkrs_log_arg {kind (ZKRS_ARG_* 0..6), i, u, p, s, ep, gai_rc, err}: the view of a LOG item and of a delivery_logs entry (section 16.2).
- zkrs_result_view {kind = COMPLETION_* 0..8, origin, rc, failed, data, stat, path, children (nchildren -1 = null vector), acl, ops, delivery_logs, n_delivery_logs}; delivery_logs hold the core's delivery lines in legacy order, emitted by C before the callback (async: "Calling COMPLETION_<KIND> ..." with legacy's xid, failed and rc, and for MULTI the per-sub-op lines; sync: "Processing sync_completion ..."; then strip ERRORs; auth: none), so the view has no xid field and C formats no delivery line itself; failed==0 implies a decoded body; rc is never 0 with failed==1; outputs are not written from a body when failed; the abi::raw writers build the legacy malloc/calloc layout from views.
- Sync record: heap struct zks_sync with refcount 2 (waiter, completer); the completer has zkrs_write_result build the reply into the record (MULTI per-op copies always, other kinds only when rc == 0 && !failed) and signals with no user code; caller's buffers written only by the waiting thread after wake, by zkrs_sync_copy_out; the vectors are handed over in C (struct assignment, or deallocate_* for a NULL out pointer); calloc failure -> ZSYSTEMERROR. Sync getconfig: zoo_getconfig and zoo_wgetconfig call the static zks_wgetconfig_, which calls the exported zoo_awgetconfig with ZKS_SYNC_MARKER (DV-FIX-5).
- Debug-build handle registry (§15.5): ZKS_HANDLE_REGISTRY (configure --enable-debug, CMake Debug); mutex-protected set of live zhandle_t *; added before library threads start, removed in zks_release; every exported entry that takes zh checks it after the legacy NULL checks and aborts with a stderr message on use after close or double close; release builds: no registry, legacy (undefined) behavior.
- Auth lockpoints: AUTH_LOCKPOINT barrier before the state change and completion collection at the AUTH reply, teardown collection, close collection and auth resend; C calls the exported zoo_lock_auth/zoo_unlock_auth on zh->auth_lock (default attributes, pthread_mutex_init(.., 0) as mt_adaptor.c:253; no ERRORCHECK); zoo_add_auth takes the same lock the same way; lock order auth_lock -> core; Rust never takes auth_lock.
- zkrs.h SASL types: zkrs_bytes {p, len (uint32_t), is_null}; zkrs_sasl_call {kind START|STEP|DISPOSE, gen (uint32_t), local, peer (zkrs_bytes: platform sockaddr image), token}; zkrs_sasl_out {status CONTINUE (0) | DONE (1) | FAIL (-1) | STALE (-2), mech, token} (STALE is glue to drain only; this document owns every zkrs.h constant value); zkrs_sockaddr_view {family 0|4|6, port, addr[16], flowinfo, scope_id} for resolve, zkrs_current_server, zkrs_peer_addr and ENDPOINT log arguments; never SASL; zkrs_sasl_result(core, gen, view) copies Cyrus output at once; lengths capped at 0x7fffffff.
- SASL glue (zks_sasl.h, no Cyrus types; details owned by 06 §4.7.2): typedef zkrs_bytes zks_bytes; typedef zkrs_sasl_out zks_sasl_out; ZKS_SASL_OUT_FAIL; typedef struct zks_sasl_sess zks_sasl_sess; void zks_sasl_start(zks_sasl_sess *s, uint32_t gen, const zks_sasl_params *p, zks_bytes local, zks_bytes peer, zkrs_sasl_out *out); void zks_sasl_step(zks_sasl_sess *s, uint32_t gen, zks_bytes challenge, zkrs_sasl_out *out); void zks_sasl_dispose(zks_sasl_sess *s, uint32_t gen); typedef struct zks_sasl_params zks_sasl_params; the glue rejects a stale gen itself (status STALE, no Cyrus call; dispose: no-op), so the libFuzzer harness covers the check; zks_sasl_start validates the family and length of local/peer, formats "%s;%s" with getnameinfo(NI_NUMERICHOST|NI_NUMERICSERV) and snprintf into bounded buffers, and logs "getnameinfo: %s\n" at zk_sasl.c:185 on error; the mechlist reaches sasl_client_start from p->mechlist (no mechlist accessor; zks_sasl_test_new takes a mechlist argument for the fuzz harness); accessors zks_sasl_sess_params, zks_sasl_gen, zks_sasl_set_gen, all defined in zks_sasl_cyrus.c (the drain records gen after a non-STALE START); lifetime zks_sasl_sess_new(zh, params) / zks_sasl_sess_free (zks_sasl_sess_clear keeps the object, for zoo_sasl_client_destroy).
- sasl_client_new failure: the glue disposes *pconn if it is non-NULL (if (s->conn) sasl_dispose(&s->conn)) before the per-session callback array and secret copies are freed.
- zks_sasl_sess: per-handle; owns service/host/mechlist copies, sasl_conn_t*, gen, per-session callback array and secret copies; borrows the application callback array; the Cyrus connection is disposed through the outbox right before the next START (zk_sasl.c:215-217) and in the final close after the transport close (zookeeper.c:719-749), never at a teardown alone; the generation advances at teardown; SAS-14 limits: callback arrays of 4096 or more entries and more than 16 secret requests per connection fail with SASL_NOMEM.
- zks_sasl_cb.h (Cyrus types; two SASL TUs only): zks_secret_sink (arg, p, len, out); zks_is_library_pass_cb(proc); zks_getsecret_unlocked(conn, ctx, sink, sink_arg, psecret), which runs the legacy password rules with no lock and writes only to the sink. zks_sasl_pass_mutex (PTHREAD_MUTEX_INITIALIZER; SRWLOCK_INIT on Windows) is private to zks_sasl_cb.c.
- Secret trampoline: zks_sasl_pass_trampoline replaces the first SASL_CB_PASS entry in a per-session array copy; the library's own callback (zks_getsecret_unlocked) and application callbacks run with no ZooKeeper lock held and write into the per-session sink zks_pass_store owned by the trampoline; _zsasl_getsecret (the proc in the block, for direct Cyrus users) computes the password with no lock held and holds zks_sasl_pass_mutex (zks_sasl_pass_lock/zks_sasl_pass_unlock) only around the short in-block copy; no lock spans application code, so unwinding needs no cleanup handler to release a lock.
- Exactly two TUs include <sasl/sasl.h> and zks_sasl_cb.h (zks_sasl_cyrus.c, zks_sasl_cb.c), both built with the hardening flags of §19.7 (06 §4.7.10) and using the portable macros ZKS_ADD_OVF/ZKS_MUL_OVF (__builtin_*_overflow on GCC and Clang, explicit range checks on MSVC) and ZKS_CLEANUP (__attribute__((cleanup)) on GCC and Clang, explicit goto-out cleanup on MSVC) from zks_sasl_cb.h, always with HAVE_CYRUS_SASL_H, and with ZKS_SASL_GLUE defined before zks_internal.h; zks_internal.h is the first include of every shim TU (zookeeper_log.h, proto.h and recordio.h only after it; the CI depfile check verifies the order) and the only include of zookeeper.h, and undefines HAVE_OPENSSL_H, and outside the SASL glue HAVE_CYRUS_SASL_H, before it; other TUs use ZKS_WITH_SASL; the TLS shim macro is ZKS_WITH_TLS; shim TUs do not depend on HAVE_OPENSSL_H. Feature defines follow legacy exactly: autotools -DHAVE_OPENSSL_H (TLS built) and -DHAVE_CYRUS_SASL_H (SASL built) in the library CPPFLAGS; CMake target_compile_definitions(zookeeper PUBLIC HAVE_OPENSSL_H / HAVE_CYRUS_SASL_H) (CMakeLists.txt:213, 227); "TLS built" = legacy autodetect found OpenSSL, or --with-rust-tls=yes / WITH_RUST_TLS=ON.
- Log records: zk-proto LogSite enum (zk-proto/src/generated/log_sites.rs) and shim/zks_log_sites.h generated from abi/log-sites.tsv, which is pinned to the final legacy C-core commit on main before the default flip (extracted by cargo xtask log-sites at that commit and re-extracted whenever the pin moves; legacy C-core changes may move or change sites freely); zk-ffi stamps time when pumping; rendered by the shim with platform snprintf, with "(null)" for a NULL STR arg on every platform; ENDPOINT args use the per-thread 134-byte buffer (legacy overwrite reproduced).
- zookeeper_interest out-parameters unwritten only on the unrecoverable return and on whole-list resolve failure with no socket; zkrs_interest_out.fd is intptr_t and written through SOCKET* under WIN32.
- Delegation graph mirrors C (exported vs static helpers) for the edges of section 20.1; AR-06 expected counts generated from the reference build for those edges, except the sync getconfig edge of DV-FIX-5, whose count comes from the section 20.1 table; library-internal calls to is_unrecoverable, zoo_get_log_callback (LOGCALLBACK), zoo_get_current_server, zoo_state, the mt IO loop's zookeeper_interest/zookeeper_process, zoo_cycle_next_server, zoo_sasl_connect, zoo_sasl_client_start, zoo_sasl_client_step, zoo_sasl_mark_failed, zoo_sasl_client_create, zoo_sasl_client_destroy, zookeeper_close from api_epilog, deallocate_Buffer, log_message and zoo_get_log_stream (for core-originated lines), zoo_htonll and the generated record deallocators are not reproduced and are excluded, like the zoo_lock_auth/zoo_unlock_auth counts.
- Prefix rule: all shim internals are zks_*; zks_* and zkrs_* are never global in a shipped .so or .a (except MSVC); .a globals = symbols.tsv + static-symbols.tsv (161 + 18 oa_*/ia_*) via a whole-library ld -r pre-link and localize.
- struct _zhandle (private): clientid_t at stable address, default watcher+context pair cell (spinlock), atomic log_callback, core, flags, sasl session, mt io/dispatch pthread_t + auth_lock (default attributes, as legacy) + start latch, Windows wsa_started; no TLS state.

## Decisions

| Decision | Rationale | Alternatives |
|---|---|---|
| The pull/outbox model everywhere, including library threads: the mt IO and dispatch loops are C functions that call Rust step functions; Cyrus calls come from SASL items and thread joins from THREAD items (K2, CC-1 to CC-3 (00 §4)). | No application code runs below any Rust frame on any thread, so C++ exceptions and longjmp behave as legacy (DSP-14 RE), no Rust lock is held across a callback, and no vtable entry can reach user code. | Push model with guarded trampolines and an abort-on-unwind cleanup on library threads (needs an abort path); extern "C-unwind" through unwind-safe drivers (unauditable); join/detach as vtable entries (would block inside Rust). |
| zkrs_handle* is Arc::into_raw; every entry takes its own strong reference for the call; C, each library thread and each outbox hold separate references; RELEASE is a step result after which C frees zh and drops its reference. | No Rust frame can observe deallocation of state it borrows, a foreign close cannot free memory under a live call, and Rust never frees or touches zh after release because C performs the release. | Box::into_raw with Rust reclaiming after a release_host vtable call: unsound when a call frame still holds &self. |
| Public C input structs never cross into Rust: the shim marshals zoo_op_t[] and ACL_vector into zkrs_op/zkrs_acl; zkrs.h includes no public header and passes the public result records to the writers as void * (K3, CC-28 (00 §4)). | Union variant selection stays in C, there is no bindgen, and the only mirrors are the six non-union result records of abi::raw, checked by zk-ffi-layout on every target. | bindgen mirrors of every public type plus runtime layout cookies: union reads would happen in Rust. |
| Validation order lives in zk-proto submit; zk-ffi passes InputDefect flags (bad value buffer, BAD_ACL, shim OOM) that zk-proto reports at the legacy stage (CC-11 (00 §4)). | One home for the order; shim-side failures never reorder return codes. | Order in zk-ffi/src/legacy: a second copy of legacy logic outside the core. |
| Bad value buffers and unusable ACLs return ZMARSHALLINGERROR at the serialization stage, single ops and multi alike (CC-12 (00 §4)). | That is what legacy's own funnel returns for a serialization failure, at the point legacy reaches it (after the state check). | ZBADARGUMENTS at the validation stage: reorders rcs relative to the state check. |
| Sync record is a heap, refcounted C struct; on the completing thread the abi::raw writers build the reply into it and C signals; the abi::raw copy-out runs on the waiting thread and only for a decoded reply (K12, CC-8 (00 §4)). | Waiter cancellation cannot corrupt a reused stack; caller buffers are touched only by the caller; ABI-06 interposition unchanged. | Stack record with IO-thread copy-out; Rust SyncSlot with copy-out from Rust data. |
| zoo_lock_auth parity through AUTH_LOCKPOINT barriers before the state change and collection at every legacy site; C locks and unlocks auth_lock in the drain through the exported zoo_lock_auth/zoo_unlock_auth; the mutex has default attributes, as legacy (CC-7 (00 §4)). | Reproduces the legacy stall and LA-01 with lock order auth_lock -> core, no inversion at the teardown site and no lock-held callback; a same-thread relock keeps the legacy behavior, so no deviation is added. | A shim-only lock that the IO side never takes (visible change); vtable lock_auth/unlock_auth called by Rust around collection (lock-order inversion). |
| Results are materialized from Rust views by zk-ffi's abi::raw writers in the exact legacy malloc/calloc layout, with libc allocation from the library's one CRT; the sync copy-out is an abi::raw writer too; C keeps the callback invocation, the post-callback deallocation order, the sync waiting and the vector hand-over (K11). | Paths must be chroot-stripped, multi items parsed by wire type and failed computed by the core; deserialize_* does not bound lengths. Placing the writers in the one audited unsafe module, covered by Miri, the layout tests and a fuzz target, keeps about 500 lines of pure data shaping out of the shim; the application's free(), deallocate_* and the jute deallocators work unchanged because the allocation calls and sizes are legacy's. | Raw bytes plus generated deserialize_*: a second decoder without bounds. Materialization in shim C: the same rules in C, outside Miri, with a larger shim. |
| Pending LOG items are delivered at a barrier before every Blocking vtable call and before the IO step's TLS file reads; zkrs_handle_new and zkrs_set_servers return zkrs_step for this. | A log callback sees each line before the blocking work starts, as in legacy, and the callback still runs from a C frame; the cost is one round trip, only on those paths and only when logs are pending. | Emit the lines after the blocking call returns (a callback would see them late, after a long DNS wait). |
| Debug builds keep a registry of live handles (ZKS_HANDLE_REGISTRY) and abort on use after close or double close; release builds behave as legacy with no overhead. | Turns a silent use-after-free into a clear diagnostic where developers test, without any cost or behavior change in shipped builds (07 CLS-10). | A registry in every build (overhead and a new failure mode in production); no detection. |
| Delegation mirrors C exactly for the §20.1 edges, including static helpers; library-internal calls to other exported functions are not reproduced; local sync removal completes its own record via the static helper; WAT-13 applies only to the async exported entry. | LD_PRELOAD interposers see identical call counts for the §20.1 edges other than the sync getconfig edge of DV-FIX-5 (AR-06), and sync local removal matches legacy notify_sync_completion. `zoo_lock_auth`/`zoo_unlock_auth` call counts are not an ABI contract and AR-06 excludes them, so one lock window around `zkrs_add_auth` keeps registration and send in a single Rust call. | Route everything through exported zoo_aw*: changes hit counts. Split `zkrs_add_auth` into a registration call and a send call so that the shim locks twice: adds a Rust call only to match a count that is not a contract. |
| Thread identity comes only from C thread-local storage written before the start latch; the latch has three parties; C passes the caller role (CC-4 (00 §4), K13). | Race-free, allocation-free, no TLS destructors in foreign threads, and the init thread's pthread_t stores are published before any library thread runs application code. | pthread_equal on zh->io_thread from any thread (racy); std::thread::current (allocates, registers destructors, pins the DSO). |
| Vtable entries are Pure or Blocking only; all Rust mutexes are acquired through a non-Send Unlocked proof whose one constructor only the entry points call (§7); release builds abort on same-thread core-lock re-entry; C asserts vtable code never re-enters zkrs_* (K4, CC-6 (00 §4)). | The remaining hazards (blocking under a lock, re-entry) are prevented at compile time and fail loudly at run time. | A third UserReaching class: unnecessary because no entry reaches user code. |
| zookeeper_init_ssl only parses the cert string and passes four borrowed strings and tls_env_allowed through zkrs_init_args; TLS runs in rustls inside zk-driver with LegacyChainOnly; no TLS code in C (06 §3). | Keeps OpenSSL and all TLS glue out of the unsafe surface; the passphrase lives in C only for the duration of init; set-uid processes keep OpenSSL's rule of ignoring SSL_CERT_FILE/SSL_CERT_DIR, so trust is never widened. | OpenSSL in the shim over memory BIOs (keeps the OpenSSL dependency closure and puts TLS state machine glue in unsafe C); reading the environment unconditionally (widens trust in set-uid applications). |
| SASL is Cyrus in two hardened C TUs behind a three-function bounded interface (06 §4.7.2 signatures), a generation passed to and checked by every glue call, outbox-serialized dispose and a per-session secret trampoline (06 §4.7). | The Cyrus API is C and its callbacks are application code; the smallest C surface with exact-length inputs, copy-at-once outputs and no cross-thread dispose minimizes unsafe memory operations while keeping legacy behavior. Checking the generation in the glue itself (status STALE, no Cyrus call) puts the check inside the code the libFuzzer harness drives. | Generation checked only in the drain (the fuzz harness would not cover the check); Rust Cyrus bindings (adds unsafe outside zk-ffi's audit and Rust frames under Cyrus callbacks); a native Rust SASL in the drop-in (changes mechanism coverage and the Cyrus callback API). |
| The Cyrus connection is disposed by a DISPOSE item right before the next START and, in the final close, after the transport close; a teardown emits no DISPOSE but advances the generation. | Legacy disposes at the top of zoo_sasl_connect (zk_sasl.c:215-217) and in destroy after close_zsock (zookeeper.c:719-749), so Cyrus callbacks run at the legacy points and SAS-13 is RE; the generation still rejects late results for the old session. | DISPOSE at every teardown (a Cyrus callback such as SASL_CB_LOG would run earlier than in legacy). |
| The glue takes the params (including the mechlist) and the address views as explicit arguments; the params come from the session's own copies through an accessor. | The glue functions are pure over their arguments, so the fuzz harness drives exactly the production code with fuzzed tokens, addresses, params and generations. | Glue reads the session fields directly (harness would need a real session layout). |
| Password secret path (DAT-13, DSP-13; 06 §4.7.6-4.7.7): the library password callback and application callbacks run with no ZooKeeper lock held and write into a per-session sink owned by the trampoline; the process-wide mutex guards only the short, non-callback in-block copy of the direct Cyrus path. | No internal lock spans application code (K2), so a handle waiting at `getpass` or in a transform callback delays no other handle, unwinding needs no cleanup handler to release a lock, and no same-thread relock can occur; every write to the shared in-block buffer is still serialized, and ZooKeeper sessions never read a buffer another session can rewrite. | Holding the mutex around the library callback and the copy (a lock across application code: cross-handle stalls past the receive deadline, and a lock to release on unwind). |
| Shim allocations keep legacy's checked return codes; the zoo_add_auth entry OOM is a flag that Rust reports at legacy's stage; only allocations legacy left unchecked abort (K19, 07 P7). | No visible OOM behavior changes where legacy had one, and rc order is exact even under OOM. | Abort everywhere; return ZSYSTEMERROR before the state checks (reorders rcs). |
| A forced close inside an API call leaves only LOG items in that call's outbox; the event and the ZCONNECTIONLOSS results of the work pending at the trigger come from the IO owner's next driving call, under R-CB (DV-FIX-8). | Legacy runs no callback inside `zoo_a*` or `zoo_set_servers`, so an application can hold a lock around them that its callbacks also take, and a `zoo_a*` can itself run inside a callback. The state still reads 999 when the call returns. | Draining the teardown in the API call's frame (new re-entrancy, and auth completions on an application thread in mt). |
| Sync getconfig calls the exported `zoo_awgetconfig` through a static helper (DV-FIX-5). | It reuses the raw-path request of async getconfig, keeps sync traffic visible to interposers on `zoo_a*`, and adds no flag to `zoo_wget`. The `zoo_wget` count for these two entries is the one AR-06 difference, and it follows from the fix. | A hidden raw-path flag passed through `zoo_wget` (an exported entry cannot take one without thread-local state). |
| Token retained iff ZKRS_OK; auth-frame encoding is infallible (CC-10 (00 §4)). | One ownership state fewer; legacy reaches the corresponding rc only through allocation failure. | A LATE_RETAINED status with a second ownership rule. |
| Windows: WSAStartup at the legacy init point with the legacy error line, WSACleanup in zks_release; SOCKET* written via an intptr_t interest_out.fd; MSVC uses __try/__finally outside the SASL glue, and the SASL glue uses the MSVC forms of ZKS_ADD_OVF/ZKS_MUL_OVF/ZKS_CLEANUP; in scope at M4 (CC-33 (00 §4)). SASL on Windows is optional, as in legacy, with the SRWLOCK password mutex. | Keeps the per-process Winsock side effects and full SOCKET width without a deviation; legacy CMake builds SASL only when Cyrus is found, so an optional subset matches legacy. | Drop winport behavior as an unregistered deviation; make Windows SASL an M4 gate (needs Cyrus for Windows in every build). |
| The Site table is pinned to the final legacy C-core commit on main before the default flip; `cargo xtask log-sites` extracts it at that commit and re-extracts it whenever the pin moves. Legacy C-core changes may move or change log sites freely. | The table matches the C core that the reference build of 08 §4.1 uses, so func@line and texts match the oracle, and legacy C-core maintenance is not constrained by the table. | Pin to a release tag (would miss C-core fixes made on main before the flip). |
