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

namespace ZooKeeperNet
{
    using System;
    using System.Collections.Generic;
    using System.Linq;
    using System.Text;
    using System.Net;

    public class ZooKeeperEndpoint
    {
        private const int retryCeiling = 10;
        private readonly IPEndPoint serverAddress;
        private DateTime nextAvailability = DateTime.MinValue;
        private int retryAttempts = 0;
        private TimeSpan backoffInterval;

        public ZooKeeperEndpoint(IPEndPoint serverAddress, TimeSpan backoffInterval)
        {
            this.serverAddress = serverAddress;
            this.backoffInterval = backoffInterval;
        }

        public IPEndPoint ServerAddress
        {
            get { return this.serverAddress; }
        }

        public DateTime NextAvailability
        {
            get { return this.nextAvailability; }
        }

        public int RetryAttempts
        {
            get { return this.retryAttempts; }
        }

        public void SetAsSuccess()
        {
            this.retryAttempts = 0;
            this.nextAvailability = DateTime.MinValue;
        }

        public void SetAsFailure()
        {
            this.retryAttempts++;
            this.nextAvailability = this.nextAvailability == DateTime.MinValue ? DateTime.Now : this.nextAvailability;
            this.nextAvailability = GetNextAvailability(this.nextAvailability, this.backoffInterval, this.retryAttempts);
        }

        public static DateTime GetNextAvailability(DateTime currentAvailability, TimeSpan backoffInterval, int retryAttempts)
        {
            double backoffIntervalMinutes = backoffInterval.Minutes >= 1 ? backoffInterval.Minutes : 1;
            int backoffMinutes = (int)((1d / backoffIntervalMinutes) * (Math.Pow(backoffIntervalMinutes, Math.Min(retryAttempts, retryCeiling)) - 1d));
            backoffMinutes = backoffMinutes >= 1 ? backoffMinutes : 1;
            return currentAvailability.AddMinutes(backoffMinutes);
        }
    }
}
