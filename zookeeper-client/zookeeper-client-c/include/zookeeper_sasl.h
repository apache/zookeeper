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

#ifndef ZOOKEEPER_SASL_H_
#define ZOOKEEPER_SASL_H_

#include <sasl/sasl.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * \brief Zookeeper SASL handle.
 *
 * This is an opaque handle to the SASL client state.  A handle is
 * obtained using \zoo_sasl_connect.
 */
typedef struct zoo_sasl_conn zoo_sasl_conn_t;

/**
 * \brief initialize sasl library
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param callbacks sasl callbacks
 * \return ZSYSTEMERROR if initialization failed
 */
ZOOAPI int zoo_sasl_init(zhandle_t *zh, sasl_callback_t *callbacks);

/**
 * \brief creates a sasl connection for the zookeeper socket
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param servicename name of the zookeeper service
 * \param host host of the zookeeper service
 * \param sasl_conn out parameter for the created sasl connection
 * \param mech out parameter for the sasl mechanisms supported by the client
 * \param mechlen out parameter for the count of supported mechs
 * \return ZSYSTEMERRROR if connection failed
 */
ZOOAPI int zoo_sasl_connect(zhandle_t *zh, char *servicename,
        char *host, zoo_sasl_conn_t **sasl_conn, const char **mechs, int *mechlen);

/**
 * \brief authenticates asynchronously
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param zh the connection handle obtained by a call to \ref zoo_sasl_connect
 * \param mech the selected mechanism
 * \param supportedmechs mechanisms supported by client (obtained by a call
 * to \ref zoo_sasl_connect)
 * \return
 */
ZOOAPI int zoo_asasl_authenticate(zhandle_t *th, zoo_sasl_conn_t *conn, const char *mech,
        const char *supportedmechs);

#ifdef THREADED
/**
 * \brief authenticates synchronously
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param zh the connection handle obtained by a call to \ref zoo_sasl_connect
 * \param mech the selected mechanism
 * \param supportedmechs mechanisms supported by client (obtained by a call
 * to \ref zoo_sasl_connect)
 * \return
 */
ZOOAPI int zoo_sasl_authenticate(zhandle_t *th, zoo_sasl_conn_t *conn, const char *mech,
        const char *supportedmechs);
#endif

#ifdef __cplusplus
}
#endif

#endif /* ZOOKEEPER_SASL_H_ */
