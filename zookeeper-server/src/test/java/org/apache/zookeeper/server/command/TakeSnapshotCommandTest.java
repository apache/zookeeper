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

package org.apache.zookeeper.server.command;

import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.apache.zookeeper.server.command.AbstractFourLetterCommand.ZK_NOT_SERVING;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TakeSnapshotCommandTest {
    private TakeSnapshotCommand takeSnapshotCommand;
    private StringWriter outputWriter;
    private ZooKeeperServer zks;

    @BeforeEach
    public void setUp() {
        outputWriter = new StringWriter();
        ServerCnxn serverCnxnMock = mock(ServerCnxn.class);

        zks = mock(ZooKeeperServer.class);
        when(zks.isRunning()).thenReturn(true);

        takeSnapshotCommand = new TakeSnapshotCommand(new PrintWriter(outputWriter), serverCnxnMock);
        takeSnapshotCommand.setZkServer(zks);
    }

    @Test
    public void testTakeSnapshot() {
        // Arrange
        doNothing().when(zks).takeSnapshot();

        // Act
        takeSnapshotCommand.commandRun();

        // Assert
        String output = outputWriter.toString();
        assertEquals("Snapshot taken", output.trim());
    }

}