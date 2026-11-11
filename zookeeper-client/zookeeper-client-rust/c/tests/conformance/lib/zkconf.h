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
 * Differential conformance test harness and trace recorder.
 * Test-only support code; never installed or shipped.
 *
 * Conformance scenario binaries compile once against the public C headers, link
 * against the legacy C library, and bind the target implementation (`c` or
 * `rust`) at runtime via `DYLD_LIBRARY_PATH` / `LD_LIBRARY_PATH`. The recorder
 * emits a normalized event and log trace so deterministic scenarios produce
 * identical transcripts across both implementations.
 *
 * Normalization rules:
 * - Session IDs and zxids are assigned sequential ordinal labels
 *   (`S<n>` and `Z<n>`) in order of first observation.
 * - Callback contexts use caller supplied string labels; raw pointer
 *   addresses in log messages are normalized to `PTR`.
 * - Threads are labeled by role (`app`, `L1` for the thread delivering the
 *   first watcher event, `L2`, ...).
 * - Configured TCP ports are replaced with symbolic port names (`P1`, ...).
 * - Log lines strip timestamps, PIDs, and TIDs while preserving severity,
 *   `func@line`, and message text.
 *
 * Environment variables (populated by `run.sh`):
 *   ZKCONF_CORE    target implementation under test (`"c"` or `"rust"`)
 *   ZKCONF_LIBDIR  expected directory of the loaded `libzookeeper` image
 *   ZKCONF_OUT     output path prefix (`<prefix>.trace`, `.log`, and `.dev`)
 *   ZKCONF_HOSTS   comma-separated list of active server `host:port` endpoints
 *   ZKCONF_DEAD    unbound `host:port` endpoint where no server is listening
 *   ZKCONF_PORTS   `"port=name,..."` mapping table for port normalization
 *   ZKCONF_CTL     server lifecycle helper command (`CTL start|stop INDEX`)
 */

#ifndef ZKCONF_H_
#define ZKCONF_H_

#include <stdint.h>
#include <stddef.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <zookeeper.h>

/*
 * Socket send flag for in-process test proxies. Suppressing `SIGPIPE` on proxy
 * sockets ensures that any `SIGPIPE` delivered during a test originates from
 * the client library under test. On platforms lacking `MSG_NOSIGNAL`,
 * `ZKC_NOSIGNAL` falls back to 0 and `zkc_init` installs `SIG_IGN` for `SIGPIPE`.
 */
#ifdef MSG_NOSIGNAL
#define ZKC_NOSIGNAL MSG_NOSIGNAL
#else
#define ZKC_NOSIGNAL 0
#endif

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Initializes the test harness; exits immediately after printing metadata if
 * `--describe` is passed.
 */
void zkc_init(int argc, char **argv, const char *test_id, int nservers,
              const char *mode, const char *logmode);
int zkc_finish(void);

/* Queries the active implementation (`"c"` or `"rust"`) and configured server endpoints. */
int zkc_is_rust(void);
const char *zkc_core(void);
const char *zkc_host(int index);
const char *zkc_hosts(int count);
const char *zkc_dead(void);

/* Thread role assignment: `"app"` for the main thread, `"L1"`, `"L2"`, ... for library threads. */
void zkc_role_app(void);
const char *zkc_role(void);

/* Appends a normalized event line prefixed with the calling thread's role. */
void zkc_ev(const char *kind, const char *fmt, ...)
    __attribute__((format(printf, 2, 3)));

/* Value normalizers returning strings valid for the lifetime of the test process. */
const char *zkc_rc(int rc);
const char *zkc_sid(int64_t session_id);
const char *zkc_zxid(int64_t zxid);
const char *zkc_state(int state);
const char *zkc_type(int type);
const char *zkc_lbl(const void *ctx);
void zkc_stat_str(char *buf, size_t len, const struct Stat *stat);
void zkc_norm_text(char *out, size_t outlen, const char *in);

/* Verifies that no internal Rust stack frames appear below the current C callback. */
void zkc_frame_check(const char *site);

/* Standard trace-recording callbacks expecting a string literal or NULL context pointer. */
void zkc_watcher(zhandle_t *zh, int type, int state, const char *path,
                 void *ctx);
void zkc_void_cb(int rc, const void *d);
void zkc_stat_cb(int rc, const struct Stat *s, const void *d);
void zkc_data_cb(int rc, const char *v, int len, const struct Stat *s,
                 const void *d);
void zkc_strings_cb(int rc, const struct String_vector *sv, const void *d);
void zkc_strings_stat_cb(int rc, const struct String_vector *sv,
                         const struct Stat *s, const void *d);
void zkc_string_cb(int rc, const char *v, const void *d);
void zkc_string_stat_cb(int rc, const char *v, const struct Stat *s,
                        const void *d);
void zkc_acl_cb(int rc, struct ACL_vector *acl, struct Stat *s,
                const void *d);
void zkc_log_cb(const char *msg);

/* Optional post-recording callback hooks invoked from completion and watcher callbacks. */
typedef void (*zkc_hook_fn)(const char *kind, int rc, const void *d);
void zkc_set_hook(zkc_hook_fn hook);
typedef void (*zkc_whook_fn)(zhandle_t *zh, int type, int state,
                             const char *path, void *ctx);
void zkc_set_watch_hook(zkc_whook_fn hook);

/*
 * Bounded event-wait primitives. Supported keys: `"cb"`, `"watch"`,
 * `"session:<STATE>"` (any watcher), and `"session:<STATE>@<ctx>"` (watcher with
 * matching context label). In single threaded (`st`) builds, wait calls drive
 * the handle poll registry directly; `zkc_sleep_ms` performs a pure sleep
 * without driving registered handles.
 */
int zkc_await(const char *key, int n, int timeout_ms);
int zkc_await_none(const char *key, int wait_ms);
void zkc_sleep_ms(int ms);

/*
 * Opens a session via `zookeeper_init2` and awaits `ZOO_CONNECTED_STATE`. In
 * `st` builds, registers the handle in the poll loop; `zkc_close` deregisters
 * it prior to invoking `zookeeper_close`.
 */
zhandle_t *zkc_connect(const char *hosts, int timeout_ms, int flags);
void zkc_close(zhandle_t *zh);

/*
 * single threaded (`st`) poll registry. Mirrors `cli_st` by querying
 * `zookeeper_interest` across all registered handles, multiplexing readiness
 * via `select()` using the minimum timeout, and invoking `zookeeper_process` on
 * ready handles. Callbacks that close a handle re-entrantly must call
 * `zkc_poll_remove` first. In `mt` builds, registry operations are no-ops and
 * `zkc_poll` simply sleeps.
 */
int zkc_is_st(void);
void zkc_poll_add(zhandle_t *zh);
void zkc_poll_remove(zhandle_t *zh);
/* Returns 1 if `zh` is currently registered in the `st` poll loop. */
int zkc_polled(const zhandle_t *zh);
/* Drives the `st` poll registry for `ms` milliseconds, returning the iteration count. */
int zkc_poll(int ms);
/* Executes a single `st` poll pass bounded to `ms` milliseconds in `select()`. */
void zkc_poll_once(int ms);

/*
 * Driving-call scope tracking. Brackets each `st` driver call with
 * `zkc_in_call(1)` and `zkc_in_call(0)` so callback trace records include
 * `in=<call>` (or `in=none` if invoked outside any driver entry point).
 */
void zkc_in_call(int entering);
const char *zkc_call_name(void);
#ifndef THREADED
int zkc_interest(zhandle_t *zh, int *fd, int *interest, struct timeval *tv);
int zkc_process(zhandle_t *zh, int events);
#endif

/*
 * In-process TCP relay proxy for connection-loss and flush tests. Listens on an
 * ephemeral `127.0.0.1` port normalized to `PX` in trace output and forwards
 * accepted connections to `target`. Because the proxy runs on a background
 * thread, thread-count baselines must be captured before starting it.
 *
 * `zkc_proxy_hold(1)` suspends server-to-client forwarding until
 * `zkc_proxy_hold(0)`. `zkc_proxy_reset()` terminates all active client
 * connections with TCP `RST` and closes the upstream server sockets.
 */
const char *zkc_proxy_start(const char *target);
void zkc_proxy_hold(int on);
int zkc_proxy_reset(void);
long zkc_proxy_up_bytes(void);
int zkc_proxy_accepted(void);
void zkc_proxy_stop(void);

/*
 * Forces session expiration on `zh` (whose context must be a string label) by
 * attaching a secondary handle to the same `clientid_t` on `hosts` and closing
 * the clone. In `mt` builds, `zh`'s I/O thread is held inside its log callback
 * during the clone's close so `zh` cannot reconnect before the session is
 * terminated; in `st` builds, `zh` is temporarily removed from the poll
 * registry. Returns 0 once `zh` delivers `ZOO_EXPIRED_SESSION_STATE`.
 */
int zkc_expire(zhandle_t *zh, const char *hosts);

/* Process thread and open file-descriptor counters. */
int zkc_threads(void);
int zkc_fds(void);

/*
 * Returns 1 if the loaded `libsasl2` shared object declares a `DT_NEEDED`
 * dependency on `libcrypto` (as on Debian/Ubuntu, where `libcrypto` may be
 * mapped into the process via Cyrus SASL). Always returns 0 on macOS.
 */
int zkc_sasl_needs_crypto(void);

/* Invokes `ZKCONF_CTL` to start or stop server `index`. */
int zkc_server(const char *cmd, int index);

/* Records a `DEV` mode assertion; any failure causes `zkc_finish` to exit with status 1. */
void zkc_dev(const char *id, int ok, const char *fmt, ...)
    __attribute__((format(printf, 3, 4)));
/*
 * Records a characterized known issue (`XFAIL` on failure, `XPASS` on success)
 * without failing the test run.
 */
void zkc_known(const char *id, int ok, const char *fmt, ...)
    __attribute__((format(printf, 3, 4)));
/* Records a fatal test-harness error in the trace and marks the run as failed. */
void zkc_fail(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/*
 * Executes `fn` in a forked child process under a `timeout_ms` deadline. Must
 * be invoked prior to creating any `zhandle_t` in the parent process so no
 * background library threads are lost across `fork()`. Records `"exit:N"`,
 * `"signal:N"`, or `"hang"` in the `.dev` file and returns the status string.
 */
const char *zkc_child(const char *label, int (*fn)(void), int timeout_ms);

#ifdef __cplusplus
}
#endif

#endif /* ZKCONF_H_ */
