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

package org.apache.zookeeper.server.quorum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

public class BufferStatsTest {

    @Test
    public void testSetProposalSizeSetMinMax() {
        BufferStats stats = new BufferStats();
        assertEquals(-1, stats.getLastBufferSize());
        assertEquals(-1, stats.getMinBufferSize());
        assertEquals(-1, stats.getMaxBufferSize());
        stats.setLastBufferSize(10);
        assertEquals(10, stats.getLastBufferSize());
        assertEquals(10, stats.getMinBufferSize());
        assertEquals(10, stats.getMaxBufferSize());
        stats.setLastBufferSize(20);
        assertEquals(20, stats.getLastBufferSize());
        assertEquals(10, stats.getMinBufferSize());
        assertEquals(20, stats.getMaxBufferSize());
        stats.setLastBufferSize(5);
        assertEquals(5, stats.getLastBufferSize());
        assertEquals(5, stats.getMinBufferSize());
        assertEquals(20, stats.getMaxBufferSize());
    }

    @Test
    public void testReset() {
        BufferStats stats = new BufferStats();
        stats.setLastBufferSize(10);
        assertEquals(10, stats.getLastBufferSize());
        assertEquals(10, stats.getMinBufferSize());
        assertEquals(10, stats.getMaxBufferSize());
        stats.reset();
        assertEquals(-1, stats.getLastBufferSize());
        assertEquals(-1, stats.getMinBufferSize());
        assertEquals(-1, stats.getMaxBufferSize());
    }

}
