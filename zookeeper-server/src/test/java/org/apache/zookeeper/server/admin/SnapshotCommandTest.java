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

package org.apache.zookeeper.server.admin;

import static org.apache.zookeeper.server.ZooKeeperServer.ZOOKEEPER_SERIALIZE_LAST_PROCESSED_ZXID_ENABLED;
import static org.apache.zookeeper.server.admin.Commands.SnapshotCommand.ADMIN_SNAPSHOT_ENABLED;
import static org.apache.zookeeper.server.admin.Commands.SnapshotCommand.ADMIN_SNAPSHOT_INTERVAL;
import static org.apache.zookeeper.server.admin.Commands.SnapshotCommand.REQUEST_QUERY_PARAM_STREAMING;
import static org.apache.zookeeper.server.admin.JettyAdminServerTest.URL_FORMAT;
import static org.apache.zookeeper.server.admin.JettyAdminServerTest.jettyAdminPort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.IOUtils;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SnapshotCommandTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotCommandTest.class);

    private static final String PATH = "/snapshot_test";
    private static final int NODE_COUNT = 10;

    private final String hostPort =  "127.0.0.1:" + PortAssignment.unique();
    private ServerCnxnFactory cnxnFactory;
    private JettyAdminServer adminServer;
    private ZooKeeperServer zks;
    private ZooKeeper zk;

    @TempDir
    static File dataDir;

    @TempDir
    static File logDir;

    @BeforeAll
    public void setup() throws Exception {
        // start ZookeeperServer
        System.setProperty("zookeeper.4lw.commands.whitelist", "*");
        zks = new ZooKeeperServer(dataDir, logDir, 3000);
        final int port = Integer.parseInt(hostPort.split(":")[1]);
        cnxnFactory = ServerCnxnFactory.createFactory(port, -1);
        cnxnFactory.startup(zks);
        assertTrue(ClientBase.waitForServerUp(hostPort, 120000));

        // start AdminServer
        System.setProperty("zookeeper.admin.enableServer", "true");
        System.setProperty("zookeeper.admin.serverPort", "" + jettyAdminPort);
        System.setProperty(ADMIN_SNAPSHOT_ENABLED, "true");
        System.setProperty(ADMIN_SNAPSHOT_INTERVAL, "0");
        System.setProperty(ZOOKEEPER_SERIALIZE_LAST_PROCESSED_ZXID_ENABLED, "true");

        adminServer = new JettyAdminServer();
        adminServer.setZooKeeperServer(zks);
        adminServer.start();

        // create Zookeeper client and test data
        zk = ClientBase.createZKClient(hostPort);
        createData(zk, NODE_COUNT);
    }

    @AfterAll
    public void tearDown() throws Exception {
        System.clearProperty("zookeeper.4lw.commands.whitelist");
        System.clearProperty("zookeeper.admin.enableServer");
        System.clearProperty("zookeeper.admin.serverPort");
        System.clearProperty(ADMIN_SNAPSHOT_ENABLED);
        System.clearProperty(ADMIN_SNAPSHOT_INTERVAL);
        System.clearProperty(ZOOKEEPER_SERIALIZE_LAST_PROCESSED_ZXID_ENABLED);

        if (zk != null) {
            zk.close();
        }

        if (adminServer != null) {
            adminServer.shutdown();
        }

        if (cnxnFactory != null) {
            cnxnFactory.shutdown();
        }

        if (zks != null) {
            zks.shutdown();
        }
    }

    @Test
    public void testSnapshotCommand_streaming() throws Exception {
        // take snapshot with streaming
        final HttpURLConnection snapshotConn = sendSnapshotRequest(true);

        // validate snapshot response
        assertEquals(HttpURLConnection.HTTP_OK, snapshotConn.getResponseCode());
        validateResponseHeaders(snapshotConn);
        final File snapshotFile = new File(dataDir + "/snapshot." + System.currentTimeMillis());
        try (final InputStream inputStream = snapshotConn.getInputStream();
             final FileOutputStream outputStream = new FileOutputStream(snapshotFile)) {
            IOUtils.copyBytes(inputStream, outputStream, 1024, true);
            final long fileSize = Files.size(snapshotFile.toPath());
            assertTrue(fileSize > 0);
        }
    }

    @Test
    public void testSnapshotCommand_nonStreaming() throws Exception {
        // take snapshot without streaming
        final HttpURLConnection snapshotConn = sendSnapshotRequest(false);

        // validate snapshot response
        assertEquals(HttpURLConnection.HTTP_OK, snapshotConn.getResponseCode());
        validateResponseHeaders(snapshotConn);
        displayResponsePayload(snapshotConn);
    }

    @Test
    public void testSnapshotCommand_disabled() throws Exception {
        System.setProperty(ADMIN_SNAPSHOT_ENABLED, "false");
        try {
            // take snapshot
            final HttpURLConnection snapshotConn = sendSnapshotRequest(true);

            // validate snapshot response
            assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, snapshotConn.getResponseCode());
        } finally {
            System.setProperty(ADMIN_SNAPSHOT_ENABLED, "true");
        }
    }

    @Test
    public void testSnapshotCommand_serializeLastZxidDisabled() throws Exception {
        ZooKeeperServer.setSerializeLastProcessedZxidEnabled(false);
        try {
            // take snapshot
            final HttpURLConnection snapshotConn = sendSnapshotRequest(true);

            // validate snapshot response
            assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, snapshotConn.getResponseCode());
        } finally {
            ZooKeeperServer.setSerializeLastProcessedZxidEnabled(true);
        }
    }

    private void createData(final ZooKeeper zk, final long count) throws Exception {
        try {
            zk.create(PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (final KeeperException.NodeExistsException ignore) {
            // ignore
        }

        for (int i = 0; i < count; i++) {
            final String processNodePath = zk.create(String.format("%s/%s", PATH, "n_"), new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
            LOG.info("Node created. path={}" + processNodePath);
        }
    }

    private HttpURLConnection sendSnapshotRequest(final boolean streaming) throws Exception  {
        final String queryParamsStr = buildQueryStringForSnapshotCommand(streaming);
        final URL snapshotURL = new URL(String.format(URL_FORMAT + "/snapshot", jettyAdminPort) + "?" + queryParamsStr);
        final HttpURLConnection snapshotConn = (HttpURLConnection) snapshotURL.openConnection();
        snapshotConn.setRequestMethod("GET");

        return snapshotConn;
    }

    private String buildQueryStringForSnapshotCommand(final boolean streaming) throws Exception {
        final Map<String, String> parameters = new HashMap<>();
        parameters.put(REQUEST_QUERY_PARAM_STREAMING, String.valueOf(streaming));
        return getParamsString(parameters);
    }

    private static String getParamsString(final Map<String, String> params) throws UnsupportedEncodingException {
        final StringBuilder result = new StringBuilder();

        for (final Map.Entry<String, String> entry : params.entrySet()) {
            result.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            result.append("=");

            result.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            result.append("&");
        }

        final String resultString = result.toString();
        return resultString.length() > 0
                ? resultString.substring(0, resultString.length() - 1)
                : resultString;
    }

    private void validateResponseHeaders(final HttpURLConnection conn) {
        LOG.info("Header:{}, Value:{}",
                Commands.SnapshotCommand.RESPONSE_HEADER_LAST_ZXID,
                conn.getHeaderField(Commands.SnapshotCommand.RESPONSE_HEADER_LAST_ZXID));
        assertNotNull(conn.getHeaderField(Commands.SnapshotCommand.RESPONSE_HEADER_LAST_ZXID));

        LOG.info("Header:{}, Value:{}",
                Commands.SnapshotCommand.RESPONSE_HEADER_SNAPSHOT_SIZE,
                conn.getHeaderField(Commands.SnapshotCommand.RESPONSE_HEADER_SNAPSHOT_SIZE));
        assertNotNull(conn.getHeaderField(Commands.SnapshotCommand.RESPONSE_HEADER_SNAPSHOT_SIZE));
        assertTrue(Integer.parseInt(conn.getHeaderField(Commands.SnapshotCommand.RESPONSE_HEADER_SNAPSHOT_SIZE)) > 0);
    }

    private void displayResponsePayload(final HttpURLConnection conn) throws IOException {
        final StringBuilder sb = new StringBuilder();
        try (final BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                sb.append(inputLine);
            }
            LOG.info("Response payload: {}", sb);
        }
    }
}
