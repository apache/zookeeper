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

package org.apache.zookeeper.server;

import org.apache.zookeeper.jmx.ZKMBeanInfo;

/**
 * This class implements the data tree MBean.
 */
public class DataTreeBean implements DataTreeMXBean, ZKMBeanInfo {

    DataTree dataTree;

    public DataTreeBean(org.apache.zookeeper.server.DataTree dataTree) {
        this.dataTree = dataTree;
    }

    public int getNodeCount() {
        return dataTree.getNodeCount();
    }

    public long approximateDataSize() {
        return dataTree.cachedApproximateDataSize();
    }

    public int countEphemerals() {
        return dataTree.getEphemeralsCount();
    }

    public int getWatchCount() {
        return dataTree.getWatchCount();
    }

    public String getName() {
        return "InMemoryDataTree";
    }

    public boolean isHidden() {
        return false;
    }

    public String getLastZxid() {
        return "0x" + Long.toHexString(dataTree.lastProcessedZxid);
    }

}
