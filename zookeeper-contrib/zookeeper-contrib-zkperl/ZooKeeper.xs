/* Net::ZooKeeper - Perl extension for Apache ZooKeeper
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define PERL_NO_GET_CONTEXT

#include "EXTERN.h"
#include "perl.h"
#include "XSUB.h"

#include <pthread.h>                    /* pthread_mutex_lock(), etc. */
#include <string.h>                     /* memset(), etc. */
#include <limits.h>                     /* CHAR_BIT */
#include <sys/time.h>                   /* gettimeofday() */

#include <zookeeper/zookeeper.h>

#include "build/check_zk_version.h"


#define PACKAGE_NAME "Net::ZooKeeper"
#define PACKAGE_SIGNATURE 19631123

#define STAT_PACKAGE_NAME "Net::ZooKeeper::Stat"
#define STAT_PACKAGE_SIGNATURE 19960512

#define WATCH_PACKAGE_NAME "Net::ZooKeeper::Watch"
#define WATCH_PACKAGE_SIGNATURE 20050326

#define MAX_KEY_NAME_LEN 16             /* "children_version" */

#define NUM_ACL_ENTRY_KEYS 3
#define NUM_KEYS 7
#define NUM_STAT_KEYS 11
#define NUM_WATCH_KEYS 3

#define DEFAULT_RECV_TIMEOUT_MSEC 10000

#define DEFAULT_DATA_BUF_LEN 1023
#define DEFAULT_PATH_BUF_LEN 1023
#define DEFAULT_WATCH_TIMEOUT 60000

#define ZOO_LOG_LEVEL_OFF 0

#ifndef strcaseEQ
#define strcaseEQ(a,b) (!strcasecmp((a),(b)))
#endif


typedef struct Stat zk_stat_t;

typedef HV* Net__ZooKeeper__Stat;

typedef struct zk_watch_t zk_watch_t;

struct zk_watch_t {
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int done;
    int ret;
    int event_type;
    int event_state;
    unsigned int timeout;
    zk_watch_t *prev;
    zk_watch_t *next;
    int ref_count;
};

typedef HV* Net__ZooKeeper__Watch;

typedef struct {
    zhandle_t *handle;
    zk_watch_t *first_watch;
    int data_buf_len;
    int path_buf_len;
    unsigned int watch_timeout;
    const char *hosts;
    int hosts_len;
    int last_ret;
    int last_errno;
} zk_t;

typedef HV* Net__ZooKeeper;

typedef struct {
    I32 signature;
    union {
        zk_t *zk;
        zk_stat_t *stat;
        zk_watch_t *watch;
    } handle;
} zk_handle_t;

typedef struct {
    const char name[MAX_KEY_NAME_LEN + 1];
    U32 name_len;
    size_t offset;
    size_t size;
    U32 hash;
} zk_key_t;


static zk_key_t zk_acl_entry_keys[NUM_ACL_ENTRY_KEYS] = {
    {"perms", 0, 0, 0, 0},
    {"scheme", 0, 0, 0, 0},
    {"id", 0, 0, 0, 0}
};

static zk_key_t zk_keys[NUM_KEYS] = {
    {"data_read_len", 0, 0, 0, 0},
    {"path_read_len", 0, 0, 0, 0},
    {"watch_timeout", 0, 0, 0, 0},
    {"hosts", 0, 0, 0, 0},
    {"session_timeout", 0, 0, 0, 0},
    {"session_id", 0, 0, 0, 0},
    {"pending_watches", 0, 0, 0, 0}
};

static zk_key_t zk_stat_keys[NUM_STAT_KEYS] = {
    {"czxid", 0, offsetof(struct Stat, czxid),
     sizeof(((struct Stat*) 0)->czxid), 0},
    {"mzxid", 0, offsetof(struct Stat, mzxid),
     sizeof(((struct Stat*) 0)->mzxid), 0},
    {"ctime", 0, offsetof(struct Stat, ctime),
     sizeof(((struct Stat*) 0)->ctime), 0},
    {"mtime", 0, offsetof(struct Stat, mtime),
     sizeof(((struct Stat*) 0)->mtime), 0},
    {"version", 0, offsetof(struct Stat, version),
     sizeof(((struct Stat*) 0)->version), 0},
    {"children_version", 0, offsetof(struct Stat, cversion),
     sizeof(((struct Stat*) 0)->cversion), 0},
    {"acl_version", 0, offsetof(struct Stat, aversion),
     sizeof(((struct Stat*) 0)->aversion), 0},
    {"ephemeral_owner", 0, offsetof(struct Stat, ephemeralOwner),
     sizeof(((struct Stat*) 0)->ephemeralOwner), 0},
    {"data_len", 0, offsetof(struct Stat, dataLength),
     sizeof(((struct Stat*) 0)->dataLength), 0},
    {"num_children", 0, offsetof(struct Stat, numChildren),
     sizeof(((struct Stat*) 0)->numChildren), 0},
    {"children_zxid", 0, offsetof(struct Stat, pzxid),
     sizeof(((struct Stat*) 0)->pzxid), 0}
};

static zk_key_t zk_watch_keys[NUM_WATCH_KEYS] = {
    {"timeout", 0, 0, 0, 0},
    {"event", 0, 0, 0, 0},
    {"state", 0, 0, 0, 0}
};


static void _zk_watcher(zhandle_t *handle, int type, int state,
                        const char *path, void *context)
{
    zk_watch_t *watch_ctx = context;

    pthread_mutex_lock(&watch_ctx->mutex);

    watch_ctx->event_type = type;
    watch_ctx->event_state = state;

    watch_ctx->done = 1;

    pthread_cond_signal(&watch_ctx->cond);
    pthread_mutex_unlock(&watch_ctx->mutex);

    return;
}

static void _zk_auth_completion(int ret, const void *data)
{
    zk_watch_t *watch_ctx = (zk_watch_t*) data;

    pthread_mutex_lock(&watch_ctx->mutex);

    watch_ctx->ret = ret;

    watch_ctx->done = 1;

    pthread_cond_signal(&watch_ctx->cond);
    pthread_mutex_unlock(&watch_ctx->mutex);

    return;
}

static zk_watch_t *_zk_create_watch(pTHX)
{
    zk_watch_t *watch;

    Newxz(watch, 1, zk_watch_t);

    if (pthread_mutex_init(&watch->mutex, NULL)) {
        int save_errno = errno;

        Safefree(watch);

        errno = save_errno;
        return NULL;
    }

    if (pthread_cond_init(&watch->cond, NULL)) {
        int save_errno = errno;

        pthread_mutex_destroy(&watch->mutex);
        Safefree(watch);

        errno = save_errno;
        return NULL;
    }

    return watch;
}

static void _zk_destroy_watch(pTHX_ zk_watch_t *watch)
{
    pthread_cond_destroy(&watch->cond);
    pthread_mutex_destroy(&watch->mutex);

    Safefree(watch);

    return;
}

static zk_watch_t *_zk_acquire_watch(pTHX)
{
    zk_watch_t *watch = _zk_create_watch(aTHX);

    if (watch) {
        watch->ref_count = 1;
    }

    return watch;
}

static void _zk_release_watch(pTHX_ zk_watch_t *watch, int list)
{
    if (list) {
        if (watch->prev) {
            watch->prev->next = watch->next;
        }
        if (watch->next) {
            watch->next->prev = watch->prev;
        }
        watch->prev = NULL;
        watch->next = NULL;
    }

    if (--watch->ref_count == 0) {
        _zk_destroy_watch(aTHX_ watch);
    }

    return;
}

static unsigned int _zk_release_watches(pTHX_ zk_watch_t *first_watch,
                                        int final)
{
    zk_watch_t *watch = first_watch->next;
    unsigned int pending_watches = 0;

    while (watch) {
        zk_watch_t *next_watch = watch->next;
        int done = final;

        if (!final) {
            pthread_mutex_lock(&watch->mutex);
            done = watch->done;
            pthread_mutex_unlock(&watch->mutex);
        }

        if (done) {
            _zk_release_watch(aTHX_ watch, 1);
        }
        else {
            ++pending_watches;
        }

        watch = next_watch;
    }

    return pending_watches;
}

static void _zk_replace_watch(pTHX_ zk_handle_t *handle,
                              zk_watch_t *first_watch,
                              zk_watch_t *old_watch, zk_watch_t *new_watch)
{
    zk_watch_t *next_watch;

    new_watch->timeout = old_watch->timeout;

    _zk_release_watch(aTHX_ old_watch, 0);

    /* cleanup any completed watches not tied to a handle */
    _zk_release_watches(aTHX_ first_watch, 0);

    next_watch = first_watch->next;

    new_watch->prev = first_watch;
    new_watch->next = next_watch;

    if (next_watch) {
        next_watch->prev = new_watch;
    }

    first_watch->next = new_watch;

    ++new_watch->ref_count;

    handle->handle.watch = new_watch;

    return;
}

static void _zk_free_acl(pTHX_ struct ACL_vector *acl)
{
    if (acl->data) {
        Safefree(acl->data);
    }

    return;
}

static const char *_zk_fill_acl(pTHX_ AV *acl_arr, struct ACL_vector *acl)
{
    I32 num_acl_entries = av_len(acl_arr) + 1;
    int i;

    Zero(acl, 1, struct ACL_vector);

    if (num_acl_entries <= 0) {
        return NULL;
    }
    else if (num_acl_entries > PERL_INT_MAX) {
        num_acl_entries = PERL_INT_MAX;
    }

    Newx(acl->data, num_acl_entries, struct ACL);

    for (i = 0; i < num_acl_entries; ++i) {
        SV **acl_entry_ptr;
        HV *acl_entry_hash;
        zk_key_t *key;
        SV **val_ptr;
        struct ACL acl_entry;

        acl_entry_ptr = av_fetch(acl_arr, i, 0);

        if (!acl_entry_ptr) {
            continue;
        }

        if (!SvROK(*acl_entry_ptr) ||
            SvTYPE(SvRV(*acl_entry_ptr)) != SVt_PVHV) {
            _zk_free_acl(aTHX_ acl);

            return "invalid ACL entry hash reference";
        }

        acl_entry_hash = (HV*) SvRV(*acl_entry_ptr);

        key = &zk_acl_entry_keys[0];
        val_ptr = hv_fetch(acl_entry_hash, key->name, key->name_len, 0);

        if (!val_ptr) {
            _zk_free_acl(aTHX_ acl);

            return "no ACL entry perms element";
        }

        acl_entry.perms = SvIV(*val_ptr);

        if (!acl_entry.perms || (acl_entry.perms & ~ZOO_PERM_ALL)) {
            _zk_free_acl(aTHX_ acl);

            return "invalid ACL entry perms";
        }

        key = &zk_acl_entry_keys[1];
        val_ptr = hv_fetch(acl_entry_hash, key->name, key->name_len, 0);

        if (!val_ptr) {
            _zk_free_acl(aTHX_ acl);

            return "no ACL entry scheme element";
        }

        acl_entry.id.scheme = SvPV_nolen(*val_ptr);

        key = &zk_acl_entry_keys[2];
        val_ptr = hv_fetch(acl_entry_hash, key->name, key->name_len, 0);

        if (!val_ptr) {
            _zk_free_acl(aTHX_ acl);

            return "no ACL entry id element";
        }

        acl_entry.id.id = SvPV_nolen(*val_ptr);

        ++acl->count;
        acl->data[i] = acl_entry;
    }

    return NULL;
}

static void _zk_fill_acl_entry_hash(pTHX_ struct ACL *acl_entry,
                                    HV *acl_entry_hash)
{
    zk_key_t *key;
    SV *val;

    key = &zk_acl_entry_keys[0];
    val = newSViv(acl_entry->perms);

    if (!hv_store(acl_entry_hash, key->name, key->name_len, val, key->hash)) {
        SvREFCNT_dec(val);
    }

    key = &zk_acl_entry_keys[1];
    val = newSVpv(acl_entry->id.scheme, 0);

    if (!hv_store(acl_entry_hash, key->name, key->name_len, val, key->hash)) {
        SvREFCNT_dec(val);
    }

    key = &zk_acl_entry_keys[2];
    val = newSVpv(acl_entry->id.id, 0);

    if (!hv_store(acl_entry_hash, key->name, key->name_len, val, key->hash)) {
        SvREFCNT_dec(val);
    }

    return;
}

static zk_handle_t *_zk_check_handle_inner(pTHX_ HV *attr_hash,
                                           I32 package_signature)
{
    zk_handle_t *handle = NULL;

    if (SvRMAGICAL(attr_hash)) {
        MAGIC *magic = mg_find((SV*) attr_hash, PERL_MAGIC_ext);

        if (magic) {
            handle = (zk_handle_t*) magic->mg_ptr;

            if (handle->signature != package_signature) {
                handle = NULL;
            }
        }
    }

    return handle;
}

static zk_handle_t *_zk_check_handle_outer(pTHX_ HV *hash, HV **attr_hash_ptr,
                                           const char *package_name,
                                           I32 package_signature)
{
    zk_handle_t *handle = NULL;

    if (attr_hash_ptr) {
        *attr_hash_ptr = NULL;
    }

    if (SvRMAGICAL((SV*) hash)) {
        MAGIC *magic = mg_find((SV*) hash, PERL_MAGIC_tied);

        if (magic) {
            SV *attr = magic->mg_obj;

            if (SvROK(attr) && SvTYPE(SvRV(attr)) == SVt_PVHV &&
                sv_derived_from(attr, package_name)) {
                HV *attr_hash = (HV*) SvRV(attr);

                handle = _zk_check_handle_inner(aTHX_ attr_hash,
                                                package_signature);

                if (handle && attr_hash_ptr) {
                    *attr_hash_ptr = attr_hash;
                }
            }
        }
    }

    return handle;
}

static zk_t *_zk_get_handle_inner(pTHX_ Net__ZooKeeper attr_hash)
{
    zk_handle_t *handle;

    handle = _zk_check_handle_inner(aTHX_ attr_hash, PACKAGE_SIGNATURE);

    return handle ? handle->handle.zk : NULL;
}

static zk_t *_zk_get_handle_outer(pTHX_ Net__ZooKeeper zkh)
{
    zk_handle_t *handle;

    handle = _zk_check_handle_outer(aTHX_ zkh, NULL, PACKAGE_NAME,
                                    PACKAGE_SIGNATURE);

    return handle ? handle->handle.zk : NULL;
}

static zk_stat_t *_zks_get_handle_inner(pTHX_ Net__ZooKeeper__Stat attr_hash)
{
    zk_handle_t *handle;

    handle = _zk_check_handle_inner(aTHX_ attr_hash, STAT_PACKAGE_SIGNATURE);

    return handle ? handle->handle.stat : NULL;
}

static zk_stat_t *_zks_get_handle_outer(pTHX_ Net__ZooKeeper__Stat zksh)
{
    zk_handle_t *handle;

    handle = _zk_check_handle_outer(aTHX_ zksh, NULL, STAT_PACKAGE_NAME,
                                    STAT_PACKAGE_SIGNATURE);

    return handle ? handle->handle.stat : NULL;
}

static zk_watch_t *_zkw_get_handle_inner(pTHX_ Net__ZooKeeper__Watch attr_hash)
{
    zk_handle_t *handle;

    handle = _zk_check_handle_inner(aTHX_ attr_hash, WATCH_PACKAGE_SIGNATURE);

    return handle ? handle->handle.watch : NULL;
}

static zk_watch_t *_zkw_get_handle_outer(pTHX_ Net__ZooKeeper__Watch zkwh,
                                         zk_handle_t **handle_ptr)
{
    zk_handle_t *handle;

    handle = _zk_check_handle_outer(aTHX_ zkwh, NULL, WATCH_PACKAGE_NAME,
                                    WATCH_PACKAGE_SIGNATURE);

    if (handle_ptr) {
        *handle_ptr = handle;
    }

    return handle ? handle->handle.watch : NULL;
}


MODULE = Net::ZooKeeper  PACKAGE = Net::ZooKeeper  PREFIX = zk_

REQUIRE: 1.9508

PROTOTYPES: ENABLE

BOOT:
{
    int i;

    for (i = 0; i < NUM_ACL_ENTRY_KEYS; ++i) {
        zk_key_t *key = &zk_acl_entry_keys[i];

        key->name_len = strlen(key->name);
        PERL_HASH(key->hash, key->name, key->name_len);
    }

    for (i = 0; i < NUM_KEYS; ++i) {
        zk_keys[i].name_len = strlen(zk_keys[i].name);
    }

    for (i = 0; i < NUM_STAT_KEYS; ++i) {
        zk_stat_keys[i].name_len = strlen(zk_stat_keys[i].name);
    }

    for (i = 0; i < NUM_WATCH_KEYS; ++i) {
        zk_watch_keys[i].name_len = strlen(zk_watch_keys[i].name);
    }

    zoo_set_log_stream(NULL);
    zoo_set_debug_level(0);
}


I32
zk_constant(alias=Nullch)
        char *alias
    ALIAS:
        ZOK = ZOK
        ZSYSTEMERROR = ZSYSTEMERROR
        ZRUNTIMEINCONSISTENCY = ZRUNTIMEINCONSISTENCY
        ZDATAINCONSISTENCY = ZDATAINCONSISTENCY
        ZCONNECTIONLOSS = ZCONNECTIONLOSS
        ZMARSHALLINGERROR = ZMARSHALLINGERROR
        ZUNIMPLEMENTED = ZUNIMPLEMENTED
        ZOPERATIONTIMEOUT = ZOPERATIONTIMEOUT
        ZBADARGUMENTS = ZBADARGUMENTS
        ZINVALIDSTATE = ZINVALIDSTATE
        ZAPIERROR = ZAPIERROR
        ZNONODE = ZNONODE
        ZNOAUTH = ZNOAUTH
        ZBADVERSION = ZBADVERSION
        ZNOCHILDRENFOREPHEMERALS = ZNOCHILDRENFOREPHEMERALS
        ZNODEEXISTS = ZNODEEXISTS
        ZNOTEMPTY = ZNOTEMPTY
        ZSESSIONEXPIRED = ZSESSIONEXPIRED
        ZINVALIDCALLBACK = ZINVALIDCALLBACK
        ZINVALIDACL = ZINVALIDACL
        ZAUTHFAILED = ZAUTHFAILED
        ZCLOSING = ZCLOSING
        ZNOTHING = ZNOTHING

        ZOO_EPHEMERAL = ZOO_EPHEMERAL
        ZOO_SEQUENCE = ZOO_SEQUENCE

        ZOO_PERM_READ = ZOO_PERM_READ
        ZOO_PERM_WRITE = ZOO_PERM_WRITE
        ZOO_PERM_CREATE = ZOO_PERM_CREATE
        ZOO_PERM_DELETE = ZOO_PERM_DELETE
        ZOO_PERM_ADMIN = ZOO_PERM_ADMIN
        ZOO_PERM_ALL = ZOO_PERM_ALL

        ZOO_CREATED_EVENT = ZOO_CREATED_EVENT
        ZOO_DELETED_EVENT = ZOO_DELETED_EVENT
        ZOO_CHANGED_EVENT = ZOO_CHANGED_EVENT
        ZOO_CHILD_EVENT = ZOO_CHILD_EVENT
        ZOO_SESSION_EVENT = ZOO_SESSION_EVENT
        ZOO_NOTWATCHING_EVENT = ZOO_NOTWATCHING_EVENT

        ZOO_EXPIRED_SESSION_STATE = ZOO_EXPIRED_SESSION_STATE
        ZOO_AUTH_FAILED_STATE = ZOO_AUTH_FAILED_STATE
        ZOO_CONNECTING_STATE = ZOO_CONNECTING_STATE
        ZOO_ASSOCIATING_STATE = ZOO_ASSOCIATING_STATE
        ZOO_CONNECTED_STATE = ZOO_CONNECTED_STATE

        ZOO_LOG_LEVEL_OFF = ZOO_LOG_LEVEL_OFF
        ZOO_LOG_LEVEL_ERROR = ZOO_LOG_LEVEL_ERROR
        ZOO_LOG_LEVEL_WARN = ZOO_LOG_LEVEL_WARN
        ZOO_LOG_LEVEL_INFO = ZOO_LOG_LEVEL_INFO
        ZOO_LOG_LEVEL_DEBUG = ZOO_LOG_LEVEL_DEBUG
    CODE:
         if (!ix) {
             if (!alias) {
                 alias = GvNAME(CvGV(cv));
             }

             if (strEQ(alias, "ZOK")) {
                 RETVAL = ZOK;
             }
             else if (strEQ(alias, "ZOO_LOG_LEVEL_OFF")) {
                 RETVAL = ZOO_LOG_LEVEL_OFF;
             }
             else {
                 Perl_croak(aTHX_ "unknown " PACKAGE_NAME " constant: %s",
                            alias);
             }
         }
         else {
             RETVAL = ix;
         }
    OUTPUT:
        RETVAL


AV *
zk_acl_constant(alias=Nullch)
        char *alias
    ALIAS:
        ZOO_OPEN_ACL_UNSAFE = 1
        ZOO_READ_ACL_UNSAFE = 2
        ZOO_CREATOR_ALL_ACL = 3
    PREINIT:
        struct ACL_vector acl;
        AV *acl_arr;
        int i;
    PPCODE:
        if (!ix && !alias) {
            alias = GvNAME(CvGV(cv));
        }

        if (ix == 1 || (alias != NULL && strEQ(alias, "ZOO_OPEN_ACL_UNSAFE"))) {
            acl = ZOO_OPEN_ACL_UNSAFE;
        }
        else if (ix == 2 || (alias != NULL && strEQ(alias, "ZOO_READ_ACL_UNSAFE"))) {
            acl = ZOO_READ_ACL_UNSAFE;
        }
        else if (ix == 3 || (alias != NULL && strEQ(alias, "ZOO_CREATOR_ALL_ACL"))) {
            acl = ZOO_CREATOR_ALL_ACL;
        }
        else {
             Perl_croak(aTHX_ "unknown " PACKAGE_NAME " constant: %s", alias);
        }

        acl_arr = newAV();

        av_extend(acl_arr, acl.count);

        for (i = 0; i < acl.count; ++i) {
            HV *acl_entry_hash = newHV();
            SV *val;

            _zk_fill_acl_entry_hash(aTHX_ &acl.data[i], acl_entry_hash);

            val = newRV_noinc((SV*) acl_entry_hash);

            if (!av_store(acl_arr, i, val)) {
                SvREFCNT_dec(val);
            }
        }

        ST(0) = sv_2mortal(newRV_noinc((SV*) acl_arr));

        XSRETURN(1);


void
zk_set_log_level(level)
        int level
    PPCODE:
        if (level < ZOO_LOG_LEVEL_OFF || level > ZOO_LOG_LEVEL_DEBUG) {
            Perl_croak(aTHX_ "invalid log level: %d", level);
        }

        zoo_set_debug_level(level);

        XSRETURN_EMPTY;


void
zk_set_deterministic_conn_order(flag)
        bool flag
    PPCODE:
        zoo_deterministic_conn_order(!!flag);

        XSRETURN_EMPTY;


void
zk_new(package, hosts, ...)
        char *package
        char *hosts
    PREINIT:
        int recv_timeout = DEFAULT_RECV_TIMEOUT_MSEC;
        const clientid_t *client_id = NULL;
        zk_t *zk;
        zk_handle_t *handle;
        HV *stash, *zk_hash, *attr_hash;
        SV *attr;
        int i;
    PPCODE:
        if (items > 2 && items % 2) {
            Perl_croak(aTHX_ "invalid number of arguments");
        }

        for (i = 2; i < items; i += 2) {
            char *key = SvPV_nolen(ST(i));

            if (strcaseEQ(key, "session_timeout")) {
                recv_timeout = SvIV(ST(i + 1));

                /* NOTE: would be nice if requirement in zookeeper_interest()
                 * that recv_timeout*2 be non-negative was documented
                 */
                if (recv_timeout < 0 || recv_timeout > (PERL_INT_MAX >> 1)) {
                    Perl_croak(aTHX_ "invalid session timeout: %d",
                               recv_timeout);
                }
            }
            else if (strcaseEQ(key, "session_id")) {
                STRLEN client_id_len;

                client_id = (const clientid_t*) SvPV(ST(i + 1), client_id_len);

                if (client_id_len != sizeof(clientid_t)) {
                    Perl_croak(aTHX_ "invalid session ID");
                }
            }
        }

        Newxz(zk, 1, zk_t);

        zk->handle = zookeeper_init(hosts, NULL, recv_timeout,
                                    client_id, NULL, 0);

        if (!zk->handle) {
            Safefree(zk);

            XSRETURN_UNDEF;
        }

        Newxz(zk->first_watch, 1, zk_watch_t);

        zk->data_buf_len = DEFAULT_DATA_BUF_LEN;
        zk->path_buf_len = DEFAULT_PATH_BUF_LEN;
        zk->watch_timeout = DEFAULT_WATCH_TIMEOUT;

        zk->hosts_len = strlen(hosts);
        zk->hosts = savepvn(hosts, zk->hosts_len);

        Newx(handle, 1, zk_handle_t);

        handle->signature = PACKAGE_SIGNATURE;
        handle->handle.zk = zk;

        /* We use several tricks from DBI here.  The attr_hash is our
         * empty inner hash; we attach extra magic to it in the form of
         * our zk_handle_t structure.  Then we tie attr_hash to zk_hash,
         * our outer hash.  This is what is passed around (by reference) by
         * callers.
         *
         * Most methods use _zk_get_handle_outer() which finds our inner
         * handle, then returns the zk_t structure from its extra magic
         * pointer.
         *
         * However, the tied hash methods, FETCH(), STORE(), and so forth,
         * receive an already-dereferenced inner handle hash.  This is
         * because we bless both inner and outer handles into this class,
         * so when a caller's code references a hash element in our
         * outer handle, Perl detects its tied magic, looks up the
         * tied object (our inner handle) and invokes the tied hash methods
         * in its class on it.  Since we blessed it into the same class
         * as the outer handle, these methods simply reside in our package.
         */

        stash = gv_stashpv(package, GV_ADDWARN);

        attr_hash = newHV();

        sv_magic((SV*) attr_hash, Nullsv, PERL_MAGIC_ext,
                 (const char*) handle, 0);

        attr = sv_bless(newRV_noinc((SV*) attr_hash), stash);

        zk_hash = newHV();

        sv_magic((SV*) zk_hash, attr, PERL_MAGIC_tied, Nullch, 0);
        SvREFCNT_dec(attr);

        ST(0) = sv_bless(sv_2mortal(newRV_noinc((SV*) zk_hash)), stash);

        XSRETURN(1);


void
zk_DESTROY(zkh)
        Net::ZooKeeper zkh
    PREINIT:
        zk_handle_t *handle;
        HV *attr_hash;
        int ret = ZBADARGUMENTS;
    PPCODE:
        handle = _zk_check_handle_outer(aTHX_ zkh, &attr_hash,
                                        PACKAGE_NAME, PACKAGE_SIGNATURE);

        if (!handle) {
            handle = _zk_check_handle_inner(aTHX_ zkh, PACKAGE_SIGNATURE);

            if (handle) {
                attr_hash = zkh;
                zkh = NULL;
            }
        }

        if (handle) {
            zk_t *zk = handle->handle.zk;

            ret = zookeeper_close(zk->handle);

            /* detach all now-inactive watches still tied to handles */
            _zk_release_watches(aTHX_ zk->first_watch, 1);

            Safefree(zk->first_watch);
            Safefree(zk->hosts);
            Safefree(zk);
            Safefree(handle);

            sv_unmagic((SV*) attr_hash, PERL_MAGIC_ext);
        }

        if (zkh && attr_hash) {
            sv_unmagic((SV*) zkh, PERL_MAGIC_tied);
        }

        if (GIMME_V == G_VOID) {
            XSRETURN_EMPTY;
        }
        else if (ret == ZOK) {
            XSRETURN_YES;
        }
        else {
            XSRETURN_NO;
        }


void
zk_CLONE(package)
        char *package
    PPCODE:
        XSRETURN_EMPTY;


void
zk_CLONE_SKIP(package)
        char *package
    PPCODE:
        XSRETURN_YES;


void
zk_TIEHASH(package, ...)
        char *package
    PPCODE:
        Perl_croak(aTHX_ "tying hashes of class "
                         PACKAGE_NAME " not supported");


void
zk_UNTIE(attr_hash, ref_count)
        Net::ZooKeeper attr_hash
        IV ref_count
    PPCODE:
        Perl_croak(aTHX_ "untying hashes of class "
                         PACKAGE_NAME " not supported");


void
zk_FIRSTKEY(attr_hash)
        Net::ZooKeeper attr_hash
    PREINIT:
        zk_t *zk;
    PPCODE:
        zk = _zk_get_handle_inner(aTHX_ attr_hash);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        ST(0) = sv_2mortal(newSVpvn(zk_keys[0].name, zk_keys[0].name_len));

        XSRETURN(1);


void
zk_NEXTKEY(attr_hash, attr_key)
        Net::ZooKeeper attr_hash
        SV *attr_key
    PREINIT:
        zk_t *zk;
        char *key;
        int i;
    PPCODE:
        zk = _zk_get_handle_inner(aTHX_ attr_hash);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        key = SvPV_nolen(attr_key);

        for (i = 0; i < NUM_KEYS; ++i) {
            if (strcaseEQ(key, zk_keys[i].name)) {
                ++i;

                break;
            }
        }

        if (i < NUM_KEYS) {
            ST(0) = sv_2mortal(newSVpvn(zk_keys[i].name, zk_keys[i].name_len));

            XSRETURN(1);
        }
        else {
            XSRETURN_EMPTY;
        }


void
zk_SCALAR(attr_hash)
        Net::ZooKeeper attr_hash
    PPCODE:
        XSRETURN_YES;


void
zk_FETCH(attr_hash, attr_key)
        Net::ZooKeeper attr_hash
        SV *attr_key
    PREINIT:
        zk_t *zk;
        char *key;
        SV *val = NULL;
    PPCODE:
        zk = _zk_get_handle_inner(aTHX_ attr_hash);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        key = SvPV_nolen(attr_key);

        if (strcaseEQ(key, "data_read_len")) {
            val = newSViv(zk->data_buf_len);
        }
        else if (strcaseEQ(key, "path_read_len")) {
            val = newSViv(zk->path_buf_len);
        }
        else if (strcaseEQ(key, "watch_timeout")) {
            val = newSVuv(zk->watch_timeout);
        }
        else if (strcaseEQ(key, "hosts")) {
            val = newSVpvn(zk->hosts, zk->hosts_len);
        }
        else if (strcaseEQ(key, "session_timeout")) {
            val = newSViv(zoo_recv_timeout(zk->handle));
        }
        else if (strcaseEQ(key, "session_id")) {
            const clientid_t *client_id;
            clientid_t null_client_id;

            client_id = zoo_client_id(zk->handle);

            memset(&null_client_id, 0, sizeof(clientid_t));

            if (!memcmp(client_id, &null_client_id, sizeof(clientid_t))) {
                val = newSVpv("", 0);
            }
            else {
                val = newSVpvn((const char*) client_id, sizeof(clientid_t));
            }
        }
        else if (strcaseEQ(key, "pending_watches")) {
            /* cleanup any completed watches not tied to a handle */
            val = newSVuv(_zk_release_watches(aTHX_ zk->first_watch, 0));
        }

        if (val) {
            ST(0) = sv_2mortal(val);

            XSRETURN(1);
        }

        Perl_warn(aTHX_ "invalid element: %s", key);

        XSRETURN_UNDEF;


void
zk_STORE(attr_hash, attr_key, attr_val)
        Net::ZooKeeper attr_hash
        SV *attr_key
        SV *attr_val
    PREINIT:
        zk_t *zk;
        char *key;
    PPCODE:
        zk = _zk_get_handle_inner(aTHX_ attr_hash);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        key = SvPV_nolen(attr_key);

        if (strcaseEQ(key, "data_read_len")) {
            int val = SvIV(attr_val);

            if (val < 0) {
                Perl_croak(aTHX_ "invalid data read length: %d", val);
            }

            zk->data_buf_len = val;
        }
        else if (strcaseEQ(key, "path_read_len")) {
            int val = SvIV(attr_val);

            if (val < 0) {
                Perl_croak(aTHX_ "invalid path read length: %d", val);
            }

            zk->path_buf_len = val;
        }
        else if (strcaseEQ(key, "watch_timeout")) {
            zk->watch_timeout = SvUV(attr_val);
        }
        else {
            int i;

            for (i = 0; i < NUM_KEYS; ++i) {
                if (strcaseEQ(key, zk_keys[i].name)) {
                    Perl_warn(aTHX_ "read-only element: %s", key);

                    XSRETURN_EMPTY;
                }
            }

            Perl_warn(aTHX_ "invalid element: %s", key);
        }

        XSRETURN_EMPTY;


void
zk_EXISTS(attr_hash, attr_key)
        Net::ZooKeeper attr_hash
        SV *attr_key
    PREINIT:
        zk_t *zk;
        char *key;
        int i;
    PPCODE:
        zk = _zk_get_handle_inner(aTHX_ attr_hash);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        key = SvPV_nolen(attr_key);

        for (i = 0; i < NUM_KEYS; ++i) {
            if (strcaseEQ(key, zk_keys[i].name)) {
                XSRETURN_YES;
            }
        }

        XSRETURN_NO;


void
zk_DELETE(attr_hash, attr_key)
        Net::ZooKeeper attr_hash
        SV *attr_key
    PPCODE:
        Perl_warn(aTHX_ "deleting elements from hashes of class "
                        PACKAGE_NAME " not supported");

        XSRETURN_EMPTY;


void
zk_CLEAR(attr_hash)
        Net::ZooKeeper attr_hash
    PPCODE:
        Perl_warn(aTHX_ "clearing hashes of class "
                        PACKAGE_NAME " not supported");

        XSRETURN_EMPTY;


SV *
zk_get_error(zkh)
        Net::ZooKeeper zkh
    PREINIT:
        zk_t *zk;
    CODE:
        zk = _zk_get_handle_outer(aTHX_ zkh);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        RETVAL = newSViv(zk->last_ret);
        errno = zk->last_errno;
    OUTPUT:
        RETVAL


void
zk_add_auth(zkh, scheme, cert)
        Net::ZooKeeper zkh
        char *scheme
        char *cert; cert = (char *) SvPV($arg, cert_len);
    PREINIT:
        zk_t *zk;
        STRLEN cert_len;
        zk_watch_t *watch;
        int ret;
    PPCODE:
        zk = _zk_get_handle_outer(aTHX_ zkh);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        zk->last_ret = ZOK;
        zk->last_errno = 0;

        if (cert_len > PERL_INT_MAX) {
            Perl_croak(aTHX_ "invalid certificate length: %u", cert_len);
        }

        watch = _zk_create_watch(aTHX);

        if (!watch) {
            /* errno will be set */
            zk->last_ret = ZSYSTEMERROR;
            zk->last_errno = errno;

            XSRETURN_NO;
        }

        errno = 0;
        ret = zoo_add_auth(zk->handle, scheme, cert, cert_len,
                           _zk_auth_completion, watch);

        zk->last_ret = ret;
        zk->last_errno = errno;

        if (ret == ZOK) {
            pthread_mutex_lock(&watch->mutex);

            while (!watch->done) {
                pthread_cond_wait(&watch->cond, &watch->mutex);
            }

            pthread_mutex_unlock(&watch->mutex);

            if (watch->done) {
                ret = watch->ret;
            }
            else {
                ret = ZINVALIDSTATE;
            }

            /* errno may be set while we waited */
            zk->last_ret = ret;
            zk->last_errno = errno;
        }

        _zk_destroy_watch(aTHX_ watch);

        if (ret == ZOK) {
            XSRETURN_YES;
        }
        else {
            XSRETURN_NO;
        }


void
zk_create(zkh, path, buf, ...)
        Net::ZooKeeper zkh
        char *path
        char *buf; buf = (char *) SvPV($arg, buf_len);
    PREINIT:
        zk_t *zk;
        STRLEN buf_len;
        int flags = 0;
        char *path_buf;
        int path_buf_len;
        AV *acl_arr = NULL;
        struct ACL_vector acl;
        int i, ret;
    PPCODE:
        zk = _zk_get_handle_outer(aTHX_ zkh);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        zk->last_ret = ZOK;
        zk->last_errno = 0;

        if (items > 3 && !(items % 2)) {
            Perl_croak(aTHX_ "invalid number of arguments");
        }

        if (buf_len > PERL_INT_MAX) {
            Perl_croak(aTHX_ "invalid data length: %u", buf_len);
        }

        path_buf_len = zk->path_buf_len;

        for (i = 3; i < items; i += 2) {
            char *key = SvPV_nolen(ST(i));

            if (strcaseEQ(key, "path_read_len")) {
                path_buf_len = SvIV(ST(i + 1));

                if (path_buf_len < 2) {
                    Perl_croak(aTHX_ "invalid path read length: %d",
                               path_buf_len);
                }
            }
            else if (strcaseEQ(key, "flags")) {
                flags = SvIV(ST(i + 1));

                if (flags & ~(ZOO_SEQUENCE | ZOO_EPHEMERAL)) {
                    Perl_croak(aTHX_ "invalid create flags: %d", flags);
                }
            }
            else if (strcaseEQ(key, "acl")) {
                const char *err;

                if (!SvROK(ST(i + 1)) || SvTYPE(SvRV(ST(i + 1))) != SVt_PVAV) {
                    Perl_croak(aTHX_ "invalid ACL array reference");
                }

                acl_arr = (AV*) SvRV(ST(i + 1));

                err = _zk_fill_acl(aTHX_ acl_arr, &acl);

                if (err) {
                    Perl_croak(aTHX_ err);
                }
            }
        }

        /* NOTE: would be nice to be able to rely on null-terminated string */
        ++path_buf_len;
        Newxz(path_buf, path_buf_len, char);

        errno = 0;
        ret = zoo_create(zk->handle, path, buf, buf_len,
                         (acl_arr ? &acl : NULL), flags,
                         path_buf, path_buf_len);

        zk->last_ret = ret;
        zk->last_errno = errno;

        if (acl_arr) {
            _zk_free_acl(aTHX_ &acl);
        }

        if (ret == ZOK) {
            ST(0) = sv_newmortal();
#ifdef SV_HAS_TRAILING_NUL
            sv_usepvn_flags(ST(0), path_buf, strlen(path_buf),
                            SV_HAS_TRAILING_NUL);
#else
            sv_usepvn(ST(0), path_buf, strlen(path_buf));
#endif
            SvCUR_set(ST(0), strlen(path_buf));

            XSRETURN(1);
        }

        Safefree(path_buf);

        XSRETURN_UNDEF;


void
zk_delete(zkh, path, ...)
        Net::ZooKeeper zkh
        char *path
    PREINIT:
        zk_t *zk;
        int version = -1;
        int i, ret;
    PPCODE:
        zk = _zk_get_handle_outer(aTHX_ zkh);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        zk->last_ret = ZOK;
        zk->last_errno = 0;

        if (items > 2 && items % 2) {
            Perl_croak(aTHX_ "invalid number of arguments");
        }

        for (i = 2; i < items; i += 2) {
            char *key = SvPV_nolen(ST(i));

            if (strcaseEQ(key, "version")) {
                version = SvIV(ST(i + 1));

                if (version < 0) {
                    Perl_croak(aTHX_ "invalid version requirement: %d",
                               version);
                }
            }
        }

        errno = 0;
        ret = zoo_delete(zk->handle, path, version);

        zk->last_ret = ret;
        zk->last_errno = errno;

        if (ret == ZOK) {
            XSRETURN_YES;
        }
        else {
            XSRETURN_NO;
        }


void
zk_exists(zkh, path, ...)
        Net::ZooKeeper zkh
        char *path
    PREINIT:
        zk_t *zk;
        zk_stat_t *stat = NULL;
        zk_watch_t *old_watch = NULL;
        zk_handle_t *watch_handle = NULL;
        watcher_fn watcher = NULL;
        zk_watch_t *new_watch = NULL;
        int i, ret;
    PPCODE:
        zk = _zk_get_handle_outer(aTHX_ zkh);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        zk->last_ret = ZOK;
        zk->last_errno = 0;

        if (items > 2 && items % 2) {
            Perl_croak(aTHX_ "invalid number of arguments");
        }

        for (i = 2; i < items; i += 2) {
            char *key = SvPV_nolen(ST(i));

            if (strcaseEQ(key, "stat")) {
                if (!SvROK(ST(i + 1)) || SvTYPE(SvRV(ST(i + 1))) != SVt_PVHV ||
                    !sv_derived_from(ST(i + 1), STAT_PACKAGE_NAME)) {
                    Perl_croak(aTHX_ "stat is not a hash reference of "
                                     "type " STAT_PACKAGE_NAME);
                }

                stat = _zks_get_handle_outer(aTHX_ (HV*) SvRV(ST(i + 1)));

                if (!stat) {
                    Perl_croak(aTHX_ "invalid stat handle");
                }
            }
            else if (strcaseEQ(key, "watch")) {
                if (!SvROK(ST(i + 1)) || SvTYPE(SvRV(ST(i + 1))) != SVt_PVHV ||
                    !sv_derived_from(ST(i + 1), WATCH_PACKAGE_NAME)) {
                    Perl_croak(aTHX_ "watch is not a hash reference of "
                                     "type " WATCH_PACKAGE_NAME);
                }

                old_watch = _zkw_get_handle_outer(aTHX_ (HV*) SvRV(ST(i + 1)),
                                                  &watch_handle);

                if (!old_watch) {
                    Perl_croak(aTHX_ "invalid watch handle");
                }
            }
        }

        if (watch_handle) {
            new_watch = _zk_acquire_watch(aTHX);

            if (!new_watch) {
                /* errno will be set */
                zk->last_ret = ZSYSTEMERROR;
                zk->last_errno = errno;

                XSRETURN_NO;
            }

            watcher = _zk_watcher;
        }

        errno = 0;
        ret = zoo_wexists(zk->handle, path, watcher, new_watch, stat);

        zk->last_ret = ret;
        zk->last_errno = errno;

        if (watch_handle) {
            _zk_replace_watch(aTHX_ watch_handle, zk->first_watch,
                              old_watch, new_watch);
        }

        if (ret == ZOK) {
            XSRETURN_YES;
        }
        else {
            XSRETURN_NO;
        }


void
zk_get_children(zkh, path, ...)
        Net::ZooKeeper zkh
        char *path
    PREINIT:
        zk_t *zk;
        zk_watch_t *old_watch = NULL;
        zk_handle_t *watch_handle = NULL;
        watcher_fn watcher = NULL;
        zk_watch_t *new_watch = NULL;
        struct String_vector strings;
        int i, ret;
    PPCODE:
        zk = _zk_get_handle_outer(aTHX_ zkh);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        zk->last_ret = ZOK;
        zk->last_errno = 0;

        if (items > 2 && items % 2) {
            Perl_croak(aTHX_ "invalid number of arguments");
        }

        for (i = 2; i < items; i += 2) {
            char *key = SvPV_nolen(ST(i));

            if (strcaseEQ(key, "watch")) {
                if (!SvROK(ST(i + 1)) || SvTYPE(SvRV(ST(i + 1))) != SVt_PVHV ||
                    !sv_derived_from(ST(i + 1), WATCH_PACKAGE_NAME)) {
                    Perl_croak(aTHX_ "watch is not a hash reference of "
                                     "type " WATCH_PACKAGE_NAME);
                }

                old_watch = _zkw_get_handle_outer(aTHX_ (HV*) SvRV(ST(i + 1)),
                                                  &watch_handle);

                if (!old_watch) {
                    Perl_croak(aTHX_ "invalid watch handle");
                }
            }
        }

        if (watch_handle) {
            new_watch = _zk_acquire_watch(aTHX);

            if (!new_watch) {
                /* errno will be set */
                zk->last_ret = ZSYSTEMERROR;
                zk->last_errno = errno;

                if (GIMME_V == G_ARRAY) {
                    XSRETURN_EMPTY;
                }
                else {
                    XSRETURN_UNDEF;
                }
            }

            watcher = _zk_watcher;
        }

        Zero(&strings, 1, struct String_vector);

        errno = 0;
        ret = zoo_wget_children(zk->handle, path, watcher, new_watch,
                                &strings);

        zk->last_ret = ret;
        zk->last_errno = errno;

        if (watch_handle) {
            _zk_replace_watch(aTHX_ watch_handle, zk->first_watch,
                              old_watch, new_watch);
        }

        if (ret == ZOK) {
            int num_children;

            num_children =
                (strings.count > PERL_INT_MAX) ? PERL_INT_MAX : strings.count;

            if (GIMME_V == G_ARRAY && num_children > 0) {
                EXTEND(SP, num_children);

                for (i = 0; i < num_children; ++i) {
                    ST(i) = sv_2mortal(newSVpv(strings.data[i], 0));
                }
            }

            /* NOTE: would be nice if this were documented as required */
            deallocate_String_vector(&strings);

            if (GIMME_V == G_ARRAY) {
                if (num_children == 0) {
                    XSRETURN_EMPTY;
                }

                XSRETURN(num_children);
            }
            else {
                ST(0) = sv_2mortal(newSViv(num_children));

                XSRETURN(1);
            }
        }
        else {
            if (GIMME_V == G_ARRAY) {
                XSRETURN_EMPTY;
            }
            else {
                XSRETURN_UNDEF;
            }
        }


void
zk_get(zkh, path, ...)
        Net::ZooKeeper zkh
        char *path
    PREINIT:
        zk_t *zk;
        int buf_len;
        zk_stat_t *stat = NULL;
        zk_watch_t *old_watch = NULL;
        zk_handle_t *watch_handle = NULL;
        char *buf;
        watcher_fn watcher = NULL;
        zk_watch_t *new_watch = NULL;
        int i, ret;
    PPCODE:
        zk = _zk_get_handle_outer(aTHX_ zkh);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        zk->last_ret = ZOK;
        zk->last_errno = 0;

        if (items > 2 && items % 2) {
            Perl_croak(aTHX_ "invalid number of arguments");
        }

        buf_len = zk->data_buf_len;

        for (i = 2; i < items; i += 2) {
            char *key = SvPV_nolen(ST(i));

            if (strcaseEQ(key, "data_read_len")) {
                buf_len = SvIV(ST(i + 1));

                if (buf_len < 0) {
                    Perl_croak(aTHX_ "invalid data read length: %d",
                               buf_len);
                }
            }
            else if (strcaseEQ(key, "stat")) {
                if (!SvROK(ST(i + 1)) || SvTYPE(SvRV(ST(i + 1))) != SVt_PVHV ||
                    !sv_derived_from(ST(i + 1), STAT_PACKAGE_NAME)) {
                    Perl_croak(aTHX_ "stat is not a hash reference of "
                                     "type " STAT_PACKAGE_NAME);
                }

                stat = _zks_get_handle_outer(aTHX_ (HV*) SvRV(ST(i + 1)));

                if (!stat) {
                    Perl_croak(aTHX_ "invalid stat handle");
                }
            }
            else if (strcaseEQ(key, "watch")) {
                if (!SvROK(ST(i + 1)) || SvTYPE(SvRV(ST(i + 1))) != SVt_PVHV ||
                    !sv_derived_from(ST(i + 1), WATCH_PACKAGE_NAME)) {
                    Perl_croak(aTHX_ "watch is not a hash reference of "
                                     "type " WATCH_PACKAGE_NAME);
                }

                old_watch = _zkw_get_handle_outer(aTHX_ (HV*) SvRV(ST(i + 1)),
                                                  &watch_handle);

                if (!old_watch) {
                    Perl_croak(aTHX_ "invalid watch handle");
                }
            }
        }

        if (watch_handle) {
            new_watch = _zk_acquire_watch(aTHX);

            if (!new_watch) {
                /* errno will be set */
                zk->last_ret = ZSYSTEMERROR;
                zk->last_errno = errno;

                XSRETURN_UNDEF;
            }

            watcher = _zk_watcher;
        }

        Newx(buf, buf_len + 1, char);

        errno = 0;
        ret = zoo_wget(zk->handle, path, watcher, new_watch,
                       buf, &buf_len, stat);

        zk->last_ret = ret;
        zk->last_errno = errno;

        if (watch_handle) {
            _zk_replace_watch(aTHX_ watch_handle, zk->first_watch,
                              old_watch, new_watch);
        }

        if (ret == ZOK && buf_len != -1) {
            ST(0) = sv_newmortal();
#ifdef SV_HAS_TRAILING_NUL
            buf[buf_len] = '\0';
            sv_usepvn_flags(ST(0), buf, buf_len, SV_HAS_TRAILING_NUL);
#else
            sv_usepvn(ST(0), buf, buf_len);
#endif

            XSRETURN(1);
        }
        else {
            Safefree(buf);

            XSRETURN_UNDEF;
        }


void
zk_set(zkh, path, buf, ...)
        Net::ZooKeeper zkh
        char *path
        char *buf; buf = (char *) SvPV($arg, buf_len);
    PREINIT:
        zk_t *zk;
        int version = -1;
        zk_stat_t *stat = NULL;
        STRLEN buf_len;
        int i, ret;
    PPCODE:
        zk = _zk_get_handle_outer(aTHX_ zkh);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        zk->last_ret = ZOK;
        zk->last_errno = 0;

        if (items > 3 && !(items % 2)) {
            Perl_croak(aTHX_ "invalid number of arguments");
        }

        if (buf_len > PERL_INT_MAX) {
            Perl_croak(aTHX_ "invalid data length: %u", buf_len);
        }

        for (i = 3; i < items; i += 2) {
            char *key = SvPV_nolen(ST(i));

            if (strcaseEQ(key, "version")) {
                version = SvIV(ST(i + 1));

                if (version < 0) {
                    Perl_croak(aTHX_ "invalid version requirement: %d",
                               version);
                }
            }
            else if (strcaseEQ(key, "stat")) {
                if (!SvROK(ST(i + 1)) || SvTYPE(SvRV(ST(i + 1))) != SVt_PVHV ||
                    !sv_derived_from(ST(i + 1), STAT_PACKAGE_NAME)) {
                    Perl_croak(aTHX_ "stat is not a hash reference of "
                                     "type " STAT_PACKAGE_NAME);
                }

                stat = _zks_get_handle_outer(aTHX_ (HV*) SvRV(ST(i + 1)));

                if (!stat) {
                    Perl_croak(aTHX_ "invalid stat handle");
                }
            }
        }

        errno = 0;
        ret = zoo_set2(zk->handle, path, buf, buf_len, version, stat);

        zk->last_ret = ret;
        zk->last_errno = errno;

        if (ret == ZOK) {
            XSRETURN_YES;
        }
        else {
            XSRETURN_NO;
        }


void
zk_get_acl(zkh, path, ...)
        Net::ZooKeeper zkh
        char *path
    PREINIT:
        zk_t *zk;
        zk_stat_t *stat = NULL;
        struct ACL_vector acl;
        int i, ret;
    PPCODE:
        zk = _zk_get_handle_outer(aTHX_ zkh);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        zk->last_ret = ZOK;
        zk->last_errno = 0;

        if (items > 2 && items % 2) {
            Perl_croak(aTHX_ "invalid number of arguments");
        }

        for (i = 2; i < items; i += 2) {
            char *key = SvPV_nolen(ST(i));

            if (strcaseEQ(key, "stat")) {
                if (!SvROK(ST(i + 1)) || SvTYPE(SvRV(ST(i + 1))) != SVt_PVHV ||
                    !sv_derived_from(ST(i + 1), STAT_PACKAGE_NAME)) {
                    Perl_croak(aTHX_ "stat is not a hash reference of "
                                     "type " STAT_PACKAGE_NAME);
                }

                stat = _zks_get_handle_outer(aTHX_ (HV*) SvRV(ST(i + 1)));

                if (!stat) {
                    Perl_croak(aTHX_ "invalid stat handle");
                }
            }
        }

        errno = 0;
        ret = zoo_get_acl(zk->handle, path, &acl, stat);

        zk->last_ret = ret;
        zk->last_errno = errno;

        if (ret == ZOK) {
            int num_acl_entries;

            num_acl_entries =
                (acl.count > PERL_INT_MAX) ? PERL_INT_MAX : acl.count;

            if (GIMME_V == G_ARRAY && num_acl_entries > 0) {
                EXTEND(SP, num_acl_entries);

                for (i = 0; i < num_acl_entries; ++i) {
                    HV *acl_entry_hash = newHV();

                    _zk_fill_acl_entry_hash(aTHX_ &acl.data[i],
                                            acl_entry_hash);

                    ST(i) = sv_2mortal(newRV_noinc((SV*) acl_entry_hash));
                }
            }

            /* NOTE: would be nice if this were documented as required */
            deallocate_ACL_vector(&acl);

            if (GIMME_V == G_ARRAY) {
                if (num_acl_entries == 0) {
                    XSRETURN_EMPTY;
                }

                XSRETURN(num_acl_entries);
            }
            else {
                ST(0) = sv_2mortal(newSViv(num_acl_entries));

                XSRETURN(1);
            }
        }
        else {
            if (GIMME_V == G_ARRAY) {
                XSRETURN_EMPTY;
            }
            else {
                XSRETURN_UNDEF;
            }
        }


void
zk_set_acl(zkh, path, acl_arr, ...)
        Net::ZooKeeper zkh
        char *path
        AV *acl_arr
    PREINIT:
        zk_t *zk;
        const char *err;
        int version = -1;
        struct ACL_vector acl;
        int i, ret;
    PPCODE:
        zk = _zk_get_handle_outer(aTHX_ zkh);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        zk->last_ret = ZOK;
        zk->last_errno = 0;

        if (items > 3 && !(items % 2)) {
            Perl_croak(aTHX_ "invalid number of arguments");
        }

        err = _zk_fill_acl(aTHX_ acl_arr, &acl);

        if (err) {
            Perl_croak(aTHX_ err);
        }

        for (i = 3; i < items; i += 2) {
            char *key = SvPV_nolen(ST(i));

            if (strcaseEQ(key, "version")) {
                version = SvIV(ST(i + 1));

                if (version < 0) {
                    Perl_croak(aTHX_ "invalid version requirement: %d",
                               version);
                }
            }
        }

        errno = 0;
        ret = zoo_set_acl(zk->handle, path, version, &acl);

        zk->last_ret = ret;
        zk->last_errno = errno;

        _zk_free_acl(aTHX_ &acl);

        if (ret == ZOK) {
            XSRETURN_YES;
        }
        else {
            XSRETURN_NO;
        }


void
zk_stat(zkh)
        Net::ZooKeeper zkh
    PREINIT:
        zk_t *zk;
        zk_handle_t *handle;
        HV *stash, *stat_hash, *attr_hash;
        SV *attr;
    PPCODE:
        zk = _zk_get_handle_outer(aTHX_ zkh);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        zk->last_ret = ZOK;
        zk->last_errno = 0;

        Newx(handle, 1, zk_handle_t);

        handle->signature = STAT_PACKAGE_SIGNATURE;

        Newxz(handle->handle.stat, 1, zk_stat_t);

        /* As in zk_new(), we use two levels of magic here. */

        stash = gv_stashpv(STAT_PACKAGE_NAME, GV_ADDWARN);

        attr_hash = newHV();

        sv_magic((SV*) attr_hash, Nullsv, PERL_MAGIC_ext,
                 (const char*) handle, 0);

        attr = sv_bless(newRV_noinc((SV*) attr_hash), stash);

        stat_hash = newHV();

        sv_magic((SV*) stat_hash, attr, PERL_MAGIC_tied, Nullch, 0);
        SvREFCNT_dec(attr);

        ST(0) = sv_bless(sv_2mortal(newRV_noinc((SV*) stat_hash)), stash);

        XSRETURN(1);


void
zk_watch(zkh, ...)
        Net::ZooKeeper zkh
    PREINIT:
        zk_t *zk;
        unsigned int timeout;
        zk_watch_t *watch;
        zk_handle_t *handle;
        HV *stash, *watch_hash, *attr_hash;
        SV *attr;
        int i;
    PPCODE:
        zk = _zk_get_handle_outer(aTHX_ zkh);

        if (!zk) {
            Perl_croak(aTHX_ "invalid handle");
        }

        zk->last_ret = ZOK;
        zk->last_errno = 0;

        if (items > 1 && !(items % 2)) {
            Perl_croak(aTHX_ "invalid number of arguments");
        }

        timeout = zk->watch_timeout;

        for (i = 1; i < items; i += 2) {
            char *key = SvPV_nolen(ST(i));

            if (strcaseEQ(key, "timeout")) {
                timeout = SvUV(ST(i + 1));
            }
        }

        watch = _zk_acquire_watch(aTHX);

        if (!watch) {
            /* errno will be set */
            zk->last_ret = ZSYSTEMERROR;
            zk->last_errno = errno;

            XSRETURN_UNDEF;
        }

        Newx(handle, 1, zk_handle_t);

        handle->signature = WATCH_PACKAGE_SIGNATURE;
        handle->handle.watch = watch;

        /* As in zk_new(), we use two levels of magic here. */

        stash = gv_stashpv(WATCH_PACKAGE_NAME, GV_ADDWARN);

        attr_hash = newHV();

        watch->timeout = timeout;

        sv_magic((SV*) attr_hash, Nullsv, PERL_MAGIC_ext,
                 (const char*) handle, 0);

        attr = sv_bless(newRV_noinc((SV*) attr_hash), stash);

        watch_hash = newHV();

        sv_magic((SV*) watch_hash, attr, PERL_MAGIC_tied, Nullch, 0);
        SvREFCNT_dec(attr);

        ST(0) = sv_bless(sv_2mortal(newRV_noinc((SV*) watch_hash)), stash);

        XSRETURN(1);


MODULE = Net::ZooKeeper  PACKAGE = Net::ZooKeeper::Stat  PREFIX = zks_

void
zks_DESTROY(zksh)
        Net::ZooKeeper::Stat zksh
    PREINIT:
        zk_handle_t *handle;
        HV *attr_hash;
        int ret = ZBADARGUMENTS;
    PPCODE:
        handle = _zk_check_handle_outer(aTHX_ zksh, &attr_hash,
                                        STAT_PACKAGE_NAME,
                                        STAT_PACKAGE_SIGNATURE);

        if (!handle) {
            handle = _zk_check_handle_inner(aTHX_ zksh,
                                            STAT_PACKAGE_SIGNATURE);

            if (handle) {
                attr_hash = zksh;
                zksh = NULL;
            }
        }

        if (handle) {
            ret = ZOK;

            Safefree(handle->handle.stat);
            Safefree(handle);

            sv_unmagic((SV*) attr_hash, PERL_MAGIC_ext);
        }

        if (zksh && attr_hash) {
            sv_unmagic((SV*) zksh, PERL_MAGIC_tied);
        }

        if (GIMME_V == G_VOID) {
            XSRETURN_EMPTY;
        }
        else if (ret == ZOK) {
            XSRETURN_YES;
        }
        else {
            XSRETURN_NO;
        }


void
zks_CLONE(package)
        char *package
    PPCODE:
        XSRETURN_EMPTY;


void
zks_CLONE_SKIP(package)
        char *package
    PPCODE:
        XSRETURN_YES;


void
zks_TIEHASH(package, ...)
        char *package
    PPCODE:
        Perl_croak(aTHX_ "tying hashes of class "
                         STAT_PACKAGE_NAME " not supported");


void
zks_UNTIE(attr_hash, ref_count)
        Net::ZooKeeper::Stat attr_hash
        IV ref_count
    PPCODE:
        Perl_croak(aTHX_ "untying hashes of class "
                         STAT_PACKAGE_NAME " not supported");


void
zks_FIRSTKEY(attr_hash)
        Net::ZooKeeper::Stat attr_hash
    PREINIT:
        zk_stat_t *stat;
    PPCODE:
        stat = _zks_get_handle_inner(aTHX_ attr_hash);

        if (!stat) {
            Perl_croak(aTHX_ "invalid handle");
        }

        ST(0) = sv_2mortal(newSVpvn(zk_stat_keys[0].name,
                                    zk_stat_keys[0].name_len));

        XSRETURN(1);


void
zks_NEXTKEY(attr_hash, attr_key)
        Net::ZooKeeper::Stat attr_hash
        SV *attr_key
    PREINIT:
        zk_stat_t *stat;
        char *key;
        int i;
    PPCODE:
        stat = _zks_get_handle_inner(aTHX_ attr_hash);

        if (!stat) {
            Perl_croak(aTHX_ "invalid handle");
        }

        key = SvPV_nolen(attr_key);

        for (i = 0; i < NUM_STAT_KEYS; ++i) {
            if (strcaseEQ(key, zk_stat_keys[i].name)) {
                ++i;

                break;
            }
        }

        if (i < NUM_STAT_KEYS) {
            ST(0) = sv_2mortal(newSVpvn(zk_stat_keys[i].name,
                                        zk_stat_keys[i].name_len));

            XSRETURN(1);
        }
        else {
            XSRETURN_EMPTY;
        }


void
zks_SCALAR(attr_hash)
        Net::ZooKeeper::Stat attr_hash
    PPCODE:
        XSRETURN_YES;


void
zks_FETCH(attr_hash, attr_key)
        Net::ZooKeeper::Stat attr_hash
        SV *attr_key
    PREINIT:
        zk_stat_t *stat;
        char *key;
        SV *val = NULL;
        int i;
    PPCODE:
        stat = _zks_get_handle_inner(aTHX_ attr_hash);

        if (!stat) {
            Perl_croak(aTHX_ "invalid handle");
        }

        key = SvPV_nolen(attr_key);

        for (i = 0; i < NUM_STAT_KEYS; ++i) {
            if (strcaseEQ(key, zk_stat_keys[i].name)) {
                if (zk_stat_keys[i].size * CHAR_BIT == 32) {
                    val = newSViv(*((int32_t*) (((char*) stat) +
                                                zk_stat_keys[i].offset)));
                }
                else {
                    /* NOTE: %lld is inconsistent, so cast to a double */
                    val = newSVpvf("%.0f", (double)
                                   *((int64_t*) (((char*) stat) +
                                                 zk_stat_keys[i].offset)));
                }

                break;
            }
        }

        if (val) {
            ST(0) = sv_2mortal(val);

            XSRETURN(1);
        }

        Perl_warn(aTHX_ "invalid element: %s", key);

        XSRETURN_UNDEF;


void
zks_STORE(attr_hash, attr_key, attr_val)
        Net::ZooKeeper::Stat attr_hash
        SV *attr_key
        SV *attr_val
    PREINIT:
        zk_stat_t *stat;
        char *key;
        int i;
    PPCODE:
        stat = _zks_get_handle_inner(aTHX_ attr_hash);

        if (!stat) {
            Perl_croak(aTHX_ "invalid handle");
        }

        key = SvPV_nolen(attr_key);

        for (i = 0; i < NUM_STAT_KEYS; ++i) {
            if (strcaseEQ(key, zk_stat_keys[i].name)) {
                Perl_warn(aTHX_ "read-only element: %s", key);

                XSRETURN_EMPTY;
            }
        }

        Perl_warn(aTHX_ "invalid element: %s", key);

        XSRETURN_EMPTY;


void
zks_EXISTS(attr_hash, attr_key)
        Net::ZooKeeper::Stat attr_hash
        SV *attr_key
    PREINIT:
        zk_stat_t *stat;
        char *key;
        int i;
    PPCODE:
        stat = _zks_get_handle_inner(aTHX_ attr_hash);

        if (!stat) {
            Perl_croak(aTHX_ "invalid handle");
        }

        key = SvPV_nolen(attr_key);

        for (i = 0; i < NUM_STAT_KEYS; ++i) {
            if (strcaseEQ(key, zk_stat_keys[i].name)) {
                XSRETURN_YES;
            }
        }

        XSRETURN_NO;


void
zks_DELETE(attr_hash, attr_key)
        Net::ZooKeeper::Stat attr_hash
        SV *attr_key
    PPCODE:
        Perl_warn(aTHX_ "deleting elements from hashes of class "
                        STAT_PACKAGE_NAME " not supported");

        XSRETURN_EMPTY;


void
zks_CLEAR(attr_hash)
        Net::ZooKeeper::Stat attr_hash
    PPCODE:
        Perl_warn(aTHX_ "clearing hashes of class "
                        STAT_PACKAGE_NAME " not supported");

        XSRETURN_EMPTY;


MODULE = Net::ZooKeeper  PACKAGE = Net::ZooKeeper::Watch  PREFIX = zkw_

void
zkw_DESTROY(zkwh)
        Net::ZooKeeper::Watch zkwh
    PREINIT:
        zk_handle_t *handle;
        HV *attr_hash;
        int ret = ZBADARGUMENTS;
    PPCODE:
        handle = _zk_check_handle_outer(aTHX_ zkwh, &attr_hash,
                                        WATCH_PACKAGE_NAME,
                                        WATCH_PACKAGE_SIGNATURE);

        if (!handle) {
            handle = _zk_check_handle_inner(aTHX_ zkwh,
                                            WATCH_PACKAGE_SIGNATURE);

            if (handle) {
                attr_hash = zkwh;
                zkwh = NULL;
            }
        }

        if (handle) {
            ret = ZOK;

            _zk_release_watch(aTHX_ handle->handle.watch, 0);
            Safefree(handle);

            sv_unmagic((SV*) attr_hash, PERL_MAGIC_ext);
        }

        if (zkwh && attr_hash) {
            sv_unmagic((SV*) zkwh, PERL_MAGIC_tied);
        }

        if (GIMME_V == G_VOID) {
            XSRETURN_EMPTY;
        }
        else if (ret == ZOK) {
            XSRETURN_YES;
        }
        else {
            XSRETURN_NO;
        }


void
zkw_CLONE(package)
        char *package
    PPCODE:
        XSRETURN_EMPTY;


void
zkw_CLONE_SKIP(package)
        char *package
    PPCODE:
        XSRETURN_YES;


void
zkw_TIEHASH(package, ...)
        char *package
    PPCODE:
        Perl_croak(aTHX_ "tying hashes of class "
                         WATCH_PACKAGE_NAME " not supported");


void
zkw_UNTIE(attr_hash, ref_count)
        Net::ZooKeeper::Watch attr_hash
        IV ref_count
    PPCODE:
        Perl_croak(aTHX_ "untying hashes of class "
                         WATCH_PACKAGE_NAME " not supported");


void
zkw_FIRSTKEY(attr_hash)
        Net::ZooKeeper::Watch attr_hash
    PREINIT:
        zk_watch_t *watch;
    PPCODE:
        watch = _zkw_get_handle_inner(aTHX_ attr_hash);

        if (!watch) {
            Perl_croak(aTHX_ "invalid handle");
        }

        ST(0) = sv_2mortal(newSVpvn(zk_watch_keys[0].name,
                                    zk_watch_keys[0].name_len));

        XSRETURN(1);


void
zkw_NEXTKEY(attr_hash, attr_key)
        Net::ZooKeeper::Watch attr_hash
        SV *attr_key
    PREINIT:
        zk_watch_t *watch;
        char *key;
        int i;
    PPCODE:
        watch = _zkw_get_handle_inner(aTHX_ attr_hash);

        if (!watch) {
            Perl_croak(aTHX_ "invalid handle");
        }

        key = SvPV_nolen(attr_key);

        for (i = 0; i < NUM_WATCH_KEYS; ++i) {
            if (strcaseEQ(key, zk_watch_keys[i].name)) {
                ++i;

                break;
            }
        }

        if (i < NUM_WATCH_KEYS) {
            ST(0) = sv_2mortal(newSVpvn(zk_watch_keys[i].name,
                                        zk_watch_keys[i].name_len));

            XSRETURN(1);
        }
        else {
            XSRETURN_EMPTY;
        }


void
zkw_SCALAR(attr_hash)
        Net::ZooKeeper::Watch attr_hash
    PPCODE:
        XSRETURN_YES;


void
zkw_FETCH(attr_hash, attr_key)
        Net::ZooKeeper::Watch attr_hash
        SV *attr_key
    PREINIT:
        zk_watch_t *watch;
        char *key;
        SV *val = NULL;
    PPCODE:
        watch = _zkw_get_handle_inner(aTHX_ attr_hash);

        if (!watch) {
            Perl_croak(aTHX_ "invalid handle");
        }

        key = SvPV_nolen(attr_key);

        if (strcaseEQ(key, "timeout")) {
            val = newSVuv(watch->timeout);
        }
        else if (strcaseEQ(key, "event")) {
            val = newSViv(watch->event_type);
        }
        else if (strcaseEQ(key, "state")) {
            val = newSViv(watch->event_state);
        }

        if (val) {
            ST(0) = sv_2mortal(val);

            XSRETURN(1);
        }

        Perl_warn(aTHX_ "invalid element: %s", key);

        XSRETURN_UNDEF;


void
zkw_STORE(attr_hash, attr_key, attr_val)
        Net::ZooKeeper::Watch attr_hash
        SV *attr_key
        SV *attr_val
    PREINIT:
        zk_watch_t *watch;
        char *key;
    PPCODE:
        watch = _zkw_get_handle_inner(aTHX_ attr_hash);

        if (!watch) {
            Perl_croak(aTHX_ "invalid handle");
        }

        key = SvPV_nolen(attr_key);

        if (strcaseEQ(key, "timeout")) {
            watch->timeout = SvUV(attr_val);
        }
        else {
            int i;

            for (i = 0; i < NUM_WATCH_KEYS; ++i) {
                if (strcaseEQ(key, zk_watch_keys[i].name)) {
                    Perl_warn(aTHX_ "read-only element: %s", key);

                    XSRETURN_EMPTY;
                }
            }

            Perl_warn(aTHX_ "invalid element: %s", key);
        }

        XSRETURN_EMPTY;


void
zkw_EXISTS(attr_hash, attr_key)
        Net::ZooKeeper::Watch attr_hash
        SV *attr_key
    PREINIT:
        zk_watch_t *watch;
        char *key;
        int i;
    PPCODE:
        watch = _zkw_get_handle_inner(aTHX_ attr_hash);

        if (!watch) {
            Perl_croak(aTHX_ "invalid handle");
        }

        key = SvPV_nolen(attr_key);

        for (i = 0; i < NUM_WATCH_KEYS; ++i) {
            if (strcaseEQ(key, zk_watch_keys[i].name)) {
                XSRETURN_YES;
            }
        }

        XSRETURN_NO;


void
zkw_DELETE(attr_hash, attr_key)
        Net::ZooKeeper::Watch attr_hash
        SV *attr_key
    PPCODE:
        Perl_warn(aTHX_ "deleting elements from hashes of class "
                        WATCH_PACKAGE_NAME " not supported");

        XSRETURN_EMPTY;


void
zkw_CLEAR(attr_hash)
        Net::ZooKeeper::Watch attr_hash
    PPCODE:
        Perl_warn(aTHX_ "clearing hashes of class "
                        WATCH_PACKAGE_NAME " not supported");

        XSRETURN_EMPTY;


void
zkw_wait(zkwh, ...)
        Net::ZooKeeper::Watch zkwh
    PREINIT:
        zk_watch_t *watch;
        unsigned int timeout;
        struct timeval end_timeval;
        int i, done;
        struct timespec wait_timespec;
    PPCODE:
        watch = _zkw_get_handle_outer(aTHX_ zkwh, NULL);

        if (!watch) {
            Perl_croak(aTHX_ "invalid handle");
        }

        if (items > 1 && !(items % 2)) {
            Perl_croak(aTHX_ "invalid number of arguments");
        }

        timeout = watch->timeout;

        for (i = 1; i < items; i += 2) {
            char *key = SvPV_nolen(ST(i));

            if (strcaseEQ(key, "timeout")) {
                timeout = SvUV(ST(i + 1));
            }
        }

        gettimeofday(&end_timeval, NULL);

        end_timeval.tv_sec += timeout / 1000;
        end_timeval.tv_usec += (timeout % 1000) * 1000;

        wait_timespec.tv_sec = end_timeval.tv_sec;
        wait_timespec.tv_nsec = end_timeval.tv_usec * 1000;

        pthread_mutex_lock(&watch->mutex);

        while (!watch->done) {
            struct timeval curr_timeval;

            gettimeofday(&curr_timeval, NULL);

            if (end_timeval.tv_sec < curr_timeval.tv_sec ||
                (end_timeval.tv_sec == curr_timeval.tv_sec &&
                 end_timeval.tv_usec <= curr_timeval.tv_usec)) {
                break;
            }

            pthread_cond_timedwait(&watch->cond, &watch->mutex,
                                   &wait_timespec);
        }

        done = watch->done;

        pthread_mutex_unlock(&watch->mutex);

        if (done) {
            XSRETURN_YES;
        }
        else {
            XSRETURN_NO;
        }

