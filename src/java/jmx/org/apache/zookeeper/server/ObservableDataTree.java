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

import java.util.ArrayList;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.util.DataTreeObserver;
import org.apache.zookeeper.server.util.EventInfo;
import org.apache.zookeeper.server.util.ObservableComponent;
import org.apache.zookeeper.server.util.ObserverManager;

/**
 * An observable data tree notifies its observers about any data modification
 * operation, including znode creation, znode update operation, znode deletion 
 * and znode's ACL update.
 * 
 * In order to be able to receive connection notification, applications
 * are required to implement {@link ObservableDataTree} interface and register
 * its instance with {@link ObserverManager}.
 */
public class ObservableDataTree extends DataTree implements ObservableComponent {
    private enum Event {
        ADD() {
            public void dispatch(DataTree dt,DataNode node,DataTreeObserver o) {
                o.onAdd(dt,node);
            }
        },
        DELETE() {
            public void dispatch(DataTree dt,DataNode node, DataTreeObserver o) {
                o.onDelete(dt,node);
            }
        },
        UPDATE() {
            public void dispatch(DataTree dt,DataNode node, DataTreeObserver o) {
                o.onUpdate(dt,node);
            }
        },
        UPDATE_ACL() {
            public void dispatch(DataTree dt,DataNode node, DataTreeObserver o) {
                o.onUpdateACL(dt,node);
            }
        };
        public abstract void dispatch(DataTree dt,DataNode node, DataTreeObserver o);
    }

    private class TreeEventInfo implements EventInfo{
        private Event ev;
        private DataNode node;
        TreeEventInfo(Event ev, DataNode node){
            this.ev=ev;
            this.node=node;
        }
        public void dispatch(Object source, Object observer){
            ev.dispatch((ObservableDataTree)source, node, (DataTreeObserver)observer);
        }
    }

    public void dispatchEvent(Object observer, Object args) {
        ((EventInfo)args).dispatch(this,observer);
    }


    public String createNode(String path, byte[] data, ArrayList<ACL> acl,
            long ephemeralOwner, long zxid, long time) throws KeeperException {
        String result=super.createNode(path, data, acl, ephemeralOwner, zxid, time);
        ObserverManager.getInstance().notifyObservers(this,
                new TreeEventInfo(Event.ADD,getNode(result)));
        return result;
    }

    public void deleteNode(String path) throws KeeperException.NoNodeException {
        DataNode deleted=getNode(path);
        super.deleteNode(path);
        ObserverManager.getInstance().notifyObservers(this,
                new TreeEventInfo(Event.DELETE,deleted));
    }

    public Stat setACL(String path, ArrayList<ACL> acl, int version)
            throws KeeperException {
        Stat stat=super.setACL(path, acl, version);
        ObserverManager.getInstance().notifyObservers(this,
                new TreeEventInfo(Event.UPDATE_ACL,getNode(path)));
        return stat;
    }


    public Stat setData(String path, byte[] data, int version, long zxid,
            long time) throws KeeperException.NoNodeException {
        Stat stat=super.setData(path, data, version, zxid, time);
        ObserverManager.getInstance().notifyObservers(this,
                new TreeEventInfo(Event.UPDATE,getNode(path)));
        return stat;
    }    
}
