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
 * Watch registration, one-shot delivery, deduplication, and removal.
 * Mode: PER-ROLE.
 *
 * Invariants:
 * - The initial session event on a newly connected handle is CONNECTED.
 * - When a client's own mutation triggers a watch notification on the
 *   same session, the watch event is delivered prior to the mutation's
 *   completion callback.
 * - Duplicate registrations of the same (watcher_fn, watcherCtx) pair
 *   for a given event are deduplicated to a single invocation; distinct
 *   registrations fire in legacy watch table order (child, exists, data), and
 *   watch=1 registrations dispatch to the handle's default watcher with the
 *   handle context.
 * - Watches are one-shot and do not fire on subsequent mutations unless
 *   re-registered.
 * - Removing a non-existent watcher returns ZNOWATCHER.
 * - Asynchronous remove-watches entry points accept a cast
 *   `void_completion_t *` function pointer and invoke it upon completion.
 * - Calling zoo_set_watcher on a handle initialized with a NULL
 *   watcher returns the internal non-NULL no-op watcher stub (which is safe to
 *   call), and calling zoo_set_watcher(zh, NULL) returns the previously
 *   installed watcher.
 *
 * In st builds, zkcs helpers substitute for synchronous calls and all callbacks
 * execute inline on the application thread inside zookeeper_process.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"
#include "zkconf_sync.h"

#include <string.h>

static void call(const char *what, int rc)
{
    zkc_ev("call", "%s -> %s", what, zkc_rc(rc));
}

static void wait_watch(int n)
{
    zkc_await("watch", n, 10000);
}

int main(int argc, char **argv)
{
    zhandle_t *zh;
    zhandle_t *nw;
    struct Stat st;
    char buf[64];
    int len;
    int rc;

    zkc_init(argc, argv, "watch", 1, "PER-ROLE", "ordered");
    zh = zkc_connect(zkc_host(0), 10000, 0);
    if (zh == NULL) {
        return zkc_finish();
    }
    rc = zkcs_create(zh, "/w", "0", 1, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
    call("zoo_create /w", rc);

    /* A data watch fires CHANGED once (one-shot). */
    len = sizeof(buf);
    rc = zkcs_wget(zh, "/w", zkc_watcher, "wget", buf, &len, &st);
    call("zoo_wget /w", rc);
    call("zoo_aset /w", zoo_aset(zh, "/w", "1", 1, -1, zkc_stat_cb, "set1"));
    /* The watch event is delivered before the set completion on L1. */
    wait_watch(1);
    zkc_await("cb", 1, 10000);
    call("zoo_aset /w", zoo_aset(zh, "/w", "2", 1, -1, zkc_stat_cb, "set2"));
    zkc_await("cb", 1, 10000);
    zkc_ev("check", "extra watch events: %d", zkc_await_none("watch", 300));

    /* An exists watch on a missing node fires CREATED. */
    rc = zkcs_wexists(zh, "/w/c", zkc_watcher, "wexists", &st);
    call("zoo_wexists /w/c", rc);
    call("zoo_acreate /w/c", zoo_acreate(zh, "/w/c", "c", 1,
                                         &ZOO_OPEN_ACL_UNSAFE, 0,
                                         zkc_string_cb, "mk"));
    wait_watch(1);
    zkc_await("cb", 1, 10000);

    /* A child watch fires CHILD; a data watch on the child fires DELETED. */
    {
        struct String_vector sv;
        memset(&sv, 0, sizeof(sv));
        rc = zkcs_wget_children(zh, "/w", zkc_watcher, "wkids", &sv);
        call("zoo_wget_children /w", rc);
        deallocate_String_vector(&sv);
    }
    len = sizeof(buf);
    rc = zkcs_wget(zh, "/w/c", zkc_watcher, "wget-c", buf, &len, NULL);
    call("zoo_wget /w/c", rc);
    call("zoo_adelete /w/c", zoo_adelete(zh, "/w/c", -1, zkc_void_cb, "rm"));
    wait_watch(2);
    zkc_await("cb", 1, 10000);

    /* The default watcher gets watch=1 events with the handle context. */
    rc = zkcs_exists(zh, "/w", 1, &st);
    call("zoo_exists /w watch=1", rc);
    call("zoo_aset /w", zoo_aset(zh, "/w", "3", 1, -1, zkc_stat_cb, "set3"));
    wait_watch(1);
    zkc_await("cb", 1, 10000);

    /*
     * Duplicate registrations of one (fn, ctx) pair fire once; distinct
     * contexts fire in registration order.
     */
    len = sizeof(buf);
    rc = zkcs_wget(zh, "/w", zkc_watcher, "dupA", buf, &len, NULL);
    call("zoo_wget /w dupA", rc);
    rc = zkcs_wexists(zh, "/w", zkc_watcher, "dupA", NULL);
    call("zoo_wexists /w dupA", rc);
    rc = zkcs_wexists(zh, "/w", zkc_watcher, "dupB", NULL);
    call("zoo_wexists /w dupB", rc);
    len = sizeof(buf);
    rc = zkcs_wget(zh, "/w", zkc_watcher, "dupC", buf, &len, NULL);
    call("zoo_wget /w dupC", rc);
    rc = zkcs_get(zh, "/w", 1, buf, &len, NULL);
    call("zoo_get /w watch=1", rc);
    call("zoo_aset /w", zoo_aset(zh, "/w", "4", 1, -1, zkc_stat_cb, "set4"));
    wait_watch(4);
    zkc_await("cb", 1, 10000);
    zkc_ev("check", "extra watch events: %d", zkc_await_none("watch", 300));

    /* Watch removal return codes and notification suppression. */
    rc = zkcs_remove_watches(zh, "/w", ZWATCHTYPE_DATA, zkc_watcher, "none", 0);
    call("zoo_remove_watches /w no such watcher", rc);
    rc = zkcs_remove_watches(zh, "/w", ZWATCHTYPE_DATA, zkc_watcher, "none", 1);
    call("zoo_remove_watches /w no such watcher local", rc);
    len = sizeof(buf);
    rc = zkcs_wget(zh, "/w", zkc_watcher, "rmme", buf, &len, NULL);
    call("zoo_wget /w rmme", rc);
    rc = zkcs_remove_watches(zh, "/w", ZWATCHTYPE_DATA, zkc_watcher, "rmme", 0);
    call("zoo_remove_watches /w rmme", rc);
    zkc_ev("check", "watch events after remove: %d",
           zkc_await_none("watch", 500));
    rc = zkcs_remove_all_watches(zh, "/w", ZWATCHTYPE_ANY, 0);
    call("zoo_remove_all_watches /w none", rc);
    len = sizeof(buf);
    rc = zkcs_wget(zh, "/w", zkc_watcher, "rmall", buf, &len, NULL);
    call("zoo_wget /w rmall", rc);
    call("zoo_aremove_all_watches /w",
         zoo_aremove_all_watches(zh, "/w", ZWATCHTYPE_DATA, 0,
                                 (void_completion_t *)zkc_void_cb,
                                 "rmall-cb"));
    zkc_await("cb", 1, 10000);
    zkc_ev("check", "watch events after remove all: %d",
           zkc_await_none("watch", 500));
    /* zoo_aremove_watches with local=1 is covered in dev_misc. */
    rc = zkcs_remove_watches(zh, "/w", ZWATCHTYPE_ANY, NULL, NULL, 1);
    call("zoo_remove_watches /w local all none", rc);
    call("zoo_aset /w", zoo_aset(zh, "/w", "5", 1, -1, zkc_stat_cb, "set5"));
    zkc_await("cb", 1, 10000);
    zkc_ev("check", "watch events after set: %d", zkc_await_none("watch", 300));

    /* A handle initialized with a NULL default watcher. */
    nw = zookeeper_init(zkc_host(0), NULL, 10000, NULL, NULL, 0);
    zkc_ev("call", "zookeeper_init watcher=NULL -> %s",
           nw == NULL ? "NULL" : "zh");
    if (nw != NULL) {
        watcher_fn old;
        int i;
        /* single threaded handles progress through connection establishment only when polled. */
        zkc_poll_add(nw);
        for (i = 0; i < 100 && zoo_state(nw) != ZOO_CONNECTED_STATE; i++) {
            zkc_poll(50);
        }
        old = zoo_set_watcher(nw, zkc_watcher);
        zkc_ev("ret", "zoo_set_watcher old=%s", old == NULL ? "NULL" : "LIBFN");
        if (old != NULL) {
            old(nw, ZOO_SESSION_EVENT, ZOO_CONNECTED_STATE, "", NULL);
            zkc_ev("ret", "old watcher called");
        }
        old = zoo_set_watcher(nw, NULL);
        zkc_ev("ret", "zoo_set_watcher old=%s",
               old == zkc_watcher ? "zkc_watcher" : "OTHER");
        zkc_close(nw);
    }
    zkc_close(zh);
    return zkc_finish();
}
