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

package org.apache.zookeeper.server.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Set;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ReferenceCountedACLCache;

public class AssertEqual {

  public static void assertDBEqual(DataTree expected, DataTree actual) {
    // lastProcessedZxid
    assertEquals(expected.lastProcessedZxid, actual.lastProcessedZxid);
    // data nodes
    assertEquals(expected.getNodeCount(), actual.getNodeCount());
    assertDataTreeEqual(expected, actual, "/");
    // ACL
    assertCachesEqual(expected.getReferenceCountedAclCache(), actual.getReferenceCountedAclCache());
  }

  public static void assertDataTreeEqual(DataTree expected, DataTree actual, String name) {
    DataNode nodeExpected = expected.getNode(name);
    DataNode nodeActual = actual.getNode(name);
    Set<String> childrenExpected;
    Set<String> childrenActual;
    assertStatEqual(nodeExpected.stat, nodeActual.stat);
    assertEquals(new String(nodeActual.getData()), new String(nodeExpected.getData()));
    childrenActual = nodeActual.getChildren();
    childrenExpected = nodeExpected.getChildren();
    for (String child : childrenActual) {
      assertTrue(childrenExpected.contains(child));
      String childName = name + (name.equals("/") ? "" : "/") + child;
      assertDataTreeEqual(expected, actual, childName);
    }
  }

  public static void assertStatEqual(StatPersisted expected, StatPersisted actual) {
    assertEquals(expected.getCzxid(), actual.getCzxid());
    assertEquals(expected.getCtime(), actual.getCtime());
    assertEquals(expected.getMzxid(), actual.getMzxid());
    assertEquals(expected.getMtime(), actual.getMtime());
    assertEquals(expected.getPzxid(), actual.getPzxid());
    assertEquals(expected.getCversion(), actual.getCversion());
    assertEquals(expected.getVersion(), actual.getVersion());
    assertEquals(expected.getAversion(), actual.getAversion());
    assertEquals(expected.getEphemeralOwner(), actual.getEphemeralOwner());
  }

  public static void assertCachesEqual(ReferenceCountedACLCache expected, ReferenceCountedACLCache actual) {
    assertEquals(expected.getAclKeyMap(), actual.getAclKeyMap());
    assertEquals(expected.getLongKeyMap(), actual.getLongKeyMap());
    assertEquals(expected.getReferenceCounter(), actual.getReferenceCounter());
  }
}
