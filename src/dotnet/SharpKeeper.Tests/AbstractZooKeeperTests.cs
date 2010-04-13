namespace SharpKeeper.Tests
{
    using System;

    public abstract class AbstractZooKeeperTests : IWatcher
    {
        protected static readonly TimeSpan CONNECTION_TIMEOUT = new TimeSpan(0, 0, 0, 0, 10000);

        protected virtual ZooKeeper CreateClient()
        {
            return new ZooKeeper("127.0.0.1:2181", new TimeSpan(0, 0, 0, 10), this);
        }

        protected ZooKeeper CreateClient(IWatcher watcher)
        {
            return new ZooKeeper("127.0.0.1:2181", new TimeSpan(0, 0, 0, 10), watcher);
        }

        public void Process(WatchedEvent @event)
        {
            Console.WriteLine(@event);
        }
    }
}