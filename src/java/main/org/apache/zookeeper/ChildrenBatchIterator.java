package org.apache.zookeeper;

import org.apache.zookeeper.data.PathWithStat;

import javax.naming.OperationNotSupportedException;
import java.util.Iterator;
import java.util.List;

/**
 * Iterator over children nodes of a given path.
 */
class ChildrenBatchIterator implements Iterator<PathWithStat> {
    private final ZooKeeper zooKeeper;
    private final String path;
    private final Watcher watcher;
    private final int batchSize;
    private Iterator<PathWithStat> currentBatchIterator;
    private List<PathWithStat> currentBatch;
    private long highestCZkId = -1;

    ChildrenBatchIterator(ZooKeeper zooKeeper, String path, Watcher watcher, int batchSize, int minZkid)
            throws KeeperException, InterruptedException {
        this.zooKeeper = zooKeeper;
        this.path = path;
        this.watcher = watcher;
        this.batchSize = batchSize;
        this.highestCZkId = minZkid;

        this.currentBatch = zooKeeper.getChildren(path, watcher, batchSize, minZkid);
        this.currentBatchIterator = currentBatch.iterator();
    }

    @Override
    public boolean hasNext() {
        return currentBatchIterator.hasNext();
    }

    @Override
    public PathWithStat next() {

        if(!hasNext()) {
            throw new RuntimeException("No more element");
        }

        PathWithStat returnChildren = currentBatchIterator.next();

        highestCZkId = returnChildren.getStat().getCzxid();

        // If we reached the end of the current batch, fetch the next one

        try {
            this.currentBatch = zooKeeper.getChildren(path, watcher, batchSize, highestCZkId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch next batch", e);
        }

        this.currentBatchIterator = currentBatch.iterator();

        return returnChildren;
    }

    @Override
    public void remove() {
        throw new RuntimeException(new OperationNotSupportedException("remove not supported"));
    }
}
