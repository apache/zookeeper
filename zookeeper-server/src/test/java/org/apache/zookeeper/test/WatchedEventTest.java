/*
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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import java.util.EnumSet;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.proto.WatcherEvent;
import org.junit.jupiter.api.Test;

public class WatchedEventTest extends ZKTestCase {

    @Test
    public void testCreatingWatchedEvent() {
        // EventWatch is a simple, immutable type, so all we need to do
        // is make sure we can create all possible combinations of values.

        EnumSet<EventType> allTypes = EnumSet.allOf(EventType.class);
        EnumSet<KeeperState> allStates = EnumSet.allOf(KeeperState.class);
        WatchedEvent we;

        for (EventType et : allTypes) {
            for (KeeperState ks : allStates) {
                we = new WatchedEvent(et, ks, "blah");
                assertEquals(et, we.getType());
                assertEquals(ks, we.getState());
                assertEquals("blah", we.getPath());
            }
        }
    }

    @Test
    public void testCreatingWatchedEventFromWrapper() {
        // Make sure we can handle any type of correct wrapper

        EnumSet<EventType> allTypes = EnumSet.allOf(EventType.class);
        EnumSet<KeeperState> allStates = EnumSet.allOf(KeeperState.class);
        WatchedEvent we;
        WatcherEvent wep;

        for (EventType et : allTypes) {
            for (KeeperState ks : allStates) {
                wep = new WatcherEvent(et.getIntValue(), ks.getIntValue(), "blah");
                we = new WatchedEvent(wep);
                assertEquals(et, we.getType());
                assertEquals(ks, we.getState());
                assertEquals("blah", we.getPath());
            }
        }
    }

    @Test
    public void testCreatingWatchedEventFromInvalidWrapper() {
        // Make sure we can't convert from an invalid wrapper

        try {
            WatcherEvent wep = new WatcherEvent(-2342, -252352, "foo");
            new WatchedEvent(wep);
            fail("Was able to create WatchedEvent from bad wrapper");
        } catch (RuntimeException re) {
            // we're good
        }
    }

    @Test
    public void testConvertingToEventWrapper() {
        WatchedEvent we = new WatchedEvent(EventType.NodeCreated, KeeperState.Expired, "blah");
        WatcherEvent wew = we.getWrapper();

        assertEquals(EventType.NodeCreated.getIntValue(), wew.getType());
        assertEquals(KeeperState.Expired.getIntValue(), wew.getState());
        assertEquals("blah", wew.getPath());
    }

}
