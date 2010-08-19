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
package org.apache.hedwig.client.netty;

import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.handler.codec.frame.LengthFieldBasedFrameDecoder;
import org.jboss.netty.handler.codec.frame.LengthFieldPrepender;
import org.jboss.netty.handler.codec.protobuf.ProtobufDecoder;
import org.jboss.netty.handler.codec.protobuf.ProtobufEncoder;
import org.jboss.netty.handler.ssl.SslHandler;

import org.apache.hedwig.protocol.PubSubProtocol;

public class ClientChannelPipelineFactory implements ChannelPipelineFactory {

    private HedwigClient client;

    public ClientChannelPipelineFactory(HedwigClient client) {
        this.client = client;
    }

    // Retrieve a ChannelPipeline from the factory.
    public ChannelPipeline getPipeline() throws Exception {
        // Create a new ChannelPipline using the factory method from the
        // Channels helper class.
        ChannelPipeline pipeline = Channels.pipeline();        
        if (client.getSslFactory() != null) {
            pipeline.addLast("ssl", new SslHandler(client.getSslFactory().getEngine()));
        }        
        pipeline.addLast("lengthbaseddecoder", new LengthFieldBasedFrameDecoder(client.getConfiguration()
                .getMaximumMessageSize(), 0, 4, 0, 4));
        pipeline.addLast("lengthprepender", new LengthFieldPrepender(4));

        pipeline.addLast("protobufdecoder", new ProtobufDecoder(PubSubProtocol.PubSubResponse.getDefaultInstance()));
        pipeline.addLast("protobufencoder", new ProtobufEncoder());

        pipeline.addLast("responsehandler", new ResponseHandler(client));
        return pipeline;
    }

}
