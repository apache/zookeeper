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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Date;

import javax.management.ObjectName;

import org.apache.log4j.Logger;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.jmx.ZKMBeanInfo;
import org.apache.zookeeper.server.NIOServerCnxn.CnxnStats;

/**
 * Implementation of connection MBean interface.
 */
public class ConnectionBean implements ConnectionMXBean, ZKMBeanInfo {
    private static final Logger LOG = Logger.getLogger(ConnectionBean.class);

    private final ServerCnxn connection;
    private final CnxnStats stats;

    private final ZooKeeperServer zk;
    
    private final String remoteIP;
    private final long sessionId;

    public ConnectionBean(ServerCnxn connection,ZooKeeperServer zk){
        this.connection = connection;
        this.stats = (CnxnStats)connection.getStats();
        this.zk = zk;
        
        InetSocketAddress sockAddr = connection.getRemoteAddress();
        if (sockAddr == null) {
            remoteIP = "Unknown";
        } else {
            InetAddress addr = sockAddr.getAddress();
            if (addr instanceof Inet6Address) {
                remoteIP = ObjectName.quote(addr.getHostAddress());
            } else {
                remoteIP = addr.getHostAddress();
            }
        }
        sessionId = connection.getSessionId();
    }
    
    public String getSessionId() {
        return "0x" + Long.toHexString(sessionId);
    }

    public String getSourceIP() {
        InetSocketAddress sockAddr = connection.getRemoteAddress();
        if (sockAddr == null) {
            return null;
        }
        return sockAddr.getAddress().getHostAddress()
            + ":" + sockAddr.getPort();
    }

    public String getName() {
        return MBeanRegistry.getInstance().makeFullPath("Connections", remoteIP,
                getSessionId());
    }
    
    public boolean isHidden() {
        return false;
    }
    
    public String[] getEphemeralNodes() {
        if(zk.getZKDatabase()  !=null){
            String[] res= zk.getZKDatabase().getEphemerals(sessionId)
                .toArray(new String[0]);
            Arrays.sort(res);
            return res;
        }
        return null;
    }
    
    public String getStartedTime() {
        return stats.getEstablished().toString();
    }
    
    public void terminateSession() {
        try {
            zk.closeSession(sessionId);
        } catch (Exception e) {
            LOG.warn("Unable to closeSession() for session: 0x" 
                    + getSessionId(), e);
        }
    }
    
    public void terminateConnection() {
        connection.sendCloseSession();
    }

    public void resetCounters() {
        stats.reset();
    }

    @Override
    public String toString() {
        return "ConnectionBean{ClientIP=" + ObjectName.quote(getSourceIP())
            + ",SessionId=0x" + getSessionId() + "}";
    }
    
    public long getOutstandingRequests() {
        return stats.getOutstandingRequests();
    }
    
    public long getPacketsReceived() {
        return stats.getPacketsReceived();
    }
    
    public long getPacketsSent() {
        return stats.getPacketsSent();
    }
    
    public int getSessionTimeout() {
        return connection.getSessionTimeout();
    }

    public long getMinLatency() {
        return stats.getMinLatency();
    }

    public long getAvgLatency() {
        return stats.getAvgLatency();
    }

    public long getMaxLatency() {
        return stats.getMaxLatency();
    }
    
    public String getLastOperation() {
        return stats.getLastOperation();
    }

    public String getLastCxid() {
        return "0x" + Long.toHexString(stats.getLastCxid());
    }

    public String getLastZxid() {
        return "0x" + Long.toHexString(stats.getLastZxid());
    }

    public String getLastResponseTime() {
        return new Date(stats.getLastResponseTime()).toString();
    }

    public long getLastLatency() {
        return stats.getLastLatency();
    }
}
