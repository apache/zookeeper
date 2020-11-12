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

package org.apache.zookeeper;

import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import org.apache.zookeeper.data.PathWithStat;

/**
 * Iterator over children nodes of a given path.
 */
class ChildrenBatchIterator implements RemoteIterator<PathWithStat> {

    private final ZooKeeper zooKeeper;
    private final String path;
    private final Watcher watcher;
    private final int batchSize;
    private final LinkedList<PathWithStat> childrenQueue;
    private long nextBatchMinZxid;
    private int nextBatchZxidOffset;


    ChildrenBatchIterator(ZooKeeper zooKeeper, String path, Watcher watcher, int batchSize, long minZxid)
            throws KeeperException, InterruptedException {
        this.zooKeeper = zooKeeper;
        this.path = path;
        this.watcher = watcher;
        this.batchSize = batchSize;
        this.nextBatchZxidOffset = 0;
        this.nextBatchMinZxid = minZxid;

        this.childrenQueue = new LinkedList<>();

        List<PathWithStat> firstChildrenBatch = zooKeeper.getChildren(path, watcher, batchSize, nextBatchMinZxid, nextBatchZxidOffset);
        childrenQueue.addAll(firstChildrenBatch);

        updateOffsetsForNextBatch(firstChildrenBatch);
    }

    @Override
    public boolean hasNext() {

        // next() never lets childrenQueue empty unless we iterated over all children
        return !childrenQueue.isEmpty();
    }

    @Override
    public PathWithStat next() throws KeeperException, InterruptedException, NoSuchElementException {

        if (!hasNext()) {
            throw new NoSuchElementException("No more children");
        }

        // If we're down to the last element, backfill before returning it
        if (childrenQueue.size() == 1) {

            List<PathWithStat> childrenBatch = zooKeeper.getChildren(path, watcher, batchSize, nextBatchMinZxid, nextBatchZxidOffset);
            childrenQueue.addAll(childrenBatch);

            updateOffsetsForNextBatch(childrenBatch);
        }

        PathWithStat returnChildren = childrenQueue.pop();

        return returnChildren;
    }

    /**
     * Prepare minZxid and zkidOffset for the next batch request based on the children returned in the current
     */
    private void updateOffsetsForNextBatch(List<PathWithStat> children) {

        for (PathWithStat child : children) {
            long childZxid = child.getStat().getCzxid();

            if (nextBatchMinZxid == childZxid) {
                ++nextBatchZxidOffset;
            } else {
                nextBatchZxidOffset = 1;
                nextBatchMinZxid = childZxid;
            }
        }
    }
}