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

package org.apache.zookeeper.server;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A Manager Class for TTL node, making add/remove/update ttl thread-safe
 * ranking by expireTime/mtime asc for the performance.
 */
public class TTLManager {

    private final Map<String, TTLNode> ttlMap = new HashMap<>();

    /**
     * This set contains the paths of all ttl nodes, ranking by expireTime asc.
     * this is only an internally used object in the DataTree.
     */
    private final Set<TTLNode> ttls = new TreeSet<>(
            Comparator.comparingLong(TTLNode::getExpireTime)
                    .thenComparing(TTLNode::getPath)
    );

    synchronized void addTTL(String path, DataNode node) {
        TTLNode ttlNode = new TTLNode(path, node.stat.getMtime() + EphemeralType.TTL.getValue(node.stat.getEphemeralOwner()));
        ttls.add(ttlNode);
        ttlMap.put(path, ttlNode);
    }

    synchronized void removeTTL(String path) {
        TTLNode ttlNode = ttlMap.remove(path);
        if (ttlNode != null) {
            ttls.remove(ttlNode);
        }
    }

    synchronized void updateTTL(String path, DataNode node) {
        TTLNode ttlNode = ttlMap.remove(path);
        if (ttlNode != null) {
            ttls.remove(ttlNode);
        }
        ttlNode = new TTLNode(path, node.stat.getMtime() + EphemeralType.TTL.getValue(node.stat.getEphemeralOwner()));
        ttlMap.put(path, ttlNode);
        ttls.add(ttlNode);
    }

    synchronized Set<TTLNode> getTTLs() {
        return ttls;
    }

    /**
     * A encapsultaing class for TTLNode
     */
    static class TTLNode {

        private final String path;

        private final long expireTime;

        public TTLNode(String path, long expireTime) {
            this.path = path;
            this.expireTime = expireTime;
        }

        public String getPath() {
            return path;
        }

        public long getExpireTime() {
            return expireTime;
        }

        @Override
        public int hashCode() {
            return path.hashCode();
        }

        @Override
        public String toString() {
            return "TTLNode{"
                    + "path='" + path + '\''
                    + ", expireTime=" + expireTime
                    + '}';
        }
    }
}
