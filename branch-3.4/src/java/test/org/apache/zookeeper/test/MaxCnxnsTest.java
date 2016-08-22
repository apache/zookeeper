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

package org.apache.zookeeper.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.proto.ConnectRequest;
import org.junit.Assert;
import org.junit.Test;

public class MaxCnxnsTest extends ClientBase {
    final private int numCnxns = 30;
    AtomicInteger numConnected = new AtomicInteger(0);
    String host;
    int port;

    @Override
    public void setUp() throws Exception {
        maxCnxns = numCnxns;
        super.setUp();
    }

    class CnxnThread extends Thread {
        int i;
        SocketChannel socket;
        public CnxnThread(int i) {
            super("CnxnThread-"+i);
            this.i = i;
        }

        public void run() {
            SocketChannel sChannel = null;
            try {
                /*
                 * For future unwary socket programmers: although connect 'blocks' it
                 * does not require an accept on the server side to return. Therefore
                 * you can not assume that all the sockets are connected at the end of
                 * this for loop.
                 */
                sChannel = SocketChannel.open();
                sChannel.connect(new InetSocketAddress(host,port));
                // Construct a connection request
                ConnectRequest conReq = new ConnectRequest(0, 0,
                        10000, 0, "password".getBytes());
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
                boa.writeInt(-1, "len");
                conReq.serialize(boa, "connect");
                baos.close();
                ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
                bb.putInt(bb.capacity() - 4);
                bb.rewind();

                /* Send a connect request. Any socket that has been closed (or at least
                 * not added to the cnxn list on the server) will not have any bytes to
                 * read and get an eof.
                 *
                 *  The trick here was finding a call that caused the server to put
                 *  bytes in the input stream without closing the cnxn. None of
                 *  the four letter commands do that, so we actually try to create
                 *  a session which should send us something back, while maintaining
                 *  the connection.
                 */

                int eof = sChannel.write(bb);
                // If the socket times out, we count that as Assert.failed -
                // the server should respond within 10s
                sChannel.socket().setSoTimeout(10000);
                if (!sChannel.socket().isClosed()){
                    eof = sChannel.socket().getInputStream().read();
                    if (eof != -1) {
                        numConnected.incrementAndGet();
                    }
                }
            }
            catch (IOException io) {
                // "Connection reset by peer"
            }
            finally {
                if (sChannel != null) {
                    try {
                        sChannel.close();
                    }
                    catch (Exception e) {
                    }
                }
            }
        }
    }

    /**
     * Verify the ability to limit the number of concurrent connections.
     * @throws IOException
     * @throws InterruptedException
     */
    @Test
    public void testMaxCnxns() throws IOException, InterruptedException{
        String split[] = hostPort.split(":");
        host = split[0];
        port = Integer.parseInt(split[1]);
        int numThreads = numCnxns + 5;
        CnxnThread[] threads = new CnxnThread[numThreads];

        for (int i=0;i<numCnxns;++i) {
          threads[i] = new CnxnThread(i);
        }

        for (int i=0;i<numCnxns;++i) {
            threads[i].start();
        }

        for (int i=0;i<numCnxns;++i) {
            threads[i].join();
        }
        Assert.assertSame(numCnxns,numConnected.get());
    }
}
