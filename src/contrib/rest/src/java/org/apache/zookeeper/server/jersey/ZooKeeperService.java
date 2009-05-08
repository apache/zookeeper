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

package org.apache.zookeeper.server.jersey;

import java.io.IOException;
import java.util.HashMap;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;

/**
 * Singleton which provides JAX-RS resources access to the ZooKeeper
 * client. There's a single session for each base uri (so usually just one).
 */
public class ZooKeeperService {
    /** Map base uri to ZooKeeper host:port parameters */
    private static HashMap<String, String> uriMap
        = new HashMap<String, String>();

    /** Map base uri to ZooKeeper session */
    private static HashMap<String, ZooKeeper> zkMap =
        new HashMap<String, ZooKeeper>();

    /** Track the status of the ZooKeeper session */
    private static class MyWatcher implements Watcher {
        volatile boolean connected;
        final String uriBase;

        /** Separate watcher for each base uri */
        public MyWatcher(String uriBase) {
            this.uriBase = uriBase;
        }

        /** Track state - in particular watch for expiration. if
         * it happens for re-creation of the ZK client session
         */
        synchronized public void process(WatchedEvent event) {
            if (event.getState() == KeeperState.SyncConnected) {
                connected = true;
            } else if (event.getState() == KeeperState.Expired) {
                connected = false;
                close(uriBase);
            } else {
                connected = false;
            }
        }
    }

    /**
     * Specify ZooKeeper host:port for a particular base uri. The host:port
     * string is passed to the ZK client, so this can be formatted with
     * more than a single host:port pair.
     */
    synchronized public static void mapUriBase(String uriBase, String hostport)
    {
        uriMap.put(uriBase, hostport);
    }

    /**
     * Close the ZooKeeper session and remove it from the internal maps
     */
    synchronized public static void close(String uriBase) {
        ZooKeeper zk = zkMap.remove(uriBase);
        if (zk == null) {
            return;
        }
        try {
            zk.close();
        } catch (InterruptedException e) {
            // FIXME
            e.printStackTrace();
        }
    }

    /**
     * Return a ZooKeeper client which may or may not be connected, but
     * it will not be expired. This method can be called multiple times,
     * the same object will be returned except in the case where the
     * session expires (at which point a new session will be returned)
     */
    synchronized public static ZooKeeper getClient(String baseUri)
        throws IOException
    {
        ZooKeeper zk = zkMap.get(baseUri);
        if (zk == null) {
            String hostPort = uriMap.get(baseUri);
            zk = new ZooKeeper(hostPort, 30000, new MyWatcher(baseUri));
            zkMap.put(baseUri, zk);
        }
        return zk;
    }
}
