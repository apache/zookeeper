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
package org.apache.hedwig.server.persistence;

import java.util.Collection;
import java.util.Map;

public class MapMethods {

    public static <K, V> V getAfterInsertingIfAbsent(Map<K, V> map, K key, Factory<V> valueFactory) {
        V value = map.get(key);

        if (value == null) {
            value = valueFactory.newInstance();
            map.put(key, value);
        }

        return value;
    }

    public static <K, V, Z extends Collection<V>> void addToMultiMap(Map<K, Z> map, K key, V value,
            Factory<Z> valueFactory) {
        Collection<V> collection = getAfterInsertingIfAbsent(map, key, valueFactory);

        collection.add(value);

    }

    public static <K, V, Z extends Collection<V>> boolean removeFromMultiMap(Map<K, Z> map, K key, V value) {
        Collection<V> collection = map.get(key);

        if (collection == null) {
            return false;
        }

        if (!collection.remove(value)) {
            return false;
        } else {
            if (collection.isEmpty()) {
                map.remove(key);
            }
            return true;
        }

    }

}
