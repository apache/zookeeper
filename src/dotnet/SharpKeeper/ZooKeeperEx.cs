using System.Collections.Generic;

namespace SharpKeeper
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
