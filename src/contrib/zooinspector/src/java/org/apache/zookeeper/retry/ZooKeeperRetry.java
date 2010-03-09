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
package org.apache.zookeeper.retry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.inspector.logger.LoggerFactory;

/**
 * A Class which extends {@link ZooKeeper} and will automatically retry calls to
 * zookeeper if a {@link KeeperException.ConnectionLossException} occurs
 */
public class ZooKeeperRetry extends ZooKeeper {

    private boolean closed = false;
    private final Watcher watcher;
    private int limit = -1;

    /**
     * @param connectString
     * @param sessionTimeout
     * @param watcher
     * @throws IOException
     */
    public ZooKeeperRetry(String connectString, int sessionTimeout,
            Watcher watcher) throws IOException {
        super(connectString, sessionTimeout, watcher);
        this.watcher = watcher;
    }

    /**
     * @param connectString
     * @param sessionTimeout
     * @param watcher
     * @param sessionId
     * @param sessionPasswd
     * @throws IOException
     */
    public ZooKeeperRetry(String connectString, int sessionTimeout,
            Watcher watcher, long sessionId, byte[] sessionPasswd)
            throws IOException {
        super(connectString, sessionTimeout, watcher, sessionId, sessionPasswd);
        this.watcher = watcher;
    }

    @Override
    public synchronized void close() throws InterruptedException {
        this.closed = true;
        super.close();
    }

    @Override
    public String create(String path, byte[] data, List<ACL> acl,
            CreateMode createMode) throws KeeperException, InterruptedException {
        int count = 0;
        do {
            try {
                return super.create(path, data, acl, createMode);
            } catch (KeeperException.ConnectionLossException e) {
                LoggerFactory.getLogger().warn(
                        "ZooKeeper connection lost.  Trying to reconnect.");
                if (exists(path, false) != null) {
                    return path;
                }
            } catch (KeeperException.NodeExistsException e) {
                return path;
            }
        } while (!closed && (limit == -1 || count++ < limit));
        return null;
    }

    @Override
    public void delete(String path, int version) throws InterruptedException,
            KeeperException {
        int count = 0;
        do {
            try {
                super.delete(path, version);
            } catch (KeeperException.ConnectionLossException e) {
                LoggerFactory.getLogger().warn(
                        "ZooKeeper connection lost.  Trying to reconnect.");
                if (exists(path, false) == null) {
                    return;
                }
            } catch (KeeperException.NoNodeException e) {
                break;
            }
        } while (!closed && (limit == -1 || count++ < limit));
    }

    @Override
    public Stat exists(String path, boolean watch) throws KeeperException,
            InterruptedException {
        int count = 0;
        do {
            try {
                return super.exists(path, watch ? watcher : null);
            } catch (KeeperException.ConnectionLossException e) {
                LoggerFactory.getLogger().warn(
                        "ZooKeeper connection lost.  Trying to reconnect.");
            }
        } while (!closed && (limit == -1 || count++ < limit));
        return null;
    }

    @Override
    public Stat exists(String path, Watcher watcher) throws KeeperException,
            InterruptedException {
        int count = 0;
        do {
            try {
                return super.exists(path, watcher);
            } catch (KeeperException.ConnectionLossException e) {
                LoggerFactory.getLogger().warn(
                        "ZooKeeper connection lost.  Trying to reconnect.");
            }
        } while (!closed && (limit == -1 || count++ < limit));
        return null;
    }

    @Override
    public List<ACL> getACL(String path, Stat stat) throws KeeperException,
            InterruptedException {
        int count = 0;
        do {
            try {
                return super.getACL(path, stat);
            } catch (KeeperException.ConnectionLossException e) {
                LoggerFactory.getLogger().warn(
                        "ZooKeeper connection lost.  Trying to reconnect.");
            }
        } while (!closed && (limit == -1 || count++ < limit));
        return null;
    }

    @Override
    public List<String> getChildren(String path, boolean watch)
            throws KeeperException, InterruptedException {
        int count = 0;
        do {
            try {
                return super.getChildren(path, watch ? watcher : null);
            } catch (KeeperException.ConnectionLossException e) {
                LoggerFactory.getLogger().warn(
                        "ZooKeeper connection lost.  Trying to reconnect.");
            }
        } while (!closed && (limit == -1 || count++ < limit));
        return new ArrayList<String>();
    }

    @Override
    public List<String> getChildren(String path, Watcher watcher)
            throws KeeperException, InterruptedException {
        int count = 0;
        do {
            try {
                return super.getChildren(path, watcher);
            } catch (KeeperException.ConnectionLossException e) {
                LoggerFactory.getLogger().warn(
                        "ZooKeeper connection lost.  Trying to reconnect.");
            }
        } while (!closed && (limit == -1 || count++ < limit));
        return new ArrayList<String>();
    }

    @Override
    public byte[] getData(String path, boolean watch, Stat stat)
            throws KeeperException, InterruptedException {
        int count = 0;
        do {
            try {
                return super.getData(path, watch ? watcher : null, stat);
            } catch (KeeperException.ConnectionLossException e) {
                LoggerFactory.getLogger().warn(
                        "ZooKeeper connection lost.  Trying to reconnect.");
            }
        } while (!closed && (limit == -1 || count++ < limit));
        return null;
    }

    @Override
    public byte[] getData(String path, Watcher watcher, Stat stat)
            throws KeeperException, InterruptedException {
        int count = 0;
        do {
            try {
                return super.getData(path, watcher, stat);
            } catch (KeeperException.ConnectionLossException e) {
                LoggerFactory.getLogger().warn(
                        "ZooKeeper connection lost.  Trying to reconnect.");
            }
        } while (!closed && (limit == -1 || count++ < limit));
        return null;
    }

    @Override
    public Stat setACL(String path, List<ACL> acl, int version)
            throws KeeperException, InterruptedException {
        int count = 0;
        do {
            try {
                return super.setACL(path, acl, version);
            } catch (KeeperException.ConnectionLossException e) {
                LoggerFactory.getLogger().warn(
                        "ZooKeeper connection lost.  Trying to reconnect.");
                Stat s = exists(path, false);
                if (s != null) {
                    if (getACL(path, s).equals(acl)) {
                        return s;
                    }
                } else {
                    return null;
                }
            }
        } while (!closed && (limit == -1 || count++ < limit));
        return null;
    }

    @Override
    public Stat setData(String path, byte[] data, int version)
            throws KeeperException, InterruptedException {
        int count = 0;
        do {
            try {
                return super.setData(path, data, version);
            } catch (KeeperException.ConnectionLossException e) {
                LoggerFactory.getLogger().warn(
                        "ZooKeeper connection lost.  Trying to reconnect.");
                Stat s = exists(path, false);
                if (s != null) {
                    if (getData(path, false, s) == data) {
                        return s;
                    }
                } else {
                    return null;
                }
            }
        } while (!closed && (limit == -1 || count++ < limit));
        return null;
    }

    /**
     * @param limit
     */
    public void setRetryLimit(int limit) {
        this.limit = limit;
    }

    /**
     * @return true if successfully connected to zookeeper
     */
    public boolean testConnection() {
        int count = 0;
        do {
            try {
                return super.exists("/", null) != null;
            } catch (Exception e) {
                LoggerFactory.getLogger().warn(
                        "ZooKeeper connection lost.  Trying to reconnect.");
            }
        } while (count++ < 5);
        return false;
    }

}
