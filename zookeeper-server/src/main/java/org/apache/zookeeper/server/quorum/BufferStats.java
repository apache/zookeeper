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

package org.apache.zookeeper.server.quorum;

/**
 * Provides live statistics about Jute buffer usage in term of proposal and client request size.
 */
public class BufferStats {

    public static final int INIT_VALUE = -1;

    /**
     * Size of the last buffer usage.
     */
    private int lastBufferSize = INIT_VALUE;

    /**
     * Size of the smallest buffer usage.
     */
    private int minBufferSize = INIT_VALUE;

    /**
     * Size of the largest buffer usage.
     */
    private int maxBufferSize = INIT_VALUE;

    /**
     * Size of the last buffer usage.
     */
    public synchronized int getLastBufferSize() {
        return lastBufferSize;
    }

    /**
     * Updates statistics by setting the last buffer usage size.
     */
    public synchronized void setLastBufferSize(int value) {
        lastBufferSize = value;
        if (minBufferSize == INIT_VALUE || value < minBufferSize) {
            minBufferSize = value;
        }
        if (value > maxBufferSize) {
            maxBufferSize = value;
        }
    }

    /**
     * Size of the smallest buffer usage.
     */
    public synchronized int getMinBufferSize() {
        return minBufferSize;
    }

    /**
     * Size of the largest buffer usage.
     */
    public synchronized int getMaxBufferSize() {
        return maxBufferSize;
    }

    /**
     * Reset statistics.
     */
    public synchronized void reset() {
        lastBufferSize = INIT_VALUE;
        minBufferSize = INIT_VALUE;
        maxBufferSize = INIT_VALUE;
    }

    @Override
    public synchronized String toString() {
        return String.format("%d/%d/%d", lastBufferSize, minBufferSize, maxBufferSize);
    }

}
