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
 */
﻿namespace ZooKeeperNet
{
    using System;
    using System.Collections.Generic;
    using System.Linq;
    using log4net;
    using Org.Apache.Jute;
    using Org.Apache.Zookeeper.Data;

    public class DataTree
    {
        private static readonly ILog LOG = LogManager.GetLogger(typeof(DataTree));

        private readonly object locker = new object();

        private readonly Dictionary<string, DataNode> nodes = new Dictionary<string, DataNode>();

        private ZKWatchManager dataWatches = new ZKWatchManager();

        private ZKWatchManager childWatches = new ZKWatchManager();

        /** the root of zookeeper tree */
        private static string rootZookeeper = "/";

        /** the zookeeper nodes that acts as the management and status node **/
        private static string procZookeeper = Quotas.procZookeeper;

        /** this will be the string thats stored as a child of root */
        private static string procChildZookeeper = procZookeeper.Substring(1);

        /**
         * the zookeeper quota node that acts as the quota management node for
         * zookeeper
         */
        private static string quotaZookeeper = Quotas.quotaZookeeper;

        /** this will be the string thats stored as a child of /zookeeper */
        private static string quotaChildZookeeper = quotaZookeeper.Substring(procZookeeper.Length + 1);

        /**
         * the path trie that keeps track fo the quota nodes in this datatree
         */
        //private PathTrie pTrie = new PathTrie();

        /**
         * This hashtable lists the paths of the ephemeral nodes of a session.
         */
        private Dictionary<long, HashSet<string>> ephemerals = new Dictionary<long, HashSet<string>>();

        /**
         * this is map from longs to acl's. It saves acl's being stored for each
         * datanode.
         */
        //not concurrent
        public Dictionary<long, List<ACL>> longKeyMap = new Dictionary<long, List<ACL>>();

        /**
         * this a map from acls to long.
         */
        //not concurrent
        public Dictionary<List<ACL>, long> aclKeyMap = new Dictionary<List<ACL>, long>();

        /**
         * these are the number of acls that we have in the datatree
         */
        protected long aclIndex = 0;

        public Dictionary<long, HashSet<string>> Ephemerals
        {
            get { return ephemerals; }
        }

        public HashSet<string> GetEphemerals(long sessionId)
        {
            HashSet<string> retv;
            if (!ephemerals.TryGetValue(sessionId, out retv))
                return new HashSet<string>();

            lock (retv)
            {
                return new HashSet<string>(retv);
            }
        }

        private long IncrementIndex()
        {
            return ++aclIndex;
        }


        public long ConvertAcls(List<ACL> acls)
        {
            lock (locker)
            {
                if (acls == null)
                    return -1L;
                // get the value from the map
                long ret;
                if (!aclKeyMap.TryGetValue(acls, out ret))
                {
                    ret = IncrementIndex();
                    longKeyMap.Add(ret, acls);
                    aclKeyMap.Add(acls, ret);
                }
                return ret;
            }
        }

    public List<ACL> convertLong(long longVal) {
        lock (locker)
        {
            if (longVal == -1L)
                return Ids.OPEN_ACL_UNSAFE;
            List<ACL> acls;
            if (!longKeyMap.TryGetValue(longVal, out acls)) {
                LOG.Error("ERROR: ACL not available for long " + longVal);
                throw new InvalidOperationException("Failed to fetch acls for " + longVal);
            }
            return acls;
        }
    }

        public List<long> Sessions
        {
            get
            {
                return ephemerals.Keys.ToList();
            }
        }

        public void addDataNode(string path, DataNode node)
        {
            nodes.Add(path, node);
        }

        public DataNode getNode(string path)
        {
            DataNode node;
            nodes.TryGetValue(path, out node);
            return node;
        }

        public int getNodeCount()
        {
            return nodes.Count;
        }


        public static void CopyStat(Stat from, Stat to)
        {
            to.Aversion = from.Aversion;
            to.Ctime = from.Ctime;
            to.Cversion = from.Cversion;
            to.Czxid = from.Czxid;
            to.Mtime = from.Mtime;
            to.Mzxid = from.Mzxid;
            to.Pzxid = from.Pzxid;
            to.Version = from.Version;
            to.EphemeralOwner = from.EphemeralOwner;
            to.DataLength = from.DataLength;
            to.NumChildren = from.NumChildren;
        }
        


        public class DataNode : IRecord
        {
            private readonly object locker = new object();
            private readonly DataNode parent;
            private byte[] data;
            private long acl;
            private StatPersisted stat;
            private HashSet<string> children;

            public DataNode(DataNode parent, byte[] data, long acl, StatPersisted stat)
            {
                this.parent = parent;
                this.data = data;
                this.acl = acl;
                this.stat = stat;
            }

            public HashSet<string> Children
            {
                get
                {
                    lock (locker)
                    {
                        return children;
                    }
                }
                set
                {
                    lock (locker)
                    {
                        children = value;
                    }
                }
            }

            public bool AddChild(string child)
            {
                lock (locker)
                {
                    if (children == null)
                    {
                        children = new HashSet<string>();
                    }
                    return children.Add(child);
                }
            }

            public bool RemoveChild(string child)
            {
                lock (locker)
                {
                    if (children == null)
                        return false;
                    return children.Remove(child);
                }
            }

            public void CopyStat(Stat to)
            {
                lock (locker)
                {
                    to.Aversion = stat.Aversion;
                    to.Ctime = stat.Ctime;
                    to.Cversion = stat.Cversion;
                    to.Czxid = stat.Czxid;
                    to.Mtime = stat.Mtime;
                    to.Mzxid = stat.Mzxid;
                    to.Pzxid = stat.Pzxid;
                    to.Version = stat.Version;
                    to.EphemeralOwner = stat.EphemeralOwner;
                    to.DataLength = data == null ? 0 : data.Length;
                    if (this.children == null)
                    {
                        to.NumChildren = 0;
                    }
                    else
                    {
                        to.NumChildren = children.Count;
                    }
                }
            }

            public void Serialize(IOutputArchive archive, string tag)
            {
                lock (locker)
                {
                    archive.StartRecord(this, "node");
                    archive.WriteBuffer(data, "data");
                    archive.WriteLong(acl, "acl");
                    stat.Serialize(archive, "statpersisted");
                    archive.EndRecord(this, "node");
                }
            }

            public void Deserialize(IInputArchive archive, string tag)
            {
                lock (locker)
                {
                    archive.StartRecord("node");
                    data = archive.ReadBuffer("data");
                    acl = archive.ReadLong("acl");
                    stat = new StatPersisted();
                    stat.Deserialize(archive, "statpersisted");
                    archive.EndRecord("node");
                }
            }
        }
    }
}
