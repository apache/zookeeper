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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.LinkedList;
import java.util.List;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelConfig;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.DefaultChannelFuture;
import org.jboss.netty.channel.SucceededChannelFuture;

public class WriteRecordingChannel implements Channel {

    public boolean closed = false;
    ChannelFuture closingFuture = new DefaultChannelFuture(this, false);
    List<Object> messagesWritten = new LinkedList<Object>();

    public List<Object> getMessagesWritten() {
        return messagesWritten;
    }

    public void clearMessages() {
        messagesWritten.clear();
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        throw new RuntimeException("Not intended");
    }

    @Override
    public ChannelFuture close() {
        closed = true;
        closingFuture.setSuccess();
        return new SucceededChannelFuture(this);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        throw new RuntimeException("Not intended");
    }

    @Override
    public ChannelFuture disconnect() {
        return close();
    }

    @Override
    public ChannelFuture getCloseFuture() {
        return closingFuture;
    }

    @Override
    public ChannelConfig getConfig() {
        throw new RuntimeException("Not intended");
    }

    @Override
    public ChannelFactory getFactory() {
        throw new RuntimeException("Not intended");
    }

    @Override
    public Integer getId() {
        throw new RuntimeException("Not intended");
    }

    @Override
    public int getInterestOps() {
        throw new RuntimeException("Not intended");
    }

    @Override
    public SocketAddress getLocalAddress() {
        return new InetSocketAddress("localhost", 1234);
    }

    @Override
    public Channel getParent() {
        throw new RuntimeException("Not intended");
    }

    @Override
    public ChannelPipeline getPipeline() {
        throw new RuntimeException("Not intended");
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return new InetSocketAddress("www.yahoo.com", 80);
    }

    @Override
    public boolean isBound() {
        throw new RuntimeException("Not intended");
    }

    @Override
    public boolean isConnected() {
        return closed == false;
    }

    @Override
    public boolean isOpen() {
        throw new RuntimeException("Not intended");
    }

    @Override
    public boolean isReadable() {
        throw new RuntimeException("Not intended");
    }

    @Override
    public boolean isWritable() {
        throw new RuntimeException("Not intended");
    }

    @Override
    public ChannelFuture setInterestOps(int interestOps) {
        throw new RuntimeException("Not intended");
    }

    @Override
    public ChannelFuture setReadable(boolean readable) {
        throw new RuntimeException("Not intended");
    }

    @Override
    public ChannelFuture unbind() {
        throw new RuntimeException("Not intended");
    }

    @Override
    public ChannelFuture write(Object message) {
        messagesWritten.add(message);
        return new SucceededChannelFuture(this);
    }

    @Override
    public ChannelFuture write(Object message, SocketAddress remoteAddress) {
        throw new RuntimeException("Not intended");
    }

    @Override
    public int compareTo(Channel o) {
        throw new RuntimeException("Not intended");
    }

}
