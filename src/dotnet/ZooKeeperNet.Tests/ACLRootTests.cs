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
﻿namespace ZooKeeperNet.Tests
{
    using System;
    using System.Text;
    using NUnit.Framework;

    [TestFixture]
    public class ACLRootTests : AbstractZooKeeperTests
    {
        [Test]
        public void testRootAcl()
        {
            ZooKeeper zk = CreateClient();
            // set auth using digest
            zk.AddAuthInfo("digest", Encoding.UTF8.GetBytes("pat:test"));
            zk.SetACL("/", Ids.CREATOR_ALL_ACL, -1);
            zk.GetData("/", false, null);
            zk.Dispose();

            // verify no access
            zk = CreateClient();
            try
            {
                zk.GetData("/", false, null);
                Assert.Fail("validate auth");
            }
            catch (KeeperException.NoAuthException e)
            {
                // expected
            }
            string path = "/" + Guid.NewGuid() + "apps";
            try
            {
                zk.Create(path, null, Ids.CREATOR_ALL_ACL, CreateMode.Persistent);
                Assert.Fail("validate auth");
            }
            catch (KeeperException.InvalidACLException e)
            {
                // expected
            }
            zk.AddAuthInfo("digest", Encoding.UTF8.GetBytes("world:anyone"));
            try
            {
                zk.Create(path, null, Ids.CREATOR_ALL_ACL,
                          CreateMode.Persistent);
                Assert.Fail("validate auth");
            }
            catch (KeeperException.NoAuthException e)
            {
                // expected
            }
            zk.Dispose();
            // verify access using original auth
            zk = CreateClient();
            zk.AddAuthInfo("digest", Encoding.UTF8.GetBytes("pat:test"));
            zk.GetData("/", false, null);
            zk.Create(path, null, Ids.CREATOR_ALL_ACL, CreateMode.Persistent);
            zk.Delete(path, -1);
            // reset acl (back to open) and verify accessible again
            zk.SetACL("/", Ids.OPEN_ACL_UNSAFE, -1);
            zk.Dispose();
            zk = CreateClient();
            zk.GetData("/", false, null);
            zk.Create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            try
            {
                zk.Create(path, null, Ids.CREATOR_ALL_ACL, CreateMode.Persistent);
                Assert.Fail("validate auth");
            }
            catch (KeeperException.InvalidACLException e)
            {
                // expected
            }
            zk.Delete(path, -1);
            zk.AddAuthInfo("digest", Encoding.UTF8.GetBytes("world:anyone"));
            zk.Create(path, null, Ids.CREATOR_ALL_ACL, CreateMode.Persistent);
            zk.Dispose();
            zk = CreateClient();
            zk.Delete(path, -1);
        }
    }
}
