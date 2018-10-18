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

package org.apache.zookeeper.server.quorum.auth;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase.MainThread;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QuorumAuthTestBase provides a base class for testing quorum peer mutual
 * authentication using SASL mechanisms.
 */
public class QuorumAuthTestBase extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(QuorumAuthTestBase.class);
    protected List<MainThread> mt = new ArrayList<MainThread>();
    protected static File jaasConfigDir;

    public static void setupJaasConfig(String jaasEntries) {
        try {
            jaasConfigDir = ClientBase.createTmpDir();
            File saslConfFile = new File(jaasConfigDir, "jaas.conf");
            FileWriter fwriter = new FileWriter(saslConfFile);
            fwriter.write(jaasEntries);
            fwriter.close();
            System.setProperty("java.security.auth.login.config",
                    saslConfFile.getAbsolutePath());
        } catch (IOException ioe) {
            LOG.error("Failed to create tmp directory to hold JAAS conf file", ioe);
            // could not create tmp directory to hold JAAS conf file : test will
            // fail now.
        }
    }

    public static void cleanupJaasConfig() {
        if (jaasConfigDir != null) {
            FileUtils.deleteQuietly(jaasConfigDir);
        }
    }

    protected String startQuorum(final int serverCount,
            Map<String, String> authConfigs, int authServerCount) throws IOException {
        StringBuilder connectStr = new StringBuilder();
        final int[] clientPorts = startQuorum(serverCount, connectStr,
                authConfigs, authServerCount);
        for (int i = 0; i < serverCount; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            ClientBase.CONNECTION_TIMEOUT));
        }
        return connectStr.toString();
    }

    protected int[] startQuorum(final int serverCount, StringBuilder connectStr,
            Map<String, String> authConfigs, int authServerCount) throws IOException {
        final int clientPorts[] = new int[serverCount];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < serverCount; i++) {
            clientPorts[i] = PortAssignment.unique();
            String server = String.format(
                    "server.%d=localhost:%d:%d:participant", i,
                    PortAssignment.unique(), PortAssignment.unique());
            sb.append(server + "\n");
            connectStr.append("127.0.0.1:" + clientPorts[i]);
            if (i < serverCount - 1) {
                connectStr.append(",");
            }
        }
        String quorumCfg = sb.toString();
        // servers with authentication interfaces configured
        int i = 0;
        for (; i < authServerCount; i++) {
            startServer(authConfigs, clientPorts, quorumCfg, i);
        }
        // servers without any authentication configured
        for (int j = 0; j < serverCount - authServerCount; j++, i++) {
            MainThread mthread = new MainThread(i, clientPorts[i], quorumCfg);
            mt.add(mthread);
            mthread.start();
        }
        return clientPorts;
    }

    private void startServer(Map<String, String> authConfigs,
            final int[] clientPorts, String quorumCfg, int i)
                    throws IOException {
        MainThread mthread = new MainThread(i, clientPorts[i], quorumCfg,
                authConfigs);
        mt.add(mthread);
        mthread.start();
    }

    protected void startServer(MainThread restartPeer,
            Map<String, String> authConfigs) throws IOException {
        MainThread mthread = new MainThread(restartPeer.getMyid(),
                restartPeer.getClientPort(), restartPeer.getQuorumCfgSection(),
                authConfigs);
        mt.add(mthread);
        mthread.start();
    }

    void shutdownAll() {
        for (int i = 0; i < mt.size(); i++) {
            shutdown(i);
        }
    }

    MainThread shutdown(int index) {
        MainThread mainThread = mt.get(index);
        try {
            mainThread.shutdown();
        } catch (InterruptedException e) {
        } finally {
            mt.remove(index);
        }
        mainThread.deleteBaseDir();
        return mainThread;
    }
}
