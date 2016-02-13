using System;
using System.Threading.Tasks;
using Xunit;

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
    public sealed class DistributedQueueTest : ClientBase {

        [Fact]
        public async Task testOffer1() {
            const string dir = "/testOffer1";
            const string testString = "Hello World";
            const int num_clients = 1;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = await createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            await queueHandles[0].offer(testString.UTF8getBytes());

            byte[] dequeuedBytes = await queueHandles[0].remove();
            Assert.assertEquals(dequeuedBytes.UTF8bytesToString(), testString);
        }

        [Fact]
        public async Task testOffer2() {
            const string dir = "/testOffer2";
            const string testString = "Hello World";
            const int num_clients = 2;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = await createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            await queueHandles[0].offer(testString.UTF8getBytes());

            byte[] dequeuedBytes = await queueHandles[1].remove();
            Assert.assertEquals(dequeuedBytes.UTF8bytesToString(), testString);
        }

        [Fact]
        public async Task testTake1() {
            const string dir = "/testTake1";
            const string testString = "Hello World";
            const int num_clients = 1;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = await createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            await queueHandles[0].offer(testString.UTF8getBytes());

            byte[] dequeuedBytes = await queueHandles[0].take();
            Assert.assertEquals(dequeuedBytes.UTF8bytesToString(), testString);
        }

        [Fact]
        public async Task testRemove1() {
            const string dir = "/testRemove1";
            const int num_clients = 1;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = await createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            try {
                await queueHandles[0].remove();
            }
            catch (InvalidOperationException) {
                return;
            }
            Assert.assertTrue(false);
        }

        private async Task createNremoveMtest(string dir, int n, int m) {
            const string testString = "Hello World";
            const int num_clients = 2;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = await createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            for (int i = 0; i < n; i++) {
                string offerString = testString + i;
                await queueHandles[0].offer(offerString.UTF8getBytes());
            }

            byte[] data = null;
            for (int i = 0; i < m; i++) {
                data = await queueHandles[1].remove();
            }
            // ReSharper disable once AssignNullToNotNullAttribute
            Assert.assertEquals(data.UTF8bytesToString(), testString + (m - 1));
        }

        [Fact]
        public Task testRemove2() {
            return createNremoveMtest("/testRemove2", 10, 2);
        }

        [Fact]
        public Task testRemove3() {
            return createNremoveMtest("/testRemove3", 1000, 1000);
        }

        private async Task createNremoveMelementTest(string dir, int n, int m) {
            const string testString = "Hello World";
            const int num_clients = 2;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = await createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            for (int i = 0; i < n; i++) {
                string offerString = testString + i;
                await queueHandles[0].offer(offerString.UTF8getBytes());
            }

            for (int i = 0; i < m; i++) {
                await queueHandles[1].remove();
            }
            Assert.assertEquals((await queueHandles[1].element()).UTF8bytesToString(), testString + m);
        }

        [Fact]
        public Task testElement1() {
            return createNremoveMelementTest("/testElement1", 1, 0);
        }

        [Fact]
        public Task testElement2() {
            return createNremoveMelementTest("/testElement2", 10, 2);
        }

        [Fact]
        public Task testElement3() {
            return createNremoveMelementTest("/testElement3", 1000, 500);
        }

        [Fact]
        public Task testElement4() {
            return createNremoveMelementTest("/testElement4", 1000, 1000 - 1);
        }

        [Fact]
        public async Task testTakeWait1() {
            const string dir = "/testTakeWait1";
            const string testString = "Hello World";
            const int num_clients = 1;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = await createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            byte[][] takeResult = new byte[1][];
            Task takeTask = Task.Run(async ()=>{try {
                  takeResult[0] = await queueHandles[0].take();
                }
                catch (KeeperException) {

                }});
            await Task.Delay(1000);
            Task offerTask = Task.Run(async ()=>{
                try {
                    await queueHandles[0].offer(testString.UTF8getBytes());
                }
                catch (KeeperException) {

                }});
            await offerTask;

            await takeTask;

            Assert.assertTrue(takeResult[0] != null);
            // ReSharper disable once AssignNullToNotNullAttribute
            Assert.assertEquals(takeResult[0].UTF8bytesToString(), testString);
        }

        [Fact]
        public async Task testTakeWait2() {
            const string dir = "/testTakeWait2";
            const string testString = "Hello World";
            const int num_clients = 1;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++) {
                clients[i] = await createClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }
            const int num_attempts = 2;
            for (int i = 0; i < num_attempts; i++) {
                byte[][] takeResult = new byte[1][];
                string threadTestString = testString + i;
                Task takeTask = Task.Run(async() =>
                {
                    try {
                        takeResult[0] = await queueHandles[0].take();
                    }
                    catch (KeeperException) {

                    }
                });

                await Task.Delay(1000);
                Task offerTask = Task.Run(async () =>
                {
                    try {
                        await queueHandles[0].offer(threadTestString.UTF8getBytes());
                    }
                    catch (KeeperException) {

                    }
                });
                await offerTask;

                await takeTask;

                Assert.assertTrue(takeResult[0] != null);
                // ReSharper disable once AssignNullToNotNullAttribute
                Assert.assertEquals(takeResult[0].UTF8bytesToString(), threadTestString);
            }
        }
    }
}