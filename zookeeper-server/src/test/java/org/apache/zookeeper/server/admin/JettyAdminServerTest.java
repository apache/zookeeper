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

package org.apache.zookeeper.server.admin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.ZooKeeperServerMainTest;
import org.apache.zookeeper.server.admin.AdminServer.AdminServerException;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyAdminServerTest extends ZKTestCase{
    protected static final Logger LOG = LoggerFactory.getLogger(JettyAdminServerTest.class);

    private static final String URL_FORMAT = "http://localhost:%d/commands";
    private static final int jettyAdminPort = PortAssignment.unique();

    @Before
    public void enableServer() {
        // Override setting in ZKTestCase
        System.setProperty("zookeeper.admin.enableServer", "true");
        System.setProperty("zookeeper.admin.serverPort", "" + jettyAdminPort);
    }

    /**
     * Tests that we can start and query a JettyAdminServer.
     */
    @Test
    public void testJettyAdminServer() throws AdminServerException, IOException {
        JettyAdminServer server = new JettyAdminServer();;
        try {
            server.start();
            queryAdminServer(jettyAdminPort);
        } finally {
            server.shutdown();
        }
    }

    /**
     * Starts a standalone server and tests that we can query its AdminServer.
     */
    @Test
    public void testStandalone() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();

        ZooKeeperServerMainTest.MainThread main = new ZooKeeperServerMainTest.MainThread(CLIENT_PORT, false, null);
        main.start();

        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT,
                ClientBase.CONNECTION_TIMEOUT));

        queryAdminServer(jettyAdminPort);

        main.shutdown();

        Assert.assertTrue("waiting for server down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT,
                        ClientBase.CONNECTION_TIMEOUT));
    }

    /**
     * Starts a quorum of two servers and tests that we can query both AdminServers.
     */
    @Test
    public void testQuorum() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT_QP1 = PortAssignment.unique();
        final int CLIENT_PORT_QP2 = PortAssignment.unique();

        final int ADMIN_SERVER_PORT1 = PortAssignment.unique();
        final int ADMIN_SERVER_PORT2 = PortAssignment.unique();

        String quorumCfgSection = String.format
            ("server.1=127.0.0.1:%d:%d;%d\nserver.2=127.0.0.1:%d:%d;%d",
             PortAssignment.unique(), PortAssignment.unique(), CLIENT_PORT_QP1,
             PortAssignment.unique(), PortAssignment.unique(), CLIENT_PORT_QP2
            );
        QuorumPeerTestBase.MainThread q1 = new QuorumPeerTestBase.MainThread(
                1, CLIENT_PORT_QP1, ADMIN_SERVER_PORT1, quorumCfgSection, null);
        q1.start();

        // Since JettyAdminServer reads a system property to determine its port,
        // make sure it initializes itself before setting the system property
        // again with the second port number
        Thread.sleep(500);

        QuorumPeerTestBase.MainThread q2 = new QuorumPeerTestBase.MainThread(
                2, CLIENT_PORT_QP2, ADMIN_SERVER_PORT2, quorumCfgSection, null);
        q2.start();

        Thread.sleep(500);

        Assert.assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                ClientBase.CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 2 being up",
                        ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                        ClientBase.CONNECTION_TIMEOUT));

        queryAdminServer(ADMIN_SERVER_PORT1);
        queryAdminServer(ADMIN_SERVER_PORT2);

        q1.shutdown();
        q2.shutdown();

        Assert.assertTrue("waiting for server 1 down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                        ClientBase.CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 2 down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2,
                        ClientBase.CONNECTION_TIMEOUT));
    }

    /**
     * Check that we can load the commands page of an AdminServer running at
     * localhost:port. (Note that this should work even if no zk server is set.)
     */
    private void queryAdminServer(int port) throws MalformedURLException, IOException {
        queryAdminServer(String.format(URL_FORMAT, port));
    }

    /**
     * Check that loading urlStr results in a non-zero length response.
     */
    private void queryAdminServer(String urlStr) throws MalformedURLException, IOException {
        URL url = new URL(urlStr);
        BufferedReader dis = new BufferedReader(new InputStreamReader((url.openStream())));
        String line = dis.readLine();
        Assert.assertTrue(line.length() > 0);
    }
}
