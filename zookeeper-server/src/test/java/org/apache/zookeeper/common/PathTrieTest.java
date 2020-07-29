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

package org.apache.zookeeper.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PathTrieTest {

    private PathTrie pathTrie;

    @BeforeEach
    public void before() {
        this.pathTrie = new PathTrie();
    }

    @Test
    public void addNullPath() {
        assertThrows(NullPointerException.class, () -> {
            this.pathTrie.addPath(null);
        });
    }

    @Test
    public void addIllegalPath() {
        assertThrows(IllegalArgumentException.class, () -> {
            this.pathTrie.addPath("");
        });
    }

    @Test
    public void addPathToRoot() {
        this.pathTrie.addPath("node1");
        assertTrue(this.pathTrie.existsNode("/node1"));
    }

    @Test
    public void addPathToRootLeaves() {
        this.pathTrie.addPath("node1");
        this.pathTrie.addPath("node1/node2");
        this.pathTrie.addPath("node1/node3");
        assertTrue(this.pathTrie.existsNode("/node1"));
        assertTrue(this.pathTrie.existsNode("/node1/node2"));
        assertTrue(this.pathTrie.existsNode("/node1/node3"));
    }

    @Test
    public void deleteNullPath() {
        assertThrows(NullPointerException.class, () -> {
            this.pathTrie.deletePath(null);
        });
    }

    @Test
    public void deleteIllegalPath() {
        assertThrows(IllegalArgumentException.class, () -> {
            this.pathTrie.deletePath("");
        });
    }

    @Test
    public void deletePathFromRoot() {
        this.pathTrie.addPath("node1");
        this.pathTrie.deletePath("node1");
        assertFalse(this.pathTrie.existsNode("/node1"));
    }

    @Test
    public void deletePathFromRootLeaves() {
        this.pathTrie.addPath("node1");
        this.pathTrie.addPath("node1/node2");
        this.pathTrie.addPath("node1/node3");

        this.pathTrie.deletePath("node1/node3");

        assertTrue(this.pathTrie.existsNode("/node1"));
        assertTrue(this.pathTrie.existsNode("/node1/node2"));
        assertFalse(this.pathTrie.existsNode("/node1/node3"));

        this.pathTrie.deletePath("node1/node2");

        assertTrue(this.pathTrie.existsNode("/node1"));
        assertFalse(this.pathTrie.existsNode("/node1/node2"));

        this.pathTrie.deletePath("node1");
        assertFalse(this.pathTrie.existsNode("/node1"));
    }

    @Test
    public void deletePathDoesNotExist() {
        this.pathTrie.addPath("node1");
        this.pathTrie.addPath("node1/node2");

        this.pathTrie.deletePath("node1/node3");

        assertTrue(this.pathTrie.existsNode("/node1"));
        assertTrue(this.pathTrie.existsNode("/node1/node2"));
    }

    @Test
    public void deleteRootPath() {
        this.pathTrie.addPath("node1");
        this.pathTrie.addPath("node1/node2");
        this.pathTrie.addPath("node1/node3");

        // Nodes are only removed from the trie if they are a leaf node
        this.pathTrie.deletePath("node1");

        assertTrue(this.pathTrie.existsNode("/node1"));
        assertTrue(this.pathTrie.existsNode("/node1/node2"));
        assertTrue(this.pathTrie.existsNode("/node1/node3"));
    }

    @Test
    public void findMaxPrefixNullPath() {
        assertThrows(NullPointerException.class, () -> {
            this.pathTrie.findMaxPrefix(null);
        });
    }

    @Test
    public void findMaxPrefixRootPath() {
        assertEquals("/", this.pathTrie.findMaxPrefix("/"));
    }

    @Test
    public void findMaxPrefixChildren() {
        this.pathTrie.addPath("node1");
        this.pathTrie.addPath("node1/node2");
        this.pathTrie.addPath("node1/node3");

        assertEquals("/node1", this.pathTrie.findMaxPrefix("/node1"));
        assertEquals("/node1/node2", this.pathTrie.findMaxPrefix("/node1/node2"));
        assertEquals("/node1/node3", this.pathTrie.findMaxPrefix("/node1/node3"));
    }

    @Test
    public void findMaxPrefixChildrenPrefix() {
        this.pathTrie.addPath("node1");

        assertEquals("/node1", this.pathTrie.findMaxPrefix("/node1/node2"));
        assertEquals("/node1", this.pathTrie.findMaxPrefix("/node1/node3"));
    }

}
