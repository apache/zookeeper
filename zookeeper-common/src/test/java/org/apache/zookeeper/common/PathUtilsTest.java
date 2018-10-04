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
import org.junit.Test;

public class PathUtilsTest extends ZKTestCase {

    @Test
    public void testValidatePath_ValidPath() {
        PathUtils.validatePath("/this is / a valid/path");
    }

    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_Null() {
        PathUtils.validatePath(null);
    }
    

    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_EmptyString() {
        PathUtils.validatePath("");
    }

    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_NotAbsolutePath() {
        PathUtils.validatePath("not/valid");
    }

    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_EndsWithSlash() {
        PathUtils.validatePath("/ends/with/slash/");
    }
    
    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_ContainsNullCharacter() {
        PathUtils.validatePath("/test\u0000");
    }
    
    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_DoubleSlash() {
        PathUtils.validatePath("/double//slash");
    }
    
    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_SinglePeriod() {
        PathUtils.validatePath("/single/./period");
    }
    
    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_DoublePeriod() {
        PathUtils.validatePath("/double/../period");
    }
    
    @Test
    public void testValidatePath_NameContainingPeriod() {
        // A period that isn't on its own is ok
        PathUtils.validatePath("/name/with.period.");
    }
    
    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_0x01() {
        PathUtils.validatePath("/test\u0001");
    }
    
    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_0x1F() {
        PathUtils.validatePath("/test\u001F");
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
    
    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_0x7f() {
        PathUtils.validatePath("/test\u007f");
    }
    
    @Test(expected=IllegalArgumentException.class) 
    public void testValidatePath_0x9f() {
        PathUtils.validatePath("/test\u009f");
    }
    
    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_ud800() {
        PathUtils.validatePath("/test\ud800");
    }

    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_uf8ff() {
        PathUtils.validatePath("/test\uf8ff");
    }
    
    @Test
    public void testValidatePath_HighestAllowableChar() {
        PathUtils.validatePath("/test\uffef");
    }
    
    @Test(expected=IllegalArgumentException.class)
    public void testValidatePath_SupplementaryChar() {
        PathUtils.validatePath("/test\ufff0");
    }

}
