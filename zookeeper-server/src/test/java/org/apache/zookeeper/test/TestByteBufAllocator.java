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

package org.apache.zookeeper.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This is a custom ByteBufAllocator that tracks outstanding allocations and
 * crashes the program if any of them are leaked.
 *
 * Never use this class in production, it will cause your server to run out
 * of memory! This is because it holds strong references to all allocated
 * buffers and doesn't release them until checkForLeaks() is called at the
 * end of a unit test.
 *
 * Note: the original code was copied from https://github.com/airlift/drift,
 * with the permission and encouragement of airlift's author (dain). Airlift
 * uses the same apache 2.0 license as Zookeeper so this should be ok.
 *
 * However, the code was modified to take advantage of Netty's built-in
 * leak tracking and make a best effort to print details about buffer leaks.
 *
 */
public class TestByteBufAllocator extends PooledByteBufAllocator {

    private static AtomicReference<TestByteBufAllocator> INSTANCE = new AtomicReference<>(null);

    /**
     * Get the singleton testing allocator.
     * @return the singleton allocator, creating it if one does not exist.
     */
    public static TestByteBufAllocator getInstance() {
        TestByteBufAllocator result = INSTANCE.get();
        if (result == null) {
            ResourceLeakDetector.Level oldLevel = ResourceLeakDetector.getLevel();
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
            INSTANCE.compareAndSet(null, new TestByteBufAllocator(oldLevel));
            result = INSTANCE.get();
        }
        return result;
    }

    /**
     * Destroys the singleton testing allocator and throws an error if any of the
     * buffers allocated by it have been leaked. Attempts to print leak details to
     * standard error before throwing, by using netty's built-in leak tracking.
     * Note that this might not always work, since it only triggers when a buffer
     * is garbage-collected and calling System.gc() does not guarantee that a buffer
     * will actually be GC'ed.
     *
     * This should be called at the end of a unit test's tearDown() method.
     */
    public static void checkForLeaks() {
        TestByteBufAllocator result = INSTANCE.getAndSet(null);
        if (result != null) {
            result.checkInstanceForLeaks();
        }
    }

    private final List<ByteBuf> trackedBuffers = new ArrayList<>();
    private final ResourceLeakDetector.Level oldLevel;

    private TestByteBufAllocator(ResourceLeakDetector.Level oldLevel) {
        super(false);
        this.oldLevel = oldLevel;
    }

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        return track(super.newHeapBuffer(initialCapacity, maxCapacity));
    }

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        return track(super.newDirectBuffer(initialCapacity, maxCapacity));
    }

    @Override
    public CompositeByteBuf compositeHeapBuffer(int maxNumComponents) {
        return track(super.compositeHeapBuffer(maxNumComponents));
    }

    @Override
    public CompositeByteBuf compositeDirectBuffer(int maxNumComponents) {
        return track(super.compositeDirectBuffer(maxNumComponents));
    }

    private synchronized CompositeByteBuf track(CompositeByteBuf byteBuf) {
        trackedBuffers.add(Objects.requireNonNull(byteBuf));
        return byteBuf;
    }

    private synchronized ByteBuf track(ByteBuf byteBuf) {
        trackedBuffers.add(Objects.requireNonNull(byteBuf));
        return byteBuf;
    }

    private void checkInstanceForLeaks() {
        try {
            long referencedBuffersCount = 0;
            synchronized (this) {
                referencedBuffersCount = trackedBuffers.stream().filter(byteBuf -> byteBuf.refCnt() > 0).count();
                // Make tracked buffers eligible for GC
                trackedBuffers.clear();
            }
            // Throw an error if there were any leaked buffers
            if (referencedBuffersCount > 0) {
                // Trigger a GC. This will hopefully (but not necessarily) print
                // details about detected leaks to standard error before the error
                // is thrown.
                System.gc();
                throw new AssertionError("Found a netty ByteBuf leak!");
            }
        } finally {
            ResourceLeakDetector.setLevel(oldLevel);
        }
    }

}
