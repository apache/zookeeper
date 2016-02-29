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
using System.Threading;
using System.Threading.Tasks;

namespace org.apache.utils
{
//Similar to the implementation found in Microsoft.VisualStudio.Threading
    internal class AsyncManualResetEvent
    {
        private readonly object syncObject = new object();

        private bool isSet;

        private TaskCompletionSource<bool> _tcs = new TaskCompletionSource<bool>();

        public Task WaitAsync()
        {
            lock (syncObject)
            {
                return _tcs.Task;
            }
        }

        public void Set()
        {
            TaskCompletionSource<bool> tcs;
            bool flag;
            lock (syncObject)
            {
                flag = !isSet;
                tcs = _tcs;
                isSet = true;
            }
            if (flag && !tcs.Task.IsCompleted)
            {
                Task.Factory.StartNew(s => ((TaskCompletionSource<bool>) s).TrySetResult(true),
                    tcs, CancellationToken.None, TaskCreationOptions.PreferFairness, TaskScheduler.Default);
            }
        }

        public void Reset()
        {
            lock (syncObject)
            {
                if (!isSet) return;
                _tcs = new TaskCompletionSource<bool>();
                isSet = false;
            }
        }
    }
}