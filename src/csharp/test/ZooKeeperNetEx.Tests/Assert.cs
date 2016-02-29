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

using System.Collections.Concurrent;
using Newtonsoft.Json;
using AssertXunit=Xunit.Assert;

namespace org.apache.zookeeper {
    internal static class Assert
    {
        public static void assertTrue(bool test)
        {
            AssertXunit.True(test);
        }
        public static void assertTrue(string msg, bool test)
        {
            AssertXunit.True(test, msg);
        }

        public static void assertFalse(bool test)
        {
            AssertXunit.False(test);
        }
        public static void assertFalse(string msg, bool test)
        {
            AssertXunit.False(test, msg);
        }

        public static void assertNull(object obj)
        {
            AssertXunit.Null(obj);
        }
        public static void assertNull(string msg, object obj)
        {
            AssertXunit.True(obj == null, msg);
        }

        public static void assertNotNull(object obj)
        {
            AssertXunit.NotNull(obj);
        }
        public static void assertNotNull(string msg, object obj)
        {
            AssertXunit.True(obj != null, msg);
        }

        public static void fail()
        {
            AssertXunit.True(false);
        }
        public static void fail(string msg)
        {
            AssertXunit.True(false, msg);
        }

        public static void assertEquals(object a, object b)
        {
            string aJson = JsonConvert.SerializeObject(a);
            string bJson = JsonConvert.SerializeObject(b);
            AssertXunit.Equal(aJson, bJson);
        }
        public static void assertEquals(string msg, object a, object b)
        {
            string aJson = JsonConvert.SerializeObject(a);
            string bJson = JsonConvert.SerializeObject(b);
            AssertXunit.True(aJson == bJson, msg);
        }
        
        public static void assertNotEquals<T>(T a, T b)
        {
            string aJson = JsonConvert.SerializeObject(a);
            string bJson = JsonConvert.SerializeObject(b);
            AssertXunit.NotEqual(aJson, bJson);
        }

        public static T poll<T>(this BlockingCollection<T> blockingCollection, int milliSeconds)
        {
            T item;
            blockingCollection.TryTake(out item, milliSeconds);
            return item;
        }
    }
}