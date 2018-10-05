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

package org.apache.zookeeper.server.util;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;
import java.util.Map.Entry;

import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;


public class ConfigUtils {
    static public String getClientConfigStr(String configData) {
        Properties props = new Properties();        
        try {
          props.load(new StringReader(configData));
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
        StringBuffer sb = new StringBuffer();
        boolean first = true;
        String version = "";
        for (Entry<Object, Object> entry : props.entrySet()) {
             String key = entry.getKey().toString().trim();
             String value = entry.getValue().toString().trim();
             if (key.equals("version")) version = value;
             if (!key.startsWith("server.")) continue;           
             QuorumPeer.QuorumServer qs;
             try {
               qs = new QuorumPeer.QuorumServer(-1, value);
             } catch (ConfigException e) {              
                    e.printStackTrace();
                    continue;
             }
             if (!first) sb.append(",");
             else first = false;
             if (null != qs.clientAddr) {
                 sb.append(qs.clientAddr.getHostString()
                         + ":" + qs.clientAddr.getPort());
             }
        }
        return version + " " + sb.toString();
    }
}
