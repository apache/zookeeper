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


static int sasl_proceed(int sr, zhandle_t *zh, zoo_sasl_conn_t *conn,
        const char *clientout, int clientoutlen, int sync);

static int sasl_auth(zhandle_t *zh, zoo_sasl_conn_t *conn, const char *mech,
        const char *supportedmechs, int sync) {
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
            LOG_DEBUG(LOGCALLBACK(zh), "client doesn't support mandatory mech '%s'\n", mech);
            return ZSYSTEMERROR;
        }
    }

    sr = sasl_client_start((sasl_conn_t *) conn, mech, NULL, &clientout, &clientoutlen,
            &chosenmech);

    LOG_DEBUG(LOGCALLBACK(zh), "SASL Authentication mechanism: %s", chosenmech);

    return sasl_proceed(sr, zh, conn, clientout, clientoutlen, sync);
}

#ifdef THREADED
int zoo_sasl_authenticate(zhandle_t *zh, zoo_sasl_conn_t *conn, const char *mech,
        const char *supportedmechs) {
    return sasl_auth(zh, conn, mech, supportedmechs, 1);
}
#endif

int zoo_asasl_authenticate(zhandle_t *zh, zoo_sasl_conn_t *conn, const char *mech,
        const char *supportedmechs) {
    return sasl_auth(zh, conn, mech, supportedmechs, 0);
}

static int sasl_step(int rc, zhandle_t *zh, zoo_sasl_conn_t *conn, int sync,
        const char *serverin, int serverinlen) {
    const char *clientout;
    unsigned clientoutlen;
    int sr;
    int r = rc;

    if (r != ZOK) {
        LOG_ERROR(LOGCALLBACK(zh), "Reading sasl response failed: %d", r);
        return r;
    }

    sr = sasl_client_step((sasl_conn_t *) conn, serverin, serverinlen, NULL, &clientout,
            &clientoutlen);

    return sasl_proceed(sr, zh, conn, clientout, clientoutlen, sync);
}

static int sasl_step_async(int rc, zhandle_t *zh, zoo_sasl_conn_t *conn,
        const char *serverin, int serverinlen) {
    return sasl_step(rc, zh, conn, 0, serverin, serverinlen);
}

static int sasl_complete(int rc, zhandle_t *zh, zoo_sasl_conn_t *conn,
        const char *serverin, int serverinlen) {
    if (rc != ZOK) {
        LOG_ERROR(LOGCALLBACK(zh), "Reading sasl response failed: %d", rc);
        return rc;
    }

    LOG_DEBUG(LOGCALLBACK(zh), "SASL Authentication complete [%d]", rc);
    return rc;
}

static int sasl_proceed(int sr, zhandle_t *zh, zoo_sasl_conn_t *conn,
        const char *clientout, int clientoutlen, int sync) {
    int r = ZOK;
    if (sr != SASL_OK && sr != SASL_CONTINUE) {
        LOG_ERROR(LOGCALLBACK(zh), "starting SASL negotiation: %s %s",
                sasl_errstring(sr, NULL, NULL),
                sasl_errdetail((sasl_conn_t *) conn));
        return ZSYSTEMERROR;
    }

    if (sr == SASL_CONTINUE || clientoutlen > 0) {
        if(sync) {
#ifdef THREADED
            const char *serverin;
            unsigned serverinlen;

            r = zoo_sasl(zh, conn, clientout, clientoutlen, &serverin, &serverinlen);
            if (sr == SASL_CONTINUE) {
                r = sasl_step(r, zh, conn, sync, serverin, serverinlen);
            } else {
                r = sasl_complete(r, zh, conn, serverin, serverinlen);
            }
#else
            LOG_ERROR(LOGCALLBACK(zh), "Sync sasl_proceed used without threads");
            abort();
#endif
        } else {
            r = zoo_asasl(zh, conn, clientout, clientoutlen,
                           (sr == SASL_CONTINUE) ? sasl_step_async : sasl_complete);
        }
    }
    if (r != ZOK) {
        LOG_ERROR(LOGCALLBACK(zh), "Sending sasl request failed: %d", r);
        return r;
    }
    return r;
}

int zoo_sasl_init(zhandle_t *zh, sasl_callback_t *callbacks) {
    int rc = sasl_client_init(callbacks);
    if (rc != SASL_OK) {
        LOG_ERROR(LOGCALLBACK(zh), "initializing libsasl: %s",
                 sasl_errstring(rc, NULL, NULL));
        rc = ZSYSTEMERROR;
    } else {
        rc = ZOK;
    }
    return rc;
}

int zoo_sasl_connect(zhandle_t *zh, char *servicename, char *host, zoo_sasl_conn_t **sasl_conn,
        const char **mechs, int *mechlen) {
    char localaddr[NI_MAXHOST + NI_MAXSERV], remoteaddr[NI_MAXHOST + NI_MAXSERV];
    char hbuf[NI_MAXHOST], pbuf[NI_MAXSERV];
    int r;
    int salen;
    int niflags, error;
    struct sockaddr_storage local_ip, remote_ip;
    sasl_conn_t *conn;
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

    LOG_DEBUG(LOGCALLBACK(zh), "Zookeeper Host: %s %s %s", "ubook", localaddr, remoteaddr);

    /*
     memset(&secprops, 0L, sizeof(secprops));
     secprops.maxbufsize = SAMPLE_SEC_BUF_SIZE;
     secprops.max_ssf = 2048;
     secprops.min_ssf = 128;
     */

    /* client new connection */
    r = sasl_client_new(servicename, host ? host : hbuf, localaddr, remoteaddr,
            NULL, 0, &conn);
    if (r != SASL_OK) {
        LOG_ERROR(LOGCALLBACK(zh), "allocating connection state: %s %s",
                sasl_errstring(r, NULL, NULL), sasl_errdetail(conn));
        return ZSYSTEMERROR;
    } else {
        r = ZOK;
    }

    //sasl_setprop(conn, SASL_SSF_EXTERNAL, &extssf);

    //sasl_setprop(conn, SASL_SEC_PROPS, &secprops);

    sasl_listmech(conn, NULL, NULL, " ", NULL, mechs, NULL, mechlen);

    *sasl_conn = (zoo_sasl_conn_t *) conn;

    return r;
}
