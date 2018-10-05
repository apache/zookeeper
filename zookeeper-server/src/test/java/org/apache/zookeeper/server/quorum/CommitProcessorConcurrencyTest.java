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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.GetDataRequest;
import org.apache.zookeeper.proto.SyncRequest;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.WorkerService;
import org.apache.zookeeper.server.RequestProcessor.RequestProcessorException;
import org.apache.zookeeper.server.ZooKeeperServerListener;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommitProcessorConcurrencyTest extends ZKTestCase {
    protected static final Logger LOG =
            LoggerFactory.getLogger(CommitProcessorConcurrencyTest.class);

    Boolean executedFlag = false;
    MockCommitProcessor processor;

    @Before
    public void setUp() throws Exception {
        processor = new MockCommitProcessor();
    }

    @After
    public void tearDown() throws Exception {
        processor.shutdown();
    }

    class MockCommitProcessor extends CommitProcessor {

        MockCommitProcessor() {
          super( 
                  new RequestProcessor() {
                      public void processRequest(Request request) 
                              throws RequestProcessorException {
                          executedFlag = true;
                      }
                      public void shutdown(){}
          },
          "0",
          false, new ZooKeeperServerListener(){

              @Override
              public void notifyStopping(String errMsg, int exitCode) {

              }});
        }

        public void testStart() {
            this.stopped = false;
            this.workerPool = new WorkerService(
                    "CommitProcWork", 1, true);
        }

        public void addToCommittedRequests(Request req) {
            this.committedRequests.add(req);
        }

        public void addToNextPending(Request req) {
            this.nextPending.set(req);
        }

        public void addToQueuedRequests(Request req) {
            //this.numRequestsProcessing.incrementAndGet();
            this.queuedRequests.add(req);
        }

        public void testProcessCommitted() {
            this.processCommitted();
        }

        @Override
        public void shutdown() {
            this.workerPool.stop();
        }
    }

    /*
     * We populate the necessary data structures in the CommitProcessor
     * instance and run processCommitted
     */
    @Test
    public void raceTest() 
    throws Exception {

       ByteArrayOutputStream boas = new ByteArrayOutputStream();
       BinaryOutputArchive boa = BinaryOutputArchive.getArchive(boas);
       GetDataRequest getReq = new GetDataRequest("/testrace", false);
       getReq.serialize(boa, "request");
       ByteBuffer bb = ByteBuffer.wrap(boas.toByteArray());
       Request readReq = new Request(null, 0x0, 0, OpCode.getData,
               bb, new ArrayList<Id>());

       boas.reset();
       SyncRequest syncReq = new SyncRequest("/testrace");
       syncReq.serialize(boa, "request");
       bb = ByteBuffer.wrap(boas.toByteArray());
       Request writeReq = new Request(null, 0x0, 0, OpCode.sync,
                                 bb, new ArrayList<Id>());

       processor.addToCommittedRequests(writeReq);
       processor.addToQueuedRequests(readReq);
       processor.addToQueuedRequests(writeReq);

       processor.testStart();
       processor.testProcessCommitted();
       Assert.assertFalse("Next request processor executed", executedFlag);
    }
}