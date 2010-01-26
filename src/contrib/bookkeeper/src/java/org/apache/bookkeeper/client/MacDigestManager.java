package org.apache.bookkeeper.client;

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

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

class MacDigestManager extends DigestManager {
    public static String DIGEST_ALGORITHM = "SHA-1";
    public static String KEY_ALGORITHM = "HmacSHA1";
    Mac mac;

    public MacDigestManager(long ledgerId, byte[] passwd) throws GeneralSecurityException {
        super(ledgerId);
        byte[] macKey = genDigest("mac", passwd);
        SecretKeySpec keySpec = new SecretKeySpec(macKey, KEY_ALGORITHM);
        mac = Mac.getInstance(KEY_ALGORITHM);
        mac.init(keySpec);
        
        
    }

    static byte[] genDigest(String pad, byte[] passwd) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
        digest.update(pad.getBytes());
        digest.update(passwd);
                return digest.digest();
    }

    @Override
    int getMacCodeLength() {
        return 20;
    }

    
    @Override
    byte[] getValueAndReset() {
        return mac.doFinal();
    }
    
    @Override
    void update(byte[] data, int offset, int length) {
        mac.update(data, offset, length);
    }
    
    
}
