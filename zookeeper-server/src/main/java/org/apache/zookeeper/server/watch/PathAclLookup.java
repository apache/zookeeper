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

package org.apache.zookeeper.server.watch;

import java.util.List;
import org.apache.zookeeper.data.ACL;

/**
 * Resolves the current ACL of an arbitrary znode path. Used by
 * {@link IWatchManager#setPathAclLookup} so that a watch manager supporting
 * {@link WatcherMode#PERSISTENT_RECURSIVE} watches can, at event-delivery time, additionally
 * require READ on a recursive watch's own registration root -- not just on the node the event
 * happens to be about -- before notifying it. Looking this up live at delivery time (rather than
 * once at registration time) means a later {@code setACL} that tightens or loosens the
 * registration root is honored immediately, for every event delivered after it.
 */
@FunctionalInterface
public interface PathAclLookup {

    /**
     * @param path the znode path to resolve
     * @return the path's current ACL, or {@code null} if no node exists at that path
     */
    List<ACL> getACL(String path);

}
