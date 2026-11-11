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

# C client ABI goldens and export tools

This directory holds the ABI files that the build of the Rust drop-in
libraries `libzookeeper_mt` and `libzookeeper_st` uses. The release ships
them. The goldens come from a build of the legacy C client. The
`cargo xtask` tasks in `zookeeper-client-rust/xtask` generate and check
them. Do not edit the generated files by hand.

| File | Content | Task |
|---|---|---|
| `symbols.tsv` | The export set of both flavors in all four configurations. It is the single export source. | `abi golden` |
| `static-symbols.tsv` | The globals that only the static archives define. | `abi golden` |
| `gen-exports.awk` | The export-list generator. It reads the two TSV files and writes the `.sym`, `.map`, `.exp`, `.def` and `.keep` lists of a flavor and a configuration. | none |
| `prelink.sh` | The pre-link step of Make and CMake. It joins the shim archive of a flavor and the `zk-ffi` staticlib into one relocatable object. Only the names of the keep list stay global. | none |
| `target-cpu.sh` | The Rust target CPU decision of Make, CMake and Maven, and the wrapper of the staticlib cargo call. It gives `x86-64-v2` when the C compiler makes x86-64-v2 code, and it adds the flag to the rustflags of the user. | none |
| `tls-probe.sh` | The TLS decision and the default trust paths of the Maven build. It uses the OpenSSL probe of configure and prints `tls`, `openssl` (the header probe result), `cert_file` and `cert_dir`. | none |

The ABI data for development and conformance checks is in
`../tests/abi` (`zookeeper-client-rust/c/tests/abi`). The build does not
read it, so a release can omit it:

| File | Content | Task |
|---|---|---|
| `absent.tsv` | The names that a shared library must not export. | `abi golden` |
| `log-sites.tsv` | The `LOG_*` call sites of the C core, with level and format. | `log-sites` |
| `layout/probe.c` | The L1 layout probe of the public types. | `layout gen` |
| `layout/<target>.expect` | The measured layout facts for one target. | `layout expect` |

The column descriptions below cover the files of both directories.

## Columns

`symbols.tsv` has the columns `name kind flavors feature rw size_lp64 size_ilp32 decl note`.

- `kind` is `func` or `data`.
- `flavors` is `mt,st` or `mt`.
- `feature` is `-` (always present), `ssl` (only with TLS) or `sasl` (only with SASL).
- `rw` is `ro` or `rw` for data, and `-` for functions.
- `size_lp64` and `size_ilp32` are the data object sizes in bytes, and `-` for functions.
- `decl` is the declaring installed header and line, or `-`.
- `note` records the ABI-01 facts, for example an export that no installed header declares.

The export totals for mt/st are: plain 201/178, ssl 202/179, sasl 210/187,
full 211/188. In every configuration, 36 exports are data. Of these, 30 are
read-only `const int` objects and 6 are writable.

`static-symbols.tsv` has the columns `name kind decl note`. All 179 rows
apply to both flavors and to every configuration.

`absent.tsv` has the columns `name flavors class decl`. The `class` column
has one of these values:

- `archive-only`: the name is in `static-symbols.tsv`.
- `internal`: an undeclared legacy global that DV-ABI-1 removes.
- `undefined`: no library defines the name.
- `mt-only`: a sync function that only mt defines, so st must not export it.

`log-sites.tsv` has a header comment that describes its columns. The `line`
column is the line of the macro name. The `line_end` column is the line of
the closing parenthesis. Apple clang expands `__LINE__` to `line_end` when a
call spans more than one line.

`<target>.expect` has the columns `config fact type member value`. The
`plain` rows apply to the headers without `HAVE_OPENSSL_H` and
`HAVE_CYRUS_SASL_H`. The `tls-sasl` rows list only the facts that are new
or different when both macros are defined.

## Reference build

The goldens come from an autotools build of the legacy C client
(`zookeeper-client/zookeeper-client-c`) with four
configurations, installed under `/tmp/zk-legacy-ref/install-<config>`:

| Config | configure options |
|---|---|
| plain | `--without-openssl --without-sasl` |
| ssl | `--with-openssl=<openssl@3> --without-sasl` |
| sasl | `--without-openssl --with-sasl=<cyrus-sasl>` |
| full | `--with-openssl=<openssl@3> --with-sasl=<cyrus-sasl>` |

The SASL configurations need `CFLAGS="-g -O2 -D_GNU_SOURCE -Wno-gnu-folding-constant"`
with clang, because `-Werror` stops the build in `zk_sasl.c`.
Build each configuration in its own build directory. Run `autoreconf -if`
in the legacy tree once. Then run `configure` with
`--prefix=/tmp/zk-legacy-ref/install-<config>` and the options of the
table, then `make` and `make install`.

## Commands

Run these commands from `zookeeper-client-rust`.

```sh
# Regenerate the export goldens from the reference build.
cargo xtask abi golden --ref /tmp/zk-legacy-ref

# Regenerate the log-site table, or compare it with the zk-proto table.
cargo xtask log-sites
cargo xtask log-sites --check

# Regenerate the layout probe and the layout facts of the host target.
cargo xtask layout gen
cargo xtask layout expect --include <prefix>/include \
    --full-cflags "-I<openssl@3>/include -I<cyrus-sasl>/include"

# Check a shared library or a static archive against the goldens.
cargo xtask abi check <libzookeeper_mt.dylib> --flavor mt --config plain
cargo xtask abi static <libzookeeper_mt.a> --flavor mt --config plain
```

`abi check` fails when the names, kinds or data sizes differ from
`symbols.tsv`. It also checks the identity of the library, such as the
Mach-O install name and versions. The `--reference` option accepts the
known legacy deviations: DV-TLS-4 for the OpenSSL references and DV-ABI-1
for the archive globals. The checks read `absent.tsv` from the golden
directory when it is there, and otherwise from `../tests/abi`. A tree
without that directory skips check E1.

The `--check` option of `log-sites`, `layout gen` and `layout expect`
compares the output with the file and fails when the file is out of date.

## Layout checks

Three checks cover the layout of the types at the C boundary:

- `layout/<target>.expect` and `../tests/abi/layout_check.c` check the
  public headers against the measured facts of each target. A cross
  compiler can make the facts, because the probe does not run.
- `../shim/zks_layout_check.c` pins the `zkrs.h` layouts of the 64-bit
  and the 32-bit targets. The shim does not compile when a layout
  changes.
- The `zk-ffi-layout` crate compares C with Rust on the build host. It
  reads the member lists of the public types from `layout/probe.c`.
  It compiles a C probe in the `plain`, `ssl`, `sasl` and `full`
  configurations. Then it compares the size, the alignment and each
  field of the `zk-ffi` mirrors and `zkrs.h` types with rustc. It also
  compares the enum values and the constants.

Run these commands from `zookeeper-client-rust`:

```sh
cargo xtask layout gen --check
cargo test --manifest-path crates/zk-ffi-layout/Cargo.toml
```

`zk-ffi-layout` is a separate workspace, so `cargo test --workspace` does
not run it. The test task of the Maven module runs it for the `plain`
configuration, and for `sasl` when the SASL probe passes. Run the `ssl`
and `full` configurations with the command above.
