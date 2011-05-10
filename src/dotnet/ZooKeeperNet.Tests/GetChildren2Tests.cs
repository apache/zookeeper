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
namespace ZooKeeperNet.Tests
{
    using System;
    using System.Collections.Generic;
    using System.Linq;
    using NUnit.Framework;
    using Org.Apache.Zookeeper.Data;

    [TestFixture]
    public class GetChildren2Tests : AbstractZooKeeperTests
    {
        private ZooKeeper zk;

        [SetUp]
        public void Setup()
        {
            zk = CreateClient();
        }

        public void Teardown()
        {
            zk.Dispose();
        }

        [Test]
        public void testChild()
        {
            string name = "/" + Guid.NewGuid() + "foo";
            zk.Create(name, name.GetBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.Persistent);

            string childname = name + "/bar";
            zk.Create(childname, childname.GetBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.Ephemeral);

            Stat stat = new Stat();
            List<string> s = zk.GetChildren(name, false, stat);

            Assert.AreEqual(stat.Czxid, stat.Mzxid);
            Assert.AreEqual(stat.Czxid + 1, stat.Pzxid);
            Assert.AreEqual(stat.Ctime, stat.Mtime);
            Assert.AreEqual(1, stat.Cversion);
            Assert.AreEqual(0, stat.Version);
            Assert.AreEqual(0, stat.Aversion);
            Assert.AreEqual(0, stat.EphemeralOwner);
            Assert.AreEqual(name.Length, stat.DataLength);
            Assert.AreEqual(1, stat.NumChildren);
            Assert.AreEqual(s.Count, stat.NumChildren);

            s = zk.GetChildren(childname, false, stat);

            Assert.AreEqual(stat.Czxid, stat.Mzxid);
            Assert.AreEqual(stat.Czxid, stat.Pzxid);
            Assert.AreEqual(stat.Ctime, stat.Mtime);
            Assert.AreEqual(0, stat.Cversion);
            Assert.AreEqual(0, stat.Version);
            Assert.AreEqual(0, stat.Aversion);
            Assert.AreEqual(zk.SessionId, stat.EphemeralOwner);
            Assert.AreEqual(childname.Length, stat.DataLength);
            Assert.AreEqual(0, stat.NumChildren);
            Assert.AreEqual(s.Count, stat.NumChildren);
        }

        [Test]
        public void testChildren()
        {
            string name = "/" + Guid.NewGuid() + "foo";
            zk.Create(name, name.GetBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.Persistent);

            List<string> children = new List<string>();
            List<string> children_s = new List<string>();

            for (int i = 0; i < 10; i++)
            {
                string childname = name + "/bar" + i;
                string childname_s = "bar" + i;
                children.Add(childname);
                children_s.Add(childname_s);
            }

            for (int i = 0; i < children.Count; i++)
            {
                string childname = children[i];
                zk.Create(childname, childname.GetBytes(), Ids.OPEN_ACL_UNSAFE,
                        CreateMode.Ephemeral);

                Stat stat = new Stat();
                List<string> s = zk.GetChildren(name, false, stat);

                Assert.AreEqual(stat.Czxid, stat.Mzxid);
                Assert.AreEqual(stat.Czxid + i + 1, stat.Pzxid);
                Assert.AreEqual(stat.Ctime, stat.Mtime);
                Assert.AreEqual(i + 1, stat.Cversion);
                Assert.AreEqual(0, stat.Version);
                Assert.AreEqual(0, stat.Aversion);
                Assert.AreEqual(0, stat.EphemeralOwner);
                Assert.AreEqual(name.Length, stat.DataLength);
                Assert.AreEqual(i + 1, stat.NumChildren);
                Assert.AreEqual(s.Count, stat.NumChildren);
            }
            List<string> p = zk.GetChildren(name, false, null);
            List<string> c_a = children_s;
            List<string> c_b = p;
            c_a = c_a.OrderBy(e => e).ToList();
            c_b = c_b.OrderBy(e => e).ToList();

            Assert.AreEqual(c_a.Count, 10);
            Assert.AreEqual(c_a, c_b);
        }
    }
}
