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
 * Cross-platform dynamic symbol interposition macros for conformance preload
 * libraries. Test-only code.
 *
 * Each preload defines an interposer `zkconf_NAME` for target symbol `NAME`,
 * invokes the underlying implementation via `ZKCONF_REAL(NAME)`, and registers
 * the interposition with `ZKCONF_INTERPOSE(NAME)`.
 *
 * - macOS (Mach-O): emits a `__DATA,__interpose` tuple redirecting external
 *   calls to `zkconf_NAME` while direct references to `NAME` within the preload
 *   image resolve to the original function.
 * - Linux (ELF): exports `NAME` as a strong alias to `zkconf_NAME` (winning
 *   symbol resolution via `LD_PRELOAD`) and resolves the underlying symbol via
 *   `dlsym(RTLD_NEXT, ...)` with atomic per-site caching.
 */

#ifndef ZKCONF_INTERPOSE_H_
#define ZKCONF_INTERPOSE_H_

#include <stdint.h>

#ifdef __APPLE__

#define ZKCONF_REAL(name) name

/* Registers a Mach-O dyld interpose tuple (`{replacement, replacee}`). */
#define ZKCONF_INTERPOSE(name)                                                                 \
    __attribute__((used)) static struct {                                                      \
        const void *replacement;                                                               \
        const void *replacee;                                                                  \
    } zkconf_interpose_##name __attribute__((section("__DATA,__interpose"))) = {               \
        (const void *)(uintptr_t)&zkconf_##name,                                               \
        (const void *)(uintptr_t)&name,                                                        \
    }

#else

#include <dlfcn.h>

/* Atomically resolves and caches the `RTLD_NEXT` address of `name`. */
static inline void *zkconf_next(void **slot, const char *name)
{
    void *fn = __atomic_load_n(slot, __ATOMIC_ACQUIRE);
    if (fn == NULL) {
        fn = dlsym(RTLD_NEXT, name);
        __atomic_store_n(slot, fn, __ATOMIC_RELEASE);
    }
    return fn;
}

#define ZKCONF_REAL(name)                                                                      \
    (__extension__({                                                                           \
        static void *zkconf_slot_;                                                             \
        (__typeof__(&zkconf_##name))zkconf_next(&zkconf_slot_, #name);                         \
    }))

/* Exports `name` as a default-visibility ELF symbol alias for `zkconf_##name`. */
#define ZKCONF_INTERPOSE(name)                                                                 \
    extern __typeof__(zkconf_##name) zkconf_elf_##name __asm__(#name)                          \
        __attribute__((alias("zkconf_" #name), visibility("default")))

#endif

#endif /* ZKCONF_INTERPOSE_H_ */
