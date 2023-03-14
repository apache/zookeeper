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
package org.apache.zookeeper.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.apache.zookeeper.audit.AuditEvent.Result;
import org.junit.jupiter.api.Test;

public class AuditEventTest {

    @Test
    public void testFormat() {
        AuditEvent auditEvent = new AuditEvent(Result.SUCCESS);
        auditEvent.addEntry(AuditEvent.FieldName.USER, "Value1");
        auditEvent.addEntry(AuditEvent.FieldName.OPERATION, "Value2");
        String actual = auditEvent.toString();
        String expected = "user=Value1\toperation=Value2\tresult=success";
        assertEquals(expected, actual);
    }

    @Test
    public void testFormatShouldIgnoreKeyIfValueIsNull() {
        AuditEvent auditEvent = new AuditEvent(Result.SUCCESS);
        auditEvent.addEntry(AuditEvent.FieldName.USER, null);
        auditEvent.addEntry(AuditEvent.FieldName.OPERATION, "Value2");
        String actual = auditEvent.toString();
        String expected = "operation=Value2\tresult=success";
        assertEquals(expected, actual);
    }
}
