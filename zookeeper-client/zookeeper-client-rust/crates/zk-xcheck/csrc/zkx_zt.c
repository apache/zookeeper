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
 * Watcher registration table test driver. Includes the unmodified legacy
 * zk_hashtable.c implementation directly to inspect internal watcher object
 * linked lists during differential verification.
 *
 * Each watcher entry pairs one of four synthetic callback functions (IDs 0..3)
 * with an integer context token.
 */

#include <stdio.h>

#include "zk_hashtable.c"

#include <stdint.h>

#define ZKX_DLV_MAX 4096
#define ZKX_PATH_MAX 256

static zhandle_t zkx_zt_zh;

static int zkx_dlv_n = 0;
static int zkx_dlv_fns[ZKX_DLV_MAX];
static uintptr_t zkx_dlv_ctxs[ZKX_DLV_MAX];
static int zkx_dlv_types[ZKX_DLV_MAX];
static char zkx_dlv_paths[ZKX_DLV_MAX][ZKX_PATH_MAX];

static void zkx_record(int fn, int type, const char *path, void *ctx)
{
    if (zkx_dlv_n >= ZKX_DLV_MAX)
        return;
    zkx_dlv_fns[zkx_dlv_n] = fn;
    zkx_dlv_ctxs[zkx_dlv_n] = (uintptr_t)ctx;
    zkx_dlv_types[zkx_dlv_n] = type;
    snprintf(zkx_dlv_paths[zkx_dlv_n], ZKX_PATH_MAX, "%s", path ? path : "");
    zkx_dlv_n++;
}

static void zkx_w0(zhandle_t *zh, int type, int state, const char *path, void *ctx)
{
    (void)zh; (void)state;
    zkx_record(0, type, path, ctx);
}

static void zkx_w1(zhandle_t *zh, int type, int state, const char *path, void *ctx)
{
    (void)zh; (void)state;
    zkx_record(1, type, path, ctx);
}

static void zkx_w2(zhandle_t *zh, int type, int state, const char *path, void *ctx)
{
    (void)zh; (void)state;
    zkx_record(2, type, path, ctx);
}

static void zkx_w3(zhandle_t *zh, int type, int state, const char *path, void *ctx)
{
    (void)zh; (void)state;
    zkx_record(3, type, path, ctx);
}

static watcher_fn zkx_fns[4] = { zkx_w0, zkx_w1, zkx_w2, zkx_w3 };

static watcher_fn zkx_fn(int id)
{
    if (id < 0 || id > 3)
        return NULL;
    return zkx_fns[id];
}

static int zkx_fn_id(watcher_fn fn)
{
    int i;
    for (i = 0; i < 4; i++) {
        if (zkx_fns[i] == fn)
            return i;
    }
    return -1;
}

static zk_hashtable *zkx_zt_table(int table)
{
    switch (table) {
    case 0: return zkx_zt_zh.active_node_watchers;
    case 1: return zkx_zt_zh.active_exist_watchers;
    default: return zkx_zt_zh.active_child_watchers;
    }
}

static zk_hashtable *zkx_checker_data(zhandle_t *zh, int rc)
{
    (void)rc;
    return zh->active_node_watchers;
}

static zk_hashtable *zkx_checker_exist(zhandle_t *zh, int rc)
{
    (void)rc;
    return zh->active_exist_watchers;
}

static zk_hashtable *zkx_checker_child(zhandle_t *zh, int rc)
{
    (void)rc;
    return zh->active_child_watchers;
}

void zkx_zt_reset(void)
{
    if (zkx_zt_zh.active_node_watchers) {
        destroy_zk_hashtable(zkx_zt_zh.active_node_watchers);
        destroy_zk_hashtable(zkx_zt_zh.active_exist_watchers);
        destroy_zk_hashtable(zkx_zt_zh.active_child_watchers);
    }
    free(zkx_zt_zh.chroot);
    memset(&zkx_zt_zh, 0, sizeof(zkx_zt_zh));
    zkx_zt_zh.active_node_watchers = create_zk_hashtable();
    zkx_zt_zh.active_exist_watchers = create_zk_hashtable();
    zkx_zt_zh.active_child_watchers = create_zk_hashtable();
    zkx_dlv_n = 0;
}

void zkx_zt_set_default(int fn, uintptr_t ctx)
{
    zkx_zt_zh.watcher = zkx_fn(fn);
    zkx_zt_zh.context = (void *)ctx;
}

void zkx_zt_set_chroot(const char *chroot)
{
    free(zkx_zt_zh.chroot);
    zkx_zt_zh.chroot = chroot ? strdup(chroot) : NULL;
}

void zkx_zt_activate(int table, const char *path, int fn, uintptr_t ctx)
{
    watcher_registration_t reg;
    reg.watcher = zkx_fn(fn);
    reg.context = (void *)ctx;
    reg.path = path;
    switch (table) {
    case 0: reg.checker = zkx_checker_data; break;
    case 1: reg.checker = zkx_checker_exist; break;
    default: reg.checker = zkx_checker_child; break;
    }
    activateWatcher(&zkx_zt_zh, &reg, ZOK);
}

/* Collects and dispatches watchers for the specified event; returns delivery count. */
int zkx_zt_event(int type, const char *path)
{
    watcher_object_list_t *list;
    char *p = strdup(path);
    zkx_dlv_n = 0;
    list = collectWatchers(&zkx_zt_zh, type, p);
    deliverWatchers(&zkx_zt_zh, type, 3, p, &list);
    free(p);
    return zkx_dlv_n;
}

int zkx_dlv_get(int i, int *fn, uintptr_t *ctx, int *type, const char **path)
{
    if (i < 0 || i >= zkx_dlv_n)
        return 0;
    *fn = zkx_dlv_fns[i];
    *ctx = zkx_dlv_ctxs[i];
    *type = zkx_dlv_types[i];
    *path = zkx_dlv_paths[i];
    return 1;
}

int zkx_zt_has(const char *path, int wtype, int fn, uintptr_t ctx)
{
    return pathHasWatcher(&zkx_zt_zh, path, wtype, zkx_fn(fn), (void *)ctx);
}

void zkx_zt_remove(const char *path, int wtype, int fn, uintptr_t ctx)
{
    removeWatchers(&zkx_zt_zh, path, (ZooWatcherType)wtype, zkx_fn(fn),
            (void *)ctx);
}

/*
 * Exports all watcher registrations in bucket and list iteration order. Each
 * output entry records its path index in `keys`, callback ID (`fns`), and
 * context token (`ctxs`). Returns the total number of registered watchers,
 * which may exceed `cap`.
 */
int zkx_zt_dump(int table, const char **keys, int key_cap, int *nkeys,
        int *key_idx, int *fns, uintptr_t *ctxs, int cap)
{
    struct hashtable *h = getImpl(zkx_zt_table(table));
    struct hashtable_itr *it;
    int k = 0;
    int n = 0;
    *nkeys = 0;
    if (hashtable_count(h) == 0)
        return 0;
    it = hashtable_iterator(h);
    do {
        const char *key = hashtable_iterator_key(it);
        watcher_object_list_t *wl = hashtable_iterator_value(it);
        watcher_object_t *wo = wl ? wl->head : NULL;
        if (k < key_cap)
            keys[k] = key;
        while (wo) {
            if (n < cap) {
                key_idx[n] = k;
                fns[n] = zkx_fn_id(wo->watcher);
                ctxs[n] = (uintptr_t)wo->context;
            }
            n++;
            wo = wo->next;
        }
        k++;
    } while (hashtable_iterator_advance(it));
    free(it);
    *nkeys = k;
    return n;
}
