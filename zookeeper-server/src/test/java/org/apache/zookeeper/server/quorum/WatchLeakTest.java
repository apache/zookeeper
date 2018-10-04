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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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
import java.util.Collections;
import java.util.Random;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.MockPacket;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.proto.SetWatches;
import org.apache.zookeeper.server.MockNIOServerCnxn;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.MockSelectorThread;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.ZKParameterized;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrate ZOOKEEPER-1382 : Watches leak on expired session
 */
@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(ZKParameterized.RunnerFactory.class)
public class WatchLeakTest {

    protected static final Logger LOG = LoggerFactory
            .getLogger(WatchLeakTest.class);

    final long SESSION_ID = 0xBABEL;

    private final boolean sessionTimedout;

    @Before
    public void setUp() {
        System.setProperty("zookeeper.admin.enableServer", "false");
    }

    public WatchLeakTest(boolean sessionTimedout) {
        this.sessionTimedout = sessionTimedout;
    }

    @Parameters
    public static Collection<Object[]> configs() {
        return Arrays.asList(new Object[][] {
            { false }, { true },
        });
    }

    /**
     * Check that if session has expired then no watch can be set
     */

    @Test
    public void testWatchesLeak() throws Exception {

        NIOServerCnxnFactory serverCnxnFactory = mock(NIOServerCnxnFactory.class);
        final SelectionKey sk = new FakeSK();
        MockSelectorThread selectorThread = mock(MockSelectorThread.class);
        when(selectorThread.addInterestOpsUpdateRequest(any(SelectionKey.class))).thenAnswer(new Answer<Boolean>() {
            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable {
                SelectionKey sk = (SelectionKey)invocation.getArguments()[0];
                NIOServerCnxn nioSrvCnx = (NIOServerCnxn)sk.attachment();
                sk.interestOps(nioSrvCnx.getInterestOps());
                return true;
            }
        });

        ZKDatabase database = new ZKDatabase(null);
        database.setlastProcessedZxid(2L);
        QuorumPeer quorumPeer = mock(QuorumPeer.class);
        FileTxnSnapLog logfactory = mock(FileTxnSnapLog.class);
        // Directories are not used but we need it to avoid NPE
        when(logfactory.getDataDir()).thenReturn(new File(""));
        when(logfactory.getSnapDir()).thenReturn(new File(""));
        FollowerZooKeeperServer fzks = null;

        try {
            // Create a new follower
            fzks = new FollowerZooKeeperServer(logfactory, quorumPeer, database);
            fzks.startup();
            fzks.setServerCnxnFactory(serverCnxnFactory);
            quorumPeer.follower = new MyFollower(quorumPeer, fzks);
            LOG.info("Follower created");
            // Simulate a socket channel between a client and a follower
            final SocketChannel socketChannel = createClientSocketChannel();
            // Create the NIOServerCnxn that will handle the client requests
            final MockNIOServerCnxn nioCnxn = new MockNIOServerCnxn(fzks,
                    socketChannel, sk, serverCnxnFactory, selectorThread);
            sk.attach(nioCnxn);
            // Send the connection request as a client do
            nioCnxn.doIO(sk);
            LOG.info("Client connection sent");
            // Send the valid or invalid session packet to the follower
            QuorumPacket qp = createValidateSessionPacketResponse(!sessionTimedout);
            quorumPeer.follower.processPacket(qp);
            LOG.info("Session validation sent");
            // OK, now the follower knows that the session is valid or invalid, let's try
            // to send the watches
            nioCnxn.doIO(sk);
            // wait for the the request processor to do his job
            Thread.sleep(1000L);
            LOG.info("Watches processed");
            // If session has not been validated, there must be NO watches
            int watchCount = database.getDataTree().getWatchCount();
            if (sessionTimedout) {
                // Session has not been re-validated !
                LOG.info("session is not valid, watches = {}", watchCount);
                assertEquals("Session is not valid so there should be no watches", 0, watchCount);
            } else {
                // Session has been re-validated
                LOG.info("session is valid, watches = {}", watchCount);
                assertEquals("Session is valid so the watch should be there", 1, watchCount);
            }
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
            boolean reading = (ops & OP_READ) != 0;
            boolean writing = (ops & OP_WRITE) != 0;
            if (reading && writing) {
                LOG.info("Channel is ready for reading and writing");
            } else if (reading) {
                LOG.info("Channel is ready for reading only");
            } else if (writing) {
                LOG.info("Channel is ready for writing only");
            }
            return ops;
        }

    }

    /**
     * Create a watches message with a single watch on /
     *
     * @return a message that attempts to set 1 watch on /
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
     * @return a serialized connection request
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
     * @param valid <code>true</code> to create a valid session message
     *
     * @throws Exception
     */
    private QuorumPacket createValidateSessionPacketResponse(boolean valid) throws Exception {
        QuorumPacket qp = createValidateSessionPacket();
        ByteArrayInputStream bis = new ByteArrayInputStream(qp.getData());
        DataInputStream dis = new DataInputStream(bis);
        long id = dis.readLong();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeLong(id);
        // false means that the session has expired
        dos.writeBoolean(valid);
        qp.setData(bos.toByteArray());
        return qp;
    }

    /**
     * Forge an validate session packet as a LEARNER do
     *
     * @return
     * @throws Exception
     */
    private QuorumPacket createValidateSessionPacket() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(SESSION_ID);
        dos.writeInt(3000);
        dos.close();
        QuorumPacket qp = new QuorumPacket(Leader.REVALIDATE, -1,
                baos.toByteArray(), null);
        return qp;
    }

}
