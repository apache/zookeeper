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

import java.awt.event.ActionListener;
import java.util.HashMap;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JToolBar;

public class Toolbar {

    private final IconResource iconResource;
    private final JToolBar toolbar = new JToolBar();
    private final Map<Button, JButton> buttons = new HashMap<Button, JButton>();

    private static final Button[] buttonsToToggle = new Button[] {
        Button.connect, Button.disconnect, Button.refresh, Button.addNode, Button.deleteNode
    };

    public Toolbar(IconResource iconResource) {
        this.iconResource = iconResource;
        init();
    }

    public void addActionListener(Button button, ActionListener actionListener) {
        buttons.get(button).addActionListener(actionListener);
    }

    public JToolBar getJToolBar() {
        return toolbar;
    }

    public void toggleButtons(boolean connected) {
        for(Button button : buttonsToToggle) {
            buttons.get(button).setEnabled(connected != button.enabled);
        }
    }

    private void init() {
        toolbar.setFloatable(false);
        for(Button button : Button.values()) {
            JButton jbutton = button.createJButton(iconResource);
            buttons.put(button, jbutton);
            toolbar.add(jbutton);
        }
    }

    public static enum Button {
        connect("Connect",IconResource.ICON_START,true),
        disconnect("Disconnect",IconResource.ICON_STOP,false),
        refresh("Refresh",IconResource.ICON_REFRESH,false),
        addNode("Add Node",IconResource.ICON_DOCUMENT_ADD,false),
        deleteNode("Delete Node",IconResource.ICON_TRASH,false),
        nodeViewers("Change Node Viewers",IconResource.ICON_ChangeNodeViewers,true),
        about("About ZooInspector",IconResource.ICON_HELP_ABOUT,true);

        private String toolTip;
        private String icon;
        private boolean enabled;

        Button(String toolTip, String icon, boolean enabled) {
            this.toolTip = toolTip;
            this.icon = icon;
            this.enabled = enabled;
        }

        public JButton createJButton(IconResource iconResource) {
            JButton jbutton = new JButton(iconResource.get(icon, toolTip));
            jbutton.setEnabled(enabled);
            jbutton.setToolTipText(toolTip);
            return jbutton;
        }
    }
}
