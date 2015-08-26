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
 * All rights reserved.
 * 
 */


#if NET4
using System.Collections.Generic;
#endif

namespace System.Threading.Tasks
{
    //http://blogs.msdn.com/b/pfxteam/archive/2012/02/12/10266988.aspx
    //http://www.hanselman.com/blog/ComparingTwoTechniquesInNETAsynchronousCoordinationPrimitives.aspx
    [Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Design", "CA1001:TypesThatOwnDisposableFieldsShouldBeDisposable")]
    internal sealed class AsyncLock
    {
#if NET4
        private class AsyncSemaphore
        {
            private readonly static Task s_completed = TaskEx.FromResult(true);
            private readonly Queue<TaskCompletionSource<bool>> m_waiters = new Queue<TaskCompletionSource<bool>>();
            private int m_currentCount; 

            public AsyncSemaphore(int initialCount)
            {
                if (initialCount < 0) throw new ArgumentOutOfRangeException("initialCount");
                m_currentCount = initialCount;
            }

            public Task WaitAsync()
            {
                lock (m_waiters)
                {
                    if (m_currentCount > 0)
                    {
                        --m_currentCount;
                        return s_completed;
                    }
                    else
                    {
                        var waiter = new TaskCompletionSource<bool>();
                        m_waiters.Enqueue(waiter);
                        return waiter.Task;
                    }
                }
            }
            public void Release()
            {
                TaskCompletionSource<bool> toRelease = null;
                lock (m_waiters)
                {
                    if (m_waiters.Count > 0)
                        toRelease = m_waiters.Dequeue();
                    else
                        ++m_currentCount;
                }
                if (toRelease != null)
                    toRelease.SetResult(true);
            }
        }
        private readonly AsyncSemaphore m_semaphore = new AsyncSemaphore(1); 
#else
        private readonly SemaphoreSlim m_semaphore = new SemaphoreSlim(1, 1);
#endif
        private readonly Task<IDisposable> m_releaser;

        [Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Reliability", "CA2000:Dispose objects before losing scope")]
        public AsyncLock()
        {
            m_releaser = TaskEx.FromResult((IDisposable)new Releaser(this));
        }

        public Task<IDisposable> LockAsync()
        {
            var wait = m_semaphore.WaitAsync();
            return wait.IsCompleted ?
                        m_releaser : wait.ContinueWith(
#if NET4
                        (_) => m_releaser.Result
#else
                        (_, state) => (IDisposable)state,
                               m_releaser.Result
#endif
                        ,CancellationToken.None, 
                        TaskContinuationOptions.ExecuteSynchronously, 
                        TaskScheduler.Default);
        }

        private sealed class Releaser : IDisposable
        {
            private readonly AsyncLock m_toRelease;
            internal Releaser(AsyncLock toRelease) { m_toRelease = toRelease; }
            public void Dispose() { m_toRelease.m_semaphore.Release(); }
        }
    }
}
