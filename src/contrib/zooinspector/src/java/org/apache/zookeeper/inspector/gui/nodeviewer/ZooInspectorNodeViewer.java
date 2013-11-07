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

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.List;

import javax.swing.JPanel;

import org.apache.zookeeper.inspector.manager.ZooInspectorNodeManager;

/**
 * A {@link JPanel} for displaying information about the currently selected
 * node(s)
 */
public abstract class ZooInspectorNodeViewer extends JPanel implements
        Transferable {
    /**
     * The {@link DataFlavor} used for DnD in the node viewer configuration
     * dialog
     */
    public static final DataFlavor nodeViewerDataFlavor = new DataFlavor(
            ZooInspectorNodeViewer.class, "nodeviewer");

    /**
     * @param zooInspectorManager
     */
    public abstract void setZooInspectorManager(
            ZooInspectorNodeManager zooInspectorManager);

    /**
     * Called whenever the selected nodes in the tree view changes.
     * 
     * @param selectedNodes
     *            - the nodes currently selected in the tree view
     * 
     */
    public abstract void nodeSelectionChanged(List<String> selectedNodes);

    /**
     * @return the title of the node viewer. this will be shown on the tab for
     *         this node viewer.
     */
    public abstract String getTitle();

    /*
     * (non-Javadoc)
     * 
     * @see
     * java.awt.datatransfer.Transferable#getTransferData(java.awt.datatransfer
     * .DataFlavor)
     */
    public Object getTransferData(DataFlavor flavor)
            throws UnsupportedFlavorException, IOException {
        if (flavor.equals(nodeViewerDataFlavor)) {
            return this.getClass().getCanonicalName();
        } else {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.awt.datatransfer.Transferable#getTransferDataFlavors()
     */
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[] { nodeViewerDataFlavor };
    }

    /*
     * (non-Javadoc)
     * 
     * @seejava.awt.datatransfer.Transferable#isDataFlavorSupported(java.awt.
     * datatransfer.DataFlavor)
     */
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return flavor.equals(nodeViewerDataFlavor);
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((getTitle() == null) ? 0 : getTitle().hashCode());
        return result;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ZooInspectorNodeViewer other = (ZooInspectorNodeViewer) obj;
        if (getClass().getCanonicalName() != other.getClass()
                .getCanonicalName()) {
            return false;
        }
        if (getTitle() == null) {
            if (other.getTitle() != null)
                return false;
        } else if (!getTitle().equals(other.getTitle()))
            return false;
        return true;
    }
}
