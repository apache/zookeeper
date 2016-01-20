package org.apache.zookeeper;

import org.apache.zookeeper.data.PathWithStat;

import java.util.LinkedList;
import java.util.List;

/**
 * Iterator over children nodes of a given path.
 */
class ChildrenBatchIterator implements RemoteIterator<PathWithStat> {

    private final ZooKeeper zooKeeper;
    private final String path;
    private final Watcher watcher;
    private final int batchSize;
    private final LinkedList<PathWithStat> childrenQueue;


    ChildrenBatchIterator(ZooKeeper zooKeeper, String path, Watcher watcher, int batchSize, int minZxid)
            throws KeeperException, InterruptedException {
        this.zooKeeper = zooKeeper;
        this.path = path;
        this.watcher = watcher;
        this.batchSize = batchSize;

        this.childrenQueue = new LinkedList<>();

        List<PathWithStat> firstChildrenBatch = zooKeeper.getChildren(path, watcher, batchSize, minZxid);
        childrenQueue.addAll(firstChildrenBatch);
    }

    @Override
    public boolean hasNext() {

        // next() never lets childrenQueue empty unless we iterated over all children
        return ! childrenQueue.isEmpty();
    }

    @Override
    public PathWithStat next() throws KeeperException, InterruptedException {

        if(!hasNext()) {
            throw new RuntimeException("No more element");
        }

        // If we're down to the last element, backfill before returning it
        if(childrenQueue.size() == 1) {

            long highestCZxId = childrenQueue.get(0).getStat().getCzxid();

            List<PathWithStat> childrenBatch = zooKeeper.getChildren(path, watcher, batchSize, highestCZxId);
            childrenQueue.addAll(childrenBatch);

        }

        PathWithStat returnChildren = childrenQueue.pop();

        return returnChildren;
    }
}
