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

package org.apache.zookeeper.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import ch.qos.logback.classic.Level;
import java.io.File;
import java.io.IOException;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.test.LoggerTestTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class ZKConfigTest {

    private final X509Util x509Util = new ClientX509Util();
    private static LoggerTestTool loggerTestTool;

    @BeforeAll
    public static void setUpBeforeClass() {
        loggerTestTool = new LoggerTestTool(ZKConfig.class, Level.DEBUG);
    }

    @AfterAll
    public static void tearDownAfterClass() throws Exception {
        loggerTestTool.close();
    }

    @AfterEach
    public void tearDown() throws Exception {
        System.clearProperty(x509Util.getSslProtocolProperty());
    }

    // property is not set we should get the default value
    @Test
    @Timeout(value = 10)
    public void testBooleanRetrievalFromPropertyDefault() {
        ZKConfig conf = new ZKConfig();
        String prop = "UnSetProperty" + System.currentTimeMillis();
        boolean defaultValue = false;
        boolean result = conf.getBoolean(prop, defaultValue);
        assertEquals(defaultValue, result);
    }

    // property is set to an valid boolean, we should get the set value
    @Test
    @Timeout(value = 10)
    public void testBooleanRetrievalFromProperty() {
        boolean value = true;
        boolean defaultValue = false;
        System.setProperty(x509Util.getSslProtocolProperty(), Boolean.toString(value));
        ZKConfig conf = new ZKConfig();
        boolean result = conf.getBoolean(x509Util.getSslProtocolProperty(), defaultValue);
        assertEquals(value, result);
    }

    // property is set but with white spaces in the beginning
    @Test
    @Timeout(value = 10)
    public void testBooleanRetrievalFromPropertyWithWhitespacesInBeginning() {
        boolean value = true;
        boolean defaultValue = false;
        System.setProperty(x509Util.getSslProtocolProperty(), " " + value);
        ZKConfig conf = new ZKConfig();
        boolean result = conf.getBoolean(x509Util.getSslProtocolProperty(), defaultValue);
        assertEquals(value, result);
    }

    // property is set but with white spaces at the end
    @Test
    @Timeout(value = 10)
    public void testBooleanRetrievalFromPropertyWithWhitespacesAtEnd() {
        boolean value = true;
        boolean defaultValue = false;
        System.setProperty(x509Util.getSslProtocolProperty(), value + " ");
        ZKConfig conf = new ZKConfig();
        boolean result = conf.getBoolean(x509Util.getSslProtocolProperty(), defaultValue);
        assertEquals(value, result);
    }

    // property is set but with white spaces at the beginning and the end
    @Test
    @Timeout(value = 10)
    public void testBooleanRetrievalFromPropertyWithWhitespacesAtBeginningAndEnd() {
        boolean value = true;
        boolean defaultValue = false;
        System.setProperty(x509Util.getSslProtocolProperty(), " " + value + " ");
        ZKConfig conf = new ZKConfig();
        boolean result = conf.getBoolean(x509Util.getSslProtocolProperty(), defaultValue);
        assertEquals(value, result);
    }

    @Test
    public void testLogRedactorFromConfigFile() throws IOException, QuorumPeerConfig.ConfigException {
        // Arrange
        File configFile = new File("./src/test/resources/zookeeper-client.config");

        // Act
        new ZKConfig(configFile);

        // Assert
        String logLine = loggerTestTool.readLogLine("ZK Config");
        assertNotNull(logLine, "Unable to find ZK Config line in the logs");
        assertFalse(logLine.contains("FileSecret456!"), "Logs should not contain any secrets");
        assertFalse(logLine.contains("AnotherFileSecret789!"), "Logs should not contain any secrets");
        assertTrue(logLine.contains("/home/zookeeper/top_secret.txt"));      // what we shouldn't redact
    }

    @Test
    public void testLogRedactorInDebugLogs() throws IOException {
        // Arrange
        ZKConfig conf = new ZKConfig();

        // Act
        conf.setProperty("zookeeper.some.secret.password", "0ldP4ssw0rd");
        conf.setProperty("zookeeper.some.secret.password", "N3Ws3cr3t");

        // Assert
        String logLine = loggerTestTool.readLogLine("replaced with new value");
        assertNotNull(logLine, "Unable to find relevant line in the logs");
        assertFalse(logLine.contains("0ldP4ssw0rd"), "Logs should not contain any secrets");
        assertFalse(logLine.contains("N3Ws3cr3t"), "Logs should not contain any secrets");
    }

    @Test
    public void testDontRedactorInDebugLogs() throws IOException {
        // Arrange
        ZKConfig conf = new ZKConfig();

        // Act
        conf.setProperty("zookeeper.some.secret.passwordPath", "/home/zookeeper/old_secret.txt");  // what we shouldn't redact
        conf.setProperty("zookeeper.some.secret.passwordPath", "/home/zookeeper/new_secret.txt");  // what we shouldn't redact

        // Assert
        String logLine = loggerTestTool.readLogLine("replaced with new value");
        assertNotNull(logLine, "Unable to find relevant line in the logs");
        assertTrue(logLine.contains("/home/zookeeper/new_secret.txt"));
    }

}
