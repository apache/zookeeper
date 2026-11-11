/*
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
 * Cross-validation test harness supporting extracted legacy C client sources:
 *
 * - Deterministic value streams for random() and drand48() so C and Rust
 *   implementations consume identical random sequences;
 * - In-memory capture buffer for LOG_* diagnostic macro invocations;
 * - Offline getaddrinfo() stub recording resolution requests and synthesizing
 *   deterministic socket addresses without network I/O;
 * - FFI driver routines for exercising the legacy C hashtable and addrvec_t.
 *
 * All harness entry points rely on process global state and must be invoked
 * under the Rust harness serialization lock.
 */

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>

#include "addrvec.h"
#include "hashtable/hashtable.h"
#include "hashtable/hashtable_itr.h"
#include "zkx_harness.h"

unsigned long zkx_setup_random_calls = 0;
unsigned long zkx_close_calls = 0;

int zkx_char_is_signed(void)
{
    return ((char)-1) < 0;
}

/* Deterministic PRNG feeds; returns 0 once the configured sequence is exhausted. */

static const long *zkx_rand_vals = NULL;
static size_t zkx_rand_len = 0;
static size_t zkx_rand_pos = 0;
static size_t zkx_rand_calls = 0;

void zkx_set_random_feed(const long *vals, size_t n)
{
    zkx_rand_vals = vals;
    zkx_rand_len = n;
    zkx_rand_pos = 0;
    zkx_rand_calls = 0;
}

size_t zkx_random_calls(void)
{
    return zkx_rand_calls;
}

long zkx_random(void)
{
    zkx_rand_calls++;
    if (zkx_rand_pos >= zkx_rand_len)
        return 0;
    return zkx_rand_vals[zkx_rand_pos++];
}

static const double *zkx_drand_vals = NULL;
static size_t zkx_drand_len = 0;
static size_t zkx_drand_pos = 0;
static size_t zkx_drand_calls = 0;

void zkx_set_drand_feed(const double *vals, size_t n)
{
    zkx_drand_vals = vals;
    zkx_drand_len = n;
    zkx_drand_pos = 0;
    zkx_drand_calls = 0;
}

size_t zkx_drand_calls_get(void)
{
    return zkx_drand_calls;
}

double zkx_drand48(void)
{
    zkx_drand_calls++;
    if (zkx_drand_pos >= zkx_drand_len)
        return 0.0;
    return zkx_drand_vals[zkx_drand_pos++];
}

unsigned long zkx_setup_random_count(void)
{
    return zkx_setup_random_calls;
}

unsigned long zkx_close_count(void)
{
    return zkx_close_calls;
}

/* Diagnostic log capture buffer. */

#define ZKX_LOG_MAX 64
#define ZKX_LOG_LEN 1024

static int zkx_log_n = 0;
static const char *zkx_log_levels[ZKX_LOG_MAX];
static const char *zkx_log_fmts[ZKX_LOG_MAX];
static char zkx_log_msgs[ZKX_LOG_MAX][ZKX_LOG_LEN];

void zkx_log(const char *level, const char *fmt, ...)
{
    va_list ap;
    if (zkx_log_n >= ZKX_LOG_MAX)
        return;
    zkx_log_levels[zkx_log_n] = level;
    zkx_log_fmts[zkx_log_n] = fmt;
    va_start(ap, fmt);
    vsnprintf(zkx_log_msgs[zkx_log_n], ZKX_LOG_LEN, fmt, ap);
    va_end(ap);
    zkx_log_n++;
}

void zkx_log_clear(void)
{
    zkx_log_n = 0;
}

int zkx_log_get(int i, const char **level, const char **fmt, const char **msg)
{
    if (i < 0 || i >= zkx_log_n)
        return 0;
    *level = zkx_log_levels[i];
    *fmt = zkx_log_fmts[i];
    *msg = zkx_log_msgs[i];
    return 1;
}

int zkx_log_count(void)
{
    return zkx_log_n;
}

/* Socket address encoding and decoding helpers. */

int zkx_addr_decode(const struct sockaddr_storage *ss, unsigned char ip[16],
        unsigned short *port)
{
    memset(ip, 0, 16);
    *port = 0;
    if (ss->ss_family == AF_INET) {
        const struct sockaddr_in *a = (const struct sockaddr_in *)ss;
        memcpy(ip, &a->sin_addr, 4);
        *port = ntohs(a->sin_port);
        return 4;
    }
    if (ss->ss_family == AF_INET6) {
        const struct sockaddr_in6 *a = (const struct sockaddr_in6 *)ss;
        memcpy(ip, &a->sin6_addr, 16);
        *port = ntohs(a->sin6_port);
        return 6;
    }
    return 0;
}

void zkx_addr_encode(struct sockaddr_storage *ss, int family,
        const unsigned char *ip, unsigned short port)
{
    memset(ss, 0, sizeof(*ss));
    if (family == 6) {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)ss;
        a->sin6_family = AF_INET6;
        memcpy(&a->sin6_addr, ip, 16);
        a->sin6_port = htons(port);
    } else {
        struct sockaddr_in *a = (struct sockaddr_in *)ss;
        a->sin_family = AF_INET;
        memcpy(&a->sin_addr, ip, 4);
        a->sin_port = htons(port);
    }
}

/*
 * Deterministic getaddrinfo() stub. Numeric IPv4/IPv6 literals parse directly
 * to their corresponding address; symbolic hostnames map to 192.0.2.N where N
 * derives from the invocation index. Service strings are parsed as numeric port
 * numbers, and every query is logged for inspection.
 */

#define ZKX_GAI_MAX 256
#define ZKX_GAI_LEN 512

static int zkx_gai_n = 0;
static char zkx_gai_hosts[ZKX_GAI_MAX][ZKX_GAI_LEN];
static char zkx_gai_services[ZKX_GAI_MAX][ZKX_GAI_LEN];

void zkx_gai_clear(void)
{
    zkx_gai_n = 0;
}

int zkx_gai_count(void)
{
    return zkx_gai_n;
}

int zkx_gai_get(int i, const char **host, const char **service)
{
    if (i < 0 || i >= zkx_gai_n)
        return 0;
    *host = zkx_gai_hosts[i];
    *service = zkx_gai_services[i];
    return 1;
}

int zkx_getaddrinfo(const char *node, const char *service,
        const struct addrinfo *hints, struct addrinfo **res)
{
    struct addrinfo *ai;
    struct sockaddr_storage *ss;
    unsigned char ip[16];
    unsigned short port;
    int family = 4;
    int call = zkx_gai_n;
    (void)hints;
    if (zkx_gai_n < ZKX_GAI_MAX) {
        snprintf(zkx_gai_hosts[zkx_gai_n], ZKX_GAI_LEN, "%s", node ? node : "");
        snprintf(zkx_gai_services[zkx_gai_n], ZKX_GAI_LEN, "%s",
                service ? service : "");
        zkx_gai_n++;
    }
    port = (unsigned short)strtol(service ? service : "0", NULL, 0);
    memset(ip, 0, sizeof(ip));
    if (node && inet_pton(AF_INET, node, ip) == 1) {
        family = 4;
    } else if (node && inet_pton(AF_INET6, node, ip) == 1) {
        family = 6;
    } else {
        ip[0] = 192;
        ip[1] = 0;
        ip[2] = 2;
        ip[3] = (unsigned char)(1 + call % 250);
    }
    ai = calloc(1, sizeof(*ai));
    ss = calloc(1, sizeof(*ss));
    if (!ai || !ss) {
        free(ai);
        free(ss);
        return EAI_MEMORY;
    }
    zkx_addr_encode(ss, family, ip, port);
    ai->ai_family = family == 6 ? AF_INET6 : AF_INET;
    ai->ai_socktype = SOCK_STREAM;
    ai->ai_protocol = IPPROTO_TCP;
    ai->ai_addr = (struct sockaddr *)ss;
    ai->ai_addrlen = family == 6 ? sizeof(struct sockaddr_in6)
                                 : sizeof(struct sockaddr_in);
    *res = ai;
    return 0;
}

void zkx_freeaddrinfo(struct addrinfo *res)
{
    while (res) {
        struct addrinfo *next = res->ai_next;
        free(res->ai_addr);
        free(res);
        res = next;
    }
}

/* Raw C hashtable driver mapping NUL-terminated string keys to integer values. */

unsigned int zkx_djb2_signed(void *key);
unsigned int zkx_djb2_unsigned(void *key);

static int zkx_str_eq(void *a, void *b)
{
    return strcmp((const char *)a, (const char *)b) == 0;
}

void *zkx_ht_new(unsigned minsize, int unsigned_char)
{
    return create_hashtable(minsize,
            unsigned_char ? zkx_djb2_unsigned : zkx_djb2_signed, zkx_str_eq);
}

void zkx_ht_free(void *h)
{
    if (h)
        hashtable_destroy((struct hashtable *)h, 1);
}

int zkx_ht_insert(void *h, const char *key, int value)
{
    char *k = strdup(key);
    int *v = malloc(sizeof(int));
    if (!k || !v) {
        free(k);
        free(v);
        return 0;
    }
    *v = value;
    if (!hashtable_insert((struct hashtable *)h, k, v)) {
        free(k);
        free(v);
        return 0;
    }
    return 1;
}

int zkx_ht_search(void *h, const char *key, int *out)
{
    int *v = hashtable_search((struct hashtable *)h, (void *)key);
    if (!v)
        return 0;
    *out = *v;
    return 1;
}

int zkx_ht_remove(void *h, const char *key, int *out)
{
    int *v = hashtable_remove((struct hashtable *)h, (void *)key);
    if (!v)
        return 0;
    *out = *v;
    free(v);
    return 1;
}

unsigned zkx_ht_count(void *h)
{
    return hashtable_count((struct hashtable *)h);
}

unsigned zkx_ht_table_length(void *h)
{
    return ((struct hashtable *)h)->tablelength;
}

unsigned zkx_ht_load_limit(void *h)
{
    return ((struct hashtable *)h)->loadlimit;
}

unsigned zkx_ht_hash(void *h, const char *key)
{
    return hash((struct hashtable *)h, (void *)key);
}

/*
 * Exports table keys and values in bucket iteration order. Returned key
 * pointers remain valid until the table is mutated or destroyed. Returns the
 * total number of entries in the table.
 */
int zkx_ht_dump(void *h, const char **keys, int *vals, int cap)
{
    struct hashtable *t = (struct hashtable *)h;
    struct hashtable_itr *it;
    int n = 0;
    if (hashtable_count(t) == 0)
        return 0;
    it = hashtable_iterator(t);
    do {
        if (n < cap) {
            keys[n] = hashtable_iterator_key(it);
            vals[n] = *(int *)hashtable_iterator_value(it);
        }
        n++;
    } while (hashtable_iterator_advance(it));
    free(it);
    return n;
}

/* Address vector (addrvec_t) driver operating on packed IPv4/port endpoints. */

static addrvec_t zkx_av;

static void zkx_av_item(struct sockaddr_storage *ss, unsigned ip,
        unsigned short port)
{
    unsigned char b[4];
    b[0] = (unsigned char)(ip >> 24);
    b[1] = (unsigned char)(ip >> 16);
    b[2] = (unsigned char)(ip >> 8);
    b[3] = (unsigned char)ip;
    zkx_addr_encode(ss, 4, b, port);
}

void zkx_av_clear(void)
{
    addrvec_free(&zkx_av);
}

int zkx_av_append(unsigned ip, unsigned short port)
{
    struct sockaddr_storage ss;
    zkx_av_item(&ss, ip, port);
    return addrvec_append(&zkx_av, &ss);
}

static unsigned zkx_av_ip_of(const struct sockaddr_storage *ss, int *family,
        unsigned short *port)
{
    unsigned char ip[16];
    *family = zkx_addr_decode(ss, ip, port);
    return ((unsigned)ip[0] << 24) | ((unsigned)ip[1] << 16)
        | ((unsigned)ip[2] << 8) | (unsigned)ip[3];
}

/* Advances the cursor and returns the address family (4 for IPv4, 0 if empty). */
int zkx_av_next(unsigned *ip, unsigned short *port)
{
    struct sockaddr_storage ss;
    int family;
    addrvec_next(&zkx_av, &ss);
    *ip = zkx_av_ip_of(&ss, &family, port);
    return family;
}

int zkx_av_peek(unsigned *ip, unsigned short *port)
{
    struct sockaddr_storage ss;
    int family;
    addrvec_peek(&zkx_av, &ss);
    *ip = zkx_av_ip_of(&ss, &family, port);
    return family;
}

int zkx_av_hasnext(void)
{
    return addrvec_hasnext(&zkx_av);
}

int zkx_av_atend(void)
{
    return addrvec_atend(&zkx_av);
}

unsigned zkx_av_cursor(void)
{
    return zkx_av.next;
}

unsigned zkx_av_count(void)
{
    return zkx_av.count;
}

int zkx_av_get(unsigned i, unsigned *ip, unsigned short *port)
{
    int family;
    if (i >= zkx_av.count)
        return 0;
    *ip = zkx_av_ip_of(&zkx_av.data[i], &family, port);
    return family;
}

void zkx_av_shuffle(void)
{
    addrvec_shuffle(&zkx_av);
}

static void zkx_av_build(addrvec_t *v, const unsigned *ips,
        const unsigned short *ports, unsigned n)
{
    unsigned i;
    addrvec_init(v);
    for (i = 0; i < n; i++) {
        struct sockaddr_storage ss;
        zkx_av_item(&ss, ips[i], ports[i]);
        addrvec_append(v, &ss);
    }
}

int zkx_av_eq(const unsigned *a_ips, const unsigned short *a_ports,
        unsigned na, const unsigned *b_ips, const unsigned short *b_ports,
        unsigned nb)
{
    addrvec_t a;
    addrvec_t b;
    int r;
    zkx_av_build(&a, a_ips, a_ports, na);
    zkx_av_build(&b, b_ips, b_ports, nb);
    r = addrvec_eq(&a, &b);
    addrvec_free(&a);
    addrvec_free(&b);
    return r;
}
