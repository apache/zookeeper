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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.client.ConnectStringParser;
import org.junit.jupiter.api.Test;

public class ConnectStringParserTest extends ZKTestCase {

    private static final int DEFAULT_PORT = 2181;

    @Test
    public void testSingleServerChrootPath() {
        String chrootPath = "/hallo/welt";
        String servers = "10.10.10.1";
        assertChrootPath(chrootPath, new ConnectStringParser(servers + chrootPath));

        servers = "[2001:db8:1::242:ac11:2]";
        assertChrootPath(chrootPath, new ConnectStringParser(servers + chrootPath));
    }

    @Test
    public void testMultipleServersChrootPath() {
        String chrootPath = "/hallo/welt";
        String servers = "10.10.10.1,10.10.10.2";
        assertChrootPath(chrootPath, new ConnectStringParser(servers + chrootPath));

        servers = "[2001:db8:1::242:ac11:2]:2181,[2001:db8:85a3:8d3:1319:8a2e:370:7348]:5678";
        assertChrootPath(chrootPath, new ConnectStringParser(servers + chrootPath));
    }

    @Test
    public void testParseServersWithoutPort() {
        String servers = "10.10.10.1,10.10.10.2";
        ConnectStringParser parser = new ConnectStringParser(servers);
        assertEquals("10.10.10.1", parser.getServerAddresses().get(0).getHostString());
        assertEquals(DEFAULT_PORT, parser.getServerAddresses().get(0).getPort());
        assertEquals("10.10.10.2", parser.getServerAddresses().get(1).getHostString());
        assertEquals(DEFAULT_PORT, parser.getServerAddresses().get(1).getPort());

        servers = "[2001:db8:1::242:ac11:2],[2001:db8:85a3:8d3:1319:8a2e:370:7348]";
        parser = new ConnectStringParser(servers);
        assertEquals("2001:db8:1::242:ac11:2", parser.getServerAddresses().get(0).getHostString());
        assertEquals(DEFAULT_PORT, parser.getServerAddresses().get(0).getPort());
        assertEquals("2001:db8:85a3:8d3:1319:8a2e:370:7348", parser.getServerAddresses().get(1).getHostString());
        assertEquals(DEFAULT_PORT, parser.getServerAddresses().get(1).getPort());
    }

    @Test
    public void testParseServersWithPort() {
        String servers = "10.10.10.1:112,10.10.10.2:110";
        ConnectStringParser parser = new ConnectStringParser(servers);
        assertEquals("10.10.10.1", parser.getServerAddresses().get(0).getHostString());
        assertEquals("10.10.10.2", parser.getServerAddresses().get(1).getHostString());
        assertEquals(112, parser.getServerAddresses().get(0).getPort());
        assertEquals(110, parser.getServerAddresses().get(1).getPort());

        servers = "[2001:db8:1::242:ac11:2]:1234,[2001:db8:85a3:8d3:1319:8a2e:370:7348]:5678";
        parser = new ConnectStringParser(servers);
        assertEquals("2001:db8:1::242:ac11:2", parser.getServerAddresses().get(0).getHostString());
        assertEquals("2001:db8:85a3:8d3:1319:8a2e:370:7348", parser.getServerAddresses().get(1).getHostString());
        assertEquals(1234, parser.getServerAddresses().get(0).getPort());
        assertEquals(5678, parser.getServerAddresses().get(1).getPort());
    }

    private void assertChrootPath(String expected, ConnectStringParser parser) {
        assertEquals(expected, parser.getChrootPath());
    }

    @Test
    public void testParseIPV6ConnectionString() {
        String servers = "[127::1],127.0.10.2";
        ConnectStringParser parser = new ConnectStringParser(servers);

        assertEquals("127::1", parser.getServerAddresses().get(0).getHostString());
        assertEquals("127.0.10.2", parser.getServerAddresses().get(1).getHostString());
        assertEquals(2181, parser.getServerAddresses().get(0).getPort());
        assertEquals(2181, parser.getServerAddresses().get(1).getPort());

        servers = "[127::1]:2181,[127::2]:2182,[127::3]:2183";
        parser = new ConnectStringParser(servers);

        assertEquals("127::1", parser.getServerAddresses().get(0).getHostString());
        assertEquals("127::2", parser.getServerAddresses().get(1).getHostString());
        assertEquals("127::3", parser.getServerAddresses().get(2).getHostString());
        assertEquals(2181, parser.getServerAddresses().get(0).getPort());
        assertEquals(2182, parser.getServerAddresses().get(1).getPort());
        assertEquals(2183, parser.getServerAddresses().get(2).getPort());
    }

}
