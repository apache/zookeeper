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

package org.apache.zookeeper.util;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A bounded blocking queue backed by an array. This queue orders elements FIFO
 * (first-in-first-out). The head of the queue is that element that has been on
 * the queue the longest time. The tail of the queue is that element that has
 * been on the queue the shortest time. New elements are inserted at the tail of
 * the queue, and the queue retrieval operations obtain elements at the head of
 * the queue. If the queue is full, the head of the queue (the oldest element)
 * will be removed to make room for the newest element.
 */
public class CircularBlockingQueue<E> implements BlockingQueue<E> {

  private static final Logger LOG = LoggerFactory.getLogger(CircularBlockingQueue.class);

  /** Main lock guarding all access */
  private final ReentrantLock lock;

  /** Condition for waiting takes */
  private final Condition notEmpty;

  /** The array-backed queue */
  private final ArrayDeque<E> queue;

  private final int maxSize;

  private long droppedCount;

  public CircularBlockingQueue(int queueSize) {
    this.queue = new ArrayDeque<>(queueSize);
    this.maxSize = queueSize;

    this.lock =  new ReentrantLock();
    this.notEmpty = this.lock.newCondition();
    this.droppedCount = 0L;
  }

  /**
   * This method differs from {@link BlockingQueue#offer(Object)} in that it
   * will remove the oldest queued element (the element at the front of the
   * queue) in order to make room for any new elements if the queue is full.
   *
   * @param e the element to add
   * @return true since it will make room for any new elements if required
   */
  @Override
  public boolean offer(E e) {
      Objects.requireNonNull(e);
      final ReentrantLock lock = this.lock;
      lock.lock();
      try {
          if (this.queue.size() == this.maxSize) {
              final E discard = this.queue.remove();
              this.droppedCount++;
              LOG.debug("Queue is full. Discarding oldest element [count={}]: {}",
                  this.droppedCount, discard);
          }
          this.queue.add(e);
          this.notEmpty.signal();
      } finally {
          lock.unlock();
      }
      return true;
  }

  @Override
  public E poll(long timeout, TimeUnit unit) throws InterruptedException {
      long nanos = unit.toNanos(timeout);
      final ReentrantLock lock = this.lock;
      lock.lockInterruptibly();
      try {
          while (this.queue.isEmpty()) {
              if (nanos <= 0) {
                  return null;
              }
              nanos = this.notEmpty.awaitNanos(nanos);
          }
          return this.queue.poll();
      } finally {
          lock.unlock();
      }
  }

  @Override
  public E take() throws InterruptedException {
      final ReentrantLock lock = this.lock;
      lock.lockInterruptibly();
      try {
          while (this.queue.isEmpty()) {
            this.notEmpty.await();
          }
          return this.queue.poll();
      } finally {
          lock.unlock();
      }
  }

  @Override
  public boolean isEmpty() {
    final ReentrantLock lock = this.lock;
    lock.lock();
    try {
      return this.queue.isEmpty();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int size() {
    final ReentrantLock lock = this.lock;
    lock.lock();
    try {
      return this.queue.size();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the number of elements that were dropped from the queue because the
   * queue was full when a new element was offered.
   *
   * @return The number of elements dropped (lost) from the queue
   */
  public long getDroppedCount() {
    return this.droppedCount;
  }

  /**
   * For testing purposes only.
   *
   * @return True if a thread is blocked waiting for a new element to be offered
   *         to the queue
   */
  boolean isConsumerThreadBlocked() {
    final ReentrantLock lock = this.lock;
    lock.lock();
    try {
      return lock.getWaitQueueLength(this.notEmpty) > 0;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int drainTo(Collection<? super E> c) {
    throw new UnsupportedOperationException();
  }


  @Override
  public E poll() {
    throw new UnsupportedOperationException();
  }

  @Override
  public E element() {
    throw new UnsupportedOperationException();
  }

  @Override
  public E peek() {
    throw new UnsupportedOperationException();
  }

  @Override
  public E remove() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean addAll(Collection<? extends E> arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clear() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean containsAll(Collection<?> arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Iterator<E> iterator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean removeAll(Collection<?> arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean retainAll(Collection<?> arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object[] toArray() {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T[] toArray(T[] arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean add(E e) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean contains(Object o) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int drainTo(Collection<? super E> c, int maxElements) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean offer(E e, long timeout, TimeUnit unit)
      throws InterruptedException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void put(E e) throws InterruptedException {
    throw new UnsupportedOperationException();
  }

  @Override
  public int remainingCapacity() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean remove(Object o) {
    throw new UnsupportedOperationException();
  }

}
