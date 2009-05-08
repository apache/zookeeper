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

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;


/**
 * Represents the CHILD using JAXB.
 * Special JSON version is required to get proper formatting in both
 * JSON and XML output. See details in ZNodeResource.
 */
@XmlRootElement(name="child")
public class ZChildren {
    public String path;
    public String uri;

    public String child_uri_template;
    @XmlElementWrapper(name="children")
    @XmlElement(name="child")
    public List<String> children;

    public ZChildren() {
        // needed by jersey
        children = new ArrayList<String>();
    }

    public ZChildren(String path, String uri, String child_uri_template,
            List<String> children)
    {
        this.path = path;
        this.uri = uri;
        this.child_uri_template = child_uri_template;
        if (children != null) {
            this.children = children;
        } else {
            this.children = new ArrayList<String>();
        }
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ZChildren)) {
            return false;
        }
        ZChildren o = (ZChildren) obj;
        return path.equals(o.path) && children.equals(o.children);
    }

    @Override
    public String toString() {
        return "ZChildren(" + path + "," + children + ")";
    }
}
