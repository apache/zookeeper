// ***************************************************
// CSCI 612 - Blue
//
// This manages the state of the DataTree as it relates to Nodes currently in the cache and those that are not.
//
// Stephen
//
// Used to determine if a Node is already in the cache and can be immediately accessed, or if the node needs to be addded to the node list in the DataTree, and the cachedNodes list.
// ***************************************************

package org.apache.zookeeper.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class DataCache {
    private static final long ONE_KB = 1024;
    private static final int MAX_SIZE_IN_KB = 1;

    private static final Logger LOG = LoggerFactory.getLogger(DataCache.class);

    private Map<String, Long> allNodes = new HashMap<>();
    private LinkedHashMap<String, Long> cachedNodes = new LinkedHashMap<>(); // Kept in order by LRU first
    private List<String> nodesPendingEviction = Collections.synchronizedList(new ArrayList<>());
    private int currentSize;


    /**
     *
     * @param pathToNode
     * @return whether the node was already in the cache
     */
    public synchronized boolean markNodeAsAccessed(String pathToNode) {
        boolean nodeInCache = false;
        if (cachedNodes.keySet().contains(pathToNode)) {
            LOG.info("Node already marked as cached, putting it at the end of the hash map.");
            cachedNodes.remove(pathToNode); // Remove as it will need to be appended to the end of the LinkedHashMap
            nodeInCache = true;
        }

        LOG.info("marking: " + pathToNode + " as accessed.");
        long size = allNodes.get(pathToNode);
        if (size < 0) {
            throw new IllegalArgumentException("Attempted to mark a node as accessed when it was not found in the list of all tracked nodes.");
        }

        removeLeastRecentlyUsedNodePathsUntilCanAccommodateSize(size);

        cachedNodes.put(pathToNode, size);

        if (!nodeInCache) {
            currentSize += size;
        }

        LOG.info("After marking a node as accessed the cache is now: " + currentSize + " bytes.");

        return nodeInCache;
    }

    public void addNodeToAllNodes(String path, long size) {
        LOG.info("adding: " + path + " to allNodes with size: " + size);
        allNodes.put(path, size);
    }

    public void removeNode(String path) {
        LOG.info("removing node: " + path);
        allNodes.remove(path);
        if (cachedNodes.containsKey(path)) {
            long sizeOfRemovedNode = cachedNodes.remove(path);
            currentSize -= sizeOfRemovedNode;
        }
    }

    public void updateSizeOfNode(String path, long size) {
        LOG.info("updating the size of node: " + path + " to: " + size);
        allNodes.put(path, size);

        if (cachedNodes.containsKey(path)) {
            long oldSize = cachedNodes.remove(path);
            cachedNodes.put(path, size);
            long sizeDifference = size - oldSize;
            currentSize += sizeDifference;

            // Just in case we have gone over our cache size
            removeLeastRecentlyUsedNodePathsUntilCanAccommodateSize(0L);
        }
    }

    public List<String> getAndClearNodesPendingEviction() {
        List<String> nodesPendingEvictionCopy = new ArrayList<>(nodesPendingEviction);
        nodesPendingEviction.clear();
        LOG.info("Nodes pending eviction: " + nodesPendingEvictionCopy.size());
        return nodesPendingEvictionCopy;
    }

    private void removeLeastRecentlyUsedNodePathsUntilCanAccommodateSize(long size) {
        Iterator<String> cachedNodesIterator = cachedNodes.keySet().iterator();
        while (((currentSize + size) / ONE_KB >= MAX_SIZE_IN_KB) && cachedNodes.size() > 0) {
            String firstPath = cachedNodesIterator.next();
            LOG.info("removing " + firstPath + " from the cache");
            long sizeOfRemovedNode = cachedNodes.get(firstPath);
            cachedNodesIterator.remove();
            currentSize -= sizeOfRemovedNode;
            LOG.info("cache size is now: " + currentSize);

            nodesPendingEviction.add(firstPath);
        }
    }
}
