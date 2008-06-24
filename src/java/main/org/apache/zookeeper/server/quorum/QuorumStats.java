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

package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.server.ServerStats;

public class QuorumStats extends ServerStats {
    
    public interface Provider {
        static public final String UNKNOWN_STATE="unknown";
        static public final String LOOKING_STATE="leaderelection";
        static public final String LEADING_STATE="leading";
        static public final String FOLLOWING_STATE="following";
        
        public String[] getQuorumPeers();
        public String getServerState();
    }
    private Provider qprovider=null;
    
    static public QuorumStats getInstance(){
        ServerStats i=ServerStats.getInstance();
        assert i==null || i instanceof QuorumStats;
        return (QuorumStats)i;
    }
    static public void registerAsConcrete(){
        assert getInstance()==null;
        setInstance(new QuorumStats());
    }
    protected QuorumStats(){}
    
    public void setStatsProvider(Provider newProvider){
        qprovider=newProvider;
    }
    
    public String getServerState(){
        return (qprovider!=null)?qprovider.getServerState():Provider.UNKNOWN_STATE;
    }
    
    public String[] getQuorumPeers(){
        return (qprovider!=null)?qprovider.getQuorumPeers():new String[0];
    }
    public String toString(){
        StringBuilder sb=new StringBuilder(super.toString());
        String state=getServerState();
        if(state.equals(Provider.LEADING_STATE)){
            sb.append("Followers:");
            for(String f: getQuorumPeers()){
                sb.append(" ").append(f);
            }
            sb.append("\n");            
        }else if(state.equals(Provider.FOLLOWING_STATE)){
            sb.append("Leader: ");
            String[] ldr=getQuorumPeers();
            if(ldr.length>0)
                sb.append(ldr[0]);
            else
                sb.append("not connected");
            sb.append("\n");
        }
        return sb.toString();
    }
}
