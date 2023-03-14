/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.junit.jupiter.api.Test;

public class ConfigUtilsTest {

    @Test
    public void testGetHostAndPortWithIPv6() throws ConfigException {
        String[] nsa = ConfigUtils.getHostAndPort("[2001:db8:85a3:8d3:1319:8a2e:370:7348]:443");
        assertEquals(nsa[0], "2001:db8:85a3:8d3:1319:8a2e:370:7348");
        assertEquals(nsa[1], "443");

        nsa = ConfigUtils.getHostAndPort("[2001:db8:1::242:ac11:2]:2888:3888");
        assertEquals(nsa[0], "2001:db8:1::242:ac11:2");
        assertEquals(nsa[1], "2888");
        assertEquals(nsa[2], "3888");
    }

    @Test
    public void testGetHostAndPortWithIPv4() throws ConfigException {
        String[] nsa = ConfigUtils.getHostAndPort("127.0.0.1:443");
        assertEquals(nsa[0], "127.0.0.1");
        assertEquals(nsa[1], "443");

        nsa = ConfigUtils.getHostAndPort("127.0.0.1:2888:3888");
        assertEquals(nsa[0], "127.0.0.1");
        assertEquals(nsa[1], "2888");
        assertEquals(nsa[2], "3888");
    }

    @Test
    public void testGetHostAndPortWithoutBracket() {
        assertThrows(ConfigException.class, () -> {
            String[] nsa = ConfigUtils.getHostAndPort("[2001:db8:85a3:8d3:1319:8a2e:370:7348");
        });
    }

    @Test
    public void testGetHostAndPortWithoutPortAfterColon() {
        assertThrows(ConfigException.class, () -> {
            String[] nsa = ConfigUtils.getHostAndPort("[2001:db8:1::242:ac11:2]:");
        });
    }

    @Test
    public void testGetHostAndPortWithoutPort() throws ConfigException {
        String[] nsa = ConfigUtils.getHostAndPort("127.0.0.1");
        assertEquals(nsa[0], "127.0.0.1");
        assertEquals(nsa.length, 1);

        nsa = ConfigUtils.getHostAndPort("[2001:db8:1::242:ac11:2]");
        assertEquals(nsa[0], "2001:db8:1::242:ac11:2");
        assertEquals(nsa.length, 1);
    }

    @Test
    public void testGetPropertyBackwardCompatibleWay() throws ConfigException {
        String newProp = "zookeeper.prop.x.y.z";
        String oldProp = "prop.x.y.z";

        // Null as both properties are not set
        String result = ConfigUtils.getPropertyBackwardCompatibleWay(newProp);
        assertNull(result);

        // Return old property value when only old property is set
        String oldPropValue = "oldPropertyValue";
        System.setProperty(oldProp, oldPropValue);
        result = ConfigUtils.getPropertyBackwardCompatibleWay(newProp);
        assertEquals(oldPropValue, result);

        // Return new property value when both properties are set
        String newPropValue = "newPropertyValue";
        System.setProperty(newProp, newPropValue);
        result = ConfigUtils.getPropertyBackwardCompatibleWay(newProp);
        assertEquals(newPropValue, result);

        // cleanUp
        clearProp(newProp, oldProp);

        // Return trimmed value
        System.setProperty(oldProp, oldPropValue + "  ");
        result = ConfigUtils.getPropertyBackwardCompatibleWay(newProp);
        assertEquals(oldPropValue, result);

        System.setProperty(newProp, "  " + newPropValue);
        result = ConfigUtils.getPropertyBackwardCompatibleWay(newProp);
        assertEquals(newPropValue, result);

        // cleanUp
        clearProp(newProp, oldProp);
    }

    private void clearProp(String newProp, String oldProp) {
        System.clearProperty(newProp);
        System.clearProperty(oldProp);
    }
}
