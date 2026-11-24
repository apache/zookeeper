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
 * Transactional multi-operation request building and completion handling.
 *
 * Implements operation initialization helpers (zoo_*_op_init) and asynchronous multi-request
 * submission (zoo_amulti). The caller's operation array is inspected synchronously during
 * submission, binding output result destinations and recording sub-operation types so that
 * result decoding can safely unpack heterogenous multi-transaction responses upon completion.
 */

#include "zks_internal.h"

#include <assert.h>

/* Resolves container and TTL creation flags to specific multi sub-operation codes. */
static int zks_create_op_type(int mode, int default_op)
{
    if (mode == ZOO_CONTAINER) {
        return ZOO_CREATE_CONTAINER_OP;
    } else if (mode == ZOO_PERSISTENT_WITH_TTL || mode == ZOO_PERSISTENT_SEQUENTIAL_WITH_TTL) {
        return ZOO_CREATE_TTL_OP;
    } else {
        return default_op;
    }
}

void zoo_create_op_init(zoo_op_t *op, const char *path, const char *value, int valuelen,
                        const struct ACL_vector *acl, int mode, char *path_buffer,
                        int path_buffer_len)
{
    assert(op);
    op->type = zks_create_op_type(mode, ZOO_CREATE_OP);
    op->create_op.path = path;
    op->create_op.data = value;
    op->create_op.datalen = valuelen;
    op->create_op.acl = acl;
    op->create_op.flags = mode;
    op->create_op.ttl = 0;
    op->create_op.buf = path_buffer;
    op->create_op.buflen = path_buffer_len;
}

void zoo_create2_op_init(zoo_op_t *op, const char *path, const char *value, int valuelen,
                         const struct ACL_vector *acl, int mode, char *path_buffer,
                         int path_buffer_len)
{
    assert(op);
    op->type = zks_create_op_type(mode, ZOO_CREATE2_OP);
    op->create_op.path = path;
    op->create_op.data = value;
    op->create_op.datalen = valuelen;
    op->create_op.acl = acl;
    op->create_op.flags = mode;
    op->create_op.buf = path_buffer;
    op->create_op.buflen = path_buffer_len;
}

void zoo_delete_op_init(zoo_op_t *op, const char *path, int version)
{
    assert(op);
    op->type = ZOO_DELETE_OP;
    op->delete_op.path = path;
    op->delete_op.version = version;
}

void zoo_set_op_init(zoo_op_t *op, const char *path, const char *buffer, int buflen, int version,
                     struct Stat *stat)
{
    assert(op);
    op->type = ZOO_SETDATA_OP;
    op->set_op.path = path;
    op->set_op.data = buffer;
    op->set_op.datalen = buflen;
    op->set_op.version = version;
    op->set_op.stat = stat;
}

void zoo_check_op_init(zoo_op_t *op, const char *path, int version)
{
    assert(op);
    op->type = ZOO_CHECK_OP;
    op->check_op.path = path;
    op->check_op.version = version;
}

/* Identifies valid sub-operation types supported within ZooKeeper transactions. */
static int zks_multi_supported(int type)
{
    switch (type) {
    case ZOO_CREATE_OP:
    case ZOO_CREATE_CONTAINER_OP:
    case ZOO_DELETE_OP:
    case ZOO_SETDATA_OP:
    case ZOO_CHECK_OP:
        return 1;
    default:
        return 0;
    }
}

/* Maps sub-operation types to their respective result payload decoding categories. */
static uint8_t zks_multi_kind(int type)
{
    switch (type) {
    case ZOO_CREATE_OP:
    case ZOO_CREATE_CONTAINER_OP:
        return ZKRS_K_STRING;
    case ZOO_SETDATA_OP:
        return ZKRS_K_STAT;
    default:
        return ZKRS_K_VOID;
    }
}

/*
 * Allocates tracking context for an in-flight multi request, capturing the caller's
 * results array and sub-operation decoding categories.
 */
static struct zks_req *zks_req_new_multi(void_completion_t completion, const void *data,
                                         zoo_op_result_t *results, int count,
                                         const zoo_op_t *ops)
{
    zks_fn fn = ZKS_FN(completion);
    struct zks_req *r;
    int i;
    /* Synchronous zoo_multi invocations pass ZKS_SYNC_MARKER and the barrier context. */
    r = zks_req_new(ZKRS_K_MULTI, zks_req_mode_of(fn), fn, data);
    if (r == NULL) {
        return NULL;
    }
    r->results = results;
    if (count <= 0) {
        return r;
    }
    r->op_kind = malloc((size_t)count);
    if (r->op_kind == NULL) {
        zks_req_free(r);
        return NULL;
    }
    r->count = count;
    for (i = 0; i < count; i++) {
        r->op_kind[i] = zks_multi_kind(ops[i].type);
    }
    return r;
}

int zoo_amulti(zhandle_t *zh, int count, const zoo_op_t *ops, zoo_op_result_t *results,
               void_completion_t completion, const void *data)
{
    zkrs_request rq = ZKRS_REQUEST_INIT(ZKRS_OP_MULTI);
    struct zks_req *r;
    int i;
    ZKS_RETURN_IF_NO_HANDLE(zh, ZBADARGUMENTS);
    /* Validates parameter bounds prior to mutating caller-provided result slots. */
    if (count < 0 || (count > 0 && (ops == NULL || results == NULL))) {
        return ZBADARGUMENTS;
    }
    /* Bind caller supplied result buffer pointers from the operation descriptors. */
    for (i = 0; i < count && zks_multi_supported(ops[i].type); i++) {
        if (ops[i].type == ZOO_CREATE_OP || ops[i].type == ZOO_CREATE_CONTAINER_OP) {
            results[i].value = ops[i].create_op.buf;
            results[i].valuelen = ops[i].create_op.buflen;
        } else if (ops[i].type == ZOO_SETDATA_OP) {
            results[i].stat = ops[i].set_op.stat;
        }
    }
    r = zks_req_new_multi(completion, data, results, count, ops);
    /*
     * The Rust FFI layer processes operations sequentially up to the first unrecognized
     * operation type, reproducing legacy error truncation semantics.
     */
    rq.ops = ops;
    rq.ops_len = i < count ? i + 1 : i;
    rq.count = count;
    return zks_submit_prepared(zh, &rq, r);
}

/*
 * Unpacks decoded multi-response items into caller supplied result structures before invoking
 * the user completion callback.
 */
ZKS_HIDDEN void zks_multi_complete(struct zks_req *r, const zkrs_result_view *v)
{
    ZKS_CALL_VOID(zkrs_write_multi(v, r->results, r->op_kind, r->count));
    if (r->fn.v != NULL) {
        ZKS_APP_CALL(r->fn.v(v->rc, r->data));
    }
}
