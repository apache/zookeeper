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
package org.apache.zookeeper.inspector.gui.actions;

import org.apache.zookeeper.inspector.gui.ZooInspectorPanel;
import org.apache.zookeeper.inspector.gui.ZooInspectorTreeViewer;
import org.apache.zookeeper.inspector.manager.ZooInspectorManager;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.awt.event.KeyEvent;

public class AddNodeAction extends AbstractAction {

    private JPanel panel;
    private ZooInspectorTreeViewer treeViewer;
    private ZooInspectorManager zooInspectorManager;

    public AddNodeAction(JPanel parentPanel,
                         ZooInspectorTreeViewer treeViewer,
                         ZooInspectorManager zooInspectorManager) {
        this.panel = parentPanel;
        this.treeViewer = treeViewer;
        this.zooInspectorManager = zooInspectorManager;
    }

    public void actionPerformed(ActionEvent e) {
        final List<String> selectedNodes = treeViewer
                .getSelectedNodes();
        if (selectedNodes.size() == 1) {
            final String nodeName = JOptionPane.showInputDialog(
                    panel,
                    "Please Enter a name for the new node",
                    "Create Node", JOptionPane.INFORMATION_MESSAGE);
            if (nodeName != null && nodeName.length() > 0) {
                SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {

                    @Override
                    protected Boolean doInBackground() throws Exception {
                        return zooInspectorManager
                                .createNode(selectedNodes.get(0),
                                        nodeName);
                    }

                    @Override
                    protected void done() {
                        treeViewer.refreshView();
                    }
                };
                worker.execute();
            }
        } else {
            JOptionPane.showMessageDialog(panel,
                    "Please select 1 parent node for the new node.");
        }
    }
}
