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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class NodesCache {

    public static final int CACHE_SIZE = 40000;

    public static final int EXPIRATION_TIME = 100;

    private final LoadingCache<String, List<String>> nodes;

    private ZooKeeper zooKeeper;

    public NodesCache(ZooKeeper zooKeeper) {
        this.zooKeeper = zooKeeper;
        this.nodes = CacheBuilder.newBuilder()
                .maximumSize(CACHE_SIZE)
                .expireAfterWrite(EXPIRATION_TIME, TimeUnit.MILLISECONDS)
                .build(
                        new CacheLoader<String, List<String>>() {
                            @Override
                            public List<String> load(String nodePath) throws Exception {
                                return getChildren(nodePath);
                            }
                        }
                );
    }

    public List<String> getChildren(String nodePath) {
        try {
            Stat s = zooKeeper.exists(nodePath, false);
            if (s != null) {
                List<String> children = this.zooKeeper.getChildren(nodePath, false);
                Collections.sort(children);
                return children;
            }
        } catch (Exception e) {
            LoggerFactory.getLogger().error(
                    "Error occurred retrieving child of node: " + nodePath, e
            );
        }
        return null;
    }

    public String getNodeChild(String nodePath, int index) {
        List<String> childNodes = null;
        try {
            childNodes = nodes.get(nodePath);
            return childNodes.get(index);
        } catch (ExecutionException e) {
            LoggerFactory.getLogger().error(
                    "Error occurred retrieving child " + index + "of node: " + nodePath, e
            );
        }
        return null;
    }

}
