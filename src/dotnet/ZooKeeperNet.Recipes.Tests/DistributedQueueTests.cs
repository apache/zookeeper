namespace ZooKeeperNetRecipes.Tests
{
    using System;
    using System.Text;
    using System.Threading;
    using NUnit.Framework;
    using ZooKeeperNet;
    using ZooKeeperNet.Recipes;
    using ZooKeeperNet.Tests;

    [TestFixture]
    public class DistributedQueueTests : AbstractZooKeeperTests
    {
        private ZooKeeper[] clients;

        [TearDown]
        public void Teardown()
        {
            foreach (var zk in clients)
                zk.Dispose();
        }

        [Test]
        public void testOffer1()
        {
            String dir = "/testOffer1" + Guid.NewGuid();
            String testString = "Hello World";
            int num_clients = 1;
            clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++)
            {
                clients[i] = CreateClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            queueHandles[0].Enqueue(Encoding.UTF8.GetBytes(testString));

            byte[] dequeuedBytes = queueHandles[0].Dequeue();
            Assert.AreEqual(Encoding.UTF8.GetString(dequeuedBytes), testString);
        }

        [Test]
        public void testOffer2()
        {
            String dir = "/testOffer2" + Guid.NewGuid();
            String testString = "Hello World";
            int num_clients = 2;
            clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++)
            {
                clients[i] = CreateClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            queueHandles[0].Enqueue(Encoding.UTF8.GetBytes(testString));

            byte[] dequeuedBytes = queueHandles[1].Dequeue();
            Assert.AreEqual(Encoding.UTF8.GetString(dequeuedBytes), testString);
        }

        [Test]
        public void testTake1()
        {
            String dir = "/testTake1" + Guid.NewGuid();
            String testString = "Hello World";
            int num_clients = 1;
            clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++)
            {
                clients[i] = CreateClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            queueHandles[0].Enqueue(testString.GetBytes());

            byte[] dequeuedBytes = queueHandles[0].Take();
            Assert.AreEqual(Encoding.UTF8.GetString(dequeuedBytes), testString);
        }

        [Test]
        public void testRemove1()
        {
            String dir = "/testRemove1" + Guid.NewGuid();
            String testString = "Hello World";
            int num_clients = 1;
            clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++)
            {
                clients[i] = CreateClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            try
            {
                queueHandles[0].Dequeue();
            }
            catch (NoSuchElementException e)
            {
                return;
            }
            Assert.Fail();
        }

        public void createNremoveMtest(String dir, int n, int m)
        {
            String testString = "Hello World";
            int num_clients = 2;
            clients = new ZooKeeper[num_clients];
            var queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++)
            {
                clients[i] = CreateClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            for (int i = 0; i < n; i++)
            {
                var offerString = testString + i;
                queueHandles[0].Enqueue(offerString.GetBytes());
            }

            byte[] data = null;
            for (int i = 0; i < m; i++)
            {
                data = queueHandles[1].Dequeue();
            }
            Assert.AreEqual(testString + (m - 1), Encoding.UTF8.GetString(data));
        }

        [Test]
        public void testRemove2()
        {
            createNremoveMtest("/testRemove2" + Guid.NewGuid(), 10, 2);
        }

        [Test]
        public void testRemove3()
        {
            createNremoveMtest("/testRemove3" + Guid.NewGuid(), 1000, 1000);
        }


        public void createNremoveMelementTest(String dir, int n, int m)
        {
            String testString = "Hello World";
            int num_clients = 2;
            clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++)
            {
                clients[i] = CreateClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            for (int i = 0; i < n; i++)
            {
                String offerString = testString + i;
                queueHandles[0].Enqueue(offerString.GetBytes());
            }

            byte[] data = null;
            for (int i = 0; i < m; i++)
            {
                data = queueHandles[1].Dequeue();
            }
            Assert.AreEqual(Encoding.UTF8.GetString(queueHandles[1].Peek()), testString + m);
        }

        [Test]
        public void testPeek1()
        {
            createNremoveMelementTest("/testElement1" + Guid.NewGuid(), 1, 0);
        }

        [Test]
        public void testPeek2()
        {
            createNremoveMelementTest("/testElement2" + Guid.NewGuid(), 10, 2);
        }

        [Test]
        public void testPeek3()
        {
            createNremoveMelementTest("/testElement3" + Guid.NewGuid(), 1000, 500);
        }

        [Test]
        public void testPeek4()
        {
            createNremoveMelementTest("/testElement4" + Guid.NewGuid(), 1000, 1000 - 1);
        }

        [Test]
        public void testTakeWait1()
        {
            String dir = "/testTakeWait1" + Guid.NewGuid();
            string testString = "Hello World";
            int num_clients = 1;
            clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++)
            {
                clients[i] = CreateClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            byte[][] takeResult = new byte[1][];
            var wait = new ManualResetEvent(false);
            var maxRetry = 10;
            ThreadPool.QueueUserWorkItem(st =>
            {
                try
                {
                    takeResult[0] = queueHandles[0].Take();
                    wait.Set();
                }
                catch (KeeperException)
                {
                }
                catch (ThreadInterruptedException)
                {
                }
            });

            Thread.Sleep(1000);

            ThreadPool.QueueUserWorkItem(st =>
            {
                try
                {
                    while (!wait.WaitOne(1000) && maxRetry-- > 0)
                        queueHandles[0].Enqueue(testString.GetBytes());
                }
                catch (KeeperException)
                {
                }
                catch (ThreadInterruptedException)
                {
                }
            });
            Assert.True(wait.WaitOne(3000), "Failed to take");

            Assert.True(takeResult[0] != null);
            Assert.AreEqual(Encoding.UTF8.GetString(takeResult[0]), testString);
        }

        [Test]
        public void testTakeWait2()
        {
            String dir = "/testTakeWait2" + Guid.NewGuid();
            string testString = "Hello World";
            int num_clients = 1;
            clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++)
            {
                clients[i] = CreateClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }
            int num_attempts = 2;
            for (int i = 0; i < num_attempts; i++)
            {
                byte[][] takeResult = new byte[1][];
                string threadTestString = testString + i;
                var wait = new ManualResetEvent(false);
                int maxRetry = 10;
                ThreadPool.QueueUserWorkItem(st =>
                {
                    try
                    {
                        takeResult[0] = queueHandles[0].Take();
                        wait.Set();
                    }
                    catch (KeeperException e)
                    {
                    }
                    catch (ThreadInterruptedException e)
                    {
                    }
                });

                Thread.Sleep(1000);
                ThreadPool.QueueUserWorkItem(st =>
                {
                    try
                    {
                        while (!wait.WaitOne(1000) && maxRetry-- > 0)
                            queueHandles[0].Enqueue(threadTestString.GetBytes());
                    }
                    catch (KeeperException e)
                    {
                    }
                    catch (ThreadInterruptedException e)
                    {
                    }
                });
                Assert.True(wait.WaitOne(3000), "Failed to take");

                Assert.True(takeResult[0] != null);
                Assert.AreEqual(threadTestString, Encoding.UTF8.GetString(takeResult[0]));
            }
        }

    }
}
