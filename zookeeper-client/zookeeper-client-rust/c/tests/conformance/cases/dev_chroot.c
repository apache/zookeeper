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
 * Chroot path handling deviations. Mode: DEV.
 *
 * Deviations verified:
 * - Synchronous zoo_getconfig on a chrooted handle. The
 *   legacy library prepends the chroot prefix and fails with ZNONODE, whereas
 *   the Rust core queries the global "/zookeeper/config" path.
 * - Under a chroot, sequential creation on "/" and
 *   operations on the single-character relative path "x" return ZBADARGUMENTS
 *   without invoking completions on Rust (or ZMARSHALLINGERROR inside
 *   zoo_amulti). Legacy C creates "/ch0000000000" outside the chroot boundary
 *   and maps "x" to the chroot root.
 * - Under a chroot, the Rust core rejects any path not beginning with "/", so
 *   "" and "2/x" return ZBADARGUMENTS. Legacy C concatenates the relative
 *   suffix directly onto the chroot prefix, creating "/ch2/x" and stripping
 *   the response path back to "2/x".
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"
#include "zkconf_sync.h"

#include <string.h>

static zhandle_t *g_plain;

static int exists(const char *path)
{
    struct Stat st;
    return zkcs_exists(g_plain, path, 0, &st) == ZOK;
}

/*
 * Returns the wait timeout for completion absence checks: short on Rust
 * (where zero completions are expected) and 10 s on legacy C (where one
 * completion is expected and zkc_await_none returns as soon as it arrives).
 */
static int completion_window(int rust_ms)
{
    return zkc_is_rust() ? rust_ms : 10000;
}

#ifdef THREADED
static void getconfig_sync(zhandle_t *ch)
{
    char buf[512];
    int len = sizeof(buf);
    struct Stat st;
    int rc = zoo_getconfig(ch, 0, buf, &len, &st);
    zkc_dev("DV-FIX-5", rc == (zkc_is_rust() ? ZOK : ZNONODE),
            "zoo_getconfig under a chroot -> %s", zkc_rc(rc));
}
#endif

static void seq_on_root(zhandle_t *ch)
{
    int rc;
    int ncb;
    int outside;
    struct String_vector sv;
    int i;
    rc = zoo_acreate(ch, "/", "", 0, &ZOO_OPEN_ACL_UNSAFE, ZOO_SEQUENCE,
                     zkc_string_cb, "seq-root");
    ncb = zkc_await_none("cb", completion_window(2000));
    outside = 0;
    if (zkcs_get_children(g_plain, "/", 0, &sv) == ZOK) {
        for (i = 0; i < sv.count; i++) {
            if (strncmp(sv.data[i], "ch0", 3) == 0) {
                outside++;
            }
        }
        deallocate_String_vector(&sv);
    }
    if (zkc_is_rust()) {
        zkc_dev("DV-FIX-7", rc == ZBADARGUMENTS && ncb == 0 && outside == 0,
                "seq create on / -> %s callbacks=%d outside=%d", zkc_rc(rc),
                ncb, outside);
    } else {
        zkc_dev("DV-FIX-7", rc == ZOK && ncb == 1 && outside == 1,
                "seq create on / -> %s callbacks=%d outside=%d", zkc_rc(rc),
                ncb, outside);
    }
}

static void one_char(zhandle_t *ch)
{
    char buf[16];
    int len = sizeof(buf);
    struct Stat st;
    int grc = zkcs_get(ch, "x", 0, buf, &len, &st);
    int crc = zkcs_create(ch, "x", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
    int arc = zoo_aget(ch, "x", 0, zkc_data_cb, "one-char");
    int ncb = zkc_await_none("cb", completion_window(1000));
    if (zkc_is_rust()) {
        zkc_dev("DV-FIX-7", grc == ZBADARGUMENTS && crc == ZBADARGUMENTS &&
                                arc == ZBADARGUMENTS && ncb == 0,
                "\"x\": get=%s create=%s aget=%s callbacks=%d", zkc_rc(grc),
                zkc_rc(crc), zkc_rc(arc), ncb);
    } else {
        zkc_dev("DV-FIX-7", grc == ZOK && crc == ZNODEEXISTS && arc == ZOK &&
                                ncb == 1,
                "\"x\": get=%s create=%s aget=%s callbacks=%d", zkc_rc(grc),
                zkc_rc(crc), zkc_rc(arc), ncb);
    }
}

static void multi_escape(zhandle_t *ch)
{
    zoo_op_t ops[2];
    zoo_op_result_t res[2];
    char p0[64];
    int rc1;
    int rc2;
    int ncb;
    memset(res, 0, sizeof(res));
    zoo_create_op_init(&ops[0], "x", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
    rc1 = zoo_amulti(ch, 1, ops, res, zkc_void_cb, "multi-x");
    zoo_create_op_init(&ops[1], "/", "", 0, &ZOO_OPEN_ACL_UNSAFE,
                       ZOO_SEQUENCE, p0, sizeof(p0));
    rc2 = zoo_amulti(ch, 1, &ops[1], &res[1], zkc_void_cb, "multi-seq");
    if (zkc_is_rust()) {
        ncb = zkc_await_none("cb", 2000);
    } else {
        ncb = zkc_await("cb", 2, 10000) == 0 ? 2 : -1;
    }
    if (zkc_is_rust()) {
        zkc_dev("DV-FIX-7", rc1 == ZMARSHALLINGERROR &&
                                rc2 == ZMARSHALLINGERROR && ncb == 0,
                "multi \"x\" -> %s, multi seq on / -> %s, callbacks=%d",
                zkc_rc(rc1), zkc_rc(rc2), ncb);
    } else {
        zkc_dev("DV-FIX-7", rc1 == ZOK && rc2 == ZOK && ncb == 2,
                "multi \"x\" -> %s, multi seq on / -> %s, callbacks=%d",
                zkc_rc(rc1), zkc_rc(rc2), ncb);
    }
}

static void relative_paths(zhandle_t *ch)
{
    char path[64];
    char buf[16];
    int len = sizeof(buf);
    struct Stat st;
    int crc;
    int erc;
    int ok;
    memset(path, 0, sizeof(path));
    crc = zkcs_create(ch, "2/x", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, path,
                     sizeof(path));
    erc = zkcs_get(ch, "", 0, buf, &len, &st);
    if (zkc_is_rust()) {
        ok = crc == ZBADARGUMENTS && path[0] == '\0' && erc == ZBADARGUMENTS;
    } else {
        ok = crc == ZOK && strcmp(path, "2/x") == 0 && exists("/ch2/x") &&
             erc == ZOK;
    }
    zkc_dev("DV-FIX-7", ok,
            "PTH-01-relative: create \"2/x\" -> %s path=\"%s\"; get \"\" -> %s",
            zkc_rc(crc), path, zkc_rc(erc));
}

int main(int argc, char **argv)
{
    char host[160];
    zhandle_t *ch;

    zkc_init(argc, argv, "dev_chroot", 1, "DEV", "ignore");
    g_plain = zkc_connect(zkc_host(0), 10000, 0);
    if (g_plain == NULL) {
        return zkc_finish();
    }
    zkcs_create(g_plain, "/ch", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
    zkcs_create(g_plain, "/ch2", "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, NULL, 0);
    snprintf(host, sizeof(host), "%s/ch", zkc_host(0));
    ch = zkc_connect(host, 10000, 0);
    if (ch != NULL) {
#ifdef THREADED
        getconfig_sync(ch);
#endif
        seq_on_root(ch);
        one_char(ch);
        multi_escape(ch);
        relative_paths(ch);
        zkc_close(ch);
    }
    zkc_close(g_plain);
    return zkc_finish();
}
