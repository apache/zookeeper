using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Threading.Tasks;

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

namespace org.apache.zookeeper.client
{
	/// <summary>
	/// Most simple HostProvider, resolves only on instantiation.
	/// </summary>
	internal sealed class StaticHostProvider : HostProvider
	{

        private readonly List<DnsEndPoint> m_ServerAddresses;

		private int lastIndex = -1;

		private int currentIndex = -1;

        public StaticHostProvider(List<DnsEndPoint> serverAddresses)
		{
            if (serverAddresses.Count == 0)
			{
				throw new ArgumentException("A HostProvider may not be empty!");
			}

            m_ServerAddresses = new List<DnsEndPoint>(serverAddresses.OrderBy(s => Guid.NewGuid()));
		}

		public int size()
		{
			return m_ServerAddresses.Count;
		}

        public async Task<DnsEndPoint> next(long spinDelay)
		{
			++currentIndex;
			if (currentIndex == m_ServerAddresses.Count)
			{
				currentIndex = 0;
			}
			if (currentIndex == lastIndex && spinDelay > 0) 
            {
			    await TaskUtils.Delay(TimeSpan.FromMilliseconds(spinDelay)).ConfigureAwait(false);
			}
			else if (lastIndex == -1)
			{
				// We don't want to sleep on the first ever connect attempt.
				lastIndex = 0;
			}

			return m_ServerAddresses[currentIndex];
		}

		public void onConnected()
		{
			lastIndex = currentIndex;
		}

	}
}