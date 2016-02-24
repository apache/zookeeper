package org.apache.zookeeper;

import java.util.NoSuchElementException;

/**
 * An iterator over a collection whose elements need to be fetched remotely.
 */
public interface RemoteIterator<E> {

    /**
     * Returns true if the iterator has more elements.
     * @return true if the iterator has more elements, false otherwise.
     */
    boolean hasNext();

    /**
     * Returns the next element in the iteration.
     * @return the next element in the iteration.
     * @throws InterruptedException if the thread is interrupted
     * @throws KeeperException if an error is encountered server-side
     * @throws NoSuchElementException if the iteration has no more elements
     */
    E next() throws InterruptedException, KeeperException, NoSuchElementException;
}
