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
package org.apache.zookeeper.inspector.manager;

/**
 * A Manager for all interactions between the application and the node tree in a
 * Zookeeper instance
 */
public interface ZooInspectorNodeTreeManager extends
        ZooInspectorReadOnlyManager {

    /**
     * @param parent
     *            - the parent node path for the node to add
     * @param nodeName
     *            - the name of the new node
     * @return true if the node was successfully created
     */
    public abstract boolean createNode(String parent, String nodeName);

    /**
     * @param nodePath
     *            - the path to the node to delete
     * @return true if the node was successfully deleted
     */
    public abstract boolean deleteNode(String nodePath);

}