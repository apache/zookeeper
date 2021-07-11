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
package org.apache.zookeeper.common;

import org.apache.zookeeper.common.crypto.TestCrypt;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class SSLUtilTest {

    @After
    public  void teardown() throws Exception {
        System.clearProperty(ZKConfig.CONFIG_CRYPT_CLASS);
        SSLUtil.clearCrypt();
    }

    @Test
    public void testGetDecryptedPassword() throws Exception {
        TestCrypt c = new TestCrypt();
        String encryptedText = c.encrypt("test123");
        System.setProperty(ZKConfig.CONFIG_CRYPT_CLASS,
                TestCrypt.class.getName());
        String decryptedPwd = SSLUtil.getDecryptedText(encryptedText, true);
        Assert.assertEquals(decryptedPwd, "test123");
    }

    @Test
    public void testGetDecryptPasswordWithoutCryptClass() throws Exception {
        TestCrypt c = new TestCrypt();
        String encryptedText = c.encrypt("test123");
        try {
            SSLUtil.getDecryptedText(encryptedText, true);
            Assert.fail("Exception expected since Crypt class not specified");
        } catch (RuntimeException e) {
            Assert.assertEquals(e.getMessage(), "Failed to get the Crypt reference used for decrypting a text.");
        }
    }

    @Test
    public void testGetDecryptedPasswordInvalid() throws Exception {
        System.setProperty(ZKConfig.CONFIG_CRYPT_CLASS,
                TestCrypt.class.getName());
        try {
            SSLUtil.getDecryptedText(null, true);
            Assert.fail("EXception expected since the encrypted text was null");
        } catch (RuntimeException e) {
            Assert.assertEquals(e.getMessage(), "Failed to decrypt the encrypted text");
        }
    }
}
