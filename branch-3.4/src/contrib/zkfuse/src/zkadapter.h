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

#ifndef __ZKADAPTER_H__
#define __ZKADAPTER_H__

#include <string>
#include <vector>
#include <map>

extern "C" {
#include "zookeeper.h"
}

#include "log.h"
#include "mutex.h"
#include "thread.h"
#include "blockingqueue.h"
#include "event.h"

using namespace std;
using namespace zkfuse;

namespace zk {
    
/**
 * \brief A cluster related exception.
 */
class ZooKeeperException :
    public std::exception
{
    public:
        
        /**
         * \brief Constructor.
         * 
         * @param msg the detailed message associated with this exception
         */
        ZooKeeperException(const string &msg) : 
            m_message(msg), m_zkErrorCode(0) 
        {}

        /**
         * \brief Constructor.
         * 
         * @param msg the detailed message associated with this exception
         * @param errorCode the ZK error code associated with this exception
         */
        ZooKeeperException(const string &msg, int errorCode) : 
            m_zkErrorCode(errorCode) 
        {
            char tmp[100];
            sprintf( tmp, " (ZK error code: %d)", errorCode );
            m_message = msg + tmp;
        }
                
        /**
         * \brief Destructor.
         */
        ~ZooKeeperException() throw() {}
        
        /**
         * \brief Returns detailed description of the exception.
         */
        const char *what() const throw() {
            return m_message.c_str();
        }
        
        /**
         * \brief Returns the ZK error code.
         */
        int getZKErrorCode() const {
            return m_zkErrorCode;
        }

    private:
        
        /**
         * The detailed message associated with this exception.
         */
        string m_message;
        
        /**
         * The optional error code received from ZK.
         */
        int m_zkErrorCode;
        
};
    
/**
 * \brief This class encapsulates configuration of a ZK client.
 */
class ZooKeeperConfig
{
    public:
        
        /**
         * \brief Constructor.
         * 
         * @param hosts the comma separated list of host and port pairs of ZK nodes
         * @param leaseTimeout the lease timeout (heartbeat)
         * @param autoReconnect whether to allow for auto-reconnect
         * @param connectTimeout the connect timeout, in milliseconds;
         */
        ZooKeeperConfig(const string &hosts, 
                        int leaseTimeout, 
                        bool autoReconnect = true, 
                        long long int connectTimeout = 15000) :
            m_hosts(hosts), m_leaseTimeout(leaseTimeout), 
                  m_autoReconnect(autoReconnect), m_connectTimeout(connectTimeout) {}
        
        /**
         * \brief Returns the list of ZK hosts to connect to.
         */
        string getHosts() const { return m_hosts; }
        
        /**
         * \brief Returns the lease timeout.
         */
        int getLeaseTimeout() const { return m_leaseTimeout; }
        
        /**
         * \brief Returns whether {@link ZooKeeperAdapter} should attempt 
         * \brief to automatically reconnect in case of a connection failure.
         */
        bool getAutoReconnect() const { return m_autoReconnect; }

        /**
         * \brief Gets the connect timeout.
         * 
         * @return the connect timeout
         */
        long long int getConnectTimeout() const { return m_connectTimeout; }
                  
    private:
        
        /**
         * The host addresses of ZK nodes.
         */
        const string m_hosts;

        /**
         * The ZK lease timeout.
         */
        const int m_leaseTimeout;
        
        /**
         * True if this adapater should attempt to autoreconnect in case 
         * the current session has been dropped.
         */
        const bool m_autoReconnect;
        
        /**
         * How long to wait, in milliseconds, before a connection 
         * is established to ZK.
         */
        const long long int m_connectTimeout;
        
};

/**
 * \brief A data value object representing a watcher event received from the ZK.
 */
class ZKWatcherEvent
{
    public:

        /**
         * \brief The type representing the user's context.
         */
        typedef void *ContextType;
        
        /**
         * \brief Constructor.
         * 
         * @param type the type of this event
         * @param state the state of this event
         * @param path the corresponding path, may be empty for some event types
         * @param context the user specified context; possibly NULL
         */
        ZKWatcherEvent() : 
            m_type(-1), m_state(-1), m_path(""), mp_context(NULL) {}
                        
        /**
         * \brief Constructor.
         * 
         * @param type the type of this event
         * @param state the state of this event
         * @param path the corresponding path, may be empty for some event types
         * @param context the user specified context; possibly NULL
         */
        ZKWatcherEvent(int type, int state, const string &path, 
                       ContextType context = NULL) :
            m_type(type), m_state(state), m_path(path), mp_context(context) {}
        
        int getType() const { return m_type; }
        int getState() const { return m_state; }
        string const &getPath() const { return m_path; }
        ContextType getContext() const { return mp_context; }
        
        bool operator==(const ZKWatcherEvent &we) const {
            return m_type == we.m_type && m_state == we.m_state 
                    && m_path == we.m_path && mp_context == we.mp_context;
        }
        
    private:
        
        /**
         * The type of this event. It can be either ZOO_CREATED_EVENT, ZOO_DELETED_EVENT,
         * ZOO_CHANGED_EVENT, ZOO_CHILD_EVENT, ZOO_SESSION_EVENT or ZOO_NOTWATCHING_EVENT. 
         * See zookeeper.h for more details.
         */
        const int m_type;
        
        /**
         * The state of ZK at the time of sending this event.
         * It can be either ZOO_CONNECTING_STATE, ZOO_ASSOCIATING_STATE, 
         * ZOO_CONNECTED_STATE, ZOO_EXPIRED_SESSION_STATE or AUTH_FAILED_STATE.
         * See {@file zookeeper.h} for more details.
         */
        const int m_state;
        
        /**
         * The corresponding path of the node in subject. It may be empty
         * for some event types.
         */
        const string m_path;
        
        /**
         * The pointer to the user specified context, possibly NULL.
         */
        ContextType mp_context;
        
};

/**
 * \brief The type definition of ZK event source.
 */
typedef EventSource<ZKWatcherEvent> ZKEventSource;

/**
 * \brief The type definition of ZK event listener.
 */
typedef EventListener<ZKWatcherEvent> ZKEventListener;
           
/**
 * \brief This is a wrapper around ZK C synchrounous API.
 */
class ZooKeeperAdapter
    : public ZKEventSource
{
    public:
        /**
         * \brief The global function that handles all ZK asynchronous notifications.
         */
        friend void zkWatcher(zhandle_t *, int, int, const char *, void *watcherCtx);
        
        /**
         * \brief The type representing the user's context.
         */
        typedef void *ContextType;
        
        /**
         * \brief The map type of ZK event listener to user specified context mapping.
         */
        typedef map<ZKEventListener *, ContextType> Listener2Context;
        
        /**
         * \brief The map type of ZK path's to listener's contexts.
         */
        typedef map<string, Listener2Context> Path2Listener2Context;
                  
        /**
         * \brief All possible states of this client, in respect to 
         * \brief connection to the ZK server.
         */
        enum AdapterState {
            //mp_zkHandle is NULL
            AS_DISCONNECTED = 0,
            //mp_zkHandle is valid but this client is reconnecting
            AS_CONNECTING,
            //mp_zkHandle is valid and this client is connected
            AS_CONNECTED,
            //mp_zkHandle is valid, however no more calls can be made to ZK API
            AS_SESSION_EXPIRED
        };
                
        /**
         * \brief Constructor.
         * Attempts to create a ZK adapter, optionally connecting
         * to the ZK. Note, that if the connection is to be established
         * and the given listener is NULL, some events may be lost, 
         * as they may arrive asynchronously before this method finishes.
         * 
         * @param config the ZK configuration
         * @param listener the event listener to be used for listening 
         *                 on incoming ZK events;
         *                 if <code>NULL</code> not used
         * @param establishConnection whether to establish connection to the ZK
         * 
         * @throw ZooKeeperException if cannot establish connection to the given ZK
         */
        ZooKeeperAdapter(ZooKeeperConfig config, 
                         ZKEventListener *listener = NULL,
                         bool establishConnection = false) 
            throw(ZooKeeperException);

        /**
         * \brief Destructor.
         */
        ~ZooKeeperAdapter(); 
                  
        /**
         * \brief Returns the current config.
         */
        const ZooKeeperConfig &getZooKeeperConfig() const {
            return m_zkConfig;                      
        }

        /**
         * \brief Restablishes connection to the ZK. 
         * If this adapter is already connected, the current connection 
         * will be dropped and a new connection will be established.
         * 
         * @throw ZooKeeperException if cannot establish connection to the ZK
         */
        void reconnect() throw(ZooKeeperException);
        
        /**
         * \brief Disconnects from the ZK and unregisters {@link #mp_zkHandle}.
         */
        void disconnect();
        
        /**
         * \brief Creates a new node identified by the given path. 
         * This method will optionally attempt to create all missing ancestors.
         * 
         * @param path the absolute path name of the node to be created
         * @param value the initial value to be associated with the node
         * @param flags the ZK flags of the node to be created
         * @param createAncestors if true and there are some missing ancestor nodes, 
         *        this method will attempt to create them
         * 
         * @return true if the node has been successfully created; false otherwise
         * @throw ZooKeeperException if the operation has failed
         */ 
        bool createNode(const string &path, 
                        const string &value = "", 
                        int flags = 0, 
                        bool createAncestors = true) 
            throw(ZooKeeperException);
                  
        /**
         * \brief Creates a new sequence node using the give path as the prefix.
         * This method will optionally attempt to create all missing ancestors.
         * 
         * @param path the absolute path name of the node to be created; 
         * @param value the initial value to be associated with the node
         * @param flags the ZK flags of the sequence node to be created 
         *              (in addition to SEQUENCE)
         * @param createAncestors if true and there are some missing ancestor 
         *                        nodes, this method will attempt to create them
         * 
         * @return the sequence number associate with newly created node,
         *         or -1 if it couldn't be created
         * @throw ZooKeeperException if the operation has failed
         */ 
        int64_t createSequence(const string &path, 
                               const string &value = "", 
                               int flags = 0, 
                               bool createAncestors = true) 
            throw(ZooKeeperException);
        
        /**
         * \brief Deletes a node identified by the given path.
         * 
         * @param path the absolute path name of the node to be deleted
         * @param recursive if true this method will attempt to remove 
         *                  all children of the given node if any exist
         * @param version the expected version of the node. The function will 
         *                fail if the actual version of the node does not match 
         *                the expected version
         * 
         * @return true if the node has been deleted; false otherwise
         * @throw ZooKeeperException if the operation has failed
         */
        bool deleteNode(const string &path, bool recursive = false, int version = -1) 
            throw(ZooKeeperException);
        
        /**
         * \brief Checks whether the given node exists or not.
         * 
         * @param path the absolute path name of the node to be checked
         * @param listener the listener for ZK watcher events; 
         *                 passing non <code>NULL</code> effectively establishes
         *                 a ZK watch on the given node
         * @param context the user specified context that is to be passed
         *                in a corresponding {@link ZKWatcherEvent} at later time; 
         *                not used if <code>listener</code> is <code>NULL</code>
         * @param stat the optional node statistics to be filled in by ZK
         * 
         * @return true if the given node exists; false otherwise
         * @throw ZooKeeperException if the operation has failed
         */
        bool nodeExists(const string &path, 
                        ZKEventListener *listener = NULL, 
                        void *context = NULL,
                        Stat *stat = NULL) 
            throw(ZooKeeperException);

        /**
         * \brief Retrieves list of all children of the given node.
         * 
         * @param path the absolute path name of the node for which to get children
         * @param listener the listener for ZK watcher events; 
         *                 passing non <code>NULL</code> effectively establishes
         *                 a ZK watch on the given node
         * @param context the user specified context that is to be passed
         *                in a corresponding {@link ZKWatcherEvent} at later time; 
         *                not used if <code>listener</code> is <code>NULL</code>
         * 
         * @return the list of absolute paths of child nodes, possibly empty
         * @throw ZooKeeperException if the operation has failed
         */
        void getNodeChildren(vector<string> &children,
                             const string &path, 
                             ZKEventListener *listener = NULL, 
                             void *context = NULL) 
            throw(ZooKeeperException);
                
        /**
         * \brief Gets the given node's data.
         * 
         * @param path the absolute path name of the node to get data from
         * @param listener the listener for ZK watcher events; 
         *                 passing non <code>NULL</code> effectively establishes
         *                 a ZK watch on the given node
         * @param context the user specified context that is to be passed
         *                in a corresponding {@link ZKWatcherEvent} at later time; 
         *                not used if <code>listener</code> is <code>NULL</code>
         * @param stat the optional node statistics to be filled in by ZK
         * 
         * @return the node's data
         * @throw ZooKeeperException if the operation has failed
         */
        string getNodeData(const string &path, 
                           ZKEventListener *listener = NULL, 
                           void *context = NULL,
                           Stat *stat = NULL) 
            throw(ZooKeeperException);
        
        /**
         * \brief Sets the given node's data.
         * 
         * @param path the absolute path name of the node to get data from
         * @param value the node's data to be set
         * @param version the expected version of the node. The function will 
         *                fail if the actual version of the node does not match 
         *                the expected version
         * 
         * @throw ZooKeeperException if the operation has failed
         */
        void setNodeData(const string &path, const string &value, int version = -1) 
            throw(ZooKeeperException);
        
        /**
         * \brief Validates the given path to a node in ZK.
         * 
         * @param the path to be validated
         * 
         * @throw ZooKeeperException if the given path is not valid
         *        (for instance it doesn't start with "/")
         */
        static void validatePath(const string &path) throw(ZooKeeperException);

        /**
         * Returns the current state of this adapter.
         * 
         * @return the current state of this adapter
         * @see AdapterState
         */
        AdapterState getState() const {
            return m_state;
        }          
        
    private:
        
        /**
         * This enum defines methods from this class than can trigger an event.
         */
        enum WatchableMethod {
            NODE_EXISTS = 0,
            GET_NODE_CHILDREN,
            GET_NODE_DATA
        };
                
        /**
         * \brief Creates a new node identified by the given path. 
         * This method is used internally to implement {@link createNode(...)} 
         * and {@link createSequence(...)}. On success, this method will set
         * <code>createdPath</code>.
         * 
         * @param path the absolute path name of the node to be created
         * @param value the initial value to be associated with the node
         * @param flags the ZK flags of the node to be created
         * @param createAncestors if true and there are some missing ancestor nodes, 
         *        this method will attempt to create them
         * @param createdPath the actual path of the node that has been created; 
         *        useful for sequences
         * 
         * @return true if the node has been successfully created; false otherwise
         * @throw ZooKeeperException if the operation has failed
         */ 
        bool createNode(const string &path, 
                        const string &value, 
                        int flags, 
                        bool createAncestors,
                        string &createdPath) 
            throw(ZooKeeperException);
        
        /**
         * Handles an asynchronous event received from the ZK.
         */
        void handleEvent(int type, int state, const string &path);
        
        /**
         * Handles an asynchronous event received from the ZK.
         * This method iterates over all listeners and passes the event 
         * to each of them.
         */
        void handleEvent(int type, int state, const string &path, 
                         const Listener2Context &listeners);        
        
        /**
         * \brief Enqueues the given event in {@link #m_events} queue.
         */
        void enqueueEvent(int type, int state, const string &path);
        
        /**
         * \brief Processes all ZK adapter events in a loop.
         */
        void processEvents();

        /**
         * \brief Processes all user events in a loop.
         */
        void processUserEvents();

        /**
         * \brief Registers the given context in the {@link #m_zkContexts} 
         * \brief contexts map.
         * 
         * @param method the method where the given path is being used
         * @param path the path of interest
         * @param listener the event listener to call back later on
         * @param context the user specified context to be passed back to user
         */
        void registerContext(WatchableMethod method, const string &path, 
                             ZKEventListener *listener, ContextType context);
        
        /**
         * \brief Attempts to find a listener to context map in the contexts' 
         * \brief map, based on the specified criteria.
         * If the context is found, it will be removed the udnerlying map.
         * 
         * @param method the method type identify Listener2Context map
         * @param path the path to be used to search in the Listener2Context map
         * 
         * @return the context map associated with the given method and path, 
         *         or empty map if not found
         */
        Listener2Context findAndRemoveListenerContext(WatchableMethod method, 
                                                      const string &path);

        /**
         * Sets the new state in case it's different then the current one.
         * This method assumes that {@link #m_stateLock} has been already locked.
         * 
         * @param newState the new state to be set
         */
        void setState(AdapterState newState); 
        
        /**
         * Waits until this client gets connected. The total wait time 
         * is given by {@link getRemainingConnectTimeout()}.
         * If a timeout elapses, this method will throw an exception.
         * 
         * @throw ZooKeeperException if unable to connect within the given timeout
         */
        void waitUntilConnected() 
            throw(ZooKeeperException);
                                      
        /**
         * Verifies whether the connection is established,
         * optionally auto reconnecting.
         * 
         * @throw ZooKeeperConnection if this client is disconnected
         *        and auto-reconnect failed or was not allowed
         */
        void verifyConnection() throw(ZooKeeperException);

        /**
         * Returns the remaining connect timeout. The timeout resets
         * to {@link #m_connectTimeout} on a successfull connection to the ZK.
         * 
         * @return the remaining connect timeout, in milliseconds
         */
        long long int getRemainingConnectTimeout() { 
            return m_remainingConnectTimeout; 
        }
        
        /**
         * Resets the remaining connect timeout to {@link #m_connectTimeout}.
         */
        void resetRemainingConnectTimeout() { 
            m_remainingConnectTimeout = m_zkConfig.getConnectTimeout(); 
        }
        
        /**
         * Updates the remaining connect timeout to reflect the given wait time.
         * 
         * @param time the time for how long waited so far on connect to succeed
         */
        void waitedForConnect(long long time) { 
            m_remainingConnectTimeout -= time; 
        }
                
    private:
        
        /**
         * The mutex use to protect {@link #m_zkContexts}.
         */
        zkfuse::Mutex m_zkContextsMutex;
        
        /**
         * The map of registered ZK paths that are being watched.
         * Each entry maps a function type to another map of registered contexts.
         * 
         * @see WatchableMethod
         */
        map<int, Path2Listener2Context> m_zkContexts;
        
        /**
         * The current ZK configuration.
         */
        const ZooKeeperConfig m_zkConfig;

        /**
         * The current ZK session.
         */
        zhandle_t *mp_zkHandle;
        
        /**
         * The blocking queue of all events waiting to be processed by ZK adapter.
         */
        BlockingQueue<ZKWatcherEvent> m_events;
        
        /**
         * The blocking queue of all events waiting to be processed by users
         * of ZK adapter.
         */
        BlockingQueue<ZKWatcherEvent> m_userEvents;
        
        /**
         * The thread that dispatches all events from {@link #m_events} queue.
         */
        CXXThread<ZooKeeperAdapter> m_eventDispatcher;

        /**
         * The thread that dispatches all events from {@link #m_userEvents} queue.
         */
        CXXThread<ZooKeeperAdapter> m_userEventDispatcher;
                
        /**
         * Whether {@link #m_eventDispatcher} is terminating.
         */
        volatile bool m_terminating;
        
        /**
         * Whether this adapter is connected to the ZK.
         */
        volatile bool m_connected;
        
        /**
         * The state of this adapter.
         */
        AdapterState m_state;
        
        /**
         * The lock used to synchronize access to {@link #m_state}.
         */
        Lock m_stateLock;

        /**
         * How much time left for the connect to succeed, in milliseconds.
         */
        long long int m_remainingConnectTimeout;
                
};
        
}   /* end of 'namespace zk' */

#endif /* __ZKADAPTER_H__ */
