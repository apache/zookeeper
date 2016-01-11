package org.apache.zookeeper;

import org.apache.zookeeper.data.PathWithStat;

import java.util.Iterator;
import java.util.List;

/**
 * Iterator over children nodes of a given path.
 */
class ChildrenBatchIterator implements RemoteIterator<PathWithStat> {

    private final ZooKeeper zooKeeper;
    private final String path;
    private final Watcher watcher;
    private final int batchSize;
    private Iterator<PathWithStat> currentBatchIterator;
    private List<PathWithStat> currentBatch;
    private long highestCZkId = -1;
    private boolean noMoreChildren = false;


    ChildrenBatchIterator(ZooKeeper zooKeeper, String path, Watcher watcher, int batchSize, int minZkid)
            throws KeeperException, InterruptedException {
        this.zooKeeper = zooKeeper;
        this.path = path;
        this.watcher = watcher;
        this.batchSize = batchSize;
        this.highestCZkId = minZkid;

        this.currentBatch = zooKeeper.getChildren(path, watcher, batchSize, highestCZkId);
        this.currentBatchIterator = currentBatch.iterator();
    }

    @Override
    public boolean hasNext() throws InterruptedException, KeeperException {

        // More element in current batch
        if (currentBatchIterator.hasNext()) {
            return true;
        }

        // Server already said no more elements (and possibly set a watch)
        if (noMoreChildren) {
            return false;
        }

        // No more element in current batch, but server may have more
        this.currentBatch = zooKeeper.getChildren(path, watcher, batchSize, highestCZkId);
        this.currentBatchIterator = currentBatch.iterator();

        // Definitely reached the end of pagination
        if(currentBatch.isEmpty()) {
            noMoreChildren = true;
            return false;
        }

        // We fetched a new non-empty batch
        return true;
    }

    @Override
    public PathWithStat next() throws KeeperException, InterruptedException {

        if(!hasNext()) {
            throw new RuntimeException("No more element");
        }

        PathWithStat returnChildren = currentBatchIterator.next();

        highestCZkId = returnChildren.getStat().getCzxid();

        return returnChildren;
    }
}
