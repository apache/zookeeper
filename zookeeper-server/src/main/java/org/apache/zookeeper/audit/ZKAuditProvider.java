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
import java.lang.reflect.Constructor;
import org.apache.zookeeper.audit.AuditEvent.Result;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZKAuditProvider {
    /**
     * System property that enables audit logging.
     */
    public static final String AUDIT_ENABLE = "zookeeper.audit.enable";
    static final String AUDIT_IMPL_CLASS = "zookeeper.audit.impl.class";
    private static final Logger LOG = LoggerFactory.getLogger(ZKAuditProvider.class);
    private static String selectedAuditLoggerClass;

    // Parsing server configuration must not initialize the audit logger.
    private static class AuditState {
        private static final boolean auditEnabled;
        private static final AuditLogger auditLogger;
        private static final RuntimeException initializationFailure;

        static {
            String loggerClass = null;
            synchronized (ZKAuditProvider.class) {
                auditEnabled = Boolean.getBoolean(AUDIT_ENABLE);
                if (auditEnabled) {
                    loggerClass = System.getProperty(AUDIT_IMPL_CLASS, Slf4jAuditLogger.class.getName());
                    selectedAuditLoggerClass = loggerClass;
                }
            }
            AuditLogger logger = null;
            RuntimeException failure = null;
            if (auditEnabled) {
                try {
                    logger = getAuditLogger(loggerClass);
                    LOG.info("ZooKeeper audit is enabled.");
                } catch (RuntimeException | LinkageError e) {
                    failure = new IllegalStateException("Couldn't initialize audit logger " + loggerClass, e);
                }
            } else {
                LOG.info("ZooKeeper audit is disabled.");
            }
            auditLogger = logger;
            initializationFailure = failure;
        }
    }

    /**
     * Configures the audit logger class without initializing the logger.
     *
     * @throws IllegalArgumentException if another audit logger class has already been selected
     */
    public static synchronized void configureAuditLogger(String loggerClass) {
        if (selectedAuditLoggerClass != null && !selectedAuditLoggerClass.equals(loggerClass)) {
            throw new IllegalArgumentException("Audit logger is already selected as " + selectedAuditLoggerClass
                + "; cannot configure " + loggerClass + ". Set " + AUDIT_IMPL_CLASS + " before using the audit provider.");
        }
        System.setProperty(AUDIT_IMPL_CLASS, loggerClass);
    }

    private static AuditLogger getAuditLogger(String auditLoggerClass) {
        try {
            Constructor<?> clientCxnConstructor = Class.forName(auditLoggerClass)
                    .getDeclaredConstructor();
            AuditLogger auditLogger = (AuditLogger) clientCxnConstructor.newInstance();
            auditLogger.initialize();
            return auditLogger;
        } catch (Exception e) {
            throw new RuntimeException("Couldn't instantiate " + auditLoggerClass, e);
        }
    }

    /**
     * @return true if audit log is enabled
     * @throws IllegalStateException if the audit logger could not be initialized
     */
    public static boolean isAuditEnabled() {
        if (AuditState.initializationFailure != null) {
            throw AuditState.initializationFailure;
        }
        return AuditState.auditEnabled;
    }

    public static void log(String user, String operation, String znode, String acl,
                           String createMode, String session, String ip, Result result) {
        if (isAuditEnabled()) {
            AuditState.auditLogger.logAuditEvent(createLogEvent(user, operation, znode, acl, createMode, session, ip, result));
        }
    }

    /**
     * A helper api for creating an AuditEvent object.
     */
    static AuditEvent createLogEvent(String user, String operation, Result result) {
        AuditEvent event = new AuditEvent(result);
        event.addEntry(FieldName.USER, user);
        event.addEntry(FieldName.OPERATION, operation);
        return event;
    }

    /**
     * A helper api for creating an AuditEvent object.
     */
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

    /**
     * Add audit log for server start and register server stop log.
     */
    public static void addZKStartStopAuditLog() {
        if (isAuditEnabled()) {
            log(getZKUser(), AuditConstants.OP_START, Result.SUCCESS);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log(getZKUser(), AuditConstants.OP_STOP, Result.INVOKED);
            }));
        }
    }

    /**
     * Add audit log for server start fail.
     * Audit errors must not prevent the server from exiting with its intended code.
     */
    public static void addServerStartFailureAuditLog() {
        try {
            if (isAuditEnabled()) {
                log(ZKAuditProvider.getZKUser(), AuditConstants.OP_START, Result.FAILURE);
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Unable to audit server startup failure", e);
        }
    }

    /**
     * Logs an operation without session, IP, or znode fields when auditing is enabled.
     */
    public static void log(String user, String operation, Result result) {
        if (isAuditEnabled()) {
            AuditState.auditLogger.logAuditEvent(createLogEvent(user, operation, result));
        }
    }

    /**
     * User who has started the ZooKeeper server user, it will be the logged-in
     * user. If no user logged-in then system user.
     */
    public static String getZKUser() {
        return ServerCnxnFactory.getUserName();
    }

}
