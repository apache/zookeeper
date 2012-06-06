namespace ZooKeeperNetRecipes.Tests
{
    using System;
    using System.Linq;
    using NUnit.Framework;
    using ZooKeeperNet.Recipes;
    using ZooKeeperNet;
    using ZooKeeperNet.Tests;

    [TestFixture]
    public class ZNodeNameTests : AbstractZooKeeperTests
    {
        [Test]
        public void testDeleteAllNodeExceptPraweda()
        {
            using (var zk = CreateClient())
            {
                DeleteChild(zk, "/");
            }
        }

        private void DeleteChild(ZooKeeper zk, string path)
        {
            if (!string.IsNullOrEmpty(path) && !path.Contains("praweda") && !path.Contains("zookeeper"))
            {
                var lstChild = zk.GetChildren(path, false);
                foreach (var child in lstChild)
                {
                    if (path != "/")
                        DeleteChild(zk, path + "/" + child);
                    else
                        DeleteChild(zk, "/" + child);
                }
                if (path != "/")
                    zk.Delete(path, -1);
            }
        }
        [Test]
        public void testOrderWithSamePrefix()
        {
            String[] names = { "x-3", "x-5", "x-11", "x-1" };
            String[] expected = { "x-1", "x-3", "x-5", "x-11" };
            AssertOrderedNodeNames(names, expected);
        }
        
        [Test]
        public void testOrderWithDifferentPrefixes()
        {
            String[] names = { "r-3", "r-2", "r-1", "w-2", "w-1" };
            String[] expected = { "r-1", "r-2", "r-3", "w-1", "w-2" };
            AssertOrderedNodeNames(names, expected);
        }

        protected void AssertOrderedNodeNames(String[] names, String[] expected)
        {
            Assert.AreEqual(names.Length, expected.Length, "The two arrays should be the same size!");
            var nodeNames = new SortedSet<ZNodeName>();
            foreach (string name in names)
            {
                nodeNames.Add(new ZNodeName(name));
            }

            int index = 0;
            foreach (string name in nodeNames.Select(nodeName => nodeName.Name))
            {
                Assert.AreEqual(name, expected[index++]);
            }
        }
    }
}
