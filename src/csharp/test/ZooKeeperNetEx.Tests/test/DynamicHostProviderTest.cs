using System.Collections.Generic;
﻿using System;
using System.Diagnostics;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;
using org.apache.utils;
using org.apache.zookeeper.client;
using Xunit;

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

namespace org.apache.zookeeper.test
{
    public sealed class DynamicHostProviderTest : ClientBase
    {
        [Fact]
        public async Task testNextGoesRound()
        {
            HostProvider hostProvider = getHostProvider(1);
            await hostProvider.next(0);
            var first = await hostProvider.next(0);
            var second = await hostProvider.next(0);
            Xunit.Assert.Equal(first, second);
        }

        [Fact]
        public async Task testNextGoesRoundAndSleeps()
        {
            byte size = 2;
            HostProvider hostProvider = getHostProvider(size);
            await hostProvider.next(0);
            while (size > 0)
            {
                await hostProvider.next(0);
                --size;
            }
            long start = TimeHelper.ElapsedMiliseconds;
            await hostProvider.next(1000);
            long stop = TimeHelper.ElapsedMiliseconds;
            Assert.assertTrue(900 <= stop - start);
        }

        [Fact]
        public async Task testNextDoesNotSleepForZero()
        {
            byte size = 2;
            HostProvider hostProvider = getHostProvider(size);
            await hostProvider.next(0);
            while (size > 0)
            {
                await hostProvider.next(0);
                --size;
            }
            long start = TimeHelper.ElapsedMiliseconds;
            await hostProvider.next(0);
            long stop = TimeHelper.ElapsedMiliseconds;
            Assert.assertTrue(5 > stop - start);
        }

        [Fact]
        public async Task testTwoConsequitiveCallsToNextReturnDifferentElement()
        {
            HostProvider hostProvider = getHostProvider(2);
            await hostProvider.next(0);
            Assert.assertNotEquals((await hostProvider.next(0)).ToString(), (await hostProvider.next(0)).ToString());
        }

        [Fact]
        public async Task testOnConnectDoesNotReset()
        {
            HostProvider hostProvider = getHostProvider(2);
            await hostProvider.next(0);
            var first = await hostProvider.next(0);
            hostProvider.onConnected();
            var second = await hostProvider.next(0);
            Assert.assertNotEquals(first.ToString(), second.ToString());
        }

        private static readonly TypeLogger<DynamicHostProviderTest> log = new TypeLogger<DynamicHostProviderTest>();

        [Fact]
        public async Task testResetAfterConnectPutsFirst()
        {
            HostProvider hostProvider = getHostProvider(20);
            await hostProvider.next(0);
            var endpoint = await hostProvider.next(0);
            hostProvider.onConnected();
            for (int i = 0; i < 19; i++) await hostProvider.next(0);
            Assert.assertEquals(endpoint.ToString(), (await hostProvider.next(0)).ToString());
        }

        [Fact]
        public async Task TestFirstAndSecondAreSame()
        {
            HostProvider hostProvider = getHostProvider(20);
            Assert.assertEquals((await hostProvider.next(0)).ToString(), (await hostProvider.next(0)).ToString());
        }

        [Fact]
        public async Task testOnlyOneAvailable()
        {
            log.debug("START - testOnlyOneAvailable");
            var connectionString = Enumerable.Range(0, 9).Select(i => Guid.NewGuid().ToString("N")).ToCommaDelimited() +
                                   ",localhost";
            var zk = new ZooKeeper(connectionString, CONNECTION_TIMEOUT, NullWatcher.Instance);
            await zk.existsAsync("/");
            await zk.closeAsync();
            log.debug("END - testOnlyOneAvailable");
        }

        [Fact]
        public async Task testNoOneAvailable()
        {
            log.debug("START - testNoOneAvailable");
            var connectionString = Enumerable.Range(0, 9).Select(i => Guid.NewGuid().ToString("N")).ToCommaDelimited();
            var zk = new ZooKeeper(connectionString, CONNECTION_TIMEOUT, NullWatcher.Instance);
            try
            {
                await zk.existsAsync("/");
            }
            catch (KeeperException.ConnectionLossException)
            {
                return;
            }
            finally
            {
                await zk.closeAsync();
            }
            log.debug("END - testNoOneAvailable");
        }

        private static List<HostAndPort> getUnresolvedServerAddresses(byte size)
        {
            var list = new List<HostAndPort>(size);
            while (size > 0)
            {
                list.Add(new HostAndPort("10.10.10." + size, 1234 + size));
                --size;
            }
            return list;
        }

        private static DynamicHostProvider getHostProvider(byte size, IDnsResolver dnsResolver = null)
        {
            return new DynamicHostProvider(getUnresolvedServerAddresses(size), dnsResolver, log);
        }

        private class FakeDnsResolver : IDnsResolver
        {
            public Func<Task<IEnumerable<ResolvedEndPoint>>> ResolveFunc;

            public Task<IEnumerable<ResolvedEndPoint>> Resolve(IEnumerable<HostAndPort> unresolvedHosts)
            {
                return ResolveFunc();
            }
            
        }

        [Fact]
        public async Task TestBeforeFirstDnsSuccess()
        {
            var fakeDnsResolver = new FakeDnsResolver();
            var resolvedEndPoints = new List<ResolvedEndPoint>();
            fakeDnsResolver.ResolveFunc = () => Task.FromResult((IEnumerable<ResolvedEndPoint>) resolvedEndPoints);
            var sw = new Stopwatch();

            sw.Start();
            var dynamicHostProvider = getHostProvider(1, fakeDnsResolver);
            //before calling next() for the first time
            AssertState(dynamicHostProvider, currentIndex: -1, lastIP: null, resolvingInBackground: false,
                firstDnsTry: true);
            //expected to fail since no IPs were returned
            await Xunit.Assert.ThrowsAsync<SocketException>(() => dynamicHostProvider.next(1000));
            //FirstDnsTry is now false
            AssertState(dynamicHostProvider, currentIndex: -1, lastIP: null, resolvingInBackground: false,
                firstDnsTry: false);
            //should have ignored given timeouts, since it was the first dns attempt
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds <= 15);

            sw.Restart();
            //again the dns resolution returns an empty list, we expected to fail
            await Xunit.Assert.ThrowsAsync<SocketException>(() => dynamicHostProvider.next(1000));
            AssertState(dynamicHostProvider, currentIndex: -1, lastIP: null, resolvingInBackground: false);
            //we expect the timeout to be used, since it's not the first dns attempt
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds >= 999);

            //adding a resolved address to dns resolution
            var resolvedEndPoint1 = new ResolvedEndPoint(IPAddress.Loopback, 999);
            resolvedEndPoints.Add(resolvedEndPoint1);

            sw.Restart();
            //this is the first successful dns attempt, after a failed one. 
            var next = await dynamicHostProvider.next(1000);
            Xunit.Assert.Equal(resolvedEndPoint1, next);
            AssertState(dynamicHostProvider, currentIndex: -1, lastIP: null, resolvingInBackground: false);
            //we expect the timeout to be used, since it's not the first dns attempt
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds >= 999);

            sw.Restart();
            //we asked for the next, knowing it would be the same address
            next = await dynamicHostProvider.next(1000);
            Xunit.Assert.Equal(next, resolvedEndPoint1);
            AssertState(dynamicHostProvider, currentIndex: 0, lastIP: resolvedEndPoint1, resolvingInBackground: false);
            //we ignore the timeout because it's the first time we're in the main loop
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds <= 10);

            sw.Restart();
            //Creating a new host provider, this time the dns resolve will succeed on first try
            dynamicHostProvider = getHostProvider(1, fakeDnsResolver);
            next = await dynamicHostProvider.next(1000);
            Xunit.Assert.Equal(next, resolvedEndPoint1);
            AssertState(dynamicHostProvider, currentIndex: -1, lastIP: null, resolvingInBackground: false);
            next = await dynamicHostProvider.next(1000);
            Xunit.Assert.Equal(next, resolvedEndPoint1);
            AssertState(dynamicHostProvider, currentIndex: 0, lastIP: resolvedEndPoint1, resolvingInBackground: false);
            //we ignored the timeout since we have succeeded in resolving an address
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds <= 15);

            sw.Restart();
            //we ask for the next address, but this time we expect the timeout. bcz we looped
            next = await dynamicHostProvider.next(1000);
            Xunit.Assert.Equal(next, resolvedEndPoint1);
            AssertState(dynamicHostProvider, currentIndex: 0, lastIP: resolvedEndPoint1, resolvingInBackground: false);
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds >= 999);
        }
        public async Task TestAfterFirstDnsSuccess()
        {
            var fakeDnsResolver = new FakeDnsResolver();
            var resolvedEndPoints = new List<ResolvedEndPoint>();
            //adding a resolved address to dns resolution
            var resolved1 = new ResolvedEndPoint(IPAddress.Loopback, 999);
            resolvedEndPoints.Add(resolved1);

            fakeDnsResolver.ResolveFunc = async () =>
            {
                await Task.Delay(200);
                return resolvedEndPoints;
            };

            var dynamicHostProvider = getHostProvider(1, fakeDnsResolver);
            await dynamicHostProvider.next(0);
            await dynamicHostProvider.next(0);
            AssertState(dynamicHostProvider, currentIndex: 0, lastIP: resolved1, resolvingInBackground: false);

            //clearing resolveEndPoints in order to make sure background resolving is ignored when it fails
            resolvedEndPoints.Clear();

            var sw = new Stopwatch();
            sw.Restart();
            //this should start a background dns resolving task that's expected to fail
            var next = await dynamicHostProvider.next(100);
            Xunit.Assert.Equal(next, resolved1);
            AssertState(dynamicHostProvider, currentIndex: 0, lastIP: resolved1, resolvingInBackground: true);
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds >=99 && sw.ElapsedMilliseconds <= 200);

            //the resolving runs in the background, we make sure we can still get the last ip
            sw.Restart();
            next = await dynamicHostProvider.next(10);
            Xunit.Assert.Equal(next, resolved1);
            AssertState(dynamicHostProvider, currentIndex: 0, lastIP: resolved1, resolvingInBackground: true);
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds >= 9 && sw.ElapsedMilliseconds <= 200);

            await Task.Delay(300);

            //background resolving should have ended by now
            AssertState(dynamicHostProvider, currentIndex: 0, lastIP: resolved1, resolvingInBackground: false);

            //add a new ip to the returned list (we only have one now) this doesn't yet affect anything yet
            var resolved2 = new ResolvedEndPoint(IPAddress.Loopback, 998);
            resolvedEndPoints.Add(resolved2);

            sw.Restart();
            //make sure we still have the last known ip available
            next = await dynamicHostProvider.next(100);
            Xunit.Assert.Equal(next, resolved1);
            //we should start another background dns resolving which should return 'resolve2'
            AssertState(dynamicHostProvider, currentIndex: 0, lastIP: resolved1, resolvingInBackground: true);
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds >= 99);

            await Task.Delay(300);

            //background resolving should have ended by now
            AssertState(dynamicHostProvider, currentIndex: 0, lastIP: resolved1, resolvingInBackground: false);

            sw.Restart();
            //we expect to get 'resolve2' without the timeout since it's different than 'resolve1'
            next = await dynamicHostProvider.next(1000);
            Xunit.Assert.Equal(next, resolved2);
            AssertState(dynamicHostProvider, currentIndex: 0, lastIP: resolved2, resolvingInBackground: false);
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds <= 15);

            //add back 'resolved1' to the returned list (the list is: 'resolved1','resolved2')
            resolvedEndPoints.Add(resolved1);

            sw.Restart();
            //we expect to get 'resolve1' without the timeout since 'resolve2' was returned after a timeout
            next = await dynamicHostProvider.next(1000);
            Xunit.Assert.Equal(next, resolved1);
            AssertState(dynamicHostProvider, currentIndex: 0, lastIP: resolved2, resolvingInBackground: false);
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds <= 15);

            sw.Restart();
            //we expect to get 'resolve2' with the timeout since it's the LastIP
            next = await dynamicHostProvider.next(100);
            Xunit.Assert.Equal(next, resolved2);
            AssertState(dynamicHostProvider, currentIndex: 1, lastIP: resolved2, resolvingInBackground: false);
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds >= 99);

            sw.Restart();
            //we expect to get 'resolve1' without the timeout since 'resolve2' was returned after a timeout
            next = await dynamicHostProvider.next(1000);
            Xunit.Assert.Equal(next, resolved1);
            AssertState(dynamicHostProvider, currentIndex: 0, lastIP: resolved2, resolvingInBackground: false);
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds <= 15);

            //notify we have successfully connected with 'resolve1'
            dynamicHostProvider.onConnected();

            sw.Restart();
            //we expect to get 'resolve2' without the timeout since it's NOT the LastIP
            next = await dynamicHostProvider.next(1000);
            Xunit.Assert.Equal(next, resolved2);
            AssertState(dynamicHostProvider, currentIndex: 1, lastIP: resolved1, resolvingInBackground: false);
            Assert.assertTrue($"is {sw.ElapsedMilliseconds}", sw.ElapsedMilliseconds <= 15);
        }

        private void AssertState(DynamicHostProvider dynamicHostProvider, int currentIndex, ResolvedEndPoint lastIP, bool resolvingInBackground, bool firstDnsTry=false)
        {
            Xunit.Assert.Equal(currentIndex, dynamicHostProvider.CurrentIndex);
            Xunit.Assert.Equal(lastIP, dynamicHostProvider.LastIP);
            Xunit.Assert.Equal(firstDnsTry, dynamicHostProvider.FirstDnsTry);
            Xunit.Assert.Equal(resolvingInBackground, dynamicHostProvider.ResolvingInBackground);
        }
    }
}