using NUnit.Framework;

namespace org.apache.zookeeper
{
    [SetUpFixture]
    public class TestsSetup
    {
        [SetUp]
        public void setUp()
        {
            tearDown();

            ClientBase.createNode(ClientBase.testsNode, CreateMode.PERSISTENT).ContinueWith(t =>
            {
                if (t.Exception != null && !(t.Exception.InnerExceptions[0] is KeeperException.NodeExistsException)) throw t.Exception;
            }).Wait();
        }

        [TearDown]
        public void tearDown()
        {
            ClientBase.deleteNode(ClientBase.testsNode).ContinueWith(t =>
            {
                if (t.Exception != null && !(t.Exception.InnerExceptions[0] is KeeperException.NoNodeException)) throw t.Exception;
            }).Wait();
        }
    }
}
