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
    using NUnit.Framework;
    using Org.Apache.Zookeeper.Data;

    [TestFixture]
    public class StatTests : AbstractZooKeeperTests
    {
        private ZooKeeper zk;

        [SetUp]
        public void SetUp()
        {
            zk = CreateClient();
        }

        [TearDown]
        public void TearDown()
        {
            zk.Dispose();
        }

        /**
         * Create a new Stat, fill in dummy values trying to catch Assert.failure
         * to copy in client or server code.
         *
         * @return a new stat with dummy values
         */
        private Stat newStat()
        {
            Stat stat = new Stat();

            stat.Aversion = 100;
            stat.Ctime = 100;
            stat.Cversion = 100;
            stat.Czxid = 100;
            stat.DataLength = 100;
            stat.EphemeralOwner = 100;
            stat.Mtime = 100;
            stat.Mzxid = 100;
            stat.NumChildren = 100;
            stat.Pzxid = 100;
            stat.Version = 100;

            return stat;
        }

        [Test]
        public void testBasic()
        {
            string name = "/" + Guid.NewGuid() + "foo";
            zk.Create(name, name.GetBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.Persistent);

            Stat stat;
            stat = newStat();
            zk.GetData(name, false, stat);

            Assert.AreEqual(stat.Czxid, stat.Mzxid);
            Assert.AreEqual(stat.Czxid, stat.Pzxid);
            Assert.AreEqual(stat.Ctime, stat.Mtime);
            Assert.AreEqual(0, stat.Cversion);
            Assert.AreEqual(0, stat.Version);
            Assert.AreEqual(0, stat.Aversion);
            Assert.AreEqual(0, stat.EphemeralOwner);
            Assert.AreEqual(name.Length, stat.DataLength);
            Assert.AreEqual(0, stat.NumChildren);
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

            Stat stat;

            stat = newStat();
            zk.GetData(name, false, stat);

            Assert.AreEqual(stat.Czxid, stat.Mzxid);
            Assert.AreEqual(stat.Czxid + 1, stat.Pzxid);
            Assert.AreEqual(stat.Ctime, stat.Mtime);
            Assert.AreEqual(1, stat.Cversion);
            Assert.AreEqual(0, stat.Version);
            Assert.AreEqual(0, stat.Aversion);
            Assert.AreEqual(0, stat.EphemeralOwner);
            Assert.AreEqual(name.Length, stat.DataLength);
            Assert.AreEqual(1, stat.NumChildren);

            stat = newStat();
            zk.GetData(childname, false, stat);

            Assert.AreEqual(stat.Czxid, stat.Mzxid);
            Assert.AreEqual(stat.Czxid, stat.Pzxid);
            Assert.AreEqual(stat.Ctime, stat.Mtime);
            Assert.AreEqual(0, stat.Cversion);
            Assert.AreEqual(0, stat.Version);
            Assert.AreEqual(0, stat.Aversion);
            Assert.AreEqual(zk.SessionId, stat.EphemeralOwner);
            Assert.AreEqual(childname.Length, stat.DataLength);
            Assert.AreEqual(0, stat.NumChildren);
        }

        [Test]
        public void testChildren()
        {
            string name = "/" + Guid.NewGuid() + "foo";
            zk.Create(name, name.GetBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.Persistent);

            for (int i = 0; i < 10; i++)
            {
                string childname = name + "/bar" + i;
                zk.Create(childname, childname.GetBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EphemeralSequential);

                Stat stat;

                stat = newStat();
                zk.GetData(name, false, stat);

                Assert.AreEqual(stat.Czxid, stat.Mzxid);
                Assert.AreEqual(stat.Czxid + i + 1, stat.Pzxid);
                Assert.AreEqual(stat.Ctime, stat.Mtime);
                Assert.AreEqual(i + 1, stat.Cversion);
                Assert.AreEqual(0, stat.Version);
                Assert.AreEqual(0, stat.Aversion);
                Assert.AreEqual(0, stat.EphemeralOwner);
                Assert.AreEqual(name.Length, stat.DataLength);
                Assert.AreEqual(i + 1, stat.NumChildren);
            }
        }

        [Test]
        public void testDataSizeChange()
        {
            string name = "/" + Guid.NewGuid() + "foo";
            zk.Create(name, name.GetBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);

            Stat stat;

            stat = newStat();
            zk.GetData(name, false, stat);

            Assert.AreEqual(stat.Czxid, stat.Mzxid);
            Assert.AreEqual(stat.Czxid, stat.Pzxid);
            Assert.AreEqual(stat.Ctime, stat.Mtime);
            Assert.AreEqual(0, stat.Cversion);
            Assert.AreEqual(0, stat.Version);
            Assert.AreEqual(0, stat.Aversion);
            Assert.AreEqual(0, stat.EphemeralOwner);
            Assert.AreEqual(name.Length, stat.DataLength);
            Assert.AreEqual(0, stat.NumChildren);

            zk.SetData(name, (name + name).GetBytes(), -1);

            stat = newStat();
            zk.GetData(name, false, stat);

            Assert.AreNotSame(stat.Czxid, stat.Mzxid);
            Assert.AreEqual(stat.Czxid, stat.Pzxid);
            Assert.AreNotSame(stat.Ctime, stat.Mtime);
            Assert.AreEqual(0, stat.Cversion);
            Assert.AreEqual(1, stat.Version);
            Assert.AreEqual(0, stat.Aversion);
            Assert.AreEqual(0, stat.EphemeralOwner);
            Assert.AreEqual(name.Length * 2, stat.DataLength);
            Assert.AreEqual(0, stat.NumChildren);
        }
    }
}
