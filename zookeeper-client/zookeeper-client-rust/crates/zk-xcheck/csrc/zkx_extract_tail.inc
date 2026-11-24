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
 * Epilogue appended to the generated zookeeper.c extraction translation unit.
 * Exposes external zkx_*-prefixed FFI entry points wrapping static functions
 * extracted from zookeeper.c.
 */

#include <stdio.h>
#include "zkx_harness.h"

static zsock_t zkx_u_sock = { -1 };
static zhandle_t zkx_u_zh;
static addrvec_t zkx_resolved;

int zkx_is_valid_path(const char *path, int mode)
{
    return isValidPath(path, mode);
}

int zkx_is_sequence(int mode)
{
    return ZOOKEEPER_IS_SEQUENCE(mode) ? 1 : 0;
}

int zkx_count_hosts(const char *hosts)
{
    return count_hosts((char *)hosts);
}

/* Always returns a heap-allocated string; caller must release via zkx_free(). */
char *zkx_prepend(const char *chroot, const char *path)
{
    zhandle_t zh;
    char *r;
    memset(&zh, 0, sizeof(zh));
    zh.chroot = (char *)chroot;
    r = prepend_string(&zh, path);
    return r == path ? strdup(path) : r;
}

/* Always returns a heap-allocated string; caller must release via zkx_free(). */
char *zkx_sub_string(const char *chroot, const char *path)
{
    zhandle_t zh;
    char *r;
    memset(&zh, 0, sizeof(zh));
    zh.chroot = (char *)chroot;
    r = sub_string(&zh, path);
    return r == path ? strdup(path) : r;
}

void zkx_free(void *p)
{
    free(p);
}

long zkx_strtol0(const char *s, size_t *used)
{
    char *end = NULL;
    long v = strtol(s, &end, 0);
    *used = (size_t)(end - s);
    return v;
}

int zkx_long_to_int(long v)
{
    int port = v;
    return port;
}

int zkx_resolve(const char *hosts, int permute)
{
    zhandle_t zh;
    memset(&zh, 0, sizeof(zh));
    addrvec_free(&zkx_resolved);
    disable_conn_permute = permute ? 0 : 1;
    return resolve_hosts(&zh, hosts, &zkx_resolved);
}

unsigned zkx_resolved_count(void)
{
    return zkx_resolved.count;
}

int zkx_resolved_get(unsigned i, unsigned char ip[16], unsigned short *port)
{
    if (i >= zkx_resolved.count)
        return 0;
    return zkx_addr_decode(&zkx_resolved.data[i], ip, port);
}

void zkx_u_reset(void)
{
    addrvec_free(&zkx_u_zh.addrs);
    addrvec_free(&zkx_u_zh.addrs_old);
    addrvec_free(&zkx_u_zh.addrs_new);
    free(zkx_u_zh.hostname);
    memset(&zkx_u_zh, 0, sizeof(zkx_u_zh));
    zkx_u_sock.sock = -1;
    zkx_u_zh.fd = &zkx_u_sock;
}

void zkx_u_set_connected(int connected)
{
    zkx_u_sock.sock = connected ? 3 : -1;
}

int zkx_u_connected(void)
{
    return zkx_u_sock.sock != -1;
}

int zkx_u_update(const char *hosts, int permute)
{
    free(zkx_u_zh.hostname);
    zkx_u_zh.hostname = strdup(hosts);
    disable_conn_permute = permute ? 0 : 1;
    return update_addrs(&zkx_u_zh, NULL);
}

void zkx_u_cycle(void)
{
    zoo_cycle_next_server(&zkx_u_zh);
}

int zkx_u_reconfig(void)
{
    return zkx_u_zh.reconfig;
}

double zkx_u_p_old(void)
{
    return zkx_u_zh.pOld;
}

double zkx_u_p_new(void)
{
    return zkx_u_zh.pNew;
}

int zkx_u_state(void)
{
    return zkx_u_zh.state;
}

static const addrvec_t *zkx_u_vec(int which)
{
    switch (which) {
    case 0: return &zkx_u_zh.addrs;
    case 1: return &zkx_u_zh.addrs_old;
    default: return &zkx_u_zh.addrs_new;
    }
}

unsigned zkx_u_vec_count(int which)
{
    return zkx_u_vec(which)->count;
}

unsigned zkx_u_vec_next(int which)
{
    return zkx_u_vec(which)->next;
}

int zkx_u_vec_get(int which, unsigned i, unsigned char ip[16],
        unsigned short *port)
{
    const addrvec_t *v = zkx_u_vec(which);
    if (i >= v->count)
        return 0;
    return zkx_addr_decode(&v->data[i], ip, port);
}

int zkx_u_cur(unsigned char ip[16], unsigned short *port)
{
    return zkx_addr_decode(&zkx_u_zh.addr_cur, ip, port);
}
