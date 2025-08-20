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

#ifndef ZK_SASL_H_
#define ZK_SASL_H_

#ifdef __cplusplus
extern "C" {
#endif

/**
 * \brief enumerates SASL authentication states.  Corresponds to
 * org.apache.zookeeper.client.ZooKeeperSaslClient.SaslState.
 */
typedef enum {
    ZOO_SASL_INITIAL,
    ZOO_SASL_INTERMEDIATE,
    ZOO_SASL_COMPLETE,
    ZOO_SASL_FAILED
} ZooSaslState;

/**
 * \brief zoo_sasl_client_t structure.
 *
 * This structure holds (a copy of) the original SASL parameters, the
 * Cyrus SASL client "object," and the current authentication state.
 *
 * See \ref zoo_sasl_client_create and \ref zoo_sasl_client_destroy.
 */
typedef struct _zoo_sasl_client {
    zoo_sasl_params_t params;
    sasl_conn_t *conn;
    ZooSaslState state;
    unsigned char is_gssapi;
    unsigned char is_last_packet;
} zoo_sasl_client_t;

/**
 * \brief allocates a \ref zoo_sasl_client_t "object."
 *
 * \param sasl_params The SASL parameters to use.  Note while "string"
 *   parameters are copied, the callbacks array is simply referenced:
 *   its lifetime must therefore cover that of the handle.
 * \return the client object, or NULL on failure
 */
zoo_sasl_client_t *zoo_sasl_client_create(zoo_sasl_params_t *sasl_params);

/**
 * \brief "destroys" a \ref zoo_sasl_client_t "object" allocated by
 * \ref zoo_sasl_client_create.
 *
 * \param sasl_client the client "object"
 */
void zoo_sasl_client_destroy(zoo_sasl_client_t *sasl_client);

/**
 * \brief put the handle and SASL client in failed state.
 *
 * This sets the SASL client in \ref ZOO_SASL_FAILED state and the
 * ZooKeeper handle in \ref ZOO_AUTH_FAILED_STATE state.
 *
 * \param zh the ZooKeeper handle to mark
 */
void zoo_sasl_mark_failed(zhandle_t *zh);

/**
 * \brief prepares the SASL connection object for the (connecting)
 * ZooKeeper handle.
 *
 * The client is switched to \ref ZOO_SASL_INITIAL state, or \ref
 * ZOO_SASL_FAILED in case of error.
 *
 * \param zh the ZooKeeper handle in \ref ZOO_CONNECTING_STATE state
 * \return ZOK on success, or one of the following on failure:
 *   ZINVALIDSTATE - no SASL client present
 *   ZSYSTEMERROR - SASL library error
 */
int zoo_sasl_connect(zhandle_t *zh);

/**
 * \brief queues an encoded SASL request to ZooKeeper.
 *
 * Note that such packets are added to the front of the queue,
 * pre-empting "normal" communications.
 *
 * \param zh the ZooKeeper handle
 * \param client_data the encoded SASL data, ready to send
 * \param client_data_len the length of \c client_data
 * \return ZOK on success, or ZMARSHALLINGERROR if something went wrong
 */
int queue_sasl_request(zhandle_t *zh, const char *client_data,
     int client_data_len);

/**
 * \brief starts a new SASL authentication session using the
 * parameters provided to \ref zoo_sasl_client_create
 *
 * On entry, the client must be in \ref ZOO_SASL_INITIAL state; this
 * call switches it to \ref ZOO_SASL_INTERMEDIATE state or \ref
 * ZOO_SASL_FAILED in case of error.
 *
 * Note that this is not a "normal" ZK client function; the
 * corresponding packets are added to the front of the queue,
 * pre-empting other requests.
 *
 * \param zh the ZooKeeper handle, with the SASL client in
 *   \ref ZOO_SASL_INITIAL state
 * \return ZOK on success, or one of the following on failure:
 *   ZINVALIDSTATE - client not in expected state
 *   ZSYSTEMERROR - SASL library error
 *   ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
int zoo_sasl_client_start(zhandle_t *zh);

/**
 * \brief performs a step in the SASL authentication process.
 *
 * On entry, the client must be in \ref ZOO_SASL_INTERMEDIATE
 * state. This call switches it to \ref ZOO_SASL_COMPLETE state if
 * (and only if) the process is complete--or to \ref ZOO_SASL_FAILED
 * in case of error.
 *
 * \param zh the ZooKeeper handle, with the SASL client in
 *   \ref ZOO_SASL_INTERMEDIATE state
 * \param server_data SASL data from the ZooKeeper server
 * \param server_data_len length of \c server_data
 * \return ZOK on success, or one of the following on failure:
 *   ZINVALIDSTATE - client not in expected state
 *   ZSYSTEMERROR - SASL library error
 *   ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
int zoo_sasl_client_step(zhandle_t *zh, const char *server_data,
    int server_data_len);

#ifdef __cplusplus
}
#endif

#endif /*ZK_SASL_H_*/
