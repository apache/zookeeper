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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.apache.zookeeper.Login;
import org.apache.zookeeper.audit.AuditContextTestSupport.FailingAuditLogger;
import org.apache.zookeeper.audit.AuditContextTestSupport.RecordingAuditLogger;
import org.apache.zookeeper.audit.AuditEvent.FieldName;
import org.apache.zookeeper.audit.AuditEvent.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class AuditContextTest {

    @Test
    public void testEventFieldsAndSanitization() {
        List<AuditEvent> events = new ArrayList<>();
        AuditContext context = new AuditContext(events::add);

        context.log("user\tresult=failure\n", AuditConstants.OP_LOGIN, Result.SUCCESS);
        context.log("user", AuditConstants.OP_CREATE, "/node\r\n", null,
            "persistent", "0x1", "127.0.0.1", Result.FAILURE);

        assertTrue(context.isEnabled());
        assertNotSame(events.get(0), events.get(1));
        assertEquals("user=userresult=failure\toperation=login\tresult=success", events.get(0).toString());
        assertEquals("session=0x1\tuser=user\tip=127.0.0.1\toperation=create"
            + "\tznode=/node\tznode_type=persistent\tresult=failure", events.get(1).toString());
        assertEquals(Result.FAILURE, events.get(1).getResult());
    }

    @Test
    public void testDisabledContextIgnoresEvents() {
        AuditContext context = AuditContext.disabled();

        assertFalse(context.isEnabled());
        context.log(null, null, null);
        context.log(null, null, null, null, null, null, null, null);
        assertNotSame(context, AuditContext.disabled());
        assertThrows(NullPointerException.class, () -> new AuditContext(null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testWriteFailuresRemainVisibleToCaller(boolean linkageFailure) {
        RuntimeException runtimeFailure = new IllegalStateException("audit write failed");
        LinkageError linkageError = new NoClassDefFoundError("audit backend dependency");
        AuditContext context = new AuditContext(event -> {
            if (linkageFailure) {
                throw linkageError;
            }
            throw runtimeFailure;
        });

        if (linkageFailure) {
            assertSame(linkageError, assertThrows(LinkageError.class,
                () -> context.log("user", AuditConstants.OP_LOGIN, Result.SUCCESS)));
            assertSame(linkageError, assertThrows(LinkageError.class,
                () -> context.log("user", AuditConstants.OP_CREATE, "/node", null, null, null, null, Result.SUCCESS)));
        } else {
            assertSame(runtimeFailure, assertThrows(RuntimeException.class,
                () -> context.log("user", AuditConstants.OP_LOGIN, Result.SUCCESS)));
            assertSame(runtimeFailure, assertThrows(RuntimeException.class,
                () -> context.log("user", AuditConstants.OP_CREATE, "/node", null, null, null, null, Result.SUCCESS)));
        }
    }

    @Test
    public void testContextsOwnIndependentBackendsAndServerIdentities() {
        RecordingAuditLogger.instances.clear();
        AuditContext first = AuditContext.create(true, RecordingAuditLogger.class.getName());
        AuditContext second = AuditContext.create(true, RecordingAuditLogger.class.getName());
        first.setServerUser("server-one");
        second.setServerUser("server-two");

        assertNotSame(first, second);
        assertEquals(2, RecordingAuditLogger.instances.size());
        assertEquals("server-one", first.getServerUser());
        assertEquals("server-two", second.getServerUser());
        first.log(first.getServerUser(), AuditConstants.OP_START, Result.SUCCESS);
        second.log(second.getServerUser(), AuditConstants.OP_START, Result.SUCCESS);

        assertEquals(1, RecordingAuditLogger.instances.get(0).initializations);
        assertEquals(1, RecordingAuditLogger.instances.get(1).initializations);
        assertEquals("server-one", RecordingAuditLogger.instances.get(0).events.get(0).getValue(FieldName.USER));
        assertEquals("server-two", RecordingAuditLogger.instances.get(1).events.get(0).getValue(FieldName.USER));
        first.setServerUser("replacement");
        assertEquals("replacement", first.getServerUser());
        assertEquals("server-two", second.getServerUser());

        AuditContext disabledOne = AuditContext.disabled();
        AuditContext disabledTwo = AuditContext.disabled();
        disabledOne.setServerUser("disabled-one");
        assertEquals("disabled-one", disabledOne.getServerUser());
        assertEquals(Login.SYSTEM_USER, disabledTwo.getServerUser());
    }

    @Test
    public void testInitializationFailuresDoNotPoisonOtherRuntimes() {
        assertInitializationFailure(FailingAuditLogger.class, FailingAuditLogger.FAILURE);
        assertInitializationFailure(FailingConstructorAuditLogger.class, FailingConstructorAuditLogger.failure);
        assertInitializationFailure(LinkageFailingAuditLogger.class, LinkageFailingAuditLogger.failure);

        IllegalStateException missing = assertThrows(IllegalStateException.class,
            () -> AuditContext.create(true, "missing.AuditLogger"));
        assertTrue(missing.getMessage().contains("missing.AuditLogger"));
        assertTrue(missing.getCause() instanceof ClassNotFoundException);
        assertFalse(AuditContext.create(false, "missing.AuditLogger").isEnabled());

        RecordingAuditLogger.instances.clear();
        AuditContext healthy = AuditContext.create(true, RecordingAuditLogger.class.getName());
        healthy.log("healthy", AuditConstants.OP_LOGIN, Result.SUCCESS);
        assertTrue(healthy.isEnabled());
        assertEquals(1, RecordingAuditLogger.instances.size());
        assertEquals("healthy", RecordingAuditLogger.instances.get(0).events.get(0).getValue(FieldName.USER));
    }

    private static void assertInitializationFailure(Class<? extends AuditLogger> loggerClass, Throwable cause) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> AuditContext.create(true, loggerClass.getName()));
        assertTrue(failure.getMessage().contains(loggerClass.getName()));
        assertSame(cause, failure.getCause());
    }

    public static class FailingConstructorAuditLogger implements AuditLogger {

        private static final IllegalArgumentException failure =
            new IllegalArgumentException("audit construction failed");

        public FailingConstructorAuditLogger() {
            throw failure;
        }

        @Override
        public void logAuditEvent(AuditEvent event) {
            throw new AssertionError("uninitialized backend used");
        }
    }

    public static class LinkageFailingAuditLogger implements AuditLogger {

        private static final LinkageError failure = new NoClassDefFoundError("audit backend dependency");

        @Override
        public void initialize() {
            throw failure;
        }

        @Override
        public void logAuditEvent(AuditEvent event) {
            throw new AssertionError("uninitialized backend used");
        }
    }

}
