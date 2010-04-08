using System.Collections.Generic;

namespace SharpKeeper
{
    using System;
    using MiscUtil.Conversion;

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
            var lengthBits = BigEndianBitConverter.INSTANCE.GetBytes(originalLength - 4);
            var newBuffer = new byte[originalLength + lengthBits.Length];
            //Array.Copy(lengthBits, newBuffer, lengthBits.Length - 1);
            //Array.Copy(buffer, 0, newBuffer, lengthBits.Length - 1, originalLength);
            Array.Copy(buffer, newBuffer, buffer.Length - 1);
            Array.Copy(lengthBits, 0, newBuffer, buffer.Length, lengthBits.Length - 1);
            return newBuffer;
        }
    }
}
