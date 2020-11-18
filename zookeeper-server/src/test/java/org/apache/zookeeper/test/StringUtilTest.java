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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.Arrays;
import java.util.Collections;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.common.StringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class StringUtilTest extends ZKTestCase {

    @Test
    public void testStringSplit() {
        final String s1 = "   a  ,   b  , ";
        assertEquals(Arrays.asList("a", "b"), StringUtils.split(s1, ","));

        assertEquals(Collections.emptyList(), StringUtils.split("", ","));

        final String s3 = "1, , 2";
        assertEquals(Arrays.asList("1", "2"), StringUtils.split(s3, ","));
    }

    @Test
    public void testStringJoinNullDelim() {
        Assertions.assertThrows(NullPointerException.class, () -> {
            StringUtils.joinStrings(Collections.emptyList(), null);
        });
    }

    @Test
    public void testStringJoinNullListNullDelim() {
        Assertions.assertThrows(NullPointerException.class, () -> {
            StringUtils.joinStrings(null, null);
        });
    }

    @Test
    public void testStringJoinNullList() {
        assertNull(StringUtils.joinStrings(null, ","));
    }

    @Test
    public void testStringJoin() {
        final String expected = "a,B,null,d";
        assertEquals(expected,
            StringUtils.joinStrings(Arrays.asList("a", "B", null, "d"), ","));
    }

}
