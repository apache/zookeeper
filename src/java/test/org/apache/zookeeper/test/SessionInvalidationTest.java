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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import junit.framework.Assert;

import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.RequestHeader;
import org.junit.Test;

public class SessionInvalidationTest extends ClientBase {
    /**
     * Test solution for ZOOKEEPER-1208. Verify that operations are not
     * accepted after a close session.
     * 
     * We're using our own marshalling here in order to force an operation
     * after the session is closed (ZooKeeper.class will not allow this). Also
     * by filling the pipe with operations it increases the likelyhood that
     * the server will process the create before FinalRequestProcessor
     * removes the session from the tracker.
     */
    @Test
    public void testCreateAfterCloseShouldFail() throws Exception {
        for (int i = 0; i < 10; i++) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);

            // open a connection
            boa.writeInt(44, "len");
            ConnectRequest conReq = new ConnectRequest(0, 0, 30000, 0, new byte[16]);
            conReq.serialize(boa, "connect");

            // close connection
            boa.writeInt(8, "len");
            RequestHeader h = new RequestHeader(1, ZooDefs.OpCode.closeSession);
            h.serialize(boa, "header");

            // create ephemeral znode
            boa.writeInt(52, "len"); // We'll fill this in later
            RequestHeader header = new RequestHeader(2, OpCode.create);
            header.serialize(boa, "header");
            CreateRequest createReq = new CreateRequest("/foo" + i, new byte[0],
                    Ids.OPEN_ACL_UNSAFE, 1);
            createReq.serialize(boa, "request");
            baos.close();
            
            System.out.println("Length:" + baos.toByteArray().length);
            
            String hp[] = hostPort.split(":");
            Socket sock = new Socket(hp[0], Integer.parseInt(hp[1]));
            InputStream resultStream = null;
            try {
                OutputStream outstream = sock.getOutputStream();
                byte[] data = baos.toByteArray();
                outstream.write(data);
                outstream.flush();
                
                resultStream = sock.getInputStream();
                byte[] b = new byte[10000];
                int len;
                while ((len = resultStream.read(b)) >= 0) {
                    // got results
                    System.out.println("gotlen:" + len);
                }
            } finally {
                if (resultStream != null) {
                    resultStream.close();
                }
                sock.close();
            }
        }
        
        ZooKeeper zk = createClient();
        Assert.assertEquals(1, zk.getChildren("/", false).size());

        zk.close();
    }
}
