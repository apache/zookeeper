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

import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZKDatabase;
import org.junit.Before;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public abstract class CommandTest {
    ServerCnxn serverCnxnMock;
    StringWriter outputWriter;
    ServerStats.Provider providerMock;
    LeaderZooKeeperServer zks;
    ServerCnxnFactory serverCnxnFactory;

    @Before
    public void setUp() {
        outputWriter = new StringWriter();
        serverCnxnMock = mock(ServerCnxn.class);

        zks = mock(LeaderZooKeeperServer.class);
        when(zks.isRunning()).thenReturn(true);
        providerMock = mock(ServerStats.Provider.class);
        when(zks.serverStats()).thenReturn(new ServerStats(providerMock));
        ZKDatabase zkDatabaseMock = mock(ZKDatabase.class);
        when(zks.getZKDatabase()).thenReturn(zkDatabaseMock);
        DataTree dataTreeMock = mock(DataTree.class);
        when(zkDatabaseMock.getDataTree()).thenReturn(dataTreeMock);
        Leader leaderMock = mock(Leader.class);
        when(leaderMock.getProposalStats()).thenReturn(new ProposalStats());
        when(zks.getLeader()).thenReturn(leaderMock);

        serverCnxnFactory = mock(ServerCnxnFactory.class);
        ServerCnxn serverCnxn = mock(ServerCnxn.class);
        List<ServerCnxn> connections = new ArrayList<>();
        connections.add(serverCnxn);
        when(serverCnxnFactory.getConnections()).thenReturn(connections);
    }
}
