# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# The Autotools part of the Rust client core. configure.ac calls
# ZK_RUST_CLIENT after the legacy probes. ZK_RUST_CLIENT sets the build
# conditionals, the compiler flags and the link targets of rust.am.

# ZK_RUST_CLIENT
# --------------
# The main macro of the drop-in configure. It declares the options of the
# Rust client: the workspace, a prebuilt staticlib, the target, TLS and
# the offline crate sources. Then it sets the flags and the output files.
AC_DEFUN([ZK_RUST_CLIENT], [
dnl The Rust C tree is the source directory. Its tests subtree is optional.
zk_rust_c_dir=$srcdir
AM_CONDITIONAL([ZKRS_TESTS], [test -d "$zk_rust_c_dir/tests"])
dnl Preserve pre-probe flags and options prior to legacy OpenSSL probe execution.
m4_divert_text([INIT_PREPARE], [zk_user_with_openssl=${with_openssl-yes}
zk_rust_init_CPPFLAGS=$CPPFLAGS
zk_rust_init_LDFLAGS=$LDFLAGS])
AC_ARG_WITH([rust-workspace],
 [AS_HELP_STRING([--with-rust-workspace=DIR],[Rust workspace, with the same shim as the drop-in sources (default: the parent of the source directory)])],
 [], [with_rust_workspace=auto])
AC_ARG_WITH([rust-staticlib],
 [AS_HELP_STRING([--with-rust-staticlib=PATH],[link this prebuilt libzk_ffi.a; make does not run cargo])])
AC_ARG_WITH([rust-target],
 [AS_HELP_STRING([--with-rust-target=TRIPLE],[pass --target TRIPLE to cargo (required for cross builds)])])
dnl TLS configuration options evaluated by ZK_RUST_TLS_CONFIG.
AC_ARG_WITH([rust-tls],
 [AS_HELP_STRING([--with-rust-tls=yes|no],[build TLS into the Rust client also without OpenSSL headers [default=no]])],
 [], [with_rust_tls=no])
AC_ARG_WITH([rust-tls-provider],
 [AS_HELP_STRING([--with-rust-tls-provider=ring|aws-lc-rs|aws-lc-rs-fips],[the rustls crypto provider of the Rust client [default=ring]])],
 [zk_rust_provider_given=yes],
 [with_rust_tls_provider=ring])
AC_ARG_WITH([rust-tls-default-paths],
 [AS_HELP_STRING([--with-rust-tls-default-paths=FILE:DIR],[the default trust file and directory of the Rust client (default: the OPENSSLDIR of the probed OpenSSL, else the platform list)])],
 [], [with_rust_tls_default_paths=auto])
dnl Offline package source options evaluated by ZK_RUST_CRATE_SOURCES.
AC_ARG_WITH([cargo-vendor],
 [AS_HELP_STRING([--with-cargo-vendor=DIR],[build the Rust client offline with the crates of a cargo vendor directory])],
 [AS_CASE([$withval], [no], [with_cargo_vendor=])],
 [with_cargo_vendor=])
AC_ARG_WITH([system-rust-crates],
 [AS_HELP_STRING([--with-system-rust-crates@<:@=DIR@:>@],[build the Rust client offline with the system crates of DIR (default DIR: /usr/share/cargo/registry)])],
 [AS_CASE([$withval], [no], [with_system_rust_crates=],
   [yes], [with_system_rust_crates=/usr/share/cargo/registry])],
 [with_system_rust_crates=])
AC_ARG_WITH([rust-target-cpu],
 [AS_HELP_STRING([--with-rust-target-cpu=auto|no|CPU],[the -C target-cpu of the Rust code: auto follows the C compiler, no adds no flag [default=auto]])],
 [], [with_rust_target_cpu=auto])
AC_ARG_ENABLE([werror],
 [AS_HELP_STRING([--disable-werror],[do not add -Werror to the Rust client shim flags])],
 [], [enable_werror=yes])
AC_ARG_VAR([CARGO],[the cargo command of the Rust client build])
AC_ARG_VAR([RUSTC],[the rustc command of the Rust client build])
AS_IF([test -n "$with_cargo_vendor" && test -n "$with_system_rust_crates"],
  [AC_MSG_ERROR([give only one of --with-cargo-vendor and --with-system-rust-crates])])
AS_IF([test "x$with_cargo_vendor" = xyes],
  [AC_MSG_ERROR([--with-cargo-vendor needs a directory: give --with-cargo-vendor=DIR])])
ZKRS_CARGO_SOURCE_ARGS=
ZKRS_CARGO_SOURCE_CONFIG=
dnl This configure builds only the Rust client core, so a failed check stops it.
ZK_RUST_CHECK
AS_IF([test "x$zk_rust_ok" != xyes],
  [AC_MSG_ERROR([cannot build the Rust client: $zk_rust_why; zookeeper-client-c builds the legacy C client])])
AM_CONDITIONAL([ZKRS_PREBUILT], [test -n "$with_rust_staticlib"])
dnl Header search flags propagated to consumer utilities in rust.am.
ZK_OPENSSL_DIR_CPPFLAGS=
ZK_SASL_DIR_CPPFLAGS=
zk_rust_tls=no
zk_rust_sasl=no
AC_MSG_NOTICE([building the Rust client core from $RUST_WORKSPACE])
dnl The shim must not see the include directory of the SASL probe. Remove it
dnl from CPPFLAGS. The programs get it back through ZK_SASL_DIR_CPPFLAGS.
AS_IF([test "x$have_sasl" = xyes && test "x$with_sasl" != xno && test "x$with_sasl" != xyes],
  [AS_CASE([$CPPFLAGS], [*" -I$with_sasl/include"],
    [CPPFLAGS=${CPPFLAGS%" -I$with_sasl/include"}
     ZK_SASL_DIR_CPPFLAGS="-I$with_sasl/include"])])
dnl Remove the directory flags of the OpenSSL probe from CPPFLAGS and
dnl LDFLAGS. The Rust core links no OpenSSL library.
AS_CASE([$with_openssl], [yes|no], [], [
  zk_rust_post="$zk_rust_init_CPPFLAGS -I$with_openssl/include"
  AS_CASE([$CPPFLAGS], ["$zk_rust_post"*],
    [CPPFLAGS=$zk_rust_init_CPPFLAGS${CPPFLAGS@%:@"$zk_rust_post"}])
  zk_rust_post="$zk_rust_init_LDFLAGS -L$with_openssl/lib"
  AS_CASE([$LDFLAGS], ["$zk_rust_post"*],
    [LDFLAGS=$zk_rust_init_LDFLAGS${LDFLAGS@%:@"$zk_rust_post"}])])
dnl The target triple is the --with-rust-target value, else the host triple of rustc.
AS_IF([test -n "$with_rust_target"],
  [RUST_TARGET=$with_rust_target; ZKRS_TARGET_ARGS="--target $RUST_TARGET"],
  [RUST_TARGET=`"$RUSTC" -vV | sed -n 's/^host: //p'`; ZKRS_TARGET_ARGS=])
RUST_TARGET_ENV=`echo "$RUST_TARGET" | tr '.-' '__'`
ZKRS_PROFILE=capi
ZKS_FEATURE_CPPFLAGS=
AS_IF([test "x$enable_debug" = xyes],
  [ZKRS_PROFILE=capi-dev; ZKS_FEATURE_CPPFLAGS=-DZKS_HANDLE_REGISTRY])
AS_IF([test -n "$with_rust_staticlib"],
  [ZKRS_STATICLIB=`cd "$(dirname "$with_rust_staticlib")" && pwd`/`basename "$with_rust_staticlib"`],
  [test -n "$with_rust_target"],
  [ZKRS_STATICLIB='$(abs_builddir)/cargo/$(RUST_TARGET)/$(ZKRS_PROFILE)/libzk_ffi.a'],
  [ZKRS_STATICLIB='$(abs_builddir)/cargo/$(ZKRS_PROFILE)/libzk_ffi.a'])
AS_IF([test "x$enable_werror" != xno], [ZKS_WERROR=-Werror], [ZKS_WERROR=])
AS_CASE([$host_os],
  [darwin*], [ZKRS_GC_LDFLAGS=-Wl,-dead_strip],
  [ZKRS_GC_LDFLAGS=-Wl,--gc-sections
   AC_CHECK_TOOL([OBJCOPY], [objcopy])
   AS_IF([test -z "$OBJCOPY"], [AC_MSG_ERROR([the Rust client pre-link needs objcopy])])])
ZK_RUST_PROBE
ZK_RUST_TARGET_CPU
ZK_RUST_TLS_CONFIG
AS_IF([test "x$zk_rust_tls" = xyes], [HAVE_SSL01=1], [HAVE_SSL01=0])
AS_IF([test "x$zk_rust_sasl" = xyes], [HAVE_SASL01=1], [HAVE_SASL01=0])
AS_CASE([$HAVE_SSL01$HAVE_SASL01],
  [00], [ZKRS_ABI_CONFIG=plain], [10], [ZKRS_ABI_CONFIG=ssl],
  [01], [ZKRS_ABI_CONFIG=sasl], [ZKRS_ABI_CONFIG=full])
AC_SUBST([RUST_WORKSPACE]) AC_SUBST([RUST_TARGET]) AC_SUBST([RUST_TARGET_ENV])
AC_SUBST([ZKRS_TARGET_ARGS]) AC_SUBST([ZKRS_PROFILE]) AC_SUBST([ZKRS_STATICLIB])
AC_SUBST([ZKRS_TARGET_CPU]) AC_SUBST([ZKRS_CARGO_WRAP])
AC_SUBST([ZKRS_CARGO_SOURCE_ARGS]) AC_SUBST([ZKRS_CARGO_SOURCE_CONFIG])
AC_SUBST([ZKRS_NATIVE_LIBS]) AC_SUBST([ZKRS_GC_LDFLAGS]) AC_SUBST([ZKS_FEATURE_CPPFLAGS])
AC_SUBST([ZKS_CFLAGS]) AC_SUBST([ZKS_HARDEN_CFLAGS]) AC_SUBST([ZKS_WERROR])
AC_SUBST([ZKRS_CARGO_FEATURES]) AC_SUBST([ZKS_CONFIG_CPPFLAGS]) AC_SUBST([ZKRS_SASL_LIBS])
AC_SUBST([ZKS_SASL_CFLAGS]) AC_SUBST([ZK_TLS_DEFAULT_CERT_FILE]) AC_SUBST([ZK_TLS_DEFAULT_CERT_DIR])
AC_SUBST([ZKS_HARDEN_SASL_CFLAGS]) AC_SUBST([ZK_OPENSSL_DIR_CPPFLAGS])
AC_SUBST([ZK_SASL_DIR_CPPFLAGS]) AC_SUBST([HAVE_SSL01]) AC_SUBST([HAVE_SASL01]) AC_SUBST([ZKRS_ABI_CONFIG])
AM_CONDITIONAL([RUST_TLS], [test "x$zk_rust_tls" = xyes])
AM_CONDITIONAL([RUST_SASL], [test "x$zk_rust_sasl" = xyes])
dnl The programs define HAVE_OPENSSL_H only when they can see the OpenSSL headers.
dnl This is the only definition of WANT_OPENSSL.
AM_CONDITIONAL([WANT_OPENSSL], [test "x$zk_rust_cli_ssl" = xyes])
dnl Libs.private of the pc files needs CLOCK_GETTIME_LIBS. configure.ac sets
dnl that variable after this macro. Thus this code runs before configure
dnl writes the output files.
AC_CONFIG_COMMANDS_PRE([
AS_IF([test "x$HAVE_SASL01" = x1], [zk_pc_sasl=-lsasl2], [zk_pc_sasl=])
for zk_pc_fl in st mt; do
  zk_pc_all="-lm $CLOCK_GETTIME_LIBS"
  AS_IF([test $zk_pc_fl = mt], [zk_pc_all="$zk_pc_all -lpthread"])
  zk_pc_out=
  for zk_pc_l in $zk_pc_all $ZKRS_NATIVE_LIBS $zk_pc_sasl; do
    AS_CASE([" $zk_pc_out "], [*" $zk_pc_l "*], [],
      [zk_pc_out="${zk_pc_out:+$zk_pc_out }$zk_pc_l"])
  done
  eval "ZKRS_PC_PRIVATE_$zk_pc_fl=\$zk_pc_out"
done
])
AC_SUBST([ZKRS_PC_PRIVATE_st]) AC_SUBST([ZKRS_PC_PRIVATE_mt])
AC_CONFIG_FILES([zookeeper_st.pc zookeeper_mt.pc])
])

# ZK_RUST_CHECK
# -------------
# Validates host environment suitability, toolchain versions, workspace consistency,
# and crate source availability. On success, exports RUST_WORKSPACE, RUSTC, CARGO,
# and ZKRS_CARGO_SOURCE_ARGS; on failure, records the failure reason in zk_rust_why.
AC_DEFUN([ZK_RUST_CHECK], [
zk_rust_ok=yes
zk_rust_why=
AC_PATH_PROG([RUSTC], [rustc])
AC_PATH_PROG([CARGO], [cargo])
AC_MSG_CHECKING([whether this host can build the Rust client])
AS_CASE([$host_os],
  [cygwin*], [zk_rust_ok=no; zk_rust_why="the Rust client does not support Cygwin"])
dnl Verify that external workspace declarations maintain ABI compatibility with the local shim header.
AS_IF([test "x$zk_rust_ok" = xyes], [
  AS_IF([test "x$with_rust_workspace" != xauto && test -n "$with_rust_workspace"],
    [zk_rust_ws=$with_rust_workspace],
    [zk_rust_ws=$zk_rust_c_dir/..])
  AS_IF([test -f "$zk_rust_ws/Cargo.toml"],
    [RUST_WORKSPACE=`cd "$zk_rust_ws" && pwd`],
    [zk_rust_ok=no; zk_rust_why="no Rust workspace at $zk_rust_ws (give --with-rust-workspace=DIR)"])
])
AS_IF([test "x$zk_rust_ok" = xyes], [
  zk_rust_ws_phys=`cd "$RUST_WORKSPACE" && pwd -P`
  zk_rust_own_phys=`cd "$zk_rust_c_dir/.." && pwd -P`
  AS_IF([test "x$zk_rust_ws_phys" != "x$zk_rust_own_phys" &&
         ! cmp -s "$RUST_WORKSPACE/c/shim/zkrs.h" "$zk_rust_c_dir/shim/zkrs.h"],
    [zk_rust_ok=no
     zk_rust_why="the workspace $RUST_WORKSPACE does not have the shim header of $zk_rust_own_phys/c (give the workspace of the drop-in sources)"])
])
dnl Enforce version parity between Cargo workspace metadata and zookeeper_version.h.
AS_IF([test "x$zk_rust_ok" = xyes], [
  zk_rust_ws_version=`sed -n '/^\@<:@workspace.package\@:>@/,/^\@<:@/s/^version *= *"\(.*\)"/\1/p' "$RUST_WORKSPACE/Cargo.toml"`
  zk_rust_zoo_version=`sed -n 's/^#define ZOO_VERSION "\(.*\)"/\1/p' "$srcdir/../../zookeeper-client-c/include/zookeeper_version.h"`
  AS_IF([test "x$zk_rust_ws_version" != "x$zk_rust_zoo_version"],
    [zk_rust_ok=no
     zk_rust_why="the Rust workspace version $zk_rust_ws_version is not ZOO_VERSION $zk_rust_zoo_version"])
])
dnl Verify toolchain version constraints (cargo and rustc >= 1.85, or rustc for prebuilt libraries).
AS_IF([test "x$zk_rust_ok" = xyes], [
  AS_IF([test -n "$with_rust_staticlib"], [
    AS_IF([test ! -f "$with_rust_staticlib"],
      [zk_rust_ok=no; zk_rust_why="the staticlib $with_rust_staticlib does not exist"],
      [test -z "$RUSTC"],
      [zk_rust_ok=no; zk_rust_why="rustc not found (needed with --with-rust-staticlib)"])
  ], [
    zk_rust_tools_ok=no
    AS_IF([test -n "$RUSTC" && test -n "$CARGO"], [
      zk_rust_v1=`"$RUSTC" --version 2>/dev/null | awk '{ split($[2], v, "."); print (v[[1]] * 1000 + v[[2]]) }'`
      zk_rust_v2=`"$CARGO" --version 2>/dev/null | awk '{ split($[2], v, "."); print (v[[1]] * 1000 + v[[2]]) }'`
      AS_IF([test -n "$zk_rust_v1" && test -n "$zk_rust_v2" &&
             test "$zk_rust_v1" -ge 1085 && test "$zk_rust_v2" -ge 1085],
        [zk_rust_tools_ok=yes])
    ])
    AS_IF([test "x$zk_rust_tools_ok" != xyes],
      [zk_rust_ok=no; zk_rust_why="cargo and rustc 1.85 or later not found"],
      [test "x$host" != "x$build" && test -z "$with_rust_target"],
      [zk_rust_ok=no; zk_rust_why="a cross build needs --with-rust-target=TRIPLE"])
  ])
])
AS_IF([test "x$zk_rust_ok" = xyes], [ZK_RUST_CRATE_SOURCES])
AS_IF([test "x$zk_rust_ok" = xyes], [AC_MSG_RESULT([yes])], [AC_MSG_RESULT([no: $zk_rust_why])])
AS_IF([test "x$zk_rust_ok" = xyes && test -n "$zk_rust_src_note"], [AC_MSG_NOTICE([$zk_rust_src_note])])
])

# ZK_RUST_CRATE_SOURCES
# ---------------------
# Configures package dependency resolution for online builds or offline vendored/system
# crate directories. Emits cargo-sources.toml to override Cargo registry configuration
# and validates dependency availability via cargo fetch.
AC_DEFUN([ZK_RUST_CRATE_SOURCES], [
zk_rust_src_config=`pwd`/cargo-sources.toml
rm -f "$zk_rust_src_config"
zk_rust_src_note=
zk_rust_src_opt=
zk_rust_src_dir=
AS_IF([test -n "$with_cargo_vendor"],
  [zk_rust_src_opt=--with-cargo-vendor; zk_rust_src_dir=$with_cargo_vendor],
  [test -n "$with_system_rust_crates"],
  [zk_rust_src_opt=--with-system-rust-crates; zk_rust_src_dir=$with_system_rust_crates])
AS_IF([test -n "$zk_rust_src_opt" && test -d "$zk_rust_src_dir"],
  [zk_rust_src_dir=`cd "$zk_rust_src_dir" && pwd`])
zk_rust_src_bad=no
AS_CASE([$zk_rust_src_config$zk_rust_src_dir], [*@<:@\ \"\'\\@:>@*], [zk_rust_src_bad=yes])
AS_IF([test -z "$zk_rust_src_opt"], [
  dnl Validate dependency availability in online or local Cargo cache.
  AS_IF([test -z "$with_rust_staticlib" &&
         ! (cd "$RUST_WORKSPACE" && "$CARGO" fetch --locked) >&AS_MESSAGE_LOG_FD 2>&1],
    [zk_rust_ok=no; zk_rust_why="no crate source: cargo fetch --locked failed"])
], [test ! -d "$zk_rust_src_dir"], [
  zk_rust_ok=no
  zk_rust_why="the crate directory $zk_rust_src_dir of $zk_rust_src_opt does not exist"
], [test -z "$CARGO"], [
  zk_rust_ok=no; zk_rust_why="cargo not found (needed with $zk_rust_src_opt)"
], [test "x$zk_rust_src_bad" = xyes], [
  zk_rust_ok=no
  zk_rust_why="the build directory or the crate directory $zk_rust_src_dir of $zk_rust_src_opt has a space, a quote or a backslash"
], [
  zk_rust_src_kind=directory
  AS_IF([test "x$zk_rust_src_opt" = x--with-system-rust-crates && test -d "$zk_rust_src_dir/index"],
    [zk_rust_src_kind=local-registry])
  {
    echo '# The crate sources of this build. configure writes this file.'
    echo '@<:@source.crates-io@:>@'
    echo 'replace-with = "zk-local"'
    echo '@<:@source.zk-local@:>@'
    echo "$zk_rust_src_kind = \"$zk_rust_src_dir\""
  } >"$zk_rust_src_config"
  ZKRS_CARGO_SOURCE_ARGS="--offline --config $zk_rust_src_config"
  ZKRS_CARGO_SOURCE_CONFIG=$zk_rust_src_config
  zk_rust_src_note="cargo runs offline with the crates of $zk_rust_src_dir ($zk_rust_src_kind)"
  AS_IF([test "x$zk_rust_src_opt" = x--with-system-rust-crates], [
    AS_IF([(cd "$RUST_WORKSPACE" && "$CARGO" generate-lockfile $ZKRS_CARGO_SOURCE_ARGS) >&AS_MESSAGE_LOG_FD 2>&1],
      [zk_rust_src_note="$zk_rust_src_note; cargo generate-lockfile wrote $RUST_WORKSPACE/Cargo.lock again"],
      [zk_rust_ok=no
       zk_rust_why="the system crates of $zk_rust_src_dir do not resolve the workspace: cargo generate-lockfile --offline failed"])
  ])
  AS_IF([test "x$zk_rust_ok" = xyes && test -z "$with_rust_staticlib" &&
         ! (cd "$RUST_WORKSPACE" && "$CARGO" fetch --locked $ZKRS_CARGO_SOURCE_ARGS) >&AS_MESSAGE_LOG_FD 2>&1],
    [zk_rust_ok=no
     zk_rust_why="the crate directory $zk_rust_src_dir of $zk_rust_src_opt does not have all crates of Cargo.lock: cargo fetch --locked --offline failed"])
])
AS_IF([test "x$zk_rust_ok" != xyes],
  [rm -f "$zk_rust_src_config"; ZKRS_CARGO_SOURCE_ARGS=; ZKRS_CARGO_SOURCE_CONFIG=])
])

# ZK_RUST_CC_FLAG(FLAG, VARIABLE)
# -------------------------------
# Probes compiler support for the specified diagnostic or hardening flag using -Werror,
# appending accepted flags to the destination variable.
AC_DEFUN([ZK_RUST_CC_FLAG], [
AC_MSG_CHECKING([whether $CC accepts $1])
zk_rust_saved_CFLAGS=$CFLAGS
CFLAGS="$CFLAGS -Werror $1"
AC_LINK_IFELSE([AC_LANG_PROGRAM([[#include <stdio.h>
#include <string.h>]], [[char b[8]; snprintf(b, sizeof b, "%d", 1); return (int)strlen(b);]])],
  [AC_MSG_RESULT([yes]); $2="${$2:+$$2 }$1"; zk_rust_flag_ok=yes],
  [AC_MSG_RESULT([no]); zk_rust_flag_ok=no])
CFLAGS=$zk_rust_saved_CFLAGS
])

# ZK_RUST_PROBE
# -------------
# Discovers native static library linker dependencies via rustc and evaluates compiler
# support for semantic interposition, exception unwinding, and security hardening flags.
AC_DEFUN([ZK_RUST_PROBE], [
AC_MSG_CHECKING([for the native libraries of a Rust staticlib])
zk_rust_tmp=`mktemp -d 2>/dev/null || echo ./conftest.rust`
mkdir -p "$zk_rust_tmp"
: >"$zk_rust_tmp/conftest.rs"
zk_rust_native=`"$RUSTC" --crate-type staticlib --crate-name conftest $ZKRS_TARGET_ARGS \
  --print native-static-libs -o "$zk_rust_tmp/libconftest.a" "$zk_rust_tmp/conftest.rs" 2>&1 |
  sed -n 's/.*native-static-libs: //p'`
rm -rf "$zk_rust_tmp"
ZKRS_NATIVE_LIBS=
for zk_rust_lib in $zk_rust_native; do
  AS_CASE([$zk_rust_lib], [-lc|-lSystem], [continue])
  AS_CASE([" $ZKRS_NATIVE_LIBS "], [*" $zk_rust_lib "*], [continue])
  ZKRS_NATIVE_LIBS="${ZKRS_NATIVE_LIBS:+$ZKRS_NATIVE_LIBS }$zk_rust_lib"
done
AC_MSG_RESULT([${ZKRS_NATIVE_LIBS:-none}])
ZKS_CFLAGS=
ZK_RUST_CC_FLAG([-fsemantic-interposition], [ZKS_CFLAGS])
ZK_RUST_CC_FLAG([-fexceptions], [ZKS_CFLAGS])
ZKS_HARDEN_CFLAGS=
ZK_RUST_CC_FLAG([-U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=3], [ZKS_HARDEN_CFLAGS])
AS_IF([test "x$zk_rust_flag_ok" != xyes], [
  ZK_RUST_CC_FLAG([-U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=2], [ZKS_HARDEN_CFLAGS])
  AS_IF([test "x$zk_rust_flag_ok" != xyes], [AC_MSG_NOTICE([dropped the hardening flag -D_FORTIFY_SOURCE])])
])
for zk_rust_flag in -fstack-protector-strong -fstack-clash-protection -ftrivial-auto-var-init=zero \
    -fno-strict-overflow -Wformat=2 -Wformat-security; do
  ZK_RUST_CC_FLAG([$zk_rust_flag], [ZKS_HARDEN_CFLAGS])
  AS_IF([test "x$zk_rust_flag_ok" != xyes], [AC_MSG_NOTICE([dropped the hardening flag $zk_rust_flag])])
done
AS_CASE([$host_cpu], [aarch64*|arm64*], [
  ZK_RUST_CC_FLAG([-mbranch-protection=standard], [ZKS_HARDEN_CFLAGS])
  AS_IF([test "x$zk_rust_flag_ok" != xyes], [AC_MSG_NOTICE([dropped the hardening flag -mbranch-protection=standard])])
])
])

# ZK_RUST_TARGET_CPU
# ------------------
# The Rust code gets the code generation baseline of the C toolchain.
# abi/target-cpu.sh makes the decision of --with-rust-target-cpu. CMake
# and Maven use the same script. The probe compiles with CC, CPPFLAGS and
# CFLAGS. make runs cargo in the build directory, so the probe reads the
# cargo configuration of that directory. ZKRS_CARGO_WRAP runs cargo
# through the script, which adds the flag to the rustflags of the user.
AC_DEFUN([ZK_RUST_TARGET_CPU], [
ZKRS_TARGET_CPU=
ZKRS_CARGO_WRAP=
zk_rust_cpu_policy=explicit
zk_rust_cpu_lc=`echo "$with_rust_target_cpu" | tr ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz`
AS_CASE([$zk_rust_cpu_lc],
  [auto|yes], [with_rust_target_cpu=auto; zk_rust_cpu_policy=auto],
  [no|off], [with_rust_target_cpu=no])
AC_MSG_CHECKING([for the Rust target CPU])
AS_IF([test -n "$with_rust_staticlib"],
  [zk_rust_cpu_why="the prebuilt staticlib keeps the code generation of its build"],
  [test "x$with_rust_target_cpu" = xno],
  [zk_rust_cpu_why="the option is no"],
  [zk_rust_cpu_dir=`pwd`
   zk_rust_cpu_out=`$SHELL "$zk_rust_c_dir/abi/target-cpu.sh" probe "--choice=$with_rust_target_cpu" \
     "--rustc=$RUSTC" "--target=$RUST_TARGET" "--dir=$zk_rust_cpu_dir" "--cc=$CC" \
     "--cflags=${CPPFLAGS:+$CPPFLAGS }$CFLAGS" 2>&1` ||
     AC_MSG_ERROR([--with-rust-target-cpu=$with_rust_target_cpu: $zk_rust_cpu_out])
   ZKRS_TARGET_CPU=`echo "$zk_rust_cpu_out" | sed -n 's/^target_cpu=//p'`
   zk_rust_cpu_why=`echo "$zk_rust_cpu_out" | sed -n 's/^why=//p'`])
AS_IF([test -n "$ZKRS_TARGET_CPU"],
  [ZKRS_CARGO_WRAP="\$(SHELL) \$(ZKRS_SRCDIR)/abi/target-cpu.sh run --cpu=$ZKRS_TARGET_CPU --policy=$zk_rust_cpu_policy --target=\$(RUST_TARGET) --"
   AC_MSG_RESULT([$ZKRS_TARGET_CPU ($zk_rust_cpu_why)])],
  [AC_MSG_RESULT([none ($zk_rust_cpu_why)])])
])

# ZK_RUST_TLS_DEFAULT_PATHS
# -------------------------
# Resolves default certificate authority trust bundle and directory paths.
# Evaluates paths based on explicit configuration options, OpenSSL CLI
# environment introspection (OPENSSLDIR), or platform default certificate stores.
AC_DEFUN([ZK_RUST_TLS_DEFAULT_PATHS], [
ZK_TLS_DEFAULT_CERT_FILE=
ZK_TLS_DEFAULT_CERT_DIR=
zk_rust_paths_from=none
AS_CASE([$with_rust_tls_default_paths],
  [auto], [
    AS_CASE([$zk_rust_tls:$host_os], [no:*|*:mingw*|*:msys*|*:windows*], [], [
      zk_rust_ossl_bin=
      AS_CASE([$zk_user_with_openssl],
        [yes], [AC_PATH_PROG([OPENSSL_CLI], [openssl])
                zk_rust_ossl_bin=$OPENSSL_CLI],
        [no], [],
        [AS_IF([test -x "$zk_user_with_openssl/bin/openssl"],
           [zk_rust_ossl_bin=$zk_user_with_openssl/bin/openssl])])
      zk_rust_ossl_dir=
      AS_IF([test -n "$zk_rust_ossl_bin"], [
        zk_rust_ossl_dir=`"$zk_rust_ossl_bin" version -d 2>/dev/null | sed -n 's/^OPENSSLDIR: "\(.*\)"$/\1/p'`
        AS_IF([test -n "$zk_rust_ossl_dir"], [zk_rust_paths_from="$zk_rust_ossl_bin version -d"])
      ])
      AS_IF([test -z "$zk_rust_ossl_dir"], [
        for zk_rust_d in /usr/lib/ssl /etc/pki/tls /etc/ssl /usr/local/ssl \
            /usr/local/etc/openssl@3 /opt/homebrew/etc/openssl@3; do
          AS_IF([test -d "$zk_rust_d"],
            [zk_rust_ossl_dir=$zk_rust_d; zk_rust_paths_from="the platform list"; break])
        done
      ])
      AS_IF([test -n "$zk_rust_ossl_dir"], [
        ZK_TLS_DEFAULT_CERT_FILE="$zk_rust_ossl_dir/cert.pem"
        ZK_TLS_DEFAULT_CERT_DIR="$zk_rust_ossl_dir/certs"
      ])
    ])],
  [*:*:*], [AC_MSG_ERROR([--with-rust-tls-default-paths=$with_rust_tls_default_paths: give FILE:DIR with one colon])],
  [*:*], [
    ZK_TLS_DEFAULT_CERT_FILE=`echo "$with_rust_tls_default_paths" | sed 's/:.*//'`
    ZK_TLS_DEFAULT_CERT_DIR=`echo "$with_rust_tls_default_paths" | sed 's/^@<:@^:@:>@*://'`
    for zk_rust_d in "$ZK_TLS_DEFAULT_CERT_FILE" "$ZK_TLS_DEFAULT_CERT_DIR"; do
      AS_CASE([$zk_rust_d], [""|/*], [],
        [AC_MSG_ERROR([--with-rust-tls-default-paths=$with_rust_tls_default_paths: give absolute paths (FILE or DIR can be empty)])])
    done
    zk_rust_paths_from=--with-rust-tls-default-paths],
  [AC_MSG_ERROR([--with-rust-tls-default-paths=$with_rust_tls_default_paths: give FILE:DIR])])
AS_IF([test "x$zk_rust_tls" = xyes], [
  AC_MSG_CHECKING([for the default trust locations of the Rust client])
  AC_MSG_RESULT([file=${ZK_TLS_DEFAULT_CERT_FILE:-none} dir=${ZK_TLS_DEFAULT_CERT_DIR:-none} (from $zk_rust_paths_from)])
])
])

# ZK_RUST_TLS_CONFIG
# ------------------
# Configures TLS and SASL security subsystems for the Rust client core.
# Maps OpenSSL header discovery and configuration options to Rustls feature flags
# without linking against libssl or libcrypto. Configures Cyrus SASL compilation
# options, CA trust root definitions, and preprocessor definitions for feature-gated
# shim units.
AC_DEFUN([ZK_RUST_TLS_CONFIG], [
AS_CASE([$with_rust_tls], [yes|no], [],
  [AC_MSG_ERROR([--with-rust-tls=$with_rust_tls: give yes or no])])
AS_IF([test "x$with_rust_tls" = xyes && test "x$zk_user_with_openssl" = xno],
  [AC_MSG_ERROR([--with-rust-tls=yes contradicts --with-openssl=no: give only one of them])])
dnl Validate crypto provider selection independent of TLS enablement state.
AS_CASE([$with_rust_tls_provider],
  [ring|aws-lc-rs], [],
  [aws-lc-rs-fips], [
    AS_CASE([$host_os], [linux*], [],
      [AC_MSG_ERROR([--with-rust-tls-provider=aws-lc-rs-fips needs a Linux host, not $host_os])])
    AS_CASE([$host_cpu], [x86_64|aarch64], [],
      [AC_MSG_ERROR([--with-rust-tls-provider=aws-lc-rs-fips needs an x86_64 or aarch64 host, not $host_cpu])])],
  [AC_MSG_ERROR([--with-rust-tls-provider=$with_rust_tls_provider: give ring, aws-lc-rs or aws-lc-rs-fips])])
zk_rust_tls=no
AS_IF([test "x$with_openssl" != xno || test "x$with_rust_tls" = xyes], [zk_rust_tls=yes])
zk_rust_sasl=no
AS_IF([test "x$have_sasl" = xyes && test "x$with_sasl" != xno], [zk_rust_sasl=yes])
AS_IF([test "x$zk_rust_tls" = xno && test -n "$zk_rust_provider_given"],
  [AC_MSG_NOTICE([TLS is not built: --with-rust-tls-provider=$with_rust_tls_provider is not used])])
dnl Verify build tool dependencies (cmake, go) when building the FIPS crypto provider.
AS_IF([test "x$zk_rust_tls" = xyes && test "x$with_rust_tls_provider" = xaws-lc-rs-fips &&
       test -z "$with_rust_staticlib"], [
  AC_PATH_PROG([ZKRS_CMAKE], [cmake])
  AC_PATH_PROG([ZKRS_GO], [go])
  AS_IF([test -z "$ZKRS_CMAKE" || test -z "$ZKRS_GO"],
    [AC_MSG_ERROR([--with-rust-tls-provider=aws-lc-rs-fips needs cmake and go])])
])
ZKRS_CARGO_FEATURES=
ZKS_CONFIG_CPPFLAGS=
ZKS_SASL_CFLAGS=
ZKS_HARDEN_SASL_CFLAGS=
ZKRS_SASL_LIBS=
zk_rust_features=
AS_IF([test "x$zk_rust_tls" = xyes], [
  zk_rust_features="tls-$with_rust_tls_provider"
  ZKS_CONFIG_CPPFLAGS="-DZKS_WITH_TLS -DHAVE_OPENSSL_H"
])
dnl Configure Cyrus SASL compiler flags, include search paths, and warning suppressions.
AS_IF([test "x$zk_rust_sasl" = xyes], [
  zk_rust_features="${zk_rust_features:+$zk_rust_features,}sasl"
  ZKS_CONFIG_CPPFLAGS="${ZKS_CONFIG_CPPFLAGS:+$ZKS_CONFIG_CPPFLAGS }-DZKS_WITH_SASL -DHAVE_CYRUS_SASL_H"
  ZKRS_SASL_LIBS=-lsasl2
  ZKS_SASL_CFLAGS=-DHAVE_CYRUS_SASL_H
  AS_CASE([$with_sasl], [yes], [],
    [ZKS_SASL_CFLAGS="$ZKS_SASL_CFLAGS -isystem $with_sasl/include"])
  ZKS_HARDEN_SASL_CFLAGS=$ZKS_HARDEN_CFLAGS
  zk_rust_sasl_flags="-Wconversion -Wsign-conversion"
  AS_CASE([$host_cpu], [x86_64|i?86], [zk_rust_sasl_flags="$zk_rust_sasl_flags -fcf-protection"])
  dnl Suppress deprecation diagnostics on macOS when using system SDK SASL headers.
  AS_CASE([$host_os:$with_sasl], [darwin*:yes],
    [zk_rust_sasl_flags="$zk_rust_sasl_flags -Wno-deprecated-declarations"])
  for zk_rust_flag in $zk_rust_sasl_flags; do
    ZK_RUST_CC_FLAG([$zk_rust_flag], [ZKS_HARDEN_SASL_CFLAGS])
    AS_IF([test "x$zk_rust_flag_ok" != xyes], [AC_MSG_NOTICE([dropped the SASL glue flag $zk_rust_flag])])
  done
])
AS_IF([test -n "$zk_rust_features"], [ZKRS_CARGO_FEATURES="--features=$zk_rust_features"])
dnl Verify availability of openssl/ossl_typ.h for consumer utilities compiling against zookeeper.h.
zk_rust_cli_ssl=no
ZK_OPENSSL_DIR_CPPFLAGS=
AS_IF([test "x$zk_rust_tls" = xyes], [
  AS_CASE([$zk_user_with_openssl], [yes|no], [],
    [ZK_OPENSSL_DIR_CPPFLAGS="-I$zk_user_with_openssl/include"])
  zk_rust_saved_CPPFLAGS=$CPPFLAGS
  CPPFLAGS="$CPPFLAGS $ZK_OPENSSL_DIR_CPPFLAGS"
  AC_CHECK_HEADER([openssl/ossl_typ.h], [zk_rust_cli_ssl=yes])
  CPPFLAGS=$zk_rust_saved_CPPFLAGS
  AS_IF([test "x$zk_rust_cli_ssl" != xyes], [
    ZK_OPENSSL_DIR_CPPFLAGS=
    AC_MSG_WARN([no OpenSSL headers: cli and load_gen have no TLS option, and applications that define HAVE_OPENSSL_H need the headers])
  ])
])
dnl Verify feature flag compatibility of precompiled static libraries via zkrs_feature_tls symbol inspection.
AS_IF([test -n "$with_rust_staticlib"], [
  AC_MSG_CHECKING([whether $with_rust_staticlib has the TLS feature])
  zk_rust_lib_tls=no
  AS_IF([{ $NM --no-llvm-bc "$with_rust_staticlib"; $NM "$with_rust_staticlib"; } 2>/dev/null | grep -E ' @<:@A-Z@:>@ _?zkrs_feature_tls$' >/dev/null],
    [zk_rust_lib_tls=yes])
  AC_MSG_RESULT([$zk_rust_lib_tls])
  AS_IF([test "x$zk_rust_lib_tls" != "x$zk_rust_tls"],
    [AC_MSG_ERROR([the staticlib $with_rust_staticlib has tls=$zk_rust_lib_tls, but this configure has tls=$zk_rust_tls: build the staticlib with the TLS decision of abi/tls-probe.sh and the same --with-openssl and --with-rust-tls])])
])
ZK_RUST_TLS_DEFAULT_PATHS
AC_MSG_CHECKING([for the TLS and SASL build of the Rust client])
AC_MSG_RESULT([tls=$zk_rust_tls provider=$with_rust_tls_provider sasl=$zk_rust_sasl])
])
