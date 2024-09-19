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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.Learner;
import org.apache.zookeeper.server.quorum.LearnerZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ZooKeeperServerShutdownTest extends ZKTestCase  {

    static class ShutdownTrackRequestProcessor implements RequestProcessor {
        boolean shutdown = false;

        @Override
        public void processRequest(Request request) throws RequestProcessorException {
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }
    }

    public static class ShutdownTrackLearnerZooKeeperServer extends LearnerZooKeeperServer {
        public ShutdownTrackLearnerZooKeeperServer(FileTxnSnapLog logFactory, QuorumPeer self) throws IOException {
            super(logFactory, 2000, 2000, 2000, -1, new ZKDatabase(logFactory), self);
        }

        @Override
        protected void setupRequestProcessors() {
            firstProcessor = new ShutdownTrackRequestProcessor();
            syncProcessor = new SyncRequestProcessor(this, null);
            syncProcessor.start();
        }

        ShutdownTrackRequestProcessor getFirstProcessor() {
            return (ShutdownTrackRequestProcessor) firstProcessor;
        }

        SyncRequestProcessor getSyncRequestProcessor() {
            return syncProcessor;
        }

        @Override
        public Learner getLearner() {
            return null;
        }
    }

    @Test
    void testLearnerZooKeeperServerShutdown(@TempDir File tmpDir) throws Exception {
        File tmpFile = File.createTempFile("test", ".dir", tmpDir);
        tmpFile.delete();
        FileTxnSnapLog logFactory = new FileTxnSnapLog(tmpFile, tmpFile);
        ShutdownTrackLearnerZooKeeperServer zooKeeperServer = new ShutdownTrackLearnerZooKeeperServer(logFactory, new QuorumPeer());
        zooKeeperServer.startup();
        zooKeeperServer.shutdown(false);
        assertTrue(zooKeeperServer.getFirstProcessor().shutdown);
        assertFalse(zooKeeperServer.getSyncRequestProcessor().isAlive());
    }
}
