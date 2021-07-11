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

package org.apache.zookeeper.server.persistence;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.util.AssertEqual;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.CreateSessionTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RocksDBSnapEndToEndTest extends ClientBase {
  private static final Logger LOG = LoggerFactory.getLogger(RocksDBSnapEndToEndTest.class);

  @Override
  public void setUp() {
  }

  @Test
  public void testCreateAndCloseSessionSuccess() throws Exception {
    // Use FileToRocksDBSnap to deserialize the snapshot from file systems.
    System.setProperty(SnapshotFactory.ZOOKEEPER_SNAPSHOT_NAME, FileToRocksDBSnap.class.getName());
    setUpWithServerId(1);
    ZooKeeperServer zks = serverFactory.getZooKeeperServer();

    // Take a snapshot in RocksDB.
    zks.takeSnapshot();
    stopServer();

    // Use RocksDB to apply transactions.
    System.setProperty(SnapshotFactory.ZOOKEEPER_SNAPSHOT_NAME, RocksDBSnap.class.getName());
    startServer();
    zks = serverFactory.getZooKeeperServer();

    long firstClientId = (long) 1;
    long secondClientId = (long) 2;
    long thirdClientId = (long) 3;
    // Process the CreateSessionTxn.
    TxnHeader txnHeader = new TxnHeader(firstClientId, 1414, 0, 55, ZooDefs.OpCode.createSession);
    CreateSessionTxn cst = new CreateSessionTxn(20000);
    zks.processTxn(txnHeader, cst); // request is null

    txnHeader = new TxnHeader(secondClientId, 1414, 1, 55, ZooDefs.OpCode.createSession);
    cst = new CreateSessionTxn(30000);
    zks.processTxn(txnHeader, cst); // request is null

    // create two nodes with the same clientID, they will be added to the same key under ephemerals
    txnHeader = new TxnHeader(secondClientId, 2, 2, 2, ZooDefs.OpCode.create);
    CreateTxn txn = new CreateTxn("/foo1", "data1".getBytes(StandardCharsets.UTF_8), ZooDefs.Ids.OPEN_ACL_UNSAFE, true, 1);
    zks.processTxn(txnHeader, txn); // request is null

    txnHeader = new TxnHeader(secondClientId, 3, 3, 3, ZooDefs.OpCode.create);
    txn = new CreateTxn("/foo2", "data2".getBytes(StandardCharsets.UTF_8), ZooDefs.Ids.OPEN_ACL_UNSAFE, true, 1);
    zks.processTxn(txnHeader, txn); // request is null

    // Create a node with a different clientID.
    txnHeader = new TxnHeader(thirdClientId, 4, 4, 4, ZooDefs.OpCode.create);
    txn = new CreateTxn("/foo3", "data3".getBytes(StandardCharsets.UTF_8), ZooDefs.Ids.OPEN_ACL_UNSAFE, true, 1);
    zks.processTxn(txnHeader, txn); // request is null

    // Close the session, so the session will be removed and the two nodes with the same clientID (ephemeralOwner)
    // will be deleted.
    txnHeader = new TxnHeader(secondClientId, 1414, 5, 55, ZooDefs.OpCode.closeSession);
    zks.processTxn(txnHeader, null); // request is null

    // This will flush instead of taking a full snapshot.
    zks.takeSnapshot();

    DataTree dataTree = zks.getZKDatabase().getDataTree();
    ConcurrentHashMap<Long, Integer> sessions = zks.getZKDatabase().getSessionWithTimeOuts();
    // Get a copy of the in memory session to compare with the sessions gotten from RocksDB snapshot.
    ConcurrentHashMap<Long, Integer> sessionCopy = new ConcurrentHashMap<Long, Integer>(sessions);

    stopServer();

    FileTxnSnapLog snapLogSpy = spy(new FileTxnSnapLog(tmpDir, tmpDir));
    ConcurrentHashMap<Long, Integer> dserSessions = new ConcurrentHashMap<>();
    DataTree dserTree = spy(new DataTree());
    FileTxnSnapLog.PlayBackListener listener = mock(FileTxnSnapLog.PlayBackListener.class);
    snapLogSpy.restore(dserTree, dserSessions, listener);

    Assert.assertTrue(dserSessions.size() == 1);
    Assert.assertTrue(dserSessions.get(firstClientId) == 20000);
    Assert.assertEquals(new String(dserTree.getNode("/foo3").getData(), StandardCharsets.UTF_8),
        "data3");
    // The node is deleted in closeSession.
    Assert.assertTrue(dserTree.getNode("/foo1") == null);
    Assert.assertTrue(dserTree.getNode("/foo2") == null);
    // Global sessions are the same.
    Assert.assertEquals(sessionCopy, dserSessions);
    // Data trees are the same.
    AssertEqual.assertDBEqual(dataTree, dserTree);
  }
}
