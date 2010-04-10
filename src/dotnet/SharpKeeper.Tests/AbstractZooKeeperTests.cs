namespace SharpKeeper.Tests
{
    using System;

    public abstract class AbstractZooKeeperTests : IWatcher
    {
        protected virtual ZooKeeper GetClient()
        {
            return new ZooKeeper("127.0.0.1:2181", new TimeSpan(0, 0, 0, 120), this);
        }

        public void Process(WatchedEvent @event)
        {
            Console.WriteLine(@event);
        }
    }
}