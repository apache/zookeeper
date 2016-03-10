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

import org.apache.zookeeper.inspector.gui.ZooInspectorTreeViewer;
import org.apache.zookeeper.inspector.manager.ZooInspectorManager;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.awt.event.KeyEvent;

public class DeleteNodeAction extends AbstractAction {

    private JPanel parentPanel;
    private ZooInspectorTreeViewer treeViewer;
    private ZooInspectorManager zooInspectorManager;

    public DeleteNodeAction(JPanel parentPanel,
                            ZooInspectorTreeViewer treeViewer,
                            ZooInspectorManager zooInspectorManager) {
        this.parentPanel = parentPanel;
        this.treeViewer = treeViewer;
        this.zooInspectorManager = zooInspectorManager;
    }


    public void actionPerformed(ActionEvent e) {
        final List<String> selectedNodes = treeViewer
                .getSelectedNodes();
        if (selectedNodes.size() == 0) {
            JOptionPane.showMessageDialog(parentPanel,
                    "Please select at least 1 node to be deleted");
        } else {
            int answer = JOptionPane.showConfirmDialog(
                    parentPanel,
                    "Are you sure you want to delete the selected nodes?"
                            + "(This action cannot be reverted)",
                    "Confirm Delete", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (answer == JOptionPane.YES_OPTION) {
                SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {

                    @Override
                    protected Boolean doInBackground() throws Exception {
                        for (String nodePath : selectedNodes) {
                            zooInspectorManager
                                    .deleteNode(nodePath);
                        }
                        return true;
                    }

                    @Override
                    protected void done() {
                        treeViewer.refreshView();
                    }
                };
                worker.execute();
            }
        }
    }
}
