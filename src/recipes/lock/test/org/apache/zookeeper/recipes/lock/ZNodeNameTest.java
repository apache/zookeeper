/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.recipes.lock;

import junit.framework.TestCase;

import java.util.SortedSet;
import java.util.TreeSet;

/**
 * test for znodenames
 */
public class ZNodeNameTest extends TestCase {
    public void testOrderWithSamePrefix() throws Exception {
        String[] names = { "x-3", "x-5", "x-11", "x-1" };
        String[] expected = { "x-1", "x-3", "x-5", "x-11" };
        assertOrderedNodeNames(names, expected);
    }
    public void testOrderWithDifferentPrefixes() throws Exception {
        String[] names = { "r-3", "r-2", "r-1", "w-2", "w-1" };
        String[] expected = { "r-1", "r-2", "r-3", "w-1", "w-2" };
        assertOrderedNodeNames(names, expected);
    }

    protected void assertOrderedNodeNames(String[] names, String[] expected) {
        int size = names.length;
        assertEquals("The two arrays should be the same size!", names.length, expected.length);
        SortedSet<ZNodeName> nodeNames = new TreeSet<ZNodeName>();
        for (String name : names) {
            nodeNames.add(new ZNodeName(name));
        }

        int index = 0;
        for (ZNodeName nodeName : nodeNames) {
            String name = nodeName.getName();
            assertEquals("Node " + index, expected[index++], name);
        }
    }

}
