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

package org.apache.zookeeper.server.util;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map.Entry;
import java.util.Properties;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;

public class ConfigUtils {

    public static String getClientConfigStr(String configData) {
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
            if (key.equals("version")) {
                version = value;
            }
            if (!key.startsWith("server.")) {
                continue;
            }
            QuorumPeer.QuorumServer qs;
            try {
                qs = new QuorumPeer.QuorumServer(-1, value);
            } catch (ConfigException e) {
                e.printStackTrace();
                continue;
            }
            if (!first) {
                sb.append(",");
            } else {
                first = false;
            }
            if (null != qs.clientAddr) {
                sb.append(qs.clientAddr.getHostString() + ":" + qs.clientAddr.getPort());
            }
        }
        return version + " " + sb.toString();
    }

    /**
     * Gets host and port by splitting server config
     * with support for IPv6 literals
     * @return String[] first element being the
     *  IP address and the next being the port
     * @param s server config, server:port
     */
    public static String[] getHostAndPort(String s) throws ConfigException {
        if (s.startsWith("[")) {
            int i = s.indexOf("]");
            if (i < 0) {
                throw new ConfigException(s + " starts with '[' but has no matching ']:'");
            }
            if (i + 2 == s.length()) {
                throw new ConfigException(s + " doesn't have a port after colon");
            }
            if (i + 2 < s.length()) {
                String[] sa = s.substring(i + 2).split(":");
                String[] nsa = new String[sa.length + 1];
                nsa[0] = s.substring(1, i);
                System.arraycopy(sa, 0, nsa, 1, sa.length);
                return nsa;
            }
            return new String[]{s.replaceAll("\\[|\\]", "")};
        } else {
            return s.split(":");
        }
    }

    /**
     * Some old configuration properties are not configurable in zookeeper configuration file
     * zoo.cfg. To make these properties configurable in zoo.cfg old properties are prepended
     * with zookeeper. For example prop.x.y.z changed to zookeeper.prop.x.y.z. But for backward
     * compatibility both prop.x.y.z and zookeeper.prop.x.y.z should be supported.
     * This method first gets value from new property, if first property is not configured
     * then gets value from old property
     *
     * @param newPropertyKey new property key which starts with zookeeper.
     * @return either new or old system property value. Null if none of the properties are set.
     */
    public static String getPropertyBackwardCompatibleWay(String newPropertyKey) {
        String newKeyValue = System.getProperty(newPropertyKey);
        if (newKeyValue != null) {
            return newKeyValue.trim();
        }
        String oldPropertyKey = newPropertyKey.replace("zookeeper.", "");
        String oldKeyValue = System.getProperty(oldPropertyKey);

        if (oldKeyValue != null) {
            return oldKeyValue.trim();
        }
        return null;
    }

}
