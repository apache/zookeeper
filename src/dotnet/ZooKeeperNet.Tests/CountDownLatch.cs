namespace ZooKeeperNet.Tests
{
    using System;
    using System.Threading;

    public class CountDownLatch
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