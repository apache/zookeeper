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

#ifndef ZOOKEEPER_H_
#define ZOOKEEPER_H_

#include <stdlib.h>

/* we must not include config.h as a public header */
#ifndef WIN32
#include <sys/socket.h>
#include <sys/time.h>
#endif

#ifdef WIN32
#include <winsock2.h> /* must always be included before ws2tcpip.h */
#include <ws2tcpip.h> /* for struct sock_addr and socklen_t */
#endif

#ifdef HAVE_OPENSSL_H
#include <openssl/ossl_typ.h>
#endif

#include <stdio.h>
#include <ctype.h>

#ifdef HAVE_CYRUS_SASL_H
#include <sasl/sasl.h>
#endif /* HAVE_CYRUS_SASL_H */

#include "proto.h"
#include "zookeeper_version.h"
#include "recordio.h"
#include "zookeeper.jute.h"

/**
 * \file zookeeper.h
 * \brief ZooKeeper functions and definitions.
 *
 * ZooKeeper is a network service that may be backed by a cluster of
 * synchronized servers. The data in the service is represented as a tree
 * of data nodes. Each node has data, children, an ACL, and status information.
 * The data for a node is read and write in its entirety.
 *
 * ZooKeeper clients can leave watches when they queries the data or children
 * of a node. If a watch is left, that client will be notified of the change.
 * The notification is a one time trigger. Subsequent chances to the node will
 * not trigger a notification unless the client issues a query with the watch
 * flag set. If the client is ever disconnected from the service, the watches do
 * not need to be reset. The client automatically resets the watches.
 *
 * When a node is created, it may be flagged as an ephemeral node. Ephemeral
 * nodes are automatically removed when a client session is closed or when
 * a session times out due to inactivity (the ZooKeeper runtime fills in
 * periods of inactivity with pings). Ephemeral nodes cannot have children.
 *
 * ZooKeeper clients are identified by a server assigned session id. For
 * security reasons The server
 * also generates a corresponding password for a session. A client may save its
 * id and corresponding password to persistent storage in order to use the
 * session across program invocation boundaries.
 */

/* Support for building on various platforms */

// on cygwin we should take care of exporting/importing symbols properly
#ifdef DLL_EXPORT
#    define ZOOAPI __declspec(dllexport)
#else
#  if (defined(__CYGWIN__) || defined(WIN32)) && !defined(USE_STATIC_LIB)
#    define ZOOAPI __declspec(dllimport)
#  else
#    define ZOOAPI
#  endif
#endif

/** zookeeper return constants **/

enum ZOO_ERRORS {
  ZOK = 0, /*!< Everything is OK */

  /** System and server-side errors.
   * This is never thrown by the server, it shouldn't be used other than
   * to indicate a range. Specifically error codes greater than this
   * value, but lesser than {@link #ZAPIERROR}, are system errors. */
  ZSYSTEMERROR = -1,
  ZRUNTIMEINCONSISTENCY = -2, /*!< A runtime inconsistency was found */
  ZDATAINCONSISTENCY = -3, /*!< A data inconsistency was found */
  ZCONNECTIONLOSS = -4, /*!< Connection to the server has been lost */
  ZMARSHALLINGERROR = -5, /*!< Error while marshalling or unmarshalling data */
  ZUNIMPLEMENTED = -6, /*!< Operation is unimplemented */
  ZOPERATIONTIMEOUT = -7, /*!< Operation timeout */
  ZBADARGUMENTS = -8, /*!< Invalid arguments */
  ZINVALIDSTATE = -9, /*!< Invliad zhandle state */
  ZNEWCONFIGNOQUORUM = -13, /*!< No quorum of new config is connected and
                                 up-to-date with the leader of last commmitted
                                 config - try invoking reconfiguration after new
                                 servers are connected and synced */
  ZRECONFIGINPROGRESS = -14, /*!< Reconfiguration requested while another
                                  reconfiguration is currently in progress. This
                                  is currently not supported. Please retry. */
  ZSSLCONNECTIONERROR = -15, /*!< The SSL connection Error */

  /** API errors.
   * This is never thrown by the server, it shouldn't be used other than
   * to indicate a range. Specifically error codes greater than this
   * value are API errors (while values less than this indicate a
   * {@link #ZSYSTEMERROR}).
   */
  ZAPIERROR = -100,
  ZNONODE = -101, /*!< Node does not exist */
  ZNOAUTH = -102, /*!< Not authenticated */
  ZBADVERSION = -103, /*!< Version conflict */
  ZNOCHILDRENFOREPHEMERALS = -108, /*!< Ephemeral nodes may not have children */
  ZNODEEXISTS = -110, /*!< The node already exists */
  ZNOTEMPTY = -111, /*!< The node has children */
  ZSESSIONEXPIRED = -112, /*!< The session has been expired by the server */
  ZINVALIDCALLBACK = -113, /*!< Invalid callback specified */
  ZINVALIDACL = -114, /*!< Invalid ACL specified */
  ZAUTHFAILED = -115, /*!< Client authentication failed */
  ZCLOSING = -116, /*!< ZooKeeper is closing */
  ZNOTHING = -117, /*!< (not error) no server responses to process */
  ZSESSIONMOVED = -118, /*!<session moved to another server, so operation is ignored */
  ZNOTREADONLY = -119, /*!< state-changing request is passed to read-only server */
  ZEPHEMERALONLOCALSESSION = -120, /*!< Attempt to create ephemeral node on a local session */
  ZNOWATCHER = -121, /*!< The watcher couldn't be found */
  ZRECONFIGDISABLED = -123, /*!< Attempts to perform a reconfiguration operation when reconfiguration feature is disabled */
  ZSESSIONCLOSEDREQUIRESASLAUTH = -124, /*!< The session has been closed by server because server requires client to do authentication via configured authentication scheme at server, but client is not configured with required authentication scheme or configured but failed (i.e. wrong credential used.). */
  ZTHROTTLEDOP = -127 /*!< Operation was throttled and not executed at all. please, retry! */

  /* when adding/changing values here also update zerror(int) to return correct error message */
};

#ifdef __cplusplus
extern "C" {
#endif

/**
*  @name Debug levels
*/
typedef enum {ZOO_LOG_LEVEL_ERROR=1,ZOO_LOG_LEVEL_WARN=2,ZOO_LOG_LEVEL_INFO=3,ZOO_LOG_LEVEL_DEBUG=4} ZooLogLevel;

/**
 * @name ACL Consts
 */
extern ZOOAPI const int ZOO_PERM_READ;
extern ZOOAPI const int ZOO_PERM_WRITE;
extern ZOOAPI const int ZOO_PERM_CREATE;
extern ZOOAPI const int ZOO_PERM_DELETE;
extern ZOOAPI const int ZOO_PERM_ADMIN;
extern ZOOAPI const int ZOO_PERM_ALL;


#define ZOO_CONFIG_NODE "/zookeeper/config"

/* flags for zookeeper_init{,2} */
#define ZOO_READONLY         1

/** Disable logging of the client environment at initialization time. */
#define ZOO_NO_LOG_CLIENTENV 2

/** This Id represents anyone. */
extern ZOOAPI struct Id ZOO_ANYONE_ID_UNSAFE;
/** This Id is only usable to set ACLs. It will get substituted with the
 * Id's the client authenticated with.
 */
extern ZOOAPI struct Id ZOO_AUTH_IDS;

/** This is a completely open ACL*/
extern ZOOAPI struct ACL_vector ZOO_OPEN_ACL_UNSAFE;
/** This ACL gives the world the ability to read. */
extern ZOOAPI struct ACL_vector ZOO_READ_ACL_UNSAFE;
/** This ACL gives the creators authentication id's all permissions. */
extern ZOOAPI struct ACL_vector ZOO_CREATOR_ALL_ACL;

/**
 * @name Interest Consts
 * These constants are used to express interest in an event and to
 * indicate to zookeeper which events have occurred. They can
 * be ORed together to express multiple interests. These flags are
 * used in the interest and event parameters of
 * \ref zookeeper_interest and \ref zookeeper_process.
 */
// @{
extern ZOOAPI const int ZOOKEEPER_WRITE;
extern ZOOAPI const int ZOOKEEPER_READ;
// @}

/**
 * @name Create Mode
 *
 * These modes are used by zoo_create to affect node create.
 */
// @{
extern ZOOAPI const int ZOO_PERSISTENT;
extern ZOOAPI const int ZOO_EPHEMERAL;
extern ZOOAPI const int ZOO_PERSISTENT_SEQUENTIAL;
extern ZOOAPI const int ZOO_EPHEMERAL_SEQUENTIAL;
extern ZOOAPI const int ZOO_CONTAINER;
extern ZOOAPI const int ZOO_PERSISTENT_WITH_TTL;
extern ZOOAPI const int ZOO_PERSISTENT_SEQUENTIAL_WITH_TTL;

/**
 * \deprecated ZOO_SEQUENCE Create Flag has been deprecated. Use ZOO_PERSISTENT_SEQUENTIAL
 * or ZOO_EPHEMERAL_SEQUENTIAL instead of it.
 */
extern ZOOAPI const int ZOO_SEQUENCE;
// @}

/**
 * @name State Consts
 * These constants represent the states of a zookeeper connection. They are
 * possible parameters of the watcher callback.
 */
// @{
extern ZOOAPI const int ZOO_EXPIRED_SESSION_STATE;
extern ZOOAPI const int ZOO_AUTH_FAILED_STATE;
extern ZOOAPI const int ZOO_CONNECTING_STATE;
extern ZOOAPI const int ZOO_ASSOCIATING_STATE;
extern ZOOAPI const int ZOO_CONNECTED_STATE;
extern ZOOAPI const int ZOO_READONLY_STATE;
extern ZOOAPI const int ZOO_NOTCONNECTED_STATE;
// @}

/**
 * @name Watch Types
 * These constants indicate the event that caused the watch event. They are
 * possible values of the first parameter of the watcher callback.
 */
// @{
/**
 * \brief a node has been created.
 *
 * This is only generated by watches on non-existent nodes. These watches
 * are set using \ref zoo_exists.
 */
extern ZOOAPI const int ZOO_CREATED_EVENT;
/**
 * \brief a node has been deleted.
 *
 * This is only generated by watches on nodes. These watches
 * are set using \ref zoo_exists and \ref zoo_get.
 */
extern ZOOAPI const int ZOO_DELETED_EVENT;
/**
 * \brief a node has changed.
 *
 * This is only generated by watches on nodes. These watches
 * are set using \ref zoo_exists and \ref zoo_get.
 */
extern ZOOAPI const int ZOO_CHANGED_EVENT;
/**
 * \brief a change as occurred in the list of children.
 *
 * This is only generated by watches on the child list of a node. These watches
 * are set using \ref zoo_get_children or \ref zoo_get_children2.
 */
extern ZOOAPI const int ZOO_CHILD_EVENT;
/**
 * \brief a session has been lost.
 *
 * This is generated when a client loses contact or reconnects with a server.
 */
extern ZOOAPI const int ZOO_SESSION_EVENT;

/**
 * \brief a watch has been removed.
 *
 * This is generated when the server for some reason, probably a resource
 * constraint, will no longer watch a node for a client.
 */
extern ZOOAPI const int ZOO_NOTWATCHING_EVENT;
// @}

/**
 * \brief ZooKeeper handle.
 *
 * This is the handle that represents a connection to the ZooKeeper service.
 * It is needed to invoke any ZooKeeper function. A handle is obtained using
 * \ref zookeeper_init.
 */
typedef struct _zhandle zhandle_t;

/**
 * This structure represents the certificates to zookeeper.
 */
typedef struct _zcert {
    char *certstr;
    char *ca;
    char *cert;
    char *key;
    char *passwd;
} zcert_t;

/**
 * This structure represents the socket to zookeeper.
 */
typedef struct _zsock {
#ifdef WIN32
    SOCKET sock;
#else
    int sock;
#endif
    zcert_t *cert;
#ifdef HAVE_OPENSSL_H
    SSL *ssl_sock;
    SSL_CTX *ssl_ctx;
#endif
} zsock_t;


/**
 * \brief client id structure.
 *
 * This structure holds the id and password for the session. This structure
 * should be treated as opaque. It is received from the server when a session
 * is established and needs to be sent back as-is when reconnecting a session.
 */
typedef struct {
    int64_t client_id;
    char passwd[16];
} clientid_t;

/**
 * \brief zoo_op structure.
 *
 * This structure holds all the arguments necessary for one op as part
 * of a containing multi_op via \ref zoo_multi or \ref zoo_amulti.
 * This structure should be treated as opaque and initialized via
 * \ref zoo_create_op_init, \ref zoo_delete_op_init, \ref zoo_set_op_init
 * and \ref zoo_check_op_init.
 */
typedef struct zoo_op {
    int type;
    union {
        // CREATE
        struct {
            const char *path;
            const char *data;
            int datalen;
	        char *buf;
            int buflen;
            const struct ACL_vector *acl;
            int flags;
            int64_t ttl;
        } create_op;

        // DELETE
        struct {
            const char *path;
            int version;
        } delete_op;

        // SET
        struct {
            const char *path;
            const char *data;
            int datalen;
            int version;
            struct Stat *stat;
        } set_op;

        // CHECK
        struct {
            const char *path;
            int version;
        } check_op;
    };
} zoo_op_t;

/**
 * \brief zoo_create_op_init.
 *
 * This function initializes a zoo_op_t with the arguments for a ZOO_CREATE_OP.
 *
 * \param op A pointer to the zoo_op_t to be initialized.
 * \param path The name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param value The data to be stored in the node.
 * \param valuelen The number of bytes in data. To set the data to be NULL use
 * value as NULL and valuelen as -1.
 * \param acl The initial ACL of the node. The ACL must not be null or empty.
 * \param mode this parameter should be one of the Create Modes.
 * \param path_buffer Buffer which will be filled with the path of the
 *    new node (this might be different than the supplied path
 *    because of the ZOO_SEQUENCE flag).  The path string will always be
 *    null-terminated. This parameter may be NULL if path_buffer_len = 0.
 * \param path_buffer_len Size of path buffer; if the path of the new
 *    node (including space for the null terminator) exceeds the buffer size,
 *    the path string will be truncated to fit.  The actual path of the
 *    new node in the server will not be affected by the truncation.
 *    The path string will always be null-terminated.
 */
void zoo_create_op_init(zoo_op_t *op, const char *path, const char *value,
        int valuelen,  const struct ACL_vector *acl, int mode,
        char *path_buffer, int path_buffer_len);

/**
 * \brief zoo_delete_op_init.
 *
 * This function initializes a zoo_op_t with the arguments for a ZOO_DELETE_OP.
 *
 * \param op A pointer to the zoo_op_t to be initialized.
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param version the expected version of the node. The function will fail if the
 *    actual version of the node does not match the expected version.
 *  If -1 is used the version check will not take place.
 */
void zoo_delete_op_init(zoo_op_t *op, const char *path, int version);

/**
 * \brief zoo_set_op_init.
 *
 * This function initializes an zoo_op_t with the arguments for a ZOO_SETDATA_OP.
 *
 * \param op A pointer to the zoo_op_t to be initialized.
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param buffer the buffer holding data to be written to the node.
 * \param buflen the number of bytes from buffer to write. To set NULL as data
 * use buffer as NULL and buflen as -1.
 * \param version the expected version of the node. The function will fail if
 * the actual version of the node does not match the expected version. If -1 is
 * used the version check will not take place.
 */
void zoo_set_op_init(zoo_op_t *op, const char *path, const char *buffer,
        int buflen, int version, struct Stat *stat);

/**
 * \brief zoo_check_op_init.
 *
 * This function initializes an zoo_op_t with the arguments for a ZOO_CHECK_OP.
 *
 * \param op A pointer to the zoo_op_t to be initialized.
 * \param path The name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param version the expected version of the node. The function will fail if the
 *    actual version of the node does not match the expected version.
 */
void zoo_check_op_init(zoo_op_t *op, const char *path, int version);

/**
 * \brief zoo_op_result structure.
 *
 * This structure holds the result for an op submitted as part of a multi_op
 * via \ref zoo_multi or \ref zoo_amulti.
 */
typedef struct zoo_op_result {
    int err;
    char *value;
	int valuelen;
    struct Stat *stat;
} zoo_op_result_t;

/**
 * \brief signature of a watch function.
 *
 * There are two ways to receive watch notifications: legacy and watcher object.
 * <p>
 * The legacy style, an application wishing to receive events from ZooKeeper must
 * first implement a function with this signature and pass a pointer to the function
 * to \ref zookeeper_init. Next, the application sets a watch by calling one of
 * the getter API that accept the watch integer flag (for example, \ref zoo_aexists,
 * \ref zoo_get, etc).
 * <p>
 * The watcher object style uses an instance of a "watcher object" which in
 * the C world is represented by a pair: a pointer to a function implementing this
 * signature and a pointer to watcher context -- handback user-specific data.
 * When a watch is triggered this function will be called along with
 * the watcher context. An application wishing to use this style must use
 * the getter API functions with the "w" prefix in their names (for example, \ref
 * zoo_awexists, \ref zoo_wget, etc).
 *
 * \param zh zookeeper handle
 * \param type event type. This is one of the *_EVENT constants.
 * \param state connection state. The state value will be one of the *_STATE constants.
 * \param path znode path for which the watcher is triggered. NULL if the event
 * type is ZOO_SESSION_EVENT
 * \param watcherCtx watcher context.
 */
typedef void (*watcher_fn)(zhandle_t *zh, int type,
        int state, const char *path,void *watcherCtx);

/**
 * \brief typedef for setting the log callback. It's a function pointer which
 * returns void and accepts a const char* as its only argument.
 *
 * \param message message to be passed to the callback function.
 */
typedef void (*log_callback_fn)(const char *message);

/**
 * \brief create a handle to used communicate with zookeeper.
 *
 * This method creates a new handle and a zookeeper session that corresponds
 * to that handle. Session establishment is asynchronous, meaning that the
 * session should not be considered established until (and unless) an
 * event of state ZOO_CONNECTED_STATE is received.
 * \param host comma separated host:port pairs, each corresponding to a zk
 *   server. e.g. "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002"
 * \param fn the global watcher callback function. When notifications are
 *   triggered this function will be invoked.
 * \param clientid the id of a previously established session that this
 *   client will be reconnecting to. Pass 0 if not reconnecting to a previous
 *   session. Clients can access the session id of an established, valid,
 *   connection by calling \ref zoo_client_id. If the session corresponding to
 *   the specified clientid has expired, or if the clientid is invalid for
 *   any reason, the returned zhandle_t will be invalid -- the zhandle_t
 *   state will indicate the reason for failure (typically
 *   ZOO_EXPIRED_SESSION_STATE).
 * \param context the handback object that will be associated with this instance
 *   of zhandle_t. Application can access it (for example, in the watcher
 *   callback) using \ref zoo_get_context. The object is not used by zookeeper
 *   internally and can be null.
 * \param flags reserved for future use. Should be set to zero.
 * \return a pointer to the opaque zhandle structure. If it fails to create
 * a new zhandle the function returns NULL and the errno variable
 * indicates the reason.
 */
ZOOAPI zhandle_t *zookeeper_init(const char *host, watcher_fn fn,
  int recv_timeout, const clientid_t *clientid, void *context, int flags);

#ifdef HAVE_OPENSSL_H
ZOOAPI zhandle_t *zookeeper_init_ssl(const char *host, const char *cert, watcher_fn fn,
  int recv_timeout, const clientid_t *clientid, void *context, int flags);
#endif

ZOOAPI void close_zsock(zsock_t *zsock);

/**
 * \brief create a handle to communicate with zookeeper.
 *
 * This function is identical to \ref zookeeper_init except it allows one
 * to specify an additional callback to be used for all logging for that
 * specific connection. For more details on the logging callback see
 * \ref zoo_get_log_callback and \ref zoo_set_log_callback.
 *
 * This method creates a new handle and a zookeeper session that corresponds
 * to that handle. Session establishment is asynchronous, meaning that the
 * session should not be considered established until (and unless) an
 * event of state ZOO_CONNECTED_STATE is received.
 * \param host comma separated host:port pairs, each corresponding to a zk
 *   server. e.g. "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002"
 * \param fn the global watcher callback function. When notifications are
 *   triggered this function will be invoked.
 * \param clientid the id of a previously established session that this
 *   client will be reconnecting to. Pass 0 if not reconnecting to a previous
 *   session. Clients can access the session id of an established, valid,
 *   connection by calling \ref zoo_client_id. If the session corresponding to
 *   the specified clientid has expired, or if the clientid is invalid for
 *   any reason, the returned zhandle_t will be invalid -- the zhandle_t
 *   state will indicate the reason for failure (typically
 *   ZOO_EXPIRED_SESSION_STATE).
 * \param context the handback object that will be associated with this instance
 *   of zhandle_t. Application can access it (for example, in the watcher
 *   callback) using \ref zoo_get_context. The object is not used by zookeeper
 *   internally and can be null.
 * \param flags reserved for future use. Should be set to zero.
 * \param log_callback All log messages will be passed to this callback function.
 *   For more details see \ref zoo_get_log_callback and \ref zoo_set_log_callback.
 * \return a pointer to the opaque zhandle structure. If it fails to create
 * a new zhandle the function returns NULL and the errno variable
 * indicates the reason.
 */
ZOOAPI zhandle_t *zookeeper_init2(const char *host, watcher_fn fn,
  int recv_timeout, const clientid_t *clientid, void *context, int flags,
  log_callback_fn log_callback);

#ifdef HAVE_CYRUS_SASL_H

/**
 * \brief zoo_sasl_params structure.
 *
 * This structure holds the SASL parameters for the connection.
 *
 * Its \c service, \c host and \c callbacks fields are used with Cyrus
 * SASL's \c sasl_client_new; its \c mechlist field with \c
 * sasl_client_start.  Please refer to these functions for precise
 * semantics.
 *
 * Note while "string" parameters are copied into the ZooKeeper
 * client, the callbacks array is simply referenced: its lifetime must
 * therefore cover that of the handle.
 */
typedef struct zoo_sasl_params {
  const char *service;          /*!< The service name, usually "zookeeper" */
  const char *host;             /*!< The server name, e.g. "zk-sasl-md5" */
  const char *mechlist;         /*!< Mechanisms to try, e.g. "DIGEST-MD5" */
  const sasl_callback_t *callbacks;  /*!< List of callbacks */
} zoo_sasl_params_t;

/**
 * \brief create a handle to communicate with zookeeper.
 *
 * This function is identical to \ref zookeeper_init2 except that it
 * allows specifying optional SASL connection parameters.  It is only
 * available if the client library was configured to link against the
 * Cyrus SASL library, and only visible when \c HAVE_CYRUS_SASL_H is defined.
 *
 * This method creates a new handle and a zookeeper session that corresponds
 * to that handle. Session establishment is asynchronous, meaning that the
 * session should not be considered established until (and unless) an
 * event of state ZOO_CONNECTED_STATE is received.
 * \param host comma separated host:port pairs, each corresponding to a zk
 *   server. e.g. "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002"
 * \param fn the global watcher callback function. When notifications are
 *   triggered this function will be invoked.
 * \param clientid the id of a previously established session that this
 *   client will be reconnecting to. Pass 0 if not reconnecting to a previous
 *   session. Clients can access the session id of an established, valid,
 *   connection by calling \ref zoo_client_id. If the session corresponding to
 *   the specified clientid has expired, or if the clientid is invalid for
 *   any reason, the returned zhandle_t will be invalid -- the zhandle_t
 *   state will indicate the reason for failure (typically
 *   ZOO_EXPIRED_SESSION_STATE).
 * \param context the handback object that will be associated with this instance
 *   of zhandle_t. Application can access it (for example, in the watcher
 *   callback) using \ref zoo_get_context. The object is not used by zookeeper
 *   internally and can be null.
 * \param flags reserved for future use. Should be set to zero.
 * \param log_callback All log messages will be passed to this callback function.
 *   For more details see \ref zoo_get_log_callback and \ref zoo_set_log_callback.
 * \param sasl_params a pointer to a \ref zoo_sasl_params_t structure
 *   specifying SASL connection parameters, or NULL to skip SASL
 *   authentication
 * \return a pointer to the opaque zhandle structure. If it fails to create
 * a new zhandle the function returns NULL and the errno variable
 * indicates the reason.
 */
ZOOAPI zhandle_t *zookeeper_init_sasl(const char *host, watcher_fn fn,
  int recv_timeout, const clientid_t *clientid, void *context, int flags,
  log_callback_fn log_callback, zoo_sasl_params_t *sasl_params);

/**
 * \brief allocates and initializes a basic array of Cyrus SASL callbacks.
 *
 * This small helper function makes it easy to pass "static"
 * parameters to Cyrus SASL's underlying callback-based API.  Its use
 * is not mandatory; you can still implement interactive dialogs by
 * defining your own callbacks.
 *
 * \param user the "canned" response to \c SASL_CB_USER and \c SASL_CB_AUTHNAME,
 *   or NULL for none
 * \param realm the "canned" response to \c SASL_CB_GETREALM, or NULL for none
 * \param password_file the name of a file whose first line is read in
 *   response to \c SASL_CB_PASS, or NULL for none
 * \return the freshly-malloc()ed callbacks array, or NULL if allocation
 *   failed.  Deallocate with free(), but only after the corresponding
 *   ZooKeeper handle is closed.
 */
ZOOAPI sasl_callback_t *zoo_sasl_make_basic_callbacks(const char *user,
  const char *realm, const char* password_file);

#endif /* HAVE_CYRUS_SASL_H */

/**
 * \brief update the list of servers this client will connect to.
 *
 * This method allows a client to update the connection string by providing
 * a new comma separated list of host:port pairs, each corresponding to a
 * ZooKeeper server.
 *
 * This function invokes a probabilistic load-balancing algorithm which may cause
 * the client to disconnect from its current host to achieve expected uniform
 * connections per server in the new list. In case the current host to which the
 * client is connected is not in the new list this call will always cause the
 * connection to be dropped. Otherwise, the decision is based on whether the
 * number of servers has increased or decreased and by how much.
 *
 * If the connection is dropped, the client moves to a special "reconfig" mode
 * where he chooses a new server to connect to using the probabilistic algorithm.
 * After finding a server or exhaustively trying all the servers in the new list,
 * the client moves back to the normal mode of operation where it will pick an
 * arbitrary server from the 'host' string.
 *
 * See {@link https://issues.apache.org/jira/browse/ZOOKEEPER-1355} for the
 * protocol and its evaluation,
 *
 * \param host comma separated host:port pairs, each corresponding to a zk
 *   server. e.g. "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002"
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZSYSTEMERROR -- a system (OS) error occured; it's worth checking errno to get details
 */
ZOOAPI int zoo_set_servers(zhandle_t *zh, const char *hosts);

/**
 * \brief sets a minimum delay to observe between "routine" host name
 * resolutions.
 *
 * The client performs regular resolutions of the list of servers
 * passed to \ref zookeeper_init or set with \ref zoo_set_servers in
 * order to detect changes at the DNS level.
 *
 * By default, it does so every time it checks for socket readiness.
 * This results in low latency in the detection of changes, but can
 * lead to heavy DNS traffic when the local cache is not effective.
 *
 * This method allows an application to influence the rate of polling.
 * When delay_ms is set to a value greater than zero, the client skips
 * most "routine" resolutions which would have happened in a window of
 * that many milliseconds since the last successful one.
 *
 * Setting delay_ms to 0 disables this logic, reverting to the default
 * behavior.  Setting it to -1 disables network resolutions during
 * normal operation (but not, e.g., on connection loss).
 *
 * \param delay_ms 0, -1, or the window size in milliseconds
 * \return ZOK on success or ZBADARGUMENTS for invalid input parameters
 */
ZOOAPI int zoo_set_servers_resolution_delay(zhandle_t *zh, int delay_ms);

/**
 * \brief cycle to the next server on the next connection attempt.
 *
 * Note: typically this method should NOT be used outside of testing.
 *
 * This method allows a client to cycle through the list of servers in it's
 * connection pool to be used on the next connection attempt. This function does
 * not actually trigger a connection or state change in any way. Its purpose is
 * to allow testing changing servers on the fly and the probabilistic load
 * balancing algorithm.
 */
ZOOAPI void zoo_cycle_next_server(zhandle_t *zh);

/**
 * \brief get current host:port this client is connecting/connected to.
 *
 * Note: typically this method should NOT be used outside of testing.
 *
 * This method allows a client to get the current host:port that this client
 * is either in the process of connecting to or is currently connected to. This
 * is mainly used for testing purposes but might also come in handy as a general
 * purpose tool to be used by other clients.
 */
ZOOAPI const char* zoo_get_current_server(zhandle_t* zh);

/**
 * \brief close the zookeeper handle and free up any resources.
 *
 * After this call, the client session will no longer be valid. The function
 * will flush any outstanding send requests before return. As a result it may
 * block.
 *
 * This method should only be called only once on a zookeeper handle. Calling
 * twice will cause undefined (and probably undesirable behavior). Calling any other
 * zookeeper method after calling close is undefined behaviour and should be avoided.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \return a result code. Regardless of the error code returned, the zhandle
 * will be destroyed and all resources freed.
 *
 * ZOK - success
 * ZBADARGUMENTS - invalid input parameters
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 * ZOPERATIONTIMEOUT - failed to flush the buffers within the specified timeout.
 * ZCONNECTIONLOSS - a network error occurred while attempting to send request to server
 * ZSYSTEMERROR -- a system (OS) error occurred; it's worth checking errno to get details
 */
ZOOAPI int zookeeper_close(zhandle_t *zh);

/**
 * \brief return the client session id, only valid if the connections
 * is currently connected (ie. last watcher state is ZOO_CONNECTED_STATE)
 */
ZOOAPI const clientid_t *zoo_client_id(zhandle_t *zh);

/**
 * \brief return the timeout for this session, only valid if the connections
 * is currently connected (ie. last watcher state is ZOO_CONNECTED_STATE). This
 * value may change after a server re-connect.
 */
ZOOAPI int zoo_recv_timeout(zhandle_t *zh);

/**
 * \brief return the context for this handle.
 */
ZOOAPI const void *zoo_get_context(zhandle_t *zh);

/**
 * \brief set the context for this handle.
 */
ZOOAPI void zoo_set_context(zhandle_t *zh, void *context);

/**
 * \brief set a watcher function
 * \return previous watcher function
 */
ZOOAPI watcher_fn zoo_set_watcher(zhandle_t *zh,watcher_fn newFn);

/**
 * \brief returns the socket address for the current connection
 * \return socket address of the connected host or NULL on failure, only valid if the
 * connection is current connected
 */
ZOOAPI struct sockaddr* zookeeper_get_connected_host(zhandle_t *zh,
        struct sockaddr *addr, socklen_t *addr_len);

#ifndef THREADED
/**
 * \brief Returns the events that zookeeper is interested in.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param fd is the file descriptor of interest
 * \param interest is an or of the ZOOKEEPER_WRITE and ZOOKEEPER_READ flags to
 *    indicate the I/O of interest on fd.
 * \param tv a timeout value to be used with select/poll system call
 * \return a result code.
 * ZOK - success
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZCONNECTIONLOSS - a network error occurred while attempting to establish
 * a connection to the server
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 * ZOPERATIONTIMEOUT - hasn't received anything from the server for 2/3 of the
 * timeout value specified in zookeeper_init()
 * ZSYSTEMERROR -- a system (OS) error occurred; it's worth checking errno to get details
 */
#ifdef WIN32
ZOOAPI int zookeeper_interest(zhandle_t *zh, SOCKET *fd, int *interest,
	struct timeval *tv);
#else
ZOOAPI int zookeeper_interest(zhandle_t *zh, int *fd, int *interest,
	struct timeval *tv);
#endif

/**
 * \brief Notifies zookeeper that an event of interest has happened.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param events will be an OR of the ZOOKEEPER_WRITE and ZOOKEEPER_READ flags.
 * \return a result code.
 * ZOK - success
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZCONNECTIONLOSS - a network error occurred while attempting to send request to server
 * ZSESSIONEXPIRED - connection attempt failed -- the session's expired
 * ZAUTHFAILED - authentication request failed, e.i. invalid credentials
 * ZRUNTIMEINCONSISTENCY - a server response came out of order
 * ZSYSTEMERROR -- a system (OS) error occurred; it's worth checking errno to get details
 * ZNOTHING -- not an error; simply indicates that there no more data from the server
 *              to be processed (when called with ZOOKEEPER_READ flag).
 */
ZOOAPI int zookeeper_process(zhandle_t *zh, int events);
#endif

/**
 * \brief signature of a completion function for a call that returns void.
 *
 * This method will be invoked at the end of a asynchronous call and also as
 * a result of connection loss or timeout.
 * \param rc the error code of the call. Connection loss/timeout triggers
 * the completion with one of the following error codes:
 * ZCONNECTIONLOSS -- lost connection to the server
 * ZOPERATIONTIMEOUT -- connection timed out
 * Data related events trigger the completion with error codes listed the
 * Exceptions section of the documentation of the function that initiated the
 * call. (Zero indicates call was successful.)
 * \param data the pointer that was passed by the caller when the function
 *   that this completion corresponds to was invoked. The programmer
 *   is responsible for any memory freeing associated with the data
 *   pointer.
 */
typedef void (*void_completion_t)(int rc, const void *data);

/**
 * \brief signature of a completion function that returns a Stat structure.
 *
 * This method will be invoked at the end of a asynchronous call and also as
 * a result of connection loss or timeout.
 * \param rc the error code of the call. Connection loss/timeout triggers
 * the completion with one of the following error codes:
 * ZCONNECTIONLOSS -- lost connection to the server
 * ZOPERATIONTIMEOUT -- connection timed out
 * Data related events trigger the completion with error codes listed the
 * Exceptions section of the documentation of the function that initiated the
 * call. (Zero indicates call was successful.)
 * \param stat a pointer to the stat information for the node involved in
 *   this function. If a non zero error code is returned, the content of
 *   stat is undefined. The programmer is NOT responsible for freeing stat.
 * \param data the pointer that was passed by the caller when the function
 *   that this completion corresponds to was invoked. The programmer
 *   is responsible for any memory freeing associated with the data
 *   pointer.
 */
typedef void (*stat_completion_t)(int rc, const struct Stat *stat,
        const void *data);

/**
 * \brief signature of a completion function that returns data.
 *
 * This method will be invoked at the end of a asynchronous call and also as
 * a result of connection loss or timeout.
 * \param rc the error code of the call. Connection loss/timeout triggers
 * the completion with one of the following error codes:
 * ZCONNECTIONLOSS -- lost connection to the server
 * ZOPERATIONTIMEOUT -- connection timed out
 * Data related events trigger the completion with error codes listed the
 * Exceptions section of the documentation of the function that initiated the
 * call. (Zero indicates call was successful.)
 * \param value the value of the information returned by the asynchronous call.
 *   If a non zero error code is returned, the content of value is undefined.
 *   The programmer is NOT responsible for freeing value.
 * \param value_len the number of bytes in value.
 * \param stat a pointer to the stat information for the node involved in
 *   this function. If a non zero error code is returned, the content of
 *   stat is undefined. The programmer is NOT responsible for freeing stat.
 * \param data the pointer that was passed by the caller when the function
 *   that this completion corresponds to was invoked. The programmer
 *   is responsible for any memory freeing associated with the data
 *   pointer.
 */
typedef void (*data_completion_t)(int rc, const char *value, int value_len,
        const struct Stat *stat, const void *data);

/**
 * \brief signature of a completion function that returns a list of strings.
 *
 * This method will be invoked at the end of a asynchronous call and also as
 * a result of connection loss or timeout.
 * \param rc the error code of the call. Connection loss/timeout triggers
 * the completion with one of the following error codes:
 * ZCONNECTIONLOSS -- lost connection to the server
 * ZOPERATIONTIMEOUT -- connection timed out
 * Data related events trigger the completion with error codes listed the
 * Exceptions section of the documentation of the function that initiated the
 * call. (Zero indicates call was successful.)
 * \param strings a pointer to the structure containng the list of strings of the
 *   names of the children of a node. If a non zero error code is returned,
 *   the content of strings is undefined. The programmer is NOT responsible
 *   for freeing strings.
 * \param data the pointer that was passed by the caller when the function
 *   that this completion corresponds to was invoked. The programmer
 *   is responsible for any memory freeing associated with the data
 *   pointer.
 */
typedef void (*strings_completion_t)(int rc,
        const struct String_vector *strings, const void *data);

/**
 * \brief signature of a completion function that returns a string and stat.
 * .
 *
 * This method will be invoked at the end of a asynchronous call and also as
 * a result of connection loss or timeout.
 * \param rc the error code of the call. Connection loss/timeout triggers
 * the completion with one of the following error codes:
 * ZCONNECTIONLOSS -- lost connection to the server
 * ZOPERATIONTIMEOUT -- connection timed out
 * Data related events trigger the completion with error codes listed the
 * Exceptions section of the documentation of the function that initiated the
 * call. (Zero indicates call was successful.)
 * \param value the value of the string returned.
 * \param stat a pointer to the stat information for the node involved in
 *   this function. If a non zero error code is returned, the content of
 *   stat is undefined. The programmer is NOT responsible for freeing stat.
 * \param data the pointer that was passed by the caller when the function
 *   that this completion corresponds to was invoked. The programmer
 *   is responsible for any memory freeing associated with the data
 *   pointer.
 */
typedef void (*string_stat_completion_t)(int rc,
        const char *string, const struct Stat *stat, const void *data);

/**
 * \brief signature of a completion function that returns a list of strings and stat.
 * .
 *
 * This method will be invoked at the end of a asynchronous call and also as
 * a result of connection loss or timeout.
 * \param rc the error code of the call. Connection loss/timeout triggers
 * the completion with one of the following error codes:
 * ZCONNECTIONLOSS -- lost connection to the server
 * ZOPERATIONTIMEOUT -- connection timed out
 * Data related events trigger the completion with error codes listed the
 * Exceptions section of the documentation of the function that initiated the
 * call. (Zero indicates call was successful.)
 * \param strings a pointer to the structure containng the list of strings of the
 *   names of the children of a node. If a non zero error code is returned,
 *   the content of strings is undefined. The programmer is NOT responsible
 *   for freeing strings.
 * \param stat a pointer to the stat information for the node involved in
 *   this function. If a non zero error code is returned, the content of
 *   stat is undefined. The programmer is NOT responsible for freeing stat.
 * \param data the pointer that was passed by the caller when the function
 *   that this completion corresponds to was invoked. The programmer
 *   is responsible for any memory freeing associated with the data
 *   pointer.
 */
typedef void (*strings_stat_completion_t)(int rc,
        const struct String_vector *strings, const struct Stat *stat,
        const void *data);

/**
 * \brief signature of a completion function that returns a list of strings.
 *
 * This method will be invoked at the end of a asynchronous call and also as
 * a result of connection loss or timeout.
 * \param rc the error code of the call. Connection loss/timeout triggers
 * the completion with one of the following error codes:
 * ZCONNECTIONLOSS -- lost connection to the server
 * ZOPERATIONTIMEOUT -- connection timed out
 * Data related events trigger the completion with error codes listed the
 * Exceptions section of the documentation of the function that initiated the
 * call. (Zero indicates call was successful.)
 * \param value the value of the string returned.
 * \param data the pointer that was passed by the caller when the function
 *   that this completion corresponds to was invoked. The programmer
 *   is responsible for any memory freeing associated with the data
 *   pointer.
 */
typedef void
        (*string_completion_t)(int rc, const char *value, const void *data);

/**
 * \brief signature of a completion function that returns an ACL.
 *
 * This method will be invoked at the end of a asynchronous call and also as
 * a result of connection loss or timeout.
 * \param rc the error code of the call. Connection loss/timeout triggers
 * the completion with one of the following error codes:
 * ZCONNECTIONLOSS -- lost connection to the server
 * ZOPERATIONTIMEOUT -- connection timed out
 * Data related events trigger the completion with error codes listed the
 * Exceptions section of the documentation of the function that initiated the
 * call. (Zero indicates call was successful.)
 * \param acl a pointer to the structure containng the ACL of a node. If a non
 *   zero error code is returned, the content of strings is undefined. The
 *   programmer is NOT responsible for freeing acl.
 * \param stat a pointer to the stat information for the node involved in
 *   this function. If a non zero error code is returned, the content of
 *   stat is undefined. The programmer is NOT responsible for freeing stat.
 * \param data the pointer that was passed by the caller when the function
 *   that this completion corresponds to was invoked. The programmer
 *   is responsible for any memory freeing associated with the data
 *   pointer.
 */
typedef void (*acl_completion_t)(int rc, struct ACL_vector *acl,
        struct Stat *stat, const void *data);

/**
 * \brief get the state of the zookeeper connection.
 *
 * The return value will be one of the \ref State Consts.
 */
ZOOAPI int zoo_state(zhandle_t *zh);

/**
 * \brief create a node.
 *
 * This method will create a node in ZooKeeper. A node can only be created if
 * it does not already exist. The Create Mode affects the creation of nodes.
 * If ZOO_EPHEMERAL mode is chosen, the node will automatically get removed if the
 * client session goes away. If ZOO_CONTAINER flag is set, a container node will be
 * created. For ZOO_*_SEQUENTIAL modes, a unique monotonically increasing
 * sequence number is appended to the path name. The sequence number is always fixed
 * length of 10 digits, 0 padded.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path The name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param value The data to be stored in the node.
 * \param valuelen The number of bytes in data.
 * \param acl The initial ACL of the node. The ACL must not be null or empty.
 * \param mode this parameter should be one of the Create Modes.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the parent node does not exist.
 * ZNODEEXISTS the node already exists
 * ZNOAUTH the client does not have permission.
 * ZNOCHILDRENFOREPHEMERALS cannot create children of ephemeral nodes.
 * \param data The data that will be passed to the completion routine when the 
 * function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_acreate(zhandle_t *zh, const char *path, const char *value, 
        int valuelen, const struct ACL_vector *acl, int mode,
        string_completion_t completion, const void *data);

/**
 * \brief create a node.
 *
 * This method will create a node in ZooKeeper. A node can only be created if
 * it does not already exist. The Create Mode affects the creation of nodes.
 * If ZOO_EPHEMERAL mode is chosen, the node will automatically get removed if the
 * client session goes away. If ZOO_CONTAINER flag is set, a container node will be
 * created. For ZOO_*_SEQUENTIAL modes, a unique monotonically increasing
 * sequence number is appended to the path name. The sequence number is always fixed
 * length of 10 digits, 0 padded. When ZOO_*_WITH_TTL is selected, a ttl node will be
 * created.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path The name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param value The data to be stored in the node.
 * \param valuelen The number of bytes in data.
 * \param acl The initial ACL of the node. The ACL must not be null or empty.
 * \param mode this parameter should be one of the Create Modes.
 * \param ttl the value of ttl in milliseconds. It must be positive for ZOO_*_WITH_TTL
 *    Create modes, otherwise it must be -1.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the parent node does not exist.
 * ZNODEEXISTS the node already exists
 * ZNOAUTH the client does not have permission.
 * ZNOCHILDRENFOREPHEMERALS cannot create children of ephemeral nodes.
 * \param data The data that will be passed to the completion routine when the
 * function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_acreate_ttl(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl, int mode, int64_t ttl,
        string_completion_t completion, const void *data);

/**
 * \brief create a node asynchronously and returns stat details.
 *
 * This method will create a node in ZooKeeper. A node can only be created if
 * it does not already exist. The Create Mode affects the creation of nodes.
 * If ZOO_EPHEMERAL mode is chosen, the node will automatically get removed if the
 * client session goes away. If ZOO_CONTAINER flag is set, a container node will be
 * created. For ZOO_*_SEQUENTIAL modes, a unique monotonically increasing
 * sequence number is appended to the path name. The sequence number is always fixed
 * length of 10 digits, 0 padded.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path The name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param value The data to be stored in the node.
 * \param valuelen The number of bytes in data.
 * \param acl The initial ACL of the node. The ACL must not be null or empty.
 * \param mode this parameter should be one of the Create Modes.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the parent node does not exist.
 * ZNODEEXISTS the node already exists
 * ZNOAUTH the client does not have permission.
 * ZNOCHILDRENFOREPHEMERALS cannot create children of ephemeral nodes.
 * \param data The data that will be passed to the completion routine when the
 * function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_acreate2(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl, int mode,
        string_stat_completion_t completion, const void *data);

/**
 * \brief create a node asynchronously and returns stat details.
 *
 * This method will create a node in ZooKeeper. A node can only be created if
 * it does not already exist. The Create Mode affects the creation of nodes.
 * If ZOO_EPHEMERAL mode is chosen, the node will automatically get removed if the
 * client session goes away. If ZOO_CONTAINER flag is set, a container node will be
 * created. For ZOO_*_SEQUENTIAL modes, a unique monotonically increasing
 * sequence number is appended to the path name. The sequence number is always fixed
 * length of 10 digits, 0 padded. When ZOO_*_WITH_TTL is selected, a ttl node will be
 * created.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path The name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param value The data to be stored in the node.
 * \param valuelen The number of bytes in data.
 * \param acl The initial ACL of the node. The ACL must not be null or empty.
 * \param mode this parameter should be one of the Create Modes.
 * \param ttl the value of ttl in milliseconds. It must be positive for ZOO_*_WITH_TTL
 *    Create modes, otherwise it must be -1.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the parent node does not exist.
 * ZNODEEXISTS the node already exists
 * ZNOAUTH the client does not have permission.
 * ZNOCHILDRENFOREPHEMERALS cannot create children of ephemeral nodes.
 * \param data The data that will be passed to the completion routine when the
 * function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_acreate2_ttl(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl, int mode, int64_t ttl,
        string_stat_completion_t completion, const void *data);

/**
 * \brief delete a node in zookeeper.
 * 
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes 
 * separating ancestors of the node.
 * \param version the expected version of the node. The function will fail if the
 *    actual version of the node does not match the expected version.
 *  If -1 is used the version check will not take place. 
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADVERSION expected version does not match actual version.
 * ZNOTEMPTY children are present; node cannot be deleted.
 * \param data the data that will be passed to the completion routine when
 * the function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_adelete(zhandle_t *zh, const char *path, int version,
        void_completion_t completion, const void *data);

/**
 * \brief checks the existence of a node in zookeeper.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watch if nonzero, a watch will be set at the server to notify the
 * client if the node changes. The watch will be set even if the node does not
 * exist. This allows clients to watch for nodes to appear.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * \param data the data that will be passed to the completion routine when the
 * function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_aexists(zhandle_t *zh, const char *path, int watch,
        stat_completion_t completion, const void *data);

/**
 * \brief checks the existence of a node in zookeeper.
 *
 * This function is similar to \ref zoo_axists except it allows one specify
 * a watcher object - a function pointer and associated context. The function
 * will be called once the watch has fired. The associated context data will be
 * passed to the function as the watcher context parameter.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watcher if non-null a watch will set on the specified znode on the server.
 * The watch will be set even if the node does not exist. This allows clients
 * to watch for nodes to appear.
 * \param watcherCtx user specific data, will be passed to the watcher callback.
 * Unlike the global context set by \ref zookeeper_init, this watcher context
 * is associated with the given instance of the watcher only.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * \param data the data that will be passed to the completion routine when the
 * function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_awexists(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        stat_completion_t completion, const void *data);

/**
 * \brief gets the data associated with a node.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watch if nonzero, a watch will be set at the server to notify
 * the client if the node changes.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * \param data the data that will be passed to the completion routine when
 * the function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either in ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_aget(zhandle_t *zh, const char *path, int watch,
        data_completion_t completion, const void *data);

/**
 * \brief gets the data associated with a node.
 *
 * This function is similar to \ref zoo_aget except it allows one specify
 * a watcher object rather than a boolean watch flag.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watcher if non-null, a watch will be set at the server to notify
 * the client if the node changes.
 * \param watcherCtx user specific data, will be passed to the watcher callback.
 * Unlike the global context set by \ref zookeeper_init, this watcher context
 * is associated with the given instance of the watcher only.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * \param data the data that will be passed to the completion routine when
 * the function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either in ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_awget(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        data_completion_t completion, const void *data);

/**
 * \brief gets the last committed configuration of the ZooKeeper cluster as it is known to
 * the server to which the client is connected.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param watch if nonzero, a watch will be set at the server to notify
 * the client if the configuration changes.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the configuration node (/zookeeper/config) does not exist.
 * ZNOAUTH the client does not have permission to access the configuration node.
 * \param data the configuration data that will be passed to the completion routine when
 * the function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either in ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_agetconfig(zhandle_t *zh, int watch,
        data_completion_t completion, const void *data);

/**
 * \brief gets the last committed configuration of the ZooKeeper cluster as it is known to
 * the server to which the client is connected.
 *
 * This function is similar to \ref zoo_agetconfig except it allows one specify
 * a watcher object rather than a boolean watch flag.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param watcher if non-null, a watch will be set at the server to notify
 * the client if the node changes.
 * \param watcherCtx user specific data, will be passed to the watcher callback.
 * Unlike the global context set by \ref zookeeper_init, this watcher context
 * is associated with the given instance of the watcher only.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the configuration node (/zookeeper/config) does not exist.
 * ZNOAUTH the client does not have permission to access the configuration node.
 * \param data the configuration data that will be passed to the completion routine when
 * the function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either in ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_awgetconfig(zhandle_t *zh, watcher_fn watcher, void* watcherCtx,
        data_completion_t completion, const void *data);

/**
 * \brief asynchronous reconfiguration interface - allows changing ZK cluster
 * ensemble membership and roles of ensemble peers.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param joining - comma separated list of servers to be added to the ensemble.
 * Each has a configuration line for a server to be added (as would appear in a
 * configuration file), only for maj. quorums.  NULL for non-incremental reconfiguration.
 * \param leaving - comma separated list of server IDs to be removed from the ensemble.
 * Each has an id of a server to be removed, only for maj. quorums.  NULL for
 * non-incremental reconfiguration.
 * \param members - comma separated list of new membership (e.g., contents of a
 * membership configuration file) - for use only with a non-incremental
 * reconfiguration. NULL for incremental reconfiguration.
 * \param version - version of config from which we want to reconfigure - if
 * current config is different reconfiguration will fail. Should be -1 to disable
 * this option.
 * \param completion - the routine to invoke when the request completes. The
 * completion will be triggered with one of the following codes passed in as the
 * rc argument:
 * ZOK operation completed successfully
 * \param data the configuration data that will be passed to the completion routine
 * when the function completes.
 * \return return value of the function call.
 * ZOK operation completed successfully
 * ZBADARGUMENTS - invalid input parameters (one case when this is returned is
 * when the new config has less than 2 servers)
 * ZINVALIDSTATE - zhandle state is either in ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 * ZNEWCONFIGNOQUORUM - no quorum of new config is connected and up-to-date with
 * the leader of last committed config - try invoking reconfiguration after new servers are connected and synced
 * ZRECONFIGINPROGRESS - another reconfig is currently in progress
 */
ZOOAPI int zoo_areconfig(zhandle_t *zh, const char *joining, const char *leaving,
       const char *members, int64_t version, data_completion_t dc, const void *data);

/**
 * \brief sets the data associated with a node.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param buffer the buffer holding data to be written to the node.
 * \param buflen the number of bytes from buffer to write.
 * \param version the expected version of the node. The function will fail if
 * the actual version of the node does not match the expected version. If -1 is
 * used the version check will not take place. * completion: If null,
 * the function will execute synchronously. Otherwise, the function will return
 * immediately and invoke the completion routine when the request completes.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADVERSION expected version does not match actual version.
 * \param data the data that will be passed to the completion routine when
 * the function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_aset(zhandle_t *zh, const char *path, const char *buffer, int buflen,
        int version, stat_completion_t completion, const void *data);

/**
 * \brief lists the children of a node.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watch if nonzero, a watch will be set at the server to notify
 * the client if the node changes.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * \param data the data that will be passed to the completion routine when
 * the function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_aget_children(zhandle_t *zh, const char *path, int watch,
        strings_completion_t completion, const void *data);

/**
 * \brief lists the children of a node.
 *
 * This function is similar to \ref zoo_aget_children except it allows one specify
 * a watcher object rather than a boolean watch flag.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watcher if non-null, a watch will be set at the server to notify
 * the client if the node changes.
 * \param watcherCtx user specific data, will be passed to the watcher callback.
 * Unlike the global context set by \ref zookeeper_init, this watcher context
 * is associated with the given instance of the watcher only.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * \param data the data that will be passed to the completion routine when
 * the function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_awget_children(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        strings_completion_t completion, const void *data);

/**
 * \brief lists the children of a node, and get the parent stat.
 *
 * This function is new in version 3.3.0
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watch if nonzero, a watch will be set at the server to notify
 * the client if the node changes.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * \param data the data that will be passed to the completion routine when
 * the function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_aget_children2(zhandle_t *zh, const char *path, int watch,
        strings_stat_completion_t completion, const void *data);

/**
 * \brief lists the children of a node, and get the parent stat.
 *
 * This function is similar to \ref zoo_aget_children2 except it allows one specify
 * a watcher object rather than a boolean watch flag.
 *
 * This function is new in version 3.3.0
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watcher if non-null, a watch will be set at the server to notify
 * the client if the node changes.
 * \param watcherCtx user specific data, will be passed to the watcher callback.
 * Unlike the global context set by \ref zookeeper_init, this watcher context
 * is associated with the given instance of the watcher only.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * \param data the data that will be passed to the completion routine when
 * the function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_awget_children2(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        strings_stat_completion_t completion, const void *data);

/**
 * \brief Flush leader channel.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * \param data the data that will be passed to the completion routine when
 * the function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */

ZOOAPI int zoo_async(zhandle_t *zh, const char *path,
        string_completion_t completion, const void *data);


/**
 * \brief gets the acl associated with a node.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * \param data the data that will be passed to the completion routine when
 * the function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_aget_acl(zhandle_t *zh, const char *path, acl_completion_t completion,
        const void *data);

/**
 * \brief sets the acl associated with a node.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param buffer the buffer holding the acls to be written to the node.
 * \param buflen the number of bytes from buffer to write.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZINVALIDACL invalid ACL specified
 * ZBADVERSION expected version does not match actual version.
 * \param data the data that will be passed to the completion routine when
 * the function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_aset_acl(zhandle_t *zh, const char *path, int version,
        struct ACL_vector *acl, void_completion_t, const void *data);

/**
 * \brief atomically commits multiple zookeeper operations.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param count the number of operations
 * \param ops an array of operations to commit
 * \param results an array to hold the results of the operations
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with any of the error codes that can that can be returned by the
 * ops supported by a multi op (see \ref zoo_acreate, \ref zoo_adelete, \ref zoo_aset).
 * \param data the data that will be passed to the completion routine when
 * the function completes.
 * \return the return code for the function call. This can be any of the
 * values that can be returned by the ops supported by a multi op (see
 * \ref zoo_acreate, \ref zoo_adelete, \ref zoo_aset).
 */
ZOOAPI int zoo_amulti(zhandle_t *zh, int count, const zoo_op_t *ops,
        zoo_op_result_t *results, void_completion_t, const void *data);

/**
 * \brief return an error string.
 *
 * \param return code
 * \return string corresponding to the return code
 */
ZOOAPI const char* zerror(int c);

/**
 * \brief specify application credentials.
 *
 * The application calls this function to specify its credentials for purposes
 * of authentication. The server will use the security provider specified by
 * the scheme parameter to authenticate the client connection. If the
 * authentication request has failed:
 * - the server connection is dropped
 * - the watcher is called with the ZOO_AUTH_FAILED_STATE value as the state
 * parameter.
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param scheme the id of authentication scheme. Natively supported:
 * "digest" password-based authentication
 * \param cert application credentials. The actual value depends on the scheme.
 * \param certLen the length of the data parameter
 * \param completion the routine to invoke when the request completes. One of
 * the following result codes may be passed into the completion callback:
 * ZOK operation completed successfully
 * ZAUTHFAILED authentication failed
 * \param data the data that will be passed to the completion routine when the
 * function completes.
 * \return ZOK on success or one of the following errcodes on failure:
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 * ZSYSTEMERROR - a system error occurred
 */
ZOOAPI int zoo_add_auth(zhandle_t *zh,const char* scheme,const char* cert,
	int certLen, void_completion_t completion, const void *data);

/**
 * \brief checks if the current zookeeper connection state can't be recovered.
 *
 *  The application must close the zhandle and try to reconnect.
 *
 * \param zh the zookeeper handle (see \ref zookeeper_init)
 * \return ZINVALIDSTATE if connection is unrecoverable
 */
ZOOAPI int is_unrecoverable(zhandle_t *zh);

/**
 * \brief sets the debugging level for the library
 */
ZOOAPI void zoo_set_debug_level(ZooLogLevel logLevel);

/**
 * \brief sets the stream to be used by the library for logging
 *
 * The zookeeper library uses stderr as its default log stream. Application
 * must make sure the stream is writable. Passing in NULL resets the stream
 * to its default value (stderr).
 */
ZOOAPI void zoo_set_log_stream(FILE* logStream);

/**
 * \brief gets the callback to be used by this connection for logging.
 *
 * This is a per-connection logging mechanism that will take priority over
 * the library-wide default log stream. That is, zookeeper library will first
 * try to use a per-connection callback if available and if not, will fallback
 * to using the logging stream. Passing in NULL resets the callback and will
 * cause it to then fallback to using the logging stream as described in \ref
 * zoo_set_log_stream.
 */
ZOOAPI log_callback_fn zoo_get_log_callback(const zhandle_t *zh);

/**
 * \brief sets the callback to be used by the library for logging
 *
 * Setting this callback has the effect of overriding the default log stream.
 * Zookeeper will first try to use a per-connection callback if available
 * and if not, will fallback to using the logging stream. Passing in NULL
 * resets the callback and will cause it to then fallback to using the logging
 * stream as described in \ref zoo_set_log_stream.
 *
 * Note: The provided callback will be invoked by multiple threads and therefore
 * it needs to be thread-safe.
 */
ZOOAPI void zoo_set_log_callback(zhandle_t *zh, log_callback_fn callback);

/**
 * \brief enable/disable quorum endpoint order randomization
 *
 * Note: typically this method should NOT be used outside of testing.
 *
 * If passed a non-zero value, will make the client connect to quorum peers
 * in the order as specified in the zookeeper_init() call.
 * A zero value causes zookeeper_init() to permute the peer endpoints
 * which is good for more even client connection distribution among the
 * quorum peers.
 */
ZOOAPI void zoo_deterministic_conn_order(int yesOrNo);

/**
 * Type of watches: used to select which type of watches should be removed
 */
typedef enum {
  ZWATCHTYPE_CHILD = 1,
  ZWATCHTYPE_DATA = 2,
  ZWATCHTYPE_ANY = 3	
} ZooWatcherType;

/**
 * \brief removes the watches for the given path and watcher type.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the path for which watches will be removed
 * \param wtype the watcher type to be removed
 * \param watcher the watcher to be removed, if null all watches for that
 * path (and watcher type) will be removed
 * \param watcherCtx the contex associated with the watcher to be removed
 * \param local whether the watches will be removed locally even if there is
 * no server connection
 * \return the return code for the function call.
 * ZOK - operation completed successfully
 * ZNOWATCHER - the watcher couldn't be found.
 * ZINVALIDSTATE - if !local, zhandle state is either ZOO_SESSION_EXPIRED_STATE
 * or ZOO_AUTH_FAILED_STATE
 * ZBADARGUMENTS - invalid input parameters
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 * ZSYSTEMERROR - a system error occured
 */
ZOOAPI int zoo_aremove_watches(zhandle_t *zh, const char *path,
        ZooWatcherType wtype, watcher_fn watcher, void *watcherCtx, int local,
        void_completion_t *completion, const void *data);

/**
 * \brief removes all the watches for the given path and watcher type.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the path for which watches will be removed
 * \param wtype the watcher type to be removed
 * \param local whether the watches will be removed locally even if there is
 * no server connection
 * \return the return code for the function call.
 * ZOK - operation completed successfully
 * ZNOWATCHER - the watcher couldn't be found.
 * ZINVALIDSTATE - if !local, zhandle state is either ZOO_SESSION_EXPIRED_STATE
 * or ZOO_AUTH_FAILED_STATE
 * ZBADARGUMENTS - invalid input parameters
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 * ZSYSTEMERROR - a system error occured
 */
ZOOAPI int zoo_remove_all_watches(zhandle_t *zh, const char *path,
        ZooWatcherType wtype, int local);

/**
 * \brief removes all the watches for the given path and watcher type.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the path for which watches will be removed
 * \param wtype the watcher type to be removed
 * \param local whether the watches will be removed locally even if there is
 * no server connection
 * \return the return code for the function call.
 * ZOK - operation completed successfully
 * ZNOWATCHER - the watcher couldn't be found.
 * ZINVALIDSTATE - if !local, zhandle state is either ZOO_SESSION_EXPIRED_STATE
 * or ZOO_AUTH_FAILED_STATE
 * ZBADARGUMENTS - invalid input parameters
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 * ZSYSTEMERROR - a system error occured
*/
ZOOAPI int zoo_aremove_all_watches(zhandle_t *zh, const char *path,
        ZooWatcherType wtype, int local, void_completion_t *completion,
        const void *data);

#ifdef THREADED
/**
 * \brief create a node synchronously.
 *
 * This method will create a node in ZooKeeper. A node can only be created if
 * it does not already exist. The Create Mode affects the creation of nodes.
 * If ZOO_EPHEMERAL mode is chosen, the node will automatically get removed if the
 * client session goes away. If ZOO_CONTAINER flag is set, a container node will be
 * created. For ZOO_*_SEQUENTIAL modes, a unique monotonically increasing
 * sequence number is appended to the path name. The sequence number is always fixed
 * length of 10 digits, 0 padded.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path The name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param value The data to be stored in the node.
 * \param valuelen The number of bytes in data. To set the data to be NULL use
 * value as NULL and valuelen as -1.
 * \param acl The initial ACL of the node. The ACL must not be null or empty.
 * \param mode this parameter should be one of the Create Modes.
 * \param path_buffer Buffer which will be filled with the path of the
 *    new node (this might be different than the supplied path
 *    because of the ZOO_SEQUENCE flag).  The path string will always be
 *    null-terminated. This parameter may be NULL if path_buffer_len = 0.
 * \param path_buffer_len Size of path buffer; if the path of the new
 *    node (including space for the null terminator) exceeds the buffer size,
 *    the path string will be truncated to fit.  The actual path of the
 *    new node in the server will not be affected by the truncation.
 *    The path string will always be null-terminated.
 * \return  one of the following codes are returned:
 * ZOK operation completed successfully
 * ZNONODE the parent node does not exist.
 * ZNODEEXISTS the node already exists
 * ZNOAUTH the client does not have permission.
 * ZNOCHILDRENFOREPHEMERALS cannot create children of ephemeral nodes.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_create(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl, int mode,
        char *path_buffer, int path_buffer_len);

/**
 * \brief create a node synchronously.
 *
 * This method will create a node in ZooKeeper. A node can only be created if
 * it does not already exist. The Create Mode affects the creation of nodes.
 * If ZOO_EPHEMERAL mode is chosen, the node will automatically get removed if the
 * client session goes away. If ZOO_CONTAINER flag is set, a container node will be
 * created. For ZOO_*_SEQUENTIAL modes, a unique monotonically increasing
 * sequence number is appended to the path name. The sequence number is always fixed
 * length of 10 digits, 0 padded. When ZOO_*_WITH_TTL is selected, a ttl node will be
 * created.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path The name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param value The data to be stored in the node.
 * \param valuelen The number of bytes in data. To set the data to be NULL use
 * value as NULL and valuelen as -1.
 * \param acl The initial ACL of the node. The ACL must not be null or empty.
 * \param mode this parameter should be one of the Create Modes.
 * \param ttl the value of ttl in milliseconds. It must be positive for ZOO_*_WITH_TTL
 *    Create modes, otherwise it must be -1.
 * \param path_buffer Buffer which will be filled with the path of the
 *    new node (this might be different than the supplied path
 *    because of the ZOO_SEQUENCE flag).  The path string will always be
 *    null-terminated. This parameter may be NULL if path_buffer_len = 0.
 * \param path_buffer_len Size of path buffer; if the path of the new
 *    node (including space for the null terminator) exceeds the buffer size,
 *    the path string will be truncated to fit.  The actual path of the
 *    new node in the server will not be affected by the truncation.
 *    The path string will always be null-terminated.
 * \return  one of the following codes are returned:
 * ZOK operation completed successfully
 * ZNONODE the parent node does not exist.
 * ZNODEEXISTS the node already exists
 * ZNOAUTH the client does not have permission.
 * ZNOCHILDRENFOREPHEMERALS cannot create children of ephemeral nodes.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_create_ttl(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl, int mode, int64_t ttl,
        char *path_buffer, int path_buffer_len);

/**
 * \brief create a node synchronously and collect stat details.
 *
 * This method will create a node in ZooKeeper. A node can only be created if
 * it does not already exist. The Create Mode affects the creation of nodes.
 * If ZOO_EPHEMERAL mode is chosen, the node will automatically get removed if the
 * client session goes away. If ZOO_CONTAINER flag is set, a container node will be
 * created. For ZOO_*_SEQUENTIAL modes, a unique monotonically increasing
 * sequence number is appended to the path name. The sequence number is always fixed
 * length of 10 digits, 0 padded.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path The name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param value The data to be stored in the node.
 * \param valuelen The number of bytes in data. To set the data to be NULL use
 * value as NULL and valuelen as -1.
 * \param acl The initial ACL of the node. The ACL must not be null or empty.
 * \param mode this parameter should be one of the Create Modes.
 * \param path_buffer Buffer which will be filled with the path of the
 *    new node (this might be different than the supplied path
 *    because of the ZOO_SEQUENCE flag).  The path string will always be
 *    null-terminated. This parameter may be NULL if path_buffer_len = 0.
 * \param path_buffer_len Size of path buffer; if the path of the new
 *    node (including space for the null terminator) exceeds the buffer size,
 *    the path string will be truncated to fit.  The actual path of the
 *    new node in the server will not be affected by the truncation.
 *    The path string will always be null-terminated.
 * \param stat The Stat struct to store Stat info into.
 * \return  one of the following codes are returned:
 * ZOK operation completed successfully
 * ZNONODE the parent node does not exist.
 * ZNODEEXISTS the node already exists
 * ZNOAUTH the client does not have permission.
 * ZNOCHILDRENFOREPHEMERALS cannot create children of ephemeral nodes.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_create2(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl, int mode,
        char *path_buffer, int path_buffer_len, struct Stat *stat);

/**
 * \brief create a node synchronously and collect stat details.
 *
 * This method will create a node in ZooKeeper. A node can only be created if
 * it does not already exist. The Create Mode affects the creation of nodes.
 * If ZOO_EPHEMERAL mode is chosen, the node will automatically get removed if the
 * client session goes away. If ZOO_CONTAINER flag is set, a container node will be
 * created. For ZOO_*_SEQUENTIAL modes, a unique monotonically increasing
 * sequence number is appended to the path name. The sequence number is always fixed
 * length of 10 digits, 0 padded. When ZOO_*_WITH_TTL is selected, a ttl node will be
 * created.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path The name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param value The data to be stored in the node.
 * \param valuelen The number of bytes in data. To set the data to be NULL use
 * value as NULL and valuelen as -1.
 * \param acl The initial ACL of the node. The ACL must not be null or empty.
 * \param mode this parameter should be one of the Create Modes.
 * \param ttl the value of ttl in milliseconds. It must be positive for ZOO_*_WITH_TTL
 *    Create modes, otherwise it must be -1.
 * \param path_buffer Buffer which will be filled with the path of the
 *    new node (this might be different than the supplied path
 *    because of the ZOO_SEQUENCE flag).  The path string will always be
 *    null-terminated. This parameter may be NULL if path_buffer_len = 0.
 * \param path_buffer_len Size of path buffer; if the path of the new
 *    node (including space for the null terminator) exceeds the buffer size,
 *    the path string will be truncated to fit.  The actual path of the
 *    new node in the server will not be affected by the truncation.
 *    The path string will always be null-terminated.
 * \param stat The Stat struct to store Stat info into.
 * \return  one of the following codes are returned:
 * ZOK operation completed successfully
 * ZNONODE the parent node does not exist.
 * ZNODEEXISTS the node already exists
 * ZNOAUTH the client does not have permission.
 * ZNOCHILDRENFOREPHEMERALS cannot create children of ephemeral nodes.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_create2_ttl(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl, int mode, int64_t ttl,
        char *path_buffer, int path_buffer_len, struct Stat *stat);

/**
 * \brief delete a node in zookeeper synchronously.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param version the expected version of the node. The function will fail if the
 *    actual version of the node does not match the expected version.
 *  If -1 is used the version check will not take place.
 * \return one of the following values is returned.
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADVERSION expected version does not match actual version.
 * ZNOTEMPTY children are present; node cannot be deleted.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_delete(zhandle_t *zh, const char *path, int version);

/**
 * \brief checks the existence of a node in zookeeper synchronously.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watch if nonzero, a watch will be set at the server to notify the
 * client if the node changes. The watch will be set even if the node does not
 * exist. This allows clients to watch for nodes to appear.
 * \param the return stat value of the node.
 * \return  return code of the function call.
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_exists(zhandle_t *zh, const char *path, int watch, struct Stat *stat);

/**
 * \brief checks the existence of a node in zookeeper synchronously.
 *
 * This function is similar to \ref zoo_exists except it allows one specify
 * a watcher object rather than a boolean watch flag.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watcher if non-null a watch will set on the specified znode on the server.
 * The watch will be set even if the node does not exist. This allows clients
 * to watch for nodes to appear.
 * \param watcherCtx user specific data, will be passed to the watcher callback.
 * Unlike the global context set by \ref zookeeper_init, this watcher context
 * is associated with the given instance of the watcher only.
 * \param the return stat value of the node.
 * \return  return code of the function call.
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_wexists(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx, struct Stat *stat);

/**
 * \brief gets the data associated with a node synchronously.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watch if nonzero, a watch will be set at the server to notify
 * the client if the node changes.
 * \param buffer the buffer holding the node data returned by the server
 * \param buffer_len is the size of the buffer pointed to by the buffer parameter.
 * It'll be set to the actual data length upon return. If the data is NULL, length is -1.
 * \param stat if not NULL, will hold the value of stat for the path on return.
 * \return return value of the function call.
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either in ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_get(zhandle_t *zh, const char *path, int watch, char *buffer,
                   int* buffer_len, struct Stat *stat);
/**
 * \brief gets the data associated with a node synchronously.
 *
 * This function is similar to \ref zoo_get except it allows one specify
 * a watcher object rather than a boolean watch flag.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watcher if non-null, a watch will be set at the server to notify
 * the client if the node changes.
 * \param watcherCtx user specific data, will be passed to the watcher callback.
 * Unlike the global context set by \ref zookeeper_init, this watcher context
 * is associated with the given instance of the watcher only.
 * \param buffer the buffer holding the node data returned by the server
 * \param buffer_len is the size of the buffer pointed to by the buffer parameter.
 * It'll be set to the actual data length upon return. If the data is NULL, length is -1.
 * \param stat if not NULL, will hold the value of stat for the path on return.
 * \return return value of the function call.
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either in ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_wget(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        char *buffer, int* buffer_len, struct Stat *stat);

/**
 * \brief gets the last committed configuration of the ZooKeeper cluster as it is known to
 * the server to which the client is connected, synchronously.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param watch if nonzero, a watch will be set at the server to notify
 * the client if the node changes.
 * \param buffer the buffer holding the configuration data returned by the server
 * \param buffer_len is the size of the buffer pointed to by the buffer parameter.
 * It'll be set to the actual data length upon return. If the data is NULL, length is -1.
 * \param stat if not NULL, will hold the value of stat for the path on return.
 * \return return value of the function call.
 * ZOK operation completed successfully
 * ZNONODE the configuration node (/zookeeper/config) does not exist.
 * ZNOAUTH the client does not have permission to access the configuration node.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either in ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_getconfig(zhandle_t *zh, int watch, char *buffer,
                         int* buffer_len, struct Stat *stat);

/**
 * \brief gets the last committed configuration of the ZooKeeper cluster as it is known to
 * the server to which the client is connected, synchronously.
 *
 * This function is similar to \ref zoo_getconfig except it allows one specify
 * a watcher object rather than a boolean watch flag.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param watcher if non-null, a watch will be set at the server to notify
 * the client if the node changes.
 * \param watcherCtx user specific data, will be passed to the watcher callback.
 * Unlike the global context set by \ref zookeeper_init, this watcher context
 * is associated with the given instance of the watcher only.
 * \param buffer the buffer holding the configuration data returned by the server
 * \param buffer_len is the size of the buffer pointed to by the buffer parameter.
 * It'll be set to the actual data length upon return. If the data is NULL, length is -1.
 * \param stat if not NULL, will hold the value of stat for the path on return.
 * \return return value of the function call.
 * ZOK operation completed successfully
 * ZNONODE the configuration node (/zookeeper/config) does not exist.
 * ZNOAUTH the client does not have permission to access the configuration node.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either in ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_wgetconfig(zhandle_t *zh, watcher_fn watcher, void* watcherCtx,
        char *buffer, int* buffer_len, struct Stat *stat);

/**
 * \brief synchronous reconfiguration interface - allows changing ZK cluster
 * ensemble membership and roles of ensemble peers.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param joining - comma separated list of servers to be added to the ensemble.
 * Each has a configuration line for a server to be added (as would appear in a
 * configuration file), only for maj. quorums.  NULL for non-incremental reconfiguration.
 * \param leaving - comma separated list of server IDs to be removed from the ensemble.
 * Each has an id of a server to be removed, only for maj. quorums.  NULL for
 * non-incremental reconfiguration.
 * \param members - comma separated list of new membership (e.g., contents of a
 *  membership configuration file) - for use only with a non-incremental
 * reconfiguration. NULL for incremental reconfiguration.
 * \param version - zxid of config from which we want to reconfigure - if
 * current config is different reconfiguration will fail. Should be -1 to
 * disable this option.
 * \param buffer the buffer holding the configuration data returned by the server
 * \param buffer_len is the size of the buffer pointed to by the buffer parameter.
 * It'll be set to the actual data length upon return. If the data is NULL, length
 * is -1.
 * \param stat if not NULL, will hold the value of stat for the path on return.
 * \return return value of the function call.
 * ZOK operation completed successfully
 * ZBADARGUMENTS - invalid input parameters (one case when this is returned is
 * when the new config has less than 2 servers)
 * ZINVALIDSTATE - zhandle state is either in ZOO_SESSION_EXPIRED_STATE or
 * ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 * ZNEWCONFIGNOQUORUM - no quorum of new config is connected and up-to-date with
 * the leader of last committed config - try invoking reconfiguration after new
 * servers are connected and synced
 * ZRECONFIGINPROGRESS - another reconfig is currently in progress
 */
ZOOAPI int zoo_reconfig(zhandle_t *zh, const char *joining, const char *leaving,
       const char *members, int64_t version, char *buffer, int* buffer_len,
       struct Stat *stat);

/**
 * \brief sets the data associated with a node. See zoo_set2 function if
 * you require access to the stat information associated with the znode.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param buffer the buffer holding data to be written to the node.
 * \param buflen the number of bytes from buffer to write. To set NULL as data
 * use buffer as NULL and buflen as -1.
 * \param version the expected version of the node. The function will fail if
 * the actual version of the node does not match the expected version. If -1 is
 * used the version check will not take place.
 * \return the return code for the function call.
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADVERSION expected version does not match actual version.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_set(zhandle_t *zh, const char *path, const char *buffer,
                   int buflen, int version);

/**
 * \brief sets the data associated with a node. This function is the same
 * as zoo_set except that it also provides access to stat information
 * associated with the znode.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param buffer the buffer holding data to be written to the node.
 * \param buflen the number of bytes from buffer to write. To set NULL as data
 * use buffer as NULL and buflen as -1.
 * \param version the expected version of the node. The function will fail if
 * the actual version of the node does not match the expected version. If -1 is
 * used the version check will not take place.
 * \param stat if not NULL, will hold the value of stat for the path on return.
 * \return the return code for the function call.
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADVERSION expected version does not match actual version.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_set2(zhandle_t *zh, const char *path, const char *buffer,
                   int buflen, int version, struct Stat *stat);

/**
 * \brief lists the children of a node synchronously.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watch if nonzero, a watch will be set at the server to notify
 * the client if the node changes.
 * \param strings return value of children paths.
 * \return the return code of the function.
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_get_children(zhandle_t *zh, const char *path, int watch,
                            struct String_vector *strings);

/**
 * \brief lists the children of a node synchronously.
 *
 * This function is similar to \ref zoo_get_children except it allows one specify
 * a watcher object rather than a boolean watch flag.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watcher if non-null, a watch will be set at the server to notify
 * the client if the node changes.
 * \param watcherCtx user specific data, will be passed to the watcher callback.
 * Unlike the global context set by \ref zookeeper_init, this watcher context
 * is associated with the given instance of the watcher only.
 * \param strings return value of children paths.
 * \return the return code of the function.
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_wget_children(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        struct String_vector *strings);

/**
 * \brief lists the children of a node and get its stat synchronously.
 *
 * This function is new in version 3.3.0
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watch if nonzero, a watch will be set at the server to notify
 * the client if the node changes.
 * \param strings return value of children paths.
 * \param stat return value of node stat.
 * \return the return code of the function.
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_get_children2(zhandle_t *zh, const char *path, int watch,
                            struct String_vector *strings, struct Stat *stat);

/**
 * \brief lists the children of a node and get its stat synchronously.
 *
 * This function is similar to \ref zoo_get_children except it allows one specify
 * a watcher object rather than a boolean watch flag.
 *
 * This function is new in version 3.3.0
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param watcher if non-null, a watch will be set at the server to notify
 * the client if the node changes.
 * \param watcherCtx user specific data, will be passed to the watcher callback.
 * Unlike the global context set by \ref zookeeper_init, this watcher context
 * is associated with the given instance of the watcher only.
 * \param strings return value of children paths.
 * \param stat return value of node stat.
 * \return the return code of the function.
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_wget_children2(zhandle_t *zh, const char *path,
        watcher_fn watcher, void* watcherCtx,
        struct String_vector *strings, struct Stat *stat);

/**
 * \brief gets the acl associated with a node synchronously.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param acl the return value of acls on the path.
 * \param stat returns the stat of the path specified.
 * \return the return code for the function call.
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_get_acl(zhandle_t *zh, const char *path, struct ACL_vector *acl,
                       struct Stat *stat);

/**
 * \brief sets the acl associated with a node synchronously.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param version the expected version of the path.
 * \param acl the acl to be set on the path.
 * \return the return code for the function call.
 * ZOK operation completed successfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZINVALIDACL invalid ACL specified
 * ZBADVERSION expected version does not match actual version.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_set_acl(zhandle_t *zh, const char *path, int version,
                           const struct ACL_vector *acl);

/**
 * \brief atomically commits multiple zookeeper operations synchronously.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param count the number of operations
 * \param ops an array of operations to commit
 * \param results an array to hold the results of the operations
 * \return the return code for the function call. This can be any of the
 * values that can be returned by the ops supported by a multi op (see
 * \ref zoo_acreate, \ref zoo_adelete, \ref zoo_aset).
 */
ZOOAPI int zoo_multi(zhandle_t *zh, int count, const zoo_op_t *ops, zoo_op_result_t *results);

/**
 * \brief removes the watches for the given path and watcher type.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the path for which watches will be removed
 * \param wtype the watcher type to be removed
 * \param watcher the watcher to be removed, if null all watches for that
 * path (and watcher type) will be removed
 * \param watcherCtx the contex associated with the watcher to be removed
 * \param local whether the watches will be removed locally even if there is
 * no server connection
 * \return the return code for the function call.
 * ZOK - operation completed successfully
 * ZNOWATCHER - the watcher couldn't be found.
 * ZINVALIDSTATE - if !local, zhandle state is either ZOO_SESSION_EXPIRED_STATE
 * or ZOO_AUTH_FAILED_STATE
 * ZBADARGUMENTS - invalid input parameters
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 * ZSYSTEMERROR - a system error occured
 */
ZOOAPI int zoo_remove_watches(zhandle_t *zh, const char *path,
        ZooWatcherType wtype, watcher_fn watcher, void *watcherCtx, int local);
#endif
#ifdef __cplusplus
}
#endif

#endif /*ZOOKEEPER_H_*/
