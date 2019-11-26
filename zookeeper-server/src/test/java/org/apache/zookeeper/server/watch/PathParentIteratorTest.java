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

package org.apache.zookeeper.server.watch;

import org.junit.Assert;
import org.junit.Test;

public class PathParentIteratorTest {
    @Test
    public void testRoot() {
        PathParentIterator pathParentIterator = PathParentIterator.forAll("/");
        Assert.assertTrue(pathParentIterator.hasNext());
        Assert.assertFalse(pathParentIterator.atParentPath());
        Assert.assertEquals(pathParentIterator.next(), "/");
        Assert.assertFalse(pathParentIterator.hasNext());
    }

    @Test
    public void test1Level() {
        PathParentIterator pathParentIterator = PathParentIterator.forAll("/a");
        Assert.assertTrue(pathParentIterator.hasNext());
        Assert.assertFalse(pathParentIterator.atParentPath());
        Assert.assertEquals(pathParentIterator.next(), "/a");

        Assert.assertTrue(pathParentIterator.hasNext());
        Assert.assertEquals(pathParentIterator.next(), "/");
        Assert.assertTrue(pathParentIterator.atParentPath());

        Assert.assertFalse(pathParentIterator.hasNext());
    }

    @Test
    public void testLong() {
        PathParentIterator pathParentIterator = PathParentIterator.forAll("/a/b/c/d");

        Assert.assertTrue(pathParentIterator.hasNext());
        Assert.assertEquals(pathParentIterator.next(), "/a/b/c/d");
        Assert.assertFalse(pathParentIterator.atParentPath());

        Assert.assertTrue(pathParentIterator.hasNext());
        Assert.assertEquals(pathParentIterator.next(), "/a/b/c");
        Assert.assertTrue(pathParentIterator.atParentPath());

        Assert.assertTrue(pathParentIterator.hasNext());
        Assert.assertEquals(pathParentIterator.next(), "/a/b");
        Assert.assertTrue(pathParentIterator.atParentPath());

        Assert.assertTrue(pathParentIterator.hasNext());
        Assert.assertEquals(pathParentIterator.next(), "/a");
        Assert.assertTrue(pathParentIterator.atParentPath());

        Assert.assertTrue(pathParentIterator.hasNext());
        Assert.assertEquals(pathParentIterator.next(), "/");
        Assert.assertTrue(pathParentIterator.atParentPath());

        Assert.assertFalse(pathParentIterator.hasNext());
    }

    @Test
    public void testForPathOnly() {
        PathParentIterator pathParentIterator = PathParentIterator.forPathOnly("/a/b/c/d");
        Assert.assertTrue(pathParentIterator.hasNext());
        Assert.assertEquals(pathParentIterator.next(), "/a/b/c/d");
        Assert.assertFalse(pathParentIterator.atParentPath());

        Assert.assertFalse(pathParentIterator.hasNext());
    }
}