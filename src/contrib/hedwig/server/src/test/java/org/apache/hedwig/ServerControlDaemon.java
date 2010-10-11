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
package org.apache.hedwig;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

import org.apache.log4j.Logger;

import org.jboss.netty.channel.Channel;  
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.string.StringEncoder;
import org.jboss.netty.handler.codec.string.StringDecoder;
import org.jboss.netty.handler.codec.frame.Delimiters;
import org.jboss.netty.handler.codec.frame.DelimiterBasedFrameDecoder;
import org.jboss.netty.channel.ChannelPipelineCoverage;

import java.util.HashMap;

public class ServerControlDaemon {
    private static final Logger LOG =
        Logger.getLogger(ServerControlDaemon.class);

    @ChannelPipelineCoverage("all")
    public static class ServerControlDaemonHandler extends SimpleChannelUpstreamHandler{
	private ServerControl control;
	private HashMap<Channel, HashMap<String, ServerControl.TestServer>> serverMap;
	
	public ServerControlDaemonHandler() {
	    serverMap = new HashMap<Channel, HashMap<String, ServerControl.TestServer>>();
	    control = new ServerControl();
	}

	private void addServerForChannel(Channel c, ServerControl.TestServer t) {
	    LOG.info("Created server " + t.getAddress());
	    HashMap<String, ServerControl.TestServer> map = serverMap.get(c);
	    if (map == null) {
		map = new HashMap<String, ServerControl.TestServer>();
		serverMap.put(c, map);
	    }
	    map.put(t.getAddress(), t);	    
	}
	
	private void killServerForChannel(Channel c, String name) {
	    LOG.info("Killing server " + name);
	    HashMap<String, ServerControl.TestServer> map = serverMap.get(c);
	    ServerControl.TestServer t = map.get(name);
	    map.remove(name);
	    try {
		t.kill();
	    } catch (Exception e) {
		e.printStackTrace();
		// do nothing, should be killed, we won't use it again anyhow
	    }
	}

	private ServerControl.TestServer lookupServer(Channel c, String name) {
	    HashMap<String, ServerControl.TestServer> map = serverMap.get(c);
	    return map.get(name);
	}
	
	private void clearServersForChannel(Channel c) {
	    HashMap<String, ServerControl.TestServer> map = serverMap.get(c);
	    serverMap.remove(map);
	    
	    if (map != null) {
		for (ServerControl.TestServer t : map.values()) {
		    t.kill();
		}
		map.clear();
	    }
	}

	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
	    try {
		String command = (String)e.getMessage();
		LOG.info("Command: " + command);
		String[] args = command.split("\\s+");

		if (args[0].equals("START")) {
		    ServerControl.TestServer t = null;
		    if (args[1].equals("ZOOKEEPER")) {
			t = control.startZookeeperServer(Integer.valueOf(args[2]));
			addServerForChannel(ctx.getChannel(), t);
		    } else if (args[1].equals("BOOKKEEPER")) {
			ServerControl.TestServer zk = lookupServer(ctx.getChannel(), args[3]);
			t = control.startBookieServer(Integer.valueOf(args[2]), zk);
			addServerForChannel(ctx.getChannel(), t);
		    } else if (args[1].equals("HEDWIG")) {
			ServerControl.TestServer zk = lookupServer(ctx.getChannel(), args[4]);
			t = control.startPubSubServer(Integer.valueOf(args[2]), args[3], zk);
			addServerForChannel(ctx.getChannel(), t);
		    }

		    ctx.getChannel().write("OK " + t.getAddress() + "\n");
		} else if (args[0].equals("KILL")) {
		    killServerForChannel(ctx.getChannel(), args[1]);
		    
		    ctx.getChannel().write("OK Killed " + args[1] + "\n");
		} else if (args[0].equals("TEST")) {
		    LOG.info("\n******\n\n" + args[1] + "\n\n******");
		    ctx.getChannel().write("OK Test Noted\n");
		} else {
		    ctx.getChannel().write("ERR Bad Command\n");
		}
	    } catch (Exception ex) {
		ex.printStackTrace();
		ctx.getChannel().write("ERR " + ex.toString() + "\n");
	    }
	}
	
	public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
	    clearServersForChannel(ctx.getChannel());
	}
    }

    public static void main(String[] args) throws Exception {
	// Configure the server.
	int port = 5672;
	if (args.length == 1) {
	    port = Integer.valueOf(args[0]); 
	}
	ServerBootstrap bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(Executors.newCachedThreadPool(),
											  Executors.newCachedThreadPool()));
	// Set up the pipeline factory.
	bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
		public ChannelPipeline getPipeline() throws Exception {
		    ChannelPipeline p = Channels.pipeline();
		    p.addLast("frameDecoder", new DelimiterBasedFrameDecoder(80, Delimiters.lineDelimiter()));
		    p.addLast("stringDecoder", new StringDecoder("UTF-8"));
		    
		    // Encoder
		    p.addLast("stringEncoder", new StringEncoder("UTF-8"));
		    p.addLast("handler", new ServerControlDaemonHandler());
		    
		    return p;
		}
	    });
	
	LOG.info("Listening on localhost:"+port);
	// Bind and start to accept incoming connections.
	bootstrap.bind(new InetSocketAddress(port));
    }
}
