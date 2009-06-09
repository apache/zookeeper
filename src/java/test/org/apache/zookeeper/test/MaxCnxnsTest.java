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

import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.proto.ConnectRequest;

public class MaxCnxnsTest extends ClientBase {

    final private int numCnxns = 5;
    
    protected void setUp() throws Exception {
        maxCnxns = numCnxns;
        super.setUp();
    }
    
    /**
     * Verify the ability to limit the number of concurrent connections. 
     * @throws IOException
     * @throws InterruptedException
     */
    public void testMaxCnxns() throws IOException, InterruptedException{
        SocketChannel[] sockets = new SocketChannel[numCnxns+5];
        String split[] = hostPort.split(":");
        String host = split[0];
        int port = Integer.parseInt(split[1]);
        int numConnected = 0;

        /*
         * For future unwary socket programmers: although connect 'blocks' it 
         * does not require an accept on the server side to return. Therefore
         * you can not assume that all the sockets are connected at the end of
         * this for loop.
         */
        for (int i=0;i<sockets.length;++i) {            
          SocketChannel sChannel = SocketChannel.open();
          sChannel.connect(new InetSocketAddress(host,port));          
          sockets[i] = sChannel;
        }
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
        for (int i=0;i<sockets.length;++i) {
            try {
                bb.rewind();
                int eof = sockets[i].write(bb);
                // If the socket times out, we count that as failed - 
                // the server should respond within 10s
                sockets[i].socket().setSoTimeout(10000);                
                if (!sockets[i].socket().isClosed()){
                    eof = sockets[i].socket().getInputStream().read(); 
                    if (eof != -1) {
                        numConnected++;
                    }
                }
            }            
            catch (IOException io) {
                // "Connection reset by peer"
            }
        }
        assertSame(numCnxns,numConnected);
    }
}
