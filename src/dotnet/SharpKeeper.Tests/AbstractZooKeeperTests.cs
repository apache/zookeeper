namespace SharpKeeper.Tests
{
    using System;

    public abstract class AbstractZooKeeperTests : IWatcher
    {
        protected static readonly TimeSpan CONNECTION_TIMEOUT = new TimeSpan(0, 0, 0, 0, 10000);

        protected virtual ZooKeeper CreateClient()
        {
            return new ZooKeeper("192.168.0.180:2181", new TimeSpan(0, 0, 0, 10000), this);
        }

        protected virtual ZooKeeper CreateClient(string node)
        {
            return new ZooKeeper("192.168.0.180:2181" + node, new TimeSpan(0, 0, 0, 10), this);
        }


        protected ZooKeeper CreateClient(IWatcher watcher)
        {
            return new ZooKeeper("192.168.0.180:2181", new TimeSpan(0, 0, 0, 10), watcher);
        }

        public void Process(WatchedEvent @event)
        {
            Console.WriteLine(@event);
        }
    }
}