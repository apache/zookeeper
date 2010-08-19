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
package org.apache.hedwig.server.netty;

import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.handler.codec.frame.LengthFieldBasedFrameDecoder;
import org.jboss.netty.handler.codec.frame.LengthFieldPrepender;
import org.jboss.netty.handler.codec.protobuf.ProtobufDecoder;
import org.jboss.netty.handler.codec.protobuf.ProtobufEncoder;
import org.jboss.netty.handler.ssl.SslHandler;

import org.apache.hedwig.protocol.PubSubProtocol;
import org.apache.hedwig.server.ssl.SslServerContextFactory;

public class PubSubServerPipelineFactory implements ChannelPipelineFactory {

    // TODO: make these conf settings
    final static int MAX_WORKER_THREADS = 32;
    final static int MAX_CHANNEL_MEMORY_SIZE = 10 * 1024 * 1024;
    final static int MAX_TOTAL_MEMORY_SIZE = 100 * 1024 * 1024;

    private UmbrellaHandler uh;
    private SslServerContextFactory sslFactory;
    private int maxMessageSize;

    /**
     * 
     * @param uh
     * @param sslFactory
     *            may be null if ssl is disabled
     * @param cfg
     */
    public PubSubServerPipelineFactory(UmbrellaHandler uh, SslServerContextFactory sslFactory, int maxMessageSize) {
        this.uh = uh;
        this.sslFactory = sslFactory;
        this.maxMessageSize = maxMessageSize;
    }

    public ChannelPipeline getPipeline() throws Exception {
        ChannelPipeline pipeline = Channels.pipeline();
        if (sslFactory != null) {
            pipeline.addLast("ssl", new SslHandler(sslFactory.getEngine()));
        }
        pipeline.addLast("lengthbaseddecoder",
                new LengthFieldBasedFrameDecoder(maxMessageSize, 0, 4, 0, 4));
        pipeline.addLast("lengthprepender", new LengthFieldPrepender(4));

        pipeline.addLast("protobufdecoder", new ProtobufDecoder(PubSubProtocol.PubSubRequest.getDefaultInstance()));
        pipeline.addLast("protobufencoder", new ProtobufEncoder());

        // pipeline.addLast("executor", new ExecutionHandler(
        // new OrderedMemoryAwareThreadPoolExecutor(MAX_WORKER_THREADS,
        // MAX_CHANNEL_MEMORY_SIZE, MAX_TOTAL_MEMORY_SIZE)));
        //
        // Dependency injection.
        pipeline.addLast("umbrellahandler", uh);
        return pipeline;
    }
}
