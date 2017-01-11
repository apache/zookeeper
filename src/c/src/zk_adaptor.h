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

#ifndef ZK_ADAPTOR_H_
#define ZK_ADAPTOR_H_
#include <zookeeper.jute.h>
#ifdef THREADED
#ifndef WIN32
#include <pthread.h>
#else
#include "winport.h"
#endif
#endif
#include "zookeeper.h"
#include "zk_hashtable.h"
#include "addrvec.h"

/* predefined xid's values recognized as special by the server */
#define WATCHER_EVENT_XID -1 
#define PING_XID -2
#define AUTH_XID -4
#define SET_WATCHES_XID -8

/* zookeeper state constants */
#define EXPIRED_SESSION_STATE_DEF -112
#define AUTH_FAILED_STATE_DEF -113
#define CONNECTING_STATE_DEF 1
#define ASSOCIATING_STATE_DEF 2
#define CONNECTED_STATE_DEF 3
#define READONLY_STATE_DEF 5
#define NOTCONNECTED_STATE_DEF 999

/* zookeeper event type constants */
#define CREATED_EVENT_DEF 1
#define DELETED_EVENT_DEF 2
#define CHANGED_EVENT_DEF 3
#define CHILD_EVENT_DEF 4
#define SESSION_EVENT_DEF -1
#define NOTWATCHING_EVENT_DEF -2

#ifdef __cplusplus
extern "C" {
#endif

struct _buffer_list;
struct _completion_list;

typedef struct _buffer_head {
    struct _buffer_list *volatile head;
    struct _buffer_list *last;
#ifdef THREADED
    pthread_mutex_t lock;
#endif
} buffer_head_t;

typedef struct _completion_head {
    struct _completion_list *volatile head;
    struct _completion_list *last;
#ifdef THREADED
    pthread_cond_t cond;
    pthread_mutex_t lock;
#endif
} completion_head_t;

int lock_buffer_list(buffer_head_t *l);
int unlock_buffer_list(buffer_head_t *l);
int lock_completion_list(completion_head_t *l);
int unlock_completion_list(completion_head_t *l);

struct sync_completion {
    int rc;
    union {
        struct {
            char *str;
            int str_len;
        } str;
        struct Stat stat;
        struct {
            char *buffer;
            int buff_len;
            struct Stat stat;
        } data;
        struct {
            struct ACL_vector acl;
            struct Stat stat;
        } acl;
        struct String_vector strs2;
        struct {
            struct String_vector strs2;
            struct Stat stat2;
        } strs_stat;
    } u;
    int complete;
#ifdef THREADED
    pthread_cond_t cond;
    pthread_mutex_t lock;
#endif
};

typedef struct _auth_info {
    int state; /* 0=>inactive, >0 => active */
    char* scheme;
    struct buffer auth;
    void_completion_t completion;
    const char* data;
    struct _auth_info *next;
} auth_info;

/**
 * This structure represents a packet being read or written.
 */
typedef struct _buffer_list {
    char *buffer;
    int len; /* This represents the length of sizeof(header) + length of buffer */
    int curr_offset; /* This is the offset into the header followed by offset into the buffer */
    struct _buffer_list *next;
} buffer_list_t;

/* the size of connect request */
#define HANDSHAKE_REQ_SIZE 45
/* connect request */
struct connect_req {
    int32_t protocolVersion;
    int64_t lastZxidSeen;
    int32_t timeOut;
    int64_t sessionId;
    int32_t passwd_len;
    char passwd[16];
    char readOnly;
};

/* the connect response */
struct prime_struct {
    int32_t len;
    int32_t protocolVersion;
    int32_t timeOut;
    int64_t sessionId;
    int32_t passwd_len;
    char passwd[16];
    char readOnly;
}; 

#ifdef THREADED
/* this is used by mt_adaptor internally for thread management */
struct adaptor_threads {
     pthread_t io;
     pthread_t completion;
     int threadsToWait;             // barrier
     pthread_cond_t cond;           // barrier's conditional
     pthread_mutex_t lock;          // ... and a lock
     pthread_mutex_t zh_lock;       // critical section lock
     pthread_mutex_t reconfig_lock; // lock for reconfiguring cluster's ensemble
#ifdef WIN32
     SOCKET self_pipe[2];
#else
     int self_pipe[2];
#endif
};
#endif

/** the auth list for adding auth */
typedef struct _auth_list_head {
     auth_info *auth;
#ifdef THREADED
     pthread_mutex_t lock;
#endif
} auth_list_head_t;

/**
 * This structure represents the connection to zookeeper.
 */
struct _zhandle {
#ifdef WIN32
    SOCKET fd;                          // the descriptor used to talk to zookeeper
#else
    int fd;                             // the descriptor used to talk to zookeeper
#endif

    // Hostlist and list of addresses
    char *hostname;                     // hostname contains list of zookeeper servers to connect to
    struct sockaddr_storage addr_cur;   // address of server we're currently connecting/connected to 
    struct sockaddr_storage addr_rw_server; // address of last known read/write server found.

    addrvec_t addrs;                    // current list of addresses we're connected to
    addrvec_t addrs_old;                // old list of addresses that we are no longer connected to
    addrvec_t addrs_new;                // new list of addresses to connect to if we're reconfiguring

    int reconfig;                       // Are we in the process of reconfiguring cluster's ensemble
    double pOld, pNew;                  // Probability for selecting between 'addrs_old' and 'addrs_new'
    int delay;
    int disable_reconnection_attempt;   // When set, client will not try reconnect to a different server in
                                        // server list. This makes a sticky server for client, and is useful
                                        // for testing if a sticky server is required, or if client wants to
                                        // explicitly shuffle server by calling zoo_cycle_next_server.
                                        // The default value is 0.

    watcher_fn watcher;                 // the registered watcher

    // Message timings
    struct timeval last_recv;           // time last message was received
    struct timeval last_send;           // time last message was sent
    struct timeval last_ping;           // time last PING was sent
    struct timeval next_deadline;       // time of the next deadline
    int recv_timeout;                   // max receive timeout for messages from server

    // Buffers
    buffer_list_t *input_buffer;        // current buffer being read in
    buffer_head_t to_process;           // buffers that have been read and ready to be processed
    buffer_head_t to_send;              // packets queued to send
    completion_head_t sent_requests;    // outstanding requests
    completion_head_t completions_to_process; // completions that are ready to run
    int outstanding_sync;               // number of outstanding synchronous requests

    /* read-only mode specific fields */
    struct timeval last_ping_rw; /* The last time we checked server for being r/w */
    int ping_rw_timeout; /* The time that can go by before checking next server */

    // State info
    volatile int state;                 // Current zookeeper state
    void *context;                      // client-side provided context
    clientid_t client_id;               // client-id
    long long last_zxid;                // last zookeeper ID
    auth_list_head_t auth_h;            // authentication data list
    log_callback_fn log_callback;       // Callback for logging (falls back to logging to stderr)
    int io_count;			// counts the number of iterations of do_io

    // Primer storage
    struct _buffer_list primer_buffer;  // The buffer used for the handshake at the start of a connection
    struct prime_struct primer_storage; // the connect response
    char primer_storage_buffer[41];     // the true size of primer_storage

    /* zookeeper_close is not reentrant because it de-allocates the zhandler. 
     * This guard variable is used to defer the destruction of zhandle till 
     * right before top-level API call returns to the caller */
    int32_t ref_counter;
    volatile int close_requested;
    void *adaptor_priv;

    /* Used for debugging only: non-zero value indicates the time when the zookeeper_process
     * call returned while there was at least one unprocessed server response 
     * available in the socket recv buffer */
    struct timeval socket_readable;

    // Watchers
    zk_hashtable* active_node_watchers;   
    zk_hashtable* active_exist_watchers;
    zk_hashtable* active_child_watchers;

    /** used for chroot path at the client side **/
    char *chroot;

    /** Indicates if this client is allowed to go to r/o mode */
    char allow_read_only;
    /** Indicates if we connected to a majority server before */
    char seen_rw_server_before;
};


int adaptor_init(zhandle_t *zh);
void adaptor_finish(zhandle_t *zh);
void adaptor_destroy(zhandle_t *zh);
#if THREADED
struct sync_completion *alloc_sync_completion(void);
int wait_sync_completion(struct sync_completion *sc);
void free_sync_completion(struct sync_completion *sc);
void notify_sync_completion(struct sync_completion *sc);
#endif
int adaptor_send_queue(zhandle_t *zh, int timeout);
int process_async(int outstanding_sync);
void process_completions(zhandle_t *zh);
int flush_send_queue(zhandle_t*zh, int timeout);
char* sub_string(zhandle_t *zh, const char* server_path);
void free_duplicate_path(const char* free_path, const char* path);
int zoo_lock_auth(zhandle_t *zh);
int zoo_unlock_auth(zhandle_t *zh);

// ensemble reconfigure access guards
int lock_reconfig(struct _zhandle *zh);
int unlock_reconfig(struct _zhandle *zh);

// critical section guards
int enter_critical(zhandle_t* zh);
int leave_critical(zhandle_t* zh);

// zhandle object reference counting
void api_prolog(zhandle_t* zh);
int api_epilog(zhandle_t *zh, int rc);
int32_t get_xid();

// returns the new value of the ref counter
int32_t inc_ref_counter(zhandle_t* zh,int i);

#ifdef THREADED
// atomic post-increment
int32_t fetch_and_add(volatile int32_t* operand, int incr);
// in mt mode process session event asynchronously by the completion thread
#define PROCESS_SESSION_EVENT(zh,newstate) queue_session_event(zh,newstate)
#else
// in single-threaded mode process session event immediately
//#define PROCESS_SESSION_EVENT(zh,newstate) deliverWatchers(zh,ZOO_SESSION_EVENT,newstate,0)
#define PROCESS_SESSION_EVENT(zh,newstate) queue_session_event(zh,newstate)
#endif

#ifdef __cplusplus
}
#endif

#endif /*ZK_ADAPTOR_H_*/


