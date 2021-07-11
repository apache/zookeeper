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

package org.apache.zookeeper.log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.apache.commons.io.IOUtils;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Log4jConfigWatcherTest {
    private static String testLogFilePath;
    private static final String zookeeperLogLevel = "zookeeper.root.logger=";
    private static final String LOG_4_J_WATCHER_LOG_DIR = "log4j.watcher.log.dir";

    @Before
    public void setUp() throws Exception {
        File tempDir = ClientBase.createEmptyTestDir();
        testLogFilePath = tempDir.getPath() + "/zookeeper.log";
        System.setProperty(LOG_4_J_WATCHER_LOG_DIR, tempDir.getPath());
    }

    @After
    public void tearDown() throws Exception {
        System.clearProperty(Log4jConfigWatcher.LOG4J_CONFIGURATION_WATCH);
        System.clearProperty(Log4jConfigWatcher.LOG4J_CONFIGURATION);
        System.clearProperty(Log4jConfigWatcher.LOG4J_CONFIGURATION_WATCH_INTERVAL);
        System.clearProperty(LOG_4_J_WATCHER_LOG_DIR);
        File dir = new File(testLogFilePath);
        dir.delete();
    }

    @Test
    public void testLogLevelChangeWithoutRestartingJVM() throws Exception {
        System.setProperty(Log4jConfigWatcher.LOG4J_CONFIGURATION_WATCH, "true");
        System
            .setProperty(Log4jConfigWatcher.LOG4J_CONFIGURATION, "log4j-watcher-log4j.properties");
        System.setProperty(Log4jConfigWatcher.LOG4J_CONFIGURATION_WATCH_INTERVAL, "2000");
        Log4jConfigWatcher.watchLog4jConfiguration();

        // Changed the log level to WARN
        alterLogConfigFile("/" + System.getProperty(Log4jConfigWatcher.LOG4J_CONFIGURATION),
            zookeeperLogLevel, zookeeperLogLevel + "WARN, ROLLINGFILE");
        // Waiting for some time so configuration event is triggered and changes reloaded into memory
        Thread.sleep(5000);
        Logger LOG = LoggerFactory.getLogger(Log4jConfigWatcherTest.class);
        LOG.warn("Warn level log is present in initial Configuration");
        LOG.info("Info level log is present in initial Configuration");
        Assert.assertTrue(checkTargetInLogFile(testLogFilePath,
            "Warn level log is present in initial Configuration"));
        Assert.assertFalse(checkTargetInLogFile(testLogFilePath,
            "Info level log is present in initial Configuration"));

        // Changed the log level to INFO
        alterLogConfigFile("/" + System.getProperty(Log4jConfigWatcher.LOG4J_CONFIGURATION),
            zookeeperLogLevel, zookeeperLogLevel + "INFO, ROLLINGFILE");
        // Waiting for some time so configuration event is triggered and changes reloaded into memory
        Thread.sleep(5000);
        LOG.info("Info level log is present after altering the Configuration");
        Assert.assertTrue(checkTargetInLogFile(testLogFilePath,
            "Info level log is present after altering the Configuration"));
    }

    private void alterLogConfigFile(String log4jConfigurationFile, String key, String value)
        throws IOException {
        InputStream inputStream = null;
        BufferedWriter bw = null;
        try {
            // log4j configuration file is modified from classpath, not from the resource folder
            inputStream = getClass().getResourceAsStream(log4jConfigurationFile);
            List<String> lines = IOUtils.readLines(inputStream, "UTF-8");
            FileWriter fileWriter =
                new FileWriter(getClass().getResource(log4jConfigurationFile).getPath());
            bw = new BufferedWriter(fileWriter);
            for (String line : lines) {
                if (line.contains(key)) {
                    line = value;
                }
                bw.write(line);
                bw.newLine();
            }
            bw.flush();
        } finally {
            org.apache.zookeeper.common.IOUtils.closeStream(inputStream);
            org.apache.zookeeper.common.IOUtils.closeStream(bw);
        }
    }

    private Boolean checkTargetInLogFile(String fileName, String targetString) throws IOException {
        FileInputStream input = new FileInputStream(fileName);
        List<String> lines = IOUtils.readLines(input, "UTF-8");
        for (String line : lines) {
            if (line.contains(targetString)) {
                return true;
            }
        }
        org.apache.zookeeper.common.IOUtils.closeStream(input);
        return false;
    }
}
