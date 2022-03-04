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

import com.nitido.utils.toaster.Toaster;
import org.apache.zookeeper.inspector.gui.nodeviewer.NodeSelectionListener;
import org.apache.zookeeper.inspector.logger.LoggerFactory;
import org.apache.zookeeper.inspector.manager.NodeListener;
import org.apache.zookeeper.inspector.manager.Pair;
import org.apache.zookeeper.inspector.manager.ZooInspectorManager;

import javax.swing.ImageIcon;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ZooInspectorTreeView extends JPanel {
    private static final String PATH_SEPARATOR = "/";
    private static final String ROOT_PATH = PATH_SEPARATOR;

    private final JTree tree;
    private final ZooInspectorTreeModel treeModel;

    private final JPopupMenu rightClickMenu;
    private final JMenuItem createChildNodeMenuItem;
    private final JMenuItem deleteNodeMenuItem;
    private final JMenuItem refreshNodeMenuItem;
    private final JMenuItem addWatchMenuItem;
    private final JMenuItem removeWatchMenuItem;

    private final Toaster toasterManager;
    private final ImageIcon toasterIcon;

    private final List<NodeSelectionListener> nodeSelectionListeners = new LinkedList<>();

    public ZooInspectorTreeView(final ZooInspectorManager manager, IconResource iconResource) {
        this.toasterManager = new Toaster();
        this.toasterManager.setBorderColor(Color.BLACK);
        this.toasterManager.setMessageColor(Color.BLACK);
        this.toasterManager.setToasterColor(Color.WHITE);
        this.toasterIcon = iconResource.get(IconResource.ICON_INFORMATION, "");

        // Set up tree to display all ZNodes
        this.treeModel = new ZooInspectorTreeModel(manager);
        this.tree = new JTree();
        this.tree.setEditable(false);
        this.tree.setFocusable(true);
        this.tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        this.tree.setModel(this.treeModel);
        this.tree.setCellRenderer(new ZooInspectorTreeCellRenderer(iconResource));

        // Set up right click menu for individual ZNodes
        this.rightClickMenu = new JPopupMenu();
        this.createChildNodeMenuItem = new JMenuItem("Create Child Node");
        this.deleteNodeMenuItem = new JMenuItem("Delete Node");
        this.refreshNodeMenuItem = new JMenuItem("Refresh Node");
        this.addWatchMenuItem = new JMenuItem("Add Watch");
        this.removeWatchMenuItem = new JMenuItem("Remove Watch");

        // Add various event listeners (all implemented as inner classes on this class)
        TreeEventHandler treeHandler = new TreeEventHandler();
        MouseEventHandler mouseHandler = new MouseEventHandler();
        KeyEventHandler keyHandler = new KeyEventHandler();
        ActionEventHandler actionHandler = new ActionEventHandler();

        this.tree.addKeyListener(keyHandler);
        this.tree.addTreeExpansionListener(treeHandler);
        this.tree.getSelectionModel().addTreeSelectionListener(treeHandler);
        this.tree.addMouseListener(mouseHandler);
        this.rightClickMenu.add(this.createChildNodeMenuItem).addActionListener(actionHandler);
        this.rightClickMenu.add(this.deleteNodeMenuItem).addActionListener(actionHandler);
        this.rightClickMenu.add(this.refreshNodeMenuItem).addActionListener(actionHandler);
        this.rightClickMenu.add(this.addWatchMenuItem).addActionListener(actionHandler);
        this.rightClickMenu.add(this.removeWatchMenuItem).addActionListener(actionHandler);

        setLayout(new BorderLayout());
        add(this.tree, BorderLayout.CENTER);
    }

    public void addNodeSelectionListener(NodeSelectionListener l) {
        if (!this.nodeSelectionListeners.contains(l)) {
            this.nodeSelectionListeners.add(l);
        }
    }

    @SuppressWarnings("unused")
    public void removeNodeSelectionListener(NodeSelectionListener l) {
        this.nodeSelectionListeners.remove(l);
    }

    ///////////////////////////////// EVENT HANDLERS /////////////////////////////////

    private class NodeEventHandler implements NodeListener {
        @Override
        public void processEvent(String nodePath, String eventType, Map<String, String> eventInfo) {
            StringBuilder sb = new StringBuilder(256);
            sb.append("Node: ");
            sb.append(nodePath);
            sb.append("\nEvent: ");
            sb.append(eventType);
            if (eventInfo != null) {
                for (Map.Entry<String, String> entry : eventInfo.entrySet()) {
                    sb.append("\n");
                    sb.append(entry.getKey());
                    sb.append(": ");
                    sb.append(entry.getValue());
                }
            }
            toasterManager.showToaster(toasterIcon, sb.toString());
        }
    }

    public class TreeEventHandler implements TreeExpansionListener, TreeSelectionListener {
        @Override
        public void treeExpanded(TreeExpansionEvent event) {
            ZooInspectorTreeNode expandingNode = (ZooInspectorTreeNode) event.getPath().getLastPathComponent();

            // This whole chunk of code before the "refreshNode" call is to deal with the fact that when lazy-loading a
            // node, we first give it a single "placeholder" empty child node to mark it as having children, but we don't
            // actually load those children until the user decides to expand it.  These "if" statements figure out if the
            // node has a single placeholder child or not.

            if (expandingNode.isLeaf() || expandingNode.getChildCount() != 1) {
                return;
            }

            ZooInspectorTreeNode onlyChild = ((ZooInspectorTreeNode) expandingNode.getChildAt(0));
            if (!onlyChild.isPlaceholder()) {
                return;
            }

            treeModel.refreshNode(expandingNode);
        }

        @Override
        public void treeCollapsed(TreeExpansionEvent event) {
        }

        @Override
        public void valueChanged(TreeSelectionEvent e) {
            ZooInspectorTreeNode node = (ZooInspectorTreeNode) e.getPath().getLastPathComponent();
            String selectedPath = node.getPathString();
            for (NodeSelectionListener listener : nodeSelectionListeners) {
                listener.nodePathSelected(selectedPath);
            }
        }
    }

    private class KeyEventHandler extends KeyAdapter {
        @Override
        public void keyReleased(KeyEvent e) {
            if (!tree.hasFocus()) {
                return;
            }

            switch (e.getKeyCode()) {
                case KeyEvent.VK_D:
                    deleteNode();
                    break;
                case KeyEvent.VK_N:
                    createNode();
                    break;
                case KeyEvent.VK_R:
                    refreshNode();
                    break;
                default:
                    break;
            }
        }
    }

    private class ActionEventHandler implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            if (e.getSource() == createChildNodeMenuItem) {
                createNode();
            } else if (e.getSource() == deleteNodeMenuItem) {
                deleteNode();
            } else if (e.getSource() == refreshNodeMenuItem) {
                refreshNode();
            } else if (e.getSource() == addWatchMenuItem) {
                addWatch();
            } else if (e.getSource() == removeWatchMenuItem) {
                removeWatch();
            }
        }
    }

    private class MouseEventHandler extends MouseAdapter {

        @Override
        public void mouseClicked(MouseEvent e) {
            ZooInspectorTreeNode selectedNode = getSelectedNode();
            if (selectedNode == null) {
                //If there's no node currently selected, see if the mouse is on top of one and select it
                int selectedRow = tree.getRowForLocation(e.getX(), e.getY());
                if (selectedRow == -1) {
                    return;
                }
                tree.setSelectionRow(selectedRow);

                TreePath selectedPath = tree.getPathForLocation(e.getX(), e.getY());
                tree.setSelectionPath(selectedPath);
            }

            boolean shouldShowPopup = e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e);
            if (shouldShowPopup) {
                rightClickMenu.show(ZooInspectorTreeView.this, e.getX(), e.getY());
            }
        }
    }

    ///////////////////////////////// BUSINESS LOGIC /////////////////////////////////

    /**
     * Initialize the view by creating a completely new tree by removing and refreshing all nodes.
     */
    public void initialize() {
        this.treeModel.init();
    }

    /**
     * Clear all the existing nodes from the tree view.
     */
    public void clear() {
        this.treeModel.clear();
    }

    /**
     * Start the UI workflow for creating a new ZNode as a child of the selected node.
     */
    public void createNode() {
        ZooInspectorTreeNode parentNode = getSelectedNode();
        if (parentNode == null) {
            return;
        }

        final String newNodeName = JOptionPane.showInputDialog(
                this,
                "Please enter a name for the new node: ",
                "Create Child Node",
                JOptionPane.INFORMATION_MESSAGE);

        if (newNodeName == null || newNodeName.trim().isEmpty()) {
            return;
        }

        this.treeModel.createNode(parentNode, newNodeName);
    }

    /**
     * Start the UI workflow for deleting the selected node.
     */
    public void deleteNode() {
        ZooInspectorTreeNode nodeToDelete = getSelectedNode();
        if (nodeToDelete == null) {
            return;
        }

        int answer = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete the selected node '" + nodeToDelete.getPathString() + "'?\n" +
                        "(This action cannot be reverted)",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (answer != JOptionPane.YES_OPTION) {
            return;
        }

        this.treeModel.deleteNode(nodeToDelete);
    }

    /**
     * Refresh the selected node (i.e. deleting all children and re-fetch them from Zookeeper).
     */
    public void refreshNode() {
        ZooInspectorTreeNode nodeToRefresh = getSelectedNode();
        if (nodeToRefresh == null) {
            return;
        }
        this.treeModel.refreshNode(nodeToRefresh);
    }

    /**
     * Add a Zookeeper watch to the selected node.
     */
    public void addWatch() {
        ZooInspectorTreeNode nodeToWatch = getSelectedNode();
        if (nodeToWatch == null) {
            return;
        }
        this.treeModel.addWatch(nodeToWatch);
    }

    /**
     * Remove a Zookeeper watch from the selected node (has no effect if the node does not have an existing watch).
     */
    public void removeWatch() {
        ZooInspectorTreeNode nodeToUnwatch = getSelectedNode();
        if (nodeToUnwatch == null) {
            return;
        }
        this.treeModel.removeWatch(nodeToUnwatch);
    }

    /**
     * @return The node object corresponding to the selected node or null if there is no node selected.
     */
    private ZooInspectorTreeNode getSelectedNode() {
        TreePath selected = this.tree.getSelectionPath();
        return selected != null ? ((ZooInspectorTreeNode) selected.getLastPathComponent()) : null;
    }

    private void showWarnDialog(String message){
        JOptionPane.showMessageDialog(this,
                message, "Error",
                JOptionPane.ERROR_MESSAGE);
    }

    ///////////////////////////////// BACKING DATA MODEL /////////////////////////////////

    /**
     * An implement of the backing TreeModel data for the JTree in the user interface.  Controls what data is actually
     * available to be rendered in the UI.
     */
    private class ZooInspectorTreeModel extends DefaultTreeModel {
        private final ZooInspectorManager manager;

        public ZooInspectorTreeModel(ZooInspectorManager manager) {
            super(new ZooInspectorTreeNode(ROOT_PATH, ROOT_PATH, 0));
            this.manager = manager;
        }

        /**
         * Create a new ZNode in Zookeeper as a child of the given parent node.
         *
         * @param parentNode  The parent node to create a new child ZNode underneath
         * @param newNodeName The name of the new child ZNode to create
         */
        public void createNode(ZooInspectorTreeNode parentNode, String newNodeName) {
            SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {
                @Override
                protected Boolean doInBackground() {
                    //runs on a background non-UI thread
                    return manager.createNode(parentNode.getPathString(), newNodeName);
                }

                @Override
                protected void done() {
                    //runs on the UI event thread
                    boolean success;
                    try {
                        success = get();
                    } catch (Exception e) {
                        success = false;
                        LoggerFactory.getLogger().error("create fail for {} {}", parentNode, newNodeName, e);
                        showWarnDialog("create " + newNodeName + " in " + parentNode + " fail, exception is " + e.getMessage());
                    }

                    if (!success) {
                        showWarnDialog("create " + newNodeName + " in " + parentNode + " fail, see log for more detail");
                    }
                    else {
                        //extra logic to find the correct spot alphabetically to insert the new node in the tree`
                        int i = 0;
                        for (; i < parentNode.getChildCount(); i++) {
                            ZooInspectorTreeNode existingChild = (ZooInspectorTreeNode) parentNode.getChildAt(i);
                            if (newNodeName.compareTo(existingChild.getName()) < 0) {
                                break;
                            }
                        }
                        insertNodeInto(new ZooInspectorTreeNode(newNodeName, parentNode, 0), parentNode, i);
                        parentNode.setNumDisplayChildren(parentNode.getNumDisplayChildren() + 1);
                    }
                    getRootPane().setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            };
            getRootPane().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            worker.execute();
        }

        /**
         * Delete the specified ZNode in Zookeeper.
         *
         * @param nodeToDelete The node to delete.
         */
        public void deleteNode(ZooInspectorTreeNode nodeToDelete) {
            SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {
                @Override
                protected Boolean doInBackground() {
                    //runs on a background non-UI thread
                    return manager.deleteNode(nodeToDelete.getPathString());
                }

                @Override
                protected void done() {
                    //runs on the UI event thread
                    ZooInspectorTreeNode parent = (ZooInspectorTreeNode) nodeToDelete.getParent();
                    parent.setNumDisplayChildren(parent.getNumDisplayChildren() - 1);
                    removeNodeFromParent(nodeToDelete);
                    getRootPane().setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            };
            getRootPane().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            worker.execute();
        }

        /**
         * Refresh the specified node in the UI. Refresh is equivalent to removing all existing children from the UI
         * view and re-fetching them from Zookeeper.
         *
         * @param nodeToRefresh The node whose subtree should be refreshed.
         */
        public void refreshNode(ZooInspectorTreeNode nodeToRefresh) {
            SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {
                final LinkedList<Pair<String, Integer>> childrenToAdd = new LinkedList<>();

                @Override
                protected Boolean doInBackground() {
                    //runs on a background non-UI thread
                    //Make all the network calls here (to get children and their child counts) and collect the children,
                    //but we can't add them to the UI until we're back on the event thread in done()
                    List<String> children = manager.getChildren(nodeToRefresh.getPathString());
                    if (children == null) {
                        return false;
                    }
                    nodeToRefresh.setNumDisplayChildren(children.size());

                    for (String childName : children) {
                        ZooInspectorTreeNode childNode = new ZooInspectorTreeNode(childName, nodeToRefresh, 0);
                        int numChildren = manager.getNumChildren(childNode.getPathString());
                        childrenToAdd.add(new Pair<>(childName, numChildren));
                    }
                    return true;
                }

                @Override
                protected void done() {
                    //runs on the UI event thread
                    nodeToRefresh.removeAllChildren();

                    for (Pair<String, Integer> childPair : childrenToAdd) {
                        ZooInspectorTreeNode childNode = new ZooInspectorTreeNode(childPair.getKey(),
                                nodeToRefresh,
                                childPair.getValue());

                        if (childPair.getValue() > 0) {
                            // add a placeholder child so the UI renders this node like it has children
                            childNode.add(new ZooInspectorTreeNode());
                        }
                        nodeToRefresh.add(childNode);
                    }

                    reload(nodeToRefresh);
                    getRootPane().setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            };
            getRootPane().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            worker.execute();
        }

        /**
         * Add a Zookeeper watch to the specified node (which will display updates in a Toast)
         *
         * @param nodeToWatch A reference to the node to place a watch on
         */
        public void addWatch(ZooInspectorTreeNode nodeToWatch) {
            this.manager.addWatchers(Collections.singletonList(nodeToWatch.getPathString()), new NodeEventHandler());
        }

        /**
         * Remove a Zookeeper watch from the specified node (has no effect if this node has no existing watch)
         *
         * @param nodeToUnwatch A reference to the node to remove a watch from
         */
        public void removeWatch(ZooInspectorTreeNode nodeToUnwatch) {
            this.manager.removeWatchers(Collections.singletonList(nodeToUnwatch.getPathString()));
        }

        /**
         * Reinitialize the entire tree by refreshing the root node.
         */
        public void init() {
            refreshNode((ZooInspectorTreeNode) getRoot());
        }

        /**
         * Clear the tree by removing all children from the root node.
         */
        public void clear() {
            ZooInspectorTreeNode root = (ZooInspectorTreeNode) getRoot();
            root.setNumDisplayChildren(0);
            root.removeAllChildren();
            reload();
        }
    }

    /**
     * A representation of a single node in the TreeModel.  Keeps track of a node's name, the number of children
     * it has and its full Zookeeper path.
     */
    private static class ZooInspectorTreeNode extends DefaultMutableTreeNode {
        private final String name;
        private final String pathString;
        private int numDisplayChildren;

        public ZooInspectorTreeNode() {
            this("", "", 0);
        }

        public ZooInspectorTreeNode(String name, ZooInspectorTreeNode parent, int numDisplayChildren) {
            this(name, (PATH_SEPARATOR.equals(parent.getName()) ? "" : parent.getPathString()) + PATH_SEPARATOR + name, numDisplayChildren);
        }

        public ZooInspectorTreeNode(String name, String pathString, int numDisplayChildren) {
            this.name = name;
            this.pathString = pathString;
            this.numDisplayChildren = numDisplayChildren;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            ZooInspectorTreeNode that = (ZooInspectorTreeNode) o;
            return this.pathString.equals(that.pathString);
        }

        @Override
        public int hashCode() {
            return this.pathString.hashCode();
        }

        public boolean isPlaceholder() {
            //A placeholder node renders as "Loading..." on the UI and is identified by having an empty name and path
            return this.name.isEmpty() && this.getPathString().isEmpty();
        }

        public String getName() {
            return this.name;
        }

        public String getPathString() {
            return this.pathString;
        }

        public int getNumDisplayChildren() {
            return this.numDisplayChildren;
        }

        public void setNumDisplayChildren(int numDisplayChildren) {
            this.numDisplayChildren = numDisplayChildren;
        }

        @Override
        public String toString() {
            //NOTE: Don't mess with this; it's actually used to construct the TreePath entries; if you want to
            //change the name on the UI display, use the TreeCellRenderer below
            return this.name;
        }
    }

    /**
     * A class that controls how a given tree node is rendered in the tree on the UI (this is what's responsible for
     * drawing "Loading..." for a placeholder node or render the # of children for a node).
     */
    private static class ZooInspectorTreeCellRenderer extends DefaultTreeCellRenderer {
        public ZooInspectorTreeCellRenderer(IconResource iconResource) {
            setLeafIcon(iconResource.get(IconResource.ICON_TREE_LEAF, ""));
            setOpenIcon(iconResource.get(IconResource.ICON_TREE_OPEN, ""));
            setClosedIcon(iconResource.get(IconResource.ICON_TREE_CLOSE, ""));
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            ZooInspectorTreeNode node = (ZooInspectorTreeNode) value;
            String text = node.getName();

            if (node.isPlaceholder()) {
                text = "Loading...";
            }

            if (node.getNumDisplayChildren() > 0) {
                text += " (" + node.getNumDisplayChildren() + ")";
            }

            return super.getTreeCellRendererComponent(tree, text, sel, expanded, leaf, row, hasFocus);
        }
    }
}
