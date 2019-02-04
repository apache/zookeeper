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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.zookeeper.data.Stat;

@SuppressWarnings("serial")
public class ResponseCache {
    // Magic number chosen to be "big enough but not too big"
    private static final int DEFAULT_RESPONSE_CACHE_SIZE = 400;

    private static class Entry {
        public Stat stat;
        public byte[] data;
    }

    private Map<String, Entry> cache = Collections.synchronizedMap(
        new LRUCache<String, Entry>(getResponseCacheSize()));

    public ResponseCache() {
    }

    public void put(String path, byte[] data, Stat stat) {
        Entry entry = new Entry();
        entry.data = data;
        entry.stat = stat;
        cache.put(path, entry);
    }

    public byte[] get(String key, Stat stat) {
        Entry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (!stat.equals(entry.stat)) {
            // The node has been modified, invalidate cache.
            cache.remove(key);
            return null;
        } else {
            return entry.data;
        }
    }

    private static int getResponseCacheSize() {
        return Integer.getInteger("zookeeper.maxResponseCacheSize", DEFAULT_RESPONSE_CACHE_SIZE);
    }

    public static boolean isEnabled() {
        return getResponseCacheSize() > 0;
    }

    private static class LRUCache<K, V> extends LinkedHashMap<K, V> {
        private int cacheSize;

        LRUCache(int cacheSize) {
            super(cacheSize/4);
            this.cacheSize = cacheSize;
        }

        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() >= cacheSize;
        }
    }
}
