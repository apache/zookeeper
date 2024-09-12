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

package org.apache.zookeeper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class BlockingQueueWatcher implements Watcher {
    private final BlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<>();

    @Override
    public void process(WatchedEvent event) {
        assertTrue(events.add(event));
    }

    public WatchedEvent pollEvent(Duration timeout) throws InterruptedException {
        return events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Format {@link Duration} with suffix "ms" or "s".
     *
     * <p>I guess {@link Duration#toString()} is verbose and not intuitive.
     */
    private String formatTimeout(Duration timeout) {
        long millis = timeout.toMillis();
        if (millis < TimeUnit.SECONDS.toMillis(1)) {
            return millis + "ms";
        }
        long secs = millis / TimeUnit.SECONDS.toMillis(1);
        millis %= TimeUnit.SECONDS.toMillis(1);
        // We are test code, second unit is large enough.
        if (millis == 0) {
            return secs + "s";
        }
        return secs + "s" + millis + "ms";
    }

    private Supplier<String> noEventMessage(Duration timeout) {
        return () -> String.format("no event after %s", formatTimeout(timeout));
    }

    public WatchedEvent takeEvent(Duration timeout) throws InterruptedException {
        WatchedEvent event = pollEvent(timeout);
        assertNotNull(event, noEventMessage(timeout));
        return event;
    }
}
