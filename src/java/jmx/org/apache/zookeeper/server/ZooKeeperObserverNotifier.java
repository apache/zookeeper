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

import org.apache.zookeeper.server.util.ObservableComponent;
import org.apache.zookeeper.server.util.ObserverManager;
import org.apache.zookeeper.server.util.ServerObserver;

/**
 * The class is responsible for triggering and dispatching of server life cycle events.
 * <p>
 * In order to make itself observable, the server class creates an instance of 
 * ZooKeeperObserverNotifier and then, calls the notifyStarted or notifyShutdown 
 * methods to notify all registered observers of a startup or shutdown event 
 * correspondingly.
 * 
 * Application wishing to be notified of a server state change should implement
 * {@link ServerObserver} interface and register an instance of the interface
 * with {@link ObserverManager}.
 */
public class ZooKeeperObserverNotifier implements ObservableComponent {
    private enum Event {
        STARTUP() {
            public void dispatch(ZooKeeperServer s, ServerObserver o) {
                o.onStartup(s);
            }
        },
        SHUTDOWN() {
            public void dispatch(ZooKeeperServer s, ServerObserver o) {
                o.onShutdown(s);
            }
        };
        public abstract void dispatch(ZooKeeperServer s, ServerObserver o);
    }

    private ZooKeeperServer server;
    
    public ZooKeeperObserverNotifier(ZooKeeperServer server) {
        this.server=server;
    }

    /**
     * Generate a startup event.
     */
    public void notifyStarted(){
        ObserverManager.getInstance().notifyObservers(this,Event.STARTUP);        
    }
    /**
     * Generate a shutdown event.
     */
    public void notifyShutdown(){
        ObserverManager.getInstance().notifyObservers(this,Event.SHUTDOWN);        
    }
    
    public void dispatchEvent(Object observer, Object args) {
        Event ev=(Event)args;
        ev.dispatch(server,(ServerObserver)observer);
    }

}
