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
 * Asynchronous CRUD operations across all completion callback signatures.
 * Mode: PER-ROLE.
 *
 * Invariants:
 * - An asynchronous get on a node with null data delivers value == NULL
 *   and len == -1; on empty data, value != NULL and len == 0; on failure,
 *   value == NULL, len == 0, and stat == NULL.
 * - In mt builds, all completion callbacks execute on the completion
 *   thread and never on the application thread.
 * - Completion callbacks fire in FIFO submission order, including across
 *   a pipelined batch of requests.
 * - zoo_async completes with the requested path string.
 *
 * In st builds, the wait helper drives the handle via zookeeper_process, so
 * completions execute inline on the application thread.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"

#include <string.h>

#define WAIT1() zkc_await("cb", 1, 10000)

static void call(const char *what, int rc)
{
    zkc_ev("call", "%s -> %s", what, zkc_rc(rc));
}

int main(int argc, char **argv)
{
    zhandle_t *zh;
    int i;
    int rc;

    zkc_init(argc, argv, "crud_async", 1, "PER-ROLE", "ordered");
    zh = zkc_connect(zkc_host(0), 10000, 0);
    if (zh == NULL) {
        return zkc_finish();
    }

    call("zoo_acreate /ca", zoo_acreate(zh, "/ca", "v", 1, &ZOO_OPEN_ACL_UNSAFE,
                                        0, zkc_string_cb, "create"));
    WAIT1();
    call("zoo_acreate /ca dup", zoo_acreate(zh, "/ca", "v", 1,
                                            &ZOO_OPEN_ACL_UNSAFE, 0,
                                            zkc_string_cb, "dup"));
    WAIT1();
    call("zoo_acreate2 /ca/n", zoo_acreate2(zh, "/ca/n", NULL, -1,
                                            &ZOO_OPEN_ACL_UNSAFE, 0,
                                            zkc_string_stat_cb, "create2"));
    WAIT1();
    call("zoo_acreate2 /x/y", zoo_acreate2(zh, "/x/y", "v", 1,
                                           &ZOO_OPEN_ACL_UNSAFE, 0,
                                           zkc_string_stat_cb, "create2-err"));
    WAIT1();
    call("zoo_acreate2_ttl /ca/t", zoo_acreate2_ttl(zh, "/ca/t", "t", 1,
                                                    &ZOO_OPEN_ACL_UNSAFE,
                                                    ZOO_PERSISTENT_WITH_TTL,
                                                    600000, zkc_string_stat_cb,
                                                    "ttl"));
    WAIT1();
    call("zoo_acreate /ca/e", zoo_acreate(zh, "/ca/e", "", 0,
                                          &ZOO_OPEN_ACL_UNSAFE, 0,
                                          zkc_string_cb, "empty"));
    WAIT1();
    call("zoo_acreate /ca/s-", zoo_acreate(zh, "/ca/s-", "s", 1,
                                           &ZOO_OPEN_ACL_UNSAFE,
                                           ZOO_EPHEMERAL_SEQUENTIAL,
                                           zkc_string_cb, "seq"));
    WAIT1();

    /* Null node payload, empty node payload, and error completion values. */
    call("zoo_aget /ca/n", zoo_aget(zh, "/ca/n", 0, zkc_data_cb, "null"));
    WAIT1();
    call("zoo_aget /ca/e", zoo_aget(zh, "/ca/e", 0, zkc_data_cb, "empty"));
    WAIT1();
    call("zoo_aget /ca/missing", zoo_aget(zh, "/ca/missing", 0, zkc_data_cb,
                                          "fail"));
    WAIT1();
    call("zoo_aget /ca", zoo_aget(zh, "/ca", 0, zkc_data_cb, "data"));
    WAIT1();

    call("zoo_aexists /ca", zoo_aexists(zh, "/ca", 0, zkc_stat_cb, "ex"));
    WAIT1();
    call("zoo_aexists /ca/missing", zoo_aexists(zh, "/ca/missing", 0,
                                                zkc_stat_cb, "ex-miss"));
    WAIT1();
    call("zoo_aset /ca", zoo_aset(zh, "/ca", "w", 1, 0, zkc_stat_cb, "set"));
    WAIT1();
    call("zoo_aset /ca v=0", zoo_aset(zh, "/ca", "w", 1, 0, zkc_stat_cb,
                                      "set-badver"));
    WAIT1();
    call("zoo_aset /ca/n NULL,-1", zoo_aset(zh, "/ca/n", NULL, -1, -1,
                                            zkc_stat_cb, "set-null"));
    WAIT1();

    call("zoo_aget_children /ca", zoo_aget_children(zh, "/ca", 0,
                                                    zkc_strings_cb, "kids"));
    WAIT1();
    call("zoo_aget_children /ca/missing",
         zoo_aget_children(zh, "/ca/missing", 0, zkc_strings_cb, "kids-miss"));
    WAIT1();
    call("zoo_aget_children2 /ca", zoo_aget_children2(zh, "/ca", 0,
                                                      zkc_strings_stat_cb,
                                                      "kids2"));
    WAIT1();
    call("zoo_aget_children2 /ca/missing",
         zoo_aget_children2(zh, "/ca/missing", 0, zkc_strings_stat_cb,
                            "kids2-miss"));
    WAIT1();
    call("zoo_aget_acl /ca", zoo_aget_acl(zh, "/ca", zkc_acl_cb, "acl"));
    WAIT1();
    call("zoo_aget_acl /ca/missing", zoo_aget_acl(zh, "/ca/missing",
                                                  zkc_acl_cb, "acl-miss"));
    WAIT1();
    call("zoo_aset_acl /ca/e", zoo_aset_acl(zh, "/ca/e", -1,
                                            &ZOO_READ_ACL_UNSAFE,
                                            zkc_void_cb, "setacl"));
    WAIT1();
    call("zoo_aset_acl /ca/e v=9", zoo_aset_acl(zh, "/ca/e", 9,
                                                &ZOO_READ_ACL_UNSAFE,
                                                zkc_void_cb, "setacl-badver"));
    WAIT1();
    call("zoo_async /ca", zoo_async(zh, "/ca", zkc_string_cb, "sync"));
    WAIT1();
    call("zoo_agetconfig", zoo_agetconfig(zh, 0, zkc_data_cb, "config"));
    WAIT1();

    /* 20 pipelined requests complete in call order. */
    for (i = 0; i < 20; i++) {
        static const char *const labels[] = {
            "p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9",
            "p10", "p11", "p12", "p13", "p14", "p15", "p16", "p17", "p18",
            "p19"};
        if (i % 2 == 0) {
            rc = zoo_aexists(zh, "/ca", 0, zkc_stat_cb, labels[i]);
        } else {
            rc = zoo_aget(zh, "/ca/e", 0, zkc_data_cb, labels[i]);
        }
        if (rc != ZOK) {
            zkc_fail("pipelined call %d -> %s", i, zkc_rc(rc));
        }
    }
    zkc_await("cb", 20, 10000);

    call("zoo_adelete /ca", zoo_adelete(zh, "/ca", -1, zkc_void_cb,
                                        "del-notempty"));
    WAIT1();
    call("zoo_adelete /ca/e v=5", zoo_adelete(zh, "/ca/e", 5, zkc_void_cb,
                                              "del-badver"));
    WAIT1();
    call("zoo_adelete /ca/e", zoo_adelete(zh, "/ca/e", -1, zkc_void_cb,
                                          "del"));
    WAIT1();
    call("zoo_adelete /ca/missing", zoo_adelete(zh, "/ca/missing", -1,
                                                zkc_void_cb, "del-miss"));
    WAIT1();
    zkc_close(zh);
    return zkc_finish();
}
