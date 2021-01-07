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
static int handle_socket_error_msg(zhandle_t *zh, int line, int rc,
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

static void cleanup(zhandle_t *zh,int rc)
{
    close_zsock(zh->fd);
    if (is_unrecoverable(zh)) {
        LOG_DEBUG(LOGCALLBACK(zh), "Calling a watcher for a ZOO_SESSION_EVENT and the state=%s",
                state2String(zh->state));
        PROCESS_SESSION_EVENT(zh, zh->state);
    } else if (is_connected(zh)) {
        LOG_DEBUG(LOGCALLBACK(zh), "Calling a watcher for a ZOO_SESSION_EVENT and the state=CONNECTING_STATE");
        PROCESS_SESSION_EVENT(zh, ZOO_CONNECTING_STATE);
    }
    cleanup_bufs(zh,1,rc);

    LOG_DEBUG(LOGCALLBACK(zh), "Previous connection=%s delay=%d", zoo_get_current_server(zh), zh->delay);

    if (!is_unrecoverable(zh)) {
        zh->state = 0;
    }
    if (process_async(zh->outstanding_sync)) {
        process_completions(zh);
    }
}

static void handle_error(zhandle_t *zh,int rc)
{
    cleanup(zh, rc);
    // NOTE: If we're at the end of the list of addresses to connect to, then
    // we want to delay the next connection attempt to avoid spinning.
    // Then increment what host we'll connect to since we failed to connect to current
    zh->delay = addrvec_atend(&zh->addrs);
    addrvec_next(&zh->addrs, &zh->addr_cur);
}

static int handle_socket_error_msg(zhandle_t *zh, int line, int rc,
        const char* format, ...)
{
    if(logLevel>=ZOO_LOG_LEVEL_ERROR){
        va_list va;
        char buf[1024];
        va_start(va,format);
        vsnprintf(buf, sizeof(buf)-1,format,va);
        log_message(LOGCALLBACK(zh), ZOO_LOG_LEVEL_ERROR,line,__func__,
            "Socket %s zk retcode=%d, errno=%d(%s): %s",
            zoo_get_current_server(zh),rc,errno,strerror(errno),buf);
        va_end(va);
    }
    handle_error(zh,rc);
    return rc;
}

static void auth_completion_func(int rc, zhandle_t* zh)
{
    void_completion_t auth_completion = NULL;
    auth_completion_list_t a_list;
    auth_completion_list_t *a_tmp;

    if(zh==NULL)
        return;

    zoo_lock_auth(zh);

    if(rc!=0){
        zh->state=ZOO_AUTH_FAILED_STATE;
    }else{
        //change state for all auths
        mark_active_auth(zh);
    }
    a_list.completion = NULL;
    a_list.next = NULL;
    get_auth_completions(&zh->auth_h, &a_list);
    zoo_unlock_auth(zh);
    if (rc) {
        LOG_ERROR(LOGCALLBACK(zh), "Authentication scheme %s failed. Connection closed.",
                   zh->auth_h.auth->scheme);
    }
    else {
        LOG_INFO(LOGCALLBACK(zh), "Authentication scheme %s succeeded", zh->auth_h.auth->scheme);
    }
    a_tmp = &a_list;
    // chain call user's completion function
    while (a_tmp->completion != NULL) {
        auth_completion = a_tmp->completion;
        auth_completion(rc, a_tmp->auth_data);
        a_tmp = a_tmp->next;
        if (a_tmp == NULL)
            break;
    }
    free_auth_completion(&a_list);
}

static int send_info_packet(zhandle_t *zh, auth_info* auth) {
    struct oarchive *oa;
    struct RequestHeader h = {AUTH_XID, ZOO_SETAUTH_OP};
    struct AuthPacket req;
    int rc;
    oa = create_buffer_oarchive();
    rc = serialize_RequestHeader(oa, "header", &h);
    req.type=0;   // ignored by the server
    req.scheme = auth->scheme;
    req.auth = auth->auth;
    rc = rc < 0 ? rc : serialize_AuthPacket(oa, "req", &req);
    /* add this buffer to the head of the send queue */
    rc = rc < 0 ? rc : queue_front_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    return rc;
}

/** send all auths, not just the last one **/
static int send_auth_info(zhandle_t *zh) {
    int rc = 0;
    auth_info *auth = NULL;

    zoo_lock_auth(zh);
    auth = zh->auth_h.auth;
    if (auth == NULL) {
        zoo_unlock_auth(zh);
        return ZOK;
    }
    while (auth != NULL) {
        rc = send_info_packet(zh, auth);
        auth = auth->next;
    }
    zoo_unlock_auth(zh);
    LOG_DEBUG(LOGCALLBACK(zh), "Sending all auth info request to %s", zoo_get_current_server(zh));
    return (rc <0) ? ZMARSHALLINGERROR:ZOK;
}

static int send_last_auth_info(zhandle_t *zh)
{
    int rc = 0;
    auth_info *auth = NULL;

    zoo_lock_auth(zh);
    auth = get_last_auth(&zh->auth_h);
    if(auth==NULL) {
      zoo_unlock_auth(zh);
      return ZOK; // there is nothing to send
    }
    rc = send_info_packet(zh, auth);
    zoo_unlock_auth(zh);
    LOG_DEBUG(LOGCALLBACK(zh), "Sending auth info request to %s",zoo_get_current_server(zh));
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

static void free_key_list(char **list, int count)
{
    int i;

    for(i = 0; i < count; i++) {
        free(list[i]);
    }
    free(list);
}

static int send_set_watches(zhandle_t *zh)
{
    struct oarchive *oa;
    struct RequestHeader h = {SET_WATCHES_XID, ZOO_SETWATCHES_OP};
    struct SetWatches req;
    int rc;

    req.relativeZxid = zh->last_zxid;
    lock_watchers(zh);
    req.dataWatches.data = collect_keys(zh->active_node_watchers, (int*)&req.dataWatches.count);
    req.existWatches.data = collect_keys(zh->active_exist_watchers, (int*)&req.existWatches.count);
    req.childWatches.data = collect_keys(zh->active_child_watchers, (int*)&req.childWatches.count);
    unlock_watchers(zh);

    // return if there are no pending watches
    if (!req.dataWatches.count && !req.existWatches.count &&
        !req.childWatches.count) {
        free_key_list(req.dataWatches.data, req.dataWatches.count);
        free_key_list(req.existWatches.data, req.existWatches.count);
        free_key_list(req.childWatches.data, req.childWatches.count);
        return ZOK;
    }


    oa = create_buffer_oarchive();
    rc = serialize_RequestHeader(oa, "header", &h);
    rc = rc < 0 ? rc : serialize_SetWatches(oa, "req", &req);
    /* add this buffer to the head of the send queue */
    rc = rc < 0 ? rc : queue_front_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);
    free_key_list(req.dataWatches.data, req.dataWatches.count);
    free_key_list(req.existWatches.data, req.existWatches.count);
    free_key_list(req.childWatches.data, req.childWatches.count);
    LOG_DEBUG(LOGCALLBACK(zh), "Sending set watches request to %s",zoo_get_current_server(zh));
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

static int serialize_prime_connect(struct connect_req *req, char* buffer){
    //this should be the order of serialization
    int offset = 0;
    req->protocolVersion = htonl(req->protocolVersion);
    memcpy(buffer + offset, &req->protocolVersion, sizeof(req->protocolVersion));
    offset = offset +  sizeof(req->protocolVersion);

    req->lastZxidSeen = zoo_htonll(req->lastZxidSeen);
    memcpy(buffer + offset, &req->lastZxidSeen, sizeof(req->lastZxidSeen));
    offset = offset +  sizeof(req->lastZxidSeen);

    req->timeOut = htonl(req->timeOut);
    memcpy(buffer + offset, &req->timeOut, sizeof(req->timeOut));
    offset = offset +  sizeof(req->timeOut);

    req->sessionId = zoo_htonll(req->sessionId);
    memcpy(buffer + offset, &req->sessionId, sizeof(req->sessionId));
    offset = offset +  sizeof(req->sessionId);

    req->passwd_len = htonl(req->passwd_len);
    memcpy(buffer + offset, &req->passwd_len, sizeof(req->passwd_len));
    offset = offset +  sizeof(req->passwd_len);

    memcpy(buffer + offset, req->passwd, sizeof(req->passwd));
    offset = offset +  sizeof(req->passwd);

    memcpy(buffer + offset, &req->readOnly, sizeof(req->readOnly));

    return 0;
}

static int deserialize_prime_response(struct prime_struct *resp, char* buffer)
{
     //this should be the order of deserialization
     int offset = 0;
     memcpy(&resp->len, buffer + offset, sizeof(resp->len));
     offset = offset +  sizeof(resp->len);

     resp->len = ntohl(resp->len);
     memcpy(&resp->protocolVersion,
            buffer + offset,
            sizeof(resp->protocolVersion));
     offset = offset +  sizeof(resp->protocolVersion);

     resp->protocolVersion = ntohl(resp->protocolVersion);
     memcpy(&resp->timeOut, buffer + offset, sizeof(resp->timeOut));
     offset = offset +  sizeof(resp->timeOut);

     resp->timeOut = ntohl(resp->timeOut);
     memcpy(&resp->sessionId, buffer + offset, sizeof(resp->sessionId));
     offset = offset +  sizeof(resp->sessionId);

     resp->sessionId = zoo_htonll(resp->sessionId);
     memcpy(&resp->passwd_len, buffer + offset, sizeof(resp->passwd_len));
     offset = offset +  sizeof(resp->passwd_len);

     resp->passwd_len = ntohl(resp->passwd_len);
     memcpy(resp->passwd, buffer + offset, sizeof(resp->passwd));
     offset = offset +  sizeof(resp->passwd);

     memcpy(&resp->readOnly, buffer + offset, sizeof(resp->readOnly));

     return 0;
}

static int prime_connection(zhandle_t *zh)
{
    int rc;
    /*this is the size of buffer to serialize req into*/
    char buffer_req[HANDSHAKE_REQ_SIZE];
    int len = sizeof(buffer_req);
    int hlen = 0;
    struct connect_req req;

    if (zh->state == ZOO_SSL_CONNECTING_STATE) {
       // The SSL connection is yet to happen.
       return ZOK;
    }
    req.protocolVersion = 0;
    req.sessionId = zh->seen_rw_server_before ? zh->client_id.client_id : 0;
    req.passwd_len = sizeof(req.passwd);
    memcpy(req.passwd, zh->client_id.passwd, sizeof(zh->client_id.passwd));
    req.timeOut = zh->recv_timeout;
    req.lastZxidSeen = zh->last_zxid;
    req.readOnly = zh->allow_read_only;
    hlen = htonl(len);
    /* We are running fast and loose here, but this string should fit in the initial buffer! */
    rc=zookeeper_send(zh->fd, &hlen, sizeof(len));
    serialize_prime_connect(&req, buffer_req);
    rc=rc<0 ? rc : zookeeper_send(zh->fd, buffer_req, len);
    if (rc<0) {
        return handle_socket_error_msg(zh, __LINE__, ZCONNECTIONLOSS,
                "failed to send a handshake packet: %s", strerror(errno));
    }
    zh->state = ZOO_ASSOCIATING_STATE;

    zh->input_buffer = &zh->primer_buffer;
    memset(zh->input_buffer->buffer, 0, zh->input_buffer->len);

    /* This seems a bit weird to to set the offset to 4, but we already have a
     * length, so we skip reading the length (and allocating the buffer) by
     * saying that we are already at offset 4 */
    zh->input_buffer->curr_offset = 4;

    return ZOK;
}

static inline int calculate_interval(const struct timeval *start,
        const struct timeval *end)
{
    int interval;
    struct timeval i = *end;
    i.tv_sec -= start->tv_sec;
    i.tv_usec -= start->tv_usec;
    interval = i.tv_sec * 1000 + (i.tv_usec/1000);
    return interval;
}

static struct timeval get_timeval(int interval)
{
    struct timeval tv;
    if (interval < 0) {
        interval = 0;
    }
    tv.tv_sec = interval/1000;
    tv.tv_usec = (interval%1000)*1000;
    return tv;
}

 static int add_void_completion(zhandle_t *zh, int xid, void_completion_t dc,
     const void *data);
 static int add_string_completion(zhandle_t *zh, int xid,
     string_completion_t dc, const void *data);
 static int add_string_stat_completion(zhandle_t *zh, int xid,
     string_stat_completion_t dc, const void *data);


 int send_ping(zhandle_t* zh)
 {
    int rc;
    struct oarchive *oa = create_buffer_oarchive();
    struct RequestHeader h = {PING_XID, ZOO_PING_OP};

    rc = serialize_RequestHeader(oa, "header", &h);
    enter_critical(zh);
    get_system_time(&zh->last_ping);
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);
    close_buffer_oarchive(&oa, 0);
    return rc<0 ? rc : adaptor_send_queue(zh, 0);
}

/* upper bound of a timeout for seeking for r/w server when in read-only mode */
const int MAX_RW_TIMEOUT = 60000;
const int MIN_RW_TIMEOUT = 200;

static int ping_rw_server(zhandle_t* zh)
{
    char buf[10];
    zsock_t fd;
    int rc;
    sendsize_t ssize;
    int sock_flags;

    addrvec_peek(&zh->addrs, &zh->addr_rw_server);

#ifdef SOCK_CLOEXEC_ENABLED
    sock_flags = SOCK_STREAM | SOCK_CLOEXEC;
#else
    sock_flags = SOCK_STREAM;
#endif
    fd.sock = socket(zh->addr_rw_server.ss_family, sock_flags, 0);
    if (fd.sock < 0) {
        return 0;
    }

    zookeeper_set_sock_nodelay(zh, fd.sock);
    zookeeper_set_sock_timeout(zh, fd.sock, 1);

    rc = zookeeper_connect(zh, &zh->addr_rw_server, fd.sock);
    if (rc < 0) {
        return 0;
    }

#ifdef HAVE_OPENSSL_H
    fd.ssl_sock = NULL;
    fd.ssl_ctx = NULL;

    if (zh->fd->cert != NULL) {
        fd.cert = zh->fd->cert;
        rc = init_ssl_for_socket(&fd, zh, 0);
        if (rc != ZOK) {
            rc = 0;
            goto out;
        }
    }
#endif

    ssize = zookeeper_send(&fd, "isro", 4);
    if (ssize < 0) {
        rc = 0;
        goto out;
    }

    memset(buf, 0, sizeof(buf));
    rc = zookeeper_recv(&fd, buf, sizeof(buf), 0);
    if (rc < 0) {
        rc = 0;
        goto out;
    }

    rc = strcmp("rw", buf) == 0;

out:
    close_zsock(&fd);
    addr_rw_server = rc ? &zh->addr_rw_server : 0;
    return rc;
}

#if !defined(WIN32) && !defined(min)
static inline int min(int a, int b)
{
    return a < b ? a : b;
}
#endif

static void zookeeper_set_sock_noblock(zhandle_t *zh, socket_t sock)
{
#ifdef _WIN32
    ULONG nonblocking_flag = 1;

    ioctlsocket(sock, FIONBIO, &nonblocking_flag);
#else
    fcntl(sock, F_SETFL, O_NONBLOCK|fcntl(sock, F_GETFL, 0));
#endif
}

static void zookeeper_set_sock_timeout(zhandle_t *zh, socket_t s, int timeout)
{
    struct timeval tv;

    tv.tv_sec = timeout;
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(struct timeval));
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(struct timeval));
}

static void zookeeper_set_sock_nodelay(zhandle_t *zh, socket_t sock)
{
#ifdef _WIN32
    char enable_tcp_nodelay = 1;
#else
    int enable_tcp_nodelay = 1;
#endif
    int rc;

    rc = setsockopt(sock,
                    IPPROTO_TCP,
                    TCP_NODELAY,
                    &enable_tcp_nodelay,
                    sizeof(enable_tcp_nodelay));

    if (rc) {
        LOG_WARN(LOGCALLBACK(zh),
                 "Unable to set TCP_NODELAY, latency may be effected");
    }
}

static socket_t zookeeper_connect(zhandle_t *zh,
                                  struct sockaddr_storage *addr,
                                  socket_t fd)
{
    int rc;
    int addr_len;

#if defined(AF_INET6)
    if (addr->ss_family == AF_INET6) {
        addr_len = sizeof(struct sockaddr_in6);
    } else {
        addr_len = sizeof(struct sockaddr_in);
    }
#else
    addr_len = sizeof(struct sockaddr_in);
#endif

    LOG_DEBUG(LOGCALLBACK(zh), "[zk] connect()\n");
    rc = connect(fd, (struct sockaddr *)addr, addr_len);

#ifdef _WIN32
    errno = GetLastError();

#ifndef EWOULDBLOCK
#define EWOULDBLOCK WSAEWOULDBLOCK
#endif

#ifndef EINPROGRESS
#define EINPROGRESS WSAEINPROGRESS
#endif

#if _MSC_VER >= 1600
    switch(errno) {
    case WSAEWOULDBLOCK:
        errno = EWOULDBLOCK;
        break;
    case WSAEINPROGRESS:
        errno = EINPROGRESS;
        break;
    }
#endif
#endif

    return rc;
}

int zookeeper_interest(zhandle_t *zh, socket_t *fd, int *interest,
     struct timeval *tv)
{
    int sock_flags;
    int rc = 0;
    struct timeval now;

#ifdef SOCK_CLOEXEC_ENABLED
    sock_flags = SOCK_STREAM | SOCK_CLOEXEC;
#else
    sock_flags = SOCK_STREAM;
#endif

    if(zh==0 || fd==0 ||interest==0 || tv==0)
        return ZBADARGUMENTS;
    if (is_unrecoverable(zh))
        return ZINVALIDSTATE;
    get_system_time(&now);
    if(zh->next_deadline.tv_sec!=0 || zh->next_deadline.tv_usec!=0){
        int time_left = calculate_interval(&zh->next_deadline, &now);
        int max_exceed = zh->recv_timeout / 10 > 200 ? 200 :
                         (zh->recv_timeout / 10);
        if (time_left > max_exceed)
            LOG_WARN(LOGCALLBACK(zh), "Exceeded deadline by %dms", time_left);
    }
    api_prolog(zh);

    rc = update_addrs(zh, &now);
    if (rc != ZOK) {
        return api_epilog(zh, rc);
    }

    *fd = zh->fd->sock;
    *interest = 0;
    tv->tv_sec = 0;
    tv->tv_usec = 0;

    if (*fd == -1) {
        /*
         * If we previously failed to connect to server pool (zh->delay == 1)
         * then we need delay our connection on this iteration 1/60 of the
         * recv timeout before trying again so we don't spin.
         *
         * We always clear the delay setting. If we fail again, we'll set delay
         * again and on the next iteration we'll do the same.
         *
         * We will also delay if the disable_reconnection_attempt is set.
         */
        if (zh->delay == 1 || zh->disable_reconnection_attempt == 1) {
            *tv = get_timeval(zh->recv_timeout/60);
            zh->delay = 0;

            LOG_WARN(LOGCALLBACK(zh), "Delaying connection after exhaustively trying all servers [%s]",
                     zh->hostname);
        } else {
            if (addr_rw_server) {
                zh->addr_cur = *addr_rw_server;
                addr_rw_server = 0;
            } else {
                // No need to delay -- grab the next server and attempt connection
                zoo_cycle_next_server(zh);
            }
            zh->fd->sock  = socket(zh->addr_cur.ss_family, sock_flags, 0);
            if (zh->fd->sock < 0) {
              rc = handle_socket_error_msg(zh,
                                           __LINE__,
                                           ZSYSTEMERROR,
                                           "socket() call failed");
              return api_epilog(zh, rc);
            }

            zookeeper_set_sock_nodelay(zh, zh->fd->sock);
            zookeeper_set_sock_noblock(zh, zh->fd->sock);

            rc = zookeeper_connect(zh, &zh->addr_cur, zh->fd->sock);

            if (rc == -1) {
                /* we are handling the non-blocking connect according to
                 * the description in section 16.3 "Non-blocking connect"
                 * in UNIX Network Programming vol 1, 3rd edition */
                if (errno == EWOULDBLOCK || errno == EINPROGRESS) {
                    // For SSL, we first go to ZOO_SSL_CONNECTING_STATE
                    if (zh->fd->cert != NULL)
                        zh->state = ZOO_SSL_CONNECTING_STATE;
                    else
                        zh->state = ZOO_CONNECTING_STATE;
                } else {
                    rc = handle_socket_error_msg(zh,
                                                 __LINE__,
                                                 ZCONNECTIONLOSS,
                                                 "connect() call failed");
                    return api_epilog(zh, rc);
                }
            } else {
#ifdef HAVE_OPENSSL_H
                if (zh->fd->cert != NULL) {
                    // We do SSL_connect() here
                    if (init_ssl_for_handler(zh) != ZOK) {
                        return ZSSLCONNECTIONERROR;
                    }
                }
#endif
                rc = prime_connection(zh);
                if (rc != 0) {
                    return api_epilog(zh,rc);
                }

                LOG_INFO(LOGCALLBACK(zh),
                         "Initiated connection to server %s",
                         format_endpoint_info(&zh->addr_cur));
            }
            *tv = get_timeval(zh->recv_timeout/3);
        }
        *fd = zh->fd->sock;
        zh->last_recv = now;
        zh->last_send = now;
        zh->last_ping = now;
        zh->last_ping_rw = now;
        zh->ping_rw_timeout = MIN_RW_TIMEOUT;
    }

    if (zh->fd->sock != -1) {
        int idle_recv = calculate_interval(&zh->last_recv, &now);
        int idle_send = calculate_interval(&zh->last_send, &now);
        int recv_to = zh->recv_timeout*2/3 - idle_recv;
        int send_to = zh->recv_timeout/3;
        // have we exceeded the receive timeout threshold?
        if (recv_to <= 0 && zh->state != ZOO_SSL_CONNECTING_STATE) {
            // We gotta cut our losses and connect to someone else
#ifdef _WIN32
            errno = WSAETIMEDOUT;
#else
            errno = ETIMEDOUT;
#endif
            *interest=0;
            *tv = get_timeval(0);
            return api_epilog(zh,handle_socket_error_msg(zh,
                    __LINE__,ZOPERATIONTIMEOUT,
                    "connection to %s timed out (exceeded timeout by %dms)",
                    format_endpoint_info(&zh->addr_cur),
                    -recv_to));

        }

        // We only allow 1/3 of our timeout time to expire before sending
        // a PING
        if (is_connected(zh)) {
            send_to = zh->recv_timeout/3 - idle_send;
            if (send_to <= 0) {
                if (zh->sent_requests.head == 0) {
                    rc = send_ping(zh);
                    if (rc < 0) {
                        LOG_ERROR(LOGCALLBACK(zh), "failed to send PING request (zk retcode=%d)",rc);
                        return api_epilog(zh,rc);
                    }
                }
                send_to = zh->recv_timeout/3;
            }
        }

        // If we are in read-only mode, seek for read/write server
        if (zh->state == ZOO_READONLY_STATE) {
            int idle_ping_rw = calculate_interval(&zh->last_ping_rw, &now);
            if (idle_ping_rw >= zh->ping_rw_timeout) {
                zh->last_ping_rw = now;
                idle_ping_rw = 0;
                zh->ping_rw_timeout = min(zh->ping_rw_timeout * 2,
                                          MAX_RW_TIMEOUT);
                if (ping_rw_server(zh)) {
                    struct sockaddr_storage addr;
                    addrvec_peek(&zh->addrs, &addr);
                    zh->ping_rw_timeout = MIN_RW_TIMEOUT;
                    LOG_INFO(LOGCALLBACK(zh),
                             "r/w server found at %s",
                             format_endpoint_info(&addr));
                    cleanup(zh, ZOK);
                } else {
                    addrvec_next(&zh->addrs, NULL);
                }
            }
            send_to = min(send_to, zh->ping_rw_timeout - idle_ping_rw);
        }

        // choose the lesser value as the timeout
        *tv = get_timeval(min(recv_to, send_to));

        zh->next_deadline.tv_sec = now.tv_sec + tv->tv_sec;
        zh->next_deadline.tv_usec = now.tv_usec + tv->tv_usec;
        if (zh->next_deadline.tv_usec > 1000000) {
            zh->next_deadline.tv_sec += zh->next_deadline.tv_usec / 1000000;
            zh->next_deadline.tv_usec = zh->next_deadline.tv_usec % 1000000;
        }
        *interest = ZOOKEEPER_READ;
        /* we are interested in a write if we are connected and have something
         * to send, or we are waiting for a connect to finish. */
        if ((zh->to_send.head && (is_connected(zh) || is_sasl_auth_in_progress(zh)))
            || zh->state == ZOO_CONNECTING_STATE
            || zh->state == ZOO_SSL_CONNECTING_STATE) {
            *interest |= ZOOKEEPER_WRITE;
        }
    }
    return api_epilog(zh,ZOK);
}

#ifdef HAVE_OPENSSL_H

/*
 * use this function, if you want to init SSL for the socket currently registered in the zookeeper handler
 */
static int init_ssl_for_handler(zhandle_t *zh)
{
    int rc = init_ssl_for_socket(zh->fd, zh, 1);
    if (rc == ZOK) {
        // (SUCCESS) Now mark the ZOO_CONNECTING_STATE so that
        // prime_connection() happen.
        // prime_connection() only happens in ZOO_CONNECTING_STATE
        zh->state = ZOO_CONNECTING_STATE;
    }
    return rc;
}

/*
 * use this function, if you want to init SSL for a socket, pointing to a different server address than the one
 * currently registered in the zookeeper handler (e.g. ping other servers when you are connected to a read-only one)
 */
static int init_ssl_for_socket(zsock_t *fd, zhandle_t *zh, int fail_on_error) {

    SSL_CTX **ctx;

    if (!fd->ssl_sock) {
        const SSL_METHOD *method;

#if OPENSSL_VERSION_NUMBER < 0x10100000L
        OpenSSL_add_all_algorithms();
        ERR_load_BIO_strings();
        ERR_load_crypto_strings();
        SSL_load_error_strings();
        SSL_library_init();
        method = SSLv23_client_method();
#else
        OPENSSL_init_ssl(OPENSSL_INIT_LOAD_SSL_STRINGS | OPENSSL_INIT_LOAD_CRYPTO_STRINGS, NULL);
        method = TLS_client_method();
#endif
        if (FIPS_mode() == 0) {
            LOG_INFO(LOGCALLBACK(zh), "FIPS mode is OFF ");
        } else {
            LOG_INFO(LOGCALLBACK(zh), "FIPS mode is ON ");
        }
        fd->ssl_ctx = SSL_CTX_new(method);
        ctx = &fd->ssl_ctx;

        SSL_CTX_set_verify(*ctx, SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT, 0);
        /*SERVER CA FILE*/
        if (SSL_CTX_load_verify_locations(*ctx, fd->cert->ca, 0) != 1) {
            SSL_CTX_free(*ctx);
            LOG_ERROR(LOGCALLBACK(zh), "Failed to load CA file %s", fd->cert->ca);
            errno = EINVAL;
            return ZBADARGUMENTS;
        }
        if (SSL_CTX_set_default_verify_paths(*ctx) != 1) {
            SSL_CTX_free(*ctx);
            LOG_ERROR(LOGCALLBACK(zh), "Call to SSL_CTX_set_default_verify_paths failed");
            errno = EINVAL;
            return ZBADARGUMENTS;
        }
        /*CLIENT CA FILE (With Certificate Chain)*/
        if (SSL_CTX_use_certificate_chain_file(*ctx, fd->cert->cert) != 1) {
            SSL_CTX_free(*ctx);
            LOG_ERROR(LOGCALLBACK(zh), "Failed to load client certificate chain from %s", fd->cert->cert);
            errno = EINVAL;
            return ZBADARGUMENTS;
        }
        /*CLIENT PRIVATE KEY*/
        SSL_CTX_set_default_passwd_cb_userdata(*ctx, fd->cert->passwd);
        if (SSL_CTX_use_PrivateKey_file(*ctx, fd->cert->key, SSL_FILETYPE_PEM) != 1) {
            SSL_CTX_free(*ctx);
            LOG_ERROR(LOGCALLBACK(zh), "Failed to load client private key from %s", fd->cert->key);
            errno = EINVAL;
            return ZBADARGUMENTS;
        }
        /*CHECK*/
        if (SSL_CTX_check_private_key(*ctx) != 1) {
            SSL_CTX_free(*ctx);
            LOG_ERROR(LOGCALLBACK(zh), "SSL_CTX_check_private_key failed");
            errno = EINVAL;
            return ZBADARGUMENTS;
        }
        /*MULTIPLE HANDSHAKE*/
        SSL_CTX_set_mode(*ctx, SSL_MODE_AUTO_RETRY);

        fd->ssl_sock = SSL_new(*ctx);
        if (fd->ssl_sock == NULL) {
            if (fail_on_error) {
                return handle_socket_error_msg(zh,__LINE__,ZSSLCONNECTIONERROR, "error creating ssl context");
            } else {
                LOG_ERROR(LOGCALLBACK(zh), "error creating ssl context");
                return ZSSLCONNECTIONERROR;
            }

        }
        SSL_set_fd(fd->ssl_sock, fd->sock);
    }
    while(1) {
        int rc;
        int sock = fd->sock;
        struct timeval tv;
        fd_set s_rfds, s_wfds;
        tv.tv_sec = 1;
        tv.tv_usec = 0;
        FD_ZERO(&s_rfds);
        FD_ZERO(&s_wfds);
        rc = SSL_connect(fd->ssl_sock);
        if (rc == 1) {
            return ZOK;
        } else {
            rc = SSL_get_error(fd->ssl_sock, rc);
            if (rc == SSL_ERROR_WANT_READ) {
                FD_SET(sock, &s_rfds);
                FD_CLR(sock, &s_wfds);
            } else if (rc == SSL_ERROR_WANT_WRITE) {
                FD_SET(sock, &s_wfds);
                FD_CLR(sock, &s_rfds);
            } else {
                if (fail_on_error) {
                    return handle_socket_error_msg(zh,__LINE__,ZSSLCONNECTIONERROR, "error in ssl connect");
                } else {
                    LOG_ERROR(LOGCALLBACK(zh), "error in ssl connect");
                    return ZSSLCONNECTIONERROR;
                }
            }
            rc = select(sock + 1, &s_rfds, &s_wfds, NULL, &tv);
            if (rc == -1) {
                if (fail_on_error) {
                    return handle_socket_error_msg(zh,__LINE__,ZSSLCONNECTIONERROR, "error in ssl connect (after select)");
                } else {
                    LOG_ERROR(LOGCALLBACK(zh), "error in ssl connect (after select)");
                    return ZSSLCONNECTIONERROR;
                }
            }
        }
    }
}


#endif

/*
 * the "bottom half" of the session establishment procedure, executed
 * either after receiving the "prime response," or after SASL
 * authentication is complete
 */
static void finalize_session_establishment(zhandle_t *zh) {
    zh->state = zh->primer_storage.readOnly ?
        ZOO_READONLY_STATE : ZOO_CONNECTED_STATE;
    zh->reconfig = 0;
    LOG_INFO(LOGCALLBACK(zh),
             "session establishment complete on server %s, sessionId=%#llx, negotiated timeout=%d %s",
             format_endpoint_info(&zh->addr_cur),
             zh->client_id.client_id, zh->recv_timeout,
             zh->primer_storage.readOnly ? "(READ-ONLY mode)" : "");
    /* we want the auth to be sent for, but since both call push to front
       we need to call send_watch_set first */
    send_set_watches(zh);
    /* send the authentication packet now */
    send_auth_info(zh);
    LOG_DEBUG(LOGCALLBACK(zh), "Calling a watcher for a ZOO_SESSION_EVENT and the state=ZOO_CONNECTED_STATE");
    zh->input_buffer = 0; // just in case the watcher calls zookeeper_process() again
    PROCESS_SESSION_EVENT(zh, zh->state);

    if (has_sasl_client(zh)) {
        /* some packets might have been delayed during SASL negotiaton. */
        adaptor_send_queue(zh, 0);
    }
}

#ifdef HAVE_CYRUS_SASL_H

/*
 * queue an encoded SASL request to ZooKeeper.  The packet is added to
 * the front of the queue.
 *
 * \param zh the ZooKeeper handle
 * \param client_data the encoded SASL data, ready to send
 * \param client_data_len the length of \c client_data
 * \return ZOK on success, or ZMARSHALLINGERROR if something went wrong
 */
int queue_sasl_request(zhandle_t *zh, const char *client_data, int client_data_len)
{
    struct oarchive *oa;
    int rc;

    /* Java client use normal xid, too. */
    struct RequestHeader h = { get_xid(), ZOO_SASL_OP };
    struct GetSASLRequest req = { { client_data_len, client_data_len>0 ? (char *) client_data : "" } };

    oa = create_buffer_oarchive();
    rc = serialize_RequestHeader(oa, "header", &h);
    rc = rc < 0 ? rc : serialize_GetSASLRequest(oa, "req", &req);
    rc = rc < 0 ? rc : queue_front_buffer_bytes(&zh->to_send, get_buffer(oa),
         get_buffer_len(oa));
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh),
        "SASL: Queued request len=%d rc=%d", client_data_len, rc);

    return (rc < 0) ? ZMARSHALLINGERROR : ZOK;
}

/*
 * decode an expected SASL response and perform the corresponding
 * authentication step
 */
static int process_sasl_response(zhandle_t *zh, char *buffer, int len)
{
    struct iarchive *ia = create_buffer_iarchive(buffer, len);
    struct ReplyHeader hdr;
    struct SetSASLResponse res;
    int rc;

    rc = ia ? ZOK : ZSYSTEMERROR;
    rc = rc < 0 ? rc : deserialize_ReplyHeader(ia, "hdr", &hdr);
    rc = rc < 0 ? rc : deserialize_SetSASLResponse(ia, "reply", &res);
    rc = rc < 0 ? rc : zoo_sasl_client_step(zh, res.token.buff, res.token.len);
    deallocate_SetSASLResponse(&res);
    if (ia) {
        close_buffer_iarchive(&ia);
    }

    LOG_DEBUG(LOGCALLBACK(zh),
        "SASL: Processed response len=%d rc=%d", len, rc);

    return rc;
}

#endif /* HAVE_CYRUS_SASL_H */

static int check_events(zhandle_t *zh, int events)
{
    if (zh->fd->sock == -1)
        return ZINVALIDSTATE;

#ifdef HAVE_OPENSSL_H
    if ((events&ZOOKEEPER_WRITE) && (zh->state == ZOO_SSL_CONNECTING_STATE) && zh->fd->cert != NULL) {
        int rc, error;
        socklen_t len = sizeof(error);
        rc = getsockopt(zh->fd->sock, SOL_SOCKET, SO_ERROR, &error, &len);
        /* the description in section 16.4 "Non-blocking connect"
         * in UNIX Network Programming vol 1, 3rd edition, points out
         * that sometimes the error is in errno and sometimes in error */
        if (rc < 0 || error) {
            if (rc == 0)
                errno = error;
            return handle_socket_error_msg(zh, __LINE__,ZCONNECTIONLOSS,
                "server refused to accept the client");
        }
        // We do SSL_connect() here
        if (init_ssl_for_handler(zh) != ZOK) {
            return ZSSLCONNECTIONERROR;
        }
    }
#endif

    if ((events&ZOOKEEPER_WRITE)&&(zh->state == ZOO_CONNECTING_STATE)) {
        int rc, error;
        socklen_t len = sizeof(error);
        rc = getsockopt(zh->fd->sock, SOL_SOCKET, SO_ERROR, &error, &len);
        /* the description in section 16.4 "Non-blocking connect"
         * in UNIX Network Programming vol 1, 3rd edition, points out
         * that sometimes the error is in errno and sometimes in error */
        if (rc < 0 || error) {
            if (rc == 0)
                errno = error;
            return handle_socket_error_msg(zh, __LINE__,ZCONNECTIONLOSS,
                "server refused to accept the client");
        }

        if((rc=prime_connection(zh))!=0)
            return rc;

        LOG_INFO(LOGCALLBACK(zh), "initiated connection to server %s", format_endpoint_info(&zh->addr_cur));
        return ZOK;
    }

    if (zh->to_send.head && (events&ZOOKEEPER_WRITE)) {
        /* make the flush call non-blocking by specifying a 0 timeout */
        int rc=flush_send_queue(zh,0);
        if (rc < 0)
            return handle_socket_error_msg(zh,__LINE__,ZCONNECTIONLOSS,
                "failed while flushing send queue");
    }
    if (events&ZOOKEEPER_READ) {
        int rc;
        if (zh->input_buffer == 0) {
            zh->input_buffer = allocate_buffer(0,0);
        }

        rc = recv_buffer(zh, zh->input_buffer);
        if (rc < 0) {
            return handle_socket_error_msg(zh, __LINE__,ZCONNECTIONLOSS,
                "failed while receiving a server response");
        }
        if (rc > 0) {
            get_system_time(&zh->last_recv);
            if (zh->input_buffer != &zh->primer_buffer) {
                if (is_connected(zh) || !is_sasl_auth_in_progress(zh)) {
                    queue_buffer(&zh->to_process, zh->input_buffer, 0);
#ifdef HAVE_CYRUS_SASL_H
                } else {
                    rc = process_sasl_response(zh, zh->input_buffer->buffer, zh->input_buffer->curr_offset);
                    free_buffer(zh->input_buffer);
                    if (rc < 0) {
                        zoo_sasl_mark_failed(zh);
                        return rc;
                    } else if (zh->sasl_client->state == ZOO_SASL_COMPLETE) {
                        /*
                         * SASL authentication just completed; send
                         * watches, auth. info, etc. now.
                         */
                        finalize_session_establishment(zh);
                    }
#endif /* HAVE_CYRUS_SASL_H */
                }
            } else  {
                int64_t oldid, newid;
                //deserialize
                deserialize_prime_response(&zh->primer_storage, zh->primer_buffer.buffer);
                /* We are processing the primer_buffer, so we need to finish
                 * the connection handshake */
                oldid = zh->seen_rw_server_before ? zh->client_id.client_id : 0;
                zh->seen_rw_server_before |= !zh->primer_storage.readOnly;
                newid = zh->primer_storage.sessionId;
                if (oldid != 0 && oldid != newid) {
                    zh->state = ZOO_EXPIRED_SESSION_STATE;
                    errno = ESTALE;
                    return handle_socket_error_msg(zh,__LINE__,ZSESSIONEXPIRED,
                            "sessionId=%#llx has expired.",oldid);
                } else {
                    zh->recv_timeout = zh->primer_storage.timeOut;
                    zh->client_id.client_id = newid;

                    memcpy(zh->client_id.passwd, &zh->primer_storage.passwd,
                           sizeof(zh->client_id.passwd));

#ifdef HAVE_CYRUS_SASL_H
                    if (zh->sasl_client) {
                        /*
                         * Start a SASL authentication session.
                         * Watches, auth. info, etc. will be sent
                         * after it completes.
                         */
                        rc = zoo_sasl_connect(zh);
                        rc = rc < 0 ? rc : zoo_sasl_client_start(zh);
                        if (rc < 0) {
                            zoo_sasl_mark_failed(zh);
                            return rc;
                        }
                    } else {
                        /* Can send watches, auth. info, etc. immediately. */
                        finalize_session_establishment(zh);
                    }
#else /* HAVE_CYRUS_SASL_H */
                    /* Can send watches, auth. info, etc. immediately. */
                    finalize_session_establishment(zh);
#endif /* HAVE_CYRUS_SASL_H */
                }
            }
            zh->input_buffer = 0;
        } else {
            // zookeeper_process was called but there was nothing to read
            // from the socket
            return ZNOTHING;
        }
    }
    return ZOK;
}

void api_prolog(zhandle_t* zh)
{
    inc_ref_counter(zh,1);
}

int api_epilog(zhandle_t *zh,int rc)
{
    if(inc_ref_counter(zh,-1)==0 && zh->close_requested!=0)
        zookeeper_close(zh);
    return rc;
}

//#ifdef THREADED
// IO thread queues session events to be processed by the completion thread
static int queue_session_event(zhandle_t *zh, int state)
{
    int rc;
    struct WatcherEvent evt = { ZOO_SESSION_EVENT, state, "" };
    struct ReplyHeader hdr = { WATCHER_EVENT_XID, 0, 0 };
    struct oarchive *oa;
    completion_list_t *cptr;

    if ((oa=create_buffer_oarchive())==NULL) {
        LOG_ERROR(LOGCALLBACK(zh), "out of memory");
        goto error;
    }
    rc = serialize_ReplyHeader(oa, "hdr", &hdr);
    rc = rc<0?rc: serialize_WatcherEvent(oa, "event", &evt);
    if(rc<0){
        close_buffer_oarchive(&oa, 1);
        goto error;
    }
    cptr = create_completion_entry(zh, WATCHER_EVENT_XID,-1,0,0,0,0);
    cptr->buffer = allocate_buffer(get_buffer(oa), get_buffer_len(oa));
    cptr->buffer->curr_offset = get_buffer_len(oa);
    if (!cptr->buffer) {
        free(cptr);
        close_buffer_oarchive(&oa, 1);
        goto error;
    }
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);
    lock_watchers(zh);
    cptr->c.watcher_result = collectWatchers(zh, ZOO_SESSION_EVENT, "");
    unlock_watchers(zh);
    queue_completion(&zh->completions_to_process, cptr, 0);
    if (process_async(zh->outstanding_sync)) {
        process_completions(zh);
    }
    return ZOK;
error:
    errno=ENOMEM;
    return ZSYSTEMERROR;
}
//#endif

completion_list_t *dequeue_completion(completion_head_t *list)
{
    completion_list_t *cptr;
    lock_completion_list(list);
    cptr = list->head;
    if (cptr) {
        list->head = cptr->next;
        if (!list->head) {
            assert(list->last == cptr);
            list->last = 0;
        }
    }
    unlock_completion_list(list);
    return cptr;
}

// cleanup completion list of a failed multi request
static void cleanup_failed_multi(zhandle_t *zh, int xid, int rc, completion_list_t *cptr) {
    completion_list_t *entry;
    completion_head_t *clist = &cptr->c.clist;
    while ((entry = dequeue_completion(clist)) != NULL) {
        // Fake failed response for all sub-requests
        deserialize_response(zh, entry->c.type, xid, 1, rc, entry, NULL);
        destroy_completion_entry(entry);
    }
}

static int deserialize_multi(zhandle_t *zh, int xid, completion_list_t *cptr, struct iarchive *ia)
{
    int rc = 0;
    completion_head_t *clist = &cptr->c.clist;
    struct MultiHeader mhdr = {0, 0, 0};
    assert(clist);
    deserialize_MultiHeader(ia, "multiheader", &mhdr);
    while (!mhdr.done) {
        completion_list_t *entry = dequeue_completion(clist);
        assert(entry);

        if (mhdr.type == -1) {
            struct ErrorResponse er;
            deserialize_ErrorResponse(ia, "error", &er);
            mhdr.err = er.err ;
            if (rc == 0 && er.err != 0 && er.err != ZRUNTIMEINCONSISTENCY) {
                rc = er.err;
            }
        }

        deserialize_response(zh, entry->c.type, xid, mhdr.type == -1, mhdr.err, entry, ia);
        deserialize_MultiHeader(ia, "multiheader", &mhdr);
        //While deserializing the response we must destroy completion entry for each operation in
        //the zoo_multi transaction. Otherwise this results in memory leak when client invokes zoo_multi
        //operation.
        destroy_completion_entry(entry);
    }

    return rc;
}

static void deserialize_response(zhandle_t *zh, int type, int xid, int failed, int rc, completion_list_t *cptr, struct iarchive *ia)
{
    switch (type) {
    case COMPLETION_DATA:
        LOG_DEBUG(LOGCALLBACK(zh), "Calling COMPLETION_DATA for xid=%#x failed=%d rc=%d",
                    cptr->xid, failed, rc);
        if (failed) {
            cptr->c.data_result(rc, 0, 0, 0, cptr->data);
        } else {
            struct GetDataResponse res;
            deserialize_GetDataResponse(ia, "reply", &res);
            cptr->c.data_result(rc, res.data.buff, res.data.len,
                    &res.stat, cptr->data);
            deallocate_GetDataResponse(&res);
        }
        break;
    case COMPLETION_STAT:
        LOG_DEBUG(LOGCALLBACK(zh), "Calling COMPLETION_STAT for xid=%#x failed=%d rc=%d",
                    cptr->xid, failed, rc);
        if (failed) {
            cptr->c.stat_result(rc, 0, cptr->data);
        } else {
            struct SetDataResponse res;
            deserialize_SetDataResponse(ia, "reply", &res);
            cptr->c.stat_result(rc, &res.stat, cptr->data);
            deallocate_SetDataResponse(&res);
        }
        break;
    case COMPLETION_STRINGLIST:
        LOG_DEBUG(LOGCALLBACK(zh), "Calling COMPLETION_STRINGLIST for xid=%#x failed=%d rc=%d",
                    cptr->xid, failed, rc);
        if (failed) {
            cptr->c.strings_result(rc, 0, cptr->data);
        } else {
            struct GetChildrenResponse res;
            deserialize_GetChildrenResponse(ia, "reply", &res);
            cptr->c.strings_result(rc, &res.children, cptr->data);
            deallocate_GetChildrenResponse(&res);
        }
        break;
    case COMPLETION_STRINGLIST_STAT:
        LOG_DEBUG(LOGCALLBACK(zh), "Calling COMPLETION_STRINGLIST_STAT for xid=%#x failed=%d rc=%d",
                    cptr->xid, failed, rc);
        if (failed) {
            cptr->c.strings_stat_result(rc, 0, 0, cptr->data);
        } else {
            struct GetChildren2Response res;
            deserialize_GetChildren2Response(ia, "reply", &res);
            cptr->c.strings_stat_result(rc, &res.children, &res.stat, cptr->data);
            deallocate_GetChildren2Response(&res);
        }
        break;
    case COMPLETION_STRING:
        LOG_DEBUG(LOGCALLBACK(zh), "Calling COMPLETION_STRING for xid=%#x failed=%d, rc=%d",
                    cptr->xid, failed, rc);
        if (failed) {
            cptr->c.string_result(rc, 0, cptr->data);
        } else {
            struct CreateResponse res;
            const char *client_path;
            memset(&res, 0, sizeof(res));
            deserialize_CreateResponse(ia, "reply", &res);
            client_path = sub_string(zh, res.path);
            cptr->c.string_result(rc, client_path, cptr->data);
            free_duplicate_path(client_path, res.path);
            deallocate_CreateResponse(&res);
        }
        break;
    case COMPLETION_STRING_STAT:
        LOG_DEBUG(LOGCALLBACK(zh), "Calling COMPLETION_STRING_STAT for xid=%#x failed=%d, rc=%d",
                    cptr->xid, failed, rc);
        if (failed) {
            cptr->c.string_stat_result(rc, 0, 0, cptr->data);
        } else {
            struct Create2Response res;
            const char *client_path;
            deserialize_Create2Response(ia, "reply", &res);
            client_path = sub_string(zh, res.path);
            cptr->c.string_stat_result(rc, client_path, &res.stat, cptr->data);
            free_duplicate_path(client_path, res.path);
            deallocate_Create2Response(&res);
        }
        break;
    case COMPLETION_ACLLIST:
        LOG_DEBUG(LOGCALLBACK(zh), "Calling COMPLETION_ACLLIST for xid=%#x failed=%d rc=%d",
                    cptr->xid, failed, rc);
        if (failed) {
            cptr->c.acl_result(rc, 0, 0, cptr->data);
        } else {
            struct GetACLResponse res;
            deserialize_GetACLResponse(ia, "reply", &res);
            cptr->c.acl_result(rc, &res.acl, &res.stat, cptr->data);
            deallocate_GetACLResponse(&res);
        }
        break;
    case COMPLETION_VOID:
        LOG_DEBUG(LOGCALLBACK(zh), "Calling COMPLETION_VOID for xid=%#x failed=%d rc=%d",
                    cptr->xid, failed, rc);
        assert(cptr->c.void_result);
        cptr->c.void_result(rc, cptr->data);
        break;
    case COMPLETION_MULTI:
        LOG_DEBUG(LOGCALLBACK(zh), "Calling COMPLETION_MULTI for xid=%#x failed=%d rc=%d",
                    cptr->xid, failed, rc);
        assert(cptr->c.void_result);
        if (failed) {
            cleanup_failed_multi(zh, xid, rc, cptr);
        } else {
            rc = deserialize_multi(zh, xid, cptr, ia);
        }
        cptr->c.void_result(rc, cptr->data);
        break;
    default:
        LOG_DEBUG(LOGCALLBACK(zh), "Unsupported completion type=%d", cptr->c.type);
    }
}


/* handles async completion (both single- and multithreaded) */
void process_completions(zhandle_t *zh)
{
    completion_list_t *cptr;
    while ((cptr = dequeue_completion(&zh->completions_to_process)) != 0) {
        struct ReplyHeader hdr;
        buffer_list_t *bptr = cptr->buffer;
        struct iarchive *ia = create_buffer_iarchive(bptr->buffer,
                bptr->len);
        deserialize_ReplyHeader(ia, "hdr", &hdr);

        if (hdr.xid == WATCHER_EVENT_XID) {
            int type, state;
            struct WatcherEvent evt;
            deserialize_WatcherEvent(ia, "event", &evt);
            /* We are doing a notification, so there is no pending request */
            type = evt.type;
            state = evt.state;
            /* This is a notification so there aren't any pending requests */
            LOG_DEBUG(LOGCALLBACK(zh), "Calling a watcher for node [%s], type = %d event=%s",
                       (evt.path==NULL?"NULL":evt.path), cptr->c.type,
                       watcherEvent2String(type));
            deliverWatchers(zh,type,state,evt.path, &cptr->c.watcher_result);
            deallocate_WatcherEvent(&evt);
        } else {
            deserialize_response(zh, cptr->c.type, hdr.xid, hdr.err != 0, hdr.err, cptr, ia);
        }
        destroy_completion_entry(cptr);
        close_buffer_iarchive(&ia);
    }
}

static void isSocketReadable(zhandle_t* zh)
{
#ifndef _WIN32
    struct pollfd fds;
    fds.fd = zh->fd->sock;
    fds.events = POLLIN;
    if (poll(&fds,1,0)<=0) {
        // socket not readable -- no more responses to process
        zh->socket_readable.tv_sec=zh->socket_readable.tv_usec=0;
    }
#else
    fd_set rfds;
    struct timeval waittime = {0, 0};
    FD_ZERO(&rfds);
    FD_SET( zh->fd , &rfds);
    if (select(0, &rfds, NULL, NULL, &waittime) <= 0){
        // socket not readable -- no more responses to process
        zh->socket_readable.tv_sec=zh->socket_readable.tv_usec=0;
    }
#endif
    else{
        get_system_time(&zh->socket_readable);
    }
}

static void checkResponseLatency(zhandle_t* zh)
{
    int delay;
    struct timeval now;

    if(zh->socket_readable.tv_sec==0)
        return;

    get_system_time(&now);
    delay=calculate_interval(&zh->socket_readable, &now);
    if(delay>20)
        LOG_DEBUG(LOGCALLBACK(zh), "The following server response has spent at least %dms sitting in the client socket recv buffer",delay);

    zh->socket_readable.tv_sec=zh->socket_readable.tv_usec=0;
}

int zookeeper_process(zhandle_t *zh, int events)
{
    buffer_list_t *bptr;
    int rc;

    if (zh==NULL)
        return ZBADARGUMENTS;
    if (is_unrecoverable(zh))
        return ZINVALIDSTATE;
    api_prolog(zh);
    IF_DEBUG(checkResponseLatency(zh));
    rc = check_events(zh, events);
    if (rc!=ZOK)
        return api_epilog(zh, rc);

    IF_DEBUG(isSocketReadable(zh));

    while (rc >= 0 && (bptr=dequeue_buffer(&zh->to_process))) {
        struct ReplyHeader hdr;
        struct iarchive *ia = create_buffer_iarchive(
                                    bptr->buffer, bptr->curr_offset);
        deserialize_ReplyHeader(ia, "hdr", &hdr);

        if (hdr.xid == PING_XID) {
            // Ping replies can arrive out-of-order
            int elapsed = 0;
            struct timeval now;
            gettimeofday(&now, 0);
            elapsed = calculate_interval(&zh->last_ping, &now);
            LOG_DEBUG(LOGCALLBACK(zh), "Got ping response in %d ms", elapsed);
            free_buffer(bptr);
        } else if (hdr.xid == WATCHER_EVENT_XID) {
            struct WatcherEvent evt;
            int type = 0;
            char *path = NULL;
            completion_list_t *c = NULL;

            LOG_DEBUG(LOGCALLBACK(zh), "Processing WATCHER_EVENT");

            deserialize_WatcherEvent(ia, "event", &evt);
            type = evt.type;
            path = evt.path;
            /* We are doing a notification, so there is no pending request */
            c = create_completion_entry(zh, WATCHER_EVENT_XID,-1,0,0,0,0);
            c->buffer = bptr;
            lock_watchers(zh);
            c->c.watcher_result = collectWatchers(zh, type, path);
            unlock_watchers(zh);

            // We cannot free until now, otherwise path will become invalid
            deallocate_WatcherEvent(&evt);
            queue_completion(&zh->completions_to_process, c, 0);
        } else if (hdr.xid == SET_WATCHES_XID) {
            LOG_DEBUG(LOGCALLBACK(zh), "Processing SET_WATCHES");
            free_buffer(bptr);
        } else if (hdr.xid == AUTH_XID){
            LOG_DEBUG(LOGCALLBACK(zh), "Processing AUTH_XID");

            /* special handling for the AUTH response as it may come back
             * out-of-band */
            auth_completion_func(hdr.err,zh);
            free_buffer(bptr);
            /* authentication completion may change the connection state to
             * unrecoverable */
            if(is_unrecoverable(zh)){
                handle_error(zh, ZAUTHFAILED);
                close_buffer_iarchive(&ia);
                return api_epilog(zh, ZAUTHFAILED);
            }
        } else {
            int rc = hdr.err;
            /* Find the request corresponding to the response */
            completion_list_t *cptr = dequeue_completion(&zh->sent_requests);

            /* [ZOOKEEPER-804] Don't assert if zookeeper_close has been called. */
            if (zh->close_requested == 1 && cptr == NULL) {
                LOG_DEBUG(LOGCALLBACK(zh), "Completion queue has been cleared by zookeeper_close()");
                close_buffer_iarchive(&ia);
                free_buffer(bptr);
                return api_epilog(zh,ZINVALIDSTATE);
            }
            assert(cptr);
            /* The requests are going to come back in order */
            if (cptr->xid != hdr.xid) {
                LOG_DEBUG(LOGCALLBACK(zh), "Processing unexpected or out-of-order response!");

                // received unexpected (or out-of-order) response
                close_buffer_iarchive(&ia);
                free_buffer(bptr);
                // put the completion back on the queue (so it gets properly
                // signaled and deallocated) and disconnect from the server
                queue_completion(&zh->sent_requests,cptr,1);
                return api_epilog(zh,
                                  handle_socket_error_msg(zh, __LINE__,ZRUNTIMEINCONSISTENCY,
                                  "unexpected server response: expected %#x, but received %#x",
                                  hdr.xid,cptr->xid));
            }

            if (hdr.zxid > 0) {
                // Update last_zxid only when it is a request response
                zh->last_zxid = hdr.zxid;
            }
            lock_watchers(zh);
            activateWatcher(zh, cptr->watcher, rc);
            deactivateWatcher(zh, cptr->watcher_deregistration, rc);
            unlock_watchers(zh);

            if (cptr->c.void_result != SYNCHRONOUS_MARKER) {
                LOG_DEBUG(LOGCALLBACK(zh), "Queueing asynchronous response");
                cptr->buffer = bptr;
                queue_completion(&zh->completions_to_process, cptr, 0);
            } else {
#ifdef THREADED
                struct sync_completion
                        *sc = (struct sync_completion*)cptr->data;
                sc->rc = rc;

                process_sync_completion(zh, cptr, sc, ia);

                notify_sync_completion(sc);
                free_buffer(bptr);
                zh->outstanding_sync--;
                destroy_completion_entry(cptr);
#else
                abort_singlethreaded(zh);
#endif
            }
        }

        close_buffer_iarchive(&ia);

    }
    if (process_async(zh->outstanding_sync)) {
        process_completions(zh);
    }

    return api_epilog(zh, ZOK);
}

int zoo_state(zhandle_t *zh)
{
    if(zh!=0)
        return zh->state;
    return 0;
}

static watcher_registration_t* create_watcher_registration(const char* path,
        result_checker_fn checker,watcher_fn watcher,void* ctx){
    watcher_registration_t* wo;
    if(watcher==0)
        return 0;
    wo=calloc(1,sizeof(watcher_registration_t));
    wo->path=strdup(path);
    wo->watcher=watcher;
    wo->context=ctx;
    wo->checker=checker;
    return wo;
}

static watcher_deregistration_t* create_watcher_deregistration(const char* path,
        watcher_fn watcher, void *watcherCtx, ZooWatcherType wtype) {
    watcher_deregistration_t *wdo;

    wdo = calloc(1, sizeof(watcher_deregistration_t));
    if (!wdo) {
      return NULL;
    }
    wdo->path = strdup(path);
    wdo->watcher = watcher;
    wdo->context = watcherCtx;
    wdo->type = wtype;
    return wdo;
}

static void destroy_watcher_registration(watcher_registration_t* wo){
    if(wo!=0){
        free((void*)wo->path);
        free(wo);
    }
}

static void destroy_watcher_deregistration(watcher_deregistration_t *wdo) {
    if (wdo) {
        free((void *)wdo->path);
        free(wdo);
    }
}

static completion_list_t* create_completion_entry(zhandle_t *zh, int xid, int completion_type,
        const void *dc, const void *data,watcher_registration_t* wo, completion_head_t *clist)
{
    return do_create_completion_entry(zh, xid, completion_type, dc, data, wo,
                                      clist, NULL);
}

static completion_list_t* create_completion_entry_deregistration(zhandle_t *zh,
        int xid, int completion_type, const void *dc, const void *data,
        watcher_deregistration_t* wdo, completion_head_t *clist)
{
    return do_create_completion_entry(zh, xid, completion_type, dc, data, NULL,
                                      clist, wdo);
}

static completion_list_t* do_create_completion_entry(zhandle_t *zh, int xid,
        int completion_type, const void *dc, const void *data,
        watcher_registration_t* wo, completion_head_t *clist,
        watcher_deregistration_t* wdo)
{
    completion_list_t *c = calloc(1, sizeof(completion_list_t));
    if (!c) {
        LOG_ERROR(LOGCALLBACK(zh), "out of memory");
        return 0;
    }
    c->c.type = completion_type;
    c->data = data;
    switch(c->c.type) {
    case COMPLETION_VOID:
        c->c.void_result = (void_completion_t)dc;
        break;
    case COMPLETION_STRING:
        c->c.string_result = (string_completion_t)dc;
        break;
    case COMPLETION_DATA:
        c->c.data_result = (data_completion_t)dc;
        break;
    case COMPLETION_STAT:
        c->c.stat_result = (stat_completion_t)dc;
        break;
    case COMPLETION_STRINGLIST:
        c->c.strings_result = (strings_completion_t)dc;
        break;
    case COMPLETION_STRINGLIST_STAT:
        c->c.strings_stat_result = (strings_stat_completion_t)dc;
        break;
    case COMPLETION_STRING_STAT:
        c->c.string_stat_result = (string_stat_completion_t)dc;
    case COMPLETION_ACLLIST:
        c->c.acl_result = (acl_completion_t)dc;
        break;
    case COMPLETION_MULTI:
        assert(clist);
        c->c.void_result = (void_completion_t)dc;
        c->c.clist = *clist;
        break;
    }
    c->xid = xid;
    c->watcher = wo;
    c->watcher_deregistration = wdo;

    return c;
}

static void destroy_completion_entry(completion_list_t* c){
    if(c!=0){
        destroy_watcher_registration(c->watcher);
        destroy_watcher_deregistration(c->watcher_deregistration);
        if(c->buffer!=0)
            free_buffer(c->buffer);
        free(c);
    }
}

static void queue_completion_nolock(completion_head_t *list,
                                    completion_list_t *c,
                                    int add_to_front)
{
    c->next = 0;
    /* appending a new entry to the back of the list */
    if (list->last) {
        assert(list->head);
        // List is not empty
        if (!add_to_front) {
            list->last->next = c;
            list->last = c;
        } else {
            c->next = list->head;
            list->head = c;
        }
    } else {
        // List is empty
        assert(!list->head);
        list->head = c;
        list->last = c;
    }
}

static void queue_completion(completion_head_t *list, completion_list_t *c,
        int add_to_front)
{

    lock_completion_list(list);
    queue_completion_nolock(list, c, add_to_front);
    unlock_completion_list(list);
}

static int add_completion(zhandle_t *zh, int xid, int completion_type,
        const void *dc, const void *data, int add_to_front,
        watcher_registration_t* wo, completion_head_t *clist)
{
    completion_list_t *c =create_completion_entry(zh, xid, completion_type, dc,
            data, wo, clist);
    return do_add_completion(zh, dc, c, add_to_front);
}

static int add_completion_deregistration(zhandle_t *zh, int xid,
        int completion_type, const void *dc, const void *data, int add_to_front,
        watcher_deregistration_t* wdo, completion_head_t *clist)
{
    completion_list_t *c = create_completion_entry_deregistration(zh, xid,
           completion_type, dc, data, wdo, clist);
    return do_add_completion(zh, dc, c, add_to_front);
}

static int do_add_completion(zhandle_t *zh, const void *dc,
        completion_list_t *c, int add_to_front)
{
    int rc = 0;
    if (!c)
        return ZSYSTEMERROR;
    lock_completion_list(&zh->sent_requests);
    if (zh->close_requested != 1) {
        queue_completion_nolock(&zh->sent_requests, c, add_to_front);
        if (dc == SYNCHRONOUS_MARKER) {
            zh->outstanding_sync++;
        }
        rc = ZOK;
    } else {
        free(c);
        rc = ZINVALIDSTATE;
    }
    unlock_completion_list(&zh->sent_requests);
    return rc;
}

static int add_data_completion(zhandle_t *zh, int xid, data_completion_t dc,
        const void *data,watcher_registration_t* wo)
{
    return add_completion(zh, xid, COMPLETION_DATA, dc, data, 0, wo, 0);
}

static int add_stat_completion(zhandle_t *zh, int xid, stat_completion_t dc,
        const void *data,watcher_registration_t* wo)
{
    return add_completion(zh, xid, COMPLETION_STAT, dc, data, 0, wo, 0);
}

static int add_strings_completion(zhandle_t *zh, int xid,
        strings_completion_t dc, const void *data,watcher_registration_t* wo)
{
    return add_completion(zh, xid, COMPLETION_STRINGLIST, dc, data, 0, wo, 0);
}

static int add_strings_stat_completion(zhandle_t *zh, int xid,
        strings_stat_completion_t dc, const void *data,watcher_registration_t* wo)
{
    return add_completion(zh, xid, COMPLETION_STRINGLIST_STAT, dc, data, 0, wo, 0);
}

static int add_acl_completion(zhandle_t *zh, int xid, acl_completion_t dc,
        const void *data)
{
    return add_completion(zh, xid, COMPLETION_ACLLIST, dc, data, 0, 0, 0);
}

static int add_void_completion(zhandle_t *zh, int xid, void_completion_t dc,
        const void *data)
{
    return add_completion(zh, xid, COMPLETION_VOID, dc, data, 0, 0, 0);
}

static int add_string_completion(zhandle_t *zh, int xid,
        string_completion_t dc, const void *data)
{
    return add_completion(zh, xid, COMPLETION_STRING, dc, data, 0, 0, 0);
}

static int add_string_stat_completion(zhandle_t *zh, int xid,
        string_stat_completion_t dc, const void *data)
{
    return add_completion(zh, xid, COMPLETION_STRING_STAT, dc, data, 0, 0, 0);
}

static int add_multi_completion(zhandle_t *zh, int xid, void_completion_t dc,
        const void *data, completion_head_t *clist)
{
    return add_completion(zh, xid, COMPLETION_MULTI, dc, data, 0,0, clist);
}

/**
 * After sending the close request, we are waiting for a given millisecs for
 * getting the answer and/or for the socket to be closed by the server.
 *
 * This function should not be called while we still want to process
 * any response from the server. It must be called after adaptor_finish called,
 * in order not to mess with the I/O receiver thread in multi-threaded mode.
 */
int wait_for_session_to_be_closed(zhandle_t *zh, int timeout_ms)
{
    int ret = 0;
#ifndef WIN32
    struct pollfd fd_s[1];
#else
    fd_set rfds;
    struct timeval waittime = {timeout_ms / 1000, (timeout_ms % 1000) * 1000};
#endif

    if (zh == NULL) {
        return ZBADARGUMENTS;
    }

#ifndef WIN32
    fd_s[0].fd = zh->fd->sock;
    fd_s[0].events = POLLIN;
    ret = poll(fd_s, 1, timeout_ms);
#else
    FD_ZERO(&rfds);
    FD_SET(zh->fd->sock , &rfds);
    ret = select(zh->fd->sock + 1, &rfds, NULL, NULL, &waittime);
#endif

    if (ret == 0){
        LOG_WARN(LOGCALLBACK(zh), "Timed out (%dms) during waiting for server's reply after sending a close request, sessionId=%#llx\n",
            timeout_ms, zh->client_id.client_id);
    } else if (ret < 0) {
        LOG_WARN(LOGCALLBACK(zh), "System error (%d) happened while waiting for server's reply, sessionId=%#llx\n",
            ret, zh->client_id.client_id);
    }

    return ZOK;
}

int zookeeper_close(zhandle_t *zh)
{
    int rc=ZOK;
    if (zh==0)
        return ZBADARGUMENTS;

    zh->close_requested=1;
    if (inc_ref_counter(zh,1)>1) {
        /* We have incremented the ref counter to prevent the
         * completions from calling zookeeper_close before we have
         * completed the adaptor_finish call below. */

    /* Signal any syncronous completions before joining the threads */
        enter_critical(zh);
        free_completions(zh,1,ZCLOSING);
        leave_critical(zh);

        adaptor_finish(zh);
        /* Now we can allow the handle to be cleaned up, if the completion
         * threads finished during the adaptor_finish call. */
        api_epilog(zh, 0);
        return ZOK;
    }
    /* No need to decrement the counter since we're just going to
     * destroy the handle later. */
    if (is_connected(zh)) {
        struct oarchive *oa;
        struct RequestHeader h = {get_xid(), ZOO_CLOSE_OP};
        LOG_INFO(LOGCALLBACK(zh), "Closing zookeeper sessionId=%#llx to %s\n",
            zh->client_id.client_id, zoo_get_current_server(zh));
        oa = create_buffer_oarchive();
        rc = serialize_RequestHeader(oa, "header", &h);
        rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa), get_buffer_len(oa));
        /* We queued the buffer, so don't free it */
        close_buffer_oarchive(&oa, 0);
        if (rc < 0) {
            LOG_DEBUG(LOGCALLBACK(zh), "Error during closing zookeeper session, sessionId=%#llx to %s (error: %d)\n",
                zh->client_id.client_id, zoo_get_current_server(zh), rc);
            rc = ZMARSHALLINGERROR;
        } else {
            /* make sure the close request is sent; we set timeout to an arbitrary
             * (but reasonable) number of milliseconds since we want the call to block*/
            rc = adaptor_send_queue(zh, 3000);

            /* give some time to the server to process the session close request properly */
            rc = rc < 0 ? rc : wait_for_session_to_be_closed(zh, 1500);
        }
    } else {
        rc = ZOK;
    }

    LOG_INFO(LOGCALLBACK(zh), "Freeing zookeeper resources for sessionId=%#llx\n", zh->client_id.client_id);
    destroy(zh);
    adaptor_destroy(zh);
    free(zh->fd);
    free(zh);
#ifdef _WIN32
    Win32WSACleanup();
#endif
    return rc;
}

static int isValidPath(const char* path, const int mode) {
    int len = 0;
    char lastc = '/';
    char c;
    int i = 0;

  if (path == 0)
    return 0;
  len = strlen(path);
  if (len == 0)
    return 0;
  if (path[0] != '/')
    return 0;
  if (len == 1) // done checking - it's the root
    return 1;
  if (path[len - 1] == '/' && !ZOOKEEPER_IS_SEQUENCE(mode))
    return 0;

  i = 1;
  for (; i < len; lastc = path[i], i++) {
    c = path[i];

    if (c == 0) {
      return 0;
    } else if (c == '/' && lastc == '/') {
      return 0;
    } else if (c == '.' && lastc == '.') {
      if (path[i-2] == '/' && (((i + 1 == len) && !ZOOKEEPER_IS_SEQUENCE(mode))
                               || path[i+1] == '/')) {
        return 0;
      }
    } else if (c == '.') {
      if ((path[i-1] == '/') && (((i + 1 == len) && !ZOOKEEPER_IS_SEQUENCE(mode))
                                 || path[i+1] == '/')) {
        return 0;
      }
    } else if (c > 0x00 && c < 0x1f) {
      return 0;
    }
  }

  return 1;
}

/*---------------------------------------------------------------------------*
 * REQUEST INIT HELPERS
 *---------------------------------------------------------------------------*/
/* Common Request init helper functions to reduce code duplication */
static int Request_path_init(zhandle_t *zh, int mode,
        char **path_out, const char *path)
{
    assert(path_out);

    *path_out = prepend_string(zh, path);
    if (zh == NULL || !isValidPath(*path_out, mode)) {
        free_duplicate_path(*path_out, path);
        return ZBADARGUMENTS;
    }
    if (is_unrecoverable(zh)) {
        free_duplicate_path(*path_out, path);
        return ZINVALIDSTATE;
    }

    return ZOK;
}

static int Request_path_watch_init(zhandle_t *zh, int mode,
        char **path_out, const char *path,
        int32_t *watch_out, uint32_t watch)
{
    int rc = Request_path_init(zh, mode, path_out, path);
    if (rc != ZOK) {
        return rc;
    }
    *watch_out = watch;
    return ZOK;
}

/*---------------------------------------------------------------------------*
 * ASYNC API
 *---------------------------------------------------------------------------*/
int zoo_aget(zhandle_t *zh, const char *path, int watch, data_completion_t dc,
        const void *data)
{
    return zoo_awget(zh,path,watch?zh->watcher:0,zh->context,dc,data);
}

int zoo_awget(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        data_completion_t dc, const void *data)
{
    struct oarchive *oa;
    char *server_path = prepend_string(zh, path);
    struct RequestHeader h = {get_xid(), ZOO_GETDATA_OP};
    struct GetDataRequest req =  { (char*)server_path, watcher!=0 };
    int rc;

    if (zh==0 || !isValidPath(server_path, 0)) {
        free_duplicate_path(server_path, path);
        return ZBADARGUMENTS;
    }
    if (is_unrecoverable(zh)) {
        free_duplicate_path(server_path, path);
        return ZINVALIDSTATE;
    }
    oa=create_buffer_oarchive();
    rc = serialize_RequestHeader(oa, "header", &h);
    rc = rc < 0 ? rc : serialize_GetDataRequest(oa, "req", &req);
    enter_critical(zh);
    rc = rc < 0 ? rc : add_data_completion(zh, h.xid, dc, data,
    create_watcher_registration(server_path,data_result_checker,watcher,watcherCtx));
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);
    free_duplicate_path(server_path, path);
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending request xid=%#x for path [%s] to %s",h.xid,path,
            zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

int zoo_agetconfig(zhandle_t *zh, int watch, data_completion_t dc,
        const void *data)
{
    return zoo_awgetconfig(zh,watch?zh->watcher:0,zh->context,dc,data);
}

int zoo_awgetconfig(zhandle_t *zh, watcher_fn watcher, void* watcherCtx,
        data_completion_t dc, const void *data)
{
    struct oarchive *oa;
    char *path = ZOO_CONFIG_NODE;
    char *server_path = ZOO_CONFIG_NODE;
    struct RequestHeader h = { get_xid(), ZOO_GETDATA_OP };
    struct GetDataRequest req =  { (char*)server_path, watcher!=0 };
    int rc;

    if (zh==0 || !isValidPath(server_path, 0)) {
        free_duplicate_path(server_path, path);
        return ZBADARGUMENTS;
    }
    if (is_unrecoverable(zh)) {
        free_duplicate_path(server_path, path);
        return ZINVALIDSTATE;
    }
    oa=create_buffer_oarchive();
    rc = serialize_RequestHeader(oa, "header", &h);
    rc = rc < 0 ? rc : serialize_GetDataRequest(oa, "req", &req);
    enter_critical(zh);
    rc = rc < 0 ? rc : add_data_completion(zh, h.xid, dc, data,
                                           create_watcher_registration(server_path,data_result_checker,watcher,watcherCtx));
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
                                          get_buffer_len(oa));
    leave_critical(zh);
    free_duplicate_path(server_path, path);
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending request xid=%#x for path [%s] to %s",h.xid,path,
               zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

int zoo_areconfig(zhandle_t *zh, const char *joining, const char *leaving,
       const char *members, int64_t version, data_completion_t dc, const void *data)
{
    struct oarchive *oa;
    struct RequestHeader h = { get_xid(), ZOO_RECONFIG_OP };
    struct ReconfigRequest req;
   int rc = 0;

    if (zh==0) {
        return ZBADARGUMENTS;
    }
    if (is_unrecoverable(zh)) {
        return ZINVALIDSTATE;
    }

   oa=create_buffer_oarchive();
   req.joiningServers = (char *)joining;
   req.leavingServers = (char *)leaving;
   req.newMembers = (char *)members;
   req.curConfigId = version;
    rc = serialize_RequestHeader(oa, "header", &h);
   rc = rc < 0 ? rc : serialize_ReconfigRequest(oa, "req", &req);
    enter_critical(zh);
    rc = rc < 0 ? rc : add_data_completion(zh, h.xid, dc, data, NULL);
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending Reconfig request xid=%#x to %s",h.xid, zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);

    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

static int SetDataRequest_init(zhandle_t *zh, struct SetDataRequest *req,
        const char *path, const char *buffer, int buflen, int version)
{
    int rc;
    assert(req);
    rc = Request_path_init(zh, 0, &req->path, path);
    if (rc != ZOK) {
        return rc;
    }
    req->data.buff = (char*)buffer;
    req->data.len = buflen;
    req->version = version;

    return ZOK;
}

int zoo_aset(zhandle_t *zh, const char *path, const char *buffer, int buflen,
        int version, stat_completion_t dc, const void *data)
{
    struct oarchive *oa;
    struct RequestHeader h = {get_xid(), ZOO_SETDATA_OP};
    struct SetDataRequest req;
    int rc = SetDataRequest_init(zh, &req, path, buffer, buflen, version);
    if (rc != ZOK) {
        return rc;
    }
    oa = create_buffer_oarchive();
    rc = serialize_RequestHeader(oa, "header", &h);
    rc = rc < 0 ? rc : serialize_SetDataRequest(oa, "req", &req);
    enter_critical(zh);
    rc = rc < 0 ? rc : add_stat_completion(zh, h.xid, dc, data,0);
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);
    free_duplicate_path(req.path, path);
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending request xid=%#x for path [%s] to %s",h.xid,path,
            zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

static int CreateRequest_init(zhandle_t *zh, struct CreateRequest *req,
        const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl_entries, int mode)
{
    int rc;
    assert(req);
    rc = Request_path_init(zh, mode, &req->path, path);
    assert(req);
    if (rc != ZOK) {
        return rc;
    }
    req->flags = mode;
    req->data.buff = (char*)value;
    req->data.len = valuelen;
    if (acl_entries == 0) {
        req->acl.count = 0;
        req->acl.data = 0;
    } else {
        req->acl = *acl_entries;
    }

    return ZOK;
}

static int CreateTTLRequest_init(zhandle_t *zh, struct CreateTTLRequest *req,
        const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl_entries, int mode, int64_t ttl)
{
    int rc;
    assert(req);
    rc = Request_path_init(zh, mode, &req->path, path);
    assert(req);
    if (rc != ZOK) {
        return rc;
    }
    req->flags = mode;
    req->data.buff = (char*)value;
    req->data.len = valuelen;
    if (acl_entries == 0) {
        req->acl.count = 0;
        req->acl.data = 0;
    } else {
        req->acl = *acl_entries;
    }
    req->ttl = ttl;

    return ZOK;
}

static int get_create_op_type(int mode, int default_op) {
    if (mode == ZOO_CONTAINER) {
        return ZOO_CREATE_CONTAINER_OP;
    } else if (ZOOKEEPER_IS_TTL(mode)) {
        return ZOO_CREATE_TTL_OP;
    } else {
        return default_op;
    }
}

int zoo_acreate(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl_entries, int mode,
        string_completion_t completion, const void *data)
{
    return zoo_acreate_ttl(zh, path, value, valuelen, acl_entries, mode, -1, completion, data);
}

int zoo_acreate_ttl(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl_entries, int mode, int64_t ttl,
        string_completion_t completion, const void *data)
{
    struct oarchive *oa;
    struct RequestHeader h = {get_xid(), get_create_op_type(mode, ZOO_CREATE_OP)};
    int rc;
    char *req_path;

    if (ZOOKEEPER_IS_TTL(mode)) {
        struct CreateTTLRequest req;

        if (ttl <= 0 || ttl > ZOO_MAX_TTL) {
            return ZBADARGUMENTS;
        }

        rc = CreateTTLRequest_init(zh, &req,
                path, value, valuelen, acl_entries, mode, ttl);
        if (rc != ZOK) {
            return rc;
        }
        oa = create_buffer_oarchive();
        rc = serialize_RequestHeader(oa, "header", &h);
        rc = rc < 0 ? rc : serialize_CreateTTLRequest(oa, "req", &req);

        req_path = req.path;
    } else {
        struct CreateRequest req;

        if (ttl >= 0) {
            return ZBADARGUMENTS;
        }

        rc = CreateRequest_init(zh, &req,
                path, value, valuelen, acl_entries, mode);
        if (rc != ZOK) {
            return rc;
        }
        oa = create_buffer_oarchive();
        rc = serialize_RequestHeader(oa, "header", &h);
        rc = rc < 0 ? rc : serialize_CreateRequest(oa, "req", &req);

        req_path = req.path;
    }

    enter_critical(zh);
    rc = rc < 0 ? rc : add_string_completion(zh, h.xid, completion, data);
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);
    free_duplicate_path(req_path, path);
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending request xid=%#x for path [%s] to %s",h.xid,path,
            zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

int zoo_acreate2(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl_entries, int mode,
        string_stat_completion_t completion, const void *data)
{
    return zoo_acreate2_ttl(zh, path, value, valuelen, acl_entries, mode, -1, completion, data);
}

int zoo_acreate2_ttl(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl_entries, int mode, int64_t ttl,
        string_stat_completion_t completion, const void *data)
{
    struct oarchive *oa;
    struct RequestHeader h = { get_xid(), get_create_op_type(mode, ZOO_CREATE2_OP) };
    int rc;
    char *req_path;

    if (ZOOKEEPER_IS_TTL(mode)) {
        struct CreateTTLRequest req;

        if (ttl <= 0 || ttl > ZOO_MAX_TTL) {
            return ZBADARGUMENTS;
        }

        rc = CreateTTLRequest_init(zh, &req,
                path, value, valuelen, acl_entries, mode, ttl);
        if (rc != ZOK) {
            return rc;
        }
        oa = create_buffer_oarchive();
        rc = serialize_RequestHeader(oa, "header", &h);
        rc = rc < 0 ? rc : serialize_CreateTTLRequest(oa, "req", &req);

        req_path = req.path;
    } else {
        struct CreateRequest req;

        if (ttl >= 0) {
            return ZBADARGUMENTS;
        }

        rc = CreateRequest_init(zh, &req, path, value, valuelen, acl_entries, mode);
        if (rc != ZOK) {
            return rc;
        }
        oa = create_buffer_oarchive();
        rc = serialize_RequestHeader(oa, "header", &h);
        rc = rc < 0 ? rc : serialize_CreateRequest(oa, "req", &req);

        req_path = req.path;
    }

    enter_critical(zh);
    rc = rc < 0 ? rc : add_string_stat_completion(zh, h.xid, completion, data);
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);
    free_duplicate_path(req_path, path);
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending request xid=%#x for path [%s] to %s",h.xid,path,
            zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

int DeleteRequest_init(zhandle_t *zh, struct DeleteRequest *req,
        const char *path, int version)
{
    int rc = Request_path_init(zh, 0, &req->path, path);
    if (rc != ZOK) {
        return rc;
    }
    req->version = version;
    return ZOK;
}

int zoo_adelete(zhandle_t *zh, const char *path, int version,
        void_completion_t completion, const void *data)
{
    struct oarchive *oa;
    struct RequestHeader h = {get_xid(), ZOO_DELETE_OP};
    struct DeleteRequest req;
    int rc = DeleteRequest_init(zh, &req, path, version);
    if (rc != ZOK) {
        return rc;
    }
    oa = create_buffer_oarchive();
    rc = serialize_RequestHeader(oa, "header", &h);
    rc = rc < 0 ? rc : serialize_DeleteRequest(oa, "req", &req);
    enter_critical(zh);
    rc = rc < 0 ? rc : add_void_completion(zh, h.xid, completion, data);
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);
    free_duplicate_path(req.path, path);
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending request xid=%#x for path [%s] to %s",h.xid,path,
            zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

int zoo_aexists(zhandle_t *zh, const char *path, int watch,
        stat_completion_t sc, const void *data)
{
    return zoo_awexists(zh,path,watch?zh->watcher:0,zh->context,sc,data);
}

int zoo_awexists(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        stat_completion_t completion, const void *data)
{
    struct oarchive *oa;
    struct RequestHeader h = {get_xid(), ZOO_EXISTS_OP};
    struct ExistsRequest req;
    int rc = Request_path_watch_init(zh, 0, &req.path, path,
            &req.watch, watcher != NULL);
    if (rc != ZOK) {
        return rc;
    }
    oa = create_buffer_oarchive();
    rc = serialize_RequestHeader(oa, "header", &h);
    rc = rc < 0 ? rc : serialize_ExistsRequest(oa, "req", &req);
    enter_critical(zh);
    rc = rc < 0 ? rc : add_stat_completion(zh, h.xid, completion, data,
        create_watcher_registration(req.path,exists_result_checker,
                watcher,watcherCtx));
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);
    free_duplicate_path(req.path, path);
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending request xid=%#x for path [%s] to %s",h.xid,path,
            zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

static int zoo_awget_children_(zhandle_t *zh, const char *path,
         watcher_fn watcher, void* watcherCtx,
         strings_completion_t sc,
         const void *data)
{
    struct oarchive *oa;
    struct RequestHeader h = {get_xid(), ZOO_GETCHILDREN_OP};
    struct GetChildrenRequest req ;
    int rc = Request_path_watch_init(zh, 0, &req.path, path,
            &req.watch, watcher != NULL);
    if (rc != ZOK) {
        return rc;
    }
    oa = create_buffer_oarchive();
    rc = serialize_RequestHeader(oa, "header", &h);
    rc = rc < 0 ? rc : serialize_GetChildrenRequest(oa, "req", &req);
    enter_critical(zh);
    rc = rc < 0 ? rc : add_strings_completion(zh, h.xid, sc, data,
            create_watcher_registration(req.path,child_result_checker,watcher,watcherCtx));
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);
    free_duplicate_path(req.path, path);
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending request xid=%#x for path [%s] to %s",h.xid,path,
            zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

int zoo_aget_children(zhandle_t *zh, const char *path, int watch,
        strings_completion_t dc, const void *data)
{
    return zoo_awget_children_(zh,path,watch?zh->watcher:0,zh->context,dc,data);
}

int zoo_awget_children(zhandle_t *zh, const char *path,
         watcher_fn watcher, void* watcherCtx,
         strings_completion_t dc,
         const void *data)
{
    return zoo_awget_children_(zh,path,watcher,watcherCtx,dc,data);
}

static int zoo_awget_children2_(zhandle_t *zh, const char *path,
         watcher_fn watcher, void* watcherCtx,
         strings_stat_completion_t ssc,
         const void *data)
{
    /* invariant: (sc == NULL) != (sc == NULL) */
    struct oarchive *oa;
    struct RequestHeader h = {get_xid(), ZOO_GETCHILDREN2_OP};
    struct GetChildren2Request req ;
    int rc = Request_path_watch_init(zh, 0, &req.path, path,
            &req.watch, watcher != NULL);
    if (rc != ZOK) {
        return rc;
    }
    oa = create_buffer_oarchive();
    rc = serialize_RequestHeader(oa, "header", &h);
    rc = rc < 0 ? rc : serialize_GetChildren2Request(oa, "req", &req);
    enter_critical(zh);
    rc = rc < 0 ? rc : add_strings_stat_completion(zh, h.xid, ssc, data,
            create_watcher_registration(req.path,child_result_checker,watcher,watcherCtx));
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);
    free_duplicate_path(req.path, path);
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending request xid=%#x for path [%s] to %s",h.xid,path,
            zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

int zoo_aget_children2(zhandle_t *zh, const char *path, int watch,
        strings_stat_completion_t dc, const void *data)
{
    return zoo_awget_children2_(zh,path,watch?zh->watcher:0,zh->context,dc,data);
}

int zoo_awget_children2(zhandle_t *zh, const char *path,
         watcher_fn watcher, void* watcherCtx,
         strings_stat_completion_t dc,
         const void *data)
{
    return zoo_awget_children2_(zh,path,watcher,watcherCtx,dc,data);
}

int zoo_async(zhandle_t *zh, const char *path,
        string_completion_t completion, const void *data)
{
    struct oarchive *oa;
    struct RequestHeader h = {get_xid(), ZOO_SYNC_OP};
    struct SyncRequest req;
    int rc = Request_path_init(zh, 0, &req.path, path);
    if (rc != ZOK) {
        return rc;
    }
    oa = create_buffer_oarchive();
    rc = serialize_RequestHeader(oa, "header", &h);
    rc = rc < 0 ? rc : serialize_SyncRequest(oa, "req", &req);
    enter_critical(zh);
    rc = rc < 0 ? rc : add_string_completion(zh, h.xid, completion, data);
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);
    free_duplicate_path(req.path, path);
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending request xid=%#x for path [%s] to %s",h.xid,path,
            zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}


int zoo_aget_acl(zhandle_t *zh, const char *path, acl_completion_t completion,
        const void *data)
{
    struct oarchive *oa;
    struct RequestHeader h = {get_xid(), ZOO_GETACL_OP};
    struct GetACLRequest req;
    int rc = Request_path_init(zh, 0, &req.path, path) ;
    if (rc != ZOK) {
        return rc;
    }
    oa = create_buffer_oarchive();
    rc = serialize_RequestHeader(oa, "header", &h);
    rc = rc < 0 ? rc : serialize_GetACLRequest(oa, "req", &req);
    enter_critical(zh);
    rc = rc < 0 ? rc : add_acl_completion(zh, h.xid, completion, data);
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);
    free_duplicate_path(req.path, path);
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending request xid=%#x for path [%s] to %s",h.xid,path,
            zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

int zoo_aset_acl(zhandle_t *zh, const char *path, int version,
        struct ACL_vector *acl, void_completion_t completion, const void *data)
{
    struct oarchive *oa;
    struct RequestHeader h = {get_xid(), ZOO_SETACL_OP};
    struct SetACLRequest req;
    int rc = Request_path_init(zh, 0, &req.path, path);
    if (rc != ZOK) {
        return rc;
    }
    oa = create_buffer_oarchive();
    req.acl = *acl;
    req.version = version;
    rc = serialize_RequestHeader(oa, "header", &h);
    rc = rc < 0 ? rc : serialize_SetACLRequest(oa, "req", &req);
    enter_critical(zh);
    rc = rc < 0 ? rc : add_void_completion(zh, h.xid, completion, data);
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);
    free_duplicate_path(req.path, path);
    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending request xid=%#x for path [%s] to %s",h.xid,path,
            zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);
    return (rc < 0)?ZMARSHALLINGERROR:ZOK;
}

/* Completions for multi-op results */
static void op_result_string_completion(int err, const char *value, const void *data)
{
    struct zoo_op_result *result = (struct zoo_op_result *)data;
    assert(result);
    result->err = err;

    if (result->value && value) {
        int len = strlen(value) + 1;
        if (len > result->valuelen) {
            len = result->valuelen;
        }
        if (len > 0) {
            memcpy(result->value, value, len - 1);
            result->value[len - 1] = '\0';
        }
    } else {
        result->value = NULL;
    }
}

static void op_result_void_completion(int err, const void *data)
{
    struct zoo_op_result *result = (struct zoo_op_result *)data;
    assert(result);
    result->err = err;
}

static void op_result_stat_completion(int err, const struct Stat *stat, const void *data)
{
    struct zoo_op_result *result = (struct zoo_op_result *)data;
    assert(result);
    result->err = err;

    if (result->stat && err == 0 && stat) {
        *result->stat = *stat;
    } else {
        result->stat = NULL ;
    }
}

static int CheckVersionRequest_init(zhandle_t *zh, struct CheckVersionRequest *req,
        const char *path, int version)
{
    int rc ;
    assert(req);
    rc = Request_path_init(zh, 0, &req->path, path);
    if (rc != ZOK) {
        return rc;
    }
    req->version = version;

    return ZOK;
}

int zoo_amulti(zhandle_t *zh, int count, const zoo_op_t *ops,
        zoo_op_result_t *results, void_completion_t completion, const void *data)
{
    struct RequestHeader h = {get_xid(), ZOO_MULTI_OP};
    struct MultiHeader mh = {-1, 1, -1};
    struct oarchive *oa = create_buffer_oarchive();
    completion_head_t clist = { 0 };

    int rc = serialize_RequestHeader(oa, "header", &h);

    int index = 0;
    for (index=0; index < count; index++) {
        const zoo_op_t *op = ops+index;
        zoo_op_result_t *result = results+index;
        completion_list_t *entry = NULL;

        struct MultiHeader mh = {op->type, 0, -1};
        rc = rc < 0 ? rc : serialize_MultiHeader(oa, "multiheader", &mh);

        switch(op->type) {
            case ZOO_CREATE_CONTAINER_OP:
            case ZOO_CREATE_OP: {
                struct CreateRequest req;

                rc = rc < 0 ? rc : CreateRequest_init(zh, &req,
                                        op->create_op.path, op->create_op.data,
                                        op->create_op.datalen, op->create_op.acl,
                                        op->create_op.flags);
                rc = rc < 0 ? rc : serialize_CreateRequest(oa, "req", &req);
                result->value = op->create_op.buf;
                result->valuelen = op->create_op.buflen;

                enter_critical(zh);
                entry = create_completion_entry(zh, h.xid, COMPLETION_STRING, op_result_string_completion, result, 0, 0);
                leave_critical(zh);
                free_duplicate_path(req.path, op->create_op.path);
                break;
            }

            case ZOO_DELETE_OP: {
                struct DeleteRequest req;
                rc = rc < 0 ? rc : DeleteRequest_init(zh, &req, op->delete_op.path, op->delete_op.version);
                rc = rc < 0 ? rc : serialize_DeleteRequest(oa, "req", &req);

                enter_critical(zh);
                entry = create_completion_entry(zh, h.xid, COMPLETION_VOID, op_result_void_completion, result, 0, 0);
                leave_critical(zh);
                free_duplicate_path(req.path, op->delete_op.path);
                break;
            }

            case ZOO_SETDATA_OP: {
                struct SetDataRequest req;
                rc = rc < 0 ? rc : SetDataRequest_init(zh, &req,
                                        op->set_op.path, op->set_op.data,
                                        op->set_op.datalen, op->set_op.version);
                rc = rc < 0 ? rc : serialize_SetDataRequest(oa, "req", &req);
                result->stat = op->set_op.stat;

                enter_critical(zh);
                entry = create_completion_entry(zh, h.xid, COMPLETION_STAT, op_result_stat_completion, result, 0, 0);
                leave_critical(zh);
                free_duplicate_path(req.path, op->set_op.path);
                break;
            }

            case ZOO_CHECK_OP: {
                struct CheckVersionRequest req;
                rc = rc < 0 ? rc : CheckVersionRequest_init(zh, &req,
                                        op->check_op.path, op->check_op.version);
                rc = rc < 0 ? rc : serialize_CheckVersionRequest(oa, "req", &req);

                enter_critical(zh);
                entry = create_completion_entry(zh, h.xid, COMPLETION_VOID, op_result_void_completion, result, 0, 0);
                leave_critical(zh);
                free_duplicate_path(req.path, op->check_op.path);
                break;
            }

            default:
                LOG_ERROR(LOGCALLBACK(zh), "Unimplemented sub-op type=%d in multi-op", op->type);
                return ZUNIMPLEMENTED;
        }

        queue_completion(&clist, entry, 0);
    }

    rc = rc < 0 ? rc : serialize_MultiHeader(oa, "multiheader", &mh);

    /* BEGIN: CRTICIAL SECTION */
    enter_critical(zh);
    rc = rc < 0 ? rc : add_multi_completion(zh, h.xid, completion, data, &clist);
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    leave_critical(zh);

    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending multi request xid=%#x with %d subrequests to %s",
            h.xid, index, zoo_get_current_server(zh));
    /* make a best (non-blocking) effort to send the requests asap */
    adaptor_send_queue(zh, 0);

    return (rc < 0) ? ZMARSHALLINGERROR : ZOK;
}

typedef union WatchesRequest WatchesRequest;

union WatchesRequest {
    struct CheckWatchesRequest check;
    struct RemoveWatchesRequest remove;
};

static int aremove_watches(
        zhandle_t *zh, const char *path, ZooWatcherType wtype,
        watcher_fn watcher, void *watcherCtx, int local,
        void_completion_t *completion, const void *data, int all)
{
    char *server_path = prepend_string(zh, path);
    int rc;
    struct oarchive *oa;
    struct RequestHeader h = { 
        get_xid(), 
        all ? ZOO_REMOVE_WATCHES : ZOO_CHECK_WATCHES 
    };
    WatchesRequest req;
    watcher_deregistration_t *wdo;

    if (!zh || !isValidPath(server_path, 0)) {
        rc = ZBADARGUMENTS;
        goto done;
    }

    if (!local && is_unrecoverable(zh)) {
        rc = ZINVALIDSTATE;
        goto done;
    }

    lock_watchers(zh);
    if (!pathHasWatcher(zh, server_path, wtype, watcher, watcherCtx)) {
        rc = ZNOWATCHER;
        unlock_watchers(zh);
        goto done;
    }

    if (local) {
        removeWatchers(zh, server_path, wtype, watcher, watcherCtx);
        unlock_watchers(zh);
#ifdef THREADED
        notify_sync_completion((struct sync_completion *)data);
#endif
        rc = ZOK;
        goto done;
    }
    unlock_watchers(zh);

    oa = create_buffer_oarchive();
    rc = serialize_RequestHeader(oa, "header", &h);

    if (all) {
       req.remove.path = (char*)server_path;
       req.remove.type = wtype;
       rc = rc < 0 ? rc : serialize_RemoveWatchesRequest(oa, "req", &req.remove);
    } else {
        req.check.path = (char*)server_path;
        req.check.type = wtype;
        rc = rc < 0 ? rc : serialize_CheckWatchesRequest(oa, "req", &req.check);
    }

    if (rc < 0) {
        goto done;
    }

    wdo = create_watcher_deregistration(
        server_path, watcher, watcherCtx, wtype);

    if (!wdo) {
        rc = ZSYSTEMERROR;
        goto done;
    }

    enter_critical(zh);
    rc = add_completion_deregistration(
        zh, h.xid, COMPLETION_VOID, completion, data, 0, wdo, 0);
    rc = rc < 0 ? rc : queue_buffer_bytes(&zh->to_send, get_buffer(oa),
            get_buffer_len(oa));
    rc = rc < 0 ? ZMARSHALLINGERROR : ZOK;
    leave_critical(zh);

    /* We queued the buffer, so don't free it */
    close_buffer_oarchive(&oa, 0);

    LOG_DEBUG(LOGCALLBACK(zh), "Sending request xid=%#x for path [%s] to %s",
              h.xid, path, zoo_get_current_server(zh));

    adaptor_send_queue(zh, 0);

done:
    free_duplicate_path(server_path, path);
    return rc;
}
void zoo_create_op_init(zoo_op_t *op, const char *path, const char *value,
        int valuelen,  const struct ACL_vector *acl, int mode,
        char *path_buffer, int path_buffer_len)
{
    assert(op);
    op->type = get_create_op_type(mode, ZOO_CREATE_OP);
    op->create_op.path = path;
    op->create_op.data = value;
    op->create_op.datalen = valuelen;
    op->create_op.acl = acl;
    op->create_op.flags = mode;
    op->create_op.ttl = 0;
    op->create_op.buf = path_buffer;
    op->create_op.buflen = path_buffer_len;
}

void zoo_create2_op_init(zoo_op_t *op, const char *path, const char *value,
        int valuelen,  const struct ACL_vector *acl, int mode,
        char *path_buffer, int path_buffer_len)
{
    assert(op);
    op->type = get_create_op_type(mode, ZOO_CREATE2_OP);
    op->create_op.path = path;
    op->create_op.data = value;
    op->create_op.datalen = valuelen;
    op->create_op.acl = acl;
    op->create_op.flags = mode;
    op->create_op.buf = path_buffer;
    op->create_op.buflen = path_buffer_len;
}

void zoo_delete_op_init(zoo_op_t *op, const char *path, int version)
{
    assert(op);
    op->type = ZOO_DELETE_OP;
    op->delete_op.path = path;
    op->delete_op.version = version;
}

void zoo_set_op_init(zoo_op_t *op, const char *path, const char *buffer,
        int buflen, int version, struct Stat *stat)
{
    assert(op);
    op->type = ZOO_SETDATA_OP;
    op->set_op.path = path;
    op->set_op.data = buffer;
    op->set_op.datalen = buflen;
    op->set_op.version = version;
    op->set_op.stat = stat;
}

void zoo_check_op_init(zoo_op_t *op, const char *path, int version)
{
    assert(op);
    op->type = ZOO_CHECK_OP;
    op->check_op.path = path;
    op->check_op.version = version;
}

/* specify timeout of 0 to make the function non-blocking */
/* timeout is in milliseconds */
int flush_send_queue(zhandle_t*zh, int timeout)
{
    int rc= ZOK;
    struct timeval started;
#ifdef _WIN32
    fd_set pollSet;
    struct timeval wait;
#endif
    get_system_time(&started);
    // we can't use dequeue_buffer() here because if (non-blocking) send_buffer()
    // returns EWOULDBLOCK we'd have to put the buffer back on the queue.
    // we use a recursive lock instead and only dequeue the buffer if a send was
    // successful
    lock_buffer_list(&zh->to_send);
    while (zh->to_send.head != 0 && (is_connected(zh) || is_sasl_auth_in_progress(zh))) {
        if (is_sasl_auth_in_progress(zh)) {
            // We don't let non-SASL packets escape as long as
            // negotiation is not complete.  (SASL packets are always
            // pushed to the front of the queue.)
            buffer_list_t *buff = zh->to_send.head;
            int32_t type;

            rc = extract_request_type(buff->buffer, buff->len, &type);

            if (rc < 0 || type != ZOO_SASL_OP) {
                break;
            }
        }

        if(timeout!=0){
#ifndef _WIN32
            struct pollfd fds;
#endif
            int elapsed;
            struct timeval now;
            get_system_time(&now);
            elapsed=calculate_interval(&started,&now);
            if (elapsed>timeout) {
                rc = ZOPERATIONTIMEOUT;
                break;
            }

#ifdef _WIN32
            wait = get_timeval(timeout-elapsed);
            FD_ZERO(&pollSet);
            FD_SET(zh->fd->sock, &pollSet);
            // Poll the socket
            rc = select((int)(zh->fd->sock)+1, NULL,  &pollSet, NULL, &wait);
#else
            fds.fd = zh->fd->sock;
            fds.events = POLLOUT;
            fds.revents = 0;
            rc = poll(&fds, 1, timeout-elapsed);
#endif
            if (rc<=0) {
                /* timed out or an error or POLLERR */
                rc = rc==0 ? ZOPERATIONTIMEOUT : ZSYSTEMERROR;
                break;
            }
        }

        rc = send_buffer(zh, zh->to_send.head);
        if(rc==0 && timeout==0){
            /* send_buffer would block while sending this buffer */
            rc = ZOK;
            break;
        }
        if (rc < 0) {
            rc = ZCONNECTIONLOSS;
            break;
        }
        // if the buffer has been sent successfully, remove it from the queue
        if (rc > 0)
            remove_buffer(&zh->to_send);
        get_system_time(&zh->last_send);
        rc = ZOK;
    }
    unlock_buffer_list(&zh->to_send);
    return rc;
}

const char* zerror(int c)
{
    switch (c){
    case ZOK:
      return "ok";
    case ZSYSTEMERROR:
      return "system error";
    case ZRUNTIMEINCONSISTENCY:
      return "run time inconsistency";
    case ZDATAINCONSISTENCY:
      return "data inconsistency";
    case ZCONNECTIONLOSS:
      return "connection loss";
    case ZMARSHALLINGERROR:
      return "marshalling error";
    case ZUNIMPLEMENTED:
      return "unimplemented";
    case ZOPERATIONTIMEOUT:
      return "operation timeout";
    case ZBADARGUMENTS:
      return "bad arguments";
    case ZINVALIDSTATE:
      return "invalid zhandle state";
    case ZNEWCONFIGNOQUORUM:
      return "no quorum of new config is connected and up-to-date with the leader of last commmitted config - try invoking reconfiguration after new servers are connected and synced";
    case ZRECONFIGINPROGRESS:
      return "Another reconfiguration is in progress -- concurrent reconfigs not supported (yet)";
    case ZAPIERROR:
      return "api error";
    case ZNONODE:
      return "no node";
    case ZNOAUTH:
      return "not authenticated";
    case ZBADVERSION:
      return "bad version";
    case  ZNOCHILDRENFOREPHEMERALS:
      return "no children for ephemerals";
    case ZNODEEXISTS:
      return "node exists";
    case ZNOTEMPTY:
      return "not empty";
    case ZSESSIONEXPIRED:
      return "session expired";
    case ZINVALIDCALLBACK:
      return "invalid callback";
    case ZINVALIDACL:
      return "invalid acl";
    case ZAUTHFAILED:
      return "authentication failed";
    case ZCLOSING:
      return "zookeeper is closing";
    case ZNOTHING:
      return "(not error) no server responses to process";
    case ZSESSIONMOVED:
      return "session moved to another server, so operation is ignored";
    case ZNOTREADONLY:
      return "state-changing request is passed to read-only server";
    case ZEPHEMERALONLOCALSESSION:
      return "attempt to create ephemeral node on a local session";
    case ZNOWATCHER:
      return "the watcher couldn't be found";
    case ZRECONFIGDISABLED:
      return "attempts to perform a reconfiguration operation when reconfiguration feature is disable";
    case ZSESSIONCLOSEDREQUIRESASLAUTH:
      return "session closed by server because client is required to do SASL authentication";
    case ZTHROTTLEDOP:
      return "Operation was throttled due to high load";
    }
    if (c > 0) {
      return strerror(c);
    }
    return "unknown error";
}

int zoo_add_auth(zhandle_t *zh,const char* scheme,const char* cert,
        int certLen,void_completion_t completion, const void *data)
{
    struct buffer auth;
    auth_info *authinfo;
    if(scheme==NULL || zh==NULL)
        return ZBADARGUMENTS;

    if (is_unrecoverable(zh))
        return ZINVALIDSTATE;

    // [ZOOKEEPER-800] zoo_add_auth should return ZINVALIDSTATE if
    // the connection is closed.
    if (zoo_state(zh) == 0) {
        return ZINVALIDSTATE;
    }

    if(cert!=NULL && certLen!=0){
        auth.buff=calloc(1,certLen);
        if(auth.buff==0) {
            return ZSYSTEMERROR;
        }
        memcpy(auth.buff,cert,certLen);
        auth.len=certLen;
    } else {
        auth.buff = 0;
        auth.len = 0;
    }

    zoo_lock_auth(zh);
    authinfo = (auth_info*) malloc(sizeof(auth_info));
    authinfo->scheme=strdup(scheme);
    authinfo->auth=auth;
    authinfo->completion=completion;
    authinfo->data=data;
    authinfo->next = NULL;
    add_last_auth(&zh->auth_h, authinfo);
    zoo_unlock_auth(zh);

    if (is_connected(zh) ||
        // When associating, only send info packets if no SASL
        // negotiation is planned.  (Such packets would be queued in
        // front of SASL packets, which is forbidden, and SASL
        // completion is followed by a 'send_auth_info' anyway.)
        (zh->state == ZOO_ASSOCIATING_STATE && !has_sasl_client(zh))) {
        return send_last_auth_info(zh);
    }

    return ZOK;
}

static const char* format_endpoint_info(const struct sockaddr_storage* ep)
{
    static char buf[134] = { 0 };
    char addrstr[INET6_ADDRSTRLEN] = { 0 };
    const char *fmtstring;
    void *inaddr;
    char is_inet6 = 0;  // poor man's boolean
#ifdef _WIN32
    char * addrstring;
#endif
    int port;
    if(ep==0)
        return "null";

#if defined(AF_INET6)
    if(ep->ss_family==AF_INET6){
        inaddr=&((struct sockaddr_in6*)ep)->sin6_addr;
        port=((struct sockaddr_in6*)ep)->sin6_port;
        is_inet6 = 1;
    } else {
#endif
        inaddr=&((struct sockaddr_in*)ep)->sin_addr;
        port=((struct sockaddr_in*)ep)->sin_port;
#if defined(AF_INET6)
    }
#endif
    fmtstring = (is_inet6 ? "[%s]:%d" : "%s:%d");
#ifdef _WIN32
    addrstring = inet_ntoa (*(struct in_addr*)inaddr);
    sprintf(buf,fmtstring,addrstring,ntohs(port));
#else
    inet_ntop(ep->ss_family,inaddr,addrstr,sizeof(addrstr)-1);
    sprintf(buf,fmtstring,addrstr,ntohs(port));
#endif
    return buf;
}

log_callback_fn zoo_get_log_callback(const zhandle_t* zh)
{
    // Verify we have a valid handle
    if (zh == NULL) {
        return NULL;
    }

    return zh->log_callback;
}

void zoo_set_log_callback(zhandle_t *zh, log_callback_fn callback)
{
    // Verify we have a valid handle
    if (zh == NULL) {
        return;
    }

    zh->log_callback = callback;
}

void zoo_deterministic_conn_order(int yesOrNo)
{
    disable_conn_permute=yesOrNo;
}

#ifdef THREADED

static void process_sync_completion(zhandle_t *zh,
        completion_list_t *cptr,
        struct sync_completion *sc,
        struct iarchive *ia)
{
    LOG_DEBUG(LOGCALLBACK(zh), "Processing sync_completion with type=%d xid=%#x rc=%d",
            cptr->c.type, cptr->xid, sc->rc);

    switch(cptr->c.type) {
    case COMPLETION_DATA:
        if (sc->rc==0) {
            struct GetDataResponse res;
            int len;
            deserialize_GetDataResponse(ia, "reply", &res);
            if (res.data.len <= sc->u.data.buff_len) {
                len = res.data.len;
            } else {
                len = sc->u.data.buff_len;
            }
            sc->u.data.buff_len = len;
            // check if len is negative
            // just of NULL which is -1 int
            if (len == -1) {
                sc->u.data.buffer = NULL;
            } else {
                memcpy(sc->u.data.buffer, res.data.buff, len);
            }
            sc->u.data.stat = res.stat;
            deallocate_GetDataResponse(&res);
        }
        break;
    case COMPLETION_STAT:
        if (sc->rc==0) {
            struct SetDataResponse res;
            deserialize_SetDataResponse(ia, "reply", &res);
            sc->u.stat = res.stat;
            deallocate_SetDataResponse(&res);
        }
        break;
    case COMPLETION_STRINGLIST:
        if (sc->rc==0) {
            struct GetChildrenResponse res;
            deserialize_GetChildrenResponse(ia, "reply", &res);
            sc->u.strs2 = res.children;
            /* We don't deallocate since we are passing it back */
            // deallocate_GetChildrenResponse(&res);
        }
        break;
    case COMPLETION_STRINGLIST_STAT:
        if (sc->rc==0) {
            struct GetChildren2Response res;
            deserialize_GetChildren2Response(ia, "reply", &res);
            sc->u.strs_stat.strs2 = res.children;
            sc->u.strs_stat.stat2 = res.stat;
            /* We don't deallocate since we are passing it back */
            // deallocate_GetChildren2Response(&res);
        }
        break;
    case COMPLETION_STRING:
        if (sc->rc==0) {
            struct CreateResponse res;
            int len;
            const char * client_path;
            deserialize_CreateResponse(ia, "reply", &res);
            //ZOOKEEPER-1027
            client_path = sub_string(zh, res.path);
            len = strlen(client_path) + 1;if (len > sc->u.str.str_len) {
                len = sc->u.str.str_len;
            }
            if (len > 0) {
                memcpy(sc->u.str.str, client_path, len - 1);
                sc->u.str.str[len - 1] = '\0';
            }
            free_duplicate_path(client_path, res.path);
            deallocate_CreateResponse(&res);
        }
        break;
    case COMPLETION_STRING_STAT:
        if (sc->rc==0) {
            struct Create2Response res;
            int len;
            const char * client_path;
            deserialize_Create2Response(ia, "reply", &res);
            client_path = sub_string(zh, res.path);
            len = strlen(client_path) + 1;
            if (len > sc->u.str.str_len) {
                len = sc->u.str.str_len;
            }
            if (len > 0) {
                memcpy(sc->u.str.str, client_path, len - 1);
                sc->u.str.str[len - 1] = '\0';
            }
            free_duplicate_path(client_path, res.path);
            sc->u.stat = res.stat;
            deallocate_Create2Response(&res);
        }
        break;
    case COMPLETION_ACLLIST:
        if (sc->rc==0) {
            struct GetACLResponse res;
            deserialize_GetACLResponse(ia, "reply", &res);
            sc->u.acl.acl = res.acl;
            sc->u.acl.stat = res.stat;
            /* We don't deallocate since we are passing it back */
            //deallocate_GetACLResponse(&res);
        }
        break;
    case COMPLETION_VOID:
        break;
    case COMPLETION_MULTI:
        sc->rc = deserialize_multi(zh, cptr->xid, cptr, ia);
        break;
    default:
        LOG_DEBUG(LOGCALLBACK(zh), "Unsupported completion type=%d", cptr->c.type);
        break;
    }
}

/*---------------------------------------------------------------------------*
 * SYNC API
 *---------------------------------------------------------------------------*/
int zoo_create(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl, int mode,
        char *path_buffer, int path_buffer_len)
{
    return zoo_create_ttl(zh, path, value, valuelen, acl, mode, -1,
        path_buffer, path_buffer_len);
}

int zoo_create_ttl(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl, int mode, int64_t ttl,
        char *path_buffer, int path_buffer_len)
{
    struct sync_completion *sc = alloc_sync_completion();
    int rc;
    if (!sc) {
        return ZSYSTEMERROR;
    }
    sc->u.str.str = path_buffer;
    sc->u.str.str_len = path_buffer_len;
    rc=zoo_acreate_ttl(zh, path, value, valuelen, acl, mode, ttl, SYNCHRONOUS_MARKER, sc);
    if(rc==ZOK){
        wait_sync_completion(sc);
        rc = sc->rc;
    }
    free_sync_completion(sc);
    return rc;
}

int zoo_create2(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl, int mode,
        char *path_buffer, int path_buffer_len, struct Stat *stat)
{
    return zoo_create2_ttl(zh, path, value, valuelen, acl, mode, -1,
        path_buffer, path_buffer_len, stat);
}

int zoo_create2_ttl(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl, int mode, int64_t ttl,
        char *path_buffer, int path_buffer_len, struct Stat *stat)
{
    struct sync_completion *sc = alloc_sync_completion();
    int rc;
    if (!sc) {
        return ZSYSTEMERROR;
    }

    sc->u.str.str = path_buffer;
    sc->u.str.str_len = path_buffer_len;
    rc=zoo_acreate2_ttl(zh, path, value, valuelen, acl, mode, ttl, SYNCHRONOUS_MARKER, sc);
    if(rc==ZOK){
        wait_sync_completion(sc);
        rc = sc->rc;
        if (rc == 0 && stat) {
            *stat = sc->u.stat;
        }
    }
    free_sync_completion(sc);
    return rc;
}

int zoo_delete(zhandle_t *zh, const char *path, int version)
{
    struct sync_completion *sc = alloc_sync_completion();
    int rc;
    if (!sc) {
        return ZSYSTEMERROR;
    }
    rc=zoo_adelete(zh, path, version, SYNCHRONOUS_MARKER, sc);
    if(rc==ZOK){
        wait_sync_completion(sc);
        rc = sc->rc;
    }
    free_sync_completion(sc);
    return rc;
}

int zoo_exists(zhandle_t *zh, const char *path, int watch, struct Stat *stat)
{
    return zoo_wexists(zh,path,watch?zh->watcher:0,zh->context,stat);
}

int zoo_wexists(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx, struct Stat *stat)
{
    struct sync_completion *sc = alloc_sync_completion();
    int rc;
    if (!sc) {
        return ZSYSTEMERROR;
    }
    rc=zoo_awexists(zh,path,watcher,watcherCtx,SYNCHRONOUS_MARKER, sc);
    if(rc==ZOK){
        wait_sync_completion(sc);
        rc = sc->rc;
        if (rc == 0&& stat) {
            *stat = sc->u.stat;
        }
    }
    free_sync_completion(sc);
    return rc;
}

int zoo_get(zhandle_t *zh, const char *path, int watch, char *buffer,
        int* buffer_len, struct Stat *stat)
{
    return zoo_wget(zh,path,watch?zh->watcher:0,zh->context,
            buffer,buffer_len,stat);
}

int zoo_wget(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        char *buffer, int* buffer_len, struct Stat *stat)
{
    struct sync_completion *sc;
    int rc=0;

    if(buffer_len==NULL)
        return ZBADARGUMENTS;
    if((sc=alloc_sync_completion())==NULL)
        return ZSYSTEMERROR;

    sc->u.data.buffer = buffer;
    sc->u.data.buff_len = *buffer_len;
    rc=zoo_awget(zh, path, watcher, watcherCtx, SYNCHRONOUS_MARKER, sc);
    if(rc==ZOK){
        wait_sync_completion(sc);
        rc = sc->rc;
        if (rc == 0) {
            if(stat)
                *stat = sc->u.data.stat;
            *buffer_len = sc->u.data.buff_len;
        }
    }
    free_sync_completion(sc);
    return rc;
}

int zoo_getconfig(zhandle_t *zh, int watch, char *buffer,
        int* buffer_len, struct Stat *stat)
{
    return zoo_wget(zh,ZOO_CONFIG_NODE,watch?zh->watcher:0,zh->context, buffer,buffer_len,stat);
}

int zoo_wgetconfig(zhandle_t *zh, watcher_fn watcher, void* watcherCtx,
       char *buffer, int* buffer_len, struct Stat *stat)
{
   return zoo_wget(zh, ZOO_CONFIG_NODE, watcher, watcherCtx, buffer, buffer_len, stat);
}


int zoo_reconfig(zhandle_t *zh, const char *joining, const char *leaving,
       const char *members, int64_t version, char *buffer, int* buffer_len,
       struct Stat *stat)
{
    struct sync_completion *sc;
    int rc=0;

    if(buffer_len==NULL)
        return ZBADARGUMENTS;
    if((sc=alloc_sync_completion())==NULL)
        return ZSYSTEMERROR;

    sc->u.data.buffer = buffer;
    sc->u.data.buff_len = *buffer_len;
    rc=zoo_areconfig(zh, joining, leaving, members, version, SYNCHRONOUS_MARKER, sc);

    if(rc==ZOK){
        wait_sync_completion(sc);
        rc = sc->rc;
        if (rc == 0) {
            if(stat)
                *stat = sc->u.data.stat;
            *buffer_len = sc->u.data.buff_len;
        }
    }
    free_sync_completion(sc);
    return rc;
}

int zoo_set(zhandle_t *zh, const char *path, const char *buffer, int buflen,
        int version)
{
  return zoo_set2(zh, path, buffer, buflen, version, 0);
}

int zoo_set2(zhandle_t *zh, const char *path, const char *buffer, int buflen,
        int version, struct Stat *stat)
{
    struct sync_completion *sc = alloc_sync_completion();
    int rc;
    if (!sc) {
        return ZSYSTEMERROR;
    }
    rc=zoo_aset(zh, path, buffer, buflen, version, SYNCHRONOUS_MARKER, sc);
    if(rc==ZOK){
        wait_sync_completion(sc);
        rc = sc->rc;
        if (rc == 0 && stat) {
            *stat = sc->u.stat;
        }
    }
    free_sync_completion(sc);
    return rc;
}

static int zoo_wget_children_(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        struct String_vector *strings)
{
    struct sync_completion *sc = alloc_sync_completion();
    int rc;
    if (!sc) {
        return ZSYSTEMERROR;
    }
    rc= zoo_awget_children (zh, path, watcher, watcherCtx, SYNCHRONOUS_MARKER, sc);
    if(rc==ZOK){
        wait_sync_completion(sc);
        rc = sc->rc;
        if (rc == 0) {
            if (strings) {
                *strings = sc->u.strs2;
            } else {
                deallocate_String_vector(&sc->u.strs2);
            }
        }
    }
    free_sync_completion(sc);
    return rc;
}

static int zoo_wget_children2_(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        struct String_vector *strings, struct Stat *stat)
{
    struct sync_completion *sc = alloc_sync_completion();
    int rc;
    if (!sc) {
        return ZSYSTEMERROR;
    }
    rc= zoo_awget_children2(zh, path, watcher, watcherCtx, SYNCHRONOUS_MARKER, sc);

    if(rc==ZOK){
        wait_sync_completion(sc);
        rc = sc->rc;
        if (rc == 0) {
            *stat = sc->u.strs_stat.stat2;
            if (strings) {
                *strings = sc->u.strs_stat.strs2;
            } else {
                deallocate_String_vector(&sc->u.strs_stat.strs2);
            }
        }
    }
    free_sync_completion(sc);
    return rc;
}

int zoo_get_children(zhandle_t *zh, const char *path, int watch,
        struct String_vector *strings)
{
    return zoo_wget_children_(zh,path,watch?zh->watcher:0,zh->context,strings);
}

int zoo_wget_children(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        struct String_vector *strings)
{
    return zoo_wget_children_(zh,path,watcher,watcherCtx,strings);
}

int zoo_get_children2(zhandle_t *zh, const char *path, int watch,
        struct String_vector *strings, struct Stat *stat)
{
    return zoo_wget_children2_(zh,path,watch?zh->watcher:0,zh->context,strings,stat);
}

int zoo_wget_children2(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        struct String_vector *strings, struct Stat *stat)
{
    return zoo_wget_children2_(zh,path,watcher,watcherCtx,strings,stat);
}

int zoo_get_acl(zhandle_t *zh, const char *path, struct ACL_vector *acl,
        struct Stat *stat)
{
    struct sync_completion *sc = alloc_sync_completion();
    int rc;
    if (!sc) {
        return ZSYSTEMERROR;
    }
    rc=zoo_aget_acl(zh, path, SYNCHRONOUS_MARKER, sc);
    if(rc==ZOK){
        wait_sync_completion(sc);
        rc = sc->rc;
        if (rc == 0&& stat) {
            *stat = sc->u.acl.stat;
        }
        if (rc == 0) {
            if (acl) {
                *acl = sc->u.acl.acl;
            } else {
                deallocate_ACL_vector(&sc->u.acl.acl);
            }
        }
    }
    free_sync_completion(sc);
    return rc;
}

int zoo_set_acl(zhandle_t *zh, const char *path, int version,
        const struct ACL_vector *acl)
{
    struct sync_completion *sc = alloc_sync_completion();
    int rc;
    if (!sc) {
        return ZSYSTEMERROR;
    }
    rc=zoo_aset_acl(zh, path, version, (struct ACL_vector*)acl,
            SYNCHRONOUS_MARKER, sc);
    if(rc==ZOK){
        wait_sync_completion(sc);
        rc = sc->rc;
    }
    free_sync_completion(sc);
    return rc;
}

static int remove_watches(
    zhandle_t *zh, const char *path, ZooWatcherType wtype,
    watcher_fn watcher, void *wctx, int local, int all)
{
    int rc = 0;
    struct sync_completion *sc;

    if (!path)
        return ZBADARGUMENTS;

    sc = alloc_sync_completion();
    if (!sc)
        return ZSYSTEMERROR;

    rc = aremove_watches(zh, path, wtype, watcher, wctx, local,
                              SYNCHRONOUS_MARKER, sc, all);
    if (rc == ZOK) {
        wait_sync_completion(sc);
        rc = sc->rc;
    }
    free_sync_completion(sc);
    return rc;
}

int zoo_multi(zhandle_t *zh, int count, const zoo_op_t *ops, zoo_op_result_t *results)
{
    int rc;

    struct sync_completion *sc = alloc_sync_completion();
    if (!sc) {
        return ZSYSTEMERROR;
    }

    rc = zoo_amulti(zh, count, ops, results, SYNCHRONOUS_MARKER, sc);
    if (rc == ZOK) {
        wait_sync_completion(sc);
        rc = sc->rc;
    }
    free_sync_completion(sc);

    return rc;
}

int zoo_remove_watches(zhandle_t *zh, const char *path, ZooWatcherType wtype,
         watcher_fn watcher, void *watcherCtx, int local)
{
    return remove_watches(zh, path, wtype, watcher, watcherCtx, local, 0);
}

int zoo_remove_all_watches(
        zhandle_t *zh, const char *path, ZooWatcherType wtype, int local)
{
    return remove_watches(zh, path, wtype, NULL, NULL, local, 1);

}
#endif

int zoo_aremove_watches(zhandle_t *zh, const char *path, ZooWatcherType wtype,
        watcher_fn watcher, void *watcherCtx, int local,
        void_completion_t *completion, const void *data)
{
    return aremove_watches(
        zh, path, wtype, watcher, watcherCtx, local, completion, data, 0);
}

int zoo_aremove_all_watches(zhandle_t *zh, const char *path,
        ZooWatcherType wtype, int local, void_completion_t *completion,
        const void *data)
{
    return aremove_watches(
        zh, path, wtype, NULL, NULL, local, completion, data, 1);
}
