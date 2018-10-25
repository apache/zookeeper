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

package org.apache.zookeeper.test.system;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.runner.JUnitCore;

@Ignore("No tests in this class.")
public class BaseSysTest {
    private static final File testData = new File(
            System.getProperty("test.data.dir", "src/test/resources/data"));
    private static int fakeBasePort = 33222;
    private static String zkHostPort;
    protected String prefix = "/sysTest";
    ZooKeeper zk;
    static {
        try {
            zkHostPort = System.getProperty("sysTest.zkHostPort", InetAddress.getLocalHost().getCanonicalHostName() + ":2181");
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }
    InstanceManager im;
    @Before
    public void setUp() throws Exception {
        if (!fakeMachines) {
            zk = new ZooKeeper(zkHostPort, 15000, new Watcher() {public void process(WatchedEvent e){}});
            im = new InstanceManager(zk, prefix);
        }
    }
    @After
    public void tearDown() throws Exception {
        if (null != im) {
            im.close();
        }
    }

    int serverCount = defaultServerCount;
    int clientCount = defaultClientCount;
    static int defaultServerCount = 5;
    static int defaultClientCount = 7;
    static {
        defaultServerCount = Integer.parseInt(System.getProperty("simpleSysTest.defaultServerCount", Integer.toString(defaultServerCount)));
        defaultClientCount = Integer.parseInt(System.getProperty("simpleSysTest.defaultClientCount", Integer.toString(defaultClientCount)));
    }

    String serverHostPort;
    String quorumHostPort;
    public String getHostPort() {
        return serverHostPort;
    }
    public int getServerCount() {
        return serverCount;
    }
    public int getClientCount() {
        return clientCount;
    }

    public void startServers() throws IOException {
        for(int i = 0; i < serverCount; i++) {
            startServer(i);
        }
    }
    public void stopServers() throws IOException {
        for(int i = 0; i < serverCount; i++) {
            stopServer(i);
        }
    }
    public void startClients() throws IOException {
        for(int i = 0; i < clientCount; i++) {
            startClient(i);
        }
    }
    public void stopClients() throws IOException {
        for(int i = 0; i < clientCount; i++) {
            stopClient(i);
        }
    }

    private static boolean fakeMachines = System.getProperty("baseSysTest.fakeMachines", "no").equals("yes");

    public void configureServers(int count) throws Exception {
        serverCount = count;
        if (fakeMachines) {
            fakeConfigureServers(count);
        } else {
            distributedConfigureServers(count);
        }
    }

    private void distributedConfigureServers(int count) throws IOException {
        StringBuilder sbClient = new StringBuilder();
        StringBuilder sbServer = new StringBuilder();
        try {
            for(int i = 0; i < count; i++) {
                String r[] = QuorumPeerInstance.createServer(im, i);
                if (i > 0) {
                    sbClient.append(',');
                    sbServer.append(',');
                }
                sbClient.append(r[0]); // r[0] == "host:clientPort"
                sbServer.append(r[1]); // r[1] == "host:leaderPort:leaderElectionPort"
                sbServer.append(";"+(r[0].split(":"))[1]); // Appending ";clientPort"
            }
            serverHostPort = sbClient.toString();
            quorumHostPort = sbServer.toString();
        } catch(Exception e) {
            IOException ioe = new IOException(e.getMessage());
            ioe.setStackTrace(e.getStackTrace());
            throw ioe;
        }
    }

    private QuorumPeer qps[];
    private File qpsDirs[];
    Map<Long,QuorumServer> peers;
    private void fakeConfigureServers(int count) throws IOException {
        peers = new HashMap<Long,QuorumServer>();
        qps = new QuorumPeer[count];
        qpsDirs = new File[count];
        for(int i = 1; i <= count; i++) {
            InetSocketAddress peerAddress = new InetSocketAddress("127.0.0.1",
                    fakeBasePort + i);
            InetSocketAddress electionAddr = new InetSocketAddress("127.0.0.1",
                    serverCount + fakeBasePort + i);
            peers.put(Long.valueOf(i), new QuorumServer(i, peerAddress,
                    electionAddr));
        }
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < count; i++) {
            //make that testData exists otherwise it fails on windows
            testData.mkdirs();
            qpsDirs[i] = File.createTempFile("sysTest", ".tmp", testData);
            qpsDirs[i].delete();
            qpsDirs[i].mkdir();
            int port = fakeBasePort+10+i;
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append("localhost:");
            sb.append(port);
        }
        serverHostPort = sb.toString();
    }
    final static int tickTime = 2000;
    final static int initLimit = 3;
    final static int syncLimit = 3;

    public void startServer(int index) throws IOException {
        int port = fakeBasePort+10+index;
        if (fakeMachines) {
            qps[index] = new QuorumPeer(peers, qpsDirs[index], qpsDirs[index], port, 3, index+1, tickTime, initLimit, syncLimit);
            qps[index].start();
        } else {
            try {
                QuorumPeerInstance.startInstance(im, quorumHostPort, index);
            } catch(Exception e) {
                IOException ioe = new IOException(e.getClass().getName() + ": " + e.getMessage());
                ioe.setStackTrace(e.getStackTrace());
                throw ioe;
            }
        }
    }
    public void stopServer(int index) throws IOException {
        if (fakeMachines) {
            qps[index].shutdown();
        } else {
            try {
                QuorumPeerInstance.stopInstance(im, index);
            } catch(Exception e) {
                IOException ioe = new IOException(e.getMessage());
                ioe.setStackTrace(e.getStackTrace());
                throw ioe;
            }
        }
    }

    public void configureClients(int count, Class<? extends Instance> clazz, String params) throws Exception {
        clientCount = count;
        if (fakeMachines) {
            fakeConfigureClients(count, clazz, params);
        } else {
            distributedConfigureClients(count, clazz, params);
        }
    }
    private Class<? extends Instance> clazz;
    String params;
    private void distributedConfigureClients(int count, Class<? extends Instance> clazz, String params) throws IOException {
        this.clazz = clazz;
        this.params = params;

    }
    private Instance fakeBaseClients[];
    private void fakeConfigureClients(int count, Class<? extends Instance> clazz, String params) throws IOException, ClassNotFoundException {
        fakeBaseClients = new Instance[count];
        for(int i = 0; i < count; i++) {
            try {
                fakeBaseClients[i] = clazz.newInstance();
            } catch (InstantiationException e) {
                e.printStackTrace();
                return;
            } catch (IllegalAccessException e) {
                e.printStackTrace();
                return;
            }
            fakeBaseClients[i].configure(i + " " + params);
        }
    }
    public void startClient(int index) throws IOException {
        if (fakeMachines) {
            fakeStartClient(index);
        } else {
            distributedStartClient(index);
        }
    }
    private void distributedStartClient(int index) throws IOException {
        try {
            im.assignInstance("client" + index, clazz, index + " " + params, 1);
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }
    private void fakeStartClient(int index) {
        fakeBaseClients[index].start();
    }
    public void stopClient(int index) throws IOException {
        if (fakeMachines) {
            fakeStopClient(index);
        } else {
            distributedStopClient(index);
        }
    }
    private void distributedStopClient(int index) throws IOException {
        try {
            im.removeInstance("client"+index);
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }
    private void fakeStopClient(int index) {
        fakeBaseClients[index].stop();
    }
    
    static public void main(String args[]) {
        JUnitCore.main(args);
    }
}
