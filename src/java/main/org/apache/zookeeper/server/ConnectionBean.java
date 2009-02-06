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

/**
 * Implementation of connection MBean interface.
 */
public class ConnectionBean implements ConnectionMXBean, ZKMBeanInfo {
    private static final Logger LOG = Logger.getLogger(ConnectionBean.class);
    private ServerCnxn connection;
    private ZooKeeperServer zk;
    private Date timeCreated;
    
    public ConnectionBean(ServerCnxn connection,ZooKeeperServer zk){
        this.connection=connection;
        this.zk=zk;
        timeCreated=new Date();
    }
    
    public String getSessionId() {
        return "0x" + Long.toHexString(connection.getSessionId());
    }

    public String getSourceIP() {
        InetSocketAddress sockAddr = connection.getRemoteAddress();
        return sockAddr.getAddress().getHostAddress()
            + ":" + sockAddr.getPort();
    }

    public String getName() {
        InetAddress addr = connection.getRemoteAddress().getAddress();
        String ip = addr.getHostAddress();
        if (addr instanceof Inet6Address) {
            ip = ObjectName.quote(ip);
        }
        return MBeanRegistry.getInstance().makeFullPath("Connections", ip,
                getSessionId());
    }
    
    public boolean isHidden() {
        return false;
    }
    
    public String[] getEphemeralNodes() {
        if(zk.dataTree!=null){
            String[] res=zk.dataTree.getEphemerals(connection.getSessionId())
                .toArray(new String[0]);
            Arrays.sort(res);
            return res;
        }
        return null;
    }
    
    public String getStartedTime() {
        return timeCreated.toString();
    }
    
    public void terminateSession() {
        try {
            zk.closeSession(connection.getSessionId());
        } catch (Exception e) {
            LOG.warn("Unable to closeSession() for session: 0x" 
                    + getSessionId(), e);
        }
    }
    
    public void terminateConnection() {
        connection.close();
    }

    @Override
    public String toString() {
        return "ConnectionBean{ClientIP=" + ObjectName.quote(getSourceIP())
            + ",SessionId=0x" + getSessionId() + "}";
    }
    
    public long getOutstandingRequests() {
        return connection.getStats().getOutstandingRequests();
    }
    
    public long getPacketsReceived() {
        return connection.getStats().getPacketsReceived();
    }
    
    public long getPacketsSent() {
        return connection.getStats().getPacketsSent();
    }
    
    public int getSessionTimeout() {
        return connection.getSessionTimeout();
    }

}
