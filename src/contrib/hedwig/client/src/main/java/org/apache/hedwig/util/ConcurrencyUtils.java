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
package org.apache.hedwig.util;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CyclicBarrier;

public class ConcurrencyUtils {

    public static <T, U extends T, V extends BlockingQueue<T>> void put(V queue, U value) {
        try {
            queue.put(value);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static <T> T take(BlockingQueue<T> queue) {
        try {
            return queue.take();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

}
