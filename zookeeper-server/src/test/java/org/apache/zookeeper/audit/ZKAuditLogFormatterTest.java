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
package org.apache.zookeeper.audit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ZKAuditLogFormatterTest {

    @Test
    public void testFormat() {
        ZKAuditLogFormatter formatter = new ZKAuditLogFormatter();
        formatter.addField("k1", "Value1");
        formatter.addField("k2", "Value2");
        String actual = formatter.format();
        String expected = "k1=Value1\tk2=Value2";
        assertEquals(expected, actual);
    }

    @Test
    public void testFormatShouldIgnoreKeyIfValueIsNull() {
        ZKAuditLogFormatter formatter = new ZKAuditLogFormatter();
        formatter.addField("k1", null);
        formatter.addField("k2", "Value2");
        String actual = formatter.format();
        String expected = "k2=Value2";
        assertEquals(expected, actual);
    }
}
