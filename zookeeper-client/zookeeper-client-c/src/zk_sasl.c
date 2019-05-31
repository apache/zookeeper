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

#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <string.h>

#include "zk_adaptor.h"
#include "zookeeper_log.h"
#include "zookeeper_sasl.h"

#define SAMPLE_SEC_BUF_SIZE (2048)

struct zoo_sasl_conn {
    sasl_conn_t *sasl_conn;
    int rc;
};

static int _zsasl_complete(int rc, zhandle_t *zh, zoo_sasl_conn_t *conn,
                           const char *serverin, int serverinlen) {
    if (rc != ZOK) {
        LOG_ERROR(LOGCALLBACK(zh), "Reading sasl response failed: %d", rc);
        return rc;
    }

    LOG_DEBUG(LOGCALLBACK(zh), "SASL Authentication complete [%d]", rc);
    return rc;
}


#if THREADED

static int _zsasl_proceed(int sr, zhandle_t *zh, zoo_sasl_conn_t *conn,
        const char *clientout, int clientoutlen);

static int _zsasl_auth(zhandle_t *zh, zoo_sasl_conn_t *conn, const char *mech,
        const char *supportedmechs) {
    const char *clientout;
    const char *chosenmech;
    unsigned clientoutlen;
    int sr = 0;

    /*
     if (supportedmechs) {
     serverin = (char *) malloc(strlen(supportedmechs));
     strncpy(serverin, supportedmechs, strlen(supportedmechs));
     }
     */

    if (mech) {
        if (!strstr(supportedmechs, mech)) {
            LOG_DEBUG(LOGCALLBACK(zh),
                "client doesn't support mandatory mech '%s'\n", mech);
            return ZSYSTEMERROR;
        }
    }

    sr = sasl_client_start(conn->sasl_conn, mech, NULL, &clientout, &clientoutlen,
            &chosenmech);

    LOG_DEBUG(LOGCALLBACK(zh), "SASL Authentication mechanism: %s", chosenmech);

    return _zsasl_proceed(sr, zh, conn, clientout, clientoutlen);
}

static int _zsasl_step(int rc, zhandle_t *zh, zoo_sasl_conn_t *conn,
        const char *serverin, int serverinlen) {
    const char *clientout;
    unsigned clientoutlen;
    int sr;
    int r = rc;

    if (r != ZOK) {
        LOG_ERROR(LOGCALLBACK(zh), "Reading sasl response failed: %d", r);
        return r;
    }

    sr = sasl_client_step(conn->sasl_conn, serverin, serverinlen, NULL, &clientout,
            &clientoutlen);

    return _zsasl_proceed(sr, zh, conn, clientout, clientoutlen);
}

static int _zsasl_proceed(int sr, zhandle_t *zh, zoo_sasl_conn_t *conn,
        const char *clientout, int clientoutlen) {
    int r = ZOK;

    if (sr != SASL_OK && sr != SASL_CONTINUE) {
        LOG_ERROR(LOGCALLBACK(zh),
                  "starting SASL negotiation: %s %s",
                  sasl_errstring(sr, NULL, NULL),
                  sasl_errdetail(conn->sasl_conn));
        return ZSYSTEMERROR;
    }

    if (sr == SASL_CONTINUE || clientoutlen > 0) {
        char serverin[8192];
        int serverinlen = sizeof(serverin);

        r = zoo_sasl(zh, clientout, clientoutlen, serverin, &serverinlen);
        if (sr == SASL_CONTINUE) {
            r = _zsasl_step(r, zh, conn, serverin, serverinlen);
        } else {
            r = _zsasl_complete(r, zh, conn, serverin, serverinlen);
        }
    }

    if (r != ZOK) {
        LOG_ERROR(LOGCALLBACK(zh), "Sending sasl request failed: %d", r);
        return r;
    }
    return r;
}

int zoo_sasl_authenticate(zhandle_t *zh, zoo_sasl_conn_t *conn, const char *mech,
        const char *supportedmechs) {
    return _zsasl_auth(zh, conn, mech, supportedmechs);
}

#endif  /* !THREADED */

static int _zasasl_proceed(int sr, zhandle_t *zh, zoo_sasl_conn_t *conn,
                           const char *clientout, int clientoutlen);

static int _zasasl_auth(zhandle_t *zh, zoo_sasl_conn_t *conn, const char *mech,
                        const char *supportedmechs) {
    const char *clientout;
    const char *chosenmech;
    unsigned clientoutlen;
    int sr = 0;

    /*
     if (supportedmechs) {
     serverin = (char *) malloc(strlen(supportedmechs));
     strncpy(serverin, supportedmechs, strlen(supportedmechs));
     }
     */

    if (mech) {
        if (!strstr(supportedmechs, mech)) {
            LOG_DEBUG(LOGCALLBACK(zh),
                "client doesn't support mandatory mech '%s'\n", mech);
            return ZSYSTEMERROR;
        }
    }

    sr = sasl_client_start(conn->sasl_conn, mech, NULL, &clientout, &clientoutlen,
            &chosenmech);

    LOG_DEBUG(LOGCALLBACK(zh), "SASL Authentication mechanism: %s", chosenmech);

    return _zasasl_proceed(sr, zh, conn, clientout, clientoutlen);
}

static void _zasasl_step(int rc, zhandle_t *zh,
        const char *serverin, int serverinlen, const void *data) {
    zoo_sasl_conn_t *conn = (zoo_sasl_conn_t *)data;
    const char *clientout;
    unsigned clientoutlen;
    int sr;
    int r = rc;

    if (r != ZOK) {
        LOG_ERROR(LOGCALLBACK(zh), "Reading sasl response failed: %d", r);
        return;
    }

    sr = sasl_client_step(conn->sasl_conn, serverin, serverinlen, NULL, &clientout,
            &clientoutlen);

    conn->rc = _zasasl_proceed(sr, zh, conn, clientout, clientoutlen);
}

static void _zasasl_complete(int rc, zhandle_t *zh,
        const char *serverin, int serverinlen, const void *data) {
    zoo_sasl_conn_t *conn = (zoo_sasl_conn_t *)data;

    conn->rc = _zsasl_complete(rc, zh, conn, serverin, serverinlen);
}

static int _zasasl_proceed(int sr, zhandle_t *zh, zoo_sasl_conn_t *conn,
                           const char *clientout, int clientoutlen) {
    int r = ZOK;

    if (sr != SASL_OK && sr != SASL_CONTINUE) {
        LOG_ERROR(LOGCALLBACK(zh),
                  "starting SASL negotiation: %s %s",
                  sasl_errstring(sr, NULL, NULL),
                  sasl_errdetail(conn->sasl_conn));
        return ZSYSTEMERROR;
    }

    if (sr == SASL_CONTINUE || clientoutlen > 0) {
        r = zoo_asasl(zh, clientout, clientoutlen,
                (sr == SASL_CONTINUE) ? _zasasl_step : _zasasl_complete,
                conn);
    }

    if (r != ZOK) {
        LOG_ERROR(LOGCALLBACK(zh), "Sending sasl request failed: %d", r);
        return r;
    }
    return r;
}

int zoo_asasl_authenticate(zhandle_t *zh, zoo_sasl_conn_t *conn, const char *mech,
        const char *supportedmechs) {
    return _zasasl_auth(zh, conn, mech, supportedmechs);
}


int zoo_sasl_init(zhandle_t *zh, sasl_callback_t *callbacks) {
    int rc = sasl_client_init(callbacks);
    if (rc != SASL_OK) {
        LOG_ERROR(LOGCALLBACK(zh),
                  "initializing libsasl: %s", sasl_errstring(rc, NULL, NULL));
        rc = ZSYSTEMERROR;
    } else {
        rc = ZOK;
    }
    return rc;
}

int zoo_sasl_connect(zhandle_t *zh, char *servicename, char *host, zoo_sasl_conn_t **conn_out,
        const char **mechs, int *mechlen) {
    char localaddr[NI_MAXHOST + NI_MAXSERV], remoteaddr[NI_MAXHOST + NI_MAXSERV];
    char hbuf[NI_MAXHOST], pbuf[NI_MAXSERV];
    int r;
    int salen;
    int niflags, error;
    struct sockaddr_storage local_ip, remote_ip;
    zoo_sasl_conn_t *conn;
    //sasl_security_properties_t secprops;
    //sasl_ssf_t extssf = 128;

    /* set ip addresses */
    salen = sizeof(local_ip);
    if (getsockname(zh->fd, (struct sockaddr *) &local_ip,
                    (unsigned *) &salen) < 0) {
        LOG_ERROR(LOGCALLBACK(zh), "getsockname");
        return ZSYSTEMERROR;
    }

    niflags = (NI_NUMERICHOST | NI_NUMERICSERV);
#ifdef NI_WITHSCOPEID
    if (((struct sockaddr *)&local_ip)->sa_family ==AF_INET6)
    niflags |= NI_WITHSCOPEID;
#endif
    error = getnameinfo((struct sockaddr *) &local_ip, salen, hbuf,
            sizeof(hbuf), pbuf, sizeof(pbuf), niflags);
    if (error != 0) {
        LOG_ERROR(LOGCALLBACK(zh), "getnameinfo: %s\n", gai_strerror(error));
        strcpy(hbuf, "unknown");
        strcpy(pbuf, "unknown");
        return ZSYSTEMERROR;
    }
    snprintf(localaddr, sizeof(localaddr), "%s;%s", hbuf, pbuf);

    salen = sizeof(remote_ip);
    if (getpeername(zh->fd, (struct sockaddr *) &remote_ip,
                    (unsigned *) &salen) < 0) {
        LOG_ERROR(LOGCALLBACK(zh), "getpeername");
        return ZSYSTEMERROR;
    }

    niflags = (NI_NUMERICHOST | NI_NUMERICSERV);
#ifdef NI_WITHSCOPEID
    if (((struct sockaddr *)&remote_ip)->sa_family == AF_INET6)
    niflags |= NI_WITHSCOPEID;
#endif
    error = getnameinfo((struct sockaddr *) &remote_ip, salen, hbuf,
            sizeof(hbuf), pbuf, sizeof(pbuf), niflags);
    if (error != 0) {
        LOG_ERROR(LOGCALLBACK(zh), "getnameinfo: %s\n", gai_strerror(error));
        strcpy(hbuf, "unknown");
        strcpy(pbuf, "unknown");
        return ZSYSTEMERROR;
    }
    snprintf(remoteaddr, sizeof(remoteaddr), "%s;%s", hbuf, pbuf);

    LOG_DEBUG(LOGCALLBACK(zh),
              "Zookeeper Host: %s %s",
              localaddr, remoteaddr);

    /*
     memset(&secprops, 0L, sizeof(secprops));
     secprops.maxbufsize = SAMPLE_SEC_BUF_SIZE;
     secprops.max_ssf = 2048;
     secprops.min_ssf = 128;
     */

    conn = (zoo_sasl_conn_t *)malloc(sizeof(zoo_sasl_conn_t));
    if (!conn) {
        LOG_ERROR(LOGCALLBACK(zh), "allocating zoo_sasl_conn_t");
        return ZSYSTEMERROR;
    }
    conn->sasl_conn = NULL;
    conn->rc = ZOK;

    /* client new connection */
    r = sasl_client_new(servicename, host ? host : hbuf, localaddr, remoteaddr,
            NULL, 0, &conn->sasl_conn);
    if (r != SASL_OK) {
        LOG_ERROR(LOGCALLBACK(zh),
            "allocating connection state: %s %s",
            sasl_errstring(r, NULL, NULL));
        return ZSYSTEMERROR;
    } else {
        r = ZOK;
    }

    //sasl_setprop(conn->sasl_conn, SASL_SSF_EXTERNAL, &extssf);

    //sasl_setprop(conn->sasl_conn, SASL_SEC_PROPS, &secprops);

    sasl_listmech(conn->sasl_conn, NULL, NULL, " ", NULL, mechs, NULL, mechlen);

    *conn_out = conn;

    return r;
}
