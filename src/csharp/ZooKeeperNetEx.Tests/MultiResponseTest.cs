/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

using System.IO;
using NUnit.Framework;
using org.apache.jute;
using org.apache.utils;
using org.apache.zookeeper.data;

namespace org.apache.zookeeper
{
	internal sealed class MultiResponseTest : ClientBase
	{
        [Test]
		public void testRoundTrip()
		{
			MultiResponse response = new MultiResponse();

			response.add(new OpResult.CheckResult());
			response.add(new OpResult.CreateResult("foo-bar"));
			response.add(new OpResult.DeleteResult());

			Stat s = new Stat();
			s.setCzxid(546);
			response.add(new OpResult.SetDataResult(s));

			MultiResponse decodedResponse = codeDecode(response);

			Assert.assertEquals(response, decodedResponse);
		}

        [Test]
		public void testEmptyRoundTrip()
		{
			MultiResponse result = new MultiResponse();
			MultiResponse decodedResult = codeDecode(result);

            Assert.assertEquals(result, decodedResult);
		}

		private static MultiResponse codeDecode(MultiResponse request)
		{
            var ms = new MemoryStream();
            BigEndianBinaryWriter baos = new BigEndianBinaryWriter(ms);
			BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
            request.serialize(boa, "result");
		    ms.Position = 0;

		    BinaryInputArchive bia = BinaryInputArchive.getArchive(new BigEndianBinaryReader(ms));
            MultiResponse decodedRequest = new MultiResponse();
            decodedRequest.deserialize(bia, "result");
			return decodedRequest;
		}

	}

}