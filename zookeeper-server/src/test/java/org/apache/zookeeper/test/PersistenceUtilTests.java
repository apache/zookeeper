/**
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

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.server.persistence.ZxidRange;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test server/persistence/Util.java methods
 */
public class PersistenceUtilTests extends ZKTestCase {
    @Test
    public void testShortLogName() {
        testDataFileName(Util.makeLogName(10), Util.TXLOG_PREFIX, new ZxidRange(10));
    }

    @Test
    public void testShortSnapName() {
        testDataFileName(Util.makeSnapshotName(55), Util.SNAP_PREFIX, new ZxidRange(55));
    }

    @Test
    public void testLongSnapName() {
        testDataFileName(Util.makeSnapshotName(100, 160), Util.SNAP_PREFIX, new ZxidRange(100, 160));
    }

    private void testDataFileName(String name, String prefix, ZxidRange expected) {

        long zxid = Util.getZxidFromName(name, prefix);
        ZxidRange zr = Util.getZxidRangeFromName(name, prefix);

        Assert.assertEquals(expected.getLow(), zxid);
        Assert.assertEquals(expected.getLow(), zr.getLow());
        Assert.assertEquals(expected.isHighPresent(), zr.isHighPresent());

        if (expected.isHighPresent()) {
            Assert.assertEquals(expected.getHigh(), zr.getHigh());
        }
    }
}
