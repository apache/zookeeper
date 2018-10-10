/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.zookeeper.server.quorum;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.Assert.*;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.MockPacket;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.proto.SetWatches;
import org.apache.zookeeper.server.MockNIOServerCnxn;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrate ZOOKEEPER-1382 : Watches leak on expired session
 */
public class WatchLeakTest {

    protected static final Logger LOG = LoggerFactory
            .getLogger(WatchLeakTest.class);

    final long SESSION_ID = 0xBABEL;

    /**
     * ZOOKEEPR-1382 test class
     */
    @Test
    public void testWatchesWithClientSessionTimeout() throws Exception {

        NIOServerCnxnFactory serverCnxnFactory = new NIOServerCnxnFactory();

        ZKDatabase database = new ZKDatabase(null);
        database.setlastProcessedZxid(2L);
        QuorumPeer quorumPeer = mock(QuorumPeer.class);
        FileTxnSnapLog logfactory = mock(FileTxnSnapLog.class);
        // Directories are not used but we need it to avoid NPE
        when(logfactory.getDataDir()).thenReturn(new File("/tmp"));
        when(logfactory.getSnapDir()).thenReturn(new File("/tmp"));
        FollowerZooKeeperServer fzks = null;
        try {
            fzks = new FollowerZooKeeperServer(logfactory, quorumPeer, null,
                    database);
            fzks.startup();
            fzks.setServerCnxnFactory(serverCnxnFactory);
            quorumPeer.follower = new MyFollower(quorumPeer, fzks);
            final SelectionKey sk = new FakeSK();
            // Simulate a socket channel between a client and a follower
            final SocketChannel socketChannel = createClientSocketChannel();
            // Create the NIOServerCnxn that will handle the client requests
            final MockNIOServerCnxn nioCnxn = new MockNIOServerCnxn(fzks,
                    socketChannel, sk, serverCnxnFactory);
            // Send the connection request as a client do
            nioCnxn.doIO(sk);
            // Send the invalid session packet to the follower
            QuorumPacket qp = createInvalidSessionPacket();
            quorumPeer.follower.processPacket(qp);
            // OK, now the follower knows that the session is invalid, let's try
            // to
            // send it the watches
            nioCnxn.doIO(sk);
            // wait for the the request processor to do his job
            Thread.sleep(1000L);
            // Session has not been re-validated !
            // If session has not been validated, there must be NO watches
            int watchCount = database.getDataTree().getWatchCount();
            LOG.info("watches = " + watchCount);
            assertEquals(0, watchCount);
        } finally {
            if (fzks != null) {
                fzks.shutdown();
            }
        }
    }

    /**
     * A follower with no real leader connection
     */
    public static class MyFollower extends Follower {
        /**
         * Create a follower with a mocked leader connection
         * 
         * @param self
         * @param zk
         */
        MyFollower(QuorumPeer self, FollowerZooKeeperServer zk) {
            super(self, zk);
            leaderOs = mock(OutputArchive.class);
            leaderIs = mock(InputArchive.class);
            bufferedOutput = mock(BufferedOutputStream.class);
        }
    }

    /**
     * Simulate the behavior of a real selection key
     */
    private static class FakeSK extends SelectionKey {

        @Override
        public SelectableChannel channel() {
            return null;
        }

        @Override
        public Selector selector() {
            return mock(Selector.class);
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void cancel() {
        }

        @Override
        public int interestOps() {
            return ops;
        }

        private int ops = OP_WRITE + OP_READ;

        @Override
        public SelectionKey interestOps(int ops) {
            this.ops = ops;
            return this;
        }

        @Override
        public int readyOps() {
            return ops;
        }

    }

    /**
     * Create a watches message with a single watch on /
     * 
     * @return
     */
    private ByteBuffer createWatchesMessage() {
        List<String> dataWatches = new ArrayList<String>(1);
        dataWatches.add("/");
        List<String> existWatches = Collections.emptyList();
        List<String> childWatches = Collections.emptyList();
        SetWatches sw = new SetWatches(1L, dataWatches, existWatches,
                childWatches);
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.setWatches);
        h.setXid(-8);
        MockPacket p = new MockPacket(h, new ReplyHeader(), sw, null, null);
        return p.createAndReturnBB();
    }

    /**
     * This is the secret that we use to generate passwords, for the moment it
     * is more of a sanity check.
     */
    static final private long superSecret = 0XB3415C00L;

    /**
     * Create a connection request
     * 
     * @return
     */
    private ByteBuffer createConnRequest() {
        Random r = new Random(SESSION_ID ^ superSecret);
        byte p[] = new byte[16];
        r.nextBytes(p);
        ConnectRequest conReq = new ConnectRequest(0, 1L, 30000, SESSION_ID, p);
        MockPacket packet = new MockPacket(null, null, conReq, null, null, false);
        return packet.createAndReturnBB();
    }

    /**
     * Mock a client channel with a connection request and a watches message
     * inside.
     * 
     * @return a socket channel
     * @throws IOException
     */
    private SocketChannel createClientSocketChannel() throws IOException {

        SocketChannel socketChannel = mock(SocketChannel.class);
        Socket socket = mock(Socket.class);
        InetSocketAddress socketAddress = new InetSocketAddress(1234);
        when(socket.getRemoteSocketAddress()).thenReturn(socketAddress);
        when(socketChannel.socket()).thenReturn(socket);

        // Send watches packet to server connection
        final ByteBuffer connRequest = createConnRequest();
        final ByteBuffer watchesMessage = createWatchesMessage();
        final ByteBuffer request = ByteBuffer.allocate(connRequest.limit()
                + watchesMessage.limit());
        request.put(connRequest);
        request.put(watchesMessage);

        Answer<Integer> answer = new Answer<Integer>() {
            int i = 0;

            @Override
            public Integer answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                ByteBuffer bb = (ByteBuffer) args[0];
                for (int k = 0; k < bb.limit(); k++) {
                    bb.put(request.get(i));
                    i = i + 1;
                }
                return bb.limit();
            }
        };
        when(socketChannel.read(any(ByteBuffer.class))).thenAnswer(answer);
        return socketChannel;
    }

    /**
     * Forge an invalid session packet as a LEADER do
     * 
     * @throws Exception
     */
    private QuorumPacket createInvalidSessionPacket() throws Exception {
        QuorumPacket qp = createValidateSessionQuorumPacket();
        ByteArrayInputStream bis = new ByteArrayInputStream(qp.getData());
        DataInputStream dis = new DataInputStream(bis);
        long id = dis.readLong();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeLong(id);
        // false means that the session has expired
        dos.writeBoolean(false);
        qp.setData(bos.toByteArray());
        return qp;
    }

    /**
     * Forge an validate session packet as a LEARNER do
     * 
     * @return
     * @throws Exception
     */
    private QuorumPacket createValidateSessionQuorumPacket() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(SESSION_ID);
        dos.writeInt(3000);
        dos.close();
        QuorumPacket qp = new QuorumPacket(Leader.REVALIDATE, -1,
                baos.toByteArray(), null);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                    "To validate session 0x" + Long.toHexString(2L));
        }
        return qp;
    }

}