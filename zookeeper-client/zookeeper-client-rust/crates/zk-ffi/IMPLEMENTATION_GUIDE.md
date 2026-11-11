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

# Rust Core Drop-in Library Implementation Guide

This guide is for contributors to the Rust Core drop-in libraries `libzookeeper_mt` and `libzookeeper_st`. Each library has two halves. The Rust half is the `zk-ffi` crate. The C half is the shim in `zookeeper-client-rust/c/shim`. The guide gives the rules, the shared structure and the contract of each part.

The design is in this guide and in `zookeeper-client-rust/design/specification.md` ("spec"). The legacy C sources in `zookeeper-client-c/src` are the reference for every observable behavior.

All paths in this guide are relative to the `zookeeper-client` directory. `RS` means `zookeeper-client-rust/crates/zk-ffi`. `SH` means `zookeeper-client-rust/c/shim`.

## 1. Rules for every change

### 1.1 Boundaries

- Do not edit any file in `zookeeper-client-c`. The drop-in build reads its headers, its sources and its version from there, and writes nothing there. The `generated/zookeeper.jute.c` and `src/recordio.c` go into the library unchanged.
- `SH/zkrs.h` is generated. Do not edit it by hand. cbindgen writes it from `RS/src/abi/types.rs` and the exports (section 5).
- The doc comment on each Rust function and the comment on each C function is its contract. When a change breaks a contract, change the contract in the same commit.
- An exported signature (`zkrs_*`, `zoo_*`, `zookeeper_*`) is part of the private ABI between the halves. A change to it changes `zkrs.h`, so change both halves in the same commit.
- cbindgen copies the doc comment of each export into `zkrs.h`. After you change such a comment, regenerate `zkrs.h` and commit it with the change.
- The fields of a Rust struct belong to the file that declares it. Other files use only its methods. The fields of `struct _zhandle`, `struct zks_req` and `struct zks_sync` have fixed writers.

### 1.2 Code rules

Rust:

- MSRV is Rust 1.85, edition 2024. Let-chains are not available.
- The crate lints deny `unwrap_used`, `expect_used`, `panic`, `indexing_slicing`, `arithmetic_side_effects`, `dbg_macro`, `print_stdout`, `print_stderr`, `large_stack_arrays`, `large_stack_frames`, `undocumented_unsafe_blocks`, `multiple_unsafe_ops_per_block` and `ptr_as_ptr`. Use `get`, `checked_*`, `wrapping_*`, `saturating_*` and `try_from`. Do not use `as` casts.
- `unsafe` code is allowed only in `RS/src/abi/raw.rs`, `RS/src/abi/raw/` and the test stand in. Every other module uses the `raw` functions. The test `RS/tests/unsafe_scope.rs` fails on an unsafe block, fn, impl or trait in another file.
- `RS/src/abi/raw/writers.rs` uses only `abi/types.rs`, `abi/legacy.rs`, `libc` and `std`. Thus another crate can include it by `#[path]`.
- Every export body runs in `ffi_guard` or `with_handle`. Production code never panics. The `capi` profile has `panic = "abort"`. A broken contract of C calls `abort_with`.
- No callback of the application runs in a Rust frame. Rust gives effects to C only through the outbox. The vtable entries are Pure or Blocking leaves and reach no application code.
- A token goes to C exactly once: through a RESULT item (`CReq::into_raw`), through a non-OK `zkrs_status`, or through `discard_token` when it drops. Never copy a token address.
- The dependencies are `zk-proto`, `zk-jute`, `zk-driver`, `libc` and `zeroize`. The features are `tls`, the three TLS provider features and `sasl`. A new dependency or feature needs a review of the drop in build rules.

C:

- `zks_internal.h` is the first include of every shim unit, and the only include of `zookeeper.h`.
- The internal names start with `zks_` and are `static` or `ZKS_HIDDEN`. No other global symbol appears. The dev script checks the export set.
- Every call into Rust uses `ZKS_CALL(...)`. The handle rule: read `zh->core` into a local first. After a call that can release the handle, read `zh` only through the step protocol of section 2.4 (`zks_drive`).
- A vtable entry runs under `zks_in_vtable` and calls no `zkrs_*` function. `ZKS_CALL` aborts on such a call.
- Use `errno` values and return codes as legacy does. Cite the legacy file and function in a comment when the behavior is not obvious.

### 1.3 Checks

Rust:

```sh
cd zookeeper-client/zookeeper-client-rust
cargo build  -p zk-ffi
cargo rustc --locked -p zk-ffi --profile capi --crate-type staticlib
cargo clippy -p zk-ffi --all-targets -- -D warnings
cargo +1.85.0 check -p zk-ffi --all-targets
cargo test   -p zk-ffi
RUSTDOCFLAGS='-D warnings' cargo doc -p zk-ffi --no-deps
cargo fmt -p zk-ffi --check
(cd crates/zk-ffi && cbindgen --config cbindgen.toml --crate zk-ffi \
    --output ../../c/shim/zkrs.h --verify)
cargo xtask layout gen --check
cargo test --manifest-path crates/zk-ffi-layout/Cargo.toml
```

Run clippy and the tests also for each feature set of the drop-in. The shim README lists the feature sets ("Checks").

The layout check needs a C compiler and the OpenSSL and Cyrus SASL headers. It finds them in the default paths or through pkg-config. `ZK_LAYOUT_SSL_CFLAGS` and `ZK_LAYOUT_SASL_CFLAGS` give other flags. `ZK_LAYOUT_CONFIGS` selects fewer configurations, for example `plain,sasl`. A configuration that does not build fails the test.

A change to the handle, the close or the thread exports also needs the loom models and a clippy pass under `--cfg loom`. The unit tests of the library also run under Miri (nightly with the `miri` component):

```sh
cargo xtask loom -p zk-ffi
RUSTFLAGS='--cfg loom' CARGO_TARGET_DIR=target/loom \
  cargo clippy -p zk-ffi --all-targets -- -D warnings
cargo xtask miri --only zk-ffi
```

C:

```sh
cd zookeeper-client/zookeeper-client-c
mkdir -p /tmp/zk-shim/obj
for f in ../zookeeper-client-rust/c/shim/zks_*.c; do
  b=$(basename "$f" .c)
  cc -std=c99 -Wall -Wextra -Wdeclaration-after-statement -Werror \
     -DTHREADED -Iinclude -Igenerated -I../zookeeper-client-rust/c/shim -c "$f" -o /tmp/zk-shim/obj/$b.mt.o
  cc -std=c99 -Wall -Wextra -Wdeclaration-after-statement -Werror \
     -Iinclude -Igenerated -I../zookeeper-client-rust/c/shim -c "$f" -o /tmp/zk-shim/obj/$b.st.o
done
../zookeeper-client-rust/dev/build-dropin.sh "$PWD/.." /tmp/zk-dropin
```

The dev script builds the staticlib, the shim archive, the relocatable object, the shared library, the static archive and `cli_FL` of each flavor. It checks the export set against `symbols.tsv`, checks that no private symbol is global and runs a smoke test.

### 1.4 Tests

- Rust unit tests use two helpers: `abi::standin` (the stand in vtable and its recorder) and `abi::handle::test_support::new_handle` (a handle with the stand-in vtable and no driver). Start each test with `let _rec = standin::recorder();`.
- A test may build zk-driver values (`Outbox`, `Item`, `OpResult`, `LogRecord`) directly.
- A Rust test that needs a real server has `#[ignore = "real-server"]`. It reads the first port of its range from `ZK_TEST_PORT_BASE`, and it skips when the variable is not set. Run these tests with `ZK_TEST_PORT_BASE=<base> cargo test -p zk-ffi -- --ignored`.
- The C tests are in `zookeeper-client-rust/c/tests`. `abi/` holds the ABI and layout tests, `conformance/` compares the Rust core with the legacy core, `link/` checks the link of applications, and `public/` runs the legacy public-API suite. The shim README gives the commands.
- Real-server tests start and stop the server with `zookeeper-client-rust/dev/test-server.sh start|stop PORT DIR`. Always stop the server, also after a failure. Never use 2181.
- The loom models of the C exports are in `abi/loom_models.rs`. They compile only under `--cfg loom` and use the stand-in vtable. The IO thread of a model does not poll, because a blocking poll stops the whole model.
- `cargo xtask miri` runs the unit tests of the library under Miri. A test that Miri cannot run has `#[cfg_attr(miri, ignore = "<reason>")]`.

## 2. Shared structure

### 2.1 Core files

These files hold the shared frame of both halves. A change here affects every aspect of the client, so review it with care.

| File | Content |
|---|---|
| `RS/Cargo.toml` | The crate, its lint table, the dependencies and the features. |
| `RS/build.rs` | The check that the crate version equals `ZOO_VERSION`. |
| `RS/cbindgen.toml` | The `zkrs.h` generator configuration. |
| `RS/src/lib.rs` | The crate root. |
| `RS/src/abi/mod.rs` | The module tree. |
| `RS/src/abi/types.rs` | Every `repr(C)` type and constant of `zkrs.h`. |
| `RS/src/abi/legacy.rs` | `repr(C)` mirrors of `struct Stat`, `String_vector`, `ACL_vector`, `ACL`, `Id`, `zoo_op_result_t`. |
| `RS/src/abi/raw.rs` | The boundary readers, the handle and outbox pointer helpers, the vtable calls. |
| `RS/src/abi/entry.rs` | `ffi_guard`, `with_handle`, `abort_with`. |
| `RS/src/abi/results.rs` | The four writer exports. They call `raw::writers`. |
| `RS/src/abi/standin/mod.rs` | The test stand-in vtable. |
| `SH/zkrs.h` | Generated by cbindgen. `--verify` must pass. |
| `SH/zks_internal.h` | `struct _zhandle`, `struct zks_req`, `struct zks_sync`, the watcher cell, the latch, `ZKS_CALL`, the thread roles, `enum zks_c_site`, every hidden prototype. |
| `SH/zks_data.c` | The 36 exported data objects. |
| `SH/zks_compat.c` | `zoo_lock_auth`, `zoo_unlock_auth`, `close_zsock`. |
| `SH/zks_hashtable_stub.c` | The CMake `hashtable` archive member. |
| `zookeeper-client-rust/dev/build-dropin.sh` | The macOS dev build of the drop-in (section 1.3). |

### 2.2 The layers

```
application
   |  exported C ABI (include/*.h)
   v
C shim (SH): zks_async, zks_multi, zks_sync -> zks_submit_prepared
             zks_handle, zks_threads, zks_drain (step protocol, delivery)
             zks_log, zks_env, zks_errors, zks_resolve, zks_rng (leaves)
             zks_tls, zks_sasl_cyrus, zks_sasl_cb (TLS and SASL builds)
   |  ZKS_CALL(zkrs_*)  borrowed inputs, outbox out        ^ vtable: Pure or Blocking
   v                                                       |
zk-ffi (RS): entry -> handle | submit + multi | outbox + logging | accessors
             tls | sasl
             raw (readers, vtable calls) + raw::writers   host (HostLeaves)
   |  safe Rust
   v
zk-driver: threaded::Shared (mt), Polled (st), Init, Outbox, Item, Step
   v
zk-proto: Connection, Request, MultiOp, LogRecord, LogSite
```

### 2.3 Locks and the writers of the shared fields

Rust: `Inner.init` is a `zk_driver::sync::Mutex`. Take it only with `take_init` and `put_init`, never across `Init::step`. `Inner.driver` is set once (`install`). `FfiHost.host` is atomic and goes to 0 once, at the final close. The driver locks belong to zk-driver. A vtable call of a Blocking leaf holds no `core` lock and no leaf lock (`Unlocked` token).

C: the watcher cell `zh->dw` holds two independent lock-free atomic pointers and no lock (inline helpers of `zks_internal.h`). Setters use release stores and readers use acquire loads. `zh->auth_lock` is the legacy `zoo_lock_auth` mutex. `struct zks_sync` has its own mutex and condition variable. No C lock is held across a `zkrs_*` call, except `auth_lock` across `zkrs_add_auth`. The drain handles an AUTH_LOCKPOINT item with `zoo_lock_auth` and then `zoo_unlock_auth`, so it waits for a holder.

| Field | Writer |
|---|---|
| `zh->core` | `zks_init_common` (`zks_handle.c`). Read only after that. |
| `zh->client_id` | `zks_vt_store_client_id` (`zks_handle.c`, under the vtable). It writes only when the id or password changes, so a reconnect to the same session does not write. Application reads are not synchronized, as in legacy. |
| `zh->dw` | `zks_handle.c` (init, `zoo_set_watcher`, `zoo_set_context`). Readers use `zks_watch_cell_read` or `zks_watch_cell_ctx`. |
| `zh->log_callback` | `zks_handle.c` (init, `zoo_set_log_callback`, relaxed store). `zks_log.c` reads it (relaxed load). |
| `zh->flags`, `zh->sasl` | `zks_handle.c` (init). `zh->sasl` is NULL except in a SASL build with SASL parameters. |
| `zh->io_thread`, `zh->dispatch_thread`, `zh->start_latch` | `zks_threads.c`. |
| `zh->auth_lock` | `zks_handle.c` initializes and destroys it. `zks_compat.c` locks it. |
| `struct zks_req` | `zks_req_new` (`zks_async.c`). `zks_drain.c` frees it after delivery. `zks_sync.c` sets `sync`. |
| `struct zks_sync` | `zks_sync.c`. `zks_drain.c` calls `zks_sync_complete` from delivery. |

### 2.4 The step protocol and the outbox

Every driving export (`zkrs_handle_new`, `zkrs_io_step`, `zkrs_wait_dispatch`, `zkrs_close`, `zkrs_resume`, `zkrs_set_servers`, `zkrs_thread_exit`, `zkrs_interest`, `zkrs_process`) fills the outbox of the caller and returns a `zkrs_step`. `zkrs_submit`, `zkrs_add_auth` and `zkrs_cycle_next_server` fill the outbox and return no step.

- `ZKRS_MORE`: the call stopped at a barrier. C drains the outbox (`zks_drain`) and calls `zkrs_resume`.
- `ZKRS_DONE`: the call finished, `*rc` is valid. C drains the outbox.
- `ZKRS_RELEASE`: the final close ran. C drains the outbox, then calls `zks_release`.
- `ZKRS_EXIT`: a library thread leaves its loop.

`zks_drive` (`zks_drain.c`) runs this loop for every caller. `zks_drain` calls `zkrs_outbox_next` until it returns 0, then `zkrs_outbox_finish`. When an application callback unwinds the drain, the cleanup handler calls `zkrs_outbox_requeue`. The item types are LOG, RESULT, WATCH, AUTH_LOCKPOINT, SASL and THREAD. Only a SASL build makes SASL items.

### 2.5 Call graph

Rust exports and their C callers:

| Rust export (file) | C caller (file) |
|---|---|
| `zkrs_handle_new`, `zkrs_handle_abandon`, `zkrs_handle_retain`, `zkrs_handle_release` (`handle.rs`) | `zks_init_common`, `zks_release` (`zks_handle.c`), `zks_start_threads` (`zks_threads.c`) |
| `zkrs_thread_enter`, `zkrs_thread_exit`, `zkrs_io_step`, `zkrs_wait_dispatch` (`handle.rs`) | the loops of `zks_threads.c` |
| `zkrs_dispatch_ended` (`handle.rs`) | the cleanup handler of the dispatch thread in `zks_threads.c` |
| `zkrs_interest`, `zkrs_process`, `zkrs_close`, `zkrs_resume`, `zkrs_interest_resume`, `zkrs_last_errno` (`handle.rs`) | `zks_handle.c`, `zks_drain.c` |
| `zkrs_submit`, `zkrs_add_auth`, `zkrs_add_auth_check`, `zkrs_validate_ttl` (`submit.rs`) | `zks_async.c`, `zks_multi.c` |
| `zkrs_outbox_next`, `zkrs_outbox_finish`, `zkrs_outbox_requeue` (`outbox.rs`) | `zks_drain` (`zks_drain.c`) |
| `zkrs_state`, `zkrs_recv_timeout`, `zkrs_set_servers`, `zkrs_set_resolution_delay`, `zkrs_cycle_next_server`, `zkrs_current_server` (`accessors.rs`) | `zks_handle.c`, `is_unrecoverable` (`zks_errors.c`) |
| `zkrs_peer_addr` (`accessors.rs`) | `zookeeper_get_connected_host` (`zks_resolve.c`) |
| `zkrs_registry_add`, `zkrs_registry_remove`, `zkrs_registry_check` (`registry.rs`) | the `ZKS_REGISTRY_*` macros of registry builds (`zks_internal.h`) |
| `zkrs_write_result` (`results.rs`, `raw/writers.rs`) | `zks_deliver_result` (`zks_drain.c`), `zks_sync_complete` (`zks_sync.c`) |
| `zkrs_write_path` (`results.rs`) | `zks_deliver_watch` (`zks_drain.c`) |
| `zkrs_write_multi` (`results.rs`) | `zks_multi_complete` (`zks_multi.c`) |
| `zkrs_sync_copy_out` (`results.rs`) | the sync wrappers (`zks_sync.c`) |
| `zkrs_sasl_result`, `zkrs_sasl_mark_failed` (`sasl.rs`) | the Cyrus glue (`zks_sasl_cyrus.c`) |
| `zkrs_feature_tls` (`tls.rs`) | `zks_tls.c`; the configure and CMake TLS checks look for the symbol with `nm` |

Vtable entries (C, called by Rust through `raw::vt_*` and `FfiHost`):

| Entry (file) | Rust caller |
|---|---|
| `zks_vt_discard_token` (`zks_drain.c`) | `CReq::drop` |
| `zks_vt_snapshot_default_watcher`, `zks_vt_store_client_id` (`zks_handle.c`) | `FfiHost` Pure leaves |
| `zks_vt_log_enabled` (`zks_log.c`) | `FfiHost::log_enabled` |
| `zks_vt_conn_permute_disabled`, `zks_vt_rng_random`, `zks_vt_rng_drand48`, `zks_vt_rng_reseed` (`zks_rng.c`) | `FfiHost` leaves |
| `zks_vt_resolve` (`zks_resolve.c`) | `FfiHost::resolve` |

C to C (across units):

| Caller (file) | Callee (file) |
|---|---|
| sync wrappers (`zks_sync.c`) | exported `zoo_a*` (`zks_async.c`, through the PLT), `zks_aremove_watches` (`zks_async.c`) |
| `zoo_amulti` (`zks_multi.c`) | `zks_req_new`, `zks_submit_prepared` (`zks_async.c`) |
| `zks_submit_prepared` (`zks_async.c`) | `zks_drain` (`zks_drain.c`) for the log items |
| `zks_drain` (`zks_drain.c`) | `zks_log_emit` (`zks_log.c`), `zks_multi_complete` (`zks_multi.c`), `zks_sync_complete` (`zks_sync.c`), `zks_thread_item` (`zks_threads.c`), `zoo_lock_auth` (`zks_compat.c`), `zks_req_free` (`zks_async.c`) |
| `zks_init_common` (`zks_handle.c`) | `zks_log_env` (`zks_env.c`), `zks_start_threads` (`zks_threads.c`), `ZKS_LOG` (`zks_log.c`) |
| `zoo_get_current_server` (`zks_handle.c`) | `zks_format_endpoint` (`zks_resolve.c`) |
| `zks_log_emit` (`zks_log.c`) | `zks_format_endpoint` (`zks_resolve.c`) |
| `zks_aremove_watches` (`zks_async.c`) | `zks_sync_complete_local` (`zks_sync.c`, under `THREADED`) |
| `zks_vt_discard_token` (`zks_drain.c`) | `zks_sync_complete_closing` (`zks_sync.c`), `zks_req_free` (`zks_async.c`) |

The exported functions call each other as the legacy functions do. An exported caller calls the exported callee, so an interposed symbol sees the same calls as with legacy.

## 3. The parts of the library

Each part lists its files, the legacy sources that define its behavior, its functions, its invariants and the tests that a change needs.

### Writers

Files: `zookeeper-client-rust/crates/zk-ffi/src/abi/raw/writers.rs`, `zookeeper-client-rust/crates/zk-ffi/src/abi/raw/tests.rs`.

Sources: zookeeper.c `deserialize_response`, `deserialize_multi`, `process_sync_completion`; zookeeper.jute.c `deserialize_Stat`, `deserialize_String_vector`, `deserialize_ACL_vector`.

Functions:

| Function | File |
|---|---|
| `c_malloc`, `c_calloc` | writers.rs |
| `write_stat(dst, s)` | writers.rs |
| `write_result(v, d)` | writers.rs |
| `write_path(s) -> *mut c_char` | writers.rs |
| `write_multi(v, results, op_kind, count)` | writers.rs |
| `sync_copy_out(s, d)` | writers.rs |

Calls made: `libc` (through `c_malloc` and `c_calloc`), the mirrors of `legacy.rs`.

Calls received: `results.rs` (`zkrs_write_result`, `zkrs_write_path`, `zkrs_write_multi`, `zkrs_sync_copy_out`), and so `zks_deliver_result` and `zks_deliver_watch` (`zks_drain.c`), `zks_multi_complete` (`zks_multi.c`) and the sync wrappers (`zks_sync.c`).

Invariants:

- Every object that the application or `deallocate_*` frees comes from `c_malloc` or `c_calloc`. A NULL from a non-zero size aborts.
- `write_stat` writes field by field. `zkrs_stat` and `struct Stat` need not have the same layout.
- A NULL destination member means "no output". Write nothing to it.
- A DATA result with a NULL znode gives `value == NULL` and length -1. An empty znode gives a non-NULL zero-length buffer, as legacy.
- `write_path` never returns NULL.
- `sync_copy_out` follows the legacy truncation formulas exactly, also for a negative capacity. It writes the caller buffers only when `rc == ZOK`, except the multi per-op writers.
- The writers read only the view. They hold no handle and no lock.

Divergences from legacy:

- A failed C allocation for a result object aborts the process. `out_of_memory` first writes `zk-ffi: out of memory allocating N bytes` to stderr. The message formats only an integer, so the write needs no heap memory.
- Legacy does not check most of these allocations. A failed string or buffer allocation gives the completion the server return code with a NULL or partial result. A failed vector allocation makes legacy dereference a NULL pointer.

### Handle and host

Files: `zookeeper-client-rust/crates/zk-ffi/src/abi/handle.rs`, `zookeeper-client-rust/crates/zk-ffi/src/abi/host.rs`, `zookeeper-client-rust/crates/zk-ffi/src/abi/tokens.rs`.

Sources: zk-driver (the mt and st drivers, the init and the lifecycle); zookeeper.c `zookeeper_init_internal`, `zookeeper_close`, `api_prolog`, `api_epilog`; mt_adaptor.c `do_io`, `do_completion`, `adaptor_finish`.

Functions:

| Function | File |
|---|---|
| the `Inner` methods, `role_of`, `step_out`, `require_driver`, `require_mt`, `test_support` | handle.rs |
| `zkrs_handle_new`, `zkrs_handle_abandon`, `zkrs_handle_retain`, `zkrs_handle_release` | handle.rs |
| `zkrs_thread_enter`, `zkrs_thread_exit` | handle.rs |
| `zkrs_io_step`, `zkrs_wait_dispatch` | handle.rs |
| `zkrs_dispatch_ended` | handle.rs |
| `zkrs_interest`, `zkrs_process`, `zkrs_interest_resume`, `zkrs_last_errno` | handle.rs |
| `zkrs_close`, `zkrs_resume` | handle.rs |
| `FfiHost` and its leaves, with `HostLeaves::resolve` | host.rs |
| `CTokens`, `CReq`, `CWatcher` | tokens.rs |

Calls made: `raw::{read_struct, cstr_opt, cstr_secret, bytes, handle_into_raw, handle_arc, write_out, vt_resolve, c_bytes, sink_from_ptr}`, `with_outbox`, `zk_driver::{Init, Shared, Polled, Role, Step}` (`Init::new`, `Init::step`, `Init::abandon`, `Shared::from_init`, `Polled::from_init`, `thread_enter`, `thread_exit`, `io_step`, `wait_dispatch`, `resume`, `close`, `interest_exported`, `process_exported`), `FfiHost::zero`, `tls::setup`, `sasl::setup`.

Calls received: `zks_init_common`, `zks_release`, `zookeeper_close`, `zookeeper_interest`, `zookeeper_process` (`zks_handle.c`), `zks_drive` (`zks_drain.c`), the loops of `zks_threads.c`. Every other Rust module calls the `Inner` methods.

Invariants:

- `zkrs_handle_new` copies every input before it returns. C may free the strings at once. A chroot parse error leaves `*out` NULL. Otherwise `*out` is set before the first barrier.
- A build without the `tls` feature rejects `a->tls == 1` with `EINVAL`. A build without the `sasl` feature rejects `a->sasl == 1` in the same way. The flavor selects the driver mode and never changes `Compat` (`Compat::LEGACY_C`).
- A failed init after `*out` is set ends only with `zkrs_handle_abandon`.
- The final close takes every leftover token out of the driver, calls `FfiHost::zero`, and returns `ZKRS_RELEASE`. No leaf call follows it.
- `zkrs_interest` on an mt handle writes `{fd = -1, interest = 0, timeout_ms = recv_timeout / 3}` and `*written = 1`. `zkrs_process` on an mt handle gives ZINVALIDSTATE. `zkrs_interest_resume` aborts on an mt handle.
- On an mt handle, the role of `zkrs_close` decides the path: an application thread runs the full close, the IO or dispatch thread runs the deferred close.
- On an mt handle, the close mark follows every result that the driver decoded before the close, also for a close from an IO callback. The dispatch thread leaves only after the mark. In a close from an application thread, the mark also follows the results of the final flush and `destroy`. In a close from a dispatch callback, the dispatch thread delivers those results at its exit. The closing application thread delivers no async completion. It runs only the auth completions that `destroy` fails, as zookeeper.c `free_completions` does.
- Each accepted completion token has exactly one end: the dispatch thread delivers it. A refused submit gives the token back to C. A discard of an accepted token is a lost completion, so the final close discards no accepted token. The one exception is a close from an IO callback. There, the final close runs after the dispatch thread exits, and the results of `destroy` go to `discard_token` with no callback, as zookeeper.c `destroy` does.
- `resolve` holds no lock in the vtable call. It maps `(errno, gai_rc)` to `ResolveError` and keeps the address order. It logs nothing.

Loom models (in `abi/loom_models.rs`): a foreign close that joins the IO and dispatch threads; a close from an IO callback that races a submit, with detach and the final close on the IO thread. Each model checks the end of every token and the exit of the dispatch thread after the mark.

### Submit and multi

Files: `zookeeper-client-rust/crates/zk-ffi/src/abi/submit.rs`, `zookeeper-client-rust/crates/zk-ffi/src/abi/multi.rs`.

Sources: the zk-proto `Request`, `InputDefect` and `SubmitError` contracts; zookeeper.c `zoo_a*`, `zoo_add_auth`, `zoo_amulti`, `aremove_watches`.

Functions:

| Function | File |
|---|---|
| `kind_of`, `zkrs_validate_ttl` | submit.rs |
| `build_request(h, rq, token, delivery) -> Request<CTokens>` | submit.rs |
| `data_of(rq) -> Option<&[u8]>` (the borrowed create or set data value) | submit.rs |
| `status_of(e) -> zkrs_status` | submit.rs |
| `defects_of(rq, token) -> InputDefect` | submit.rs |
| `zkrs_submit`, `zkrs_add_auth`, `zkrs_add_auth_check` | submit.rs |
| `multi_ops(rq) -> Vec<MultiOpRef<'_>>`, `multi_op(op) -> MultiOpRef<'_>` (borrowed sub-ops that live as long as `rq`) | multi.rs |

Calls made: `raw::{read_struct, cstr_opt, cstr_ref, cstr_secret, value_ref, array, bytes}`, `CReq::new`, `CReq::into_raw`,
`CWatcher::from_request`, `FfiHost::discard_fn`, `require_driver`, `with_outbox`, the `Driver` methods `submit`, `add_auth` and `state`, `zk_proto::check_ttl`.

Calls received: `zks_submit_prepared`, `zoo_add_auth`, `zks_aremove_watches` (`zks_async.c`).

Invariants:

- `zkrs_submit` never delivers a callback. It can queue log items in `ob`.
- `ZKRS_OK` retains the token, and the driver delivers it exactly once. `ZKRS_OK_LOCAL` does not retain it. Every other status gives it back with `CReq::into_raw`, so no `discard_token` call happens: C frees the request itself.
- C passes the legacy `struct ACL_vector` of a create or a set ACL as it is. `acl_of_vec` reads it through the `CAclVector` mirror once.
- C passes the `zoo_op_t` array of a multi as it is. The union of an op holds only the member of its type, so `multi_ops` makes no reference to a `CZooOp`. It copies each field that it uses through a raw pointer at the offset of the field. It never reads `create_op.ttl`.
- Input defects (a bad value buffer, a bad ACL vector, a NULL op array, token 0) become `InputDefect` flags. They never change the order of the return codes.
- `zkrs_add_auth_check` takes no lock and uses no outbox.
- The credential of `zkrs_add_auth` goes into `SecretBytes`. A NULL `cert` with `cert_len > 0` is an empty credential.
- `multi_ops` reads exactly `ops_len` ops. An op type outside {1, 19, 2, 5, 13} is the last op of the prefix.
- `multi_ops` borrows each sub-op path and value from C memory. zk-proto encodes them into the owned request frame inside `zkrs_submit`, and no view outlives that call.

### Outbox, logging and accessors

Files: `zookeeper-client-rust/crates/zk-ffi/src/abi/outbox.rs`, `zookeeper-client-rust/crates/zk-ffi/src/abi/logging.rs`, `zookeeper-client-rust/crates/zk-ffi/src/abi/accessors.rs`.

Sources: the zk-driver outbox and FIFO; the zk-proto host list and resolution; zookeeper.c `zoo_state`, `zoo_recv_timeout`, `zoo_set_servers`, `zoo_cycle_next_server`, `zoo_get_current_server`, `zookeeper_get_connected_host`.

Functions:

| Function | File |
|---|---|
| `FfiOutbox::new`, `FfiOutbox::keep`, `with_outbox`, `take_driver_outbox` | outbox.rs |
| `zkrs_outbox_next`, `zkrs_outbox_finish`, `zkrs_outbox_requeue` | outbox.rs |
| The result, watch and watcher-list storage of `Staged` | outbox.rs |
| `now`, `level_code`, `LogViews::clear` | logging.rs |
| `site_id(site) -> u32` | logging.rs |
| `LogViews::stage(recs, stamp)` | logging.rs |
| `zkrs_state`, `zkrs_recv_timeout` | accessors.rs |
| `zkrs_set_servers`, `zkrs_set_resolution_delay`, `zkrs_cycle_next_server` | accessors.rs |
| `zkrs_current_server`, `zkrs_peer_addr` | accessors.rs |

Calls made: `raw::{outbox_take, outbox_from_impl, outbox_into_impl, c_bytes, write_out}`, `Outbox::{pop, finish, requeue, resume_at}`, `zk_driver::outbox::discard_item`, the `Driver` methods `requeue`, `state`, `recv_timeout`, `set_servers`, `peer_addr`, `read_conn` and `with_conn`, `Connection::{set_resolve_delay, cycle_next_server}`, `CReq::into_raw`, `LogSite::ALL`.

Calls received: `zks_drain` (`zks_drain.c`) for the outbox exports; `zks_handle.c`, `is_unrecoverable` (`zks_errors.c`) and
`zookeeper_get_connected_host` (`zks_resolve.c`) for the accessors. `handle.rs` uses `with_outbox`.

Invariants:

- A view stays valid until the next call on the same outbox. Every pointer in a view points into `Staged`. Build all elements before you take a pointer, so no vector grows after it.
- `zkrs_outbox_next` gives the items in the FIFO order of the driver. `WatchTerminated` items go to `discard_item` and C never sees them.
- A RESULT item gives its token to C with `CReq::into_raw`. The view carries `CReq::kind`.
- `zkrs_outbox_finish` keeps the empty box when a resume tag is pending. Otherwise it frees the box and sets `impl` to NULL.
- `zkrs_outbox_requeue` gives every untaken item and the tag back to the driver. The next driving call delivers them first.
- `site_id` is the index in `LogSite::ALL`. The generated `zks_log_sites.h` uses the same order.
- `zkrs_state` gives every legacy state value, including 0, 7, 999, -112 and -113. `zkrs_peer_addr` gives -1 unless the state is CONNECTED.
- `zkrs_set_servers` with a NULL `hosts` gives ZBADARGUMENTS and the `SetServersEmpty` line. It returns `ZKRS_MORE` at the log-flush barrier before a Blocking `resolve`.

### Async entry points and multi (C)

Files: `zookeeper-client-rust/c/shim/zks_async.c`, `zookeeper-client-rust/c/shim/zks_multi.c`.

Sources: zookeeper.c `zoo_a*`, `zoo_add_auth`, `aremove_watches`, `zoo_amulti`, `zoo_create_op_init` and the other op inits.

Functions:

| Function | File |
|---|---|
| `zks_sync_marker`, the static `zks_funnel`, the static `zks_create_null_handle` | zks_async.c |
| `zks_req_new`, `zks_req_free` | zks_async.c |
| `zks_submit_prepared` (`zks_submit_raw`, `zks_submit_req`) | zks_async.c |
| `zks_aremove_watches` | zks_async.c |
| every `zoo_a*` function, `zoo_add_auth`, `zoo_aremove_watches`, `zoo_aremove_all_watches` | zks_async.c |
| `zoo_amulti` (the call-time writes and the op prefix length), the five `zoo_*_op_init` functions | zks_multi.c |
| `zks_multi_complete` | zks_multi.c |

Calls made: `zkrs_submit`, `zkrs_add_auth`, `zkrs_add_auth_check`, `zkrs_validate_ttl`, `zkrs_write_multi`, `zks_drain` (`zks_drain.c`), `zks_sync_complete_local` (`zks_sync.c`), `zks_watch_arg` (reads the cell with `zks_watch_cell_read`).

Calls received: the application; the sync wrappers (`zks_sync.c`) through the exported `zoo_a*` functions and `zks_aremove_watches`; `zks_drain` for `zks_multi_complete` and `zks_req_free`.

Invariants:

- The return code follows the legacy stage order. Every status goes through `zks_funnel`.
- C frees the `zks_req` for every status other than `ZKRS_OK`. With `ZKRS_OK` the request belongs to Rust until delivery.
- A completion of `ZKS_SYNC_MARKER` gives mode `ZKS_REQ_SYNC`, and `data` is the `struct zks_sync *`.
- `zks_submit_prepared` drains the log items of the submit in the calling thread before it returns.
- The create and set ACL entry points put the `struct ACL_vector` pointer of the caller into the request as it is. C copies no entry.
- `zoo_add_auth` holds `auth_lock` (through the exported `zoo_lock_auth`) around the whole `zkrs_add_auth` call.
- `zoo_amulti` writes the call-time results as legacy does. It passes the `zoo_op_t` array of the caller, and `ops_len` counts the ops up to and including the first unsupported type. C copies no op.
- The exported functions call each other as in section 2.5.

### Handle, threads and drain (C)

Files: `zookeeper-client-rust/c/shim/zks_handle.c`, `zookeeper-client-rust/c/shim/zks_threads.c`, `zookeeper-client-rust/c/shim/zks_drain.c`.

Sources: zookeeper.c `zookeeper_init_internal`, `zookeeper_close`, `zookeeper_interest`, `zookeeper_process`, `process_completions`, `deliver_completion`, `cleanup_bufs`, the accessors; mt_adaptor.c `start_threads`, `do_io`, `do_completion`, `adaptor_finish`.

Functions:

| Function | File |
|---|---|
| the thread-role variables, `zks_vtable`, `zks_null_watcher`, `zks_secure_zero` | zks_handle.c |
| `zks_vt_snapshot_default_watcher`, `zks_vt_store_client_id` | zks_handle.c |
| `zookeeper_init`, `zookeeper_init2`, `zks_init_common` (the legacy init order) | zks_handle.c |
| `zks_release` | zks_handle.c |
| `zookeeper_close`, `zookeeper_interest`, `zookeeper_process` | zks_handle.c |
| `zoo_state`, `zoo_recv_timeout`, `zoo_client_id`, `zoo_get_context`, `zoo_set_context`, `zoo_set_watcher` | zks_handle.c |
| `zoo_get_log_callback`, `zoo_set_log_callback` | zks_handle.c |
| `zoo_set_servers`, `zoo_set_servers_resolution_delay`, `zoo_cycle_next_server`, `zoo_get_current_server` | zks_handle.c |
| `ZKS_REGISTRY_ADD`, `ZKS_REGISTRY_REMOVE`, `ZKS_REGISTRY_CHECK` | zks_internal.h |
| `zks_latch_init`, `zks_latch_arrive_and_wait`, `zks_latch_destroy` | zks_threads.c |
| `zks_start_threads`, the IO and dispatch loops, `zks_thread_item` | zks_threads.c |
| `zks_drain`, `zks_drive`, `zks_vt_discard_token` | zks_drain.c |
| the static `zks_deliver_result` and `zks_deliver_watch` | zks_drain.c |

Calls made: `zkrs_handle_new`, `zkrs_handle_abandon`, `zkrs_handle_retain`, `zkrs_handle_release`, `zkrs_thread_enter`, `zkrs_thread_exit`, `zkrs_io_step`, `zkrs_wait_dispatch`, `zkrs_dispatch_ended`, `zkrs_interest`, `zkrs_process`, `zkrs_close`, `zkrs_resume`, the accessors of `accessors.rs`, `zkrs_outbox_next`, `zkrs_outbox_finish`, `zkrs_outbox_requeue`, `zkrs_write_result`, `zkrs_write_path`; `zks_log_env`, `zks_log_emit`, `ZKS_LOG`, `ZKS_LOG_CB`, `zks_format_endpoint`; `zks_multi_complete`, `zks_req_free`; `zks_sync_complete`, `zks_sync_complete_closing`; `zoo_lock_auth`, `zoo_unlock_auth`; pthreads.

Calls received: the application; Rust through the vtable; every C unit through `zks_drain` and `zks_drive`.

Invariants:

- `zookeeper_init` returns only a ready handle. On a failure it calls `zkrs_handle_abandon`, frees `zh`, sets `errno` as legacy and returns NULL.
- Each library thread holds one handle reference (`zkrs_handle_retain` before the thread starts). The start latch opens only after both threads run.
- The handle rule of section 1.2 applies everywhere. After `ZKRS_RELEASE`, `zks_drive` drains and then calls `zks_release`. Nothing reads `zh` after that.
- `zks_drain` runs every callback in a C frame. A cleanup handler calls `zkrs_outbox_requeue` when a callback unwinds. The outbox of the dispatch loop is the exception (`zks_tls_dispatch_ob`). Its unwind ends the thread, so the drain does not requeue it.
- A `pthread_cleanup_push` handler covers the dispatch loop. When a callback ends the thread, for example with `pthread_exit`, it calls `zkrs_dispatch_ended` and then `zkrs_handle_release`. The core frees the untaken items and wakes a waiting close. The joining close gives back the lifecycle reference of the thread.
- `zks_deliver_result` calls the completion of `zks_req.kind` with the legacy arguments. A sync request goes to `zks_sync_complete`. A multi goes to `zks_multi_complete`. A completion that is NULL is skipped, but the objects are still freed as legacy does.
- `zks_deliver_watch` frees the path from `zkrs_write_path` after the watcher returns. The NULL watcher sentinel is never called.
- `zks_vt_discard_token` makes no call into Rust and runs no application code. A sync request ends its wait with ZCLOSING.
- The THREAD items: JOIN joins the thread of the role, DETACH detaches the calling thread. Rust never waits for a thread.
- `zoo_set_watcher` and `zoo_set_context` store only their own atomic pointer. A reader can see an old watcher with a new context, as in legacy. `zoo_get_context` and `zoo_set_context` take no lock, so a signal handler can call them in release builds. `zoo_get_log_callback` and `zoo_set_log_callback` use relaxed atomics.
- The registry macros call Rust only under `ZKS_HANDLE_REGISTRY`, and are empty otherwise. zk-ffi always compiles `zkrs_registry_add`, `zkrs_registry_remove` and `zkrs_registry_check`. The set is a `Mutex<HashSet<usize>>` of handle addresses, and a poisoned lock stays usable. A failed check writes the legacy message once to stderr and calls `std::process::abort`.

### Sync wrappers (C)

Files: `zookeeper-client-rust/c/shim/zks_sync.c`.

Sources: `zks_aremove_watches` with `sync = 1`; mt_adaptor.c `alloc_sync_completion`, `wait_sync_completion`, `notify_sync_completion`; the sync wrappers of zookeeper.c.

Functions:

| Function | File |
|---|---|
| `zks_sync_complete`, `zks_sync_complete_local`, `zks_sync_complete_closing` | zks_sync.c |
| the static `zks_sync_new`, `zks_sync_wait`, `zks_sync_unref`, `zks_sync_src` helpers, and the wrapper steps `zks_sync_begin`, `zks_sync_finish`, `zks_sync_end` and `zks_sync_end_data` | zks_sync.c |
| the 23 sync wrappers | zks_sync.c |
| the static `zks_wgetconfig_`, `zks_wget_children_`, `zks_wget_children2_`, `zks_remove_watches` | zks_sync.c |

Calls made: the exported `zoo_a*` functions and `zks_aremove_watches` (`zks_async.c`), `zkrs_write_result`, `zkrs_sync_copy_out`, `zks_current_role`, `ZKS_REGISTRY_CHECK`, pthreads, `deallocate_*`.

Calls received: the application; `zks_deliver_result` and `zks_vt_discard_token` (`zks_drain.c`); `zks_aremove_watches` (`zks_async.c`).

Invariants:

- The IO thread gets ZSYSTEMERROR with `errno = EDEADLK` before anything is queued. Legacy C deadlocks here.
- The record has two references: the waiter and the completer. The completer touches nothing after its unreference. A cancelled waiter drops its reference in a cleanup handler.
- The completion writes only record members. The caller buffers are written only by the waiting thread, through `zkrs_sync_copy_out`, and only when `rc == ZOK` (except the multi per-op writers).
- The vectors pass to the caller by struct assignment. A NULL output pointer frees them with `deallocate_*`.
- `zoo_getconfig` and `zoo_wgetconfig` use `zks_wgetconfig_`, so they read the absolute config node under a chroot.
- The whole file is under `THREADED`.

### Log, environment, errors, resolve and RNG (C)

Files: `zookeeper-client-rust/c/shim/zks_log.c`, `zookeeper-client-rust/c/shim/zks_env.c`, `zookeeper-client-rust/c/shim/zks_errors.c`, `zookeeper-client-rust/c/shim/zks_resolve.c`, `zookeeper-client-rust/c/shim/zks_rng.c`, `zookeeper-client-rust/c/shim/zks_layout_check.c`.

Sources: zk_log.c; zookeeper.c `log_env`, `format_endpoint_info`, `resolve_hosts`, `zookeeper_get_connected_host`, `is_unrecoverable`, `zoo_deterministic_conn_order`; the `rng` code of zookeeper.c; `zookeeper-client-rust/c/tests/abi/log-sites.tsv`.

Functions:

| Function | File |
|---|---|
| `zoo_get_log_stream`, `zoo_set_log_stream`, `zoo_set_debug_level` | zks_log.c |
| `log_message` (the prefix, the per-thread buffers, the sink) | zks_log.c |
| `zks_vt_log_enabled` | zks_log.c |
| `zks_log_emit` (the pinned format of a core site) | zks_log.c |
| `zks_log_site`, `zks_log_site_cb` (the C sites of `enum zks_c_site`) | zks_log.c |
| `zks_log_env` | zks_env.c |
| `zerror`, `zoo_version_str`, `is_unrecoverable` | zks_errors.c |
| `zks_vt_resolve`, `zks_format_endpoint`, `zookeeper_get_connected_host` | zks_resolve.c |
| `zks_vt_conn_permute_disabled`, `zks_vt_rng_random`, `zks_vt_rng_drand48`, `zks_vt_rng_reseed`, `zoo_deterministic_conn_order` | zks_rng.c |
| the size and offset checks of every `zkrs.h` type | zks_layout_check.c |

Calls made: `zkrs_state` (`is_unrecoverable`), `zkrs_peer_addr` (`zookeeper_get_connected_host`), libc (`getaddrinfo`, `random`, `drand48`, `srandom`, `srand48`, `gettimeofday`, `uname`, `getpwuid`).

Calls received: Rust through the vtable (`log_enabled`, `resolve`, the RNG entries); `zks_drain` for `zks_log_emit`; `zks_init_common` for `zks_log_env` and `ZKS_LOG`; `zoo_get_current_server` for `zks_format_endpoint`; the application.

Invariants:

- `log_message` gives the legacy line byte for byte, with the legacy prefix, the thread id and the time format of zk_log.c.
- The level rule is the legacy `LOG_*` macro rule over `logLevel`. DEBUG needs equality.
- `zks_log_emit` checks the level again, renders the pinned format of the site and uses the per-handle callback when one is set.
- The C sites use the `func`, `line` and format of their `zookeeper-client-rust/c/tests/abi/log-sites.tsv` rows. The `C_SITES` list of `xtask/src/logsites.rs` names the row of each `enum zks_c_site` key.
- The vtable entries run under `zks_in_vtable` and call no `zkrs_*` function. `zks_vt_resolve` keeps the address order of `getaddrinfo` and reports each skipped family.
- `zks_format_endpoint` returns a per-thread buffer, as legacy.
- `zks_vt_rng_reseed` seeds `srandom` and `srand48` as legacy does.

### TLS and SASL

Files: `zookeeper-client-rust/crates/zk-ffi/src/abi/tls.rs`, `zookeeper-client-rust/crates/zk-ffi/src/abi/sasl.rs`,
`zookeeper-client-rust/c/shim/zks_tls.c`, `zookeeper-client-rust/c/shim/zks_sasl.h`, `zookeeper-client-rust/c/shim/zks_sasl_cb.h`, `zookeeper-client-rust/c/shim/zks_sasl_cyrus.c`, `zookeeper-client-rust/c/shim/zks_sasl_cb.c`.

The `tls` feature builds the TLS configuration of the drop-in on rustls. No drop in build links OpenSSL. The `sasl` feature enables the SASL item flow. Cyrus SASL runs in the C glue, never in Rust. The module doc comments of `tls.rs` and `sasl.rs` give the contracts. The shim README ("TLS and SASL") gives the build rules.

Invariants:

- `zkrs_handle_new` calls `tls::setup` for each handle with `a->tls == 1`. A bad file never fails the init. It fails each connect attempt, as legacy does.
- The drain gives each SASL item to the Cyrus glue. The glue gives the Cyrus output back with `zkrs_sasl_result`. A stale generation is ignored.
- Only the two Cyrus glue units see `HAVE_CYRUS_SASL_H`. No shim unit sees `HAVE_OPENSSL_H`.

Divergences from legacy, where OpenSSL opens any path and accepts any positive PBKDF2 iteration count:

- CA, certificate and key files must be regular files of at most 32 MiB (`MAX_FILE` in zk-driver `src/net/tls/connector.rs`). The client checks the file type before and after the open. Thus a FIFO, a device or a directory that is in place at the check does not block the I/O thread. On unix the open does not wait for a FIFO writer, so a path that changes to a FIFO after the first check also fails at once.
- A rejected certificate or key candidate falls through to the next candidate path, and the load fails when no candidate is left. Today every drop-in path has one candidate, so a rejected certificate or key fails the load directly.
- For the CA file, only a missing candidate falls through. A rejected CA candidate fails the load.
- A rejected default trust file is skipped. A rejected file in a hashed directory ends the scan of its hash, as a missing file does.
- An encrypted PKCS#8 key with a PBKDF2 iteration count outside 1 to 10,000,000 (`PBKDF2_MAX_ITER` in zk-driver `src/net/tls/identity.rs`) is rejected.
- Each rejected CA, certificate or key fails the connect attempt and not the init, as a bad file does in legacy.

## 4. Dependencies between the parts

The parts depend on the signatures of section 2.5, not on the bodies.

- The Rust parts build and test alone, with the stand-in vtable.
- The C parts compile alone with the C checks of section 1.3.
- An end-to-end run needs init, threads and drain (`handle.rs`, `outbox.rs`, `zks_handle.c`, `zks_threads.c`, `zks_drain.c`). A request also needs submit (`submit.rs`, `zks_async.c`). A result also needs the writers. The sync API also needs `zks_sync.c`. The log lines need `logging.rs` and `zks_log.c`.

## 5. Generated files

- `SH/zkrs.h`: cbindgen writes it from the `repr(C)` types and the exports of zk-ffi. Regenerate it after a change to an export or to a doc comment of an export:
  `(cd RS && cbindgen --config cbindgen.toml --crate zk-ffi --output ../../c/shim/zkrs.h)`.
  `--verify` fails when the file is out of date.
- `SH/zks_log_sites.h`: `cargo xtask log-sites header` writes it from `zookeeper-client-rust/c/tests/abi/log-sites.tsv` and `LogSite::ALL`. The order of `ZKS_CORE_SITES` equals `site_id`. `ZKS_C_SITES` has the C sites in the order of `enum zks_c_site`. `cargo xtask log-sites header --check` fails when the file is out of date.
- `c/tests/abi/layout/probe.c` and `<target>.expect`: `cargo xtask layout gen` and `cargo xtask layout expect` write them. `zookeeper-client-rust/c/tests/abi/layout_check.c` checks the public headers against the expect file. `SH/zks_layout_check.c` pins the `zkrs.h` layouts at compile time.
- The `zk-ffi-layout` crate (`zookeeper-client-rust/crates/zk-ffi-layout`) is the cross-language check. It compiles a C probe against the public headers and `SH/zkrs.h` in the `plain`, `ssl`, `sasl` and `full` configurations. It compares the size, the alignment and each field of the legacy mirrors and of the `zkrs.h` types with rustc. It also compares the enum values and the constants of `zkrs.h`, the result codes and the operation codes. It reads the member lists of the public types from `probe.c`, so run `cargo xtask layout gen --check` first.

Before you finish a change, run the ABI checks of the shim README ("Checks") on the built libraries.

## 6. The st flavor and the static archives

### 6.1 Scope

The single threaded flavor and the static archives give:

- `libzookeeper_st.2.dylib`, with exactly the 178 st plain rows of `abi/symbols.tsv`, install name and versions as for mt (3.0.0);
- `libzookeeper_mt.a` and `libzookeeper_st.a` from autotools, and the CMake static `zookeeper` target of the flavor that `WANT_SYNCAPI` selects. Each archive has one member, the pre-linked object of the flavor, and defines exactly the globals of `symbols.tsv` and `static-symbols.tsv`;
- the CMake `hashtable` archive with no globals;
- `cli_st`, from the unchanged `src/cli.c` without `THREADED`;
- the st semantics: the caller polls, callbacks run on the caller thread, and there is no sync API.

The autotools and CMake projects in `zookeeper-client-c` build the legacy client. The drop-in projects in `zookeeper-client-rust/c` build the Rust core. On Linux, the autotools and CMake builds make ELF libraries with a version script. Keep the platform code paths behind `cfg` or `#ifdef`.

### 6.2 One staticlib for both flavors

- One zk-ffi staticlib serves both flavors. `zkrs_init_args.flavor` selects the driver of each handle at run time. `ZKRS_FLAVOR_ST` gives `Driver::St(Arc<Polled<CTokens>>)` with `DriverMode::ST` and `Flavor::St`.
- The `Driver` facade in `RS/src/abi/handle.rs` covers both drivers: `outbox`, `requeue`, `state`, `recv_timeout`, `submit`, `add_auth`, `set_servers`, `peer_addr`, `read_conn` and `with_conn`. The submit, accessor and outbox code use only the facade, so they serve both flavors.
- The mt-only exports (`zkrs_thread_enter`, `zkrs_thread_exit`, `zkrs_io_step`, `zkrs_wait_dispatch`, `zkrs_dispatch_ended`) abort on an st handle through `require_mt`. C never makes these calls in st.
- `zkrs_interest`, `zkrs_process`, `zkrs_close`, `zkrs_resume` and `zkrs_interest_resume` dispatch to the st paths. `zkrs_process` and `zkrs_close` call `Polled` directly in their `drive` closures.
- zk-driver: `Polled::read_conn` and `Polled::with_conn`. `with_conn` does not wake anything. The next driving call of the application routes the outputs.
- The shim compiles in both flavors. `zookeeper_interest` has the resume loop. `ZKS_HOLD_SCOPE` marks the scopes that run application callbacks (section 6.5).
- `dev/build-dropin.sh` builds both flavors: the dylib, the one-member archive and `cli_FL` for each flavor, with the export, private-symbol and member checks, a smoke test against the dylib and the archive, and `ABI_CHECK=1` for `cargo xtask abi check` and `cargo xtask abi static`.

### 6.3 The st item flow and the frame rules

The st driver is `zk_driver::Polled`. It owns the stage of each driving call, so zk-ffi keeps no state across calls.

- **Barriers.** A Polled step returns `More` at each `Session` output, `DrainPoint` and auth lockpoint. C drains the outbox with `zks_drain`. Then C calls `zkrs_interest_resume` (in `zookeeper_interest`) or `zkrs_resume` (in `zks_drive`). Polled
  continues the stage that the handle records.
- **Inline callbacks.** The application callbacks run in `zks_drain`, on the caller thread, inside `zookeeper_interest`, `zookeeper_process` or `zookeeper_close`. When a callback runs, no Rust frame is on the stack and no Rust lock is held.
- **Nested calls.** A callback can call any API of the same handle. The nested call has its own outbox, a C local. Polled defines the result of a nested driving call. zk-ffi passes the step and the rc through.
- **Submit.** `Polled::submit` runs one flush pass after the queue step, and it forces a close when the flush fails. An async call never runs an application callback. Its outbox holds only LOG items, and `zks_submit_req` drains them. The next driving call delivers the results of a forced close.
- **Close.** A close from a callback queues the ZCLOSING fakes and returns. The epilog of the enclosing driving call runs the final close: `More`, then `Release` with the close rc. After `Release`, C calls `zks_release` and does not read `zh` again. `zookeeper_interest` writes its outputs to caller memory also after a release.
- **Interest outputs.** The step that reaches `finish_interest` sets `InterestOut::written`. That step can be `More`, because items of the step wait in the outbox, and the resume does not write the outputs again. So any step writes `*out` and `*written = 1` when `InterestOut::written` is true. Otherwise `Done` or `Release` writes `*written = 0`, so C keeps the caller values, and `More` writes nothing. `zkrs_interest_resume` reads its start value from the C locals `*out` and `*written`. The fd is the real socket, or -1 after a close.
- **Abandoned calls.** A callback that unwinds out of a driving call leaves the stage of that call stopped. C requeues the outbox. The next interest or process call completes the stopped stage first, then runs its own step under the same ref (`Polled::st_begin`).
- **No threads.** st has no THREAD items, no sync records and no `zks_sync.c` or `zks_threads.c`. `zoo_lock_auth` is a no-op, so the AUTH_LOCKPOINT item does nothing.
- **Log lines.** The st prefix has no `(0x...)` thread id and uses the full header size (zk_log.c, `zks_log.c`). The core lines of `zoo_cycle_next_server` and of `zoo_set_servers_resolution_delay` go out in the next driving call of the application, as the mt lines go out through the IO thread.

### 6.4 Differences between st and mt

| Topic | mt | st |
|---|---|---|
| Driver | `Shared` with the IO and dispatch threads | `Polled`, called by the application |
| `zookeeper_interest` | fd -1, interest 0, tv recv_timeout/3, never `More` | real fd, bits and timeout; barriers through `zkrs_interest_resume` |
| `zookeeper_process` | ZINVALIDSTATE | one IO pass with the exact reads of legacy C |
| Callbacks | on the dispatch thread | inline in `zks_drain` on the caller thread |
| Submit | queue, wake the IO thread | queue, one flush pass, forced close on a flush failure |
| Close | role-based path | `Polled::close`; deferred when called from a callback |
| Sync API | `zks_sync.c`, 23 rows | not built, not exported |
| THREAD items | yes | none |
| Log prefix | `pid(0x...)` | `pid` only |
| Cancel mask | full scope (section 6.5) | calls into Rust only (section 6.5) |
| Link | `-lpthread` | nothing on macOS (libpthread on glibc older than 2.34) |
| Exports (plain) | 201 | 178 |

### 6.5 The cancel mask in st

Each call into Rust runs with a masked cancel state in both flavors (`ZKS_CALL` through `zks_rust_enter`). A cancel inside a Rust frame can leave a Rust lock or a Rust state half changed. A scope that runs application callbacks (`zks_drain`, `zks_drive`, `zks_submit_req` with its drain, `zookeeper_close`) uses `ZKS_HOLD_SCOPE`. In mt it masks the full scope. In st it is empty: no Rust frame and no Rust lock exist while an st callback runs, so a cancel in a callback leaves no Rust state broken. The callbacks keep the cancel state of legacy st. `zks_init_common` keeps `ZKS_CANCEL_SCOPE` in both flavors.

`zks_cancel_masked` has three states. OFF means that the shim did not change the cancel state. SCOPED means that an outer shim frame disabled cancellation and restores it on exit. PINNED means that the IO or dispatch thread disabled cancellation for its full life (`zks_cancel_pin`). Nested calls skip the state change when the state is not OFF.

The state is SCOPED only while no application code runs above the frame that set it. `ZKS_APP_CALL` wraps each application function pointer call: completions, watchers, the log callback in `zks_log_sink`, and the Cyrus steps that call the SASL callbacks. It sets the state to OFF before the call. After the call it disables cancellation again and sets SCOPED. A longjmp out of a callback, or a callback that enables cancellation, therefore cannot make later calls skip the mask. The cost is one `pthread_setcancelstate` for each callback that runs under a scoped mask. The IO and dispatch threads and st `zookeeper_process` callbacks pay nothing.

Divergences from legacy, which does not change the cancel state:

- In mt, callbacks that run on an application thread inside a client call run with cancellation disabled.
- A longjmp out of such a callback skips the restore of the outer frame, so that thread keeps cancellation disabled. This is the safe direction, and the shim accepts it.
- The IO and dispatch threads cannot be cancelled. Applications must not change their cancel state from a callback.

The st shim therefore needs `<pthread.h>` and `zks_cancel_masked` in both flavors. `pthread_setcancelstate` is in libSystem on macOS, in glibc 2.34 and later and in musl. The st link adds libpthread on older glibc.

### 6.6 Call graph for st

| C entry (file) | Rust export | zk-ffi path | zk-driver |
|---|---|---|---|
| `zookeeper_interest` (`zks_handle.c`) | `zkrs_interest`, then `zkrs_interest_resume` | `st_interest`, `st_interest_resume` | `Polled::interest`, `Polled::resume` |
| `zookeeper_process` (`zks_handle.c`) | `zkrs_process`, then `zkrs_resume` in `zks_drive` | the `drive` closure of `zkrs_process`, `st_resume` | `Polled::process`, `Polled::resume` |
| `zookeeper_close` (`zks_handle.c`) | `zkrs_close`, then `zkrs_resume` | the `drive` closure of `zkrs_close`, `st_resume` | `Polled::close`, `Polled::resume` |
| `zoo_a*`, `zoo_amulti` (`zks_submit_req`, `zks_async.c`) | `zkrs_submit` | `Driver::submit` | `Polled::submit` |
| `zoo_add_auth` (`zks_async.c`) | `zkrs_add_auth`, `zkrs_add_auth_check` | `Driver::add_auth` | `Polled::add_auth` |
| `zoo_set_servers` (`zks_handle.c`) | `zkrs_set_servers`, then `zkrs_resume` | `Driver::set_servers` | `Polled::set_servers`, `Polled::resume` |
| `zoo_cycle_next_server`, `zoo_set_servers_resolution_delay` (`zks_handle.c`) | `zkrs_cycle_next_server`, `zkrs_set_resolution_delay` | `Driver::with_conn` | `Polled::with_conn` |
| `zoo_state`, `zoo_recv_timeout`, `zoo_get_current_server` (`zks_handle.c`), `zookeeper_get_connected_host` (`zks_resolve.c`) | the accessors | `Driver::state`, `recv_timeout`, `read_conn`, `peer_addr` | the Polled accessors |
| `zks_drain` (`zks_drain.c`) | `zkrs_outbox_next`, `zkrs_outbox_finish`, `zkrs_outbox_requeue` | `FfiOutbox`, `Driver::requeue` | `Polled::requeue` |
| `zookeeper_init` (`zks_handle.c`) | `zkrs_handle_new`, then `zkrs_resume` | `make_driver` | `Polled::from_init` |

The build glue joins the units: `abi/prelink.sh` and `abi/gen-exports.awk` serve autotools, CMake and the dev script.

### 6.7 Checks for both flavors

- The rules and checks of section 1 apply to both flavors.
- The dev script check for both flavors is `FLAVORS="mt st" ABI_CHECK=1 dev/build-dropin.sh "$PWD/.." /tmp/zk-dropin`. Run it after every change to the shim or to zk-ffi.
- `run-abi-tests.sh` and `conformance/run.sh` take `--flavor mt|st`. Run them for both flavors.

### 6.8 The st parts

#### zk-ffi st paths

Files: `zookeeper-client-rust/crates/zk-ffi/src/abi/handle.rs`, `zookeeper-client-rust/crates/zk-ffi/src/abi/submit.rs`,
`zookeeper-client-rust/crates/zk-ffi/src/abi/accessors.rs`, `zookeeper-client-rust/crates/zk-ffi/src/abi/outbox.rs`.

Sources: zk-driver `polled.rs` (the doc comment of each method); zookeeper.c `zookeeper_interest`, `zookeeper_process`, `zookeeper_close`, `nonblocking_send`.

Functions:

| Function | File |
|---|---|
| `st_interest` (`Polled::interest`, the `InterestOut` copy) | handle.rs |
| `st_interest_resume` (`Polled::resume`, the same copy) | handle.rs |
| the `drive` closure of `zkrs_process` (calls `Polled::process` directly) | handle.rs |
| `st_resume` (`Polled::resume` with a scratch `InterestOut`) | handle.rs |
| the `drive` closure of `zkrs_close` (calls `Polled::close` directly, close rc in `r`) | handle.rs |
| the st paths of `Driver::submit`, `Driver::add_auth`, `Driver::set_servers` and the accessors, against the `Polled` contracts | submit.rs, accessors.rs |
| `FfiOutbox` for nested st outboxes and the requeue after an unwind | outbox.rs |

Calls made: the `Polled` methods, `drive`, `finish_step`, `step_out`, `raw::write_out`, `with_outbox`.

Calls received: the five driving exports (section 6.6), and so `zookeeper_interest`, `zookeeper_process`, `zookeeper_close`, `zks_drive` and `zks_drain`.

Invariants:

- No application code runs in a Rust frame. Each st path runs one Polled step and returns its step. No path loops over `More`.
- The output rules of section 6.3 for `*out`, `*written` and `*rc`. The conversions use `try_from` with the fallback of the mt arm (fd -1, interest 0). No panic is reachable.
- `st_resume` serves `zks_drive`, which continues a process, close, set-servers or init stage. The scratch `InterestOut` is dropped.
- `Release` writes the close rc through `finish_step`, and the host goes to 0 before C frees `zh`.

#### Shim st

Files: `zookeeper-client-rust/c/shim/zks_internal.h`, `zookeeper-client-rust/c/shim/zks_handle.c`, `zookeeper-client-rust/c/shim/zks_drain.c`, `zookeeper-client-rust/c/shim/zks_async.c`.

Sources: section 6.5 of this guide; st_adaptor.c; zookeeper.c `zookeeper_interest`, `zookeeper_process`, `zookeeper_close`, `process_completions`.

Functions:

| Function | File |
|---|---|
| `zks_cancel_mask`, `zks_cancel_unmask`, `zks_cancel_pin` and `ZKS_APP_CALL` in both flavors, `<pthread.h>` in both, empty `ZKS_HOLD_SCOPE` in st | zks_internal.h |
| `zks_cancel_masked` in both flavors | zks_handle.c |
| the st paths of `zookeeper_interest`, `zookeeper_process`, `zookeeper_close`, `zks_init_common` and `zks_release` | zks_handle.c |
| `zks_drain` and `zks_drive` for nested st calls and the release rules | zks_drain.c |
| `zks_submit_req` for st (LOG items only) | zks_async.c |

Calls made: the Rust exports of section 6.6, `zks_log_emit`, `zks_deliver_result`, `zks_deliver_watch`, `zks_multi_complete`.

Calls received: the application, and every shim unit through `ZKS_CALL`, `ZKS_CANCEL_SCOPE` and `ZKS_HOLD_SCOPE`.

Invariants:

- Every unit compiles with no warning in both flavors, with and without `-DZKS_HANDLE_REGISTRY`.
- In mt, `ZKS_HOLD_SCOPE` is `ZKS_CANCEL_SCOPE`.
- The handle rule: read `zh->core` into a local first. After a step that can give `Release`, read `zh` only through the step protocol.
- `zookeeper_interest` writes the caller outputs only when `written` is 1, also after a release.
- No new global symbol. The export checks of the dev script pass for both flavors.

#### Autotools

Files: `zookeeper-client-rust/c/configure.ac`, `zookeeper-client-rust/c/Makefile.am`, `zookeeper-client-rust/c/rust.am`, `zookeeper-client-rust/c/m4/zk_rust.m4`, `zookeeper-client-rust/c/abi/prelink.sh`, `zookeeper-client-rust/c/abi/gen-exports.awk`.

Sources: `rust.am`; the rules for `libzookeeper_st.la` and `cli_st` in the legacy `zookeeper-client-c/Makefile.am`.

Items:

| Item | File |
|---|---|
| `zookeeper_st.sym`, `zookeeper_st.keep`, `libzks_st.la` (the shim with no `THREADED`, no `zks_sync.c`, no `zks_threads.c`), `zookeeper_st_all.lo`, `ZKRS_LIBADD_st` with no `-lpthread`, `libzookeeper_st.la`, `cli_st` | rust.am |
| `abi-check` for both libraries, and `abi-static` for both archives (`cargo xtask abi static`) | rust.am |
| the legacy probes, in the legacy order | configure.ac |
| the values that configure gives to the Rust build | m4/zk_rust.m4 |
| flavor support in the pre-link and the export generator | prelink.sh, gen-exports.awk |

Calls made: `abi/prelink.sh`, `abi/gen-exports.awk`, cargo, libtool, `cargo xtask abi`.

Calls received: `make`, `make install`, `make check`. CMake also calls `prelink.sh` and `gen-exports.awk`. The dev script calls `gen-exports.awk`.

Invariants:

- Each archive has one member, the pre-linked object of the flavor. The st library depends only on libSystem on macOS.
- The st dylib exports the 178 st plain rows and has the versions of legacy.
- The drop-in project changes no file in `zookeeper-client-c`. The legacy build there keeps its targets, sources and flags.
- `c/configure.ac` keeps the legacy probes in the legacy order. The test `dv_bld_probe_parity` compares them with the legacy `configure.ac`.
- `c/Makefile.am` keeps the global variables of the legacy `Makefile.am`. The test `dv_bld_makefile_parity` compares them.
- `rust.am` names the units in `c/legacy/` and not the legacy sources, so no object goes into `zookeeper-client-c`.
- A change to `prelink.sh` or `gen-exports.awk` keeps the command lines of CMake and of the dev script valid.

#### CMake

Files: `zookeeper-client-rust/c/CMakeLists.txt`, `zookeeper-client-rust/c/cmake/ZkCProbes.cmake`, `zookeeper-client-rust/c/cmake/ZkRustClient.cmake`,
`zookeeper-client-rust/c/cmake/ZkRustCore.cmake`, `zookeeper-client-rust/c/cmake/ZkRust.cmake`,
`zookeeper-client-rust/c/cmake/ZkRustDetect.cmake`, `zookeeper-client-rust/c/cmake/ZkRustTls.cmake`, `zookeeper-client-rust/c/cmake/ZkHarden.cmake`, `zookeeper-client-rust/c/shim/zks_hashtable_stub.c`.

Sources: the CMake files above; the legacy `zookeeper-client-c/CMakeLists.txt` (`WANT_SYNCAPI`, `cli`).

Items:

| Item | File |
|---|---|
| the legacy options and probes, copied in the legacy order | ZkCProbes.cmake |
| the flavor from `WANT_SYNCAPI` (ON gives mt, OFF gives st), and the option text of `ZOOKEEPER_BUILD_SHARED` | ZkRustClient.cmake |
| the st shim target `zks_st` (no `THREADED`, no sync or threads units), the flavor-named keep list, pre-linked object and shared library `zookeeper_<flavor>`, the flavor of `abi-check`, the `abi-static` target | ZkRustCore.cmake |
| the ctest tests with labels for `c/tests/abi`, with the flavor option of `run-abi-tests.sh` | ZkRustCore.cmake |
| the toolchain checks, the TLS decision and the hardening flags | ZkRust.cmake, ZkRustDetect.cmake, ZkRustTls.cmake, ZkHarden.cmake |
| a check that the `hashtable` archive defines no global | zks_hashtable_stub.c, ZkRustCore.cmake |

Calls made: `abi/prelink.sh`, `abi/gen-exports.awk`, cargo, `cargo xtask abi`, `tests/abi/run-abi-tests.sh`.

Calls received: `cmake --build`, `ctest`, `cmake --install`.

Invariants:

- Legacy CMake picks the flavor with `WANT_SYNCAPI` and defines `THREADED` globally. The Rust core does the same, so `cli` and the application see the same flags as with legacy.
- The static `zookeeper` archive has one member. `hashtable` has one member and no globals.
- The drop-in project changes no file in `zookeeper-client-c`. The legacy build there keeps its targets, sources and flags.
- `ZkCProbes.cmake` is a copy of the legacy probe lines. The test `dv_bld_probe_parity` compares them with the legacy `CMakeLists.txt`.
