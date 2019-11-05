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
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZKAuditLogger {
    public static final String SYSPROP_AUDIT_ENABLE = "zookeeper.audit.enable";
    public static final String SYSPROP_AUDIT_LOGGER_IMPL = "zookeeper.audit.impl.class";
    private static final Logger LOG = LoggerFactory.getLogger(ZKAuditLogger.class);
    // By default audit logging is disabled
    private static boolean auditEnabled = Boolean.getBoolean(SYSPROP_AUDIT_ENABLE);
    private static AuditLogger auditLogger;

    static {
        if (auditEnabled) {
            //initialise only when audit logging is enabled
            auditLogger = getAuditLogger();
            LOG.info("ZooKeeper audit is enabled.");
        } else {
            LOG.info("ZooKeeper audit is disabled.");
        }
    }

    private static AuditLogger getAuditLogger() {
        String auditLoggerClass = System.getProperty(SYSPROP_AUDIT_LOGGER_IMPL);
        if (auditLoggerClass == null) {
            auditLoggerClass = Log4jAuditLogger.class.getName();
        }
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
     */
    public static boolean isAuditEnabled() {
        return auditEnabled;
    }

    // @VisibleForTesting
    public static void setAuditEnabled(boolean auditEnabled) {
        ZKAuditLogger.auditEnabled = auditEnabled;
    }

    public static void logSuccess(String user, String operation) {
        log(user, operation, AuditConstants.SUCCESS);
    }

    public static void logInvoked(String user, String operation) {
        log(user, operation, AuditConstants.INVOKED);
    }

    public static void logSuccess(String user, String operation, String znode, String acl,
                                  String createMode, String session, String ip) {
        log(user, operation, znode, acl, createMode, session, ip,
                AuditConstants.SUCCESS);
    }

    public static void logFailure(String user, String operation, String znode, String acl,
                                  String createMode, String session, String ip) {
        log(user, operation, znode, acl, createMode, session, ip,
                AuditConstants.FAILURE);
    }

    private static void log(String user, String operation, String result) {
        auditLogger.logAuditEvent(createLogEvent(user, operation, result));
    }

    private static void log(String user, String operation, String znode, String acl,
                            String createMode, String session, String ip, String result) {
        auditLogger.logAuditEvent(createLogEvent(user, operation, znode, acl, createMode, session, ip, result));
    }

    /**
     * A helper api for creating an AuditEvent object.
     */
    public static AuditEvent createLogEvent(String user, String operation, String result) {
        AuditEvent event = new AuditEvent();
        event.addEntry(FieldName.USER, user);
        event.addEntry(FieldName.OPERATION, operation);
        event.addEntry(FieldName.RESULT, result);
        return event;
    }


    /**
     * A helper api for creating an AuditEvent object.
     */
    public static AuditEvent createLogEvent(String user, String operation, String znode, String acl,
                                            String createMode, String session, String ip, String result) {
        AuditEvent event = new AuditEvent();
        event.addEntry(FieldName.SESSION, session);
        event.addEntry(FieldName.USER, user);
        event.addEntry(FieldName.IP, ip);
        event.addEntry(FieldName.OPERATION, operation);
        event.addEntry(FieldName.ZNODE, znode);
        event.addEntry(FieldName.ZNODE_TYPE, createMode);
        event.addEntry(FieldName.ACL, acl);
        event.addEntry(FieldName.RESULT, result);
        return event;
    }

    /**
     * Add audit log for server start and register server stop log.
     */
    public static void addZKStartStopAuditLog() {
        if (isAuditEnabled()) {
            ZKAuditLogger.logSuccess(getZKUser(), AuditConstants.OP_START);
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    ZKAuditLogger.logInvoked(getZKUser(), AuditConstants.OP_STOP);
                }
            });
        }
    }

    /**
     * Add audit log for server start fail.
     */
    public static void addServerStartFailureAuditLog() {
        if (isAuditEnabled()) {
            log(ZKAuditLogger.getZKUser(), AuditConstants.OP_START, AuditConstants.FAILURE);
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
