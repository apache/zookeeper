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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class AuthUtilTest {

    @BeforeAll
    public static void beforeClassSetUp() {
        ProviderRegistry.reset();
        System.setProperty("zookeeper.authProvider.sasl",
                "org.apache.zookeeper.server.auth.SASLAuthenticationProvider");
        System.setProperty("zookeeper.authProvider.x509",
                "org.apache.zookeeper.server.auth.X509AuthenticationProvider");
    }

    @AfterAll
    public static void afterClassTearDown() {
        System.clearProperty("zookeeper.authProvider.sasl");
        System.clearProperty("zookeeper.authProvider.x509");
    }

    @Test
    public void testGetUserFromAllAuthenticationScheme() {
        String user = "zkUser";
        Id id = new Id("digest", user + ":password");
        String result = AuthUtil.getUser(id);
        assertEquals(user, result);

        String principal = "zkCli/hadoop.hadoop.com";
        id = new Id("sasl", principal);
        assertEquals(principal, AuthUtil.getUser(id));

        String ip = "192.168.1.2";
        id = new Id("ip", ip);
        assertEquals(ip, AuthUtil.getUser(id));

        String certificate = "CN=host-192.168.1.2,OU=OrganizationUnit,O=Organization,L=Location,ST=State,C=IN";
        id = new Id("x509", certificate);
        assertEquals(certificate, AuthUtil.getUser(id));
    }

    @Test
    public void testGetUserShouldReturnNullIfAuthenticationNotConfigured() {
        Id id = new Id("invalid Authentication Scheme", "user");
        String result = AuthUtil.getUser(id);
        assertNull(result);
    }
}
