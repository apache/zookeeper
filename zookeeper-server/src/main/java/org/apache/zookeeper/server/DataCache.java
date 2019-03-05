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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;

public class DataCache {
    private static final Logger LOG = LoggerFactory.getLogger(DataCache.class);

    private static final long ONE_MB = 1024 * 1024;
    private static final int MAX_SIZE_IN_MB = 1;

    private LinkedHashMap<String, DataCacheRecord> cachedNodes = new LinkedHashMap<>(); // Kept in order by LRU first
    private int currentSize;


    public synchronized boolean put(String path, DataNode node, long size) {
        LOG.info("Adding " + path + " to cache...");
        boolean nodeInCache = false;
        if (cachedNodes.keySet().contains(path)) {
            cachedNodes.remove(path); // Remove as it will need to be appended to the end of the LinkedHashMap
            nodeInCache = true;
            LOG.info("Node already in cache...");
        }
        trim(size);
        cachedNodes.put(path, new DataCacheRecord(path, node, size));
        currentSize += size;
        LOG.info("Success. New cache size: " + Integer.toString(currentSize));
        return nodeInCache;
    }

    public DataNode remove(String path) {
        LOG.info("Removing " + path + " to cache...");
        DataCacheRecord record = cachedNodes.remove(path);
        if (record != null) {
            currentSize -= record.size;
            LOG.info("Success. New cache size: " + Integer.toString(currentSize));
            return record.node;
        } else {
            LOG.info("Node not found.");
            return null;
        }
    }

    public void update(String path, DataNode node, long size) {
        LOG.info("Updating " + path + " in cache...");
        if (cachedNodes.containsKey(path)) {
            DataCacheRecord oldRecord = cachedNodes.remove(path);
            if (oldRecord != null) {
                cachedNodes.put(path, new DataCacheRecord(path, node, size));
                long sizeDifference = size - oldRecord.size;
                currentSize += sizeDifference;
                // Just in case we have gone over our cache size
                trim(0L);
                LOG.info("Success.");
            } else {
                LOG.info("Node not found.");
            }
        }
    }

    public ArrayList<String> toArrayList() {
        return new ArrayList<>(cachedNodes.keySet());
    }

    private void trim(long size) {
        Iterator<String> cachedNodesIterator = cachedNodes.keySet().iterator();
        while (((currentSize + size) / ONE_MB >= MAX_SIZE_IN_MB) && cachedNodes.size() > 0) {
            String firstPath = cachedNodesIterator.next();
            DataCacheRecord recordRemoved = cachedNodes.remove(firstPath);
            currentSize -= recordRemoved.size;
        }
    }
}
