using System;

namespace SharpKeeper.Tests
{
    using NUnit.Framework;

    [TestFixture]
    public class NullDataTests : AbstractZooKeeperTests
    {
        [Test]
        public void testNullData()
        {
            string path = "/" + Guid.NewGuid() + "SIZE";
            ZooKeeper zk;
            using (zk = CreateClient())
            {
                zk.Create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                // try sync zk exists 
                var stat = zk.Exists(path, false);
                Assert.AreEqual(0, stat.DataLength);
            }
        }
    }
}
