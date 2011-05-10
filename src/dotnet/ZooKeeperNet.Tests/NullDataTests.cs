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
namespace ZooKeeperNet.Tests
{
    using System;
    using NUnit.Framework;

    [TestFixture]
    public class NullDataTests : AbstractZooKeeperTests
    {
        [Test]
        public void testNullData()
        {
            string path = "/" + Guid.NewGuid() + "SIZE";
            ZooKeeper zk;
            using (zk = CreateClient())
            {
                zk.Create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                // try sync zk exists 
                var stat = zk.Exists(path, false);
                Assert.AreEqual(0, stat.DataLength);
            }
        }
    }
}
