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

/**
 * This enum represents the file type of a KeyStore or TrustStore.
 * Currently, JKS (Java keystore), PEM, PKCS12, and BCFKS types are supported.
 */
public enum KeyStoreFileType {
    JKS(".jks"),
    PEM(".pem"),
    PKCS12(".p12"),
    BCFKS(".bcfks");

    private final String defaultFileExtension;

    KeyStoreFileType(String defaultFileExtension) {
        this.defaultFileExtension = defaultFileExtension;
    }

    /**
     * The property string that specifies that a key store or trust store
     * should use this store file type.
     */
    public String getPropertyValue() {
        return this.name();
    }

    /**
     * The file extension that is associated with this file type.
     */
    public String getDefaultFileExtension() {
        return defaultFileExtension;
    }

    /**
     * Converts a property value to a StoreFileType enum. If the property value
     * is <code>null</code> or an empty string, returns <code>null</code>.
     * @param propertyValue the property value.
     * @return the KeyStoreFileType, or <code>null</code> if
     *         <code>propertyValue</code> is <code>null</code> or empty.
     * @throws IllegalArgumentException if <code>propertyValue</code> is not
     *         one of "JKS", "PEM", "BCFKS", "PKCS12", or empty/null.
     */
    public static KeyStoreFileType fromPropertyValue(String propertyValue) {
        if (propertyValue == null || propertyValue.length() == 0) {
            return null;
        }
        return KeyStoreFileType.valueOf(propertyValue.toUpperCase());
    }

    /**
     * Detects the type of KeyStore / TrustStore file from the file extension.
     * If the file name ends with ".jks", returns <code>StoreFileType.JKS</code>.
     * If the file name ends with ".pem", returns <code>StoreFileType.PEM</code>.
     * If the file name ends with ".p12", returns <code>StoreFileType.PKCS12</code>.
     * If the file name ends with ".bckfs", returns <code>StoreFileType.BCKFS</code>.
     * Otherwise, throws an IllegalArgumentException.
     * @param filename the filename of the key store or trust store file.
     * @return a KeyStoreFileType.
     * @throws IllegalArgumentException if the filename does not end with
     *         ".jks", ".pem", "p12" or "bcfks".
     */
    public static KeyStoreFileType fromFilename(String filename) {
        int i = filename.lastIndexOf('.');
        if (i >= 0) {
            String extension = filename.substring(i);
            for (KeyStoreFileType storeFileType : KeyStoreFileType.values()) {
                if (storeFileType.getDefaultFileExtension().equals(extension)) {
                    return storeFileType;
                }
            }
        }
        throw new IllegalArgumentException("Unable to auto-detect store file type from file name: " + filename);
    }

    /**
     * If <code>propertyValue</code> is not null or empty, returns the result
     * of <code>KeyStoreFileType.fromPropertyValue(propertyValue)</code>. Else,
     * returns the result of <code>KeyStoreFileType.fromFileName(filename)</code>.
     * @param propertyValue property value describing the KeyStoreFileType, or
     *                      null/empty to auto-detect the type from the file
     *                      name.
     * @param filename file name of the key store file. The file extension is
     *                 used to auto-detect the KeyStoreFileType when
     *                 <code>propertyValue</code> is null or empty.
     * @return a KeyStoreFileType.
     * @throws IllegalArgumentException if <code>propertyValue</code> is not
     *         one of "JKS", "PEM", "PKCS12", "BCFKS", or empty/null.
     * @throws IllegalArgumentException if <code>propertyValue</code>is empty
     *         or null and the type could not be determined from the file name.
     */
    public static KeyStoreFileType fromPropertyValueOrFileName(String propertyValue, String filename) {
        KeyStoreFileType result = KeyStoreFileType.fromPropertyValue(propertyValue);
        if (result == null) {
            result = KeyStoreFileType.fromFilename(filename);
        }
        return result;
    }
}
