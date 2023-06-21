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

package org.apache.zookeeper.common;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public interface BatchedBlockingQueue<T> extends BlockingQueue<T> {
    void putAll(T[] a, int offset, int len) throws InterruptedException;

    /**
     * Drain the queue into an array.
     * Wait if there are no items in the queue.
     *
     * @param array
     * @return
     * @throws InterruptedException
     */
    int takeAll(T[] array) throws InterruptedException;

    /**
     * Removes multiple items from the queue.
     *
     * The method returns when either:
     *  1. At least one item is available
     *  2. The timeout expires
     *
     *
     * @param array
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     */
    int pollAll(T[] array, long timeout, TimeUnit unit) throws InterruptedException;

}