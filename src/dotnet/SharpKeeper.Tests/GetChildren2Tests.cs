using System;
using System.Collections.Generic;
using System.Linq;

namespace SharpKeeper.Tests
{
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
        String name = "/" + Guid.NewGuid() + "foo";
        zk.Create(name, name.GetBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.Persistent);

        String childname = name + "/bar";
        zk.Create(childname, childname.GetBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.Ephemeral);

        Stat stat = new Stat();
        List<String> s = zk.GetChildren(name, false, stat);

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
        String name = "/" + Guid.NewGuid() + "foo";
        zk.Create(name, name.GetBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.Persistent);

        List<String> children = new List<String>();
        List<String> children_s = new List<String>();

        for (int i = 0; i < 10; i++) {
            String childname = name + "/bar" + i;
            String childname_s = "bar" + i;
            children.Add(childname);
            children_s.Add(childname_s);
        }

        for(int i = 0; i < children.Count; i++) {
            String childname = children[i];
            zk.Create(childname, childname.GetBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.Ephemeral);

            Stat stat = new Stat();
            List<String> s = zk.GetChildren(name, false, stat);

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
        List<String> p = zk.GetChildren(name, false, null);
        List<String> c_a = children_s;
        List<String> c_b = p;
        c_a = c_a.OrderBy(e => e).ToList();
        c_b = c_b.OrderBy(e => e).ToList();

        Assert.AreEqual(c_a.Count, 10);
        Assert.AreEqual(c_a, c_b);
    }
    }
}
