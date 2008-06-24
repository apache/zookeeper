/*
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

package org.apache.zookeeper.server;

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

/**
 * This class contains the data for a node in the data tree.
 * <p>
 * A data node contains a reference to its parent, a byte array as its data, an
 * array of ACLs, a stat object, and a set of its children's paths.
 * 
 */
public class DataNode implements Record {
    DataNode() {
    }

    DataNode(DataNode parent, byte data[], List<ACL> acl, Stat stat) {
        this.parent = parent;
        this.data = data;
        this.acl = acl;
        this.stat = stat;
        this.children = new HashSet<String>();
    }

    DataNode parent;

    byte data[];

    List<ACL> acl;

    public Stat stat;

    HashSet<String> children = new HashSet<String>();

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
        stat = new Stat();
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
