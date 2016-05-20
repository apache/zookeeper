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

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Index;
import org.apache.jute.InputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.Learner;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Assert;
import org.junit.Test;

public class LearnerTest extends ZKTestCase {
  private static final File testData = new File(
    System.getProperty("test.data.dir", "build/test/data"));

	class SimpleLearnerZooKeeperServer extends LearnerZooKeeperServer {
		boolean startupCalled;
		
		public SimpleLearnerZooKeeperServer(FileTxnSnapLog ftsl, QuorumPeer self) throws IOException {
			super(ftsl, 2000, 2000, 2000, null, new ZKDatabase(ftsl), self);
		}
		Learner learner;
		@Override
		public Learner getLearner() {
			return learner;
		}
		
		@Override
		public void startup() {
			startupCalled = true;
		}
	}
	class SimpleLearner extends Learner {
		SimpleLearner(FileTxnSnapLog ftsl) throws IOException {
            self = QuorumPeer.testingQuorumPeer();
            zk = new SimpleLearnerZooKeeperServer(ftsl, self);
			((SimpleLearnerZooKeeperServer)zk).learner = this;
		}
	}
	static private void recursiveDelete(File dir) {
		if (dir == null || !dir.exists()) {
			return;
		}
		if (!dir.isDirectory()) {
			dir.delete();
		}
		for(File child: dir.listFiles()) {
			recursiveDelete(child);
		}
	}
	@Test
	public void syncTest() throws Exception {
		File tmpFile = File.createTempFile("test", ".dir", testData);
		tmpFile.delete();
		try {
			FileTxnSnapLog ftsl = new FileTxnSnapLog(tmpFile, tmpFile);
			SimpleLearner sl = new SimpleLearner(ftsl);
			long startZxid = sl.zk.getLastProcessedZxid();
			
			// Set up bogus streams
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);
			sl.leaderOs = BinaryOutputArchive.getArchive(new ByteArrayOutputStream());
			
			// make streams and socket do something innocuous
			sl.bufferedOutput = new BufferedOutputStream(System.out);
			sl.sock = new Socket();
			
			// fake messages from the server
			QuorumPacket qp = new QuorumPacket(Leader.SNAP, 0, null, null);
			oa.writeRecord(qp, null);
			sl.zk.getZKDatabase().serializeSnapshot(oa);
			oa.writeString("BenWasHere", "signature");
			TxnHeader hdr = new TxnHeader(0, 0, 0, 0, ZooDefs.OpCode.create);
			CreateTxn txn = new CreateTxn("/foo", new byte[0], new ArrayList<ACL>(), false, sl.zk.getZKDatabase().getNode("/").stat.getCversion());
	        ByteArrayOutputStream tbaos = new ByteArrayOutputStream();
	        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(tbaos);
	        hdr.serialize(boa, "hdr");
	        txn.serialize(boa, "txn");
	        tbaos.close();
			qp = new QuorumPacket(Leader.PROPOSAL, 1, tbaos.toByteArray(), null);
			oa.writeRecord(qp, null);

			// setup the messages to be streamed to follower
			sl.leaderIs = BinaryInputArchive.getArchive(new ByteArrayInputStream(baos.toByteArray()));
			
			try {
				sl.syncWithLeader(3);
			} catch(EOFException e) {}
			
			sl.zk.shutdown();
			sl = new SimpleLearner(ftsl);
			Assert.assertEquals(startZxid, sl.zk.getLastProcessedZxid());
		} finally {
			recursiveDelete(tmpFile);
		}
	}
}
