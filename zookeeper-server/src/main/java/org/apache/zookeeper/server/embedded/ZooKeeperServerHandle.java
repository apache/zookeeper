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

package org.apache.zookeeper.server.embedded;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/**
 * This API allows you to start a ZooKeeper server node from Java code <p>
 * The server will run inside the same process.<p>
 * Typical use-cases are:
 * <ul>
 * <li>Running automated tests</li>
 * <li>Launch ZooKeeper server with a Java based service management system</li>
 * </ul>
 * <p>
 * Please take into consideration that in production usually it is better to not run the client
 * together with the server in order to avoid race conditions, especially around how ephemeral nodes work.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public interface ZooKeeperServerHandle extends AutoCloseable {
    /**
     * Joins with the server {@link Thread}.
     */
    void join() throws InterruptedException;
}
