/*TODO: license?*/

package org.apache.zookeeper.util;

import java.util.List;
import org.apache.zookeeper.data.ACL;

/**
 * Concrete Factory for generating SimpleTransportTree objects
 */
public class SimpleTransportTreeFactory implements TransportTreeFactory{

    /**
     * See interface for details
     */ 
    public TransportTree makeNewTree(String name, byte[] data, List<ACL> acl){
        return new SimpleTransportTree(name, data, acl);
    }
}


