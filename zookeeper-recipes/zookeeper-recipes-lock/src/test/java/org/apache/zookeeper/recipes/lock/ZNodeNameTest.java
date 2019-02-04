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


import org.junit.Assert;
import org.junit.Test;

import java.util.SortedSet;
import java.util.TreeSet;

/**
 * test for znodenames
 */
public class ZNodeNameTest {
    @Test
    public void testOrderWithSamePrefix() throws Exception {
        String[] names = { "x-3", "x-5", "x-11", "x-1" };
        String[] expected = { "x-1", "x-3", "x-5", "x-11" };
        assertOrderedNodeNames(names, expected);
    }
    @Test
    public void testOrderWithDifferentPrefixes() throws Exception {
        String[] names = { "r-3", "r-2", "r-1", "w-2", "w-1" };
        String[] expected = { "r-1", "w-1", "r-2", "w-2", "r-3" };
        assertOrderedNodeNames(names, expected);
    }
    @Test
    public void testOrderWithDifferentPrefixIncludingSessionId() throws Exception {
        String[] names = { "x-242681582799028564-0000000002", "x-170623981976748329-0000000003", "x-98566387950223723-0000000001" };
        String[] expected = { "x-98566387950223723-0000000001", "x-242681582799028564-0000000002", "x-170623981976748329-0000000003" };
        assertOrderedNodeNames(names, expected);
    }
    @Test
    public void testOrderWithExtraPrefixes() throws Exception {
        String[] names = { "r-1-3-2", "r-2-2-1", "r-3-1-3" };
        String[] expected = { "r-2-2-1", "r-1-3-2", "r-3-1-3" };
        assertOrderedNodeNames(names, expected);
    }

    protected void assertOrderedNodeNames(String[] names, String[] expected) {
        int size = names.length;
        SortedSet<ZNodeName> nodeNames = new TreeSet<ZNodeName>();
        for (String name : names) {
            nodeNames.add(new ZNodeName(name));
        }
        Assert.assertEquals("The SortedSet does not have the expected size!", nodeNames.size(), expected.length);

        int index = 0;
        for (ZNodeName nodeName : nodeNames) {
            String name = nodeName.getName();
            Assert.assertEquals("Node " + index, expected[index++], name);
        }
    }

}
