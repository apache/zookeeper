/*
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

package org.apache.zookeeper.client;

import java.util.List;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

/**
 * Adaptor to bridge {@link org.apache.zookeeper.admin.ZooKeeperAdmin} to implement {@link ZooKeeperAdmin}
 * while not introducing abi compatibility issue.
 */
class ZooKeeperAdminAdaptor extends ZooKeeperAdaptor implements ZooKeeperAdmin {
    private final org.apache.zookeeper.admin.ZooKeeperAdmin admin;

    ZooKeeperAdminAdaptor(org.apache.zookeeper.admin.ZooKeeperAdmin zk) {
        super(zk);
        this.admin = zk;
    }

    @Override
    public byte[] reconfigure(
            String joiningServers,
            String leavingServers,
            String newMembers,
            long fromConfig,
            Stat stat) throws KeeperException, InterruptedException {
        return admin.reconfigure(joiningServers, leavingServers, newMembers, fromConfig, stat);
    }

    @Override
    public byte[] reconfigure(
            List<String> joiningServers,
            List<String> leavingServers,
            List<String> newMembers,
            long fromConfig,
            Stat stat) throws KeeperException, InterruptedException {
        return admin.reconfigure(joiningServers, leavingServers, newMembers, fromConfig, stat);
    }

    @Override
    public void reconfigure(
            String joiningServers,
            String leavingServers,
            String newMembers,
            long fromConfig,
            AsyncCallback.DataCallback cb,
            Object ctx) {
        admin.reconfigure(joiningServers, leavingServers, newMembers, fromConfig, cb, ctx);
    }

    @Override
    public void reconfigure(
            List<String> joiningServers,
            List<String> leavingServers,
            List<String> newMembers,
            long fromConfig,
            AsyncCallback.DataCallback cb,
            Object ctx) {
        admin.reconfigure(joiningServers, leavingServers, newMembers, fromConfig, cb, ctx);
    }
}
