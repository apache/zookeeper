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
 * Dynamic memory deallocation scanner supporting credential zeroization verification.
 *
 * Because the repository policy strictly prohibits unsafe code in client crates, custom
 * allocator level heap inspections cannot be implemented directly in Rust. This dynamic
 * library provides equivalent heap introspection by interposing `free` and `realloc`.
 * `free_scan.sh` injects it with `DYLD_INSERT_LIBRARIES` on macOS and with `LD_PRELOAD`
 * on Linux (glibc).
 *
 * Prior to releasing heap memory, the interposed deallocator inspects the entire memory block
 * for the byte sequence specified in `ZK_FREE_SCAN_MARKER`. Any occurrence indicates that
 * sensitive material was not zeroized prior to deallocation, recording a failure entry to
 * the destination log referenced by `ZK_FREE_SCAN_OUT`. The interposed `realloc` routine
 * forces a realloc-and-copy sequence to ensure that previous memory allocations are similarly
 * verified before release.
 */

#ifndef __APPLE__
#define _GNU_SOURCE
#endif

#include <execinfo.h>
#include <fcntl.h>
#ifdef __APPLE__
#include <malloc/malloc.h>
#else
#include <malloc.h>
#endif
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/*
 * Platform block size query and underlying allocator entry points. On macOS, interposition
 * tuples do not apply within this image, so the standard names reach the real allocator.
 * On Linux, this image defines `free` and `realloc` itself, so it calls the glibc entry points.
 */
#ifdef __APPLE__
#define ZKFS_SIZE(p) malloc_size(p)
#define ZKFS_MALLOC malloc
#define ZKFS_FREE free
#define ZKFS_REALLOC realloc
#else
extern void *__libc_malloc(size_t n);
extern void __libc_free(void *p);
extern void *__libc_realloc(void *p, size_t n);
#define ZKFS_SIZE(p) malloc_usable_size((void *)(uintptr_t)(p))
#define ZKFS_MALLOC __libc_malloc
#define ZKFS_FREE __libc_free
#define ZKFS_REALLOC __libc_realloc
#endif

/*
 * Records a detection event to the output log identified by `ZK_FREE_SCAN_OUT`.
 * When `ZK_FREE_SCAN_TRACE` is configured, appends symbolicated backtrace frames.
 * Operates without triggering dynamic memory allocations to avoid recursion.
 */
static void report(size_t size)
{
    const char *out = getenv("ZK_FREE_SCAN_OUT");
    char line[40];
    char digits[24];
    size_t n = 0;
    size_t d = 0;
    int fd;

    if (out == NULL) {
        return;
    }
    do {
        digits[d++] = (char)('0' + size % 10);
        size /= 10;
    } while (size != 0 && d < sizeof digits);
    memcpy(line, "hit ", 4);
    n = 4;
    while (d > 0) {
        line[n++] = digits[--d];
    }
    line[n++] = '\n';
    fd = open(out, O_WRONLY | O_APPEND | O_CREAT, 0600);
    if (fd >= 0) {
        (void)write(fd, line, n);
        if (getenv("ZK_FREE_SCAN_TRACE") != NULL) {
            void *frames[48];
            int depth = backtrace(frames, 48);
            backtrace_symbols_fd(frames, depth, fd);
        }
        (void)close(fd);
    }
}

/* Sentinel byte sequence initialized by the module constructor prior to process entry. */
static char g_marker[256];
static size_t g_marker_len;

/*
 * Caches the target sentinel string in static storage and removes the variable from the
 * environment block to prevent spurious detections when child processes clone and deallocate
 * environment memory.
 */
__attribute__((constructor)) static void zkfs_init(void)
{
    const char *m = getenv("ZK_FREE_SCAN_MARKER");
    size_t len;

    if (m == NULL) {
        return;
    }
    len = strlen(m);
    if (len == 0 || len >= sizeof g_marker) {
        return;
    }
    memcpy(g_marker, m, len);
    g_marker_len = len;
    (void)unsetenv("ZK_FREE_SCAN_MARKER");
}

/*
 * Inspects the memory block allocated at `p` and records a detection if the sentinel pattern is
 * present.
 */
static void scan(const void *p)
{
    size_t size;

    if (p == NULL || g_marker_len == 0) {
        return;
    }
    size = ZKFS_SIZE(p);
    if (size >= g_marker_len && memmem(p, size, g_marker, g_marker_len) != NULL) {
        report(size);
    }
}

/* Interposed deallocation hooks delegating to platform runtime allocators. */
static void zkfs_free(void *p)
{
    scan(p);
    ZKFS_FREE(p);
}

static void *zkfs_realloc(void *p, size_t n)
{
    void *q;
    size_t old;

    if (p == NULL || n == 0) {
        scan(p);
        return ZKFS_REALLOC(p, n);
    }
    q = ZKFS_MALLOC(n);
    if (q == NULL) {
        return NULL;
    }
    old = ZKFS_SIZE(p);
    memcpy(q, p, old < n ? old : n);
    scan(p);
    ZKFS_FREE(p);
    return q;
}

#ifdef __APPLE__
/*
 * dyld interposition descriptors binding replacement hooks to standard library allocator symbols.
 */
struct zkfs_interpose {
    const void *replacement;
    const void *replacee;
};

__attribute__((used)) static struct zkfs_interpose zkfs_interpose_free
    __attribute__((section("__DATA,__interpose"))) = {
        (const void *)(uintptr_t)&zkfs_free,
        (const void *)(uintptr_t)&free,
};

__attribute__((used)) static struct zkfs_interpose zkfs_interpose_realloc
    __attribute__((section("__DATA,__interpose"))) = {
        (const void *)(uintptr_t)&zkfs_realloc,
        (const void *)(uintptr_t)&realloc,
};
#else
/* Exported preload definitions interposing standard glibc allocator entry points. */
void free(void *p)
{
    zkfs_free(p);
}

void *realloc(void *p, size_t n)
{
    return zkfs_realloc(p, n);
}
#endif
