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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.util.AdHash;

/**
 * a simple wrapper to ConcurrentHashMap that recalculates a digest after
 * each mutation.
 */
public class NodeHashMapImpl implements NodeHashMap {

    private final ConcurrentHashMap<String, DataNode> nodes;
    private final boolean digestEnabled;
    private final DigestCalculator digestCalculator;

    private final AdHash hash;

    public NodeHashMapImpl(DigestCalculator digestCalculator) {
        this.digestCalculator = digestCalculator;
        nodes = new ConcurrentHashMap<>();
        hash = new AdHash();
        digestEnabled = ZooKeeperServer.isDigestEnabled();
    }

    @Override
    public DataNode put(String path, DataNode node) {
        DataNode oldNode = nodes.put(path, node);
        addDigest(path, node);
        if (oldNode != null) {
            removeDigest(path, oldNode);
        }
        return oldNode;
    }

    @Override
    public DataNode putWithoutDigest(String path, DataNode node) {
        return nodes.put(path, node);
    }

    @Override
    public DataNode get(String path) {
        return nodes.get(path);
    }

    @Override
    public DataNode remove(String path) {
        DataNode oldNode = nodes.remove(path);
        if (oldNode != null) {
            removeDigest(path, oldNode);
        }
        return oldNode;
    }

    @Override
    public Set<Map.Entry<String, DataNode>> entrySet() {
        return nodes.entrySet();
    }

    @Override
    public void clear() {
        nodes.clear();
        hash.clear();
    }

    @Override
    public int size() {
        return nodes.size();
    }

    @Override
    public void preChange(String path, DataNode node) {
        removeDigest(path, node);
    }

    @Override
    public void postChange(String path, DataNode node) {
        // we just made a change, so make sure the digest is
        // invalidated
        node.digestCached = false;
        addDigest(path, node);
    }

    private void addDigest(String path, DataNode node) {
        // Excluding everything under '/zookeeper/' for digest calculation.
        if (path.startsWith(ZooDefs.ZOOKEEPER_NODE_SUBTREE)) {
            return;
        }
        if (digestEnabled) {
            hash.addDigest(digestCalculator.calculateDigest(path, node));
        }
    }

    private void removeDigest(String path, DataNode node) {
        // Excluding everything under '/zookeeper/' for digest calculation.
        if (path.startsWith(ZooDefs.ZOOKEEPER_NODE_SUBTREE)) {
            return;
        }
        if (digestEnabled) {
            hash.removeDigest(digestCalculator.calculateDigest(path, node));
        }
    }

    @Override
    public long getDigest() {
        return hash.getHash();
    }

}
