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
package org.apache.zookeeper.server.quorum;

/**
 * Interface that defines a strategy for backing off arbitrary retry attempts.  Each successive call to
 * {@link BackoffStrategy#nextWaitMillis()} returns the amount of time to wait or {@link BackoffStrategy#STOP} if no
 * further waiting is supported.
 */
public interface BackoffStrategy {

    long STOP = -1L;

    /**
     * Get the number of milliseconds to wait before retrying the operation,
     * or {@code BackoffStrategy.STOP} if no more retries should be made.
     * @return the number of milliseconds to wait before retrying the operation.
     * @throws IllegalStateException
     */
    long nextWaitMillis() throws IllegalStateException;

    /**
     * Reset to it's initial state.
     */
    void reset();
}
