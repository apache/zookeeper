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

using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Text;

namespace org.apache.zookeeper
{
    internal static class MiscEx {
        public static TValue remove<TKey, TValue>(this IDictionary<TKey, TValue> dictionary, TKey key) {
            TValue value;
            if (dictionary.TryGetValue(key, out value))
                dictionary.Remove(key);
            return value;
        }

        public static bool isAlive(this ZooKeeper.States state) {
            return state != ZooKeeper.States.CLOSED && state != ZooKeeper.States.AUTH_FAILED;
        }

        /**
         * Returns whether we are connected to a server (which
         * could possibly be read-only, if this client is allowed
         * to go to read-only mode)
         * */

        public static bool isConnected(this ZooKeeper.States state) {
            return state == ZooKeeper.States.CONNECTED || state == ZooKeeper.States.CONNECTEDREADONLY;
        }

        public static int size(this ICollection collection) {
            return collection.Count;
        }

        public static void addAll<T>(this HashSet<T> hashSet, HashSet<T> another) {
            hashSet.UnionWith(another);
        }

        public static byte[] getBytes(this string @string) {
            return Encoding.UTF8.GetBytes(@string);
        }

        public static string ToHexString(this long num) {
            return num.ToString("x");
        }
        public static string ToHexString(this byte num)
        {
            return num.ToString("x");
        }

        public static T poll<T>(this BlockingCollection<T> blockingCollection,int milliSeconds) {
            T item;
            blockingCollection.TryTake(out item, milliSeconds);
            return item;
        }
    }
}