/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
namespace ZooKeeperNet.Tests
{
    using System;
    using System.Net;
    using System.Threading;
    using System.Collections.Generic;
    using log4net;
    using NUnit.Framework;
    using Org.Apache.Zookeeper.Data;

    [TestFixture]
    public class ZooKeeperEndPointTests : AbstractZooKeeperTests
    {
        private const int port = 2181;
        private const int retryCeiling = 10;
        private readonly TimeSpan defaultbackoffInterval = new TimeSpan(0, 2, 0);
        private readonly List<string> ips = new List<string>{"1.1.1.1", "1.1.1.2", "1.1.1.3", "1.1.1.4", "1.1.1.5"};
        private readonly ILog LOG = LogManager.GetLogger(typeof(ZooKeeperEndPointTests));
        private ZooKeeperEndpoints zkEndpoints = null;

        [SetUp]
        public void Init()
        {
            //create endpoints
            zkEndpoints = new ZooKeeperEndpoints(ips.ConvertAll(x => IPAddress.Parse(x))
                .ConvertAll(y => new IPEndPoint(y, port)));
        }

        [Test]
        public void testBackoff() 
        {
            List<int> expectedBackoff = new List<int>{1,1,3,7,15,31,63,127,255,511};
            DateTime nextAvailable = DateTime.Now;
            DateTime lastAvailable = DateTime.Now;
            TimeSpan backoff;
            int totalMinutes;

            for (int i = 1; i <= retryCeiling; i++)
            {
                lastAvailable = nextAvailable;
                nextAvailable = ZooKeeperEndpoint.GetNextAvailability(nextAvailable, defaultbackoffInterval, i);
                backoff = nextAvailable - lastAvailable;
                totalMinutes = (int)backoff.TotalMinutes;
                Assert.AreEqual(expectedBackoff[i - 1], totalMinutes);
            }
        }


        [Test]
        public void testConnectionRetry()
        {
            //set all to failure
            for (int i = 0; i <= ips.Count; i++)
            {
                zkEndpoints.GetNextAvailableEndpoint();
                zkEndpoints.CurrentEndPoint.SetAsFailure();
            }

            zkEndpoints.GetNextAvailableEndpoint();
            //verify that we've cycled back to the first connection
            Assert.AreEqual(ips[4], zkEndpoints.CurrentEndPoint.ServerAddress.Address.ToString());
            
            //verify verify this connection is available
            Assert.AreEqual(DateTime.MinValue, zkEndpoints.CurrentEndPoint.NextAvailability);
            
            //call succeeded
            zkEndpoints.CurrentEndPoint.SetAsSuccess();

            //verify that this is the only connection made available
            for (int i = 0; i <= (ips.Count * 2); i++)
            {
                zkEndpoints.GetNextAvailableEndpoint();
                Assert.AreEqual(ips[4], zkEndpoints.CurrentEndPoint.ServerAddress.Address.ToString());
            }

            //set all back to success
            for (int i = 0; i <= ips.Count; i++)
            {
                zkEndpoints.GetNextAvailableEndpoint();
                zkEndpoints.CurrentEndPoint.SetAsSuccess();
                Assert.AreEqual(DateTime.MinValue, zkEndpoints.CurrentEndPoint.NextAvailability);
            }
        }
        
        [Test, Explicit]
        public void testBackoffExpiration()
        {
            //set all to failure
            for (int i = 0; i <= ips.Count; i++)
            {
                zkEndpoints.GetNextAvailableEndpoint();
                zkEndpoints.CurrentEndPoint.SetAsFailure();
            }

            DateTime backoffTime = zkEndpoints.CurrentEndPoint.NextAvailability;

            do
            {
                zkEndpoints.GetNextAvailableEndpoint();
                if (DateTime.Now > backoffTime)
                {
                    //when the backoff ends we should cycle back to the first connection
                    Assert.AreEqual(ips[0], zkEndpoints.CurrentEndPoint.ServerAddress.Address.ToString());
                    break;
                }
                //This should be the only connection attempted during the backoff
                Assert.AreEqual(ips[4], zkEndpoints.CurrentEndPoint.ServerAddress.Address.ToString());
                Thread.Sleep(500);
            } while (1 == 1);
        }

        [Test, Explicit]
        public void testOutageRecovery()
        {
            //set all to failure
            for (int i = 0; i <= ips.Count; i++)
            {
                zkEndpoints.GetNextAvailableEndpoint();
                zkEndpoints.CurrentEndPoint.SetAsFailure();
            }

            DateTime backoffTime = zkEndpoints.CurrentEndPoint.NextAvailability.AddMinutes(1d);

            do
            {
                zkEndpoints.GetNextAvailableEndpoint();
                zkEndpoints.CurrentEndPoint.SetAsSuccess();
                if (DateTime.Now > backoffTime)
                {
                    foreach (ZooKeeperEndpoint z in zkEndpoints)
                    {
                        Assert.AreEqual(z.NextAvailability, DateTime.MinValue);
                    }
                    break;
                }
                Thread.Sleep(500);
            } while (1 == 1);
        }
    }
}