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

package org.apache.zookeeper.server.util;

import java.util.HashSet;
import java.util.Set;

import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ZooKeeperObserverNotifier;
import org.apache.zookeeper.server.quorum.QuorumPeer;

/**
 * Zookeeper specific implementation of ObserverManager. It implements a mapping
 * of observer classes to a set of observer instances.
 */
public class ZooKeeperObserverManager extends ObserverManager {

    private ZooKeeperObserverManager(){}
    
    /**
     * Explicitly set this class as a concrete instance of ObserverManager.
     */
    public static void setAsConcrete(){
        setInstance(new ZooKeeperObserverManager());
    }
    
    protected Set<Object> getObserverList(Object ob){
        if(ob instanceof ConnectionObserver || ob instanceof ServerCnxn)
            return connectionObservers;
        else if(ob instanceof ServerObserver || ob instanceof ZooKeeperObserverNotifier)
            return serverObservers;
        else if(ob instanceof QuorumPeerObserver || ob instanceof QuorumPeer)
            return quorumPeerObservers;
        else if(ob instanceof DataTreeObserver || ob instanceof DataTree)
            return dataTreeObservers;
        assert false;
        return null;
    }
    
    private Set<Object> serverObservers=new HashSet<Object>();
    private Set<Object> connectionObservers=new HashSet<Object>();
    private Set<Object> dataTreeObservers=new HashSet<Object>();
    private Set<Object> quorumPeerObservers=new HashSet<Object>();
}
