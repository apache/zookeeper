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
 * Represents a PATH using JAXB.
 */
@XmlRootElement(name="path")
public class ZPath {
    public String path;
    public String uri;

    public ZPath(){
        // needed by jersey
    }

    public ZPath(String path) {
        this(path, null);
    }

    public ZPath(String path, String uri) {
        this.path = path;
        this.uri = uri;
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ZPath)) {
            return false;
        }
        ZPath o = (ZPath) obj;
        return path.equals(o.path);
    }

    @Override
    public String toString() {
        return "ZPath(" + path + ")";
    }
}
