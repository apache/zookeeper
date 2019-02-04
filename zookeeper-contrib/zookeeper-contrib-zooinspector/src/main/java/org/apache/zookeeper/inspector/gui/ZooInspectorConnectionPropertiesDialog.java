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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.apache.zookeeper.inspector.logger.LoggerFactory;
import org.apache.zookeeper.inspector.manager.Pair;

/**
 * The connection properties dialog. This is used to determine the settings for
 * connecting to a zookeeper instance
 */
public class ZooInspectorConnectionPropertiesDialog extends JDialog {

    private final Map<String, JComponent> components;

    /**
     * @param lastConnectionProps
     *            - the last connection properties used. if this is the first
     *            conneciton since starting the applications this will be the
     *            default settings
     * @param connectionPropertiesTemplateAndLabels
     *            - the connection properties and labels to show in this dialog
     * @param zooInspectorPanel
     *            - the {@link ZooInspectorPanel} linked to this dialog
     */
    public ZooInspectorConnectionPropertiesDialog(
            Properties lastConnectionProps,
            Pair<Map<String, List<String>>, Map<String, String>> connectionPropertiesTemplateAndLabels,
            final ZooInspectorPanel zooInspectorPanel) {
        final Map<String, List<String>> connectionPropertiesTemplate = connectionPropertiesTemplateAndLabels
                .getKey();
        final Map<String, String> connectionPropertiesLabels = connectionPropertiesTemplateAndLabels
                .getValue();
        this.setLayout(new BorderLayout());
        this.setTitle("Connection Settings");
        this.setModal(true);
        this.setAlwaysOnTop(true);
        this.setResizable(false);
        final JPanel options = new JPanel();
        final JFileChooser fileChooser = new JFileChooser();
        options.setLayout(new GridBagLayout());
        int i = 0;
        components = new HashMap<String, JComponent>();
        for (Entry<String, List<String>> entry : connectionPropertiesTemplate
                .entrySet()) {
            int rowPos = 2 * i + 1;
            JLabel label = new JLabel(connectionPropertiesLabels.get(entry
                    .getKey()));
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
            options.add(label, c1);
            if (entry.getValue().size() == 0) {
                JTextField text = new JTextField();
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
                options.add(text, c2);
                components.put(entry.getKey(), text);
            } else if (entry.getValue().size() == 1) {
                JTextField text = new JTextField(entry.getValue().get(0));
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
                options.add(text, c2);
                components.put(entry.getKey(), text);
            } else {
                List<String> list = entry.getValue();
                JComboBox combo = new JComboBox(list.toArray(new String[list
                        .size()]));
                combo.setSelectedItem(list.get(0));
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
                options.add(combo, c2);
                components.put(entry.getKey(), combo);
            }
            i++;
        }
        loadConnectionProps(lastConnectionProps);
        JPanel buttonsPanel = new JPanel();
        buttonsPanel.setLayout(new GridBagLayout());
        JButton loadPropsFileButton = new JButton("Load from file");
        loadPropsFileButton.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent e) {
                int result = fileChooser
                        .showOpenDialog(ZooInspectorConnectionPropertiesDialog.this);
                if (result == JFileChooser.APPROVE_OPTION) {
                    File propsFilePath = fileChooser.getSelectedFile();
                    Properties props = new Properties();
                    try {
                        FileReader reader = new FileReader(propsFilePath);
                        try {
                            props.load(reader);
                            loadConnectionProps(props);
                        } finally {
                            reader.close();
                        }
                    } catch (IOException ex) {
                        LoggerFactory
                                .getLogger()
                                .error(
                                        "An Error occurred loading connection properties from file",
                                        ex);
                        JOptionPane
                                .showMessageDialog(
                                        ZooInspectorConnectionPropertiesDialog.this,
                                        "An Error occurred loading connection properties from file",
                                        "Error", JOptionPane.ERROR_MESSAGE);
                    }
                    options.revalidate();
                    options.repaint();
                }

            }
        });
        GridBagConstraints c3 = new GridBagConstraints();
        c3.gridx = 0;
        c3.gridy = 0;
        c3.gridwidth = 1;
        c3.gridheight = 1;
        c3.weightx = 0;
        c3.weighty = 1;
        c3.anchor = GridBagConstraints.SOUTHWEST;
        c3.fill = GridBagConstraints.NONE;
        c3.insets = new Insets(5, 5, 5, 5);
        c3.ipadx = 0;
        c3.ipady = 0;
        buttonsPanel.add(loadPropsFileButton, c3);
        JButton saveDefaultPropsFileButton = new JButton("Set As Default");
        saveDefaultPropsFileButton.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent e) {

                Properties connectionProps = getConnectionProps();
                try {
                    zooInspectorPanel
                            .setdefaultConnectionProps(connectionProps);
                } catch (IOException ex) {
                    LoggerFactory
                            .getLogger()
                            .error(
                                    "An Error occurred saving the default connection properties file",
                                    ex);
                    JOptionPane
                            .showMessageDialog(
                                    ZooInspectorConnectionPropertiesDialog.this,
                                    "An Error occurred saving the default connection properties file",
                                    "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        GridBagConstraints c6 = new GridBagConstraints();
        c6.gridx = 1;
        c6.gridy = 0;
        c6.gridwidth = 1;
        c6.gridheight = 1;
        c6.weightx = 1;
        c6.weighty = 1;
        c6.anchor = GridBagConstraints.SOUTHWEST;
        c6.fill = GridBagConstraints.NONE;
        c6.insets = new Insets(5, 5, 5, 5);
        c6.ipadx = 0;
        c6.ipady = 0;
        buttonsPanel.add(saveDefaultPropsFileButton, c6);
        JButton okButton = new JButton("OK");
        okButton.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent e) {
                ZooInspectorConnectionPropertiesDialog.this.dispose();
                Properties connectionProps = getConnectionProps();
                zooInspectorPanel.connect(connectionProps);
            }
        });
        GridBagConstraints c4 = new GridBagConstraints();
        c4.gridx = 2;
        c4.gridy = 0;
        c4.gridwidth = 1;
        c4.gridheight = 1;
        c4.weightx = 0;
        c4.weighty = 1;
        c4.anchor = GridBagConstraints.SOUTH;
        c4.fill = GridBagConstraints.HORIZONTAL;
        c4.insets = new Insets(5, 5, 5, 5);
        c4.ipadx = 0;
        c4.ipady = 0;
        buttonsPanel.add(okButton, c4);
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent e) {
                ZooInspectorConnectionPropertiesDialog.this.dispose();
            }
        });
        GridBagConstraints c5 = new GridBagConstraints();
        c5.gridx = 3;
        c5.gridy = 0;
        c5.gridwidth = 1;
        c5.gridheight = 1;
        c5.weightx = 0;
        c5.weighty = 1;
        c5.anchor = GridBagConstraints.SOUTH;
        c5.fill = GridBagConstraints.HORIZONTAL;
        c5.insets = new Insets(5, 5, 5, 5);
        c5.ipadx = 0;
        c5.ipady = 0;
        buttonsPanel.add(cancelButton, c5);
        this.add(options, BorderLayout.CENTER);
        this.add(buttonsPanel, BorderLayout.SOUTH);
        this.pack();
    }

    private void loadConnectionProps(Properties props) {
        if (props != null) {
            for (Object key : props.keySet()) {
                String propsKey = (String) key;
                if (components.containsKey(propsKey)) {
                    JComponent component = components.get(propsKey);
                    String value = props.getProperty(propsKey);
                    if (component instanceof JTextField) {
                        ((JTextField) component).setText(value);
                    } else if (component instanceof JComboBox) {
                        ((JComboBox) component).setSelectedItem(value);
                    }
                }
            }
        }
    }

    private Properties getConnectionProps() {
        Properties connectionProps = new Properties();
        for (Entry<String, JComponent> entry : components.entrySet()) {
            String value = null;
            JComponent component = entry.getValue();
            if (component instanceof JTextField) {
                value = ((JTextField) component).getText();
            } else if (component instanceof JComboBox) {
                value = ((JComboBox) component).getSelectedItem().toString();
            }
            connectionProps.put(entry.getKey(), value);
        }
        return connectionProps;
    }
}
