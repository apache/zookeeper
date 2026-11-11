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
 * Multi-operation transaction batches. Mode: PER-ROLE.
 *
 * Invariants:
 * - When sub-operation k fails, the call returns the aggregate failure
 *   code, populates per-op results[i].err values matching legacy
 *   deserialize_multi semantics, and preserves caller-initialized valuelen on
 *   unexecuted create sub-ops.
 * - Created paths returned by create sub-operations are truncated and
 *   NUL-terminated to fit the caller supplied buffer capacity.
 * - A count of 0 submits an empty multi batch that completes with ZOK;
 *   an invalid sub-operation path fails validation synchronously before any
 *   wire transmission.
 * - Asynchronous zoo_amulti populates the caller's results[] array prior to
 *   invoking the void completion callback.
 *
 * In st builds, zkcs_multi substitutes for synchronous zoo_multi.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"
#include "zkconf_sync.h"

#include <string.h>

#define NOPS 5

static char vbuf[NOPS][16];
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

static void show(const char *what, int rc, int n)
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
               res[i].err == 12345 ? "UNTOUCHED" : zkc_rc(res[i].err),
               res[i].valuelen,
               res[i].value == NULL ? "NULL"
               : (res[i].value == vbuf[i] ? "BUF" : "OTHER"),
               v, sb);
    }
}

int main(int argc, char **argv)
{
    zhandle_t *zh;
    zoo_op_t ops[NOPS];
    int rc;

    zkc_init(argc, argv, "multi", 1, "PER-ROLE", "ordered");
    zh = zkc_connect(zkc_host(0), 10000, 0);
    if (zh == NULL) {
        return zkc_finish();
    }

    /* A successful multi of every op kind. */
    reset();
    zoo_create_op_init(&ops[0], "/m", "m", 1, &ZOO_OPEN_ACL_UNSAFE, 0,
                       vbuf[0], sizeof(vbuf[0]));
    zoo_create_op_init(&ops[1], "/m/a", "a", 1, &ZOO_OPEN_ACL_UNSAFE,
                       ZOO_PERSISTENT_SEQUENTIAL, vbuf[1], 4);
    zoo_set_op_init(&ops[2], "/m", "mm", 2, 0, &sbuf[2]);
    zoo_check_op_init(&ops[3], "/m", 1);
    zoo_create_op_init(&ops[4], "/m/b", "b", 1, &ZOO_OPEN_ACL_UNSAFE, 0,
                        vbuf[4], sizeof(vbuf[4]));
    rc = zkcs_multi(zh, NOPS, ops, res);
    show("zoo_multi ok", rc, NOPS);

    /* Atomic rollback when operation 2 fails. */
    reset();
    zoo_create_op_init(&ops[0], "/m/c", "c", 1, &ZOO_OPEN_ACL_UNSAFE, 0,
                       vbuf[0], sizeof(vbuf[0]));
    zoo_set_op_init(&ops[1], "/m", "x", 1, -1, &sbuf[1]);
    zoo_delete_op_init(&ops[2], "/m/missing", -1);
    zoo_check_op_init(&ops[3], "/m", 0);
    zoo_create_op_init(&ops[4], "/m/d", "d", 1, &ZOO_OPEN_ACL_UNSAFE, 0,
                       vbuf[4], sizeof(vbuf[4]));
    rc = zkcs_multi(zh, NOPS, ops, res);
    show("zoo_multi fail at 2", rc, NOPS);

    /* A check failure with a bad version. */
    reset();
    zoo_check_op_init(&ops[0], "/m", 99);
    zoo_delete_op_init(&ops[1], "/m/b", -1);
    rc = zkcs_multi(zh, 2, ops, res);
    show("zoo_multi bad check", rc, 2);

    /* The async multi. */
    reset();
    zoo_delete_op_init(&ops[0], "/m/b", -1);
    zoo_set_op_init(&ops[1], "/m", "async", 5, -1, &sbuf[1]);
    zoo_create_op_init(&ops[2], "/m/e", "e", 1, &ZOO_OPEN_ACL_UNSAFE,
                       ZOO_EPHEMERAL, vbuf[2], sizeof(vbuf[2]));
    rc = zoo_amulti(zh, 3, ops, res, zkc_void_cb, "amulti");
    zkc_ev("call", "zoo_amulti -> %s", zkc_rc(rc));
    zkc_await("cb", 1, 10000);
    show("zoo_amulti results", rc, 3);

    reset();
    zoo_delete_op_init(&ops[0], "/m/missing", -1);
    rc = zoo_amulti(zh, 1, ops, res, zkc_void_cb, "amulti-fail");
    zkc_ev("call", "zoo_amulti fail -> %s", zkc_rc(rc));
    zkc_await("cb", 1, 10000);
    show("zoo_amulti fail results", rc, 1);

    /* Empty operation count (0) and invalid sub-operation path. */
    reset();
    rc = zoo_amulti(zh, 0, ops, res, zkc_void_cb, "amulti-0");
    zkc_ev("call", "zoo_amulti count=0 -> %s", zkc_rc(rc));
    zkc_await("cb", 1, 10000);
    rc = zkcs_multi(zh, 0, ops, res);
    zkc_ev("call", "zoo_multi count=0 -> %s", zkc_rc(rc));
    reset();
    zoo_check_op_init(&ops[0], "/m", -1);
    zoo_delete_op_init(&ops[1], "bad", -1);
    rc = zoo_amulti(zh, 2, ops, res, zkc_void_cb, "amulti-badpath");
    zkc_ev("call", "zoo_amulti bad sub-path -> %s", zkc_rc(rc));
    zkc_ev("check", "callbacks: %d", zkc_await_none("cb", 300));
    show("zoo_amulti bad sub-path results", rc, 2);

    zkc_close(zh);
    return zkc_finish();
}
