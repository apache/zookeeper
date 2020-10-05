 /*
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

 package org.apache.zookeeper.common;

 import java.util.ArrayDeque;
 import java.util.Collection;
 import java.util.Deque;
 import java.util.HashMap;
 import java.util.Map;
 import java.util.Objects;
 import java.util.concurrent.locks.Lock;
 import java.util.concurrent.locks.ReadWriteLock;
 import java.util.concurrent.locks.ReentrantReadWriteLock;
 import java.util.stream.Stream;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;

 /**
  * a class that implements prefix matching for
  * components of a filesystem path. the trie
  * looks like a tree with edges mapping to
  * the component of a path.
  * example /ab/bc/cf would map to a trie
  *           /
  *        ab/
  *        (ab)
  *      bc/
  *       /
  *      (bc)
  *   cf/
  *   (cf)
  */
 public class PathTrie {

     /** Logger for this class */
     private static final Logger LOG = LoggerFactory.getLogger(PathTrie.class);

     /** Root node of PathTrie */
     private final TrieNode rootNode;

     private final ReadWriteLock lock = new ReentrantReadWriteLock(true);

     private final Lock readLock = lock.readLock();

     private final Lock writeLock = lock.writeLock();

     static class TrieNode {

         final String value;
         final Map<String, TrieNode> children;
         boolean property;
         TrieNode parent;

         /**
          * Create a trie node with parent as parameter.
          *
          * @param parent the parent of this node
          * @param value the value stored in this node
          */
         private TrieNode(TrieNode parent, String value) {
             this.value = value;
             this.parent = parent;
             this.property = false;
             this.children = new HashMap<>(4);
         }

         /**
          * Get the parent of this node.
          *
          * @return the parent node
          */
         TrieNode getParent() {
             return this.parent;
         }

         /**
          * set the parent of this node.
          *
          * @param parent the parent to set to
          */
         void setParent(TrieNode parent) {
             this.parent = parent;
         }

         /**
          * A property that is set for a node - making it special.
          */
         void setProperty(boolean prop) {
             this.property = prop;
         }

         /**
          * The property of this node.
          *
          * @return the property for this node
          */
         boolean hasProperty() {
             return this.property;
         }

         /**
          * The value stored in this node.
          *
          * @return the value stored in this node
          */
         public String getValue() {
             return this.value;
         }

         /**
          * Add a child to the existing node.
          *
          * @param childName the string name of the child
          * @param node the node that is the child
          */
         void addChild(String childName, TrieNode node) {
             this.children.putIfAbsent(childName, node);
         }

         /**
          * Delete child from this node.
          *
          * @param childName the name of the child to be deleted
          */
         void deleteChild(String childName) {
             this.children.computeIfPresent(childName, (key, childNode) -> {
                 // Node no longer has an external property associated
                 childNode.setProperty(false);

                 // Delete it if it has no children (is a leaf node)
                 if (childNode.isLeafNode()) {
                     childNode.setParent(null);
                     return null;
                 }

                 return childNode;
             });
         }

         /**
          * Return the child of a node mapping to the input child name.
          *
          * @param childName the name of the child
          * @return the child of a node
          */
         TrieNode getChild(String childName) {
             return this.children.get(childName);
         }

         /**
          * Get the list of children of this trienode.
          *
          * @return A collection containing the node's children
          */
         Collection<String> getChildren() {
             return children.keySet();
         }

         /**
          * Determine if this node is a leaf (has no children).
          *
          * @return true if this node is a lead node; otherwise false
          */
         boolean isLeafNode() {
             return children.isEmpty();
         }

         @Override
         public String toString() {
             return "TrieNode [name=" + value + ", property=" + property + ", children=" + children.keySet() + "]";
         }

     }

     /**
      * Construct a new PathTrie with a root node.
      */
     public PathTrie() {
         this.rootNode = new TrieNode(null, "/");
     }

     /**
      * Add a path to the path trie. All paths are relative to the root node.
      *
      * @param path the path to add to the trie
      */
     public void addPath(final String path) {
         Objects.requireNonNull(path, "Path cannot be null");

         if (path.length() == 0) {
             throw new IllegalArgumentException("Invalid path: " + path);
         }
         final String[] pathComponents = split(path);

         writeLock.lock();
         try {
             TrieNode parent = rootNode;
             for (final String part : pathComponents) {
                 TrieNode child = parent.getChild(part);
                 if (child == null) {
                     child = new TrieNode(parent, part);
                     parent.addChild(part, child);
                 }
                 parent = child;
             }
             parent.setProperty(true);
         } finally {
             writeLock.unlock();
         }
     }

     /**
      * Delete a path from the trie. All paths are relative to the root node.
      *
      * @param path the path to be deleted
      */
     public void deletePath(final String path) {
         Objects.requireNonNull(path, "Path cannot be null");

         if (path.length() == 0) {
             throw new IllegalArgumentException("Invalid path: " + path);
         }
         final String[] pathComponents = split(path);


         writeLock.lock();
         try {
             TrieNode parent = rootNode;
             for (final String part : pathComponents) {
                 if (parent.getChild(part) == null) {
                     // the path does not exist
                     return;
                 }
                 parent = parent.getChild(part);
                 LOG.debug("{}", parent);
             }

             final TrieNode realParent = parent.getParent();
             realParent.deleteChild(parent.getValue());
         } finally {
             writeLock.unlock();
         }
     }

     /**
      * Return true if the given path exists in the trie, otherwise return false;
      * All paths are relative to the root node.
      *
      * @param path the input path
      * @return the largest prefix for the
      */
     public boolean existsNode(final String path) {
         Objects.requireNonNull(path, "Path cannot be null");

         if (path.length() == 0) {
             throw new IllegalArgumentException("Invalid path: " + path);
         }
         final String[] pathComponents = split(path);

         readLock.lock();
         try {
             TrieNode parent = rootNode;
             for (final String part : pathComponents) {
                 if (parent.getChild(part) == null) {
                     // the path does not exist
                     return false;
                 }
                 parent = parent.getChild(part);
                 LOG.debug("{}", parent);
             }
         } finally {
             readLock.unlock();
         }
         return true;
     }

     /**
      * Return the largest prefix for the input path. All paths are relative to the
      * root node.
      *
      * @param path the input path
      * @return the largest prefix for the input path
      */
     public String findMaxPrefix(final String path) {
         Objects.requireNonNull(path, "Path cannot be null");

         final String[] pathComponents = split(path);

         readLock.lock();
         try {
             TrieNode parent = rootNode;
             TrieNode deepestPropertyNode = null;
             for (final String element : pathComponents) {
                 parent = parent.getChild(element);
                 if (parent == null) {
                     LOG.debug("{}", element);
                     break;
                 }
                 if (parent.hasProperty()) {
                     deepestPropertyNode = parent;
                 }
             }

             if (deepestPropertyNode == null) {
                 return "/";
             }

             final Deque<String> treePath = new ArrayDeque<>();
             TrieNode node = deepestPropertyNode;
             while (node != this.rootNode) {
                 treePath.offerFirst(node.getValue());
                 node = node.parent;
             }
             return "/" + String.join("/", treePath);
         } finally {
             readLock.unlock();
         }
     }

     /**
      * Clear all nodes in the trie.
      */
     public void clear() {
         writeLock.lock();
         try {
             rootNode.getChildren().clear();
         } finally {
             writeLock.unlock();
         }
     }

     private static String[] split(final String path){
         return Stream.of(path.split("/"))
                 .filter(t -> !t.trim().isEmpty())
                 .toArray(String[]::new);
     }

 }
