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

package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.server.command.MonitorCommand;
import org.junit.Before;
import org.junit.Test;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.hasKey;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

public class MonitorCommandTest extends CommandTest {
    private MonitorCommand monitorCommand;

    @Before
    @Override
    public void setUp() {
        super.setUp();
        monitorCommand = new MonitorCommand(new PrintWriter(outputWriter), serverCnxnMock);
        monitorCommand.setZkServer(zks);
        monitorCommand.setFactory(serverCnxnFactory);
    }

    @Test
    public void testLeaderMonitorCommand() {
        // Arrange
        when(providerMock.getState()).thenReturn("leader");

        // Act
        monitorCommand.commandRun();

        // Assert
        String output = outputWriter.toString();
        Map<String, String> monitors = parseMonitors(output);
        assertThat(monitors, hasKey("zk_followers"));
    }

    @Test
    public void testProposalStats() {
        // Arrange
        when(providerMock.getState()).thenReturn("leader");

        // Act
        monitorCommand.commandRun();

        // Assert
        String output = outputWriter.toString();
        Map<String, String> monitors = parseMonitors(output);

        assertThat(monitors, hasKey("zk_min_proposal"));
        assertThat(monitors, hasKey("zk_max_proposal"));
        assertThat(monitors, hasKey("zk_avg_proposal"));
    }

    private Map<String, String> parseMonitors(String output) {
        String[] lines = output.split("\n");
        Map<String, String> monitors = new HashMap<>();
        for (String l : lines) {
            String[] item = l.split("\t");
            monitors.put(item[0], item[1]);
        }
        return monitors;
    }
}
