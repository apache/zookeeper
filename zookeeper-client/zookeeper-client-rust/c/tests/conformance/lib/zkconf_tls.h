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
 * TLS conformance test support library and TLS record proxy interface.
 * Test-only support code; never installed or shipped.
 *
 * Because `zookeeper.h` declares `zookeeper_init_ssl` only when
 * `HAVE_OPENSSL_H` is defined, TLS scenarios resolve `zookeeper_init_ssl`
 * dynamically via `dlsym`. Server endpoints and certificate directories are
 * supplied by `run.sh --tls` via `ZKCONF_TLS_HOSTS` and `ZKCONF_TLS_DIR`. Since
 * `zookeeper_init_ssl` does not accept a per-handle log callback, `zkt_setup`
 * redirects global library logging to a temporary stream file for inspection.
 *
 * In addition, provides an in-process TLS record-layer proxy that parses and
 * forwards discrete TLS records while recording content types and payload
 * lengths, and supports fault injection (record corruption, duplicate replay,
 * post-handshake record coalescing, and deterministic stream EOF).
 */

#ifndef ZKCONF_TLS_H_
#define ZKCONF_TLS_H_

#include <stddef.h>
#include <zookeeper.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Resolves `zookeeper_init_ssl` and certificate directories, awaits readiness
 * on the first `nservers` TLS ports, and opens the log capture stream. Returns
 * 0 on success, or -1 after recording a `skip` or failure event.
 */
int zkt_setup(int nservers);

/* Returns the TLS `host:port` for server `index`, or a comma-separated list of `count` hosts. */
const char *zkt_host(int index);
const char *zkt_hosts(int count);

/* Returns the `gencerts.sh` directory, `zk-testkit` TLS fixture directory, or `ZKCONF_OUT` path. */
const char *zkt_certs(void);
const char *zkt_fix(void);
const char *zkt_out(void);

/*
 * Invokes `zookeeper_init_ssl` with `zkc_watcher` and context label `ctx`,
 * recording the call in the trace and registering the handle in the `st` poll
 * loop.
 */
zhandle_t *zkt_init(const char *hosts, const char *cert, int timeout_ms,
                    const clientid_t *id, const char *ctx, int flags);
/* Invokes `zookeeper_init_ssl` directly without recording a trace event. */
zhandle_t *zkt_init_raw(const char *hosts, const char *cert, watcher_fn fn,
                        int timeout_ms, const clientid_t *id, void *ctx,
                        int flags);

/* Copies `in` to `out`, normalizing certificate, fixture, and output directory paths. */
void zkt_norm_paths(char *out, size_t outlen, const char *in);

/*
 * Log stream capture utilities. `zkt_log_reset` truncates the log stream file;
 * `zkt_log_count` counts lines containing `needle`; `zkt_log_record` appends
 * each unique matching line (starting at `"ZOO_"` with paths and ports
 * normalized, and `errno` elided when `hide_errno != 0`) to the trace.
 */
void zkt_log_reset(void);
int zkt_log_count(const char *needle);
void zkt_log_record(const char *label, const char *needle, int hide_errno);

/* Dynamic wrappers around `zkconf_preload.c` (`no-op` / `-1` when not preloaded). */
int zkt_have_preload(void);
void zkt_connect_mode(int mode);
int zkt_connect_count(void);
long zkt_connect_time(int index);

/* Monotonic wall-clock time and process CPU time (user + system) in milliseconds. */
long zkt_now_ms(void);
long zkt_cpu_ms(void);

/* Formats `errno` value `e` as a symbolic constant name for trace output. */
const char *zkt_errno(int e);

/* Writes a deterministic Ed25519 PKCS#8 private key PEM file (RFC 8410) to `path`. */
int zkt_write_ed25519(const char *path);

#ifndef THREADED
/*
 * Executes one single threaded event loop iteration (`zookeeper_interest`,
 * `select` bounded to `max_ms`, and `zookeeper_process`). Returns the first
 * non-`ZOK` (and non-`ZNOTHING`) return code, setting `*where` to `"interest"`
 * or `"process"`, `*interest` to the interest bitmask, `*err` to `errno`, and
 * `*mid` to `zoo_state(zh)` immediately after `zookeeper_interest`.
 */
int zkt_st_pass(zhandle_t *zh, int max_ms, int *interest, int *err,
                const char **where, int *mid);
#endif

/* TLS record proxy traffic direction constants. */
#define ZKT_C2S 0
#define ZKT_S2C 1

/* TLS record proxy fault-injection actions (`zkt_proxy_arm`). */
#define ZKT_PX_PASS 0
/* Flips bits in the final byte of the next server ApplicationData (type 23) record. */
#define ZKT_PX_CORRUPT 1
/* Duplicates the next server ApplicationData (type 23) record in a single TCP send. */
#define ZKT_PX_REPLAY 2
/*
 * Buffers server records following the client `ConnectRequest` until upstream
 * EOF, then flushes all buffered records in a single write and closes the
 * client write half.
 */
#define ZKT_PX_COALESCE 3
/* Immediately half-closes (`SHUT_WR`) the client stream and closes the upstream socket. */
#define ZKT_PX_EOF 4

/* Starts the TLS record proxy forwarding to `target`, returning `"127.0.0.1:PORT"` or NULL. */
const char *zkt_proxy_start(const char *target);
/* Redirects subsequent proxy connections to `target`. */
void zkt_proxy_target(const char *target);
void zkt_proxy_arm(int action);
/* Returns the current record event sequence number. */
long zkt_proxy_mark(void);
/* Returns the 1-based count of accepted proxy connections. */
int zkt_proxy_conns(void);
/*
 * Counts records in direction `dir` with sequence number `>= since` on
 * connection `conn` (`0` = any connection) matching TLS content `type` (`0` = any).
 */
int zkt_proxy_count(int dir, long since, int conn, int type);
/* Returns the sequence number of the last record in direction `dir` on `conn`, or -1. */
long zkt_proxy_last(int dir, int conn);
/*
 * Returns the sequence number of the first record in direction `dir` on `conn`
 * with sequence number `>= since`, or -1.
 */
long zkt_proxy_first(int dir, int conn, long since);
/* Blocks up to `timeout_ms` until client connection `conn` reaches EOF (returns 0 on success). */
int zkt_proxy_wait_end(int conn, int timeout_ms);
/* Returns the total byte length (and optional record count) of the last coalesced send. */
long zkt_proxy_coalesced(int *records);
/* Formats recorded TLS record events for `conn` since `since` as `"C23/66 S23/40 ..."`. */
void zkt_proxy_events(char *buf, size_t len, int conn, long since);
void zkt_proxy_stop(void);

#ifdef __cplusplus
}
#endif

#endif /* ZKCONF_TLS_H_ */
