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

#include "ZkAdaptor.h"
#include <string.h>
#include <sstream>
#include <iostream>
#include <algorithm>
#include <log4cxx/logger.h>

// Logger
static log4cxx::LoggerPtr zkLoggerPtr = log4cxx::Logger::getLogger ("zookeeper.core");

namespace zktreeutil
{
    /**
     * \brief This class provides logic for checking if a request can be retried.
     */
    class RetryHandler
    {
        public:
            RetryHandler(const ZooKeeperConfig &zkConfig) : m_zkConfig(zkConfig)
            {
                if (zkConfig.getAutoReconnect())
                    retries = 2;
                else
                    retries = 0;
            }

            /**
             * \brief Attempts to fix a side effect of the given RC.
             * 
             * @param rc the ZK error code
             * @return whether the error code has been handled and the caller should 
             *         retry an operation the caused this error
             */
            bool handleRC(int rc)
            {
                //check if the given error code is recoverable
                if (!retryOnError(rc))
                    return false;

                std::cerr << "[zktreeuti] Number of retries left: " << retries << std::endl;
                if (retries-- > 0)
                    return true;
                else
                    return false;
            }

        private:
            /**
             * The ZK config.
             */
            const ZooKeeperConfig &m_zkConfig;

            /**
             * The number of outstanding retries.
             */
            int retries;    

            /**
             * Checks whether the given error entitles this adapter
             * to retry the previous operation.
             * 
             * @param zkErrorCode one of the ZK error code
             */
            static bool retryOnError(int zkErrorCode)
            {
                return (zkErrorCode == ZCONNECTIONLOSS || zkErrorCode == ZOPERATIONTIMEOUT);
            }
    };


    // =======================================================================

    ZooKeeperAdapter::ZooKeeperAdapter(ZooKeeperConfig config) throw(ZooKeeperException) :
        m_zkConfig(config),
        mp_zkHandle(NULL)
    {
        // Enforce setting up appropriate ZK log level
        if (zkLoggerPtr->isDebugEnabled()
#ifdef LOG4CXX_TRACE
                || zkLoggerPtr->isTraceEnabled()
#endif
            ) 
        {
            zoo_set_debug_level( ZOO_LOG_LEVEL_DEBUG );
        } else if (zkLoggerPtr->isInfoEnabled()) {
            zoo_set_debug_level( ZOO_LOG_LEVEL_INFO );
        } else if (zkLoggerPtr->isWarnEnabled()) {
            zoo_set_debug_level( ZOO_LOG_LEVEL_WARN );
        } else {
            zoo_set_debug_level( ZOO_LOG_LEVEL_ERROR );
        }

        // Establish the connection
        reconnect();
    }

    ZooKeeperAdapter::~ZooKeeperAdapter()
    {
        try
        {
            disconnect();
        }
        catch (std::exception &e)
        {
            std::cerr << "[zktreeutil] An exception while disconnecting from ZK: "
                << e.what()
                << std::endl;
        }
    }

    void ZooKeeperAdapter::validatePath(const string &path) throw(ZooKeeperException)
    {
        if (path.find ("/") != 0)
        {
            std::ostringstream oss;
            oss << "Node path must start with '/' but" "it was '"
                << path
                << "'";
            throw ZooKeeperException (oss.str());
        }
        if (path.length() > 1)
        {
            if (path.rfind ("/") == path.length() - 1)
            {
                std::ostringstream oss;
                oss << "Node path must not end with '/' but it was '"
                    << path
                    << "'";
                throw ZooKeeperException (oss.str());
            }
            if (path.find( "//" ) != string::npos)
            {
                std::ostringstream oss;
                oss << "Node path must not contain '//' but it was '"
                    << path
                    << "'";
                throw ZooKeeperException (oss.str());
            }
        }
    }

    void ZooKeeperAdapter::disconnect()
    {
        if (mp_zkHandle != NULL)
        {
            zookeeper_close (mp_zkHandle);
            mp_zkHandle = NULL;
        }
    }

    void ZooKeeperAdapter::reconnect() throw(ZooKeeperException)
    {
        // Clear the connection state
        disconnect();

        // Establish a new connection to ZooKeeper
        mp_zkHandle = zookeeper_init( m_zkConfig.getHosts().c_str(), 
                NULL, 
                m_zkConfig.getLeaseTimeout(),
                0,
                NULL,
                0);
        if (mp_zkHandle == NULL)
        {
            // Invalid handle returned
            std::ostringstream oss;
            oss << "Unable to connect to ZK running at '"
                << m_zkConfig.getHosts()
                << "'";
            throw ZooKeeperException (oss.str());
        }

        // Enter into connect loop
        int64_t connWaitTime = m_zkConfig.getConnectTimeout();
        while (1)
        {
            int state = zoo_state (mp_zkHandle);
            if (state == ZOO_CONNECTED_STATE)
            {
                // connected
                std::cerr << "[zktreeutil] Connected! mp_zkHandle: "
                    << mp_zkHandle
                    << std::endl; 
                return;
            }
            else if ( state && state != ZOO_CONNECTING_STATE)
            {
                // Not connecting any more... some other issue
                std::ostringstream oss;
                oss << "Unable to connect to ZK running at '"
                    << m_zkConfig.getHosts()
                    << "'; state="
                    << state;
                throw ZooKeeperException (oss.str());
            }

            // Still connecting, wait and come back
            struct timeval now;
            gettimeofday( &now, NULL );
            int64_t milliSecs = -(now.tv_sec * 1000LL + now.tv_usec / 1000);
            std::cerr << "[zktreeutil] About to wait 1 sec" << std::endl;
            sleep (1);
            gettimeofday( &now, NULL );
            milliSecs += now.tv_sec * 1000LL + now.tv_usec / 1000;
            connWaitTime -= milliSecs;
            // Timed out !!!
            if (connWaitTime <= 0)
                break;
        }

        // Timed out while connecting
        std::ostringstream oss;
        oss << "Timed out while connecting to ZK running at '"
            << m_zkConfig.getHosts()
            << "'";
        throw ZooKeeperException (oss.str());
    }

    void ZooKeeperAdapter::verifyConnection() throw(ZooKeeperException)
    {
        // Check connection state
        int state = zoo_state (mp_zkHandle);
        if (state != ZOO_CONNECTED_STATE)
        {
            if (m_zkConfig.getAutoReconnect())
            {
                // Trying to reconnect
                std::cerr << "[zktreeutil] Trying to reconnect..." << std::endl;
                reconnect();
            }
            else
            {
                std::ostringstream oss;
                oss << "Disconnected from ZK running at '"
                    << m_zkConfig.getHosts()
                    << "'; state="
                    << state;
                throw ZooKeeperException (oss.str());
            }
        }
    }

    bool ZooKeeperAdapter::createNode(const string &path, 
            const string &value, 
            int flags, 
            bool createAncestors) throw(ZooKeeperException) 
    {
        const int MAX_PATH_LENGTH = 1024;
        char realPath[MAX_PATH_LENGTH];
        realPath[0] = 0;

        int rc;
        RetryHandler rh(m_zkConfig);
        do
        {
            verifyConnection();
            rc = zoo_create( mp_zkHandle, 
                    path.c_str(), 
                    value.c_str(),
                    value.length(),
                    &ZOO_OPEN_ACL_UNSAFE,
                    flags,
                    realPath,
                    MAX_PATH_LENGTH );
        } while (rc != ZOK && rh.handleRC(rc));
        if (rc != ZOK) // check return status
        {
            if (rc == ZNODEEXISTS)
            {
                //the node already exists
                std::cerr << "[zktreeutil] ZK node " << path << " already exists" << std::endl;
                return false;
            }
            else if (rc == ZNONODE && createAncestors)
            {
                std::cerr << "[zktreeutil] Intermediate ZK node missing in path " << path << std::endl;
                //one of the ancestors doesn't exist so lets start from the root 
                //and make sure the whole path exists, creating missing nodes if
                //necessary
                for (string::size_type pos = 1; pos != string::npos; )
                {
                    pos = path.find( "/", pos );
                    if (pos != string::npos)
                    {
                        try
                        {
                            createNode( path.substr( 0, pos ), "", 0, true );
                        }
                        catch (ZooKeeperException &e)
                        {
                            throw ZooKeeperException( string("Unable to create " "node ") + path, rc );
                        }
                        pos++;
                    }
                    else
                    {
                        // No more path components
                        return createNode( path, value, flags, false );
                    }
                }
            }

            // Unexpected error during create
            std::cerr << "[zktreeutil] Error in creating ZK node " << path << std::endl;
            throw ZooKeeperException( string("Unable to create node ") + path, rc );
        }

        // Success
        std::cerr << "[zktreeutil] " << realPath << " has been created" << std::endl;
        return true;
    }

    bool ZooKeeperAdapter::deleteNode(const string &path,
            bool recursive,
            int version) throw(ZooKeeperException)
    {
        // Validate the zk path
        validatePath( path );

        int rc;
        RetryHandler rh(m_zkConfig);
        do
        {
            verifyConnection();
            rc = zoo_delete( mp_zkHandle, path.c_str(), version );
        } while (rc != ZOK && rh.handleRC(rc));
        if (rc != ZOK) //check return status
        {
            if (rc == ZNONODE)
            {
                std::cerr << "[zktreeutil] ZK Node "
                    << path
                    << " does not exist"
                    << std::endl;
                return false;
            }
            if (rc == ZNOTEMPTY && recursive)
            {
                std::cerr << "[zktreeutil] ZK Node "
                    << path
                    << " not empty; deleting..."
                    << std::endl;
                //get all children and delete them recursively...
                vector<string> nodeList = getNodeChildren (path);
                for (vector<string>::const_iterator i = nodeList.begin();
                        i != nodeList.end();
                        ++i) {
                    deleteNode( *i, true );
                }
                //...and finally attempt to delete the node again
                return deleteNode( path, false ); 
            }

            // Unexpected return without success
            std::cerr << "[zktreeutil] Unable to delete ZK node " << path << std::endl;
            throw ZooKeeperException( string("Unable to delete node ") + path, rc );
        }

        // success
        std::cerr << "[zktreeutil] " << path << " has been deleted" << std::endl;
        return true;
    }

    vector< string > ZooKeeperAdapter::getNodeChildren (const string &path) throw (ZooKeeperException)
    {
        // Validate the zk path
        validatePath( path );

        String_vector children;
        memset( &children, 0, sizeof(children) );
        int rc;
        RetryHandler rh(m_zkConfig);
        do
        {
            verifyConnection();
            rc = zoo_get_children( mp_zkHandle,
                    path.c_str(), 
                    0,
                    &children );
        } while (rc != ZOK && rh.handleRC(rc));
        if (rc != ZOK) // check return code
        {
            std::cerr << "[zktreeutil] Error in fetching children of " << path << std::endl;
            throw ZooKeeperException( string("Unable to get children of node ") + path, rc );
        }
        else
        {
            vector< string > nodeList;
            for (int i = 0; i < children.count; ++i)
            {
                //convert each child's path from relative to absolute 
                string absPath(path);
                if (path != "/")
                {
                    absPath.append( "/" );
                } 
                absPath.append( children.data[i] ); 
                nodeList.push_back( absPath );
            }

            //make sure the order is always deterministic
            sort( nodeList.begin(), nodeList.end() );
            return nodeList;
        }
    }

    bool ZooKeeperAdapter::nodeExists(const string &path) throw(ZooKeeperException)
    {
        // Validate the zk path
        validatePath( path );

        struct Stat tmpStat;
        struct Stat* stat = &tmpStat;
        memset( stat, 0, sizeof(Stat) );

        int rc;
        RetryHandler rh(m_zkConfig);
        do {
            verifyConnection();
            rc = zoo_exists( mp_zkHandle,
                    path.c_str(),
                    0,
                    stat );
        } while (rc != ZOK && rh.handleRC(rc));
        if (rc != ZOK)
        {
            if (rc == ZNONODE)
                return false;
            // Some error
            std::cerr << "[zktreeutil] Error in checking existance of " << path << std::endl;
            throw ZooKeeperException( string("Unable to check existence of node ") + path, rc );
        } else {
            return true;        
        }
    }

    string ZooKeeperAdapter::getNodeData(const string &path) throw(ZooKeeperException)
    {
        // Validate the zk path
        validatePath( path );

        const int MAX_DATA_LENGTH = 128 * 1024;
        char buffer[MAX_DATA_LENGTH];
        memset( buffer, 0, MAX_DATA_LENGTH );
        struct Stat tmpStat;
        struct Stat* stat = &tmpStat;
        memset( stat, 0, sizeof(Stat) );

        int rc;
        int len;
        RetryHandler rh(m_zkConfig);
        do {
            verifyConnection();
            len = MAX_DATA_LENGTH - 1;
            rc = zoo_get( mp_zkHandle,
                    path.c_str(),
                    0,
                    buffer, &len, stat );
        } while (rc != ZOK && rh.handleRC(rc));
        if (rc != ZOK) // checl return code
        {
            std::cerr << "[zktreeutil] Error in fetching value of " << path << std::endl;
            throw ZooKeeperException( string("Unable to get data of node ") + path, rc );
        }

        // return data
        return string( buffer, buffer + len );
    }

    void ZooKeeperAdapter::setNodeData(const string &path,
            const string &value,
            int version) throw(ZooKeeperException)
    {
        // Validate the zk path
        validatePath( path );

        int rc;
        RetryHandler rh(m_zkConfig);
        do {
            verifyConnection();
            rc = zoo_set( mp_zkHandle,
                    path.c_str(),
                    value.c_str(),
                    value.length(),
                    version);
        } while (rc != ZOK && rh.handleRC(rc));
        if (rc != ZOK) // check return code
        {
            std::cerr << "[zktreeutil] Error in setting value of " << path << std::endl;
            throw ZooKeeperException( string("Unable to set data for node ") + path, rc );
        }
        // success
    }

}   /* end of 'namespace zktreeutil' */
