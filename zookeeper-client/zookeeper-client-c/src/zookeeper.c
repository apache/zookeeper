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

#if !defined(DLL_EXPORT) && !defined(USE_STATIC_LIB)
#  define USE_STATIC_LIB
#endif

#if defined(__CYGWIN__)
#define USE_IPV6
#endif

#include "config.h"
#include <zookeeper.h>
#include <zookeeper.jute.h>
#include <proto.h>
#include "zk_adaptor.h"
#include "zookeeper_log.h"
#include "zk_hashtable.h"

#ifdef HAVE_CYRUS_SASL_H
#include "zk_sasl.h"
#endif /* HAVE_CYRUS_SASL_H */

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <errno.h>
#include <fcntl.h>
#include <assert.h>
#include <stdarg.h>
#include <limits.h>

#ifdef HAVE_SYS_TIME_H
#include <sys/time.h>
#endif

#ifdef HAVE_SYS_SOCKET_H
#include <sys/socket.h>
#endif

#ifdef HAVE_POLL
#include <poll.h>
#endif

#ifdef HAVE_NETINET_IN_H
#include <netinet/in.h>
#include <netinet/tcp.h>
#endif

#ifdef HAVE_ARPA_INET_H
#include <arpa/inet.h>
#endif

#ifdef HAVE_NETDB_H
#include <netdb.h>
#endif

#ifdef HAVE_UNISTD_H
#include <unistd.h> // needed for _POSIX_MONOTONIC_CLOCK
#endif

#ifdef HAVE_SYS_UTSNAME_H
#include <sys/utsname.h>
#endif

#ifdef HAVE_GETPWUID_R
#include <pwd.h>
#endif

#ifdef HAVE_OPENSSL_H
#include <openssl/ssl.h>
#include <openssl/err.h>
#endif

#ifdef __MACH__ // OS X
#include <mach/clock.h>
#include <mach/mach.h>
#include <netinet/tcp.h>
#endif

#ifdef WIN32
#include <process.h> /* for getpid */
#include <direct.h> /* for getcwd */
#define EAI_ADDRFAMILY WSAEINVAL /* is this still needed? */
#define EHOSTDOWN EPIPE
#define ESTALE ENODEV
#endif

#define IF_DEBUG(x) if(logLevel==ZOO_LOG_LEVEL_DEBUG) {x;}

const int ZOOKEEPER_WRITE = 1 << 0;
const int ZOOKEEPER_READ = 1 << 1;

const int ZOO_PERSISTENT = 0;
const int ZOO_EPHEMERAL = 1;
const int ZOO_PERSISTENT_SEQUENTIAL = 2;
const int ZOO_EPHEMERAL_SEQUENTIAL = 3;
const int ZOO_CONTAINER = 4;
const int ZOO_PERSISTENT_WITH_TTL = 5;
const int ZOO_PERSISTENT_SEQUENTIAL_WITH_TTL = 6;

#define ZOOKEEPER_IS_SEQUENCE(mode) \
    ((mode) == ZOO_PERSISTENT_SEQUENTIAL || \
     (mode) == ZOO_EPHEMERAL_SEQUENTIAL || \
     (mode) == ZOO_PERSISTENT_SEQUENTIAL_WITH_TTL)
#define ZOOKEEPER_IS_TTL(mode) \
    ((mode) == ZOO_PERSISTENT_WITH_TTL || \
     (mode) == ZOO_PERSISTENT_SEQUENTIAL_WITH_TTL)

// keep ZOO_SEQUENCE as a bitmask for compatibility reasons
const int ZOO_SEQUENCE = 1 << 1;

#define ZOO_MAX_TTL 0xFFFFFFFFFFLL

const int ZOO_EXPIRED_SESSION_STATE = EXPIRED_SESSION_STATE_DEF;
const int ZOO_AUTH_FAILED_STATE = AUTH_FAILED_STATE_DEF;
const int ZOO_CONNECTING_STATE = CONNECTING_STATE_DEF;
const int ZOO_ASSOCIATING_STATE = ASSOCIATING_STATE_DEF;
const int ZOO_CONNECTED_STATE = CONNECTED_STATE_DEF;
const int ZOO_READONLY_STATE = READONLY_STATE_DEF;
const int ZOO_SSL_CONNECTING_STATE = SSL_CONNECTING_STATE_DEF;
const int ZOO_NOTCONNECTED_STATE = NOTCONNECTED_STATE_DEF;

static __attribute__ ((unused)) const char* state2String(int state){
    switch(state){
    case 0:
        return "ZOO_CLOSED_STATE";
    case CONNECTING_STATE_DEF:
        return "ZOO_CONNECTING_STATE";
    case SSL_CONNECTING_STATE_DEF:
        return "ZOO_SSL_CONNECTING_STATE";
    case ASSOCIATING_STATE_DEF:
        return "ZOO_ASSOCIATING_STATE";
    case CONNECTED_STATE_DEF:
        return "ZOO_CONNECTED_STATE";
    case READONLY_STATE_DEF:
        return "ZOO_READONLY_STATE";
    case EXPIRED_SESSION_STATE_DEF:
        return "ZOO_EXPIRED_SESSION_STATE";
    case AUTH_FAILED_STATE_DEF:
        return "ZOO_AUTH_FAILED_STATE";
    }
    return "INVALID_STATE";
}

const int ZOO_CREATED_EVENT = CREATED_EVENT_DEF;
const int ZOO_DELETED_EVENT = DELETED_EVENT_DEF;
const int ZOO_CHANGED_EVENT = CHANGED_EVENT_DEF;
const int ZOO_CHILD_EVENT = CHILD_EVENT_DEF;
const int ZOO_SESSION_EVENT = SESSION_EVENT_DEF;
const int ZOO_NOTWATCHING_EVENT = NOTWATCHING_EVENT_DEF;
static __attribute__ ((unused)) const char* watcherEvent2String(int ev){
    switch(ev){
    case 0:
        return "ZOO_ERROR_EVENT";
    case CREATED_EVENT_DEF:
        return "ZOO_CREATED_EVENT";
    case DELETED_EVENT_DEF:
        return "ZOO_DELETED_EVENT";
    case CHANGED_EVENT_DEF:
        return "ZOO_CHANGED_EVENT";
    case CHILD_EVENT_DEF:
        return "ZOO_CHILD_EVENT";
    case SESSION_EVENT_DEF:
        return "ZOO_SESSION_EVENT";
    case NOTWATCHING_EVENT_DEF:
        return "ZOO_NOTWATCHING_EVENT";
    }
    return "INVALID_EVENT";
}

const int ZOO_PERM_READ = 1 << 0;
const int ZOO_PERM_WRITE = 1 << 1;
const int ZOO_PERM_CREATE = 1 << 2;
const int ZOO_PERM_DELETE = 1 << 3;
const int ZOO_PERM_ADMIN = 1 << 4;
const int ZOO_PERM_ALL = 0x1f;
struct Id ZOO_ANYONE_ID_UNSAFE = {"world", "anyone"};
struct Id ZOO_AUTH_IDS = {"auth", ""};
static struct ACL _OPEN_ACL_UNSAFE_ACL[] = {{0x1f, {"world", "anyone"}}};
static struct ACL _READ_ACL_UNSAFE_ACL[] = {{0x01, {"world", "anyone"}}};
static struct ACL _CREATOR_ALL_ACL_ACL[] = {{0x1f, {"auth", ""}}};
struct ACL_vector ZOO_OPEN_ACL_UNSAFE = { 1, _OPEN_ACL_UNSAFE_ACL};
struct ACL_vector ZOO_READ_ACL_UNSAFE = { 1, _READ_ACL_UNSAFE_ACL};
struct ACL_vector ZOO_CREATOR_ALL_ACL = { 1, _CREATOR_ALL_ACL_ACL};

#define COMPLETION_WATCH -1
#define COMPLETION_VOID 0
#define COMPLETION_STAT 1
#define COMPLETION_DATA 2
#define COMPLETION_STRINGLIST 3
#define COMPLETION_STRINGLIST_STAT 4
#define COMPLETION_ACLLIST 5
#define COMPLETION_STRING 6
#define COMPLETION_MULTI 7
#define COMPLETION_STRING_STAT 8

typedef struct _auth_completion_list {
    void_completion_t completion;
    const char *auth_data;
    struct _auth_completion_list *next;
} auth_completion_list_t;

typedef struct completion {
    int type; /* one of COMPLETION_* values above */
    union {
        void_completion_t void_result;
        stat_completion_t stat_result;
        data_completion_t data_result;
        strings_completion_t strings_result;
        strings_stat_completion_t strings_stat_result;
        acl_completion_t acl_result;
        string_completion_t string_result;
        string_stat_completion_t string_stat_result;
        struct watcher_object_list *watcher_result;
    };
    completion_head_t clist; /* For multi-op */
} completion_t;

typedef struct _completion_list {
    int xid;
    completion_t c;
    const void *data;
    buffer_list_t *buffer;
    struct _completion_list *next;
    watcher_registration_t* watcher;
    watcher_deregistration_t* watcher_deregistration;
} completion_list_t;

const char*err2string(int err);
static inline int calculate_interval(const struct timeval *start,
        const struct timeval *end);
static int queue_session_event(zhandle_t *zh, int state);
static const char* format_endpoint_info(const struct sockaddr_storage* ep);

/* deserialize forward declarations */
static void deserialize_response(zhandle_t *zh, int type, int xid, int failed, int rc, completion_list_t *cptr, struct iarchive *ia);
static int deserialize_multi(zhandle_t *zh, int xid, completion_list_t *cptr, struct iarchive *ia);

/* completion routine forward declarations */
static int add_completion(zhandle_t *zh, int xid, int completion_type,
        const void *dc, const void *data, int add_to_front,
        watcher_registration_t* wo, completion_head_t *clist);
static int add_completion_deregistration(zhandle_t *zh, int xid,
        int completion_type, const void *dc, const void *data,
        int add_to_front, watcher_deregistration_t* wo,
        completion_head_t *clist);
static int do_add_completion(zhandle_t *zh, const void *dc, completion_list_t *c,
        int add_to_front);

static completion_list_t* create_completion_entry(zhandle_t *zh, int xid, int completion_type,
        const void *dc, const void *data, watcher_registration_t* wo,
        completion_head_t *clist);
static completion_list_t* create_completion_entry_deregistration(zhandle_t *zh,
        int xid, int completion_type, const void *dc, const void *data,
        watcher_deregistration_t* wo, completion_head_t *clist);
static completion_list_t* do_create_completion_entry(zhandle_t *zh,
        int xid, int completion_type, const void *dc, const void *data,
        watcher_registration_t* wo, completion_head_t *clist,
        watcher_deregistration_t* wdo);
static void destroy_completion_entry(completion_list_t* c);
static void queue_completion_nolock(completion_head_t *list, completion_list_t *c,
        int add_to_front);
static void queue_completion(completion_head_t *list, completion_list_t *c,
        int add_to_front);
static int handle_socket_error_msg(zhandle_t *zh, int line, const char *func, int rc,
    const char* format,...);
static void cleanup_bufs(zhandle_t *zh,int callCompletion,int rc);

static int disable_conn_permute=0; // permute enabled by default
static struct sockaddr_storage *addr_rw_server = 0;

static void *SYNCHRONOUS_MARKER = (void*)&SYNCHRONOUS_MARKER;
static int isValidPath(const char* path, const int mode);
#ifdef HAVE_OPENSSL_H
static int init_ssl_for_handler(zhandle_t *zh);
static int init_ssl_for_socket(zsock_t *fd, zhandle_t *zh, int fail_on_error);
#endif

static int aremove_watches(
    zhandle_t *zh, const char *path, ZooWatcherType wtype,
    watcher_fn watcher, void *watcherCtx, int local,
    void_completion_t *completion, const void *data, int all);

#ifdef THREADED
static void process_sync_completion(zhandle_t *zh,
        completion_list_t *cptr,
        struct sync_completion *sc,
        struct iarchive *ia);

static int remove_watches(
    zhandle_t *zh, const char *path, ZooWatcherType wtype,
    watcher_fn watcher, void *watcherCtx, int local, int all);
#endif

#ifdef _WIN32
typedef SOCKET socket_t;
typedef int sendsize_t;
#define SEND_FLAGS  0
#else
#ifdef __APPLE__
#define SEND_FLAGS SO_NOSIGPIPE
#endif
#ifdef __linux__
#define SEND_FLAGS MSG_NOSIGNAL
#endif
#ifndef SEND_FLAGS
#define SEND_FLAGS 0
#endif
typedef int socket_t;
typedef ssize_t sendsize_t;
#endif

static void zookeeper_set_sock_nodelay(zhandle_t *, socket_t);
static void zookeeper_set_sock_noblock(zhandle_t *, socket_t);
static void zookeeper_set_sock_timeout(zhandle_t *, socket_t, int);
static socket_t zookeeper_connect(zhandle_t *, struct sockaddr_storage *, socket_t);

/*
 * return 1 if zh has a SASL client configured, 0 otherwise.
 */
static int has_sasl_client(zhandle_t* zh)
{
#ifdef HAVE_CYRUS_SASL_H
    return zh->sasl_client != NULL;
#else /* !HAVE_CYRUS_SASL_H */
    return 0;
#endif /* HAVE_CYRUS_SASL_H */
}

/*
 * return 1 if zh has a SASL client performing authentication, 0 otherwise.
 */
static int is_sasl_auth_in_progress(zhandle_t* zh)
{
#ifdef HAVE_CYRUS_SASL_H
    return zh->sasl_client && zh->sasl_client->state == ZOO_SASL_INTERMEDIATE;
#else /* !HAVE_CYRUS_SASL_H */
    return 0;
#endif /* HAVE_CYRUS_SASL_H */
}

/*
 * Extract the type field (ZOO_*_OP) of a serialized RequestHeader.
 *
 * (This is not the most efficient way of fetching 4 bytes, but it is
 * currently only used during SASL negotiation.)
 *
 * \param buffer the buffer to extract the request type from.  Must
 *   start with a serialized RequestHeader;
 * \param len the buffer length.  Must be positive.
 * \param out_type out parameter; pointer to the location where the
 *   extracted type is to be stored.  Cannot be NULL.
 * \return ZOK on success, or < 0 if something went wrong
 */
static int extract_request_type(char *buffer, int len, int32_t *out_type)
{
    struct iarchive *ia;
    struct RequestHeader h;
    int rc;

    ia = create_buffer_iarchive(buffer, len);
    rc = ia ? ZOK : ZSYSTEMERROR;
    rc = rc < 0 ? rc : deserialize_RequestHeader(ia, "header", &h);
    deallocate_RequestHeader(&h);
    if (ia) {
        close_buffer_iarchive(&ia);
    }

    *out_type = h.type;

    return rc;
}

#ifndef THREADED
/*
 * abort due to the use of a sync api in a singlethreaded environment
 */
static void abort_singlethreaded(zhandle_t *zh)
{
    LOG_ERROR(LOGCALLBACK(zh), "Sync completion used without threads");
    abort();
}
#endif  /* THREADED */

static ssize_t zookeeper_send(zsock_t *fd, const void* buf, size_t len)
{
#ifdef HAVE_OPENSSL_H
    if (fd->ssl_sock)
        return (ssize_t)SSL_write(fd->ssl_sock, buf, (int)len);
#endif
    return send(fd->sock, buf, len, SEND_FLAGS);
}

static ssize_t zookeeper_recv(zsock_t *fd, void *buf, size_t len, int flags)
{
#ifdef HAVE_OPENSSL_H
    if (fd->ssl_sock)
        return (ssize_t)SSL_read(fd->ssl_sock, buf, (int)len);
#endif
    return recv(fd->sock, buf, len, flags);
}

/**
 * Get the system time.
 *
 * If the monotonic clock is available, we use that.  The monotonic clock does
 * not change when the wall-clock time is adjusted by NTP or the system
 * administrator.  The monotonic clock returns a value which is monotonically
 * increasing.
 *
 * If POSIX monotonic clocks are not available, we fall back on the wall-clock.
 *
 * @param tv         (out param) The time.
 */
void get_system_time(struct timeval *tv)
{
  int ret;

#ifdef __MACH__ // OS X
  clock_serv_t cclock;
  mach_timespec_t mts;
  ret = host_get_clock_service(mach_host_self(), SYSTEM_CLOCK, &cclock);
  if (!ret) {
    ret += clock_get_time(cclock, &mts);
    ret += mach_port_deallocate(mach_task_self(), cclock);
    if (!ret) {
      tv->tv_sec = mts.tv_sec;
      tv->tv_usec = mts.tv_nsec / 1000;
    }
  }
  if (ret) {
    // Default to gettimeofday in case of failure.
    ret = gettimeofday(tv, NULL);
  }
#elif defined CLOCK_MONOTONIC_RAW
  // On Linux, CLOCK_MONOTONIC is affected by ntp slew but CLOCK_MONOTONIC_RAW
  // is not.  We want the non-slewed (constant rate) CLOCK_MONOTONIC_RAW if it
  // is available.
  struct timespec ts = { 0 };
  ret = clock_gettime(CLOCK_MONOTONIC_RAW, &ts);
  tv->tv_sec = ts.tv_sec;
  tv->tv_usec = ts.tv_nsec / 1000;
#elif _POSIX_MONOTONIC_CLOCK
  struct timespec ts = { 0 };
  ret = clock_gettime(CLOCK_MONOTONIC, &ts);
  tv->tv_sec = ts.tv_sec;
  tv->tv_usec = ts.tv_nsec / 1000;
#elif _WIN32
  LARGE_INTEGER counts, countsPerSecond, countsPerMicrosecond;
  if (QueryPerformanceFrequency(&countsPerSecond) &&
      QueryPerformanceCounter(&counts)) {
    countsPerMicrosecond.QuadPart = countsPerSecond.QuadPart / 1000000;
    tv->tv_sec = (long)(counts.QuadPart / countsPerSecond.QuadPart);
    tv->tv_usec = (long)((counts.QuadPart % countsPerSecond.QuadPart) /
        countsPerMicrosecond.QuadPart);
    ret = 0;
  } else {
    ret = gettimeofday(tv, NULL);
  }
#else
  ret = gettimeofday(tv, NULL);
#endif
  if (ret) {
    abort();
  }
}

const void *zoo_get_context(zhandle_t *zh)
{
    return zh->context;
}

void zoo_set_context(zhandle_t *zh, void *context)
{
    if (zh != NULL) {
        zh->context = context;
    }
}

int zoo_recv_timeout(zhandle_t *zh)
{
    return zh->recv_timeout;
}

/** these functions are thread unsafe, so make sure that
    zoo_lock_auth is called before you access them **/
static auth_info* get_last_auth(auth_list_head_t *auth_list) {
    auth_info *element;
    element = auth_list->auth;
    if (element == NULL) {
        return NULL;
    }
    while (element->next != NULL) {
        element = element->next;
    }
    return element;
}

static void free_auth_completion(auth_completion_list_t *a_list) {
    auth_completion_list_t *tmp, *ftmp;
    if (a_list == NULL) {
        return;
    }
    tmp = a_list->next;
    while (tmp != NULL) {
        ftmp = tmp;
        tmp = tmp->next;
        ftmp->completion = NULL;
        ftmp->auth_data = NULL;
        free(ftmp);
    }
    a_list->completion = NULL;
    a_list->auth_data = NULL;
    a_list->next = NULL;
    return;
}

static void add_auth_completion(auth_completion_list_t* a_list, void_completion_t* completion,
                                const char *data) {
    auth_completion_list_t *element;
    auth_completion_list_t *n_element;
    element = a_list;
    if (a_list->completion == NULL) {
        //this is the first element
        a_list->completion = *completion;
        a_list->next = NULL;
        a_list->auth_data = data;
        return;
    }
    while (element->next != NULL) {
        element = element->next;
    }
    n_element = (auth_completion_list_t*) malloc(sizeof(auth_completion_list_t));
    n_element->next = NULL;
    n_element->completion = *completion;
    n_element->auth_data = data;
    element->next = n_element;
    return;
}

static void get_auth_completions(auth_list_head_t *auth_list, auth_completion_list_t *a_list) {
    auth_info *element;
    element = auth_list->auth;
    if (element == NULL) {
        return;
    }
    while (element) {
        if (element->completion) {
            add_auth_completion(a_list, &element->completion, element->data);
        }
        element->completion = NULL;
        element = element->next;
    }
    return;
}

static void add_last_auth(auth_list_head_t *auth_list, auth_info *add_el) {
    auth_info  *element;
    element = auth_list->auth;
    if (element == NULL) {
        //first element in the list
        auth_list->auth = add_el;
        return;
    }
    while (element->next != NULL) {
        element = element->next;
    }
    element->next = add_el;
    return;
}

static void init_auth_info(auth_list_head_t *auth_list)
{
    auth_list->auth = NULL;
}

static void mark_active_auth(zhandle_t *zh) {
    auth_list_head_t auth_h = zh->auth_h;
    auth_info *element;
    if (auth_h.auth == NULL) {
        return;
    }
    element = auth_h.auth;
    while (element != NULL) {
        element->state = 1;
        element = element->next;
    }
}

static void free_auth_info(auth_list_head_t *auth_list)
{
    auth_info *auth = auth_list->auth;
    while (auth != NULL) {
        auth_info* old_auth = NULL;
        if(auth->scheme!=NULL)
            free(auth->scheme);
        deallocate_Buffer(&auth->auth);
        old_auth = auth;
        auth = auth->next;
        free(old_auth);
    }
    init_auth_info(auth_list);
}

int is_unrecoverable(zhandle_t *zh)
{
    return (zh->state<0)? ZINVALIDSTATE: ZOK;
}

zk_hashtable *exists_result_checker(zhandle_t *zh, int rc)
{
    if (rc == ZOK) {
        return zh->active_node_watchers;
    } else if (rc == ZNONODE) {
        return zh->active_exist_watchers;
    }
    return 0;
}

zk_hashtable *data_result_checker(zhandle_t *zh, int rc)
{
    return rc==ZOK ? zh->active_node_watchers : 0;
}

zk_hashtable *child_result_checker(zhandle_t *zh, int rc)
{
    return rc==ZOK ? zh->active_child_watchers : 0;
}

void close_zsock(zsock_t *fd)
{
    if (fd->sock != -1) {
#ifdef HAVE_OPENSSL_H
        if (fd->ssl_sock) {
            SSL_free(fd->ssl_sock);
            fd->ssl_sock = NULL;
            SSL_CTX_free(fd->ssl_ctx);
            fd->ssl_ctx = NULL;
        }
#endif
        close(fd->sock);
        fd->sock = -1;
    }
}

/**
 * Frees and closes everything associated with a handle,
 * including the handle itself.
 */
static void destroy(zhandle_t *zh)
{
    if (zh == NULL) {
        return;
    }
    /* call any outstanding completions with a special error code */
    cleanup_bufs(zh,1,ZCLOSING);
    if (process_async(zh->outstanding_sync)) {
        process_completions(zh);
    }
    if (zh->hostname != 0) {
        free(zh->hostname);
        zh->hostname = NULL;
    }
    if (zh->fd->sock != -1) {
        close_zsock(zh->fd);
        memset(&zh->addr_cur, 0, sizeof(zh->addr_cur));
        zh->state = 0;
    }
    addrvec_free(&zh->addrs);

    if (zh->chroot != NULL) {
        free(zh->chroot);
        zh->chroot = NULL;
    }
#ifdef HAVE_OPENSSL_H
    if (zh->fd->cert) {
        free(zh->fd->cert->certstr);
        free(zh->fd->cert);
        zh->fd->cert = NULL;
    }
#endif
    free_auth_info(&zh->auth_h);
    destroy_zk_hashtable(zh->active_node_watchers);
    destroy_zk_hashtable(zh->active_exist_watchers);
    destroy_zk_hashtable(zh->active_child_watchers);
    addrvec_free(&zh->addrs_old);
    addrvec_free(&zh->addrs_new);

#ifdef HAVE_CYRUS_SASL_H
    if (zh->sasl_client) {
        zoo_sasl_client_destroy(zh->sasl_client);
        free(zh->sasl_client);
        zh->sasl_client = NULL;
    }
#endif /* HAVE_CYRUS_SASL_H */
}

static void setup_random()
{
#ifndef _WIN32          // TODO: better seed
    int seed;
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd == -1) {
        seed = getpid();
    } else {
        int seed_len = 0;

        /* Enter a loop to fill in seed with random data from /dev/urandom.
         * This is done in a loop so that we can safely handle short reads
         * which can happen due to signal interruptions.
         */
        while (seed_len < sizeof(seed)) {
            /* Assert we either read something or we were interrupted due to a
             * signal (errno == EINTR) in which case we need to retry.
             */
            int rc = read(fd, &seed + seed_len, sizeof(seed) - seed_len);
            assert(rc > 0 || errno == EINTR);
            if (rc > 0) {
                seed_len += rc;
            }
        }
        close(fd);
    }
    srandom(seed);
    srand48(seed);
#endif
}

#ifndef __CYGWIN__
/**
 * get the errno from the return code
 * of get addrinfo. Errno is not set
 * with the call to getaddrinfo, so thats
 * why we have to do this.
 */
static int getaddrinfo_errno(int rc) {
    switch(rc) {
    case EAI_NONAME:
// ZOOKEEPER-1323 EAI_NODATA and EAI_ADDRFAMILY are deprecated in FreeBSD.
#if defined EAI_NODATA && EAI_NODATA != EAI_NONAME
    case EAI_NODATA:
#endif
        return ENOENT;
    case EAI_MEMORY:
        return ENOMEM;
    default:
        return EINVAL;
    }
}
#endif

/**
 * Count the number of hosts in the connection host string. This assumes it's
 * a well-formed connection string whereby each host is separated by a comma.
 */
static int count_hosts(char *hosts)
{
    uint32_t count = 0;
    char *loc = hosts;
    if (!hosts || strlen(hosts) == 0) {
        return 0;
    }

    while ((loc = strchr(loc, ','))) {
        count++;
        loc+=1;
    }

    return count+1;
}

/**
 * Resolve hosts and populate provided address vector with shuffled results.
 * The contents of the provided address vector will be initialized to an
 * empty state.
 */
static int resolve_hosts(const zhandle_t *zh, const char *hosts_in, addrvec_t *avec)
{
    int rc = ZOK;
    char *host = NULL;
    char *hosts = NULL;
    int num_hosts = 0;
    char *strtok_last = NULL;

    if (zh == NULL || hosts_in == NULL || avec == NULL) {
        return ZBADARGUMENTS;
    }

    // initialize address vector
    addrvec_init(avec);

    hosts = strdup(hosts_in);
    if (hosts == NULL) {
        LOG_ERROR(LOGCALLBACK(zh), "out of memory");
        errno=ENOMEM;
        rc=ZSYSTEMERROR;
        goto fail;
    }

    num_hosts = count_hosts(hosts);
    if (num_hosts == 0) {
        free(hosts);
        return ZOK;
    }

    // Allocate list inside avec
    rc = addrvec_alloc_capacity(avec, num_hosts);
    if (rc != 0) {
        LOG_ERROR(LOGCALLBACK(zh), "out of memory");
        errno=ENOMEM;
        rc=ZSYSTEMERROR;
        goto fail;
    }

    host = strtok_r(hosts, ",", &strtok_last);
    while(host) {
        char *port_spec = strrchr(host, ':');
        char *end_port_spec;
        int port;
        if (!port_spec) {
            LOG_ERROR(LOGCALLBACK(zh), "no port in %s", host);
            errno=EINVAL;
            rc=ZBADARGUMENTS;
            goto fail;
        }
        *port_spec = '\0';
        port_spec++;
        port = strtol(port_spec, &end_port_spec, 0);
        if (!*port_spec || *end_port_spec || port == 0) {
            LOG_ERROR(LOGCALLBACK(zh), "invalid port in %s", host);
            errno=EINVAL;
            rc=ZBADARGUMENTS;
            goto fail;
        }
#if defined(__CYGWIN__)
        // sadly CYGWIN doesn't have getaddrinfo
        // but happily gethostbyname is threadsafe in windows
        {
        struct hostent *he;
        char **ptr;
        struct sockaddr_in *addr4;

        he = gethostbyname(host);
        if (!he) {
            LOG_ERROR(LOGCALLBACK(zh), "could not resolve %s", host);
            errno=ENOENT;
            rc=ZBADARGUMENTS;
            goto fail;
        }

        // Setup the address array
        for(ptr = he->h_addr_list;*ptr != 0; ptr++) {
            if (addrs->count == addrs->capacity) {
                rc = addrvec_grow_default(addrs);
                if (rc != 0) {
                    LOG_ERROR(LOGCALLBACK(zh), "out of memory");
                    errno=ENOMEM;
                    rc=ZSYSTEMERROR;
                    goto fail;
                }
            }
            addr = &addrs->list[addrs->count];
            addr4 = (struct sockaddr_in*)addr;
            addr->ss_family = he->h_addrtype;
            if (addr->ss_family == AF_INET) {
                addr4->sin_port = htons(port);
                memset(&addr4->sin_zero, 0, sizeof(addr4->sin_zero));
                memcpy(&addr4->sin_addr, *ptr, he->h_length);
                zh->addrs.count++;
            }
#if defined(AF_INET6)
            else if (addr->ss_family == AF_INET6) {
                struct sockaddr_in6 *addr6;

                addr6 = (struct sockaddr_in6*)addr;
                addr6->sin6_port = htons(port);
                addr6->sin6_scope_id = 0;
                addr6->sin6_flowinfo = 0;
                memcpy(&addr6->sin6_addr, *ptr, he->h_length);
                zh->addrs.count++;
            }
#endif
            else {
                LOG_WARN(LOGCALLBACK(zh), "skipping unknown address family %x for %s",
                         addr->ss_family, hosts_in);
            }
        }
        host = strtok_r(0, ",", &strtok_last);
        }
#else
        {
        struct addrinfo hints, *res, *res0;

        memset(&hints, 0, sizeof(hints));
#ifdef AI_ADDRCONFIG
        hints.ai_flags = AI_ADDRCONFIG;
#else
        hints.ai_flags = 0;
#endif
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        hints.ai_protocol = IPPROTO_TCP;

        while(isspace(*host) && host != strtok_last)
            host++;

        if ((rc = getaddrinfo(host, port_spec, &hints, &res0)) != 0) {
            //bug in getaddrinfo implementation when it returns
            //EAI_BADFLAGS or EAI_ADDRFAMILY with AF_UNSPEC and
            // ai_flags as AI_ADDRCONFIG
#ifdef AI_ADDRCONFIG
            if ((hints.ai_flags == AI_ADDRCONFIG) &&
// ZOOKEEPER-1323 EAI_NODATA and EAI_ADDRFAMILY are deprecated in FreeBSD.
#ifdef EAI_ADDRFAMILY
                ((rc ==EAI_BADFLAGS) || (rc == EAI_ADDRFAMILY))) {
#else
                (rc == EAI_BADFLAGS)) {
#endif
                //reset ai_flags to null
                hints.ai_flags = 0;
                //retry getaddrinfo
                rc = getaddrinfo(host, port_spec, &hints, &res0);
            }
#endif
            if (rc != 0) {
                errno = getaddrinfo_errno(rc);
#ifdef _WIN32
                LOG_ERROR(LOGCALLBACK(zh), "Win32 message: %s\n", gai_strerror(rc));
#elif __linux__ && __GNUC__
                LOG_ERROR(LOGCALLBACK(zh), "getaddrinfo: %s\n", gai_strerror(rc));
#else
                LOG_ERROR(LOGCALLBACK(zh), "getaddrinfo: %s\n", strerror(errno));
#endif
                rc=ZSYSTEMERROR;
                goto next;
            }
        }

        for (res = res0; res; res = res->ai_next) {
            // Expand address list if needed
            if (avec->count == avec->capacity) {
                rc = addrvec_grow_default(avec);
                if (rc != 0) {
                    LOG_ERROR(LOGCALLBACK(zh), "out of memory");
                    errno=ENOMEM;
                    rc=ZSYSTEMERROR;
                    goto fail;
                }
            }

            // Copy addrinfo into address list
            switch (res->ai_family) {
            case AF_INET:
#if defined(AF_INET6)
            case AF_INET6:
#endif
                addrvec_append_addrinfo(avec, res);
                break;
            default:
                LOG_WARN(LOGCALLBACK(zh), "skipping unknown address family %x for %s",
                          res->ai_family, hosts_in);
                break;
            }
        }

        freeaddrinfo(res0);
next:
        host = strtok_r(0, ",", &strtok_last);
        }
#endif
    }
    if (avec->count == 0) {
      rc = ZSYSTEMERROR; // not a single host resolved
      goto fail;
    }

    free(hosts);

    if(!disable_conn_permute){
        setup_random();
        addrvec_shuffle(avec);
    }

    return ZOK;

fail:
    addrvec_free(avec);

    if (hosts) {
        free(hosts);
        hosts = NULL;
    }

    return rc;
}

/**
 * Updates the list of servers and determine if changing connections is necessary.
 * Permutes server list for proper load balancing.
 *
 * Changing connections is necessary if one of the following holds:
 * a) the server this client is currently connected is not in new address list.
 *    Otherwise (if currentHost is in the new list):
 * b) the number of servers in the cluster is increasing - in this case the load
 *    on currentHost should decrease, which means that SOME of the clients
 *    connected to it will migrate to the new servers. The decision whether this
 *    client migrates or not is probabilistic so that the expected number of
 *    clients connected to each server is the same.
 *
 * If reconfig is set to true, the function sets pOld and pNew that correspond
 * to the probability to migrate to ones of the new servers or one of the old
 * servers (migrating to one of the old servers is done only if our client's
 * currentHost is not in new list).
 *
 * See zoo_cycle_next_server for the selection logic.
 *
 * \param ref_time an optional "reference time," used to determine if
 * resolution can be skipped in accordance to the delay set by \ref
 * zoo_set_servers_resolution_delay.  Passing NULL prevents skipping.
 *
 * See {@link https://issues.apache.org/jira/browse/ZOOKEEPER-1355} for the
 * protocol and its evaluation,
 */
int update_addrs(zhandle_t *zh, const struct timeval *ref_time)
{
    int rc = ZOK;
    char *hosts = NULL;
    uint32_t num_old = 0;
    uint32_t num_new = 0;
    uint32_t i = 0;
    int found_current = 0;
    addrvec_t resolved = { 0 };

    // Verify we have a valid handle
    if (zh == NULL) {
        return ZBADARGUMENTS;
    }

    // zh->hostname should always be set
    if (zh->hostname == NULL)
    {
        return ZSYSTEMERROR;
    }

    // NOTE: guard access to {hostname, addr_cur, addrs, addrs_old, addrs_new, last_resolve, resolve_delay_ms}
    lock_reconfig(zh);

    // Check if we are due for a host name resolution.  (See
    // zoo_set_servers_resolution_delay.  The answer is always "yes"
    // if no reference is provided or the file descriptor is invalid.)
    if (ref_time && zh->fd->sock != -1) {
        int do_resolve;

        if (zh->resolve_delay_ms <= 0) {
            // -1 disables, 0 means unconditional.  Fail safe.
            do_resolve = zh->resolve_delay_ms != -1;
        } else {
            int elapsed_ms = calculate_interval(&zh->last_resolve, ref_time);
            // Include < 0 in case of overflow, or if we are not
            // backed by a monotonic clock.
            do_resolve = elapsed_ms > zh->resolve_delay_ms || elapsed_ms < 0;
        }

        if (!do_resolve) {
            goto finish;
        }
    }

    // Copy zh->hostname for local use
    hosts = strdup(zh->hostname);
    if (hosts == NULL) {
        rc = ZSYSTEMERROR;
        goto finish;
    }

    rc = resolve_hosts(zh, hosts, &resolved);
    if (rc != ZOK)
    {
        goto finish;
    }

    // Unconditionally note last resolution time.
    if (ref_time) {
        zh->last_resolve = *ref_time;
    } else {
        get_system_time(&zh->last_resolve);
    }

    // If the addrvec list is identical to last time we ran don't do anything
    if (addrvec_eq(&zh->addrs, &resolved))
    {
        goto finish;
    }

    // Is the server we're connected to in the new resolved list?
    found_current = addrvec_contains(&resolved, &zh->addr_cur);

    // Clear out old and new address lists
    zh->reconfig = 1;
    addrvec_free(&zh->addrs_old);
    addrvec_free(&zh->addrs_new);

    // Divide server list into addrs_old if in previous list and addrs_new if not
    for (i = 0; i < resolved.count; i++)
    {
        struct sockaddr_storage *resolved_address = &resolved.data[i];
        if (addrvec_contains(&zh->addrs, resolved_address))
        {
            rc = addrvec_append(&zh->addrs_old, resolved_address);
            if (rc != ZOK)
            {
                goto finish;
            }
        }
        else {
            rc = addrvec_append(&zh->addrs_new, resolved_address);
            if (rc != ZOK)
            {
                goto finish;
            }
        }
    }

    num_old = zh->addrs_old.count;
    num_new = zh->addrs_new.count;

    // Number of servers increased
    if (num_old + num_new > zh->addrs.count)
    {
        if (found_current) {
            // my server is in the new config, but load should be decreased.
            // Need to decide if the client is moving to one of the new servers
            if (drand48() <= (1 - ((double)zh->addrs.count) / (num_old + num_new))) {
                zh->pNew = 1;
                zh->pOld = 0;
            } else {
                // do nothing special -- stay with the current server
                zh->reconfig = 0;
            }
        } else {
            // my server is not in the new config, and load on old servers must
            // be decreased, so connect to one of the new servers
            zh->pNew = 1;
            zh->pOld = 0;
        }
    }

    // Number of servers stayed the same or decreased
    else {
        if (found_current) {
            // my server is in the new config, and load should be increased, so
            // stay with this server and do nothing special
            zh->reconfig = 0;
        } else {
            zh->pOld = ((double) (num_old * (zh->addrs.count - (num_old + num_new)))) / ((num_old + num_new) * (zh->addrs.count - num_old));
            zh->pNew = 1 - zh->pOld;
        }
    }

    addrvec_free(&zh->addrs);
    zh->addrs = resolved;

    // If we need to do a reconfig and we're currently connected to a server,
    // then force close that connection so on next interest() call we'll make a
    // new connection
    if (zh->reconfig == 1 && zh->fd->sock != -1)
    {
        close_zsock(zh->fd);
        zh->state = ZOO_NOTCONNECTED_STATE;
    }

finish:

    unlock_reconfig(zh);

    // If we short-circuited out and never assigned resolved to zh->addrs then we
    // need to free resolved to avoid a memleak.
    if (resolved.data && zh->addrs.data != resolved.data)
    {
        addrvec_free(&resolved);
    }

    if (hosts) {
        free(hosts);
        hosts = NULL;
    }

    return rc;
}

const clientid_t *zoo_client_id(zhandle_t *zh)
{
    return &zh->client_id;
}

static void null_watcher_fn(zhandle_t* p1, int p2, int p3,const char* p4,void*p5){}

watcher_fn zoo_set_watcher(zhandle_t *zh,watcher_fn newFn)
{
    watcher_fn oldWatcher=zh->watcher;
    if (newFn) {
       zh->watcher = newFn;
    } else {
       zh->watcher = null_watcher_fn;
    }
    return oldWatcher;
}

struct sockaddr* zookeeper_get_connected_host(zhandle_t *zh,
                 struct sockaddr *addr, socklen_t *addr_len)
{
    if (zh->state!=ZOO_CONNECTED_STATE) {
        return NULL;
    }
    if (getpeername(zh->fd->sock, addr, addr_len)==-1) {
        return NULL;
    }
    return addr;
}

static void log_env(zhandle_t *zh) {
  char buf[2048];
#ifdef HAVE_SYS_UTSNAME_H
  struct utsname utsname;
#endif

#if defined(HAVE_GETUID) && defined(HAVE_GETPWUID_R)
  struct passwd pw;
  struct passwd *pwp = NULL;
  uid_t uid = 0;
#endif

  LOG_INFO(LOGCALLBACK(zh), "Client environment:zookeeper.version=%s", PACKAGE_STRING);

#ifdef HAVE_GETHOSTNAME
  gethostname(buf, sizeof(buf));
  LOG_INFO(LOGCALLBACK(zh), "Client environment:host.name=%s", buf);
#else
  LOG_INFO(LOGCALLBACK(zh), "Client environment:host.name=<not implemented>");
#endif

#ifdef HAVE_SYS_UTSNAME_H
  uname(&utsname);
  LOG_INFO(LOGCALLBACK(zh), "Client environment:os.name=%s", utsname.sysname);
  LOG_INFO(LOGCALLBACK(zh), "Client environment:os.arch=%s", utsname.release);
  LOG_INFO(LOGCALLBACK(zh), "Client environment:os.version=%s", utsname.version);
#else
  LOG_INFO(LOGCALLBACK(zh), "Client environment:os.name=<not implemented>");
  LOG_INFO(LOGCALLBACK(zh), "Client environment:os.arch=<not implemented>");
  LOG_INFO(LOGCALLBACK(zh), "Client environment:os.version=<not implemented>");
#endif

#ifdef HAVE_GETLOGIN
  LOG_INFO(LOGCALLBACK(zh), "Client environment:user.name=%s", getlogin());
#else
  LOG_INFO(LOGCALLBACK(zh), "Client environment:user.name=<not implemented>");
#endif

#if defined(HAVE_GETUID) && defined(HAVE_GETPWUID_R)
  uid = getuid();
  if (!getpwuid_r(uid, &pw, buf, sizeof(buf), &pwp) && pwp) {
    LOG_INFO(LOGCALLBACK(zh), "Client environment:user.home=%s", pw.pw_dir);
  } else {
    LOG_INFO(LOGCALLBACK(zh), "Client environment:user.home=<NA>");
  }
#else
  LOG_INFO(LOGCALLBACK(zh), "Client environment:user.home=<not implemented>");
#endif

#ifdef HAVE_GETCWD
  if (!getcwd(buf, sizeof(buf))) {
    LOG_INFO(LOGCALLBACK(zh), "Client environment:user.dir=<toolong>");
  } else {
    LOG_INFO(LOGCALLBACK(zh), "Client environment:user.dir=%s", buf);
  }
#else
  LOG_INFO(LOGCALLBACK(zh), "Client environment:user.dir=<not implemented>");
#endif
}

/**
 * Create a zookeeper handle associated with the given host and port.
 */
static zhandle_t *zookeeper_init_internal(const char *host, watcher_fn watcher,
        int recv_timeout, const clientid_t *clientid, void *context, int flags,
        log_callback_fn log_callback, zcert_t *cert, void *sasl_params)
{
    int errnosave = 0;
    zhandle_t *zh = NULL;
    char *index_chroot = NULL;

    // Create our handle
    zh = calloc(1, sizeof(*zh));
    if (!zh) {
        return 0;
    }

    // Set log callback before calling into log_env
    zh->log_callback = log_callback;

    if (!(flags & ZOO_NO_LOG_CLIENTENV)) {
        log_env(zh);
    }

    zh->fd = calloc(1, sizeof(zsock_t));
    zh->fd->sock = -1;
    if (cert) {
        zh->fd->cert = calloc(1, sizeof(zcert_t));
        memcpy(zh->fd->cert, cert, sizeof(zcert_t));
    }

#ifdef _WIN32
    if (Win32WSAStartup()){
        LOG_ERROR(LOGCALLBACK(zh), "Error initializing ws2_32.dll");
        return 0;
    }
#endif
    LOG_INFO(LOGCALLBACK(zh), "Initiating client connection, host=%s sessionTimeout=%d watcher=%p"
          " sessionId=%#llx sessionPasswd=%s context=%p flags=%d",
              host,
              recv_timeout,
              watcher,
              (clientid == 0 ? 0 : clientid->client_id),
              ((clientid == 0) || (clientid->passwd[0] == 0) ?
               "<null>" : "<hidden>"),
              context,
              flags);

    zh->hostname = NULL;
    zh->state = ZOO_NOTCONNECTED_STATE;
    zh->context = context;
    zh->recv_timeout = recv_timeout;
    zh->allow_read_only = flags & ZOO_READONLY;
    // non-zero clientid implies we've seen r/w server already
    zh->seen_rw_server_before = (clientid != 0 && clientid->client_id != 0);
    init_auth_info(&zh->auth_h);
    if (watcher) {
       zh->watcher = watcher;
    } else {
       zh->watcher = null_watcher_fn;
    }
    if (host == 0 || *host == 0) { // what we shouldn't dup
        errno=EINVAL;
        goto abort;
    }
    //parse the host to get the chroot if available
    index_chroot = strchr(host, '/');
    if (index_chroot) {
        zh->chroot = strdup(index_chroot);
        if (zh->chroot == NULL) {
            goto abort;
        }
        // if chroot is just / set it to null
        if (strlen(zh->chroot) == 1) {
            free(zh->chroot);
            zh->chroot = NULL;
        }
        // cannot use strndup so allocate and strcpy
        zh->hostname = (char *) malloc(index_chroot - host + 1);
        zh->hostname = strncpy(zh->hostname, host, (index_chroot - host));
        //strncpy does not null terminate
        *(zh->hostname + (index_chroot - host)) = '\0';

    } else {
        zh->chroot = NULL;
        zh->hostname = strdup(host);
    }
    if (zh->chroot && !isValidPath(zh->chroot, 0)) {
        errno = EINVAL;
        goto abort;
    }
    if (zh->hostname == 0) {
        goto abort;
    }
    if(update_addrs(zh, NULL) != 0) {
        goto abort;
    }

    if (clientid) {
        memcpy(&zh->client_id, clientid, sizeof(zh->client_id));
    } else {
        memset(&zh->client_id, 0, sizeof(zh->client_id));
    }
    zh->io_count = 0;
    zh->primer_buffer.buffer = zh->primer_storage_buffer;
    zh->primer_buffer.curr_offset = 0;
    zh->primer_buffer.len = sizeof(zh->primer_storage_buffer);
    zh->primer_buffer.next = 0;
    zh->last_zxid = 0;
    zh->next_deadline.tv_sec=zh->next_deadline.tv_usec=0;
    zh->socket_readable.tv_sec=zh->socket_readable.tv_usec=0;
    zh->active_node_watchers=create_zk_hashtable();
    zh->active_exist_watchers=create_zk_hashtable();
    zh->active_child_watchers=create_zk_hashtable();
    zh->disable_reconnection_attempt = 0;

#ifdef HAVE_CYRUS_SASL_H
    if (sasl_params) {
        zh->sasl_client = zoo_sasl_client_create(
            (zoo_sasl_params_t*)sasl_params);
        if (!zh->sasl_client) {
            goto abort;
        }
    }
#endif /* HAVE_CYRUS_SASL_H */

    if (adaptor_init(zh) == -1) {
        goto abort;
    }

    return zh;
abort:
    errnosave=errno;
    destroy(zh);
    free(zh->fd);
    free(zh);
    errno=errnosave;
    return 0;
}

zhandle_t *zookeeper_init(const char *host, watcher_fn watcher,
        int recv_timeout, const clientid_t *clientid, void *context, int flags)
{
    return zookeeper_init_internal(host, watcher, recv_timeout, clientid, context, flags, NULL, NULL, NULL);
}

zhandle_t *zookeeper_init2(const char *host, watcher_fn watcher,
        int recv_timeout, const clientid_t *clientid, void *context, int flags,
        log_callback_fn log_callback)
{
    return zookeeper_init_internal(host, watcher, recv_timeout, clientid, context, flags, log_callback, NULL, NULL);
}

#ifdef HAVE_OPENSSL_H
zhandle_t *zookeeper_init_ssl(const char *host, const char *cert, watcher_fn watcher,
        int recv_timeout, const clientid_t *clientid, void *context, int flags)
{
    zcert_t zcert;
    zcert.certstr = strdup(cert);
    zcert.ca = strtok(strdup(cert), ",");
    zcert.cert = strtok(NULL, ",");
    zcert.key = strtok(NULL, ",");
    zcert.passwd = strtok(NULL, ",");       
    return zookeeper_init_internal(host, watcher, recv_timeout, clientid, context, flags, NULL, &zcert, NULL);
}
#endif

#ifdef HAVE_CYRUS_SASL_H
zhandle_t *zookeeper_init_sasl(const char *host, watcher_fn watcher,
        int recv_timeout, const clientid_t *clientid, void *context, int flags,
        log_callback_fn log_callback, zoo_sasl_params_t *sasl_params)
{
    return zookeeper_init_internal(host, watcher, recv_timeout, clientid, context, flags, log_callback, NULL, sasl_params);
}
#endif /* HAVE_CYRUS_SASL_H */

/**
 * Set a new list of zk servers to connect to.  Disconnect will occur if
 * current connection endpoint is not in the list.
 */
int zoo_set_servers(zhandle_t *zh, const char *hosts)
{
    if (hosts == NULL)
    {
        LOG_ERROR(LOGCALLBACK(zh), "New server list cannot be empty");
        return ZBADARGUMENTS;
    }

    // NOTE: guard access to {hostname, addr_cur, addrs, addrs_old, addrs_new, last_resolve, resolve_delay_ms}
    lock_reconfig(zh);

    // Reset hostname to new set of hosts to connect to
    if (zh->hostname) {
        free(zh->hostname);
    }

    zh->hostname = strdup(hosts);

    unlock_reconfig(zh);

    return update_addrs(zh, NULL);
}

/*
 * Sets a minimum delay to observe between "routine" host name
 * resolutions.  See prototype for full documentation.
 */
int zoo_set_servers_resolution_delay(zhandle_t *zh, int delay_ms) {
    if (delay_ms < -1) {
        LOG_ERROR(LOGCALLBACK(zh), "Resolution delay cannot be %d", delay_ms);
        return ZBADARGUMENTS;
    }

    // NOTE: guard access to {hostname, addr_cur, addrs, addrs_old, addrs_new, last_resolve, resolve_delay_ms}
    lock_reconfig(zh);

    zh->resolve_delay_ms = delay_ms;

    unlock_reconfig(zh);

    return ZOK;
}

/**
 * Get the next server to connect to, when in 'reconfig' mode, which means that
 * we've updated the server list to connect to, and are now trying to find some
 * server to connect to. Once we get successfully connected, 'reconfig' mode is
 * set to false. Similarly, if we tried to connect to all servers in new config
 * and failed, 'reconfig' mode is set to false.
 *
 * While in 'reconfig' mode, we should connect to a server in the new set of
 * servers (addrs_new) with probability pNew and to servers in the old set of
 * servers (addrs_old) with probability pOld (which is just 1-pNew). If we tried
 * out all servers in either, we continue to try servers from the other set,
 * regardless of pNew or pOld. If we tried all servers we give up and go back to
 * the normal round robin mode
 *
 * When called, must be protected by lock_reconfig(zh).
 */
static int get_next_server_in_reconfig(zhandle_t *zh)
{
    int take_new = drand48() <= zh->pNew;

    LOG_DEBUG(LOGCALLBACK(zh), "[OLD] count=%d capacity=%d next=%d hasnext=%d",
               zh->addrs_old.count, zh->addrs_old.capacity, zh->addrs_old.next,
               addrvec_hasnext(&zh->addrs_old));
    LOG_DEBUG(LOGCALLBACK(zh), "[NEW] count=%d capacity=%d next=%d hasnext=%d",
               zh->addrs_new.count, zh->addrs_new.capacity, zh->addrs_new.next,
               addrvec_hasnext(&zh->addrs_new));

    // Take one of the new servers if we haven't tried them all yet
    // and either the probability tells us to connect to one of the new servers
    // or if we already tried them all then use one of the old servers
    if (addrvec_hasnext(&zh->addrs_new)
            && (take_new || !addrvec_hasnext(&zh->addrs_old)))
    {
        addrvec_next(&zh->addrs_new, &zh->addr_cur);
        LOG_DEBUG(LOGCALLBACK(zh), "Using next from NEW=%s", format_endpoint_info(&zh->addr_cur));
        return 0;
    }

    // start taking old servers
    if (addrvec_hasnext(&zh->addrs_old)) {
        addrvec_next(&zh->addrs_old, &zh->addr_cur);
        LOG_DEBUG(LOGCALLBACK(zh), "Using next from OLD=%s", format_endpoint_info(&zh->addr_cur));
        return 0;
    }

    LOG_DEBUG(LOGCALLBACK(zh), "Failed to find either new or old");
    memset(&zh->addr_cur, 0, sizeof(zh->addr_cur));
    return 1;
}

/**
 * Cycle through our server list to the correct 'next' server. The 'next' server
 * to connect to depends upon whether we're in a 'reconfig' mode or not. Reconfig
 * mode means we've upated the server list and are now trying to find a server
 * to connect to. Once we get connected, we are no longer in the reconfig mode.
 * Similarly, if we try to connect to all the servers in the new configuration
 * and failed, reconfig mode is set to false.
 *
 * For more algorithm details, see get_next_server_in_reconfig.
 */
void zoo_cycle_next_server(zhandle_t *zh)
{
    // NOTE: guard access to {hostname, addr_cur, addrs, addrs_old, addrs_new, last_resolve, resolve_delay_ms}
    lock_reconfig(zh);

    memset(&zh->addr_cur, 0, sizeof(zh->addr_cur));

    if (zh->reconfig)
    {
        if (get_next_server_in_reconfig(zh) == 0) {
            unlock_reconfig(zh);
            return;
        }

        // tried all new and old servers and couldn't connect
        zh->reconfig = 0;
    }

    addrvec_next(&zh->addrs, &zh->addr_cur);

    unlock_reconfig(zh);

    return;
}

/**
 * Get the host:port for the server we are currently connecting to or connected
 * to. This is largely for testing purposes but is also generally useful for
 * other client software built on top of this client.
 */
const char* zoo_get_current_server(zhandle_t* zh)
{
    const char *endpoint_info = NULL;

    // NOTE: guard access to {hostname, addr_cur, addrs, addrs_old, addrs_new, last_resolve, resolve_delay_ms}
    // Need the lock here as it is changed in update_addrs()
    lock_reconfig(zh);

    endpoint_info = format_endpoint_info(&zh->addr_cur);
    unlock_reconfig(zh);
    return endpoint_info;
}

/**
 * deallocated the free_path only its beeen allocated
 * and not equal to path
 */
void free_duplicate_path(const char *free_path, const char* path) {
    if (free_path != path) {
        free((void*)free_path);
    }
}

/**
  prepend the chroot path if available else return the path
*/
static char* prepend_string(zhandle_t *zh, const char* client_path) {
    char *ret_str;
    if (zh == NULL || zh->chroot == NULL)
        return (char *) client_path;
    // handle the chroot itself, client_path = "/"
    if (strlen(client_path) == 1) {
        return strdup(zh->chroot);
    }
    ret_str = (char *) malloc(strlen(zh->chroot) + strlen(client_path) + 1);
    strcpy(ret_str, zh->chroot);
    return strcat(ret_str, client_path);
}

/**
   strip off the chroot string from the server path
   if there is one else return the exact path
 */
char* sub_string(zhandle_t *zh, const char* server_path) {
    char *ret_str;
    if (zh->chroot == NULL)
        return (char *) server_path;
    //ZOOKEEPER-1027
    if (strncmp(server_path, zh->chroot, strlen(zh->chroot)) != 0) {
        LOG_ERROR(LOGCALLBACK(zh), "server path %s does not include chroot path %s",
                   server_path, zh->chroot);
        return (char *) server_path;
    }
    if (strlen(server_path) == strlen(zh->chroot)) {
        //return "/"
        ret_str = strdup("/");
        return ret_str;
    }
    ret_str = strdup(server_path + strlen(zh->chroot));
    return ret_str;
}

static buffer_list_t *allocate_buffer(char *buff, int len)
{
    buffer_list_t *buffer = calloc(1, sizeof(*buffer));
    if (buffer == 0)
        return 0;

    buffer->len = len==0?sizeof(*buffer):len;
    buffer->curr_offset = 0;
    buffer->buffer = buff;
    buffer->next = 0;
    return buffer;
}

static void free_buffer(buffer_list_t *b)
{
    if (!b) {
        return;
    }
    if (b->buffer) {
        free(b->buffer);
    }
    free(b);
}

static buffer_list_t *dequeue_buffer(buffer_head_t *list)
{
    buffer_list_t *b;
    lock_buffer_list(list);
    b = list->head;
    if (b) {
        list->head = b->next;
        if (!list->head) {
            assert(b == list->last);
            list->last = 0;
        }
    }
    unlock_buffer_list(list);
    return b;
}

static int remove_buffer(buffer_head_t *list)
{
    buffer_list_t *b = dequeue_buffer(list);
    if (!b) {
        return 0;
    }
    free_buffer(b);
    return 1;
}

static void queue_buffer(buffer_head_t *list, buffer_list_t *b, int add_to_front)
{
    b->next = 0;
    lock_buffer_list(list);
    if (list->head) {
        assert(list->last);
        // The list is not empty
        if (add_to_front) {
            b->next = list->head;
            list->head = b;
        } else {
            list->last->next = b;
            list->last = b;
        }
    }else{
        // The list is empty
        assert(!list->head);
        list->head = b;
        list->last = b;
    }
    unlock_buffer_list(list);
}

static int queue_buffer_bytes(buffer_head_t *list, char *buff, int len)
{
    buffer_list_t *b  = allocate_buffer(buff,len);
    if (!b)
        return ZSYSTEMERROR;
    queue_buffer(list, b, 0);
    return ZOK;
}

static int queue_front_buffer_bytes(buffer_head_t *list, char *buff, int len)
{
    buffer_list_t *b  = allocate_buffer(buff,len);
    if (!b)
        return ZSYSTEMERROR;
    queue_buffer(list, b, 1);
    return ZOK;
}

static __attribute__ ((unused)) int get_queue_len(buffer_head_t *list)
{
    int i;
    buffer_list_t *ptr;
    lock_buffer_list(list);
    ptr = list->head;
    for (i=0; ptr!=0; ptr=ptr->next, i++)
        ;
    unlock_buffer_list(list);
    return i;
}
/* returns:
 * -1 if send failed,
 * 0 if send would block while sending the buffer (or a send was incomplete),
 * 1 if success
 */
static int send_buffer(zhandle_t *zh, buffer_list_t *buff)
{
    int len = buff->len;
    int off = buff->curr_offset;
    int rc = -1;

    if (off < 4) {
        /* we need to send the length at the beginning */
        int nlen = htonl(len);
        char *b = (char*)&nlen;
        rc = zookeeper_send(zh->fd, b + off, sizeof(nlen) - off);
        if (rc == -1) {
#ifdef _WIN32
            if (WSAGetLastError() != WSAEWOULDBLOCK) {
#else
            if (errno != EAGAIN) {
#endif
                return -1;
            } else {
                return 0;
            }
        } else {
            buff->curr_offset  += rc;
        }
        off = buff->curr_offset;
    }
    if (off >= 4) {
        /* want off to now represent the offset into the buffer */
        off -= sizeof(buff->len);
        rc = zookeeper_send(zh->fd, buff->buffer + off, len - off);
        if (rc == -1) {
#ifdef _WIN32
            if (WSAGetLastError() != WSAEWOULDBLOCK) {
#else
            if (errno != EAGAIN) {
#endif
                return -1;
            }
        } else {
            buff->curr_offset += rc;
        }
    }
    return buff->curr_offset == len + sizeof(buff->len);
}

/* returns:
 * -1 if recv call failed,
 * 0 if recv would block,
 * 1 if success
 */
static int recv_buffer(zhandle_t *zh, buffer_list_t *buff)
{
    int off = buff->curr_offset;
    int rc = 0;

    /* if buffer is less than 4, we are reading in the length */
    if (off < 4) {
        char *buffer = (char*)&(buff->len);
        rc = zookeeper_recv(zh->fd, buffer+off, sizeof(int)-off, 0);
        switch (rc) {
        case 0:
            errno = EHOSTDOWN;
        case -1:
#ifdef _WIN32
            if (WSAGetLastError() == WSAEWOULDBLOCK) {
#else
            if (errno == EAGAIN) {
#endif
                return 0;
            }
            return -1;
        default:
            buff->curr_offset += rc;
        }
        off = buff->curr_offset;
        if (buff->curr_offset == sizeof(buff->len)) {
            buff->len = ntohl(buff->len);
            buff->buffer = calloc(1, buff->len);
        }
    }
    if (buff->buffer) {
        /* want off to now represent the offset into the buffer */
        off -= sizeof(buff->len);

        rc = zookeeper_recv(zh->fd, buff->buffer+off, buff->len-off, 0);

        /* dirty hack to make new client work against old server
         * old server sends 40 bytes to finish connection handshake,
         * while we're expecting 41 (1 byte for read-only mode data) */
        if (rc > 0 && buff == &zh->primer_buffer) {
            /* primer_buffer's curr_offset starts at 4 (see prime_connection) */
            int avail = buff->curr_offset - sizeof(buff->len) + rc;

            /* exactly 40 bytes (out of 41 expected) collected? */
            if (avail == buff->len - 1) {
                int32_t reply_len;

                /* extract length of ConnectResponse (+ 1-byte flag?) */
                memcpy(&reply_len, buff->buffer, sizeof(reply_len));
                reply_len = ntohl(reply_len);

                /* if 1-byte flag was not sent, fake it (value 0) */
                if ((int)(reply_len + sizeof(reply_len)) == buff->len - 1) {
                    ++rc;
                }
            }
        }

        switch(rc) {
        case 0:
            errno = EHOSTDOWN;
        case -1:
#ifdef _WIN32
            if (WSAGetLastError() == WSAEWOULDBLOCK) {
#else
            if (errno == EAGAIN) {
#endif
                break;
            }
            return -1;
        default:
            buff->curr_offset += rc;
        }
    }
    return buff->curr_offset == buff->len + sizeof(buff->len);
}

void free_buffers(buffer_head_t *list)
{
    while (remove_buffer(list))
        ;
}

void free_completions(zhandle_t *zh,int callCompletion,int reason)
{
    completion_head_t tmp_list;
    struct oarchive *oa;
    struct ReplyHeader h;
    void_completion_t auth_completion = NULL;
    auth_completion_list_t a_list, *a_tmp;

    if (lock_completion_list(&zh->sent_requests) == 0) {
        tmp_list = zh->sent_requests;
        zh->sent_requests.head = 0;
        zh->sent_requests.last = 0;
        unlock_completion_list(&zh->sent_requests);
        while (tmp_list.head) {
            completion_list_t *cptr = tmp_list.head;

            tmp_list.head = cptr->next;
            if (cptr->c.data_result == SYNCHRONOUS_MARKER) {
#ifdef THREADED
                struct sync_completion
                            *sc = (struct sync_completion*)cptr->data;
                sc->rc = reason;
                notify_sync_completion(sc);
                zh->outstanding_sync--;
                destroy_completion_entry(cptr);
#else
                abort_singlethreaded(zh);
#endif
            } else if (callCompletion) {
                // Fake the response
                buffer_list_t *bptr;
                h.xid = cptr->xid;
                h.zxid = -1;
                h.err = reason;
                oa = create_buffer_oarchive();
                serialize_ReplyHeader(oa, "header", &h);
                bptr = calloc(sizeof(*bptr), 1);
                assert(bptr);
                bptr->len = get_buffer_len(oa);
                bptr->buffer = get_buffer(oa);
                close_buffer_oarchive(&oa, 0);
                cptr->buffer = bptr;
                queue_completion(&zh->completions_to_process, cptr, 0);
            }
        }
    }

    zoo_lock_auth(zh);
    a_list.completion = NULL;
    a_list.next = NULL;
    get_auth_completions(&zh->auth_h, &a_list);
    zoo_unlock_auth(zh);

    a_tmp = &a_list;
    // chain call user's completion function
    while (a_tmp->completion != NULL) {
        auth_completion = a_tmp->completion;
        auth_completion(reason, a_tmp->auth_data);
        a_tmp = a_tmp->next;
        if (a_tmp == NULL)
            break;
    }

    free_auth_completion(&a_list);
}

static void cleanup_bufs(zhandle_t *zh,int callCompletion,int rc)
{
    enter_critical(zh);
    free_buffers(&zh->to_send);
    free_buffers(&zh->to_process);
    free_completions(zh,callCompletion,rc);
    leave_critical(zh);
    if (zh->input_buffer && zh->input_buffer != &zh->primer_buffer) {
        free_buffer(zh->input_buffer);
        zh->input_buffer = 0;
    }
}

/* return 1 if zh's state is ZOO_CONNECTED_STATE or ZOO_READONLY_STATE,
 * 0 otherwise */
static int is_connected(zhandle_t* zh)
{
    return (zh->state==ZOO_CONNECTED_STATE || zh->state==ZOO_READONLY_STATE);
}
