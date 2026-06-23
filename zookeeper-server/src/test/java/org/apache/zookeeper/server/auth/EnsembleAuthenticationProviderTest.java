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
package org.apache.zookeeper.server.auth;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.test.LoggerTestTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class EnsembleAuthenticationProviderTest {
    private static LoggerTestTool loggerTestTool;

    @BeforeAll
    public static void setupBeforeClass() {
        loggerTestTool = new LoggerTestTool(EnsembleAuthenticationProvider.class, Level.INFO);
    }

    @AfterAll
    public static void tearDownAfterClass() throws Exception {
        loggerTestTool.close();
    }

    @Test
    public void testLogForgeryWithSpecialCharacters() throws IOException {
        ServerCnxn mockServerCnxn = mock(ServerCnxn.class);
        InetSocketAddress mockAddress = new InetSocketAddress("127.0.0.1", 1234);
        doReturn(mockAddress).when(mockServerCnxn).getRemoteSocketAddress();

        EnsembleAuthenticationProvider provider = new EnsembleAuthenticationProvider();
        provider.setEnsembleNames("test-ensemble");

        byte[] authData = "andor-ensemble\nTHIS SHOULD\t NOT\r BE HERE".getBytes(StandardCharsets.UTF_8);
        KeeperException.Code err = provider.handleAuthentication(mockServerCnxn, authData);
        String logLine = loggerTestTool.readLogLine("andor-ensemble");
        Assertions.assertTrue(logLine.contains("THIS SHOULD NOT BE HERE"), "Log line doesn't contain the entire ensemble name. Forged?");

        Assertions.assertEquals(KeeperException.Code.BADARGUMENTS, err);
    }
}
