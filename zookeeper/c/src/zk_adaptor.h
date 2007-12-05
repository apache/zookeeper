/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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
#include <pthread.h>
#endif
#include "zookeeper.h"
struct _buffer_list;
struct _completion_list;

typedef struct _buffer_head {
    struct _buffer_list *head;
    struct _buffer_list *last;
#ifdef THREADED
    pthread_mutex_t lock;
#endif
} buffer_head_t;

typedef struct _completion_head {
    struct _completion_list *head;
    struct _completion_list *last;
#ifdef THREADED
    pthread_cond_t cond;
    pthread_mutex_t lock;
#endif
} completion_head_t;

void lock_buffer_list(buffer_head_t *l);
void unlock_buffer_list(buffer_head_t *l);
void lock_completion_list(completion_head_t *l);
void unlock_completion_list(completion_head_t *l);

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
        struct String_vector strs;
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

typedef struct _nesting_level {
    int level;
#ifdef THREADED
    pthread_mutex_t lock;
#endif
} nesting_level_t;

int inc_nesting_level(nesting_level_t* nl,int i);

typedef enum {TOP_LEVEL=0, NESTED=1, CLOSE_REQUESTED=2} nested_state;

 struct prime_struct {
     int32_t len;
     int32_t protocolVersion;
     int32_t timeOut;
     int64_t sessionId;
     int32_t passwd_len;
     char passwd[16];
 }; /* the connect response */

/**
 * This structure represents the connection to zookeeper.
 */

struct _zhandle {
    int fd; /* the descriptor used to talk to zookeeper */
    char *hostname; /* the hostname of zookeeper */
    struct sockaddr *addrs; /* the addresses that correspond to the hostname */
    int addrs_count; /* The number of addresses in the addrs array */
    watcher_fn watcher; /* the registered watcher */
    struct timeval last_recv; /* The time (in seconds) that the last message was received */
    int recv_timeout; /* The maximum amount of time that can go by without 
     receiving anything from the zookeeper server */
    buffer_list_t *input_buffer; /* the current buffer being read in */
    buffer_head_t to_process; /* The buffers that have been read and are ready to be processed. */
    buffer_head_t to_send; /* The packets queued to send */
    completion_head_t sent_requests; /* The outstanding requests */
    completion_head_t completions_to_process; /* completions that are ready to run */
    int connect_index; /* The index of the address to connect to */
    clientid_t client_id;
    long long last_zxid;
    int outstanding_sync; /* Number of outstanding synchronous requests */
    struct _buffer_list primer_buffer; /* The buffer used for the handshake at the start of a connection */
    struct prime_struct primer_storage; /* the connect response */
    char primer_storage_buffer[40]; /* the true size of primer_storage */
    int state;
    void *context;
    struct _auth_info auth; /* authentication data */
    /* zookeeper_close is not reentrant because it de-allocates the zhandler. 
     * This guard variable is used to defer the destruction of zhandle till 
     * right before top-level API call returns to the caller */
    nesting_level_t nesting;
    int close_requested;
    void *adaptor_priv;
};

int adaptor_init(zhandle_t *zh);
void adaptor_finish(zhandle_t *zh);
struct sync_completion *alloc_sync_completion(void);
int wait_sync_completion(struct sync_completion *sc);
void free_sync_completion(struct sync_completion *sc);
void notify_sync_completion(struct sync_completion *sc);
int adaptor_send_queue(zhandle_t *zh, int timeout);
int process_async(int outstanding_sync);
void process_completions(zhandle_t *zh);
void api_prolog(zhandle_t* zh);
int api_epilog(zhandle_t *zh, int rc);
#ifdef THREADED
#else

#endif
#endif /*ZK_ADAPTOR_H_*/

