using System;
using System.Text;
using NUnit.Framework;

namespace SharpKeeper.Tests
{
    [TestFixture]
    public class ZooKeeperIntegrationTests : AbstractZooKeeperTests
    {
        [Test]
        public void Can_create_random_node()
        {
            using (var zk = CreateClient())
            {
                var node = Guid.NewGuid();
                string path = "/" + node;
                var response = zk.Create(path, Encoding.UTF8.GetBytes(path), Ids.OPEN_ACL_UNSAFE, CreateMode.Ephemeral);
                Assert.AreEqual(path, response);
            }
        }

        [Test]
        public void Can_verify_note_exists()
        {
            using (var zk = CreateClient())
            {
                var node = Guid.NewGuid();
                string path = "/" + node;

                var stat = zk.Exists(path, false);
                Assert.IsNull(stat);

                var response = zk.Create(path, Encoding.UTF8.GetBytes(path), Ids.OPEN_ACL_UNSAFE, CreateMode.Ephemeral);
                Assert.AreEqual(path, response);

                stat = zk.Exists(path, false);
                Assert.IsNotNull(stat);
            }
        }
    }
}
