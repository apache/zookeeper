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
package org.apache.zookeeper.server.embedded;

import java.lang.management.ManagementFactory;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.MBeanServerInvocationHandler;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import org.apache.zookeeper.common.StringUtils;
import org.apache.zookeeper.server.ConnectionMXBean;
import org.apache.zookeeper.server.ZooKeeperServerBean;
import org.apache.zookeeper.server.quorum.LocalPeerMXBean;
import org.apache.zookeeper.server.quorum.QuorumBean;
import org.apache.zookeeper.server.quorum.QuorumMXBean;
import org.apache.zookeeper.server.quorum.RemotePeerMXBean;

public final class ZookeeperServeInfo {

    private static final MBeanServer localServer = ManagementFactory.getPlatformMBeanServer();

    private ZookeeperServeInfo() {
    }

    public static class PeerInfo {

        private final String name;
        private final String quorumAddress;
        private final String state;
        private final boolean leader;

        public PeerInfo(String name, String quorumAddress, String state, boolean leader) {
            this.name = name;
            this.quorumAddress = quorumAddress;
            this.state = state;
            this.leader = leader;
        }

        public String getName() {
            return name;
        }

        public String getQuorumAddress() {
            return quorumAddress;
        }

        public String getState() {
            return state;
        }

        public boolean isLeader() {
            return leader;
        }

        @Override
        public String toString() {
            return "PeerInfo{" + "name=" + name + ", leader=" + leader + ", quorumAddress=" + quorumAddress
                    + ", state=" + state + '}';
        }
    }

    public static class ConnectionInfo {

        private final String sourceip;
        private final String sessionid;
        private final String lastoperation;
        private final String lastResponseTime;
        private final String avgLatency;
        private final String lastLatency;
        private final String nodes;

        public ConnectionInfo(String sourceip, String sessionid, String lastoperation, String lastResponseTime,
                              String avgLatency, String lastLatency, String nodes) {
            this.sourceip = sourceip;
            this.sessionid = sessionid;
            this.lastoperation = lastoperation;
            this.lastResponseTime = lastResponseTime;
            this.avgLatency = avgLatency;
            this.lastLatency = lastLatency;
            this.nodes = nodes;
        }

        public String getLastLatency() {
            return lastLatency;
        }

        public String getSourceip() {
            return sourceip;
        }

        public String getSessionid() {
            return sessionid;
        }

        public String getLastoperation() {
            return lastoperation;
        }

        public String getLastResponseTime() {
            return lastResponseTime;
        }

        public String getAvgLatency() {
            return avgLatency;
        }

        public String getNodes() {
            return nodes;
        }

        @Override
        public String toString() {
            return "ConnectionInfo{" + "sourceip=" + sourceip + ", sessionid=" + sessionid + ", lastoperation="
                    + lastoperation + ", lastResponseTime=" + lastResponseTime + ", avgLatency=" + avgLatency
                    + ", nodes=" + nodes + '}';
        }
    }

    public static class ServerInfo {

        private final List<ConnectionInfo> connections = new ArrayList<>();
        private boolean leader;
        private boolean standaloneMode;
        public List<PeerInfo> peers = new ArrayList<>();

        public boolean isStandaloneMode() {
            return standaloneMode;
        }

        public List<ConnectionInfo> getConnections() {
            return connections;
        }

        public boolean isLeader() {
            return leader;
        }

        public List<PeerInfo> getPeers() {
            return Collections.unmodifiableList(peers);
        }

        public void addPeer(PeerInfo peer) {
            peers.add(peer);
        }

        @Override
        public String toString() {
            return "ServerInfo{" + "connections=" + connections + ", leader=" + leader + ", standaloneMode="
                    + standaloneMode + ", peers=" + peers + '}';
        }

    }

    public static ServerInfo getStatus() throws Exception {
        return getStatus("*");
    }

    public static ServerInfo getStatus(String beanName) throws Exception {

        ServerInfo info = new ServerInfo();
        boolean standalonemode = false;
        // org.apache.ZooKeeperService:name0=ReplicatedServer_id1,name1=replica.1,name2=Follower,name3=Connections,
        // name4=10.168.10.119,name5=0x13e83353764005a
        // org.apache.ZooKeeperService:name0=ReplicatedServer_id2,name1=replica.2,name2=Leader
        if (StringUtils.isBlank(beanName)) {
            beanName = "*";
        }
        ObjectName objectName = new ObjectName("org.apache.ZooKeeperService:name0=" + beanName);
        Set<ObjectInstance> first_level_beans = localServer.queryMBeans(objectName, null);
        if (first_level_beans.isEmpty()) {
            throw new IllegalStateException("No ZooKeeper server found in this JVM with name " + objectName);
        }
        String myName = "";
        for (ObjectInstance o : first_level_beans) {
            if (o.getClassName().equalsIgnoreCase(ZooKeeperServerBean.class.getName())) {
                standalonemode = true;
                info.leader = true;
                info.addPeer(new PeerInfo("local", "local", "STANDALONE", true));
            } else if (o.getClassName().equalsIgnoreCase(QuorumBean.class.getName())) {
                standalonemode = false;
                try {
                    QuorumMXBean quorum = MBeanServerInvocationHandler.newProxyInstance(localServer, o.getObjectName(),
                            QuorumMXBean.class, false);
                    myName = quorum.getName();
                } catch (UndeclaredThrowableException err) {
                    if (err.getCause() instanceof javax.management.InstanceNotFoundException) {
                        // maybe server not yet started or already stopped ?
                    } else {
                        throw err;
                    }
                }
            }
        }
        info.standaloneMode = standalonemode;
        if (standalonemode) {
            Set<ObjectInstance> connectionsbeans = localServer.queryMBeans(new ObjectName(
                    "org.apache.ZooKeeperService:name0=*,name1=Connections,name2=*,name3=*"), null);
            for (ObjectInstance conbean : connectionsbeans) {
                ConnectionMXBean cc = MBeanServerInvocationHandler.
                        newProxyInstance(localServer, conbean.getObjectName(), ConnectionMXBean.class, false);
                try {
                    String nodes = "";
                    if (cc.getEphemeralNodes() != null) {
                        nodes = Arrays.asList(cc.getEphemeralNodes()) + "";
                    }
                    info.connections.add(new ConnectionInfo(cc.getSourceIP(), cc.getSessionId(), cc.getLastOperation(),
                            cc.getLastResponseTime(), cc.getAvgLatency() + "", cc.getLastLatency() + "", nodes));
                } catch (Exception ex) {
                    if (ex instanceof InstanceNotFoundException && ex.getCause() instanceof InstanceNotFoundException) {
                        // SKIP
                    } else {
                        throw ex;
                    }
                }
            }
        } else {
            if (myName.isEmpty()) {
                throw new IllegalStateException(
                        "Cannot find local JMX name for current node, in quorum mode, scanned " + first_level_beans);
            }
            boolean leader = false;
            Set<ObjectInstance> replicas = localServer.queryMBeans(new ObjectName(
                    "org.apache.ZooKeeperService:name0=" + myName + ",name1=*"), null);
            for (ObjectInstance o : replicas) {
                if (o.getClassName().toLowerCase().contains("local")) {
                    LocalPeerMXBean local = MBeanServerInvocationHandler.
                            newProxyInstance(localServer, o.getObjectName(), LocalPeerMXBean.class, false);
                    info.addPeer(new PeerInfo(local.getName(), local.getQuorumAddress(), local.getState() + "",
                            local.isLeader()));

                    ObjectName asfollowername = new ObjectName(o.getObjectName() + ",name2=Follower");
                    ObjectName asleadername = new ObjectName(o.getObjectName() + ",name2=Leader");
                    boolean isleader = localServer.isRegistered(asleadername);
                    Set<ObjectInstance> connectionsbeans = null;
                    if (isleader) {
                        leader = true;
                        ObjectName asleaderconnections = new ObjectName(
                                asleadername + ",name3=Connections,name4=*,name5=*");
                        connectionsbeans = localServer.queryMBeans(asleaderconnections, null);
                    } else {
                        leader = false;
                        ObjectName asfollowernameconnections = new ObjectName(
                                asfollowername + ",name3=Connections,name4=*,name5=*");
                        connectionsbeans = localServer.queryMBeans(asfollowernameconnections, null);
                    }

                    for (ObjectInstance conbean : connectionsbeans) {
                        ConnectionMXBean cc = MBeanServerInvocationHandler.newProxyInstance(localServer,
                                conbean.getObjectName(), ConnectionMXBean.class, false);
                        try {
                            String nodes = "";
                            if (cc.getEphemeralNodes() != null) {
                                nodes = Arrays.asList(cc.getEphemeralNodes()) + "";
                            }
                            info.connections.add(new ConnectionInfo(cc.getSourceIP(), cc.getSessionId(), cc.
                                    getLastOperation(), cc.getLastResponseTime(), cc.getAvgLatency() + "", cc.
                                    getLastLatency() + "", nodes));
                        } catch (Exception ex) {
                            if (ex instanceof InstanceNotFoundException && ex.getCause() instanceof InstanceNotFoundException) {
                                // SKIP
                            } else {
                                throw ex;
                            }
                        }
                    }
                } else {
                    RemotePeerMXBean remote = MBeanServerInvocationHandler.newProxyInstance(localServer, o.
                            getObjectName(), RemotePeerMXBean.class, false);
                    info.addPeer(new PeerInfo(remote.getName(), remote.getQuorumAddress(),
                            "REMOTE", remote.isLeader()));
                }

            }
            info.leader = leader;
        }
        return info;
    }
}
