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

import org.apache.zookeeper.server.command.FourLetterCommands;
import org.apache.zookeeper.server.command.StatCommand;
import org.junit.Before;
import org.junit.Test;

import java.io.PrintWriter;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

public class StatCommandTest extends CommandTestCase {
    private StatCommand statCommand;

    @Before
    @Override
    public void setUp() {
        super.setUp();
        statCommand = new StatCommand(new PrintWriter(outputWriter), serverCnxnMock, FourLetterCommands.statCmd);
        statCommand.setZkServer(zks);
        statCommand.setFactory(serverCnxnFactory);
    }

    @Test
    public void testLeaderStatCommand() {
        // Arrange
        when(providerMock.getState()).thenReturn("leader");

        // Act
        statCommand.commandRun();

        // Assert
        String output = outputWriter.toString();
        assertCommonStrings(output);
        assertThat(output, containsString("Mode: leader"));
        assertThat(output, containsString("Proposal min/avg/max:"));
    }

    @Test
    public void testFollowerStatCommand() {
        // Arrange
        when(providerMock.getState()).thenReturn("follower");

        // Act
        statCommand.commandRun();

        // Assert
        String output = outputWriter.toString();
        assertCommonStrings(output);
        assertThat(output, containsString("Mode: follower"));
    }

    private void assertCommonStrings(String output) {
        assertThat(output, containsString("Clients:"));
        assertThat(output, containsString("Zookeeper version:"));
        assertThat(output, containsString("Node count:"));
    }
}
