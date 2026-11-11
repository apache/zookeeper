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

#ifndef ZKRS_H
#define ZKRS_H

/*
 * The private ABI between the C shim and zk-ffi.
 * cbindgen generates this file from zookeeper-client-rust/crates/zk-ffi.
 * Do not edit it. Drift check, from that crate directory:
 *   cbindgen --config cbindgen.toml --crate zk-ffi --verify \
 *     --output ../../c/shim/zkrs.h
 */

#include <stdint.h>
#include <stddef.h>

/**
 * A thread whose role was never set.
 */
#define ZKRS_ROLE_FOREIGN 0

/**
 * The thread that runs `zookeeper_init`.
 */
#define ZKRS_ROLE_INIT 1

/**
 * The mt IO thread.
 */
#define ZKRS_ROLE_IO 2

/**
 * The mt dispatch (completion) thread.
 */
#define ZKRS_ROLE_DISPATCH 3

/**
 * `zkrs_init_args.flavor` of `libzookeeper_mt`.
 */
#define ZKRS_FLAVOR_MT 1

/**
 * `zkrs_init_args.flavor` of `libzookeeper_st`.
 */
#define ZKRS_FLAVOR_ST 2

/**
 * Async delivery through the per-handle FIFO.
 */
#define ZKRS_DELIVER_QUEUED 0

/**
 * Sync and auth delivery on the producing thread.
 */
#define ZKRS_DELIVER_DIRECT 1

/**
 * The result comes from a server reply.
 */
#define ZKRS_ORIGIN_REPLY 0

/**
 * The result comes from a teardown.
 */
#define ZKRS_ORIGIN_TEARDOWN 1

/**
 * Completion kind `COMPLETION_VOID`.
 */
#define ZKRS_K_VOID 0

/**
 * Completion kind `COMPLETION_STAT`.
 */
#define ZKRS_K_STAT 1

/**
 * Completion kind `COMPLETION_DATA`.
 */
#define ZKRS_K_DATA 2

/**
 * Completion kind `COMPLETION_STRINGLIST`.
 */
#define ZKRS_K_STRINGLIST 3

/**
 * Completion kind `COMPLETION_STRINGLIST_STAT`.
 */
#define ZKRS_K_STRINGLIST_STAT 4

/**
 * Completion kind `COMPLETION_ACLLIST`.
 */
#define ZKRS_K_ACLLIST 5

/**
 * Completion kind `COMPLETION_STRING`.
 */
#define ZKRS_K_STRING 6

/**
 * Completion kind `COMPLETION_MULTI`.
 */
#define ZKRS_K_MULTI 7

/**
 * Completion kind `COMPLETION_STRING_STAT`.
 */
#define ZKRS_K_STRING_STAT 8

/**
 * `zoo_acreate*`.
 */
#define ZKRS_OP_CREATE 1

/**
 * `zoo_adelete`.
 */
#define ZKRS_OP_DELETE 2

/**
 * `zoo_awexists`.
 */
#define ZKRS_OP_EXISTS 3

/**
 * `zoo_awget`.
 */
#define ZKRS_OP_GETDATA 4

/**
 * `zoo_aset`.
 */
#define ZKRS_OP_SETDATA 5

/**
 * `zoo_aget_acl`.
 */
#define ZKRS_OP_GETACL 6

/**
 * `zoo_aset_acl`.
 */
#define ZKRS_OP_SETACL 7

/**
 * `zoo_awget_children`.
 */
#define ZKRS_OP_GETCHILDREN 8

/**
 * `zoo_awget_children2`.
 */
#define ZKRS_OP_GETCHILDREN2 9

/**
 * `zoo_async`.
 */
#define ZKRS_OP_SYNC 10

/**
 * `zoo_awgetconfig`.
 */
#define ZKRS_OP_GETCONFIG 11

/**
 * `zoo_areconfig`.
 */
#define ZKRS_OP_RECONFIG 12

/**
 * `zoo_amulti`.
 */
#define ZKRS_OP_MULTI 13

/**
 * `zoo_aremove_watches`, `zoo_aremove_all_watches`.
 */
#define ZKRS_OP_REMOVE_WATCHES 14

/**
 * `zkrs_request.flags`: create2 (reply with a stat).
 */
#define ZKRS_F_CREATE2 1

/**
 * `zkrs_request.flags`: the path gets no chroot prefix (getconfig).
 */
#define ZKRS_F_RAW_PATH 2

/**
 * `zkrs_request.flags`: remove all watches.
 */
#define ZKRS_F_REMOVE_ALL 4

/**
 * `zkrs_request.flags`: local remove-watches.
 */
#define ZKRS_F_LOCAL 8

/**
 * `zkrs_add_auth` flags: the shim could not allocate the request.
 */
#define ZKRS_AUTH_F_ALLOC_FAILED 1

/**
 * `*rc` at the SASL point of init. It is never an errno.
 */
#define ZKRS_INIT_SASL_POINT -1

/**
 * `zkrs_sockaddr_view.family` of an IPv4 address.
 */
#define ZKRS_AF_INET 4

/**
 * `zkrs_sockaddr_view.family` of an IPv6 address.
 */
#define ZKRS_AF_INET6 6

/**
 * Log argument: `int64_t i`.
 */
#define ZKRS_ARG_I64 0

/**
 * Log argument: `uint64_t u`.
 */
#define ZKRS_ARG_U64 1

/**
 * Log argument: `uintptr_t p`.
 */
#define ZKRS_ARG_PTR 2

/**
 * Log argument: `zkrs_str s`.
 */
#define ZKRS_ARG_STR 3

/**
 * Log argument: `zkrs_sockaddr_view ep`.
 */
#define ZKRS_ARG_ENDPOINT 4

/**
 * Log argument: `strerror(err)`.
 */
#define ZKRS_ARG_ERRNO_STR 5

/**
 * Log argument: the resolver text of `gai_rc` and `err`.
 */
#define ZKRS_ARG_GAI_STR 6

/**
 * The result of a driving call.
 */
typedef enum zkrs_step {
    /**
     * The call finished. `*rc` is valid.
     */
    ZKRS_DONE = 0,
    /**
     * The call stopped at a barrier. C drains, then calls `zkrs_resume`.
     */
    ZKRS_MORE = 1,
    /**
     * The final close finished. `*rc` is the close rc. C drains, then
     * frees `zh` and drops its reference.
     */
    ZKRS_RELEASE = 2,
    /**
     * Library-thread loops only: leave the loop.
     */
    ZKRS_EXIT = 3,
} zkrs_step;

/**
 * The status of a submission. C maps it to the legacy rc
 * with `zks_funnel`.
 */
typedef enum zkrs_status {
    /**
     * The token is retained and is delivered exactly once.
     */
    ZKRS_OK = 0,
    /**
     * A local remove-watches call finished. The token is not retained.
     */
    ZKRS_OK_LOCAL = 1,
    /**
     * The TTL or path stage failed.
     */
    ZKRS_EARLY_BADARGS = 2,
    /**
     * `state < 0`, or `state == 0` for `zoo_add_auth`.
     */
    ZKRS_EARLY_STATE = 3,
    /**
     * The serialization stage failed: encode, input defect or close.
     */
    ZKRS_LATE = 4,
    /**
     * A multi sub-op type is not supported.
     */
    ZKRS_UNIMPLEMENTED = 5,
    /**
     * No watcher matches the remove-watches call.
     */
    ZKRS_NOWATCHER = 6,
    /**
     * `zoo_add_auth`: a negative `certLen` with a non-NULL `cert`, or a
     * shim allocation failure.
     */
    ZKRS_SYSTEM = 7,
} zkrs_status;

/**
 * The type of an outbox item.
 */
typedef enum zkrs_item_type {
    /**
     * A log line.
     */
    ZKRS_ITEM_LOG = 1,
    /**
     * A completion: async, sync or auth.
     */
    ZKRS_ITEM_RESULT = 2,
    /**
     * A node or session event.
     */
    ZKRS_ITEM_WATCH = 3,
    /**
     * A legacy `zoo_lock_auth` site.
     */
    ZKRS_ITEM_AUTH_LOCKPOINT = 4,
    /**
     * A SASL call: start, step or dispose of one Cyrus negotiation.
     */
    ZKRS_ITEM_SASL = 5,
    /**
     * A thread join or detach (mt only).
     */
    ZKRS_ITEM_THREAD = 6,
} zkrs_item_type;

/**
 * The operation of a `ZKRS_ITEM_THREAD` item.
 */
typedef enum zkrs_thread_op {
    /**
     * Join the thread of the role.
     */
    ZKRS_THREAD_JOIN = 1,
    /**
     * Detach the calling thread.
     */
    ZKRS_THREAD_DETACH = 2,
} zkrs_thread_op;

/**
 * The kind of a SASL call.
 */
typedef enum zkrs_sasl_kind {
    /**
     * Start a new Cyrus connection. `local` and `peer` are set.
     */
    ZKRS_SASL_START = 1,
    /**
     * Give a server token to Cyrus. `token` is set.
     */
    ZKRS_SASL_STEP = 2,
    /**
     * Dispose the Cyrus connection. No result follows.
     */
    ZKRS_SASL_DISPOSE = 3,
} zkrs_sasl_kind;

/**
 * The status of a SASL result.
 *
 * `ZKRS_SASL_STALE` goes only from the glue to the drain. Rust treats
 * each status other than CONTINUE or DONE as FAIL.
 */
typedef enum zkrs_sasl_status {
    /**
     * Cyrus gave `SASL_CONTINUE`.
     */
    ZKRS_SASL_CONTINUE = 0,
    /**
     * Cyrus gave `SASL_OK`.
     */
    ZKRS_SASL_DONE = 1,
    /**
     * The glue or Cyrus failed.
     */
    ZKRS_SASL_FAIL = -1,
    /**
     * The generation is stale. The glue made no Cyrus call.
     */
    ZKRS_SASL_STALE = -2,
} zkrs_sasl_status;

/**
 * The opaque Rust handle. C holds `Arc::into_raw(Arc<Inner>)` as a
 * `const zkrs_handle *`. C never reads through it.
 */
typedef struct zkrs_handle zkrs_handle;

/**
 * The outbox of one driving call, on the C stack.
 *
 * `impl` is NULL until Rust stores the first item. Only Rust reads it.
 */
typedef struct zkrs_outbox {
    /**
     * The Rust outbox, or NULL.
     */
    void *impl;
} zkrs_outbox;

/**
 * A socket address in a fixed layout.
 */
typedef struct zkrs_sockaddr_view {
    /**
     * 0 (none), `ZKRS_AF_INET` or `ZKRS_AF_INET6`.
     */
    uint32_t family;
    /**
     * The port in host byte order.
     */
    uint16_t port;
    /**
     * The address in network byte order. IPv4 uses the first 4 bytes.
     */
    uint8_t addr[16];
    /**
     * IPv6 only.
     */
    uint32_t flowinfo;
    /**
     * IPv6 only.
     */
    uint32_t scope_id;
} zkrs_sockaddr_view;

/**
 * The arguments of `zkrs_handle_new`. The strings are
 * borrowed for the call.
 */
typedef struct zkrs_init_args {
    /**
     * `ZKRS_FLAVOR_*`.
     */
    uint32_t flavor;
    /**
     * The host string.
     */
    const char *hosts;
    /**
     * The session timeout.
     */
    int32_t recv_timeout;
    /**
     * 1 when the caller gave a client id.
     */
    uint32_t have_client_id;
    /**
     * The session id.
     */
    int64_t client_id;
    /**
     * The session password.
     */
    uint8_t client_passwd[16];
    /**
     * The raw init flags.
     */
    int32_t flags;
    /**
     * 1 only from `zookeeper_init_ssl`.
     */
    uint32_t tls;
    /**
     * TLS CA path.
     */
    const char *tls_ca;
    /**
     * TLS certificate path.
     */
    const char *tls_cert;
    /**
     * TLS key path.
     */
    const char *tls_key;
    /**
     * TLS key passphrase.
     */
    const char *tls_passwd;
    /**
     * 1 when Rust may read `SSL_CERT_FILE` and `SSL_CERT_DIR`.
     */
    uint32_t tls_env_allowed;
    /**
     * 1 when `zookeeper_init_sasl` got parameters.
     */
    uint32_t sasl;
    /**
     * The xid seed, `(int32_t)time(0)`.
     */
    int32_t xid_seed;
    /**
     * The `SOCK_CLOEXEC_ENABLED` build option.
     */
    uint32_t sock_cloexec;
    /**
     * 1 when `char` is signed in the shim compiler.
     */
    uint32_t c_char_signed;
} zkrs_init_args;

/**
 * One watcher: the application `(watcher_fn, void *)` pair.
 */
typedef struct zkrs_watcher {
    /**
     * The `watcher_fn` value.
     */
    uintptr_t fn;
    /**
     * The context value.
     */
    uintptr_t ctx;
} zkrs_watcher;

/**
 * The services that C gives to Rust. Pure entries never block.
 * Blocking entries run with no Rust lock held. No entry reaches
 * application code.
 */
typedef struct zkrs_host_vtable {
    /**
     * Pure. Frees a `zks_req` without a callback.
     */
    void (*discard_token)(uintptr_t token);
    /**
     * Pure. Reads the default watcher pair.
     */
    void (*snapshot_default_watcher)(uintptr_t host, struct zkrs_watcher *out);
    /**
     * Pure. Stores the session id and password in `zh->client_id`.
     */
    void (*store_client_id)(uintptr_t host, int64_t id, const uint8_t *passwd);
    /**
     * Pure. The `logLevel` rule of the legacy macros.
     */
    int (*log_enabled)(uint32_t level);
    /**
     * Pure. `zoo_deterministic_conn_order`.
     */
    int (*conn_permute_disabled)(void);
    /**
     * Pure. libc `random()`.
     */
    long (*rng_random)(void);
    /**
     * Pure. libc `drand48()`.
     */
    double (*rng_drand48)(void);
    /**
     * Blocking. The legacy reseed.
     */
    void (*rng_reseed)(void);
    /**
     * Blocking. `getaddrinfo` for one host entry. Returns the errno.
     */
    int (*resolve)(const char *name,
                   const char *port,
                   int32_t *gai_rc,
                   void (*push)(void *sink, const struct zkrs_sockaddr_view *addr),
                   void (*skip)(void *sink, int32_t family),
                   void *sink);
} zkrs_host_vtable;

/**
 * The outputs of `zkrs_interest`.
 */
typedef struct zkrs_interest_out {
    /**
     * The socket, or -1.
     */
    intptr_t fd;
    /**
     * `ZOOKEEPER_READ | ZOOKEEPER_WRITE`.
     */
    int32_t interest;
    /**
     * The timeout in milliseconds.
     */
    int64_t timeout_ms;
} zkrs_interest_out;

/**
 * A NUL-terminated string view. `ptr` is NULL for a jute null string.
 */
typedef struct zkrs_str {
    /**
     * The bytes, with `ptr[len] == 0`, or NULL.
     */
    const char *ptr;
    /**
     * The length without the NUL.
     */
    int32_t len;
} zkrs_str;

/**
 * A log argument. `kind` tells which field holds the value.
 */
typedef struct zkrs_log_arg {
    /**
     * `ZKRS_ARG_*`.
     */
    uint32_t kind;
    /**
     * `ZKRS_ARG_I64`.
     */
    int64_t i;
    /**
     * `ZKRS_ARG_U64`.
     */
    uint64_t u;
    /**
     * `ZKRS_ARG_PTR`.
     */
    uintptr_t p;
    /**
     * `ZKRS_ARG_STR`.
     */
    struct zkrs_str s;
    /**
     * `ZKRS_ARG_ENDPOINT`.
     */
    struct zkrs_sockaddr_view ep;
    /**
     * `ZKRS_ARG_GAI_STR`.
     */
    int32_t gai_rc;
    /**
     * `ZKRS_ARG_GAI_STR` and `ZKRS_ARG_ERRNO_STR`.
     */
    int32_t err;
} zkrs_log_arg;

/**
 * A log line of the core. C renders the pinned format of
 * `site` with `args`.
 */
typedef struct zkrs_log_view {
    /**
     * `ZOO_LOG_LEVEL_*`.
     */
    uint32_t level;
    /**
     * The site id: the index in `zk_proto::LogSite::ALL`.
     */
    uint32_t site;
    /**
     * The wall-clock seconds that zk-ffi stamped.
     */
    int64_t tv_sec;
    /**
     * The microseconds of the stamp.
     */
    int32_t tv_usec;
    /**
     * The arguments, in format order.
     */
    const struct zkrs_log_arg *args;
    /**
     * The number of arguments.
     */
    uint32_t nargs;
} zkrs_log_view;

/**
 * A byte buffer view. `len` is -1 for null znode data.
 */
typedef struct zkrs_buf {
    /**
     * The bytes, or NULL.
     */
    const char *ptr;
    /**
     * The length, or -1.
     */
    int32_t len;
} zkrs_buf;

/**
 * The fields of `struct Stat`, in a fixed layout. The writers
 * copy it field by field into the public record.
 */
typedef struct zkrs_stat {
    /**
     * `czxid`.
     */
    int64_t czxid;
    /**
     * `mzxid`.
     */
    int64_t mzxid;
    /**
     * `ctime`.
     */
    int64_t ctime;
    /**
     * `mtime`.
     */
    int64_t mtime;
    /**
     * `version`.
     */
    int32_t version;
    /**
     * `cversion`.
     */
    int32_t cversion;
    /**
     * `aversion`.
     */
    int32_t aversion;
    /**
     * `ephemeralOwner`.
     */
    int64_t ephemeralOwner;
    /**
     * `dataLength`.
     */
    int32_t dataLength;
    /**
     * `numChildren`.
     */
    int32_t numChildren;
    /**
     * `pzxid`.
     */
    int64_t pzxid;
} zkrs_stat;

/**
 * One ACL entry of a result view.
 */
typedef struct zkrs_acl_view {
    /**
     * The permission bits.
     */
    int32_t perms;
    /**
     * The scheme.
     */
    struct zkrs_str scheme;
    /**
     * The id.
     */
    struct zkrs_str id;
} zkrs_acl_view;

/**
 * One sub-result of a multi view.
 */
typedef struct zkrs_multi_op {
    /**
     * The sub-op error.
     */
    int32_t err;
    /**
     * 1 for an error result.
     */
    int32_t failed;
    /**
     * The stripped path of a create, or NULL.
     */
    struct zkrs_str path;
    /**
     * The stat of a set, or NULL.
     */
    const struct zkrs_stat *stat;
} zkrs_multi_op;

/**
 * The view of a completion.
 */
typedef struct zkrs_result_view {
    /**
     * `ZKRS_K_*` of the request.
     */
    uint32_t kind;
    /**
     * `ZKRS_ORIGIN_*`.
     */
    uint32_t origin;
    /**
     * The legacy rc.
     */
    int32_t rc;
    /**
     * 1 when no body was decoded.
     */
    int32_t failed;
    /**
     * DATA: the node data.
     */
    struct zkrs_buf data;
    /**
     * The stat, or NULL.
     */
    const struct zkrs_stat *stat;
    /**
     * STRING: the stripped path.
     */
    struct zkrs_str path;
    /**
     * STRINGLIST: the children.
     */
    const struct zkrs_str *children;
    /**
     * The number of children, or -1 for a jute null vector.
     */
    int32_t nchildren;
    /**
     * ACLLIST: the entries.
     */
    const struct zkrs_acl_view *acl;
    /**
     * The number of ACL entries, or -1.
     */
    int32_t nacl;
    /**
     * MULTI: the sub-results.
     */
    const struct zkrs_multi_op *ops;
    /**
     * The number of sub-results.
     */
    int32_t nops;
    /**
     * The delivery lines, emitted before the callback.
     */
    const struct zkrs_log_view *delivery_logs;
    /**
     * The number of delivery lines.
     */
    uint32_t n_delivery_logs;
} zkrs_result_view;

/**
 * The view of a node or session event.
 */
typedef struct zkrs_watch_event_view {
    /**
     * The event type. `ZOO_SESSION_EVENT` is -1.
     */
    int32_t type;
    /**
     * The state.
     */
    int32_t state;
    /**
     * The stripped path. `""` for a session event.
     */
    struct zkrs_str path;
    /**
     * The watchers, in delivery order.
     */
    const struct zkrs_watcher *list;
    /**
     * The number of watchers.
     */
    uint32_t n;
    /**
     * The delivery lines, emitted before the callbacks.
     */
    const struct zkrs_log_view *delivery_logs;
    /**
     * The number of delivery lines.
     */
    uint32_t n_delivery_logs;
} zkrs_watch_event_view;

/**
 * A bounded byte view.
 *
 * `is_null == 1` is a jute null token. Then `p` is NULL and `len` is 0.
 * `len > 0` needs a non-NULL `p`. `len` is at most `0x7fffffff`, so
 * each length fits in a C `int`.
 */
typedef struct zkrs_bytes {
    /**
     * The first byte.
     */
    const uint8_t *p;
    /**
     * The number of bytes.
     */
    uint32_t len;
    /**
     * 1 for a jute null token.
     */
    uint8_t is_null;
} zkrs_bytes;

/**
 * The SASL call view of a `ZKRS_ITEM_SASL` item.
 *
 * The views point into outbox storage. They stay valid until the
 * matching `zkrs_sasl_result` returns or the outbox moves on.
 */
typedef struct zkrs_sasl_call {
    /**
     * `zkrs_sasl_kind`.
     */
    uint32_t kind;
    /**
     * The session generation. It is never 0.
     */
    uint32_t gen;
    /**
     * START only: the platform `sockaddr_in` or `sockaddr_in6` image of
     * the local address. Otherwise the null view.
     */
    struct zkrs_bytes local;
    /**
     * START only: the image of the peer address. Otherwise the null view.
     */
    struct zkrs_bytes peer;
    /**
     * STEP only: the server token. A -1 token is the null view.
     */
    struct zkrs_bytes token;
} zkrs_sasl_call;

/**
 * One outbox item. Only the members of `type` are valid.
 */
typedef struct zkrs_item {
    /**
     * `zkrs_item_type`.
     */
    uint32_t type;
    /**
     * LOG.
     */
    const struct zkrs_log_view *log;
    /**
     * RESULT: the token. Ownership passes to C.
     */
    uintptr_t token;
    /**
     * RESULT.
     */
    const struct zkrs_result_view *result;
    /**
     * WATCH.
     */
    const struct zkrs_watch_event_view *watch;
    /**
     * SASL.
     */
    const struct zkrs_sasl_call *sasl;
    /**
     * THREAD: `zkrs_thread_op`.
     */
    uint32_t thread_op;
    /**
     * THREAD: `ZKRS_ROLE_IO` or `ZKRS_ROLE_DISPATCH`.
     */
    uint32_t thread_role;
} zkrs_item;

/**
 * One sub-result copy in a sync record.
 */
typedef struct zkrs_multi_copy {
    /**
     * The sub-op error.
     */
    int32_t err;
    /**
     * 1 for an error result.
     */
    int32_t failed;
    /**
     * A `malloc` copy of the path, or NULL.
     */
    char *path;
    /**
     * The path length.
     */
    int32_t path_len;
    /**
     * 1 when `stat` is valid.
     */
    uint32_t has_stat;
    /**
     * The stat of a set.
     */
    struct zkrs_stat stat;
} zkrs_multi_copy;

/**
 * Writer destinations. NULL means "not wanted".
 */
typedef struct zkrs_result_dst {
    /**
     * `struct Stat *`.
     */
    void *stat;
    /**
     * DATA: the `malloc` copy.
     */
    char **value;
    /**
     * DATA: the length, or -1.
     */
    int32_t *value_len;
    /**
     * `struct String_vector *`.
     */
    void *strings;
    /**
     * `struct ACL_vector *`.
     */
    void *acl;
    /**
     * STRING: the `malloc` copy of the stripped path.
     */
    char **str;
    /**
     * STRING: the length.
     */
    int32_t *str_len;
    /**
     * MULTI into a sync record: the per-op copies.
     */
    struct zkrs_multi_copy **ops;
    /**
     * MULTI into a sync record: the number of copies.
     */
    int32_t *nops;
} zkrs_result_dst;

/**
 * The reply image of a sync record.
 */
typedef struct zkrs_sync_src {
    /**
     * `ZKRS_K_*` of the wrapper.
     */
    uint32_t kind;
    /**
     * The rc.
     */
    int32_t rc;
    /**
     * 1 when no body was decoded.
     */
    int32_t failed;
    /**
     * `ZKRS_ORIGIN_*`.
     */
    int32_t origin;
    /**
     * DATA: the record copy.
     */
    const char *data;
    /**
     * DATA: the length, or -1.
     */
    int32_t data_len;
    /**
     * STRING: the record copy.
     */
    const char *path;
    /**
     * STRING: the length.
     */
    int32_t path_len;
    /**
     * `const struct Stat *` of the record.
     */
    const void *stat;
    /**
     * MULTI: the per-op copies.
     */
    const struct zkrs_multi_copy *ops;
    /**
     * MULTI: the number of copies.
     */
    int32_t nops;
    /**
     * MULTI: the per-op writer kinds.
     */
    const uint8_t *op_kind;
} zkrs_sync_src;

/**
 * The caller outputs of a sync wrapper. NULL where the wrapper
 * has none.
 */
typedef struct zkrs_sync_dst {
    /**
     * DATA: the caller buffer.
     */
    char *buffer;
    /**
     * DATA: the capacity in, the length out.
     */
    int32_t *buffer_len;
    /**
     * STRING: the caller path buffer.
     */
    char *path_buffer;
    /**
     * STRING: the capacity.
     */
    int32_t path_buffer_len;
    /**
     * `struct Stat *`.
     */
    void *stat;
    /**
     * MULTI: `zoo_op_result_t[]`.
     */
    void *results;
    /**
     * MULTI: the count.
     */
    int32_t count;
} zkrs_sync_dst;

/**
 * The Cyrus output of one START or STEP.
 *
 * C keeps the views valid until `zkrs_sasl_result` returns. Rust copies
 * them before it returns.
 */
typedef struct zkrs_sasl_out {
    /**
     * `zkrs_sasl_status`.
     */
    int32_t status;
    /**
     * START: the mechanism that Cyrus chose. Otherwise the null view.
     */
    struct zkrs_bytes mech;
    /**
     * The Cyrus `clientout`. The null view when Cyrus gave none.
     */
    struct zkrs_bytes token;
} zkrs_sasl_out;

/**
 * One request from a `zoo_a*` entry point.
 */
typedef struct zkrs_request {
    /**
     * `ZKRS_OP_*`.
     */
    uint32_t op;
    /**
     * `ZKRS_F_*`.
     */
    uint32_t flags;
    /**
     * The application path, or NULL.
     */
    const char *path;
    /**
     * The value buffer.
     */
    const char *data;
    /**
     * The value length.
     */
    int32_t datalen;
    /**
     * Create and set ACL: `const struct ACL_vector *`, or NULL.
     */
    const void *acl;
    /**
     * The create mode.
     */
    int32_t mode;
    /**
     * The create TTL.
     */
    int64_t ttl;
    /**
     * The version.
     */
    int32_t version;
    /**
     * The reconfig version.
     */
    int64_t reconfig_version;
    /**
     * Reconfig: joining servers, or NULL.
     */
    const char *joining;
    /**
     * Reconfig: leaving servers, or NULL.
     */
    const char *leaving;
    /**
     * Reconfig: new members, or NULL.
     */
    const char *members;
    /**
     * The watcher function, or 0.
     */
    uintptr_t watch_fn;
    /**
     * The watcher context.
     */
    uintptr_t watch_ctx;
    /**
     * Remove-watches: the watcher type.
     */
    int32_t wtype;
    /**
     * Multi: `const zoo_op_t *`, the array of the caller.
     */
    const void *ops;
    /**
     * Multi: the length of the prefix.
     */
    int32_t ops_len;
    /**
     * Multi: the original count.
     */
    int32_t count;
} zkrs_request;

#ifdef __cplusplus
extern "C" {
#endif // __cplusplus

/**
 * `zoo_state`: the legacy state int, including 0, 7, 999,
 * -112 and -113.
 */
int32_t zkrs_state(const struct zkrs_handle *h);

/**
 * `zoo_recv_timeout`: the negotiated session timeout, or the
 * requested timeout before the first session.
 */
int32_t zkrs_recv_timeout(const struct zkrs_handle *h);

/**
 * `zoo_set_servers`. `*rc` is the legacy rc.
 *
 * A NULL or empty `hosts` gives ZBADARGUMENTS with the
 * `SetServersEmpty` line. An empty string does not change the server
 * list or the connection (spec "Rejection of Empty Server Lists in
 * `zoo_set_servers`"). Legacy C accepts it. The resolution of a new list
 * can reach the Blocking `resolve` entry, so the call returns
 * `ZKRS_MORE` at the log-flush barrier.
 */
enum zkrs_step zkrs_set_servers(const struct zkrs_handle *h,
                                const char *hosts,
                                struct zkrs_outbox *ob,
                                int *rc);

/**
 * `zoo_set_servers_resolution_delay`.
 *
 * The function calls `Connection::set_resolve_delay` through the
 * `with_conn` method of the driver. A delay below -1 gives
 * ZBADARGUMENTS and the `ResolveDelayInvalid` line. The mt IO thread or
 * the next st driving call routes the line.
 */
int32_t zkrs_set_resolution_delay(const struct zkrs_handle *h, int32_t delay_ms);

/**
 * `zoo_cycle_next_server`.
 *
 * The function calls `Connection::cycle_next_server` through the
 * `with_conn` method of the driver. The reconfig lines go out through
 * the mt IO thread after the wake, or in the next st driving call. They
 * do not go through `ob` (open issue: the legacy lines come from the
 * calling thread).
 */
void zkrs_cycle_next_server(const struct zkrs_handle *h, struct zkrs_outbox *ob);

/**
 * `zoo_get_current_server`: writes the `cur` address,
 * or family 0 when none exists.
 */
void zkrs_current_server(const struct zkrs_handle *h, struct zkrs_sockaddr_view *out);

/**
 * `zookeeper_get_connected_host`. It returns 0 and writes
 * `*out` only when the state is CONNECTED (3) and `getpeername`
 * succeeds; otherwise it returns -1.
 */
int32_t zkrs_peer_addr(const struct zkrs_handle *h, struct zkrs_sockaddr_view *out);

/**
 * Makes a handle and runs the init up to the first barrier.
 *
 * The function copies `*a` and `*vt` with `raw::read_struct` and every
 * string with the `raw` readers. It builds the `zk_driver::InitArgs`
 * with `Compat::LEGACY_C` and the mode of `a->flavor`, and calls
 * `Init::new`. A parse error of the chroot leaves `*out` NULL and
 * writes `*rc`. Otherwise `*out` is set before the first barrier. Each
 * barrier returns `ZKRS_MORE`, and `zkrs_resume` continues the init. On
 * `InitStep::Ready` the function installs the driver of `a->flavor`:
 * `Shared` for `ZKRS_FLAVOR_MT`, `Polled` for `ZKRS_FLAVOR_ST`. `*rc`
 * is 0 or the init errno. A failure after `*out` is set needs
 * `zkrs_handle_abandon`. A build without TLS rejects `a->tls == 1`,
 * and a build without SASL rejects `a->sasl == 1`, with `EINVAL`. An
 * unknown flavor aborts.
 */
enum zkrs_step zkrs_handle_new(const struct zkrs_init_args *a,
                               const struct zkrs_host_vtable *vt,
                               uintptr_t host,
                               struct zkrs_handle **out,
                               struct zkrs_outbox *ob,
                               int *rc);

/**
 * Adds one strong reference. Each library thread holds one.
 */
void zkrs_handle_retain(const struct zkrs_handle *h);

/**
 * Gives back one strong reference.
 */
void zkrs_handle_release(const struct zkrs_handle *h);

/**
 * Ends a failed init.
 *
 * The function abandons the `Init` (it closes the waker and drops
 * every token), sets the host to 0 and gives back the C reference.
 */
void zkrs_handle_abandon(const struct zkrs_handle *h);

/**
 * `api_prolog` of a library thread or the init thread.
 * mt only: a call on an st handle aborts.
 */
void zkrs_thread_enter(const struct zkrs_handle *h, uint32_t role);

/**
 * `api_epilog`. It can run the final close on this
 * thread, so it returns a step. mt only.
 */
enum zkrs_step zkrs_thread_exit(const struct zkrs_handle *h,
                                uint32_t role,
                                struct zkrs_outbox *ob,
                                int *rc);

/**
 * The cleanup handler of the dispatch thread. A callback ended that
 * thread, for example with `pthread_exit`.
 *
 * The function frees the box of `ob`, and its untaken items get no
 * callback. It then marks the thread as ended and wakes a close that
 * waits for the queued callbacks (`Shared::dispatch_ended`). NULL
 * `impl` is an empty outbox. mt only.
 */
void zkrs_dispatch_ended(const struct zkrs_handle *h, struct zkrs_outbox *ob);

/**
 * One IO-thread step: interest, poll and process. It returns
 * `ZKRS_MORE` at each barrier and `ZKRS_EXIT` when the IO loop ends. mt
 * only.
 */
enum zkrs_step zkrs_io_step(const struct zkrs_handle *h, struct zkrs_outbox *ob, int *rc);

/**
 * The dispatch-thread wait. It blocks until the FIFO has
 * a batch (`ZKRS_DONE`) or the dispatch loop ends (`ZKRS_EXIT`). mt
 * only.
 */
enum zkrs_step zkrs_wait_dispatch(const struct zkrs_handle *h, struct zkrs_outbox *ob, int *rc);

/**
 * `zookeeper_interest`.
 *
 * mt: the function writes `{fd = -1, interest = 0, timeout_ms =
 * recv_timeout / 3}` and `*written = 1` from `Shared::interest_exported`,
 * and returns `ZKRS_DONE` with its rc.
 *
 * st: the function drives the connection with `Polled::interest`. Each
 * barrier returns `ZKRS_MORE`, and `zkrs_interest_resume`
 * continues the step. The call that finishes writes `*out` and
 * `*written` from the `InterestOut`. A deferred close returns
 * `ZKRS_RELEASE` with the close rc.
 */
enum zkrs_step zkrs_interest(const struct zkrs_handle *h,
                             struct zkrs_interest_out *out,
                             int *written,
                             struct zkrs_outbox *ob,
                             int *rc);

/**
 * `zookeeper_process`. mt: `ZKRS_DONE` with
 * `Shared::process_exported` (ZINVALIDSTATE).
 *
 * st: the function reads and writes the socket with `Polled::process`.
 * `events` has the `ZOOKEEPER_READ` and `ZOOKEEPER_WRITE`
 * bits. Each barrier returns `ZKRS_MORE`, and C runs the callbacks of
 * the outbox inline before `zkrs_resume`. A close from a callback ends
 * with `ZKRS_RELEASE` and the close rc.
 */
enum zkrs_step zkrs_process(const struct zkrs_handle *h,
                            int32_t events,
                            struct zkrs_outbox *ob,
                            int *rc);

/**
 * `zookeeper_close`.
 *
 * The caller role decides the close path: an application thread runs
 * the full close, the IO thread or the dispatch thread runs the
 * deferred close. Each THREAD item is a barrier. The final close sets
 * the host to 0 and returns `ZKRS_RELEASE`.
 *
 * st ignores the role. A close from a callback defers to the epilog of
 * the driving call. Otherwise this call runs the final close.
 */
enum zkrs_step zkrs_close(const struct zkrs_handle *h,
                          uint32_t caller_role,
                          struct zkrs_outbox *ob,
                          int *rc);

/**
 * Continues the step that stopped at a barrier.
 *
 * During the init it steps the `Init`. After the init it calls
 * `Shared::resume` or `Polled::resume`, which read the resume tag of
 * the outbox. A tag of `ResumeAt::Interest` belongs to
 * `zkrs_interest_resume`.
 */
enum zkrs_step zkrs_resume(const struct zkrs_handle *h, struct zkrs_outbox *ob, int *rc);

/**
 * `zkrs_resume` for a suspended `zkrs_interest`.
 *
 * st only. mt never suspends `zkrs_interest`, so a call on an mt handle
 * aborts. st continues with `Polled::resume` and writes `*out` and
 * `*written` as `zkrs_interest` does.
 */
enum zkrs_step zkrs_interest_resume(const struct zkrs_handle *h,
                                    struct zkrs_interest_out *out,
                                    int *written,
                                    struct zkrs_outbox *ob,
                                    int *rc);

/**
 * The errno of the last `zookeeper_interest` or `zookeeper_process`
 * call.
 *
 * The value is the cause of a failed step: for example ECONNREFUSED,
 * ETIMEDOUT or ESTALE. It is 0 after a step with no cause, and on an mt
 * handle. C reads it after the last step of the call and before a
 * release, and sets `errno` when the value is not 0.
 */
int zkrs_last_errno(const struct zkrs_handle *h);

/**
 * Gives the next item of the outbox.
 *
 * It returns 1 and writes `*it`, or returns 0 when the box is empty or
 * `impl` is NULL. The function pops the driver item. `Outbox::pop`
 * publishes the FIFO entries and gives the close mark to the FIFO.
 * The function stages the views of the item in [`Staged`]:
 *
 * - LOG: the log view with the stamp;
 * - RESULT: the token (`CReq::into_raw`) and its result view;
 * - WATCH: the event view with the watcher list;
 * - AUTH_LOCKPOINT and THREAD: the item fields only;
 * - SASL: the call view with the address images or the token.
 *
 * C must answer a START or STEP item, so the function records it in the
 * SASL table. C does not see some items (`WatchTerminated`). The
 * function discards them with `zk_driver::outbox::discard_item`.
 */
int zkrs_outbox_next(struct zkrs_outbox *ob, struct zkrs_item *it);

/**
 * Ends a drain.
 *
 * The function frees the box and sets `impl` to NULL when no resume
 * tag is pending. It calls `Outbox::finish` with the host leaves first.
 * With a pending tag it keeps the empty box for `zkrs_resume`.
 */
void zkrs_outbox_finish(struct zkrs_outbox *ob);

/**
 * The unwind path of a drain. A C++ exception from a callback can stop
 * the drain.
 *
 * The function gives the untaken items and the resume tag back to the
 * handle with `Driver::requeue`, frees the box and sets `impl` to NULL.
 * The next driving call delivers them first.
 */
void zkrs_outbox_requeue(struct zkrs_outbox *ob);

/**
 * Adds `zh` to the live handles. If the set cannot grow, the function
 * writes `handle registry: out of memory` and aborts.
 */
void zkrs_registry_add(const void *zh);

/**
 * Removes `zh` from the live handles. A handle that is not live is not
 * an error.
 */
void zkrs_registry_remove(const void *zh);

/**
 * Returns when `zh` is live. Otherwise it writes a message that names
 * the entry point `func` and the handle address, and aborts.
 */
void zkrs_registry_check(const void *zh, const char *func);

/**
 * Builds the legacy objects of a result view into the destinations.
 */
void zkrs_write_result(const struct zkrs_result_view *v, const struct zkrs_result_dst *d);

/**
 * The watcher path: `malloc(len + 1)`, never NULL.
 */
char *zkrs_write_path(const struct zkrs_str *s);

/**
 * The multi writers over `zoo_op_result_t results[count]`.
 */
void zkrs_write_multi(const struct zkrs_result_view *v,
                      void *results,
                      const uint8_t *op_kind,
                      int32_t count);

/**
 * The sync copy-out on the waiting thread.
 */
void zkrs_sync_copy_out(const struct zkrs_sync_src *s, const struct zkrs_sync_dst *d);

/**
 * Gives the Cyrus output of one START or STEP item to the core.
 *
 * C calls it at most once per START or STEP item, inside the drain of
 * that item. The export copies `*v` and its views with `abi::raw`
 * before it returns. A bad view is a FAIL result. A status other than
 * `ZKRS_SASL_CONTINUE` or `ZKRS_SASL_DONE` is a FAIL result with
 * `ZSYSTEMERROR` and empty texts. A `gen` that names no item that C
 * took changes nothing, and the core ignores a stale `gen`.
 */
void zkrs_sasl_result(const struct zkrs_handle *h, uint32_t gen, const struct zkrs_sasl_out *v);

/**
 * `zoo_sasl_mark_failed`: marks the negotiation failed and
 * sets `AUTH_FAILED`. It emits no event and starts no teardown. After
 * the final close it does nothing.
 */
void zkrs_sasl_mark_failed(const struct zkrs_handle *h);

/**
 * Submits one request.
 *
 * `ZKRS_OK` retains the token, and the driver delivers it exactly once.
 * `ZKRS_OK_LOCAL` is a local remove-watches call that finished; the
 * token is not retained. Every other status gives the token
 * back to C. `delivery` is `ZKRS_DELIVER_QUEUED` or
 * `ZKRS_DELIVER_DIRECT`.
 */
enum zkrs_status zkrs_submit(const struct zkrs_handle *h,
                             const struct zkrs_request *rq,
                             uintptr_t token,
                             uint32_t delivery,
                             struct zkrs_outbox *ob);

/**
 * `zoo_add_auth`.
 *
 * zk-ffi checks the `cert_len` stage (a negative value with a non-NULL
 * `cert` gives `ZKRS_SYSTEM`) and
 * `ZKRS_AUTH_F_ALLOC_FAILED` (`ZKRS_SYSTEM`). zk-proto `add_auth`
 * checks the state, the close and the send stages. `cert == NULL` with
 * `cert_len > 0` is an empty credential. The credential is
 * copied into `SecretBytes`. Token 0 means no completion.
 */
enum zkrs_status zkrs_add_auth(const struct zkrs_handle *h,
                               const char *scheme,
                               const char *cert,
                               int32_t cert_len,
                               uintptr_t token,
                               uint32_t flags,
                               struct zkrs_outbox *ob);

/**
 * Stages 1 to 3 of `zoo_add_auth` only. No lock is held
 * and no outbox is used.
 *
 * A driver state `< 0` or `== 0` gives `ZKRS_EARLY_STATE`. A negative
 * `cert_len` with a non-NULL `cert` gives `ZKRS_SYSTEM`.
 */
enum zkrs_status zkrs_add_auth_check(const struct zkrs_handle *h,
                                     const char *cert,
                                     int32_t cert_len);

/**
 * The TTL stage of a create, with no handle.
 *
 * C calls it when `zh == NULL`, so the TTL stage still wins over the
 * NULL-handle check.
 */
enum zkrs_status zkrs_validate_ttl(int32_t mode, int64_t ttl);

#if defined(ZKS_WITH_TLS)
/**
 * Marks a zk-ffi build with TLS. It always returns 1.
 *
 * Only a build with the `tls` feature defines this symbol. A shim built
 * with `ZKS_WITH_TLS` calls it. Thus a TLS shim and a plain core give an
 * undefined reference, and the prelink check fails. The build checks of
 * a prebuilt staticlib look for the symbol with `nm`.
 */
uint32_t zkrs_feature_tls(void);
#endif

#ifdef __cplusplus
}  // extern "C"
#endif  // __cplusplus

#endif  /* ZKRS_H */

/* Initializers. Every field that the macro does not name is zero. */
#define ZKRS_OUTBOX_INIT {NULL}
#define ZKRS_REQUEST_INIT(o) {.op = (o)}
#define ZKRS_RESULT_DST_INIT {0}
#define ZKRS_SYNC_DST_INIT {0}
