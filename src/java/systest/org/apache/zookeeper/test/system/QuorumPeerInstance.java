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
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;

class QuorumPeerInstance implements Instance {
    final private static Logger LOG = LoggerFactory.getLogger(QuorumPeerInstance.class);
    private static final int syncLimit = 3;
    private static final int initLimit = 3;
    private static final int tickTime = 2000;
    String serverHostPort;
    int serverId;
    Reporter r;
    QuorumPeer peer;

    public void setReporter(Reporter r) {
        this.r = r;
    }

    InetSocketAddress clientAddr;
    InetSocketAddress quorumAddr;
    HashMap<Long, QuorumServer> peers;
    File snapDir, logDir;

    public QuorumPeerInstance() {
        try {
            File tmpFile = File.createTempFile("test", ".dir");
            File tmpDir = tmpFile.getParentFile();
            tmpFile.delete();
            File zkDirs = new File(tmpDir, "zktmp.cfg");
            logDir = tmpDir;
            snapDir = tmpDir;
            Properties p;
            if (zkDirs.exists()) {
                p = new Properties();
                p.load(new FileInputStream(zkDirs));
            } else {
                p = System.getProperties();
            }
            logDir = new File(p.getProperty("logDir", tmpDir.getAbsolutePath()));
            snapDir = new File(p.getProperty("snapDir", tmpDir.getAbsolutePath()));
            logDir = File.createTempFile("zktst", ".dir", logDir);
            logDir.delete();
            logDir.mkdirs();
            snapDir = File.createTempFile("zktst", ".dir", snapDir);
            snapDir.delete();
            snapDir.mkdirs();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void configure(String params) {
        if (clientAddr == null) {
            String parts[] = params.split(" ");
            // The first time we are configured, it is just to tell
            // us which machine we are
            serverId = Integer.parseInt(parts[0]);
            if (LOG.isDebugEnabled()) {
                LOG.info("Setting up server " + serverId);
            }
            if (parts.length > 1 && parts[1].equals("false")) {
                System.setProperty("zookeeper.leaderServes", "no");
            } else {
                System.setProperty("zookeeper.leaderServes", "yes");
            }
            // Let's grab two ports
            try {
                ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLocalHost());
                clientAddr = (InetSocketAddress) ss.getLocalSocketAddress();
                ss.close();
            } catch(IOException e) {
                e.printStackTrace();
            }
            try {
                ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLocalHost());
                quorumAddr = (InetSocketAddress) ss.getLocalSocketAddress();
                ss.close();
            } catch(IOException e) {
                e.printStackTrace();
            }
            String report = clientAddr.getHostName() + ':' + clientAddr.getPort() +
            ',' + quorumAddr.getHostName() + ':' + quorumAddr.getPort();
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Reporting " + report);
                }
                r.report(report);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return;
        } else {
            int spaceIndex = params.indexOf(' ');
            if (spaceIndex == -1) {
                LOG.warn("looking for host:port,... start|stop, but found " + params);
                return;
            }
            String quorumSpecs = params.substring(0, spaceIndex);
            String cmd = params.substring(spaceIndex+1);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Running command: " + cmd);
            }
            if (!cmd.equals("start")) {
                if (peer != null) {
                    peer.shutdown();
                }
                peer = null;
                try {
                    for(int i = 0; i < 5; i++) {
                        Thread.sleep(500);
                        try {
                            // Wait until we can't connect
                            new Socket("127.0.0.1", clientAddr.getPort()).close();
                        } catch(IOException e) { break; }
                    }
                    r.report("stopped");
                } catch (Exception e) {
                    LOG.error("Unhandled error", e);
                }
                return;
            }
            String parts[] = quorumSpecs.split(",");
            peers = new HashMap<Long,QuorumServer>();
            for(int i = 0; i < parts.length; i++) {
                String subparts[] = parts[i].split(":");
                peers.put(Long.valueOf(i), new QuorumServer(i, new InetSocketAddress(subparts[0], Integer.parseInt(subparts[1]))));
            }
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Starting quorumPeer " + serverId + " on port " + clientAddr.getPort());
                }
                if (peer != null) {
                    LOG.warn("Peer " + serverId + " already started");
                    return;
                }
                System.err.println("SnapDir = " + snapDir + " LogDir = " + logDir);
                peer = new QuorumPeer(peers, snapDir, logDir, clientAddr.getPort(), 0, serverId, tickTime, initLimit, syncLimit);
                peer.start();
                for(int i = 0; i < 5; i++) {
                    Thread.sleep(500);
                    try {
                        // Wait until we can connect
                        new Socket("127.0.0.1", clientAddr.getPort()).close();
                        break;
                    } catch(IOException e) {}
                }
                r.report("started");
            } catch (Exception e) {
                LOG.error("Unhandled exception", e);
            }
        }
    }

    public void start() {
    }

    static private void recursiveDelete(File dir) {
        if (!dir.isDirectory()) {
            dir.delete();
            return;
        }
        for(File f: dir.listFiles()) {
            recursiveDelete(f);
        }
        dir.delete();
    }
    
    public void stop() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Stopping peer " + serverId);
        }
        if (peer != null) {
            peer.shutdown();
        }
        if (logDir != null) {
            recursiveDelete(logDir);
        }
        if (snapDir != null) {
            recursiveDelete(snapDir);
        }
    }

    /**
     * This method is used to configure a QuorumPeerInstance
     * 
     * @param im the InstanceManager that will be managing the new instance
     * @param i the server number to configure (should be zero based)
     * @throws NoAvailableContainers
     * @throws DuplicateNameException
     * @throws InterruptedException
     * @throws KeeperException
     */
    public static String[] createServer(InstanceManager im, int i) throws NoAvailableContainers, DuplicateNameException, InterruptedException, KeeperException {
        return createServer(im, i, true);
    }
    
    /**
     * This method is used to configure a QuorumPeerInstance
     * 
     * @param im the InstanceManager that will be managing the new instance
     * @param i the server number to configure (should be zero based)
     * @param leaderServes if false, the leader will not accept client connections
     * @throws NoAvailableContainers
     * @throws DuplicateNameException
     * @throws InterruptedException
     * @throws KeeperException
     */
    public static String[] createServer(InstanceManager im, int i, boolean leaderServes) throws NoAvailableContainers, DuplicateNameException, InterruptedException, KeeperException {
        im.assignInstance("server"+i, QuorumPeerInstance.class, Integer.toString(i) + " " + leaderServes, 50);
        return im.getStatus("server"+i, 3000).split(",");
        
    }

    /**
     * Start an instance of the quorumPeer.
     * @param im the manager of the instance
     * @param quorumHostPort the comma-separated list of host:port pairs of quorum peers 
     * @param index the zero based index of the server to start.
     * @throws InterruptedException
     * @throws KeeperException
     * @throws NoAssignmentException
     */
    public static void startInstance(InstanceManager im, String quorumHostPort, int index) throws InterruptedException, KeeperException, NoAssignmentException {
        im.resetStatus("server" + index);
        im.reconfigureInstance("server"+index, quorumHostPort + " start");
        im.getStatus("server" + index, 5000);
    }

    /**
     * Stop an instance of the quorumPeer
     * @param im the manager of the instance
     * @param index the zero based index fo the server to stop
     * @throws InterruptedException
     * @throws KeeperException
     * @throws NoAssignmentException
     */
    public static void stopInstance(InstanceManager im, int index) throws InterruptedException, KeeperException, NoAssignmentException {
        im.resetStatus("server" + index);
        im.reconfigureInstance("server"+index, Integer.toString(index) + " stop");
        im.getStatus("server" + index, 3000);
   
    }

}
