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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.zookeeper.ZKTestCase;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class WatchesPathReportTest extends ZKTestCase {
    private Map<String, Set<Long>> m;
    private WatchesPathReport r;
    @Before public void setUp() {
        m = new HashMap<String, Set<Long>>();
        Set<Long> s = new HashSet<Long>();
        s.add(101L);
        s.add(102L);
        m.put("path1", s);
        s = new HashSet<Long>();
        s.add(201L);
        m.put("path2", s);
        r = new WatchesPathReport(m);
    }
    @Test public void testHasSessions() {
        assertTrue(r.hasSessions("path1"));
        assertTrue(r.hasSessions("path2"));
        assertFalse(r.hasSessions("path3"));
    }
    @Test public void testGetSessions() {
        Set<Long> s = r.getSessions("path1");
        assertEquals(2, s.size());
        assertTrue(s.contains(101L));
        assertTrue(s.contains(102L));
        s = r.getSessions("path2");
        assertEquals(1, s.size());
        assertTrue(s.contains(201L));
        assertNull(r.getSessions("path3"));
    }
    @Test public void testToMap() {
        assertEquals(m, r.toMap());
    }
}
