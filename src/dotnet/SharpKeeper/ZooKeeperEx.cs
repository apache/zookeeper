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

        public static byte[] WrapLength(this byte[] buffer)
        {
            var originalLength = buffer.Length;
            var lengthBits = BitConverter.GetBytes(originalLength);
            var newBuffer = new byte[originalLength + lengthBits.Length];
            Array.Copy(lengthBits, newBuffer, lengthBits.Length - 1);
            Array.Copy(buffer, 0, newBuffer, lengthBits.Length - 1, originalLength);
            return newBuffer;
        }
    }
}
