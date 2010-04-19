/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
﻿namespace SharpKeeper
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
                    if (wait == TimeSpan.MaxValue) Monitor.Wait(queue, Timeout.Infinite);
                    else Monitor.Wait(queue, wait);
                }
                queue.Enqueue(data);
                if (queue.Count == 1)
                {
                    Monitor.PulseAll(queue);
                }
            }
        }

        public T Dequeue()
        {
            return TryDequeue(TimeSpan.MaxValue);
        }

        public T TryDequeue(TimeSpan wait)
        {
            lock (queue)
            {
                while (queue.Count == 0)
                {
                    if (disposed) return default(T);

                    if (wait == TimeSpan.MaxValue) Monitor.Wait(queue, Timeout.Infinite);
                    else Monitor.Wait(queue, wait);
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
