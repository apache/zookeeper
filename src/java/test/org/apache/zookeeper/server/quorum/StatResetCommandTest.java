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

import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.command.StatResetCommand;
import org.junit.Before;
import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.apache.zookeeper.server.command.AbstractFourLetterCommand.ZK_NOT_SERVING;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StatResetCommandTest {
    private StatResetCommand statResetCommand;
    private StringWriter outputWriter;
    private ZooKeeperServer zks;
    private ServerStats serverStats;

    @Before
    public void setUp() {
        outputWriter = new StringWriter();
        ServerCnxn serverCnxnMock = mock(ServerCnxn.class);

        zks = mock(ZooKeeperServer.class);
        when(zks.isRunning()).thenReturn(true);

        serverStats = mock(ServerStats.class);
        when(zks.serverStats()).thenReturn(serverStats);

        statResetCommand = new StatResetCommand(new PrintWriter(outputWriter), serverCnxnMock);
        statResetCommand.setZkServer(zks);
    }

    @Test
    public void testStatResetWithZKNotRunning() {
        // Arrange
        when(zks.isRunning()).thenReturn(false);

        // Act
        statResetCommand.commandRun();

        // Assert
        String output = outputWriter.toString();
        assertEquals(ZK_NOT_SERVING + "\n", output);
    }

    @Test
    public void testStatResetWithFollower() {
        // Arrange
        when(zks.isRunning()).thenReturn(true);
        when(serverStats.getServerState()).thenReturn("follower");

        // Act
        statResetCommand.commandRun();

        // Assert
        String output = outputWriter.toString();
        assertEquals("Server stats reset.\n", output);
        verify(serverStats, times(1)).reset();
    }

    @Test
    public void testStatResetWithLeader() {
        // Arrange
        LeaderZooKeeperServer leaderZks = mock(LeaderZooKeeperServer.class);
        when(leaderZks.isRunning()).thenReturn(true);
        when(leaderZks.serverStats()).thenReturn(serverStats);
        Leader leader = mock(Leader.class);
        when(leaderZks.getLeader()).thenReturn(leader);
        statResetCommand.setZkServer(leaderZks);

        when(serverStats.getServerState()).thenReturn("leader");

        ProposalStats proposalStats = mock(ProposalStats.class);
        when(leader.getProposalStats()).thenReturn(proposalStats);

        // Act
        statResetCommand.commandRun();

        // Assert
        String output = outputWriter.toString();
        assertEquals("Server stats reset.\n", output);
        verify(serverStats, times(1)).reset();
        verify(proposalStats, times(1)).reset();
    }
}
