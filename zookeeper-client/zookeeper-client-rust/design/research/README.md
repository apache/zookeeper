# Rust ZooKeeper client and C drop-in shim: design

This directory holds the design for a native Rust ZooKeeper client (`zookeeper-client/zookeeper-client-rust`). It covers two products that run on one Rust protocol core:

- **A drop-in for `libzookeeper_mt` and `libzookeeper_st`.** A C shim keeps the installed headers byte-identical. It also keeps every exported symbol of the configured build (211 for mt and 188 for st with TLS and SASL; 01 §5), the SONAME, struct layouts, return codes, errno, callback threads and ordering, memory ownership, log format and close semantics. Existing C and C++ applications rebuild or relink with no source change and see no change in behavior. The only exceptions are the registered deviations of 00 §5, each with its release-note text.
- **A native Rust API**, the package `apache-zookeeper`, shipped in the ASF source release. It is typed and futures-based, with tokio as the default runtime and a blocking facade as an option. A recipes crate, `apache-zookeeper-recipes` (lock, leader latch, tree cache; Curator-compatible layouts), builds on it (milestone M5R).

Every shipped Rust crate except the FFI boundary crate is `#![forbid(unsafe_code)]`; the test-only crates are never shipped. TLS is rustls for both products. SASL stays on Cyrus in hardened, isolated C, because the public C API is bound to Cyrus types. Maven builds the Rust workspace in an opt-in module (`-Prust`) with the same phase bindings as the C client; `-Pfull-build` alone needs no cargo.

## Documents

| # | Document | Owns |
|---|---|---|
| 00 | [Overview](00-overview.md) | Executive summary, architecture, key decisions (K-n), cross-cutting contracts (CC-n), deviation registers, release prerequisites, risks |
| 01 | [Architecture and build](01-architecture-build.md) | Crate graph, features, directory tree, link and prelink, ABI goldens, autotools/CMake integration, jute generation (§8), Maven integration (§13), versioning and the source release, the build-level deviation register (DV-ABI-1..2, DV-BLD-1..8, §11) |
| 02 | [Protocol core](02-proto-core.md) | `zk-jute` and the sans-IO `zk-proto` core: state machine, xids, timers, watcher tables, auth and SASL sequencing, the `Compat` policy |
| 03 | [Drivers and I/O](03-drivers-io.md) | `zk-driver`: the st, mt, threaded and tokio drivers, the outbox callback model, sockets, rustls integration, Windows |
| 04 | [Native Rust API](04-rust-api.md) | The `apache-zookeeper` public API, builder knobs and defaults, and the recipes crate `apache-zookeeper-recipes` (§22) |
| 05 | [C shim and FFI](05-c-shim-ffi.md) | The C shim, the private `zkrs.h` ABI, the host vtable, the drain, sync wrappers, the mt thread loops, `zk-ffi` |
| 06 | [Security: TLS and SASL](06-security-tls-sasl.md) | rustls trust and verification policy that matches legacy OpenSSL, key formats, DV-TLS-1..6, hardened Cyrus SASL glue, digest auth, secrets |
| 07 | [Compatibility ledger](07-compat-ledger.md) | Every legacy quirk, defect and undocumented behavior, and its disposition (reproduced, fixed-UB-only, deviation); the Compat values (§2.1), the DV-FIX entries and the list of behavior fixes (§5.1, §5.2) |
| 08 | [Verification and rollout](08-verification.md) | ABI conformance, the differential corpus against the legacy core, TLS and SASL tests, sanitizers, fuzzing, CI, milestones M0-M5 and M5R, the infrastructure requirements (§15.6) |

Citations take the form `NN §x.y`, for example `06 §3.4`. When two documents state the same fact, the owner in the table above is authoritative.

## Reading order

- **C client maintainers:** 00, then 05, 07 and 06.
- **Rust implementers:** 00, then 01, 02, 03 and 04.
- **Release and build:** 00 §2.7, then 01 §6 and §13, then 08 §14 and §15.

## Deviations and release prerequisites

00 §5 lists every deviation from legacy behavior with its release-note text, and 00 §6 lists the release prerequisites. Milestones M0-M5 and M5R in 08 §15 give the delivery order. The legacy C core stays buildable and maintained, and its pinned reference build is the differential oracle that CI compares the Rust core against (08 §4.1).
