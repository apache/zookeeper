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

package org.apache.zookeeper.server;

import java.util.Map;
import java.util.Set;

/**
 * The interface defined to manage the hash based on the entries in the 
 * nodes map.
 */
public interface NodeHashMap {

    /**
     * Add the node into the map and update the digest with the new node.
     *
     * @param path the path of the node
     * @param node the actual node associated with this path
     */
    public DataNode put(String path, DataNode node);

    /**
     * Add the node into the map without update the digest.
     *
     * @param path the path of the node
     * @param node the actual node associated with this path
     */
    public DataNode putWithoutDigest(String path, DataNode node);

    /**
     * Return the data node associated with the path.
     *
     * @param path the path to read from 
     */
    public DataNode get(String path);

    /**
     * Remove the path from the internal nodes map.
     *
     * @param path the path to remove
     * @return the node being removed
     */
    public DataNode remove(String path);

    /**
     * Return all the entries inside this map.
     */
    public Set<Map.Entry<String, DataNode>> entrySet();

    /**
     * Clear all the items stored inside this map.
     */
    public void clear();

    /**
     * Return the size of the nodes stored in this map.
     */
    public int size();

    /**
     * Called before we made the change on the node, which will clear
     * the digest associated with it.
     *
     * @param path the path being changed
     * @param node the node associated with the path
     */
    public void preChange(String path, DataNode node);

    /**
     * Called after making the changes on the node, which will update
     * the digest.
     *
     * @param path the path being changed
     * @param node the node associated with the path
     */
    public void postChange(String path, DataNode node);

    /**
     * Return the digest value.
     */
    public long getDigest();
}
