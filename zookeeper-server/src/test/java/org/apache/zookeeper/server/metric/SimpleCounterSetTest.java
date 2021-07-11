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

package org.apache.zookeeper.server.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.api.Test;


public class SimpleCounterSetTest extends ZKTestCase {
    @Test
    public void testValues() {
        final SimpleCounterSet simpleCounterSet = createSimpleCounterSetAddData("test1");
        final Map<String, Object> values = simpleCounterSet.values();

        assertEquals(2, values.size());
        assertEquals(30L , values.get("key1_test1"));
        assertEquals(70L , values.get("key2_test1"));
    }

    @Test
    public void testReset() {
        final SimpleCounterSet simpleCounterSet = createSimpleCounterSetAddData("test2");
        simpleCounterSet.reset();

        final Map<String, Object> values = simpleCounterSet.values();

        assertEquals(2, values.size());
        assertEquals(0L , values.get("key1_test2"));
        assertEquals(0L , values.get("key2_test2"));
    }

    private SimpleCounterSet createSimpleCounterSetAddData(final String name) {
        final SimpleCounterSet simpleCounterSet = new SimpleCounterSet(name);

        simpleCounterSet.add("key1", 10);
        simpleCounterSet.add("key1", 20);

        simpleCounterSet.add("key2", 30);
        simpleCounterSet.add("key2", 40);

        return simpleCounterSet;
    }
}
