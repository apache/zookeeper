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

package org.apache.zookeeper.common;

import org.apache.zookeeper.ZKTestCase;
import org.junit.Assert;
import org.junit.Test;

public class KeyStoreFileTypeTest extends ZKTestCase {
    @Test
    public void testGetPropertyValue() {
        Assert.assertEquals("PEM", KeyStoreFileType.PEM.getPropertyValue());
        Assert.assertEquals("JKS", KeyStoreFileType.JKS.getPropertyValue());
    }

    @Test
    public void testFromPropertyValue() {
        Assert.assertEquals(KeyStoreFileType.PEM, KeyStoreFileType.fromPropertyValue("PEM"));
        Assert.assertEquals(KeyStoreFileType.JKS, KeyStoreFileType.fromPropertyValue("JKS"));
        Assert.assertNull(KeyStoreFileType.fromPropertyValue(""));
        Assert.assertNull(KeyStoreFileType.fromPropertyValue(null));
    }

    @Test
    public void testFromPropertyValueIgnoresCase() {
        Assert.assertEquals(KeyStoreFileType.PEM, KeyStoreFileType.fromPropertyValue("pem"));
        Assert.assertEquals(KeyStoreFileType.JKS, KeyStoreFileType.fromPropertyValue("jks"));
        Assert.assertNull(KeyStoreFileType.fromPropertyValue(""));
        Assert.assertNull(KeyStoreFileType.fromPropertyValue(null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromPropertyValueThrowsOnBadPropertyValue() {
        KeyStoreFileType.fromPropertyValue("foobar");
    }

    @Test
    public void testFromFilename() {
        Assert.assertEquals(KeyStoreFileType.JKS,
                KeyStoreFileType.fromFilename("mykey.jks"));
        Assert.assertEquals(KeyStoreFileType.JKS,
                KeyStoreFileType.fromFilename("/path/to/key/dir/mykey.jks"));
        Assert.assertEquals(KeyStoreFileType.PEM,
                KeyStoreFileType.fromFilename("mykey.pem"));
        Assert.assertEquals(KeyStoreFileType.PEM,
                KeyStoreFileType.fromFilename("/path/to/key/dir/mykey.pem"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromFilenameThrowsOnBadFileExtension() {
        KeyStoreFileType.fromFilename("prod.key");
    }

    @Test
    public void testFromPropertyValueOrFileName() {
        // Property value takes precedence if provided
        Assert.assertEquals(KeyStoreFileType.JKS,
                KeyStoreFileType.fromPropertyValueOrFileName(
                        "JKS", "prod.key"));
        // Falls back to filename detection if no property value
        Assert.assertEquals(KeyStoreFileType.JKS,
                KeyStoreFileType.fromPropertyValueOrFileName("", "prod.jks"));
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
