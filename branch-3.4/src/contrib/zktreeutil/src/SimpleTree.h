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

#ifndef __SIMPLE_TREE_H__
#define __SIMPLE_TREE_H__

#include <vector>
#include <boost/shared_ptr.hpp>

namespace zktreeutil
{
   using std::vector;

   /**
    * \brief A simple tree data-structure template.
    */
   template < class KeyType, class DataType > class SimpleTreeNode
   {
      private:
         /**
          * \brief The type representing simple-tree node smart-pointer.
          */
         typedef boost::shared_ptr< SimpleTreeNode< KeyType, DataType > > SimpleTreeNodeSptr;

      public:
         /**
          * \brief Constructor.
          * 
          * @param isRoot the flag indicating whether the node is root.
          */
         SimpleTreeNode (bool isRoot=false) : isRoot_(isRoot)
         {
         }

         /**
          * \brief Constructor.
          * 
          * @param key the key stored at the tree node
          * @param isRoot the flag indicating whether the node is root
          */
         SimpleTreeNode (const KeyType& key, bool isRoot=false) :
            isRoot_(isRoot), key_(key)
         {
         }

         /**
          * \brief Constructor.
          * 
          * @param key the key stored at the tree node
          * @param val the value stored at the tree node
          * @param isRoot the flag indicating whether the node is root
          */
         SimpleTreeNode (const KeyType& key, const DataType& val, bool isRoot=false) :
            isRoot_(isRoot), key_(key), val_(val)
         {
         }

         /**
          * \brief Destructor.
          */
         ~SimpleTreeNode () throw() {}

         /**
          * \brief Add a child node to this node.
          *
          * @param node the child node to be added
          */
         void addChild (const SimpleTreeNodeSptr node) { children_.push_back (node); }

         /**
          * \brief Sets the key of this node.
          *
          * @param key the key to be set
          */
         void setKey (const KeyType& key) { key_ = key; }

         /**
          * \brief Sets the data of this node.
          *
          * @param val the value to be set
          */
         void setData (const DataType& val) { val_ = val; }

         /**
          * \brief Gets the key of this node.
          *
          * @return the key of this node 
          */
         KeyType getKey () const { return key_; }

         /**
          * \brief Gets the data of this node.
          *
          * @return the value of this node 
          */
         DataType getData () const { return val_; }

         /**
          * \brief Gets the i'th of this node.
          *
          * @param idx the index of the child node
          * @return the child node
          */
         SimpleTreeNodeSptr getChild (unsigned idx) const { return children_[idx]; }

         /**
          * \brief Gets the number of children of this node.
          *
          * @return the number of children
          */
         unsigned numChildren () const { return children_.size(); }

         /**
          * \brief Indicates whether this node is root.
          *
          * @return 'true' if this node is root, 'false' otherwise
          */
         bool isRoot () const { return isRoot_; }

         /**
          * \brief Indicates whether this node is leaf node.
          *
          * @return 'true' if this node is leaf node, 'false' otherwise
          */
         bool isLeaf () const { return !numChildren(); }

      private:
         bool isRoot_;                                        // Flag indicates if the node is root
         KeyType key_;                                        // Key of this node
         DataType val_;                                        // Value of this node
         vector< SimpleTreeNodeSptr > children_;    // List of children of this node
   };
}

#endif // __SIMPLE_TREE_H__
