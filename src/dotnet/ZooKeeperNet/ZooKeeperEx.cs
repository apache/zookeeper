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
﻿using System.Collections.Generic;

namespace ZooKeeperNet
{
    using System;
    using System.Text;
    using System.Threading;

    public static class ZooKeeperEx
    {
        public static TValue GetAndRemove<TKey, TValue>(this IDictionary<TKey, TValue> dictionary, TKey key)
        {
            TValue value;
            if (dictionary.TryGetValue(key, out value))
                dictionary.Remove(key);
            return value;
        }

        public static long Nanos(this DateTime dateTime)
        {
            return dateTime.Ticks / 100;
        }

        public static bool IsEmpty<T>(this ICollection<T> collection)
        {
            return collection.Count < 1;
        }

        public static byte[] GetBytes(this string @string)
        {
            return Encoding.UTF8.GetBytes(@string);
        }

        public static IDisposable AcquireReadLock(this ReaderWriterLockSlim @lock)
        {
            @lock.EnterReadLock();
            return new Disposable(@lock.ExitReadLock);
        }

        public static IDisposable AcquireUpgradableReadLock(this ReaderWriterLockSlim @lock)
        {
            @lock.EnterUpgradeableReadLock();
            return new Disposable(@lock.ExitUpgradeableReadLock);
        }

        public static IDisposable AcquireWriteLock(this ReaderWriterLockSlim @lock)
        {
            @lock.EnterWriteLock();
            return new Disposable(@lock.ExitWriteLock);
        }

        public static string Combine(this string parent, string child)
        {
            return parent + "/" + child;
        }

        private struct Disposable : IDisposable
        {
            private readonly Action action;
            private readonly Sentinel sentinel;

            public Disposable(Action action)
            {
                this.action = action;
                sentinel = new Sentinel();
            }

            public void Dispose()
            {
                action();
                GC.SuppressFinalize(sentinel);
            }
        }

        private class Sentinel
        {
            ~Sentinel()
            {
                throw new InvalidOperationException("Lock not properly disposed.");
            }
        }
    }
}
