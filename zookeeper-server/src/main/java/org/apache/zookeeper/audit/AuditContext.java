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

import static org.apache.zookeeper.audit.AuditEvent.FieldName;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.Login;
import org.apache.zookeeper.audit.AuditEvent.Result;
import org.apache.zookeeper.common.ZKConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An initialized audit backend owned by a client or server runtime.
 */
@InterfaceAudience.Private
public final class AuditContext {

    private static final Logger LOG = LoggerFactory.getLogger(AuditContext.class);
    private static final String SHUTDOWN_THREAD_NAME = "ZooKeeperAuditShutdown";
    private final AuditLogger auditLogger;
    private volatile String serverUser = Login.SYSTEM_USER;

    private AuditContext() {
        auditLogger = null;
    }

    AuditContext(AuditLogger auditLogger) {
        this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger");
    }

    /**
     * Creates and initializes the backend for one runtime.
     *
     * @throws IllegalStateException if the enabled backend cannot be initialized
     */
    public static AuditContext create(boolean enabled, String loggerClass) {
        if (!enabled) {
            return disabled();
        }
        if (loggerClass == null) {
            loggerClass = Slf4jAuditLogger.class.getName();
        }
        try {
            AuditLogger logger = Class.forName(loggerClass)
                .asSubclass(AuditLogger.class).getDeclaredConstructor().newInstance();
            logger.initialize();
            return new AuditContext(logger);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Couldn't initialize audit logger " + loggerClass, e.getCause());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new IllegalStateException("Couldn't initialize audit logger " + loggerClass, e);
        }
    }

    public static AuditContext fromSystemProperties() {
        return create(Boolean.getBoolean(ZKConfig.AUDIT_ENABLE),
            System.getProperty(ZKConfig.AUDIT_IMPL_CLASS, Slf4jAuditLogger.class.getName()));
    }

    public static AuditContext disabled() {
        return new AuditContext();
    }

    /**
     * Records the identity of the owning server's local JAAS login.
     * Keeping only the name allows shutdown auditing without retaining the server.
     */
    public void setServerUser(String user) {
        serverUser = user == null ? Login.SYSTEM_USER : user;
    }

    public String getServerUser() {
        return serverUser;
    }

    /**
     * Binds a previously unconfigured component, rejecting replacement of a live backend.
     * Disabled contexts are interchangeable, but the original binding is retained.
     */
    public static AuditContext bind(AuditContext current, AuditContext context) {
        Objects.requireNonNull(context, "auditContext");
        if (current == null || current == context) {
            return context;
        }
        if (!current.isEnabled() && !context.isEnabled()) {
            return current;
        }
        throw new IllegalStateException("Audit context is already configured");
    }

    /** Records startup; serverStop continues to describe JVM shutdown. */
    public void logServerStart() {
        if (isEnabled()) {
            log(getServerUser(), AuditConstants.OP_START, Result.SUCCESS);
            Runtime.getRuntime().addShutdownHook(new Thread(
                () -> log(getServerUser(), AuditConstants.OP_STOP, Result.INVOKED), SHUTDOWN_THREAD_NAME));
        }
    }

    /** Audit write failures must not replace the intended startup exit code. */
    public void logServerStartFailure() {
        try {
            log(getServerUser(), AuditConstants.OP_START, Result.FAILURE);
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Unable to audit server startup failure", e);
        }
    }

    public boolean isEnabled() {
        return auditLogger != null;
    }

    public void log(String user, String operation, Result result) {
        if (isEnabled()) {
            auditLogger.logAuditEvent(createLogEvent(user, operation, result));
        }
    }

    public void log(String user, String operation, String znode, String acl,
                    String createMode, String session, String ip, Result result) {
        if (isEnabled()) {
            auditLogger.logAuditEvent(createLogEvent(user, operation, znode, acl, createMode, session, ip, result));
        }
    }

    static AuditEvent createLogEvent(String user, String operation, Result result) {
        AuditEvent event = new AuditEvent(result);
        event.addEntry(FieldName.USER, user);
        event.addEntry(FieldName.OPERATION, operation);
        return event;
    }

    static AuditEvent createLogEvent(String user, String operation, String znode, String acl,
                                     String createMode, String session, String ip, Result result) {
        AuditEvent event = new AuditEvent(result);
        event.addEntry(FieldName.SESSION, session);
        event.addEntry(FieldName.USER, user);
        event.addEntry(FieldName.IP, ip);
        event.addEntry(FieldName.OPERATION, operation);
        event.addEntry(FieldName.ZNODE, znode);
        event.addEntry(FieldName.ZNODE_TYPE, createMode);
        event.addEntry(FieldName.ACL, acl);
        return event;
    }

}
