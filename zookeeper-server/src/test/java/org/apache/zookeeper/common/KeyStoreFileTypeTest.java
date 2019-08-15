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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.apache.zookeeper.ZKTestCase;
import org.junit.Test;

public class KeyStoreFileTypeTest extends ZKTestCase {

    @Test
    public void testGetPropertyValue() {
        assertEquals("PEM", KeyStoreFileType.PEM.getPropertyValue());
        assertEquals("JKS", KeyStoreFileType.JKS.getPropertyValue());
        assertEquals("PKCS12", KeyStoreFileType.PKCS12.getPropertyValue());
    }

    @Test
    public void testFromPropertyValue() {
        assertEquals(KeyStoreFileType.PEM, KeyStoreFileType.fromPropertyValue("PEM"));
        assertEquals(KeyStoreFileType.JKS, KeyStoreFileType.fromPropertyValue("JKS"));
        assertEquals(KeyStoreFileType.PKCS12, KeyStoreFileType.fromPropertyValue("PKCS12"));
        assertNull(KeyStoreFileType.fromPropertyValue(""));
        assertNull(KeyStoreFileType.fromPropertyValue(null));
    }

    @Test
    public void testFromPropertyValueIgnoresCase() {
        assertEquals(KeyStoreFileType.PEM, KeyStoreFileType.fromPropertyValue("pem"));
        assertEquals(KeyStoreFileType.JKS, KeyStoreFileType.fromPropertyValue("jks"));
        assertEquals(KeyStoreFileType.PKCS12, KeyStoreFileType.fromPropertyValue("pkcs12"));
        assertNull(KeyStoreFileType.fromPropertyValue(""));
        assertNull(KeyStoreFileType.fromPropertyValue(null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromPropertyValueThrowsOnBadPropertyValue() {
        KeyStoreFileType.fromPropertyValue("foobar");
    }

    @Test
    public void testFromFilename() {
        assertEquals(KeyStoreFileType.JKS, KeyStoreFileType.fromFilename("mykey.jks"));
        assertEquals(KeyStoreFileType.JKS, KeyStoreFileType.fromFilename("/path/to/key/dir/mykey.jks"));
        assertEquals(KeyStoreFileType.PEM, KeyStoreFileType.fromFilename("mykey.pem"));
        assertEquals(KeyStoreFileType.PEM, KeyStoreFileType.fromFilename("/path/to/key/dir/mykey.pem"));
        assertEquals(KeyStoreFileType.PKCS12, KeyStoreFileType.fromFilename("mykey.p12"));
        assertEquals(KeyStoreFileType.PKCS12, KeyStoreFileType.fromFilename("/path/to/key/dir/mykey.p12"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromFilenameThrowsOnBadFileExtension() {
        KeyStoreFileType.fromFilename("prod.key");
    }

    @Test
    public void testFromPropertyValueOrFileName() {
        // Property value takes precedence if provided
        assertEquals(KeyStoreFileType.JKS, KeyStoreFileType.fromPropertyValueOrFileName("JKS", "prod.key"));
        assertEquals(KeyStoreFileType.PEM, KeyStoreFileType.fromPropertyValueOrFileName("PEM", "prod.key"));
        assertEquals(KeyStoreFileType.PKCS12, KeyStoreFileType.fromPropertyValueOrFileName("PKCS12", "prod.key"));
        // Falls back to filename detection if no property value
        assertEquals(KeyStoreFileType.JKS, KeyStoreFileType.fromPropertyValueOrFileName("", "prod.jks"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromPropertyValueOrFileNameThrowsOnBadPropertyValue() {
        KeyStoreFileType.fromPropertyValueOrFileName("foobar", "prod.jks");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromPropertyValueOrFileNameThrowsOnBadFileExtension() {
        KeyStoreFileType.fromPropertyValueOrFileName("", "prod.key");
    }

}
