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

using NUnit.Framework;
using org.apache.zookeeper.client;

namespace org.apache.zookeeper.test
{

    internal class ConnectStringParserTest : ClientBase
	{
        [Test]
		public void testSingleServerChrootPath()
		{
			const string chrootPath = "/hallo/welt";
			const string servers = "10.10.10.1";
			assertChrootPath(chrootPath, new ConnectStringParser(servers + chrootPath));
		}

        [Test]
		public void testMultipleServersChrootPath()
		{
			const string chrootPath = "/hallo/welt";
			const string servers = "10.10.10.1,10.10.10.2";
			assertChrootPath(chrootPath, new ConnectStringParser(servers + chrootPath));
		}

        [Test]
		public void testParseServersWithoutPort()
		{
			const string servers = "10.10.10.1,10.10.10.2";
			ConnectStringParser parser = new ConnectStringParser(servers);

			Assert.assertEquals("10.10.10.1", parser.getServerAddresses()[0].Host);
            Assert.assertEquals("10.10.10.2", parser.getServerAddresses()[1].Host);
		}

        [Test]
		public void testParseServersWithPort()
		{
			const string servers = "10.10.10.1:112,10.10.10.2:110";
			ConnectStringParser parser = new ConnectStringParser(servers);

            Assert.assertEquals("10.10.10.1", parser.getServerAddresses()[0].Host);
            Assert.assertEquals("10.10.10.2", parser.getServerAddresses()[1].Host);

            Assert.assertEquals(112, parser.getServerAddresses()[0].Port);
            Assert.assertEquals(110, parser.getServerAddresses()[1].Port);
		}

		private void assertChrootPath(string expected, ConnectStringParser parser)
		{
			Assert.assertEquals(expected, parser.getChrootPath());
		}
	}

}