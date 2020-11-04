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

package org.apache.zookeeper.server.controller;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ControllerConfigTest {
    File configFile;

    private static final int AnyTickTime = 1234;
    private static final int AnyPort = 1234;
    private static final String AnyDataDir = "temp";

    public static File createTempFile() throws IOException {
        return File.createTempFile("temp", "cfg", new File(System.getProperty("user.dir")));
    }

    public static List<Integer> findNAvailablePorts(int n) throws IOException {
        List<ServerSocket> openedSockets = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            ServerSocket randomSocket = new ServerSocket(0);
            openedSockets.add(randomSocket);
            ports.add(randomSocket.getLocalPort());
        }

        for (ServerSocket s : openedSockets) {
            s.close();
        }

        return ports;
    }

    public static void writeRequiredControllerConfig(File file, int controllerPort, int zkServerPort, int adminServerPort) throws IOException {
        PrintWriter writer = new PrintWriter(file);
        writer.write("dataDir=anywhere\n");
        writer.write("controllerPort=" + controllerPort + "\n");
        writer.write("clientPort=" + zkServerPort + "\n");
        writer.write("adminPort=" + adminServerPort + "\n");
        writer.close();
    }

    @Before
    public void init() throws IOException {
        configFile = createTempFile();
    }

    private void writeFile(int portNumber) throws IOException {
        FileWriter writer = new FileWriter(configFile);
        writer.write("dataDir=somewhere\n");
        writer.write("ignore=me\n");
        writer.write("tickTime=" + AnyTickTime + "\n");
        writer.write("controllerPort=" + portNumber + "\n");
        writer.write("clientPort=" + portNumber + "\n");
        writer.flush();
        writer.close();
    }

    @After
    public void cleanup() {
        if (configFile != null) {
            configFile.delete();
        }
    }

    @Test
    public void parseFileSucceeds() throws Exception {
        writeFile(AnyPort);
        ControllerServerConfig config = new ControllerServerConfig(configFile.getAbsolutePath());
        Assert.assertEquals(AnyPort, config.getControllerAddress().getPort());
        Assert.assertEquals(AnyPort, config.getClientPortAddress().getPort());
        Assert.assertEquals(AnyTickTime, config.getTickTime());
    }

    @Test
    public void parseFileFailsWithMissingPort() throws Exception {
        FileWriter writer = new FileWriter(configFile);
        writer.write("dataDir=somewhere\n");
        writer.flush();
        writer.close();
        try {
            ControllerServerConfig config = new ControllerServerConfig(configFile.getAbsolutePath());
            Assert.fail("Should have thrown with missing server config");
        } catch (QuorumPeerConfig.ConfigException ex) {
        }
    }

    @Test public void parseMissingFileThrows() {
        try {
            ControllerServerConfig config = new ControllerServerConfig("DontLookHere.missing");
            Assert.fail("should have thrown");
        } catch (QuorumPeerConfig.ConfigException ex) {
        }
    }

    @Test
    public void parseInvalidPortThrows()throws QuorumPeerConfig.ConfigException {
        try {
            ControllerServerConfig config = new ControllerServerConfig(configFile.getAbsolutePath());
            Assert.fail("should have thrown");
        } catch (QuorumPeerConfig.ConfigException ex) {
        }
    }

    @Test
    public void validCtor() {
        ControllerServerConfig config = new ControllerServerConfig(AnyPort, AnyPort, AnyDataDir);
        Assert.assertEquals(AnyPort, config.getControllerAddress().getPort());
        Assert.assertEquals(AnyPort, config.getClientPortAddress().getPort());
        Assert.assertEquals(AnyDataDir, config.getDataDir().getName());
    }

    @Test
    public void invalidCtor() {
        try {
            ControllerServerConfig config = new ControllerServerConfig(-10, -10, "no where");
            Assert.fail("should have thrown");
        } catch (IllegalArgumentException ex) {
        }

    }
}
