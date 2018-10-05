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
package org.apache.zookeeper.server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.zookeeper.ZKTestCase;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class WatchesReportTest extends ZKTestCase {
    private Map<Long, Set<String>> m;
    private WatchesReport r;
    @Before public void setUp() {
        m = new HashMap<Long, Set<String>>();
        Set<String> s = new HashSet<String>();
        s.add("path1a");
        s.add("path1b");
        m.put(1L, s);
        s = new HashSet<String>();
        s.add("path2a");
        m.put(2L, s);
        r = new WatchesReport(m);
    }
    @Test public void testHasPaths() {
        assertTrue(r.hasPaths(1L));
        assertTrue(r.hasPaths(2L));
        assertFalse(r.hasPaths(3L));
    }
    @Test public void testGetPaths() {
        Set<String> s = r.getPaths(1L);
        assertEquals(2, s.size());
        assertTrue(s.contains("path1a"));
        assertTrue(s.contains("path1b"));
        s = r.getPaths(2L);
        assertEquals(1, s.size());
        assertTrue(s.contains("path2a"));
        assertNull(r.getPaths(3L));
    }
    @Test public void testToMap() {
        assertEquals(m, r.toMap());
    }
}
