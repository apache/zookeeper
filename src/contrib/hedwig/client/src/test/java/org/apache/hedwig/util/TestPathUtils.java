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

import java.util.Arrays;

import junit.framework.TestCase;

import org.junit.Test;

public class TestPathUtils extends TestCase {

    @Test
    public void testPrefixes() {
        assertEquals(Arrays.asList(new String[] { "/a", "/a/b", "/a/b/c" }), PathUtils.prefixes("/a/b/c"));
        assertEquals(Arrays.asList(new String[] { "/a", "/a/b", "/a/b/c" }), PathUtils.prefixes("///a///b///c"));

    }

    @Test
    public void testIsPrefix() {
        String[] paths = new String[] { "/", "/a", "/a/b" };
        for (int i = 0; i < paths.length; i++) {
            for (int j = 0; j <= i; j++) {
                assertTrue(PathUtils.isPrefix(paths[j], paths[i]));
                assertTrue(PathUtils.isPrefix(paths[j], paths[i] + "/"));
                assertTrue(PathUtils.isPrefix(paths[j] + "/", paths[i]));
                assertTrue(PathUtils.isPrefix(paths[j] + "/", paths[i] + "/"));
            }
            for (int j = i + 1; j < paths.length; j++) {
                assertFalse(PathUtils.isPrefix(paths[j], paths[i]));
                assertFalse(PathUtils.isPrefix(paths[j], paths[i] + "/"));
                assertFalse(PathUtils.isPrefix(paths[j] + "/", paths[i]));
                assertFalse(PathUtils.isPrefix(paths[j] + "/", paths[i] + "/"));
            }
        }
    }

}
