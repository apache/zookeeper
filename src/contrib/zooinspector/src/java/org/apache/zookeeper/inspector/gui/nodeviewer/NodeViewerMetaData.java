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
package org.apache.zookeeper.inspector.gui.nodeviewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.inspector.logger.LoggerFactory;
import org.apache.zookeeper.inspector.manager.ZooInspectorNodeManager;

/**
 * A node viewer for displaying the meta data for the currently selected node.
 * The meta data is essentially the information from the {@link Stat} for the
 * node
 */
public class NodeViewerMetaData extends ZooInspectorNodeViewer {
    private ZooInspectorNodeManager zooInspectorManager;
    private final JPanel metaDataPanel;
    private String selectedNode;

    /**
	 * 
	 */
    public NodeViewerMetaData() {
        this.setLayout(new BorderLayout());
        this.metaDataPanel = new JPanel();
        this.metaDataPanel.setBackground(Color.WHITE);
        JScrollPane scroller = new JScrollPane(this.metaDataPanel);
        this.add(scroller, BorderLayout.CENTER);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.zookeeper.inspector.gui.nodeviewer.ZooInspectorNodeViewer#
     * getTitle()
     */
    @Override
    public String getTitle() {
        return "Node Metadata";
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.zookeeper.inspector.gui.nodeviewer.ZooInspectorNodeViewer#
     * nodeSelectionChanged(java.util.Set)
     */
    @Override
    public void nodeSelectionChanged(List<String> selectedNodes) {
        this.metaDataPanel.removeAll();
        if (selectedNodes.size() > 0) {
            this.selectedNode = selectedNodes.get(0);
            SwingWorker<Map<String, String>, Void> worker = new SwingWorker<Map<String, String>, Void>() {

                @Override
                protected Map<String, String> doInBackground() throws Exception {
                    return NodeViewerMetaData.this.zooInspectorManager
                            .getNodeMeta(NodeViewerMetaData.this.selectedNode);
                }

                @Override
                protected void done() {
                    Map<String, String> data = null;
                    try {
                        data = get();
                    } catch (InterruptedException e) {
                        data = new HashMap<String, String>();
                        LoggerFactory.getLogger().error(
                                "Error retrieving meta data for node: "
                                        + NodeViewerMetaData.this.selectedNode,
                                e);
                    } catch (ExecutionException e) {
                        data = new HashMap<String, String>();
                        LoggerFactory.getLogger().error(
                                "Error retrieving meta data for node: "
                                        + NodeViewerMetaData.this.selectedNode,
                                e);
                    }
                    NodeViewerMetaData.this.metaDataPanel
                            .setLayout(new GridBagLayout());
                    JPanel infoPanel = new JPanel();
                    infoPanel.setBackground(Color.WHITE);
                    infoPanel.setLayout(new GridBagLayout());
                    int i = 0;
                    int rowPos = 0;
                    for (Map.Entry<String, String> entry : data.entrySet()) {
                        rowPos = 2 * i + 1;
                        JLabel label = new JLabel(entry.getKey());
                        JTextField text = new JTextField(entry.getValue());
                        text.setEditable(false);
                        GridBagConstraints c1 = new GridBagConstraints();
                        c1.gridx = 0;
                        c1.gridy = rowPos;
                        c1.gridwidth = 1;
                        c1.gridheight = 1;
                        c1.weightx = 0;
                        c1.weighty = 0;
                        c1.anchor = GridBagConstraints.WEST;
                        c1.fill = GridBagConstraints.HORIZONTAL;
                        c1.insets = new Insets(5, 5, 5, 5);
                        c1.ipadx = 0;
                        c1.ipady = 0;
                        infoPanel.add(label, c1);
                        GridBagConstraints c2 = new GridBagConstraints();
                        c2.gridx = 2;
                        c2.gridy = rowPos;
                        c2.gridwidth = 1;
                        c2.gridheight = 1;
                        c2.weightx = 0;
                        c2.weighty = 0;
                        c2.anchor = GridBagConstraints.WEST;
                        c2.fill = GridBagConstraints.HORIZONTAL;
                        c2.insets = new Insets(5, 5, 5, 5);
                        c2.ipadx = 0;
                        c2.ipady = 0;
                        infoPanel.add(text, c2);
                        i++;
                    }
                    GridBagConstraints c = new GridBagConstraints();
                    c.gridx = 1;
                    c.gridy = rowPos;
                    c.gridwidth = 1;
                    c.gridheight = 1;
                    c.weightx = 1;
                    c.weighty = 1;
                    c.anchor = GridBagConstraints.NORTHWEST;
                    c.fill = GridBagConstraints.NONE;
                    c.insets = new Insets(5, 5, 5, 5);
                    c.ipadx = 0;
                    c.ipady = 0;
                    NodeViewerMetaData.this.metaDataPanel.add(infoPanel, c);
                    NodeViewerMetaData.this.metaDataPanel.revalidate();
                    NodeViewerMetaData.this.metaDataPanel.repaint();
                }
            };
            worker.execute();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.zookeeper.inspector.gui.nodeviewer.ZooInspectorNodeViewer#
     * setZooInspectorManager
     * (org.apache.zookeeper.inspector.manager.ZooInspectorNodeManager)
     */
    @Override
    public void setZooInspectorManager(
            ZooInspectorNodeManager zooInspectorManager) {
        this.zooInspectorManager = zooInspectorManager;
    }

}
