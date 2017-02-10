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
package org.apache.zookeeper.server;

import static org.junit.Assert.*;

import java.util.Set;

import org.junit.Test;

public class DataNodeTest {

    @Test
    public void testGetChildrenShouldReturnEmptySetWhenThereAreNoChidren() {
        // create DataNode and call getChildren
        DataNode dataNode = new DataNode();
        Set<String> children = dataNode.getChildren();
        assertNotNull(children);
        assertEquals(0, children.size());

        // add child,remove child and then call getChildren
        String child = "child";
        dataNode.addChild(child);
        dataNode.removeChild(child);
        children = dataNode.getChildren();
        assertNotNull(children);
        assertEquals(0, children.size());

        // Returned empty set must not be modifiable
        children = dataNode.getChildren();
        try {
            children.add("new child");
            fail("UnsupportedOperationException is expected");
        } catch (UnsupportedOperationException e) {
            // do nothing
        }
    }

    @Test
    public void testGetChildrenReturnsImmutableEmptySet() {
        DataNode dataNode = new DataNode();
        Set<String> children = dataNode.getChildren();
        try {
            children.add("new child");
            fail("UnsupportedOperationException is expected");
        } catch (UnsupportedOperationException e) {
            // do nothing
        }
    }
}
