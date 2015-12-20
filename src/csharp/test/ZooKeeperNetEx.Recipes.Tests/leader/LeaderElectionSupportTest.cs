using System;
using System.Threading;
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
        private static readonly string testRootNode = "/" + TimeHelper.ElapsedMiliseconds + "_";
        private ZooKeeper zooKeeper;

        public LeaderElectionSupportTest() {

            zooKeeper = createClient();

            zooKeeper.createAsync(testRootNode + Thread.CurrentThread.ManagedThreadId, new byte[0],
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT).Wait();
        }

        public override void Dispose() {
            if (zooKeeper != null) {
                zooKeeper.deleteAsync(testRootNode + Thread.CurrentThread.ManagedThreadId).Wait();
            }

            base.Dispose();
        }

        [Fact]
        public void testNode() {
            var electionSupport = createLeaderElectionSupport();

            electionSupport.start().GetAwaiter().GetResult();
            Thread.Sleep(3000);
            electionSupport.stop().GetAwaiter().GetResult();
        }

        [Fact]
        public void testNodes3() {
            const int testIterations = 3;
            CountdownEvent latch = new CountdownEvent(testIterations);
            ThreadSafeInt failureCounter = new ThreadSafeInt(0);

            for (var i = 0; i < testIterations; i++) {
                runElectionSupportThread(latch, failureCounter);
            }

            Assert.assertEquals(0, failureCounter.Value);

            if (!latch.Wait(10* 1000)) {
                logger.debugFormat("Waited for all threads to start, but timed out. We had {0} failures.", failureCounter);
            }
        }

        [Fact]
        public void testNodes9() {
            const int testIterations = 9;
            

            CountdownEvent latch = new CountdownEvent(testIterations);


            ThreadSafeInt failureCounter = new ThreadSafeInt(0);

            for (var i = 0; i < testIterations; i++) {
                runElectionSupportThread(latch, failureCounter);
            }

            Assert.assertEquals(0, failureCounter.Value);

            if (!latch.Wait(10* 1000)) {
                logger.debugFormat("Waited for all threads to start, but timed out. We had {0} failures.", failureCounter.Value);
            }
        }

        [Fact]
        public void testNodes20() {
            const int testIterations = 20;


            CountdownEvent latch = new CountdownEvent(testIterations);


            ThreadSafeInt failureCounter = new ThreadSafeInt(0);

            for (var i = 0; i < testIterations; i++) {
                runElectionSupportThread(latch, failureCounter);
            }

            Assert.assertEquals(0, failureCounter.Value);

            if (!latch.Wait(10* 1000)) {
                logger.debugFormat("Waited for all threads to start, but timed out. We had {0} failures.", failureCounter);
            }
        }

        [Fact]
        public void testNodes100() {
            const int testIterations = 100;


            CountdownEvent latch = new CountdownEvent(testIterations);


            ThreadSafeInt failureCounter = new ThreadSafeInt(0);

            for (var i = 0; i < testIterations; i++) {
                runElectionSupportThread(latch, failureCounter);
            }

            Assert.assertEquals(0, failureCounter.Value);

            if (!latch.Wait(20* 1000)) {
                logger.debugFormat("Waited for all threads to start, but timed out. We had {0} failures.", failureCounter);
            }
        }

        [Fact]
        public void testOfferShuffle() {
            const int testIterations = 10;


            CountdownEvent latch = new CountdownEvent(testIterations);


            ThreadSafeInt failureCounter = new ThreadSafeInt(0);

            for (var i = 1; i <= testIterations; i++) {
                runElectionSupportThread(latch, failureCounter, Math.Min(i*1200, 10000));
            }

            if (!latch.Wait(60* 1000)) {
                logger.debugFormat("Waited for all threads to start, but timed out. We had {0} failures.", failureCounter);
            }
        }

        [Fact]
        public void testGetLeaderHostName() {
            var electionSupport = createLeaderElectionSupport();

            electionSupport.start().GetAwaiter().GetResult();

            // Sketchy: We assume there will be a leader (probably us) in 3 seconds.
            Thread.Sleep(3000);

            var leaderHostName = electionSupport.getLeaderHostName().GetAwaiter().GetResult();

            Assert.assertNotNull(leaderHostName);
            Assert.assertEquals("foohost", leaderHostName);

            electionSupport.stop().GetAwaiter().GetResult();
        }

        private LeaderElectionSupport createLeaderElectionSupport()
        {
            var electionSupport = new LeaderElectionSupport(zooKeeper,
                testRootNode + Thread.CurrentThread.ManagedThreadId, "foohost");

            return electionSupport;
        }

        private void runElectionSupportThread(CountdownEvent latch, ThreadSafeInt failureCounter) {
            runElectionSupportThread(latch, failureCounter, 3000);
        }

        private void runElectionSupportThread(CountdownEvent latch, ThreadSafeInt failureCounter, int sleepDuration) {

            var electionSupport = createLeaderElectionSupport();

            Thread t = new Thread(() =>
            {
                try
                {
                    electionSupport.start().GetAwaiter().GetResult();
                    Thread.Sleep(sleepDuration);
                    electionSupport.stop().GetAwaiter().GetResult();
                    lock (latch)
                    {
                        if (!latch.IsSet)
                            latch.Signal();
                    }
                }
                catch (Exception e)
                {
                    logger.debugFormat("Failed to run leader election due to: {0}", e.Message);
                    failureCounter.Increment();
                }
            }); 

            t.Start();
        }
    }
}