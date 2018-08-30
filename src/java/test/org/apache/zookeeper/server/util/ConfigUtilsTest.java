/**
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

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;

public class ConfigUtilsTest {

    @Test
    public void testSplitServerConfig() throws ConfigException {
        String[] nsa = ConfigUtils.getHostAndPort("[2001:db8:85a3:8d3:1319:8a2e:370:7348]:443");
        System.out.println(nsa[0]);
        assertEquals(nsa[0], "2001:db8:85a3:8d3:1319:8a2e:370:7348");
        assertEquals(nsa[1], "443");
    }

    @Test
    public void testSplitServerConfig2() throws ConfigException {
        String[] nsa = ConfigUtils.getHostAndPort("127.0.0.1:443");
        assertEquals(nsa.length, 2, 0);
    }

    @Test(expected = ConfigException.class)
    public void testSplitServerConfig3() throws ConfigException {
        String[] nsa = ConfigUtils.getHostAndPort("[2001:db8:85a3:8d3:1319:8a2e:370:7348");
    }

    @Test
    public void testSplitServerConfig4() throws ConfigException {
        String[] nsa = ConfigUtils.getHostAndPort("2001:db8:85a3:8d3:1319:8a2e:370:7348:443");
        assertFalse(nsa.length == 2);
    }
}
