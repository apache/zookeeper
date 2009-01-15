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

/**
 * Zookeeper data tree MBean.
 */
public interface DataTreeMXBean {
    /**
     * @return number of znodes in the data tree.
     */
    public int getNodeCount();
    /**
     * @return the most recent zxid processed by the data tree.
     */
    public String getLastZxid();
    /**
     * @return number of watches set.
     */
    public int getWatchCount();
    
    /**
     * @return data tree size in bytes. The size includes the znode path and 
     * its value.
     */
    public long approximateDataSize();
    /**
     * @return number of ephemeral nodes in the data tree
     */
    public int countEphemerals();
}
