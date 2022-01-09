package org.apache.zookeeper.inspector.gui.nodeviewer;

/**
 * An interface to be implented by any component that needs notification when a new element
 * is selected in the UI JTree representing the set of available ZNodes.
 */
public interface NodeSelectionListener {
    void nodePathSelected(String nodePath);
}
