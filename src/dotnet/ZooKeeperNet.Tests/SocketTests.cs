namespace ZooKeeperNet.Tests
{
    using System;
    using System.Linq;
    using NUnit.Framework;

    [TestFixture]
    public class SocketTests : AbstractZooKeeperTests
    {
        [Test]
        public void CanReadAndWriteOverASingleFrame()
        {
            SendBigByteArray(10000);
        }

        [Test]
        public void CanReadAndWriteOverTwoFrames()
        {
            SendBigByteArray(20000);
        }

        [Test]
        public void CanReadAndWriteOverManyFrames()
        {
            SendBigByteArray(100000);
        }

        private void SendBigByteArray(int bytes)
        {
            var b = new byte[bytes];
            foreach (var i in Enumerable.Range(0, bytes))
            {
                b[i] = Convert.ToByte(i % 255);
            }

            using (var client = CreateClient())
            {
                var node = "/" + Guid.NewGuid();
                client.Create(node, b, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);

                var received = client.GetData(node, false, null);
                CollectionAssert.AreEqual(b, received);
            }
        }
    }
}
