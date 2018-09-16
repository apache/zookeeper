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
package org.apache.zookeeper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import org.junit.BeforeClass;
import org.junit.Test;

public class ZKUtilTest {
    private static final File testData = new File(System.getProperty("test.data.dir", "build/test/data"));

    @BeforeClass
    public static void init() {
        testData.mkdirs();
    }

    @Test
    public void testValidateFileInput() throws IOException {
        File file = File.createTempFile("test", ".junit", testData);
        file.deleteOnExit();
        String absolutePath = file.getAbsolutePath();
        String error = ZKUtil.validateFileInput(absolutePath);
        assertNull(error);
    }

    @Test
    public void testValidateFileInputNotExist() {
        String fileName = UUID.randomUUID().toString();
        File file = new File(testData, fileName);
        String absolutePath = file.getAbsolutePath();
        String error = ZKUtil.validateFileInput(absolutePath);
        assertNotNull(error);
        String expectedMessage = "File '" + absolutePath + "' does not exist.";
        assertEquals(expectedMessage, error);
    }

    @Test
    public void testValidateFileInputDirectory() throws Exception {
        File file = File.createTempFile("test", ".junit", testData);
        file.deleteOnExit();
        // delete file, as we need directory not file
        file.delete();
        file.mkdir();
        String absolutePath = file.getAbsolutePath();
        String error = ZKUtil.validateFileInput(absolutePath);
        assertNotNull(error);
        String expectedMessage = "'" + absolutePath + "' is a direcory. it must be a file.";
        assertEquals(expectedMessage, error);
    }

    @Test
    public void testUnreadableFileInput() throws Exception {
        //skip this test on Windows, coverage on Linux
        assumeTrue(!org.apache.zookeeper.Shell.WINDOWS);
        File file = File.createTempFile("test", ".junit", testData);
        file.setReadable(false, false);
        file.deleteOnExit();
        String absolutePath = file.getAbsolutePath();
        String error = ZKUtil.validateFileInput(absolutePath);
        assertNotNull(error);
        String expectedMessage = "Read permission is denied on the file '" + absolutePath + "'";
        assertEquals(expectedMessage, error);
    }

}