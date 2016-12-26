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

import org.apache.zookeeper.server.PathIterator;
import org.junit.Assert;
import org.junit.Test;

public class PathIteratorTest {
    @Test
    public void testRoot() {
        PathIterator pathIterator = new PathIterator("/");
        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertFalse(pathIterator.atParentPath());
        Assert.assertEquals(pathIterator.next(), "/");
        Assert.assertFalse(pathIterator.hasNext());
    }

    @Test
    public void test1Level() {
        PathIterator pathIterator = new PathIterator("/a");
        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertFalse(pathIterator.atParentPath());
        Assert.assertEquals(pathIterator.next(), "/a");

        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertEquals(pathIterator.next(), "/");
        Assert.assertTrue(pathIterator.atParentPath());

        Assert.assertFalse(pathIterator.hasNext());
    }

    @Test
    public void testLong() {
        PathIterator pathIterator = new PathIterator("/a/b/c/d");

        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertEquals(pathIterator.next(), "/a/b/c/d");
        Assert.assertFalse(pathIterator.atParentPath());

        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertEquals(pathIterator.next(), "/a/b/c");
        Assert.assertTrue(pathIterator.atParentPath());

        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertEquals(pathIterator.next(), "/a/b");
        Assert.assertTrue(pathIterator.atParentPath());

        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertEquals(pathIterator.next(), "/a");
        Assert.assertTrue(pathIterator.atParentPath());

        Assert.assertTrue(pathIterator.hasNext());
        Assert.assertEquals(pathIterator.next(), "/");
        Assert.assertTrue(pathIterator.atParentPath());

        Assert.assertFalse(pathIterator.hasNext());
    }
}
