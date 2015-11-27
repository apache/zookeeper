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
using Newtonsoft.Json;

namespace org.apache.zookeeper {
    internal static class Assert
    {
        public static void assertTrue(bool test)
        {
            NUnit.Framework.Assert.IsTrue(test);
        }
        public static void assertTrue(string msg, bool test)
        {
            NUnit.Framework.Assert.IsTrue(test, msg);
        }

        public static void assertFalse(bool test)
        {
            NUnit.Framework.Assert.IsFalse(test);
        }
        public static void assertFalse(string msg, bool test)
        {
            NUnit.Framework.Assert.IsFalse(test, msg);
        }

        public static void assertNull(object obj)
        {
            NUnit.Framework.Assert.Null(obj);
        }
        public static void assertNull(string msg, object obj)
        {
            NUnit.Framework.Assert.Null(obj, msg);
        }

        public static void assertNotNull(object obj)
        {
            NUnit.Framework.Assert.NotNull(obj);
        }
        public static void assertNotNull(string msg, object obj)
        {
            NUnit.Framework.Assert.NotNull(obj, msg);
        }

        public static void fail()
        {
            NUnit.Framework.Assert.Fail();
        }
        public static void fail(string msg)
        {
            NUnit.Framework.Assert.Fail(msg);
        }

        public static void assertEquals(object a, object b)
        {
            string aJson = JsonConvert.SerializeObject(a);
            string bJson = JsonConvert.SerializeObject(b);
            NUnit.Framework.Assert.AreEqual(aJson, bJson);
        }
        public static void assertEquals(string msg, object a, object b)
        {
            string aJson = JsonConvert.SerializeObject(a);
            string bJson = JsonConvert.SerializeObject(b);
            NUnit.Framework.Assert.AreEqual(aJson, bJson, msg);
        }
        
        public static void assertNotEquals<T>(T a, T b)
        {
            string aJson = JsonConvert.SerializeObject(a);
            string bJson = JsonConvert.SerializeObject(b);
            NUnit.Framework.Assert.AreNotEqual(aJson, bJson);
        }
    }
}