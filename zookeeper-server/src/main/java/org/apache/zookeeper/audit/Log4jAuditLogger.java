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

import org.apache.zookeeper.audit.AuditEvent.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Log4j based audit logger
 */
public class Log4jAuditLogger implements AuditLogger {
    private static final Logger LOG = LoggerFactory.getLogger(Log4jAuditLogger.class);

    @Override
    public void logAuditEvent(AuditEvent auditEvent) {
        if (auditEvent.getResult() == Result.FAILURE) {
            LOG.error(auditEvent.toString());
        } else {
            LOG.info(auditEvent.toString());
        }
    }
}
