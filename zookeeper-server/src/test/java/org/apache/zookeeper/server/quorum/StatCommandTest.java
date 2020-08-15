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

package org.apache.zookeeper.server.quorum;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.command.FourLetterCommands;
import org.apache.zookeeper.server.command.StatCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class StatCommandTest {

    private StringWriter outputWriter;
    private StatCommand statCommand;
    private ServerStats.Provider providerMock;

    @BeforeEach
    public void setUp() {
        outputWriter = new StringWriter();
        ServerCnxn serverCnxnMock = mock(ServerCnxn.class);

        LeaderZooKeeperServer zks = mock(LeaderZooKeeperServer.class);
        when(zks.isRunning()).thenReturn(true);
        providerMock = mock(ServerStats.Provider.class);
        when(zks.serverStats()).thenReturn(new ServerStats(providerMock));
        ZKDatabase zkDatabaseMock = mock(ZKDatabase.class);
        when(zks.getZKDatabase()).thenReturn(zkDatabaseMock);
        Leader leaderMock = mock(Leader.class);
        when(leaderMock.getProposalStats()).thenReturn(new BufferStats());
        when(zks.getLeader()).thenReturn(leaderMock);

        ServerCnxnFactory serverCnxnFactory = mock(ServerCnxnFactory.class);
        ServerCnxn serverCnxn = mock(ServerCnxn.class);
        List<ServerCnxn> connections = new ArrayList<>();
        connections.add(serverCnxn);
        when(serverCnxnFactory.getConnections()).thenReturn(connections);

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
        assertThat(output, containsString("Proposal sizes last/min/max:"));
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
