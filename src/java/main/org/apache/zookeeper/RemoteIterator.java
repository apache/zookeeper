package org.apache.zookeeper;

/**
 * Iterator that involves server communication, and as such can throw
 */
public interface RemoteIterator<E> {

    boolean hasNext() throws InterruptedException, KeeperException;

    E next() throws InterruptedException, KeeperException;
}
