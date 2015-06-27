using System;
using System.Collections.Generic;
using NUnit.Framework;
using org.apache.utils;

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
    [TestFixture]
    internal sealed class EventTypeTest 
	{
        //[Test]
        //public void testIntConversion()
        //{
        //    // Ensure that we can convert all valid integers to EventTypes
        //    IEnumerable<Watcher.Event.EventType> allTypes = EnumUtil<Watcher.Event.EventType>.GetValues();

        //    foreach (Watcher.Event.EventType et in allTypes) {
        //        Assert.assertEquals(et, (Watcher.Event.EventType) ((int) et));
        //    }
        //}

        [Test]
		public void testInvalidIntConversion()
		{
			try {
			    Watcher.Event.EventType et = EnumUtil<Watcher.Event.EventType>.DefinedCast(324242);
				Assert.fail("Was able to create an invalid EventType via an integer");
			}
			catch (Exception)
			{
				// we're good.
			}

		}
	}

}