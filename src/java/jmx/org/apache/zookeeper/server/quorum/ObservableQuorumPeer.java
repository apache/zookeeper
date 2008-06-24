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

package org.apache.zookeeper.server.quorum;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.util.EventInfo;
import org.apache.zookeeper.server.util.ObservableComponent;
import org.apache.zookeeper.server.util.ObserverManager;
import org.apache.zookeeper.server.util.QuorumPeerObserver;

/**
 * The observable quorum peer sends notifications to all registered observers
 * when its state changes. Events of interest include peer startup, shutdown and
 * the initiation of a leader election protocol.
 * <p>
 * In order to be able to receive QuorumPeer notifications, application must 
 * implement {@link QuorumPeerObserver} and register an instance of the interface
 * with {@link ObserverManager}.
 */
public class ObservableQuorumPeer extends QuorumPeer implements ObservableComponent{

    private enum Event{
        STARTUP(){
            public void dispatch(ObservableQuorumPeer peer,QuorumPeerObserver ob){
                ob.onStartup(peer);
            }
        },
        SHUTDOWN(){
            public void dispatch(ObservableQuorumPeer peer,QuorumPeerObserver ob){
                ob.onShutdown(peer);                
            }            
        },
        LEADER_ELECTION(){
            public void dispatch(ObservableQuorumPeer peer,QuorumPeerObserver ob){
                ob.onLeaderElectionStarted(peer);                
            }
        };
        public abstract void dispatch(ObservableQuorumPeer peer,QuorumPeerObserver ob);
    }
    
    public ObservableQuorumPeer(ArrayList<QuorumServer> quorumPeers,
            File dataDir, File dataLogDir, int electionAlg,    int electionPort,long myid, 
            int tickTime, int initLimit, int syncLimit,NIOServerCnxn.Factory cnxnFactory)
            throws IOException {
        super(quorumPeers, dataDir, dataLogDir,electionAlg,electionPort, myid,
                tickTime, initLimit, syncLimit,cnxnFactory);
    }

    public ObservableQuorumPeer(NIOServerCnxn.Factory cnxnFactory) throws IOException {
        super(cnxnFactory);
    }

    // instantiate an observable follower
    protected Follower makeFollower(File dataDir,File dataLogDir) throws IOException {
        return new ObservableFollower(this, new ObservableFollowerZooKeeperServer(dataDir,
                dataLogDir, this,new ZooKeeperServer.BasicDataTreeBuilder()));
    }

    // instantiate an observable leader
    protected Leader makeLeader(File dataDir,File dataLogDir) throws IOException {
        return new ObservableLeader(this, new ObservableLeaderZooKeeperServer(dataDir, 
                dataLogDir,this,new ZooKeeperServer.BasicDataTreeBuilder()));
    }

    public void run() {
        try {
            ObserverManager.getInstance().notifyObservers(this, Event.STARTUP);
            super.run();
        } finally {
            ObserverManager.getInstance().notifyObservers(this, Event.SHUTDOWN);
        }
    }

    public void dispatchEvent(Object observer, Object args) {
        if(args instanceof ObservableQuorumPeer.Event)
            ((Event)args).dispatch(this,(QuorumPeerObserver)observer);
        else
            ((EventInfo)args).dispatch(this,observer);        
    }

    // this method is called by the base class when leader election is about to
    // start; override the method to send a notification before election protocol 
    // started
    protected Election makeLEStrategy() {
        ObserverManager.getInstance().notifyObservers(this,Event.LEADER_ELECTION);
        return super.makeLEStrategy();
    }
}
