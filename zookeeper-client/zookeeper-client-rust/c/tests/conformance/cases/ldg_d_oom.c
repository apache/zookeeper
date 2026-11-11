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
 * Out-of-memory allocation failure handling. Mode: DEV.
 *
 * The Rust drop-in preserves checked C allocation sites and their error return
 * conventions while aborting deterministically on allocation sites that legacy
 * C leaves unchecked.
 *
 * At runtime, this test compiles a helper dylib that interposes calloc() via
 * __DATA,__interpose and re-executes itself under DYLD_INSERT_LIBRARIES. The
 * child process resolves zkd_oom_arm(), arms a targeted allocation failure, and
 * reports its outcome via the child exit status.
 *
 * Invariants and deviations:
 * - Checked allocation (both libraries): failing the initial calloc() inside
 *   zookeeper_init returns NULL with errno = ENOMEM.
 * - Unchecked Jute deserialization allocation: failing the 7-entry
 *   String_vector pointer array allocation in zoo_get_children triggers a
 *   clean process abort (SIGABRT) in the Rust drop-in, whereas legacy C
 *   dereferences the NULL pointer and faults with SIGSEGV or SIGBUS.
 *
 * Requires macOS DYLD_INSERT_LIBRARIES; emits a skip record on other platforms.
 *
 * zkconf-flavors: mt
 */

#include "zkconf.h"

#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#define NCHILD 7

/*
 * Interposition dylib source: mode 1 fails the next calloc() call; mode 2
 * fails any calloc(n, s) matching the armed count and element size.
 */
static const char g_preload_src[] =
    "#include <stdlib.h>\n"
    "static volatile int g_mode;\n"
    "static volatile size_t g_n, g_s;\n"
    "void zkd_oom_arm(int mode, size_t n, size_t s)\n"
    "{ g_n = n; g_s = s; g_mode = mode; }\n"
    "static void *zkd_calloc(size_t n, size_t s)\n"
    "{\n"
    "    if (g_mode == 1) { g_mode = 0; return NULL; }\n"
    "    if (g_mode == 2 && n == g_n && s == g_s) { return NULL; }\n"
    "    return calloc(n, s);\n"
    "}\n"
    "__attribute__((used)) static const struct { const void *r; const void *o; }\n"
    "    zkd_interpose[] __attribute__((section(\"__DATA,__interpose\"))) = {\n"
    "    {(const void *)zkd_calloc, (const void *)calloc}};\n";

typedef void (*arm_fn)(int mode, size_t n, size_t s);

static void quiet_watcher(zhandle_t *zh, int type, int state,
                          const char *path, void *ctx)
{
    (void)zh;
    (void)type;
    (void)state;
    (void)path;
    (void)ctx;
}

static void quiet_log(const char *msg)
{
    (void)msg;
}

/* Extracts the first server endpoint from ZKCONF_HOSTS for the child process. */
static void first_host(char *out, size_t len)
{
    const char *h = getenv("ZKCONF_HOSTS");
    size_t n;
    snprintf(out, len, "%s", h != NULL ? h : "127.0.0.1:1");
    n = strcspn(out, ",");
    out[n] = '\0';
}

/* Child execution path under DYLD_INSERT_LIBRARIES. */
static int child_run(const char *mode)
{
    arm_fn arm = NULL;
    void *p = dlsym(RTLD_DEFAULT, "zkd_oom_arm");
    char host[128];
    zhandle_t *zh;
    struct String_vector sv;
    char path[32];
    int waited;
    int i;
    if (p == NULL) {
        return 20;
    }
    memcpy(&arm, &p, sizeof(arm));
    first_host(host, sizeof(host));
    zoo_set_debug_level(ZOO_LOG_LEVEL_ERROR);
    if (strcmp(mode, "init") == 0) {
        errno = 0;
        arm(1, 0, 0);
        zh = zookeeper_init2(host, quiet_watcher, 10000, NULL, NULL,
                             ZOO_NO_LOG_CLIENTENV, quiet_log);
        arm(0, 0, 0);
        if (zh != NULL) {
            return 21;
        }
        return errno == ENOMEM ? 0 : 22;
    }
    zh = zookeeper_init2(host, quiet_watcher, 10000, NULL, NULL, 0, quiet_log);
    for (waited = 0; zh != NULL && waited < 10000; waited += 10) {
        if (zoo_state(zh) == ZOO_CONNECTED_STATE) {
            break;
        }
        usleep(10000);
    }
    if (zh == NULL || zoo_state(zh) != ZOO_CONNECTED_STATE) {
        return 23;
    }
    zoo_create(zh, "/oom", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
    for (i = 0; i < NCHILD; i++) {
        snprintf(path, sizeof(path), "/oom/c%d", i);
        zoo_create(zh, path, "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
    }
    arm(2, NCHILD, sizeof(char *));
    i = zoo_get_children(zh, "/oom", 0, &sv);
    arm(0, 0, 0);
    return i == ZOK ? 24 : 25;
}

/* Re-executes the test binary in a child process with the interposition dylib loaded. */
static const char *run_child(const char *self, const char *lib,
                             const char *mode)
{
    static char res[2][32];
    static int next;
    char *r = res[next++ % 2];
    int status = 0;
    int waited = 0;
    pid_t pid;
    fflush(NULL);
    pid = fork();
    if (pid < 0) {
        return "fork-failed";
    }
    if (pid == 0) {
        setenv("DYLD_INSERT_LIBRARIES", lib, 1);
        setenv("ZKD_OOM_CHILD", mode, 1);
        execl(self, self, (char *)NULL);
        _exit(30);
    }
    for (;;) {
        if (waitpid(pid, &status, WNOHANG) == pid) {
            break;
        }
        if (waited >= 30000) {
            kill(pid, SIGKILL);
            waitpid(pid, &status, 0);
            return "hang";
        }
        zkc_sleep_ms(10);
        waited += 10;
    }
    if (WIFEXITED(status)) {
        snprintf(r, 32, "exit:%d", WEXITSTATUS(status));
    } else if (WIFSIGNALED(status)) {
        snprintf(r, 32, "signal:%d", WTERMSIG(status));
    } else {
        snprintf(r, 32, "other");
    }
    return r;
}

int main(int argc, char **argv)
{
    const char *mode = getenv("ZKD_OOM_CHILD");
    const char *out;
    char src[1024];
    char lib[1024];
    char cmd[4096];
    const char *init_res;
    const char *vec_res;
    FILE *f;
    int crash;

    if (mode != NULL) {
        return child_run(mode);
    }
    zkc_init(argc, argv, "ldg_d_oom", 1, "DEV", "ignore");
#ifndef __APPLE__
    zkc_ev("skip", "the case needs DYLD_INSERT_LIBRARIES");
    return zkc_finish();
#endif
    out = getenv("ZKCONF_OUT");
    snprintf(src, sizeof(src), "%s.oom.c", out != NULL ? out : "/tmp/zkconf");
    snprintf(lib, sizeof(lib), "%s.oom.dylib",
             out != NULL ? out : "/tmp/zkconf");
    f = fopen(src, "w");
    if (f == NULL || fputs(g_preload_src, f) < 0 || fclose(f) != 0) {
        zkc_fail("cannot write %s", src);
        return zkc_finish();
    }
    snprintf(cmd, sizeof(cmd), "cc -dynamiclib -o '%s' '%s'", lib, src);
    if (system(cmd) != 0) {
        zkc_fail("cannot build %s", lib);
        return zkc_finish();
    }

    init_res = run_child(argv[0], lib, "init");
    zkc_dev("GLB-04", strcmp(init_res, "exit:0") == 0,
            "a failed calloc in zookeeper_init gives NULL and ENOMEM: %s",
            init_res);

    vec_res = run_child(argv[0], lib, "vector");
    if (zkc_is_rust()) {
        zkc_dev("GLB-04", strcmp(vec_res, "signal:6") == 0,
                "a failed result vector calloc aborts: %s", vec_res);
    } else {
        crash = strcmp(vec_res, "signal:10") == 0 ||
                strcmp(vec_res, "signal:11") == 0;
        zkc_dev("GLB-04", crash,
                "legacy: a failed result vector calloc crashes: %s", vec_res);
    }
    return zkc_finish();
}
