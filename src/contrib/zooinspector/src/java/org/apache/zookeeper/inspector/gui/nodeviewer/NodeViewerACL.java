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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import org.apache.zookeeper.inspector.logger.LoggerFactory;
import org.apache.zookeeper.inspector.manager.ZooInspectorNodeManager;

/**
 * A node viewer for displaying the ACLs currently applied to the selected node
 */
public class NodeViewerACL extends ZooInspectorNodeViewer {
    private ZooInspectorNodeManager zooInspectorManager;
    private final JPanel aclDataPanel;
    private String selectedNode;

    /**
	 * 
	 */
    public NodeViewerACL() {
        this.setLayout(new BorderLayout());
        this.aclDataPanel = new JPanel();
        this.aclDataPanel.setBackground(Color.WHITE);
        JScrollPane scroller = new JScrollPane(this.aclDataPanel);
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
        return "Node ACLs";
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
        this.aclDataPanel.removeAll();
        if (selectedNodes.size() > 0) {
            this.selectedNode = selectedNodes.get(0);
            SwingWorker<List<Map<String, String>>, Void> worker = new SwingWorker<List<Map<String, String>>, Void>() {

                @Override
                protected List<Map<String, String>> doInBackground()
                        throws Exception {
                    return NodeViewerACL.this.zooInspectorManager
                            .getACLs(NodeViewerACL.this.selectedNode);
                }

                @Override
                protected void done() {
                    List<Map<String, String>> acls = null;
                    try {
                        acls = get();
                    } catch (InterruptedException e) {
                        acls = new ArrayList<Map<String, String>>();
                        LoggerFactory.getLogger().error(
                                "Error retrieving ACL Information for node: "
                                        + NodeViewerACL.this.selectedNode, e);
                    } catch (ExecutionException e) {
                        acls = new ArrayList<Map<String, String>>();
                        LoggerFactory.getLogger().error(
                                "Error retrieving ACL Information for node: "
                                        + NodeViewerACL.this.selectedNode, e);
                    }
                    aclDataPanel.setLayout(new GridBagLayout());
                    int j = 0;
                    for (Map<String, String> data : acls) {
                        int rowPos = 2 * j + 1;
                        JPanel aclPanel = new JPanel();
                        aclPanel.setBorder(BorderFactory
                                .createLineBorder(Color.BLACK));
                        aclPanel.setBackground(Color.WHITE);
                        aclPanel.setLayout(new GridBagLayout());
                        int i = 0;
                        for (Map.Entry<String, String> entry : data.entrySet()) {
                            int rowPosACL = 2 * i + 1;
                            JLabel label = new JLabel(entry.getKey());
                            JTextField text = new JTextField(entry.getValue());
                            text.setEditable(false);
                            GridBagConstraints c1 = new GridBagConstraints();
                            c1.gridx = 1;
                            c1.gridy = rowPosACL;
                            c1.gridwidth = 1;
                            c1.gridheight = 1;
                            c1.weightx = 0;
                            c1.weighty = 0;
                            c1.anchor = GridBagConstraints.NORTHWEST;
                            c1.fill = GridBagConstraints.BOTH;
                            c1.insets = new Insets(5, 5, 5, 5);
                            c1.ipadx = 0;
                            c1.ipady = 0;
                            aclPanel.add(label, c1);
                            GridBagConstraints c2 = new GridBagConstraints();
                            c2.gridx = 3;
                            c2.gridy = rowPosACL;
                            c2.gridwidth = 1;
                            c2.gridheight = 1;
                            c2.weightx = 0;
                            c2.weighty = 0;
                            c2.anchor = GridBagConstraints.NORTHWEST;
                            c2.fill = GridBagConstraints.BOTH;
                            c2.insets = new Insets(5, 5, 5, 5);
                            c2.ipadx = 0;
                            c2.ipady = 0;
                            aclPanel.add(text, c2);
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
                        aclDataPanel.add(aclPanel, c);
                    }
                    NodeViewerACL.this.aclDataPanel.revalidate();
                    NodeViewerACL.this.aclDataPanel.repaint();
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
