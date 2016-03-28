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

package org.apache.zookeeper.server.upgrade;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.jute.Index;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.data.StatPersistedV1;

/**
 * This class contains the data for a node in the data tree.
 * <p>
 * A data node contains a reference to its parent, a byte array as its data, an
 * array of ACLs, a stat object, and a set of its children's paths.
 * 
 */
public class DataNodeV1 implements Record {
    DataNodeV1() {
        // default rather than public constructor
    }

    DataNodeV1(DataNodeV1 parent, byte data[], List<ACL> acl, StatPersistedV1 stat) {
        this.parent = parent;
        this.data = data;
        this.acl = acl;
        this.stat = stat;
        this.children = new HashSet<String>();
    }
    

    /**
     * convenience method for creating DataNode
     * fully
     * @param children
     */
    public void setChildren(HashSet<String> children) {
        this.children = children;
    }
    
    /**
     * convenience methods to get the children
     * @return the children of this datanode
     */
    public HashSet<String> getChildren() {
        return this.children;
    }
    
    DataNodeV1 parent;

    byte data[];

    public List<ACL> acl;

    public StatPersistedV1 stat;

    HashSet<String> children = new HashSet<String>();

    public void copyStat(Stat to) {
        to.setAversion(stat.getAversion());
        to.setCtime(stat.getCtime());
        to.setCversion(stat.getCversion());
        to.setCzxid(stat.getCzxid());
        to.setMtime(stat.getMtime());
        to.setMzxid(stat.getMzxid());
        to.setVersion(stat.getVersion());
        to.setEphemeralOwner(stat.getEphemeralOwner());
        to.setDataLength(data.length);
        to.setNumChildren(children.size());
    }

    public void deserialize(InputArchive archive, String tag)
            throws IOException {
        archive.startRecord("node");
        data = archive.readBuffer("data");
        Index i = archive.startVector("acl");
        if (i != null) {
            acl = new ArrayList<ACL>();
            while (!i.done()) {
                ACL a = new ACL();
                a.deserialize(archive, "aclEntry");
                acl.add(a);
                i.incr();
            }
        }
        archive.endVector("acl");
        stat = new StatPersistedV1();
        stat.deserialize(archive, "stat");
        archive.endRecord("node");
    }

    synchronized public void serialize(OutputArchive archive, String tag)
            throws IOException {
        archive.startRecord(this, "node");
        archive.writeBuffer(data, "data");
        archive.startVector(acl, "acl");
        if (acl != null) {
            for (ACL a : acl) {
                a.serialize(archive, "aclEntry");
            }
        }
        archive.endVector(acl, "acl");
        stat.serialize(archive, "stat");
        archive.endRecord(this, "node");
    }
}
