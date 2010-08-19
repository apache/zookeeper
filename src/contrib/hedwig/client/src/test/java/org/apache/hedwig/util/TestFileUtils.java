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
package org.apache.hedwig.util;

import java.io.File;

import org.junit.Test;

import junit.framework.TestCase;

public class TestFileUtils extends TestCase {

    @Test
    public void testCreateTmpDirectory() throws Exception {
        String prefix = "abc";
        String suffix = "def";
        File dir = FileUtils.createTempDirectory(prefix, suffix);
        assertTrue(dir.isDirectory());
        assertTrue(dir.getName().startsWith(prefix));
        assertTrue(dir.getName().endsWith(suffix));
        FileUtils.dirDeleterThread.start();
        FileUtils.dirDeleterThread.join();
        assertFalse(dir.exists());
    }

}
