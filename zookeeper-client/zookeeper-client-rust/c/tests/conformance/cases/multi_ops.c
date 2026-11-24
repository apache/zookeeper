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
 * Multi-operation array serialization, unknown opcode rejection, and NULL ACL
 * handling. Mode: PER-ROLE.
 *
 * Invariants:
 * - A 20-operation multi batch serializes and executes all sub-operations in
 *   array order for both synchronous and asynchronous calls.
 * - An unrecognized opcode at index 2 fails validation with ZUNIMPLEMENTED,
 *   applying submission-time initialization to results[0..1] while leaving
 *   results[2..] unmodified and avoiding any read of the unknown op's union
 *   payload.
 * - A create sub-operation with a NULL ACL vector pointer serializes an empty
 *   ACL list, which the server rejects with ZINVALIDACL.
 *
 * In st builds, zkcs_multi substitutes for synchronous zoo_multi.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"
#include "zkconf_sync.h"

#include <stdio.h>
#include <string.h>

#define NOPS 20
#define UNKNOWN_OP 99

static char vbuf[NOPS][16];
static char paths[NOPS][16];
static struct Stat sbuf[NOPS];
static zoo_op_result_t res[NOPS];

static void reset(void)
{
    int i;
    memset(vbuf, '#', sizeof(vbuf));
    memset(sbuf, 0, sizeof(sbuf));
    for (i = 0; i < NOPS; i++) {
        res[i].err = 12345;
        res[i].value = NULL;
        res[i].valuelen = 777;
        res[i].stat = NULL;
    }
}

/*
 * Emits per-operation multi results. Passing with_err == 0 masks res[i].err
 * when comparing synchronous pre-send validation failures (where Rust
 * populates every res[i].err per spec section "Defined Results in Synchronous
 * Multi-Operations", verified in dev_misc).
 */
static void show_as(const char *what, int rc, int n, int with_err)
{
    int i;
    zkc_ev("ret", "%s -> %s", what, zkc_rc(rc));
    for (i = 0; i < n; i++) {
        char sb[512];
        char v[20];
        zkc_stat_str(sb, sizeof(sb), res[i].stat);
        memcpy(v, vbuf[i], 15);
        v[15] = '\0';
        zkc_ev("ret", "  [%d] err=%s valuelen=%d value=%s buf=\"%s\" %s", i,
               !with_err ? "-"
               : (res[i].err == 12345 ? "UNTOUCHED" : zkc_rc(res[i].err)),
               res[i].valuelen,
               res[i].value == NULL ? "NULL"
               : (res[i].value == vbuf[i] ? "BUF" : "OTHER"),
               v, sb);
    }
}

static void show(const char *what, int rc, int n)
{
    show_as(what, rc, n, 1);
}

/* Populates ops[1..NOPS-1] with child create or delete sub-operations under root. */
static void children(zoo_op_t *ops, const char *root, int create)
{
    int i;
    for (i = 1; i < NOPS; i++) {
        snprintf(paths[i], sizeof paths[i], "%s/k%02d", root, i);
        if (create) {
            zoo_create_op_init(&ops[i], paths[i], "k", 1, &ZOO_OPEN_ACL_UNSAFE,
                               0, vbuf[i], sizeof(vbuf[i]));
        } else {
            zoo_delete_op_init(&ops[i], paths[i], -1);
        }
    }
}

/* Populates ops[2] with an unknown opcode and poison payload bytes. */
static void unknown_at_2(zoo_op_t *ops, const char *root)
{
    snprintf(paths[0], sizeof paths[0], "%s", root);
    snprintf(paths[3], sizeof paths[3], "%s/x", root);
    zoo_create_op_init(&ops[0], paths[0], "u", 1, &ZOO_OPEN_ACL_UNSAFE, 0,
                       vbuf[0], sizeof(vbuf[0]));
    zoo_set_op_init(&ops[1], paths[0], "uu", 2, -1, &sbuf[1]);
    memset(&ops[2], 0xa5, sizeof(ops[2]));
    ops[2].type = UNKNOWN_OP;
    zoo_create_op_init(&ops[3], paths[3], "x", 1, &ZOO_OPEN_ACL_UNSAFE, 0,
                       vbuf[3], sizeof(vbuf[3]));
}

int main(int argc, char **argv)
{
    zhandle_t *zh;
    zoo_op_t ops[NOPS];
    int rc;

    zkc_init(argc, argv, "multi_ops", 1, "PER-ROLE", "ordered");
    zh = zkc_connect(zkc_host(0), 10000, 0);
    if (zh == NULL) {
        return zkc_finish();
    }

    /* Synchronous 20-operation create batch. */
    reset();
    zoo_create_op_init(&ops[0], "/mo", "r", 1, &ZOO_OPEN_ACL_UNSAFE, 0,
                       vbuf[0], sizeof(vbuf[0]));
    children(ops, "/mo", 1);
    rc = zkcs_multi(zh, NOPS, ops, res);
    show("zoo_multi 20 creates", rc, NOPS);

    /* Asynchronous 20-operation set + delete batch. */
    reset();
    zoo_set_op_init(&ops[0], "/mo", "rr", 2, 0, &sbuf[0]);
    children(ops, "/mo", 0);
    rc = zoo_amulti(zh, NOPS, ops, res, zkc_void_cb, "amulti-20");
    zkc_ev("call", "zoo_amulti 20 ops -> %s", zkc_rc(rc));
    zkc_await("cb", 1, 10000);
    show("zoo_amulti 20 ops results", rc, NOPS);

    /*
     * Unknown sub-operation opcode at index 2 (suppress log comparison across
     * these calls because legacy C logs on the caller thread whereas Rust logs
     * on the I/O thread).
     */
    zoo_set_debug_level(0);
    reset();
    unknown_at_2(ops, "/mu");
    rc = zkcs_multi(zh, 4, ops, res);
    show_as("zoo_multi unknown at 2", rc, 4, 0);

    reset();
    unknown_at_2(ops, "/mua");
    rc = zoo_amulti(zh, 4, ops, res, zkc_void_cb, "amulti-unknown");
    zkc_ev("call", "zoo_amulti unknown at 2 -> %s", zkc_rc(rc));
    zkc_ev("check", "callbacks: %d", zkc_await_none("cb", 300));
    show("zoo_amulti unknown at 2 results", rc, 4);
    zoo_set_debug_level(ZOO_LOG_LEVEL_INFO);

    /* A create op with a NULL ACL vector. */
    reset();
    zoo_create_op_init(&ops[0], "/mn", "n", 1, NULL, 0, vbuf[0],
                       sizeof(vbuf[0]));
    rc = zkcs_multi(zh, 1, ops, res);
    show("zoo_multi null acl", rc, 1);

    reset();
    zoo_create_op_init(&ops[0], "/mna", "n", 1, NULL, 0, vbuf[0],
                       sizeof(vbuf[0]));
    rc = zoo_amulti(zh, 1, ops, res, zkc_void_cb, "amulti-null-acl");
    zkc_ev("call", "zoo_amulti null acl -> %s", zkc_rc(rc));
    zkc_await("cb", 1, 10000);
    show("zoo_amulti null acl results", rc, 1);

    rc = zkcs_exists(zh, "/mu", 0, &sbuf[0]);
    zkc_ev("call", "zoo_exists /mu -> %s", zkc_rc(rc));
    rc = zkcs_exists(zh, "/mn", 0, &sbuf[0]);
    zkc_ev("call", "zoo_exists /mn -> %s", zkc_rc(rc));

    zkc_close(zh);
    return zkc_finish();
}
