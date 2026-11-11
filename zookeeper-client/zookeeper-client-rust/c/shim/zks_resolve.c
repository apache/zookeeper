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
 * DNS hostname resolution and network endpoint formatting.
 *
 * Implements POSIX getaddrinfo resolution routines supporting dual-stack IPv4/IPv6,
 * robust fallback handling for platform-specific flags (e.g. AI_ADDRCONFIG), and conversion
 * between system sockaddr structures and FFI zkrs_sockaddr_view representations.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE /* POSIX names under -std=c99 on glibc */
#endif
#include "zks_internal.h"

#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>

/* BSD-derived platforms include a length field (sa_len) in socket address headers. */
#if defined(__APPLE__) || defined(__FreeBSD__) || defined(__NetBSD__) || defined(__OpenBSD__) || \
    defined(__DragonFly__)
#define ZKS_HAVE_SA_LEN 1
#endif

/* Maps getaddrinfo error return codes to equivalent errno constants matching legacy behavior. */
static int zks_gai_errno(int rc)
{
    switch (rc) {
    case EAI_NONAME:
/* ZOOKEEPER-1323: FreeBSD deprecates EAI_NODATA and EAI_ADDRFAMILY. */
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

/*
 * Fallback retry predicate for getaddrinfo implementations where combining AI_ADDRCONFIG
 * with AF_UNSPEC returns errors when certain address families lack global routing.
 */
#ifdef AI_ADDRCONFIG
static int zks_gai_retry(int rc)
{
#ifdef EAI_ADDRFAMILY
    return rc == EAI_BADFLAGS || rc == EAI_ADDRFAMILY;
#else
    return rc == EAI_BADFLAGS;
#endif
}
#endif

/* Serializes an OS sockaddr structure into an FFI sockaddr view (supporting IPv4 and IPv6). */
static int zks_view_from_sockaddr(const struct sockaddr *sa, zkrs_sockaddr_view *v)
{
    memset(v, 0, sizeof(*v));
    if (sa->sa_family == AF_INET) {
        const struct sockaddr_in *in = (const struct sockaddr_in *)(const void *)sa;
        v->family = ZKRS_AF_INET;
        v->port = ntohs(in->sin_port);
        memcpy(v->addr, &in->sin_addr, 4);
        return 1;
    }
    if (sa->sa_family == AF_INET6) {
        const struct sockaddr_in6 *in6 = (const struct sockaddr_in6 *)(const void *)sa;
        v->family = ZKRS_AF_INET6;
        v->port = ntohs(in6->sin6_port);
        memcpy(v->addr, &in6->sin6_addr, 16);
        /* Preserve native field representation expected by the Rust sockaddr view converter. */
        v->flowinfo = in6->sin6_flowinfo;
        v->scope_id = in6->sin6_scope_id;
        return 1;
    }
    return 0;
}

/* Deserializes an FFI sockaddr view into a libc sockaddr_storage buffer. */
static socklen_t zks_sockaddr_from_view(const zkrs_sockaddr_view *v, struct sockaddr_storage *ss)
{
    memset(ss, 0, sizeof(*ss));
    if (v->family == ZKRS_AF_INET) {
        struct sockaddr_in *in = (struct sockaddr_in *)(void *)ss;
#ifdef ZKS_HAVE_SA_LEN
        in->sin_len = (uint8_t)sizeof(*in);
#endif
        in->sin_family = AF_INET;
        in->sin_port = htons(v->port);
        memcpy(&in->sin_addr, v->addr, 4);
        return (socklen_t)sizeof(*in);
    }
    if (v->family == ZKRS_AF_INET6) {
        struct sockaddr_in6 *in6 = (struct sockaddr_in6 *)(void *)ss;
#ifdef ZKS_HAVE_SA_LEN
        in6->sin6_len = (uint8_t)sizeof(*in6);
#endif
        in6->sin6_family = AF_INET6;
        in6->sin6_port = htons(v->port);
        memcpy(&in6->sin6_addr, v->addr, 16);
        in6->sin6_flowinfo = v->flowinfo;
        in6->sin6_scope_id = v->scope_id;
        return (socklen_t)sizeof(*in6);
    }
    return 0;
}

/*
 * Vtable callback invoked by Rust drivers to perform host resolution. Executes getaddrinfo
 * with AI_ADDRCONFIG retry logic, feeding resolved endpoints to the sink callback.
 */
ZKS_HIDDEN int zks_vt_resolve(const char *name, const char *port, int32_t *gai_rc,
                              void (*push)(void *sink, const zkrs_sockaddr_view *addr),
                              void (*skip)(void *sink, int32_t family), void *sink)
{
    struct addrinfo hints, *res, *res0 = NULL;
    zkrs_sockaddr_view v;
    int saved = zks_in_vtable;
    int rc;

    zks_in_vtable = 1;
    memset(&hints, 0, sizeof(hints));
#ifdef AI_ADDRCONFIG
    hints.ai_flags = AI_ADDRCONFIG;
#else
    hints.ai_flags = 0;
#endif
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    rc = getaddrinfo(name, port, &hints, &res0);
#ifdef AI_ADDRCONFIG
    if (hints.ai_flags == AI_ADDRCONFIG && zks_gai_retry(rc)) {
        hints.ai_flags = 0;
        rc = getaddrinfo(name, port, &hints, &res0);
    }
#endif
    if (rc != 0) {
        *gai_rc = rc;
        zks_in_vtable = saved;
        return zks_gai_errno(rc);
    }

    for (res = res0; res != NULL; res = res->ai_next) {
        if (res->ai_addr != NULL && zks_view_from_sockaddr(res->ai_addr, &v)) {
            push(sink, &v);
        } else {
            skip(sink, (int32_t)res->ai_family);
        }
    }
    freeaddrinfo(res0);
    *gai_rc = 0;
    zks_in_vtable = saved;
    return 0;
}

/*
 * Formats a socket address view into a human readable host:port string using thread-local
 * scratch storage, supporting IPv6 bracket formatting.
 */
ZKS_HIDDEN const char *zks_format_endpoint(const zkrs_sockaddr_view *v)
{
    static ZKS_THREAD_LOCAL char buf[134];
    char addrstr[INET6_ADDRSTRLEN] = {0};
    struct sockaddr_storage ss;
    const void *inaddr;
    int af;
    int is_inet6 = 0;

    if (v == NULL) {
        return "null";
    }
    zks_sockaddr_from_view(v, &ss);
    if (v->family == ZKRS_AF_INET6) {
        af = AF_INET6;
        inaddr = &((const struct sockaddr_in6 *)(const void *)&ss)->sin6_addr;
        is_inet6 = 1;
    } else {
        af = v->family == ZKRS_AF_INET ? AF_INET : 0;
        inaddr = &((const struct sockaddr_in *)(const void *)&ss)->sin_addr;
    }
    inet_ntop(af, inaddr, addrstr, sizeof(addrstr) - 1);
    snprintf(buf, sizeof(buf), is_inet6 ? "[%s]:%d" : "%s:%d", addrstr, (int)v->port);
    return buf;
}

struct sockaddr *zookeeper_get_connected_host(zhandle_t *zh, struct sockaddr *addr,
                                              socklen_t *addr_len)
{
    zkrs_sockaddr_view v;
    struct sockaddr_storage ss;
    socklen_t n;

    if (zh == NULL || addr == NULL || addr_len == NULL) {
        return NULL;
    }
    ZKS_REGISTRY_CHECK(zh, __func__);
    /* Peer address is queryable only while in the CONNECTED state. */
    if (ZKS_CALL(zkrs_peer_addr(zh->core, &v)) != 0) {
        return NULL;
    }
    n = zks_sockaddr_from_view(&v, &ss);
    if (n == 0) {
        return NULL;
    }
    /* Copies up to the requested buffer size and reports actual size. */
    memcpy(addr, &ss, *addr_len < n ? *addr_len : n);
    *addr_len = n;
    return addr;
}
