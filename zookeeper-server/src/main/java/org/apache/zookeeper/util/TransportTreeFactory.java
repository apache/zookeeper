/*TODO: license?*/

package org.apache.zookeeper.util;

import java.util.List;
import org.apache.zookeeper.data.ACL;

/**
 * Abstract Transport Tree Factory Class; instantiate to generate
 * transport trees at whim
 */
public interface TransportTreeFactory{

    /**
     * Generate a new Transport Tree node
     *
     * @param name The string name of the tree node (the last element of the znode path)
     * @param data The znode's data buffer, will be cloned into the tree.
     * @param acl The znode's access control list, will be cloned into the tree.
     */ 
    public TransportTree makeNewTree(String name, byte[] data, List<ACL> acl);
}


