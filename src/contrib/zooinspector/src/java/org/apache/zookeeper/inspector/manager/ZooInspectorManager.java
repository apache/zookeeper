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

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.swing.JComboBox;
import javax.swing.JTextField;

/**
 * A Manager for all interactions between the application and the Zookeeper
 * instance
 */
public interface ZooInspectorManager extends ZooInspectorNodeManager,
        ZooInspectorNodeTreeManager {

    /**
     * @param connectionProps
     * @return true if successfully connected
     */
    public boolean connect(Properties connectionProps);

    /**
     * @return true if successfully disconnected
     */
    public boolean disconnect();

    /**
     * @return a {@link Pair} containing the following:
     *         <ul>
     *         <li>a {@link Map} of property keys to list of possible values. If
     *         the list size is 1 the value is taken to be the default value for
     *         a {@link JTextField}. If the list size is greater than 1, the
     *         values are taken to be the possible options to show in a
     *         {@link JComboBox} with the first selected as default.</li>
     *         <li>a {@link Map} of property keys to the label to show on the UI
     *         </li>
     *         <ul>
     * 
     */
    public Pair<Map<String, List<String>>, Map<String, String>> getConnectionPropertiesTemplate();

    /**
     * @param selectedNodes
     *            - the nodes to add the watcher to
     * @param nodeListener
     *            - the node listener for this watcher
     */
    public void addWatchers(Collection<String> selectedNodes,
            NodeListener nodeListener);

    /**
     * @param selectedNodes
     *            - the nodes to remove the watchers from
     */
    public void removeWatchers(Collection<String> selectedNodes);

    /**
     * @param selectedFile
     *            - the file to load which contains the node viewers
     *            configuration
     * @return nodeViewers - the class names of the node viewers from the
     *         configuration
     * @throws IOException
     *             - if the configuration file cannot be loaded
     */
    public List<String> loadNodeViewersFile(File selectedFile)
            throws IOException;

    /**
     * @param selectedFile
     *            - the file to save the configuration to
     * @param nodeViewersClassNames
     *            - the class names of the node viewers
     * @throws IOException
     *             - if the configuration file cannot be saved
     */
    public void saveNodeViewersFile(File selectedFile,
            List<String> nodeViewersClassNames) throws IOException;

    /**
     * @param nodeViewersClassNames
     *            - the class names of the node viewers
     * @throws IOException
     *             - if the default configuration file cannot be loaded
     */
    public void setDefaultNodeViewerConfiguration(
            List<String> nodeViewersClassNames) throws IOException;

    /**
     * @return nodeViewers - the class names of the node viewers from the
     *         configuration
     * @throws IOException
     *             - if the default configuration file cannot be loaded
     */
    List<String> getDefaultNodeViewerConfiguration() throws IOException;

    /**
     * @param connectionProps
     *            - the connection properties last used to connect to the
     *            zookeeeper instance
     */
    public void setLastConnectionProps(Properties connectionProps);

    /**
     * @return last connection Properties - the connection properties last used
     *         to connect to the zookeeeper instance
     */
    public Properties getLastConnectionProps();

    /**
     * @param props
     *            - the properties to use as the default connection settings
     * @throws IOException
     *             - if the default configuration file cannot be saved
     */
    public void saveDefaultConnectionFile(Properties props) throws IOException;

}
