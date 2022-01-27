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

import org.apache.zookeeper.common.crypto.Crypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SSLUtil {

    private static final Logger LOG = LoggerFactory.getLogger(SSLUtil.class);
    private static Crypt crypt;

    /**
     * This method will return an instance of the user configured Crypto class
     * value for {@value ZKConfig#CONFIG_CRYPT_CLASS}.
     *
     * @return an instance of the Crypto class configured
     */
    private static Crypt getCrypt() throws ReflectiveOperationException {
        if (crypt == null) {
            synchronized (Crypt.class) {
                if (crypt == null) {
                    createCryptInstance();
                }
            }
        }
        return crypt;
    }

    private static void createCryptInstance() throws ReflectiveOperationException {
        String cryptClassName = System
                .getProperty(ZKConfig.CONFIG_CRYPT_CLASS);
        Class<?> cryptClass = null;
        if (cryptClassName != null && !cryptClassName.isEmpty()) {
            cryptClass = Class.forName(cryptClassName.trim());
            crypt = (Crypt) cryptClass.getConstructor().newInstance();
        } else {
            throw new IllegalArgumentException (
                    "Class to decrypt the encrypted text is not configured for "
                            + ZKConfig.CONFIG_CRYPT_CLASS + " property ");
        }
    }
    /**
     * This method will decrypt the given text using the Crypto class client
     * has configured if client has set the password encryption to true
     *
     * @param pwd     string to decrypt
     * @param decrypt client configured value whether the text is in plain or
     *                encrypted format
     * @return decrypted text
     */
    public static String getDecryptedText(String pwd, boolean decrypt) {
        if (decrypt) {
            try {
                getCrypt();
                pwd = crypt.decrypt(pwd);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to decrypt the encrypted text", e);
            }
        }
        return pwd;
    }

    /**
     * This method is exposed only for test purposes to clear the static crypt
     * variable
     */
    public static void clearCrypt() {
        if (crypt != null) {
            crypt = null;
        }
    }
}
