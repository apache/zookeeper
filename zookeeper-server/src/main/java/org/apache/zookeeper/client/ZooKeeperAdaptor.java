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

import java.io.IOException;
import java.util.List;
import org.apache.zookeeper.AddWatchMode;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.Transaction;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.ClientInfo;
import org.apache.zookeeper.data.Stat;

/**
 * Adaptor to bridge {@link org.apache.zookeeper.ZooKeeper} to implement {@link ZooKeeper} while not introducing
 * abi compatibility issue.
 */
class ZooKeeperAdaptor implements ZooKeeper {
    private final org.apache.zookeeper.ZooKeeper zk;

    ZooKeeperAdaptor(org.apache.zookeeper.ZooKeeper zk) {
        this.zk = zk;
    }

    @Override
    public long getSessionId() {
        return zk.getSessionId();
    }

    @Override
    public byte[] getSessionPasswd() {
        return zk.getSessionPasswd();
    }

    @Override
    public int getSessionTimeout() {
        return zk.getSessionTimeout();
    }

    @Override
    public ZKClientConfig getClientConfig() {
        return zk.getClientConfig();
    }

    @Override
    public void register(Watcher watcher) {
        zk.register(watcher);
    }

    @Override
    public void updateServerList(String connectString) throws IOException {
        zk.updateServerList(connectString);
    }

    @Override
    public Stat exists(String path, boolean watch) throws KeeperException, InterruptedException {
        return zk.exists(path, watch);
    }

    @Override
    public Stat exists(String path, Watcher watcher) throws KeeperException, InterruptedException {
        return zk.exists(path, watcher);
    }

    @Override
    public void exists(String path, boolean watch, AsyncCallback.StatCallback cb, Object ctx) {
        zk.exists(path, watch, cb, ctx);
    }

    @Override
    public void exists(String path, Watcher watcher, AsyncCallback.StatCallback cb, Object ctx) {
        zk.exists(path, watcher, cb, ctx);
    }

    @Override
    public String create(String path, byte[] data, List<ACL> acl, CreateMode createMode) throws KeeperException, InterruptedException {
        return zk.create(path, data, acl, createMode);
    }

    @Override
    public String create(String path, byte[] data, List<ACL> acl, CreateMode createMode, Stat stat) throws KeeperException, InterruptedException {
        return zk.create(path, data, acl, createMode, stat);
    }

    @Override
    public String create(String path, byte[] data, List<ACL> acl, CreateMode createMode, Stat stat, long ttl) throws KeeperException, InterruptedException {
        return zk.create(path, data, acl, createMode, stat, ttl);
    }

    @Override
    public void addAuthInfo(String scheme, byte[] auth) {
        zk.addAuthInfo(scheme, auth);
    }

    @Override
    public void create(String path, byte[] data, List<ACL> acl, CreateMode createMode, AsyncCallback.StringCallback cb, Object ctx) {
        zk.create(path, data, acl, createMode, cb, ctx);
    }

    @Override
    public void create(String path, byte[] data, List<ACL> acl, CreateMode createMode, AsyncCallback.Create2Callback cb, Object ctx) {
        zk.create(path, data, acl, createMode, cb, ctx);
    }

    @Override
    public void create(String path, byte[] data, List<ACL> acl, CreateMode createMode, AsyncCallback.Create2Callback cb, Object ctx, long ttl) {
        zk.create(path, data, acl, createMode, cb, ctx, ttl);
    }

    @Override
    public void delete(String path, int version) throws InterruptedException, KeeperException {
        zk.delete(path, version);
    }

    @Override
    public void delete(String path, int version, AsyncCallback.VoidCallback cb, Object ctx) {
        zk.delete(path, version, cb, ctx);
    }

    @Override
    public List<OpResult> multi(Iterable<Op> ops) throws InterruptedException, KeeperException {
        return zk.multi(ops);
    }

    @Override
    public void multi(Iterable<Op> ops, AsyncCallback.MultiCallback cb, Object ctx) {
        zk.multi(ops, cb, ctx);
    }

    @Override
    public byte[] getData(String path, boolean watch, Stat stat) throws KeeperException, InterruptedException {
        return zk.getData(path, watch, stat);
    }

    @Override
    public byte[] getData(String path, Watcher watcher, Stat stat) throws KeeperException, InterruptedException {
        return zk.getData(path, watcher, stat);
    }

    @Override
    public void getData(String path, boolean watch, AsyncCallback.DataCallback cb, Object ctx) {
        zk.getData(path, watch, cb, ctx);
    }

    @Override
    public void getData(String path, Watcher watcher, AsyncCallback.DataCallback cb, Object ctx) {
        zk.getData(path, watcher, cb, ctx);
    }

    @Override
    public byte[] getConfig(boolean watch, Stat stat) throws KeeperException, InterruptedException {
        return zk.getConfig(watch, stat);
    }

    @Override
    public byte[] getConfig(Watcher watcher, Stat stat) throws KeeperException, InterruptedException {
        return zk.getConfig(watcher, stat);
    }

    @Override
    public void getConfig(boolean watch, AsyncCallback.DataCallback cb, Object ctx) {
        zk.getConfig(watch, cb, ctx);
    }

    @Override
    public void getConfig(Watcher watcher, AsyncCallback.DataCallback cb, Object ctx) {
        zk.getConfig(watcher, cb, ctx);
    }

    @Override
    public Stat setData(String path, byte[] data, int version) throws KeeperException, InterruptedException {
        return zk.setData(path, data, version);
    }

    @Override
    public void setData(String path, byte[] data, int version, AsyncCallback.StatCallback cb, Object ctx) {
        zk.setData(path, data, version, cb, ctx);
    }

    @Override
    public List<ACL> getACL(String path, Stat stat) throws KeeperException, InterruptedException {
        return zk.getACL(path, stat);
    }

    @Override
    public void getACL(String path, Stat stat, AsyncCallback.ACLCallback cb, Object ctx) {
        zk.getACL(path, stat, cb, ctx);
    }

    @Override
    public Stat setACL(String path, List<ACL> acl, int aclVersion) throws KeeperException, InterruptedException {
        return zk.setACL(path, acl, aclVersion);
    }

    @Override
    public void setACL(String path, List<ACL> acl, int version, AsyncCallback.StatCallback cb, Object ctx) {
        zk.setACL(path, acl, version, cb, ctx);
    }

    @Override
    public List<String> getChildren(String path, boolean watch) throws KeeperException, InterruptedException {
        return zk.getChildren(path, watch);
    }

    @Override
    public List<String> getChildren(String path, Watcher watcher) throws KeeperException, InterruptedException {
        return zk.getChildren(path, watcher);
    }

    @Override
    public List<String> getChildren(String path, boolean watch, Stat stat) throws KeeperException, InterruptedException {
        return zk.getChildren(path, watch, stat);
    }

    @Override
    public List<String> getChildren(String path, Watcher watcher, Stat stat) throws KeeperException, InterruptedException {
        return zk.getChildren(path, watcher, stat);
    }

    @Override
    public void getChildren(String path, boolean watch, AsyncCallback.ChildrenCallback cb, Object ctx) {
        zk.getChildren(path, watch, cb, ctx);
    }

    @Override
    public void getChildren(String path, boolean watch, AsyncCallback.Children2Callback cb, Object ctx) {
        zk.getChildren(path, watch, cb, ctx);
    }

    @Override
    public void getChildren(String path, Watcher watcher, AsyncCallback.ChildrenCallback cb, Object ctx) {
        zk.getChildren(path, watcher, cb, ctx);
    }

    @Override
    public void getChildren(String path, Watcher watcher, AsyncCallback.Children2Callback cb, Object ctx) {
        zk.getChildren(path, watcher, cb, ctx);
    }

    @Override
    public int getAllChildrenNumber(String path) throws KeeperException, InterruptedException {
        return zk.getAllChildrenNumber(path);
    }

    @Override
    public void getAllChildrenNumber(String path, AsyncCallback.AllChildrenNumberCallback cb, Object ctx) {
        zk.getAllChildrenNumber(path, cb, ctx);
    }

    @Override
    public List<String> getEphemerals() throws KeeperException, InterruptedException {
        return zk.getEphemerals();
    }

    @Override
    public List<String> getEphemerals(String prefixPath) throws KeeperException, InterruptedException {
        return zk.getEphemerals(prefixPath);
    }

    @Override
    public void getEphemerals(AsyncCallback.EphemeralsCallback cb, Object ctx) {
        zk.getEphemerals(cb, ctx);
    }

    @Override
    public void getEphemerals(String prefixPath, AsyncCallback.EphemeralsCallback cb, Object ctx) {
        zk.getEphemerals(prefixPath, cb, ctx);
    }

    @Override
    public void sync(String path) throws KeeperException, InterruptedException {
        zk.sync(path);
    }

    @Override
    public void sync(String path, AsyncCallback.VoidCallback cb, Object ctx) {
        zk.sync(path, cb, ctx);
    }

    @Override
    public void removeWatches(String path, Watcher watcher, Watcher.WatcherType watcherType, boolean local) throws InterruptedException, KeeperException {
        zk.removeWatches(path, watcher, watcherType, local);
    }

    @Override
    public void removeWatches(String path, Watcher watcher, Watcher.WatcherType watcherType, boolean local, AsyncCallback.VoidCallback cb, Object ctx) {
        zk.removeWatches(path, watcher, watcherType, local, cb, ctx);
    }

    @Override
    public void removeAllWatches(String path, Watcher.WatcherType watcherType, boolean local) throws InterruptedException, KeeperException {
        zk.removeAllWatches(path, watcherType, local);
    }

    @Override
    public void removeAllWatches(String path, Watcher.WatcherType watcherType, boolean local, AsyncCallback.VoidCallback cb, Object ctx) {
        zk.removeAllWatches(path, watcherType, local, cb, ctx);
    }

    @Override
    public void addWatch(String basePath, Watcher watcher, AddWatchMode mode) throws KeeperException, InterruptedException {
        zk.addWatch(basePath, watcher, mode);
    }

    @Override
    public void addWatch(String basePath, AddWatchMode mode) throws KeeperException, InterruptedException {
        zk.addWatch(basePath, mode);
    }

    @Override
    public void addWatch(String basePath, Watcher watcher, AddWatchMode mode, AsyncCallback.VoidCallback cb, Object ctx) {
        zk.addWatch(basePath, watcher, mode, cb, ctx);
    }

    @Override
    public void addWatch(String basePath, AddWatchMode mode, AsyncCallback.VoidCallback cb, Object ctx) {
        zk.addWatch(basePath, mode, cb, ctx);
    }

    @Override
    public Transaction transaction() {
        return zk.transaction();
    }

    @Override
    public List<ClientInfo> whoAmI() throws InterruptedException {
        return zk.whoAmI();
    }

    @Override
    public void close() throws InterruptedException {
        zk.close();
    }

    @Override
    public boolean close(int waitForShutdownTimeoutMs) throws InterruptedException {
        return zk.close(waitForShutdownTimeoutMs);
    }

    @Override
    public String toString() {
        return zk.toString();
    }
}
