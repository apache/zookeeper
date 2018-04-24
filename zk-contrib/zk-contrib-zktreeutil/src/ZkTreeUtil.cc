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

#include "ZkTreeUtil.h"

#include <map>
#include <iostream>
#include <log4cxx/logger.h>
#include <boost/algorithm/string.hpp>
#include <boost/algorithm/string/split.hpp>

namespace zktreeutil
{
    using std::map;
    using std::pair;

    static ZkTreeNodeSptr loadZkTree_ (ZooKeeperAdapterSptr zkHandle,
            const string& path)
    {
        // Extract the node value
        string value = zkHandle->getNodeData(path);

        // Extract nodename from the path
        string nodename = "/";
        if (path != "/")
        {
            vector< string > nodes;
            boost::split(nodes, path, boost::is_any_of ("/") );
            nodename = nodes[nodes.size()-1];
        }

        // Create tree-node with name and value
        ZkTreeNodeSptr nodeSptr = ZkTreeNodeSptr (new ZkTreeNode (nodename, value));
        std::cerr << "[zktreeutil] loaded nodename: "
            << nodename
            << " value: "
            << value
            << std::endl;

        // Load all the children
        vector< string > cnodes = zkHandle->getNodeChildren (path);
        for (unsigned i = 0; i < cnodes.size(); i++)
            nodeSptr->addChild (loadZkTree_ (zkHandle, cnodes[i]));

        // Return the constructed node
        return nodeSptr;
    }

    static ZkTreeNodeSptr loadZkTreeXml_ (xmlNode* xmlNodePtr)
    {
        // Null check
        if (xmlNodePtr == NULL)
        {
            std::cerr << "[zktreeutil] empty XML node encountered" << std::endl;
            exit (-1);
        }

        // Get the node name
        xmlChar* name = xmlGetProp (xmlNodePtr, BAD_CAST "name");
        string nameStr = (const char*)name;
        std::cerr << "[zktreeutil] node name: " << nameStr;
        xmlFree (name);
        // Get the node value
        string valueStr;
        xmlChar* value = xmlGetProp (xmlNodePtr, BAD_CAST "value");
        if (value)
        {
            valueStr = (const char*)value;
            std::cerr << " value: " << valueStr;
        }
        xmlFree (value);
        // Get the ignore flag
        bool doIgnore = false;
        xmlChar* ignore = xmlGetProp (xmlNodePtr, BAD_CAST "ignore");
        if (ignore)
        {
            string ignoreStr = (const char*) ignore;
            if (ignoreStr == "true" || ignoreStr == "yes" || ignoreStr == "1")
            {
                doIgnore = true;
                std::cerr << " <ignore:>";
            }
        }
        xmlFree (ignore);
        std::cerr << std::endl;

        // Create the zk node
        ZkTreeNodeSptr nodeSptr =
            ZkTreeNodeSptr (new ZkTreeNode (nameStr,
                        ZkNodeData (valueStr, doIgnore)));

        // Load the children
        for (xmlNode* chldNode = xmlNodePtr->children;
                chldNode;
                chldNode = chldNode->next)
            if (chldNode->type == XML_ELEMENT_NODE)
                nodeSptr->addChild (loadZkTreeXml_ (chldNode));

        // Return the loaded node
        return nodeSptr;
    }

    static void writeZkTree_ (ZooKeeperAdapterSptr zkHandle,
            const ZkTreeNodeSptr zkNodeSptr,
            const string& path)
    {
        // Create the path in zk-tree
        zkHandle->createNode(path.c_str(), "", 0, false);
        std::cerr << "[zktreeutil] created key: " << path << std::endl;
        // Set value for the path
        string value = zkNodeSptr->getData().value;
        if (value != "")
        {
            zkHandle->setNodeData (path.c_str(), value.c_str());
            std::cerr << "[zktreeutil] set value: " << std::endl;
        }

        // Go deep to write the subtree rooted in the node, if not to be ignored
        if (!(zkNodeSptr->getData().ignoreUpdate))
        {
            for (unsigned i=0; i < zkNodeSptr->numChildren(); i++)
            {
                ZkTreeNodeSptr childNodeSptr = zkNodeSptr->getChild (i);
                // Add the node name into the path and write in zk-tree
                string cpath = ((path != "/")? path : "")
                    + string("/")
                    + childNodeSptr->getKey();
                writeZkTree_ (zkHandle, childNodeSptr, cpath);
            }
        }

        return;
    }

    static void addTreeZkAction_ (const ZkTreeNodeSptr zkNodeSptr,
            const string& path,
            vector< ZkAction >& actions)
    {
        // Create the key
        actions.push_back (ZkAction (ZkAction::CREATE, path));

        // Set value for the new key
        if (zkNodeSptr->getData().value != "")
            actions.push_back (ZkAction (ZkAction::VALUE,
                        path,
                        zkNodeSptr->getData().value));

        // Add all the children
        for (unsigned i=0; i < zkNodeSptr->numChildren(); i++)
        {
            ZkTreeNodeSptr childSptr = zkNodeSptr->getChild (i);
            string cpath = path + string("/") + childSptr->getKey();
            addTreeZkAction_ (childSptr, cpath, actions);
        }

        return;
    }

    static xmlNodePtr dumpZkTreeXml_ (const ZkTreeNodeSptr zkNodeSptr)
    {
        // Create xml node with zknode name and value
        string nodename = zkNodeSptr->getKey ();
        string value = zkNodeSptr->getData().value;
        xmlNodePtr node = xmlNewNode(NULL, BAD_CAST "zknode");
        xmlNewProp (node, BAD_CAST "name", BAD_CAST nodename.c_str());
        if (value.length())
            xmlNewProp (node, BAD_CAST "value", BAD_CAST value.c_str());

        // Add all the children rotted at this node
        for (unsigned i=0; i < zkNodeSptr->numChildren(); i++)
            xmlAddChild (node, dumpZkTreeXml_ (zkNodeSptr->getChild (i)));

        // Return xml node
        return node;
    }

    static void dumpZkTree_ (const ZkTreeNodeSptr zkNodeSptr,
            int maxLevel,
            int level,
            vector< bool >& masks)
    {
        // Check the max. dlevel to be dumped
        if (level > maxLevel)
            return;

        
        // Create branch
        for (int i=0; i < level; i++) 
        {
            if ( i== level-1) std::cout << "|   ";
            else if (masks[i]) std::cout << "    ";
            else std::cout << "|   ";
        }
        std::cout << std::endl;
        for (int i=0; i < level-1; i++)
        {
            if (masks[i]) std::cout << "    ";
            else std::cout << "|   ";
        }

        // Dump the node name and value
        std::cout << "|--[" << zkNodeSptr->getKey();
        if (zkNodeSptr->getData().value != "")
            std::cout << " => " << zkNodeSptr->getData().value;
        std::cout << "]" << std::endl;

        // Dump all the children
        for (unsigned i=0; i < zkNodeSptr->numChildren(); i++)
        {
            // Add mask for last child
            if (i == zkNodeSptr->numChildren()-1)
                masks.push_back(true);
            else
                masks.push_back(false);
            dumpZkTree_ (zkNodeSptr->getChild (i), maxLevel, level+1, masks);
        }

        masks.pop_back();
        return;
    }

    static ZkTreeNodeSptr traverseBranch_ (const ZkTreeNodeSptr& zkRootSptr,
            const string& path)
    {
        // Check if the tree is loaded into memory
        if (zkRootSptr == NULL)
        {
            string errMsg = "[zktreeutil] null root passed for traversing";
            std::cout << errMsg << std::endl;
            throw std::logic_error (errMsg);
        }

        // Split the path and add intermediate znodes
        vector< string > nodes;
        boost::split(nodes, path, boost::is_any_of ("/") );

        // Start traversing the tree
        ZkTreeNodeSptr currNodeSptr = zkRootSptr;
        for (unsigned znode_idx = 1; znode_idx < nodes.size(); znode_idx++)
        {
            bool found = false;
            for (unsigned i=0; i < currNodeSptr->numChildren(); i++)
            {
                ZkTreeNodeSptr  childNodeSptr = currNodeSptr->getChild(i);
                if (childNodeSptr->getKey() == nodes[znode_idx])
                {
                    // Found! go to the znode
                    currNodeSptr = childNodeSptr;
                    found = true;
                    break;
                }
            }
            if (!found) // No such znode found; return NULL node-ptr
            {
                string errMsg = string("[zktreeutil] unknown znode during traversal: ")
                    + nodes[znode_idx];
                std::cout << errMsg << std::endl;
                throw std::logic_error (errMsg);
            }
        }

        return currNodeSptr;
    }

    static ZkTreeNodeSptr createAncestors_ (const string& path)
    {
        // Create the root znode
        ZkTreeNodeSptr zkRootSptr = ZkTreeNodeSptr (new ZkTreeNode ("/"));
        ZkTreeNodeSptr currNodeSptr = zkRootSptr;
        // Split the path and add intermediate znodes
        vector< string > nodes;
        boost::split(nodes, path, boost::is_any_of ("/") );
        for (unsigned i=1; i < nodes.size()-1; i++)
        {
            ZkTreeNodeSptr childNodeSptr = ZkTreeNodeSptr (new ZkTreeNode (nodes[i]));
            currNodeSptr->addChild (childNodeSptr);
            currNodeSptr = childNodeSptr;
        }

        //Return the root of the branch
        return zkRootSptr;
    }

    ZooKeeperAdapterSptr ZkTreeUtil::get_zkHandle (const string& zkHosts)
    {
        try
        {
            // Create an instance of ZK adapter.
            ZooKeeperConfig config (zkHosts, 10000);
            ZooKeeperAdapterSptr zkHandleSptr =
                ZooKeeperAdapterSptr (new ZooKeeperAdapter (config));
            return zkHandleSptr;
        }
        catch (const ZooKeeperException &e)
        {
            std::cerr << "[zktreeutil] zooKeeper exception caught: "
                << e.what()
                << std::endl;
            throw;
        }
        catch (std::exception &stde)
        {
            std::cerr << "[zktreeutil] standard exception caught: "
                << stde.what()
                << std::endl;
            throw;
        }
        catch (...)
        {
            std::cerr
                << "[zktreeutil] unknown exception while connecting to zookeeper"
                << std::endl;
            throw;
        }
    }


    void ZkTreeUtil::loadZkTree (const string& zkHosts,
            const string& path,
            bool force)
    {
        // Check if already loaded
        if (loaded_ && !force)
        {
            std::cerr << "[zktreeutil] zk-tree already loaded into memory"
                << std::endl;
            return;
        }

        // Connect to ZK server
        ZooKeeperAdapterSptr zkHandle = get_zkHandle (zkHosts);
        std::cerr << "[zktreeutil] connected to ZK serverfor reading"
            << std::endl;

        // Check the existence of the path to znode
        if (!zkHandle->nodeExists (path))
        {
            string errMsg = string("[zktreeutil] path does not exists : ") + path;
            std::cout << errMsg << std::endl;
            throw std::logic_error (errMsg);
        }

        // Load the rooted (sub)tree
        ZkTreeNodeSptr zkSubrootSptr = loadZkTree_ (zkHandle, path);

        //  Create the ancestors before loading the rooted subtree
        if (path != "/")
        {
            zkRootSptr_ = createAncestors_(path);
            string ppath = path.substr (0, path.rfind('/'));
            ZkTreeNodeSptr parentSptr = traverseBranch_( zkRootSptr_, ppath);
            parentSptr->addChild (zkSubrootSptr);
        }
        else // Loaded entire zk-tree
        {
            zkRootSptr_ = zkSubrootSptr;
        }

        // Set load flag
        loaded_ = true;
        return;
    }

    void ZkTreeUtil::loadZkTreeXml (const string& zkXmlConfig,
            bool force)
    {
        // Check if already loaded
        if (loaded_ && !force)
        {
            std::cerr << "[zktreeutil] zk-tree already loaded into memory"
                << std::endl;
            return;
        }

        // Parse the file and get the DOM
        xmlDocPtr docPtr = xmlReadFile(zkXmlConfig.c_str(), NULL, 0);
        if (docPtr == NULL) {
            std::cerr << "[zktreeutil] could not parse XML file "
                << zkXmlConfig
                << std::endl;
            exit (-1);
        }
        std::cerr << "[zktreeutil] zk-tree XML parsing successful"
            << std::endl;

        // Get the root element node
        xmlNodePtr rootPtr = xmlDocGetRootElement(docPtr);
        // Create the root zk node
        zkRootSptr_ = ZkTreeNodeSptr (new ZkTreeNode ("/"));
        // Load the rooted XML tree
        for (xmlNode* chldNode = rootPtr->children;
                chldNode;
                chldNode = chldNode->next)
        {
            if (chldNode->type == XML_ELEMENT_NODE)
                zkRootSptr_->addChild (loadZkTreeXml_ (chldNode));
        }

        // set oad flag
        loaded_ = true;
        // Cleanup stuff
        xmlFreeDoc(docPtr);
        xmlCleanupParser();
        return;
    }

    void ZkTreeUtil::writeZkTree (const string& zkHosts,
            const string& path,
            bool force) const
    {
        // Connect to ZK server
        ZooKeeperAdapterSptr zkHandle = get_zkHandle (zkHosts);
        std::cerr << "[zktreeutil] connected to ZK server for writing"
            << std::endl;

        // Go to the rooted subtree
        ZkTreeNodeSptr zkRootSptr = traverseBranch_ (zkRootSptr_, path);

        // Cleanup before write if forceful write enabled
        if (force)
        {
            if (path != "/") // remove the subtree rooted at the znode
            {
                // Delete the subtree rooted at the znode before write
                if (zkHandle->nodeExists (path))
                {
                    std::cerr << "[zktreeutil] deleting subtree rooted at "
                        << path
                        << "..."
                        << std::endl;
                    zkHandle->deleteNode (path, true);
                }
            }
            else // remove the rooted znodes
            {
                std::cerr << "[zktreeutil] deleting rooted zk-tree"
                    << "..."
                    << std::endl;
                // Get the root's children
                vector< string > cnodes = zkHandle->getNodeChildren ("/");
                for (unsigned i=0; i < cnodes.size(); i++)
                {
                    if ( cnodes[i] != "/zookeeper") // reserved for zookeeper use
                        zkHandle->deleteNode(cnodes[i], true);
                }
            }
        }

        // Start tree construction
        writeZkTree_ (zkHandle, zkRootSptr, path);
        return;
    }

    void ZkTreeUtil::dumpZkTree (bool xml, int depth) const
    {
        if (xml)
        {
            // Creates a new document, a node and set it as a root node
            xmlDocPtr docPtr = xmlNewDoc(BAD_CAST "1.0");
            xmlNodePtr rootNode = xmlNewNode(NULL, BAD_CAST "root");
            xmlDocSetRootElement(docPtr, rootNode);

            // Add all the rooted children
            for (unsigned i=0; i < zkRootSptr_->numChildren(); i++)
                xmlAddChild (rootNode, dumpZkTreeXml_ (zkRootSptr_->getChild (i)));

            // Dumping document to stdio or file
            xmlSaveFormatFileEnc("-", docPtr, "UTF-8", 1);

            // Cleanup stuff
            xmlFreeDoc(docPtr);
            xmlCleanupParser();
            return;
        }

        // Dump text
        std::cout << "/" << std::endl;
        vector< bool > masks;
        for (unsigned i=0; i < zkRootSptr_->numChildren(); i++)
        {
            if (i == zkRootSptr_->numChildren()-1)
                masks.push_back(true);
            else
                masks.push_back(false);
            dumpZkTree_ (zkRootSptr_->getChild (i), depth, 1, masks);
        }

        return;
    }

    vector< ZkAction > ZkTreeUtil::diffZkTree (const string& zkHosts,
            const string& path) const
    {
        // Action container
        vector< ZkAction > actions;

        if (!loaded_)
        {
            std::cout << "[zktreeutil] zk-tree not loaded for diff"
                << std::endl;
            exit (-1);
        }

        // Load the rooted subtree from zookeeper
        ZooKeeperAdapterSptr zkHandle = get_zkHandle (zkHosts);
        std::cerr << "[zktreeutil] connected to ZK server for reading"
            << std::endl;
        ZkTreeNodeSptr zkLiveRootSptr = loadZkTree_ (zkHandle, path);

        // Go to the saved rooted subtree
        ZkTreeNodeSptr zkLoadedRootSptr =
            traverseBranch_ (zkRootSptr_, path);

        // Check the root value first
        if (zkLoadedRootSptr->getData().value
                != zkLiveRootSptr->getData().value)
        {
            actions.push_back (ZkAction (ZkAction::VALUE,
                        path,
                        zkLoadedRootSptr->getData().value,
                        zkLiveRootSptr->getData().value));
        }

        // Start traversal from root
        vector< string > ppaths;
        vector< pair< ZkTreeNodeSptr, ZkTreeNodeSptr > > commonNodes;
        ppaths.push_back ((path != "/")? path : "");
        commonNodes.push_back (pair< ZkTreeNodeSptr, ZkTreeNodeSptr >
                (zkLoadedRootSptr, zkLiveRootSptr));

        for (unsigned j=0; j < commonNodes.size(); j++)
        {
            // Get children of loaded tree
            map< string, ZkTreeNodeSptr > loadedChildren;
            for (unsigned i=0; i < commonNodes[j].first->numChildren(); i++)
            {
                ZkTreeNodeSptr childSptr = commonNodes[j].first->getChild (i);
                loadedChildren[childSptr->getKey()] = childSptr;
            }
            // Get children of live tree
            map< string, ZkTreeNodeSptr > liveChildren;
            for (unsigned i=0; i < commonNodes[j].second->numChildren(); i++)
            {
                ZkTreeNodeSptr childSptr = commonNodes[j].second->getChild (i);
                liveChildren[childSptr->getKey()] = childSptr;
            }

            // Start comparing the children
            for (map< string, ZkTreeNodeSptr >::const_iterator it =
                    loadedChildren.begin();
                    it != loadedChildren.end();
                    it++)
            {
                bool ignoreKey = it->second->getData().ignoreUpdate;
                string loadedVal = it->second->getData().value;
                // Path to this node
                string path = ppaths[j] + string("/") + it->first;

                map< string, ZkTreeNodeSptr >::const_iterator jt =
                    liveChildren.find (it->first);
                if (jt != liveChildren.end())
                {
                    // Key is present in live zk-tree
                    string liveVal = jt->second->getData().value;
                    // Check value for the key, if not ignored
                    if (!ignoreKey)
                    {
                        if (loadedVal != liveVal)
                        {
                            // Value differs, set the new value for the key
                            actions.push_back (ZkAction (ZkAction::VALUE,
                                        path,
                                        loadedVal,
                                        liveVal));
                        }

                        // Add node to common nodes
                        ppaths.push_back (path);
                        commonNodes.push_back (pair< ZkTreeNodeSptr, ZkTreeNodeSptr >
                                (it->second, jt->second));
                    }

                    // Remove the live zk node
                    liveChildren.erase (it->first);
                }
                else
                {
                    // Add the subtree rooted to this node, if not ignored
                    if (!ignoreKey)
                        addTreeZkAction_ (it->second, path, actions);
                }
            }

            // Remaining live zk nodes to be deleted
            for (map< string, ZkTreeNodeSptr >::const_iterator it = liveChildren.begin();
                    it != liveChildren.end(); it++)
            {
                string path = ppaths[j] + string("/") + it->first;
                actions.push_back (ZkAction (ZkAction::DELETE, path));
            }
        }
        // return the diff actions
        return actions;
    }

    void ZkTreeUtil::executeZkActions (const string& zkHosts,
            const vector< ZkAction >& zkActions,
            int execFlags) const
    {
        // Execute the diff zk actions
        if (zkActions.size())
        {
            // Connect to Zookeeper for writing
            ZooKeeperAdapterSptr zkHandleSptr;
            if ((execFlags & EXECUTE)
                    || (execFlags & INTERACTIVE))
            {
                zkHandleSptr = get_zkHandle (zkHosts);
                std::cerr << "[zktreeutil] connected to ZK server for writing"
                    << std::endl;
            }

            for (unsigned i=0; i < zkActions.size(); i++)
            {
                if (zkActions[i].action == ZkAction::CREATE)
                {
                    if (execFlags & PRINT)
                        std::cout << "CREAT- key:" << zkActions[i].key << std::endl;
                    if (execFlags & EXECUTE)
                    {
                        if (execFlags & INTERACTIVE)
                        {
                            string resp;
                            std::cout << "Execute this action?[yes/no]: ";
                            std::getline(std::cin, resp);
                            if (resp != "yes")
                                continue;
                        }
                        zkHandleSptr->createNode(zkActions[i].key.c_str(), "", 0, false);
                    }
                }
                else if (zkActions[i].action == ZkAction::DELETE)
                {
                    if (execFlags & PRINT)
                        std::cout << "DELET- key:" << zkActions[i].key << std::endl;
                    if (execFlags & EXECUTE)
                    {
                        if (execFlags & INTERACTIVE)
                        {
                            string resp;
                            std::cout << "Execute this action?[yes/no]: ";
                            std::getline(std::cin, resp);
                            if (resp != "yes")
                                continue;
                        }
                        zkHandleSptr->deleteNode(zkActions[i].key.c_str(), true);
                    }
                }
                else if (zkActions[i].action == ZkAction::VALUE)
                {
                    if (execFlags & PRINT)
                    {
                        std::cout << "VALUE- key:"
                            << zkActions[i].key
                            << " value:" << zkActions[i].newval;
                        if (zkActions[i].oldval != "")
                            std::cout << " old_value:" << zkActions[i].oldval;
                        std::cout << std::endl;
                    }
                    if (execFlags & EXECUTE)
                    {
                        if (execFlags & INTERACTIVE)
                        {
                            string resp;
                            std::cout << "Execute this action?[yes/no]: ";
                            std::getline(std::cin, resp);
                            if (resp != "yes")
                                continue;
                        }
                        zkHandleSptr->setNodeData (zkActions[i].key, zkActions[i].newval);
                    }
                }
            }
        }

        return;
    }

}

