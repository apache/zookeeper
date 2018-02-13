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

package org.apache.zookeeper.server.quorum;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ProposalStatsTest {
    @Test
    public void testSetProposalSizeSetMinMax() {
        ProposalStats stats = new ProposalStats();
        assertEquals(-1, stats.getLast());
        assertEquals(-1, stats.getMin());
        assertEquals(-1, stats.getMax());
        stats.setLastProposalSize(10);
        assertEquals(10, stats.getLast());
        assertEquals(10, stats.getMin());
        assertEquals(10, stats.getMax());
        stats.setLastProposalSize(20);
        assertEquals(20, stats.getLast());
        assertEquals(10, stats.getMin());
        assertEquals(20, stats.getMax());
        stats.setLastProposalSize(5);
        assertEquals(5, stats.getLast());
        assertEquals(5, stats.getMin());
        assertEquals(20, stats.getMax());
    }

    @Test
    public void testReset() {
        ProposalStats stats = new ProposalStats();
        stats.setLastProposalSize(10);
        assertEquals(10, stats.getLast());
        assertEquals(10, stats.getMin());
        assertEquals(10, stats.getMax());
        stats.reset();
        assertEquals(-1, stats.getLast());
        assertEquals(-1, stats.getMin());
        assertEquals(-1, stats.getMax());
    }
}
