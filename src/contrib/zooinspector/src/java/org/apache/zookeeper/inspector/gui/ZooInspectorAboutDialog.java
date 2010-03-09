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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JPanel;

import org.apache.zookeeper.inspector.logger.LoggerFactory;

/**
 * The About Dialog for the application
 */
public class ZooInspectorAboutDialog extends JDialog {
    /**
     * @param frame
     *            - the Frame from which the dialog is displayed
     */
    public ZooInspectorAboutDialog(Frame frame) {
        super(frame);
        this.setLayout(new BorderLayout());
        this.setIconImage(ZooInspectorIconResources.getInformationIcon()
                .getImage());
        this.setTitle("About ZooInspector");
        this.setModal(true);
        this.setAlwaysOnTop(true);
        this.setResizable(false);
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        JEditorPane aboutPane = new JEditorPane();
        aboutPane.setEditable(false);
        aboutPane.setOpaque(false);
        java.net.URL aboutURL = ZooInspectorAboutDialog.class
                .getResource("about.html");
        try {
            aboutPane.setPage(aboutURL);
        } catch (IOException e) {
            LoggerFactory.getLogger().error(
                    "Error loading about.html, file may be corrupt", e);
        }
        panel.add(aboutPane, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(600, 200));
        JPanel buttonsPanel = new JPanel();
        buttonsPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 10));
        JButton okButton = new JButton("OK");
        okButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                ZooInspectorAboutDialog.this.dispose();
            }
        });
        buttonsPanel.add(okButton);
        this.add(panel, BorderLayout.CENTER);
        this.add(buttonsPanel, BorderLayout.SOUTH);
        this.pack();
    }
}
