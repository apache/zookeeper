using System.Collections.Generic;

namespace SharpKeeper
{
    using System;

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
    }
}
