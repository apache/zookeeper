package org.apache.zookeeper.common;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public interface BatchedBlockingQueue<T> extends BlockingQueue<T> {
    void putAll(T[] a, int offset, int len) throws InterruptedException;

    /**
     * Drain the queue into an array.
     * Wait if there are no items in the queue.
     *
     * @param array
     * @return
     * @throws InterruptedException
     */
    int takeAll(T[] array) throws InterruptedException;

    /**
     * Removes multiple items from the queue.
     *
     * The method returns when either:
     *  1. At least one item is available
     *  2. The timeout expires
     *
     *
     * @param array
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     */
    int pollAll(T[] array, long timeout, TimeUnit unit) throws InterruptedException;

}