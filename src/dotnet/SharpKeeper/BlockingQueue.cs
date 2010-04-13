namespace SharpKeeper
{
    using System;
    using System.Collections.Generic;
    using System.Threading;

    public class BlockingQueue<T> : IDisposable
    {
        private readonly Queue<T> queue;
        private readonly int maxSize;
        private bool disposed;

        public BlockingQueue(int maxSize)
        {
            this.maxSize = maxSize;
            queue = new Queue<T>(maxSize);
        }

        public int Count
        {
            get
            {
                lock (queue)
                {
                    return queue.Count;
                }
            }
        }

        public void Enqueue(T data)
        {
            TryEnqueue(data, TimeSpan.MaxValue);
        }

        public void TryEnqueue(T data, TimeSpan wait)
        {
            if (data == null) throw new ArgumentNullException("data");
            lock (queue)
            {
                while (queue.Count >= maxSize && !disposed)
                {
                    Monitor.Wait(queue, wait);
                }
                queue.Enqueue(data);
                if (queue.Count == 1)
                {
                    Monitor.PulseAll(queue);
                }
            }
        }

        public void Dequeue()
        {
            TryDequeue(TimeSpan.MaxValue);
        }

        public T TryDequeue(TimeSpan wait)
        {
            lock (queue)
            {
                while (queue.Count == 0)
                {
                    if (disposed) return default(T);
                    Monitor.Wait(queue);
                }
                var answer = queue.Dequeue();
                if (queue.Count == maxSize - 1) Monitor.PulseAll(queue);
                return answer;
            }
        }

        public void Dispose()
        {
            disposed = true;
            Monitor.PulseAll(queue);
        }
    }
}
