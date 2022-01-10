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

import java.util.List;
import java.util.Map;

/**
 * A Manager for all read only interactions between the application and a node
 * in a Zookeeper instance
 */
public interface ZooInspectorReadOnlyManager {

    /**
     * @param nodePath
     *            - the path to the node whose data is to be retrieved
     * @return the data for the node
     */
    public abstract String getData(String nodePath);

    /**
     * @param nodePath
     *            - the path to the node whose metadata is to be retrieved
     * @return the metaData for the node
     */
    public abstract Map<String, String> getNodeMeta(String nodePath);

    /**
     * @param nodePath
     *            - the path to the node whose ACLs are to be retrieved
     * @return the ACLs set on the node
     */
    public abstract List<Map<String, String>> getACLs(String nodePath);

    /**
     * @param nodePath
     *            - the path to the node to parent node
     * @return the number of children of the node
     */
    public abstract int getNumChildren(String nodePath);

    /**
     * @param nodePath
     *            - the path to the node whose children to retrieve
     * @return a {@link List} of the children of the node
     */
    public abstract List<String> getChildren(String nodePath);
}
