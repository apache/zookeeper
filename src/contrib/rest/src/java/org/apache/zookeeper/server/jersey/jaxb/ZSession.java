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

@XmlRootElement(name="session")
public class ZSession {
    public String id;
    public String uri;
    
    public ZSession() {
        // needed by jersey
    }
    
    public ZSession(String id, String uri) {
        this.id = id;
        this.uri = uri;
    }
    
    @Override
    public int hashCode() {
        return id.hashCode();
    }
    
    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof ZSession)) {
            return false;
        }
        ZSession s = (ZSession) obj;
        return id.equals(s.id);
    }
    
    @Override
    public String toString() {
        return "ZSession(" + id +")";   
    }
}
