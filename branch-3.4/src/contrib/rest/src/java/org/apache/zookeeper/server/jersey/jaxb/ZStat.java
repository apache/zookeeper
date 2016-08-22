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

package org.apache.zookeeper.server.jersey.jaxb;

import javax.xml.bind.annotation.XmlRootElement;


/**
 * Represents a STAT using JAXB.
 */
@XmlRootElement(name="stat")
public class ZStat {
    public String path;
    public String uri;
    public byte[] data64;
    public String dataUtf8;

    public long czxid;
    public long mzxid;
    public long ctime;
    public long mtime;
    public int version;
    public int cversion;
    public int aversion;
    public long ephemeralOwner;
    public int dataLength;
    public int numChildren;
    public long pzxid;


    public ZStat(){
        // needed by jersey
    }

    public ZStat(String path, byte[] data64, String dataUtf8)
    {
        this.path = path;
        this.data64 = data64;
        this.dataUtf8 = dataUtf8;
    }

    public ZStat(String path, String uri, byte[] data64, String dataUtf8,
            long czxid, long mzxid, long ctime, long mtime, int version,
            int cversion, int aversion, long ephemeralOwner, int dataLength,
            int numChildren, long pzxid)
    {
        this.path = path;
        this.uri = uri;
        this.data64 = data64;
        this.dataUtf8 = dataUtf8;

        this.czxid = czxid;
        this.mzxid = mzxid;
        this.ctime = ctime;
        this.mtime = mtime;
        this.version = version;
        this.cversion = cversion;
        this.aversion = aversion;
        this.ephemeralOwner = ephemeralOwner;
        this.dataLength = dataLength;
        this.numChildren = numChildren;
        this.pzxid = pzxid;
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    /**
     * This method considers two ZStats equal if their path, encoding, and
     * data match. It does not compare the ZooKeeper
     * org.apache.zookeeper.data.Stat class fields.
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ZStat)) {
            return false;
        }
        ZStat o = (ZStat) obj;
        return toString().equals(o.toString());
    }

    @Override
    public String toString() {
        return "ZStat(" + path + "," + "b64["
            + (data64 == null ? null : new String(data64)) + "],"
            + dataUtf8 + ")";
    }
}
