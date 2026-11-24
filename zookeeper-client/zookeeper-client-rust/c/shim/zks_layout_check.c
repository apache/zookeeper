/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Static ABI Layout and Type Verification.
 *
 * Enforces compile time structural assertions verifying the sizes, alignments,
 * and individual field offsets of all types declared in zkrs.h. Because zkrs.h is
 * generated from the repr(C) Rust definitions in zk-ffi via cbindgen, these assertions
 * guarantee that both the C and Rust toolchains agree precisely on memory layouts
 * across supported CPU architectures.
 *
 * Each assertion validates three target ABI configurations:
 *   1. 64-bit architectures (LP64 / LLP64).
 *   2. 32-bit architectures with 4-byte int64 alignment (i386 System V ABI).
 *   3. 32-bit architectures with 8-byte int64 alignment (ARM, Windows x86).
 *
 * Any structural modification to zk-ffi types will intentionally break this compilation
 * check until the corresponding ABI impacts are thoroughly audited and updated here.
 */

#include "zks_internal.h"

#if UINTPTR_MAX == UINT64_MAX
#define ZKS_L(v64, v32a4, v32a8) (v64)
#elif UINTPTR_MAX == UINT32_MAX && defined(__i386__) && !defined(_WIN32)
#define ZKS_L(v64, v32a4, v32a8) (v32a4)
#elif UINTPTR_MAX == UINT32_MAX
#define ZKS_L(v64, v32a4, v32a8) (v32a8)
#else
#error "zks_layout_check.c: no layout table for this pointer size"
#endif

#define ZKS_SIZE(T, a, b, c) _Static_assert(sizeof(T) == ZKS_L(a, b, c), "size of " #T)
#define ZKS_ALIGN(T, a, b, c) _Static_assert(_Alignof(T) == ZKS_L(a, b, c), "alignment of " #T)
#define ZKS_OFF(T, f, a, b, c) \
    _Static_assert(offsetof(T, f) == ZKS_L(a, b, c), "offset of " #T "." #f)

_Static_assert(sizeof(int) == sizeof(int32_t), "int and int32_t differ");
_Static_assert(sizeof(zkrs_outbox) == sizeof(void *), "zkrs_outbox");
/* Default watcher storage relies on lock-free pointer atomics for signal-safe access. */
_Static_assert(__atomic_always_lock_free(sizeof(void *), 0), "pointer atomics are lock-free");
_Static_assert(sizeof(watcher_fn) == sizeof(void *), "watcher_fn size");
_Static_assert(sizeof(zkrs_watcher) == 2 * sizeof(uintptr_t), "zkrs_watcher");
_Static_assert(sizeof(zkrs_str) == 2 * sizeof(void *), "zkrs_str");
_Static_assert(sizeof(zkrs_buf) == 2 * sizeof(void *), "zkrs_buf");
_Static_assert(sizeof(zkrs_sockaddr_view) == 32, "zkrs_sockaddr_view");
_Static_assert(offsetof(zkrs_interest_out, fd) == 0, "zkrs_interest_out");

/*
 * Validates that the selected target ABI column matches the structural alignment
 * of int64_t. Uses offsetof within a composite structure because _Alignof on
 * scalar types can report preferred rather than member alignment in C99 modes.
 */
struct zks_i64_probe {
    char c;
    int64_t v;
};
_Static_assert(offsetof(struct zks_i64_probe, v) == ZKS_L(8, 4, 8), "layout column of this target");

/* Enumerations declared repr(C) in Rust map directly to C int storage. */
ZKS_SIZE(zkrs_step, 4, 4, 4);
ZKS_SIZE(zkrs_status, 4, 4, 4);
ZKS_SIZE(zkrs_item_type, 4, 4, 4);
ZKS_SIZE(zkrs_thread_op, 4, 4, 4);

ZKS_SIZE(zkrs_outbox, 8, 4, 4);
ZKS_ALIGN(zkrs_outbox, 8, 4, 4);
ZKS_OFF(zkrs_outbox, impl, 0, 0, 0);

ZKS_SIZE(zkrs_sockaddr_view, 32, 32, 32);
ZKS_ALIGN(zkrs_sockaddr_view, 4, 4, 4);
ZKS_OFF(zkrs_sockaddr_view, family, 0, 0, 0);
ZKS_OFF(zkrs_sockaddr_view, port, 4, 4, 4);
ZKS_OFF(zkrs_sockaddr_view, addr, 6, 6, 6);
ZKS_OFF(zkrs_sockaddr_view, flowinfo, 24, 24, 24);
ZKS_OFF(zkrs_sockaddr_view, scope_id, 28, 28, 28);

ZKS_SIZE(zkrs_init_args, 112, 84, 88);
ZKS_ALIGN(zkrs_init_args, 8, 4, 8);
ZKS_OFF(zkrs_init_args, flavor, 0, 0, 0);
ZKS_OFF(zkrs_init_args, hosts, 8, 4, 4);
ZKS_OFF(zkrs_init_args, recv_timeout, 16, 8, 8);
ZKS_OFF(zkrs_init_args, have_client_id, 20, 12, 12);
ZKS_OFF(zkrs_init_args, client_id, 24, 16, 16);
ZKS_OFF(zkrs_init_args, client_passwd, 32, 24, 24);
ZKS_OFF(zkrs_init_args, flags, 48, 40, 40);
ZKS_OFF(zkrs_init_args, tls, 52, 44, 44);
ZKS_OFF(zkrs_init_args, tls_ca, 56, 48, 48);
ZKS_OFF(zkrs_init_args, tls_cert, 64, 52, 52);
ZKS_OFF(zkrs_init_args, tls_key, 72, 56, 56);
ZKS_OFF(zkrs_init_args, tls_passwd, 80, 60, 60);
ZKS_OFF(zkrs_init_args, tls_env_allowed, 88, 64, 64);
ZKS_OFF(zkrs_init_args, sasl, 92, 68, 68);
ZKS_OFF(zkrs_init_args, xid_seed, 96, 72, 72);
ZKS_OFF(zkrs_init_args, sock_cloexec, 100, 76, 76);
ZKS_OFF(zkrs_init_args, c_char_signed, 104, 80, 80);

ZKS_SIZE(zkrs_watcher, 16, 8, 8);
ZKS_ALIGN(zkrs_watcher, 8, 4, 4);
ZKS_OFF(zkrs_watcher, fn, 0, 0, 0);
ZKS_OFF(zkrs_watcher, ctx, 8, 4, 4);

ZKS_SIZE(zkrs_host_vtable, 72, 36, 36);
ZKS_ALIGN(zkrs_host_vtable, 8, 4, 4);
ZKS_OFF(zkrs_host_vtable, discard_token, 0, 0, 0);
ZKS_OFF(zkrs_host_vtable, snapshot_default_watcher, 8, 4, 4);
ZKS_OFF(zkrs_host_vtable, store_client_id, 16, 8, 8);
ZKS_OFF(zkrs_host_vtable, log_enabled, 24, 12, 12);
ZKS_OFF(zkrs_host_vtable, conn_permute_disabled, 32, 16, 16);
ZKS_OFF(zkrs_host_vtable, rng_random, 40, 20, 20);
ZKS_OFF(zkrs_host_vtable, rng_drand48, 48, 24, 24);
ZKS_OFF(zkrs_host_vtable, rng_reseed, 56, 28, 28);
ZKS_OFF(zkrs_host_vtable, resolve, 64, 32, 32);

ZKS_SIZE(zkrs_interest_out, 24, 16, 16);
ZKS_ALIGN(zkrs_interest_out, 8, 4, 8);
ZKS_OFF(zkrs_interest_out, fd, 0, 0, 0);
ZKS_OFF(zkrs_interest_out, interest, 8, 4, 4);
ZKS_OFF(zkrs_interest_out, timeout_ms, 16, 8, 8);

ZKS_SIZE(zkrs_str, 16, 8, 8);
ZKS_ALIGN(zkrs_str, 8, 4, 4);
ZKS_OFF(zkrs_str, ptr, 0, 0, 0);
ZKS_OFF(zkrs_str, len, 8, 4, 4);

ZKS_SIZE(zkrs_log_arg, 88, 72, 80);
ZKS_ALIGN(zkrs_log_arg, 8, 4, 8);
ZKS_OFF(zkrs_log_arg, kind, 0, 0, 0);
ZKS_OFF(zkrs_log_arg, i, 8, 4, 8);
ZKS_OFF(zkrs_log_arg, u, 16, 12, 16);
ZKS_OFF(zkrs_log_arg, p, 24, 20, 24);
ZKS_OFF(zkrs_log_arg, s, 32, 24, 28);
ZKS_OFF(zkrs_log_arg, ep, 48, 32, 36);
ZKS_OFF(zkrs_log_arg, gai_rc, 80, 64, 68);
ZKS_OFF(zkrs_log_arg, err, 84, 68, 72);

ZKS_SIZE(zkrs_log_view, 40, 28, 32);
ZKS_ALIGN(zkrs_log_view, 8, 4, 8);
ZKS_OFF(zkrs_log_view, level, 0, 0, 0);
ZKS_OFF(zkrs_log_view, site, 4, 4, 4);
ZKS_OFF(zkrs_log_view, tv_sec, 8, 8, 8);
ZKS_OFF(zkrs_log_view, tv_usec, 16, 16, 16);
ZKS_OFF(zkrs_log_view, args, 24, 20, 20);
ZKS_OFF(zkrs_log_view, nargs, 32, 24, 24);

ZKS_SIZE(zkrs_buf, 16, 8, 8);
ZKS_ALIGN(zkrs_buf, 8, 4, 4);
ZKS_OFF(zkrs_buf, ptr, 0, 0, 0);
ZKS_OFF(zkrs_buf, len, 8, 4, 4);

ZKS_SIZE(zkrs_stat, 72, 68, 72);
ZKS_ALIGN(zkrs_stat, 8, 4, 8);
ZKS_OFF(zkrs_stat, czxid, 0, 0, 0);
ZKS_OFF(zkrs_stat, mzxid, 8, 8, 8);
ZKS_OFF(zkrs_stat, ctime, 16, 16, 16);
ZKS_OFF(zkrs_stat, mtime, 24, 24, 24);
ZKS_OFF(zkrs_stat, version, 32, 32, 32);
ZKS_OFF(zkrs_stat, cversion, 36, 36, 36);
ZKS_OFF(zkrs_stat, aversion, 40, 40, 40);
ZKS_OFF(zkrs_stat, ephemeralOwner, 48, 44, 48);
ZKS_OFF(zkrs_stat, dataLength, 56, 52, 56);
ZKS_OFF(zkrs_stat, numChildren, 60, 56, 60);
ZKS_OFF(zkrs_stat, pzxid, 64, 60, 64);

ZKS_SIZE(zkrs_acl_view, 40, 20, 20);
ZKS_ALIGN(zkrs_acl_view, 8, 4, 4);
ZKS_OFF(zkrs_acl_view, perms, 0, 0, 0);
ZKS_OFF(zkrs_acl_view, scheme, 8, 4, 4);
ZKS_OFF(zkrs_acl_view, id, 24, 12, 12);

ZKS_SIZE(zkrs_multi_op, 32, 20, 20);
ZKS_ALIGN(zkrs_multi_op, 8, 4, 4);
ZKS_OFF(zkrs_multi_op, err, 0, 0, 0);
ZKS_OFF(zkrs_multi_op, failed, 4, 4, 4);
ZKS_OFF(zkrs_multi_op, path, 8, 8, 8);
ZKS_OFF(zkrs_multi_op, stat, 24, 16, 16);

ZKS_SIZE(zkrs_result_view, 120, 68, 68);
ZKS_ALIGN(zkrs_result_view, 8, 4, 4);
ZKS_OFF(zkrs_result_view, kind, 0, 0, 0);
ZKS_OFF(zkrs_result_view, origin, 4, 4, 4);
ZKS_OFF(zkrs_result_view, rc, 8, 8, 8);
ZKS_OFF(zkrs_result_view, failed, 12, 12, 12);
ZKS_OFF(zkrs_result_view, data, 16, 16, 16);
ZKS_OFF(zkrs_result_view, stat, 32, 24, 24);
ZKS_OFF(zkrs_result_view, path, 40, 28, 28);
ZKS_OFF(zkrs_result_view, children, 56, 36, 36);
ZKS_OFF(zkrs_result_view, nchildren, 64, 40, 40);
ZKS_OFF(zkrs_result_view, acl, 72, 44, 44);
ZKS_OFF(zkrs_result_view, nacl, 80, 48, 48);
ZKS_OFF(zkrs_result_view, ops, 88, 52, 52);
ZKS_OFF(zkrs_result_view, nops, 96, 56, 56);
ZKS_OFF(zkrs_result_view, delivery_logs, 104, 60, 60);
ZKS_OFF(zkrs_result_view, n_delivery_logs, 112, 64, 64);

ZKS_SIZE(zkrs_watch_event_view, 56, 32, 32);
ZKS_ALIGN(zkrs_watch_event_view, 8, 4, 4);
ZKS_OFF(zkrs_watch_event_view, type, 0, 0, 0);
ZKS_OFF(zkrs_watch_event_view, state, 4, 4, 4);
ZKS_OFF(zkrs_watch_event_view, path, 8, 8, 8);
ZKS_OFF(zkrs_watch_event_view, list, 24, 16, 16);
ZKS_OFF(zkrs_watch_event_view, n, 32, 20, 20);
ZKS_OFF(zkrs_watch_event_view, delivery_logs, 40, 24, 24);
ZKS_OFF(zkrs_watch_event_view, n_delivery_logs, 48, 28, 28);

ZKS_SIZE(zkrs_item, 56, 32, 32);
ZKS_ALIGN(zkrs_item, 8, 4, 4);
ZKS_OFF(zkrs_item, type, 0, 0, 0);
ZKS_OFF(zkrs_item, log, 8, 4, 4);
ZKS_OFF(zkrs_item, token, 16, 8, 8);
ZKS_OFF(zkrs_item, result, 24, 12, 12);
ZKS_OFF(zkrs_item, watch, 32, 16, 16);
ZKS_OFF(zkrs_item, sasl, 40, 20, 20);
ZKS_OFF(zkrs_item, thread_op, 48, 24, 24);
ZKS_OFF(zkrs_item, thread_role, 52, 28, 28);

ZKS_SIZE(zkrs_bytes, 16, 12, 12);
ZKS_ALIGN(zkrs_bytes, 8, 4, 4);
ZKS_OFF(zkrs_bytes, p, 0, 0, 0);
ZKS_OFF(zkrs_bytes, len, 8, 4, 4);
ZKS_OFF(zkrs_bytes, is_null, 12, 8, 8);

ZKS_SIZE(zkrs_sasl_call, 56, 44, 44);
ZKS_ALIGN(zkrs_sasl_call, 8, 4, 4);
ZKS_OFF(zkrs_sasl_call, kind, 0, 0, 0);
ZKS_OFF(zkrs_sasl_call, gen, 4, 4, 4);
ZKS_OFF(zkrs_sasl_call, local, 8, 8, 8);
ZKS_OFF(zkrs_sasl_call, peer, 24, 20, 20);
ZKS_OFF(zkrs_sasl_call, token, 40, 32, 32);

ZKS_SIZE(zkrs_sasl_out, 40, 28, 28);
ZKS_ALIGN(zkrs_sasl_out, 8, 4, 4);
ZKS_OFF(zkrs_sasl_out, status, 0, 0, 0);
ZKS_OFF(zkrs_sasl_out, mech, 8, 4, 4);
ZKS_OFF(zkrs_sasl_out, token, 24, 16, 16);

ZKS_SIZE(zkrs_multi_copy, 96, 88, 96);
ZKS_ALIGN(zkrs_multi_copy, 8, 4, 8);
ZKS_OFF(zkrs_multi_copy, err, 0, 0, 0);
ZKS_OFF(zkrs_multi_copy, failed, 4, 4, 4);
ZKS_OFF(zkrs_multi_copy, path, 8, 8, 8);
ZKS_OFF(zkrs_multi_copy, path_len, 16, 12, 12);
ZKS_OFF(zkrs_multi_copy, has_stat, 20, 16, 16);
ZKS_OFF(zkrs_multi_copy, stat, 24, 20, 24);

ZKS_SIZE(zkrs_result_dst, 72, 36, 36);
ZKS_ALIGN(zkrs_result_dst, 8, 4, 4);
ZKS_OFF(zkrs_result_dst, stat, 0, 0, 0);
ZKS_OFF(zkrs_result_dst, value, 8, 4, 4);
ZKS_OFF(zkrs_result_dst, value_len, 16, 8, 8);
ZKS_OFF(zkrs_result_dst, strings, 24, 12, 12);
ZKS_OFF(zkrs_result_dst, acl, 32, 16, 16);
ZKS_OFF(zkrs_result_dst, str, 40, 20, 20);
ZKS_OFF(zkrs_result_dst, str_len, 48, 24, 24);
ZKS_OFF(zkrs_result_dst, ops, 56, 28, 28);
ZKS_OFF(zkrs_result_dst, nops, 64, 32, 32);

ZKS_SIZE(zkrs_sync_src, 80, 48, 48);
ZKS_ALIGN(zkrs_sync_src, 8, 4, 4);
ZKS_OFF(zkrs_sync_src, kind, 0, 0, 0);
ZKS_OFF(zkrs_sync_src, rc, 4, 4, 4);
ZKS_OFF(zkrs_sync_src, failed, 8, 8, 8);
ZKS_OFF(zkrs_sync_src, origin, 12, 12, 12);
ZKS_OFF(zkrs_sync_src, data, 16, 16, 16);
ZKS_OFF(zkrs_sync_src, data_len, 24, 20, 20);
ZKS_OFF(zkrs_sync_src, path, 32, 24, 24);
ZKS_OFF(zkrs_sync_src, path_len, 40, 28, 28);
ZKS_OFF(zkrs_sync_src, stat, 48, 32, 32);
ZKS_OFF(zkrs_sync_src, ops, 56, 36, 36);
ZKS_OFF(zkrs_sync_src, nops, 64, 40, 40);
ZKS_OFF(zkrs_sync_src, op_kind, 72, 44, 44);

ZKS_SIZE(zkrs_sync_dst, 56, 28, 28);
ZKS_ALIGN(zkrs_sync_dst, 8, 4, 4);
ZKS_OFF(zkrs_sync_dst, buffer, 0, 0, 0);
ZKS_OFF(zkrs_sync_dst, buffer_len, 8, 4, 4);
ZKS_OFF(zkrs_sync_dst, path_buffer, 16, 8, 8);
ZKS_OFF(zkrs_sync_dst, path_buffer_len, 24, 12, 12);
ZKS_OFF(zkrs_sync_dst, stat, 32, 16, 16);
ZKS_OFF(zkrs_sync_dst, results, 40, 20, 20);
ZKS_OFF(zkrs_sync_dst, count, 48, 24, 24);

ZKS_SIZE(zkrs_request, 136, 84, 96);
ZKS_ALIGN(zkrs_request, 8, 4, 8);
ZKS_OFF(zkrs_request, op, 0, 0, 0);
ZKS_OFF(zkrs_request, flags, 4, 4, 4);
ZKS_OFF(zkrs_request, path, 8, 8, 8);
ZKS_OFF(zkrs_request, data, 16, 12, 12);
ZKS_OFF(zkrs_request, datalen, 24, 16, 16);
ZKS_OFF(zkrs_request, acl, 32, 20, 20);
ZKS_OFF(zkrs_request, mode, 40, 24, 24);
ZKS_OFF(zkrs_request, ttl, 48, 28, 32);
ZKS_OFF(zkrs_request, version, 56, 36, 40);
ZKS_OFF(zkrs_request, reconfig_version, 64, 40, 48);
ZKS_OFF(zkrs_request, joining, 72, 48, 56);
ZKS_OFF(zkrs_request, leaving, 80, 52, 60);
ZKS_OFF(zkrs_request, members, 88, 56, 64);
ZKS_OFF(zkrs_request, watch_fn, 96, 60, 68);
ZKS_OFF(zkrs_request, watch_ctx, 104, 64, 72);
ZKS_OFF(zkrs_request, wtype, 112, 68, 76);
ZKS_OFF(zkrs_request, ops, 120, 72, 80);
ZKS_OFF(zkrs_request, ops_len, 128, 76, 84);
ZKS_OFF(zkrs_request, count, 132, 80, 88);
