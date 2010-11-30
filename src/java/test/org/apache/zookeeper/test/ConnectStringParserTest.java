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

package org.apache.zookeeper.test;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.client.ConnectStringParser;
import org.junit.Assert;
import org.junit.Test;

public class ConnectStringParserTest extends ZKTestCase{

    @Test
    public void testSingleServerChrootPath(){
        String chrootPath = "/hallo/welt";
        String servers = "10.10.10.1";
        assertChrootPath(chrootPath,
                new ConnectStringParser(servers+chrootPath));
    }

    @Test
    public void testMultipleServersChrootPath(){
        String chrootPath = "/hallo/welt";
        String servers = "10.10.10.1,10.10.10.2";
        assertChrootPath(chrootPath,
                new ConnectStringParser(servers+chrootPath));
    }

    @Test
    public void testParseServersWithoutPort(){
        String servers = "10.10.10.1,10.10.10.2";
        ConnectStringParser parser = new ConnectStringParser(servers);

        Assert.assertEquals("10.10.10.1", parser.getServerAddresses().get(0).getHostName());
        Assert.assertEquals("10.10.10.2", parser.getServerAddresses().get(1).getHostName());
    }

    @Test
    public void testParseServersWithPort(){
        String servers = "10.10.10.1:112,10.10.10.2:110";
        ConnectStringParser parser = new ConnectStringParser(servers);

        Assert.assertEquals("10.10.10.1", parser.getServerAddresses().get(0).getHostName());
        Assert.assertEquals("10.10.10.2", parser.getServerAddresses().get(1).getHostName());

        Assert.assertEquals(112, parser.getServerAddresses().get(0).getPort());
        Assert.assertEquals(110, parser.getServerAddresses().get(1).getPort());
    }

    private void assertChrootPath(String expected, ConnectStringParser parser){
        Assert.assertEquals(expected, parser.getChrootPath());
    }
}
