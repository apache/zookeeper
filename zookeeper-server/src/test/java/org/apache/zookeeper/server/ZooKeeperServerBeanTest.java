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
package org.apache.zookeeper.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.jute.Record;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ZooKeeperServerBeanTest {
    @Before
    public void setup() {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY,
                "org.apache.zookeeper.server.NettyServerCnxnFactory");
    }

    @After
    public void teardown() throws Exception {
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
    }

    @Test
    public void testTxnLogElapsedSyncTime() throws IOException {

        File tmpDir = ClientBase.createTmpDir();
        FileTxnSnapLog fileTxnSnapLog = new FileTxnSnapLog(new File(tmpDir, "data"),
                new File(tmpDir, "data_txnlog"));

        ZooKeeperServer zks = new ZooKeeperServer();
        zks.setTxnLogFactory(fileTxnSnapLog);

        ZooKeeperServerBean serverBean = new ZooKeeperServerBean(zks);
        long elapsedTime = serverBean.getTxnLogElapsedSyncTime();
        assertEquals(-1, elapsedTime);

        TxnHeader hdr = new TxnHeader(1, 1, 1, 1, ZooDefs.OpCode.setData);
        Record txn = new SetDataTxn("/foo", new byte[0], 1);
        Request req = new Request(0, 0, 0, hdr, txn, 0);

        try {

            zks.getTxnLogFactory().append(req);
            zks.getTxnLogFactory().commit();
            elapsedTime = serverBean.getTxnLogElapsedSyncTime();

            assertNotEquals(-1, elapsedTime);

            assertEquals(elapsedTime, serverBean.getTxnLogElapsedSyncTime());

        } finally {
            fileTxnSnapLog.close();
        }
    }

    @Test
    public void testGetSecureClientPort() throws IOException {
        ZooKeeperServer zks = new ZooKeeperServer();
        /**
         * case 1: When secure client is not configured GetSecureClientPort
         * should return empty string
         */
        ZooKeeperServerBean serverBean = new ZooKeeperServerBean(zks);
        String result = serverBean.getSecureClientPort();
        assertEquals("", result);

        /**
         * case 2: When secure client is configured GetSecureClientPort should
         * return configured port
         */

        ServerCnxnFactory cnxnFactory = ServerCnxnFactory.createFactory();
        int secureClientPort = 8443;
        InetSocketAddress address = new InetSocketAddress(secureClientPort);
        cnxnFactory.configure(address, 5, true);
        zks.setSecureServerCnxnFactory(cnxnFactory);

        result = serverBean.getSecureClientPort();
        assertEquals(Integer.toString(secureClientPort), result);

        // cleanup
        cnxnFactory.shutdown();

    }

    @Test
    public void testGetSecureClientAddress() throws IOException {
        ZooKeeperServer zks = new ZooKeeperServer();
        /**
         * case 1: When secure client is not configured getSecureClientAddress
         * should return empty string
         */
        ZooKeeperServerBean serverBean = new ZooKeeperServerBean(zks);
        String result = serverBean.getSecureClientPort();
        assertEquals("", result);

        /**
         * case 2: When secure client is configured getSecureClientAddress
         * should return configured SecureClientAddress
         */

        ServerCnxnFactory cnxnFactory = ServerCnxnFactory.createFactory();
        int secureClientPort = 8443;
        InetSocketAddress address = new InetSocketAddress(secureClientPort);
        cnxnFactory.configure(address, 5, true);
        zks.setSecureServerCnxnFactory(cnxnFactory);

        result = serverBean.getSecureClientAddress();
        String ipv4 = "0.0.0.0:" + secureClientPort;
        String ipv6 = "0:0:0:0:0:0:0:0:" + secureClientPort;
        assertTrue(result.equals(ipv4) || result.equals(ipv6));

        // cleanup
        cnxnFactory.shutdown();
    }

}
