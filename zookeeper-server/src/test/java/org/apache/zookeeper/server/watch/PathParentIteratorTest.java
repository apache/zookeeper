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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

public class PathParentIteratorTest {
    @Test
    public void testRoot() {
        PathParentIterator pathParentIterator = PathParentIterator.forAll("/");
        assertTrue(pathParentIterator.hasNext());
        assertFalse(pathParentIterator.atParentPath());
        assertEquals(pathParentIterator.next(), "/");
        assertFalse(pathParentIterator.hasNext());
    }

    @Test
    public void test1Level() {
        PathParentIterator pathParentIterator = PathParentIterator.forAll("/a");
        assertTrue(pathParentIterator.hasNext());
        assertFalse(pathParentIterator.atParentPath());
        assertEquals(pathParentIterator.next(), "/a");

        assertTrue(pathParentIterator.hasNext());
        assertEquals(pathParentIterator.next(), "/");
        assertTrue(pathParentIterator.atParentPath());

        assertFalse(pathParentIterator.hasNext());
    }

    @Test
    public void testLong() {
        PathParentIterator pathParentIterator = PathParentIterator.forAll("/a/b/c/d");

        assertTrue(pathParentIterator.hasNext());
        assertEquals(pathParentIterator.next(), "/a/b/c/d");
        assertFalse(pathParentIterator.atParentPath());

        assertTrue(pathParentIterator.hasNext());
        assertEquals(pathParentIterator.next(), "/a/b/c");
        assertTrue(pathParentIterator.atParentPath());

        assertTrue(pathParentIterator.hasNext());
        assertEquals(pathParentIterator.next(), "/a/b");
        assertTrue(pathParentIterator.atParentPath());

        assertTrue(pathParentIterator.hasNext());
        assertEquals(pathParentIterator.next(), "/a");
        assertTrue(pathParentIterator.atParentPath());

        assertTrue(pathParentIterator.hasNext());
        assertEquals(pathParentIterator.next(), "/");
        assertTrue(pathParentIterator.atParentPath());

        assertFalse(pathParentIterator.hasNext());
    }

    @Test
    public void testForPathOnly() {
        PathParentIterator pathParentIterator = PathParentIterator.forPathOnly("/a/b/c/d");
        assertTrue(pathParentIterator.hasNext());
        assertEquals(pathParentIterator.next(), "/a/b/c/d");
        assertFalse(pathParentIterator.atParentPath());

        assertFalse(pathParentIterator.hasNext());
    }
}