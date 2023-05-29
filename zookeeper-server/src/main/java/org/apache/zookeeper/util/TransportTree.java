/*TODO: license?*/

package org.apache.zookeeper.util;

import java.lang.Iterable;
import java.util.Iterator;
import java.util.List;
import org.apache.zookeeper.data.ACL;

/**
 * TransportTrees store znode subtrees unraveled as part of
 * the move and copy command line utilities.
 */
public class TransportTree implements Iterable<TransportTree>{

    public static TransportTree makeNewTree(String name, byte[] data, List<ACL> acl){
        // TODO
    }

    public TransportTree addChild(TransportTree child){
        // TODO
    }

    public String getName(){
        // TODO
    }

    public byte[] getData(){
        // TODO
    }

    public List<ACL> getACL(){
        // TODO
    }

    
    public Iterator<TransportTree> iterator(){
        return new TransportTreeIterator();
    }

    private class TransportTreeIterator implements Iterator<TransportTree>{
        TransportTreeIterator(){
            // TODO
        }

        public boolean hasNext(){
            // TODO
        }

        public TransportTree next(){
            // TODO
        }
    }

}


