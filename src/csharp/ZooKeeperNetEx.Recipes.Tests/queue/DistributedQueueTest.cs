using System;
using System.Text;
using System.Threading;
using org.apache.utils;
using NUnit.Framework;

// 
// <summary>
// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements.  See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License.  You may obtain a copy of the License at
// 
// http://www.apache.org/licenses/LICENSE-2.0
// 
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// </summary>

namespace org.apache.zookeeper.recipes.queue {
    internal sealed class DistributedQueueTest : ClientBase {

        [Test]
        public void testOffer1() {
            const string dir = "/testOffer1";
            const string testString = "Hello World";
            const int num_clients = 1;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            queueHandles[0].offer(testString.getBytes()).GetAwaiter().GetResult();

            byte[] dequeuedBytes = queueHandles[0].remove().GetAwaiter().GetResult();
            Assert.assertEquals(Encoding.UTF8.GetString(dequeuedBytes), testString);
        }

        [Test]
        public void testOffer2() {
            const string dir = "/testOffer2";
            const string testString = "Hello World";
            const int num_clients = 2;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            queueHandles[0].offer(testString.getBytes()).GetAwaiter().GetResult();

            byte[] dequeuedBytes = queueHandles[1].remove().GetAwaiter().GetResult();
            Assert.assertEquals(Encoding.UTF8.GetString(dequeuedBytes), testString);
        }

        [Test]
        public void testTake1() {
            const string dir = "/testTake1";
            const string testString = "Hello World";
            const int num_clients = 1;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            queueHandles[0].offer(testString.getBytes()).GetAwaiter().GetResult();

            byte[] dequeuedBytes = queueHandles[0].take().GetAwaiter().GetResult();
            Assert.assertEquals(Encoding.UTF8.GetString(dequeuedBytes), testString);
        }

        [Test]
        public void testRemove1() {
            const string dir = "/testRemove1";
            const int num_clients = 1;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            try {
                queueHandles[0].remove().GetAwaiter().GetResult();
            }
            catch (InvalidOperationException) {
                return;
            }
            Assert.assertTrue(false);
        }

        private void createNremoveMtest(string dir, int n, int m) {
            const string testString = "Hello World";
            const int num_clients = 2;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            for (int i = 0; i < n; i++) {
                string offerString = testString + i;
                queueHandles[0].offer(offerString.getBytes()).GetAwaiter().GetResult();
            }

            byte[] data = null;
            for (int i = 0; i < m; i++) {
                data = queueHandles[1].remove().GetAwaiter().GetResult();
            }
            // ReSharper disable once AssignNullToNotNullAttribute
            Assert.assertEquals(Encoding.UTF8.GetString(data), testString + (m - 1));
        }

        [Test]
        public void testRemove2() {
            createNremoveMtest("/testRemove2", 10, 2);
        }

        [Test]
        public void testRemove3() {
            createNremoveMtest("/testRemove3", 1000, 1000);
        }

        private void createNremoveMelementTest(string dir, int n, int m) {
            const string testString = "Hello World";
            const int num_clients = 2;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            for (int i = 0; i < n; i++) {
                string offerString = testString + i;
                queueHandles[0].offer(offerString.getBytes()).GetAwaiter().GetResult();
            }

            for (int i = 0; i < m; i++) {
                queueHandles[1].remove().GetAwaiter().GetResult();
            }
            Assert.assertEquals(Encoding.UTF8.GetString(queueHandles[1].element().GetAwaiter().GetResult()), testString + m);
        }

        [Test]
        public void testElement1() {
            createNremoveMelementTest("/testElement1", 1, 0);
        }

        [Test]
        public void testElement2() {
            createNremoveMelementTest("/testElement2", 10, 2);
        }

        [Test]
        public void testElement3() {
            createNremoveMelementTest("/testElement3", 1000, 500);
        }

        [Test]
        public void testElement4() {
            createNremoveMelementTest("/testElement4", 1000, 1000 - 1);
        }

        [Test]
        public void testTakeWait1() {
            const string dir = "/testTakeWait1";
            const string testString = "Hello World";
            const int num_clients = 1;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            byte[][] takeResult = new byte[1][];
            Thread takeThread = new Thread(()=>{try {
                  takeResult[0] = queueHandles[0].take().GetAwaiter().GetResult();
                }
                catch (KeeperException) {

                }
                catch (ThreadInterruptedException) {

                }});
            takeThread.Start();

            Thread.Sleep(1000);
            Thread offerThread = new Thread(()=>{
                try {
                    queueHandles[0].offer(testString.getBytes()).GetAwaiter().GetResult();
                }
                catch (KeeperException) {

                }
                catch (ThreadInterruptedException) {

                
            }});
            offerThread.Start();
            offerThread.Join();

            takeThread.Join();

            Assert.assertTrue(takeResult[0] != null);
            // ReSharper disable once AssignNullToNotNullAttribute
            Assert.assertEquals(Encoding.UTF8.GetString(takeResult[0]), testString);
        }

        [Test]
        public void testTakeWait2() {
            const string dir = "/testTakeWait2";
            const string testString = "Hello World";
            const int num_clients = 1;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }
            const int num_attempts = 2;
            for (int i = 0; i < num_attempts; i++) {
                byte[][] takeResult = new byte[1][];
                string threadTestString = testString + i;
                Thread takeThread = new Thread(() =>
                {
                    try {
                        takeResult[0] = queueHandles[0].take().GetAwaiter().GetResult();
                    }
                    catch (KeeperException) {

                    }
                    catch (ThreadInterruptedException) {

                    }
                });
                takeThread.Start();

                Thread.Sleep(1000);
                Thread offerThread = new Thread(() =>
                {
                    try {
                        queueHandles[0].offer(threadTestString.getBytes()).GetAwaiter().GetResult();
                    }
                    catch (KeeperException) {

                    }
                    catch (ThreadInterruptedException) {

                    }
                });
                offerThread.Start();
                offerThread.Join();

                takeThread.Join();

                Assert.assertTrue(takeResult[0] != null);
                // ReSharper disable once AssignNullToNotNullAttribute
                Assert.assertEquals(Encoding.UTF8.GetString(takeResult[0]), threadTestString);
            }
        }
    }
}