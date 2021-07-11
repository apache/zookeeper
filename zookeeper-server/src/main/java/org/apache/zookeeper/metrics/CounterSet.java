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

package org.apache.zookeeper.metrics;

/**
 * A counter refers to a value which can only increase.
 * Usually the value is reset when the process starts.
 *
 *  A CounterSet is a set of {@link Counter} grouped by keys.
 */

public interface CounterSet {
    /**
     * Increment the value by one for the given key
     * <p>This method is thread safe, The MetricsProvider will take care of synchronization.</p>
     *
     * @param key the key to increment the count
     */
    default void inc(String key) {
        add(key, 1L);
    }

    /**
     * Increment the value by a given amount for the given key
     * <p>This method is thread safe, The MetricsProvider will take care of synchronization.</p>
     *
     * @param key the key to increment the count for the given key
     * @param delta amount to increment, this cannot be a negative number.
     */
    void add(String key, long delta);
}
