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
package org.apache.hedwig.server.benchmark;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.socket.ServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.frame.LengthFieldBasedFrameDecoder;
import org.jboss.netty.handler.codec.frame.LengthFieldPrepender;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.logging.Log4JLoggerFactory;

@ChannelPipelineCoverage("all")
public class FakeBookie extends SimpleChannelHandler implements
		ChannelPipelineFactory {
	static final Logger logger = Logger.getLogger(FakeBookie.class);
	ServerSocketChannelFactory serverChannelFactory = new NioServerSocketChannelFactory(
			Executors.newCachedThreadPool(), Executors.newCachedThreadPool());

	public FakeBookie(int port) {
		InternalLoggerFactory.setDefaultFactory(new Log4JLoggerFactory());
		ServerBootstrap bootstrap = new ServerBootstrap(serverChannelFactory);

		bootstrap.setPipelineFactory(this);
		bootstrap.setOption("child.tcpNoDelay", true);
		bootstrap.setOption("child.keepAlive", true);
		bootstrap.setOption("reuseAddress", true);

		logger.info("Going into receive loop");
		// Bind and start to accept incoming connections.
		bootstrap.bind(new InetSocketAddress(port));
	}

	@Override
	public ChannelPipeline getPipeline() throws Exception {
		ChannelPipeline pipeline = Channels.pipeline();
		pipeline.addLast("lengthbaseddecoder",
				new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4));
		pipeline.addLast("lengthprepender", new LengthFieldPrepender(4));
		pipeline.addLast("main", this);
		return pipeline;
	}

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
			throws Exception {
		if (!(e.getMessage() instanceof ChannelBuffer)) {
			ctx.sendUpstream(e);
			return;
		}

		ChannelBuffer buffer = (ChannelBuffer) e.getMessage();

		int type = buffer.readInt();
		buffer.readerIndex(24);
		long ledgerId = buffer.readLong();
		long entryId = buffer.readLong();

		ChannelBuffer outBuf = ctx.getChannel().getConfig().getBufferFactory()
				.getBuffer(24);
		outBuf.writeInt(type);
		outBuf.writeInt(0); // rc
		outBuf.writeLong(ledgerId);
		outBuf.writeLong(entryId);
		e.getChannel().write(outBuf);

	}

	
	public static void main(String args[]){
		new FakeBookie(Integer.parseInt(args[0]));
	}
}
