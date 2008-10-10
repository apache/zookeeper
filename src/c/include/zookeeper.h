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

#include <sys/time.h>
#include <stdio.h>
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
 * not trigger a notification unless the client issues a querity with the watch
 * flag set. If the client is ever disconnected from the service, even if the
 * disconnection is temporary, the watches of the client will be removed from
 * the service, so a client must treat a disconnect notification as an implicit
 * trigger of all outstanding watches.
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
#  if defined(__CYGWIN__) && !defined(USE_STATIC_LIB)
#    define ZOOAPI __declspec(dllimport)
#  else
#    define ZOOAPI
#  endif
#endif

/** zookeeper return constants **/

#define ZOK                                    0
#define ZSYSTEMERROR                          -1
#define ZRUNTIMEINCONSISTENCY (ZSYSTEMERROR - 1)
#define ZDATAINCONSISTENCY    (ZSYSTEMERROR - 2)
#define ZCONNECTIONLOSS       (ZSYSTEMERROR - 3)
#define ZMARSHALLINGERROR     (ZSYSTEMERROR - 4)
#define ZUNIMPLEMENTED        (ZSYSTEMERROR - 5)
#define ZOPERATIONTIMEOUT     (ZSYSTEMERROR - 6)
#define ZBADARGUMENTS         (ZSYSTEMERROR - 7)
#define ZINVALIDSTATE         (ZSYSTEMERROR - 8)
#define ZAPIERROR                           -100
#define ZNONODE                  (ZAPIERROR - 1)
#define ZNOAUTH                  (ZAPIERROR - 2)
#define ZBADVERSION              (ZAPIERROR - 3)
#define ZNOCHILDRENFOREPHEMERALS (ZAPIERROR - 8)
#define ZNODEEXISTS             (ZAPIERROR - 10)
#define ZNOTEMPTY               (ZAPIERROR - 11)
#define ZSESSIONEXPIRED         (ZAPIERROR - 12)
#define ZINVALIDCALLBACK        (ZAPIERROR - 13)
#define ZINVALIDACL             (ZAPIERROR - 14)
#define ZAUTHFAILED             (ZAPIERROR - 15)
#define ZCLOSING                (ZAPIERROR - 16)
#define ZNOTHING                (ZAPIERROR - 17)

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

extern ZOOAPI struct Id ZOO_ANYONE_ID_UNSAFE;
extern ZOOAPI struct Id ZOO_AUTH_IDS;

extern ZOOAPI struct ACL_vector ZOO_OPEN_ACL_UNSAFE;
extern ZOOAPI struct ACL_vector ZOO_READ_ACL_UNSAFE;
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
 * @name Create Flags
 * 
 * These flags are used by zoo_create to affect node create. They may
 * be ORed together to combine effects.
 */
// @{
extern ZOOAPI const int ZOO_EPHEMERAL;
extern ZOOAPI const int ZOO_SEQUENCE;
// @}

/**
 * @name State Consts
 * These constants represent the states of a zookeeper connection. They are
 * possible parameters of the watcher callback. If a connection moves from
 * the ZOO_CONNECTED_STATE to the ZOO_CONNECTING_STATE, all outstanding 
 * watches will be removed.
 */
// @{
extern ZOOAPI const int ZOO_EXPIRED_SESSION_STATE;
extern ZOOAPI const int ZOO_AUTH_FAILED_STATE;
extern ZOOAPI const int ZOO_CONNECTING_STATE;
extern ZOOAPI const int ZOO_ASSOCIATING_STATE;
extern ZOOAPI const int ZOO_CONNECTED_STATE;
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
 * are set using \ref zoo_get_children.
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
 * \param state connection state. If the type is ZOO_SESSION_EVENT, the state value 
 * will be one of the *_STATE constants, otherwise -1.
 * \param path znode path for which the watcher is triggered. NULL if the event 
 * type is ZOO_SESSION_EVENT
 * \param watcherCtx watcher context.
 */
typedef void (*watcher_fn)(zhandle_t *zh, int type, 
        int state, const char *path,void *watcherCtx);

/**
 * \brief create a handle to used communicate with zookeeper.
 * 
 * This method creates a new handle and a zookeeper session that corresponds
 * to that handle. Session establishment is asynchronous, meaning that the
 * session should not be considered established until (and unless) an
 * event of state ZOO_CONNECTED_STATE is received.
 * \param host the host name to connect to. This may be a comma separated list
 *   of different hosts.
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

/**
 * \brief close the zookeeper handle and free up any resources.
 * 
 * After this call, the client session will no longer be valid. The function
 * will flush any outstanding send requests before return. As a result it may 
 * block.
 *
 * This method should only be called only once on a zookeeper handle. Calling
 * twice will cause undefined (and probably undesirable behavior).
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \return a result code. Regardless of the error code returned, the zhandle 
 * will be destroyed and all resources freed. 
 *
 * ZOK - success
 * ZBADARGUMENTS - invalid input parameters
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 * ZOPERATIONTIMEOUT - failed to flush the buffers within the specified timeout.
 * ZCONNECTIONLOSS - a network error occured while attempting to send request to server
 * ZSYSTEMERROR -- a system (OS) error occured; it's worth checking errno to get details
 */
ZOOAPI int zookeeper_close(zhandle_t *zh);

/**
 * \brief return the client session id, only valid if the connections
 * is currently connected (ie. last watcher state is ZOO_CONNECTED_STATE)
 */
ZOOAPI const clientid_t *zoo_client_id(zhandle_t *zh);

ZOOAPI int zoo_recv_timeout(zhandle_t *zh);

ZOOAPI const void *zoo_get_context(zhandle_t *zh);

ZOOAPI void zoo_set_context(zhandle_t *zh, void *context);

/**
 * \brief set a watcher function
 * \return previous watcher function
 */
ZOOAPI watcher_fn zoo_set_watcher(zhandle_t *zh,watcher_fn newFn);

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
 * ZCONNECTIONLOSS - a network error occured while attempting to establish 
 * a connection to the server
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 * ZOPERATIONTIMEOUT - hasn't received anything from the server for 2/3 of the
 * timeout value specified in zookeeper_init()
 * ZSYSTEMERROR -- a system (OS) error occured; it's worth checking errno to get details
 */
ZOOAPI int zookeeper_interest(zhandle_t *zh, int *fd, int *interest, 
	struct timeval *tv);

/**
 * \brief Notifies zookeeper that an event of interest has happened.
 * 
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param events will be an OR of the ZOOKEEPER_WRITE and ZOOKEEPER_READ flags.
 * \return a result code. 
 * ZOK - success
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZCONNECTIONLOSS - a network error occured while attempting to send request to server
 * ZSESSIONEXPIRED - connection attempt failed -- the session's expired
 * ZAUTHFAILED - authentication request failed, e.i. invalid credentials
 * ZRUNTIMEINCONSISTENCY - a server response came out of order
 * ZSYSTEMERROR -- a system (OS) error occured; it's worth checking errno to get details
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
 * it does not already exists. The Create Flags affect the creation of nodes.
 * If ZOO_EPHEMERAL flag is set, the node will automatically get removed if the
 * client session goes away. If the ZOO_SEQUENCE flag is set, a unique
 * monotonically increasing sequence number is appended to the path name.
 * 
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path The name of the node. Expressed as a file name with slashes 
 * separating ancestors of the node.
 * \param value The data to be stored in the node.
 * \param valuelen The number of bytes in data.
 * \param acl The initial ACL of the node. If null, the ACL of the parent will be
 *    used.
 * \param flags this parameter can be set to 0 for normal create or an OR
 *    of the Create Flags
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed succesfully
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
        int valuelen, const struct ACL_vector *acl, int flags,
        string_completion_t completion, const void *data);

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
 * ZOK operation completed succesfully
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
 * ZOK operation completed succesfully
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
 * ZOK operation completed succesfully
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
 * ZOK operation completed succesfully
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
 * ZOK operation completed succesfully
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
 * ZOK operation completed succesfully
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
 * ZOK operation completed succesfully
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
 * ZOK operation completed succesfully
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
 * \brief Flush leader channel.
 *
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes
 * separating ancestors of the node.
 * \param completion the routine to invoke when the request completes. The completion
 * will be triggered with one of the following codes passed in as the rc argument:
 * ZOK operation completed succesfully
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
 * ZOK operation completed succesfully
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
 * ZOK operation completed succesfully
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
 * ZSYSTEMERROR - a system error occured
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
 * \brief enable/disable quorum endpoint order randomization
 * 
 * If passed a non-zero value, will make the client connect to quorum peers
 * in the order as specified in the zookeeper_init() call.
 * A zero value causes zookeeper_init() to permute the peer endpoints
 * which is good for more even client connection distribution among the 
 * quorum peers.
 */
ZOOAPI void zoo_deterministic_conn_order(int yesOrNo);

/**
 * \brief create a node synchronously.
 * 
 * This method will create a node in ZooKeeper. A node can only be created if
 * it does not already exists. The Create Flags affect the creation of nodes.
 * If ZOO_EPHEMERAL flag is set, the node will automatically get removed if the
 * client session goes away. If the ZOO_SEQUENCE flag is set, a unique
 * monotonically increasing sequence number is appended to the path name.
 * 
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path The name of the node. Expressed as a file name with slashes 
 * separating ancestors of the node.
 * \param value The data to be stored in the node.
 * \param valuelen The number of bytes in data.
 * \param acl The initial ACL of the node. If null, the ACL of the parent will be
 *    used.
 * \param flags this parameter can be set to 0 for normal create or an OR
 *    of the Create Flags
 * \param realpath the real path that is created (this might be different than the
 *    path to create because of the ZOO_SEQUENCE flag.
 * \param the maximum length of real path you would want.
 * \return  one of the following codes are returned:
 * ZOK operation completed succesfully
 * ZNONODE the parent node does not exist.
 * ZNODEEXISTS the node already exists
 * ZNOAUTH the client does not have permission.
 * ZNOCHILDRENFOREPHEMERALS cannot create children of ephemeral nodes.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_create(zhandle_t *zh, const char *path, const char *value,
        int valuelen, const struct ACL_vector *acl, int flags, char *realpath,	 
        int max_realpath_len);

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
 * ZOK operation completed succesfully
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
 * ZOK operation completed succesfully
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
 * ZOK operation completed succesfully
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
 * It'll be set to the actual data length upon return.
 * \param stat if not NULL, will hold the value of stat for the path on return.
 * \return return value of the function call.
 * ZOK operation completed succesfully
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
 * It'll be set to the actual data length upon return.
 * \param stat if not NULL, will hold the value of stat for the path on return.
 * \return return value of the function call.
 * ZOK operation completed succesfully
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
 * \brief sets the data associated with a node.
 * 
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes 
 * separating ancestors of the node.
 * \param buffer the buffer holding data to be written to the node.
 * \param buflen the number of bytes from buffer to write.
 * \param version the expected version of the node. The function will fail if 
 * the actual version of the node does not match the expected version. If -1 is 
 * used the version check will not take place. 
 * \return the return code for the function call.
 * ZOK operation completed succesfully
 * ZNONODE the node does not exist.
 * ZNOAUTH the client does not have permission.
 * ZBADVERSION expected version does not match actual version.
 * ZBADARGUMENTS - invalid input parameters
 * ZINVALIDSTATE - zhandle state is either ZOO_SESSION_EXPIRED_STATE or ZOO_AUTH_FAILED_STATE
 * ZMARSHALLINGERROR - failed to marshall a request; possibly, out of memory
 */
ZOOAPI int zoo_set(zhandle_t *zh, const char *path, const char *buffer, int buflen,
                   int version);


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
 * ZOK operation completed succesfully
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
 * ZOK operation completed succesfully
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
 * \brief gets the acl associated with a node synchronously.
 * 
 * \param zh the zookeeper handle obtained by a call to \ref zookeeper_init
 * \param path the name of the node. Expressed as a file name with slashes 
 * separating ancestors of the node.
 * \param acl the return value of acls on the path.
 * \param stat returns the stat of the path specified.
 * \return the return code for the function call.
 * ZOK operation completed succesfully
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
 * ZOK operation completed succesfully
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

#ifdef __cplusplus
}
#endif

#endif /*ZOOKEEPER_H_*/
