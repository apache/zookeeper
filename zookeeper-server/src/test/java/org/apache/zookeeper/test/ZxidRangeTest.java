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

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.persistence.ZxidRange;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/**
 * Test ZxidRange functionality
 */
public class ZxidRangeTest extends ZKTestCase {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void positiveTests() {
        ZxidRange zr = new ZxidRange(44, 66);
        Range<Long> r = zr.toRange();
        Assert.assertEquals(44, zr.getLow());
        Assert.assertEquals(66, zr.getHigh());
        Assert.assertTrue(zr.isHighPresent());
        Assert.assertEquals(44, (long)r.lowerEndpoint());
        Assert.assertEquals(66, (long)r.upperEndpoint());
        Assert.assertEquals(BoundType.CLOSED, r.lowerBoundType());
        Assert.assertEquals(BoundType.CLOSED, r.upperBoundType());

        zr = new ZxidRange(99);
        r = zr.toRange();
        Assert.assertEquals(99, zr.getLow());
        Assert.assertFalse(zr.isHighPresent());
        Assert.assertEquals(99, (long)r.lowerEndpoint());
        Assert.assertEquals(Long.MAX_VALUE, (long)r.upperEndpoint());
        Assert.assertEquals(BoundType.CLOSED, r.lowerBoundType());
        Assert.assertEquals(BoundType.CLOSED, r.upperBoundType());

        zr = new ZxidRange(Range.closed(33L, 101L));
        Assert.assertEquals(33, zr.getLow());
        Assert.assertEquals(101, zr.getHigh());
        Assert.assertTrue(zr.isHighPresent());

        zr = ZxidRange.parse("1-1");
        Assert.assertEquals(1, zr.getLow());
        Assert.assertEquals(1, zr.getHigh());
        Assert.assertTrue(zr.isHighPresent());

        zr = ZxidRange.parse("9f0285dc59-9f02871e94");
        Assert.assertEquals(0x9f0285dc59L, zr.getLow());
        Assert.assertEquals(0x9f02871e94L, zr.getHigh());
        Assert.assertTrue(zr.isHighPresent());

        zr = ZxidRange.parse("9f028d5781");
        Assert.assertEquals(0x9f028d5781L, zr.getLow());
        Assert.assertFalse(zr.isHighPresent());

        zr = ZxidRange.parse(":12-34");
        Assert.assertEquals(ZxidRange.INVALID, zr);

        zr = ZxidRange.parse("45234-");
        Assert.assertEquals(ZxidRange.INVALID, zr);

        zr = ZxidRange.parse("barf");
        Assert.assertEquals(ZxidRange.INVALID, zr);

        zr = ZxidRange.parse("-55");
        Assert.assertEquals(ZxidRange.INVALID, zr);
    }

    @Test
    public void reverseRangeTest() {
        boolean failed = false;
        try {
            ZxidRange zr = new ZxidRange(20, 10);
            failed = true;
        }
        catch (IllegalArgumentException ex) {
        }

        if (failed) {
            Assert.fail("Expected IllegalArgumentException not thrown.");
        }
    }
}
