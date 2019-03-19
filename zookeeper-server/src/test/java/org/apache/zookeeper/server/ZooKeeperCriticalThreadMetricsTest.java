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

import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.proto.DeleteRequest;
import org.apache.zookeeper.proto.SetDataRequest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class ZooKeeperCriticalThreadMetricsTest extends ZKTestCase {

    private class MyRequestProcessor implements RequestProcessor {
        @Override
        public void processRequest(Request request) throws RequestProcessorException {
            // use this dummy request processor to trigger a unrecoverable ex
            throw new RequestProcessorException("test", new Exception());
        }

        @Override
        public void shutdown() {
        }
    }

    private Request createRquest(long sessionId, int xid) {
        return new Request(null, sessionId, xid, ZooDefs.OpCode.setData,
                ByteBuffer.wrap(new byte[10]), null);
    }

    @Test
    public void testUnrecoverableErrorCount() throws Exception{
        ServerMetrics.resetAll();

        PrepRequestProcessor processor =new PrepRequestProcessor(new ZooKeeperServer(), new MyRequestProcessor());
        processor.start();

        processor.processRequest(createRquest(1L, 1));
        Thread.sleep(200);

        processor.shutdown();

        Map<String, Object> values = ServerMetrics.getAllValues();
        Assert.assertEquals(1L, values.get("unrecoverable_error_count"));
    }









}
