/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All rights reserved.
 * 
 */
using System.Collections.Generic;
using org.apache.zookeeper;
using org.apache.zookeeper.data;

namespace org.apache.utils {
    internal static class ZooKeeperSyncEx {
        public static string create(this ZooKeeper zk, string path, byte[] data, List<ACL> acl, CreateMode createMode) {
            return zk.createAsync(path, data, acl, createMode).GetAwaiter().GetResult();
        }

        public static void delete(this ZooKeeper zk, string path, int version) {
            zk.deleteAsync(path, version).GetAwaiter().GetResult();
        }

        public static List<OpResult> multi(this ZooKeeper zk, List<Op> ops) {
            return zk.multiAsync(ops).GetAwaiter().GetResult();
        }

        public static Stat exists(this ZooKeeper zk, string path, Watcher watcher) {
            return zk.existsAsync(path, watcher).GetAwaiter().GetResult();
        }

        public static Stat exists(this ZooKeeper zk, string path, bool watch) {
            return zk.existsAsync(path, watch).GetAwaiter().GetResult();
        }

        public static byte[] getData(this ZooKeeper zk, string path, Watcher watcher, Stat stat) {
            return copyStat(zk.getDataAsync(path, watcher).GetAwaiter().GetResult(), stat).Data;
        }

        public static byte[] getData(this ZooKeeper zk, string path, bool watch, Stat stat) {
            return copyStat(zk.getDataAsync(path, watch).GetAwaiter().GetResult(), stat).Data;
        }

        public static Stat setData(this ZooKeeper zk, string path, byte[] data, int version) {
            return zk.setDataAsync(path, data, version).GetAwaiter().GetResult();
        }

        public static List<ACL> getACL(this ZooKeeper zk, string path, Stat stat) {
            return copyStat(zk.getACLAsync(path).GetAwaiter().GetResult(), stat).Acls;
        }

        public static Stat setACL(this ZooKeeper zk, string path, List<ACL> acl, int version) {
            return zk.setACLAsync(path, acl, version).GetAwaiter().GetResult();
        }

        public static List<string> getChildren(this ZooKeeper zk, string path, bool watch, Stat stat = null) {
            return copyStat(zk.getChildrenAsync(path, watch).GetAwaiter().GetResult(), stat).Children;
        }

        public static List<string> getChildren(this ZooKeeper zk, string path, Watcher watcher, Stat stat = null) {
            return copyStat(zk.getChildrenAsync(path, watcher).GetAwaiter().GetResult(), stat).Children;
        }

        public static void close(this ZooKeeper zk) {
            zk.closeAsync().GetAwaiter().GetResult();
        }

        public static List<OpResult> commit(this Transaction t)
		{
			return t.commitAsync().GetAwaiter().GetResult();
		}

        private static T copyStat<T>(T fromNode, Stat to) where T : NodeResult {
            if (to != null) {
                var from = fromNode.Stat;
                to.setAversion(from.getAversion());
                to.setCtime(from.getCtime());
                to.setCversion(from.getCversion());
                to.setCzxid(from.getCzxid());
                to.setMtime(from.getMtime());
                to.setMzxid(from.getMzxid());
                to.setPzxid(from.getPzxid());
                to.setVersion(from.getVersion());
                to.setEphemeralOwner(from.getEphemeralOwner());
                to.setDataLength(from.getDataLength());
                to.setNumChildren(from.getNumChildren());
            }
            return fromNode;
        }
    }
}
