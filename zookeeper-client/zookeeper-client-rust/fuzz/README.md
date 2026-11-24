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

# Fuzz Targets for the Rust Client

This crate defines the `cargo fuzz` targets for the Rust client. It is excluded from the main
workspace because it requires a nightly toolchain and maintains an independent `Cargo.lock`.

## Targets

| Target | Input |
| --- | --- |
| `jute_records` | Jute record from `zk-jute`, selected by the leading discriminator byte. |
| `frame_decoder` | Framed byte stream delivered to `FrameDecoder` in variable-length chunks. |
| `connect_response` | Handshake `ConnectResponse` evaluated standalone and in a session. |
| `multi_response` | Multi-operation response evaluated standalone and as a session reply. |
| `session_stream` | Scripted server frames, client requests, and driver events for a session. |
| `sasl_frames` | SASL negotiation frames and mechanism step outcomes for an active session. |
| `connect_string` | Connect string evaluated against both legacy and native parsers. |
| `zpath` | Chroot prefix and path input evaluated against legacy and native path validators. |
| `sasl_digest_md5` | `DIGEST-MD5` server challenges, including synthesized `rspauth` tokens. |
| `tls_trust` | PEM CA bundle evaluated by the trust store loader and certificate chain verifier. |
| `tls_keys` | Passphrase and plaintext or encrypted PEM private key bundle. |
| `tls_stream` | TLS record stream delivered to the `zk-driver` client TLS session wrapper. |
| `ffi_writers` | Decoded replies and C destination buffers for `zk-ffi` result writers. |

All targets exercise the public APIs of the client crates, with two exceptions:

- `sasl_digest_md5` invokes `apache_zookeeper::sasl::fuzzing`, which is conditionally compiled only
  when `--cfg fuzzing` is active.
- `ffi_writers` includes the result writer source modules from `zk-ffi` directly via `#[path]`.
  Because those routines populate C destination buffers through raw pointers, this target permits
  `unsafe` code so that AddressSanitizer can detect out-of-bounds memory accesses.

## Seed Corpus Generation

Seed corpora are not committed to the repository. The seed generator populates `corpus/<target>/`
(which is ignored by git):

```sh
cargo +nightly run --example gen_seeds
```

The TLS seed generator and the `tls_stream` target rely on test certificates provisioned by
`zk-testkit`. `zk-testkit` generates and caches these certificates on demand using the `openssl`
CLI, which must be available on `PATH`.

## Running Fuzz Targets

Install `cargo-fuzz` and invoke a target from this directory:

```sh
cargo install --locked cargo-fuzz
cargo +nightly fuzz run session_stream -- -max_total_time=60
```

Crashing inputs are written to `artifacts/<target>/`. Minimize crash reproducers with
`cargo fuzz tmin` and incorporate them as regression tests in the corresponding crate.

The `tls_trust` and `tls_keys` targets materialize inputs to a process-scoped temporary file
because the production loaders accept filesystem paths; each iteration unlinks its temporary file
before returning.

To preserve fuzzing throughput, `tls_keys` skips key derivation when key-stretching parameters
exceed fuzzing thresholds (PBKDF2 iteration counts above 2048 or scrypt work factors above 8192),
validating only parameter parsing for those inputs.

Because `sasl_digest_md5` requires the `fuzzing` configuration flag, run workspace lint or type
checks with `RUSTFLAGS="--cfg fuzzing"`:

```sh
RUSTFLAGS="--cfg fuzzing" cargo clippy --all-targets
```
