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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.apache.zookeeper.ZKTestCase;
import org.junit.After;
import org.junit.Before;

public class ControllerTestBase extends ZKTestCase {

    protected ControllerService controllerService;
    protected CommandClient commandClient;
    private File tempDirectory;
    protected ControllerServerConfig config;

    @Before
    public void init() throws Exception {
        List<Integer> openPorts = ControllerConfigTest.findNAvailablePorts(2);
        File tmpFile = File.createTempFile("test", ".junit", testBaseDir);
        tempDirectory = new File(tmpFile + ".dir");
        assertFalse(tempDirectory.exists());
        assertTrue(tempDirectory.mkdirs());

        config = new ControllerServerConfig(openPorts.get(0), openPorts.get(1), tempDirectory.getAbsolutePath());
        controllerService = new ControllerService();
        controllerService.start(config);

        int retries = 50;
        // The controller needs to hold an election before it is ready to process requests.
        // Busy-wait until its ready...
        while (!controllerService.isReady()) {
            Thread.sleep(100);
            retries--;
            if (retries < 0) {
                throw new TimeoutException("Service didn't start up and finish elections.");
            }
        }

        // Create a client which sends requests to localhost on the configured port.
        commandClient = new CommandClient(config.getControllerAddress().getPort());
    }

    @After
    public void cleanup() throws InterruptedException {
        if (controllerService != null) {
            controllerService.shutdown();
        }

        if (commandClient != null) {
            commandClient.close();
        }

        if (tempDirectory != null) {
            tempDirectory.delete();
        }
    }
}
