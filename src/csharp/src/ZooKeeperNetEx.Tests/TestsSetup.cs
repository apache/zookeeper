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
using NUnit.Framework;

namespace org.apache.zookeeper
{
    [SetUpFixture]
    public class TestsSetup
    {
        [SetUp]
        public void setUp()
        {
            tearDown();

            ClientBase.createNode(ClientBase.testsNode, CreateMode.PERSISTENT).ContinueWith(t =>
            {
                if (t.Exception != null && !(t.Exception.InnerExceptions[0] is KeeperException.NodeExistsException)) throw t.Exception;
            }).GetAwaiter().GetResult();
        }

        [TearDown]
        public void tearDown()
        {
            ClientBase.deleteNode(ClientBase.testsNode).ContinueWith(t =>
            {
                if (t.Exception != null && !(t.Exception.InnerExceptions[0] is KeeperException.NoNodeException)) throw t.Exception;
            }).GetAwaiter().GetResult();
        }
    }
}
