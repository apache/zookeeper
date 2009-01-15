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

package org.apache.zookeeper.jmx;

import java.lang.management.ManagementFactory;
import java.util.Enumeration;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.jmx.HierarchyDynamicMBean;
import org.apache.log4j.spi.LoggerRepository;

/**
 * Shared utilities
 */
public class ManagedUtil {
    /**
     * Register the log4j JMX mbeans. Set environment variable
     * "zookeeper.jmx.log4j.disable" to true to disable registration.
     * @see http://logging.apache.org/log4j/1.2/apidocs/index.html?org/apache/log4j/jmx/package-summary.html
     * @throws JMException if registration fails
     */
    public static void registerLog4jMBeans() throws JMException {
        if (Boolean.getBoolean("zookeeper.jmx.log4j.disable") == true) {
            return;
        }
        
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

        // Create and Register the top level Log4J MBean
        HierarchyDynamicMBean hdm = new HierarchyDynamicMBean();

        ObjectName mbo = new ObjectName("log4j:hiearchy=default");
        mbs.registerMBean(hdm, mbo);

        // Add the root logger to the Hierarchy MBean
        Logger rootLogger = Logger.getRootLogger();
        hdm.addLoggerMBean(rootLogger.getName());

        // Get each logger from the Log4J Repository and add it to
        // the Hierarchy MBean created above.
        LoggerRepository r = LogManager.getLoggerRepository();
        Enumeration enumer = r.getCurrentLoggers();
        Logger logger = null;

        while (enumer.hasMoreElements()) {
           logger = (Logger) enumer.nextElement();
           hdm.addLoggerMBean(logger.getName());
        }
    }

}
