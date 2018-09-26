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
package org.apache.zookeeper;

import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientCanonicalizeTest extends ZKTestCase {
    @Test
    public void testClientCanonicalization() throws IOException, InterruptedException {
        SaslServerPrincipal.WrapperInetSocketAddress addr = mock(SaslServerPrincipal.WrapperInetSocketAddress.class);
        SaslServerPrincipal.WrapperInetAddress ia = mock(SaslServerPrincipal.WrapperInetAddress.class);

        when(addr.getHostName()).thenReturn("zookeeper.apache.org");
        when(addr.getAddress()).thenReturn(ia);
        when(ia.getCanonicalHostName()).thenReturn("zk1.apache.org");
        when(ia.getHostAddress()).thenReturn("127.0.0.1");

        String principal = SaslServerPrincipal.getServerPrincipal(addr);
        Assert.assertEquals("The computed principal does not appear to have been canonicalized", "zookeeper/zk1.apache.org", principal);
    }

    @Test
    public void testClientNoCanonicalization() throws IOException, InterruptedException {
        SaslServerPrincipal.WrapperInetSocketAddress addr = mock(SaslServerPrincipal.WrapperInetSocketAddress.class);
        SaslServerPrincipal.WrapperInetAddress ia = mock(SaslServerPrincipal.WrapperInetAddress.class);

        when(addr.getHostName()).thenReturn("zookeeper.apache.org");
        when(addr.getAddress()).thenReturn(ia);
        when(ia.getCanonicalHostName()).thenReturn("zk1.apache.org");
        when(ia.getHostAddress()).thenReturn("127.0.0.1");

        System.setProperty(SaslServerPrincipal.ZK_SASL_CLIENT_CANONICALIZE_HOSTNAME, "false");
        try {
            String principal = SaslServerPrincipal.getServerPrincipal(addr);
            Assert.assertEquals("The computed principal does appears to have been canonicalized incorrectly", "zookeeper/zookeeper.apache.org",
                principal);
        } finally {
            System.setProperty(SaslServerPrincipal.ZK_SASL_CLIENT_CANONICALIZE_HOSTNAME, "true");
        }
    }

    @Test
    public void testClientCanonicalizationToIp() throws IOException, InterruptedException {
        SaslServerPrincipal.WrapperInetSocketAddress addr = mock(SaslServerPrincipal.WrapperInetSocketAddress.class);
        SaslServerPrincipal.WrapperInetAddress ia = mock(SaslServerPrincipal.WrapperInetAddress.class);

        when(addr.getHostName()).thenReturn("zookeeper.apache.org");
        when(addr.getAddress()).thenReturn(ia);
        when(ia.getCanonicalHostName()).thenReturn("127.0.0.1");
        when(ia.getHostAddress()).thenReturn("127.0.0.1");

        String principal = SaslServerPrincipal.getServerPrincipal(addr);
        Assert.assertEquals("The computed principal does appear to have falled back to the original host name",
            "zookeeper/zookeeper.apache.org", principal);
    }
}
