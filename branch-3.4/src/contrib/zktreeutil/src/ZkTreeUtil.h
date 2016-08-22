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

#ifndef __ZK_TREE_UTIL_H__
#define __ZK_TREE_UTIL_H__

#include <libxml/parser.h>
#include <libxml/tree.h>
#include "SimpleTree.h"
#include "ZkAdaptor.h"

namespace zktreeutil
{

#define ZKTREEUTIL_INF 1000000000
    /**
     * \brief A structure containing ZK node data.
     */
    struct ZkNodeData
    {
        /**
         * \brief The value string of the ZK node.
         */
        string value;

        /**
         * \brief The flag indicating whether children of the
         * \brief node shduld be ignored during create/diff/update
         */
        bool ignoreUpdate;

        /**
         * \brief Constructor.
         *
         * @param val the value string
         * @param ignore the flag indicating ignore any update/diff
         */
        ZkNodeData (const string& val, bool ignore=false)
            : value (val), ignoreUpdate (ignore) {}

        /**
         * \brief Constructor.
         *
         * @param ignore the flag indicating ignore any update/diff
         */
        ZkNodeData (bool ignore=false)
            : ignoreUpdate (ignore) {}
    };

    /**
     * \brief The type representing a ZK Treenode
     */
    typedef SimpleTreeNode< string, ZkNodeData > ZkTreeNode;

    /**
     * \brief The type representing a ZK Treenode smart-pointer
     */
    typedef boost::shared_ptr< ZkTreeNode > ZkTreeNodeSptr;

    /**
     * \brief The type representing a ZK Adapter smart-pointer
     */
    typedef boost::shared_ptr< ZooKeeperAdapter > ZooKeeperAdapterSptr;

    /**
     * \brief A structure defining a particular action on ZK node;
     * \brief the action can be any of -
     * \brief        CREAT- <zknode>                : creates <zknode> recussively
     * \brief        DELET- <zknode>              : deletes <zknode> recursively
     * \brief        VALUE- <zknode> <value>     : sets <value> to <zknode>
     */
    struct ZkAction
    {
        /**
         * \brief The action type; any of create/delete/setvalue.
         */
        enum ZkActionType
        {
            NONE,
            CREATE,
            DELETE,
            VALUE,
        };

        /**
         * \brief action of this instance
         */
        ZkActionType action;

        /**
         * \brief ZK node key
         */
        string key;

        /**
         * \brief value to be set, if action is setvalue
         */
        string newval;

        /**
         * \brief existing value of the ZK node key
         */
        string oldval;

        /**
         * \brief Constructor.
         */
        ZkAction ()
            : action (ZkAction::NONE) {}

        /**
         * \brief Constructor.
         *
         * @param act the action to be taken
         * @param k the key on which action to be taken
         */
        ZkAction (ZkActionType act, const string& k)
            : action(act),
            key(k) {}

        /**
         * \brief Constructor.
         *
         * @param act the action to be taken
         * @param k the key on which action to be taken
         * @param v the value of the ZK node key
         */
        ZkAction (ZkActionType act, const string& k, const string& v)
            : action(act),
            key(k),
            newval(v) {}

        /**
         * \brief Constructor.
         *
         * @param act the action to be taken
         * @param k the key on which action to be taken
         * @param nv the new value of the ZK node key
         * @param ov the old value of the ZK node key
         */
        ZkAction (ZkActionType act, const string& k, const string& nv, const string& ov)
            : action (act),
            key(k),
            newval(nv),
            oldval(ov) {}
    };

    /**
     * \brief The ZK tree utility class; supports loading ZK tree from ZK server OR
     * \brief from saved XML file, saving ZK tree into XML file, dumping the ZK tree
     * \brief on standard output, creting a diff between saved ZK tree and live ZK
     * \brief tree and incremental update of the live ZK tree.
     */
    class ZkTreeUtil
    {
        public:
            /**
             * \brief Execution flag on ZkAction
             */
            enum ZkActionExecuteFlag
            {
                NONE = 0,
                PRINT = 1,
                EXECUTE = 2,
                INTERACTIVE = 5,
            };

        public:
            /**
             * \brief Connects to zookeeper and returns a valid ZK handle
             *
             * @param zkHosts comma separated list of host:port forming ZK quorum
             * @param a valid ZK handle
             */
            static ZooKeeperAdapterSptr get_zkHandle (const string& zkHosts);


        public:
            /**
             * \brief Constructor.
             */
            ZkTreeUtil () : loaded_(false) {}

            /**
             * \brief loads the ZK tree from ZK server into memory
             *
             * @param zkHosts comma separated list of host:port forming ZK quorum
             * @param path path to the subtree to be loaded into memory
             * @param force forces reloading in case tree already loaded into memory
             */
            void loadZkTree (const string& zkHosts, const string& path="/", bool force=false);

            /**
             * \brief loads the ZK tree from XML file into memory
             *
             * @param zkXmlConfig ZK tree XML file
             * @param force forces reloading in case tree already loaded into memory
             */
            void loadZkTreeXml (const string& zkXmlConfig, bool force=false);

            /**
             * \brief writes the in-memory ZK tree on to ZK server
             *
             * @param zkHosts comma separated list of host:port forming ZK quorum
             * @param path path to the subtree to be written to ZK tree
             * @param force forces cleanup of the ZK tree on the ZK server before writing
             */
            void writeZkTree (const string& zkHosts, const string& path="/", bool force=false) const;

            /**
             * \brief dupms the in-memory ZK tree on the standard output device;
             *
             * @param xml flag indicates whether tree should be dumped in XML format
             * @param depth the depth of the tree to be dumped for non-xml dump
             */
            void dumpZkTree (bool xml=false, int depth=ZKTREEUTIL_INF) const;

            /** 
             * \brief returns a list of actions after taking a diff of in-memory
             * \brief ZK tree and live ZK tree.
             *
             * @param zkHosts comma separated list of host:port forming ZK quorum
             * @param path path to the subtree in consideration while taking diff with ZK tree
             * @return a list of ZKAction instances to be performed on live ZK tree
             */
            vector< ZkAction > diffZkTree (const string& zkHosts, const string& path="/") const;

            /**
             * \brief performs create/delete/setvalue by executing a set of
             * ZkActions on a live ZK tree.
             *
             * @param zkHosts comma separated list of host:port forming ZK quorum
             * @param zkActions set of ZkActions
             * @param execFlags flags indicating print/execute/interactive etc
             */
            void executeZkActions (const string& zkHosts,
                    const vector< ZkAction >& zkActions,
                    int execFlags) const;

        private:

            ZkTreeNodeSptr zkRootSptr_;     // ZK tree root node
            bool loaded_;                        // Falg indicating whether ZK tree loaded into memory
    };
}

#endif // __ZK_TREE_UTIL_H__
