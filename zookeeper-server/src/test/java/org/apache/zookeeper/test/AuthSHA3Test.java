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
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import org.apache.zookeeper.server.auth.DigestAuthenticationProvider;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class AuthSHA3Test extends AuthTest {

    @BeforeAll
    public static void setup() {
        // use the BouncyCastle's Provider for testing
        Security.addProvider(new BouncyCastleProvider());
        // password is test
        System.setProperty(DigestAuthenticationProvider.DIGEST_ALGORITHM_KEY, DigestAlgEnum.SHA3_256.getName());
        System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest", "super:cRy/KPYuDpW/dtsepniTMpuiuupnWgdU9txltIfv3hA=");
        System.setProperty("zookeeper.authProvider.1", "org.apache.zookeeper.test.InvalidAuthProvider");
    }

    @AfterAll
    public static void teardown() {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        System.clearProperty("zookeeper.DigestAuthenticationProvider.superDigest");
        System.clearProperty(DigestAuthenticationProvider.DIGEST_ALGORITHM_KEY);
    }

    @Test
    public void testBadAuthNotifiesWatch() throws Exception {
        super.testBadAuthNotifiesWatch();
    }

    @Test
    public void testBadAuthThenSendOtherCommands() throws Exception {
        super.testBadAuthThenSendOtherCommands();
    }

    @Test
    public void testSuper() throws Exception {
        super.testSuper();
    }

    @Test
    public void testSuperACL() throws Exception {
        super.testSuperACL();
    }

    @Test
    public void testOrdinaryACL() throws Exception {
        super.testOrdinaryACL();
    }

    @Test
    public void testGenerateDigest() throws NoSuchAlgorithmException {
        assertEquals("super:cRy/KPYuDpW/dtsepniTMpuiuupnWgdU9txltIfv3hA=", DigestAuthenticationProvider.generateDigest("super:test"));
        assertEquals("super:gM3M1QcrKC6b+h4oZ5Ixc4GTVaAsggI+AqkUaF6E1Is=", DigestAuthenticationProvider.generateDigest("super:zookeeper"));
        assertEquals("super:2Ww7VUqTohd3lX/Vf4Nvw+GxbmOsX1p337L7Bnks4L8=", DigestAuthenticationProvider.generateDigest(("super:foo")));
        assertEquals("super:Ft5s2Rtxr8zyz16feKiFR/8yqa6JoNEJ0In73aXojE8=", DigestAuthenticationProvider.generateDigest(("super:bar")));
    }

    @Test
    public void testDigest() throws NoSuchAlgorithmException {
        assertEquals("36f028580bb02cc8272a9a020f4200e346e276ae664e45ee80745574e2f5ab80", getGeneratedDigestStr(DigestAuthenticationProvider.digest("test")));
        assertEquals("af4c1abc2deaa6edffc7ce34edeb8c03ee9a1488b64fd318ddb93b4b7f1c0746", getGeneratedDigestStr(DigestAuthenticationProvider.digest("zookeeper")));
        assertEquals("76d3bc41c9f588f7fcd0d5bf4718f8f84b1c41b20882703100b9eb9413807c01", getGeneratedDigestStr(DigestAuthenticationProvider.digest(("foo"))));
        assertEquals("cceefd7e0545bcf8b6d19f3b5750c8a3ee8350418877bc6fb12e32de28137355", getGeneratedDigestStr(DigestAuthenticationProvider.digest(("bar"))));
    }
}
