package org.apache.bookkeeper.test;

/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */

import java.net.Socket;
import java.nio.ByteBuffer;

import org.apache.bookkeeper.proto.NIOServerFactory;
import org.apache.bookkeeper.proto.NIOServerFactory.Cnxn;
import org.apache.bookkeeper.proto.NIOServerFactory.PacketProcessor;
import org.junit.Test;

import junit.framework.TestCase;

public class NIOServerFactoryTest extends TestCase {
    PacketProcessor problemProcessor = new PacketProcessor() {

        public void processPacket(ByteBuffer packet, Cnxn src) {
            if (packet.getInt() == 1) {
                throw new RuntimeException("Really bad thing happened");
            }
            src.sendResponse(new ByteBuffer[] { ByteBuffer.allocate(4) });
        }

    };

    @Test
    public void testProblemProcessor() throws Exception {
        NIOServerFactory factory = new NIOServerFactory(22334, problemProcessor);
        Socket s = new Socket("127.0.0.1", 22334);
        s.setSoTimeout(5000);
        try {
            s.getOutputStream().write("\0\0\0\4\0\0\0\1".getBytes());
            s.getOutputStream().write("\0\0\0\4\0\0\0\2".getBytes());
            s.getInputStream().read();
        } finally {
            s.close();
            factory.shutdown();
        }
    }
}
