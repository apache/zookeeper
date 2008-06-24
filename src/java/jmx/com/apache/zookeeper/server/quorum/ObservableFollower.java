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

package com.yahoo.zookeeper.server.quorum;

import com.yahoo.zookeeper.server.util.EventInfo;
import com.yahoo.zookeeper.server.util.ObserverManager;
import com.yahoo.zookeeper.server.util.QuorumPeerObserver;

/**
 * This observable follower implementation notifies its registered observers
 * of its important life cycle events: startup and shutdown.
 * <p>
 * In order to be able to receive leader notifications, application must 
 * implement {@link QuorumPeerObserver} and register an instance of the interface
 * with {@link ObserverManager}.
 */
public class ObservableFollower extends Follower {

    private enum Event{
        STARTUP() {
            public void dispatch(ObservableQuorumPeer peer,
                    QuorumPeerObserver ob,Follower follower) {
                ob.onFollowerStarted(peer,follower);
            }
        },
        SHUTDOWN() {
            public void dispatch(ObservableQuorumPeer peer,
                    QuorumPeerObserver ob,Follower follower) {
                ob.onFollowerShutdown(peer,follower);
            }
        };
        public abstract void dispatch(ObservableQuorumPeer peer,
                QuorumPeerObserver ob,Follower follower);
    }

    public ObservableFollower(QuorumPeer self, FollowerZooKeeperServer zk) {
        super(self, zk);
    }

    static private class PeerEvent implements EventInfo{
        private Event ev;
        private Follower info;
        PeerEvent(Event ev,Follower info){
            this.ev=ev;
            this.info=info;
        }
        public void dispatch(Object source, Object ob) {
            ev.dispatch((ObservableQuorumPeer)source,(QuorumPeerObserver)ob,info);
        }
    }
    void followLeader() throws InterruptedException {
        try{
            ObserverManager.getInstance().notifyObservers((ObservableQuorumPeer)self, 
                    new PeerEvent(Event.STARTUP,this));
            super.followLeader();
        }finally{
            ObserverManager.getInstance().notifyObservers((ObservableQuorumPeer)self, 
                    new PeerEvent(Event.SHUTDOWN,this));            
        }
    }

}
