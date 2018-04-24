/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.zookeeper.server.quorum;

import static org.junit.Assert.assertEquals;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.junit.Assert;
import org.junit.Test;

public class QuorumServerTest extends ZKTestCase {
    @Test
    public void testToString() throws ConfigException {
        String config = "127.0.0.1:1234:1236:participant;0.0.0.0:1237";
        String expected = "127.0.0.1:1234:1236:participant;0.0.0.0:1237";
        QuorumServer qs = new QuorumServer(0, config);
        Assert.assertEquals("Use IP address", expected, qs.toString());

        config = "127.0.0.1:1234:1236;0.0.0.0:1237";
        expected = "127.0.0.1:1234:1236:participant;0.0.0.0:1237";
        qs = new QuorumServer(0, config);
        Assert.assertEquals("Type unspecified", expected, qs.toString());

        config = "127.0.0.1:1234:1236:observer;0.0.0.0:1237";
        expected = "127.0.0.1:1234:1236:observer;0.0.0.0:1237";
        qs = new QuorumServer(0, config);
        Assert.assertEquals("Observer type", expected, qs.toString());

        config = "127.0.0.1:1234:1236:participant;1237";
        expected = "127.0.0.1:1234:1236:participant;0.0.0.0:1237";
        qs = new QuorumServer(0, config);
        Assert.assertEquals("Client address unspecified",
                            expected, qs.toString());

        config = "127.0.0.1:1234:1236:participant;1.2.3.4:1237";
        expected = "127.0.0.1:1234:1236:participant;1.2.3.4:1237";
        qs = new QuorumServer(0, config);
        Assert.assertEquals("Client address specified",
                            expected, qs.toString());

        config = "example.com:1234:1236:participant;1237";
        expected = "example.com:1234:1236:participant;0.0.0.0:1237";
        qs = new QuorumServer(0, config);
        Assert.assertEquals("Use hostname", expected, qs.toString());
    }

    @Test
    public void constructionUnderstandsIpv6LiteralsInServerConfig() throws ConfigException {
        String config = "[::1]:1234:1236:participant";
        QuorumServer qs = new QuorumServer(0, config);
        assertEquals("[0:0:0:0:0:0:0:1]:1234:1236:participant", qs.toString());
    }

    @Test
    public void constructionUnderstandsIpv6LiteralsInClientConfig() throws ConfigException {
        String config = "127.0.0.1:1234:1236:participant;[::1]:1237";
        QuorumServer qs = new QuorumServer(0, config);
        assertEquals("127.0.0.1:1234:1236:participant;[0:0:0:0:0:0:0:1]:1237", qs.toString());
    }

    @Test(expected = ConfigException.class)
    public void unbalancedIpv6LiteralsInServerConfigFailToBeParsed() throws ConfigException {
        new QuorumServer(0, "[::1:1234:1236:participant");
    }

    @Test(expected = ConfigException.class)
    public void unbalancedIpv6LiteralsInClientConfigFailToBeParsed() throws ConfigException {
        new QuorumServer(0, "127.0.0.1:1234:1236:participant;[::1:1237");
    }
}
