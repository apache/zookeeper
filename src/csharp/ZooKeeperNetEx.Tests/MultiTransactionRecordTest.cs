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

using System.IO;
using NUnit.Framework;
using org.apache.jute;
using org.apache.utils;

namespace org.apache.zookeeper
{
    internal sealed class MultiTransactionRecordTest : ClientBase
	{
        [Test]
		public void testRoundTrip()
		{
			MultiTransactionRecord request = new MultiTransactionRecord();
			request.add(Op.check("check", 1));
			request.add(Op.create("create", "create data".getBytes(), ZooDefs.Ids.CREATOR_ALL_ACL, (int) ZooDefs.Perms.ALL));
			request.add(Op.delete("delete", 17));
			request.add(Op.setData("setData", "set data".getBytes(), 19));

			MultiTransactionRecord decodedRequest = codeDecode(request);

			Assert.assertEquals(request, decodedRequest);
            Assert.assertEquals(request.GetHashCode(), decodedRequest.GetHashCode());
		}

        [Test]
		public void testEmptyRoundTrip()
		{
			MultiTransactionRecord request = new MultiTransactionRecord();
			MultiTransactionRecord decodedRequest = codeDecode(request);

            Assert.assertEquals(request, decodedRequest);
            Assert.assertEquals(request.GetHashCode(), decodedRequest.GetHashCode());
		}

		private MultiTransactionRecord codeDecode(MultiTransactionRecord request) {
		    var ms = new MemoryStream();
            BigEndianBinaryWriter baos = new BigEndianBinaryWriter(ms);
			BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
			request.serialize(boa, "request");
		    ms.Position = 0;

		    BinaryInputArchive bia = BinaryInputArchive.getArchive(new BigEndianBinaryReader(ms));
			MultiTransactionRecord decodedRequest = new MultiTransactionRecord();
			decodedRequest.deserialize(bia, "request");
			return decodedRequest;
		}
	}

}