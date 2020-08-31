/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.server.ByteBufferInputStream;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extension of NIOServerCnxn which can inject changes per controller commands.
 * Similar extensions can implement on top of NettyServerCnxn as well.
 */
@SuppressFBWarnings(value = "BC_UNCONFIRMED_CAST", justification = "factory is ControllableConnectionFactory type.")
public class ControllableConnection extends NIOServerCnxn {
    private static final Logger LOG = LoggerFactory.getLogger(ControllableConnection.class);
    private final ControllableConnectionFactory controller;

    public ControllableConnection(ZooKeeperServer zk, SocketChannel sock, SelectionKey sk, NIOServerCnxnFactory factory,
                                  NIOServerCnxnFactory.SelectorThread selectorThread) throws IOException {
        super(zk, sock, sk, factory, selectorThread);
        controller = (ControllableConnectionFactory) factory;
    }

    @Override
    public int sendResponse(ReplyHeader header, Record record, String tag) {
        if (controller.shouldSendResponse()) {
            try {
                return super.sendResponse(header, record, tag);
            } catch (IOException ex) {
                LOG.warn("IO Exception occurred", ex);
            }
        } else {
            LOG.warn("Controller is configured to NOT sending response back to client.");
        }
        return -1;
    }

    @Override
    protected void readRequest() throws IOException {
        if (controller.shouldFailNextRequest()) {
            ByteBuffer buffer = incomingBuffer.slice();
            BinaryInputArchive bia = BinaryInputArchive.getArchive(new ByteBufferInputStream(buffer));
            RequestHeader h = new RequestHeader();
            h.deserialize(bia, "header");
            super.sendResponse(new ReplyHeader(h.getXid(), 0, KeeperException.Code.APIERROR.intValue()),
                    null, null);
        } else {
            controller.delayRequestIfNeeded();
            super.readRequest();
        }
    }
}
