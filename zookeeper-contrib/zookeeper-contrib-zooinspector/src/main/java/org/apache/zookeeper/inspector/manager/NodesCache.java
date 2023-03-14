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
package org.apache.zookeeper.inspector.manager;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.inspector.logger.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A local cache of ZNodes in front of the Zookeeper server(s) to help cut down on network calls.  This class will
 * return results from the cache first, if available, and then fetch results remotely from Zookeeper if not.
 */
public class NodesCache {

    public static final int CACHE_SIZE = 40000;

    public static final int ENTRY_EXPIRATION_TIME_MILLIS = 10000;

    private final LoadingCache<String, List<String>> nodes;

    private ZooKeeper zooKeeper;

    public NodesCache(ZooKeeper zooKeeper) {
        this.zooKeeper = zooKeeper;
        this.nodes = CacheBuilder.newBuilder()
                .maximumSize(CACHE_SIZE)
                .expireAfterWrite(ENTRY_EXPIRATION_TIME_MILLIS, TimeUnit.MILLISECONDS)
                .build(new CacheLoader<String, List<String>>() {
                           @Override
                           public List<String> load(String nodePath) {
                               return getChildrenRemote(nodePath);
                           }
                       }
                );
    }

    /**
     * Whereas getChildren hits the cache first, getChildrenRemote goes straight (over the network) to Zookeeper to
     * get the answer.  This is the function used by the cache to insert entries that are requested, but don't exist
     * in the cache yet.
     *
     * @param nodePath The full path to the parent whose children are to be fetched.
     * @return The list of children of the given node as a list of full ZNode path strings or null if an
     * error occurred.
     */
    private List<String> getChildrenRemote(String nodePath) {
        try {
            Stat s = zooKeeper.exists(nodePath, false);
            if (s != null) {
                List<String> children = this.zooKeeper.getChildren(nodePath, false);
                Collections.sort(children);
                return children;
            }
        } catch (Exception e) {
            LoggerFactory.getLogger().error(
                    "Error occurred retrieving children of node: " + nodePath, e
            );
        }
        return null;
    }

    /**
     * Fetch the children of the given node from the cache.
     *
     * @param nodePath The full path to the parent whose children are to be fetched.
     * @return The list of children of the given node as a list of full ZNode path strings or null if an
     * error occurred.
     */
    public List<String> getChildren(String nodePath) {
        try {
            return nodes.get(nodePath);
        } catch (Exception e) {
            LoggerFactory.getLogger().error("Error occurred retrieving children of node: " + nodePath, e);
        }
        return null;
    }
}
