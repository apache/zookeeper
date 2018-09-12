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

import org.apache.zookeeper.server.ServerCnxnFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZKAuditLogger {
    public static final String SYSPROP_AUDIT_ENABLE = "zookeeper.audit.enable";
    private static final Logger LOG = LoggerFactory.getLogger(ZKAuditLogger.class);
    // By default audit logging is disabled
    private static boolean auditEnabled = Boolean.getBoolean(SYSPROP_AUDIT_ENABLE);

    /**
     * @return true if audit log is enabled
     */
    public static boolean isAuditEnabled() {
        return auditEnabled;
    }

    // @VisisbleForTesting
    public static void setAuditEnabled(boolean auditEnabled) {
        ZKAuditLogger.auditEnabled = auditEnabled;
    }

    /**
     *
     * Prints audit log based on log level specified
     *
     */
    public static enum LogLevel {
        ERROR {
            @Override
            public void printLog(String logMsg) {
                LOG.error(logMsg);
            }
        },
        INFO {
            @Override
            public void printLog(String logMsg) {
                LOG.info(logMsg);
            }
        };
        public abstract void printLog(String logMsg);
    }

    public static enum Keys {
        USER, OPERATION, RESULT, IP, ACL, ZNODE, SESSION;
    }

    public static void logInvoked(String user, String operation) {
        log(LogLevel.INFO, user, operation, AuditConstants.INVOKED);
    }

    public static void logSuccess(String user, String operation) {
        log(LogLevel.INFO, user, operation, AuditConstants.SUCCESS);
    }

    public static void logFailure(String user, String operation) {
        log(LogLevel.ERROR, user, operation, AuditConstants.FAILURE);
    }

    private static void log(LogLevel level, String user, String operation, String logType) {
        level.printLog(createLog(user, operation, null, null, null, null, logType));
    }

    public static void logSuccess(String user, String operation, String znode, String acl,
            String session, String ip) {
        LogLevel.INFO.printLog(
                createLog(user, operation, znode, acl, session, ip, AuditConstants.SUCCESS));
    }

    public static void logFailure(String user, String operation, String znode, String acl,
            String session, String ip) {
        LogLevel.ERROR.printLog(
                createLog(user, operation, znode, acl, session, ip, AuditConstants.FAILURE));
    }

    /**
     * A helper api for creating an audit log string.
     */
    public static String createLog(String user, String operation, String znode, String acl,
            String session, String ip, String status) {
        ZKAuditLogFormatter fmt = new ZKAuditLogFormatter();
        fmt.addField(Keys.SESSION.name(), session);
        fmt.addField(Keys.USER.name(), user);
        fmt.addField(Keys.IP.name(), ip);
        fmt.addField(Keys.OPERATION.name(), operation);
        fmt.addField(Keys.ZNODE.name(), znode);
        fmt.addField(Keys.ACL.name(), acl);
        fmt.addField(Keys.RESULT.name(), status);
        return fmt.format();
    }

    /**
     * add audit log for server start and register server stop log
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
     * User who has started the ZooKeeper server user, it will be the logged-in
     * user. If no user logged-in then system user
     */
    public static String getZKUser() {
        return ServerCnxnFactory.getUserName();
    }
}
