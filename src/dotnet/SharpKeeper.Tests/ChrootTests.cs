using System;

namespace SharpKeeper.Tests
{
    using System.Diagnostics;
    using System.Text;
    using System.Threading;
    using NUnit.Framework;

    [TestFixture]
    public class ChrootTests : AbstractZooKeeperTests
    {
        private class MyWatcher : IWatcher
        {
            private readonly CountDownLatch latch = new CountDownLatch(1);
            private readonly string name;
            private readonly string path;
            private string eventPath;

            public MyWatcher(string name, string path)
            {
                this.name = name;
                this.path = path;
            }

            public void Process(WatchedEvent @event)
            {
                Console.WriteLine(string.Format("latch:{0} {1}-{2}", name, path, @event.Path));
                Debug.WriteLine(string.Format("latch:{0} {1}-{2}", name, path, @event.Path));
                eventPath = @event.Path;
                latch.CountDown();
            }

            public bool Matches()
            {
                if (!latch.Await(CONNECTION_TIMEOUT))
                {
                    Assert.Fail("No watch received within timeout period " + path);
                }
                return path.Equals(eventPath);
            }
        }

        [Test]
        public void testChrootSynchronous()
        {
            string ch1 = "/" + Guid.NewGuid() + "ch1";
            using (ZooKeeper zk1 = CreateClient())
            {
                Assert.AreEqual(ch1, zk1.Create(ch1, null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent));
            }

            string ch2 = "/" + Guid.NewGuid() + "ch2";
            using (ZooKeeper zk2 = CreateClient(ch1))
            {
                Assert.AreEqual(ch2, zk2.Create(ch2, null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent));
            }

            using (ZooKeeper zk1 = CreateClient())
            using (ZooKeeper zk2 = CreateClient(ch1))
            {
                // check get
                MyWatcher w1 = new MyWatcher("w1", ch1);
                Assert.NotNull(zk1.Exists(ch1, w1));
                string ch1Ch2 = string.Format("{0}{1}", ch1, ch2);
                MyWatcher w2 = new MyWatcher("w2", ch1Ch2);
                Assert.NotNull(zk1.Exists(ch1Ch2, w2));

                MyWatcher w3 = new MyWatcher("w3", ch2);
                Assert.NotNull(zk2.Exists(ch2, w3));

                // set watches on child
                MyWatcher w4 = new MyWatcher("w4", ch1);
                zk1.GetChildren(ch1, w4);
                MyWatcher w5 = new MyWatcher("w5", "/");
                zk2.GetChildren("/", w5);

                // check set
                zk1.SetData(ch1, Encoding.UTF8.GetBytes("1"), -1);
                zk2.SetData(ch2, "2".GetBytes(), -1);

                // check watches
                Assert.True(w1.Matches());
                Assert.True(w2.Matches());
                Assert.True(w3.Matches());

                // check exceptions
                string ch3 = "/" + Guid.NewGuid() + "ch3";
                try
                {
                    zk2.SetData(ch3, "3".GetBytes(), -1);
                }
                catch (KeeperException.NoNodeException e)
                {
                    Assert.AreEqual(ch3, e.getPath());
                }

                Assert.AreEqual("1".GetBytes(), zk1.GetData(ch1, false, null));
                Assert.AreEqual("2".GetBytes(), zk1.GetData(ch1Ch2, false, null));
                Assert.AreEqual("2".GetBytes(), zk2.GetData(ch2, false, null));

                // check delete
                zk2.Delete(ch2, -1);
                Assert.True(w4.Matches());
                Assert.True(w5.Matches());

                zk1.Delete(ch1, -1);
                Assert.Null(zk1.Exists(ch1, false));
                Assert.Null(zk1.Exists(ch1Ch2, false));
                Assert.Null(zk2.Exists(ch2, false));
            }

        }

        internal class CountDownLatch
        {
            private readonly ManualResetEvent reset;
            private readonly int occurences;
            private int count;
            private DateTime start;
            private TimeSpan remaining;

            public CountDownLatch(int occurences)
            {
                this.occurences = occurences;
                reset = new ManualResetEvent(false);
            }

            public bool Await(TimeSpan wait)
            {
                start = DateTime.Now;
                remaining = wait;
                while (count < occurences)
                {
                    if (!reset.WaitOne(remaining))
                        return false;
                }
                return true;
            }

            public void CountDown()
            {
                remaining = DateTime.Now - start;
                Interlocked.Increment(ref count);
                reset.Set();
            }

            public int Count
            {
                get
                {
                    return count;
                }
            }
        }
    }
}
