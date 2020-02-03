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
 */
public interface Counter {

    /**
     * Increment the value by one.
     * <p>This method is thread safe, The MetricsProvider will take care of synchronization.</p>
     */
    default void inc() {
        add(1);
    }

    /**
     * Increment the value by a given amount.
     * <p>This method is thread safe, The MetricsProvider will take care of synchronization.</p>
     *
     * @param delta amount to increment, this cannot be a negative number.
     */
    void add(long delta);

    /**
     * Get the current value held by the counter.
     * <p>This method is thread safe, The MetricsProvider will take care of synchronization.</p>
     *
     * @return the current value
     */
    long get();

}
