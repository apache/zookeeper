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

import static org.junit.jupiter.api.Assertions.assertThrows;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.api.Test;

public class PathUtilsTest extends ZKTestCase {

    @Test
    public void testValidatePath_ValidPath() {
        PathUtils.validatePath("/this is / a valid/path");
    }

    @Test
    public void testValidatePath_Null() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath(null);
        });
    }

    @Test
    public void testValidatePath_EmptyString() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("");
        });
    }

    @Test
    public void testValidatePath_NotAbsolutePath() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("not/valid");
        });
    }

    @Test
    public void testValidatePath_EndsWithSlash() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("/ends/with/slash/");
        });
    }

    @Test
    public void testValidatePath_ContainsNullCharacter() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("/test\u0000");
        });
    }

    @Test
    public void testValidatePath_DoubleSlash() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("/double//slash");
        });
    }

    @Test
    public void testValidatePath_SinglePeriod() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("/single/./period");
        });
    }

    @Test
    public void testValidatePath_DoublePeriod() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("/double/../period");
        });
    }

    @Test
    public void testValidatePath_NameContainingPeriod() {
        // A period that isn't on its own is ok
        PathUtils.validatePath("/name/with.period.");
    }

    @Test
    public void testValidatePath_0x01() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("/test\u0001");
        });
    }

    @Test
    public void testValidatePath_0x1F() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("/test\u001F");
        });
    }

    @Test // The first allowable character
    public void testValidatePath_0x20() {
        PathUtils.validatePath("/test\u0020");
    }

    @Test
    public void testValidatePath_0x7e() {
        // The last valid ASCII character
        PathUtils.validatePath("/test\u007e");
    }

    @Test
    public void testValidatePath_0x7f() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("/test\u007f");
        });
    }

    @Test
    public void testValidatePath_0x9f() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("/test\u009f");
        });
    }

    @Test
    public void testValidatePath_ud800() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("/test\ud800");
        });
    }

    @Test
    public void testValidatePath_uf8ff() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("/test\uf8ff");
        });
    }

    @Test
    public void testValidatePath_HighestAllowableChar() {
        PathUtils.validatePath("/test\uffef");
    }

    @Test
    public void testValidatePath_SupplementaryChar() {
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.validatePath("/test\ufff0");
        });
    }

}
