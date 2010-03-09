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
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.TreePath;

import org.apache.zookeeper.inspector.gui.nodeviewer.ZooInspectorNodeViewer;
import org.apache.zookeeper.inspector.manager.ZooInspectorManager;
import org.apache.zookeeper.inspector.manager.ZooInspectorNodeManager;

/**
 * This is the {@link JPanel} which contains the {@link ZooInspectorNodeViewer}s
 */
public class ZooInspectorNodeViewersPanel extends JPanel implements
        TreeSelectionListener, ChangeListener {

    private final List<ZooInspectorNodeViewer> nodeVeiwers = new ArrayList<ZooInspectorNodeViewer>();
    private final List<Boolean> needsReload = new ArrayList<Boolean>();
    private final JTabbedPane tabbedPane;
    private final List<String> selectedNodes = new ArrayList<String>();
    private final ZooInspectorNodeManager zooInspectorManager;

    /**
     * @param zooInspectorManager
     *            - the {@link ZooInspectorManager} for the application
     * @param nodeVeiwers
     *            - the {@link ZooInspectorNodeViewer}s to show
     */
    public ZooInspectorNodeViewersPanel(
            ZooInspectorNodeManager zooInspectorManager,
            List<ZooInspectorNodeViewer> nodeVeiwers) {
        this.zooInspectorManager = zooInspectorManager;
        this.setLayout(new BorderLayout());
        tabbedPane = new JTabbedPane(JTabbedPane.TOP,
                JTabbedPane.WRAP_TAB_LAYOUT);
        setNodeViewers(nodeVeiwers);
        tabbedPane.addChangeListener(this);
        this.add(tabbedPane, BorderLayout.CENTER);
        reloadSelectedViewer();
    }

    /**
     * @param nodeViewers
     *            - the {@link ZooInspectorNodeViewer}s to show
     */
    public void setNodeViewers(List<ZooInspectorNodeViewer> nodeViewers) {
        this.nodeVeiwers.clear();
        this.nodeVeiwers.addAll(nodeViewers);
        needsReload.clear();
        tabbedPane.removeAll();
        for (ZooInspectorNodeViewer nodeViewer : nodeVeiwers) {
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
            ZooInspectorNodeViewer viewer = this.nodeVeiwers.get(index);
            viewer.nodeSelectionChanged(selectedNodes);
            this.needsReload.set(index, false);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * javax.swing.event.TreeSelectionListener#valueChanged(javax.swing.event
     * .TreeSelectionEvent)
     */
    public void valueChanged(TreeSelectionEvent e) {
        TreePath[] paths = e.getPaths();
        selectedNodes.clear();
        for (TreePath path : paths) {
            boolean appended = false;
            StringBuilder sb = new StringBuilder();
            Object[] pathArray = path.getPath();
            for (Object o : pathArray) {
                if (o != null) {
                    String nodeName = o.toString();
                    if (nodeName != null) {
                        if (nodeName.length() > 0) {
                            appended = true;
                            sb.append("/"); //$NON-NLS-1$
                            sb.append(o.toString());
                        }
                    }
                }
            }
            if (appended) {
                selectedNodes.add(sb.toString());
            }
        }
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
