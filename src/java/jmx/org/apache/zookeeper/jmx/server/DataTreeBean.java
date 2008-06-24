/**
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.jmx.server;

import java.io.ByteArrayOutputStream;

import org.apache.log4j.Logger;

import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.jmx.ZKMBeanInfo;

/**
 * This class implements the data tree MBean.
 */
public class DataTreeBean implements DataTreeMXBean, ZKMBeanInfo {
    private static final Logger LOG = Logger.getLogger(DataTreeBean.class);

    org.apache.zookeeper.server.DataTree dataTree;
    
    public DataTreeBean(){
    }

    public DataTreeBean(org.apache.zookeeper.server.DataTree dataTree){
        this.dataTree=dataTree;
    }
    
    public int getNodeCount() {
        return dataTree.getNodeCount();
    }

    /* (non-Javadoc)
     * @see org.apache.zookeeper.jmx.server.DataTreeMBean#getDataSize()
     */
    //TODO: it's useful info but can be expensive to get
    public long getDataSize() {
      /*  We need a more efficient way to do this
        ByteArrayOutputStream stream=new ByteArrayOutputStream();
        BinaryOutputArchive oa = BinaryOutputArchive.getArchive(stream);
        try {
            dataTree.serialize(oa, "tree");
        } catch (Exception e) {
            LOG.warn("Failed to get data tree size: "+e.getMessage());            
        }
        return stream.size();
      */
        return -1;
    }

    /* (non-Javadoc)
     * @see org.apache.zookeeper.jmx.server.DataTreeMBean#getEphemeralCount()
     */
    public int getEphemeralCount() {
        return dataTree.getSessions().size();
    }

    /* (non-Javadoc)
     * @see org.apache.zookeeper.jmx.server.DataTreeMBean#getWatchCount()
     */
    public int getWatchCount() {
        return dataTree.getWatchCount();
    }

    public String getName() {
        return "InMemoryDataTree";
    }

    public boolean isHidden() {
        return false;
    }

    public long getLastZxid() {
        return dataTree.lastProcessedZxid;
    }

}
