using System.Collections.Generic;
using System.Net;
using System.Threading.Tasks;
using NUnit.Framework;
using org.apache.utils;
using org.apache.zookeeper.client;

// <summary>
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
// 
//     http://www.apache.org/licenses/LICENSE-2.0
// 
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// </summary>

namespace org.apache.zookeeper.test {
    internal sealed class StaticHostProviderTest : ClientBase
    {

        [Test]
        public async Task testNextGoesRound() {
            HostProvider hostProvider = getHostProvider(2);
            var first = await hostProvider.next(0);
            await hostProvider.next(0);
            Assert.assertEquals(first, await hostProvider.next(0));
        }

        [Test]
        public async Task testNextGoesRoundAndSleeps() {
            byte size = 2;
            HostProvider hostProvider = getHostProvider(size);
            while (size > 0) {
                await hostProvider.next(0);
                --size;
            }
            long start = TimeHelper.ElapsedMiliseconds;
            await hostProvider.next(1000);
            long stop = TimeHelper.ElapsedMiliseconds;
            Assert.assertTrue(900 <= stop - start);
        }

        [Test]
        public async Task testNextDoesNotSleepForZero() {
            byte size = 2;
            HostProvider hostProvider = getHostProvider(size);
            while (size > 0) {
                await hostProvider.next(0);
                --size;
            }
            long start = TimeHelper.ElapsedMiliseconds;
            await hostProvider.next(0);
            long stop = TimeHelper.ElapsedMiliseconds;
            Assert.assertTrue(5 > stop - start);
        }

        [Test]
        public async Task testTwoConsequitiveCallsToNextReturnDifferentElement() {
            HostProvider hostProvider = getHostProvider(2);
            Assert.assertNotEquals(await hostProvider.next(0), await hostProvider.next(0));
        }

        [Test]
        public async Task testOnConnectDoesNotReset() {
            HostProvider hostProvider = getHostProvider(2);
            var first = await hostProvider.next(0);
            hostProvider.onConnected();
            var second = await hostProvider.next(0);
            Assert.assertNotEquals(first, second);
        }


        private static List<DnsEndPoint> getUnresolvedServerAddresses(byte size)
        {
            var list = new List<DnsEndPoint>(size);
            while (size > 0)
            {
                list.Add(new DnsEndPoint("10.10.10." + size, 1234 + size));
                --size;
            }
            return list;
        }

        private static StaticHostProvider getHostProvider(byte size) {
            return new StaticHostProvider(getUnresolvedServerAddresses(size));
        }
    }

}