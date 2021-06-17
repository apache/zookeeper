/*
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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.CloseSessionTxn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CloseSessionTxnSizeTest extends ClientBase {

    private static final String JUTE_MAXBUFFER = "jute.maxbuffer";

    private static final int JUTE_MAXBUFFER_VALUE = 256;

    private String previousMaxBuffer;

    private ZooKeeper zk;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        previousMaxBuffer = System.setProperty(JUTE_MAXBUFFER, Integer.toString(JUTE_MAXBUFFER_VALUE));
        assertEquals(JUTE_MAXBUFFER_VALUE, BinaryInputArchive.maxBuffer, "Couldn't set jute.maxbuffer!");
        super.setUp();
        zk = createClient();
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        zk.close();
        if (previousMaxBuffer == null) {
            System.clearProperty(JUTE_MAXBUFFER);
        } else {
            System.setProperty(JUTE_MAXBUFFER, previousMaxBuffer);
        }
    }

    private static String makePath(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 'x');
        bytes[0] = (byte) '/';

        return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Test
    public void testCloseSessionTxnSizeFit() throws InterruptedException, IOException, KeeperException {
        // 4 bytes for vector length, 4 bytes for string length
        String path = makePath((JUTE_MAXBUFFER_VALUE - 4) / 2 - 4 - 1);

        zk.create(path + "x", null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        zk.create(path + "y", null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

        Set<String> paths = serverFactory.getZooKeeperServer()
            .getZKDatabase().getDataTree().getEphemerals(zk.getSessionId());

        // Ensure we are looking at the right session.
        assertEquals(2, paths.size(), "Ephemerals count");

        CloseSessionTxn txn = new CloseSessionTxn(new ArrayList<String>(paths));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);
        oa.writeRecord(txn, "CloseSessionTxn");

        // Check that our encoding assumptions hold.
        assertEquals(baos.size(), JUTE_MAXBUFFER_VALUE, "CloseSessionTxn size");
    }

    @Test
    public void testCloseSessionTxnSizeOverflow() throws KeeperException, InterruptedException {
        String path = makePath((JUTE_MAXBUFFER_VALUE - 4) / 2 - 4 - 1);

        zk.create(path + "x", null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

        try {
            zk.create(path + "yz", null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            fail();
        } catch (KeeperException.TooManyEphemeralsException e) {
            // Expected
        }
    }

    @Test
    public void testCloseSessionTxnSizeSequential() throws KeeperException, InterruptedException {
        String prefix = "/test-";
        String specimen = prefix + "0123456789";

        int nOk = (JUTE_MAXBUFFER_VALUE - 4) / (specimen.length() + 4);
        for (int i = 0; i < nOk; i++) {
            zk.create(prefix, null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
        }

        try {
            zk.create(prefix, null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
            fail();
        } catch (KeeperException.TooManyEphemeralsException e) {
            // Expected
        }
    }
}
