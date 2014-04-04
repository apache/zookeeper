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
package org.apache.zookeeper.inspector.encryption;

/**
 *
 */
public class BasicDataEncryptionManager implements DataEncryptionManager {

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.zookeeper.inspector.encryption.DataEncryptionManager#decryptData
     * (byte[])
     */
    public String decryptData(byte[] encrypted) throws Exception {
        return new String(encrypted);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.zookeeper.inspector.encryption.DataEncryptionManager#encryptData
     * (java.lang.String)
     */
    public byte[] encryptData(String data) throws Exception {
        if (data == null) {
            return new byte[0];
        }
        return data.getBytes();
    }

}
