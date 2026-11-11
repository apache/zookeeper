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
 * Logging Subsystem.
 *
 * This module manages diagnostic log message formatting, destination routing,
 * and dynamic filtering levels to mirror the legacy C client behavior (zk_log.c).
 * It bridges log events originating both from the Rust driver core and native C shim
 * call sites into formatted output matching the ZooKeeper C client log lines.
 *
 * Log messages follow the standard legacy line format, incorporating timestamp, process
 * ID, thread ID, severity level, call site (function and line number), and the formatted
 * message payload. Formatting buffers are allocated as thread local storage across both
 * single threaded and multithreaded builds to eliminate lock contention during log emission.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE /* POSIX names under -std=c99 on glibc */
#endif
#include "zks_internal.h"

#include <netdb.h>
#include <stdarg.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

/*
 * Format strings processed in this unit originate dynamically from precompiled
 * call-site tables or external callers of log_message rather than inline string
 * literals. To satisfy compiler hardening flags such as -Wformat=2, this warning
 * is suppressed specifically for this translation unit.
 */
#if defined(__GNUC__)
#pragma GCC diagnostic ignored "-Wformat-nonliteral"
#endif

#define ZKS_TIME_BUF_SIZE 1024
#define ZKS_LOG_BUF_SIZE 4096

/* Maximum allowable byte length for the header prefix (mirroring legacy sizing constraints). */
#ifdef THREADED
#define ZKS_LOG_HDR_SIZE (ZKS_LOG_BUF_SIZE - 1)
#else
#define ZKS_LOG_HDR_SIZE ZKS_LOG_BUF_SIZE
#endif

static ZKS_THREAD_LOCAL char zks_log_buf[ZKS_LOG_BUF_SIZE];
static ZKS_THREAD_LOCAL char zks_time_buf[ZKS_TIME_BUF_SIZE];
/* Buffer holding the non-prefixed message body before merging into the formatted log line. */
static ZKS_THREAD_LOCAL char zks_log_body[ZKS_LOG_BUF_SIZE];

/* ------------------------------------------------------------------------
 * Call Site Tables and Line Number Mapping.
 *
 * Each instrumentation call site preserves the function name, source line number,
 * and printf format specifier recorded in the ABI baseline specification.
 * ---------------------------------------------------------------------- */

#if defined(__clang__)
#define ZKS_SITE_LINE(line, line_end) (line_end)
#else
#define ZKS_SITE_LINE(line, line_end) (line)
#endif

struct zks_site {
    const char *func;
    int line;
    const char *fmt;
};

struct zks_c_site_info {
    ZooLogLevel level;
    const char *func;
    int line;
    const char *fmt;
};

/*
 * Static table of core log sites generated from zk_proto::LogSite definitions,
 * keyed sequentially by site index.
 */
#define ZKS_CORE_SITE_ROW(name, level, func, line, line_end, fmt) \
    {func, ZKS_SITE_LINE(line, line_end), fmt},

static const struct zks_site zks_core_sites[] = {ZKS_CORE_SITES(ZKS_CORE_SITE_ROW)};

#define ZKS_CORE_SITE_COUNT (sizeof(zks_core_sites) / sizeof(zks_core_sites[0]))

#define ZKS_C_SITE_ROW(name, level, func, line, line_end, fmt) \
    {level, func, ZKS_SITE_LINE(line, line_end), fmt},

/* Static metadata for call sites originating directly from C shim routines. */
static const struct zks_c_site_info zks_c_sites[ZKS_SITE_C_COUNT] = {ZKS_C_SITES(ZKS_C_SITE_ROW)};

/* ------------------------------------------------------------------------
 * Stream and Severity Filtering Configuration.
 * ---------------------------------------------------------------------- */

static ZKS_ATOMIC(FILE *) logStream = NULL;

/*
 * Resolves the active output stream, defaulting to stderr when logStream is
 * NULL without mutating the atomic global variable.
 */
static FILE *zks_log_stream(void)
{
    FILE *f = ZKS_LOAD(&logStream);
    return f != NULL ? f : stderr;
}

FILE *zoo_get_log_stream(void)
{
    return zks_log_stream();
}

void zoo_set_log_stream(FILE *stream)
{
    ZKS_STORE(&logStream, stream);
}

/*
 * Updates the global log verbosity threshold via atomic stores while remaining
 * compatible with direct macro reads of the exported logLevel symbol.
 */
void zoo_set_debug_level(ZooLogLevel level)
{
    if (level == 0) {
        /* Preserve legacy support for disabling log emission altogether. */
        ZKS_STORE(&logLevel, (ZooLogLevel)0);
        return;
    }
    if (level < ZOO_LOG_LEVEL_ERROR) {
        level = ZOO_LOG_LEVEL_ERROR;
    }
    if (level > ZOO_LOG_LEVEL_DEBUG) {
        level = ZOO_LOG_LEVEL_DEBUG;
    }
    ZKS_STORE(&logLevel, level);
}

/*
 * Evaluates whether a log event meets the active threshold configured in logLevel.
 * In accordance with legacy ZooKeeper semantics, ERROR, WARN, and INFO are enabled
 * when the configured logLevel is greater than or equal to the event severity,
 * whereas DEBUG logging requires an exact match with ZOO_LOG_LEVEL_DEBUG.
 * Because logLevel may be modified asynchronously by host applications at runtime,
 * it is read dynamically on each evaluation.
 */
static int zks_level_on(uint32_t level)
{
    uint32_t cur = (uint32_t)ZKS_LOAD(&logLevel);

    switch (level) {
    case ZOO_LOG_LEVEL_ERROR:
    case ZOO_LOG_LEVEL_WARN:
    case ZOO_LOG_LEVEL_INFO:
        return cur >= level;
    case ZOO_LOG_LEVEL_DEBUG:
        return cur == ZOO_LOG_LEVEL_DEBUG;
    default:
        return 0;
    }
}

ZKS_HIDDEN int zks_vt_log_enabled(uint32_t level)
{
    int saved = zks_in_vtable;
    int on;

    zks_in_vtable = 1;
    on = zks_level_on(level);
    zks_in_vtable = saved;
    return on;
}

/* ------------------------------------------------------------------------
 * Log Formatting: Header Prefixes, Bodies, and Output Sinks.
 * ---------------------------------------------------------------------- */

/*
 * Formats local wall-clock time into ISO 8601 timestamp text matching Logback
 * ("yyyy-MM-dd HH:mm:ss,SSS").
 */
static const char *zks_time_text(time_t sec, long usec)
{
    struct tm lt;
    size_t len;

    memset(&lt, 0, sizeof(lt));
    localtime_r(&sec, &lt);
    len = strftime(zks_time_buf, ZKS_TIME_BUF_SIZE, "%Y-%m-%d %H:%M:%S", &lt);
    snprintf(zks_time_buf + len, ZKS_TIME_BUF_SIZE - len, ",%03d", (int)(usec / 1000));
    return zks_time_buf;
}

/*
 * Retrieves the current process identifier. Unlike legacy code that cached PID
 * statically (failing to adapt across fork boundaries), this lookup evaluates
 * getpid dynamically so child processes emit accurate diagnostics.
 */
static long zks_log_pid(void)
{
    return (long)getpid();
}

/*
 * Formats the standardized log header prefix into the thread-local log buffer.
 * If the level code falls outside standard ranges, ZOO_INVALID is substituted.
 * Output length is bounded by ZKS_LOG_HDR_SIZE to ensure sufficient capacity
 * remains for the formatted message payload.
 */
static int zks_log_header(ZooLogLevel level, const char *func, int line, time_t sec, long usec)
{
    static const char *const names[] = {"ZOO_INVALID", "ZOO_ERROR", "ZOO_WARN", "ZOO_INFO",
                                        "ZOO_DEBUG"};
    const char *time = zks_time_text(sec, usec);
    const char *name = (unsigned)level <= ZOO_LOG_LEVEL_DEBUG ? names[level] : names[0];
    int ofs;

    if (func == NULL) {
        func = "(null)";
    }
#ifdef THREADED
    ofs = snprintf(zks_log_buf, ZKS_LOG_HDR_SIZE, "%s:%ld(0x%lx):%s@%s@%d: ", time, zks_log_pid(),
                   (unsigned long)pthread_self(), name, func, line);
#else
    ofs = snprintf(zks_log_buf, ZKS_LOG_HDR_SIZE, "%s:%ld:%s@%s@%d: ", time, zks_log_pid(), name,
                   func, line);
#endif
    if (ofs < 0) {
        zks_log_buf[0] = '\0';
        ofs = 0;
    } else if (ofs > ZKS_LOG_HDR_SIZE - 1) {
        ofs = ZKS_LOG_HDR_SIZE - 1;
    }
    return ofs;
}

/* Computes remaining byte capacity for message body given the current header offset. */
static size_t zks_log_room(int ofs)
{
    return ofs < ZKS_LOG_BUF_SIZE - 1 ? (size_t)(ZKS_LOG_BUF_SIZE - 1 - ofs) : 0;
}

/*
 * Routes a formatted log record to the application callback if configured, or
 * writes and flushes it to the active log stream. Application log callbacks
 * execute with thread cancellation unmasked.
 */
static void zks_log_sink(log_callback_fn cb, const char *buf)
{
    if (cb) {
        ZKS_APP_CALL(cb(buf));
    } else {
        FILE *f = zks_log_stream();
        fprintf(f, "%s\n", buf);
        fflush(f);
    }
}

static void zks_log_now(time_t *sec, long *usec)
{
    struct timeval tv;

    gettimeofday(&tv, 0);
    *sec = tv.tv_sec;
    *usec = (long)tv.tv_usec;
}

void log_message(log_callback_fn callback, ZooLogLevel curLevel, int line, const char *funcName,
                 const char *format, ...)
{
    va_list va;
    time_t sec;
    long usec;
    int ofs;
    size_t room;

    zks_log_now(&sec, &usec);
    ofs = zks_log_header(curLevel, funcName, line, sec, usec);
    room = zks_log_room(ofs);
    if (room > 0) {
        va_start(va, format);
        vsnprintf(zks_log_buf + ofs, room, format, va);
        va_end(va);
    }
    zks_log_sink(callback, zks_log_buf);
}

/* ------------------------------------------------------------------------
 * Core Call Site Formatter Driver.
 *
 * Emulates C runtime printf formatting for typed arguments marshaled across
 * the Rust FFI boundary, ensuring identical numerical and pointer representations.
 * ---------------------------------------------------------------------- */

struct zks_out {
    char *p;
    size_t cap; /* Total buffer capacity including NULL terminator. */
    size_t len; /* Current string length excluding NULL terminator. */
};

static void zks_out_bytes(struct zks_out *o, const char *s, size_t n)
{
    size_t room;

    if (o->cap == 0) {
        return;
    }
    room = o->cap - 1 - o->len;
    if (n > room) {
        n = room;
    }
    memcpy(o->p + o->len, s, n);
    o->len += n;
    o->p[o->len] = '\0';
}

/* Advances output buffer length according to bytes written by snprintf. */
static void zks_out_wrote(struct zks_out *o, int n)
{
    size_t room = o->cap - 1 - o->len;

    if (n < 0) {
        o->p[o->len] = '\0';
        return;
    }
    o->len += (size_t)n < room ? (size_t)n : room;
}

static uint64_t zks_arg_bits(const zkrs_log_arg *a)
{
    if (a == NULL) {
        return 0;
    }
    switch (a->kind) {
    case ZKRS_ARG_I64:
        return (uint64_t)a->i;
    case ZKRS_ARG_U64:
        return a->u;
    case ZKRS_ARG_PTR:
        return (uint64_t)a->p;
    default:
        return 0;
    }
}

/* Resolves textual representation of string and network endpoint arguments. */
static const char *zks_arg_text(const zkrs_log_arg *a)
{
    if (a == NULL) {
        return "(null)";
    }
    switch (a->kind) {
    case ZKRS_ARG_STR:
        return a->s.ptr != NULL ? a->s.ptr : "(null)";
    case ZKRS_ARG_ENDPOINT:
        return zks_format_endpoint(&a->ep);
    case ZKRS_ARG_ERRNO_STR:
        return strerror(a->err);
    case ZKRS_ARG_GAI_STR:
        /* Platform discrepancy in legacy host resolution error formatting. */
#if defined(_WIN32) || (defined(__linux__) && defined(__GNUC__))
        return gai_strerror(a->gai_rc);
#else
        return strerror(a->err);
#endif
    default:
        return "(null)";
    }
}

/*
 * Formats a single printf conversion specifier using the typed FFI argument.
 * Specifier widths and lengths ('l', 'll', 'h') are preserved to match expected widths.
 */
static void zks_out_conv(struct zks_out *o, const char *spec, char len, char conv,
                         const zkrs_log_arg *a)
{
    char *dst;
    size_t size;
    uint64_t bits;
    int n;

    if (o->cap == 0) {
        return;
    }
    dst = o->p + o->len;
    size = o->cap - o->len;
    bits = zks_arg_bits(a);
    switch (conv) {
    case 'd':
    case 'i':
        if (len == 'L') {
            n = snprintf(dst, size, spec, (long long)bits);
        } else if (len == 'l') {
            n = snprintf(dst, size, spec, (long)bits);
        } else {
            n = snprintf(dst, size, spec, (int)bits);
        }
        break;
    case 'u':
    case 'x':
    case 'X':
    case 'o':
        if (len == 'L') {
            n = snprintf(dst, size, spec, (unsigned long long)bits);
        } else if (len == 'l') {
            n = snprintf(dst, size, spec, (unsigned long)bits);
        } else {
            n = snprintf(dst, size, spec, (unsigned)bits);
        }
        break;
    case 'c':
        n = snprintf(dst, size, spec, (int)bits);
        break;
    case 'p':
        n = snprintf(dst, size, spec, (void *)(uintptr_t)bits);
        break;
    default: /* 's' */
        n = snprintf(dst, size, spec, zks_arg_text(a));
        break;
    }
    zks_out_wrote(o, n);
}

/*
 * Parses printf format specifiers and interleaves static text chunks with
 * marshaled arguments into the destination buffer.
 */
static void zks_printf_driver(struct zks_out *o, const char *fmt, const zkrs_log_arg *args,
                              uint32_t nargs)
{
    uint32_t next = 0;
    const char *lit = fmt;
    const char *p = fmt;

    while (*p != '\0') {
        const char *start;
        char spec[32];
        size_t speclen;
        char len = 0;

        if (*p != '%') {
            p++;
            continue;
        }
        zks_out_bytes(o, lit, (size_t)(p - lit));
        start = p++;
        if (*p == '%') {
            zks_out_bytes(o, "%", 1);
            lit = ++p;
            continue;
        }
        while (*p != '\0' && strchr("-+ #0", *p) != NULL) {
            p++;
        }
        while (*p >= '0' && *p <= '9') {
            p++;
        }
        if (*p == '.') {
            p++;
            while (*p >= '0' && *p <= '9') {
                p++;
            }
        }
        if (*p == 'h') {
            p += p[1] == 'h' ? 2 : 1;
        } else if (*p == 'l') {
            len = p[1] == 'l' ? 'L' : 'l';
            p += p[1] == 'l' ? 2 : 1;
        }
        speclen = (size_t)(p - start) + 1;
        if (*p == '\0' || strchr("diuxXocps", *p) == NULL || speclen >= sizeof(spec)) {
            /* Retain unrecognized or malformed specifiers verbatim. */
            lit = start;
            if (*p != '\0') {
                p++;
            }
            continue;
        }
        memcpy(spec, start, speclen);
        spec[speclen] = '\0';
        zks_out_conv(o, spec, len, *p, args != NULL && next < nargs ? &args[next] : NULL);
        next++;
        lit = ++p;
    }
    zks_out_bytes(o, lit, (size_t)(p - lit));
}

/*
 * Dispatches an event originating from the core driver to the diagnostic log stream.
 * Severity filtering is rechecked at emission time to reflect any runtime log level
 * modifications that occurred after event queuing. Thread local errno is preserved
 * across emission to prevent diagnostic side effects on application error reporting.
 */
ZKS_HIDDEN void zks_log_emit(zhandle_t *zh, const zkrs_log_view *v)
{
    const struct zks_site *s;
    struct zks_out o;
    int saved_errno;

    if (v == NULL || v->site >= ZKS_CORE_SITE_COUNT || !zks_level_on(v->level)) {
        return;
    }
    saved_errno = errno;
    s = &zks_core_sites[v->site];
    o.p = zks_log_body;
    o.cap = sizeof(zks_log_body) - 1;
    o.len = 0;
    zks_printf_driver(&o, s->fmt, v->args, v->nargs);
    log_message(zh != NULL ? ZKS_LOAD(&zh->log_callback) : NULL, (ZooLogLevel)v->level, s->line,
                s->func, "%s", zks_log_body);
    errno = saved_errno;
}

/* Emits a log record for native C shim call sites using variable argument lists. */
static void zks_log_c_site(log_callback_fn cb, uint32_t site, va_list va)
{
    const struct zks_c_site_info *s;
    int saved_errno;

    if (site >= ZKS_SITE_C_COUNT) {
        return;
    }
    s = &zks_c_sites[site];
    if (s->func == NULL || !zks_level_on(s->level)) {
        return;
    }
    saved_errno = errno;
    vsnprintf(zks_log_body, sizeof(zks_log_body), s->fmt, va);
    log_message(cb, s->level, s->line, s->func, "%s", zks_log_body);
    errno = saved_errno;
}

ZKS_HIDDEN void zks_log_site(zhandle_t *zh, uint32_t site, ...)
{
    va_list va;

    va_start(va, site);
    zks_log_c_site(zh != NULL ? ZKS_LOAD(&zh->log_callback) : NULL, site, va);
    va_end(va);
}

/* Emits a log record using an explicit callback pointer when zhandle_t may no longer be live. */
ZKS_HIDDEN void zks_log_site_cb(log_callback_fn cb, uint32_t site, ...)
{
    va_list va;

    va_start(va, site);
    zks_log_c_site(cb, site, va);
    va_end(va);
}
