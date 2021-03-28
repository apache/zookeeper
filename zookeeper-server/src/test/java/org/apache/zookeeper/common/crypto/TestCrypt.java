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

package org.apache.zookeeper.common.crypto;

/**
 * Default crypt class to encrypt and decrypt password for test purpose
 */
public class TestCrypt implements Crypt {

    int key = 7;

    public String encrypt(String plainText) throws Exception {
        if (null == plainText || plainText.trim().length() == 0) {
            throw new IllegalAccessException(
                    "The specified string to be encrypted[" + plainText + "] is either null or empty");
        }
        char[] chars = plainText.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] += key;
        }
        return new String(chars);
    }

    @Override
    public String decrypt (String cipherText) throws Exception {
        if (null == cipherText || cipherText.trim().length() == 0) {
            throw new IllegalAccessException(
                    "The specified string to be decrypted[" + cipherText + "] is either null or empty");
        }
        char[] chars = cipherText.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] -= key;
        }
        return new String(chars);
    }
}
