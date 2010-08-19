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
package org.apache.hedwig.server.handlers;

import java.util.List;
import junit.framework.TestCase;

import org.jboss.netty.channel.Channel;
import org.junit.Before;
import org.junit.Test;

import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubResponse;
import org.apache.hedwig.protocol.PubSubProtocol.StatusCode;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.netty.WriteRecordingChannel;
import org.apache.hedwig.server.topics.StubTopicManager;
import org.apache.hedwig.server.topics.TopicManager;

public class TestBaseHandler extends TestCase {

    MyBaseHandler handler;
    StubTopicManager tm;
    PubSubRequest request = PubSubRequest.getDefaultInstance();
    WriteRecordingChannel channel = new WriteRecordingChannel();

    protected class MyBaseHandler extends BaseHandler {

        public MyBaseHandler(TopicManager tm, ServerConfiguration conf) {
            super(tm, conf);
        }

        PubSubRequest request;

        public PubSubRequest getRequest() {
            return request;
        }

        @Override
        public void handleRequestAtOwner(PubSubRequest request, Channel channel) {
            this.request = request;
        }

    }

    @Override
    @Before
    public void setUp() throws Exception {
        ServerConfiguration conf = new ServerConfiguration();
        tm = new StubTopicManager(conf);
        handler = new MyBaseHandler(tm, conf);
        request = PubSubRequest.getDefaultInstance();
        channel = new WriteRecordingChannel();
    }

    public PubSubResponse getPubSubResponse(WriteRecordingChannel channel) {
        List<Object> messages = channel.getMessagesWritten();
        assertEquals(messages.size(), 1);

        Object message = messages.get(0);
        assertEquals(message.getClass(), PubSubResponse.class);

        return (PubSubResponse) message;
    }

    @Test
    public void testHandleRequestOnRedirect() throws Exception {
        tm.setShouldOwnEveryNewTopic(false);
        handler.handleRequest(request, channel);

        PubSubResponse response = getPubSubResponse(channel);
        assertEquals(response.getStatusCode(), StatusCode.NOT_RESPONSIBLE_FOR_TOPIC);
        assertEquals(request.getTxnId(), response.getTxnId());
        assertNull(handler.getRequest());

    }

    @Test
    public void testHandleRequestOnOwner() throws Exception {

        tm.setShouldOwnEveryNewTopic(true);
        handler.handleRequest(request, channel);
        assertEquals(0, channel.getMessagesWritten().size());
        assertEquals(handler.getRequest(), request);

    }

    @Test
    public void testHandleRequestOnError() throws Exception {

        tm.setShouldError(true);
        handler.handleRequest(request, channel);

        PubSubResponse response = getPubSubResponse(channel);
        assertEquals(response.getStatusCode(), StatusCode.SERVICE_DOWN);
        assertEquals(request.getTxnId(), response.getTxnId());
        assertNull(handler.getRequest());

    }

}
