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
package org.apache.zookeeper.server;

import java.util.List;
import java.util.Map;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.ACL;

public interface ServerWatcher extends Watcher {

  void process(WatchedEvent event, List<ACL> znodeAcl);

  /**
   * Called instead of {@link #process(WatchedEvent, List)} when delivering an event through a
   * PERSISTENT_RECURSIVE watch whose registration root is an ancestor of the node the event is
   * about, rather than the node itself. {@code recursiveWatchRootAcls} maps EVERY node from that
   * registration root down to the event's own parent, inclusive, to its CURRENT ACL ({@code null}
   * if that node no longer exists); a conforming implementation must additionally require READ
   * against every one of them before delivering, since otherwise a restrictive ancestor's ACL --
   * or a restrictive node anywhere between the registration root and the event, not just the root
   * itself -- is bypassed entirely by recursive watch delivery. The plain {@code znodeAcl} check
   * alone only ever covers the node the event is about; checking the registration root alone
   * would still let an intermediate node's name leak (e.g. root readable, an intermediate segment
   * not readable, the event's own node readable: without the intermediate node's own check, its
   * existence as a path segment would still be disclosed). The default implementation falls back
   * to the plain per-event check, for implementations (e.g. test doubles) that don't carry real
   * ACL semantics.
   */
  default void process(WatchedEvent event, List<ACL> znodeAcl, Map<String, List<ACL>> recursiveWatchRootAcls) {
    process(event, znodeAcl);
  }

}
