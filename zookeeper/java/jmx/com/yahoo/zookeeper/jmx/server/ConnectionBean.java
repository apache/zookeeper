/**
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper.jmx.server;

import java.util.Arrays;
import java.util.Date;

import org.apache.log4j.Logger;

import com.yahoo.zookeeper.jmx.MBeanRegistry;
import com.yahoo.zookeeper.jmx.ZKMBeanInfo;
import com.yahoo.zookeeper.server.ServerCnxn;
import com.yahoo.zookeeper.server.ZooKeeperServer;

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
        return Long.toHexString(connection.getSessionId());
    }

    
    public String getSourceIP() {
        return connection.getRemoteAddress().getAddress().getHostAddress()+
            ":"+connection.getRemoteAddress().getPort();
    }
    
    public String getName() {
        String ip=connection.getRemoteAddress().getAddress().getHostAddress();
        return MBeanRegistry.getInstance().makeFullPath("Connections", ip,getSessionId());
    }
    
    public boolean isHidden() {
        return false;
    }
    
    public String[] getEphemeralNodes() {
        if(zk.dataTree!=null){
            String[] res=zk.dataTree.getEphemerals(connection.getSessionId()).toArray(new String[0]);
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
            LOG.warn("Unable to closeSession() for session: "+getSessionId()+
                    ", "+e.getMessage());
        }
    }
    
    public void terminateConnection() {
        connection.close();
    }
    
    public String toString() {
        return "ConnectionBean{ClientIP="+getSourceIP()+",SessionId="+getSessionId()+"}";
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
