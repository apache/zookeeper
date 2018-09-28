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
package org.apache.zookeeper.server.watch;

import java.util.Set;
import java.util.HashSet;

import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.server.DumbWatcher;
import org.apache.zookeeper.server.util.BitHashSet;

import org.apache.zookeeper.ZKTestCase;
import org.junit.Test;
import org.junit.Assert;

public class WatcherOrBitSetTest extends ZKTestCase {

    @Test
    public void testWatcherSet() {
        Set<Watcher> wset = new HashSet<Watcher>();
        WatcherOrBitSet hashSet = new WatcherOrBitSet(wset);
        Assert.assertEquals(0, hashSet.size());

        DumbWatcher w1 = new DumbWatcher();
        Assert.assertFalse(hashSet.contains(w1));
        wset.add(w1);
        Assert.assertTrue(hashSet.contains(w1));
        Assert.assertEquals(1, hashSet.size());
        Assert.assertFalse(hashSet.contains(1));
    }

    @Test
    public void testBitSet() {
        BitHashSet bset = new BitHashSet(0);
        WatcherOrBitSet bitSet = new WatcherOrBitSet(bset);
        Assert.assertEquals(0, bitSet.size());

        Integer bit = new Integer(1);
        Assert.assertFalse(bitSet.contains(1));
        Assert.assertFalse(bitSet.contains(bit));

        bset.add(bit);
        Assert.assertTrue(bitSet.contains(1));
        Assert.assertTrue(bitSet.contains(bit));
        Assert.assertEquals(1, bitSet.size());
    }
}
