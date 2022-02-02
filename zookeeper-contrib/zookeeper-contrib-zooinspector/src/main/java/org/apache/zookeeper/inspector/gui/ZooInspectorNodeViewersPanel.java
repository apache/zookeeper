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
package org.apache.zookeeper.inspector.gui;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.apache.zookeeper.inspector.gui.nodeviewer.NodeSelectionListener;
import org.apache.zookeeper.inspector.gui.nodeviewer.ZooInspectorNodeViewer;
import org.apache.zookeeper.inspector.manager.ZooInspectorManager;
import org.apache.zookeeper.inspector.manager.ZooInspectorNodeManager;

/**
 * This is the {@link JPanel} which contains the {@link ZooInspectorNodeViewer}s
 */
public class ZooInspectorNodeViewersPanel extends JPanel implements ChangeListener, NodeSelectionListener {

    private final List<ZooInspectorNodeViewer> nodeViewers = new ArrayList<>();
    private final List<Boolean> needsReload = new ArrayList<Boolean>();
    private final JTabbedPane tabbedPane;
    private final List<String> selectedNodes = new ArrayList<String>();
    private final ZooInspectorNodeManager zooInspectorManager;

    /**
     * @param zooInspectorManager
     *            - the {@link ZooInspectorManager} for the application
     * @param nodeViewers
     *            - the {@link ZooInspectorNodeViewer}s to show
     */
    public ZooInspectorNodeViewersPanel(
            ZooInspectorNodeManager zooInspectorManager,
            List<ZooInspectorNodeViewer> nodeViewers) {
        this.zooInspectorManager = zooInspectorManager;
        this.setLayout(new BorderLayout());
        tabbedPane = new JTabbedPane(JTabbedPane.TOP,
                JTabbedPane.WRAP_TAB_LAYOUT);
        setNodeViewers(nodeViewers);
        tabbedPane.addChangeListener(this);
        this.add(tabbedPane, BorderLayout.CENTER);
        reloadSelectedViewer();
    }

    /**
     * @param nodeViewers
     *            - the {@link ZooInspectorNodeViewer}s to show
     */
    public void setNodeViewers(List<ZooInspectorNodeViewer> nodeViewers) {
        this.nodeViewers.clear();
        this.nodeViewers.addAll(nodeViewers);
        needsReload.clear();
        tabbedPane.removeAll();
        for (ZooInspectorNodeViewer nodeViewer : nodeViewers) {
            nodeViewer.setZooInspectorManager(zooInspectorManager);
            needsReload.add(true);
            tabbedPane.add(nodeViewer.getTitle(), nodeViewer);
        }
        this.revalidate();
        this.repaint();
    }

    private void reloadSelectedViewer() {
        int index = this.tabbedPane.getSelectedIndex();
        if (index != -1 && this.needsReload.get(index)) {
            ZooInspectorNodeViewer viewer = this.nodeViewers.get(index);
            viewer.nodeSelectionChanged(selectedNodes);
            this.needsReload.set(index, false);
        }
    }

    /**
     * @see org.apache.zookeeper.inspector.gui.nodeviewer.NodeSelectionListener#nodePathSelected(String)
     */
    @Override
    public void nodePathSelected(String nodePath) {
        selectedNodes.clear();
        selectedNodes.add(nodePath);
        for (int i = 0; i < needsReload.size(); i++) {
            this.needsReload.set(i, true);
        }
        reloadSelectedViewer();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * javax.swing.event.ChangeListener#stateChanged(javax.swing.event.ChangeEvent
     * )
     */
    public void stateChanged(ChangeEvent e) {
        reloadSelectedViewer();
    }
}
