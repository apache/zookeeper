using System;
using System.Collections.Generic;
using org.apache.utils;
using org.apache.zookeeper.proto;
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
    public sealed class WatchedEventTest
	{
        [Fact]
		public void testCreatingWatchedEvent()
		{
			// EventWatch is a simple, immutable type, so all we need to do
		   // is make sure we can create all possible combinations of values.

			IEnumerable<Watcher.Event.EventType> allTypes = EnumUtil<Watcher.Event.EventType>.GetValues();
            IEnumerable<Watcher.Event.KeeperState> allStates = EnumUtil<Watcher.Event.KeeperState>.GetValues();

            foreach (Watcher.Event.EventType et in allTypes)
			{
			   foreach (Watcher.Event.KeeperState ks in allStates)
			   {
				   var we = new WatchedEvent(et, ks, "blah");
				   Assert.assertEquals(et, we.get_Type());
				   Assert.assertEquals(ks, we.getState());
				   Assert.assertEquals("blah", we.getPath());
			   }

			}
		}

         [Fact]
		public void testCreatingWatchedEventFromWrapper()
		{
			// Make sure we can handle any type of correct wrapper

            IEnumerable<Watcher.Event.EventType> allTypes = EnumUtil<Watcher.Event.EventType>.GetValues();
            IEnumerable<Watcher.Event.KeeperState> allStates = EnumUtil<Watcher.Event.KeeperState>.GetValues();

             foreach (Watcher.Event.EventType et in allTypes)
			{
			   foreach (Watcher.Event.KeeperState ks in allStates)
			   {
				   var wep = new WatcherEvent((int) et, (int) ks, "blah");
				   var we = new WatchedEvent(wep);
				   Assert.assertEquals(et, we.get_Type());
				   Assert.assertEquals(ks, we.getState());
				   Assert.assertEquals("blah", we.getPath());
			   }
			}
		}

        [Fact]
		public void testCreatingWatchedEventFromInvalidWrapper()
		{
			// Make sure we can't convert from an invalid wrapper

		   try
		   {
			   WatcherEvent wep = new WatcherEvent(-2342, -252352, "foo");
			   WatchedEvent we = new WatchedEvent(wep);
			   Assert.fail("Was able to create WatchedEvent from bad wrapper");
		   }
		   catch (Exception)
		   {
			   // we're good
		   }
		}

        [Fact]
	   public void testConvertingToEventWrapper()
	   {
		   WatchedEvent we = new WatchedEvent(Watcher.Event.EventType.NodeCreated, Watcher.Event.KeeperState.Expired, "blah");
		   WatcherEvent wew = we.getWrapper();

		   Assert.assertEquals((int)Watcher.Event.EventType.NodeCreated, wew.get_Type());
           Assert.assertEquals((int)Watcher.Event.KeeperState.Expired, wew.getState());
		   Assert.assertEquals("blah", wew.getPath());
	   }
	}

}