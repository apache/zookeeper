using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using org.apache.utils;
using Xunit;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

namespace org.apache.zookeeper.recipes.leader {
    public sealed class LeaderElectionSupportTest : ClientBase {
        private static readonly ILogProducer logger = TypeLogger<LeaderElectionSupportTest>.Instance;
        private static int globalCounter;
        private readonly string root = "/" + Interlocked.Increment(ref globalCounter);
        private readonly ZooKeeper zooKeeper;

        public LeaderElectionSupportTest() {

            zooKeeper = createClient().Result;

            zooKeeper.createAsync(root, new byte[0],
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT).Wait();
        }

        [Fact]
        public async Task testNode() {
            var electionSupport = createLeaderElectionSupport();

            await electionSupport.start();
            await Task.Delay(3000);
            await electionSupport.stop();
        }

        private async Task CreateTestNodesTask(int testIterations, int millisecondsDelay)
        {
            Assert.assertTrue(await Task.WhenAll(Enumerable.Repeat(runElectionSupportTask(), testIterations))
                        .WithTimeout(millisecondsDelay));
        }

        [Fact]
        public Task testNodes3() {
            return CreateTestNodesTask(3, 10 * 1000);
        }

        [Fact]
        public Task testNodes9() {
            return CreateTestNodesTask(9, 10 * 1000);
        }

        [Fact]
        public Task testNodes20() {
            return CreateTestNodesTask(20, 10 * 1000);
        }

        [Fact]
        public Task testNodes100() {
            return CreateTestNodesTask(100, 20 * 1000);
        }

        [Fact]
        public async Task testOfferShuffle() {
            const int testIterations = 10;

            var elections = Enumerable.Range(1, testIterations)
                .Select(i => runElectionSupportTask(Math.Min(i*1200, 10000)));
            Assert.assertTrue(await Task.WhenAll(elections).WithTimeout(60*1000));
        }

        [Fact]
        public async Task testGetLeaderHostName() {
            var electionSupport = createLeaderElectionSupport();

            await electionSupport.start();

            // Sketchy: We assume there will be a leader (probably us) in 3 seconds.
            await Task.Delay(3000);

            var leaderHostName = await electionSupport.getLeaderHostName();

            Assert.assertNotNull(leaderHostName);
            Assert.assertEquals("foohost", leaderHostName);

            await electionSupport.stop();
        }

        private LeaderElectionSupport createLeaderElectionSupport()
        {
            return new LeaderElectionSupport(zooKeeper, root, "foohost");
        }

        private async Task runElectionSupportTask(int sleepDuration = 3000)
        {
            var electionSupport = createLeaderElectionSupport();

            await electionSupport.start();
            await Task.Delay(sleepDuration);
            await electionSupport.stop();
        }
    }
}