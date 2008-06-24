/**
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;

/**
 * Application must implement this interface and register its instance with
 * the {@link ObserverManager}.
 */
public interface DataTreeObserver {
    /**
     * A new znode has been created.
     * @param dt the data tree instance
     * @param node the new znode
     */
    public void onAdd(DataTree dt,DataNode node);
    /**
     * A znode has been deleted.
     * @param dt the data tree instance
     * @param node the deleted znode
     */
    public void onDelete(DataTree dt,DataNode node);
    /**
     * A znode value has changed.
     * @param dt the data tree instance
     * @param node the znode whose value's changed 
     */
    public void onUpdate(DataTree dt, DataNode node);
    /**
     * A znode's ACL has been modified. 
     * @param dt the data tree instance
     * @param node the znode whose ACL has changed
     */
    public void onUpdateACL(DataTree dt, DataNode node);
}
