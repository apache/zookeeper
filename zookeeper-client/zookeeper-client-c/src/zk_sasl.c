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

#include "config.h"

#ifdef HAVE_SYS_SOCKET_H
#include <sys/socket.h>
#endif

#ifdef HAVE_NETINET_IN_H
#include <netinet/in.h>
#endif

#ifdef HAVE_ARPA_INET_H
#include <arpa/inet.h>
#endif

#ifdef HAVE_NETDB_H
#include <netdb.h>
#endif

#ifdef HAVE_STRING_H
#include <string.h>
#endif

#ifdef HAVE_UNISTD_H
#include <unistd.h>
#endif

#include <zookeeper.h>
#include "zk_sasl.h"
#include "zk_adaptor.h"
#include "zookeeper_log.h"

/*
 * Store a duplicate of src, or NULL, into *target.  Returns
 * ZSYSTEMERROR if no memory could be allocated, ZOK otherwise.
 */
static int _zsasl_strdup(const char **target, const char *src)
{
    if (src) {
        *target = strdup(src);
        if (!*target) {
            return ZSYSTEMERROR;
        }
    }
    return ZOK;
}

/*
 * Free the malloc'ed memory referenced by *location, setting
 * *location to NULL.
 */
static void _zsasl_free(const char **location)
{
    if (*location) {
        free((void*)*location);
        *location = NULL;
    }
}

zoo_sasl_client_t *zoo_sasl_client_create(zoo_sasl_params_t *sasl_params)
{
    zoo_sasl_client_t *sc = calloc(1, sizeof(*sc));
    int rc = ZOK;

    if (!sc) {
        return NULL;
    }

    sc->state = ZOO_SASL_INITIAL;

    rc = rc < 0 ? rc : _zsasl_strdup(&sc->params.service, sasl_params->service);
    rc = rc < 0 ? rc : _zsasl_strdup(&sc->params.host, sasl_params->host);
    rc = rc < 0 ? rc : _zsasl_strdup(&sc->params.mechlist, sasl_params->mechlist);

    sc->params.callbacks = sasl_params->callbacks;

    if (rc != ZOK) {
        zoo_sasl_client_destroy(sc);
        return NULL;
    }

    return sc;
}

void zoo_sasl_client_destroy(zoo_sasl_client_t *sc)
{
    if (!sc) {
        return;
    }

    if (sc->conn) {
        sasl_dispose(&sc->conn);
    }

    sc->params.callbacks = NULL;

    _zsasl_free(&sc->params.service);
    _zsasl_free(&sc->params.host);
    _zsasl_free(&sc->params.mechlist);

    sc->state = ZOO_SASL_FAILED;
}

void zoo_sasl_mark_failed(zhandle_t *zh)
{
    if (zh->sasl_client) {
        zh->sasl_client->state = ZOO_SASL_FAILED;
    }
    zh->state = ZOO_AUTH_FAILED_STATE;
}

/*
 * Put the handle and SASL client in failed state if rc is not ZOK.
 * Returns rc.
 */
static int _zsasl_fail(zhandle_t *zh, int rc)
{
    if (rc != ZOK) {
        zoo_sasl_mark_failed(zh);
        LOG_ERROR(LOGCALLBACK(zh), "SASL authentication failed. rc=%d", rc);
    }
    return rc;
}

/*
 * Get and format the host and port associated with a socket address
 * into Cyrus SASL format.  Optionally also store the host name in
 * provided buffer.
 *
 * \param addr the socket address
 * \param salen the length of addr
 * \param ipport_buf the formatted output buffer, of size
 *   NI_MAXHOST + NI_MAXSERV
 * \param opt_host_buf the host name buffer, of size NI_MAXHOST, or
 *   NULL for none
 * \return ZOK if successful
 */
static int _zsasl_getipport(zhandle_t *zh,
                            const struct sockaddr *addr, socklen_t salen,
                            char *ipport_buf, char *opt_host_buf)
{
    char hbuf[NI_MAXHOST], pbuf[NI_MAXSERV];
    int niflags, error, written;

    niflags = (NI_NUMERICHOST | NI_NUMERICSERV);
#ifdef NI_WITHSCOPEID
    if (addr->sa_family == AF_INET6) {
        niflags |= NI_WITHSCOPEID;
    }
#endif
    error = getnameinfo(addr, salen, hbuf, sizeof(hbuf), pbuf, sizeof(pbuf),
                        niflags);
    if (error != 0) {
        LOG_ERROR(LOGCALLBACK(zh), "getnameinfo: %s\n", gai_strerror(error));
        return ZSYSTEMERROR;
    }

    written = sprintf(ipport_buf, "%s;%s", hbuf, pbuf);
    if (written < 0) {
        return ZSYSTEMERROR;
    }

    if (opt_host_buf) {
        memcpy(opt_host_buf, hbuf, sizeof(hbuf));
    }

    return ZOK;
}

int zoo_sasl_connect(zhandle_t *zh)
{
    zoo_sasl_client_t *sc = zh->sasl_client;
    char iplocalport[NI_MAXHOST + NI_MAXSERV];
    char ipremoteport[NI_MAXHOST + NI_MAXSERV];
    char host[NI_MAXHOST];
    int rc, sr;
    socklen_t salen;
    struct sockaddr_storage local_ip, remote_ip;

    if (!sc) {
        return _zsasl_fail(zh, ZINVALIDSTATE);
    }

    if (sc->conn) {
        sasl_dispose(&sc->conn);
    }

    sc->state = ZOO_SASL_INITIAL;

    /* set ip addresses */
    salen = sizeof(local_ip);
    if (getsockname(zh->fd->sock, (struct sockaddr *)&local_ip, &salen) < 0) {
        LOG_ERROR(LOGCALLBACK(zh), "getsockname");
        return _zsasl_fail(zh, ZSYSTEMERROR);
    }

    rc = _zsasl_getipport(zh, (const struct sockaddr *)&local_ip, salen,
        iplocalport, NULL);
    if (rc < 0) {
        return _zsasl_fail(zh, rc);
    }

    salen = sizeof(remote_ip);
    if (getpeername(zh->fd->sock, (struct sockaddr *)&remote_ip, &salen) < 0) {
        LOG_ERROR(LOGCALLBACK(zh), "getpeername");
        return _zsasl_fail(zh, ZSYSTEMERROR);
    }

    rc = _zsasl_getipport(zh, (const struct sockaddr *)&remote_ip, salen,
        ipremoteport, host);
    if (rc < 0) {
        return _zsasl_fail(zh, rc);
    }

    LOG_DEBUG(LOGCALLBACK(zh),
        "Zookeeper Host: %s %s", iplocalport, ipremoteport);

    /* client new connection */
    sr = sasl_client_new(
        sc->params.service,
        sc->params.host ? sc->params.host : host,
        iplocalport,
        ipremoteport,
        sc->params.callbacks,
        /*secflags*/0,
        &sc->conn);

    if (sr != SASL_OK) {
        LOG_ERROR(LOGCALLBACK(zh),
            "allocating SASL connection state: %s",
            sasl_errstring(sr, NULL, NULL));
        return _zsasl_fail(zh, ZSYSTEMERROR);
    }

    return ZOK;
}

int zoo_sasl_client_start(zhandle_t *zh)
{
    zoo_sasl_client_t *sc = zh->sasl_client;
    const char *chosenmech;
    const char *client_data;
    unsigned client_data_len;
    int sr, rc = ZOK;

    if (!sc || sc->state != ZOO_SASL_INITIAL) {
        return _zsasl_fail(zh, ZINVALIDSTATE);
    }

    sc->state = ZOO_SASL_INTERMEDIATE;

    sr = sasl_client_start(sc->conn, sc->params.mechlist,
                           NULL, &client_data, &client_data_len, &chosenmech);

    if (sr != SASL_OK && sr != SASL_CONTINUE) {
        LOG_ERROR(LOGCALLBACK(zh),
                  "Starting SASL negotiation: %s %s",
                  sasl_errstring(sr, NULL, NULL),
                  sasl_errdetail(sc->conn));
        return _zsasl_fail(zh, ZSYSTEMERROR);
    }

    LOG_DEBUG(LOGCALLBACK(zh),
              "SASL start sr:%d mech:%s client_data_len:%d",
              sr, chosenmech, (int)client_data_len);

    /*
     * HACK: Without this, the SASL client is unable to reauthenticate
     * with the ZooKeeper ensemble after a disconnect.  This is due to
     * a bug in the JDK's implementation of SASL DIGEST-MD5; the
     * upstream issue is:
     *
     *     JDK-6682540, Incorrect SASL DIGEST-MD5 behavior
     *     https://bugs.openjdk.java.net/browse/JDK-6682540
     *
     * A patch has been committed to the JDK in May 2019, but it will
     * take a while to appear in production:
     *
     *     http://hg.openjdk.java.net/jdk/jdk/rev/0627b8ad33c1
     *
     * As a workaround, we just "empty" the client start in DIGEST-MD5
     * mode, forcing the server to proceed with initial (re)authentication.
     */
    if (client_data_len > 0 && strcmp(chosenmech, "DIGEST-MD5") == 0) {
        LOG_DEBUG(LOGCALLBACK(zh),
                  "SASL start %s: refusing reauthenticate",
                  chosenmech);

        client_data = NULL;
        client_data_len = 0;
    }

    /*
     * ZooKeeperSaslClient.java:285 says:
     *
     *     GSSAPI: server sends a final packet after authentication
     *     succeeds or fails.
     *
     * so we need to keep track of that.
     */
    if (strcmp(chosenmech, "GSSAPI") == 0) {
        sc->is_gssapi = 1;
    }

    if (sr == SASL_CONTINUE || client_data_len > 0) {
        rc = queue_sasl_request(zh, client_data, client_data_len);
        if (rc < 0) {
            return _zsasl_fail(zh, rc);
        }
    }

    return rc;
}

int zoo_sasl_client_step(zhandle_t *zh, const char *server_data,
                         int server_data_len)
{
    zoo_sasl_client_t *sc = zh->sasl_client;
    const char *client_data;
    unsigned client_data_len;
    int sr, rc = ZOK;

    if (!sc || sc->state != ZOO_SASL_INTERMEDIATE) {
        return _zsasl_fail(zh, ZINVALIDSTATE);
    }

    LOG_DEBUG(LOGCALLBACK(zh),
              "SASL intermediate server_data_len:%d", server_data_len);

    if (sc->is_gssapi && sc->is_last_packet) {
        /* See note in zoo_sasl_client_start. */
        sc->is_last_packet = 0;
        sc->state = ZOO_SASL_COMPLETE;
        return rc;
    }

    sr = sasl_client_step(sc->conn, server_data, server_data_len,
            NULL, &client_data, &client_data_len);

    LOG_DEBUG(LOGCALLBACK(zh),
              "SASL intermediate sr:%d client_data_len:%d",
              sr, (int)client_data_len);

    if (sr != SASL_OK && sr != SASL_CONTINUE) {
        LOG_ERROR(LOGCALLBACK(zh),
                  "During SASL negotiation: %s %s",
                  sasl_errstring(sr, NULL, NULL),
                  sasl_errdetail(sc->conn));
        return _zsasl_fail(zh, ZSYSTEMERROR);
    }

    if (sr == SASL_CONTINUE || client_data_len > 0) {
        rc = queue_sasl_request(zh, client_data, client_data_len);
        if (rc < 0) {
            return _zsasl_fail(zh, rc);
        }
    }

    if (sr != SASL_CONTINUE) {
        if (sc->is_gssapi) {
            /* See note in zoo_sasl_client_start. */
            sc->is_last_packet = 1;
        } else {
            sc->state = ZOO_SASL_COMPLETE;
        }
    }

    return rc;
}

/*
 * Cyrus SASL callback for SASL_CB_GETREALM
 */
static int _zsasl_getrealm(void *context, int id, const char **availrealms,
                           const char **result)
{
    const char *realm = (const char*)context;
    *result = realm;
    return SASL_OK;
}

/*
 * Cyrus SASL callback for SASL_CB_USER or SASL_CB_AUTHNAME
 */
static int _zsasl_simple(void *context, int id, const char **result,
                         unsigned *len)
{
    const char *user = (const char*)context;

    /* paranoia check */
    if (!result)
        return SASL_BADPARAM;

    switch (id) {
    case SASL_CB_USER:
        *result = user;
        break;
    case SASL_CB_AUTHNAME:
        *result = user;
        break;
    default:
        return SASL_BADPARAM;
    }

    return SASL_OK;
}

#ifndef HAVE_GETPASSPHRASE
static char *
getpassphrase(const char *prompt) {
    return getpass(prompt);
}
#endif /* ! HAVE_GETPASSPHRASE */

struct zsasl_secret_ctx {
    const char *password_file;
    sasl_secret_t *secret;
};

/*
 * Cyrus SASL callback for SASL_CB_PASS
 */
static int _zsasl_getsecret(sasl_conn_t *conn, void *context, int id,
                            sasl_secret_t **psecret)
{
    struct zsasl_secret_ctx *secret_ctx = (struct zsasl_secret_ctx *)context;
    char buf[1024];
    char *password;
    size_t len;
    sasl_secret_t *x;

    /* paranoia check */
    if (!conn || !psecret || id != SASL_CB_PASS)
        return SASL_BADPARAM;

    if (secret_ctx->password_file) {
        char *p;
        FILE *fh = fopen(secret_ctx->password_file, "rt");
        if (!fh)
            return SASL_FAIL;

        if (!fgets(buf, sizeof(buf), fh)) {
            fclose(fh);
            return SASL_FAIL;
        }

        fclose(fh);

        p = strrchr(buf, '\n');
        if (p)
            *p = '\0';

        password = buf;
    } else {
        password = getpassphrase("Password: ");

        if (!password)
            return SASL_FAIL;
    }

    len = strlen(password);

    x = secret_ctx->secret = (sasl_secret_t *)realloc(
        secret_ctx->secret, sizeof(sasl_secret_t) + len);

    if (!x) {
        memset(password, 0, len);
        return SASL_NOMEM;
    }

    x->len = len;
    strcpy((char *) x->data, password);
    memset(password, 0, len);

    *psecret = x;
    return SASL_OK;
}

typedef int (* sasl_callback_fn_t)(void);

sasl_callback_t *zoo_sasl_make_basic_callbacks(const char *user,
                                               const char *realm,
                                               const char* password_file)
{
    struct zsasl_secret_ctx *secret_ctx;
    const char *user_ctx = NULL;
    const char *realm_ctx = NULL;
    int rc;

    secret_ctx = (struct zsasl_secret_ctx *)calloc(
        1, sizeof(struct zsasl_secret_ctx));
    rc = secret_ctx ? ZOK : ZSYSTEMERROR;

    rc = rc < 0 ? rc : _zsasl_strdup(&user_ctx, user);
    rc = rc < 0 ? rc : _zsasl_strdup(&realm_ctx, realm);
    rc = rc < 0 ? rc : _zsasl_strdup(&secret_ctx->password_file, password_file);

    {
        sasl_callback_t callbacks[] = {
            { SASL_CB_GETREALM, (sasl_callback_fn_t)&_zsasl_getrealm, (void*)realm_ctx },
            { SASL_CB_USER, (sasl_callback_fn_t)&_zsasl_simple, (void*)user_ctx },
            { SASL_CB_AUTHNAME, (sasl_callback_fn_t)&_zsasl_simple, (void*)user_ctx },
            { SASL_CB_PASS, (sasl_callback_fn_t)&_zsasl_getsecret, (void*)secret_ctx },
            { SASL_CB_LIST_END, NULL, NULL }
        };

        sasl_callback_t *xcallbacks = rc < 0 ? NULL : malloc(sizeof(callbacks));

        if (rc < 0 || !xcallbacks) {
            if (secret_ctx) {
                _zsasl_free(&secret_ctx->password_file);
                free(secret_ctx);
                secret_ctx = NULL;
            }
            _zsasl_free(&realm_ctx);
            _zsasl_free(&user_ctx);
            return NULL;
        }

        memcpy(xcallbacks, callbacks, sizeof(callbacks));

        return xcallbacks;
    }
}
