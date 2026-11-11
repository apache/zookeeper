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
 * Synchronous CRUD operations. Mode: EXACT.
 *
 * Invariants:
 * - Reading a node with null payload sets *buffer_len = -1 and leaves
 *   the destination buffer unmodified; payloads exceeding buffer capacity are
 *   silently truncated; failed calls leave all output buffers unmodified.
 * - zoo_create path_buffer copy and NUL-termination semantics for
 *   buffer capacities of NULL, 0, 1, short, exact, and excess length.
 * - All server error codes propagate unmodified to the synchronous caller.
 *
 * zkconf-skip-st: the sync API is mt only; crud_async covers st
 */

#include "zkconf.h"

#include <stdio.h>
#include <string.h>

static void show_stat(const char *what, int rc, const struct Stat *st)
{
    char sb[512];
    zkc_stat_str(sb, sizeof(sb), st);
    zkc_ev("ret", "%s -> %s %s", what, zkc_rc(rc), sb);
}

static void do_create(zhandle_t *zh, const char *path, const char *v, int vl,
                      int mode, int buflen)
{
    char buf[128];
    int rc;
    memset(buf, '#', sizeof(buf));
    buf[sizeof(buf) - 1] = '\0';
    rc = zoo_create(zh, path, v, vl, &ZOO_OPEN_ACL_UNSAFE, mode,
                    buflen < 0 ? NULL : buf, buflen < 0 ? 0 : buflen);
    buf[64] = '\0';
    zkc_ev("ret", "zoo_create %s mode=%d buflen=%d -> %s buf=\"%s\"", path,
           mode, buflen, zkc_rc(rc), buflen < 0 ? "-" : buf);
}

static void do_get(zhandle_t *zh, const char *path, int cap)
{
    char buf[256];
    int len = cap;
    struct Stat st;
    int rc;
    memset(buf, '#', sizeof(buf));
    memset(&st, 0xA5, sizeof(st));
    rc = zoo_get(zh, path, 0, buf, &len, &st);
    buf[cap < 255 && cap >= 0 ? cap : 255] = '\0';
    if (st.czxid == (int64_t)0xA5A5A5A5A5A5A5A5LL) {
        zkc_ev("ret", "zoo_get %s cap=%d -> %s len=%d buf=\"%s\" stat=UNTOUCHED",
               path, cap, zkc_rc(rc), len, buf);
    } else {
        char sb[512];
        zkc_stat_str(sb, sizeof(sb), &st);
        zkc_ev("ret", "zoo_get %s cap=%d -> %s len=%d buf=\"%s\" %s", path,
               cap, zkc_rc(rc), len, buf, sb);
    }
}

static void do_children(zhandle_t *zh, const char *path, int with_stat)
{
    struct String_vector sv;
    struct Stat st;
    int rc;
    int i;
    char line[1024];
    size_t o;
    memset(&sv, 0, sizeof(sv));
    memset(&st, 0, sizeof(st));
    if (with_stat) {
        rc = zoo_get_children2(zh, path, 0, &sv, &st);
    } else {
        rc = zoo_get_children(zh, path, 0, &sv);
    }
    o = (size_t)snprintf(line, sizeof(line), "%d[", sv.count);
    for (i = 0; i < sv.count && o + 2 < sizeof(line); i++) {
        o += (size_t)snprintf(line + o, sizeof(line) - o, "%s%s", i ? "," : "",
                              sv.data[i]);
    }
    if (o + 2 < sizeof(line)) {
        snprintf(line + o, sizeof(line) - o, "]");
    }
    if (with_stat) {
        char sb[512];
        zkc_stat_str(sb, sizeof(sb), &st);
        zkc_ev("ret", "zoo_get_children2 %s -> %s %s %s", path, zkc_rc(rc),
               line, sb);
    } else {
        zkc_ev("ret", "zoo_get_children %s -> %s %s", path, zkc_rc(rc), line);
    }
    deallocate_String_vector(&sv);
}

static void do_acl(zhandle_t *zh, const char *path)
{
    struct ACL_vector acl;
    struct Stat st;
    int rc;
    int i;
    char line[512];
    size_t o;
    memset(&acl, 0, sizeof(acl));
    memset(&st, 0, sizeof(st));
    rc = zoo_get_acl(zh, path, &acl, &st);
    o = (size_t)snprintf(line, sizeof(line), "%d[", acl.count);
    for (i = 0; i < acl.count && o + 2 < sizeof(line); i++) {
        o += (size_t)snprintf(line + o, sizeof(line) - o, "%s%d:%s:%s",
                              i ? "," : "", acl.data[i].perms,
                              acl.data[i].id.scheme, acl.data[i].id.id);
    }
    if (o + 2 < sizeof(line)) {
        snprintf(line + o, sizeof(line) - o, "]");
    }
    show_stat(path, rc, &st);
    zkc_ev("ret", "zoo_get_acl %s -> %s acl=%s", path, zkc_rc(rc), line);
    deallocate_ACL_vector(&acl);
}

int main(int argc, char **argv)
{
    zhandle_t *zh;
    struct Stat st;
    char big[100000];
    char bigback[100000];
    int biglen;
    int rc;
    int i;
    char buf[128];

    zkc_init(argc, argv, "crud_sync", 1, "EXACT", "ordered");
    zh = zkc_connect(zkc_host(0), 10000, 0);
    if (zh == NULL) {
        return zkc_finish();
    }

    /* path_buffer capacities across NULL, 0, 1, short, exact, and oversize. */
    do_create(zh, "/cs", "abc", 3, 0, 64);
    do_create(zh, "/cs/a", "", 0, 0, -1);
    do_create(zh, "/cs/b", "b", 1, 0, 0);
    do_create(zh, "/cs/c", "c", 1, 0, 1);
    do_create(zh, "/cs/d", "d", 1, 0, 3);
    do_create(zh, "/cs/e", "e", 1, 0, 5);
    do_create(zh, "/cs/f", "f", 1, 0, 6);
    /* Null payload creation (value == NULL, len == -1) and server error propagation. */
    do_create(zh, "/cs/null", NULL, -1, 0, 64);
    do_create(zh, "/cs", "x", 1, 0, 64);
    do_create(zh, "/nope/x", "x", 1, 0, 64);
    do_create(zh, "/cs/seq-", "s", 1, ZOO_PERSISTENT_SEQUENTIAL, 64);
    do_create(zh, "/cs/seq-", "s", 1, ZOO_PERSISTENT_SEQUENTIAL, 64);
    do_create(zh, "/cs/eph", "e", 1, ZOO_EPHEMERAL, 64);
    do_create(zh, "/cs/eph/child", "e", 1, 0, 64);
    do_create(zh, "/cs/ephseq-", "e", 1, ZOO_EPHEMERAL_SEQUENTIAL, 64);
    do_create(zh, "/cs/container", "c", 1, ZOO_CONTAINER, 64);

    /* zoo_create2 and zoo_create2_ttl populate the output Stat structure. */
    memset(buf, 0, sizeof(buf));
    memset(&st, 0, sizeof(st));
    rc = zoo_create2(zh, "/cs/c2", "c2", 2, &ZOO_OPEN_ACL_UNSAFE, 0, buf,
                     sizeof(buf), &st);
    zkc_ev("ret", "zoo_create2 path=%s", buf);
    show_stat("zoo_create2 /cs/c2", rc, &st);
    memset(&st, 0, sizeof(st));
    rc = zoo_create2_ttl(zh, "/cs/ttl", "t", 1, &ZOO_OPEN_ACL_UNSAFE,
                         ZOO_PERSISTENT_WITH_TTL, 600000, buf, sizeof(buf),
                         &st);
    zkc_ev("ret", "zoo_create2_ttl path=%s", buf);
    show_stat("zoo_create2_ttl /cs/ttl", rc, &st);
    rc = zoo_create_ttl(zh, "/cs/ttl0", "t", 1, &ZOO_OPEN_ACL_UNSAFE, 0,
                        600000, buf, sizeof(buf));
    zkc_ev("ret", "zoo_create_ttl mode=0 ttl=600000 -> %s", zkc_rc(rc));

    /* zoo_get with sufficient capacity, truncation, null data, empty data, and error. */
    do_get(zh, "/cs", 64);
    do_get(zh, "/cs", 2);
    do_get(zh, "/cs", 0);
    do_get(zh, "/cs/null", 64);
    do_get(zh, "/cs/a", 64);
    do_get(zh, "/cs/missing", 64);

    /* zoo_exists, zoo_set, and zoo_set2 with matching and mismatched versions. */
    memset(&st, 0xA5, sizeof(st));
    rc = zoo_exists(zh, "/cs/missing", 0, &st);
    zkc_ev("ret", "zoo_exists /cs/missing -> %s stat=%s", zkc_rc(rc),
           st.czxid == (int64_t)0xA5A5A5A5A5A5A5A5LL ? "UNTOUCHED" : "WRITTEN");
    rc = zoo_exists(zh, "/cs", 0, &st);
    show_stat("zoo_exists /cs", rc, &st);
    rc = zoo_exists(zh, "/cs", 0, NULL);
    zkc_ev("ret", "zoo_exists /cs stat=NULL -> %s", zkc_rc(rc));
    rc = zoo_set(zh, "/cs", "new", 3, 0);
    zkc_ev("ret", "zoo_set /cs v=0 -> %s", zkc_rc(rc));
    rc = zoo_set(zh, "/cs", "new", 3, 0);
    zkc_ev("ret", "zoo_set /cs v=0 -> %s", zkc_rc(rc));
    memset(&st, 0, sizeof(st));
    rc = zoo_set2(zh, "/cs", "newer", 5, -1, &st);
    show_stat("zoo_set2 /cs v=-1", rc, &st);
    rc = zoo_set(zh, "/cs/missing", "x", 1, -1);
    zkc_ev("ret", "zoo_set /cs/missing -> %s", zkc_rc(rc));
    rc = zoo_set(zh, "/cs/null", NULL, -1, -1);
    zkc_ev("ret", "zoo_set /cs/null NULL,-1 -> %s", zkc_rc(rc));
    do_get(zh, "/cs/null", 64);
    do_get(zh, "/cs", 64);

    /* 100000-byte payload round-trip verification. */
    for (i = 0; i < (int)sizeof(big); i++) {
        big[i] = (char)(i * 7 + 3);
    }
    rc = zoo_set(zh, "/cs/b", big, (int)sizeof(big), -1);
    zkc_ev("ret", "zoo_set /cs/b 100000 bytes -> %s", zkc_rc(rc));
    biglen = (int)sizeof(bigback);
    rc = zoo_get(zh, "/cs/b", 0, bigback, &biglen, NULL);
    zkc_ev("ret", "zoo_get /cs/b -> %s len=%d equal=%d", zkc_rc(rc), biglen,
           memcmp(big, bigback, sizeof(big)) == 0);

    /* Child listing and ACL operations. */
    do_children(zh, "/cs/eph", 0);
    do_children(zh, "/cs/missing", 0);
    do_children(zh, "/cs/a", 1);
    do_children(zh, "/", 0);
    do_acl(zh, "/cs");
    rc = zoo_set_acl(zh, "/cs", -1, &ZOO_CREATOR_ALL_ACL);
    zkc_ev("ret", "zoo_set_acl /cs CREATOR_ALL -> %s", zkc_rc(rc));
    rc = zoo_set_acl(zh, "/cs", 5, &ZOO_READ_ACL_UNSAFE);
    zkc_ev("ret", "zoo_set_acl /cs v=5 -> %s", zkc_rc(rc));
    rc = zoo_set_acl(zh, "/cs/a", 0, &ZOO_READ_ACL_UNSAFE);
    zkc_ev("ret", "zoo_set_acl /cs/a READ -> %s", zkc_rc(rc));
    do_acl(zh, "/cs/a");
    rc = zoo_set(zh, "/cs/a", "x", 1, -1);
    zkc_ev("ret", "zoo_set /cs/a (read-only acl) -> %s", zkc_rc(rc));
    do_acl(zh, "/cs/missing");

    /* Node deletion: version mismatch, non-empty directory, missing node, and valid delete. */
    rc = zoo_delete(zh, "/cs/c", 7);
    zkc_ev("ret", "zoo_delete /cs/c v=7 -> %s", zkc_rc(rc));
    rc = zoo_delete(zh, "/cs", -1);
    zkc_ev("ret", "zoo_delete /cs -> %s", zkc_rc(rc));
    rc = zoo_delete(zh, "/cs/missing", -1);
    zkc_ev("ret", "zoo_delete /cs/missing -> %s", zkc_rc(rc));
    rc = zoo_delete(zh, "/cs/c", 0);
    zkc_ev("ret", "zoo_delete /cs/c v=0 -> %s", zkc_rc(rc));

    /* Synchronous zoo_getconfig without a chroot (chroot behavior is covered in dev_chroot). */
    {
        char cfg[512];
        int cl = (int)sizeof(cfg) - 1;
        memset(cfg, 0, sizeof(cfg));
        rc = zoo_getconfig(zh, 0, cfg, &cl, &st);
        zkc_ev("ret", "zoo_getconfig -> %s len=%d", zkc_rc(rc), cl);
    }
    zkc_close(zh);
    return zkc_finish();
}
