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
 * Handle teardown concurrency stress test (`zookeeper_close`).
 *
 * Each iteration establishes a session, submits `NREQ` asynchronous
 * `zoo_aexists` operations, delays for a uniform random interval in `[DMIN, DMAX]`
 * microseconds, and invokes `zookeeper_close`. Every accepted request must
 * deliver exactly one completion callback before `zookeeper_close` returns. The
 * harness tracks lost, duplicate, and stale (cross-iteration) completions as
 * well as any `zookeeper_close` return code other than `ZOK`.
 *
 * In multithreaded (`THREADED`) builds, the delay interval uses `usleep` so
 * background I/O and completion threads race against `zookeeper_close`. In
 * single threaded builds, the caller thread drives `zookeeper_interest` and
 * `zookeeper_process` until the delay expires.
 *
 * Usage: close_stress HOST:PORT ITERATIONS [SEED]
 *
 * Environment variables:
 *   STRESS_DMIN, STRESS_DMAX  random delay bounds in microseconds (default: 0..800)
 *   STRESS_CERT               certificate specification for `zookeeper_init_ssl`
 *                             (requires compilation with `HAVE_OPENSSL_H`)
 *   STRESS_EXPECT_DIR         expected parent directory of the dynamically
 *                             loaded client library (verified via `dladdr`)
 *   STRESS_LATE               on a failing iteration, sleep an additional 200 ms
 *                             and report late completions arriving after close
 *   STRESS_LOG, STRESS_LEVEL  capture per-iteration client logs and dump them
 *                             to stderr when an iteration fails
 *
 * Failing iterations report per-slot hit counts and completion codes observed
 * at the moment `zookeeper_close` returned.
 *
 * Exits with 0 if all iterations succeed, 1 if any completion anomaly,
 * connection failure, or non-`ZOK` close return code occurs, or 2 on usage or
 * environment validation errors.
 */

#define _DARWIN_C_SOURCE
#define _GNU_SOURCE

#include <zookeeper.h>

#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define NREQ 5
#define TAG_STRIDE 8
#define CONNECT_SECONDS 10

static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cv = PTHREAD_COND_INITIALIZER;
static int connected;
static int hits[NREQ];
static int rcs[NREQ];
static long cur_iter;
static long stale;

/*
 * Completion return code histogram:
 * ZOK, ZNONODE, ZCLOSING, ZCONNECTIONLOSS, ZSESSIONEXPIRED, other.
 */
static long rc_count[6];

static int rc_slot(int rc)
{
    switch (rc) {
    case ZOK: return 0;
    case ZNONODE: return 1;
    case ZCLOSING: return 2;
    case ZCONNECTIONLOSS: return 3;
    case ZSESSIONEXPIRED: return 4;
    default: return 5;
    }
}

static int env_int(const char *name, int dflt)
{
    const char *v = getenv(name);
    return v != NULL ? atoi(v) : dflt;
}

static void watcher(zhandle_t *zh, int type, int state, const char *path,
                    void *ctx)
{
    (void)zh;
    (void)path;
    (void)ctx;
    if (type == ZOO_SESSION_EVENT && state == ZOO_CONNECTED_STATE) {
        pthread_mutex_lock(&mu);
        connected = 1;
        pthread_cond_broadcast(&cv);
        pthread_mutex_unlock(&mu);
    }
}

static void stat_cb(int rc, const struct Stat *st, const void *data)
{
    long tag = (long)data;
    int i = (int)(tag % TAG_STRIDE);

    (void)st;
    pthread_mutex_lock(&mu);
    if (tag / TAG_STRIDE != cur_iter) {
        stale++;
    } else if (i >= 0 && i < NREQ) {
        hits[i]++;
        rcs[i] = rc;
    }
    rc_count[rc_slot(rc)]++;
    pthread_mutex_unlock(&mu);
}

/*
 * Reports the loaded client library path via `dladdr` and, when
 * `STRESS_EXPECT_DIR` is set, verifies that its canonical directory matches
 * (guarding against macOS SIP stripping `DYLD_*` variables across wrapper
 * binaries and falling back to the library's default install name).
 */
static int check_library(void)
{
    Dl_info info;
    const char *want = getenv("STRESS_EXPECT_DIR");
    char got_dir[PATH_MAX];
    char want_dir[PATH_MAX];
    char *slash;

    if (dladdr((void *)zookeeper_init, &info) == 0 || info.dli_fname == NULL) {
        fprintf(stderr, "close_stress: dladdr found no client library\n");
        return -1;
    }
    printf("library=%s\n", info.dli_fname);
    if (want == NULL) {
        return 0;
    }
    if (realpath(info.dli_fname, got_dir) == NULL ||
        realpath(want, want_dir) == NULL) {
        fprintf(stderr, "close_stress: realpath failed: %s\n", strerror(errno));
        return -1;
    }
    slash = strrchr(got_dir, '/');
    if (slash != NULL) {
        *slash = '\0';
    }
    if (strcmp(got_dir, want_dir) != 0) {
        fprintf(stderr, "close_stress: loaded %s, expected a library in %s\n",
                info.dli_fname, want_dir);
        return -1;
    }
    return 0;
}

static zhandle_t *open_handle(const char *host)
{
    const char *cert = getenv("STRESS_CERT");

    if (cert != NULL) {
#ifdef HAVE_OPENSSL_H
        return zookeeper_init_ssl(host, cert, watcher, 10000, NULL, NULL, 0);
#else
        fprintf(stderr, "close_stress: STRESS_CERT needs a HAVE_OPENSSL_H build\n");
        exit(2);
#endif
    }
    return zookeeper_init(host, watcher, 10000, NULL, NULL, 0);
}

#ifdef THREADED

/* Awaits asynchronous session establishment driven by the library's I/O thread. */
static int wait_connected(zhandle_t *zh)
{
    struct timespec dl;
    int ok;

    (void)zh;
    clock_gettime(CLOCK_REALTIME, &dl);
    dl.tv_sec += CONNECT_SECONDS;
    pthread_mutex_lock(&mu);
    while (!connected) {
        if (pthread_cond_timedwait(&cv, &mu, &dl) != 0) {
            break;
        }
    }
    ok = connected;
    pthread_mutex_unlock(&mu);
    return ok;
}

/* Sleeps for `us` microseconds while background I/O and completion threads run. */
static void wait_us(zhandle_t *zh, long long us)
{
    (void)zh;
    if (us > 0) {
        usleep((useconds_t)us);
    }
}

#else

static long long now_us(void)
{
    struct timeval tv;

    gettimeofday(&tv, NULL);
    return (long long)tv.tv_sec * 1000000LL + tv.tv_usec;
}

/*
 * Executes one single threaded `zookeeper_interest` / `select` /
 * `zookeeper_process` cycle bounded to `budget_us`.
 */
static void drive_once(zhandle_t *zh, long long budget_us)
{
    int fd = -1;
    int interest = 0;
    struct timeval tv;
    fd_set rfds;
    fd_set wfds;
    long long wait;

    if (zookeeper_interest(zh, &fd, &interest, &tv) != ZOK) {
        return;
    }
    wait = (long long)tv.tv_sec * 1000000LL + tv.tv_usec;
    if (wait > budget_us) {
        wait = budget_us;
    }
    tv.tv_sec = (time_t)(wait / 1000000LL);
    tv.tv_usec = (suseconds_t)(wait % 1000000LL);
    FD_ZERO(&rfds);
    FD_ZERO(&wfds);
    if (fd >= 0) {
        if (interest & ZOOKEEPER_READ) {
            FD_SET(fd, &rfds);
        }
        if (interest & ZOOKEEPER_WRITE) {
            FD_SET(fd, &wfds);
        }
    }
    interest = 0;
    if (select(fd + 1, &rfds, &wfds, NULL, &tv) > 0 && fd >= 0) {
        if (FD_ISSET(fd, &rfds)) {
            interest |= ZOOKEEPER_READ;
        }
        if (FD_ISSET(fd, &wfds)) {
            interest |= ZOOKEEPER_WRITE;
        }
    }
    (void)zookeeper_process(zh, interest);
}

/* Drives the single threaded event loop until `ZOO_CONNECTED_STATE` is observed. */
static int wait_connected(zhandle_t *zh)
{
    long long end = now_us() + CONNECT_SECONDS * 1000000LL;

    while (!connected && now_us() < end) {
        drive_once(zh, end - now_us());
    }
    return connected;
}

/* Drives the single threaded event loop for `us` microseconds. */
static void wait_us(zhandle_t *zh, long long us)
{
    long long end = now_us() + us;

    while (now_us() < end) {
        drive_once(zh, end - now_us());
    }
}

#endif

static void print_log(FILE *lf, long it)
{
    char buf[4096];
    size_t r;

    fflush(lf);
    rewind(lf);
    fprintf(stderr, "---- log of iteration %ld\n", it);
    while ((r = fread(buf, 1, sizeof buf, lf)) > 0) {
        fwrite(buf, 1, r, stderr);
    }
    fprintf(stderr, "---- end\n");
}

int main(int argc, char **argv)
{
    const char *host;
    long n;
    long it;
    unsigned seed;
    int dmin = env_int("STRESS_DMIN", 0);
    int dmax = env_int("STRESS_DMAX", 800);
    long lost = 0, dup = 0, bad_iter = 0, conn_fail = 0, close_rc_bad = 0;

    if (argc < 3) {
        fprintf(stderr, "usage: %s HOST:PORT ITERATIONS [SEED]\n", argv[0]);
        return 2;
    }
    host = argv[1];
    n = atol(argv[2]);
    seed = argc > 3 ? (unsigned)atol(argv[3]) : (unsigned)time(NULL);
    if (dmin < 0 || dmax < dmin) {
        fprintf(stderr, "close_stress: bad wait range %d..%d\n", dmin, dmax);
        return 2;
    }
    if (check_library() != 0) {
        return 2;
    }
    srand(seed);
    zoo_set_debug_level(0);
    for (it = 0; it < n; it++) {
        FILE *lf = NULL;
        zhandle_t *zh;
        useconds_t d;
        int i;
        int bad = 0;
        int at_close[NREQ];
        int rc_at_close[NREQ];

        pthread_mutex_lock(&mu);
        memset(hits, 0, sizeof hits);
        memset(rcs, 0, sizeof rcs);
        connected = 0;
        cur_iter = it;
        pthread_mutex_unlock(&mu);
        if (getenv("STRESS_LOG") != NULL) {
            lf = tmpfile();
            zoo_set_log_stream(lf);
            zoo_set_debug_level(
                (ZooLogLevel)env_int("STRESS_LEVEL", ZOO_LOG_LEVEL_DEBUG));
        }
        zh = open_handle(host);
        if (zh == NULL) {
            conn_fail++;
            if (lf != NULL) {
                zoo_set_log_stream(NULL);
                fclose(lf);
            }
            continue;
        }
        if (!wait_connected(zh)) {
            conn_fail++;
            zookeeper_close(zh);
            if (lf != NULL) {
                print_log(lf, it);
                zoo_set_log_stream(NULL);
                fclose(lf);
            }
            continue;
        }
        for (i = 0; i < NREQ; i++) {
            void *tag = (void *)(it * TAG_STRIDE + i);
            if (zoo_aexists(zh, "/", 0, stat_cb, tag) != ZOK) {
                /* Synchronously rejected submissions never invoke a completion callback. */
                pthread_mutex_lock(&mu);
                hits[i] = 1;
                pthread_mutex_unlock(&mu);
            }
        }
        d = (useconds_t)(dmin + rand() % (dmax - dmin + 1));
        wait_us(zh, d);
        if (zookeeper_close(zh) != ZOK) {
            close_rc_bad++;
        }
        /* Snapshot per-slot completion counters immediately upon `zookeeper_close` return. */
        pthread_mutex_lock(&mu);
        memcpy(at_close, hits, sizeof at_close);
        memcpy(rc_at_close, rcs, sizeof rc_at_close);
        pthread_mutex_unlock(&mu);
        for (i = 0; i < NREQ; i++) {
            if (at_close[i] == 0) {
                lost++;
                bad = 1;
            } else if (at_close[i] > 1) {
                dup += at_close[i] - 1;
                bad = 1;
            }
        }
        if (bad) {
            int late = 0;
            char slots[NREQ * 4 + 2] = "-";
            size_t used = 0;
            bad_iter++;
            if (getenv("STRESS_LATE") != NULL) {
                usleep(200000);
                pthread_mutex_lock(&mu);
                for (i = 0; i < NREQ; i++) {
                    if (hits[i] != at_close[i]) {
                        late += hits[i] - at_close[i];
                        used += (size_t)snprintf(slots + used, sizeof slots - used,
                                                 "%s%d", used > 0 ? "," : "", i);
                    }
                }
                pthread_mutex_unlock(&mu);
            }
            if (lf != NULL) {
                print_log(lf, it);
            }
            fprintf(stderr,
                    "bad iteration=%ld wait=%u hits=%d%d%d%d%d "
                    "rcs=%d,%d,%d,%d,%d late=%d late_slots=%s\n",
                    it, (unsigned)d, at_close[0], at_close[1], at_close[2],
                    at_close[3], at_close[4], rc_at_close[0], rc_at_close[1],
                    rc_at_close[2], rc_at_close[3], rc_at_close[4], late,
                    slots);
        }
        if (lf != NULL) {
            zoo_set_log_stream(NULL);
            fclose(lf);
        }
    }
    printf("seed=%u iterations=%ld lost=%ld dup=%ld stale=%ld "
           "bad_iterations=%ld conn_fail=%ld close_rc_bad=%ld rc_ok=%ld "
           "rc_nonode=%ld rc_closing=%ld rc_connloss=%ld rc_expired=%ld "
           "rc_other=%ld\n",
           seed, n, lost, dup, stale, bad_iter, conn_fail, close_rc_bad,
           rc_count[0], rc_count[1], rc_count[2], rc_count[3], rc_count[4],
           rc_count[5]);
    return (lost || dup || stale || conn_fail || close_rc_bad) ? 1 : 0;
}
