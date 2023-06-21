/*
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

package org.apache.zookeeper.common;

import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


/**
 * This implements a {@link BlockingQueue} backed by an array with fixed capacity.
 *
 * <p>This queue only allows 1 consumer thread to dequeue items and multiple producer threads.
 */
public class BatchedArrayBlockingQueue<T>
        extends AbstractQueue<T>
        implements BlockingQueue<T>, BatchedBlockingQueue<T> {

    private final ReentrantLock lock = new ReentrantLock();

    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    private final int capacity;
    private final T[] data;

    private int size;

    private int consumerIdx;
    private int producerIdx;

    @SuppressWarnings("unchecked")
    public BatchedArrayBlockingQueue(int capacity) {
        this.capacity = capacity;
        this.data = (T[]) new Object[this.capacity];
    }

    private T dequeueOne() {
        T item = data[consumerIdx];
        data[consumerIdx] = null;
        if (++consumerIdx == capacity) {
            consumerIdx = 0;
        }

        if (size-- == capacity) {
            notFull.signalAll();
        }

        return item;
    }

    private void enqueueOne(T item) {
        data[producerIdx] = item;
        if (++producerIdx == capacity) {
            producerIdx = 0;
        }

        if (size++ == 0) {
            notEmpty.signalAll();
        }
    }

    @Override
    public T poll() {
        lock.lock();

        try {
            if (size == 0) {
                return null;
            }

            return dequeueOne();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T peek() {
        lock.lock();

        try {
            if (size == 0) {
                return null;
            }

            return data[consumerIdx];
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean offer(T e) {
        lock.lock();

        try {
            if (size == capacity) {
                return false;
            }

            enqueueOne(e);

            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(T e) throws InterruptedException {
        lock.lockInterruptibly();

        try {
            while (size == capacity) {
                notFull.await();
            }

            enqueueOne(e);
        } finally {
            lock.unlock();
        }
    }

    public int putAll(List<T> c) throws InterruptedException {
        lock.lockInterruptibly();

        try {
            while (size == capacity) {
                notFull.await();
            }

            int availableCapacity = capacity - size;

            int toInsert = Math.min(availableCapacity, c.size());

            int producerIdx = this.producerIdx;
            for (int i = 0; i < toInsert; i++) {
                data[producerIdx] = c.get(i);
                if (++producerIdx == capacity) {
                    producerIdx = 0;
                }
            }

            this.producerIdx = producerIdx;

            if (size == 0) {
                notEmpty.signalAll();
            }

            size += toInsert;

            return toInsert;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void putAll(T[] a, int offset, int len) throws InterruptedException {
        while (len > 0) {
            int published = internalPutAll(a, offset, len);
            offset += published;
            len -= published;
        }
    }

    private int internalPutAll(T[] a, int offset, int len) throws InterruptedException {
        lock.lockInterruptibly();

        try {
            while (size == capacity) {
                notFull.await();
            }

            int availableCapacity = capacity - size;
            int toInsert = Math.min(availableCapacity, len);
            int producerIdx = this.producerIdx;

            // First span
            int firstSpan = Math.min(toInsert, capacity - producerIdx);
            System.arraycopy(a, offset, data, producerIdx, firstSpan);
            producerIdx += firstSpan;

            int secondSpan = toInsert - firstSpan;
            if (secondSpan > 0) {
                System.arraycopy(a, offset + firstSpan, data, 0, secondSpan);
                producerIdx = secondSpan;
            }

            if (producerIdx == capacity) {
                producerIdx = 0;
            }

            this.producerIdx = producerIdx;

            if (size == 0) {
                notEmpty.signalAll();
            }

            size += toInsert;
            return toInsert;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean offer(T e, long timeout, TimeUnit unit) throws InterruptedException {
        long remainingTimeNanos = unit.toNanos(timeout);

        lock.lockInterruptibly();
        try {
            while (size == capacity) {
                if (remainingTimeNanos <= 0L) {
                    return false;
                }

                remainingTimeNanos = notFull.awaitNanos(remainingTimeNanos);
            }

            enqueueOne(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T take() throws InterruptedException {
        lock.lockInterruptibly();

        try {
            while (size == 0) {
                notEmpty.await();
            }

            return dequeueOne();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long remainingTimeNanos = unit.toNanos(timeout);

        lock.lockInterruptibly();
        try {
            while (size == 0) {
                if (remainingTimeNanos <= 0L) {
                    return null;
                }

                remainingTimeNanos = notEmpty.awaitNanos(remainingTimeNanos);
            }

            return dequeueOne();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public synchronized int remainingCapacity() {
        return capacity - size;
    }

    @Override
    public int drainTo(Collection<? super T> c) {
        return drainTo(c, capacity);
    }

    @Override
    public int drainTo(Collection<? super T> c, int maxElements) {
        lock.lock();
        try {
            int toDrain = Math.min(size, maxElements);

            int consumerIdx = this.consumerIdx;
            for (int i = 0; i < toDrain; i++) {
                T item = data[consumerIdx];
                data[consumerIdx] = null;
                c.add(item);

                if (++consumerIdx == capacity) {
                    consumerIdx = 0;
                }
            }

            this.consumerIdx = consumerIdx;
            if (size == capacity) {
                notFull.signalAll();
            }

            size -= toDrain;
            return toDrain;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int takeAll(T[] array) throws InterruptedException {
        return internalTakeAll(array, true, 0, TimeUnit.SECONDS);
    }

    @Override
    public int pollAll(T[] array, long timeout, TimeUnit unit) throws InterruptedException {
        return internalTakeAll(array, false, timeout, unit);
    }

    private int internalTakeAll(T[] array, boolean waitForever, long timeout, TimeUnit unit)
            throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (size == 0) {
                if (waitForever) {
                    notEmpty.await();
                } else {
                    if (!notEmpty.await(timeout, unit)) {
                        return 0;
                    }
                }
            }

            int toDrain = Math.min(size, array.length);

            int consumerIdx = this.consumerIdx;

            // First span
            int firstSpan = Math.min(toDrain, capacity - consumerIdx);
            System.arraycopy(data, consumerIdx, array, 0, firstSpan);
            Arrays.fill(data, consumerIdx, consumerIdx + firstSpan, null);
            consumerIdx += firstSpan;

            int secondSpan = toDrain - firstSpan;
            if (secondSpan > 0) {
                System.arraycopy(data, 0, array, firstSpan, secondSpan);
                Arrays.fill(data, 0, secondSpan, null);
                consumerIdx = secondSpan;
            }

            if (consumerIdx == capacity) {
                consumerIdx = 0;
            }
            this.consumerIdx = consumerIdx;
            if (size == capacity) {
                notFull.signalAll();
            }

            size -= toDrain;
            return toDrain;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            while (size > 0) {
                dequeueOne();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();

        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Iterator<T> iterator() {
        throw new UnsupportedOperationException();
    }
}
