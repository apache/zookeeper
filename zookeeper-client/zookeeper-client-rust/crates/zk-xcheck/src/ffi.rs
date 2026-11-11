// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Foreign function declarations interfacing with compiled C harness routines.
//!
//! Defines external C symbols implemented in `csrc/` and extracted legacy client units.
//! Invocations across this boundary are encapsulated within the safe abstraction module,
//! protected against concurrent mutation by the harness re-entrancy lock.

#![allow(unsafe_code)]

use std::ffi::{c_char, c_int, c_long, c_uint, c_ulong, c_ushort, c_void};

// Foreign function declarations matching C symbol signatures in `csrc/zkx_harness.c`
// and generated extract units.
unsafe extern "C" {
    pub(crate) fn zkx_char_is_signed() -> c_int;

    pub(crate) fn zkx_set_random_feed(vals: *const c_long, n: usize);
    pub(crate) fn zkx_random_calls() -> usize;
    pub(crate) fn zkx_set_drand_feed(vals: *const f64, n: usize);
    pub(crate) fn zkx_drand_calls_get() -> usize;
    pub(crate) fn zkx_setup_random_count() -> c_ulong;
    pub(crate) fn zkx_close_count() -> c_ulong;

    pub(crate) fn zkx_log_clear();
    pub(crate) fn zkx_log_count() -> c_int;
    pub(crate) fn zkx_log_get(
        i: c_int,
        level: *mut *const c_char,
        fmt: *mut *const c_char,
        msg: *mut *const c_char,
    ) -> c_int;

    pub(crate) fn zkx_gai_clear();
    pub(crate) fn zkx_gai_count() -> c_int;
    pub(crate) fn zkx_gai_get(
        i: c_int,
        host: *mut *const c_char,
        service: *mut *const c_char,
    ) -> c_int;

    pub(crate) fn zkx_ht_new(minsize: c_uint, unsigned_char: c_int) -> *mut c_void;
    pub(crate) fn zkx_ht_free(h: *mut c_void);
    pub(crate) fn zkx_ht_insert(h: *mut c_void, key: *const c_char, value: c_int) -> c_int;
    pub(crate) fn zkx_ht_search(h: *mut c_void, key: *const c_char, out: *mut c_int) -> c_int;
    pub(crate) fn zkx_ht_remove(h: *mut c_void, key: *const c_char, out: *mut c_int) -> c_int;
    pub(crate) fn zkx_ht_count(h: *mut c_void) -> c_uint;
    pub(crate) fn zkx_ht_table_length(h: *mut c_void) -> c_uint;
    pub(crate) fn zkx_ht_load_limit(h: *mut c_void) -> c_uint;
    pub(crate) fn zkx_ht_hash(h: *mut c_void, key: *const c_char) -> c_uint;
    pub(crate) fn zkx_ht_dump(
        h: *mut c_void,
        keys: *mut *const c_char,
        vals: *mut c_int,
        cap: c_int,
    ) -> c_int;

    pub(crate) fn zkx_djb2_signed(key: *mut c_void) -> c_uint;
    pub(crate) fn zkx_djb2_unsigned(key: *mut c_void) -> c_uint;

    pub(crate) fn zkx_av_clear();
    pub(crate) fn zkx_av_append(ip: c_uint, port: c_ushort) -> c_int;
    pub(crate) fn zkx_av_next(ip: *mut c_uint, port: *mut c_ushort) -> c_int;
    pub(crate) fn zkx_av_peek(ip: *mut c_uint, port: *mut c_ushort) -> c_int;
    pub(crate) fn zkx_av_hasnext() -> c_int;
    pub(crate) fn zkx_av_atend() -> c_int;
    pub(crate) fn zkx_av_cursor() -> c_uint;
    pub(crate) fn zkx_av_count() -> c_uint;
    pub(crate) fn zkx_av_get(i: c_uint, ip: *mut c_uint, port: *mut c_ushort) -> c_int;
    pub(crate) fn zkx_av_shuffle();
    pub(crate) fn zkx_av_eq(
        a_ips: *const c_uint,
        a_ports: *const c_ushort,
        na: c_uint,
        b_ips: *const c_uint,
        b_ports: *const c_ushort,
        nb: c_uint,
    ) -> c_int;

    pub(crate) fn zkx_is_valid_path(path: *const c_char, mode: c_int) -> c_int;
    pub(crate) fn zkx_is_sequence(mode: c_int) -> c_int;
    pub(crate) fn zkx_count_hosts(hosts: *const c_char) -> c_int;
    pub(crate) fn zkx_prepend(chroot: *const c_char, path: *const c_char) -> *mut c_char;
    pub(crate) fn zkx_sub_string(chroot: *const c_char, path: *const c_char) -> *mut c_char;
    pub(crate) fn zkx_free(p: *mut c_void);
    pub(crate) fn zkx_strtol0(s: *const c_char, used: *mut usize) -> c_long;
    pub(crate) fn zkx_long_to_int(v: c_long) -> c_int;
    pub(crate) fn zkx_resolve(hosts: *const c_char, permute: c_int) -> c_int;
    pub(crate) fn zkx_resolved_count() -> c_uint;
    pub(crate) fn zkx_resolved_get(i: c_uint, ip: *mut u8, port: *mut c_ushort) -> c_int;

    pub(crate) fn zkx_u_reset();
    pub(crate) fn zkx_u_set_connected(connected: c_int);
    pub(crate) fn zkx_u_connected() -> c_int;
    pub(crate) fn zkx_u_update(hosts: *const c_char, permute: c_int) -> c_int;
    pub(crate) fn zkx_u_cycle();
    pub(crate) fn zkx_u_reconfig() -> c_int;
    pub(crate) fn zkx_u_p_old() -> f64;
    pub(crate) fn zkx_u_p_new() -> f64;
    pub(crate) fn zkx_u_state() -> c_int;
    pub(crate) fn zkx_u_vec_count(which: c_int) -> c_uint;
    pub(crate) fn zkx_u_vec_next(which: c_int) -> c_uint;
    pub(crate) fn zkx_u_vec_get(which: c_int, i: c_uint, ip: *mut u8, port: *mut c_ushort)
    -> c_int;
    pub(crate) fn zkx_u_cur(ip: *mut u8, port: *mut c_ushort) -> c_int;
}

/// Generates module bindings for dual compiled watcher table drivers.
///
/// Compiles separate instances of the C watcher driver under signed and unsigned
/// character conventions to verify platform compatibility.
macro_rules! zt_driver {
    (
        $module:ident,
        $reset:literal,
        $set_default:literal,
        $set_chroot:literal,
        $activate:literal,
        $event:literal,
        $dlv_get:literal,
        $has:literal,
        $remove:literal,
        $dump:literal $(,)?
    ) => {
        pub(crate) mod $module {
            use std::ffi::{c_char, c_int};

            // Link bindings matching symbols generated for this `char` signedness variant.
            unsafe extern "C" {
                #[link_name = $reset]
                pub(crate) fn reset();
                #[link_name = $set_default]
                pub(crate) fn set_default(func: c_int, ctx: usize);
                #[link_name = $set_chroot]
                pub(crate) fn set_chroot(chroot: *const c_char);
                #[link_name = $activate]
                pub(crate) fn activate(table: c_int, path: *const c_char, func: c_int, ctx: usize);
                #[link_name = $event]
                pub(crate) fn event(event_type: c_int, path: *const c_char) -> c_int;
                #[link_name = $dlv_get]
                pub(crate) fn dlv_get(
                    i: c_int,
                    func: *mut c_int,
                    ctx: *mut usize,
                    event_type: *mut c_int,
                    path: *mut *const c_char,
                ) -> c_int;
                #[link_name = $has]
                pub(crate) fn has(
                    path: *const c_char,
                    wtype: c_int,
                    func: c_int,
                    ctx: usize,
                ) -> c_int;
                #[link_name = $remove]
                pub(crate) fn remove(path: *const c_char, wtype: c_int, func: c_int, ctx: usize);
                #[link_name = $dump]
                #[allow(clippy::too_many_arguments)]
                pub(crate) fn dump(
                    table: c_int,
                    keys: *mut *const c_char,
                    key_cap: c_int,
                    nkeys: *mut c_int,
                    key_idx: *mut c_int,
                    funcs: *mut c_int,
                    ctxs: *mut usize,
                    cap: c_int,
                ) -> c_int;
            }
        }
    };
}

zt_driver!(
    zts,
    "zkx_zts_reset",
    "zkx_zts_set_default",
    "zkx_zts_set_chroot",
    "zkx_zts_activate",
    "zkx_zts_event",
    "zkx_zts_dlv_get",
    "zkx_zts_has",
    "zkx_zts_remove",
    "zkx_zts_dump",
);

zt_driver!(
    ztu,
    "zkx_ztu_reset",
    "zkx_ztu_set_default",
    "zkx_ztu_set_chroot",
    "zkx_ztu_activate",
    "zkx_ztu_event",
    "zkx_ztu_dlv_get",
    "zkx_ztu_has",
    "zkx_ztu_remove",
    "zkx_ztu_dump",
);
