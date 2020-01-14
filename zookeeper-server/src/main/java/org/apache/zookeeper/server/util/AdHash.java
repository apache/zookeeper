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

package org.apache.zookeeper.server.util;

/**
 * This incremental hash is used to keep track of the hash of
 * the data tree to that we can quickly validate that things
 * are in sync.
 *
 * See the excellent paper: A New Paradigm for collision-free hashing:
 *   Incrementality at reduced cost,  M. Bellare and D. Micciancio
 */
public class AdHash {

    /* we use 64 bits so that we can be fast an efficient */
    private volatile long hash;

    /**
     * Add new digest to the hash value maintained in this class.
     *
     * @param digest the value to add on
     * @return the AdHash itself for chained operations
     */
    public AdHash addDigest(long digest) {
        hash += digest;
        return this;
    }

    /**
     * Remove the digest from the hash value.
     *
     * @param digest the value to remove
     * @return the AdHash itself for chained operations
     */
    public AdHash removeDigest(long digest) {
        hash -= digest;
        return this;
    }

    /**
     * Return the long value of the hash.
     */
    public long getHash() {
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AdHash && ((AdHash) other).hash == this.hash;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(hash);
    }

    @Override
    public String toString() {
        return Long.toHexString(hash);
    }

    public void clear() {
        hash = 0;
    }
}
