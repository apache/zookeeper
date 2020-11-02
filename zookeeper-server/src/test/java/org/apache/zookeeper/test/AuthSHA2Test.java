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

public class AuthSHA2Test extends AuthTest {

    @BeforeAll
    public static void setup() {
        // use the BouncyCastle's Provider for testing
        Security.addProvider(new BouncyCastleProvider());
        // password is test
        System.setProperty(DigestAuthenticationProvider.DIGEST_ALGORITHM_KEY, DigestAlgEnum.SHA_256.getName());
        System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest", "super:wjySwxg860UATFtciuZ1lpzrCHrPeov6SPu/ZD56uig=");
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
        assertEquals("super:wjySwxg860UATFtciuZ1lpzrCHrPeov6SPu/ZD56uig=", DigestAuthenticationProvider.generateDigest("super:test"));
        assertEquals("super:Ie58Fw6KA4ucTEDj23imIltKrXNDxQg8Rwtu0biQFcU=", DigestAuthenticationProvider.generateDigest("super:zookeeper"));
        assertEquals("super:rVOiTPnqEqlpIRXqSoE6+7h6SzbHUrfAe34i8n/gmRU=", DigestAuthenticationProvider.generateDigest(("super:foo")));
        assertEquals("super:vs70GBagNcqIhGR4R6rXP8E3lvJPYhzMpAMx8ghbTUk=", DigestAuthenticationProvider.generateDigest(("super:bar")));
    }

    @Test
    public void testDigest() throws NoSuchAlgorithmException {
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", getGeneratedDigestStr(DigestAuthenticationProvider.digest("test")));
        assertEquals("456831beef3fc1500939995d7369695f48642664a02d5eab9d807592a08b2384", getGeneratedDigestStr(DigestAuthenticationProvider.digest("zookeeper")));
        assertEquals("2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae", getGeneratedDigestStr(DigestAuthenticationProvider.digest(("foo"))));
        assertEquals("fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9", getGeneratedDigestStr(DigestAuthenticationProvider.digest(("bar"))));
    }
}
