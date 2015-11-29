using System;
using org.apache.utils;
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
    public sealed class KeeperStateTest
	{
        //[Fact]
        //public void testIntConversion()
        //{
        //    // Ensure that we can convert all valid integers to KeeperStates
        //    EnumSet<Watcher.Event.KeeperState> allStates = EnumSet.allOf(typeof(Watcher.Event.KeeperState));

        //    foreach (Watcher.Event.KeeperState @as in allStates)
        //    {
        //        Assert.assertEquals(@as, Watcher.Event.KeeperState.fromInt(@as.getIntValue()));
        //    }
        //}


        [Fact]
		public void testInvalidIntConversion()
		{
			try {
			    Watcher.Event.KeeperState ks = EnumUtil<Watcher.Event.KeeperState>.DefinedCast(324142);
				Assert.fail("Was able to create an invalid KeeperState via an integer");
			}
			catch (Exception)
			{
				// we're good.
			}

		}

		// <summary>
		// Validate that the deprecated constant still works. There were issues
		// found with switch statements - which need compile time constants.
		// </summary>


        //public void testDeprecatedCodeOkInSwitch()
        //{
        //    int test = 1;
        //    switch (test)
        //    {
        //    case KeeperException.Code.Ok:
        //        Assert.assertTrue(true);
        //        break;
        //    }
        //}

		// <summary>
		// Verify the enum works (paranoid) </summary>

        [Fact]
		public void testCodeOKInSwitch()
		{
			KeeperException.Code test = KeeperException.Code.OK;
			switch (test)
			{
                case KeeperException.Code.OK:
				Assert.assertTrue(true);
				break;
			}
		}
	}

}