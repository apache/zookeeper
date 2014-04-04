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

package org.apache.zookeeper.server;

import static org.junit.Assert.*;

import java.io.File;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.KeeperException.SessionMovedException;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.server.PrepRequestProcessor;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.ErrorTxn;
import org.junit.Assert;
import org.junit.Test;

public class PrepRequestProcessorTest extends ClientBase {
    private static String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private static final int CONNECTION_TIMEOUT = 3000;
    private final CountDownLatch testEnd = new CountDownLatch(1);

    @Test
    public void testPRequest() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(100);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ",
                ClientBase.waitForServerUp(HOSTPORT,CONNECTION_TIMEOUT));
        zks.sessionTracker = new MySessionTracker(); 
        PrepRequestProcessor processor = new PrepRequestProcessor(zks, new MyRequestProcessor());
        Request foo = new Request(null, 1l, 1, OpCode.create, ByteBuffer.allocate(3), null);
        processor.pRequest(foo);
        testEnd.await(5, java.util.concurrent.TimeUnit.SECONDS);
        f.shutdown();
        zks.shutdown();
    }
 

    private class MyRequestProcessor implements RequestProcessor {
        @Override
        public void processRequest(Request request) {
            Assert.assertEquals("Request should have marshalling error", new ErrorTxn(Code.MARSHALLINGERROR.intValue()),  request.txn);
            testEnd.countDown();            
        }
        @Override
        public void shutdown() {
            // TODO Auto-generated method stub
            
        }
    }
    
    private class MySessionTracker implements SessionTracker {
        @Override
        public void addSession(long id, int to) {
            // TODO Auto-generated method stub
            
        }
        @Override
        public void checkSession(long sessionId, Object owner)
                throws SessionExpiredException, SessionMovedException {
            // TODO Auto-generated method stub
            
        }
        @Override
        public long createSession(int sessionTimeout) {
            // TODO Auto-generated method stub
            return 0;
        }
        @Override
        public void dumpSessions(PrintWriter pwriter) {
            // TODO Auto-generated method stub
            
        }
         @Override
        public void removeSession(long sessionId) {
            // TODO Auto-generated method stub
            
        }
        @Override
        public void setOwner(long id, Object owner)
                throws SessionExpiredException {
            // TODO Auto-generated method stub
            
        }
        @Override
        public void shutdown() {
            // TODO Auto-generated method stub
            
        }
        @Override
        public boolean touchSession(long sessionId, int sessionTimeout) {
            // TODO Auto-generated method stub
            return false;
        }
        @Override
        public void setSessionClosing(long sessionId) {
          // TODO Auto-generated method stub
        }
    }
}
